# TempLearn Cloud

Cloudflare Worker that runs the **TempLearn model**, rather than storing another provider's temperature forecast.

The Worker ports the Android 62-coefficient inference model and its verified-error correction. It does not call the Open-Meteo forecast endpoint and does not use provider forecast temperatures.

## Current stage

- `src/inference.ts` contains the TypeScript inference and feedback-correction port.
- `POST /v1/state` accepts the model weights, latest three consecutive hourly observations, verified prediction-error samples, timezone and location context.
- `GET /v1/forecasts` reads stored TempLearn predictions.
- The hourly Cron Trigger runs the same inference code and stores a new prediction vintage.
- The Android client uploads the active 62-weight model and verified feedback after a successful local sync. The Worker independently fetches the same Best Match input fields using `past_hours=24&forecast_hours=0`; it never consumes future temperature values. Cloud predictions are reconciled against later completed past-hour inputs and their errors are added to the cloud feedback history.

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

The Android client sends that value in the `x-templearn-key` request header. For the current personal prototype, add the same value as the GitHub Actions secret `TEMPLEARN_CLOUD_SYNC_KEY`. Also add `TEMPLEARN_CLOUD_BASE_URL` as a GitHub Actions secret containing the Worker base URL (use the actual URL shown on the Worker overview). The build injects these values into the APK; do not commit them to the repository. **Note:** a key embedded in an APK can be extracted, so this is a personal-use gate, not production-grade authentication for a publicly distributed app.

## API

- `GET /health`
- `POST /v1/state` — authenticated Android model-state upload (62 weights, recent observations, verified error samples and location metadata).
- `POST /v1/run` — authenticated manual inference run for testing.
- `GET /v1/forecasts?from=2026-11-02T00:00:00Z&to=2026-11-05T00:00:00Z`

The state upload contains these fields: model weights (exactly 62 finite coefficients), the latest three consecutive observations (oldest first), verified prediction-error samples, geographic context, timezone and model version. Android serialisation will be added in the next integration stage.

Each stored forecast includes its issue time, target time, base model output, feedback correction and final predicted temperature. A forecast is not generated if the model state is missing or its latest observations cannot support the target lead.

## Important observation-source limitation

The independent feed is Open-Meteo Best Match past-hour data requested with `past_hours=24` and `forecast_hours=0`, matching the Android app's rolling recent-data request. The returned values are model-derived estimates rather than direct station thermometer readings; the app uses the same source for its recent-hour training and verification. The cloud never reads the provider's future temperature forecast. A cloud prediction is only verified when a later past-hour record contains the exact target timestamp.
