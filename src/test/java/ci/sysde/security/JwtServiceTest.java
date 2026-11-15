package ci.sysde.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

class JwtServiceTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";

    private static JwtService service(Duration duree) {
        SecretKey cle = new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        return new JwtService(new NimbusJwtEncoder(new ImmutableSecret<>(cle)),
                new JwtProperties(SECRET, "sysde", duree));
    }

    private static NimbusJwtDecoder decodeur() {
        SecretKey cle = new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        NimbusJwtDecoder d = NimbusJwtDecoder.withSecretKey(cle).macAlgorithm(MacAlgorithm.HS256).build();
        d.setJwtValidator(JwtValidators.createDefaultWithIssuer("sysde"));
        return d;
    }

    @Test
    void lejetonPorteLeCompteLeProfilEtLesPermissions() {
        UUID compte = UUID.randomUUID();
        UUID operateur = UUID.randomUUID();
        var jeton = service(Duration.ofHours(1))
                .emettre(compte, "SAPH-00348", "MO", operateur, List.of("CONSULTER_ACTIVITE", "REALISER_ACTIVITE"));

        Jwt jwt = decodeur().decode(jeton.valeur());

        assertThat(jwt.getSubject()).isEqualTo(compte.toString());
        assertThat(jwt.getClaimAsString(JwtService.CLAIM_PROFIL)).isEqualTo("MO");
        assertThat(jwt.getClaimAsString(JwtService.CLAIM_MATRICULE)).isEqualTo("SAPH-00348");
        assertThat(jwt.getClaimAsString(JwtService.CLAIM_OPERATEUR)).isEqualTo(operateur.toString());
        assertThat(jwt.getClaimAsStringList(JwtService.CLAIM_PERMISSIONS))
                .containsExactly("CONSULTER_ACTIVITE", "REALISER_ACTIVITE");
    }

    /**
     * Spring Security refuse désormais d'émettre un jeton dont l'échéance précède l'émission : le jeton expiré
     * est donc fabriqué directement avec l'encodeur, avec des dates passées.
     */
    @Test
    void unJetonExpireEstRefuse() {
        SecretKey cle = new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        Instant maintenant = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("sysde")
                .subject(UUID.randomUUID().toString())
                .issuedAt(maintenant.minusSeconds(600))
                .expiresAt(maintenant.minusSeconds(300))
                .build();
        String jeton = new NimbusJwtEncoder(new ImmutableSecret<>(cle))
                .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();

        assertThatThrownBy(() -> decodeur().decode(jeton)).isInstanceOf(JwtValidationException.class);
    }

    @Test
    void unSecretTropCourtEstRefuseAuDemarrage() {
        assertThatThrownBy(() -> new JwtProperties("court", "sysde", Duration.ofHours(1)))
                .isInstanceOf(IllegalStateException.class);
    }
}
