# Planning d'activité : migration V10 (schéma `planning`)

Source : « Programme d'activité » fourni le 14/11/2026 (3 familles, 11 activités, rôles, fréquences, 5 critères d'évaluation). Testé sur PostgreSQL 16 (V1 à V10, PostGIS remplacé par un type texte pour le test) : 15 contrôles de règles réussis. Pas encore testé avec PostGIS ni sur PostgreSQL 18.

## Tables (15)
- **Référentiel** : `planning.activite` (11 lignes), `planning.activite_profil` (15 lignes : qui réalise quoi, fréquence, tranche de superficie, règle de calcul), `planning.critere_evaluation` (5 lignes).
- **Territoire** : `referentiel.affectation_plantation` (plantation de la base externe affectée à une section, une seule section ouverte par plantation).
- **Groupes** : `planning.groupe_vulgarisation`, `planning.participant`, `planning.membre_groupe`.
- **Planning** : `planning.plan_annuel` (un par compte et par année : brouillon, soumis, validé), `planning.ligne_planning`, `planning.soumission_activite`.
- **Réalisation** : `planning.realisation`, `planning.presence` (par jour), `planning.evaluation` (bon, moyen, mauvais par critère), `planning.appreciation`, `planning.rapport`.

## Règles portées par la base
- Une ligne n'est acceptée que si l'activité est réalisée par le profil du titulaire, est planifiable et si les dates sont dans l'année du plan.
- Une visite se fait en un jour (date de fin = date de début).
- La découverte du verger n'est pas planifiable (spontanée, géolocalisation).
- Groupe de vulgarisation : responsable = moniteur actif ; nom unique par section sans casse, accents ni espaces de bord.
- Rejet d'une soumission : motif obligatoire. Réalisation terminée : heure de fin obligatoire.
- Audit : plan, lignes, soumissions, groupes, activités, plantations affectées. Non audités (volumétrie) : participants, réalisations, présences, évaluations, appréciations, rapports.

## Fréquences enregistrées (`activite_profil`)
| Activité | Profil | Fréquence |
|---|---|---|
| Formation initiale | FS / CF | 12 par an / 2 par an, par lot ; 20 jours, 20 apprentis |
| Perfectionnement in situ | MO / CF | 3 par mois / 9 par mois |
| Suivi post-perfectionnement | CF | mensuel, moitié des moniteurs, 1 saigneur tiré au hasard chacun (règle en texte) |
| Recyclage des saigneurs | CF | 1 par an, 3 jours, février à mars |
| Visite de plantation | MO / CO / CS | 4 par an (< 25 ha) / 3 par an (25 à 50 ha) / 3 par an (> 50 ha), par plantation |
| Visite d'intervention | ES | 2 par an, par plantation |
| Visite de conformité | CO | 6 par mois (une plantation par moniteur, au hasard) |
| Découverte du verger | MO | non planifiée |
| Groupe de vulgarisation | MO | 4 par an, par groupe ; 6 groupes par moniteur |
| Conférence thématique / Voyage d'étude | CS | 4 par an chacun |

## Choix à confirmer
- **Évaluation des visites** : conservée (décision du client), bien que le programme n'en parle pas. Le groupe de vulgarisation, la conférence et le voyage n'en ont pas.
- **Perfectionnement par le CF** : le tableau du programme ne cite que MO, le texte cite aussi CF ; les deux sont enregistrés.
- **Tranches de superficie** : minimum inclus, maximum exclu. Une plantation d'exactement 50 ha tombe chez le chef secteur ; le programme ne le précise pas.
- **Participants** (saigneurs, apprentis, planteurs) : tenus dans SysDE (`planning.participant`) avec une référence externe facultative. À corriger si ces personnes viennent de la base externe.
- **Définition d'un saigneur défaillant** (moins de 3 critères maîtrisés) : non codée (« maîtrisé » = bon seulement, ou bon et moyen ?).
- **Contrôle des fréquences** : les règles sont des données ; qui les vérifie (front, back) reste à décider.
- Position GPS : latitude et longitude décimales (pas de type géométrique).
- Le mobile propose aussi un type « foire » (avec contrôle) qui n'existe pas dans le programme ; « Découverte du verger » manque dans le mobile.
