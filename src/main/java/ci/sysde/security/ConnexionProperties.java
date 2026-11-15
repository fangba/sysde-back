package ci.sysde.security;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Verrouillage temporaire après des échecs de connexion successifs. */
@ConfigurationProperties("sysde.securite.connexion")
public record ConnexionProperties(int echecsMax, Duration verrouillage) {
}
