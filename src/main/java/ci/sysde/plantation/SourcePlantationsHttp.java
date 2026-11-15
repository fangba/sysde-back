package ci.sysde.plantation;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import ci.sysde.common.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Adaptateur HTTP vers l'API du tiers. <strong>Contrat PROVISOIRE</strong> (aligné sur le {@code PlantationDto} du front) :
 * <ul>
 *   <li>{@code GET {url}/plantations?secteur=CODE&page=N&size=500} → {@code {content:[…], totalPages}} ;</li>
 *   <li>{@code GET {url}/plantations/{id}} → un enregistrement, ou 404 ;</li>
 *   <li>enregistrement : {@code id, code, planteur_nom, planteur_telephone, village, operateur, secteur_code, lot, section,
 *       superficie_ha, annee_plantation, clone, en_saignee, latitude, longitude, geometrie (GeoJSON Polygon/MultiPolygon)} ;</li>
 *   <li>clé d'API dans l'en-tête {@code X-API-Key} (propriété {@code sysde.plantations.cle}).</li>
 * </ul>
 * Tout ce qui dépend de ce contrat est dans cette classe. Délais courts et une nouvelle tentative ; pas de coupe-circuit
 * ni de cache pour l'instant (à ajouter quand le contrat et la volumétrie seront connus).
 */
@Component
@ConditionalOnProperty("sysde.plantations.url")
class SourcePlantationsHttp implements SourcePlantations {

    private static final int TAILLE_PAGE = 500;

    private final String base;
    private final String cle;
    private final ObjectMapper json;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    SourcePlantationsHttp(@Value("${sysde.plantations.url}") String base, @Value("${sysde.plantations.cle:}") String cle,
                          ObjectMapper json) {
        this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        this.cle = cle;
        this.json = json;
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<PlantationExterne> lister(Set<String> codesSecteur) {
        List<PlantationExterne> toutes = new ArrayList<>();
        if (codesSecteur == null) {
            lirePages(null, toutes);
        } else {
            for (String code : codesSecteur) {
                lirePages(code, toutes);
            }
        }
        return toutes;
    }

    @SuppressWarnings("unchecked")
    private void lirePages(String code, List<PlantationExterne> sortie) {
        int page = 0;
        int pages = 1;
        while (page < pages) {
            String chemin = "/plantations?page=" + page + "&size=" + TAILLE_PAGE
                    + (code == null ? "" : "&secteur=" + URLEncoder.encode(code, StandardCharsets.UTF_8));
            Map<String, Object> corps = (Map<String, Object>) lire(chemin, false);
            for (Object ligne : (List<Object>) corps.getOrDefault("content", List.of())) {
                sortie.add(convertir((Map<String, Object>) ligne));
            }
            pages = corps.get("totalPages") instanceof Number n ? n.intValue() : 1;
            page++;
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<PlantationExterne> trouver(String ref) {
        Object corps = lire("/plantations/" + URLEncoder.encode(ref, StandardCharsets.UTF_8), true);
        return corps == null ? Optional.empty() : Optional.of(convertir((Map<String, Object>) corps));
    }

    /** {@code null} si 404 et {@code toleree404}. */
    private Object lire(String chemin, boolean toleree404) {
        HttpRequest.Builder requete = HttpRequest.newBuilder(URI.create(base + chemin)).timeout(Duration.ofSeconds(30)).GET()
                .header("Accept", "application/json");
        if (!cle.isBlank()) {
            requete.header("X-API-Key", cle);
        }
        IOException derniere = null;
        for (int essai = 0; essai < 2; essai++) {
            try {
                HttpResponse<String> r = client.send(requete.build(), HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() == 404 && toleree404) {
                    return null;
                }
                if (r.statusCode() / 100 != 2) {
                    throw indisponible("code " + r.statusCode());
                }
                return json.readValue(r.body(), Object.class);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw indisponible("interrompu");
            } catch (IOException e) {
                derniere = e;
            }
        }
        throw indisponible(derniere == null ? "erreur" : derniere.getClass().getSimpleName());
    }

    private static BusinessException indisponible(String cause) {
        return new BusinessException(HttpStatus.BAD_GATEWAY, "SOURCE_PLANTATIONS_INDISPONIBLE",
                "La base des plantations ne répond pas (" + cause + ") : réessayez plus tard.");
    }

    @SuppressWarnings("unchecked")
    static PlantationExterne convertir(Map<String, Object> m) {
        return new PlantationExterne(texte(m.get("id")), texte(m.get("code")), texte(m.get("planteur_nom")),
                texte(m.get("planteur_telephone")), texte(m.get("village")), texte(m.get("operateur")),
                texte(m.get("secteur_code")), m.get("lot") instanceof Number n ? n.intValue() : 0, texte(m.get("section")),
                m.get("superficie_ha") instanceof Number n ? new BigDecimal(n.toString()) : BigDecimal.ZERO,
                m.get("annee_plantation") instanceof Number n ? n.intValue() : null, texte(m.get("clone")),
                m.get("en_saignee") instanceof Boolean b ? b : null,
                m.get("latitude") instanceof Number n ? n.doubleValue() : null,
                m.get("longitude") instanceof Number n ? n.doubleValue() : null,
                anneau(m.get("geometrie")));
    }

    private static String texte(Object o) {
        return o == null ? null : o.toString();
    }

    /** Anneau extérieur du premier polygone d'un GeoJSON Polygon ou MultiPolygon, en [latitude, longitude]. */
    static List<double[]> anneau(Object geometrie) {
        if (!(geometrie instanceof Map<?, ?> g) || !(g.get("coordinates") instanceof List<?> coordonnees)) {
            return List.of();
        }
        Object anneau = coordonnees;
        int descentes = "MultiPolygon".equals(g.get("type")) ? 2 : 1;      // jusqu'à la liste des positions du 1er anneau
        for (int i = 0; i < descentes; i++) {
            if (!(anneau instanceof List<?> l) || l.isEmpty()) {
                return List.of();
            }
            anneau = l.get(0);
        }
        List<double[]> sommets = new ArrayList<>();
        if (anneau instanceof List<?> positions) {
            for (Object o : positions) {
                if (o instanceof List<?> p && p.size() >= 2 && p.get(0) instanceof Number lon && p.get(1) instanceof Number lat) {
                    sommets.add(new double[] {lat.doubleValue(), lon.doubleValue()});
                }
            }
        }
        return sommets;
    }
}
