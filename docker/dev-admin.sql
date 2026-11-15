-- Premier compte administrateur FIRCA, POUR LE DÉVELOPPEMENT UNIQUEMENT.
-- Matricule : FIRCA-00001      Mot de passe : Firca-Dev-2026
-- Ne jamais exécuter ni conserver ce mot de passe en production.
--
-- Usage (depuis sysde-back) :
--   docker exec -i docker-db-1 psql -U sysde -d sysde -v ON_ERROR_STOP=1 < docker\dev-admin.sql
--
-- Sans compte connecté (app.compte_id absent), la base n'accepte que la création d'un
-- administrateur : c'est le mécanisme d'amorçage prévu. Le script peut être relancé sans effet.

CREATE EXTENSION IF NOT EXISTS pgcrypto;   -- uniquement pour calculer le hash bcrypt ; retirée en fin de script

DO $$
DECLARE
    v_operateur   uuid;
    v_utilisateur uuid;
BEGIN
    IF EXISTS (SELECT 1 FROM iam.compte WHERE matricule = 'FIRCA-00001') THEN
        RAISE NOTICE 'Le compte FIRCA-00001 existe déjà : rien à faire.';
        RETURN;
    END IF;

    SELECT id INTO STRICT v_operateur
      FROM referentiel.operateur WHERE acronyme = 'FIRCA' AND supprime_le IS NULL;

    INSERT INTO iam.utilisateur (nom, prenoms, email)
    VALUES ('ADMIN', 'FIRCA (développement)', 'admin.dev@firca.local')
    RETURNING id INTO v_utilisateur;

    -- numéro et matricule (FIRCA-00001) sont attribués par le trigger de création
    INSERT INTO iam.compte (utilisateur_id, operateur_id, profil, statut, mot_de_passe_hash, doit_changer_mot_de_passe)
    VALUES (v_utilisateur, v_operateur, 'AD', 'ACTIF', crypt('Firca-Dev-2026', gen_salt('bf', 12)), false);

    RAISE NOTICE 'Compte créé : FIRCA-00001 / Firca-Dev-2026';
END $$;

DROP EXTENSION IF EXISTS pgcrypto;

SELECT matricule, reference_compte, profil, statut FROM iam.compte WHERE matricule = 'FIRCA-00001';
