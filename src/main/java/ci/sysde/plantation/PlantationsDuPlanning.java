package ci.sysde.plantation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import ci.sysde.common.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Vérifie les plantations citées par les lignes de planning (visites) : la plantation existe dans la base externe et se
 * trouve dans le lot de la ligne. Désactivée par défaut ({@code sysde.plantations.verification-planning}) tant que le
 * contrat réel du tiers n'est pas connu : un contrat faux bloquerait la saisie des plannings.
 * Si la source est injoignable ou non configurée, la saisie n'est pas bloquée (la vérification est sautée, avec une alerte).
 */
@Component
public class PlantationsDuPlanning {

    private static final Logger JOURNAL = LoggerFactory.getLogger(PlantationsDuPlanning.class);

    /** Une plantation citée par une ligne, dans le lot de cette ligne. */
    public record Citation(String ref, UUID lotId) {
    }

    private final SourcePlantations source;
    private final JdbcClient jdbc;
    private final boolean active;

    PlantationsDuPlanning(SourcePlantations source, JdbcClient jdbc,
                          @Value("${sysde.plantations.verification-planning:false}") boolean active) {
        this.source = source;
        this.jdbc = jdbc;
        this.active = active;
    }

    /** 400 si une plantation est inconnue de la base externe ou n'est pas dans le lot indiqué. */
    public void verifier(List<Citation> citations) {
        if (!active || citations.isEmpty()) {
            return;
        }
        Map<String, UUID> lotParRef = new HashMap<>();
        for (Citation c : citations) {
            lotParRef.putIfAbsent(c.ref(), c.lotId());
        }
        for (Map.Entry<String, UUID> e : lotParRef.entrySet()) {
            PlantationExterne p;
            try {
                p = source.trouver(e.getKey()).orElse(null);
            } catch (BusinessException indisponible) {
                JOURNAL.warn("Vérification des plantations du planning sautée : {}", indisponible.getMessage());
                return;
            }
            if (p == null) {
                throw invalide("La plantation « " + e.getKey() + " » n'existe pas dans la base des plantations.");
            }
            if (e.getValue() != null && !dansLeLot(p, e.getValue())) {
                throw invalide("La plantation « " + e.getKey() + " » n'est pas dans le lot indiqué (secteur " + p.secteurCode()
                        + ", lot " + p.lot() + ").");
            }
        }
    }

    private boolean dansLeLot(PlantationExterne p, UUID lotId) {
        return jdbc.sql("""
                        select count(*) from referentiel.lot l join referentiel.secteur s on s.id = l.secteur_id
                         where l.id = :lot and s.code = :code and l.numero = :numero
                        """)
                .param("lot", lotId).param("code", p.secteurCode()).param("numero", p.lot())
                .query(Long.class).single() > 0;
    }

    private static BusinessException invalide(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, "REQUETE_INVALIDE", message);
    }
}
