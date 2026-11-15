package ci.sysde.contour;

import java.io.IOException;
import java.util.UUID;

import ci.sysde.common.BusinessException;
import ci.sysde.contour.ContourDtos.ContourImport;
import ci.sysde.security.Acteur;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Contours de secteurs et de lots (endpoints-back.md §2ter). Import : {@code GERER_REFERENTIEL} ; lecture : {@code VISUALISER_CARTE}. */
@RestController
class ContoursController {

    private static final MediaType GEOJSON = new MediaType("application", "geo+json");

    private final ContoursService service;

    ContoursController(ContoursService service) {
        this.service = service;
    }

    /** Contrôle le fichier sans rien modifier : on choisit la cible, on dépose son fichier, on lit le rapport. */
    @PostMapping("/imports-contours")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_GERER_REFERENTIEL')")
    ContourImport valider(@RequestParam("file") MultipartFile fichier, @RequestParam("level") String niveau,
                          @RequestParam("targetId") UUID cibleId, Authentication auth) throws IOException {
        if (fichier.isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "REQUETE_INVALIDE", "Le fichier est vide.");
        }
        return service.valider(Acteur.depuis(auth), niveau.toUpperCase(java.util.Locale.ROOT), cibleId, fichier.getOriginalFilename(), fichier.getBytes());
    }

    @GetMapping("/imports-contours/{id}")
    @PreAuthorize("hasAuthority('PERM_GERER_REFERENTIEL')")
    ContourImport lire(@PathVariable UUID id, Authentication auth) {
        return service.lire(Acteur.depuis(auth), id);
    }

    @PostMapping("/imports-contours/{id}/application")
    @PreAuthorize("hasAuthority('PERM_GERER_REFERENTIEL')")
    ContourImport appliquer(@PathVariable UUID id, Authentication auth) {
        return service.appliquer(Acteur.depuis(auth), id);
    }

    @PostMapping("/imports-contours/{id}/annulation")
    @PreAuthorize("hasAuthority('PERM_GERER_REFERENTIEL')")
    ContourImport annuler(@PathVariable UUID id, Authentication auth) {
        return service.annuler(Acteur.depuis(auth), id);
    }

    @GetMapping("/secteurs/{id}/contour")
    @PreAuthorize("hasAuthority('PERM_VISUALISER_CARTE')")
    ResponseEntity<String> contourSecteur(@PathVariable UUID id, Authentication auth) {
        return ResponseEntity.ok().contentType(GEOJSON).body(service.contour(Acteur.depuis(auth), "SECTEUR", id));
    }

    @GetMapping("/lots/{id}/contour")
    @PreAuthorize("hasAuthority('PERM_VISUALISER_CARTE')")
    ResponseEntity<String> contourLot(@PathVariable UUID id, Authentication auth) {
        return ResponseEntity.ok().contentType(GEOJSON).body(service.contour(Acteur.depuis(auth), "LOT", id));
    }
}
