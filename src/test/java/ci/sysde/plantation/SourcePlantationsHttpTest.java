package ci.sysde.plantation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import ci.sysde.common.BusinessException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Adaptateur HTTP : pagination, clé d'API, 404, indisponibilité, géométrie. Contrat provisoire. */
class SourcePlantationsHttpTest {

    private HttpServer serveur;
    private final List<String> requetes = new CopyOnWriteArrayList<>();
    private final List<String> cles = new CopyOnWriteArrayList<>();
    private SourcePlantationsHttp source;

    private static String ligne(String id, String geometrie) {
        return "{\"id\":\"" + id + "\",\"code\":\"C-" + id + "\",\"planteur_nom\":\"Koné\",\"operateur\":\"SAPH\",\"secteur_code\":\"01\","
                + "\"lot\":2,\"section\":\"Nord\",\"superficie_ha\":12.5,\"annee_plantation\":2015,\"clone\":\"PB260\",\"en_saignee\":true,"
                + "\"latitude\":5.3,\"longitude\":-4.0" + (geometrie == null ? "" : ",\"geometrie\":" + geometrie) + "}";
    }

    @BeforeEach
    void demarrer() throws Exception {
        serveur = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serveur.createContext("/plantations", ex -> {
            requetes.add(ex.getRequestURI().toString());
            cles.add(String.valueOf(ex.getRequestHeaders().getFirst("X-API-Key")));
            String chemin = ex.getRequestURI().getPath();
            String q = String.valueOf(ex.getRequestURI().getQuery());
            int code = 200;
            String corps;
            if (chemin.equals("/plantations/introuvable")) {
                code = 404;
                corps = "{}";
            } else if (chemin.equals("/plantations/panne")) {
                code = 500;
                corps = "{}";
            } else if (chemin.startsWith("/plantations/")) {
                corps = ligne(chemin.substring("/plantations/".length()),
                        "{\"type\":\"MultiPolygon\",\"coordinates\":[[[[-4.0,5.3],[-3.9,5.3],[-3.9,5.4],[-4.0,5.3]]]]}");
            } else if (q.contains("page=0")) {
                corps = "{\"content\":[" + ligne("a", null) + "," + ligne("b", null) + "],\"totalPages\":2}";
            } else {
                corps = "{\"content\":[" + ligne("c", "{\"type\":\"Polygon\",\"coordinates\":[[[-4.0,5.3],[-3.9,5.3],[-3.9,5.4],[-4.0,5.3]]]}") + "],\"totalPages\":2}";
            }
            byte[] octets = corps.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(code, octets.length);
            ex.getResponseBody().write(octets);
            ex.close();
        });
        serveur.start();
        source = new SourcePlantationsHttp("http://127.0.0.1:" + serveur.getAddress().getPort(), "cle-secrete", JsonMapper.builder().build());
    }

    @AfterEach
    void arreter() {
        serveur.stop(0);
    }

    @Test
    void lesPagesSontLuesEtLaCleEstEnvoyee() {
        var toutes = source.lister(Set.of("01"));
        assertThat(toutes).extracting(PlantationExterne::ref).containsExactly("a", "b", "c");
        assertThat(requetes).hasSize(2).allMatch(r -> r.contains("secteur=01"));
        assertThat(cles).containsOnly("cle-secrete");
        var c = toutes.get(2);
        assertThat(c.superficieHa()).isEqualByComparingTo("12.5");
        assertThat(c.enSaignee()).isTrue();
        assertThat(c.polygone()).hasSize(4);
        assertThat(c.polygone().get(0)).containsExactly(5.3, -4.0);           // [latitude, longitude]
        assertThat(toutes.get(0).polygone()).isEmpty();
    }

    @Test
    void uneGeometrieMultiPolygoneDonneSonPremierAnneauEtUn404EstUneAbsence() {
        assertThat(source.trouver("x1").orElseThrow().polygone()).hasSize(4);
        assertThat(source.trouver("introuvable")).isEmpty();
    }

    @Test
    void uneSourceEnPanneDonneUneErreurExplicite() {
        assertThatThrownBy(() -> source.trouver("panne")).isInstanceOf(BusinessException.class)
                .hasMessageContaining("réessayez");
    }
}
