package com.example.tempalearn

import android.util.Xml
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.*

/**
 * Reads BOM's documented anonymous station feeds for Australian locations.
 * The anonymous feed is for personal, non-commercial use; see README before public distribution.
 */
object BomClient {
    data class HourlyObservation(
        val temperatureC: Double,
        val dewPointC: Double? = null,
        val apparentTemperatureC: Double? = null,
        val wetBulbTemperatureC: Double? = null,
        val windSpeedKmh: Double? = null,
        val windGustKmh: Double? = null,
        val windDirectionDeg: Double? = null,
        val pressureHpa: Double? = null,
        val surfacePressureHpa: Double? = null,
        val humidityPct: Double? = null,
        val cloudCoverPct: Double? = null,
        val rainMm: Double? = null,
        val visibilityM: Double? = null
    )

    data class Result(
        val stationName: String,
        val distanceKm: Double,
        /** Top-of-hour observations keyed by the exact local-hour Unix timestamp. */
        val observationsByHour: Map<Long, HourlyObservation>
    ) {
        val temperaturesByHour: Map<Long, Double>
            get() = observationsByHour.mapValues { it.value.temperatureC }
    }

    private data class Station(
        val wmoId: String,
        val name: String,
        val latitude: Double,
        val longitude: Double,
        val currentTemperatureC: Double?,
        val currentTimeSec: Long?
    )

    private val compactUtc = DateTimeFormatter.ofPattern("yyyyMMddHHmmss", Locale.ROOT)

    /** Returns null where BOM has no supported Australian feed, usable station, or recent temperatures. */
    fun fetchRecentObservations(
        latitude: Double,
        longitude: Double,
        locationText: String,
        timezoneId: String
    ): Result? {
        val statePrefix = statePrefix(locationText, latitude, longitude) ?: return null
        val stations = runCatching { fetchStationIndex(statePrefix) }.getOrNull().orEmpty()
        if (stations.isEmpty()) return null

        // The closest BOM station is not always an automatic station. For example, Katoomba
        // (Farnells Road, WMO 94744) currently supplies only one 9 am observation per day,
        // whereas nearby Mount Boyce (WMO 94743) supplies half-hourly observations. Probe nearby
        // station feeds in distance order and choose the closest station with genuine hourly
        // coverage, rather than blindly accepting the nearest station's sparse SYNOP data.
        val candidates = stations
            .filter { it.latitude.isFinite() && it.longitude.isFinite() && it.wmoId.matches(Regex("\\d{5}")) }
            .distinctBy { it.wmoId }
            .map { it to distanceKm(latitude, longitude, it.latitude, it.longitude) }
            .filter { it.second <= MAX_STATION_DISTANCE_KM }
            .sortedBy { it.second }
            .take(MAX_STATIONS_TO_PROBE)

        var bestSparse: LocatedStationFeed? = null
        for ((station, distance) in candidates) {
            val feed = runCatching {
                fetchStationHistory(statePrefix, station.wmoId, timezoneId)
            }.getOrNull() ?: continue
            if (feed.observationsByHour.isEmpty()) continue

            val candidate = LocatedStationFeed(station, distance, feed)
            if (feed.hasHourlyCoverage) {
                return Result(
                    stationName = station.name.ifBlank { station.wmoId },
                    distanceKm = distance,
                    observationsByHour = feed.observationsByHour
                )
            }

            val previous = bestSparse
            if (previous == null ||
                feed.observationsByHour.size > previous.feed.observationsByHour.size ||
                (feed.observationsByHour.size == previous.feed.observationsByHour.size && distance < previous.distanceKm)
            ) {
                bestSparse = candidate
            }
        }

        // If there is no genuinely hourly station nearby, retain the best sparse BOM feed as a
        // partial source rather than discarding valid observations altogether. Missing BOM hours
        // remain missing; no other provider is allowed to invent a substitute air temperature.
        bestSparse?.let {
            return Result(
                stationName = it.station.name.ifBlank { it.station.wmoId },
                distanceKm = it.distanceKm,
                observationsByHour = it.feed.observationsByHour
            )
        }

        // Last-resort fallback to the nearest station-index observation, if it is exactly on hour.
        val nearest = candidates.firstOrNull() ?: return null
        val (station, distance) = nearest
        val now = System.currentTimeMillis() / 1_000L
        val currentTimestamp = station.currentTimeSec
            ?.takeIf { it >= now - 72L * 3_600L && it <= now + 5L * 60L }
        val hour = currentTimestamp?.let { exactHourStart(it, timezoneId) }
        val currentTemperature = station.currentTemperatureC
        if (hour != null && currentTemperature != null) {
            return Result(
                station.name.ifBlank { station.wmoId }, distance,
                mapOf(hour to HourlyObservation(temperatureC = currentTemperature))
            )
        }
        return null
    }

    private fun fetchStationIndex(statePrefix: String): List<Station> {
        val url = URL("https://www.bom.gov.au/fwo/${statePrefix}60920.xml")
        val xml = requestText(url, "application/xml, text/xml")
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(xml))

        val stations = mutableListOf<Station>()
        var current: StationBuilder? = null
        var periodTimeSec: Long? = null
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val name = parser.name.lowercase(Locale.ROOT)
                    when (name) {
                        "station" -> {
                            current = StationBuilder(
                                wmoId = parser.attribute("wmo-id", "wmo_id", "wmo", "station-id", "id").orEmpty(),
                                name = parser.attribute("stn-name", "name", "station-name", "description").orEmpty(),
                                latitude = parser.attribute("lat", "latitude")?.toDoubleOrNull(),
                                longitude = parser.attribute("lon", "long", "longitude")?.toDoubleOrNull()
                            )
                            periodTimeSec = null
                        }
                        "period" -> {
                            periodTimeSec = parseUtc(parser.attribute("time-utc", "time_utc", "aifstime_utc"))
                        }
                        "element" -> {
                            val type = parser.attribute("type", "name").orEmpty().lowercase(Locale.ROOT)
                            val value = runCatching { parser.nextText() }.getOrNull()?.trim()
                            if (current != null && value != null) {
                                when {
                                    type.contains("air_temperature") || type == "air_temp" ->
                                        current?.currentTemperatureC = parseNumber(value)
                                    type == "latitude" || type == "lat" ->
                                        current?.latitude = parseNumber(value)
                                    type == "longitude" || type == "lon" ->
                                        current?.longitude = parseNumber(value)
                                    type.contains("wmo") -> current?.wmoId = value
                                }
                            }
                        }
                        "wmo-id", "wmo_id", "wmo" -> if (current != null) {
                            val text = runCatching { parser.nextText() }.getOrNull()?.trim()
                            if (!text.isNullOrBlank()) current?.wmoId = text
                        }
                        "latitude", "lat" -> if (current != null && current?.latitude == null) {
                            current?.latitude = runCatching { parser.nextText().trim().toDouble() }.getOrNull()
                        }
                        "longitude", "lon" -> if (current != null && current?.longitude == null) {
                            current?.longitude = runCatching { parser.nextText().trim().toDouble() }.getOrNull()
                        }
                    }
                }
                XmlPullParser.END_TAG -> if (parser.name.equals("station", ignoreCase = true)) {
                    current?.let { builder ->
                        val latitude = builder.latitude
                        val longitude = builder.longitude
                        if (latitude != null && longitude != null) {
                            stations += Station(
                                wmoId = builder.wmoId.trim(),
                                name = builder.name.ifBlank { builder.wmoId },
                                latitude = latitude,
                                longitude = longitude,
                                currentTemperatureC = builder.currentTemperatureC,
                                currentTimeSec = periodTimeSec
                            )
                        }
                    }
                    current = null
                    periodTimeSec = null
                }
            }
            event = parser.next()
        }
        return stations
    }

    private fun fetchStationHistory(
        statePrefix: String,
        wmoId: String,
        timezoneId: String
    ): StationFeed {
        val productId = "${statePrefix}60801"
        val url = URL("https://www.bom.gov.au/fwo/$productId/$productId.$wmoId.json")
        val root = JSONObject(requestText(url, "application/json"))
        val observations = root.optJSONObject("observations")
        val data = observations?.optJSONArray("data") ?: JSONArray()
        val observationsByHour = linkedMapOf<Long, Pair<HourlyObservation, Long>>()
        val observationTimestamps = mutableListOf<Long>()
        val now = System.currentTimeMillis() / 1_000L
        for (index in 0 until data.length()) {
            val item = data.optJSONObject(index) ?: continue
            val temperature = item.numberOrNull("air_temp") ?: continue
            val timestamp = parseUtc(item.optString("aifstime_utc").takeIf { it.isNotBlank() })
                ?: parseLocalTime(item.optString("local_date_time_full"), timezoneId)
                ?: continue
            if (timestamp < now - 72L * 3_600L || timestamp > now + 5L * 60L) continue

            // TempLearn intentionally keeps one observation per local hour. The exact :00 BOM
            // sample is used; :30 samples are neither rounded nor treated as a second hourly value.
            val hour = exactHourStart(timestamp, timezoneId) ?: continue
            observationTimestamps += timestamp
            val pressureMsl = item.numberOrNull("press_msl")
            val stationPressure = item.numberOrNull("press")
            val rainHour = item.numberOrNull("rain_hour")
            val cloudOkta = item.numberOrNull("cloud_oktas")
            val windDirection = item.numberOrNull("wind_dir_deg")
                ?: parseCompassDirection(item.optString("wind_dir"))
            val observation = HourlyObservation(
                temperatureC = temperature,
                dewPointC = item.numberOrNull("dewpt"),
                apparentTemperatureC = item.numberOrNull("apparent_t"),
                wetBulbTemperatureC = item.numberOrNull("delta_t")?.let { temperature - it },
                windSpeedKmh = item.numberOrNull("wind_spd_kmh"),
                windGustKmh = item.numberOrNull("gust_kmh"),
                windDirectionDeg = windDirection,
                pressureHpa = pressureMsl,
                surfacePressureHpa = stationPressure,
                humidityPct = item.numberOrNull("rel_hum"),
                cloudCoverPct = cloudOkta?.let { (it.coerceIn(0.0, 8.0) / 8.0) * 100.0 },
                rainMm = rainHour,
                visibilityM = item.numberOrNull("vis_km")?.times(1_000.0)
            )
            val previous = observationsByHour[hour]
            if (previous == null || timestamp > previous.second) {
                observationsByHour[hour] = observation to timestamp
            }
        }

        val sortedTimes = observationTimestamps.distinct().sorted()
        val gapsMinutes = sortedTimes.zipWithNext { a, b -> (b - a) / 60.0 }
            .filter { it > 0.0 }
            .sorted()
        val medianGapMinutes = when {
            gapsMinutes.isEmpty() -> null
            gapsMinutes.size % 2 == 1 -> gapsMinutes[gapsMinutes.size / 2]
            else -> (gapsMinutes[gapsMinutes.size / 2 - 1] + gapsMinutes[gapsMinutes.size / 2]) / 2.0
        }
        return StationFeed(
            observationsByHour = observationsByHour.mapValues { it.value.first },
            medianIntervalMinutes = medianGapMinutes
        )
    }

    private fun parseCompassDirection(value: String?): Double? {
        val direction = value?.trim()?.uppercase(Locale.ROOT)?.takeIf { it.isNotBlank() } ?: return null
        return when (direction) {
            "N" -> 0.0
            "NNE" -> 22.5
            "NE" -> 45.0
            "ENE" -> 67.5
            "E" -> 90.0
            "ESE" -> 112.5
            "SE" -> 135.0
            "SSE" -> 157.5
            "S" -> 180.0
            "SSW" -> 202.5
            "SW" -> 225.0
            "WSW" -> 247.5
            "W" -> 270.0
            "WNW" -> 292.5
            "NW" -> 315.0
            "NNW" -> 337.5
            else -> null // CALM and missing values have no meaningful bearing.
        }
    }

    private fun requestText(url: URL, accept: String): String {
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 18_000
            setRequestProperty("Accept", accept)
            setRequestProperty("User-Agent", "TempLearn-Android/0.7.0 (personal weather-learning prototype)")
        }
        try {
            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (responseCode !in 200..299) throw IOException("BOM station feed returned HTTP $responseCode")
            if (body.isBlank()) throw IOException("BOM station feed returned an empty response")
            return body
        } finally {
            connection.disconnect()
        }
    }

    private data class StationFeed(
        val observationsByHour: Map<Long, HourlyObservation>,
        val medianIntervalMinutes: Double?
    ) {
        val hasHourlyCoverage: Boolean
            get() = observationsByHour.size >= MIN_HOURLY_READINGS &&
                medianIntervalMinutes != null && medianIntervalMinutes <= MAX_MEDIAN_GAP_MINUTES
    }

    private data class LocatedStationFeed(
        val station: Station,
        val distanceKm: Double,
        val feed: StationFeed
    )

    private class StationBuilder(
        var wmoId: String,
        var name: String,
        var latitude: Double?,
        var longitude: Double?,
        var currentTemperatureC: Double? = null
    )

    private fun XmlPullParser.attribute(vararg names: String): String? {
        for (index in 0 until attributeCount) {
            val attrName = getAttributeName(index).lowercase(Locale.ROOT)
            if (names.any { it.lowercase(Locale.ROOT) == attrName }) {
                return getAttributeValue(index)
            }
        }
        return null
    }

    private fun JSONObject.numberOrNull(name: String): Double? {
        if (!has(name) || isNull(name)) return null
        return optDouble(name, Double.NaN).takeIf { it.isFinite() }
    }

    private fun parseNumber(value: String): Double? = value.toDoubleOrNull()?.takeIf { it.isFinite() }

    private fun parseUtc(value: String?): Long? {
        val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        runCatching {
            return LocalDateTime.parse(text.take(14), compactUtc).toEpochSecond(ZoneOffset.UTC)
        }
        runCatching {
            return InstantParser.parse(text)
        }
        return null
    }

    private fun parseLocalTime(value: String?, timezoneId: String): Long? {
        val text = value?.trim()?.takeIf { it.length >= 14 } ?: return null
        val local = runCatching { LocalDateTime.parse(text.take(14), compactUtc) }.getOrNull() ?: return null
        val zone = runCatching { ZoneId.of(timezoneId) }.getOrElse { ZoneOffset.UTC }
        return local.atZone(zone).toEpochSecond()
    }

    private object InstantParser {
        fun parse(value: String): Long? = runCatching {
            java.time.Instant.parse(value).epochSecond
        }.getOrNull()
    }

    /** Returns the timestamp for this local hour only when the observation is exactly HH:00:00. */
    private fun exactHourStart(epochSec: Long, timezoneId: String): Long? {
        val zone = runCatching { ZoneId.of(timezoneId) }.getOrElse { ZoneOffset.UTC }
        val local = Instant.ofEpochSecond(epochSec).atZone(zone)
        if (local.minute != 0 || local.second != 0) return null
        return local.withMinute(0).withSecond(0).withNano(0).toEpochSecond()
    }

    private fun statePrefix(locationText: String, latitude: Double, longitude: Double): String? {
        val text = " ${locationText.uppercase(Locale.ROOT).replace(Regex("[^A-Z]+"), " ")} "
        val explicit = when {
            text.contains(" NEW SOUTH WALES ") || text.contains(" NSW ") -> "IDN"
            text.contains(" AUSTRALIAN CAPITAL TERRITORY ") || text.contains(" ACT ") -> "IDN"
            text.contains(" QUEENSLAND ") || text.contains(" QLD ") -> "IDQ"
            text.contains(" VICTORIA ") || text.contains(" VIC ") -> "IDV"
            text.contains(" SOUTH AUSTRALIA ") || text.contains(" SA ") -> "IDS"
            text.contains(" TASMANIA ") || text.contains(" TAS ") -> "IDT"
            text.contains(" WESTERN AUSTRALIA ") || text.contains(" WA ") -> "IDW"
            text.contains(" NORTHERN TERRITORY ") || text.contains(" NT ") -> "IDD"
            else -> null
        }
        if (explicit != null) return explicit

        // Approximate regional fallbacks when the resolved address omits a state abbreviation.
        return when {
            longitude in 112.0..129.0 && latitude in -36.0..-12.0 -> "IDW"
            longitude in 129.0..138.0 && latitude in -27.5..-10.0 -> "IDD"
            longitude in 138.0..141.2 && latitude in -38.5..-25.0 -> "IDS"
            longitude in 143.5..149.0 && latitude in -44.5..-39.0 -> "IDT"
            latitude in -39.5..-33.0 && longitude in 140.5..150.5 -> "IDV"
            longitude in 137.0..154.5 && latitude in -28.2..-10.0 -> "IDQ"
            longitude in 140.0..154.5 && latitude in -38.0..-28.0 -> "IDN"
            else -> null
        }
    }

    private fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadiusKm = 6_371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * earthRadiusKm * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    private const val MAX_STATION_DISTANCE_KM = 100.0
    private const val MAX_STATIONS_TO_PROBE = 12
    // A 72-hour automatic feed normally yields around 72 top-of-hour temperatures. Eighteen
    // samples plus a <=90-minute median report interval distinguishes hourly-capable AWS/METAR
    // feeds from manual/daily stations without rejecting a partially unavailable station.
    private const val MIN_HOURLY_READINGS = 18
    private const val MAX_MEDIAN_GAP_MINUTES = 90.0
}
