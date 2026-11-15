package ci.sysde.referentiel;

import java.util.List;
import java.util.UUID;

import ci.sysde.referentiel.ReferentielDtos.GroupMember;
import ci.sysde.referentiel.ReferentielDtos.GroupMemberInput;
import ci.sysde.referentiel.ReferentielDtos.GroupMembersInput;
import ci.sysde.security.Acteur;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Membres d'un groupe de vulgarisation (endpoints-back.md §2, « Groupes »). */
@RestController
class MembresGroupeController {

    private final MembresGroupeService service;

    MembresGroupeController(MembresGroupeService service) {
        this.service = service;
    }

    @GetMapping("/groupes/{id}/membres")
    @PreAuthorize("hasAuthority('PERM_CONSULTER_ACTIVITE')")
    List<GroupMember> lister(@PathVariable UUID id, @RequestParam(defaultValue = "false") boolean historique, Authentication auth) {
        return service.lister(Acteur.depuis(auth), id, historique);
    }

    /** 201 si le membre est ajouté, 200 s'il l'était déjà (renvoi sans effet). */
    @PostMapping("/groupes/{id}/membres")
    @PreAuthorize("hasAnyAuthority('PERM_GERER_SECTION', 'PERM_REALISER_ACTIVITE')")
    ResponseEntity<GroupMember> ajouter(@PathVariable UUID id, @Valid @RequestBody GroupMemberInput in, Authentication auth) {
        var r = service.ajouter(Acteur.depuis(auth), id, in.participantId(), in.since());
        return ResponseEntity.status(r.cree() ? HttpStatus.CREATED : HttpStatus.OK).body(r.membre());
    }

    @PutMapping("/groupes/{id}/membres")
    @PreAuthorize("hasAnyAuthority('PERM_GERER_SECTION', 'PERM_REALISER_ACTIVITE')")
    List<GroupMember> remplacer(@PathVariable UUID id, @Valid @RequestBody GroupMembersInput in, Authentication auth) {
        return service.remplacer(Acteur.depuis(auth), id, in.participantIds());
    }

    @DeleteMapping("/groupes/{id}/membres/{participantId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyAuthority('PERM_GERER_SECTION', 'PERM_REALISER_ACTIVITE')")
    void retirer(@PathVariable UUID id, @PathVariable UUID participantId, Authentication auth) {
        service.retirer(Acteur.depuis(auth), id, participantId);
    }
}
