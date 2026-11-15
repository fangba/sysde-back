package ci.sysde.referentiel;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import ci.sysde.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/** Membres d'un groupe de vulgarisation : ajout, renvoi, départs avec historique, remplacement, périmètre. */
class MembresGroupeIntegrationTest extends AbstractIntegrationTest {

    private record Cadre(Secteur s, String groupe) {
    }

    private Cadre cadre(String code) {
        Secteur s = secteur("MG" + code, code);
        var r = s.cs().post().uri("/groupes").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("lotId", s.equipe().lots().get(0).toString(), "sectionId", s.section().toString(),
                        "name", "Groupe " + UUID.randomUUID().toString().substring(0, 8), "monitorId", s.mo().toString()))
                .retrieve().toEntity(OBJET);
        assertThat(statut(r)).isEqualTo(HttpStatus.CREATED);
        return new Cadre(s, (String) r.getBody().get("id"));
    }

    private String participant(RestClient c, String nom) {
        return (String) c.post().uri("/participants").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("lastName", nom + UUID.randomUUID().toString().substring(0, 4), "firstName", "Test")).retrieve().toEntity(OBJET)
                .getBody().get("id");
    }

    private org.springframework.http.ResponseEntity<Map<String, Object>> ajouter(RestClient c, String groupe, String participant, LocalDate depuis) {
        var corps = new java.util.HashMap<String, Object>();
        corps.put("participantId", participant);
        if (depuis != null) {
            corps.put("since", depuis.toString());
        }
        return c.post().uri("/groupes/" + groupe + "/membres").contentType(MediaType.APPLICATION_JSON).body(corps).retrieve().toEntity(OBJET);
    }

    private List<Map<String, Object>> membres(RestClient c, String groupe, boolean historique) {
        var r = c.get().uri("/groupes/" + groupe + "/membres?historique=" + historique).retrieve().toEntity(LISTE);
        assertThat(statut(r)).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    @SuppressWarnings("unchecked")
    private List<String> ids(List<Map<String, Object>> membres) {
        return membres.stream().map(m -> (String) ((Map<String, Object>) m.get("participant")).get("id")).toList();
    }

    private HttpStatus retirer(RestClient c, String groupe, String participant) {
        return statut(c.delete().uri("/groupes/" + groupe + "/membres/" + participant).retrieve().toEntity(OBJET));
    }

    @Test
    void leChefSecteurEtLeMoniteurDuGroupeAjoutentDesMembresLesAutresNePeuventQueLire() {
        Cadre c = cadre("04");
        String a = participant(c.s().moC(), "Membre"), b = participant(c.s().moC(), "Membre");
        var r = ajouter(c.s().cs(), c.groupe(), a, null);
        assertThat(statut(r)).isEqualTo(HttpStatus.CREATED);
        assertThat(r.getBody()).containsKey("participant").containsEntry("since", LocalDate.now().toString()).doesNotContainKey("until");
        // renvoi : sans effet, 200
        assertThat(statut(ajouter(c.s().cs(), c.groupe(), a, null))).isEqualTo(HttpStatus.OK);
        // le moniteur responsable du groupe ajoute aussi
        assertThat(statut(ajouter(c.s().moC(), c.groupe(), b, null))).isEqualTo(HttpStatus.CREATED);
        assertThat(ids(membres(c.s().cs(), c.groupe(), false))).containsExactlyInAnyOrder(a, b);
        // un autre technicien du secteur (CO) lit mais n'écrit pas
        Secteur memeSecteur = c.s();
        RestClient co = clientCompte(memeSecteur.co(), "CO", memeSecteur.equipe().operateur());
        assertThat(ids(membres(co, c.groupe(), false))).contains(a);
        assertThat(statut(ajouter(co, c.groupe(), participant(co, "Autre"), null))).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(retirer(co, c.groupe(), a)).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void lesParticipantsDOperateurEtLeGroupeHorsPerimetreSontRefuses() {
        Cadre c = cadre("04");
        Secteur autre = secteur("MH", "03");
        String etranger = participant(autre.moC(), "Etranger");                                    // autre opérateur
        assertThat(statut(ajouter(c.s().cs(), c.groupe(), etranger, null))).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(statut(ajouter(c.s().cs(), c.groupe(), UUID.randomUUID().toString(), null))).isEqualTo(HttpStatus.BAD_REQUEST);
        // le chef secteur d'un autre secteur ne voit pas ce groupe : 404, qu'il lise ou écrive
        assertThat(statut(autre.cs().get().uri("/groupes/" + c.groupe() + "/membres").retrieve().toEntity(OBJET))).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(statut(ajouter(autre.cs(), c.groupe(), participant(c.s().moC(), "Autre"), null))).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(statut(c.s().cs().get().uri("/groupes/" + UUID.randomUUID() + "/membres").retrieve().toEntity(OBJET))).isEqualTo(HttpStatus.NOT_FOUND);
        // date d'entrée dans le futur : 400
        assertThat(statut(ajouter(c.s().cs(), c.groupe(), participant(c.s().moC(), "Futur"), LocalDate.now().plusDays(1)))).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void unDepartResteDansLHistoriqueSaufSiLEntreeDateDAujourdHui() {
        Cadre c = cadre("04");
        String recent = participant(c.s().moC(), "Recent"), ancien = participant(c.s().moC(), "Ancien");
        assertThat(statut(ajouter(c.s().cs(), c.groupe(), recent, null))).isEqualTo(HttpStatus.CREATED);
        assertThat(statut(ajouter(c.s().cs(), c.groupe(), ancien, LocalDate.now().minusDays(40)))).isEqualTo(HttpStatus.CREATED);

        // ajouté aujourd'hui puis retiré : saisie corrigée, rien dans l'historique
        assertThat(retirer(c.s().cs(), c.groupe(), recent)).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(ids(membres(c.s().cs(), c.groupe(), true))).doesNotContain(recent);
        // membre depuis longtemps : le départ est daté et conservé
        assertThat(retirer(c.s().cs(), c.groupe(), ancien)).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(ids(membres(c.s().cs(), c.groupe(), false))).isEmpty();
        var histo = membres(c.s().cs(), c.groupe(), true);
        assertThat(histo).hasSize(1);
        assertThat(histo.get(0)).containsEntry("until", LocalDate.now().toString()).containsEntry("since", LocalDate.now().minusDays(40).toString());
        // il peut revenir : nouvelle période, l'ancienne est gardée
        assertThat(statut(ajouter(c.s().cs(), c.groupe(), ancien, null))).isEqualTo(HttpStatus.CREATED);
        assertThat(membres(c.s().cs(), c.groupe(), true)).hasSize(2);
        assertThat(ids(membres(c.s().cs(), c.groupe(), false))).containsExactly(ancien);
        // retirer quelqu'un qui n'est pas membre : 404
        assertThat(retirer(c.s().cs(), c.groupe(), recent)).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void remplacerLaListeFaitEntrerLesNouveauxEtSortirLesAbsentsToutOuRien() {
        Cadre c = cadre("04");
        String a = participant(c.s().moC(), "Liste"), b = participant(c.s().moC(), "Liste"), d = participant(c.s().moC(), "Liste");
        var premier = c.s().cs().put().uri("/groupes/" + c.groupe() + "/membres").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("participantIds", List.of(a, b))).retrieve().toEntity(LISTE);
        assertThat(statut(premier)).isEqualTo(HttpStatus.OK);
        assertThat(ids(premier.getBody())).containsExactlyInAnyOrder(a, b);
        var second = c.s().cs().put().uri("/groupes/" + c.groupe() + "/membres").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("participantIds", List.of(b, d, d))).retrieve().toEntity(LISTE);       // doublon toléré
        assertThat(ids(second.getBody())).containsExactlyInAnyOrder(b, d);
        // un participant invalide dans la liste : 400, rien ne change
        var refus = c.s().cs().put().uri("/groupes/" + c.groupe() + "/membres").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("participantIds", List.of(a, UUID.randomUUID().toString()))).retrieve().toEntity(OBJET);
        assertThat(statut(refus)).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ids(membres(c.s().cs(), c.groupe(), false))).containsExactlyInAnyOrder(b, d);
        // liste vide : tout le monde part
        c.s().cs().put().uri("/groupes/" + c.groupe() + "/membres").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("participantIds", List.of())).retrieve().toBodilessEntity();
        assertThat(membres(c.s().cs(), c.groupe(), false)).isEmpty();
    }
}
