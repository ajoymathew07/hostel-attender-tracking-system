# Runbook — deploying to Render

Who this is for: whoever owns the deployment. It assumes nothing about the code. Follow it top to
bottom; every step is something you click or paste.

Do [`google-setup.md`](google-setup.md) **first** — step 4 needs two values that only exist once
the Google console work is done.

**What you are building** (architecture §2.6): one Render web service running the app, one managed
PostgreSQL database on [Neon](https://neon.com), and — kept in your pocket for the day Render is down — the *same jar* started
on your own desktop against the *same database*. There is only ever one database. The standby is
not a copy and there is nothing to sync.

---

## Step 0 — before anything

- A GitHub account with this repository, a Render account signed in with it, and a Neon account
  (sign in with the same GitHub or Google account; no card needed).
- Why Neon and not Render's own Postgres: Render's free database **is deleted 30 days after it is
  created**. Neon's free plan does not expire. It gives 0.5 GB of storage (this system uses a few
  MB) and 100 compute-hours a month, and it suspends the database after 5 idle minutes, which costs
  nothing. The app only sees a PostgreSQL URL, so nothing in the code is tied to Neon.

## Step 1 — create the database

1. Neon console → **New project**.
2. Name `duty-tracker`. Postgres version **16**, to match `docker-compose.yml` and the test
   containers. Region: the AWS region nearest your Render region (Singapore with Render Singapore,
   for example). Every query crosses between the two, so near matters.
3. Project → **Settings → Compute**: set the compute size to a fixed **0.25 CU**. Autoscaling up
   is what would use the monthly compute-hours quickly; eight users never need more.
4. Project dashboard → **Connect**. Pick the `neondb` database and the `neondb_owner` role, and
   **turn off "Connection pooling"**. The pooled host runs PgBouncer in transaction mode, which
   breaks the session-level lock Flyway takes while it migrates; the app has its own pool of five.
5. Neon shows a string like
   `postgresql://neondb_owner:<password>@ep-xxxx-xxxx.ap-southeast-1.aws.neon.tech/neondb?sslmode=require`.
   Keep it open. You will split it into three variables in step 4.

## Step 2 — create the web service

1. **New +** → **Web Service** → connect this GitHub repository.
2. Branch: `main`. Region: the one nearest the Neon region from step 1.
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
| `DATABASE_URL` | Neon's string from step 1, rewritten: `jdbc:postgresql://ep-xxxx-xxxx.ap-southeast-1.aws.neon.tech/neondb?sslmode=require`. That is, put `jdbc:` in front and **remove `neondb_owner:<password>@`**. The JDBC driver does not read a user and password from the host part, and the app rejects a URL that does not start with `jdbc:`. Keep `?sslmode=require`; Neon refuses connections without TLS. Drop any `&channel_binding=require`. |
| `DATABASE_USERNAME` | The part before the `:` in Neon's string, normally `neondb_owner` |
| `DATABASE_PASSWORD` | The part between the `:` and the `@` |
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
database — you should see it create the schema history table and apply every `V…` migration in
`src/main/resources/db/migration`. The first start after a quiet spell can take a second or two
longer while Neon wakes the database.

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

Open the Neon console → **SQL Editor** (database `neondb`), and run, with the two real Google
addresses:

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

2. Use the same three database values as Render, from step 4. Neon has no internal network, so the
   one URL works from Render and from your desktop alike.

3. Start it, with the variables in the environment and nowhere on disk:

   ```bash
   SPRING_PROFILES_ACTIVE=cloud \
   DATABASE_URL='jdbc:postgresql://ep-xxxx-xxxx.ap-southeast-1.aws.neon.tech/neondb?sslmode=require' \
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
| `URL must start with 'jdbc'` | `DATABASE_URL` was pasted as Neon gives it. Rewrite it as `jdbc:postgresql://...` (step 4). |
| `password authentication failed` or `UnknownHostException` | The `user:password@` part was left in `DATABASE_URL`. Remove it; they go in the other two variables. |
| `connection requires SSL` / `no pg_hba.conf entry` | `?sslmode=require` is missing from `DATABASE_URL`. |
| Flyway hangs or fails taking its lock | `DATABASE_URL` points at the pooled host (it has `-pooler` in its name). Use the direct host (step 1.4). |
| Neon says the compute quota is exhausted | 100 compute-hours were used this month. The database comes back on the 1st, or on a paid plan. Check the compute size is fixed at 0.25 CU (step 1.3). |
| Health check passes, sign-in always fails | Step 6 was skipped, or was done for one row only. |
| `redirect_uri_mismatch` at Google | The exact service URL is not in the registered redirect URIs. |
| First request after a quiet period hangs ~45 s | Free instance waking. Not a fault. |

## Moving from Render's database to Neon

If the old Render database was already deleted, there is nothing to move. Do steps 1, 4 and 6 and
the app rebuilds an empty schema on its next start. The data on the old database is gone, so set
up the attenders and import the complaints again as on a first deploy.

If it is still reachable, copy it across once, with the web service stopped (Render → *Suspend*)
so nothing writes in between:

```bash
pg_dump --no-owner --no-acl '<Render External Database URL>' \
  | psql 'postgresql://neondb_owner:<password>@ep-xxxx-xxxx.ap-southeast-1.aws.neon.tech/neondb?sslmode=require'
```

Both of these take the `postgresql://` form, not the `jdbc:` one, and `pg_dump` must be version 16
or newer (`pg_dump --version`). The dump includes
`flyway_schema_history`, so Flyway sees the schema as already migrated. Then change the three
`DATABASE_*` variables on Render (step 4) and resume the service. Step 6 is already done, because
the manager rows came across.
