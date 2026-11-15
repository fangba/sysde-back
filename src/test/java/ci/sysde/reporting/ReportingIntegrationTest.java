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

/** Reporting : activités validées seulement, réalisations, anomalies, trois niveaux, périmètres. */
class ReportingIntegrationTest extends AbstractIntegrationTest {

    private static final String JUIN = String.format("%d-06", ANNEE);

    private void realiser(RestClient c, UUID ligne, int jour, int heure, int minutes, boolean gps) {
        var m = new HashMap<String, Object>();
        OffsetDateTime debut = OffsetDateTime.of(ANNEE, 6, jour, heure, 0, 0, 0, ZoneOffset.UTC);
        m.put("lineId", ligne.toString());
        m.put("status", "TERMINEE");
        m.put("startedAt", debut.toString());
        m.put("endedAt", debut.plusMinutes(minutes).toString());
        if (gps) {
            m.put("latitude", 5.345);
            m.put("longitude", -4.012);
        }
        assertThat(statut(c.put().uri("/realisations/" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).body(m).retrieve().toEntity(OBJET)))
                .isEqualTo(HttpStatus.OK);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> ligneDe(Map<String, Object> synthese, UUID id) {
        return ((List<Map<String, Object>>) synthese.get("rows")).stream().filter(r -> id.toString().equals(r.get("id"))).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private UUID idLigne(Map<String, Object> plan, int jour, String plantation) {
        return ((List<Map<String, Object>>) plan.get("lines")).stream()
                .filter(l -> l.get("date").toString().endsWith("-06-" + String.format("%02d", jour)) && java.util.Objects.equals(plantation, l.get("plantationRef")))
                .map(l -> UUID.fromString((String) l.get("id"))).findFirst().orElseThrow();
    }

    @Test
    void lesTroisNiveauxCalculentSurLesActivitesValideesSeulement() {
        Secteur s = secteur("RPT", "03");
        Secteur autre = secteur("RPU", "04");
        var mo = s.moC();
        var co = clientCompte(s.co(), "CO", s.equipe().operateur());

        // 6 activités du moniteur en juin ; un plan de contrôleur ordinaire seulement soumis (ne compte pas)
        var plan = put(mo, "/plannings/mon-planning/lignes", List.of(
                ligne("PERFECTIONNEMENT_IN_SITU", 6, 2, s, true, null), ligne("PERFECTIONNEMENT_IN_SITU", 6, 9, s, true, null),
                ligne("VISITE_PLANTATION", 6, 16, s, true, "PA"), ligne("VISITE_PLANTATION", 6, 17, s, true, "PA"),
                ligne("VISITE_PLANTATION", 6, 18, s, true, "PB"), ligne("VISITE_PLANTATION", 6, 18, s, true, "PB"))).getBody();
        put(co, "/plannings/mon-planning/lignes", List.of(ligne("VISITE_CONFORMITE", 6, 4, s, true, "PC")));
        post(co, "/plannings/mon-planning/soumission");
        // avant validation du plan du moniteur : rien n'est compté
        var avant = s.cs().get().uri("/reporting/secteur/synthese?du=" + JUIN).retrieve().toEntity(OBJET).getBody();
        assertThat(ligneDe(avant, s.mo())).containsEntry("planned", 0).containsEntry("status", "SANS_PREVU");
        post(mo, "/plannings/mon-planning/soumission");
        assertThat(statut(s.cs().post().uri("/plannings/" + plan.get("id") + "/validation").contentType(MediaType.APPLICATION_JSON).body(Map.of()).retrieve().toEntity(OBJET)))
                .isEqualTo(HttpStatus.OK);

        // réalisations : 2/6 normale ; 9/6 sans GPS ; 16/6 trop courte (5 min) ; 17/6 jamais faite ; 18/6 deux visites de la même plantation
        realiser(mo, idLigne(plan, 2, null), 2, 8, 180, true);
        realiser(mo, idLigne(plan, 9, null), 9, 8, 120, false);
        realiser(mo, idLigne(plan, 16, "PA"), 16, 9, 5, true);
        @SuppressWarnings("unchecked") var lignes18 = ((List<Map<String, Object>>) plan.get("lines")).stream()
                .filter(l -> l.get("date").toString().endsWith("-06-18")).map(l -> UUID.fromString((String) l.get("id"))).toList();
        realiser(mo, lignes18.get(0), 18, 8, 60, true);
        realiser(mo, lignes18.get(1), 18, 10, 60, true);

        // ---- niveau secteur : synthèse par collaborateur
        var synthese = s.cs().get().uri("/reporting/secteur/synthese?du=" + JUIN + "&au=" + JUIN).retrieve().toEntity(OBJET).getBody();
        var moi = ligneDe(synthese, s.mo());
        assertThat(moi).containsEntry("planned", 6).containsEntry("done", 5).containsEntry("late", 1).containsEntry("toVerify", 4)
                .containsEntry("status", "LEGER_RETARD");
        assertThat(((Number) moi.get("rate")).doubleValue()).isBetween(0.83, 0.84);
        assertThat(ligneDe(synthese, s.co())).containsEntry("planned", 0).containsEntry("status", "SANS_PREVU");     // plan soumis : ne compte pas
        assertThat(synthese).containsEntry("from", JUIN).containsEntry("to", JUIN).containsKey("today");

        // liste brute du chef secteur : anomalies par activité
        var brute = s.cs().get().uri("/reporting/secteur/activites?du=" + JUIN).retrieve().toEntity(LISTE).getBody().stream()
                .filter(a -> s.mo().toString().equals(a.get("accountId"))).toList();
        assertThat(brute).hasSize(6);
        assertThat(brute).extracting(a -> a.get("anomaly")).containsExactlyInAnyOrder(null, "SANS_GEO", "COURTE", null, "DOUBLON", "DOUBLON");
        assertThat(brute.stream().filter(a -> a.get("doneOn") == null)).hasSize(1);                                       // celle du 17/6
        assertThat(brute).extracting(a -> a.get("category")).contains("FORMATION", "VISITE");
        var collaborateurs = s.cs().get().uri("/reporting/secteur/collaborateurs").retrieve().toEntity(LISTE).getBody();
        assertThat(collaborateurs).extracting(c -> c.get("id")).contains(s.mo().toString(), s.co().toString()).doesNotContain(s.se().toString());

        // ---- niveau département : le chef de département voit le secteur, avec ses plannings par état
        var cd = clientCompte(s.equipe().cd(), "CD", s.equipe().operateur());
        var secteurs = cd.get().uri("/reporting/departement/secteurs?annee=" + ANNEE).retrieve().toEntity(LISTE).getBody();
        var monSecteur = secteurs.stream().filter(x -> "03".equals(x.get("code"))).findFirst().orElseThrow();
        @SuppressWarnings("unchecked") var plans = (Map<String, Object>) monSecteur.get("plans");
        assertThat((Integer) plans.get("validated")).isGreaterThanOrEqualTo(1);
        assertThat((Integer) plans.get("submitted")).isGreaterThanOrEqualTo(1);                                          // le plan du CO
        assertThat((Integer) monSecteur.get("collaborators")).isGreaterThanOrEqualTo(2);
        var depSynthese = cd.get().uri("/reporting/departement/synthese?du=" + JUIN).retrieve().toEntity(OBJET).getBody();
        var ligneSecteur = ligneDe(depSynthese, UUID.fromString((String) monSecteur.get("id")));
        assertThat((Integer) ligneSecteur.get("planned")).isGreaterThanOrEqualTo(6);
        assertThat((Integer) ligneSecteur.get("toVerify")).isGreaterThanOrEqualTo(4);
        assertThat(secteurs).extracting(x -> x.get("code")).doesNotContain("04");                                       // secteur d'un autre opérateur

        // ---- niveau national : superviseur et administrateur voient les opérateurs, pas de rapport mensuel inventé
        UUID su = creerCompte(adminId, fircaId, "SU");
        var national = clientCompte(su, "SU", fircaId);
        var operateurs = national.get().uri("/reporting/national/operateurs?annee=" + ANNEE).retrieve().toEntity(LISTE).getBody();
        var op = operateurs.stream().filter(x -> "PL03".equals(x.get("acronym"))).findFirst().orElseThrow();
        assertThat(op).doesNotContainKey("monthlyReport");
        assertThat((Integer) op.get("collaborators")).isGreaterThanOrEqualTo(2);
        var natSynthese = national.get().uri("/reporting/national/synthese?du=" + JUIN).retrieve().toEntity(OBJET).getBody();
        assertThat((Integer) ligneDe(natSynthese, s.equipe().operateur()).get("planned")).isGreaterThanOrEqualTo(6);
        assertThat(statut(admin().get().uri("/reporting/national/synthese?du=" + JUIN).retrieve().toEntity(OBJET))).isEqualTo(HttpStatus.OK);

        // ---- périmètres : chaque niveau est réservé à son profil ; un autre secteur ne voit rien de ces chiffres
        assertThat(autre.cs().get().uri("/reporting/secteur/collaborateurs").retrieve().toEntity(LISTE).getBody()).extracting(c -> c.get("id")).doesNotContain(s.mo().toString());
        assertThat(ligneDe(autre.cs().get().uri("/reporting/secteur/synthese?du=" + JUIN).retrieve().toEntity(OBJET).getBody(), autre.mo())).containsEntry("planned", 0);
        assertThat(cd.get().uri("/reporting/secteur/synthese").retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(s.cs().get().uri("/reporting/departement/secteurs").retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(cd.get().uri("/reporting/national/operateurs").retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(s.cs().get().uri("/reporting/national/synthese").retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(mo.get().uri("/reporting/secteur/synthese").retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);   // pas CONSULTER_REPORTING
    }

    @Test
    void uneActiviteRealiseeHorsDuContourDuSecteurEstASurveiller() {
        Secteur s = secteur("RPW", "04");
        // contour du secteur : un carré de 0,1° ; un fichier de contours s'importe puis s'applique
        var corpsImport = new org.springframework.util.LinkedMultiValueMap<String, Object>();
        corpsImport.add("file", new org.springframework.core.io.ByteArrayResource(("{\"type\":\"Polygon\",\"coordinates\":[[[-7.0,6.5],[-6.9,6.5],[-6.9,6.6],[-7.0,6.6],[-7.0,6.5]]]}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            @Override public String getFilename() { return "s04.geojson"; }
        });
        corpsImport.add("level", "SECTEUR");
        corpsImport.add("targetId", s.equipe().secteur().toString());
        String importId = (String) admin().post().uri("/imports-contours").contentType(MediaType.MULTIPART_FORM_DATA).body(corpsImport)
                .retrieve().toEntity(OBJET).getBody().get("id");
        admin().post().uri("/imports-contours/" + importId + "/application").retrieve().toBodilessEntity();

        var mo = s.moC();
        var plan = put(mo, "/plannings/mon-planning/lignes", List.of(
                ligne("PERFECTIONNEMENT_IN_SITU", 6, 2, s, true, null), ligne("PERFECTIONNEMENT_IN_SITU", 6, 9, s, true, null),
                ligne("PERFECTIONNEMENT_IN_SITU", 6, 16, s, true, null), ligne("PERFECTIONNEMENT_IN_SITU", 6, 23, s, true, null))).getBody();
        post(mo, "/plannings/mon-planning/soumission");
        s.cs().post().uri("/plannings/" + plan.get("id") + "/validation").contentType(MediaType.APPLICATION_JSON).body(Map.of()).retrieve().toBodilessEntity();

        record Point(int jour, double lat, double lon, double precision) { }
        for (Point p : List.of(new Point(2, 6.55, -6.95, 5), new Point(9, 5.30, -4.00, 5),               // dedans ; très loin
                new Point(16, 6.55, -6.8998, 5),                                                           // ~22 m dehors : toléré (50 m)
                new Point(23, 6.55, -6.8900, 5))) {                                                        // ~1,1 km dehors
            var m = new HashMap<String, Object>();
            OffsetDateTime debut = OffsetDateTime.of(ANNEE, 6, p.jour(), 8, 0, 0, 0, ZoneOffset.UTC);
            m.put("lineId", idLigne(plan, p.jour(), null).toString());
            m.put("status", "TERMINEE");
            m.put("startedAt", debut.toString());
            m.put("endedAt", debut.plusHours(2).toString());
            m.put("latitude", p.lat());
            m.put("longitude", p.lon());
            m.put("accuracyM", p.precision());
            assertThat(statut(mo.put().uri("/realisations/" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).body(m).retrieve().toEntity(OBJET))).isEqualTo(HttpStatus.OK);
        }
        var activites = s.cs().get().uri("/reporting/secteur/activites?du=" + JUIN).retrieve().toEntity(LISTE).getBody().stream()
                .filter(a -> s.mo().toString().equals(a.get("accountId"))).sorted(java.util.Comparator.comparing(a -> a.get("plannedOn").toString())).toList();
        assertThat(activites).extracting(a -> a.get("anomaly")).containsExactly(null, "HORS_SECTEUR", null, "HORS_SECTEUR");
        // sans contour de secteur, jamais signalée : le secteur 03 n'en a pas (cf. le test précédent, où les positions sont à Abidjan)
    }

    @Test
    void laPeriodeEstControlee() {
        Secteur s = secteur("RPV", "04");
        assertThat(s.cs().get().uri("/reporting/secteur/synthese").retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.OK);          // mois en cours
        assertThat(s.cs().get().uri("/reporting/secteur/synthese?du=juin").retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(s.cs().get().uri("/reporting/secteur/synthese?du=2026-06&au=2026-05").retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(s.cs().get().uri("/reporting/secteur/synthese?du=2020-01&au=2026-12").retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(s.cs().get().uri("/reporting/secteur/synthese?du=2026-01&au=2026-12").retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
