-- ============================================================================
-- V19 — Dénominations complètes des deux organismes
-- ============================================================================
-- V7 avait posé le sigle comme dénomination. On ne remplace que si la dénomination est encore celle de V7 : un administrateur
-- qui l'aurait déjà modifiée dans l'application garde sa saisie.
UPDATE referentiel.operateur
   SET denomination = 'Association professionnelle des producteurs de caoutchouc naturel'
 WHERE acronyme = 'APROMAC' AND denomination = 'APROMAC' AND supprime_le IS NULL;

UPDATE referentiel.operateur
   SET denomination = 'Fonds interprofessionnel pour la recherche et le conseil agricoles'
 WHERE acronyme = 'FIRCA' AND denomination = 'FIRCA' AND supprime_le IS NULL;
