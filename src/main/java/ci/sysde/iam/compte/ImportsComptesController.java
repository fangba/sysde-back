package ci.sysde.iam.compte;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import ci.sysde.common.BusinessException;
import ci.sysde.iam.compte.CompteDtos.ImportResult;
import ci.sysde.iam.compte.CompteDtos.ImportSummary;
import ci.sysde.security.Acteur;
import org.springframework.http.HttpHeaders;
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

/** Import CSV de comptes (endpoints-back.md §2bis) : modèle, validation, rapport, application, fichier des rejets. */
@RestController
@PreAuthorize("hasAuthority('PERM_IMPORTER_COMPTES')")
class ImportsComptesController {

    private static final MediaType CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);

    private final ImportsComptesService service;

    ImportsComptesController(ImportsComptesService service) {
        this.service = service;
    }

    @GetMapping("/imports-comptes/modele")
    ResponseEntity<String> modele() {
        return csv("modele-import-comptes.csv", ImportsComptesService.modele());
    }

    /** Contrôle le fichier en entier sans rien créer ; renvoie le rapport (erreurs ligne par ligne). */
    @PostMapping("/imports-comptes")
    @ResponseStatus(HttpStatus.CREATED)
    ImportSummary valider(@RequestParam("file") MultipartFile fichier, Authentication auth) throws IOException {
        if (fichier.isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "REQUETE_INVALIDE", "Le fichier est vide.");
        }
        return service.valider(Acteur.depuis(auth), fichier.getOriginalFilename() == null ? "import.csv" : fichier.getOriginalFilename(), fichier.getBytes());
    }

    @GetMapping("/imports-comptes/{id}")
    ImportSummary lire(@PathVariable UUID id, Authentication auth) {
        return service.lire(Acteur.depuis(auth), id);
    }

    @PostMapping("/imports-comptes/{id}/application")
    ImportResult appliquer(@PathVariable UUID id, Authentication auth) {
        return service.appliquer(Acteur.depuis(auth), id);
    }

    @PostMapping("/imports-comptes/{id}/annulation")
    ImportSummary annuler(@PathVariable UUID id, Authentication auth) {
        return service.annuler(Acteur.depuis(auth), id);
    }

    @GetMapping("/imports-comptes/{id}/rejets")
    ResponseEntity<String> rejets(@PathVariable UUID id, Authentication auth) {
        return csv("rejets-import-comptes.csv", service.rejets(Acteur.depuis(auth), id));
    }

    private static ResponseEntity<String> csv(String nom, String contenu) {
        return ResponseEntity.ok().contentType(CSV)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + nom + "\"").body(contenu);
    }
}
