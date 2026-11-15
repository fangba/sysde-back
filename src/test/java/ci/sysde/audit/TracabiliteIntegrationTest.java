package ci.sysde.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import ci.sysde.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

/** Piste d'audit : périmètre de consultation, contenu des événements, durée de conservation, export tracé. */
class TracabiliteIntegrationTest extends AbstractIntegrationTest {

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> contenu(Map<String, Object> page) {
        return (List<Map<String, Object>>) page.get("content");
    }

    @Test
    void lAuditGardeQuiQuoiQuandEtPourquoiSansJamaisLesMotsDePasse() {
        Chaine c = chaine("TRA");
        Session mo = creer(c.cs().client(), "TraceMo", "MO", c.operateur(), null);
        c.cs().client().post().uri("/comptes/" + mo.id() + "/desactivation").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("reason", "Départ")).retrieve().toBodilessEntity();

        // la désactivation : auteur (compte, matricule, profil), motif, avant / après
        var page = c.cs().client().get().uri("/tracabilite?table=compte&objectId=" + mo.id()).retrieve().toEntity(OBJET).getBody();
        var evenements = contenu(page);
        var desactivation = evenements.stream().filter(e -> "Départ".equals(e.get("reason"))).findFirst().orElseThrow();
        assertThat(desactivation).containsEntry("accountId", c.cs().id().toString()).containsEntry("matricule", c.cs().matricule())
                .containsEntry("profile", "CS").containsEntry("operation", "U");
        @SuppressWarnings("unchecked") var avant = (Map<String, Object>) desactivation.get("before");
        @SuppressWarnings("unchecked") var apres = (Map<String, Object>) desactivation.get("after");
        assertThat(avant).containsEntry("statut", "ACTIF");
        assertThat(apres).containsEntry("statut", "DESACTIVE");
        // la création : aucune trace du mot de passe ni de son empreinte (TRA-04)
        var creation = evenements.stream().filter(e -> "I".equals(e.get("operation"))).findFirst().orElseThrow();
        assertThat(creation.get("after").toString()).doesNotContain("$2").doesNotContain(mo.motDePasse());

        // le CS ne voit pas ce que son CD a fait (la création du CS) ; le CD voit celui du CS ; un autre opérateur ne voit rien
        assertThat(contenu(c.cs().client().get().uri("/tracabilite?table=compte&objectId=" + c.cs().id()).retrieve().toEntity(OBJET).getBody()))
                .isEmpty();
        assertThat(contenu(c.cd().client().get().uri("/tracabilite?table=compte&objectId=" + mo.id()).retrieve().toEntity(OBJET).getBody()))
                .isNotEmpty();
        Chaine autre = chaine("TRB");
        assertThat(contenu(autre.cs().client().get().uri("/tracabilite?table=compte&objectId=" + mo.id()).retrieve().toEntity(OBJET).getBody()))
                .isEmpty();
        // filtre par compte auteur ; un moniteur n'a pas la permission
        assertThat(contenu(c.cd().client().get().uri("/tracabilite?accountId=" + c.cs().id()).retrieve().toEntity(OBJET).getBody()))
                .extracting(e -> e.get("accountId")).containsOnly(c.cs().id().toString());
        Session actif = creer(c.cs().client(), "TraceMo2", "MO", c.operateur(), null);
        assertThat(actif.client().get().uri("/tracabilite").retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void laFenetreParDefautEtLaConservationBornentLaConsultation() {
        Chaine c = chaine("FEN");
        UUID cible = UUID.randomUUID();
        for (int jours : new int[] {5, 60}) {
            insererEvenement(c.cs().id(), cible, "il y a " + jours + " jours", "now() - interval '" + jours + " days'");
        }
        insererEvenement(c.cs().id(), cible, "il y a 40 mois", "now() - interval '40 months'");
        String base = "/tracabilite?table=essai&objectId=" + cible;

        // par défaut : 30 jours
        assertThat(contenu(c.cs().client().get().uri(base).retrieve().toEntity(OBJET).getBody())).extracting(e -> e.get("reason"))
                .containsExactly("il y a 5 jours");
        // fenêtre élargie : 60 jours ; au-delà de la conservation (36 mois), plus rien en consultation directe
        assertThat(contenu(c.cs().client().get().uri(base + "&from=2000-01-01").retrieve().toEntity(OBJET).getBody())).extracting(e -> e.get("reason"))
                .containsExactly("il y a 5 jours", "il y a 60 jours");
        // dates invalides
        assertThat(c.cs().client().get().uri(base + "&from=hier").retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(c.cs().client().get().uri(base + "&from=2026-12-01&to=2026-01-01").retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        // pagination
        var p = c.cs().client().get().uri(base + "&from=2000-01-01&size=1").retrieve().toEntity(OBJET).getBody();
        assertThat(p).containsEntry("totalElements", 2).containsEntry("totalPages", 2);
        assertThat(contenu(p)).hasSize(1);
    }

    private void insererEvenement(UUID acteur, UUID objet, String motif, String quand) {
        jdbc.sql("insert into audit.evenement (survenu_le, acteur_compte_id, motif, schema_nom, table_nom, operation, objet_id) "
                        + "values (" + quand + ", :a, :m, 'public', 'essai', 'I', :o)")
                .param("a", acteur).param("m", motif).param("o", objet.toString()).update();
    }

    @Test
    void seulLAdministrateurExporteEtLExportEstLuiMemeTrace() {
        Chaine c = chaine("EXP");
        Session mo = creer(c.cs().client(), "ExportMo", "MO", c.operateur(), null);
        c.cs().client().post().uri("/comptes/" + mo.id() + "/desactivation").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("reason", "=SUM(1+1)")).retrieve().toBodilessEntity();

        // ni le CS ni le CD n'exportent
        assertThat(c.cs().client().get().uri("/tracabilite/export").retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(c.cd().client().get().uri("/tracabilite/export").retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        var export = admin().get().uri("/tracabilite/export?table=compte&objectId=" + mo.id()).retrieve().toEntity(String.class);
        assertThat(export.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(export.getHeaders().getContentType().toString()).startsWith("text/csv");
        assertThat(export.getBody()).contains("date;compte;matricule;profil;utilisateur;adresse_ip;motif;schema;table;operation;objet;avant;apres")
                .contains(c.cs().matricule()).contains(mo.id().toString())
                .contains("'=SUM(1+1)")                                   // pas d'injection de formule dans le tableur
                .doesNotContain(";=SUM(1+1);");

        // l'export est tracé : qui, quels filtres, combien de lignes
        var traces = contenu(admin().get().uri("/tracabilite?table=tracabilite_export").retrieve().toEntity(OBJET).getBody());
        assertThat(traces).isNotEmpty();
        @SuppressWarnings("unchecked") var apres = (Map<String, Object>) traces.get(0).get("after");
        assertThat(apres).containsKey("lignes");
        assertThat((Integer) apres.get("lignes")).isPositive();
        assertThat(apres.get("filtres").toString()).contains(mo.id().toString());
        assertThat(traces.get(0)).containsEntry("accountId", adminId.toString());
    }
}
