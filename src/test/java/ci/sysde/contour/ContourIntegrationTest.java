package ci.sysde.contour;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import ci.sysde.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

/** Import de contours : lecture GeoJSON et shapefile, contrôles, signalements, application, historique. */
class ContourIntegrationTest extends AbstractIntegrationTest {

    private static final String WGS84 = "GEOGCS[\"GCS_WGS_1984\",DATUM[\"D_WGS_1984\",SPHEROID[\"WGS_1984\",6378137.0,298.257223563]],PRIMEM[\"Greenwich\",0.0],UNIT[\"Degree\",0.0174532925199433]]";

    private UUID secteur(String code) {
        return jdbc.sql("select id from referentiel.secteur where code = :c").param("c", code).query(UUID.class).single();
    }

    private UUID premierLot(String codeSecteur) {
        return jdbc.sql("select l.id from referentiel.lot l join referentiel.secteur s on s.id = l.secteur_id where s.code = :c order by l.numero limit 1")
                .param("c", codeSecteur).query(UUID.class).single();
    }

    /** Un carré (anneau extérieur anti-horaire, convention GeoJSON). */
    private static String carre(double x0, double y0, double x1, double y1) {
        return String.format(Locale.ROOT, "[[[%f,%f],[%f,%f],[%f,%f],[%f,%f],[%f,%f]]]", x0, y0, x1, y0, x1, y1, x0, y1, x0, y0);
    }

    private static byte[] geojson(String proprietes, String geometrie) {
        return ("{\"type\":\"FeatureCollection\",\"features\":[{\"type\":\"Feature\",\"properties\":" + proprietes
                + ",\"geometry\":" + geometrie + "}]}").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] carreGeojson(String proprietes, double x0, double y0, double x1, double y1) {
        return geojson(proprietes, "{\"type\":\"Polygon\",\"coordinates\":" + carre(x0, y0, x1, y1) + "}");
    }

    private ResponseEntity<Map<String, Object>> deposer(RestClient c, String niveau, UUID cible, String nom, byte[] contenu) {
        var corps = new LinkedMultiValueMap<String, Object>();
        corps.add("file", new ByteArrayResource(contenu) {
            @Override public String getFilename() { return nom; }
        });
        corps.add("level", niveau);
        corps.add("targetId", cible.toString());
        return c.post().uri("/imports-contours").contentType(MediaType.MULTIPART_FORM_DATA).body(corps).retrieve().toEntity(OBJET);
    }

    private ResponseEntity<Map<String, Object>> appliquer(RestClient c, Object id) {
        return c.post().uri("/imports-contours/" + id + "/application").retrieve().toEntity(OBJET);
    }

    @SuppressWarnings("unchecked")
    private List<String> liste(Map<String, Object> import_, String cle) {
        return (List<String>) import_.get(cle);
    }

    // ------------------------------------------------------------------ GeoJSON : de la validation à la lecture

    @Test
    void ungeojsonValideSeValideSeConserveEtSAppliqueEtSeLit() throws Exception {
        UUID cible = secteur("05");
        var rapport = deposer(admin(), "SECTEUR", cible, "secteur05.geojson", carreGeojson("{\"code\":\"5\",\"nom\":\"Test\"}", -5.00, 5.00, -4.90, 5.10));
        assertThat(rapport.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(rapport.getBody()).containsEntry("status", "PRET").containsEntry("polygons", 1).containsEntry("format", "GEOJSON")
                .containsEntry("level", "SECTEUR");
        assertThat(liste(rapport.getBody(), "errors")).isEmpty();
        double ha = ((Number) rapport.getBody().get("areaHa")).doubleValue();
        assertThat(ha).isBetween(11_000.0, 13_500.0);                                   // 0,1° × 0,1° ≈ 12 300 ha
        @SuppressWarnings("unchecked") var bbox = (List<Number>) rapport.getBody().get("bbox");
        assertThat(bbox.get(0).doubleValue()).isEqualTo(-5.00);
        String id = (String) rapport.getBody().get("id");

        // le fichier d'origine est conservé (historique des contours) et rien n'est appliqué avant confirmation
        String cle = jdbc.sql("select fichier_cle from referentiel.import_contour where id = cast(:id as uuid)").param("id", id).query(String.class).single();
        assertThat(Files.exists(STOCKAGE.resolve(cle))).isTrue();
        assertThat(jdbc.sql("select geom is null from referentiel.secteur where id = :id").param("id", cible).query(Boolean.class).single()).isTrue();
        assertThat(admin().get().uri("/secteurs/" + cible + "/contour").retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        var applique = appliquer(admin(), id);
        assertThat(applique.getBody()).containsEntry("status", "APPLIQUE");
        assertThat(jdbc.sql("select ST_SRID(geom) = 4326 and GeometryType(geom) = 'MULTIPOLYGON' from referentiel.secteur where id = :id").param("id", cible)
                .query(Boolean.class).single()).isTrue();

        // lecture GeoJSON ; hors périmètre : introuvable
        var lu = admin().get().uri("/secteurs/" + cible + "/contour").retrieve().toEntity(Map.class);
        assertThat(lu.getHeaders().getContentType().toString()).startsWith("application/geo+json");
        assertThat(lu.getBody()).containsEntry("type", "Feature");
        assertThat(((Map<?, ?>) lu.getBody().get("geometry")).get("type")).isEqualTo("MultiPolygon");
        assertThat(((Map<?, ?>) lu.getBody().get("properties")).get("code")).isEqualTo("05");
        Secteur autre = secteur("CTA", "04");
        assertThat(autre.cs().get().uri("/secteurs/" + cible + "/contour").retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(autre.cs().get().uri("/secteurs/" + secteur("04") + "/contour").retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);   // en périmètre, mais sans contour

        // on n'applique pas deux fois ; un autre administrateur ne voit pas cet import
        assertThat(appliquer(admin(), id).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        UUID ad2 = creerCompte(adminId, fircaId, "AD");
        assertThat(clientCompte(ad2, "AD", fircaId).get().uri("/imports-contours/" + id).retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void lesFichiersIncorrectsSontRejetesAvecLeurRaison() {
        UUID cible = secteur("06");
        // identification : le fichier désigne un autre secteur
        var autreCode = deposer(admin(), "SECTEUR", cible, "x.geojson", carreGeojson("{\"code\":\"07\"}", -4.80, 5.00, -4.70, 5.10));
        assertThat(autreCode.getBody()).containsEntry("status", "REJETE");
        assertThat(liste(autreCode.getBody(), "errors").get(0)).contains("désigne le secteur « 07 »").contains("secteur 06");
        // auto-intersection (nœud papillon)
        var papillon = deposer(admin(), "SECTEUR", cible, "x.geojson",
                geojson("{}", "{\"type\":\"Polygon\",\"coordinates\":[[[-4.80,5.00],[-4.70,5.10],[-4.70,5.00],[-4.80,5.10],[-4.80,5.00]]]}"));
        assertThat(papillon.getBody()).containsEntry("status", "REJETE");
        assertThat(liste(papillon.getBody(), "errors").get(0)).contains("Géométrie invalide").containsIgnoringCase("self-intersection");
        // latitude et longitude inversées
        var inverse = deposer(admin(), "SECTEUR", cible, "x.geojson", carreGeojson("{}", 5.00, -4.80, 5.10, -4.70));
        assertThat(inverse.getBody()).containsEntry("status", "REJETE");
        assertThat(liste(inverse.getBody(), "errors").get(0)).contains("hors de la Côte d'Ivoire");
        // coordonnées projetées en mètres
        var metres = deposer(admin(), "SECTEUR", cible, "x.geojson", carreGeojson("{}", 500000, 600000, 510000, 610000));
        assertThat(liste(metres.getBody(), "errors").get(0)).contains("hors de la Côte d'Ivoire");
        // système de coordonnées déclaré autre que WGS 84
        var crs = ("{\"type\":\"FeatureCollection\",\"crs\":{\"type\":\"name\",\"properties\":{\"name\":\"urn:ogc:def:crs:EPSG::32630\"}},\"features\":[{\"type\":\"Feature\",\"properties\":{},\"geometry\":"
                + "{\"type\":\"Polygon\",\"coordinates\":" + carre(-4.80, 5.00, -4.70, 5.10) + "}}]}").getBytes(StandardCharsets.UTF_8);
        assertThat(liste(deposer(admin(), "SECTEUR", cible, "x.geojson", crs).getBody(), "errors").get(0)).contains("autre que WGS 84");
        // un import rejeté ne s'applique pas
        assertThat(appliquer(admin(), autreCode.getBody().get("id")).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(jdbc.sql("select geom is null from referentiel.secteur where id = :id").param("id", cible).query(Boolean.class).single()).isTrue();

        // refus immédiats (400) : fichier illisible, géométrie non polygonale, fichier vide, cible ou niveau inconnus
        assertThat(deposer(admin(), "SECTEUR", cible, "x.geojson", "pas du json".getBytes(StandardCharsets.UTF_8)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(deposer(admin(), "SECTEUR", cible, "x.geojson", geojson("{}", "{\"type\":\"Point\",\"coordinates\":[-4.8,5.0]}")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(deposer(admin(), "SECTEUR", cible, "x.geojson", new byte[0]).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(deposer(admin(), "SECTEUR", UUID.randomUUID(), "x.geojson", carreGeojson("{}", -4.8, 5.0, -4.7, 5.1)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        var section = deposer(admin(), "SECTION", cible, "x.geojson", carreGeojson("{}", -4.8, 5.0, -4.7, 5.1));
        assertThat(section.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat((String) section.getBody().get("message")).contains("sections");
        // seule la permission GERER_REFERENTIEL importe des contours
        assertThat(deposer(chefSecteur("CTB"), "SECTEUR", cible, "x.geojson", carreGeojson("{}", -4.80, 5.00, -4.70, 5.10)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ------------------------------------------------------------------ signalements

    @Test
    void recoupementsDebordementsEtRemplacementSontSignalesSansBloquer() {
        // recoupement entre deux secteurs : la moitié du second est dans le premier
        UUID s08 = secteur("08");
        appliquer(admin(), deposer(admin(), "SECTEUR", s08, "a.geojson", carreGeojson("{}", -4.00, 6.00, -3.90, 6.10)).getBody().get("id"));
        var s09 = deposer(admin(), "SECTEUR", secteur("09"), "b.geojson", carreGeojson("{}", -3.95, 6.00, -3.85, 6.10));
        assertThat(s09.getBody()).containsEntry("status", "PRET");                                       // non bloquant
        assertThat(liste(s09.getBody(), "warnings")).anyMatch(w -> w.contains("Recoupe le secteur 08") && w.contains("50.0 %"));

        // lot qui déborde de son secteur ; secteur qui laisse un lot dehors ; remplacement d'un contour
        UUID s10 = secteur("10");
        UUID lot = premierLot("10");
        appliquer(admin(), deposer(admin(), "SECTEUR", s10, "s.geojson", carreGeojson("{}", -3.00, 7.00, -2.90, 7.10)).getBody().get("id"));
        var debordant = deposer(admin(), "LOT", lot, "l.geojson", carreGeojson("{}", -2.95, 7.00, -2.85, 7.10));
        assertThat(debordant.getBody()).containsEntry("status", "PRET").containsEntry("level", "LOT");
        assertThat(liste(debordant.getBody(), "warnings")).anyMatch(w -> w.contains("déborde du secteur 10") && w.contains("50.0 %"));
        appliquer(admin(), debordant.getBody().get("id"));
        var remplace = deposer(admin(), "SECTEUR", s10, "s2.geojson", carreGeojson("{}", -3.00, 7.00, -2.96, 7.10));
        assertThat(liste(remplace.getBody(), "warnings")).anyMatch(w -> w.contains("Remplace le contour existant"))
                .anyMatch(w -> w.contains("déborde de ce secteur"));
        // un lot dans le fichier : l'identification se fait sur son numéro, « 12 » ≠ le lot choisi
        var mauvaisNumero = deposer(admin(), "LOT", premierLot("11"), "l.geojson", carreGeojson("{\"numero\":\"999\"}", -2.50, 7.00, -2.40, 7.10));
        assertThat(mauvaisNumero.getBody()).containsEntry("status", "REJETE");

        // annulation : plus applicable
        var annule = admin().post().uri("/imports-contours/" + remplace.getBody().get("id") + "/annulation").retrieve().toEntity(OBJET);
        assertThat(annule.getBody()).containsEntry("status", "ANNULE");
        assertThat(appliquer(admin(), remplace.getBody().get("id")).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    // ------------------------------------------------------------------ shapefile

    /** Un shapefile minimal (un polygone, un attribut « code »), zippé. Anneau extérieur horaire, comme l'exige le format. */
    private static byte[] shapefileZip(double x0, double y0, double x1, double y1, String code, String prj, boolean avecShp) throws Exception {
        double[][] anneau = {{x0, y0}, {x0, y1}, {x1, y1}, {x1, y0}, {x0, y0}};
        int octetsContenu = 44 + 4 + 16 * anneau.length;
        ByteBuffer shp = ByteBuffer.allocate(100 + 8 + octetsContenu);
        shp.order(ByteOrder.BIG_ENDIAN).putInt(9994);
        shp.position(24);
        shp.putInt(shp.capacity() / 2);
        shp.order(ByteOrder.LITTLE_ENDIAN).putInt(1000).putInt(5).putDouble(x0).putDouble(y0).putDouble(x1).putDouble(y1);
        shp.position(100);
        shp.order(ByteOrder.BIG_ENDIAN).putInt(1).putInt(octetsContenu / 2);
        shp.order(ByteOrder.LITTLE_ENDIAN).putInt(5).putDouble(x0).putDouble(y0).putDouble(x1).putDouble(y1).putInt(1).putInt(anneau.length).putInt(0);
        for (double[] p : anneau) {
            shp.putDouble(p[0]).putDouble(p[1]);
        }
        // dbf : un champ « CODE » de 10 caractères
        ByteBuffer dbf = ByteBuffer.allocate(32 + 32 + 1 + 1 + 10).order(ByteOrder.LITTLE_ENDIAN);
        dbf.put((byte) 3).position(4);
        dbf.putInt(1).putShort((short) 65).putShort((short) 11);
        dbf.position(32);
        dbf.put("CODE".getBytes(StandardCharsets.ISO_8859_1));
        dbf.position(32 + 11);
        dbf.put((byte) 'C');
        dbf.position(32 + 16);
        dbf.put((byte) 10);
        dbf.position(64);
        dbf.put((byte) 0x0D).put((byte) ' ').put(String.format("%-10s", code).getBytes(StandardCharsets.ISO_8859_1));
        ByteArrayOutputStream sortie = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(sortie)) {
            if (avecShp) {
                zip.putNextEntry(new ZipEntry("secteur.shp"));
                zip.write(shp.array());
                zip.closeEntry();
            }
            zip.putNextEntry(new ZipEntry("secteur.dbf"));
            zip.write(dbf.array());
            zip.closeEntry();
            if (prj != null) {
                zip.putNextEntry(new ZipEntry("secteur.prj"));
                zip.write(prj.getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return sortie.toByteArray();
    }

    @Test
    void unShapefileZippeEstLuAvecSesAttributsEtSonSystemeDeCoordonnees() throws Exception {
        UUID cible = secteur("12");
        var ok = deposer(admin(), "SECTEUR", cible, "secteur12.zip", shapefileZip(-6.00, 5.50, -5.90, 5.60, "12", WGS84, true));
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(ok.getBody()).containsEntry("status", "PRET").containsEntry("format", "SHAPEFILE").containsEntry("polygons", 1);
        assertThat(((Number) ok.getBody().get("areaHa")).doubleValue()).isBetween(11_000.0, 13_500.0);
        assertThat(liste(ok.getBody(), "warnings")).isEmpty();
        appliquer(admin(), ok.getBody().get("id"));
        assertThat(admin().get().uri("/secteurs/" + cible + "/contour").retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.OK);

        // le code du .dbf désigne un autre secteur ; système projeté ; .prj absent (simple signalement) ; archive sans .shp (400)
        assertThat(liste(deposer(admin(), "SECTEUR", cible, "s.zip", shapefileZip(-5.80, 5.50, -5.70, 5.60, "13", WGS84, true)).getBody(), "errors").get(0))
                .contains("désigne le secteur « 13 »");
        var projete = deposer(admin(), "SECTEUR", cible, "s.zip", shapefileZip(-5.80, 5.50, -5.70, 5.60, "12", "PROJCS[\"WGS_1984_UTM_Zone_30N\"," + WGS84 + "]", true));
        assertThat(projete.getBody()).containsEntry("status", "REJETE");
        assertThat(liste(projete.getBody(), "errors").get(0)).contains("n'est pas WGS 84");
        var sansPrj = deposer(admin(), "SECTEUR", cible, "s.zip", shapefileZip(-5.80, 5.50, -5.70, 5.60, "12", null, true));
        assertThat(sansPrj.getBody()).containsEntry("status", "PRET");
        assertThat(liste(sansPrj.getBody(), "warnings")).anyMatch(w -> w.contains(".prj absent"));
        assertThat(deposer(admin(), "SECTEUR", cible, "s.zip", shapefileZip(-5.80, 5.50, -5.70, 5.60, "12", WGS84, false)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
