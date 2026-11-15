package ci.sysde.referentiel;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Formats échangés avec le front (champs en anglais, camelCase : voir {@code settings.models.ts}). */
public final class ReferentielDtos {

    private ReferentielDtos() {
    }

    // ------------------------------------------------------------------ opérateurs

    public record Operator(UUID id, String acronym, String name, String type, String address, String email,
                           String phone, boolean active) {
    }

    public record OperatorInput(
            @NotBlank @Pattern(regexp = "[A-Z0-9]{2,8}", message = "2 à 8 caractères : majuscules et chiffres") String acronym,
            @NotBlank @Size(max = 150) String name,
            @NotNull @Pattern(regexp = "ORGANISME|TECHNIQUE", message = "ORGANISME ou TECHNIQUE") String type,
            @Size(max = 255) String address,
            @Email @Size(max = 150) String email,
            @Size(max = 30) String phone,
            @NotNull Boolean active) {
    }

    // ------------------------------------------------------------------ secteurs

    public record Sector(UUID id, String code, String name) {
    }

    public record SectorInput(
            @NotBlank @Size(max = 20) String code,
            @NotBlank @Size(max = 150) String name) {
    }

    // ------------------------------------------------------------------ lots

    public record Lot(UUID id, UUID sectorId, int number, String description) {
    }

    public record LotInput(
            @NotNull UUID sectorId,
            @NotNull @Min(1) Integer number,
            @Size(max = 255) String description) {
    }

    // ------------------------------------------------------------------ contrats

    /** {@code mandantId} : l'organisme (FIRCA, APROMAC) qui mandate l'opérateur ; absent à l'envoi, c'est celui de l'appelant. */
    public record Contract(UUID id, String reference, UUID operatorId, UUID mandantId, LocalDate startDate, LocalDate endDate) {
    }

    public record ContractInput(
            @NotBlank @Size(max = 50) String reference,
            @NotNull UUID operatorId,
            UUID mandantId,
            @NotNull LocalDate startDate,
            @NotNull LocalDate endDate) {
    }

    // ------------------------------------------------------------------ attributions

    /** {@code lots} : numéros officiels des lots du secteur confiés à l'opérateur pour la durée du contrat. */
    public record Attribution(UUID id, UUID contractId, UUID sectorId, List<Integer> lots) {
    }

    /** {@code reason} : motif consigné si un lot est retiré (par défaut, un motif générique). */
    public record AttributionInput(
            @NotNull UUID contractId,
            @NotNull UUID sectorId,
            @NotEmpty List<@NotNull @Min(1) Integer> lots,
            @Size(max = 255) String reason) {
    }

    // ------------------------------------------------------------------ exercices

    /** Générés par la base (un par période de 12 mois du contrat) : seule la clôture est modifiable. {@code year} = année du début. */
    public record Exercise(UUID id, UUID attributionId, UUID contractId, UUID sectorId, int number, int year,
                           LocalDate startDate, LocalDate endDate, LocalDate closingDate) {
    }

    public record ExerciseInput(@NotNull LocalDate closingDate) {
    }

    // ------------------------------------------------------------------ sections et groupes (chef secteur)

    /** Le numéro de la section est attribué par le serveur ; le nom est saisi librement par le chef secteur. */
    public record Section(UUID id, UUID lotId, String name, String description) {
    }

    public record SectionInput(
            @NotNull UUID lotId,
            @NotBlank @Size(max = 50) String name,
            @Size(max = 255) String description) {
    }

    /** {@code monitorId} : identifiant du <b>compte</b> moniteur (profil MO), pas de l'utilisateur. */
    public record Group(UUID id, UUID lotId, UUID sectionId, String name, String village, String delegate, UUID monitorId) {
    }

    public record GroupInput(
            @NotNull UUID lotId,
            @NotNull UUID sectionId,
            @NotBlank @Size(max = 50) String name,
            @Size(max = 80) String village,
            @Size(max = 80) String delegate,
            @NotNull UUID monitorId) {
    }

    /** Membre d'un groupe de vulgarisation : une personne (participant) et sa période d'appartenance ({@code until} nul : toujours membre). */
    public record GroupMember(ci.sysde.realisation.RealisationDtos.Participant participant, java.time.LocalDate since, java.time.LocalDate until) {
    }

    /** {@code since} : date d'entrée dans le groupe (aujourd'hui par défaut ; pas dans le futur). */
    public record GroupMemberInput(@jakarta.validation.constraints.NotNull java.util.UUID participantId, java.time.LocalDate since) {
    }

    /** Liste complète des membres actuels : les absents de la liste quittent le groupe, les nouveaux y entrent. */
    public record GroupMembersInput(@jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Size(max = 1000) java.util.List<java.util.UUID> participantIds) {
    }
}
