package ci.sysde.iam.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PasswordPolicyTest {

    @Test
    void huitCaracteresSuffisentQuelsQuIlsSoient() {
        assertThat(PasswordPolicy.violations("abcdefgh")).isEmpty();      // minuscules seulement
        assertThat(PasswordPolicy.violations("ABCDEFGH")).isEmpty();      // majuscules seulement
        assertThat(PasswordPolicy.violations("12345678")).isEmpty();      // chiffres seulement
        assertThat(PasswordPolicy.violations("!?-_.,;:")).isEmpty();      // caractères spéciaux seulement
        assertThat(PasswordPolicy.violations("mot de passe")).isEmpty();  // espaces admis
        assertThat(PasswordPolicy.violations("Hevea2026ok")).isEmpty();
    }

    @Test
    void moinsDeHuitCaracteresEstRefuse() {
        assertThat(PasswordPolicy.violations("Abc1!xy")).containsExactly("au moins 8 caractères");   // 7 caractères, pourtant varié
        assertThat(PasswordPolicy.violations("abc")).containsExactly("au moins 8 caractères");
        assertThat(PasswordPolicy.violations("")).isNotEmpty();
    }

    @Test
    void nullEstRefuse() {
        assertThat(PasswordPolicy.violations(null)).isNotEmpty();
    }
}
