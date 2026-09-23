# Menosan — Development Plan

> **Audience:** the AI coding agents (and humans) building Menosan.
> **Scope:** two repositories — `menosan-api` (Kotlin + Ktor backend) and `menosan-android` (Kotlin + Jetpack Compose app).
> **Status:** v1.2 — 2026-09-23 (offline provisional reports with comparison and impact, I12 / §5.7). This file is the working source of truth during development. Where it differs from the SRS/SDP, **this plan wins**; the team will update the SRS/SDP after release to match what was actually built (see §2.2).
> **Put a copy of this file at `docs/DEVELOPMENT_PLAN.md` in both repositories.**

---

## 0. How agents should use this document

1. Read §1–§4 fully before starting any task. They hold the rules that every workstream depends on (week math, taxonomy, hotspot rules).
2. Find your workstream in §9 (backend) or §10 (Android). Each task lists **inputs, outputs, Definition of Done (DoD), and the files/packages you own**. Do not edit files owned by another workstream except through the contract-change process in §8.4.
3. The **API contract (§8)** is frozen at the end of BE-0. Android builds against it. Any change requires a PR that updates `docs/api-contract.md` in `menosan-api` and a note in `docs/CHANGELOG-contract.md`.
4. When a requirement is ambiguous and not covered here, choose the simplest behavior consistent with §2 and record it in `docs/DECISIONS.md` in your repo (one line: date, decision, reason).
5. Requirement IDs (UFR/SFR/NFR) from the SRS are referenced throughout. §12 maps each one to where it is implemented and tested.

---

## 1. Product summary

Menosan is an Android app that helps households in Dumaguete City **prevent** waste at the source. Users log household waste (manually — works offline — or by photo via Gemini). Every week (Sunday–Saturday, Philippine Time) the system builds a report, identifies **waste hotspots** (by subcategory), recommends **prevention / reduction / reuse interventions** from a curated library, lets the user **adopt** interventions, and in the next week's report shows whether the targeted subcategory **decreased, remained the same, or increased**.

Core loop (from *User Flow*):

```
Sign in with Google ─► Log waste (manual / photo) ─► Week ends (Sat 23:59 PHT)
      ▲                                                     │
      │                                                     ▼
Next week's report shows impact ◄─ User adopts ≥1 ◄─ Report: stats, hotspots,
of adopted interventions           intervention      comparison, interventions
```

Privacy is a product feature: all data is private to the account, there are no rankings, leaderboards, or cross-household comparisons (NFR1–3), and in-app language is supportive, never shaming (SDP §VII).

---

## 2. Fixed decisions

### 2.1 Decisions table

| Topic | Decision |
|---|---|
| Repos | `menosan-api` (backend), `menosan-android` (app). Rename if your GitHub repos differ. |
| Build order | **Backend first**, then Android. Android may start scaffolding (AN-0) once the API contract is frozen at the end of BE-0. |
| Backend stack | Kotlin 2.x, Ktor 3.x (Netty), kotlinx.serialization, Exposed (DSL) + HikariCP + PostgreSQL JDBC, Flyway migrations, Firebase Admin SDK (Java), Google Gen AI Java SDK (`com.google.genai:google-genai`), JUnit 5 + Ktor `testApplication`. Gradle Kotlin DSL, JDK 21. |
| Database | Neon Postgres. Two branches: `main` (prod) and `dev` (staging/tests). Store all instants as `timestamptz` (UTC). |
| Auth | Firebase Authentication, Google provider only. App sends the Firebase **ID token** as `Authorization: Bearer <token>`; the API verifies it with Firebase Admin. No passwords are ever handled (NFR5). |
| AI | Gemini, called **only from the backend** (the API key never ships in the APK). Used for (a) photo analysis and (b) choosing and lightly personalizing interventions **from the curated library only**. Model name comes from env `GEMINI_MODEL` (use the current Flash-class model on the team's key). |
| Interventions | Curated library stored in Neon (seeded from §6.4). Gemini ranks candidates and writes a short personal note. If Gemini fails or returns invalid output, a deterministic rule-based ranking is used. Gemini **never invents** an intervention (NFR12). |
| Android stack | Kotlin, Jetpack Compose + Material 3, MVVM with unidirectional state, Hilt, Room (SQLite), WorkManager, Retrofit + OkHttp + kotlinx.serialization, Firebase Auth + Credential Manager (Sign in with Google), Activity Result APIs for camera/photo picker. `minSdk 26` (Android 8.0, NFR15), `targetSdk` = latest stable. |
| Hosting | **Not decided yet.** The backend is built host-agnostic: a Dockerfile plus a fat JAR, all config from env vars, and the weekly job can be triggered **both** by an in-process scheduler **and** by `POST /internal/jobs/weekly-reports` (so a GitHub Actions cron can drive it on any host). Reports are also generated lazily on read (§5.5), so a missed or sleeping cron never loses a report. Suggested default: **Google Cloud Run** (same Google project as Firebase, scales to zero). Team must decide by **Thu 9/24 noon** (§11). |
| Offline behavior | **Offline-first for manual logging and weekly hotspots.** Manual entries are saved on the device and synced later. When a week closes and the device is offline, the app builds a **provisional offline report** on the device: totals, hotspots, and, when the local data allows it, the comparison with the previous week, the impact of last week's adopted interventions, and a recap of last week's report. It never includes new intervention recommendations or adoption. Once online, the server report (with interventions) replaces it. Sign-in, account creation, photo logging, adoption, export, and deletion need internet. |
| Tester distribution | Firebase App Distribution. |
| Language | English UI and content. Local terms are allowed where they are clearer to Dumaguete households (e.g., *palengke*, *sari-sari store*, *sando bag*, *baon*, *carinderia*, *bayong*, *tingi*). |
| Time zone | All week logic uses `Asia/Manila` (UTC+8, no DST) regardless of the device or server time zone. |

### 2.2 Interpretations of the source documents (to be reflected in the SRS later)

| # | Source says | This plan implements |
|---|---|---|
| I1 | Waste "type" (SRS) / "name" (Data Fields) | One field, `name`: a short free-text label (e.g., "Coffee 3-in-1 sachet"). |
| I2 | Hotspots are "waste categories" (SRS) | Hotspots are computed at the **subcategory** level. Main categories are used for summaries and charts. |
| I3 | Categories | Main categories: **Biodegradable, Recyclable, Residual**, plus **Special**. Special waste can be logged but is **excluded** from totals, comparisons, hotspots, interventions, and impact. It is shown in the report as a separate "not included in analysis" line (supports NFR13). |
| I4 | "Avoidable" criterion | Each subcategory carries a seeded `avoidable` flag (§3). |
| I5 | Intervention Library "could just be an LLM" (Data Fields) vs. NFR12 "curated catalog only" | Curated catalog plus Gemini as selector and personalizer (see §2.1). |
| I6 | Baseline week | The week of the report on which the intervention was adopted. Impact = that subcategory's quantity in the **immediately following** week vs. the baseline week (SFR14.3, SFR17.1). |
| I7 | Adoption window | A user can adopt (or un-adopt) interventions on a report only while that report is the **latest** one: `isLatest ⇔ report.weekStart == currentWeekStart − 7 days`, i.e., only during the following week. After that, adoptions are locked. |
| I8 | "Takes into account interventions adopted in the previous week" (User Flow 3c) | The engine receives last week's adoptions and their impact. Improved → suggest continuing plus a next step. Same or increased → prefer different, lower-effort, lower-cost options. Adopted interventions are never silently dropped. |
| I9 | Data export (NFR16) | `GET /v1/export` returns JSON. The app saves it with the Storage Access Framework. |
| I10 | Late offline sync (SFR6.4) | Entries created offline are accepted into their original (possibly closed) week if `createdAt` is within the last 14 days. The affected week's report and the next week's report are regenerated (§5.6). |
| I11 | Quantity | Positive whole number of pieces, 1–999 (SFR5.1). |
| I12 | Reports when offline (team decision 2026-09-23) | If the week has closed and the server report isn't available because the device is offline, the app generates a **provisional report on the device** from locally stored data. It includes the week totals (category and subcategory, with the Special line), the **hotspots**, and, **when the data is available locally**, the week-over-week comparison, the impact of interventions adopted on the previous report, and a recap of the previous week's report (its hotspots and adopted interventions). **New intervention recommendations and adoption are online-only.** The server report replaces the local one as soon as the device is online and pending entries have synced. See §5.7. |

---

## 3. Waste taxonomy (single source of truth)

Stored in `menosan-api/src/main/resources/taxonomy.json`, seeded into the `waste_subcategories` table by migration, served by `GET /v1/taxonomy`, and **bundled as an identical copy** in `menosan-android/app/src/main/assets/taxonomy.json` for offline use. Codes are stable identifiers and must never be renamed. Labels may change.

| Code | Main category | Label | Examples (used in Gemini prompt & UI hints) | Avoidable |
|---|---|---|---|---|
| `BIO_FOOD_LEFTOVERS` | BIODEGRADABLE | Leftover cooked food | leftover rice, ulam, plate waste | ✅ |
| `BIO_SPOILED_FOOD` | BIODEGRADABLE | Spoiled or expired food | rotten vegetables, expired bread, sour milk | ✅ |
| `BIO_PEELS_SCRAPS` | BIODEGRADABLE | Fruit & vegetable peels/scraps | banana peels, onion skins, fish scales, eggshells | ❌ |
| `BIO_YARD_WASTE` | BIODEGRADABLE | Yard & garden waste | dry leaves, grass, twigs | ❌ |
| `BIO_OTHER` | BIODEGRADABLE | Other biodegradable | coconut husk, other | ❌ |
| `REC_PET_BOTTLES` | RECYCLABLE | Plastic bottles | water, softdrink, juice PET bottles | ✅ |
| `REC_RIGID_PLASTICS` | RECYCLABLE | Rigid plastic containers | ice cream tubs, detergent bottles, food tubs | ✅ |
| `REC_PAPER_CARDBOARD` | RECYCLABLE | Paper & cardboard | boxes, receipts, flyers, newspapers | ✅ |
| `REC_GLASS` | RECYCLABLE | Glass bottles & jars | softdrink bottles, sauce jars | ✅ |
| `REC_METAL_CANS` | RECYCLABLE | Metal cans | sardine, corned beef, softdrink cans | ❌ |
| `REC_OTHER` | RECYCLABLE | Other recyclable | other | ❌ |
| `RES_SACHETS` | RESIDUAL | Sachets & small packets | shampoo, 3-in-1 coffee, condiment, detergent sachets | ✅ |
| `RES_PLASTIC_BAGS` | RESIDUAL | Plastic bags | sando bags, labo bags, ice bags | ✅ |
| `RES_SNACK_WRAPPERS` | RESIDUAL | Snack & candy wrappers | chichirya packs, biscuit and candy wrappers | ✅ |
| `RES_STYROFOAM` | RESIDUAL | Styrofoam & takeout containers | styro boxes, clamshells, takeout cups | ✅ |
| `RES_DISPOSABLES` | RESIDUAL | Disposable cutlery, cups & straws | plastic spoons, straws, cups | ✅ |
| `RES_TISSUE` | RESIDUAL | Tissue & paper towels | tissue, table napkins, wet wipes | ✅ |
| `RES_DIAPERS_SANITARY` | RESIDUAL | Diapers & sanitary products | disposable diapers, sanitary pads | ❌ |
| `RES_OTHER` | RESIDUAL | Other residual | other | ❌ |
| `SPC_BATTERIES` | SPECIAL | Batteries | AA batteries, button cells | — |
| `SPC_ELECTRONICS` | SPECIAL | Electronic waste | chargers, cables, old phones | — |
| `SPC_BULBS` | SPECIAL | Light bulbs & tubes | fluorescent tubes, bulbs | — |
| `SPC_MEDICAL` | SPECIAL | Medical waste | expired medicine, syringes, used masks | — |
| `SPC_CHEMICAL` | SPECIAL | Chemical containers | paint cans, insecticide, aerosol cans | — |
| `SPC_OTHER` | SPECIAL | Other special waste | other | — |

`taxonomy.json` shape:

```json
{
  "version": 1,
  "categories": [
    {"code": "BIODEGRADABLE", "label": "Biodegradable", "analyzed": true},
    {"code": "RECYCLABLE", "label": "Recyclable", "analyzed": true},
    {"code": "RESIDUAL", "label": "Residual", "analyzed": true},
    {"code": "SPECIAL", "label": "Special", "analyzed": false}
  ],
  "subcategories": [
    {"code": "RES_SACHETS", "category": "RESIDUAL", "label": "Sachets & small packets",
     "examples": ["shampoo sachet", "3-in-1 coffee", "condiment packet"], "avoidable": true, "sortOrder": 1}
  ]
}
```

---

## 4. Time and week rules (used by both repos)

- **Logging week** = Sunday 00:00:00.000 to Saturday 23:59:59.999, `Asia/Manila` (SFR5.3).
- `weekStart(instant) = instant.atZone(Asia/Manila).toLocalDate().with(previousOrSame(SUNDAY))`; `weekEnd = weekStart + 6 days`.
- In UTC terms, a week runs from **Saturday 16:00 UTC** to the next Saturday 15:59:59 UTC. Unit-test these boundaries on both sides: Sat 23:59:59 PHT belongs to the current week and Sun 00:00:00 PHT belongs to the next.
- `createdAt` is set by the **client** at the moment the user saves the entry (so offline entries keep their original time, NFR11). The server derives `week_start` from `createdAt`; it never trusts a client-sent week.
- The server rejects `createdAt` values more than 5 minutes in the future, and creates older than 14 days (`422 INVALID_TIMESTAMP`).
- **Editable** means the entry's `week_start` equals the server's current week start (SFR11.1–11.3). Otherwise the server returns `409 WEEK_CLOSED`.
- A week is **closed** when server now ≥ `weekEnd + 1 day` at 00:00 PHT. Reports exist only for closed weeks (SFR12.1).
- Android uses `java.time` (available natively on API 26) with `ZoneId.of("Asia/Manila")`. It **never** uses the device's default zone for week math.
- Staging only: the server clock can be overridden with env `CLOCK_OVERRIDE` (ISO instant) or `POST /internal/dev/clock` so testers can simulate week rollover. All code must obtain "now" from an injected `Clock`, never `Instant.now()` directly.

---

## 5. Analytics rules (deterministic, NFR9)

Implemented as **pure functions** in `menosan-api` package `app.menosan.analytics`, with no I/O, and fully unit-tested. Every stored report records `algorithm_version` (start at `1`).

§5.1–§5.4 (aggregation, hotspots, comparison, impact) are **also implemented on Android** for offline reports (§5.7). Both implementations must produce identical results for the shared test vectors in `docs/analytics-test-vectors.json`.

### 5.1 Weekly aggregation (SFR12.2)

For a user and a closed week W, take all entries with `week_start = W`.

- Per **subcategory**: `frequency` = number of entries, `quantity` = sum of pieces.
- Per **main category** (BIODEGRADABLE, RECYCLABLE, RESIDUAL): sum of frequency and quantity over its subcategories, plus `share = quantity / analyzedTotalQuantity`, rounded to 1 decimal percent.
- `analyzedTotals` = totals over the three main categories only.
- `special` = frequency and quantity over SPECIAL, reported separately and **excluded** from everything else.

### 5.2 Hotspot identification (SFR13.1, SFR13.2)

Candidates are the non-SPECIAL subcategories with at least 1 entry in W.

```
maxF = max frequency, maxQ = max quantity over candidates
score(s) = 0.5 * f(s)/maxF + 0.5 * q(s)/maxQ        // round to 4 decimals

MOST_FREQUENT    : every s with f(s) == maxF
HIGHEST_QUANTITY : every s with q(s) == maxQ
TOP_AVOIDABLE    : the single avoidable s with the highest score (if any avoidable candidate exists)

hotspots = MOST_FREQUENT ∪ HIGHEST_QUANTITY ∪ TOP_AVOIDABLE
criteria(s) = { MOST_FREQUENT if f==maxF, HIGHEST_QUANTITY if q==maxQ, AVOIDABLE if s.avoidable }
order by: score desc, q desc, f desc, code asc      // total, deterministic ordering
keep the first 3 → rank 1..3
```

- `criteria` can hold several values. The UI shows each one as a chip ("Most frequent", "Highest quantity", "Avoidable").
- A week with only SPECIAL entries still gets a report (SFR12.1) but has no hotspots or interventions. The UI explains why.
- A week with zero entries gets **no report**.

### 5.3 Week-over-week comparison (SFR14.1–14.3)

If the immediately preceding week W−1 has at least 1 analyzed entry, compare W against W−1 for the analyzed total, each main category, and each subcategory present in either week. Each row holds `previous`, `current`, `delta = current − previous`, `deltaPct` (null when previous = 0), and `trend ∈ {DECREASED, SAME, INCREASED}`. If W−1 has no analyzed data, set `comparison = null` and the UI shows "No comparison — there is no data for the previous week." Only W−1 is ever used as a baseline.

### 5.4 Intervention impact (SFR16.2, SFR17.1–17.2)

For each adoption made on report W−1 (the baseline), with target subcategory `s`:
`baselineQty = q_{W−1}(s)`, `followupQty = q_W(s)` (0 if there are no entries), and `result = DECREASED | SAME | INCREASED`. These results are shown in report W under "How your changes went". If the user logged nothing at all in W, report W does not exist, so the impact is shown on the report W−1 detail as "Not measured — no entries were logged the following week."

### 5.5 Report generation and triggers

`ReportService.ensureReport(userId, weekStart)` is **idempotent** (unique on `user_id, week_start`). It is triggered by:

1. The scheduled job every **Sunday 00:05 PHT** (`5 16 * * 6` in UTC cron) for all users who have entries in the week that just closed.
2. `POST /internal/jobs/weekly-reports` (header `X-Job-Key`), for an external cron such as GitHub Actions.
3. **Lazy catch-up** in `GET /v1/reports` and `GET /v1/reports/{weekStart}`: any closed week that has entries but no report is generated before responding.

Generation steps, all in one DB transaction except the Gemini call:

1. Aggregate (§5.1) → 2. Hotspots (§5.2) → 3. Comparison (§5.3) → 4. Impact of W−1 adoptions (§5.4) → 5. Intervention recommendations for each hotspot (§6.2, which may call Gemini **outside** the transaction) → 6. Persist.

### 5.6 Regeneration after a late sync (SFR6.4)

When an entry is created in a closed week W (a late offline sync):

- Regenerate report W (stats, hotspots, comparison, impact) and report W+1's comparison and impact, if those reports exist.
- **Keep** existing recommendations for hotspots that still exist. Generate recommendations only for new hotspots. Keep all adoptions, since adoptions reference `target_subcategory` and are never deleted by regeneration.
- Increment `revision` on the report and set `regenerated_at`.

### 5.7 Offline provisional report (Android, I12)

- **When:** the app is opened after a week W has closed, there is no cached server report for W, and the device is offline or the API is unreachable.
- **Computation:** a Kotlin port of §5.1–§5.4 (`aggregate()`, `findHotspots()`, `compare()`, `measureImpact()`) in `core/analytics`. Pure functions with the same `ALGORITHM_VERSION`, copied from the backend implementation with package names changed.
- **Contents and data sources:**

| Section | Included when | Source |
|---|---|---|
| Week range, totals, category and subcategory breakdown, Special line | Always | Local Room entries with `weekStart = W`, including unsynced ones |
| Hotspots with criteria chips | Always (unless W has only Special entries) | Same as above |
| Comparison with W−1 (§5.3) | W−1 has analyzed data locally | Cached **server** report for W−1 (`stats`) if present; otherwise local entries for W−1. If neither exists, show the "no comparison" message. |
| Impact of interventions adopted on report W−1 (§5.4) | The cached W−1 report holds adoptions | Adoptions and `baselineQuantity` from the cached W−1 report; `followupQuantity` from local W entries (0 if none) |
| Recap of last week (W−1 hotspots and the interventions the user adopted) | A cached W−1 report exists | Cached W−1 report |
| **New intervention recommendations and adoption** | **Never offline** | Server only (curated library + Gemini) |

- **Storage:** a `LocalReport` stored in Room (`reports_cache` with `isProvisional = true`).
- **Banner:** *"Offline summary. Connect to the internet to get suggestions for your hotspots."* If the comparison or impact couldn't be computed, add: *"Some comparisons will appear once you're back online."*
- **Replacement:** when connectivity returns, `SyncWorker` first flushes pending entries, then fetches `GET /v1/reports/{W}`. The server report replaces the provisional one completely, since it is authoritative (e.g., if entries came from another device). The UI shows the full report from then on.
- **Local retention:** Room keeps entries for the **current week and the two previous** logging weeks, plus the full cached server reports (including recommendations and adoption flags) for at least the last 2 reports. Older synced entries may be pruned. This way W and W−1 are always available locally.
- **Parity:** `docs/analytics-test-vectors.json` (created in BE-3, copied to `menosan-android/app/src/test/resources/`) holds input entries (and adoptions for the impact cases) with the expected aggregation, hotspots, comparison, and impact. Both repos run it in unit tests. Any change to §5.1–§5.4 must bump `ALGORITHM_VERSION` and update the vectors in both repos.

---

## 6. Intervention engine

### 6.1 Content principles (Philippine context — mandatory for every library item)

Most target households live paycheck to paycheck, buy *tingi* (small, per-use quantities) because that is what cash flow allows, and live in a consumerist retail environment. Therefore:

1. **Free or money-saving first.** Each item has `costLevel ∈ {FREE, SAVES_MONEY, SMALL_ONE_TIME_COST}`. `SMALL_ONE_TIME_COST` is allowed only if the cost is about ₱150 or less **and** pays for itself within about a month. Never recommend buying "eco" products as the main action.
2. **Use what the household already has.** Reuse ice cream tubs, old sando bags, glass jars, old cloth, existing water bottles.
3. **Fit local routines:** *palengke* trips, *sari-sari* stores, refill stations, *carinderia* takeout, *baon*, fiestas and *handaan*, backyard space, neighbors with livestock.
4. **Never shame.** Do not moralize about sachets or budget choices. Frame each action as a small experiment ("Try this week…"), highlight savings and convenience, and phrase conditional advice with care ("If your budget allows this week…").
5. **Prevention > reduction > reuse**, but low effort matters more than purity.
6. Every item is concrete, doable within one week, and 1–3 sentences long, with an optional short "how to" list of 2–4 steps.
7. No guidance on SPECIAL, hazardous, medical, electronic, or industrial waste (NFR13). No collection schedules or ENRO or barangay coordination (NFR14).

### 6.2 Selection algorithm

Input: a hotspot (subcategory, criteria, f, q), the user's week stats, and the previous week's adoptions with their impact results.

1. `candidates` = active library items for the hotspot's subcategory (at least 3 per subcategory are guaranteed by the seed).
2. **Rule-based pre-rank** (deterministic, also the fallback): exclude an item adopted last week whose result was `INCREASED` or `SAME` unless no alternatives remain. Then order by `costLevel` (FREE, SAVES_MONEY, SMALL_ONE_TIME_COST), `effort` (LOW, MEDIUM), `type` (PREVENT, REDUCE, REUSE), then `code`.
3. **Gemini selection** with structured output. The prompt contains the hotspot data, the candidate list (id, title, description, cost, effort), the previous adoptions and their impact, and the tone rules from §6.1. The response schema:
   ```json
   {"picks": [{"interventionId": "uuid", "rank": 1, "note": "string ≤ 200 chars"}]}
   ```
   Validate that 1–3 picks were returned, every `interventionId` is in `candidates`, ranks are unique, and each note is 200 characters or fewer, contains no URLs, and adds no new actions. Any violation, timeout (8 s), or error → use the top 3 from the rule-based pre-rank, with `note = null` and `source = RULES`.
4. If an intervention adopted last week **decreased** the target and the same subcategory is still a hotspot, pin that intervention as rank 1 with a "Keep it up" flag (`continued = true`).
5. Persist each pick as a `report_recommendations` row (`source = GEMINI | RULES`).

Recommendations are generated once per hotspot and stored. They are **not** regenerated on read, so viewing a report is deterministic.

### 6.3 Adoption

- `POST /v1/reports/{weekStart}/adoptions {interventionIds: [...]}` adopts one or more recommendations (SFR16.1). This is allowed only while that report is the latest (I7). Adoption is idempotent on `(report_id, intervention_id)`.
- Each adoption stores `target_subcategory`, `baseline_week_start`, and `baseline_quantity` (SFR16.2).
- `DELETE /v1/reports/{weekStart}/adoptions/{interventionId}` un-adopts, within the same window.

### 6.4 Seed library (starter content; BE-4 expands each item into a full description and a "how to")

Format: `type · costLevel · title`. **At least 3 items for every non-SPECIAL subcategory.**

**BIO_FOOD_LEFTOVERS**
- REDUCE · SAVES_MONEY · Measure rice per person before cooking (use one cup per headcount).
- REUSE · SAVES_MONEY · Turn leftovers into the next meal (*sinangag*, *lugaw*, *torta*, fried rice with leftover *ulam*).
- PREVENT · FREE · Serve small first portions; get seconds instead of leaving food on the plate.
- REUSE · FREE · Share extra food with neighbors or feed safe scraps to pets or backyard animals.

**BIO_SPOILED_FOOD**
- PREVENT · SAVES_MONEY · Check the fridge and pantry before going to the *palengke*; bring a short list.
- PREVENT · FREE · "Oldest first": move older food to the front of the fridge or shelf.
- REDUCE · SAVES_MONEY · Buy perishables for 2–3 days at a time instead of a whole week.
- REUSE · SAVES_MONEY · Rescue very ripe fruit (overripe bananas become *turon*, *maruya*, or banana cue).

**BIO_PEELS_SCRAPS**
- REUSE · FREE · Bury peels in a small backyard compost pit or a pot for plants.
- REDUCE · SAVES_MONEY · Use more of the produce (kangkong stems, malunggay stalks, vegetable scraps for broth).
- REUSE · FREE · Give scraps to a neighbor who keeps pigs or chickens.

**BIO_YARD_WASTE**
- REUSE · FREE · Use dry leaves as mulch around plants to keep soil moist.
- REUSE · FREE · Start a simple compost pile in a corner of the yard.
- REDUCE · FREE · Leave grass clippings on the lawn as natural fertilizer.

**BIO_OTHER**
- REUSE · FREE · Add it to a backyard compost pit if it is plant- or food-based.
- PREVENT · FREE · Next time, note what the item was so Menosan can suggest something more specific.
- REUSE · FREE · Ask a neighbor who gardens or keeps animals if they can use it.

**REC_PET_BOTTLES**
- PREVENT · SAVES_MONEY · Refill a bottle you already own with water from home instead of buying bottled water.
- REDUCE · SAVES_MONEY · For family drinks, buy one 1.5 L bottle to share instead of several small ones.
- REUSE · FREE · Reuse clean bottles for storing drinking water, cooking oil, or *suka* bought *tingi*.

**REC_RIGID_PLASTICS**
- REUSE · FREE · Reuse ice cream tubs and food containers for storage and *baon*.
- REDUCE · SAVES_MONEY · Refill detergent or dishwashing liquid at a refill station using your old bottle.
- PREVENT · FREE · Bring a container when buying *ulam* or wet goods at the *palengke*.

**REC_PAPER_CARDBOARD**
- PREVENT · FREE · Say "no receipt, thanks" or choose an e-receipt when you don't need a printed one.
- REUSE · FREE · Keep boxes for storage and use the backs of papers as scratch paper for kids' schoolwork.
- PREVENT · FREE · Decline flyers and extra paper bags when shopping.

**REC_GLASS**
- REUSE · FREE · Reuse jars for storing food, *atchara*, or spices.
- PREVENT · SAVES_MONEY · Choose returnable-bottle softdrinks at the *sari-sari* store (the bottle deposit comes back to you).
- REUSE · FREE · Reuse bottles for water, oil, or *tingi* refills.

**REC_METAL_CANS**
- REDUCE · SAVES_MONEY · Swap one canned meal a week for a simple fresh dish (*monggo*, eggs, or *ginisang gulay*), often cheaper per serving.
- REUSE · FREE · Use clean cans as plant pots, scoops, or containers.
- REDUCE · FREE · Plan meals so opened cans are fully used and no second can is opened early.

**REC_OTHER**
- PREVENT · FREE · Before buying, ask: can I refuse it, reuse something I own, or get a refillable version?
- REUSE · FREE · Clean and keep sturdy items to reuse for storage.
- PREVENT · FREE · Next time, note what the item was so Menosan can suggest something more specific.

**RES_SACHETS**
- REDUCE · SAVES_MONEY · Bring an old bottle to a refill station for shampoo, dishwashing liquid, or detergent (still in small, *tingi*-sized amounts).
- REDUCE · SAVES_MONEY · For coffee and sugar, buy *takal* (scooped) portions at the *palengke* and keep them in a jar instead of 3-in-1 sachets.
- REDUCE · SAVES_MONEY · If your budget allows this week, buy the next size up of a product you use every day. Compare the price per use first.

**RES_PLASTIC_BAGS**
- PREVENT · FREE · Bring a *bayong*, eco bag, or an old sando bag when going to the *palengke* or *sari-sari* store.
- PREVENT · FREE · Say "no plastic, thanks" for small items you can carry or pocket.
- REUSE · SAVES_MONEY · Reuse sando bags as trash liners instead of buying garbage bags.

**RES_SNACK_WRAPPERS**
- REDUCE · SAVES_MONEY · Make simple home *merienda* a few times a week (*kamote*, *turon*, *pandesal*, banana cue).
- REDUCE · SAVES_MONEY · If your budget allows, buy one bigger pack and portion it into a reusable container for *baon*.
- PREVENT · FREE · Keep a water bottle and fruit on hand to reduce impulse snack buys.

**RES_STYROFOAM**
- PREVENT · FREE · Bring your own container (*baunan*) for *carinderia* or takeout food.
- PREVENT · FREE · Dine in instead of taking out when you have time.
- PREVENT · FREE · For deliveries, choose "no cutlery" and shops that use paper or reusable packaging.

**RES_DISPOSABLES**
- PREVENT · FREE · Keep a spoon-and-fork set from home in your bag or *baon* kit.
- PREVENT · FREE · Say "no straw, thanks" when ordering drinks.
- PREVENT · FREE · For *handaan* or fiestas, borrow plates and glasses from neighbors instead of buying disposables.

**RES_TISSUE**
- REDUCE · FREE · Turn old shirts into washable rags (*basahan*) for cleaning spills.
- PREVENT · FREE · Carry a small towel or *panyo* instead of tissue.
- REDUCE · FREE · Use cloth table napkins at home that can go in the regular wash.

**RES_DIAPERS_SANITARY**
- REDUCE · SAVES_MONEY · Use cloth *lampin* at home during the day when practical, and disposables for outings and nights.
- REDUCE · FREE · If comfortable, try washable cloth pads (they can be sewn from soft old cloth).
- REDUCE · SAVES_MONEY · Change on a routine rather than "just in case" to use fewer disposables (only when comfortable for the baby).

**RES_OTHER**
- PREVENT · FREE · Before buying, ask: can I refuse it, reuse something I own, or get a refillable version?
- REUSE · FREE · Keep sturdy packaging to reuse for storage or *baon*.
- PREVENT · FREE · Next time, note what the item was so Menosan can suggest something more specific.

---

## 7. Data model (Neon / Postgres)

All tables use `id uuid` primary keys (`gen_random_uuid()` unless the client supplies one). Every table that holds private data has `user_id … REFERENCES users(id) ON DELETE CASCADE`, so account deletion is a single `DELETE FROM users` (SFR4.2).

```sql
-- V1__init.sql (owned by BE-0)
CREATE TABLE users (
  id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  firebase_uid     text UNIQUE NOT NULL,
  email            text NOT NULL,
  display_name     text,
  consented_at     timestamptz NOT NULL,          -- privacy notice accepted (Data Privacy Act)
  created_at       timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE waste_subcategories (
  code        text PRIMARY KEY,
  category    text NOT NULL CHECK (category IN ('BIODEGRADABLE','RECYCLABLE','RESIDUAL','SPECIAL')),
  label       text NOT NULL,
  examples    text[] NOT NULL DEFAULT '{}',
  avoidable   boolean NOT NULL DEFAULT false,
  sort_order  int NOT NULL
);

CREATE TABLE waste_entries (
  id                  uuid PRIMARY KEY,            -- client-generated UUID → idempotent sync (NFR8)
  user_id             uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  name                text NOT NULL CHECK (char_length(name) BETWEEN 1 AND 60),
  category            text NOT NULL,               -- derived from subcategory, validated
  subcategory_code    text NOT NULL REFERENCES waste_subcategories(code),
  quantity            int  NOT NULL CHECK (quantity BETWEEN 1 AND 999),
  source              text NOT NULL CHECK (source IN ('MANUAL','PHOTO')),
  created_at          timestamptz NOT NULL,        -- client time of creation (NFR11)
  week_start          date NOT NULL,               -- derived server-side, Asia/Manila
  updated_at          timestamptz NOT NULL DEFAULT now(),
  received_at         timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ON waste_entries (user_id, week_start);

CREATE TABLE interventions (                       -- curated library (NFR12)
  id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  code              text UNIQUE NOT NULL,          -- e.g. RES_SACHETS_REFILL_STATION
  subcategory_code  text NOT NULL REFERENCES waste_subcategories(code),
  type              text NOT NULL CHECK (type IN ('PREVENT','REDUCE','REUSE')),
  title             text NOT NULL,
  description       text NOT NULL,
  how_to            text[] NOT NULL DEFAULT '{}',
  cost_level        text NOT NULL CHECK (cost_level IN ('FREE','SAVES_MONEY','SMALL_ONE_TIME_COST')),
  effort            text NOT NULL CHECK (effort IN ('LOW','MEDIUM')),
  active            boolean NOT NULL DEFAULT true
);

CREATE TABLE weekly_reports (
  id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id            uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  week_start         date NOT NULL,
  week_end           date NOT NULL,
  stats              jsonb NOT NULL,               -- §5.1 output
  comparison         jsonb,                        -- §5.3 output or NULL
  algorithm_version  int NOT NULL,
  revision           int NOT NULL DEFAULT 1,
  generated_at       timestamptz NOT NULL DEFAULT now(),
  regenerated_at     timestamptz,
  UNIQUE (user_id, week_start)
);

CREATE TABLE hotspots (
  id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  report_id         uuid NOT NULL REFERENCES weekly_reports(id) ON DELETE CASCADE,
  subcategory_code  text NOT NULL REFERENCES waste_subcategories(code),
  rank              int NOT NULL,
  criteria          text[] NOT NULL,              -- MOST_FREQUENT, HIGHEST_QUANTITY, AVOIDABLE
  frequency         int NOT NULL,
  quantity          int NOT NULL,
  score             numeric(6,4) NOT NULL,
  UNIQUE (report_id, subcategory_code)
);

CREATE TABLE report_recommendations (              -- "Hotspot Intervention" in Data Fields
  id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  hotspot_id       uuid NOT NULL REFERENCES hotspots(id) ON DELETE CASCADE,
  intervention_id  uuid NOT NULL REFERENCES interventions(id),
  rank             int NOT NULL,
  note             text,                           -- Gemini personalization, nullable
  continued        boolean NOT NULL DEFAULT false,
  source           text NOT NULL CHECK (source IN ('GEMINI','RULES')),
  UNIQUE (hotspot_id, intervention_id)
);

CREATE TABLE adopted_interventions (
  id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id              uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  report_id            uuid NOT NULL REFERENCES weekly_reports(id) ON DELETE CASCADE,
  intervention_id      uuid NOT NULL REFERENCES interventions(id),
  target_subcategory   text NOT NULL REFERENCES waste_subcategories(code),
  baseline_week_start  date NOT NULL,
  baseline_quantity    int NOT NULL,
  adopted_at           timestamptz NOT NULL DEFAULT now(),
  UNIQUE (report_id, intervention_id)
);

CREATE TABLE intervention_impacts (
  id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  adoption_id        uuid NOT NULL UNIQUE REFERENCES adopted_interventions(id) ON DELETE CASCADE,
  followup_report_id uuid REFERENCES weekly_reports(id) ON DELETE CASCADE,
  followup_week_start date NOT NULL,
  baseline_quantity  int NOT NULL,
  followup_quantity  int NOT NULL,
  result             text NOT NULL CHECK (result IN ('DECREASED','SAME','INCREASED'))
);
```

Migrations: `V2__seed_taxonomy.sql` (from `taxonomy.json`) and `V3__seed_interventions.sql` (§6.4, owned by BE-4). Run Flyway against the Neon **direct** (non-pooled) connection string. Run the app against the **pooled** one.

**Deletion and retention (NFR4):** account deletion hard-deletes rows immediately and deletes the Firebase user through the Admin SDK. Set Neon's history retention (point-in-time restore window) to **7 days or less** on the prod project, so residual copies become unrecoverable within 7 days. Application logs must never contain entry contents, emails, tokens, or images.

---

## 8. API contract v1

`menosan-api/docs/api-contract.md` is the canonical, versioned copy of this section. BE-0 creates it from this section, and any later change follows §8.4.

### 8.1 Conventions

- Base path `/v1`. JSON uses camelCase. Dates are `YYYY-MM-DD` (Manila local dates). Instants are ISO-8601 UTC (`2026-09-27T02:15:00Z`).
- Auth: `Authorization: Bearer <Firebase ID token>` on every `/v1` route except `/v1/taxonomy`. An invalid or expired token returns `401 UNAUTHENTICATED`. A valid token with no Menosan account returns `404 ACCOUNT_NOT_FOUND` on every route except `POST /v1/account` (SFR2.2).
- Every query is scoped by the authenticated `user_id` (NFR2, NFR6). Accessing another user's resource returns `404` (never `403`, so existence doesn't leak).
- Errors: `{"error": {"code": "WEEK_CLOSED", "message": "Human readable", "details": {}}}`.
- Error codes: `UNAUTHENTICATED`, `ACCOUNT_NOT_FOUND`, `VALIDATION_FAILED`, `INVALID_TIMESTAMP`, `WEEK_CLOSED`, `NOT_FOUND`, `CONFLICT`, `ADOPTION_WINDOW_CLOSED`, `ANALYSIS_FAILED`, `NOT_WASTE`, `IMAGE_TOO_LARGE`, `RATE_LIMITED`, `INTERNAL`.

### 8.2 Endpoints

| Method & path | Purpose | Req |
|---|---|---|
| `GET /health` | Liveness plus a DB check. No auth. | — |
| `GET /v1/taxonomy` | Categories and subcategories (§3). | SFR5.1 |
| `GET /v1/me` | `200 {id,email,displayName,createdAt}` or `404 ACCOUNT_NOT_FOUND`. | SFR2.1–2.2 |
| `POST /v1/account` | Body `{consent: true}`. Creates and links the account from the verified token (idempotent: returns `200` if it exists, `201` if created). | SFR1.1–1.2 |
| `DELETE /v1/account` | Deletes all data and the Firebase user. Returns `204`. | SFR4.1–4.2, NFR4 |
| `GET /v1/export` | Full JSON export (profile, entries, reports, hotspots, comparisons, recommendations, adoptions, impacts). | NFR16 |
| `GET /v1/weeks/current` | `{weekStart, weekEnd, timezone:"Asia/Manila", serverNow}`. | SFR10.3 |
| `GET /v1/entries?weekStart=` | Entries for a week (default: current), newest first. | SFR10.1–10.2 |
| `PUT /v1/entries/{id}` | Create or update (idempotent upsert). Body `{name, subcategory, quantity, source, createdAt}`. The server derives `category` and `weekStart`. Create returns `201`, update `200`. Updates may not change `createdAt`. | SFR5, SFR6, SFR11 |
| `DELETE /v1/entries/{id}` | Returns `204`, also when the entry is already gone (idempotent). `409 WEEK_CLOSED` if the entry's week is closed. | SFR11.2–11.3 |
| `POST /v1/entries/sync` | Batch for the offline outbox: `{upserts:[EntryPut & {id}], deletes:[id]}` → `{results:[{id, status:"OK"|"WEEK_CLOSED"|"INVALID"|..., entry?}]}`. Items are processed independently, and the batch never fails as a whole because of one item. | SFR6.3–6.4, NFR8 |
| `POST /v1/photo-analysis` | `multipart/form-data`, field `image` (JPEG, ≤ 2 MB). Returns `200 {suggestion:{name, category, subcategory, quantity, confidence}, warning}`. Also `422 ANALYSIS_FAILED`, `422 NOT_WASTE`, `413 IMAGE_TOO_LARGE`, `429 RATE_LIMITED` (30 per user per day). Nothing is stored. | SFR7–8, SFR9.5 |
| `GET /v1/reports` | List `[ {weekStart, weekEnd, analyzedQuantity, hotspotCount, adoptedCount, isLatest} ]`, newest first. Runs lazy catch-up first. | SFR12.1, SFR18.2 |
| `GET /v1/reports/{weekStart}` | Full report (§8.3). | SFR12–17 |
| `POST /v1/reports/{weekStart}/adoptions` | `{interventionIds:[uuid]}` → the updated report. `409 ADOPTION_WINDOW_CLOSED` if the report is not the latest. | SFR16 |
| `DELETE /v1/reports/{weekStart}/adoptions/{interventionId}` | Un-adopt within the window. | SFR16 |
| `POST /internal/jobs/weekly-reports` | Header `X-Job-Key`. Optional `{weekStart}`. Generates missing reports. | SFR12.1 |
| `POST /internal/dev/*` | **Only when `DEV_TOOLS_ENABLED=true` (never in prod):** `clock` (set or clear override), `seed-history {email, weeks:3}` (synthetic entries in past weeks, then report generation), `reports/generate {email, weekStart}`. | testing |

### 8.3 Report payload (shape)

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

### 8.4 Contract change process

1. Open a PR in `menosan-api` that updates `docs/api-contract.md`, adds a line to `docs/CHANGELOG-contract.md`, and implements the change **backward-compatibly** (additive fields only after the Android beta is released on 9/26).
2. Open a matching issue in `menosan-android` labeled `contract-change`.
3. Breaking changes after 9/26 require human approval.

---

## 9. Backend workstreams (`menosan-api`)

Package root `app.menosan`. Suggested layout:

```
src/main/kotlin/app/menosan/
  Application.kt            config/  (AppConfig from env)      plugins/ (auth, serialization, status pages, call logging w/o bodies, rate limit)
  common/ (Clock, WeekCalc, errors, ids)                        db/ (Tables.kt, Database.kt, tx helpers)
  account/  entries/  taxonomy/  photo/  analytics/ (pure)  reports/  interventions/  export/  jobs/  dev/
src/main/resources/  application.conf  taxonomy.json  db/migration/V*.sql  prompts/*.txt
src/test/kotlin/…     Dockerfile   .github/workflows/ci.yml   docs/
```

Environment variables: `APP_ENV` (dev|staging|prod), `PORT`, `DATABASE_URL` (pooled), `DATABASE_URL_DIRECT` (migrations), `DATABASE_USER`, `DATABASE_PASSWORD`, `FIREBASE_PROJECT_ID`, `FIREBASE_SERVICE_ACCOUNT_JSON_B64`, `GEMINI_API_KEY`, `GEMINI_MODEL`, `JOB_KEY`, `DEV_TOOLS_ENABLED`, `CLOCK_OVERRIDE`. Commit `.env.example` only, never real secrets.

### BE-0 · Foundation (one agent, sequential, **Wed 9/23**) — *blocks everything*

- Gradle project, Ktor server, `AppConfig`, `/health`, JSON error handling (StatusPages → §8.1 format), request IDs, call logging **without bodies**.
- DB: Hikari (max pool 5, Neon-friendly timeouts), Flyway, `V1__init.sql` (§7), `V2__seed_taxonomy.sql`, Exposed table objects for **all** tables.
- `Clock` injection and `WeekCalc` (§4) with boundary tests.
- Firebase auth plugin: verify the ID token, then resolve `UserPrincipal(userId, firebaseUid, email)`, returning `ACCOUNT_NOT_FOUND` when needed.
- `GET /v1/taxonomy`, `GET /v1/me`, `POST /v1/account`, `GET /v1/weeks/current`.
- **Interfaces** that parallel workstreams implement, with stub implementations: `EntryRepository`, `PhotoAnalyzer`, `ReportService`, `InterventionEngine`, `GeminiClient` (thin wrapper, fakeable in tests).
- `docs/api-contract.md` (from §8), `docs/DECISIONS.md`, `.env.example`, `Dockerfile` (multi-stage, fat JAR), and a CI workflow running `./gradlew test`.
- **DoD:** app boots locally against the Neon `dev` branch, migrations apply cleanly, `/health` is green, auth works end-to-end with a real Firebase token, CI is green. Contract frozen.

### BE-1 · Accounts, entries, sync, export (**Thu 9/24**)

- `PUT/DELETE /v1/entries/{id}`, `GET /v1/entries`, and `POST /v1/entries/sync`, with all validation from §4 and §8 (derive category, validate subcategory, quantity 1–999, name 1–60 trimmed, timestamp rules, week-editability).
- Idempotency: an upsert with an existing id for the same user updates the entry. An existing id for a **different** user returns `409 CONFLICT`. Delete of a missing id returns `204`.
- A late create into a closed week calls `ReportService.onLateEntry(userId, weekStart)` (§5.6).
- `DELETE /v1/account` (DB cascade, then Firebase Admin `deleteUser`, and return `204` even if the Firebase user is already gone). `GET /v1/export`.
- **Tests:** ownership isolation (user A can't read or update or delete user B's entries), sync retry produces no duplicates, week boundary edits, closed-week rejection, account deletion removes every row.

### BE-2 · Photo analysis (**Thu 9/24**)

- `POST /v1/photo-analysis`: read the multipart part into memory (≤ 2 MB), never write it to disk or logs, send it to Gemini with `responseMimeType=application/json` and a `responseSchema` whose `subcategory` is an **enum of taxonomy codes**. Discard the bytes after the call (SFR8.5).
- Prompt (in `resources/prompts/photo_analysis.txt`): the Dumaguete/Philippine household context, the taxonomy with examples, "count visible separate pieces", and return `isWaste=false` for non-waste or unclear images.
- Validate (SFR8.3): all fields present, subcategory in the taxonomy, category matches the subcategory, quantity 1–999, name 1–60. Otherwise return `422 ANALYSIS_FAILED`. `isWaste=false` returns `422 NOT_WASTE`. Always include the `warning` text for SFR9.5.
- Per-user rate limit (30/day) and a timeout (15 s).
- **Tests:** a fake `GeminiClient` covering valid, malformed JSON, unknown code, zero quantity, timeout, and not-waste responses. Add one manual smoke test with real photos (sachet, PET bottle, leftover rice) and record the results in `docs/photo-smoke.md`.

### BE-3 · Analytics and reports (**Thu 9/24 → Fri 9/25**)

- Pure `analytics` package: `aggregate()`, `findHotspots()`, `compare()`, `measureImpact()`, exactly per §5, with table-driven unit tests covering ties, only-special weeks, one-entry weeks, the 3-hotspot cap, and a no-previous-week case.
- Write `docs/analytics-test-vectors.json` with at least 14 cases. Cover ties, only-special weeks, a single entry, the cap of 3, an avoidable hotspot that wins only by score, an empty analyzed set, comparison with and without a previous week, and impact results of DECREASED, SAME, INCREASED, and a missing follow-up. Add a test that runs all four functions against it. Android reuses this file (§5.7). Keep `aggregate()`, `findHotspots()`, `compare()`, and `measureImpact()` free of JVM-server dependencies so they can be copied into the app as-is.
- `ReportService.ensureReport`, lazy catch-up, `onLateEntry` regeneration (§5.6), persistence into `weekly_reports`, `hotspots`, and `intervention_impacts`, and calls to `InterventionEngine` for recommendations.
- `GET /v1/reports`, `GET /v1/reports/{weekStart}` (payload §8.3), and the `isLatest` flag.
- Scheduler (in-process coroutine, Sunday 00:05 PHT) plus `POST /internal/jobs/weekly-reports`. Add a GitHub Actions workflow `weekly-reports.yml` (cron `5 16 * * 6` UTC) that calls the endpoint with `JOB_KEY`, disabled until hosting is decided.
- **Tests:** a determinism test (same entries produce a byte-identical `stats`, `hotspots`, and `comparison`), idempotent generation, and late-sync regeneration that keeps adoptions.

### BE-4 · Intervention library, engine, adoption (**Thu 9/24 → Fri 9/25**)

- `V3__seed_interventions.sql` from §6.4. Expand each line into a title (≤ 60 characters), a description (1–3 sentences), and a 2–4 step `howTo`, following §6.1. Add a unit test asserting there are at least 3 active items per non-SPECIAL subcategory and none for SPECIAL.
- `InterventionEngine.recommend()` per §6.2: rule-based pre-rank, Gemini selection with strict validation, fallback, and "continued" pinning. Prompt in `resources/prompts/intervention_selection.txt`, including the tone rules and the Philippine economic context.
- Adoption endpoints (§6.3) with the window check (I7), plus the `adopted` flag in the report payload.
- **Tests:** Gemini returns an id outside the candidates (falls back), notes longer than 200 characters (falls back), previously adopted interventions that increased or stayed the same are deprioritized, adoption window closed returns `409`, and adoption is idempotent.

### BE-5 · Integration, dev tools, deployment (**Fri 9/25**)

- `/internal/dev/*` tools (§8.2), guarded by `DEV_TOOLS_ENABLED` and `X-Job-Key`.
- An end-to-end test script (`scripts/e2e.sh` or a Kotlin test) against staging: create account → log entries across 2 simulated weeks via the clock override → generate reports → adopt → roll the week → see impact.
- Deploy **staging** (Neon `dev`) and **prod** (Neon `main`). Document the base URLs in `docs/ENVIRONMENTS.md`. Enable the report cron.
- **DoD (backend release gate, Fri 9/25 night):** all SFRs in §12 marked BE are implemented and tested, staging and prod are deployed, the e2e test passes on staging, and Android has the base URLs.

---

## 10. Android workstreams (`menosan-android`)

Package root `app.menosan.android`. Suggested layout:

```
app/src/main/java/app/menosan/android/
  MenosanApp.kt  MainActivity.kt  di/  navigation/
  core/ (time/WeekCalc, network/ApiClient+AuthInterceptor, result, ui/theme, ui/components)
  data/ (local/ Room db+DAOs+entities, remote/ DTOs+services, repo/ EntryRepository, ReportRepository, AccountRepository, TaxonomyRepository)
  sync/ (SyncWorker, SyncScheduler)
  feature/ auth/ dashboard/ logging/ entries/ photo/ reports/ interventions/ settings/
```

Build flavors: `staging` and `prod` (different `API_BASE_URL`, same `applicationId` suffix rules agreed with Firebase). Signing: **one shared release keystore** kept by a human. Register its SHA-1 and SHA-256, plus each developer's debug SHA, in Firebase. Testers must be able to install updates **over** their existing app without losing pending entries.

> ⚠️ Room migration rule: from the first tester build (Sat 9/26) onward, **never use `fallbackToDestructiveMigration`**. Every schema change gets a real `Migration`. Losing a tester's pending entries would break NFR7.

### Screens (mapped to SRS Figure 3)

| Screen | Contents |
|---|---|
| **Sign-in** (Authentication) | "Continue with Google". After sign-in, call `GET /v1/me`. On 404, show the **Create account** screen with a short privacy notice (what is stored, nothing public, can export or delete anytime) and a consent checkbox, then `POST /v1/account`. Online only. |
| **Dashboard** | Current week range (Sun–Sat), entries this week, quick actions **Log manually** and **Log with photo**, a pending-sync badge, a latest report card ("Your report for Sep 27 – Oct 3 is ready"), and "This week you're trying: …" (active adoptions). |
| **Waste Logging** (manual) | Name (text), main category (radio: Biodegradable / Recyclable / Residual / Special), subcategory (dropdown filtered by category, with examples), quantity (stepper, 1–999). Inline validation. Saves to Room immediately and works offline (UFR5–6). |
| **Photo logging** | Take a photo or pick from the gallery. Requires a connection; if offline, show "Photo logging needs internet — you can log manually." Show the SFR9.5 warning. Compress to ≤ 1280 px long side, JPEG q≈80, fix EXIF rotation, upload, then **delete the temp file** (SFR8.5). The review form is the manual form prefilled, with AI values visually marked (tinted field background plus an "AI suggestion" chip that is removed per field once the user edits or confirms it, NFR10). A required "I checked these details" confirmation comes before Save (SFR9.2, 9.4). |
| **Waste Entries** (current week) | A list with name, subcategory, category color, quantity, date and time, and a sync status icon (UFR10). Edit and delete only for the current week (UFR11). |
| **History** | A list of weekly reports (`GET /v1/reports`), newest first. Cached reports stay viewable offline. A provisional offline report (§5.7) is labeled "Offline summary". |
| **Weekly Summary** | Week range, totals, main-category bar chart, subcategory breakdown, the Special line ("logged, not included in analysis"), and a comparison with the previous week or the "no comparison" message. For a provisional offline report, show the sections available per §5.7 (totals, hotspots, and the comparison, impact, and last-week recap when local data allows), plus the offline banner. Recommendation cards and Adopt buttons are hidden. |
| **Waste Hotspot** | Ranked hotspot cards with criteria chips and frequency/quantity. |
| **Intervention Results** | For each hotspot, 2–3 recommendation cards (type badge, cost badge such as "Free" or "Saves money", description, how-to, personal note) with an **Adopt** toggle while the report is the latest. An impact section shows last week's adoptions with a Decreased / Same / Increased result, framed supportively (e.g., Increased → "That's okay — try a smaller step this week"). |
| **Account Settings & Privacy** | Account email, **Export my data** (SAF `CreateDocument`, `menosan-export-YYYY-MM-DD.json`), **Log out** (UFR3: Firebase `signOut`, clear the credential state, clear Room *only if the outbox is empty*, otherwise warn first), **Delete account** (a confirmation dialog where the user types DELETE, then the API call, clear Room, sign out), and the privacy notice text. |

UI guidelines: Material 3, a calm green palette, large touch targets, readable on small 720p screens, dark mode supported, and no leaderboards or comparisons with others. Charts: simple Compose `Canvas` horizontal bars (no heavy chart library needed).

### AN-0 · Foundation (**Fri 9/25**, starts once BE-0's contract is frozen)

Gradle setup, Hilt, theme, navigation graph with placeholder screens, Firebase + Credential Manager sign-in → ID token, `AuthInterceptor` (fresh token via `getIdToken(false)`, retrying once with a forced refresh on 401), Retrofit services for **all** §8 endpoints (DTOs mirroring the contract), Room database v1 (`entries`, `reports_cache`, `taxonomy`), bundled `taxonomy.json`, and a `WeekCalc` shared by tests. **DoD:** sign-in → create account → dashboard works against staging (or a locally run `menosan-api` against the Neon `dev` branch if staging isn't up yet).

### AN-1 · Manual logging and offline sync (**Sat 9/26**) — *required for the Sunday tester build*

- Room keeps entries for the **current week and the two previous** logging weeks (needed for the offline report, §5.7).
- Room `EntryEntity(id: UUID, name, category, subcategory, quantity, source, createdAt, weekStart, syncState: PENDING_CREATE|PENDING_UPDATE|PENDING_DELETE|SYNCED, lastError)`.
- **Room is the source of truth for the current week.** Every create, edit, or delete writes to Room first, then enqueues the `SyncWorker` (unique work, `NetworkType.CONNECTED`, exponential backoff). The worker sends the outbox through `POST /v1/entries/sync` and applies per-item results. `WEEK_CLOSED` on an edit or delete reverts the local change and shows a message. Create conflicts are logged.
- On app open while online, pull `GET /v1/entries` for the current week and merge (server wins for SYNCED rows, local wins for pending rows).
- Also trigger sync on app start and after sign-in. Pending entries survive app kill, restart, and reboot (NFR7), since WorkManager persists them.
- Manual logging form, current-week list, edit and delete.
- **DoD:** airplane mode → log 5 entries → kill the app → reopen → disable airplane mode → entries appear on the server exactly once. Instrumented or robolectric tests cover the outbox logic.

**➡ Sat 9/26 evening: build `v0.5-beta` (sign-in, manual logging, entries, offline sync) and distribute it to testers through Firebase App Distribution so they can log the full week of Sun 9/27 – Sat 10/3.**

### AN-2 · Photo logging (**Sun 9/27**)

Camera (`TakePicture` into a `FileProvider` cache path) and gallery (`PickVisualMedia`), compression, upload, error handling for every §8.1 code, the review form with AI marking, and deletion of the temp image in `finally`. **DoD:** photo → suggestion → edit → confirm → saved (also through the outbox), and the offline case is handled gracefully.

### AN-3 · Reports, hotspots, interventions, impact (**Sun 9/27 → Mon 9/28**)

History list, weekly summary with charts, hotspots, recommendations with Adopt/Un-adopt (optimistic UI, reverted on error), the impact section, and caching of the last fetched reports in Room for offline viewing.

**Offline provisional report (§5.7):** `core/analytics` (Kotlin port of `aggregate()`, `findHotspots()`, `compare()`, `measureImpact()`), and a `LocalReportGenerator` triggered on app open for a closed week with no cached server report while offline. It uses the cached W−1 server report (stats, hotspots, adoptions) for the comparison, impact, and last-week recap, falling back to local W−1 entries for the comparison. Also: the offline banner, replacement by the server report after sync, and caching of full server reports (including adoption flags) for at least the last 2 reports. Unit tests run `analytics-test-vectors.json`.

**DoD:** works against staging data produced by `/internal/dev/seed-history`. In airplane mode, after rolling the week over (staging clock override or device test), the app shows an offline summary with totals and hotspots, plus the comparison, impact of last week's adoptions, and last-week recap when a W−1 report is cached. It shows no recommendations. Going back online replaces it with the full report.

### AN-4 · Dashboard, settings, privacy, polish (**Mon 9/28 → Tue 9/29**)

Dashboard composition, export, logout, account deletion, privacy notice, empty states, supportive copy review, accessibility (content descriptions, font scaling), and handling of the Android 8 minimum (test on an API 26 emulator).

### AN-5 · QA and release (**Tue 9/29**)

Full manual test pass (§11.2) on an API 26 emulator and a real low-end device. Build `v0.9-rc` for usability testing.

---

## 11. Schedule (2026-09-23 → 2026-10-05, PHT)

| Date | Backend | Android | Humans / team |
|---|---|---|---|
| **Wed 9/23** | BE-0 foundation, contract frozen | — | Provide secrets: Neon `dev`/`main` URLs, Firebase service account, Gemini key. Create GitHub repos and branch protection. |
| **Thu 9/24** | BE-1, BE-2, BE-3 (analytics), BE-4 (library) in parallel | — | **Decide hosting by noon.** Register SHA fingerprints. Recruit 10–15 testers and prepare consent forms. |
| **Fri 9/25** | BE-3 (reports/jobs), BE-4 (engine/adoption), BE-5 deploy → **backend release gate** | AN-0 foundation | Review the intervention library copy for tone and local fit. |
| **Sat 9/26** | Bug fixes and support | **AN-1** → `v0.5-beta` to testers | Onboard testers (install, sign in, how to log). |
| **Sun 9/27** | Support | AN-2, AN-3 | **Testers start logging week 9/27–10/3.** |
| **Mon 9/28** | Support | AN-3, AN-4 | — |
| **Tue 9/29** | Support | AN-4, AN-5 → `v0.9-rc` | Distribute the update (installs over the beta). |
| **Wed 9/30 – Fri 10/2** | Fixes | Fixes | **Usability and acceptance testing** (10–15 users). Report, hotspot, intervention, and impact flows are tested on **staging demo accounts** seeded with past weeks, so they can be tested before a real week ends. |
| **Sat 10/3** | — | — | The real logging week ends at 23:59 PHT. |
| **Sun 10/4, 00:05 PHT** | Weekly job runs on prod | — | Testers open their **first real weekly report** and adopt interventions. |
| **Mon 10/5** | Fixes | Fixes | Collect weekly-report feedback and close testing. |

**Important timing facts**

- If testers only start logging after Sun 9/27, the first real report can't arrive until **Sun 10/11**. That is why the Sat 9/26 beta is on the critical path.
- **Real-data impact measurement (UFR17) needs two consecutive weeks after adoption and can't happen by 10/5.** It is verified on staging with the clock override and seeded history. State this in the test report.

### 11.1 Critical path and parallelism

```
BE-0 ─┬─ BE-1 ─┐
      ├─ BE-2 ─┤
      ├─ BE-3 ─┼─ BE-5 (gate Fri) ── AN-1 (beta Sat) ── AN-2/AN-3 ── AN-4 ── AN-5 ── UAT
      └─ BE-4 ─┘        └─ AN-0 (Fri, after contract freeze)
```

Up to four backend agents can run in parallel after BE-0. BE-3 and BE-4 meet only at the `InterventionEngine` interface defined in BE-0. On Android, AN-2 and AN-3 can run in parallel after AN-1.

### 11.2 Acceptance test checklist (UAT script outline)

1. Sign up with Google → consent → dashboard. Log out → log in again. A Google account without a Menosan account sees the create screen.
2. Manual log online. Manual log offline, restart, reconnect → entry synced exactly once, with its original time.
3. Photo log: sachet, PET bottle, leftovers → the AI fields are marked → edit → confirm → saved. An unclear photo shows a friendly error.
4. Edit and delete entries in the current week. Past-week entries are read-only.
5. Demo account (staging): the report shows the week range, category and subcategory stats, the Special line excluded, up to 3 hotspots with criteria chips, a comparison (or the no-comparison message), and 2–3 recommendations per hotspot.
5b. Offline report: a user who adopted interventions on last week's report, with pending entries in a closed week and the device offline. The app shows the offline summary: totals, hotspots, comparison with last week, impact of the adopted interventions, and a recap of last week, but no new suggestions. Reconnect and the full server report replaces it, with matching numbers if no other device logged entries.
6. Adopt 2 interventions → roll the clock to the next week → the new report shows impact results (Decreased/Same/Increased).
7. Export data → the file opens and contains the entries and reports.
8. Delete account → sign in again → treated as a new user, with no old data.
9. Tone check: no message reads as blaming. Testers rate "encouraging" on a 1–5 scale (SDP ethics requirement).

---

## 12. Requirement traceability

| Req | Implemented in | Verified by |
|---|---|---|
| UFR1–2, SFR1.1–2.2, NFR5 | BE-0 auth plugin + `/v1/me`, `/v1/account`; AN-0 sign-in | BE-0 tests, UAT 1 |
| UFR3, SFR3.1 | AN-4 logout | UAT 1 |
| UFR4, SFR4.1–4.2, NFR4 | BE-1 `DELETE /v1/account` (cascade + Firebase) + Neon retention ≤ 7 days | BE-1 tests, UAT 8 |
| UFR5, SFR5.1–5.3 | BE-1 validation + WeekCalc; AN-1 form | BE-0/1 tests |
| UFR6, SFR6.1–6.4, NFR7, NFR8, NFR11 | AN-1 outbox + SyncWorker; BE-1 idempotent sync; BE-3 late regeneration | AN-1 DoD, BE-1/3 tests, UAT 2 |
| UFR7–9, SFR7.1–9.5, NFR10 | BE-2 photo analysis; AN-2 photo flow + review form | BE-2 tests, UAT 3 |
| UFR10–11, SFR10.1–11.3 | BE-1 editability; AN-1 entries list | BE-1 tests, UAT 4 |
| UFR12, SFR12.1–12.3 | BE-3 aggregation + job + lazy catch-up; AN-3 summary | BE-3 tests, UAT 5 |
| UFR13, SFR13.1–13.2 | BE-3 hotspots (subcategory level); AN-3 hotspot cards and offline provisional hotspots (§5.7) | BE-3 tests, shared test vectors in both repos, UAT 5 and 5b |
| UFR14, SFR14.1–14.3 | BE-3 comparison; AN-3 offline comparison (§5.7) | BE-3 tests, UAT 5 |
| UFR15, SFR15.1, NFR12, NFR13 | BE-4 library + engine | BE-4 tests, UAT 5 |
| UFR16, SFR16.1–16.2 | BE-4 adoption; AN-3 adopt toggle | BE-4 tests, UAT 6 |
| UFR17, SFR17.1–17.2 | BE-3 impact; AN-3 offline impact (§5.7) | BE-3 tests, UAT 6 (staging) |
| UFR18, SFR18.1–18.2 | BE-3 report retention + list; AN-3 history | UAT 5 |
| NFR1–3, NFR6 | User-scoped queries, 404 on foreign ids, no public views or rankings | BE-1 isolation tests |
| NFR9 | Pure analytics + stored recommendations + `algorithm_version` | BE-3 determinism test |
| NFR14 | Out of scope; no such features | Review |
| NFR15 | `minSdk 26` | AN-5 API 26 emulator pass |
| NFR16 | BE-1 `/v1/export`; AN-4 export | UAT 7 |

---

## 13. Risks and mitigations (development phase)

| Risk | Mitigation |
|---|---|
| Beta not ready by Sat 9/26 → no real weekly report by 10/5 | AN-1 is the top priority. If the beta slips, testers still log from whatever day it ships (a partial week still produces a report on 10/4), and report UX is covered on staging demo accounts. |
| Hosting undecided delays deployment | Host-agnostic Docker build. Default to Cloud Run if there is no decision by 9/24 noon. |
| Gemini free-tier data may be used by Google to improve its products, which conflicts with the privacy stance | Use a billing-enabled key for production if possible. Only images (discarded) and anonymized stats are sent, never emails or ids. Note this in the privacy notice. |
| Gemini latency, outages, or quota | Timeouts, rule-based fallback for interventions, a friendly photo error, and manual logging always available. |
| Neon cold start or connection limits | Pooled URL, small Hikari pool, retry on the first connection. |
| JVM cold start on scale-to-zero hosts | Keep minimum instances at 1 during testing week (low cost), or accept the few-second first request. The app shows loading states. |
| Destructive Room migrations wipe tester data | The rule in §10: no destructive migrations after 9/26. |
| Signing mismatch prevents in-place updates | One shared release keystore, owned by one human, from the first tester build. |
| Device clock wrong → offline entry in the wrong week | Server bounds (§4). The client shows week dates from `/v1/weeks/current` when online. |
| Content feels generic or culturally off (SDP risk) | §6.1 rules, a human tone review on Fri 9/25, and tester tone rating in UAT 9. |

---

## 14. Engineering conventions (both repos)

- Branches `feat/<ws>-<short>` (e.g., `feat/be3-hotspots`), Conventional Commits, and a PR per task with the workstream ID in the title. `main` stays deployable and CI must be green to merge.
- Tests are required for every rule in §4–§6. Aim for full coverage of the pure analytics package.
- Never commit secrets, `google-services.json` for prod, or keystores. Use GitHub Actions secrets and the local `local.properties`/`.env`.
- Never log request bodies, tokens, emails, entry names, or images.
- Keep dependencies minimal. Prefer standard libraries (Exposed, Ktor, Compose) over new frameworks.
- User-facing copy: short, plain English, encouraging, no blame. Use "you logged", never "you wasted".
- Record every decision that deviates from this plan in `docs/DECISIONS.md`. The team will use these files to update the SRS/SDP after release.
