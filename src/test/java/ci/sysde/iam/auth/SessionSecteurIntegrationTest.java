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

    @Test
    @SuppressWarnings("unchecked")
    void laListeDesComptesPorteLeTerritoireEnCours() {
        Secteur s = secteur("SS", "01");
        var page = admin().get().uri("/comptes?size=100&sort=createdAt,desc").retrieve().toEntity(OBJET).getBody();
        var comptes = (java.util.List<Map<String, Object>>) page.get("content");
        var mo = comptes.stream().filter(c -> s.mo().toString().equals(c.get("id"))).findFirst().orElseThrow();
        int lot = jdbc.sql("select numero from referentiel.lot where id = :id").param("id", s.equipe().lots().get(0)).query(Integer.class).single();
        String section = jdbc.sql("select nom from referentiel.section where id = :id").param("id", s.section()).query(String.class).single();
        assertThat(mo).containsEntry("sectorId", s.equipe().secteur().toString()).containsEntry("lotNumber", lot).containsEntry("sections", section);
        var cs = comptes.stream().filter(c -> s.equipe().cs().toString().equals(c.get("id"))).findFirst().orElseThrow();
        assertThat(cs).containsEntry("sectorId", s.equipe().secteur().toString()).doesNotContainKeys("lotNumber", "sections");
    }
}
