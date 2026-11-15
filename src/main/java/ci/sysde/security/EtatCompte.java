package ci.sysde.security;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * L'état courant d'un compte actif, tel que la base le dit (et non tel que le jeton l'affirme).
 * {@code jetonsValidesDepuis} : tout jeton émis avant cet instant est refusé (nul : pas de coupure).
 * {@code envoiSeul} : compte désactivé depuis moins de {@code compte.delai_synchronisation_jours} jours, qui ne peut
 * plus qu'envoyer ce qu'il a saisi hors ligne (ses permissions sont alors réduites à l'envoi).
 */
public record EtatCompte(UUID compteId, String matricule, String profil, UUID operateurId, Set<String> permissions,
                         Instant jetonsValidesDepuis, boolean envoiSeul) {

    public EtatCompte(UUID compteId, String matricule, String profil, UUID operateurId, Set<String> permissions,
                      Instant jetonsValidesDepuis) {
        this(compteId, matricule, profil, operateurId, permissions, jetonsValidesDepuis, false);
    }
}
