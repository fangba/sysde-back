package ci.sysde.iam.compte;

import java.util.List;
import java.util.UUID;

import ci.sysde.iam.compte.CompteDtos.Assignment;
import ci.sysde.iam.compte.CompteDtos.AssignmentInput;
import ci.sysde.iam.compte.CompteDtos.TakeoverInput;
import ci.sysde.iam.compte.CompteDtos.TakeoverResult;
import ci.sysde.security.Acteur;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Affectations d'un compte et reprise (endpoints-back.md §2bis). Le droit d'affecter dépend du profil de la cible (voir le service). */
@RestController
class AffectationsController {

    private final AffectationsService service;

    AffectationsController(AffectationsService service) {
        this.service = service;
    }

    @GetMapping("/comptes/{id}/affectations")
    @PreAuthorize("hasAuthority('PERM_GERER_COMPTE')")
    List<Assignment> lister(@PathVariable UUID id, Authentication auth) {
        return service.lister(Acteur.depuis(auth), id);
    }

    @PostMapping("/comptes/{id}/affectations")
    @ResponseStatus(HttpStatus.CREATED)
    Assignment affecter(@PathVariable UUID id, @Valid @RequestBody AssignmentInput in, Authentication auth) {
        return service.affecter(Acteur.depuis(auth), id, in);
    }

    @PostMapping("/comptes/{id}/affectations/{affectationId}/cloture")
    Assignment cloturer(@PathVariable UUID id, @PathVariable UUID affectationId, Authentication auth) {
        return service.cloturer(Acteur.depuis(auth), id, affectationId);
    }

    @PostMapping("/comptes/{id}/reprise")
    @PreAuthorize("hasAuthority('PERM_REPRENDRE_COMPTE')")
    TakeoverResult reprendre(@PathVariable UUID id, @Valid @RequestBody TakeoverInput in, Authentication auth) {
        return service.reprendre(Acteur.depuis(auth), id, in.successorId(), in.reason());
    }
}
