-- ============================================================================
-- Tests des règles métier portées par la base (migrations V1 à V7).
-- Exécution : psql -v ON_ERROR_STOP=1 -d <base vierge migrée> -f regles_metier.sql
-- Chaque test affiche OK ou ÉCHEC ; le script se termine en erreur s'il y a un échec.
-- Les dates du jeu d'essai sont relatives à l'année courante (pg_temp.an()) : contrats antérieurs
-- (an-3 à an-1), en cours (an à an+2) et à venir (an+3 à an+5), pour couvrir plusieurs cas.
-- ============================================================================
\set ON_ERROR_STOP on
\if :{?AFFICHER_TOUT}
\else
  \set AFFICHER_TOUT non
\endif
\pset format unaligned
\pset tuples_only on

CREATE TEMP TABLE resultats (nom text, ok boolean, detail text);
CREATE TEMP TABLE ids (cle text PRIMARY KEY, id uuid NOT NULL);

CREATE FUNCTION pg_temp.id(p_cle text) RETURNS uuid LANGUAGE sql AS
  $$ SELECT id FROM ids WHERE cle = p_cle $$;
CREATE FUNCTION pg_temp.garder(p_cle text, p_id uuid) RETURNS void LANGUAGE sql AS
  $$ INSERT INTO ids VALUES (p_cle, p_id) ON CONFLICT (cle) DO UPDATE SET id = EXCLUDED.id $$;
-- Le compte connecté pour les instructions suivantes (clé inconnue : aucun acteur).
CREATE FUNCTION pg_temp.acteur(p_cle text) RETURNS void LANGUAGE sql AS
  $$ SELECT set_config('app.compte_id', coalesce(pg_temp.id(p_cle)::text, ''), false) $$;
-- Année de référence et dates relatives : le jeu d'essai ne dépend pas de la date d'exécution.
CREATE FUNCTION pg_temp.an() RETURNS integer LANGUAGE sql AS $$ SELECT extract(year FROM current_date)::integer $$;
CREATE FUNCTION pg_temp.d(p_annees integer, p_mois integer, p_jour integer) RETURNS date LANGUAGE sql AS
  $$ SELECT make_date(pg_temp.an() + p_annees, p_mois, p_jour) $$;
CREATE FUNCTION pg_temp.motif(p_motif text) RETURNS void LANGUAGE sql AS
  $$ SELECT set_config('app.motif', p_motif, false) $$;

-- Réussite attendue.
CREATE FUNCTION pg_temp.ok(p_nom text, p_sql text) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
  EXECUTE p_sql;
  EXECUTE 'SET CONSTRAINTS ALL IMMEDIATE';
  EXECUTE 'RESET ROLE';
  INSERT INTO pg_temp.resultats VALUES (p_nom, true, NULL);
EXCEPTION WHEN OTHERS THEN
  INSERT INTO pg_temp.resultats VALUES (p_nom, false, 'erreur inattendue : ' || SQLERRM);
END $$;

-- Refus attendu, avec un extrait du message. Les effets sont annulés.
CREATE FUNCTION pg_temp.echec(p_nom text, p_sql text, p_attendu text) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
  EXECUTE p_sql;
  EXECUTE 'SET CONSTRAINTS ALL IMMEDIATE';
  RAISE EXCEPTION 'SANS_ERREUR';
EXCEPTION WHEN OTHERS THEN
  IF SQLERRM = 'SANS_ERREUR' THEN
    INSERT INTO pg_temp.resultats VALUES (p_nom, false, 'aucune erreur alors qu''un refus était attendu');
  ELSIF position(p_attendu IN SQLERRM) > 0 THEN
    INSERT INTO pg_temp.resultats VALUES (p_nom, true, NULL);
  ELSE
    INSERT INTO pg_temp.resultats VALUES (p_nom, false, 'message reçu : ' || SQLERRM);
  END IF;
END $$;

CREATE FUNCTION pg_temp.verifie(p_nom text, p_condition boolean) RETURNS void LANGUAGE sql AS
  $$ INSERT INTO pg_temp.resultats VALUES (p_nom, coalesce(p_condition, false), CASE WHEN coalesce(p_condition, false) THEN NULL ELSE 'condition fausse' END) $$;

-- Crée une personne et son compte (actif, mot de passe défini). Le compte est gardé sous p_cle,
-- la personne sous 'u_' || p_cle. La hiérarchie de création s'applique au compte connecté.
CREATE FUNCTION pg_temp.compte(p_op uuid, p_profil char(2), p_cle text, p_numero text DEFAULT NULL) RETURNS uuid LANGUAGE plpgsql AS $$
DECLARE v_u uuid; v_c uuid;
BEGIN
  INSERT INTO iam.utilisateur (nom, prenoms) VALUES ('Nom ' || p_cle, 'Prénom') RETURNING id INTO v_u;
  INSERT INTO iam.compte (utilisateur_id, operateur_id, profil, numero, statut, mot_de_passe_hash)
  VALUES (v_u, p_op, p_profil, p_numero, 'ACTIF', 'hash') RETURNING id INTO v_c;
  PERFORM pg_temp.garder('u_' || p_cle, v_u);
  PERFORM pg_temp.garder(p_cle, v_c);
  RETURN v_c;
END $$;

-- Affecte un compte à un territoire (début : 1er janvier de l'année courante, sauf indication).
CREATE FUNCTION pg_temp.affecter(p_cle text, p_secteur text, p_lot text DEFAULT NULL, p_section text DEFAULT NULL,
                                 p_debut date DEFAULT NULL) RETURNS void LANGUAGE plpgsql AS $$
DECLARE v_compte iam.compte;
BEGIN
  SELECT * INTO v_compte FROM iam.compte WHERE id = pg_temp.id(p_cle);
  IF NOT FOUND THEN RAISE EXCEPTION 'Test mal écrit : aucun compte pour %', p_cle; END IF;
  INSERT INTO iam.affectation (compte_id, operateur_id, secteur_id, lot_id, section_id, debut)
  VALUES (v_compte.id, v_compte.operateur_id, pg_temp.id(p_secteur), pg_temp.id(p_lot), pg_temp.id(p_section),
          coalesce(p_debut, pg_temp.d(0, 1, 1)));
END $$;

-- ---------------------------------------------------------------------------
-- 0. Identifiants UUID v7
-- ---------------------------------------------------------------------------
SELECT pg_temp.verifie('uuid_v7 : version 7', substr(util.uuid_v7()::text, 15, 1) = '7');
SELECT pg_temp.verifie('uuid_v7 : variante RFC', substr(util.uuid_v7()::text, 20, 1) IN ('8','9','a','b'));
-- L'ordre est garanti à la milliseconde (RFC 9562, sans compteur) : on espace donc les tirages.
CREATE FUNCTION pg_temp.uuid_ordonnes() RETURNS boolean LANGUAGE plpgsql AS $$
DECLARE v uuid[] := '{}'; i int;
BEGIN
  FOR i IN 1..10 LOOP v := v || util.uuid_v7(); PERFORM pg_sleep(0.003); END LOOP;
  RETURN v = (SELECT array_agg(x ORDER BY x) FROM unnest(v) x);
END $$;
SELECT pg_temp.verifie('uuid_v7 : ordre croissant dans le temps', pg_temp.uuid_ordonnes());

-- ---------------------------------------------------------------------------
-- 1. Utilisateurs, comptes, hiérarchie de création, matricules
-- ---------------------------------------------------------------------------
SELECT pg_temp.garder('firca',   (SELECT id FROM referentiel.operateur WHERE acronyme = 'FIRCA'));
SELECT pg_temp.garder('apromac', (SELECT id FROM referentiel.operateur WHERE acronyme = 'APROMAC'));

SELECT pg_temp.acteur('inconnu');   -- aucun acteur : amorçage
SELECT pg_temp.ok('amorçage : premier administrateur sans acteur (numéro repris du système existant)',
  $$ SELECT pg_temp.compte(pg_temp.id('firca'), 'AD', 'ad_firca', '00001') $$);
SELECT pg_temp.ok('amorçage : administrateur APROMAC',
  $$ SELECT pg_temp.compte(pg_temp.id('apromac'), 'AD', 'ad_apromac', '00001') $$);
SELECT pg_temp.verifie('matricule = acronyme + profil + numéro',
  (SELECT matricule = 'FIRCA-AD-00001' FROM iam.compte WHERE id = pg_temp.id('ad_firca')));
SELECT pg_temp.verifie('référence du compte = matricule (calculée)',
  (SELECT reference_compte = 'FIRCA-AD-00001' FROM iam.compte WHERE id = pg_temp.id('ad_firca')));
SELECT pg_temp.verifie('le même numéro existe dans deux organisations',
  (SELECT count(DISTINCT operateur_id) = 2 FROM iam.compte WHERE numero = '00001'));

SELECT pg_temp.acteur('ad_firca');
INSERT INTO referentiel.operateur (type, acronyme, denomination) VALUES ('TECHNIQUE', 'SAPH', 'SAPH'), ('TECHNIQUE', 'SOGB', 'SOGB');
SELECT pg_temp.garder('saph', (SELECT id FROM referentiel.operateur WHERE acronyme = 'SAPH'));
SELECT pg_temp.garder('sogb', (SELECT id FROM referentiel.operateur WHERE acronyme = 'SOGB'));

SELECT pg_temp.acteur('inconnu');
SELECT pg_temp.echec('création d''un CD sans compte connecté',
  $$ SELECT pg_temp.compte(pg_temp.id('saph'), 'CD', 'x') $$, 'sans compte connecté');

SELECT pg_temp.acteur('ad_firca');
SELECT pg_temp.ok('AD crée un CD (SAPH), numéro attribué automatiquement',
  $$ SELECT pg_temp.compte(pg_temp.id('saph'), 'CD', 'cd_saph') $$);
SELECT pg_temp.ok('AD crée un CD (SOGB)',
  $$ SELECT pg_temp.compte(pg_temp.id('sogb'), 'CD', 'cd_sogb') $$);
SELECT pg_temp.verifie('premier numéro automatique d''un opérateur : 00001',
  (SELECT matricule = 'SAPH-CD-00001' FROM iam.compte WHERE id = pg_temp.id('cd_saph')));
SELECT pg_temp.echec('AD ne peut pas créer un CS',
  $$ SELECT pg_temp.compte(pg_temp.id('saph'), 'CS', 'x') $$, 'hiérarchie');
SELECT pg_temp.ok('AD crée un superviseur de son organisation',
  $$ SELECT pg_temp.compte(pg_temp.id('firca'), 'SU', 'su_firca') $$);
SELECT pg_temp.ok('AD crée un second administrateur de son organisation',
  $$ SELECT pg_temp.compte(pg_temp.id('firca'), 'AD', 'ad2_firca') $$);
SELECT pg_temp.echec('isolation : AD FIRCA ne crée pas dans APROMAC',
  $$ SELECT pg_temp.compte(pg_temp.id('apromac'), 'SU', 'x') $$, 'même organisation');
SELECT pg_temp.echec('un profil d''organisme ne se crée pas chez un opérateur technique',
  $$ SELECT pg_temp.compte(pg_temp.id('saph'), 'SU', 'x') $$, 'réservé aux organismes');
SELECT pg_temp.echec('un profil d''opérateur ne se crée pas dans un organisme',
  $$ SELECT pg_temp.compte(pg_temp.id('firca'), 'CD', 'x') $$, 'réservé aux opérateurs techniques');

SELECT pg_temp.acteur('cd_saph');
SELECT pg_temp.ok('CD crée un CS',
  $$ SELECT pg_temp.compte(pg_temp.id('saph'), 'CS', 'cs_saph') $$);
SELECT pg_temp.ok('CD crée un second CS (plusieurs CS par opérateur)',
  $$ SELECT pg_temp.compte(pg_temp.id('saph'), 'CS', 'cs2_saph') $$);
SELECT pg_temp.echec('CD ne peut pas créer un technicien',
  $$ SELECT pg_temp.compte(pg_temp.id('saph'), 'MO', 'x') $$, 'hiérarchie');
SELECT pg_temp.echec('CD ne crée pas chez un autre opérateur',
  $$ SELECT pg_temp.compte(pg_temp.id('sogb'), 'CS', 'x') $$, 'même organisation');

SELECT pg_temp.acteur('cd_sogb');
SELECT pg_temp.ok('CD SOGB crée son CS',
  $$ SELECT pg_temp.compte(pg_temp.id('sogb'), 'CS', 'cs_sogb') $$);

SELECT pg_temp.acteur('cs_saph');
SELECT pg_temp.ok('CS crée un moniteur avec le numéro de l''opérateur (00348)',
  $$ SELECT pg_temp.compte(pg_temp.id('saph'), 'MO', 'mo_saph', '00348') $$);
SELECT pg_temp.ok('CS crée un second moniteur (même profil, deux comptes), numéro automatique',
  $$ SELECT pg_temp.compte(pg_temp.id('saph'), 'MO', 'mo2_saph') $$);
SELECT pg_temp.verifie('le numéro automatique suit le plus grand numéro repris (00349 après 00348)',
  (SELECT matricule = 'SAPH-MO-00349' FROM iam.compte WHERE id = pg_temp.id('mo2_saph')));
SELECT pg_temp.ok('CS crée un contrôleur ordinaire',
  $$ SELECT pg_temp.compte(pg_temp.id('saph'), 'CO', 'co_saph') $$);
SELECT pg_temp.ok('CS crée un formateur de saignée',
  $$ SELECT pg_temp.compte(pg_temp.id('saph'), 'FS', 'fs_saph') $$);
SELECT pg_temp.ok('CS crée un second formateur de saignée',
  $$ SELECT pg_temp.compte(pg_temp.id('saph'), 'FS', 'fs2_saph') $$);
SELECT pg_temp.ok('CS crée un contrôleur de saignée',
  $$ SELECT pg_temp.compte(pg_temp.id('saph'), 'CF', 'cf_saph') $$);
SELECT pg_temp.ok('CS crée une équipe spéciale',
  $$ SELECT pg_temp.compte(pg_temp.id('saph'), 'ES', 'es_saph') $$);
SELECT pg_temp.ok('CS crée une secrétaire',
  $$ SELECT pg_temp.compte(pg_temp.id('saph'), 'SE', 'se_saph') $$);
SELECT pg_temp.echec('CS ne peut pas créer un CS',
  $$ SELECT pg_temp.compte(pg_temp.id('saph'), 'CS', 'x') $$, 'hiérarchie');
SELECT pg_temp.echec('CS ne crée pas chez un autre opérateur',
  $$ SELECT pg_temp.compte(pg_temp.id('sogb'), 'MO', 'x') $$, 'même organisation');
SELECT pg_temp.echec('l''ancien profil FO n''existe plus (remplacé par FS)',
  $$ SELECT pg_temp.compte(pg_temp.id('saph'), 'FO', 'x') $$, 'hiérarchie');
SELECT pg_temp.echec('le profil DI n''existe plus (remplacé par SU)',
  $$ SELECT pg_temp.compte(pg_temp.id('firca'), 'DI', 'x') $$, 'hiérarchie');
SELECT pg_temp.verifie('dix profils exactement',
  (SELECT count(*) = 10 AND bool_and(code IN ('AD','SU','CD','CS','CF','CO','FS','MO','ES','SE')) FROM iam.profil));

SELECT pg_temp.echec('numéro unique dans l''organisation, tous profils confondus',
  $$ SELECT pg_temp.compte(pg_temp.id('saph'), 'CO', 'x', '00348') $$, 'operateur_id_numero_key');
SELECT pg_temp.echec('numéro : trop court',
  $$ SELECT pg_temp.compte(pg_temp.id('saph'), 'CO', 'x', '123') $$, 'check constraint');
SELECT pg_temp.echec('numéro : lettres refusées',
  $$ SELECT pg_temp.compte(pg_temp.id('saph'), 'CO', 'x', 'AB123') $$, 'check constraint');
SELECT pg_temp.ok('numéro : sept chiffres acceptés',
  $$ SELECT pg_temp.compte(pg_temp.id('saph'), 'CO', 'co7_saph', '1234567') $$);
SELECT pg_temp.echec('un utilisateur n''a qu''un compte actif à la fois',
  $$ INSERT INTO iam.compte (utilisateur_id, operateur_id, profil, statut, mot_de_passe_hash)
     VALUES (pg_temp.id('u_mo_saph'), pg_temp.id('saph'), 'MO', 'ACTIF', 'h') $$, 'ux_compte_actif');
SELECT pg_temp.echec('un compte actif doit avoir un mot de passe',
  $$ INSERT INTO iam.utilisateur (nom, prenoms) VALUES ('X', 'Y');
     INSERT INTO iam.compte (utilisateur_id, operateur_id, profil, statut)
     SELECT id, pg_temp.id('saph'), 'MO', 'ACTIF' FROM iam.utilisateur WHERE nom = 'X' $$, 'ck_compte_actif_mot_de_passe');
SELECT pg_temp.ok('un compte créé est en attente d''activation et ne peut pas se connecter',
  $$ INSERT INTO iam.utilisateur (nom, prenoms) VALUES ('Attente', 'Compte');
     INSERT INTO iam.compte (utilisateur_id, operateur_id, profil)
     SELECT id, pg_temp.id('saph'), 'MO' FROM iam.utilisateur WHERE nom = 'Attente' $$);
SELECT pg_temp.verifie('compte en attente : statut EN_ATTENTE et connexion refusée',
  (SELECT c.statut = 'EN_ATTENTE' AND NOT iam.compte_peut_se_connecter(c.id)
     FROM iam.compte c JOIN iam.utilisateur u ON u.id = c.utilisateur_id WHERE u.nom = 'Attente'));
SELECT pg_temp.verifie('compte actif : connexion autorisée',
  iam.compte_peut_se_connecter(pg_temp.id('mo_saph')));
SELECT pg_temp.echec('un compte reste attaché à son utilisateur',
  $$ UPDATE iam.compte SET utilisateur_id = pg_temp.id('u_mo2_saph') WHERE id = pg_temp.id('mo_saph') $$, 'reste attaché');
SELECT pg_temp.echec('un compte ne change pas d''organisation',
  $$ UPDATE iam.compte SET operateur_id = pg_temp.id('sogb') WHERE id = pg_temp.id('mo_saph') $$, 'ne change pas d''organisation');

-- Les permissions pilotent la création : retirer « créer un compte » au CS l'empêche de créer.
SELECT pg_temp.acteur('ad_firca');
DELETE FROM iam.profil_permission WHERE profil = 'CS' AND permission = 'CREER_COMPTE';
SELECT pg_temp.acteur('cs_saph');
SELECT pg_temp.echec('permission retirée au profil : le CS ne peut plus créer de compte',
  $$ SELECT pg_temp.compte(pg_temp.id('saph'), 'MO', 'x') $$, 'permission de créer');
SELECT pg_temp.acteur('ad_firca');
INSERT INTO iam.profil_permission VALUES ('CS', 'CREER_COMPTE');
SELECT pg_temp.acteur('cs_saph');
SELECT pg_temp.ok('permission rétablie : le CS crée de nouveau',
  $$ SELECT pg_temp.compte(pg_temp.id('saph'), 'MO', 'mo_tmp') $$);

SELECT pg_temp.acteur('cs_sogb');
SELECT pg_temp.ok('CS SOGB crée un formateur de saignée',
  $$ SELECT pg_temp.compte(pg_temp.id('sogb'), 'FS', 'fs_sogb') $$);

SELECT pg_temp.echec('acronyme : figé dès qu''un compte est rattaché',
  $$ UPDATE referentiel.operateur SET acronyme = 'SAPH2' WHERE id = pg_temp.id('saph') $$, 'ne peut plus changer');
SELECT pg_temp.echec('acronyme : format invalide',
  $$ INSERT INTO referentiel.operateur (type, acronyme, denomination) VALUES ('TECHNIQUE', 'saph', 'x') $$, 'operateur_acronyme_check');
SELECT pg_temp.ok('opérateur sans compte : acronyme encore modifiable, adresse enregistrée',
  $$ INSERT INTO referentiel.operateur (type, acronyme, denomination, adresse) VALUES ('TECHNIQUE', 'TMP', 'Temporaire', 'Abidjan');
     UPDATE referentiel.operateur SET acronyme = 'TMP2' WHERE acronyme = 'TMP' $$);

-- ---------------------------------------------------------------------------
-- 2. Référentiel territorial
-- ---------------------------------------------------------------------------
SELECT pg_temp.acteur('ad_firca');
INSERT INTO referentiel.secteur (code, nom) VALUES ('S01', 'Secteur 01'), ('S02', 'Secteur 02');
SELECT pg_temp.garder('s01', (SELECT id FROM referentiel.secteur WHERE code = 'S01'));
SELECT pg_temp.garder('s02', (SELECT id FROM referentiel.secteur WHERE code = 'S02'));
INSERT INTO referentiel.lot (secteur_id, numero)
SELECT pg_temp.id(s), n FROM (VALUES ('s01'), ('s02')) AS x(s), generate_series(1, 3) AS n;
SELECT pg_temp.garder('s01_l' || numero, id) FROM referentiel.lot WHERE secteur_id = pg_temp.id('s01');
SELECT pg_temp.garder('s02_l' || numero, id) FROM referentiel.lot WHERE secteur_id = pg_temp.id('s02');


SELECT pg_temp.echec('secteur : code en double',
  $$ INSERT INTO referentiel.secteur (code, nom) VALUES ('S01', 'Autre') $$, 'ux_secteur_code');
SELECT pg_temp.echec('lot : numéro en double dans un secteur',
  $$ INSERT INTO referentiel.lot (secteur_id, numero) VALUES (pg_temp.id('s01'), 2) $$, 'ux_lot_numero');
SELECT pg_temp.ok('lot : un quatrième lot est possible (le nombre peut évoluer)',
  $$ INSERT INTO referentiel.lot (secteur_id, numero) VALUES (pg_temp.id('s02'), 4) $$);
SELECT pg_temp.garder('s02_l4', id) FROM referentiel.lot WHERE secteur_id = pg_temp.id('s02') AND numero = 4;

SELECT pg_temp.acteur('cs_saph');
INSERT INTO referentiel.section (lot_id, numero)
SELECT pg_temp.id(l), n FROM (VALUES ('s01_l1'), ('s01_l3'), ('s02_l1')) AS x(l), generate_series(1, 7) AS n;
SELECT pg_temp.garder('s01_l1_sec' || numero, id) FROM referentiel.section WHERE lot_id = pg_temp.id('s01_l1');
SELECT pg_temp.garder('s01_l3_sec1', id) FROM referentiel.section WHERE lot_id = pg_temp.id('s01_l3') AND numero = 1;
SELECT pg_temp.garder('s02_l1_sec1', id) FROM referentiel.section WHERE lot_id = pg_temp.id('s02_l1') AND numero = 1;
SELECT pg_temp.echec('section : numéro en double dans un lot',
  $$ INSERT INTO referentiel.section (lot_id, numero) VALUES (pg_temp.id('s01_l1'), 3) $$, 'ux_section_numero');

SELECT pg_temp.acteur('ad_firca');
INSERT INTO referentiel.secteur (code, nom) VALUES ('S99', 'À supprimer');
UPDATE referentiel.secteur SET supprime_le = now() WHERE code = 'S99';
SELECT pg_temp.ok('suppression logique : le code peut être réutilisé',
  $$ INSERT INTO referentiel.secteur (code, nom) VALUES ('S99', 'Recréé') $$);

-- ---------------------------------------------------------------------------
-- 3. Contrats, attributions, exclusivité des lots
-- ---------------------------------------------------------------------------
INSERT INTO gestion.contrat (reference, operateur_id, organisme_mandant_id, date_debut, date_fin) VALUES
  ('C-SAPH-COURS', pg_temp.id('saph'), pg_temp.id('firca'), pg_temp.d(0,1,1), pg_temp.d(2,12,31)),
  ('C-SOGB-COURS', pg_temp.id('sogb'), pg_temp.id('firca'), pg_temp.d(0,1,1), pg_temp.d(2,12,31));
SELECT pg_temp.garder('c_saph', (SELECT id FROM gestion.contrat WHERE reference = 'C-SAPH-COURS'));
SELECT pg_temp.garder('c_sogb', (SELECT id FROM gestion.contrat WHERE reference = 'C-SOGB-COURS'));

SELECT pg_temp.echec('contrat : chevauchement pour un même opérateur',
  $$ INSERT INTO gestion.contrat (reference, operateur_id, organisme_mandant_id, date_debut, date_fin)
     VALUES ('C-SAPH-X', pg_temp.id('saph'), pg_temp.id('firca'), pg_temp.d(2,6,1), pg_temp.d(4,12,31)) $$, 'ex_contrat_chevauchement');
SELECT pg_temp.echec('contrat : réservé aux opérateurs techniques',
  $$ INSERT INTO gestion.contrat (reference, operateur_id, organisme_mandant_id, date_debut, date_fin)
     VALUES ('C-FIRCA', pg_temp.id('firca'), pg_temp.id('firca'), pg_temp.d(0,1,1), pg_temp.d(2,12,31)) $$, 'contrat_operateur_id_operateur_type_fkey');
SELECT pg_temp.echec('contrat : le mandant doit être un organisme, pas un opérateur technique',
  $$ INSERT INTO gestion.contrat (reference, operateur_id, organisme_mandant_id, date_debut, date_fin)
     VALUES ('C-MAND', pg_temp.id('sogb'), pg_temp.id('saph'), pg_temp.d(5,1,1), pg_temp.d(7,12,31)) $$, 'organisme_mandant_id_organisme_mandant_type_fkey');
SELECT pg_temp.verifie('contrat : l''organisme mandant est enregistré',
  (SELECT organisme_mandant_id = pg_temp.id('firca') FROM gestion.contrat WHERE id = pg_temp.id('c_saph')));
SELECT pg_temp.echec('contrat : la fin doit suivre le début',
  $$ INSERT INTO gestion.contrat (reference, operateur_id, organisme_mandant_id, date_debut, date_fin)
     VALUES ('C-BAD', pg_temp.id('sogb'), pg_temp.id('firca'), pg_temp.d(5,1,1), pg_temp.d(5,1,1)) $$, 'contrat_check');

INSERT INTO gestion.attribution (contrat_id, secteur_id) VALUES
  (pg_temp.id('c_saph'), pg_temp.id('s01')), (pg_temp.id('c_saph'), pg_temp.id('s02')), (pg_temp.id('c_sogb'), pg_temp.id('s01'));
SELECT pg_temp.garder('at_saph_s01', id) FROM gestion.attribution WHERE contrat_id = pg_temp.id('c_saph') AND secteur_id = pg_temp.id('s01');
SELECT pg_temp.garder('at_saph_s02', id) FROM gestion.attribution WHERE contrat_id = pg_temp.id('c_saph') AND secteur_id = pg_temp.id('s02');
SELECT pg_temp.garder('at_sogb_s01', id) FROM gestion.attribution WHERE contrat_id = pg_temp.id('c_sogb') AND secteur_id = pg_temp.id('s01');
SELECT pg_temp.verifie('attribution : l''opérateur est recopié du contrat',
  (SELECT operateur_id = pg_temp.id('saph') FROM gestion.attribution WHERE id = pg_temp.id('at_saph_s01')));
SELECT pg_temp.echec('attribution : doublon contrat / secteur',
  $$ INSERT INTO gestion.attribution (contrat_id, secteur_id) VALUES (pg_temp.id('c_saph'), pg_temp.id('s01')) $$, 'ux_attribution_contrat_secteur');

-- Secteur partagé : SAPH lots 1 et 2, SOGB lot 3.
INSERT INTO gestion.attribution_lot (attribution_id, contrat_id, secteur_id, lot_id)
SELECT pg_temp.id('at_saph_s01'), pg_temp.id('c_saph'), pg_temp.id('s01'), pg_temp.id(l) FROM (VALUES ('s01_l1'), ('s01_l2')) AS x(l);
INSERT INTO gestion.attribution_lot (attribution_id, contrat_id, secteur_id, lot_id)
VALUES (pg_temp.id('at_sogb_s01'), pg_temp.id('c_sogb'), pg_temp.id('s01'), pg_temp.id('s01_l3'));
INSERT INTO gestion.attribution_lot (attribution_id, contrat_id, secteur_id, lot_id)
SELECT pg_temp.id('at_saph_s02'), pg_temp.id('c_saph'), pg_temp.id('s02'), pg_temp.id(l) FROM (VALUES ('s02_l1'), ('s02_l2'), ('s02_l3')) AS x(l);

SELECT pg_temp.verifie('secteur partagé entre deux opérateurs : accepté',
  (SELECT count(DISTINCT contrat_id) = 2 FROM gestion.attribution_lot WHERE secteur_id = pg_temp.id('s01')));
SELECT pg_temp.echec('exclusivité : un lot déjà attribué ne peut aller à un autre opérateur',
  $$ INSERT INTO gestion.attribution_lot (attribution_id, contrat_id, secteur_id, lot_id)
     VALUES (pg_temp.id('at_sogb_s01'), pg_temp.id('c_sogb'), pg_temp.id('s01'), pg_temp.id('s01_l1')) $$, 'ex_lot_exclusif');
SELECT pg_temp.echec('un lot d''un autre secteur ne peut rejoindre l''attribution',
  $$ INSERT INTO gestion.attribution_lot (attribution_id, contrat_id, secteur_id, lot_id)
     VALUES (pg_temp.id('at_sogb_s01'), pg_temp.id('c_sogb'), pg_temp.id('s01'), pg_temp.id('s02_l4')) $$, 'violates foreign key');

-- Un contrat ultérieur de SAPH peut reprendre le lot 3 une fois celui de SOGB terminé…
INSERT INTO gestion.contrat (reference, operateur_id, organisme_mandant_id, date_debut, date_fin)
VALUES ('C-SAPH-FUTUR', pg_temp.id('saph'), pg_temp.id('firca'), pg_temp.d(3,1,1), pg_temp.d(5,12,31));
INSERT INTO gestion.attribution (contrat_id, secteur_id)
SELECT id, pg_temp.id('s01') FROM gestion.contrat WHERE reference = 'C-SAPH-FUTUR';
INSERT INTO gestion.attribution_lot (attribution_id, contrat_id, secteur_id, lot_id)
SELECT a.id, a.contrat_id, a.secteur_id, pg_temp.id('s01_l3')
  FROM gestion.attribution a JOIN gestion.contrat c ON c.id = a.contrat_id WHERE c.reference = 'C-SAPH-FUTUR';
SELECT pg_temp.verifie('transfert de lot à la fin d''un contrat : accepté',
  (SELECT count(*) = 2 FROM gestion.attribution_lot WHERE lot_id = pg_temp.id('s01_l3')));
-- … mais prolonger le contrat de SOGB recréerait un chevauchement.
SELECT pg_temp.echec('prolonger un contrat ne doit pas casser l''exclusivité des lots',
  $$ UPDATE gestion.contrat SET date_fin = pg_temp.d(3,6,30) WHERE id = pg_temp.id('c_sogb') $$, 'ex_lot_exclusif');

-- Contrats antérieurs : l'historique d'un opérateur, et un lot qui change d'opérateur à la fin d'un contrat.
-- Avant l'année courante, SOGB tenait le lot 1 du secteur 01 et SAPH le lot 2 ; SAPH tient ensuite les deux.
SELECT pg_temp.ok('contrats antérieurs (SAPH et SOGB) avec leurs attributions et leurs lots',
  $$ INSERT INTO gestion.contrat (reference, operateur_id, organisme_mandant_id, date_debut, date_fin) VALUES
       ('C-SAPH-ANT', pg_temp.id('saph'), pg_temp.id('firca'), pg_temp.d(-3,1,1), pg_temp.d(-1,12,31)),
       ('C-SOGB-ANT', pg_temp.id('sogb'), pg_temp.id('firca'), pg_temp.d(-3,1,1), pg_temp.d(-1,12,31));
     SELECT pg_temp.garder('c_saph_ant', (SELECT id FROM gestion.contrat WHERE reference = 'C-SAPH-ANT'));
     SELECT pg_temp.garder('c_sogb_ant', (SELECT id FROM gestion.contrat WHERE reference = 'C-SOGB-ANT'));
     INSERT INTO gestion.attribution (contrat_id, secteur_id) VALUES
       (pg_temp.id('c_saph_ant'), pg_temp.id('s01')), (pg_temp.id('c_sogb_ant'), pg_temp.id('s01'));
     SELECT pg_temp.garder('at_saph_ant', (SELECT id FROM gestion.attribution WHERE contrat_id = pg_temp.id('c_saph_ant')));
     SELECT pg_temp.garder('at_sogb_ant', (SELECT id FROM gestion.attribution WHERE contrat_id = pg_temp.id('c_sogb_ant')));
     INSERT INTO gestion.attribution_lot (attribution_id, contrat_id, secteur_id, lot_id) VALUES
       (pg_temp.id('at_sogb_ant'), pg_temp.id('c_sogb_ant'), pg_temp.id('s01'), pg_temp.id('s01_l1')),
       (pg_temp.id('at_saph_ant'), pg_temp.id('c_saph_ant'), pg_temp.id('s01'), pg_temp.id('s01_l2')) $$);
SELECT pg_temp.verifie('lot 1 : SOGB avant, SAPH ensuite (changement d''opérateur à la fin du contrat), sans chevauchement',
  (SELECT count(*) = 2 AND count(DISTINCT operateur_id) = 2
     FROM gestion.attribution_lot al JOIN gestion.attribution a ON a.id = al.attribution_id
    WHERE al.lot_id = pg_temp.id('s01_l1')));
SELECT pg_temp.verifie('lot 2 : SAPH dans le contrat antérieur puis dans le contrat en cours (continuité)',
  (SELECT count(*) = 2 AND count(DISTINCT contrat_id) = 2 FROM gestion.attribution_lot WHERE lot_id = pg_temp.id('s01_l2')));
SELECT pg_temp.echec('un contrat ne peut pas chevaucher le contrat antérieur du même opérateur',
  $$ INSERT INTO gestion.contrat (reference, operateur_id, organisme_mandant_id, date_debut, date_fin)
     VALUES ('C-SAPH-CHEV', pg_temp.id('saph'), pg_temp.id('firca'), pg_temp.d(-2,1,1), pg_temp.d(0,12,31)) $$, 'ex_contrat_chevauchement');
SELECT pg_temp.echec('prolonger un contrat antérieur ne peut pas empiéter sur le contrat en cours',
  $$ UPDATE gestion.contrat SET date_fin = pg_temp.d(0,6,30) WHERE id = pg_temp.id('c_sogb_ant') $$, 'ex_');
SELECT pg_temp.echec('un lot déjà tenu par un autre opérateur à cette époque ne peut pas lui être attribué rétroactivement',
  $$ INSERT INTO gestion.attribution_lot (attribution_id, contrat_id, secteur_id, lot_id)
     VALUES (pg_temp.id('at_saph_ant'), pg_temp.id('c_saph_ant'), pg_temp.id('s01'), pg_temp.id('s01_l1')) $$, 'ex_lot_exclusif');
SELECT pg_temp.verifie('contrat antérieur : trois exercices de 12 mois (n° 1 à 3), du 1er janvier an-3 au 31 décembre an-1',
  (SELECT string_agg(numero::text, ',' ORDER BY numero) = '1,2,3'
          AND min(date_debut) = pg_temp.d(-3,1,1) AND max(date_fin) = pg_temp.d(-1,12,31)
     FROM gestion.exercice WHERE attribution_id = pg_temp.id('at_saph_ant')));
SELECT pg_temp.echec('contrat antérieur : un quatrième exercice n''est pas couvert',
  $$ INSERT INTO gestion.exercice (attribution_id, numero) VALUES (pg_temp.id('at_saph_ant'), 4) $$,
  'n''est pas couvert');
SELECT pg_temp.verifie('contrat à venir : exercices générés pour ses trois périodes',
  (SELECT count(*) = 3 AND min(e.date_debut) = pg_temp.d(3,1,1)
     FROM gestion.exercice e JOIN gestion.attribution a ON a.id = e.attribution_id
     JOIN gestion.contrat c ON c.id = a.contrat_id WHERE c.reference = 'C-SAPH-FUTUR'));

-- ---------------------------------------------------------------------------
-- 4. Exercices
-- ---------------------------------------------------------------------------
SELECT pg_temp.verifie('exercices : trois exercices générés automatiquement par attribution',
  (SELECT bool_and(n = 3) FROM (SELECT count(*) AS n FROM gestion.exercice GROUP BY attribution_id) t));
SELECT pg_temp.verifie('exercices : clôture proposée à la fin de la période',
  (SELECT bool_and(date_cloture = date_fin) FROM gestion.exercice));
-- Contrats qui ne commencent pas le 1er janvier : les exercices suivent la date de début.
INSERT INTO referentiel.operateur (type, acronyme, denomination) VALUES ('TECHNIQUE', 'EXD', 'Opérateur à exercices décalés');
SELECT pg_temp.garder('exd', (SELECT id FROM referentiel.operateur WHERE acronyme = 'EXD'));
INSERT INTO gestion.contrat (reference, operateur_id, organisme_mandant_id, date_debut, date_fin)
VALUES ('C-EXD', pg_temp.id('exd'), pg_temp.id('firca'), pg_temp.d(0,6,1), pg_temp.d(3,5,31));
SELECT pg_temp.garder('c_exd', (SELECT id FROM gestion.contrat WHERE reference = 'C-EXD'));
INSERT INTO gestion.attribution (contrat_id, secteur_id) VALUES (pg_temp.id('c_exd'), pg_temp.id('s02'));
SELECT pg_temp.garder('at_exd', (SELECT id FROM gestion.attribution WHERE contrat_id = pg_temp.id('c_exd')));
SELECT pg_temp.verifie('contrat du 1er juin : trois exercices de 1er juin à 31 mai',
  (SELECT count(*) = 3
      AND bool_and(date_debut = pg_temp.d(numero - 1, 6, 1) AND date_fin = pg_temp.d(numero, 5, 31) AND date_cloture = date_fin)
     FROM gestion.exercice WHERE attribution_id = pg_temp.id('at_exd')));
SELECT pg_temp.echec('contrat du 1er juin : clôture avant le début de l''exercice refusée',
  $$ UPDATE gestion.exercice SET date_cloture = pg_temp.d(1,5,31) WHERE attribution_id = pg_temp.id('at_exd') AND numero = 3 $$, 'La clôture');
SELECT pg_temp.ok('contrat du 1er juin : clôture modifiable à l''intérieur ou après la période',
  $$ UPDATE gestion.exercice SET date_cloture = date_fin + 30 WHERE attribution_id = pg_temp.id('at_exd') AND numero = 1 $$);
INSERT INTO gestion.contrat (reference, operateur_id, organisme_mandant_id, date_debut, date_fin)
VALUES ('C-EXD-COURT', pg_temp.id('exd'), pg_temp.id('firca'), pg_temp.d(4,6,1), pg_temp.d(5,11,30));
INSERT INTO gestion.attribution (contrat_id, secteur_id)
SELECT id, pg_temp.id('s02') FROM gestion.contrat WHERE reference = 'C-EXD-COURT';
SELECT pg_temp.verifie('contrat de 18 mois : deux exercices, le second plus court (jusqu''à la fin du contrat)',
  (SELECT count(*) = 2 AND max(e.date_fin) = pg_temp.d(5,11,30) AND min(e.date_fin - e.date_debut) < 365
     FROM gestion.exercice e JOIN gestion.attribution a ON a.id = e.attribution_id
     JOIN gestion.contrat c ON c.id = a.contrat_id WHERE c.reference = 'C-EXD-COURT'));
UPDATE gestion.contrat SET date_fin = pg_temp.d(7,5,31) WHERE reference = 'C-EXD-COURT';
SELECT pg_temp.verifie('prolongation à 36 mois : le second exercice est étendu à 12 mois, un troisième est ajouté',
  (SELECT count(*) = 3 AND bool_and(e.date_fin = pg_temp.d(e.numero + 4, 5, 31) AND e.date_cloture = e.date_fin)
     FROM gestion.exercice e JOIN gestion.attribution a ON a.id = e.attribution_id
     JOIN gestion.contrat c ON c.id = a.contrat_id WHERE c.reference = 'C-EXD-COURT'));
SELECT pg_temp.verifie('paramètre : durée d''un contrat = 3 ans',
  (SELECT valeur = '3' FROM util.parametre WHERE cle = 'contrat.duree_annees'));
SELECT pg_temp.garder('ex_saph_s01_an0', id) FROM gestion.exercice WHERE attribution_id = pg_temp.id('at_saph_s01') AND numero = 1;
SELECT pg_temp.garder('ex_saph_s01_an1', id) FROM gestion.exercice WHERE attribution_id = pg_temp.id('at_saph_s01') AND numero = 2;
SELECT pg_temp.garder('ex_saph_s02_an0', id) FROM gestion.exercice WHERE attribution_id = pg_temp.id('at_saph_s02') AND numero = 1;
SELECT pg_temp.garder('ex_sogb_s01_an0', id) FROM gestion.exercice WHERE attribution_id = pg_temp.id('at_sogb_s01') AND numero = 1;

SELECT pg_temp.echec('exercice : numéro hors contrat',
  $$ INSERT INTO gestion.exercice (attribution_id, numero) VALUES (pg_temp.id('at_saph_s01'), 4) $$, 'n''est pas couvert');
SELECT pg_temp.echec('exercice : doublon attribution / numéro',
  $$ INSERT INTO gestion.exercice (attribution_id, numero) VALUES (pg_temp.id('at_saph_s01'), 1) $$, 'ux_exercice_numero');
SELECT pg_temp.echec('exercice : clôture avant le début',
  $$ INSERT INTO gestion.exercice (attribution_id, numero, date_cloture) VALUES (pg_temp.id('at_saph_s01'), 4, pg_temp.d(-5,1,1)) $$, 'n''est pas couvert');
SELECT pg_temp.echec('attribution avec exercices : suppression refusée',
  $$ UPDATE gestion.attribution SET supprime_le = now() WHERE id = pg_temp.id('at_saph_s01') $$, 'exercices');
SELECT pg_temp.echec('contrat avec attributions : suppression refusée',
  $$ UPDATE gestion.contrat SET supprime_le = now() WHERE id = pg_temp.id('c_saph') $$, 'attributions');

-- ---------------------------------------------------------------------------
-- 5. Affectations (périmètre des comptes)
-- ---------------------------------------------------------------------------
SELECT pg_temp.verifie('l''affectation n''est liée à aucun exercice (faite une seule fois)',
  NOT EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = 'iam' AND table_name = 'affectation' AND column_name IN ('exercice_id', 'attribution_id')));

SELECT pg_temp.acteur('cs_saph');
SELECT pg_temp.ok('CS affecté à son secteur',
  $$ SELECT pg_temp.affecter('cs_saph', 's01') $$);
SELECT pg_temp.echec('un seul CS par secteur et par opérateur',
  $$ SELECT pg_temp.affecter('cs2_saph', 's01') $$, 'Un seul chef secteur');
SELECT pg_temp.echec('CS : pas d''affectation à un lot précis',
  $$ SELECT pg_temp.affecter('cs2_saph', 's02', 's02_l1') $$, 'secteur entier');
SELECT pg_temp.ok('CS : un autre secteur, un autre CS',
  $$ SELECT pg_temp.affecter('cs2_saph', 's02') $$);
SELECT pg_temp.echec('CS : un seul secteur à la fois',
  $$ SELECT pg_temp.affecter('cs_saph', 's02') $$, 'un seul secteur à la fois');
SELECT pg_temp.ok('secteur partagé : le CS de SOGB a son propre périmètre (ses lots)',
  $$ SELECT pg_temp.affecter('cs_sogb', 's01') $$);
SELECT pg_temp.echec('un secteur non attribué à l''opérateur est refusé',
  $$ SELECT pg_temp.affecter('cs_sogb', 's02') $$, 'n''est pas attribué');

SELECT pg_temp.ok('moniteur : une section',
  $$ SELECT pg_temp.affecter('mo_saph', 's01', 's01_l1', 's01_l1_sec1') $$);
SELECT pg_temp.echec('moniteur : pas deux sections',
  $$ SELECT pg_temp.affecter('mo_saph', 's01', 's01_l1', 's01_l1_sec2') $$, 'au plus 1');
SELECT pg_temp.echec('une section n''a qu''un seul moniteur à la fois',
  $$ SELECT pg_temp.affecter('mo2_saph', 's01', 's01_l1', 's01_l1_sec1') $$, 'seul moniteur');
SELECT pg_temp.ok('second moniteur : une autre section',
  $$ SELECT pg_temp.affecter('mo2_saph', 's01', 's01_l1', 's01_l1_sec2') $$);
SELECT pg_temp.echec('moniteur : affectation à un lot sans section',
  $$ SELECT pg_temp.affecter('mo_tmp', 's01', 's01_l1') $$, 'affecté à des sections');

SELECT pg_temp.ok('contrôleur ordinaire : moins de 6 sections accepté',
  $$ SELECT pg_temp.affecter('co_saph', 's01', 's01_l1', 's01_l1_sec' || n) FROM generate_series(1, 3) n $$);
SELECT pg_temp.ok('contrôleur ordinaire : plus de 6 sections accepté, sans plafond ni avertissement',
  $$ SELECT pg_temp.affecter('co_saph', 's01', 's01_l1', 's01_l1_sec' || n) FROM generate_series(4, 7) n $$);
SELECT pg_temp.verifie('contrôleur ordinaire : 7 sections affectées',
  (SELECT count(*) = 7 FROM iam.affectation WHERE compte_id = pg_temp.id('co_saph') AND fin IS NULL));
SELECT pg_temp.echec('contrôleur ordinaire : pas de section d''un lot attribué à un autre opérateur',
  $$ SELECT pg_temp.affecter('co_saph', 's01', 's01_l3', 's01_l3_sec1') $$, 'n''est pas attribué');
SELECT pg_temp.echec('contrôleur ordinaire : un seul secteur à la fois',
  $$ SELECT pg_temp.affecter('co_saph', 's02', 's02_l1', 's02_l1_sec1') $$, 'un seul secteur à la fois');
SELECT pg_temp.echec('doublon d''affectation ouverte',
  $$ SELECT pg_temp.affecter('co_saph', 's01', 's01_l1', 's01_l1_sec1') $$, 'ux_affectation_ouverte');

SELECT pg_temp.ok('formateur de saignée : un lot',
  $$ SELECT pg_temp.affecter('fs_saph', 's01', 's01_l1') $$);
SELECT pg_temp.echec('formateur de saignée : pas un second lot',
  $$ SELECT pg_temp.affecter('fs_saph', 's01', 's01_l2') $$, 'au plus 1');
SELECT pg_temp.ok('plusieurs formateurs de saignée peuvent travailler sur le même lot',
  $$ SELECT pg_temp.affecter('fs2_saph', 's01', 's01_l1') $$);
SELECT pg_temp.ok('contrôleur de saignée : un lot',
  $$ SELECT pg_temp.affecter('cf_saph', 's01', 's01_l1') $$);
SELECT pg_temp.echec('formateur d''un autre opérateur : lot refusé',
  $$ SELECT pg_temp.affecter('fs_sogb', 's01', 's01_l2') $$, 'n''est pas attribué');
SELECT pg_temp.ok('formateur SOGB sur le lot 3 du secteur partagé',
  $$ SELECT pg_temp.affecter('fs_sogb', 's01', 's01_l3') $$);
SELECT pg_temp.ok('équipe spéciale : un secteur',
  $$ SELECT pg_temp.affecter('es_saph', 's01') $$);
SELECT pg_temp.echec('équipe spéciale : pas un lot',
  $$ SELECT pg_temp.affecter('es_saph', 's01', 's01_l1') $$, 'secteur entier');
SELECT pg_temp.ok('secrétaire : un secteur',
  $$ SELECT pg_temp.affecter('se_saph', 's01') $$);
SELECT pg_temp.echec('CD : aucune affectation territoriale',
  $$ SELECT pg_temp.affecter('cd_saph', 's01') $$, 'n''a pas d''affectation');
SELECT pg_temp.echec('un lot ne peut être retiré d''une attribution tant que des agents y sont affectés',
  $$ SELECT set_config('app.motif', 'Essai', true);
     DELETE FROM gestion.attribution_lot WHERE attribution_id = pg_temp.id('at_saph_s01') AND lot_id = pg_temp.id('s01_l1') $$,
  'encore affecté');

SELECT pg_temp.verifie('état des affectations en cours : toutes actives (territoire couvert par un contrat en cours)',
  (SELECT bool_and(etat = 'ACTIVE') FROM iam.v_affectation_etat WHERE fin IS NULL));

-- Affectations datées dans un contrat antérieur : continuité d'un contrat à l'autre, ou territoire perdu.
SELECT pg_temp.acteur('cs_saph');
SELECT pg_temp.ok('création d''une équipe spéciale ancienne et d''un contrôleur de saignée',
  $$ SELECT pg_temp.compte(pg_temp.id('saph'), 'ES', 'es_ant_saph') $$);
SELECT pg_temp.acteur('cs_sogb');
SELECT pg_temp.ok('création d''un formateur SOGB ancien',
  $$ SELECT pg_temp.compte(pg_temp.id('sogb'), 'FS', 'fs_ant_sogb') $$);
SELECT pg_temp.ok('affectation datée dans le contrat antérieur de l''opérateur : acceptée',
  $$ SELECT pg_temp.affecter('es_ant_saph', 's01', NULL, NULL, pg_temp.d(-2,3,1)) $$);
SELECT pg_temp.verifie('...et, le territoire étant couvert aussi par le contrat en cours, elle reste active (continuité)',
  (SELECT etat = 'ACTIVE' FROM iam.v_affectation_etat WHERE compte_id = pg_temp.id('es_ant_saph')));
SELECT pg_temp.acteur('cs_saph');
SELECT pg_temp.echec('affectation datée avant tout contrat de l''opérateur : refusée',
  $$ SELECT pg_temp.compte(pg_temp.id('saph'), 'ES', 'es_avant', NULL);
     SELECT pg_temp.affecter('es_avant', 's01', NULL, NULL, pg_temp.d(-5,1,1)) $$, 'n''est pas attribué');
SELECT pg_temp.acteur('cs_saph');
SELECT pg_temp.echec('lot tenu par un autre opérateur à cette date : affectation refusée',
  $$ SELECT pg_temp.affecter('fs_saph', 's01', 's01_l1', NULL, pg_temp.d(-2,3,1)) $$, 'n''est pas attribué');
SELECT pg_temp.acteur('cs_sogb');
SELECT pg_temp.ok('le même lot, à la même date, pour l''opérateur qui le tenait : accepté',
  $$ SELECT pg_temp.affecter('fs_ant_sogb', 's01', 's01_l1', NULL, pg_temp.d(-2,3,1)) $$);
SELECT pg_temp.verifie('le lot est passé à SAPH : l''affectation de SOGB est « à revoir » (contrat en cours qui ne le couvre plus)',
  (SELECT etat = 'A_REVOIR' FROM iam.v_affectation_etat WHERE compte_id = pg_temp.id('fs_ant_sogb')));
SELECT pg_temp.verifie('...et elle n''est pas supprimée',
  (SELECT count(*) = 1 FROM iam.affectation WHERE compte_id = pg_temp.id('fs_ant_sogb') AND fin IS NULL));
SELECT pg_temp.echec('la fin d''une affectation ne peut pas précéder son début',
  $$ UPDATE iam.affectation SET fin = pg_temp.d(-3,1,1) WHERE compte_id = pg_temp.id('fs_ant_sogb') $$, 'affectation_check');

-- Passage d'un contrat à l'autre : une affectation reste, son état dit si elle est à revoir.
SELECT pg_temp.acteur('ad_firca');
INSERT INTO referentiel.operateur (type, acronyme, denomination) VALUES ('TECHNIQUE', 'REVU', 'Opérateur A'), ('TECHNIQUE', 'REV2', 'Opérateur B');
SELECT pg_temp.garder('revu', (SELECT id FROM referentiel.operateur WHERE acronyme = 'REVU'));
SELECT pg_temp.garder('rev2', (SELECT id FROM referentiel.operateur WHERE acronyme = 'REV2'));
INSERT INTO gestion.contrat (reference, operateur_id, organisme_mandant_id, date_debut, date_fin) VALUES
  ('C-REVU-1', pg_temp.id('revu'), pg_temp.id('firca'), '2020-01-01', '2022-12-31'),
  ('C-REVU-2', pg_temp.id('revu'), pg_temp.id('firca'), '2023-01-01', '2060-12-31'),
  ('C-REV2-1', pg_temp.id('rev2'), pg_temp.id('firca'), '2020-01-01', '2022-12-31');
INSERT INTO gestion.attribution (contrat_id, secteur_id)
SELECT c.id, pg_temp.id(s) FROM gestion.contrat c JOIN (VALUES ('C-REVU-1', 's02'), ('C-REVU-2', 's01'), ('C-REV2-1', 's02')) v(ref, s) ON v.ref = c.reference;
SELECT pg_temp.compte(pg_temp.id('revu'), 'CD', 'cd_revu');
SELECT pg_temp.compte(pg_temp.id('rev2'), 'CD', 'cd_rev2');
SELECT pg_temp.acteur('cd_revu');
SELECT pg_temp.compte(pg_temp.id('revu'), 'CS', 'cs_revu');
SELECT pg_temp.acteur('cd_rev2');
SELECT pg_temp.compte(pg_temp.id('rev2'), 'CS', 'cs_rev2');
SELECT pg_temp.affecter('cs_revu', 's02', NULL, NULL, DATE '2021-06-01');
SELECT pg_temp.affecter('cs_rev2', 's02', NULL, NULL, DATE '2021-06-01');
SELECT pg_temp.verifie('affectation : le nouveau contrat ne couvre plus le secteur -> à revoir (pas supprimée)',
  (SELECT etat = 'A_REVOIR' FROM iam.v_affectation_etat WHERE compte_id = pg_temp.id('cs_revu')));
SELECT pg_temp.verifie('affectation : opérateur sans contrat en cours -> suspendue',
  (SELECT etat = 'SUSPENDUE' FROM iam.v_affectation_etat WHERE compte_id = pg_temp.id('cs_rev2')));
SELECT pg_temp.verifie('affectation à revoir : toujours présente et ouverte',
  (SELECT count(*) = 1 FROM iam.affectation WHERE compte_id = pg_temp.id('cs_revu') AND fin IS NULL));

-- ---------------------------------------------------------------------------
-- 6. Vie des comptes : profil, matricule, départ et reprise, désactivation
-- ---------------------------------------------------------------------------
-- Promotion : le profil change, le compte reste ; le matricule suit le profil et l'ancien est gardé en alias.
SELECT pg_temp.acteur('cd_saph');
SELECT pg_temp.motif('');
SELECT pg_temp.echec('changement de profil : le motif est obligatoire',
  $$ UPDATE iam.compte SET profil = 'CS' WHERE id = pg_temp.id('co_saph') $$, 'motif est obligatoire');
SELECT pg_temp.motif('Promotion 2026');
SELECT pg_temp.echec('changement de profil : impossible pour son propre compte',
  $$ UPDATE iam.compte SET profil = 'CS' WHERE id = pg_temp.id('cd_saph') $$, 'autorité');
SELECT pg_temp.ok('promotion CO -> CS par le CD',
  $$ UPDATE iam.compte SET profil = 'CS' WHERE id = pg_temp.id('co_saph') $$);
SELECT pg_temp.verifie('promotion : le matricule prend le nouveau profil (la référence le suit)',
  (SELECT matricule = 'SAPH-CS-' || numero AND reference_compte = matricule
     FROM iam.compte WHERE id = pg_temp.id('co_saph')));
SELECT pg_temp.verifie('promotion : l''ancien matricule est gardé en alias, avec le motif du changement de profil',
  (SELECT count(*) = 1 FROM iam.alias_matricule
    WHERE compte_id = pg_temp.id('co_saph') AND ancien_matricule = 'SAPH-CO-' || (SELECT numero FROM iam.compte WHERE id = pg_temp.id('co_saph'))
      AND motif = 'Changement de profil : Promotion 2026'));
SELECT pg_temp.verifie('promotion : historique des profils (CO puis CS) avec motif',
  (SELECT string_agg(profil, ',' ORDER BY debut) = 'CO,CS' AND bool_or(motif = 'Promotion 2026')
     FROM iam.compte_profil WHERE compte_id = pg_temp.id('co_saph')));
SELECT pg_temp.verifie('promotion : les affectations de l''ancien profil sont closes, conservées',
  (SELECT count(*) = 7 AND bool_and(fin = current_date) FROM iam.affectation WHERE compte_id = pg_temp.id('co_saph')));
SELECT pg_temp.verifie('promotion : les affectations closes sont à l''état CLOSE',
  (SELECT bool_and(etat = 'CLOSE') FROM iam.v_affectation_etat WHERE compte_id = pg_temp.id('co_saph')));
SELECT pg_temp.verifie('profil à un instant donné : CO avant, CS maintenant',
  iam.profil_a(pg_temp.id('co_saph'), (SELECT debut FROM iam.compte_profil WHERE compte_id = pg_temp.id('co_saph') ORDER BY debut LIMIT 1)) = 'CO'
  AND iam.profil_a(pg_temp.id('co_saph'), now()) = 'CS');

SELECT pg_temp.acteur('cs_saph');
SELECT pg_temp.echec('un CS ne promeut pas un technicien au rang de CS',
  $$ UPDATE iam.compte SET profil = 'CS' WHERE id = pg_temp.id('mo2_saph') $$, 'autorité');
SELECT pg_temp.ok('mouvement latéral MO -> CO par le CS, avec motif',
  $$ UPDATE iam.compte SET profil = 'CO' WHERE id = pg_temp.id('mo_tmp') $$);
SELECT pg_temp.motif('Rétrogradation');
SELECT pg_temp.ok('rétrogradation du CS promu vers moniteur par le CD',
  $$ SELECT pg_temp.acteur('cd_saph'); UPDATE iam.compte SET profil = 'MO' WHERE id = pg_temp.id('co_saph');
     SELECT pg_temp.acteur('cs_saph') $$);
SELECT pg_temp.motif('Promotion 2026');
SELECT pg_temp.acteur('cd_saph');
SELECT pg_temp.ok('...et promotion de nouveau, l''historique garde les trois changements',
  $$ UPDATE iam.compte SET profil = 'CO' WHERE id = pg_temp.id('co_saph') $$);
SELECT pg_temp.verifie('historique : CO, CS, MO, CO',
  (SELECT string_agg(profil, ',' ORDER BY debut) = 'CO,CS,MO,CO' FROM iam.compte_profil WHERE compte_id = pg_temp.id('co_saph')));
SELECT pg_temp.acteur('ad_firca');
SELECT pg_temp.echec('changement entre profil d''organisme et profil d''opérateur refusé',
  $$ UPDATE iam.compte SET profil = 'SU' WHERE id = pg_temp.id('cd_saph') $$, 'interchangeables');
SELECT pg_temp.motif('');

-- Correction d'un matricule : administrateur seulement, avec motif ; l'ancien est conservé en alias.
SELECT pg_temp.acteur('cd_saph');
SELECT pg_temp.motif('Alignement sur le système de la SAPH');
SELECT pg_temp.echec('correction du matricule : réservée à l''administrateur',
  $$ UPDATE iam.compte SET numero = '00777' WHERE id = pg_temp.id('cs2_saph') $$, 'Seul un administrateur');
SELECT pg_temp.acteur('ad_firca');
SELECT pg_temp.motif('');
SELECT pg_temp.echec('correction du matricule : motif obligatoire',
  $$ UPDATE iam.compte SET numero = '00777' WHERE id = pg_temp.id('cs2_saph') $$, 'motif est obligatoire');
SELECT pg_temp.motif('Alignement sur le système de la SAPH');
SELECT pg_temp.echec('correction du matricule : numéro déjà pris',
  $$ UPDATE iam.compte SET numero = '00348' WHERE id = pg_temp.id('cs2_saph') $$, 'operateur_id_numero_key');
SELECT pg_temp.ok('correction du matricule par l''administrateur',
  $$ UPDATE iam.compte SET numero = '00777' WHERE id = pg_temp.id('cs2_saph') $$);
SELECT pg_temp.motif('');
SELECT pg_temp.verifie('correction : matricule et référence mis à jour',
  (SELECT matricule = 'SAPH-CS-00777' AND reference_compte = 'SAPH-CS-00777' FROM iam.compte WHERE id = pg_temp.id('cs2_saph')));
SELECT pg_temp.verifie('correction : l''ancien matricule est conservé en alias avec motif',
  (SELECT count(*) = 1 FROM iam.alias_matricule
    WHERE compte_id = pg_temp.id('cs2_saph') AND ancien_matricule = 'SAPH-CS-00003' AND motif = 'Alignement sur le système de la SAPH'));
SELECT pg_temp.acteur('cs_saph');
SELECT pg_temp.echec('un ancien numéro n''est jamais attribué à un autre compte',
  $$ SELECT pg_temp.compte(pg_temp.id('saph'), 'SE', 'x', '00003') $$, 'ancien matricule');
UPDATE iam.compte SET matricule = 'SAPH-SE-99999' WHERE id = pg_temp.id('se_saph');
SELECT pg_temp.verifie('un matricule modifié directement est ignoré (il est calculé à partir du profil et du numéro)',
  (SELECT matricule <> 'SAPH-SE-99999' AND matricule = 'SAPH-SE-' || numero FROM iam.compte WHERE id = pg_temp.id('se_saph')));

-- Désactivation, sessions, synchronisation du mobile.
INSERT INTO iam.jeton_rafraichissement (compte_id, famille_id, empreinte, expire_le)
VALUES (pg_temp.id('mo_saph'), gen_random_uuid(), repeat('a', 64), now() + interval '1 day');
SELECT pg_temp.ok('le CS désactive le compte d''un moniteur qui part',
  $$ UPDATE iam.compte SET statut = 'DESACTIVE' WHERE id = pg_temp.id('mo_saph') $$);
SELECT pg_temp.verifie('désactivation : le rafraîchissement reste utilisable pour l''envoi seul (V17), le serveur le réduit à l''envoi',
  (SELECT revoque_le IS NULL FROM iam.jeton_rafraichissement WHERE compte_id = pg_temp.id('mo_saph')));
SELECT pg_temp.verifie('compte désactivé : connexion refusée',
  NOT iam.compte_peut_se_connecter(pg_temp.id('mo_saph')));
SELECT pg_temp.verifie('compte désactivé : envoi seul du mobile encore permis (délai de 30 jours)',
  iam.compte_peut_synchroniser(pg_temp.id('mo_saph')));
UPDATE iam.compte SET desactive_le = now() - interval '35 days' WHERE id = pg_temp.id('mo_saph');
SELECT pg_temp.verifie('compte désactivé depuis 35 jours : plus d''envoi',
  NOT iam.compte_peut_synchroniser(pg_temp.id('mo_saph')));
UPDATE util.parametre SET valeur = '60' WHERE cle = 'compte.delai_synchronisation_jours';
SELECT pg_temp.verifie('le délai est un paramètre : porté à 60 jours, l''envoi est de nouveau permis',
  iam.compte_peut_synchroniser(pg_temp.id('mo_saph')));
UPDATE util.parametre SET valeur = '30' WHERE cle = 'compte.delai_synchronisation_jours';
SELECT pg_temp.echec('un compte non concerné ne peut pas désactiver (moniteur sur secrétaire)',
  $$ SELECT pg_temp.acteur('mo2_saph'); UPDATE iam.compte SET statut = 'DESACTIVE' WHERE id = pg_temp.id('se_saph') $$, 'autorité');
SELECT pg_temp.acteur('cs_saph');

-- Reprise du travail d'un compte désactivé par le compte de son successeur.
SELECT pg_temp.ok('le CS crée le compte du successeur',
  $$ SELECT pg_temp.compte(pg_temp.id('saph'), 'MO', 'mo3_saph') $$);
SELECT pg_temp.echec('reprise : le motif est obligatoire',
  $$ SELECT iam.reprendre_compte(pg_temp.id('mo_saph'), pg_temp.id('mo3_saph'), ' ') $$, 'motif est obligatoire');
SELECT pg_temp.echec('reprise : le compte repris doit être désactivé',
  $$ SELECT iam.reprendre_compte(pg_temp.id('fs2_saph'), pg_temp.id('mo3_saph'), 'Essai') $$, 'doit être désactivé');
SELECT pg_temp.echec('reprise : un CS d''un autre opérateur n''a pas l''autorité',
  $$ SELECT pg_temp.acteur('cs_sogb'); SELECT iam.reprendre_compte(pg_temp.id('mo_saph'), pg_temp.id('mo3_saph'), 'Essai') $$, 'autorité');
SELECT pg_temp.acteur('cs_saph');
SELECT pg_temp.echec('reprise : sans compte connecté',
  $$ SELECT pg_temp.acteur('inconnu'); SELECT iam.reprendre_compte(pg_temp.id('mo_saph'), pg_temp.id('mo3_saph'), 'Essai') $$, 'autorité');
SELECT pg_temp.acteur('cs_saph');
SELECT pg_temp.ok('reprise : le successeur reprend les affectations du moniteur parti',
  $$ SELECT iam.reprendre_compte(pg_temp.id('mo_saph'), pg_temp.id('mo3_saph'), 'Démission') $$);
SELECT pg_temp.verifie('reprise : affectation clôturée chez l''ancien, ouverte chez le successeur (même section)',
  (SELECT count(*) = 1 FROM iam.affectation WHERE compte_id = pg_temp.id('mo_saph') AND fin = current_date
      AND section_id = pg_temp.id('s01_l1_sec1'))
  AND (SELECT count(*) = 1 FROM iam.affectation WHERE compte_id = pg_temp.id('mo3_saph') AND fin IS NULL
      AND section_id = pg_temp.id('s01_l1_sec1')));
SELECT pg_temp.verifie('reprise : conservée avec motif et nombre d''affectations',
  (SELECT count(*) = 1 FROM iam.reprise_compte
    WHERE source_id = pg_temp.id('mo_saph') AND cible_id = pg_temp.id('mo3_saph') AND motif = 'Démission' AND nb_affectations = 1));

-- Désactiver une personne désactive tous ses comptes.
SELECT pg_temp.acteur('mo2_saph');
SELECT pg_temp.echec('désactiver un utilisateur demande l''autorité de gérer ses comptes',
  $$ UPDATE iam.utilisateur SET statut = 'DESACTIVE' WHERE id = pg_temp.id('u_se_saph') $$, 'autorité');
SELECT pg_temp.acteur('cs_saph');
SELECT pg_temp.ok('le CS désactive la personne (secrétaire)',
  $$ UPDATE iam.utilisateur SET statut = 'DESACTIVE' WHERE id = pg_temp.id('u_se_saph') $$);
SELECT pg_temp.verifie('utilisateur désactivé : ses comptes sont désactivés, il ne peut accéder à aucun',
  (SELECT statut = 'DESACTIVE' FROM iam.compte WHERE id = pg_temp.id('se_saph')) AND NOT iam.compte_peut_se_connecter(pg_temp.id('se_saph')));
SELECT pg_temp.echec('un compte d''un utilisateur désactivé ne peut pas être réactivé',
  $$ UPDATE iam.compte SET statut = 'ACTIF' WHERE id = pg_temp.id('se_saph') $$, 'utilisateur est désactivé');
SELECT pg_temp.echec('aucun nouveau compte pour un utilisateur désactivé',
  $$ INSERT INTO iam.compte (utilisateur_id, operateur_id, profil, statut, mot_de_passe_hash)
     VALUES (pg_temp.id('u_se_saph'), pg_temp.id('saph'), 'SE', 'ACTIF', 'h') $$, 'utilisateur est désactivé');
SELECT pg_temp.ok('réactivation de la personne puis de son compte',
  $$ UPDATE iam.utilisateur SET statut = 'ACTIF' WHERE id = pg_temp.id('u_se_saph');
     UPDATE iam.compte SET statut = 'ACTIF' WHERE id = pg_temp.id('se_saph') $$);
SELECT pg_temp.verifie('compte réactivé : connexion de nouveau possible',
  iam.compte_peut_se_connecter(pg_temp.id('se_saph')));
SELECT pg_temp.ok('même personne, nouveau compte, nouveau matricule (après désactivation de l''ancien)',
  $$ INSERT INTO iam.compte (utilisateur_id, operateur_id, profil, statut, mot_de_passe_hash)
     VALUES (pg_temp.id('u_mo_saph'), pg_temp.id('saph'), 'CO', 'ACTIF', 'h') $$);
SELECT pg_temp.verifie('la personne a deux comptes, un seul actif',
  (SELECT count(*) = 2 AND count(*) FILTER (WHERE statut <> 'DESACTIVE') = 1
     FROM iam.compte WHERE utilisateur_id = pg_temp.id('u_mo_saph')));

-- Opérateur désactivé : tous ses comptes sont bloqués (cahier ORG-06).
SELECT pg_temp.verifie('opérateur actif : le CS de REV2 peut se connecter',
  iam.compte_peut_se_connecter(pg_temp.id('cs_rev2')));
UPDATE referentiel.operateur SET actif = false WHERE id = pg_temp.id('rev2');
SELECT pg_temp.verifie('opérateur désactivé : connexion et envoi mobile bloqués immédiatement',
  NOT iam.compte_peut_se_connecter(pg_temp.id('cs_rev2')) AND NOT iam.compte_peut_synchroniser(pg_temp.id('cs_rev2')));
UPDATE referentiel.operateur SET actif = true WHERE id = pg_temp.id('rev2');

-- Un compte sans histoire peut être supprimé ; son numéro n'est jamais réutilisé ; sinon désactivation.
SELECT pg_temp.ok('compte créé par erreur : suppression logique possible',
  $$ SELECT pg_temp.compte(pg_temp.id('saph'), 'SE', 'tmp_saph', '09999');
     UPDATE iam.compte SET supprime_le = now() WHERE id = pg_temp.id('tmp_saph') $$);
SELECT pg_temp.echec('numéro d''un compte supprimé : jamais réutilisé',
  $$ SELECT pg_temp.compte(pg_temp.id('saph'), 'SE', 'x', '09999') $$, 'operateur_id_numero_key');
SELECT pg_temp.echec('un compte qui a un historique ne se supprime pas',
  $$ UPDATE iam.compte SET supprime_le = now() WHERE id = pg_temp.id('mo_saph') $$, 'a un historique');

-- Permissions : garde-fous.
SELECT pg_temp.acteur('cd_saph');
SELECT pg_temp.echec('seul un profil qui détient « gérer les permissions » peut les ajuster',
  $$ DELETE FROM iam.profil_permission WHERE profil = 'SE' AND permission = 'VISUALISER_CARTE' $$, 'permission de gérer les permissions');
SELECT pg_temp.acteur('ad_firca');
SELECT pg_temp.echec('l''administrateur ne peut pas perdre « gérer les permissions »',
  $$ DELETE FROM iam.profil_permission WHERE profil = 'AD' AND permission = 'GERER_PERMISSIONS' $$, 'ne peut pas être retirée');
SELECT pg_temp.echec('l''administrateur ne peut pas perdre « gérer les référentiels »',
  $$ DELETE FROM iam.profil_permission WHERE profil = 'AD' AND permission = 'GERER_REFERENTIEL' $$, 'ne peut pas être retirée');
SELECT pg_temp.ok('l''administrateur ajuste les permissions d''un profil',
  $$ DELETE FROM iam.profil_permission WHERE profil = 'SE' AND permission = 'VISUALISER_CARTE';
     INSERT INTO iam.profil_permission VALUES ('SE', 'VISUALISER_CARTE') $$);
SELECT pg_temp.verifie('matrice de départ : le SU et le CD ne consultent aucun planning, le CS le valide',
  NOT iam.a_permission('SU', 'CONSULTER_PLANNING') AND NOT iam.a_permission('CD', 'CONSULTER_PLANNING')
  AND NOT iam.a_permission('CD', 'VALIDER_PLANNING') AND iam.a_permission('CS', 'VALIDER_PLANNING'));
SELECT pg_temp.verifie('matrice de départ : tous les techniciens et le secrétaire ont dashboard et carte, pas le reporting',
  (SELECT bool_and(iam.a_permission(p.code, 'CONSULTER_DASHBOARD') AND iam.a_permission(p.code, 'VISUALISER_CARTE')
                   AND NOT iam.a_permission(p.code, 'CONSULTER_REPORTING'))
     FROM iam.profil p WHERE p.code IN ('CF', 'CO', 'FS', 'MO', 'ES', 'SE')));
SELECT pg_temp.verifie('matrice de départ : le secrétaire enregistre et consulte les plannings, consulte les activités',
  iam.a_permission('SE', 'ENREGISTRER_PLANNING') AND iam.a_permission('SE', 'CONSULTER_PLANNING')
  AND iam.a_permission('SE', 'CONSULTER_ACTIVITE') AND NOT iam.a_permission('SE', 'REALISER_ACTIVITE'));

-- ---------------------------------------------------------------------------
-- 7. Piste d'audit
-- ---------------------------------------------------------------------------
SELECT pg_temp.acteur('ad_firca');
SELECT set_config('app.motif', 'Correction de saisie', false);
UPDATE referentiel.lot SET description = 'Lot nord' WHERE id = pg_temp.id('s01_l1');
SELECT set_config('app.motif', '', false);

SELECT pg_temp.verifie('audit : la modification d''un lot est tracée avec acteur et motif',
  (SELECT count(*) = 1 FROM audit.evenement
    WHERE table_nom = 'lot' AND operation = 'U' AND objet_id = pg_temp.id('s01_l1')::text
      AND acteur_compte_id = pg_temp.id('ad_firca') AND acteur_utilisateur_id = pg_temp.id('u_ad_firca')
      AND acteur_profil = 'AD' AND motif = 'Correction de saisie'));
SELECT pg_temp.verifie('audit : seules les colonnes modifiées sont enregistrées',
  (SELECT nouvelles = '{"description": "Lot nord"}'::jsonb AND anciennes = '{"description": ""}'::jsonb
     FROM audit.evenement WHERE table_nom = 'lot' AND operation = 'U' AND objet_id = pg_temp.id('s01_l1')::text));
SELECT pg_temp.verifie('audit : la suppression logique est une opération S',
  (SELECT count(*) = 1 FROM audit.evenement WHERE table_nom = 'secteur' AND operation = 'S'));
SELECT pg_temp.echec('retrait d''un lot : le motif est obligatoire',
  $$ DELETE FROM gestion.attribution_lot WHERE attribution_id = pg_temp.id('at_saph_s02') AND lot_id = pg_temp.id('s02_l3') $$,
  'motif est obligatoire');
SELECT set_config('app.motif', 'Lot repris en gestion directe', false);
DELETE FROM gestion.attribution_lot WHERE attribution_id = pg_temp.id('at_saph_s02') AND lot_id = pg_temp.id('s02_l3');
SELECT set_config('app.motif', '', false);
SELECT pg_temp.verifie('retrait d''un lot : conservé dans l''historique avec motif et opérateur',
  (SELECT count(*) = 1 FROM gestion.retrait_lot
    WHERE lot_id = pg_temp.id('s02_l3') AND operateur_id = pg_temp.id('saph')
      AND motif = 'Lot repris en gestion directe' AND retire_par = pg_temp.id('ad_firca')
      AND reserve_jusqu_au = pg_temp.d(2,12,31)));
INSERT INTO gestion.contrat (reference, operateur_id, organisme_mandant_id, date_debut, date_fin)
VALUES ('C-SOGB-S02', pg_temp.id('sogb'), pg_temp.id('firca'), pg_temp.d(3,1,1), pg_temp.d(5,12,31));
SELECT pg_temp.garder('c_sogb2', (SELECT id FROM gestion.contrat WHERE reference = 'C-SOGB-S02'));
INSERT INTO gestion.attribution (contrat_id, secteur_id) VALUES (pg_temp.id('c_sogb2'), pg_temp.id('s02'));
SELECT pg_temp.garder('at_sogb2_s02', (SELECT id FROM gestion.attribution WHERE contrat_id = pg_temp.id('c_sogb2')));
SELECT pg_temp.verifie('prolongation d''un contrat : l''exercice manquant est généré',
  (SELECT count(*) = 0 FROM gestion.exercice WHERE attribution_id = pg_temp.id('at_sogb2_s02') AND numero = 4));
UPDATE gestion.contrat SET date_fin = pg_temp.d(6,12,31) WHERE id = pg_temp.id('c_sogb2');
SELECT pg_temp.verifie('prolongation d''un contrat : quatrième exercice créé, aucun doublon',
  (SELECT count(*) = 1 FROM gestion.exercice WHERE attribution_id = pg_temp.id('at_sogb2_s02') AND numero = 4)
  AND (SELECT count(*) = 4 FROM gestion.exercice WHERE attribution_id = pg_temp.id('at_sogb2_s02')));
SELECT pg_temp.ok('lot retiré : peut passer à un autre opérateur après la fin du contrat d''origine',
  $$ INSERT INTO gestion.attribution_lot (attribution_id, contrat_id, secteur_id, lot_id)
     VALUES (pg_temp.id('at_sogb2_s02'), pg_temp.id('c_sogb2'), pg_temp.id('s02'), pg_temp.id('s02_l3')) $$);
INSERT INTO gestion.attribution (contrat_id, secteur_id) VALUES (pg_temp.id('c_sogb'), pg_temp.id('s02'));
SELECT pg_temp.garder('at_sogb_s02', (SELECT id FROM gestion.attribution WHERE contrat_id = pg_temp.id('c_sogb') AND secteur_id = pg_temp.id('s02')));
SELECT pg_temp.echec('lot retiré : ne peut pas changer d''opérateur avant la fin du contrat d''origine',
  $$ INSERT INTO gestion.attribution_lot (attribution_id, contrat_id, secteur_id, lot_id)
     VALUES (pg_temp.id('at_sogb_s02'), pg_temp.id('c_sogb'), pg_temp.id('s02'), pg_temp.id('s02_l3')) $$,
  'ne peut changer d''opérateur');
SELECT pg_temp.ok('lot retiré : l''opérateur d''origine peut le reprendre',
  $$ INSERT INTO gestion.attribution_lot (attribution_id, contrat_id, secteur_id, lot_id)
     VALUES (pg_temp.id('at_saph_s02'), pg_temp.id('c_saph'), pg_temp.id('s02'), pg_temp.id('s02_l3')) $$);
SELECT pg_temp.verifie('audit : le retrait d''un lot d''attribution est tracé (D) avec les anciennes valeurs',
  (SELECT count(*) = 1 FROM audit.evenement
    WHERE table_nom = 'attribution_lot' AND operation = 'D' AND anciennes ->> 'lot_id' = pg_temp.id('s02_l3')::text));

UPDATE iam.compte SET mot_de_passe_hash = '$2a$10$secretsecretsecretsecretsecretsecretsecretsecretsecretsec' WHERE id = pg_temp.id('su_firca');
SELECT pg_temp.verifie('audit : aucun hash de mot de passe n''est journalisé',
  (SELECT count(*) = 0 FROM audit.evenement WHERE nouvelles::text LIKE '%secretsecret%' OR anciennes::text LIKE '%secretsecret%'));
SELECT pg_temp.verifie('audit : un changement de mot de passe est tracé sans sa valeur',
  (SELECT count(*) = 1 FROM audit.evenement
    WHERE table_nom = 'compte' AND objet_id = pg_temp.id('su_firca')::text
      AND nouvelles = '{"mot_de_passe_hash": "modifié"}'::jsonb));
SELECT pg_temp.verifie('audit : la création d''un compte est tracée (matricule, créateur, profil du créateur)',
  (SELECT count(*) = 1 FROM audit.evenement WHERE table_nom = 'compte' AND operation = 'I'
    AND nouvelles ->> 'matricule' = 'SAPH-MO-00348' AND nouvelles ->> 'reference_compte' = 'SAPH-MO-00348'
    AND acteur_compte_id = pg_temp.id('cs_saph') AND acteur_profil = 'CS'));
SELECT pg_temp.verifie('audit : aucun hash de mot de passe dans la création d''un compte',
  (SELECT count(*) = 0 FROM audit.evenement WHERE table_nom = 'compte' AND nouvelles ? 'mot_de_passe_hash' AND nouvelles ->> 'mot_de_passe_hash' <> 'modifié'));
CREATE TEMP TABLE compteur AS SELECT count(*) AS n FROM audit.evenement;
UPDATE referentiel.lot SET description = description WHERE id = pg_temp.id('s01_l1');
SELECT pg_temp.verifie('audit : un UPDATE sans changement ne produit aucun événement',
  (SELECT n FROM compteur) = (SELECT count(*) FROM audit.evenement));

SELECT pg_temp.echec('rôle applicatif : modification de l''audit interdite',
  $$ SET LOCAL ROLE sysde_app; UPDATE audit.evenement SET motif = 'x' $$, 'permission denied');
SELECT pg_temp.echec('rôle applicatif : suppression dans l''audit interdite',
  $$ SET LOCAL ROLE sysde_app; DELETE FROM audit.evenement $$, 'permission denied');
SELECT pg_temp.echec('rôle applicatif : insertion directe dans l''audit interdite',
  $$ SET LOCAL ROLE sysde_app; INSERT INTO audit.evenement (schema_nom, table_nom, operation, objet_id) VALUES ('a','b','I','c') $$, 'permission denied');
SELECT pg_temp.ok('rôle applicatif : lecture de l''audit et écriture métier autorisées',
  format($$ SET LOCAL ROLE sysde_app; SELECT count(*) FROM audit.evenement;
            UPDATE referentiel.lot SET description = 'Lot sud' WHERE id = %L $$, pg_temp.id('s01_l1')));
SELECT pg_temp.echec('rôle applicatif : pas de DELETE sur les tables à suppression logique',
  $$ SET LOCAL ROLE sysde_app; DELETE FROM referentiel.secteur $$, 'permission denied');

-- Contours : la valeur de la géométrie n'est jamais copiée dans l'audit, seule la modification y apparaît.
-- (V8 absente : on simule la colonne avec du texte ; V8 présente : on utilise une vraie géométrie.)
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                  WHERE table_schema = 'referentiel' AND table_name = 'lot' AND column_name = 'geom') THEN
    ALTER TABLE referentiel.lot ADD COLUMN geom text;
    UPDATE referentiel.lot SET geom = 'CONTENU-VOLUMINEUX-DU-CONTOUR' WHERE id = pg_temp.id('s01_l2');
  ELSE
    EXECUTE format($f$UPDATE referentiel.lot SET geom = ST_GeomFromText('MULTIPOLYGON(((0 0,0 1,1 1,0 0)))', 4326) WHERE id = %L$f$,
                   pg_temp.id('s01_l2'));
  END IF;
END $$;
SELECT pg_temp.verifie('audit : la modification d''un contour est signalée sans copier la géométrie',
  (SELECT count(*) = 1 FROM audit.evenement
    WHERE table_nom = 'lot' AND objet_id = pg_temp.id('s01_l2')::text
      AND nouvelles = '{"geom": "modifié"}'::jsonb AND nouvelles::text NOT LIKE '%VOLUMINEUX%'));

SELECT pg_temp.ok('import de contours : un lot identifié, fichier GeoJSON en WGS 84',
  format($$ INSERT INTO referentiel.import_contour (niveau, lot_id, format, fichier_nom, fichier_cle, empreinte)
            VALUES ('LOT', %L, 'GEOJSON', 'lot1.geojson', 'imports/lot1.geojson', repeat('a', 64)) $$, pg_temp.id('s01_l1')));
SELECT pg_temp.echec('import de contours : un autre système de coordonnées est refusé',
  format($$ INSERT INTO referentiel.import_contour (niveau, lot_id, format, fichier_nom, fichier_cle, empreinte, srid)
            VALUES ('LOT', %L, 'SHAPEFILE', 'lot1.shp', 'imports/lot1.shp', repeat('a', 64), 32629) $$, pg_temp.id('s01_l1')),
  'ck_import_contour_srid');
SELECT pg_temp.echec('import de contours : le niveau section est réservé (pas encore ouvert)',
  format($$ INSERT INTO referentiel.import_contour (niveau, section_id, format, fichier_nom, fichier_cle, empreinte)
            VALUES ('SECTION', %L, 'GEOJSON', 's.geojson', 'imports/s.geojson', repeat('a', 64)) $$, pg_temp.id('s01_l1_sec1')),
  'ck_import_contour_niveau');
SELECT pg_temp.echec('import de contours : la cible doit correspondre au niveau annoncé',
  format($$ INSERT INTO referentiel.import_contour (niveau, secteur_id, format, fichier_nom, fichier_cle, empreinte)
            VALUES ('LOT', %L, 'GEOJSON', 'x.geojson', 'imports/x.geojson', repeat('a', 64)) $$, pg_temp.id('s01')),
  'ck_import_contour_cible');
SELECT pg_temp.echec('import de contours : une seule cible à la fois',
  format($$ INSERT INTO referentiel.import_contour (niveau, secteur_id, lot_id, format, fichier_nom, fichier_cle, empreinte)
            VALUES ('SECTEUR', %L, %L, 'GEOJSON', 'x.geojson', 'imports/x.geojson', repeat('a', 64)) $$, pg_temp.id('s01'), pg_temp.id('s01_l1')),
  'ck_import_contour_cible');

-- Profil, motif et acteur dans la piste d'audit.
SELECT pg_temp.verifie('audit : la promotion est tracée avec son motif et l''ancien profil',
  (SELECT count(*) >= 1 FROM audit.evenement
    WHERE table_nom = 'compte' AND operation = 'U' AND objet_id = pg_temp.id('co_saph')::text
      AND anciennes ->> 'profil' = 'CO' AND nouvelles ->> 'profil' = 'CS' AND motif = 'Promotion 2026'
      AND acteur_compte_id = pg_temp.id('cd_saph') AND acteur_profil = 'CD'));
SELECT pg_temp.verifie('audit : la correction d''un matricule est tracée avec motif et acteur administrateur',
  (SELECT count(*) = 1 FROM audit.evenement
    WHERE table_nom = 'compte' AND objet_id = pg_temp.id('cs2_saph')::text
      AND nouvelles ->> 'numero' = '00777' AND anciennes ->> 'numero' = '00003'
      AND motif = 'Alignement sur le système de la SAPH' AND acteur_profil = 'AD'));
SELECT pg_temp.verifie('audit : la désactivation d''un compte est tracée',
  (SELECT count(*) >= 1 FROM audit.evenement
    WHERE table_nom = 'compte' AND objet_id = pg_temp.id('mo_saph')::text AND nouvelles ->> 'statut' = 'DESACTIVE'));
SELECT pg_temp.verifie('audit : la reprise est tracée',
  (SELECT count(*) = 1 FROM audit.evenement
    WHERE table_nom = 'reprise_compte' AND operation = 'I' AND nouvelles ->> 'motif' = 'Démission'));
SELECT pg_temp.verifie('audit : le retrait d''une permission est tracé (D)',
  (SELECT count(*) >= 1 FROM audit.evenement
    WHERE table_nom = 'profil_permission' AND operation = 'D' AND objet_id = 'SE:VISUALISER_CARTE' AND acteur_profil = 'AD'));
SELECT pg_temp.verifie('audit : la désactivation d''une organisation est tracée',
  (SELECT count(*) >= 1 FROM audit.evenement
    WHERE table_nom = 'operateur' AND objet_id = pg_temp.id('rev2')::text AND nouvelles ->> 'actif' = 'false'));

-- Connexions.
SELECT pg_temp.acteur('inconnu');
SELECT audit.tracer_connexion(pg_temp.id('mo3_saph'), 'SAPH-MO-00350', 'SUCCES', '10.0.0.8', 'Android');
SELECT audit.tracer_connexion(NULL, 'SAPH-99998', 'ECHEC', '10.0.0.9');
SELECT pg_temp.verifie('audit : une connexion réussie est tracée avec compte, utilisateur et profil',
  (SELECT count(*) = 1 FROM audit.evenement
    WHERE table_nom = 'connexion' AND acteur_compte_id = pg_temp.id('mo3_saph')
      AND acteur_utilisateur_id = pg_temp.id('u_mo3_saph') AND acteur_profil = 'MO'
      AND nouvelles ->> 'resultat' = 'SUCCES' AND adresse_ip = '10.0.0.8'));
SELECT pg_temp.verifie('audit : un échec avec un matricule inconnu est tracé sans compte',
  (SELECT count(*) = 1 FROM audit.evenement
    WHERE table_nom = 'connexion' AND acteur_compte_id IS NULL AND objet_id = 'SAPH-99998'
      AND nouvelles ->> 'resultat' = 'ECHEC'));
SELECT pg_temp.echec('audit : résultat de connexion inconnu refusé',
  $$ SELECT audit.tracer_connexion(NULL, 'X', 'PEUT-ETRE') $$, 'inconnu');
CREATE TEMP TABLE compteur_connexion AS SELECT count(*) AS n FROM audit.evenement;
UPDATE iam.compte SET derniere_connexion = now(), echecs_connexion = 2, verrouille_jusqu_a = NULL WHERE id = pg_temp.id('mo3_saph');
SELECT pg_temp.verifie('audit : derniere_connexion et compteur d''échecs ne produisent pas d''événement de données',
  (SELECT n FROM compteur_connexion) = (SELECT count(*) FROM audit.evenement));
SELECT pg_temp.ok('rôle applicatif : peut tracer une connexion',
  $$ SET LOCAL ROLE sysde_app; SELECT audit.tracer_connexion(NULL, 'SAPH-CD-00001', 'DECONNEXION') $$);
SELECT pg_temp.echec('rôle applicatif : ne peut pas modifier les permissions du référentiel de profils',
  $$ SET LOCAL ROLE sysde_app; INSERT INTO iam.profil (code, libelle, organisme, niveau, rang, ordre) VALUES ('ZZ', 'x', false, 'AUCUN', 9, 99) $$,
  'permission denied');
SELECT pg_temp.echec('rôle applicatif : les historiques de reprise sont en ajout seul',
  $$ SET LOCAL ROLE sysde_app; UPDATE iam.reprise_compte SET motif = 'x' $$, 'permission denied');
SELECT pg_temp.echec('rôle applicatif : les anciens matricules sont en ajout seul',
  $$ SET LOCAL ROLE sysde_app; UPDATE iam.alias_matricule SET motif = 'x' $$, 'permission denied');

SELECT pg_temp.acteur('cs_saph');
SELECT pg_temp.ok('rôle applicatif : création de comptes, affectation, désactivation et reprise de bout en bout',
  format($$ SET LOCAL ROLE sysde_app;
     INSERT INTO iam.utilisateur (nom, prenoms) VALUES ('App1', 'Role'), ('App2', 'Role');
     INSERT INTO iam.compte (utilisateur_id, operateur_id, profil, statut, mot_de_passe_hash)
       SELECT id, %L, 'MO', 'ACTIF', 'h' FROM iam.utilisateur WHERE nom IN ('App1', 'App2');
     INSERT INTO iam.affectation (compte_id, operateur_id, secteur_id, lot_id, section_id)
       SELECT c.id, c.operateur_id, %L, %L, %L FROM iam.compte c JOIN iam.utilisateur u ON u.id = c.utilisateur_id WHERE u.nom = 'App1';
     UPDATE iam.compte SET statut = 'DESACTIVE' WHERE utilisateur_id = (SELECT id FROM iam.utilisateur WHERE nom = 'App1');
     SELECT iam.reprendre_compte(a.id, b.id, 'Essai applicatif')
       FROM iam.compte a JOIN iam.utilisateur ua ON ua.id = a.utilisateur_id AND ua.nom = 'App1',
            iam.compte b JOIN iam.utilisateur ub ON ub.id = b.utilisateur_id AND ub.nom = 'App2' $$,
    pg_temp.id('saph'), pg_temp.id('s01'), pg_temp.id('s01_l1'), pg_temp.id('s01_l1_sec3')));
SELECT pg_temp.verifie('rôle applicatif : la reprise a bien transféré la section au successeur',
  (SELECT count(*) = 1 FROM iam.affectation f JOIN iam.compte c ON c.id = f.compte_id JOIN iam.utilisateur u ON u.id = c.utilisateur_id
    WHERE u.nom = 'App2' AND f.fin IS NULL AND f.section_id = pg_temp.id('s01_l1_sec3')));

-- Contours PostGIS (V8) : colonnes typées, WGS 84, requêtes spatiales du contrôle d'import.
SELECT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'postgis') AS a_postgis \gset
\if :a_postgis
SELECT pg_temp.acteur('ad_firca');
SELECT pg_temp.ok('contour : un secteur et deux lots reçoivent un MultiPolygon en WGS 84',
  $$ UPDATE referentiel.secteur SET geom = ST_Multi(ST_MakeEnvelope(-5.0, 5.0, -4.0, 6.0, 4326)) WHERE id = pg_temp.id('s01');
     UPDATE referentiel.lot SET geom = ST_Multi(ST_MakeEnvelope(-4.9, 5.1, -4.5, 5.5, 4326)) WHERE id = pg_temp.id('s01_l1');
     UPDATE referentiel.lot SET geom = ST_Multi(ST_MakeEnvelope(-4.2, 5.6, -3.8, 5.9, 4326)) WHERE id = pg_temp.id('s01_l3') $$);
SELECT pg_temp.echec('contour : un autre système de coordonnées est refusé par la colonne',
  $$ UPDATE referentiel.lot SET geom = ST_Multi(ST_Transform(ST_MakeEnvelope(-4.9, 5.1, -4.5, 5.5, 4326), 32630))
      WHERE id = pg_temp.id('s01_l2') $$, 'SRID');
UPDATE referentiel.lot SET geom = ST_MakeEnvelope(-4.9, 5.1, -4.5, 5.5, 4326) WHERE id = pg_temp.id('s01_l2');
SELECT pg_temp.verifie('contour : un simple polygone est promu en MultiPolygon par PostGIS',
  (SELECT GeometryType(geom) = 'MULTIPOLYGON' FROM referentiel.lot WHERE id = pg_temp.id('s01_l2')));
SELECT pg_temp.echec('contour : un point est refusé par la colonne',
  $$ UPDATE referentiel.lot SET geom = ST_SetSRID(ST_MakePoint(-4.5, 5.5), 4326) WHERE id = pg_temp.id('s01_l2') $$, 'MultiPolygon');
SELECT pg_temp.verifie('contrôle d''import : un lot dans son secteur ne déborde pas',
  (SELECT ST_Within(l.geom, s.geom) FROM referentiel.lot l JOIN referentiel.secteur s ON s.id = l.secteur_id WHERE l.id = pg_temp.id('s01_l1')));
SELECT pg_temp.verifie('contrôle d''import : le débordement d''un lot est mesuré en surface (avertissement non bloquant)',
  (SELECT NOT ST_Within(l.geom, s.geom)
          AND ST_Area(ST_Difference(l.geom, s.geom)::geography) > 0
          AND ST_Area(ST_Difference(l.geom, s.geom)::geography) < ST_Area(l.geom::geography)
     FROM referentiel.lot l JOIN referentiel.secteur s ON s.id = l.secteur_id WHERE l.id = pg_temp.id('s01_l3')));
SELECT pg_temp.verifie('contrôle d''import : une géométrie invalide (nœud papillon) est détectée',
  NOT ST_IsValid(ST_GeomFromText('POLYGON((0 0, 1 1, 1 0, 0 1, 0 0))', 4326)));
SELECT pg_temp.verifie('contrôle d''import : le recoupement de deux lots voisins est détecté',
  (SELECT ST_Overlaps(ST_MakeEnvelope(-4.9, 5.1, -4.5, 5.5, 4326), ST_MakeEnvelope(-4.7, 5.3, -4.3, 5.7, 4326))));
SELECT pg_temp.verifie('index spatiaux GiST présents sur secteur, lot et section',
  (SELECT count(*) = 3 FROM pg_indexes WHERE schemaname = 'referentiel' AND indexdef LIKE '%USING gist (geom)%'));
SELECT pg_temp.verifie('contour de section : colonne réservée, vide',
  (SELECT count(*) = 0 FROM referentiel.section WHERE geom IS NOT NULL));
SELECT pg_temp.verifie('audit : le contour d''un secteur modifié est signalé sans copier la géométrie',
  (SELECT count(*) = 1 FROM audit.evenement
    WHERE table_nom = 'secteur' AND objet_id = pg_temp.id('s01')::text AND nouvelles = '{"geom": "modifié"}'::jsonb));
\endif

-- Partitions : l'événement du jour est dans la partition du mois, pas dans la partition de secours.
SELECT pg_temp.verifie('audit : événements rangés dans la partition mensuelle',
  (SELECT count(*) = 0 FROM audit.evenement_defaut));
SELECT pg_temp.verifie('audit : 13 partitions mensuelles préparées',
  (SELECT count(*) = 13 FROM pg_inherits i JOIN pg_class p ON p.oid = i.inhparent JOIN pg_class c ON c.oid = i.inhrelid
    WHERE p.relname = 'evenement' AND c.relname <> 'evenement_defaut'));

-- ---------------------------------------------------------------------------
-- Bilan
-- ---------------------------------------------------------------------------
\pset tuples_only off
SELECT CASE WHEN ok THEN 'OK     ' ELSE 'ÉCHEC  ' END AS statut, nom, coalesce(detail, '') AS detail
  FROM resultats WHERE NOT ok OR :'AFFICHER_TOUT' = 'oui';
SELECT count(*) FILTER (WHERE ok) AS reussis, count(*) FILTER (WHERE NOT ok) AS echecs FROM resultats;
SELECT count(*) FILTER (WHERE NOT ok) > 0 AS a_des_echecs FROM resultats \gset
\if :a_des_echecs
  \echo 'Des tests ont échoué.'
  SELECT 1 / 0;
\endif
