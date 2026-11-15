package ci.sysde.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

class EtatCompteServiceTest {

    private final UUID compte = UUID.randomUUID();
    private final AtomicInteger chargements = new AtomicInteger();
    private final AtomicReference<Optional<EtatCompte>> reponse = new AtomicReference<>(
            Optional.of(new EtatCompte(UUID.randomUUID(), "SAPH-00001", "CS", UUID.randomUUID(), Set.of("GERER_SECTION"), null)));
    private final AtomicReference<Instant> maintenant = new AtomicReference<>(Instant.parse("2026-11-15T10:00:00Z"));
    private final Clock horloge = new Clock() {
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return maintenant.get(); }
    };
    private final EtatCompteService service = new EtatCompteService(id -> {
        chargements.incrementAndGet();
        return reponse.get();
    }, horloge, Duration.ofSeconds(30));

    @Test
    void lEtatEstGardeEnCacheJusquALExpiration() {
        service.de(compte);
        service.de(compte);
        assertThat(chargements).hasValue(1);

        maintenant.set(maintenant.get().plusSeconds(31));
        service.de(compte);
        assertThat(chargements).hasValue(2);
    }

    @Test
    void invaliderForceLaRelecture() {
        service.de(compte);
        service.invalider();
        service.de(compte);
        assertThat(chargements).hasValue(2);
    }

    @Test
    void uneReponseNegativeNestJamaisGardeeEtLeCompteReactiveRevient() {
        reponse.set(Optional.empty());
        assertThat(service.de(compte)).isEmpty();
        assertThat(service.de(compte)).isEmpty();
        assertThat(chargements).hasValue(2);                 // relu à chaque fois

        reponse.set(Optional.of(new EtatCompte(compte, "SAPH-00001", "CS", UUID.randomUUID(), Set.of(), null)));
        assertThat(service.de(compte)).isPresent();          // réactivé : accepté tout de suite
    }

    @Test
    void unCompteDevenuInactifEstRefuseAuPlusTardALExpiration() {
        assertThat(service.de(compte)).isPresent();
        reponse.set(Optional.empty());
        assertThat(service.de(compte)).isPresent();          // encore en cache
        maintenant.set(maintenant.get().plusSeconds(31));
        assertThat(service.de(compte)).isEmpty();            // la borne : moins d'une minute
    }
}
