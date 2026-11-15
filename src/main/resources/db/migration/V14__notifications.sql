-- ============================================================================
-- V14 — Notifications (créées par le serveur, lues par leur destinataire)
-- ============================================================================
-- Créées comme effet d'une action : décision sur un planning ou une activité, soumission par la secrétaire.
-- Pas d'audit (volumétrie, aucune valeur de traçabilité métier) ; pas de suppression : on marque « lue ».
CREATE TABLE iam.notification (
    id          uuid        PRIMARY KEY DEFAULT util.uuid_v7(),
    compte_id   uuid        NOT NULL REFERENCES iam.compte (id),
    type        varchar(40) NOT NULL,                       -- PLANNING_VALIDE, PLANNING_REJETE, ACTIVITE_VALIDEE…
    titre       varchar(150) NOT NULL,
    message     text        NOT NULL,
    ton         text        NOT NULL DEFAULT 'info' CHECK (ton IN ('info', 'success', 'warning', 'error')),
    objet_id    uuid,                                       -- le planning concerné
    lue         boolean     NOT NULL DEFAULT false,
    lue_le      timestamptz,
    cree_le     timestamptz NOT NULL DEFAULT now(),
    CHECK (lue = (lue_le IS NOT NULL))
);
CREATE INDEX ix_notification_compte ON iam.notification (compte_id, cree_le DESC);
CREATE INDEX ix_notification_non_lues ON iam.notification (compte_id) WHERE NOT lue;
