-- ============================================================================
-- V17 — Appareils mobiles (suivi de synchronisation)
-- ============================================================================
-- Un appareil = une installation de l'application (identifiant UUID tiré par l'appareil à la première utilisation).
-- L'appareil signale lui-même sa dernière synchronisation et le nombre d'éléments en attente d'envoi ; le serveur
-- ne le déduit pas des réalisations reçues (un collaborateur sans activité paraîtrait « en retard » à tort).
-- Pas d'audit : donnée de suivi à fort volume, sans valeur de traçabilité métier.
CREATE TABLE iam.appareil (
    id                 uuid        NOT NULL,                 -- choisi par l'appareil
    compte_id          uuid        NOT NULL REFERENCES iam.compte (id),
    modele             varchar(100),
    systeme            varchar(50),
    version_application varchar(30),
    en_attente         integer     NOT NULL DEFAULT 0 CHECK (en_attente >= 0),
    derniere_synchro_le timestamptz NOT NULL DEFAULT now(),  -- dernier contact réussi vu par le serveur
    premiere_vue_le    timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (compte_id, id)
);
CREATE INDEX ix_appareil_synchro ON iam.appareil (derniere_synchro_le);

-- ============================================================================
-- Envoi seul d'un compte désactivé (iam.compte_peut_synchroniser, 30 jours)
-- ============================================================================
-- Jusqu'ici la désactivation révoquait aussitôt les jetons de rafraîchissement : un mobile resté hors ligne ne pouvait
-- alors plus rien envoyer, le délai de 30 jours restait lettre morte. Désormais seule la remise en attente coupe les
-- sessions ; un compte désactivé garde son rafraîchissement, mais le serveur le réduit à l'envoi (JwtEtatConverter,
-- SecurityConfig) et le refuse une fois le délai écoulé (SessionService, iam.compte_peut_synchroniser).
CREATE OR REPLACE FUNCTION iam.couper_sessions_compte() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.statut = 'EN_ATTENTE' AND OLD.statut IS DISTINCT FROM NEW.statut THEN
    UPDATE iam.jeton_rafraichissement SET revoque_le = now()
     WHERE compte_id = NEW.id AND revoque_le IS NULL;
  END IF;
  RETURN NULL;
END $$;
