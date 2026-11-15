package ci.sysde.iam.auth;

import java.util.ArrayList;
import java.util.List;

/** Règle de robustesse d'un mot de passe : 10 caractères, une majuscule, une minuscule, un chiffre. */
public final class PasswordPolicy {

    public static final int LONGUEUR_MIN = 10;

    private PasswordPolicy() {
    }

    /** Liste des règles non respectées (vide si le mot de passe est valide). */
    public static List<String> violations(String motDePasse) {
        List<String> v = new ArrayList<>();
        if (motDePasse == null || motDePasse.length() < LONGUEUR_MIN) {
            v.add("au moins " + LONGUEUR_MIN + " caractères");
        }
        String m = motDePasse == null ? "" : motDePasse;
        if (m.chars().noneMatch(Character::isUpperCase)) {
            v.add("une majuscule");
        }
        if (m.chars().noneMatch(Character::isLowerCase)) {
            v.add("une minuscule");
        }
        if (m.chars().noneMatch(Character::isDigit)) {
            v.add("un chiffre");
        }
        return v;
    }
}
