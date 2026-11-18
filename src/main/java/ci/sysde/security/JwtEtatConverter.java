package ci.sysde.security;

import java.util.List;
import java.util.UUID;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Le jeton prouve <em>qui</em> parle ; ce qu'il a le droit de faire est relu en base (PRO-17). Profil, opérateur et
 * permissions du jeton sont remplacés par l'état courant du compte, et un compte devenu inactif est rejeté (401, ORG-06).
 */
@Component
class JwtEtatConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    /** Autorité portée par un compte désactivé qui n'a plus que le droit d'envoyer. */
    static final String ENVOI_SEUL = "ENVOI_SEUL";
    /** Autorité portée par un compte au mot de passe temporaire : il ne peut que le changer (et se déconnecter). */
    static final String MOT_DE_PASSE_TEMPORAIRE = "MOT_DE_PASSE_TEMPORAIRE";
    private static final String ENVOI = "REALISER_ACTIVITE";

    private final EtatCompteService etats;

    JwtEtatConverter(EtatCompteService etats) {
        this.etats = etats;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        UUID compteId;
        try {
            compteId = UUID.fromString(jwt.getSubject());
        } catch (RuntimeException e) {
            throw new InvalidBearerTokenException("Jeton sans compte valide.");
        }
        EtatCompte etat = etats.de(compteId)
                .orElseThrow(() -> new InvalidBearerTokenException("Compte inactif : session terminée."));
        // Sessions coupées (réinitialisation du mot de passe) : les jetons émis avant sont refusés. Les jetons portent
        // une date à la seconde : on compare à la seconde, au risque de laisser passer ceux émis dans la même seconde.
        if (etat.jetonsValidesDepuis() != null && jwt.getIssuedAt() != null
                && jwt.getIssuedAt().isBefore(etat.jetonsValidesDepuis().truncatedTo(java.time.temporal.ChronoUnit.SECONDS))) {
            throw new InvalidBearerTokenException("Session coupée : reconnectez-vous.");
        }
        // Envoi seul (compte désactivé depuis peu) : seule la permission d'envoyer subsiste, et une autorité marque le cas
        // pour que la configuration de sécurité ferme tout le reste.
        // Mot de passe temporaire : aucune permission tant qu'il n'est pas changé (la configuration de sécurité ne laisse
        // passer que le changement de mot de passe, le profil et la déconnexion).
        var permissions = etat.doitChangerMotDePasse()
                ? java.util.Set.<String>of()
                : etat.envoiSeul()
                        ? etat.permissions().stream().filter(JwtEtatConverter.ENVOI::equals).collect(java.util.stream.Collectors.toSet())
                        : etat.permissions();
        Jwt courant = Jwt.withTokenValue(jwt.getTokenValue())
                .headers(h -> h.putAll(jwt.getHeaders()))
                .claims(c -> {
                    c.putAll(jwt.getClaims());
                    c.put(JwtService.CLAIM_MATRICULE, etat.matricule());
                    c.put(JwtService.CLAIM_PROFIL, etat.profil());
                    c.put(JwtService.CLAIM_OPERATEUR, etat.operateurId().toString());
                    c.put(JwtService.CLAIM_PERMISSIONS, List.copyOf(permissions));
                })
                .build();
        var autorites = new java.util.ArrayList<org.springframework.security.core.GrantedAuthority>(
                permissions.stream().sorted().map(p -> new SimpleGrantedAuthority("PERM_" + p)).toList());
        if (etat.envoiSeul()) {
            autorites.add(new SimpleGrantedAuthority(ENVOI_SEUL));
        }
        if (etat.doitChangerMotDePasse()) {
            autorites.add(new SimpleGrantedAuthority(MOT_DE_PASSE_TEMPORAIRE));
        }
        return new JwtAuthenticationToken(courant, autorites, compteId.toString());
    }
}
