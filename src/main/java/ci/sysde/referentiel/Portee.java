package ci.sysde.referentiel;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import ci.sysde.security.Acteur;
import org.springframework.stereotype.Component;

/**
 * Ce qu'un compte peut lire dans la gestion contractuelle : {@code operateur == null} = tous les opérateurs,
 * {@code secteurs == null} = tous les secteurs. Fragments SQL prêts à concaténer (valeurs toujours en paramètres).
 */
record Portee(UUID operateur, Set<UUID> secteurs) {

    boolean vide() {
        return secteurs != null && secteurs.isEmpty();
    }

    String parOperateur(String colonne) {
        return operateur == null ? "" : " and " + colonne + " = :portee_operateur";
    }

    String parSecteur(String colonne) {
        return secteurs == null ? "" : " and " + colonne + " in (:portee_secteurs)";
    }

    Map<String, Object> parametres() {
        Map<String, Object> m = new HashMap<>();
        if (operateur != null) {
            m.put("portee_operateur", operateur);
        }
        if (secteurs != null && !secteurs.isEmpty()) {
            m.put("portee_secteurs", secteurs);
        }
        return m;
    }

    @Component
    static class Fabrique {

        private final SecteursVisibles visibles;

        Fabrique(SecteursVisibles visibles) {
            this.visibles = visibles;
        }

        Portee de(Acteur acteur) {
            var secteurs = visibles.de(acteur);
            // AD et SU : aucun filtre. Les autres : leur opérateur, et leurs secteurs visibles.
            if (secteurs.isEmpty()) {
                return new Portee(null, null);
            }
            return new Portee(acteur.operateurId(), secteurs.get());
        }
    }
}
