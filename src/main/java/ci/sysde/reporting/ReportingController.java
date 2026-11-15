package ci.sysde.reporting;

import java.util.List;

import ci.sysde.reporting.ReportingDtos.Collaborator;
import ci.sysde.reporting.ReportingDtos.DeviceSync;
import ci.sysde.reporting.ReportingDtos.ReportActivity;
import ci.sysde.reporting.ReportingDtos.ReportOperator;
import ci.sysde.reporting.ReportingDtos.ReportSector;
import ci.sysde.reporting.ReportingDtos.Synthesis;
import ci.sysde.security.Acteur;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reporting (endpoints-back.md §7bis). Permission {@code CONSULTER_REPORTING} ; chaque niveau est réservé à son profil
 * (403 sinon). {@code du} et {@code au} : mois {@code aaaa-mm}, {@code au} inclus ; sans eux, le mois en cours.
 */
@RestController
@PreAuthorize("hasAuthority('PERM_CONSULTER_REPORTING')")
class ReportingController {

    private final ReportingService service;

    ReportingController(ReportingService service) {
        this.service = service;
    }

    // ------------------------------------------------------------------ chef secteur

    @GetMapping("/reporting/secteur/collaborateurs")
    List<Collaborator> collaborateurs(Authentication auth) {
        return service.collaborateursDuSecteur(Acteur.depuis(auth));
    }

    @GetMapping("/reporting/secteur/synchronisation")
    List<DeviceSync> synchronisation(Authentication auth) {
        return service.synchronisationDuSecteur(Acteur.depuis(auth));
    }

    @GetMapping("/reporting/secteur/activites")
    List<ReportActivity> activites(@RequestParam(required = false) String du, @RequestParam(required = false) String au, Authentication auth) {
        return service.activitesDuSecteur(Acteur.depuis(auth), du, au);
    }

    @GetMapping("/reporting/secteur/synthese")
    Synthesis syntheseSecteur(@RequestParam(required = false) String du, @RequestParam(required = false) String au, Authentication auth) {
        return service.syntheseDuSecteur(Acteur.depuis(auth), du, au);
    }

    // ------------------------------------------------------------------ chef de département

    @GetMapping("/reporting/departement/secteurs")
    List<ReportSector> secteurs(@RequestParam(required = false) Integer annee, Authentication auth) {
        return service.secteursDuDepartement(Acteur.depuis(auth), annee);
    }

    @GetMapping("/reporting/departement/synthese")
    Synthesis syntheseDepartement(@RequestParam(required = false) String du, @RequestParam(required = false) String au, Authentication auth) {
        return service.syntheseDuDepartement(Acteur.depuis(auth), du, au);
    }

    // ------------------------------------------------------------------ administrateur et superviseur

    @GetMapping("/reporting/national/operateurs")
    List<ReportOperator> operateurs(@RequestParam(required = false) Integer annee, Authentication auth) {
        return service.operateurs(Acteur.depuis(auth), annee);
    }

    @GetMapping("/reporting/national/synthese")
    Synthesis syntheseNationale(@RequestParam(required = false) String du, @RequestParam(required = false) String au, Authentication auth) {
        return service.syntheseNationale(Acteur.depuis(auth), du, au);
    }
}
