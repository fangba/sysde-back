package ci.sysde.security;

import java.util.Set;
import java.util.UUID;

/**
 * Étendue territoriale d'un compte : ce qu'il a le droit de voir et de toucher.
 *
 * <ul>
 *   <li>{@code NATIONAL} : administrateur et superviseur, tout le territoire ;</li>
 *   <li>{@code OPERATEUR} : chef de département, tout son opérateur ;</li>
 *   <li>{@code AFFECTATIONS} : les autres profils, selon leurs affectations en cours (secteur, lot ou sections).</li>
 * </ul>
 *
 * Un technicien affecté à un lot ou à une section voit aussi son secteur (carte, plantations) :
 * {@link #voitSecteur} est donc plus large que {@link #voitLot} et {@link #voitSection}.
 */
public record Perimetre(
        Etendue etendue,
        UUID operateurId,
        Set<UUID> secteurs,
        Set<UUID> secteursEntiers,
        Set<UUID> lotsEntiers,
        Set<UUID> sections) {

    public enum Etendue { NATIONAL, OPERATEUR, AFFECTATIONS }

    public static Perimetre national(UUID operateurId) {
        return new Perimetre(Etendue.NATIONAL, operateurId, Set.of(), Set.of(), Set.of(), Set.of());
    }

    public static Perimetre operateur(UUID operateurId) {
        return new Perimetre(Etendue.OPERATEUR, operateurId, Set.of(), Set.of(), Set.of(), Set.of());
    }

    public static Perimetre affectations(UUID operateurId, Set<UUID> secteurs, Set<UUID> secteursEntiers,
                                         Set<UUID> lotsEntiers, Set<UUID> sections) {
        return new Perimetre(Etendue.AFFECTATIONS, operateurId, Set.copyOf(secteurs), Set.copyOf(secteursEntiers),
                Set.copyOf(lotsEntiers), Set.copyOf(sections));
    }

    public boolean estNational() {
        return etendue == Etendue.NATIONAL;
    }

    /** Peut-il voir cet opérateur ? Le national, tous ; les autres, le leur seulement. */
    public boolean voitOperateur(UUID operateur) {
        return estNational() || operateurId.equals(operateur);
    }

    /** Secteur entier (ou simple présence d'une affectation dans ce secteur, pour la lecture large). */
    public boolean voitSecteur(UUID operateur, UUID secteur) {
        if (!voitOperateur(operateur)) {
            return false;
        }
        return etendue != Etendue.AFFECTATIONS || secteurs.contains(secteur);
    }

    /** Lot entier : secteur entier, ou lot affecté. Une section seule ne donne pas tout le lot. */
    public boolean voitLot(UUID operateur, UUID secteur, UUID lot) {
        if (!voitOperateur(operateur)) {
            return false;
        }
        return etendue != Etendue.AFFECTATIONS || secteursEntiers.contains(secteur) || lotsEntiers.contains(lot);
    }

    /** Section : secteur entier, lot entier, ou section affectée. */
    public boolean voitSection(UUID operateur, UUID secteur, UUID lot, UUID section) {
        if (!voitOperateur(operateur)) {
            return false;
        }
        return etendue != Etendue.AFFECTATIONS
                || secteursEntiers.contains(secteur) || lotsEntiers.contains(lot) || sections.contains(section);
    }
}
