package ci.sysde.plantation;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/** Source de plantations des tests d'intégration : une liste en mémoire que chaque test remplit. */
@Component
@Primary
class SourcePlantationsDeTest implements SourcePlantations {

    static final List<PlantationExterne> DONNEES = new CopyOnWriteArrayList<>();

    @Override
    public List<PlantationExterne> lister(Set<String> codesSecteur) {
        return new ArrayList<>(DONNEES.stream().filter(p -> codesSecteur == null || codesSecteur.contains(p.secteurCode())).toList());
    }

    @Override
    public Optional<PlantationExterne> trouver(String ref) {
        return DONNEES.stream().filter(p -> p.ref().equals(ref)).findFirst();
    }
}
