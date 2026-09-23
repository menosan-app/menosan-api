# Environments — menosan-api

Owned by BE-5 (plan §9). **Hosting is not decided yet** (plan §2.1, due Thu 9/24 noon). The backend is
host-agnostic: one Docker image (or `build/libs/menosan-api.jar`), all config from env vars. The commands below
assume the plan's suggested default, **Google Cloud Run**. Update the URLs table once the team decides.

## 1. Base URLs

| Environment | API base URL | Neon branch | Who uses it |
|---|---|---|---|
| local | `http://localhost:8080` (Android emulator: `http://10.0.2.2:8080`) | `dev` | developers |
| staging | **TBD after deploy** | `dev` | Android `staging` flavor, UAT demo accounts, e2e |
| prod | **TBD after deploy** | `main` | Android `prod` flavor, testers' real weeks |

Android takes these as `API_BASE_URL` per build flavor (plan §10). Send them to the Android team as soon as they exist.

## 2. Environment variables

See `.env.example` for formats. Secrets (★) go into the host's secret store, never into the repo or the image.

| Variable | local | staging | prod |
|---|---|---|---|
| `APP_ENV` | `dev` | `staging` (**must be set**: the image defaults to `prod`) | `prod` |
| `PORT` | `8080` | set by the host (Cloud Run: 8080) | same |
| `DATABASE_URL` ★ | Neon `dev` pooled | Neon `dev` pooled | Neon `main` pooled |
| `DATABASE_URL_DIRECT` ★ | Neon `dev` direct | Neon `dev` direct | Neon `main` direct |
| `FIREBASE_PROJECT_ID` | `menosan-app` | `menosan-app` | `menosan-app` |
| `FIREBASE_SERVICE_ACCOUNT_JSON_B64` ★ | yes | yes | yes |
| `GEMINI_API_KEY` ★ | optional | yes | yes (billing-enabled key if possible, plan §13) |
| `GEMINI_MODEL` | `gemini-2.5-flash` | same | same |
| `JOB_KEY` ★ | any | random, **different from prod** | random |
| `DEV_TOOLS_ENABLED` | `true` if you want them | `true` | ignored (always off in prod) |
| `CLOCK_OVERRIDE` | empty | empty (use `/internal/dev/clock`) | ignored |

- The app runs Flyway migrations **on every start**, against `DATABASE_URL_DIRECT`. The first prod start applies
  V1–V3 to Neon `main`. Migrations are append-only, so this is safe to repeat.
- Generate a job key: `openssl rand -hex 32`.

## 3. Deploying (Cloud Run, suggested default)

One-time setup (a human with owner rights on the Firebase/GCP project `menosan-app`):

```bash
gcloud config set project menosan-app
gcloud services enable run.googleapis.com artifactregistry.googleapis.com secretmanager.googleapis.com cloudbuild.googleapis.com
gcloud artifacts repositories create menosan --repository-format=docker --location=asia-southeast1

# One secret per value and environment, e.g.:
printf '%s' "$VALUE" | gcloud secrets create staging-database-url --data-file=-
#   staging-database-url, staging-database-url-direct, staging-job-key,
#   prod-database-url, prod-database-url-direct, prod-job-key,
#   firebase-sa-b64, gemini-api-key
# Let the Cloud Run service account read them:
gcloud projects add-iam-policy-binding menosan-app \
  --member="serviceAccount:$(gcloud projects describe menosan-app --format='value(projectNumber)')-compute@developer.gserviceaccount.com" \
  --role=roles/secretmanager.secretAccessor
```

Each release (from a clean checkout of `main`):

```bash
IMAGE=asia-southeast1-docker.pkg.dev/menosan-app/menosan/menosan-api:$(git rev-parse --short HEAD)
gcloud builds submit --tag "$IMAGE"      # builds the Dockerfile remotely (no local Docker needed)

# Staging
gcloud run deploy menosan-api-staging --image "$IMAGE" --region asia-southeast1 --allow-unauthenticated \
  --min-instances 0 --max-instances 2 --memory 1Gi --cpu 1 \
  --set-env-vars APP_ENV=staging,FIREBASE_PROJECT_ID=menosan-app,GEMINI_MODEL=gemini-2.5-flash,DEV_TOOLS_ENABLED=true \
  --set-secrets DATABASE_URL=staging-database-url:latest,DATABASE_URL_DIRECT=staging-database-url-direct:latest,JOB_KEY=staging-job-key:latest,FIREBASE_SERVICE_ACCOUNT_JSON_B64=firebase-sa-b64:latest,GEMINI_API_KEY=gemini-api-key:latest

# Prod (same image, after staging passes the e2e run)
gcloud run deploy menosan-api --image "$IMAGE" --region asia-southeast1 --allow-unauthenticated \
  --min-instances 1 --max-instances 3 --memory 1Gi --cpu 1 \
  --set-env-vars APP_ENV=prod,FIREBASE_PROJECT_ID=menosan-app,GEMINI_MODEL=gemini-2.5-flash \
  --set-secrets DATABASE_URL=prod-database-url:latest,DATABASE_URL_DIRECT=prod-database-url-direct:latest,JOB_KEY=prod-job-key:latest,FIREBASE_SERVICE_ACCOUNT_JSON_B64=firebase-sa-b64:latest,GEMINI_API_KEY=gemini-api-key:latest
```

Notes:
- `--allow-unauthenticated` is correct: the API does its own auth (Firebase ID tokens, `X-Job-Key`).
- `--min-instances 1` on prod during the testing weeks avoids JVM cold starts for testers (plan §13). Staging can scale to zero.
- Cloud Run throttles CPU between requests, so the in-process Sunday job isn't reliable there. **Enable the
  GitHub Actions cron** (§5). Lazy catch-up on read still covers any missed run.
- Other hosts (Render, Fly.io, a VM): run the same image with the same env vars, and set the health check to `GET /health`.

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

- [ ] Hosting decided, and this file's URLs table filled in
- [ ] Staging deployed (`APP_ENV=staging`, `DEV_TOOLS_ENABLED=true`), `GET /health` → `{"status":"ok","db":"ok"}`
- [ ] `scripts/e2e-staging.sh` passes
- [ ] Prod deployed (`APP_ENV=prod`), `GET /health` OK, `POST /internal/dev/clock` → 404
- [ ] Neon prod retention ≤ 7 days
- [ ] `weekly-reports.yml` enabled for prod and tested once by hand
- [ ] Base URLs sent to Android
