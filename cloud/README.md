# TempLearn Cloud

Cloudflare Worker for collecting hourly Open-Meteo forecast vintages while the Android device is offline.

## Cloudflare Git integration

Set the project root directory to `cloud` and use:

- Build command: `npm install`
- Deploy command: `npx wrangler deploy`
- Preview command: `npx wrangler dev`

Do not deploy until the D1 database has been created and its ID added to `wrangler.toml`.

## One-time D1 setup

1. In Cloudflare, open **Workers & Pages → D1 SQL Database**.
2. Create a database named `templearn-forecasts`.
3. Copy its database ID into `wrangler.toml`, replacing `REPLACE_WITH_CLOUDFLARE_D1_DATABASE_ID`.
4. Apply the migration from the Cloudflare Worker project using Wrangler:
   `npx wrangler d1 migrations apply templearn-forecasts --remote`
5. Deploy the Worker.

The Cron Trigger runs hourly at minute 0 UTC. Each run stores a separate forecast vintage for future hourly target times; earlier vintages are not overwritten.

## API

- `GET /health`
- `GET /v1/forecasts?from=2026-11-02T00:00:00Z&to=2026-11-05T00:00:00Z`

Initial location: Mount Boyce, NSW (latitude -33.6153, longitude 150.2665). This first phase is a single-location prototype. The Android app has not yet been connected to this endpoint.
