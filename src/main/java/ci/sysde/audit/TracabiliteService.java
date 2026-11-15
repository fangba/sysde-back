package ci.sysde.audit;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import ci.sysde.common.BusinessException;
import ci.sysde.common.Page;
import ci.sysde.common.PageRequete;
import ci.sysde.iam.compte.PorteeComptes;
import ci.sysde.security.Acteur;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Consultation et export de la piste d'audit (TRA-06 à TRA-09). Périmètre : les événements dont l'<em>auteur</em> est un compte
 * que l'appelant peut voir (ses comptes de rang inférieur dans son périmètre, et lui-même) ; l'AD et le SU voient aussi les
 * traitements système. La consultation directe est limitée à la durée de conservation (paramètre {@code audit.conservation_mois}).
 */
@Service
class TracabiliteService {

    static final int EXPORT_MAX = 50_000;
    private static final int JOURS_PAR_DEFAUT = 30;

    /** {@code before}/{@code after} : valeurs modifiées (jamais de mot de passe ni de contour : voir V5). */
    record Evenement(UUID id, OffsetDateTime at, UUID accountId, String matricule, UUID userId, String profile, String ip,
                     String reason, String schema, String table, String operation, String objectId, Object before, Object after) {
    }

    record Filtre(UUID accountId, String table, String objectId, Instant from, Instant to) {
    }

    private static final String COLONNES = """
            select e.id, e.survenu_le, e.acteur_compte_id, c.matricule, e.acteur_utilisateur_id, e.acteur_profil, e.adresse_ip, e.motif,
                   e.schema_nom, e.table_nom, e.operation, e.objet_id, e.anciennes::text as anciennes, e.nouvelles::text as nouvelles""";
    private static final String DEPUIS = """
              from audit.evenement e
              left join iam.compte c on c.id = e.acteur_compte_id
              left join iam.profil p on p.code = c.profil
              left join referentiel.operateur o on o.id = c.operateur_id
             where""";

    private final JdbcClient jdbc;
    private final PorteeComptes portees;
    private final ObjectMapper json;

    TracabiliteService(JdbcClient jdbc, PorteeComptes portees, ObjectMapper json) {
        this.jdbc = jdbc;
        this.portees = portees;
        this.json = json;
    }

    @Transactional(readOnly = true)
    Page<Evenement> lister(Acteur acteur, Filtre f, PageRequete page) {
        Requete r = requete(acteur, f);
        long total = jdbc.sql("select count(*)" + DEPUIS + r.where).params(r.params).query(Long.class).single();
        List<Evenement> contenu = jdbc.sql(COLONNES + DEPUIS + r.where + " order by e.survenu_le desc, e.id desc limit :limite offset :decalage")
                .params(r.params).param("limite", page.size()).param("decalage", page.decalage())
                .query(this::ligne).list();
        return Page.de(contenu, page, total);
    }

    /** Fichier CSV pour un audit externe. L'export est tracé (qui, quels filtres, combien de lignes) avant d'être rendu. */
    @Transactional
    String exporter(Acteur acteur, Filtre f) {
        Requete r = requete(acteur, f);
        List<Evenement> evenements = jdbc.sql(COLONNES + DEPUIS + r.where + " order by e.survenu_le desc, e.id desc limit :max")
                .params(r.params).param("max", EXPORT_MAX + 1).query(this::ligne).list();
        if (evenements.size() > EXPORT_MAX) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "REQUETE_INVALIDE",
                    "Plus de " + EXPORT_MAX + " événements : réduisez la période ou ajoutez un filtre.");
        }
        Map<String, Object> filtres = new HashMap<>();
        filtres.put("accountId", f.accountId() == null ? null : f.accountId().toString());
        filtres.put("table", f.table());
        filtres.put("objectId", f.objectId());
        filtres.put("from", r.params.get("from").toString());
        filtres.put("to", r.params.get("to").toString());
        jdbc.sql("select audit.tracer_export(cast(:f as jsonb), :n)").param("f", json.writeValueAsString(filtres))
                .param("n", evenements.size()).query().singleValue();

        StringBuilder sb = new StringBuilder("﻿date;compte;matricule;profil;utilisateur;adresse_ip;motif;schema;table;operation;objet;avant;apres\n");
        for (Evenement e : evenements) {
            sb.append(String.join(";", csv(e.at().toString()), csv(str(e.accountId())), csv(e.matricule()), csv(e.profile()),
                    csv(str(e.userId())), csv(e.ip()), csv(e.reason()), csv(e.schema()), csv(e.table()), csv(e.operation()),
                    csv(e.objectId()), csv(e.before() == null ? null : json.writeValueAsString(e.before())),
                    csv(e.after() == null ? null : json.writeValueAsString(e.after())))).append('\n');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ requête

    private record Requete(String where, Map<String, Object> params) {
    }

    private Requete requete(Acteur acteur, Filtre f) {
        PorteeComptes.Condition portee = portees.de(acteur);
        Map<String, Object> params = new HashMap<>(portee.params());
        params.put("self", acteur.compteId());
        boolean systeme = acteur.estProfil(Acteur.ADMINISTRATEUR, Acteur.SUPERVISEUR);
        StringBuilder where = new StringBuilder("(").append(portee.sql()).append(" or e.acteur_compte_id = :self")
                .append(systeme ? " or e.acteur_compte_id is null" : "").append(")");

        // Conservation : au-delà, les mois anciens sont archivés (hors consultation directe).
        Instant plancher = jdbc.sql("select now() - make_interval(months => coalesce((select valeur::int from util.parametre"
                        + " where cle = 'audit.conservation_mois' and supprime_le is null), 36))")
                .query(OffsetDateTime.class).single().toInstant();
        Instant to = f.to() != null ? f.to() : Instant.now().plusSeconds(1);
        Instant from = f.from() != null ? f.from() : to.minus(java.time.Duration.ofDays(JOURS_PAR_DEFAUT));
        if (from.isAfter(to)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "REQUETE_INVALIDE", "La date de début est postérieure à la date de fin.");
        }
        if (from.isBefore(plancher)) {
            from = plancher;
        }
        where.append(" and e.survenu_le >= :from and e.survenu_le < :to");
        params.put("from", from.atOffset(ZoneOffset.UTC));
        params.put("to", to.atOffset(ZoneOffset.UTC));
        if (f.accountId() != null) {
            where.append(" and e.acteur_compte_id = :compte");
            params.put("compte", f.accountId());
        }
        if (f.table() != null && !f.table().isBlank()) {
            where.append(" and e.table_nom = :table");
            params.put("table", f.table().strip());
        }
        if (f.objectId() != null && !f.objectId().isBlank()) {
            where.append(" and e.objet_id = :objet");
            params.put("objet", f.objectId().strip());
        }
        return new Requete(where.toString(), params);
    }

    private Evenement ligne(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        String avant = rs.getString("anciennes");
        String apres = rs.getString("nouvelles");
        return new Evenement(rs.getObject("id", UUID.class), rs.getObject("survenu_le", OffsetDateTime.class),
                rs.getObject("acteur_compte_id", UUID.class), rs.getString("matricule"), rs.getObject("acteur_utilisateur_id", UUID.class),
                rs.getString("acteur_profil"), rs.getString("adresse_ip"), rs.getString("motif"), rs.getString("schema_nom"),
                rs.getString("table_nom"), rs.getString("operation"), rs.getString("objet_id"),
                avant == null ? null : json.readValue(avant, Object.class), apres == null ? null : json.readValue(apres, Object.class));
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static String csv(String valeur) {
        if (valeur == null) {
            return "";
        }
        // Neutralise l'injection de formule dans un tableur (=, +, -, @ en tête de cellule).
        String v = !valeur.isEmpty() && "=+-@".indexOf(valeur.charAt(0)) >= 0 ? "'" + valeur : valeur;
        return v.contains(";") || v.contains("\"") || v.contains("\n") ? "\"" + v.replace("\"", "\"\"") + "\"" : v;
    }

    static Instant debut(LocalDate d) {
        return d == null ? null : d.atStartOfDay().toInstant(ZoneOffset.UTC);
    }
}
