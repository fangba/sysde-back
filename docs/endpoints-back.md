# SysDE — endpoints que le back doit fournir au front

Document de suivi, mis à jour à chaque fois que le front appelle (ou prévoit d'appeler) une route du back. Il complète `decisions-back.md` (socle technique, modèle de données). Source : le code du front (`D:\projet_sysde\sysde-front`, classes `Http…Api`).

Dernière mise à jour : 15 novembre 2026 (opérateurs, secteurs, lots, contrats, attributions, exercices, sections, groupes, permissions, comptes, planning, notifications, réalisations (sans photos), contours implémentés ; avant : socle back du 14/11).

## Comment lire ce document
- **Mode actuel du front** : `useDemoData: true` (données locales). Chaque route ci-dessous est celle que le front appellera au passage à `useDemoData: false`.
- **État** : 🔴 à faire · 🟡 à faire, point à trancher · ⚪ prévu côté front mais pas encore appelé. Rien n'est encore implémenté côté back pour ces routes.
- **Format des données** : les interfaces TypeScript du front font foi (fichier indiqué dans chaque section). Les champs JSON sont en anglais et en camelCase ; les chemins sont en français.

## Conventions générales
| Sujet | Ce que le front attend |
| --- | --- |
| URL de base | `apiBaseUrl` : `http://localhost:8080/api` en développement, `/api` en production. Tous les chemins ci-dessous sont relatifs à cette base. |
| Authentification | En-tête `Authorization: Bearer <jeton>` sur tous les appels, sauf `POST /auth/login` et `POST /auth/refresh`. **Les droits ne sont pas lus dans le jeton** : profil et permissions sont relus en base à chaque requête (cache < 30 s, vidé à chaque changement de permissions ou d'opérateur) ; un changement s'applique dès la requête suivante (PRO-17), et un opérateur désactivé fait rejeter ses jetons en 401 (ORG-06). |
| Étendue des données | **Toujours appliquée par le serveur à partir du jeton** (organisme, opérateur, secteur, lot). Le front n'envoie jamais l'identité de l'appelant. |
| Erreurs | **Implémenté (14/11/2026) : RFC 9457**, `Content-Type: application/problem+json`. Corps : `type`, `title`, `status`, `detail` (standard) **plus** les champs historiques à plat : `code` (stable, en MAJUSCULES), `message` (en français, affichable), `horodatage`, et `details[{champ, message}]` pour les erreurs de validation. Codes HTTP : 400 requête invalide (`REQUETE_INVALIDE`, `REQUETE_ILLISIBLE`, `TRI_INVALIDE`), 401 session expirée ou compte/opérateur désactivé depuis l'émission du jeton (`SESSION_EXPIREE` : le client doit se reconnecter), `IDENTIFIANTS_INVALIDES`, 403 accès refusé (`ACCES_REFUSE`, `COMPTE_INACTIF`), 404 `INTROUVABLE`, 405, 409 conflit (`CONFLIT_DONNEES`, `CONFLIT_DE_VERSION`), 422 règle métier violée (`REGLE_METIER`, message du trigger de la base), 423 `COMPTE_VERROUILLE`, 5xx `ERREUR_INTERNE`. Le front peut afficher `message` tel quel. |
| Pagination | **Implémentée (14/11/2026)** pour les listes volumineuses : `?page=0&size=25&sort=champ,asc`. Réponse `{ content[], page, size, totalElements, totalPages }`. `size` plafonné à 100 ; un tri sur un champ non prévu renvoie 400 `TRI_INVALIDE`. Les petites listes de référentiel restent des tableaux simples (voir chaque ressource). |
| Dates | ISO `aaaa-mm-jj` (jour) ou date-heure ISO 8601. |
| Identifiants | Chaînes opaques (UUID v7 prévus). |
| Suppression | Aucune route `DELETE` n'est appelée pour l'instant (suppression logique prévue côté back). |

## 1. Authentification (`core/auth/auth.api.ts`)
| État | Méthode et chemin | Corps / paramètres | Réponse | À savoir |
| --- | --- | --- | --- | --- |
| 🟡 | `POST /auth/login` | `{ email, password }` (le back lit `matricule`, pas `email` : écart ouvert) | `{ token, expiresAt, mustChangePassword, user, refreshToken, refreshExpiresAt }` (les deux derniers champs sont nouveaux : le mobile et le web doivent les conserver) ; `user` = `SessionUser` (`core/auth/current-user.ts` : prénom, nom, e-mail, libellé du rôle, `administrator`, `poste`, `operator {acronym, name}`, `sector {code, name}`) | **Écart avec `decisions-back.md`** : le front identifie par **e-mail**, le back prévoit la connexion par **matricule**. À aligner (le front devra changer ou le back accepter les deux). Le front dit « poste », le back « profil/compte ». |
| 🟡 | `POST /auth/refresh` | `{ refreshToken }` | même réponse que `/auth/login` (nouveau `token` **et nouveau `refreshToken`**) | **Implémenté (14/11/2026), non encore appelé par le front.** Public (le jeton d'accès a pu expirer). Rotation par famille : chaque jeton ne sert qu'une fois ; présenter un jeton déjà utilisé coupe toute la famille (401 `SESSION_EXPIREE`, reconnexion). Profil et permissions sont relus en base. Compte devenu inactif : 401. |
| 🟡 | `POST /auth/logout` | `{ refreshToken }` | 204 | **Implémenté (14/11/2026).** Révoque le jeton et sa famille. Exige le jeton d'accès. |
| 🔴 | `POST /auth/mot-de-passe` | `{ currentPassword, newPassword }` | 204 sans corps | Refuser (400) si l'ancien mot de passe est faux ou si le nouveau ne respecte pas la règle de robustesse. |

## 2. Paramètres (`features/settings/data/settings.api.ts`)
Huit ressources identiques : **liste, création, modification** (pas de suppression appelée). Formats : `settings.models.ts` (`Xxx` pour la lecture, `XxxInput` pour l'écriture).

| État | Ressource | Routes |
| --- | --- | --- |
| 🟢 | Opérateurs | `GET /operateurs` · `POST /operateurs` · `PUT /operateurs/{id}` — **implémenté (15/11/2026)**. `Operator {id, acronym, name, type, address?, email?, phone?, active}`. Lecture : AD/SU tous, les autres leur opérateur seul. Écriture : permission `GERER_REFERENTIEL` (403 sinon). Acronyme figé dès qu'un compte existe (409), acronyme unique (409). `POST` → 201. |
| 🟢 | Contrats | `GET /contrats` · `POST /contrats` · `PUT /contrats/{id}` — **implémenté (15/11/2026)**. `Contract {id, reference, operatorId, mandantId, startDate, endDate}` ; `mandantId` facultatif à l'envoi (par défaut : l'organisme de l'appelant), doit être un ORGANISME ; `operatorId` doit être TECHNIQUE et ne change pas (400). Fin > début (400). Chevauchement avec un autre contrat du même opérateur : 409. Lecture : AD/SU tous, les autres ceux de leur opérateur. |
| 🟢 | Attributions | `GET /attributions` · `POST /attributions` · `PUT /attributions/{id}` — **implémenté (15/11/2026)**. `Attribution {id, contractId, sectorId, lots: number[]}` (numéros officiels des lots du secteur) ; `lots` non vide ; entrée facultative `reason` = motif si un lot est retiré (défaut : « Modification de l'attribution »). `contractId` et `sectorId` ne changent pas (400) ; lot absent du secteur : 400 ; lot déjà attribué à un opérateur sur la période : 409 ; retrait d'un lot encore affecté à des agents : 409 (message de la base). Les exercices sont générés à la création. |
| 🟢 | Exercices | `GET /exercices` · `PUT /exercices/{id}` — **implémenté (15/11/2026), sans `POST`** : la base génère un exercice par période de 12 mois de l'attribution (et les recalcule si les dates du contrat changent), un `POST` n'a donc pas lieu d'être : **le front doit retirer le bouton de création**. `Exercise {id, attributionId, contractId, sectorId, number, year, startDate, endDate, closingDate}` ; `year` = année de `startDate` (transitoire, pour le front actuel). `PUT {closingDate}` : seule la clôture se modifie ; antérieure au début de l'exercice : 422. |
| 🟢 | Utilisateurs → **Comptes** | voir §2bis (`/comptes`) | **Le back fait foi (même principe que `/permissions`)** : le front remplace `/utilisateurs` par `/comptes`. |
| 🟢 | Secteurs | `GET /secteurs` · `POST /secteurs` · `PUT /secteurs/{id}` — **implémenté (15/11/2026)**. Lecture bornée : AD/SU tous ; CD les secteurs où son opérateur a une attribution ; autres : secteurs de leurs affectations. Écriture : `GERER_REFERENTIEL`. Code unique (409). |
| 🟢 | Lots | `GET /lots` · `POST /lots` · `PUT /lots/{id}` — **implémenté (15/11/2026)**. Même périmètre de lecture que les secteurs (lots des secteurs visibles). Numéro unique par secteur (409). **Un lot ne change pas de secteur** (400 si `sectorId` diffère à la modification). |
| 🟢 | Sections (subdivisions d'un lot) | `GET /sections` · `POST /sections` · `PUT /sections/{id}` — **implémenté (15/11/2026)**. `Section {id, lotId, name, description}`. Nom saisi (50 car. max), **unique par lot** sans tenir compte de la casse, des accents ni des espaces (409) ; les espaces multiples sont réduits à l'enregistrement. Le **numéro est attribué par le serveur** (TER-04), non exposé. Lot non modifiable (400). Écriture : permission `GERER_SECTION` (chef secteur par défaut) **et** lot de son secteur confié à son opérateur (403 sinon). Lecture : selon le périmètre ; un compte ne voit que les sections des lots confiés à son opérateur ; AD/SU voient tout. Migration V11 (colonne `description`, index d'unicité du nom). |
| 🟢 | Groupes de vulgarisation | `GET /groupes` · `POST /groupes` · `PUT /groupes/{id}` — **implémenté (15/11/2026)**, règles du 26/10 reprises. **`monitorId` = identifiant du compte MO** (pas de l'utilisateur) : compte actif, de l'opérateur titulaire du lot, affecté à ce secteur (400 sinon). Nom unique par section (409) ; `sectionId` doit appartenir à `lotId` (400) ; lot et section figés à la modification (400) ; `village` et `delegate` renvoyés en chaîne vide s'ils sont absents. Même permission et même périmètre que les sections (`GERER_SECTION`). |
| 🔴 | Activités (référentiel du planning) | `GET /activites` · `POST /activites` · `PUT /activites/{id}` |
| 🟢 | Droits par profil | `GET /permissions` · `PUT /permissions` · `GET /permissions/catalogue` — **implémenté (15/11/2026), le back fait foi (décision du propriétaire)**. Vocabulaire de la base : **22 codes de permissions** (`GERER_COMPTE`, `VALIDER_PLANNING`, `CONSULTER_DASHBOARD`…) et **10 profils** (`AD`, `SU`, `CD`, `CS`, `CF`, `CO`, `FS`, `MO`, `ES`, `SE` ; plus de `DI`). `GET /permissions` → `{ "AD": [codes], "CS": [codes], … }` ; `PUT` enregistre d'un coup les profils présents dans le bloc (absents = inchangés) et renvoie le bloc complet ; tout est contrôlé avant écriture (profil ou code inconnu : 400, rien n'est enregistré). `GET /permissions/catalogue` → `{ profiles: [{code, label, rank, order}], permissions: [{code, domain, label}] }`. Réservé à `GERER_PERMISSIONS` (403 sinon). Garde-fou de la base : l'AD ne peut pas perdre `GERER_PERMISSIONS` ni `GERER_REFERENTIEL` (409). **Le front doit remplacer son catalogue local (7 codes `dashboard.voir`…) et `DI` par AD.** Limite : les permissions sont encore dans le jeton (PRO-17), un changement ne s'applique qu'au prochain jeton. |

## 2bis. Comptes (`/comptes`), imports et traçabilité — implémenté le 15/11/2026
Un compte = la personne (nom, prénoms, e-mail, téléphone) + son profil, son opérateur et son matricule. Tous les identifiants sont ceux du **compte** (pas de l'utilisateur). Le modèle de l'écran « utilisateurs » du front (poste, secteur, lot…) est à refondre : le secteur, le lot et les sections viendront des **affectations** (tranche 2).

`Account {id, userId, matricule, reference, number, profile, profileLabel, operatorId, operatorAcronym, lastName, firstName, email, phone, status (EN_ATTENTE | ACTIF | DESACTIVE), mustChangePassword, lastLoginAt, createdAt}`.

| État | Méthode et chemin | Permission | À savoir |
| --- | --- | --- | --- |
| 🟢 | `GET /comptes?q&profile&status&operatorId&page&size&sort` | `GERER_COMPTE` | Page `{content, page, size, totalElements, totalPages}`. `q` : nom, prénoms ou matricule (sans casse, `%` et `_` traités comme du texte). Tri : `matricule`, `lastName`, `profile`, `status`, `createdAt` (400 sinon). **Périmètre** : l'AD voit son organisme et tous les opérateurs techniques ; les autres, les comptes de **rang inférieur** de leur opérateur ; un chef secteur voit en plus seulement les comptes affectés à ses secteurs ou **sans affectation** (à placer). Un compte hors périmètre est un 404. |
| 🟢 | `GET /comptes/{id}` | `GERER_COMPTE` | Même périmètre. |
| 🟢 | `POST /comptes` | `CREER_COMPTE` | `{lastName, firstName, email?, phone?, operatorId, profile, number?}` → 201 `{account, temporaryPassword}`. **Le mot de passe temporaire (12 car.) n'est montré qu'une fois**, à remettre à la personne ; le compte est ACTIF avec `mustChangePassword: true`. `number` : numéro de matricule historique (5 à 7 chiffres) ; absent, le système en attribue un. La base impose la hiérarchie (AD→AD/SU/CD, CD→CS, CS→CF/CO/FS/MO/ES/SE) : sinon 403 avec le message de la base. E-mail déjà pris par une autre personne : 409. **Pas de lien d'activation par e-mail pour l'instant** (aucun envoi d'e-mails en place) : seul le mode « mot de passe temporaire » de COM-04 existe. |
| 🟢 | `PUT /comptes/{id}` | `GERER_COMPTE` | `{lastName, firstName, email?, phone?}` : données de la personne seulement. 403 si le compte n'est pas dans l'autorité de l'appelant (rang strictement inférieur, même organisation). |
| 🟢 | `POST /comptes/{id}/desactivation` · `/reactivation` | `GERER_COMPTE` | Corps facultatif `{reason}` (tracé). Désactiver coupe les sessions (jetons de rafraîchissement révoqués, jetons d'accès rejetés aussitôt). Sur soi-même : 400. Déjà dans l'état : 409. |
| 🟢 | `POST /comptes/{id}/reinitialisation` | `GERER_COMPTE` | → `{account, temporaryPassword}`. Nouveau mot de passe temporaire à changer, échecs et verrou effacés, **toutes les sessions coupées** (COM-07). Compte actif seulement (409 sinon). Limite : un jeton émis dans la seconde même de la réinitialisation n'est pas coupé. |
| 🟢 | `PUT /comptes/{id}/profil` | `CHANGER_PROFIL` | `{profile, reason}` (motif obligatoire, 400 sinon). Historique conservé, affectations closes (règles de la base) ; profil d'organisme ↔ profil d'opérateur refusé. Le matricule ne change pas, la `reference` si. Effet immédiat sur les droits (relus côté serveur). |
| 🟢 | `PUT /comptes/{id}/matricule` | `GERER_COMPTE` + **AD seulement** (403 sinon) | `{number, reason}` (motif obligatoire). L'ancien matricule est conservé en alias et refusé à la connexion. |
| 🟢 | `GET /comptes/{id}/affectations` | `GERER_COMPTE` | `Assignment {id, accountId, sectorId, sectorCode, sectorName, lotId?, lotNumber?, sectionId?, sectionName?, startDate, endDate?, state}`. `state` : ACTIVE, SUSPENDUE (entre deux contrats), A_REVOIR (territoire plus couvert), CLOSE. Historique compris. **Les champs `null` sont absents du JSON** (ex. `endDate` d'une affectation en cours). |
| 🟢 | `POST /comptes/{id}/affectations` | `AFFECTER_SECTEUR` (cible CS) ou `AFFECTER_TECHNICIEN` (autres) | `{sectorId, lotId?, sectionId?}` → 201. Niveau selon le profil de la cible (CS, ES, SE : secteur ; CF, FS : lot ; MO, CO : section) ; un seul secteur à la fois ; un seul CS par secteur et opérateur ; un seul MO par section ; territoire couvert par un contrat de l'opérateur de la cible : sinon 422/409 avec le message de la base. Un chef secteur n'affecte que dans **son** secteur (403). Sans aucun droit d'affecter : 403 ; cible hors périmètre : 404. |
| 🟢 | `POST /comptes/{id}/affectations/{affectationId}/cloture` | idem | Clôt (date de fin = aujourd'hui) sans rien effacer (AFF-05). Déjà close : 409. |
| 🟢 | `POST /comptes/{id}/reprise` | `REPRENDRE_COMPTE` | `{successorId, reason}` (motif obligatoire) → `{transferredAssignments}`. Le compte repris doit être **désactivé**, le successeur actif, même organisation (422 sinon). Les affectations en cours passent au successeur. **Les activités ne sont pas encore transférées** (schéma `planning`). |
| 🟢 | `GET /imports-comptes/modele` · `POST /imports-comptes` · `GET /imports-comptes/{id}` · `POST /imports-comptes/{id}/application` · `POST /imports-comptes/{id}/annulation` · `GET /imports-comptes/{id}/rejets` | `IMPORTER_COMPTES` (chef secteur) | **Import CSV (COM-09)**. Colonnes : `nom;prenoms;email;telephone;profil;numero;secteur;lot;section` (séparateur `;` `,` ou tabulation, UTF-8, 5 000 lignes et 2 Mo au plus). `POST /imports-comptes` (multipart, champ `file`) contrôle **tout le fichier sans rien créer** → 201 `{id, fileName, status (PRET | ANNULE), lines, valid, rejected, errors[{line, message}]}` (200 premières erreurs ; `line` = numéro de ligne du fichier, l'en-tête étant la ligne 1). `…/application` crée les lignes valides, **une transaction par ligne** (la base peut encore refuser une ligne) → `{created, rejected, accounts[{line, accountId, matricule, reference, lastName, firstName, temporaryPassword}], errors}` ; **les mots de passe temporaires ne sont montrés qu'ici, une fois**. `…/rejets` : CSV des lignes rejetées avec une colonne `erreur`, à corriger et réimporter. Un import n'est visible que de son créateur (404 sinon) ; le même fichier ne s'applique pas deux fois (409). Le chef secteur ne crée que dans son opérateur, les profils de sa hiérarchie et le territoire de **son** secteur. |
| 🟢 | `GET /tracabilite?accountId&table&objectId&from&to&page&size` | `CONSULTER_TRACABILITE` | Page d'`AuditEvent {id, at, accountId, matricule, userId, profile, ip, reason, schema, table, operation (I | U | S | D), objectId, before, after}` (`before` / `after` : seulement les colonnes modifiées ; jamais de mot de passe ni de contour). **Périmètre = événements dont l'auteur est un compte visible de l'appelant** (comptes de rang inférieur de son périmètre, et lui-même) ; AD et SU voient aussi les traitements système. Sans `from` : 30 derniers jours ; `from` / `to` : `aaaa-mm-jj` ou date-heure ISO 8601 (`to` exclu). Jamais au-delà de la conservation (`audit.conservation_mois`, 36). |
| 🟢 | `GET /tracabilite/export?…` | `EXPORTER_TRACABILITE` (AD) | Mêmes filtres → CSV (50 000 lignes au plus, 400 sinon). **L'export est lui-même tracé** (table `tracabilite_export`, filtres et nombre de lignes). Cellules commençant par `=`, `+`, `-`, `@` neutralisées (injection de formule). |

Points à trancher :
- Les listes sont renvoyées **en entier, sans pagination** (le front filtre et pagine lui-même). Incompatible avec 1 million de comptes : prévoir pagination et recherche côté serveur pour `/utilisateurs` avant la mise en production.
- `/utilisateurs` : le front manipule des « utilisateurs » avec un poste, un opérateur et un secteur ; le back distingue utilisateur, compte et profil. Le modèle de l'écran devra être réaligné (déjà noté dans `decisions-back.md`, « Front à renommer »).
- `/exercices` : le front utilise encore l'année ; le back prévoit numéro et période.
- `/activites` : définit, par poste, la fréquence et la durée utilisées par le planning (règles de contrôle). Format et règles à valider avec le client.

## 2ter. Contours des secteurs et des lots (PostGIS) — implémenté le 15/11/2026
On choisit la **cible** (un secteur ou un lot), on dépose son fichier, on lit le rapport de contrôle, puis on confirme. Réservé à `GERER_REFERENTIEL` (403 sinon) ; un import n'est visible que de son créateur (404 sinon). Les contours des **sections** sont réservés (400).

| État | Méthode et chemin | À savoir |
| --- | --- | --- |
| 🟢 | `POST /imports-contours` (multipart : `file`, `level` = `SECTEUR` \| `LOT`, `targetId`) | **Contrôle sans rien modifier** → 201 `ContourImport {id, level, targetId, targetLabel, format (GEOJSON \| SHAPEFILE), fileName, status (PRET \| REJETE), polygons, areaHa?, bbox? [minLon, minLat, maxLon, maxLat], errors[], warnings[], createdAt}`. **Formats** : GeoJSON (`Polygon`, `MultiPolygon`, `Feature`, `FeatureCollection`) ; **shapefile zippé** (`.shp` polygones, `.dbf`, `.prj`). 25 Mo et 500 000 points au plus. **Seul WGS 84 (EPSG:4326)** : `crs` GeoJSON autre, ou `.prj` projeté (`PROJCS`) ou non WGS 84 → erreur ; `.prj` absent → simple avertissement. Fichier illisible, géométrie non polygonale, vide, cible ou niveau inconnu : **400** (rien n'est enregistré). |
| | **Erreurs bloquantes** (`REJETE`, non applicable) | Géométrie **invalide** (auto-intersection… avec la raison de PostGIS) ; coordonnées **hors de la Côte d'Ivoire** (inversion latitude/longitude, mètres) ; **identification incohérente** : si le fichier porte un attribut `code` / `code_secteur` / `secteur` (secteur) ou `numero` / `num_lot` / `lot` (lot), il doit désigner la cible choisie (« 05 » = « 5 »). Plusieurs polygones sont réunis en un seul MultiPolygon. |
| | **Avertissements** (ne bloquent pas) | **Recoupement** avec un autre secteur ou lot (> 0,1 % de la superficie, 5 plus gros listés, avec le pourcentage) ; lot qui **déborde** de son secteur, ou lots déjà contourés qui débordent du nouveau secteur (> 1 %) ; **remplacement** d'un contour existant ; polygones réunis ; `.prj` absent. |
| 🟢 | `GET /imports-contours/{id}` · `POST /imports-contours/{id}/application` · `POST /imports-contours/{id}/annulation` | **L'application relit le fichier conservé et le recontrôle** (l'état des voisins a pu changer) avant de remplacer `geom` (409 si une erreur apparaît ou si l'import n'est pas `PRET`). Le **fichier d'origine est conservé** dans le stockage (historique des contours, `referentiel.import_contour`) ; la piste d'audit signale qu'un contour a changé sans en copier la géométrie. |
| 🟢 | `GET /secteurs/{id}/contour` · `GET /lots/{id}/contour` | `VISUALISER_CARTE`. GeoJSON `Feature` (`application/geo+json`, `MultiPolygon`, 6 décimales). 404 hors périmètre ou sans contour. |
| 🔴 | `GET /carte/catalogue` · `GET /carte/plantations` (§7) | Pas encore : les plantations viennent d'une base externe dont le contrat est inconnu. |

**Stockage** : `StockageObjets` (interface) et `StockageLocal` (disque, `sysde.stockage.repertoire`, `./data/fichiers` par défaut). MinIO viendra la remplacer sans toucher aux appelants (servira aux photos).

## 3. Planning annuel (`features/planning/data/planning.api.ts`, modèles dans `planning.models.ts`)
Objet central `AnnualPlan` : `id, userId, year, status (BROUILLON | SOUMIS | VALIDE), lines[], rejection? {reason, date}, submissions? [{activityId, status SOUMIS|VALIDE|REJETE, rejection?}], editedBy? (SELF | SECRETARY), editedAt?`.
`PlanLine` : `id, date, endDate (inclus), activityId, place (lot/section), plantation? (visites)`. **Plus de champ `hours` (retiré le 27/10/2026)** : le front ne l'envoie plus ; le back ne doit ni le stocker ni l'exiger (ignorer s'il arrive d'un ancien client).
**Une visite de plantation se fait en 1 jour (règle du 28/10/2026)** : pour une activité de catégorie Visite, `endDate` est égal à `date` (ou absent) ; le back refuse (400) une ligne de visite dont `endDate > date`. Formation et rencontre peuvent s'étendre sur plusieurs jours.
Le paramètre `annee` est l'année du planning (ex. 2026).

### Implémenté le 15/11/2026 — le back fait foi : écarts avec `planning.models.ts`
Le front est à aligner sur ces formats (décisions du propriétaire : le back fait foi). Les champs `null` sont **absents** du JSON.
- **Identifiants de compte** : `AnnualPlan.account {id, matricule, profile, lastName, firstName}` remplace `userId` ; `{compteId}` dans les chemins (au lieu de `{userId}`).
- **Lieu structuré** : `place` (texte libre « Lot 3 ») est remplacé par `lotId`, `sectionId` (+ `lotNumber`, `sectionName` en lecture) ; `plantation` par `plantationRef` (référence dans la base externe) ; ajout de `groupId` (groupe de vulgarisation). Le lot doit être dans le secteur du compte et confié à son opérateur ; la section, du lot (400 sinon).
- **Activités** : `activityId` est l'identifiant de `planning.activite` (une activité partagée entre profils, la base vérifie qu'elle est réalisée par le profil du titulaire : 422 sinon). `GET /plannings/activites` renvoie les activités planifiables du profil de l'appelant avec leurs **fréquences** (nombre, période, « par », tranche de superficie, règle). `/activites` (CRUD) reste à faire.
- **Rejet** : le corps est `{reason}` (et non `motif`). `rejection {reason, date}`.
- **Plan jamais enregistré** (`GET /plannings/equipe`) : `id` absent, `status: BROUILLON`, aucune ligne. Il est créé à la première saisie.
- **Une ligne de visite exige sa `plantationRef`** (400 sinon) ; une activité non planifiée (découverte du verger) est refusée (422).
- Les erreurs de saisie de lignes indiquent le numéro de ligne (« Ligne 3 : … »), à partir de 1.
- **Cycle** : plan BROUILLON → SOUMIS → VALIDE ; un rejet le remet en BROUILLON et rejette en même temps ses activités encore soumises (avec le même motif) ; une validation valide aussi les activités soumises une à une. Un plan SOUMIS ou VALIDE n'est plus modifiable (409) ; les lignes d'une activité SOUMIS ou VALIDE doivent être renvoyées **à l'identique** (409 sinon). Soumettre un plan vide : 400. Chef secteur : VALIDE d'office.
- **Non contrôlé** : les fréquences (séances par période, par plantation…). Les règles sont des données (`activite_profil`) ; qui les vérifie reste à décider avec le client.

### 3.1 Mon planning (tous les postes de terrain : CS, CF, CO, FS, MO, ES)
| État | Méthode et chemin | Corps / paramètres | Réponse | Règles à appliquer côté serveur |
| --- | --- | --- | --- | --- |
| 🟢 | `GET /plannings/mon-planning?annee=` | | `AnnualPlan` | Crée un planning vide en BROUILLON s'il n'existe pas encore. |
| 🟢 | `PUT /plannings/mon-planning/lignes?annee=` | tableau de `PlanLineInput` (`PlanLine` sans `id`) | `AnnualPlan` | Remplace **toutes** les lignes. Refusé (409) si le planning est SOUMIS ou VALIDE. Les lignes d'une activité soumise ou validée (voir 3.3) ne peuvent pas être modifiées. Dates dans l'année du planning, `endDate ≥ date`, **et `endDate = date` pour une visite de plantation (1 jour)**. Les activités doivent être actives et propres au poste. |
| 🟢 | `POST /plannings/mon-planning/soumission?annee=` | `{}` | `AnnualPlan` | BROUILLON → SOUMIS pour les postes soumis au chef secteur. **Pour le chef secteur : VALIDE d'office** (personne au-dessus). Absorbe les activités soumises non encore validées. Refusé (409) si déjà soumis ou validé. |
| 🟢 | `POST /plannings/mon-planning/activites/{activiteId}/soumission?annee=` | `{}` | `AnnualPlan` | Soumet **une seule activité** au chef secteur (CF, CO, FS, MO, ES ; pas le CS). Exige au moins une date. Verrouille les dates de l'activité jusqu'à la décision. Réservée au titulaire. |

### 3.2 Validation par le chef secteur
| État | Méthode et chemin | Corps / paramètres | Réponse | Règles |
| --- | --- | --- | --- | --- |
| ⚪ | ~~`GET /plannings/a-valider?annee=`~~ **abandonné** : utiliser `GET /plannings/equipe` (3.3), qui renvoie déjà tous les collaborateurs et leurs plannings ; le tri soumis / à examiner se fait côté front. Ancienne note : `GET /plannings/a-valider?annee=` | | `AnnualPlan[]` | Réservé au CS. **Le front attend les plannings de tous les collaborateurs actifs de son secteur, quel que soit leur état** (brouillon compris), hors CS et SE, avec les activités soumises (`submissions`). Le nom « a-valider » est trompeur : le tri soumis / à examiner se fait côté front. À renommer ou à filtrer ? |
| 🟢 | `POST /plannings/{planId}/validation` | `{}` | `AnnualPlan` | CS du même secteur uniquement ; planning SOUMIS requis, sinon 409. → VALIDE. Crée une **notification** au collaborateur. |
| 🟢 | `POST /plannings/{planId}/rejet` | `{ motif }` (obligatoire) | `AnnualPlan` | Planning SOUMIS requis. Le planning **repasse en BROUILLON** avec `rejection = { reason, date }`. Notification au collaborateur. |
| 🟢 | `POST /plannings/{planId}/activites/{activiteId}/validation` | `{}` | `AnnualPlan` | Activité à l'état SOUMIS requise ; passe à VALIDE (reste verrouillée). Notification. |
| 🟢 | `POST /plannings/{planId}/activites/{activiteId}/rejet` | `{ motif }` (obligatoire) | `AnnualPlan` | Activité à l'état SOUMIS ; passe à REJETE avec motif, dates de nouveau modifiables. Notification. |

### 3.3 Équipe du secteur (chef secteur et secrétaire)
| État | Méthode et chemin | Corps / paramètres | Réponse | Règles |
| --- | --- | --- | --- | --- |
| 🟢 | `GET /plannings/equipe?annee=` | | `AnnualPlan[]` | CS ou SE uniquement (403 sinon). Un plan par collaborateur **actif** du même opérateur et secteur, **y compris ceux qui n'ont pas encore de planning** (plan vide en BROUILLON, sans ligne). Exclut les SE. Le chef secteur est inclus **pour la secrétaire seulement** (le CS ne voit pas les CS). Sert au dashboard et à l'onglet Équipe de la secrétaire, et à la liste des membres du chef secteur. |

### 3.4 Saisie à la place d'un collaborateur (secrétaire) — ajouté le 25/10/2026
Mêmes formats et mêmes règles que 3.1, avec `{compteId}` = collaborateur visé.
| État | Méthode et chemin | Corps / paramètres | Réponse | Règles spécifiques |
| --- | --- | --- | --- | --- |
| 🟢 | `GET /plannings/collaborateurs/{compteId}?annee=` | | `AnnualPlan` | |
| 🟢 | `PUT /plannings/collaborateurs/{compteId}/lignes?annee=` | tableau de `PlanLineInput` | `AnnualPlan` | Renseigne `editedBy = SECRETARY` et `editedAt`. Sert aussi à l'**import Excel d'équipe** (le front appelle cette route une fois par personne ; pas d'endpoint d'import groupé). |
| 🟢 | `POST /plannings/collaborateurs/{compteId}/soumission?annee=` | `{}` | `AnnualPlan` | Soumet « au nom de » la personne. Cible = CS : VALIDE d'office. **Notification** à la personne concernée. |

Règle d'accès commune : l'appelante doit être SE, du **même opérateur et du même secteur**, et la cible un utilisateur **actif** d'un poste à planning (CS, CF, CO, FS, MO, ES ; pas SE ni administrateur) ; sinon **403**. La soumission **par activité** reste réservée au titulaire (aucune route « collaborateurs » équivalente).

### 3.5 Règles métier que le front applique seul (à décider : le back les revérifie-t-il ?)
Le contrôle de cohérence d'une activité (`checkFrequency` dans `planning.rules.ts` : nombre de séances par période, par plantation/saigneur/groupe, durée = durée de l'activité, pas de chevauchement) n'existe **que dans le front**. Le résultat (Actif / Inactif / À vérifier) n'est pas enregistré. Le back doit au minimum refuser la soumission d'une activité sans date ; la reprise complète des règles côté serveur est à décider avec le client.

## 3bis. Réalisations et participants — implémenté le 15/11/2026 (hors photos)
Saisie d'une activité sur le terrain (souvent **hors ligne**, mobile). Écriture : `REALISER_ACTIVITE` ; lecture : `CONSULTER_ACTIVITE` (**l'AD ne l'a pas par défaut**, matrice du cahier des charges ; ajustable par `/permissions`). **Les photos ne sont pas encore prises en charge** (stockage objet à venir).

| État | Méthode et chemin | À savoir |
| --- | --- | --- |
| 🟢 | `PUT /realisations/{id}` | **Envoi d'un état complet, à identifiant choisi par l'appareil (UUID v7), rejouable.** Corps `RealisationInput {lineId, status (EN_COURS \| TERMINEE), startedAt, endedAt?, latitude?, longitude?, accuracyM?, observations?, attendance[{participantId, day, present}], evaluations[{participantId, criterion, level (BON \| MOYEN \| MAUVAIS)}], appreciations[{participantId, verdict (CONFORME \| NON_CONFORME), comment?}], report?, expectedVersion?}` → toujours 200 `Realisation` (mêmes champs + `id`, `planId`, `accountId`, `activityId`, `receivedAt`, `version`). **Rejeu** : même identifiant et même contenu (empreinte, V15) → aucun effet, même avec une `expectedVersion` périmée (réponse perdue). **Conflit** : contenu différent et `expectedVersion` ≠ version courante → 409 `CONFLIT_DE_VERSION`. Les listes (présences, évaluations, appréciations) **remplacent** les précédentes. Règles (400 sauf mention) : la ligne est dans **votre** planning (403 sinon) et n'a pas déjà une autre réalisation (409) ; terminée ⇒ `endedAt` ; fin ≥ début ; latitude et longitude ensemble ; présences dans la période de la ligne, sans doublon ; participants de **votre opérateur** ; critères connus ; présences, évaluations, rapport seulement si l'activité en a (`avec_presence`, `avec_evaluation`, `avec_rapport`) ; plafond de participants de l'activité (20 pour la formation initiale). Une terminée ne repasse pas en cours (409). Un rapport retiré est vidé. `startedAt` = date réelle de l'action (saisie hors ligne), `receivedAt` = réception par le serveur. |
| 🟢 | `GET /realisations/{id}` · `GET /realisations?accountId&activityId&status&from&to&page&size` | Détail complet / résumés paginés (par début d'activité, récent d'abord). **Périmètre** : chacun les siennes ; chef secteur et secrétaire, celles de leur secteur ; chef de département, de son opérateur ; superviseur, tout opérateur technique. Hors périmètre : 404. |
| 🟢 | `GET /plannings/criteres-evaluation` | Les 5 critères d'évaluation `{code, label, order}`. |
| 🟢 | `GET /participants?q&page&size&sort` · `POST /participants` · `PUT /participants/{id}` | `Participant {id, lastName, firstName, phone?, village?, externalRef?}`, par opérateur (cloisonné). `POST` accepte un `id` choisi par l'appareil : **un renvoi ne crée pas de doublon** (201, ligne existante) ; l'identifiant d'un autre opérateur est refusé (409). Tri : `lastName`, `firstName`, `village`. |
| 🔴 | Photos, membres d'un groupe, activités spontanées | Voir `decisions-back.md`. L'envoi seul d'un compte désactivé est fait (voir §3ter). |

## 3ter. API mobile (implémentée le 15/11/2026)

Pas de permission propre : le contenu suit le profil et le périmètre du compte, relus en base.

| État | Méthode et chemin | Réponse |
| --- | --- | --- |
| 🟢 | `GET /mobile/amorcage?annee=` (année courante par défaut) | **Paquet de départ** `Bundle {generatedAt, year, account {accountId, matricule, profile, operatorId}, plan? (AnnualPlan, absent pour un profil sans planning), activities[] (PlanActivity avec fréquences), criteria[], sectors[], lots[], sections[], groups[], realisations[{id, lineId, status, version}], unreadNotifications}`. Les `realisations` donnent l'état serveur des envois de l'année : de quoi décider quoi renvoyer et avec quelle `expectedVersion` (détail par `GET /realisations/{id}`). **Les participants n'y sont pas** (liste longue) : paginer `GET /participants`. Appeler le serveur crée un plan vide en brouillon s'il n'existe pas (comme `mon-planning`). |
| 🟢 | `POST /mobile/synchronisation` | Corps `{deviceId (UUID tiré par l'appareil), model?, system?, appVersion?, pending (≥ 0)}` → 204. **Signal de l'appareil** après chaque synchronisation : un appareil par (compte, `deviceId`), état et heure écrasés. Alimente `GET /reporting/secteur/synchronisation`. |

**Envoi seul (compte désactivé).** Un compte désactivé depuis moins de 30 jours (`compte.delai_synchronisation_jours`) garde son jeton de rafraîchissement : le serveur le reconnaît mais le réduit à **l'envoi** : `PUT /realisations/{id}`, `POST /participants`, `PUT /participants/{id}` (et `POST /mobile/synchronisation`) ; tout le reste répond **403**. `POST /auth/refresh` fonctionne tant que dure le délai ; au-delà : **401** `SESSION_EXPIREE` partout. Réactiver le compte rétablit tout. Le mobile doit donc, sur un 403 pour un compte désactivé, ne plus tenter que l'envoi.

## 4. Notifications (`core/notifications/notification.service.ts`)
Aujourd'hui, en démo, les notifications sont gardées dans le navigateur. Avec le back réel :
| État | Méthode et chemin | Corps / paramètres | Réponse | À savoir |
| --- | --- | --- | --- | --- |
| 🟢 | `GET /notifications` | | `AppNotification[]` : `id, title, message, tone (info/success/warning/error…), createdAt, read` | Destinataire déduit du jeton. Le champ `to` (e-mail) du front disparaît. Le front n'a pas de pagination : prévoir au moins une limite (les 50 dernières). |
| 🟢 | `POST /notifications/{id}/lue` | `{}` | 204 | **Nouvelle, non encore appelée par le front** (aujourd'hui `markRead` local). |
| 🟢 | `POST /notifications/lues` | `{}` | 204 | Idem, pour « tout marquer comme lu » (`markAllRead`). |

**Implémenté le 15/11/2026** (migration V14, table `iam.notification`) : `AppNotification {id, title, message, tone (info | success | warning | error), createdAt, read}`, les 50 dernières, la plus récente d'abord ; un compte ne lit et ne marque que les siennes (404 sinon). Les notifications sont **créées par le serveur**, comme effet des actions suivantes (le front ne les enverra pas) :
- validation ou rejet d'un planning → au collaborateur ;
- validation ou rejet d'une activité → au collaborateur ;
- soumission d'un planning par la secrétaire à la place d'un collaborateur → au collaborateur.
Le front affiche un toast « Vous avez N nouvelles notifications » à la connexion ; un rafraîchissement périodique reste à définir.

## 5. Dashboard (`features/dashboard/data/dashboard.api.ts`)
| État | Méthode et chemin | Corps / paramètres | Réponse | À savoir |
| --- | --- | --- | --- | --- |
| 🟡 | `GET /dashboard` | query : `profile`, `operator`, `sector?`, `filterOperator?`, `filterSector?` | `DashboardData` (`dashboard.models.ts` : production mensuelle, planteurs, hectares, taux de visite, visites du mois, échéances, secteurs) | Réservé aux profils AD, CD, CS, SU. **Les paramètres d'identité (`profile`, `operator`, `sector`) sont aujourd'hui envoyés par le front : le back doit les ignorer et déduire l'étendue du jeton**, seuls `filterOperator` et `filterSector` sont de vrais filtres. Les chiffres de production et de visites réalisées viendront du mobile (reportés côté back). |
| ⚪ | — | | | Les dashboards de terrain (CF, CO, FS, MO, ES) et de la secrétaire **n'appellent pas `/dashboard`** : ils se calculent à partir de `/plannings/mon-planning` et `/plannings/equipe`. |

## 6. Plantations (`features/plantations/data/plantations.api.ts`)
| État | Méthode et chemin | Réponse | À savoir |
| --- | --- | --- | --- |
| 🟡 | `GET /plantations` | `PlantationDto[]` (`plantations.api.ts`) | Lecture seule, base externe. Étendue appliquée par le serveur : administrateurs et SU tout, CD son opérateur, les autres leur opérateur **et** leur secteur. Sans pagination aujourd'hui : à prévoir. Le champ « plantation » des visites du planning est un texte libre, **pas encore lié** à cette liste. **Chaque plantation doit aussi porter l'affectation faite dans SysDE** : `section_id` et `section_nom` (nuls si non affectée), à fusionner par le back avec les données de la base externe. |
| 🟡 | `PUT /plantations/{id}/section` | corps `{ sectionId: string \| null }` → `PlantationDto` | Affecte (ou retire, avec `null`) la plantation d'une section. **Chef secteur uniquement**, plantation de son secteur. Règle : la section doit appartenir au **lot de la plantation** (même numéro de lot, même secteur) ; sinon 400. 403 pour les autres postes, 404 si plantation hors périmètre. |
| 🟡 | `PUT /plantations/affectations` | corps `[{ plantationId, sectionId \| null }]` → `PlantationDto[]` (celles qui ont changé) | Même chose en lot (import Excel : le front lit le fichier, contrôle chaque ligne et n'envoie que les lignes valides). **Tout ou rien** : une seule ligne invalide (400/404) annule tout le lot. Fichier Excel lu côté front : colonnes « Plantation » (code) et « Section » (nom). |

## 7. Carte (`features/map/data/map.api.ts`)
| État | Méthode et chemin | Paramètres | Réponse | À savoir |
| --- | --- | --- | --- | --- |
| 🔴 | `GET /carte/catalogue` | | `MapOperatorOption[]` (opérateurs, secteurs, lots, sections disponibles pour l'appelant) | Étendue selon le profil : AD et SU tous les opérateurs ; CD son opérateur (tous ses secteurs) ; **CS et SE leur opérateur et leur seul secteur** (le front verrouille le filtre sur ce secteur, mais le serveur doit aussi le garantir). |
| 🔴 | `GET /carte/plantations` | `operatorId` (obligatoire), `sectorId?`, `lot?` (nombre), `section?` | `MapPlantation[]` : plantations avec polygone | Les contours viendront de PostGIS (WGS 84, MultiPolygon). Format de géométrie (GeoJSON ?) à fixer. |

## 7bis. Reporting (trois niveaux) — implémenté le 15/11/2026 (le back fait foi)
Permission `CONSULTER_REPORTING` (AD, SU, CD, CS) ; **chaque niveau est réservé à son profil** (403 sinon). `du` et `au` : mois `aaaa-mm`, `au` inclus ; sans eux, le mois en cours ; au plus 24 mois (400 sinon).

**Ce qui est compté** : les activités **validées** (lignes d'un plan validé, ou d'une activité validée une à une), dans le mois de leur **premier jour**. Un brouillon ou un plan soumis n'engage personne. Collaborateurs = CF, CO, FS, MO, ES **actifs** (ni le chef secteur, ni la secrétaire). *Réalisée* = réalisation **terminée** de la ligne. *En retard* = non réalisée et prévue avant aujourd'hui. *Taux* = réalisées / prévues ; **À jour ≥ 90 %, Léger retard ≥ 75 %, sinon En retard** (`A_JOUR`, `LEGER_RETARD`, `EN_RETARD` ; `SANS_PREVU` si rien de prévu). Attention : pour le mois en cours, « prévues » inclut les activités à venir, donc le taux est bas en début de mois.

**Anomalies** (activité réalisée, une seule affichée, par priorité) : `SANS_GEO` (pas de position), `HORS_SECTEUR` (position hors du contour du secteur du lot de la ligne, au-delà de 50 m ou de la précision déclarée ; **jamais signalée si le contour du secteur n'est pas importé**), `DOUBLON` (même collaborateur, même activité, même plantation, même jour : au moins deux réalisées), `COURTE` (durée < `reporting.duree_courte_minutes`, **15 par défaut, à valider avec le client**, paramètre modifiable). Calcul **côté serveur** (réponse aux points ouverts « qui calcule » et « définition d'un doublon »).

| État | Méthode et chemin | Réservé à | Réponse |
| --- | --- | --- | --- |
| 🟢 | `GET /reporting/secteur/collaborateurs` | CS | `[{id, name, profile}]` : l'équipe du secteur. |
| 🟢 | `GET /reporting/secteur/activites?du&au` | CS | **Liste brute** `ReportActivity {lineId, accountId, activityId, activity, category (VISITE \| FORMATION \| RENCONTRE), plannedOn, doneOn?, anomaly?}`, 5 000 au plus (400 sinon : réduire la période). |
| 🟢 | `GET /reporting/secteur/synthese?du&au` | CS | `Synthesis {today, from, to, total, rows[]}` ; `ReportRow {id (compte), label, planned, done, late, toVerify, rate?, status}`, une ligne par collaborateur (zéros compris). |
| 🟢 | `GET /reporting/departement/secteurs?annee` | CD | `ReportSector {id, code, name, chiefName?, collaborators, plans {validated, submitted, draft}}` (secteurs de l'opérateur sous contrat en cours ; sans planning enregistré : brouillon). |
| 🟢 | `GET /reporting/departement/synthese?du&au` | CD | `Synthesis`, une ligne par **secteur**. |
| 🟢 | `GET /reporting/national/operateurs?annee` | AD, SU | `ReportOperator {id, acronym, name, sectors, collaborators, plans}`. **Pas de `monthlyReport`** : l'origine du rapport mensuel par opérateur n'est pas définie. |
| 🟢 | `GET /reporting/national/synthese?du&au` | AD, SU | `Synthesis`, une ligne par **opérateur**. |
| ⚪ | ~~`GET /reporting/departement/activites`~~ · ~~`GET /reporting/national/activites`~~ | | **Abandonnés** : des dizaines de milliers de lignes par mois ; la synthèse serveur les remplace (réponse au point ouvert « agrégation serveur ou liste brute »). Le front n'a plus à recalculer les taux pour ces deux niveaux. |
| 🟢 | `GET /reporting/secteur/synchronisation` | CS | `[{accountId, name, profile, devices, lastSyncAt?, pending}]` : un collaborateur par ligne, **y compris ceux sans appareil** (`devices: 0`, pas de `lastSyncAt`). Données **signalées par les appareils** (`POST /mobile/synchronisation`), pas déduites des réalisations. `pending` somme les éléments en attente de ses appareils. Le seuil « en retard de synchro » est laissé au front (nombre de jours depuis `lastSyncAt`). |

## 8. Prévu côté front, pas encore appelé
- Historique/traçabilité, reprise de compte, import de contours, permissions fines : écrans pas encore faits (voir « Points ouverts » de `decisions-back.md`).
- Rafraîchissement du jeton : **le back le fournit** (`POST /auth/refresh`) ; le front ne gère encore que l'expiration (401 → reconnexion). Durée du jeton d'accès : 8 h aujourd'hui ; à ramener à 15 minutes quand le front et le mobile appelleront `/auth/refresh`.

## Points ouverts transverses
1. Connexion par e-mail (front) ou par matricule (back) ?
2. ~~Format du corps d'erreur~~ : tranché le 14/11/2026 (RFC 9457 + `code` et `message`, voir Conventions). Le front doit encore lire `message`.
3. Pagination : convention fixée (voir Conventions) ; à appliquer à `/utilisateurs`, `/plantations`, `/notifications`. La recherche serveur reste à définir (paramètre `q`).
4. Concurrence : la secrétaire et le collaborateur peuvent modifier le même brouillon ; prévoir un contrôle de version (ETag ou numéro de révision, 409 en cas de conflit).
5. Où vivent les règles de fréquence (voir 3.5).
6. Fréquence de rafraîchissement des notifications (sondage ou temps réel).

## Journal des mises à jour
| Date | Changement |
| --- | --- |
| 26/10/2026 | Création du document à partir de tout ce que le front appelle (auth, paramètres, planning, dashboard, plantations, carte). Ajout des routes `/plannings/collaborateurs/{userId}…` (saisie par la secrétaire, 25/10) et des routes de notifications. Correction côté front : `HttpPlanningApi` ignorait `userId` ; il appelle désormais bien `/plannings/collaborateurs/{userId}` (+ test). |
| 26/10/2026 | Carte : le filtre est ouvert à la secrétaire ; pour CS et SE, `/carte/catalogue` ne doit renvoyer que leur secteur (et `/carte/plantations` refuser tout autre secteur). |
| 26/10/2026 | Paramètres : le chef secteur obtient le menu avec le seul onglet Références > Sections. Nouvelle ressource `/sections`. Le front charge toujours en bloc `/operateurs`, `/contrats`, `/attributions`, `/exercices`, `/utilisateurs`, `/secteurs`, `/lots`, `/sections`, `/activites` à l'ouverture des Paramètres : le chef secteur doit pouvoir lire au minimum ses contrats, attributions, secteurs et lots (ou le front passera à des appels ciblés). |
| 26/10/2026 | Sections : champ `code` remplacé par `name` (nom libre, saisi par le chef secteur, jamais généré) ; plus aucune section préremplie. |
| 26/10/2026 | Plantations : le chef secteur affecte une plantation à une section (`PUT /plantations/{id}/section`) ou en lot via import Excel (`PUT /plantations/affectations`) ; `PlantationDto` gagne `section_id` / `section_nom`. Le back ne crée jamais de plantation depuis l'import : il n'affecte que des plantations existantes. |
| 26/10/2026 | Paramètres > Références du chef secteur : nouvelle ressource `/groupes` (groupe de vulgarisation : lot + section + nom, village et délégué facultatifs, moniteur obligatoire). |
| 27/10/2026 | Planning : suppression des heures. `PlanLine.hours` disparaît (modèle, saisie, import Excel, totaux : seuls restent le nombre d'activités et de jours). Le front n'envoie plus `hours`. |
| 27/10/2026 | Références : l'administrateur n'a plus ni Sections ni Groupes (seulement Secteurs et Lots). Sections et groupes sont réservés au chef secteur. |
| 27/10/2026 | Reporting du secteur (chef secteur, écran 1 Synthèse) : nouvelle section 7bis (routes proposées pour les activités remontées du mobile, collaborateurs, synchronisation) et règles de calcul. |
| 27/10/2026 | Reporting du département (chef de département) : routes `/reporting/departement/secteurs` et `/reporting/departement/activites` ajoutées au §7bis. |
| 27/10/2026 | Reporting national (administrateur, superviseur) : routes `/reporting/national/operateurs` et `/reporting/national/activites` ajoutées au §7bis. |
| 27/10/2026 | Reporting national : suppression de toute donnée de synchronisation des appareils (`/reporting/national/operateurs` ne renvoie plus `appareils_*`, `jours_sans_synchro`, `derniere_synchro`) ; l'état des rapports mensuels (`rapport_mensuel`) est mis en avant. |
| 27/10/2026 | Reporting du département : suppression des données de synchronisation (`/reporting/departement/secteurs` ne renvoie plus `appareils_*`, `jours_sans_synchro`, `derniere_synchro`). La synchronisation des appareils n'est suivie que par le chef secteur. |
| 28/10/2026 | Planning : une visite de plantation se fait en 1 jour. Pour une ligne de catégorie Visite, `endDate` = `date` (ou absent) ; le back refuse (400) `endDate > date`. Le mobile n'affiche plus de date de fin pour les visites ; formation et rencontre gardent leur période. **Le front web n'applique pas encore cette règle** (à faire dans la saisie du planning). |
| 14/11/2026 | Socle back implémenté (code livré, non compilé par l'assistant) : erreurs RFC 9457 avec `code`/`message`, pagination `page/size/sort`, `POST /auth/refresh` et `POST /auth/logout` (rotation des jetons de rafraîchissement par famille), réponse de connexion enrichie (`refreshToken`, `refreshExpiresAt`), étendue territoriale relue en base (`Perimetre`). Voir `decisions-back.md`. |
| 15/11/2026 | API mobile (§3ter) : `GET /mobile/amorcage`, `POST /mobile/synchronisation`, envoi seul d'un compte désactivé (403 hors envoi, 401 après 30 jours), `GET /reporting/secteur/synchronisation` livré. Un compte désactivé conserve son rafraîchissement (V17). |
| 15/11/2026 | `POST /auth/mot-de-passe` : `refreshToken` facultatif (session conservée), les autres sessions sont révoquées. `/auth/login` et `/auth/refresh` : 429 `TROP_DE_REQUETES` (+ `Retry-After`) au-delà de 30 requêtes/minute par adresse IP. |
