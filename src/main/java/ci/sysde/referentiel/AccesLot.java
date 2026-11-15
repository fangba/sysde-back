package ci.sysde.referentiel;

import java.util.UUID;

import ci.sysde.common.BusinessException;
import ci.sysde.security.Acteur;
import ci.sysde.security.Perimetre;
import ci.sysde.security.PerimetreService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Qui peut agir sur un lot : un lot est « confié » à l'opérateur titulaire de son attribution en cours.
 * Le chef secteur n'écrit que sur les lots de son secteur confiés à son opérateur ; AD et SU (s'ils ont la
 * permission) sur tous les lots.
 */
@Component
class AccesLot {

    /** Opérateur titulaire du lot aujourd'hui (sous-requête réutilisable, alias de lot : {@code l}). */
    static final String TITULAIRE = """
            (select c.operateur_id from gestion.attribution_lot al join gestion.contrat c on c.id = al.contrat_id
              where al.lot_id = l.id and al.periode @> current_date limit 1)""";

    record Lot(UUID id, UUID secteurId, UUID operateurId) {
    }

    private final JdbcClient jdbc;
    private final PerimetreService perimetres;

    AccesLot(JdbcClient jdbc, PerimetreService perimetres) {
        this.jdbc = jdbc;
        this.perimetres = perimetres;
    }

    Perimetre perimetre(Acteur acteur) {
        return perimetres.de(acteur);
    }

    /** 400 si le lot n'existe pas. {@code operateurId} est nul si le lot n'est attribué à personne aujourd'hui. */
    Lot lot(UUID lotId) {
        return jdbc.sql("select l.id, l.secteur_id, " + TITULAIRE + " as operateur_id from referentiel.lot l"
                        + " where l.id = :id and l.supprime_le is null")
                .param("id", lotId)
                .query((rs, n) -> new Lot(rs.getObject("id", UUID.class), rs.getObject("secteur_id", UUID.class),
                        rs.getObject("operateur_id", UUID.class)))
                .optional()
                .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST, "REQUETE_INVALIDE", "Lot inconnu."));
    }

    /** 403 si le compte n'a pas la main sur ce lot. */
    void exigerEcriture(Acteur acteur, Lot lot) {
        Perimetre p = perimetres.de(acteur);
        if (p.estNational()) {
            return;
        }
        if (lot.operateurId() == null || !p.voitLot(lot.operateurId(), lot.secteurId(), lot.id())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ACCES_REFUSE",
                    "Ce lot n'est pas confié à votre opérateur dans votre secteur.");
        }
    }
}
