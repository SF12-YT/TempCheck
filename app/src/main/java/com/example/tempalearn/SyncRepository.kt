package com.example.tempalearn

import android.content.Context
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

object AppPreferences {
    const val PREFS_NAME = "templearn_preferences"
    const val KEY_LOCATION = "location"
    const val KEY_ACTIVE_LOCATION_KEY = "active_location_key"
    const val DEFAULT_LOCATION = "2780, NSW, Australia"
    const val SHARED_MODEL_KEY = "__templearn_shared_weather_model__"
    const val KEY_NOTIFICATION_PERMISSION_REQUESTED = "notification_permission_requested"

    fun locationKey(location: String) = location.trim().replace(Regex("\\s+"), " ").lowercase(Locale.US)
    fun geocodedLocationKey(geocodingId: Long) = "geo:$geocodingId"
    fun activeLocationKey(preferences: android.content.SharedPreferences, location: String) =
        preferences.getString(KEY_ACTIVE_LOCATION_KEY, null)?.takeIf { it.isNotBlank() } ?: locationKey(location)
    fun syncTimeKey(locationKey: String) = "last_sync_$locationKey"
    fun timezoneKey(locationKey: String) = "timezone_$locationKey"
    fun resolvedAddressKey(locationKey: String) = "resolved_address_$locationKey"
    fun latitudeKey(locationKey: String) = "latitude_$locationKey"
    fun longitudeKey(locationKey: String) = "longitude_$locationKey"
    fun elevationKey(locationKey: String) = "elevation_$locationKey"
    // v3 marks the JSON archive snapshot as fully imported on this device.
    fun historyCompleteKey(locationKey: String) = "open_meteo_history_file_complete_v3_$locationKey"
}

enum class ArchiveProgressStage { DOWNLOADING, IMPORTING }

data class ArchiveDownloadProgress(
    val stage: ArchiveProgressStage,
    val bytesProcessed: Long,
    val totalBytes: Long
) {
    /** -1 means the server did not provide a content length; the UI should stay indeterminate. */
    val percent: Int
        get() = if (totalBytes <= 0L) -1 else ((bytesProcessed.coerceAtLeast(0L) * 100L) / totalBytes).toInt().coerceIn(0, 100)
}

data class DatasetDownloadResult(
    val locationKey: String,
    val fetchedHours: Int,
    val savedReadings: Int,
    val initialHistoryComplete: Boolean,
    val initialImportCompletedNow: Boolean,
    val importedFromDate: String,
    val importedToDate: String,
    val locationProfile: LocationProfile,
    val localArchivePath: String,
    val localArchiveBytes: Long
)

data class TrainingProgress(val percent: Int, val message: String)

data class TrainingResult(
    val locationKey: String,
    val trainingExamples: Int,
    val sharedTrainingExamples: Int,
    val modelReady: Boolean
)

data class SyncResult(
    val locationKey: String, val fetchedHours: Int, val savedReadings: Int, val trainingExamples: Int,
    val timezoneId: String, val modelReady: Boolean, val resolvedAddress: String, val locationCount: Int,
    val sharedTrainingExamples: Int, val initialHistoryComplete: Boolean, val initialImportCompletedNow: Boolean,
    val importedFromDate: String, val importedToDate: String, val locationProfile: LocationProfile
)

object SyncRepository {
    /**
     * Import the published complete JSON snapshot for the default location. The original JSON is
     * retained unchanged on-device. Other locations retain the one-file Open-Meteo CSV path.
     * Subsequent syncs update only the latest available date from Open-Meteo Archive API.
     */
    @Throws(Exception::class)
    fun downloadHistoricalDataset(
        context: Context,
        location: String,
        selectedProfile: LocationProfile? = null,
        onProgress: (ArchiveDownloadProgress) -> Unit = {}
    ): DatasetDownloadResult {
        val app = context.applicationContext
        val normalized = location.trim()
        require(normalized.length >= 2) { "Enter a postcode, town, or address." }
        val prefs = app.getSharedPreferences(AppPreferences.PREFS_NAME, Context.MODE_PRIVATE)
        val legacyKey = AppPreferences.locationKey(normalized)
        var locationKey = AppPreferences.activeLocationKey(prefs, normalized)
        val db = TemperatureDatabase(app)
        try {
            var profile = selectedProfile?.copy(searchQuery = normalized)
                ?: db.getLocationProfile(locationKey)
                ?: db.getLocationProfile(legacyKey)
                ?: LocationGeocodingClient.resolveUnambiguous(normalized, legacyKey)
            val canonical = AppPreferences.geocodedLocationKey(profile.geocodingId)
            if (locationKey != canonical) {
                db.moveLocationKey(legacyKey, canonical, profile.geocodingId)
                locationKey = canonical
                profile = profile.copy(locationKey = canonical)
            }
            if (profile.elevationM == null) {
                val elevation = runCatching { OpenMeteoClient.fetchElevation(profile.latitude, profile.longitude) }.getOrNull()
                if (elevation != null) profile = profile.copy(elevationM = elevation)
            }
            db.saveLocationProfile(profile)
            prefs.edit()
                .putString(AppPreferences.KEY_ACTIVE_LOCATION_KEY, locationKey)
                .putString(AppPreferences.KEY_LOCATION, normalized)
                .putString(AppPreferences.timezoneKey(locationKey), profile.timezoneId)
                .putString(AppPreferences.resolvedAddressKey(locationKey), profile.displayName)
                .putString(AppPreferences.latitudeKey(locationKey), profile.latitude.toString())
                .putString(AppPreferences.longitudeKey(locationKey), profile.longitude.toString())
                .putString(AppPreferences.elevationKey(locationKey), profile.elevationM?.toString())
                .apply()

            val zone = ZoneId.of(profile.timezoneId)
            val archiveEndDate = minOf(LocalDate.now(zone), LocalDate.now(ZoneId.of("UTC")).minusDays(1))
            val completeKey = AppPreferences.historyCompleteKey(locationKey)
            val archiveDirectory = File(
                app.filesDir,
                "historical-datasets/" + locationKey.replace(Regex("[^A-Za-z0-9_-]"), "_")
            )
            if (!archiveDirectory.exists() && !archiveDirectory.mkdirs()) {
                throw java.io.IOException("Could not create the local historical dataset folder.")
            }

            // The published JSON is the full 2000-10/31/2026 snapshot for the app's default
            // 2780 location. Other locations receive a matching full-range CSV from Open-Meteo.
            val usePublishedJson = normalized.equals(AppPreferences.DEFAULT_LOCATION, ignoreCase = true)
            val archiveFile = File(
                archiveDirectory,
                if (usePublishedJson) "open_meteo_best_match_full_history.json"
                else "open_meteo_best_match_2000_present_v3.csv"
            )
            val initialNeeded = !prefs.getBoolean(completeKey, false) || !archiveFile.isFile || archiveFile.length() <= 0L
            val latest = db.getLatestTimestampSec(locationKey)
            val latestDate = latest?.let { Instant.ofEpochSecond(it).atZone(zone).toLocalDate() }
            var fetchedRows: Int
            var importedFromDate: String
            var importedToDate: String

            if (initialNeeded && usePublishedJson) {
                // A completed JSON download is reused after an interrupted or failed database import.
                if (!archiveFile.isFile || archiveFile.length() <= 0L) {
                    OpenMeteoClient.downloadHistoricalJsonToFile(archiveFile) { transfer ->
                        onProgress(
                            ArchiveDownloadProgress(
                                ArchiveProgressStage.DOWNLOADING,
                                transfer.bytesTransferred,
                                transfer.totalBytes
                            )
                        )
                    }
                }
                val imported = OpenMeteoClient.importHourlyArchiveJsonFile(
                    archiveFile,
                    onBatch = { batch -> db.insertReadings(locationKey, batch) },
                    onProgress = { transfer ->
                        onProgress(
                            ArchiveDownloadProgress(
                                ArchiveProgressStage.IMPORTING,
                                transfer.bytesTransferred,
                                transfer.totalBytes
                            )
                        )
                    }
                )
                fetchedRows = imported.importedRows
                val archiveZone = imported.metadata.timezoneId?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: zone
                importedFromDate = Instant.ofEpochSecond(imported.firstTimestampSec).atZone(archiveZone).toLocalDate().toString()
                importedToDate = Instant.ofEpochSecond(imported.lastTimestampSec).atZone(archiveZone).toLocalDate().toString()

                // Use the exact grid point and timezone reported by the JSON, not the postcode
                // centroid returned by geocoding, so model location metadata matches its data.
                val metadata = imported.metadata
                if (metadata.latitude != null && metadata.longitude != null) {
                    profile = profile.copy(
                        latitude = metadata.latitude,
                        longitude = metadata.longitude,
                        elevationM = metadata.elevationM ?: profile.elevationM,
                        timezoneId = metadata.timezoneId ?: profile.timezoneId
                    )
                    db.saveLocationProfile(profile)
                    prefs.edit()
                        .putString(AppPreferences.timezoneKey(locationKey), profile.timezoneId)
                        .putString(AppPreferences.latitudeKey(locationKey), profile.latitude.toString())
                        .putString(AppPreferences.longitudeKey(locationKey), profile.longitude.toString())
                        .putString(AppPreferences.elevationKey(locationKey), profile.elevationM?.toString())
                        .apply()
                }
            } else {
                // Incremental refreshes only re-read the latest stored date; new locations still
                // receive one complete CSV file from 1 January 2000, never segmented downloads.
                val start = if (initialNeeded) LocalDate.of(2000, 1, 1)
                    else minOf(latestDate ?: archiveEndDate, archiveEndDate)
                val importFile = if (initialNeeded) archiveFile else File(archiveDirectory, "latest_update.csv")
                if (!importFile.isFile || importFile.length() <= 0L || !initialNeeded) {
                    OpenMeteoClient.downloadHourlyArchiveToFile(
                        profile.latitude, profile.longitude, profile.timezoneId, profile.elevationM,
                        start, archiveEndDate, importFile
                    ) { transfer ->
                        onProgress(
                            ArchiveDownloadProgress(
                                ArchiveProgressStage.DOWNLOADING,
                                transfer.bytesTransferred,
                                transfer.totalBytes
                            )
                        )
                    }
                }
                fetchedRows = OpenMeteoClient.importHourlyArchiveFile(
                    importFile,
                    onBatch = { batch -> db.insertReadings(locationKey, batch) },
                    onProgress = { transfer ->
                        onProgress(
                            ArchiveDownloadProgress(
                                ArchiveProgressStage.IMPORTING,
                                transfer.bytesTransferred,
                                transfer.totalBytes
                            )
                        )
                    }
                )
                importedFromDate = start.toString()
                importedToDate = archiveEndDate.toString()
            }

            if (initialNeeded) {
                db.clearModels()
                prefs.edit().putBoolean(completeKey, true).apply()
                File(archiveDirectory, "open_meteo_best_match_2000_present.csv").delete()
            }
            prefs.edit().putLong(AppPreferences.syncTimeKey(locationKey), Instant.now().epochSecond).apply()
            return DatasetDownloadResult(
                locationKey = locationKey,
                fetchedHours = fetchedRows,
                savedReadings = db.getSavedCount(locationKey),
                initialHistoryComplete = prefs.getBoolean(completeKey, false),
                initialImportCompletedNow = initialNeeded,
                importedFromDate = importedFromDate,
                importedToDate = importedToDate,
                locationProfile = profile,
                localArchivePath = archiveFile.absolutePath,
                localArchiveBytes = archiveFile.length()
            )
        } finally {
            db.close()
        }
    }

    /** Train from the already-downloaded local archive, without making any network requests. */
    @Throws(Exception::class)
    fun pretrainHistoricalDataset(
        context: Context,
        location: String,
        onProgress: (TrainingProgress) -> Unit = {}
    ): TrainingResult {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(AppPreferences.PREFS_NAME, Context.MODE_PRIVATE)
        val locationKey = AppPreferences.activeLocationKey(prefs, location)
        val db = TemperatureDatabase(app)
        try {
            val complete = prefs.getBoolean(AppPreferences.historyCompleteKey(locationKey), false)
            require(complete) { "Finish downloading the historical dataset before pre-training." }
            val profiles = db.getAllLocationProfiles()
            val profile = profiles[locationKey] ?: throw IllegalStateException("The selected location has not been saved yet.")
            onProgress(TrainingProgress(8, "Reading the saved historical dataset…"))
            val sampled = db.getAllReadingsByLocation()
            val series = linkedMapOf<String, Pair<List<HourlyTemperature>, String>>()
            val contexts = linkedMapOf<String, ModelLocationContext>()
            sampled.forEach { (key, readings) ->
                val saved = profiles[key]
                if (saved != null) {
                    series[key] = readings to saved.timezoneId
                    contexts[key] = saved.modelContext()
                }
            }
            contexts[locationKey] = profile.modelContext()
            val otherSeries = series.filterKeys { it != locationKey }
            onProgress(TrainingProgress(28, "Preparing location-specific training examples…"))
            val prior = if (otherSeries.isNotEmpty()) {
                WeatherLearner.trainShared(otherSeries, locationContexts = contexts.filterKeys { it != locationKey })
            } else null
            onProgress(TrainingProgress(48, "Fitting the local temperature model…"))
            val local = WeatherLearner.trainLocal(
                readings = series[locationKey]?.first.orEmpty(),
                timezoneId = profile.timezoneId,
                priorModel = prior,
                locationContext = profile.modelContext()
            )
            if (local != null) db.saveModel(locationKey, local)
            onProgress(TrainingProgress(82, "Fitting the cross-location model…"))
            val shared = WeatherLearner.trainShared(series, locationContexts = contexts)
            if (shared != null) db.saveModel(AppPreferences.SHARED_MODEL_KEY, shared)
            onProgress(TrainingProgress(100, if (local != null) "Pre-training complete." else "Not enough valid hourly examples to fit a model yet."))
            return TrainingResult(
                locationKey = locationKey,
                trainingExamples = local?.trainingExamples ?: 0,
                sharedTrainingExamples = shared?.trainingExamples ?: 0,
                modelReady = local != null
            )
        } finally {
            db.close()
        }
    }

    /** Existing refresh flow: update archive first, then refit the models. */
    @Throws(Exception::class)
    fun sync(
        context: Context,
        location: String,
        selectedProfile: LocationProfile? = null,
        onArchiveProgress: (ArchiveDownloadProgress) -> Unit = {},
        onTrainingProgress: (TrainingProgress) -> Unit = {}
    ): SyncResult {
        val downloaded = downloadHistoricalDataset(context, location, selectedProfile, onArchiveProgress)
        onTrainingProgress(TrainingProgress(5, "Historical dataset ready; starting model training…"))
        val trained = pretrainHistoricalDataset(context, location, onTrainingProgress)
        return SyncResult(
            locationKey = downloaded.locationKey,
            fetchedHours = downloaded.fetchedHours,
            savedReadings = downloaded.savedReadings,
            trainingExamples = trained.trainingExamples,
            timezoneId = downloaded.locationProfile.timezoneId,
            modelReady = trained.modelReady,
            resolvedAddress = downloaded.locationProfile.displayName,
            locationCount = TemperatureDatabase(context).useCountLocationsSafely(),
            sharedTrainingExamples = trained.sharedTrainingExamples,
            initialHistoryComplete = downloaded.initialHistoryComplete,
            initialImportCompletedNow = downloaded.initialImportCompletedNow,
            importedFromDate = downloaded.importedFromDate,
            importedToDate = downloaded.importedToDate,
            locationProfile = downloaded.locationProfile
        )
    }

    private fun TemperatureDatabase.useCountLocationsSafely(): Int = try {
        getLocationCount()
    } finally {
        close()
    }
}
