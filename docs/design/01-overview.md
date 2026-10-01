# 1. Overview & Scope

## 1.1 Problem

The Hostel Manager at Cauvery Hostel, IIT Madras processes daily maintenance complaints by hand.
Residents submit a Google Form; the manager copies each response into a spreadsheet, sorts it
into a domain (Electrical, Civil, RO Plant), checks which attenders are on duty and which floors
they cover, assigns the work verbally, chases inspection IDs, and at month end tallies points for
bonus calculation. This costs **30–45 minutes every day** and is error-prone at every step.

Four attenders serve the hostel. Points accrue at **+2** per resolved complaint, custom values for
special duties, and **−1** per logged infraction. All of it is currently reconciled by hand in a
spreadsheet that is easily corrupted when edited from mobile devices.

## 1.2 Goals

| G | Goal | Measured by |
|---|---|---|
| G1 | Eliminate manual copy-paste from the Form response sheet | Zero manual transcription; no duplicate complaints across polls |
| G2 | Every complaint carries its domain without the manager sorting it | The resident selects it on the Form (§5.5); anything unanswered reaches triage, never a wrong domain |
| G3 | Assign each complaint to the most appropriate attender automatically | Dispatch produces an assignment or an explicit triage reason, 100% of the time |
| G4 | Give attenders a usable mobile queue | Attender can log a CMS docket and resolve a ticket in ≤3 taps from the queue |
| G5 | Make point accounting correct and auditable | No double-credit under concurrent updates; every point movement traceable to an actor and a cause |
| G6 | Replace the end-of-month manual tally | One-click `.xlsx` export; leaderboard always live |
| G7 | Surface operational insight the manager cannot currently see | MTTR per category, bottleneck stage, anomaly alerts |
| G8 | Reach an attender when work is assigned, without them opening the app | Push notification delivered to the attender's phone for new and escalated assignments |

## 1.3 Non-goals

Explicitly **out of scope** for this project, to keep the solo build tractable:

- **Resident-facing accounts.** Residents keep using the existing Google Form. They never log in.
- **Replacing the IITM EU CMS app.** We record its docket number; we do not integrate with or
  supersede it. There is no CMS API available to us.
- **Native mobile applications.** The attender interface is a responsive web view.
- **Multi-hostel tenancy.** Single-hostel deployment. The schema does not model a hostel dimension.
- **Payroll or bonus disbursement.** We compute points; money is handled outside the system.
- **A native mobile app store presence.** Push reaches attenders through the installable web app
  (Web Push), not a Play Store binary.
- **Operating without internet.** The stakeholder confirmed that internet at the hostel office is
  reliable; the outage we design for is the **cloud platform** being unavailable, not the network.
  This assumption is load-bearing for §2.6 and ADR-001.

## 1.4 Actors

| Actor | Count | Device | Authenticated | Responsibilities |
|---|---|---|---|---|
| **Hostel Manager** | 1 | Desktop (office), occasionally mobile | Yes — Google sign-in, allowlisted | Oversight, triage of unclassified complaints, reassignment, penalties, special duties, exports, account provisioning |
| **Attender** | ~5 | Personal Android / iOS phone, hostel Wi-Fi / mobile data | Yes — Google sign-in, allowlisted | Receive push alerts, view assigned queue (with the reporting student's contact detail), record CMS docket, mark resolved, claim special duties, view own standing |
| **Resident** | ~hundreds | Any | **No** | Submits the Google Form. Never touches this system directly. |
| **Scheduler** | — | — | System | Polls the sheet, ages priorities, runs telemetry scans |

## 1.5 Glossary

| Term | Meaning |
|---|---|
| **Complaint** | A single maintenance request originating from one Google Form response |
| **Task** | The unit of work dispatched to attender(s). One complaint yields one task; special duties are tasks with no complaint |
| **Domain** | Category of work: `ELECTRICAL`, `CIVIL`, `RO_PLANT`, `NETWORK`, or `UNCLASSIFIED` |
| **CMS docket** | The tracking number issued by the IITM EU Campus Management System when an attender files the issue officially |
| **Triage queue** | Holding area for complaints the resident did not categorize, or that dispatch could not assign |
| **Ledger entry** | An immutable row recording a point movement, with actor, cause and effective date |
| **Special duty** | A manager-posted ad-hoc task with a custom point value, claimable by any attender |
| **Penalty** | A manager-logged infraction carrying a negative point value |
| **SLA** | Target resolution time for a complaint, by domain and priority |
| **OCC** | Optimistic concurrency control — version-checked updates that fail rather than silently overwrite |

## 1.6 Non-functional requirements

| ID | Requirement | Target | Rationale |
|---|---|---|---|
| NFR-1 | Scale envelope | ≤50 complaints/day, ≤10 concurrent users, ≤50k rows/year | Single hostel, ~5 attenders (4–8 users in total). Sizing everything else follows from this. |
| NFR-2 | Ingestion latency | New Form response visible in the manager dashboard within **5 minutes** | Poll interval, not a real-time requirement |
| NFR-3 | Interactive response time | p95 < 500 ms for queue and dashboard reads | Attenders on mobile data in hostel corridors |
| NFR-4 | Mobile payload | Attender queue initial load < 150 KB | Low-end phones, patchy connectivity |
| NFR-5 | Correctness under concurrency | Zero double-credits, zero lost updates | Core integrity guarantee; see §7 |
| NFR-6 | Availability | Best-effort. A **cloud app-tier outage** is survivable: the manager runs the same jar on their desktop against the same managed database. Internet is assumed available | Free-tier PaaS sleeps and occasionally fails; outage plan B from proposal §10 |
| NFR-10 | Push delivery | P1 assignment push dispatched within **30 s** of assignment; delivery itself is best-effort | The push service is outside our control, so the in-app queue stays the source of truth |
| NFR-7 | Timezone | All business dates in **Asia/Kolkata**; all timestamps stored UTC | Month boundaries for the bonus cycle must be unambiguous |
| NFR-8 | Auditability | Every state change and point movement is reconstructible | Bonus disputes must be resolvable from the log |
| NFR-9 | Data retention | Complaint text retained 1 year; resident email retained 90 days; student name, roll number and phone visible to the assigned attender for 7 days after resolution, and retained with the complaint record for the manager | Minimize PII exposure; access is scoped by role and complaint lifecycle (§11.4) |

## 1.7 Open questions

Pending confirmation from the stakeholder. Each has a working default so no design work is blocked.

| Q | Question | Working default |
|---|---|---|
| OQ-1 | Exact format of the IITM EU CMS docket number | Configurable regex, permissive default `^[A-Za-z0-9/-]{4,32}$`. Validation is a config property, not a code constant. |
| OQ-2 | Per-domain SLA targets | Electrical 8h, RO Plant 12h, Civil 24h, Network 24h (working hours). See §8.2. |
| OQ-3 | Are attender floor allocations fixed or rotating? | Modelled as time-bounded assignments so both work. See §3.4. |
| OQ-4 | Exact Google Form column headers and question set | Ingestion maps columns by header name via config, tolerant of reordering. See §5.2. |
| OQ-5 | Do attenders have domain specialisations, or is everyone a generalist? | Skill weight defaults to neutral; scoring supports specialisation if it exists. See §6.2. |
| OQ-6 | Working-hours window for SLA aging | 08:00–20:00 IST, seven days | 

## 1.8 Constraints

- **Solo developer**, roughly nine weeks, alongside coursework. Work is sliced into five delivery
  phases (§13), the first of which is a walking skeleton that runs end to end.
- **Every line must be explainable in a viva.** This is a real design constraint, not a formality:
  where a simpler mechanism gives the same user-visible behaviour, we take the simpler one and
  record why (ADR-012). Framework features are preferred over hand-rolled equivalents.
- **Free-tier hosting.** Instances sleep when idle; this shapes the scheduler design (§2.5).
- **No CMS API.** Docket numbers are entered by hand — the system cannot verify them upstream.
- **Low-tech end users.** Attenders may have limited literacy in English and low-end phones. UI
  must lean on iconography, large tap targets and minimal text entry.
