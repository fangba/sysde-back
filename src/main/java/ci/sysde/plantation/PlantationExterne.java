package ci.sysde.plantation;

import java.math.BigDecimal;
import java.util.List;

/**
 * Une plantation telle que la base externe la décrit (lecture seule). {@code ref} est son identifiant chez le tiers :
 * c'est aussi l'identifiant exposé par SysDE et celui qu'on garde dans {@code referentiel.affectation_plantation}.
 * {@code polygone} : sommets {@code [latitude, longitude]} de l'anneau extérieur ; vide si la plantation n'est pas géolocalisée.
 */
record PlantationExterne(String ref, String code, String planteurNom, String planteurTelephone, String village,
                         String operateur, String secteurCode, int lot, String section, BigDecimal superficieHa,
                         Integer anneePlantation, String clonage, Boolean enSaignee, Double latitude, Double longitude,
                         List<double[]> polygone) {
}
