# Runbook — Google Cloud console setup

Who this is for: whoever owns the deployment. It assumes no knowledge of the code. Everything here
is done by hand in a browser, once, and produces values you paste into Render in
[`deploy.md`](deploy.md).

There are two separate things here and they are easy to confuse:

| | What it is for | Who it represents |
|---|---|---|
| **OAuth client** (step 2) | Signing people in | The *application*, asking Google to identify a person |
| **Service account** (step 3) | Reading the Form response sheet | A robot account with its own address, which you grant read access to one sheet |

The OAuth client is needed to deploy at all. The service account is only needed once Google Sheets
ingestion ships; until then complaints arrive by CSV upload. Doing both now saves a second visit.

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

Only needed when Google Sheets ingestion ships. Nothing reads `GOOGLE_SA_KEY` today.

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

### 3d. Share the sheet with it — read-only

Open the Google Form's **response spreadsheet** → **Share** → paste the service account's email
address → role **Viewer** → uncheck "Notify people" → Share.

**Viewer, never Editor.** The app only ever reads the sheet. If this key leaks, the worst an
attacker gets is a copy of complaints we already have; with Editor they could alter or destroy the
source of record (§11.5).

### 3e. Base64 the key and store it

The key is JSON with newlines in it, which does not survive a dashboard environment-variable box,
so it goes in base64-encoded. Secrets are environment variables and never files (§11.5).

macOS / Linux:

```bash
base64 -i ~/Downloads/<project>-<id>.json | tr -d '\n' | pbcopy   # macOS, now on the clipboard
base64 -w 0 ~/Downloads/<project>-<id>.json                        # Linux, prints one long line
```

Paste the result into Render as `GOOGLE_SA_KEY`.

### 3f. Delete the downloaded file

```bash
rm ~/Downloads/<project>-<id>.json
```

Then empty the Trash, and check it is not also sitting in a synced folder or an email to yourself.
A private key on a laptop is the one artefact of this whole setup that nobody can revoke for you —
though you can always delete the key in the console and make a new one, which is the right move if
you are unsure where a copy ended up.

## Every variable this runbook produces

| Variable | From | Needed |
|---|---|---|
| `GOOGLE_CLIENT_ID` | Step 2b | Now — sign-in does not work without it |
| `GOOGLE_CLIENT_SECRET` | Step 2b | Now — sign-in does not work without it |
| `GOOGLE_SA_KEY` | Step 3e, base64 of the JSON key | When Sheets ingestion ships |

Back to [`deploy.md`](deploy.md) step 4.

## Checklist

- [ ] Both redirect URIs registered, including `http://localhost:8080/login/oauth2/code/google`
- [ ] Every real user's Google address added as a test user on the consent screen
- [ ] Service account created with **no project roles**
- [ ] Response sheet shared with the service account as **Viewer**
- [ ] Downloaded JSON key deleted from the machine
- [ ] Nothing from this page committed to the repository
