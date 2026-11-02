import { makeFeatures, predict, type ErrorSample, type ModelContext, type Observation, type PredictionResult } from "./inference";
import { trainLocalFromExamples, type LearnedModel, type TrainingExample } from "./trainer";

interface Env {
  DB: D1Database;
  LOCATION_ID: string;
  TIMEZONE: string;
  LATITUDE: string;
  LONGITUDE: string;
  SYNC_KEY?: string;
}
type DbRow = Record<string, unknown>;

const HOURLY_FIELDS = [
  "temperature_2m", "relative_humidity_2m", "dew_point_2m", "precipitation", "rain", "snowfall",
  "weather_code", "cloud_cover", "cloud_cover_low", "cloud_cover_mid", "cloud_cover_high",
  "pressure_msl", "wind_speed_10m", "wind_gusts_10m", "shortwave_radiation"
];
const MODEL_VERSION = "templearn-cloud-62-v2";
const HOUR = 3600;
const MAX_EXAMPLES = 4000;

function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store",
      "access-control-allow-origin": "*",
      "access-control-allow-methods": "GET,POST,OPTIONS",
      "access-control-allow-headers": "content-type,x-templearn-key"
    }
  });
}
function authorized(request: Request, env: Env): boolean {
  return !!env.SYNC_KEY && request.headers.get("x-templearn-key") === env.SYNC_KEY;
}
function iso(sec: number): string {
  return new Date(sec * 1000).toISOString();
}
function finiteNumber(value: unknown): number | null {
  return typeof value === "number" && Number.isFinite(value) ? value : null;
}
function parseObservation(value: unknown): Observation | null {
  if (!value || typeof value !== "object") return null;
  const row = value as Record<string, unknown>;
  const timestampSec = finiteNumber(row.timestampSec);
  const temperatureC = finiteNumber(row.temperatureC);
  if (timestampSec === null || temperatureC === null) return null;
  const optional = (key: string) => finiteNumber(row[key]);
  return {
    timestampSec, temperatureC,
    dewPointC: optional("dewPointC"), windSpeedKmh: optional("windSpeedKmh"),
    windGustKmh: optional("windGustKmh"), pressureHpa: optional("pressureHpa"),
    humidityPct: optional("humidityPct"), cloudCoverPct: optional("cloudCoverPct"),
    precipitationMm: optional("precipitationMm"), solarRadiationWm2: optional("solarRadiationWm2"),
    cloudLowPct: optional("cloudLowPct"), cloudMidPct: optional("cloudMidPct"),
    cloudHighPct: optional("cloudHighPct"), windDirectionDeg: optional("windDirectionDeg"),
    soilTemperatureC: optional("soilTemperatureC"), soilMoisture01cm: optional("soilMoisture01cm"),
    vaporPressureDeficitKpa: optional("vaporPressureDeficitKpa"),
    surfacePressureHpa: optional("surfacePressureHpa"), rainMm: optional("rainMm"),
    showersMm: optional("showersMm"), snowfallCm: optional("snowfallCm"),
    weatherCode: optional("weatherCode"), visibilityM: optional("visibilityM"),
    evapotranspirationMm: optional("evapotranspirationMm"), et0Mm: optional("et0Mm"),
    uvIndex: optional("uvIndex"), uvIndexClearSky: optional("uvIndexClearSky"),
    wetBulbTemperatureC: optional("wetBulbTemperatureC"), apparentTemperatureC: optional("apparentTemperatureC"),
    solarRadiationInstantWm2: optional("solarRadiationInstantWm2"), snowDepthM: optional("snowDepthM"),
    directRadiationWm2: optional("directRadiationWm2"), diffuseRadiationWm2: optional("diffuseRadiationWm2"),
    boundaryLayerHeightM: optional("boundaryLayerHeightM"), windSpeed100mKmh: optional("windSpeed100mKmh"),
    windDirection100mDeg: optional("windDirection100mDeg"), soilMoisture0To7cm: optional("soilMoisture0To7cm")
  };
}
function parsePayload(value: unknown): Observation | null {
  try { return parseObservation(JSON.parse(String(value))); } catch { return null; }
}
function contextFromProfile(body: any): ModelContext {
  return {
    latitude: finiteNumber(body?.latitude),
    longitude: finiteNumber(body?.longitude),
    elevationM: finiteNumber(body?.elevationM)
  };
}

async function ensureColumn(env: Env, table: string, column: string, declaration: string): Promise<void> {
  const info = await env.DB.prepare(`PRAGMA table_info(${table})`).all<DbRow>();
  if (!(info.results || []).some(row => String(row.name) === column)) {
    await env.DB.prepare(`ALTER TABLE ${table} ADD COLUMN ${column} ${declaration}`).run();
  }
}

async function ensureSchema(env: Env): Promise<void> {
  await env.DB.prepare(`CREATE TABLE IF NOT EXISTS forecast_vintages (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    location_id TEXT NOT NULL,
    issued_at TEXT NOT NULL,
    target_time TEXT NOT NULL,
    source TEXT NOT NULL DEFAULT 'templearn-algorithm',
    payload_json TEXT NOT NULL,
    UNIQUE(location_id, issued_at, target_time)
  )`).run();
  await env.DB.prepare("CREATE INDEX IF NOT EXISTS idx_forecast_location_target ON forecast_vintages(location_id,target_time)").run();
  await env.DB.prepare("CREATE INDEX IF NOT EXISTS idx_forecast_location_issued ON forecast_vintages(location_id,issued_at)").run();

  await env.DB.prepare(`CREATE TABLE IF NOT EXISTS cloud_model_state (
    location_id TEXT PRIMARY KEY NOT NULL,
    weights_json TEXT NOT NULL,
    observations_json TEXT NOT NULL,
    feedback_json TEXT NOT NULL,
    context_json TEXT NOT NULL,
    timezone TEXT NOT NULL,
    model_version TEXT NOT NULL,
    updated_at TEXT NOT NULL
  )`).run();
  for (const [column, declaration] of [
    ["training_examples", "INTEGER NOT NULL DEFAULT 0"],
    ["validation_mae_c", "REAL"],
    ["validation_bias_c", "REAL"],
    ["validation_examples", "INTEGER NOT NULL DEFAULT 0"],
    ["trained_at_sec", "INTEGER NOT NULL DEFAULT 0"],
    ["training_start_sec", "INTEGER NOT NULL DEFAULT 0"],
    ["training_end_sec", "INTEGER NOT NULL DEFAULT 0"],
    ["trained_location_count", "INTEGER NOT NULL DEFAULT 1"],
    ["last_trained_through_sec", "INTEGER NOT NULL DEFAULT 0"]
  ]) await ensureColumn(env, "cloud_model_state", column, declaration);

  await env.DB.prepare(`CREATE TABLE IF NOT EXISTS cloud_observations (
    location_id TEXT NOT NULL,
    timestamp_sec INTEGER NOT NULL,
    temperature_c REAL NOT NULL,
    payload_json TEXT NOT NULL,
    PRIMARY KEY(location_id,timestamp_sec)
  )`).run();
  await env.DB.prepare("CREATE INDEX IF NOT EXISTS idx_cloud_observations_time ON cloud_observations(location_id,timestamp_sec)").run();

  await env.DB.prepare(`CREATE TABLE IF NOT EXISTS cloud_verified_forecasts (
    forecast_id INTEGER PRIMARY KEY,
    location_id TEXT NOT NULL,
    target_timestamp_sec INTEGER NOT NULL,
    predicted_temp_c REAL NOT NULL,
    actual_temp_c REAL NOT NULL,
    local_hour INTEGER NOT NULL,
    temperature_trend_c REAL NOT NULL,
    cloud_cover_pct REAL,
    solar_radiation_wm2 REAL,
    weather_code INTEGER,
    error_c REAL NOT NULL,
    verification_method TEXT NOT NULL DEFAULT 'LIVE',
    verified_at TEXT NOT NULL
  )`).run();
  await ensureColumn(env, "cloud_verified_forecasts", "verification_method", "TEXT NOT NULL DEFAULT 'LIVE'");
  await env.DB.prepare("CREATE INDEX IF NOT EXISTS idx_cloud_verified_time ON cloud_verified_forecasts(location_id,target_timestamp_sec)").run();

  await env.DB.prepare(`CREATE TABLE IF NOT EXISTS cloud_bootstrap_state (
    location_id TEXT PRIMARY KEY NOT NULL,
    complete INTEGER NOT NULL DEFAULT 0,
    expected_rows INTEGER NOT NULL DEFAULT 0,
    uploaded_rows INTEGER NOT NULL DEFAULT 0,
    last_uploaded_timestamp_sec INTEGER NOT NULL DEFAULT 0,
    started_at TEXT NOT NULL,
    completed_at TEXT
  )`).run();
  await env.DB.prepare(`CREATE TABLE IF NOT EXISTS cloud_learning_state (
    location_id TEXT PRIMARY KEY NOT NULL,
    last_replay_target_sec INTEGER NOT NULL DEFAULT 0
  )`).run();
  await env.DB.prepare(`CREATE TABLE IF NOT EXISTS cloud_locations (
    location_id TEXT PRIMARY KEY NOT NULL,
    latitude REAL NOT NULL,
    longitude REAL NOT NULL,
    elevation_m REAL,
    timezone TEXT NOT NULL,
    updated_at TEXT NOT NULL
  )`).run();
}

async function getLocation(env: Env, locationId: string): Promise<{ context: ModelContext; timezone: string }> {
  const row = await env.DB.prepare("SELECT latitude,longitude,elevation_m,timezone FROM cloud_locations WHERE location_id=?")
    .bind(locationId).first<DbRow>();
  if (!row) return {
    context: { latitude: Number(env.LATITUDE), longitude: Number(env.LONGITUDE), elevationM: null },
    timezone: env.TIMEZONE
  };
  return {
    context: {
      latitude: Number(row.latitude), longitude: Number(row.longitude),
      elevationM: row.elevation_m == null ? null : Number(row.elevation_m)
    },
    timezone: String(row.timezone || env.TIMEZONE)
  };
}
async function bootstrapState(env: Env, locationId: string): Promise<DbRow | null> {
  return env.DB.prepare("SELECT complete,expected_rows,uploaded_rows,last_uploaded_timestamp_sec,started_at,completed_at FROM cloud_bootstrap_state WHERE location_id=?")
    .bind(locationId).first<DbRow>();
}

async function insertObservations(env: Env, locationId: string, observations: Observation[]): Promise<number> {
  let inserted = 0;
  const statements = observations.map(row => env.DB.prepare(
    "INSERT OR IGNORE INTO cloud_observations (location_id,timestamp_sec,temperature_c,payload_json) VALUES (?,?,?,?)"
  ).bind(locationId, row.timestampSec, row.temperatureC, JSON.stringify(row)));
  // D1 batches stay deliberately small; this also keeps each transaction safely bounded.
  for (let offset = 0; offset < statements.length; offset += 100) {
    const results = await env.DB.batch(statements.slice(offset, offset + 100));
    for (const result of results) inserted += Number(result.meta?.changes || 0);
  }
  return inserted;
}
function readApiValue(hourly: Record<string, unknown>, key: string, index: number): number | null {
  const values = hourly[key];
  if (!Array.isArray(values)) return null;
  return finiteNumber(values[index]);
}

/**
 * The app's live feed is deliberately the same Open-Meteo Best Match feed:
 * only completed past hours are retained, never forecast-hour temperatures.
 * INSERT OR IGNORE keeps the historical archive's existing overlap untouched.
 */
async function fetchPastObservations(env: Env, locationId: string, issuedSec: number): Promise<{ rows: Observation[]; inserted: number }> {
  const location = await getLocation(env, locationId);
  const params = new URLSearchParams({
    latitude: String(location.context.latitude ?? env.LATITUDE),
    longitude: String(location.context.longitude ?? env.LONGITUDE),
    hourly: HOURLY_FIELDS.join(","),
    timezone: location.timezone,
    past_hours: "96",
    forecast_hours: "0",
    timeformat: "unixtime",
    temperature_unit: "celsius",
    wind_speed_unit: "kmh",
    precipitation_unit: "mm",
    models: "best_match"
  });
  const response = await fetch("https://api.open-meteo.com/v1/forecast?" + params.toString(), {
    headers: { accept: "application/json", "user-agent": "TempLearn-Cloud/2.0" }
  });
  if (!response.ok) throw new Error("Open-Meteo 96-hour past-input request failed: HTTP " + response.status);
  const payload = await response.json() as { hourly?: Record<string, unknown> };
  const hourly = payload.hourly;
  if (!hourly || !Array.isArray(hourly.time)) throw new Error("Open-Meteo response has no hourly history");
  const times = hourly.time as unknown[];
  const completedBeforeSec = Math.floor(issuedSec / HOUR) * HOUR;
  const rows: Observation[] = [];
  for (let index = 0; index < times.length; index++) {
    const timestampSec = finiteNumber(times[index]);
    const temperatureC = readApiValue(hourly, "temperature_2m", index);
    if (timestampSec === null || timestampSec >= completedBeforeSec || temperatureC === null) continue;
    rows.push({
      timestampSec, temperatureC,
      humidityPct: readApiValue(hourly, "relative_humidity_2m", index),
      dewPointC: readApiValue(hourly, "dew_point_2m", index),
      precipitationMm: readApiValue(hourly, "precipitation", index),
      rainMm: readApiValue(hourly, "rain", index),
      snowfallCm: readApiValue(hourly, "snowfall", index),
      weatherCode: readApiValue(hourly, "weather_code", index),
      cloudCoverPct: readApiValue(hourly, "cloud_cover", index),
      cloudLowPct: readApiValue(hourly, "cloud_cover_low", index),
      cloudMidPct: readApiValue(hourly, "cloud_cover_mid", index),
      cloudHighPct: readApiValue(hourly, "cloud_cover_high", index),
      pressureHpa: readApiValue(hourly, "pressure_msl", index),
      windSpeedKmh: readApiValue(hourly, "wind_speed_10m", index),
      windGustKmh: readApiValue(hourly, "wind_gusts_10m", index),
      solarRadiationWm2: readApiValue(hourly, "shortwave_radiation", index)
    });
  }
  rows.sort((a, b) => a.timestampSec - b.timestampSec);
  if (rows.length < 3) throw new Error("Fewer than three completed hourly readings were returned by Open-Meteo");
  const inserted = await insertObservations(env, locationId, rows);
  return { rows, inserted };
}

async function loadTrainingExamples(
  env: Env,
  locationId: string,
  timezone: string,
  context: ModelContext,
  beforeTimestampSec?: number
): Promise<{ examples: TrainingExample[]; startSec: number; endSec: number; observationCount: number }> {
  // Match Android's getAllReadingsByLocation() sampling: retain four-row windows
  // at a stride of max(4, ceil(observationCount / 4000)), then make one target
  // example from each four-consecutive-hour window.
  const base = await env.DB.prepare(
    "SELECT COUNT(*) AS count,MIN(timestamp_sec) AS first_ts,MAX(timestamp_sec) AS last_ts FROM cloud_observations WHERE location_id=?"
  ).bind(locationId).first<DbRow>();
  const observationCount = Number(base?.count || 0);
  if (observationCount < 4) return { examples: [], startSec: Number(base?.first_ts || 0), endSec: Number(base?.last_ts || 0), observationCount };
  const params: unknown[] = beforeTimestampSec == null
    ? [locationId]
    : [locationId, beforeTimestampSec];
  const cutoffSql = beforeTimestampSec == null ? "" : " AND timestamp_sec < ?";
  const sql = `
    WITH ranked AS (
      SELECT timestamp_sec,payload_json,
        ROW_NUMBER() OVER (ORDER BY timestamp_sec ASC) AS row_num
      FROM cloud_observations
      WHERE location_id=?${cutoffSql}
    ),
    sequenced AS (
      SELECT timestamp_sec,payload_json,row_num,
        LAG(timestamp_sec,1) OVER (ORDER BY row_num) AS p1_ts,
        LAG(payload_json,1) OVER (ORDER BY row_num) AS p1_json,
        LAG(timestamp_sec,2) OVER (ORDER BY row_num) AS p2_ts,
        LAG(payload_json,2) OVER (ORDER BY row_num) AS p2_json,
        LAG(timestamp_sec,3) OVER (ORDER BY row_num) AS p3_ts,
        LAG(payload_json,3) OVER (ORDER BY row_num) AS p3_json
      FROM ranked
    )
    SELECT timestamp_sec,payload_json,p1_json,p2_json,p3_json
    FROM sequenced
    WHERE ((row_num - 1) % MAX(4,CAST((? + 3999) / 4000 AS INTEGER))) = 3
      AND timestamp_sec-p1_ts=3600 AND p1_ts-p2_ts=3600 AND p2_ts-p3_ts=3600
    ORDER BY timestamp_sec ASC
  `;
  const queryParams = beforeTimestampSec == null
    ? [locationId, observationCount]
    : [locationId, beforeTimestampSec, observationCount];
  const result = await env.DB.prepare(sql).bind(...queryParams).all<DbRow>();
  const examples: TrainingExample[] = [];
  for (const row of result.results || []) {
    const target = parsePayload(row.payload_json);
    const p1 = parsePayload(row.p1_json);
    const p2 = parsePayload(row.p2_json);
    const p3 = parsePayload(row.p3_json);
    if (!target || !p1 || !p2 || !p3) continue;
    examples.push({
      features: makeFeatures(target.timestampSec, [p1, p2, p3], timezone, context),
      targetNormalized: (target.temperatureC - 20) / 10,
      actualC: target.temperatureC
    });
  }
  const startSec = beforeTimestampSec == null
    ? Number(base?.first_ts || 0)
    : Number((await env.DB.prepare("SELECT MIN(timestamp_sec) AS value FROM cloud_observations WHERE location_id=? AND timestamp_sec<?")
      .bind(locationId, beforeTimestampSec).first<DbRow>())?.value || 0);
  const endSec = beforeTimestampSec == null
    ? Number(base?.last_ts || 0)
    : Number((await env.DB.prepare("SELECT MAX(timestamp_sec) AS value FROM cloud_observations WHERE location_id=? AND timestamp_sec<?")
      .bind(locationId, beforeTimestampSec).first<DbRow>())?.value || 0);
  return { examples, startSec, endSec, observationCount };
}

async function loadModel(env: Env, locationId: string): Promise<{ model: LearnedModel; context: ModelContext; timezone: string; modelVersion: string } | null> {
  const row = await env.DB.prepare("SELECT * FROM cloud_model_state WHERE location_id=?").bind(locationId).first<DbRow>();
  if (!row) return null;
  let weights: number[];
  try { weights = JSON.parse(String(row.weights_json)); } catch { return null; }
  if (!Array.isArray(weights) || weights.length !== 62 || !weights.every(x => typeof x === "number" && Number.isFinite(x))) return null;
  let context: ModelContext = {};
  try { context = JSON.parse(String(row.context_json)) as ModelContext; } catch { /* keep empty context */ }
  return {
    model: {
      weights,
      trainingExamples: Number(row.training_examples || 0),
      validationMaeC: row.validation_mae_c == null ? Number.NaN : Number(row.validation_mae_c),
      validationBiasC: row.validation_bias_c == null ? Number.NaN : Number(row.validation_bias_c),
      validationExamples: Number(row.validation_examples || 0),
      trainedAtSec: Number(row.trained_at_sec || 0),
      trainingStartSec: Number(row.training_start_sec || 0),
      trainingEndSec: Number(row.training_end_sec || 0),
      trainedLocationCount: Number(row.trained_location_count || 1),
      trainingPriorWeights: null
    },
    context,
    timezone: String(row.timezone || "UTC"),
    modelVersion: String(row.model_version || MODEL_VERSION)
  };
}

async function trainModel(env: Env, locationId: string, beforeTimestampSec?: number): Promise<LearnedModel | null> {
  const location = await getLocation(env, locationId);
  const training = await loadTrainingExamples(env, locationId, location.timezone, location.context, beforeTimestampSec);
  const model = trainLocalFromExamples(
    training.examples, training.startSec, training.endSec, null, Math.floor(Date.now() / 1000)
  );
  if (!model) return null;
  if (beforeTimestampSec == null) {
    const latest = await latestObservations(env, locationId, 3);
    await env.DB.prepare(`INSERT INTO cloud_model_state
      (location_id,weights_json,observations_json,feedback_json,context_json,timezone,model_version,updated_at,
       training_examples,validation_mae_c,validation_bias_c,validation_examples,trained_at_sec,
       training_start_sec,training_end_sec,trained_location_count,last_trained_through_sec)
      VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
      ON CONFLICT(location_id) DO UPDATE SET
       weights_json=excluded.weights_json,observations_json=excluded.observations_json,feedback_json='[]',
       context_json=excluded.context_json,timezone=excluded.timezone,model_version=excluded.model_version,
       updated_at=excluded.updated_at,training_examples=excluded.training_examples,
       validation_mae_c=excluded.validation_mae_c,validation_bias_c=excluded.validation_bias_c,
       validation_examples=excluded.validation_examples,trained_at_sec=excluded.trained_at_sec,
       training_start_sec=excluded.training_start_sec,training_end_sec=excluded.training_end_sec,
       trained_location_count=excluded.trained_location_count,last_trained_through_sec=excluded.last_trained_through_sec`)
      .bind(locationId,JSON.stringify(model.weights),JSON.stringify(latest),JSON.stringify([]),JSON.stringify(location.context),
        location.timezone,MODEL_VERSION,new Date().toISOString(),model.trainingExamples,model.validationMaeC,
        model.validationBiasC,model.validationExamples,model.trainedAtSec,model.trainingStartSec,
        model.trainingEndSec,model.trainedLocationCount,training.endSec).run();
  }
  return model;
}

async function latestObservations(env: Env, locationId: string, limit: number): Promise<Observation[]> {
  const rows = await env.DB.prepare(
    "SELECT payload_json FROM cloud_observations WHERE location_id=? ORDER BY timestamp_sec DESC LIMIT ?"
  ).bind(locationId, limit).all<DbRow>();
  return (rows.results || []).map(row => parsePayload(row.payload_json)).filter((x): x is Observation => x !== null).reverse();
}

async function loadCloudFeedback(env: Env, locationId: string, beforeTimestampSec?: number): Promise<ErrorSample[]> {
  const query = beforeTimestampSec == null
    ? env.DB.prepare(`SELECT target_timestamp_sec,predicted_temp_c,actual_temp_c,local_hour,temperature_trend_c,
        cloud_cover_pct,solar_radiation_wm2,weather_code FROM cloud_verified_forecasts
        WHERE location_id=? ORDER BY target_timestamp_sec DESC LIMIT 5000`).bind(locationId)
    : env.DB.prepare(`SELECT target_timestamp_sec,predicted_temp_c,actual_temp_c,local_hour,temperature_trend_c,
        cloud_cover_pct,solar_radiation_wm2,weather_code FROM cloud_verified_forecasts
        WHERE location_id=? AND target_timestamp_sec<? ORDER BY target_timestamp_sec DESC LIMIT 5000`).bind(locationId,beforeTimestampSec);
  const result = await query.all<DbRow>();
  return (result.results || []).map(row => ({
    targetTimestampSec: Number(row.target_timestamp_sec),
    predictedTempC: Number(row.predicted_temp_c),
    actualTempC: Number(row.actual_temp_c),
    localHour: Number(row.local_hour),
    temperatureTrendC: Number(row.temperature_trend_c),
    cloudCoverPct: row.cloud_cover_pct == null ? null : Number(row.cloud_cover_pct),
    solarRadiationWm2: row.solar_radiation_wm2 == null ? null : Number(row.solar_radiation_wm2),
    weatherCode: row.weather_code == null ? null : Number(row.weather_code)
  }));
}

async function recordVerifiedForecast(
  env: Env, locationId: string, forecastId: number, prediction: PredictionResult,
  actualTempC: number, verificationMethod: "LIVE" | "WALK_FORWARD_REPLAY", verifiedAtSec: number
): Promise<void> {
  const error = actualTempC - prediction.predictedTempC;
  await env.DB.prepare(`INSERT OR IGNORE INTO cloud_verified_forecasts
    (forecast_id,location_id,target_timestamp_sec,predicted_temp_c,actual_temp_c,local_hour,
     temperature_trend_c,cloud_cover_pct,solar_radiation_wm2,weather_code,error_c,verification_method,verified_at)
    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)`)
    .bind(forecastId,locationId,prediction.targetTimestampSec,prediction.predictedTempC,actualTempC,
      prediction.localHour,prediction.temperatureTrendC,prediction.cloudCoverPct,prediction.solarRadiationWm2,
      prediction.weatherCode,error,verificationMethod,iso(verifiedAtSec)).run();
}

async function reconcileLiveForecasts(env: Env, locationId: string, observations: Observation[], verifiedAtSec: number): Promise<number> {
  let verified = 0;
  for (const actual of observations) {
    const rows = await env.DB.prepare(`SELECT id,payload_json FROM forecast_vintages
      WHERE location_id=? AND source='templearn-algorithm' AND target_time=?`)
      .bind(locationId,iso(actual.timestampSec)).all<DbRow>();
    for (const row of rows.results || []) {
      let prediction: PredictionResult;
      try { prediction = JSON.parse(String(row.payload_json)) as PredictionResult; } catch { continue; }
      if (!Number.isFinite(prediction.predictedTempC)) continue;
      await recordVerifiedForecast(env,locationId,Number(row.id),prediction,actual.temperatureC,"LIVE",verifiedAtSec);
      verified++;
    }
  }
  return verified;
}

async function runWalkForwardReplay(env: Env, locationId: string, nowSec: number): Promise<number> {
  const cutoff = nowSec - 24 * HOUR;
  const replayModel = await trainModel(env,locationId,cutoff);
  if (!replayModel) return 0;
  const location = await getLocation(env,locationId);
  const historyRows = await env.DB.prepare(`SELECT payload_json FROM cloud_observations
    WHERE location_id=? AND timestamp_sec<? ORDER BY timestamp_sec DESC LIMIT 72`)
    .bind(locationId,cutoff).all<DbRow>();
  const history: Observation[] = (historyRows.results || [])
    .map(row => parsePayload(row.payload_json)).filter((x): x is Observation => x !== null).reverse();
  const targetRows = await env.DB.prepare(`SELECT timestamp_sec,payload_json FROM cloud_observations
    WHERE location_id=? AND timestamp_sec>=? AND timestamp_sec<? ORDER BY timestamp_sec ASC`)
    .bind(locationId,cutoff,Math.floor(nowSec/HOUR)*HOUR).all<DbRow>();
  const replayState = await env.DB.prepare("SELECT last_replay_target_sec FROM cloud_learning_state WHERE location_id=?")
    .bind(locationId).first<DbRow>();
  let lastReplay = Number(replayState?.last_replay_target_sec || 0);
  let created = 0;
  let feedback = await loadCloudFeedback(env,locationId);
  for (const row of targetRows.results || []) {
    const target = parsePayload(row.payload_json);
    if (!target || target.timestampSec <= lastReplay) continue;
    const recent = history.slice(-3);
    if (recent.length === 3 && recent[1].timestampSec-recent[0].timestampSec===HOUR &&
        recent[2].timestampSec-recent[1].timestampSec===HOUR &&
        target.timestampSec-recent[2].timestampSec===HOUR) {
      const prediction = predict({
        weights: replayModel.weights, observations: recent, feedback,
        targetTimestampSec: target.timestampSec, timezone: location.timezone,
        context: location.context, predictionMadeAtSec: recent[2].timestampSec,
        modelVersion: MODEL_VERSION + "-walk-forward"
      });
      const issued = iso(prediction.predictionMadeAtSec);
      const targetTime = iso(prediction.targetTimestampSec);
      await env.DB.prepare(`INSERT OR IGNORE INTO forecast_vintages
        (location_id,issued_at,target_time,source,payload_json) VALUES (?,?,?,'templearn-walk-forward-replay',?)`)
        .bind(locationId,issued,targetTime,JSON.stringify(prediction)).run();
      const vintage = await env.DB.prepare(`SELECT id FROM forecast_vintages
        WHERE location_id=? AND issued_at=? AND target_time=?`)
        .bind(locationId,issued,targetTime).first<DbRow>();
      if (vintage) {
        await recordVerifiedForecast(env,locationId,Number(vintage.id),prediction,target.temperatureC,"WALK_FORWARD_REPLAY",nowSec);
        feedback.unshift({
          targetTimestampSec: prediction.targetTimestampSec,
          predictedTempC: prediction.predictedTempC, actualTempC: target.temperatureC,
          localHour: prediction.localHour, temperatureTrendC: prediction.temperatureTrendC,
          cloudCoverPct: prediction.cloudCoverPct, solarRadiationWm2: prediction.solarRadiationWm2,
          weatherCode: prediction.weatherCode
        });
        feedback = feedback.slice(0,5000);
        created++;
      }
    }
    history.push(target);
    if (history.length > 72) history.shift();
    lastReplay = target.timestampSec;
  }
  await env.DB.prepare(`INSERT INTO cloud_learning_state(location_id,last_replay_target_sec) VALUES (?,?)
    ON CONFLICT(location_id) DO UPDATE SET last_replay_target_sec=excluded.last_replay_target_sec`)
    .bind(locationId,lastReplay).run();
  return created;
}

async function generateForecast(env: Env, locationId: string, nowSec: number): Promise<PredictionResult | null> {
  const state = await loadModel(env,locationId);
  if (!state) return null;
  const observations = await latestObservations(env,locationId,3);
  if (observations.length !== 3 ||
      observations[1].timestampSec-observations[0].timestampSec!==HOUR ||
      observations[2].timestampSec-observations[1].timestampSec!==HOUR) return null;
  // Mount Boyce/Sydney uses an exact whole-hour UTC offset, so the next local hour
  // is also the next UTC hour. No provider forecast temperature is used.
  const targetSec = (Math.floor(nowSec / HOUR) + 1) * HOUR;
  const feedback = await loadCloudFeedback(env,locationId);
  const prediction = predict({
    weights: state.model.weights, observations, feedback,
    targetTimestampSec: targetSec, timezone: state.timezone,
    context: state.context, predictionMadeAtSec: nowSec, modelVersion: state.modelVersion
  });
  await env.DB.prepare(`INSERT OR IGNORE INTO forecast_vintages
    (location_id,issued_at,target_time,source,payload_json)
    VALUES (?,?,?,'templearn-algorithm',?)`)
    .bind(locationId,iso(nowSec),iso(targetSec),JSON.stringify(prediction)).run();
  return prediction;
}

async function syncLocation(env: Env, locationId: string, nowMs = Date.now()): Promise<Record<string, unknown>> {
  const bootstrap = await bootstrapState(env,locationId);
  if (!bootstrap || Number(bootstrap.complete) !== 1) {
    return { ok: false, location_id: locationId, reason: "Initial historical upload is not complete" };
  }
  const nowSec = Math.floor(nowMs / 1000);
  const fetched = await fetchPastObservations(env,locationId,nowSec);
  const liveVerified = await reconcileLiveForecasts(env,locationId,fetched.rows,nowSec);
  const existingModel = await loadModel(env,locationId);
  let trained = existingModel?.model || null;
  if (!trained || fetched.inserted > 0) trained = await trainModel(env,locationId);
  const replayed = fetched.inserted > 0 || !existingModel
    ? await runWalkForwardReplay(env,locationId,nowSec) : 0;
  const prediction = await generateForecast(env,locationId,nowSec);
  const counts = await getCounts(env,locationId);
  return {
    ok: !!prediction,
    location_id: locationId,
    newly_imported_96h_rows: fetched.inserted,
    live_forecasts_verified: liveVerified,
    walk_forward_checks_added: replayed,
    model_ready: !!trained,
    training_examples: trained?.trainingExamples || 0,
    prediction,
    counts,
    input_source: "Open-Meteo Best Match completed past hours only"
  };
}

async function getCounts(env: Env, locationId: string): Promise<Record<string, number>> {
  const observations = await env.DB.prepare("SELECT COUNT(*) AS count FROM cloud_observations WHERE location_id=?").bind(locationId).first<DbRow>();
  const live = await env.DB.prepare("SELECT COUNT(*) AS count FROM cloud_verified_forecasts WHERE location_id=? AND verification_method='LIVE'").bind(locationId).first<DbRow>();
  const replay = await env.DB.prepare("SELECT COUNT(*) AS count FROM cloud_verified_forecasts WHERE location_id=? AND verification_method='WALK_FORWARD_REPLAY'").bind(locationId).first<DbRow>();
  const pending = await env.DB.prepare(`SELECT COUNT(*) AS count FROM forecast_vintages f
    LEFT JOIN cloud_verified_forecasts v ON v.forecast_id=f.id
    WHERE f.location_id=? AND f.source='templearn-algorithm' AND v.forecast_id IS NULL`).bind(locationId).first<DbRow>();
  return {
    storedObservations: Number(observations?.count || 0),
    liveVerified: Number(live?.count || 0),
    walkForwardVerified: Number(replay?.count || 0),
    pendingForecasts: Number(pending?.count || 0)
  };
}

async function dashboard(env: Env, locationId: string): Promise<Record<string, unknown>> {
  const state = await loadModel(env,locationId);
  const bootstrap = await bootstrapState(env,locationId);
  const observations = await latestObservations(env,locationId,72);
  const latestForecastRow = await env.DB.prepare(`SELECT issued_at,target_time,payload_json FROM forecast_vintages
    WHERE location_id=? AND source='templearn-algorithm' ORDER BY issued_at DESC LIMIT 1`)
    .bind(locationId).first<DbRow>();
  const latestForecast = latestForecastRow ? {
    issuedAt: String(latestForecastRow.issued_at),
    targetTime: String(latestForecastRow.target_time),
    prediction: JSON.parse(String(latestForecastRow.payload_json))
  } : null;
  return {
    ok: true,
    locationId,
    bootstrap: {
      complete: Number(bootstrap?.complete || 0) === 1,
      expectedRows: Number(bootstrap?.expected_rows || 0),
      uploadedRows: Number(bootstrap?.uploaded_rows || 0),
      lastUploadedTimestampSec: Number(bootstrap?.last_uploaded_timestamp_sec || 0)
    },
    model: state ? {
      weights: state.model.weights,
      trainingExamples: state.model.trainingExamples,
      validationMaeC: state.model.validationMaeC,
      validationBiasC: state.model.validationBiasC,
      validationExamples: state.model.validationExamples,
      trainedAtSec: state.model.trainedAtSec,
      trainingStartSec: state.model.trainingStartSec,
      trainingEndSec: state.model.trainingEndSec,
      trainedLocationCount: state.model.trainedLocationCount,
      modelVersion: state.modelVersion
    } : null,
    latestForecast,
    observations,
    counts: await getCounts(env,locationId)
  };
}

async function handleRequest(request: Request, env: Env): Promise<Response> {
  const url = new URL(request.url);
  if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: {
    "access-control-allow-origin": "*", "access-control-allow-methods": "GET,POST,OPTIONS",
    "access-control-allow-headers": "content-type,x-templearn-key"
  }});
  if (url.pathname === "/health" && request.method === "GET") {
    return json({
      ok: true, service: "templearn-cloud",
      engine: "cloud-authoritative ridge-regression + online error correction + walk-forward replay",
      featureCount: 62, androidRole: "user-interface-and-cache",
      recentInputHours: 96, futureProviderTemperatureInputs: false
    });
  }
  if (url.pathname === "/v1/forecasts" && request.method === "GET") {
    await ensureSchema(env);
    const locationId = url.searchParams.get("locationId") || env.LOCATION_ID;
    const from = url.searchParams.get("from"), to = url.searchParams.get("to");
    if (!from || !to || !Number.isFinite(Date.parse(from)) || !Number.isFinite(Date.parse(to))) {
      return json({ error: "Valid from and to ISO dates are required" },400);
    }
    const rows = await env.DB.prepare(`SELECT location_id,issued_at,target_time,source,payload_json
      FROM forecast_vintages WHERE location_id=? AND target_time>=? AND target_time<=?
      ORDER BY issued_at ASC,target_time ASC LIMIT 2000`)
      .bind(locationId,new Date(from).toISOString(),new Date(to).toISOString()).all<DbRow>();
    return json({ locationId, forecasts: (rows.results || []).map(row => ({
      locationId: row.location_id, issuedAt: row.issued_at, targetTime: row.target_time,
      source: row.source, prediction: JSON.parse(String(row.payload_json))
    })) });
  }
  if (url.pathname.startsWith("/v1/") && !authorized(request,env)) {
    return json({ error: "Unauthorized; configure the SYNC_KEY Worker secret" },401);
  }
  if (url.pathname === "/v1/bootstrap/status" && request.method === "GET") {
    await ensureSchema(env);
    const locationId = url.searchParams.get("locationId") || env.LOCATION_ID;
    const state = await bootstrapState(env,locationId);
    const count = await env.DB.prepare("SELECT COUNT(*) AS count FROM cloud_observations WHERE location_id=?")
      .bind(locationId).first<DbRow>();
    return json({
      ok: true, locationId,
      complete: Number(state?.complete || 0) === 1,
      expectedRows: Number(state?.expected_rows || 0),
      uploadedRows: Number(state?.uploaded_rows || 0),
      lastUploadedTimestampSec: Number(state?.last_uploaded_timestamp_sec || 0),
      cloudObservationCount: Number(count?.count || 0)
    });
  }
  if (url.pathname === "/v1/bootstrap/start" && request.method === "POST") {
    await ensureSchema(env);
    let body: any; try { body = await request.json(); } catch { return json({ error: "Invalid JSON" },400); }
    const locationId = String(body.locationId || env.LOCATION_ID).trim();
    const latitude = finiteNumber(body.latitude), longitude = finiteNumber(body.longitude);
    const timezone = String(body.timezone || env.TIMEZONE);
    if (!locationId || latitude === null || latitude < -90 || latitude > 90 ||
        longitude === null || longitude < -180 || longitude > 180 || !timezone) {
      return json({ error: "A location ID, valid coordinates and IANA timezone are required" },400);
    }
    await env.DB.prepare(`INSERT INTO cloud_locations(location_id,latitude,longitude,elevation_m,timezone,updated_at)
      VALUES(?,?,?,?,?,?) ON CONFLICT(location_id) DO UPDATE SET latitude=excluded.latitude,
      longitude=excluded.longitude,elevation_m=excluded.elevation_m,timezone=excluded.timezone,updated_at=excluded.updated_at`)
      .bind(locationId,latitude,longitude,finiteNumber(body.elevationM),timezone,new Date().toISOString()).run();
    const existing = await bootstrapState(env,locationId);
    if (!existing || Number(existing.complete) !== 1) {
      await env.DB.prepare(`INSERT INTO cloud_bootstrap_state(location_id,complete,expected_rows,uploaded_rows,last_uploaded_timestamp_sec,started_at)
        VALUES(?,0,?,0,0,?) ON CONFLICT(location_id) DO UPDATE SET
        expected_rows=excluded.expected_rows,started_at=excluded.started_at`)
        .bind(locationId,Math.max(0,Math.floor(Number(body.expectedRows || 0))),new Date().toISOString()).run();
    }
    return json({ ok: true, locationId, alreadyComplete: Number(existing?.complete || 0) === 1 });
  }
  if (url.pathname === "/v1/bootstrap/batch" && request.method === "POST") {
    await ensureSchema(env);
    let body: any; try { body = await request.json(); } catch { return json({ error: "Invalid JSON" },400); }
    const locationId = String(body.locationId || env.LOCATION_ID);
    if (!Array.isArray(body.observations) || body.observations.length < 1 || body.observations.length > 1500) {
      return json({ error: "Each history batch must contain 1–1500 observations" },400);
    }
    const state = await bootstrapState(env,locationId);
    if (!state || Number(state.complete) === 1) return json({ error: "Bootstrap is not open for this location" },409);
    const parsed = body.observations.map(parseObservation);
    if (parsed.some(row => row === null)) return json({ error: "Every observation must contain a finite timestamp and temperature" },400);
    const rows = parsed as Observation[];
    if (rows.some((row,i) => i > 0 && row.timestampSec <= rows[i-1].timestampSec)) {
      return json({ error: "History batches must be ordered by increasing timestamp" },400);
    }
    const inserted = await insertObservations(env,locationId,rows);
    const lastTimestamp = rows[rows.length-1].timestampSec;
    await env.DB.prepare(`UPDATE cloud_bootstrap_state SET uploaded_rows=uploaded_rows+?,
      last_uploaded_timestamp_sec=MAX(last_uploaded_timestamp_sec,?) WHERE location_id=?`)
      .bind(inserted,lastTimestamp,locationId).run();
    return json({ ok: true, received: rows.length, inserted, lastUploadedTimestampSec: lastTimestamp });
  }
  if (url.pathname === "/v1/bootstrap/complete" && request.method === "POST") {
    await ensureSchema(env);
    let body: any; try { body = await request.json(); } catch { return json({ error: "Invalid JSON" },400); }
    const locationId = String(body.locationId || env.LOCATION_ID);
    const state = await bootstrapState(env,locationId);
    if (!state) return json({ error: "Bootstrap was not started" },409);
    const count = await env.DB.prepare("SELECT COUNT(*) AS count FROM cloud_observations WHERE location_id=?")
      .bind(locationId).first<DbRow>();
    if (Number(count?.count || 0) < Number(state.expected_rows || 0)) {
      return json({ error: "Historical upload is incomplete", expectedRows: Number(state.expected_rows || 0), cloudRows: Number(count?.count || 0) },409);
    }
    await env.DB.prepare("UPDATE cloud_bootstrap_state SET complete=1,completed_at=? WHERE location_id=?")
      .bind(new Date().toISOString(),locationId).run();
    try { return json(await syncLocation(env,locationId)); }
    catch (error) { return json({ error: error instanceof Error ? error.message : "Cloud training failed" },422); }
  }
  if (url.pathname === "/v1/sync" && request.method === "POST") {
    await ensureSchema(env);
    let body: any = {}; try { body = await request.json(); } catch { /* default location */ }
    const locationId = String(body.locationId || env.LOCATION_ID);
    try { return json(await syncLocation(env,locationId)); }
    catch (error) { return json({ error: error instanceof Error ? error.message : "Cloud sync failed" },422); }
  }
  if (url.pathname === "/v1/dashboard" && request.method === "GET") {
    await ensureSchema(env);
    const locationId = url.searchParams.get("locationId") || env.LOCATION_ID;
    return json(await dashboard(env,locationId));
  }
  if (url.pathname === "/v1/run" && request.method === "POST") {
    await ensureSchema(env);
    let body: any = {}; try { body = await request.json(); } catch { /* default location */ }
    try { return json(await syncLocation(env,String(body.locationId || env.LOCATION_ID))); }
    catch (error) { return json({ error: error instanceof Error ? error.message : "Cloud run failed" },422); }
  }
  return json({ error: "Not found" },404);
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    try {
      return await handleRequest(request,env);
    } catch (error) {
      return json({ error: error instanceof Error ? error.message : "Unhandled cloud error" },500);
    }
  },
  async scheduled(event: ScheduledController, env: Env): Promise<void> {
    await ensureSchema(env);
    const rows = await env.DB.prepare("SELECT location_id FROM cloud_bootstrap_state WHERE complete=1").all<DbRow>();
    for (const row of rows.results || []) {
      try { await syncLocation(env,String(row.location_id),event.scheduledTime); }
      catch { /* One location's transient provider error must not stop other scheduled locations. */ }
    }
  }
};
