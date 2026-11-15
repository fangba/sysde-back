package ci.sysde.security;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Limite de débit par adresse IP sur les routes publiques d'authentification : {@code max} requêtes par {@code fenetre}. */
@ConfigurationProperties("sysde.securite.debit")
public record DebitProperties(int max, Duration fenetre) {
}
