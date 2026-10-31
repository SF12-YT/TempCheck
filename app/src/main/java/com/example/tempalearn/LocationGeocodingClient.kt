package com.example.tempalearn

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.util.Locale

/** Global location search through Open-Meteo Geocoding; returns coordinates, elevation and timezone. */
object LocationGeocodingClient {
    data class Place(
        val id: Long,
        val name: String,
        val latitude: Double,
        val longitude: Double,
        val elevationM: Double?,
        val timezoneId: String,
        val countryCode: String?,
        val country: String?,
        val admin1: String?,
        val admin2: String?,
        val featureCode: String?,
        val population: Long?,
        val postcodes: List<String>
    ) {
        val displayName: String
            get() = listOfNotNull(
                name.takeIf { it.isNotBlank() },
                admin2?.takeIf { it.isNotBlank() && it != name },
                admin1?.takeIf { it.isNotBlank() && it != name },
                country?.takeIf { it.isNotBlank() }
            ).distinct().joinToString(", ")

        fun toProfile(locationKey: String, searchQuery: String, elevationOverrideM: Double? = null) =
            LocationProfile(
                locationKey = locationKey,
                geocodingId = id,
                searchQuery = searchQuery,
                name = name,
                admin1 = admin1,
                admin2 = admin2,
                country = country,
                countryCode = countryCode,
                latitude = latitude,
                longitude = longitude,
                elevationM = elevationOverrideM ?: elevationM,
                timezoneId = timezoneId,
                featureCode = featureCode,
                population = population,
                postcodes = postcodes,
                resolvedAtSec = Instant.now().epochSecond
            )

        fun selectionLabel(): String {
            val coordinates = String.format(Locale.ROOT, " · %.4f°, %.4f°", latitude, longitude)
            val elevation = elevationM?.let { " · ${it.toInt()} m elevation" } ?: " · elevation unavailable"
            val postcode = postcodes.firstOrNull()?.let { " · $it" }.orEmpty()
            return displayName + postcode + coordinates + elevation
        }
    }

    /**
     * Search accepts a place/postcode optionally qualified by an admin area and country, e.g.
     * "Katoomba, NSW, Australia". Several results are returned so the user can disambiguate them.
     */
    @Throws(IOException::class)
    fun search(query: String): List<Place> {
        val raw = query.trim().replace(Regex("\\s+"), " ")
        require(raw.length >= 2) { "Enter a town, postcode or address with at least two characters." }
        val parts = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val explicitCountry = parts.lastOrNull()?.let(::countryCodeFor)
        val inferredAustralianState = parts.getOrNull(1)?.uppercase(Locale.ROOT) in setOf(
            "NSW", "VIC", "QLD", "SA", "WA", "TAS", "ACT", "NT"
        )
        val countryCode = explicitCountry ?: if (inferredAustralianState) "AU" else null
        val locationName = parts.first()
        val adminQualifier = parts.getOrNull(1)?.takeUnless { countryCodeFor(it) != null }
        val nameParameter = when {
            adminQualifier != null -> "$locationName, $adminQualifier"
            countryCode != null -> locationName
            else -> raw
        }
        val parameters = buildList {
            add("name=${encode(nameParameter)}")
            add("count=10")
            add("language=en")
            countryCode?.let { add("countryCode=${encode(it)}") }
        }.joinToString("&")
        val url = URL("https://geocoding-api.open-meteo.com/v1/search?$parameters")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "TempLearn-Android/0.7.0")
        }
        try {
            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (responseCode !in 200..299) {
                throw IOException("Open-Meteo Geocoding returned HTTP $responseCode: ${responseText.take(350)}")
            }
            val root = try {
                JSONObject(responseText)
            } catch (error: Exception) {
                throw IOException("Open-Meteo Geocoding returned data that could not be read as JSON.", error)
            }
            if (root.optBoolean("error")) {
                throw IOException(root.optString("reason", "Open-Meteo could not resolve this location."))
            }
            val results = root.optJSONArray("results") ?: return emptyList()
            return buildList {
                for (index in 0 until results.length()) {
                    val item = results.optJSONObject(index) ?: continue
                    val id = item.optLong("id", -1L)
                    val name = item.optString("name", "").trim()
                    val latitude = item.optDouble("latitude", Double.NaN)
                    val longitude = item.optDouble("longitude", Double.NaN)
                    val timezone = item.optString("timezone", "").trim()
                    if (id <= 0 || name.isBlank() || !latitude.isFinite() || !longitude.isFinite() || timezone.isBlank()) continue
                    if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) continue
                    val postcodeArray = item.optJSONArray("postcodes")
                    val postcodes = buildList {
                        if (postcodeArray != null) for (postcodeIndex in 0 until postcodeArray.length()) {
                            postcodeArray.optString(postcodeIndex).takeIf { it.isNotBlank() }?.let(::add)
                        }
                    }
                    add(
                        Place(
                            id = id,
                            name = name,
                            latitude = latitude,
                            longitude = longitude,
                            elevationM = item.numberOrNull("elevation"),
                            timezoneId = timezone,
                            countryCode = item.optString("country_code", "").takeIf { it.isNotBlank() },
                            country = item.optString("country", "").takeIf { it.isNotBlank() },
                            admin1 = item.optString("admin1", "").takeIf { it.isNotBlank() },
                            admin2 = item.optString("admin2", "").takeIf { it.isNotBlank() },
                            featureCode = item.optString("feature_code", "").takeIf { it.isNotBlank() },
                            population = item.takeIf { it.has("population") && !it.isNull("population") }
                                ?.optLong("population"),
                            postcodes = postcodes
                        )
                    )
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    /** Background sync may resolve a not-yet-saved location only when the result is unambiguous. */
    @Throws(IOException::class)
    fun resolveUnambiguous(query: String, locationKey: String): LocationProfile {
        val matches = search(query)
        if (matches.isEmpty()) throw IOException("No matching place was found. Try adding its state and country.")
        if (matches.size > 1) {
            throw IOException("Several places match this search. Open Settings, search again, and choose the correct result.")
        }
        return matches.single().toProfile(locationKey, query)
    }

    private fun JSONObject.numberOrNull(key: String): Double? {
        if (!has(key) || isNull(key)) return null
        return optDouble(key, Double.NaN).takeIf { it.isFinite() }
    }

    private fun countryCodeFor(value: String): String? {
        val cleaned = value.trim()
        val upper = cleaned.uppercase(Locale.ROOT)
        val aliases = mapOf("UK" to "GB", "U.K." to "GB", "USA" to "US", "U.S.A." to "US")
        aliases[upper]?.let { return it }
        if (upper.matches(Regex("[A-Z]{2}")) && Locale.getISOCountries().contains(upper)) return upper
        if (upper.matches(Regex("[A-Z]{3}"))) {
            Locale.getISOCountries().firstOrNull { code ->
                runCatching { Locale("", code).getISO3Country().equals(upper, ignoreCase = true) }.getOrDefault(false)
            }?.let { return it }
        }
        return Locale.getISOCountries().firstOrNull { code ->
            Locale("", code).getDisplayCountry(Locale.ENGLISH).equals(cleaned, ignoreCase = true)
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
}
