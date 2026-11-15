package ci.sysde.realisation;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import ci.sysde.common.BusinessException;
import ci.sysde.common.Page;
import ci.sysde.common.PageRequete;
import ci.sysde.common.TriSql;
import ci.sysde.realisation.RealisationDtos.Participant;
import ci.sysde.realisation.RealisationDtos.ParticipantInput;
import ci.sysde.security.Acteur;
import ci.sysde.security.PerimetreService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Participants aux activités (saigneurs, apprentis, planteurs), tenus par opérateur. L'identifiant peut venir de l'appareil
 * (UUID v7) : un renvoi après coupure ne crée pas de doublon.
 */
@Service
class ParticipantService {

    private static final Map<String, String> TRIS = Map.of("lastName", "lower(p.nom)", "firstName", "lower(p.prenoms)", "village", "lower(p.village)");

    private final JdbcClient jdbc;
    private final PerimetreService perimetres;

    ParticipantService(JdbcClient jdbc, PerimetreService perimetres) {
        this.jdbc = jdbc;
        this.perimetres = perimetres;
    }

    @Transactional(readOnly = true)
    Page<Participant> lister(Acteur acteur, String q, PageRequete page, String sort) {
        boolean national = perimetres.de(acteur).estNational();
        String filtre = " where p.supprime_le is null" + (national ? "" : " and p.operateur_id = :op");
        java.util.Map<String, Object> params = new java.util.HashMap<>();
        if (!national) {
            params.put("op", acteur.operateurId());
        }
        if (q != null && !q.isBlank()) {
            filtre += " and (lower(p.nom || ' ' || p.prenoms) like :q or lower(coalesce(p.village, '')) like :q)";
            params.put("q", "%" + q.strip().toLowerCase(java.util.Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        String ordre = TriSql.clause(sort, TRIS, "lower(p.nom) asc, lower(p.prenoms) asc", "p.id asc");
        long total = jdbc.sql("select count(*) from planning.participant p" + filtre).params(params).query(Long.class).single();
        List<Participant> contenu = jdbc.sql("select p.id, p.nom, p.prenoms, p.telephone, p.village, p.reference_externe from planning.participant p"
                        + filtre + " order by " + ordre + " limit :limite offset :decalage")
                .params(params).param("limite", page.size()).param("decalage", page.decalage())
                .query(ParticipantService::ligne).list();
        return Page.de(contenu, page, total);
    }

    /** Création, ou renvoi d'une création déjà reçue (même identifiant, même opérateur) : la ligne existante est renvoyée telle quelle. */
    @Transactional
    Participant creer(Acteur acteur, ParticipantInput in) {
        UUID id = in.id() != null ? in.id() : UUID.randomUUID();
        if (in.id() != null) {
            UUID operateur = jdbc.sql("select operateur_id from planning.participant where id = :id").param("id", id).query(UUID.class).optional().orElse(null);
            if (operateur != null) {
                if (!operateur.equals(acteur.operateurId())) {
                    throw new BusinessException(HttpStatus.CONFLICT, "CONFLIT_DONNEES", "Cet identifiant est déjà utilisé.");
                }
                return lire(id);
            }
        }
        jdbc.sql("""
                        insert into planning.participant (id, operateur_id, nom, prenoms, telephone, village, reference_externe)
                        values (:id, :op, :nom, :prenoms, :tel, :village, :ref)
                        """)
                .param("id", id).param("op", acteur.operateurId()).param("nom", in.lastName().strip()).param("prenoms", in.firstName().strip())
                .param("tel", vide(in.phone())).param("village", vide(in.village())).param("ref", vide(in.externalRef())).update();
        return lire(id);
    }

    @Transactional
    Participant modifier(Acteur acteur, UUID id, ParticipantInput in) {
        int n = jdbc.sql("""
                        update planning.participant set nom = :nom, prenoms = :prenoms, telephone = :tel, village = :village, reference_externe = :ref,
                               version = version + 1
                         where id = :id and operateur_id = :op and supprime_le is null
                        """)
                .param("id", id).param("op", acteur.operateurId()).param("nom", in.lastName().strip()).param("prenoms", in.firstName().strip())
                .param("tel", vide(in.phone())).param("village", vide(in.village())).param("ref", vide(in.externalRef())).update();
        if (n == 0) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "INTROUVABLE", "Participant introuvable.");
        }
        return lire(id);
    }

    private Participant lire(UUID id) {
        return jdbc.sql("select p.id, p.nom, p.prenoms, p.telephone, p.village, p.reference_externe from planning.participant p where p.id = :id and p.supprime_le is null")
                .param("id", id).query(ParticipantService::ligne).single();
    }

    private static Participant ligne(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Participant(rs.getObject("id", UUID.class), rs.getString("nom"), rs.getString("prenoms"), rs.getString("telephone"),
                rs.getString("village"), rs.getString("reference_externe"));
    }

    private static String vide(String t) {
        return t == null || t.isBlank() ? null : t.strip();
    }
}
