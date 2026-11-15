-- ============================================================================
-- V12 — Comptes : recherche rapide et coupure des jetons d'accès
-- ============================================================================
-- * Recherche par nom, prénoms ou matricule sur un million de comptes (EXI-02) : index trigramme.
-- * jetons_valides_depuis : tout jeton d'accès émis avant cet instant est refusé. Sert à couper les sessions
--   d'un compte qui reste actif (réinitialisation du mot de passe, COM-07).
-- ============================================================================
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX ix_utilisateur_recherche ON iam.utilisateur
    USING gin ((lower(nom || ' ' || prenoms)) gin_trgm_ops) WHERE supprime_le IS NULL;
CREATE INDEX ix_compte_matricule_recherche ON iam.compte
    USING gin ((lower(matricule)) gin_trgm_ops) WHERE supprime_le IS NULL;

ALTER TABLE iam.compte ADD COLUMN jetons_valides_depuis timestamptz;
