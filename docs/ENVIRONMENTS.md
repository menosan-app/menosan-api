# Environments — menosan-api

Owned by BE-5 (plan §9). **Hosting: [Render](https://render.com)** (team decision 2026-09-23, plan §2.1). Web
services built from the repo's `Dockerfile`, **created by hand in the Render dashboard** (no Blueprint, see
`docs/DECISIONS.md`), in Render's **Singapore** region, next to Neon (`aws ap-southeast-1`). All config comes from env vars.

## 1. Base URLs

| Environment | API base URL | Neon branch | Who uses it |
|---|---|---|---|
| local | `http://localhost:8080` (Android emulator: `http://10.0.2.2:8080`) | `dev` | developers |
| staging | `https://menosan-api-staging.onrender.com` (deployed 2026-09-24) | `dev` | Android `staging` flavor, UAT demo accounts, e2e |
| prod | **not created yet** (planned: `https://menosan-api.onrender.com`) | `main` | Android `prod` flavor, testers' real weeks |

Android takes these as `API_BASE_URL` per build flavor (plan §10). Send them to the Android team as soon as they exist.

## 2. Environment variables

See `.env.example` for formats. Secrets (★) go into the host's secret store, never into the repo or the image.

| Variable | local | staging | prod |
|---|---|---|---|
| `APP_ENV` | `dev` | `staging` (**must be set**: the image defaults to `prod`) | `prod` |
| `PORT` | `8080` | `8080` | `8080` |
| `DATABASE_URL` ★ | Neon `dev` pooled | Neon `dev` pooled | Neon `main` pooled |
| `DATABASE_URL_DIRECT` ★ | Neon `dev` direct | Neon `dev` direct | Neon `main` direct |
| `FIREBASE_PROJECT_ID` | `menosan-app` | `menosan-app` | `menosan-app` |
| `FIREBASE_SERVICE_ACCOUNT_JSON_B64` ★ | yes | yes | yes |
| `GEMINI_API_KEY` ★ | optional | yes | yes (billing-enabled key if possible, plan §13) |
| `GEMINI_PHOTO_MODEL` | default `gemini-3.5-flash-lite` | don't set (default) | don't set (default) |
| `GEMINI_INTERVENTION_MODEL` | default `gemini-3.1-flash-lite` | don't set (default) | don't set (default) |
| `GEMINI_RPM` / `GEMINI_RPD` | default `15` / `500` (per model) | don't set, unless the key's limits differ | same |
| `GEMINI_MODEL` | **no longer used** (startup warning if set) | **delete it** | don't set |
| `JOB_KEY` ★ | any | `openssl rand -hex 32`, **different from prod** | `openssl rand -hex 32` |
| `DEV_TOOLS_ENABLED` | `true` if you want them | `true` | don't set (always off in prod) |
| `CLOCK_OVERRIDE` | empty | don't set (use `/internal/dev/clock`) | don't set |
| `JAVA_TOOL_OPTIONS` | — | `-XX:TieredStopAtLevel=1` (faster JVM start on Free's small CPU) | don't set |

- The app runs Flyway migrations **on every start**, against `DATABASE_URL_DIRECT`. The first prod start applies
  V1–V3 to Neon `main`. Migrations are append-only, so this is safe to repeat.
- Leave out `DATABASE_USER`/`DATABASE_PASSWORD` when the JDBC URLs already carry the credentials (they do in `.env`).

## 3. Deploying (Render, by hand)

| Service | Plan | Branch | Deploys | Neon |
|---|---|---|---|---|
| `menosan-api-staging` | **Free** | `main` | automatically, after CI checks pass | `dev` |
| `menosan-api` (not created yet) | **Starter** (always on) | `main` | **by hand only**, after staging passes the e2e run | `main` |

**Creating a service** (a human with access to the team's Render workspace):

1. Render → **New → Web Service** → connect `menosan-app/menosan-api`.
2. Name as in the table, **Language: Docker**, branch `main`, region **Singapore**, Dockerfile path `./Dockerfile`,
   instance type as in the table. Advanced → **Health check path `/health`**. Auto-Deploy: staging
   **After CI checks pass**, prod **Off**.
3. Environment: type each variable from §2 **by hand**. Don't use *Add from .env*: it keeps inline `# comments`
   as part of the value (`DEV_TOOLS_ENABLED=true # …` is not `true`). No quotes, no comments.
4. **Create Web Service.** The first Docker build takes several minutes. In **Logs**, the startup line
   `AppConfig(appEnv=STAGING, …)` (or `PROD`) shows the settings with secrets redacted. There must be no
   `GEMINI_API_KEY is not set` warning. Staging also logs `Dev tools are enabled at /internal/dev`.
5. Check it:
   - `GET /health` → `{"status":"ok","db":"ok"}`
   - `GET /internal/dev/clock` with no key → staging `401`, prod `404`
   - `POST /internal/jobs/weekly-reports` with no key → `401` (a `404` means `JOB_KEY` isn't set)
6. Fill in §1. For prod, also put its `JOB_KEY` in the GitHub secret (§5).

**Each release:**
- Staging redeploys on its own after every green push to `main`.
- Prod: after staging passes `scripts/e2e-staging.sh`, open the `menosan-api` service → **Manual Deploy → Deploy latest commit**.
  To roll back, use **Rollbacks** on the service page (the image of an earlier deploy).

Notes:
- **Staging on Free** sleeps after 15 idle minutes, and the next request wakes it (up to about a minute for the JVM).
  Each wake-up is a restart, which clears the dev clock set with `/internal/dev/clock` and the photo rate-limit
  counters. The in-process Sunday job doesn't run while it sleeps; lazy catch-up on read generates missing reports.
  Free gives the workspace 750 instance-hours a month.
- **Prod on Starter** is always on: no cold starts for testers (the Android client would time out first), and the
  in-process Sunday job runs. The GitHub Actions cron (§5) is a safety net, and lazy catch-up covers any missed run.
- **Memory:** Free and Starter have 512 MB. The JVM uses up to 75% of it (`-XX:MaxRAMPercentage=75` in the `Dockerfile`)
  and exits on OOM (`-XX:+ExitOnOutOfMemoryError`), so Render restarts it. If a service restarts with OOM in the logs,
  move it to Standard (2 GB).
- **Deploys:** Render keeps the old instance until the new one passes `/health`, so both run for a moment. That's
  safe: Flyway locks migrations, and the weekly job is idempotent.
- Keep **1 instance** per service: the photo rate limiter is counted per instance (`docs/DECISIONS.md`).
- The API does its own auth (Firebase ID tokens, `X-Job-Key`), so the services are public.
- Logs: service page → **Logs**. They never contain bodies, tokens, emails, or images (plan §14).

## 4. Neon

- Prod project: set **history retention (point-in-time restore) to 7 days or less** (plan §7, NFR4). Human action in the Neon console.
- Staging and local share the `dev` branch. DB tests never touch Neon (embedded Postgres).

## 5. Weekly report cron (GitHub Actions)

`.github/workflows/weekly-reports.yml` calls `POST /internal/jobs/weekly-reports` every Sunday 00:05 PHT. To enable it
for prod, in the repo's *Settings → Secrets and variables → Actions*:
- variables: `WEEKLY_REPORTS_ENABLED=true`, `API_BASE_URL=<prod base URL>` (no trailing slash)
- secret: `JOB_KEY` = prod's `JOB_KEY`

Test it once with *Run workflow* (`workflow_dispatch`) and an empty `weekStart`: expect `200 {"weekStart":…,"created":N}`.

## 6. Staging dev tools (`/internal/dev/...`)

Only when `DEV_TOOLS_ENABLED=true` and not prod. Every call needs `X-Job-Key: <staging JOB_KEY>`. Full rules: `api-contract.md` §5.10.

```bash
API=https://<staging host>; KEY=<staging JOB_KEY>
# Server clock (affects everyone on staging)
curl -s "$API/internal/dev/clock" -H "X-Job-Key: $KEY"
curl -s -X POST "$API/internal/dev/clock" -H "X-Job-Key: $KEY" -H 'Content-Type: application/json' -d '{"now":"2026-10-04T00:05:00Z"}'
curl -s -X POST "$API/internal/dev/clock" -H "X-Job-Key: $KEY" -H 'Content-Type: application/json' -d '{}'   # back to real time

# Demo account for UAT (the tester signs in to the staging app once first, so the account exists)
curl -s -X POST "$API/internal/dev/seed-history" -H "X-Job-Key: $KEY" -H 'Content-Type: application/json' \
  -d '{"email":"demo1@example.com","weeks":3}'
curl -s -X POST "$API/internal/dev/reset" -H "X-Job-Key: $KEY" -H 'Content-Type: application/json' -d '{"email":"demo1@example.com"}'
curl -s -X POST "$API/internal/dev/reports/generate" -H "X-Job-Key: $KEY" -H 'Content-Type: application/json' \
  -d '{"email":"demo1@example.com","weekStart":"2026-09-20","regenerate":false}'
```

`seed-history` gives the account 3 past weeks with reports: the two older reports each have an adopted intervention
whose impact shows as DECREASED on the next report, and the latest report is left for the tester to adopt (UAT 5 and 6).
To show impact on that adoption, move the clock a week forward, log a few entries, and move it again.

## 7. End-to-end check on staging

```bash
E2E_BASE_URL=https://<staging host> E2E_JOB_KEY=<staging JOB_KEY> E2E_ID_TOKEN=<token> scripts/e2e-staging.sh
```

- Use a **throwaway** Google account: the run deletes its entries and reports. Get a token from `token-helper/index.html`.
- The run moves staging's clock for about a minute and restores it. Don't run it during UAT sessions.
- The same scenario runs in CI in-process (`EndToEndFlowTest`), so a staging failure points at config, data, or hosting.

PowerShell: `$env:E2E_BASE_URL="…"; $env:E2E_JOB_KEY="…"; $env:E2E_ID_TOKEN="…"; ./gradlew test --tests 'app.menosan.e2e.StagingE2eTest' --rerun`

## 8. Release gate checklist (plan §9 BE-5, Fri 9/25)

- [x] Hosting decided: Render (2026-09-23)
- [x] Staging deployed by hand (§3), `APP_ENV=staging`, `DEV_TOOLS_ENABLED=true`, `GET /health` → `{"status":"ok","db":"ok"}` (2026-09-24)
- [x] Staging smoke test (2026-09-24): account create and delete, photo analysis via Gemini, `seed-history`, reports with Gemini notes
- [ ] Staging URL sent to Android
- [ ] `scripts/e2e-staging.sh` passes
- [ ] Prod deployed (`APP_ENV=prod`), `GET /health` OK, `POST /internal/dev/clock` → 404
- [ ] Neon prod retention ≤ 7 days
- [ ] `weekly-reports.yml` enabled for prod and tested once by hand
- [ ] Prod URL sent to Android
