package com.example.tempalearn

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale
import java.util.concurrent.Executors

/** First-run setup: download historical data and pre-train as two separate, visible actions. */
class FirstLaunchSetupActivity : Activity() {
    private val prefs by lazy { getSharedPreferences(AppPreferences.PREFS_NAME, Context.MODE_PRIVATE) }
    private val worker = Executors.newSingleThreadExecutor()
    private val bg = Color.rgb(243, 247, 245)
    private val ink = Color.rgb(23, 47, 44)
    private val muted = Color.rgb(105, 126, 121)
    private val green = Color.rgb(8, 127, 107)

    private lateinit var locationInput: EditText
    private lateinit var locationStatus: TextView
    private lateinit var stationStatus: TextView
    private lateinit var stationMode: RadioGroup
    private lateinit var downloadProgress: ProgressBar
    private lateinit var downloadStatus: TextView
    private lateinit var downloadButton: Button
    private lateinit var trainProgress: ProgressBar
    private lateinit var trainStatus: TextView
    private lateinit var trainButton: Button
    private lateinit var finishButton: Button

    @Volatile private var taskRunning = false
    private var selectedProfile: LocationProfile? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(18, 59, 56)
        window.navigationBarColor = bg
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        buildPage()
        restoreState()
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdown()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { finishSetup() }

    private fun buildPage() {
        val scroll = ScrollView(this).apply { setBackgroundColor(bg); clipToPadding = false; isFillViewport = true }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(32))
        }
        scroll.addView(page, ScrollView.LayoutParams(-1, -2))
        setContentView(scroll)

        addText(page, "T E M P L E A R N · FIRST-TIME SETUP", 10.5f, green, true)
        addText(page, "Build your local\nweather learner.", 29f, ink, true).apply {
            setPadding(0, dp(10), 0, dp(7))
            setLineSpacing(dp(1).toFloat(), 1f)
        }
        addText(
            page,
            "Choose a place and a nearby observation station. Download the historical dataset first, then pre-train the model using the data saved on this phone. Both steps have their own progress.",
            13.5f, muted, false
        ).setLineSpacing(dp(3).toFloat(), 1f)

        val placeCard = card()
        page.addView(placeCard, cardParams())
        addText(placeCard, "1 · WEATHER LOCATION", 10.5f, muted, true)
        locationInput = EditText(this).apply {
            setText(prefs.getString(AppPreferences.KEY_LOCATION, AppPreferences.DEFAULT_LOCATION))
            hint = "e.g. Katoomba, NSW, Australia"
            textSize = 15f
            setTextColor(ink)
            setHintTextColor(muted)
            setSingleLine(true)
            setPadding(dp(12), 0, dp(10), 0)
            background = rounded(Color.rgb(246, 249, 247), 12, Color.rgb(221, 232, 227))
        }
        placeCard.addView(locationInput, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(9) })
        placeCard.addView(button("Find and save location", true) { searchPlace() }, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(8) })
        locationStatus = addText(placeCard, "Choose a specific place, such as Katoomba, NSW, Australia.", 11.5f, muted, false)
        locationStatus.setPadding(0, dp(8), 0, 0)

        val stationCard = card()
        page.addView(stationCard, cardParams())
        addText(stationCard, "2 · OBSERVATION STATION", 10.5f, muted, true)
        addText(
            stationCard,
            "This is a separate BOM station reference for recent observed weather. Automatic mode prefers a nearby station with hourly coverage; manual mode lets you choose a specific station. It does not change the Open-Meteo historical grid data.",
            11.5f, muted, false
        ).apply { setPadding(0, dp(6), 0, dp(8)); setLineSpacing(dp(2).toFloat(), 1f) }
        stationMode = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val automatic = RadioButton(this).apply {
            id = View.generateViewId()
            text = "Automatic · nearest suitable reporting station"
            textSize = 13f
            setTextColor(ink)
        }
        val manual = RadioButton(this).apply {
            id = View.generateViewId()
            text = "Manual · choose a station"
            textSize = 13f
            setTextColor(ink)
        }
        stationMode.addView(automatic)
        stationMode.addView(manual)
        stationCard.addView(stationMode, LinearLayout.LayoutParams(-1, -2))
        stationMode.check(if (prefs.getString(AppPreferences.KEY_STATION_MODE, "AUTO") == "MANUAL") manual.id else automatic.id)
        stationMode.setOnCheckedChangeListener { _, id ->
            prefs.edit().putString(AppPreferences.KEY_STATION_MODE, if (id == manual.id) "MANUAL" else "AUTO").apply()
            stationStatus.text = if (id == manual.id) "Manual mode selected. Choose a station below." else "Automatic mode selected. Find the nearest suitable station."
        }
        stationCard.addView(button("Find / choose station", false) { chooseStation() }, LinearLayout.LayoutParams(-1, dp(45)).apply { topMargin = dp(5) })
        stationStatus = addText(stationCard, "No station selected yet.", 11.5f, muted, false)
        stationStatus.setPadding(0, dp(8), 0, 0)

        val downloadCard = card()
        page.addView(downloadCard, cardParams())
        addText(downloadCard, "3 · DOWNLOAD HISTORICAL DATASET", 10.5f, muted, true)
        addText(
            downloadCard,
            "Open-Meteo Best Match · hourly reanalysis · 1 January 2000 to today. The archive is requested in 14-day chunks, saved after each response, and resumes from the latest stored date if a connection fails.",
            11.5f, muted, false
        ).apply { setPadding(0, dp(6), 0, dp(9)); setLineSpacing(dp(2).toFloat(), 1f) }
        downloadProgress = progressBar()
        downloadCard.addView(downloadProgress, LinearLayout.LayoutParams(-1, dp(8)))
        downloadStatus = addText(downloadCard, "Historical dataset has not been downloaded yet.", 11.5f, muted, false)
        downloadStatus.setPadding(0, dp(8), 0, dp(8))
        downloadButton = button("Download historical dataset", true) { downloadHistory() }
        downloadCard.addView(downloadButton, LinearLayout.LayoutParams(-1, dp(48)))

        val trainingCard = card()
        page.addView(trainingCard, cardParams())
        addText(trainingCard, "4 · PRE-TRAIN THE MODEL", 10.5f, muted, true)
        addText(
            trainingCard,
            "This uses the historical dataset already stored on your phone. It makes no weather API requests. Start pre-training only after the archive download is complete.",
            11.5f, muted, false
        ).apply { setPadding(0, dp(6), 0, dp(9)); setLineSpacing(dp(2).toFloat(), 1f) }
        trainProgress = progressBar()
        trainingCard.addView(trainProgress, LinearLayout.LayoutParams(-1, dp(8)))
        trainStatus = addText(trainingCard, "Waiting for the historical download to finish.", 11.5f, muted, false)
        trainStatus.setPadding(0, dp(8), 0, dp(8))
        trainButton = button("Pre-train with historical dataset", false) { pretrainHistory() }
        trainButton.isEnabled = false
        trainButton.alpha = 0.45f
        trainingCard.addView(trainButton, LinearLayout.LayoutParams(-1, dp(48)))

        finishButton = button("Finish setup", true) { finishSetup() }
        page.addView(finishButton, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(2) })
        page.addView(button("Do this later", false) { finishSetup() }, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(5) })
    }

    private fun restoreState() {
        val location = prefs.getString(AppPreferences.KEY_LOCATION, AppPreferences.DEFAULT_LOCATION) ?: AppPreferences.DEFAULT_LOCATION
        val key = AppPreferences.activeLocationKey(prefs, location)
        val db = TemperatureDatabase(this)
        try {
            selectedProfile = db.getLocationProfile(key)
            selectedProfile?.let { locationStatus.text = "Selected: " + it.displayName }
            if (prefs.getBoolean(AppPreferences.historyCompleteKey(key), false)) {
                downloadProgress.progress = 100
                downloadStatus.text = "Historical dataset already downloaded · " + db.getSavedCount(key) + " stored hourly rows."
                downloadButton.text = "Update historical dataset"
                trainButton.isEnabled = true
                trainButton.alpha = 1f
            }
            val model = db.getModel(key)
            if (model != null) {
                trainProgress.progress = 100
                trainStatus.text = "A local model is already trained · " + model.trainingExamples + " examples."
            }
        } finally { db.close() }
        val stationName = prefs.getString(AppPreferences.KEY_STATION_NAME, null)
        val stationId = prefs.getString(AppPreferences.KEY_STATION_WMO_ID, null)
        if (!stationName.isNullOrBlank() && !stationId.isNullOrBlank()) {
            stationStatus.text = prefs.getString(AppPreferences.KEY_STATION_MODE, "AUTO") + " · " + stationName + " · WMO " + stationId
        }
    }

    private fun searchPlace() {
        val query = locationInput.text.toString().trim()
        if (query.length < 2) { locationInput.error = "Enter a postcode, town or address."; return }
        runTask(locationStatus, "Searching Open-Meteo Geocoding…") {
            val places = LocationGeocodingClient.search(query)
            runOnUiThread {
                if (places.isEmpty()) {
                    locationStatus.text = "No matching place found. Include the state and country."
                } else {
                    AlertDialog.Builder(this)
                        .setTitle("Choose your location")
                        .setItems(places.map { it.selectionLabel() }.toTypedArray()) { _, index -> savePlace(query, places[index]) }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
        }
    }

    private fun savePlace(query: String, place: LocationGeocodingClient.Place) {
        runTask(locationStatus, "Saving " + place.displayName + "…") {
            val elevation = place.elevationM ?: runCatching { OpenMeteoClient.fetchElevation(place.latitude, place.longitude) }.getOrNull()
            val key = AppPreferences.geocodedLocationKey(place.id)
            val profile = place.toProfile(key, query, elevation)
            val db = TemperatureDatabase(this)
            try { db.saveLocationProfile(profile) } finally { db.close() }
            prefs.edit()
                .putString(AppPreferences.KEY_LOCATION, query)
                .putString(AppPreferences.KEY_ACTIVE_LOCATION_KEY, key)
                .putString(AppPreferences.timezoneKey(key), profile.timezoneId)
                .putString(AppPreferences.resolvedAddressKey(key), profile.displayName)
                .putString(AppPreferences.latitudeKey(key), profile.latitude.toString())
                .putString(AppPreferences.longitudeKey(key), profile.longitude.toString())
                .putString(AppPreferences.elevationKey(key), profile.elevationM?.toString())
                .putString(AppPreferences.KEY_STATION_MODE, "AUTO")
                .remove(AppPreferences.KEY_STATION_WMO_ID)
                .remove(AppPreferences.KEY_STATION_NAME)
                .remove(AppPreferences.KEY_STATION_DISTANCE_KM)
                .remove(AppPreferences.KEY_STATION_TEMP_C)
                .remove(AppPreferences.KEY_STATION_TIME_SEC)
                .apply()
            selectedProfile = profile
            runOnUiThread {
                stationMode.check(stationMode.getChildAt(0).id)
                stationStatus.text = "Location saved. Find a nearby BOM station or choose one manually."
                locationStatus.text = String.format(Locale.getDefault(), "Selected: %s · %.4f°, %.4f°", profile.displayName, profile.latitude, profile.longitude)
                downloadStatus.text = "Ready to download historical weather for " + profile.displayName + "."
            }
        }
    }

    private fun chooseStation() {
        val profile = selectedProfile
        if (profile == null) { stationStatus.text = "Choose and save a location first."; return }
        val manual = stationMode.checkedRadioButtonId == stationMode.getChildAt(1).id
        if (!manual) {
            runTask(stationStatus, "Finding the nearest suitable hourly-reporting BOM station…") {
                val result = BomClient.fetchRecentObservations(
                    profile.latitude, profile.longitude, profile.displayName + ", " + profile.searchQuery, profile.timezoneId
                ) ?: throw IllegalStateException("No nearby BOM station with usable recent observations was found.")
                saveStation(result, "AUTO")
                runOnUiThread { stationStatus.text = stationLabel(result, "Automatic") }
            }
        } else {
            runTask(stationStatus, "Loading nearby BOM stations…") {
                val choices = BomClient.listNearbyStations(
                    profile.latitude, profile.longitude, profile.displayName + ", " + profile.searchQuery
                )
                runOnUiThread {
                    if (choices.isEmpty()) {
                        stationStatus.text = "No nearby BOM stations were returned for this region."
                    } else {
                        AlertDialog.Builder(this)
                            .setTitle("Choose a nearby BOM station")
                            .setItems(choices.map { it.label() }.toTypedArray()) { _, index ->
                                val station = choices[index]
                                runTask(stationStatus, "Checking " + station.name + " · WMO " + station.wmoId + "…") {
                                    val result = BomClient.fetchRecentObservations(
                                        profile.latitude, profile.longitude, profile.displayName + ", " + profile.searchQuery,
                                        profile.timezoneId, preferredStationWmoId = station.wmoId
                                    ) ?: throw IllegalStateException("The selected station returned no usable recent observations.")
                                    saveStation(result, "MANUAL")
                                    runOnUiThread { stationStatus.text = stationLabel(result, "Manual") }
                                }
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                }
            }
        }
    }

    private fun saveStation(result: BomClient.Result, mode: String) {
        val latest = result.observationsByHour.maxByOrNull { it.key }
        prefs.edit()
            .putString(AppPreferences.KEY_STATION_MODE, mode)
            .putString(AppPreferences.KEY_STATION_WMO_ID, result.stationWmoId)
            .putString(AppPreferences.KEY_STATION_NAME, result.stationName)
            .putFloat(AppPreferences.KEY_STATION_DISTANCE_KM, result.distanceKm.toFloat())
            .putFloat(AppPreferences.KEY_STATION_TEMP_C, latest?.value?.temperatureC?.toFloat() ?: Float.NaN)
            .putLong(AppPreferences.KEY_STATION_TIME_SEC, latest?.key ?: 0L)
            .apply()
    }

    private fun stationLabel(result: BomClient.Result, mode: String): String {
        val latest = result.observationsByHour.maxByOrNull { it.key }
        val last = latest?.let { " · latest " + String.format(Locale.getDefault(), "%.1f°C", it.value.temperatureC) }.orEmpty()
        return mode + " · " + result.stationName + " · WMO " + result.stationWmoId + " · " +
            String.format(Locale.getDefault(), "%.1f km", result.distanceKm) + last
    }

    private fun downloadHistory() {
        val profile = selectedProfile
        val location = prefs.getString(AppPreferences.KEY_LOCATION, null)?.takeIf { it.isNotBlank() }
        if (profile == null || location == null) {
            locationStatus.text = "Choose and save a weather location before downloading."
            return
        }
        if (taskRunning) return
        setTaskRunning(true)
        downloadStatus.text = "Preparing historical download in 14-day chunks…"
        worker.execute {
            try {
                val result = SyncRepository.downloadHistoricalDataset(this, location, profile) { progress ->
                    runOnUiThread {
                        downloadProgress.progress = progress.percent
                        downloadStatus.text = String.format(
                            Locale.getDefault(), "%d%% · %d of %d chunks · %s to %s · %d rows in this chunk",
                            progress.percent, progress.completedChunks, progress.totalChunks,
                            progress.chunkStart, progress.chunkEnd, progress.rowsThisChunk
                        )
                    }
                }
                runOnUiThread {
                    setTaskRunning(false)
                    downloadProgress.progress = 100
                    downloadStatus.text = "Download complete · " + result.savedReadings + " saved hourly rows · " +
                        result.importedFromDate + " to " + result.importedToDate + "."
                    trainButton.isEnabled = true
                    trainButton.alpha = 1f
                    trainStatus.text = "Historical dataset is ready. Start pre-training when you are ready."
                    selectedProfile = result.locationProfile
                }
            } catch (error: Exception) {
                runOnUiThread {
                    setTaskRunning(false)
                    val detail = error.message ?: "check your connection and retry"
                    downloadStatus.text = if (detail.contains("HTTP 429")) {
                        "HTTP 429 · Open-Meteo rate-limited this request. Completed chunks are saved; wait before retrying. The next attempt resumes from the last stored date."
                    } else {
                        "Download paused/failed: " + detail + ". Completed chunks are saved; retry resumes from the last stored date."
                    }
                    val key = AppPreferences.activeLocationKey(prefs, location)
                    if (prefs.getBoolean(AppPreferences.historyCompleteKey(key), false)) {
                        trainButton.isEnabled = true
                        trainButton.alpha = 1f
                    }
                }
            }
        }
    }

    private fun pretrainHistory() {
        val location = prefs.getString(AppPreferences.KEY_LOCATION, null)?.takeIf { it.isNotBlank() } ?: return
        val key = AppPreferences.activeLocationKey(prefs, location)
        if (!prefs.getBoolean(AppPreferences.historyCompleteKey(key), false)) {
            trainStatus.text = "Complete the historical download before pre-training."
            return
        }
        if (taskRunning) return
        setTaskRunning(true)
        trainStatus.text = "Preparing locally stored training data…"
        worker.execute {
            try {
                val result = SyncRepository.pretrainHistoricalDataset(this, location) { progress ->
                    runOnUiThread {
                        trainProgress.progress = progress.percent
                        trainStatus.text = progress.percent.toString() + "% · " + progress.message
                    }
                }
                runOnUiThread {
                    setTaskRunning(false)
                    trainProgress.progress = 100
                    trainStatus.text = if (result.modelReady) {
                        "Pre-training complete · " + result.trainingExamples + " local examples · " +
                            result.sharedTrainingExamples + " shared examples. No weather API calls were made."
                    } else {
                        "The archive is saved, but there are not enough consecutive valid temperature hours for a model yet."
                    }
                    trainButton.isEnabled = true
                    trainButton.alpha = 1f
                }
            } catch (error: Exception) {
                runOnUiThread {
                    setTaskRunning(false)
                    trainStatus.text = "Pre-training failed: " + (error.message ?: "please try again")
                    trainButton.isEnabled = true
                    trainButton.alpha = 1f
                }
            }
        }
    }

    private fun setTaskRunning(running: Boolean) {
        taskRunning = running
        downloadButton.isEnabled = !running
        downloadButton.alpha = if (running) 0.55f else 1f
        val location = prefs.getString(AppPreferences.KEY_LOCATION, AppPreferences.DEFAULT_LOCATION) ?: AppPreferences.DEFAULT_LOCATION
        val complete = prefs.getBoolean(AppPreferences.historyCompleteKey(AppPreferences.activeLocationKey(prefs, location)), false)
        trainButton.isEnabled = !running && complete
        trainButton.alpha = if (trainButton.isEnabled) 1f else 0.45f
        finishButton.isEnabled = !running
    }

    private fun runTask(status: TextView, loading: String, action: () -> Unit) {
        if (taskRunning) return
        setTaskRunning(true)
        status.text = loading
        worker.execute {
            try {
                action()
            } catch (error: Exception) {
                runOnUiThread { status.text = "Could not complete this step: " + (error.message ?: "please try again") }
            } finally {
                runOnUiThread { setTaskRunning(false) }
            }
        }
    }

    private fun finishSetup() {
        if (taskRunning) return
        prefs.edit().putBoolean(AppPreferences.KEY_INITIAL_SETUP_COMPLETE, true).apply()
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun progressBar() = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 100
        progress = 0
    }

    private fun button(label: String, primary: Boolean, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 14f
        setTextColor(if (primary) Color.WHITE else green)
        background = if (primary) rounded(green, 13) else rounded(Color.WHITE, 13, Color.rgb(221, 232, 227))
        minHeight = dp(46)
        stateListAnimator = null
        setOnClickListener { action() }
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(17), dp(17), dp(17), dp(17))
        background = rounded(Color.WHITE, 20)
        elevation = dp(1).toFloat()
    }

    private fun cardParams() = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) }

    private fun addText(parent: LinearLayout, value: String, size: Float, color: Int, bold: Boolean) =
        TextView(this).apply {
            text = value
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, size)
            setTextColor(color)
            typeface = if (bold) Typeface.create("sans-serif-medium", Typeface.BOLD) else Typeface.create("sans-serif", Typeface.NORMAL)
            includeFontPadding = true
            parent.addView(this, LinearLayout.LayoutParams(-1, -2))
        }

    private fun rounded(fill: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
