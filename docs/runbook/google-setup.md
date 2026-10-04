# Runbook — Google Cloud console setup

Who this is for: whoever owns the deployment. It assumes no knowledge of the code. Everything here
is done by hand in a browser, once, and produces values you paste into Render in
[`deploy.md`](deploy.md).

There are two separate things here and they are easy to confuse:

| | What it is for | Who it represents |
|---|---|---|
| **OAuth client** (step 2) | Signing people in | The *application*, asking Google to identify a person |
| **Service account** (step 3) | Reading the Form response sheet | A robot account with its own address, which you grant read access to one sheet |

The OAuth client is needed to deploy at all. The service account is what Google Sheets ingestion
reads the response sheet with; without it the application still starts and complaints arrive by CSV
upload instead. Do both now — a deployment with no service account has half its intake missing.

---

## Step 1 — a project

1. [console.cloud.google.com](https://console.cloud.google.com) → project picker → **New project**.
2. Name it something you will recognise in a year, e.g. `cauvery-duty-tracker`. Create it, then
   make sure it is the selected project in the top bar — **every step below must happen inside
   it**, and selecting the wrong project is the single most common way this goes wrong.

## Step 2 — the OAuth client (sign-in)

### 2a. Consent screen

**APIs & Services** → **OAuth consent screen**.

- **User type: External.** Choose this even though the users are at IIT Madras. *Internal* is only
  available to Google Workspace organisations and restricts sign-in to that organisation's
  accounts — and the allowlist is deliberately domain-agnostic, so that an attender with a
  personal Gmail works exactly like anyone else (ADR-002).
- App name: `Cauvery Duty Tracker`. User support email and developer contact email: your own.
- Scopes: add `openid`, `.../auth/userinfo.email`, `.../auth/userinfo.profile`. Nothing else. The
  app asks who you are and no more.
- **Test users:** while the app is in *Testing* only listed addresses can sign in. Add every
  address you will put in `app_user` — the two managers and every attender. This is a second
  allowlist, Google's, independent of ours; a person missing from it sees a Google error before
  our code ever runs.
- Leave publishing status as *Testing*. It does not expire for this use and avoids verification.

### 2b. The client

**APIs & Services** → **Credentials** → **Create credentials** → **OAuth client ID**.

- Application type: **Web application**. Name: `duty-tracker-web`.
- **Authorised redirect URIs — add both:**

  | URI | Why |
  |---|---|
  | `https://<your-service>.onrender.com/login/oauth2/code/google` | The deployed app. Fill in your real Render URL once step 2 of `deploy.md` has created the service. |
  | `http://localhost:8080/login/oauth2/code/google` | **The standby.** When Render is down you run the same jar on your own desktop, and it signs in with the same Google client — so `localhost` must be a registered redirect URI too, or the standby is unusable exactly when you need it. Google permits `http` for `localhost` and only for `localhost`. Port 8080 is not arbitrary: the standby has no `PORT` set and binds 8080. |

  They must match character for character — scheme, host, port, path, and no trailing slash.
  A mismatch shows as `redirect_uri_mismatch` at sign-in.

- Create. Google shows the **Client ID** and **Client secret** once.

| Value | Environment variable |
|---|---|
| Client ID | `GOOGLE_CLIENT_ID` |
| Client secret | `GOOGLE_CLIENT_SECRET` |

Paste both straight into Render's environment settings. Do not put them in a file, do not put them
in the repository, do not paste them into chat. If you lose the secret, generate a new one on the
same client and update the Render variable — the client ID does not change.

## Step 3 — the Sheets service account (ingestion)

`GOOGLE_SA_KEY` is read by `GoogleApiSheetReader`, which base64-decodes it straight into Google's
credential parser — the key never becomes a file. Without it the poll skips with a debug line and
the CSV fallback keeps working, which is a deliberate fallback and not a degraded mode.

### 3a. Enable the API

**APIs & Services** → **Library** → search *Google Sheets API* → **Enable**.

### 3b. Create the account

**IAM & Admin** → **Service accounts** → **Create service account**.

- Name: `duty-tracker-sheets-reader`.
- **"Grant this service account access to the project" — skip it. Assign no roles at all.** A
  project role would give this account standing permissions across the whole project. It needs
  exactly one thing: permission to read one spreadsheet, and that is granted on the spreadsheet in
  step 3d, not here. An account with no roles that has been shared one file can read that file and
  nothing else.
- Copy its email address — it looks like
  `duty-tracker-sheets-reader@<project>.iam.gserviceaccount.com`.

### 3c. A key

On the service account → **Keys** → **Add key** → **Create new key** → **JSON**. A `.json` file
downloads. It is a private key: treat it as you would a password.

### 3d. The response sheet, and its id

Nothing can be read until the Form is writing somewhere. In the Google Form → **Responses** →
**Link to Sheets** → create a spreadsheet. The tab it makes is called `Form Responses 1`, which is
what `GOOGLE_SHEET_NAME` defaults to; rename that tab and you must set the variable as well.

The spreadsheet's id is the middle segment of its URL:

```
https://docs.google.com/spreadsheets/d/1a2B3cD4eF5gH6iJ7kL8mN9oP0qR/edit
                                      └──────── GOOGLE_SHEET_ID ────────┘
```

Set it as `GOOGLE_SHEET_ID`. It is an identifier and not a secret — the sheet is protected by who
it is shared with, not by the id being hard to guess — but it belongs in the environment with the
rest of the configuration rather than in the repository, because it differs per deployment.

**Without this variable there is no ingestion and no error.** `spreadsheet-id` defaults to empty,
the poll finds nothing configured, and **Sync Now** reports that the response sheet is not
configured on this instance. That reads like a missing feature rather than a missing variable.

### 3e. Share the sheet with it — read-only

Open the Google Form's **response spreadsheet** → **Share** → paste the service account's email
address → role **Viewer** → uncheck "Notify people" → Share.

**Viewer, never Editor.** The app only ever reads the sheet. If this key leaks, the worst an
attacker gets is a copy of complaints we already have; with Editor they could alter or destroy the
source of record (§11.5).

### 3f. Base64 the key and store it

The key is JSON with newlines in it, which does not survive a dashboard environment-variable box,
so it goes in base64-encoded. Secrets are environment variables and never files (§11.5).

macOS / Linux:

```bash
base64 -i ~/Downloads/<project>-<id>.json | tr -d '\n' | pbcopy   # macOS, now on the clipboard
base64 -w 0 ~/Downloads/<project>-<id>.json                        # Linux, prints one long line
```

Paste the result into Render as `GOOGLE_SA_KEY`.

### 3g. Delete the downloaded file

```bash
rm ~/Downloads/<project>-<id>.json
```

Then empty the Trash, and check it is not also sitting in a synced folder or an email to yourself.
A private key on a laptop is the one artefact of this whole setup that nobody can revoke for you —
though you can always delete the key in the console and make a new one, which is the right move if
you are unsure where a copy ended up.

---

## Step 4 — the wake-up trigger (Apps Script)

A free-tier instance sleeps when idle, and a sleeping instance has no scheduler running, so the
three-minute poll stops happening exactly when nobody is looking (§2.5). A small script on the
response sheet fixes that: when a Form response arrives it makes one HTTP request to the
application, which is what causes the platform to wake the instance and reconcile immediately.

The request **carries no complaint data**. It says only "the sheet changed"; the application then
does its usual watermark comparison and works out for itself what is new. That is why nothing here
has to be reliable — a trigger that fires twice, fires for a row already held, or never fires at
all all end in the same place. If this step is skipped entirely the system still works; complaints
just arrive up to three minutes later, or when the manager presses **Sync Now**.

### 4a. Generate the shared secret

The endpoint has no session to authenticate — its caller is a Google server — so it is guarded by
a shared secret instead. Generate one:

```bash
openssl rand -base64 32
```

Keep the output on your clipboard for the next two steps. It goes in exactly two places and is
**never** committed: the repository contains only the variable's name.

| Where | As |
|---|---|
| The hosting platform's environment settings | `INGESTION_WAKE_SECRET` |
| The Apps Script project's script properties | `WAKE_SECRET` |

**Until `INGESTION_WAKE_SECRET` is set, the endpoint refuses every call.** An unset secret closes
the door rather than opening it, so there is no window in which the URL is unguarded.

### 4b. Add the script

Open the Google Form's **response spreadsheet** → **Extensions** → **Apps Script**. Replace
whatever is in `Code.gs` with this and save:

```javascript
/**
 * Wakes the Duty Tracker when a Form response arrives (design §2.5).
 *
 * Sends no response data — only the fact that the sheet changed. The application does its own
 * delta detection, so a duplicate call is harmless and a failed one costs nothing but a few
 * minutes' latency.
 */
function wakeDutyTracker() {
  var props = PropertiesService.getScriptProperties();
  UrlFetchApp.fetch(props.getProperty('WAKE_URL'), {
    method: 'post',
    headers: { 'X-Wake-Secret': props.getProperty('WAKE_SECRET') },
    // The app answers 202 immediately and ingests in the background, so there is nothing here
    // worth retrying — and an uncaught failure would email you on every single response.
    muteHttpExceptions: true
  });
}
```

### 4c. Give it the URL and the secret

**Project Settings** (the gear on the left) → **Script properties** → **Add script property**,
twice:

| Property | Value |
|---|---|
| `WAKE_URL` | `https://<your-service>.onrender.com/api/ingestion/wake` |
| `WAKE_SECRET` | The string from step 4a |

Script properties rather than constants in the file: the secret then is not in the script's source,
which is visible to anyone you ever share the spreadsheet's editing rights with.

### 4d. Attach the trigger

**Triggers** (the clock on the left) → **Add trigger**:

| Field | Value |
|---|---|
| Function to run | `wakeDutyTracker` |
| Event source | **From spreadsheet** |
| Event type | **On form submit** |
| Failure notification | Daily (weekly is easy to miss; immediate is noise) |

**"On form submit", not "On change".** *On change* also fires when you or anyone else edits a cell
by hand, which would wake the instance for nothing.

Save. Google asks you to authorise the script the first time — it is your own script on your own
sheet, and the "unverified app" warning is expected. `UrlFetchApp` is the only permission it wants.

### 4e. Check it

Submit a test response through the Form. Within a few seconds:

- **Apps Script → Executions** shows `wakeDutyTracker` with type *Trigger* and status *Completed*.
  Note what that does **not** tell you: `muteHttpExceptions: true` means a refused call completes
  just like an accepted one, so this line confirms only that the script ran and reached something.
  A *Failed* line means it could not reach the service at all.
- **The hosting platform's own logs** are the only place a rejected call is visible.
  `Refused a wake-up call: the shared secret did not match` means the two copies of the secret
  differ — most often a trailing newline on one of them, since `openssl rand -base64 32` emits one.
  An accepted call logs no warning and is followed within seconds by an ingestion run on the
  `task-1` thread, which is the executor the endpoint hands the work to.
- The complaint appears on the manager's dashboard without anyone pressing **Sync Now**.

Then delete the test complaint's row from the sheet if you like — it has already been ingested and
the application will not re-read it (§5.3, deletions are not tracked).

## Every variable this runbook produces

| Variable | From | Needed |
|---|---|---|
| `GOOGLE_CLIENT_ID` | Step 2b | Now — sign-in does not work without it |
| `GOOGLE_CLIENT_SECRET` | Step 2b | Now — sign-in does not work without it |
| `GOOGLE_SHEET_ID` | Step 3d, the id in the sheet's URL | For Sheets ingestion — without it there is no ingestion and no error |
| `GOOGLE_SHEET_NAME` | Step 3d | Only if the response tab is not named `Form Responses 1` |
| `GOOGLE_SA_KEY` | Step 3f, base64 of the JSON key | For Sheets ingestion |
| `INGESTION_WAKE_SECRET` | Step 4a | Only for the wake-up trigger — without it the endpoint stays closed and the scheduled poll is the backstop |

Back to [`deploy.md`](deploy.md) step 4.

## Checklist

- [ ] Both redirect URIs registered, including `http://localhost:8080/login/oauth2/code/google`
- [ ] Every real user's Google address added as a test user on the consent screen
- [ ] Service account created with **no project roles**
- [ ] Form linked to a response spreadsheet, and its id set as `GOOGLE_SHEET_ID`
- [ ] Response tab still named `Form Responses 1`, or `GOOGLE_SHEET_NAME` set to match
- [ ] Response sheet shared with the service account as **Viewer**
- [ ] Downloaded JSON key deleted from the machine
- [ ] `INGESTION_WAKE_SECRET` set on the host, and the same value as the `WAKE_SECRET` script property
- [ ] `WAKE_URL` points at the deployed service, path `/api/ingestion/wake`
- [ ] Trigger is **On form submit**, from the spreadsheet
- [ ] A test response reached the dashboard without pressing Sync Now
- [ ] The platform log shows no `Refused a wake-up call` line for that submission
- [ ] Nothing from this page committed to the repository
