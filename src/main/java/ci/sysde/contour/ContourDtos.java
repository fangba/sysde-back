package ci.sysde.contour;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Formats de l'API contours (champs en anglais, camelCase). */
public final class ContourDtos {

    private ContourDtos() {
    }

    /**
     * Rapport de contrôle d'un fichier de contours. {@code status} : PRET (applicable), REJETE (erreurs bloquantes), APPLIQUE ou ANNULE.
     * {@code warnings} ne bloquent pas (recoupements, débordements, contour remplacé). {@code areaHa} : superficie géodésique.
     * {@code bbox} : {minLon, minLat, maxLon, maxLat}.
     */
    public record ContourImport(UUID id, String level, UUID targetId, String targetLabel, String format, String fileName, String status,
                                int polygons, Double areaHa, List<Double> bbox, List<String> errors, List<String> warnings,
                                OffsetDateTime createdAt) {
    }
}
