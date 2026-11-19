-- Réinitialisation de la base de développement, POUR LE DÉVELOPPEMENT UNIQUEMENT.
--
-- Efface toutes les données saisies (comptes, contrats, attributions, plannings, réalisations, notifications, audit…)
-- et garde :
--   * les données essentielles au fonctionnement, posées par les migrations : profils et permissions, activités du
--     programme et leurs fréquences, critères d'évaluation, paramètres, secteurs et lots ;
--   * les opérateurs (organismes et opérateurs techniques), tels qu'ils sont dans la base.
-- Puis crée deux administrateurs, avec la nomenclature ACRONYME-PROFIL-NUMÉRO :
--   FIRCA-AD-00001     et     APROMAC-AD-00001      Mot de passe (les deux) : admin123
-- Aucun changement de mot de passe n'est exigé à la première connexion.
--
-- Usage (depuis sysde-back) :
--   docker exec -i docker-db-1 psql -U sysde -d sysde -v ON_ERROR_STOP=1 < docker/dev-reinitialiser.sql
--
-- Une seule transaction : si une étape échoue, rien n'est changé. La base doit être au dernier schéma (migrations appliquées).
-- Ne jamais exécuter ni conserver ces mots de passe en production.

CREATE EXTENSION IF NOT EXISTS pgcrypto;   -- uniquement pour le hash bcrypt ; retirée en fin de script

BEGIN;

TRUNCATE
    audit.evenement,
    gestion.attribution_lot, gestion.attribution, gestion.exercice, gestion.retrait_lot, gestion.contrat,
    iam.affectation, iam.alias_matricule, iam.appareil, iam.compte_profil, iam.compteur_numero, iam.identite_externe,
    iam.import_compte_ligne, iam.import_compte, iam.jeton_compte, iam.jeton_rafraichissement, iam.notification,
    iam.reprise_compte, iam.compte, iam.utilisateur,
    planning.appreciation, planning.evaluation, planning.presence, planning.rapport, planning.realisation,
    planning.soumission_activite, planning.ligne_planning, planning.plan_annuel,
    planning.membre_groupe, planning.groupe_vulgarisation, planning.participant,
    referentiel.affectation_plantation, referentiel.import_contour, referentiel.section;

-- Sans compte connecté (app.compte_id absent), la base n'accepte que la création d'un administrateur : c'est le mécanisme
-- d'amorçage. Le numéro (00001, la numérotation repart à zéro) et le matricule sont attribués par la base.
DO $$
DECLARE
    r             record;
    v_operateur   uuid;
    v_utilisateur uuid;
BEGIN
    FOR r IN SELECT * FROM (VALUES
        ('FIRCA',   'admin@firca.local'),
        ('APROMAC', 'admin@apromac.local')
    ) AS t(acronyme, email) LOOP
        SELECT id INTO STRICT v_operateur
          FROM referentiel.operateur WHERE acronyme = r.acronyme AND supprime_le IS NULL;

        INSERT INTO iam.utilisateur (nom, prenoms, email)
        VALUES ('ADMIN', r.acronyme, r.email)
        RETURNING id INTO v_utilisateur;

        INSERT INTO iam.compte (utilisateur_id, operateur_id, profil, statut, mot_de_passe_hash, doit_changer_mot_de_passe)
        VALUES (v_utilisateur, v_operateur, 'AD', 'ACTIF', crypt('admin123', gen_salt('bf', 12)), false);
    END LOOP;
END $$;

COMMIT;

DROP EXTENSION IF EXISTS pgcrypto;

SELECT matricule, reference_compte, profil, statut, doit_changer_mot_de_passe FROM iam.compte ORDER BY matricule;
SELECT type, acronyme FROM referentiel.operateur WHERE supprime_le IS NULL ORDER BY type, acronyme;
