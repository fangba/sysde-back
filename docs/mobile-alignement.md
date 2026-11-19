# Alignement mobile ↔ back (lecture du 18/11/2026, mis à jour le 19/11/2026 : format du matricule)

Lecture seule : rien n'a été modifié dans `sysde-mobile`. Sources : `MOBILE-CONTEXTE.md`, le code Flutter (`lib/`, 88 fichiers, lu pour les couches données et domaine, la file d'envoi, la base locale, les écrans de synchronisation et de mise à jour du planning) et le back tel qu'il est (`/mobile/amorcage`, `/realisations`, `/participants`, `/auth/*`).
Ce document remplace les §4 et §5 de `MOBILE-CONTEXTE.md`, périmés depuis l'API mobile du 15/11 et le jeton à 15 min du 18/11. Légende : **[écart]** le mobile et le back ne se correspondent pas · **[décision]** à trancher par Franck / le client · **[à vérifier]** non lu ou non testé.

## 1. Les deux parcours voulus

1. **Première connexion, puis usage sans réseau.** Connexion par matricule en ligne une seule fois ; ensuite l'application s'ouvre sans réseau. Tout le programme du technicien est copié en local : il travaille sans connexion.
2. **Envoi, puis mise à jour.** Quand il a fini, il envoie ses saisies (réseau nécessaire). Si son planning change, il le met à jour à la demande.

## 2. Ce qui existe

| Côté | Existe | N'existe pas |
| --- | --- | --- |
| Mobile | Écrans complets sur données de démonstration ; base SQLite (`control`, `report`, `outbox`) ; file d'envoi qui se remplit ; écran de synchronisation et écran de mise à jour du planning (simulés) | Tout appel réseau (ni `dio`, ni `http`) ; connexion réelle ; stockage de jeton ; copie locale des activités, participants, plantations, contours, profil, date de dernière synchro |
| Back | `POST /auth/login`, `/auth/refresh` (rotation), `/auth/logout` ; `GET /mobile/amorcage` (plan, activités du profil avec fréquences et durée, critères, secteurs, lots, sections, groupes, état des réalisations, notifications non lues) ; `PUT /realisations/{id}` (état complet, rejouable, `expectedVersion`) ; `GET`/`POST`/`PUT /participants` ; `POST /mobile/synchronisation` ; envoi seul pour compte désactivé | Voir §4 |

## 3. Correspondance des modèles

| Mobile | Back | Remarque |
| --- | --- | --- |
| `Activity.id` (`'a1'`, texte) | `PlanLine.id` (UUID) | Le mobile doit adopter l'UUID de la ligne (voir écart E2) |
| `Activity.category` + `visitType` / `trainingType` / `meetingType` | `PlanActivity.code` (`VISITE_PLANTATION`, `VISITE_CONFORMITE`, `VISITE_INTERVENTION`, `DECOUVERTE_VERGER`, `FORMATION_INITIALE`, `PERFECTIONNEMENT_IN_SITU`, `SUIVI_POST_PERFECTIONNEMENT`, `RECYCLAGE_SAIGNEURS`, `GROUPE_VULGARISATION`, `CONFERENCE_THEMATIQUE`, `VOYAGE_ETUDE`) | Table de traduction à écrire. **`fair` (foire) n'a pas d'équivalent** (écart E8) |
| `Activity.durationDays` (en dur : 20 / 3 / 1) | `PlanActivity.durationDays` | À lire du référentiel |
| `Activity.date`, `lastDay` | `PlanLine.date`, `endDate` | |
| `Activity.place` (texte), `section`, `lot`, `village` | `lotNumber`, `sectionName`, groupe (`Group.village`) | Le texte `place` se recompose |
| `Activity.subjectName` (plantation, saigneur, groupe) | `plantationRef`, `groupId` | Le back ne porte que la **référence** de la plantation, pas son nom ni son propriétaire (écart E7) |
| `Activity.rank` (1re visite, session 2…) | — | Se calcule sur l'appareil |
| `ActivityStatus.todo / inProgress / done` | `EN_COURS` / `TERMINEE` ; « à faire » = pas de réalisation | |
| `Attendance.present / absent` (une par participant) | `AttendanceInput{participantId, day, present}` | **Par jour** au back (écart E4) |
| `Criterion` (5) et `Rating` good / average / poor | `EvaluationInput{criterion, level}` : `CONSOMMATION`, `PROFONDEUR`, `PENTE`, `BLESSURES`, `PROPRETE` ; `BON`, `MOYEN`, `MAUVAIS` | Traduction à l'envoi |
| `Verdict` compliant / nonCompliant + commentaire | `AppreciationInput{verdict, comment}` : `CONFORME`, `NON_CONFORME` | |
| `report.done` (`doneAt`) | `report` (texte, ≤ 20 000) | Le mobile n'a pas de contenu de rapport (écart E5) |
| `Participant.id` (`'s1'`), nom unique, rôle, téléphone | `Participant{id UUID, lastName, firstName, phone, village, externalRef}` | Nom à scinder ; le rôle (saigneur / planteur) n'existe pas au back |
| `UserProfile` | `Me{accountId, matricule, profile, operatorId}` + `user` du login | Secteur, lot, sections : à tirer des affectations |
| `outbox` : `control.upsert` par participant, `report.done` par activité | `PUT /realisations/{id}` : **une réalisation complète par activité** | Écart E3 |

## 4. Écarts et trous

**E1. Qui sont les participants d'une activité ? [écart] [décision]**
Le mobile liste, par activité, les gens à pointer : saigneurs convoqués (formation), membres du groupe (vulgarisation), saigneurs et planteur de la plantation (visite). Le back n'a **aucun lien** entre une ligne de planning et ses participants : les participants sont une liste par opérateur (`GET /participants?q=`, paginée), les groupes n'ont ni membres, et les saigneurs d'une plantation viennent de la base externe. Sans cela, l'écran « liste de présence » n'a rien à afficher hors démo. Options : (a) l'agent choisit ses participants dans la liste de l'opérateur, ou en crée sur place (`POST /participants`, déjà prévu) ; (b) le back ajoute les membres d'un groupe et une convocation par ligne ; (c) les saigneurs d'une plantation viennent de l'API externe (contrat provisoire). À trancher avant tout, car cela change le modèle.

**E2. Identifiants [écart]**
Le mobile utilise des textes (`a1`, `s1`). Il faut les UUID du back : `lineId` pour l'activité, UUID v7 générés **sur l'appareil** pour une réalisation et pour un participant créé hors ligne. L'identifiant de la réalisation doit être **gardé** sur l'appareil dès la première saisie : renvoyer avec un autre identifiant donne 409 « cette ligne a déjà une réalisation ».

**E3. Granularité d'envoi [écart]**
Le mobile envoie un message par participant et par champ ; le back reçoit l'**état complet** d'une réalisation (présences, évaluations, appréciations, rapport, début, fin, position, observations). Il faut construire la réalisation **au moment de l'envoi** à partir des lignes `control`, du rapport et des champs manquants (E5), puis remplacer `control.upsert` / `report.done` par un message `realisation.upsert` par activité. Les erreurs à gérer : 400 (règles de `verifier`), 403 (ligne d'un autre compte), 409 `CONFLIT_DE_VERSION`, « terminée ne repasse pas en cours ».

**E4. Présence par jour [écart] [décision]**
Le back veut une présence par participant **et par jour**, dans la période de l'activité. La formation initiale dure 20 jours, le recyclage 3 : le mobile ne garde qu'une présence par participant. Une visite d'un jour n'est pas concernée. À décider pour les formations longues : un pointage par jour (l'écran change), ou une présence valable pour toute la période (le back doit alors l'accepter).

**E5. Champs que le mobile ne saisit pas encore [écart]**
`startedAt` (obligatoire), `endedAt` (obligatoire si terminée), position GPS (obligatoire pour une activité spontanée comme la découverte du verger), précision, observations, **texte du rapport**. Le mobile a un « rapport fait » sans contenu ; l'écran du rapport est « à définir » (contexte §5.7). [à vérifier] : la fiche d'activité affiche position et photos en démo, rien n'est capté.

**E6. Plan et réalisations déjà faites [écart] [décision]**
- Le back **ne conserve pas les identifiants de lignes** : à chaque enregistrement d'un planning, les lignes non verrouillées sont supprimées (suppression logique) puis recréées. Comparer deux téléchargements **par identifiant** donnerait « tout retiré, tout ajouté ». La comparaison doit se faire sur la clé naturelle (activité, date, lot ou section, plantation ou groupe).
- Une réalisation faite hors ligne sur une ligne remplacée entre-temps recevrait « ligne de planning inconnue ». À décider : rattacher par clé naturelle à la nouvelle ligne, ou l'envoyer comme activité spontanée.
- Le back n'a pas, dans la partie lue, de contrôle du statut du planning à l'envoi, et `/mobile/amorcage` renvoie le plan quel que soit son statut. Le mobile affiche-t-il les lignes d'un planning non validé ? Ton écran de mise à jour parle de « planning validé ». [décision] : le mobile ne travaille que sur les activités validées, et le back le refuse-t-il sinon ? [à vérifier].

**E7. Plantations [écart] [à vérifier]**
Le mobile affiche le nom de la plantation et de son propriétaire ; la ligne ne porte que `plantationRef`. Le back a un module plantations (base externe en lecture seule, plantations affectées aux sections) : [à vérifier] ce que le mobile peut lire (liste affectée, détail) pour résoudre la référence, et le sort du relevé de nouvelle plantation (contexte §3, ouvert).

**E8. Types sans équivalent [écart] [décision]**
`fair` (foire, avec contrôle) n'existe pas dans le programme du back. `orchard` (découverte du verger) existe : activité spontanée, sans ligne, avec `plantationRef` et position obligatoires.

**E9. Anomalies [décision, déjà ouvert]**
Le mobile a un champ `anomaly` (hors secteur, durée courte, doublon, sans position) ; le back n'en calcule pas. Qui les calcule, avec quels seuils ?

**E10. Photos** : en attente (décision de Franck). Les écrans existent, aucun stockage côté appareil ni serveur.

## 5. Connexion et session (parcours 1)

- **Première connexion** : `POST /auth/login` par matricule. On garde, dans le stockage sécurisé d'Android (`flutter_secure_storage`) : le **jeton de rafraîchissement** (30 jours, avec rotation) et son échéance. On garde en base locale l'identité (profil, secteur, lot, sections) pour l'afficher hors ligne.
- **Le mot de passe n'est jamais gardé**, ni en clair ni chiffré : une tablette perdue donnerait un mot de passe réutilisable sur le web. Recommandé en plus : verrouillage de l'appareil, ou un code PIN local, pour ouvrir l'application.
- **Ouverture sans réseau** : directe, depuis la session stockée. Aucun appel réseau n'est fait tant que l'agent travaille.
- **Envoi** : l'application rafraîchit le jeton d'accès (15 min) avec le jeton de rafraîchissement, puis envoie. La rotation impose de **garder le nouveau jeton avant d'envoyer** ; si la réponse du rafraîchissement est perdue (réseau), le jeton est réutilisé et la famille coupée : reconnexion complète. **[décision]** un délai de grâce côté back pour ce cas (déjà ouvert dans le contexte §4).
- **Au bout de 30 jours sans réseau** : jeton de rafraîchissement expiré, reconnexion avec le mot de passe avant d'envoyer ; les données locales restent. **[décision]** durée adaptée aux tournées longues ?
- **Mot de passe temporaire** : `mustChangePassword` renvoie vers `POST /auth/mot-de-passe` avant tout (403 `MOT_DE_PASSE_TEMPORAIRE` ailleurs).
- **Compte désactivé** : 403 hors envoi ; l'application ne tente alors que l'envoi (30 jours) ; 401 `SESSION_EXPIREE` ensuite.

### Changement du matricule (19/11/2026) : ce que le mobile doit prendre en compte

Le matricule est désormais **`ACRONYME-PROFIL-NUMÉRO`** (ex. `SAPH-CS-00001`, migration V20) au lieu de `ACRONYME-NUMÉRO` (`SAPH-00348`).

- **Connexion** : l'agent saisit le nouveau matricule. **Les anciens matricules n'ouvrent plus de session** (401), y compris pour les comptes existants : la migration les a tous convertis et gardés en alias. Le formulaire de connexion (`login_page.dart`) est pré-rempli avec `SAPH-00348` en démonstration : à remplacer par un exemple au nouveau format, ou par un champ vide.
- **Contrôle côté appareil** (s'il y en a un) : `^[A-Z0-9]+-[A-Z]{2}-[0-9]{5,7}$`, sans casse (le serveur met en majuscules). Le serveur reste juge ; ne pas bloquer un format que le back accepterait.
- **Affichage** : `UserProfile.matricule` (commentaire « ACRONYME-NUMERO » de `user_profile.dart`) et les données de démonstration (`demo_data.dart`, `profile.dart`) sont à mettre au nouveau format.
- **`reference`** : le champ renvoyé par le back est maintenant **identique au matricule** (il valait `PROFIL-ACRONYME-NUMÉRO`). Le mobile ne doit plus s'en servir comme d'une seconde identité.
- **Un changement de profil change le matricule.** Après une promotion ou une rétrogradation, `SAPH-CO-00500` devient `SAPH-CF-00500` (même numéro). Le compte, ses jetons de session et son identifiant (`accountId`) **ne changent pas** : l'appareil reste connecté. En revanche le matricule gardé en local (profil affiché, réglages) est périmé : le mettre à jour depuis `user.matricule` de `/auth/refresh` ou de `/auth/moi`. Les affectations du compte sont closes par le serveur à ce moment (voir §7 pour le rechargement du planning) : le technicien doit refaire une mise à jour de son planning.
- **Hors ligne** : sans réseau, l'appareil continue d'afficher le matricule enregistré ; il ne se corrige qu'au prochain contact avec le serveur.

## 6. Copie locale du programme (parcours 1, suite)

Au premier chargement et à chaque mise à jour : `GET /mobile/amorcage` (plan, activités du profil, critères, secteurs, lots, sections, groupes, états des réalisations) puis `GET /participants` paginé. Le mobile les range en base locale (migration v2 : profil, plan et lignes, activités du référentiel, critères, groupes, participants, réalisations locales avec leur UUID, leur version serveur, leur état). `lastSync` et `lastPlanningUpdate` quittent les valeurs fixes de la démo pour la base.

## 7. Mise à jour du planning (parcours 2)

Même appel `GET /mobile/amorcage`, puis comparaison avec la base locale sur la **clé naturelle** (E6) : lignes ajoutées, modifiées (date déplacée), retirées. Les `realisations[{id, lineId, status, version}]` du serveur indiquent ce qui y est déjà (saisie faite depuis un autre appareil, ou déjà envoyée) : le mobile les rapproche de ses réalisations locales avant de décider quoi renvoyer, et avec quelle `expectedVersion`. Les saisies locales non envoyées ne sont jamais écrasées par la mise à jour.

## 8. Ordre de travail proposé

1. **Décisions** : E1 (participants), E4 (présence par jour), E6 (statut du plan et réalisations déjà faites), E8 (foire), contenu du rapport (E5), délai de grâce du refresh, durée de 30 jours, code PIN.
2. **Back**, selon les décisions : ce qui manque pour E1 / E4 (si l'option le demande), contrôle du statut à l'envoi (E6), délai de grâce.
3. **Mobile, brique 1 : connexion et session** (login, stockage sécurisé, rafraîchissement, ouverture hors ligne, tests).
4. **Mobile, brique 2 : base locale v2 et chargement** (amorçage, participants, profil), remplacement des données de démonstration par la base.
5. **Mobile, brique 3 : envoi** (construction de la réalisation, `PUT /realisations/{id}`, signal de synchronisation, reprise sur coupure, erreurs 400 / 403 / 409).
6. **Mobile, brique 4 : mise à jour du planning** (comparaison sur clé naturelle).
7. Photos et projection TMCI : toujours en attente.
