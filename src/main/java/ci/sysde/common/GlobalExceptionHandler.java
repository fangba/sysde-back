package ci.sysde.common;

import java.net.URI;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Un seul format d'erreur pour toute l'API : RFC 9457 ({@code application/problem+json}).
 *
 * <p>Le corps garde les champs historiques lus par les clients, à plat : {@code code} (stable),
 * {@code message} (affichable, en français), {@code details} (erreurs par champ) et {@code horodatage},
 * en plus des champs standard {@code type}, {@code title}, {@code status}, {@code detail}.
 * Les erreurs de Spring MVC (405, 415, paramètre manquant, ressource absente…) passent par le même chemin.
 */
@RestControllerAdvice
class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** SQLSTATE levés par les triggers de la base ({@code RAISE EXCEPTION ... USING ERRCODE}). */
    private static final String CHECK_VIOLATION = "23514";
    private static final String UNIQUE_VIOLATION = "23505";
    private static final String RESTRICT_VIOLATION = "23001";
    private static final String INSUFFICIENT_PRIVILEGE = "42501";
    private static final String NO_DATA_FOUND = "P0002";
    private static final String EXCLUSION_VIOLATION = "23P01";

    // ------------------------------------------------------------------ erreurs métier et sécurité

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<Object> metier(BusinessException e) {
        return reponse(e.status(), e.code(), e.getMessage(), List.of());
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<Object> refuse(AccessDeniedException e) {
        return reponse(HttpStatus.FORBIDDEN, "ACCES_REFUSE", "Vous n'avez pas le droit d'effectuer cette action.", List.of());
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<Object> parametresInvalides(ConstraintViolationException e) {
        List<ApiError.Detail> details = e.getConstraintViolations().stream()
                .map(v -> new ApiError.Detail(dernierSegment(v.getPropertyPath().toString()), v.getMessage()))
                .toList();
        return reponse(HttpStatus.BAD_REQUEST, "REQUETE_INVALIDE", "La requête contient des valeurs invalides.", details);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<Object> conflitDeVersion(OptimisticLockingFailureException e) {
        return reponse(HttpStatus.CONFLICT, "CONFLIT_DE_VERSION",
                "Les données ont été modifiées entre-temps : rechargez et réessayez.", List.of());
    }

    // ------------------------------------------------------------------ règles portées par la base

    /**
     * Les triggers de la base lèvent des messages déjà métier et en français (avec un SQLSTATE dédié) :
     * ils sont rendus tels quels. Une contrainte « technique » (clé étrangère, valeur unique) garde un
     * message générique : le texte de PostgreSQL ne doit jamais atteindre l'utilisateur.
     */
    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<Object> base(DataAccessException e) {
        SQLException sql = causeSql(e);
        String etat = sql == null ? null : sql.getSQLState();
        String message = sql == null ? null : sql.getMessage();
        boolean regleMetier = message != null && !ressembleAUneContrainte(message);

        if (INSUFFICIENT_PRIVILEGE.equals(etat) && regleMetier) {
            return reponse(HttpStatus.FORBIDDEN, "ACCES_REFUSE", premiereLigne(message), List.of());
        }
        if (NO_DATA_FOUND.equals(etat) && regleMetier) {
            return reponse(HttpStatus.NOT_FOUND, "INTROUVABLE", premiereLigne(message), List.of());
        }
        if (CHECK_VIOLATION.equals(etat) && regleMetier) {
            return reponse(HttpStatus.UNPROCESSABLE_ENTITY, "REGLE_METIER", premiereLigne(message), List.of());
        }
        if ((UNIQUE_VIOLATION.equals(etat) || RESTRICT_VIOLATION.equals(etat)) && regleMetier) {
            return reponse(HttpStatus.CONFLICT, "CONFLIT_DONNEES", premiereLigne(message), List.of());
        }
        if (EXCLUSION_VIOLATION.equals(etat)) {
            return reponse(HttpStatus.CONFLICT, "CONFLIT_DONNEES", messageExclusion(message), List.of());
        }
        if (etat != null && etat.startsWith("23")) {
            log.warn("Violation d'intégrité ({}) : {}", etat, message);
            String texte = UNIQUE_VIOLATION.equals(etat)
                    ? "Cette valeur existe déjà."
                    : "L'opération viole une règle de gestion.";
            return reponse(HttpStatus.CONFLICT, "CONFLIT_DONNEES", texte, List.of());
        }
        log.error("Erreur d'accès aux données", e);
        return reponse(HttpStatus.INTERNAL_SERVER_ERROR, "ERREUR_INTERNE", "Une erreur interne est survenue.", List.of());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> inattendue(Exception e) {
        log.error("Erreur inattendue", e);
        return reponse(HttpStatus.INTERNAL_SERVER_ERROR, "ERREUR_INTERNE", "Une erreur interne est survenue.", List.of());
    }

    // ------------------------------------------------------------------ erreurs de Spring MVC

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException e, HttpHeaders headers,
                                                                  HttpStatusCode status, WebRequest request) {
        List<ApiError.Detail> details = e.getBindingResult().getFieldErrors().stream()
                .map(f -> new ApiError.Detail(f.getField(), f.getDefaultMessage()))
                .toList();
        return reponse(HttpStatus.BAD_REQUEST, "REQUETE_INVALIDE", "La requête contient des valeurs invalides.", details);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException e, HttpHeaders headers,
                                                                  HttpStatusCode status, WebRequest request) {
        return reponse(HttpStatus.BAD_REQUEST, "REQUETE_ILLISIBLE", "Le corps de la requête est illisible.", List.of());
    }

    /** Point de passage de toutes les autres erreurs MVC : on y ajoute {@code code} et {@code message}. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception e, Object body, HttpHeaders headers,
                                                             HttpStatusCode status, WebRequest request) {
        ResponseEntity<Object> standard = super.handleExceptionInternal(e, body, headers, status, request);
        if (standard != null && standard.getBody() instanceof ProblemDetail probleme) {
            HttpStatus http = HttpStatus.resolve(status.value());
            enrichir(probleme, codePourStatut(http), messagePourStatut(http, probleme), List.of());
        }
        return standard;
    }

    // ------------------------------------------------------------------ construction

    private static ResponseEntity<Object> reponse(HttpStatus statut, String code, String message, List<ApiError.Detail> details) {
        ProblemDetail probleme = ProblemDetail.forStatusAndDetail(statut, message);
        probleme.setTitle(statut.getReasonPhrase());
        enrichir(probleme, code, message, details);
        return ResponseEntity.status(statut)
                .header(HttpHeaders.CONTENT_TYPE, "application/problem+json")
                .body(probleme);
    }

    private static void enrichir(ProblemDetail probleme, String code, String message, List<ApiError.Detail> details) {
        probleme.setType(URI.create("about:blank"));
        probleme.setProperty("code", code);
        probleme.setProperty("message", message);
        probleme.setProperty("horodatage", Instant.now().toString());
        if (!details.isEmpty()) {
            probleme.setProperty("details", details);
        }
    }

    private static String codePourStatut(HttpStatus statut) {
        if (statut == null) {
            return "ERREUR";
        }
        return switch (statut) {
            case NOT_FOUND -> "INTROUVABLE";
            case METHOD_NOT_ALLOWED -> "METHODE_NON_AUTORISEE";
            case UNSUPPORTED_MEDIA_TYPE -> "FORMAT_NON_SUPPORTE";
            case NOT_ACCEPTABLE -> "FORMAT_NON_ACCEPTABLE";
            case BAD_REQUEST -> "REQUETE_INVALIDE";
            case PAYLOAD_TOO_LARGE -> "FICHIER_TROP_VOLUMINEUX";
            case SERVICE_UNAVAILABLE -> "SERVICE_INDISPONIBLE";
            default -> statut.is5xxServerError() ? "ERREUR_INTERNE" : "ERREUR";
        };
    }

    private static String messagePourStatut(HttpStatus statut, ProblemDetail probleme) {
        if (statut == null) {
            return "Une erreur est survenue.";
        }
        return switch (statut) {
            case NOT_FOUND -> "Ressource introuvable.";
            case METHOD_NOT_ALLOWED -> "Cette méthode n'est pas autorisée sur cette ressource.";
            case UNSUPPORTED_MEDIA_TYPE -> "Le format du contenu envoyé n'est pas pris en charge.";
            case NOT_ACCEPTABLE -> "Le format de réponse demandé n'est pas disponible.";
            case BAD_REQUEST -> "La requête est invalide.";
            case PAYLOAD_TOO_LARGE -> "Le fichier envoyé est trop volumineux.";
            default -> statut.is5xxServerError() ? "Une erreur interne est survenue." : "Une erreur est survenue.";
        };
    }

    // ------------------------------------------------------------------ utilitaires

    private static SQLException causeSql(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql) {
                return sql;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return null;
    }

    /** Contraintes d'exclusion (chevauchements) connues : message métier ; sinon message générique. */
    static String messageExclusion(String message) {
        if (message != null && message.contains("ex_contrat_chevauchement")) {
            return "Les dates de ce contrat chevauchent un autre contrat du même opérateur.";
        }
        if (message != null && message.contains("ex_lot_exclusif")) {
            return "Un de ces lots est déjà attribué à un opérateur sur cette période.";
        }
        return "Cette opération crée un chevauchement avec une donnée existante.";
    }

    /** Un message de contrainte PostgreSQL (« violates … », « duplicate key … ») est technique, pas métier. */
    static boolean ressembleAUneContrainte(String message) {
        String m = message.toLowerCase(Locale.ROOT);
        return m.contains("violates") || m.contains("duplicate key") || m.contains("violation");
    }

    /** PostgreSQL ajoute « Où : fonction PL/pgSQL… » après le message : on ne garde que la première ligne. */
    static String premiereLigne(String message) {
        int fin = message.indexOf('\n');
        String ligne = fin < 0 ? message : message.substring(0, fin);
        return ligne.replaceFirst("^(ERROR|ERREUR)\\s*:\\s*", "").trim();
    }

    private static String dernierSegment(String chemin) {
        int point = chemin.lastIndexOf('.');
        return point < 0 ? chemin : chemin.substring(point + 1);
    }
}
