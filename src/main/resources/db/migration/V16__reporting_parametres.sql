-- ============================================================================
-- V16 — Reporting : paramètre de détection des anomalies
-- ============================================================================
-- Seuil de « durée courte » (anomalie à vérifier). Valeur de départ à valider avec le client ; modifiable sans migration.
INSERT INTO util.parametre (cle, valeur, description) VALUES
  ('reporting.duree_courte_minutes', '15',
     'Durée (minutes) en dessous de laquelle une activité réalisée est signalée « courte » (à vérifier)');
