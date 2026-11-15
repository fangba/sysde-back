package ci.sysde.audit;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

import ci.sysde.audit.TracabiliteService.Evenement;
import ci.sysde.audit.TracabiliteService.Filtre;
import ci.sysde.common.BusinessException;
import ci.sysde.common.Page;
import ci.sysde.common.PageRequete;
import ci.sysde.security.Acteur;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Piste d'audit (endpoints-back.md §2bis). Les dates sont des jours (aaaa-mm-jj) ou des instants ISO 8601 ;
 * sans {@code from}, les 30 derniers jours. {@code to} est exclu.
 */
@RestController
class TracabiliteController {

    private static final MediaType CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);

    private final TracabiliteService service;

    TracabiliteController(TracabiliteService service) {
        this.service = service;
    }

    @GetMapping("/tracabilite")
    @PreAuthorize("hasAuthority('PERM_CONSULTER_TRACABILITE')")
    Page<Evenement> lister(@RequestParam(required = false) UUID accountId, @RequestParam(required = false) String table,
                           @RequestParam(required = false) String objectId, @RequestParam(required = false) String from,
                           @RequestParam(required = false) String to, @RequestParam(required = false) Integer page,
                           @RequestParam(required = false) Integer size, Authentication auth) {
        return service.lister(Acteur.depuis(auth), filtre(accountId, table, objectId, from, to), PageRequete.de(page, size));
    }

    @GetMapping("/tracabilite/export")
    @PreAuthorize("hasAuthority('PERM_EXPORTER_TRACABILITE')")
    ResponseEntity<String> exporter(@RequestParam(required = false) UUID accountId, @RequestParam(required = false) String table,
                                    @RequestParam(required = false) String objectId, @RequestParam(required = false) String from,
                                    @RequestParam(required = false) String to, Authentication auth) {
        String contenu = service.exporter(Acteur.depuis(auth), filtre(accountId, table, objectId, from, to));
        return ResponseEntity.ok().contentType(CSV)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"tracabilite.csv\"").body(contenu);
    }

    private static Filtre filtre(UUID accountId, String table, String objectId, String from, String to) {
        return new Filtre(accountId, table, objectId, instant(from, "from"), instant(to, "to"));
    }

    /** Un jour (aaaa-mm-jj, minuit UTC) ou un instant ISO 8601. */
    private static Instant instant(String texte, String nom) {
        if (texte == null || texte.isBlank()) {
            return null;
        }
        try {
            return texte.length() <= 10 ? TracabiliteService.debut(LocalDate.parse(texte)) : OffsetDateTime.parse(texte).toInstant();
        } catch (java.time.format.DateTimeParseException e) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "REQUETE_INVALIDE",
                    "Date « " + nom + " » invalide : attendu aaaa-mm-jj ou date-heure ISO 8601.");
        }
    }
}
