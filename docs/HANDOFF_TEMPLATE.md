# Handoff — <repo> — <YYYY-MM-DD HH:MM PHT>

> Copy this template to the top of `docs/HANDOFF.md` at the end of every session, and whenever you are about to run out of context or tokens. Put the newest entry **first** and keep the older entries below it.
> Write it so an agent that has never seen this repo can continue within 5 minutes. Be concrete: file paths, commands, and test names.

## 1. Session
- **Agent / model:** <e.g., Claude Code (Opus), Codex, Gemini CLI>
- **Workstream(s):** <e.g., BE-0 Foundation — see docs/DEVELOPMENT_PLAN.md §9>
- **Branch:** `<branch>` · **Last commit:** `<short sha> <message>`
- **Overall state:** 🟢 on track / 🟡 partially done / 🔴 blocked

## 2. Done this session
- [x] <task> (`path/to/file.kt`, commit `abc123`)
- [x] <task>

## 3. In progress (unfinished)
| Item | Where | What's left |
|---|---|---|
| <e.g., sync endpoint> | `src/main/kotlin/app/menosan/entries/SyncRoutes.kt` | <per-item WEEK_CLOSED handling; 2 tests failing> |

## 4. Next steps (in order)
1. <most important next action, specific enough to start immediately>
2. <…>
3. <…>

## 5. Verify the current state
```bash
<commands to build, test, and run, e.g. ./gradlew test>
```
- Expected: <e.g., 48 tests pass, 2 known failures listed in §6>

## 6. Known issues / failing tests
- <test name or symptom> — <suspected cause> — <idea for fix>

## 7. Decisions made (also logged in docs/DECISIONS.md)
- <decision> — <reason>

## 8. API contract changes
- <none> | <endpoint/field changed + CHANGELOG-contract.md entry + Android issue link>

## 9. Environment / setup notes
- <new env vars, migrations added (V#), dependencies added, secrets needed (names only — never values)>

## 10. Questions / blockers for humans
- <e.g., hosting decision needed; Gemini key quota exceeded>
