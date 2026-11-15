package ci.sysde.plantation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import ci.sysde.security.Acteur;
import ci.sysde.security.Perimetre;
import ci.sysde.security.PerimetreService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Les lots que l'appelant voit, avec leur secteur et leur titulaire (opérateur de l'attribution en cours). Une plantation
 * de la base externe est rattachée à SysDE par (code de secteur, numéro de lot) ; l'opérateur est celui que SysDE dit,
 * pas celui que la base externe déclare.
 */
@Component
class Territoire {

    record LotInfo(UUID id, int numero, UUID secteurId, String secteurCode, String secteurNom, UUID operateurId,
                   String operateur, String operateurNom) {
        String cle() {
            return cle(secteurCode, numero);
        }

        static String cle(String secteurCode, int numero) {
            return secteurCode + "/" + numero;
        }
    }

    private final JdbcClient jdbc;
    private final PerimetreService perimetres;

    Territoire(JdbcClient jdbc, PerimetreService perimetres) {
        this.jdbc = jdbc;
        this.perimetres = perimetres;
    }

    /** Lots visibles en lecture : tout pour AD et SU ; l'opérateur de l'appelant et, pour les autres profils, ses secteurs. */
    List<LotInfo> visibles(Acteur acteur) {
        Perimetre p = perimetres.de(acteur);
        return tous().stream()
                .filter(l -> p.estNational() || (l.operateurId() != null && p.voitSecteur(l.operateurId(), l.secteurId())))
                .toList();
    }

    Perimetre perimetre(Acteur acteur) {
        return perimetres.de(acteur);
    }

    private List<LotInfo> tous() {
        return jdbc.sql("""
                        select l.id, l.numero, s.id as secteur_id, s.code, s.nom as secteur_nom, c.operateur_id,
                               o.acronyme, o.denomination
                          from referentiel.lot l
                          join referentiel.secteur s on s.id = l.secteur_id and s.supprime_le is null
                          left join gestion.attribution_lot al on al.lot_id = l.id and al.periode @> current_date
                          left join gestion.contrat c on c.id = al.contrat_id
                          left join referentiel.operateur o on o.id = c.operateur_id
                         where l.supprime_le is null
                         order by s.code, l.numero
                        """)
                .query((rs, n) -> new LotInfo(rs.getObject("id", UUID.class), rs.getInt("numero"),
                        rs.getObject("secteur_id", UUID.class), rs.getString("code"), rs.getString("secteur_nom"),
                        rs.getObject("operateur_id", UUID.class), rs.getString("acronyme"), rs.getString("denomination")))
                .list();
    }

    /** Noms des sections par lot. */
    Map<UUID, List<String>> sectionsParLot() {
        Map<UUID, List<String>> par = new HashMap<>();
        jdbc.sql("select lot_id, nom from referentiel.section where supprime_le is null order by nom")
                .query((rs, n) -> {
                    par.computeIfAbsent(rs.getObject("lot_id", UUID.class), k -> new java.util.ArrayList<>()).add(rs.getString("nom"));
                    return 0;
                }).list();
        return par;
    }

    static Map<String, LotInfo> parCle(List<LotInfo> lots) {
        return lots.stream().collect(Collectors.toMap(LotInfo::cle, l -> l, (a, b) -> a));
    }
}
