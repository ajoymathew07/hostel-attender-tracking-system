# Runbook — deploying to Render

Who this is for: whoever owns the deployment. It assumes nothing about the code. Follow it top to
bottom; every step is something you click or paste.

Do [`google-setup.md`](google-setup.md) **first** — step 4 needs two values that only exist once
the Google console work is done.

**What you are building** (architecture §2.6): one Render web service running the app, one managed
PostgreSQL database, and — kept in your pocket for the day Render is down — the *same jar* started
on your own desktop against the *same database*. There is only ever one database. The standby is
not a copy and there is nothing to sync.

---

## Step 0 — before anything

- A GitHub account with this repository, and a Render account signed in with it.
- `psql` on your desktop (`brew install libpq` on a Mac, or use Render's web shell for the
  database). You need it once, at step 6, and it is the step that matters most.
- Render's free PostgreSQL instance **expires after 30 days** and is then deleted. For a course
  project that is usually fine, but note the date you created it. Everything below works the same
  on a paid instance.

## Step 1 — create the database

1. Render dashboard → **New +** → **Postgres**.
2. Name `duty-tracker-db`. Region: whichever is nearest — **use the same region for the web
   service in step 2**, or the two cannot talk over the internal network.
3. PostgreSQL version 16, to match `docker-compose.yml` and the test containers.
4. Create it, and wait until the status is *Available*.
5. Open it and leave the **Info** tab open. You will copy five fields from it in step 4:
   *Hostname*, *Port*, *Database*, *Username*, *Password*. There is also an *External Database
   URL* further down — you need that one in step 8, not now.

## Step 2 — create the web service

1. **New +** → **Web Service** → connect this GitHub repository.
2. Branch: `main`. Region: **the same one as the database.**
3. Language / runtime: **Docker**. Render will find the `Dockerfile` at the repository root and
   needs no build or start command — the image already runs the jar.
   (Render has no native Java runtime, which is why a Dockerfile is the route.)
4. Instance type: Free is enough for this system's eight users. Note that a free instance sleeps
   after ~15 minutes idle and takes 30–60 s to answer the next request.
5. **Health Check Path**: `/actuator/health`. Set it now — without it Render cannot tell a started
   app from a crash-looping one.
6. Do **not** deploy yet. Add the environment variables first (step 4), or the first deploy will
   fail on purpose and you will have to redeploy anyway.

## Step 3 — how the app decides which configuration to use

The app has exactly two configurations, `local` and `cloud`, and **no default**. If neither is
named it refuses to start and prints which variable to set. `SPRING_PROFILES_ACTIVE=cloud` is baked
into the `Dockerfile`, so on Render it is already correct and you do not set it by hand. If you
ever override it in the dashboard, `cloud` is the only valid value there.

## Step 4 — environment variables

Web service → **Environment** → add each of these. All five are required; the app will not start
without any one of them and will name the ones that are missing.

| Variable | Where the value comes from |
|---|---|
| `DATABASE_URL` | Built by hand from the database's **Info** tab: `jdbc:postgresql://<Hostname>:<Port>/<Database>`. Use the **internal** hostname (it looks like `dpg-xxxxxxxx-a`), which needs no SSL and does not leave Render's network. It must start with `jdbc:` — Render's own "Internal Database URL" starts with `postgresql://` and the app will reject it. |
| `DATABASE_USERNAME` | Database → Info → *Username* |
| `DATABASE_PASSWORD` | Database → Info → *Password* |
| `GOOGLE_CLIENT_ID` | Google Cloud console, from [`google-setup.md`](google-setup.md) step 2 |
| `GOOGLE_CLIENT_SECRET` | Google Cloud console, from [`google-setup.md`](google-setup.md) step 2 |

Set by the platform, not by you:

| Variable | Note |
|---|---|
| `PORT` | Render injects it and the app binds to it. Do not set it. |
| `SPRING_PROFILES_ACTIVE` | Already `cloud`, from the `Dockerfile`. |

Not needed yet — they belong to features that are not built:

- `GOOGLE_SA_KEY` — the Sheets service-account key. Create it now if you like
  ([`google-setup.md`](google-setup.md) step 3); nothing reads it until Google Sheets ingestion
  ships. Until then, CSV upload is the ingestion path.
- `APP_SECRET` — §11.5 reserves it as a session signing key. Sessions are currently held in the
  application's own memory and nothing signs them, so there is nothing for this variable to do.
  Do not set it; it would be a value nobody reads.

Optional — push alerts to attender phones (section 9.6). Without both keys the app runs normally
and the "Enable alerts" button stays hidden.

| Variable | Where the value comes from |
|---|---|
| `VAPID_PUBLIC_KEY` / `VAPID_PRIVATE_KEY` | Generate once with `npx web-push generate-vapid-keys` and paste both. Keep them: a new pair makes every existing subscription stop working, and each attender has to tap "Enable alerts" again. |
| `VAPID_SUBJECT` | `mailto:` followed by the manager's address. Push services use it to reach whoever runs the app. |

## Step 5 — deploy

Trigger the deploy. Watch the log. Flyway runs on boot and applies the migrations to the empty
database — you should see it create the schema history table and apply `V1` through `V7`.

If the log ends in a message naming environment variables, you missed one in step 4. That message
is deliberate: the app would rather stop than start against a database you did not mean.

## Step 6 — ⚠️ THE STEP THAT WILL BITE YOU: replace the placeholder managers

**Nobody can sign in yet. Not you. Do this before you try.**

The schema seeds two `MANAGER` rows so that the recovery story is a row rather than a mechanism
(ADR-002). Both addresses deliberately use the reserved `.invalid` domain, so neither can ever be a
real Google account:

- `manager.placeholder@replace-me.invalid`
- `breakglass.placeholder@replace-me.invalid`

Sign-in is by Google account, admitted by an allowlist of addresses in `app_user`. A `.invalid`
address matches no Google account, so **until you replace both, every sign-in is rejected** — and
the rejection looks exactly like "the deploy is broken".

Connect to the database (Render database page → **Connect** → *PSQL Command*, paste it into a
terminal) and run, with the two real Google addresses:

```sql
UPDATE app_user SET email = 'the.manager@gmail.com'
 WHERE email = 'manager.placeholder@replace-me.invalid';

UPDATE app_user SET email = 'hostel.office@gmail.com'
 WHERE email = 'breakglass.placeholder@replace-me.invalid';
```

Use two *different* accounts controlled by two different people, or by one person on two devices.
That is the whole point of having two.

Check it took, and that no placeholder survives:

```sql
SELECT email, role, active FROM app_user WHERE role = 'MANAGER';
```

Neither row may still end in `.invalid`.

## Step 7 — verify the deploy

1. **Health.** `curl https://<your-service>.onrender.com/actuator/health` → `{"status":"UP"}`.
   That exact response and nothing more is the point: it must be reachable without signing in, so
   it deliberately reveals no version, no database name and no component detail.
   `/actuator/health/readiness` and `/actuator/health/liveness` answer the same way. Every other
   `/actuator/...` path returns 404 — they are not published at all.
2. **Sign-in.** Open the service URL in a browser. You get the login page; sign in with one of the
   two addresses from step 6. If Google shows a `redirect_uri_mismatch`, the Render URL is not
   registered — [`google-setup.md`](google-setup.md) step 2, and note the URL has to match exactly,
   including `https` and no trailing slash.
3. **End to end.** Upload a CSV, assign a complaint to an attender, resolve it from a phone, and
   check the leaderboard moved. That is the Phase 1 exit criterion (§13.3).

## Step 8 — the standby, for when Render is down

Same jar, same database, started on your desktop. Attenders cannot reach it — it is on
`localhost` — but you keep working and everything you do lands in the one real database
(ADR-001).

Set it up *now*, while nothing is wrong, and test it once.

1. Build the jar once and keep it somewhere you can find in a hurry:

   ```bash
   ./mvnw -DskipTests package        # target/duty-tracker-0.1.0-SNAPSHOT.jar
   ```

2. Get the database's **External Database URL** from the Render database page. External
   connections must use TLS, so the JDBC URL needs `?sslmode=require`:

   ```
   jdbc:postgresql://<external-hostname>:5432/<database>?sslmode=require
   ```

   The external hostname is the long one, ending in `.render.com`.

3. Start it, with the variables in the environment and nowhere on disk:

   ```bash
   SPRING_PROFILES_ACTIVE=cloud \
   DATABASE_URL='jdbc:postgresql://<external-hostname>:5432/<database>?sslmode=require' \
   DATABASE_USERNAME='<username>' \
   DATABASE_PASSWORD='<password>' \
   GOOGLE_CLIENT_ID='<client id>' \
   GOOGLE_CLIENT_SECRET='<client secret>' \
   java -jar duty-tracker-0.1.0-SNAPSHOT.jar
   ```

   No `PORT`, so it binds 8080 — which is why the registered redirect URI has to say
   `http://localhost:8080/login/oauth2/code/google` and not some other port.

4. Open `http://localhost:8080` and sign in with the same Google account. It is the same
   application against the same data; a complaint you resolve here is resolved for everyone the
   moment Render comes back.

Do not run a second database, and do not restore a dump into one "so the standby has data". Two
copies of the points ledger that disagree is the failure this system exists to prevent, and it is
the reason the standby is built this boring way.

## If something goes wrong

| Symptom | Cause |
|---|---|
| Start fails naming environment variables | One of the five in step 4 is missing or blank. |
| Start fails saying exactly one profile must be active | `SPRING_PROFILES_ACTIVE` was overridden to something other than `cloud`, or blanked. |
| `URL must start with 'jdbc'` | `DATABASE_URL` was pasted as Render gives it. Rewrite it as `jdbc:postgresql://...`. |
| Connection refused / timeout to the database | The web service and the database are in different regions, or the internal hostname was used from your desktop. The desktop needs the external host and `sslmode=require`. |
| Health check passes, sign-in always fails | Step 6 was skipped, or was done for one row only. |
| `redirect_uri_mismatch` at Google | The exact service URL is not in the registered redirect URIs. |
| First request after a quiet period hangs ~45 s | Free instance waking. Not a fault. |
