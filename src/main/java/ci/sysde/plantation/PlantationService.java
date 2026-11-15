package ci.sysde.plantation;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import ci.sysde.common.BusinessException;
import ci.sysde.plantation.PlantationDtos.AssignmentInput;
import ci.sysde.plantation.PlantationDtos.Plantation;
import ci.sysde.plantation.Territoire.LotInfo;
import ci.sysde.security.Acteur;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Plantations de la base externe, filtrées par le territoire que l'appelant voit et fusionnées avec l'affectation à une
 * section faite dans SysDE (le chef secteur, seul, l'écrit). SysDE ne crée ni ne modifie jamais une plantation.
 */
@Service
class PlantationService {

    static final int MAX = 20_000;

    /** Plantation externe, son lot SysDE et son affectation éventuelle. */
    record Vue(PlantationExterne p, LotInfo lot, UUID sectionId, String sectionNom) {
    }

    private final SourcePlantations source;
    private final Territoire territoire;
    private final JdbcClient jdbc;
    private final AffectationsPlantations affectations;

    PlantationService(SourcePlantations source, Territoire territoire, JdbcClient jdbc, AffectationsPlantations affectations) {
        this.source = source;
        this.territoire = territoire;
        this.jdbc = jdbc;
        this.affectations = affectations;
    }

    @Transactional(readOnly = true)
    List<Plantation> lister(Acteur acteur, String q, Integer lot, UUID secteurId) {
        return vues(acteur, secteurId, null, lot, q).stream().map(PlantationService::dto).toList();
    }

    /** Plantations visibles, avec leur affectation. {@code operateurId}, {@code secteurId} et {@code lot} affinent. */
    List<Vue> vues(Acteur acteur, UUID secteurId, UUID operateurId, Integer lot, String q) {
        List<LotInfo> visibles = territoire.visibles(acteur).stream()
                .filter(l -> secteurId == null || l.secteurId().equals(secteurId))
                .filter(l -> operateurId == null || operateurId.equals(l.operateurId()))
                .filter(l -> lot == null || l.numero() == lot)
                .toList();
        if (visibles.isEmpty()) {
            return List.of();
        }
        Set<String> codes = visibles.stream().map(LotInfo::secteurCode).collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, LotInfo> parCle = Territoire.parCle(visibles);
        String filtre = q == null || q.isBlank() ? null : q.strip().toLowerCase(Locale.ROOT);
        Map<String, String[]> affectees = affectationsOuvertes();
        List<Vue> sortie = new java.util.ArrayList<>();
        for (PlantationExterne p : source.lister(codes)) {
            LotInfo l = parCle.get(LotInfo.cle(p.secteurCode(), p.lot()));
            if (l == null || (filtre != null && !contient(p, filtre))) {
                continue;
            }
            String[] a = affectees.get(p.ref());
            sortie.add(new Vue(p, l, a == null ? null : UUID.fromString(a[0]), a == null ? null : a[1]));
            if (sortie.size() > MAX) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "REQUETE_INVALIDE",
                        "Trop de plantations (plus de " + MAX + ") : filtrez par secteur, lot ou recherche.");
            }
        }
        return sortie;
    }

    /** Une plantation par son identifiant, dans le territoire visible ; 404 sinon. */
    @Transactional(readOnly = true)
    Plantation lire(Acteur acteur, String ref) {
        PlantationExterne p = source.trouver(ref).orElseThrow(PlantationService::introuvable);
        LotInfo lot = Territoire.parCle(territoire.visibles(acteur)).get(LotInfo.cle(p.secteurCode(), p.lot()));
        if (lot == null) {
            throw introuvable();
        }
        String[] a = affectationsOuvertes().get(ref);
        return dto(new Vue(p, lot, a == null ? null : UUID.fromString(a[0]), a == null ? null : a[1]));
    }

    /** {@code PUT /plantations/{id}/section} : un cas particulier du lot. */
    List<Plantation> affecter(Acteur acteur, List<AssignmentInput> demandes) {
        if (!acteur.estProfil(Acteur.CHEF_SECTEUR)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ACCES_REFUSE", "Seul le chef secteur affecte les plantations aux sections.");
        }
        if (demandes.stream().map(AssignmentInput::plantationId).distinct().count() != demandes.size()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "REQUETE_INVALIDE", "Une plantation figure plusieurs fois dans la demande.");
        }
        var perimetre = territoire.perimetre(acteur);
        Map<String, LotInfo> lots = Territoire.parCle(territoire.visibles(acteur));
        Map<String, Vue> cibles = new HashMap<>();
        // Tout est vérifié avant la moindre écriture : un lot de demandes est accepté ou refusé en bloc.
        for (AssignmentInput d : demandes) {
            PlantationExterne p = source.trouver(d.plantationId())
                    .orElseThrow(() -> introuvable());
            LotInfo lot = lots.get(LotInfo.cle(p.secteurCode(), p.lot()));
            if (lot == null || lot.operateurId() == null || !perimetre.voitLot(lot.operateurId(), lot.secteurId(), lot.id())) {
                throw introuvable();
            }
            if (d.sectionId() != null) {
                UUID lotDeLaSection = jdbc.sql("select lot_id from referentiel.section where id = :id and supprime_le is null")
                        .param("id", d.sectionId()).query(UUID.class).optional()
                        .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST, "REQUETE_INVALIDE", "Section inconnue."));
                if (!lotDeLaSection.equals(lot.id())) {
                    throw new BusinessException(HttpStatus.BAD_REQUEST, "REQUETE_INVALIDE",
                            "La section doit appartenir au lot de la plantation (lot " + lot.numero() + ").");
                }
            }
            cibles.put(d.plantationId(), new Vue(p, lot, null, null));
        }
        List<String> modifiees = affectations.appliquer(demandes.stream()
                .map(d -> new AffectationsPlantations.Demande(d.plantationId(), cibles.get(d.plantationId()).lot().id(), d.sectionId()))
                .toList());
        Map<String, String[]> apres = affectationsOuvertes();
        return modifiees.stream().map(ref -> {
            Vue v = cibles.get(ref);
            String[] a = apres.get(ref);
            return dto(new Vue(v.p(), v.lot(), a == null ? null : UUID.fromString(a[0]), a == null ? null : a[1]));
        }).toList();
    }

    /** plantation → [id de section, nom de section] pour les affectations en cours. */
    private Map<String, String[]> affectationsOuvertes() {
        Map<String, String[]> par = new HashMap<>();
        jdbc.sql("""
                        select a.plantation_ref, s.id, s.nom from referentiel.affectation_plantation a
                          join referentiel.section s on s.id = a.section_id
                         where a.fin is null and a.supprime_le is null
                        """)
                .query((rs, n) -> {
                    par.put(rs.getString(1), new String[] {rs.getObject(2, UUID.class).toString(), rs.getString(3)});
                    return 0;
                }).list();
        return par;
    }

    private static boolean contient(PlantationExterne p, String filtre) {
        return Stream.of(p.code(), p.planteurNom(), p.village()).anyMatch(t -> t != null && t.toLowerCase(Locale.ROOT).contains(filtre));
    }

    static Plantation dto(Vue v) {
        PlantationExterne p = v.p();
        LotInfo l = v.lot();
        return new Plantation(p.ref(), p.code(), p.planteurNom(), p.planteurTelephone(), p.village(), l.operateurId(), l.operateur(),
                l.secteurId(), l.secteurCode(), l.secteurNom(), l.numero(), p.section(), v.sectionId(), v.sectionNom(),
                p.superficieHa(), p.anneePlantation(), p.clonage(), Boolean.TRUE.equals(p.enSaignee()) ? "SAIGNEE" : "JEUNE",
                p.latitude() != null && p.longitude() != null || !p.polygone().isEmpty());
    }

    private static BusinessException introuvable() {
        return new BusinessException(HttpStatus.NOT_FOUND, "INTROUVABLE", "Plantation introuvable.");
    }
}
