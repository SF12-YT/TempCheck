package com.example.tempalearn

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.text.method.LinkMovementMethod
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

class MainActivity : Activity() {
    private val backgroundColor = Color.rgb(243, 247, 245)
    private val inkColor = Color.rgb(23, 47, 44)
    private val mutedColor = Color.rgb(105, 126, 121)
    private val greenColor = Color.rgb(8, 127, 107)
    private val prefs by lazy {
        getSharedPreferences(AppPreferences.PREFS_NAME, Context.MODE_PRIVATE)
    }
    private val executor = Executors.newSingleThreadExecutor()

    private lateinit var rootContainer: FrameLayout
    private lateinit var mainScreen: ScrollView
    private lateinit var settingsScreen: ScrollView
    private lateinit var locationInput: EditText
    private lateinit var locationSummaryText: TextView
    private lateinit var syncButton: Button
    private lateinit var statusText: TextView
    private lateinit var predictionText: TextView
    private lateinit var predictionSubtext: TextView
    private lateinit var storedCountText: TextView
    private lateinit var trainingCountText: TextView
    private lateinit var accuracyText: TextView
    private lateinit var accuracyDetailsText: TextView
    private lateinit var sharedModelText: TextView
    private lateinit var chartView: TemperatureChartView
    private lateinit var historyRows: LinearLayout
    private lateinit var historyCountText: TextView

    @Volatile private var syncInProgress = false
    private var uiBuilt = false
    private var pendingModelExportZip: ByteArray? = null
    private var pendingModelExportFilename: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(18, 59, 56)
        window.navigationBarColor = backgroundColor
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR

        if (!prefs.contains(AppPreferences.KEY_LOCATION)) {
            prefs.edit().putString(AppPreferences.KEY_LOCATION, AppPreferences.DEFAULT_LOCATION).apply()
        }
        NotificationHelper.createChannel(this)
        if (!prefs.getBoolean(AppPreferences.KEY_INITIAL_SETUP_COMPLETE, false)) {
            startActivity(Intent(this, FirstLaunchSetupActivity::class.java))
            finish()
            return
        }
        buildInterface()
        uiBuilt = true
        scheduleDailySync()
        refreshDashboard()
        requestNotificationPermissionIfNeeded()
        if (shouldAutoSync()) syncNow(manual = false)
    }

    override fun onResume() {
        super.onResume()
        if (uiBuilt) refreshDashboard()
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
    }

    private fun buildInterface() {
        rootContainer = FrameLayout(this)
        mainScreen = ScrollView(this).apply {
            setBackgroundColor(backgroundColor)
            clipToPadding = false
            isFillViewport = true
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(30))
        }
        mainScreen.addView(content, FrameLayout.LayoutParams(-1, -2))
        rootContainer.addView(mainScreen, FrameLayout.LayoutParams(-1, -1))

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        content.addView(header, LinearLayout.LayoutParams(-1, -2))
        addText(header, "T E M P L E A R N · WEATHER LEARNING", 10.5f, greenColor, true).apply {
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        val settingsButton = Button(this).apply {
            text = "Settings"
            isAllCaps = false
            textSize = 12f
            setTextColor(greenColor)
            background = roundedDrawable(Color.WHITE, 12, Color.rgb(221, 232, 227))
            minHeight = dp(42)
            stateListAnimator = null
            setPadding(dp(12), 0, dp(12), 0)
            setOnClickListener { showSettings(true) }
        }
        header.addView(settingsButton, LinearLayout.LayoutParams(-2, dp(42)).apply { marginStart = dp(8) })

        addText(content, "Weather has\npatterns.", 30f, inkColor, true).apply {
            setLineSpacing(dp(1).toFloat(), 1.0f)
            setPadding(0, dp(9), 0, dp(8))
        }
        addText(
            content,
            "Collect hourly weather, learn how conditions relate to temperature, and keep each location's history on this phone.",
            14f,
            mutedColor,
            false
        ).apply { setLineSpacing(dp(3).toFloat(), 1.0f) }
        addSpacer(content, 20)

        val locationCard = newCard()
        content.addView(locationCard, cardLayoutParams())
        addText(locationCard, "CURRENT LOCATION", 11f, mutedColor, true)
        locationSummaryText = addText(
            locationCard,
            prefs.getString(AppPreferences.KEY_LOCATION, AppPreferences.DEFAULT_LOCATION)
                ?: AppPreferences.DEFAULT_LOCATION,
            17f,
            inkColor,
            true
        ).apply { setPadding(0, dp(7), 0, dp(3)) }
        addText(locationCard, "Change the postcode, town, or address in Settings.", 12f, mutedColor, false)
            .setPadding(0, dp(2), 0, 0)

        syncButton = Button(this).apply {
            text = "Refresh weather & learn"
            isAllCaps = false
            textSize = 15f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = roundedDrawable(greenColor, 15)
            minHeight = dp(52)
            stateListAnimator = null
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener { syncNow(manual = true) }
        }
        locationCard.addView(syncButton, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(15) })
        statusText = addText(locationCard, "Ready. Weather readings and models stay on this phone.", 12f, mutedColor, false)
        statusText.setPadding(0, dp(10), 0, 0)

        val predictionCard = newCard(Color.rgb(18, 59, 56)).apply { setPadding(dp(19), dp(19), dp(19), dp(18)) }
        content.addView(predictionCard, cardLayoutParams())
        addText(predictionCard, "MODEL'S NEXT-HOURLY TEMPERATURE ESTIMATE", 11f, Color.rgb(153, 219, 203), true)
        predictionText = addText(predictionCard, "— °C", 36f, Color.WHITE, true).apply {
            setPadding(0, dp(7), 0, dp(2))
        }
        predictionSubtext = addText(predictionCard, "Fetch weather to start or update the learning model.", 12f, Color.rgb(207, 226, 220), false)
        predictionSubtext.setLineSpacing(dp(2).toFloat(), 1.0f)

        val statRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        predictionCard.addView(statRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(17) })
        val savedStat = metricBlock("STORED HOURS", "0")
        val trainingStat = metricBlock("LOCAL EXAMPLES", "0")
        val accuracyStat = metricBlock("ACCURACY", "—")
        storedCountText = savedStat.second
        trainingCountText = trainingStat.second
        accuracyText = accuracyStat.second
        statRow.addView(savedStat.first, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(7) })
        statRow.addView(trainingStat.first, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(4); marginEnd = dp(4) })
        statRow.addView(accuracyStat.first, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(7) })
        accuracyDetailsText = addText(
            predictionCard,
            "Accuracy uses held-out hours; signed bias shows whether estimates tend to run warm or cool.",
            10.5f,
            Color.rgb(207, 226, 220),
            false
        ).apply { setPadding(0, dp(8), 0, 0) }
        sharedModelText = addText(
            predictionCard,
            "Shared weather patterns will be learned as data accumulates across locations.",
            10.5f,
            Color.rgb(207, 226, 220),
            false
        ).apply { setPadding(0, dp(8), 0, 0) }

        val historyCard = newCard()
        content.addView(historyCard, cardLayoutParams())
        val historyHeading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        historyCard.addView(historyHeading, LinearLayout.LayoutParams(-1, -2))
        addText(historyHeading, "Last 24 hours", 18f, inkColor, true).apply {
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        historyCountText = addText(historyHeading, "0 POINTS", 10f, mutedColor, true)
        chartView = TemperatureChartView(this)
        historyCard.addView(chartView, LinearLayout.LayoutParams(-1, dp(158)).apply { topMargin = dp(12) })
        addText(historyCard, "OLDER  ←  HOURLY TEMPERATURES  →  LATEST", 9f, mutedColor, true)
            .setPadding(0, dp(2), 0, dp(12))
        historyRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        historyCard.addView(historyRows, LinearLayout.LayoutParams(-1, -2))

        val attribution = "Historical hourly temperature targets and predictors: Open-Meteo Historical Weather API, Best Match reanalysis. Missing values remain missing; no other provider fills them. The initial history is imported from 1 January 2000 once per location, then only the latest local date is refreshed. Weather data by Open-Meteo.com (CC BY 4.0). Reanalysis is modelled historical weather, not a local station observation. Validation uses later unseen hours and is not a guarantee of forecast accuracy."
        addText(content, attribution, 11f, mutedColor, false).apply {
            val attributedText = android.text.SpannableString(attribution)
            val providerStart = attribution.indexOf("Open-Meteo.com")
            if (providerStart >= 0) {
                attributedText.setSpan(
                    android.text.style.URLSpan("https://open-meteo.com"),
                    providerStart,
                    providerStart + "Open-Meteo.com".length,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            val licenseStart = attribution.indexOf("CC BY 4.0")
            if (licenseStart >= 0) {
                attributedText.setSpan(
                    android.text.style.URLSpan("https://creativecommons.org/licenses/by/4.0/"),
                    licenseStart,
                    licenseStart + "CC BY 4.0".length,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            text = attributedText
            movementMethod = LinkMovementMethod.getInstance()
            linksClickable = true
            setLineSpacing(dp(2).toFloat(), 1.0f)
            setPadding(dp(2), dp(1), dp(2), dp(6))
        }

        buildSettingsScreen(rootContainer)
        setContentView(rootContainer)
    }

    private fun buildSettingsScreen(root: FrameLayout) {
        settingsScreen = ScrollView(this).apply {
            setBackgroundColor(backgroundColor)
            clipToPadding = false
            isFillViewport = true
            visibility = View.GONE
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(30))
        }
        settingsScreen.addView(content, FrameLayout.LayoutParams(-1, -2))
        root.addView(settingsScreen, FrameLayout.LayoutParams(-1, -1))

        addText(content, "T E M P L E A R N · SETTINGS", 11f, greenColor, true)
        addText(content, "Choose your location.", 28f, inkColor, true).apply {
            setPadding(0, dp(10), 0, dp(7))
        }
        addText(
            content,
            "Search for a postcode, town or address, then choose the matching place. TempLearn saves its coordinates, elevation and timezone and keeps each location's Open-Meteo history and local model separate.",
            14f,
            mutedColor,
            false
        ).apply { setLineSpacing(dp(3).toFloat(), 1.0f) }
        addSpacer(content, 16)

        val locationCard = newCard()
        content.addView(locationCard, cardLayoutParams())
        addText(locationCard, "WEATHER LOCATION", 11f, mutedColor, true)
        locationInput = createLocationInput(
            prefs.getString(AppPreferences.KEY_LOCATION, AppPreferences.DEFAULT_LOCATION)
                ?: AppPreferences.DEFAULT_LOCATION
        )
        locationCard.addView(locationInput, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(9) })
        addText(
            locationCard,
            "Examples: 2780, NSW, Australia · Katoomba, NSW, Australia · Perth, WA, Australia. Geocoding supplies the canonical coordinates, elevation and timezone. Open-Meteo Best Match supplies all 27 hourly training variables.",
            12f,
            mutedColor,
            false
        ).apply { setPadding(0, dp(8), 0, 0) }

        val saveButton = Button(this).apply {
            text = "Save location & refresh"
            isAllCaps = false
            textSize = 15f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = roundedDrawable(greenColor, 15)
            minHeight = dp(52)
            stateListAnimator = null
            setOnClickListener {
                val newLocation = locationInput.text.toString().trim()
                if (newLocation.length < 2) {
                    locationInput.error = "Enter a postcode, town or address."
                    return@setOnClickListener
                }
                searchAndSelectLocation(newLocation, this)
            }
        }
        content.addView(saveButton, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(5); bottomMargin = dp(10) })
        addSpacer(content, 18)
        val modelCard = newCard()
        content.addView(modelCard, cardLayoutParams())
        addText(modelCard, "PORTABLE MACHINE-LEARNING MODEL", 11f, mutedColor, true)
        addText(
            modelCard,
            "Export a complete, portable model package: learned parameters, 53-feature schema, preprocessing, ridge-regression algorithm, inference contract, Python inference and training code, evaluation metrics, training coverage and geographic metadata. It opens independently of TempLearn and contains no API keys or raw hourly history.",
            12f,
            mutedColor,
            false
        ).apply { setPadding(0, dp(7), 0, dp(10)); setLineSpacing(dp(2).toFloat(), 1.0f) }
        val exportLocalButton = Button(this).apply {
            text = "Export this location's model package (.zip)"
            isAllCaps = false
            textSize = 14f
            setTextColor(Color.WHITE)
            background = roundedDrawable(greenColor, 13)
            minHeight = dp(48)
            stateListAnimator = null
            setOnClickListener { beginModelExport(shared = false) }
        }
        modelCard.addView(exportLocalButton, LinearLayout.LayoutParams(-1, dp(48)))
        val exportSharedButton = Button(this).apply {
            text = "Export shared model package (.zip)"
            isAllCaps = false
            textSize = 14f
            setTextColor(greenColor)
            background = roundedDrawable(Color.WHITE, 13, Color.rgb(221, 232, 227))
            minHeight = dp(46)
            stateListAnimator = null
            setOnClickListener { beginModelExport(shared = true) }
        }
        modelCard.addView(exportSharedButton, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(7) })

        val cancelButton = Button(this).apply {
            text = "Cancel"
            isAllCaps = false
            textSize = 14f
            setTextColor(greenColor)
            background = roundedDrawable(Color.WHITE, 15, Color.rgb(221, 232, 227))
            minHeight = dp(48)
            stateListAnimator = null
            setOnClickListener { showSettings(false) }
        }
        content.addView(cancelButton, LinearLayout.LayoutParams(-1, dp(48)))
    }

    private fun showSettings(show: Boolean) {
        if (!::settingsScreen.isInitialized || !::mainScreen.isInitialized) return
        if (show && syncInProgress) {
            statusText.text = "Please let the current refresh finish before changing location."
            return
        }
        if (show) {
            locationInput.setText(
                prefs.getString(AppPreferences.KEY_LOCATION, AppPreferences.DEFAULT_LOCATION)
                    ?: AppPreferences.DEFAULT_LOCATION
            )
            mainScreen.visibility = View.GONE
            settingsScreen.visibility = View.VISIBLE
        } else {
            settingsScreen.visibility = View.GONE
            mainScreen.visibility = View.VISIBLE
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::settingsScreen.isInitialized && settingsScreen.visibility == View.VISIBLE) {
            showSettings(false)
        } else {
            super.onBackPressed()
        }
    }

    private fun createLocationInput(value: String): EditText = EditText(this).apply {
        setText(value)
        hint = "e.g. 2780, NSW, Australia or Katoomba, NSW"
        textSize = 15f
        setTextColor(inkColor)
        setHintTextColor(mutedColor)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        setSingleLine(true)
        setSelectAllOnFocus(true)
        setPadding(dp(12), 0, dp(10), 0)
        background = roundedDrawable(Color.rgb(246, 249, 247), 12, Color.rgb(221, 232, 227))
    }

    private fun metricBlock(label: String, initialValue: String): Pair<LinearLayout, TextView> {
        val block = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(9), dp(10), dp(7), dp(10))
            background = roundedDrawable(Color.rgb(31, 76, 71), 12)
        }
        addText(block, label, 8.5f, Color.rgb(164, 205, 195), true)
        val value = addText(block, initialValue, 17f, Color.WHITE, true).apply {
            setPadding(0, dp(5), 0, 0)
            maxLines = 1
            setAutoSizeTextTypeWithDefaults(TextView.AUTO_SIZE_TEXT_TYPE_UNIFORM)
        }
        return block to value
    }

    private fun refreshDashboard() {
        if (!uiBuilt) return
        val location = prefs.getString(AppPreferences.KEY_LOCATION, AppPreferences.DEFAULT_LOCATION)
            ?.trim().orEmpty().ifBlank { AppPreferences.DEFAULT_LOCATION }
        if (location.length < 2) {
            predictionText.text = "— °C"
            predictionSubtext.text = "Enter a postcode, town, or address, then fetch data."
            chartView.setReadings(emptyList())
            historyRows.removeAllViews()
            historyCountText.text = "0 POINTS"
            storedCountText.text = "0"
            trainingCountText.text = "0"
            accuracyText.text = "—"
            accuracyDetailsText.text = "Not enough held-out predictions yet."
            return
        }

        val locationKey = AppPreferences.activeLocationKey(prefs, location)
        val db = TemperatureDatabase(this)
        try {
            val profile = db.getLocationProfile(locationKey)
            locationSummaryText.text = profile?.displayName ?: location
            val zoneId = profile?.timezoneId ?: (prefs.getString(
                AppPreferences.timezoneKey(locationKey),
                ZoneId.systemDefault().id
            ) ?: ZoneId.systemDefault().id)
            val all = db.getLatestReadings(locationKey, 72)
            val recent = all.takeLast(24)
            val model = db.getModel(locationKey)
            val sharedModel = db.getModel(AppPreferences.SHARED_MODEL_KEY)
            chartView.setReadings(recent)
            storedCountText.text = db.getSavedCount(locationKey).toString()
            trainingCountText.text = model?.trainingExamples?.toString() ?: "0"
            val effectiveModelForMetrics = model ?: sharedModel?.takeIf { profile != null }
            if (effectiveModelForMetrics != null && effectiveModelForMetrics.validationExamples > 0 && effectiveModelForMetrics.validationMaeC.isFinite()) {
                accuracyText.text = String.format(Locale.getDefault(), "±%.1f°", effectiveModelForMetrics.validationMaeC)
                val bias = effectiveModelForMetrics.validationBiasC
                val biasText = when {
                    bias > 0.15 -> String.format(Locale.getDefault(), "+%.1f°C warm bias", bias)
                    bias < -0.15 -> String.format(Locale.getDefault(), "−%.1f°C cool bias", -bias)
                    else -> "no strong warm/cool bias"
                }
                accuracyDetailsText.text = String.format(
                    Locale.getDefault(),
                    "Held-out MAE %.1f°C · %s · %d unseen hours",
                    effectiveModelForMetrics.validationMaeC,
                    biasText,
                    effectiveModelForMetrics.validationExamples
                )
                if (model == null) accuracyDetailsText.text = "Shared-model validation · ${accuracyDetailsText.text}"
            } else {
                accuracyText.text = "—"
                accuracyDetailsText.text = "Not enough held-out hours to estimate accuracy yet."
            }
            historyCountText.text = "${recent.size} POINTS"
            renderHistory(recent, zoneId)

            sharedModelText.text = if (sharedModel != null) {
                "Shared model: ${sharedModel.trainingExamples} training examples across ${sharedModel.trainedLocationCount} geocoded training location(s). New places can borrow these patterns and adapt locally."
            } else {
                "Shared weather model is still learning. The first location builds a wider history; new postcodes can use shared patterns after this model is trained."
            }

            // A newly selected place can borrow the shared geographical model immediately;
            // once enough local hourly history exists, its locally adapted model takes precedence.
            val predictionModel = model ?: sharedModel?.takeIf { profile != null }
            val predicted = predictionModel?.let {
                WeatherLearner.predictNext(all, it, zoneId, profile?.modelContext() ?: ModelLocationContext())
            }
            if (predicted != null) {
                predictionText.text = String.format(Locale.getDefault(), "%.1f °C", predicted.second)
                val formatter = DateTimeFormatter.ofPattern("EEE, d MMM · h:mm a", Locale.US)
                val localTime = Instant.ofEpochSecond(predicted.first).atZone(ZoneId.of(zoneId))
                val modelDescription = if (model != null) "Location-adapted estimate" else "Shared-model estimate · adapting to this location"
                predictionSubtext.text = "$modelDescription · ${formatter.format(localTime)} local time"
            } else {
                predictionText.text = "— °C"
                predictionSubtext.text = if (all.size < 4) {
                    "Fetch hourly weather to begin learning for this postcode."
                } else {
                    "Three consecutive hours are needed to estimate the next point."
                }
            }
        } finally {
            db.close()
        }
    }

    private fun renderHistory(readings: List<HourlyTemperature>, timezoneId: String) {
        historyRows.removeAllViews()
        if (readings.isEmpty()) {
            addText(historyRows, "No readings saved yet. Tap “Refresh weather & learn”.", 13f, mutedColor, false)
                .setPadding(0, dp(4), 0, dp(4))
            return
        }
        val formatter = DateTimeFormatter.ofPattern("EEE · h:mm a", Locale.US)
        val zone = runCatching { ZoneId.of(timezoneId) }.getOrElse { ZoneId.systemDefault() }
        readings.asReversed().forEachIndexed { index, reading ->
            val rowContent = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(9), 0, dp(9))
            }
            val top = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val time = Instant.ofEpochSecond(reading.timestampSec).atZone(zone)
            val timeView = addText(top, formatter.format(time), 12f, mutedColor, false)
            timeView.layoutParams = LinearLayout.LayoutParams(0, -2, 1.0f)
            val temperature = addText(
                top,
                String.format(Locale.getDefault(), "%.1f °C", reading.temperatureC),
                14f,
                inkColor,
                true
            )
            temperature.gravity = Gravity.END
            temperature.layoutParams = LinearLayout.LayoutParams(dp(82), -2)
            rowContent.addView(top, LinearLayout.LayoutParams(-1, -2))
            addText(rowContent, describeOtherWeather(reading), 10.5f, mutedColor, false).apply {
                setPadding(0, dp(3), 0, 0)
                setLineSpacing(dp(1).toFloat(), 1.0f)
            }
            historyRows.addView(rowContent, LinearLayout.LayoutParams(-1, -2))
            if (index != readings.lastIndex) {
                historyRows.addView(View(this).apply { setBackgroundColor(Color.rgb(235, 240, 237)) }, LinearLayout.LayoutParams(-1, dp(1)))
            }
        }
    }

    private fun describeOtherWeather(reading: HourlyTemperature): String {
        val parts = mutableListOf<String>()
        reading.dewPointC?.let { parts += String.format(Locale.getDefault(), "Dew %.1f°C", it) }
        reading.windSpeedKmh?.let { speed ->
            val gust = reading.windGustKmh?.let { String.format(Locale.getDefault(), " (gust %.0f)", it) }.orEmpty()
            parts += String.format(Locale.getDefault(), "Wind %.0f km/h%s", speed, gust)
        }
        reading.pressureHpa?.let { parts += String.format(Locale.getDefault(), "Pressure %.0f hPa", it) }
        reading.humidityPct?.let { parts += String.format(Locale.getDefault(), "RH %.0f%%", it) }
        reading.cloudCoverPct?.let { parts += String.format(Locale.getDefault(), "Cloud %.0f%%", it) }
        reading.precipitationMm?.let { parts += String.format(Locale.getDefault(), "Rain %.1f mm", it) }
        reading.surfacePressureHpa?.let { parts += String.format(Locale.getDefault(), "Surface pressure %.0f hPa", it) }
        reading.solarRadiationWm2?.let { parts += String.format(Locale.getDefault(), "Solar %.0f W/m²", it) }
        reading.apparentTemperatureC?.let { parts += String.format(Locale.getDefault(), "Feels-like %.1f°C", it) }
        reading.wetBulbTemperatureC?.let { parts += String.format(Locale.getDefault(), "Wet bulb %.1f°C", it) }
        reading.visibilityM?.let { parts += String.format(Locale.getDefault(), "Visibility %.1f km", it / 1000.0) }
        reading.showersMm?.let { parts += String.format(Locale.getDefault(), "Showers %.1f mm", it) }
        reading.snowfallCm?.let { parts += String.format(Locale.getDefault(), "Snow %.1f cm", it) }
        reading.cloudLowPct?.let { parts += String.format(Locale.getDefault(), "Low cloud %.0f%%", it) }
        reading.cloudMidPct?.let { parts += String.format(Locale.getDefault(), "Mid cloud %.0f%%", it) }
        reading.cloudHighPct?.let { parts += String.format(Locale.getDefault(), "High cloud %.0f%%", it) }
        reading.windDirectionDeg?.let { parts += String.format(Locale.getDefault(), "Wind direction %.0f°", it) }
        reading.soilTemperatureC?.let { parts += String.format(Locale.getDefault(), "Soil %.1f°C", it) }
        reading.soilMoisture01cm?.let { parts += String.format(Locale.getDefault(), "Soil moisture %.2f", it) }
        reading.vaporPressureDeficitKpa?.let { parts += String.format(Locale.getDefault(), "VPD %.2f kPa", it) }
        reading.evapotranspirationMm?.let { parts += String.format(Locale.getDefault(), "ET %.2f mm", it) }
        reading.et0Mm?.let { parts += String.format(Locale.getDefault(), "ET₀ %.2f mm", it) }
        reading.uvIndex?.let { parts += String.format(Locale.getDefault(), "UV %.1f", it) }
        reading.weatherCode?.let { parts += "WMO $it" }
        parts += "Air temperature: Open-Meteo Best Match"
        return parts.joinToString(" · ")
    }

    private fun syncNow(manual: Boolean) {
        if (syncInProgress) return
        val location = prefs.getString(AppPreferences.KEY_LOCATION, AppPreferences.DEFAULT_LOCATION)
            ?.trim().orEmpty().ifBlank { AppPreferences.DEFAULT_LOCATION }
        if (location.length < 2) {
            statusText.text = "Enter a postcode, town, or address first."
            return
        }
        syncInProgress = true
        syncButton.isEnabled = false
        syncButton.alpha = 0.7f
        statusText.text = "Fetching Open-Meteo Best Match hourly history…"
        executor.execute {
            try {
                val result = SyncRepository.sync(this, location)
                runOnUiThread {
                    syncInProgress = false
                    syncButton.isEnabled = true
                    syncButton.alpha = 1f
                    val importText = if (result.initialImportCompletedNow) {
                        "Initial 2000-present Open-Meteo hourly archive imported"
                    } else {
                        "Latest-date Open-Meteo hourly refresh completed"
                    }
                    val geoText = String.format(
                        Locale.getDefault(),
                        "%.4f°, %.4f° · %s m elevation",
                        result.locationProfile.latitude,
                        result.locationProfile.longitude,
                        result.locationProfile.elevationM?.let { String.format(Locale.getDefault(), "%.0f", it) } ?: "unknown"
                    )
                    locationSummaryText.text = result.resolvedAddress
                    statusText.text = "${result.resolvedAddress} ($geoText): $importText · ${result.fetchedHours} fetched/updated hourly rows · ${result.trainingExamples} local training examples · ${result.savedReadings} stored hourly records."
                    NotificationHelper.showSyncSuccess(this, result, source = if (manual) "manual" else "automatic")
                    refreshDashboard()
                }
            } catch (error: Exception) {
                runOnUiThread {
                    syncInProgress = false
                    syncButton.isEnabled = true
                    syncButton.alpha = 1f
                    statusText.text = "Sync failed: ${error.message ?: "please check your connection and try again."}"
                }
            }
        }
    }

    private fun searchAndSelectLocation(query: String, button: Button) {
        if (syncInProgress) {
            Toast.makeText(this, "Please let the current refresh finish first.", Toast.LENGTH_SHORT).show()
            return
        }
        button.isEnabled = false
        statusText.text = "Searching for matching places…"
        executor.execute {
            try {
                val matches = LocationGeocodingClient.search(query)
                runOnUiThread {
                    button.isEnabled = true
                    if (matches.isEmpty()) {
                        statusText.text = "No matching place found. Try adding its state and country."
                        AlertDialog.Builder(this)
                            .setTitle("No matching location")
                            .setMessage("Open-Meteo could not find that place. Try a more specific search, such as ‘Katoomba, NSW, Australia’.")
                            .setPositiveButton("OK", null)
                            .show()
                        return@runOnUiThread
                    }
                    val labels = matches.map { it.selectionLabel() }.toTypedArray()
                    AlertDialog.Builder(this)
                        .setTitle("Choose the correct location")
                        .setItems(labels) { _, index ->
                            saveSelectedLocation(query, matches[index], allowLegacyMigration = matches.size == 1)
                        }
                        .setNegativeButton("Cancel") { _, _ -> statusText.text = "Location not changed." }
                        .show()
                }
            } catch (error: Exception) {
                runOnUiThread {
                    button.isEnabled = true
                    statusText.text = "Location search failed: ${error.message ?: "check your connection"}"
                }
            }
        }
    }

    private fun saveSelectedLocation(
        query: String,
        place: LocationGeocodingClient.Place,
        allowLegacyMigration: Boolean
    ) {
        statusText.text = "Saving ${place.displayName}…"
        executor.execute {
            try {
                val elevation = place.elevationM ?: runCatching {
                    OpenMeteoClient.fetchElevation(place.latitude, place.longitude)
                }.getOrNull()
                val legacyKey = AppPreferences.locationKey(query)
                val locationKey = AppPreferences.geocodedLocationKey(place.id)
                val profile = place.toProfile(locationKey, query, elevation)
                val database = TemperatureDatabase(this)
                try {
                    if (allowLegacyMigration) {
                        database.moveLocationKey(legacyKey, locationKey, place.id)
                    }
                    database.saveLocationProfile(profile)
                } finally {
                    database.close()
                }
                prefs.edit()
                    .putString(AppPreferences.KEY_LOCATION, query)
                    .putString(AppPreferences.KEY_ACTIVE_LOCATION_KEY, locationKey)
                    .apply()
                runOnUiThread {
                    locationSummaryText.text = profile.displayName
                    showSettings(false)
                    refreshDashboard()
                    syncNow(manual = true)
                }
            } catch (error: Exception) {
                runOnUiThread {
                    statusText.text = "Could not save location: ${error.message ?: "please try again"}"
                }
            }
        }
    }

    private fun beginModelExport(shared: Boolean) {
        val location = prefs.getString(AppPreferences.KEY_LOCATION, AppPreferences.DEFAULT_LOCATION)
            ?.trim().orEmpty().ifBlank { AppPreferences.DEFAULT_LOCATION }
        val locationKey = AppPreferences.activeLocationKey(prefs, location)
        val database = TemperatureDatabase(this)
        val exportData = try {
            val model = database.getModel(if (shared) AppPreferences.SHARED_MODEL_KEY else locationKey)
            val profiles = database.getAllLocationProfiles()
            val profile = profiles[locationKey]
            if (model == null) null else Triple(model, profile, profiles.values.toList())
        } finally {
            database.close()
        }
        if (exportData == null) {
            Toast.makeText(this, if (shared) "The shared model is not trained yet." else "This location's model is not trained yet.", Toast.LENGTH_LONG).show()
            return
        }
        val (model, profile, allProfiles) = exportData
        val timezoneId = profile?.timezoneId
            ?: prefs.getString(AppPreferences.timezoneKey(locationKey), ZoneId.systemDefault().id)
            ?: ZoneId.systemDefault().id
        try {
            val profilesForExport = if (shared) allProfiles else listOfNotNull(profile)
            val zipBytes = WeatherModelExport.toZip(
                context = this,
                model = model,
                modelScope = if (shared) "shared" else "location",
                locationLabel = if (shared) "Shared model" else (profile?.displayName ?: location),
                timezoneId = timezoneId,
                defaultLocation = profile,
                trainingLocations = profilesForExport
            )
            val safeName = (profile?.name ?: location).lowercase(Locale.ROOT)
                .replace(Regex("[^a-z0-9]+"), "_").trim('_').take(36).ifBlank { "location" }
            pendingModelExportZip = zipBytes
            pendingModelExportFilename = if (shared) "templearn_shared_model_v2.zip" else "templearn_${safeName}_model_v2.zip"
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/zip"
                putExtra(Intent.EXTRA_TITLE, pendingModelExportFilename)
            }
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQUEST_EXPORT_MODEL)
        } catch (error: Exception) {
            Toast.makeText(this, "Model package could not be prepared: ${error.message ?: "export error"}", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Deprecated by Activity Result APIs; retained for broad Android compatibility.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_EXPORT_MODEL) return
        val zipBytes = pendingModelExportZip
        pendingModelExportZip = null
        val filename = pendingModelExportFilename ?: "templearn_model_v2.zip"
        pendingModelExportFilename = null
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null || zipBytes == null) return
        try {
            contentResolver.openOutputStream(uri, "w")?.use { it.write(zipBytes) }
                ?: throw java.io.IOException("Android could not open the selected destination.")
            Toast.makeText(this, "Model package exported: $filename", Toast.LENGTH_LONG).show()
        } catch (error: Exception) {
            Toast.makeText(this, "Model export failed: ${error.message ?: "write error"}", Toast.LENGTH_LONG).show()
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < 33) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        if (prefs.getBoolean(AppPreferences.KEY_NOTIFICATION_PERMISSION_REQUESTED, false)) return

        AlertDialog.Builder(this)
            .setTitle("Enable weather update notifications?")
            .setMessage("TempLearn can notify you when the daily hourly weather fetch succeeds and the local model has been updated.")
            .setPositiveButton("Enable") { _, _ ->
                prefs.edit().putBoolean(AppPreferences.KEY_NOTIFICATION_PERMISSION_REQUESTED, true).apply()
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_POST_NOTIFICATIONS)
            }
            .setNegativeButton("Not now") { _, _ ->
                prefs.edit().putBoolean(AppPreferences.KEY_NOTIFICATION_PERMISSION_REQUESTED, true).apply()
            }
            .show()
    }

    private fun shouldAutoSync(): Boolean {
        val location = prefs.getString(AppPreferences.KEY_LOCATION, AppPreferences.DEFAULT_LOCATION)
            ?.takeIf { it.isNotBlank() } ?: return true
        val key = AppPreferences.activeLocationKey(prefs, location)
        val lastSync = prefs.getLong(AppPreferences.syncTimeKey(key), 0L)
        if (lastSync == 0L || Instant.now().epochSecond - lastSync > 6L * 60L * 60L) return true
        val db = TemperatureDatabase(this)
        return try {
            val saved = db.getSavedCount(key)
            saved == 0 ||
                db.getModel(key) == null ||
                db.getModel(AppPreferences.SHARED_MODEL_KEY) == null
        } finally {
            db.close()
        }
    }

    private fun scheduleDailySync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<DailySyncWorker>(24, java.util.concurrent.TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
            "templearn_daily_temperature_sync",
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    private fun newCard(color: Int = Color.WHITE): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(17), dp(17), dp(17), dp(17))
        background = roundedDrawable(color, 20)
        elevation = dp(1).toFloat()
    }

    private fun cardLayoutParams() = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(15) }

    private fun addText(
        parent: LinearLayout,
        text: String,
        sizeSp: Float,
        color: Int,
        bold: Boolean
    ): TextView = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        else typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        includeFontPadding = true
        parent.addView(this, LinearLayout.LayoutParams(-1, -2))
    }

    private fun addSpacer(parent: LinearLayout, heightDp: Int) {
        parent.addView(View(this), LinearLayout.LayoutParams(1, dp(heightDp)))
    }

    private fun roundedDrawable(fillColor: Int, radiusDp: Int, strokeColor: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fillColor)
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeColor != null) setStroke(dp(1), strokeColor)
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQUEST_POST_NOTIFICATIONS = 104
        private const val REQUEST_EXPORT_MODEL = 7412
    }

    private class TemperatureChartView(context: Context) : View(context) {
        private var readings: List<HourlyTemperature> = emptyList()
        private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(229, 237, 233)
            strokeWidth = dpValue(1f)
            style = Paint.Style.STROKE
        }
        private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(8, 127, 107)
            strokeWidth = dpValue(2.8f)
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(8, 127, 107)
            style = Paint.Style.FILL
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(105, 126, 121)
            textSize = dpValue(10f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }

        fun setReadings(newReadings: List<HourlyTemperature>) {
            readings = newReadings.toList()
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (readings.isEmpty()) {
                textPaint.textSize = dpValue(12f)
                canvas.drawText("Hourly temperature appears here after sync", dpValue(6f), height / 2f, textPaint)
                textPaint.textSize = dpValue(10f)
                return
            }
            val left = dpValue(31f)
            val right = width.toFloat() - dpValue(8f)
            val top = dpValue(13f)
            val bottom = height.toFloat() - dpValue(15f)
            val minimum = readings.minOf { it.temperatureC }
            val maximum = readings.maxOf { it.temperatureC }
            val padding = max(1.0, (maximum - minimum) * 0.15)
            val low = floor(minimum - padding)
            val high = ceil(maximum + padding).let { if (it <= low) low + 2.0 else it }
            val range = high - low

            for (line in 0..3) {
                val y = top + (bottom - top) * line / 3f
                canvas.drawLine(left, y, right, y, gridPaint)
                val value = high - range * line / 3.0
                canvas.drawText(String.format(Locale.getDefault(), "%.0f°", value), 0f, y + dpValue(3f), textPaint)
            }

            val path = Path()
            val xSpan = max(1, readings.size - 1)
            readings.forEachIndexed { index, reading ->
                val x = left + (right - left) * index / xSpan.toFloat()
                val y = bottom - ((reading.temperatureC - low) / range).toFloat() * (bottom - top)
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                if (index == 0 || index == readings.lastIndex) canvas.drawCircle(x, y, dpValue(3.5f), dotPaint)
            }
            canvas.drawPath(path, curvePaint)
        }

        private fun dpValue(value: Float): Float = value * resources.displayMetrics.density
    }

}
