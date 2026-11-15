package ci.sysde.realisation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
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

/** Réalisations : envoi rejouable, règles de contenu, périmètre de lecture, participants. */
class RealisationIntegrationTest extends AbstractIntegrationTest {

    private static final OffsetDateTime DEBUT = OffsetDateTime.of(ANNEE, 3, 10, 8, 30, 0, 0, ZoneOffset.UTC);

    /** Un moniteur avec une ligne de perfectionnement (1 jour) et une ligne de formation initiale (formateur) dans son planning. */
    private record Terrain(Secteur s, UUID ligne, UUID fs, RestClient fsC, UUID ligneFormation) {
    }

    @SuppressWarnings("unchecked")
    private UUID premiereLigne(Map<String, Object> plan, UUID activite) {
        return ((List<Map<String, Object>>) plan.get("lines")).stream().filter(l -> activite.toString().equals(l.get("activityId")))
                .map(l -> UUID.fromString((String) l.get("id"))).findFirst().orElseThrow();
    }

    private Terrain terrain(String code) {
        Secteur s = secteur("RE" + code, code);
        var plan = put(s.moC(), "/plannings/mon-planning/lignes", List.of(ligne("PERFECTIONNEMENT_IN_SITU", 3, 10, s, true, null))).getBody();
        UUID ligne = premiereLigne(plan, activite("PERFECTIONNEMENT_IN_SITU"));
        // un formateur de saignée (FS) : formation initiale de 20 jours, 20 apprentis au plus
        UUID fs = creerCompte(s.equipe().cs(), s.equipe().operateur(), "FS");
        affecter(fs, s.equipe().operateur(), s.equipe().secteur(), s.equipe().lots().get(0), null);
        RestClient fsC = clientCompte(fs, "FS", s.equipe().operateur());
        var l = ligne("FORMATION_INITIALE", 6, 1, s, false, null);
        l.put("endDate", LocalDate.of(ANNEE, 6, 20).toString());
        var planFs = put(fsC, "/plannings/mon-planning/lignes", List.of(l)).getBody();
        return new Terrain(s, ligne, fs, fsC, premiereLigne(planFs, activite("FORMATION_INITIALE")));
    }

    private UUID participant(RestClient c, String nom) {
        return UUID.fromString((String) c.post().uri("/participants").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("lastName", nom, "firstName", "Test")).retrieve().toEntity(OBJET).getBody().get("id"));
    }

    private Map<String, Object> envoi(UUID ligne, String statut) {
        var m = new HashMap<String, Object>();
        m.put("lineId", ligne.toString());
        m.put("status", statut);
        m.put("startedAt", DEBUT.toString());
        if ("TERMINEE".equals(statut)) {
            m.put("endedAt", DEBUT.plusHours(3).toString());
        }
        return m;
    }

    private ResponseEntity<Map<String, Object>> envoyer(RestClient c, UUID id, Map<String, Object> corps) {
        return c.put().uri("/realisations/" + id).contentType(MediaType.APPLICATION_JSON).body(corps).retrieve().toEntity(OBJET);
    }

    @Test
    void lEnvoiEstCompletRejouableEtVersionne() {
        Terrain t = terrain("02");
        UUID id = UUID.randomUUID();                                   // identifiant choisi par l'appareil
        UUID saigneur = participant(t.s().moC(), "Saigneur-A");

        var corps = envoi(t.ligne(), "EN_COURS");
        corps.put("latitude", 5.345678);
        corps.put("longitude", -4.012345);
        corps.put("accuracyM", 8.5);
        corps.put("observations", "Départ du verger");
        corps.put("attendance", List.of(Map.of("participantId", saigneur.toString(), "day", LocalDate.of(ANNEE, 3, 10).toString(), "present", true)));
        var cree = envoyer(t.s().moC(), id, corps);
        assertThat(cree.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cree.getBody()).containsEntry("status", "EN_COURS").containsEntry("version", 0).containsEntry("lineId", t.ligne().toString())
                .containsEntry("accountId", t.s().mo().toString()).containsKey("receivedAt").doesNotContainKey("endedAt");
        assertThat(cree.getBody().get("latitude").toString()).startsWith("5.3456");

        // rejeu d'un envoi dont la réponse s'est perdue : même contenu, aucun changement (version inchangée), même avec une version périmée
        var rejeu = new HashMap<>(corps);
        rejeu.put("expectedVersion", 7);
        var rejoue = envoyer(t.s().moC(), id, rejeu);
        assertThat(rejoue.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rejoue.getBody()).containsEntry("version", 0);

        // fin de l'activité : l'état complet remplace le précédent, la version monte
        var fin = envoi(t.ligne(), "TERMINEE");
        fin.put("observations", "RAS");
        fin.put("attendance", List.of(Map.of("participantId", saigneur.toString(), "day", LocalDate.of(ANNEE, 3, 10).toString(), "present", false)));
        fin.put("evaluations", List.of(Map.of("participantId", saigneur.toString(), "criterion", "PROFONDEUR", "level", "MOYEN")));
        fin.put("appreciations", List.of(Map.of("participantId", saigneur.toString(), "verdict", "NON_CONFORME", "comment", "À revoir")));
        fin.put("report", "Saigneur à suivre le mois prochain.");
        fin.put("expectedVersion", 0);
        var termine = envoyer(t.s().moC(), id, fin);
        assertThat(termine.getBody()).containsEntry("status", "TERMINEE").containsEntry("version", 1).containsKey("endedAt")
                .containsEntry("report", "Saigneur à suivre le mois prochain.");
        @SuppressWarnings("unchecked") var presences = (List<Map<String, Object>>) termine.getBody().get("attendance");
        assertThat(presences).hasSize(1);
        assertThat(presences.get(0)).containsEntry("present", false);                       // remplacée, pas ajoutée
        assertThat((List<?>) termine.getBody().get("evaluations")).hasSize(1);
        assertThat((List<?>) termine.getBody().get("appreciations")).hasSize(1);

        // conflit : une version périmée avec un contenu différent est refusée ; une terminée ne repasse pas en cours
        var perime = new HashMap<>(fin);
        perime.put("observations", "Autre texte");
        perime.put("expectedVersion", 0);
        var conflit = envoyer(t.s().moC(), id, perime);
        assertThat(conflit.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflit.getBody()).containsEntry("code", "CONFLIT_DE_VERSION");
        var retour = envoi(t.ligne(), "EN_COURS");
        retour.put("expectedVersion", 1);
        assertThat(envoyer(t.s().moC(), id, retour).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        // le rapport retiré est vidé
        var sansRapport = new HashMap<>(fin);
        sansRapport.remove("report");
        sansRapport.put("expectedVersion", 1);
        assertThat(envoyer(t.s().moC(), id, sansRapport).getBody()).doesNotContainKey("report");

        // une seule réalisation par ligne
        assertThat(envoyer(t.s().moC(), UUID.randomUUID(), envoi(t.ligne(), "EN_COURS")).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void lesReglesDeContenuSontAppliquees() {
        Terrain t = terrain("03");
        var mo = t.s().moC();
        UUID p = participant(mo, "Regles-A");
        String jour = LocalDate.of(ANNEE, 3, 10).toString();
        // terminée sans heure de fin ; latitude sans longitude ; fin avant début
        var sansFin = envoi(t.ligne(), "TERMINEE");
        sansFin.remove("endedAt");
        assertThat(envoyer(mo, UUID.randomUUID(), sansFin).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        var seuleLat = envoi(t.ligne(), "EN_COURS");
        seuleLat.put("latitude", 5.3);
        assertThat(envoyer(mo, UUID.randomUUID(), seuleLat).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        var finAvant = envoi(t.ligne(), "TERMINEE");
        finAvant.put("endedAt", DEBUT.minusHours(1).toString());
        assertThat(envoyer(mo, UUID.randomUUID(), finAvant).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        var latFolle = envoi(t.ligne(), "EN_COURS");
        latFolle.put("latitude", 123);
        latFolle.put("longitude", 5);
        assertThat(envoyer(mo, UUID.randomUUID(), latFolle).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // présence hors de la période de la ligne, en double ; participant inconnu ; critère inconnu
        var horsPeriode = envoi(t.ligne(), "EN_COURS");
        horsPeriode.put("attendance", List.of(Map.of("participantId", p.toString(), "day", LocalDate.of(ANNEE, 3, 11).toString(), "present", true)));
        assertThat(envoyer(mo, UUID.randomUUID(), horsPeriode).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        var doublon = envoi(t.ligne(), "EN_COURS");
        doublon.put("attendance", List.of(Map.of("participantId", p.toString(), "day", jour, "present", true), Map.of("participantId", p.toString(), "day", jour, "present", false)));
        assertThat(envoyer(mo, UUID.randomUUID(), doublon).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        var inconnu = envoi(t.ligne(), "EN_COURS");
        inconnu.put("attendance", List.of(Map.of("participantId", UUID.randomUUID().toString(), "day", jour, "present", true)));
        assertThat(envoyer(mo, UUID.randomUUID(), inconnu).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        var critere = envoi(t.ligne(), "EN_COURS");
        critere.put("evaluations", List.of(Map.of("participantId", p.toString(), "criterion", "BIDON", "level", "BON")));
        assertThat(envoyer(mo, UUID.randomUUID(), critere).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        var niveau = envoi(t.ligne(), "EN_COURS");
        niveau.put("evaluations", List.of(Map.of("participantId", p.toString(), "criterion", "PROFONDEUR", "level", "EXCELLENT")));
        assertThat(envoyer(mo, UUID.randomUUID(), niveau).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        // un participant d'un autre opérateur n'existe pas pour moi
        Secteur autre = secteur("REX", "04");
        UUID etranger = participant(autre.moC(), "Autre-Operateur");
        var autreOp = envoi(t.ligne(), "EN_COURS");
        autreOp.put("attendance", List.of(Map.of("participantId", etranger.toString(), "day", jour, "present", true)));
        assertThat(envoyer(mo, UUID.randomUUID(), autreOp).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // la formation initiale accueille 20 apprentis au plus, sur une période de 20 jours
        List<Map<String, Object>> vingtEtUn = new java.util.ArrayList<>();
        for (int i = 0; i < 21; i++) {
            vingtEtUn.add(Map.of("participantId", participant(t.fsC(), "Apprenti-" + i).toString(), "day", LocalDate.of(ANNEE, 6, 2).toString(), "present", true));
        }
        var trop = envoi(t.ligneFormation(), "EN_COURS");
        trop.put("attendance", vingtEtUn);
        assertThat((String) envoyer(t.fsC(), UUID.randomUUID(), trop).getBody().get("message")).contains("20 participants au plus");
        var vingt = envoi(t.ligneFormation(), "EN_COURS");
        vingt.put("attendance", vingtEtUn.subList(0, 20));
        assertThat(envoyer(t.fsC(), UUID.randomUUID(), vingt).getStatusCode()).isEqualTo(HttpStatus.OK);

        // un moniteur ne livre ni la ligne d'un autre, ni une ligne inconnue ; il n'y a pas de rapport pour une activité qui n'en a pas
        assertThat(envoyer(mo, UUID.randomUUID(), envoi(t.ligneFormation(), "EN_COURS")).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(envoyer(mo, UUID.randomUUID(), envoi(UUID.randomUUID(), "EN_COURS")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(envoyer(t.s().seC(), UUID.randomUUID(), envoi(t.ligne(), "EN_COURS")).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);      // SE : pas REALISER_ACTIVITE
    }

    @Test
    void chacunVoitSesRealisationsEtLeChefSecteurCellesDeSonSecteur() {
        Terrain t = terrain("02");
        Secteur autre = secteur("REY", "04");
        UUID id = UUID.randomUUID();
        envoyer(t.s().moC(), id, envoi(t.ligne(), "EN_COURS"));

        // le titulaire, son chef secteur, sa secrétaire et le chef de département voient ; un autre secteur et un collègue non
        assertThat(t.s().moC().get().uri("/realisations/" + id).retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(t.s().cs().get().uri("/realisations/" + id).retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(t.s().seC().get().uri("/realisations/" + id).retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(clientCompte(t.s().equipe().cd(), "CD", t.s().equipe().operateur()).get().uri("/realisations/" + id).retrieve().toBodilessEntity().getStatusCode())
                .isEqualTo(HttpStatus.OK);
        // le superviseur voit tout opérateur technique ; l'administrateur n'a pas « consulter les activités » par défaut (matrice du cahier des charges)
        UUID su = creerCompte(adminId, fircaId, "SU");
        assertThat(clientCompte(su, "SU", fircaId).get().uri("/realisations/" + id).retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(admin().get().uri("/realisations/" + id).retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(autre.cs().get().uri("/realisations/" + id).retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(clientCompte(t.s().co(), "CO", t.s().equipe().operateur()).get().uri("/realisations/" + id).retrieve().toBodilessEntity().getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        // liste paginée et filtrée : le chef secteur voit la réalisation du moniteur, pas l'autre secteur
        var page = t.s().cs().get().uri("/realisations?accountId=" + t.s().mo() + "&status=EN_COURS&size=10").retrieve().toEntity(OBJET).getBody();
        @SuppressWarnings("unchecked") var contenu = (List<Map<String, Object>>) page.get("content");
        assertThat(contenu).extracting(r -> r.get("id")).contains(id.toString());
        assertThat(contenu.get(0)).doesNotContainKey("attendance");                          // résumé : sans le détail
        @SuppressWarnings("unchecked") var chezLAutre = (List<Map<String, Object>>) autre.cs().get().uri("/realisations?accountId=" + t.s().mo())
                .retrieve().toEntity(OBJET).getBody().get("content");
        assertThat(chezLAutre).isEmpty();
        assertThat(t.s().moC().get().uri("/realisations?status=TERMINEE").retrieve().toEntity(OBJET).getBody()).containsEntry("totalElements", 0);

        // critères d'évaluation (référentiel pour l'écran)
        var criteres = t.s().moC().get().uri("/plannings/criteres-evaluation").retrieve().toEntity(LISTE).getBody();
        assertThat(criteres).extracting(c -> c.get("code")).containsExactly("CONSOMMATION", "PROFONDEUR", "PENTE", "BLESSURES", "PROPRETE");
    }

    @Test
    void lesParticipantsSeCreentHorsLigneSansDoublonEtRestentDansLOperateur() {
        Secteur s = secteur("REP", "03");
        Secteur autre = secteur("REQ", "04");
        UUID id = UUID.randomUUID();                                                       // créé sur l'appareil
        var corps = Map.of("id", id.toString(), "lastName", "KOUADIO", "firstName", "Yao", "village", "Koffikro", "phone", "0707070707");
        var premier = s.moC().post().uri("/participants").contentType(MediaType.APPLICATION_JSON).body(corps).retrieve().toEntity(OBJET);
        assertThat(premier.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(premier.getBody()).containsEntry("id", id.toString()).containsEntry("village", "Koffikro");
        // renvoi : pas de doublon
        assertThat(s.moC().post().uri("/participants").contentType(MediaType.APPLICATION_JSON).body(corps).retrieve().toBodilessEntity().getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(jdbc.sql("select count(*) from planning.participant where id = :id").param("id", id).query(Long.class).single()).isEqualTo(1);
        // l'identifiant d'un autre opérateur n'est pas réutilisable
        assertThat(autre.moC().post().uri("/participants").contentType(MediaType.APPLICATION_JSON).body(corps).retrieve().toBodilessEntity().getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        // recherche (sans casse), cloisonnement par opérateur, modification
        var trouve = s.cs().get().uri("/participants?q=kouad").retrieve().toEntity(OBJET).getBody();
        assertThat((List<?>) trouve.get("content")).hasSize(1);
        assertThat((List<?>) autre.cs().get().uri("/participants?q=kouad").retrieve().toEntity(OBJET).getBody().get("content")).isEmpty();
        var modifie = s.moC().put().uri("/participants/" + id).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("lastName", "KOUADIO", "firstName", "Yao Jean", "village", "Koffikro-2")).retrieve().toEntity(OBJET);
        assertThat(modifie.getBody()).containsEntry("firstName", "Yao Jean").doesNotContainKey("phone");
        assertThat(autre.moC().put().uri("/participants/" + id).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("lastName", "X", "firstName", "Y")).retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        // la secrétaire consulte mais n'écrit pas
        assertThat(s.seC().get().uri("/participants").retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(s.seC().post().uri("/participants").contentType(MediaType.APPLICATION_JSON).body(Map.of("lastName", "A", "firstName", "B"))
                .retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
