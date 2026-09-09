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
weighted accordingly — and note that with no attender credential stored anywhere, a lost phone
exposes a *session*, not a secret.

## 11.2 Authentication

Per ADR-002: **Google sign-in for attenders, a local password for the manager, invite-only
provisioning for both.**

| | Manager | Attender |
|---|---|---|
| Mechanism | Username + password (Spring form login) | Google OIDC (`openid, email, profile`) |
| Credential stored | BCrypt hash, cost 12 | **None** |
| Admission decided by | Password check | Verified email present and `active` in `app_user` |
| Session | 12 h idle timeout | 12 h idle timeout |
| Re-authentication | Type the password | One tap — Google account already signed in on Android |

### Why the split

Attenders get the mechanism with **no credential to lose, forget, or leak**, and re-entry costs a
single tap on a phone where the Google account is already active. The manager gets the mechanism
with **no external dependency**, which matters for three separate reasons: they are the only person
who can reach the standby deployment during a cloud outage, they are the account that must work
during a demo regardless of network conditions, and they are the support desk that fixes everyone
else's access.

### The allowlist is the access-control boundary

Google establishes *who someone is*; it has no opinion on whether they may use this system. That
decision is ours and is made in one place — a custom `OidcUserService` that resolves the verified
email against `app_user` and throws if there is no matching active row. A sign-in from any other
Google account, institute or personal, is rejected without creating anything.

Emails are normalised (trimmed, lower-cased) before comparison, and matched only against the
`email_verified` claim — never a self-asserted address.

### What this removes, and what it adds

**Removed:** PIN generation and hashing, lockout counters, a PIN reset flow, credential rate
limiting, and a remember-me token table. None of these can fail if none of them exist, and there is
no longer a weak 6-digit secret to defend.

**Added, and stated plainly:**

| New exposure | Assessment |
|---|---|
| Dependency on Google availability | If Google OIDC is unreachable, attenders cannot sign in. The manager still can, and can record work on their behalf via `act-as` (§9.4) |
| OAuth client secret | One more environment variable (§11.5). It authenticates our application to Google; it is not a user credential |
| Two redirect URIs to maintain | The cloud URL and `http://localhost:8080/...` for the standby and development. A stale entry breaks sign-in visibly, not silently |
| Attender email addresses stored | Personal data, recorded in §11.4 |

The manager's password is rate-limited to 10 attempts/minute/IP rather than locked out after N
failures. Lockout on a single-administrator account is itself a denial-of-service: anyone who knows
the username could lock the only person who can fix it.

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
| Edit lexicon, weights, SLA table | ✅ | ❌ |

**Ownership** is checked in addition to role: an attender acting on a task must hold an `ACTIVE`
`task_assignment` for it (`NOT_ASSIGNED` → 403). Role alone is never sufficient for a task action —
otherwise any attender could resolve any other attender's work and collect the points.

## 11.4 PII and data minimisation

| Data | Why we hold it | Retention (NFR-9) |
|---|---|---|
| Resident email | Contacting the reporter if details are unclear | 90 days, then nulled |
| Room number | **Operationally essential** — dispatch depends on it | 1 year |
| Complaint text | Diagnosis, classification, chronic-defect detection | 1 year, then `[purged]` |
| Attender name | Assignment, leaderboard | Life of employment |
| Attender Google email | Allowlist key, and identifies the actor in the audit log | Life of employment |
| Push endpoint + keys | Delivering assignment alerts | Until revoked or 3 failures; a device identifier, so treated as personal data |

We **do not** collect resident names or phone numbers even when the Form offers them — the mapped
column set (§5.2) simply omits them. The cheapest way to protect data is not to hold it.

Aggregates survive purging: MTTR, arrival counts and chronic-defect detection all continue to work
on rows whose text has been redacted, because they depend on timestamps, domain and room — not on
what the resident wrote.

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
| Push payload privacy | Payloads are end-to-end encrypted (`aes128gcm`) per the Web Push spec, so Google's or Mozilla's push service relays ciphertext it cannot read. Bodies still avoid resident identifiers — a lock-screen preview is visible to anyone holding the phone, so notifications name a room, never a person |
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
