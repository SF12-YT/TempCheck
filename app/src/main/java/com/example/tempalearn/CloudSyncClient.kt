package com.example.tempalearn

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Best-effort upload of the active local model to the TempLearn cloud inference worker. */
object CloudSyncClient {
    fun upload(
        profile: LocationProfile,
        model: LearnedWeatherModel,
        readings: List<HourlyTemperature>,
        feedback: List<PredictionErrorSample>
    ): Boolean {
        val baseUrl = BuildConfig.TEMPLEARN_CLOUD_BASE_URL.trim().trimEnd('/')
        val syncKey = BuildConfig.TEMPLEARN_CLOUD_SYNC_KEY.trim()
        if (baseUrl.isBlank() || syncKey.isBlank()) return false
        require(model.weights.size == WeatherLearner.WEIGHT_COUNT) { "The cloud model must contain 62 weights." }
        val latest = readings.takeLast(3)
        require(latest.size == 3) { "Three consecutive observations are required for cloud sync." }
        require(latest[1].timestampSec - latest[0].timestampSec == 3_600L &&
            latest[2].timestampSec - latest[1].timestampSec == 3_600L) {
            "The latest observations are not three consecutive hours."
        }

        val observationArray = JSONArray().apply { latest.forEach { put(observationJson(it)) } }
        val feedbackArray = JSONArray().apply {
            feedback.asSequence()
                .filter { it.predictedTempC.isFinite() && it.actualTempC.isFinite() && it.temperatureTrendC.isFinite() }
                .sortedByDescending { it.targetTimestampSec }
                .take(5_000)
                .forEach { sample ->
                    put(JSONObject()
                        .put("targetTimestampSec", sample.targetTimestampSec)
                        .put("predictedTempC", sample.predictedTempC)
                        .put("actualTempC", sample.actualTempC)
                        .put("localHour", sample.localHour)
                        .put("temperatureTrendC", sample.temperatureTrendC)
                        .put("cloudCoverPct", sample.cloudCoverPct.jsonValue())
                        .put("solarRadiationWm2", sample.solarRadiationWm2.jsonValue())
                        .put("weatherCode", sample.weatherCode?.let { it }.jsonValue()))
                }
        }
        val context = JSONObject()
            .put("latitude", profile.latitude)
            .put("longitude", profile.longitude)
            .put("elevationM", profile.elevationM.jsonValue())
        val body = JSONObject()
            .put("weights", JSONArray().apply { model.weights.forEach { put(it) } })
            .put("observations", observationArray)
            .put("feedback", feedbackArray)
            .put("context", context)
            .put("timezone", profile.timezoneId)
            .put("modelVersion", "templearn-62-v1")
            .toString()

        val connection = (URL("$baseUrl/v1/state").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 12_000
            readTimeout = 20_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("x-templearn-key", syncKey)
        }
        return try {
            val bytes = body.toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            status in 200..299 && JSONObject(responseText).optBoolean("ok", false)
        } finally {
            connection.disconnect()
        }
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
        put("weatherCode", reading.weatherCode?.let { it }.jsonValue())
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

    private fun Double?.jsonValue(): Any = this?.takeIf { it.isFinite() } ?: JSONObject.NULL
    private fun Int?.jsonValue(): Any = this ?: JSONObject.NULL
}
