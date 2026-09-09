# 12. Test Plan

## 12.1 Strategy

Tests are written **with the phase that introduces the behaviour** (§13), not batched at the end.
Phase 1 ships with the ledger and auth tests; the concurrency suite lands with Phase 4's shared
tasks. A phase is not complete until its tests are green.


| Level | Tool | Scope | Target |
|---|---|---|---|
| **Unit** | JUnit 5, AssertJ | Pure domain logic — scoring, classification, state machine, statistics | ~70% of tests; no Spring context |
| **Slice** | `@DataJpaTest`, `@WebMvcTest` | Repositories against real Postgres; controller contracts | ~15% |
| **Integration** | `@SpringBootTest` + Testcontainers | Full pipeline, transactions, concurrency | ~10% |
| **Concurrency** | JUnit + `CountDownLatch` / `ExecutorService` | The races in §7.1 | Explicit, named suite |
| **Architecture** | ArchUnit | Layering rules (§2.4) | Continuous |
| **Manual/UAT** | Stakeholder | Acceptance criteria (§12.6) | Per milestone |

**Authentication is tested where the logic actually is.** The OAuth2 authorization-code flow is
Spring Security's code and is not re-tested; what *we* wrote is the allowlist decision, which is a
plain unit test over `OidcUserService` — known email admits with the right role, unknown email
rejects, inactive user rejects, case and whitespace variants normalise. Every other test
authenticates with `@WithMockUser`. The manager's form login is covered end to end because it has
no external dependency.

**All database tests run against real PostgreSQL via Testcontainers**, never an in-memory
substitute. The properties we most need to verify — unique-constraint behaviour under concurrent
inserts, optimistic-lock failures, `SELECT ... FOR UPDATE` — are precisely the ones an H2 stand-in
would fake convincingly and wrongly. Coverage floor: **75% line, 90% on `domain`**, enforced by
JaCoCo in CI.

## 12.2 Fixtures

| Fixture | Source | Size | Used by |
|---|---|---|---|
| **F1 — Real anonymised complaints** | Cauvery Hostel logs, PII replaced with dummy data | ~200 rows | Classifier gold set, realistic dispatch |
| **F2 — Synthetic edge cases** | Hand-authored | ~60 rows | Corner-case suite (§12.4) |
| **F3 — Concurrency workload** | Generated | 4 attenders × 25 tasks | Race tests (§12.5) |
| **F4 — Volume/telemetry** | Generated, 90 days | ~1800 complaints | Analytics, anomaly detectors |
| **F5 — Malformed sheet** | Hand-authored | ~30 rows | Ingestion robustness |

**Anonymisation of F1 happens once, at extraction, before the data ever enters the repository.** A
committed fixture is effectively permanent, so the scrub cannot be a later step: emails →
`resident{n}@example.com`, names removed, room numbers remapped through a fixed permutation that
preserves floor structure (so dispatch tests stay meaningful) while breaking identifiability. The
mapping table is not committed.

## 12.3 Categorization gold set

The proposal's "≥90% accuracy" needs a defensible methodology, not a spot check.

**Construction.** From F1, 200 complaints labelled by hand into `ELECTRICAL`, `CIVIL`, `RO_PLANT`,
`NETWORK`, `AMBIGUOUS`. Split **70/30 into development and holdout**. Lexicon terms and thresholds
are tuned *only* against development; the holdout is scored once per milestone. Tuning against the
holdout would produce a number that means nothing.

**Metrics.** Reported together, never in isolation:

```
coverage  = auto-accepted / total
accuracy  = correct predictions / auto-accepted          ← the ≥90% criterion
recall_d  = correctly predicted as d / truly d
```

Plus a per-domain confusion matrix.

Coverage is reported alongside accuracy because **accuracy alone is trivially gameable**: a
classifier that refuses to classify anything scores 100%. Targets: **accuracy ≥ 90%, coverage ≥ 70%**
on the holdout. Complaints labelled `AMBIGUOUS` by the human labeller are expected to land in
triage; if one is auto-accepted, that is counted as an error.

**Continuous evaluation.** Every manager triage correction in production is `(text, true label)` —
a free growing labelled set. A monthly re-scoring job reports drift, and this corpus is also what
would eventually make the OpenNLP stage (§5.7) viable.

## 12.4 Corner-case suite

Every case named in the proposal's verification plan, plus what the design surfaced:

| # | Case | Expected |
|---|---|---|
| E1 | Ambiguous text scoring two domains equally | `UNCLASSIFIED` → triage, both scores shown |
| E2 | Identical simultaneous submissions (different residents) | Two complaints — distinct `response_key` (§5.3) |
| E3 | Byte-identical resubmission by the same resident | One complaint — `response_key` collides |
| E4 | Same CSV uploaded twice | Zero duplicates |
| E5 | Unallocated / unparseable floor number | `TRIAGE` / `UNMAPPED_LOCATION` |
| E6 | All attenders marked unavailable | `TRIAGE` / `NO_AVAILABLE_ATTENDER`, nothing lost |
| E7 | Floor exists, nobody covers it | Assigned to least-loaded, `UNCOVERED_FLOOR` warning |
| E8 | Every attender over `L_cap` | Assigned to best, `CapacityExceeded` notification |
| E9 | Sheet row deleted mid-month | Ignored; the complaint is unaffected (§5.3) |
| E10 | Sheet row edited after ingestion | Skipped and logged; original text retained. *(Amendment handling is backlog, §13.8)* |
| E11 | Row deleted causing all indices to shift | Zero spurious amendments (§5.1) |
| E12 | Required column renamed | Run fails loudly with `INGESTION_COLUMN_MISSING` |
| E13 | Empty / whitespace-only complaint text | `UNCLASSIFIED` → triage |
| E14 | Tamil-transliterated text ("thanni varala") | Fuzzy/lexicon hit or triage — never a wrong confident domain |
| E15 | Resolve attempted with no docket | `GUARD_FAILED` 422 |
| E16 | Docket failing the configured pattern | `INVALID_DOCKET` 422 |
| E17 | Docket corrected after entry | `docket_no` updated; `audit_event` holds the old and new values |
| E18 | Resolve → reopen → resolve again | Credit, reversal, new credit. Net +2. Three ledger rows |
| E19 | Penalty backdated into a closed period | Applied to correct period, manager warned |
| E20 | Attender A acts on attender B's task | 403 `NOT_ASSIGNED` |
| E20a | Google sign-in by a non-allowlisted email | Rejected; no `app_user` row created |
| E20b | Sign-in by an allowlisted but `active = false` user | Rejected |
| E20c | Allowlisted email differing in case or surrounding space | Admitted — matching is normalised |
| E21 | Month boundary at 02:00 IST on the 1st | Falls in the new month (NFR-7) |
| E22 | Sheets API down for 6 hours | Backoff, degraded banner, full catch-up on recovery |
| E23 | Anomaly detector below its `n` gate | Emits nothing (§8.3) |
| E24 | `MAD = 0` in A1 | Falls back to `3 × median`, no divide-by-zero |
| E25 | Attender queue with 0 tasks | Empty state, not an error |
| E26 | Leaderboard with an attender at 0 points | Appears, ranked last (`LEFT JOIN`, §7.8) |
| E27 | Same browser re-subscribes to push | Existing row updated, not duplicated (`uq_push_endpoint`) |
| E28 | Push service returns 410 Gone | Subscription revoked; no further attempts |
| E29 | Attender with no push subscription is assigned a task | Assignment succeeds; push skipped silently |
| E30 | Push service unreachable for an hour | Retries then `FAILED`; **no state transition affected** |
| E31 | Notification listener throws | The originating transaction still commits (`AFTER_COMMIT`) |

## 12.5 Concurrency suite

Direct tests of §7.1. Each runs against real Postgres, using a `CountDownLatch` so threads start
together, and asserts on **final database state** rather than on response codes.

| # | Test | Setup | Assertion |
|---|---|---|---|
| X1 | Double resolve, same key | 10 threads, same task, same `Idempotency-Key` | Exactly 1 ledger row, +2 total, all 10 get 200 |
| X2 | Double resolve, different keys | 10 threads, same task, distinct keys | Exactly 1 ledger row — `RESOLVE:{task}:{att}` collides regardless (§7.3) |
| X3 | Reassign vs resolve | Manager reassigns while attender resolves | Exactly one succeeds; loser gets 409; ledger consistent with the winner |
| X4 | Shared duty, concurrent completion | 4 attenders, shared task, simultaneous | 4 ledger rows (one each), task `COMPLETED` exactly once |
| X5 | Over-claim | 4 attenders claim a `k=1` duty at once | 1 assignment; 3 receive `NO_SLOTS_LEFT` |
| X6 | Concurrent ingestion | 3 poll runs of the same rows in parallel | No duplicate complaints; ShedLock serialises |
| X7 | Leaderboard under write load | Read while 100 credits are written | Every read internally consistent; final total exact |
| X8 | Reopen race | Manager reopens while attender re-resolves | Net points correct; no orphaned credit |
| X9 | **Proposal performance fixture** | 4 attenders × 25 tasks resolved concurrently | 100 ledger rows, 200 points, zero lost updates, p95 < 500 ms |

X2 is the sharpest test in the suite: it proves that correctness does **not** depend on the client
sending a good idempotency key. Even a buggy client generating a fresh UUID per retry cannot
double-credit, because the ledger key is derived from the work, not from the request.

## 12.6 Acceptance-criteria traceability

Each stakeholder-facing criterion from proposal §8, mapped to what proves it:

| Criterion | Verified by | Milestone |
|---|---|---|
| Ingests automatically, no copy-paste, no duplicates | E2, E3, E4, E9, E10, E11, X6 + live sheet demo | Mid-demo |
| Categorizes ≥90% into Civil/Electrical/RO | §12.3 holdout evaluation | Mid-demo |
| Assigns to the primary floor attender | Dispatch unit tests, E5–E8 | Mid-demo |
| Attender queue on mobile: docket entry + resolve, +2 credited | E15–E18, X1, X2 + device walkthrough | Mid-demo |
| Manager logs −1 penalties and special duties | Ledger tests, E19 | Final |
| Real-time leaderboard | §7.8 tests, X7, SSE integration test | Final |
| One-click clean `.xlsx` export | POI tests, manual inspection | Final |
| Anomaly detection, bottlenecks, visualizations | §8 detector tests on F4, E23, E24 | Final |
| Attenders alerted on their phone when work is assigned (G8) | E27–E31 + device walkthrough on a real Android phone | Final |

## 12.7 What we are not testing

Stated to keep the plan honest:

- **Browser matrix** — Chrome on Android and desktop only; that is what these five people use.
- **Load beyond NFR-1.** X9 is the stated performance fixture. We do not test 1000 concurrent
  users, because there will never be five.
- **Google Sheets API itself** — mocked at the port boundary (§5.8). We test our reconciliation
  logic, not Google's service.
- **Browser push services themselves** (FCM, Mozilla autopush) — mocked at the HTTP boundary. We
  test our subscription handling, payload construction and failure responses, not Google's delivery.
- **iOS Safari push** — attenders are on Android (§10.1).
- **The IITM EU CMS app** — no integration exists to test (§1.8).
- **Penetration testing** — out of scope per §11.1.
