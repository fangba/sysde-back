package ci.sysde.security;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * @param duree                  durée de vie du jeton d'accès
 * @param dureeRafraichissement  durée de vie du jeton de rafraîchissement (30 jours par défaut : le mobile
 *                               travaille hors ligne, la session ne doit pas expirer entre deux synchronisations)
 */
@ConfigurationProperties("sysde.jwt")
public record JwtProperties(String secret, String issuer, Duration duree, Duration dureeRafraichissement) {

    @ConstructorBinding
    public JwtProperties {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("sysde.jwt.secret doit faire au moins 32 octets.");
        }
        if (dureeRafraichissement == null) {
            dureeRafraichissement = Duration.ofDays(30);
        }
    }

    /** Constructeur historique (sans durée de rafraîchissement). */
    public JwtProperties(String secret, String issuer, Duration duree) {
        this(secret, issuer, duree, null);
    }
}
