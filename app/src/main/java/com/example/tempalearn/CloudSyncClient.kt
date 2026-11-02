package com.example.tempalearn

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class CloudSyncResult(
    val newlyImportedHours: Int,
    val trainingExamples: Int,
    val sharedTrainingExamples: Int,
    val modelReady: Boolean,
    val bootstrapComplete: Boolean
)

/**
 * Android is a client/cache only. This class sends the existing Open-Meteo archive to
 * the authoritative cloud engine once, resumes interrupted uploads by timestamp, then
 * downloads the current cloud dashboard and its latest observations/model metrics.
 */
object CloudSyncClient {
    private const val PAGE_SIZE = 1000
    private const val CLOUD_DASHBOARD_PREFIX = "cloud_dashboard_"
    private const val REQUEST_TIMEOUT_MS = 20_000
    private const val LONG_READ_TIMEOUT_MS = 180_000

    fun bootstrapAndSync(
        context: Context,
        profile: LocationProfile,
        database: TemperatureDatabase,
        onProgress: (TrainingProgress) -> Unit = {}
    ): CloudSyncResult {
        val baseUrl = BuildConfig.TEMPLEARN_CLOUD_BASE_URL.trim().trimEnd('/')
        val syncKey = BuildConfig.TEMPLEARN_CLOUD_SYNC_KEY.trim()
        require(baseUrl.isNotBlank()) {
            "The TempLearn cloud URL is not configured in the release build."
        }
        require(syncKey.isNotBlank()) {
            "The TempLearn cloud key is not configured in the release build."
        }

        val locationKey = profile.locationKey
        val statusPath = "/v1/bootstrap/status?locationId=" + encode(locationKey)
        var status = request(baseUrl, statusPath, "GET", syncKey, null)
        var syncResponse: JSONObject? = null
        val expectedRows = database.getOpenMeteoReadingCount(locationKey)
        val legacyFeedback = database.getVerifiedPredictionErrors(locationKey)
            .sortedBy { it.targetTimestampSec }
            .takeLast(5_000)

        if (!status.optBoolean("complete", false)) {
            onProgress(TrainingProgress(4, "Preparing the cloud history transfer…"))
            val start = JSONObject()
                .put("locationId", locationKey)
                .put("latitude", profile.latitude)
                .put("longitude", profile.longitude)
                .put("elevationM", profile.elevationM.jsonValue())
                .put("timezone", profile.timezoneId)
                .put("expectedRows", expectedRows)
                .put("expectedFeedbackRows", legacyFeedback.size)
            val startResult = request(baseUrl, "/v1/bootstrap/start", "POST", syncKey, start)
            if (!startResult.optBoolean("alreadyComplete", false)) {
                status = request(baseUrl, statusPath, "GET", syncKey, null)
                var cursor = status.optLong("lastUploadedTimestampSec", 0L)
                var uploaded = status.optLong("uploadedRows", 0L)

                while (true) {
                    val page = database.getReadingsPage(locationKey, cursor, PAGE_SIZE)
                    if (page.isEmpty()) break

                    val batch = JSONArray()
                    page.forEach { batch.put(observationJson(it)) }
                    val response = request(
                        baseUrl,
                        "/v1/bootstrap/batch",
                        "POST",
                        syncKey,
                        JSONObject()
                            .put("locationId", locationKey)
                            .put("observations", batch),
                        retryCount = 3
                    )
                    if (!response.optBoolean("ok", false)) {
                        throw IOException("Cloud history transfer was not accepted.")
                    }

                    cursor = page.last().timestampSec
                    uploaded += response.optInt("inserted", 0)
                    val progress = if (expectedRows > 0) {
                        (8 + (70.0 * cursorProgress(database, locationKey, cursor, expectedRows)).toInt()).coerceIn(8, 78)
                    } else 78
                    onProgress(
                        TrainingProgress(
                            progress,
                            "Transferring historical observations to the cloud… $uploaded / $expectedRows"
                        )
                    )
                }

                status = request(baseUrl, statusPath, "GET", syncKey, null)
                if (!status.optBoolean("feedbackComplete", false)) {
                    var feedbackCursor = status.optLong("lastUploadedFeedbackTimestampSec", 0L)
                    val remainingFeedback = legacyFeedback.filter { it.targetTimestampSec > feedbackCursor }
                    remainingFeedback.chunked(PAGE_SIZE).forEach { batchRows ->
                        val feedbackBatch = JSONArray()
                        batchRows.forEach { sample ->
                            feedbackBatch.put(JSONObject()
                                .put("targetTimestampSec", sample.targetTimestampSec)
                                .put("predictedTempC", sample.predictedTempC)
                                .put("actualTempC", sample.actualTempC)
                                .put("localHour", sample.localHour)
                                .put("temperatureTrendC", sample.temperatureTrendC)
                                .put("cloudCoverPct", sample.cloudCoverPct.jsonValue())
                                .put("solarRadiationWm2", sample.solarRadiationWm2.jsonValue())
                                .put("weatherCode", sample.weatherCode.jsonValue()))
                        }
                        val feedbackResponse = request(
                            baseUrl,
                            "/v1/bootstrap/feedback/batch",
                            "POST",
                            syncKey,
                            JSONObject().put("locationId", locationKey).put("feedback", feedbackBatch),
                            retryCount = 3
                        )
                        if (!feedbackResponse.optBoolean("ok", false)) {
                            throw IOException("The existing verified forecast errors could not be transferred.")
                        }
                        feedbackCursor = batchRows.last().targetTimestampSec
                    }
                    request(
                        baseUrl,
                        "/v1/bootstrap/feedback/complete",
                        "POST",
                        syncKey,
                        JSONObject().put("locationId", locationKey)
                    )
                }

                onProgress(TrainingProgress(80, "Checking the cloud history and importing the missing 96 hours…"))
                syncResponse = request(
                    baseUrl,
                    "/v1/bootstrap/complete",
                    "POST",
                    syncKey,
                    JSONObject().put("locationId", locationKey),
                    retryCount = 2,
                    readTimeoutMs = LONG_READ_TIMEOUT_MS
                )
                if (!syncResponse.optBoolean("ok", false)) {
                    throw IOException(
                        syncResponse.optString(
                            "error",
                            "The cloud history transfer is incomplete. It will resume on the next sync."
                        )
                    )
                }
            }
        }

        if (syncResponse == null) {
            onProgress(TrainingProgress(82, "Running the cloud forecasting and learning engine…"))
            syncResponse = request(
                baseUrl,
                "/v1/sync",
                "POST",
                syncKey,
                JSONObject().put("locationId", locationKey),
                retryCount = 2,
                readTimeoutMs = LONG_READ_TIMEOUT_MS
            )
        }

        onProgress(TrainingProgress(94, "Downloading the cloud model and forecast…"))
        val dashboard = request(
            baseUrl,
            "/v1/dashboard?locationId=" + encode(locationKey),
            "GET",
            syncKey,
            null
        )
        cacheDashboard(context, locationKey, dashboard)
        importDashboard(context, locationKey, database, dashboard)

        val model = dashboard.optJSONObject("model")
        val sharedModel = dashboard.optJSONObject("sharedModel")
        val counts = dashboard.optJSONObject("counts")
        val modelReady = model != null || sharedModel != null
        val trainingExamples = model?.optInt("trainingExamples", 0)
            ?: sharedModel?.optInt("trainingExamples", 0)
            ?: 0
        val sharedExamples = sharedModel?.optInt("trainingExamples", 0) ?: trainingExamples
        val complete = dashboard.optJSONObject("bootstrap")?.optBoolean("complete", false) == true

        onProgress(TrainingProgress(100, "Cloud forecasting and learning are active."))
        return CloudSyncResult(
            newlyImportedHours = syncResponse.optInt("newly_imported_96h_rows", 0),
            trainingExamples = trainingExamples,
            sharedTrainingExamples = sharedExamples,
            modelReady = modelReady,
            bootstrapComplete = complete
        )
    }

    private fun cursorProgress(
        database: TemperatureDatabase,
        locationKey: String,
        cursor: Long,
        expectedRows: Int
    ): Double {
        if (expectedRows <= 0) return 1.0
        // The timestamps span many years, so use the stable local row count for the
        // progress estimate rather than assuming a uniform timestamp distribution.
        val latest = database.getLatestTimestampSec(locationKey) ?: return 0.0
        val firstPage = database.getReadingsPage(locationKey, 0L, 1).firstOrNull()?.timestampSec ?: return 0.0
        if (latest <= firstPage) return 1.0
        return ((cursor - firstPage).toDouble() / (latest - firstPage).toDouble()).coerceIn(0.0, 1.0)
    }

    private fun cacheDashboard(context: Context, locationKey: String, dashboard: JSONObject) {
        context.applicationContext
            .getSharedPreferences(AppPreferences.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(CLOUD_DASHBOARD_PREFIX + locationKey, dashboard.toString())
            .apply()
    }

    private fun importDashboard(
        context: Context,
        locationKey: String,
        database: TemperatureDatabase,
        dashboard: JSONObject
    ) {
        val observations = dashboard.optJSONArray("observations") ?: JSONArray()
        val recent = ArrayList<HourlyTemperature>(observations.length())
        for (index in 0 until observations.length()) {
            val row = observations.optJSONObject(index) ?: continue
            val timestamp = row.optLong("timestampSec", -1L)
            val temperature = nullableDouble(row, "temperatureC") ?: continue
            if (timestamp <= 0L) continue
            recent += HourlyTemperature(
                timestampSec = timestamp,
                temperatureC = temperature,
                dewPointC = nullableDouble(row, "dewPointC"),
                windSpeedKmh = nullableDouble(row, "windSpeedKmh"),
                windGustKmh = nullableDouble(row, "windGustKmh"),
                pressureHpa = nullableDouble(row, "pressureHpa"),
                humidityPct = nullableDouble(row, "humidityPct"),
                cloudCoverPct = nullableDouble(row, "cloudCoverPct"),
                precipitationMm = nullableDouble(row, "precipitationMm"),
                solarRadiationWm2 = nullableDouble(row, "solarRadiationWm2"),
                cloudLowPct = nullableDouble(row, "cloudLowPct"),
                cloudMidPct = nullableDouble(row, "cloudMidPct"),
                cloudHighPct = nullableDouble(row, "cloudHighPct"),
                rainMm = nullableDouble(row, "rainMm"),
                snowfallCm = nullableDouble(row, "snowfallCm"),
                weatherCode = nullableDouble(row, "weatherCode")?.toInt(),
                temperatureSource = OpenMeteoClient.SOURCE
            )
        }
        if (recent.isNotEmpty()) database.insertReadingsIfAbsent(locationKey, recent)

        dashboard.optJSONObject("model")?.let { model ->
            parseModel(model)?.let { database.saveModel(locationKey, it) }
        }
        dashboard.optJSONObject("sharedModel")?.let { model ->
            parseModel(model)?.let { database.saveModel(AppPreferences.SHARED_MODEL_KEY, it) }
        }
    }

    private fun parseModel(json: JSONObject): LearnedWeatherModel? {
        val weightsJson = json.optJSONArray("weights") ?: return null
        if (weightsJson.length() != WeatherLearner.WEIGHT_COUNT) return null
        val weights = (0 until weightsJson.length()).map { weightsJson.optDouble(it, Double.NaN) }
        if (weights.any { !it.isFinite() }) return null
        return LearnedWeatherModel(
            weights = weights,
            trainingExamples = json.optInt("trainingExamples", 0),
            validationMaeC = nullableDouble(json, "validationMaeC") ?: Double.NaN,
            validationBiasC = nullableDouble(json, "validationBiasC") ?: Double.NaN,
            validationExamples = json.optInt("validationExamples", 0),
            trainedAtSec = json.optLong("trainedAtSec", 0L),
            trainingStartSec = json.optLong("trainingStartSec", 0L),
            trainingEndSec = json.optLong("trainingEndSec", 0L),
            trainedLocationCount = json.optInt("trainedLocationCount", 1),
            trainingPriorWeights = null
        )
    }

    private fun observationJson(reading: HourlyTemperature): JSONObject = JSONObject().apply {
        put("timestampSec", reading.timestampSec)
        put("temperatureC", reading.temperatureC)
        put("dewPointC", reading.dewPointC.jsonValue())
        put("windSpeedKmh", reading.windSpeedKmh.jsonValue())
        put("windGustKmh", reading.windGustKmh.jsonValue())
        put("pressureHpa", reading.pressureHpa.jsonValue())
        put("humidityPct", reading.humidityPct.jsonValue())
        put("cloudCoverPct", reading.cloudCoverPct.jsonValue())
        put("precipitationMm", reading.precipitationMm.jsonValue())
        put("solarRadiationWm2", reading.solarRadiationWm2.jsonValue())
        put("cloudLowPct", reading.cloudLowPct.jsonValue())
        put("cloudMidPct", reading.cloudMidPct.jsonValue())
        put("cloudHighPct", reading.cloudHighPct.jsonValue())
        put("windDirectionDeg", reading.windDirectionDeg.jsonValue())
        put("soilTemperatureC", reading.soilTemperatureC.jsonValue())
        put("soilMoisture01cm", reading.soilMoisture01cm.jsonValue())
        put("vaporPressureDeficitKpa", reading.vaporPressureDeficitKpa.jsonValue())
        put("surfacePressureHpa", reading.surfacePressureHpa.jsonValue())
        put("rainMm", reading.rainMm.jsonValue())
        put("showersMm", reading.showersMm.jsonValue())
        put("snowfallCm", reading.snowfallCm.jsonValue())
        put("weatherCode", reading.weatherCode.jsonValue())
        put("visibilityM", reading.visibilityM.jsonValue())
        put("evapotranspirationMm", reading.evapotranspirationMm.jsonValue())
        put("et0Mm", reading.et0Mm.jsonValue())
        put("uvIndex", reading.uvIndex.jsonValue())
        put("uvIndexClearSky", reading.uvIndexClearSky.jsonValue())
        put("wetBulbTemperatureC", reading.wetBulbTemperatureC.jsonValue())
        put("apparentTemperatureC", reading.apparentTemperatureC.jsonValue())
        put("solarRadiationInstantWm2", reading.solarRadiationInstantWm2.jsonValue())
        put("snowDepthM", reading.snowDepthM.jsonValue())
        put("directRadiationWm2", reading.directRadiationWm2.jsonValue())
        put("diffuseRadiationWm2", reading.diffuseRadiationWm2.jsonValue())
        put("boundaryLayerHeightM", reading.boundaryLayerHeightM.jsonValue())
        put("windSpeed100mKmh", reading.windSpeed100mKmh.jsonValue())
        put("windDirection100mDeg", reading.windDirection100mDeg.jsonValue())
        put("soilMoisture0To7cm", reading.soilMoisture0To7cm.jsonValue())
    }

    private fun request(
        baseUrl: String,
        path: String,
        method: String,
        key: String,
        body: JSONObject?,
        retryCount: Int = 3,
        readTimeoutMs: Int = 60_000
    ): JSONObject {
        var lastError: IOException? = null
        for (attempt in 0 until retryCount) {
            val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = REQUEST_TIMEOUT_MS
                readTimeout = readTimeoutMs
                setRequestProperty("Accept", "application/json")
                setRequestProperty("x-templearn-key", key)
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
            }
            try {
                if (body != null) {
                    val bytes = body.toString().toByteArray(Charsets.UTF_8)
                    connection.setFixedLengthStreamingMode(bytes.size)
                    connection.outputStream.use { it.write(bytes) }
                }
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val responseText = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                if (status !in 200..299) {
                    val error = IOException("TempLearn cloud returned HTTP $status: ${responseText.take(350)}")
                    if ((status == 429 || status >= 500) && attempt + 1 < retryCount) {
                        lastError = error
                    } else {
                        throw error
                    }
                } else {
                    return if (responseText.isBlank()) JSONObject() else JSONObject(responseText)
                }
            } catch (error: IOException) {
                if (attempt + 1 >= retryCount) throw error
                lastError = error
            } finally {
                connection.disconnect()
            }
            try {
                Thread.sleep(750L * (attempt + 1))
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("The cloud request was interrupted.", interrupted)
            }
        }
        throw lastError ?: IOException("The TempLearn cloud request failed.")
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    private fun nullableDouble(json: JSONObject, key: String): Double? {
        if (!json.has(key) || json.isNull(key)) return null
        return json.optDouble(key, Double.NaN).takeIf { it.isFinite() }
    }

    private fun Double?.jsonValue(): Any =
        this?.takeIf { it.isFinite() } ?: JSONObject.NULL

    private fun Int?.jsonValue(): Any = this ?: JSONObject.NULL
}
