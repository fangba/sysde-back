package ci.sysde.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class LimiteDebitFiltreTest {

    /** Horloge réglable. */
    private static final class Horloge extends java.time.Clock {
        Instant t = Instant.parse("2026-11-15T10:00:00Z");

        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public java.time.Clock withZone(java.time.ZoneId z) { return this; }
        @Override public Instant instant() { return t; }
    }

    private int appeler(LimiteDebitFiltre f, String ip, String chemin) throws Exception {
        var req = new MockHttpServletRequest("POST", chemin);
        req.setRequestURI(chemin);
        req.setRemoteAddr(ip);
        var res = new MockHttpServletResponse();
        f.doFilter(req, res, new MockFilterChain());
        return res.getStatus();
    }

    @Test
    void auDelaDeLaLimiteLAdresseRecoitUn429PuisRepasseQuandLaFenetreSEcoule() throws Exception {
        var h = new Horloge();
        var f = new LimiteDebitFiltre(new DebitProperties(3, Duration.ofMinutes(1)), h);
        for (int i = 0; i < 3; i++) {
            assertThat(appeler(f, "10.0.0.1", "/auth/login")).isEqualTo(200);
        }
        assertThat(appeler(f, "10.0.0.1", "/auth/login")).isEqualTo(429);
        assertThat(appeler(f, "10.0.0.1", "/auth/refresh")).as("même compteur pour login et refresh").isEqualTo(429);
        assertThat(appeler(f, "10.0.0.2", "/auth/login")).as("autre adresse").isEqualTo(200);
        assertThat(appeler(f, "10.0.0.1", "/secteurs")).as("route non limitée").isEqualTo(200);
        h.t = h.t.plusSeconds(61);
        assertThat(appeler(f, "10.0.0.1", "/auth/login")).isEqualTo(200);
    }
}
