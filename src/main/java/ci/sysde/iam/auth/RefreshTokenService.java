package ci.sysde.iam.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import ci.sysde.common.BusinessException;
import ci.sysde.security.JwtProperties;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Jetons de rafraîchissement ({@code iam.jeton_rafraichissement}), avec rotation par famille :
 * chaque rafraîchissement remplace le jeton par un nouveau de la même famille. Présenter un jeton déjà
 * tourné prouve qu'il a fuité : toute la famille est révoquée et l'utilisateur doit se reconnecter.
 * Seule l'empreinte SHA-256 est stockée ; la valeur en clair n'existe que chez le client.
 */
@Service
public class RefreshTokenService {

    private static final SecureRandom ALEA = new SecureRandom();
    private static final int OCTETS = 32;

    private final JdbcClient jdbc;
    private final Duration duree;

    public RefreshTokenService(JdbcClient jdbc, JwtProperties props) {
        this.jdbc = jdbc;
        this.duree = props.dureeRafraichissement();
    }

    /** Jeton émis : la valeur en clair (à remettre au client une seule fois) et son échéance. */
    public record Emis(String valeur, java.time.Instant expireLe) {
    }

    /** Résultat d'une rotation : le compte concerné, la famille et le nouveau jeton. */
    public record Rotation(UUID compteId, UUID familleId, Emis nouveau) {
    }

    /** Ouvre une nouvelle famille (connexion). */
    @Transactional
    public Emis emettre(UUID compteId, String appareil, String adresseIp) {
        return inserer(compteId, UUID.randomUUID(), appareil, adresseIp);
    }

    /**
     * Échange un jeton valide contre un nouveau. Le refus (jeton inconnu, expiré, déjà utilisé) ne défait pas
     * la transaction : la révocation de la famille doit survivre.
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public Rotation tourner(String valeurPresentee, String appareil, String adresseIp) {
        if (valeurPresentee == null || valeurPresentee.isBlank()) {
            throw sessionExpiree();
        }
        Ligne ligne = jdbc.sql("""
                        select id, compte_id, famille_id, revoque_le is not null as revoque, expire_le <= now() as expire
                          from iam.jeton_rafraichissement
                         where empreinte = :empreinte
                           for update
                        """)
                .param("empreinte", empreinte(valeurPresentee))
                .query((rs, n) -> new Ligne(rs.getObject("id", UUID.class), rs.getObject("compte_id", UUID.class),
                        rs.getObject("famille_id", UUID.class), rs.getBoolean("revoque"), rs.getBoolean("expire")))
                .optional()
                .orElseThrow(RefreshTokenService::sessionExpiree);

        if (ligne.revoque()) {
            revoquerFamille(ligne.familleId());         // réutilisation : la famille entière est coupée
            throw sessionExpiree();
        }
        if (ligne.expire()) {
            throw sessionExpiree();
        }
        jdbc.sql("update iam.jeton_rafraichissement set revoque_le = now() where id = :id")
                .param("id", ligne.id())
                .update();
        Emis nouveau = inserer(ligne.compteId(), ligne.familleId(), appareil, adresseIp);
        return new Rotation(ligne.compteId(), ligne.familleId(), nouveau);
    }

    /** Déconnexion : révoque la famille du jeton présenté. Silencieux si le jeton est inconnu. */
    @Transactional
    public void revoquer(String valeurPresentee) {
        if (valeurPresentee == null || valeurPresentee.isBlank()) {
            return;
        }
        jdbc.sql("""
                        update iam.jeton_rafraichissement set revoque_le = now()
                         where revoque_le is null
                           and famille_id = (select famille_id from iam.jeton_rafraichissement where empreinte = :empreinte)
                        """)
                .param("empreinte", empreinte(valeurPresentee))
                .update();
    }

    /**
     * Après un changement de mot de passe : toutes les sessions du compte sont révoquées, sauf celle dont on présente
     * le jeton (la personne qui change son mot de passe garde sa session). Sans jeton présenté, toutes le sont.
     */
    @Transactional
    public void revoquerAutres(UUID compteId, String valeurConservee) {
        String empreinte = valeurConservee == null || valeurConservee.isBlank() ? null : empreinte(valeurConservee);
        jdbc.sql("""
                        update iam.jeton_rafraichissement set revoque_le = now()
                         where compte_id = :compte and revoque_le is null
                           and (cast(:empreinte as text) is null or famille_id is distinct from
                                (select j.famille_id from iam.jeton_rafraichissement j
                                  where j.empreinte = :empreinte and j.compte_id = :compte))
                        """)
                .param("compte", compteId).param("empreinte", empreinte).update();
    }

    @Transactional
    public void revoquerFamille(UUID familleId) {
        jdbc.sql("update iam.jeton_rafraichissement set revoque_le = now() where famille_id = :famille and revoque_le is null")
                .param("famille", familleId)
                .update();
    }

    private Emis inserer(UUID compteId, UUID familleId, String appareil, String adresseIp) {
        String valeur = nouvelleValeur();
        java.time.Instant expire = jdbc.sql("""
                        insert into iam.jeton_rafraichissement (compte_id, famille_id, empreinte, appareil, adresse_ip, expire_le)
                        values (:compte, :famille, :empreinte, cast(:appareil as varchar), cast(cast(:ip as text) as inet),
                                now() + make_interval(secs => :secondes))
                        returning expire_le
                        """)
                .param("compte", compteId)
                .param("famille", familleId)
                .param("empreinte", empreinte(valeur))
                .param("appareil", tronquer(appareil, 150))
                .param("ip", adresseIp)
                .param("secondes", (double) duree.toSeconds())
                .query((rs, n) -> rs.getTimestamp("expire_le").toInstant())
                .single();
        return new Emis(valeur, expire);
    }

    // ------------------------------------------------------------------ outils (testés sans base)

    static String nouvelleValeur() {
        byte[] octets = new byte[OCTETS];
        ALEA.nextBytes(octets);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(octets);
    }

    /** SHA-256 en hexadécimal minuscule : 64 caractères, comme la colonne {@code char(64)}. */
    static String empreinte(String valeur) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(valeur.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponible", e);
        }
    }

    static String tronquer(String texte, int max) {
        if (texte == null) {
            return null;
        }
        return texte.length() <= max ? texte : texte.substring(0, max);
    }

    private static BusinessException sessionExpiree() {
        return new BusinessException(HttpStatus.UNAUTHORIZED, "SESSION_EXPIREE", "Votre session a expiré : reconnectez-vous.");
    }

    private record Ligne(UUID id, UUID compteId, UUID familleId, boolean revoque, boolean expire) {
    }
}
