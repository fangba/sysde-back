package ci.sysde.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import ci.sysde.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/** Les activités spontanées dans le reporting : à part du taux, avec leurs anomalies (hors secteur, doublon). */
class ReportingSpontaneesIntegrationTest extends AbstractIntegrationTest {

    private static final String SEPTEMBRE = String.format("%d-09", ANNEE);

    private void importerContourDuSecteur(Secteur s) {
        var corps = new org.springframework.util.LinkedMultiValueMap<String, Object>();
        corps.add("file", new org.springframework.core.io.ByteArrayResource(("{\"type\":\"Polygon\",\"coordinates\":[[[-7.0,6.5],[-6.9,6.5],[-6.9,6.6],[-7.0,6.6],[-7.0,6.5]]]}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            @Override public String getFilename() { return "s04.geojson"; }
        });
        corps.add("level", "SECTEUR");
        corps.add("targetId", s.equipe().secteur().toString());
        String id = (String) admin().post().uri("/imports-contours").contentType(MediaType.MULTIPART_FORM_DATA).body(corps)
                .retrieve().toEntity(OBJET).getBody().get("id");
        admin().post().uri("/imports-contours/" + id + "/application").retrieve().toBodilessEntity();
    }

    private HttpStatus decouverte(RestClient mo, int jour, String plantation, double lat, double lon, String statut) {
        var m = new HashMap<String, Object>();
        OffsetDateTime debut = OffsetDateTime.of(ANNEE, 9, jour, 8, 0, 0, 0, ZoneOffset.UTC);
        m.put("activityId", activite("DECOUVERTE_VERGER").toString());
        m.put("plantationRef", plantation);
        m.put("status", statut);
        m.put("startedAt", debut.toString());
        if ("TERMINEE".equals(statut)) {
            m.put("endedAt", debut.plusMinutes(30).toString());
        }
        m.put("latitude", lat);
        m.put("longitude", lon);
        m.put("accuracyM", 5);
        return statut(mo.put().uri("/realisations/" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).body(m).retrieve().toEntity(OBJET));
    }

    @Test
    @SuppressWarnings("unchecked")
    void lesActivitesSpontaneesSontCompteesAPartSansToucherAuTaux() {
        Secteur s = secteur("RSP", "04");
        importerContourDuSecteur(s);
        var mo = s.moC();
        // une activité prévue, validée et réalisée en septembre : le taux reste 1 / 1
        var plan = put(mo, "/plannings/mon-planning/lignes", List.of(ligne("PERFECTIONNEMENT_IN_SITU", 9, 10, s, true, null))).getBody();
        post(mo, "/plannings/mon-planning/soumission");
        s.cs().post().uri("/plannings/" + plan.get("id") + "/validation").contentType(MediaType.APPLICATION_JSON).body(Map.of()).retrieve().toBodilessEntity();
        var ligneId = ((List<Map<String, Object>>) plan.get("lines")).get(0).get("id");
        var prevue = new HashMap<String, Object>();
        OffsetDateTime d10 = OffsetDateTime.of(ANNEE, 9, 10, 8, 0, 0, 0, ZoneOffset.UTC);
        prevue.put("lineId", ligneId);
        prevue.put("status", "TERMINEE");
        prevue.put("startedAt", d10.toString());
        prevue.put("endedAt", d10.plusHours(2).toString());
        prevue.put("latitude", 6.55);
        prevue.put("longitude", -6.95);
        assertThat(statut(mo.put().uri("/realisations/" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).body(prevue).retrieve().toEntity(OBJET))).isEqualTo(HttpStatus.OK);

        // quatre découvertes terminées : une normale, une très loin du secteur, deux sur la même plantation le même jour ; une en cours
        assertThat(decouverte(mo, 3, "P-NORMALE", 6.55, -6.95, "TERMINEE")).isEqualTo(HttpStatus.OK);
        assertThat(decouverte(mo, 4, "P-LOIN", 5.30, -4.00, "TERMINEE")).isEqualTo(HttpStatus.OK);
        assertThat(decouverte(mo, 5, "P-DOUBLE", 6.55, -6.95, "TERMINEE")).isEqualTo(HttpStatus.OK);
        assertThat(decouverte(mo, 5, "P-DOUBLE", 6.55, -6.95, "TERMINEE")).isEqualTo(HttpStatus.OK);
        assertThat(decouverte(mo, 6, "P-ENCOURS", 6.55, -6.95, "EN_COURS")).isEqualTo(HttpStatus.OK);

        // chef secteur : synthèse du moniteur
        var synthese = s.cs().get().uri("/reporting/secteur/synthese?du=" + SEPTEMBRE).retrieve().toEntity(OBJET).getBody();
        var ligneMo = ((List<Map<String, Object>>) synthese.get("rows")).stream().filter(r -> s.mo().toString().equals(r.get("id"))).findFirst().orElseThrow();
        assertThat(ligneMo).containsEntry("planned", 1).containsEntry("done", 1).containsEntry("toVerify", 0).containsEntry("rate", 1.0)
                .containsEntry("spontaneous", 4).containsEntry("spontaneousToVerify", 3);               // l'activité en cours n'est pas comptée

        // liste brute : l'activité prévue et les quatre découvertes, celles-ci sans ligne ni date prévue
        var brute = s.cs().get().uri("/reporting/secteur/activites?du=" + SEPTEMBRE).retrieve().toEntity(LISTE).getBody().stream()
                .filter(a -> s.mo().toString().equals(a.get("accountId"))).toList();
        assertThat(brute).hasSize(5);
        var spontanees = brute.stream().filter(a -> Boolean.TRUE.equals(a.get("spontaneous"))).toList();
        assertThat(spontanees).hasSize(4).allSatisfy(a -> assertThat(a).doesNotContainKeys("lineId", "plannedOn").containsKeys("realisationId", "doneOn"));
        assertThat(spontanees).extracting(a -> a.get("anomaly")).containsExactlyInAnyOrder(null, "HORS_SECTEUR", "DOUBLON", "DOUBLON");
        assertThat(brute.stream().filter(a -> !Boolean.TRUE.equals(a.get("spontaneous"))).findFirst().orElseThrow()).containsKey("lineId");

        // chef de département et administrateur : les compteurs sont aussi dans leurs synthèses
        var cd = clientCompte(s.equipe().cd(), "CD", s.equipe().operateur());
        var total = ((Map<String, Object>) cd.get().uri("/reporting/departement/synthese?du=" + SEPTEMBRE).retrieve().toEntity(OBJET).getBody().get("total"));
        assertThat(((Number) total.get("spontaneous")).intValue()).isGreaterThanOrEqualTo(4);
        assertThat(((Number) total.get("spontaneousToVerify")).intValue()).isGreaterThanOrEqualTo(3);
        var national = ((Map<String, Object>) admin().get().uri("/reporting/national/synthese?du=" + SEPTEMBRE).retrieve().toEntity(OBJET).getBody().get("total"));
        assertThat(((Number) national.get("spontaneous")).intValue()).isGreaterThanOrEqualTo(4);
    }
}
