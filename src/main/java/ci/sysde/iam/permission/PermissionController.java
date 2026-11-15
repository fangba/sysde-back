package ci.sysde.iam.permission;

import java.util.List;
import java.util.Map;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Droits par profil (endpoints-back.md §2). Le vocabulaire est celui de la base : codes de permissions
 * ({@code GERER_COMPTE}…) et codes de profils ({@code AD}, {@code CS}…). Réservé à la permission {@code GERER_PERMISSIONS}.
 */
@RestController
@PreAuthorize("hasAuthority('PERM_GERER_PERMISSIONS')")
class PermissionController {

    private final PermissionService service;

    PermissionController(PermissionService service) {
        this.service = service;
    }

    /** Profils et permissions existants, avec leurs libellés (pour construire l'écran). */
    @GetMapping("/permissions/catalogue")
    PermissionService.Catalogue catalogue() {
        return service.catalogue();
    }

    /** Un bloc {@code { profil: [codes] }} pour les dix profils. */
    @GetMapping("/permissions")
    Map<String, List<String>> lire() {
        return service.lire();
    }

    /**
     * Enregistre d'un coup les droits des profils présents dans le bloc ; les profils absents ne changent pas.
     * Renvoie le bloc complet après enregistrement.
     */
    @PutMapping("/permissions")
    Map<String, List<String>> enregistrer(@RequestBody Map<String, List<String>> droits) {
        return service.enregistrer(droits);
    }
}
