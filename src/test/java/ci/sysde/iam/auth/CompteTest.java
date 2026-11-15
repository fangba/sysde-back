package ci.sysde.iam.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import ci.sysde.iam.compte.Compte;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class CompteTest {

    private static final Instant T0 = Instant.parse("2026-11-12T10:00:00Z");

    private static Compte compte() {
        return newCompte();
    }

    static Compte newCompte() {
        try {
            var ctor = Compte.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void lecompteSeVerrouilleAuCinquiemeEchecEtLeCompteurRepartAZero() {
        Compte c = compte();
        for (int i = 1; i < 5; i++) {
            assertThat(c.enregistrerEchec(5, Duration.ofMinutes(15), T0)).isFalse();
        }
        assertThat(c.enregistrerEchec(5, Duration.ofMinutes(15), T0)).isTrue();
        assertThat(c.echecsConnexion()).isZero();
        assertThat(c.estVerrouille(T0.plusSeconds(60))).isTrue();
        assertThat(c.estVerrouille(T0.plus(Duration.ofMinutes(16)))).isFalse();
    }

    @Test
    void unSuccesRemetLesCompteursAZero() {
        Compte c = compte();
        c.enregistrerEchec(5, Duration.ofMinutes(15), T0);
        c.enregistrerSucces(T0);
        assertThat(c.echecsConnexion()).isZero();
        assertThat(c.verrouilleJusquA()).isNull();
    }

    @Test
    void definirUnMotDePasseLeveLObligationDeLeChanger() {
        Compte c = compte();
        ReflectionTestUtils.setField(c, "doitChangerMotDePasse", true);
        c.definirMotDePasse("hash");
        assertThat(c.doitChangerMotDePasse()).isFalse();
        assertThat(c.aUnMotDePasse()).isTrue();
    }
}
