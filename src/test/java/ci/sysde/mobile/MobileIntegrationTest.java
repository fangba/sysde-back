package ci.sysde.mobile;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import ci.sysde.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/** API mobile : paquet de départ, envoi seul d'un compte désactivé, suivi des appareils. */
class MobileIntegrationTest extends AbstractIntegrationTest {

    private static final OffsetDateTime DEBUT = OffsetDateTime.of(ANNEE, 3, 10, 8, 30, 0, 0, ZoneOffset.UTC);

    private RestClient anonyme() {
        return RestClient.builder().baseUrl("http://localhost:" + port).defaultStatusHandler(s -> true, (q, p) -> { }).build();
    }

    @SuppressWarnings("unchecked")
    private UUID premiereLigne(Map<String, Object> plan) {
        return UUID.fromString((String) ((List<Map<String, Object>>) plan.get("lines")).get(0).get("id"));
    }

    private HttpStatus envoyer(RestClient c, UUID id, UUID ligne) {
        return statut(c.put().uri("/realisations/" + id).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("lineId", ligne.toString(), "status", "EN_COURS", "startedAt", DEBUT.toString()))
                .retrieve().toEntity(OBJET));
    }

    @Test
    @SuppressWarnings("unchecked")
    void lePaquetDeDepartContientLePlanLeReferentielEtLEtatDesEnvois() {
        Secteur s = secteur("MB", "01");
        var plan = put(s.moC(), "/plannings/mon-planning/lignes", List.of(ligne("PERFECTIONNEMENT_IN_SITU", 3, 10, s, true, null))).getBody();
        UUID ligne = premiereLigne(plan);
        UUID id = UUID.randomUUID();
        assertThat(envoyer(s.moC(), id, ligne)).isEqualTo(HttpStatus.OK);

        var r = s.moC().get().uri("/mobile/amorcage?annee=" + ANNEE).retrieve().toEntity(OBJET);
        assertThat(statut(r)).isEqualTo(HttpStatus.OK);
        var b = r.getBody();
        assertThat((Map<String, Object>) b.get("account")).containsEntry("profile", "MO");
        assertThat((List<?>) ((Map<String, Object>) b.get("plan")).get("lines")).hasSize(1);
        assertThat((List<?>) b.get("activities")).isNotEmpty();
        assertThat((List<?>) b.get("criteria")).hasSize(5);
        assertThat(((List<Map<String, Object>>) b.get("lots")).stream().map(l -> l.get("id")).toList())
                .contains(s.equipe().lots().get(0).toString());
        assertThat(((List<Map<String, Object>>) b.get("sections")).stream().map(x -> x.get("id")).toList())
                .contains(s.section().toString());
        // l'état des envois : de quoi renvoyer avec la bonne version attendue
        var etats = (List<Map<String, Object>>) b.get("realisations");
        assertThat(etats).hasSize(1);
        assertThat(etats.get(0)).containsEntry("id", id.toString()).containsEntry("lineId", ligne.toString())
                .containsEntry("status", "EN_COURS");
        assertThat(b).containsKey("generatedAt");

        // un profil sans planning reçoit le référentiel sans plan
        var ad = admin().get().uri("/mobile/amorcage").retrieve().toEntity(OBJET);
        assertThat(statut(ad)).isEqualTo(HttpStatus.OK);
        assertThat(ad.getBody()).doesNotContainKey("plan");
        // sans jeton : 401
        assertThat(statut(anonyme().get().uri("/mobile/amorcage").retrieve().toEntity(OBJET))).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void unCompteDesactiveNePeutPlusQuEnvoyerPuisPlusRienApresLeDelai() {
        Secteur s = secteur("MD", "01");
        // un moniteur réel (connexion et rafraîchissement), dans sa propre section, avec une ligne dans son planning
        Session co = creer(s.cs(), "Envoi" + UUID.randomUUID().toString().substring(0, 6), "MO", s.equipe().operateur(), null);
        UUID section = sectionDe(s.equipe(), "Sud-" + UUID.randomUUID().toString().substring(0, 8));
        affecter(co.id(), s.equipe().operateur(), s.equipe().secteur(), s.equipe().lots().get(0), section);
        var l = ligne("PERFECTIONNEMENT_IN_SITU", 3, 10, s, false, null);
        l.put("sectionId", section.toString());
        var plan = put(co.client(), "/plannings/mon-planning/lignes", List.of(l)).getBody();
        UUID ligne = premiereLigne(plan);

        // désactivé par son chef secteur
        assertThat(statut(s.cs().post().uri("/comptes/" + co.id() + "/desactivation").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("reason", "Départ")).retrieve().toEntity(OBJET))).isEqualTo(HttpStatus.OK);

        // il peut envoyer ce qu'il a saisi, créer un participant et signaler son appareil…
        assertThat(envoyer(co.client(), UUID.randomUUID(), ligne)).isEqualTo(HttpStatus.OK);
        assertThat(statut(co.client().post().uri("/participants").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("lastName", "Hors-ligne", "firstName", "Test")).retrieve().toEntity(OBJET))).isEqualTo(HttpStatus.CREATED);
        assertThat(statut(co.client().post().uri("/mobile/synchronisation").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("deviceId", UUID.randomUUID().toString(), "pending", 0)).retrieve().toEntity(OBJET)))
                .isEqualTo(HttpStatus.NO_CONTENT);
        // …mais ni lire, ni télécharger, ni planifier
        assertThat(statut(co.client().get().uri("/mobile/amorcage").retrieve().toEntity(OBJET))).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(statut(co.client().get().uri("/participants").retrieve().toEntity(OBJET))).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(statut(co.client().get().uri("/auth/moi").retrieve().toEntity(OBJET))).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(statut(put(co.client(), "/plannings/mon-planning/lignes", List.of()))).isEqualTo(HttpStatus.FORBIDDEN);

        // le rafraîchissement reste possible : le mobile hors ligne depuis longtemps peut encore envoyer
        var refresh = anonyme().post().uri("/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("refreshToken", co.refresh())).retrieve().toEntity(OBJET);
        assertThat(statut(refresh)).isEqualTo(HttpStatus.OK);
        RestClient renouvele = RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultHeader("Authorization", "Bearer " + refresh.getBody().get("token"))
                .defaultStatusHandler(st -> true, (q, p) -> { }).build();
        assertThat(envoyer(renouvele, UUID.randomUUID(), ligne)).as("une ligne ne reçoit qu'une réalisation").isEqualTo(HttpStatus.CONFLICT);

        // délai écoulé : plus rien
        jdbc.sql("update iam.compte set desactive_le = now() - interval '31 days' where id = :id").param("id", co.id()).update();
        etats.invalider();
        assertThat(envoyer(renouvele, UUID.randomUUID(), ligne)).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(statut(anonyme().post().uri("/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("refreshToken", refresh.getBody().get("refreshToken"))).retrieve().toEntity(OBJET)))
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @SuppressWarnings("unchecked")
    void leChefSecteurVoitLaSynchronisationDeSesCollaborateurs() {
        Secteur s = secteur("MS", "01");
        UUID appareil = UUID.randomUUID();
        // deux signaux du même appareil : une seule ligne, dernier état retenu
        for (int attente : new int[] {7, 3}) {
            assertThat(statut(s.moC().post().uri("/mobile/synchronisation").contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("deviceId", appareil.toString(), "model", "Tab A8", "system", "Android 14", "appVersion", "1.0.2",
                            "pending", attente)).retrieve().toEntity(OBJET))).isEqualTo(HttpStatus.NO_CONTENT);
        }
        assertThat(jdbc.sql("select count(*) from iam.appareil where compte_id = :c").param("c", s.mo()).query(Long.class).single()).isEqualTo(1L);
        // contrôles d'entrée
        assertThat(statut(s.moC().post().uri("/mobile/synchronisation").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("deviceId", appareil.toString(), "pending", -1)).retrieve().toEntity(OBJET))).isEqualTo(HttpStatus.BAD_REQUEST);

        var r = s.cs().get().uri("/reporting/secteur/synchronisation").retrieve().toEntity(LISTE);
        assertThat(statut(r)).isEqualTo(HttpStatus.OK);
        var mo = r.getBody().stream().filter(x -> s.mo().toString().equals(x.get("accountId"))).findFirst().orElseThrow();
        assertThat(mo).containsEntry("devices", 1).containsEntry("pending", 3).containsKey("lastSyncAt");
        // un collaborateur sans appareil apparaît aussi, sans date de synchronisation
        var co = r.getBody().stream().filter(x -> s.co().toString().equals(x.get("accountId"))).findFirst().orElseThrow();
        assertThat(co).containsEntry("devices", 0).doesNotContainKey("lastSyncAt");
        // réservé au chef secteur
        assertThat(statut(admin().get().uri("/reporting/secteur/synchronisation").retrieve().toEntity(OBJET))).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
