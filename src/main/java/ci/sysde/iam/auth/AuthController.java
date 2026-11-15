package ci.sysde.iam.auth;

import java.util.UUID;

import ci.sysde.iam.auth.AuthDtos.ChangePasswordRequest;
import ci.sysde.iam.auth.AuthDtos.LoginRequest;
import ci.sysde.iam.auth.AuthDtos.LoginResponse;
import ci.sysde.iam.auth.AuthDtos.RefreshRequest;
import ci.sysde.iam.auth.AuthDtos.SessionUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
class AuthController {

    private final AuthService service;
    private final SessionService sessions;

    AuthController(AuthService service, SessionService sessions) {
        this.service = service;
        this.sessions = sessions;
    }

    @PostMapping("/login")
    LoginResponse login(@Valid @RequestBody LoginRequest requete, HttpServletRequest http) {
        LoginResponse connexion = service.connecter(requete.matricule(), requete.password(), http.getRemoteAddr());
        return sessions.ouvrir(connexion, appareil(http), http.getRemoteAddr());
    }

    /** Échange le jeton de rafraîchissement contre de nouveaux jetons (rotation). Public : le jeton d'accès a pu expirer. */
    @PostMapping("/refresh")
    LoginResponse rafraichir(@Valid @RequestBody RefreshRequest requete, HttpServletRequest http) {
        return sessions.rafraichir(requete.refreshToken(), http.getRemoteAddr(), appareil(http));
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deconnecter(@Valid @RequestBody RefreshRequest requete, Authentication auth, HttpServletRequest http) {
        sessions.deconnecter(requete.refreshToken(), UUID.fromString(auth.getName()), http.getRemoteAddr());
    }

    /** Compte connecté (identité affichée, permissions). */
    @GetMapping("/moi")
    SessionUser moi(Authentication auth) {
        return service.moi(UUID.fromString(auth.getName()));
    }

    @PostMapping("/mot-de-passe")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void changerMotDePasse(@Valid @RequestBody ChangePasswordRequest requete, Authentication auth, HttpServletRequest http) {
        service.changerMotDePasse(UUID.fromString(auth.getName()), requete.currentPassword(),
                requete.newPassword(), http.getRemoteAddr());
        sessions.couperAutresSessions(UUID.fromString(auth.getName()), requete.refreshToken());
    }

    private static String appareil(HttpServletRequest http) {
        return http.getHeader("User-Agent");
    }
}
