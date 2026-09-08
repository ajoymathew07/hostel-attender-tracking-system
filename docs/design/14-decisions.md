# 14. Decision Record

Architecture Decision Records for the choices that shaped this design. Each states what was
decided, what it cost, and what would make us revisit it.

---

## ADR-001 · PostgreSQL everywhere, not SQLite/H2 for development

**Status:** Accepted · 8 Sep 2026

**Context.** The proposal specified SQLite or embedded H2 for local development with PostgreSQL in
production. That is a common and convenient split.

**Decision.** PostgreSQL in development (Docker Compose), test (Testcontainers) and production. H2
is retained *only* for the offline fallback jar (§2.6).

**Rationale.**
- SQLite serialises writers. The shared-task concurrency control (§7) is a headline feature of this
  project; on SQLite the tests would pass for the wrong reason and prove nothing about production.
- H2 and Postgres diverge on constraint-violation behaviour, isolation semantics and locking —
  exactly the surface our correctness argument (§7.3) rests on.
- Dialect drift in JPA surfaces late and expensively.

**Consequences.** Requires Docker locally. Schema is constrained to the portable SQL subset (§3.8)
so the fallback jar still runs. Accepted: the constraint costs a few Postgres-only conveniences and
buys tests that mean what they say.

**Revisit if:** the offline fallback becomes the primary deployment.

---

## ADR-002 · Local credentials, invite-only; no Google OAuth

**Status:** Accepted · 8 Sep 2026

**Context.** Users are one manager and ~4 attenders. Requirements: people without institute
accounts must be able to use the system, but only a specific pre-registered set may have access.

**Decision.** No self-registration. Every principal is a pre-provisioned `app_user` row. Manager
authenticates with username + password; attenders with a **per-user** 6-digit PIN plus a
device-bound persistent token. No OAuth of any kind.

**Rationale.**
- **Decisive:** the proposal requires an offline local-server fallback for outages (§2.6). Google
  OAuth cannot complete a redirect flow without internet — it would fail during precisely the
  outage the fallback exists for.
- Local credentials require no institute account, so "non-institute people must have access" is met
  by *removing* an external dependency rather than adding one.
- "Only a specific set registered" is really the invariant *no self-registration*, which holds
  independently of credential type.
- OAuth would add a consent screen, a public redirect URI, extra secrets and a second login path to
  test — for one user's convenience.

**Consequences.** We store password and PIN hashes, so we own credential hygiene: BCrypt cost 12,
lockout, rate limiting, audited attempts (§11.2). A 6-digit PIN is weak in isolation; the design
states this openly and compensates with lockout, no username enumeration, a small blast radius per
attender, and one-click device revocation.

**Revisit if:** the user population grows beyond ~20, or the institute mandates SSO.

---

## ADR-003 · SSE, not WebSocket, for live updates

**Status:** Accepted · 8 Sep 2026

**Context.** The proposal listed "Spring WebSocket / SSE" for real-time manager notifications.

**Decision.** Server-Sent Events, with a transactional outbox for replay.

**Rationale.** The requirement is strictly one-directional — server pushes duty updates to one
manager dashboard. SSE is plain HTTP: it traverses PaaS proxies cleanly, reconnects automatically,
and needs no STOMP broker or fallback negotiation. Its native `Last-Event-ID` mechanism pairs
exactly with an outbox table (§3.7) to give gap-free replay after the free tier drops an idle
connection.

**Consequences.** No client→server push channel; state changes go over normal HTTP POSTs, which is
what we wanted anyway since they must pass through the state machine. SSE is limited to ~6
concurrent connections per browser origin — irrelevant at one manager.

**Revisit if:** bidirectional realtime is ever needed (e.g. live attender-to-manager chat).

---

## ADR-004 · Rule-based lexicon as the primary classifier; OpenNLP deferred

**Status:** Accepted · 8 Sep 2026

**Context.** The proposal names Apache OpenNLP for "rule-assisted text categorization", with an
acceptance criterion of ≥90% accuracy.

**Decision.** A weighted lexicon with fuzzy matching and a confidence gate is the primary and only
required classifier. OpenNLP `DocumentCategorizer` is designed in as an optional second stage,
consulted only for items the rules route to triage, and is tagged Extended (§13.3).

**Rationale.**
- A supervised model needs a few hundred labelled examples per class. We have no confirmation that
  volume of labelled data exists, and the project cannot depend on an unverified input.
- Complaint text is short, misspelled and often code-mixed Tamil/English transliteration — where
  lexicon + edit-distance degrades gracefully and a thin-data model does not.
- The lexicon is editable by the manager without a redeploy.
- Every decision is explainable by naming the terms that fired — which matters for the viva and for
  the manager's trust in the triage card (§10.3).

**Consequences.** Requires curating a domain lexicon (a few hours, with the stakeholder). Ceiling on
accuracy is lower than a well-trained model's — mitigated by the confidence gate routing uncertain
items to triage rather than guessing. Production triage corrections accumulate the labelled corpus
that would make stage two viable later, so nothing is foreclosed.

**Revisit if:** holdout accuracy stalls below 90% with coverage above 70%.

---

## ADR-005 · Greedy per-complaint dispatch, not global optimisation

**Status:** Accepted · 8 Sep 2026

**Context.** Assignment could be formulated as a global optimisation (Hungarian algorithm, min-cost
flow) over a batch of complaints and attenders.

**Decision.** Greedy `argmax` of a weighted score, per complaint, with a deterministic tie-break
chain (§6.2).

**Rationale.** Complaints arrive one at a time from a 3-minute poll; a typical batch is 1–3 items,
and the optimal assignment of a 1-item batch *is* the greedy one. With |A| = 4 the theoretical gain
is negligible, while batch optimisation would require holding complaints back to accumulate a
batch — adding latency to exactly the P1 cases that matter most. Greedy is also explainable, and
"Ravi 0.78 vs Kumar 0.50" is something a manager can challenge.

**Consequences.** No global optimality guarantee. Anti-starvation must be handled explicitly, which
it is, via the tie-break chain and the load term.

**Revisit if:** attender count grows past ~15, or complaints start arriving in large batches.

---

## ADR-006 · Pessimistic locking for duty claims, optimistic elsewhere

**Status:** Accepted · 8 Sep 2026

**Context.** Claiming a special-duty slot is a read-modify-write on a count (`active < k`). The
house style everywhere else is optimistic locking.

**Decision.** `SELECT ... FOR UPDATE` on the task row for claims only.

**Rationale.** Optimistic locking would work correctly, but its user-visible failure is a retry
prompt for someone standing in a corridor. Contention here is trivially low — 4 people, a handful
of duties a week — so a lock held for microseconds costs nothing and lets the loser get an
immediate, unambiguous "already claimed". The clarity is worth the inconsistency in style, and the
inconsistency is documented rather than accidental.

**Consequences.** One deliberate deviation from the OCC default. `uq_task_attender` provides a
second, independent guarantee against self-double-claim.

**Revisit if:** claim contention ever becomes non-trivial.

---

## ADR-007 · `READ COMMITTED` isolation, not `SERIALIZABLE`

**Status:** Accepted · 8 Sep 2026

**Context.** The proposal calls for "strict transactional isolation". The strongest reading is
`SERIALIZABLE`.

**Decision.** PostgreSQL's default `READ COMMITTED`, with correctness carried by uniqueness
constraints and version checks.

**Rationale.** Every invariant we depend on — no double-credit, no lost update, no over-claim — is
enforced by a `UNIQUE` constraint, an `@Version` check, or a row lock. All three hold at
`READ COMMITTED`. `SERIALIZABLE` would add serialization failures and retry complexity across every
transaction to buy a guarantee already obtained more cheaply and more locally.

**Consequences.** Non-repeatable reads are possible within a transaction; no invariant relies on
their absence. Any *future* invariant depending on a multi-row consistent snapshot would need
explicit locking or a stronger level for that transaction alone.

**Revisit if:** a new requirement needs cross-row consistency that constraints cannot express.

---

## ADR-008 · Append-only ledger; no stored point balance

**Status:** Accepted · 8 Sep 2026

**Context.** The obvious implementation of a point total is a counter column on the attender.

**Decision.** `point_ledger` is append-only. Balances are `SUM` aggregates. Corrections are
compensating rows, never updates or deletes. No `attender.points` column exists.

**Rationale.**
- A counter has a lost-update race on every increment; an insert with a unique key does not.
- Bonus disputes require answering "why is this number 34?" — a ledger answers line by line, a
  counter cannot answer at all.
- Reversal-by-compensation keeps the reopen cycle (T17) auditable.
- At NFR-1 volume the aggregate is an index scan over a few hundred rows; there is no performance
  argument for denormalising.

**Consequences.** Every point read is an aggregate query. Accepted, and measured: ~600 rows per
period. If volume ever justified it, the right answer is a materialized view refreshed on commit —
never a mutable counter, which would give back the property that makes this design correct.

**Revisit if:** ledger volume exceeds ~1M rows.

---

## ADR-009 · Content-derived identity for source rows, not row index

**Status:** Accepted · 8 Sep 2026

**Context.** Ingestion must detect new, edited and deleted Google Form responses without a change
feed.

**Decision.** Identity is `response_key = SHA256(timestamp ‖ email)`; change detection is a separate
`content_hash` over all cells. Row index is stored as a positional hint only.

**Rationale.** Deleting one sheet row shifts every index below it. Keying on `(sheet, row_index)`
would report the entire tail as amended after a single deletion, and — depending on the
reconciliation rule — could duplicate hundreds of complaints in one poll. A Google Form response
retains its submission timestamp and submitter across edits, so a content-derived key is stable
across both row shifts and edits. Separating identity from change detection is what lets one
algorithm handle inserts, amendments and deletions coherently.

**Consequences.** Requires the Form to collect email, or timestamp-only keys with an in-batch
disambiguator for same-second submissions (§5.3). Deletion can only be concluded from a **full**
scan, never an incremental one — the most important guard in the algorithm.

**Revisit if:** the Form is replaced by a source with stable native row IDs.

---

## ADR-010 · Modular monolith, not microservices

**Status:** Accepted · 8 Sep 2026

**Context.** The proposal describes several distinct subsystems (ingestion, dispatch, ledger,
analytics) that could be separate services.

**Decision.** One Spring Boot jar with enforced internal module boundaries (§2.3–2.4), ports and
adapters only where an external system is involved.

**Rationale.** At ≤50 complaints/day with 4 users, distribution would add network failure modes,
deployment complexity and eventual-consistency problems in exchange for scale we will never need.
Critically, our core correctness guarantees (§7) rely on **single-database transactions**; splitting
the ledger from the workflow would turn a uniqueness constraint into a distributed-transaction
problem. Module boundaries are drawn so a component *could* be extracted if that ever changed.

**Consequences.** One deployment unit, one database, one failure domain. ArchUnit enforces the
boundaries that would otherwise erode (§2.4).

**Revisit if:** the system is ever extended to multiple hostels with independent operations.

---

## ADR-011 · Web Push with VAPID for attender notifications, not FCM or a native app

**Status:** Accepted · 8 Sep 2026

**Context.** Attenders must be alerted on their phone when work is assigned, without having to open
the app and look (G8). This was added after the proposal, which had listed manager-side SSE only.

**Decision.** The **Web Push protocol with VAPID**, delivered to the service worker the PWA already
registers for offline replay. No Firebase project, no Google Play binary, no third-party push SDK.
Library: `nl.martijndwars:web-push`.

**Rationale.**
- The service worker is **already required** for the offline action queue (§10.2). Push reuses that
  registration, so the marginal cost is a subscription table, an endpoint pair and a message handler
  — not a new client platform.
- VAPID needs only a self-generated key pair. FCM would mean a Firebase project, a Google account
  dependency and an SDK, to reach the same Android Chrome that speaks Web Push natively.
- A native app would need Play Store distribution, signing keys and an update path for four people
  who already use the web app — plainly disproportionate.
- Payloads are end-to-end encrypted, so the relaying push service sees only ciphertext.

**Consequences.**
- Attenders must grant browser notification permission, and a denial is not recoverable from inside
  the app. Mitigation: ask only *after* first successful login, once the app has shown its value
  (§10.2), and expose a settings toggle so people dial alerts down rather than revoking them.
- Push **cannot work on the offline fallback jar** (§2.6) — there is no internet to reach a push
  service. Acceptable: during an outage the manager is running roll-call in person anyway, which is
  the assisted-admin path (§9.4).
- iOS would require an installed PWA on Safari 16.4+. Out of scope; attenders are on Android.
- Delivery is best-effort and no state transition depends on it (NFR-10). The in-app queue stays the
  source of truth, so a missed push degrades convenience, never correctness.

**Revisit if:** attenders move to iOS in numbers, or delivery reliability proves inadequate in the
field — at which point FCM's data channel would be the next step, not a native app.
