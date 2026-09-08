# 6. Dispatch Heuristic

## 6.1 Problem statement

Given a classified complaint *c* and the set of attenders *A*, choose the attender who should do the
work — accounting for who covers that floor, who is actually on duty, and who is already
overloaded. The proposal requires this be a genuine heuristic rather than round-robin, and that it
be **formally specified** so its behaviour is testable.

Requirements the formulation must satisfy:

| R | Requirement |
|---|---|
| R1 | Always terminates with either an assignment or an explicit, recorded triage reason |
| R2 | Deterministic — identical inputs give identical output, including tie-breaks |
| R3 | Explainable — the manager can see *why* an attender was chosen |
| R4 | No starvation — one attender cannot absorb everything while others idle |
| R5 | Tunable without a redeploy |

## 6.2 Formulation

### Stage 1 — hard eligibility filter

Availability is a **gate, not a score**. An attender on leave must never be chosen, no matter how
well they score elsewhere; folding it into a weighted sum would let a strong floor match outvote
the fact that someone is not at work.

```
E = { a ∈ A :  a.active
             ∧ availabilityAt(a, now) = AVAILABLE
             ∧ ¬ alreadyAssignedTo(a, c) }
```

If `E = ∅` → **no assignment**, complaint → `TRIAGE` with reason `NO_AVAILABLE_ATTENDER` (T3).
This is the proposal's "all assigned floor attenders unavailable" edge case, and it is handled by
construction rather than by a special branch.

### Stage 2 — score the eligible set

```
Score(a, c) = w_f · Floor(a, c)
            + w_l · Load(a)
            + w_s · Skill(a, c.domain)
            + w_c · Continuity(a, c)
```

Every component is normalised to **[0, 1]** and weights sum to 1, so `Score ∈ [0, 1]` and the
weights are directly interpretable as "how much does this factor matter".

#### Floor proximity — `Floor(a, c)`

Uses the coverage in force *at the complaint's report time*, not today's (§3.4), so historical
decisions stay explainable.

| Situation | Value |
|---|---|
| `PRIMARY` coverage of the complaint's floor | **1.00** |
| `SECONDARY` coverage of that floor | **0.70** |
| Covers an adjacent floor in the same wing (\|Δ\| = 1) | **0.40** |
| Same wing, \|Δ\| ≥ 2 | `max(0.10, 0.35 − 0.05·|Δ|)` |
| Different wing | **0.10** |

Non-zero floors for distant attenders are intentional: when the primary is unavailable, someone
still has to go, and a zero would make the whole term uninformative in exactly that case.

If `c.floor_id IS NULL` (room number unparseable, OQ-4) the term is undefined. Rather than guess,
the complaint goes to `TRIAGE` with reason `UNMAPPED_LOCATION` — dispatching on a location we could
not read is worse than asking.

#### Workload — `Load(a)`

Higher score = *more* free capacity, so the term pushes work toward idle attenders.

```
L(a) = Σ  π(t)          over tasks t currently ACTIVE for a
       t                (status ∈ {ASSIGNED, INSPECTING, AWAITING_CMS, IN_PROGRESS})

where π(P1) = 3,  π(P2) = 2,  π(P3) = 1        (ON_HOLD tasks count 0)

Load(a) = 1 − min(1, L(a) / L_cap)              L_cap default 8
```

Weighting by priority rather than counting tickets reflects reality: three P1 electrical faults is
not the same workload as three P3 cosmetic complaints. `ON_HOLD` work is excluded because the
attender is genuinely not blocked by it (§4.2, T12).

#### Domain skill — `Skill(a, d)`

Defaults to a neutral **0.5** for every attender/domain pair, because we do not yet know whether
attenders specialise (OQ-5). If specialisations exist the manager sets them; if historical data
accumulates, the value can be derived from that attender's resolution rate and relative MTTR in
that domain. Neutral defaults mean the term contributes nothing until we have real information —
the heuristic never invents a preference it has no evidence for.

#### Continuity — `Continuity(a, c)`

```
1.0  if a resolved a complaint in the same room within 14 days
0.5  if a resolved a complaint on the same floor within 14 days
0.0  otherwise
```

A recurring fixture fault is faster to fix for whoever saw it last, and — usefully — it is also the
same signal §8.3 uses to detect chronic defects and band-aid repairs.

#### Default weights

| Weight | Value | Rationale |
|---|---|---|
| `w_f` floor | **0.45** | The stakeholder's stated primary criterion |
| `w_l` load | **0.30** | Strong enough to override floor when someone is badly overloaded |
| `w_s` skill | **0.15** | Inert until specialisations are known |
| `w_c` continuity | **0.10** | A tiebreak-scale nudge, not a driver |

All five values, plus `L_cap`, `τ`, and the proximity table, are `@ConfigurationProperties`
(R5). §12 pins their behaviour with tests that assert *relationships* ("an overloaded primary
loses to an idle secondary") rather than exact float outputs, so tuning does not break the suite.

### Stage 3 — select

```
best = argmax Score(a, c)
        a∈E
```

Tie-break chain, applied in order (R2, R4):

1. Fewest tasks assigned **today** — the anti-starvation term
2. Longest time since last assignment
3. Lowest `app_user.id` — guarantees full determinism

Then: create `task`, create `task_assignment` with `assign_method = AUTO`, and persist the full
score breakdown into `task_assignment.score_snapshot` as JSON (R3):

```json
{"chosen": 7, "score": 0.781,
 "components": {"floor": 1.0, "load": 0.625, "skill": 0.5, "continuity": 0.0},
 "weights": {"floor":0.45,"load":0.30,"skill":0.15,"continuity":0.10},
 "runnersUp": [{"attenderId": 9, "score": 0.503}],
 "tieBreak": null}
```

The snapshot is what makes "why did Ravi get this?" answerable months later, and it is what the
dashboard renders in the assignment tooltip.

### Capacity overflow

If every eligible attender is at or over `L_cap`, we still assign to the best-scoring one — work
cannot simply vanish — but emit a `CapacityExceeded` notification so the manager sees the queue is
saturated. Overflowing visibly beats triaging silently.

## 6.3 Shared tasks

For `task.shared = true` (common-room overhaul, furniture transport), dispatch selects the **top
*k*** by score instead of the top 1, where *k* is set by the manager when posting the duty. Each
selected attender gets their own `task_assignment` row and, on completion, their own ledger credit
under their own idempotency key. The concurrency consequences are §7.5.

## 6.4 Degenerate cases

| Case | Detection | Outcome |
|---|---|---|
| No attender available | `E = ∅` | `TRIAGE` / `NO_AVAILABLE_ATTENDER` |
| Room number unparseable | `floor_id IS NULL` | `TRIAGE` / `UNMAPPED_LOCATION` |
| Floor exists but nobody covers it | All `Floor = 0.10` | Assign anyway (least-loaded wins), warn `UNCOVERED_FLOOR` |
| Complaint `UNCLASSIFIED` | Domain gate | Never reaches dispatch — triaged at T3 |
| Every attender at capacity | `∀a: L(a) ≥ L_cap` | Assign best, notify `CapacityExceeded` |
| Exact score tie | Equal `Score` | Tie-break chain, §6.2 stage 3 |
| Manager disagrees | — | `manager_reassign` (T6); both assignments audited |

Every row terminates (R1). There is no path where a complaint silently has no owner — the invariant
the dispatch module is responsible for (§2.3).

## 6.5 Why greedy, and not optimal assignment

A global optimum over a batch (Hungarian algorithm, min-cost flow) is the textbook answer and is
**deliberately rejected** here:

- Complaints arrive **one at a time** from the poll. A batch is typically 1–3 items, and the optimal
  assignment of a 1-item batch is the greedy one.
- With |A| = 4, the theoretical gain over a whole day is negligible, while the cost is real:
  batch optimisation would mean holding complaints back to accumulate a batch, adding latency to
  exactly the P1 cases that matter most.
- Greedy is explainable (R3). "Ravi scored 0.78, Kumar 0.50" is something the manager can argue
  with. "The global cost matrix minimised at this permutation" is not.

Complexity is `O(|A| · log|A|)` per complaint — trivially within NFR-3. This trade-off is recorded
as ADR-005.
