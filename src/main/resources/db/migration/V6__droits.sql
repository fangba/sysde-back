-- ============================================================================
-- V6 — Droits du rôle applicatif (moindre privilège)
-- ============================================================================
-- L'application lit et écrit les données métier ; elle ne peut ni modifier ni
-- supprimer la piste d'audit, ni la structure.
-- ============================================================================

GRANT USAGE ON SCHEMA util, referentiel, gestion, iam, audit TO sysde_app;
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA util TO sysde_app;

GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA referentiel, gestion, iam TO sysde_app;

-- Suppressions réelles autorisées uniquement là où le modèle les prévoit.
GRANT DELETE ON gestion.attribution_lot,
                iam.profil_permission,
                iam.jeton_compte,
                iam.jeton_rafraichissement,
                iam.import_compte_ligne
      TO sysde_app;

-- Les profils et les permissions (la liste elle-même) sont des données de référence maintenues
-- par migration ; seules les permissions accordées à chaque profil sont ajustables.
REVOKE INSERT, UPDATE ON iam.profil, iam.permission FROM sysde_app;

-- Piste d'audit : lecture seule.
GRANT SELECT ON audit.evenement TO sysde_app;
GRANT EXECUTE ON FUNCTION audit.assurer_partitions(integer) TO sysde_app;
GRANT EXECUTE ON FUNCTION audit.tracer_connexion(uuid, text, text, text, text) TO sysde_app;

-- Les prochaines tables créées par migration suivent la même règle.
ALTER DEFAULT PRIVILEGES IN SCHEMA referentiel, gestion, iam
    GRANT SELECT, INSERT, UPDATE ON TABLES TO sysde_app;

-- Paramètres : lecture et modification. Historiques (retraits de lots, reprises, anciens matricules) : ajout seul.
GRANT SELECT, INSERT, UPDATE ON util.parametre TO sysde_app;
REVOKE UPDATE ON gestion.retrait_lot, iam.reprise_compte, iam.alias_matricule FROM sysde_app;
