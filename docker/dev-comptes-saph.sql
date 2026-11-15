-- Un compte par profil pour l'opérateur technique SAPH, POUR LE DÉVELOPPEMENT UNIQUEMENT.
-- Mot de passe commun : Firca-Dev-2026 (aucun changement exigé à la première connexion).
-- Prérequis : le compte FIRCA-00001 (docker/dev-admin.sql).
--
-- Usage (depuis sysde-back) :
--   docker exec -i docker-db-1 psql -U sysde -d sysde -v ON_ERROR_STOP=1 < docker\dev-comptes-saph.sql
--
-- La base impose la hiérarchie de création, le script agit donc comme chaque créateur tour à tour :
--   administrateur -> SAPH + chef de département -> chef secteur -> les six autres profils.
-- Les affectations territoriales (secteur, lot, section) ne sont pas créées : les secteurs n'existent pas encore.
-- Relançable : un profil déjà créé (même e-mail) est ignoré.

CREATE EXTENSION IF NOT EXISTS pgcrypto;   -- uniquement pour le hash bcrypt ; retirée en fin de script

DO $$
DECLARE
    v_hash      text := crypt('Firca-Dev-2026', gen_salt('bf', 12));
    v_admin     uuid;
    v_saph      uuid;
    v_cd        uuid;
    v_cs        uuid;
    r           record;
    v_util      uuid;
BEGIN
    SELECT id INTO v_admin FROM iam.compte WHERE matricule = 'FIRCA-00001' AND statut = 'ACTIF';
    IF v_admin IS NULL THEN
        RAISE EXCEPTION 'Compte FIRCA-00001 introuvable : exécutez d''abord docker/dev-admin.sql.';
    END IF;

    -- 1. Administrateur : opérateur SAPH et chef de département
    PERFORM set_config('app.compte_id', v_admin::text, true);
    SELECT id INTO v_saph FROM referentiel.operateur WHERE acronyme = 'SAPH' AND supprime_le IS NULL;
    IF v_saph IS NULL THEN
        INSERT INTO referentiel.operateur (type, acronyme, denomination)
        VALUES ('TECHNIQUE', 'SAPH', 'SAPH') RETURNING id INTO v_saph;
    END IF;

    FOR r IN SELECT * FROM (VALUES
        (1, 'CD', 'CHEF DEPARTEMENT', 'Dev', 'cd.dev@saph.local'),
        (2, 'CS', 'CHEF SECTEUR',     'Dev', 'cs.dev@saph.local'),
        (3, 'CF', 'CONTROLEUR SAIGNEE',  'Dev', 'cf.dev@saph.local'),
        (3, 'CO', 'CONTROLEUR ORDINAIRE','Dev', 'co.dev@saph.local'),
        (3, 'FS', 'FORMATEUR',        'Dev', 'fs.dev@saph.local'),
        (3, 'MO', 'MONITEUR',         'Dev', 'mo.dev@saph.local'),
        (3, 'ES', 'EQUIPE SPECIALE',  'Dev', 'es.dev@saph.local'),
        (3, 'SE', 'SECRETAIRE',       'Dev', 'se.dev@saph.local')
    ) AS t(etape, profil, nom, prenoms, email) ORDER BY etape, profil
    LOOP
        -- acteur : AD pour le CD, CD pour le CS, CS pour les autres
        PERFORM set_config('app.compte_id',
            (CASE r.etape WHEN 1 THEN v_admin WHEN 2 THEN v_cd ELSE v_cs END)::text, true);

        IF EXISTS (SELECT 1 FROM iam.utilisateur WHERE lower(email) = r.email AND supprime_le IS NULL) THEN
            RAISE NOTICE 'Profil % déjà créé : ignoré.', r.profil;
        ELSE
            INSERT INTO iam.utilisateur (nom, prenoms, email) VALUES (r.nom, r.prenoms, r.email)
            RETURNING id INTO v_util;
            INSERT INTO iam.compte (utilisateur_id, operateur_id, profil, statut, mot_de_passe_hash, doit_changer_mot_de_passe)
            VALUES (v_util, v_saph, r.profil, 'ACTIF', v_hash, false);
        END IF;

        IF r.profil = 'CD' THEN
            SELECT c.id INTO v_cd FROM iam.compte c JOIN iam.utilisateur u ON u.id = c.utilisateur_id
             WHERE lower(u.email) = r.email AND c.supprime_le IS NULL;
        ELSIF r.profil = 'CS' THEN
            SELECT c.id INTO v_cs FROM iam.compte c JOIN iam.utilisateur u ON u.id = c.utilisateur_id
             WHERE lower(u.email) = r.email AND c.supprime_le IS NULL;
        END IF;
    END LOOP;
END $$;

DROP EXTENSION IF EXISTS pgcrypto;

SELECT c.matricule, c.profil, p.libelle, c.statut
  FROM iam.compte c JOIN iam.profil p ON p.code = c.profil
 WHERE c.matricule LIKE 'SAPH-%' ORDER BY p.ordre;
