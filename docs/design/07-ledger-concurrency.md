# 7. Points Ledger & Concurrency Control

## 7.1 What can actually go wrong

The proposal calls for "strict transactional isolation and optimistic locking to prevent race
conditions and double-crediting". Before choosing mechanisms, here is the concrete threat model —
five races that will genuinely occur in this deployment:

| # | Race | Realistic trigger | Consequence if unhandled |
|---|---|---|---|
| C1 | **Double resolve** | Attender taps *Resolve*, the response is lost on hostel Wi-Fi, they tap again | +4 points for one job — corrupts the bonus |
| C2 | **Lost update** | Manager reassigns a task at the same moment the attender resolves it | Credit goes to the wrong person, or the reassignment silently vanishes |
| C3 | **Shared-task double completion** | Two attenders on a shared duty mark done simultaneously | Task completion logic runs twice; over-notification, possible double close |
| C4 | **Over-claim** | Special duty with `k = 1` claimed by two attenders in the same second | Two people show up; one is credited for work they did not get |
| C5 | **Torn leaderboard read** | Dashboard reads while credits are being written | Standings briefly inconsistent with the ledger |

These are not hypothetical. C1 in particular is *the expected case* on a phone in a hostel corridor,
not an exotic edge.

## 7.2 Mechanisms

We use four layered mechanisms rather than one, because each race has a different shape:

| M | Mechanism | Addresses |
|---|---|---|
| **M1** | Optimistic locking (`@Version`) on every mutable aggregate | C2, C3 |
| **M2** | `UNIQUE (idempotency_key)` on `point_ledger` | C1 |
| **M3** | Client-supplied `Idempotency-Key` on state-changing requests | C1 (at HTTP level, before it reaches the domain) |
| **M4** | Short pessimistic lock on the task row for claims | C4 |
| **M5** | Leaderboard derived by query, never stored | C5 |

A note on the layering: M2 alone is sufficient to make double-crediting impossible. M3 exists so
the *user experience* of a retry is a clean success rather than a swallowed conflict, and M1 exists
for the non-ledger state that also needs protecting.

## 7.3 Why double-crediting is structurally impossible

This is the central correctness argument of the system.

The credit for resolving a complaint is written with a key derived **deterministically from facts
that identify the work**, not from anything about the request:

```java
String key = "RESOLVE:" + task.getId() + ":" + attender.getId()
           + (reopenCount > 0 ? ":r" + reopenCount : "");
```

Crediting is then:

```java
@Transactional
void credit(Task task, Attender a, int points, String reasonCode, String key) {
    try {
        ledgerRepo.saveAndFlush(new LedgerEntry(a, task, reasonCode, points, key, ...));
    } catch (DataIntegrityViolationException dup) {
        // uq_ledger_idem — this exact credit already exists. Not an error.
        log.info("Credit {} already applied; no-op", key);
    }
}
```

The guarantee does not rest on transaction ordering, isolation level, retry logic, or on the
application checking-then-inserting (which is itself a race). It rests on a **database uniqueness
constraint**, which the database enforces atomically no matter how many concurrent transactions
attempt the insert. Whether the duplicate arrives 20 milliseconds later on a retried HTTP request
or 20 minutes later from a queued action replayed after a signal drop, the outcome is identical:
one credit.

The `:r{n}` suffix handles the reopen cycle (T17). After a reopen the reopen counter increments, so
a legitimate re-resolution produces a *different* key and is correctly credited again — the
constraint blocks duplicates without ever blocking genuine repeat work.

**Consequence:** `attender.points` does not exist as a column anywhere in the schema. There is no
counter to increment, therefore no lost-update on a counter, therefore nothing to reconcile. A
balance is a `SUM` over immutable facts.

## 7.4 Idempotency at the HTTP boundary

Every state-changing attender action carries a key generated on the client:

```
POST /api/attender/tasks/42/resolve
Idempotency-Key: 6f1c2b90-...        # UUID v4, persisted in localStorage until ACKed
```

The mobile UI generates the key **before** the first send and reuses it for every retry of that
same user intent. Server-side, a filter records `(key, endpoint, status, response)` for 24 hours;
a replay returns the stored response without re-executing.

This is what makes the flaky-connection story work end to end. The attender taps *Resolve*, loses
signal, the service worker queues the request, and when connectivity returns it replays with the
same key. Whether the original request actually reached the server or not, the attender sees one
success and the ledger holds one credit.

## 7.5 Shared tasks

For `task.shared = true`, each assignee has an independent `task_assignment` row and an independent
idempotency key `DUTY:{task_id}:{attender_id}`. Two attenders completing simultaneously therefore
credit *different* keys — no contention, both correct.

The contended part is the **task's own completion**, which depends on all assignees being done:

```java
@Transactional
void markMyPartDone(long taskId, long attenderId, String idemKey) {
    Task task = taskRepo.findById(taskId).orElseThrow();          // loads @Version
    TaskAssignment mine = assignmentRepo.findActive(taskId, attenderId)
                              .orElseThrow(NotAssigned::new);
    mine.setStatus(COMPLETED);
    ledger.credit(task, attenderId, task.getPointValue(), "SPECIAL_DUTY", idemKey);

    if (assignmentRepo.countActiveIncomplete(taskId) == 0) {
        workflow.transition(task, TASK_COMPLETED);                // bumps task.version
    }
}
```

Under concurrency both transactions read `task.version = v`. The one that commits second finds the
version has moved and throws `ObjectOptimisticLockingFailureException`. It is retried once
transparently; on retry it re-reads, sees its peer already completed the task, and skips the
transition. Its own credit — already keyed uniquely — is unaffected either way. C3 resolved.

## 7.6 Claiming a duty (C4)

Claiming is a genuine read-modify-write on a count (`active claims < k`), and OCC handles it, but
the retry-on-conflict path is user-visible and confusing for someone standing in a corridor. Since
contention is trivially low — at most four people, claiming a handful of duties a week — we take a
**short pessimistic lock** and give the loser an immediate, clear answer:

```java
@Transactional
void claim(long taskId, long attenderId) {
    Task t = taskRepo.findByIdForUpdate(taskId);      // SELECT ... FOR UPDATE
    if (t.getStatus() != OPEN) throw new AlreadyClaimed();
    if (assignmentRepo.countActive(taskId) >= t.getSlots()) throw new NoSlotsLeft();
    assignmentRepo.save(new TaskAssignment(t, attenderId, CLAIMED));
}
```

The lock is held for microseconds. `uq_task_attender` (§3.6) additionally makes a double-claim by
the *same* attender impossible regardless. Choosing pessimistic locking here, against the general
preference for OCC elsewhere, is recorded as ADR-006 — the deciding factor is UX clarity at
negligible contention, not performance.

## 7.7 Optimistic locking and conflict UX

`@Version` guards `complaint`, `task`, `task_assignment`, `floor_assignment` and `app_user`.

| Situation | Handling |
|---|---|
| Background/system job hits a conflict | Retry up to 3× with jitter, then log and skip |
| Attender action hits a conflict | Retry once transparently; then HTTP `409` with a plain-language message |
| Manager action hits a conflict | HTTP `409`, UI shows the current state and asks for confirmation |

C2 concretely: the manager reassigns while the attender resolves. Both mutate `task_assignment`.
One commits; the other gets a 409. If the attender lost, they are told "this task was reassigned to
someone else" — which is true, and far better than silently crediting a person who no longer owns
the work.

**Isolation level: `READ COMMITTED`** (the PostgreSQL default). We deliberately do not raise it.
Every invariant we depend on is enforced by a uniqueness constraint or a version check, both of
which hold at `READ COMMITTED`. `SERIALIZABLE` would add serialization failures and retry
complexity to buy a guarantee we have already obtained more cheaply. Recorded as ADR-007.

## 7.8 The leaderboard

A derived projection, never stored state:

```sql
SELECT u.id, u.display_name,
       COALESCE(SUM(l.points), 0)                                        AS total,
       COALESCE(SUM(CASE WHEN l.points > 0 THEN l.points END), 0)        AS earned,
       COALESCE(SUM(CASE WHEN l.points < 0 THEN l.points END), 0)        AS deducted,
       COUNT(CASE WHEN l.reason_code = 'COMPLAINT_RESOLVED' THEN 1 END)  AS resolved
  FROM app_user u
  LEFT JOIN point_ledger l ON l.attender_id = u.id AND l.period_key = :period
 WHERE u.role = 'ATTENDER' AND u.active
 GROUP BY u.id, u.display_name
 ORDER BY total DESC, resolved DESC, u.display_name;
```

`LEFT JOIN` so an attender with zero activity still appears — a missing row would read as a bug to
the manager. Ordering is fully deterministic through the tie-breaks.

At NFR-1 scale (~50k ledger rows/year, ~600/month per period) this is an index scan over a few
hundred rows and needs **no caching, no materialized view and no denormalised counter**. Adding one
would trade away the very property that makes the ledger correct. If volume ever justified it, the
right move would be a materialized view refreshed on commit — not a mutable counter.

## 7.9 Month boundaries

`period_key` is derived from `effective_date`, which is the **Asia/Kolkata business date** (NFR-7),
not the UTC instant. A task resolved at 02:00 IST on 1 October belongs to October, which is what
the manager expects; storing UTC and grouping naively would have silently filed it under September.

A penalty for late September behaviour, logged on 2 October, is entered with an explicit
`effective_date` of the September date. It lands in the September period, the audit records both
the effective date and the entry date, and if the September leaderboard has already been exported
the manager is warned that a closed period is being amended. We allow it — the alternative is a
manager who cannot correct a genuine mistake — but never silently.
