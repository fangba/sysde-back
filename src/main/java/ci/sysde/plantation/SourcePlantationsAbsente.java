package ci.sysde.plantation;

import java.util.Optional;
import java.util.Set;

import ci.sysde.common.BusinessException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Aucune source configurée ({@code sysde.plantations.url} absent) : les routes répondent 503, pas une liste vide trompeuse. */
@Component
@ConditionalOnProperty(name = "sysde.plantations.url", havingValue = "__aucune__", matchIfMissing = true)
class SourcePlantationsAbsente implements SourcePlantations {

    @Override
    public java.util.List<PlantationExterne> lister(Set<String> codesSecteur) {
        throw indisponible();
    }

    @Override
    public Optional<PlantationExterne> trouver(String ref) {
        throw indisponible();
    }

    private static BusinessException indisponible() {
        return new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "SOURCE_PLANTATIONS_INDISPONIBLE",
                "La base des plantations n'est pas configurée sur ce serveur.");
    }
}
