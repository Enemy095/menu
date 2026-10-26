\set ON_ERROR_STOP on
BEGIN;
SELECT pg_advisory_xact_lock(1835363957, 1);
\ir is-empty.sql
\if :bootstrap_empty
    DO $$
    BEGIN
        IF (SELECT version FROM public.flyway_schema_history
            WHERE success AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1)
                IS DISTINCT FROM '10' THEN
            RAISE EXCEPTION 'LOCAL_BOOTSTRAP_SCHEMA_MISMATCH: expected Flyway V10';
        END IF;
    END $$;
    \ir v1/content.sql
    \echo LOCAL_BOOTSTRAP_CREATED: published local configuration and menu
\else
    \echo LOCAL_BOOTSTRAP_SKIPPED: database already contains application data
\endif
\ir v2/update.sql
\ir v3/update.sql
\ir v4/update.sql
\ir v5/update.sql
\ir v6/update.sql
\ir v7/update.sql
COMMIT;
