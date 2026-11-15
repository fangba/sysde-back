package ci.sysde.plantation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import ci.sysde.common.BusinessException;
import ci.sysde.plantation.PlantationDtos.MapLotOption;
import ci.sysde.plantation.PlantationDtos.MapOperatorOption;
import ci.sysde.plantation.PlantationDtos.MapPlantation;
import ci.sysde.plantation.PlantationDtos.MapSectorOption;
import ci.sysde.plantation.Territoire.LotInfo;
import ci.sysde.security.Acteur;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Carte : catalogue des filtres et plantations avec leur contour. Le territoire visible suit le profil : AD et SU tout ;
 * CD son opérateur ; CS, SE et techniciens leur opérateur dans leurs secteurs (le serveur refuse tout autre secteur).
 */
@Service
class CarteService {

    private final Territoire territoire;
    private final PlantationService plantations;

    CarteService(Territoire territoire, PlantationService plantations) {
        this.territoire = territoire;
        this.plantations = plantations;
    }

    @Transactional(readOnly = true)
    List<MapOperatorOption> catalogue(Acteur acteur) {
        Map<UUID, List<String>> sections = territoire.sectionsParLot();
        Map<UUID, MapOperatorOption> operateurs = new LinkedHashMap<>();
        Map<UUID, Map<UUID, MapSectorOption>> secteurs = new LinkedHashMap<>();
        for (LotInfo l : territoire.visibles(acteur)) {
            if (l.operateurId() == null) {
                continue;                           // lot attribué à personne : rien à montrer sur la carte
            }
            var op = operateurs.computeIfAbsent(l.operateurId(),
                    id -> new MapOperatorOption(id, l.operateur(), l.operateurNom(), new ArrayList<>()));
            var parSecteur = secteurs.computeIfAbsent(l.operateurId(), id -> new LinkedHashMap<>());
            var secteur = parSecteur.computeIfAbsent(l.secteurId(), id -> {
                var s = new MapSectorOption(id, l.secteurCode(), l.secteurNom(), new ArrayList<>());
                op.sectors().add(s);
                return s;
            });
            secteur.lots().add(new MapLotOption(l.numero(), sections.getOrDefault(l.id(), List.of())));
        }
        return List.copyOf(operateurs.values());
    }

    @Transactional(readOnly = true)
    List<MapPlantation> plantations(Acteur acteur, UUID operateurId, UUID secteurId, Integer lot, String section) {
        List<LotInfo> visibles = territoire.visibles(acteur);
        if (visibles.stream().noneMatch(l -> operateurId.equals(l.operateurId()))) {
            throw refuse("Cet opérateur n'est pas dans votre périmètre.");
        }
        if (secteurId != null && visibles.stream().noneMatch(l -> secteurId.equals(l.secteurId()))) {
            throw refuse("Ce secteur n'est pas dans votre périmètre.");
        }
        return plantations.vues(acteur, secteurId, operateurId, lot, null).stream()
                .filter(v -> !v.p().polygone().isEmpty())
                .map(v -> new MapPlantation(v.p().ref(), v.p().code(), v.p().planteurNom(), v.lot().operateurId(), v.lot().secteurId(),
                        v.lot().numero(), v.sectionNom() != null ? v.sectionNom() : v.p().section(), v.p().superficieHa(),
                        v.p().polygone()))
                .filter(m -> section == null || section.isBlank() || section.equals(m.section()))
                .toList();
    }

    private static BusinessException refuse(String message) {
        return new BusinessException(HttpStatus.FORBIDDEN, "ACCES_REFUSE", message);
    }
}
