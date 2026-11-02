CREATE TABLE IF NOT EXISTS forecast_vintages (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  location_id TEXT NOT NULL,
  issued_at TEXT NOT NULL,
  target_time TEXT NOT NULL,
  source TEXT NOT NULL DEFAULT 'open-meteo',
  payload_json TEXT NOT NULL,
  UNIQUE(location_id, issued_at, target_time)
);
CREATE INDEX IF NOT EXISTS idx_forecast_location_target
  ON forecast_vintages(location_id, target_time);
CREATE INDEX IF NOT EXISTS idx_forecast_location_issued
  ON forecast_vintages(location_id, issued_at);
