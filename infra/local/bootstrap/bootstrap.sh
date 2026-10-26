#!/bin/sh
set -eu

case "${LOCAL_BOOTSTRAP_ENABLED:-true}" in
    false) echo 'LOCAL_BOOTSTRAP_DISABLED'; exit 0 ;;
    true) ;;
    *) echo 'LOCAL_BOOTSTRAP_ENABLED must be true or false' >&2; exit 1 ;;
esac

cd /bootstrap/v1
sha256sum -c checksums.sha256
cd /bootstrap/v2
sha256sum -c checksums.sha256
cd /bootstrap/v3
sha256sum -c checksums.sha256
cd /bootstrap/v4
sha256sum -c checksums.sha256
cd /bootstrap/v5
sha256sum -c checksums.sha256
cd /bootstrap/v6
sha256sum -c checksums.sha256
cd /bootstrap/v7
sha256sum -c checksums.sha256
cd /bootstrap/v1
# Check before copying any objects. Existing installations keep their own content.
empty=$(psql -X -v ON_ERROR_STOP=1 -At -f /bootstrap/is-empty.sql -c '\echo :bootstrap_empty')
if [ "$empty" != "t" ]; then
    # apply.sql rechecks the database and applies only pending explicit content releases.
    psql -X -v ON_ERROR_STOP=1 -f /bootstrap/apply.sql
    exit 0
fi

mc alias set destination http://minio:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null
# All bundle asset paths are validated when exporting and verified by checksums.
find assets -type f | sort | while IFS= read -r key; do
    expected=$(sha256sum "$key" | cut -d ' ' -f 1)
    target="destination/$MINIO_BUCKET/$key"
    if mc stat --json "$target" > /tmp/bootstrap-stat.json 2>&1; then
        : # Existing immutable object: verify below without overwriting it.
    elif grep -Fq '"message":"Object does not exist"' /tmp/bootstrap-stat.json; then
        # Match the pinned mc client's explicit missing-object result only.
        mc cp --quiet "$key" "$target"
    else
        echo 'LOCAL_BOOTSTRAP_STORAGE_ERROR: object metadata could not be checked' >&2
        exit 1
    fi
    mc cat "$target" > /tmp/bootstrap-object
    actual=$(sha256sum /tmp/bootstrap-object | cut -d ' ' -f 1)
    if [ "$expected" != "$actual" ]; then
        echo 'LOCAL_BOOTSTRAP_ASSET_CONFLICT: content-addressed object differs' >&2
        exit 1
    fi
done

# Recheck under a transaction lock. A second initializer can only skip, never
# duplicate or overwrite an already committed content graph.
psql -X -v ON_ERROR_STOP=1 -f /bootstrap/apply.sql
