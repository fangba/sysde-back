package ci.sysde.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Limite le nombre de requêtes par adresse IP sur {@code /auth/login} et {@code /auth/refresh} (fenêtre glissante).
 * Complète le verrouillage par compte : celui-ci protège un matricule, celui-là freine un attaquant qui en essaie
 * beaucoup. L'état est en mémoire de l'instance : avec plusieurs instances, la limite se multiplie ; derrière un
 * proxy, {@code server.forward-headers-strategy} doit être réglé pour que l'adresse vue soit celle du client.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class LimiteDebitFiltre extends OncePerRequestFilter {

    private static final Set<String> ROUTES = Set.of("/auth/login", "/auth/refresh");
    private static final int ADRESSES_MAX = 100_000;

    private final DebitProperties regles;
    private final Clock horloge;
    private final ConcurrentHashMap<String, ArrayDeque<Long>> passages = new ConcurrentHashMap<>();

    LimiteDebitFiltre(DebitProperties regles, Clock horloge) {
        this.regles = regles;
        this.horloge = horloge;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest requete) {
        return !"POST".equals(requete.getMethod()) || !ROUTES.contains(requete.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest requete, HttpServletResponse reponse, FilterChain suite)
            throws ServletException, IOException {
        long attente = attente(requete.getRemoteAddr());
        if (attente > 0) {
            reponse.setStatus(429);
            reponse.setHeader("Retry-After", String.valueOf(attente));
            reponse.setContentType("application/problem+json");
            reponse.setCharacterEncoding(StandardCharsets.UTF_8.name());
            String message = "Trop de tentatives : réessayez dans " + attente + " s.";
            reponse.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Too Many Requests\",\"status\":429,\"detail\":\""
                    + message + "\",\"code\":\"TROP_DE_REQUETES\",\"message\":\"" + message + "\"}");
            return;
        }
        suite.doFilter(requete, reponse);
    }

    /** 0 : passe (et la requête est comptée) ; sinon, secondes à attendre. */
    long attente(String adresse) {
        long maintenant = horloge.millis();
        long fenetre = regles.fenetre().toMillis();
        if (passages.size() >= ADRESSES_MAX) {
            passages.clear();
        }
        ArrayDeque<Long> file = passages.computeIfAbsent(adresse, a -> new ArrayDeque<>());
        synchronized (file) {
            while (!file.isEmpty() && file.peekFirst() <= maintenant - fenetre) {
                file.pollFirst();
            }
            if (file.size() >= regles.max()) {
                return Math.max(1, (file.peekFirst() + fenetre - maintenant + 999) / 1000);
            }
            file.addLast(maintenant);
            return 0;
        }
    }
}
