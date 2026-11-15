package ci.sysde.iam.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

class RefreshTokenServiceTest {

    @Test
    void empreinteFait64CaracteresHexaEtEstStable() {
        String e = RefreshTokenService.empreinte("abc");
        assertThat(e).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(e).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(RefreshTokenService.empreinte("abc")).isEqualTo(e);
        assertThat(RefreshTokenService.empreinte("abd")).isNotEqualTo(e);
    }

    @Test
    void lesValeursSontLonguesEtUniques() {
        Set<String> vues = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            String v = RefreshTokenService.nouvelleValeur();
            assertThat(v).hasSize(43).matches("[A-Za-z0-9_-]+");   // 32 octets en Base64 URL sans remplissage
            assertThat(vues.add(v)).isTrue();
        }
    }

    @Test
    void tronquerRespecteLaLimite() {
        assertThat(RefreshTokenService.tronquer(null, 5)).isNull();
        assertThat(RefreshTokenService.tronquer("abc", 5)).isEqualTo("abc");
        assertThat(RefreshTokenService.tronquer("abcdefgh", 5)).isEqualTo("abcde");
    }
}
