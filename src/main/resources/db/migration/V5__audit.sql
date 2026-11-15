-- ============================================================================
-- V5 — Piste d'audit
-- ============================================================================
-- * Table en ajout seul, partitionnée par mois (purge/archivage = détacher une partition).
-- * Alimentée par des triggers : aucune écriture ne peut y échapper, quel que soit le code appelant.
-- * L'application ne reçoit que le droit de lecture (voir V6) ; seule la fonction
--   SECURITY DEFINER ci-dessous écrit dans la table.
-- * Les champs secrets (hash de mot de passe…) et les contours géométriques sont exclus de la capture :
--   on y voit seulement qu'ils ont changé.
-- * Informations de contexte transmises par l'application, par transaction :
--     app.compte_id, app.requete_id, app.adresse_ip, app.motif
-- * Chaque événement porte le compte acteur, son utilisateur et le profil qu'avait le compte à cet
--   instant (un profil peut changer à la promotion).
-- * Les connexions (succès, échecs, verrouillages…) sont tracées par audit.tracer_connexion().
-- ============================================================================

CREATE TABLE audit.evenement (
    id             uuid        NOT NULL DEFAULT util.uuid_v7(),
    survenu_le     timestamptz NOT NULL DEFAULT clock_timestamp(),
    acteur_compte_id      uuid,             -- NULL : traitement système
    acteur_utilisateur_id uuid,
    acteur_profil         char(2),          -- profil du compte au moment de l'action
    requete_id     text,
    adresse_ip     text,
    motif          text,
    schema_nom     name        NOT NULL,
    table_nom      name        NOT NULL,
    operation      char(1)     NOT NULL CHECK (operation IN ('I', 'U', 'S', 'D')),   -- S = suppression logique
    objet_id       text        NOT NULL,
    anciennes      jsonb,                   -- UPDATE : seulement les colonnes modifiées
    nouvelles      jsonb,
    transaction_id bigint      NOT NULL DEFAULT txid_current(),
    PRIMARY KEY (survenu_le, id)
) PARTITION BY RANGE (survenu_le);

CREATE INDEX ix_audit_objet  ON audit.evenement (table_nom, objet_id, survenu_le DESC);
CREATE INDEX ix_audit_acteur ON audit.evenement (acteur_compte_id, survenu_le DESC) WHERE acteur_compte_id IS NOT NULL;
CREATE INDEX ix_audit_utilisateur ON audit.evenement (acteur_utilisateur_id, survenu_le DESC) WHERE acteur_utilisateur_id IS NOT NULL;

-- Partition de secours : jamais de perte d'événement si le renouvellement des partitions échoue.
CREATE TABLE audit.evenement_defaut PARTITION OF audit.evenement DEFAULT;

-- Crée la partition du mois contenant p_mois (idempotent).
CREATE FUNCTION audit.creer_partition(p_mois date) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public AS $$
DECLARE
  v_debut date := date_trunc('month', p_mois)::date;
  v_fin   date := (date_trunc('month', p_mois) + interval '1 month')::date;
  v_nom   text := format('evenement_%s', to_char(v_debut, 'YYYY_MM'));
BEGIN
  IF to_regclass(format('audit.%I', v_nom)) IS NULL THEN
    EXECUTE format(
      'CREATE TABLE audit.%I PARTITION OF audit.evenement FOR VALUES FROM (%L) TO (%L)',
      v_nom, v_debut, v_fin);
  END IF;
END $$;

-- À appeler chaque jour par une tâche planifiée de l'application : garde N mois d'avance.
CREATE FUNCTION audit.assurer_partitions(p_mois_avance integer DEFAULT 3) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public AS $$
BEGIN
  FOR i IN 0..p_mois_avance LOOP
    PERFORM audit.creer_partition((current_date + make_interval(months => i))::date);
  END LOOP;
END $$;

REVOKE ALL ON FUNCTION audit.creer_partition(date), audit.assurer_partitions(integer) FROM PUBLIC;

SELECT audit.assurer_partitions(12);

-- Capture générique. Arguments du trigger : noms de colonnes à ne jamais journaliser.
CREATE FUNCTION audit.enregistrer() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public AS $$
DECLARE
  -- Colonnes techniques ou à fort volume ignorées : les connexions ont leur propre trace (tracer_connexion).
  v_techniques text[] := ARRAY['modifie_le', 'modifie_par', 'version',
                               'derniere_connexion', 'echecs_connexion', 'verrouille_jusqu_a'];
  v_secrets    text[] := COALESCE(TG_ARGV, ARRAY[]::text[]);
  v_old        jsonb;
  v_new        jsonb;
  v_op         char(1);
  v_anciennes  jsonb;
  v_nouvelles  jsonb;
  v_objet      text;
  v_secret     text;
  v_acteur     uuid := util.acteur();
  v_utilisateur uuid;
  v_profil     char(2);
BEGIN
  IF TG_OP = 'INSERT' THEN
    v_op := 'I';
    v_new := to_jsonb(NEW) - v_secrets;
    v_nouvelles := v_new;
    v_objet := COALESCE(v_new ->> 'id', v_new ->> 'cle', concat_ws(':', v_new ->> 'profil', v_new ->> 'permission'));
  ELSIF TG_OP = 'DELETE' THEN
    v_op := 'D';
    v_old := to_jsonb(OLD) - v_secrets;
    v_anciennes := v_old;
    v_objet := COALESCE(v_old ->> 'id', v_old ->> 'cle', concat_ws(':', v_old ->> 'profil', v_old ->> 'permission'));
  ELSE
    v_old := to_jsonb(OLD) - v_secrets;
    v_new := to_jsonb(NEW) - v_secrets;
    v_objet := COALESCE(v_new ->> 'id', v_new ->> 'cle', concat_ws(':', v_new ->> 'profil', v_new ->> 'permission'));
    v_op := CASE WHEN (v_old ->> 'supprime_le') IS NULL AND (v_new ->> 'supprime_le') IS NOT NULL
                 THEN 'S' ELSE 'U' END;
    SELECT jsonb_object_agg(n.key, n.value), jsonb_object_agg(n.key, v_old -> n.key)
      INTO v_nouvelles, v_anciennes
      FROM jsonb_each(v_new) n
     WHERE v_old -> n.key IS DISTINCT FROM n.value
       AND n.key <> ALL (v_techniques);
    -- Un champ secret modifié est tracé sans sa valeur : on sait qu'il a changé, pas à quoi.
    FOREACH v_secret IN ARRAY v_secrets LOOP   -- sans effet si aucun secret
      IF to_jsonb(OLD) -> v_secret IS DISTINCT FROM to_jsonb(NEW) -> v_secret THEN
        v_nouvelles := COALESCE(v_nouvelles, '{}'::jsonb) || jsonb_build_object(v_secret, 'modifié');
      END IF;
    END LOOP;
    IF v_nouvelles IS NULL THEN
      RETURN NULL;                         -- seules des colonnes techniques ont changé
    END IF;
  END IF;

  IF v_acteur IS NOT NULL THEN
    SELECT c.utilisateur_id, iam.profil_a(c.id, clock_timestamp()) INTO v_utilisateur, v_profil
      FROM iam.compte c WHERE c.id = v_acteur;
  END IF;

  INSERT INTO audit.evenement
         (acteur_compte_id, acteur_utilisateur_id, acteur_profil,
          requete_id, adresse_ip, motif, schema_nom, table_nom, operation, objet_id, anciennes, nouvelles)
  VALUES (v_acteur, v_utilisateur, v_profil,
          nullif(current_setting('app.requete_id', true), ''),
          nullif(current_setting('app.adresse_ip', true), ''),
          nullif(current_setting('app.motif', true), ''),
          TG_TABLE_SCHEMA, TG_TABLE_NAME, v_op, v_objet, v_anciennes, v_nouvelles);
  RETURN NULL;
END $$;

REVOKE ALL ON FUNCTION audit.enregistrer() FROM PUBLIC;

-- Trace d'une connexion ou d'un événement de sécurité. Appelée par l'application, qui ne peut pas
-- écrire directement dans la table. p_compte peut être NULL (matricule inconnu).
-- Résultats : SUCCES, ECHEC, VERROUILLE, DECONNEXION, MOT_DE_PASSE_CHANGE, MOT_DE_PASSE_REINITIALISE, ACTIVATION.
CREATE FUNCTION audit.tracer_connexion(p_compte uuid, p_matricule text, p_resultat text,
                                       p_adresse_ip text DEFAULT NULL, p_appareil text DEFAULT NULL)
RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public AS $$
DECLARE
  v_utilisateur uuid;
  v_profil      char(2);
BEGIN
  IF p_resultat NOT IN ('SUCCES', 'ECHEC', 'VERROUILLE', 'DECONNEXION',
                        'MOT_DE_PASSE_CHANGE', 'MOT_DE_PASSE_REINITIALISE', 'ACTIVATION') THEN
    RAISE EXCEPTION 'Résultat de connexion inconnu : %', p_resultat USING ERRCODE = 'check_violation';
  END IF;
  IF p_compte IS NOT NULL THEN
    SELECT c.utilisateur_id, iam.profil_a(c.id, clock_timestamp()) INTO v_utilisateur, v_profil
      FROM iam.compte c WHERE c.id = p_compte;
  END IF;
  INSERT INTO audit.evenement
         (acteur_compte_id, acteur_utilisateur_id, acteur_profil, requete_id, adresse_ip,
          schema_nom, table_nom, operation, objet_id, nouvelles)
  VALUES (p_compte, v_utilisateur, v_profil, nullif(current_setting('app.requete_id', true), ''),
          coalesce(p_adresse_ip, nullif(current_setting('app.adresse_ip', true), '')),
          'iam', 'connexion', 'I', coalesce(p_compte::text, p_matricule, 'inconnu'),
          jsonb_strip_nulls(jsonb_build_object('resultat', p_resultat, 'matricule', p_matricule, 'appareil', p_appareil)));
END $$;
REVOKE ALL ON FUNCTION audit.tracer_connexion(uuid, text, text, text, text) FROM PUBLIC;

-- Tables auditées. Les historiques en ajout seul (retrait_lot, compte_profil, alias_matricule) ne le sont pas :
-- ils sont eux-mêmes la trace. Les jetons, sessions et lignes d'import sont volontairement exclus
-- (volumétrie élevée, aucune valeur de traçabilité métier).
DO $$
DECLARE
  t text;
  v_tables text[] := ARRAY[
    'referentiel.operateur', 'referentiel.secteur', 'referentiel.lot', 'referentiel.section',
    'gestion.contrat', 'gestion.attribution', 'gestion.attribution_lot', 'gestion.exercice',
    'iam.utilisateur', 'iam.compte', 'iam.affectation',
    'iam.reprise_compte', 'iam.identite_externe', 'iam.profil_permission', 'iam.import_compte', 'util.parametre'
  ];
BEGIN
  FOREACH t IN ARRAY v_tables LOOP
    IF t = 'iam.compte' THEN
      EXECUTE format(
        'CREATE TRIGGER trg_audit AFTER INSERT OR UPDATE OR DELETE ON %s
           FOR EACH ROW EXECUTE FUNCTION audit.enregistrer(%L)', t, 'mot_de_passe_hash');
    ELSIF t IN ('referentiel.secteur', 'referentiel.lot', 'referentiel.section') THEN
      -- Contours (colonne geom, ajoutée par V8) : trop volumineux pour la piste d'audit ;
      -- on y lit seulement que le contour a été modifié.
      EXECUTE format(
        'CREATE TRIGGER trg_audit AFTER INSERT OR UPDATE OR DELETE ON %s
           FOR EACH ROW EXECUTE FUNCTION audit.enregistrer(%L)', t, 'geom');
    ELSE
      EXECUTE format(
        'CREATE TRIGGER trg_audit AFTER INSERT OR UPDATE OR DELETE ON %s
           FOR EACH ROW EXECUTE FUNCTION audit.enregistrer()', t);
    END IF;
  END LOOP;
END $$;
