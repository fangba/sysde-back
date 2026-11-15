package ci.sysde.planning;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import ci.sysde.common.BusinessException;
import ci.sysde.notification.NotificationService;
import ci.sysde.planning.PlanningDtos.AnnualPlan;
import ci.sysde.planning.PlanningDtos.Frequency;
import ci.sysde.planning.PlanningDtos.PersonRef;
import ci.sysde.planning.PlanningDtos.PlanActivity;
import ci.sysde.planning.PlanningDtos.PlanLine;
import ci.sysde.planning.PlanningDtos.PlanLineInput;
import ci.sysde.planning.PlanningDtos.Rejection;
import ci.sysde.planning.PlanningDtos.Submission;
import ci.sysde.security.Acteur;
import ci.sysde.security.Perimetre;
import ci.sysde.security.PerimetreService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Planning annuel d'activités. La base porte les règles d'intégrité d'une ligne (année du plan, activité propre au profil,
 * planifiable, visite d'un jour) et du rejet (motif). Ici : le cycle de vie du plan (brouillon, soumis, validé), le
 * verrouillage des activités soumises, qui peut agir sur quel plan (titulaire, secrétaire, chef secteur) et les notifications.
 *
 * <p>Pas encore contrôlé : les fréquences (nombre de séances par période, par plantation…) : les règles sont des données
 * ({@code activite_profil}), mais qui les vérifie reste à décider avec le client.
 */
@Service
public class PlanningService {

    static final Set<String> PROFILS_PLANNING = Set.of("CS", "CF", "CO", "FS", "MO", "ES");
    private static final List<String> EQUIPE_DU_CS = List.of("CF", "CO", "FS", "MO", "ES");
    private static final List<String> EQUIPE_DE_LA_SECRETAIRE = List.of("CS", "CF", "CO", "FS", "MO", "ES");

    private record PlanRow(UUID id, UUID compteId, int annee, String statut, OffsetDateTime rejetLe, String rejetMotif,
                           String saisiPar, OffsetDateTime saisiLe) {
    }

    private record ActiviteInfo(UUID id, String libelle, String categorie, boolean actif, boolean planifiee) {
    }

    private final JdbcClient jdbc;
    private final PerimetreService perimetres;
    private final NotificationService notifications;

    PlanningService(JdbcClient jdbc, PerimetreService perimetres, NotificationService notifications) {
        this.jdbc = jdbc;
        this.perimetres = perimetres;
        this.notifications = notifications;
    }

    // ------------------------------------------------------------------ référentiel

    /** Activités que le profil de l'appelant peut planifier, avec leurs fréquences (lecture seule). */
    @Transactional(readOnly = true)
    public List<PlanActivity> activites(Acteur acteur) {
        Map<UUID, PlanActivity> par = new LinkedHashMap<>();
        Map<UUID, List<Frequency>> frequences = new HashMap<>();
        jdbc.sql("""
                        select a.id, a.code, a.libelle, a.categorie, a.duree_jours, a.planifiee, a.mois_debut, a.mois_fin,
                               ap.nombre, ap.periode, ap.par, ap.superficie_min_ha, ap.superficie_max_ha, ap.regle
                          from planning.activite a
                          join planning.activite_profil ap on ap.activite_id = a.id and ap.profil = :profil and ap.supprime_le is null
                         where a.supprime_le is null and a.actif and a.planifiee
                         order by a.ordre, ap.superficie_min_ha nulls first
                        """)
                .param("profil", acteur.profil())
                .query((rs, n) -> {
                    UUID id = rs.getObject("id", UUID.class);
                    frequences.computeIfAbsent(id, k -> new ArrayList<>()).add(new Frequency(rs.getObject("nombre", Integer.class),
                            rs.getString("periode"), rs.getString("par"), rs.getBigDecimal("superficie_min_ha"),
                            rs.getBigDecimal("superficie_max_ha"), rs.getString("regle")));
                    par.computeIfAbsent(id, k -> {
                        try {
                            return new PlanActivity(id, rs.getString("code"), rs.getString("libelle"), rs.getString("categorie"),
                                    rs.getInt("duree_jours"), rs.getBoolean("planifiee"), rs.getObject("mois_debut", Integer.class),
                                    rs.getObject("mois_fin", Integer.class), frequences.get(id));
                        } catch (java.sql.SQLException e) {
                            throw new IllegalStateException(e);
                        }
                    });
                    return id;
                }).list();
        return List.copyOf(par.values());
    }

    // ------------------------------------------------------------------ mon planning

    /** Crée un planning vide en brouillon s'il n'existe pas encore. */
    @Transactional
    public AnnualPlan monPlanning(Acteur acteur, int annee) {
        exigerProfilPlanning(acteur);
        return construire(obtenir(acteur.compteId(), annee));
    }

    @Transactional
    AnnualPlan remplacerMesLignes(Acteur acteur, int annee, List<PlanLineInput> lignes) {
        exigerProfilPlanning(acteur);
        return remplacer(acteur.compteId(), annee, lignes, "TITULAIRE");
    }

    @Transactional
    AnnualPlan soumettreMonPlanning(Acteur acteur, int annee) {
        exigerProfilPlanning(acteur);
        return soumettre(acteur.compteId(), annee, false);
    }

    /** Soumet une seule activité au chef secteur ; ses dates sont verrouillées jusqu'à la décision. Titulaire seulement, jamais le CS. */
    @Transactional
    AnnualPlan soumettreActivite(Acteur acteur, int annee, UUID activiteId) {
        exigerProfilPlanning(acteur);
        if (acteur.estProfil(Acteur.CHEF_SECTEUR)) {
            throw refuse("Le chef secteur n'a personne au-dessus de lui : il soumet son planning entier, qui est validé d'office.");
        }
        PlanRow plan = verrouiller(acteur.compteId(), annee);
        if (!"BROUILLON".equals(plan.statut())) {
            throw conflit("Le planning est déjà " + (plan.statut().equals("SOUMIS") ? "soumis" : "validé") + ".");
        }
        int lignes = jdbc.sql("select count(*) from planning.ligne_planning where plan_id = :p and activite_id = :a and supprime_le is null")
                .param("p", plan.id()).param("a", activiteId).query(Integer.class).single();
        if (lignes == 0) {
            throw invalide("Cette activité n'a aucune date dans le planning : rien à soumettre.");
        }
        String actuel = jdbc.sql("select statut from planning.soumission_activite where plan_id = :p and activite_id = :a")
                .param("p", plan.id()).param("a", activiteId).query(String.class).optional().orElse(null);
        if ("SOUMIS".equals(actuel) || "VALIDE".equals(actuel)) {
            throw conflit("Cette activité est déjà " + ("SOUMIS".equals(actuel) ? "soumise" : "validée") + ".");
        }
        jdbc.sql("""
                        insert into planning.soumission_activite (plan_id, activite_id) values (:p, :a)
                        on conflict (plan_id, activite_id) do update
                           set statut = 'SOUMIS', soumis_le = now(), decide_le = null, decide_par = null, motif = null
                        """)
                .param("p", plan.id()).param("a", activiteId).update();
        return construire(plan);
    }

    // ------------------------------------------------------------------ équipe et saisie par la secrétaire

    /** Un plan par collaborateur actif du secteur, y compris ceux qui n'en ont pas encore (vide, sans identifiant). */
    @Transactional(readOnly = true)
    List<AnnualPlan> equipe(Acteur acteur, int annee) {
        verifierAnnee(annee);
        boolean cs = acteur.estProfil(Acteur.CHEF_SECTEUR);
        if (!cs && !acteur.estProfil("SE")) {
            throw refuse("L'équipe du secteur est réservée au chef secteur et à la secrétaire.");
        }
        Set<UUID> secteurs = perimetres.de(acteur).secteursEntiers();
        if (secteurs.isEmpty()) {
            return List.of();
        }
        List<PersonRef> membres = jdbc.sql("""
                        select c.id, c.matricule, c.profil, u.nom, u.prenoms
                          from iam.compte c join iam.utilisateur u on u.id = c.utilisateur_id
                         where c.statut = 'ACTIF' and c.supprime_le is null and c.operateur_id = :op and c.id <> :self
                           and c.profil in (:profils)
                           and exists (select 1 from iam.affectation f where f.compte_id = c.id and f.fin is null
                                          and f.supprime_le is null and f.secteur_id in (:secteurs))
                         order by u.nom, u.prenoms, c.id
                        """)
                .param("op", acteur.operateurId()).param("self", acteur.compteId())
                .param("profils", cs ? EQUIPE_DU_CS : EQUIPE_DE_LA_SECRETAIRE).param("secteurs", secteurs)
                .query(PlanningService::personne).list();
        if (membres.isEmpty()) {
            return List.of();
        }
        Map<UUID, PlanRow> existants = new HashMap<>();
        jdbc.sql(SELECT_PLAN + " where p.compte_id in (:ids) and p.annee = :annee and p.supprime_le is null")
                .param("ids", membres.stream().map(PersonRef::id).toList()).param("annee", annee)
                .query(PlanningService::ligne).list().forEach(r -> existants.put(r.compteId(), r));
        Map<UUID, AnnualPlan> construits = new HashMap<>();
        construire(new ArrayList<>(existants.values())).forEach(p -> construits.put(p.account().id(), p));
        List<AnnualPlan> sortie = new ArrayList<>();
        for (PersonRef m : membres) {
            sortie.add(construits.getOrDefault(m.id(), new AnnualPlan(null, m, annee, "BROUILLON", List.of(), null, List.of(), null, null)));
        }
        return sortie;
    }

    @Transactional
    AnnualPlan planDuCollaborateur(Acteur secretaire, UUID cible, int annee) {
        exigerSecretaireDe(secretaire, cible);
        return construire(obtenir(cible, annee));
    }

    @Transactional
    AnnualPlan remplacerLignesDuCollaborateur(Acteur secretaire, UUID cible, int annee, List<PlanLineInput> lignes) {
        exigerSecretaireDe(secretaire, cible);
        return remplacer(cible, annee, lignes, "SECRETAIRE");
    }

    @Transactional
    AnnualPlan soumettreDuCollaborateur(Acteur secretaire, UUID cible, int annee) {
        exigerSecretaireDe(secretaire, cible);
        return soumettre(cible, annee, true);
    }

    // ------------------------------------------------------------------ décisions du chef secteur

    @Transactional
    AnnualPlan valider(Acteur cs, UUID planId) {
        PlanRow plan = planDeLEquipe(cs, planId);
        int n = jdbc.sql("update planning.plan_annuel set statut = 'VALIDE', valide_le = now(), valide_par = :cs where id = :id and statut = 'SOUMIS'")
                .param("id", planId).param("cs", cs.compteId()).update();
        if (n == 0) {
            throw conflit("Seul un planning soumis peut être validé.");
        }
        // Les activités soumises une à une sont couvertes par la validation du planning.
        jdbc.sql("update planning.soumission_activite set statut = 'VALIDE', decide_le = now(), decide_par = :cs where plan_id = :id and statut = 'SOUMIS'")
                .param("id", planId).param("cs", cs.compteId()).update();
        notifications.creer(plan.compteId(), "PLANNING_VALIDE", "Planning validé",
                "Votre planning " + plan.annee() + " a été validé par votre chef secteur.", "success", planId);
        return construire(recharger(planId));
    }

    /** Le planning repasse en brouillon avec le motif ; ses activités encore soumises sont rejetées avec le même motif. */
    @Transactional
    AnnualPlan rejeter(Acteur cs, UUID planId, String motif) {
        PlanRow plan = planDeLEquipe(cs, planId);
        int n = jdbc.sql("update planning.plan_annuel set statut = 'BROUILLON', soumis_le = null, rejet_motif = :m, rejet_le = now() where id = :id and statut = 'SOUMIS'")
                .param("id", planId).param("m", motif.strip()).update();
        if (n == 0) {
            throw conflit("Seul un planning soumis peut être rejeté.");
        }
        jdbc.sql("update planning.soumission_activite set statut = 'REJETE', decide_le = now(), decide_par = :cs, motif = :m where plan_id = :id and statut = 'SOUMIS'")
                .param("id", planId).param("cs", cs.compteId()).param("m", motif.strip()).update();
        notifications.creer(plan.compteId(), "PLANNING_REJETE", "Planning rejeté",
                "Votre planning " + plan.annee() + " a été rejeté : " + motif.strip(), "warning", planId);
        return construire(recharger(planId));
    }

    @Transactional
    AnnualPlan validerActivite(Acteur cs, UUID planId, UUID activiteId) {
        PlanRow plan = planDeLEquipe(cs, planId);
        int n = jdbc.sql("update planning.soumission_activite set statut = 'VALIDE', decide_le = now(), decide_par = :cs, motif = null where plan_id = :p and activite_id = :a and statut = 'SOUMIS'")
                .param("p", planId).param("a", activiteId).param("cs", cs.compteId()).update();
        if (n == 0) {
            throw conflit("Seule une activité soumise peut être validée.");
        }
        notifications.creer(plan.compteId(), "ACTIVITE_VALIDEE", "Activité validée",
                "« " + libelle(activiteId) + " » de votre planning " + plan.annee() + " a été validée.", "success", planId);
        return construire(recharger(planId));
    }

    @Transactional
    AnnualPlan rejeterActivite(Acteur cs, UUID planId, UUID activiteId, String motif) {
        PlanRow plan = planDeLEquipe(cs, planId);
        int n = jdbc.sql("update planning.soumission_activite set statut = 'REJETE', decide_le = now(), decide_par = :cs, motif = :m where plan_id = :p and activite_id = :a and statut = 'SOUMIS'")
                .param("p", planId).param("a", activiteId).param("cs", cs.compteId()).param("m", motif.strip()).update();
        if (n == 0) {
            throw conflit("Seule une activité soumise peut être rejetée.");
        }
        notifications.creer(plan.compteId(), "ACTIVITE_REJETEE", "Activité rejetée",
                "« " + libelle(activiteId) + " » de votre planning " + plan.annee() + " a été rejetée : " + motif.strip(), "warning", planId);
        return construire(recharger(planId));
    }

    // ------------------------------------------------------------------ cœur : lignes et soumission

    private AnnualPlan remplacer(UUID compte, int annee, List<PlanLineInput> entree, String saisiPar) {
        PlanRow plan = verrouiller(compte, annee);
        if (!"BROUILLON".equals(plan.statut())) {
            throw conflit("Le planning est " + ("SOUMIS".equals(plan.statut()) ? "soumis" : "validé") + " : il n'est plus modifiable.");
        }
        List<PlanLineInput> lignes = entree == null ? List.of() : entree;
        verifierLignes(compte, annee, lignes);

        Set<UUID> verrouillees = new HashSet<>(jdbc.sql("select activite_id from planning.soumission_activite where plan_id = :p and statut in ('SOUMIS', 'VALIDE')")
                .param("p", plan.id()).query(UUID.class).list());
        // Les lignes d'une activité soumise ou validée ne bougent pas : le client doit les renvoyer à l'identique.
        List<String> existantesVerrouillees = new ArrayList<>(jdbc.sql("select activite_id, date_debut, date_fin, lot_id, section_id, plantation_ref, groupe_id from planning.ligne_planning where plan_id = :p and supprime_le is null")
                .param("p", plan.id()).query((rs, n) -> verrouillees.contains(rs.getObject("activite_id", UUID.class))
                        ? cle(rs.getObject("activite_id", UUID.class), rs.getObject("date_debut", LocalDate.class), rs.getObject("date_fin", LocalDate.class),
                        rs.getObject("lot_id", UUID.class), rs.getObject("section_id", UUID.class), rs.getString("plantation_ref"), rs.getObject("groupe_id", UUID.class))
                        : null).list().stream().filter(java.util.Objects::nonNull).toList());
        List<String> recuesVerrouillees = lignes.stream().filter(l -> verrouillees.contains(l.activityId()))
                .map(l -> cle(l.activityId(), l.date(), l.endDate() == null ? l.date() : l.endDate(), l.lotId(), l.sectionId(), l.plantationRef(), l.groupId()))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        java.util.Collections.sort(existantesVerrouillees);
        java.util.Collections.sort(recuesVerrouillees);
        if (!existantesVerrouillees.equals(recuesVerrouillees)) {
            throw conflit("Les dates d'une activité soumise ou validée ne peuvent pas être modifiées.");
        }

        if (verrouillees.isEmpty()) {
            jdbc.sql("update planning.ligne_planning set supprime_le = now(), supprime_par = util.acteur() where plan_id = :p and supprime_le is null")
                    .param("p", plan.id()).update();
        } else {
            jdbc.sql("update planning.ligne_planning set supprime_le = now(), supprime_par = util.acteur() where plan_id = :p and supprime_le is null and activite_id not in (:v)")
                    .param("p", plan.id()).param("v", verrouillees).update();
        }
        for (PlanLineInput l : lignes) {
            if (verrouillees.contains(l.activityId())) {
                continue;
            }
            jdbc.sql("""
                            insert into planning.ligne_planning (plan_id, activite_id, date_debut, date_fin, lot_id, section_id, plantation_ref, groupe_id)
                            values (:p, :a, :d, :f, :lot, :section, :plantation, :groupe)
                            """)
                    .param("p", plan.id()).param("a", l.activityId()).param("d", l.date()).param("f", l.endDate() == null ? l.date() : l.endDate())
                    .param("lot", l.lotId()).param("section", l.sectionId())
                    .param("plantation", l.plantationRef() == null || l.plantationRef().isBlank() ? null : l.plantationRef().strip())
                    .param("groupe", l.groupId()).update();
        }
        jdbc.sql("update planning.plan_annuel set saisi_par = :s, saisi_le = now() where id = :id").param("s", saisiPar).param("id", plan.id()).update();
        return construire(recharger(plan.id()));
    }

    /** Soumet le plan : le chef secteur est validé d'office (personne au-dessus), les autres passent à SOUMIS. */
    private AnnualPlan soumettre(UUID compte, int annee, boolean parSecretaire) {
        PlanRow plan = verrouiller(compte, annee);
        if (!"BROUILLON".equals(plan.statut())) {
            throw conflit("Le planning est déjà " + ("SOUMIS".equals(plan.statut()) ? "soumis" : "validé") + ".");
        }
        int lignes = jdbc.sql("select count(*) from planning.ligne_planning where plan_id = :p and supprime_le is null").param("p", plan.id()).query(Integer.class).single();
        if (lignes == 0) {
            throw invalide("Le planning est vide : ajoutez au moins une activité avant de le soumettre.");
        }
        String profil = jdbc.sql("select profil from iam.compte where id = :id").param("id", compte).query(String.class).single().strip();
        boolean valideDOffice = "CS".equals(profil);
        if (valideDOffice) {
            jdbc.sql("update planning.plan_annuel set statut = 'VALIDE', soumis_le = now(), valide_le = now(), rejet_motif = null, rejet_le = null where id = :id")
                    .param("id", plan.id()).update();
        } else {
            jdbc.sql("update planning.plan_annuel set statut = 'SOUMIS', soumis_le = now(), rejet_motif = null, rejet_le = null where id = :id")
                    .param("id", plan.id()).update();
        }
        if (parSecretaire) {
            notifications.creer(compte, "PLANNING_SOUMIS_PAR_SECRETAIRE", "Planning soumis",
                    valideDOffice ? "La secrétaire a enregistré et validé votre planning " + annee + "."
                            : "La secrétaire a soumis votre planning " + annee + " à votre chef secteur.", "info", plan.id());
        }
        return construire(recharger(plan.id()));
    }

    // ------------------------------------------------------------------ contrôles

    /** Contrôles d'une saisie, avec le numéro de ligne (à partir de 1) ; la base revérifie année, profil, visite d'un jour. */
    private void verifierLignes(UUID compte, int annee, List<PlanLineInput> lignes) {
        if (lignes.isEmpty()) {
            return;
        }
        Map<UUID, ActiviteInfo> activites = new HashMap<>();
        jdbc.sql("select id, libelle, categorie, actif, planifiee from planning.activite where supprime_le is null and id in (:ids)")
                .param("ids", lignes.stream().map(PlanLineInput::activityId).distinct().toList())
                .query((rs, n) -> activites.put(rs.getObject("id", UUID.class), new ActiviteInfo(rs.getObject("id", UUID.class),
                        rs.getString("libelle"), rs.getString("categorie"), rs.getBoolean("actif"), rs.getBoolean("planifiee")))).list();
        UUID operateur = jdbc.sql("select operateur_id from iam.compte where id = :id").param("id", compte).query(UUID.class).single();

        Set<UUID> lotsDemandes = new HashSet<>();
        Set<UUID> sectionsDemandees = new HashSet<>();
        Set<UUID> groupesDemandes = new HashSet<>();
        lignes.forEach(l -> {
            if (l.lotId() != null) {
                lotsDemandes.add(l.lotId());
            }
            if (l.sectionId() != null) {
                sectionsDemandees.add(l.sectionId());
            }
            if (l.groupId() != null) {
                groupesDemandes.add(l.groupId());
            }
        });
        Set<UUID> lotsAutorises = lotsDemandes.isEmpty() ? Set.of() : new HashSet<>(jdbc.sql("""
                        select l.id from referentiel.lot l
                         where l.id in (:ids) and l.supprime_le is null
                           and exists (select 1 from gestion.attribution_lot al join gestion.contrat c on c.id = al.contrat_id
                                        where al.lot_id = l.id and c.operateur_id = :op and al.periode @> current_date)
                           and l.secteur_id in (select f.secteur_id from iam.affectation f
                                                 where f.compte_id = :compte and f.fin is null and f.supprime_le is null)
                        """).param("ids", lotsDemandes).param("op", operateur).param("compte", compte).query(UUID.class).list());
        Map<UUID, UUID> lotDeSection = new HashMap<>();
        if (!sectionsDemandees.isEmpty()) {
            jdbc.sql("select id, lot_id from referentiel.section where id in (:ids) and supprime_le is null").param("ids", sectionsDemandees)
                    .query((rs, n) -> lotDeSection.put(rs.getObject("id", UUID.class), rs.getObject("lot_id", UUID.class))).list();
        }
        Map<UUID, UUID> lotDeGroupe = new HashMap<>();
        if (!groupesDemandes.isEmpty()) {
            jdbc.sql("select id, lot_id from planning.groupe_vulgarisation where id in (:ids) and supprime_le is null").param("ids", groupesDemandes)
                    .query((rs, n) -> lotDeGroupe.put(rs.getObject("id", UUID.class), rs.getObject("lot_id", UUID.class))).list();
        }

        int numero = 0;
        for (PlanLineInput l : lignes) {
            numero++;
            String ou = "Ligne " + numero + " : ";
            ActiviteInfo a = activites.get(l.activityId());
            if (a == null) {
                throw invalide(ou + "activité inconnue.");
            }
            if (!a.actif()) {
                throw invalide(ou + "l'activité « " + a.libelle() + " » n'est plus proposée.");
            }
            LocalDate fin = l.endDate() == null ? l.date() : l.endDate();
            if (fin.isBefore(l.date())) {
                throw invalide(ou + "la date de fin précède la date de début.");
            }
            if (l.date().getYear() != annee || fin.getYear() != annee) {
                throw invalide(ou + "les dates doivent être dans l'année du planning (" + annee + ").");
            }
            if ("VISITE".equals(a.categorie())) {
                if (!fin.equals(l.date())) {
                    throw invalide(ou + "une visite se fait en un seul jour.");
                }
                if (l.plantationRef() == null || l.plantationRef().isBlank()) {
                    throw invalide(ou + "la plantation visitée est obligatoire pour une visite.");
                }
            }
            if (l.sectionId() != null && l.lotId() == null) {
                throw invalide(ou + "une section se donne avec son lot.");
            }
            if (l.lotId() != null && !lotsAutorises.contains(l.lotId())) {
                throw invalide(ou + "ce lot n'est pas dans le secteur de ce compte ou n'est pas confié à son opérateur.");
            }
            if (l.sectionId() != null && !l.lotId().equals(lotDeSection.get(l.sectionId()))) {
                throw invalide(ou + "cette section n'appartient pas à ce lot.");
            }
            if (l.groupId() != null && !java.util.Objects.equals(l.lotId(), lotDeGroupe.get(l.groupId()))) {
                throw invalide(ou + "ce groupe n'appartient pas à ce lot.");
            }
        }
    }

    /** Le profil de l'appelant a-t-il un planning d'activité ? */
    public boolean aUnPlanning(Acteur acteur) {
        return PROFILS_PLANNING.contains(acteur.profil());
    }

    private void exigerProfilPlanning(Acteur acteur) {
        if (!aUnPlanning(acteur)) {
            throw refuse("Votre profil n'a pas de planning d'activité.");
        }
    }

    /** La secrétaire agit sur un collaborateur actif de son opérateur et de son secteur (CS compris), jamais sur une secrétaire. */
    private void exigerSecretaireDe(Acteur secretaire, UUID cible) {
        if (!secretaire.estProfil("SE")) {
            throw refuse("Seule la secrétaire saisit à la place d'un collaborateur.");
        }
        if (!estMembre(secretaire, cible, EQUIPE_DE_LA_SECRETAIRE)) {
            throw refuse("Ce compte n'est pas un collaborateur actif de votre secteur.");
        }
    }

    private PlanRow planDeLEquipe(Acteur cs, UUID planId) {
        PlanRow plan = jdbc.sql(SELECT_PLAN + " where p.id = :id and p.supprime_le is null").param("id", planId)
                .query(PlanningService::ligne).optional()
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "INTROUVABLE", "Planning introuvable."));
        if (!cs.estProfil(Acteur.CHEF_SECTEUR) || !estMembre(cs, plan.compteId(), EQUIPE_DU_CS)) {
            throw refuse("Ce planning n'est pas celui d'un collaborateur de votre secteur.");
        }
        return plan;
    }

    private boolean estMembre(Acteur acteur, UUID cible, List<String> profils) {
        Set<UUID> secteurs = perimetres.de(acteur).secteursEntiers();
        if (secteurs.isEmpty() || perimetres.de(acteur).etendue() != Perimetre.Etendue.AFFECTATIONS) {
            return false;
        }
        return jdbc.sql("""
                        select exists (select 1 from iam.compte c
                                        where c.id = :id and c.statut = 'ACTIF' and c.supprime_le is null and c.operateur_id = :op
                                          and c.profil in (:profils)
                                          and exists (select 1 from iam.affectation f where f.compte_id = c.id and f.fin is null
                                                         and f.supprime_le is null and f.secteur_id in (:secteurs)))
                        """)
                .param("id", cible).param("op", acteur.operateurId()).param("profils", profils).param("secteurs", secteurs)
                .query(Boolean.class).single();
    }

    // ------------------------------------------------------------------ accès aux plans

    private static final String SELECT_PLAN = """
            select p.id, p.compte_id, p.annee, p.statut, p.rejet_le, p.rejet_motif, p.saisi_par, p.saisi_le
              from planning.plan_annuel p""";

    private static PlanRow ligne(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new PlanRow(rs.getObject("id", UUID.class), rs.getObject("compte_id", UUID.class), rs.getInt("annee"),
                rs.getString("statut"), rs.getObject("rejet_le", OffsetDateTime.class), rs.getString("rejet_motif"),
                rs.getString("saisi_par"), rs.getObject("saisi_le", OffsetDateTime.class));
    }

    private static void verifierAnnee(int annee) {
        if (annee < 2000 || annee > 2100) {
            throw invalide("Année invalide : " + annee + ".");
        }
    }

    /** Lit le plan du compte pour l'année, en le créant (brouillon vide) s'il n'existe pas. */
    private PlanRow obtenir(UUID compte, int annee) {
        verifierAnnee(annee);
        jdbc.sql("insert into planning.plan_annuel (compte_id, annee) values (:c, :a) on conflict (compte_id, annee) where supprime_le is null do nothing")
                .param("c", compte).param("a", annee).update();
        return jdbc.sql(SELECT_PLAN + " where p.compte_id = :c and p.annee = :a and p.supprime_le is null").param("c", compte).param("a", annee)
                .query(PlanningService::ligne).single();
    }

    /** Comme {@link #obtenir} avec un verrou : deux saisies simultanées du même plan s'enchaînent. */
    private PlanRow verrouiller(UUID compte, int annee) {
        PlanRow p = obtenir(compte, annee);
        return jdbc.sql(SELECT_PLAN + " where p.id = :id for update").param("id", p.id()).query(PlanningService::ligne).single();
    }

    private PlanRow recharger(UUID planId) {
        return jdbc.sql(SELECT_PLAN + " where p.id = :id").param("id", planId).query(PlanningService::ligne).single();
    }

    private AnnualPlan construire(PlanRow plan) {
        return construire(List.of(plan)).get(0);
    }

    private List<AnnualPlan> construire(List<PlanRow> plans) {
        if (plans.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = plans.stream().map(PlanRow::id).toList();
        Map<UUID, PersonRef> personnes = new HashMap<>();
        jdbc.sql("select c.id, c.matricule, c.profil, u.nom, u.prenoms from iam.compte c join iam.utilisateur u on u.id = c.utilisateur_id where c.id in (:ids)")
                .param("ids", plans.stream().map(PlanRow::compteId).toList())
                .query(PlanningService::personne).list().forEach(p -> personnes.put(p.id(), p));
        Map<UUID, List<PlanLine>> lignes = new HashMap<>();
        jdbc.sql("""
                        select l.plan_id, l.id, l.date_debut, l.date_fin, l.activite_id, l.lot_id, lot.numero, l.section_id, s.nom as section_nom,
                               l.plantation_ref, l.groupe_id
                          from planning.ligne_planning l
                          left join referentiel.lot lot on lot.id = l.lot_id
                          left join referentiel.section s on s.id = l.section_id
                         where l.plan_id in (:ids) and l.supprime_le is null
                         order by l.date_debut, l.date_fin, l.id
                        """)
                .param("ids", ids)
                .query((rs, n) -> {
                    lignes.computeIfAbsent(rs.getObject("plan_id", UUID.class), k -> new ArrayList<>()).add(new PlanLine(
                            rs.getObject("id", UUID.class), rs.getObject("date_debut", LocalDate.class), rs.getObject("date_fin", LocalDate.class),
                            rs.getObject("activite_id", UUID.class), rs.getObject("lot_id", UUID.class), rs.getObject("numero", Integer.class),
                            rs.getObject("section_id", UUID.class), rs.getString("section_nom"), rs.getString("plantation_ref"),
                            rs.getObject("groupe_id", UUID.class)));
                    return null;
                }).list();
        Map<UUID, List<Submission>> soumissions = new HashMap<>();
        jdbc.sql("select plan_id, activite_id, statut, decide_le, motif from planning.soumission_activite where plan_id in (:ids) order by soumis_le, activite_id")
                .param("ids", ids)
                .query((rs, n) -> {
                    String motif = rs.getString("motif");
                    soumissions.computeIfAbsent(rs.getObject("plan_id", UUID.class), k -> new ArrayList<>()).add(new Submission(
                            rs.getObject("activite_id", UUID.class), rs.getString("statut"),
                            motif == null ? null : new Rejection(motif, rs.getObject("decide_le", OffsetDateTime.class))));
                    return null;
                }).list();

        List<AnnualPlan> sortie = new ArrayList<>();
        for (PlanRow p : plans) {
            sortie.add(new AnnualPlan(p.id(), personnes.get(p.compteId()), p.annee(), p.statut(),
                    lignes.getOrDefault(p.id(), List.of()),
                    p.rejetMotif() == null ? null : new Rejection(p.rejetMotif(), p.rejetLe()),
                    soumissions.getOrDefault(p.id(), List.of()),
                    p.saisiLe() == null ? null : ("SECRETAIRE".equals(p.saisiPar()) ? "SECRETARY" : "SELF"), p.saisiLe()));
        }
        return sortie;
    }

    private static PersonRef personne(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new PersonRef(rs.getObject("id", UUID.class), rs.getString("matricule"), rs.getString("profil").strip(),
                rs.getString("nom"), rs.getString("prenoms"));
    }

    private String libelle(UUID activiteId) {
        return jdbc.sql("select libelle from planning.activite where id = :id").param("id", activiteId).query(String.class).optional().orElse("Activité");
    }

    private static String cle(UUID activite, LocalDate debut, LocalDate fin, UUID lot, UUID section, String plantation, UUID groupe) {
        return String.join("|", String.valueOf(activite), String.valueOf(debut), String.valueOf(fin), String.valueOf(lot),
                String.valueOf(section), plantation == null || plantation.isBlank() ? "null" : plantation.strip(), String.valueOf(groupe));
    }

    private static BusinessException refuse(String message) {
        return new BusinessException(HttpStatus.FORBIDDEN, "ACCES_REFUSE", message);
    }

    private static BusinessException invalide(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, "REQUETE_INVALIDE", message);
    }

    private static BusinessException conflit(String message) {
        return new BusinessException(HttpStatus.CONFLICT, "CONFLIT_DONNEES", message);
    }
}
