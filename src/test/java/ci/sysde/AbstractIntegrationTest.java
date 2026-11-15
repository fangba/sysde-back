package ci.sysde;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import ci.sysde.security.EtatCompteService;
import ci.sysde.security.JwtService;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base des tests d'intégration : vraie base (PostgreSQL 18 + PostGIS, migrations Flyway réelles), serveur HTTP réel,
 * jetons de vrais comptes (le serveur relit profil et permissions en base, PRO-17).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractIntegrationTest {

    /** Un seul conteneur PostGIS pour toutes les classes de test : démarré une fois, arrêté avec la JVM (Ryuk). */
    static final PostgreSQLContainer DB = new PostgreSQLContainer(
            DockerImageName.parse("postgis/postgis:18-3.6").asCompatibleSubstituteFor("postgres"));

    /** Stockage des fichiers importés (contours) : un répertoire temporaire pour toute la JVM de test. */
    protected static final java.nio.file.Path STOCKAGE;

    static {
        DB.start();
        try {
            STOCKAGE = java.nio.file.Files.createTempDirectory("sysde-stockage");
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void base(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", DB::getJdbcUrl);
        r.add("spring.datasource.username", DB::getUsername);
        r.add("spring.datasource.password", DB::getPassword);
        r.add("spring.flyway.url", DB::getJdbcUrl);
        r.add("spring.flyway.user", DB::getUsername);
        r.add("spring.flyway.password", DB::getPassword);
        r.add("sysde.stockage.repertoire", STOCKAGE::toString);
        r.add("sysde.securite.debit.max", () -> "100000");     // les tests ouvrent des centaines de sessions depuis la même adresse
    }

    @LocalServerPort
    protected int port;
    @Autowired
    protected JdbcClient jdbc;
    @Autowired
    protected JwtService jwt;
    @Autowired
    protected TransactionTemplate tx;
    @Autowired
    protected EtatCompteService etats;

    protected static UUID adminId;
    protected static UUID fircaId;

    @BeforeAll
    static void amorcer(@Autowired JdbcClient jdbc) {
        if (adminId != null) {
            return;                                   // déjà fait par une autre classe de test (même base)
        }
        // Sans compte connecté, la base n'accepte que la création d'un administrateur (amorçage).
        fircaId = jdbc.sql("select id from referentiel.operateur where acronyme = 'FIRCA'").query(UUID.class).single();
        UUID utilisateur = jdbc.sql("insert into iam.utilisateur (nom, prenoms, email) values ('ADMIN', 'Test', 'a@test.local') returning id")
                .query(UUID.class).single();
        adminId = jdbc.sql("""
                        insert into iam.compte (utilisateur_id, operateur_id, profil, statut, mot_de_passe_hash, doit_changer_mot_de_passe)
                        values (:u, :o, 'AD', 'ACTIF', 'x', false) returning id
                        """)
                .param("u", utilisateur).param("o", fircaId).query(UUID.class).single();
    }

    protected RestClient admin() {
        return clientCompte(adminId, "AD", fircaId);
    }

    protected static final ParameterizedTypeReference<Map<String, Object>> OBJET = new ParameterizedTypeReference<>() { };
    protected static final ParameterizedTypeReference<List<Map<String, Object>>> LISTE = new ParameterizedTypeReference<>() { };

    protected String operateurTechnique(String acronyme) {
        return (String) admin().post().uri("/operateurs").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("acronym", acronyme, "name", acronyme, "type", "TECHNIQUE", "active", true))
                .retrieve().toEntity(OBJET).getBody().get("id");
    }

    /** Un chef secteur réel (AD → CD → CS) d'un opérateur neuf, sans permission d'écriture sur le référentiel. */
    protected RestClient chefSecteur(String acronyme) {
        UUID op = UUID.fromString(operateurTechnique(acronyme));
        return clientCompte(creerCompte(creerCompte(adminId, op, "CD"), op, "CS"), "CS", op);
    }

    protected Map<String, Object> contrat(String reference, String operateur, String debut, String fin) {
        return Map.of("reference", reference, "operatorId", operateur, "startDate", debut, "endDate", fin);
    }

    /** Un opérateur avec contrat en cours, ses lots d'un secteur, un CD, un CS affecté au secteur entier. */
    protected record Equipe(UUID operateur, UUID cd, UUID cs, UUID secteur, List<UUID> lots) {
    }

    protected UUID creerCompte(UUID acteur, UUID operateur, String profil) {
        return tx.execute(st -> {
            jdbc.sql("select set_config('app.compte_id', :a, true)").param("a", acteur.toString()).query().singleValue();
            UUID u = jdbc.sql("insert into iam.utilisateur (nom, prenoms, email) values ('T', 'T', :e) returning id")
                    .param("e", UUID.randomUUID() + "@t.local").query(UUID.class).single();
            return jdbc.sql("""
                            insert into iam.compte (utilisateur_id, operateur_id, profil, statut, mot_de_passe_hash, doit_changer_mot_de_passe)
                            values (:u, :o, :p, 'ACTIF', 'x', false) returning id
                            """)
                    .param("u", u).param("o", operateur).param("p", profil).query(UUID.class).single();
        });
    }

    protected void affecter(UUID compte, UUID operateur, UUID secteur, UUID lot, UUID section) {
        jdbc.sql("insert into iam.affectation (compte_id, operateur_id, secteur_id, lot_id, section_id) values (:c, :o, :s, :l, :x)")
                .param("c", compte).param("o", operateur).param("s", secteur).param("l", lot).param("x", section).update();
    }

    protected Equipe monterEquipe(String acronyme, String codeSecteur) {
        var c = admin();
        UUID op = UUID.fromString(operateurTechnique(acronyme));
        LocalDate debut = LocalDate.now().minusDays(30);
        c.post().uri("/contrats").contentType(MediaType.APPLICATION_JSON)
                .body(contrat("CT-" + acronyme, op.toString(), debut.toString(), debut.plusYears(3).toString()))
                .retrieve().toBodilessEntity();
        UUID secteur = jdbc.sql("select id from referentiel.secteur where code = :c").param("c", codeSecteur).query(UUID.class).single();
        List<UUID> lots = jdbc.sql("select id from referentiel.lot where secteur_id = :s order by numero").param("s", secteur).query(UUID.class).list();
        List<Integer> numeros = jdbc.sql("select numero from referentiel.lot where secteur_id = :s order by numero").param("s", secteur).query(Integer.class).list();
        String contrat = (String) c.get().uri("/contrats").retrieve().toEntity(LISTE).getBody().stream()
                .filter(x -> ("CT-" + acronyme).equals(x.get("reference"))).findFirst().orElseThrow().get("id");
        assertThat(c.post().uri("/attributions").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("contractId", contrat, "sectorId", secteur.toString(), "lots", numeros))
                .retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID cd = creerCompte(adminId, op, "CD");
        UUID cs = creerCompte(cd, op, "CS");
        affecter(cs, op, secteur, null, null);
        return new Equipe(op, cd, cs, secteur, lots);
    }

    /** Jeton d'un vrai compte : profil, opérateur et permissions sont relus en base par le serveur (PRO-17). */
    protected RestClient clientCompte(UUID compte, String profil, UUID operateur) {
        String token = jwt.emettre(compte, "TEST-" + profil, profil, operateur, List.of()).valeur();
        return RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultHeader("Authorization", "Bearer " + token)
                .defaultStatusHandler(st -> true, (req, res) -> { }).build();
    }

    /** Crée une section dans le premier lot de l'équipe (via la base, pour amorcer une affectation de moniteur). */
    protected UUID sectionDe(Equipe e, String nom) {
        return jdbc.sql("insert into referentiel.section (lot_id, numero, nom) values (:l, "
                        + "(select coalesce(max(numero), 0) + 1 from referentiel.section where lot_id = :l), :n) returning id")
                .param("l", e.lots().get(0)).param("n", nom).query(UUID.class).single();
    }

    protected static final ParameterizedTypeReference<Map<String, List<String>>> DROITS = new ParameterizedTypeReference<>() { };

    /** Un compte créé par l'API, avec son mot de passe temporaire et une session ouverte par une vraie connexion. */
    protected record Session(UUID id, String matricule, String motDePasse, String jeton, String refresh, RestClient client) {
    }

    protected Map<String, Object> corps(String nom, String profil, UUID operateur, String numero) {
        var m = new java.util.HashMap<String, Object>();
        m.put("lastName", nom);
        m.put("firstName", "Prénom " + nom);
        m.put("email", nom.toLowerCase() + "@test.local");
        m.put("operatorId", operateur.toString());
        m.put("profile", profil);
        if (numero != null) {
            m.put("number", numero);
        }
        return m;
    }

    @SuppressWarnings("unchecked")
    protected Session creer(RestClient par, String nom, String profil, UUID operateur, String numero) {
        var r = par.post().uri("/comptes").contentType(MediaType.APPLICATION_JSON).body(corps(nom, profil, operateur, numero))
                .retrieve().toEntity(OBJET);
        assertThat(r.getStatusCode()).as("création de " + nom).isEqualTo(HttpStatus.CREATED);
        var compte = (Map<String, Object>) r.getBody().get("account");
        String mdp = (String) r.getBody().get("temporaryPassword");
        return ouvrir(UUID.fromString((String) compte.get("id")), (String) compte.get("matricule"), mdp);
    }

    protected Session ouvrir(UUID id, String matricule, String motDePasse) {
        var login = RestClient.builder().baseUrl("http://localhost:" + port).defaultStatusHandler(s -> true, (q, p) -> { }).build()
                .post().uri("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("matricule", matricule, "password", motDePasse)).retrieve().toEntity(OBJET);
        assertThat(login.getStatusCode()).as("connexion de " + matricule).isEqualTo(HttpStatus.OK);
        String jeton = (String) login.getBody().get("token");
        RestClient client = RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultHeader("Authorization", "Bearer " + jeton).defaultStatusHandler(s -> true, (q, p) -> { }).build();
        return new Session(id, matricule, motDePasse, jeton, (String) login.getBody().get("refreshToken"), client);
    }

    /** AD → CD → CS d'un opérateur neuf. */
    protected record Chaine(UUID operateur, Session cd, Session cs) {
    }

    protected Chaine chaine(String acronyme) {
        UUID op = UUID.fromString(operateurTechnique(acronyme));
        Session cd = creer(admin(), "Cd" + acronyme, "CD", op, null);
        Session cs = creer(cd.client(), "Cs" + acronyme, "CS", op, null);
        return new Chaine(op, cd, cs);
    }

    // ------------------------------------------------------------------ planning (partagé par les tests de planning et de réalisation)

    protected static final int ANNEE = LocalDate.now().getYear();

    /** Une équipe de secteur : un CS, un moniteur affecté à une section, un contrôleur ordinaire, une équipe spéciale et une secrétaire. */
    protected record Secteur(Equipe equipe, UUID mo, UUID co, UUID es, UUID se, UUID section, RestClient cs, RestClient moC, RestClient seC) {
    }

    /** Une seule attribution par secteur pour toute la classe (un lot n'a qu'un opérateur à la fois) ; comptes et section neufs à chaque test. */
    protected static final Map<String, Equipe> EQUIPES = new HashMap<>();

    protected Secteur secteur(String acronyme, String code) {
        Equipe e = EQUIPES.computeIfAbsent(code, c -> monterEquipe("PL" + c, c));
        UUID section = sectionDe(e, "Nord-" + UUID.randomUUID().toString().substring(0, 8));
        UUID mo = creerCompte(e.cs(), e.operateur(), "MO");
        UUID co = creerCompte(e.cs(), e.operateur(), "CO");
        UUID es = creerCompte(e.cs(), e.operateur(), "ES");
        UUID se = creerCompte(e.cs(), e.operateur(), "SE");
        affecter(mo, e.operateur(), e.secteur(), e.lots().get(0), section);
        affecter(co, e.operateur(), e.secteur(), e.lots().get(0), section);
        affecter(es, e.operateur(), e.secteur(), null, null);
        affecter(se, e.operateur(), e.secteur(), null, null);
        return new Secteur(e, mo, co, es, se, section, clientCompte(e.cs(), "CS", e.operateur()),
                clientCompte(mo, "MO", e.operateur()), clientCompte(se, "SE", e.operateur()));
    }

    protected UUID activite(String code) {
        return jdbc.sql("select id from planning.activite where code = :c").param("c", code).query(UUID.class).single();
    }

    protected Map<String, Object> ligne(String code, int mois, int jour, Secteur s, boolean section, String plantation) {
        var m = new HashMap<String, Object>();
        m.put("date", LocalDate.of(ANNEE, mois, jour).toString());
        m.put("activityId", activite(code).toString());
        m.put("lotId", s.equipe().lots().get(0).toString());
        if (section) {
            m.put("sectionId", s.section().toString());
        }
        if (plantation != null) {
            m.put("plantationRef", plantation);
        }
        return m;
    }

    protected org.springframework.http.ResponseEntity<Map<String, Object>> put(RestClient c, String chemin, Object lignes) {
        return c.put().uri(chemin + "?annee=" + ANNEE).contentType(MediaType.APPLICATION_JSON).body(lignes).retrieve().toEntity(OBJET);
    }

    protected org.springframework.http.ResponseEntity<Map<String, Object>> post(RestClient c, String chemin) {
        return c.post().uri(chemin + (chemin.contains("?") ? "&" : "?") + "annee=" + ANNEE).contentType(MediaType.APPLICATION_JSON).body(Map.of())
                .retrieve().toEntity(OBJET);
    }


    protected HttpStatus statut(org.springframework.http.ResponseEntity<?> r) {
        return HttpStatus.valueOf(r.getStatusCode().value());
    }
}
