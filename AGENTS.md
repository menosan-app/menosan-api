# AGENTS.md — menosan-api

Kotlin + Ktor backend for Menosan. **Read `docs/DEVELOPMENT_PLAN.md` §1–§9 before you start any task.**

## Ground rules
- Stack: Kotlin 2.x, Ktor 3.x, Exposed, HikariCP, Flyway, Neon Postgres, Firebase Admin, Google Gen AI Java SDK, JUnit 5. JDK 21, Gradle Kotlin DSL.
- `docs/api-contract.md` is the contract with the Android app. Don't change it without following plan §8.4.
- Every private query is scoped by the authenticated `userId`. A resource that belongs to someone else returns 404.
- Get "now" from the injected `Clock` only. Use `WeekCalc` (Asia/Manila, Sunday–Saturday) for all week math.
- Analytics (`app.menosan.analytics`) stays pure and deterministic, with no I/O. Bump `ALGORITHM_VERSION` whenever the rules change.
- `aggregate()`, `findHotspots()`, `compare()`, and `measureImpact()` are also ported to the Android app for offline reports (plan §5.7). Keep them free of server dependencies. Any change must bump `ALGORITHM_VERSION` and update `docs/analytics-test-vectors.json` (and tell the Android repo).
- SPECIAL waste is excluded from totals, comparisons, hotspots, interventions, and impact.
- Interventions come only from the `interventions` table. Gemini may pick and annotate them but never invent new ones.
- Never persist or log images, request bodies, tokens, or emails.
- Migrations are append-only (`V{n}__*.sql`). Never edit a migration that has already been applied.

## Session start and handoff (mandatory)
1. **Before starting:** read `docs/HANDOFF.md` (newest entry first), then `git log --oneline -15` and `git status`. Continue from "Next steps" unless the human says otherwise.
2. **Before stopping:** update `docs/HANDOFF.md` using `docs/HANDOFF_TEMPLATE.md`, putting the new entry at the top. Do the same at the end of a session, after finishing a workstream, or when your context or tokens are running low. Stop coding and write the handoff first.
3. Commit the handoff with the code: `docs: handoff <workstream>`.
4. Never leave uncommitted work without describing it in the handoff.

## Workstreams and ownership
| WS | Owns |
|---|---|
| BE-0 | `Application.kt`, `config/`, `plugins/`, `common/`, `db/`, `taxonomy/`, `account/` (me/create), V1–V2 migrations, Dockerfile, CI, docs |
| BE-1 | `entries/`, `export/`, account deletion |
| BE-2 | `photo/`, `prompts/photo_analysis.txt` |
| BE-3 | `analytics/`, `reports/`, `jobs/` |
| BE-4 | `interventions/`, V3 seed, `prompts/intervention_selection.txt` |
| BE-5 | `dev/`, `scripts/`, deployment, `docs/ENVIRONMENTS.md` |

## Commands
- `./gradlew test` must pass before every PR.
- `./gradlew run` needs env vars from `.env.example`.
- `./gradlew buildFatJar` produces the Docker input.

## Done means
Code, tests for every rule you touched, contract doc updated if applicable, a `docs/DECISIONS.md` line for any deviation, and green CI.
