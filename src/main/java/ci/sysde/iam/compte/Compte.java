package ci.sysde.iam.compte;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.type.SqlTypes;

/**
 * Compte (table {@code iam.compte}). Seules les colonnes utiles à la connexion sont mappées ; la création et
 * la modification des comptes (profil, matricule, statut) viendront avec le module comptes.
 * Les triggers de la base font foi pour les règles de gestion.
 */
@Entity
@Table(schema = "iam", name = "compte")
@SQLRestriction("supprime_le is null")
@DynamicUpdate
public class Compte {

    @Id
    private UUID id;

    @Column(name = "utilisateur_id", nullable = false, updatable = false)
    private UUID utilisateurId;

    @Column(name = "operateur_id", nullable = false, updatable = false)
    private UUID operateurId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "profil", nullable = false, length = 2, columnDefinition = "char(2)", updatable = false)
    private String profil;

    @Column(name = "matricule", nullable = false, updatable = false)
    private String matricule;

    @Column(name = "statut", nullable = false, updatable = false)
    private String statut;

    @Column(name = "mot_de_passe_hash")
    private String motDePasseHash;

    @Column(name = "doit_changer_mot_de_passe", nullable = false)
    private boolean doitChangerMotDePasse;

    @Column(name = "echecs_connexion", nullable = false)
    private short echecsConnexion;

    @Column(name = "verrouille_jusqu_a")
    private Instant verrouilleJusquA;

    @Column(name = "derniere_connexion")
    private Instant derniereConnexion;

    @Version
    private long version;

    protected Compte() {
    }

    public boolean estVerrouille(Instant maintenant) {
        return verrouilleJusquA != null && verrouilleJusquA.isAfter(maintenant);
    }

    public boolean aUnMotDePasse() {
        return motDePasseHash != null && !motDePasseHash.isBlank();
    }

    /**
     * Enregistre un échec de connexion ; au-delà de {@code echecsMax} échecs consécutifs, le compte est
     * verrouillé pour {@code duree} et le compteur repart de zéro.
     *
     * @return vrai si ce dernier échec vient de verrouiller le compte
     */
    public boolean enregistrerEchec(int echecsMax, Duration duree, Instant maintenant) {
        echecsConnexion++;
        if (echecsConnexion >= echecsMax) {
            verrouilleJusquA = maintenant.plus(duree);
            echecsConnexion = 0;
            return true;
        }
        return false;
    }

    public void enregistrerSucces(Instant maintenant) {
        echecsConnexion = 0;
        verrouilleJusquA = null;
        derniereConnexion = maintenant;
    }

    public void definirMotDePasse(String hash) {
        this.motDePasseHash = hash;
        this.doitChangerMotDePasse = false;
    }

    public UUID id() {
        return id;
    }

    public UUID utilisateurId() {
        return utilisateurId;
    }

    public UUID operateurId() {
        return operateurId;
    }

    public String profil() {
        return profil;
    }

    public String matricule() {
        return matricule;
    }

    public String statut() {
        return statut;
    }

    public String motDePasseHash() {
        return motDePasseHash;
    }

    public boolean doitChangerMotDePasse() {
        return doitChangerMotDePasse;
    }

    public short echecsConnexion() {
        return echecsConnexion;
    }

    public Instant verrouilleJusquA() {
        return verrouilleJusquA;
    }
}
