# Handoff log — menosan-api

Newest entry first. Use `docs/HANDOFF_TEMPLATE.md` for each entry. Every agent **reads this file before starting** and **updates it before stopping**.

---

# Handoff — menosan-api — 2026-09-23 (BE-4) PHT

## 1. Session
- **Agent / model:** Claude Code (Opus 5.5, `claude-opus-5-5`)
- **Workstream(s):** BE-4 Intervention library, engine, adoption (docs/DEVELOPMENT_PLAN.md §6, §9)
- **Branch:** `feat/be4-interventions` (worktree `menosan-api-be4`, not pushed, not merged) · **Last code commit:** `829f1e4 feat(interventions): adoption endpoints with I7 window, report payload helpers, app wiring`
- **Overall state:** 🟢 BE-4 DoD met on the branch, 86/86 tests green. Two integration points are still open with BE-2 and BE-3 (§3, §4).
- **Note:** the session prompt said to "create and work on branch feat/be1-entries". That branch is already checked out in the `menosan-api-be1` worktree and belongs to BE-1, so BE-4 work stayed on `feat/be4-interventions`.

## 2. Done this session
- [x] `V3__seed_interventions.sql`: 59 curated items (≥ 3 per non-SPECIAL subcategory, none for SPECIAL), each with a title (≤ 60 chars), a 1–3 sentence description, and 2–4 `how_to` steps, following §6.1. Test: `InterventionLibrarySeedTest`. (`058b107`)
- [x] `interventions/Intervention.kt`: model, `CostLevel`/`Effort`/`InterventionType` enums, `InterventionRepository` + `ExposedInterventionRepository`. (`058b107`)
- [x] `RuleRanking` (pure): cost → effort → type → code. SAME/INCREASED adoptions are excluded unless nothing else is left, and DECREASED ones are pinned first with `continued = true`. (`1e9b3e4`)
- [x] `GeminiSelection` + `LibraryInterventionEngine` (the real `InterventionEngine`): the prompt carries anonymized numbers only, the `responseSchema` has an enum of candidate ids, and the timeout is 8 s. Any violation rejects the whole answer and falls back to `RULES`: count outside 1–3, unknown or non-candidate id, duplicate id or rank, rank < 1, note > 200 chars, URL, blaming words, malformed JSON, error, or timeout. Short Gemini answers are padded with rule picks. Prompt: `resources/prompts/intervention_selection.txt`. Tests: `RuleRankingTest`, `LibraryInterventionEngineTest`. (`1e9b3e4`)
- [x] Adoption: `AdoptionService` + `ExposedAdoptionService` (user-scoped, I7 window via `AdoptionWindow.isLatest`, all-or-nothing, `ON CONFLICT DO NOTHING`, baseline = the hotspot's quantity), `POST /v1/reports/{weekStart}/adoptions`, `DELETE /v1/reports/{weekStart}/adoptions/{interventionId}`. Test: `AdoptionRoutesTest` (window closed 409, idempotent, 404 for another user's or a missing report, 400 cases, auth). (`829f1e4`)
- [x] Helpers for BE-3 in `interventions/InterventionQueries.kt` (call inside `db.tx {}`): `insertRecommendations(hotspotId, picks)`; `recommendationViews(reportId)` → `Map<hotspotId, List<RecommendationView>>` (the contract §3 recommendation shape, including `adopted`); `adoptionsForReport(reportId)` → baselines for impact and `PreviousAdoption`; `adoptedInterventionIds(reportId)`. Test: `InterventionQueriesTest`. (`829f1e4`)
- [x] Wiring: `AppDeps.adoptions`, `AppDeps.reportResponder`. `main()` builds `LibraryInterventionEngine(ExposedInterventionRepository(db), gemini, taxonomy)` and `ExposedAdoptionService(db, clock)`. Routes are mounted in `Application.module` inside `authenticated { }`. (`829f1e4`)
- [x] Docs: 9 BE-4 lines in `DECISIONS.md`, `api-contract.md` §5.7 (adoption clarifications, no shape change), and a `CHANGELOG-contract.md` line.

## 3. In progress (unfinished)
| Item | Where | What's left |
|---|---|---|
| Full report as the adoption response | `AppDeps.reportResponder` | BE-3 must provide a `ReportResponder` that renders the §8.3 report. Until then the endpoints return the interim `{weekStart, adoptedInterventionIds}` (see DECISIONS). **Must be wired before the Android beta.** |
| Real Gemini | `Application.kt` `main()` (`val gemini: GeminiClient = StubGeminiClient`) | BE-2 swaps in the real client. Until then every recommendation uses `source = RULES`, which is correct fallback behavior. |

## 4. Next steps (in order)
1. **Merge order:** merge BE-1/BE-2/BE-3 and this branch into `main` one at a time. Expected conflicts are only in `AppDeps.kt` and `Application.kt` (additive params and route mounts), so keep both sides. If another branch also added a `V3`, renumber this file **before** it is applied to Neon (it isn't applied anywhere yet).
2. **BE-3 integration (whoever merges second):**
   - In `ReportService` generation step 5, for each hotspot call `deps.interventions.recommend(RecommendationInput(HotspotInput(code, criteria, f, q), analyzedTotalQuantity, previousAdoptions))` **outside** the DB transaction. Build `previousAdoptions` from `adoptionsForReport(reportW−1Id)` plus W's impact result per adoption (`PreviousAdoption(interventionId, targetSubcategory, result)`). Then call `insertRecommendations(hotspotId, picks)` inside the persist transaction.
   - On regeneration (§5.6), only new hotspots need `recommend()`. Adoptions reference `(report_id, intervention_id)`, so they survive.
   - In `GET /v1/reports/{weekStart}`, fill `hotspots[].recommendations` from `recommendationViews(reportId)[hotspotId] ?: emptyList()`, fill `adoptedCount` in the list from `adoptedInterventionIds`, and use `AdoptionWindow.isLatest(weekStart, clock)` for `isLatest`.
   - In `main()`, set `reportResponder = ReportResponder { call, userId, weekStart -> call.respond(<full report>) }`. Then change the `AdoptionRoutesTest` assertions that use `adoptedIds()` (which reads the interim body) to read `hotspots[].recommendations[].adopted`.
3. **BE-2 integration:** replace `StubGeminiClient` in `main()` with the real client. The engine already passes `systemInstruction`, `responseSchemaJson`, and `timeout = 8s`.
4. Human tone review of the library copy (plan §11, Fri 9/25): read `V3__seed_interventions.sql`. Content fixes after it is applied to Neon need a **new** migration (`UPDATE interventions … WHERE code = …`).
5. BE-5 e2e: seed history → report → adopt → roll the week → impact.

## 5. Verify the current state
```bash
# Windows without a JDK on PATH (Git Bash): export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
./gradlew test    # 86 tests, 0 failures
```
- New test classes: `InterventionLibrarySeedTest` (5), `RuleRankingTest` (6), `LibraryInterventionEngineTest` (16), `AdoptionRoutesTest` (11), `InterventionQueriesTest` (2).
- Manual check (needs a report row, so only after BE-3 or BE-5 seeding): `POST /v1/reports/<latest Sunday>/adoptions {"interventionIds":["<id from the report>"]}` → 200.

## 6. Known issues / failing tests
- None failing.
- The adoption response is interim until BE-3 wires `reportResponder` (§3). Android must not build against the interim body.
- Adoption doesn't run lazy report catch-up. A report row that doesn't exist yet → 404. Clients always GET the report first (which runs catch-up), so this is fine.

## 7. Decisions made (also logged in docs/DECISIONS.md)
- SAME/INCREASED adoptions are excluded (not just moved down) unless nothing else is left. A null result is neutral.
- DECREASED adoptions are pinned first (`continued`, `RULES`, no note). Gemini fills the remaining slots.
- 1–2 Gemini picks are padded with rule picks up to 3.
- Notes with blaming words are rejected (→ fallback). Blank notes become null.
- Adoption errors: 400 for a bad request shape or ids not on the report (all-or-nothing), 404 for a missing or foreign report, 409 outside the window. Max 9 ids. Idempotent both ways.
- Baseline = the hotspot's quantity at adoption time.

## 8. API contract changes
- `api-contract.md` §5.7 clarifies adoption validation, errors, and idempotency (no shape change). `CHANGELOG-contract.md` has a new line. **A human still needs to open the Android issue (label `contract-change`).**

## 9. Environment / setup notes
- Migration added: `V3__seed_interventions.sql` (not applied to Neon yet). **Once applied, never edit it.**
- Resource added: `prompts/intervention_selection.txt`.
- No new env vars or dependencies.

## 10. Questions / blockers for humans
- Open the Android `contract-change` issue for §5.7.
- Review the 59 library items for tone and local fit (plan §11, Fri 9/25).
- Still open from BE-0: the real-token check, the first CI run (push `main`), and the hosting decision (due Thu 9/24 noon).

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
