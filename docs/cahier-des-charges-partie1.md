# SysDE — cahier des charges, partie 1 (en cours de rédaction)

Rédigé section par section avec le client. Une section n'est « validée » que lorsque le client l'a confirmée.
Hors périmètre de cette partie : planning d'activité (règles à venir), activités, mobile, dashboard, reporting, carte (leur contenu métier viendra plus tard ; seules les permissions d'accès sont fixées ici).

Plan : 1 Organisations et opérateurs · 2 Territoire · 3 Contrats, attributions, exercices · 4 Utilisateurs, comptes, profils et permissions · 5 Création et vie des comptes · 6 Affectations · 7 Traçabilité · 8 Exigences non fonctionnelles.

---

## 1. Organisations et opérateurs — VALIDÉE (19/10/2026)

- **ORG-01** Il existe deux types d'organisation. Les **organismes** (APROMAC, FIRCA) supervisent et administrent sans pratiquer l'encadrement. Les **opérateurs techniques** (SAPH, SOGB, TRCI, EXAT, CCP, IDH, CHC) pratiquent l'encadrement technique.
- **ORG-02** Les deux organismes existent dès l'installation et ne se créent ni ne se suppriment depuis l'application. Les opérateurs techniques sont créés par les administrateurs ; leur nombre n'est pas limité à sept.
- **ORG-03** Chaque organisation a un **acronyme** unique de 2 à 8 caractères (lettres majuscules et chiffres), par exemple SAPH. Il entre dans les matricules (`SAPH-00348`) et ne change plus dès que des comptes sont créés.
- **ORG-04** Une organisation est décrite par son acronyme, sa dénomination, son **adresse**, son e-mail, son téléphone et son état actif ou non.
- **ORG-05** Les utilisateurs d'APROMAC et de FIRCA sont cloisonnés : un administrateur ne voit ni ne gère les utilisateurs de l'autre organisme. Les opérateurs, secteurs, lots, contrats et exercices sont communs aux deux. Un contrat enregistre l'organisme qui a mandaté l'opérateur (FIRCA).
- **ORG-06** Une organisation avec des contrats ou des comptes ne peut pas être supprimée, seulement désactivée. **Désactiver un opérateur bloque immédiatement la connexion de tous ses comptes.**

Conséquences techniques retenues : à la désactivation, les sessions en cours sont révoquées et les nouvelles connexions refusées ; les jetons d'accès déjà émis sont rejetés par un contrôle de l'état de l'opérateur (cache de moins d'une minute, invalidé à la désactivation).

---

## 2. Territoire : secteur, lot, section — VALIDÉE (19/10/2026)

- **TER-01** Trois niveaux : secteur → lot → section.
- **TER-02** Un secteur a un code unique et un nom. Seuls les administrateurs le créent et le modifient.
- **TER-03** Un lot a un numéro, unique dans son secteur, et une description. Seuls les administrateurs le créent et le modifient. Le nombre de lots par secteur n'est pas figé (3 aujourd'hui). Les localités sont retirées pour le moment.
- **TER-04** Une section a un numéro, unique dans son lot, et un nom. Seuls les chefs secteur les créent et les modifient, dans les lots attribués à leur opérateur. Pas de plafond par lot. Les sections restent attachées au lot : le nouvel opérateur d'un lot reprend ses sections.
- **TER-05** Un secteur peut être exploité par plusieurs opérateurs (jusqu'à trois). Le lot est l'unité d'attribution exclusive.
- **TER-06** On ne supprime pas un secteur, un lot ou une section qui contient des éléments, est attribué ou a des agents affectés. La suppression est logique.
- **TER-07** Les **secteurs et les lots** peuvent recevoir un contour (shapefile ou GeoJSON), en WGS 84 uniquement. Le contour des sections est réservé pour plus tard (la colonne existe, aucun import pour l'instant).
- **TER-08** Un import de contour se fait élément par élément : on choisit le secteur ou le lot, puis on dépose son fichier. Si le fichier contient des attributs d'identification (code du secteur ; code du secteur et numéro du lot), ils doivent désigner l'élément choisi, sinon l'import est refusé. Les imports se font à tout moment, quand les fichiers existent.
- **TER-09** Le fichier est contrôlé en entier avant application : géométrie valide, coordonnées plausibles pour la Côte d'Ivoire. Remplacer un contour existant demande une confirmation et l'ancien fichier est conservé.
- **TER-10** Un contour qui dépasse de son parent (lot hors de son secteur) ou qui recoupe un voisin n'est **pas bloquant** : l'import affiche un avertissement avec la surface qui dépasse.

En standby : le système national TMCI-5.5 (ITRF2014, époque 2010.0) et ses paramètres ne sont pas traités pour l'instant. Tout est en WGS 84.

---

## 3. Contrats, attributions, exercices — VALIDÉE (21/10/2026)

- **CON-01** Un contrat lie un opérateur technique à une période (date de début, date de fin). Il a une référence unique et enregistre l'organisme mandant (FIRCA). Seuls les administrateurs le créent et le modifient.
- **CON-02** Deux contrats d'un même opérateur ne peuvent pas se chevaucher.
- **CON-03** L'état d'un contrat se déduit des dates : à venir, en cours, terminé.
- **CON-04** La durée d'un contrat est aujourd'hui de **3 ans** ; elle peut évoluer. Elle est donc un paramètre modifiable par les administrateurs (`contrat.duree_annees`), pas une valeur figée dans le code. Les contrats déjà créés gardent leur durée.
- **CON-05** Une **attribution** est l'ensemble des lots d'un secteur confiés à un opérateur pour la durée de son contrat. Il y en a une par couple contrat / secteur. Seuls les administrateurs l'établissent.
- **CON-06** Un lot ne peut appartenir à deux opérateurs sur des périodes de contrat qui se recouvrent. Un secteur peut être partagé, jamais un lot.
- **CON-07** Pendant un contrat : on peut **ajouter** un lot libre à une attribution ; on peut **retirer** un lot s'il n'a plus d'agent affecté, avec un **motif obligatoire** conservé dans l'historique (lot, opérateur, motif, auteur, date) ; on ne peut **jamais donner un lot retiré à un autre opérateur** avant la fin du contrat dont il a été retiré. Le changement d'opérateur se fait à la fin du contrat.
- **CON-08** Un **exercice** est une période de 12 mois consécutifs d'une attribution, comptée à partir de la date de début du contrat (l'exercice n° 1 commence à cette date, le n° 2 un an plus tard, etc.), avec une date de clôture. Il est unique par attribution et par numéro ; il doit être couvert par le contrat ; le dernier est plus court si le contrat ne se termine pas à un anniversaire ; la clôture ne peut pas précéder le début de l'exercice. Exemple : un contrat du 1er juin 2026 au 31 mai 2029 a trois exercices, du 1er juin au 31 mai. Seuls les administrateurs le modifient.
- **CON-09** **Génération automatique** : à la création d'une attribution, un exercice est créé pour chaque période de 12 mois couverte par le contrat, clôturé à la fin de la période (modifiable). Si le contrat est prolongé ou raccourci, les périodes sont recalculées et les exercices manquants sont ajoutés.
- **CON-10** On ne supprime pas un contrat qui a des attributions, ni une attribution qui a des exercices.

Décision (21/10/2026) : l'exercice n'est pas une année civile mais une période de 12 mois à partir de la date de début du contrat.

---

## 4. Utilisateurs, comptes, profils et permissions — VALIDÉE (20/10/2026)

### Utilisateur, compte, profil

- **PRO-01** Un **utilisateur** est une personne enregistrée. Il est activé ou désactivé. S'il est désactivé, il n'accède à aucun compte.
- **PRO-02** Un **compte** appartient à un seul utilisateur pendant toute sa vie. C'est le compte qui agit dans le système, et tout ce qu'il fait est tracé. Il est activé ou désactivé et identifiable par son matricule.
- **PRO-03** Un compte a **un seul profil** parmi dix. Le profil porte les droits.
- **PRO-04** Un utilisateur a **un seul compte actif à la fois**. Un nouvel arrivant reçoit toujours un nouveau compte et un nouveau matricule.
- **PRO-05** Le **matricule** a la forme `ACRONYME-NUMÉRO` (ex. `SAPH-00348`, numéro de 5 à 7 chiffres). Il ne change pas de lui-même (ni à la promotion, ni au changement de profil) et sert d'identifiant de connexion : l'utilisateur saisit uniquement le matricule. Le numéro est unique au sein de l'organisation, **tous profils confondus** (un moniteur et un chef secteur ne peuvent pas partager un numéro). Seul un administrateur peut le corriger (voir COM-03).
- **PRO-06** La **référence du compte** (ex. `MO-SAPH-00348`) est calculée à partir du profil actuel et du matricule. Elle n'est jamais saisie et change quand le profil change.

| Code | Profil | Organisation | Niveau d'affectation |
|---|---|---|---|
| AD | Administrateur | APROMAC, FIRCA | aucun (tout) |
| SU | Superviseur | APROMAC, FIRCA | aucun (tout, en consultation) |
| CD | Chef de département | Opérateur | aucun (tous les secteurs de son opérateur) |
| CS | Chef secteur | Opérateur | secteur (1 attribution) |
| CF | Contrôleur de saignée | Opérateur | lot (1) |
| CO | Contrôleur ordinaire | Opérateur | sections (sans plafond ; 6 en pratique, jamais bloquant) |
| FS | Formateur de saignée | Opérateur | lot (1) |
| MO | Moniteur | Opérateur | section (1) |
| ES | Équipe spéciale | Opérateur | secteur |
| SE | Secrétaire | Opérateur | secteur |

Plusieurs comptes d'un même profil peuvent exister chez un opérateur (plusieurs MO, plusieurs CS).

### Changement de profil

- **PRO-07** Un compte peut être **promu, rétrogradé ou changer de profil** latéralement, avec un **motif obligatoire**. Le compte et le matricule restent ; la référence du compte change. Le changement n'est possible qu'à l'intérieur d'un même type d'organisation (un profil d'opérateur ne devient pas AD ou SU).
- **PRO-08** Les changements de profil sont conservés avec leurs dates. La traçabilité retient le profil du compte au moment de l'action.
- **PRO-09** Au changement de profil, les affectations du compte sont closes. Les activités en cours restent au compte ; le chef secteur répartit celles qui sortent du nouveau profil.

### Départ et reprise

- **PRO-10** Quand un collaborateur part, son compte est désactivé, jamais supprimé.
- **PRO-11** Une **reprise** transfère vers le compte du successeur : les affectations en cours (closes chez l'ancien) et les activités non réalisées ou en cours. Chaque transfert est tracé (date, auteur, motif). La reprise est lancée par celui qui gère le compte dans la hiérarchie de création.
- **PRO-12** Le travail déjà réalisé reste attaché à l'ancien compte, définitivement. Une activité en cours est reprise entière par le successeur ; les saisies partielles de l'ancien lui restent attachées.
- **PRO-13** Départ sans remplaçant : le travail reste non attribué en attendant, ou le chef secteur le répartit entre plusieurs techniciens.
- **PRO-14** Un compte désactivé garde un **droit d'envoi seul** pour synchroniser le mobile pendant **30 jours** (paramètre modifiable `compte.delai_synchronisation_jours`). Ces saisies restent attribuées à l'ancien compte.

### Permissions

- **PRO-15** Chaque profil possède un ensemble de **permissions**, c'est-à-dire d'actions autorisées. Elles sont **identiques pour tous les opérateurs**. Les administrateurs peuvent les ajuster ; chaque ajustement est tracé.
- **PRO-16** Ne sont pas ajustables : la liste des profils, leur niveau d'affectation, la hiérarchie de création des comptes, et les permissions « Gérer les permissions » et « Gérer les référentiels » de l'AD (garde-fou contre le blocage du système).
- **PRO-17** Les permissions ne sont pas figées dans le jeton de connexion : elles sont relues côté serveur (cache de moins d'une minute), si bien qu'un changement s'applique dès la requête suivante.
- **PRO-18** La **portée** d'une permission découle du profil et des affectations : un technicien, ses propres données ; le SE, les techniciens de son secteur ; le CS, son secteur ; le CD, son opérateur ; l'AD et le SU, tout.
- **PRO-19** Les techniciens (CF, CO, FS, MO, ES) et le SE se connectent aussi au web : ils y voient le planning d'activité, les activités, le dashboard et la carte.

| Permission | AD | SU | CD | CS | CF | CO | FS | MO | ES | SE |
|---|---|---|---|---|---|---|---|---|---|---|
| Gérer les référentiels | ✓ | | | | | | | | | |
| Gérer les permissions | ✓ | | | | | | | | | |
| Créer un compte | ✓ | | ✓ | ✓ | | | | | | |
| Gérer un compte | ✓ | | ✓ | ✓ | | | | | | |
| Importer des comptes (CSV) | | | | ✓ | | | | | | |
| Changer le profil d'un compte | ✓ | | ✓ | ✓ | | | | | | |
| Reprendre un compte | ✓ | | ✓ | ✓ | | | | | | |
| Consulter tous les secteurs | | | ✓ | | | | | | | |
| Affecter une attribution à un CS | | | ✓ | | | | | | | |
| Gérer les sections | | | | ✓ | | | | | | |
| Affecter les techniciens | | | | ✓ | | | | | | |
| Consulter le planning d'activité | | | | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| Enregistrer un planning d'activité | | | | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| Valider un planning d'activité | | | | ✓ | | | | | | |
| Consulter les activités | | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| Réaliser une activité | | | | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | |
| Contrôler une activité | | | | ✓ | ✓ | ✓ | | | | |
| Dashboard | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| Reporting | ✓ | ✓ | ✓ | ✓ | | | | | | |
| Carte | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| Consulter la traçabilité | ✓ | ✓ | ✓ | ✓ | | | | | | |
| Exporter la traçabilité | ✓ | | | | | | | | | |

Précisions : la traçabilité se consulte sur le périmètre décrit en TRA-06 (AD : tout son organisme ; SU : en lecture ; CD : son opérateur ; CS : son secteur) et ne s'exporte que par l'AD (TRA-08). une seule permission « enregistrer un planning » (son propre planning pour un technicien ou le CS, ceux des techniciens pour le SE). Le CS valide le planning de ses collaborateurs d'encadrement et consulte leurs activités ; personne ne valide le planning du CS. Le SU et le CD ne voient aucun planning, seulement les activités. DI (directeur) est supprimé : il faisait double emploi avec SU. « Valider une activité » et « Transmettre un dossier » sont retirés de l'ancienne version.

Points reportés : la hiérarchie de création est reprise en section 5 ; l'affectation d'une attribution à un CS (par le CD) en section 6 ; l'enregistrement du compte **et** de l'utilisateur qui l'occupait dans chaque trace en section 7.

---

## 5. Création et vie des comptes — VALIDÉE (21/10/2026)

- **COM-01** Hiérarchie de création : AD crée AD, SU et CD ; CD crée CS ; CS crée CF, CO, FS, MO, ES et SE. Un compte ne crée que dans son propre opérateur. Seul l'AD crée un CD, pour l'opérateur de son choix.
- **COM-02** Créer un compte demande le nom, les prénoms, le profil, un téléphone et éventuellement un e-mail. L'utilisateur est soit une personne déjà enregistrée, soit créée en même temps. Un avertissement (non bloquant) signale un doublon probable quand le nom et le téléphone coïncident avec une personne existante.
- **COM-03** **Matricule.** Les opérateurs ont déjà des matricules : le **numéro** est saisi à la création du compte ou fourni dans la colonne « numéro » du fichier CSV, de façon à reprendre le numéro historique. S'il n'est pas fourni, le système en attribue un, en évitant tous les numéros déjà pris, sans jamais en réutiliser. Seul l'**AD** peut corriger le numéro d'un compte existant, avec un motif obligatoire ; la correction est tracée et l'ancien matricule est conservé comme alias (refusé à la connexion, retrouvable dans l'historique).
- **COM-04** Un compte créé est « en attente d'activation » : il ne peut pas se connecter tant que son utilisateur n'a pas défini son mot de passe. Deux modes selon les cas : lien d'activation envoyé par e-mail (valable quelques jours) si l'e-mail est renseigné ; sinon mot de passe temporaire remis par le responsable, à changer à la première connexion.
- **COM-05** Le mot de passe est stocké haché, jamais en clair et jamais visible, même pour l'AD. Il respecte des règles minimales (longueur, pas un mot courant).
- **COM-06** Après 5 échecs consécutifs, le compte est verrouillé 15 minutes et l'événement est tracé. Le message d'erreur ne dit jamais si c'est le matricule ou le mot de passe qui est faux.
- **COM-07** La réinitialisation d'un mot de passe est lancée par le responsable hiérarchique. Elle remet le compte en « attente d'activation » et coupe toutes ses sessions.
- **COM-08** Désactiver un compte coupe immédiatement ses sessions ; il peut être réactivé. Un compte avec de l'historique ne se supprime jamais (suppression logique).
- **COM-09** **Import CSV.** Un modèle de fichier est téléchargeable dans l'application. Le fichier est contrôlé en entier ; un rapport indique les erreurs ligne par ligne (profil inconnu, doublon, numéro déjà pris, e-mail invalide…). Le responsable confirme, puis les lignes valides sont appliquées et les lignes rejetées sont fournies dans un fichier à corriger.
- **COM-10** Chaque création, import, correction de matricule, activation, verrouillage, réinitialisation et désactivation est tracé.

---

## 6. Affectations — VALIDÉE (21/10/2026)

- **AFF-01** Une affectation relie un compte à une partie du territoire. Elle se fait **une seule fois**, sans renouvellement annuel : elle suit le contrat et reste valable d'un exercice à l'autre. Un compte n'a qu'un seul secteur à un instant donné.
- **AFF-02** Ce qu'un compte reçoit dépend de son profil :

| Profil | Affecté à | Maximum |
|---|---|---|
| CS | un secteur, c'est-à-dire les lots que son opérateur y détient | 1 |
| CF, FS | un lot | 1 |
| MO | une section | 1 |
| CO | des sections | aucun plafond, aucun avertissement |
| ES, SE | un secteur | 1 |
| AD, SU, CD | aucune (leur périmètre découle du profil) | — |

- **AFF-03** Le CD affecte un secteur à ses CS. Le CS affecte ses techniciens dans les lots et sections de son opérateur. Si un secteur est partagé entre deux opérateurs, chacun a son CS, qui ne voit et ne gère que les lots de son opérateur.
- **AFF-04** Plusieurs CF ou FS peuvent être sur un même lot. Une section n'a qu'un seul MO à la fois.
- **AFF-05** Une affectation a une date de début et une date de fin. Un changement en cours de route (départ, reprise, changement de profil) clôt l'ancienne affectation et en ouvre une nouvelle, sans rien effacer.
- **AFF-06** Un lot affecté à un agent ne peut pas être retiré du contrat (CON-07).
- **AFF-07** Un compte sans affectation reste actif, mais ne voit aucune donnée territoriale.
- **AFF-08** **Passage d'un contrat à l'autre.** L'affectation est liée au territoire, pas au contrat. Si le nouveau contrat de l'opérateur couvre toujours le même territoire, les affectations continuent sans rien refaire. Si un lot ou un secteur n'est plus couvert, les affectations concernées sont signalées « à revoir », pas supprimées : l'agent ne voit plus ce territoire et son responsable (CS pour un technicien, CD pour un CS) est invité à le réaffecter. Entre deux contrats, les affectations sont suspendues puis reprennent avec le nouveau contrat.

---

## 7. Traçabilité — VALIDÉE (21/10/2026)

- **TRA-01** Toute création, modification, suppression logique et changement d'état de donnée est enregistré : qui (le compte **et** l'utilisateur qui l'occupait), quand, quoi (valeur avant et après), depuis quelle adresse IP, et le motif quand il est exigé (retrait de lot, changement de profil, correction de matricule, reprise).
- **TRA-02** Les connexions sont tracées : succès, échecs, verrouillages, déconnexions, changements et réinitialisations de mot de passe.
- **TRA-03** Rien dans la traçabilité ne peut être modifié ni effacé, par personne, administrateur compris. Les enregistrements sont ajoutés seulement.
- **TRA-04** Les mots de passe et leurs empreintes ne sont jamais enregistrés (on voit seulement qu'ils ont changé). Les contours géographiques non plus : on voit qu'un contour a changé, et le fichier d'origine est conservé.
- **TRA-05** Pour les saisies faites hors ligne sur mobile, on garde la date réelle de l'action et la date de synchronisation.
- **TRA-06** La traçabilité se consulte par compte, utilisateur, date, type d'objet et objet précis. L'AD la consulte sur tout son organisme, le SU en lecture. Le CD la consulte sur les comptes de son opérateur, le CS sur ceux de son secteur. Le cloisonnement APROMAC / FIRCA s'applique.
- **TRA-07** On trace uniquement les modifications et les connexions, pas les simples consultations.
- **TRA-08** Un extrait de la traçabilité peut être exporté en fichier pour un audit externe, par l'AD seulement. L'export est lui-même tracé.
- **TRA-09** Conservation : **3 ans** en consultation directe, durée portée par un paramètre modifiable (`audit.conservation_mois`, 36). Au-delà, les mois anciens sont archivés (détachés de la consultation directe), jamais détruits : un litige après la fin d'un contrat reste traçable.
- **TRA-10** Les événements sont rangés par mois, ce qui permet d'archiver les anciens mois sans ralentir les récents.

---

## 8. Exigences non fonctionnelles — VALIDÉE (21/10/2026)

- **EXI-01** **Volumétrie :** 1 million de comptes ; jusqu'à 5 000 connexions simultanées (pics de synchronisation mobile) ; jusqu'à 5 000 activités par heure ; environ 950 techniciens.
- **EXI-02** **Performance :** une page ordinaire s'affiche en moins de 2 secondes pour 95 % des requêtes. La recherche parmi 1 million de comptes répond en moins d'1 seconde, avec pagination.
- **EXI-03** **Disponibilité :** 99,5 % par mois (environ 3 h 40 d'arrêt cumulé). Les maintenances sont planifiées de nuit et annoncées à l'avance. *(validé)*
- **EXI-04** **Sauvegardes :** sauvegarde complète quotidienne, avec archivage continu des changements. Perte de données maximale de 15 minutes, reprise en moins de 4 heures. Une restauration est testée chaque trimestre. *(validé)*
- **EXI-05** **Sécurité :** connexions chiffrées partout ; jeton d'accès court (15 minutes) avec renouvellement ; mots de passe hachés avec un algorithme moderne ; limitation du nombre de requêtes par adresse ; secrets hors du code ; vérification des droits côté serveur à chaque requête.
- **EXI-06** **Données personnelles :** les données de 1 million de personnes sont soumises à la loi ivoirienne de protection des données personnelles (loi 2013-450, autorité ARTCI). À vérifier : déclaration et durées de conservation exigées.
- **EXI-07** **Hors ligne (mobile) :** l'envoi du mobile est répétable sans doublon (identifiants générés par le téléphone), avec la date réelle de l'action conservée.
- **EXI-08** **Fichiers :** photos, rapports et contours sont stockés à part de la base, dans un stockage objet, sauvegardés eux aussi. Référence de dimensionnement : 5 photos de 1,5 Mo par activité, soit environ 37 Go reçus en une heure de pointe à 5 000 activités.
- **EXI-09** **Supervision :** surveillance en continu (état des serveurs, erreurs, lenteurs) avec alertes à l'équipe d'exploitation.
- **EXI-10** **Interface :** en français, utilisable sur ordinateur et tablette, navigateurs Chrome, Edge et Firefox (versions récentes). Heures conservées en UTC, affichées à l'heure d'Abidjan.
- **EXI-11** **Hébergement :** la plateforme est installée chez l'organisme APROMAC. Pour tenir EXI-03 et EXI-04 : alimentation électrique de secours, double liaison internet, copie des sauvegardes hors du site d'hébergement.
- **EXI-12** **Connexion instable (web).** Le web exige une connexion mais ne perd jamais le travail : un formulaire en cours est sauvegardé localement et retrouvé après une coupure ; une action refusée faute de réseau est reprise automatiquement au retour de la connexion, sans doublon ; un bandeau indique « connexion perdue » et ce qui est en attente ; un fichier (import CSV, contour) se téléverse par morceaux ; les listes déjà affichées restent lisibles. Il n'y a pas de saisie web entièrement hors ligne. Le CS et le SE enregistrent les plannings au bureau. Sur le terrain, le technicien travaille sur le mobile, qui embarque une copie de son planning et des activités à exécuter.
