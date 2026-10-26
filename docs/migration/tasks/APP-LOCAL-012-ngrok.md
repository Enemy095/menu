# APP-LOCAL-012: Manual Windows public tunnel launcher

The owner requested ngrok sharing of the local Docker site and selected manual
one-file restart after reboot. Add start/stop launchers with process ownership
validation, bounded startup, ignored state and generated public URL environment.
Use the installed ngrok agent and existing per-user credentials without copying
secrets into the repository. Disable request inspection and bind the discovery API
to loopback. Reuse only the managed process and refuse occupied inspector ports.

The Docker launcher accepts an explicit public-tunnel environment overlay. The
frontend proxies only published content-addressed public images to the configured
internal asset origin, so external clients never need localhost asset URLs. Backend
Host validation and forwarded-header trust are unchanged. No schema, order, menu
content or OpenAPI change; no real order or Telegram test send.

Validation: frontend lint/typecheck/unit tests, production Docker build/start,
external root/schedule/image access and image-proxy rejection checks; repeat start,
stop and restart. Record actual results after verification.

Results: lint/typecheck and all 83 frontend tests passed; production Docker build
and health checks passed. The HTTPS root and availability return 200, published
logo returns image/png 200 and an unpublished valid hash returns 404. Browser
checks at 1440, 768 and 390 pixels decode all 21 rendered images without localhost
URLs and verify canonical metadata uses the current HTTPS tunnel. Repeat start
reuses the same PID. Stop releases the inspector port while the local site remains
200; restart opens a new tunnel and refreshes the generated environment/link.
Credentials and runtime files remain ignored by both Git and Docker build context.
