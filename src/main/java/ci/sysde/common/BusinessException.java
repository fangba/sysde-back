package ci.sysde.common;

import org.springframework.http.HttpStatus;

/**
 * Erreur métier : un code stable (lu par les clients) et un message en français.
 * Le code HTTP porte la nature de l'erreur (400, 401, 403, 404, 409, 423...).
 */
public class BusinessException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public BusinessException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
