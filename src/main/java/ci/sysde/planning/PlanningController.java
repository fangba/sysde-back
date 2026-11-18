package ci.sysde.planning;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import ci.sysde.planning.PlanningDtos.AnnualPlan;
import ci.sysde.planning.PlanningDtos.FrequencyCheck;
import ci.sysde.planning.PlanningDtos.PlanActivity;
import ci.sysde.planning.PlanningDtos.PlanLineInput;
import ci.sysde.planning.PlanningDtos.ReasonInput;
import ci.sysde.security.Acteur;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Planning annuel (endpoints-back.md §3). {@code annee} absent : l'année en cours. Les identifiants sont ceux des <b>comptes</b>. */
@RestController
class PlanningController {

    private final PlanningService service;

    PlanningController(PlanningService service) {
        this.service = service;
    }

    private static int annee(Integer annee) {
        return annee == null ? LocalDate.now().getYear() : annee;
    }

    /** Activités que le profil de l'appelant peut planifier, avec leurs fréquences. */
    @GetMapping("/plannings/activites")
    @PreAuthorize("hasAuthority('PERM_CONSULTER_PLANNING')")
    List<PlanActivity> activites(Authentication auth) {
        return service.activites(Acteur.depuis(auth));
    }

    // ------------------------------------------------------------------ mon planning

    @GetMapping("/plannings/mon-planning")
    @PreAuthorize("hasAuthority('PERM_CONSULTER_PLANNING')")
    AnnualPlan monPlanning(@RequestParam(required = false) Integer annee, Authentication auth) {
        return service.monPlanning(Acteur.depuis(auth), annee(annee));
    }

    /** Contrôle des dates de mon planning face aux fréquences de mon profil (informatif). */
    @GetMapping("/plannings/mon-planning/controle")
    @PreAuthorize("hasAuthority('PERM_CONSULTER_PLANNING')")
    List<FrequencyCheck> controle(@RequestParam(required = false) Integer annee, Authentication auth) {
        return service.controlerMonPlanning(Acteur.depuis(auth), annee(annee));
    }

    /** Contrôle de dates pas encore enregistrées (le brouillon de l'éditeur) : rien n'est écrit. */
    @PostMapping("/plannings/mon-planning/controle")
    @PreAuthorize("hasAuthority('PERM_CONSULTER_PLANNING')")
    List<FrequencyCheck> controleBrouillon(@RequestBody List<@Valid PlanLineInput> lignes, Authentication auth) {
        return service.controlerBrouillon(Acteur.depuis(auth), lignes);
    }

    /** Contrôle des fréquences du planning d'un collaborateur du secteur (chef secteur). */
    @GetMapping("/plannings/{planId}/controle")
    @PreAuthorize("hasAuthority('PERM_CONSULTER_PLANNING')")
    List<FrequencyCheck> controleDeLEquipe(@PathVariable UUID planId, Authentication auth) {
        return service.controlerPlanDeLEquipe(Acteur.depuis(auth), planId);
    }

    @PutMapping("/plannings/mon-planning/lignes")
    @PreAuthorize("hasAuthority('PERM_ENREGISTRER_PLANNING')")
    AnnualPlan remplacerLignes(@RequestParam(required = false) Integer annee, @RequestBody List<@Valid PlanLineInput> lignes, Authentication auth) {
        return service.remplacerMesLignes(Acteur.depuis(auth), annee(annee), lignes);
    }

    @PostMapping("/plannings/mon-planning/soumission")
    @PreAuthorize("hasAuthority('PERM_ENREGISTRER_PLANNING')")
    AnnualPlan soumettre(@RequestParam(required = false) Integer annee, Authentication auth) {
        return service.soumettreMonPlanning(Acteur.depuis(auth), annee(annee));
    }

    @PostMapping("/plannings/mon-planning/activites/{activiteId}/soumission")
    @PreAuthorize("hasAuthority('PERM_ENREGISTRER_PLANNING')")
    AnnualPlan soumettreActivite(@PathVariable UUID activiteId, @RequestParam(required = false) Integer annee, Authentication auth) {
        return service.soumettreActivite(Acteur.depuis(auth), annee(annee), activiteId);
    }

    // ------------------------------------------------------------------ équipe et secrétaire

    @GetMapping("/plannings/equipe")
    @PreAuthorize("hasAuthority('PERM_CONSULTER_PLANNING')")
    List<AnnualPlan> equipe(@RequestParam(required = false) Integer annee, Authentication auth) {
        return service.equipe(Acteur.depuis(auth), annee(annee));
    }

    @GetMapping("/plannings/collaborateurs/{compteId}")
    @PreAuthorize("hasAuthority('PERM_CONSULTER_PLANNING')")
    AnnualPlan planDuCollaborateur(@PathVariable UUID compteId, @RequestParam(required = false) Integer annee, Authentication auth) {
        return service.planDuCollaborateur(Acteur.depuis(auth), compteId, annee(annee));
    }

    @PutMapping("/plannings/collaborateurs/{compteId}/lignes")
    @PreAuthorize("hasAuthority('PERM_ENREGISTRER_PLANNING')")
    AnnualPlan remplacerLignesDuCollaborateur(@PathVariable UUID compteId, @RequestParam(required = false) Integer annee,
                                              @RequestBody List<@Valid PlanLineInput> lignes, Authentication auth) {
        return service.remplacerLignesDuCollaborateur(Acteur.depuis(auth), compteId, annee(annee), lignes);
    }

    @PostMapping("/plannings/collaborateurs/{compteId}/soumission")
    @PreAuthorize("hasAuthority('PERM_ENREGISTRER_PLANNING')")
    AnnualPlan soumettreDuCollaborateur(@PathVariable UUID compteId, @RequestParam(required = false) Integer annee, Authentication auth) {
        return service.soumettreDuCollaborateur(Acteur.depuis(auth), compteId, annee(annee));
    }

    // ------------------------------------------------------------------ décisions du chef secteur

    @PostMapping("/plannings/{planId}/validation")
    @PreAuthorize("hasAuthority('PERM_VALIDER_PLANNING')")
    AnnualPlan valider(@PathVariable UUID planId, Authentication auth) {
        return service.valider(Acteur.depuis(auth), planId);
    }

    @PostMapping("/plannings/{planId}/rejet")
    @PreAuthorize("hasAuthority('PERM_VALIDER_PLANNING')")
    AnnualPlan rejeter(@PathVariable UUID planId, @Valid @RequestBody ReasonInput in, Authentication auth) {
        return service.rejeter(Acteur.depuis(auth), planId, in.reason());
    }

    @PostMapping("/plannings/{planId}/activites/{activiteId}/validation")
    @PreAuthorize("hasAuthority('PERM_VALIDER_PLANNING')")
    AnnualPlan validerActivite(@PathVariable UUID planId, @PathVariable UUID activiteId, Authentication auth) {
        return service.validerActivite(Acteur.depuis(auth), planId, activiteId);
    }

    @PostMapping("/plannings/{planId}/activites/{activiteId}/rejet")
    @PreAuthorize("hasAuthority('PERM_VALIDER_PLANNING')")
    AnnualPlan rejeterActivite(@PathVariable UUID planId, @PathVariable UUID activiteId, @Valid @RequestBody ReasonInput in, Authentication auth) {
        return service.rejeterActivite(Acteur.depuis(auth), planId, activiteId, in.reason());
    }
}
