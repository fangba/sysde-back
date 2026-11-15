package ci.sysde.realisation;

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
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

/** Activités spontanées (découverte du verger) : réalisation sans ligne de planning, avec plantation et position. */
class ActiviteSpontaneeIntegrationTest extends AbstractIntegrationTest {

    private static final OffsetDateTime DEBUT = OffsetDateTime.of(ANNEE, 4, 2, 9, 0, 0, 0, ZoneOffset.UTC);

    private Map<String, Object> spontanee() {
        var m = new HashMap<String, Object>();
        m.put("activityId", activite("DECOUVERTE_VERGER").toString());
        m.put("plantationRef", "PLT-" + UUID.randomUUID().toString().substring(0, 6));
        m.put("status", "TERMINEE");
        m.put("startedAt", DEBUT.toString());
        m.put("endedAt", DEBUT.plusMinutes(40).toString());
        m.put("latitude", 5.345678);
        m.put("longitude", -4.012345);
        m.put("accuracyM", 6.5);
        return m;
    }

    private ResponseEntity<Map<String, Object>> envoyer(RestClient c, UUID id, Map<String, Object> corps) {
        return c.put().uri("/realisations/" + id).contentType(MediaType.APPLICATION_JSON).body(corps).retrieve().toEntity(OBJET);
    }

    @Test
    void unMoniteurEnvoieUneDecouverteDuVergerSansLigneDePlanning() {
        Secteur s = secteur("SP", "02");
        UUID id = UUID.randomUUID();
        var corps = spontanee();
        var r = envoyer(s.moC(), id, corps);
        assertThat(statut(r)).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).containsEntry("activityId", corps.get("activityId")).containsEntry("plantationRef", corps.get("plantationRef"))
                .containsEntry("status", "TERMINEE").doesNotContainKeys("lineId", "planId");
        long version = ((Number) r.getBody().get("version")).longValue();

        // rejeu : même résultat, version inchangée
        var rejeu = envoyer(s.moC(), id, corps);
        assertThat(statut(rejeu)).isEqualTo(HttpStatus.OK);
        assertThat(((Number) rejeu.getBody().get("version")).longValue()).isEqualTo(version);
        // correction : version suivante ; version attendue périmée : conflit
        var corrige = new HashMap<>(corps);
        corrige.put("observations", "Parcelle clôturée");
        corrige.put("expectedVersion", version);
        var maj = envoyer(s.moC(), id, corrige);
        assertThat(statut(maj)).isEqualTo(HttpStatus.OK);
        assertThat(((Number) maj.getBody().get("version")).longValue()).isEqualTo(version + 1);
        var perimee = new HashMap<>(corps);
        perimee.put("observations", "Autre version");
        perimee.put("expectedVersion", version);
        assertThat(statut(envoyer(s.moC(), id, perimee))).isEqualTo(HttpStatus.CONFLICT);

        // lecture : par le moniteur, son chef secteur ; la liste porte la plantation et filtre par activité
        assertThat(statut(s.moC().get().uri("/realisations/" + id).retrieve().toEntity(OBJET))).isEqualTo(HttpStatus.OK);
        assertThat(statut(s.cs().get().uri("/realisations/" + id).retrieve().toEntity(OBJET))).isEqualTo(HttpStatus.OK);
        var liste = s.moC().get().uri("/realisations?activityId=" + corps.get("activityId")).retrieve().toEntity(OBJET).getBody();
        @SuppressWarnings("unchecked")
        var contenu = (List<Map<String, Object>>) liste.get("content");
        assertThat(contenu).anySatisfy(x -> assertThat(x).containsEntry("id", id.toString()).containsEntry("plantationRef", corps.get("plantationRef")));

        // l'amorçage mobile liste l'envoi (sans ligne), pour qu'un appareil qui se réinstalle sache quoi renvoyer
        @SuppressWarnings("unchecked")
        var etats = (List<Map<String, Object>>) s.moC().get().uri("/mobile/amorcage?annee=" + ANNEE).retrieve().toEntity(OBJET).getBody().get("realisations");
        assertThat(etats).anySatisfy(e -> assertThat(e).containsEntry("id", id.toString()).doesNotContainKey("lineId"));
    }

    @Test
    void lesActivitesSpontaneesSontControleesCommeLesAutres() {
        Secteur s = secteur("SP", "02");
        UUID id = UUID.randomUUID();
        // ni ligne ni activité
        var rien = spontanee();
        rien.remove("activityId");
        assertThat(statut(envoyer(s.moC(), id, rien))).isEqualTo(HttpStatus.BAD_REQUEST);
        // une activité qui se planifie ne se réalise pas sans ligne
        var planifiee = spontanee();
        planifiee.put("activityId", activite("PERFECTIONNEMENT_IN_SITU").toString());
        var refus = envoyer(s.moC(), id, planifiee);
        assertThat(statut(refus)).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat((String) refus.getBody().get("message")).contains("se planifie");
        // plantation et position obligatoires
        var sansPlantation = spontanee();
        sansPlantation.remove("plantationRef");
        assertThat(statut(envoyer(s.moC(), id, sansPlantation))).isEqualTo(HttpStatus.BAD_REQUEST);
        var sansPosition = spontanee();
        sansPosition.remove("latitude");
        sansPosition.remove("longitude");
        assertThat(statut(envoyer(s.moC(), id, sansPosition))).isEqualTo(HttpStatus.BAD_REQUEST);
        // un profil qui ne réalise pas cette activité (CO) : 400
        RestClient co = clientCompte(s.co(), "CO", s.equipe().operateur());
        var parCo = envoyer(co, UUID.randomUUID(), spontanee());
        assertThat(statut(parCo)).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat((String) parCo.getBody().get("message")).contains("votre profil");
        // cette activité n'a ni liste de présence, ni évaluation, ni rapport
        var avecPresence = spontanee();
        avecPresence.put("attendance", List.of(Map.of("participantId", UUID.randomUUID().toString(), "day", DEBUT.toLocalDate().toString(), "present", true)));
        assertThat(statut(envoyer(s.moC(), id, avecPresence))).isEqualTo(HttpStatus.BAD_REQUEST);
        // avec une ligne de planning : pas de plantation propre (la ligne la porte), et l'activité doit être celle de la ligne
        var plan = put(s.moC(), "/plannings/mon-planning/lignes", List.of(ligne("PERFECTIONNEMENT_IN_SITU", 3, 10, s, true, null))).getBody();
        @SuppressWarnings("unchecked")
        UUID ligne = UUID.fromString((String) ((List<Map<String, Object>>) plan.get("lines")).get(0).get("id"));
        var deLigne = new HashMap<String, Object>();
        deLigne.put("lineId", ligne.toString());
        deLigne.put("status", "EN_COURS");
        deLigne.put("startedAt", DEBUT.toString());
        deLigne.put("plantationRef", "X");
        assertThat(statut(envoyer(s.moC(), UUID.randomUUID(), deLigne))).as("plantation + ligne").isEqualTo(HttpStatus.BAD_REQUEST);
        deLigne.remove("plantationRef");
        deLigne.put("activityId", activite("DECOUVERTE_VERGER").toString());
        assertThat(statut(envoyer(s.moC(), UUID.randomUUID(), deLigne))).as("mauvaise activité pour la ligne").isEqualTo(HttpStatus.BAD_REQUEST);
        deLigne.put("activityId", activite("PERFECTIONNEMENT_IN_SITU").toString());
        assertThat(statut(envoyer(s.moC(), UUID.randomUUID(), deLigne))).as("activité de la ligne, facultative").isEqualTo(HttpStatus.OK);
    }

    @Test
    void uneActiviteDejaRealiseeSansLigneNeChangePlusDeNature() {
        Secteur s = secteur("SP", "02");
        assertThat(statut(envoyer(s.moC(), UUID.randomUUID(), spontanee()))).isEqualTo(HttpStatus.OK);
        var activite = admin().get().uri("/activites").retrieve().toEntity(LISTE).getBody().stream()
                .filter(a -> "DECOUVERTE_VERGER".equals(a.get("code"))).findFirst().orElseThrow();
        var corps = new HashMap<String, Object>(activite);
        corps.put("planned", true);                                   // la rendre planifiable changerait sa nature
        corps.remove("id");
        var r = admin().put().uri("/activites/" + activite.get("id")).contentType(MediaType.APPLICATION_JSON).body(corps).retrieve().toEntity(OBJET);
        assertThat(statut(r)).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void laBaseRefuseUneRealisationSansLigneQuiNePorteNiPlantationNiPosition() {
        Secteur s = secteur("SP", "02");
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> tx.executeWithoutResult(st -> {
            jdbc.sql("select set_config('app.compte_id', :a, true)").param("a", s.mo().toString()).query().singleValue();
            jdbc.sql("insert into planning.realisation (activite_id, compte_id, statut, debut_le) values (:a, :c, 'EN_COURS', now())")
                    .param("a", activite("DECOUVERTE_VERGER")).param("c", s.mo()).update();
        }))).isNotNull().hasMessageContaining("ck_realisation_spontanee");
    }
}
