package ci.sysde.iam.auth;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class AuthDtos {

    private AuthDtos() {
    }

    /** Connexion par matricule (ex. SAPH-00348). */
    public record LoginRequest(
            @NotBlank @Size(max = 17) String matricule,
            @NotBlank @Size(max = 200) String password) {
    }

    public record ChangePasswordRequest(
            @NotBlank @Size(max = 200) String currentPassword,
            @NotBlank @Size(max = 200) String newPassword,
            @Size(max = 200) String refreshToken) {
    }

    public record Operator(UUID id, String acronym, String name) {
    }

    public record SessionUser(
            UUID accountId,
            String matricule,
            String reference,
            String firstNames,
            String lastName,
            String email,
            String profile,
            String profileLabel,
            boolean administrator,
            Operator operator,
            Sector sector,
            List<String> permissions) {
    }

    /** Secteur de rattachement d'un compte (son affectation en cours ; le premier par code s'il en a plusieurs) ; absent sans affectation. */
    public record Sector(String code, String name) {
    }

    /** Rafraîchissement ou déconnexion : le jeton de rafraîchissement reçu à la connexion. */
    public record RefreshRequest(@NotBlank @Size(max = 200) String refreshToken) {
    }

    /**
     * Réponse de connexion et de rafraîchissement. {@code refreshToken} est remis une seule fois et change à
     * chaque rafraîchissement (rotation) : le client doit conserver le dernier reçu.
     */
    public record LoginResponse(String token, Instant expiresAt, boolean mustChangePassword, SessionUser user,
                                String refreshToken, Instant refreshExpiresAt) {

        /** Forme historique, sans jeton de rafraîchissement. */
        public LoginResponse(String token, Instant expiresAt, boolean mustChangePassword, SessionUser user) {
            this(token, expiresAt, mustChangePassword, user, null, null);
        }
    }
}
