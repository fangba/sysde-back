-- ============================================================================
-- V10 — Planning d'activité : référentiel des activités, planning annuel, réalisation
-- ============================================================================
-- Source : « Programme d'activité » (3 familles, 11 activités) et décisions du front (endpoints-back.md §3).
--   * famille = catégorie : FORMATION (saignée), VISITE (plantation), RENCONTRE (technique) ;
--   * chaque activité dit quels profils la réalisent et à quelle fréquence (table activite_profil) ;
--   * un planning annuel par compte et par année : lignes (période, activité, lot / section, plantation),
--     soumis et validé par le chef secteur, soumission possible activité par activité ;
--   * la réalisation d'une ligne : présences (par jour), évaluation sur 5 critères, appréciation, rapport.
-- Règles de fréquence : elles sont enregistrées ici (données), mais leur contrôle reste à décider (front seul pour l'instant).
-- Les plantations et leurs superficies viennent d'une base externe : seules des références textuelles sont stockées.
-- ============================================================================

CREATE SCHEMA IF NOT EXISTS planning;
GRANT USAGE ON SCHEMA planning TO sysde_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA planning GRANT SELECT, INSERT, UPDATE ON TABLES TO sysde_app;

-- Comparaison de noms sans tenir compte de la casse, des accents ni des espaces de bord (unicité des noms de groupes).
CREATE EXTENSION IF NOT EXISTS unaccent;
CREATE FUNCTION util.sans_accent(p_texte text) RETURNS text
LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$ SELECT lower(public.unaccent('public.unaccent', btrim(p_texte))) $$;

-- ---------------------------------------------------------------------------
-- Référentiel : activités
-- ---------------------------------------------------------------------------
CREATE TABLE planning.activite (
    id               uuid        PRIMARY KEY DEFAULT util.uuid_v7(),
    code             varchar(40) NOT NULL CHECK (code ~ '^[A-Z_]+$'),
    libelle          varchar(100) NOT NULL,
    categorie        text        NOT NULL CHECK (categorie IN ('FORMATION', 'VISITE', 'RENCONTRE')),
    duree_jours      smallint    NOT NULL DEFAULT 1 CHECK (duree_jours > 0),
    planifiee        boolean     NOT NULL DEFAULT true,       -- false : faite de manière spontanée (découverte du verger)
    avec_presence    boolean     NOT NULL DEFAULT true,       -- liste de présence (chaque jour pour une formation longue)
    avec_evaluation  boolean     NOT NULL DEFAULT false,      -- évaluation sur les 5 critères
    avec_rapport     boolean     NOT NULL DEFAULT true,
    participants_min smallint    CHECK (participants_min > 0),
    participants_max smallint    CHECK (participants_max > 0),
    mois_debut       smallint    CHECK (mois_debut BETWEEN 1 AND 12),   -- période habituelle (recyclage : février à mars)
    mois_fin         smallint    CHECK (mois_fin BETWEEN 1 AND 12),
    ordre            smallint    NOT NULL,
    actif            boolean     NOT NULL DEFAULT true,
    cree_le          timestamptz NOT NULL DEFAULT now(),
    cree_par         uuid,
    modifie_le       timestamptz NOT NULL DEFAULT now(),
    modifie_par      uuid,
    supprime_le      timestamptz,
    supprime_par     uuid,
    version          bigint      NOT NULL DEFAULT 0,
    CHECK (participants_min IS NULL OR participants_max IS NULL OR participants_min <= participants_max),
    CHECK ((mois_debut IS NULL) = (mois_fin IS NULL))
);
CREATE UNIQUE INDEX ux_activite_code ON planning.activite (code) WHERE supprime_le IS NULL;
SELECT util.attacher_horodatage('planning.activite');

-- Qui réalise quoi, et à quelle fréquence. Une ligne par (activité, profil, tranche de superficie).
--   nombre / periode : nombre de séances par période ; `par` : la fréquence vaut pour chaque lot, plantation, groupe…
--   superficie_min_ha (incluse) et superficie_max_ha (exclue) : tranche de plantations encadrée par ce profil.
--   regle : règle de calcul qui ne se réduit pas à un nombre (tirages au hasard, moitié des moniteurs…).
CREATE TABLE planning.activite_profil (
    id                uuid        PRIMARY KEY DEFAULT util.uuid_v7(),
    activite_id       uuid        NOT NULL REFERENCES planning.activite (id),
    profil            char(2)     NOT NULL REFERENCES iam.profil (code),
    nombre            smallint    CHECK (nombre > 0),
    periode           text        CHECK (periode IN ('ANNEE', 'SEMESTRE', 'TRIMESTRE', 'MOIS', 'SEMAINE')),
    par               text        CHECK (par IN ('LOT', 'PLANTATION', 'SAIGNEUR', 'MONITEUR', 'GROUPE')),
    superficie_min_ha numeric(7,2) CHECK (superficie_min_ha >= 0),
    superficie_max_ha numeric(7,2) CHECK (superficie_max_ha > 0),
    regle             text        NOT NULL DEFAULT '',
    cree_le           timestamptz NOT NULL DEFAULT now(),
    cree_par          uuid,
    modifie_le        timestamptz NOT NULL DEFAULT now(),
    modifie_par       uuid,
    supprime_le       timestamptz,
    supprime_par      uuid,
    version           bigint      NOT NULL DEFAULT 0,
    CHECK (nombre IS NULL OR periode IS NOT NULL),
    CHECK (superficie_min_ha IS NULL OR superficie_max_ha IS NULL OR superficie_max_ha > superficie_min_ha)
);
CREATE UNIQUE INDEX ux_activite_profil ON planning.activite_profil (activite_id, profil, (coalesce(superficie_min_ha, 0)))
    WHERE supprime_le IS NULL;
CREATE INDEX ix_activite_profil_profil ON planning.activite_profil (profil) WHERE supprime_le IS NULL;
SELECT util.attacher_horodatage('planning.activite_profil');

-- Critères d'évaluation d'un saigneur ; chacun est noté BON, MOYEN ou MAUVAIS.
CREATE TABLE planning.critere_evaluation (
    code     varchar(30) PRIMARY KEY CHECK (code ~ '^[A-Z_]+$'),
    libelle  varchar(60) NOT NULL,
    ordre    smallint    NOT NULL
);

-- ---------------------------------------------------------------------------
-- Plantations affectées à une section (la plantation elle-même vient de la base externe)
-- ---------------------------------------------------------------------------
CREATE TABLE referentiel.affectation_plantation (
    id             uuid        PRIMARY KEY DEFAULT util.uuid_v7(),
    plantation_ref varchar(50) NOT NULL,                  -- identifiant dans la base externe
    lot_id         uuid        NOT NULL,
    section_id     uuid        NOT NULL,
    debut          date        NOT NULL DEFAULT current_date,
    fin            date,
    cree_le        timestamptz NOT NULL DEFAULT now(),
    cree_par       uuid,
    modifie_le     timestamptz NOT NULL DEFAULT now(),
    modifie_par    uuid,
    supprime_le    timestamptz,
    supprime_par   uuid,
    version        bigint      NOT NULL DEFAULT 0,
    CHECK (fin IS NULL OR fin >= debut),
    FOREIGN KEY (section_id, lot_id) REFERENCES referentiel.section (id, lot_id)
);
-- Une plantation n'est affectée qu'à une section à la fois.
CREATE UNIQUE INDEX ux_affectation_plantation_ouverte ON referentiel.affectation_plantation (plantation_ref)
    WHERE fin IS NULL AND supprime_le IS NULL;
CREATE INDEX ix_affectation_plantation_section ON referentiel.affectation_plantation (section_id)
    WHERE fin IS NULL AND supprime_le IS NULL;
SELECT util.attacher_horodatage('referentiel.affectation_plantation');

-- ---------------------------------------------------------------------------
-- Groupes de vulgarisation et participants
-- ---------------------------------------------------------------------------
-- Un groupe est dans une section, animé par un moniteur ; 20 à 25 planteurs d'un même village ou regroupement.
CREATE TABLE planning.groupe_vulgarisation (
    id                 uuid        PRIMARY KEY DEFAULT util.uuid_v7(),
    lot_id             uuid        NOT NULL,
    section_id         uuid        NOT NULL,
    nom                varchar(50) NOT NULL CHECK (length(btrim(nom)) > 0),
    village            varchar(80),
    delegue            varchar(80),
    moniteur_compte_id uuid        NOT NULL REFERENCES iam.compte (id),
    cree_le            timestamptz NOT NULL DEFAULT now(),
    cree_par           uuid,
    modifie_le         timestamptz NOT NULL DEFAULT now(),
    modifie_par        uuid,
    supprime_le        timestamptz,
    supprime_par       uuid,
    version            bigint      NOT NULL DEFAULT 0,
    FOREIGN KEY (section_id, lot_id) REFERENCES referentiel.section (id, lot_id)
);
CREATE UNIQUE INDEX ux_groupe_nom ON planning.groupe_vulgarisation (section_id, util.sans_accent(nom))
    WHERE supprime_le IS NULL;
CREATE INDEX ix_groupe_moniteur ON planning.groupe_vulgarisation (moniteur_compte_id) WHERE supprime_le IS NULL;
SELECT util.attacher_horodatage('planning.groupe_vulgarisation');

CREATE FUNCTION planning.verifier_groupe() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM iam.compte c
                  WHERE c.id = NEW.moniteur_compte_id AND c.profil = 'MO'
                    AND c.statut <> 'DESACTIVE' AND c.supprime_le IS NULL) THEN
    RAISE EXCEPTION 'Le responsable d''un groupe de vulgarisation doit être un moniteur actif.'
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER trg_groupe_verifier BEFORE INSERT OR UPDATE OF moniteur_compte_id ON planning.groupe_vulgarisation
    FOR EACH ROW EXECUTE FUNCTION planning.verifier_groupe();

-- Personne qui suit une activité : apprenti ou saigneur en formation, planteur d'un groupe.
-- Hypothèse : tenue dans SysDE ; `reference_externe` permettra de la rattacher à la base externe des planteurs.
CREATE TABLE planning.participant (
    id                uuid        PRIMARY KEY DEFAULT util.uuid_v7(),
    operateur_id      uuid        NOT NULL REFERENCES referentiel.operateur (id),
    nom               varchar(100) NOT NULL,
    prenoms           varchar(150) NOT NULL,
    telephone         varchar(30),
    village           varchar(80),
    reference_externe varchar(50),
    cree_le           timestamptz NOT NULL DEFAULT now(),
    cree_par          uuid,
    modifie_le        timestamptz NOT NULL DEFAULT now(),
    modifie_par       uuid,
    supprime_le       timestamptz,
    supprime_par      uuid,
    version           bigint      NOT NULL DEFAULT 0
);
CREATE INDEX ix_participant_operateur ON planning.participant (operateur_id, lower(nom)) WHERE supprime_le IS NULL;
SELECT util.attacher_horodatage('planning.participant');

-- Membres d'un groupe : liste connue dès le début de janvier, avec historique des départs.
CREATE TABLE planning.membre_groupe (
    id             uuid        PRIMARY KEY DEFAULT util.uuid_v7(),
    groupe_id      uuid        NOT NULL REFERENCES planning.groupe_vulgarisation (id),
    participant_id uuid        NOT NULL REFERENCES planning.participant (id),
    depuis         date        NOT NULL DEFAULT current_date,
    jusqu_au       date,
    CHECK (jusqu_au IS NULL OR jusqu_au >= depuis)
);
CREATE UNIQUE INDEX ux_membre_groupe_ouvert ON planning.membre_groupe (groupe_id, participant_id) WHERE jusqu_au IS NULL;
CREATE INDEX ix_membre_groupe_participant ON planning.membre_groupe (participant_id);

-- ---------------------------------------------------------------------------
-- Planning annuel
-- ---------------------------------------------------------------------------
CREATE TABLE planning.plan_annuel (
    id            uuid        PRIMARY KEY DEFAULT util.uuid_v7(),
    compte_id     uuid        NOT NULL REFERENCES iam.compte (id),
    annee         smallint    NOT NULL CHECK (annee BETWEEN 2000 AND 2100),
    statut        text        NOT NULL DEFAULT 'BROUILLON' CHECK (statut IN ('BROUILLON', 'SOUMIS', 'VALIDE')),
    soumis_le     timestamptz,
    valide_le     timestamptz,
    valide_par    uuid        REFERENCES iam.compte (id),
    rejet_motif   text,                                   -- dernier rejet : le plan repasse en brouillon
    rejet_le      timestamptz,
    saisi_par     text        NOT NULL DEFAULT 'TITULAIRE' CHECK (saisi_par IN ('TITULAIRE', 'SECRETAIRE')),
    saisi_le      timestamptz,
    cree_le       timestamptz NOT NULL DEFAULT now(),
    cree_par      uuid,
    modifie_le    timestamptz NOT NULL DEFAULT now(),
    modifie_par   uuid,
    supprime_le   timestamptz,
    supprime_par  uuid,
    version       bigint      NOT NULL DEFAULT 0,
    CHECK ((statut = 'VALIDE') = (valide_le IS NOT NULL)),
    CHECK (rejet_motif IS NULL OR length(btrim(rejet_motif)) > 0)
);
CREATE UNIQUE INDEX ux_plan_annuel ON planning.plan_annuel (compte_id, annee) WHERE supprime_le IS NULL;
SELECT util.attacher_horodatage('planning.plan_annuel');

-- Une ligne : une période (bornes incluses) pour une activité, sur un lot ou une section,
-- et la plantation visitée pour une visite. Pas d'heures.
CREATE TABLE planning.ligne_planning (
    id             uuid        PRIMARY KEY DEFAULT util.uuid_v7(),
    plan_id        uuid        NOT NULL REFERENCES planning.plan_annuel (id),
    activite_id    uuid        NOT NULL REFERENCES planning.activite (id),
    date_debut     date        NOT NULL,
    date_fin       date        NOT NULL,
    lot_id         uuid        REFERENCES referentiel.lot (id),
    section_id     uuid,
    plantation_ref varchar(50),
    groupe_id      uuid        REFERENCES planning.groupe_vulgarisation (id),
    cree_le        timestamptz NOT NULL DEFAULT now(),
    cree_par       uuid,
    modifie_le     timestamptz NOT NULL DEFAULT now(),
    modifie_par    uuid,
    supprime_le    timestamptz,
    supprime_par   uuid,
    version        bigint      NOT NULL DEFAULT 0,
    CHECK (date_fin >= date_debut),
    CHECK (section_id IS NULL OR lot_id IS NOT NULL),
    FOREIGN KEY (section_id, lot_id) REFERENCES referentiel.section (id, lot_id)
);
CREATE INDEX ix_ligne_plan ON planning.ligne_planning (plan_id) WHERE supprime_le IS NULL;
CREATE INDEX ix_ligne_dates ON planning.ligne_planning (date_debut, date_fin) WHERE supprime_le IS NULL;
CREATE INDEX ix_ligne_activite ON planning.ligne_planning (activite_id) WHERE supprime_le IS NULL;
SELECT util.attacher_horodatage('planning.ligne_planning');

-- Règles d'intégrité d'une ligne : dans l'année du plan, activité planifiable et propre au profil du
-- titulaire, visite en un seul jour (règle du 28/10/2026).
CREATE FUNCTION planning.verifier_ligne() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  v_annee     smallint;
  v_profil    char(2);
  v_categorie text;
  v_planifiee boolean;
  v_libelle   text;
BEGIN
  SELECT p.annee, c.profil INTO v_annee, v_profil
    FROM planning.plan_annuel p JOIN iam.compte c ON c.id = p.compte_id WHERE p.id = NEW.plan_id;
  SELECT a.categorie, a.planifiee, a.libelle INTO v_categorie, v_planifiee, v_libelle
    FROM planning.activite a WHERE a.id = NEW.activite_id;

  IF extract(year FROM NEW.date_debut) <> v_annee OR extract(year FROM NEW.date_fin) <> v_annee THEN
    RAISE EXCEPTION 'Les dates doivent être dans l''année du planning (%).', v_annee USING ERRCODE = 'check_violation';
  END IF;
  IF NOT v_planifiee THEN
    RAISE EXCEPTION 'L''activité « % » n''est pas planifiée : elle est faite de manière spontanée.', v_libelle
      USING ERRCODE = 'check_violation';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM planning.activite_profil ap
                  WHERE ap.activite_id = NEW.activite_id AND ap.profil = v_profil AND ap.supprime_le IS NULL) THEN
    RAISE EXCEPTION 'L''activité « % » n''est pas réalisée par le profil %.', v_libelle, v_profil
      USING ERRCODE = 'check_violation';
  END IF;
  IF v_categorie = 'VISITE' AND NEW.date_fin <> NEW.date_debut THEN
    RAISE EXCEPTION 'Une visite se fait en un seul jour.' USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER trg_ligne_verifier BEFORE INSERT OR UPDATE ON planning.ligne_planning
    FOR EACH ROW WHEN (NEW.supprime_le IS NULL) EXECUTE FUNCTION planning.verifier_ligne();

-- Soumission d'une activité seule au chef secteur ; ses dates sont verrouillées jusqu'à la décision.
CREATE TABLE planning.soumission_activite (
    id           uuid        PRIMARY KEY DEFAULT util.uuid_v7(),
    plan_id      uuid        NOT NULL REFERENCES planning.plan_annuel (id),
    activite_id  uuid        NOT NULL REFERENCES planning.activite (id),
    statut       text        NOT NULL DEFAULT 'SOUMIS' CHECK (statut IN ('SOUMIS', 'VALIDE', 'REJETE')),
    soumis_le    timestamptz NOT NULL DEFAULT now(),
    decide_le    timestamptz,
    decide_par   uuid        REFERENCES iam.compte (id),
    motif        text,
    cree_le      timestamptz NOT NULL DEFAULT now(),
    modifie_le   timestamptz NOT NULL DEFAULT now(),
    modifie_par  uuid,
    version      bigint      NOT NULL DEFAULT 0,
    UNIQUE (plan_id, activite_id),
    CHECK (statut <> 'REJETE' OR length(btrim(coalesce(motif, ''))) > 0),
    CHECK ((statut = 'SOUMIS') = (decide_le IS NULL))
);

-- ---------------------------------------------------------------------------
-- Réalisation (saisie sur tablette, hors ligne : l'identifiant peut être fourni par l'appareil)
-- ---------------------------------------------------------------------------
CREATE TABLE planning.realisation (
    id           uuid        PRIMARY KEY DEFAULT util.uuid_v7(),
    ligne_id     uuid        NOT NULL UNIQUE REFERENCES planning.ligne_planning (id),
    compte_id    uuid        NOT NULL REFERENCES iam.compte (id),   -- qui a réalisé l'activité
    statut       text        NOT NULL DEFAULT 'EN_COURS' CHECK (statut IN ('EN_COURS', 'TERMINEE')),
    debut_le     timestamptz NOT NULL,
    fin_le       timestamptz,
    latitude     numeric(9,6) CHECK (latitude BETWEEN -90 AND 90),
    longitude    numeric(9,6) CHECK (longitude BETWEEN -180 AND 180),
    precision_m  numeric(7,1) CHECK (precision_m >= 0),
    observations text        NOT NULL DEFAULT '',
    recu_le      timestamptz NOT NULL DEFAULT now(),                -- réception par le serveur
    cree_le      timestamptz NOT NULL DEFAULT now(),
    cree_par     uuid,
    modifie_le   timestamptz NOT NULL DEFAULT now(),
    modifie_par  uuid,
    supprime_le  timestamptz,
    supprime_par uuid,
    version      bigint      NOT NULL DEFAULT 0,
    CHECK (fin_le IS NULL OR fin_le >= debut_le),
    CHECK (statut <> 'TERMINEE' OR fin_le IS NOT NULL),
    CHECK ((latitude IS NULL) = (longitude IS NULL))
);
CREATE INDEX ix_realisation_compte ON planning.realisation (compte_id, debut_le DESC) WHERE supprime_le IS NULL;
SELECT util.attacher_horodatage('planning.realisation');

-- Liste de présence : une ligne par participant et par jour.
CREATE TABLE planning.presence (
    realisation_id uuid    NOT NULL REFERENCES planning.realisation (id),
    participant_id uuid    NOT NULL REFERENCES planning.participant (id),
    jour           date    NOT NULL,
    present        boolean NOT NULL,
    PRIMARY KEY (realisation_id, participant_id, jour)
);
CREATE INDEX ix_presence_participant ON planning.presence (participant_id);

-- Évaluation : une note par participant et par critère.
CREATE TABLE planning.evaluation (
    realisation_id uuid        NOT NULL REFERENCES planning.realisation (id),
    participant_id uuid        NOT NULL REFERENCES planning.participant (id),
    critere_code   varchar(30) NOT NULL REFERENCES planning.critere_evaluation (code),
    niveau         text        NOT NULL CHECK (niveau IN ('BON', 'MOYEN', 'MAUVAIS')),
    PRIMARY KEY (realisation_id, participant_id, critere_code)
);
CREATE INDEX ix_evaluation_participant ON planning.evaluation (participant_id);

-- Appréciation d'ensemble d'un participant (conforme ou non, avec commentaire).
CREATE TABLE planning.appreciation (
    realisation_id uuid NOT NULL REFERENCES planning.realisation (id),
    participant_id uuid NOT NULL REFERENCES planning.participant (id),
    verdict        text NOT NULL CHECK (verdict IN ('CONFORME', 'NON_CONFORME')),
    commentaire    text NOT NULL DEFAULT '',
    PRIMARY KEY (realisation_id, participant_id)
);

-- Rapport de l'activité (le format du contenu reste à définir).
CREATE TABLE planning.rapport (
    realisation_id uuid        PRIMARY KEY REFERENCES planning.realisation (id),
    contenu        text        NOT NULL,
    redige_le      timestamptz NOT NULL DEFAULT now(),
    redige_par     uuid        REFERENCES iam.compte (id)
);

-- ---------------------------------------------------------------------------
-- Droits, audit
-- ---------------------------------------------------------------------------
-- Suppressions réelles : saisies qui se corrigent (présence ou note retirée).
GRANT DELETE ON planning.presence, planning.evaluation, planning.appreciation, planning.membre_groupe TO sysde_app;
REVOKE INSERT, UPDATE ON planning.critere_evaluation FROM sysde_app;   -- données de référence maintenues par migration
GRANT EXECUTE ON FUNCTION util.sans_accent(text) TO sysde_app;

-- Audit des modifications de planification et de référentiel. Exclus : participants, présences, évaluations,
-- appréciations, rapports et réalisations (volumétrie élevée ; la réalisation porte déjà ses auteurs et dates).
DO $$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['planning.activite', 'planning.activite_profil', 'planning.plan_annuel',
                           'planning.ligne_planning', 'planning.soumission_activite',
                           'planning.groupe_vulgarisation', 'referentiel.affectation_plantation'] LOOP
    EXECUTE format('CREATE TRIGGER trg_audit AFTER INSERT OR UPDATE OR DELETE ON %s
                      FOR EACH ROW EXECUTE FUNCTION audit.enregistrer()', t);
  END LOOP;
END $$;

-- ---------------------------------------------------------------------------
-- Données de référence : activités du programme
-- ---------------------------------------------------------------------------
INSERT INTO planning.critere_evaluation (code, libelle, ordre) VALUES
  ('CONSOMMATION', 'Consommation', 1),
  ('PROFONDEUR',   'Profondeur',   2),
  ('PENTE',        'Pente',        3),
  ('BLESSURES',    'Blessures',    4),
  ('PROPRETE',     'Propreté',     5);

-- L'évaluation des visites est conservée à la demande du client, bien que le programme n'en parle pas.
INSERT INTO planning.activite
  (code, libelle, categorie, duree_jours, planifiee, avec_presence, avec_evaluation, avec_rapport,
   participants_min, participants_max, mois_debut, mois_fin, ordre) VALUES
  ('FORMATION_INITIALE',           'Formation initiale',            'FORMATION', 20, true,  true,  true,  true,  NULL, 20, NULL, NULL, 1),
  ('PERFECTIONNEMENT_IN_SITU',     'Perfectionnement in situ',      'FORMATION',  1, true,  true,  true,  true,  NULL, NULL, NULL, NULL, 2),
  ('SUIVI_POST_PERFECTIONNEMENT',  'Suivi post-perfectionnement',   'FORMATION',  1, true,  true,  true,  true,  NULL, NULL, NULL, NULL, 3),
  ('RECYCLAGE_SAIGNEURS',          'Recyclage des saigneurs',       'FORMATION',  3, true,  true,  true,  true,  NULL, NULL, 2, 3, 4),
  ('VISITE_PLANTATION',            'Visite de plantation',          'VISITE',     1, true,  true,  true,  true,  NULL, NULL, NULL, NULL, 5),
  ('VISITE_INTERVENTION',          'Visite d''intervention',        'VISITE',     1, true,  true,  true,  true,  NULL, NULL, NULL, NULL, 6),
  ('VISITE_CONFORMITE',            'Visite de conformité',          'VISITE',     1, true,  true,  true,  true,  NULL, NULL, NULL, NULL, 7),
  ('DECOUVERTE_VERGER',            'Découverte du verger',          'VISITE',     1, false, false, false, false, NULL, NULL, NULL, NULL, 8),
  ('GROUPE_VULGARISATION',         'Groupe de vulgarisation',       'RENCONTRE',  1, true,  true,  false, true,  20, 25, NULL, NULL, 9),
  ('CONFERENCE_THEMATIQUE',        'Conférence thématique',         'RENCONTRE',  1, true,  true,  false, true,  NULL, NULL, NULL, NULL, 10),
  ('VOYAGE_ETUDE',                 'Voyage d''étude',               'RENCONTRE',  1, true,  true,  false, true,  NULL, NULL, NULL, NULL, 11);

INSERT INTO planning.activite_profil (activite_id, profil, nombre, periode, par, superficie_min_ha, superficie_max_ha, regle)
SELECT a.id, v.profil, v.nombre, v.periode, v.par, v.smin, v.smax, v.regle
  FROM (VALUES
    ('FORMATION_INITIALE', 'FS', 12, 'ANNEE', 'LOT', NULL::numeric, NULL::numeric,
        'Une session par mois dans son lot. Chaque session dure 20 jours et accueille 20 apprentis.'),
    ('FORMATION_INITIALE', 'CF',  2, 'ANNEE', 'LOT', NULL, NULL,
        'Une session par semestre dans son lot.'),
    ('PERFECTIONNEMENT_IN_SITU', 'MO',  3, 'MOIS', NULL, NULL, NULL,
        'Perfectionne 3 saigneurs par mois dans sa section, 1 par semaine pendant 3 semaines. Les saigneurs ne sont pas connus à l''avance.'),
    ('PERFECTIONNEMENT_IN_SITU', 'CF',  9, 'MOIS', NULL, NULL, NULL,
        'Perfectionne 9 saigneurs par mois dans son lot : 3 par semaine pendant 3 semaines. Les saigneurs ne sont pas connus à l''avance.'),
    ('SUIVI_POST_PERFECTIONNEMENT', 'CF', NULL, 'MOIS', NULL, NULL, NULL,
        'Chaque mois : la moitié des moniteurs du lot, tirés au hasard, puis 1 saigneur ayant suivi le perfectionnement in situ, tiré au hasard chez chacun d''eux.'),
    ('RECYCLAGE_SAIGNEURS', 'CF',  1, 'ANNEE', NULL, NULL, NULL,
        'Une fois par an, sur 3 jours, généralement de février à mars. Concerne les saigneurs défaillants (moins de 3 critères de qualité maîtrisés), identifiés par le moniteur en visite de plantation.'),
    ('VISITE_PLANTATION', 'MO',  4, 'ANNEE', 'PLANTATION', NULL, 25,
        'Une visite par trimestre de la même plantation. Plantations de moins de 25 ha de sa section.'),
    ('VISITE_PLANTATION', 'CO',  3, 'ANNEE', 'PLANTATION', 25, 50,
        'Une visite par trimestre de la même plantation, sur 3 trimestres. Plantations de 25 à 50 ha. Une des visites est faite par le chef secteur, pour faire le point.'),
    ('VISITE_PLANTATION', 'CS',  3, 'ANNEE', 'PLANTATION', 50, NULL,
        'Une visite par trimestre de la même plantation, sur 3 trimestres. Plantations de plus de 50 ha de son secteur.'),
    ('VISITE_INTERVENTION', 'ES',  2, 'ANNEE', 'PLANTATION', NULL, NULL,
        'Une visite par semestre de la même plantation. L''équipe intervient sur l''ensemble des plantations du secteur.'),
    ('VISITE_CONFORMITE', 'CO',  6, 'MOIS', NULL, NULL, NULL,
        '6 visites par mois : une plantation prise au hasard chez chacun des 6 moniteurs sous sa responsabilité.'),
    ('DECOUVERTE_VERGER', 'MO', NULL, NULL, NULL, NULL, NULL,
        'Géolocalisation d''une plantation. Non planifiée : faite de manière spontanée.'),
    ('GROUPE_VULGARISATION', 'MO',  4, 'ANNEE', 'GROUPE', NULL, NULL,
        'Une animation par trimestre du même groupe. Chaque moniteur anime 6 groupes ; la liste des membres est connue dès le début de janvier.'),
    ('CONFERENCE_THEMATIQUE', 'CS',  4, 'ANNEE', NULL, NULL, NULL,
        'Une conférence par trimestre.'),
    ('VOYAGE_ETUDE', 'CS',  4, 'ANNEE', NULL, NULL, NULL,
        'Un voyage par trimestre.')
  ) AS v(code, profil, nombre, periode, par, smin, smax, regle)
  JOIN planning.activite a ON a.code = v.code;
