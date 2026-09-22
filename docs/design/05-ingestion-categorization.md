# 5. Ingestion & Categorization

## 5.1 The hard part

Google Sheets is not an event source. It offers no change feed, no per-row identity and no
"give me what changed since X". We poll a mutable document and must derive deltas ourselves,
while guaranteeing NFR-1's headline property: **a complaint is never created twice**.

Three properties of the real sheet make the naive approach fail:

1. **Row indices are not stable.** If the manager deletes row 40, every row below shifts up by one.
   Keying on `(sheet_id, row_index)` would then report the entire tail of the sheet as amended and,
   depending on the reconciliation rule, could duplicate hundreds of complaints in one poll.
2. **Responses can be edited.** With "edit response" enabled, a resident can change their answer
   after submission. Content changes; it is still the same complaint.
3. **The instance sleeps.** On free-tier hosting the poller may not run for hours (§2.5): a sleeping
   instance has no scheduler. A Google Apps Script trigger on the response sheet wakes it when a
   response arrives, but the trigger is a *hint*, not a delivery mechanism — it carries no row data
   and may fire twice or not at all. Any design that depends on "what changed since the last tick"
   is fragile; the design must be *stateless with respect to elapsed time*.

## 5.2 Column mapping

Form question wording and column order will change (OQ-4). Ingestion therefore maps by **header
name**, configured, not by position:

```yaml
ingestion:
  sheets:
    columns:
      timestamp:  ["Timestamp", "Submitted at"]
      email:      ["Email Address", "Email"]
      name:       ["Name", "Student Name", "Full Name"]
      rollNo:     ["Roll Number", "Roll No", "Roll"]
      phone:      ["Phone Number", "Contact Number", "Mobile"]
      room:       ["Room Number", "Room No", "Room"]
      description:["Describe the issue", "Complaint", "Issue"]
      category:   ["Type of issue"]        # optional resident-supplied hint
    required: [timestamp, description]
```

`name`, `rollNo` and `phone` are mapped because the assigned attender needs to identify and reach
the student (§11.4). They are optional: a Form that does not ask for them still ingests, and the
attender simply sees the room number alone.

Each logical field lists accepted header aliases. At the start of a run we resolve headers once; an
unresolvable **required** column fails the whole run loudly rather than ingesting garbage — a
silent partial ingest is far worse than a visible failure, because the manager would have no way to
know complaints were being dropped.

## 5.3 Delta detection

Identity is derived from content, not position:

```
response_key = SHA256( normalize(timestamp) || "\x1f" || normalize(email) )
content_hash = SHA256( join("\x1f", [normalize(cell) for cell in row]) )
```

`response_key` is stable across row shifts and across edits — a Google Form response keeps its
submission timestamp and submitter when edited. `content_hash` changes whenever any answer changes.
Splitting identity from change-detection is what makes all three failure modes above tractable.

### Reconciliation rules

| Observation | Meaning | Action |
|---|---|---|
| `response_key` unseen | New submission | Insert `source_row`, create complaint (T1) |
| Known key, same `content_hash` | Already ingested | Skip. Touch `last_seen_*`, nothing else |
| Known key, different `content_hash` | Response was edited | **Phase 5 feature.** For now: skip and log. See below |
| Duplicate `response_key` within one batch | Two submissions in the same second by the same person | Disambiguate by appending the in-batch ordinal before hashing |

**Deletions are not tracked.** Once a complaint is ingested it is ours; the sheet is an *inbox*, not
a continuing source of truth. An earlier draft detected removed rows (a `VANISHED` state plus a
nightly full-sheet reconcile pass) so the manager could be told a row had disappeared. That bought
one notification at the cost of a second scan mode, an extra state and a manager review path
(ADR-012). Work may already have been done and points credited against a complaint, so we would
never have deleted it anyway.

**Amendments are deferred to Phase 5.** They only occur if the Google Form has "edit response"
enabled, which is off by default. Until that is confirmed and the feature is built, an edited row is
skipped with a log line — the complaint keeps its original text, which is the safe behaviour. The
schema already reserves the `AMENDED` state so adding it later is additive.

### Scan strategy

With deletions ignored and amendments deferred, the sheet is append-only from our point of view, so
**one fetch mode suffices**:

```
from = max(1, lastRowSeen − OVERLAP)      OVERLAP = 50
fetch rows [from .. end]
```

The overlap absorbs row-index drift if someone does delete a row, and `response_key` makes
re-reading rows we already have a no-op regardless. A long gap — the instance slept for six hours,
or a manual "Sync Now" after a week — is handled by the same code path: `lastRowSeen` has not moved,
so the fetch simply covers more rows. **There is no separate catch-up mode to get wrong.**

This is also what makes the Apps Script wake-up trigger (§2.5) safe: it only signals that the sheet
changed, so a duplicate trigger, a trigger for a row we already hold, or a trigger that never
arrives all converge on the same delta computation.

### Pseudocode

```
function reconcile(rows, run):
    seen = {}
    for (i, row) in rows:
        key = responseKey(row)
        if key in seen: key = responseKey(row, disambiguator = ++seen[key])
        seen[key] = 0

        if sourceRowRepo.exists(sourceRef, key):
            run.rowsSkipped++                              # already ingested
            continue

        sourceRowRepo.save(sourceRow(key, contentHash(row), rowIndex = i, run))
        complaint = normalizeToComplaint(row)              # 5.4
        classify(complaint)                                # 5.5
        workflow.transition(complaint, INGEST)             # T1
        run.rowsCreated++
    run.lastRowSeen = max(rowIndex)
```

Fifteen lines, one branch. That is the entire delta-detection algorithm, and it is worth noting how
much of the original complexity came from *features* (amendment tracking, deletion detection) rather
than from the hard part — which is identity, and is handled by one `UNIQUE` constraint.

Each row is processed in its **own transaction**. One malformed response therefore cannot abort the
whole run; it is recorded on `ingestion_run.error_detail` and the run continues. Given that this
is an unattended background job over data we do not control, partial progress is strictly better
than all-or-nothing.

### Quota and failure budget

Sheets API allows 300 read requests/minute/project. At one request per 3-minute poll we use ~0.1%
of quota, leaving ample headroom for manual syncs. Failures back off exponentially (3 → 6 → 12 →
30 min, capped) and after three consecutive failures the dashboard shows a **degraded-ingestion
banner** telling the manager to use the CSV fallback. Silence is the failure mode to avoid: the
manager must find out from the UI, not by noticing complaints stopped arriving.

## 5.4 Normalization to a complaint

| Field | Derivation |
|---|---|
| `raw_text` | The description cell, verbatim, never modified |
| `reported_at` | Parsed form timestamp, interpreted in Asia/Kolkata, stored UTC |
| `reporter_room` | Regex `\b([A-Z]{0,2}[-\s]?\d{3,4})\b` over the room cell, then the description as fallback |
| `floor_id` | Derived from room number via a configurable rule (default: first digit of a 3-digit room, first two of a 4-digit). Unmapped → `NULL`, which routes to triage |
| `reporter_name` | Name cell, trimmed; optional — `NULL` when the Form does not ask (§11.4) |
| `reporter_roll_no` | Roll-number cell, upper-cased and trimmed; optional |
| `reporter_phone` | Phone cell, digits normalised; optional |
| `reporter_contact` | Email cell; purged after 90 days (NFR-9) |
| `public_ref` | `CAU-{year}-{zero-padded sequence}` |

## 5.5 Categorization

**Approach (ADR-004): a weighted lexicon with fuzzy matching, gated on confidence.** Apache OpenNLP
document categorization is designed in as an optional second stage (§5.7) but is *not* on the
critical path.

The reasoning is about data, not sophistication. A supervised classifier needs a few hundred
labelled examples per class; we do not yet know we will have them, complaint text is short
(often under 10 words), frequently misspelled, and often code-mixed Tamil/English transliteration.
A weighted lexicon degrades gracefully in exactly those conditions, needs no training data, is
editable by the manager without a redeploy, and — decisive for a viva — every decision it makes can
be explained by pointing at the terms that fired.

### Pipeline

```
raw text
  → lowercase, Unicode NFKC, strip punctuation, collapse whitespace
  → expand abbreviations ("ac"→"air conditioner", "wtr"→"water", "elec"→"electrical")
  → tokenize (whitespace + n-grams up to 3, so phrases can be matched)
  → match against category_term
  → score per domain
  → confidence gate
  → DOMAIN or UNCLASSIFIED
```

### Matching and scoring

For each domain *d*, over matched terms *t*:

```
S(d) = Σ  w(t) · m(t)
     t∈T(d)

where  m(t) = 1.00   exact token/phrase match
             0.70   fuzzy match  (Damerau-Levenshtein ≤ 2 on tokens of length ≥ 4,
                                  or Jaro-Winkler ≥ 0.90)
             0.00   otherwise
```

Multi-word phrases carry higher weights than single tokens because they disambiguate: `water` alone
is nearly useless (it appears in RO Plant, Civil and Electrical complaints), while `no water` and
`water leaking` are decisive. Phrase matches suppress their constituent single-token matches so a
phrase is not double-counted.

Let `S₁ ≥ S₂` be the top two domain scores. Confidence combines **absolute evidence** and
**separation**:

```
confidence = 0  if S₁ < S_min                          (nothing meaningful matched)
             0  if S₁ = 0
             (S₁ − S₂) / S₁    otherwise               (margin over the runner-up)

accept if confidence ≥ τ  AND  S₁ ≥ S_min
```

Defaults: `S_min = 1.0`, `τ = 0.40`. Both are configuration, tuned against the gold set (§12.3).

Two gates rather than one matters. A single absolute threshold accepts a complaint that scores
strongly for *two* domains equally — precisely the ambiguous cases where a human should decide. A
single margin threshold accepts a complaint where one weak term matched and nothing else did.
Requiring both routes exactly the uncertain cases to triage.

### Failing to triage, on purpose

Anything not accepted becomes `UNCLASSIFIED` and lands in the manager's triage queue (T3) — the
proposal's Risk 2 Plan B. The manager sets the domain in one click, and that correction is
recorded. This is a deliberate precision-over-recall stance: a wrongly-auto-assigned complaint
sends an attender to the wrong job and erodes trust in the whole system, whereas a triaged one
costs the manager three seconds.

### Priority extraction

Independent of domain, a separate keyword set raises base priority (§4.5). `shock`, `spark`,
`short circuit`, `sewage`, `no water`, `overflow` → `P1`. These are matched **exactly only** — no
fuzzy matching — because a false P1 from a near-miss spelling would distort the whole queue.

## 5.6 Meeting the 90% accuracy criterion

The acceptance criterion needs a testable definition, so we fix one now:

> Accuracy is measured over the **auto-accepted** subset (confidence ≥ τ) of a held-out labelled
> gold set, as the fraction whose predicted domain equals the human label.

Items routed to triage are excluded from the denominator but tracked separately as **coverage**
(the fraction auto-accepted). Reporting both is what makes the number honest — a classifier that
accepts nothing would otherwise score 100%. Targets: **accuracy ≥ 90%, coverage ≥ 70%**. Full
methodology, dataset construction and the confusion matrix live in §12.3.

## 5.7 OpenNLP as a deferred second stage

Once enough manager triage corrections have accumulated, they form a labelled corpus for free —
every triage decision is `(text, human label)`. At that point an OpenNLP `DocumentCategorizer`
can be trained and consulted **only for items the lexicon routes to triage**, with its own
confidence gate. This is upside without risk: the rule engine stays the primary path, so a poorly
trained model can only convert triage items into accepted ones, never corrupt a decision the rules
were already confident about. Tagged **Extended** in §13 — it ships only if the schedule allows.

## 5.8 CSV fallback

Risk 1's Plan B is a drag-and-drop `.csv` upload on the manager dashboard. It is **the same port**:

```java
public interface ComplaintSource {
    String sourceRef();
    SourceType type();
    List<SourceRow> fetch(FetchWindow window);
}
```

`GoogleSheetsSource` and `CsvUploadSource` both implement it, and reconciliation, normalization,
classification and dispatch are shared verbatim. The fallback therefore inherits the idempotency
guarantee for free: re-uploading the same CSV twice — the obvious thing a flustered user does
during an outage — produces zero duplicate complaints, because `response_key` collides.
