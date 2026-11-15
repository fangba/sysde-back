# SysDE mobile — ce que le back impose (référence)

Dernière mise à jour : 27 octobre 2026. Document de départ pour l'application mobile (hors ligne). Il regroupe ce qui est **décidé** côté back et web, et ce qui reste **à décider**. Sources : `decisions-back.md`, `endpoints-back.md`, `cahier-des-charges-partie1.md`, `identite-visuelle.md`.

## 0. Stack retenue (décidée le 27/10/2026)
- **Flutter** (Dart), Android en priorité. Cible principale : **tablettes de 8 pouces**, mais l'application doit s'adapter à tout appareil Android et aux téléphones (mise en page adaptative, pas deux applications).
- Architecture : hors ligne d'abord (la base locale est la source de lecture de l'écran ; le réseau ne fait que synchroniser). Couches : présentation, domaine (règles de calcul reprises du web), données (base locale + API).
- Paquets pressentis (à valider à l'amorçage) : Riverpod (état), go_router (navigation), drift (SQLite typé), dio (HTTP, intercepteur de jeton), freezed + json_serializable (modèles), flutter_secure_storage (jetons), connectivity_plus, geolocator, camera ou image_picker, workmanager (synchronisation en arrière-plan), uuid (v7 côté appareil), flutter_localizations (français).
- Synchronisation : file d'envoi (outbox) en base, envoi idempotent par UUID v7, reprise sur coupure, photos en différé ; conflits par numéro de révision (409).
- Mise en page : trois largeurs (téléphone < 600 dp, tablette 8 pouces 600 à 840 dp, grande tablette > 840 dp). Sur tablette : liste et détail côte à côte (ex. planning à gauche, activité à droite), navigation latérale à la place de la barre du bas. Cibles tactiles ≥ 48 dp, usage en plein soleil (contraste élevé), orientation portrait et paysage.
- Design : jetons du web transposés en `ThemeData` (Material 3) ; la maquette actuelle est une version téléphone, la version tablette reste à dessiner.
- Risque à tester tôt : 500 activités et 50 photos en attente sur une tablette d'entrée de gamme.

## 1. Utilisateurs du mobile
- Profils de terrain : CF contrôleur de saignée, CO contrôleur ordinaire, FS formateur de saignée, MO moniteur, ES équipe spéciale. Le chef secteur (CS) et la secrétaire (SE) utilisent surtout le web ; leur place sur le mobile est à confirmer.
- Un compte = un utilisateur, un seul profil actif. Seul le compte agit et est tracé (audit).
- Les comptes sont créés par l'organisation (chef secteur, administrateur) : **pas d'inscription depuis le mobile**.

## 2. Authentification
- Connexion par **matricule** (`ACRONYME-NUMERO`, ex. SAPH-00348) + mot de passe. Le front web de démonstration utilise l'e-mail : à trancher.
- Jeton d'accès + jeton de rafraîchissement (`iam.jeton_compte`, `iam.jeton_rafraichissement`). Compatible JWT/OIDC.
- Connexion en ligne obligatoire la première fois ; ensuite usage hors ligne. Un compte désactivé ne peut plus synchroniser après un délai (paramètre, 30 jours par défaut).
- Changement de mot de passe : `POST /auth/mot-de-passe` `{currentPassword,newPassword}` → 204, minimum 8 caractères.

## 3. Données descendantes (serveur → téléphone)
**Implémenté (15/11/2026)** : `GET /mobile/amorcage` sert tout ce paragraphe d'un coup (hors participants et plantations) ; envoi, rejeu, conflits et envoi seul d'un compte désactivé : `endpoints-back.md` §3bis et §3ter.

- Planning annuel du collaborateur : lignes = période (début, fin incluses), activité, lot/section, plantation pour une visite. **Pas d'heures.**
- États de planning et d'activité : brouillon, SOUMIS, VALIDE, REJETE (motif visible).
- Référentiel : activités autorisées du poste (catégorie Formation / Visite / Rencontre, fréquence, durée en jours), lots, sections, groupes de vulgarisation, plantations affectées.
- Plantations : viennent d'une base externe (API tierce, lecture seule). SysDE ne stocke que l'affectation plantation → section.
- Notifications : créées côté serveur à chaque validation ou rejet.

## 4. Données montantes (téléphone → serveur)
- Réalisation d'une activité : activité du planning, date, position GPS (précision), photos, observations, début et fin.
- Anomalies (« à vérifier ») : activité portant une anomalie (position hors secteur, durée courte, doublon…). **Qui les calcule (mobile ou back) reste ouvert.**
- Rapport mensuel par opérateur : origine à définir.

## 5. Règles de calcul (identiques au web)
- Taux = réalisées / prévues. À jour ≥ 90 %, Léger retard ≥ 75 %, sinon En retard.
- En retard = prévue avant la date du jour et non réalisée.
- Fréquences : nombre de séances attendu par période (an, semestre, mois), éventuellement « par plantation / saigneur / moniteur / groupe ».

## 6. Contraintes techniques du back utiles au mobile
- Identifiants **UUID v7** : le mobile peut les générer hors ligne (idempotence des envois).
- Suppression logique, colonnes `cree_le`, `modifie_le`, `version` (contrôle de version pour les conflits).
- Stockage objet (MinIO) pour les photos. Taille et nombre de photos : à fournir.
- Géométries en WGS 84 (SRID 4326).
- Volumétrie : environ 950 techniciens, 5 000 activités par heure ouvrée, 5 000 connexions simultanées.
- Erreurs : RFC 9457 (format du corps d'erreur à finaliser).

## 7. À décider avant de coder le mobile
- Technologie du mobile (Flutter, React Native, Kotlin/Swift) et base locale.
- Protocole de synchronisation : file d'envoi, reprise sur coupure, conflits (ETag ou numéro de révision, 409), envoi des photos en différé.
- Fréquence GPS, taille et nombre de photos, format du rapport.
- Charge utile exacte d'une activité remontée.
- Rôle du chef secteur et de la secrétaire sur le mobile.
- Données de synchronisation visibles du seul chef secteur (bandeau « appareils » du reporting web) : champs à fournir par le mobile (appareil, dernière synchro, nombre en attente).

## 8. Identité visuelle (mobile)
Mêmes jetons que le web : vert marque `#0c7a48`, action `#138553 → #0c7a48`, vert foncé `#0f3d28`, vert clair `#dff3e6` / `#c9f0d8`, fond `#f4f7f5`, texte `#0e1a14` / `#5b6b62`, bordure `#e6ece8`, avertissement `#fdf1d6` / `#8a5d06`, rouge `#fde8e8` / `#b42318`. Catégories : Visite vert `#0c7a48`, Formation menthe `#34c38f`, Rencontre violet `#6d4fd1` (violet réservé aux données). Police Montserrat. Logo : `public/images/logo.png` du front.

## 9. Maquette
Canvas Design « SysDE Mobile — Maquette » : 6 écrans (démarrage, connexion, accueil, planning, activité, synchronisation). Lien : https://claude.ai/artifact/GuN37PNfdTBzQn5s4zLhuc
