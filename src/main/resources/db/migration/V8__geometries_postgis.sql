-- ============================================================================
-- V8 — Géométries (PostGIS)
-- ============================================================================
-- Contours en WGS 84 (SRID 4326), seul système accepté pour l'instant. Le système national
-- (projection TMCI-5.5, ITRF2014 époque 2010.0) pourra s'ajouter quand ses paramètres seront
-- obtenus auprès de l'organisme cartographique : définition dans spatial_ref_sys, puis transformation
-- à l'import vers 4326 (les colonnes ci-dessous restent en 4326). Colonnes facultatives : elles se remplissent
-- quand les contours sont disponibles. Index GiST pour les requêtes spatiales.
-- Les parcelles et planteurs viennent d'une autre base : aucune table ici.
-- ============================================================================

CREATE EXTENSION IF NOT EXISTS postgis;

ALTER TABLE referentiel.secteur ADD COLUMN geom geometry(MultiPolygon, 4326);
ALTER TABLE referentiel.lot     ADD COLUMN geom geometry(MultiPolygon, 4326);
ALTER TABLE referentiel.section ADD COLUMN geom geometry(MultiPolygon, 4326);   -- réservée : pas d'import au jalon 1

CREATE INDEX ix_secteur_geom ON referentiel.secteur USING gist (geom);
CREATE INDEX ix_lot_geom     ON referentiel.lot     USING gist (geom);
CREATE INDEX ix_section_geom ON referentiel.section USING gist (geom);
