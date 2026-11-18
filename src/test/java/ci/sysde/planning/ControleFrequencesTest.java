package ci.sysde.planning;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import ci.sysde.planning.PlanningDtos.Frequency;
import ci.sysde.planning.PlanningDtos.PlanActivity;
import ci.sysde.planning.PlanningDtos.PlanLine;
import org.junit.jupiter.api.Test;

class ControleFrequencesTest {

    private static final UUID ACTIVITE = UUID.randomUUID();

    private static PlanActivity activite(int jours, Frequency... frequences) {
        return new PlanActivity(ACTIVITE, "TEST", "Activité test", "VISITE", jours, true, null, null, List.of(frequences));
    }

    private static PlanLine ligne(int mois, int jour, int duree, String plantation) {
        LocalDate debut = LocalDate.of(2026, mois, jour);
        return new PlanLine(UUID.randomUUID(), debut, debut.plusDays(duree - 1), ACTIVITE, null, 3, null, null, plantation, null);
    }

    @Test
    void uneActiviteSansSeanceMaisAvecUneFrequenceEstSignalee() {
        var r = ControleFrequences.controler(activite(1, new Frequency(4, "ANNEE", null, null, null, "")), List.of());
        assertThat(r.ok()).isFalse();
        assertThat(r.issues()).containsExactly("Aucune séance n'est programmée.");
        assertThat(r.expected()).isEqualTo(4);
    }

    @Test
    void lesSeancesDeLAnneeDoiventEtreAuNombreDemande() {
        var f = new Frequency(2, "ANNEE", null, null, null, "");
        assertThat(ControleFrequences.controler(activite(1, f), List.of(ligne(3, 1, 1, null), ligne(9, 1, 1, null))).ok()).isTrue();
        var trop = ControleFrequences.controler(activite(1, f), List.of(ligne(3, 1, 1, null)));
        assertThat(trop.ok()).isFalse();
        assertThat(trop.issues().get(0)).isEqualTo("1 séance programmée pour 2 séances attendues.");
    }

    @Test
    void parSemestreChaqueSemestreEstControle() {
        var f = new Frequency(1, "SEMESTRE", null, null, null, "");
        var r = ControleFrequences.controler(activite(1, f), List.of(ligne(2, 1, 1, null), ligne(3, 1, 1, null)));
        assertThat(r.issues()).containsExactly(
                "2 séances programmées au 1er semestre pour 1 séance attendue.",
                "0 séance programmée au 2e semestre pour 1 séance attendue.");
        assertThat(r.expected()).isEqualTo(2);
    }

    @Test
    void parPlantationChaquePlantationDoitAtteindreLeNombre() {
        var f = new Frequency(2, "ANNEE", "PLANTATION", null, null, "");
        var r = ControleFrequences.controler(activite(1, f), List.of(
                ligne(2, 1, 1, "P1"), ligne(8, 1, 1, "P1"), ligne(3, 1, 1, "P2")));
        assertThat(r.ok()).isFalse();
        assertThat(r.issues()).containsExactly("Plantation P2 : 1 séance programmée pour 2 séances attendues.");
        assertThat(r.expected()).isNull();
    }

    @Test
    void laDureeDeLaSeanceEstVerifiee() {
        var r = ControleFrequences.controler(activite(3, new Frequency(1, "ANNEE", null, null, null, "")), List.of(ligne(5, 4, 1, null)));
        assertThat(r.issues()).containsExactly("La séance du 04/05/2026 dure 1 jour au lieu de 3 jours.");
    }

    @Test
    void deuxSeancesDUneMemeUniteNeSeChevauchentPas() {
        var f = new Frequency(2, "ANNEE", "PLANTATION", null, null, "");
        var r = ControleFrequences.controler(activite(3, f), List.of(ligne(5, 4, 3, "P1"), ligne(5, 6, 3, "P1")));
        assertThat(r.issues()).anyMatch(m -> m.contains("se chevauchent"));
        // des plantations différentes ne se gênent pas
        var autres = ControleFrequences.controler(activite(3, new Frequency(1, "ANNEE", "PLANTATION", null, null, "")),
                List.of(ligne(5, 4, 3, "P1"), ligne(5, 6, 3, "P2")));
        assertThat(autres.ok()).isTrue();
    }

    @Test
    void uneFrequenceQuiDependDeLaSuperficieNeControlePasLeNombre() {
        var tranches = List.of(
                new Frequency(4, "ANNEE", "PLANTATION", BigDecimal.ZERO, new BigDecimal("25"), ""),
                new Frequency(3, "ANNEE", "PLANTATION", new BigDecimal("25"), null, ""));
        var activite = new PlanActivity(ACTIVITE, "VISITE", "Visite", "VISITE", 1, true, null, null, tranches);
        var lignes = new ArrayList<>(List.of(ligne(2, 1, 1, "P1")));
        assertThat(ControleFrequences.controler(activite, lignes).ok()).isTrue();
    }
}
