# SysDE — back-end

Spring Boot 4.1 · Java 25 LTS · PostgreSQL + PostGIS · Flyway · monolithe modulaire.

## Base de données

Une base, cinq schémas :

| Schéma        | Contenu                                                              |
|---------------|----------------------------------------------------------------------|
| `referentiel` | opérateurs (organismes et techniques), secteurs, lots, sections      |
| `gestion`     | contrats, attributions (lots par secteur), exercices                 |
| `iam`         | utilisateurs, comptes, profils, permissions, affectations, reprise, jetons, import CSV |
| `audit`       | piste d'audit, ajout seul, partitionnée par mois                     |
| `util`        | fonctions communes (UUID v7, horodatage), paramètres                 |

Le futur schéma `planning` (programmes d'activité, activités) viendra après les règles métier.

### Migrations (`src/main/resources/db/migration`)

| Fichier                        | Rôle                                                       |
|--------------------------------|------------------------------------------------------------|
| `V1__fondations.sql`           | schémas, rôle `sysde_app`, `util.uuid_v7()`, horodatage    |
| `V2__referentiel.sql`          | opérateurs, secteurs, lots, sections                       |
| `V3__gestion.sql`              | contrats, attributions, exclusivité des lots, exercices    |
| `V4__iam.sql`                  | profils, utilisateurs, comptes, matricules, affectations, reprise |
| `V5__audit.sql`                | audit partitionné, triggers de capture, traçage des connexions |
| `V6__droits.sql`               | droits du rôle applicatif (moindre privilège)              |
| `V7__donnees_de_reference.sql` | 10 profils, permissions, APROMAC et FIRCA, paramètres      |
| `V8__geometries_postgis.sql`   | colonnes `geom` et index GiST (nécessite PostGIS)          |

### Conventions

- Identifiants **UUID v7**. Le serveur génère par défaut ; le mobile peut fournir le sien (envois idempotents).
- **Suppression logique** (`supprime_le`, `supprime_par`) sur les tables de gestion ; les unicités sont des index partiels `WHERE supprime_le IS NULL`.
- Colonnes techniques renseignées par trigger : `cree_le/par`, `modifie_le/par`. `version` sert au verrouillage optimiste (Hibernate `@Version`).

### Ce que l'application doit faire à chaque transaction

```sql
SELECT set_config('app.compte_id',      '<uuid du compte connecté>',         true);
SELECT set_config('app.requete_id',     '<identifiant de corrélation>',      true);  -- facultatif
SELECT set_config('app.adresse_ip',     '<ip>',                              true);  -- facultatif
SELECT set_config('app.motif',          '<motif saisi>',                     true);  -- facultatif
```

`true` limite la valeur à la transaction : compatible PgBouncer en mode transaction.
Sans `app.compte_id`, seule la création d'un administrateur est acceptée (amorçage).
Les connexions (succès, échecs, verrouillages) sont tracées par `audit.tracer_connexion(...)`.

### Modèle des comptes (cahier des charges, sections 4 à 6)

- **Utilisateur** : la personne (activé / désactivé). **Compte** : appartient à un utilisateur pour toute sa vie, c'est lui
  qui agit et qui est tracé. **Profil** : AD, SU, CD, CS, CF, CO, FS, MO, ES, SE ; il porte les permissions.
- Matricule `ACRONYME-NUMERO` (ex. `SAPH-00348`) : stable, identifiant de connexion, numéro unique par organisation tous profils
  confondus, jamais réutilisé ; repris de l'opérateur ou attribué. Correction possible par l'AD seul (ancien matricule en alias).
- `reference_compte` (ex. `MO-SAPH-00348`) : profil + matricule, calculée.
- Un seul compte actif par utilisateur ; un nouvel arrivant a un nouveau compte ; changement de profil avec motif et historique.
- Départ : compte désactivé, jamais supprimé ; `iam.reprendre_compte()` transfère les affectations en cours au successeur.
  Les activités en cours seront transférées de la même manière par le schéma `planning` (à venir).
- Affectation : liée au territoire (secteur, lot, section) et à des dates, faite une seule fois ; la vue `iam.v_affectation_etat`
  donne ACTIVE, SUSPENDUE, A_REVOIR ou CLOSE.
- Fonctions de décision : `iam.compte_peut_se_connecter()`, `iam.compte_peut_synchroniser()` (droit d'envoi seul après
  désactivation, paramètre `compte.delai_synchronisation_jours`), `iam.profil_a()` (profil à un instant donné).

### Règles portées par la base

Exclusivité d'un lot sur la période d'un contrat · pas de contrats chevauchants pour un opérateur ·
retrait d'un lot avec motif obligatoire, sans transfert à un autre opérateur avant la fin du contrat, impossible tant qu'un
agent y est affecté · exercices générés automatiquement (une période de 12 mois à partir du début du contrat, la dernière plus courte si besoin) ·
hiérarchie de création des comptes (AD → AD, SU, CD ; CD → CS ; CS → techniciens) et permissions correspondantes ·
isolation APROMAC / FIRCA à la création · numéro de matricule unique, jamais réutilisé · un compte actif par utilisateur ·
profil immuable sauf changement motivé par un compte de rang supérieur · permissions ajustables par l'AD, qui ne peut perdre
ni « gérer les permissions » ni « gérer les référentiels » · périmètre par profil (maximum d'affectations, un secteur à la fois,
un CS par secteur et opérateur, un moniteur par section, territoire couvert par un contrat de l'opérateur) ·
désactivation d'un utilisateur ou d'un opérateur : accès bloqué, sessions coupées · piste d'audit non modifiable par
l'application, avec compte, utilisateur et profil de l'acteur.

### Tests

`src/test/resources/db/regles_metier.sql` vérifie ces règles sur une base migrée (252 contrôles avec PostGIS, 241 sans) :

```bash
psql -v ON_ERROR_STOP=1 -d sysde_test -f src/test/resources/db/regles_metier.sql
# -v AFFICHER_TOUT=oui pour lister chaque contrôle
```

Les dates du jeu d'essai sont relatives à l'année courante (contrats antérieurs, en cours et à venir) : les tests
ne dépendent pas de la date d'exécution. Les contrôles de géométrie ne s'exécutent que si PostGIS est installé.
À rejouer avec Testcontainers (image `postgis/postgis`, PostgreSQL 18 + PostGIS 3.6) dans la suite JUnit.

### État de validation

V1 à V8 sont exécutées et testées sur **PostgreSQL 18.6 + PostGIS 3.6.4** (252 contrôles, 0 échec). V1 à V7 passent
aussi sur PostgreSQL 16 (241 contrôles) ; `util.uuid_v7()` utilise `uuidv7()` natif dès PostgreSQL 18.
PostgreSQL 19 n'étant qu'en version candidate, la cible reste la 18.


## Application Spring Boot (socle, 12/11/2026)

Code dans `src/main/java/ci/sysde`, un package par module (`common`, `config`, `security`, `iam`).

### Démarrer en local
```bash
docker compose -f docker/compose.yml up -d      # PostgreSQL 18 + PostGIS
mvn spring-boot:run                         # Flyway applique V1 à V8 au démarrage
```
Documentation de l'API : `http://localhost:8080/swagger-ui.html` · santé : `/actuator/health`.
Variables : `SYSDE_DB_URL`, `SYSDE_DB_USER`, `SYSDE_DB_PASSWORD` (rôle `sysde_app` en production),
`SYSDE_FLYWAY_*` (propriétaire du schéma), `SYSDE_JWT_SECRET` (32 octets au moins), `SYSDE_CORS_ORIGINES`.

### Ce qui est en place
- **Erreurs** : un seul format `{ code, message, horodatage, details[] }` (`common/ApiError`). `BusinessException(statut, code, message)` pour les règles métier ; les violations de contraintes de la base donnent 409.
- **Sécurité** : API sans état, jeton Bearer JWT (HS256) portant compte, profil, opérateur et permissions (autorités `PERM_xxx`). Tout est refusé sauf `POST /auth/login`, la santé et la documentation. L'étendue des données est toujours relue côté serveur à partir du jeton.
- **Acteur de la transaction** : `security/ActeurTransaction` pose `app.compte_id` au début de chaque méthode `@Transactional` pour que les triggers renseignent `cree_par`, `modifie_par` et la piste d'audit.
- **Authentification** : `POST /auth/login` (matricule + mot de passe), `GET /auth/moi`, `POST /auth/mot-de-passe`. Verrouillage temporaire après 5 échecs (15 min), connexions tracées par `audit.tracer_connexion`.
- **Tests** : unitaires (règles de verrouillage, mot de passe, jeton). Les tests d'intégration sur PostgreSQL (Testcontainers, image PostGIS) restent à ajouter.

### Pas encore fait
Rafraîchissement du jeton (table `iam.jeton_rafraichissement` prête), activation et réinitialisation du mot de passe, création et gestion des comptes, paramètres, traçabilité, schéma `planning` et synchronisation mobile.
