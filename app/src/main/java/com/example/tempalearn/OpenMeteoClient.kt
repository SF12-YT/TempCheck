package com.example.tempalearn

import android.util.JsonReader
import android.util.JsonToken
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.net.URLEncoder
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.GZIPInputStream

/** Progress for the single full-file transfer or the later local archive import. */
data class ArchiveFileProgress(val bytesTransferred: Long, val totalBytes: Long)

data class HistoricalArchiveMetadata(
    val latitude: Double?,
    val longitude: Double?,
    val elevationM: Double?,
    val timezoneId: String?
)

data class JsonArchiveImportResult(
    val importedRows: Int,
    val firstTimestampSec: Long,
    val lastTimestampSec: Long,
    val metadata: HistoricalArchiveMetadata
)

/** Open-Meteo Best Match hourly archive client. CSV is parsed as a stream, not a giant JSON array. */
object OpenMeteoClient {
    private val hourlyFields = listOf(
        "temperature_2m",
        "relative_humidity_2m",
        "dew_point_2m",
        "precipitation",
        "rain",
        "snowfall",
        "weather_code",
        "cloud_cover",
        "cloud_cover_low",
        "cloud_cover_mid",
        "cloud_cover_high",
        "pressure_msl",
        "wind_speed_10m",
        "wind_gusts_10m",
        "shortwave_radiation"
    )

    /**
     * Downloads one complete date range as one HTTP response to an on-device CSV file.
     * The file is written to a .part sibling and atomically renamed only after the entire
     * response and CSV header have been verified. No date-window segmentation is performed.
     */
    @Throws(IOException::class)
    fun downloadHourlyArchiveToFile(
        latitude: Double,
        longitude: Double,
        timezoneId: String,
        elevationM: Double?,
        startDate: LocalDate,
        endDate: LocalDate,
        destination: File,
        onProgress: (ArchiveFileProgress) -> Unit = {}
    ): File {
        require(latitude.isFinite() && latitude in -90.0..90.0)
        require(longitude.isFinite() && longitude in -180.0..180.0)
        require(!endDate.isBefore(startDate)) { "The archive end date precedes its start date." }
        ZoneId.of(timezoneId)
        val timezone = URLEncoder.encode(timezoneId, Charsets.UTF_8.name())
        val elevation = elevationM?.takeIf { it.isFinite() }?.let { "&elevation=$it" }.orEmpty()
        val url = URL(
            "https://archive-api.open-meteo.com/v1/archive?latitude=$latitude&longitude=$longitude$elevation" +
                "&start_date=$startDate&end_date=$endDate&hourly=${hourlyFields.joinToString(",")}&models=best_match" +
                "&timezone=$timezone&timeformat=unixtime&temperature_unit=celsius&wind_speed_unit=kmh" +
                "&precipitation_unit=mm&format=csv"
        )
        return downloadCsvToFile(url, destination, "Open-Meteo Historical Weather API", onProgress)
    }

    /**
     * Uses the user's rolling-refresh endpoint: previous local day, no future forecast hours.
     * The extra CSV/time parameters keep timestamps compatible with TempLearn's Unix-time parser.
     */
    @Throws(IOException::class)
    fun downloadRecentHourlyForecastToFile(
        latitude: Double,
        longitude: Double,
        timezoneId: String,
        destination: File,
        onProgress: (ArchiveFileProgress) -> Unit = {}
    ): File {
        require(latitude.isFinite() && latitude in -90.0..90.0)
        require(longitude.isFinite() && longitude in -180.0..180.0)
        ZoneId.of(timezoneId)
        val timezone = URLEncoder.encode(timezoneId, Charsets.UTF_8.name())
        val url = URL(
            "https://api.open-meteo.com/v1/forecast?latitude=$latitude&longitude=$longitude" +
                "&hourly=${hourlyFields.joinToString(",")}&timezone=$timezone&past_days=1&forecast_days=0" +
                "&timeformat=unixtime&temperature_unit=celsius&wind_speed_unit=kmh" +
                "&precipitation_unit=mm&format=csv"
        )
        return downloadCsvToFile(url, destination, "Open-Meteo Forecast API", onProgress)
    }

    @Throws(IOException::class)
    private fun downloadCsvToFile(
        url: URL,
        destination: File,
        apiName: String,
        onProgress: (ArchiveFileProgress) -> Unit
    ): File {
        val parent = destination.parentFile ?: throw IOException("The local dataset folder is unavailable.")
        if (!parent.exists() && !parent.mkdirs()) throw IOException("Could not create the local dataset folder.")
        val partial = File(parent, destination.name + ".part")
        var lastError: IOException? = null
        for (attempt in 0..MAX_RETRIES) {
            partial.delete()
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 30_000
                readTimeout = 180_000
                setRequestProperty("Accept", "text/csv")
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("User-Agent", "TempLearn-Android/0.9.6")
            }
            try {
                val code = connection.responseCode
                if (code !in 200..299) {
                    val retryAfter = connection.getHeaderField("Retry-After")
                    val detail = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                    val error = IOException("$apiName returned HTTP $code: ${detail.take(350)}")
                    val retryable = code == 429 || code in 500..599
                    if (!retryable || attempt == MAX_RETRIES) throw error
                    lastError = error
                    val waitMs = retryDelayMillis(retryAfter, attempt)
                    connection.disconnect()
                    sleepBeforeRetry(waitMs)
                    continue
                }

                val contentLength = connection.getHeaderFieldLong("Content-Length", -1L)
                val raw = CountingInputStream(connection.inputStream)
                val decoded = if (connection.contentEncoding.equals("gzip", true)) GZIPInputStream(raw) else raw
                val fileOutput = FileOutputStream(partial, false)
                val bufferedOutput = BufferedOutputStream(fileOutput, 64 * 1024)
                try {
                    val buffer = ByteArray(64 * 1024)
                    var lastReported = 0L
                    while (true) {
                        val count = decoded.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        bufferedOutput.write(buffer, 0, count)
                        val transferred = raw.bytesRead
                        if (transferred - lastReported >= PROGRESS_INTERVAL_BYTES) {
                            onProgress(ArchiveFileProgress(transferred, contentLength))
                            lastReported = transferred
                        }
                    }
                    bufferedOutput.flush()
                    fileOutput.fd.sync()
                    onProgress(ArchiveFileProgress(raw.bytesRead, contentLength))
                } finally {
                    bufferedOutput.close()
                }

                if (!partial.exists() || partial.length() == 0L) {
                    throw IOException("$apiName returned an empty hourly CSV file.")
                }
                validateCsvHeader(partial)
                try {
                    java.nio.file.Files.move(
                        partial.toPath(),
                        destination.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE
                    )
                } catch (_: Exception) {
                    if (destination.exists() && !destination.delete()) {
                        throw IOException("Could not replace the previous local hourly dataset.")
                    }
                    if (!partial.renameTo(destination)) {
                        throw IOException("Could not save the completed hourly dataset on this device.")
                    }
                }
                return destination
            } catch (error: IOException) {
                partial.delete()
                if (attempt == MAX_RETRIES ||
                    (error.message?.contains("returned HTTP 4") == true && error.message?.contains("HTTP 429") != true)
                ) throw error
                lastError = error
                sleepBeforeRetry((BASE_RETRY_DELAY_MS * (1L shl attempt)).coerceAtMost(MAX_RETRY_DELAY_MS))
            } finally {
                connection.disconnect()
            }
        }
        throw lastError ?: IOException("$apiName download failed after retries.")
    }

    /** Imports the already-downloaded local CSV; this function performs no network requests. */
    @Throws(IOException::class)
    fun importHourlyArchiveFile(
        file: File,
        onBatch: (List<HourlyTemperature>) -> Unit,
        onProgress: (ArchiveFileProgress) -> Unit = {}
    ): Int {
        if (!file.isFile || file.length() <= 0L) throw IOException("The saved historical CSV file is missing or empty.")
        val totalBytes = file.length()
        val counting = CountingInputStream(BufferedInputStream(FileInputStream(file), 64 * 1024))
        return ProgressBufferedReader(InputStreamReader(counting, Charsets.UTF_8), counting).use { reader ->
            parseCsv(reader, onBatch) { bytesRead ->
                onProgress(ArchiveFileProgress(bytesRead.coerceAtMost(totalBytes), totalBytes))
            }.also {
                onProgress(ArchiveFileProgress(totalBytes, totalBytes))
            }
        }
    }

    private fun validateCsvHeader(file: File) {
        val validHeader = BufferedReader(InputStreamReader(FileInputStream(file), Charsets.UTF_8)).use { reader ->
            var found = false
            repeat(30) {
                val line = reader.readLine() ?: return@repeat
                val cells = splitCsv(line).mapIndexed { index, value ->
                    if (index == 0) value.trim().removePrefix("\uFEFF") else value.trim()
                }
                val fields = cells.toSet()
                if (cells.firstOrNull() == "time" && hourlyFields.all { it in fields }) {
                    found = true
                    return@use true
                }
            }
            found
        }
        if (!validHeader) {
            throw IOException("The downloaded CSV is incomplete or has no hourly header containing all 15 requested fields.")
        }
    }

    private fun retryDelayMillis(retryAfter: String?, attempt: Int): Long {
        retryAfter?.toLongOrNull()?.let { seconds ->
            return (seconds * 1_000L).coerceIn(1_000L, MAX_RETRY_DELAY_MS)
        }
        if (!retryAfter.isNullOrBlank()) {
            val serverTime = runCatching {
                ZonedDateTime.parse(retryAfter, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
            }.getOrNull()
            if (serverTime != null) {
                return (serverTime - System.currentTimeMillis()).coerceIn(1_000L, MAX_RETRY_DELAY_MS)
            }
        }
        return (BASE_RETRY_DELAY_MS * (1L shl attempt)).coerceAtMost(MAX_RETRY_DELAY_MS)
    }

    private fun sleepBeforeRetry(waitMs: Long) {
        try {
            Thread.sleep(waitMs)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("The historical download was interrupted.", interrupted)
        }
    }

    private fun parseCsv(
        reader: ProgressBufferedReader,
        onBatch: (List<HourlyTemperature>) -> Unit,
        onBytesRead: (Long) -> Unit
    ): Int {
        var header: List<String>? = null
        var indices: Map<String, Int> = emptyMap()
        val batch = ArrayList<HourlyTemperature>(256)
        var imported = 0
        var lastProgressBytes = 0L

        while (true) {
            val line = reader.readLine() ?: break
            if (line.isBlank()) continue
            val cells = splitCsv(line)
            if (cells.firstOrNull()?.trim()?.equals("time", true) == true && cells.any { it.trim() == "temperature_2m" }) {
                header = cells.map { it.trim() }
                indices = header!!.withIndex().associate { it.value to it.index }
                val missing = hourlyFields.filterNot { it in indices }
                if (missing.isNotEmpty()) throw IOException("CSV is missing requested fields: ${missing.joinToString()}")
                continue
            }
            val columns = header ?: continue
            if (cells.size < columns.size) continue
            val timestamp = cells.getOrNull(indices["time"] ?: 0)?.trim()?.toLongOrNull() ?: continue
            val temperature = cells.numberAt(indices, "temperature_2m") ?: continue
            batch += HourlyTemperature(
                timestampSec = timestamp,
                temperatureC = temperature,
                humidityPct = cells.numberAt(indices, "relative_humidity_2m"),
                dewPointC = cells.numberAt(indices, "dew_point_2m"),
                precipitationMm = cells.numberAt(indices, "precipitation"),
                rainMm = cells.numberAt(indices, "rain"),
                snowfallCm = cells.numberAt(indices, "snowfall"),
                weatherCode = cells.numberAt(indices, "weather_code")?.toInt(),
                cloudCoverPct = cells.numberAt(indices, "cloud_cover"),
                cloudLowPct = cells.numberAt(indices, "cloud_cover_low"),
                cloudMidPct = cells.numberAt(indices, "cloud_cover_mid"),
                cloudHighPct = cells.numberAt(indices, "cloud_cover_high"),
                pressureHpa = cells.numberAt(indices, "pressure_msl"),
                windSpeedKmh = cells.numberAt(indices, "wind_speed_10m"),
                windGustKmh = cells.numberAt(indices, "wind_gusts_10m"),
                solarRadiationWm2 = cells.numberAt(indices, "shortwave_radiation"),
                temperatureSource = SOURCE
            )
            imported++
            if (batch.size >= 256) {
                onBatch(batch.toList())
                batch.clear()
            }
            val currentBytes = countingBytesRead(reader)
            if (currentBytes - lastProgressBytes >= PROGRESS_INTERVAL_BYTES) {
                onBytesRead(currentBytes)
                lastProgressBytes = currentBytes
            }
        }
        if (header == null) throw IOException("Open-Meteo response contained no hourly CSV header.")
        if (batch.isNotEmpty()) onBatch(batch.toList())
        if (imported == 0) throw IOException("No rows with a valid 2 m temperature target were returned.")
        onBytesRead(countingBytesRead(reader))
        return imported
    }

    private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
        var bytesRead: Long = 0L
            private set

        override fun read(): Int {
            val value = super.read()
            if (value >= 0) bytesRead++
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val count = super.read(buffer, offset, length)
            if (count > 0) bytesRead += count.toLong()
            return count
        }
    }

    private class ProgressBufferedReader(
        input: InputStreamReader,
        val counter: CountingInputStream
    ) : BufferedReader(input, 64 * 1024)

    private fun countingBytesRead(reader: ProgressBufferedReader): Long = reader.counter.bytesRead

    private fun splitCsv(line:String):List<String>{
        val values=ArrayList<String>();val field=StringBuilder();var quoted=false;var i=0
        while(i<line.length){
            val c=line[i]
            when{
                c=='"' && quoted && i+1<line.length && line[i+1]=='"'->{field.append('"');i++}
                c=='"'->quoted=!quoted
                c==',' && !quoted->{values+=field.toString();field.setLength(0)}
                else->field.append(c)
            }
            i++
        }
        values+=field.toString()
        return values
    }

    private fun List<String>.numberAt(indices:Map<String,Int>,name:String):Double?{
        val value=getOrNull(indices[name] ?: return null)?.trim().orEmpty()
        return value.toDoubleOrNull()?.takeIf{it.isFinite()}
    }

    /**
     * Downloads the immutable, complete Open-Meteo JSON archive published for TempLearn's
     * default 2780 location. The unmodified JSON is retained locally. Its SHA-256 is checked
     * before the .part file is atomically promoted, so incomplete or replaced assets are rejected.
     */
    @Throws(IOException::class)
    fun downloadHistoricalJsonToFile(
        destination: File,
        onProgress: (ArchiveFileProgress) -> Unit = {}
    ): File {
        val parent = destination.parentFile ?: throw IOException("The local dataset folder is unavailable.")
        if (!parent.exists() && !parent.mkdirs()) throw IOException("Could not create the local dataset folder.")
        val partial = File(parent, destination.name + ".part")
        var lastError: IOException? = null
        for (attempt in 0..MAX_RETRIES) {
            partial.delete()
            val connection = (URL(HISTORICAL_JSON_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 30_000
                readTimeout = 180_000
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("User-Agent", "TempLearn-Android/0.9.4")
            }
            try {
                val code = connection.responseCode
                if (code !in 200..299) {
                    val detail = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                    val error = IOException("Historical JSON download returned HTTP $code: ${detail.take(250)}")
                    if ((code != 429 && code !in 500..599) || attempt == MAX_RETRIES) throw error
                    lastError = error
                    val delay = retryDelayMillis(connection.getHeaderField("Retry-After"), attempt)
                    connection.disconnect()
                    sleepBeforeRetry(delay)
                    continue
                }

                val totalBytes = connection.getHeaderFieldLong("Content-Length", -1L)
                val digest = MessageDigest.getInstance("SHA-256")
                val input = BufferedInputStream(connection.inputStream, 64 * 1024)
                val outputFile = FileOutputStream(partial, false)
                val output = BufferedOutputStream(outputFile, 64 * 1024)
                var transferred = 0L
                var lastReported = 0L
                try {
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        transferred += count
                        if (transferred - lastReported >= PROGRESS_INTERVAL_BYTES) {
                            onProgress(ArchiveFileProgress(transferred, totalBytes))
                            lastReported = transferred
                        }
                    }
                    output.flush()
                    outputFile.fd.sync()
                    onProgress(ArchiveFileProgress(transferred, totalBytes))
                } finally {
                    output.close()
                    input.close()
                }

                val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
                if (actualHash != HISTORICAL_JSON_SHA256) {
                    throw IOException("The downloaded historical JSON failed its SHA-256 integrity check.")
                }
                if (partial.length() != HISTORICAL_JSON_SIZE_BYTES) {
                    throw IOException("The downloaded historical JSON has an unexpected file size.")
                }
                java.nio.file.Files.move(
                    partial.toPath(),
                    destination.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE
                )
                return destination
            } catch (error: IOException) {
                partial.delete()
                if (attempt == MAX_RETRIES ||
                    (error.message?.contains("HTTP 4") == true && error.message?.contains("HTTP 429") != true)
                ) throw error
                lastError = error
                sleepBeforeRetry((BASE_RETRY_DELAY_MS * (1L shl attempt)).coerceAtMost(MAX_RETRY_DELAY_MS))
            } finally {
                connection.disconnect()
            }
        }
        throw lastError ?: IOException("The historical JSON download failed after retries.")
    }

    /**
     * Streams the saved JSON's column arrays into primitive arrays, then writes database rows
     * in small batches. The original JSON file is never rewritten or reduced; unrecognised hourly
     * variables remain available in that retained raw archive for future features.
     */
    @Throws(IOException::class)
    fun importHourlyArchiveJsonFile(
        file: File,
        onBatch: (List<HourlyTemperature>) -> Unit,
        onProgress: (ArchiveFileProgress) -> Unit = {}
    ): JsonArchiveImportResult {
        if (!file.isFile || file.length() <= 0L) throw IOException("The saved historical JSON file is missing or empty.")
        var times: LongArray? = null
        val columns = linkedMapOf<String, DoubleArray>()
        var latitude: Double? = null
        var longitude: Double? = null
        var elevation: Double? = null
        var timezone: String? = null

        JsonReader(InputStreamReader(BufferedInputStream(FileInputStream(file), 64 * 1024), Charsets.UTF_8)).use { reader ->
            reader.isLenient = false
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "latitude" -> latitude = reader.readNullableDouble()
                    "longitude" -> longitude = reader.readNullableDouble()
                    "elevation" -> elevation = reader.readNullableDouble()
                    "timezone" -> timezone = if (reader.peek() == JsonToken.NULL) { reader.nextNull(); null } else reader.nextString()
                    "hourly" -> {
                        reader.beginObject()
                        while (reader.hasNext()) {
                            val field = reader.nextName()
                            when {
                                field == "time" -> times = reader.readUnixTimeArray()
                                field in hourlyFields -> columns[field] = reader.readNullableDoubleArray()
                                else -> reader.skipValue()
                            }
                        }
                        reader.endObject()
                    }
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
        }

        val timestamps = times ?: throw IOException("The JSON archive has no hourly.time array.")
        val temperatures = columns["temperature_2m"]
            ?: throw IOException("The JSON archive has no hourly.temperature_2m target.")
        if (timestamps.isEmpty() || temperatures.size != timestamps.size) {
            throw IOException("The JSON archive's hourly timestamps and temperature values do not match.")
        }
        columns.forEach { (name, values) ->
            if (values.size != timestamps.size) {
                throw IOException("The JSON archive's $name column has a different number of hourly rows.")
            }
        }

        fun value(name: String, index: Int): Double? =
            columns[name]?.get(index)?.takeIf { it.isFinite() }

        val totalBytes = file.length()
        val batch = ArrayList<HourlyTemperature>(256)
        var imported = 0
        for (index in timestamps.indices) {
            val temperature = temperatures[index]
            if (temperature.isFinite()) {
                batch += HourlyTemperature(
                    timestampSec = timestamps[index],
                    temperatureC = temperature,
                    humidityPct = value("relative_humidity_2m", index),
                    dewPointC = value("dew_point_2m", index),
                    precipitationMm = value("precipitation", index),
                    rainMm = value("rain", index),
                    snowfallCm = value("snowfall", index),
                    weatherCode = value("weather_code", index)?.toInt(),
                    cloudCoverPct = value("cloud_cover", index),
                    cloudLowPct = value("cloud_cover_low", index),
                    cloudMidPct = value("cloud_cover_mid", index),
                    cloudHighPct = value("cloud_cover_high", index),
                    pressureHpa = value("pressure_msl", index),
                    windSpeedKmh = value("wind_speed_10m", index),
                    windGustKmh = value("wind_gusts_10m", index),
                    solarRadiationWm2 = value("shortwave_radiation", index),
                    temperatureSource = SOURCE
                )
                imported++
            }
            if (batch.size >= 256) {
                onBatch(batch.toList())
                batch.clear()
            }
            if (index % 1024 == 0) {
                onProgress(ArchiveFileProgress((index.toLong() * totalBytes / timestamps.size).coerceAtMost(totalBytes), totalBytes))
            }
        }
        if (batch.isNotEmpty()) onBatch(batch.toList())
        onProgress(ArchiveFileProgress(totalBytes, totalBytes))
        if (imported == 0) throw IOException("The JSON archive contains no valid 2 m temperature targets.")
        return JsonArchiveImportResult(
            importedRows = imported,
            firstTimestampSec = timestamps.first(),
            lastTimestampSec = timestamps.last(),
            metadata = HistoricalArchiveMetadata(latitude, longitude, elevation, timezone)
        )
    }

    private fun JsonReader.readNullableDouble(): Double? {
        if (peek() == JsonToken.NULL) {
            nextNull()
            return null
        }
        return nextDouble().takeIf { it.isFinite() }
    }

    private fun JsonReader.readNullableDoubleArray(): DoubleArray {
        val values = DoubleArrayBuilder()
        beginArray()
        while (hasNext()) values.add(readNullableDouble() ?: Double.NaN)
        endArray()
        return values.toArray()
    }

    private fun JsonReader.readUnixTimeArray(): LongArray {
        val values = LongArrayBuilder()
        beginArray()
        while (hasNext()) {
            if (peek() == JsonToken.NULL) {
                nextNull()
                throw IOException("The JSON archive contains a null hourly timestamp.")
            }
            val value = if (peek() == JsonToken.STRING) nextString().toLongOrNull()
                else runCatching { nextLong() }.getOrNull()
            values.add(value ?: throw IOException("The JSON archive contains an invalid Unix timestamp."))
        }
        endArray()
        return values.toArray()
    }

    private class DoubleArrayBuilder {
        private var values = DoubleArray(16_384)
        private var size = 0
        fun add(value: Double) {
            if (size == values.size) values = values.copyOf(values.size * 2)
            values[size++] = value
        }
        fun toArray(): DoubleArray = values.copyOf(size)
    }

    private class LongArrayBuilder {
        private var values = LongArray(16_384)
        private var size = 0
        fun add(value: Long) {
            if (size == values.size) values = values.copyOf(values.size * 2)
            values[size++] = value
        }
        fun toArray(): LongArray = values.copyOf(size)
    }

    @Throws(IOException::class)
    fun fetchElevation(latitude:Double,longitude:Double):Double?{
        require(latitude.isFinite() && latitude in -90.0..90.0)
        require(longitude.isFinite() && longitude in -180.0..180.0)
        val url=URL("https://api.open-meteo.com/v1/elevation?latitude=$latitude&longitude=$longitude")
        val connection=(url.openConnection() as HttpURLConnection).apply{
            requestMethod="GET";connectTimeout=12_000;readTimeout=18_000
            setRequestProperty("Accept","application/json")
            setRequestProperty("User-Agent","TempLearn-Android/0.9.4")
        }
        try{
            val code=connection.responseCode
            val stream=if(code in 200..299)connection.inputStream else connection.errorStream
            val response=stream?.bufferedReader(Charsets.UTF_8)?.use{it.readText()}.orEmpty()
            if(code !in 200..299)throw IOException("Open-Meteo Elevation returned HTTP $code: ${response.take(250)}")
            val values=org.json.JSONObject(response).optJSONArray("elevation") ?: return null
            if(values.length()==0 || values.isNull(0))return null
            return values.optDouble(0,Double.NaN).takeIf{it.isFinite()}
        }finally{connection.disconnect()}
    }
    const val HISTORICAL_JSON_URL = "https://github.com/SF12-YT/dummy-assets/releases/download/openmeteo/archive.json"
    const val HISTORICAL_JSON_SHA256 = "88720e72c510d81f8db148c48a925c3d02e8bb8301e957471c9f446f2c465f6a"
    const val HISTORICAL_JSON_SIZE_BYTES = 30_895_447L
    private const val MAX_RETRIES = 4
    private const val PROGRESS_INTERVAL_BYTES = 256L * 1024L
    private const val BASE_RETRY_DELAY_MS = 5_000L
    private const val MAX_RETRY_DELAY_MS = 300_000L
    const val DEFAULT_LATITUDE = -33.6153
    const val DEFAULT_LONGITUDE = 150.2665
    const val DEFAULT_TIMEZONE_ID = "Australia/Sydney"
    const val SOURCE="OPEN_METEO_BEST_MATCH"
}