package ci.sysde.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GlobalExceptionHandlerTest {

    @Test
    void unMessageDeTriggerEstMetierUneContrainteNonLest() {
        assertThat(GlobalExceptionHandler.ressembleAUneContrainte("Le profil CS est réservé aux opérateurs techniques."))
                .isFalse();
        assertThat(GlobalExceptionHandler.ressembleAUneContrainte(
                "ERROR: duplicate key value violates unique constraint \"ux_operateur_acronyme\"")).isTrue();
        assertThat(GlobalExceptionHandler.ressembleAUneContrainte(
                "new row for relation \"compte\" violates check constraint \"x\"")).isTrue();
    }

    @Test
    void seulLaPremiereLigneEstConservee() {
        String brut = "ERROR: Un motif est obligatoire pour retirer un lot.\n  Où : fonction PL/pgSQL gestion.verifier()";
        assertThat(GlobalExceptionHandler.premiereLigne(brut)).isEqualTo("Un motif est obligatoire pour retirer un lot.");
        assertThat(GlobalExceptionHandler.premiereLigne("Simple message")).isEqualTo("Simple message");
    }
}
