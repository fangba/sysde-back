package ci.sysde.reporting;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Formats de l'API reporting (champs en anglais, camelCase). Les champs {@code null} sont absents du JSON. */
public final class ReportingDtos {

    private ReportingDtos() {
    }

    /**
     * Une activité prévue au planning (plan validé, ou activité validée), avec sa réalisation si elle est terminée.
     * {@code category} : VISITE, FORMATION ou RENCONTRE. {@code anomaly} : SANS_GEO, HORS_SECTEUR, DOUBLON ou COURTE
     * (HORS_SECTEUR seulement si le contour du secteur est importé). Une activité <b>spontanée</b> ({@code spontaneous}) n'a ni ligne
     * ni date prévue : {@code lineId} et {@code plannedOn} sont absents, {@code doneOn} est le jour de sa réalisation ;
     * {@code realisationId} ouvre la réalisation (absent pour une activité prévue et non réalisée).
     */
    public record ReportActivity(UUID lineId, UUID realisationId, boolean spontaneous, UUID accountId, UUID activityId, String activity,
                                 String category, LocalDate plannedOn, LocalDate doneOn, String anomaly) {
    }

    public record Collaborator(UUID id, String name, String profile) {
    }

    /**
     * Synchronisation du mobile d'un collaborateur, telle que ses appareils la signalent. {@code lastSyncAt} est nul
     * si aucun appareil ne s'est jamais signalé ; {@code pending} somme les éléments en attente sur ses appareils.
     */
    public record DeviceSync(UUID accountId, String name, String profile, int devices, java.time.OffsetDateTime lastSyncAt,
                             long pending) {
    }

    /**
     * Une ligne de synthèse : {@code planned} activités prévues dans la période, {@code done} réalisées (terminées),
     * {@code late} non réalisées et prévues avant aujourd'hui, {@code toVerify} réalisées avec une anomalie.
     * {@code rate} = done / planned ; {@code status} : A_JOUR (≥ 90 %), LEGER_RETARD (≥ 75 %), EN_RETARD, ou SANS_PREVU.
     * Les activités spontanées (non planifiées) ne comptent <b>ni dans les prévues, ni dans les réalisées, ni dans le taux</b> :
     * {@code spontaneous} les compte (terminées dans la période) et {@code spontaneousToVerify} compte celles qui portent une anomalie.
     */
    public record ReportRow(UUID id, String label, int planned, int done, int late, int toVerify, Double rate, String status,
                            int spontaneous, int spontaneousToVerify) {
    }

    public record Synthesis(LocalDate today, String from, String to, ReportRow total, List<ReportRow> rows) {
    }

    /** Plannings annuels des collaborateurs par état (sans planning enregistré : brouillon). */
    public record Plans(int validated, int submitted, int draft) {
    }

    public record ReportSector(UUID id, String code, String name, String chiefName, int collaborators, Plans plans) {
    }

    public record ReportOperator(UUID id, String acronym, String name, int sectors, int collaborators, Plans plans) {
    }
}
