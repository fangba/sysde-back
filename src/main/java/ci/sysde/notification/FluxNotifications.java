package ci.sysde.notification;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

import ci.sysde.notification.NotificationService.Notification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Notifications poussées en temps réel (Server-Sent Events) aux navigateurs connectés. Un flux appartient à un compte ; il
 * ne vit que {@link #DUREE_MS} (moins que la durée d'un jeton d'accès) : le client se reconnecte avec un jeton à jour, ce
 * qui borne aussi le temps pendant lequel un compte désactivé reste abonné. Un battement de cœur périodique garde la
 * connexion ouverte à travers les proxys. Le registre est local à l'instance : avec plusieurs instances, le client garde sa
 * relecture périodique de {@code GET /notifications}.
 */
@Component
class FluxNotifications {

    static final long DUREE_MS = 10 * 60_000L;

    private static final Logger LOG = LoggerFactory.getLogger(FluxNotifications.class);

    private final Map<UUID, Set<SseEmitter>> abonnes = new ConcurrentHashMap<>();

    SseEmitter abonner(UUID compte) {
        SseEmitter flux = new SseEmitter(DUREE_MS);
        Set<SseEmitter> duCompte = abonnes.computeIfAbsent(compte, c -> new CopyOnWriteArraySet<>());
        duCompte.add(flux);
        Runnable retirer = () -> retirer(compte, flux);
        flux.onCompletion(retirer);
        flux.onTimeout(retirer);
        flux.onError(e -> retirer.run());
        try {
            flux.send(SseEmitter.event().comment("connecte"));
        } catch (IOException | IllegalStateException e) {
            retirer.run();
        }
        return flux;
    }

    void pousser(UUID compte, Notification notification) {
        Set<SseEmitter> flux = abonnes.get(compte);
        if (flux == null) {
            return;
        }
        for (SseEmitter f : flux) {
            envoyer(compte, f, SseEmitter.event().id(notification.id().toString()).name("notification").data(notification));
        }
    }

    @Scheduled(fixedDelay = 25_000)
    void battementDeCoeur() {
        abonnes.forEach((compte, flux) -> flux.forEach(f -> envoyer(compte, f, SseEmitter.event().comment("battement"))));
    }

    int nombreAbonnes(UUID compte) {
        Set<SseEmitter> flux = abonnes.get(compte);
        return flux == null ? 0 : flux.size();
    }

    private void envoyer(UUID compte, SseEmitter flux, SseEmitter.SseEventBuilder evenement) {
        try {
            flux.send(evenement);
        } catch (IOException | IllegalStateException e) {
            // Client parti : on oublie ce flux, il se reconnectera.
            LOG.debug("Flux de notifications fermé pour {}", compte);
            retirer(compte, flux);
        }
    }

    private void retirer(UUID compte, SseEmitter flux) {
        abonnes.computeIfPresent(compte, (c, set) -> {
            set.remove(flux);
            return set.isEmpty() ? null : set;
        });
    }
}
