package ci.sysde.reporting;

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
import org.springframework.web.client.RestClient;

/** Tableau de bord : visites sur 12 mois, échéances de contrats, étendue déduite du compte, filtres. */
class DashboardIntegrationTest extends AbstractIntegrationTest {

    private static final LocalDate AUJOURDHUI = LocalDate.now(ZoneOffset.UTC);

    private Map<String, Object> dashboard(RestClient c, String requete) {
        var r = c.get().uri("/dashboard" + requete).retrieve().toEntity(OBJET);
        assertThat(statut(r)).as("GET /dashboard" + requete).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> moisCourant(Map<String, Object> d) {
        var mois = (List<Map<String, Object>>) d.get("months");
        return mois.get(mois.size() - 1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void lesVisitesValideesSontCompteesParMoisSurDouzeMois() {
        Secteur s = secteur("DB", "01");
        var avant = dashboard(s.cs(), "");
        var mois = (List<Map<String, Object>>) avant.get("months");
        assertThat(mois).hasSize(12);
        assertThat(moisCourant(avant)).containsEntry("month", java.time.YearMonth.from(AUJOURDHUI).toString());
        assertThat(mois.get(0).get("month")).isEqualTo(java.time.YearMonth.from(AUJOURDHUI).minusMonths(11).toString());
        int prevuesAvant = ((Number) moisCourant(avant).get("planned")).intValue();
        int realiseesAvant = ((Number) moisCourant(avant).get("done")).intValue();
        int retardAvant = ((Number) ((Map<String, Object>) avant.get("visits")).get("late")).intValue();

        // deux visites validées ce mois-ci, la première réalisée (les deux le 1er du mois)
        int m = AUJOURDHUI.getMonthValue();
        var mo = s.moC();
        var plan = put(mo, "/plannings/mon-planning/lignes", List.of(
                ligne("VISITE_PLANTATION", m, 1, s, true, "PA-1"), ligne("VISITE_PLANTATION", m, 1, s, true, "PA-2"))).getBody();
        post(mo, "/plannings/mon-planning/soumission");
        s.cs().post().uri("/plannings/" + plan.get("id") + "/validation").contentType(MediaType.APPLICATION_JSON).body(Map.of()).retrieve().toBodilessEntity();
        UUID ligne = UUID.fromString((String) ((List<Map<String, Object>>) plan.get("lines")).get(0).get("id"));
        OffsetDateTime debut = OffsetDateTime.of(ANNEE, m, 1, 8, 0, 0, 0, ZoneOffset.UTC);
        var corps = new HashMap<String, Object>();
        corps.put("lineId", ligne.toString());
        corps.put("status", "TERMINEE");
        corps.put("startedAt", debut.toString());
        corps.put("endedAt", debut.plusHours(1).toString());
        assertThat(statut(mo.put().uri("/realisations/" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).body(corps).retrieve().toEntity(OBJET)))
                .isEqualTo(HttpStatus.OK);

        var apres = dashboard(s.cs(), "");
        var courant = moisCourant(apres);
        assertThat(((Number) courant.get("planned")).intValue()).isEqualTo(prevuesAvant + 2);
        assertThat(((Number) courant.get("done")).intValue()).isEqualTo(realiseesAvant + 1);
        assertThat(((Number) courant.get("rate")).doubleValue()).isBetween(0.0, 1.0);
        var visites = (Map<String, Object>) apres.get("visits");
        assertThat(((Number) visites.get("planned")).intValue()).isEqualTo(prevuesAvant + 2);
        assertThat(((Number) visites.get("done")).intValue()).isEqualTo(realiseesAvant + 1);
        // la visite non réalisée est en retard si le 1er est passé
        assertThat(((Number) visites.get("late")).intValue()).isEqualTo(retardAvant + (AUJOURDHUI.getDayOfMonth() > 1 ? 1 : 0));
        // ce que le serveur ne sait pas fournir est dit explicitement
        assertThat((List<String>) apres.get("unavailable")).containsExactlyInAnyOrder("PRODUCTION", "PLANTEURS", "HECTARES", "RAPPORTS");
    }

    @Test
    @SuppressWarnings("unchecked")
    void lEtendueVientDuCompteEtLesProfilsNonAutorisesSontRefuses() {
        Secteur s = secteur("DB", "01");
        // un moniteur n'a pas de tableau de bord de chef : 403 même avec la permission
        assertThat(statut(s.moC().get().uri("/dashboard").retrieve().toEntity(OBJET))).isEqualTo(HttpStatus.FORBIDDEN);
        // les paramètres d'identité du front sont ignorés : un chef secteur ne se donne pas un autre périmètre
        var normal = dashboard(s.cs(), "");
        var truque = dashboard(s.cs(), "?profile=organisme&operator=AUTRE&sector=99&filterOperator=SAPH&filterSector=03");
        assertThat(truque.get("months")).isEqualTo(normal.get("months"));
        // le chef de département voit les secteurs de son opérateur ; son filtre de secteur reste dans son périmètre
        var cd = clientCompte(s.equipe().cd(), "CD", s.equipe().operateur());
        var tableauCd = dashboard(cd, "");
        assertThat(((List<Map<String, Object>>) tableauCd.get("sectors")).stream().map(x -> x.get("code")).toList()).contains("01");
        assertThat(dashboard(cd, "?filterSector=01").get("months")).isNotNull();
        assertThat(statut(cd.get().uri("/dashboard?filterSector=03").retrieve().toEntity(OBJET))).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(statut(cd.get().uri("/dashboard?filterSector=99").retrieve().toEntity(OBJET))).isEqualTo(HttpStatus.BAD_REQUEST);
        // l'administrateur filtre par opérateur (acronyme) ; opérateur inconnu : 400
        assertThat(dashboard(admin(), "?filterOperator=PL01").get("sectors")).isNotNull();
        assertThat(dashboard(admin(), "").get("months")).isNotNull();
        assertThat(statut(admin().get().uri("/dashboard?filterOperator=NEXISTEPAS").retrieve().toEntity(OBJET))).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @SuppressWarnings("unchecked")
    void lesFinsDeContratProchesSontDesEcheancesDeLOperateurConcerne() {
        String acro = "DE" + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase().replaceAll("[0-9]", "X");
        String op = operateurTechnique(acro);
        LocalDate fin = AUJOURDHUI.plusDays(45);
        String ref = "CT-" + acro;
        assertThat(statut(admin().post().uri("/contrats").contentType(MediaType.APPLICATION_JSON)
                .body(contrat(ref, op, AUJOURDHUI.minusMonths(2).toString(), fin.toString())).retrieve().toEntity(OBJET))).isEqualTo(HttpStatus.CREATED);

        var echeances = (List<Map<String, Object>>) dashboard(admin(), "").get("deadlines");
        assertThat(echeances).anySatisfy(e -> assertThat(e).containsEntry("title", "Fin du contrat " + ref).containsEntry("operator", acro)
                .containsEntry("date", fin.toString()).containsEntry("kind", "CONTRAT"));
        // un opérateur voit ses échéances, pas celles des autres
        Secteur autre = secteur("DB", "01");
        assertThat((List<Map<String, Object>>) dashboard(autre.cs(), "").get("deadlines")).noneMatch(e -> ref.equals(((String) e.get("title")).replace("Fin du contrat ", "")));
        // le filtre d'opérateur isole ses échéances
        var filtrees = (List<Map<String, Object>>) dashboard(admin(), "?filterOperator=" + acro).get("deadlines");
        assertThat(filtrees).isNotEmpty().allSatisfy(e -> assertThat(e).containsEntry("operator", acro));
    }
}
