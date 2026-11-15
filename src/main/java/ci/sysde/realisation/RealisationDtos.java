package ci.sysde.realisation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** Formats de l'API réalisations (champs en anglais, camelCase). Les champs {@code null} sont absents du JSON. */
public final class RealisationDtos {

    private RealisationDtos() {
    }

    // ------------------------------------------------------------------ participants

    /** Saigneur, apprenti ou planteur qui suit une activité. {@code externalRef} : référence dans la base externe, facultative. */
    public record Participant(UUID id, String lastName, String firstName, String phone, String village, String externalRef) {
    }

    /** {@code id} : identifiant choisi par l'appareil (UUID v7) pour pouvoir renvoyer sans doublon. */
    public record ParticipantInput(
            UUID id,
            @NotBlank @Size(max = 100) String lastName,
            @NotBlank @Size(max = 150) String firstName,
            @Size(max = 30) String phone,
            @Size(max = 80) String village,
            @Size(max = 50) String externalRef) {
    }

    // ------------------------------------------------------------------ réalisation

    public record AttendanceInput(@NotNull UUID participantId, @NotNull LocalDate day, @NotNull Boolean present) {
    }

    /** {@code level} : BON, MOYEN ou MAUVAIS. {@code criterion} : code d'un critère (voir {@code /plannings/criteres-evaluation}). */
    public record EvaluationInput(
            @NotNull UUID participantId,
            @NotBlank @Size(max = 30) String criterion,
            @NotNull @Pattern(regexp = "BON|MOYEN|MAUVAIS") String level) {
    }

    /** {@code verdict} : CONFORME ou NON_CONFORME. */
    public record AppreciationInput(
            @NotNull UUID participantId,
            @NotNull @Pattern(regexp = "CONFORME|NON_CONFORME") String verdict,
            @Size(max = 2000) String comment) {
    }

    /**
     * Réalisation envoyée par l'appareil, <b>d'une ligne de planning</b> ({@code lineId}) ou <b>spontanée</b> (sans ligne : {@code activityId}
     * d'une activité non planifiée, {@code plantationRef} et position obligatoires). Sans ligne, {@code activityId} est l'activité ;
     * avec une ligne, il est facultatif (et doit être celui de la ligne), et {@code plantationRef} interdit (la ligne le porte).
     * Réalisation d'une ligne de planning, envoyée par l'appareil : <b>état complet, rejouable</b> (même identifiant, même
     * contenu : même résultat). {@code status} : EN_COURS ou TERMINEE (heure de fin obligatoire). {@code expectedVersion} : version
     * connue de l'appareil, pour détecter une modification faite ailleurs (409).
     */
    public record RealisationInput(
            UUID lineId,
            UUID activityId,
            @Size(max = 50) String plantationRef,
            @NotNull @Pattern(regexp = "EN_COURS|TERMINEE") String status,
            @NotNull OffsetDateTime startedAt,
            OffsetDateTime endedAt,
            @DecimalMin("-90") @DecimalMax("90") BigDecimal latitude,
            @DecimalMin("-180") @DecimalMax("180") BigDecimal longitude,
            @PositiveOrZero BigDecimal accuracyM,
            @Size(max = 5000) String observations,
            @Valid List<AttendanceInput> attendance,
            @Valid List<EvaluationInput> evaluations,
            @Valid List<AppreciationInput> appreciations,
            @Size(max = 20000) String report,
            Long expectedVersion) {
    }

    public record Attendance(UUID participantId, LocalDate day, boolean present) {
    }

    public record Evaluation(UUID participantId, String criterion, String level) {
    }

    public record Appreciation(UUID participantId, String verdict, String comment) {
    }

    /** {@code receivedAt} : réception par le serveur (la date réelle de l'action est {@code startedAt}, saisie hors ligne). */
    public record Realisation(UUID id, UUID lineId, UUID planId, UUID accountId, UUID activityId, String plantationRef, String status,
                              OffsetDateTime startedAt, OffsetDateTime endedAt, BigDecimal latitude, BigDecimal longitude,
                              BigDecimal accuracyM, String observations, OffsetDateTime receivedAt, long version,
                              List<Attendance> attendance, List<Evaluation> evaluations, List<Appreciation> appreciations,
                              String report) {
    }

    public record RealisationSummary(UUID id, UUID lineId, UUID accountId, UUID activityId, String plantationRef, String status,
                                     OffsetDateTime startedAt, OffsetDateTime endedAt, OffsetDateTime receivedAt, long version) {
    }

    public record Criterion(String code, String label, int order) {
    }
}
