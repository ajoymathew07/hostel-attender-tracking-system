# 10. Frontend Design

## 10.1 Two clients, two very different users

| | Manager (desktop) | Attender (mobile) |
|---|---|---|
| Device | Office desktop, 1440px+ | Low-end Android, 360px |
| Session | Long, seated, focused | 30 seconds, standing, one hand |
| Network | Office LAN | Patchy hostel Wi-Fi / mobile data |
| Literacy | Comfortable with spreadsheets | May have limited English literacy |
| Needs | Density, overview, control | One task, one obvious action |

These are not the same application with a different breakpoint. They are two layouts sharing a
backend, and the attender view is designed **mobile-only** — it is never used on a desktop, so it
carries no desktop compromise.

## 10.2 Attender mobile

Design rules, all driven by the table above:

1. **Never more than one primary action visible per task.** The state machine already knows what
   comes next (`nextActions`, §9.3); the UI renders that one button large.
2. **Tap targets ≥ 48×48 px**, single-column, no horizontal scrolling.
3. **Icon + colour carry meaning, text confirms it.** Domain and priority are recognisable without
   reading.
4. **Minimal typing.** The only free-text entries in the whole flow are the CMS docket number and an
   optional resolution note.
5. **Optimistic UI with an offline queue.** Actions apply locally, queue when offline, replay with
   their idempotency key (§7.4).

```
┌──────────────────────────────┐
│ ☰  My Queue          Ravi ▾  │
│ ⭐ 34 pts · #2 this month    │
├──────────────────────────────┤
│ [ Queue 4 ]  [ Duties 2 ]    │
├──────────────────────────────┤
│ ┏━━━━━━━━━━━━━━━━━━━━━━━━━━┓ │
│ ┃ 🔴 P1  ⚡ ELECTRICAL      ┃ │
│ ┃ Room 214 · Wing A, Fl 2  ┃ │
│ ┃ Fan not working,         ┃ │
│ ┃ sparking sound           ┃ │
│ ┃ ⏱ Due in 1h 22m          ┃ │
│ ┃ ┌──────────────────────┐ ┃ │
│ ┃ │   ▶  START WORK      │ ┃ │
│ ┃ └──────────────────────┘ ┃ │
│ ┃            Release ⤺     ┃ │
│ ┗━━━━━━━━━━━━━━━━━━━━━━━━━━┛ │
│ ┌──────────────────────────┐ │
│ │ 🟡 P2  🔧 CIVIL          │ │
│ │ Room 118 · Wing A, Fl 1  │ │
│ │ Door latch broken        │ │
│ │ ⏱ Due in 19h             │ │
│ │ [    ▶  START WORK     ] │ │
│ └──────────────────────────┘ │
├──────────────────────────────┤
│  📋 Queue   ⭐ Duties  👤 Me │
└──────────────────────────────┘
```

### The resolution flow

Four screens, each one decision:

```
START WORK              DEFECT?                DOCKET                  DONE
┌────────────┐   →   ┌────────────┐   →   ┌────────────┐   →   ┌────────────┐
│ Room 214   │       │ Is there   │       │ Enter CMS  │       │ Fixed?     │
│ Fan spark  │       │ really a   │       │ docket no. │       │            │
│            │       │ fault?     │       │ ┌────────┐ │       │ Note       │
│ [▶ START]  │       │ [✓ YES]    │       │ │EU/2609…│ │       │ (optional) │
│            │       │ [✗ NO]     │       │ └────────┘ │       │ [✓ RESOLVE]│
└────────────┘       └────────────┘       │ [SAVE]     │       │  → +2 pts  │
      T7                 T9/T10           └────────────┘       └────────────┘
                                               T11                   T14
```

The docket screen cannot be skipped — it is the guard on T14 (§4.2), and putting it inline in the
flow rather than as a separate chore is what makes institutional traceability happen without
nagging.

### Offline behaviour

A service worker caches the shell and the current queue. Actions taken offline show a **"⏳ will
sync"** badge, are stored in IndexedDB with their idempotency key, and replay on reconnect. Because
replay is idempotent (§7.3), the failure mode "attender resolved three tasks in the basement with
no signal" resolves itself correctly and silently.

## 10.3 Manager desktop

```
┌──────────────────────────────────────────────────────────────────────────────┐
│ Cauvery Hostel · Duty Tracker          🔄 Sync now   📥 CSV   📊 Export ▾   ⚙ │
├──────────────────────────────────────────────────────────────────────────────┤
│ ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌──────────────┐ │
│ │ NEW  12 │ │ TRIAGE 3│ │ ACTIVE 8│ │RESOLVED │ │SLA RISK │ │ ⚠ ALERTS   2 │ │
│ │  today  │ │  ← act  │ │         │ │ 9 today │ │    2    │ │              │ │
│ └─────────┘ └─────────┘ └─────────┘ └─────────┘ └─────────┘ └──────────────┘ │
├────────────────────────────────────────────┬─────────────────────────────────┤
│ TRIAGE QUEUE                    (3)        │ LIVE ACTIVITY            ● live │
│ ┌────────────────────────────────────────┐ │ 11:02 Ravi resolved CAU-0417 +2 │
│ │ CAU-2026-0421 · Room 3?? · unmapped    │ │ 10:58 Kumar filed EU/26/0912    │
│ │ "water coming from ceiling fan"        │ │ 10:41 New CAU-0421 → triage     │
│ │ Suggested: CIVIL 0.31 / ELEC 0.28  ⚠   │ │ 10:22 Suresh started CAU-0419   │
│ │ [Electrical][Civil][RO][Net]  [Reject] │ │                                 │
│ │ Assign → ( Ravi ▾ )          [ASSIGN]  │ ├─────────────────────────────────┤
│ └────────────────────────────────────────┘ │ LEADERBOARD · September         │
│                                            │ 1. Kumar    41  ▓▓▓▓▓▓▓▓▓       │
│ ALL COMPLAINTS       [status▾][domain▾][🔍]│ 2. Ravi     34  ▓▓▓▓▓▓▓         │
│ ┌────────────────────────────────────────┐ │ 3. Suresh   28  ▓▓▓▓▓           │
│ │Ref     Room Dom  Pri Attender  Status  │ │ 4. Anand    22  ▓▓▓▓            │
│ │0417 ▸  214  ⚡   P1  Ravi      RESOLVED│ │        [+ Penalty] [+ Duty]     │
│ │0419 ▸  118  🔧   P2  Suresh    INSPECT │ ├─────────────────────────────────┤
│ │0420 ▸  305  💧   P2  Kumar     ON HOLD │ │ ⚠ ANOMALIES                     │
│ │0416 ▸  210  ⚡   P3  Anand     IN PROG │ │ 🔴 Room 214: 3 electrical in    │
│ └────────────────────────────────────────┘ │    14 days — chronic defect     │
│                                            │ 🟡 Wing B arrivals 3× baseline  │
├────────────────────────────────────────────┴─────────────────────────────────┤
│ ANALYTICS   [Arrivals] [MTTR] [Funnel] [Workload] [SLA]                       │
│  ╭────────────────────────╮ ╭────────────────────────╮ ╭───────────────────╮ │
│  │ Arrivals · 30d         │ │ MTTR by domain  (n=147)│ │ Workload now      │ │
│  │      ╱╲    ╱╲          │ │ ⚡ ▓▓▓▓▓ 6.2h md       │ │ Ravi   ▓▓▓▓▓ 5    │ │
│  │  ╱╲╱  ╲╱╲╱  ╲          │ │ 🔧 ▓▓▓▓▓▓▓▓ 19h md     │ │ Kumar  ▓▓▓ 3      │ │
│  ╰────────────────────────╯ ╰────────────────────────╯ ╰───────────────────╯ │
└──────────────────────────────────────────────────────────────────────────────┘
```

### Design notes

**Triage is placed above the complaint list, not in a tab.** It is the only part of the dashboard
that *requires* the manager's action, and the entire value proposition is reducing their daily
30–45 minutes. Anything requiring action goes where the eye lands first.

**The triage card shows the classifier's runner-up scores** (`CIVIL 0.31 / ELEC 0.28`). This turns
an opaque failure into useful information: the manager sees *why* the system was unsure, which
makes the one-click correction feel like collaboration rather than cleanup after a dumb machine.

**Live activity and the leaderboard are SSE-driven.** No polling, no refresh button — the proposal's
"real-time duty updates" requirement.

**Every derived statistic shows its `n`** (§8.4).

## 10.4 Screens

| Screen | Route | Audience |
|---|---|---|
| Login | `/login` | Both — role-appropriate form (password vs PIN keypad) |
| Manager dashboard | `/` | Manager |
| Complaint detail | `/complaints/{ref}` | Manager — full audit timeline, score breakdown, docket history |
| Attender queue | `/m` | Attender |
| Task detail / flow | `/m/tasks/{id}` | Attender |
| Special duties | `/m/duties` | Attender |
| My standing | `/m/me` | Attender — point history, month rank |
| Attender admin | `/admin/attenders` | Manager — provisioning, PIN reset, leave, floor coverage |
| Reports | `/reports` | Manager — date range, export |
| Settings | `/settings` | Manager — lexicon, weights, thresholds, SLA table |

The **complaint detail** screen is the explainability surface: the full audit timeline, the dispatch
score snapshot (§6.2) rendered as a readable breakdown, the docket history including superseded
entries, and every ledger movement attached to the task. If the manager ever asks "why did this
happen", one screen answers it.

## 10.5 Styling & accessibility

Tailwind CSS (utility classes, no separate design system to maintain solo). Chart.js for
visualizations.

- WCAG AA contrast throughout; **colour is never the only signal** — priority carries an icon and a
  text label as well as a hue, which also covers colour-blind users
- Semantic HTML, labelled form controls, visible focus rings
- Mobile: no fixed-position overlays that a soft keyboard can trap
- Charts have accessible data-table fallbacks

## 10.6 Performance

| Target | Approach |
|---|---|
| Attender first load < 150 KB (NFR-4) | Server-rendered shell, Tailwind purged, no framework runtime, Chart.js loaded **only** on manager routes |
| p95 < 500 ms (NFR-3) | Indexed queries (§3), no N+1 (fetch joins on queue and dashboard), pagination everywhere |
| Chart payloads | Pre-aggregated server-side; the browser never receives raw rows |
