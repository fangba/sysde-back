-- ============================================================================
-- V11 — Sections : description et unicité du nom dans le lot
-- ============================================================================
-- Le chef secteur saisit le nom (jamais généré) : unique dans le lot sans tenir compte de la casse,
-- des accents ni des espaces de bord. Le numéro reste attribué par le serveur (TER-04).
-- Les sections sans nom (valeur par défaut) sont exclues de la règle.
-- ============================================================================
ALTER TABLE referentiel.section ADD COLUMN description varchar(255) NOT NULL DEFAULT '';

CREATE UNIQUE INDEX ux_section_nom ON referentiel.section (lot_id, util.sans_accent(nom))
    WHERE supprime_le IS NULL AND nom <> '';
