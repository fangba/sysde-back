package ci.sysde.notification;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import ci.sysde.common.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Notifications : créées par le serveur (jamais par un client), lues et marquées lues par leur seul destinataire. */
@Service
public class NotificationService {

    static final int LIMITE = 50;

    /** {@code tone} : info, success, warning ou error. */
    public record Notification(UUID id, String title, String message, String tone, OffsetDateTime createdAt, boolean read) {
    }

    private final JdbcClient jdbc;
    private final FluxNotifications flux;

    NotificationService(JdbcClient jdbc, FluxNotifications flux) {
        this.jdbc = jdbc;
        this.flux = flux;
    }

    /** Dans la transaction de l'action qui la provoque : si l'action échoue, la notification n'existe pas. */
    @Transactional
    public void creer(UUID destinataire, String type, String titre, String message, String ton, UUID objetId) {
        Notification creee = jdbc.sql("""
                        insert into iam.notification (compte_id, type, titre, message, ton, objet_id)
                        values (:c, :type, :titre, :message, :ton, :objet)
                        returning id, titre, message, ton, cree_le, lue
                        """)
                .param("c", destinataire).param("type", type).param("titre", titre).param("message", message)
                .param("ton", ton).param("objet", objetId)
                .query(NotificationService::ligne).single();
        // Poussée une fois la transaction validée : un client ne voit jamais une notification d'une action annulée.
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    flux.pousser(destinataire, creee);
                }
            });
        } else {
            flux.pousser(destinataire, creee);
        }
    }

    private static Notification ligne(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Notification(rs.getObject("id", UUID.class), rs.getString("titre"), rs.getString("message"),
                rs.getString("ton"), rs.getObject("cree_le", OffsetDateTime.class), rs.getBoolean("lue"));
    }

    /** Les 50 dernières, la plus récente d'abord. */
    @Transactional(readOnly = true)
    List<Notification> dernieres(UUID compte) {
        return jdbc.sql("select id, titre, message, ton, cree_le, lue from iam.notification where compte_id = :c order by cree_le desc, id desc limit :n")
                .param("c", compte).param("n", LIMITE)
                .query(NotificationService::ligne).list();
    }

    @Transactional
    void marquerLue(UUID compte, UUID id) {
        int n = jdbc.sql("update iam.notification set lue = true, lue_le = coalesce(lue_le, now()) where id = :id and compte_id = :c")
                .param("id", id).param("c", compte).update();
        if (n == 0) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "INTROUVABLE", "Notification introuvable.");
        }
    }

    @Transactional
    void toutMarquerLu(UUID compte) {
        jdbc.sql("update iam.notification set lue = true, lue_le = now() where compte_id = :c and not lue").param("c", compte).update();
    }
}
