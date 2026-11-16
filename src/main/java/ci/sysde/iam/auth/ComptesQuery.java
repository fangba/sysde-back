package ci.sysde.iam.auth;

import java.util.List;
import java.util.UUID;

import ci.sysde.iam.auth.AuthDtos.Operator;
import ci.sysde.iam.auth.AuthDtos.SessionUser;
import ci.sysde.iam.compte.Compte;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Lectures du module connexion (fonctions de décision de la base, permissions, identité affichée). */
@Component
public class ComptesQuery {

    private final JdbcClient jdbc;

    public ComptesQuery(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Compte actif, utilisateur actif, organisation active, non verrouillé (fonction de la base). */
    public boolean peutSeConnecter(UUID compteId) {
        return jdbc.sql("select iam.compte_peut_se_connecter(:id)")
                .param("id", compteId)
                .query(Boolean.class)
                .single();
    }

    /** Comme {@link #peutSeConnecter}, ou compte désactivé depuis moins du délai d'envoi seul (fonction de la base). */
    public boolean peutSynchroniser(UUID compteId) {
        return jdbc.sql("select iam.compte_peut_synchroniser(:id)")
                .param("id", compteId)
                .query(Boolean.class)
                .single();
    }

    /** Secteur de l'affectation en cours (le premier par code s'il y en a plusieurs) ; nul sans affectation. */
    private AuthDtos.Sector secteurDe(UUID compteId) {
        return jdbc.sql("""
                        select s.code, s.nom from iam.affectation f join referentiel.secteur s on s.id = f.secteur_id
                         where f.compte_id = :id and f.fin is null and f.supprime_le is null and s.supprime_le is null
                         order by s.code limit 1
                        """)
                .param("id", compteId)
                .query((rs, n) -> new AuthDtos.Sector(rs.getString("code"), rs.getString("nom")))
                .optional().orElse(null);
    }

    public List<String> permissionsDuProfil(String profil) {
        return jdbc.sql("select permission from iam.profil_permission where profil = :profil order by permission")
                .param("profil", profil)
                .query(String.class)
                .list();
    }

    public SessionUser lireSession(Compte compte, List<String> permissions) {
        return jdbc.sql("""
                        select c.reference_compte, u.prenoms, u.nom, u.email,
                               p.libelle as profil_libelle,
                               o.id as operateur_id, o.acronyme, o.denomination
                          from iam.compte c
                          join iam.utilisateur u on u.id = c.utilisateur_id
                          join iam.profil p on p.code = c.profil
                          join referentiel.operateur o on o.id = c.operateur_id
                         where c.id = :id
                        """)
                .param("id", compte.id())
                .query((rs, n) -> new SessionUser(
                        compte.id(),
                        compte.matricule(),
                        rs.getString("reference_compte"),
                        rs.getString("prenoms"),
                        rs.getString("nom"),
                        rs.getString("email"),
                        compte.profil(),
                        rs.getString("profil_libelle"),
                        "AD".equals(compte.profil()),
                        new Operator(rs.getObject("operateur_id", UUID.class), rs.getString("acronyme"),
                                rs.getString("denomination")),
                        secteurDe(compte.id()),
                        permissions))
                .single();
    }
}
