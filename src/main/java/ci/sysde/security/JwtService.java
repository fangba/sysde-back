package ci.sysde.security;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/** Émet les jetons d'accès. L'étendue (organisme, opérateur, secteur...) est toujours relue côté serveur. */
@Service
public class JwtService {

    public static final String CLAIM_PERMISSIONS = "permissions";
    public static final String CLAIM_PROFIL = "profil";
    public static final String CLAIM_MATRICULE = "matricule";
    public static final String CLAIM_OPERATEUR = "operateur";

    private final JwtEncoder encoder;
    private final JwtProperties props;

    public JwtService(JwtEncoder encoder, JwtProperties props) {
        this.encoder = encoder;
        this.props = props;
    }

    public Jeton emettre(UUID compteId, String matricule, String profil, UUID operateurId, List<String> permissions) {
        Instant maintenant = Instant.now();
        Instant expire = maintenant.plus(props.duree());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(props.issuer())
                .subject(compteId.toString())
                .issuedAt(maintenant)
                .expiresAt(expire)
                .claim(CLAIM_MATRICULE, matricule)
                .claim(CLAIM_PROFIL, profil)
                .claim(CLAIM_OPERATEUR, operateurId.toString())
                .claim(CLAIM_PERMISSIONS, permissions)
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String valeur = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new Jeton(valeur, expire);
    }

    public record Jeton(String valeur, Instant expireLe) {
    }
}
