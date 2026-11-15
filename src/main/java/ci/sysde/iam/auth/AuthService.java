package ci.sysde.iam.auth;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import ci.sysde.common.BusinessException;
import ci.sysde.iam.auth.AuthDtos.LoginResponse;
import ci.sysde.iam.auth.AuthDtos.SessionUser;
import ci.sysde.iam.compte.Compte;
import ci.sysde.iam.compte.CompteRepository;
import ci.sysde.security.ConnexionProperties;
import ci.sysde.security.JwtService;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    static final String MESSAGE_IDENTIFIANTS = "Matricule ou mot de passe incorrect.";

    private final CompteRepository comptes;
    private final ComptesQuery requetes;
    private final JournalConnexion journal;
    private final PasswordEncoder encodeur;
    private final JwtService jwt;
    private final ConnexionProperties regles;
    private final Clock horloge;

    public AuthService(CompteRepository comptes, ComptesQuery requetes, JournalConnexion journal,
                       PasswordEncoder encodeur, JwtService jwt, ConnexionProperties regles, Clock horloge) {
        this.comptes = comptes;
        this.requetes = requetes;
        this.journal = journal;
        this.encodeur = encodeur;
        this.jwt = jwt;
        this.regles = regles;
        this.horloge = horloge;
    }

    /**
     * Connexion par matricule. Le compteur d'échecs et la trace d'audit doivent survivre au refus :
     * l'exception métier ne défait donc pas la transaction.
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public LoginResponse connecter(String matriculeSaisi, String motDePasse, String adresseIp) {
        String matricule = matriculeSaisi.trim().toUpperCase(Locale.ROOT);
        Instant maintenant = horloge.instant();

        Compte compte = comptes.findByMatricule(matricule).orElse(null);
        if (compte == null) {
            journal.tracer(null, matricule, "ECHEC", adresseIp);
            throw identifiantsInvalides();
        }
        if (compte.estVerrouille(maintenant)) {
            journal.tracer(compte.id(), matricule, "VERROUILLE", adresseIp);
            throw new BusinessException(HttpStatus.LOCKED, "COMPTE_VERROUILLE",
                    "Compte verrouillé temporairement après plusieurs échecs. Réessayez plus tard.");
        }
        if (!compte.aUnMotDePasse() || !encodeur.matches(motDePasse, compte.motDePasseHash())) {
            boolean verrouille = compte.enregistrerEchec(regles.echecsMax(), regles.verrouillage(), maintenant);
            journal.tracer(compte.id(), matricule, verrouille ? "VERROUILLE" : "ECHEC", adresseIp);
            throw identifiantsInvalides();
        }
        // Mot de passe juste : le compte est-il utilisable (actif, utilisateur et organisation actifs) ?
        if (!requetes.peutSeConnecter(compte.id())) {
            journal.tracer(compte.id(), matricule, "ECHEC", adresseIp);
            throw new BusinessException(HttpStatus.FORBIDDEN, "COMPTE_INACTIF",
                    "Ce compte n'est pas actif. Contactez votre responsable.");
        }

        compte.enregistrerSucces(maintenant);
        journal.tracer(compte.id(), matricule, "SUCCES", adresseIp);

        List<String> permissions = requetes.permissionsDuProfil(compte.profil());
        JwtService.Jeton jeton = jwt.emettre(compte.id(), compte.matricule(), compte.profil(),
                compte.operateurId(), permissions);
        SessionUser session = requetes.lireSession(compte, permissions);
        return new LoginResponse(jeton.valeur(), jeton.expireLe(), compte.doitChangerMotDePasse(), session);
    }

    @Transactional(readOnly = true)
    public SessionUser moi(UUID compteId) {
        Compte compte = comptes.findById(compteId).orElseThrow(() ->
                new BusinessException(HttpStatus.UNAUTHORIZED, "SESSION_EXPIREE", "Votre session a expiré : reconnectez-vous."));
        return requetes.lireSession(compte, requetes.permissionsDuProfil(compte.profil()));
    }

    @Transactional(noRollbackFor = BusinessException.class)
    public void changerMotDePasse(UUID compteId, String actuel, String nouveau, String adresseIp) {
        Compte compte = comptes.findById(compteId).orElseThrow(() ->
                new BusinessException(HttpStatus.UNAUTHORIZED, "SESSION_EXPIREE", "Votre session a expiré : reconnectez-vous."));
        if (!compte.aUnMotDePasse() || !encodeur.matches(actuel, compte.motDePasseHash())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MOT_DE_PASSE_ACTUEL_INCORRECT",
                    "Le mot de passe actuel est incorrect.");
        }
        List<String> manques = PasswordPolicy.violations(nouveau);
        if (!manques.isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MOT_DE_PASSE_FAIBLE",
                    "Le nouveau mot de passe doit contenir : " + String.join(", ", manques) + ".");
        }
        if (encodeur.matches(nouveau, compte.motDePasseHash())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MOT_DE_PASSE_IDENTIQUE",
                    "Le nouveau mot de passe doit être différent de l'ancien.");
        }
        compte.definirMotDePasse(encodeur.encode(nouveau));
        journal.tracer(compte.id(), compte.matricule(), "MOT_DE_PASSE_CHANGE", adresseIp);
    }

    private static BusinessException identifiantsInvalides() {
        return new BusinessException(HttpStatus.UNAUTHORIZED, "IDENTIFIANTS_INVALIDES", MESSAGE_IDENTIFIANTS);
    }
}
