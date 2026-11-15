package ci.sysde.plantation;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import ci.sysde.common.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Enveloppe l'adaptateur HTTP : <strong>cache court</strong> (par secteur, et par plantation), <strong>donnée périmée
 * tolérée</strong> si la source tombe (mieux qu'une erreur pour une liste ou une carte), <strong>coupe-circuit</strong>.
 * <ul>
 *   <li>une lecture est servie du cache pendant {@code sysde.plantations.cache} (60 s) ;</li>
 *   <li>au-delà, on recharge ; si la source échoue (ou si le circuit est ouvert), on sert l'ancienne valeur tant qu'elle a
 *       moins de {@code sysde.plantations.cache-perime} (10 min), sinon l'erreur remonte ;</li>
 *   <li>un seul chargement à la fois par clé (les autres attendent le résultat : pas de ruée sur le tiers) ;</li>
 *   <li>{@code sysde.plantations.coupe-circuit.echecs} échecs de suite (5) ouvrent le circuit pour
 *       {@code sysde.plantations.coupe-circuit.pause} (30 s).</li>
 * </ul>
 * Le cache est en mémoire de l'instance. Il n'est pas vidé par les affectations (elles sont propres à SysDE).
 */
@Component
@Primary
@ConditionalOnProperty("sysde.plantations.url")
class SourcePlantationsResiliente implements SourcePlantations {

    private static final Logger JOURNAL = LoggerFactory.getLogger(SourcePlantationsResiliente.class);
    private static final int PLANTATIONS_MAX = 10_000;

    private record Entree(Object valeur, Instant chargeeLe) {
    }

    private final SourcePlantations delegue;
    private final Clock horloge;
    private final Duration ttl;
    private final Duration perime;
    private final CoupeCircuit circuit;
    private final Map<String, Entree> listes = new ConcurrentHashMap<>();
    private final Map<String, Entree> unites = Collections.synchronizedMap(new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Entree> eldest) {
            return size() > PLANTATIONS_MAX;
        }
    });
    private final Map<String, Object> verrous = new ConcurrentHashMap<>();

    SourcePlantationsResiliente(@Qualifier("sourcePlantationsHttp") SourcePlantations delegue, Clock horloge,
                                @Value("${sysde.plantations.cache:PT60S}") Duration ttl,
                                @Value("${sysde.plantations.cache-perime:PT10M}") Duration perime,
                                @Value("${sysde.plantations.coupe-circuit.echecs:5}") int echecsMax,
                                @Value("${sysde.plantations.coupe-circuit.pause:PT30S}") Duration pause) {
        this.delegue = delegue;
        this.horloge = horloge;
        this.ttl = ttl;
        this.perime = perime;
        this.circuit = new CoupeCircuit(echecsMax, pause, horloge);
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<PlantationExterne> lister(Set<String> codesSecteur) {
        if (codesSecteur == null) {
            return (List<PlantationExterne>) lire(listes, "*", () -> delegue.lister(null));
        }
        List<PlantationExterne> toutes = new ArrayList<>();
        for (String code : new TreeSet<>(codesSecteur)) {
            toutes.addAll((List<PlantationExterne>) lire(listes, code, () -> delegue.lister(Set.of(code))));
        }
        return toutes;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<PlantationExterne> trouver(String ref) {
        return (Optional<PlantationExterne>) lire(unites, ref, () -> delegue.trouver(ref));
    }

    private Object lire(Map<String, Entree> cache, String cle, Supplier<Object> chargement) {
        Entree e = cache.get(cle);
        if (frais(e)) {
            return e.valeur();
        }
        // Un seul chargement à la fois par clé ; les autres lecteurs attendent puis relisent le cache.
        synchronized (verrous.computeIfAbsent(cache == listes ? "L" + cle : "U" + cle, k -> new Object())) {
            e = cache.get(cle);
            if (frais(e)) {
                return e.valeur();
            }
            try {
                Object valeur = circuit.appeler(() -> charger(chargement));
                cache.put(cle, new Entree(valeur, horloge.instant()));
                return valeur;
            } catch (BusinessException indisponible) {
                if (e != null && horloge.instant().isBefore(e.chargeeLe().plus(perime))) {
                    JOURNAL.warn("Base des plantations indisponible ({}) : données du cache servies.", indisponible.getMessage());
                    return e.valeur();
                }
                throw indisponible;
            }
        }
    }

    /** Une réponse illisible du tiers compte comme un échec, au même titre qu'une panne réseau. */
    private static Object charger(Supplier<Object> chargement) {
        try {
            return chargement.get();
        } catch (BusinessException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new BusinessException(HttpStatus.BAD_GATEWAY, "SOURCE_PLANTATIONS_INDISPONIBLE",
                    "La base des plantations a renvoyé une réponse illisible (" + e.getClass().getSimpleName() + ").");
        }
    }

    private boolean frais(Entree e) {
        return e != null && horloge.instant().isBefore(e.chargeeLe().plus(ttl));
    }
}
