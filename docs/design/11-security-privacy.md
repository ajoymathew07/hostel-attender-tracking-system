# 11. Security & Privacy

## 11.1 Threat model

This is an internal tool for one hostel with five accounts, deployed on public cloud hosting.
Naming what we are and are not defending against keeps the controls proportionate:

| Threat | In scope | Response |
|---|---|---|
| Opportunistic internet scanning of a public URL | ✅ | Auth on everything, no anonymous endpoints |
| A student discovering the URL and trying to log in | ✅ | Allowlist — a valid Google sign-in still fails without a provisioned row |
| An attender viewing or altering another attender's points | ✅ | Role + ownership checks on every action |
| An attender gaming the point system | ✅ | Docket gate on credit, append-only ledger, audit |
| A lost or shared attender phone | ✅ | `active = false` blocks the next sign-in; push subscriptions revoked at once; session expires within 12 h |
| Resident PII exposure | ✅ | Minimisation, retention limits (§11.4) |
| Targeted attack by a skilled adversary | ❌ | Out of scope; no assets justify it |
| DDoS | ❌ | Free-tier platform's problem |

The most *likely* real incident here is mundane: a shared or lost phone. The controls above are
weighted accordingly — and note that with no credential stored anywhere in this system, a lost phone
exposes a *session*, not a secret.

## 11.2 Authentication

Per ADR-002: **one mechanism for everyone — Google sign-in, admitted by an allowlist.** No
passwords, no PINs, no credential of any kind stored by this system.

| | Every user |
|---|---|
| Mechanism | Google OIDC (`openid, email, profile`) |
| Credential stored | **None** |
| Admission decided by | Verified email present and `active` in `app_user` |
| Role | `app_user.role`, never anything Google asserts |
| Session | 12 h idle timeout |
| Re-authentication | One tap — account already signed in on the device |

### The allowlist is the access-control boundary

Google establishes *who someone is*; it has no opinion on whether they may use this system. That
decision is ours and is made in one place — a custom `OidcUserService` that resolves the verified
email against `app_user` and throws if there is no matching active row. A sign-in from any other
Google account, institute or personal, is rejected without creating anything.

Emails are normalised (trimmed, lower-cased) before comparison and matched only against the
`email_verified` claim, never a self-asserted address.

### Why one mechanism rather than two

An earlier draft kept a local password for the manager, on the grounds that they need the standby
during a cloud outage, that a demo must work regardless of network, and that they are the support
desk for everyone else. All three were re-examined once "outage" was confirmed to mean the cloud
platform rather than the network:

- **Standby.** Google permits `http://localhost` redirect URIs — the standard pattern for local
  development and installed apps. Registering `http://localhost:8080/login/oauth2/code/google`
  alongside the cloud URI means the standby signs in normally.
- **Demo.** The application is hosted on Render. If the network is down the demo is down regardless
  of how authentication works, so a local password buys nothing in the scenario it was meant for.
- **Support desk.** This one is real, and it is answered by a **row, not a mechanism** (below).

Collapsing to one mechanism deletes the `password_hash` and `username` columns, the BCrypt encoder,
the login form and controller, password rate limiting, and one of two `SecurityFilterChain`
branches. What remains is a single sentence: *every user authenticates with Google and is admitted
by the allowlist.*

### Break-glass

**Two `MANAGER` rows are provisioned from day one** — the manager's own account and a second one
(the hostel office's shared account, or a personal Gmail). If either becomes inaccessible, the other
can still administer, including editing the allowlist.

Beyond that, the developer holds database access, so `UPDATE app_user SET email = ?` is the ultimate
recovery path. That is written into the runbook rather than dressed up as a feature. It is worth
noting this is no worse than a password would have been: a forgotten manager password ends in
exactly the same place, with one more mechanism to have built.

### What this costs, stated plainly

| Exposure | Assessment |
|---|---|
| Dependency on Google availability | Nobody can sign in if Google OIDC is unreachable. The application is cloud-hosted, so a network failure already takes the system down independently of authentication |
| Not integration-tested end to end | The authorization-code flow is Spring Security's code. What we wrote — the allowlist decision — is unit-tested directly; other tests use `@WithMockUser` (§12.1) |
| OAuth client secret; two redirect URIs | One environment variable (§11.5) and a one-time console setup. Scopes are non-sensitive, so no Google verification review is required |
| Recovery is partly external | A user who loses their Google account needs *some* working account before a manager can repoint the allowlist |
| Email addresses stored | Personal data, recorded in §11.4 |

Revoking a lost device is `active = false`, which blocks the next sign-in immediately and revokes
that user's push subscriptions in the same action; the session expires within 12 hours.

## 11.3 Authorization

Role-based, enforced at the service layer (not only in controllers) via `@PreAuthorize`. Roles come
from `app_user.role` at sign-in, never from anything Google asserts — Google tells us *who*, we
decide *what they may do*:

| Capability | Manager | Attender |
|---|---|---|
| View all complaints | ✅ | ❌ (own assignments only) |
| Trigger ingestion / upload CSV | ✅ | ❌ |
| Classify, assign, reassign, reject, reopen | ✅ | ❌ |
| Start / inspect / docket / resolve | ✅ (via `act-as`) | ✅ (own assignments only) |
| Claim special duties | ❌ | ✅ |
| Log penalties, adjustments, special duties | ✅ | ❌ |
| View leaderboard | ✅ (full) | ✅ (ranks + own detail) |
| View another attender's point detail | ✅ | ❌ |
| Provision users, edit the allowlist, deactivate accounts | ✅ | ❌ |
| Export reports | ✅ | ❌ |
| Edit weights, SLA table, working day | ✅ | ❌ |

**Ownership** is checked in addition to role: an attender acting on a task must hold an `ACTIVE`
`task_assignment` for it (`NOT_ASSIGNED` → 403). Role alone is never sufficient for a task action —
otherwise any attender could resolve any other attender's work and collect the points.

## 11.4 PII and data minimisation

Complaints originate from residents who never agreed to use this system and cannot log in to check
what we keep. The stance is therefore **minimisation plus scoped access**: hold only what complaint
handling, accountability and reporting need, and let each role see it only for as long as the
complaint's lifecycle justifies.

| Data | Why we hold it | Retention / access (NFR-9) |
|---|---|---|
| Student name | Lets the assigned attender identify the resident; available to the manager for oversight | **Attender: 7 days after resolution.** Manager: retained with the complaint record |
| Roll number | Unambiguous reference to the resident when handling the complaint | **Attender: 7 days after resolution.** Manager: retained with the complaint record |
| Student phone number | Lets the attender reach the resident when clarification or room access is needed | **Attender: 7 days after resolution.** Manager: retained with the complaint record |
| Resident email | Contacting the reporter if details are unclear | 90 days, then nulled |
| Room number | **Operationally essential** — dispatch depends on it, and it identifies where the work is | 1 year |
| Complaint text | Diagnosis, classification, chronic-defect detection | 1 year, then `[purged]` |
| Attender name | Assignment, accountability, leaderboard | Life of employment |
| Attender Google email | Allowlist key, and identifies the actor in the audit log | Life of employment |
| Push endpoint + keys | Delivering assignment alerts | Until revoked or 3 failures; a device identifier, so treated as personal data |
| Audit log, point ledger | Bonus disputes, accountability, historical reconstruction | Indefinite |

**The 7-day attender window is the main control.** An attender needs the student's details while
the work is live and briefly afterwards, not permanently. After that the fields disappear from the
attender's view of the complaint while remaining on the record for the manager, so oversight and
dispute resolution still work.

**No resident profiles.** We never build a resident account or a cross-complaint profile. Each
complaint carries its own reporter fields and nothing links them into a history.

Aggregates survive purging: MTTR, arrival counts and chronic-defect detection all continue to work
on rows whose text has been redacted or whose reporter fields are hidden, because they depend on
timestamps, domain and room — not on who reported it or what they wrote.

**Test data is synthetic.** Real student details never enter the repository: names and phone
numbers are replaced with synthetic values, roll numbers with synthetic identifiers, and room
numbers remapped through a fixed permutation that preserves floor structure so dispatch tests stay
meaningful. The mapping table is not committed.

## 11.5 Secrets

| Secret | Handling |
|---|---|
| Google service-account JSON | Base64 in `GOOGLE_SA_KEY` env var. **Never a file in the repo.** |
| Database credentials | `DATABASE_URL` env var, injected by the platform |
| Session signing key | `APP_SECRET` env var, generated per environment |
| Google OAuth client | `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` env vars. Authenticates the application to Google; **not** a user credential |
| VAPID key pair | `VAPID_PUBLIC_KEY` / `VAPID_PRIVATE_KEY` env vars. The private key authenticates us to push services; rotating it invalidates every subscription, so it is generated once per environment |

`.gitignore` blocks `**/google-service-account*.json`, `**/credentials.json`, `.env*` and
`application-local.yml` (§ repo root). CI runs **Gitleaks** on every PR so a committed secret fails
the build rather than reaching `main`. The Sheets service account is granted **read-only** access
to the single response sheet — if it leaks, the blast radius is reading complaints we already have.

## 11.6 Application security

| Control | Implementation |
|---|---|
| SQL injection | JPA/parameterised queries only; no string-concatenated SQL |
| XSS | Thymeleaf escapes by default; `th:utext` is banned and checked in review |
| CSRF | Spring Security tokens on all state-changing form posts |
| Clickjacking | `X-Frame-Options: DENY` |
| Transport | HTTPS enforced (`Strict-Transport-Security`); platform-terminated TLS |
| Headers | CSP, `X-Content-Type-Options: nosniff`, `Referrer-Policy` |
| Upload safety | CSV only, ≤5 MB, ≤5000 rows, content-type and header validated before parsing |
| Push payload privacy | Payloads are end-to-end encrypted (`aes128gcm`) per the Web Push spec, so Google's or Mozilla's push service relays ciphertext it cannot read. Bodies carry only the minimum needed to alert the attender — complaint reference and room number — and **never** the student's name, roll number or phone, since a lock-screen preview is visible to anyone holding the phone |
| Dependencies | OWASP Dependency-Check in CI; Dependabot enabled |
| Error leakage | RFC 7807 responses only; stack traces never returned to a client |

## 11.7 Audit as a security control

`audit_event` (§3.7) is append-only and covers sign-ins (including rejected ones, with the email
that was refused), provisioning, every state transition, every point movement, `act-as` actions, and
configuration changes.

Two properties make it trustworthy rather than decorative: it is written **in the same transaction**
as the change it records, so it cannot silently diverge; and there is **no application code path
that updates or deletes an audit row** — the JPA entity exposes no setters and the repository no
delete method. Since the ledger is also append-only, an attender's monthly bonus can be
reconstructed from first principles and defended line by line in a dispute, which is the situation
this control actually exists for.
