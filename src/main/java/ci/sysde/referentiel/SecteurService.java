package ci.sysde.referentiel;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import ci.sysde.common.BusinessException;
import ci.sysde.referentiel.ReferentielDtos.Lot;
import ci.sysde.referentiel.ReferentielDtos.LotInput;
import ci.sysde.referentiel.ReferentielDtos.Sector;
import ci.sysde.referentiel.ReferentielDtos.SectorInput;
import ci.sysde.security.Acteur;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Secteurs et lots : lecture bornée par le périmètre, écriture réservée à {@code GERER_REFERENTIEL} (contrôlée par le contrôleur). */
@Service
public class SecteurService {

    private final JdbcClient jdbc;
    private final SecteursVisibles visibles;

    SecteurService(JdbcClient jdbc, SecteursVisibles visibles) {
        this.jdbc = jdbc;
        this.visibles = visibles;
    }

    // ------------------------------------------------------------------ secteurs

    @Transactional(readOnly = true)
    public List<Sector> secteurs(Acteur acteur) {
        Optional<Set<UUID>> ids = visibles.de(acteur);
        if (ids.isPresent() && ids.get().isEmpty()) {
            return List.of();
        }
        String filtre = ids.isPresent() ? " and s.id in (:ids)" : "";
        var requete = jdbc.sql("select s.id, s.code, s.nom from referentiel.secteur s where s.supprime_le is null"
                + filtre + " order by s.code");
        if (ids.isPresent()) {
            requete = requete.param("ids", ids.get());
        }
        return requete.query((rs, n) -> new Sector(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("nom")))
                .list();
    }

    @Transactional
    Sector creerSecteur(SectorInput in) {
        UUID id = jdbc.sql("insert into referentiel.secteur (code, nom) values (:code, :nom) returning id")
                .param("code", in.code().strip())
                .param("nom", in.name().strip())
                .query(UUID.class)
                .single();
        return lireSecteur(id);
    }

    @Transactional
    Sector modifierSecteur(UUID id, SectorInput in) {
        int n = jdbc.sql("update referentiel.secteur set code = :code, nom = :nom where id = :id and supprime_le is null")
                .param("id", id)
                .param("code", in.code().strip())
                .param("nom", in.name().strip())
                .update();
        if (n == 0) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "INTROUVABLE", "Secteur introuvable.");
        }
        return lireSecteur(id);
    }

    private Sector lireSecteur(UUID id) {
        return jdbc.sql("select id, code, nom from referentiel.secteur where id = :id and supprime_le is null")
                .param("id", id)
                .query((rs, n) -> new Sector(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("nom")))
                .single();
    }

    // ------------------------------------------------------------------ lots

    @Transactional(readOnly = true)
    public List<Lot> lots(Acteur acteur) {
        Optional<Set<UUID>> ids = visibles.de(acteur);
        if (ids.isPresent() && ids.get().isEmpty()) {
            return List.of();
        }
        String filtre = ids.isPresent() ? " and l.secteur_id in (:ids)" : "";
        var requete = jdbc.sql("select l.id, l.secteur_id, l.numero, l.description from referentiel.lot l"
                + " where l.supprime_le is null" + filtre + " order by l.numero");
        if (ids.isPresent()) {
            requete = requete.param("ids", ids.get());
        }
        return requete.query(SecteurService::ligneLot).list();
    }

    @Transactional
    Lot creerLot(LotInput in) {
        UUID id = jdbc.sql("""
                        insert into referentiel.lot (secteur_id, numero, description)
                        values (:secteur, :numero, :description)
                        returning id
                        """)
                .param("secteur", in.sectorId())
                .param("numero", in.number())
                .param("description", in.description() == null ? "" : in.description().strip())
                .query(UUID.class)
                .single();
        return lireLot(id);
    }

    /** Le secteur d'un lot ne change pas : ses attributions en dépendent. */
    @Transactional
    Lot modifierLot(UUID id, LotInput in) {
        Lot actuel = lireLot(id);
        if (!actuel.sectorId().equals(in.sectorId())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "REQUETE_INVALIDE",
                    "Un lot ne peut pas changer de secteur.");
        }
        jdbc.sql("update referentiel.lot set numero = :numero, description = :description where id = :id")
                .param("id", id)
                .param("numero", in.number())
                .param("description", in.description() == null ? "" : in.description().strip())
                .update();
        return lireLot(id);
    }

    private Lot lireLot(UUID id) {
        return jdbc.sql("select id, secteur_id, numero, description from referentiel.lot where id = :id and supprime_le is null")
                .param("id", id)
                .query(SecteurService::ligneLot)
                .optional()
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "INTROUVABLE", "Lot introuvable."));
    }

    private static Lot ligneLot(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Lot(rs.getObject("id", UUID.class), rs.getObject("secteur_id", UUID.class), rs.getInt("numero"),
                rs.getString("description"));
    }
}
