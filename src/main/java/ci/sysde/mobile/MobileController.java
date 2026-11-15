package ci.sysde.mobile;

import ci.sysde.mobile.MobileDtos.Bundle;
import ci.sysde.mobile.MobileDtos.SyncReport;
import ci.sysde.security.Acteur;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** API du mobile (endpoints-back.md §3ter). Pas de permission propre : le contenu suit le profil et le périmètre. */
@RestController
class MobileController {

    private final MobileService service;

    MobileController(MobileService service) {
        this.service = service;
    }

    @GetMapping("/mobile/amorcage")
    Bundle amorcage(@RequestParam(required = false) Integer annee, Authentication auth) {
        return service.amorcage(Acteur.depuis(auth), annee);
    }

    @PostMapping("/mobile/synchronisation")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void signaler(@Valid @RequestBody SyncReport in, Authentication auth) {
        service.signaler(Acteur.depuis(auth), in);
    }
}
