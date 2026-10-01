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
| `floor_id` | **The first digit of the room number**, for 3-digit (100–399) and 4-digit (1000–3099) rooms alike — 169 and 1099 are both floor 1. Unmapped → `NULL`, which routes to triage |
| `reporter_name` | Name cell, trimmed; optional — `NULL` when the Form does not ask (§11.4) |
| `reporter_roll_no` | Roll-number cell, upper-cased and trimmed; optional |
| `reporter_phone` | Phone cell, digits normalised; optional |
| `reporter_contact` | Email cell; purged after 90 days (NFR-9) |
| `public_ref` | `CAU-{year}-{zero-padded sequence}` |

An earlier draft of this table read "first digit of a 3-digit room, first two of a 4-digit", which
maps room 1000 to floor 10 and would send every four-digit room to triage. Cauvery room numbers
carry no wing letter and the first digit is the floor in both shapes; the rule above is the one the
building uses. The wing is configuration (`dispatch.wing`), because the number does not carry it.


## 5.5 The domain comes from the form

**Approach: the Form asks, and the resident answers.** A required question — Civil, Electrical,
LAN, RO, Others — and the answer is the complaint's domain. `ClassificationMethod.REPORTED`
records that it was the resident's call, so a manager's later correction is distinguishable from
it.

This replaces ADR-004's weighted lexicon with fuzzy matching and a confidence gate, which was
designed, built and then removed. The reasoning that produced it was sound and the conclusion was
still wrong, which is worth keeping:

- The lexicon existed to **infer** something the resident already knows. Its accuracy ceiling was
  the quality of a term list over short, misspelled, often code-mixed text; a drop-down's ceiling
  is whether the resident reads five options. One of those is much higher.
- It was **not cheap**. A `category_term` table and its seed, an n-gram tokeniser, Jaro–Winkler
  matching, two thresholds nobody could set from first principles, and a 200-item gold set whose
  only purpose was to find out whether the whole apparatus worked. All of it to avoid asking.
- The thing the lexicon protected — **nothing is auto-assigned on a guess** — does not depend on
  the lexicon at all. It is the triage queue, and the triage queue stays.

**Mapping.** The Form's words are not the database's. `LAN` is `NETWORK`, `RO` is `RO_PLANT`, and
matching ignores case and surrounding space, because those are the resident's typing rather than
their meaning. The Form's wording is still OQ-4 — not ours to control — so the mapping is lenient
and anything unrecognised is `UNCLASSIFIED` rather than an error: a Form option somebody adds
without telling us fills the triage queue, which is visible, instead of stopping the run or
guessing.

### Failing to triage, on purpose

`Others`, an unanswered question, and a value we do not recognise all become `UNCLASSIFIED` and
land in the manager's triage queue (T3) — the proposal's Risk 2 Plan B, unchanged. The manager
sets the domain in one click, and that correction is recorded. The precision-over-recall stance
is the same one the gate used to serve: a wrongly-assigned complaint sends an attender to the
wrong job and erodes trust in the whole system, whereas a triaged one costs the manager three
seconds.

### Priority extraction

Priority is **not** on the form, and is still inferred from the text. A separate keyword set
raises base priority (§4.5): `shock`, `spark`, `short circuit`, `sewage`, `no water`, `overflow`
→ `P1`. These are matched **exactly only** — no fuzzy matching — because a false P1 from a
near-miss spelling would distort the whole queue.

Asking the resident instead would be the obvious symmetry and is the wrong call: a resident
reporting their own problem has every incentive to call it urgent, and nobody is wrong to. The
keyword set is about hazard, not about urgency, and it is ours to judge.

## 5.6 The 90% accuracy criterion

The criterion was written for a classifier and is met by construction now: the domain is what the
resident selected, so there is no prediction to be wrong. What remains worth measuring is
**coverage** — the fraction of complaints that arrive with a usable domain rather than `Others` or
a blank — because that is what decides how much of the manager's 30–45 minutes the system actually
removes.

Coverage is observable without a gold set: it is the share of ingested complaints that reach
`ASSIGNED` without a manager touching them, which the dashboard's triage counter shows directly.
A low figure means the Form's options do not match the complaints people actually have, and the
fix is an option, not a threshold.

The gold set of §12.3 is withdrawn with the classifier it existed to measure.

## 5.7 Withdrawn: OpenNLP as a deferred second stage

This section proposed training an OpenNLP `DocumentCategorizer` on accumulated triage corrections,
to be consulted only for items the lexicon could not place. With no lexicon and no inference, it
has nothing to improve on. Withdrawn.

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
