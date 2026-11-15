package ci.sysde.mobile;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

import ci.sysde.common.BusinessException;
import ci.sysde.mobile.MobileDtos.Bundle;
import ci.sysde.mobile.MobileDtos.Me;
import ci.sysde.mobile.MobileDtos.RealisationState;
import ci.sysde.mobile.MobileDtos.SyncReport;
import ci.sysde.planning.PlanningDtos.AnnualPlan;
import ci.sysde.planning.PlanningService;
import ci.sysde.realisation.RealisationService;
import ci.sysde.referentiel.SecteurService;
import ci.sysde.referentiel.SectionService;
import ci.sysde.security.Acteur;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * API du mobile : paquet de départ (tout ce qu'il faut pour travailler hors ligne, dans le périmètre du compte, relu en
 * base) et signal de synchronisation. Aucune règle propre : on assemble ce que les autres modules servent déjà.
 */
@Service
class MobileService {

    private final JdbcClient jdbc;
    private final Clock horloge;
    private final PlanningService planning;
    private final RealisationService realisations;
    private final SecteurService secteurs;
    private final SectionService sections;

    MobileService(JdbcClient jdbc, Clock horloge, PlanningService planning, RealisationService realisations,
                  SecteurService secteurs, SectionService sections) {
        this.jdbc = jdbc;
        this.horloge = horloge;
        this.planning = planning;
        this.realisations = realisations;
        this.secteurs = secteurs;
        this.sections = sections;
    }

    @Transactional
    Bundle amorcage(Acteur acteur, Integer annee) {
        int an = annee != null ? annee : OffsetDateTime.now(horloge).getYear();
        if (an < 2000 || an > 2100) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "REQUETE_INVALIDE", "Année invalide.");
        }
        AnnualPlan plan = null;
        List<RealisationState> etats = List.of();
        if (planning.aUnPlanning(acteur)) {
            plan = planning.monPlanning(acteur, an);
            etats = jdbc.sql("""
                            select r.id, r.ligne_id, r.statut, r.version
                              from planning.realisation r
                              left join planning.ligne_planning l on l.id = r.ligne_id
                              left join planning.plan_annuel p on p.id = l.plan_id
                             where r.compte_id = :compte and r.supprime_le is null
                               and (p.annee = :annee or (r.ligne_id is null and extract(year from r.debut_le at time zone 'UTC') = :annee))
                             order by r.id
                            """)
                    .param("compte", acteur.compteId()).param("annee", an)
                    .query((rs, n) -> new RealisationState(rs.getObject("id", java.util.UUID.class),
                            rs.getObject("ligne_id", java.util.UUID.class), rs.getString("statut"), rs.getLong("version")))
                    .list();
        }
        long nonLues = jdbc.sql("select count(*) from iam.notification where compte_id = :c and not lue")
                .param("c", acteur.compteId()).query(Long.class).single();
        return new Bundle(OffsetDateTime.now(horloge), an,
                new Me(acteur.compteId(), acteur.matricule(), acteur.profil(), acteur.operateurId()), plan,
                planning.activites(acteur), realisations.criteres(), secteurs.secteurs(acteur), secteurs.lots(acteur),
                sections.sections(acteur), sections.groupes(acteur), etats, nonLues);
    }

    /** Signal de synchronisation : (compte, appareil) créé ou mis à jour. Sert au suivi du chef secteur. */
    @Transactional
    void signaler(Acteur acteur, SyncReport in) {
        jdbc.sql("""
                        insert into iam.appareil (id, compte_id, modele, systeme, version_application, en_attente, derniere_synchro_le)
                        values (:id, :compte, :modele, :systeme, :version, :attente, now())
                        on conflict (compte_id, id) do update
                           set modele = coalesce(excluded.modele, iam.appareil.modele),
                               systeme = coalesce(excluded.systeme, iam.appareil.systeme),
                               version_application = coalesce(excluded.version_application, iam.appareil.version_application),
                               en_attente = excluded.en_attente, derniere_synchro_le = now()
                        """)
                .param("id", in.deviceId()).param("compte", acteur.compteId()).param("modele", in.model())
                .param("systeme", in.system()).param("version", in.appVersion()).param("attente", in.pending())
                .update();
    }
}
