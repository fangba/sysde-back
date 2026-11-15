-- ============================================================================
-- V9 — Secteurs et lots (liste native fournie par l'organisme : sysde_secteur_lot.xlsx)
-- ============================================================================
-- * secteur.code = numéro officiel du secteur sur deux chiffres (01 à 18 ; Bondoukou, numéroté 19 dans la liste d'origine, est corrigé en 18 : le 18 avait été oublié).
-- * lot.numero   = numéro officiel du lot (1 à 55, continu sur l'ensemble des secteurs).
-- * lot.description = zones couvertes par le lot.
-- Les contours (geom) seront importés plus tard depuis les fichiers shapefile / GeoJSON.
-- ============================================================================

WITH s AS (INSERT INTO referentiel.secteur (code, nom) VALUES ('01', 'DABOU') RETURNING id)
INSERT INTO referentiel.lot (secteur_id, numero, description)
SELECT s.id, v.numero, v.description FROM s, (VALUES
  (1, 'Dabou communes et préfectures'),
  (2, 'Toupa - Irah'),
  (3, 'Irobo - Tamabo')
) AS v(numero, description);

WITH s AS (INSERT INTO referentiel.secteur (code, nom) VALUES ('02', 'GRAND LAHOU') RETURNING id)
INSERT INTO referentiel.lot (secteur_id, numero, description)
SELECT s.id, v.numero, v.description FROM s, (VALUES
  (4, 'Grand Lahou'),
  (5, 'Yocoboué - Gô'),
  (6, 'Fresco')
) AS v(numero, description);

WITH s AS (INSERT INTO referentiel.secteur (code, nom) VALUES ('03', 'ANGUEDEDOU') RETURNING id)
INSERT INTO referentiel.lot (secteur_id, numero, description)
SELECT s.id, v.numero, v.description FROM s, (VALUES
  (7, 'Anguédédou (S/P Songon)'),
  (8, 'Bingerville'),
  (9, 'Anyama - Azaguié - Agboville')
) AS v(numero, description);

WITH s AS (INSERT INTO referentiel.secteur (code, nom) VALUES ('04', 'BONOUA') RETURNING id)
INSERT INTO referentiel.lot (secteur_id, numero, description)
SELECT s.id, v.numero, v.description FROM s, (VALUES
  (10, 'Grand Bassam - Bonoua'),
  (11, 'Samo - Akroaba - Adiaké'),
  (12, 'Bongo - Alépé')
) AS v(numero, description);

WITH s AS (INSERT INTO referentiel.secteur (code, nom) VALUES ('05', 'GAGNOA') RETURNING id)
INSERT INTO referentiel.lot (secteur_id, numero, description)
SELECT s.id, v.numero, v.description FROM s, (VALUES
  (13, 'Gagnoa - Guibéroua'),
  (14, 'Ouragahio - Sinfra'),
  (15, 'Divo - Lakota'),
  (16, 'Oumé - Diégonéfla')
) AS v(numero, description);

WITH s AS (INSERT INTO referentiel.secteur (code, nom) VALUES ('06', 'SOUBRE') RETURNING id)
INSERT INTO referentiel.lot (secteur_id, numero, description)
SELECT s.id, v.numero, v.description FROM s, (VALUES
  (17, 'Soubré - Gueyo'),
  (18, 'Yabayo - Buyo-Grand Zatry'),
  (19, 'Touih - Méagui')
) AS v(numero, description);

WITH s AS (INSERT INTO referentiel.secteur (code, nom) VALUES ('07', 'SAN PEDRO') RETURNING id)
INSERT INTO referentiel.lot (secteur_id, numero, description)
SELECT s.id, v.numero, v.description FROM s, (VALUES
  (20, 'San Pedro - Nonoua'),
  (21, 'Gabiadji - Gnity'),
  (22, 'Sassandra - Sago')
) AS v(numero, description);

WITH s AS (INSERT INTO referentiel.secteur (code, nom) VALUES ('08', 'GRAND BEREBY') RETURNING id)
INSERT INTO referentiel.lot (secteur_id, numero, description)
SELECT s.id, v.numero, v.description FROM s, (VALUES
  (23, 'Grand Béréby et S/P'),
  (24, 'Tabou - Grabo'),
  (25, 'Néka - Djiroutou')
) AS v(numero, description);

WITH s AS (INSERT INTO referentiel.secteur (code, nom) VALUES ('09', 'GUIGLO') RETURNING id)
INSERT INTO referentiel.lot (secteur_id, numero, description)
SELECT s.id, v.numero, v.description FROM s, (VALUES
  (26, 'Guiglo'),
  (27, 'Zagné - Taï'),
  (28, 'Duékoué - Dibobly - Guezon - Gbapleu'),
  (29, 'Blolequin - Toulepleu')
) AS v(numero, description);

WITH s AS (INSERT INTO referentiel.secteur (code, nom) VALUES ('10', 'TIASSALE') RETURNING id)
INSERT INTO referentiel.lot (secteur_id, numero, description)
SELECT s.id, v.numero, v.description FROM s, (VALUES
  (30, 'Sikensi - N''douci - N''zianouan'),
  (31, 'Tiassalé - Hermankono - Blé')
) AS v(numero, description);

WITH s AS (INSERT INTO referentiel.secteur (code, nom) VALUES ('11', 'BETTIE') RETURNING id)
INSERT INTO referentiel.lot (secteur_id, numero, description)
SELECT s.id, v.numero, v.description FROM s, (VALUES
  (32, 'Bettié - Ebilassokro - Zaranou'),
  (33, 'Yakassé-Attobrou'),
  (34, 'Adzopé - Akoupé')
) AS v(numero, description);

WITH s AS (INSERT INTO referentiel.secteur (code, nom) VALUES ('12', 'DAOUKRO') RETURNING id)
INSERT INTO referentiel.lot (secteur_id, numero, description)
SELECT s.id, v.numero, v.description FROM s, (VALUES
  (35, 'Daoukro commune et sous-préfectures'),
  (36, 'M''bahiakro - Prikro'),
  (37, 'Dimbokro - Bocanda'),
  (38, 'Ouellé - Ettrokro'),
  (39, 'Bongouanou - Arrah')
) AS v(numero, description);

WITH s AS (INSERT INTO referentiel.secteur (code, nom) VALUES ('13', 'ABOISSO') RETURNING id)
INSERT INTO referentiel.lot (secteur_id, numero, description)
SELECT s.id, v.numero, v.description FROM s, (VALUES
  (40, 'N''zikro - Aboisso - Ayamé'),
  (41, 'Maféré - Noé')
) AS v(numero, description);

WITH s AS (INSERT INTO referentiel.secteur (code, nom) VALUES ('14', 'DALOA') RETURNING id)
INSERT INTO referentiel.lot (secteur_id, numero, description)
SELECT s.id, v.numero, v.description FROM s, (VALUES
  (42, 'Daloa - Vavoua'),
  (43, 'Issia - Guessabo')
) AS v(numero, description);

WITH s AS (INSERT INTO referentiel.secteur (code, nom) VALUES ('15', 'ABENGOUROU') RETURNING id)
INSERT INTO referentiel.lot (secteur_id, numero, description)
SELECT s.id, v.numero, v.description FROM s, (VALUES
  (44, 'Amélékia - Zinzénou - Agnibilékro'),
  (45, 'Abengourou - Niablé'),
  (46, 'Anuassué - Bonahouin')
) AS v(numero, description);

WITH s AS (INSERT INTO referentiel.secteur (code, nom) VALUES ('16', 'MAN') RETURNING id)
INSERT INTO referentiel.lot (secteur_id, numero, description)
SELECT s.id, v.numero, v.description FROM s, (VALUES
  (47, 'Danané - Zouan Hounien'),
  (48, 'Man - Bangolo'),
  (49, 'Biankouma - Touba')
) AS v(numero, description);

WITH s AS (INSERT INTO referentiel.secteur (code, nom) VALUES ('17', 'YAMOUSSOUKRO') RETURNING id)
INSERT INTO referentiel.lot (secteur_id, numero, description)
SELECT s.id, v.numero, v.description FROM s, (VALUES
  (50, 'Taabo - Toumodi'),
  (51, 'Yamoussoukro - Tiébissou - Didiévi'),
  (52, 'Bouaflé - Zuénoula')
) AS v(numero, description);

WITH s AS (INSERT INTO referentiel.secteur (code, nom) VALUES ('18', 'BONDOUKOU') RETURNING id)
INSERT INTO referentiel.lot (secteur_id, numero, description)
SELECT s.id, v.numero, v.description FROM s, (VALUES
  (53, 'Koun-Fao - Tanda'),
  (54, 'Bondoukou'),
  (55, 'Sandégué - Nassian')
) AS v(numero, description);
