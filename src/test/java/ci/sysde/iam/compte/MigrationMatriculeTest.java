package ci.sysde.iam.compte;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * V20 (matricule = ACRONYME-PROFIL-NUMÉRO) sur une base qui contient déjà des comptes : ils sont convertis, leur ancien
 * matricule est gardé en alias, et la numérotation continue. Base à part : on s'arrête à V19, on crée des comptes à
 * l'ancien format, puis on applique la suite.
 */
class MigrationMatriculeTest {

    private static Flyway flyway(PostgreSQLContainer db, String cible) {
        var conf = Flyway.configure().dataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword());
        return (cible == null ? conf : conf.target(cible)).load();
    }

    private static String un(Connection c, String sql) throws SQLException {
        try (var st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static List<String> liste(Connection c, String sql) throws SQLException {
        List<String> v = new ArrayList<>();
        try (var st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                v.add(rs.getString(1));
            }
        }
        return v;
    }

    /** Un administrateur d'organisme : sans compte connecté, c'est le seul profil que la base accepte de créer. */
    private static void creerAdmin(Connection c, String acronyme, String email) throws SQLException {
        try (var st = c.createStatement()) {
            st.execute("insert into iam.utilisateur (nom, prenoms, email) values ('ADMIN', 'Test', '" + email + "')");
            st.execute("""
                    insert into iam.compte (utilisateur_id, operateur_id, profil, statut, mot_de_passe_hash, doit_changer_mot_de_passe)
                    select (select id from iam.utilisateur where email = '%s'), o.id, 'AD', 'ACTIF', 'x', false
                      from referentiel.operateur o where o.acronyme = '%s'
                    """.formatted(email, acronyme));
        }
    }

    @Test
    void lesComptesExistantsPassentAuNouveauFormatEtLaNumerotationContinue() throws Exception {
        try (var db = new PostgreSQLContainer(DockerImageName.parse("postgis/postgis:18-3.6").asCompatibleSubstituteFor("postgres"))) {
            db.start();
            flyway(db, "19").migrate();

            try (Connection c = DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword())) {
                creerAdmin(c, "FIRCA", "firca@test.local");
                creerAdmin(c, "APROMAC", "apromac@test.local");
                assertThat(liste(c, "select matricule from iam.compte order by matricule")).containsExactly("APROMAC-00001", "FIRCA-00001");

                flyway(db, null).migrate();

                assertThat(liste(c, "select matricule from iam.compte order by matricule")).containsExactly("APROMAC-AD-00001", "FIRCA-AD-00001");
                // la « référence » suit le matricule
                assertThat(un(c, "select reference_compte from iam.compte c join referentiel.operateur o on o.id = c.operateur_id where o.acronyme = 'FIRCA'"))
                        .isEqualTo("FIRCA-AD-00001");
                // les anciens matricules sont gardés en alias, avec leur motif
                assertThat(liste(c, "select ancien_matricule from iam.alias_matricule order by ancien_matricule"))
                        .containsExactly("APROMAC-00001", "FIRCA-00001");
                assertThat(un(c, "select motif from iam.alias_matricule where ancien_matricule = 'FIRCA-00001'")).contains("Nouveau format");
                // la numérotation continue (le numéro 00001 n'est pas réattribué) et le nouveau format s'applique
                creerAdmin(c, "FIRCA", "firca2@test.local");
                assertThat(un(c, "select matricule from iam.compte where utilisateur_id = (select id from iam.utilisateur where email = 'firca2@test.local')"))
                        .isEqualTo("FIRCA-AD-00002");
                // la contrainte de la base n'accepte plus l'ancien format
                assertThat(un(c, "select pg_get_constraintdef(oid) from pg_constraint where conname = 'compte_matricule_check'"))
                        .contains("[A-Z]{2}");
            }
        }
    }
}
