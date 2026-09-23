# Handoff log — menosan-api

Newest entry first. Use `docs/HANDOFF_TEMPLATE.md` for each entry. Every agent **reads this file before starting** and **updates it before stopping**.

---

# Handoff — menosan-api — 2026-09-23 09:05 PHT

## 1. Session
- **Agent / model:** Claude Code (Opus 5.5, `claude-opus-5-5`)
- **Workstream(s):** BE-0 Foundation (docs/DEVELOPMENT_PLAN.md §9)
- **Branch:** `main` (not pushed) · **Last code commit:** `8d60b9c docs: api contract v1, contract changelog, decisions, env example, README`
- **Overall state:** 🟢 BE-0 code complete, 46/46 tests green. **Human-verified 2026-09-23:** `./gradlew run` boots against Neon `dev`, V1+V2 applied, `/health` and `/v1/taxonomy` OK. Still open: a real Firebase token check and the first CI run (see §10).

## 2. Done this session
- [x] Stopped ignoring `AGENTS.md`, `CLAUDE.md`, and `docs/` (human decision). Added `.gitattributes` (LF for `gradlew`). (`04f4b1d`)
- [x] Gradle 9.6.0 wrapper and Kotlin DSL build with a version catalog (`gradle/libs.versions.toml`). JDK 21 toolchain via foojay. Ktor plugin `buildFatJar` → `build/libs/menosan-api.jar`. (`e59a4fc`)
- [x] `AppConfig` + `.env` loader (`config/`). Real env wins, `.env` is skipped in prod, and required vars are reported by name only. (`2dbbb33`, `8dd19cc`)
- [x] `OverridableClock` + `WeekCalc` (Asia/Manila, Sun–Sat) with boundary tests (Sat 15:59:59.999Z vs 16:00Z) and zone-independence tests. `ApiException`/`ErrorCode` (`common/`). (`50dcf19`)
- [x] DB: Hikari (max 5, min idle 0), Flyway on the direct URL, `V1__init.sql` (verbatim §7), `V2__seed_taxonomy.sql` (generated), Exposed objects for all 9 tables (`db/Tables.kt`), `Db.tx {}` helper, `JdbcHealthCheck`. Taxonomy model + validation (`taxonomy/Taxonomy.kt`). (`bf38f95`)
- [x] Plugins: §8.1 error bodies (StatusPages), `X-Request-Id`, call logging (method/path/status/ms only), JSON config, `/health`. (`3613b81`)
- [x] Firebase auth: `TokenVerifier` + `FirebaseTokenVerifier`, route-scoped `authenticated { }` plugin → 401 `UNAUTHENTICATED` / 404 `ACCOUNT_NOT_FOUND`. `GET /v1/me`, idempotent `POST /v1/account` (`INSERT … ON CONFLICT DO NOTHING`). (`1d39ab9`)
- [x] `GET /v1/taxonomy`, `GET /v1/weeks/current`. Stubs: `EntryRepository`, `PhotoAnalyzer`, `ReportService`, `InterventionEngine`, `GeminiClient`. `AppDeps` wiring and `Application.module(deps)`. (`0b06d40`)
- [x] Embedded PostgreSQL 17 integration tests (migrations, seed parity, idempotent account create, cascade delete). (`31182b5`)
- [x] Multi-stage `Dockerfile`, `.dockerignore`, `.github/workflows/ci.yml` (test + fat JAR). (`ddad34d`)
- [x] `docs/api-contract.md` (v1 frozen, §8 plus §5 clarifications), `docs/CHANGELOG-contract.md`, `.env.example`, README, and 15 `docs/DECISIONS.md` lines. (`8d60b9c`)

## 3. In progress (unfinished)
| Item | Where | What's left |
|---|---|---|
| — | — | Nothing half-done in code. |

## 4. Next steps (in order)
Only start these after the human confirms the §10 checks. Parallel agents: one branch or worktree each (`feat/be1-entries`, `feat/be2-photo`, `feat/be3-reports`, `feat/be4-interventions`), merged to `main` one at a time.
1. **BE-1 (entries, sync, export, account deletion):** implement `ExposedEntryRepository` in `entries/` (replace `StubEntryRepository` in `AppDeps` + `main()`). Add routes inside `authenticated(deps.tokenVerifier, deps.users) { … }` in `Application.module`. Use `WeekCalc.weekStart(createdAt)`, `WeekCalc.isCurrentWeek`, and `deps.taxonomy.categoryOf(code)`. Call `deps.reports.onLateEntry(...)` for late creates (the stub is a no-op). For `DELETE /v1/account`, use `FirebaseTokenVerifier.auth.deleteUser(uid)` (consider extracting a small `FirebaseUserAdmin` interface so tests can fake it). Use `PostgresTestDb.db` for isolation, sync, and cascade tests.
2. **BE-2 (photo):** implement a real `GeminiClient` in `common/GeminiClient.kt` (google-genai is already a dependency; `GEMINI_API_KEY`/`GEMINI_MODEL` are in `AppConfig`) and `PhotoAnalyzer` in `photo/`. Multipart `POST /v1/photo-analysis`, never log or store bytes, 30/day rate limit, 15 s timeout.
3. **BE-3 (analytics, reports, jobs):** pure `analytics/` (§5.1–5.4, keep it portable to Android), `docs/analytics-test-vectors.json` (≥14 cases), a real `ReportService`, `GET /v1/reports[/{weekStart}]`, the scheduler, `POST /internal/jobs/weekly-reports` (check `config.jobKey`), and `.github/workflows/weekly-reports.yml` (disabled).
4. **BE-4 (interventions):** `V3__seed_interventions.sql` (§6.4 expanded), a real `InterventionEngine` (rules + Gemini + fallback + `continued`), and adoption endpoints with the I7 window.
5. Then BE-5 (dev tools using `OverridableClock.setOverride`, e2e, deploy).

## 5. Verify the current state
```bash
# Windows without a JDK on PATH (PowerShell): $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
./gradlew test           # 46 tests, 0 failures (embedded Postgres downloads PG17 binaries on first run)
./gradlew buildFatJar    # build/libs/menosan-api.jar
./gradlew run            # needs .env; applies V1+V2 to DATABASE_URL_DIRECT, serves :8080
curl -s localhost:8080/health                 # {"status":"ok","db":"ok"}
curl -s localhost:8080/v1/taxonomy            # version 1, 4 categories, 25 subcategories
curl -si localhost:8080/v1/me                 # 401 UNAUTHENTICATED
```
- Expected: all tests pass. Test classes: `WeekCalcTest`, `AppConfigTest`, `TaxonomyTest`, `TaxonomySeedTest`, `MigrationFilesTest`, `DatabaseIntegrationTest`, `AccountRoutesTest`, `PublicRoutesTest`, `LoggingPrivacyTest`.

## 6. Known issues / failing tests
- None failing.
- Not verified here: `docker build` (Docker isn't installed on this machine; CI builds the JAR, not the image) and GitHub Actions CI (nothing is pushed yet).
- The fat JAR is about 105 MB (firebase-admin + google-genai + flyway). Fine for now.

## 7. Decisions made (also logged in docs/DECISIONS.md)
- `VALIDATION_FAILED` = 400. Full status table is in `api-contract.md` §5.1.
- `CLOCK_OVERRIDE` is an offset clock (keeps ticking). Invalid values are ignored with a warning, and it's ignored in prod.
- Manual DI (`AppDeps`), with no framework.
- `/v1/taxonomy` serves `taxonomy.json` as-is (includes `timezone`).
- Unknown paths and wrong methods → 404 `NOT_FOUND` JSON.
- `StubReportService` and `StubInterventionEngine` are no-ops. The other stubs throw 501.
- Embedded PG17 for DB tests.

## 8. API contract changes
- Created `docs/api-contract.md` v1 (frozen 2026-09-23) plus the first `CHANGELOG-contract.md` entry. No Android issue needed (initial version).

## 9. Environment / setup notes
- Env vars: see `.env.example`. `DATABASE_USER`/`DATABASE_PASSWORD` are optional (the URLs may carry credentials).
- Migrations added: `V1__init.sql`, `V2__seed_taxonomy.sql`. **Once applied to Neon, never edit them.**
- Exposed 1.5 notes for later agents: imports are `org.jetbrains.exposed.v1.core.*` / `…v1.jdbc.*`, UUID columns use `javaUUID(...)`, and the Kotlin property for column `source` is `entrySource` / `recommendationSource` (`source` clashes with `ColumnSet.source`).
- Logback keeps the `Exposed` logger at WARN (DEBUG would log SQL with bound values, e.g. emails).

## 10. Questions / blockers for humans
- ~~Boot against Neon `dev`~~: done 2026-09-23 (V1+V2 are now applied on `dev`, so never edit them).
- **Real-token check:** call `/v1/me` → `POST /v1/account` → `/v1/me` with a real Firebase ID token.
- **Push `main`** so CI runs for the first time.
- **Fix `.env`:** `CLOCK_OVERRIDE=true` isn't an ISO instant (it's ignored with a warning). Leave it empty, or set e.g. `2026-10-04T00:05:00Z`.
- Hosting decision is still due Thu 9/24 noon (plan §11).

---

_Older entries: none (this is the first session)._
