package ci.sysde.iam.auth;

import java.util.ArrayList;
import java.util.List;

/**
 * Règle d'un mot de passe choisi par l'utilisateur : <b>8 caractères au moins</b>, quels qu'ils soient (majuscules,
 * minuscules, chiffres et caractères spéciaux ne sont pas exigés). Même règle que le web (rules/mot-de-passe.md).
 */
public final class PasswordPolicy {

    public static final int LONGUEUR_MIN = 8;

    private PasswordPolicy() {
    }

    /** Liste des règles non respectées (vide si le mot de passe est valide). */
    public static List<String> violations(String motDePasse) {
        List<String> v = new ArrayList<>();
        if (motDePasse == null || motDePasse.length() < LONGUEUR_MIN) {
            v.add("au moins " + LONGUEUR_MIN + " caractères");
        }
        return v;
    }
}
