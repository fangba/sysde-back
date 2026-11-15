package ci.sysde.referentiel;

import java.util.List;
import java.util.UUID;

import ci.sysde.common.BusinessException;
import ci.sysde.referentiel.ReferentielDtos.Operator;
import ci.sysde.referentiel.ReferentielDtos.OperatorInput;
import ci.sysde.security.Acteur;
import ci.sysde.security.EtatCompteService;
import ci.sysde.security.PerimetreService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Opérateurs et organismes. Les règles (acronyme unique, acronyme figé dès qu'un compte existe, type lié aux
 * contrats) sont portées par la base : leurs messages remontent tels quels.
 */
@Service
class OperateurService {

    private static final String COLONNES = "o.id, o.acronyme, o.denomination, o.type, o.adresse, o.email, o.telephone, o.actif";

    private final JdbcClient jdbc;
    private final PerimetreService perimetres;
    private final EtatCompteService etats;

    OperateurService(JdbcClient jdbc, PerimetreService perimetres, EtatCompteService etats) {
        this.jdbc = jdbc;
        this.perimetres = perimetres;
        this.etats = etats;
    }

    /** AD et SU : tous les opérateurs. Les autres : le leur seulement. */
    @Transactional(readOnly = true)
    List<Operator> lister(Acteur acteur) {
        var p = perimetres.de(acteur);
        var requete = p.estNational()
                ? jdbc.sql("select " + COLONNES + " from referentiel.operateur o where o.supprime_le is null order by o.acronyme")
                : jdbc.sql("select " + COLONNES + " from referentiel.operateur o where o.supprime_le is null and o.id = :id order by o.acronyme")
                        .param("id", p.operateurId());
        return requete.query(OperateurService::ligne).list();
    }

    @Transactional
    Operator creer(OperatorInput in) {
        UUID id = jdbc.sql("""
                        insert into referentiel.operateur (type, acronyme, denomination, adresse, email, telephone, actif)
                        values (:type, :acronyme, :nom, :adresse, :email, :telephone, :actif)
                        returning id
                        """)
                .param("type", in.type())
                .param("acronyme", in.acronym())
                .param("nom", in.name())
                .param("adresse", in.address())
                .param("email", in.email())
                .param("telephone", in.phone())
                .param("actif", in.active())
                .query(UUID.class)
                .single();
        return lire(id);
    }

    @Transactional
    Operator modifier(UUID id, OperatorInput in) {
        int n = jdbc.sql("""
                        update referentiel.operateur
                           set type = :type, acronyme = :acronyme, denomination = :nom, adresse = :adresse,
                               email = :email, telephone = :telephone, actif = :actif
                         where id = :id and supprime_le is null
                        """)
                .param("id", id)
                .param("type", in.type())
                .param("acronyme", in.acronym())
                .param("nom", in.name())
                .param("adresse", in.address())
                .param("email", in.email())
                .param("telephone", in.phone())
                .param("actif", in.active())
                .update();
        if (n == 0) {
            throw introuvable();
        }
        // ORG-06 : désactiver un opérateur coupe aussitôt l'accès de ses comptes (jetons déjà émis compris).
        etats.invalider();
        return lire(id);
    }

    private Operator lire(UUID id) {
        return jdbc.sql("select " + COLONNES + " from referentiel.operateur o where o.id = :id and o.supprime_le is null")
                .param("id", id)
                .query(OperateurService::ligne)
                .optional()
                .orElseThrow(OperateurService::introuvable);
    }

    private static Operator ligne(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Operator(rs.getObject("id", UUID.class), rs.getString("acronyme"), rs.getString("denomination"),
                rs.getString("type"), rs.getString("adresse"), rs.getString("email"), rs.getString("telephone"),
                rs.getBoolean("actif"));
    }

    private static BusinessException introuvable() {
        return new BusinessException(HttpStatus.NOT_FOUND, "INTROUVABLE", "Opérateur introuvable.");
    }
}
