package ci.sysde.realisation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import ci.sysde.common.BusinessException;
import ci.sysde.common.Page;
import ci.sysde.common.PageRequete;
import ci.sysde.realisation.RealisationDtos.Appreciation;
import ci.sysde.realisation.RealisationDtos.AppreciationInput;
import ci.sysde.realisation.RealisationDtos.Attendance;
import ci.sysde.realisation.RealisationDtos.AttendanceInput;
import ci.sysde.realisation.RealisationDtos.Criterion;
import ci.sysde.realisation.RealisationDtos.Evaluation;
import ci.sysde.realisation.RealisationDtos.EvaluationInput;
import ci.sysde.realisation.RealisationDtos.Realisation;
import ci.sysde.realisation.RealisationDtos.RealisationInput;
import ci.sysde.realisation.RealisationDtos.RealisationSummary;
import ci.sysde.security.Acteur;
import ci.sysde.security.Perimetre;
import ci.sysde.security.PerimetreService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Réalisation d'une ligne de planning, saisie sur le terrain (souvent hors ligne). L'envoi est un <b>état complet</b> à
 * identifiant fixé par l'appareil : le rejeter à l'identique ne change rien (empreinte du contenu), une modification faite
 * entre-temps est détectée par la version attendue (409). La date réelle de l'action est conservée ({@code startedAt}), la
 * réception est datée à part. Les photos ne sont pas encore prises en charge.
 */
@Service
public class RealisationService {

    private record LignePlan(UUID id, UUID planId, UUID compteId, UUID activiteId, LocalDate debut, LocalDate fin) {
    }

    private record Existante(UUID id, UUID ligneId, UUID compteId, String statut, long version, String empreinte) {
    }

    private record Activite(boolean avecPresence, boolean avecEvaluation, boolean avecRapport, Integer participantsMax) {
    }

    private static final String COLONNES = """
            select r.id, r.ligne_id, l.plan_id, r.compte_id, l.activite_id, r.statut, r.debut_le, r.fin_le, r.latitude, r.longitude,
                   r.precision_m, r.observations, r.recu_le, r.version
              from planning.realisation r
              join planning.ligne_planning l on l.id = r.ligne_id
              join iam.compte c on c.id = r.compte_id
              join referentiel.operateur o on o.id = c.operateur_id
             where r.supprime_le is null""";

    private final JdbcClient jdbc;
    private final PerimetreService perimetres;
    private final ObjectMapper json;

    RealisationService(JdbcClient jdbc, PerimetreService perimetres, ObjectMapper json) {
        this.jdbc = jdbc;
        this.perimetres = perimetres;
        this.json = json;
    }

    // ------------------------------------------------------------------ référentiel

    @Transactional(readOnly = true)
    public List<Criterion> criteres() {
        return jdbc.sql("select code, libelle, ordre from planning.critere_evaluation order by ordre")
                .query((rs, n) -> new Criterion(rs.getString("code"), rs.getString("libelle"), rs.getInt("ordre"))).list();
    }

    // ------------------------------------------------------------------ envoi

    @Transactional
    Realisation enregistrer(Acteur acteur, UUID id, RealisationInput in) {
        LignePlan ligne = jdbc.sql("""
                        select l.id, l.plan_id, p.compte_id, l.activite_id, l.date_debut, l.date_fin
                          from planning.ligne_planning l join planning.plan_annuel p on p.id = l.plan_id
                         where l.id = :id and l.supprime_le is null and p.supprime_le is null
                        """)
                .param("id", in.lineId())
                .query((rs, n) -> new LignePlan(rs.getObject("id", UUID.class), rs.getObject("plan_id", UUID.class),
                        rs.getObject("compte_id", UUID.class), rs.getObject("activite_id", UUID.class),
                        rs.getObject("date_debut", LocalDate.class), rs.getObject("date_fin", LocalDate.class)))
                .optional().orElseThrow(() -> invalide("Ligne de planning inconnue."));
        if (!ligne.compteId().equals(acteur.compteId())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ACCES_REFUSE", "Cette ligne n'est pas dans votre planning.");
        }

        String empreinte = empreinte(in);
        Existante existante = jdbc.sql("select id, ligne_id, compte_id, statut, version, empreinte from planning.realisation where id = :id and supprime_le is null for update")
                .param("id", id)
                .query((rs, n) -> new Existante(rs.getObject("id", UUID.class), rs.getObject("ligne_id", UUID.class),
                        rs.getObject("compte_id", UUID.class), rs.getString("statut"), rs.getLong("version"),
                        rs.getString("empreinte") == null ? null : rs.getString("empreinte").strip()))
                .optional().orElse(null);
        if (existante != null) {
            if (!existante.compteId().equals(acteur.compteId())) {
                throw new BusinessException(HttpStatus.FORBIDDEN, "ACCES_REFUSE", "Cette réalisation est celle d'un autre compte.");
            }
            if (!existante.ligneId().equals(in.lineId())) {
                throw conflit("Cette réalisation concerne une autre ligne de planning.");
            }
            if (empreinte.equals(existante.empreinte())) {
                return lire(id);                                   // rejeu d'un envoi déjà reçu : rien à refaire
            }
            if (in.expectedVersion() != null && in.expectedVersion() != existante.version()) {
                throw new BusinessException(HttpStatus.CONFLICT, "CONFLIT_DE_VERSION",
                        "Cette réalisation a été modifiée depuis : rechargez-la avant de renvoyer.");
            }
            if ("TERMINEE".equals(existante.statut()) && "EN_COURS".equals(in.status())) {
                throw conflit("Une réalisation terminée ne repasse pas en cours.");
            }
        } else if (jdbc.sql("select exists (select 1 from planning.realisation where ligne_id = :l and supprime_le is null)")
                .param("l", in.lineId()).query(Boolean.class).single()) {
            throw conflit("Cette ligne de planning a déjà une réalisation.");
        }
        verifier(acteur, ligne, in);

        if (existante == null) {
            jdbc.sql("""
                            insert into planning.realisation (id, ligne_id, compte_id, statut, debut_le, fin_le, latitude, longitude, precision_m, observations, empreinte)
                            values (:id, :ligne, :compte, :statut, :debut, :fin, :lat, :lon, :precision, :obs, :empreinte)
                            """)
                    .param("id", id).param("ligne", in.lineId()).param("compte", acteur.compteId()).param("statut", in.status())
                    .param("debut", in.startedAt()).param("fin", in.endedAt()).param("lat", in.latitude()).param("lon", in.longitude())
                    .param("precision", in.accuracyM()).param("obs", in.observations() == null ? "" : in.observations())
                    .param("empreinte", empreinte).update();
        } else {
            jdbc.sql("""
                            update planning.realisation
                               set statut = :statut, debut_le = :debut, fin_le = :fin, latitude = :lat, longitude = :lon, precision_m = :precision,
                                   observations = :obs, empreinte = :empreinte, version = version + 1, recu_le = now()
                             where id = :id
                            """)
                    .param("id", id).param("statut", in.status()).param("debut", in.startedAt()).param("fin", in.endedAt())
                    .param("lat", in.latitude()).param("lon", in.longitude()).param("precision", in.accuracyM())
                    .param("obs", in.observations() == null ? "" : in.observations()).param("empreinte", empreinte).update();
            jdbc.sql("delete from planning.presence where realisation_id = :id").param("id", id).update();
            jdbc.sql("delete from planning.evaluation where realisation_id = :id").param("id", id).update();
            jdbc.sql("delete from planning.appreciation where realisation_id = :id").param("id", id).update();
        }
        for (AttendanceInput p : liste(in.attendance())) {
            jdbc.sql("insert into planning.presence (realisation_id, participant_id, jour, present) values (:r, :p, :j, :present)")
                    .param("r", id).param("p", p.participantId()).param("j", p.day()).param("present", p.present()).update();
        }
        for (EvaluationInput e : liste(in.evaluations())) {
            jdbc.sql("insert into planning.evaluation (realisation_id, participant_id, critere_code, niveau) values (:r, :p, :c, :n)")
                    .param("r", id).param("p", e.participantId()).param("c", e.criterion()).param("n", e.level()).update();
        }
        for (AppreciationInput a : liste(in.appreciations())) {
            jdbc.sql("insert into planning.appreciation (realisation_id, participant_id, verdict, commentaire) values (:r, :p, :v, :c)")
                    .param("r", id).param("p", a.participantId()).param("v", a.verdict()).param("c", a.comment() == null ? "" : a.comment()).update();
        }
        // Le rapport n'a pas de suppression (droits minimaux) : un rapport retiré est vidé.
        jdbc.sql("""
                        insert into planning.rapport (realisation_id, contenu, redige_par) values (:r, :c, :par)
                        on conflict (realisation_id) do update set contenu = excluded.contenu, redige_le = now(), redige_par = excluded.redige_par
                        """)
                .param("r", id).param("c", in.report() == null ? "" : in.report()).param("par", acteur.compteId()).update();
        return lire(id);
    }

    // ------------------------------------------------------------------ lecture

    @Transactional(readOnly = true)
    Realisation lire(Acteur acteur, UUID id) {
        Portee p = portee(acteur);
        Realisation r = jdbc.sql(COLONNES + " and r.id = :id and (" + p.sql + ")").params(p.params).param("id", id)
                .query(RealisationService::ligne).optional()
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "INTROUVABLE", "Réalisation introuvable."));
        return avecEnfants(r);
    }

    @Transactional(readOnly = true)
    Page<RealisationSummary> lister(Acteur acteur, UUID compteId, UUID activiteId, String statut, OffsetDateTime depuis,
                                    OffsetDateTime jusqua, PageRequete page) {
        Portee p = portee(acteur);
        StringBuilder where = new StringBuilder(" and (").append(p.sql).append(")");
        Map<String, Object> params = new HashMap<>(p.params);
        if (compteId != null) {
            where.append(" and r.compte_id = :compte");
            params.put("compte", compteId);
        }
        if (activiteId != null) {
            where.append(" and l.activite_id = :activite");
            params.put("activite", activiteId);
        }
        if (statut != null && !statut.isBlank()) {
            where.append(" and r.statut = :statut");
            params.put("statut", statut);
        }
        if (depuis != null) {
            where.append(" and r.debut_le >= :depuis");
            params.put("depuis", depuis);
        }
        if (jusqua != null) {
            where.append(" and r.debut_le < :jusqua");
            params.put("jusqua", jusqua);
        }
        long total = jdbc.sql("select count(*) " + COLONNES.substring(COLONNES.indexOf("from planning.realisation")) + where)
                .params(params).query(Long.class).single();
        List<RealisationSummary> contenu = jdbc.sql(COLONNES + where + " order by r.debut_le desc, r.id desc limit :limite offset :decalage")
                .params(params).param("limite", page.size()).param("decalage", page.decalage())
                .query(RealisationService::ligne).list().stream()
                .map(r -> new RealisationSummary(r.id(), r.lineId(), r.accountId(), r.activityId(), r.status(), r.startedAt(), r.endedAt(),
                        r.receivedAt(), r.version()))
                .toList();
        return Page.de(contenu, page, total);
    }

    // ------------------------------------------------------------------ contrôles

    private void verifier(Acteur acteur, LignePlan ligne, RealisationInput in) {
        Activite activite = jdbc.sql("select avec_presence, avec_evaluation, avec_rapport, participants_max from planning.activite where id = :id")
                .param("id", ligne.activiteId())
                .query((rs, n) -> new Activite(rs.getBoolean("avec_presence"), rs.getBoolean("avec_evaluation"), rs.getBoolean("avec_rapport"),
                        rs.getObject("participants_max", Integer.class)))
                .single();
        if ((in.latitude() == null) != (in.longitude() == null)) {
            throw invalide("La latitude et la longitude vont ensemble.");
        }
        if ("TERMINEE".equals(in.status()) && in.endedAt() == null) {
            throw invalide("L'heure de fin est obligatoire pour une réalisation terminée.");
        }
        if (in.endedAt() != null && in.endedAt().isBefore(in.startedAt())) {
            throw invalide("La fin précède le début.");
        }
        List<AttendanceInput> presences = liste(in.attendance());
        List<EvaluationInput> evaluations = liste(in.evaluations());
        List<AppreciationInput> appreciations = liste(in.appreciations());
        if (!presences.isEmpty() && !activite.avecPresence()) {
            throw invalide("Cette activité n'a pas de liste de présence.");
        }
        if ((!evaluations.isEmpty() || !appreciations.isEmpty()) && !activite.avecEvaluation()) {
            throw invalide("Cette activité n'est pas évaluée.");
        }
        if (in.report() != null && !in.report().isBlank() && !activite.avecRapport()) {
            throw invalide("Cette activité n'a pas de rapport.");
        }

        Set<UUID> participants = new HashSet<>();
        Set<String> vus = new HashSet<>();
        for (AttendanceInput p : presences) {
            if (p.day().isBefore(ligne.debut()) || p.day().isAfter(ligne.fin())) {
                throw invalide("Présence du " + p.day() + " hors de la période de l'activité (" + ligne.debut() + " au " + ligne.fin() + ").");
            }
            if (!vus.add(p.participantId() + "|" + p.day())) {
                throw invalide("Présence en double pour un même participant et un même jour.");
            }
            participants.add(p.participantId());
        }
        if (activite.participantsMax() != null && participants.size() > activite.participantsMax()) {
            throw invalide("Cette activité accueille " + activite.participantsMax() + " participants au plus.");
        }
        Set<String> notes = new HashSet<>();
        for (EvaluationInput e : evaluations) {
            if (!notes.add(e.participantId() + "|" + e.criterion())) {
                throw invalide("Évaluation en double pour un même participant et un même critère.");
            }
            participants.add(e.participantId());
        }
        Set<UUID> apprecies = new HashSet<>();
        for (AppreciationInput a : appreciations) {
            if (!apprecies.add(a.participantId())) {
                throw invalide("Appréciation en double pour un même participant.");
            }
            participants.add(a.participantId());
        }
        if (!participants.isEmpty()) {
            Set<UUID> connus = new HashSet<>(jdbc.sql("select id from planning.participant where id in (:ids) and operateur_id = :op and supprime_le is null")
                    .param("ids", participants).param("op", acteur.operateurId()).query(UUID.class).list());
            participants.removeAll(connus);
            if (!participants.isEmpty()) {
                throw invalide("Participant inconnu de votre opérateur : " + participants.iterator().next() + ".");
            }
        }
        if (!evaluations.isEmpty()) {
            Set<String> criteres = new HashSet<>(jdbc.sql("select code from planning.critere_evaluation").query(String.class).list());
            for (EvaluationInput e : evaluations) {
                if (!criteres.contains(e.criterion())) {
                    throw invalide("Critère d'évaluation inconnu : « " + e.criterion() + " ».");
                }
            }
        }
    }

    // ------------------------------------------------------------------ périmètre de lecture

    private record Portee(String sql, Map<String, Object> params) {
    }

    /**
     * Qui voit quoi (CONSULTER_ACTIVITE) : chacun ses réalisations ; le chef secteur et la secrétaire, celles de leur secteur ;
     * le chef de département, celles de son opérateur ; l'administrateur et le superviseur, tout opérateur technique.
     * Conditions sur les alias {@code c} (compte) et {@code o} (opérateur du compte).
     */
    private Portee portee(Acteur acteur) {
        Map<String, Object> params = new HashMap<>();
        params.put("self", acteur.compteId());
        params.put("pop", acteur.operateurId());
        String sql = "c.id = :self";
        if (acteur.estProfil(Acteur.ADMINISTRATEUR, Acteur.SUPERVISEUR)) {
            sql += " or c.operateur_id = :pop or o.type = 'TECHNIQUE'";
        } else if (acteur.estProfil(Acteur.CHEF_DEPARTEMENT)) {
            sql += " or c.operateur_id = :pop";
        } else if (acteur.estProfil(Acteur.CHEF_SECTEUR, "SE")) {
            Perimetre p = perimetres.de(acteur);
            if (!p.secteursEntiers().isEmpty()) {
                sql += " or (c.operateur_id = :pop and exists (select 1 from iam.affectation f where f.compte_id = c.id and f.fin is null"
                        + " and f.supprime_le is null and f.secteur_id in (:secteurs)))";
                params.put("secteurs", p.secteursEntiers());
            }
        }
        return new Portee(sql, params);
    }

    // ------------------------------------------------------------------ utilitaires

    private Realisation lire(UUID id) {
        return avecEnfants(jdbc.sql(COLONNES + " and r.id = :id").param("id", id).query(RealisationService::ligne).single());
    }

    private Realisation avecEnfants(Realisation r) {
        List<Attendance> presences = jdbc.sql("select participant_id, jour, present from planning.presence where realisation_id = :id order by jour, participant_id")
                .param("id", r.id()).query((rs, n) -> new Attendance(rs.getObject("participant_id", UUID.class), rs.getObject("jour", LocalDate.class), rs.getBoolean("present"))).list();
        List<Evaluation> evaluations = jdbc.sql("select participant_id, critere_code, niveau from planning.evaluation where realisation_id = :id order by participant_id, critere_code")
                .param("id", r.id()).query((rs, n) -> new Evaluation(rs.getObject("participant_id", UUID.class), rs.getString("critere_code"), rs.getString("niveau"))).list();
        List<Appreciation> appreciations = jdbc.sql("select participant_id, verdict, commentaire from planning.appreciation where realisation_id = :id order by participant_id")
                .param("id", r.id()).query((rs, n) -> new Appreciation(rs.getObject("participant_id", UUID.class), rs.getString("verdict"),
                        rs.getString("commentaire").isEmpty() ? null : rs.getString("commentaire"))).list();
        String rapport = jdbc.sql("select contenu from planning.rapport where realisation_id = :id").param("id", r.id()).query(String.class).optional()
                .filter(c -> !c.isBlank()).orElse(null);
        return new Realisation(r.id(), r.lineId(), r.planId(), r.accountId(), r.activityId(), r.status(), r.startedAt(), r.endedAt(),
                r.latitude(), r.longitude(), r.accuracyM(), r.observations() == null || r.observations().isEmpty() ? null : r.observations(),
                r.receivedAt(), r.version(), presences, evaluations, appreciations, rapport);
    }

    private static Realisation ligne(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Realisation(rs.getObject("id", UUID.class), rs.getObject("ligne_id", UUID.class), rs.getObject("plan_id", UUID.class),
                rs.getObject("compte_id", UUID.class), rs.getObject("activite_id", UUID.class), rs.getString("statut"),
                rs.getObject("debut_le", OffsetDateTime.class), rs.getObject("fin_le", OffsetDateTime.class),
                rs.getBigDecimal("latitude"), rs.getBigDecimal("longitude"), rs.getBigDecimal("precision_m"), rs.getString("observations"),
                rs.getObject("recu_le", OffsetDateTime.class), rs.getLong("version"), List.of(), List.of(), List.of(), null);
    }

    /** Empreinte du contenu, sans la version attendue : deux envois identiques ont la même. */
    private String empreinte(RealisationInput in) {
        RealisationInput sansVersion = new RealisationInput(in.lineId(), in.status(), in.startedAt(), in.endedAt(), in.latitude(), in.longitude(),
                in.accuracyM(), in.observations(), liste(in.attendance()), liste(in.evaluations()), liste(in.appreciations()), in.report(), null);
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(json.writeValueAsString(sansVersion).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static <T> List<T> liste(List<T> l) {
        return l == null ? new ArrayList<>() : l;
    }

    private static BusinessException invalide(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, "REQUETE_INVALIDE", message);
    }

    private static BusinessException conflit(String message) {
        return new BusinessException(HttpStatus.CONFLICT, "CONFLIT_DONNEES", message);
    }
}
