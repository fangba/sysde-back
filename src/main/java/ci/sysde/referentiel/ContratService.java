package ci.sysde.referentiel;

import java.util.List;
import java.util.UUID;

import ci.sysde.common.BusinessException;
import ci.sysde.referentiel.ReferentielDtos.Contract;
import ci.sysde.referentiel.ReferentielDtos.ContractInput;
import ci.sysde.security.Acteur;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Contrats. Le chevauchement des contrats d'un opérateur, la génération des exercices et la propagation des dates aux lots
 * sont portés par la base ; ici seulement les contrôles qui donnent un message clair avant l'écriture.
 */
@Service
class ContratService {

    private static final String SELECT = """
            select c.id, c.reference, c.operateur_id, c.organisme_mandant_id, c.date_debut, c.date_fin
              from gestion.contrat c where c.supprime_le is null""";

    private final JdbcClient jdbc;
    private final Portee.Fabrique portees;

    ContratService(JdbcClient jdbc, Portee.Fabrique portees) {
        this.jdbc = jdbc;
        this.portees = portees;
    }

    @Transactional(readOnly = true)
    List<Contract> lister(Acteur acteur) {
        Portee p = portees.de(acteur);
        return jdbc.sql(SELECT + p.parOperateur("c.operateur_id") + " order by c.date_debut desc, c.reference")
                .params(p.parametres())
                .query(ContratService::ligne)
                .list();
    }

    @Transactional
    Contract creer(Acteur acteur, ContractInput in) {
        UUID mandant = in.mandantId() != null ? in.mandantId() : acteur.operateurId();
        verifier(mandant, in);
        UUID id = jdbc.sql("""
                        insert into gestion.contrat (reference, operateur_id, organisme_mandant_id, date_debut, date_fin)
                        values (:reference, :operateur, :mandant, :debut, :fin)
                        returning id
                        """)
                .param("reference", in.reference().strip())
                .param("operateur", in.operatorId())
                .param("mandant", mandant)
                .param("debut", in.startDate())
                .param("fin", in.endDate())
                .query(UUID.class)
                .single();
        return lire(id);
    }

    /** L'opérateur d'un contrat ne change pas : ses attributions en dépendent. */
    @Transactional
    Contract modifier(UUID id, ContractInput in) {
        Contract actuel = lire(id);
        if (!actuel.operatorId().equals(in.operatorId())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "REQUETE_INVALIDE",
                    "Un contrat ne peut pas changer d'opérateur.");
        }
        UUID mandant = in.mandantId() != null ? in.mandantId() : actuel.mandantId();
        verifier(mandant, in);
        jdbc.sql("""
                        update gestion.contrat
                           set reference = :reference, organisme_mandant_id = :mandant, date_debut = :debut, date_fin = :fin
                         where id = :id
                        """)
                .param("id", id)
                .param("reference", in.reference().strip())
                .param("mandant", mandant)
                .param("debut", in.startDate())
                .param("fin", in.endDate())
                .update();
        return lire(id);
    }

    /** Vérifie les dates, le type de l'opérateur et celui du mandant (par défaut, l'organisme de l'appelant). */
    private void verifier(UUID mandant, ContractInput in) {
        verifierDates(in);
        if (!type(in.operatorId()).equals("TECHNIQUE")) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "REQUETE_INVALIDE",
                    "Un contrat se conclut avec un opérateur technique.");
        }
        if (!type(mandant).equals("ORGANISME")) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "REQUETE_INVALIDE",
                    "Le mandant d'un contrat doit être un organisme (FIRCA, APROMAC).");
        }
    }

    private static void verifierDates(ContractInput in) {
        if (!in.endDate().isAfter(in.startDate())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "REQUETE_INVALIDE",
                    "La fin du contrat doit être postérieure à son début.");
        }
    }

    private String type(UUID operateur) {
        return jdbc.sql("select type from referentiel.operateur where id = :id and supprime_le is null")
                .param("id", operateur)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST, "REQUETE_INVALIDE", "Opérateur inconnu."));
    }

    private Contract lire(UUID id) {
        return jdbc.sql(SELECT + " and c.id = :id")
                .param("id", id)
                .query(ContratService::ligne)
                .optional()
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "INTROUVABLE", "Contrat introuvable."));
    }

    private static Contract ligne(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Contract(rs.getObject("id", UUID.class), rs.getString("reference"), rs.getObject("operateur_id", UUID.class),
                rs.getObject("organisme_mandant_id", UUID.class), rs.getObject("date_debut", java.time.LocalDate.class),
                rs.getObject("date_fin", java.time.LocalDate.class));
    }
}
