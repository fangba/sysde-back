package ci.sysde.security;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Crochet unique : au début de toute méthode transactionnelle, transmet le compte connecté à la base
 * ({@code set_config('app.compte_id', ..., true)}), pour JPA comme pour jOOQ. La valeur ne vit que le temps
 * de la transaction : compatible PgBouncer en mode transaction. Les triggers s'en servent pour
 * {@code cree_par}, {@code modifie_par} et la piste d'audit.
 *
 * <p>S'exécute après l'ouverture de la transaction (ordre le plus bas).
 */
@Aspect
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
class ActeurTransaction {

    private final JdbcClient jdbc;

    ActeurTransaction(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Around("@annotation(org.springframework.transaction.annotation.Transactional)"
            + " || @within(org.springframework.transaction.annotation.Transactional)")
    Object poser(ProceedingJoinPoint pjp) throws Throwable {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken) && TransactionSynchronizationManager.isActualTransactionActive()
                && !TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            jdbc.sql("select set_config('app.compte_id', :id, true)").param("id", auth.getName()).query().singleValue();
        }
        return pjp.proceed();
    }
}
