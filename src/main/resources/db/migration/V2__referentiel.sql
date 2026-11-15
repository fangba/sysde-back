-- ============================================================================
-- V2 — Référentiel : opérateurs, secteurs, lots, sections
-- ============================================================================
-- Les géométries PostGIS sont ajoutées par V8 (voir ce fichier).
-- ============================================================================

-- Organisme (APROMAC, FIRCA : administration) ou opérateur technique (SAPH, SOGB…).
-- L'acronyme sert de segment du matricule : CS-SAPH-00123. Il est figé dès qu'un compte existe (voir V4).
CREATE TABLE referentiel.operateur (
    id            uuid        PRIMARY KEY DEFAULT util.uuid_v7(),
    type          text        NOT NULL CHECK (type IN ('ORGANISME', 'TECHNIQUE')),
    acronyme      varchar(8)  NOT NULL CHECK (acronyme ~ '^[A-Z0-9]{2,8}$'),
    denomination  varchar(150) NOT NULL,
    adresse       varchar(255),
    email         varchar(150),
    telephone     varchar(30),
    actif         boolean     NOT NULL DEFAULT true,
    cree_le       timestamptz NOT NULL DEFAULT now(),
    cree_par      uuid,
    modifie_le    timestamptz NOT NULL DEFAULT now(),
    modifie_par   uuid,
    supprime_le   timestamptz,
    supprime_par  uuid,
    version       bigint      NOT NULL DEFAULT 0,
    UNIQUE (id, type)                        -- cible de clés étrangères composites
);
CREATE UNIQUE INDEX ux_operateur_acronyme ON referentiel.operateur (acronyme) WHERE supprime_le IS NULL;
SELECT util.attacher_horodatage('referentiel.operateur');

CREATE TABLE referentiel.secteur (
    id            uuid        PRIMARY KEY DEFAULT util.uuid_v7(),
    code          varchar(20) NOT NULL,
    nom           varchar(150) NOT NULL,
    cree_le       timestamptz NOT NULL DEFAULT now(),
    cree_par      uuid,
    modifie_le    timestamptz NOT NULL DEFAULT now(),
    modifie_par   uuid,
    supprime_le   timestamptz,
    supprime_par  uuid,
    version       bigint      NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX ux_secteur_code ON referentiel.secteur (code) WHERE supprime_le IS NULL;
SELECT util.attacher_horodatage('referentiel.secteur');

-- Un secteur compte 3 lots aujourd'hui ; le nombre peut évoluer, il n'est donc pas figé en base.
CREATE TABLE referentiel.lot (
    id            uuid        PRIMARY KEY DEFAULT util.uuid_v7(),
    secteur_id    uuid        NOT NULL REFERENCES referentiel.secteur (id),
    numero        smallint    NOT NULL CHECK (numero > 0),
    description   varchar(255) NOT NULL DEFAULT '',
    cree_le       timestamptz NOT NULL DEFAULT now(),
    cree_par      uuid,
    modifie_le    timestamptz NOT NULL DEFAULT now(),
    modifie_par   uuid,
    supprime_le   timestamptz,
    supprime_par  uuid,
    version       bigint      NOT NULL DEFAULT 0,
    UNIQUE (id, secteur_id)
);
CREATE UNIQUE INDEX ux_lot_numero ON referentiel.lot (secteur_id, numero) WHERE supprime_le IS NULL;
SELECT util.attacher_horodatage('referentiel.lot');

-- Sections : créées par les chefs de secteur (pas par les administrateurs).
-- Le nombre de sections par lot est libre (paramétrable par le métier).
CREATE TABLE referentiel.section (
    id            uuid        PRIMARY KEY DEFAULT util.uuid_v7(),
    lot_id        uuid        NOT NULL REFERENCES referentiel.lot (id),
    numero        integer     NOT NULL CHECK (numero > 0),
    nom           varchar(150) NOT NULL DEFAULT '',
    cree_le       timestamptz NOT NULL DEFAULT now(),
    cree_par      uuid,
    modifie_le    timestamptz NOT NULL DEFAULT now(),
    modifie_par   uuid,
    supprime_le   timestamptz,
    supprime_par  uuid,
    version       bigint      NOT NULL DEFAULT 0,
    UNIQUE (id, lot_id)
);
CREATE UNIQUE INDEX ux_section_numero ON referentiel.section (lot_id, numero) WHERE supprime_le IS NULL;
SELECT util.attacher_horodatage('referentiel.section');

-- ---------------------------------------------------------------------------
-- Import des contours (secteurs, lots, sections) depuis des fichiers shapefile ou GeoJSON.
-- Un import vise UN élément identifié (un secteur, un lot ou une section) : on choisit la cible,
-- puis on dépose son fichier. Si le fichier contient des attributs d'identification (code du
-- secteur, numéro du lot…), ils doivent désigner la cible choisie, sinon l'import est refusé.
-- Le fichier est contrôlé en entier (géométrie valide, coordonnées plausibles, recoupements
-- signalés), puis appliqué sur confirmation. Le fichier d'origine est conservé dans le stockage
-- objet : il sert d'historique des contours (la piste d'audit signale la modification d'un
-- contour sans en copier la géométrie).
-- Seul le WGS 84 (EPSG:4326) est accepté. Pour ajouter plus tard le système national
-- (TMCI-5.5, ITRF2014 époque 2010.0), il faudra en définir les paramètres dans
-- spatial_ref_sys puis élargir la contrainte ck_import_contour_srid.
-- ---------------------------------------------------------------------------
CREATE TABLE referentiel.import_contour (
    id             uuid        PRIMARY KEY DEFAULT util.uuid_v7(),
    -- Contours de secteurs et de lots seulement pour l'instant. Le niveau SECTION est réservé :
    -- la colonne et la cible existent, il suffira d'ajouter 'SECTION' à cette liste.
    niveau         text        NOT NULL CONSTRAINT ck_import_contour_niveau CHECK (niveau IN ('SECTEUR', 'LOT')),
    secteur_id     uuid        REFERENCES referentiel.secteur (id),
    lot_id         uuid        REFERENCES referentiel.lot (id),
    section_id     uuid        REFERENCES referentiel.section (id),
    format         text        NOT NULL CHECK (format IN ('SHAPEFILE', 'GEOJSON')),
    fichier_nom    varchar(255) NOT NULL,
    fichier_cle    varchar(255) NOT NULL,                 -- emplacement dans le stockage objet
    empreinte      char(64)    NOT NULL,                  -- SHA-256 du fichier
    srid           integer     NOT NULL DEFAULT 4326,
    statut         text        NOT NULL DEFAULT 'VALIDATION'
        CHECK (statut IN ('VALIDATION', 'PRET', 'REJETE', 'APPLIQUE', 'ANNULE')),
    nb_elements    integer     NOT NULL DEFAULT 0,        -- polygones contenus dans le fichier
    nb_erreurs     integer     NOT NULL DEFAULT 0,
    rapport        jsonb,                                 -- erreurs et avertissements
    createur_id    uuid,
    cree_le        timestamptz NOT NULL DEFAULT now(),
    termine_le     timestamptz,
    CONSTRAINT ck_import_contour_srid CHECK (srid = 4326),
    -- exactement une cible, du niveau annoncé
    CONSTRAINT ck_import_contour_cible CHECK (
        (niveau = 'SECTEUR' AND secteur_id IS NOT NULL AND lot_id IS NULL     AND section_id IS NULL) OR
        (niveau = 'LOT'     AND lot_id     IS NOT NULL AND secteur_id IS NULL AND section_id IS NULL) OR
        (niveau = 'SECTION' AND section_id IS NOT NULL AND secteur_id IS NULL AND lot_id IS NULL))
);
CREATE INDEX ix_import_contour_date ON referentiel.import_contour (cree_le DESC);
CREATE INDEX ix_import_contour_lot ON referentiel.import_contour (lot_id) WHERE lot_id IS NOT NULL;
