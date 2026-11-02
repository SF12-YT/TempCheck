interface Env {
  DB: D1Database;
  LOCATION_ID: string;
  LATITUDE: string;
  LONGITUDE: string;
}

const FIELDS = [
  "temperature_2m", "relative_humidity_2m", "dew_point_2m",
  "precipitation", "rain", "snowfall", "weather_code",
  "cloud_cover", "cloud_cover_low", "cloud_cover_mid", "cloud_cover_high",
  "pressure_msl", "wind_speed_10m", "wind_gusts_10m", "shortwave_radiation"
];

function reply(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store",
      "access-control-allow-origin": "*",
      "access-control-allow-methods": "GET, OPTIONS"
    }
  });
}

async function capture(env: Env): Promise<void> {
  const issued = new Date();
  issued.setUTCMinutes(0, 0, 0);
  const issuedAt = issued.toISOString();
  const endpoint = new URL("https://api.open-meteo.com/v1/forecast");
  endpoint.searchParams.set("latitude", env.LATITUDE);
  endpoint.searchParams.set("longitude", env.LONGITUDE);
  endpoint.searchParams.set("hourly", FIELDS.join(","));
  endpoint.searchParams.set("forecast_days", "3");
  endpoint.searchParams.set("timezone", "UTC");

  const response = await fetch(endpoint);
  if (!response.ok) throw new Error(`Open-Meteo HTTP ${response.status}`);
  const body = await response.json() as {
    hourly?: Record<string, Array<string | number | null>>;
  };
  const times = body.hourly?.time;
  if (!Array.isArray(times)) throw new Error("Missing hourly timestamps");

  const writes: D1PreparedStatement[] = [];
  for (let i = 0; i < times.length; i++) {
    const rawTime = times[i];
    if (typeof rawTime !== "string") continue;
    const target = new Date(rawTime + "Z");
    if (!Number.isFinite(target.getTime()) || target <= issued) continue;

    const values: Record<string, string | number | null> = {};
    for (const field of FIELDS) {
      const series = body.hourly?.[field];
      values[field] = Array.isArray(series) ? (series[i] ?? null) : null;
    }
    writes.push(env.DB.prepare(
      "INSERT OR IGNORE INTO forecast_vintages (location_id, issued_at, target_time, source, payload_json) VALUES (?, ?, ?, 'open-meteo', ?)"
    ).bind(env.LOCATION_ID, issuedAt, target.toISOString(), JSON.stringify(values)));
  }

  for (let i = 0; i < writes.length; i += 50) {
    await env.DB.batch(writes.slice(i, i + 50));
  }
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    if (request.method === "OPTIONS") return reply({});
    if (url.pathname === "/health") {
      return reply({ ok: true, service: "templearn-cloud" });
    }
    if (request.method !== "GET" || url.pathname !== "/v1/forecasts") {
      return reply({ error: "Not found" }, 404);
    }

    const from = url.searchParams.get("from");
    const to = url.searchParams.get("to");
    const limit = Math.min(Math.max(Number(url.searchParams.get("limit") || 500), 1), 2000);
    if (!from || !to || !Number.isFinite(Date.parse(from)) || !Number.isFinite(Date.parse(to))) {
      return reply({ error: "Valid ISO-8601 from and to parameters are required" }, 400);
    }
    if (Date.parse(to) < Date.parse(from) || Date.parse(to) - Date.parse(from) > 8 * 86400000) {
      return reply({ error: "Range must be ordered and at most 8 days" }, 400);
    }

    const rows = await env.DB.prepare(
      "SELECT location_id, issued_at, target_time, source, payload_json FROM forecast_vintages WHERE location_id = ? AND target_time >= ? AND target_time <= ? ORDER BY issued_at ASC, target_time ASC LIMIT ?"
    ).bind(env.LOCATION_ID, new Date(from).toISOString(), new Date(to).toISOString(), limit).all();

    return reply({
      location_id: env.LOCATION_ID,
      forecasts: (rows.results || []).map((row: Record<string, unknown>) => ({
        location_id: row.location_id,
        issued_at: row.issued_at,
        target_time: row.target_time,
        source: row.source,
        values: JSON.parse(String(row.payload_json))
      }))
    });
  },

  async scheduled(_event: ScheduledController, env: Env): Promise<void> {
    await capture(env);
  }
};
