# Decisions log

Record every deviation from `docs/DEVELOPMENT_PLAN.md` here. The team uses this file to update the SRS/SDP after release.

| Date | Workstream | Decision | Reason |
|---|---|---|---|
| 2026-09-23 | BE-0 | `.gitignore` no longer ignores `AGENTS.md`, `CLAUDE.md`, and `docs/`. They are committed. | Human decision. Handoffs, the contract, and decisions must be shared with parallel agents and the Android team. |
| 2026-09-23 | BE-0 | Library versions: Kotlin 2.4.20, Ktor 3.6.0, Exposed 1.5.0 (`org.jetbrains.exposed.v1.*`, `javaUUID` columns), HikariCP 7.1.0, Flyway 13.7.0, firebase-admin 9.10.0, google-genai 1.72.0, JUnit 6.1.3, Gradle 9.6.0 (JDK 21 via the foojay toolchain resolver). | Latest stable on Maven Central at BE-0 time. |
| 2026-09-23 | BE-0 | Manual dependency wiring (`AppDeps`) instead of a DI framework. | Keeps dependencies minimal (plan §14). Tests pass fakes directly. |
| 2026-09-23 | BE-0 | `DATABASE_USER`/`DATABASE_PASSWORD` are optional. The Neon JDBC URLs may carry `user`/`password` query params instead. | The team's `.env` already embeds credentials in the URLs. |
| 2026-09-23 | BE-0 | `CLOCK_OVERRIDE` sets "now" and the clock keeps ticking from it (offset, not frozen). It is ignored in prod. An unparseable value is ignored with a startup warning. | A frozen clock breaks the "≤ 5 min in the future" check and makes staging feel broken. Fail-soft keeps local runs booting. |
| 2026-09-23 | BE-0 | `VALIDATION_FAILED` uses HTTP 400 (415 for a wrong content type). Full per-code status table is in `api-contract.md` §5.1. | The plan lists codes but not statuses. Clients switch on `error.code`. |
| 2026-09-23 | BE-0 | `POST /v1/account` without `consent: true` returns `400 VALIDATION_FAILED` with `details.field = "consent"`. The account email and name come only from the verified token. | The plan says "requires consent" but gives no error. |
| 2026-09-23 | BE-0 | `GET /v1/taxonomy` returns `taxonomy.json` as-is, including the top-level `timezone`. | Android bundles an identical copy, so one shape is simpler. The extra field is additive. |
| 2026-09-23 | BE-0 | Unknown paths return `404 NOT_FOUND` in the §8.1 error body. A wrong method on a known path also returns `404` (not `405`). | A catch-all route is simpler and avoids StatusPages rewriting deliberate 404 bodies. |
| 2026-09-23 | BE-0 | Auth is a route-scoped plugin: `authenticated(verifier, users) { … }` (requires an account) and `authenticated(…, requireAccount = false)` (only `POST /v1/account`). Handlers read `call.principal` / `call.account`. | One way to guard routes. Forgetting it fails loudly (`error(...)`) instead of leaking data. |
| 2026-09-23 | BE-0 | `X-Request-Id`: a valid client-sent id (1–64 of `[A-Za-z0-9_-]`) is echoed, anything else is replaced, and requests are never rejected over it. Call logs contain only method, path (no query), status, duration, and request id. | Traceability without logging user data. |
| 2026-09-23 | BE-0 | `V2__seed_taxonomy.sql` is generated from `taxonomy.json` (v1) by `TaxonomySeedTest`, which fails if they drift. Later taxonomy changes need a new migration. | Single source of truth (plan §3) with append-only migrations. |
| 2026-09-23 | BE-0 | Tests use embedded PostgreSQL 17 (`io.zonky.test:embedded-postgres`, test-only) for migration and repository tests. | Real SQL coverage in CI without Docker or secrets. PG 17 matches Neon's default. |
| 2026-09-23 | BE-0 | Stubs: `StubReportService` and `StubInterventionEngine` are no-ops (return null/0/empty). `StubEntryRepository`, `StubPhotoAnalyzer`, and `StubGeminiClient` throw (501 / `GeminiException`). | Lets BE-1 call `onLateEntry` and BE-3 build reports before BE-3/BE-4 land. |
| 2026-09-23 | BE-0 | Docker image defaults to `APP_ENV=prod`. Staging must set `APP_ENV=staging` explicitly. | Safe default: no `.env`, no dev tools, no clock override. |
