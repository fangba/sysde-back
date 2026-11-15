package ci.sysde.reporting;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import ci.sysde.common.BusinessException;
import ci.sysde.reporting.ReportingDtos.Collaborator;
import ci.sysde.reporting.ReportingDtos.DeviceSync;
import ci.sysde.reporting.ReportingDtos.Plans;
import ci.sysde.reporting.ReportingDtos.ReportActivity;
import ci.sysde.reporting.ReportingDtos.ReportOperator;
import ci.sysde.reporting.ReportingDtos.ReportRow;
import ci.sysde.reporting.ReportingDtos.ReportSector;
import ci.sysde.reporting.ReportingDtos.Synthesis;
import ci.sysde.security.Acteur;
import ci.sysde.security.PerimetreService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reporting à trois niveaux, mêmes règles de calcul : chef secteur (ses collaborateurs), chef de département (ses secteurs),
 * administrateur et superviseur (les opérateurs). Les chiffres sont calculés <b>côté serveur</b> (un opérateur national, c'est des
 * dizaines de milliers d'activités par mois) ; seule la liste du chef secteur reste brute, car bornée.
 *
 * <p>{@code HORS_SECTEUR} : la position d'une activité réalisée sur une ligne à lot est hors du contour du secteur de ce lot, au-delà de
 * 50 m ou de la précision déclarée de la position ; sans contour de secteur importé, jamais signalée.
 *
 * <p>Sont comptées les activités <b>validées</b> : lignes d'un plan validé, ou d'une activité validée une à une. Un brouillon ou un
 * plan soumis n'engage personne. Une activité compte dans le mois de son premier jour. Collaborateurs = CF, CO, FS, MO, ES actifs
 * (ni le chef secteur, ni la secrétaire).
 */
@Service
class ReportingService {

    static final List<String> COLLABORATEURS = List.of("CF", "CO", "FS", "MO", "ES");
    private static final int ACTIVITES_MAX = 5000;
    private static final int MOIS_MAX = 24;

    /** Entité d'une synthèse : ce qui est regroupé et ce qui est visible. */
    private enum Niveau {
        SECTEUR("c.id", "", """
                c.operateur_id = :pop and exists (select 1 from iam.affectation f where f.compte_id = c.id and f.fin is null
                       and f.supprime_le is null and f.secteur_id in (:secteurs))"""),
        DEPARTEMENT("af.secteur_id", """
                join (select distinct compte_id, secteur_id from iam.affectation where fin is null and supprime_le is null) af
                  on af.compte_id = c.id""", "c.operateur_id = :pop"),
        NATIONAL("c.operateur_id", "join referentiel.operateur o on o.id = c.operateur_id", "o.type = 'TECHNIQUE'");

        final String entite;
        final String jointures;
        final String portee;

        Niveau(String entite, String jointures, String portee) {
            this.entite = entite;
            this.jointures = jointures;
            this.portee = portee;
        }
    }

    /** Activités validées de la période, avec leur réalisation et leur anomalie éventuelle (colonne {@code anomalie}). */
    private static final String ACTIVITES_DE_LA_PERIODE = """
            select l.id as ligne_id, p.compte_id, l.activite_id, l.date_debut as prevue_le,
                   case when r.statut = 'TERMINEE' then (r.debut_le at time zone 'UTC')::date end as realisee_le,
                   case
                     when r.id is null or r.statut <> 'TERMINEE' then null
                     when r.latitude is null then 'SANS_GEO'
                     when sc.geom is not null
                          and not ST_Covers(sc.geom, ST_SetSRID(ST_MakePoint(r.longitude, r.latitude), 4326))
                          and not ST_DWithin(sc.geom::geography, ST_SetSRID(ST_MakePoint(r.longitude, r.latitude), 4326)::geography,
                                             greatest(coalesce(r.precision_m, 0), 50)) then 'HORS_SECTEUR'
                     when l.plantation_ref is not null and count(*) filter (where r.statut = 'TERMINEE')
                            over (partition by p.compte_id, l.activite_id, l.plantation_ref, (r.debut_le at time zone 'UTC')::date) > 1 then 'DOUBLON'
                     when extract(epoch from (r.fin_le - r.debut_le)) / 60 < :seuil then 'COURTE'
                   end as anomalie
              from planning.ligne_planning l
              join planning.plan_annuel p on p.id = l.plan_id and p.supprime_le is null
              left join planning.realisation r on r.ligne_id = l.id and r.supprime_le is null
              left join referentiel.lot lo on lo.id = l.lot_id
              left join referentiel.secteur sc on sc.id = lo.secteur_id
             where l.supprime_le is null and l.date_debut >= :du and l.date_debut < :au
               and (p.statut = 'VALIDE' or exists (select 1 from planning.soumission_activite s
                        where s.plan_id = p.id and s.activite_id = l.activite_id and s.statut = 'VALIDE'))""";

    private final JdbcClient jdbc;
    private final PerimetreService perimetres;

    ReportingService(JdbcClient jdbc, PerimetreService perimetres) {
        this.jdbc = jdbc;
        this.perimetres = perimetres;
    }

    // ------------------------------------------------------------------ niveau secteur (chef secteur)

    @Transactional(readOnly = true)
    List<Collaborator> collaborateursDuSecteur(Acteur acteur) {
        Set<UUID> secteurs = secteursDuChefSecteur(acteur);
        if (secteurs.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        select c.id, u.nom, u.prenoms, c.profil
                          from iam.compte c join iam.utilisateur u on u.id = c.utilisateur_id
                         where c.statut = 'ACTIF' and c.supprime_le is null and c.operateur_id = :pop and c.profil in (:profils)
                           and exists (select 1 from iam.affectation f where f.compte_id = c.id and f.fin is null
                                          and f.supprime_le is null and f.secteur_id in (:secteurs))
                         order by u.nom, u.prenoms, c.id
                        """)
                .param("pop", acteur.operateurId()).param("profils", COLLABORATEURS).param("secteurs", secteurs)
                .query((rs, n) -> new Collaborator(rs.getObject("id", UUID.class), rs.getString("nom") + " " + rs.getString("prenoms"),
                        rs.getString("profil").strip()))
                .list();
    }

    /** Un collaborateur par ligne, y compris ceux dont aucun appareil ne s'est signalé (c'est l'information utile). */
    @Transactional(readOnly = true)
    List<DeviceSync> synchronisationDuSecteur(Acteur acteur) {
        List<Collaborator> equipe = collaborateursDuSecteur(acteur);
        if (equipe.isEmpty()) {
            return List.of();
        }
        var parCompte = new java.util.HashMap<UUID, DeviceSync>();
        jdbc.sql("""
                        select compte_id, count(*) as appareils, max(derniere_synchro_le) as derniere, sum(en_attente) as en_attente
                          from iam.appareil where compte_id in (:comptes) group by compte_id
                        """)
                .param("comptes", equipe.stream().map(Collaborator::id).toList())
                .query((rs, n) -> {
                    UUID id = rs.getObject("compte_id", UUID.class);
                    parCompte.put(id, new DeviceSync(id, null, null, rs.getInt("appareils"),
                            rs.getObject("derniere", java.time.OffsetDateTime.class), rs.getLong("en_attente")));
                    return id;
                }).list();
        return equipe.stream().map(c -> {
            DeviceSync d = parCompte.get(c.id());
            return d == null ? new DeviceSync(c.id(), c.name(), c.profile(), 0, null, 0)
                    : new DeviceSync(c.id(), c.name(), c.profile(), d.devices(), d.lastSyncAt(), d.pending());
        }).toList();
    }

    /** Liste brute des activités du secteur (au plus 5 000 : au-delà, réduisez la période). */
    @Transactional(readOnly = true)
    List<ReportActivity> activitesDuSecteur(Acteur acteur, String du, String au) {
        Set<UUID> secteurs = secteursDuChefSecteur(acteur);
        Periode periode = periode(du, au);
        if (secteurs.isEmpty()) {
            return List.of();
        }
        List<ReportActivity> activites = jdbc.sql("select t.ligne_id, t.compte_id, a.id as activite_id, a.libelle, a.categorie, t.prevue_le, t.realisee_le, t.anomalie from ("
                        + ACTIVITES_DE_LA_PERIODE + ") t join iam.compte c on c.id = t.compte_id join planning.activite a on a.id = t.activite_id"
                        + " where c.statut = 'ACTIF' and c.profil in (:profils) and " + Niveau.SECTEUR.portee
                        + " order by t.prevue_le, t.ligne_id limit :max")
                .params(periode.params(seuil())).param("pop", acteur.operateurId()).param("profils", COLLABORATEURS).param("secteurs", secteurs)
                .param("max", ACTIVITES_MAX + 1)
                .query((rs, n) -> new ReportActivity(rs.getObject("ligne_id", UUID.class), rs.getObject("compte_id", UUID.class),
                        rs.getObject("activite_id", UUID.class), rs.getString("libelle"), rs.getString("categorie"),
                        rs.getObject("prevue_le", LocalDate.class), rs.getObject("realisee_le", LocalDate.class), rs.getString("anomalie")))
                .list();
        if (activites.size() > ACTIVITES_MAX) {
            throw invalide("Plus de " + ACTIVITES_MAX + " activités : réduisez la période.");
        }
        return activites;
    }

    @Transactional(readOnly = true)
    Synthesis syntheseDuSecteur(Acteur acteur, String du, String au) {
        Set<UUID> secteurs = secteursDuChefSecteur(acteur);
        Periode periode = periode(du, au);
        List<ReportRow> entites = collaborateursDuSecteur(acteur).stream()
                .map(c -> new Entite(c.id(), c.name())).map(e -> vide(e)).toList();
        Map<String, Object> params = new HashMap<>(periode.params(seuil()));
        params.put("pop", acteur.operateurId());
        params.put("secteurs", secteurs.isEmpty() ? Set.of(new UUID(0, 0)) : secteurs);
        return synthese(Niveau.SECTEUR, periode, params, entites);
    }

    // ------------------------------------------------------------------ niveau département (chef de département)

    @Transactional(readOnly = true)
    List<ReportSector> secteursDuDepartement(Acteur acteur, Integer annee) {
        exigerProfil(acteur, "Le reporting par secteur est réservé au chef de département.", Acteur.CHEF_DEPARTEMENT);
        int an = annee == null ? LocalDate.now(ZoneOffset.UTC).getYear() : annee;
        return jdbc.sql("""
                        select s.id, s.code, s.nom,
                               (select u.nom || ' ' || u.prenoms from iam.compte cs join iam.utilisateur u on u.id = cs.utilisateur_id
                                  join iam.affectation f on f.compte_id = cs.id and f.fin is null and f.supprime_le is null
                                 where cs.profil = 'CS' and cs.statut = 'ACTIF' and cs.operateur_id = :pop and f.secteur_id = s.id
                                 order by cs.matricule limit 1) as chef,
                               (select count(distinct c.id) from iam.compte c join iam.affectation f on f.compte_id = c.id and f.fin is null and f.supprime_le is null
                                 where f.secteur_id = s.id and c.operateur_id = :pop and c.statut = 'ACTIF' and c.supprime_le is null and c.profil in (:profils)) as collaborateurs,
                               (select count(distinct p.compte_id) from planning.plan_annuel p join iam.compte c on c.id = p.compte_id
                                  join iam.affectation f on f.compte_id = c.id and f.fin is null and f.supprime_le is null
                                 where f.secteur_id = s.id and c.operateur_id = :pop and c.statut = 'ACTIF' and c.profil in (:profils)
                                   and p.annee = :an and p.supprime_le is null and p.statut = 'VALIDE') as valides,
                               (select count(distinct p.compte_id) from planning.plan_annuel p join iam.compte c on c.id = p.compte_id
                                  join iam.affectation f on f.compte_id = c.id and f.fin is null and f.supprime_le is null
                                 where f.secteur_id = s.id and c.operateur_id = :pop and c.statut = 'ACTIF' and c.profil in (:profils)
                                   and p.annee = :an and p.supprime_le is null and p.statut = 'SOUMIS') as soumis
                          from referentiel.secteur s
                         where s.supprime_le is null and exists (select 1 from gestion.attribution a join gestion.contrat ct on ct.id = a.contrat_id
                                where a.secteur_id = s.id and a.operateur_id = :pop and a.supprime_le is null and ct.supprime_le is null
                                  and ct.periode @> current_date)
                         order by s.code
                        """)
                .param("pop", acteur.operateurId()).param("profils", COLLABORATEURS).param("an", an)
                .query((rs, n) -> {
                    int total = rs.getInt("collaborateurs");
                    int valides = rs.getInt("valides");
                    int soumis = rs.getInt("soumis");
                    return new ReportSector(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("nom"), rs.getString("chef"),
                            total, new Plans(valides, soumis, Math.max(0, total - valides - soumis)));
                }).list();
    }

    @Transactional(readOnly = true)
    Synthesis syntheseDuDepartement(Acteur acteur, String du, String au) {
        exigerProfil(acteur, "Le reporting par secteur est réservé au chef de département.", Acteur.CHEF_DEPARTEMENT);
        Periode periode = periode(du, au);
        List<ReportRow> entites = secteursDuDepartement(acteur, null).stream()
                .map(s -> vide(new Entite(s.id(), s.code() + " — " + s.name()))).toList();
        Map<String, Object> params = new HashMap<>(periode.params(seuil()));
        params.put("pop", acteur.operateurId());
        return synthese(Niveau.DEPARTEMENT, periode, params, entites);
    }

    // ------------------------------------------------------------------ niveau national (administrateur, superviseur)

    /** Les opérateurs techniques. Le rapport mensuel par opérateur n'est pas fourni : son origine reste à préciser. */
    @Transactional(readOnly = true)
    List<ReportOperator> operateurs(Acteur acteur, Integer annee) {
        exigerNational(acteur);
        int an = annee == null ? LocalDate.now(ZoneOffset.UTC).getYear() : annee;
        return jdbc.sql("""
                        select o.id, o.acronyme, o.denomination,
                               (select count(distinct a.secteur_id) from gestion.attribution a join gestion.contrat ct on ct.id = a.contrat_id
                                 where a.operateur_id = o.id and a.supprime_le is null and ct.supprime_le is null and ct.periode @> current_date) as secteurs,
                               (select count(*) from iam.compte c where c.operateur_id = o.id and c.statut = 'ACTIF' and c.supprime_le is null
                                   and c.profil in (:profils)) as collaborateurs,
                               (select count(*) from planning.plan_annuel p join iam.compte c on c.id = p.compte_id
                                 where c.operateur_id = o.id and c.statut = 'ACTIF' and c.profil in (:profils) and p.annee = :an
                                   and p.supprime_le is null and p.statut = 'VALIDE') as valides,
                               (select count(*) from planning.plan_annuel p join iam.compte c on c.id = p.compte_id
                                 where c.operateur_id = o.id and c.statut = 'ACTIF' and c.profil in (:profils) and p.annee = :an
                                   and p.supprime_le is null and p.statut = 'SOUMIS') as soumis
                          from referentiel.operateur o
                         where o.type = 'TECHNIQUE' and o.supprime_le is null
                         order by o.acronyme
                        """)
                .param("profils", COLLABORATEURS).param("an", an)
                .query((rs, n) -> {
                    int total = rs.getInt("collaborateurs");
                    int valides = rs.getInt("valides");
                    int soumis = rs.getInt("soumis");
                    return new ReportOperator(rs.getObject("id", UUID.class), rs.getString("acronyme"), rs.getString("denomination"),
                            rs.getInt("secteurs"), total, new Plans(valides, soumis, Math.max(0, total - valides - soumis)));
                }).list();
    }

    @Transactional(readOnly = true)
    Synthesis syntheseNationale(Acteur acteur, String du, String au) {
        exigerNational(acteur);
        Periode periode = periode(du, au);
        List<ReportRow> entites = operateurs(acteur, null).stream().map(o -> vide(new Entite(o.id(), o.acronym()))).toList();
        return synthese(Niveau.NATIONAL, periode, new HashMap<>(periode.params(seuil())), entites);
    }

    // ------------------------------------------------------------------ calcul

    private record Entite(UUID id, String label) {
    }

    private record Periode(LocalDate du, LocalDate au, String libelleDu, String libelleAu) {
        Map<String, Object> params(int seuil) {
            return Map.of("du", du, "au", au, "seuil", seuil);
        }
    }

    private static ReportRow vide(Entite e) {
        return new ReportRow(e.id(), e.label(), 0, 0, 0, 0, null, "SANS_PREVU");
    }

    /** Regroupe les activités validées de la période par entité ; les entités sans activité figurent à zéro. */
    private Synthesis synthese(Niveau niveau, Periode periode, Map<String, Object> params, List<ReportRow> entites) {
        Map<UUID, int[]> chiffres = new HashMap<>();
        jdbc.sql("select " + niveau.entite + " as ent_id, count(*) as prevues, count(*) filter (where t.realisee_le is not null) as realisees,"
                        + " count(*) filter (where t.realisee_le is null and t.prevue_le < current_date) as retard,"
                        + " count(*) filter (where t.anomalie is not null) as a_verifier"
                        + " from (" + ACTIVITES_DE_LA_PERIODE + ") t join iam.compte c on c.id = t.compte_id " + niveau.jointures
                        + " where c.statut = 'ACTIF' and c.profil in (:profils) and " + niveau.portee + " group by " + niveau.entite)
                .params(params).param("profils", COLLABORATEURS)
                .query((rs, n) -> chiffres.put(rs.getObject("ent_id", UUID.class),
                        new int[] {rs.getInt("prevues"), rs.getInt("realisees"), rs.getInt("retard"), rs.getInt("a_verifier")}))
                .list();
        List<ReportRow> lignes = new ArrayList<>();
        int[] total = new int[4];
        for (ReportRow e : entites) {
            int[] c = chiffres.getOrDefault(e.id(), new int[4]);
            for (int i = 0; i < 4; i++) {
                total[i] += c[i];
            }
            lignes.add(ligne(e.id(), e.label(), c));
        }
        return new Synthesis(LocalDate.now(ZoneOffset.UTC), periode.libelleDu(), periode.libelleAu(),
                ligne(null, "Total", total), lignes);
    }

    private static ReportRow ligne(UUID id, String label, int[] c) {
        Double taux = c[0] == 0 ? null : (double) c[1] / c[0];
        return new ReportRow(id, label, c[0], c[1], c[2], c[3], taux, statut(taux));
    }

    /** À jour ≥ 90 %, Léger retard ≥ 75 %, sinon En retard (règle commune aux trois niveaux). */
    static String statut(Double taux) {
        if (taux == null) {
            return "SANS_PREVU";
        }
        if (taux >= 0.90) {
            return "A_JOUR";
        }
        return taux >= 0.75 ? "LEGER_RETARD" : "EN_RETARD";
    }

    // ------------------------------------------------------------------ utilitaires

    private int seuil() {
        return jdbc.sql("select valeur::int from util.parametre where cle = 'reporting.duree_courte_minutes' and supprime_le is null")
                .query(Integer.class).optional().orElse(15);
    }

    private Set<UUID> secteursDuChefSecteur(Acteur acteur) {
        exigerProfil(acteur, "Le reporting du secteur est réservé au chef secteur.", Acteur.CHEF_SECTEUR);
        return perimetres.de(acteur).secteursEntiers();
    }

    private static void exigerProfil(Acteur acteur, String message, String profil) {
        if (!acteur.estProfil(profil)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ACCES_REFUSE", message);
        }
    }

    private static void exigerNational(Acteur acteur) {
        if (!acteur.estProfil(Acteur.ADMINISTRATEUR, Acteur.SUPERVISEUR)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ACCES_REFUSE", "Le reporting par opérateur est réservé à l'administrateur et au superviseur.");
        }
    }

    /** {@code du} et {@code au} : mois (aaaa-mm), au inclus ; par défaut le mois en cours. Au plus 24 mois. */
    private static Periode periode(String du, String au) {
        YearMonth debut;
        YearMonth fin;
        try {
            debut = du == null || du.isBlank() ? YearMonth.now(ZoneOffset.UTC) : YearMonth.parse(du);
            fin = au == null || au.isBlank() ? debut : YearMonth.parse(au);
        } catch (java.time.format.DateTimeParseException e) {
            throw invalide("Mois invalide : attendu aaaa-mm.");
        }
        if (fin.isBefore(debut)) {
            throw invalide("Le mois de fin précède le mois de début.");
        }
        if (java.time.temporal.ChronoUnit.MONTHS.between(debut, fin) >= MOIS_MAX) {
            throw invalide("La période ne peut pas dépasser " + MOIS_MAX + " mois.");
        }
        return new Periode(debut.atDay(1), fin.plusMonths(1).atDay(1), debut.toString(), fin.toString());
    }

    private static BusinessException invalide(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, "REQUETE_INVALIDE", message);
    }
}
