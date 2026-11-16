package ci.sysde.iam.compte;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Formats de l'API {@code /comptes} (champs en anglais, camelCase). Un compte porte la personne qui l'occupe. */
public final class CompteDtos {

    private CompteDtos() {
    }

    /** {@code status} : EN_ATTENTE, ACTIF ou DESACTIVE. {@code reference} : profil + matricule (ex. MO-SAPH-00348). */
    public record Account(UUID id, UUID userId, String matricule, String reference, String number, String profile,
                          String profileLabel, UUID operatorId, String operatorAcronym, String lastName, String firstName,
                          String email, String phone, String status, boolean mustChangePassword,
                          OffsetDateTime lastLoginAt, OffsetDateTime createdAt, UUID sectorId, Integer lotNumber, String sections) {
    }

    /** Le mot de passe temporaire n'est montré qu'une fois, à celui qui crée ou réinitialise le compte. */
    public record AccountWithPassword(Account account, String temporaryPassword) {
    }

    /** {@code number} : numéro de matricule historique (5 à 7 chiffres) ; absent, le système en attribue un. */
    public record AccountInput(
            @NotBlank @Size(max = 100) String lastName,
            @NotBlank @Size(max = 150) String firstName,
            @Email @Size(max = 150) String email,
            @Size(max = 30) String phone,
            @NotNull UUID operatorId,
            @NotNull @Pattern(regexp = "[A-Z]{2}") String profile,
            @Pattern(regexp = "[0-9]{5,7}", message = "5 à 7 chiffres") String number) {
    }

    public record PersonInput(
            @NotBlank @Size(max = 100) String lastName,
            @NotBlank @Size(max = 150) String firstName,
            @Email @Size(max = 150) String email,
            @Size(max = 30) String phone) {
    }

    /** Motif facultatif (désactivation, réactivation, réinitialisation) : conservé dans la traçabilité. */
    public record ReasonInput(@Size(max = 500) String reason) {
    }

    public record ProfileChange(
            @NotNull @Pattern(regexp = "[A-Z]{2}") String profile,
            @NotBlank @Size(max = 500) String reason) {
    }

    public record MatriculeCorrection(
            @NotNull @Pattern(regexp = "[0-9]{5,7}", message = "5 à 7 chiffres") String number,
            @NotBlank @Size(max = 500) String reason) {
    }

    // ------------------------------------------------------------------ affectations et reprise

    /** {@code state} : ACTIVE, SUSPENDUE (entre deux contrats), A_REVOIR (territoire plus couvert) ou CLOSE. */
    public record Assignment(UUID id, UUID accountId, UUID sectorId, String sectorCode, String sectorName, UUID lotId,
                             Integer lotNumber, UUID sectionId, String sectionName, LocalDate startDate, LocalDate endDate,
                             String state) {
    }

    /** Territoire d'une affectation : un secteur, un lot de ce secteur, ou une section de ce lot (selon le profil). */
    public record AssignmentInput(@NotNull UUID sectorId, UUID lotId, UUID sectionId) {
    }

    public record TakeoverInput(@NotNull UUID successorId, @NotBlank @Size(max = 500) String reason) {
    }

    public record TakeoverResult(int transferredAssignments) {
    }

    // ------------------------------------------------------------------ import CSV (COM-09)

    /** {@code line} : numéro de ligne dans le fichier (l'en-tête est la ligne 1). */
    public record ImportError(int line, String message) {
    }

    /** {@code status} : PRET (au moins une ligne valide), APPLIQUE ou ANNULE. {@code errors} : les 200 premières erreurs. */
    public record ImportSummary(UUID id, String fileName, String status, int lines, int valid, int rejected,
                                java.util.List<ImportError> errors) {
    }

    /** Un compte créé par l'import ; le mot de passe temporaire n'est montré qu'ici, une seule fois. */
    public record ImportedAccount(int line, UUID accountId, String matricule, String reference, String lastName,
                                  String firstName, String temporaryPassword) {
    }

    public record ImportResult(UUID id, int created, int rejected, java.util.List<ImportedAccount> accounts,
                               java.util.List<ImportError> errors) {
    }
}
