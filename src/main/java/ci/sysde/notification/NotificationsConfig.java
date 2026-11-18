package ci.sysde.notification;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Active les tâches planifiées (battement de cœur des flux de notifications). */
@Configuration
@EnableScheduling
class NotificationsConfig {
}
