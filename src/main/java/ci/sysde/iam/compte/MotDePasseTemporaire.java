package ci.sysde.iam.compte;

import java.security.SecureRandom;

import ci.sysde.iam.auth.PasswordPolicy;

/** Mot de passe temporaire remis par le responsable (COM-04) : respecte la règle de robustesse, sans caractères ambigus. */
final class MotDePasseTemporaire {

    private static final String MAJ = "ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final String MIN = "abcdefghijkmnpqrstuvwxyz";
    private static final String CHIFFRES = "23456789";
    private static final String TOUS = MAJ + MIN + CHIFFRES;
    private static final int LONGUEUR = 12;
    private static final SecureRandom ALEA = new SecureRandom();

    private MotDePasseTemporaire() {
    }

    static String generer() {
        char[] c = new char[LONGUEUR];
        c[0] = tirer(MAJ);
        c[1] = tirer(MIN);
        c[2] = tirer(CHIFFRES);
        for (int i = 3; i < LONGUEUR; i++) {
            c[i] = tirer(TOUS);
        }
        for (int i = LONGUEUR - 1; i > 0; i--) {          // mélange de Fisher-Yates : les trois premiers ne sont pas en tête
            int j = ALEA.nextInt(i + 1);
            char t = c[i];
            c[i] = c[j];
            c[j] = t;
        }
        String mdp = new String(c);
        if (!PasswordPolicy.violations(mdp).isEmpty()) {
            throw new IllegalStateException("Mot de passe temporaire non conforme");
        }
        return mdp;
    }

    private static char tirer(String alphabet) {
        return alphabet.charAt(ALEA.nextInt(alphabet.length()));
    }
}
