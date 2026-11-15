package ci.sysde.contour;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import ci.sysde.common.BusinessException;
import ci.sysde.contour.ContourDtos.ContourImport;
import ci.sysde.contour.LectureContours.Lecture;
import ci.sysde.referentiel.SecteursVisibles;
import ci.sysde.security.Acteur;
import ci.sysde.stockage.StockageObjets;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Import des contours de secteurs et de lots (PostGIS, WGS 84, MultiPolygon). Le fichier est contrôlé en entier (géométrie valide,
 * coordonnées plausibles en Côte d'Ivoire, identification du fichier cohérente avec la cible), puis appliqué sur confirmation. Les
 * recoupements, les débordements et le remplacement d'un contour existant sont <b>signalés sans bloquer</b>. Le fichier d'origine est
 * conservé dans le stockage : il sert d'historique des contours.
 *
 * <p>À l'application, le fichier conservé est relu et recontrôlé : l'état des contours voisins a pu changer depuis la validation.
 */
@Service
class ContoursService {

    /** Côte d'Ivoire, avec marge : ce qui est hors de cette boîte est presque sûrement une inversion latitude/longitude ou des mètres. */
    private static final double LON_MIN = -9.5;
    private static final double LON_MAX = -1.5;
    private static final double LAT_MIN = 3.5;
    private static final double LAT_MAX = 11.5;
    private static final double SEUIL_SIGNALEMENT = 0.001;       // 0,1 % de la superficie
    private static final double SEUIL_DEBORDEMENT = 0.01;        // 1 %
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

    private record Cible(String niveau, UUID id, UUID secteurId, String code, String libelle, boolean aUnContour) {
    }

    private record Rapport(List<String> erreurs, List<String> avertissements, Double superficieHa, List<Double> emprise, String geojson, int polygones) {
    }

    private final JdbcClient jdbc;
    private final StockageObjets stockage;
    private final ObjectMapper json;
    private final SecteursVisibles visibles;

    ContoursService(JdbcClient jdbc, StockageObjets stockage, ObjectMapper json, SecteursVisibles visibles) {
        this.jdbc = jdbc;
        this.stockage = stockage;
        this.json = json;
        this.visibles = visibles;
    }

    // ------------------------------------------------------------------ validation

    /** Contrôle le fichier, le conserve et enregistre le rapport. Un fichier illisible est refusé (400) ; un fichier lisible mais incorrect donne un import REJETE. */
    @Transactional
    ContourImport valider(Acteur acteur, String niveau, UUID cibleId, String nomFichier, byte[] contenu) {
        Cible cible = cible(niveau, cibleId);
        Lecture lecture = lire(nomFichier, contenu);
        Rapport rapport = analyser(cible, lecture);

        UUID id = UUID.randomUUID();
        String cle = stockage.enregistrer("contours/" + id, nomFichier == null || nomFichier.isBlank() ? "contour" : nomFichier, contenu);
        jdbc.sql("""
                        insert into referentiel.import_contour (id, niveau, secteur_id, lot_id, format, fichier_nom, fichier_cle, empreinte, statut,
                                                                nb_elements, nb_erreurs, rapport, createur_id)
                        values (:id, :niveau, :secteur, :lot, :format, :nom, :cle, :empreinte, :statut, :n, :e, cast(:rapport as jsonb), :createur)
                        """)
                .param("id", id).param("niveau", cible.niveau())
                .param("secteur", "SECTEUR".equals(cible.niveau()) ? cible.id() : null).param("lot", "LOT".equals(cible.niveau()) ? cible.id() : null)
                .param("format", lecture.format()).param("nom", abreger(nomFichier)).param("cle", cle).param("empreinte", sha256(contenu))
                .param("statut", rapport.erreurs().isEmpty() ? "PRET" : "REJETE").param("n", rapport.polygones()).param("e", rapport.erreurs().size())
                .param("rapport", json.writeValueAsString(rapportJson(rapport))).param("createur", acteur.compteId()).update();
        return lireImport(id);
    }

    @Transactional(readOnly = true)
    ContourImport lire(Acteur acteur, UUID id) {
        possede(acteur, id);
        return lireImport(id);
    }

    /** Applique le contour : le fichier conservé est relu et recontrôlé, puis la géométrie de la cible est remplacée. */
    @Transactional
    ContourImport appliquer(Acteur acteur, UUID id) {
        Map<String, Object> entete = possede(acteur, id);
        if (!"PRET".equals(entete.get("statut"))) {
            throw conflit("Cet import n'est pas prêt à être appliqué (statut : " + entete.get("statut") + ").");
        }
        UUID cibleId = (UUID) (entete.get("secteur_id") != null ? entete.get("secteur_id") : entete.get("lot_id"));
        Cible cible = cible((String) entete.get("niveau"), cibleId);
        Lecture lecture = lire((String) entete.get("fichier_nom"), stockage.lire((String) entete.get("fichier_cle")));
        Rapport rapport = analyser(cible, lecture);
        if (!rapport.erreurs().isEmpty()) {
            throw conflit("Le contrôle du fichier échoue maintenant : " + rapport.erreurs().get(0));
        }
        String table = "SECTEUR".equals(cible.niveau()) ? "referentiel.secteur" : "referentiel.lot";
        jdbc.sql("update " + table + " set geom = ST_Multi(ST_SetSRID(ST_GeomFromGeoJSON(:g), 4326)) where id = :id")
                .param("g", rapport.geojson()).param("id", cible.id()).update();
        jdbc.sql("update referentiel.import_contour set statut = 'APPLIQUE', termine_le = now(), rapport = cast(:r as jsonb) where id = :id")
                .param("r", json.writeValueAsString(rapportJson(rapport))).param("id", id).update();
        return lireImport(id);
    }

    @Transactional
    ContourImport annuler(Acteur acteur, UUID id) {
        Map<String, Object> entete = possede(acteur, id);
        if (!"PRET".equals(entete.get("statut")) && !"REJETE".equals(entete.get("statut"))) {
            throw conflit("Cet import ne peut plus être annulé (statut : " + entete.get("statut") + ").");
        }
        jdbc.sql("update referentiel.import_contour set statut = 'ANNULE', termine_le = now() where id = :id").param("id", id).update();
        return lireImport(id);
    }

    // ------------------------------------------------------------------ lecture d'un contour

    /** Le contour d'un secteur ou d'un lot en GeoJSON (Feature), dans le périmètre de l'appelant. */
    @Transactional(readOnly = true)
    String contour(Acteur acteur, String niveau, UUID id) {
        boolean secteur = "SECTEUR".equals(niveau);
        UUID secteurId = secteur ? id : jdbc.sql("select secteur_id from referentiel.lot where id = :id and supprime_le is null").param("id", id)
                .query(UUID.class).optional().orElse(null);
        Optional<Set<UUID>> permis = visibles.de(acteur);
        if (secteurId == null || (permis.isPresent() && !permis.get().contains(secteurId))) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "INTROUVABLE", (secteur ? "Secteur" : "Lot") + " introuvable.");
        }
        String sql = secteur
                ? "select jsonb_build_object('type', 'Feature', 'properties', jsonb_build_object('id', s.id, 'code', s.code, 'name', s.nom), 'geometry', ST_AsGeoJSON(s.geom, 6)::jsonb)::text"
                + " from referentiel.secteur s where s.id = :id and s.geom is not null"
                : "select jsonb_build_object('type', 'Feature', 'properties', jsonb_build_object('id', l.id, 'number', l.numero, 'sectorId', l.secteur_id), 'geometry', ST_AsGeoJSON(l.geom, 6)::jsonb)::text"
                + " from referentiel.lot l where l.id = :id and l.geom is not null";
        return jdbc.sql(sql).param("id", id).query(String.class).optional()
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "INTROUVABLE", "Pas de contour pour " + (secteur ? "ce secteur." : "ce lot.")));
    }

    // ------------------------------------------------------------------ analyse

    private Rapport analyser(Cible cible, Lecture lecture) {
        List<String> erreurs = new ArrayList<>(lecture.erreurs());
        List<String> avertissements = new ArrayList<>(lecture.avertissements());
        if (lecture.polygones().isEmpty()) {
            erreurs.add("Le fichier ne contient aucun polygone.");
            return new Rapport(erreurs, avertissements, null, null, null, 0);
        }
        verifierIdentification(cible, lecture, erreurs);
        if (lecture.polygones().size() > 1) {
            avertissements.add(lecture.polygones().size() + " polygones réunis en un seul contour (MultiPolygon).");
        }
        // Coordonnées plausibles : détecte l'inversion latitude / longitude et les coordonnées projetées (en mètres).
        double xMin = Double.MAX_VALUE, xMax = -Double.MAX_VALUE, yMin = Double.MAX_VALUE, yMax = -Double.MAX_VALUE;
        for (LectureContours.Polygone p : lecture.polygones()) {
            for (List<double[]> anneau : p.anneaux()) {
                for (double[] pt : anneau) {
                    xMin = Math.min(xMin, pt[0]);
                    xMax = Math.max(xMax, pt[0]);
                    yMin = Math.min(yMin, pt[1]);
                    yMax = Math.max(yMax, pt[1]);
                }
            }
        }
        if (xMin < LON_MIN || xMax > LON_MAX || yMin < LAT_MIN || yMax > LAT_MAX) {
            erreurs.add(String.format(Locale.ROOT, "Coordonnées hors de la Côte d'Ivoire (longitude %.4f à %.4f, latitude %.4f à %.4f) : vérifiez l'ordre longitude, latitude et le système WGS 84.",
                    xMin, xMax, yMin, yMax));
        }
        String geojson = LectureContours.multiPolygone(lecture);
        if (!erreurs.isEmpty()) {
            return new Rapport(erreurs, avertissements, null, List.of(xMin, yMin, xMax, yMax), geojson, lecture.polygones().size());
        }

        Double superficie = null;
        try {
            Map<String, Object> stats = jdbc.sql("""
                            select ST_IsValid(g) as valide, ST_IsValidReason(g) as raison,
                                   case when ST_IsValid(g) then ST_Area(g::geography) / 10000.0 end as ha
                              from (select ST_SetSRID(ST_GeomFromGeoJSON(:g), 4326) as g) t
                            """).param("g", geojson).query((rs, n) -> {
                Map<String, Object> m = new HashMap<>();
                m.put("valide", rs.getBoolean("valide"));
                m.put("raison", rs.getString("raison"));
                m.put("ha", rs.getObject("ha"));
                return m;
            }).single();
            if (!(Boolean) stats.get("valide")) {
                erreurs.add("Géométrie invalide : " + stats.get("raison") + ".");
            } else {
                superficie = ((Number) stats.get("ha")).doubleValue();
                signaler(cible, geojson, avertissements);
            }
        } catch (org.springframework.dao.DataAccessException e) {
            erreurs.add("Géométrie illisible : anneau non fermé ou trop court.");
        }
        return new Rapport(erreurs, avertissements, superficie, List.of(xMin, yMin, xMax, yMax), geojson, lecture.polygones().size());
    }

    /** Recoupements, débordements et remplacement : des signalements, pas des refus. */
    private void signaler(Cible cible, String geojson, List<String> avertissements) {
        boolean secteur = "SECTEUR".equals(cible.niveau());
        String table = secteur ? "referentiel.secteur" : "referentiel.lot";
        String etiquette = secteur ? "s.code || ' (' || s.nom || ')'" : "'lot ' || s.numero";
        jdbc.sql("""
                        with n as (select ST_SetSRID(ST_GeomFromGeoJSON(:g), 4326) as g)
                        select %s as nom, ST_Area(ST_Intersection(s.geom, n.g)::geography) / ST_Area(n.g::geography) as part
                          from %s s, n
                         where s.id <> :id and s.supprime_le is null and s.geom is not null and ST_Intersects(s.geom, n.g)
                         order by part desc limit 5
                        """.formatted(etiquette, table))
                .param("g", geojson).param("id", cible.id())
                .query((rs, i) -> Map.entry(rs.getString("nom"), rs.getDouble("part"))).list().stream()
                .filter(e -> e.getValue() > SEUIL_SIGNALEMENT)
                .forEach(e -> avertissements.add(String.format(Locale.ROOT, "Recoupe %s %s sur %.1f %% de sa superficie.",
                        secteur ? "le secteur" : "le", e.getKey(), e.getValue() * 100)));
        if (secteur) {
            // Les lots déjà contourés de ce secteur doivent rester dedans.
            jdbc.sql("""
                            with n as (select ST_SetSRID(ST_GeomFromGeoJSON(:g), 4326) as g)
                            select l.numero, 1 - ST_Area(ST_Intersection(l.geom, n.g)::geography) / ST_Area(l.geom::geography) as dehors
                              from referentiel.lot l, n
                             where l.secteur_id = :id and l.supprime_le is null and l.geom is not null
                            """).param("g", geojson).param("id", cible.id())
                    .query((rs, i) -> Map.entry(rs.getInt("numero"), rs.getDouble("dehors"))).list().stream()
                    .filter(e -> e.getValue() > SEUIL_DEBORDEMENT)
                    .forEach(e -> avertissements.add(String.format(Locale.ROOT, "Le lot %d, déjà contouré, déborde de ce secteur sur %.1f %% de sa superficie.",
                            e.getKey(), e.getValue() * 100)));
        } else {
            jdbc.sql("""
                            with n as (select ST_SetSRID(ST_GeomFromGeoJSON(:g), 4326) as g)
                            select sec.code, 1 - ST_Area(ST_Intersection(n.g, sec.geom)::geography) / ST_Area(n.g::geography) as dehors
                              from referentiel.secteur sec, n
                             where sec.id = :secteur and sec.geom is not null
                            """).param("g", geojson).param("secteur", cible.secteurId())
                    .query((rs, i) -> Map.entry(rs.getString("code"), rs.getDouble("dehors"))).list().stream()
                    .filter(e -> e.getValue() > SEUIL_DEBORDEMENT)
                    .forEach(e -> avertissements.add(String.format(Locale.ROOT, "Le lot déborde du secteur %s sur %.1f %% de sa superficie.",
                            e.getKey(), e.getValue() * 100)));
        }
        if (cible.aUnContour()) {
            avertissements.add("Remplace le contour existant (le fichier précédent reste conservé dans l'historique des imports).");
        }
    }

    /** Si le fichier porte des attributs d'identification (code du secteur, numéro du lot), ils doivent désigner la cible choisie. */
    private static void verifierIdentification(Cible cible, Lecture lecture, List<String> erreurs) {
        boolean secteur = "SECTEUR".equals(cible.niveau());
        List<String> cles = secteur ? List.of("code", "code_secteur", "codesecteur", "secteur") : List.of("numero", "num_lot", "numlot", "numero_lot", "lot");
        for (Map<String, String> attrs : lecture.attributs()) {
            for (String cle : cles) {
                String valeur = attrs.get(cle);
                if (valeur != null && !valeur.isBlank() && !memeIdentifiant(valeur, cible.code())) {
                    erreurs.add("Le fichier désigne " + (secteur ? "le secteur « " : "le lot « ") + valeur + " » (attribut « " + cle + " »), mais vous avez choisi "
                            + cible.libelle() + ".");
                    return;
                }
            }
        }
    }

    /** « 05 » et « 5 » désignent le même secteur ; sinon comparaison sans casse ni accents. */
    static boolean memeIdentifiant(String a, String b) {
        String x = a.strip();
        String y = b.strip();
        if (x.matches("\\d+") && y.matches("\\d+")) {
            return Long.parseLong(x) == Long.parseLong(y);
        }
        return plier(x).equals(plier(y));
    }

    private static String plier(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ utilitaires

    private Cible cible(String niveau, UUID id) {
        if ("SECTION".equals(niveau)) {
            throw invalide("Le contour des sections est réservé : seuls les secteurs et les lots reçoivent un contour.");
        }
        if (!"SECTEUR".equals(niveau) && !"LOT".equals(niveau)) {
            throw invalide("Niveau inconnu : attendu SECTEUR ou LOT.");
        }
        return ("SECTEUR".equals(niveau)
                ? jdbc.sql("select id, code, nom, geom is not null as contour from referentiel.secteur where id = :id and supprime_le is null")
                        .param("id", id).query((rs, n) -> new Cible("SECTEUR", id, id, rs.getString("code"), "le secteur " + rs.getString("code") + " (" + rs.getString("nom") + ")", rs.getBoolean("contour")))
                : jdbc.sql("select l.id, l.numero, l.secteur_id, l.geom is not null as contour from referentiel.lot l where l.id = :id and l.supprime_le is null")
                        .param("id", id).query((rs, n) -> new Cible("LOT", id, rs.getObject("secteur_id", UUID.class), String.valueOf(rs.getInt("numero")), "le lot " + rs.getInt("numero"), rs.getBoolean("contour"))))
                .optional().orElseThrow(() -> invalide("Cible inconnue : " + (("SECTEUR".equals(niveau)) ? "secteur" : "lot") + " introuvable."));
    }

    private Lecture lire(String nomFichier, byte[] contenu) {
        try {
            return LectureContours.lire(json, nomFichier, contenu);
        } catch (IllegalArgumentException e) {
            throw invalide(e.getMessage());
        }
    }

    private Map<String, Object> possede(Acteur acteur, UUID id) {
        return jdbc.sql("select id, niveau, secteur_id, lot_id, fichier_nom, fichier_cle, statut, createur_id from referentiel.import_contour where id = :id")
                .param("id", id).query((rs, n) -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("id", rs.getObject("id", UUID.class));
                    m.put("niveau", rs.getString("niveau"));
                    m.put("secteur_id", rs.getObject("secteur_id", UUID.class));
                    m.put("lot_id", rs.getObject("lot_id", UUID.class));
                    m.put("fichier_nom", rs.getString("fichier_nom"));
                    m.put("fichier_cle", rs.getString("fichier_cle"));
                    m.put("statut", rs.getString("statut"));
                    m.put("createur_id", rs.getObject("createur_id", UUID.class));
                    return m;
                }).optional().filter(m -> acteur.compteId().equals(m.get("createur_id")))
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "INTROUVABLE", "Import introuvable."));
    }

    @SuppressWarnings("unchecked")
    private ContourImport lireImport(UUID id) {
        return jdbc.sql("""
                        select i.id, i.niveau, coalesce(i.secteur_id, i.lot_id) as cible_id, i.format, i.fichier_nom, i.statut, i.nb_elements,
                               i.rapport::text as rapport, i.cree_le,
                               case when i.niveau = 'SECTEUR' then (select 'secteur ' || s.code from referentiel.secteur s where s.id = i.secteur_id)
                                    else (select 'lot ' || l.numero from referentiel.lot l where l.id = i.lot_id) end as libelle
                          from referentiel.import_contour i where i.id = :id
                        """).param("id", id)
                .query((rs, n) -> {
                    Map<String, Object> r = rs.getString("rapport") == null ? Map.of() : json.readValue(rs.getString("rapport"), MAP);
                    List<Double> bbox = r.get("emprise") instanceof List<?> l ? l.stream().map(o -> ((Number) o).doubleValue()).toList() : null;
                    return new ContourImport(rs.getObject("id", UUID.class), rs.getString("niveau"), rs.getObject("cible_id", UUID.class),
                            rs.getString("libelle"), rs.getString("format"), rs.getString("fichier_nom"), rs.getString("statut"), rs.getInt("nb_elements"),
                            r.get("superficieHa") instanceof Number num ? num.doubleValue() : null, bbox,
                            (List<String>) r.getOrDefault("erreurs", List.of()), (List<String>) r.getOrDefault("avertissements", List.of()),
                            rs.getObject("cree_le", OffsetDateTime.class));
                }).single();
    }

    private static Map<String, Object> rapportJson(Rapport r) {
        Map<String, Object> m = new HashMap<>();
        m.put("erreurs", r.erreurs());
        m.put("avertissements", r.avertissements());
        m.put("superficieHa", r.superficieHa());
        m.put("emprise", r.emprise());
        return m;
    }

    private static String abreger(String nom) {
        String n = nom == null || nom.isBlank() ? "contour" : nom.strip();
        return n.length() > 255 ? n.substring(n.length() - 255) : n;
    }

    private static String sha256(byte[] contenu) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(contenu));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static BusinessException invalide(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, "REQUETE_INVALIDE", message);
    }

    private static BusinessException conflit(String message) {
        return new BusinessException(HttpStatus.CONFLICT, "CONFLIT_DONNEES", message);
    }
}
