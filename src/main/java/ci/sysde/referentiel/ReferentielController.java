package ci.sysde.referentiel;

import java.util.List;
import java.util.UUID;

import ci.sysde.referentiel.ReferentielDtos.Attribution;
import ci.sysde.referentiel.ReferentielDtos.AttributionInput;
import ci.sysde.referentiel.ReferentielDtos.Contract;
import ci.sysde.referentiel.ReferentielDtos.ContractInput;
import ci.sysde.referentiel.ReferentielDtos.Exercise;
import ci.sysde.referentiel.ReferentielDtos.ExerciseInput;
import ci.sysde.referentiel.ReferentielDtos.Group;
import ci.sysde.referentiel.ReferentielDtos.GroupInput;
import ci.sysde.referentiel.ReferentielDtos.Lot;
import ci.sysde.referentiel.ReferentielDtos.LotInput;
import ci.sysde.referentiel.ReferentielDtos.Operator;
import ci.sysde.referentiel.ReferentielDtos.OperatorInput;
import ci.sysde.referentiel.ReferentielDtos.Section;
import ci.sysde.referentiel.ReferentielDtos.SectionInput;
import ci.sysde.referentiel.ReferentielDtos.Sector;
import ci.sysde.referentiel.ReferentielDtos.SectorInput;
import ci.sysde.security.Acteur;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Opérateurs, secteurs et lots (endpoints-back.md §2). Lecture pour tout compte connecté, dans son périmètre ;
 * écriture réservée à la permission {@code GERER_REFERENTIEL}. La base ne vérifie pas cette permission : le
 * contrôle est ici.
 */
@RestController
class ReferentielController {

    private static final String ECRITURE = "hasAuthority('PERM_GERER_REFERENTIEL')";
    /** Sections et groupes : le chef secteur (la base de départ ne l'accorde qu'à lui), dans son périmètre. */
    private static final String ECRITURE_SECTION = "hasAuthority('PERM_GERER_SECTION')";

    private final OperateurService operateurs;
    private final SecteurService secteurs;
    private final ContratService contrats;
    private final AttributionService attributions;
    private final SectionService sections;

    ReferentielController(OperateurService operateurs, SecteurService secteurs, ContratService contrats,
                          AttributionService attributions, SectionService sections) {
        this.sections = sections;
        this.operateurs = operateurs;
        this.secteurs = secteurs;
        this.contrats = contrats;
        this.attributions = attributions;
    }

    // ------------------------------------------------------------------ opérateurs

    @GetMapping("/operateurs")
    List<Operator> operateurs(Authentication auth) {
        return operateurs.lister(Acteur.depuis(auth));
    }

    @PostMapping("/operateurs")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(ECRITURE)
    Operator creerOperateur(@Valid @RequestBody OperatorInput in) {
        return operateurs.creer(in);
    }

    @PutMapping("/operateurs/{id}")
    @PreAuthorize(ECRITURE)
    Operator modifierOperateur(@PathVariable UUID id, @Valid @RequestBody OperatorInput in) {
        return operateurs.modifier(id, in);
    }

    // ------------------------------------------------------------------ secteurs

    @GetMapping("/secteurs")
    List<Sector> secteurs(Authentication auth) {
        return secteurs.secteurs(Acteur.depuis(auth));
    }

    @PostMapping("/secteurs")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(ECRITURE)
    Sector creerSecteur(@Valid @RequestBody SectorInput in) {
        return secteurs.creerSecteur(in);
    }

    @PutMapping("/secteurs/{id}")
    @PreAuthorize(ECRITURE)
    Sector modifierSecteur(@PathVariable UUID id, @Valid @RequestBody SectorInput in) {
        return secteurs.modifierSecteur(id, in);
    }

    // ------------------------------------------------------------------ lots

    @GetMapping("/lots")
    List<Lot> lots(Authentication auth) {
        return secteurs.lots(Acteur.depuis(auth));
    }

    @PostMapping("/lots")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(ECRITURE)
    Lot creerLot(@Valid @RequestBody LotInput in) {
        return secteurs.creerLot(in);
    }

    @PutMapping("/lots/{id}")
    @PreAuthorize(ECRITURE)
    Lot modifierLot(@PathVariable UUID id, @Valid @RequestBody LotInput in) {
        return secteurs.modifierLot(id, in);
    }

    // ------------------------------------------------------------------ contrats

    @GetMapping("/contrats")
    List<Contract> contrats(Authentication auth) {
        return contrats.lister(Acteur.depuis(auth));
    }

    @PostMapping("/contrats")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(ECRITURE)
    Contract creerContrat(@Valid @RequestBody ContractInput in, Authentication auth) {
        return contrats.creer(Acteur.depuis(auth), in);
    }

    @PutMapping("/contrats/{id}")
    @PreAuthorize(ECRITURE)
    Contract modifierContrat(@PathVariable UUID id, @Valid @RequestBody ContractInput in) {
        return contrats.modifier(id, in);
    }

    // ------------------------------------------------------------------ attributions

    @GetMapping("/attributions")
    List<Attribution> attributions(Authentication auth) {
        return attributions.lister(Acteur.depuis(auth));
    }

    @PostMapping("/attributions")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(ECRITURE)
    Attribution creerAttribution(@Valid @RequestBody AttributionInput in) {
        return attributions.creer(in);
    }

    @PutMapping("/attributions/{id}")
    @PreAuthorize(ECRITURE)
    Attribution modifierAttribution(@PathVariable UUID id, @Valid @RequestBody AttributionInput in) {
        return attributions.modifier(id, in);
    }

    // ------------------------------------------------------------------ exercices (générés par la base : pas de POST)

    @GetMapping("/exercices")
    List<Exercise> exercices(Authentication auth) {
        return attributions.exercices(Acteur.depuis(auth));
    }

    @PutMapping("/exercices/{id}")
    @PreAuthorize(ECRITURE)
    Exercise cloturerExercice(@PathVariable UUID id, @Valid @RequestBody ExerciseInput in) {
        return attributions.cloturer(id, in);
    }

    // ------------------------------------------------------------------ sections et groupes

    @GetMapping("/sections")
    List<Section> sections(Authentication auth) {
        return sections.sections(Acteur.depuis(auth));
    }

    @PostMapping("/sections")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(ECRITURE_SECTION)
    Section creerSection(@Valid @RequestBody SectionInput in, Authentication auth) {
        return sections.creerSection(Acteur.depuis(auth), in);
    }

    @PutMapping("/sections/{id}")
    @PreAuthorize(ECRITURE_SECTION)
    Section modifierSection(@PathVariable UUID id, @Valid @RequestBody SectionInput in, Authentication auth) {
        return sections.modifierSection(Acteur.depuis(auth), id, in);
    }

    @GetMapping("/groupes")
    List<Group> groupes(Authentication auth) {
        return sections.groupes(Acteur.depuis(auth));
    }

    @PostMapping("/groupes")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(ECRITURE_SECTION)
    Group creerGroupe(@Valid @RequestBody GroupInput in, Authentication auth) {
        return sections.creerGroupe(Acteur.depuis(auth), in);
    }

    @PutMapping("/groupes/{id}")
    @PreAuthorize(ECRITURE_SECTION)
    Group modifierGroupe(@PathVariable UUID id, @Valid @RequestBody GroupInput in, Authentication auth) {
        return sections.modifierGroupe(Acteur.depuis(auth), id, in);
    }
}
