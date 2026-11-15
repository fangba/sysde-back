package ci.sysde.plantation;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import ci.sysde.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;

/** Les plantations d'une visite doivent exister dans la base externe et être dans le lot de la ligne (option activée). */
@TestPropertySource(properties = "sysde.plantations.verification-planning=true")
class VerificationPlantationsPlanningTest extends AbstractIntegrationTest {

    private PlantationExterne ext(String ref, int lot) {
        return new PlantationExterne(ref, "C-" + ref, "Planteur", null, null, "X", "01", lot, "", new BigDecimal("10"), 2015, null,
                true, null, null, List.of());
    }

    @Test
    void uneVisiteExigeUnePlantationConnueDansLeLotDeLaLigne() {
        Secteur s = secteur("VP", "01");
        int lot0 = jdbc.sql("select numero from referentiel.lot where id = :id").param("id", s.equipe().lots().get(0)).query(Integer.class).single();
        int lot1 = jdbc.sql("select numero from referentiel.lot where id = :id").param("id", s.equipe().lots().get(1)).query(Integer.class).single();
        String ici = "V-" + UUID.randomUUID().toString().substring(0, 6);
        String ailleurs = "W-" + UUID.randomUUID().toString().substring(0, 6);
        SourcePlantationsDeTest.DONNEES.add(ext(ici, lot0));
        SourcePlantationsDeTest.DONNEES.add(ext(ailleurs, lot1));

        // connue et dans le lot de la ligne : acceptée
        assertThat(statut(put(s.moC(), "/plannings/mon-planning/lignes", List.of(ligne("VISITE_PLANTATION", 4, 8, s, true, ici)))))
                .isEqualTo(HttpStatus.OK);
        // inconnue : 400
        var inconnue = put(s.moC(), "/plannings/mon-planning/lignes", List.of(ligne("VISITE_PLANTATION", 4, 8, s, true, "INCONNUE-" + ici)));
        assertThat(statut(inconnue)).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat((String) inconnue.getBody().get("message")).contains("n'existe pas");
        // connue mais dans un autre lot : 400
        var autreLot = put(s.moC(), "/plannings/mon-planning/lignes", List.of(ligne("VISITE_PLANTATION", 4, 8, s, true, ailleurs)));
        assertThat(statut(autreLot)).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat((String) autreLot.getBody().get("message")).contains("n'est pas dans le lot");
    }
}
