package ci.sysde.iam.compte;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Lecture d'un fichier CSV (RFC 4180) tel que l'exportent Excel ou LibreOffice en France : séparateur « ; » ou « , »
 * ou tabulation (détecté sur l'en-tête), guillemets, guillemets doublés, retours à la ligne dans un champ, BOM UTF-8.
 */
final class CsvLecture {

    /** {@code numero} : ligne du fichier où l'enregistrement commence (l'en-tête est la ligne 1). */
    record Ligne(int numero, Map<String, String> valeurs) {
    }

    record Resultat(List<String> entetes, List<Ligne> lignes) {
    }

    private CsvLecture() {
    }

    static Resultat lire(String texte) {
        if (texte.startsWith("﻿")) {
            texte = texte.substring(1);
        }
        char sep = separateur(texte);
        List<List<String>> enregistrements = new ArrayList<>();
        List<Integer> numeros = new ArrayList<>();
        StringBuilder champ = new StringBuilder();
        List<String> courant = new ArrayList<>();
        boolean guillemets = false;
        boolean champCommence = false;
        int ligne = 1;
        int debutEnregistrement = 1;
        for (int i = 0; i < texte.length(); i++) {
            char c = texte.charAt(i);
            if (guillemets) {
                if (c == '"') {
                    if (i + 1 < texte.length() && texte.charAt(i + 1) == '"') {
                        champ.append('"');
                        i++;
                    } else {
                        guillemets = false;
                    }
                } else {
                    if (c == '\n') {
                        ligne++;
                    }
                    champ.append(c);
                }
            } else if (c == '"' && !champCommence) {
                guillemets = true;
                champCommence = true;
            } else if (c == sep) {
                courant.add(champ.toString().strip());
                champ.setLength(0);
                champCommence = false;
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < texte.length() && texte.charAt(i + 1) == '\n') {
                    i++;
                }
                courant.add(champ.toString().strip());
                champ.setLength(0);
                champCommence = false;
                if (!(courant.size() == 1 && courant.get(0).isEmpty())) {      // lignes vides ignorées
                    enregistrements.add(courant);
                    numeros.add(debutEnregistrement);
                }
                courant = new ArrayList<>();
                ligne++;
                debutEnregistrement = ligne;
            } else {
                champ.append(c);
                champCommence = true;
            }
        }
        if (guillemets) {
            throw new IllegalArgumentException("Guillemet non fermé : le fichier est mal formé.");
        }
        courant.add(champ.toString().strip());
        if (!(courant.size() == 1 && courant.get(0).isEmpty())) {
            enregistrements.add(courant);
            numeros.add(debutEnregistrement);
        }
        if (enregistrements.isEmpty()) {
            throw new IllegalArgumentException("Le fichier est vide.");
        }
        List<String> entetes = enregistrements.get(0).stream().map(CsvLecture::normaliser).toList();
        List<Ligne> lignes = new ArrayList<>();
        for (int r = 1; r < enregistrements.size(); r++) {
            Map<String, String> valeurs = new LinkedHashMap<>();
            List<String> champs = enregistrements.get(r);
            for (int k = 0; k < entetes.size(); k++) {
                valeurs.put(entetes.get(k), k < champs.size() ? champs.get(k) : "");
            }
            lignes.add(new Ligne(numeros.get(r), valeurs));
        }
        return new Resultat(entetes, lignes);
    }

    /** Séparateur le plus fréquent dans la première ligne. */
    private static char separateur(String texte) {
        int fin = texte.indexOf('\n');
        String entete = fin < 0 ? texte : texte.substring(0, fin);
        long pointVirgule = entete.chars().filter(c -> c == ';').count();
        long virgule = entete.chars().filter(c -> c == ',').count();
        long tab = entete.chars().filter(c -> c == '\t').count();
        if (tab > pointVirgule && tab > virgule) {
            return '\t';
        }
        return pointVirgule >= virgule ? ';' : ',';
    }

    /** « Prénoms », « prenoms », « PRÉNOMS » désignent la même colonne. */
    static String normaliser(String entete) {
        String s = Normalizer.normalize(entete, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return s.strip().toLowerCase(Locale.ROOT);
    }
}
