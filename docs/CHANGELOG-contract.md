# API contract changelog

Every change to `docs/api-contract.md` gets a line here (newest first). After the Android beta (2026-09-26), changes must be additive unless a human approves a breaking change (plan §8.4).

| Date | Version | Change | Breaking? | Android issue |
|---|---|---|---|---|
| 2026-09-23 | v1 | Clarification §5.7 (BE-4): adoption validation, 404/409 order, idempotency, 1–9 ids, all-or-nothing. No shape change. | No | To open (label `contract-change`) |
| 2026-09-23 | v1 | Initial contract frozen from plan §8 (BE-0). Adds clarifications §5: status per error code, `/health` body, taxonomy includes `timezone`, `POST /v1/account` validation, instant precision, `X-Request-Id`. | — | — |
