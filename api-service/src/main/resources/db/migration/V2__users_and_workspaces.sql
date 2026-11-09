-- Phase 2: users, workspaces and per-workspace roles.
--
-- Identity lives in Keycloak; app_user is a local projection keyed by the token's
-- "sub" claim, created just-in-time on a user's first authenticated request.
-- Authorization (who may do what in which workspace) lives here, not in Keycloak,
-- because it is per-tenant business data that owners manage from the app.

CREATE TABLE app_user (
    id            uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    subject       text        NOT NULL UNIQUE,          -- OIDC "sub" (stable Keycloak user id)
    username      text        NOT NULL,                 -- "preferred_username" (may change in Keycloak)
    email         text,
    display_name  text        NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    last_seen_at  timestamptz NOT NULL DEFAULT now()
);

-- Usernames and e-mails are looked up case-insensitively when an owner invites someone.
-- Not unique: Keycloak guarantees uniqueness at any point in time, but a local row can be
-- stale (a user renamed in Keycloak who has not logged in since).
CREATE INDEX app_user_username_lower_idx ON app_user (lower(username));
CREATE INDEX app_user_email_lower_idx ON app_user (lower(email));

CREATE TABLE workspace (
    id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    name        text        NOT NULL CHECK (length(btrim(name)) BETWEEN 1 AND 100),
    created_by  uuid        REFERENCES app_user (id) ON DELETE SET NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE workspace_member (
    workspace_id  uuid        NOT NULL REFERENCES workspace (id) ON DELETE CASCADE,
    user_id       uuid        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    role          text        NOT NULL CHECK (role IN ('OWNER', 'MEMBER', 'VIEWER')),
    joined_at     timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (workspace_id, user_id)
);

-- "Which workspaces can this user see?" is the hottest authorization query.
CREATE INDEX workspace_member_user_idx ON workspace_member (user_id);
