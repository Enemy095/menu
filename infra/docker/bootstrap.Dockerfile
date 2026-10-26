# syntax=docker/dockerfile:1
FROM minio/mc:RELEASE.2025-04-16T18-13-26Z AS object-client
FROM postgres:17.5-alpine3.21
COPY --from=object-client /usr/bin/mc /usr/local/bin/mc
WORKDIR /bootstrap
COPY infra/local/bootstrap/ ./
RUN chmod +x bootstrap.sh
ENTRYPOINT ["/bin/sh", "/bootstrap/bootstrap.sh"]
