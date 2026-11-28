package com.conreo.couchytv.data

import android.content.Context
import android.util.AtomicFile
import com.conreo.couchytv.BuildConfig
import kotlinx.serialization.SerialName
import kotlinx.serialization.encodeToString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/** Thematic bucket for a WMO weather code, so the icon set stays small. */
enum class WeatherIconKind { Clear, PartlyCloudy, Cloudy, Fog, Rain, Snow, Storm }

/** Maps Open-Meteo's WMO weather_code to a display bucket. */
fun iconKindFor(code: Int): WeatherIconKind = when (code) {
    0 -> WeatherIconKind.Clear
    1, 2 -> WeatherIconKind.PartlyCloudy
    3 -> WeatherIconKind.Cloudy
    45, 48 -> WeatherIconKind.Fog
    51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82 -> WeatherIconKind.Rain
    71, 73, 75, 77, 85, 86 -> WeatherIconKind.Snow
    95, 96, 99 -> WeatherIconKind.Storm
    else -> WeatherIconKind.Cloudy
}

/** One day of the week-ahead forecast; date is ISO yyyy-MM-dd (Open-Meteo, timezone=auto). */
data class DailyForecast(
    val date: String,
    val low: Double,
    val high: Double,
    val kind: WeatherIconKind,
    /** Chance of precipitation, 0..100; -1 if Open-Meteo didn't return one. */
    val precipChance: Int = -1,
)

data class HourlyForecast(
    val time: LocalTime,
    val temperature: Double,
    val kind: WeatherIconKind,
    val precipChance: Int,
    val isDay: Boolean = true,
)

/** Temperatures are already in the unit the caller requested from Open-Meteo. */
data class WeatherData(
    val temp: Double,
    val low: Double,
    val high: Double,
    val kind: WeatherIconKind,
    val fetchedAt: Long,
    /** Today first, up to seven days. */
    val daily: List<DailyForecast> = emptyList(),
    val hourly: List<HourlyForecast> = emptyList(),
    val isDay: Boolean = true,
    val observedAt: LocalDateTime? = null,
)

private val json = Json { ignoreUnknownKeys = true }
private const val GEOJS_ENDPOINT = "https://get.geojs.io/v1/ip/geo.json"
private val fahrenheitCountries = setOf("US", "PW", "MH", "FM", "AS", "GU", "MP", "PR", "UM", "VI")

@Serializable
private data class ForecastResponse(
    val current: CurrentBlock? = null,
    val daily: DailyBlock? = null,
    val hourly: HourlyBlock? = null,
    val timezone: String? = null,
    @SerialName("utc_offset_seconds") val offsetSeconds: Int = 0,
)
@Serializable
private data class CurrentBlock(
    val time: Long? = null,
    @SerialName("is_day") val isDay: Int = 1,
    @SerialName("temperature_2m") val temperature: Double? = null,
    @SerialName("weather_code") val weatherCode: Int = 0,
)
@Serializable
private data class HourlyBlock(
    val time: List<Long> = emptyList(),
    @SerialName("temperature_2m") val temp: List<Double?> = emptyList(),
    @SerialName("weather_code") val code: List<Int?> = emptyList(),
    @SerialName("precipitation_probability") val precip: List<Int?> = emptyList(),
    @SerialName("is_day") val isDay: List<Int?> = emptyList(),
)
@Serializable
private data class DailyBlock(
    val time: List<Long> = emptyList(),
    @SerialName("weather_code") val weatherCode: List<Int?> = emptyList(),
    @SerialName("temperature_2m_max") val max: List<Double?> = emptyList(),
    @SerialName("temperature_2m_min") val min: List<Double?> = emptyList(),
    @SerialName("precipitation_probability_max") val precipProb: List<Int?> = emptyList(),
)

@Serializable
private data class GeoJsResponse(
    val latitude: JsonPrimitive? = null,
    val longitude: JsonPrimitive? = null,
    val city: String? = null,
    val region: String? = null,
    val country: String? = null,
    @SerialName("country_code") val countryCode: String? = null,
)

@Serializable
internal data class WeatherLocation(val latitude: Double, val longitude: Double, val label: String = "") {
    fun isValid(): Boolean = latitude.isFinite() && latitude in -90.0..90.0 &&
        longitude.isFinite() && longitude in -180.0..180.0 &&
        label.length <= 256 && label.none { it.isISOControl() }
}

internal fun LauncherConfig.withWeatherLocation(location: WeatherLocation): LauncherConfig {
    require(location.isValid())
    return copy(
        weatherLocationSource = WEATHER_SRC_LATLONG,
        weatherLat = location.latitude,
        weatherLon = location.longitude,
        weatherCityLabel = location.label,
    )
}

@Serializable
private data class ZippopotamResponse(val places: List<ZippopotamPlace> = emptyList())
@Serializable
private data class ZippopotamPlace(
    @SerialName("latitude") val lat: String = "",
    @SerialName("longitude") val lon: String = "",
)

/** Only request-affecting settings participate in refresh and fallback identity. */
internal data class WeatherRequest(
    val source: Int,
    val lat: Double,
    val lon: Double,
    val zip: String,
    val country: String,
    val unit: Int,
) {
    val locationKey: String get() = "$source|$lat|$lon|$zip|$country"

    companion object {
        fun from(config: LauncherConfig): WeatherRequest? {
            if (!config.weatherEnabled || config.weatherUnit !in 0..1) return null
            val source = config.weatherLocationSource
            // Upgrading an old IP setting does not authorize a request to the new provider.
            if (source == WEATHER_SRC_GEOIP && !config.weatherGeoJsEnabled) return null
            if (source !in WEATHER_SRC_GEOIP..WEATHER_SRC_ZIP) return null
            val manual = source == WEATHER_SRC_LATLONG
            if (manual && (!config.weatherLat.isFinite() || config.weatherLat !in -90.0..90.0 ||
                    !config.weatherLon.isFinite() || config.weatherLon !in -180.0..180.0)) return null
            val zip = if (source == WEATHER_SRC_ZIP) config.weatherZip.trim() else ""
            val country = if (source == WEATHER_SRC_ZIP)
                config.weatherCountry.trim().ifEmpty { "us" }.lowercase(Locale.ROOT) else ""
            if (source == WEATHER_SRC_ZIP && (zip.isEmpty() || zip.length > 32 ||
                    zip.any { it.isISOControl() } || !country.matches(Regex("[a-z]{2}")))) return null
            return WeatherRequest(source, if (manual) config.weatherLat else 0.0,
                if (manual) config.weatherLon else 0.0, zip, country, config.weatherUnit)
        }
    }
}

internal enum class WeatherFailure { IpRateLimited, LocationUnavailable, ForecastUnavailable }

internal data class WeatherResult(
    val data: WeatherData?,
    val failure: WeatherFailure? = null,
    val ipLocationLabel: String? = null,
    val usingSavedIpLocation: Boolean = false,
)
internal data class WeatherHttpResponse(val body: String? = null, val status: Int? = null)

/** Blocking I/O: callers use Dispatchers.IO; responses and timeouts are bounded. */
private fun getWeatherText(url: String): WeatherHttpResponse = runCatching {
    val conn = URL(url).openConnection() as HttpURLConnection
    try {
        conn.connectTimeout = 8_000
        conn.readTimeout = 8_000
        conn.requestMethod = "GET"
        if (url == GEOJS_ENDPOINT) {
            conn.setRequestProperty("User-Agent", "CouchyLauncher/${BuildConfig.VERSION_NAME}")
            conn.setRequestProperty("Accept", "application/json")
            conn.instanceFollowRedirects = false
        }
        val status = conn.responseCode
        if (status !in 200..299) return@runCatching WeatherHttpResponse(status = status)
        val body = conn.inputStream.buffered().use { input ->
            val out = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(4096)
            while (true) {
                val count = input.read(chunk)
                if (count == -1) break
                require(out.size() + count <= 512 * 1024) { "Weather response too large" }
                out.write(chunk, 0, count)
            }
            out.toString("UTF-8")
        }
        WeatherHttpResponse(body, status)
    } finally { conn.disconnect() }
}.getOrDefault(WeatherHttpResponse())

/** Fallback data never crosses locations or units and expires after six hours. */
internal class WeatherClient(
    private val get: (String) -> WeatherHttpResponse = ::getWeatherText,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var cachedLocationKey: String? = null
    private var cachedLocation: Pair<Double, Double>? = null
    private var locationFetchedAt = 0L
    private var lastRequest: WeatherRequest? = null
    private var lastGood: WeatherData? = null
    private var ipAttempt: IpLocationAttempt? = null

    private data class IpLocationAttempt(
        val location: WeatherLocation?,
        val failure: WeatherFailure?,
        val countryCode: String?,
    )

    private var providerIpAttempt: IpLocationAttempt? = null

    /** A preview uses the same per-launch lookup as the forecast without saving an unconfirmed choice. */
    @Synchronized
    fun previewIpLocation(): WeatherLocation? = lookupIpOnce().location

    @Synchronized
    fun initialWeatherUnit(): Int =
        if (lookupIpOnce().countryCode?.trim()?.uppercase(Locale.ROOT) in fahrenheitCountries) 1 else 0

    private fun lookupIpOnce(): IpLocationAttempt = providerIpAttempt ?: run {
        val response = get(GEOJS_ENDPOINT)
        val decoded = response.body?.let { runCatching { json.decodeFromString<GeoJsResponse>(it) }.getOrNull() }
        val latitude = decoded?.latitude?.doubleOrNull
        val longitude = decoded?.longitude?.doubleOrNull
        val fresh = if (decoded != null && latitude != null && longitude != null) {
            val label = listOfNotNull(decoded.city, decoded.region, decoded.country)
                .map(String::trim).filter(String::isNotEmpty).distinct().joinToString(", ")
                .take(256).filterNot { it.isISOControl() }
            WeatherLocation(latitude, longitude, label).takeIf(WeatherLocation::isValid)
        } else null
        val failure = when {
            fresh != null -> null
            response.status == 429 -> WeatherFailure.IpRateLimited
            else -> WeatherFailure.LocationUnavailable
        }
        IpLocationAttempt(fresh, failure, decoded?.countryCode)
    }.also { providerIpAttempt = it }

    private fun locateIpOnce(
        load: () -> WeatherLocation?,
        save: (WeatherLocation) -> Unit,
    ): IpLocationAttempt = ipAttempt ?: run {
        val attempt = lookupIpOnce()
        val fresh = attempt.location
        if (fresh != null) {
            runCatching { save(fresh) }
            attempt
        } else attempt.copy(location = runCatching(load).getOrNull()?.takeIf(WeatherLocation::isValid))
    }.also { ipAttempt = it }

    private fun resolveLocation(request: WeatherRequest): Pair<Double, Double>? {
        cachedLocation?.let {
            if (cachedLocationKey == request.locationKey && now() - locationFetchedAt in 0..3_600_000L) return it
        }
        val resolved = when (request.source) {
            WEATHER_SRC_LATLONG -> request.lat to request.lon
            WEATHER_SRC_ZIP -> get("https://api.zippopotam.us/${request.country}/${URLEncoder.encode(request.zip, "UTF-8")}").body
                ?.let { runCatching { json.decodeFromString(ZippopotamResponse.serializer(), it) }.getOrNull() }
                ?.places?.firstOrNull()?.let { p ->
                    val lat = p.lat.toDoubleOrNull()
                    val lon = p.lon.toDoubleOrNull()
                    if (lat != null && lon != null) lat to lon else null
                }
            else -> null
        }?.takeIf { (lat, lon) -> lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0 }
        if (resolved != null) {
            cachedLocationKey = request.locationKey
            cachedLocation = resolved
            locationFetchedAt = now()
        }
        return resolved
    }

    @Synchronized
    fun fetch(
        request: WeatherRequest,
        loadIpLocation: () -> WeatherLocation? = { null },
        saveIpLocation: (WeatherLocation) -> Unit = {},
    ): WeatherResult {
        val ip = if (request.source == WEATHER_SRC_GEOIP) locateIpOnce(loadIpLocation, saveIpLocation) else null
        val usedSavedIp = ip?.failure != null && ip.location != null
        val ipLabel = ip?.location?.label?.takeIf(String::isNotBlank)
        fun fallback(reason: WeatherFailure) = WeatherResult(
            lastGood?.takeIf { lastRequest == request && now() - it.fetchedAt in 0..21_600_000L },
            reason,
            ipLabel,
            usedSavedIp,
        )
        val coordinates = if (ip != null) ip.location?.let { it.latitude to it.longitude }
        else resolveLocation(request)
        val (lat, lon) = coordinates ?: return fallback(ip?.failure ?: WeatherFailure.LocationUnavailable)
        val unit = if (request.unit == 1) "fahrenheit" else "celsius"
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,weather_code,is_day" +
            "&hourly=temperature_2m,weather_code,precipitation_probability,is_day&forecast_hours=7" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
            "&temperature_unit=$unit&timezone=auto&timeformat=unixtime&forecast_days=7"
        val body = get(url).body ?: return fallback(WeatherFailure.ForecastUnavailable)
        val parsed = runCatching { json.decodeFromString(ForecastResponse.serializer(), body) }.getOrNull()
            ?: return fallback(WeatherFailure.ForecastUnavailable)
        val current = parsed.current ?: return fallback(WeatherFailure.ForecastUnavailable)
        val temperature = current.temperature?.takeIf { it.isFinite() }
            ?: return fallback(WeatherFailure.ForecastUnavailable)
        // Open-Meteo daily epochs use its response offset. For individual hours,
        // the forecast's IANA zone preserves the local clock across DST changes.
        val offset = parsed.offsetSeconds.coerceIn(-18 * 3600, 18 * 3600)
        val zone = parsed.timezone?.let { runCatching { ZoneId.of(it) }.getOrNull() }
            ?: ZoneOffset.ofTotalSeconds(offset)
        val daily = parsed.daily?.let { d ->
            d.time.take(7).mapIndexedNotNull { i, epoch ->
                val date = runCatching {
                    LocalDate.ofEpochDay(Math.floorDiv(epoch + offset, 86_400L)).toString()
                }.getOrNull() ?: return@mapIndexedNotNull null
                val low = d.min.getOrNull(i)?.takeIf(Double::isFinite) ?: return@mapIndexedNotNull null
                val high = d.max.getOrNull(i)?.takeIf(Double::isFinite) ?: return@mapIndexedNotNull null
                DailyForecast(date, low, high,
                    iconKindFor(d.weatherCode.getOrNull(i) ?: current.weatherCode),
                    d.precipProb.getOrNull(i)?.coerceIn(0, 100) ?: -1)
            }
        }.orEmpty()
        val hourly = parsed.hourly?.let { h ->
            val forecastTime = current.time ?: now() / 1000
            h.time.mapIndexedNotNull { i, epoch ->
                if (epoch <= forecastTime) return@mapIndexedNotNull null
                val time = runCatching {
                    Instant.ofEpochSecond(epoch).atZone(zone).toLocalTime()
                }.getOrNull() ?: return@mapIndexedNotNull null
                val temp = h.temp.getOrNull(i)?.takeIf(Double::isFinite) ?: return@mapIndexedNotNull null
                HourlyForecast(time, temp, iconKindFor(h.code.getOrNull(i) ?: current.weatherCode),
                    h.precip.getOrNull(i)?.coerceIn(0, 100) ?: -1, h.isDay.getOrNull(i) != 0)
            }.take(6)
        }.orEmpty()
        val observedAt = current.time?.let { epoch ->
            runCatching { Instant.ofEpochSecond(epoch).atZone(zone).toLocalDateTime() }.getOrNull()
        }
        val forecast = WeatherData(temperature, daily.firstOrNull()?.low ?: temperature,
            daily.firstOrNull()?.high ?: temperature, iconKindFor(current.weatherCode), now(), daily,
            hourly = hourly, isDay = current.isDay != 0, observedAt = observedAt)
        lastGood = forecast
        lastRequest = request
        return WeatherResult(forecast, ip?.failure, ipLabel, usedSavedIp)
    }

}

@Serializable
internal data class WeatherCity(
    val name: String,
    val latitude: Double = Double.NaN,
    val longitude: Double = Double.NaN,
    val admin1: String = "",
    val country: String = "",
) {
    val description: String get() = listOf(admin1, country)
        .filter { it.isNotBlank() && !it.equals(name, ignoreCase = true) }
        .distinct().joinToString(", ")
    val label: String get() = listOf(name, description).filter(String::isNotBlank).joinToString(", ")
    fun toLocation() = WeatherLocation(latitude, longitude, label)
}

@Serializable
private data class WeatherCityResponse(val results: List<WeatherCity> = emptyList())

internal fun searchWeatherCities(
    query: String,
    language: String,
    get: (String) -> WeatherHttpResponse = ::getWeatherText,
): List<WeatherCity>? {
    val term = query.trim()
    require(term.length in 2..80 && term.none { it.isISOControl() }) { "Invalid weather city search" }
    val lang = language.lowercase(Locale.ROOT).let {
        when (it) {
            "iw" -> "he"
            else -> if (it.matches(Regex("[a-z]{2}"))) it else "en"
        }
    }
    val encoded = URLEncoder.encode(term, "UTF-8")
    val response = get("https://geocoding-api.open-meteo.com/v1/search?name=$encoded&count=8&language=$lang")
    BetaDiagnostics.event("Weather city search response http=${response.status} dataAvailable=${response.body != null}")
    val body = response.body ?: return null
    return runCatching { json.decodeFromString<WeatherCityResponse>(body).results }
        .getOrNull()?.asSequence()
        ?.filter { it.name.isNotBlank() && it.label.length <= 256 &&
            it.label.none { ch -> ch.isISOControl() } &&
            it.latitude.isFinite() && it.latitude in -90.0..90.0 &&
            it.longitude.isFinite() && it.longitude in -180.0..180.0 }
        ?.distinctBy { Triple(it.label, it.latitude, it.longitude) }
        ?.take(8)?.toList().also { BetaDiagnostics.event("Weather city search matches=${it?.size}") }
}

internal object WeatherRepository {
    private val client = WeatherClient()
    fun initialWeatherUnit(): Int = client.initialWeatherUnit()
    fun previewIpLocation(): WeatherLocation? = client.previewIpLocation().also {
        BetaDiagnostics.event("Weather IP location available=${it != null}")
    }

    fun fetch(context: Context, request: WeatherRequest): WeatherResult {
        val storage = AtomicFile(File(context.noBackupFilesDir, "weather-ip-location.json"))
        BetaDiagnostics.event("Weather fetch source=${request.source} unit=${request.unit}")
        return client.fetch(
            request,
            loadIpLocation = {
                val file = storage.baseFile
                if (!file.isFile || file.length() > 4096) null
                else json.decodeFromString<WeatherLocation>(storage.readFully().decodeToString())
            },
            saveIpLocation = { location ->
                val bytes = json.encodeToString(location).encodeToByteArray()
                val out = storage.startWrite()
                try {
                    out.write(bytes)
                    storage.finishWrite(out)
                } catch (failure: Exception) {
                    storage.failWrite(out)
                    throw failure
                }
            },
        ).also { BetaDiagnostics.event("Weather fetch result failure=${it.failure} conditionsAvailable=${it.data != null} savedLocation=${it.usingSavedIpLocation}") }
    }
}
