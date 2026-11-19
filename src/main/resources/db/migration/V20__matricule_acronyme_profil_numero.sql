-- ============================================================================
-- V20 — Matricule = ACRONYME-PROFIL-NUMÉRO (ex. SAPH-CS-00001)
-- ============================================================================
-- Avant : ACRONYME-NUMÉRO (SAPH-00348), stable quel que soit le profil, la « référence du compte » (profil + matricule)
-- portant le profil. Désormais le profil est dans le matricule :
--   * le numéro reste attribué par organisation (5 à 7 chiffres, unique dans l'organisation, jamais réutilisé) ;
--   * le matricule est recalculé quand le profil change (promotion, rétrogradation) ; l'ancien est conservé en alias
--     (iam.alias_matricule, avec le motif) : il n'ouvre plus de session, et son numéro n'est pas réattribué ;
--   * la « référence du compte » reste dans les échanges (champ `reference`) mais devient identique au matricule.
-- Les comptes existants sont convertis, leur ancien matricule conservé en alias.

-- 1. Colonnes : le matricule peut atteindre 8 + 1 + 2 + 1 + 7 = 19 caractères. La contrainte et la référence calculée
--    sont reposées après la conversion des comptes existants (étape 3).
ALTER TABLE iam.compte DROP COLUMN reference_compte;
ALTER TABLE iam.compte DROP CONSTRAINT compte_matricule_check;
ALTER TABLE iam.compte ALTER COLUMN matricule TYPE varchar(24);
ALTER TABLE iam.alias_matricule ALTER COLUMN ancien_matricule TYPE varchar(24);

-- 2. Règles de la base : création, correction du numéro et changement de profil.
CREATE OR REPLACE FUNCTION iam.avant_creation_compte() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  v_type_operateur text;
  v_acronyme       text;
  v_organisme      boolean;
  v_createur       char(2);
  v_acteur         uuid := util.acteur();
  a                iam.compte;
BEGIN
  SELECT o.type, o.acronyme INTO v_type_operateur, v_acronyme
    FROM referentiel.operateur o WHERE o.id = NEW.operateur_id;
  SELECT p.organisme, p.createur_profil INTO v_organisme, v_createur
    FROM iam.profil p WHERE p.code = NEW.profil;

  IF v_organisme AND v_type_operateur <> 'ORGANISME' THEN
    RAISE EXCEPTION 'Le profil % est réservé aux organismes (APROMAC, FIRCA).', NEW.profil
      USING ERRCODE = 'check_violation';
  END IF;
  IF NOT v_organisme AND v_type_operateur <> 'TECHNIQUE' THEN
    RAISE EXCEPTION 'Le profil % est réservé aux opérateurs techniques.', NEW.profil
      USING ERRCODE = 'check_violation';
  END IF;
  IF NEW.statut <> 'DESACTIVE'
     AND EXISTS (SELECT 1 FROM iam.utilisateur u WHERE u.id = NEW.utilisateur_id AND u.statut = 'DESACTIVE') THEN
    RAISE EXCEPTION 'L''utilisateur est désactivé : aucun compte actif ne peut lui être rattaché.'
      USING ERRCODE = 'check_violation';
  END IF;

  IF v_acteur IS NULL THEN
    IF NEW.profil <> 'AD' THEN
      RAISE EXCEPTION 'Création du profil % impossible sans compte connecté.', NEW.profil
        USING ERRCODE = 'insufficient_privilege';
    END IF;
  ELSE
    SELECT * INTO a FROM iam.compte c WHERE c.id = v_acteur AND c.supprime_le IS NULL;
    IF NOT FOUND OR a.statut <> 'ACTIF' THEN
      RAISE EXCEPTION 'Le compte connecté n''est pas actif.' USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF a.profil IS DISTINCT FROM v_createur THEN
      RAISE EXCEPTION 'Un profil % ne peut pas créer un profil % (hiérarchie : %).',
        a.profil, NEW.profil, COALESCE(v_createur, 'aucun')
        USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF NOT iam.a_permission(a.profil, 'CREER_COMPTE') THEN
      RAISE EXCEPTION 'Le profil % n''a pas la permission de créer des comptes.', a.profil
        USING ERRCODE = 'insufficient_privilege';
    END IF;
    -- Isolation APROMAC / FIRCA, et un chef ne crée que dans son propre opérateur.
    IF NEW.profil <> 'CD' AND NEW.operateur_id <> a.operateur_id THEN
      RAISE EXCEPTION 'Création refusée : le compte doit appartenir à la même organisation que son créateur.'
        USING ERRCODE = 'insufficient_privilege';
    END IF;
  END IF;

  NEW.numero := iam.reserver_numero(NEW.operateur_id, NEW.numero);
  IF EXISTS (SELECT 1 FROM iam.alias_matricule am
              WHERE am.operateur_id = NEW.operateur_id AND am.ancien_numero = NEW.numero) THEN
    RAISE EXCEPTION 'Le numéro % est déjà utilisé par un ancien matricule de l''organisation.', NEW.numero
      USING ERRCODE = 'unique_violation';
  END IF;
  NEW.matricule := v_acronyme || '-' || NEW.profil || '-' || NEW.numero;
  IF NEW.statut = 'DESACTIVE' THEN
    NEW.desactive_le := now();
  END IF;
  RETURN NEW;
END $$;

CREATE OR REPLACE FUNCTION iam.avant_modification_compte() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  v_acteur   uuid := util.acteur();
  v_profil_a char(2);
  v_motif    text := btrim(coalesce(current_setting('app.motif', true), ''));
  v_acronyme text;
  v_org_old  boolean;
  v_org_new  boolean;
BEGIN
  IF NEW.utilisateur_id <> OLD.utilisateur_id THEN
    RAISE EXCEPTION 'Un compte reste attaché à son utilisateur : créer un nouveau compte pour une autre personne.'
      USING ERRCODE = 'restrict_violation';
  END IF;
  IF NEW.operateur_id <> OLD.operateur_id THEN
    RAISE EXCEPTION 'Un compte ne change pas d''organisation.' USING ERRCODE = 'restrict_violation';
  END IF;

  -- Correction du numéro (donc du matricule) : administrateur seulement, motif obligatoire.
  IF NEW.numero IS DISTINCT FROM OLD.numero THEN
    SELECT c.profil INTO v_profil_a FROM iam.compte c WHERE c.id = v_acteur AND c.statut = 'ACTIF';
    IF v_profil_a IS DISTINCT FROM 'AD' THEN
      RAISE EXCEPTION 'Seul un administrateur peut corriger un matricule.' USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF v_motif = '' THEN
      RAISE EXCEPTION 'Le motif est obligatoire pour corriger un matricule.' USING ERRCODE = 'check_violation';
    END IF;
    NEW.numero := iam.reserver_numero(NEW.operateur_id, NEW.numero);
    IF EXISTS (SELECT 1 FROM iam.alias_matricule am
                WHERE am.operateur_id = NEW.operateur_id AND am.ancien_numero = NEW.numero
                  AND am.compte_id <> NEW.id) THEN
      RAISE EXCEPTION 'Le numéro % est déjà utilisé par un ancien matricule de l''organisation.', NEW.numero
        USING ERRCODE = 'unique_violation';
    END IF;
    SELECT o.acronyme INTO v_acronyme FROM referentiel.operateur o WHERE o.id = NEW.operateur_id;
    NEW.matricule := v_acronyme || '-' || NEW.profil || '-' || NEW.numero;
    INSERT INTO iam.alias_matricule (compte_id, operateur_id, ancien_numero, ancien_matricule, motif, corrige_par)
    VALUES (OLD.id, OLD.operateur_id, OLD.numero, OLD.matricule, v_motif, v_acteur);
  ELSE
    NEW.matricule := OLD.matricule;
  END IF;

  -- Changement de profil : promotion, rétrogradation ou mouvement latéral.
  IF NEW.profil <> OLD.profil THEN
    IF v_motif = '' THEN
      RAISE EXCEPTION 'Le motif est obligatoire pour changer le profil d''un compte.' USING ERRCODE = 'check_violation';
    END IF;
    SELECT p.organisme INTO v_org_old FROM iam.profil p WHERE p.code = OLD.profil;
    SELECT p.organisme INTO v_org_new FROM iam.profil p WHERE p.code = NEW.profil;
    IF v_org_old <> v_org_new THEN
      RAISE EXCEPTION 'Un profil d''organisme et un profil d''opérateur ne sont pas interchangeables.'
        USING ERRCODE = 'check_violation';
    END IF;
    IF v_acteur IS NULL OR NOT iam.acteur_autorise('CHANGER_PROFIL', OLD.id, ARRAY[OLD.profil, NEW.profil]) THEN
      RAISE EXCEPTION 'Changement de profil refusé : le compte connecté n''a pas l''autorité sur les profils % et %.',
        OLD.profil, NEW.profil USING ERRCODE = 'insufficient_privilege';
    END IF;
    -- Les affectations sont closes ; les activités en cours restent au compte.
    UPDATE iam.affectation SET fin = current_date
     WHERE compte_id = OLD.id AND fin IS NULL AND supprime_le IS NULL;
    UPDATE iam.compte_profil SET fin = clock_timestamp() WHERE compte_id = OLD.id AND fin IS NULL;
    INSERT INTO iam.compte_profil (compte_id, profil, motif) VALUES (OLD.id, NEW.profil, v_motif);
    -- Le profil fait partie du matricule : il change avec lui, et l'ancien est conservé en alias avec le motif du changement
    -- (si le numéro a aussi été corrigé dans la même modification, l'alias existe déjà).
    IF NEW.numero = OLD.numero THEN
      SELECT o.acronyme INTO v_acronyme FROM referentiel.operateur o WHERE o.id = NEW.operateur_id;
      NEW.matricule := v_acronyme || '-' || NEW.profil || '-' || NEW.numero;
      INSERT INTO iam.alias_matricule (compte_id, operateur_id, ancien_numero, ancien_matricule, motif, corrige_par)
      VALUES (OLD.id, OLD.operateur_id, OLD.numero, OLD.matricule, 'Changement de profil : ' || v_motif, v_acteur);
    END IF;
  END IF;

  -- Désactivation / réactivation.
  IF NEW.statut IS DISTINCT FROM OLD.statut THEN
    IF NEW.statut = 'DESACTIVE' THEN
      NEW.desactive_le := now();
    ELSE
      NEW.desactive_le := NULL;
      IF EXISTS (SELECT 1 FROM iam.utilisateur u WHERE u.id = NEW.utilisateur_id AND u.statut = 'DESACTIVE') THEN
        RAISE EXCEPTION 'L''utilisateur est désactivé : il ne peut accéder à aucun compte.'
          USING ERRCODE = 'check_violation';
      END IF;
    END IF;
    -- Hors activation par l'utilisateur lui-même et hors cascade : il faut l'autorité de gérer le compte.
    IF v_acteur IS NOT NULL AND v_acteur <> OLD.id
       AND coalesce(current_setting('app.cascade', true), '') <> 'oui'
       AND (NEW.statut = 'DESACTIVE' OR OLD.statut = 'DESACTIVE')
       AND NOT iam.acteur_autorise('GERER_COMPTE', OLD.id, ARRAY[OLD.profil]) THEN
      RAISE EXCEPTION 'Le compte connecté n''a pas l''autorité de désactiver ou de réactiver ce compte.'
        USING ERRCODE = 'insufficient_privilege';
    END IF;
  END IF;

  -- Un compte qui a une histoire ne se supprime pas.
  IF OLD.supprime_le IS NULL AND NEW.supprime_le IS NOT NULL THEN
    IF OLD.derniere_connexion IS NOT NULL
       OR EXISTS (SELECT 1 FROM iam.affectation f WHERE f.compte_id = OLD.id)
       OR EXISTS (SELECT 1 FROM iam.reprise_compte r WHERE r.source_id = OLD.id OR r.cible_id = OLD.id)
       OR (SELECT count(*) FROM iam.compte_profil cp WHERE cp.compte_id = OLD.id) > 1 THEN
      RAISE EXCEPTION 'Suppression impossible : ce compte a un historique. Désactivez-le.'
        USING ERRCODE = 'restrict_violation';
    END IF;
  END IF;
  RETURN NEW;
END $$;

-- 3. Comptes existants : l'ancien matricule est gardé en alias, le nouveau est calculé.
INSERT INTO iam.alias_matricule (compte_id, operateur_id, ancien_numero, ancien_matricule, motif)
SELECT c.id, c.operateur_id, c.numero, c.matricule, 'Nouveau format du matricule : acronyme, profil, numéro'
  FROM iam.compte c
 WHERE c.matricule ~ '^[A-Z0-9]{2,8}-[0-9]{5,7}$';

ALTER TABLE iam.compte DISABLE TRIGGER trg_compte_2_modification;
UPDATE iam.compte c
   SET matricule = o.acronyme || '-' || c.profil || '-' || c.numero
  FROM referentiel.operateur o
 WHERE o.id = c.operateur_id AND c.matricule ~ '^[A-Z0-9]{2,8}-[0-9]{5,7}$';
ALTER TABLE iam.compte ENABLE TRIGGER trg_compte_2_modification;

ALTER TABLE iam.compte ADD CONSTRAINT compte_matricule_check
    CHECK (matricule ~ '^[A-Z0-9]{2,8}-[A-Z]{2}-[0-9]{5,7}$');
ALTER TABLE iam.compte ADD COLUMN reference_compte varchar(24) GENERATED ALWAYS AS (matricule) STORED;
