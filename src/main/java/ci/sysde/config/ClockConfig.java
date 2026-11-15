package ci.sysde.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class ClockConfig {

    /** Horloge injectée : les règles de verrouillage et d'expiration restent testables. */
    @Bean
    Clock horloge() {
        return Clock.systemUTC();
    }
}
