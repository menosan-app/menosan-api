# API contract changelog

Every change to `docs/api-contract.md` gets a line here (newest first). After the Android beta (2026-09-26), changes must be additive unless a human approves a breaking change (plan §8.4).

| Date | Version | Change | Breaking? | Android issue |
|---|---|---|---|---|
| 2026-09-23 | v1 | Clarification §5.10 (BE-5): request and response shapes of the staging-only `/internal/dev/...` tools (`clock`, `seed-history`, `reset`, `reports/generate`). `reset` is new and internal. Nothing Android calls changed. | No | — (not used by the app) |
| 2026-09-23 | v1 | Clarification §7 (BE-2): `POST /v1/photo-analysis` request and response details, JPEG check by bytes, 413 limits, `RATE_LIMITED` details `{limit, resetsAt}` and what counts, `415` for a non-multipart body. No shape change to §2. | No | To open (label `contract-change`) |
| 2026-09-23 | v1 | Clarification §5.9 (BE-4): adoption validation, 404/409 order, idempotency, 1–9 ids, all-or-nothing. No shape change. | No | To open (label `contract-change`) |
| 2026-09-23 | v1 | BE-1 clarifications §6 (additive): entry object incl. `editable`/`updatedAt`, `GET /v1/entries` wrapper `{weekStart, weekEnd, editable, entries}`, PUT/DELETE error details, unchanged-replay rule, sync result shape `{id, op, status, entry, message}` and 500-item limit, export document shape, `DELETE /v1/account` retry semantics. | No (fills gaps before Android starts) | — |
| 2026-09-23 | v1 | Clarifications §5.7 (report payload details: row shapes of `comparison.categories`/`subcategories`, ordering, rounding, `isLatest`, 400 for a bad `weekStart`, regeneration and `revision`) and §5.8 (job endpoint). No shape changes to §3 (BE-3). | No | — |
| 2026-09-23 | v1 | Initial contract frozen from plan §8 (BE-0). Adds clarifications §5: status per error code, `/health` body, taxonomy includes `timezone`, `POST /v1/account` validation, instant precision, `X-Request-Id`. | — | — |
