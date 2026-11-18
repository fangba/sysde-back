package ci.sysde.planning;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Formats de l'API planning (champs en anglais, camelCase). Les champs absents valent « non renseigné ». */
public final class PlanningDtos {

    private PlanningDtos() {
    }

    /** La personne à qui appartient le planning. {@code id} est l'identifiant du <b>compte</b>. */
    public record PersonRef(UUID id, String matricule, String profile, String lastName, String firstName) {
    }

    /** Une activité prévue : période, activité, lot ou section (identifiants), plantation visitée ou groupe animé. */
    public record PlanLine(UUID id, LocalDate date, LocalDate endDate, UUID activityId, UUID lotId, Integer lotNumber,
                           UUID sectionId, String sectionName, String plantationRef, UUID groupId) {
    }

    /** Ligne à enregistrer. {@code endDate} absent : une activité d'un jour. */
    public record PlanLineInput(
            @NotNull LocalDate date,
            LocalDate endDate,
            @NotNull UUID activityId,
            UUID lotId,
            UUID sectionId,
            @Size(max = 50) String plantationRef,
            UUID groupId) {
    }

    /** Dernier rejet, conservé jusqu'à la nouvelle soumission. */
    public record Rejection(String reason, OffsetDateTime date) {
    }

    /** {@code status} : SOUMIS, VALIDE ou REJETE. Une activité sans entrée est en brouillon. */
    public record Submission(UUID activityId, String status, Rejection rejection) {
    }

    /**
     * Planning annuel d'un compte. {@code id} est absent tant que le planning n'a jamais été enregistré (équipe, lecture seule).
     * {@code status} : BROUILLON, SOUMIS ou VALIDE. {@code editedBy} : SELF ou SECRETARY.
     */
    public record AnnualPlan(UUID id, PersonRef account, int year, String status, List<PlanLine> lines, Rejection rejection,
                             List<Submission> submissions, String editedBy, OffsetDateTime editedAt) {
    }

    public record ReasonInput(@NotBlank @Size(max = 500) String reason) {
    }

    /** Fréquence d'une activité pour un profil : {@code count} séances par {@code period}, pour chaque {@code per}. */
    public record Frequency(Integer count, String period, String per, BigDecimal minAreaHa, BigDecimal maxAreaHa, String rule) {
    }

    /**
     * Résultat du contrôle des dates d'une activité face à sa fréquence. {@code expected} : séances attendues sur l'année
     * (absent si la fréquence est « par » une unité, ou ne se calcule pas) ; {@code actual} : séances programmées.
     */
    public record FrequencyCheck(UUID activityId, String label, boolean ok, Integer expected, int actual, List<String> issues) {
    }

    /** Activité planifiable par le profil de l'appelant (référentiel en lecture seule). */
    public record PlanActivity(UUID id, String code, String label, String category, int durationDays, boolean planned,
                               Integer monthStart, Integer monthEnd, List<Frequency> frequencies) {
    }
}
