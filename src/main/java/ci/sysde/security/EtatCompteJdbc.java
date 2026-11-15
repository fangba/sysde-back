package ci.sysde.security;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Compte actif, utilisateur actif, opérateur actif ; à défaut, compte désactivé depuis peu (envoi seul). Le verrouillage temporaire après échecs de connexion n'en fait
 * pas partie : il empêche de se connecter, pas de poursuivre une session déjà ouverte (sinon quiconque
 * connaît un matricule pourrait en éjecter le titulaire).
 */
@Component
class EtatCompteJdbc implements EtatCompteSource {

    private final JdbcClient jdbc;

    EtatCompteJdbc(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<EtatCompte> charger(UUID compteId) {
        Optional<EtatCompte> actif = lire(compteId, "c.statut = 'ACTIF' and u.statut = 'ACTIF' and o.actif", false);
        if (actif.isPresent()) {
            return actif;
        }
        // Compte désactivé depuis peu : envoi seul (la base décide, iam.compte_peut_synchroniser).
        return lire(compteId, "iam.compte_peut_synchroniser(c.id)", true);
    }

    private Optional<EtatCompte> lire(UUID compteId, String condition, boolean envoiSeul) {
        return jdbc.sql("""
                        select c.matricule, c.profil, c.operateur_id, c.jetons_valides_depuis,
                               coalesce((select array_agg(pp.permission order by pp.permission)
                                           from iam.profil_permission pp where pp.profil = c.profil), '{}') as permissions
                          from iam.compte c
                          join iam.utilisateur u on u.id = c.utilisateur_id
                          join referentiel.operateur o on o.id = c.operateur_id
                         where c.id = :id and c.supprime_le is null and u.supprime_le is null and o.supprime_le is null
                           and\s""" + condition)
                .param("id", compteId)
                .query((rs, n) -> new EtatCompte(compteId, rs.getString("matricule"), rs.getString("profil"),
                        rs.getObject("operateur_id", UUID.class), Set.of((String[]) rs.getArray("permissions").getArray()),
                        Optional.ofNullable(rs.getObject("jetons_valides_depuis", java.time.OffsetDateTime.class))
                                .map(java.time.OffsetDateTime::toInstant).orElse(null), envoiSeul))
                .optional();
    }
}
