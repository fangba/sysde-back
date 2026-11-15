package ci.sysde.referentiel;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import ci.sysde.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * Intégration sur une vraie base (PostgreSQL 18 + PostGIS, migrations Flyway réelles) : droits d'écriture,
 * périmètre de lecture, règles de la base remontées en erreurs lisibles.
 */
class ReferentielIntegrationTest extends AbstractIntegrationTest {

    @Test
    void ladministrateurCreeEtModifieUnOperateur() {
        var c = admin();
        var cree = c.post().uri("/operateurs").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("acronym", "SOGB", "name", "Société des Caoutchoucs de Grand-Béréby", "type", "TECHNIQUE", "active", true))
                .retrieve().toEntity(OBJET);
        assertThat(cree.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String id = (String) cree.getBody().get("id");

        var modifie = c.put().uri("/operateurs/" + id).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("acronym", "SOGB", "name", "SOGB SA", "type", "TECHNIQUE", "phone", "27 00 00 00", "active", true))
                .retrieve().toEntity(OBJET);
        assertThat(modifie.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(modifie.getBody()).containsEntry("name", "SOGB SA").containsEntry("phone", "27 00 00 00");

        var liste = c.get().uri("/operateurs").retrieve().toEntity(LISTE);
        assertThat(liste.getBody()).extracting(o -> o.get("acronym")).contains("FIRCA", "APROMAC", "SOGB");
    }

    @Test
    void unAcronymeEnDoubleEstRefuse409() {
        var c = admin();
        var corps = Map.of("acronym", "DOUBLON", "name", "Premier", "type", "TECHNIQUE", "active", true);
        assertThat(c.post().uri("/operateurs").contentType(MediaType.APPLICATION_JSON).body(corps)
                .retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var second = c.post().uri("/operateurs").contentType(MediaType.APPLICATION_JSON).body(corps)
                .retrieve().toEntity(OBJET);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody()).containsEntry("code", "CONFLIT_DONNEES");
    }

    @Test
    void uneRequeteInvalideEstRefusee400() {
        var r = admin().post().uri("/operateurs").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("acronym", "minuscules", "name", "X", "type", "TECHNIQUE", "active", true))
                .retrieve().toEntity(OBJET);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody()).containsEntry("code", "REQUETE_INVALIDE");
    }

    @Test
    void sansLaPermissionEcrireEstInterdit403() {
        var cs = chefSecteur("NOW");
        var r = cs.post().uri("/secteurs").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("code", "99", "name", "INTERDIT")).retrieve().toEntity(OBJET);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(r.getBody()).containsEntry("code", "ACCES_REFUSE");
    }

    @Test
    void sansJetonLaLectureEstRefusee401() {
        var r = RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultStatusHandler(s -> true, (req, res) -> { }).build()
                .get().uri("/secteurs").retrieve().toBodilessEntity();
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void ladministrateurVoitLes18SecteursEt55Lots() {
        var c = admin();
        assertThat(c.get().uri("/secteurs").retrieve().toEntity(LISTE).getBody()).hasSizeGreaterThanOrEqualTo(18);
        assertThat(c.get().uri("/lots").retrieve().toEntity(LISTE).getBody()).hasSizeGreaterThanOrEqualTo(55);
    }

    @Test
    void secteurEtLotSeCreentEtUnLotNeChangePasDeSecteur() {
        var c = admin();
        var secteur = c.post().uri("/secteurs").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("code", "T1", "name", "TEST UN")).retrieve().toEntity(OBJET);
        assertThat(secteur.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String s1 = (String) secteur.getBody().get("id");
        String s2 = (String) c.post().uri("/secteurs").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("code", "T2", "name", "TEST DEUX")).retrieve().toEntity(OBJET).getBody().get("id");

        var lot = c.post().uri("/lots").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("sectorId", s1, "number", 901, "description", "Lot d'essai")).retrieve().toEntity(OBJET);
        assertThat(lot.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String lotId = (String) lot.getBody().get("id");

        var memeNumero = c.post().uri("/lots").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("sectorId", s1, "number", 901)).retrieve().toEntity(OBJET);
        assertThat(memeNumero.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        var deplace = c.put().uri("/lots/" + lotId).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("sectorId", s2, "number", 901)).retrieve().toEntity(OBJET);
        assertThat(deplace.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        var inconnu = c.put().uri("/secteurs/" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("code", "ZZ", "name", "X")).retrieve().toEntity(OBJET);
        assertThat(inconnu.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void unChefDeDepartementNeVoitQueSonOperateurEtSesSecteurs() {
        String idSaph = (String) admin().post().uri("/operateurs").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("acronym", "SAPH", "name", "SAPH", "type", "TECHNIQUE", "active", true))
                .retrieve().toEntity(OBJET).getBody().get("id");
        UUID saph = UUID.fromString(idSaph);
        var cd = clientCompte(creerCompte(adminId, saph, "CD"), "CD", saph);

        // Aucune attribution : ni secteur ni lot, et un seul opérateur (le sien).
        assertThat(cd.get().uri("/secteurs").retrieve().toEntity(LISTE).getBody()).isEmpty();
        assertThat(cd.get().uri("/lots").retrieve().toEntity(LISTE).getBody()).isEmpty();
        assertThat(cd.get().uri("/operateurs").retrieve().toEntity(LISTE).getBody())
                .extracting(o -> o.get("acronym")).containsExactly("SAPH");

        // Un contrat et une attribution sur le secteur 01 : il le voit, avec ses 3 lots, et lui seul.
        UUID contrat = jdbc.sql("""
                        insert into gestion.contrat (reference, operateur_id, organisme_mandant_id, date_debut, date_fin)
                        values ('CT-IT-1', :op, :man, date '2026-01-01', date '2028-12-31') returning id
                        """).param("op", saph).param("man", fircaId).query(UUID.class).single();
        UUID secteur01 = jdbc.sql("select id from referentiel.secteur where code = '01'").query(UUID.class).single();
        jdbc.sql("insert into gestion.attribution (contrat_id, secteur_id) values (:c, :s)")
                .param("c", contrat).param("s", secteur01).update();

        var secteurs = cd.get().uri("/secteurs").retrieve().toEntity(LISTE).getBody();
        assertThat(secteurs).extracting(o -> o.get("code")).containsExactly("01");
        assertThat(cd.get().uri("/lots").retrieve().toEntity(LISTE).getBody()).hasSize(3);
    }

    // ------------------------------------------------------------------ contrats, attributions, exercices

    @Test
    void contratAttributionEtExercicesDeBoutEnBout() {
        var c = admin();
        String op = operateurTechnique("CTR1");

        var ct = c.post().uri("/contrats").contentType(MediaType.APPLICATION_JSON)
                .body(contrat("CT-A", op, "2030-01-01", "2032-12-31")).retrieve().toEntity(OBJET);
        assertThat(ct.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(ct.getBody().get("mandantId")).isEqualTo(fircaId.toString());     // mandant par défaut : l'organisme de l'appelant
        String contratId = (String) ct.getBody().get("id");

        // secteur 02 (Grand Lahou) : lots 4, 5, 6 ; on en confie deux
        String secteur02 = jdbc.sql("select id from referentiel.secteur where code = '02'").query(UUID.class).single().toString();
        var at = c.post().uri("/attributions").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("contractId", contratId, "sectorId", secteur02, "lots", List.of(4, 5))).retrieve().toEntity(OBJET);
        assertThat(at.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(at.getBody().get("lots")).isEqualTo(List.of(4, 5));
        String attributionId = (String) at.getBody().get("id");

        // 3 exercices générés automatiquement (3 ans)
        var exercices = c.get().uri("/exercices").retrieve().toEntity(LISTE).getBody().stream()
                .filter(e -> attributionId.equals(e.get("attributionId"))).toList();
        assertThat(exercices).extracting(e -> e.get("number")).containsExactly(1, 2, 3);
        assertThat(exercices.get(0)).containsEntry("startDate", "2030-01-01").containsEntry("endDate", "2030-12-31")
                .containsEntry("closingDate", "2030-12-31").containsEntry("year", 2030);

        // on ajoute le lot 6, on retire le lot 4 (motif fourni)
        var modifie = c.put().uri("/attributions/" + attributionId).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("contractId", contratId, "sectorId", secteur02, "lots", List.of(5, 6), "reason", "Lot repris en gestion directe"))
                .retrieve().toEntity(OBJET);
        assertThat(modifie.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(modifie.getBody().get("lots")).isEqualTo(List.of(5, 6));
        assertThat(jdbc.sql("select motif from gestion.retrait_lot where attribution_id = :a").param("a", UUID.fromString(attributionId))
                .query(String.class).single()).isEqualTo("Lot repris en gestion directe");

        // la clôture se modifie ; avant le début de l'exercice, elle est refusée (règle de la base)
        String ex1 = (String) exercices.get(0).get("id");
        var cloture = c.put().uri("/exercices/" + ex1).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("closingDate", "2031-02-15")).retrieve().toEntity(OBJET);
        assertThat(cloture.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cloture.getBody()).containsEntry("closingDate", "2031-02-15");
        var tropTot = c.put().uri("/exercices/" + ex1).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("closingDate", "2029-01-01")).retrieve().toEntity(OBJET);
        assertThat(tropTot.getStatusCode().value()).isEqualTo(422);
        assertThat(tropTot.getBody()).containsEntry("code", "REGLE_METIER");
    }

    @Test
    void unLotNePeutEtreAttribueDeuxFoisSurLaMemePeriode() {
        var c = admin();
        String a = operateurTechnique("EXA");
        String b = operateurTechnique("EXB");
        String ca = (String) c.post().uri("/contrats").contentType(MediaType.APPLICATION_JSON)
                .body(contrat("CT-EXA", a, "2040-01-01", "2042-12-31")).retrieve().toEntity(OBJET).getBody().get("id");
        String cb = (String) c.post().uri("/contrats").contentType(MediaType.APPLICATION_JSON)
                .body(contrat("CT-EXB", b, "2041-01-01", "2043-12-31")).retrieve().toEntity(OBJET).getBody().get("id");
        String secteur03 = jdbc.sql("select id from referentiel.secteur where code = '03'").query(UUID.class).single().toString();

        assertThat(c.post().uri("/attributions").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("contractId", ca, "sectorId", secteur03, "lots", List.of(7)))
                .retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var conflit = c.post().uri("/attributions").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("contractId", cb, "sectorId", secteur03, "lots", List.of(7))).retrieve().toEntity(OBJET);
        assertThat(conflit.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat((String) conflit.getBody().get("message")).contains("déjà attribué");
    }

    @Test
    void deuxContratsDuMemeOperateurNeSeChevauchentPas() {
        var c = admin();
        String op = operateurTechnique("CHEV");
        assertThat(c.post().uri("/contrats").contentType(MediaType.APPLICATION_JSON)
                .body(contrat("CT-CH1", op, "2050-01-01", "2052-12-31")).retrieve().toBodilessEntity().getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        var r = c.post().uri("/contrats").contentType(MediaType.APPLICATION_JSON)
                .body(contrat("CT-CH2", op, "2052-06-01", "2055-05-31")).retrieve().toEntity(OBJET);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat((String) r.getBody().get("message")).contains("chevauchent");
    }

    @Test
    void controlesDesContratsEtAttributions() {
        var c = admin();
        String op = operateurTechnique("CTL");
        // fin avant début
        assertThat(c.post().uri("/contrats").contentType(MediaType.APPLICATION_JSON)
                .body(contrat("CT-BAD", op, "2060-01-01", "2059-01-01")).retrieve().toBodilessEntity().getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        // contrat avec un organisme (FIRCA) au lieu d'un opérateur technique
        assertThat(c.post().uri("/contrats").contentType(MediaType.APPLICATION_JSON)
                .body(contrat("CT-ORG", fircaId.toString(), "2060-01-01", "2062-12-31")).retrieve().toBodilessEntity().getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        // lot qui n'appartient pas au secteur
        String ct = (String) c.post().uri("/contrats").contentType(MediaType.APPLICATION_JSON)
                .body(contrat("CT-OK", op, "2060-01-01", "2062-12-31")).retrieve().toEntity(OBJET).getBody().get("id");
        String secteur04 = jdbc.sql("select id from referentiel.secteur where code = '04'").query(UUID.class).single().toString();
        var r = c.post().uri("/attributions").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("contractId", ct, "sectorId", secteur04, "lots", List.of(1))).retrieve().toEntity(OBJET);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat((String) r.getBody().get("message")).contains("absent");
        // un CS ne lit pas les contrats des autres opérateurs et ne peut rien écrire
        UUID opId = UUID.fromString(op);
        var cs = clientCompte(creerCompte(creerCompte(adminId, opId, "CD"), opId, "CS"), "CS", opId);
        assertThat(cs.get().uri("/contrats").retrieve().toEntity(LISTE).getBody()).extracting(x -> x.get("reference")).containsExactly("CT-OK");
        assertThat(cs.post().uri("/contrats").contentType(MediaType.APPLICATION_JSON)
                .body(contrat("CT-X", op, "2070-01-01", "2072-12-31")).retrieve().toBodilessEntity().getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ------------------------------------------------------------------ sections et groupes

    @Test
    void leChefSecteurGereLesSectionsDeSesLotsSeulement() {
        Equipe a = monterEquipe("SEA", "05");
        Equipe b = monterEquipe("SEB", "06");
        var csA = clientCompte(a.cs(), "CS", a.operateur());
        var csB = clientCompte(b.cs(), "CS", b.operateur());
        String lotA = a.lots().get(0).toString();

        var nord = csA.post().uri("/sections").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("lotId", lotA, "name", "Nord", "description", "Zone nord")).retrieve().toEntity(OBJET);
        assertThat(nord.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(nord.getBody()).containsEntry("name", "Nord");
        String nordId = (String) nord.getBody().get("id");

        // même nom, sans tenir compte de la casse, des accents ni des espaces multiples : refusé
        for (String doublon : List.of("NORD", "  nord ", "Nórd")) {
            var r = csA.post().uri("/sections").contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("lotId", lotA, "name", doublon)).retrieve().toEntity(OBJET);
            assertThat(r.getStatusCode()).as(doublon).isEqualTo(HttpStatus.CONFLICT);
        }
        // le même nom est libre dans un autre lot
        assertThat(csA.post().uri("/sections").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("lotId", a.lots().get(1).toString(), "name", "Nord")).retrieve().toBodilessEntity().getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        // numéros attribués par le serveur, consécutifs dans le lot
        csA.post().uri("/sections").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("lotId", lotA, "name", "Sud")).retrieve().toBodilessEntity();
        assertThat(jdbc.sql("select numero from referentiel.section where lot_id = :l order by numero").param("l", a.lots().get(0))
                .query(Integer.class).list()).containsExactly(1, 2);

        // modification : on renomme, mais on ne déplace pas la section dans un autre lot
        var renomme = csA.put().uri("/sections/" + nordId).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("lotId", lotA, "name", "Nord-Est")).retrieve().toEntity(OBJET);
        assertThat(renomme.getBody()).containsEntry("name", "Nord-Est");
        assertThat(csA.put().uri("/sections/" + nordId).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("lotId", a.lots().get(1).toString(), "name", "Nord-Est")).retrieve().toBodilessEntity().getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        // le chef secteur d'un autre opérateur ne peut ni écrire sur ce lot ni modifier sa section
        assertThat(csB.post().uri("/sections").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("lotId", lotA, "name", "Intrus")).retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(csB.put().uri("/sections/" + nordId).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("lotId", lotA, "name", "Piraté")).retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        // ... et il ne les voit pas
        assertThat(csB.get().uri("/sections").retrieve().toEntity(LISTE).getBody()).extracting(x -> x.get("name")).doesNotContain("Nord-Est", "Sud");
        assertThat(csA.get().uri("/sections").retrieve().toEntity(LISTE).getBody()).extracting(x -> x.get("name")).contains("Nord-Est", "Sud");
        // sans la permission GERER_SECTION : 403
        assertThat(clientCompte(a.cd(), "CD", a.operateur()).post().uri("/sections")
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("lotId", lotA, "name", "X")).retrieve().toBodilessEntity().getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void groupesDeVulgarisationEtMoniteur() {
        Equipe a = monterEquipe("GRA", "07");
        Equipe b = monterEquipe("GRB", "08");
        var csA = clientCompte(a.cs(), "CS", a.operateur());
        String lot = a.lots().get(0).toString();
        String autreLot = a.lots().get(1).toString();
        String section = (String) csA.post().uri("/sections").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("lotId", lot, "name", "A")).retrieve().toEntity(OBJET).getBody().get("id");
        String sectionAutreLot = (String) csA.post().uri("/sections").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("lotId", autreLot, "name", "B")).retrieve().toEntity(OBJET).getBody().get("id");

        UUID mo = creerCompte(a.cs(), a.operateur(), "MO");
        affecter(mo, a.operateur(), a.secteur(), a.lots().get(0), UUID.fromString(section));
        UUID moAutreOperateur = creerCompte(b.cs(), b.operateur(), "MO");
        affecter(moAutreOperateur, b.operateur(), b.secteur(), b.lots().get(0), sectionDe(b, "Z"));

        Map<String, Object> groupe = Map.of("lotId", lot, "sectionId", section, "name", "Groupe Koffikro", "monitorId", mo.toString());
        var cree = csA.post().uri("/groupes").contentType(MediaType.APPLICATION_JSON).body(groupe).retrieve().toEntity(OBJET);
        assertThat(cree.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(cree.getBody()).containsEntry("monitorId", mo.toString()).containsEntry("village", "").containsEntry("delegate", "");
        String groupeId = (String) cree.getBody().get("id");

        // nom déjà pris dans la section (casse et accents ignorés)
        assertThat(csA.post().uri("/groupes").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("lotId", lot, "sectionId", section, "name", "groupe koffikro", "monitorId", mo.toString()))
                .retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        // section d'un autre lot
        assertThat(csA.post().uri("/groupes").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("lotId", lot, "sectionId", sectionAutreLot, "name", "G2", "monitorId", mo.toString()))
                .retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        // moniteur : ni un CS, ni un MO d'un autre opérateur
        for (UUID invalide : List.of(a.cs(), moAutreOperateur)) {
            assertThat(csA.post().uri("/groupes").contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("lotId", lot, "sectionId", section, "name", "G3", "monitorId", invalide.toString()))
                    .retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        // modification : village et délégué facultatifs ; lot et section figés
        var modifie = csA.put().uri("/groupes/" + groupeId).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("lotId", lot, "sectionId", section, "name", "Groupe Koffikro", "village", "Koffikro", "delegate", "N'Guessan", "monitorId", mo.toString()))
                .retrieve().toEntity(OBJET);
        assertThat(modifie.getBody()).containsEntry("village", "Koffikro").containsEntry("delegate", "N'Guessan");
        assertThat(csA.put().uri("/groupes/" + groupeId).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("lotId", autreLot, "sectionId", sectionAutreLot, "name", "Groupe Koffikro", "monitorId", mo.toString()))
                .retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        // l'autre chef secteur ne voit ni ne modifie ce groupe
        var csB = clientCompte(b.cs(), "CS", b.operateur());
        assertThat(csB.get().uri("/groupes").retrieve().toEntity(LISTE).getBody()).extracting(x -> x.get("id")).doesNotContain(groupeId);
        assertThat(csA.get().uri("/groupes").retrieve().toEntity(LISTE).getBody()).extracting(x -> x.get("id")).contains(groupeId);
        assertThat(csB.put().uri("/groupes/" + groupeId).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("lotId", lot, "sectionId", section, "name", "Piraté", "monitorId", mo.toString()))
                .retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ------------------------------------------------------------------ permissions par profil

    @Test
    void ladministrateurAjusteLesDroitsDesProfils() {
        var ad = admin();

        var cat = ad.get().uri("/permissions/catalogue").retrieve().toEntity(OBJET).getBody();
        assertThat((List<?>) cat.get("profiles")).hasSize(10);
        assertThat((List<?>) cat.get("permissions")).hasSize(22);

        Map<String, List<String>> droits = ad.get().uri("/permissions").retrieve().toEntity(DROITS).getBody();
        assertThat(droits).containsKeys("AD", "SU", "CD", "CS", "CF", "CO", "FS", "MO", "ES", "SE");
        assertThat(droits.get("AD")).contains("GERER_REFERENTIEL", "GERER_PERMISSIONS");
        assertThat(droits.get("CF")).doesNotContain("CONSULTER_REPORTING");

        // ajout puis retrait d'une permission (un profil seul est envoyé : les autres ne bougent pas)
        var avec = new java.util.ArrayList<>(droits.get("CF"));
        avec.add("CONSULTER_REPORTING");
        var apres = ad.put().uri("/permissions").contentType(MediaType.APPLICATION_JSON).body(Map.of("CF", avec))
                .retrieve().toEntity(DROITS);
        assertThat(apres.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(apres.getBody().get("CF")).contains("CONSULTER_REPORTING");
        assertThat(apres.getBody().get("CS")).isEqualTo(droits.get("CS"));
        var retour = ad.put().uri("/permissions").contentType(MediaType.APPLICATION_JSON).body(Map.of("CF", droits.get("CF")))
                .retrieve().toEntity(DROITS);
        assertThat(retour.getBody().get("CF")).isEqualTo(droits.get("CF"));

        // garde-fou de la base : l'administrateur ne perd ni GERER_PERMISSIONS ni GERER_REFERENTIEL
        var sansGarde = new java.util.ArrayList<>(droits.get("AD"));
        sansGarde.remove("GERER_PERMISSIONS");
        var refus = ad.put().uri("/permissions").contentType(MediaType.APPLICATION_JSON).body(Map.of("AD", sansGarde))
                .retrieve().toEntity(OBJET);
        assertThat(refus.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat((String) refus.getBody().get("message")).contains("GERER_PERMISSIONS");
        assertThat(ad.get().uri("/permissions").retrieve().toEntity(DROITS).getBody().get("AD")).contains("GERER_PERMISSIONS");

        // un bloc invalide ne change rien (la permission valide qui précède n'est pas enregistrée)
        var invalide = new java.util.LinkedHashMap<String, List<String>>();
        invalide.put("CF", List.of("CONSULTER_PLANNING", "N_IMPORTE_QUOI"));
        assertThat(ad.put().uri("/permissions").contentType(MediaType.APPLICATION_JSON).body(invalide)
                .retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ad.put().uri("/permissions").contentType(MediaType.APPLICATION_JSON).body(Map.of("DI", List.of()))
                .retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ad.get().uri("/permissions").retrieve().toEntity(DROITS).getBody().get("CF")).isEqualTo(droits.get("CF"));

        // sans GERER_PERMISSIONS : lecture et écriture interdites
        var cs = chefSecteur("NOP");
        assertThat(cs.get().uri("/permissions").retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(cs.put().uri("/permissions").contentType(MediaType.APPLICATION_JSON).body(Map.of("CS", List.of("GERER_PERMISSIONS")))
                .retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ------------------------------------------------------------------ PRO-17 et ORG-06 : droits relus côté serveur

    @Test
    void lesDroitsSontRelusCoteServeurALaRequeteSuivante() {
        Equipe e = monterEquipe("RLS", "09");
        var cs = clientCompte(e.cs(), "CS", e.operateur());
        String lot = e.lots().get(0).toString();
        java.util.function.Supplier<HttpStatus> creerSection = () -> (HttpStatus) cs.post().uri("/sections")
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("lotId", lot, "name", "S" + System.nanoTime()))
                .retrieve().toBodilessEntity().getStatusCode();
        assertThat(creerSection.get()).isEqualTo(HttpStatus.CREATED);

        // l'administrateur retire GERER_SECTION au profil CS : le même jeton est refusé aussitôt (PRO-17)
        var ad = admin();
        Map<String, List<String>> avant = ad.get().uri("/permissions").retrieve().toEntity(DROITS).getBody();
        var sans = new java.util.ArrayList<>(avant.get("CS"));
        sans.remove("GERER_SECTION");
        try {
            ad.put().uri("/permissions").contentType(MediaType.APPLICATION_JSON).body(Map.of("CS", sans)).retrieve().toBodilessEntity();
            assertThat(creerSection.get()).isEqualTo(HttpStatus.FORBIDDEN);
        } finally {
            ad.put().uri("/permissions").contentType(MediaType.APPLICATION_JSON).body(Map.of("CS", avant.get("CS"))).retrieve().toBodilessEntity();
        }
        assertThat(creerSection.get()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void desactiverUnOperateurRejetteSesJetonsDejaEmisEtLeReactiverLesRend() {
        Equipe e = monterEquipe("DSO", "10");
        var cs = clientCompte(e.cs(), "CS", e.operateur());
        var cd = clientCompte(e.cd(), "CD", e.operateur());
        assertThat(cs.get().uri("/sections").retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cd.get().uri("/secteurs").retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> actif = Map.of("acronym", "DSO", "name", "DSO", "type", "TECHNIQUE", "active", true);
        Map<String, Object> inactif = Map.of("acronym", "DSO", "name", "DSO", "type", "TECHNIQUE", "active", false);
        var ad = admin();
        assertThat(ad.put().uri("/operateurs/" + e.operateur()).contentType(MediaType.APPLICATION_JSON).body(inactif)
                .retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.OK);

        // tous ses comptes sont rejetés (ORG-06), sans attendre l'expiration du jeton
        for (var client : List.of(cs, cd)) {
            var r = client.get().uri("/sections").retrieve().toEntity(OBJET);
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(r.getBody()).containsEntry("code", "SESSION_EXPIREE");
        }

        // réactivé : le même jeton fonctionne de nouveau (pas de réponse négative périmée)
        ad.put().uri("/operateurs/" + e.operateur()).contentType(MediaType.APPLICATION_JSON).body(actif).retrieve().toBodilessEntity();
        assertThat(cs.get().uri("/sections").retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void unCompteDesactiveOuUnJetonSurUnCompteInconnuEstRejete() {
        Equipe e = monterEquipe("DSC", "11");
        var cs = clientCompte(e.cs(), "CS", e.operateur());
        assertThat(cs.get().uri("/sections").retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.OK);

        // compte inconnu de la base (jeton valide, signé, mais le compte n'existe pas)
        var fantome = clientCompte(UUID.randomUUID(), "AD", fircaId);
        assertThat(fantome.get().uri("/secteurs").retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // compte désactivé (hors cache pour ce test : on invalide comme le fera le module Comptes)
        tx.executeWithoutResult(st -> {
            jdbc.sql("select set_config('app.compte_id', :a, true)").param("a", adminId.toString()).query().singleValue();
            jdbc.sql("update iam.compte set statut = 'DESACTIVE' where id = :id").param("id", e.cs()).update();
        });
        etats.invalider(e.cs());
        // envoi seul (30 jours) : le compte est reconnu mais ne peut plus rien lire (403)
        assertThat(cs.get().uri("/sections").retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        // délai dépassé : session terminée
        jdbc.sql("update iam.compte set desactive_le = now() - interval '31 days' where id = :id").param("id", e.cs()).update();
        etats.invalider(e.cs());
        assertThat(cs.get().uri("/sections").retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
