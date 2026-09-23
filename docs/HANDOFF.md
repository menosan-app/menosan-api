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

## 3. In progress (unfinished)
| Item | Where | What's left |
|---|---|---|
| — | — | Nothing half-done. |

## 4. Next steps (in order)
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
