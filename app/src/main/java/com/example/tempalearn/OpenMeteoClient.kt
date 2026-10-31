package com.example.tempalearn

import java.io.BufferedReader
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.GZIPInputStream

/** Open-Meteo Best Match hourly archive client. CSV is parsed as a stream, not a giant JSON array. */
object OpenMeteoClient {
    private val hourlyFields = listOf(
        "temperature_2m",
        "apparent_temperature",
        "relative_humidity_2m",
        "dew_point_2m",
        "precipitation",
        "rain",
        "snowfall",
        "snow_depth",
        "weather_code",
        "cloud_cover",
        "cloud_cover_low",
        "cloud_cover_mid",
        "cloud_cover_high",
        "surface_pressure",
        "pressure_msl",
        "wind_speed_10m",
        "wind_direction_10m",
        "wind_gusts_10m",
        "shortwave_radiation",
        "direct_radiation",
        "diffuse_radiation",
        "wet_bulb_temperature_2m",
        "boundary_layer_height",
        "wind_speed_100m",
        "wind_direction_100m",
        "soil_temperature_0_to_7cm",
        "soil_moisture_0_to_7cm"
    )

    @Throws(IOException::class)
    fun fetchHourlyArchive(
        latitude: Double, longitude: Double, timezoneId: String, elevationM: Double?,
        startDate: LocalDate, endDate: LocalDate, onBatch: (List<HourlyTemperature>) -> Unit
    ): Int {
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

        var lastError: IOException? = null
        for (attempt in 0..MAX_RETRIES) {
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 30_000
                readTimeout = 180_000
                setRequestProperty("Accept", "text/csv")
                setRequestProperty("Accept-Encoding", "gzip")
                setRequestProperty("User-Agent", "TempLearn-Android/0.9.0")
            }
            try {
                val code = connection.responseCode
                if (code !in 200..299) {
                    val retryAfter = connection.getHeaderField("Retry-After")
                    val detail = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                    val error = IOException("Open-Meteo Historical Weather returned HTTP $code: ${detail.take(350)}")
                    val retryable = code == 429 || code in 500..599
                    if (!retryable || attempt == MAX_RETRIES) throw error
                    lastError = error
                    val waitMs = retryDelayMillis(retryAfter, attempt)
                    connection.disconnect()
                    sleepBeforeRetry(waitMs)
                    continue
                }
                val input = connection.inputStream
                val decoded = if (connection.contentEncoding.equals("gzip", true)) GZIPInputStream(input) else input
                return decoded.bufferedReader(Charsets.UTF_8).use { parseCsv(it, onBatch) }
            } catch (error: IOException) {
                if (attempt == MAX_RETRIES || error.message?.contains("HTTP 4") == true && error.message?.contains("HTTP 429") != true) throw error
                lastError = error
                val waitMs = (BASE_RETRY_DELAY_MS * (1L shl attempt)).coerceAtMost(60_000L)
                sleepBeforeRetry(waitMs)
            } finally {
                connection.disconnect()
            }
        }
        throw lastError ?: IOException("Open-Meteo archive request failed after retries.")
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

    private fun parseCsv(reader:BufferedReader,onBatch:(List<HourlyTemperature>)->Unit):Int{
        var header:List<String>?=null
        var indices:Map<String,Int> = emptyMap()
        val batch=ArrayList<HourlyTemperature>(256)
        var imported=0
        reader.forEachLine{line->
            if(line.isBlank())return@forEachLine
            val cells=splitCsv(line)
            if(cells.firstOrNull()?.trim()?.equals("time",true)==true && cells.any{it.trim()=="temperature_2m"}){
                header=cells.map{it.trim()}
                indices=header!!.withIndex().associate{it.value to it.index}
                val missing=hourlyFields.filterNot{it in indices}
                if(missing.isNotEmpty())throw IOException("CSV is missing requested fields: ${missing.joinToString()}")
                return@forEachLine
            }
            val columns=header ?: return@forEachLine
            if(cells.size<columns.size)return@forEachLine
            val timestamp=cells.getOrNull(indices["time"] ?: 0)?.trim()?.toLongOrNull() ?: return@forEachLine
            val temperature=cells.numberAt(indices,"temperature_2m") ?: return@forEachLine
            batch += HourlyTemperature(
                timestampSec=timestamp,temperatureC=temperature,
                apparentTemperatureC=cells.numberAt(indices,"apparent_temperature"),
                humidityPct=cells.numberAt(indices,"relative_humidity_2m"),
                dewPointC=cells.numberAt(indices,"dew_point_2m"),
                precipitationMm=cells.numberAt(indices,"precipitation"),
                rainMm=cells.numberAt(indices,"rain"),
                snowfallCm=cells.numberAt(indices,"snowfall"),
                snowDepthM=cells.numberAt(indices,"snow_depth"),
                weatherCode=cells.numberAt(indices,"weather_code")?.toInt(),
                cloudCoverPct=cells.numberAt(indices,"cloud_cover"),
                cloudLowPct=cells.numberAt(indices,"cloud_cover_low"),
                cloudMidPct=cells.numberAt(indices,"cloud_cover_mid"),
                cloudHighPct=cells.numberAt(indices,"cloud_cover_high"),
                surfacePressureHpa=cells.numberAt(indices,"surface_pressure"),
                pressureHpa=cells.numberAt(indices,"pressure_msl"),
                windSpeedKmh=cells.numberAt(indices,"wind_speed_10m"),
                windDirectionDeg=cells.numberAt(indices,"wind_direction_10m"),
                windGustKmh=cells.numberAt(indices,"wind_gusts_10m"),
                solarRadiationWm2=cells.numberAt(indices,"shortwave_radiation"),
                directRadiationWm2=cells.numberAt(indices,"direct_radiation"),
                diffuseRadiationWm2=cells.numberAt(indices,"diffuse_radiation"),
                wetBulbTemperatureC=cells.numberAt(indices,"wet_bulb_temperature_2m"),
                boundaryLayerHeightM=cells.numberAt(indices,"boundary_layer_height"),
                windSpeed100mKmh=cells.numberAt(indices,"wind_speed_100m"),
                windDirection100mDeg=cells.numberAt(indices,"wind_direction_100m"),
                soilTemperatureC=cells.numberAt(indices,"soil_temperature_0_to_7cm"),
                soilMoisture0To7cm=cells.numberAt(indices,"soil_moisture_0_to_7cm"),
                temperatureSource=SOURCE
            )
            imported++
            if(batch.size>=256){onBatch(batch.toList());batch.clear()}
        }
        if(header==null)throw IOException("Open-Meteo response contained no hourly CSV header.")
        if(batch.isNotEmpty())onBatch(batch.toList())
        if(imported==0)throw IOException("No rows with a valid 2 m temperature target were returned.")
        return imported
    }

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

    @Throws(IOException::class)
    fun fetchElevation(latitude:Double,longitude:Double):Double?{
        require(latitude.isFinite() && latitude in -90.0..90.0)
        require(longitude.isFinite() && longitude in -180.0..180.0)
        val url=URL("https://api.open-meteo.com/v1/elevation?latitude=$latitude&longitude=$longitude")
        val connection=(url.openConnection() as HttpURLConnection).apply{
            requestMethod="GET";connectTimeout=12_000;readTimeout=18_000
            setRequestProperty("Accept","application/json")
            setRequestProperty("User-Agent","TempLearn-Android/0.9.0")
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
    private const val MAX_RETRIES = 4
    private const val BASE_RETRY_DELAY_MS = 5_000L
    private const val MAX_RETRY_DELAY_MS = 300_000L
    const val SOURCE="OPEN_METEO_BEST_MATCH"
}