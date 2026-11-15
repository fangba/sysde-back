package ci.sysde.iam.compte;

import java.util.HashMap;
import java.util.Map;

import ci.sysde.security.Acteur;
import ci.sysde.security.Perimetre;
import ci.sysde.security.PerimetreService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Quels comptes un compte connecté peut voir (condition SQL sur les alias {@code c} = compte, {@code p} = profil du compte,
 * {@code o} = opérateur du compte). Reflète {@code iam.acteur_autorise} : rang strictement supérieur, même organisation.
 * AD et SU : leur organisme et tous les opérateurs techniques. Un chef secteur : ses secteurs et les comptes sans affectation.
 */
@Component
public class PorteeComptes {

    /** {@code sql} : condition sans « and » initial ; {@code params} : ses paramètres nommés. */
    public record Condition(String sql, Map<String, Object> params) {
    }

    private final PerimetreService perimetres;
    private final JdbcClient jdbc;

    PorteeComptes(PerimetreService perimetres, JdbcClient jdbc) {
        this.perimetres = perimetres;
        this.jdbc = jdbc;
    }

    public Condition de(Acteur acteur) {
        Perimetre p = perimetres.de(acteur);
        Map<String, Object> params = new HashMap<>();
        params.put("pop", acteur.operateurId());
        if (acteur.estProfil(Acteur.ADMINISTRATEUR, Acteur.SUPERVISEUR)) {
            return new Condition("(c.operateur_id = :pop or o.type = 'TECHNIQUE')", params);
        }
        params.put("rang", jdbc.sql("select rang from iam.profil where code = :p").param("p", acteur.profil()).query(Integer.class).single());
        StringBuilder sql = new StringBuilder("c.operateur_id = :pop and p.rang > :rang");
        if (p.etendue() == Perimetre.Etendue.AFFECTATIONS) {
            // Un chef secteur voit les comptes affectés à ses secteurs, et ceux qui n'ont pas encore d'affectation (à placer).
            String sansAffectation = "not exists (select 1 from iam.affectation f where f.compte_id = c.id and f.fin is null and f.supprime_le is null)";
            if (p.secteurs().isEmpty()) {
                sql.append(" and ").append(sansAffectation);
            } else {
                sql.append(" and (").append(sansAffectation)
                        .append(" or exists (select 1 from iam.affectation f where f.compte_id = c.id and f.fin is null and f.supprime_le is null")
                        .append(" and f.secteur_id in (:secteurs)))");
                params.put("secteurs", p.secteurs());
            }
        }
        return new Condition(sql.toString(), params);
    }
}
