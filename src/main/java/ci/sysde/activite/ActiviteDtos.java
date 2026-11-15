package ci.sysde.activite;

import java.util.List;
import java.util.UUID;

import ci.sysde.planning.PlanningDtos.Frequency;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Référentiel des activités (endpoints-back.md §2, « Activités »). */
public final class ActiviteDtos {

    private ActiviteDtos() {
    }

    /** Ce qu'un profil fait de cette activité : ses fréquences (une par tranche de superficie ; vide : aucune fréquence définie). */
    public record ProfileFrequencies(String profile, List<Frequency> frequencies) {
    }

    public record Activity(UUID id, String code, String label, String category, int durationDays, boolean planned,
                           boolean withAttendance, boolean withEvaluation, boolean withReport, Integer minParticipants,
                           Integer maxParticipants, Integer monthFrom, Integer monthTo, int order, boolean active,
                           List<ProfileFrequencies> profiles) {
    }

    public record ProfileFrequenciesInput(@NotBlank @Size(min = 2, max = 2) String profile, List<@Valid FrequencyInput> frequencies) {
    }

    /** {@code period} : ANNEE, SEMESTRE, TRIMESTRE, MOIS, SEMAINE ; {@code per} : LOT, PLANTATION, SAIGNEUR, MONITEUR, GROUPE. */
    public record FrequencyInput(@Min(1) @Max(1000) Integer count, String period, String per, @Min(0) java.math.BigDecimal minAreaHa,
                                 @Min(0) java.math.BigDecimal maxAreaHa, @Size(max = 2000) String rule) {
    }

    /**
     * {@code code} : obligatoire à la création, immuable ensuite (majuscules et tirets bas). {@code order} absent : à la
     * suite des autres.
     */
    public record ActivityInput(
            @Pattern(regexp = "^[A-Z_]{1,40}$", message = "Le code se compose de majuscules et de tirets bas.") String code,
            @NotBlank @Size(max = 100) String label,
            @NotNull @Pattern(regexp = "^(FORMATION|VISITE|RENCONTRE)$") String category,
            @NotNull @Min(1) @Max(365) Integer durationDays,
            @NotNull Boolean planned,
            @NotNull Boolean withAttendance,
            @NotNull Boolean withEvaluation,
            @NotNull Boolean withReport,
            @Min(1) Integer minParticipants,
            @Min(1) Integer maxParticipants,
            @Min(1) @Max(12) Integer monthFrom,
            @Min(1) @Max(12) Integer monthTo,
            @Min(1) Integer order,
            @NotNull Boolean active,
            @NotNull List<@Valid ProfileFrequenciesInput> profiles) {
    }
}
