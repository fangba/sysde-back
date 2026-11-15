package ci.sysde.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class PaginationTest {

    @Test
    void valeursAbsentesPrennentLesDefauts() {
        PageRequete p = PageRequete.de(null, null);
        assertThat(p.page()).isZero();
        assertThat(p.size()).isEqualTo(PageRequete.TAILLE_DEFAUT);
    }

    @Test
    void tailleEstBorneeEtPageNegativeRamenee() {
        PageRequete p = PageRequete.de(-3, 10_000);
        assertThat(p.page()).isZero();
        assertThat(p.size()).isEqualTo(PageRequete.TAILLE_MAX);
        assertThat(PageRequete.de(0, 0).size()).isEqualTo(PageRequete.TAILLE_DEFAUT);
    }

    @Test
    void decalageNeDebordePas() {
        assertThat(new PageRequete(3, 25).decalage()).isEqualTo(75L);
        assertThat(new PageRequete(Integer.MAX_VALUE, 100).decalage()).isEqualTo(214_748_364_700L);
    }

    @Test
    void pageCalculeLeNombreDePages() {
        Page<String> page = Page.de(List.of("a", "b"), new PageRequete(0, 2), 5);
        assertThat(page.totalPages()).isEqualTo(3);
        assertThat(page.totalElements()).isEqualTo(5);
        assertThat(Page.de(List.<String>of(), new PageRequete(0, 25), 0).totalPages()).isZero();
        assertThat(page.mapper(String::toUpperCase).content()).containsExactly("A", "B");
    }

    @Test
    void triRefuseUnChampHorsListeBlanche() {
        Map<String, String> ok = Map.of("nom", "o.denomination");
        assertThatThrownBy(() -> TriSql.clause("mot_de_passe_hash,asc", ok, "o.denomination asc", "o.id"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Tri impossible");
        assertThatThrownBy(() -> TriSql.clause("nom;drop table x,asc", ok, "o.denomination asc", "o.id"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void triConstruitUneClauseSure() {
        Map<String, String> ok = Map.of("nom", "o.denomination");
        assertThat(TriSql.clause("nom,desc", ok, "o.denomination asc", "o.id")).isEqualTo("o.denomination desc, o.id");
        assertThat(TriSql.clause("nom", ok, "o.denomination asc", "o.id")).isEqualTo("o.denomination asc, o.id");
        assertThat(TriSql.clause(null, ok, "o.denomination asc", "o.id")).isEqualTo("o.denomination asc, o.id");
        assertThatThrownBy(() -> TriSql.clause("nom,sideways", ok, "o.denomination asc", "o.id"))
                .isInstanceOf(BusinessException.class);
    }
}
