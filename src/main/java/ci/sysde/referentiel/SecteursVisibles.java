package ci.sysde.referentiel;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import ci.sysde.security.Acteur;
import ci.sysde.security.Perimetre;
import ci.sysde.security.PerimetreService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Secteurs qu'un compte a le droit de voir, relus en base à chaque appel (jamais lus dans le jeton).
 * AD et SU : tous. CD : les secteurs où son opérateur a une attribution. Autres : ceux de leurs affectations.
 */
@Component
public class SecteursVisibles {

    private final PerimetreService perimetres;
    private final JdbcClient jdbc;

    public SecteursVisibles(PerimetreService perimetres, JdbcClient jdbc) {
        this.perimetres = perimetres;
        this.jdbc = jdbc;
    }

    /** {@code Optional.empty()} : aucun filtre (tous les secteurs). Sinon, l'ensemble autorisé (éventuellement vide). */
    public Optional<Set<UUID>> de(Acteur acteur) {
        Perimetre p = perimetres.de(acteur);
        return switch (p.etendue()) {
            case NATIONAL -> Optional.empty();
            case OPERATEUR -> Optional.of(new HashSet<>(jdbc.sql("""
                            select distinct a.secteur_id
                              from gestion.attribution a
                             where a.operateur_id = :operateur and a.supprime_le is null
                            """)
                    .param("operateur", p.operateurId())
                    .query(UUID.class)
                    .list()));
            case AFFECTATIONS -> Optional.of(p.secteurs());
        };
    }
}
