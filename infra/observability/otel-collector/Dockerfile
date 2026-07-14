FROM otel/opentelemetry-collector-contrib:0.130.0 AS collector

# The upstream collector image is intentionally minimal. Alpine supplies only
# the in-container wget used by the Compose healthcheck.
FROM alpine:3.22.1
COPY --from=collector /otelcol-contrib /otelcol-contrib
USER 10001:10001
ENTRYPOINT ["/otelcol-contrib"]
