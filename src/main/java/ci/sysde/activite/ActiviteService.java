package ci.sysde.activite;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import ci.sysde.activite.ActiviteDtos.Activity;
import ci.sysde.activite.ActiviteDtos.ActivityInput;
import ci.sysde.activite.ActiviteDtos.FrequencyInput;
import ci.sysde.activite.ActiviteDtos.ProfileFrequencies;
import ci.sysde.activite.ActiviteDtos.ProfileFrequenciesInput;
import ci.sysde.common.BusinessException;
import ci.sysde.planning.PlanningDtos.Frequency;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Référentiel des activités et des fréquences par profil. Les règles de forme sont dans la base (contraintes) ; ici, les
 * contrôles qui donnent un message clair, et la protection des activités déjà planifiées.
 */
@Service
class ActiviteService {

    /** Profils qui ont un planning. */
    private static final Set<String> PROFILS = Set.of("CS", "CF", "CO", "FS", "MO", "ES");
    private static final Set<String> PERIODES = Set.of("ANNEE", "SEMESTRE", "TRIMESTRE", "MOIS", "SEMAINE");
    private static final Set<String> PAR = Set.of("LOT", "PLANTATION", "SAIGNEUR", "MONITEUR", "GROUPE");

    private static final String COLONNES = "id, code, libelle, categorie, duree_jours, planifiee, avec_presence, avec_evaluation,"
            + " avec_rapport, participants_min, participants_max, mois_debut, mois_fin, ordre, actif";

    private final JdbcClient jdbc;

    ActiviteService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    List<Activity> lister() {
        Map<UUID, List<ProfileFrequencies>> profils = profils(null);
        return jdbc.sql("select " + COLONNES + " from planning.activite where supprime_le is null order by ordre, code")
                .query((rs, n) -> activite(rs, profils)).list();
    }

    @Transactional(readOnly = true)
    Activity lire(UUID id) {
        Map<UUID, List<ProfileFrequencies>> profils = profils(id);
        return jdbc.sql("select " + COLONNES + " from planning.activite where id = :id and supprime_le is null")
                .param("id", id).query((rs, n) -> activite(rs, profils)).optional().orElseThrow(ActiviteService::introuvable);
    }

    @Transactional
    Activity creer(ActivityInput in) {
        if (in.code() == null) {
            throw invalide("Le code est obligatoire à la création.");
        }
        verifier(in);
        if (jdbc.sql("select count(*) from planning.activite where code = :c and supprime_le is null").param("c", in.code())
                .query(Long.class).single() > 0) {
            throw conflit("Une activité porte déjà le code « " + in.code() + " ».");
        }
        int ordre = in.order() != null ? in.order()
                : jdbc.sql("select coalesce(max(ordre), 0) + 1 from planning.activite").query(Integer.class).single();
        UUID id = jdbc.sql("""
                        insert into planning.activite (code, libelle, categorie, duree_jours, planifiee, avec_presence, avec_evaluation,
                                                       avec_rapport, participants_min, participants_max, mois_debut, mois_fin, ordre, actif)
                        values (:code, :libelle, :categorie, :duree, :planifiee, :presence, :evaluation, :rapport, :pmin, :pmax, :debut, :fin,
                                :ordre, :actif)
                        returning id
                        """)
                .param("code", in.code()).param("libelle", in.label().strip()).param("categorie", in.category())
                .param("duree", in.durationDays()).param("planifiee", in.planned()).param("presence", in.withAttendance())
                .param("evaluation", in.withEvaluation()).param("rapport", in.withReport()).param("pmin", in.minParticipants())
                .param("pmax", in.maxParticipants()).param("debut", in.monthFrom()).param("fin", in.monthTo()).param("ordre", ordre)
                .param("actif", in.active()).query(UUID.class).single();
        synchroniserProfils(id, in.profiles());
        return lire(id);
    }

    @Transactional
    Activity modifier(UUID id, ActivityInput in) {
        Activity actuelle = lire(id);
        verifier(in);
        if (in.code() != null && !in.code().equals(actuelle.code())) {
            throw invalide("Le code d'une activité ne change pas.");
        }
        boolean planifiee = jdbc.sql("select count(*) from planning.ligne_planning where activite_id = :id and supprime_le is null")
                .param("id", id).query(Long.class).single() > 0;
        if (planifiee && (!actuelle.category().equals(in.category()) || actuelle.durationDays() != in.durationDays()
                || actuelle.planned() != in.planned())) {
            throw conflit("Cette activité figure déjà dans des plannings : sa catégorie, sa durée et son caractère planifié ne changent plus"
                    + " (désactivez-la et créez-en une nouvelle).");
        }
        // Retirer un profil qui a déjà des lignes de planning pour cette activité : refusé.
        Set<String> gardes = new HashSet<>();
        in.profiles().forEach(p -> gardes.add(p.profile()));
        for (ProfileFrequencies p : actuelle.profiles()) {
            if (!gardes.contains(p.profile()) && jdbc.sql("""
                            select count(*) from planning.ligne_planning l
                              join planning.plan_annuel pl on pl.id = l.plan_id
                              join iam.compte c on c.id = pl.compte_id
                             where l.activite_id = :id and l.supprime_le is null and pl.supprime_le is null and c.profil = :profil
                            """).param("id", id).param("profil", p.profile()).query(Long.class).single() > 0) {
                throw conflit("Le profil " + p.profile() + " a déjà des lignes de planning pour cette activité : on ne peut plus le retirer.");
            }
        }
        int ordre = in.order() != null ? in.order() : actuelle.order();
        jdbc.sql("""
                        update planning.activite
                           set libelle = :libelle, categorie = :categorie, duree_jours = :duree, planifiee = :planifiee,
                               avec_presence = :presence, avec_evaluation = :evaluation, avec_rapport = :rapport,
                               participants_min = :pmin, participants_max = :pmax, mois_debut = :debut, mois_fin = :fin,
                               ordre = :ordre, actif = :actif, version = version + 1
                         where id = :id
                        """)
                .param("id", id).param("libelle", in.label().strip()).param("categorie", in.category()).param("duree", in.durationDays())
                .param("planifiee", in.planned()).param("presence", in.withAttendance()).param("evaluation", in.withEvaluation())
                .param("rapport", in.withReport()).param("pmin", in.minParticipants()).param("pmax", in.maxParticipants())
                .param("debut", in.monthFrom()).param("fin", in.monthTo()).param("ordre", ordre).param("actif", in.active()).update();
        synchroniserProfils(id, in.profiles());
        return lire(id);
    }

    // ------------------------------------------------------------------ profils et fréquences

    /** Aligne les lignes de la base sur la demande : met à jour celles qui existent (même profil et même tranche), ajoute, retire les autres. */
    private void synchroniserProfils(UUID id, List<ProfileFrequenciesInput> profils) {
        record Ligne(String profil, BigDecimal min, Integer nombre, String periode, String par, BigDecimal max, String regle) {
            String cle() {
                return profil + "/" + (min == null ? "0" : min.stripTrailingZeros().toPlainString());
            }
        }
        Map<String, Ligne> voulues = new LinkedHashMap<>();
        for (ProfileFrequenciesInput p : profils) {
            List<FrequencyInput> frequences = p.frequencies() == null || p.frequencies().isEmpty()
                    ? List.of(new FrequencyInput(null, null, null, null, null, null)) : p.frequencies();
            for (FrequencyInput f : frequences) {
                Ligne l = new Ligne(p.profile(), f.minAreaHa(), f.count(), f.period(), f.per(), f.maxAreaHa(),
                        f.rule() == null ? "" : f.rule().strip());
                if (voulues.put(l.cle(), l) != null) {
                    throw invalide("Le profil " + p.profile() + " a deux fréquences pour la même tranche de superficie.");
                }
            }
        }
        Map<String, UUID> existantes = new HashMap<>();
        jdbc.sql("select id, profil, superficie_min_ha from planning.activite_profil where activite_id = :id and supprime_le is null")
                .param("id", id).query((rs, n) -> {
                    BigDecimal min = rs.getBigDecimal("superficie_min_ha");
                    existantes.put(rs.getString("profil").strip() + "/" + (min == null ? "0" : min.stripTrailingZeros().toPlainString()),
                            rs.getObject("id", UUID.class));
                    return 0;
                }).list();
        for (Map.Entry<String, UUID> e : existantes.entrySet()) {
            if (!voulues.containsKey(e.getKey())) {
                jdbc.sql("update planning.activite_profil set supprime_le = now(), version = version + 1 where id = :id")
                        .param("id", e.getValue()).update();
            }
        }
        for (Ligne l : voulues.values()) {
            UUID ligneId = existantes.get(l.cle());
            var requete = ligneId != null
                    ? jdbc.sql("""
                            update planning.activite_profil set nombre = :nombre, periode = :periode, par = :par, superficie_min_ha = :min,
                                   superficie_max_ha = :max, regle = :regle, version = version + 1 where id = :ligne
                            """).param("ligne", ligneId)
                    : jdbc.sql("""
                            insert into planning.activite_profil (activite_id, profil, nombre, periode, par, superficie_min_ha, superficie_max_ha, regle)
                            values (:id, :profil, :nombre, :periode, :par, :min, :max, :regle)
                            """).param("id", id).param("profil", l.profil());
            requete.param("nombre", l.nombre()).param("periode", l.periode()).param("par", l.par()).param("min", l.min())
                    .param("max", l.max()).param("regle", l.regle()).update();
        }
    }

    private void verifier(ActivityInput in) {
        if (in.minParticipants() != null && in.maxParticipants() != null && in.minParticipants() > in.maxParticipants()) {
            throw invalide("Le nombre minimal de participants dépasse le maximum.");
        }
        if ((in.monthFrom() == null) != (in.monthTo() == null)) {
            throw invalide("La période habituelle se donne avec ses deux mois (début et fin).");
        }
        Set<String> vus = new HashSet<>();
        for (ProfileFrequenciesInput p : in.profiles()) {
            if (!PROFILS.contains(p.profile())) {
                throw invalide("Le profil « " + p.profile() + " » n'a pas de planning (profils : " + String.join(", ", new java.util.TreeSet<>(PROFILS)) + ").");
            }
            if (!vus.add(p.profile())) {
                throw invalide("Le profil " + p.profile() + " figure plusieurs fois.");
            }
            for (FrequencyInput f : p.frequencies() == null ? List.<FrequencyInput>of() : p.frequencies()) {
                if (f.count() != null && f.period() == null) {
                    throw invalide("Une fréquence avec un nombre de séances demande sa période.");
                }
                if (f.period() != null && !PERIODES.contains(f.period())) {
                    throw invalide("Période inconnue : « " + f.period() + " » (" + String.join(", ", PERIODES) + ").");
                }
                if (f.per() != null && !PAR.contains(f.per())) {
                    throw invalide("Valeur « par » inconnue : « " + f.per() + " » (" + String.join(", ", PAR) + ").");
                }
                if (f.minAreaHa() != null && f.maxAreaHa() != null && f.maxAreaHa().compareTo(f.minAreaHa()) <= 0) {
                    throw invalide("La superficie maximale d'une tranche doit dépasser son minimum.");
                }
                if (f.maxAreaHa() != null && f.maxAreaHa().signum() <= 0) {
                    throw invalide("La superficie maximale doit être positive.");
                }
            }
        }
    }

    // ------------------------------------------------------------------ lecture

    private Map<UUID, List<ProfileFrequencies>> profils(UUID activiteId) {
        Map<UUID, Map<String, List<Frequency>>> parActivite = new LinkedHashMap<>();
        var requete = jdbc.sql("""
                        select activite_id, profil, nombre, periode, par, superficie_min_ha, superficie_max_ha, regle
                          from planning.activite_profil where supprime_le is null""" + (activiteId == null ? "" : " and activite_id = :id")
                + " order by profil, superficie_min_ha nulls first");
        if (activiteId != null) {
            requete = requete.param("id", activiteId);
        }
        requete.query((rs, n) -> {
            Integer nombre = rs.getObject("nombre", Integer.class);
            String periode = rs.getString("periode");
            BigDecimal min = rs.getBigDecimal("superficie_min_ha");
            BigDecimal max = rs.getBigDecimal("superficie_max_ha");
            String par = rs.getString("par");
            String regle = rs.getString("regle");
            List<Frequency> liste = parActivite.computeIfAbsent(rs.getObject("activite_id", UUID.class), k -> new LinkedHashMap<>())
                    .computeIfAbsent(rs.getString("profil").strip(), k -> new ArrayList<>());
            // une ligne sans aucune donnée = profil sans fréquence définie : pas de fréquence à lister
            if (nombre != null || periode != null || par != null || min != null || max != null || (regle != null && !regle.isEmpty())) {
                liste.add(new Frequency(nombre, periode, par, min, max, regle));
            }
            return 0;
        }).list();
        Map<UUID, List<ProfileFrequencies>> sortie = new HashMap<>();
        parActivite.forEach((a, m) -> sortie.put(a, m.entrySet().stream().map(e -> new ProfileFrequencies(e.getKey(), e.getValue())).toList()));
        return sortie;
    }

    private static Activity activite(java.sql.ResultSet rs, Map<UUID, List<ProfileFrequencies>> profils) throws java.sql.SQLException {
        UUID id = rs.getObject("id", UUID.class);
        return new Activity(id, rs.getString("code"), rs.getString("libelle"), rs.getString("categorie"), rs.getInt("duree_jours"),
                rs.getBoolean("planifiee"), rs.getBoolean("avec_presence"), rs.getBoolean("avec_evaluation"), rs.getBoolean("avec_rapport"),
                rs.getObject("participants_min", Integer.class), rs.getObject("participants_max", Integer.class),
                rs.getObject("mois_debut", Integer.class), rs.getObject("mois_fin", Integer.class), rs.getInt("ordre"),
                rs.getBoolean("actif"), profils.getOrDefault(id, List.of()));
    }

    private static BusinessException introuvable() {
        return new BusinessException(HttpStatus.NOT_FOUND, "INTROUVABLE", "Activité introuvable.");
    }

    private static BusinessException invalide(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, "REQUETE_INVALIDE", message);
    }

    private static BusinessException conflit(String message) {
        return new BusinessException(HttpStatus.CONFLICT, "CONFLIT", message);
    }
}
