# API contract changelog

Every change to `docs/api-contract.md` gets a line here (newest first). After the Android beta (2026-09-26), changes must be additive unless a human approves a breaking change (plan §8.4).

| Date | Version | Change | Breaking? | Android issue |
|---|---|---|---|---|
| 2026-09-23 | v1 | BE-1 clarifications §6 (additive): entry object incl. `editable`/`updatedAt`, `GET /v1/entries` wrapper `{weekStart, weekEnd, editable, entries}`, PUT/DELETE error details, unchanged-replay rule, sync result shape `{id, op, status, entry, message}` and 500-item limit, export document shape, `DELETE /v1/account` retry semantics. | No (fills gaps before Android starts) | — |
| 2026-09-23 | v1 | Initial contract frozen from plan §8 (BE-0). Adds clarifications §5: status per error code, `/health` body, taxonomy includes `timezone`, `POST /v1/account` validation, instant precision, `X-Request-Id`. | — | — |
