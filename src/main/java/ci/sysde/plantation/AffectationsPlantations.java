package ci.sysde.plantation;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Écriture des affectations plantation → section (tout ou rien, une seule transaction). L'historique est conservé. */
@Service
class AffectationsPlantations {

    record Demande(String plantationRef, UUID lotId, UUID sectionId) {
    }

    private final JdbcClient jdbc;

    AffectationsPlantations(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Renvoie les références dont l'affectation a réellement changé. */
    @Transactional
    List<String> appliquer(List<Demande> demandes) {
        List<String> changees = new ArrayList<>();
        for (Demande d : demandes) {
            UUID courante = jdbc.sql("""
                            select section_id from referentiel.affectation_plantation
                             where plantation_ref = :ref and fin is null and supprime_le is null for update
                            """)
                    .param("ref", d.plantationRef()).query(UUID.class).optional().orElse(null);
            if (java.util.Objects.equals(courante, d.sectionId())) {
                continue;
            }
            if (courante != null) {
                jdbc.sql("update referentiel.affectation_plantation set fin = current_date where plantation_ref = :ref and fin is null and supprime_le is null")
                        .param("ref", d.plantationRef()).update();
            }
            if (d.sectionId() != null) {
                jdbc.sql("insert into referentiel.affectation_plantation (plantation_ref, lot_id, section_id) values (:ref, :lot, :section)")
                        .param("ref", d.plantationRef()).param("lot", d.lotId()).param("section", d.sectionId()).update();
            }
            changees.add(d.plantationRef());
        }
        return changees;
    }
}
