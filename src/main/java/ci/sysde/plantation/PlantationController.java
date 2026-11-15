package ci.sysde.plantation;

import java.util.List;
import java.util.UUID;

import ci.sysde.plantation.PlantationDtos.AssignmentInput;
import ci.sysde.plantation.PlantationDtos.MapOperatorOption;
import ci.sysde.plantation.PlantationDtos.MapPlantation;
import ci.sysde.plantation.PlantationDtos.Plantation;
import ci.sysde.plantation.PlantationDtos.SectionInput;
import ci.sysde.security.Acteur;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Plantations (endpoints-back.md §6) et carte (§7). Lecture : {@code VISUALISER_CARTE} ; affectation à une section :
 * {@code GERER_SECTION} et profil chef secteur.
 */
@RestController
@Validated
class PlantationController {

    private final PlantationService plantations;
    private final CarteService carte;

    PlantationController(PlantationService plantations, CarteService carte) {
        this.plantations = plantations;
        this.carte = carte;
    }

    @GetMapping("/plantations")
    @PreAuthorize("hasAuthority('PERM_VISUALISER_CARTE')")
    List<Plantation> lister(@RequestParam(required = false) @Size(max = 100) String q, @RequestParam(required = false) Integer lot,
                            @RequestParam(required = false) UUID sectorId, Authentication auth) {
        return plantations.lister(Acteur.depuis(auth), q, lot, sectorId);
    }

    @PutMapping("/plantations/{id}/section")
    @PreAuthorize("hasAuthority('PERM_GERER_SECTION')")
    Plantation affecter(@PathVariable String id, @RequestBody SectionInput in, Authentication auth) {
        List<Plantation> changees = plantations.affecter(Acteur.depuis(auth), List.of(new AssignmentInput(id, in.sectionId())));
        // aucune modification (déjà dans cet état) : on renvoie l'état courant
        return changees.isEmpty() ? plantations.lire(Acteur.depuis(auth), id) : changees.get(0);
    }

    @PutMapping("/plantations/affectations")
    @PreAuthorize("hasAuthority('PERM_GERER_SECTION')")
    List<Plantation> affecterEnLot(@RequestBody List<@Valid AssignmentInput> in, Authentication auth) {
        return plantations.affecter(Acteur.depuis(auth), in);
    }

    @GetMapping("/carte/catalogue")
    @PreAuthorize("hasAuthority('PERM_VISUALISER_CARTE')")
    List<MapOperatorOption> catalogue(Authentication auth) {
        return carte.catalogue(Acteur.depuis(auth));
    }

    @GetMapping("/carte/plantations")
    @PreAuthorize("hasAuthority('PERM_VISUALISER_CARTE')")
    List<MapPlantation> cartePlantations(@RequestParam UUID operatorId, @RequestParam(required = false) UUID sectorId,
                                         @RequestParam(required = false) Integer lot, @RequestParam(required = false) String section,
                                         Authentication auth) {
        return carte.plantations(Acteur.depuis(auth), operatorId, sectorId, lot, section);
    }
}
