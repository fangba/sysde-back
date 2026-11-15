package ci.sysde.security;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Relit en base l'étendue du compte connecté (affectations en cours). Jamais lue dans le jeton. */
@Service
public class PerimetreService {

    private final JdbcClient jdbc;

    public PerimetreService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Perimetre de(Acteur acteur) {
        if (acteur.estProfil(Acteur.ADMINISTRATEUR, Acteur.SUPERVISEUR)) {
            return Perimetre.national(acteur.operateurId());
        }
        if (acteur.estProfil(Acteur.CHEF_DEPARTEMENT)) {
            return Perimetre.operateur(acteur.operateurId());
        }
        Set<UUID> secteurs = new HashSet<>();
        Set<UUID> secteursEntiers = new HashSet<>();
        Set<UUID> lotsEntiers = new HashSet<>();
        Set<UUID> sections = new HashSet<>();

        jdbc.sql("""
                        select secteur_id, lot_id, section_id
                          from iam.affectation
                         where compte_id = :compte
                           and supprime_le is null
                           and debut <= current_date
                           and (fin is null or fin >= current_date)
                        """)
                .param("compte", acteur.compteId())
                .query((rs, n) -> {
                    UUID secteur = rs.getObject("secteur_id", UUID.class);
                    UUID lot = rs.getObject("lot_id", UUID.class);
                    UUID section = rs.getObject("section_id", UUID.class);
                    secteurs.add(secteur);
                    if (lot == null) {
                        secteursEntiers.add(secteur);
                    } else if (section == null) {
                        lotsEntiers.add(lot);
                    } else {
                        sections.add(section);
                    }
                    return secteur;
                })
                .list();
        return Perimetre.affectations(acteur.operateurId(), secteurs, secteursEntiers, lotsEntiers, sections);
    }
}
