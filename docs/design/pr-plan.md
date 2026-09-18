# PR Plan

Sixteen pull requests to the mid-demo, in merge order. Scope comes from the proposal's mid-demo
commitment (§9) and the phased delivery plan in [13. Delivery Plan](13-timeline.md):

> Automated Google Sheets ingestion with offline CSV fallback; rule-based categorization; dynamic
> priority aging and floor-dispatch heuristic; attender mobile interface with CMS ID logging and
> +2 point attribution; functional manager dashboard displaying real-time attender task lists; and
> initial cloud deployment.

Each PR is built on a feature branch, developed test-first, and merged through review. A PR opens
only once its unit tests pass and an evaluator confirms its scope is complete.

## Stage 1 — Foundation

| # | PR | Contents | Needs |
|---|---|---|---|
| 1 | Project skeleton | Maven, Java 21, Spring Boot 3, package layout (§2.3), four ArchUnit rules, Docker Compose Postgres, Flyway wiring, Testcontainers base, CI running `mvn verify` | — |
| 2 | Schema + audit spine | All Flyway migrations (`app_user` → `audit_event`), entities, repositories, both unique constraints, the `BEFORE_COMMIT` audit listener | 1 |
| 3 | Identity | `OidcUserService` allowlist, `SecurityFilterChain`, authorization matrix, ownership checks, provisioning screen, two seeded `MANAGER` rows | 2 |

The schema ships as one PR rather than three. The foreign keys and the two constraints that carry
the design — `uq_source_row` and `uq_ledger_idem` (§4.3) — are easier to check against §3 as a
whole than spread across three diffs.

## Stage 2 — Walking skeleton

| # | PR | Contents | Needs |
|---|---|---|---|
| 4 | CSV ingestion + list view | `ComplaintSource` port, CSV adapter, upload validation, Thymeleaf + Tailwind shell, manager complaint list | 2, 3 |
| 5 | Assignment + attender queue | Manual assign, reassign and release; mobile-first own-assignments queue; student name, roll number and phone with the 7-day post-resolution window (§11.4) | 4 |
| 6 | Resolve → +2 → leaderboard | Ledger write under `RESOLVE:{task}:{attender}`, X1/X2 concurrency tests, `SUM` leaderboard projection | 5 |
| 7 | Cloud deployment | Render plus managed Postgres, environment config, health check, CI green on the deployed build | 6 |

**Phase 1 exit criterion**, met at PR 7: on the deployed URL the manager uploads a CSV and assigns a
complaint; the attender opens it on a phone and taps Resolve; the leaderboard shows +2.

## Stage 3 — Automation

| # | PR | Contents | Needs |
|---|---|---|---|
| 8 | Sheets ingestion | Sheets v4 adapter, service-account auth, header-name mapping, `response_key` delta detection, `ingestion_run` records | 4 |
| 9 | Wake and sync | Apps Script trigger endpoint, `Sync Now`, scheduled poll with ShedLock | 8 |
| 10 | Classifier + triage | Weighted lexicon, Jaro–Winkler fuzzy matching, confidence gate, triage queue with one-click domain and assign | 8 |
| 11 | State machine + docket gate | Full transition table, `WorkflowService.transition()`, `RECORD_DOCKET`, configurable docket pattern, the `mark_resolved` guard | 6 |
| 12 | Priority, SLA, aging | Base priority rules, working-hours calendar, due dates, escalation at 1.5× capped at `P1`, hold time excluded | 11 |
| 13 | Auto-dispatch | Eligibility filter, Floor and Load scoring, tie-break chain, `score_snapshot` persistence | 12, 5 |
| 14 | Config + live dashboard | Manager-editable weights, SLA windows, working hours, thresholds and load caps; polling auto-refresh for task lists and counters | 13, 12 |
| 15 | Gold set | ~200 labelled complaints, 70/30 development and holdout split, accuracy and coverage harness | 10 |
| 16 | Demo hardening | Seed data, error states, mobile pass, deploy rehearsal | all |

**Phase 2 exit criterion**, met at PR 16: a complaint submitted to the Google Form appears
categorized and assigned on the right attender's phone with no human action in between.

## Why these four stay standalone

PRs **2**, **6**, **11** and **13** each hold one of the design's load-bearing guarantees — the two
unique constraints, ledger idempotency under concurrency, the transition table and its guards, and
the dispatch heuristic. Folding any of them into a neighbouring PR means the part that most needs
careful review gets skimmed alongside routine CRUD.

## The proposal's "real-time" dashboard

The proposal promises real-time attender task lists at the mid-demo; the design schedules SSE for
Phase 4 (§13.6). PR 14 closes the gap with polling auto-refresh, which is demonstrable and cheap,
leaving true SSE where the design put it.

## After the mid-demo

Phases 3 to 5 — penalties and special duties, Excel export, Web Push, SSE, shared-task concurrency,
analytics and anomaly detectors, then hardening — are roughly another twenty PRs, planned once
mid-demo scope is closed.
