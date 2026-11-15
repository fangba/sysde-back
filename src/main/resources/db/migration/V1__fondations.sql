-- ============================================================================
-- V1 — Fondations : schémas, rôle applicatif, fonctions utilitaires
-- ============================================================================
-- Conventions communes à tout le modèle :
--   * identifiants UUID v7 (triés par date de création, index compacts)
--   * suppression logique : colonnes supprime_le / supprime_par, unicités partielles
--   * colonnes techniques : cree_le, cree_par, modifie_le, modifie_par, version
--   * l'acteur de la transaction (le COMPTE connecté) est transmis par l'application avec
--       SELECT set_config('app.compte_id', '<uuid du compte>', true);
--     (true = valable pour la transaction seulement, compatible PgBouncer en mode transaction)
-- ============================================================================

CREATE EXTENSION IF NOT EXISTS btree_gist;   -- contraintes d'exclusion (non-chevauchement)

CREATE SCHEMA IF NOT EXISTS util;
CREATE SCHEMA IF NOT EXISTS referentiel;     -- opérateurs, secteurs, lots, sections
CREATE SCHEMA IF NOT EXISTS gestion;         -- contrats, attributions, exercices
CREATE SCHEMA IF NOT EXISTS iam;             -- comptes, postes, permissions, affectations
CREATE SCHEMA IF NOT EXISTS audit;           -- piste d'audit (ajout seul)

-- Rôle utilisé par l'application. Le mot de passe et le droit de connexion
-- sont définis par l'exploitation, jamais dans une migration.
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sysde_app') THEN
    CREATE ROLE sysde_app NOLOGIN;
  END IF;
END $$;

-- ---------------------------------------------------------------------------
-- UUID v7 (RFC 9562) : 48 bits d'horodatage en millisecondes + aléa.
-- PostgreSQL 18 et suivants fournissent uuidv7() en natif : on l'utilise. Avant, on en
-- reconstruit un équivalent (version 7, variante RFC) à partir de gen_random_uuid().
-- ---------------------------------------------------------------------------
DO $$
BEGIN
  IF current_setting('server_version_num')::integer >= 180000 THEN
    EXECUTE $f$CREATE OR REPLACE FUNCTION util.uuid_v7() RETURNS uuid
               LANGUAGE sql VOLATILE PARALLEL SAFE AS 'SELECT uuidv7()'$f$;
  ELSE
    EXECUTE $f$CREATE OR REPLACE FUNCTION util.uuid_v7() RETURNS uuid
               LANGUAGE sql VOLATILE PARALLEL SAFE AS $g$
                 SELECT encode(
                          set_bit(
                            set_bit(
                              overlay(uuid_send(gen_random_uuid())
                                      PLACING substring(int8send(floor(extract(epoch FROM clock_timestamp()) * 1000)::bigint) FROM 3)
                                      FROM 1 FOR 6),
                              52, 1),
                            53, 1),
                          'hex')::uuid
               $g$
               $f$;
  END IF;
END $$;

-- Compte acteur de la transaction courante (NULL pour un traitement système).
CREATE OR REPLACE FUNCTION util.acteur() RETURNS uuid
LANGUAGE sql STABLE AS $$
  SELECT nullif(current_setting('app.compte_id', true), '')::uuid
$$;

-- Renseigne les colonnes techniques ; cree_le / cree_par ne changent jamais.
CREATE OR REPLACE FUNCTION util.horodater() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'INSERT' THEN
    NEW.cree_le := now();
    NEW.cree_par := COALESCE(NEW.cree_par, util.acteur());
  ELSE
    NEW.cree_le := OLD.cree_le;
    NEW.cree_par := OLD.cree_par;
    IF OLD.supprime_le IS NULL AND NEW.supprime_le IS NOT NULL THEN
      NEW.supprime_par := COALESCE(NEW.supprime_par, util.acteur());
    END IF;
  END IF;
  NEW.modifie_le := now();
  NEW.modifie_par := util.acteur();
  RETURN NEW;
END $$;

CREATE OR REPLACE FUNCTION util.attacher_horodatage(p_table regclass) RETURNS void
LANGUAGE plpgsql AS $$
BEGIN
  EXECUTE format(
    'CREATE TRIGGER trg_horodater BEFORE INSERT OR UPDATE ON %s
       FOR EACH ROW EXECUTE FUNCTION util.horodater()', p_table);
END $$;

-- Paramètres modifiables par les administrateurs (ex. durée d'un contrat). Auditée (voir V5).
CREATE TABLE util.parametre (
    cle           varchar(100) PRIMARY KEY CHECK (cle ~ '^[a-z0-9_.]+$'),
    valeur        text        NOT NULL,
    description   varchar(255) NOT NULL DEFAULT '',
    cree_le       timestamptz NOT NULL DEFAULT now(),
    cree_par      uuid,
    modifie_le    timestamptz NOT NULL DEFAULT now(),
    modifie_par   uuid,
    supprime_le   timestamptz,
    supprime_par  uuid,
    version       bigint      NOT NULL DEFAULT 0
);
SELECT util.attacher_horodatage('util.parametre');
