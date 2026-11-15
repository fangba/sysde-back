package ci.sysde.iam.auth;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Trace des connexions dans la piste d'audit ({@code audit.tracer_connexion}, seule porte d'écriture).
 * Résultats : SUCCES, ECHEC, VERROUILLE, DECONNEXION, MOT_DE_PASSE_CHANGE, MOT_DE_PASSE_REINITIALISE, ACTIVATION.
 */
@Component
public class JournalConnexion {

    private final JdbcClient jdbc;

    public JournalConnexion(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void tracer(UUID compteId, String matricule, String resultat, String adresseIp) {
        jdbc.sql("select audit.tracer_connexion(cast(:compte as uuid), :matricule, :resultat, cast(:ip as text), null)")
                .param("compte", compteId)
                .param("matricule", matricule)
                .param("resultat", resultat)
                .param("ip", adresseIp)
                .query()
                .listOfRows();
    }
}
