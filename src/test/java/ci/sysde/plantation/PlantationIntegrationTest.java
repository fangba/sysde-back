package ci.sysde.plantation;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import ci.sysde.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/** Plantations (base externe simulée) et carte : territoire, affectation aux sections, contours. */
class PlantationIntegrationTest extends AbstractIntegrationTest {

    private static final List<double[]> CARRE = List.of(new double[] {5.30, -4.00}, new double[] {5.30, -3.99},
            new double[] {5.31, -3.99}, new double[] {5.31, -4.00}, new double[] {5.30, -4.00});

    private int numeroDeLot(UUID lot) {
        return jdbc.sql("select numero from referentiel.lot where id = :id").param("id", lot).query(Integer.class).single();
    }

    private PlantationExterne ext(String ref, String code, String secteur, int lot, boolean polygone) {
        return new PlantationExterne(ref, code, "Planteur " + ref, "0102030405", "Village", "PLx", secteur, lot, "Nord",
                new BigDecimal("12.5"), 2015, "PB260", true, 5.3, -4.0, polygone ? CARRE : List.of());
    }

    /** Deux secteurs d'opérateurs différents (01 et 03), quelques plantations dans chacun. */
    private record Jeu(Secteur a, Secteur b, int lotA0, int lotA1, int lotB0, String p1, String p2, String p3, String pb) {
    }

    private Jeu jeu() {
        Secteur a = secteur("PA", "01");
        Secteur b = secteur("PB", "03");
        int lotA0 = numeroDeLot(a.equipe().lots().get(0));
        int lotA1 = numeroDeLot(a.equipe().lots().get(1));
        int lotB0 = numeroDeLot(b.equipe().lots().get(0));
        String suffixe = UUID.randomUUID().toString().substring(0, 6);
        String p1 = "A1-" + suffixe, p2 = "A2-" + suffixe, p3 = "A3-" + suffixe, pb = "B1-" + suffixe;
        SourcePlantationsDeTest.DONNEES.add(ext(p1, "PL-" + p1, "01", lotA0, true));
        SourcePlantationsDeTest.DONNEES.add(ext(p2, "PL-" + p2, "01", lotA1, true));
        SourcePlantationsDeTest.DONNEES.add(ext(p3, "PL-" + p3, "01", lotA0, false));      // sans contour
        SourcePlantationsDeTest.DONNEES.add(ext(pb, "PL-" + pb, "03", lotB0, true));
        return new Jeu(a, b, lotA0, lotA1, lotB0, p1, p2, p3, pb);
    }

    private List<Map<String, Object>> liste(RestClient c, String uri) {
        var r = c.get().uri(uri).retrieve().toEntity(LISTE);
        assertThat(statut(r)).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    private HttpStatus affecter(RestClient c, String ref, UUID section) {
        var corps = new java.util.HashMap<String, Object>();
        corps.put("sectionId", section == null ? null : section.toString());
        return statut(c.put().uri("/plantations/" + ref + "/section").contentType(MediaType.APPLICATION_JSON).body(corps)
                .retrieve().toEntity(OBJET));
    }

    @Test
    void chacunNeVoitQueLesPlantationsDeSonTerritoire() {
        Jeu j = jeu();
        var csA = liste(j.a().cs(), "/plantations").stream().map(p -> (String) p.get("id")).toList();
        assertThat(csA).contains(j.p1(), j.p2(), j.p3()).doesNotContain(j.pb());
        var admin = liste(admin(), "/plantations").stream().map(p -> (String) p.get("id")).toList();
        assertThat(admin).contains(j.p1(), j.pb());
        // l'opérateur affiché est celui du titulaire du lot (SysDE), pas celui de la base externe ("PLx")
        var une = liste(j.a().cs(), "/plantations?q=" + j.p1()).get(0);
        assertThat(une).containsEntry("operator", "PL01").containsEntry("lot", j.lotA0()).containsEntry("stage", "SAIGNEE")
                .containsEntry("geolocated", true).containsEntry("clone", "PB260").doesNotContainKey("sectionId");
        // un moniteur du secteur voit son secteur ; le filtre par lot et la recherche affinent
        assertThat(liste(j.a().moC(), "/plantations").stream().map(p -> p.get("id")).toList()).contains(j.p1());
        assertThat(liste(j.a().cs(), "/plantations?lot=" + j.lotA1()).stream().map(p -> p.get("id")).toList())
                .contains(j.p2()).doesNotContain(j.p1());
        assertThat(liste(j.a().cs(), "/plantations?q=zzz-introuvable")).isEmpty();
    }

    @Test
    void leChefSecteurAffecteUnePlantationASectionDeSonLotSeulement() {
        Jeu j = jeu();
        var r = j.a().cs().put().uri("/plantations/" + j.p1() + "/section").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("sectionId", j.a().section().toString())).retrieve().toEntity(OBJET);
        assertThat(statut(r)).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).containsEntry("sectionId", j.a().section().toString()).containsKey("sectionName");
        // la liste porte l'affectation
        assertThat(liste(j.a().cs(), "/plantations?q=" + j.p1()).get(0)).containsEntry("sectionId", j.a().section().toString());
        // renvoyer la même affectation : sans effet, 200
        assertThat(affecter(j.a().cs(), j.p1(), j.a().section())).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.sql("select count(*) from referentiel.affectation_plantation where plantation_ref = :r and fin is null")
                .param("r", j.p1()).query(Long.class).single()).isEqualTo(1L);
        // une section d'un autre lot : 400
        UUID sectionAutreLot = jdbc.sql("insert into referentiel.section (lot_id, numero, nom) values (:l, "
                        + "(select coalesce(max(numero), 0) + 1 from referentiel.section where lot_id = :l), :n) returning id")
                .param("l", j.a().equipe().lots().get(1)).param("n", "Lot1-" + UUID.randomUUID().toString().substring(0, 6))
                .query(UUID.class).single();
        assertThat(affecter(j.a().cs(), j.p1(), sectionAutreLot)).isEqualTo(HttpStatus.BAD_REQUEST);
        // changer de section garde l'historique ; retirer (null) clôt l'affectation
        assertThat(affecter(j.a().cs(), j.p2(), sectionAutreLot)).isEqualTo(HttpStatus.OK);
        UUID autre = sectionDe(j.a().equipe(), "Est-" + UUID.randomUUID().toString().substring(0, 6));
        assertThat(affecter(j.a().cs(), j.p1(), autre)).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.sql("select count(*) from referentiel.affectation_plantation where plantation_ref = :r").param("r", j.p1())
                .query(Long.class).single()).isEqualTo(2L);
        assertThat(affecter(j.a().cs(), j.p1(), null)).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.sql("select count(*) from referentiel.affectation_plantation where plantation_ref = :r and fin is null")
                .param("r", j.p1()).query(Long.class).single()).isZero();
    }

    @Test
    void lesAffectationsRespectentLeProfilLePerimetreEtLeToutOuRien() {
        Jeu j = jeu();
        // un moniteur n'a pas la permission : 403 ; une plantation d'un autre secteur : 404
        assertThat(affecter(j.a().moC(), j.p1(), j.a().section())).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(affecter(j.a().cs(), j.pb(), j.a().section())).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(affecter(j.a().cs(), "inconnue-" + UUID.randomUUID(), j.a().section())).isEqualTo(HttpStatus.NOT_FOUND);
        // l'administrateur lit mais n'affecte pas (réservé au chef secteur)
        assertThat(affecter(admin(), j.p1(), j.a().section())).isIn(HttpStatus.FORBIDDEN);

        // lot : une ligne invalide annule tout
        var corps = List.of(Map.of("plantationId", j.p1(), "sectionId", j.a().section().toString()),
                Map.of("plantationId", j.pb(), "sectionId", j.a().section().toString()));
        var refus = j.a().cs().put().uri("/plantations/affectations").contentType(MediaType.APPLICATION_JSON).body(corps)
                .retrieve().toEntity(OBJET);
        assertThat(statut(refus)).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(jdbc.sql("select count(*) from referentiel.affectation_plantation where plantation_ref = :r").param("r", j.p1())
                .query(Long.class).single()).isZero();
        // lot valide : renvoie celles qui ont changé
        var bon = j.a().cs().put().uri("/plantations/affectations").contentType(MediaType.APPLICATION_JSON)
                .body(List.of(Map.of("plantationId", j.p1(), "sectionId", j.a().section().toString()),
                        Map.of("plantationId", j.p3(), "sectionId", j.a().section().toString())))
                .retrieve().toEntity(LISTE);
        assertThat(statut(bon)).isEqualTo(HttpStatus.OK);
        assertThat(bon.getBody()).hasSize(2);
        // doublon dans la demande : 400
        assertThat(statut(j.a().cs().put().uri("/plantations/affectations").contentType(MediaType.APPLICATION_JSON)
                .body(List.of(Map.of("plantationId", j.p1()), Map.of("plantationId", j.p1()))).retrieve().toEntity(OBJET)))
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @SuppressWarnings("unchecked")
    void laCarteDonneLeCatalogueEtLesContoursDansLePerimetre() {
        Jeu j = jeu();
        // catalogue du chef secteur : son opérateur, son secteur, ses lots avec leurs sections
        var cat = liste(j.a().cs(), "/carte/catalogue");
        assertThat(cat).hasSize(1);
        assertThat(cat.get(0)).containsEntry("acronym", "PL01");
        var secteurs = (List<Map<String, Object>>) cat.get(0).get("sectors");
        assertThat(secteurs).hasSize(1).first().satisfies(s -> assertThat(s).containsEntry("code", "01"));
        var lots = (List<Map<String, Object>>) secteurs.get(0).get("lots");
        assertThat(lots.stream().filter(l -> ((Number) l.get("lot")).intValue() == j.lotA0()).findFirst().orElseThrow().get("sections"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST).isNotEmpty();
        // l'administrateur voit au moins les deux opérateurs
        assertThat(liste(admin(), "/carte/catalogue").stream().map(o -> o.get("acronym")).toList()).contains("PL01", "PL03");

        UUID op = UUID.fromString((String) cat.get(0).get("id"));
        var carte = liste(j.a().cs(), "/carte/plantations?operatorId=" + op);
        var ids = carte.stream().map(p -> (String) p.get("id")).toList();
        assertThat(ids).contains(j.p1(), j.p2()).doesNotContain(j.p3(), j.pb());     // sans contour : absente
        var p1 = carte.stream().filter(p -> j.p1().equals(p.get("id"))).findFirst().orElseThrow();
        assertThat((List<?>) p1.get("polygon")).hasSize(5);
        assertThat((List<Number>) ((List<?>) p1.get("polygon")).get(0)).extracting(Number::doubleValue).containsExactly(5.30, -4.00);
        assertThat(liste(j.a().cs(), "/carte/plantations?operatorId=" + op + "&lot=" + j.lotA1()).stream().map(p -> p.get("id")).toList())
                .contains(j.p2()).doesNotContain(j.p1());
        // un autre opérateur ou un autre secteur que le sien : 403
        UUID opB = j.b().equipe().operateur();
        assertThat(statut(j.a().cs().get().uri("/carte/plantations?operatorId=" + opB).retrieve().toEntity(OBJET))).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(statut(j.a().cs().get().uri("/carte/plantations?operatorId=" + op + "&sectorId=" + j.b().equipe().secteur())
                .retrieve().toEntity(OBJET))).isEqualTo(HttpStatus.FORBIDDEN);
        // l'administrateur choisit n'importe quel opérateur
        assertThat(liste(admin(), "/carte/plantations?operatorId=" + opB).stream().map(p -> p.get("id")).toList()).contains(j.pb());
    }
}
