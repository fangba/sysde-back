package ci.sysde.iam.compte;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import ci.sysde.common.BusinessException;
import ci.sysde.common.Page;
import ci.sysde.common.PageRequete;
import ci.sysde.common.TriSql;
import ci.sysde.iam.compte.CompteDtos.Account;
import ci.sysde.iam.compte.CompteDtos.AccountInput;
import ci.sysde.iam.compte.CompteDtos.AccountWithPassword;
import ci.sysde.iam.compte.CompteDtos.PersonInput;
import ci.sysde.security.Acteur;
import ci.sysde.security.EtatCompteService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gestion des comptes (PRO, COM). La base porte les règles : hiérarchie de création, autorité sur un compte
 * ({@code iam.acteur_autorise}), motif obligatoire, coupure des sessions à la désactivation, historique des profils.
 * Ici : le périmètre de lecture, la recherche, le mot de passe temporaire et l'invalidation du cache d'état.
 */
@Service
class ComptesService {

    private static final String SELECT = """
            select c.id, c.utilisateur_id, c.matricule, c.reference_compte, c.numero, c.profil, p.libelle as profil_libelle,
                   c.operateur_id, o.acronyme, u.nom, u.prenoms, u.email, u.telephone, c.statut,
                   c.doit_changer_mot_de_passe, c.derniere_connexion, c.cree_le
              from iam.compte c
              join iam.utilisateur u on u.id = c.utilisateur_id
              join iam.profil p on p.code = c.profil
              join referentiel.operateur o on o.id = c.operateur_id
             where c.supprime_le is null""";

    private static final Map<String, String> TRIS = Map.of(
            "matricule", "c.matricule",
            "lastName", "lower(u.nom)",
            "profile", "p.ordre",
            "status", "c.statut",
            "createdAt", "c.cree_le");

    private final JdbcClient jdbc;
    private final PorteeComptes portees;
    private final PasswordEncoder encodeur;
    private final EtatCompteService etats;

    ComptesService(JdbcClient jdbc, PorteeComptes portees, PasswordEncoder encodeur, EtatCompteService etats) {
        this.jdbc = jdbc;
        this.portees = portees;
        this.encodeur = encodeur;
        this.etats = etats;
    }

    // ------------------------------------------------------------------ lecture

    /** Recherche paginée dans le périmètre de l'appelant ; {@code q} cherche dans le nom, les prénoms et le matricule. */
    @Transactional(readOnly = true)
    Page<Account> lister(Acteur acteur, String q, String profil, String statut, UUID operateur, PageRequete page, String sort) {
        PorteeComptes.Condition portee = portees.de(acteur);
        StringBuilder where = new StringBuilder(" and (").append(portee.sql()).append(")");
        Map<String, Object> params = new java.util.HashMap<>(portee.params());
        if (q != null && !q.isBlank()) {
            where.append(" and (lower(u.nom || ' ' || u.prenoms) like :q or lower(c.matricule) like :q)");
            params.put("q", "%" + q.strip().toLowerCase(java.util.Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        if (profil != null && !profil.isBlank()) {
            where.append(" and c.profil = :profil");
            params.put("profil", profil);
        }
        if (statut != null && !statut.isBlank()) {
            where.append(" and c.statut = :statut");
            params.put("statut", statut);
        }
        if (operateur != null) {
            where.append(" and c.operateur_id = :operateur");
            params.put("operateur", operateur);
        }
        String ordre = TriSql.clause(sort, TRIS, "c.matricule asc", "c.id asc");
        long total = jdbc.sql("select count(*) from iam.compte c join iam.utilisateur u on u.id = c.utilisateur_id"
                        + " join iam.profil p on p.code = c.profil join referentiel.operateur o on o.id = c.operateur_id"
                        + " where c.supprime_le is null" + where)
                .params(params).query(Long.class).single();
        List<Account> contenu = jdbc.sql(SELECT + where + " order by " + ordre + " limit :limite offset :decalage")
                .params(params).param("limite", page.size()).param("decalage", page.decalage())
                .query(ComptesService::ligne).list();
        return Page.de(contenu, page, total);
    }

    @Transactional(readOnly = true)
    Account lire(Acteur acteur, UUID id) {
        PorteeComptes.Condition portee = portees.de(acteur);
        return jdbc.sql(SELECT + " and (" + portee.sql() + ") and c.id = :id").params(portee.params()).param("id", id)
                .query(ComptesService::ligne).optional().orElseThrow(ComptesService::introuvable);
    }

    // ------------------------------------------------------------------ création et modification

    @Transactional
    AccountWithPassword creer(AccountInput in) {
        if (!jdbc.sql("select exists (select 1 from iam.profil where code = :p)").param("p", in.profile()).query(Boolean.class).single()) {
            throw invalide("Profil inconnu : " + in.profile() + ".");
        }
        if (!jdbc.sql("select exists (select 1 from referentiel.operateur where id = :o and supprime_le is null and actif)")
                .param("o", in.operatorId()).query(Boolean.class).single()) {
            throw invalide("Opérateur inconnu ou inactif.");
        }
        verifierEmailLibre(in.email(), null);
        UUID utilisateur = jdbc.sql("insert into iam.utilisateur (nom, prenoms, email, telephone) values (:n, :p, :e, :t) returning id")
                .param("n", in.lastName().strip()).param("p", in.firstName().strip())
                .param("e", vide(in.email())).param("t", vide(in.phone()))
                .query(UUID.class).single();
        String motDePasse = MotDePasseTemporaire.generer();
        // La base contrôle la hiérarchie (qui crée quel profil), le type d'organisme, la permission et attribue le matricule.
        UUID id = jdbc.sql("""
                        insert into iam.compte (utilisateur_id, operateur_id, profil, numero, statut, mot_de_passe_hash, doit_changer_mot_de_passe)
                        values (:u, :o, :p, :numero, 'ACTIF', :hash, true)
                        returning id
                        """)
                .param("u", utilisateur).param("o", in.operatorId()).param("p", in.profile()).param("numero", in.number())
                .param("hash", encodeur.encode(motDePasse))
                .query(UUID.class).single();
        return new AccountWithPassword(lireSansPerimetre(id), motDePasse);
    }

    /** Nom, prénoms, e-mail et téléphone de la personne. Ni le profil, ni le matricule, ni l'opérateur. */
    @Transactional
    Account modifierPersonne(Acteur acteur, UUID id, PersonInput in) {
        Account compte = lireSansPerimetre(id);
        exigerAutorite(acteur, id, compte.profile());
        verifierEmailLibre(in.email(), compte.userId());
        jdbc.sql("update iam.utilisateur set nom = :n, prenoms = :p, email = :e, telephone = :t where id = :u")
                .param("u", compte.userId()).param("n", in.lastName().strip()).param("p", in.firstName().strip())
                .param("e", vide(in.email())).param("t", vide(in.phone())).update();
        return lireSansPerimetre(id);
    }

    // ------------------------------------------------------------------ états

    /** Désactive : sessions coupées par la base ; les jetons d'accès déjà émis sont rejetés dès que le cache est vidé. */
    @Transactional
    Account desactiver(Acteur acteur, UUID id, String motif) {
        refuserSurSoi(acteur, id);
        Account compte = lireSansPerimetre(id);
        if ("DESACTIVE".equals(compte.status())) {
            throw conflit("Ce compte est déjà désactivé.");
        }
        poserMotif(motif);
        jdbc.sql("update iam.compte set statut = 'DESACTIVE' where id = :id").param("id", id).update();
        etats.invalider(id);
        return lireSansPerimetre(id);
    }

    @Transactional
    Account reactiver(Acteur acteur, UUID id, String motif) {
        refuserSurSoi(acteur, id);
        Account compte = lireSansPerimetre(id);
        if (!"DESACTIVE".equals(compte.status())) {
            throw conflit("Ce compte n'est pas désactivé.");
        }
        poserMotif(motif);
        jdbc.sql("update iam.compte set statut = 'ACTIF' where id = :id").param("id", id).update();
        etats.invalider(id);
        return lireSansPerimetre(id);
    }

    /**
     * Réinitialisation (COM-07) : nouveau mot de passe temporaire à changer à la première connexion, échecs et verrou
     * effacés, toutes les sessions coupées (jetons de rafraîchissement révoqués, jetons d'accès émis avant refusés).
     */
    @Transactional
    AccountWithPassword reinitialiser(Acteur acteur, UUID id, String motif) {
        refuserSurSoi(acteur, id);
        Account compte = lireSansPerimetre(id);
        exigerAutorite(acteur, id, compte.profile());
        if (!"ACTIF".equals(compte.status())) {
            throw conflit("Seul un compte actif se réinitialise (réactivez-le d'abord).");
        }
        poserMotif(motif);
        String motDePasse = MotDePasseTemporaire.generer();
        jdbc.sql("""
                        update iam.compte
                           set mot_de_passe_hash = :hash, doit_changer_mot_de_passe = true, echecs_connexion = 0,
                               verrouille_jusqu_a = null, jetons_valides_depuis = now()
                         where id = :id
                        """)
                .param("id", id).param("hash", encodeur.encode(motDePasse)).update();
        jdbc.sql("update iam.jeton_rafraichissement set revoque_le = now() where compte_id = :id and revoque_le is null")
                .param("id", id).update();
        etats.invalider(id);
        return new AccountWithPassword(lireSansPerimetre(id), motDePasse);
    }

    // ------------------------------------------------------------------ profil et matricule

    /** Promotion, rétrogradation ou mouvement latéral (PRO-07) ; la base exige le motif et l'autorité, et clôt les affectations. */
    @Transactional
    Account changerProfil(Acteur acteur, UUID id, String profil, String motif) {
        refuserSurSoi(acteur, id);
        Account compte = lireSansPerimetre(id);
        if (compte.profile().equals(profil)) {
            throw conflit("Le compte a déjà ce profil.");
        }
        if (!jdbc.sql("select exists (select 1 from iam.profil where code = :p)").param("p", profil).query(Boolean.class).single()) {
            throw invalide("Profil inconnu : " + profil + ".");
        }
        poserMotif(motif);
        jdbc.sql("update iam.compte set profil = :p where id = :id").param("p", profil).param("id", id).update();
        etats.invalider(id);
        return lireSansPerimetre(id);
    }

    /** Correction du numéro (COM-03) : administrateur seulement (règle de la base), l'ancien matricule est conservé en alias. */
    @Transactional
    Account corrigerMatricule(UUID id, String numero, String motif) {
        Account compte = lireSansPerimetre(id);
        if (compte.number().equals(numero)) {
            throw conflit("Le compte a déjà ce numéro.");
        }
        poserMotif(motif);
        jdbc.sql("update iam.compte set numero = :n where id = :id").param("n", numero).param("id", id).update();
        etats.invalider(id);
        return lireSansPerimetre(id);
    }

    // ------------------------------------------------------------------ utilitaires

    private Account lireSansPerimetre(UUID id) {
        return jdbc.sql(SELECT + " and c.id = :id").param("id", id).query(ComptesService::ligne).optional()
                .orElseThrow(ComptesService::introuvable);
    }

    /** Applique la règle de la base « puis-je gérer ce compte ? » là où elle ne tient pas lieu de garde-fou (données de la personne). */
    private void exigerAutorite(Acteur acteur, UUID id, String profilCible) {
        boolean autorise = jdbc.sql("select iam.acteur_autorise('GERER_COMPTE', :id, array[cast(:profil as char(2))])")
                .param("id", id).param("profil", profilCible).query(Boolean.class).single();
        if (!autorise) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ACCES_REFUSE", "Vous n'avez pas l'autorité de gérer ce compte.");
        }
    }

    private static void refuserSurSoi(Acteur acteur, UUID id) {
        if (acteur.compteId().equals(id)) {
            throw invalide("Vous ne pouvez pas faire cette opération sur votre propre compte.");
        }
    }

    private void poserMotif(String motif) {
        if (motif != null && !motif.isBlank()) {
            jdbc.sql("select set_config('app.motif', :m, true)").param("m", motif.strip()).query().singleValue();
        }
    }

    private void verifierEmailLibre(String email, UUID sauf) {
        if (email == null || email.isBlank()) {
            return;
        }
        boolean pris = jdbc.sql("""
                        select exists (select 1 from iam.utilisateur where lower(email) = lower(:e) and supprime_le is null
                                          and (cast(:sauf as uuid) is null or id <> :sauf))
                        """)
                .param("e", email.strip()).param("sauf", sauf).query(Boolean.class).single();
        if (pris) {
            throw conflit("Cet e-mail est déjà utilisé par une autre personne.");
        }
    }

    private static String vide(String texte) {
        return texte == null || texte.isBlank() ? null : texte.strip();
    }

    private static Account ligne(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Account(rs.getObject("id", UUID.class), rs.getObject("utilisateur_id", UUID.class), rs.getString("matricule"),
                rs.getString("reference_compte"), rs.getString("numero"), rs.getString("profil"), rs.getString("profil_libelle"),
                rs.getObject("operateur_id", UUID.class), rs.getString("acronyme"), rs.getString("nom"), rs.getString("prenoms"),
                rs.getString("email"), rs.getString("telephone"), rs.getString("statut"),
                rs.getBoolean("doit_changer_mot_de_passe"),
                rs.getObject("derniere_connexion", java.time.OffsetDateTime.class), rs.getObject("cree_le", java.time.OffsetDateTime.class));
    }

    private static BusinessException introuvable() {
        return new BusinessException(HttpStatus.NOT_FOUND, "INTROUVABLE", "Compte introuvable.");
    }

    private static BusinessException invalide(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, "REQUETE_INVALIDE", message);
    }

    private static BusinessException conflit(String message) {
        return new BusinessException(HttpStatus.CONFLICT, "CONFLIT_DONNEES", message);
    }
}
