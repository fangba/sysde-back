package ci.sysde.iam.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PasswordPolicyTest {

    @Test
    void unMotDePasseRobusteEstAccepte() {
        assertThat(PasswordPolicy.violations("Hevea2026ok")).isEmpty();
    }

    @Test
    void tropCourtSansMajusculeNiChiffreCumuleLesViolations() {
        assertThat(PasswordPolicy.violations("abc")).hasSize(3); // longueur, majuscule, chiffre
    }

    @Test
    void nullEstRefuse() {
        assertThat(PasswordPolicy.violations(null)).isNotEmpty();
    }
}
