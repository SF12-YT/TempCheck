package com.example.tempalearn

import android.content.Context
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

object AppPreferences {
    const val PREFS_NAME = "templearn_preferences"
    const val KEY_LOCATION = "location"
    const val KEY_ACTIVE_LOCATION_KEY = "active_location_key"
    const val DEFAULT_LOCATION = "2780, NSW, Australia"
    const val SHARED_MODEL_KEY = "__templearn_shared_weather_model__"
    const val KEY_NOTIFICATION_PERMISSION_REQUESTED = "notification_permission_requested"

    /** Stable per-query key; histories remain separate and can be reopened by returning to that query. */
    fun locationKey(location: String): String =
        location.trim().replace(Regex("\\s+"), " ").lowercase(Locale.US)

    fun geocodedLocationKey(geocodingId: Long): String = "geo:$geocodingId"

    fun activeLocationKey(preferences: android.content.SharedPreferences, location: String): String =
        preferences.getString(KEY_ACTIVE_LOCATION_KEY, null)?.takeIf { it.isNotBlank() }
            ?: locationKey(location)

    fun syncTimeKey(locationKey: String) = "last_sync_$locationKey"
    fun timezoneKey(locationKey: String) = "timezone_$locationKey"
    fun resolvedAddressKey(locationKey: String) = "resolved_address_$locationKey"
    fun latitudeKey(locationKey: String) = "latitude_$locationKey"
    fun longitudeKey(locationKey: String) = "longitude_$locationKey"
    fun elevationKey(locationKey: String) = "elevation_$locationKey"
}

data class SyncResult(
    val locationKey: String,
    val fetchedHours: Int,
    val savedReadings: Int,
    val trainingExamples: Int,
    val timezoneId: String,
    val modelReady: Boolean,
    val resolvedAddress: String,
    val locationCount: Int,
    val sharedTrainingExamples: Int,
    val bomStationName: String?,
    val bomDistanceKm: Double?,
    val bomAppliedHours: Int,
    val openMeteoDailyDays: Int,
    val openMeteoAvailable: Boolean,
    val locationProfile: LocationProfile
)

object SyncRepository {
    /**
     * Resolve or reuse one canonical Open-Meteo geocoding result, then fan its coordinates,
     * elevation and timezone out to all data-source adapters. Only BOM rows train the temperature
     * model; Open-Meteo daily context remains in its separate daily table.
     */
    @Throws(Exception::class)
    fun sync(context: Context, location: String, selectedProfile: LocationProfile? = null): SyncResult {
        val appContext = context.applicationContext
        val normalizedLocation = location.trim()
        require(normalizedLocation.length >= 2) { "Enter a postcode, town, or address." }
        val preferences = appContext.getSharedPreferences(AppPreferences.PREFS_NAME, Context.MODE_PRIVATE)
        val legacyLocationKey = AppPreferences.locationKey(normalizedLocation)
        var locationKey = AppPreferences.activeLocationKey(preferences, normalizedLocation)
        val database = TemperatureDatabase(appContext)
        try {
            var profile = selectedProfile?.copy(searchQuery = normalizedLocation)
                ?: database.getLocationProfile(locationKey)
                ?: database.getLocationProfile(legacyLocationKey)
                ?: LocationGeocodingClient.resolveUnambiguous(normalizedLocation, legacyLocationKey)

            val canonicalKey = AppPreferences.geocodedLocationKey(profile.geocodingId)
            if (locationKey != canonicalKey) {
                // Migrate the legacy query-keyed history once, but never merge two different
                // GeoNames places that happened to share the same search text.
                database.moveLocationKey(legacyLocationKey, canonicalKey, profile.geocodingId)
                locationKey = canonicalKey
                profile = profile.copy(locationKey = canonicalKey)
            }

            // Prefer the geocoder's elevation. If it is absent, use Open-Meteo's global 90 m DEM.
            if (profile.elevationM == null) {
                val demElevation = runCatching {
                    OpenMeteoClient.fetchElevation(profile.latitude, profile.longitude)
                }.getOrNull()
                if (demElevation != null) profile = profile.copy(elevationM = demElevation)
            }
            database.saveLocationProfile(profile)

            val openMeteo = runCatching {
                OpenMeteoClient.fetchPreviousDailyContext(
                    latitude = profile.latitude,
                    longitude = profile.longitude,
                    timezoneId = profile.timezoneId,
                    elevationM = profile.elevationM
                )
            }.getOrNull()
            openMeteo?.let { database.insertDailyWeather(locationKey, it.byDate) }

            // BOM remains the exclusive provider for historical air temperature and historical
            // predictors. Each refresh accumulates its latest ~72-hour station window locally.
            val bom = runCatching {
                BomClient.fetchRecentObservations(
                    latitude = profile.latitude,
                    longitude = profile.longitude,
                    locationText = "${profile.displayName}, $normalizedLocation",
                    timezoneId = profile.timezoneId
                )
            }.getOrNull()

            val freshBomByHour = bom?.observationsByHour.orEmpty()
            val bomReadings = freshBomByHour.toSortedMap().map { (timestamp, observation) ->
                HourlyTemperature(
                    timestampSec = timestamp,
                    temperatureC = observation.temperatureC,
                    dewPointC = observation.dewPointC,
                    windSpeedKmh = observation.windSpeedKmh,
                    windGustKmh = observation.windGustKmh,
                    pressureHpa = observation.pressureHpa,
                    humidityPct = observation.humidityPct,
                    cloudCoverPct = observation.cloudCoverPct,
                    precipitationMm = observation.rainMm,
                    windDirectionDeg = observation.windDirectionDeg,
                    temperatureSource = "BOM",
                    surfacePressureHpa = observation.surfacePressureHpa,
                    rainMm = observation.rainMm,
                    visibilityM = observation.visibilityM,
                    wetBulbTemperatureC = observation.wetBulbTemperatureC,
                    apparentTemperatureC = observation.apparentTemperatureC
                )
            }
            database.insertReadings(locationKey, bomReadings)

            val readingsByLocation = database.getAllReadingsByLocation()
            val profilesByLocation = database.getAllLocationProfiles()
            val seriesByLocation = linkedMapOf<String, Pair<List<HourlyTemperature>, String>>()
            val contextsByLocation = linkedMapOf<String, ModelLocationContext>()
            readingsByLocation.forEach { (key, readings) ->
                // A shared learner must never invent geographic metadata for legacy histories.
                // Until a place has a canonical profile, keep its observations stored but exclude
                // them from cross-location fitting rather than pretending they are at 0°, 0°.
                val savedProfile = profilesByLocation[key]
                    ?: if (key == locationKey) profile else null
                if (savedProfile == null) return@forEach
                val zone = savedProfile.timezoneId
                seriesByLocation[key] = readings to zone
                contextsByLocation[key] = savedProfile.modelContext()
            }
            // The active profile may have no BOM rows on its first sync yet; it is still the
            // location context used by the local learner and the shared-model training set.
            contextsByLocation[locationKey] = profile.modelContext()

            val otherSeries = seriesByLocation.filterKeys { it != locationKey }
            val otherContexts = contextsByLocation.filterKeys { it != locationKey }
            val crossLocationPrior = if (otherSeries.isNotEmpty()) {
                WeatherLearner.trainShared(otherSeries, locationContexts = otherContexts)
            } else null
            val activeReadings = readingsByLocation[locationKey].orEmpty()
            val localModel = WeatherLearner.trainLocal(
                readings = activeReadings,
                timezoneId = profile.timezoneId,
                priorModel = crossLocationPrior,
                locationContext = profile.modelContext()
            )
            if (localModel != null) database.saveModel(locationKey, localModel)

            val sharedModel = WeatherLearner.trainShared(
                series = seriesByLocation,
                locationContexts = contextsByLocation
            )
            if (sharedModel != null) database.saveModel(AppPreferences.SHARED_MODEL_KEY, sharedModel)

            preferences.edit()
                .putString(AppPreferences.KEY_ACTIVE_LOCATION_KEY, locationKey)
                .putString(AppPreferences.timezoneKey(locationKey), profile.timezoneId)
                .putString(AppPreferences.resolvedAddressKey(locationKey), profile.displayName)
                .putString(AppPreferences.latitudeKey(locationKey), profile.latitude.toString())
                .putString(AppPreferences.longitudeKey(locationKey), profile.longitude.toString())
                .putString(AppPreferences.elevationKey(locationKey), profile.elevationM?.toString())
                .putLong(AppPreferences.syncTimeKey(locationKey), Instant.now().epochSecond)
                .apply()

            return SyncResult(
                locationKey = locationKey,
                fetchedHours = bomReadings.size,
                savedReadings = database.getSavedCount(locationKey),
                trainingExamples = localModel?.trainingExamples ?: 0,
                timezoneId = profile.timezoneId,
                modelReady = localModel != null,
                resolvedAddress = profile.displayName,
                locationCount = database.getLocationCount(),
                sharedTrainingExamples = sharedModel?.trainingExamples ?: 0,
                bomStationName = bom?.stationName,
                bomDistanceKm = bom?.distanceKm,
                bomAppliedHours = freshBomByHour.size,
                openMeteoDailyDays = openMeteo?.byDate?.size ?: 0,
                openMeteoAvailable = openMeteo != null,
                locationProfile = profile
            )
        } finally {
            database.close()
        }
    }
}
