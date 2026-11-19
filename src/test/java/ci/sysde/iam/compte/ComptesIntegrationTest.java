package ci.sysde.iam.compte;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import ci.sysde.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

/** Comptes de bout en bout : création par la hiérarchie, connexion avec le mot de passe temporaire, états, profil, matricule. */
class ComptesIntegrationTest extends AbstractIntegrationTest {

    @Test
    void laHierarchieCreeLesComptesEtLeMotDePasseTemporaireOuvreUneSession() {
        UUID op = UUID.fromString(operateurTechnique("HIE"));
        var admin = admin();
        var r = admin.post().uri("/comptes").contentType(MediaType.APPLICATION_JSON).body(corps("Kone", "CD", op, null))
                .retrieve().toEntity(OBJET);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        @SuppressWarnings("unchecked") var compte = (Map<String, Object>) r.getBody().get("account");
        assertThat((String) compte.get("matricule")).matches("HIE-[A-Z]{2}-[0-9]{5}");
        assertThat((String) compte.get("reference")).isEqualTo(compte.get("matricule"));
        assertThat(compte).containsEntry("status", "ACTIF").containsEntry("mustChangePassword", true);
        assertThat((String) r.getBody().get("temporaryPassword")).hasSize(12);
        assertThat(compte).doesNotContainKey("password").doesNotContainKey("passwordHash");

        // le mot de passe temporaire permet de se connecter, avec l'obligation de le changer
        var login = RestClient.builder().baseUrl("http://localhost:" + port).defaultStatusHandler(s -> true, (q, p) -> { }).build()
                .post().uri("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("matricule", compte.get("matricule"), "password", r.getBody().get("temporaryPassword")))
                .retrieve().toEntity(OBJET);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(login.getBody()).containsEntry("mustChangePassword", true);
    }

    @Test
    void laHierarchieDeCreationEtLesNumerosHistoriquesSontRespectes() {
        Chaine c = chaine("NUM");
        // un CS crée un moniteur avec son numéro historique
        Session mo = creer(c.cs().client(), "Moniteur", "MO", c.operateur(), "00348");
        assertThat(mo.matricule()).isEqualTo("NUM-MO-00348");
        // numéro déjà pris : refusé
        var doublon = c.cs().client().post().uri("/comptes").contentType(MediaType.APPLICATION_JSON)
                .body(corps("Autre", "CO", c.operateur(), "00348")).retrieve().toEntity(OBJET);
        assertThat(doublon.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        // un CS ne crée pas un CS, un CD ne crée pas un MO : la base refuse (hiérarchie)
        var cs = c.cs().client().post().uri("/comptes").contentType(MediaType.APPLICATION_JSON)
                .body(corps("Intrus", "CS", c.operateur(), null)).retrieve().toEntity(OBJET);
        assertThat(cs.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        var mo2 = c.cd().client().post().uri("/comptes").contentType(MediaType.APPLICATION_JSON)
                .body(corps("Intrus2", "MO", c.operateur(), null)).retrieve().toEntity(OBJET);
        assertThat(mo2.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        // un moniteur n'a pas la permission de créer
        assertThat(mo.client().post().uri("/comptes").contentType(MediaType.APPLICATION_JSON)
                .body(corps("Intrus3", "CO", c.operateur(), null)).retrieve().toBodilessEntity().getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        // requête invalide : profil mal formé, e-mail déjà pris
        assertThat(admin().post().uri("/comptes").contentType(MediaType.APPLICATION_JSON)
                .body(corps("Mal", "cd", c.operateur(), null)).retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        var memeMail = c.cs().client().post().uri("/comptes").contentType(MediaType.APPLICATION_JSON)
                .body(corps("Moniteur", "CO", c.operateur(), null)).retrieve().toEntity(OBJET);
        assertThat(memeMail.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat((String) memeMail.getBody().get("message")).contains("e-mail");
    }

    @Test
    void lAnnuaireEstPagineRechercheEtBorneAuPerimetre() {
        Chaine a = chaine("ANA");
        Chaine b = chaine("ANB");
        creer(a.cs().client(), "Traore", "CF", a.operateur(), null);
        creer(a.cs().client(), "Diallo", "CO", a.operateur(), null);

        // le CD de A voit les comptes de rang inférieur de son opérateur (CS, CF, CO), pas lui-même ni l'opérateur B
        var page = a.cd().client().get().uri("/comptes?size=10&sort=lastName,asc").retrieve().toEntity(OBJET).getBody();
        @SuppressWarnings("unchecked") var contenu = (List<Map<String, Object>>) page.get("content");
        assertThat(contenu).extracting(x -> x.get("lastName")).containsExactly("CsANA", "Diallo", "Traore");
        assertThat(contenu).extracting(x -> x.get("operatorId")).containsOnly(a.operateur().toString());
        assertThat(page).containsEntry("totalElements", 3);

        // pagination, recherche (insensible à la casse), filtre par profil
        var p1 = a.cd().client().get().uri("/comptes?size=2&page=1&sort=lastName,asc").retrieve().toEntity(OBJET).getBody();
        assertThat((List<?>) p1.get("content")).hasSize(1);
        assertThat(p1).containsEntry("totalPages", 2);
        var q = a.cd().client().get().uri("/comptes?q=TRAO").retrieve().toEntity(OBJET).getBody();
        assertThat((List<?>) q.get("content")).hasSize(1);
        var parProfil = a.cd().client().get().uri("/comptes?profile=CO").retrieve().toEntity(OBJET).getBody();
        assertThat((List<?>) parProfil.get("content")).hasSize(1);
        // plusieurs profils d'un coup, et une recherche sans accents (« Traoré » se trouve en tapant « traore »)
        var deuxProfils = a.cd().client().get().uri("/comptes?profile=CF,CO").retrieve().toEntity(OBJET).getBody();
        assertThat((List<?>) deuxProfils.get("content")).hasSize(2);
        creer(a.cs().client(), "Kouamé", "MO", a.operateur(), null);
        assertThat((List<?>) a.cd().client().get().uri("/comptes?q=kouame").retrieve().toEntity(OBJET).getBody().get("content")).hasSize(1);
        assertThat((List<?>) a.cd().client().get().uri("/comptes?q=KOUAMÉ").retrieve().toEntity(OBJET).getBody().get("content")).hasSize(1);
        // filtres de colonne : nom et matricule séparément, sans casse ni accents, cumulables
        @SuppressWarnings("unchecked") String matriculeTraore = (String) ((List<Map<String, Object>>) q.get("content")).get(0).get("matricule");
        assertThat((List<?>) a.cd().client().get().uri("/comptes?name=KOUAMÉ").retrieve().toEntity(OBJET).getBody().get("content")).hasSize(1);
        assertThat((List<?>) a.cd().client().get().uri("/comptes?name=traore").retrieve().toEntity(OBJET).getBody().get("content")).hasSize(1);
        assertThat((List<?>) a.cd().client().get().uri("/comptes?matricule=" + matriculeTraore.toLowerCase()).retrieve().toEntity(OBJET).getBody().get("content")).hasSize(1);
        assertThat((List<?>) a.cd().client().get().uri("/comptes?matricule=" + matriculeTraore + "&name=kouame").retrieve().toEntity(OBJET).getBody().get("content")).isEmpty();
        assertThat((List<?>) a.cd().client().get().uri("/comptes?name=%25").retrieve().toEntity(OBJET).getBody().get("content")).isEmpty();
        // la recherche traite % et _ comme du texte
        assertThat((List<?>) a.cd().client().get().uri("/comptes?q=%25").retrieve().toEntity(OBJET).getBody().get("content")).isEmpty();
        assertThat(a.cd().client().get().uri("/comptes?sort=password,asc").retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // un compte d'un autre opérateur est introuvable (pas de fuite), et l'AD voit tout opérateur technique
        var autre = a.cd().client().get().uri("/comptes/" + b.cs().id()).retrieve().toEntity(OBJET);
        assertThat(autre.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(admin().get().uri("/comptes/" + b.cs().id()).retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.OK);
        // un chef secteur voit ses techniciens non affectés, pas ceux d'un autre opérateur
        var csPage = a.cs().client().get().uri("/comptes?size=50").retrieve().toEntity(OBJET).getBody();
        @SuppressWarnings("unchecked") var csContenu = (List<Map<String, Object>>) csPage.get("content");
        assertThat(csContenu).extracting(x -> x.get("lastName")).containsExactlyInAnyOrder("Diallo", "Kouamé", "Traore");
        // sans la permission : 403
        assertThat(creer(a.cs().client(), "Simple", "MO", a.operateur(), null).client().get().uri("/comptes")
                .retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void ModifierLesDonneesDeLaPersonneSeulementDansSonAutorite() {
        Chaine a = chaine("MPA");
        Chaine b = chaine("MPB");
        Session co = creer(a.cs().client(), "Yao", "CO", a.operateur(), null);
        var modif = a.cs().client().put().uri("/comptes/" + co.id()).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("lastName", "Yao-Kouame", "firstName", "Marie", "phone", "07 00 00 00 00", "email", "yao.k@test.local"))
                .retrieve().toEntity(OBJET);
        assertThat(modif.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(modif.getBody()).containsEntry("lastName", "Yao-Kouame").containsEntry("phone", "07 00 00 00 00")
                .containsEntry("matricule", co.matricule());
        // le CS d'un autre opérateur n'a aucune autorité sur ce compte
        assertThat(b.cs().client().put().uri("/comptes/" + co.id()).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("lastName", "Piraté", "firstName", "X")).retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        // un compte de rang égal ou supérieur non plus (un CS ne modifie pas son CD)
        assertThat(a.cs().client().put().uri("/comptes/" + a.cd().id()).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("lastName", "Piraté", "firstName", "X")).retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        // e-mail pris par quelqu'un d'autre
        assertThat(a.cs().client().put().uri("/comptes/" + co.id()).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("lastName", "Yao", "firstName", "Marie", "email", "cs" + "mpa@test.local")).retrieve().toBodilessEntity().getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void desactiverCoupeLesSessionsEtLaReactivationLesRend() throws Exception {
        Chaine c = chaine("DES");
        Session mo = creer(c.cs().client(), "Partant", "MO", c.operateur(), null);
        assertThat(mo.client().get().uri("/auth/moi").retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.OK);

        // on ne se désactive pas soi-même
        assertThat(c.cs().client().post().uri("/comptes/" + c.cs().id() + "/desactivation").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of()).retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        var off = c.cs().client().post().uri("/comptes/" + mo.id() + "/desactivation").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("reason", "Départ de l'entreprise")).retrieve().toEntity(OBJET);
        assertThat(off.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(off.getBody()).containsEntry("status", "DESACTIVE");
        // envoi seul : le jeton d'accès reste reconnu mais ne sert plus qu'à envoyer (403 ailleurs), le rafraîchissement
        // reste possible pendant le délai de synchronisation (30 jours)
        assertThat(mo.client().get().uri("/auth/moi").retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        var refresh = RestClient.builder().baseUrl("http://localhost:" + port).defaultStatusHandler(s -> true, (q, p) -> { }).build()
                .post().uri("/auth/refresh").contentType(MediaType.APPLICATION_JSON).body(Map.of("refreshToken", mo.refresh()))
                .retrieve().toBodilessEntity();
        assertThat(refresh.getStatusCode()).isEqualTo(HttpStatus.OK);
        // le délai écoulé, tout est rejeté : jeton d'accès (401) et rafraîchissement (401), même un jeton neuf
        jdbc.sql("update iam.compte set desactive_le = now() - interval '31 days' where id = :id").param("id", mo.id()).update();
        etats.invalider();
        assertThat(mo.client().get().uri("/auth/moi").retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        var refresh2 = RestClient.builder().baseUrl("http://localhost:" + port).defaultStatusHandler(s -> true, (q, p) -> { }).build()
                .post().uri("/auth/refresh").contentType(MediaType.APPLICATION_JSON).body(Map.of("refreshToken", mo.refresh()))
                .retrieve().toBodilessEntity();
        assertThat(refresh2.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        // la désactivation est tracée avec son motif
        assertThat(jdbc.sql("select count(*) from audit.evenement where table_nom = 'compte' and objet_id = :id and motif = :m")
                .param("id", mo.id().toString()).param("m", "Départ de l'entreprise").query(Long.class).single()).isPositive();
        // déjà désactivé : 409
        assertThat(c.cs().client().post().uri("/comptes/" + mo.id() + "/desactivation").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of()).retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        // un compte d'un autre opérateur ne peut pas être touché
        Chaine autre = chaine("DEX");
        assertThat(autre.cs().client().post().uri("/comptes/" + mo.id() + "/reactivation").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of()).retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // réactivé : une nouvelle connexion fonctionne
        var on = c.cs().client().post().uri("/comptes/" + mo.id() + "/reactivation").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of()).retrieve().toEntity(OBJET);
        assertThat(on.getBody()).containsEntry("status", "ACTIF");
        ouvrir(mo.id(), mo.matricule(), mo.motDePasse());
    }

    @Test
    void unMotDePasseTemporaireNeLaisseQueLeChangementDuMotDePasse() {
        Chaine c = chaine("TMP");
        Session mo = creerTemporaire(c.cs().client(), "Temporaire", "MO", c.operateur(), null);
        // consulter son profil, oui ; le reste est fermé, avec un code que le front reconnaît
        assertThat(mo.client().get().uri("/auth/moi").retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.OK);
        var ferme = mo.client().get().uri("/comptes").retrieve().toEntity(OBJET);
        assertThat(ferme.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(ferme.getBody()).containsEntry("code", "MOT_DE_PASSE_TEMPORAIRE");
        assertThat(mo.client().get().uri("/carte/catalogue").retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        // le changement de mot de passe lève le verrou aussitôt, avec le même jeton
        assertThat(mo.client().post().uri("/auth/mot-de-passe").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("currentPassword", mo.motDePasse(), "newPassword", "Nouveau-mdp-2026!"))
                .retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(mo.client().get().uri("/carte/catalogue").retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void reinitialiserVerrouDeNouveauLeCompteJusquAuChangement() throws Exception {
        Chaine c = chaine("RVT");
        Session co = creer(c.cs().client(), "Reverrou", "CO", c.operateur(), null);
        Thread.sleep(1100);                                   // les jetons portent une date à la seconde
        var reset = c.cs().client().post().uri("/comptes/" + co.id() + "/reinitialisation").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of()).retrieve().toEntity(OBJET);
        Session apres = ouvrir(co.id(), co.matricule(), (String) reset.getBody().get("temporaryPassword"));
        assertThat(apres.client().get().uri("/carte/catalogue").retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void changerSonMotDePasseCoupeLesAutresSessionsEtGardeLaCourante() {
        Chaine c = chaine("MDP");
        Session mo = creer(c.cs().client(), "Mdp", "MO", c.operateur(), null);
        // une seconde session du même compte (autre appareil)
        Session autre = ouvrir(mo.id(), mo.matricule(), mo.motDePasse());
        var nouveau = "Nouveau-mdp-2026!";
        assertThat(mo.client().post().uri("/auth/mot-de-passe").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("currentPassword", mo.motDePasse(), "newPassword", nouveau, "refreshToken", mo.refresh()))
                .retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        var anonyme = RestClient.builder().baseUrl("http://localhost:" + port).defaultStatusHandler(s -> true, (q, p) -> { }).build();
        assertThat(anonyme.post().uri("/auth/refresh").contentType(MediaType.APPLICATION_JSON).body(Map.of("refreshToken", autre.refresh()))
                .retrieve().toBodilessEntity().getStatusCode()).as("l'autre session est coupée").isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(anonyme.post().uri("/auth/refresh").contentType(MediaType.APPLICATION_JSON).body(Map.of("refreshToken", mo.refresh()))
                .retrieve().toBodilessEntity().getStatusCode()).as("la session courante est conservée").isEqualTo(HttpStatus.OK);
    }

    @Test
    void reinitialiserDonneUnNouveauMotDePasseEtCoupeToutesLesSessions() throws Exception {
        Chaine c = chaine("RES");
        Session co = creer(c.cs().client(), "Oublie", "CO", c.operateur(), null);
        assertThat(co.client().get().uri("/auth/moi").retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.OK);
        Thread.sleep(1100);                                   // les jetons portent une date à la seconde

        var reset = c.cs().client().post().uri("/comptes/" + co.id() + "/reinitialisation").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of()).retrieve().toEntity(OBJET);
        assertThat(reset.getStatusCode()).isEqualTo(HttpStatus.OK);
        String nouveau = (String) reset.getBody().get("temporaryPassword");
        assertThat(nouveau).isNotEqualTo(co.motDePasse());
        @SuppressWarnings("unchecked") var compteReset = (Map<String, Object>) reset.getBody().get("account");
        assertThat(compteReset).containsEntry("mustChangePassword", true);

        // l'ancien jeton d'accès et l'ancien jeton de rafraîchissement ne servent plus
        assertThat(co.client().get().uri("/auth/moi").retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(RestClient.builder().baseUrl("http://localhost:" + port).defaultStatusHandler(s -> true, (q, p) -> { }).build()
                .post().uri("/auth/refresh").contentType(MediaType.APPLICATION_JSON).body(Map.of("refreshToken", co.refresh()))
                .retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        // l'ancien mot de passe est refusé, le nouveau accepté ; la nouvelle session fonctionne
        assertThat(RestClient.builder().baseUrl("http://localhost:" + port).defaultStatusHandler(s -> true, (q, p) -> { }).build()
                .post().uri("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("matricule", co.matricule(), "password", co.motDePasse())).retrieve().toBodilessEntity().getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        Session neuve = ouvrir(co.id(), co.matricule(), nouveau);
        assertThat(neuve.client().get().uri("/auth/moi").retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void changerLeProfilExigeUnMotifGardeLHistoriqueEtLeMatriculeSeCorrigeParLAdministrateurSeul() {
        Chaine c = chaine("PRF");
        Session co = creer(c.cs().client(), "Promu", "CO", c.operateur(), "00500");

        // sans motif : refusé par la validation
        assertThat(c.cs().client().put().uri("/comptes/" + co.id() + "/profil").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("profile", "CF")).retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        var promu = c.cs().client().put().uri("/comptes/" + co.id() + "/profil").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("profile", "CF", "reason", "Promotion au contrôle de saignée")).retrieve().toEntity(OBJET);
        assertThat(promu.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(promu.getBody()).containsEntry("profile", "CF").containsEntry("reference", "PRF-CF-00500")
                .containsEntry("matricule", "PRF-CF-00500");                    // le profil fait partie du matricule : il change avec lui
        assertThat(jdbc.sql("select ancien_matricule from iam.alias_matricule where compte_id = :id").param("id", co.id())
                .query(String.class).list()).containsExactly("PRF-CO-00500");   // l'ancien est gardé en alias
        assertThat(connexion("PRF-CO-00500", co.motDePasse())).isEqualTo(HttpStatus.UNAUTHORIZED);   // et n'ouvre plus de session
        assertThat(jdbc.sql("select count(*) from iam.compte_profil where compte_id = :id").param("id", co.id()).query(Long.class).single())
                .isEqualTo(2);                                                  // l'historique est conservé
        // le nouveau profil s'applique dès la requête suivante (relu côté serveur)
        assertThat(co.client().get().uri("/auth/moi").retrieve().toEntity(OBJET).getBody()).containsEntry("profile", "CF");
        // un profil d'organisme n'est pas interchangeable avec un profil d'opérateur ; même profil : 409
        assertThat(c.cs().client().put().uri("/comptes/" + co.id() + "/profil").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("profile", "AD", "reason", "Test")).retrieve().toBodilessEntity().getStatusCode())
                .isIn(HttpStatus.UNPROCESSABLE_CONTENT, HttpStatus.FORBIDDEN);
        assertThat(c.cs().client().put().uri("/comptes/" + co.id() + "/profil").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("profile", "CF", "reason", "Test")).retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // correction du matricule : l'AD seul, avec motif ; l'ancien matricule est refusé à la connexion
        assertThat(c.cs().client().put().uri("/comptes/" + co.id() + "/matricule").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("number", "00501", "reason", "Erreur de saisie")).retrieve().toBodilessEntity().getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        var corrige = admin().put().uri("/comptes/" + co.id() + "/matricule").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("number", "00501", "reason", "Erreur de saisie")).retrieve().toEntity(OBJET);
        assertThat(corrige.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(corrige.getBody()).containsEntry("matricule", "PRF-CF-00501");
        assertThat(jdbc.sql("select ancien_matricule from iam.alias_matricule where compte_id = :id").param("id", co.id())
                .query(String.class).list()).containsExactlyInAnyOrder("PRF-CO-00500", "PRF-CF-00500");
        assertThat(connexion("PRF-CF-00500", co.motDePasse())).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /** Statut HTTP d'une tentative de connexion (sans lever d'exception). */
    private HttpStatus connexion(String matricule, String motDePasse) {
        return (HttpStatus) RestClient.builder().baseUrl("http://localhost:" + port).defaultStatusHandler(s -> true, (q, p) -> { }).build()
                .post().uri("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("matricule", matricule, "password", motDePasse)).retrieve().toBodilessEntity().getStatusCode();
    }

    // ------------------------------------------------------------------ affectations et reprise

    @SuppressWarnings("unchecked")
    private static java.util.List<Map<String, Object>> contenu(Map<String, Object> page) {
        return (java.util.List<Map<String, Object>>) page.get("content");
    }

    private String section(RestClient cs, Equipe e, int lot, String nom) {
        return (String) cs.post().uri("/sections").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("lotId", e.lots().get(lot).toString(), "name", nom)).retrieve().toEntity(OBJET).getBody().get("id");
    }

    private Map<String, Object> territoire(Equipe e, Integer lot, String section) {
        var m = new java.util.HashMap<String, Object>();
        m.put("sectorId", e.secteur().toString());
        if (lot != null) {
            m.put("lotId", e.lots().get(lot).toString());
        }
        if (section != null) {
            m.put("sectionId", section);
        }
        return m;
    }

    @Test
    void leChefSecteurAffecteSesTechniciensEtLeChefDeDepartementSesChefsSecteur() {
        Equipe e = monterEquipe("AFF", "12");
        Equipe autre = monterEquipe("AFX", "13");
        RestClient cs = clientCompte(e.cs(), "CS", e.operateur());
        RestClient cd = clientCompte(e.cd(), "CD", e.operateur());
        String sec = section(cs, e, 0, "Nord");
        Session mo = creer(cs, "MoniteurAFF", "MO", e.operateur(), null);
        Session mo2 = creer(cs, "Moniteur2AFF", "MO", e.operateur(), null);

        // un moniteur : une section ; état ACTIVE (territoire couvert par le contrat)
        var a = cs.post().uri("/comptes/" + mo.id() + "/affectations").contentType(MediaType.APPLICATION_JSON)
                .body(territoire(e, 0, sec)).retrieve().toEntity(OBJET);
        assertThat(a.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(a.getBody()).containsEntry("state", "ACTIVE").containsEntry("sectionName", "Nord").doesNotContainKey("endDate");
        String affectationId = (String) a.getBody().get("id");

        // filtres serveur sur le territoire : secteur, lot, section (sans casse ni accents), cumulables
        var cible = cs.get().uri("/comptes?profile=MO&sectorId=" + e.secteur()).retrieve().toEntity(OBJET).getBody();
        assertThat(contenu(cible)).extracting(c -> c.get("id")).containsExactly(mo.id().toString());
        Object lotNumero = contenu(cible).get(0).get("lotNumber");
        assertThat(contenu(cs.get().uri("/comptes?profile=MO&sectorId=" + e.secteur() + "&lot=" + lotNumero + "&section=nOrD").retrieve().toEntity(OBJET).getBody()))
                .extracting(c -> c.get("id")).containsExactly(mo.id().toString());
        assertThat(contenu(cs.get().uri("/comptes?profile=MO&section=sud").retrieve().toEntity(OBJET).getBody())).isEmpty();
        assertThat(contenu(cs.get().uri("/comptes?profile=MO&lot=" + ((Integer) lotNumero + 1000)).retrieve().toEntity(OBJET).getBody())).isEmpty();
        assertThat(contenu(cs.get().uri("/comptes?profile=MO&sectorId=" + autre.secteur()).retrieve().toEntity(OBJET).getBody())).isEmpty();

        // règles de la base : une section n'a qu'un moniteur ; un moniteur s'affecte à une section, pas à un secteur entier
        var deuxieme = cs.post().uri("/comptes/" + mo2.id() + "/affectations").contentType(MediaType.APPLICATION_JSON)
                .body(territoire(e, 0, sec)).retrieve().toEntity(OBJET);
        assertThat(deuxieme.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat((String) deuxieme.getBody().get("message")).contains("un seul moniteur");
        var parSecteur = cs.post().uri("/comptes/" + mo2.id() + "/affectations").contentType(MediaType.APPLICATION_JSON)
                .body(territoire(e, null, null)).retrieve().toEntity(OBJET);
        assertThat(parSecteur.getStatusCode().value()).isEqualTo(422);

        // un chef secteur n'affecte pas dans le secteur d'un autre ; un moniteur n'affecte personne
        assertThat(cs.post().uri("/comptes/" + mo2.id() + "/affectations").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("sectorId", autre.secteur().toString())).retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(mo.client().post().uri("/comptes/" + mo2.id() + "/affectations").contentType(MediaType.APPLICATION_JSON)
                .body(territoire(e, 0, sec)).retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // le CD affecte un secteur à un CS (AFFECTER_SECTEUR) ; le CS ne le peut pas pour un autre CS
        Session cs2 = creer(cd, "ChefDeuxAFF", "CS", e.operateur(), null);
        assertThat(cd.post().uri("/comptes/" + cs2.id() + "/affectations").contentType(MediaType.APPLICATION_JSON)
                .body(territoire(e, null, null)).retrieve().toEntity(OBJET).getStatusCode().value()).isEqualTo(409);   // un seul CS par secteur et opérateur
        // un chef secteur ne voit pas ses pairs : le compte lui est introuvable
        assertThat(cs.post().uri("/comptes/" + cs2.id() + "/affectations").contentType(MediaType.APPLICATION_JSON)
                .body(territoire(e, null, null)).retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // historique : la clôture ne supprime rien ; une seconde clôture est refusée
        var cloture = cs.post().uri("/comptes/" + mo.id() + "/affectations/" + affectationId + "/cloture").retrieve().toEntity(OBJET);
        assertThat(cloture.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cloture.getBody()).containsEntry("state", "CLOSE");
        assertThat(cs.post().uri("/comptes/" + mo.id() + "/affectations/" + affectationId + "/cloture").retrieve().toBodilessEntity().getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        var liste = cs.get().uri("/comptes/" + mo.id() + "/affectations").retrieve().toEntity(LISTE).getBody();
        assertThat(liste).hasSize(1);
        // la section est libre : le second moniteur peut maintenant la prendre
        assertThat(cs.post().uri("/comptes/" + mo2.id() + "/affectations").contentType(MediaType.APPLICATION_JSON)
                .body(territoire(e, 0, sec)).retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.CREATED);
        // hors périmètre : introuvable
        assertThat(clientCompte(autre.cs(), "CS", autre.operateur()).get().uri("/comptes/" + mo.id() + "/affectations")
                .retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void laRepriseTransfereLesAffectationsDuCompteDesactiveVersLeSuccesseur() {
        Equipe e = monterEquipe("REP", "14");
        Equipe autre = monterEquipe("REX", "15");
        RestClient cs = clientCompte(e.cs(), "CS", e.operateur());
        String sec = section(cs, e, 0, "A");
        Session ancien = creer(cs, "AncienREP", "MO", e.operateur(), null);
        Session nouveau = creer(cs, "NouveauREP", "MO", e.operateur(), null);
        cs.post().uri("/comptes/" + ancien.id() + "/affectations").contentType(MediaType.APPLICATION_JSON)
                .body(territoire(e, 0, sec)).retrieve().toBodilessEntity();

        // le compte repris doit être désactivé ; sans motif : 400
        assertThat(cs.post().uri("/comptes/" + ancien.id() + "/reprise").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("successorId", nouveau.id().toString())).retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        var actif = cs.post().uri("/comptes/" + ancien.id() + "/reprise").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("successorId", nouveau.id().toString(), "reason", "Départ")).retrieve().toEntity(OBJET);
        assertThat(actif.getStatusCode().value()).isEqualTo(422);
        assertThat((String) actif.getBody().get("message")).contains("désactivé");

        cs.post().uri("/comptes/" + ancien.id() + "/desactivation").contentType(MediaType.APPLICATION_JSON).body(Map.of()).retrieve().toBodilessEntity();
        // un chef secteur d'un autre opérateur ne voit même pas ces comptes
        assertThat(clientCompte(autre.cs(), "CS", autre.operateur()).post().uri("/comptes/" + ancien.id() + "/reprise")
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("successorId", nouveau.id().toString(), "reason", "X"))
                .retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        var reprise = cs.post().uri("/comptes/" + ancien.id() + "/reprise").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("successorId", nouveau.id().toString(), "reason", "Départ à la retraite")).retrieve().toEntity(OBJET);
        assertThat(reprise.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(reprise.getBody()).containsEntry("transferredAssignments", 1);
        // le successeur a la section, l'ancien l'a perdue (close) ; la reprise est tracée avec son motif
        var chezNouveau = cs.get().uri("/comptes/" + nouveau.id() + "/affectations").retrieve().toEntity(LISTE).getBody();
        assertThat(chezNouveau).extracting(x -> x.get("state")).containsExactly("ACTIVE");
        var chezAncien = cs.get().uri("/comptes/" + ancien.id() + "/affectations").retrieve().toEntity(LISTE).getBody();
        assertThat(chezAncien).extracting(x -> x.get("state")).containsExactly("CLOSE");
        assertThat(jdbc.sql("select motif from iam.reprise_compte where source_id = :s").param("s", ancien.id()).query(String.class).single())
                .isEqualTo("Départ à la retraite");
    }

    // ------------------------------------------------------------------ import CSV

    private org.springframework.http.ResponseEntity<Map<String, Object>> televerser(RestClient client, byte[] contenu) {
        var corps = new LinkedMultiValueMap<String, Object>();
        corps.add("file", new ByteArrayResource(contenu) {
            @Override public String getFilename() { return "comptes.csv"; }
        });
        return client.post().uri("/imports-comptes").contentType(MediaType.MULTIPART_FORM_DATA).body(corps).retrieve().toEntity(OBJET);
    }

    @Test
    void importCsvControleLeFichierEnEntierPuisCreeLesLignesValides() {
        Equipe e = monterEquipe("IMP", "16");
        Equipe autre = monterEquipe("IMQ", "17");
        RestClient cs = clientCompte(e.cs(), "CS", e.operateur());
        section(cs, e, 0, "Nord");
        int lot = jdbc.sql("select numero from referentiel.lot where id = :id").param("id", e.lots().get(0)).query(Integer.class).single();

        String csv = "\uFEFFnom;prénoms;email;telephone;profil;numero;secteur;lot;section\n"
                + "Aka;Jean;jean.aka@imp.test;;MO;00700;16;" + lot + ";Nord\n"                  // 2  valide
                + "Bamba;Fanta;fanta@imp.test;;CO;;16;" + lot + ";nord\n"                       // 3  valide (section sans tenir compte de la casse)
                + "Coulibaly;Ali;ali@imp.test;;MO;;16;" + lot + ";Nord\n"                       // 4  valide au contrôle, refusée par la base : un seul moniteur par section
                + "Dosso;Awa;pasunmail;;MO;;;;\n"                                                // 5  e-mail invalide
                + "Ettien;Paul;paul@imp.test;;XX;;;;\n"                                          // 6  profil inconnu
                + "Fofana;Zoe;zoe@imp.test;;CF;00700;;;\n"                                       // 7  numéro en double dans le fichier
                + "Gnagne;Luc;JEAN.AKA@imp.test;;CO;;;;\n"                                       // 8  e-mail en double dans le fichier
                + "Hien;Ben;ben@imp.test;;CS;;;;\n"                                              // 9  un CS ne crée pas un CS
                + "Irie;Max;max@imp.test;;ES;;99;;\n"                                            // 10 secteur inconnu
                + "\"Kouassi \"\"Jr\"\"\";Eve;eve@imp.test;;ES;;;;\n";                             // 11 valide : guillemets doublés, pas de territoire
        var rapport = televerser(cs, csv.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(rapport.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(rapport.getBody()).containsEntry("status", "PRET").containsEntry("lines", 10).containsEntry("valid", 4).containsEntry("rejected", 6);
        @SuppressWarnings("unchecked") var erreurs = (List<Map<String, Object>>) rapport.getBody().get("errors");
        assertThat(erreurs).extracting(x -> x.get("line")).containsExactlyInAnyOrder(5, 6, 7, 8, 9, 10);
        assertThat(erreurs).extracting(x -> (String) x.get("message")).anyMatch(m -> m.contains("E-mail invalide"))
                .anyMatch(m -> m.contains("Profil inconnu")).anyMatch(m -> m.contains("Numéro en double"))
                .anyMatch(m -> m.contains("E-mail en double")).anyMatch(m -> m.contains("Secteur inconnu"));
        String importId = (String) rapport.getBody().get("id");
        // rien n'est créé tant que le responsable n'a pas confirmé
        assertThat(jdbc.sql("select count(*) from iam.utilisateur where email like '%@imp.test'").query(Long.class).single()).isZero();

        // un autre chef secteur ne voit pas cet import ; un moniteur n'a pas la permission
        assertThat(clientCompte(autre.cs(), "CS", autre.operateur()).post().uri("/imports-comptes/" + importId + "/application")
                .retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        Session mo = creer(cs, "SansDroitImp", "MO", e.operateur(), null);
        assertThat(mo.client().get().uri("/imports-comptes/modele").retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // application : 3 comptes créés ; le 3e moniteur de la section est refusé par la base
        var resultat = cs.post().uri("/imports-comptes/" + importId + "/application").retrieve().toEntity(OBJET);
        assertThat(resultat.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resultat.getBody()).containsEntry("created", 3).containsEntry("rejected", 7);
        @SuppressWarnings("unchecked") var crees = (List<Map<String, Object>>) resultat.getBody().get("accounts");
        assertThat(crees).extracting(x -> x.get("line")).containsExactlyInAnyOrder(2, 3, 11);
        var aka = crees.stream().filter(x -> "Aka".equals(x.get("lastName"))).findFirst().orElseThrow();
        assertThat(aka).containsEntry("matricule", "IMP-MO-00700").containsEntry("reference", "IMP-MO-00700");
        @SuppressWarnings("unchecked") var erreursFinales = (List<Map<String, Object>>) resultat.getBody().get("errors");
        assertThat(erreursFinales).anyMatch(x -> Integer.valueOf(4).equals(x.get("line")) && ((String) x.get("message")).contains("un seul moniteur"));

        // le mot de passe temporaire fonctionne ; le compte est affecté à sa section
        Session ouverte = ouvrir(UUID.fromString((String) aka.get("accountId")), "IMP-MO-00700", (String) aka.get("temporaryPassword"));
        assertThat(ouverte.client().get().uri("/auth/moi").retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.OK);
        var aff = cs.get().uri("/comptes/" + aka.get("accountId") + "/affectations").retrieve().toEntity(LISTE).getBody();
        assertThat(aff).hasSize(1);
        assertThat(aff.get(0)).containsEntry("sectionName", "Nord").containsEntry("state", "ACTIVE");

        // fichier des rejets (à corriger) : en-tête + erreur, lignes rejetées seulement
        var rejets = cs.get().uri("/imports-comptes/" + importId + "/rejets").retrieve().toEntity(String.class);
        assertThat(rejets.getHeaders().getContentType().toString()).startsWith("text/csv");
        assertThat(rejets.getBody()).contains("nom;prenoms;email;telephone;profil;numero;secteur;lot;section;erreur")
                .contains("Dosso").contains("Coulibaly").doesNotContain("Bamba");

        // pas deux fois : ni la même application, ni le même fichier
        assertThat(cs.post().uri("/imports-comptes/" + importId + "/application").retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        var memeFichier = televerser(cs, csv.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(memeFichier.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat((String) memeFichier.getBody().get("message")).contains("déjà été importé");
    }

    @Test
    void importCsvRefuseUnFichierMalFormeEtOffreLeModele() {
        Equipe e = monterEquipe("IMR", "18");
        RestClient cs = clientCompte(e.cs(), "CS", e.operateur());
        var modele = cs.get().uri("/imports-comptes/modele").retrieve().toEntity(String.class);
        assertThat(modele.getBody()).contains("nom;prenoms;email;telephone;profil;numero;secteur;lot;section");

        // colonne obligatoire absente, fichier vide, encodage invalide
        assertThat(televerser(cs, "nom;prenoms\nA;B\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(televerser(cs, "nom;prenoms;profil\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(televerser(cs, new byte[] {(byte) 0xE9, (byte) 0xE9, '\n', (byte) 0xFF}).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        // séparateur « , » accepté ; un fichier sans aucune ligne valide est ANNULE (rien à appliquer)
        var virgule = televerser(cs, "nom,prenoms,profil\nA,B,ZZ\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(virgule.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(virgule.getBody()).containsEntry("status", "ANNULE").containsEntry("valid", 0);
        assertThat(cs.post().uri("/imports-comptes/" + virgule.getBody().get("id") + "/application").retrieve().toBodilessEntity().getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }
}
