-- ============================================================================
-- V3 — Gestion contractuelle : contrats, attributions, exercices
-- ============================================================================
-- Règles portées par la base :
--   * un contrat appartient à un opérateur TECHNIQUE et enregistre l'ORGANISME qui le mandate ;
--   * deux contrats d'un même opérateur ne se chevauchent pas ;
--   * une attribution par couple contrat / secteur ;
--   * un lot n'appartient qu'à un opérateur sur une période donnée (exclusion
--     sur la période du contrat) ;
--   * un lot d'une attribution appartient au secteur de cette attribution ;
--   * un exercice est une période de 12 mois consécutifs à partir de la date de début du contrat
--     (le dernier peut être plus court si le contrat ne finit pas à un anniversaire).
-- ============================================================================

CREATE TABLE gestion.contrat (
    id             uuid        PRIMARY KEY DEFAULT util.uuid_v7(),
    reference      varchar(50) NOT NULL,
    operateur_id   uuid        NOT NULL,
    operateur_type text        NOT NULL DEFAULT 'TECHNIQUE' CHECK (operateur_type = 'TECHNIQUE'),
    organisme_mandant_id   uuid NOT NULL,     -- l'organisme qui mandate l'opérateur (FIRCA)
    organisme_mandant_type text NOT NULL DEFAULT 'ORGANISME' CHECK (organisme_mandant_type = 'ORGANISME'),
    date_debut     date        NOT NULL,
    date_fin       date        NOT NULL,
    periode        daterange   GENERATED ALWAYS AS (daterange(date_debut, date_fin, '[]')) STORED,
    cree_le        timestamptz NOT NULL DEFAULT now(),
    cree_par       uuid,
    modifie_le     timestamptz NOT NULL DEFAULT now(),
    modifie_par    uuid,
    supprime_le    timestamptz,
    supprime_par   uuid,
    version        bigint      NOT NULL DEFAULT 0,
    CHECK (date_fin > date_debut),
    FOREIGN KEY (operateur_id, operateur_type) REFERENCES referentiel.operateur (id, type),
    FOREIGN KEY (organisme_mandant_id, organisme_mandant_type) REFERENCES referentiel.operateur (id, type),
    UNIQUE (id, operateur_id),
    -- Pas de chevauchement entre contrats d'un même opérateur (contrats actifs seulement).
    CONSTRAINT ex_contrat_chevauchement
        EXCLUDE USING gist (operateur_id WITH =, periode WITH &&) WHERE (supprime_le IS NULL)
);
CREATE UNIQUE INDEX ux_contrat_reference ON gestion.contrat (reference) WHERE supprime_le IS NULL;
SELECT util.attacher_horodatage('gestion.contrat');

-- Attribution : ensemble des lots d'un secteur confiés à un opérateur pour la durée d'un contrat.
CREATE TABLE gestion.attribution (
    id            uuid        PRIMARY KEY DEFAULT util.uuid_v7(),
    contrat_id    uuid        NOT NULL,
    operateur_id  uuid        NOT NULL,     -- recopié du contrat (voir trigger), verrouillé par la clé composite
    secteur_id    uuid        NOT NULL REFERENCES referentiel.secteur (id),
    cree_le       timestamptz NOT NULL DEFAULT now(),
    cree_par      uuid,
    modifie_le    timestamptz NOT NULL DEFAULT now(),
    modifie_par   uuid,
    supprime_le   timestamptz,
    supprime_par  uuid,
    version       bigint      NOT NULL DEFAULT 0,
    FOREIGN KEY (contrat_id, operateur_id) REFERENCES gestion.contrat (id, operateur_id),
    UNIQUE (id, secteur_id),
    UNIQUE (id, contrat_id),
    UNIQUE (id, operateur_id)
);
CREATE UNIQUE INDEX ux_attribution_contrat_secteur
    ON gestion.attribution (contrat_id, secteur_id) WHERE supprime_le IS NULL;
SELECT util.attacher_horodatage('gestion.attribution');

CREATE FUNCTION gestion.renseigner_attribution() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  SELECT c.operateur_id INTO NEW.operateur_id FROM gestion.contrat c WHERE c.id = NEW.contrat_id;
  RETURN NEW;
END $$;
CREATE TRIGGER trg_attribution_operateur BEFORE INSERT OR UPDATE OF contrat_id ON gestion.attribution
    FOR EACH ROW EXECUTE FUNCTION gestion.renseigner_attribution();

-- Lots d'une attribution. Table sans suppression logique : retirer un lot est une
-- suppression réelle, tracée par l'audit (opération D) ; impossible tant que des
-- comptes y sont affectés (déclencheur défini dans V4, iam.refuser_retrait_lot_affecte).
CREATE TABLE gestion.attribution_lot (
    id             uuid        PRIMARY KEY DEFAULT util.uuid_v7(),
    attribution_id uuid        NOT NULL,
    contrat_id     uuid        NOT NULL,
    secteur_id     uuid        NOT NULL,
    lot_id         uuid        NOT NULL,
    periode        daterange   NOT NULL,    -- recopiée du contrat (trigger)
    cree_le        timestamptz NOT NULL DEFAULT now(),
    cree_par       uuid,
    UNIQUE (attribution_id, lot_id),
    FOREIGN KEY (attribution_id, contrat_id) REFERENCES gestion.attribution (id, contrat_id),
    FOREIGN KEY (attribution_id, secteur_id) REFERENCES gestion.attribution (id, secteur_id),
    FOREIGN KEY (lot_id, secteur_id)         REFERENCES referentiel.lot (id, secteur_id),
    -- Un lot ne peut appartenir à deux opérateurs sur des périodes qui se recouvrent.
    CONSTRAINT ex_lot_exclusif EXCLUDE USING gist (lot_id WITH =, periode WITH &&)
);
CREATE INDEX ix_attribution_lot_contrat ON gestion.attribution_lot (contrat_id);

CREATE FUNCTION gestion.renseigner_periode_lot() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  SELECT c.periode INTO NEW.periode FROM gestion.contrat c WHERE c.id = NEW.contrat_id;
  RETURN NEW;
END $$;
CREATE TRIGGER trg_attribution_lot_periode BEFORE INSERT OR UPDATE ON gestion.attribution_lot
    FOR EACH ROW EXECUTE FUNCTION gestion.renseigner_periode_lot();

-- Si les dates d'un contrat changent, la période des lots suit (et l'exclusion est revérifiée).
CREATE FUNCTION gestion.propager_periode_contrat() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.periode IS DISTINCT FROM OLD.periode THEN
    UPDATE gestion.attribution_lot SET periode = NEW.periode WHERE contrat_id = NEW.id;
  END IF;
  RETURN NULL;
END $$;
CREATE TRIGGER trg_contrat_periode AFTER UPDATE ON gestion.contrat
    FOR EACH ROW EXECUTE FUNCTION gestion.propager_periode_contrat();

-- Exercice : une période de 12 mois d'une attribution, comptée à partir du début du contrat.
-- L'exercice n° 1 commence à la date de début du contrat, le n° 2 un an plus tard, etc.
-- Les dates de début et de fin sont calculées ; la clôture est proposée à la fin de l'exercice.
CREATE TABLE gestion.exercice (
    id             uuid        PRIMARY KEY DEFAULT util.uuid_v7(),
    attribution_id uuid        NOT NULL REFERENCES gestion.attribution (id),
    numero         smallint    NOT NULL CHECK (numero BETWEEN 1 AND 50),
    date_debut     date        NOT NULL,
    date_fin       date        NOT NULL,
    date_cloture   date        NOT NULL,
    cree_le        timestamptz NOT NULL DEFAULT now(),
    cree_par       uuid,
    modifie_le     timestamptz NOT NULL DEFAULT now(),
    modifie_par    uuid,
    supprime_le    timestamptz,
    supprime_par   uuid,
    version        bigint      NOT NULL DEFAULT 0,
    CHECK (date_fin >= date_debut),
    UNIQUE (id, attribution_id)
);
CREATE UNIQUE INDEX ux_exercice_numero ON gestion.exercice (attribution_id, numero) WHERE supprime_le IS NULL;
SELECT util.attacher_horodatage('gestion.exercice');

-- Fixe les dates de l'exercice d'après son numéro et le contrat ; refuse un numéro hors contrat.
CREATE FUNCTION gestion.verifier_exercice() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  v_debut date;
  v_fin   date;
BEGIN
  SELECT c.date_debut, c.date_fin INTO v_debut, v_fin
    FROM gestion.attribution a JOIN gestion.contrat c ON c.id = a.contrat_id
   WHERE a.id = NEW.attribution_id;

  NEW.date_debut := (v_debut + make_interval(years => NEW.numero - 1))::date;
  IF NEW.date_debut > v_fin THEN
    RAISE EXCEPTION 'L''exercice n° % n''est pas couvert par le contrat (% au %).', NEW.numero, v_debut, v_fin
      USING ERRCODE = 'check_violation';
  END IF;
  NEW.date_fin := least((v_debut + make_interval(years => NEW.numero))::date - 1, v_fin);
  IF TG_OP = 'INSERT' THEN
    NEW.date_cloture := coalesce(NEW.date_cloture, NEW.date_fin);
  END IF;
  IF NEW.date_cloture < NEW.date_debut THEN
    RAISE EXCEPTION 'La clôture ne peut pas précéder le début de l''exercice n° %.', NEW.numero
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER trg_exercice_verifier BEFORE INSERT OR UPDATE ON gestion.exercice
    FOR EACH ROW EXECUTE FUNCTION gestion.verifier_exercice();

-- Suppression logique d'une attribution : ses lots sont libérés (suppression réelle des
-- lignes de liaison, tracée par l'audit). Refusée si des comptes y sont affectés
-- ou si des exercices existent encore.
CREATE FUNCTION gestion.liberer_lots_attribution() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.supprime_le IS NULL AND NEW.supprime_le IS NOT NULL THEN
    IF EXISTS (SELECT 1 FROM gestion.exercice e WHERE e.attribution_id = NEW.id AND e.supprime_le IS NULL) THEN
      RAISE EXCEPTION 'Suppression impossible : des exercices sont rattachés à cette attribution.'
        USING ERRCODE = 'restrict_violation';
    END IF;
    DELETE FROM gestion.attribution_lot WHERE attribution_id = NEW.id;
  END IF;
  RETURN NULL;
END $$;
CREATE TRIGGER trg_attribution_liberer AFTER UPDATE OF supprime_le ON gestion.attribution
    FOR EACH ROW EXECUTE FUNCTION gestion.liberer_lots_attribution();

-- Un contrat portant encore des attributions actives ne peut pas être supprimé.
CREATE FUNCTION gestion.verifier_suppression_contrat() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.supprime_le IS NULL AND NEW.supprime_le IS NOT NULL
     AND EXISTS (SELECT 1 FROM gestion.attribution a WHERE a.contrat_id = NEW.id AND a.supprime_le IS NULL) THEN
    RAISE EXCEPTION 'Suppression impossible : le contrat a encore des attributions.'
      USING ERRCODE = 'restrict_violation';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER trg_contrat_suppression BEFORE UPDATE OF supprime_le ON gestion.contrat
    FOR EACH ROW EXECUTE FUNCTION gestion.verifier_suppression_contrat();

-- ---------------------------------------------------------------------------
-- Retrait d'un lot en cours de contrat
-- ---------------------------------------------------------------------------
-- Règles : on peut ajouter un lot libre ; on peut retirer un lot s'il n'a plus d'agent affecté
-- (déclencheur iam.refuser_retrait_lot_affecte, V4) et avec un motif obligatoire ; un lot retiré ne peut pas
-- passer à un autre opérateur avant la fin du contrat dont il a été retiré.
CREATE TABLE gestion.retrait_lot (
    id               uuid        PRIMARY KEY DEFAULT util.uuid_v7(),
    lot_id           uuid        NOT NULL REFERENCES referentiel.lot (id),
    secteur_id       uuid        NOT NULL,
    attribution_id   uuid        NOT NULL REFERENCES gestion.attribution (id),
    contrat_id       uuid        NOT NULL,
    operateur_id     uuid        NOT NULL,             -- opérateur qui perd le lot
    motif            text        NOT NULL CHECK (length(btrim(motif)) > 0),
    retire_le        timestamptz NOT NULL DEFAULT now(),
    retire_par       uuid        DEFAULT util.acteur(),
    reserve_jusqu_au date        NOT NULL              -- fin du contrat
);
CREATE INDEX ix_retrait_lot_lot ON gestion.retrait_lot (lot_id);

CREATE FUNCTION gestion.tracer_retrait_lot() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  v_motif     text := btrim(coalesce(current_setting('app.motif', true), ''));
  v_operateur uuid;
  v_fin       date;
BEGIN
  IF v_motif = '' THEN
    RAISE EXCEPTION 'Un motif est obligatoire pour retirer un lot d''une attribution.'
      USING ERRCODE = 'check_violation';
  END IF;
  SELECT c.operateur_id, c.date_fin INTO v_operateur, v_fin FROM gestion.contrat c WHERE c.id = OLD.contrat_id;
  INSERT INTO gestion.retrait_lot (lot_id, secteur_id, attribution_id, contrat_id, operateur_id, motif, reserve_jusqu_au)
  VALUES (OLD.lot_id, OLD.secteur_id, OLD.attribution_id, OLD.contrat_id, v_operateur, v_motif, v_fin);
  RETURN OLD;
END $$;
CREATE TRIGGER trg_attribution_lot_retrait BEFORE DELETE ON gestion.attribution_lot
    FOR EACH ROW EXECUTE FUNCTION gestion.tracer_retrait_lot();

CREATE FUNCTION gestion.verifier_reserve_lot() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  v_operateur uuid;
BEGIN
  SELECT c.operateur_id INTO v_operateur FROM gestion.contrat c WHERE c.id = NEW.contrat_id;
  IF EXISTS (SELECT 1 FROM gestion.retrait_lot r
              WHERE r.lot_id = NEW.lot_id
                AND r.operateur_id <> v_operateur
                AND daterange(r.retire_le::date, r.reserve_jusqu_au, '[]') && NEW.periode) THEN
    RAISE EXCEPTION 'Ce lot a été retiré d''un contrat encore en cours : il ne peut changer d''opérateur qu''à la fin de ce contrat.'
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER trg_attribution_lot_reserve BEFORE INSERT ON gestion.attribution_lot
    FOR EACH ROW EXECUTE FUNCTION gestion.verifier_reserve_lot();

-- La suppression d'une attribution libère ses lots ; le motif est celui de la suppression.
CREATE OR REPLACE FUNCTION gestion.liberer_lots_attribution() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.supprime_le IS NULL AND NEW.supprime_le IS NOT NULL THEN
    IF EXISTS (SELECT 1 FROM gestion.exercice e WHERE e.attribution_id = NEW.id AND e.supprime_le IS NULL) THEN
      RAISE EXCEPTION 'Suppression impossible : des exercices sont rattachés à cette attribution.'
        USING ERRCODE = 'restrict_violation';
    END IF;
    PERFORM set_config('app.motif',
      COALESCE(nullif(btrim(current_setting('app.motif', true)), ''), 'Suppression de l''attribution'), true);
    DELETE FROM gestion.attribution_lot WHERE attribution_id = NEW.id;
  END IF;
  RETURN NULL;
END $$;

-- ---------------------------------------------------------------------------
-- Génération automatique des exercices
-- ---------------------------------------------------------------------------
-- Une attribution reçoit un exercice par période de 12 mois couverte par son contrat, clôturé à
-- la fin de la période (modifiable ensuite). Si le contrat change de dates, les périodes sont
-- recalculées (la clôture suit la fin quand elle n'avait pas été modifiée) et les exercices
-- manquants sont ajoutés.
CREATE FUNCTION gestion.generer_exercices(p_attribution uuid) RETURNS void
LANGUAGE plpgsql AS $$
DECLARE
  v_debut date;
  v_fin   date;
BEGIN
  SELECT c.date_debut, c.date_fin INTO v_debut, v_fin
    FROM gestion.attribution a JOIN gestion.contrat c ON c.id = a.contrat_id
   WHERE a.id = p_attribution AND a.supprime_le IS NULL;
  IF NOT FOUND THEN RETURN; END IF;

  -- exercices existants : dates recalculées, clôture alignée si elle suivait la fin
  UPDATE gestion.exercice e
     SET date_cloture = CASE WHEN e.date_cloture = e.date_fin
                             THEN least((v_debut + make_interval(years => e.numero))::date - 1, v_fin)
                             ELSE e.date_cloture END
   WHERE e.attribution_id = p_attribution AND e.supprime_le IS NULL;

  INSERT INTO gestion.exercice (attribution_id, numero)
  SELECT p_attribution, n
    FROM generate_series(1, 50) n
   WHERE (v_debut + make_interval(years => n - 1))::date <= v_fin
     AND NOT EXISTS (SELECT 1 FROM gestion.exercice e
                      WHERE e.attribution_id = p_attribution AND e.numero = n AND e.supprime_le IS NULL);
END $$;

CREATE FUNCTION gestion.generer_exercices_attribution() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  PERFORM gestion.generer_exercices(NEW.id);
  RETURN NULL;
END $$;
CREATE TRIGGER trg_attribution_exercices AFTER INSERT ON gestion.attribution
    FOR EACH ROW EXECUTE FUNCTION gestion.generer_exercices_attribution();

CREATE FUNCTION gestion.generer_exercices_contrat() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE v_attribution uuid;
BEGIN
  IF NEW.date_debut IS DISTINCT FROM OLD.date_debut OR NEW.date_fin IS DISTINCT FROM OLD.date_fin THEN
    FOR v_attribution IN SELECT a.id FROM gestion.attribution a WHERE a.contrat_id = NEW.id AND a.supprime_le IS NULL LOOP
      PERFORM gestion.generer_exercices(v_attribution);
    END LOOP;
  END IF;
  RETURN NULL;
END $$;
CREATE TRIGGER trg_contrat_exercices AFTER UPDATE OF date_debut, date_fin ON gestion.contrat
    FOR EACH ROW EXECUTE FUNCTION gestion.generer_exercices_contrat();
