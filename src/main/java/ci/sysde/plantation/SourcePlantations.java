package ci.sysde.plantation;

import java.util.Optional;
import java.util.Set;

/**
 * Accès en lecture seule aux plantations de la base externe. SysDE n'en dépend que par cette interface : le contrat
 * réel du tiers n'est pas connu, l'adaptateur HTTP ({@link SourcePlantationsHttp}) porte un contrat provisoire.
 */
interface SourcePlantations {

    /** Plantations des secteurs donnés (codes) ; {@code null} : tous les secteurs. */
    java.util.List<PlantationExterne> lister(Set<String> codesSecteur);

    Optional<PlantationExterne> trouver(String ref);
}
