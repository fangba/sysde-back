-- ============================================================================
-- V18 — Activités spontanées (réalisation sans ligne de planning)
-- ============================================================================
-- Une activité « non planifiée » (planning.activite.planifiee = false : la découverte du verger, par le moniteur) se fait de
-- façon spontanée : elle n'a pas de ligne dans le planning. Sa réalisation porte alors elle-même l'activité, la plantation
-- visitée et la position (la géolocalisation est l'objet même de l'activité).

ALTER TABLE planning.realisation ADD COLUMN activite_id    uuid REFERENCES planning.activite (id);
ALTER TABLE planning.realisation ADD COLUMN plantation_ref varchar(50);

-- Les réalisations existantes viennent toutes d'une ligne : l'activité est celle de la ligne.
UPDATE planning.realisation r SET activite_id = l.activite_id FROM planning.ligne_planning l WHERE l.id = r.ligne_id;
ALTER TABLE planning.realisation ALTER COLUMN activite_id SET NOT NULL;
ALTER TABLE planning.realisation ALTER COLUMN ligne_id DROP NOT NULL;     -- l'unicité (une réalisation par ligne) reste : NULL n'y compte pas

-- Sans ligne, la réalisation doit dire quelle plantation elle a géolocalisée, et où.
ALTER TABLE planning.realisation ADD CONSTRAINT ck_realisation_spontanee
    CHECK (ligne_id IS NOT NULL OR (plantation_ref IS NOT NULL AND latitude IS NOT NULL));
CREATE INDEX ix_realisation_activite ON planning.realisation (activite_id) WHERE supprime_le IS NULL;

-- Cohérence de l'activité : celle de la ligne si la réalisation vient d'une ligne ; sinon une activité non planifiée, encore
-- proposée, que le profil du compte réalise.
CREATE FUNCTION planning.verifier_realisation() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  v_activite_ligne uuid;
  v_planifiee      boolean;
  v_actif          boolean;
  v_libelle        text;
  v_profil         char(2);
BEGIN
  IF NEW.ligne_id IS NOT NULL THEN
    SELECT l.activite_id INTO v_activite_ligne FROM planning.ligne_planning l WHERE l.id = NEW.ligne_id;
    IF v_activite_ligne IS DISTINCT FROM NEW.activite_id THEN
      RAISE EXCEPTION 'L''activité d''une réalisation est celle de sa ligne de planning.' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
  END IF;
  SELECT a.planifiee, a.actif, a.libelle INTO v_planifiee, v_actif, v_libelle FROM planning.activite a WHERE a.id = NEW.activite_id;
  IF v_planifiee THEN
    RAISE EXCEPTION 'L''activité « % » se planifie : elle ne peut pas être réalisée sans ligne de planning.', v_libelle
      USING ERRCODE = 'check_violation';
  END IF;
  IF NOT v_actif THEN
    RAISE EXCEPTION 'L''activité « % » n''est plus proposée.', v_libelle USING ERRCODE = 'check_violation';
  END IF;
  SELECT c.profil INTO v_profil FROM iam.compte c WHERE c.id = NEW.compte_id;
  IF NOT EXISTS (SELECT 1 FROM planning.activite_profil ap
                  WHERE ap.activite_id = NEW.activite_id AND ap.profil = v_profil AND ap.supprime_le IS NULL) THEN
    RAISE EXCEPTION 'L''activité « % » n''est pas réalisée par le profil %.', v_libelle, v_profil USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER trg_realisation_verifier BEFORE INSERT OR UPDATE OF ligne_id, activite_id ON planning.realisation
    FOR EACH ROW EXECUTE FUNCTION planning.verifier_realisation();
