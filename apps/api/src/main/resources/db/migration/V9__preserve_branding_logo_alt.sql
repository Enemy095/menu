-- Preserve the operator-approved accessible logo text independently from the display name.
ALTER TABLE site_branding
    ADD COLUMN logo_alt varchar(160);

UPDATE site_branding
SET logo_alt = display_name
WHERE logo_alt IS NULL;

ALTER TABLE site_branding
    ADD CONSTRAINT ck_site_branding_logo_alt
    CHECK (logo_alt IS NULL OR length(btrim(logo_alt)) > 0);
