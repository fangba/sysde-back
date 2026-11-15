package ci.sysde.iam.compte;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CompteRepository extends JpaRepository<Compte, UUID> {

    Optional<Compte> findByMatricule(String matricule);
}
