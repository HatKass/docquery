-- Demo seed (local development / public demo only).
--
-- Loaded only when DEMO_SEED_ENABLED=true (the local default); production
-- deployments set it to false so this file is never on Flyway's path.
-- Repeatable and idempotent: it re-runs whenever this file changes and never
-- overwrites changes users made through the app.
--
-- The subjects match the fixed user ids in infra/keycloak/realm/docquery-realm.json,
-- so the demo users land in the demo workspace with the right role on first login.

INSERT INTO app_user (id, subject, username, email, display_name) VALUES
    ('00000000-0000-4000-8000-000000000101', '6f1c1a3e-0c55-4c1b-9d7e-1a2b3c4d0001', 'owner',  'owner@docquery.local',  'Olivia Owner'),
    ('00000000-0000-4000-8000-000000000102', '6f1c1a3e-0c55-4c1b-9d7e-1a2b3c4d0002', 'member', 'member@docquery.local', 'Max Member'),
    ('00000000-0000-4000-8000-000000000103', '6f1c1a3e-0c55-4c1b-9d7e-1a2b3c4d0003', 'viewer', 'viewer@docquery.local', 'Vera Viewer')
ON CONFLICT (subject) DO NOTHING;

INSERT INTO workspace (id, name, created_by) VALUES
    ('00000000-0000-4000-8000-000000000001', 'Demo workspace', '00000000-0000-4000-8000-000000000101')
ON CONFLICT (id) DO NOTHING;

INSERT INTO workspace_member (workspace_id, user_id, role)
SELECT '00000000-0000-4000-8000-000000000001', u.id, m.role
FROM (VALUES ('6f1c1a3e-0c55-4c1b-9d7e-1a2b3c4d0001', 'OWNER'),
             ('6f1c1a3e-0c55-4c1b-9d7e-1a2b3c4d0002', 'MEMBER'),
             ('6f1c1a3e-0c55-4c1b-9d7e-1a2b3c4d0003', 'VIEWER')) AS m (subject, role)
JOIN app_user u ON u.subject = m.subject
ON CONFLICT (workspace_id, user_id) DO NOTHING;
