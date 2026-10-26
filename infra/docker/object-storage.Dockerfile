# syntax=docker/dockerfile:1
# Keep the existing MinIO releases, using their official GitHub release binaries.
# The old minio/minio and minio/mc Docker Hub repositories are unavailable.
FROM alpine:3.21 AS binaries
ARG TARGETARCH
RUN apk add --no-cache ca-certificates curl
RUN set -eu; \
    case "$TARGETARCH" in \
      amd64) server_sha=53e2a2cb16c5366ea6fbbc479c19ddb4c6a0948273e752f740fb1fbf27bb817c; \
             client_sha=ac90da87a35641be5a0ac75d49de5161ddb47d629b5ba01261b0ae9e00aea15f ;; \
      arm64) server_sha=6c2f3142c94240206123177f4ba1e360daa5d1e0a4962e90757ef4f92c3ab57c; \
             client_sha=61bb88e7435919834478ddd4d405a6de6d2c227079da5e8ee9655147398819a0 ;; \
      *) echo "Unsupported MinIO binary architecture: $TARGETARCH" >&2; exit 1 ;; \
    esac; \
    mkdir -p /opt/bin; \
    curl --fail --location --retry 3 \
      "https://github.com/minio/minio/releases/download/RELEASE.2025-04-22T22-12-26Z/minio.linux-$TARGETARCH.RELEASE.2025-04-22T22-12-26Z" \
      --output /opt/bin/minio; \
    echo "$server_sha  /opt/bin/minio" | sha256sum -c -; \
    curl --fail --location --retry 3 \
      "https://github.com/minio/mc/releases/download/RELEASE.2025-04-16T18-13-26Z/mc.linux-$TARGETARCH.RELEASE.2025-04-16T18-13-26Z" \
      --output /opt/bin/mc; \
    echo "$client_sha  /opt/bin/mc" | sha256sum -c -; \
    chmod +x /opt/bin/minio /opt/bin/mc

FROM alpine:3.21 AS server
RUN apk add --no-cache ca-certificates curl
COPY --from=binaries /opt/bin/minio /usr/local/bin/minio
EXPOSE 9000 9001
ENTRYPOINT ["minio"]
CMD ["server", "/data", "--console-address", ":9001"]

FROM alpine:3.21 AS client
RUN apk add --no-cache ca-certificates
COPY --from=binaries /opt/bin/mc /usr/local/bin/mc
ENTRYPOINT ["mc"]

FROM postgres:17.5-alpine3.21 AS bootstrap
COPY --from=binaries /opt/bin/mc /usr/local/bin/mc
WORKDIR /bootstrap
COPY infra/local/bootstrap/ ./
RUN chmod +x bootstrap.sh
ENTRYPOINT ["/bin/sh", "/bootstrap/bootstrap.sh"]
