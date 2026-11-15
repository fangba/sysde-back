package ci.sysde.plantation;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Charges utiles des plantations et de la carte (endpoints-back.md §6 et §7). */
public final class PlantationDtos {

    private PlantationDtos() {
    }

    /**
     * Plantation de la base externe, enrichie du territoire SysDE ({@code operator} = titulaire actuel du lot) et de
     * l'affectation à une section ({@code sectionId} et {@code sectionName} nuls si non affectée). {@code section} est le
     * texte brut de la base externe.
     */
    public record Plantation(String id, String code, String planterName, String planterPhone, String village, UUID operatorId,
                             String operator, UUID sectorId, String sectorCode, String sectorName, int lot, String section,
                             UUID sectionId, String sectionName, BigDecimal hectares, Integer plantedYear, @JsonProperty("clone") String cloneName,
                             String stage, boolean geolocated) {
    }

    /** Affectation d'une plantation à une section de son lot ; {@code sectionId} nul retire l'affectation. */
    public record SectionInput(UUID sectionId) {
    }

    public record AssignmentInput(@NotBlank @Size(max = 50) String plantationId, UUID sectionId) {
    }

    public record MapLotOption(int lot, List<String> sections) {
    }

    public record MapSectorOption(UUID id, String code, String name, List<MapLotOption> lots) {
    }

    public record MapOperatorOption(UUID id, String acronym, String name, List<MapSectorOption> sectors) {
    }

    /** {@code polygon} : sommets {@code [latitude, longitude]} dans l'ordre. */
    public record MapPlantation(String id, String name, String owner, UUID operatorId, UUID sectorId, int lot, String section,
                                BigDecimal hectares, List<double[]> polygon) {
    }
}
