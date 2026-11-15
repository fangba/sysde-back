package ci.sysde.common;

import java.util.Locale;
import java.util.Map;

/**
 * Construit une clause {@code ORDER BY} sûre à partir de {@code ?sort=champ,asc}. Seuls les champs
 * déclarés dans la liste blanche sont acceptés : le texte du client n'entre jamais dans le SQL.
 */
public final class TriSql {

    private TriSql() {
    }

    /**
     * @param sort      valeur reçue (ex. {@code nom,desc}), peut être vide
     * @param autorises champ exposé par l'API → expression SQL (ex. {@code "nom" -> "o.denomination"})
     * @param defaut    clause complète utilisée si {@code sort} est vide (ex. {@code "o.denomination asc"})
     * @return une clause du type {@code "o.denomination desc, id asc"}, toujours terminée par un départage stable
     * @throws BusinessException 400 si le champ n'est pas autorisé
     */
    public static String clause(String sort, Map<String, String> autorises, String defaut, String departage) {
        if (sort == null || sort.isBlank()) {
            return defaut + ", " + departage;
        }
        String[] morceaux = sort.split(",", 2);
        String champ = morceaux[0].trim();
        String expression = autorises.get(champ);
        if (expression == null) {
            throw new BusinessException(org.springframework.http.HttpStatus.BAD_REQUEST, "TRI_INVALIDE",
                    "Tri impossible sur « " + champ + " ». Champs possibles : " + String.join(", ", autorises.keySet()) + ".");
        }
        String sens = morceaux.length > 1 ? morceaux[1].trim().toLowerCase(Locale.ROOT) : "asc";
        if (!sens.equals("asc") && !sens.equals("desc")) {
            throw new BusinessException(org.springframework.http.HttpStatus.BAD_REQUEST, "TRI_INVALIDE",
                    "Le sens du tri doit être asc ou desc.");
        }
        return expression + " " + sens + ", " + departage;
    }
}
