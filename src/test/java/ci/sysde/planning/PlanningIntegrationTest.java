package ci.sysde.planning;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import ci.sysde.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/** Planning annuel de bout en bout : saisie, soumission, décisions du chef secteur, secrétaire, notifications. */
class PlanningIntegrationTest extends AbstractIntegrationTest {

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> liste(Map<String, Object> plan, String cle) {
        return (List<Map<String, Object>>) plan.get(cle);
    }

    @SuppressWarnings("unchecked")
    private static String idCompte(Map<String, Object> plan) {
        return (String) ((Map<String, Object>) plan.get("account")).get("id");
    }

    @SuppressWarnings("unchecked")
    private static String profil(Map<String, Object> plan) {
        return (String) ((Map<String, Object>) plan.get("account")).get("profile");
    }


    // ------------------------------------------------------------------ saisie

    @Test
    void leTitulaireSaisitSonPlanningEtLesRèglesDeLigneSontAppliquées() {
        Secteur s = secteur("PL1", "02");
        // seules les activités planifiables de son profil sont proposées
        var activites = s.moC().get().uri("/plannings/activites").retrieve().toEntity(LISTE).getBody();
        assertThat(activites).extracting(a -> a.get("code")).contains("PERFECTIONNEMENT_IN_SITU", "VISITE_PLANTATION", "GROUPE_VULGARISATION")
                .doesNotContain("DECOUVERTE_VERGER", "FORMATION_INITIALE", "CONFERENCE_THEMATIQUE");

        var vide = s.moC().get().uri("/plannings/mon-planning?annee=" + ANNEE).retrieve().toEntity(OBJET).getBody();
        assertThat(vide).containsEntry("status", "BROUILLON").containsEntry("year", ANNEE);
        assertThat(vide.get("id")).isNotNull();
        assertThat(liste(vide, "lines")).isEmpty();

        // la saisie remplace toutes les lignes
        var deux = put(s.moC(), "/plannings/mon-planning/lignes", List.of(
                ligne("PERFECTIONNEMENT_IN_SITU", 3, 10, s, true, null), ligne("VISITE_PLANTATION", 4, 8, s, true, "PLANT-001")));
        assertThat(statut(deux)).isEqualTo(HttpStatus.OK);
        assertThat(liste(deux.getBody(), "lines")).hasSize(2);
        assertThat(liste(deux.getBody(), "lines").get(0)).containsKey("sectionName").containsEntry("endDate", LocalDate.of(ANNEE, 3, 10).toString());
        var une = put(s.moC(), "/plannings/mon-planning/lignes", List.of(ligne("VISITE_PLANTATION", 5, 6, s, true, "PLANT-002")));
        assertThat(liste(une.getBody(), "lines")).hasSize(1);
        assertThat(une.getBody()).containsEntry("editedBy", "SELF");

        // règles : visite en un jour et avec sa plantation, année du planning, activité propre au profil, lot du secteur, section du lot
        var visite2j = ligne("VISITE_PLANTATION", 5, 6, s, true, "P");
        visite2j.put("endDate", LocalDate.of(ANNEE, 5, 7).toString());
        assertThat(statut(put(s.moC(), "/plannings/mon-planning/lignes", List.of(visite2j)))).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(statut(put(s.moC(), "/plannings/mon-planning/lignes", List.of(ligne("VISITE_PLANTATION", 5, 6, s, true, null))))).isEqualTo(HttpStatus.BAD_REQUEST);
        var autreAnnee = ligne("PERFECTIONNEMENT_IN_SITU", 3, 10, s, true, null);
        autreAnnee.put("date", LocalDate.of(ANNEE + 1, 3, 10).toString());
        assertThat(statut(put(s.moC(), "/plannings/mon-planning/lignes", List.of(autreAnnee)))).isEqualTo(HttpStatus.BAD_REQUEST);
        var finAvant = ligne("PERFECTIONNEMENT_IN_SITU", 3, 10, s, true, null);
        finAvant.put("endDate", LocalDate.of(ANNEE, 3, 9).toString());
        assertThat(statut(put(s.moC(), "/plannings/mon-planning/lignes", List.of(finAvant)))).isEqualTo(HttpStatus.BAD_REQUEST);
        var horsProfil = put(s.moC(), "/plannings/mon-planning/lignes", List.of(ligne("FORMATION_INITIALE", 3, 10, s, false, null)));
        assertThat(horsProfil.getStatusCode().value()).isEqualTo(422);                               // règle de la base
        assertThat((String) horsProfil.getBody().get("message")).contains("n'est pas réalisée par le profil MO");
        assertThat(statut(put(s.moC(), "/plannings/mon-planning/lignes", List.of(ligne("DECOUVERTE_VERGER", 3, 10, s, false, "P-1"))))).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        var activiteInconnue = ligne("PERFECTIONNEMENT_IN_SITU", 3, 10, s, true, null);
        activiteInconnue.put("activityId", UUID.randomUUID().toString());
        assertThat(statut(put(s.moC(), "/plannings/mon-planning/lignes", List.of(activiteInconnue)))).isEqualTo(HttpStatus.BAD_REQUEST);
        var lotEtranger = ligne("PERFECTIONNEMENT_IN_SITU", 3, 10, s, false, null);
        lotEtranger.put("lotId", jdbc.sql("select id from referentiel.lot where numero = 1").query(UUID.class).single().toString());
        assertThat(statut(put(s.moC(), "/plannings/mon-planning/lignes", List.of(lotEtranger)))).isEqualTo(HttpStatus.BAD_REQUEST);
        var mauvaiseSection = ligne("PERFECTIONNEMENT_IN_SITU", 3, 10, s, false, null);
        mauvaiseSection.put("lotId", s.equipe().lots().get(1).toString());
        mauvaiseSection.put("sectionId", s.section().toString());
        assertThat(statut(put(s.moC(), "/plannings/mon-planning/lignes", List.of(mauvaiseSection)))).isEqualTo(HttpStatus.BAD_REQUEST);
        // une erreur n'abîme pas le planning existant
        assertThat(liste(s.moC().get().uri("/plannings/mon-planning?annee=" + ANNEE).retrieve().toEntity(OBJET).getBody(), "lines")).hasSize(1);

        // pas de planning pour un profil qui n'en a pas (secrétaire) ; une année absurde est refusée
        assertThat(s.seC().get().uri("/plannings/mon-planning").retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(s.moC().get().uri("/plannings/mon-planning?annee=1900").retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ------------------------------------------------------------------ soumission et décisions

    @Test
    void soumissionRejetValidationEtNotifications() {
        Secteur s = secteur("PL2", "03");
        Secteur autre = secteur("PL3", "04");

        assertThat(statut(post(s.moC(), "/plannings/mon-planning/soumission"))).isEqualTo(HttpStatus.BAD_REQUEST);          // vide
        put(s.moC(), "/plannings/mon-planning/lignes", List.of(ligne("PERFECTIONNEMENT_IN_SITU", 3, 10, s, true, null)));
        var soumis = post(s.moC(), "/plannings/mon-planning/soumission");
        assertThat(soumis.getBody()).containsEntry("status", "SOUMIS");
        String planId = (String) soumis.getBody().get("id");
        // soumis : ni modification ni nouvelle soumission
        assertThat(statut(put(s.moC(), "/plannings/mon-planning/lignes", List.of()))).isEqualTo(HttpStatus.CONFLICT);
        assertThat(statut(post(s.moC(), "/plannings/mon-planning/soumission"))).isEqualTo(HttpStatus.CONFLICT);

        // le chef secteur voit toute son équipe (plans vides compris, sans la secrétaire) ; la secrétaire voit aussi le chef secteur
        var equipe = s.cs().get().uri("/plannings/equipe?annee=" + ANNEE).retrieve().toEntity(LISTE).getBody();
        // (le secteur est partagé par plusieurs tests : on vérifie la présence et l'absence de comptes précis)
        assertThat(equipe).extracting(p -> idCompte(p)).contains(s.mo().toString(), s.co().toString(), s.es().toString())
                .doesNotContain(s.se().toString(), s.equipe().cs().toString());
        assertThat(equipe).extracting(p -> profil(p)).doesNotContain("SE", "CS");
        var plan = equipe.stream().filter(p -> s.mo().toString().equals(idCompte(p))).findFirst().orElseThrow();
        assertThat(plan).containsEntry("status", "SOUMIS");
        var plan_co = equipe.stream().filter(p -> s.co().toString().equals(idCompte(p))).findFirst().orElseThrow();
        assertThat(plan_co).containsEntry("status", "BROUILLON").doesNotContainKey("id");                           // jamais enregistré
        assertThat(s.seC().get().uri("/plannings/equipe?annee=" + ANNEE).retrieve().toEntity(LISTE).getBody())
                .extracting(p -> idCompte(p)).contains(s.equipe().cs().toString(), s.mo().toString()).doesNotContain(s.se().toString());
        assertThat(s.moC().get().uri("/plannings/equipe").retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // rejet : motif obligatoire ; l'autre secteur et le titulaire ne décident pas
        assertThat(statut(post(s.cs(), "/plannings/" + planId + "/rejet"))).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(autre.cs().post().uri("/plannings/" + planId + "/validation?annee=" + ANNEE).contentType(MediaType.APPLICATION_JSON).body(Map.of())
                .retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(statut(post(s.moC(), "/plannings/" + planId + "/validation"))).isEqualTo(HttpStatus.FORBIDDEN);
        var rejete = s.cs().post().uri("/plannings/" + planId + "/rejet").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("reason", "Dates trop rapprochées")).retrieve().toEntity(OBJET);
        assertThat(rejete.getBody()).containsEntry("status", "BROUILLON");
        @SuppressWarnings("unchecked") var rejet = (Map<String, Object>) rejete.getBody().get("rejection");
        assertThat(rejet).containsEntry("reason", "Dates trop rapprochées");
        // le titulaire est notifié, peut corriger puis resoumettre ; le rejet n'est plus affiché une fois resoumis
        var notifs = s.moC().get().uri("/notifications").retrieve().toEntity(LISTE).getBody();
        assertThat(notifs).anyMatch(n -> "Planning rejeté".equals(n.get("title")) && ((String) n.get("message")).contains("Dates trop rapprochées") && Boolean.FALSE.equals(n.get("read")));
        assertThat(statut(put(s.moC(), "/plannings/mon-planning/lignes", List.of(ligne("PERFECTIONNEMENT_IN_SITU", 3, 24, s, true, null))))).isEqualTo(HttpStatus.OK);
        var resoumis = post(s.moC(), "/plannings/mon-planning/soumission");
        assertThat(resoumis.getBody()).containsEntry("status", "SOUMIS").doesNotContainKey("rejection");

        // validation : le planning ne bouge plus
        assertThat(statut(post(s.cs(), "/plannings/" + planId + "/validation"))).isEqualTo(HttpStatus.OK);
        assertThat(statut(post(s.cs(), "/plannings/" + planId + "/validation"))).isEqualTo(HttpStatus.CONFLICT);   // plus soumis
        assertThat(statut(put(s.moC(), "/plannings/mon-planning/lignes", List.of()))).isEqualTo(HttpStatus.CONFLICT);
        assertThat(s.moC().get().uri("/notifications").retrieve().toEntity(LISTE).getBody()).extracting(n -> n.get("title")).contains("Planning validé");

        // le chef secteur : son planning est validé d'office
        put(s.cs(), "/plannings/mon-planning/lignes", List.of(ligne("CONFERENCE_THEMATIQUE", 6, 5, s, false, null)));
        assertThat(post(s.cs(), "/plannings/mon-planning/soumission").getBody()).containsEntry("status", "VALIDE");
    }

    @Test
    void leControleDesFrequencesEstInformatifEtAccessibleAuTitulaireEtAuChefSecteur() {
        Secteur s = secteur("PL1", "02");
        var mo = s.moC();
        put(mo, "/plannings/mon-planning/lignes", List.of(ligne("PERFECTIONNEMENT_IN_SITU", 3, 10, s, true, null)));
        var controle = mo.get().uri("/plannings/mon-planning/controle?annee=" + ANNEE).retrieve().toEntity(LISTE).getBody();
        var perf = controle.stream().filter(c -> activite("PERFECTIONNEMENT_IN_SITU").toString().equals(c.get("activityId"))).findFirst().orElseThrow();
        // 3 par mois attendues : une seule séance en mars, rien les autres mois
        assertThat(perf).containsEntry("ok", false).containsEntry("actual", 1).containsEntry("expected", 36);
        assertThat(liste(perf, "issues")).isNotEmpty();
        // la soumission n'est pas bloquée (mode AVERTIR par défaut)
        assertThat(statut(post(mo, "/plannings/mon-planning/soumission"))).isEqualTo(HttpStatus.OK);

        // le chef secteur contrôle le plan de son collaborateur
        var equipe = s.cs().get().uri("/plannings/equipe?annee=" + ANNEE).retrieve().toEntity(LISTE).getBody();
        String planId = (String) equipe.stream().filter(p -> idCompte(p).equals(s.mo().toString())).findFirst().orElseThrow().get("id");
        assertThat(statut(s.cs().get().uri("/plannings/" + planId + "/controle").retrieve().toEntity(LISTE))).isEqualTo(HttpStatus.OK);
        // un moniteur n'a pas accès au contrôle d'un plan d'équipe
        assertThat(statut(mo.get().uri("/plannings/" + planId + "/controle").retrieve().toEntity(OBJET))).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void lesActivitesSeSoumettentUneAUneEtSeVerrouillent() {
        Secteur s = secteur("PL5", "04");
        var mo = s.moC();
        assertThat(statut(post(mo, "/plannings/mon-planning/activites/" + activite("PERFECTIONNEMENT_IN_SITU") + "/soumission"))).isEqualTo(HttpStatus.BAD_REQUEST);

        var perf = ligne("PERFECTIONNEMENT_IN_SITU", 3, 10, s, true, null);
        var visite = ligne("VISITE_PLANTATION", 4, 8, s, true, "PLANT-9");
        put(mo, "/plannings/mon-planning/lignes", List.of(perf, visite));
        var soumise = post(mo, "/plannings/mon-planning/activites/" + activite("PERFECTIONNEMENT_IN_SITU") + "/soumission");
        assertThat(liste(soumise.getBody(), "submissions")).extracting(x -> x.get("status")).containsExactly("SOUMIS");
        assertThat(statut(post(mo, "/plannings/mon-planning/activites/" + activite("PERFECTIONNEMENT_IN_SITU") + "/soumission"))).isEqualTo(HttpStatus.CONFLICT);

        // ses dates sont verrouillées ; les autres activités restent modifiables
        var decalee = new HashMap<>(perf);
        decalee.put("date", LocalDate.of(ANNEE, 3, 11).toString());
        assertThat(statut(put(mo, "/plannings/mon-planning/lignes", List.of(decalee, visite)))).isEqualTo(HttpStatus.CONFLICT);
        assertThat(statut(put(mo, "/plannings/mon-planning/lignes", List.of(visite)))).isEqualTo(HttpStatus.CONFLICT);        // on ne peut pas la retirer
        var autreVisite = ligne("VISITE_PLANTATION", 7, 8, s, true, "PLANT-10");
        var ok = put(mo, "/plannings/mon-planning/lignes", List.of(perf, autreVisite));
        assertThat(statut(ok)).isEqualTo(HttpStatus.OK);
        assertThat(liste(ok.getBody(), "lines")).hasSize(2);

        // le chef secteur rejette l'activité avec un motif : ses dates sont de nouveau modifiables
        String planId = (String) ok.getBody().get("id");
        String activiteUrl = "/plannings/" + planId + "/activites/" + activite("PERFECTIONNEMENT_IN_SITU");
        assertThat(s.cs().post().uri(activiteUrl + "/rejet").contentType(MediaType.APPLICATION_JSON).body(Map.of()).retrieve().toBodilessEntity().getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        var rejetee = s.cs().post().uri(activiteUrl + "/rejet").contentType(MediaType.APPLICATION_JSON).body(Map.of("reason", "Mauvaise semaine")).retrieve().toEntity(OBJET);
        var sub = liste(rejetee.getBody(), "submissions").get(0);
        assertThat(sub).containsEntry("status", "REJETE");
        @SuppressWarnings("unchecked") var motif = (Map<String, Object>) sub.get("rejection");
        assertThat(motif).containsEntry("reason", "Mauvaise semaine");
        assertThat(s.moC().get().uri("/notifications").retrieve().toEntity(LISTE).getBody()).extracting(n -> n.get("title")).contains("Activité rejetée");
        assertThat(statut(put(mo, "/plannings/mon-planning/lignes", List.of(decalee, autreVisite)))).isEqualTo(HttpStatus.OK);
        // resoumise, puis validée : verrouillée définitivement
        post(mo, "/plannings/mon-planning/activites/" + activite("PERFECTIONNEMENT_IN_SITU") + "/soumission");
        assertThat(statut(s.cs().post().uri(activiteUrl + "/validation").contentType(MediaType.APPLICATION_JSON).body(Map.of()).retrieve().toEntity(OBJET)))
                .isEqualTo(HttpStatus.OK);
        assertThat(statut(s.cs().post().uri(activiteUrl + "/validation").contentType(MediaType.APPLICATION_JSON).body(Map.of()).retrieve().toEntity(OBJET)))
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(statut(put(mo, "/plannings/mon-planning/lignes", List.of(perf, autreVisite)))).isEqualTo(HttpStatus.CONFLICT);
        assertThat(s.moC().get().uri("/notifications").retrieve().toEntity(LISTE).getBody()).extracting(n -> n.get("title")).contains("Activité validée");
        // le chef secteur ne soumet pas d'activité seule
        assertThat(statut(post(s.cs(), "/plannings/mon-planning/activites/" + activite("CONFERENCE_THEMATIQUE") + "/soumission"))).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ------------------------------------------------------------------ secrétaire

    @Test
    void laSecretaireSaisitEtSoumetALaPlaceDuCollaborateur() {
        Secteur s = secteur("PL6", "02");
        Secteur autre = secteur("PL7", "04");
        String chemin = "/plannings/collaborateurs/" + s.mo();

        assertThat(s.seC().get().uri(chemin + "?annee=" + ANNEE).retrieve().toEntity(OBJET).getBody()).containsEntry("status", "BROUILLON");
        var saisi = put(s.seC(), chemin + "/lignes", List.of(ligne("PERFECTIONNEMENT_IN_SITU", 3, 10, s, true, null)));
        assertThat(saisi.getBody()).containsEntry("editedBy", "SECRETARY");
        assertThat(liste(saisi.getBody(), "lines")).hasSize(1);
        assertThat(s.moC().get().uri("/plannings/mon-planning?annee=" + ANNEE).retrieve().toEntity(OBJET).getBody()).containsEntry("editedBy", "SECRETARY");
        var soumis = post(s.seC(), chemin + "/soumission");
        assertThat(soumis.getBody()).containsEntry("status", "SOUMIS");
        assertThat(s.moC().get().uri("/notifications").retrieve().toEntity(LISTE).getBody()).extracting(n -> n.get("title")).contains("Planning soumis");

        // chef secteur : soumis par la secrétaire, validé d'office
        String cs = "/plannings/collaborateurs/" + s.equipe().cs();
        put(s.seC(), cs + "/lignes", List.of(ligne("CONFERENCE_THEMATIQUE", 6, 5, s, false, null)));
        assertThat(post(s.seC(), cs + "/soumission").getBody()).containsEntry("status", "VALIDE");

        // elle ne saisit ni pour une consœur, ni pour un autre secteur ; un moniteur ne saisit pas pour un autre
        assertThat(s.seC().get().uri("/plannings/collaborateurs/" + s.se() + "?annee=" + ANNEE).retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(autre.seC().get().uri(chemin + "?annee=" + ANNEE).retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(statut(put(s.moC(), "/plannings/collaborateurs/" + s.co() + "/lignes", List.of()))).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(statut(put(s.cs(), chemin + "/lignes", List.of()))).isEqualTo(HttpStatus.FORBIDDEN);               // le CS non plus : c'est la secrétaire
        // un compte désactivé n'est plus un collaborateur
        s.cs().post().uri("/comptes/" + s.co() + "/desactivation").contentType(MediaType.APPLICATION_JSON).body(Map.of()).retrieve().toBodilessEntity();
        assertThat(s.seC().get().uri("/plannings/collaborateurs/" + s.co() + "?annee=" + ANNEE).retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ------------------------------------------------------------------ notifications

    @Test
    void lesNotificationsNeSontLuesQueParLeurDestinataire() {
        Secteur s = secteur("PL8", "04");
        put(s.moC(), "/plannings/mon-planning/lignes", List.of(ligne("PERFECTIONNEMENT_IN_SITU", 3, 10, s, true, null)));
        String planId = (String) post(s.moC(), "/plannings/mon-planning/soumission").getBody().get("id");
        s.cs().post().uri("/plannings/" + planId + "/rejet").contentType(MediaType.APPLICATION_JSON).body(Map.of("reason", "Non")).retrieve().toBodilessEntity();
        s.cs().post().uri("/plannings/" + planId + "/validation").contentType(MediaType.APPLICATION_JSON).body(Map.of()).retrieve().toBodilessEntity();   // 409 : pas de notification

        var liste = s.moC().get().uri("/notifications").retrieve().toEntity(LISTE).getBody();
        assertThat(liste).hasSize(1);
        assertThat(liste.get(0)).containsEntry("tone", "warning").containsEntry("read", false);
        String id = (String) liste.get(0).get("id");
        // personne d'autre ne les voit ni ne les marque
        assertThat(s.cs().get().uri("/notifications").retrieve().toEntity(LISTE).getBody()).isEmpty();
        assertThat(s.cs().post().uri("/notifications/" + id + "/lue").retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(s.moC().post().uri("/notifications/" + id + "/lue").retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(s.moC().get().uri("/notifications").retrieve().toEntity(LISTE).getBody().get(0)).containsEntry("read", true);
        assertThat(s.moC().post().uri("/notifications/lues").retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(s.moC().get().uri("/notifications").retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
