package ci.sysde.iam.compte;

import java.util.UUID;

import ci.sysde.common.Page;
import ci.sysde.common.PageRequete;
import ci.sysde.iam.compte.CompteDtos.Account;
import ci.sysde.iam.compte.CompteDtos.AccountInput;
import ci.sysde.iam.compte.CompteDtos.AccountWithPassword;
import ci.sysde.iam.compte.CompteDtos.MatriculeCorrection;
import ci.sysde.iam.compte.CompteDtos.PersonInput;
import ci.sysde.iam.compte.CompteDtos.ProfileChange;
import ci.sysde.iam.compte.CompteDtos.ReasonInput;
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

/**
 * Comptes (endpoints-back.md §2bis). Chaque route exige une permission ; la base décide ensuite de l'autorité sur la cible
 * (hiérarchie, organisation) et ses messages remontent tels quels (403, 409, 422).
 */
@RestController
class ComptesController {

    private final ComptesService service;

    ComptesController(ComptesService service) {
        this.service = service;
    }

    @GetMapping("/comptes")
    @PreAuthorize("hasAuthority('PERM_GERER_COMPTE')")
    Page<Account> lister(@RequestParam(required = false) String q, @RequestParam(required = false) String profile,
                         @RequestParam(required = false) String status, @RequestParam(required = false) UUID operatorId,
                         @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
                         @RequestParam(required = false) String sort, Authentication auth) {
        return service.lister(Acteur.depuis(auth), q, profile, status, operatorId, PageRequete.de(page, size), sort);
    }

    @GetMapping("/comptes/{id}")
    @PreAuthorize("hasAuthority('PERM_GERER_COMPTE')")
    Account lire(@PathVariable UUID id, Authentication auth) {
        return service.lire(Acteur.depuis(auth), id);
    }

    @PostMapping("/comptes")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_CREER_COMPTE')")
    AccountWithPassword creer(@Valid @RequestBody AccountInput in) {
        return service.creer(in);
    }

    @PutMapping("/comptes/{id}")
    @PreAuthorize("hasAuthority('PERM_GERER_COMPTE')")
    Account modifier(@PathVariable UUID id, @Valid @RequestBody PersonInput in, Authentication auth) {
        return service.modifierPersonne(Acteur.depuis(auth), id, in);
    }

    @PostMapping("/comptes/{id}/desactivation")
    @PreAuthorize("hasAuthority('PERM_GERER_COMPTE')")
    Account desactiver(@PathVariable UUID id, @Valid @RequestBody(required = false) ReasonInput in, Authentication auth) {
        return service.desactiver(Acteur.depuis(auth), id, in == null ? null : in.reason());
    }

    @PostMapping("/comptes/{id}/reactivation")
    @PreAuthorize("hasAuthority('PERM_GERER_COMPTE')")
    Account reactiver(@PathVariable UUID id, @Valid @RequestBody(required = false) ReasonInput in, Authentication auth) {
        return service.reactiver(Acteur.depuis(auth), id, in == null ? null : in.reason());
    }

    @PostMapping("/comptes/{id}/reinitialisation")
    @PreAuthorize("hasAuthority('PERM_GERER_COMPTE')")
    AccountWithPassword reinitialiser(@PathVariable UUID id, @Valid @RequestBody(required = false) ReasonInput in, Authentication auth) {
        return service.reinitialiser(Acteur.depuis(auth), id, in == null ? null : in.reason());
    }

    @PutMapping("/comptes/{id}/profil")
    @PreAuthorize("hasAuthority('PERM_CHANGER_PROFIL')")
    Account changerProfil(@PathVariable UUID id, @Valid @RequestBody ProfileChange in, Authentication auth) {
        return service.changerProfil(Acteur.depuis(auth), id, in.profile(), in.reason());
    }

    @PutMapping("/comptes/{id}/matricule")
    @PreAuthorize("hasAuthority('PERM_GERER_COMPTE')")
    Account corrigerMatricule(@PathVariable UUID id, @Valid @RequestBody MatriculeCorrection in) {
        return service.corrigerMatricule(id, in.number(), in.reason());
    }
}
