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
    const val DEFAULT_LOCATION = "Katoomba, NSW, Australia"
    const val SHARED_MODEL_KEY = "__templearn_shared_weather_model__"
    const val KEY_NOTIFICATION_PERMISSION_REQUESTED = "notification_permission_requested"
    const val WEATHER_POINT_AUTOMATIC = "automatic"
    const val WEATHER_POINT_MANUAL = "manual"
    const val DEFAULT_WEATHER_POINT_NAME = "Mount Boyce"

    fun locationKey(location: String) = location.trim().replace(Regex("\\s+"), " ").lowercase(Locale.US)
    fun geocodedLocationKey(geocodingId: Long) = "geo:$geocodingId"
    fun weatherDataLocationKey(geocodingId: Long, latitude: Double, longitude: Double): String {
        val latitudeE5 = kotlin.math.round(latitude * 100_000.0).toLong()
        val longitudeE5 = kotlin.math.round(longitude * 100_000.0).toLong()
        return "geo:$geocodingId:point:$latitudeE5:$longitudeE5"
    }
    fun weatherPointModeKey(locationKey: String) = "weather_point_mode_$locationKey"
    fun weatherPointNameKey(locationKey: String) = "weather_point_name_$locationKey"
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
    val bytesTransferred: Long,
    val totalBytes: Long
) {
    /** -1 means the server did not provide a content length; the UI should stay indeterminate. */
    val percent: Int
        get() = if (totalBytes <= 0L) -1 else ((bytesTransferred.coerceAtLeast(0L) * 100L) / totalBytes).toInt().coerceIn(0, 100)
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
    val importedFromDate: String, val importedToDate: String, val locationProfile: LocationProfile,
    val recentUpdateWarning: String? = null
)

object SyncRepository {
    /**
     * Import and validate the published long-term archive. Its JSON/CSV is a temporary import
     * source; the hourly readings and fitted model are retained in the app's private database.
     * The default JSON is removed only after historical training has completed successfully.
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
        val isDefaultLocation = normalized.equals(AppPreferences.DEFAULT_LOCATION, ignoreCase = true)
        var locationKey = AppPreferences.activeLocationKey(prefs, normalized)
        val db = TemperatureDatabase(app)
        try {
            var profile = selectedProfile?.copy(searchQuery = normalized)
                ?: db.getLocationProfile(locationKey)
                ?: db.getLocationProfile(legacyKey)
                ?: if (isDefaultLocation) {
                    // The published archive is the default Mount Boyce weather-data point.
                    // Katoomba remains the named place shown in the interface.
                    LocationProfile(
                        locationKey = legacyKey,
                        geocodingId = 0L,
                        searchQuery = normalized,
                        name = "Katoomba",
                        admin1 = "New South Wales",
                        admin2 = null,
                        country = "Australia",
                        countryCode = "AU",
                        latitude = OpenMeteoClient.DEFAULT_LATITUDE,
                        longitude = OpenMeteoClient.DEFAULT_LONGITUDE,
                        elevationM = null,
                        timezoneId = OpenMeteoClient.DEFAULT_TIMEZONE_ID,
                        featureCode = null,
                        population = null,
                        postcodes = listOf("2780"),
                        resolvedAtSec = Instant.now().epochSecond
                    )
                } else {
                    LocationGeocodingClient.resolveUnambiguous(normalized, legacyKey)
                }

            // Keep the requested town as the profile label, even when its weather-data
            // coordinates are a separately selected nearby location.
            if (isDefaultLocation && profile.name.equals("2780", ignoreCase = true)) {
                profile = profile.copy(
                    searchQuery = normalized,
                    name = "Katoomba",
                    admin1 = "New South Wales",
                    admin2 = null,
                    country = "Australia",
                    countryCode = "AU",
                    postcodes = listOf("2780")
                )
            }

            // Profiles are stored under the location-plus-weather-point key. Preserve that key
            // instead of collapsing Automatic and Manual selections into one town-only series.
            if (locationKey != profile.locationKey) {
                locationKey = profile.locationKey
            }
            val usePublishedJson = isDefaultLocation &&
                kotlin.math.abs(profile.latitude - OpenMeteoClient.DEFAULT_LATITUDE) <= 0.001 &&
                kotlin.math.abs(profile.longitude - OpenMeteoClient.DEFAULT_LONGITUDE) <= 0.001

            if (isDefaultLocation && !prefs.contains(AppPreferences.weatherPointModeKey(locationKey))) {
                prefs.edit()
                    .putString(AppPreferences.weatherPointModeKey(locationKey), AppPreferences.WEATHER_POINT_MANUAL)
                    .putString(AppPreferences.weatherPointNameKey(locationKey), AppPreferences.DEFAULT_WEATHER_POINT_NAME)
                    .apply()
            }
            if (profile.elevationM == null && !usePublishedJson) {
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

            // The JSON/CSV file is an import source, not the app's permanent data store.
            // The full hourly series is stored in SQLite; the default JSON is deleted only after
            // the historical model has trained successfully in sync().
            val archiveFile = File(
                archiveDirectory,
                if (usePublishedJson) "open_meteo_best_match_full_history.json"
                else "open_meteo_best_match_2000_present_v3.csv"
            )
            // Completion is recorded only after a successful full import. Do not make the source
            // file's continued presence a requirement: the user may remove the JSON to reclaim storage.
            val historyComplete = prefs.getBoolean(completeKey, false)
            val initialNeeded = !historyComplete
            var fetchedRows = 0
            var importedFromDate = LocalDate.now(zone).minusDays(1).toString()
            var importedToDate = LocalDate.now(zone).toString()

            if (initialNeeded && usePublishedJson) {
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
                var uniqueHistoryRows = 0
                val imported = OpenMeteoClient.importHourlyArchiveJsonFile(
                    archiveFile,
                    onBatch = { batch -> uniqueHistoryRows += db.insertReadingsIfAbsent(locationKey, batch) },
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
                fetchedRows += uniqueHistoryRows
                val archiveZone = imported.metadata.timezoneId?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: zone
                importedFromDate = Instant.ofEpochSecond(imported.firstTimestampSec).atZone(archiveZone).toLocalDate().toString()
                importedToDate = Instant.ofEpochSecond(imported.lastTimestampSec).atZone(archiveZone).toLocalDate().toString()

                // Use the exact grid point and timezone recorded by the published JSON.
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
            } else if (initialNeeded) {
                // Other locations still receive the full 2000-present Archive API dataset once.
                val startDate = LocalDate.of(2000, 1, 1)
                if (!archiveFile.isFile || archiveFile.length() <= 0L) {
                    OpenMeteoClient.downloadHourlyArchiveToFile(
                        profile.latitude, profile.longitude, profile.timezoneId, profile.elevationM,
                        startDate, archiveEndDate, archiveFile
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
                var uniqueHistoryRows = 0
                OpenMeteoClient.importHourlyArchiveFile(
                    archiveFile,
                    onBatch = { batch -> uniqueHistoryRows += db.insertReadingsIfAbsent(locationKey, batch) },
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
                fetchedRows += uniqueHistoryRows
                importedFromDate = startDate.toString()
                importedToDate = archiveEndDate.toString()
            }

            // The historical archive is a complete, independent training source. Mark it complete
            // immediately after its full import, before attempting the optional rolling refresh.
            if (initialNeeded) {
                db.clearModels()
                db.clearPredictionFeedback(locationKey)
                prefs.edit().putBoolean(completeKey, true).apply()
                File(archiveDirectory, "open_meteo_best_match_2000_present.csv").delete()
            }

            db.getLatestTimestampSec(locationKey)?.let { latestTimestamp ->
                importedToDate = Instant.ofEpochSecond(latestTimestamp).atZone(zone).toLocalDate().toString()
            }
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

    /**
     * Fetch and merge the rolling recent-data window after the historical archive has already
     * been imported and trained. A failure here must not invalidate the historical dataset.
     */
    @Throws(Exception::class)
    private fun refreshRecentForecastDataset(
        context: Context,
        location: String,
        onProgress: (ArchiveDownloadProgress) -> Unit = {}
    ): Int {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(AppPreferences.PREFS_NAME, Context.MODE_PRIVATE)
        val locationKey = AppPreferences.activeLocationKey(prefs, location)
        val db = TemperatureDatabase(app)
        try {
            val profile = db.getLocationProfile(locationKey)
                ?: throw IllegalStateException("The selected location profile is not available.")
            val archiveDirectory = File(
                app.filesDir,
                "historical-datasets/" + locationKey.replace(Regex("[^A-Za-z0-9_-]"), "_")
            )
            if (!archiveDirectory.exists() && !archiveDirectory.mkdirs()) {
                throw java.io.IOException("Could not create the local recent-data folder.")
            }

            // Include enough completed past hours to cover the oldest pending forecast target.
            // This feed excludes future hours, so a target remains pending until its timestamp
            // becomes available as a past hour.
            val nowSec = Instant.now().epochSecond
            val oldestPendingTargetSec = db.getOldestPendingPredictionTimestampSec(locationKey)
            val pendingLookbackHours = oldestPendingTargetSec?.let { targetSec ->
                val elapsed = (nowSec - targetSec).coerceAtLeast(0L)
                val required = (elapsed + 3_599L) / 3_600L + 2L
                required.coerceIn(24L, 24L * 92L).toInt()
            } ?: 24
            // Reuse one temporary CSV so an hourly refresh does not accumulate a
            // separate, progressively larger lookback file every hour.
            archiveDirectory.listFiles { file ->
                file.isFile && file.name.startsWith("recent_forecast_past_") && file.name.endsWith("h.csv")
            }?.forEach { it.delete() }
            val recentForecastFile = File(archiveDirectory, "recent_forecast_past.csv")
            OpenMeteoClient.downloadRecentHourlyForecastToFile(
                profile.latitude,
                profile.longitude,
                profile.timezoneId,
                recentForecastFile,
                pastHours = pendingLookbackHours
            ) { transfer ->
                onProgress(
                    ArchiveDownloadProgress(
                        ArchiveProgressStage.DOWNLOADING,
                        transfer.bytesTransferred,
                        transfer.totalBytes
                    )
                )
            }
            // Replay the entire rolling 24-hour observation window, including rows that
            // were already in SQLite before this app version was installed. The temporary
            // replay model is fitted only on observations strictly older than that window.
            // It is never saved as the production model and never sees any target in this replay.
            val replayCutoffSec = Instant.now().epochSecond - 24L * 60L * 60L
            val allReadingsForReplay = db.getAllReadingsByLocation()[locationKey].orEmpty()
            val replayTrainingReadings = allReadingsForReplay.filter {
                it.timestampSec < replayCutoffSec
            }
            val replayModel = WeatherLearner.trainLocal(
                readings = replayTrainingReadings,
                timezoneId = profile.timezoneId,
                locationContext = profile.modelContext()
            )

            val existingLatest = db.getLatestReadings(locationKey, 96)
            val existingRecentReadings = existingLatest.filter {
                it.timestampSec >= replayCutoffSec
            }
            val walkForwardHistory = existingLatest.filter {
                it.timestampSec < replayCutoffSec
            }.takeLast(72).toMutableList()
            val existingTimestamps = existingLatest.mapTo(mutableSetOf()) { it.timestampSec }
            val downloadedRecentRows = mutableListOf<HourlyTemperature>()
            var uniqueForecastRows = 0

            // Parse first, then merge downloaded readings with already stored rows. This makes
            // upgrading an installation able to verify its existing recent observations too.
            OpenMeteoClient.importHourlyArchiveFile(
                recentForecastFile,
                onBatch = { batch -> downloadedRecentRows.addAll(batch) },
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

            val recentByTimestamp = linkedMapOf<Long, HourlyTemperature>()
            existingRecentReadings.forEach { recentByTimestamp[it.timestampSec] = it }
            downloadedRecentRows
                .filter { it.timestampSec >= replayCutoffSec }
                .forEach { recentByTimestamp.putIfAbsent(it.timestampSec, it) }

            // Walk forward in timestamp order. For each target, use only the three immediately
            // preceding actual observations and feedback from earlier targets. The target's
            // actual temperature is not appended to the history until after its prediction.
            recentByTimestamp.values.sortedBy { it.timestampSec }.forEach { reading ->
                val feedbackBeforeTarget = db.getVerifiedPredictionErrors(locationKey)
                    .filter { it.targetTimestampSec < reading.timestampSec }
                val walkForward = replayModel?.let { model ->
                    predictWalkForwardTarget(
                        priorReadings = walkForwardHistory,
                        targetTimestampSec = reading.timestampSec,
                        model = model,
                        timezoneId = profile.timezoneId,
                        locationContext = profile.modelContext(),
                        feedbackSamples = feedbackBeforeTarget
                    )
                }

                walkForward?.let { prediction ->
                    db.savePendingPrediction(
                        locationKey = locationKey,
                        targetTimestampSec = prediction.targetTimestampSec,
                        predictedTempC = prediction.predictedTempC,
                        predictedAtSec = prediction.predictionMadeAtSec,
                        localHour = prediction.localHour,
                        temperatureTrendC = prediction.temperatureTrendC,
                        cloudCoverPct = prediction.cloudCoverPct,
                        solarRadiationWm2 = prediction.solarRadiationWm2,
                        weatherCode = prediction.weatherCode,
                        verificationMethod = PredictionVerificationMethod.WALK_FORWARD_REPLAY
                    )
                }

                // Newly fetched targets are inserted only after their prediction is saved.
                // Existing targets stay in place; both cases can now be reconciled to truth.
                if (existingTimestamps.add(reading.timestampSec)) {
                    uniqueForecastRows += db.insertReadingsIfAbsent(locationKey, listOf(reading))
                }
                db.reconcilePendingPredictions(locationKey)

                walkForwardHistory += reading
                if (walkForwardHistory.size > 72) walkForwardHistory.removeAt(0)
            }

            // Older rows returned at the rolling-window boundary can be safely retained as
            // observations, but are not treated as targets in the 24-hour verification replay.
            downloadedRecentRows
                .filter { it.timestampSec < replayCutoffSec && existingTimestamps.add(it.timestampSec) }
                .sortedBy { it.timestampSec }
                .forEach { reading ->
                    uniqueForecastRows += db.insertReadingsIfAbsent(locationKey, listOf(reading))
                }

            db.reconcilePendingPredictions(locationKey)
            prefs.edit().putLong(AppPreferences.syncTimeKey(locationKey), Instant.now().epochSecond).apply()
            return uniqueForecastRows
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

    /**
     * Import and learn from the complete historical source first. The recent Forecast API
     * refresh is a separate best-effort step; it can add newer hours but cannot block learning
     * from the already-validated JSON/CSV historical archive.
     */
    @Throws(Exception::class)
    fun sync(
        context: Context,
        location: String,
        selectedProfile: LocationProfile? = null,
        onArchiveProgress: (ArchiveDownloadProgress) -> Unit = {},
        onTrainingProgress: (TrainingProgress) -> Unit = {}
    ): SyncResult {
        // The complete historical archive remains cached locally for the UI and recovery.
        // It is transferred to D1 once, in resumable pages, so Cloudflare owns all training.
        val downloaded = downloadHistoricalDataset(context, location, selectedProfile, onArchiveProgress)
        val database = TemperatureDatabase(context)
        try {
            onTrainingProgress(TrainingProgress(3, "Connecting to the TempLearn cloud engine…"))
            val cloud = CloudSyncClient.bootstrapAndSync(
                context = context,
                profile = downloaded.locationProfile,
                database = database,
                onProgress = onTrainingProgress
            )

            // Only discard the temporary download after the complete archive is safely in
            // the cloud and the cloud has produced a usable trained model. SQLite stays intact.
            if (cloud.bootstrapComplete && cloud.modelReady &&
                downloaded.initialHistoryComplete &&
                downloaded.localArchivePath.endsWith(".json", ignoreCase = true)
            ) {
                val historicalJson = File(downloaded.localArchivePath)
                if (historicalJson.isFile) historicalJson.delete()
            }

            val latestTimestamp = database.getLatestTimestampSec(downloaded.locationKey)
            val endDate = latestTimestamp?.let {
                Instant.ofEpochSecond(it)
                    .atZone(ZoneId.of(downloaded.locationProfile.timezoneId))
                    .toLocalDate()
                    .toString()
            } ?: downloaded.importedToDate

            return SyncResult(
                locationKey = downloaded.locationKey,
                fetchedHours = downloaded.fetchedHours + cloud.newlyImportedHours,
                savedReadings = database.getSavedCount(downloaded.locationKey),
                trainingExamples = cloud.trainingExamples,
                timezoneId = downloaded.locationProfile.timezoneId,
                modelReady = cloud.modelReady,
                resolvedAddress = downloaded.locationProfile.displayName,
                locationCount = database.getLocationCount(),
                sharedTrainingExamples = cloud.sharedTrainingExamples,
                initialHistoryComplete = downloaded.initialHistoryComplete,
                initialImportCompletedNow = downloaded.initialImportCompletedNow,
                importedFromDate = downloaded.importedFromDate,
                importedToDate = endDate,
                locationProfile = downloaded.locationProfile,
                recentUpdateWarning = null
            )
        } finally {
            database.close()
        }
    }

    /**
     * Preserve the forecast from the currently stored weather state before the rolling refresh
     * imports newer hours. This lets that refresh verify a prediction made from the prior state.
     */
    private fun storeProspectivePredictionBeforeRefresh(
        context: Context,
        locationKey: String,
        locationProfile: LocationProfile
    ) {
        val database = TemperatureDatabase(context.applicationContext)
        try {
            val readings = database.getLatestReadings(locationKey, 72)
            val model = database.getModel(locationKey)
                ?: database.getModel(AppPreferences.SHARED_MODEL_KEY)
                ?: return

            predictAndStoreNextHour(
                database = database,
                locationKey = locationKey,
                readings = readings,
                model = model,
                timezoneId = locationProfile.timezoneId,
                locationContext = locationProfile.modelContext()
            )
        } finally {
            database.close()
        }
    }

    private fun TemperatureDatabase.useCountLocationsSafely(): Int = try {
        getLocationCount()
    } finally {
        close()
    }
}
