# 8. Analytics & Telemetry

## 8.1 Where the numbers come from

Analytics reads only from `audit_event` and `point_ledger`. Because every status change is written
through the single transition path (§4.1) with `from_state`, `to_state` and `occurred_at`, the
lifecycle of any complaint is fully reconstructible from the log — we never needed to add
special-purpose measurement columns, and no metric can drift out of sync with the operational data.

Stage durations are the intervals between consecutive transitions:

| Stage | From → To | What it actually measures |
|---|---|---|
| **Dispatch latency** | `NEW` → `ASSIGNED` | Our system's own responsiveness |
| **Pickup latency** | `ASSIGNED` → `INSPECTING` | How long before an attender starts |
| **Inspection latency** | `INSPECTING` → `AWAITING_CMS` | Time to confirm the defect on site |
| **Filing latency** | `AWAITING_CMS` → `IN_PROGRESS` | Delay in getting a CMS docket |
| **Repair latency** | `IN_PROGRESS` → `RESOLVED` | The actual fix, excluding holds |
| **Closure latency** | `RESOLVED` → `CLOSED` | Manager confirmation lag |

Time spent in `ON_HOLD` is subtracted from the enclosing stage (§4.2, T12). Without that, "waiting
three days for the Engineering Unit to send a plumber" would be recorded as attender slowness and
would point the bottleneck analysis at exactly the wrong place.

## 8.2 MTTR and bottleneck detection

**Resolution time** = `RESOLVED.occurred_at − complaint.reported_at − holdDuration`.

The proposal asks for MTTR, which conventionally means the arithmetic mean. We compute and display
the mean, but present the **median as the headline figure**, with p90 alongside:

At ~20 complaints/day, one complaint that sat over a long weekend moves the mean by hours while the
typical experience is unchanged. Reporting only the mean would make the dashboard react to noise
and would quietly mislead the manager about what is normal. Showing mean, median and p90 together
costs nothing and makes the skew visible rather than hidden.

**Bottleneck identification**, per domain *d*:

```
For each stage s:  share(s, d) = median duration of s in d
                                 ──────────────────────────────
                                 Σ median duration of all stages in d

bottleneck(d) = argmax share(s, d),  reported only when share ≥ 0.35
```

The 0.35 floor exists so we do not report a "bottleneck" when time is spread evenly — a stage that
is 26% of a five-stage pipeline is not a bottleneck, it is arithmetic. Each result is displayed
with its sample size, and suppressed entirely below `n = 10` for that domain.

**SLA targets** driving breach metrics (OQ-2, pending confirmation):

| Domain | P1 | P2 | P3 |
|---|---|---|---|
| Electrical | 4h | 8h | 72h |
| RO Plant | 6h | 12h | 72h |
| Civil | 8h | 24h | 72h |
| Network | 8h | 24h | 72h |

Measured in working hours (08:00–20:00 IST, OQ-6). These are configuration, not constants.

## 8.3 Anomaly detection

### The honest constraint

This deserves stating plainly, in the design doc rather than after the fact: with **4 attenders and
~20 complaints/day**, we are doing statistics on small samples. A textbook z-score over a 30-day
window has *n* ≈ 600 overall but only *n* ≈ 150 per domain and *n* ≈ 5 per domain-day. Ordinary
mean-and-standard-deviation outlier detection on data this thin and this skewed will produce
frequent false alarms, and a dashboard that cries wolf is one the manager stops reading — which is
a worse outcome than having no anomaly detection at all.

Three design responses follow from this:

1. **Robust statistics only.** Median and MAD, never mean and σ, for outlier detection. Both have a
   50% breakdown point; the mean has 0%.
2. **Minimum sample gates.** Every detector declares an *n* below which it emits nothing.
3. **Evidence with every alert.** Each alert carries its statistic, its threshold and its *n*, so a
   sceptical manager can judge it rather than trust it.

### Detectors

**A1 — Prolonged resolution latency.** Modified z-score (Iglewicz & Hoaglin):

```
MAD  = median( |xᵢ − median(x)| )
Mᵢ   = 0.6745 · (xᵢ − median(x)) / MAD
flag if Mᵢ > 3.5
```

Reference set: same domain, trailing 90 days, resolved complaints. **Gate: n ≥ 12.** If `MAD = 0`
(possible when most complaints resolve in an identical rounded time) fall back to flagging
`xᵢ > 3 × median`, since the modified z-score is undefined there.

**A2 — Chronic location / band-aid repair.** No statistics at all, just counting:

```
flag room r if  ≥3 complaints in the same domain from r within 30 days
severity HIGH if ≥3 within 14 days
```

Exact counting is *more* reliable than a statistical test at this sample size, and this detector
directly serves the proposal's "chronic fixture defects / band-aid repairs" requirement. It is also
the most operationally actionable output the system produces: three electrical faults in room 214
in a fortnight means the fixture needs replacing, not another patch.

**A3 — Arrival volume surge.** Poisson tail test against an EWMA baseline:

```
λ̂ₜ = α·xₜ₋₁ + (1−α)·λ̂ₜ₋₁          α = 0.3
flag if  P(X ≥ xₜ | Poisson(λ̂ₜ)) < 0.01
```

Poisson rather than Gaussian because daily arrival counts are small non-negative integers — exactly
what Poisson models and where a normal approximation is worst. **Gate: ≥14 days of history.**
Separate baselines per wing, so a burst localised to one wing (a failed riser, a water outage) is
caught even when the hostel total looks normal.

**A4 — SLA breach cluster.** Flag when breaches in a rolling 7 days exceed 2× the trailing 8-week
rate for that domain. **Gate: ≥4 weeks of history.**

**A5 — Workload imbalance.** Flag when `max(L(a)) / mean(L(a)) > 2.0` across active attenders for
3 consecutive days — this is a check on our own dispatch heuristic (§6) as much as on the attenders.

### Alert storage

```sql
CREATE TABLE analytics_alert (
    id            BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    detector      VARCHAR(8)   NOT NULL,      -- A1..A5
    severity      VARCHAR(8)   NOT NULL,      -- INFO | WARN | HIGH
    subject_type  VARCHAR(24)  NOT NULL,      -- COMPLAINT | ROOM | DOMAIN | ATTENDER | WING
    subject_ref   VARCHAR(64)  NOT NULL,
    summary       VARCHAR(255) NOT NULL,
    evidence      TEXT         NOT NULL,      -- JSON: statistic, threshold, n, window
    detected_at   TIMESTAMP    NOT NULL,
    acknowledged_at TIMESTAMP,
    acknowledged_by BIGINT REFERENCES app_user(id),
    dedupe_key    VARCHAR(128) NOT NULL,
    CONSTRAINT uq_alert_dedupe UNIQUE (dedupe_key)
);
```

`dedupe_key` (e.g. `A2:ROOM:214:ELECTRICAL:2026-09`) means a nightly scan re-raising the same
finding updates rather than duplicates it — otherwise a genuine month-long chronic-defect condition
would generate thirty identical alerts and bury everything else.

Detectors run on a nightly `@Scheduled` scan, holding the same ShedLock as ingestion (§2.5).
They are pure reads over `audit_event`; they never write to operational tables.

## 8.4 Dashboard visualizations

Chart.js, all fed from JSON endpoints (§9.4):

| Visualization | Type | Answers |
|---|---|---|
| Arrival velocity | Line, 30 days, per domain | "Are complaints trending up?" |
| Resolution funnel | Stacked bar by stage | "Where does time go?" |
| MTTR by domain | Bar + median/p90 error markers | "Which category is slowest?" |
| Attender workload | Horizontal bar, live | "Who is swamped right now?" |
| SLA breach distribution | Donut by domain | "What are we failing?" |
| Leaderboard | Ranked table, live via SSE | "Who is ahead this month?" |
| Anomaly feed | Card list, severity-sorted | "What needs my attention?" |

Every chart that renders a derived statistic shows its **sample size**. A chart drawn from six data
points must not look as authoritative as one drawn from six hundred, and the *n* is the cheapest
possible way to convey that.

## 8.5 Explicit limitations

Recorded here so they can be stated honestly in the viva rather than discovered by a grader:

- Anomaly detection is **advisory**, never automated action. Nothing is reassigned or escalated on
  the strength of a detector firing.
- A1 assumes resolution times within a domain are drawn from one distribution. A domain with two
  genuinely distinct populations (5-minute fuse swaps and 3-day rewiring jobs) will flag the long
  tail as anomalous. Mitigation: segment by priority once volume allows.
- A3's baseline is contaminated by the surges it is meant to detect — a sustained high period
  becomes the new normal within roughly 1/α ≈ 3 days.
- The first four weeks after go-live produce almost no alerts by design, because every gate is
  unmet. This is correct behaviour and should not be read as a defect.
