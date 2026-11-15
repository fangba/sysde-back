package ci.sysde.referentiel;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import ci.sysde.common.BusinessException;
import ci.sysde.referentiel.ReferentielDtos.Attribution;
import ci.sysde.referentiel.ReferentielDtos.AttributionInput;
import ci.sysde.referentiel.ReferentielDtos.Exercise;
import ci.sysde.referentiel.ReferentielDtos.ExerciseInput;
import ci.sysde.security.Acteur;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Attributions (les lots d'un secteur confiés à un opérateur pour la durée d'un contrat) et leurs exercices.
 * Exclusivité d'un lot sur une période, retrait d'un lot encore affecté, exercices de 12 mois : portés par la base.
 */
@Service
class AttributionService {

    private static final String SELECT_ATTRIBUTION = """
            select a.id, a.contrat_id, a.secteur_id,
                   coalesce((select array_agg(l.numero::int order by l.numero)
                               from gestion.attribution_lot al join referentiel.lot l on l.id = al.lot_id
                              where al.attribution_id = a.id), '{}') as lots
              from gestion.attribution a where a.supprime_le is null""";

    private static final String SELECT_EXERCICE = """
            select e.id, e.attribution_id, a.contrat_id, a.secteur_id, e.numero, e.date_debut, e.date_fin, e.date_cloture
              from gestion.exercice e join gestion.attribution a on a.id = e.attribution_id
             where e.supprime_le is null and a.supprime_le is null""";

    private final JdbcClient jdbc;
    private final Portee.Fabrique portees;

    AttributionService(JdbcClient jdbc, Portee.Fabrique portees) {
        this.jdbc = jdbc;
        this.portees = portees;
    }

    // ------------------------------------------------------------------ attributions

    @Transactional(readOnly = true)
    List<Attribution> lister(Acteur acteur) {
        Portee p = portees.de(acteur);
        if (p.vide()) {
            return List.of();
        }
        return jdbc.sql(SELECT_ATTRIBUTION + p.parOperateur("a.operateur_id") + p.parSecteur("a.secteur_id")
                        + " order by a.secteur_id, a.id")
                .params(p.parametres())
                .query(AttributionService::ligne)
                .list();
    }

    @Transactional
    Attribution creer(AttributionInput in) {
        Map<Integer, UUID> lots = resoudreLots(in);
        UUID id = jdbc.sql("insert into gestion.attribution (contrat_id, secteur_id) values (:contrat, :secteur) returning id")
                .param("contrat", in.contractId())
                .param("secteur", in.sectorId())
                .query(UUID.class)
                .single();
        ajouter(id, in, lots.values());
        return lire(id);
    }

    /** Le contrat et le secteur d'une attribution ne changent pas ; seuls ses lots évoluent (ajout, retrait avec motif). */
    @Transactional
    Attribution modifier(UUID id, AttributionInput in) {
        Attribution actuelle = lire(id);
        if (!actuelle.contractId().equals(in.contractId()) || !actuelle.sectorId().equals(in.sectorId())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "REQUETE_INVALIDE",
                    "Le contrat et le secteur d'une attribution ne changent pas.");
        }
        Map<Integer, UUID> voulus = resoudreLots(in);
        Set<Integer> retires = new TreeSet<>(actuelle.lots());
        retires.removeAll(voulus.keySet());
        if (!retires.isEmpty()) {
            String motif = in.reason() == null || in.reason().isBlank() ? "Modification de l'attribution" : in.reason().strip();
            // Le déclencheur de retrait lit le motif dans la transaction.
            jdbc.sql("select set_config('app.motif', :motif, true)").param("motif", motif).query().singleValue();
            jdbc.sql("""
                            delete from gestion.attribution_lot al using referentiel.lot l
                             where al.attribution_id = :id and l.id = al.lot_id and l.numero in (:numeros)
                            """)
                    .param("id", id)
                    .param("numeros", retires)
                    .update();
        }
        List<UUID> ajoutes = new ArrayList<>();
        voulus.forEach((numero, lotId) -> {
            if (!actuelle.lots().contains(numero)) {
                ajoutes.add(lotId);
            }
        });
        ajouter(id, in, ajoutes);
        return lire(id);
    }

    /** Numéros de lots demandés → identifiants, dans le secteur de l'attribution ; 400 si l'un n'existe pas. */
    private Map<Integer, UUID> resoudreLots(AttributionInput in) {
        Set<Integer> numeros = new TreeSet<>(in.lots());
        Map<Integer, UUID> trouves = new HashMap<>();
        jdbc.sql("select id, numero from referentiel.lot where secteur_id = :secteur and supprime_le is null and numero in (:numeros)")
                .param("secteur", in.sectorId())
                .param("numeros", numeros)
                .query((rs, n) -> trouves.put(rs.getInt("numero"), rs.getObject("id", UUID.class)))
                .list();
        Set<Integer> manquants = new TreeSet<>(numeros);
        manquants.removeAll(trouves.keySet());
        if (!manquants.isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "REQUETE_INVALIDE",
                    "Lot(s) " + manquants + " absent(s) de ce secteur.");
        }
        return trouves;
    }

    private void ajouter(UUID attribution, AttributionInput in, Iterable<UUID> lotIds) {
        for (UUID lotId : lotIds) {
            jdbc.sql("""
                            insert into gestion.attribution_lot (attribution_id, contrat_id, secteur_id, lot_id)
                            values (:attribution, :contrat, :secteur, :lot)
                            """)
                    .param("attribution", attribution)
                    .param("contrat", in.contractId())
                    .param("secteur", in.sectorId())
                    .param("lot", lotId)
                    .update();
        }
    }

    private Attribution lire(UUID id) {
        return jdbc.sql(SELECT_ATTRIBUTION + " and a.id = :id")
                .param("id", id)
                .query(AttributionService::ligne)
                .optional()
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "INTROUVABLE", "Attribution introuvable."));
    }

    private static Attribution ligne(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        Integer[] numeros = (Integer[]) rs.getArray("lots").getArray();
        return new Attribution(rs.getObject("id", UUID.class), rs.getObject("contrat_id", UUID.class),
                rs.getObject("secteur_id", UUID.class), List.of(numeros));
    }

    // ------------------------------------------------------------------ exercices

    @Transactional(readOnly = true)
    List<Exercise> exercices(Acteur acteur) {
        Portee p = portees.de(acteur);
        if (p.vide()) {
            return List.of();
        }
        return jdbc.sql(SELECT_EXERCICE + p.parOperateur("a.operateur_id") + p.parSecteur("a.secteur_id")
                        + " order by e.date_debut, a.secteur_id, e.numero")
                .params(p.parametres())
                .query(AttributionService::ligneExercice)
                .list();
    }

    /** Seule la date de clôture se modifie (les périodes sont calculées sur le contrat). */
    @Transactional
    Exercise cloturer(UUID id, ExerciseInput in) {
        int n = jdbc.sql("update gestion.exercice set date_cloture = :cloture where id = :id and supprime_le is null")
                .param("id", id)
                .param("cloture", in.closingDate())
                .update();
        if (n == 0) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "INTROUVABLE", "Exercice introuvable.");
        }
        return jdbc.sql(SELECT_EXERCICE + " and e.id = :id")
                .param("id", id)
                .query(AttributionService::ligneExercice)
                .single();
    }

    private static Exercise ligneExercice(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        LocalDate debut = rs.getObject("date_debut", LocalDate.class);
        return new Exercise(rs.getObject("id", UUID.class), rs.getObject("attribution_id", UUID.class),
                rs.getObject("contrat_id", UUID.class), rs.getObject("secteur_id", UUID.class), rs.getInt("numero"),
                debut.getYear(), debut, rs.getObject("date_fin", LocalDate.class), rs.getObject("date_cloture", LocalDate.class));
    }
}
