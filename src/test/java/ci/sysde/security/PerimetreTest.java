package ci.sysde.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class PerimetreTest {

    private final UUID saph = UUID.randomUUID();
    private final UUID autre = UUID.randomUUID();
    private final UUID secteur = UUID.randomUUID();
    private final UUID autreSecteur = UUID.randomUUID();
    private final UUID lot = UUID.randomUUID();
    private final UUID autreLot = UUID.randomUUID();
    private final UUID section = UUID.randomUUID();
    private final UUID autreSection = UUID.randomUUID();

    @Test
    void lAdministrateurVoitTout() {
        Perimetre p = Perimetre.national(saph);
        assertThat(p.voitOperateur(autre)).isTrue();
        assertThat(p.voitSection(autre, autreSecteur, autreLot, autreSection)).isTrue();
    }

    @Test
    void leChefDeDepartementVoitSonOperateurSeulement() {
        Perimetre p = Perimetre.operateur(saph);
        assertThat(p.voitSecteur(saph, autreSecteur)).isTrue();
        assertThat(p.voitLot(saph, secteur, lot)).isTrue();
        assertThat(p.voitOperateur(autre)).isFalse();
        assertThat(p.voitSecteur(autre, secteur)).isFalse();
    }

    @Test
    void leChefSecteurVoitSonSecteurEntier() {
        Perimetre p = Perimetre.affectations(saph, Set.of(secteur), Set.of(secteur), Set.of(), Set.of());
        assertThat(p.voitSecteur(saph, secteur)).isTrue();
        assertThat(p.voitLot(saph, secteur, autreLot)).isTrue();
        assertThat(p.voitSection(saph, secteur, autreLot, autreSection)).isTrue();
        assertThat(p.voitSecteur(saph, autreSecteur)).isFalse();
        assertThat(p.voitLot(autre, secteur, lot)).isFalse();
    }

    @Test
    void leControleurDeSaigneeVoitSonLotMaisLeSecteurEnLectureLarge() {
        Perimetre p = Perimetre.affectations(saph, Set.of(secteur), Set.of(), Set.of(lot), Set.of());
        assertThat(p.voitLot(saph, secteur, lot)).isTrue();
        assertThat(p.voitLot(saph, secteur, autreLot)).isFalse();
        assertThat(p.voitSection(saph, secteur, lot, autreSection)).isTrue();
        assertThat(p.voitSecteur(saph, secteur)).isTrue();
    }

    @Test
    void leMoniteurNeVoitQueSaSection() {
        Perimetre p = Perimetre.affectations(saph, Set.of(secteur), Set.of(), Set.of(), Set.of(section));
        assertThat(p.voitSection(saph, secteur, lot, section)).isTrue();
        assertThat(p.voitSection(saph, secteur, lot, autreSection)).isFalse();
        assertThat(p.voitLot(saph, secteur, lot)).isFalse();
        assertThat(p.voitSecteur(saph, secteur)).isTrue();
    }

    @Test
    void sansAffectationRienNEstVisible() {
        Perimetre p = Perimetre.affectations(saph, Set.of(), Set.of(), Set.of(), Set.of());
        assertThat(p.voitSecteur(saph, secteur)).isFalse();
        assertThat(p.voitLot(saph, secteur, lot)).isFalse();
        assertThat(p.voitSection(saph, secteur, lot, section)).isFalse();
    }
}
