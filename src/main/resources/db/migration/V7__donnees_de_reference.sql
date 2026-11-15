-- ============================================================================
-- V7 — Données de référence : profils, permissions, organismes, paramètres
-- ============================================================================
-- Les mots de passe ne sont jamais semés : le premier administrateur est créé
-- au démarrage par l'application à partir de variables d'environnement.
-- ============================================================================

-- Profils. niveau = granularité de l'affectation territoriale ; rang = place dans la hiérarchie
-- (un compte ne gère que des comptes de rang inférieur).
--   AUCUN : pas d'affectation (périmètre = tout l'opérateur, ou national)
INSERT INTO iam.profil (code, libelle, organisme, niveau, max_affectations, createur_profil, rang, ordre) VALUES
  ('AD', 'Administrateur',         true,  'AUCUN',   0,    'AD', 1, 1),
  ('SU', 'Superviseur',            true,  'AUCUN',   0,    'AD', 2, 2),
  ('CD', 'Chef de département',    false, 'AUCUN',   0,    'AD', 2, 3),
  ('CS', 'Chef secteur',           false, 'SECTEUR', 1,    'CD', 3, 4),
  ('CF', 'Contrôleur de saignée',  false, 'LOT',     1,    'CS', 4, 5),
  ('CO', 'Contrôleur ordinaire',   false, 'SECTION', NULL, 'CS', 4, 6),
  ('FS', 'Formateur de saignée',   false, 'LOT',     1,    'CS', 4, 7),
  ('MO', 'Moniteur',               false, 'SECTION', 1,    'CS', 4, 8),
  ('ES', 'Équipe spéciale',        false, 'SECTEUR', 1,    'CS', 4, 9),
  ('SE', 'Secrétaire',             false, 'SECTEUR', 1,    'CS', 4, 10);

INSERT INTO iam.permission (code, domaine, libelle) VALUES
  ('GERER_REFERENTIEL',      'ADMINISTRATION', 'Gérer opérateurs, secteurs, lots, contrats, attributions et exercices'),
  ('GERER_PERMISSIONS',      'ADMINISTRATION', 'Ajuster les permissions de chaque profil'),
  ('CREER_COMPTE',           'COMPTE',         'Créer un compte dans sa limite hiérarchique'),
  ('GERER_COMPTE',           'COMPTE',         'Modifier, désactiver, réactiver un compte de son périmètre'),
  ('IMPORTER_COMPTES',       'COMPTE',         'Importer des comptes par fichier CSV'),
  ('CHANGER_PROFIL',         'COMPTE',         'Promouvoir, rétrograder ou changer le profil d''un compte'),
  ('REPRENDRE_COMPTE',       'COMPTE',         'Reprendre le travail d''un compte qui part vers un autre compte'),
  ('CONSULTER_TOUS_SECTEURS','TERRITOIRE',     'Consulter tous les secteurs de son opérateur'),
  ('AFFECTER_SECTEUR',       'TERRITOIRE',     'Affecter un secteur à un chef secteur'),
  ('GERER_SECTION',          'TERRITOIRE',     'Créer et modifier les sections de son secteur'),
  ('AFFECTER_TECHNICIEN',    'TERRITOIRE',     'Affecter les techniciens à leurs lots et sections'),
  ('CONSULTER_PLANNING',     'PLANNING',       'Consulter le planning d''activité'),
  ('ENREGISTRER_PLANNING',   'PLANNING',       'Enregistrer un planning d''activité (le sien, ou celui des techniciens pour le secrétaire)'),
  ('VALIDER_PLANNING',       'PLANNING',       'Valider le planning d''activité de ses collaborateurs d''encadrement'),
  ('CONSULTER_ACTIVITE',     'ACTIVITE',       'Consulter les activités de son périmètre'),
  ('REALISER_ACTIVITE',      'ACTIVITE',       'Réaliser une activité de terrain'),
  ('CONTROLER_ACTIVITE',     'ACTIVITE',       'Contrôler une activité'),
  ('CONSULTER_DASHBOARD',    'ANALYSE',        'Consulter le tableau de bord'),
  ('CONSULTER_REPORTING',    'ANALYSE',        'Consulter les bilans et rapports'),
  ('VISUALISER_CARTE',       'ANALYSE',        'Visualiser la carte'),
  ('CONSULTER_TRACABILITE',  'TRACABILITE',    'Consulter la piste d''audit sur son périmètre'),
  ('EXPORTER_TRACABILITE',   'TRACABILITE',    'Exporter un extrait de la piste d''audit');

-- Base de départ validée avec le client (cahier des charges, section 4 ; traçabilité : section 7).
-- Les administrateurs pourront ajuster ces droits depuis l'application.
INSERT INTO iam.profil_permission (profil, permission) VALUES
  ('AD','GERER_REFERENTIEL'), ('AD','GERER_PERMISSIONS'), ('AD','CREER_COMPTE'), ('AD','GERER_COMPTE'),
  ('AD','CHANGER_PROFIL'), ('AD','REPRENDRE_COMPTE'),
  ('AD','CONSULTER_DASHBOARD'), ('AD','CONSULTER_REPORTING'), ('AD','VISUALISER_CARTE'),
  ('AD','CONSULTER_TRACABILITE'), ('AD','EXPORTER_TRACABILITE'),
  ('SU','CONSULTER_ACTIVITE'), ('SU','CONSULTER_DASHBOARD'), ('SU','CONSULTER_REPORTING'),
  ('SU','VISUALISER_CARTE'), ('SU','CONSULTER_TRACABILITE'),
  ('CD','CREER_COMPTE'), ('CD','GERER_COMPTE'), ('CD','CHANGER_PROFIL'), ('CD','REPRENDRE_COMPTE'),
  ('CD','CONSULTER_TOUS_SECTEURS'), ('CD','AFFECTER_SECTEUR'), ('CD','CONSULTER_ACTIVITE'),
  ('CD','CONSULTER_DASHBOARD'), ('CD','CONSULTER_REPORTING'), ('CD','VISUALISER_CARTE'), ('CD','CONSULTER_TRACABILITE'),
  ('CS','CREER_COMPTE'), ('CS','GERER_COMPTE'), ('CS','IMPORTER_COMPTES'), ('CS','CHANGER_PROFIL'), ('CS','REPRENDRE_COMPTE'),
  ('CS','GERER_SECTION'), ('CS','AFFECTER_TECHNICIEN'),
  ('CS','CONSULTER_PLANNING'), ('CS','ENREGISTRER_PLANNING'), ('CS','VALIDER_PLANNING'),
  ('CS','CONSULTER_ACTIVITE'), ('CS','REALISER_ACTIVITE'), ('CS','CONTROLER_ACTIVITE'),
  ('CS','CONSULTER_DASHBOARD'), ('CS','CONSULTER_REPORTING'), ('CS','VISUALISER_CARTE'), ('CS','CONSULTER_TRACABILITE'),
  ('CF','CONSULTER_PLANNING'), ('CF','ENREGISTRER_PLANNING'),
  ('CF','CONSULTER_ACTIVITE'), ('CF','REALISER_ACTIVITE'), ('CF','CONTROLER_ACTIVITE'),
  ('CF','CONSULTER_DASHBOARD'), ('CF','VISUALISER_CARTE'),
  ('CO','CONSULTER_PLANNING'), ('CO','ENREGISTRER_PLANNING'),
  ('CO','CONSULTER_ACTIVITE'), ('CO','REALISER_ACTIVITE'), ('CO','CONTROLER_ACTIVITE'),
  ('CO','CONSULTER_DASHBOARD'), ('CO','VISUALISER_CARTE'),
  ('FS','CONSULTER_PLANNING'), ('FS','ENREGISTRER_PLANNING'),
  ('FS','CONSULTER_ACTIVITE'), ('FS','REALISER_ACTIVITE'),
  ('FS','CONSULTER_DASHBOARD'), ('FS','VISUALISER_CARTE'),
  ('MO','CONSULTER_PLANNING'), ('MO','ENREGISTRER_PLANNING'),
  ('MO','CONSULTER_ACTIVITE'), ('MO','REALISER_ACTIVITE'),
  ('MO','CONSULTER_DASHBOARD'), ('MO','VISUALISER_CARTE'),
  ('ES','CONSULTER_PLANNING'), ('ES','ENREGISTRER_PLANNING'),
  ('ES','CONSULTER_ACTIVITE'), ('ES','REALISER_ACTIVITE'),
  ('ES','CONSULTER_DASHBOARD'), ('ES','VISUALISER_CARTE'),
  ('SE','CONSULTER_PLANNING'), ('SE','ENREGISTRER_PLANNING'),
  ('SE','CONSULTER_ACTIVITE'),
  ('SE','CONSULTER_DASHBOARD'), ('SE','VISUALISER_CARTE');

-- Les deux organismes. Les opérateurs techniques sont créés par les administrateurs dans l'application.
INSERT INTO referentiel.operateur (type, acronyme, denomination) VALUES
  ('ORGANISME', 'APROMAC', 'APROMAC'),
  ('ORGANISME', 'FIRCA',   'FIRCA');

-- Paramètres modifiables par les administrateurs, sans migration.
INSERT INTO util.parametre (cle, valeur, description) VALUES
  ('contrat.duree_annees', '3', 'Durée d''un contrat d''encadrement technique, en années'),
  ('compte.delai_synchronisation_jours', '30',
     'Jours pendant lesquels un compte désactivé peut encore envoyer ses saisies du mobile (envoi seul)'),
  ('audit.conservation_mois', '36',
     'Mois de piste d''audit en consultation directe ; au-delà, les mois sont archivés, jamais détruits');
