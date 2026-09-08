# 11. Security & Privacy

## 11.1 Threat model

This is an internal tool for one hostel with five accounts, deployed on public cloud hosting.
Naming what we are and are not defending against keeps the controls proportionate:

| Threat | In scope | Response |
|---|---|---|
| Opportunistic internet scanning of a public URL | ✅ | Auth on everything, no anonymous endpoints |
| A student discovering the URL and trying to log in | ✅ | Rate limiting, lockout, no self-registration |
| An attender viewing or altering another attender's points | ✅ | Role + ownership checks on every action |
| An attender gaming the point system | ✅ | Docket gate on credit, append-only ledger, audit |
| A lost or shared attender phone | ✅ | Remember-me token revocation, PIN reset, push subscription revocation |
| Resident PII exposure | ✅ | Minimisation, retention limits (§11.4) |
| Targeted attack by a skilled adversary | ❌ | Out of scope; no assets justify it |
| DDoS | ❌ | Free-tier platform's problem |

The most *likely* real incident here is mundane: a shared or lost phone. The controls above are
weighted accordingly.

## 11.2 Authentication

Per ADR-002: **local credentials only, invite-only provisioning, no OAuth.**

| | Manager | Attender |
|---|---|---|
| Credential | Username + password (min 12 chars) | 6-digit PIN, unique per user |
| Hash | BCrypt, cost 12 | BCrypt, cost 12 |
| Session | Standard, 12h idle timeout | Standard + 90-day device token |
| Re-auth | Every session | Once per device |

**Each attender has their own PIN.** PINs are never shared and never stored in plaintext: on
provisioning the system generates a random 6-digit value, displays it **once** to the manager to
hand over, and persists only the BCrypt hash. Neither the manager nor the database can recover it
afterwards — a forgotten PIN is reset, not looked up.

**A 6-digit PIN is weak on its own** (10⁶ space) and the design says so explicitly rather than
implying otherwise. It is acceptable here only because of the surrounding controls:

- Lockout after **5** failed attempts, 15-minute cooldown, escalating on repeat
- Global rate limit of 10 login attempts/minute/IP
- Attender identity is selected from a 4-name list, so there is no username to enumerate
- Every attempt, successful or not, is written to `audit_event`
- Manager is notified in-app on any lockout
- An attender's blast radius is small: they can see their own queue and their own points, and can
  neither view nor alter anyone else's

Sustained brute force would need ~10⁵ attempts against a 5-per-15-minutes lockout — decades. The
realistic attack is a **shoulder-surfed or shared PIN**, which lockouts cannot prevent; that is why
the manager can revoke devices and rotate PINs at will, and why the audit log records who did what.

### Device tokens

Spring Security's **`PersistentTokenBasedRememberMeServices`** backed by `JdbcTokenRepositoryImpl`,
using the framework's own `persistent_logins` table (§3.3). It implements the series/token scheme:
an indexed series identifier plus a single-use token **rotated on every request**, so a stolen
cookie stops working as soon as the legitimate device authenticates again, and the mismatch is
itself detectable. Cookie is `HttpOnly; Secure; SameSite=Lax` with a 90-day expiry.

An earlier draft hand-rolled the identical design. Using the framework's implementation deletes that
code, removes a class of subtle mistakes from a security-critical path, and gives a reviewer a
mechanism they already know (ADR-012). Revoking a lost phone is a single `DELETE` by username.

## 11.3 Authorization

Role-based, enforced at the service layer (not only in controllers) via `@PreAuthorize`:

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
| Provision users, reset PINs, revoke devices | ✅ | ❌ |
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
| Attender PIN | Authentication | Hash only, never plaintext |
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

`audit_event` (§3.7) is append-only and covers authentication attempts, provisioning, every state
transition, every point movement, `act-as` actions, and configuration changes.

Two properties make it trustworthy rather than decorative: it is written **in the same transaction**
as the change it records, so it cannot silently diverge; and there is **no application code path
that updates or deletes an audit row** — the JPA entity exposes no setters and the repository no
delete method. Since the ledger is also append-only, an attender's monthly bonus can be
reconstructed from first principles and defended line by line in a dispute, which is the situation
this control actually exists for.
