# API contract changelog

Every change to `docs/api-contract.md` gets a line here (newest first). After the Android beta (2026-09-26), changes must be additive unless a human approves a breaking change (plan §8.4).

| Date | Version | Change | Breaking? | Android issue |
|---|---|---|---|---|
| 2026-09-23 | v1 | Clarifications §5.7 (report payload details: row shapes of `comparison.categories`/`subcategories`, ordering, rounding, `isLatest`, 400 for a bad `weekStart`, regeneration and `revision`) and §5.8 (job endpoint). No shape changes to §3 (BE-3). | No | — |
| 2026-09-23 | v1 | Initial contract frozen from plan §8 (BE-0). Adds clarifications §5: status per error code, `/health` body, taxonomy includes `timezone`, `POST /v1/account` validation, instant precision, `X-Request-Id`. | — | — |
