package ci.sysde.contour;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import tools.jackson.databind.ObjectMapper;

/**
 * Lecture des fichiers de contours : GeoJSON (Polygon, MultiPolygon, Feature, FeatureCollection) et shapefile zippé (.shp polygones,
 * .dbf attributs, .prj système de coordonnées). Aucune dépendance : le format des polygones est simple et fermé. Les
 * fichiers illisibles lèvent {@link IllegalArgumentException} (message en français) ; les problèmes de fond (système de
 * coordonnées non WGS 84…) sont rendus en {@code erreurs} pour figurer au rapport.
 */
final class LectureContours {

    /** Un polygone : un anneau extérieur puis des trous, chaque anneau = liste de points {x (longitude), y (latitude)}. */
    record Polygone(List<List<double[]>> anneaux) {
    }

    record Lecture(String format, List<Polygone> polygones, List<Map<String, String>> attributs, List<String> erreurs,
                   List<String> avertissements, int sommets) {
    }

    static final int SOMMETS_MAX = 500_000;
    private static final int OCTETS_MAX = 60_000_000;       // somme des entrées d'un zip, contre les bombes de décompression

    private LectureContours() {
    }

    static Lecture lire(ObjectMapper json, String nomFichier, byte[] contenu) {
        String nom = nomFichier == null ? "" : nomFichier.toLowerCase(Locale.ROOT);
        boolean zip = nom.endsWith(".zip") || (contenu.length > 3 && contenu[0] == 'P' && contenu[1] == 'K');
        Lecture l = zip ? shapefile(contenu) : geojson(json, contenu);
        if (l.sommets() > SOMMETS_MAX) {
            throw new IllegalArgumentException("Le fichier compte plus de " + SOMMETS_MAX + " points : simplifiez le contour.");
        }
        return l;
    }

    /** GeoJSON d'un MultiPolygon unique réunissant tous les polygones du fichier. */
    static String multiPolygone(Lecture l) {
        StringBuilder sb = new StringBuilder("{\"type\":\"MultiPolygon\",\"coordinates\":[");
        boolean premier = true;
        for (Polygone p : l.polygones()) {
            sb.append(premier ? "" : ",").append('[');
            premier = false;
            boolean premierAnneau = true;
            for (List<double[]> anneau : p.anneaux()) {
                sb.append(premierAnneau ? "" : ",").append('[');
                premierAnneau = false;
                for (int i = 0; i < anneau.size(); i++) {
                    sb.append(i == 0 ? "" : ",").append('[').append(anneau.get(i)[0]).append(',').append(anneau.get(i)[1]).append(']');
                }
                sb.append(']');
            }
            sb.append(']');
        }
        return sb.append("]}").toString();
    }

    // ------------------------------------------------------------------ GeoJSON

    @SuppressWarnings("unchecked")
    private static Lecture geojson(ObjectMapper json, byte[] contenu) {
        Object racine;
        try {
            racine = json.readValue(new String(contenu, StandardCharsets.UTF_8), Object.class);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Le fichier n'est ni un GeoJSON ni un shapefile zippé lisible.");
        }
        if (!(racine instanceof Map<?, ?> m)) {
            throw new IllegalArgumentException("GeoJSON invalide : un objet est attendu.");
        }
        Map<String, Object> doc = (Map<String, Object>) m;
        List<String> erreurs = new ArrayList<>();
        Object crs = doc.get("crs");
        if (crs != null) {
            String texte = String.valueOf(crs);
            if (!texte.contains("4326") && !texte.toUpperCase(Locale.ROOT).contains("CRS84")) {
                erreurs.add("Le fichier déclare un système de coordonnées autre que WGS 84 (EPSG:4326) : " + texte + ".");
            }
        }
        List<Polygone> polygones = new ArrayList<>();
        List<Map<String, String>> attributs = new ArrayList<>();
        int[] sommets = {0};
        String type = String.valueOf(doc.get("type"));
        switch (type) {
            case "FeatureCollection" -> {
                Object features = doc.get("features");
                if (!(features instanceof List<?> liste)) {
                    throw new IllegalArgumentException("GeoJSON invalide : « features » est attendu.");
                }
                for (Object f : liste) {
                    feature((Map<String, Object>) f, polygones, attributs, sommets);
                }
            }
            case "Feature" -> feature(doc, polygones, attributs, sommets);
            default -> geometrie(doc, polygones, sommets);
        }
        return new Lecture("GEOJSON", polygones, attributs, erreurs, new ArrayList<>(), sommets[0]);
    }

    @SuppressWarnings("unchecked")
    private static void feature(Map<String, Object> feature, List<Polygone> polygones, List<Map<String, String>> attributs, int[] sommets) {
        Object geometrie = feature.get("geometry");
        if (!(geometrie instanceof Map<?, ?> g)) {
            return;                                                  // entité sans géométrie : ignorée
        }
        int avant = polygones.size();
        geometrie((Map<String, Object>) g, polygones, sommets);
        if (polygones.size() > avant) {
            Map<String, String> proprietes = new LinkedHashMap<>();
            if (feature.get("properties") instanceof Map<?, ?> p) {
                p.forEach((k, v) -> proprietes.put(String.valueOf(k).toLowerCase(Locale.ROOT), v == null ? "" : String.valueOf(v).strip()));
            }
            attributs.add(proprietes);
        }
    }

    @SuppressWarnings("unchecked")
    private static void geometrie(Map<String, Object> g, List<Polygone> polygones, int[] sommets) {
        String type = String.valueOf(g.get("type"));
        Object coords = g.get("coordinates");
        switch (type) {
            case "Polygon" -> polygones.add(polygone(coords, sommets));
            case "MultiPolygon" -> {
                if (!(coords instanceof List<?> liste)) {
                    throw new IllegalArgumentException("GeoJSON invalide : coordonnées de MultiPolygon illisibles.");
                }
                for (Object p : liste) {
                    polygones.add(polygone(p, sommets));
                }
            }
            default -> throw new IllegalArgumentException("Le fichier doit contenir des polygones (Polygon ou MultiPolygon), pas des « " + type + " ».");
        }
    }

    private static Polygone polygone(Object coords, int[] sommets) {
        if (!(coords instanceof List<?> anneaux) || anneaux.isEmpty()) {
            throw new IllegalArgumentException("GeoJSON invalide : polygone sans anneau.");
        }
        List<List<double[]>> sortie = new ArrayList<>();
        for (Object a : anneaux) {
            if (!(a instanceof List<?> points)) {
                throw new IllegalArgumentException("GeoJSON invalide : anneau illisible.");
            }
            List<double[]> anneau = new ArrayList<>();
            for (Object pt : points) {
                if (!(pt instanceof List<?> xy) || xy.size() < 2 || !(xy.get(0) instanceof Number x) || !(xy.get(1) instanceof Number y)) {
                    throw new IllegalArgumentException("GeoJSON invalide : point illisible.");
                }
                anneau.add(new double[] {x.doubleValue(), y.doubleValue()});
            }
            sommets[0] += anneau.size();
            sortie.add(anneau);
        }
        return new Polygone(sortie);
    }

    // ------------------------------------------------------------------ shapefile

    private static Lecture shapefile(byte[] zip) {
        Map<String, byte[]> entrees = decompresser(zip);
        byte[] shp = trouver(entrees, ".shp");
        if (shp == null) {
            throw new IllegalArgumentException("Archive sans fichier .shp : un shapefile zippé contient au moins .shp, .dbf et .prj.");
        }
        List<String> erreurs = new ArrayList<>();
        List<String> avertissements = new ArrayList<>();
        byte[] prj = trouver(entrees, ".prj");
        if (prj == null) {
            avertissements.add("Fichier .prj absent : le système de coordonnées WGS 84 n'a pas pu être confirmé.");
        } else {
            String texte = new String(prj, StandardCharsets.UTF_8).strip();
            if (!texte.startsWith("GEOGCS") || !texte.toUpperCase(Locale.ROOT).contains("WGS")) {
                erreurs.add("Le système de coordonnées du fichier n'est pas WGS 84 (EPSG:4326) : " + texte.substring(0, Math.min(80, texte.length())) + ".");
            }
        }
        byte[] dbf = trouver(entrees, ".dbf");
        List<Map<String, String>> table = dbf == null ? List.of() : dbf(dbf);

        List<Polygone> polygones = new ArrayList<>();
        List<Map<String, String>> attributs = new ArrayList<>();
        int[] sommets = {0};
        ByteBuffer b = ByteBuffer.wrap(shp);
        if (shp.length < 100 || b.order(ByteOrder.BIG_ENDIAN).getInt(0) != 9994) {
            throw new IllegalArgumentException("Fichier .shp illisible (en-tête invalide).");
        }
        int pos = 100;
        int enregistrement = 0;
        while (pos + 12 <= shp.length) {
            int octets = b.order(ByteOrder.BIG_ENDIAN).getInt(pos + 4) * 2;
            int base = pos + 8;
            if (octets < 4 || base + octets > shp.length) {
                throw new IllegalArgumentException("Fichier .shp tronqué ou corrompu.");
            }
            int type = b.order(ByteOrder.LITTLE_ENDIAN).getInt(base);
            if (type != 0) {
                if (type != 5 && type != 15 && type != 25) {
                    throw new IllegalArgumentException("Le fichier doit contenir des polygones (type de forme " + type + " non accepté).");
                }
                int avant = polygones.size();
                polygonesDeLEnregistrement(b, base, octets, polygones, sommets);
                if (polygones.size() > avant) {
                    attributs.add(enregistrement < table.size() ? table.get(enregistrement) : Map.of());
                }
            }
            enregistrement++;
            pos = base + octets;
        }
        return new Lecture("SHAPEFILE", polygones, attributs, erreurs, avertissements, sommets[0]);
    }

    private static void polygonesDeLEnregistrement(ByteBuffer b, int base, int octets, List<Polygone> polygones, int[] sommets) {
        b.order(ByteOrder.LITTLE_ENDIAN);
        int nbParties = b.getInt(base + 36);
        int nbPoints = b.getInt(base + 40);
        if (nbParties < 0 || nbPoints < 0 || base + 44 + 4L * nbParties + 16L * nbPoints > base + octets) {
            throw new IllegalArgumentException("Fichier .shp corrompu (nombre de points incohérent).");
        }
        int[] parties = new int[nbParties + 1];
        for (int i = 0; i < nbParties; i++) {
            parties[i] = b.getInt(base + 44 + 4 * i);
        }
        parties[nbParties] = nbPoints;
        int debutPoints = base + 44 + 4 * nbParties;
        List<List<double[]>> courant = null;
        for (int p = 0; p < nbParties; p++) {
            List<double[]> anneau = new ArrayList<>();
            for (int i = parties[p]; i < parties[p + 1]; i++) {
                anneau.add(new double[] {b.getDouble(debutPoints + 16 * i), b.getDouble(debutPoints + 16 * i + 8)});
            }
            sommets[0] += anneau.size();
            // Un anneau horaire est extérieur, un anneau anti-horaire est un trou du polygone précédent.
            if (aireSignee(anneau) < 0 || courant == null) {
                courant = new ArrayList<>();
                polygones.add(new Polygone(courant));
            }
            courant.add(anneau);
        }
    }

    private static double aireSignee(List<double[]> anneau) {
        double somme = 0;
        for (int i = 0; i < anneau.size() - 1; i++) {
            somme += anneau.get(i)[0] * anneau.get(i + 1)[1] - anneau.get(i + 1)[0] * anneau.get(i)[1];
        }
        return somme / 2;
    }

    /** Attributs du .dbf : une table (nom de colonne en minuscules → valeur) par enregistrement. */
    private static List<Map<String, String>> dbf(byte[] d) {
        if (d.length < 33) {
            return List.of();
        }
        ByteBuffer b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
        int nb = b.getInt(4);
        int entete = Short.toUnsignedInt(b.getShort(8));
        int longueur = Short.toUnsignedInt(b.getShort(10));
        List<String> noms = new ArrayList<>();
        List<Integer> largeurs = new ArrayList<>();
        for (int pos = 32; pos + 32 <= entete && d[pos] != 0x0D; pos += 32) {
            int fin = pos;
            while (fin < pos + 11 && d[fin] != 0) {
                fin++;
            }
            noms.add(new String(d, pos, fin - pos, StandardCharsets.ISO_8859_1).strip().toLowerCase(Locale.ROOT));
            largeurs.add(Byte.toUnsignedInt(d[pos + 16]));
        }
        List<Map<String, String>> table = new ArrayList<>();
        for (int r = 0; r < nb; r++) {
            int debut = entete + r * longueur;
            if (debut + longueur > d.length) {
                break;
            }
            Map<String, String> ligne = new HashMap<>();
            int pos = debut + 1;                                        // octet de suppression
            for (int k = 0; k < noms.size(); k++) {
                ligne.put(noms.get(k), new String(d, pos, largeurs.get(k), StandardCharsets.ISO_8859_1).strip());
                pos += largeurs.get(k);
            }
            table.add(ligne);
        }
        return table;
    }

    private static Map<String, byte[]> decompresser(byte[] zip) {
        Map<String, byte[]> entrees = new HashMap<>();
        long total = 0;
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            int nb = 0;
            while ((e = in.getNextEntry()) != null) {
                if (e.isDirectory()) {
                    continue;
                }
                if (++nb > 100) {
                    throw new IllegalArgumentException("Archive trop volumineuse (trop de fichiers).");
                }
                ByteArrayOutputStream sortie = new ByteArrayOutputStream();
                byte[] tampon = new byte[8192];
                int n;
                while ((n = in.read(tampon)) > 0) {
                    total += n;
                    if (total > OCTETS_MAX) {
                        throw new IllegalArgumentException("Archive trop volumineuse une fois décompressée.");
                    }
                    sortie.write(tampon, 0, n);
                }
                entrees.put(e.getName().toLowerCase(Locale.ROOT), sortie.toByteArray());
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Archive zip illisible.");
        }
        return entrees;
    }

    private static byte[] trouver(Map<String, byte[]> entrees, String extension) {
        return entrees.entrySet().stream().filter(e -> e.getKey().endsWith(extension)).map(Map.Entry::getValue).findFirst().orElse(null);
    }
}
