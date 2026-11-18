package ci.sysde.planning;

import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ci.sysde.planning.PlanningDtos.Frequency;
import ci.sysde.planning.PlanningDtos.FrequencyCheck;
import ci.sysde.planning.PlanningDtos.PlanActivity;
import ci.sysde.planning.PlanningDtos.PlanLine;

/**
 * Contrôle des dates d'une activité face à sa fréquence (même règle que le front, qui la garde pour la saisie) :
 * <ol>
 *   <li>pour chaque période (année, semestre, trimestre ou mois), le nombre de séances est celui demandé ; quand la fréquence
 *       est « par plantation / groupe / lot… », chaque unité présente dans le planning doit l'atteindre ;</li>
 *   <li>chaque séance dure le nombre de jours de l'activité ;</li>
 *   <li>deux séances d'une même unité ne se chevauchent pas.</li>
 * </ol>
 * Limites assumées : une unité absente du planning n'est pas détectée ; quand la fréquence dépend de la superficie de la
 * plantation (plusieurs tranches, superficie dans la base externe), ou d'une règle en texte, ou d'une période à la semaine,
 * le nombre de séances n'est pas contrôlé (durée et chevauchement le sont toujours).
 */
final class ControleFrequences {

    private static final String[] MOIS = {"janvier", "février", "mars", "avril", "mai", "juin", "juillet", "août",
            "septembre", "octobre", "novembre", "décembre"};

    private ControleFrequences() {
    }

    /** Une période de l'année : libellé (vide pour l'année entière) et mois couverts (1 à 12). */
    private record Periode(String libelle, int premierMois, int dernierMois) {
        boolean contient(int mois) {
            return mois >= premierMois && mois <= dernierMois;
        }
    }

    static FrequencyCheck controler(PlanActivity activite, List<PlanLine> lignes) {
        Frequency frequence = frequenceControlable(activite);
        List<PlanLine> triees = lignes.stream().sorted(Comparator.comparing(PlanLine::date)).toList();
        List<String> problemes = new ArrayList<>();
        String par = frequence != null ? frequence.per() : lierParSeul(activite);

        if (triees.isEmpty()) {
            if (aUneFrequence(activite)) {
                problemes.add("Aucune séance n'est programmée.");
            }
        } else if (frequence != null && frequence.count() != null) {
            List<Periode> periodes = periodes(frequence.period());
            if (periodes != null) {
                Map<String, List<PlanLine>> groupes = regrouper(triees, par);
                for (var groupe : groupes.entrySet()) {
                    for (Periode periode : periodes) {
                        long n = groupe.getValue().stream().filter(l -> periode.contient(l.date().getMonthValue())).count();
                        if (n != frequence.count()) {
                            problemes.add((groupe.getKey().isEmpty() ? "" : groupe.getKey() + " : ")
                                    + pluriel(n, "séance") + " programmée" + (n > 1 ? "s" : "")
                                    + (periode.libelle().isEmpty() ? "" : " " + periode.libelle())
                                    + " pour " + pluriel(frequence.count(), "séance") + " attendue" + (frequence.count() > 1 ? "s" : "") + ".");
                        }
                    }
                }
            }
        }
        for (PlanLine l : triees) {
            long jours = jours(l);
            if (jours != activite.durationDays()) {
                problemes.add("La séance du " + periode(l) + " dure " + pluriel(jours, "jour") + " au lieu de "
                        + pluriel(activite.durationDays(), "jour") + ".");
            }
        }
        Map<String, PlanLine> derniere = new LinkedHashMap<>();
        for (PlanLine l : triees) {
            String cle = cle(l, par);
            PlanLine precedente = derniere.get(cle);
            if (precedente != null && !l.date().isAfter(fin(precedente))) {
                problemes.add("Les séances du " + periode(precedente) + " et du " + periode(l) + " se chevauchent.");
            }
            if (precedente == null || fin(l).isAfter(fin(precedente))) {
                derniere.put(cle, l);
            }
        }
        return new FrequencyCheck(activite.id(), activite.label(), problemes.isEmpty(), attendu(frequence, par), triees.size(), List.copyOf(problemes));
    }

    /** La fréquence qui s'applique à toutes les plantations ; aucune quand elle dépend de la superficie. */
    private static Frequency frequenceControlable(PlanActivity activite) {
        List<Frequency> f = activite.frequencies();
        if (f == null || f.size() != 1) {
            return null;
        }
        Frequency unique = f.get(0);
        return unique.minAreaHa() == null && unique.maxAreaHa() == null ? unique : null;
    }

    private static boolean aUneFrequence(PlanActivity activite) {
        return activite.frequencies() != null && activite.frequencies().stream().anyMatch(f -> f.count() != null);
    }

    /** Sans fréquence unique (tranches de superficie), l'unité du chevauchement reste celle de la règle de l'activité. */
    private static String lierParSeul(PlanActivity activite) {
        return activite.frequencies() == null ? null
                : activite.frequencies().stream().map(Frequency::per).filter(p -> p != null).findFirst().orElse(null);
    }

    private static Integer attendu(Frequency frequence, String par) {
        if (frequence == null || frequence.count() == null || par != null) {
            return null;
        }
        List<Periode> periodes = periodes(frequence.period());
        return periodes == null ? null : frequence.count() * periodes.size();
    }

    private static Map<String, List<PlanLine>> regrouper(List<PlanLine> lignes, String par) {
        Map<String, List<PlanLine>> groupes = new LinkedHashMap<>();
        for (PlanLine l : lignes) {
            groupes.computeIfAbsent(cle(l, par), k -> new ArrayList<>()).add(l);
        }
        return groupes;
    }

    /** L'unité d'une séance : la plantation, le groupe, le lot ou la section, selon le « par » de la fréquence ; vide sans « par ». */
    private static String cle(PlanLine l, String par) {
        if (par == null) {
            return "";
        }
        return switch (par) {
            case "PLANTATION" -> l.plantationRef() == null ? "Sans plantation" : "Plantation " + l.plantationRef();
            case "GROUPE" -> l.groupId() == null ? "Sans groupe" : "Groupe " + l.groupId().toString().substring(0, 8);
            case "LOT" -> l.lotNumber() == null ? "Sans lot" : "Lot " + l.lotNumber();
            default -> lieu(l);
        };
    }

    private static String lieu(PlanLine l) {
        String lot = l.lotNumber() == null ? null : "Lot " + l.lotNumber();
        if (l.sectionName() == null) {
            return lot == null ? "Sans lieu" : lot;
        }
        return lot == null ? "Section " + l.sectionName() : lot + " · Section " + l.sectionName();
    }

    /** Périodes d'une année pour la fréquence ; {@code null} quand elles ne se contrôlent pas (semaine). */
    private static List<Periode> periodes(String periode) {
        if (periode == null) {
            return null;
        }
        return switch (periode) {
            case "ANNEE" -> List.of(new Periode("", 1, 12));
            case "SEMESTRE" -> List.of(new Periode("au 1er semestre", 1, 6), new Periode("au 2e semestre", 7, 12));
            case "TRIMESTRE" -> List.of(new Periode("au 1er trimestre", 1, 3), new Periode("au 2e trimestre", 4, 6),
                    new Periode("au 3e trimestre", 7, 9), new Periode("au 4e trimestre", 10, 12));
            case "MOIS" -> {
                List<Periode> mois = new ArrayList<>();
                for (int m = 1; m <= 12; m++) {
                    mois.add(new Periode("en " + MOIS[m - 1], m, m));
                }
                yield mois;
            }
            default -> null;
        };
    }

    private static java.time.LocalDate fin(PlanLine l) {
        return l.endDate() == null ? l.date() : l.endDate();
    }

    private static long jours(PlanLine l) {
        return ChronoUnit.DAYS.between(l.date(), fin(l)) + 1;
    }

    private static String periode(PlanLine l) {
        return l.endDate() == null || l.endDate().equals(l.date()) ? format(l.date()) : format(l.date()) + " → " + format(l.endDate());
    }

    private static String format(java.time.LocalDate d) {
        return String.format("%02d/%02d/%d", d.getDayOfMonth(), d.getMonthValue(), d.getYear());
    }

    private static String pluriel(long n, String mot) {
        return n + " " + mot + (n > 1 ? "s" : "");
    }
}
