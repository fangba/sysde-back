package ci.sysde.security;

import java.util.Optional;
import java.util.UUID;

/** D'où vient l'état d'un compte. Vide : compte, utilisateur ou opérateur inactif ou supprimé. */
public interface EtatCompteSource {

    Optional<EtatCompte> charger(UUID compteId);
}
