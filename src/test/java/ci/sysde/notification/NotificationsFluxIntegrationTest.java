package ci.sysde.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import ci.sysde.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Flux temps réel des notifications (SSE) : poussée au destinataire seul, après validation de la transaction. */
class NotificationsFluxIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private NotificationService notifications;

    /** Lit un flux SSE dans un fil à part : chaque ligne reçue est mise en file. */
    private BlockingQueue<String> ouvrir(String jeton, HttpClient http) throws Exception {
        BlockingQueue<String> lignes = new LinkedBlockingQueue<>();
        HttpResponse<java.io.InputStream> reponse = http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/notifications/flux"))
                        .header("Authorization", "Bearer " + jeton).header("Accept", "text/event-stream").build(),
                HttpResponse.BodyHandlers.ofInputStream());
        assertThat(reponse.statusCode()).isEqualTo(200);
        assertThat(reponse.headers().firstValue("Content-Type").orElse("")).startsWith("text/event-stream");
        Thread lecteur = new Thread(() -> {
            try (var in = new BufferedReader(new InputStreamReader(reponse.body(), StandardCharsets.UTF_8))) {
                String l;
                while ((l = in.readLine()) != null) {
                    lignes.add(l);
                }
            } catch (Exception e) {
                // flux fermé
            }
        });
        lecteur.setDaemon(true);
        lecteur.start();
        return lignes;
    }

    private String attendre(BlockingQueue<String> lignes, String debut) throws InterruptedException {
        long fin = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < fin) {
            String l = lignes.poll(500, TimeUnit.MILLISECONDS);
            if (l != null && l.startsWith(debut)) {
                return l;
            }
        }
        return null;
    }

    @Test
    void uneNotificationEstPousseeAuSeulDestinataireApresValidation() throws Exception {
        Secteur e = secteur("FLX", "04");
        String jetonMo = jwt.emettre(e.mo(), "TEST-MO", "MO", e.equipe().operateur(), List.of()).valeur();
        String jetonCo = jwt.emettre(e.co(), "TEST-CO", "CO", e.equipe().operateur(), List.of()).valeur();
        HttpClient http = HttpClient.newHttpClient();

        // sans jeton : refusé
        assertThat(http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/notifications/flux")).build(),
                HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(401);

        BlockingQueue<String> mo = ouvrir(jetonMo, http);
        BlockingQueue<String> co = ouvrir(jetonCo, http);
        assertThat(attendre(mo, ":connecte")).isNotNull();
        assertThat(attendre(co, ":connecte")).isNotNull();

        notifications.creer(e.mo(), "TEST", "Bonjour flux", "Message poussé", "info", null);

        assertThat(attendre(mo, "event:notification")).isNotNull();
        String donnees = attendre(mo, "data:");
        assertThat(donnees).contains("Bonjour flux").contains("\"read\":false");
        // l'autre compte ne reçoit rien
        assertThat(attendre(co, "event:notification")).isNull();

        // une transaction annulée ne pousse rien
        try {
            tx.executeWithoutResult(st -> {
                notifications.creer(e.mo(), "TEST", "Annulée", "Ne doit pas arriver", "info", null);
                throw new IllegalStateException("annulation");
            });
        } catch (IllegalStateException attendue) {
            // voulu
        }
        assertThat(attendre(mo, "event:notification")).isNull();
    }
}
