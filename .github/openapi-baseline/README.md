# OpenAPI compatibility baseline

`ci.yml` looks for a released contract at `openapi.yaml` in this directory. Until the first public API release, that file intentionally does not exist: lint, semantic tests, deterministic generation, type checking, and build gates run, but there is no honest historical contract to compare.

At the first API release, copy the exact released `packages/api-contract/openapi/openapi.yaml` here in the release commit. From then on, CI runs pinned `oasdiff` and rejects definite backward-incompatible changes. Update the baseline only as part of an explicitly versioned API release; never copy the current branch spec merely to make CI green.
