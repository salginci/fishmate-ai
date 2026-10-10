# Deployment

Target: **Cloud Run + Cloud SQL**, one stateless service. No Kubernetes — the
backend has no state of its own and nothing here needs an orchestrator. One
engineer should be able to operate it.

```
Android ──HTTPS──▶ Cloud Run (fishmate-api) ──▶ Cloud SQL (PostgreSQL 16)
                          │
                          ├──▶ Secret Manager (GEMINI_API_KEY, AUTH_SECRET)
                          ├──▶ Gemini API (token minting only)
                          └──▶ Open-Meteo (no credential)

Android ──WSS────▶ Gemini Live API (direct, constrained ephemeral token)
```

Note the second line: media never transits Cloud Run, so instance size and
concurrency are driven by token minting and tool calls — both small, fast
requests.

---

## 1. Prerequisites

```bash
gcloud auth login
gcloud config set project YOUR_PROJECT_ID
gcloud services enable run.googleapis.com sqladmin.googleapis.com \
    secretmanager.googleapis.com artifactregistry.googleapis.com \
    cloudbuild.googleapis.com
```

> `gcloud auth login` is interactive — run it yourself with `! gcloud auth login`.

```bash
export PROJECT_ID=$(gcloud config get-value project)
export REGION=europe-west1          # close to the first users (Türkiye)
export SERVICE=fishmate-api
export DB_INSTANCE=fishmate-db
```

## 2. Secrets

```bash
printf '%s' "YOUR_GEMINI_API_KEY" | \
  gcloud secrets create gemini-api-key --data-file=- --replication-policy=automatic

openssl rand -hex 32 | \
  gcloud secrets create auth-secret --data-file=- --replication-policy=automatic

openssl rand -base64 24 | tr -d '\n' | \
  gcloud secrets create db-password --data-file=- --replication-policy=automatic
```

Secrets are mounted as environment variables, never baked into the image.

## 3. Cloud SQL

```bash
gcloud sql instances create "$DB_INSTANCE" \
  --database-version=POSTGRES_16 \
  --tier=db-f1-micro \
  --region="$REGION" \
  --storage-auto-increase \
  --backup-start-time=03:00

gcloud sql databases create fishmate --instance="$DB_INSTANCE"

gcloud sql users create fishmate \
  --instance="$DB_INSTANCE" \
  --password="$(gcloud secrets versions access latest --secret=db-password)"
```

`db-f1-micro` is right for a demo and early users; move to `db-g1-small` when
concurrent sessions justify it.

## 4. Service account

```bash
gcloud iam service-accounts create fishmate-api \
  --display-name="FishMate API"

SA="fishmate-api@${PROJECT_ID}.iam.gserviceaccount.com"

for role in roles/cloudsql.client roles/secretmanager.secretAccessor; do
  gcloud projects add-iam-policy-binding "$PROJECT_ID" \
    --member="serviceAccount:${SA}" --role="$role"
done
```

Least privilege: Cloud SQL client and secret reader, nothing else.

## 5. Build and deploy

```bash
cd backend

gcloud builds submit --tag "gcr.io/${PROJECT_ID}/${SERVICE}:$(git rev-parse --short HEAD)"

CONN="${PROJECT_ID}:${REGION}:${DB_INSTANCE}"
DB_PASS=$(gcloud secrets versions access latest --secret=db-password)

gcloud run deploy "$SERVICE" \
  --image "gcr.io/${PROJECT_ID}/${SERVICE}:$(git rev-parse --short HEAD)" \
  --region "$REGION" \
  --platform managed \
  --service-account "$SA" \
  --add-cloudsql-instances "$CONN" \
  --set-secrets "GEMINI_API_KEY=gemini-api-key:latest,AUTH_SECRET=auth-secret:latest" \
  --set-env-vars "ENVIRONMENT=production" \
  --set-env-vars "LOG_JSON=true" \
  --set-env-vars "DEMO_MODE_ENABLED=false" \
  --set-env-vars "CORS_ORIGINS=https://fishmate.example" \
  --set-env-vars "WEATHER_PROVIDER=open_meteo" \
  --set-env-vars "DATABASE_URL=postgresql+asyncpg://fishmate:${DB_PASS}@/fishmate?host=/cloudsql/${CONN}" \
  --min-instances 0 \
  --max-instances 10 \
  --concurrency 40 \
  --cpu 1 --memory 512Mi \
  --timeout 60s \
  --allow-unauthenticated
```

Why these values:

| Flag | Reason |
|---|---|
| `LOG_JSON=true` | Cloud Logging parses structured logs into queryable fields |
| `DEMO_MODE_ENABLED=false` | Required — startup **refuses** production with demo mode on |
| `CORS_ORIGINS` explicit | Also required; `*` fails the startup check |
| `min-instances 0` | Cold starts are acceptable: the first request is a login, not a live session |
| `concurrency 40` | Requests are short and I/O-bound; no media passes through |
| `timeout 60s` | Nothing here legitimately takes longer |

> **`min-instances 1`** is worth the cost for a live presentation — a cold start
> during the demo is an avoidable few seconds.

## 6. Migrations

Cloud Run instances are ephemeral, so migrations run as a one-off job rather
than at container start (which would race between instances):

```bash
gcloud run jobs create fishmate-migrate \
  --image "gcr.io/${PROJECT_ID}/${SERVICE}:$(git rev-parse --short HEAD)" \
  --region "$REGION" \
  --service-account "$SA" \
  --set-cloudsql-instances "$CONN" \
  --set-secrets "AUTH_SECRET=auth-secret:latest" \
  --set-env-vars "DATABASE_URL=postgresql+asyncpg://fishmate:${DB_PASS}@/fishmate?host=/cloudsql/${CONN}" \
  --command alembic --args upgrade,head

gcloud run jobs execute fishmate-migrate --region "$REGION" --wait
```

Re-run on each deploy that includes a migration.

## 7. Verify

```bash
URL=$(gcloud run services describe "$SERVICE" --region "$REGION" --format='value(status.url)')

curl -s "$URL/api/v1/health" | python3 -m json.tool
# live_api_configured must be true

curl -s "$URL/api/v1/health/ready" | python3 -m json.tool
# {"status": "ready"}  -> the database is reachable

curl -s "$URL/docs" -o /dev/null -w '%{http_code}\n'
# 404 -> docs correctly disabled in production

curl -s "$URL/api/v1/demo/scenarios" -o /dev/null -w '%{http_code}\n'
# 404 -> demo router correctly not registered
```

The last two are the deployment's own security checks. If either returns 200,
`ENVIRONMENT` is not set to `production`.

## 8. Point the app at it

```bash
cd android
./gradlew assembleRelease -PapiBaseUrl="$URL/"
```

The release build already forbids cleartext, so an `http://` URL will be
rejected at runtime — which is the intended behaviour.

---

## Operating

```bash
# logs
gcloud run services logs tail "$SERVICE" --region "$REGION"

# errors only
gcloud logging read \
  'resource.type=cloud_run_revision AND severity>=ERROR' \
  --limit 50 --format json

# token minting rate (the cost signal worth watching)
gcloud logging read 'jsonPayload.event="live_token.minted"' --limit 100
```

Metrics are exposed at `/api/v1/health/metrics` **outside** production; in
production they are deliberately not public, and the platform's own monitoring
covers request rate, latency and errors.

### What to watch

| Signal | Why |
|---|---|
| `live_token.minted` rate | Directly proportional to Live API cost |
| `tools.failed` / `tools.crashed` | A broken tool degrades answers silently |
| `weather.upstream_5xx` | Open-Meteo trouble; the app degrades gracefully but answers get vaguer |
| `http.latency` p95 on `/tools/execute-batch` | This sits inside a live conversation — slow tools mean awkward pauses |

## Rollback

```bash
gcloud run revisions list --service "$SERVICE" --region "$REGION"
gcloud run services update-traffic "$SERVICE" \
  --region "$REGION" --to-revisions REVISION_NAME=100
```

Roll the service back first; only roll a migration back if the new revision
cannot run against the new schema (migrations here are additive).

## Cost sketch

| Item | Rough monthly, light use |
|---|---|
| Cloud Run (min-instances 0) | a few dollars |
| Cloud SQL `db-f1-micro` | ~$8–10 |
| Secret Manager | cents |
| Open-Meteo | **free, no key** |
| **Gemini Live** | the dominant cost — see [GEMINI_LIVE.md](GEMINI_LIVE.md#9-cost-control) |

Live API usage dwarfs the infrastructure. The levers that matter are camera
frame rate and session length, not instance size.
