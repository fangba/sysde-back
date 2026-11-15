package ci.sysde.activite;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import ci.sysde.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/** Référentiel des activités : création, modification, fréquences par profil, protection des activités déjà planifiées. */
class ActiviteIntegrationTest extends AbstractIntegrationTest {

    private static String lettres() {
        StringBuilder sb = new StringBuilder("T");
        for (char c : UUID.randomUUID().toString().replace("-", "").toCharArray()) {
            sb.append((char) ('A' + (c % 26)));
        }
        return sb.substring(0, 12);
    }

    private Map<String, Object> corps(String code, String categorie) {
        var m = new HashMap<String, Object>();
        if (code != null) {
            m.put("code", code);
        }
        m.put("label", "Activité d'essai " + code);
        m.put("category", categorie);
        m.put("durationDays", 1);
        m.put("planned", true);
        m.put("withAttendance", true);
        m.put("withEvaluation", false);
        m.put("withReport", true);
        m.put("active", true);
        m.put("profiles", List.of(
                Map.of("profile", "MO", "frequencies", List.of(
                        Map.of("count", 4, "period", "ANNEE", "per", "PLANTATION", "maxAreaHa", 25),
                        Map.of("count", 2, "period", "ANNEE", "per", "PLANTATION", "minAreaHa", 25))),
                Map.of("profile", "CO", "frequencies", List.of())));
        return m;
    }

    private org.springframework.http.ResponseEntity<Map<String, Object>> envoyer(RestClient c, String methode, String uri, Object corps) {
        var spec = "PUT".equals(methode) ? c.put().uri(uri) : c.post().uri(uri);
        return spec.contentType(MediaType.APPLICATION_JSON).body(corps).retrieve().toEntity(OBJET);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> profils(Map<String, Object> activite) {
        return (List<Map<String, Object>>) activite.get("profiles");
    }

    @Test
    @SuppressWarnings("unchecked")
    void onCreeUneActiviteAvecSesFrequencesParProfilEtOnLaRelit() {
        String code = lettres();
        var r = envoyer(admin(), "POST", "/activites", corps(code, "RENCONTRE"));
        assertThat(statut(r)).isEqualTo(HttpStatus.CREATED);
        var a = r.getBody();
        assertThat(a).containsEntry("code", code).containsEntry("category", "RENCONTRE").containsEntry("active", true);
        var mo = profils(a).stream().filter(p -> "MO".equals(p.get("profile"))).findFirst().orElseThrow();
        assertThat((List<?>) mo.get("frequencies")).hasSize(2);
        var co = profils(a).stream().filter(p -> "CO".equals(p.get("profile"))).findFirst().orElseThrow();
        assertThat((List<?>) co.get("frequencies")).isEmpty();                              // profil sans fréquence définie
        // lecture par identifiant et dans la liste ; un chef secteur lit mais n'écrit pas
        String id = (String) a.get("id");
        assertThat(admin().get().uri("/activites/" + id).retrieve().toEntity(OBJET).getBody()).containsEntry("code", code);
        Secteur s = secteur("AC", "04");
        var liste = s.cs().get().uri("/activites").retrieve().toEntity(LISTE);
        assertThat(statut(liste)).isEqualTo(HttpStatus.OK);
        assertThat(liste.getBody().stream().map(x -> x.get("code")).toList()).contains(code, "VISITE_PLANTATION");
        assertThat(statut(envoyer(s.cs(), "POST", "/activites", corps(lettres(), "RENCONTRE")))).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(statut(envoyer(s.cs(), "PUT", "/activites/" + id, corps(code, "RENCONTRE")))).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void lesControlesDeFormeEtDUniciteRefusentLesActivitesInvalides() {
        String code = lettres();
        assertThat(statut(envoyer(admin(), "POST", "/activites", corps(code, "RENCONTRE")))).isEqualTo(HttpStatus.CREATED);
        assertThat(statut(envoyer(admin(), "POST", "/activites", corps(code, "RENCONTRE")))).as("code déjà pris").isEqualTo(HttpStatus.CONFLICT);
        assertThat(statut(envoyer(admin(), "POST", "/activites", corps(null, "RENCONTRE")))).as("code obligatoire").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(statut(envoyer(admin(), "POST", "/activites", corps("minuscules", "RENCONTRE")))).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(statut(envoyer(admin(), "POST", "/activites", corps(lettres(), "AUTRE")))).as("catégorie inconnue").isEqualTo(HttpStatus.BAD_REQUEST);

        var profilInconnu = corps(lettres(), "RENCONTRE");
        profilInconnu.put("profiles", List.of(Map.of("profile", "AD", "frequencies", List.of())));
        assertThat(statut(envoyer(admin(), "POST", "/activites", profilInconnu))).as("profil sans planning").isEqualTo(HttpStatus.BAD_REQUEST);

        var sansPeriode = corps(lettres(), "RENCONTRE");
        sansPeriode.put("profiles", List.of(Map.of("profile", "MO", "frequencies", List.of(Map.of("count", 3)))));
        assertThat(statut(envoyer(admin(), "POST", "/activites", sansPeriode))).as("nombre sans période").isEqualTo(HttpStatus.BAD_REQUEST);

        var tranches = corps(lettres(), "RENCONTRE");
        tranches.put("profiles", List.of(Map.of("profile", "MO", "frequencies", List.of(
                Map.of("count", 1, "period", "ANNEE", "minAreaHa", 10), Map.of("count", 2, "period", "ANNEE", "minAreaHa", 10)))));
        assertThat(statut(envoyer(admin(), "POST", "/activites", tranches))).as("deux tranches identiques").isEqualTo(HttpStatus.BAD_REQUEST);

        var inverse = corps(lettres(), "RENCONTRE");
        inverse.put("minParticipants", 10);
        inverse.put("maxParticipants", 5);
        assertThat(statut(envoyer(admin(), "POST", "/activites", inverse))).isEqualTo(HttpStatus.BAD_REQUEST);
        var moisSeul = corps(lettres(), "RENCONTRE");
        moisSeul.put("monthFrom", 2);
        assertThat(statut(envoyer(admin(), "POST", "/activites", moisSeul))).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void onModifieLesFrequencesEtOnTraceLHistorique() {
        String code = lettres();
        var a = envoyer(admin(), "POST", "/activites", corps(code, "RENCONTRE")).getBody();
        String id = (String) a.get("id");
        // on garde MO (une seule tranche, modifiée), on retire CO, on ajoute CS
        var nouveau = corps(code, "RENCONTRE");
        nouveau.put("label", "Libellé modifié");
        nouveau.put("profiles", List.of(
                Map.of("profile", "MO", "frequencies", List.of(Map.of("count", 6, "period", "SEMESTRE", "per", "PLANTATION", "maxAreaHa", 25))),
                Map.of("profile", "CS", "frequencies", List.of())));
        var r = envoyer(admin(), "PUT", "/activites/" + id, nouveau);
        assertThat(statut(r)).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).containsEntry("label", "Libellé modifié");
        assertThat(profils(r.getBody()).stream().map(p -> p.get("profile")).toList()).containsExactlyInAnyOrder("MO", "CS");
        var mo = profils(r.getBody()).stream().filter(p -> "MO".equals(p.get("profile"))).findFirst().orElseThrow();
        assertThat((List<?>) mo.get("frequencies")).hasSize(1);
        // les lignes retirées sont supprimées logiquement (historique), pas effacées
        assertThat(jdbc.sql("select count(*) from planning.activite_profil where activite_id = :id and supprime_le is not null")
                .param("id", UUID.fromString(id)).query(Long.class).single()).isEqualTo(2L);        // CO et la 2e tranche de MO
        // le code ne change pas, une activité inconnue est introuvable
        var autreCode = corps(lettres(), "RENCONTRE");
        assertThat(statut(envoyer(admin(), "PUT", "/activites/" + id, autreCode))).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(statut(envoyer(admin(), "PUT", "/activites/" + UUID.randomUUID(), corps(code, "RENCONTRE")))).isEqualTo(HttpStatus.NOT_FOUND);
        // modification tracée dans l'audit
        assertThat(jdbc.sql("select count(*) from audit.evenement where table_nom = 'activite' and objet_id = :id")
                .param("id", id).query(Long.class).single()).isPositive();
    }

    @Test
    void uneActiviteDejaPlanifieeEstProtegeeEtUneActiviteDesactiveeNEstPlusProposee() {
        Secteur s = secteur("AC", "04");
        String code = lettres();
        var a = envoyer(admin(), "POST", "/activites", corps(code, "RENCONTRE")).getBody();
        String id = (String) a.get("id");
        // le moniteur planifie cette activité
        assertThat(statut(put(s.moC(), "/plannings/mon-planning/lignes", List.of(ligne(code, 5, 12, s, true, null))))).isEqualTo(HttpStatus.OK);

        var autreCategorie = corps(code, "FORMATION");
        assertThat(statut(envoyer(admin(), "PUT", "/activites/" + id, autreCategorie))).as("catégorie figée").isEqualTo(HttpStatus.CONFLICT);
        var autreDuree = corps(code, "RENCONTRE");
        autreDuree.put("durationDays", 3);
        assertThat(statut(envoyer(admin(), "PUT", "/activites/" + id, autreDuree))).as("durée figée").isEqualTo(HttpStatus.CONFLICT);
        var sansMo = corps(code, "RENCONTRE");
        sansMo.put("profiles", List.of(Map.of("profile", "CO", "frequencies", List.of())));
        assertThat(statut(envoyer(admin(), "PUT", "/activites/" + id, sansMo))).as("profil retiré alors qu'il planifie").isEqualTo(HttpStatus.CONFLICT);
        // le libellé et les fréquences restent modifiables
        var libelle = corps(code, "RENCONTRE");
        libelle.put("label", "Renommée");
        assertThat(statut(envoyer(admin(), "PUT", "/activites/" + id, libelle))).isEqualTo(HttpStatus.OK);

        // désactivée : plus proposée au planning, le planning existant reste lisible
        var inactive = corps(code, "RENCONTRE");
        inactive.put("active", false);
        assertThat(statut(envoyer(admin(), "PUT", "/activites/" + id, inactive))).isEqualTo(HttpStatus.OK);
        var proposees = s.moC().get().uri("/plannings/activites").retrieve().toEntity(LISTE).getBody();
        assertThat(proposees.stream().map(x -> x.get("code")).toList()).doesNotContain(code);
        assertThat(statut(put(s.moC(), "/plannings/mon-planning/lignes", List.of(ligne(code, 6, 12, s, true, null))))).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(s.moC().get().uri("/plannings/mon-planning?annee=" + ANNEE).retrieve().toEntity(OBJET).getBody().get("lines")).asList().hasSize(1);
    }
}
