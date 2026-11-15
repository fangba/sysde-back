package ci.sysde.mobile;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import ci.sysde.planning.PlanningDtos.AnnualPlan;
import ci.sysde.planning.PlanningDtos.PlanActivity;
import ci.sysde.realisation.RealisationDtos.Criterion;
import ci.sysde.referentiel.ReferentielDtos.Group;
import ci.sysde.referentiel.ReferentielDtos.Lot;
import ci.sysde.referentiel.ReferentielDtos.Section;
import ci.sysde.referentiel.ReferentielDtos.Sector;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Charges utiles de l'API mobile (endpoints-back.md §3ter). */
public final class MobileDtos {

    private MobileDtos() {
    }

    /** Qui est connecté, tel que le serveur le voit aujourd'hui (jamais lu dans le jeton). */
    public record Me(UUID accountId, String matricule, String profile, UUID operatorId) {
    }

    /** Où en est une réalisation côté serveur : de quoi décider quoi renvoyer, et avec quelle version attendue. */
    public record RealisationState(UUID id, UUID lineId, String status, long version) {
    }

    /**
     * Paquet de départ du mobile. {@code plan} est nul pour un profil sans planning. Les participants ne sont pas
     * inclus (liste longue) : le mobile les pagine par {@code GET /participants}.
     */
    public record Bundle(OffsetDateTime generatedAt, int year, Me account, AnnualPlan plan, List<PlanActivity> activities,
                         List<Criterion> criteria, List<Sector> sectors, List<Lot> lots, List<Section> sections,
                         List<Group> groups, List<RealisationState> realisations, long unreadNotifications) {
    }

    /** Signal de l'appareil à chaque synchronisation : qui il est, ce qui lui reste à envoyer. */
    public record SyncReport(@NotNull UUID deviceId, @Size(max = 100) String model, @Size(max = 50) String system,
                             @Size(max = 30) String appVersion, @NotNull @Min(0) Integer pending) {
    }
}
