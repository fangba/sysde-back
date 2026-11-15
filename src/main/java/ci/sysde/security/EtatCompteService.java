package ci.sysde.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * État des comptes relu côté serveur (PRO-17 : permissions non figées dans le jeton ; ORG-06 : opérateur désactivé
 * = jetons rejetés), avec un cache de courte durée (moins d'une minute). Le cache est vidé à chaque changement de
 * permissions ou d'opérateur sur cette instance : l'effet est immédiat ; sur une autre instance, il est borné par la durée.
 *
 * <p>Seuls les comptes qui ont encore un accès (actifs, ou en envoi seul) sont gardés en cache : un compte réactivé,
 * ou qui se reconnecte, n'est jamais refusé à cause d'une réponse négative périmée. Un compte en envoi seul dont le
 * délai expire reste admis au plus la durée du cache (30 s).
 */
@Service
public class EtatCompteService {

    private static final int TAILLE_MAX = 50_000;

    private record Entree(EtatCompte etat, Instant expire) {
    }

    private final EtatCompteSource source;
    private final Clock horloge;
    private final Duration duree;
    private final ConcurrentHashMap<UUID, Entree> cache = new ConcurrentHashMap<>();

    EtatCompteService(EtatCompteSource source, Clock horloge,
                      @Value("${sysde.securite.cache-etat-compte:PT30S}") Duration duree) {
        this.source = source;
        this.horloge = horloge;
        this.duree = duree;
    }

    /** Vide : le compte ne peut plus agir (désactivé, ou son utilisateur ou son opérateur). */
    public Optional<EtatCompte> de(UUID compteId) {
        Instant maintenant = horloge.instant();
        Entree entree = cache.get(compteId);
        if (entree != null && entree.expire().isAfter(maintenant)) {
            return Optional.of(entree.etat());
        }
        Optional<EtatCompte> charge = source.charger(compteId);
        if (charge.isPresent()) {
            if (cache.size() >= TAILLE_MAX) {
                cache.clear();
            }
            cache.put(compteId, new Entree(charge.get(), maintenant.plus(duree)));
        } else {
            cache.remove(compteId);
        }
        return charge;
    }

    /** À appeler après tout changement qui touche les droits ou l'état de comptes : permissions, opérateur, compte. */
    public void invalider() {
        cache.clear();
    }

    public void invalider(UUID compteId) {
        cache.remove(compteId);
    }
}
