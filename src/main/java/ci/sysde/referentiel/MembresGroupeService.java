package ci.sysde.referentiel;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import ci.sysde.common.BusinessException;
import ci.sysde.realisation.RealisationDtos.Participant;
import ci.sysde.referentiel.ReferentielDtos.GroupMember;
import ci.sysde.security.Acteur;
import ci.sysde.security.Perimetre;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Membres d'un groupe de vulgarisation : des participants de l'opérateur titulaire du lot du groupe, avec leur période
 * d'appartenance (les départs sont conservés). Qui écrit : le chef secteur du lot (permission {@code GERER_SECTION}) ou le
 * moniteur responsable du groupe ({@code REALISER_ACTIVITE}) ; qui lit : tout compte qui voit le groupe.
 */
@Service
class MembresGroupeService {

    private record Groupe(UUID id, UUID lotId, UUID sectionId, UUID secteurId, UUID operateurId, UUID moniteurId) {
    }

    private static final String COLONNES_MEMBRE = """
            m.depuis, m.jusqu_au, p.id, p.nom, p.prenoms, p.telephone, p.village, p.reference_externe""";

    private final JdbcClient jdbc;
    private final AccesLot acces;

    MembresGroupeService(JdbcClient jdbc, AccesLot acces) {
        this.jdbc = jdbc;
        this.acces = acces;
    }

    @Transactional(readOnly = true)
    List<GroupMember> lister(Acteur acteur, UUID groupeId, boolean historique) {
        Groupe g = visible(acteur, groupeId);
        return membres(g.id(), historique);
    }

    /** Ajoute un membre ; un renvoi (déjà membre) renvoie la ligne existante sans rien changer. */
    @Transactional
    AjoutResultat ajouter(Acteur acteur, UUID groupeId, UUID participantId, LocalDate depuis) {
        Groupe g = modifiable(acteur, groupeId);
        if (depuis != null && depuis.isAfter(LocalDate.now())) {
            throw invalide("La date d'entrée ne peut pas être dans le futur.");
        }
        verifierParticipants(g, List.of(participantId));
        var existant = ouvert(g.id(), participantId);
        if (existant) {
            return new AjoutResultat(membre(g.id(), participantId), false);
        }
        jdbc.sql("insert into planning.membre_groupe (groupe_id, participant_id, depuis) values (:g, :p, coalesce(cast(:depuis as date), current_date))")
                .param("g", g.id()).param("p", participantId).param("depuis", depuis).update();
        return new AjoutResultat(membre(g.id(), participantId), true);
    }

    record AjoutResultat(GroupMember membre, boolean cree) {
    }

    /** Départ d'un membre : il reste dans l'historique, sauf s'il vient d'être ajouté aujourd'hui (saisie qui se corrige). */
    @Transactional
    void retirer(Acteur acteur, UUID groupeId, UUID participantId) {
        Groupe g = modifiable(acteur, groupeId);
        if (!ouvert(g.id(), participantId)) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "INTROUVABLE", "Ce participant n'est pas membre de ce groupe.");
        }
        quitter(g.id(), participantId);
    }

    /** Remplace la liste des membres actuels : tout est vérifié avant la moindre écriture. */
    @Transactional
    List<GroupMember> remplacer(Acteur acteur, UUID groupeId, List<UUID> participants) {
        Groupe g = modifiable(acteur, groupeId);
        Set<UUID> voulus = new LinkedHashSet<>(participants);
        verifierParticipants(g, new ArrayList<>(voulus));
        Set<UUID> actuels = new LinkedHashSet<>(jdbc.sql("select participant_id from planning.membre_groupe where groupe_id = :g and jusqu_au is null for update")
                .param("g", g.id()).query(UUID.class).list());
        for (UUID p : actuels) {
            if (!voulus.contains(p)) {
                quitter(g.id(), p);
            }
        }
        for (UUID p : voulus) {
            if (!actuels.contains(p)) {
                jdbc.sql("insert into planning.membre_groupe (groupe_id, participant_id) values (:g, :p)").param("g", g.id()).param("p", p).update();
            }
        }
        return membres(g.id(), false);
    }

    // ------------------------------------------------------------------ interne

    private void quitter(UUID groupe, UUID participant) {
        jdbc.sql("delete from planning.membre_groupe where groupe_id = :g and participant_id = :p and jusqu_au is null and depuis = current_date")
                .param("g", groupe).param("p", participant).update();
        jdbc.sql("update planning.membre_groupe set jusqu_au = current_date where groupe_id = :g and participant_id = :p and jusqu_au is null")
                .param("g", groupe).param("p", participant).update();
    }

    private boolean ouvert(UUID groupe, UUID participant) {
        return jdbc.sql("select exists (select 1 from planning.membre_groupe where groupe_id = :g and participant_id = :p and jusqu_au is null)")
                .param("g", groupe).param("p", participant).query(Boolean.class).single();
    }

    private GroupMember membre(UUID groupe, UUID participant) {
        return jdbc.sql("select " + COLONNES_MEMBRE + " from planning.membre_groupe m join planning.participant p on p.id = m.participant_id"
                        + " where m.groupe_id = :g and m.participant_id = :p and m.jusqu_au is null")
                .param("g", groupe).param("p", participant).query(MembresGroupeService::ligne).single();
    }

    private List<GroupMember> membres(UUID groupe, boolean historique) {
        return jdbc.sql("select " + COLONNES_MEMBRE + " from planning.membre_groupe m join planning.participant p on p.id = m.participant_id"
                        + " where m.groupe_id = :g" + (historique ? "" : " and m.jusqu_au is null")
                        + " order by lower(p.nom), lower(p.prenoms), m.depuis desc")
                .param("g", groupe).query(MembresGroupeService::ligne).list();
    }

    private static GroupMember ligne(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new GroupMember(new Participant(rs.getObject("id", UUID.class), rs.getString("nom"), rs.getString("prenoms"),
                rs.getString("telephone"), rs.getString("village"), rs.getString("reference_externe")),
                rs.getObject("depuis", LocalDate.class), rs.getObject("jusqu_au", LocalDate.class));
    }

    /** Les participants existent et sont ceux de l'opérateur titulaire du lot du groupe. */
    private void verifierParticipants(Groupe g, List<UUID> participants) {
        if (participants.isEmpty()) {
            return;
        }
        if (g.operateurId() == null) {
            throw invalide("Le lot de ce groupe n'est confié à aucun opérateur.");
        }
        long bons = jdbc.sql("select count(*) from planning.participant where id in (:ids) and operateur_id = :op and supprime_le is null")
                .param("ids", participants).param("op", g.operateurId()).query(Long.class).single();
        if (bons != participants.size()) {
            throw invalide("Un participant est inconnu ou n'appartient pas à l'opérateur de ce groupe.");
        }
    }

    private Groupe visible(Acteur acteur, UUID id) {
        Groupe g = jdbc.sql("""
                        select g.id, g.lot_id, g.section_id, l.secteur_id, g.moniteur_compte_id, %s as operateur_id
                          from planning.groupe_vulgarisation g join referentiel.lot l on l.id = g.lot_id
                         where g.id = :id and g.supprime_le is null
                        """.formatted(AccesLot.TITULAIRE))
                .param("id", id)
                .query((rs, n) -> new Groupe(rs.getObject("id", UUID.class), rs.getObject("lot_id", UUID.class),
                        rs.getObject("section_id", UUID.class), rs.getObject("secteur_id", UUID.class),
                        rs.getObject("operateur_id", UUID.class), rs.getObject("moniteur_compte_id", UUID.class)))
                .optional().orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "INTROUVABLE", "Groupe introuvable."));
        Perimetre p = acces.perimetre(acteur);
        boolean voit = p.estNational() || (g.operateurId() != null && p.voitSection(g.operateurId(), g.secteurId(), g.lotId(), g.sectionId()));
        if (!voit) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "INTROUVABLE", "Groupe introuvable.");
        }
        return g;
    }

    /** Écriture : le moniteur responsable du groupe, ou le chef secteur du lot (403 sinon). */
    private Groupe modifiable(Acteur acteur, UUID id) {
        Groupe g = visible(acteur, id);
        boolean moniteurDuGroupe = acteur.compteId().equals(g.moniteurId()) && acteur.a("REALISER_ACTIVITE");
        if (!moniteurDuGroupe) {
            if (!acteur.a("GERER_SECTION")) {
                throw new BusinessException(HttpStatus.FORBIDDEN, "ACCES_REFUSE", "Seuls le moniteur du groupe et le chef secteur gèrent ses membres.");
            }
            acces.exigerEcriture(acteur, acces.lot(g.lotId()));
        }
        return g;
    }

    private static BusinessException invalide(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, "REQUETE_INVALIDE", message);
    }
}
