# Photo analysis smoke test (BE-2)

Manual check of `POST /v1/photo-analysis` against real Gemini with real household photos (plan §9 BE-2). Re-run it whenever the model, the prompt (`src/main/resources/prompts/photo_analysis.txt`), or the schema changes.

## How to run

1. Take photos on a phone, the way a user would, then shrink them the way the app does (longest side ≤ 1280 px, JPEG quality ≈ 80). Keep them under 2 MB.
2. Put them in a folder **outside the repo**, and never commit them.
3. Run (Git Bash, from the repo root, with `GEMINI_API_KEY` in `.env`):

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
GEMINI_LIVE_TEST=true PHOTO_SMOKE_DIR=/d/photo-smoke ./gradlew cleanTest test --tests '*GeminiLiveSmokeTest*'
grep -h SMOKE build/test-results/test/TEST-app.menosan.photo.GeminiLiveSmokeTest.xml
```

Each photo prints one line: `PHOTO-SMOKE | file | size | latency | suggestion or error code`. Optional: `GEMINI_MODEL=<model>` to compare models, and `SMOKE_THINKING_LEVEL=minimal|low|default`.

## Results

### 2026-09-23: model selection (synthetic image only)

No real photos yet. The synthetic image is a blue card with the text "Hello, Menosan", which should be `NOT_WASTE`.

| Model | Thinking | Photo | Intervention selection |
|---|---|---|---|
| `gemini-2.5-flash` | — | 404: closed to new API keys | 404 → rules |
| `gemini-3.6-flash` | model default | `NOT_WASTE`, 5.0 s | timed out at 8 s → rules |
| **`gemini-3.6-flash`** | **low (chosen)** | **`NOT_WASTE`, 3.4–3.7 s** | **valid Gemini picks and notes, 2.6–3.1 s** |
| `gemini-3.6-flash` | minimal | `NOT_WASTE`, 2.1 s | valid Gemini picks, 3.1 s |
| `gemini-3.8-flash` | model default / low | 503 overloaded, then `NOT_WASTE` in 8.6 s | timed out → rules |
| `gemini-3.8-flash` | minimal | 400: `minimal` not supported | 400 → rules |
| `gemini-3.5-flash-lite` | any | `NOT_WASTE`, 1.5–1.6 s | valid Gemini picks, 1.5–1.7 s |

Also seen: one `429 RESOURCE_EXHAUSTED` (free-tier quota, from many calls in a row) and one transient `IOException`. Both become `422 ANALYSIS_FAILED`, and a retry worked.

### 2026-09-24: free-tier quotas, switch to the lite models

The team stays on the free tier. The key's limits (AI Studio → Rate limits): `gemini-3.6-flash` **5 RPM / 20 RPD**,
too few for even one day of testing. `gemini-3.5-flash-lite` and `gemini-3.1-flash-lite` **15 RPM / 500 RPD each**,
and quotas are per model. So photos use `3.5-flash-lite` and recommendations `3.1-flash-lite`, behind an in-app
throttle (`GeminiRateLimiter`). Gemini was overloaded that night (503s, slower than on 9/23):

| Model | Thinking | Photo | Intervention selection |
|---|---|---|---|
| **`gemini-3.5-flash-lite`** | **minimal (chosen)** | **`NOT_WASTE`, 1.9 s** | — |
| `gemini-3.5-flash-lite` | low | — | valid Gemini pick and note, 6.1 s |
| `gemini-3.1-flash-lite` | low | — | 503, then timed out at 8 s twice → rules |
| `gemini-3.1-flash-lite` | model default | — | 503 → rules |
| **`gemini-3.1-flash-lite`** | **minimal (chosen)** | — | **valid Gemini picks and note, 6.4 s** |

Staging on 2026-09-23 with `gemini-3.6-flash`: a real PET bottle photo → "Water PET bottle", `REC_PET_BOTTLES`, 1,
confidence 0.9, 4.9 s (after one failed try).

### Real photos: TO DO (human)

Required by the plan: at least a sachet, a PET bottle, and leftover rice. Also worth trying: a pile of mixed waste, a sando bag, a styro box, a dark or blurry photo, and a non-waste photo (a room, a plate of food still being eaten).

| Photo | Expected | Result (name / subcategory / quantity / confidence) | Latency | OK? |
|---|---|---|---|---|
| Coffee 3-in-1 sachets (e.g. 5) | `RES_SACHETS`, 5 | | | |
| PET bottle(s) | `REC_PET_BOTTLES` | | | |
| Leftover rice | `BIO_FOOD_LEFTOVERS`, 1 | | | |
| | | | | |
