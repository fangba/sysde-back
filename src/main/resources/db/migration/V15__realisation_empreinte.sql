-- ============================================================================
-- V15 — Réalisations : empreinte du contenu reçu (envois hors ligne rejouables)
-- ============================================================================
-- Le mobile peut renvoyer le même envoi si la réponse s'est perdue. L'empreinte (SHA-256 du contenu, hors numéro de version
-- attendu) permet de reconnaître ce rejeu : il est accepté sans rien changer, au lieu d'un conflit de version.
ALTER TABLE planning.realisation ADD COLUMN empreinte char(64);
