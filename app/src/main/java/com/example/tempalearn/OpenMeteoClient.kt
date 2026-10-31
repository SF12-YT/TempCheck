package com.example.tempalearn

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

/** Retrieves only the previous completed day's non-temperature Open-Meteo aggregates. */
object OpenMeteoClient {
    data class DailyVariables(
        val localDate: String,
        val sunriseSec: Long?,
        val sunsetSec: Long?,
        val daylightDurationSec: Double?,
        val sunshineDurationSec: Double?,
        val rainSumMm: Double?,
        val showersSumMm: Double?,
        val precipitationSumMm: Double?,
        val precipitationHours: Double?,
        val precipitationProbabilityMaxPct: Double?,
        val windDirectionDominantDeg: Double?,
        val windSpeedMaxKmh: Double?,
        val windGustsMaxKmh: Double?,
        val shortwaveRadiationSum: Double?,
        val et0SumMm: Double?
    )

    data class Result(val byDate: List<DailyVariables>)

    @Throws(IOException::class)
    fun fetchPreviousDailyContext(
        latitude: Double,
        longitude: Double,
        timezoneId: String,
        elevationM: Double? = null
    ): Result {
        // Daily aggregates only: one previous local calendar day and zero forecast days.
        // No hourly or temperature-valued fields are requested from Open-Meteo.
        val dailyFields = listOf(
            "sunrise",
            "sunset",
            "daylight_duration",
            "sunshine_duration",
            "rain_sum",
            "showers_sum",
            "precipitation_sum",
            "precipitation_hours",
            "precipitation_probability_max",
            "wind_direction_10m_dominant",
            "wind_speed_10m_max",
            "wind_gusts_10m_max",
            "shortwave_radiation_sum",
            "et0_fao_evapotranspiration"
        ).joinToString(",")

        val encodedTimezone = URLEncoder.encode(timezoneId, Charsets.UTF_8.name())
        val elevationParameter = elevationM?.takeIf { it.isFinite() }?.let { "&elevation=$it" }.orEmpty()
        val url = URL(
            "https://api.open-meteo.com/v1/forecast" +
                "?latitude=$latitude&longitude=$longitude$elevationParameter" +
                "&daily=$dailyFields" +
                "&past_days=1&forecast_days=0" +
                "&timezone=$encodedTimezone" +
                "&wind_speed_unit=kmh&precipitation_unit=mm"
        )
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 18_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "TempLearn-Android/0.7.0")
        }
        try {
            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (responseCode !in 200..299) {
                throw IOException("Open-Meteo returned HTTP $responseCode: ${responseText.take(350)}")
            }
            val root = try {
                JSONObject(responseText)
            } catch (error: Exception) {
                throw IOException("Open-Meteo returned data that could not be read as JSON.", error)
            }
            val daily = root.optJSONObject("daily")
                ?: throw IOException("Open-Meteo returned no daily weather data.")
            val dailyResult = parsePreviousCompletedDay(daily, timezoneId)
            if (dailyResult.isEmpty()) {
                throw IOException("Open-Meteo returned no completed previous-day daily record.")
            }
            return Result(dailyResult)
        } finally {
            connection.disconnect()
        }
    }

    /** Uses Open-Meteo's global 90 m DEM endpoint when the geocoder has no elevation value. */
    @Throws(IOException::class)
    fun fetchElevation(latitude: Double, longitude: Double): Double? {
        require(latitude.isFinite() && latitude in -90.0..90.0)
        require(longitude.isFinite() && longitude in -180.0..180.0)
        val url = URL("https://api.open-meteo.com/v1/elevation?latitude=$latitude&longitude=$longitude")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 18_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "TempLearn-Android/0.7.0")
        }
        try {
            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (responseCode !in 200..299) {
                throw IOException("Open-Meteo Elevation returned HTTP $responseCode: ${responseText.take(250)}")
            }
            val root = JSONObject(responseText)
            val values = root.optJSONArray("elevation") ?: return null
            if (values.length() == 0 || values.isNull(0)) return null
            return values.optDouble(0, Double.NaN).takeIf { it.isFinite() }
        } finally {
            connection.disconnect()
        }
    }

    private fun parsePreviousCompletedDay(daily: JSONObject, timezoneId: String): List<DailyVariables> {
        val times = daily.optJSONArray("time") ?: return emptyList()
        val zone = runCatching { ZoneId.of(timezoneId) }.getOrElse { ZoneId.of("UTC") }
        val today = LocalDate.now(zone)
        val result = ArrayList<DailyVariables>(times.length())
        for (index in 0 until times.length()) {
            val rawDate = times.optString(index, "")
            val localDate = runCatching { LocalDate.parse(rawDate.take(10)) }.getOrNull() ?: continue
            // Protect against an API response containing today's incomplete daily aggregate.
            if (!localDate.isBefore(today)) continue
            result += DailyVariables(
                localDate = localDate.toString(),
                sunriseSec = daily.dateTimeAt("sunrise", index, zone),
                sunsetSec = daily.dateTimeAt("sunset", index, zone),
                daylightDurationSec = daily.numberAt("daylight_duration", index),
                sunshineDurationSec = daily.numberAt("sunshine_duration", index),
                rainSumMm = daily.numberAt("rain_sum", index),
                showersSumMm = daily.numberAt("showers_sum", index),
                precipitationSumMm = daily.numberAt("precipitation_sum", index),
                precipitationHours = daily.numberAt("precipitation_hours", index),
                precipitationProbabilityMaxPct = daily.numberAt("precipitation_probability_max", index),
                windDirectionDominantDeg = daily.numberAt("wind_direction_10m_dominant", index),
                windSpeedMaxKmh = daily.numberAt("wind_speed_10m_max", index),
                windGustsMaxKmh = daily.numberAt("wind_gusts_10m_max", index),
                shortwaveRadiationSum = daily.numberAt("shortwave_radiation_sum", index),
                et0SumMm = daily.numberAt("et0_fao_evapotranspiration", index)
            )
        }
        return result
    }

    private fun JSONObject.dateTimeAt(arrayName: String, index: Int, zone: ZoneId): Long? {
        val array = optJSONArray(arrayName) ?: return null
        if (index !in 0 until array.length() || array.isNull(index)) return null
        val raw = array.optString(index, "")
        if (raw.isBlank()) return null
        return runCatching { OffsetDateTime.parse(raw).toEpochSecond() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(raw).atZone(zone).toEpochSecond() }.getOrNull()
    }

    private fun JSONObject.numberAt(arrayName: String, index: Int): Double? {
        val array = optJSONArray(arrayName) ?: return null
        if (index !in 0 until array.length() || array.isNull(index)) return null
        val value = array.optDouble(index, Double.NaN)
        return value.takeIf { it.isFinite() }
    }

}
