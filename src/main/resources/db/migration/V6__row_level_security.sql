-- V6: Postgres Row-Level Security as a SECOND tenant guard (defence in depth).
--
-- The application already filters every query by the tenant from the JWT. RLS makes the database enforce the same
-- rule, so a future query that forgets "AND tenant_id = ?" returns nothing instead of another tenant's rows.
--
--  * The app connects as a NON-owner, NON-superuser role (${app_user}). Owners and superusers bypass RLS, so the
--    migration owner (who runs Flyway) is a different role.
--  * Every connection checkout sets  app.tenant_id  (TenantAwareDataSource). Policies compare against it.
--  * Unset / empty  -> no rows (fail closed).  '__system__' -> cross-tenant system work (relay, recovery, digest).
--
-- vector_store moves under Flyway ownership here (it was created by Spring AI's initialize-schema before). The app
-- role must not own a table it is policed on: an owner could simply ALTER TABLE ... DISABLE ROW LEVEL SECURITY.

CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS hstore;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- Same DDL Spring AI 1.1.8 PgVectorStore uses (UUID id, json metadata, 1536 dims, HNSW cosine).
CREATE TABLE IF NOT EXISTS vector_store (
    id        uuid DEFAULT uuid_generate_v4() PRIMARY KEY,
    content   text,
    metadata  json,
    embedding vector(1536)
);
CREATE INDEX IF NOT EXISTS spring_ai_vector_index ON vector_store USING HNSW (embedding vector_cosine_ops);

-- Full-text column + indexes (previously added at runtime by TsvectorSchemaInitializer).
ALTER TABLE vector_store
    ADD COLUMN IF NOT EXISTS content_tsv tsvector
    GENERATED ALWAYS AS (to_tsvector('english', coalesce(content, ''))) STORED;
CREATE INDEX IF NOT EXISTS idx_vector_store_content_tsv ON vector_store USING GIN (content_tsv);
CREATE INDEX IF NOT EXISTS idx_vector_store_tenant_break
    ON vector_store ((metadata->>'tenantId'), (metadata->>'breakItemId'));

-- Application login role (created only if missing; in production the DBA usually pre-creates it).
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '${app_user}') THEN
        EXECUTE format('CREATE ROLE %I LOGIN PASSWORD %L NOSUPERUSER NOBYPASSRLS', '${app_user}', '${app_password}');
    END IF;
END $$;

GRANT USAGE ON SCHEMA public TO ${app_user};
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO ${app_user};
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO ${app_user};
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO ${app_user};
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO ${app_user};
-- Flyway bookkeeping is not the app's business.
REVOKE ALL ON flyway_schema_history FROM ${app_user};

-- Policies. One helper expression, applied to every tenant-owned table.
CREATE OR REPLACE FUNCTION rls_tenant_allows(row_tenant text) RETURNS boolean
    LANGUAGE sql STABLE AS $$
    SELECT CASE coalesce(current_setting('app.tenant_id', true), '')
             WHEN '' THEN false                         -- no tenant bound: fail closed
             WHEN '__system__' THEN true                -- explicit system scope
             ELSE row_tenant = current_setting('app.tenant_id', true)
           END
$$;

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['break_item', 'evidence', 'investigation_job', 'resolution_proposal', 'llm_call',
                             'approval_task', 'audit_chain_head', 'audit_event', 'outbox_event']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('DROP POLICY IF EXISTS tenant_isolation ON %I', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (rls_tenant_allows(tenant_id)) '
                       'WITH CHECK (rls_tenant_allows(tenant_id))', t);
    END LOOP;
END $$;

ALTER TABLE vector_store ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON vector_store;
CREATE POLICY tenant_isolation ON vector_store
    USING (rls_tenant_allows(metadata->>'tenantId'))
    WITH CHECK (rls_tenant_allows(metadata->>'tenantId'));
