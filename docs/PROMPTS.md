# Prompts — menosan-api

Copy and paste these prompts. Replace the `<…>` placeholders.

---

## 1. Claude Code — first session (BE-0 Foundation)

```
You are starting development of the Menosan backend in this repository (menosan-api).

Read these files fully before writing any code:
- AGENTS.md
- docs/DEVELOPMENT_PLAN.md (focus on §1–§9 and §12)
- docs/HANDOFF.md
- src/main/resources/taxonomy.json

Your task is workstream BE-0 (Foundation) from §9 of the plan. Stop when its Definition of Done is met. Do not start BE-1 to BE-5.

Scope:
1. Gradle Kotlin DSL project: Kotlin 2.x, Ktor 3.x (Netty), JDK 21, package root app.menosan. Include the Gradle wrapper. If you can't generate gradle-wrapper.jar because Gradle isn't installed, tell me and I'll run `gradle wrapper` or generate it from IntelliJ.
2. AppConfig loaded from env vars (see .env.example; load .env locally), /health with a DB check, StatusPages that return the §8.1 error format, request IDs, and call logging that never logs bodies, tokens, or emails.
3. Database: HikariCP (max pool 5) against the Neon pooled URL, Flyway against DATABASE_URL_DIRECT, V1__init.sql exactly as in §7, V2__seed_taxonomy.sql generated from taxonomy.json, and Exposed table objects for all tables.
4. An injectable Clock and WeekCalc (Asia/Manila, Sunday–Saturday), with unit tests for the Sat 23:59:59 / Sun 00:00 PHT boundary (Sat 15:59:59 / 16:00 UTC).
5. Firebase auth plugin: verify the ID token with the Firebase Admin SDK (service account from FIREBASE_SERVICE_ACCOUNT_JSON_B64), resolve UserPrincipal, and return 401 UNAUTHENTICATED or 404 ACCOUNT_NOT_FOUND per §8.1.
6. Endpoints: GET /v1/taxonomy, GET /v1/me, POST /v1/account (idempotent, requires consent=true), GET /v1/weeks/current.
7. Interfaces with stub implementations for later workstreams: EntryRepository, PhotoAnalyzer, ReportService, InterventionEngine, GeminiClient.
8. docs/api-contract.md (copy §8 as the canonical contract), docs/CHANGELOG-contract.md, a multi-stage Dockerfile (fat JAR), and a GitHub Actions CI workflow running ./gradlew test.

Rules:
- Follow AGENTS.md. Use small commits with Conventional Commit messages.
- Tests must not need real secrets. Fake Firebase verification and Gemini behind interfaces.
- If something in the plan is ambiguous, pick the simplest option, log it in docs/DECISIONS.md, and keep going. Only stop to ask me when you need a secret or a human decision.
- I will put real values in .env myself. Never print or commit secrets.

When done:
1. Run ./gradlew test and show the result.
2. Tell me exactly how to verify it manually (run the app locally, then curl /health and /v1/taxonomy).
3. Write the first entry in docs/HANDOFF.md using docs/HANDOFF_TEMPLATE.md, with BE-1 to BE-4 as the next steps, and commit it.
```

---

## 2. Claude Code — later sessions (any workstream)

```
Continue Menosan backend development. Read AGENTS.md, docs/HANDOFF.md (newest entry), and the relevant sections of docs/DEVELOPMENT_PLAN.md, then run `git log --oneline -15` and `./gradlew test` to confirm the current state.

Work on: <BE-1 | BE-2 | BE-3 | BE-4 | BE-5 — or "the next steps in HANDOFF.md">.
Stay inside the files your workstream owns (AGENTS.md table). Meet the workstream's Definition of Done in §9, including its listed tests.
When finished, or if context runs low, update docs/HANDOFF.md from the template and commit.
```

> Parallel agents: run each workstream on its own branch or git worktree (e.g., `feat/be1-entries`, `feat/be2-photo`) and merge to `main` one at a time. Only start them after BE-0 is merged.

---

## 3. Emergency handoff (paste when you're about to run out of tokens)

```
Stop coding now. Update docs/HANDOFF.md using docs/HANDOFF_TEMPLATE.md (new entry at the top) with the exact state of your work: what's done, what's half-done (file paths and what's left), failing tests, next steps in order, and decisions made. Then commit everything, including work in progress, with the message "wip: <workstream> handoff".
```

---

## 4. Fallback prompt for another model (Codex, Gemini CLI, Cursor, GPT, etc.)

These tools don't read CLAUDE.md automatically, so this prompt is self-contained.

```
You are taking over development of "Menosan", a Kotlin + Ktor backend (repository: menosan-api) for an Android app that helps households in Dumaguete City, Philippines, prevent household waste. Another AI agent was working on this before you. Your job is to continue exactly where it stopped, following the same plan and rules.

Step 1 — Load context (don't write any code yet):
1. Read AGENTS.md (repository rules and file ownership).
2. Read docs/HANDOFF.md. The newest entry at the top is the current state.
3. Read docs/DEVELOPMENT_PLAN.md. This is the source of truth. Pay special attention to §2 (fixed decisions), §4 (week and time rules: Asia/Manila, Sunday–Saturday), §5 (deterministic analytics), §6 (intervention engine), §7 (database schema), §8 (API contract), and §9 (backend workstreams with Definitions of Done).
4. Read docs/api-contract.md and docs/DECISIONS.md.
5. Run `git log --oneline -20`, `git status`, and `./gradlew test`.

Step 2 — Before coding, reply with a short summary:
- Which workstream you are continuing, and its Definition of Done.
- What is already done and what is left (from HANDOFF.md plus your own inspection).
- Whether the tests pass now, and any mismatch between HANDOFF.md and the actual code.
Then continue working without waiting for approval, unless something is blocked by a missing secret or a human decision.

Step 3 — Rules you must follow:
- Stack is fixed: Kotlin 2.x, Ktor 3.x, Exposed, HikariCP, Flyway, Neon Postgres, Firebase Admin SDK, Google Gen AI Java SDK, JUnit 5. Don't switch frameworks or add large dependencies.
- Don't change the API contract (docs/api-contract.md) unless you follow §8.4 of the plan.
- Every private query is scoped by the authenticated userId. Resources owned by other users return 404.
- Get "now" only from the injected Clock. Do all week math through WeekCalc in Asia/Manila.
- SPECIAL waste is excluded from totals, comparisons, hotspots, interventions, and impact.
- Interventions only come from the curated `interventions` table. Gemini may only pick and annotate them.
- Never log or store images, request bodies, tokens, or emails. Never commit secrets.
- Migrations are append-only.
- Write tests for every rule you implement. `./gradlew test` must pass before you commit.
- Record any deviation from the plan in docs/DECISIONS.md.

Step 4 — Before you stop (end of task, or when your context or budget is running low):
Add a new entry at the top of docs/HANDOFF.md using docs/HANDOFF_TEMPLATE.md. Include your model name, and commit it with the code. The next agent may be a different model, so be explicit.
```
