# TempLearn Cloud

Cloudflare Worker that runs the **TempLearn model**, rather than storing another provider's temperature forecast.

The Worker ports the Android 62-coefficient inference model and its verified-error correction. It does not call the Open-Meteo forecast endpoint and does not use provider forecast temperatures.

## Current stage

- `src/inference.ts` contains the TypeScript inference and feedback-correction port.
- `POST /v1/state` accepts the model weights, latest three consecutive hourly observations, verified prediction-error samples, timezone and location context.
- `GET /v1/forecasts` reads stored TempLearn predictions.
- The hourly Cron Trigger runs the same inference code and stores a new prediction vintage.
- The Android app is **not connected yet**. Until it uploads a trained model and fresh observations, the Worker correctly reports that no model state is available and generates no forecast.

## Cloudflare setup

Keep the Cloudflare project root at `cloud`:

- Build command: `npm install`
- Deploy command: `npx wrangler deploy`
- Preview command: `npx wrangler dev`

The existing D1 database binding is configured in `wrangler.toml`. The Worker creates its `cloud_model_state` table automatically on first sync or scheduled run. The original `forecast_vintages` table remains in use so existing stored data is not dropped; new rows use source `templearn-algorithm`.

## Required secret

Before allowing Android sync, create a Worker secret named `SYNC_KEY` in Cloudflare:

1. Open **Workers & Pages → templearn-cloud → Settings → Variables and Secrets**.
2. Add a secret named `SYNC_KEY` with a long random value.
3. Redeploy if Cloudflare does not automatically activate the new secret.

The Android client will send that value in the `x-templearn-key` request header. Do not put the secret in GitHub or in this repository.

## API

- `GET /health`
- `POST /v1/state` — authenticated model-state upload.
- `POST /v1/run` — authenticated manual inference run for testing.
- `GET /v1/forecasts?from=2026-11-02T00:00:00Z&to=2026-11-05T00:00:00Z`

The state upload contains these fields: model weights (exactly 62 finite coefficients), the latest three consecutive observations (oldest first), verified prediction-error samples, geographic context, timezone and model version. Android serialisation will be added in the next integration stage.

Each stored forecast includes its issue time, target time, base model output, feedback correction and final predicted temperature. A forecast is not generated if the model state is missing or its latest observations cannot support the target lead.

## Important observation-source limitation

The Worker currently uses the latest three observations uploaded by Android. This is enough to validate cloud inference, but it does **not** keep the model supplied with new inputs while the phone is off. The next phase must add an independent feed of completed-hour Open-Meteo input observations (never Open-Meteo's forward temperature forecast), then reconcile cloud predictions with later actual readings. Do not treat stale uploaded readings as fresh forecasts.
