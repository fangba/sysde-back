package ci.sysde.reporting;

import ci.sysde.reporting.ReportingDtos.DashboardData;
import ci.sysde.security.Acteur;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /dashboard} (endpoints-back.md §5bis). Permission {@code CONSULTER_DASHBOARD}, profils AD, SU, CD, CS. Les paramètres
 * d'identité envoyés par le front ({@code profile}, {@code operator}, {@code sector}) sont <b>ignorés</b> : l'étendue vient du compte.
 */
@RestController
class DashboardController {

    private final DashboardService service;

    DashboardController(DashboardService service) {
        this.service = service;
    }

    @GetMapping("/dashboard")
    @PreAuthorize("hasAuthority('PERM_CONSULTER_DASHBOARD')")
    DashboardData lire(@RequestParam(required = false) String filterOperator, @RequestParam(required = false) String filterSector,
                       Authentication auth) {
        return service.lire(Acteur.depuis(auth), filterOperator, filterSector);
    }
}
