package ci.sysde.reporting;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import ci.sysde.common.BusinessException;
import ci.sysde.referentiel.SecteursVisibles;
import ci.sysde.reporting.ReportingDtos.DashboardData;
import ci.sysde.reporting.ReportingDtos.Deadline;
import ci.sysde.reporting.ReportingDtos.MonthVisits;
import ci.sysde.reporting.ReportingDtos.SectorRef;
import ci.sysde.reporting.ReportingDtos.VisitBreakdown;
import ci.sysde.security.Acteur;
import ci.sysde.security.PerimetreService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tableau de bord (AD, SU, CD, CS) : taux et décompte des <b>visites</b> validées sur 12 mois, échéances de contrats, secteurs de l'opérateur.
 * Même règles de calcul que le reporting (activités validées, mois du premier jour). L'étendue vient du compte, jamais de la requête ;
 * seuls {@code filterOperator} (acronyme, AD et SU) et {@code filterSector} (code, AD, SU et CD) affinent.
 * <p>Pas fournis, faute de source : la production de caoutchouc, les planteurs et les hectares (il faudrait une API d'agrégats du tiers
 * des plantations : lire toutes les plantations à chaque ouverture serait trop lourd) et les rapports mensuels des opérateurs.
 */
@Service
class DashboardService {

    private static final int MOIS = 12;
    private static final int ECHEANCE_JOURS = 180;

    private record Portee(String compte, String contrat, Map<String, Object> params) {
    }

    private final JdbcClient jdbc;
    private final PerimetreService perimetres;
    private final SecteursVisibles visibles;

    DashboardService(JdbcClient jdbc, PerimetreService perimetres, SecteursVisibles visibles) {
        this.jdbc = jdbc;
        this.perimetres = perimetres;
        this.visibles = visibles;
    }

    @Transactional(readOnly = true)
    DashboardData lire(Acteur acteur, String filtreOperateur, String filtreSecteur) {
        if (!acteur.estProfil(Acteur.ADMINISTRATEUR, Acteur.SUPERVISEUR, Acteur.CHEF_DEPARTEMENT, Acteur.CHEF_SECTEUR)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ACCES_REFUSE", "Le tableau de bord est réservé à l'administrateur, au superviseur et aux chefs.");
        }
        LocalDate aujourdhui = LocalDate.now(ZoneOffset.UTC);
        YearMonth courant = YearMonth.from(aujourdhui);
        YearMonth premier = courant.minusMonths(MOIS - 1);
        Portee p = portee(acteur, filtreOperateur, filtreSecteur);

        Map<String, int[]> parMois = new LinkedHashMap<>();                       // mois → prévues, réalisées, en retard
        for (int i = 0; i < MOIS; i++) {
            parMois.put(premier.plusMonths(i).toString(), new int[3]);
        }
        Map<String, Object> params = new HashMap<>(p.params());
        params.put("du", premier.atDay(1));
        params.put("au", courant.plusMonths(1).atDay(1));
        params.put("seuil", 15);                                                  // inutile ici (anomalies non lues) mais attendu par la requête
        params.put("profils", ReportingService.COLLABORATEURS);
        jdbc.sql("select to_char(t.prevue_le, 'YYYY-MM') as mois, count(*) as prevues,"
                        + " count(*) filter (where t.realisee_le is not null) as realisees,"
                        + " count(*) filter (where t.realisee_le is null and t.prevue_le < current_date) as retard"
                        + " from (" + ReportingService.ACTIVITES_DE_LA_PERIODE + ") t join iam.compte c on c.id = t.compte_id"
                        + " join planning.activite a on a.id = t.activite_id"
                        + " where a.categorie = 'VISITE' and c.statut = 'ACTIF' and c.profil in (:profils) and " + p.compte() + " group by 1")
                .params(params)
                .query((rs, n) -> {
                    int[] m = parMois.get(rs.getString("mois"));
                    if (m != null) {
                        m[0] = rs.getInt("prevues");
                        m[1] = rs.getInt("realisees");
                        m[2] = rs.getInt("retard");
                    }
                    return 0;
                }).list();
        List<MonthVisits> mois = new ArrayList<>();
        parMois.forEach((m, c) -> mois.add(new MonthVisits(m, c[0], c[1], c[0] == 0 ? null : (double) c[1] / c[0])));
        int[] c = parMois.get(courant.toString());
        return new DashboardData(aujourdhui, mois, new VisitBreakdown(c[0], c[1], c[2]), echeances(p, aujourdhui), secteurs(acteur, p),
                List.of("PRODUCTION", "PLANTEURS", "HECTARES", "RAPPORTS"));
    }

    // ------------------------------------------------------------------ étendue

    private Portee portee(Acteur acteur, String filtreOperateur, String filtreSecteur) {
        Map<String, Object> params = new HashMap<>();
        String compte;
        String contrat;
        if (acteur.estProfil(Acteur.CHEF_SECTEUR)) {
            Set<UUID> secteurs = perimetres.de(acteur).secteursEntiers();
            params.put("pop", acteur.operateurId());
            params.put("secteurs", secteurs.isEmpty() ? Set.of(new UUID(0, 0)) : secteurs);
            compte = "c.operateur_id = :pop and exists (select 1 from iam.affectation f where f.compte_id = c.id and f.fin is null"
                    + " and f.supprime_le is null and f.secteur_id in (:secteurs))";
            contrat = "k.operateur_id = :pop and exists (select 1 from gestion.attribution at where at.contrat_id = k.id"
                    + " and at.supprime_le is null and at.secteur_id in (:secteurs))";
            return new Portee(compte, contrat, params);
        }
        if (acteur.estProfil(Acteur.CHEF_DEPARTEMENT)) {
            params.put("pop", acteur.operateurId());
            compte = "c.operateur_id = :pop";
            contrat = "k.operateur_id = :pop";
        } else {
            compte = "exists (select 1 from referentiel.operateur o where o.id = c.operateur_id and o.type = 'TECHNIQUE')";
            contrat = "true";
            if (filtreOperateur != null && !filtreOperateur.isBlank()) {
                UUID operateur = jdbc.sql("select id from referentiel.operateur where acronyme = :a and type = 'TECHNIQUE' and supprime_le is null")
                        .param("a", filtreOperateur.strip()).query(UUID.class).optional()
                        .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST, "REQUETE_INVALIDE", "Opérateur inconnu : « " + filtreOperateur + " »."));
                params.put("fop", operateur);
                compte += " and c.operateur_id = :fop";
                contrat += " and k.operateur_id = :fop";
            }
        }
        if (filtreSecteur != null && !filtreSecteur.isBlank()) {
            UUID secteur = jdbc.sql("select id from referentiel.secteur where code = :c and supprime_le is null").param("c", filtreSecteur.strip())
                    .query(UUID.class).optional()
                    .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST, "REQUETE_INVALIDE", "Secteur inconnu : « " + filtreSecteur + " »."));
            if (acteur.estProfil(Acteur.CHEF_DEPARTEMENT) && !visibles.de(acteur).orElse(Set.of()).contains(secteur)) {
                throw new BusinessException(HttpStatus.FORBIDDEN, "ACCES_REFUSE", "Ce secteur n'est pas dans votre périmètre.");
            }
            params.put("fs", secteur);
            compte += " and exists (select 1 from iam.affectation f where f.compte_id = c.id and f.fin is null and f.supprime_le is null and f.secteur_id = :fs)";
            contrat += " and exists (select 1 from gestion.attribution at where at.contrat_id = k.id and at.supprime_le is null and at.secteur_id = :fs)";
        }
        return new Portee(compte, contrat, params);
    }

    /** Fins de contrat dans les 180 jours. */
    private List<Deadline> echeances(Portee p, LocalDate aujourdhui) {
        Map<String, Object> params = new HashMap<>(p.params());
        params.put("debut", aujourdhui);
        params.put("fin", aujourdhui.plusDays(ECHEANCE_JOURS));
        return jdbc.sql("select k.id, k.reference, k.date_fin, o.acronyme from gestion.contrat k join referentiel.operateur o on o.id = k.operateur_id"
                        + " where k.supprime_le is null and k.date_fin >= :debut and k.date_fin <= :fin and " + p.contrat()
                        + " order by k.date_fin, k.reference")
                .params(params)
                .query((rs, n) -> new Deadline(rs.getObject("id", UUID.class), "Fin du contrat " + rs.getString("reference"),
                        rs.getString("acronyme"), rs.getObject("date_fin", LocalDate.class), "CONTRAT", null))
                .list();
    }

    /** Secteurs de l'opérateur, pour le filtre du chef de département (aussi pour AD et SU qui filtrent sur un opérateur). */
    private List<SectorRef> secteurs(Acteur acteur, Portee p) {
        if (!acteur.estProfil(Acteur.CHEF_DEPARTEMENT) && !p.params().containsKey("fop")) {
            return null;
        }
        UUID operateur = acteur.estProfil(Acteur.CHEF_DEPARTEMENT) ? acteur.operateurId() : (UUID) p.params().get("fop");
        return jdbc.sql("""
                        select distinct s.code, s.nom from referentiel.secteur s join gestion.attribution a on a.secteur_id = s.id
                         where a.operateur_id = :op and a.supprime_le is null and s.supprime_le is null order by s.code
                        """)
                .param("op", operateur).query((rs, n) -> new SectorRef(rs.getString("code"), rs.getString("nom"))).list();
    }
}
