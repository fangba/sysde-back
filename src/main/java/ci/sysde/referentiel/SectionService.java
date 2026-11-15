package ci.sysde.referentiel;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import ci.sysde.common.BusinessException;
import ci.sysde.referentiel.ReferentielDtos.Group;
import ci.sysde.referentiel.ReferentielDtos.GroupInput;
import ci.sysde.referentiel.ReferentielDtos.Section;
import ci.sysde.referentiel.ReferentielDtos.SectionInput;
import ci.sysde.security.Acteur;
import ci.sysde.security.Perimetre;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sections et groupes de vulgarisation, gérés par le chef secteur (permission {@code GERER_SECTION}) sur les lots
 * de son secteur confiés à son opérateur. Lecture : selon le périmètre du compte.
 */
@Service
public class SectionService {

    /** Une ligne lue, avec le secteur du lot et son opérateur titulaire, avant le filtre de périmètre. */
    private record Candidat<T>(T valeur, UUID secteur, UUID operateur) {
    }

    private final JdbcClient jdbc;
    private final AccesLot acces;
    private final SecteursVisibles visibles;

    SectionService(JdbcClient jdbc, AccesLot acces, SecteursVisibles visibles) {
        this.jdbc = jdbc;
        this.acces = acces;
        this.visibles = visibles;
    }

    // ------------------------------------------------------------------ sections

    @Transactional(readOnly = true)
    public List<Section> sections(Acteur acteur) {
        Perimetre p = acces.perimetre(acteur);
        Optional<Set<UUID>> secteurs = visibles.de(acteur);
        if (secteurs.isPresent() && secteurs.get().isEmpty()) {
            return List.of();
        }
        var requete = jdbc.sql("""
                select s.id, s.lot_id, s.nom, s.description, l.secteur_id, %s as operateur_id
                  from referentiel.section s join referentiel.lot l on l.id = s.lot_id
                 where s.supprime_le is null and l.supprime_le is null %s
                 order by l.numero, s.numero""".formatted(AccesLot.TITULAIRE, secteurs.isPresent() ? "and l.secteur_id in (:ids)" : ""));
        if (secteurs.isPresent()) {
            requete = requete.param("ids", secteurs.get());
        }
        return requete.query((rs, n) -> new Candidat<>(
                        new Section(rs.getObject("id", UUID.class), rs.getObject("lot_id", UUID.class), rs.getString("nom"),
                                rs.getString("description")),
                        rs.getObject("secteur_id", UUID.class), rs.getObject("operateur_id", UUID.class)))
                .list().stream()
                .filter(c -> voit(p, c.operateur(), c.secteur(), c.valeur().lotId(), c.valeur().id()))
                .map(Candidat::valeur)
                .toList();
    }

    @Transactional
    Section creerSection(Acteur acteur, SectionInput in) {
        AccesLot.Lot lot = acces.lot(in.lotId());
        acces.exigerEcriture(acteur, lot);
        String nom = normaliser(in.name());
        verifierNomSection(in.lotId(), nom, null);
        // Verrou sur le lot : deux créations simultanées n'obtiennent pas le même numéro.
        jdbc.sql("select id from referentiel.lot where id = :id for update").param("id", in.lotId()).query().singleValue();
        UUID id = jdbc.sql("""
                        insert into referentiel.section (lot_id, numero, nom, description)
                        values (:lot, (select coalesce(max(numero), 0) + 1 from referentiel.section
                                        where lot_id = :lot and supprime_le is null), :nom, :description)
                        returning id
                        """)
                .param("lot", in.lotId())
                .param("nom", nom)
                .param("description", in.description() == null ? "" : in.description().strip())
                .query(UUID.class)
                .single();
        return lireSection(id);
    }

    /** Le lot d'une section ne change pas. */
    @Transactional
    Section modifierSection(Acteur acteur, UUID id, SectionInput in) {
        Section actuelle = lireSection(id);
        if (!actuelle.lotId().equals(in.lotId())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "REQUETE_INVALIDE", "Une section ne peut pas changer de lot.");
        }
        acces.exigerEcriture(acteur, acces.lot(actuelle.lotId()));
        String nom = normaliser(in.name());
        verifierNomSection(actuelle.lotId(), nom, id);
        jdbc.sql("update referentiel.section set nom = :nom, description = :description where id = :id")
                .param("id", id)
                .param("nom", nom)
                .param("description", in.description() == null ? "" : in.description().strip())
                .update();
        return lireSection(id);
    }

    private void verifierNomSection(UUID lot, String nom, UUID sauf) {
        boolean existe = jdbc.sql("""
                        select exists (select 1 from referentiel.section
                                        where lot_id = :lot and supprime_le is null and util.sans_accent(nom) = util.sans_accent(:nom)
                                          and (cast(:sauf as uuid) is null or id <> :sauf))
                        """)
                .param("lot", lot).param("nom", nom).param("sauf", sauf)
                .query(Boolean.class).single();
        if (existe) {
            throw new BusinessException(HttpStatus.CONFLICT, "CONFLIT_DONNEES", "Une section porte déjà ce nom dans ce lot.");
        }
    }

    private Section lireSection(UUID id) {
        return jdbc.sql("select id, lot_id, nom, description from referentiel.section where id = :id and supprime_le is null")
                .param("id", id)
                .query((rs, n) -> new Section(rs.getObject("id", UUID.class), rs.getObject("lot_id", UUID.class),
                        rs.getString("nom"), rs.getString("description")))
                .optional()
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "INTROUVABLE", "Section introuvable."));
    }

    // ------------------------------------------------------------------ groupes

    @Transactional(readOnly = true)
    public List<Group> groupes(Acteur acteur) {
        Perimetre p = acces.perimetre(acteur);
        Optional<Set<UUID>> secteurs = visibles.de(acteur);
        if (secteurs.isPresent() && secteurs.get().isEmpty()) {
            return List.of();
        }
        var requete = jdbc.sql("""
                select g.id, g.lot_id, g.section_id, g.nom, g.village, g.delegue, g.moniteur_compte_id, l.secteur_id,
                       %s as operateur_id
                  from planning.groupe_vulgarisation g join referentiel.lot l on l.id = g.lot_id
                 where g.supprime_le is null %s
                 order by l.numero, g.nom""".formatted(AccesLot.TITULAIRE, secteurs.isPresent() ? "and l.secteur_id in (:ids)" : ""));
        if (secteurs.isPresent()) {
            requete = requete.param("ids", secteurs.get());
        }
        return requete.query((rs, n) -> new Candidat<>(ligneGroupe(rs), rs.getObject("secteur_id", UUID.class),
                        rs.getObject("operateur_id", UUID.class)))
                .list().stream()
                .filter(c -> voit(p, c.operateur(), c.secteur(), c.valeur().lotId(), c.valeur().sectionId()))
                .map(Candidat::valeur)
                .toList();
    }

    @Transactional
    Group creerGroupe(Acteur acteur, GroupInput in) {
        AccesLot.Lot lot = acces.lot(in.lotId());
        acces.exigerEcriture(acteur, lot);
        verifierGroupe(lot, in, null);
        UUID id = jdbc.sql("""
                        insert into planning.groupe_vulgarisation (lot_id, section_id, nom, village, delegue, moniteur_compte_id)
                        values (:lot, :section, :nom, :village, :delegue, :moniteur)
                        returning id
                        """)
                .param("lot", in.lotId())
                .param("section", in.sectionId())
                .param("nom", normaliser(in.name()))
                .param("village", vide(in.village()))
                .param("delegue", vide(in.delegate()))
                .param("moniteur", in.monitorId())
                .query(UUID.class)
                .single();
        return lireGroupe(id);
    }

    /** Le lot et la section d'un groupe ne changent pas. */
    @Transactional
    Group modifierGroupe(Acteur acteur, UUID id, GroupInput in) {
        Group actuel = lireGroupe(id);
        if (!actuel.lotId().equals(in.lotId()) || !actuel.sectionId().equals(in.sectionId())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "REQUETE_INVALIDE",
                    "Le lot et la section d'un groupe ne changent pas.");
        }
        AccesLot.Lot lot = acces.lot(actuel.lotId());
        acces.exigerEcriture(acteur, lot);
        verifierGroupe(lot, in, id);
        jdbc.sql("""
                        update planning.groupe_vulgarisation
                           set nom = :nom, village = :village, delegue = :delegue, moniteur_compte_id = :moniteur
                         where id = :id
                        """)
                .param("id", id)
                .param("nom", normaliser(in.name()))
                .param("village", vide(in.village()))
                .param("delegue", vide(in.delegate()))
                .param("moniteur", in.monitorId())
                .update();
        return lireGroupe(id);
    }

    /** Section du lot, nom libre dans la section, moniteur MO actif du même opérateur et du même secteur. */
    private void verifierGroupe(AccesLot.Lot lot, GroupInput in, UUID sauf) {
        boolean sectionDuLot = jdbc.sql("select exists (select 1 from referentiel.section where id = :s and lot_id = :l and supprime_le is null)")
                .param("s", in.sectionId()).param("l", in.lotId()).query(Boolean.class).single();
        if (!sectionDuLot) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "REQUETE_INVALIDE", "Cette section n'appartient pas à ce lot.");
        }
        if (lot.operateurId() == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "REQUETE_INVALIDE", "Ce lot n'est confié à aucun opérateur.");
        }
        boolean nomPris = jdbc.sql("""
                        select exists (select 1 from planning.groupe_vulgarisation
                                        where section_id = :s and supprime_le is null
                                          and util.sans_accent(nom) = util.sans_accent(:nom)
                                          and (cast(:sauf as uuid) is null or id <> :sauf))
                        """)
                .param("s", in.sectionId()).param("nom", normaliser(in.name())).param("sauf", sauf)
                .query(Boolean.class).single();
        if (nomPris) {
            throw new BusinessException(HttpStatus.CONFLICT, "CONFLIT_DONNEES", "Un groupe porte déjà ce nom dans cette section.");
        }
        boolean moniteurValide = jdbc.sql("""
                        select exists (
                          select 1 from iam.compte c
                           where c.id = :m and c.profil = 'MO' and c.statut <> 'DESACTIVE' and c.supprime_le is null
                             and c.operateur_id = :operateur
                             and exists (select 1 from iam.affectation f
                                          where f.compte_id = c.id and f.secteur_id = :secteur
                                            and f.fin is null and f.supprime_le is null))
                        """)
                .param("m", in.monitorId()).param("operateur", lot.operateurId()).param("secteur", lot.secteurId())
                .query(Boolean.class).single();
        if (!moniteurValide) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "REQUETE_INVALIDE",
                    "Le moniteur doit être un compte MO actif de cet opérateur, affecté à ce secteur.");
        }
    }

    private Group lireGroupe(UUID id) {
        return jdbc.sql("""
                        select id, lot_id, section_id, nom, village, delegue, moniteur_compte_id
                          from planning.groupe_vulgarisation where id = :id and supprime_le is null
                        """)
                .param("id", id)
                .query((rs, n) -> ligneGroupe(rs))
                .optional()
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "INTROUVABLE", "Groupe introuvable."));
    }

    private static Group ligneGroupe(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Group(rs.getObject("id", UUID.class), rs.getObject("lot_id", UUID.class),
                rs.getObject("section_id", UUID.class), rs.getString("nom"),
                rs.getString("village") == null ? "" : rs.getString("village"),
                rs.getString("delegue") == null ? "" : rs.getString("delegue"),
                rs.getObject("moniteur_compte_id", UUID.class));
    }

    // ------------------------------------------------------------------ utilitaires

    /** Visibilité d'une section ou d'un groupe : l'opérateur titulaire du lot doit être visible du compte. */
    private static boolean voit(Perimetre p, UUID operateurDuLot, UUID secteur, UUID lot, UUID section) {
        if (p.estNational()) {
            return true;
        }
        return operateurDuLot != null && p.voitSection(operateurDuLot, secteur, lot, section);
    }

    /** Espaces de bord retirés, espaces multiples réduits : l'unicité ne tient pas compte des espaces en trop. */
    static String normaliser(String nom) {
        return nom.strip().replaceAll("\\s+", " ");
    }

    private static String vide(String texte) {
        return texte == null || texte.isBlank() ? null : texte.strip();
    }
}
