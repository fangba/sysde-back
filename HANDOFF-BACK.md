# SysDE back — passation pour Claude Code (14/11/2026)

Tu reprends l'implémentation du back-end de SysDE (encadrement technique de l'hévéa, Côte d'Ivoire). Le propriétaire parle français et veut des réponses brèves, précises, sans complaisance : conteste les idées faibles. Code, commentaires, messages d'erreur et noms métier en **français** (comme l'existant).

## Lire d'abord (dans `docs/`)
`decisions-back.md` (décisions, socle) · `endpoints-back.md` (routes que le front web appelle ; 🔴 = à faire) · `cahier-des-charges-partie1.md` (règles métier validées, sections 1 à 8) · `planning-v10.md` (schéma planning) · `mobile-reference-back.md` (besoins du mobile Flutter hors ligne).
Règle permanente : quand une route ou une règle change, mets à jour `docs/endpoints-back.md` et `docs/decisions-back.md` (le propriétaire les recopie dans son projet Claude).

## Stack et exécution
- Spring Boot 4.1.0, Java 25, Maven. PostgreSQL 18 + PostGIS en Docker (`docker/compose.yml`, conteneur `docker-db-1`, port **5433**, base/utilisateur/mot de passe `sysde`). Flyway seul maître du schéma (migrations `V1` à `V10` dans `src/main/resources/db/migration`), Hibernate en `validate`.
- Comptes de développement : `docker/dev-admin.sql` (FIRCA-00001, mot de passe `Firca-Dev-2026`) puis `docker/dev-comptes-saph.sql` (un compte par profil chez SAPH).
- Front Angular : `D:\projet_sysde\sysde-front`. Mobile Flutter : `D:\projet_sysde\sysde-mobile`. Ne les modifie pas sans demande.

## État du code (paquets `ci.sysde`)
- `common` : `BusinessException(status, code, message)`, `GlobalExceptionHandler` (RFC 9457 + champs `code`, `message`, `horodatage`, `details`), `Page`/`PageRequete`/`TriSql` (pagination `page,size,sort`, tri par liste blanche).
- `security` : `SecurityConfig` (JWT HS256, Bearer, `PERM_xxx`), `JwtService`, `Acteur` (compte lu dans le jeton), `Perimetre` + `PerimetreService` (étendue territoriale relue en base), `ActeurTransaction` (aspect : pose `app.compte_id` en début de toute méthode `@Transactional` non lecture seule ; les triggers s'en servent pour `cree_par`, `modifie_par` et l'audit).
- `iam.auth` : `AuthService` (connexion par **matricule**), `SessionService` + `RefreshTokenService` (`POST /auth/refresh`, `/auth/logout`, rotation par famille), `ComptesQuery`, `JournalConnexion`. `iam.compte` : `Compte` (entité JPA, colonnes de connexion seulement) + `CompteRepository`.
- **Tout le reste du back est à écrire** : référentiels, comptes, contrats, planning, réalisations, API mobile, plantations.
- Le code livré le 14/11 (socle) **n'a jamais été compilé par l'auteur précédent** (pas d'accès à Maven Central). Première tâche : `mvn verify`, corriger ce qui ne compile pas, faire passer les tests (`PaginationTest`, `GlobalExceptionHandlerTest`, `PerimetreTest`, `RefreshTokenServiceTest`, `JwtServiceTest`, tests de `iam.auth`).

## Conventions décidées
- Accès aux données : **JdbcClient** (SQL explicite) pour lectures et écritures ; JPA seulement quand une entité simple s'y prête (`Compte`). Pas de jOOQ pour l'instant.
- UUID v7 (colonnes `id` avec défaut `util.uuid_v7()` ; le mobile peut en générer hors ligne = idempotence). Suppression logique (`supprime_le`), colonnes `cree_le/cree_par/modifie_le/modifie_par/version`.
- Erreurs : un seul format. Un message levé par un trigger de la base (SQLSTATE `23514`, `42501`, `P0002`, `23505`, `23001`, sans nom de contrainte) est rendu tel quel en français ; une contrainte technique reste générique.
- Étendue territoriale **toujours relue côté serveur** (jamais lue dans le jeton ni envoyée par le client). AD/SU : tout ; CD : son opérateur ; autres : leurs affectations en cours (`iam.affectation`). Un technicien de lot ou de section voit aussi son secteur en lecture large.
- Les règles de gestion sont **dans la base** (triggers, contraintes) : ne les duplique pas dans Java, rends-les lisibles (messages) et teste-les. `src/test/resources/db/regles_metier.sql` contient 252 contrôles SQL.
- Audit : `audit.evenement` alimenté par triggers (les tables auditées ont une colonne `id`) ; connexions via `audit.tracer_connexion`. Le rôle applicatif `sysde_app` n'a ni UPDATE sur l'audit ni DDL (voir `V6__droits.sql`).
- Tests : Testcontainers (image PostGIS) pour les tests d'intégration ; tests unitaires purs quand c'est possible.

## Écarts connus à corriger (ordre conseillé)
1. **Jeton d'accès** : 8 h aujourd'hui ; l'exigence EXI-05 dit 15 min avec renouvellement. À ramener à 15 min **quand** web et mobile appellent `/auth/refresh` (le front web ne gère encore que 401 → reconnexion). Durée réglable par `SYSDE_JWT_DUREE` (`PT15M`). Limite de débit par IP en place (429).
2. ~~**PRO-17**~~ **fait le 15/11/2026** (voir `decisions-back.md`, « PRO-17 et ORG-06 ») : les permissions ne doivent pas être figées dans le jeton ; elles sont relues côté serveur (cache < 1 min). Aujourd'hui elles sont dans le jeton (`permissions`). **ORG-06** : désactiver un opérateur doit faire rejeter les jetons déjà émis (cache < 1 min). Un filtre/convertisseur unique relisant état du compte, de l'opérateur et permissions règle les deux.
3. ~~Le changement de mot de passe ne révoque pas les autres sessions~~ **fait le 15/11/2026** (jetons de rafraîchissement ; les jetons d'accès émis courent jusqu'à échéance).
4. Rafraîchissement mobile : si la réponse d'un `/auth/refresh` est perdue, la famille est coupée à la réutilisation (pas de délai de grâce). À arbitrer pour le mobile hors ligne.
5. Le front web envoie `email` à `/auth/login`, le back attend `matricule` (décision ouverte, côté front).
6. Limitation du débit par adresse IP (EXI-05) : non faite.

## Plan d'implémentation (un module à la fois, testé)
1. Socle (fait, à compiler/tester).
2. **Référentiels** : opérateurs, secteurs, lots, sections, groupes de vulgarisation, contrats, attributions, exercices, activités, droits par profil (`/permissions`), import de contours (PostGIS). Routes : `endpoints-back.md` §2.
3. **Comptes** : création hiérarchique (le trigger `iam.avant_creation_compte` impose AD→AD/SU/CD, CD→CS, CS→CF/CO/FS/MO/ES/SE), matricule (`iam.reserver_numero`), activation, réinitialisation, désactivation, changement de profil, reprise (`iam.reprendre_compte`), import CSV, affectations, traçabilité (consultation/export).
4. **Planning** (schéma `planning`, V10) : plans annuels, lignes, soumission du plan et par activité, validation/rejet par le CS, saisie par la secrétaire, notifications créées côté serveur. Routes : `endpoints-back.md` §3–4. Fréquences = données (`planning.activite_profil`) ; où elles sont contrôlées reste à décider avec le client.
5. **Réalisations** (mobile) : réalisation, présences, évaluations (bon/moyen/mauvais × 5 critères), appréciations, rapports, photos (MinIO) — **photos en standby (décision du propriétaire, 15/11/2026)** : ne pas les entamer sans demande.
6. ~~**API mobile**~~ **faite le 15/11/2026** (`/mobile/amorcage`, `/mobile/synchronisation`, envoi seul, suivi des appareils ; voir `endpoints-back.md` §3ter ; restent les photos) : synchronisation (envoi idempotent par UUID v7, reprise sur coupure, conflits par numéro de révision/ETag + 409, photos en différé), téléchargement du planning et du référentiel, droit d'envoi seul des comptes désactivés (`iam.compte_peut_synchroniser`).
7. Reporting (3 niveaux), dashboard, carte (`/carte/*`, GeoJSON), notifications.

## Plantations (base externe) — décision du 14/11/2026
**Implémenté le 15/11/2026** (`ci.sysde.plantation`, voir `endpoints-back.md` §6 et §7) avec un contrat externe provisoire ; **le simulateur `sysde-plantations-sim` est fait** (dossier frère `D:\projet_sysde\sysde-plantations-sim`, voir son `README.md` ; 550 plantations dans les 55 lots, port 8090, base sur le port 5434 ; l'adaptateur de SysDE a été vérifié contre lui). Les tests de `sysde-back` restent sur une source en mémoire + un serveur HTTP local. Reste : contrat réel du tiers, cache et coupe-circuit, activer la vérification des `plantationRef` du planning (`sysde.plantations.verification-planning`, faite mais désactivée par défaut).
Les plantations et planteurs viennent d'une **base externe** via l'API d'un tiers (lecture seule). Pour les tests, on construit un **simulateur séparé** avec sa propre base.
- Le back ne dépend que d'une interface `SourcePlantations`. Un seul adaptateur HTTP (RestClient, délais d'attente, reprises, coupe-circuit, cache court) parle à l'API ; le reste du code n'en sait rien.
- `sysde-plantations-sim` : petit Spring Boot **à côté** de `sysde-back` (dossier frère), sa base PostgreSQL dans `docker/compose.yml` (autre port que 5433, p. ex. 5434), données de test cohérentes avec les 18 secteurs et 55 lots de V9, superficies aux seuils (24,9 ; 25 ; 49,9 ; 50 ; 120 ha).
- Contrat **provisoire** (le vrai n'est pas connu) : liste paginée, filtre `modifieDepuis`, authentification par clé d'API, contours en GeoJSON ; fichier OpenAPI écrit par nous + tests de contrat (WireMock) sur l'adaptateur.
- SysDE ne copie pas la base externe : `referentiel.affectation_plantation` ne garde que la référence (`plantation_ref`, sans clé étrangère) + lot + section + dates. Le chef secteur affecte une plantation à une section de **son** lot (400 sinon) ; l'import Excel d'affectations est « tout ou rien » et ne crée jamais de plantation.
- Seuils de visite par superficie (planning) : minimum inclus, maximum exclu (25 ha → CO, 50 ha → CS).

## À savoir sur la base (V1 à V10)
- Schémas : `util`, `referentiel` (operateur, secteur, lot, section, import_contour, affectation_plantation), `gestion` (contrat, attribution, attribution_lot, exercice, retrait_lot), `iam` (profil, permission, profil_permission, utilisateur, compte, compteur_numero, alias_matricule, compte_profil, jeton_compte, jeton_rafraichissement, affectation, reprise_compte, import_compte, import_compte_ligne, identite_externe), `audit` (evenement partitionné par mois), `planning` (voir `planning-v10.md`).
- V9 : 18 secteurs (codes `01` à `18`, Bondoukou = 18) et 55 lots, numérotés globalement de 1 à 55. V8 exige PostGIS (colonnes `geom`).
- Fonctions utiles : `iam.compte_peut_se_connecter`, `iam.compte_peut_synchroniser`, `iam.a_permission`, `iam.profil_a`, `iam.reserver_numero`, `iam.reprendre_compte`, `gestion.generer_exercices`, `util.sans_accent`.
- Quand tu ajoutes une table : nouvelle migration `V11+` (jamais modifier une migration déjà appliquée), `util.attacher_horodatage`, trigger d'audit si la table est métier, `GRANT` pour `sysde_app` (les `ALTER DEFAULT PRIVILEGES` de V6 couvrent SELECT/INSERT/UPDATE), puis mets à jour le document « SysDE — Modélisation de la base de données » (le propriétaire le tient dans Claude Docs : signale-lui le changement).

## Livraison
Travaille directement dans le dépôt ; un `mvn verify` vert est le critère de fin de chaque module. Résume en deux ou trois phrases ce qui a changé, ce qui reste ouvert, et ce que tu n'as pas pu vérifier.
