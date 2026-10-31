package com.example.tempalearn

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Hourly weather observations and fitted model weights are kept in this app's private database. */
class TemperatureDatabase(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE readings (
                location_key TEXT NOT NULL,
                timestamp_sec INTEGER NOT NULL,
                temperature_c REAL NOT NULL,
                dew_point_c REAL,
                wind_speed_kmh REAL,
                wind_gust_kmh REAL,
                pressure_hpa REAL,
                humidity_pct REAL,
                cloud_cover_pct REAL,
                precipitation_mm REAL,
                solar_radiation_wm2 REAL,
                cloud_low_pct REAL,
                cloud_mid_pct REAL,
                cloud_high_pct REAL,
                wind_direction_deg REAL,
                soil_temperature_c REAL,
                soil_moisture_0_1cm REAL,
                vapor_pressure_deficit_kpa REAL,
                surface_pressure_hpa REAL,
                rain_mm REAL,
                showers_mm REAL,
                snowfall_cm REAL,
                weather_code INTEGER,
                visibility_m REAL,
                evapotranspiration_mm REAL,
                et0_mm REAL,
                uv_index REAL,
                uv_index_clear_sky REAL,
                wet_bulb_temperature_c REAL,
                apparent_temperature_c REAL,
                shortwave_radiation_instant_wm2 REAL,
                temperature_source TEXT NOT NULL DEFAULT 'BOM',
                PRIMARY KEY(location_key, timestamp_sec)
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX readings_location_time ON readings(location_key, timestamp_sec)")
        createDailyWeatherTable(db)
        createLocationProfilesTable(db)
        createModelStateTable(db)
    }

    private fun createDailyWeatherTable(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS daily_weather (
                location_key TEXT NOT NULL,
                local_date TEXT NOT NULL,
                sunrise_sec INTEGER,
                sunset_sec INTEGER,
                daylight_duration_sec REAL,
                sunshine_duration_sec REAL,
                rain_sum_mm REAL,
                showers_sum_mm REAL,
                precipitation_sum_mm REAL,
                precipitation_hours REAL,
                precipitation_probability_max_pct REAL,
                wind_direction_dominant_deg REAL,
                wind_speed_max_kmh REAL,
                wind_gusts_max_kmh REAL,
                shortwave_radiation_sum REAL,
                et0_sum_mm REAL,
                PRIMARY KEY(location_key, local_date)
            )""".trimIndent()
        )
    }

    private fun createHourlyForecastTable(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS hourly_forecast (
                location_key TEXT NOT NULL,
                timestamp_sec INTEGER NOT NULL,
                fetched_at_sec INTEGER NOT NULL,
                temperature_2m_c REAL,
                dew_point_c REAL,
                relative_humidity_pct REAL,
                rain_mm REAL,
                precipitation_mm REAL,
                showers_mm REAL,
                pressure_msl_hpa REAL,
                weather_code INTEGER,
                surface_pressure_hpa REAL,
                cloud_cover_pct REAL,
                cloud_cover_low_pct REAL,
                cloud_cover_mid_pct REAL,
                cloud_cover_high_pct REAL,
                visibility_m REAL,
                evapotranspiration_mm REAL,
                et0_mm REAL,
                vapor_pressure_deficit_kpa REAL,
                wind_speed_kmh REAL,
                uv_index REAL,
                uv_index_clear_sky REAL,
                wet_bulb_temperature_c REAL,
                apparent_temperature_c REAL,
                shortwave_radiation_wm2 REAL,
                shortwave_radiation_instant_wm2 REAL,
                wind_gusts_kmh REAL,
                wind_direction_deg REAL,
                snowfall_cm REAL,
                PRIMARY KEY(location_key, timestamp_sec)
            )""".trimIndent()
        )
    }

    private fun createLocationProfilesTable(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS location_profiles (
                location_key TEXT PRIMARY KEY NOT NULL,
                geocoding_id INTEGER NOT NULL,
                search_query TEXT NOT NULL,
                display_name TEXT NOT NULL,
                place_name TEXT NOT NULL,
                admin1 TEXT,
                admin2 TEXT,
                country TEXT,
                country_code TEXT,
                latitude REAL NOT NULL,
                longitude REAL NOT NULL,
                elevation_m REAL,
                timezone_id TEXT NOT NULL,
                feature_code TEXT,
                population INTEGER,
                postcodes TEXT,
                resolved_at_sec INTEGER NOT NULL
            )""".trimIndent()
        )
    }

    private fun createModelStateTable(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS model_state (
                location_key TEXT PRIMARY KEY NOT NULL,
                weights TEXT NOT NULL,
                training_examples INTEGER NOT NULL,
                fit_mae_c REAL NOT NULL,
                validation_bias_c REAL NOT NULL DEFAULT 0,
                validation_examples INTEGER NOT NULL DEFAULT 0,
                trained_at_sec INTEGER NOT NULL,
                training_start_sec INTEGER NOT NULL DEFAULT 0,
                training_end_sec INTEGER NOT NULL DEFAULT 0,
                trained_location_count INTEGER NOT NULL DEFAULT 1,
                prior_weights TEXT
            )""".trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE readings ADD COLUMN dew_point_c REAL")
            db.execSQL("ALTER TABLE readings ADD COLUMN wind_speed_kmh REAL")
            db.execSQL("ALTER TABLE readings ADD COLUMN wind_gust_kmh REAL")
            db.execSQL("ALTER TABLE readings ADD COLUMN pressure_hpa REAL")
            db.execSQL("ALTER TABLE readings ADD COLUMN humidity_pct REAL")
            db.execSQL("ALTER TABLE readings ADD COLUMN cloud_cover_pct REAL")
            db.execSQL("ALTER TABLE readings ADD COLUMN precipitation_mm REAL")
            db.execSQL("ALTER TABLE readings ADD COLUMN solar_radiation_wm2 REAL")
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE model_state ADD COLUMN validation_bias_c REAL NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE model_state ADD COLUMN validation_examples INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE readings ADD COLUMN cloud_low_pct REAL")
            db.execSQL("ALTER TABLE readings ADD COLUMN cloud_mid_pct REAL")
            db.execSQL("ALTER TABLE readings ADD COLUMN cloud_high_pct REAL")
            db.execSQL("ALTER TABLE readings ADD COLUMN wind_direction_deg REAL")
            db.execSQL("ALTER TABLE readings ADD COLUMN soil_temperature_c REAL")
            db.execSQL("ALTER TABLE readings ADD COLUMN soil_moisture_0_1cm REAL")
            db.execSQL("ALTER TABLE readings ADD COLUMN vapor_pressure_deficit_kpa REAL")
            db.execSQL("ALTER TABLE readings ADD COLUMN temperature_source TEXT NOT NULL DEFAULT 'Visual Crossing'")
            // Existing temperatures are preserved, but legacy Visual Crossing predictor values are
            // cleared so new training does not silently mix providers for the same weather fields.
            db.execSQL("""UPDATE readings SET dew_point_c=NULL, wind_speed_kmh=NULL, wind_gust_kmh=NULL,
                pressure_hpa=NULL, humidity_pct=NULL, cloud_cover_pct=NULL, precipitation_mm=NULL,
                solar_radiation_wm2=NULL, cloud_low_pct=NULL, cloud_mid_pct=NULL, cloud_high_pct=NULL,
                wind_direction_deg=NULL, soil_temperature_c=NULL, soil_moisture_0_1cm=NULL,
                vapor_pressure_deficit_kpa=NULL""".trimIndent())
        }
        if (oldVersion < 5) {
            // v0.4.0 changes provider ownership: Open-Meteo is now the sole source for predictor
            // fields. Preserve all temperatures, but clear pre-existing predictor values so a
            // historical Visual Crossing value is not mixed with an Open-Meteo value by accident.
            db.execSQL("""UPDATE readings SET dew_point_c=NULL, wind_speed_kmh=NULL, wind_gust_kmh=NULL,
                pressure_hpa=NULL, humidity_pct=NULL, cloud_cover_pct=NULL, precipitation_mm=NULL,
                solar_radiation_wm2=NULL, cloud_low_pct=NULL, cloud_mid_pct=NULL, cloud_high_pct=NULL,
                wind_direction_deg=NULL, soil_temperature_c=NULL, soil_moisture_0_1cm=NULL,
                vapor_pressure_deficit_kpa=NULL""".trimIndent())
        }
        if (oldVersion < 6) {
            db.execSQL("ALTER TABLE readings ADD COLUMN surface_pressure_hpa REAL")
            db.execSQL("ALTER TABLE readings ADD COLUMN rain_mm REAL")
            db.execSQL("ALTER TABLE readings ADD COLUMN showers_mm REAL")
            db.execSQL("ALTER TABLE readings ADD COLUMN snowfall_cm REAL")
            db.execSQL("ALTER TABLE readings ADD COLUMN weather_code INTEGER")
            db.execSQL("ALTER TABLE readings ADD COLUMN visibility_m REAL")
            db.execSQL("ALTER TABLE readings ADD COLUMN evapotranspiration_mm REAL")
            db.execSQL("ALTER TABLE readings ADD COLUMN et0_mm REAL")
            db.execSQL("ALTER TABLE readings ADD COLUMN uv_index REAL")
            db.execSQL("ALTER TABLE readings ADD COLUMN uv_index_clear_sky REAL")
            db.execSQL("ALTER TABLE readings ADD COLUMN wet_bulb_temperature_c REAL")
            db.execSQL("ALTER TABLE readings ADD COLUMN apparent_temperature_c REAL")
            db.execSQL("ALTER TABLE readings ADD COLUMN shortwave_radiation_instant_wm2 REAL")
            createDailyWeatherTable(db)
            createHourlyForecastTable(db)
        }
        if (oldVersion < 8) {
            // v0.6.0 establishes BOM as the exclusive air-temperature target source. Remove
            // historical Open-Meteo/Visual Crossing pseudo-observations and refit from BOM only.
            // This is a deliberate one-time clean-up of mixed-source training targets.
            db.execSQL("DELETE FROM readings WHERE UPPER(TRIM(temperature_source)) <> 'BOM'")
            db.execSQL("UPDATE readings SET temperature_source='BOM' WHERE UPPER(TRIM(temperature_source))='BOM'")
            db.execSQL("DELETE FROM model_state")
            // Forecast temperature is never requested or shown as an alternate air-temperature source.
            db.execSQL("UPDATE hourly_forecast SET temperature_2m_c=NULL, dew_point_c=NULL, wet_bulb_temperature_c=NULL, apparent_temperature_c=NULL")
            db.execSQL("UPDATE readings SET dew_point_c=NULL, wet_bulb_temperature_c=NULL, apparent_temperature_c=NULL")
        }
        if (oldVersion < 9) {
            // v0.6.1 makes historical training rows BOM-only, field by field. Previous builds
            // could fill a missing BOM predictor with Open-Meteo, and the database did not retain
            // per-field provenance. Clear all old predictors (but preserve verified BOM air-
            // temperature targets); the next BOM 72-hour refresh repopulates the fields BOM reports.
            db.execSQL("""UPDATE readings SET
                dew_point_c=NULL, wind_speed_kmh=NULL, wind_gust_kmh=NULL,
                pressure_hpa=NULL, humidity_pct=NULL, cloud_cover_pct=NULL, precipitation_mm=NULL,
                solar_radiation_wm2=NULL, cloud_low_pct=NULL, cloud_mid_pct=NULL, cloud_high_pct=NULL,
                wind_direction_deg=NULL, soil_temperature_c=NULL, soil_moisture_0_1cm=NULL,
                vapor_pressure_deficit_kpa=NULL, surface_pressure_hpa=NULL, rain_mm=NULL,
                showers_mm=NULL, snowfall_cm=NULL, weather_code=NULL, visibility_m=NULL,
                evapotranspiration_mm=NULL, et0_mm=NULL, uv_index=NULL, uv_index_clear_sky=NULL,
                wet_bulb_temperature_c=NULL, apparent_temperature_c=NULL,
                shortwave_radiation_instant_wm2=NULL""".trimIndent())
            // Previous Open-Meteo daily history and forecast cache are cleared before the daily-only policy.
            db.execSQL("DELETE FROM daily_weather")
            db.execSQL("DELETE FROM hourly_forecast")
            db.execSQL("DELETE FROM model_state")
        }
        if (oldVersion < 10) {
            // v0.6.2 switches Open-Meteo to the previous completed day at daily resolution only.
            // Earlier daily rows were forward-looking context, so discard them. Hourly forecast
            // storage is no longer used and is removed from upgraded installations.
            db.execSQL("DELETE FROM daily_weather")
            db.execSQL("DROP TABLE IF EXISTS hourly_forecast")
        }
        if (oldVersion < 11) {
            // v0.7.0 persists canonical geocoded locations and adds geographic model metadata.
            createLocationProfilesTable(db)
            db.execSQL("ALTER TABLE model_state ADD COLUMN training_start_sec INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE model_state ADD COLUMN training_end_sec INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE model_state ADD COLUMN trained_location_count INTEGER NOT NULL DEFAULT 1")
            db.execSQL("ALTER TABLE model_state ADD COLUMN prior_weights TEXT")
            // The feature vector now includes latitude, longitude and elevation. Old 46-weight
            // models cannot be applied to the new 53-feature schema; raw BOM readings are kept.
            db.execSQL("DELETE FROM model_state")
        }
    }

    fun insertReadings(locationKey: String, readings: List<HourlyTemperature>) {
        require(readings.all { it.temperatureSource == "BOM" }) {
            "Only BOM air-temperature observations may be stored as training targets."
        }
        val writable = writableDatabase
        writable.beginTransaction()
        try {
            readings.forEach { reading ->
                val values = ContentValues().apply {
                    put("location_key", locationKey)
                    put("timestamp_sec", reading.timestampSec)
                    put("temperature_c", reading.temperatureC)
                    putNullable("dew_point_c", reading.dewPointC)
                    putNullable("wind_speed_kmh", reading.windSpeedKmh)
                    putNullable("wind_gust_kmh", reading.windGustKmh)
                    putNullable("pressure_hpa", reading.pressureHpa)
                    putNullable("humidity_pct", reading.humidityPct)
                    putNullable("cloud_cover_pct", reading.cloudCoverPct)
                    putNullable("precipitation_mm", reading.precipitationMm)
                    putNullable("solar_radiation_wm2", reading.solarRadiationWm2)
                    putNullable("cloud_low_pct", reading.cloudLowPct)
                    putNullable("cloud_mid_pct", reading.cloudMidPct)
                    putNullable("cloud_high_pct", reading.cloudHighPct)
                    putNullable("wind_direction_deg", reading.windDirectionDeg)
                    putNullable("soil_temperature_c", reading.soilTemperatureC)
                    putNullable("soil_moisture_0_1cm", reading.soilMoisture01cm)
                    putNullable("vapor_pressure_deficit_kpa", reading.vaporPressureDeficitKpa)
                    putNullable("surface_pressure_hpa", reading.surfacePressureHpa)
                    putNullable("rain_mm", reading.rainMm)
                    putNullable("showers_mm", reading.showersMm)
                    putNullable("snowfall_cm", reading.snowfallCm)
                    if (reading.weatherCode == null) putNull("weather_code") else put("weather_code", reading.weatherCode)
                    putNullable("visibility_m", reading.visibilityM)
                    putNullable("evapotranspiration_mm", reading.evapotranspirationMm)
                    putNullable("et0_mm", reading.et0Mm)
                    putNullable("uv_index", reading.uvIndex)
                    putNullable("uv_index_clear_sky", reading.uvIndexClearSky)
                    putNullable("wet_bulb_temperature_c", reading.wetBulbTemperatureC)
                    putNullable("apparent_temperature_c", reading.apparentTemperatureC)
                    putNullable("shortwave_radiation_instant_wm2", reading.solarRadiationInstantWm2)
                    put("temperature_source", reading.temperatureSource)
                }
                writable.insertWithOnConflict("readings", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }
            writable.setTransactionSuccessful()
        } finally {
            writable.endTransaction()
        }
    }

    fun getAllReadings(locationKey: String): List<HourlyTemperature> {
        val result = mutableListOf<HourlyTemperature>()
        readableDatabase.rawQuery(
            """SELECT timestamp_sec, temperature_c, dew_point_c, wind_speed_kmh, wind_gust_kmh,
                pressure_hpa, humidity_pct, cloud_cover_pct, precipitation_mm, solar_radiation_wm2,
                cloud_low_pct, cloud_mid_pct, cloud_high_pct, wind_direction_deg, soil_temperature_c,
                soil_moisture_0_1cm, vapor_pressure_deficit_kpa, temperature_source,
                surface_pressure_hpa, rain_mm, showers_mm, snowfall_cm, weather_code, visibility_m,
                evapotranspiration_mm, et0_mm, uv_index, uv_index_clear_sky, wet_bulb_temperature_c,
                apparent_temperature_c, shortwave_radiation_instant_wm2
                FROM readings WHERE location_key=? ORDER BY timestamp_sec ASC""".trimIndent(),
            arrayOf(locationKey)
        ).use { cursor ->
            while (cursor.moveToNext()) result += cursor.toWeatherReading()
        }
        return result
    }

    fun getAllReadingsByLocation(): Map<String, List<HourlyTemperature>> {
        val result = linkedMapOf<String, MutableList<HourlyTemperature>>()
        readableDatabase.rawQuery(
            """SELECT location_key, timestamp_sec, temperature_c, dew_point_c, wind_speed_kmh,
                wind_gust_kmh, pressure_hpa, humidity_pct, cloud_cover_pct, precipitation_mm, solar_radiation_wm2,
                cloud_low_pct, cloud_mid_pct, cloud_high_pct, wind_direction_deg, soil_temperature_c,
                soil_moisture_0_1cm, vapor_pressure_deficit_kpa, temperature_source,
                surface_pressure_hpa, rain_mm, showers_mm, snowfall_cm, weather_code, visibility_m,
                evapotranspiration_mm, et0_mm, uv_index, uv_index_clear_sky, wet_bulb_temperature_c,
                apparent_temperature_c, shortwave_radiation_instant_wm2
                FROM readings ORDER BY location_key ASC, timestamp_sec ASC""".trimIndent(),
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val key = cursor.getString(0)
                result.getOrPut(key) { mutableListOf() }.add(cursor.toWeatherReading(offset = 1))
            }
        }
        return result
    }

    /**
     * Re-key a legacy query-based location to its stable Open-Meteo/GeoNames place ID.
     * Existing records are moved only when the destination is empty and the source profile,
     * when present, identifies the same geocoded place.
     */
    fun moveLocationKey(fromKey: String, toKey: String, expectedGeocodingId: Long): Boolean {
        if (fromKey == toKey) return true
        val sourceProfile = getLocationProfile(fromKey)
        if (sourceProfile != null && sourceProfile.geocodingId != expectedGeocodingId) return false
        val db = writableDatabase
        db.beginTransaction()
        try {
            val targetTables = listOf("readings", "daily_weather", "model_state", "location_profiles")
            for (table in targetTables) {
                db.rawQuery("SELECT 1 FROM $table WHERE location_key=? LIMIT 1", arrayOf(toKey)).use { cursor ->
                    if (cursor.moveToFirst()) return false
                }
            }
            targetTables.forEach { table ->
                val values = ContentValues().apply { put("location_key", toKey) }
                db.update(table, values, "location_key=?", arrayOf(fromKey))
            }
            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
    }

    fun saveLocationProfile(profile: LocationProfile) {
        require(profile.latitude.isFinite() && profile.latitude in -90.0..90.0)
        require(profile.longitude.isFinite() && profile.longitude in -180.0..180.0)
        val values = ContentValues().apply {
            put("location_key", profile.locationKey)
            put("geocoding_id", profile.geocodingId)
            put("search_query", profile.searchQuery)
            put("display_name", profile.displayName)
            put("place_name", profile.name)
            put("admin1", profile.admin1)
            put("admin2", profile.admin2)
            put("country", profile.country)
            put("country_code", profile.countryCode)
            put("latitude", profile.latitude)
            put("longitude", profile.longitude)
            putNullable("elevation_m", profile.elevationM)
            put("timezone_id", profile.timezoneId)
            put("feature_code", profile.featureCode)
            profile.population?.let { put("population", it) } ?: putNull("population")
            put("postcodes", profile.postcodes.joinToString("|"))
            put("resolved_at_sec", profile.resolvedAtSec)
        }
        writableDatabase.insertWithOnConflict("location_profiles", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getLocationProfile(locationKey: String): LocationProfile? {
        readableDatabase.rawQuery(
            "SELECT geocoding_id, search_query, place_name, admin1, admin2, country, country_code, latitude, longitude, elevation_m, timezone_id, feature_code, population, postcodes, resolved_at_sec FROM location_profiles WHERE location_key=?",
            arrayOf(locationKey)
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return cursor.toLocationProfile(locationKey)
        }
    }

    fun getAllLocationProfiles(): Map<String, LocationProfile> {
        val result = linkedMapOf<String, LocationProfile>()
        readableDatabase.rawQuery(
            "SELECT location_key, geocoding_id, search_query, place_name, admin1, admin2, country, country_code, latitude, longitude, elevation_m, timezone_id, feature_code, population, postcodes, resolved_at_sec FROM location_profiles ORDER BY display_name COLLATE NOCASE",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val key = cursor.getString(0)
                result[key] = cursor.toLocationProfile(key, 1)
            }
        }
        return result
    }

    /** Upsert completed daily aggregates by local date; retain the growing daily history separately. */
    fun insertDailyWeather(locationKey: String, days: List<OpenMeteoClient.DailyVariables>) {
        val writable = writableDatabase
        writable.beginTransaction()
        try {
            days.forEach { day ->
                val values = ContentValues().apply {
                    put("location_key", locationKey)
                    put("local_date", day.localDate)
                    putNullableLong("sunrise_sec", day.sunriseSec)
                    putNullableLong("sunset_sec", day.sunsetSec)
                    putNullable("daylight_duration_sec", day.daylightDurationSec)
                    putNullable("sunshine_duration_sec", day.sunshineDurationSec)
                    putNullable("rain_sum_mm", day.rainSumMm)
                    putNullable("showers_sum_mm", day.showersSumMm)
                    putNullable("precipitation_sum_mm", day.precipitationSumMm)
                    putNullable("precipitation_hours", day.precipitationHours)
                    putNullable("precipitation_probability_max_pct", day.precipitationProbabilityMaxPct)
                    putNullable("wind_direction_dominant_deg", day.windDirectionDominantDeg)
                    putNullable("wind_speed_max_kmh", day.windSpeedMaxKmh)
                    putNullable("wind_gusts_max_kmh", day.windGustsMaxKmh)
                    putNullable("shortwave_radiation_sum", day.shortwaveRadiationSum)
                    putNullable("et0_sum_mm", day.et0SumMm)
                }
                writable.insertWithOnConflict("daily_weather", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }
            writable.setTransactionSuccessful()
        } finally {
            writable.endTransaction()
        }
    }

    fun getDailyWeatherCount(locationKey: String): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM daily_weather WHERE location_key=?", arrayOf(locationKey)).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    fun getSavedCount(locationKey: String): Int {
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM readings WHERE location_key=?",
            arrayOf(locationKey)
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    /** Counts legacy temperature-only rows that should be enriched with weather variables. */
    fun getReadingsMissingCoreWeatherCount(locationKey: String): Int {
        readableDatabase.rawQuery(
            """SELECT COUNT(*) FROM readings WHERE location_key=?
                AND dew_point_c IS NULL AND wind_speed_kmh IS NULL AND pressure_hpa IS NULL AND humidity_pct IS NULL""".trimIndent(),
            arrayOf(locationKey)
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    fun getLocationCount(): Int {
        readableDatabase.rawQuery("SELECT COUNT(DISTINCT location_key) FROM readings", null).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    fun saveModel(locationKey: String, model: LearnedWeatherModel) {
        val values = ContentValues().apply {
            put("location_key", locationKey)
            put("weights", model.weights.joinToString(",") { it.toString() })
            put("training_examples", model.trainingExamples)
            // Column retained for backwards-compatible upgrades; it now stores held-out validation MAE.
            put("fit_mae_c", model.validationMaeC)
            put("validation_bias_c", model.validationBiasC)
            put("validation_examples", model.validationExamples)
            put("trained_at_sec", model.trainedAtSec)
            put("training_start_sec", model.trainingStartSec)
            put("training_end_sec", model.trainingEndSec)
            put("trained_location_count", model.trainedLocationCount)
            if (model.trainingPriorWeights == null) putNull("prior_weights")
            else put("prior_weights", model.trainingPriorWeights.joinToString(","))
        }
        writableDatabase.insertWithOnConflict("model_state", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getModel(locationKey: String): LearnedWeatherModel? {
        readableDatabase.rawQuery(
            "SELECT weights, training_examples, fit_mae_c, validation_bias_c, validation_examples, trained_at_sec, training_start_sec, training_end_sec, trained_location_count, prior_weights FROM model_state WHERE location_key=?",
            arrayOf(locationKey)
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            val weights = cursor.getString(0).split(',').mapNotNull { it.toDoubleOrNull() }
            if (weights.size != WeatherLearner.WEIGHT_COUNT) return null
            return LearnedWeatherModel(
                weights = weights,
                trainingExamples = cursor.getInt(1),
                validationMaeC = cursor.getDouble(2),
                validationBiasC = cursor.getDouble(3),
                validationExamples = cursor.getInt(4),
                trainedAtSec = cursor.getLong(5),
                trainingStartSec = cursor.getLong(6),
                trainingEndSec = cursor.getLong(7),
                trainedLocationCount = cursor.getInt(8),
                trainingPriorWeights = if (cursor.isNull(9)) null else cursor.getString(9)
                    .split(',').mapNotNull { it.toDoubleOrNull() }
                    .takeIf { it.size == WeatherLearner.WEIGHT_COUNT }
            )
        }
    }

    private fun ContentValues.putNullable(name: String, value: Double?) {
        if (value == null || !value.isFinite()) putNull(name) else put(name, value)
    }

    private fun ContentValues.putNullableLong(name: String, value: Long?) {
        if (value == null) putNull(name) else put(name, value)
    }

    private fun android.database.Cursor.toWeatherReading(offset: Int = 0): HourlyTemperature = HourlyTemperature(
        timestampSec = getLong(offset),
        temperatureC = getDouble(offset + 1),
        dewPointC = nullableDouble(offset + 2),
        windSpeedKmh = nullableDouble(offset + 3),
        windGustKmh = nullableDouble(offset + 4),
        pressureHpa = nullableDouble(offset + 5),
        humidityPct = nullableDouble(offset + 6),
        cloudCoverPct = nullableDouble(offset + 7),
        precipitationMm = nullableDouble(offset + 8),
        solarRadiationWm2 = nullableDouble(offset + 9),
        cloudLowPct = nullableDouble(offset + 10),
        cloudMidPct = nullableDouble(offset + 11),
        cloudHighPct = nullableDouble(offset + 12),
        windDirectionDeg = nullableDouble(offset + 13),
        soilTemperatureC = nullableDouble(offset + 14),
        soilMoisture01cm = nullableDouble(offset + 15),
        vaporPressureDeficitKpa = nullableDouble(offset + 16),
        temperatureSource = if (isNull(offset + 17)) "BOM" else getString(offset + 17),
        surfacePressureHpa = nullableDouble(offset + 18),
        rainMm = nullableDouble(offset + 19),
        showersMm = nullableDouble(offset + 20),
        snowfallCm = nullableDouble(offset + 21),
        weatherCode = if (isNull(offset + 22)) null else getInt(offset + 22),
        visibilityM = nullableDouble(offset + 23),
        evapotranspirationMm = nullableDouble(offset + 24),
        et0Mm = nullableDouble(offset + 25),
        uvIndex = nullableDouble(offset + 26),
        uvIndexClearSky = nullableDouble(offset + 27),
        wetBulbTemperatureC = nullableDouble(offset + 28),
        apparentTemperatureC = nullableDouble(offset + 29),
        solarRadiationInstantWm2 = nullableDouble(offset + 30)
    )

    private fun android.database.Cursor.toLocationProfile(locationKey: String, offset: Int = 0): LocationProfile {
        val postcodeText = if (isNull(offset + 13)) "" else getString(offset + 13)
        return LocationProfile(
            locationKey = locationKey,
            geocodingId = getLong(offset),
            searchQuery = getString(offset + 1),
            name = getString(offset + 2),
            admin1 = if (isNull(offset + 3)) null else getString(offset + 3),
            admin2 = if (isNull(offset + 4)) null else getString(offset + 4),
            country = if (isNull(offset + 5)) null else getString(offset + 5),
            countryCode = if (isNull(offset + 6)) null else getString(offset + 6),
            latitude = getDouble(offset + 7),
            longitude = getDouble(offset + 8),
            elevationM = nullableDouble(offset + 9),
            timezoneId = getString(offset + 10),
            featureCode = if (isNull(offset + 11)) null else getString(offset + 11),
            population = if (isNull(offset + 12)) null else getLong(offset + 12),
            postcodes = postcodeText.split('|').filter { it.isNotBlank() },
            resolvedAtSec = getLong(offset + 14)
        )
    }

    private fun android.database.Cursor.nullableDouble(index: Int): Double? =
        if (isNull(index)) null else getDouble(index)

    companion object {
        private const val DATABASE_NAME = "templearn_local.db"
        private const val DATABASE_VERSION = 11
    }
}

data class HourlyTemperature(
    val timestampSec: Long,
    val temperatureC: Double,
    val dewPointC: Double? = null,
    val windSpeedKmh: Double? = null,
    val windGustKmh: Double? = null,
    val pressureHpa: Double? = null,
    val humidityPct: Double? = null,
    val cloudCoverPct: Double? = null,
    val precipitationMm: Double? = null,
    val solarRadiationWm2: Double? = null,
    val cloudLowPct: Double? = null,
    val cloudMidPct: Double? = null,
    val cloudHighPct: Double? = null,
    val windDirectionDeg: Double? = null,
    val soilTemperatureC: Double? = null,
    val soilMoisture01cm: Double? = null,
    val vaporPressureDeficitKpa: Double? = null,
    val temperatureSource: String = "BOM",
    val surfacePressureHpa: Double? = null,
    val rainMm: Double? = null,
    val showersMm: Double? = null,
    val snowfallCm: Double? = null,
    val weatherCode: Int? = null,
    val visibilityM: Double? = null,
    val evapotranspirationMm: Double? = null,
    val et0Mm: Double? = null,
    val uvIndex: Double? = null,
    val uvIndexClearSky: Double? = null,
    val wetBulbTemperatureC: Double? = null,
    val apparentTemperatureC: Double? = null,
    val solarRadiationInstantWm2: Double? = null
)

data class LearnedWeatherModel(
    val weights: List<Double>,
    val trainingExamples: Int,
    val validationMaeC: Double,
    /** Positive bias means predictions tend to be too warm; negative means too cool. */
    val validationBiasC: Double,
    val validationExamples: Int,
    val trainedAtSec: Long,
    val trainingStartSec: Long = 0L,
    val trainingEndSec: Long = 0L,
    val trainedLocationCount: Int = 1,
    val trainingPriorWeights: List<Double>? = null
)
