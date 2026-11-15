-- ============================================================================
-- V13 — Traçabilité : l'export est lui-même tracé (TRA-08)
-- ============================================================================
-- L'application ne peut pas écrire dans audit.evenement : comme pour les connexions, une fonction dédiée s'en charge.
-- Acteur = compte connecté (app.compte_id). Les filtres de l'export et le nombre de lignes exportées sont conservés.
CREATE FUNCTION audit.tracer_export(p_filtres jsonb, p_nb integer) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public AS $$
DECLARE
  v_acteur      uuid := nullif(current_setting('app.compte_id', true), '')::uuid;
  v_utilisateur uuid;
  v_profil      char(2);
BEGIN
  IF v_acteur IS NOT NULL THEN
    SELECT c.utilisateur_id, iam.profil_a(c.id, clock_timestamp()) INTO v_utilisateur, v_profil
      FROM iam.compte c WHERE c.id = v_acteur;
  END IF;
  INSERT INTO audit.evenement
         (acteur_compte_id, acteur_utilisateur_id, acteur_profil, requete_id, adresse_ip,
          schema_nom, table_nom, operation, objet_id, nouvelles)
  VALUES (v_acteur, v_utilisateur, v_profil, nullif(current_setting('app.requete_id', true), ''),
          nullif(current_setting('app.adresse_ip', true), ''),
          'audit', 'tracabilite_export', 'I', util.uuid_v7()::text,
          jsonb_build_object('filtres', coalesce(p_filtres, '{}'::jsonb), 'lignes', p_nb));
END $$;
REVOKE ALL ON FUNCTION audit.tracer_export(jsonb, integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION audit.tracer_export(jsonb, integer) TO sysde_app;
