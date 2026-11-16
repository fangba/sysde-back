package ci.sysde.iam.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import ci.sysde.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** Le secteur de rattachement figure dans l'utilisateur de session (le front en a besoin pour son étendue). */
class SessionSecteurIntegrationTest extends AbstractIntegrationTest {

    @Test
    @SuppressWarnings("unchecked")
    void leSecteurDeLAffectationEnCoursEstDansLUtilisateurDeSession() {
        Secteur s = secteur("SS", "01");
        var moi = s.cs().get().uri("/auth/moi").retrieve().toEntity(OBJET);
        assertThat(statut(moi)).isEqualTo(HttpStatus.OK);
        var secteur = (Map<String, Object>) moi.getBody().get("sector");
        assertThat(secteur).containsEntry("code", "01").containsKey("name");
        // l'administrateur n'a pas d'affectation : pas de secteur
        assertThat(admin().get().uri("/auth/moi").retrieve().toEntity(OBJET).getBody()).doesNotContainKey("sector");
    }
}
