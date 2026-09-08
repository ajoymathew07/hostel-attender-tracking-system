# 9. API & Data Contracts

## 9.1 Shape of the web layer

A hybrid, chosen to match the two very different clients:

- **Thymeleaf server-rendered pages** deliver the page shell, navigation and first paint. For the
  attender on a low-end phone this matters — the queue is usable before any JavaScript runs, which
  is the difference between a working tool and a spinner in a corridor with two bars of signal.
- **JSON endpoints under `/api/**`** serve dynamic data: chart series, live queue refreshes, and
  every state-changing action.
- **SSE at `/api/manager/stream`** pushes live updates to the manager dashboard.
- **Web Push** delivers assignment alerts to attender phones even when the app is closed (§9.6).

All JSON endpoints are versionless (`/api/...`) — this is a single-deployment internal tool with no
third-party consumers, so a version prefix would be ceremony without a payer.

## 9.2 Conventions

| Aspect | Rule |
|---|---|
| Timestamps | ISO-8601 UTC with `Z` (`2026-09-08T14:32:00Z`). Clients render in Asia/Kolkata |
| Dates | `YYYY-MM-DD`, always the Asia/Kolkata business date |
| Money/points | Integers. No floats anywhere in the ledger |
| Nulls | Omitted from responses rather than sent as `null` |
| Casing | `camelCase` |
| Paging | `?page=0&size=25`, envelope `{content, page, size, totalElements, totalPages}` |
| Auth | Session cookie (`HttpOnly; Secure; SameSite=Lax`) + device token for attenders |
| CSRF | Spring Security tokens on all non-GET form posts |
| Idempotency | `Idempotency-Key` header **required** on attender state-changing POSTs (§7.4) |

## 9.3 Attender API (mobile)

| Method | Path | Purpose | Transition |
|---|---|---|---|
| `GET` | `/api/attender/me` | Profile, month points, rank | — |
| `GET` | `/api/attender/queue` | Assigned active tasks, priority-ordered | — |
| `POST` | `/api/attender/tasks/{id}/start` | Begin inspection | T7 |
| `POST` | `/api/attender/tasks/{id}/confirm-defect` | Defect is real | T9 |
| `POST` | `/api/attender/tasks/{id}/no-defect` | Nothing wrong found | T10 |
| `POST` | `/api/attender/tasks/{id}/docket` | Record CMS docket number | T11 |
| `POST` | `/api/attender/tasks/{id}/resolve` | Mark fixed → **+2 points** | T14 |
| `POST` | `/api/attender/tasks/{id}/hold` | Block with reason | T12 |
| `POST` | `/api/attender/tasks/{id}/release` | Hand back to triage | T8 |
| `GET` | `/api/attender/duties` | Open special duties | — |
| `POST` | `/api/attender/duties/{id}/claim` | Claim a slot | §7.6 |
| `POST` | `/api/attender/duties/{id}/complete` | Mark my part done | §7.5 |
| `GET` | `/api/attender/standing` | Point history, this period | — |
| `GET` | `/api/attender/push/key` | VAPID public key for subscription | — |
| `POST` | `/api/attender/push/subscribe` | Register this device for push | — |
| `POST` | `/api/attender/push/unsubscribe` | Stop push on this device | — |
| `POST` | `/api/attender/push/test` | Send a test notification to self | — |

### `GET /api/attender/queue`

```json
{
  "attenderId": 7,
  "generatedAt": "2026-09-08T09:14:02Z",
  "tasks": [
    {
      "taskId": 412,
      "complaintRef": "CAU-2026-0417",
      "priority": "P1",
      "agedFrom": "P2",
      "domain": "ELECTRICAL",
      "title": "Fan not working, sparking sound",
      "room": "214",
      "floor": "Wing A, Floor 2",
      "status": "ASSIGNED",
      "reportedAt": "2026-09-08T08:41:00Z",
      "slaDueAt": "2026-09-08T12:41:00Z",
      "slaState": "AT_RISK",
      "docket": null,
      "nextActions": ["start", "release"],
      "points": 2
    }
  ],
  "counts": {"assigned": 3, "inProgress": 1, "onHold": 0}
}
```

`nextActions` is computed **server-side from the state machine**, not inferred by the client. The
mobile UI renders exactly the buttons this array names. This keeps §4's transition table as the
single source of truth for what is legal — the client cannot offer an action the server would
reject, so an attender never taps a button that fails.

### `POST /api/attender/tasks/{id}/resolve`

```http
POST /api/attender/tasks/412/resolve
Idempotency-Key: 6f1c2b90-8e44-4c1a-9f2b-3d5e7a1c0b22
Content-Type: application/json

{"note": "Replaced fan capacitor and switch"}
```

```json
{
  "taskId": 412,
  "status": "RESOLVED",
  "resolvedAt": "2026-09-08T11:02:41Z",
  "pointsAwarded": 2,
  "periodTotal": 34,
  "alreadyApplied": false
}
```

`alreadyApplied: true` on a replay — the request succeeded, no second credit was written (§7.3).
The client shows the same success screen either way; the field exists for diagnostics, not for the
user.

## 9.4 Manager API

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/manager/summary` | Dashboard header counts |
| `GET` | `/api/manager/complaints` | Filter/paginate all complaints |
| `GET` | `/api/manager/triage` | Unclassified + unassignable queue |
| `POST` | `/api/manager/complaints/{id}/classify` | Set domain manually (T4) |
| `POST` | `/api/manager/complaints/{id}/assign` | Assign from triage (T4) |
| `POST` | `/api/manager/complaints/{id}/reassign` | Move to another attender (T6) |
| `POST` | `/api/manager/complaints/{id}/reject` | Reject with reason (T5) |
| `POST` | `/api/manager/complaints/{id}/reopen` | Reopen, reverses credit (T17) |
| `POST` | `/api/manager/complaints/{id}/act-as` | **Assisted admin log** (Risk 3 Plan B) |
| `POST` | `/api/manager/penalties` | Log a −1 infraction |
| `POST` | `/api/manager/duties` | Post a special duty |
| `POST` | `/api/manager/adjustments` | Manual point adjustment, note required |
| `GET` | `/api/manager/leaderboard?period=2026-09` | Standings |
| `GET` | `/api/manager/analytics/mttr` | MTTR + median + p90 by domain |
| `GET` | `/api/manager/analytics/arrivals` | Arrival velocity series |
| `GET` | `/api/manager/analytics/funnel` | Stage-share breakdown |
| `GET` | `/api/manager/analytics/workload` | Live per-attender load |
| `GET` | `/api/manager/alerts` | Anomaly feed |
| `POST` | `/api/manager/alerts/{id}/ack` | Acknowledge an alert |
| `POST` | `/api/manager/ingestion/sync` | **Sync Now** (§2.5) |
| `POST` | `/api/manager/ingestion/csv` | CSV fallback upload (Risk 1 Plan B) |
| `GET` | `/api/manager/reports/daily.xlsx` | Daily Excel export |
| `GET` | `/api/manager/reports/weekly.xlsx` | Weekly Excel export |
| `GET/POST` | `/api/manager/users` | List / provision accounts |
| `POST` | `/api/manager/users/{id}/reset-pin` | Issue a new PIN |
| `POST` | `/api/manager/users/{id}/revoke-devices` | Lost-phone response |
| `POST` | `/api/manager/availability` | Mark an attender on leave |

`act-as` is worth noting: it lets the manager perform an attender's transition during roll-call when
that attender cannot use their phone. It executes the identical transition through the identical
state machine, but writes `actor_id = manager, on_behalf_of = attender` to the audit log. The
points still go to the attender — the manager is the typist, not the earner — and the audit record
makes that distinction permanent.

### `POST /api/manager/penalties`

```json
{"attenderId": 9, "points": -1, "reasonCode": "LATE_ARRIVAL",
 "effectiveDate": "2026-09-08", "note": "Reported 45 min late, no prior intimation"}
```

`note` is mandatory for every negative adjustment. A point deduction affects someone's bonus; an
unexplained one is indefensible in a dispute, which is precisely when the record gets read.

## 9.5 SSE contract

```
GET /api/manager/stream
Accept: text/event-stream
Last-Event-ID: 88213
```

```
id: 88214
event: ComplaintCreated
data: {"complaintId":903,"ref":"CAU-2026-0418","domain":"CIVIL","priority":"P2","room":"310"}

id: 88215
event: TaskResolved
data: {"taskId":412,"attenderId":7,"attenderName":"Ravi","points":2,"periodTotal":34}
```

| Event | Fired when |
|---|---|
| `ComplaintCreated` | Ingestion creates a complaint (T1) |
| `ComplaintAmended` | Source row edited (§5.3) |
| `TriageRequired` | Complaint needs manager attention (T3) |
| `TaskAssigned` | Dispatch assigns (T2) |
| `TaskStarted` / `TaskResolved` | Attender progress (T7 / T14) |
| `DocketRecorded` | CMS number entered (T11) |
| `DutyClaimed` / `DutyCompleted` | Special duty progress |
| `PenaltyLogged` | Manager logs an infraction |
| `AlertRaised` | Anomaly detector fires |
| `IngestionDegraded` | Poll failing repeatedly (§5.3) |
| `PushDeliveryFailed` | An attender's device stopped accepting push (§9.6) |

Event `id` is `outbox_event.id`. On reconnect the browser automatically sends `Last-Event-ID` and
the server replays everything after it from the outbox. This is what makes the free-tier proxy
dropping an idle connection (NFR-6) a non-event rather than a silent gap in the manager's view.
Heartbeat comment every 20 s keeps the connection from being reaped in the first place. Replay is
capped at 500 events; beyond that the client is told to refresh outright.

## 9.6 Push notification contract

Attenders receive alerts on their phone even when the app is closed, using the **Web Push protocol
with VAPID** (ADR-011). The same service worker that backs the offline queue (§10.2) receives them,
so no second client-side runtime is introduced.

### Subscription flow

```
1. Attender logs in on their phone, taps "Enable alerts"
2. Browser asks for notification permission        (one-time, OS-level)
3. Client GETs /api/attender/push/key              → VAPID public key
4. Client calls pushManager.subscribe(...)         → endpoint + p256dh + auth
5. Client POSTs those to /api/attender/push/subscribe
6. Server stores a push_subscription row keyed on the endpoint
```

```http
POST /api/attender/push/subscribe
Content-Type: application/json

{
  "endpoint": "https://fcm.googleapis.com/fcm/send/dQw4w9...",
  "keys": {"p256dh": "BNcRdreALRFXTkOOUHK1EtK2wtaz5Ry4YfYCA_0QTpQtUbVlUls0VJXg7A8u-Ts1XbjhazAkj7I99e8QcYP7DkM=",
           "auth":   "tBHItJI5svbpez7KI4CCXg=="},
  "deviceLabel": "Ravi's phone"
}
```

`endpoint` is unique (§3.7), so re-subscribing the same browser updates rather than duplicates the
row — a browser reissues its endpoint periodically, and without that constraint an attender would
accumulate stale subscriptions and receive the same alert several times.

### Notification types

Only events an attender must **act on** are pushed. Everything else stays in the app.

| Trigger | Title | Body | Priority |
|---|---|---|---|
| `TaskAssigned` (P1) | 🔴 Urgent: Room 214 | Electrical — sparking sound. Due in 4h | high |
| `TaskAssigned` (P2/P3) | New task: Room 118 | Civil — door latch broken | normal |
| Priority escalated to P1 | ⏰ Now urgent: Room 305 | RO Plant — overdue | high |
| `SpecialDutyPosted` | New duty available (+5) | Hostel night preparation | normal |
| `TaskReassigned` away | Task reassigned | Room 118 moved to Kumar | normal |
| SLA breach imminent (30 min) | ⏱ Due soon: Room 214 | Not yet resolved | high |

**Deliberately not pushed:** point credits, leaderboard changes, manager dashboard activity, anomaly
alerts. Notifying someone that they earned the points they just earned is noise, and an attender who
learns to swipe our notifications away will miss the P1 that matters.

### Payload

Encrypted per the Web Push spec (`aes128gcm`); the push service never sees the plaintext.

```json
{
  "title": "🔴 Urgent: Room 214",
  "body": "Electrical — sparking sound. Due in 4h",
  "tag": "task-412",
  "data": {"taskId": 412, "url": "/m/tasks/412"},
  "actions": [{"action": "open", "title": "Open"}],
  "renotify": false
}
```

`tag` collapses repeat notifications for the same task, so an escalation replaces the original
alert rather than stacking on it.

### Delivery semantics

Push is **best-effort and advisory** (NFR-10). The in-app queue remains the source of truth — an
attender who never enables notifications loses nothing but convenience, and no state transition
depends on a push being delivered. Push sends therefore happen **after commit**, from the outbox
poller, never inside the transaction that caused them.

| Response from push service | Meaning | Action |
|---|---|---|
| `201` / `200` | Accepted for delivery | Record `SENT` |
| `404` / `410` | Subscription expired | Revoke the row permanently |
| `429` | Rate limited | Back off, retry per `Retry-After` |
| `413` | Payload too large | Truncate body, retry once |
| `5xx` | Push service down | Retry ×3 with backoff, then `FAILED` |

Three consecutive failures revoke the subscription and surface a "notifications not reaching this
device" note on the manager's attender-admin screen — a phone that silently stopped receiving alerts
is exactly the failure the manager needs told about, not one to discover during a P1.

## 9.7 Error model

RFC 7807 problem details:

```json
{
  "type": "https://hostel.iitm/errors/illegal-transition",
  "title": "Action not allowed in current state",
  "status": 409,
  "detail": "Task 412 is RESOLVED; 'start' is only valid from ASSIGNED.",
  "instance": "/api/attender/tasks/412/start",
  "code": "ILLEGAL_TRANSITION",
  "currentState": "RESOLVED"
}
```

| `code` | HTTP | Meaning |
|---|---|---|
| `ILLEGAL_TRANSITION` | 409 | Event not legal from the current state (§4.2) |
| `GUARD_FAILED` | 422 | Legal transition, unmet precondition (e.g. resolve with no docket) |
| `OPTIMISTIC_LOCK` | 409 | Concurrent modification; client should refresh |
| `ALREADY_CLAIMED` | 409 | Duty taken (§7.6) |
| `NO_SLOTS_LEFT` | 409 | Shared duty full |
| `NOT_ASSIGNED` | 403 | Caller does not hold an active assignment |
| `INVALID_DOCKET` | 422 | Docket fails the configured pattern (OQ-1) |
| `INGESTION_COLUMN_MISSING` | 422 | Required sheet column unresolvable (§5.2) |
| `ACCOUNT_LOCKED` | 423 | Too many failed PIN attempts (§11.2) |
| `VALIDATION_FAILED` | 400 | Bean-validation failure; `errors[]` lists fields |

`detail` is written to be shown directly to the user. On the attender's phone there is no developer
to interpret a stack trace, and "This task was already resolved" is the entire message that person
needs.
