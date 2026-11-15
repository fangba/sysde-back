package ci.sysde.activite;

import java.util.List;
import java.util.UUID;

import ci.sysde.activite.ActiviteDtos.Activity;
import ci.sysde.activite.ActiviteDtos.ActivityInput;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Référentiel des activités : lecture pour qui gère le référentiel ou consulte les activités, écriture pour {@code GERER_REFERENTIEL}. */
@RestController
class ActiviteController {

    private final ActiviteService service;

    ActiviteController(ActiviteService service) {
        this.service = service;
    }

    @GetMapping("/activites")
    @PreAuthorize("hasAnyAuthority('PERM_GERER_REFERENTIEL', 'PERM_CONSULTER_ACTIVITE')")
    List<Activity> lister() {
        return service.lister();
    }

    @GetMapping("/activites/{id}")
    @PreAuthorize("hasAnyAuthority('PERM_GERER_REFERENTIEL', 'PERM_CONSULTER_ACTIVITE')")
    Activity lire(@PathVariable UUID id) {
        return service.lire(id);
    }

    @PostMapping("/activites")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_GERER_REFERENTIEL')")
    Activity creer(@Valid @RequestBody ActivityInput in) {
        return service.creer(in);
    }

    @PutMapping("/activites/{id}")
    @PreAuthorize("hasAuthority('PERM_GERER_REFERENTIEL')")
    Activity modifier(@PathVariable UUID id, @Valid @RequestBody ActivityInput in) {
        return service.modifier(id, in);
    }
}
