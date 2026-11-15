package ci.sysde.plantation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import ci.sysde.common.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** Cache, donnée périmée tolérée et coupe-circuit, avec une horloge réglable et une source factice. */
class SourcePlantationsResilienteTest {

    private static final class Horloge extends java.time.Clock {
        Instant t = Instant.parse("2026-11-15T10:00:00Z");

        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public java.time.Clock withZone(java.time.ZoneId z) { return this; }
        @Override public Instant instant() { return t; }
        void avancer(long secondes) { t = t.plusSeconds(secondes); }
    }

    /** Source factice : compte ses appels, peut tomber en panne ou répondre n'importe quoi. */
    private static final class Source implements SourcePlantations {
        final AtomicInteger appels = new AtomicInteger();
        volatile boolean panne;
        volatile boolean illisible;
        volatile String ref = "P1";

        @Override
        public List<PlantationExterne> lister(Set<String> codes) {
            appels.incrementAndGet();
            sortirSiPanne();
            return List.of(plantation(ref));
        }

        @Override
        public Optional<PlantationExterne> trouver(String r) {
            appels.incrementAndGet();
            sortirSiPanne();
            return "absente".equals(r) ? Optional.empty() : Optional.of(plantation(r));
        }

        private void sortirSiPanne() {
            if (illisible) {
                throw new ClassCastException("réponse inattendue");
            }
            if (panne) {
                throw new BusinessException(HttpStatus.BAD_GATEWAY, "SOURCE_PLANTATIONS_INDISPONIBLE", "panne");
            }
        }
    }

    private static PlantationExterne plantation(String ref) {
        return new PlantationExterne(ref, "C-" + ref, "Planteur", null, null, "X", "01", 1, "", BigDecimal.TEN, 2015, null, true,
                null, null, List.of());
    }

    private final Horloge horloge = new Horloge();
    private final Source source = new Source();
    private final SourcePlantationsResiliente cache = new SourcePlantationsResiliente(source, horloge, Duration.ofSeconds(60),
            Duration.ofMinutes(10), 3, Duration.ofSeconds(30));

    @Test
    void uneLectureRecenteEstServieDuCacheEtRechargeeApresLeDelai() {
        assertThat(cache.lister(Set.of("01"))).extracting(PlantationExterne::ref).containsExactly("P1");
        cache.lister(Set.of("01"));
        horloge.avancer(59);
        cache.lister(Set.of("01"));
        assertThat(source.appels.get()).isEqualTo(1);
        horloge.avancer(2);
        source.ref = "P2";
        assertThat(cache.lister(Set.of("01"))).extracting(PlantationExterne::ref).containsExactly("P2");
        assertThat(source.appels.get()).isEqualTo(2);
        // un autre secteur est une autre entrée ; plusieurs secteurs sont agrégés
        assertThat(cache.lister(Set.of("01", "02"))).hasSize(2);
        assertThat(source.appels.get()).isEqualTo(3);
    }

    @Test
    void uneAbsenceEstAussiMiseEnCacheEtNEstPasUnEchec() {
        assertThat(cache.trouver("absente")).isEmpty();
        assertThat(cache.trouver("absente")).isEmpty();
        assertThat(source.appels.get()).isEqualTo(1);
        assertThat(cache.trouver("P9")).isPresent();
    }

    @Test
    void siLaSourceTombeOnServiraLAncienneValeurTantQuElleNEstPasTropVieille() {
        cache.lister(Set.of("01"));
        source.panne = true;
        horloge.avancer(120);                                      // périmée pour le cache, pas pour la tolérance
        assertThat(cache.lister(Set.of("01"))).extracting(PlantationExterne::ref).containsExactly("P1");
        horloge.avancer(600);                                      // plus de 10 minutes : l'erreur remonte
        assertThatThrownBy(() -> cache.lister(Set.of("01"))).isInstanceOf(BusinessException.class).hasMessage("panne");
        // sans aucune valeur en cache, l'erreur remonte aussitôt
        assertThatThrownBy(() -> cache.lister(Set.of("02"))).isInstanceOf(BusinessException.class);
    }

    @Test
    void apresDesEchecsRepetesLeCircuitS_ouvrePuisSeReferme() {
        source.panne = true;
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> cache.lister(Set.of("0" + (source.appels.get() % 9 + 1)))).hasMessage("panne");
        }
        int avant = source.appels.get();
        // circuit ouvert : échec immédiat, la source n'est plus appelée
        assertThatThrownBy(() -> cache.lister(Set.of("08"))).isInstanceOf(BusinessException.class).hasMessageContaining("temporairement coupée");
        assertThat(source.appels.get()).isEqualTo(avant);
        // la pause écoulée, un essai passe : toujours en panne, le circuit se rouvre aussitôt
        horloge.avancer(31);
        assertThatThrownBy(() -> cache.lister(Set.of("08"))).hasMessage("panne");
        assertThat(source.appels.get()).isEqualTo(avant + 1);
        assertThatThrownBy(() -> cache.lister(Set.of("08"))).hasMessageContaining("temporairement coupée");
        // la source revient : après la pause, l'essai réussit et tout reprend
        source.panne = false;
        horloge.avancer(31);
        assertThat(cache.lister(Set.of("08"))).hasSize(1);
        assertThat(cache.lister(Set.of("09"))).hasSize(1);
    }

    @Test
    void uneReponseIllisibleCompteCommeUnEchec() {
        source.illisible = true;
        assertThatThrownBy(() -> cache.trouver("P1")).isInstanceOf(BusinessException.class).hasMessageContaining("illisible");
    }

    @Test
    void lesAppelsConcurrentsNeChargentQuUneFoisLaMemeCle() throws Exception {
        var pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        try {
            var taches = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 16; i++) {
                taches.add(pool.submit(() -> cache.lister(Set.of("05"))));
            }
            for (var t : taches) {
                t.get();
            }
        } finally {
            pool.shutdown();
        }
        assertThat(source.appels.get()).isEqualTo(1);
    }
}
