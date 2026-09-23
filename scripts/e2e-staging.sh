#!/usr/bin/env bash
# End-to-end check of a deployed staging API (plan §9 BE-5): account → log week A → roll the clock →
# report + adopt → log week B → roll → comparison and impact. Runs app.menosan.e2e.StagingE2eTest.
#
#   E2E_BASE_URL=https://<staging host> \
#   E2E_JOB_KEY=<staging JOB_KEY> \
#   E2E_ID_TOKEN=<Firebase ID token> \
#   scripts/e2e-staging.sh
#
# - The token must belong to a THROWAWAY Google account: the run deletes all of its entries and reports.
#   Get one from token-helper/index.html ("Copy fresh token"); it is valid for about an hour.
# - The run moves staging's server-wide clock for a minute or two and then restores it, so don't run it
#   while testers are using staging.
# - Staging needs APP_ENV=staging, DEV_TOOLS_ENABLED=true, and JOB_KEY (docs/ENVIRONMENTS.md).
set -euo pipefail
cd "$(dirname "$0")/.."

: "${E2E_BASE_URL:?Set E2E_BASE_URL to the staging base URL, e.g. https://menosan-api-staging-xyz.a.run.app}"
: "${E2E_JOB_KEY:?Set E2E_JOB_KEY to the JOB_KEY of staging}"
: "${E2E_ID_TOKEN:?Set E2E_ID_TOKEN to a Firebase ID token of a throwaway test account}"
export E2E_BASE_URL E2E_JOB_KEY E2E_ID_TOKEN

echo "Health check: $E2E_BASE_URL/health"
curl --fail-with-body --silent --show-error --retry 3 --retry-all-errors --retry-delay 10 --max-time 120 "$E2E_BASE_URL/health"
echo

# --rerun: env vars aren't Gradle inputs, so the test task would otherwise be UP-TO-DATE.
# --info prints the scenario's "[e2e] ..." progress lines.
./gradlew test --tests 'app.menosan.e2e.StagingE2eTest' --rerun --no-daemon --info 2>&1 |
    grep -E '\[e2e\]|StagingE2eTest|AssertionError|Expected|BUILD (SUCCESSFUL|FAILED)'
