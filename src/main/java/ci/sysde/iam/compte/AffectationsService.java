package ci.sysde.iam.compte;

import java.util.List;
import java.util.UUID;

import ci.sysde.common.BusinessException;
import ci.sysde.iam.compte.CompteDtos.Account;
import ci.sysde.iam.compte.CompteDtos.Assignment;
import ci.sysde.iam.compte.CompteDtos.AssignmentInput;
import ci.sysde.iam.compte.CompteDtos.TakeoverResult;
import ci.sysde.security.Acteur;
import ci.sysde.security.Perimetre;
import ci.sysde.security.PerimetreService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Affectations territoriales d'un compte (AFF) et reprise (PRO-11). La base porte les règles : niveau selon le profil,
 * un seul secteur à la fois, maximum par profil, territoire couvert par un contrat de l'opérateur, un seul CS par
 * secteur et opérateur, un seul MO par section, retrait d'un lot encore affecté refusé.
 * Ici : qui a le droit d'affecter qui, et où.
 */
@Service
class AffectationsService {

    private static final String SELECT = """
            select f.id, f.compte_id, f.secteur_id, s.code, s.nom as secteur_nom, f.lot_id, l.numero, f.section_id,
                   sec.nom as section_nom, f.debut, f.fin, f.etat
              from iam.v_affectation_etat f
              join referentiel.secteur s on s.id = f.secteur_id
              left join referentiel.lot l on l.id = f.lot_id
              left join referentiel.section sec on sec.id = f.section_id
             where f.compte_id = :compte""";

    private final JdbcClient jdbc;
    private final ComptesService comptes;
    private final PerimetreService perimetres;

    AffectationsService(JdbcClient jdbc, ComptesService comptes, PerimetreService perimetres) {
        this.jdbc = jdbc;
        this.comptes = comptes;
        this.perimetres = perimetres;
    }

    /** Affectations en cours et historique du compte (404 si le compte est hors du périmètre de l'appelant). */
    @Transactional(readOnly = true)
    List<Assignment> lister(Acteur acteur, UUID compteId) {
        comptes.lire(acteur, compteId);
        return jdbc.sql(SELECT + " order by f.debut desc, f.id").param("compte", compteId)
                .query(AffectationsService::ligne).list();
    }

    @Transactional
    Assignment affecter(Acteur acteur, UUID compteId, AssignmentInput in) {
        exigerUnDroitDAffectation(acteur);
        Account cible = comptes.lire(acteur, compteId);
        exigerDroit(acteur, cible, in);
        UUID id = jdbc.sql("""
                        insert into iam.affectation (compte_id, operateur_id, secteur_id, lot_id, section_id)
                        values (:compte, :operateur, :secteur, :lot, :section)
                        returning id
                        """)
                .param("compte", compteId).param("operateur", cible.operatorId())
                .param("secteur", in.sectorId()).param("lot", in.lotId()).param("section", in.sectionId())
                .query(UUID.class).single();
        return lireUne(id);
    }

    /** Clôt l'affectation (AFF-05) : rien n'est effacé, la ligne garde sa date de fin. */
    @Transactional
    Assignment cloturer(Acteur acteur, UUID compteId, UUID affectationId) {
        exigerUnDroitDAffectation(acteur);
        Account cible = comptes.lire(acteur, compteId);
        Assignment actuelle = jdbc.sql(SELECT + " and f.id = :id").param("compte", compteId).param("id", affectationId)
                .query(AffectationsService::ligne).optional()
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "INTROUVABLE", "Affectation introuvable."));
        if (actuelle.endDate() != null) {
            throw new BusinessException(HttpStatus.CONFLICT, "CONFLIT_DONNEES", "Cette affectation est déjà close.");
        }
        exigerDroit(acteur, cible, new AssignmentInput(actuelle.sectorId(), actuelle.lotId(), actuelle.sectionId()));
        jdbc.sql("update iam.affectation set fin = greatest(current_date, debut) where id = :id").param("id", affectationId).update();
        return lireUne(affectationId);
    }

    /** Reprise : les affectations du compte désactivé passent au successeur, avec motif (règles de la base). */
    @Transactional
    TakeoverResult reprendre(Acteur acteur, UUID sourceId, UUID successeurId, String motif) {
        comptes.lire(acteur, sourceId);
        comptes.lire(acteur, successeurId);
        int n = jdbc.sql("select iam.reprendre_compte(:source, :cible, :motif)")
                .param("source", sourceId).param("cible", successeurId).param("motif", motif)
                .query(Integer.class).single();
        return new TakeoverResult(n);
    }

    // ------------------------------------------------------------------ droits

    /**
     * Le CD affecte un secteur à ses CS (AFFECTER_SECTEUR), le CS place ses techniciens dans les lots et sections de
     * son secteur (AFFECTER_TECHNICIEN). Autorité sur la personne : rang strictement supérieur (règle de la base).
     */
    /** Sans aucun droit d'affecter : 403 d'emblée (et non un 404 trompeur parce que le compte est hors de son périmètre). */
    private static void exigerUnDroitDAffectation(Acteur acteur) {
        if (!acteur.a("AFFECTER_SECTEUR") && !acteur.a("AFFECTER_TECHNICIEN")) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ACCES_REFUSE", "Vous n'avez pas le droit d'affecter.");
        }
    }

    private void exigerDroit(Acteur acteur, Account cible, AssignmentInput in) {
        String permission = "CS".equals(cible.profile()) ? "AFFECTER_SECTEUR" : "AFFECTER_TECHNICIEN";
        boolean autorise = acteur.a(permission) && jdbc.sql("select iam.acteur_autorise(:perm, :id, array[cast(:profil as char(2))])")
                .param("perm", permission).param("id", cible.id()).param("profil", cible.profile()).query(Boolean.class).single();
        if (!autorise) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ACCES_REFUSE", "Vous n'avez pas le droit d'affecter ce compte.");
        }
        // Un chef secteur n'affecte que dans son propre secteur.
        Perimetre p = perimetres.de(acteur);
        if (p.etendue() == Perimetre.Etendue.AFFECTATIONS && !p.secteursEntiers().contains(in.sectorId())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ACCES_REFUSE", "Ce territoire n'est pas dans votre secteur.");
        }
    }

    private Assignment lireUne(UUID id) {
        return jdbc.sql("""
                        select f.id, f.compte_id, f.secteur_id, s.code, s.nom as secteur_nom, f.lot_id, l.numero, f.section_id,
                               sec.nom as section_nom, f.debut, f.fin, f.etat
                          from iam.v_affectation_etat f
                          join referentiel.secteur s on s.id = f.secteur_id
                          left join referentiel.lot l on l.id = f.lot_id
                          left join referentiel.section sec on sec.id = f.section_id
                         where f.id = :id""")
                .param("id", id).query(AffectationsService::ligne).single();
    }

    private static Assignment ligne(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Assignment(rs.getObject("id", UUID.class), rs.getObject("compte_id", UUID.class),
                rs.getObject("secteur_id", UUID.class), rs.getString("code"), rs.getString("secteur_nom"),
                rs.getObject("lot_id", UUID.class), rs.getObject("numero", Integer.class),
                rs.getObject("section_id", UUID.class), rs.getString("section_nom"),
                rs.getObject("debut", java.time.LocalDate.class), rs.getObject("fin", java.time.LocalDate.class),
                rs.getString("etat"));
    }
}
