package ci.sysde.realisation;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import ci.sysde.common.Page;
import ci.sysde.common.PageRequete;
import ci.sysde.realisation.RealisationDtos.Criterion;
import ci.sysde.realisation.RealisationDtos.Participant;
import ci.sysde.realisation.RealisationDtos.ParticipantInput;
import ci.sysde.realisation.RealisationDtos.Realisation;
import ci.sysde.realisation.RealisationDtos.RealisationInput;
import ci.sysde.realisation.RealisationDtos.RealisationSummary;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Réalisations et participants (endpoints-back.md §3bis). Écriture : {@code REALISER_ACTIVITE} ; lecture : {@code CONSULTER_ACTIVITE}. */
@RestController
class RealisationController {

    private final RealisationService realisations;
    private final ParticipantService participants;

    RealisationController(RealisationService realisations, ParticipantService participants) {
        this.realisations = realisations;
        this.participants = participants;
    }

    @GetMapping("/plannings/criteres-evaluation")
    @PreAuthorize("hasAuthority('PERM_CONSULTER_ACTIVITE')")
    List<Criterion> criteres() {
        return realisations.criteres();
    }

    // ------------------------------------------------------------------ réalisations

    /** Envoi (ou renvoi) d'une réalisation : l'identifiant est celui de l'appareil. Toujours 200 : le rejeu est sans effet. */
    @PutMapping("/realisations/{id}")
    @PreAuthorize("hasAuthority('PERM_REALISER_ACTIVITE')")
    Realisation enregistrer(@PathVariable UUID id, @Valid @RequestBody RealisationInput in, Authentication auth) {
        return realisations.enregistrer(Acteur.depuis(auth), id, in);
    }

    @GetMapping("/realisations/{id}")
    @PreAuthorize("hasAuthority('PERM_CONSULTER_ACTIVITE')")
    Realisation lire(@PathVariable UUID id, Authentication auth) {
        return realisations.lire(Acteur.depuis(auth), id);
    }

    @GetMapping("/realisations")
    @PreAuthorize("hasAuthority('PERM_CONSULTER_ACTIVITE')")
    Page<RealisationSummary> lister(@RequestParam(required = false) UUID accountId, @RequestParam(required = false) UUID activityId,
                                    @RequestParam(required = false) String status, @RequestParam(required = false) OffsetDateTime from,
                                    @RequestParam(required = false) OffsetDateTime to, @RequestParam(required = false) Integer page,
                                    @RequestParam(required = false) Integer size, Authentication auth) {
        return realisations.lister(Acteur.depuis(auth), accountId, activityId, status, from, to, PageRequete.de(page, size));
    }

    // ------------------------------------------------------------------ participants

    @GetMapping("/participants")
    @PreAuthorize("hasAuthority('PERM_CONSULTER_ACTIVITE')")
    Page<Participant> participants(@RequestParam(required = false) String q, @RequestParam(required = false) Integer page,
                                   @RequestParam(required = false) Integer size, @RequestParam(required = false) String sort, Authentication auth) {
        return participants.lister(Acteur.depuis(auth), q, PageRequete.de(page, size), sort);
    }

    @PostMapping("/participants")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_REALISER_ACTIVITE')")
    Participant creerParticipant(@Valid @RequestBody ParticipantInput in, Authentication auth) {
        return participants.creer(Acteur.depuis(auth), in);
    }

    @PutMapping("/participants/{id}")
    @PreAuthorize("hasAuthority('PERM_REALISER_ACTIVITE')")
    Participant modifierParticipant(@PathVariable UUID id, @Valid @RequestBody ParticipantInput in, Authentication auth) {
        return participants.modifier(Acteur.depuis(auth), id, in);
    }
}
