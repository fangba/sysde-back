package ci.sysde.common;

import java.time.Instant;
import java.util.List;

/** Corps de toute erreur : code métier stable, message affichable, détails de validation. */
public record ApiError(String code, String message, Instant horodatage, List<Detail> details) {

    public record Detail(String champ, String message) {
    }

    public static ApiError of(String code, String message) {
        return new ApiError(code, message, Instant.now(), List.of());
    }

    public static ApiError of(String code, String message, List<Detail> details) {
        return new ApiError(code, message, Instant.now(), details);
    }
}
