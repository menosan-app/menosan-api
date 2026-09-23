# Handoff log — menosan-api

Newest entry first. Use `docs/HANDOFF_TEMPLATE.md` for each entry. Every agent **reads this file before starting** and **updates it before stopping**.

---

# Handoff — menosan-api — 2026-09-23 09:38 PHT

## 1. Session
- **Agent / model:** Claude Code (Opus 5.5, `claude-opus-5-5`)
- **Workstream(s):** BE-1 Accounts, entries, sync, export (docs/DEVELOPMENT_PLAN.md §9)
- **Branch:** `feat/be1-entries` (worktree `D:/CCS6/Menosan/menosan-api-be1`, not pushed, not merged) · **Last code commit:** `11ddcc9 docs: BE-1 contract clarifications and decisions`
- **Overall state:** 🟢 BE-1 code complete, 85/85 tests green (46 BE-0 + 39 new). **Human-verified 2026-09-23:** reported all tests passing, including the manual walkthrough against Neon `dev` with a real Firebase token (entries, sync, export, account deletion).

## 2. Done this session
- [x] Entry rules in `entries/EntryValidation.kt` (field validation, 5 min / 14 day create bounds, strict UUID parse) and `entries/EntryService.kt` (create/update/replay/week-closed/ownership, sync, late-entry trigger). (`82d79ef`)
- [x] `entries/ExposedEntryRepository.kt`: upsert via `INSERT … ON CONFLICT DO NOTHING` then scoped `UPDATE`, so concurrent retries never duplicate. `EntryRepository` interface kept unchanged (only KDoc added), so BE-3/BE-4 branches merge cleanly.
- [x] `entries/EntryRoutes.kt`: `GET /v1/entries`, `PUT/DELETE /v1/entries/{id}`, `POST /v1/entries/sync`, DTOs.
- [x] `account/AccountDeletion.kt`: `DELETE /v1/account` (cascade delete → Firebase `deleteUser`, `USER_NOT_FOUND` = success), mounted with `requireAccount = false` so retries work. `FirebaseUserAdmin` interface for fakes.
- [x] `export/DataExporter.kt`, `export/ExportRoutes.kt`: `GET /v1/export`, one transaction, calls `reports.catchUp` first.
- [x] Wiring: `AppDeps` gained `accountDeletion` and `exporter` (stub defaults), `main()` uses the real implementations, routes mounted in `Application.module`.
- [x] Tests (embedded PG): `EntryValidationTest`, `EntryRoutesTest`, `EntrySyncTest`, `AccountDeletionTest`, `ExportRoutesTest`, `EntryLoggingPrivacyTest`. Shared helpers in `src/test/kotlin/app/menosan/DbTestSupport.kt` (`DbTestEnv` gives fresh uids/tokens per test, `RecordingReportService`, `FakeFirebaseUsers`, `seedReportGraph`). A quick mutation check (removing the delete week check) was caught by 2 tests.
- [x] `docs/api-contract.md` §6 (additive BE-1 clarifications), `CHANGELOG-contract.md`, 8 `DECISIONS.md` lines. (`11ddcc9`)
# Handoff — menosan-api — 2026-09-23 09:45 PHT

## 1. Session
- **Agent / model:** Claude Code (Opus 5.5, `claude-opus-5-5`)
- **Workstream(s):** BE-3 Analytics, reports, jobs (docs/DEVELOPMENT_PLAN.md §5, §9 BE-3)
- **Branch:** `feat/be3-reports` (worktree `menosan-api-be3`, not pushed) · **Last code commit:** `27c74ec fix(reports): order impacts by target, then intervention id, like measureImpact()`
- **Overall state:** 🟢 BE-3 code complete, 87/87 tests green (46 from BE-0 + 41 new), `buildFatJar` OK. Not yet merged to `main`.
- Note: the session prompt said "branch feat/be1-entries", but that branch is checked out in the BE-1 worktree (`menosan-api-be1`) and belongs to BE-1. BE-3 work stayed on `feat/be3-reports` (plan §14 naming).

## 2. Done this session
- [x] Pure analytics `analytics/Analytics.kt`: `aggregate()`, `findHotspots()`, `compare()`, `measureImpact()`, `ALGORITHM_VERSION = 1`. Only stdlib + kotlinx.serialization (a test enforces this) so Android can copy the file. Integer rounding, half away from zero. (`92a33a4`)
- [x] `docs/analytics-test-vectors.json`: 21 cases (ties, cap of 3, avoidable-by-score, only-special, empty, rounding, comparison with/without/only-special previous week, impact DECREASED/SAME/INCREASED/target-not-logged/missing follow-up/special-only follow-up, full week). `AnalyticsVectorsTest` runs all four functions against it. `AnalyticsTest` has 19 hand-computed cases. (`92a33a4`)
- [x] `reports/`: `DefaultReportService` (ensureReport, catchUp, onLateEntry, generateMissing, listReports, getReport) and `ReportStore` (all SQL, user-scoped). Idempotent insert via `ON CONFLICT DO NOTHING`. Regeneration locks the report row (`FOR UPDATE`), keeps surviving hotspots' recommendations and all adoptions, bumps `revision`, and also regenerates W+1. The engine is called outside the transaction. (`032bdd6`)
- [x] `GET /v1/reports`, `GET /v1/reports/{weekStart}` (§8.3 payload with `adopted` flags and `impacts`), `isLatest`. `parseWeekStart()` is reusable. (`032bdd6`)
- [x] `jobs/WeeklyReportJob.kt`: `POST /internal/jobs/weekly-reports` (`X-Job-Key`, optional `{weekStart}`), `requireJobKey()` for BE-5, and the in-process scheduler (Sunday 00:05 PHT, polls the injected clock every minute). Started in `main()`. (`032bdd6`)
- [x] `.github/workflows/weekly-reports.yml` (cron `5 16 * * 6`), disabled unless the repo variable `WEEKLY_REPORTS_ENABLED == 'true'`. (`811bdd7`)
- [x] `api-contract.md` §5.7–§5.8 clarifications (no shape changes), CHANGELOG line, 10 `DECISIONS.md` lines. (`811bdd7`)

## 3. In progress (unfinished)
| Item | Where | What's left |
|---|---|---|
| — | — | Nothing half-done. |

## 4. Next steps (in order)
<<<<<<< HEAD
1. **Human:** review and merge `feat/be1-entries` into `main` (expect small conflicts in `Application.kt`/`AppDeps.kt` if BE-3/BE-4 merge first: keep both sides' params and routes).
2. ~~**Human:** smoke-test against Neon `dev` with a real token~~: done 2026-09-23.
3. **Human:** open the `contract-change` issue in `menosan-android` pointing to `api-contract.md` §6 (plan §8.4). Android DTOs should follow §6 (entry list wrapper, sync result shape).
4. **BE-3:** `ReportService.onLateEntry(userId, weekStart)` is now called for every late create (once per week per request, after commit; failures are only logged). Consider a staleness safety net in `ensureReport`/catch-up (e.g. `max(waste_entries.received_at) > coalesce(regenerated_at, generated_at)` → regenerate). `EntryRepository.listForWeek` and `userIdsWithEntries` are implemented and ready.
5. **BE-5:** `seed-history` can insert entries via `EntryRepository.upsert` (bypasses the 14-day rule on purpose) or through `EntryService`.

## 5. Verify the current state
```bash
# Windows without a JDK on PATH: export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"  (Git Bash)
./gradlew test           # 85 tests, 0 failures
./gradlew run            # needs .env
curl -si -X PUT localhost:8080/v1/entries/$(uuidgen) -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"Coffee sachet","subcategory":"RES_SACHETS","quantity":2,"source":"MANUAL","createdAt":"'$(date -u +%FT%TZ)'"}'   # 201
curl -s localhost:8080/v1/entries -H "Authorization: Bearer $TOKEN"   # {weekStart, weekEnd, editable, entries:[…]}
```
- Expected: all tests pass. New test classes: `EntryValidationTest` (9), `EntryRoutesTest` (12), `EntrySyncTest` (9), `AccountDeletionTest` (5), `ExportRoutesTest` (3), `EntryLoggingPrivacyTest` (1).

## 6. Known issues / failing tests
- None failing.
- Race, accepted: if two requests of the same user create the same id at the same moment, the second one updates it without the week check (last write wins). Only the same user can hit it, with the same offline payload in practice.
- A create whose `createdAt` is up to 5 min in the future can land in next week (clock skew right at Saturday midnight). It's then not editable until that week starts. Negligible.
- Tokens stay valid for up to 1 h after `DELETE /v1/account` (the verifier doesn't check revocation). The app must sign out after `204`.

## 7. Decisions made (also logged in docs/DECISIONS.md)
- Unchanged replay → `200` in any week (retry-safe sync).
- `createdAt` change on update → `400` (`field=createdAt`); `createdAt` truncated to ms.
- Foreign id: upsert → `409 CONFLICT`, delete → `204` no-op.
- `GET /v1/entries` wrapper + `editable`/`updatedAt` on entries; non-Sunday `weekStart` → `400`.
- Sync result `{id, op, status, entry, message}`, statuses incl. `ERROR`, 500-item cap, server copy on `WEEK_CLOSED`.
- `onLateEntry` after commit, failures logged only.
- `DELETE /v1/account` without account requirement; DB first, then Firebase; `500` + retry on Firebase failure.
- Export runs catch-up first; versioned nested document.

## 8. API contract changes
- `docs/api-contract.md` §6 added (additive clarifications, nothing in §1–§5 changed) + `CHANGELOG-contract.md` line. Android `contract-change` issue still to be opened by a human (§4 step 3).

## 9. Environment / setup notes
- No new env vars, dependencies, or migrations.
- Test intervention rows made by `insertTestIntervention()` are `active = false` with code `TEST_…` and are deleted in `finally`, so BE-4's library-count tests on the shared embedded DB aren't affected.
- Exposed 1.5: `update({ where }) { … }`, `insertIgnore { }.insertedCount`, `select(col).withDistinct()`, `Table.join(other, JoinType.INNER, a, b)` all work as used in `entries/` and `export/`.

## 10. Questions / blockers for humans
- Merge order for `feat/be1-entries`, `feat/be3-reports`, `feat/be4-interventions`.
- Open BE-0 items still apply: real-token check, first CI run after pushing `main`, `.env` `CLOCK_OVERRIDE=true` fix, hosting decision (Thu 9/24 noon).
=======
1. **Human:** review and merge `feat/be3-reports` into `main`. Expect trivial conflicts in `Application.kt` (BE-1/BE-4 also add routes there) and `docs/DECISIONS.md` / `CHANGELOG-contract.md` (append-only tables: keep both sides).
2. **BE-1 (when merging):** after a *create* into a closed week, call `deps.reports.onLateEntry(userId, weekStart)`. It now really regenerates, so call it after the entry's transaction commits. Export can read `weekly_reports.stats`/`comparison` via `ReportJson` + `WeeklyStats`/`Comparison` (`reports/ReportModels.kt`), or call `reports.getReport(userId, week)` for each week.
3. **BE-4 (when merging):**
   - In `main()`, replace `val interventions: InterventionEngine = StubInterventionEngine` with the real engine. It feeds both `AppDeps.interventions` and `DefaultReportService`.
   - Adoption routes: use `parseWeekStart()`. The I7 window is `weekStart == WeekCalc.currentWeekStart(clock).minusDays(7)`. Return `reports.getReport(userId, weekStart)`.
   - `baseline_quantity` = the target subcategory's quantity in that report's `stats.subcategories` (0 if absent).
   - The payload's `adopted` flag is already computed from `adopted_interventions` (BE-3).
   - Reports generated before BE-4 lands have no recommendations; they aren't regenerated on read, by design (§6.2).
4. **BE-5:** `/internal/dev/*` can reuse `call.requireJobKey(config.jobKey)` and `reports.ensureReport` / `generateMissing`. Enable `weekly-reports.yml` once hosting is decided (set `WEEKLY_REPORTS_ENABLED`, `API_BASE_URL`, secret `JOB_KEY`).
5. **Android (AN-3):** copy `src/main/kotlin/app/menosan/analytics/Analytics.kt` (change the package) and `docs/analytics-test-vectors.json` to `app/src/test/resources/`. The vectors' `about` field explains how to run each case.

## 5. Verify the current state
```bash
# Windows without a JDK on PATH (bash): export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
./gradlew test           # 87 tests, 0 failures
./gradlew test --tests '*Analytics*'        # pure analytics + shared vectors
./gradlew test --tests '*Report*' --tests '*WeeklyReportJob*'   # DB-backed service, routes, job
REGEN_ANALYTICS_VECTORS=true ./gradlew test --tests '*AnalyticsVectorsTest*'   # only after an intended rule change + ALGORITHM_VERSION bump; review the diff
./gradlew buildFatJar
```
- New test classes: `AnalyticsTest`, `AnalyticsVectorsTest`, `ReportServiceTest`, `ReportRoutesTest`, `WeeklyReportJobTest`. Fixtures: `reports/ReportFixtures.kt` (+ `ReportApp` in `ReportRoutesTest.kt`).
- DB tests share one embedded Postgres, so tests that call the job across all users use weeks no other test touches (2026-07-05, 2026-08-02).

## 6. Known issues / failing tests
- None failing.
- **Smoke-tested on Neon `dev` (2026-09-23 10:45 PHT), then cleaned up.** Seeded one throwaway user and entries in weeks 2026-08-16 and 2026-08-23 by SQL (the entries API is still BE-1's stub). Ran `./gradlew run` and called `POST /internal/jobs/weekly-reports`:
  - Error cases: missing or wrong key → 401, open week → 400, non-Sunday → 400.
  - Generation: each week `created: 1`, then `created: 0` on a repeat (idempotent).
  - The stored stats, hotspots, scores, and comparison matched a hand calculation of §5.
  - Logs held only method, path, status, and request id.
  - The scheduler logged its next run as `2026-09-26T16:05:00Z`.
  - Afterwards the user was deleted; the cascade left 0 users, entries, and reports on `dev`.
- Not yet checked on a live server: report reads (`GET /v1/reports*`, which need a real Firebase token) and recommendations (the stub engine returns none until BE-4).
- Neon `dev` runs PostgreSQL **18.6**. Tests use embedded PG 17. No issue seen, but BE-0's "PG 17 matches Neon" note is out of date.
- Stopping `./gradlew run` from a tool can leave the forked `ApplicationKt` JVM listening on 8080. Check the port and stop that process.

## 7. Decisions made (also logged in docs/DECISIONS.md)
- Integer rounding (half away from zero) for share, deltaPct, and score.
- Impact uses the adoption's stored `baseline_quantity`.
- No follow-up entries → no impact rows; "Not measured" is client-side.
- Regeneration drops hotspots that fell out of the top 3 (and their recommendations), never adoptions.
- Reports read `waste_entries` directly (independent of BE-1's `EntryRepository`).
- A bad `weekStart` returns 400. An open or empty week returns 404.
- Job endpoint: 404 without `JOB_KEY`, 401 for a bad key.
- The scheduler polls the clock every minute.

## 8. API contract changes
- `api-contract.md` §5.7 (reports) and §5.8 (job endpoint): clarifications only, and §3 is unchanged. CHANGELOG line added. No Android issue needed (non-breaking), but tell AN-3 about the comparison row shapes in §5.7.

## 9. Environment / setup notes
- No new env vars (uses the existing `JOB_KEY`), no migrations, no new dependencies.
- The scheduler starts only in `main()`, not in `Application.module`, so tests never start it by accident.

## 10. Questions / blockers for humans
- Push `feat/be3-reports` and open a PR (CI hasn't run on it).
- Hosting decision (still due Thu 9/24 noon) gates enabling `weekly-reports.yml`.
- Carried over from BE-0: real-token check, `.env` `CLOCK_OVERRIDE=true` fix.
>>>>>>> feat/be3-reports

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
