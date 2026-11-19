-- Opérateurs techniques et dénominations complètes, POUR LE DÉVELOPPEMENT UNIQUEMENT.
-- Dénominations reprises des données de démonstration du front. Les adresses, e-mails et téléphones ne sont pas renseignés.
-- Prérequis : le compte FIRCA-AD-00001 (docker/dev-admin.sql).
--
-- Usage (depuis sysde-back) :
--   docker exec -i docker-db-1 psql -U sysde -d sysde -v ON_ERROR_STOP=1 < docker/dev-operateurs.sql
--
-- Relançable : un opérateur existant n'est complété que si sa dénomination est encore son sigle ; un opérateur absent est créé.
-- Le script agit comme l'administrateur (traçabilité) et ne change jamais un sigle ni un type.

DO $$
DECLARE
    v_admin uuid;
    r       record;
BEGIN
    SELECT id INTO v_admin FROM iam.compte WHERE matricule = 'FIRCA-AD-00001' AND statut = 'ACTIF';
    IF v_admin IS NULL THEN
        RAISE EXCEPTION 'Compte FIRCA-AD-00001 introuvable : exécutez d''abord docker/dev-admin.sql.';
    END IF;
    PERFORM set_config('app.compte_id', v_admin::text, true);

    FOR r IN SELECT * FROM (VALUES
        ('ORGANISME', 'APROMAC', 'Association professionnelle des producteurs de caoutchouc naturel'),
        ('ORGANISME', 'FIRCA',   'Fonds interprofessionnel pour la recherche et le conseil agricoles'),
        ('TECHNIQUE', 'SAPH',    'Société africaine de plantations d’hévéas'),
        ('TECHNIQUE', 'SOGB',    'Société des caoutchoucs de Grand-Béréby'),
        ('TECHNIQUE', 'TRCI',    'Tropical Rubber Côte d’Ivoire'),
        ('TECHNIQUE', 'EXAT',    'Exploitation agricole Téhui'),
        ('TECHNIQUE', 'CCP',     'Compagnie des caoutchoucs du Pakidié'),
        ('TECHNIQUE', 'IDH',     'L’Ivoirienne d’hévéa'),
        ('TECHNIQUE', 'CHC',     'Compagnie hévéicole de Cavally')
    ) AS t(type, acronyme, denomination) LOOP
        IF EXISTS (SELECT 1 FROM referentiel.operateur WHERE acronyme = r.acronyme AND supprime_le IS NULL) THEN
            UPDATE referentiel.operateur SET denomination = r.denomination
             WHERE acronyme = r.acronyme AND denomination = r.acronyme AND supprime_le IS NULL;
        ELSE
            INSERT INTO referentiel.operateur (type, acronyme, denomination) VALUES (r.type, r.acronyme, r.denomination);
        END IF;
    END LOOP;
END $$;
