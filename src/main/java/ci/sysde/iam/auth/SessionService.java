package ci.sysde.iam.auth;

import java.util.List;
import java.util.UUID;

import ci.sysde.common.BusinessException;
import ci.sysde.iam.auth.AuthDtos.LoginResponse;
import ci.sysde.iam.auth.AuthDtos.SessionUser;
import ci.sysde.iam.compte.Compte;
import ci.sysde.iam.compte.CompteRepository;
import ci.sysde.security.JwtService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cycle de vie de la session après la connexion : jeton de rafraîchissement remis à la connexion,
 * rotation à chaque rafraîchissement, révocation à la déconnexion.
 */
@Service
public class SessionService {

    private final RefreshTokenService jetons;
    private final CompteRepository comptes;
    private final ComptesQuery requetes;
    private final JournalConnexion journal;
    private final JwtService jwt;

    public SessionService(RefreshTokenService jetons, CompteRepository comptes, ComptesQuery requetes,
                          JournalConnexion journal, JwtService jwt) {
        this.jetons = jetons;
        this.comptes = comptes;
        this.requetes = requetes;
        this.journal = journal;
        this.jwt = jwt;
    }

    /** Ajoute le jeton de rafraîchissement à une réponse de connexion réussie. */
    public LoginResponse ouvrir(LoginResponse connexion, String appareil, String adresseIp) {
        RefreshTokenService.Emis suite = jetons.emettre(connexion.user().accountId(), appareil, adresseIp);
        return new LoginResponse(connexion.token(), connexion.expiresAt(), connexion.mustChangePassword(),
                connexion.user(), suite.valeur(), suite.expireLe());
    }

    /**
     * Échange un jeton de rafraîchissement contre un nouveau jeton d'accès et un nouveau jeton de
     * rafraîchissement. Le profil et les permissions sont relus en base : un changement de droits prend effet
     * ici. Un compte désactivé depuis peu garde son rafraîchissement (envoi seul, limité par le délai de la base) ; un compte devenu inactif au-delà est refusé et sa famille de jetons coupée.
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public LoginResponse rafraichir(String jetonPresente, String adresseIp, String appareil) {
        RefreshTokenService.Rotation rotation = jetons.tourner(jetonPresente, appareil, adresseIp);
        Compte compte = comptes.findById(rotation.compteId()).orElse(null);
        if (compte == null || !requetes.peutSynchroniser(compte.id())) {
            jetons.revoquerFamille(rotation.familleId());
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "SESSION_EXPIREE",
                    "Votre session a expiré : reconnectez-vous.");
        }
        List<String> permissions = requetes.permissionsDuProfil(compte.profil());
        JwtService.Jeton jeton = jwt.emettre(compte.id(), compte.matricule(), compte.profil(),
                compte.operateurId(), permissions);
        SessionUser session = requetes.lireSession(compte, permissions);
        return new LoginResponse(jeton.valeur(), jeton.expireLe(), compte.doitChangerMotDePasse(), session,
                rotation.nouveau().valeur(), rotation.nouveau().expireLe());
    }

    /** Mot de passe changé : les autres sessions du compte sont coupées (celle du jeton présenté est conservée). */
    @Transactional
    public void couperAutresSessions(UUID compteId, String jetonConserve) {
        jetons.revoquerAutres(compteId, jetonConserve);
    }

    /** Déconnexion : le jeton présenté et toute sa famille ne serviront plus. */
    @Transactional
    public void deconnecter(String jetonPresente, UUID compteId, String adresseIp) {
        jetons.revoquer(jetonPresente);
        comptes.findById(compteId).ifPresent(c -> journal.tracer(c.id(), c.matricule(), "DECONNEXION", adresseIp));
    }
}
