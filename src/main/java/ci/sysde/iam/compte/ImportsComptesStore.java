package ci.sysde.iam.compte;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import ci.sysde.iam.compte.CompteDtos.ImportedAccount;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Accès aux tables d'import. Séparé du service pour que chaque opération ait sa propre transaction (via le proxy) :
 * l'application d'un import traite chaque ligne dans une transaction indépendante, si bien qu'une ligne refusée par la
 * base n'annule pas les autres.
 */
@Service
class ImportsComptesStore {

    /** Une ligne telle que stockée : valeurs du fichier, territoire résolu à la validation, erreurs éventuelles. */
    record LigneStockee(int numero, Map<String, String> valeurs, UUID secteurId, UUID lotId, UUID sectionId,
                        List<String> erreurs, boolean valide) {
    }

    record Entete(UUID id, UUID createurId, String fichier, String empreinte, String statut, int lignes, int valides, int rejetes) {
    }

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    ImportsComptesStore(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional
    UUID creer(UUID createur, String fichier, String empreinte, String statut, List<LigneStockee> lignes) {
        int valides = (int) lignes.stream().filter(LigneStockee::valide).count();
        UUID id = jdbc.sql("""
                        insert into iam.import_compte (createur_id, fichier_nom, empreinte, statut, nb_lignes, nb_valides, nb_rejetes)
                        values (:createur, :fichier, :empreinte, :statut, :n, :v, :r) returning id
                        """)
                .param("createur", createur).param("fichier", fichier).param("empreinte", empreinte).param("statut", statut)
                .param("n", lignes.size()).param("v", valides).param("r", lignes.size() - valides)
                .query(UUID.class).single();
        for (LigneStockee l : lignes) {
            Map<String, Object> donnees = new java.util.LinkedHashMap<>(l.valeurs());
            donnees.put("_secteurId", l.secteurId() == null ? null : l.secteurId().toString());
            donnees.put("_lotId", l.lotId() == null ? null : l.lotId().toString());
            donnees.put("_sectionId", l.sectionId() == null ? null : l.sectionId().toString());
            jdbc.sql("""
                            insert into iam.import_compte_ligne (import_id, numero_ligne, donnees, erreurs, valide)
                            values (:id, :n, cast(:donnees as jsonb), cast(:erreurs as jsonb), :valide)
                            """)
                    .param("id", id).param("n", l.numero()).param("donnees", json.writeValueAsString(donnees))
                    .param("erreurs", l.erreurs().isEmpty() ? null : json.writeValueAsString(l.erreurs()))
                    .param("valide", l.valide()).update();
        }
        return id;
    }

    @Transactional(readOnly = true)
    Optional<Entete> entete(UUID id) {
        return jdbc.sql("select id, createur_id, fichier_nom, empreinte, statut, nb_lignes, nb_valides, nb_rejetes from iam.import_compte where id = :id")
                .param("id", id)
                .query((rs, n) -> new Entete(rs.getObject("id", UUID.class), rs.getObject("createur_id", UUID.class),
                        rs.getString("fichier_nom"), rs.getString("empreinte").strip(), rs.getString("statut"),
                        rs.getInt("nb_lignes"), rs.getInt("nb_valides"), rs.getInt("nb_rejetes")))
                .optional();
    }

    @Transactional(readOnly = true)
    List<LigneStockee> lignes(UUID importId) {
        return jdbc.sql("select numero_ligne, donnees::text as donnees, erreurs::text as erreurs, valide from iam.import_compte_ligne where import_id = :id order by numero_ligne")
                .param("id", importId)
                .query((rs, n) -> {
                    Map<String, Object> d = json.readValue(rs.getString("donnees"), MAP);
                    Map<String, String> valeurs = new java.util.LinkedHashMap<>();
                    d.forEach((k, v) -> {
                        if (!k.startsWith("_")) {
                            valeurs.put(k, v == null ? "" : v.toString());
                        }
                    });
                    List<String> erreurs = rs.getString("erreurs") == null ? List.of()
                            : json.readValue(rs.getString("erreurs"), new TypeReference<List<String>>() { });
                    return new LigneStockee(rs.getInt("numero_ligne"), valeurs, uuid(d.get("_secteurId")), uuid(d.get("_lotId")),
                            uuid(d.get("_sectionId")), erreurs, rs.getBoolean("valide"));
                })
                .list();
    }

    /** Une transaction par ligne : la base peut encore refuser une ligne (un seul moniteur par section, par exemple). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    ImportedAccount appliquerLigne(UUID operateur, LigneStockee l, String profil, String hash, String motDePasse) {
        Map<String, String> v = l.valeurs();
        UUID utilisateur = jdbc.sql("insert into iam.utilisateur (nom, prenoms, email, telephone) values (:n, :p, :e, :t) returning id")
                .param("n", v.get("nom")).param("p", v.get("prenoms")).param("e", vide(v.get("email"))).param("t", vide(v.get("telephone")))
                .query(UUID.class).single();
        UUID compte = jdbc.sql("""
                        insert into iam.compte (utilisateur_id, operateur_id, profil, numero, statut, mot_de_passe_hash, doit_changer_mot_de_passe)
                        values (:u, :o, :p, :numero, 'ACTIF', :hash, true) returning id
                        """)
                .param("u", utilisateur).param("o", operateur).param("p", profil).param("numero", vide(v.get("numero")))
                .param("hash", hash).query(UUID.class).single();
        if (l.secteurId() != null) {
            jdbc.sql("insert into iam.affectation (compte_id, operateur_id, secteur_id, lot_id, section_id) values (:c, :o, :s, :l, :x)")
                    .param("c", compte).param("o", operateur).param("s", l.secteurId()).param("l", l.lotId()).param("x", l.sectionId())
                    .update();
        }
        return jdbc.sql("select matricule, reference_compte from iam.compte where id = :id").param("id", compte)
                .query((rs, n) -> new ImportedAccount(l.numero(), compte, rs.getString("matricule"), rs.getString("reference_compte"),
                        v.get("nom"), v.get("prenoms"), motDePasse))
                .single();
    }

    @Transactional
    void terminer(UUID id, String statut, int crees, int rejetes, Map<Integer, String> erreursApplication) {
        erreursApplication.forEach((ligne, message) -> {
            try {
                jdbc.sql("update iam.import_compte_ligne set valide = false, erreurs = cast(:e as jsonb) where import_id = :id and numero_ligne = :n")
                        .param("e", json.writeValueAsString(List.of(message))).param("id", id).param("n", ligne).update();
            } catch (RuntimeException e) {
                throw new IllegalStateException(e);
            }
        });
        jdbc.sql("update iam.import_compte set statut = :s, nb_valides = :v, nb_rejetes = :r, termine_le = now() where id = :id")
                .param("s", statut).param("v", crees).param("r", rejetes).param("id", id).update();
    }

    /** Fichier déjà appliqué par quelqu'un du même opérateur : on ne crée pas deux fois les mêmes comptes. */
    @Transactional(readOnly = true)
    boolean dejaApplique(String empreinte, UUID operateur) {
        return jdbc.sql("""
                        select exists (select 1 from iam.import_compte i join iam.compte c on c.id = i.createur_id
                                        where i.empreinte = :e and i.statut = 'APPLIQUE' and c.operateur_id = :o)
                        """).param("e", empreinte).param("o", operateur).query(Boolean.class).single();
    }

    private static UUID uuid(Object o) {
        return o == null ? null : UUID.fromString(o.toString());
    }

    private static String vide(String t) {
        return t == null || t.isBlank() ? null : t.strip();
    }

    static List<String> erreurs(String... messages) {
        return new ArrayList<>(List.of(messages));
    }
}
