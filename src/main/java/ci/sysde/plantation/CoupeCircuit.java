package ci.sysde.plantation;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;

import ci.sysde.common.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

/**
 * Coupe-circuit : après {@code echecsMax} échecs consécutifs, les appels échouent tout de suite pendant {@code pause}
 * (on ne charge pas une source déjà en panne et on ne fait pas attendre les utilisateurs). Passé ce délai, un seul appel
 * d'essai est autorisé : s'il réussit, le circuit se referme ; sinon il se rouvre pour une nouvelle pause.
 */
final class CoupeCircuit {

    private static final Logger JOURNAL = LoggerFactory.getLogger(CoupeCircuit.class);

    private enum Etat { FERME, OUVERT, ESSAI }

    private final int echecsMax;
    private final Duration pause;
    private final Clock horloge;
    private Etat etat = Etat.FERME;
    private int echecs;
    private Instant ouvertJusqua = Instant.MIN;

    CoupeCircuit(int echecsMax, Duration pause, Clock horloge) {
        this.echecsMax = echecsMax;
        this.pause = pause;
        this.horloge = horloge;
    }

    <T> T appeler(Supplier<T> appel) {
        autoriser();
        try {
            T resultat = appel.get();
            succes();
            return resultat;
        } catch (RuntimeException e) {
            echec();
            throw e;
        }
    }

    private synchronized void autoriser() {
        Instant maintenant = horloge.instant();
        if (etat == Etat.OUVERT && !maintenant.isBefore(ouvertJusqua)) {
            etat = Etat.ESSAI;                                  // la pause est finie : un appel d'essai passe
            return;
        }
        if (etat == Etat.OUVERT || etat == Etat.ESSAI) {
            long secondes = Math.max(1, Duration.between(maintenant, ouvertJusqua).toSeconds());
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "SOURCE_PLANTATIONS_INDISPONIBLE",
                    "La base des plantations est temporairement coupée après des échecs répétés : réessayez dans "
                            + (etat == Etat.ESSAI ? "quelques secondes." : secondes + " s."));
        }
    }

    private synchronized void succes() {
        if (etat != Etat.FERME) {
            JOURNAL.info("Base des plantations de nouveau joignable : circuit refermé.");
        }
        etat = Etat.FERME;
        echecs = 0;
    }

    private synchronized void echec() {
        echecs++;
        if (etat == Etat.ESSAI || echecs >= echecsMax) {
            if (etat != Etat.OUVERT) {
                JOURNAL.warn("Base des plantations en échec ({} échecs de suite) : circuit ouvert pendant {}.", echecs, pause);
            }
            etat = Etat.OUVERT;
            ouvertJusqua = horloge.instant().plus(pause);
        }
    }

    synchronized boolean ouvert() {
        return etat != Etat.FERME;
    }
}
