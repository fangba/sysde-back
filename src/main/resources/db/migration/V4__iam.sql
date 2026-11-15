-- ============================================================================
-- V4 — IAM : profils, permissions, utilisateurs, comptes, affectations,
--       jetons, import CSV
-- ============================================================================
-- Vocabulaire (cahier des charges, section 4) :
--   * utilisateur : la personne. Activé ou désactivé.
--   * compte      : appartient à UN utilisateur pour toute sa vie ; c'est lui qui agit et qui est
--                   tracé. Un seul profil à la fois (modifiable à la promotion, avec motif),
--                   un matricule stable, une référence calculée (profil + matricule).
--   * profil      : la fonction (AD, SU, CD, CS, CF, CO, FS, MO, ES, SE). Il porte les permissions.
--   * affectation : le territoire d'un compte (secteur, lot, section), avec dates de début et de fin.
--                   Faite une seule fois : elle suit le territoire d'un contrat à l'autre.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- Profils (10) et règles de périmètre
-- ---------------------------------------------------------------------------
CREATE TABLE iam.profil (
    code             char(2)     PRIMARY KEY CHECK (code ~ '^[A-Z]{2}$'),
    libelle          varchar(60) NOT NULL,
    organisme        boolean     NOT NULL,     -- true : réservé à APROMAC / FIRCA
    niveau           text        NOT NULL CHECK (niveau IN ('AUCUN', 'SECTEUR', 'LOT', 'SECTION')),
    max_affectations smallint    CHECK (max_affectations >= 0),   -- NULL = illimité
    createur_profil  char(2)     REFERENCES iam.profil (code) DEFERRABLE INITIALLY DEFERRED,
    rang             smallint    NOT NULL,     -- 1 = le plus haut ; un compte ne gère que des rangs inférieurs
    ordre            smallint    NOT NULL
);

CREATE TABLE iam.permission (
    code     varchar(60) PRIMARY KEY CHECK (code ~ '^[A-Z_]+$'),
    domaine  varchar(30) NOT NULL,
    libelle  varchar(150) NOT NULL
);

-- Ajustable par les administrateurs : les permissions d'un profil (identiques pour tous les opérateurs).
CREATE TABLE iam.profil_permission (
    profil      char(2)     NOT NULL REFERENCES iam.profil (code),
    permission  varchar(60) NOT NULL REFERENCES iam.permission (code),
    PRIMARY KEY (profil, permission)
);

CREATE FUNCTION iam.a_permission(p_profil char(2), p_permission text) RETURNS boolean
LANGUAGE sql STABLE AS $$
  SELECT EXISTS (SELECT 1 FROM iam.profil_permission pp WHERE pp.profil = p_profil AND pp.permission = p_permission)
$$;

-- ---------------------------------------------------------------------------
-- Utilisateurs (les personnes)
-- ---------------------------------------------------------------------------
CREATE TABLE iam.utilisateur (
    id            uuid        PRIMARY KEY DEFAULT util.uuid_v7(),
    nom           varchar(100) NOT NULL,
    prenoms       varchar(150) NOT NULL,
    email         varchar(150),
    telephone     varchar(30),
    statut        text        NOT NULL DEFAULT 'ACTIF' CHECK (statut IN ('ACTIF', 'DESACTIVE')),
    desactive_le  timestamptz,
    cree_le       timestamptz NOT NULL DEFAULT now(),
    cree_par      uuid,
    modifie_le    timestamptz NOT NULL DEFAULT now(),
    modifie_par   uuid,
    supprime_le   timestamptz,
    supprime_par  uuid,
    version       bigint      NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX ux_utilisateur_email ON iam.utilisateur (lower(email))
    WHERE supprime_le IS NULL AND email IS NOT NULL;
CREATE INDEX ix_utilisateur_nom ON iam.utilisateur (lower(nom), telephone) WHERE supprime_le IS NULL;
SELECT util.attacher_horodatage('iam.utilisateur');

-- Liaison à un fournisseur d'identité externe (SSO). Vide au jalon 1.
CREATE TABLE iam.identite_externe (
    id              uuid        PRIMARY KEY DEFAULT util.uuid_v7(),
    utilisateur_id  uuid        NOT NULL REFERENCES iam.utilisateur (id),
    fournisseur     varchar(50) NOT NULL,
    sujet           varchar(255) NOT NULL,
    cree_le         timestamptz NOT NULL DEFAULT now(),
    UNIQUE (fournisseur, sujet)
);

-- ---------------------------------------------------------------------------
-- Comptes
-- ---------------------------------------------------------------------------
-- Matricule = ACRONYME-NUMERO (ex. SAPH-00348) : identifiant de connexion, stable. Le numéro est
-- celui de l'opérateur (repris à la création ou à l'import) ; à défaut, le système en attribue un.
-- Il est unique dans l'organisation, tous profils confondus, et n'est jamais réutilisé.
-- reference_compte = profil + matricule (ex. MO-SAPH-00348) : calculée, change avec le profil.
CREATE TABLE iam.compte (
    id                        uuid        PRIMARY KEY DEFAULT util.uuid_v7(),
    utilisateur_id            uuid        NOT NULL REFERENCES iam.utilisateur (id),
    operateur_id              uuid        NOT NULL REFERENCES referentiel.operateur (id),
    profil                    char(2)     NOT NULL REFERENCES iam.profil (code),
    numero                    varchar(7)  NOT NULL CHECK (numero ~ '^[0-9]{5,7}$'),
    matricule                 varchar(17) NOT NULL CHECK (matricule ~ '^[A-Z0-9]{2,8}-[0-9]{5,7}$'),
    reference_compte          varchar(20) GENERATED ALWAYS AS (profil || '-' || matricule) STORED,
    statut                    text        NOT NULL DEFAULT 'EN_ATTENTE'
        CHECK (statut IN ('EN_ATTENTE', 'ACTIF', 'DESACTIVE')),
    desactive_le              timestamptz,
    mot_de_passe_hash         varchar(100),                 -- jamais journalisé (voir V5)
    doit_changer_mot_de_passe boolean     NOT NULL DEFAULT true,
    echecs_connexion          smallint    NOT NULL DEFAULT 0,
    verrouille_jusqu_a        timestamptz,
    derniere_connexion        timestamptz,
    cree_le                   timestamptz NOT NULL DEFAULT now(),
    cree_par                  uuid,
    modifie_le                timestamptz NOT NULL DEFAULT now(),
    modifie_par               uuid,
    supprime_le               timestamptz,
    supprime_par              uuid,
    version                   bigint      NOT NULL DEFAULT 0,
    CONSTRAINT ck_compte_actif_mot_de_passe CHECK (statut <> 'ACTIF' OR mot_de_passe_hash IS NOT NULL),
    UNIQUE (id, operateur_id),
    UNIQUE (operateur_id, numero),
    UNIQUE (matricule)
);
-- Un seul compte actif (ou en attente d'activation) à la fois par utilisateur.
CREATE UNIQUE INDEX ux_compte_actif ON iam.compte (utilisateur_id)
    WHERE statut <> 'DESACTIVE' AND supprime_le IS NULL;
CREATE INDEX ix_compte_utilisateur ON iam.compte (utilisateur_id);
CREATE INDEX ix_compte_operateur_profil ON iam.compte (operateur_id, profil) WHERE supprime_le IS NULL;
SELECT util.attacher_horodatage('iam.compte');

-- Dernier numéro attribué par organisation : garantit qu'un numéro n'est jamais réutilisé.
CREATE TABLE iam.compteur_numero (
    operateur_id  uuid     PRIMARY KEY REFERENCES referentiel.operateur (id),
    dernier       integer  NOT NULL
);

-- Réserve un numéro : celui qui est fourni (repris du système de l'opérateur) ou, à défaut, le suivant.
CREATE FUNCTION iam.reserver_numero(p_operateur uuid, p_numero text DEFAULT NULL) RETURNS varchar
LANGUAGE plpgsql AS $$
DECLARE v_dernier integer;
BEGIN
  IF p_numero IS NULL THEN
    INSERT INTO iam.compteur_numero (operateur_id, dernier) VALUES (p_operateur, 1)
    ON CONFLICT (operateur_id) DO UPDATE SET dernier = iam.compteur_numero.dernier + 1
    RETURNING dernier INTO v_dernier;
    RETURN lpad(v_dernier::text, greatest(5, length(v_dernier::text)), '0');
  END IF;
  IF p_numero !~ '^[0-9]{5,7}$' THEN
    RETURN p_numero;                     -- la contrainte du compte refusera le format
  END IF;
  INSERT INTO iam.compteur_numero (operateur_id, dernier) VALUES (p_operateur, p_numero::integer)
  ON CONFLICT (operateur_id) DO UPDATE SET dernier = greatest(iam.compteur_numero.dernier, p_numero::integer);
  RETURN p_numero;
END $$;

-- Anciens matricules (correction par un administrateur) : refusés à la connexion,
-- retrouvables dans l'historique, et leur numéro n'est plus attribué à un autre compte.
CREATE TABLE iam.alias_matricule (
    id                uuid        PRIMARY KEY DEFAULT util.uuid_v7(),
    compte_id         uuid        NOT NULL REFERENCES iam.compte (id),
    operateur_id      uuid        NOT NULL,
    ancien_numero     varchar(7)  NOT NULL,
    ancien_matricule  varchar(17) NOT NULL,
    motif             text        NOT NULL CHECK (length(btrim(motif)) > 0),
    corrige_le        timestamptz NOT NULL DEFAULT now(),
    corrige_par       uuid
);
CREATE INDEX ix_alias_numero ON iam.alias_matricule (operateur_id, ancien_numero);
CREATE INDEX ix_alias_matricule ON iam.alias_matricule (ancien_matricule);

-- Historique des profils d'un compte (promotions, rétrogradations). Une ligne ouverte par compte.
CREATE TABLE iam.compte_profil (
    id          uuid        PRIMARY KEY DEFAULT util.uuid_v7(),
    compte_id   uuid        NOT NULL REFERENCES iam.compte (id),
    profil      char(2)     NOT NULL REFERENCES iam.profil (code),
    debut       timestamptz NOT NULL DEFAULT clock_timestamp(),
    fin         timestamptz,
    motif       text,                       -- obligatoire dès le second profil
    change_par  uuid        DEFAULT util.acteur(),
    CHECK (fin IS NULL OR fin >= debut)
);
CREATE UNIQUE INDEX ux_compte_profil_ouvert ON iam.compte_profil (compte_id) WHERE fin IS NULL;
CREATE INDEX ix_compte_profil_compte ON iam.compte_profil (compte_id, debut);

-- Profil qu'avait un compte à un instant donné (pour la traçabilité).
CREATE FUNCTION iam.profil_a(p_compte uuid, p_instant timestamptz DEFAULT now()) RETURNS char(2)
LANGUAGE sql STABLE AS $$
  SELECT cp.profil FROM iam.compte_profil cp
   WHERE cp.compte_id = p_compte AND cp.debut <= p_instant AND (cp.fin IS NULL OR p_instant < cp.fin)
   ORDER BY cp.debut DESC LIMIT 1
$$;

-- Le compte connecté (util.acteur()) peut-il exercer p_permission sur le compte p_cible ?
-- Conditions : compte actif, permission détenue par son profil, autre compte que la cible,
-- rang strictement supérieur à celui de chaque profil concerné (un AD peut aussi gérer un AD),
-- même organisation (un AD gère tous les opérateurs techniques, mais pas l'autre organisme).
CREATE FUNCTION iam.acteur_autorise(p_permission text, p_cible uuid, p_profils char(2)[]) RETURNS boolean
LANGUAGE sql STABLE AS $$
  SELECT EXISTS (
    SELECT 1
      FROM iam.compte a
      JOIN iam.profil pa ON pa.code = a.profil
      JOIN iam.compte c  ON c.id = p_cible
     WHERE a.id = util.acteur() AND a.statut = 'ACTIF' AND a.supprime_le IS NULL
       AND a.id <> c.id
       AND iam.a_permission(a.profil, p_permission)
       AND NOT EXISTS (
             SELECT 1 FROM unnest(p_profils) AS x(code) JOIN iam.profil px ON px.code = x.code
              WHERE NOT (pa.rang < px.rang OR (a.profil = 'AD' AND x.code = 'AD'))
                 OR (px.organisme AND a.operateur_id <> c.operateur_id))
       AND (a.operateur_id = c.operateur_id
            OR (a.profil = 'AD' AND NOT EXISTS (SELECT 1 FROM iam.profil pc WHERE pc.code = ANY (p_profils) AND pc.organisme))))
$$;

-- Création d'un compte : cohérence profil / organisation, hiérarchie de création, permission,
-- attribution du numéro et du matricule.
--   Administrateur -> AD, SU (de son organisation) et CD (de n'importe quel opérateur technique)
--   CD -> CS ; CS -> CF, CO, FS, MO, ES, SE (toujours de son propre opérateur)
-- Sans acteur (amorçage), seul un administrateur peut être créé.
CREATE FUNCTION iam.avant_creation_compte() RETURNS trigger
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
  NEW.matricule := v_acronyme || '-' || NEW.numero;
  IF NEW.statut = 'DESACTIVE' THEN
    NEW.desactive_le := now();
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER trg_compte_1_creation BEFORE INSERT ON iam.compte
    FOR EACH ROW EXECUTE FUNCTION iam.avant_creation_compte();

CREATE FUNCTION iam.apres_creation_compte() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  INSERT INTO iam.compte_profil (compte_id, profil) VALUES (NEW.id, NEW.profil);
  RETURN NULL;
END $$;
CREATE TRIGGER trg_compte_profil_initial AFTER INSERT ON iam.compte
    FOR EACH ROW EXECUTE FUNCTION iam.apres_creation_compte();

-- Modification d'un compte : immuabilité (utilisateur, opérateur), correction du numéro par un
-- administrateur, changement de profil avec motif, désactivation / réactivation, suppression.
CREATE FUNCTION iam.avant_modification_compte() RETURNS trigger
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
    NEW.matricule := v_acronyme || '-' || NEW.numero;
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
-- (la table reprise_compte est créée plus bas ; la fonction ne la lit qu'à l'exécution)
CREATE TRIGGER trg_compte_2_modification BEFORE UPDATE ON iam.compte
    FOR EACH ROW EXECUTE FUNCTION iam.avant_modification_compte();

-- Désactivation ou remise en attente : les sessions du compte sont coupées immédiatement.
-- (Les jetons de rafraîchissement sont définis plus bas ; la fonction ne les lit qu'à l'exécution.)
CREATE FUNCTION iam.couper_sessions_compte() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.statut IN ('DESACTIVE', 'EN_ATTENTE') AND OLD.statut IS DISTINCT FROM NEW.statut THEN
    UPDATE iam.jeton_rafraichissement SET revoque_le = now()
     WHERE compte_id = NEW.id AND revoque_le IS NULL;
  END IF;
  RETURN NULL;
END $$;
CREATE TRIGGER trg_compte_sessions AFTER UPDATE OF statut ON iam.compte
    FOR EACH ROW EXECUTE FUNCTION iam.couper_sessions_compte();

-- Désactiver un utilisateur désactive tous ses comptes : il ne peut plus accéder à aucun.
CREATE FUNCTION iam.avant_modification_utilisateur() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE v_acteur uuid := util.acteur();
BEGIN
  IF NEW.statut IS DISTINCT FROM OLD.statut THEN
    NEW.desactive_le := CASE WHEN NEW.statut = 'DESACTIVE' THEN now() END;
    IF v_acteur IS NOT NULL THEN
      -- L'acteur doit pouvoir gérer au moins un des comptes de la personne (ou avoir le droit de
      -- gérer les comptes, si elle n'en a aucun).
      IF EXISTS (SELECT 1 FROM iam.compte c WHERE c.utilisateur_id = OLD.id AND c.supprime_le IS NULL) THEN
        IF NOT EXISTS (SELECT 1 FROM iam.compte c
                        WHERE c.utilisateur_id = OLD.id AND c.supprime_le IS NULL
                          AND iam.acteur_autorise('GERER_COMPTE', c.id, ARRAY[c.profil])) THEN
          RAISE EXCEPTION 'Le compte connecté n''a pas l''autorité de désactiver ou de réactiver cet utilisateur.'
            USING ERRCODE = 'insufficient_privilege';
        END IF;
      ELSIF NOT iam.a_permission((SELECT profil FROM iam.compte WHERE id = v_acteur), 'GERER_COMPTE') THEN
        RAISE EXCEPTION 'Le compte connecté n''a pas la permission de gérer les comptes.'
          USING ERRCODE = 'insufficient_privilege';
      END IF;
    END IF;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER trg_utilisateur_modification BEFORE UPDATE ON iam.utilisateur
    FOR EACH ROW EXECUTE FUNCTION iam.avant_modification_utilisateur();

CREATE FUNCTION iam.cascade_desactivation_utilisateur() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.statut = 'DESACTIVE' AND OLD.statut IS DISTINCT FROM NEW.statut THEN
    PERFORM set_config('app.cascade', 'oui', true);
    UPDATE iam.compte SET statut = 'DESACTIVE'
     WHERE utilisateur_id = NEW.id AND statut <> 'DESACTIVE' AND supprime_le IS NULL;
    PERFORM set_config('app.cascade', '', true);
  END IF;
  RETURN NULL;
END $$;
CREATE TRIGGER trg_utilisateur_cascade AFTER UPDATE OF statut ON iam.utilisateur
    FOR EACH ROW EXECUTE FUNCTION iam.cascade_desactivation_utilisateur();

-- Garde-fous sur les permissions : seul un compte qui détient « gérer les permissions » peut les
-- ajuster, et l'administrateur ne peut pas perdre « gérer les permissions » ni « gérer les référentiels »
-- (le système ne doit pas pouvoir se bloquer lui-même).
CREATE FUNCTION iam.garder_permissions() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE v_acteur uuid := util.acteur();
BEGIN
  IF TG_OP = 'DELETE' AND OLD.profil = 'AD' AND OLD.permission IN ('GERER_PERMISSIONS', 'GERER_REFERENTIEL') THEN
    RAISE EXCEPTION 'La permission % ne peut pas être retirée à l''administrateur.', OLD.permission
      USING ERRCODE = 'restrict_violation';
  END IF;
  IF v_acteur IS NOT NULL
     AND NOT iam.a_permission((SELECT c.profil FROM iam.compte c WHERE c.id = v_acteur), 'GERER_PERMISSIONS') THEN
    RAISE EXCEPTION 'Le compte connecté n''a pas la permission de gérer les permissions.'
      USING ERRCODE = 'insufficient_privilege';
  END IF;
  IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER trg_profil_permission_garde BEFORE INSERT OR DELETE ON iam.profil_permission
    FOR EACH ROW EXECUTE FUNCTION iam.garder_permissions();

-- Un compte peut-il se connecter ? Actif, utilisateur actif, organisation active, non verrouillé.
CREATE FUNCTION iam.compte_peut_se_connecter(p_compte uuid) RETURNS boolean
LANGUAGE sql STABLE AS $$
  SELECT EXISTS (
    SELECT 1
      FROM iam.compte c
      JOIN iam.utilisateur u ON u.id = c.utilisateur_id
      JOIN referentiel.operateur o ON o.id = c.operateur_id
     WHERE c.id = p_compte AND c.supprime_le IS NULL AND u.supprime_le IS NULL AND o.supprime_le IS NULL
       AND c.statut = 'ACTIF' AND u.statut = 'ACTIF' AND o.actif
       AND (c.verrouille_jusqu_a IS NULL OR c.verrouille_jusqu_a <= now()))
$$;

-- Un compte peut-il synchroniser le mobile (envoi seul) ? Oui s'il peut se connecter, ou s'il a été
-- désactivé depuis moins de compte.delai_synchronisation_jours jours (30 par défaut).
CREATE FUNCTION iam.compte_peut_synchroniser(p_compte uuid) RETURNS boolean
LANGUAGE sql STABLE AS $$
  SELECT iam.compte_peut_se_connecter(p_compte) OR EXISTS (
    SELECT 1
      FROM iam.compte c
      JOIN referentiel.operateur o ON o.id = c.operateur_id
     WHERE c.id = p_compte AND c.supprime_le IS NULL AND c.statut = 'DESACTIVE' AND o.actif
       AND c.desactive_le + make_interval(days => COALESCE(
             (SELECT p.valeur::integer FROM util.parametre p
               WHERE p.cle = 'compte.delai_synchronisation_jours' AND p.supprime_le IS NULL), 30)) >= now())
$$;

-- Jetons à usage unique (activation du compte, réinitialisation). Seule l'empreinte est stockée.
CREATE TABLE iam.jeton_compte (
    id              uuid        PRIMARY KEY DEFAULT util.uuid_v7(),
    compte_id       uuid        NOT NULL REFERENCES iam.compte (id),
    type            text        NOT NULL CHECK (type IN ('ACTIVATION', 'REINITIALISATION')),
    empreinte       char(64)    NOT NULL UNIQUE,
    expire_le       timestamptz NOT NULL,
    utilise_le      timestamptz,
    cree_le         timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_jeton_compte ON iam.jeton_compte (compte_id);

-- Jetons de rafraîchissement (sessions). Rotation par « famille » : la réutilisation
-- d'un jeton déjà tourné révoque toute la famille.
CREATE TABLE iam.jeton_rafraichissement (
    id              uuid        PRIMARY KEY DEFAULT util.uuid_v7(),
    compte_id       uuid        NOT NULL REFERENCES iam.compte (id),
    famille_id      uuid        NOT NULL,
    empreinte       char(64)    NOT NULL UNIQUE,
    appareil        varchar(150),
    adresse_ip      inet,
    expire_le       timestamptz NOT NULL,
    revoque_le      timestamptz,
    cree_le         timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_jeton_rafraichissement_compte ON iam.jeton_rafraichissement (compte_id);
CREATE INDEX ix_jeton_rafraichissement_expire ON iam.jeton_rafraichissement (expire_le);

-- ---------------------------------------------------------------------------
-- Affectations : territoire d'un compte, avec dates
-- ---------------------------------------------------------------------------
--   niveau SECTEUR : une ligne, lot_id et section_id vides (CS, ES, SE)
--   niveau LOT     : une ligne avec lot_id (CF, FS)
--   niveau SECTION : une ligne par section (MO : une seule ; CO : autant que nécessaire)
-- Faite une seule fois, liée au territoire et non au contrat. Une affectation est ouverte tant que
-- fin est vide. Si le territoire n'est plus couvert par un contrat de l'opérateur, elle devient
-- « à revoir » (voir la vue iam.v_affectation_etat) : jamais supprimée automatiquement.
CREATE TABLE iam.affectation (
    id              uuid        PRIMARY KEY DEFAULT util.uuid_v7(),
    compte_id       uuid        NOT NULL,
    operateur_id    uuid        NOT NULL,
    secteur_id      uuid        NOT NULL REFERENCES referentiel.secteur (id),
    lot_id          uuid,
    section_id      uuid,
    debut           date        NOT NULL DEFAULT current_date,
    fin             date,
    cree_le         timestamptz NOT NULL DEFAULT now(),
    cree_par        uuid,
    modifie_le      timestamptz NOT NULL DEFAULT now(),
    modifie_par     uuid,
    supprime_le     timestamptz,
    supprime_par    uuid,
    version         bigint      NOT NULL DEFAULT 0,
    CHECK (section_id IS NULL OR lot_id IS NOT NULL),
    CHECK (fin IS NULL OR fin >= debut),
    FOREIGN KEY (compte_id, operateur_id) REFERENCES iam.compte (id, operateur_id),
    -- le lot appartient au secteur (ignoré si lot_id est vide) :
    FOREIGN KEY (lot_id, secteur_id)      REFERENCES referentiel.lot (id, secteur_id),
    -- la section appartient au lot (ignoré si section_id est vide) :
    FOREIGN KEY (section_id, lot_id)      REFERENCES referentiel.section (id, lot_id)
);
CREATE UNIQUE INDEX ux_affectation_ouverte ON iam.affectation (compte_id, secteur_id, lot_id, section_id)
    NULLS NOT DISTINCT WHERE fin IS NULL AND supprime_le IS NULL;
CREATE INDEX ix_affectation_compte ON iam.affectation (compte_id) WHERE supprime_le IS NULL;
CREATE INDEX ix_affectation_operateur_secteur ON iam.affectation (operateur_id, secteur_id)
    WHERE fin IS NULL AND supprime_le IS NULL;
CREATE INDEX ix_affectation_lot ON iam.affectation (lot_id) WHERE fin IS NULL AND supprime_le IS NULL;
CREATE INDEX ix_affectation_section ON iam.affectation (section_id) WHERE fin IS NULL AND supprime_le IS NULL;
SELECT util.attacher_horodatage('iam.affectation');

CREATE FUNCTION iam.verifier_affectation() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  v_profil  char(2);
  v_statut  text;
  v_niveau  text;
  v_max     smallint;
  v_nb      integer;
BEGIN
  IF NEW.fin IS NOT NULL OR NEW.supprime_le IS NOT NULL THEN
    RETURN NEW;                            -- clôture : rien à vérifier
  END IF;

  SELECT c.profil, c.statut INTO v_profil, v_statut FROM iam.compte c WHERE c.id = NEW.compte_id;
  SELECT p.niveau, p.max_affectations INTO v_niveau, v_max FROM iam.profil p WHERE p.code = v_profil;

  IF v_statut = 'DESACTIVE' THEN
    RAISE EXCEPTION 'Un compte désactivé ne peut pas recevoir d''affectation.' USING ERRCODE = 'check_violation';
  END IF;
  IF v_niveau = 'AUCUN' THEN
    RAISE EXCEPTION 'Le profil % n''a pas d''affectation territoriale.', v_profil
      USING ERRCODE = 'check_violation';
  ELSIF v_niveau = 'SECTEUR' AND (NEW.lot_id IS NOT NULL OR NEW.section_id IS NOT NULL) THEN
    RAISE EXCEPTION 'Le profil % est affecté à un secteur entier, pas à un lot ou une section.', v_profil
      USING ERRCODE = 'check_violation';
  ELSIF v_niveau = 'LOT' AND (NEW.lot_id IS NULL OR NEW.section_id IS NOT NULL) THEN
    RAISE EXCEPTION 'Le profil % est affecté à un lot.', v_profil
      USING ERRCODE = 'check_violation';
  ELSIF v_niveau = 'SECTION' AND NEW.section_id IS NULL THEN
    RAISE EXCEPTION 'Le profil % est affecté à des sections.', v_profil
      USING ERRCODE = 'check_violation';
  END IF;

  -- Le territoire doit être couvert par un contrat de l'opérateur en vigueur à la date de début.
  IF NEW.lot_id IS NOT NULL THEN
    IF NOT EXISTS (SELECT 1 FROM gestion.attribution_lot al
                     JOIN gestion.attribution a ON a.id = al.attribution_id
                    WHERE a.operateur_id = NEW.operateur_id AND al.lot_id = NEW.lot_id
                      AND a.supprime_le IS NULL AND al.periode @> NEW.debut) THEN
      RAISE EXCEPTION 'Le lot n''est pas attribué à l''opérateur du compte.' USING ERRCODE = 'check_violation';
    END IF;
  ELSIF NOT EXISTS (SELECT 1 FROM gestion.attribution a JOIN gestion.contrat c ON c.id = a.contrat_id
                     WHERE a.operateur_id = NEW.operateur_id AND a.secteur_id = NEW.secteur_id
                       AND a.supprime_le IS NULL AND c.supprime_le IS NULL
                       AND c.periode @> NEW.debut) THEN
    RAISE EXCEPTION 'Le secteur n''est pas attribué à l''opérateur du compte.' USING ERRCODE = 'check_violation';
  END IF;

  -- Un seul secteur à la fois.
  IF EXISTS (SELECT 1 FROM iam.affectation f
              WHERE f.compte_id = NEW.compte_id AND f.id <> NEW.id AND f.fin IS NULL
                AND f.supprime_le IS NULL AND f.secteur_id <> NEW.secteur_id) THEN
    RAISE EXCEPTION 'Un compte ne peut intervenir que sur un seul secteur à la fois.'
      USING ERRCODE = 'check_violation';
  END IF;

  IF v_max IS NOT NULL THEN
    SELECT count(*) INTO v_nb FROM iam.affectation f
     WHERE f.compte_id = NEW.compte_id AND f.id <> NEW.id AND f.fin IS NULL AND f.supprime_le IS NULL;
    IF v_nb >= v_max THEN
      RAISE EXCEPTION 'Le profil % admet au plus % affectation(s).', v_profil, v_max
        USING ERRCODE = 'check_violation';
    END IF;
  END IF;

  -- Un seul chef secteur par secteur et par opérateur ; un seul moniteur par section
  -- (les comptes désactivés ne comptent pas).
  IF v_profil = 'CS' AND EXISTS (
       SELECT 1 FROM iam.affectation f JOIN iam.compte c ON c.id = f.compte_id
        WHERE f.operateur_id = NEW.operateur_id AND f.secteur_id = NEW.secteur_id AND f.id <> NEW.id
          AND f.fin IS NULL AND f.supprime_le IS NULL AND c.profil = 'CS' AND c.statut <> 'DESACTIVE') THEN
    RAISE EXCEPTION 'Un seul chef secteur par secteur et par opérateur.' USING ERRCODE = 'unique_violation';
  END IF;
  IF v_profil = 'MO' AND EXISTS (
       SELECT 1 FROM iam.affectation f JOIN iam.compte c ON c.id = f.compte_id
        WHERE f.section_id = NEW.section_id AND f.id <> NEW.id
          AND f.fin IS NULL AND f.supprime_le IS NULL AND c.profil = 'MO' AND c.statut <> 'DESACTIVE') THEN
    RAISE EXCEPTION 'Une section n''a qu''un seul moniteur à la fois.' USING ERRCODE = 'unique_violation';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER trg_affectation_verifier BEFORE INSERT OR UPDATE ON iam.affectation
    FOR EACH ROW EXECUTE FUNCTION iam.verifier_affectation();

-- État d'une affectation, à la date du jour :
--   ACTIVE    territoire couvert par un contrat en cours de l'opérateur
--   SUSPENDUE l'opérateur n'a aucun contrat en cours (entre deux contrats)
--   A_REVOIR  l'opérateur a un contrat en cours qui ne couvre plus ce territoire : à réaffecter
--   CLOSE     affectation terminée (historique)
CREATE VIEW iam.v_affectation_etat AS
SELECT f.*,
       CASE WHEN f.fin IS NOT NULL AND f.fin <= current_date THEN 'CLOSE'
            WHEN x.couvert THEN 'ACTIVE'
            WHEN NOT x.contrat_en_cours THEN 'SUSPENDUE'
            ELSE 'A_REVOIR' END AS etat
  FROM iam.affectation f
 CROSS JOIN LATERAL (
        SELECT CASE WHEN f.lot_id IS NOT NULL
                    THEN EXISTS (SELECT 1 FROM gestion.attribution_lot al
                                  JOIN gestion.attribution a ON a.id = al.attribution_id
                                 WHERE a.operateur_id = f.operateur_id AND al.lot_id = f.lot_id
                                   AND a.supprime_le IS NULL AND al.periode @> current_date)
                    ELSE EXISTS (SELECT 1 FROM gestion.attribution a JOIN gestion.contrat c ON c.id = a.contrat_id
                                  WHERE a.operateur_id = f.operateur_id AND a.secteur_id = f.secteur_id
                                    AND a.supprime_le IS NULL AND c.supprime_le IS NULL
                                    AND c.periode @> current_date)
               END AS couvert,
               EXISTS (SELECT 1 FROM gestion.contrat c
                        WHERE c.operateur_id = f.operateur_id AND c.supprime_le IS NULL
                          AND c.periode @> current_date) AS contrat_en_cours
       ) x
 WHERE f.supprime_le IS NULL;

-- Un lot encore affecté à un agent ne peut pas être retiré d'une attribution.
CREATE FUNCTION iam.refuser_retrait_lot_affecte() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE v_operateur uuid;
BEGIN
  SELECT a.operateur_id INTO v_operateur FROM gestion.attribution a WHERE a.id = OLD.attribution_id;
  IF EXISTS (SELECT 1 FROM iam.affectation f JOIN iam.compte c ON c.id = f.compte_id
              WHERE f.operateur_id = v_operateur AND f.lot_id = OLD.lot_id
                AND f.fin IS NULL AND f.supprime_le IS NULL AND c.statut <> 'DESACTIVE') THEN
    RAISE EXCEPTION 'Retrait impossible : ce lot est encore affecté à des agents.'
      USING ERRCODE = 'restrict_violation';
  END IF;
  RETURN OLD;
END $$;
CREATE TRIGGER trg_attribution_lot_affecte BEFORE DELETE ON gestion.attribution_lot
    FOR EACH ROW EXECUTE FUNCTION iam.refuser_retrait_lot_affecte();

-- ---------------------------------------------------------------------------
-- Reprise : le travail d'un compte qui part passe au compte de son successeur
-- ---------------------------------------------------------------------------
-- Les affectations en cours du compte désactivé sont closes et recopiées sur le compte repreneur.
-- Les activités non réalisées ou en cours seront transférées de la même manière par le schéma
-- planning (à venir) ; le travail déjà réalisé reste attaché à l'ancien compte.
CREATE TABLE iam.reprise_compte (
    id               uuid        PRIMARY KEY DEFAULT util.uuid_v7(),
    source_id        uuid        NOT NULL REFERENCES iam.compte (id),
    cible_id         uuid        NOT NULL REFERENCES iam.compte (id),
    motif            text        NOT NULL CHECK (length(btrim(motif)) > 0),
    nb_affectations  integer     NOT NULL,
    repris_le        timestamptz NOT NULL DEFAULT now(),
    repris_par       uuid        DEFAULT util.acteur(),
    CHECK (source_id <> cible_id)
);
CREATE INDEX ix_reprise_source ON iam.reprise_compte (source_id);
CREATE INDEX ix_reprise_cible ON iam.reprise_compte (cible_id);

CREATE FUNCTION iam.reprendre_compte(p_source uuid, p_cible uuid, p_motif text) RETURNS integer
LANGUAGE plpgsql AS $$
DECLARE
  s        iam.compte;
  c        iam.compte;
  v_nb     integer := 0;
  f        iam.affectation;
BEGIN
  IF p_motif IS NULL OR btrim(p_motif) = '' THEN
    RAISE EXCEPTION 'Le motif est obligatoire pour une reprise.' USING ERRCODE = 'check_violation';
  END IF;
  SELECT * INTO s FROM iam.compte WHERE id = p_source AND supprime_le IS NULL;
  SELECT * INTO c FROM iam.compte WHERE id = p_cible AND supprime_le IS NULL;
  IF s.id IS NULL OR c.id IS NULL THEN
    RAISE EXCEPTION 'Compte introuvable.' USING ERRCODE = 'no_data_found';
  END IF;
  IF s.operateur_id <> c.operateur_id THEN
    RAISE EXCEPTION 'La reprise se fait entre comptes de la même organisation.' USING ERRCODE = 'check_violation';
  END IF;
  IF s.statut <> 'DESACTIVE' THEN
    RAISE EXCEPTION 'Le compte repris doit être désactivé.' USING ERRCODE = 'check_violation';
  END IF;
  IF c.statut = 'DESACTIVE' THEN
    RAISE EXCEPTION 'Le compte repreneur doit être actif ou en attente d''activation.' USING ERRCODE = 'check_violation';
  END IF;
  IF util.acteur() IS NULL OR NOT iam.acteur_autorise('REPRENDRE_COMPTE', s.id, ARRAY[s.profil, c.profil]) THEN
    RAISE EXCEPTION 'Reprise refusée : le compte connecté n''a pas l''autorité sur ces comptes.'
      USING ERRCODE = 'insufficient_privilege';
  END IF;

  FOR f IN SELECT * FROM iam.affectation
            WHERE compte_id = s.id AND fin IS NULL AND supprime_le IS NULL ORDER BY id LOOP
    UPDATE iam.affectation SET fin = current_date WHERE id = f.id;
    INSERT INTO iam.affectation (compte_id, operateur_id, secteur_id, lot_id, section_id, debut)
    VALUES (c.id, f.operateur_id, f.secteur_id, f.lot_id, f.section_id, current_date);
    v_nb := v_nb + 1;
  END LOOP;

  INSERT INTO iam.reprise_compte (source_id, cible_id, motif, nb_affectations)
  VALUES (s.id, c.id, btrim(p_motif), v_nb);
  RETURN v_nb;
END $$;

-- ---------------------------------------------------------------------------
-- Import CSV de comptes : validation complète, puis application des lignes valides.
-- ---------------------------------------------------------------------------
-- Colonnes du modèle : nom, prenoms, email, telephone, profil, numero (facultatif, repris de
-- l'opérateur), secteur / lot / section (facultatifs, pour affecter à l'import).
CREATE TABLE iam.import_compte (
    id              uuid        PRIMARY KEY DEFAULT util.uuid_v7(),
    createur_id     uuid        NOT NULL REFERENCES iam.compte (id),
    fichier_nom     varchar(255) NOT NULL,
    empreinte       char(64)    NOT NULL,                -- SHA-256 du fichier
    statut          text        NOT NULL DEFAULT 'VALIDATION'
        CHECK (statut IN ('VALIDATION', 'PRET', 'APPLIQUE', 'ANNULE')),
    nb_lignes       integer     NOT NULL DEFAULT 0,
    nb_valides      integer     NOT NULL DEFAULT 0,
    nb_rejetes      integer     NOT NULL DEFAULT 0,
    cree_le         timestamptz NOT NULL DEFAULT now(),
    termine_le      timestamptz
);
CREATE INDEX ix_import_createur ON iam.import_compte (createur_id, cree_le DESC);

CREATE TABLE iam.import_compte_ligne (
    import_id       uuid        NOT NULL REFERENCES iam.import_compte (id) ON DELETE CASCADE,
    numero_ligne    integer     NOT NULL,
    donnees         jsonb       NOT NULL,
    erreurs         jsonb,
    valide          boolean     NOT NULL DEFAULT false,
    PRIMARY KEY (import_id, numero_ligne)
);

-- L'acronyme d'une organisation ne change plus dès qu'un compte lui est rattaché
-- (il est inscrit dans les matricules).
CREATE FUNCTION iam.figer_acronyme() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.acronyme IS DISTINCT FROM OLD.acronyme
     AND EXISTS (SELECT 1 FROM iam.compte c WHERE c.operateur_id = OLD.id) THEN
    RAISE EXCEPTION 'L''acronyme % ne peut plus changer : des comptes y sont rattachés.', OLD.acronyme
      USING ERRCODE = 'restrict_violation';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER trg_operateur_acronyme BEFORE UPDATE OF acronyme ON referentiel.operateur
    FOR EACH ROW EXECUTE FUNCTION iam.figer_acronyme();
