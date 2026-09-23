# Menosan API contract — v1

> **Status:** v1, **frozen 2026-09-23** at the end of BE-0. Canonical copy of `docs/DEVELOPMENT_PLAN.md` §8, plus the clarifications in §5 below.
> Android (`menosan-android`) builds against this file. Changes follow §4 and must be logged in `docs/CHANGELOG-contract.md`.

---

## 1. Conventions

- Base path `/v1`. JSON uses camelCase. Dates are `YYYY-MM-DD` (Manila local dates). Instants are ISO-8601 UTC (`2026-09-27T02:15:00Z`).
- Auth: `Authorization: Bearer <Firebase ID token>` on every `/v1` route except `/v1/taxonomy`. An invalid or expired token returns `401 UNAUTHENTICATED`. A valid token with no Menosan account returns `404 ACCOUNT_NOT_FOUND` on every route except `POST /v1/account` (SFR2.2).
- Every query is scoped by the authenticated `user_id` (NFR2, NFR6). Accessing another user's resource returns `404` (never `403`, so existence doesn't leak).
- Errors: `{"error": {"code": "WEEK_CLOSED", "message": "Human readable", "details": {}}}`.
- Error codes: `UNAUTHENTICATED`, `ACCOUNT_NOT_FOUND`, `VALIDATION_FAILED`, `INVALID_TIMESTAMP`, `WEEK_CLOSED`, `NOT_FOUND`, `CONFLICT`, `ADOPTION_WINDOW_CLOSED`, `ANALYSIS_FAILED`, `NOT_WASTE`, `IMAGE_TOO_LARGE`, `RATE_LIMITED`, `INTERNAL`.

## 2. Endpoints

| Method & path | Purpose | Req |
|---|---|---|
| `GET /health` | Liveness plus a DB check. No auth. | — |
| `GET /v1/taxonomy` | Categories and subcategories (plan §3). | SFR5.1 |
| `GET /v1/me` | `200 {id,email,displayName,createdAt}` or `404 ACCOUNT_NOT_FOUND`. | SFR2.1–2.2 |
| `POST /v1/account` | Body `{consent: true}`. Creates and links the account from the verified token (idempotent: returns `200` if it exists, `201` if created). | SFR1.1–1.2 |
| `DELETE /v1/account` | Deletes all data and the Firebase user. Returns `204`. | SFR4.1–4.2, NFR4 |
| `GET /v1/export` | Full JSON export (profile, entries, reports, hotspots, comparisons, recommendations, adoptions, impacts). | NFR16 |
| `GET /v1/weeks/current` | `{weekStart, weekEnd, timezone:"Asia/Manila", serverNow}`. | SFR10.3 |
| `GET /v1/entries?weekStart=` | Entries for a week (default: current), newest first. | SFR10.1–10.2 |
| `PUT /v1/entries/{id}` | Create or update (idempotent upsert). Body `{name, subcategory, quantity, source, createdAt}`. The server derives `category` and `weekStart`. Create returns `201`, update `200`. Updates may not change `createdAt`. | SFR5, SFR6, SFR11 |
| `DELETE /v1/entries/{id}` | Returns `204`, also when the entry is already gone (idempotent). `409 WEEK_CLOSED` if the entry's week is closed. | SFR11.2–11.3 |
| `POST /v1/entries/sync` | Batch for the offline outbox: `{upserts:[EntryPut & {id}], deletes:[id]}` → `{results:[{id, status:"OK"\|"WEEK_CLOSED"\|"INVALID"\|..., entry?}]}`. Items are processed independently, and the batch never fails as a whole because of one item. | SFR6.3–6.4, NFR8 |
| `POST /v1/photo-analysis` | `multipart/form-data`, field `image` (JPEG, ≤ 2 MB). Returns `200 {suggestion:{name, category, subcategory, quantity, confidence}, warning}`. Also `422 ANALYSIS_FAILED`, `422 NOT_WASTE`, `413 IMAGE_TOO_LARGE`, `429 RATE_LIMITED` (30 per user per day). Nothing is stored. | SFR7–8, SFR9.5 |
| `GET /v1/reports` | List `[ {weekStart, weekEnd, analyzedQuantity, hotspotCount, adoptedCount, isLatest} ]`, newest first. Runs lazy catch-up first. | SFR12.1, SFR18.2 |
| `GET /v1/reports/{weekStart}` | Full report (§3). | SFR12–17 |
| `POST /v1/reports/{weekStart}/adoptions` | `{interventionIds:[uuid]}` → the updated report. `409 ADOPTION_WINDOW_CLOSED` if the report is not the latest. | SFR16 |
| `DELETE /v1/reports/{weekStart}/adoptions/{interventionId}` | Un-adopt within the window. | SFR16 |
| `POST /internal/jobs/weekly-reports` | Header `X-Job-Key`. Optional `{weekStart}`. Generates missing reports. | SFR12.1 |
| `POST /internal/dev/*` | **Only when `DEV_TOOLS_ENABLED=true` (never in prod):** `clock` (set or clear override), `seed-history {email, weeks:3}` (synthetic entries in past weeks, then report generation), `reports/generate {email, weekStart}`. | testing |

## 3. Report payload (shape)

```json
{
  "weekStart": "2026-09-27", "weekEnd": "2026-10-03", "revision": 1, "isLatest": true,
  "stats": {
    "analyzedTotals": {"frequency": 42, "quantity": 118},
    "categories": [{"category": "RESIDUAL", "frequency": 20, "quantity": 70, "sharePct": 59.3}],
    "subcategories": [{"code": "RES_SACHETS", "category": "RESIDUAL", "frequency": 12, "quantity": 40}],
    "special": {"frequency": 1, "quantity": 2}
  },
  "hotspots": [{
    "rank": 1, "subcategory": "RES_SACHETS", "criteria": ["MOST_FREQUENT","HIGHEST_QUANTITY","AVOIDABLE"],
    "frequency": 12, "quantity": 40, "score": 1.0,
    "recommendations": [{
      "interventionId": "…", "code": "RES_SACHETS_REFILL_STATION", "type": "REDUCE",
      "title": "…", "description": "…", "howTo": ["…"], "costLevel": "SAVES_MONEY", "effort": "LOW",
      "note": "You logged 12 sachets — mostly shampoo…", "continued": false, "adopted": true
    }]
  }],
  "comparison": {
    "previousWeekStart": "2026-09-20",
    "total": {"previous": 130, "current": 118, "delta": -12, "deltaPct": -9.2, "trend": "DECREASED"},
    "categories": [ … ], "subcategories": [ … ]
  },
  "impacts": [{
    "interventionId": "…", "title": "…", "targetSubcategory": "RES_PLASTIC_BAGS",
    "baselineWeekStart": "2026-09-20", "baselineQuantity": 15, "followupQuantity": 9, "result": "DECREASED"
  }]
}
```

## 4. Contract change process

1. Open a PR in `menosan-api` that updates `docs/api-contract.md`, adds a line to `docs/CHANGELOG-contract.md`, and implements the change **backward-compatibly** (additive fields only after the Android beta is released on 9/26).
2. Open a matching issue in `menosan-android` labeled `contract-change`.
3. Breaking changes after 9/26 require human approval.

## 5. Clarifications (BE-0, part of v1)

These fill gaps in the plan and don't change anything above. They're also logged in `docs/DECISIONS.md`.

### 5.1 HTTP status per error code
Clients should switch on `error.code`, not only on the status.

| Code | Status |
|---|---|
| `UNAUTHENTICATED` | 401 |
| `ACCOUNT_NOT_FOUND` | 404 |
| `VALIDATION_FAILED` | 400 (415 when the `Content-Type` isn't JSON) |
| `INVALID_TIMESTAMP` | 422 |
| `WEEK_CLOSED` | 409 |
| `NOT_FOUND` | 404 (also for unknown paths) |
| `CONFLICT` | 409 |
| `ADOPTION_WINDOW_CLOSED` | 409 |
| `ANALYSIS_FAILED` | 422 |
| `NOT_WASTE` | 422 |
| `IMAGE_TOO_LARGE` | 413 |
| `RATE_LIMITED` | 429 |
| `INTERNAL` | 500 (501 while an endpoint is still a stub during development) |

- `details` is always a JSON object. It is `{}` unless noted. For example, `VALIDATION_FAILED` on `POST /v1/account` sends `{"field":"consent"}`.
- Messages are short, user-safe English. They never echo request content.

### 5.2 `GET /health`
- `200 {"status":"ok","db":"ok"}` when the database answers.
- `503 {"status":"degraded","db":"down"}` otherwise.

### 5.3 `GET /v1/taxonomy`
Returns the bundled `taxonomy.json` exactly, including the top-level `"timezone": "Asia/Manila"`:
`{version, timezone, categories:[{code,label,analyzed}], subcategories:[{code,category,label,examples,avoidable,sortOrder}]}`.

### 5.4 `POST /v1/account`
- `consent` must be `true`. A missing body, `false`, or a missing field returns `400 VALIDATION_FAILED` and creates nothing.
- The response body for `200` and `201` has the same shape as `GET /v1/me`.
- `email` and `displayName` come from the verified Firebase token, never from the request body.

### 5.5 `GET /v1/me` and `GET /v1/weeks/current`
- `createdAt` and `serverNow` are ISO-8601 UTC instants with at most millisecond precision.
- `displayName` may be `null`.
- Example: `{"weekStart":"2026-09-27","weekEnd":"2026-10-03","timezone":"Asia/Manila","serverNow":"2026-09-30T04:00:00Z"}`.

### 5.6 Request IDs
- Every response carries `X-Request-Id`.
- Clients may send their own `X-Request-Id` (1–64 letters, digits, `-` or `_`), and the server echoes it back. Anything else is replaced with a server-generated UUID, and the request is never rejected because of it.
- Include the ID in bug reports.

### 5.7 Reports (BE-3)
Added 2026-09-23 by BE-3. These fill gaps in §3 and don't change its shape.

- `GET /v1/reports` returns a bare JSON array (newest first), exactly as in §2. It is `[]` when there are no reports.
- `isLatest` is `true` only for the report whose `weekStart` is the current week's start minus 7 days. Only that report accepts adoptions (plan I7).
- `GET /v1/reports/{weekStart}`:
  - `weekStart` must be a Sunday in `YYYY-MM-DD` format. Otherwise the response is `400 VALIDATION_FAILED` with `details.field = "weekStart"`.
  - `404 NOT_FOUND` when the week is still open, when the user logged nothing that week, or when the report would belong to someone else.
  - A missing report for a closed week that has entries is generated before responding.
- `stats` (plan §5.1):
  - `categories` always lists the three analyzed categories in the order `BIODEGRADABLE`, `RECYCLABLE`, `RESIDUAL`, including those with zero entries. `sharePct` is `0.0` when nothing analyzed was logged.
  - `subcategories` lists only analyzed subcategories with at least one entry, ordered by quantity (desc), then frequency (desc), then code.
  - SPECIAL waste appears only in `special`.
- `hotspots` (plan §5.2): at most 3, ordered by `rank`. `criteria` holds `MOST_FREQUENT`, `HIGHEST_QUANTITY`, and `AVOIDABLE`, in that order. `score` has at most 4 decimals. The list is empty for a week with only SPECIAL waste. `recommendations` (1–3, ordered by rank) may be empty if no curated item could be picked.
- `comparison` (plan §5.3) is `null` when the previous week has no analyzed entries. Otherwise the rows are:
  - `total: {previous, current, delta, deltaPct, trend}`
  - `categories: [{category, previous, current, delta, deltaPct, trend}]`: all three analyzed categories, in the same order as `stats`.
  - `subcategories: [{code, category, previous, current, delta, deltaPct, trend}]`: every analyzed subcategory present in either week, ordered by code.
  - All values are quantities (pieces). `deltaPct` is rounded to 1 decimal (half away from zero) and is `null` when `previous` is 0. `trend` is `DECREASED`, `SAME`, or `INCREASED`.
- `impacts` (plan §5.4) lists the interventions adopted on the previous week's report, measured in this week, ordered by `targetSubcategory`, then `interventionId` (same order as `measureImpact()`). `baselineQuantity` is the value stored at adoption time. `followupQuantity` is `0` when the target wasn't logged this week.
  - If nothing at all was logged the following week, there is no following report and no impact rows exist. In that case the client shows "Not measured" on the adopted cards of the earlier report once that following week has closed.
- `revision` starts at 1 and increases each time a late offline sync regenerates the report (plan §5.6). Regeneration keeps adoptions and the recommendations of hotspots that still exist. A hotspot that is no longer in the top 3 is removed along with its recommendations, but adoptions of its interventions are kept and still measured.
- The same rules are implemented for offline reports on Android and are pinned by `docs/analytics-test-vectors.json`.

### 5.8 `POST /internal/jobs/weekly-reports`
- Header `X-Job-Key` must equal the server's `JOB_KEY`. If it is missing or wrong, the response is `401 UNAUTHENTICATED`. If the server has no `JOB_KEY` configured, the endpoint returns `404 NOT_FOUND`.
- The body is optional: `{"weekStart":"YYYY-MM-DD"}`. An empty body means the week that just closed. A `weekStart` that isn't a Sunday, or whose week hasn't closed yet, returns `400 VALIDATION_FAILED`.
- The response is `200 {"weekStart":"2026-09-27","created":12}`. The job is idempotent: it only creates reports that are missing.

### 5.9 Adoption (`POST /v1/reports/{weekStart}/adoptions`, `DELETE …/adoptions/{interventionId}`)
- `weekStart` must be a Sunday date (`YYYY-MM-DD`). Anything else returns `400 VALIDATION_FAILED` with `details.field = "weekStart"`.
- POST body: `{"interventionIds": [uuid, …]}` with 1–9 ids. An empty or missing list, more than 9 ids, or an id that isn't a UUID returns `400 VALIDATION_FAILED` with `details.field = "interventionIds"`.
- No report for that week (including another user's report) returns `404 NOT_FOUND`.
- The report isn't the latest one (I7: `weekStart == currentWeekStart − 7 days`) → `409 ADOPTION_WINDOW_CLOSED`. This applies to both POST and DELETE.
- Every id must be one of the report's recommendations. Otherwise the request returns `400 VALIDATION_FAILED` (`details.field = "interventionIds"`) and nothing is adopted (all-or-nothing).
- Both are idempotent. Adopting again keeps the original adoption and baseline, and un-adopting something that isn't adopted still returns `200`.
- Both return `200` with the updated full report (§3), where `hotspots[].recommendations[].adopted` reflects the change.

### 5.10 Staging dev tools (`/internal/dev/...`, BE-5)
Not for the Android app. They exist only when `DEV_TOOLS_ENABLED=true` and `APP_ENV` isn't `prod`. Otherwise every path returns `404 NOT_FOUND`. Every call needs `X-Job-Key`, with the same rules as §5.8 (`404` without a configured `JOB_KEY`, `401 UNAUTHENTICATED` for a missing or wrong key). Accounts are found by email, case-insensitively: an unknown email returns `404 NOT_FOUND`, and several accounts with the same email return `409 CONFLICT`. Bad input returns `400 VALIDATION_FAILED` with `details.field` when one field is at fault. Usage: `docs/ENVIRONMENTS.md` §6.

| Call | Body | Response `200` |
|---|---|---|
| `GET /internal/dev/clock` | — | `{serverNow, overridden, currentWeekStart}` |
| `POST /internal/dev/clock` | `{"now":"<ISO instant>"}` sets the server-wide time, which keeps ticking from there. `{}`, `{"now":null}`, or no body restores real time. | same as GET |
| `POST /internal/dev/seed-history` | `{email, weeks = 3 (1–8), adopt = true, seed = 1}` | `{userId, weeks:[{weekStart, entries, analyzedQuantity, hotspots:[code], adopted:[interventionCode], impacts}]}`, oldest first |
| `POST /internal/dev/reset` | `{email}` | `{userId, reportsDeleted, entriesDeleted}` |
| `POST /internal/dev/reports/generate` | `{email, weekStart, regenerate = false}` | the full report (§3) |

- `seed-history` logs synthetic household entries (same `seed` → same entries) in the last `weeks` closed weeks, then generates their reports oldest first. With `adopt`, every report except the latest gets its top recommendation adopted (baseline = that hotspot's quantity), and the next week logs less of that subcategory, so the next report shows a `DECREASED` impact. `409 CONFLICT` (`details.weekStart`) if the account already has entries in one of those weeks. Reset it first.
- `reset` deletes the account's entries and reports, including hotspots, recommendations, adoptions, and impacts. The account itself stays.
- `reports/generate`: a week that hasn't closed returns `400`. `regenerate: true` rebuilds an existing report as after a late sync (plan §5.6, which also refreshes the next week's report and bumps `revision`). `404` if nothing was logged that week.

## 6. Clarifications (BE-1, part of v1)

Additive only: these define shapes and error cases the plan left open. They're also logged in `docs/DECISIONS.md`.

### 6.1 Entry object
Returned by `PUT /v1/entries/{id}`, `GET /v1/entries`, and `POST /v1/entries/sync`:

```json
{"id": "8b0c…", "name": "Coffee 3-in-1 sachet", "category": "RESIDUAL", "subcategory": "RES_SACHETS",
 "quantity": 3, "source": "MANUAL", "createdAt": "2026-09-29T01:00:00Z", "weekStart": "2026-09-27",
 "updatedAt": "2026-09-29T01:00:02Z", "editable": true}
```

- `category` and `weekStart` are derived by the server. `editable` is `true` only when `weekStart` is the server's current week.
- `createdAt` is stored with millisecond precision. Any ISO-8601 instant with `Z` or an offset is accepted and returned in UTC.

### 6.2 `GET /v1/entries?weekStart=`
- Response: `{weekStart, weekEnd, editable, entries: [Entry]}`. Entries are newest first (`createdAt` desc).
- `weekStart` defaults to the current week. Any past week is allowed. A value that isn't a Sunday `YYYY-MM-DD` returns `400 VALIDATION_FAILED` (`details.field = "weekStart"`).

### 6.3 `PUT /v1/entries/{id}`
- `{id}` must be a canonical UUID (client-generated). Otherwise `400 VALIDATION_FAILED` (`details.field = "id"`).
- Field rules → `400 VALIDATION_FAILED` with `details.field`: `name` (trimmed, 1–60 characters), `subcategory` (a taxonomy code), `quantity` (1–999), `source` (`MANUAL` | `PHOTO`), `createdAt` (ISO-8601 instant). A wrong JSON type also returns `400`, without `details.field`.
- **Create** (id not seen before): `createdAt` more than 5 minutes ahead of the server → `422 INVALID_TIMESTAMP` (`details.reason = "FUTURE"`). More than 14 days old → `422 INVALID_TIMESTAMP` (`details.reason = "TOO_OLD"`). A create into a closed week within 14 days is accepted (`201`) and refreshes that week's report (plan §5.6).
- **Update** (id exists for this user): `createdAt` must equal the stored value, else `400 VALIDATION_FAILED` (`details.field = "createdAt"`). If the entry's week is not the current week → `409 WEEK_CLOSED` (`details.weekStart`).
- **Unchanged replay:** a PUT whose fields all equal the stored entry returns `200` with the entry in any week, even a closed one, so retrying a create that already succeeded is never an error.
- An id that belongs to another user → `409 CONFLICT` (`details.field = "id"`). The other user's entry is untouched.

### 6.4 `DELETE /v1/entries/{id}`
- `204` when deleted, when the id doesn't exist, and when it belongs to another user (nothing is deleted then).
- `409 WEEK_CLOSED` (`details.weekStart`) when the entry is in a past week.

### 6.5 `POST /v1/entries/sync`
- Request: `{"upserts": [{id, name, subcategory, quantity, source, createdAt}], "deletes": ["<id>"]}`. Both lists are optional. At most 500 items in total, otherwise the whole request gets `400 VALIDATION_FAILED`.
- Upserts run first, then deletes, each in request order and each on its own (same rules as `PUT` and `DELETE`).
- Response: `{"results": [{id, op, status, entry, message}]}` with one result per item, in the same order (upserts, then deletes).
  - `op`: `UPSERT` | `DELETE`. `id`: the item's id, or `null` if the item had no string id.
  - `status`: `OK` | `WEEK_CLOSED` | `INVALID` (any `VALIDATION_FAILED` rule, a malformed item, or a bad id) | `INVALID_TIMESTAMP` | `CONFLICT` | `ERROR` (server-side problem: keep the item and retry later).
  - `entry`: the saved entry for an `OK` upsert. For `WEEK_CLOSED`, the **unchanged server copy**, so the app can revert its local change. Otherwise `null`.
  - `message`: a short, user-safe reason when `status` isn't `OK`, else `null`.
- Deleting a missing id is `OK`. Retrying a whole batch is safe: nothing is duplicated.

### 6.6 `GET /v1/export`
- `200` with `Content-Disposition: attachment; filename=menosan-export-YYYY-MM-DD.json` (Manila date) and `Cache-Control: no-store`.
- Body:

```json
{
  "format": "menosan-export", "exportVersion": 1, "exportedAt": "…", "timezone": "Asia/Manila",
  "profile": {"id", "email", "displayName", "createdAt", "consentedAt"},
  "entries": [{"id", "name", "category", "subcategory", "quantity", "source", "createdAt", "weekStart", "updatedAt", "receivedAt"}],
  "reports": [{
    "weekStart", "weekEnd", "revision", "algorithmVersion", "generatedAt", "regeneratedAt",
    "stats": {…}, "comparison": {…} | null,
    "hotspots": [{"rank", "subcategory", "criteria", "frequency", "quantity", "score",
                  "recommendations": [{"interventionId", "code", "title", "rank", "note", "continued", "source"}]}],
    "adoptions": [{"interventionId", "code", "title", "targetSubcategory", "baselineWeekStart", "baselineQuantity", "adoptedAt",
                   "impact": {"followupWeekStart", "baselineQuantity", "followupQuantity", "result"} | null}]
  }]
}
```

- Entries are oldest first, reports by `weekStart` ascending. `stats` and `comparison` are the stored report JSON (plan §5.1, §5.3). Adoptions are listed under the report they were made on (the baseline week).

### 6.7 `DELETE /v1/account`
- Needs a valid token but **not** an account, so it's safe to retry: `204` also when the Menosan account is already gone.
- Deletes every database row of the account first, then the Firebase user. If the Firebase call fails, the response is `500 INTERNAL` (the data is already gone) and the app should retry. After `204`, the app signs out and clears local data.

## 7. Clarifications (BE-2, part of v1)

Additive only. They're also logged in `docs/DECISIONS.md`.

### 7.1 `POST /v1/photo-analysis`
- Request: `multipart/form-data` with one file part named `image`. Other parts are ignored. The server checks the bytes: the file must start with the JPEG marker `FF D8 FF`, whatever the part's `Content-Type` says.
- Response `200`:

```json
{"suggestion": {"name": "Coffee 3-in-1 sachet", "category": "RESIDUAL", "subcategory": "RES_SACHETS", "quantity": 5, "confidence": 0.82},
 "warning": "This is an AI suggestion and it can be wrong. Please check the name, category, subcategory, and quantity before saving."}
```

  - `name` is 1–60 characters, `subcategory` is a taxonomy code (Special codes included), `category` always matches it, `quantity` is 1–999, and `confidence` is 0–1. So the suggestion always passes `PUT /v1/entries/{id}` validation unchanged (with `source = "PHOTO"`).
  - `warning` is always present (SFR9.5). Show it next to the review form.
- Errors (`error.details` in brackets):
  - `400 VALIDATION_FAILED` (`{"field":"image"}`): no `image` part, an empty file, a file that isn't a JPEG, or a malformed multipart body.
  - `415 VALIDATION_FAILED` (`{"field":"image"}`): the request isn't `multipart/form-data`.
  - `413 IMAGE_TOO_LARGE` (`{"maxBytes":2097152}`): the image is over 2 MB (2 × 1024 × 1024 bytes), or the whole body is over 2 MB + 64 KB.
  - `422 NOT_WASTE`: the photo doesn't show household waste, or is too unclear to tell.
  - `422 ANALYSIS_FAILED`: Gemini failed, timed out (15 s), or returned something that didn't pass validation. Retrying with the same or a clearer photo is fine. Manual logging always works.
  - `429 RATE_LIMITED` (`{"limit":30,"resetsAt":"2026-09-30T16:00:00Z"}`): 30 analyses per user per Manila calendar day. `resetsAt` is the next Manila midnight. Every analysis that reaches Gemini counts, including `NOT_WASTE` and `ANALYSIS_FAILED`. Rejected uploads (400/413/415) don't count.
- Nothing is stored: the image is held in memory for the Gemini call only and is never logged.
