CREATE TABLE restaurant_closed_dates (
    closed_date date PRIMARY KEY,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE restaurant_weekly_closed_days (
    iso_day smallint PRIMARY KEY CHECK (iso_day BETWEEN 1 AND 7)
);

CREATE TABLE telegram_calendar_poll_state (
    singleton_id smallint PRIMARY KEY CHECK (singleton_id = 1),
    last_update_id bigint NOT NULL CHECK (last_update_id >= 0)
);

CREATE TABLE site_content_releases (
    release_id varchar(80) PRIMARY KEY,
    applied_at timestamptz NOT NULL DEFAULT now()
);

DO $$
DECLARE
    runtime_role text := current_setting('app.sc_be_001_runtime_role', true);
    object_name text;
BEGIN
    IF runtime_role IS NULL THEN RETURN; END IF;
    FOREACH object_name IN ARRAY ARRAY['restaurant_closed_dates', 'restaurant_weekly_closed_days', 'telegram_calendar_poll_state'] LOOP
        EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE %I.%I TO %I', current_schema(), object_name, runtime_role);
    END LOOP;
    EXECUTE format('GRANT SELECT ON TABLE %I.site_content_releases TO %I', current_schema(), runtime_role);
END $$;
