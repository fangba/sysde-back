package ci.sysde.security;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Le compte connecté, lu dans le jeton d'accès. L'étendue territoriale n'est jamais lue dans le jeton :
 * elle est relue en base à chaque requête (voir {@link PerimetreService}).
 */
public record Acteur(UUID compteId, String matricule, String profil, UUID operateurId, Set<String> permissions) {

    public static final String ADMINISTRATEUR = "AD";
    public static final String SUPERVISEUR = "SU";
    public static final String CHEF_DEPARTEMENT = "CD";
    public static final String CHEF_SECTEUR = "CS";

    public static Acteur depuis(Authentication authentification) {
        if (!(authentification instanceof JwtAuthenticationToken jeton)) {
            throw new IllegalStateException("Aucun compte authentifié par jeton dans cette requête.");
        }
        List<String> permissions = jeton.getToken().getClaimAsStringList(JwtService.CLAIM_PERMISSIONS);
        return new Acteur(
                UUID.fromString(jeton.getName()),
                jeton.getToken().getClaimAsString(JwtService.CLAIM_MATRICULE),
                jeton.getToken().getClaimAsString(JwtService.CLAIM_PROFIL),
                UUID.fromString(jeton.getToken().getClaimAsString(JwtService.CLAIM_OPERATEUR)),
                permissions == null ? Set.of() : Set.copyOf(permissions));
    }

    public boolean a(String permission) {
        return permissions.contains(permission);
    }

    public boolean estProfil(String... profils) {
        for (String p : profils) {
            if (p.equals(profil)) {
                return true;
            }
        }
        return false;
    }
}
