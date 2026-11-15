package ci.sysde.iam.permission;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import ci.sysde.common.BusinessException;
import ci.sysde.security.EtatCompteService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lecture et ajustement des permissions de chaque profil. Les garde-fous sont dans la base
 * ({@code iam.garder_permissions}) : seul un compte qui détient {@code GERER_PERMISSIONS} peut ajuster, et
 * l'administrateur ne peut pas perdre {@code GERER_PERMISSIONS} ni {@code GERER_REFERENTIEL}.
 *
 * <p>PRO-17 : les permissions sont relues côté serveur à chaque requête (cache de moins d'une minute, vidé ici).
 */
@Service
class PermissionService {

    record Profil(String code, String label, int rank, int order) {
    }

    record Permission(String code, String domain, String label) {
    }

    record Catalogue(List<Profil> profiles, List<Permission> permissions) {
    }

    private final JdbcClient jdbc;
    private final EtatCompteService etats;

    PermissionService(JdbcClient jdbc, EtatCompteService etats) {
        this.jdbc = jdbc;
        this.etats = etats;
    }

    @Transactional(readOnly = true)
    Catalogue catalogue() {
        List<Profil> profils = jdbc.sql("select code, libelle, rang, ordre from iam.profil order by ordre")
                .query((rs, n) -> new Profil(rs.getString("code"), rs.getString("libelle"), rs.getInt("rang"), rs.getInt("ordre")))
                .list();
        List<Permission> permissions = jdbc.sql("select code, domaine, libelle from iam.permission order by domaine, code")
                .query((rs, n) -> new Permission(rs.getString("code"), rs.getString("domaine"), rs.getString("libelle")))
                .list();
        return new Catalogue(profils, permissions);
    }

    @Transactional(readOnly = true)
    Map<String, List<String>> lire() {
        Map<String, List<String>> droits = new LinkedHashMap<>();
        jdbc.sql("select code from iam.profil order by ordre").query(String.class).list()
                .forEach(profil -> droits.put(profil, new ArrayList<>()));
        jdbc.sql("select profil, permission from iam.profil_permission order by permission")
                .query((rs, n) -> {
                    droits.get(rs.getString("profil")).add(rs.getString("permission"));
                    return null;
                })
                .list();
        return droits;
    }

    @Transactional
    Map<String, List<String>> enregistrer(Map<String, List<String>> demande) {
        Map<String, List<String>> actuel = lire();
        Set<String> permissionsConnues = new TreeSet<>(jdbc.sql("select code from iam.permission").query(String.class).list());

        // Tout est contrôlé avant la première écriture : un bloc invalide ne change rien.
        for (var entree : demande.entrySet()) {
            if (!actuel.containsKey(entree.getKey())) {
                throw invalide("Profil inconnu : " + entree.getKey() + ".");
            }
            if (entree.getValue() == null) {
                throw invalide("Liste de permissions absente pour le profil " + entree.getKey() + ".");
            }
            for (String code : entree.getValue()) {
                if (!permissionsConnues.contains(code)) {
                    throw invalide("Permission inconnue : " + code + ".");
                }
            }
        }
        for (var entree : demande.entrySet()) {
            String profil = entree.getKey();
            Set<String> voulues = new TreeSet<>(entree.getValue());
            Set<String> existantes = new TreeSet<>(actuel.get(profil));
            for (String code : existantes) {
                if (!voulues.contains(code)) {
                    jdbc.sql("delete from iam.profil_permission where profil = :p and permission = :c")
                            .param("p", profil).param("c", code).update();
                }
            }
            for (String code : voulues) {
                if (!existantes.contains(code)) {
                    jdbc.sql("insert into iam.profil_permission (profil, permission) values (:p, :c)")
                            .param("p", profil).param("c", code).update();
                }
            }
        }
        // PRO-17 : le changement s'applique dès la requête suivante.
        etats.invalider();
        return lire();
    }

    private static BusinessException invalide(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, "REQUETE_INVALIDE", message);
    }
}
