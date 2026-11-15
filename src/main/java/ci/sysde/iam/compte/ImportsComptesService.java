package ci.sysde.iam.compte;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import ci.sysde.common.BusinessException;
import ci.sysde.iam.compte.CompteDtos.ImportError;
import ci.sysde.iam.compte.CompteDtos.ImportResult;
import ci.sysde.iam.compte.CompteDtos.ImportSummary;
import ci.sysde.iam.compte.CompteDtos.ImportedAccount;
import ci.sysde.iam.compte.ImportsComptesStore.LigneStockee;
import ci.sysde.security.Acteur;
import ci.sysde.security.Perimetre;
import ci.sysde.security.PerimetreService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Import CSV de comptes (COM-09) : le fichier est contrôlé en entier et le rapport donne les erreurs ligne par ligne ;
 * le responsable confirme, puis les lignes valides sont appliquées et les lignes rejetées sont fournies dans un fichier
 * à corriger. L'application est faite ligne par ligne (une transaction chacune) : la base reste juge en dernier ressort.
 */
@Service
class ImportsComptesService {

    static final int LIGNES_MAX = 5000;
    private static final int ERREURS_AFFICHEES = 200;
    static final List<String> COLONNES = List.of("nom", "prenoms", "email", "telephone", "profil", "numero", "secteur", "lot", "section");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    /** Mots de passe temporaires : 12 caractères aléatoires, changés dès la première connexion ; coût réduit pour tenir 1 000 comptes. */
    private static final BCryptPasswordEncoder ENCODEUR_TEMPORAIRE = new BCryptPasswordEncoder(10);

    private final JdbcClient jdbc;
    private final ImportsComptesStore store;
    private final PerimetreService perimetres;

    ImportsComptesService(JdbcClient jdbc, ImportsComptesStore store, PerimetreService perimetres) {
        this.jdbc = jdbc;
        this.store = store;
        this.perimetres = perimetres;
    }

    // ------------------------------------------------------------------ modèle et rapport

    static String modele() {
        return "﻿" + String.join(";", COLONNES) + "\n"
                + "KONE;Awa;awa.kone@exemple.ci;0102030405;MO;;01;1;Section Nord\n";
    }

    // ------------------------------------------------------------------ validation

    ImportSummary valider(Acteur acteur, String nomFichier, byte[] contenu) {
        String texte = decoder(contenu);
        CsvLecture.Resultat csv;
        try {
            csv = CsvLecture.lire(texte);
        } catch (IllegalArgumentException e) {
            throw invalide(e.getMessage());
        }
        for (String requise : List.of("nom", "prenoms", "profil")) {
            if (!csv.entetes().contains(requise)) {
                throw invalide("Colonne absente : « " + requise + " ». Colonnes attendues : " + String.join(", ", COLONNES) + ".");
            }
        }
        if (csv.lignes().isEmpty()) {
            throw invalide("Le fichier ne contient aucune ligne de données.");
        }
        if (csv.lignes().size() > LIGNES_MAX) {
            throw invalide("Le fichier dépasse " + LIGNES_MAX + " lignes : découpez-le.");
        }
        String empreinte = sha256(contenu);
        if (store.dejaApplique(empreinte, acteur.operateurId())) {
            throw new BusinessException(HttpStatus.CONFLICT, "CONFLIT_DONNEES", "Ce fichier a déjà été importé : les comptes existent.");
        }
        List<LigneStockee> lignes = controler(acteur, csv);
        long valides = lignes.stream().filter(LigneStockee::valide).count();
        UUID id = store.creer(acteur.compteId(), nomFichier, empreinte, valides > 0 ? "PRET" : "ANNULE", lignes);
        return resume(store.entete(id).orElseThrow(), lignes);
    }

    /** Contrôle de toutes les lignes : champs, doublons dans le fichier et en base, territoire dans le secteur de l'appelant. */
    private List<LigneStockee> controler(Acteur acteur, CsvLecture.Resultat csv) {
        UUID operateur = acteur.operateurId();
        Perimetre perimetre = perimetres.de(acteur);
        Map<String, String> niveaux = new HashMap<>();
        jdbc.sql("select code, niveau from iam.profil where createur_profil = :p and code <> :p").param("p", acteur.profil())
                .query((rs, n) -> niveaux.put(rs.getString("code").strip(), rs.getString("niveau"))).list();

        Set<String> emailsFichier = new HashSet<>();
        Set<String> numerosFichier = new HashSet<>();
        Set<String> emailsPris = emailsExistants(csv);
        Set<String> numerosPris = numerosExistants(operateur, csv);
        Map<String, UUID> secteurs = new HashMap<>();
        Map<String, UUID> lots = new HashMap<>();
        Map<String, UUID> sections = new HashMap<>();

        List<LigneStockee> sortie = new ArrayList<>();
        for (CsvLecture.Ligne ligne : csv.lignes()) {
            Map<String, String> v = new java.util.LinkedHashMap<>();
            COLONNES.forEach(c -> v.put(c, ligne.valeurs().getOrDefault(c, "")));
            List<String> erreurs = new ArrayList<>();
            UUID secteurId = null;
            UUID lotId = null;
            UUID sectionId = null;

            exiger(erreurs, v.get("nom"), "nom", 100);
            exiger(erreurs, v.get("prenoms"), "prénoms", 150);
            if (v.get("telephone").length() > 30) {
                erreurs.add("Téléphone trop long (30 caractères au plus).");
            }
            String profil = v.get("profil").toUpperCase(Locale.ROOT);
            v.put("profil", profil);
            String niveau = niveaux.get(profil);
            if (profil.isEmpty()) {
                erreurs.add("Profil obligatoire.");
            } else if (niveau == null) {
                erreurs.add("Profil inconnu ou que vous ne pouvez pas créer : « " + profil + " » (autorisés : " + String.join(", ", new java.util.TreeSet<>(niveaux.keySet())) + ").");
            }
            String email = v.get("email").toLowerCase(Locale.ROOT);
            if (!email.isEmpty()) {
                if (!EMAIL.matcher(email).matches() || email.length() > 150) {
                    erreurs.add("E-mail invalide.");
                } else if (!emailsFichier.add(email)) {
                    erreurs.add("E-mail en double dans le fichier.");
                } else if (emailsPris.contains(email)) {
                    erreurs.add("E-mail déjà utilisé par une autre personne.");
                }
            }
            String numero = v.get("numero");
            if (!numero.isEmpty()) {
                if (!numero.matches("[0-9]{5,7}")) {
                    erreurs.add("Numéro invalide : 5 à 7 chiffres.");
                } else if (!numerosFichier.add(numero)) {
                    erreurs.add("Numéro en double dans le fichier.");
                } else if (numerosPris.contains(numero)) {
                    erreurs.add("Numéro déjà pris par un compte ou un ancien matricule de l'opérateur.");
                }
            }

            // territoire : secteur (code), lot (numéro), section (nom)
            String sCode = v.get("secteur");
            String lNum = v.get("lot");
            String sNom = v.get("section");
            boolean territoire = !(sCode.isEmpty() && lNum.isEmpty() && sNom.isEmpty());
            if (territoire) {
                if (sCode.isEmpty()) {
                    erreurs.add("Le secteur est obligatoire dès qu'un lot ou une section est donné.");
                } else {
                    secteurId = secteurs.computeIfAbsent(sCode, c -> jdbc.sql("select id from referentiel.secteur where code = :c and supprime_le is null")
                            .param("c", c).query(UUID.class).optional().orElse(null));
                    if (secteurId == null) {
                        erreurs.add("Secteur inconnu : « " + sCode + " ».");
                    } else if (perimetre.etendue() == Perimetre.Etendue.AFFECTATIONS && !perimetre.secteursEntiers().contains(secteurId)) {
                        erreurs.add("Le secteur « " + sCode + " » n'est pas le vôtre.");
                        secteurId = null;
                    }
                }
                if (secteurId != null && !lNum.isEmpty()) {
                    if (!lNum.matches("[0-9]{1,4}")) {
                        erreurs.add("Numéro de lot invalide.");
                    } else {
                        UUID s = secteurId;
                        lotId = lots.computeIfAbsent(s + "/" + lNum, k -> jdbc.sql("""
                                        select l.id from referentiel.lot l
                                         where l.secteur_id = :s and l.numero = :n and l.supprime_le is null
                                           and exists (select 1 from gestion.attribution_lot al join gestion.contrat c on c.id = al.contrat_id
                                                        where al.lot_id = l.id and c.operateur_id = :o and al.periode @> current_date)
                                        """).param("s", s).param("n", Integer.parseInt(lNum)).param("o", operateur)
                                .query(UUID.class).optional().orElse(null));
                        if (lotId == null) {
                            erreurs.add("Lot " + lNum + " absent de ce secteur ou non confié à votre opérateur.");
                        }
                    }
                }
                if (!sNom.isEmpty()) {
                    if (lNum.isEmpty()) {
                        erreurs.add("La section exige le lot.");
                    } else if (lotId != null) {
                        UUID l = lotId;
                        sectionId = sections.computeIfAbsent(l + "/" + sNom.toLowerCase(Locale.ROOT), k -> jdbc.sql("""
                                        select id from referentiel.section where lot_id = :l and supprime_le is null
                                           and util.sans_accent(nom) = util.sans_accent(:n)
                                        """).param("l", l).param("n", sNom).query(UUID.class).optional().orElse(null));
                        if (sectionId == null) {
                            erreurs.add("Section « " + sNom + " » inconnue dans ce lot.");
                        }
                    }
                }
            }
            // cohérence profil / niveau d'affectation (la base la revérifiera)
            if (niveau != null) {
                switch (niveau) {
                    case "SECTEUR" -> {
                        if (!lNum.isEmpty() || !sNom.isEmpty()) {
                            erreurs.add("Le profil " + profil + " est affecté à un secteur entier : ni lot ni section.");
                        }
                    }
                    case "LOT" -> {
                        if (territoire && (lNum.isEmpty() || !sNom.isEmpty())) {
                            erreurs.add("Le profil " + profil + " est affecté à un lot : lot obligatoire, pas de section.");
                        }
                    }
                    case "SECTION" -> {
                        if (territoire && sNom.isEmpty()) {
                            erreurs.add("Le profil " + profil + " est affecté à des sections : section obligatoire.");
                        }
                    }
                    default -> {
                        if (territoire) {
                            erreurs.add("Le profil " + profil + " n'a pas d'affectation territoriale.");
                        }
                    }
                }
            }
            boolean valide = erreurs.isEmpty();
            sortie.add(new LigneStockee(ligne.numero(), v, valide ? secteurId : null, valide ? lotId : null,
                    valide ? sectionId : null, erreurs, valide));
        }
        return sortie;
    }

    // ------------------------------------------------------------------ lecture, annulation, application

    ImportSummary lire(Acteur acteur, UUID id) {
        var entete = possede(acteur, id);
        return resume(entete, store.lignes(id));
    }

    ImportSummary annuler(Acteur acteur, UUID id) {
        var entete = possede(acteur, id);
        if (!"PRET".equals(entete.statut())) {
            throw conflit("Seul un import prêt peut être annulé.");
        }
        store.terminer(id, "ANNULE", 0, entete.lignes(), Map.of());
        return lire(acteur, id);
    }

    /** Crée les comptes des lignes valides. Les mots de passe temporaires ne sont renvoyés qu'ici, une seule fois. */
    ImportResult appliquer(Acteur acteur, UUID id) {
        var entete = possede(acteur, id);
        if (!"PRET".equals(entete.statut())) {
            throw conflit("Cet import n'est pas prêt à être appliqué (statut : " + entete.statut() + ").");
        }
        List<LigneStockee> aCreer = store.lignes(id).stream().filter(LigneStockee::valide).toList();
        List<String> motsDePasse = aCreer.stream().map(l -> MotDePasseTemporaire.generer()).toList();
        // Le hachage est coûteux : en parallèle (1 000 comptes en quelques secondes plutôt qu'en minutes).
        List<String> hash = motsDePasse.parallelStream().map(ENCODEUR_TEMPORAIRE::encode).toList();

        List<ImportedAccount> crees = new ArrayList<>();
        Map<Integer, String> echecs = new java.util.TreeMap<>();
        for (int i = 0; i < aCreer.size(); i++) {
            LigneStockee l = aCreer.get(i);
            try {
                crees.add(store.appliquerLigne(acteur.operateurId(), l, l.valeurs().get("profil"), hash.get(i), motsDePasse.get(i)));
            } catch (RuntimeException e) {
                echecs.put(l.numero(), message(e));
            }
        }
        store.terminer(id, "APPLIQUE", crees.size(), entete.lignes() - crees.size(), echecs);
        List<ImportError> erreurs = new ArrayList<>();
        store.lignes(id).stream().filter(l -> !l.valide()).forEach(l -> l.erreurs().forEach(m -> erreurs.add(new ImportError(l.numero(), m))));
        return new ImportResult(id, crees.size(), entete.lignes() - crees.size(), crees, erreurs);
    }

    /** Fichier à corriger : les lignes rejetées, avec leur motif, dans le même format que le modèle. */
    String rejets(Acteur acteur, UUID id) {
        possede(acteur, id);
        StringBuilder sb = new StringBuilder("﻿").append(String.join(";", COLONNES)).append(";erreur\n");
        for (LigneStockee l : store.lignes(id)) {
            if (!l.valide()) {
                COLONNES.forEach(c -> sb.append(csv(l.valeurs().getOrDefault(c, ""))).append(';'));
                sb.append(csv(String.join(" | ", l.erreurs()))).append('\n');
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ utilitaires

    private ImportsComptesStore.Entete possede(Acteur acteur, UUID id) {
        return store.entete(id).filter(e -> e.createurId().equals(acteur.compteId()))
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "INTROUVABLE", "Import introuvable."));
    }

    private static ImportSummary resume(ImportsComptesStore.Entete e, List<LigneStockee> lignes) {
        List<ImportError> erreurs = new ArrayList<>();
        for (LigneStockee l : lignes) {
            for (String m : l.erreurs()) {
                if (erreurs.size() < ERREURS_AFFICHEES) {
                    erreurs.add(new ImportError(l.numero(), m));
                }
            }
        }
        return new ImportSummary(e.id(), e.fichier(), e.statut(), e.lignes(), e.valides(), e.rejetes(), erreurs);
    }

    private Set<String> emailsExistants(CsvLecture.Resultat csv) {
        List<String> emails = csv.lignes().stream().map(l -> l.valeurs().getOrDefault("email", "").toLowerCase(Locale.ROOT))
                .filter(e -> !e.isBlank()).distinct().toList();
        if (emails.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.sql("select lower(email) from iam.utilisateur where supprime_le is null and lower(email) in (:e)")
                .param("e", emails).query(String.class).list());
    }

    private Set<String> numerosExistants(UUID operateur, CsvLecture.Resultat csv) {
        List<String> numeros = csv.lignes().stream().map(l -> l.valeurs().getOrDefault("numero", "")).filter(n -> n.matches("[0-9]{5,7}")).distinct().toList();
        if (numeros.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.sql("""
                        select numero from iam.compte where operateur_id = :o and numero in (:n)
                        union select ancien_numero from iam.alias_matricule where operateur_id = :o and ancien_numero in (:n)
                        """).param("o", operateur).param("n", numeros).query(String.class).list());
    }

    private static void exiger(List<String> erreurs, String valeur, String nom, int max) {
        if (valeur.isBlank()) {
            erreurs.add("Le champ « " + nom + " » est obligatoire.");
        } else if (valeur.length() > max) {
            erreurs.add("Le champ « " + nom + " » dépasse " + max + " caractères.");
        }
    }

    private static String decoder(byte[] contenu) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(contenu)).toString();
        } catch (CharacterCodingException e) {
            throw invalide("Le fichier doit être encodé en UTF-8 (dans Excel : « CSV UTF-8 »).");
        }
    }

    private static String sha256(byte[] contenu) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(contenu));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String csv(String valeur) {
        return valeur.contains(";") || valeur.contains("\"") || valeur.contains("\n") ? "\"" + valeur.replace("\"", "\"\"") + "\"" : valeur;
    }

    /** Message lisible d'un échec à l'application : celui du déclencheur de la base s'il est métier, sinon un texte générique. */
    private static String message(Throwable e) {
        if (e instanceof BusinessException b) {
            return b.getMessage();
        }
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        String m = t.getMessage() == null ? "" : t.getMessage();
        String ligne = m.contains("\n") ? m.substring(0, m.indexOf('\n')) : m;
        ligne = ligne.replaceFirst("^(ERROR|ERREUR)\\s*:\\s*", "").trim();
        String bas = ligne.toLowerCase(Locale.ROOT);
        if (ligne.isEmpty() || bas.contains("violates") || bas.contains("duplicate key") || bas.contains("violation")) {
            return "La ligne viole une règle de gestion (valeur déjà existante ou invalide).";
        }
        return ligne;
    }

    private static BusinessException invalide(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, "REQUETE_INVALIDE", message);
    }

    private static BusinessException conflit(String message) {
        return new BusinessException(HttpStatus.CONFLICT, "CONFLIT_DONNEES", message);
    }
}
