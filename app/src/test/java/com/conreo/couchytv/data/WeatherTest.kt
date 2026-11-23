package com.conreo.couchytv.data

import org.junit.Assert.*
import org.junit.Test

class WeatherTest {
    private val enabled = LauncherConfig(weatherEnabled = true, weatherLat = 40.7, weatherLon = -74.0)
    private val response = """{"current":{"temperature_2m":21.5,"weather_code":61},"daily":{"time":[1795392000],"temperature_2m_min":[12.0],"temperature_2m_max":[22.0],"weather_code":[61],"precipitation_probability_max":[60]}}"""

    @Test fun disabledAndInvalidInputsProduceNoRequest() {
        assertNull(WeatherRequest.from(LauncherConfig()))
        for (bad in listOf(enabled.copy(weatherLat = 91.0), enabled.copy(weatherLon = -181.0),
            enabled.copy(weatherLat = Double.NaN), enabled.copy(weatherUnit = 2), enabled.copy(weatherLocationSource = 8),
            enabled.copy(weatherLocationSource = WEATHER_SRC_ZIP, weatherZip = "10001", weatherCountry = "../")))
            assertNull(WeatherRequest.from(bad))
    }
    @Test fun upgradingOldIpSettingRequiresOptingIntoGeoJs() {
        val old = enabled.copy(weatherLocationSource = WEATHER_SRC_GEOIP)
        assertNull(WeatherRequest.from(old))
        assertNotNull(WeatherRequest.from(old.copy(weatherGeoJsEnabled = true)))
        assertNotNull(WeatherRequest.from(enabled))
    }

    @Test fun requestIdentityChangesForLocationAndUnits() {
        val request = WeatherRequest.from(enabled)
        assertNotEquals(request, WeatherRequest.from(enabled.copy(weatherLat = 51.5)))
        assertNotEquals(request, WeatherRequest.from(enabled.copy(weatherUnit = 1)))
        assertEquals(request, WeatherRequest.from(enabled.copy(accent = 2, weatherDisplay = 2)))
    }
    @Test fun zipIsNormalizedAndIgnoresUnusedCoordinates() {
        val zip = enabled.copy(weatherLocationSource = WEATHER_SRC_ZIP, weatherZip = " 10001 ", weatherCountry = " US ")
        val request = WeatherRequest.from(zip)!!
        assertEquals("10001", request.zip); assertEquals("us", request.country)
        assertEquals(request, WeatherRequest.from(zip.copy(weatherLat = 0.0)))
    }
    @Test fun manualLocationDoesNotContactAnIpService() {
        val urls = mutableListOf<String>()
        val client = WeatherClient(get = { urls += it; WeatherHttpResponse(response) }, now = { 1000L })
        val data = client.fetch(WeatherRequest.from(enabled)!!).data!!
        assertEquals(1, urls.size); assertTrue(urls.single().startsWith("https://api.open-meteo.com/"))
        assertEquals(21.5, data.temp, 0.01); assertEquals(WeatherIconKind.Rain, data.kind)
        assertTrue(data.hourly.isEmpty())
        assertEquals(60, data.daily.single().precipChance)
    }
    @Test fun fallbackNeverLeaksAcrossUnitsOrLocations() {
        var online = true
        val client = WeatherClient(get = { WeatherHttpResponse(if (online) response else null) }, now = { 1000L })
        val request = WeatherRequest.from(enabled)!!
        val data = client.fetch(request).data
        online = false
        assertEquals(data, client.fetch(request).data)
        assertNull(client.fetch(WeatherRequest.from(enabled.copy(weatherUnit = 1))!!).data)
        assertNull(client.fetch(WeatherRequest.from(enabled.copy(weatherLat = 1.0))!!).data)
    }
    @Test fun staleFallbackExpires() {
        var time = 1000L
        var online = true
        val client = WeatherClient(get = { WeatherHttpResponse(if (online) response else null) }, now = { time })
        val request = WeatherRequest.from(enabled)!!
        assertNotNull(client.fetch(request).data); online = false; time += 21_600_001L
        assertNull(client.fetch(request).data)
    }
    @Test fun malformedResponseKeepsSameRequestFallback() {
        var text = response
        val client = WeatherClient(get = { WeatherHttpResponse(text) }, now = { 1000L })
        val request = WeatherRequest.from(enabled)!!
        val good = client.fetch(request).data; text = "not JSON"
        assertEquals(good, client.fetch(request).data)
    }
    @Test fun rateLimitedIpLookupIsDistinctFromForecastFailure() {
        val ip = WeatherRequest.from(enabled.copy(weatherLocationSource = WEATHER_SRC_GEOIP, weatherGeoJsEnabled = true))!!
        val endpoints = mutableListOf<String>()
        val client = WeatherClient(get = { endpoints += it; WeatherHttpResponse(status = 429) })
        assertEquals(WeatherResult(null, WeatherFailure.IpRateLimited), client.fetch(ip))
        assertEquals(listOf("https://get.geojs.io/v1/ip/geo.json"), endpoints)
        val forecastDown = WeatherClient(get = { WeatherHttpResponse(status = 503) })
        val manual = WeatherRequest.from(enabled)!!
        assertEquals(WeatherResult(null, WeatherFailure.ForecastUnavailable), forecastDown.fetch(manual))
    }

    @Test fun incompleteForecastDoesNotDisplayZeroDegrees() {
        val client = WeatherClient(get = { WeatherHttpResponse("""{"current":{}}""") })
        assertEquals(
            WeatherResult(null, WeatherFailure.ForecastUnavailable),
            client.fetch(WeatherRequest.from(enabled)!!),
        )
    }

    @Test fun providerCoordinatesMustBeValid() {
        val client = WeatherClient(get = { WeatherHttpResponse("""{"latitude":900.0,"longitude":0.0}""") })
        val result = client.fetch(WeatherRequest.from(enabled.copy(weatherLocationSource = WEATHER_SRC_GEOIP, weatherGeoJsEnabled = true))!!)
        assertNull(result.data)
        assertEquals(WeatherFailure.LocationUnavailable, result.failure)
    }
    @Test fun citySearchEncodesLocationAndFiltersInvalidResults() {
        val locations = """{"results":[
            {"name":"São Paulo","latitude":-23.55,"longitude":-46.63,"admin1":"São Paulo","country":"Brazil"},
            {"name":"Invalid","latitude":91,"longitude":5},
            {"name":"Missing coordinates"}
        ]}"""
        val cities = searchWeatherCities(" São Paulo, Brazil ", "iw") { url ->
            assertEquals(
                "https://geocoding-api.open-meteo.com/v1/search?name=S%C3%A3o+Paulo%2C+Brazil&count=8&language=he",
                url,
            )
            WeatherHttpResponse(locations, 200)
        }!!
        assertEquals("São Paulo, Brazil", cities.single().label)
        assertEquals(-23.55, cities.single().latitude, 0.001)
        assertNull(searchWeatherCities("Paris", "en") { WeatherHttpResponse(status = 429) })
        assertEquals(emptyList<WeatherCity>(), searchWeatherCities("Paris", "en") {
            WeatherHttpResponse("{}", 200)
        })
    }

    @Test fun successfulIpSearchRunsOnceAcrossForecastRefreshesAndUnits() {
        var time = 1_000L
        var ipCalls = 0
        var forecastCalls = 0
        var saved: WeatherIpLocation? = null
        val client = WeatherClient(get = {
            if (it.startsWith("https://get.geojs.io")) {
                ipCalls++
                WeatherHttpResponse(
                    """{"latitude":"52.52","longitude":"13.41","city":"Berlin","region":"Berlin","country":"Germany","ip":"192.0.2.1"}""",
                    200,
                )
            } else {
                assertTrue(it.contains("latitude=52.52&longitude=13.41"))
                forecastCalls++
                WeatherHttpResponse(response, 200)
            }
        }, now = { time })
        val ip = WeatherRequest.from(enabled.copy(weatherLocationSource = WEATHER_SRC_GEOIP, weatherGeoJsEnabled = true))!!
        assertEquals("Berlin, Germany", client.fetch(ip, saveIpLocation = { saved = it }).ipLocationLabel)
        time += 8 * 60 * 60 * 1000
        assertNotNull(client.fetch(ip.copy(unit = 1)).data)
        assertNotNull(client.fetch(ip).data)
        assertEquals(WeatherIpLocation(52.52, 13.41, "Berlin, Germany"), saved)
        assertEquals(1, ipCalls)
        assertEquals(3, forecastCalls)
    }

    @Test fun failedIpSearchRunsOncePerLaunchAndCanUseSavedCity() {
        val ip = WeatherRequest.from(enabled.copy(weatherLocationSource = WEATHER_SRC_GEOIP, weatherGeoJsEnabled = true))!!
        var ipCalls = 0
        val transport: (String) -> WeatherHttpResponse = {
            if (it.startsWith("https://get.geojs.io")) {
                ipCalls++
                WeatherHttpResponse(status = 429)
            } else {
                assertTrue(it.contains("latitude=52.52&longitude=13.41"))
                WeatherHttpResponse(response)
            }
        }
        val firstLaunch = WeatherClient(get = transport)
        repeat(3) { assertEquals(WeatherFailure.IpRateLimited, firstLaunch.fetch(ip).failure) }
        assertEquals(1, ipCalls)
        val secondLaunch = WeatherClient(get = transport)
        val result = secondLaunch.fetch(ip, loadIpLocation = { WeatherIpLocation(52.52, 13.41, "Berlin") })
        assertTrue(result.usingSavedIpLocation)
        assertEquals("Berlin", result.ipLocationLabel)
        assertNotNull(result.data)
        assertNotNull(secondLaunch.fetch(ip.copy(unit = 1)).data)
        assertEquals(2, ipCalls)
    }

    @Test fun hourlyForecastUsesLocalTimeSkipsPastAndMissingValuesAndKeepsDaily() {
        fun epoch(value: String) = java.time.Instant.parse(value).epochSecond
        val now = epoch("2026-03-08T06:30:00Z")
        val past = epoch("2026-03-08T06:00:00Z")
        val next = epoch("2026-03-08T07:00:00Z")
        val missing = epoch("2026-03-08T08:00:00Z")
        val later = epoch("2026-03-08T09:00:00Z")
        val daily = epoch("2026-03-08T05:00:00Z")
        val payload = """{
            "timezone":"America/New_York", "utc_offset_seconds":-18000,
            "current":{"time":$now,"temperature_2m":5,"weather_code":0,"is_day":0},
            "hourly":{"time":[$past,$next,$missing,$later],"temperature_2m":[4,6,null,7],
                "weather_code":[0,3,0,61],"precipitation_probability":[0,20,null,35],"is_day":[0,0,0,1]},
            "daily":{"time":[$daily],"temperature_2m_min":[2],"temperature_2m_max":[10],"weather_code":[61]}
        }"""
        val client = WeatherClient(get = { url ->
            assertTrue(url.contains("&forecast_hours=7"))
            assertTrue(url.contains("&timeformat=unixtime"))
            WeatherHttpResponse(payload, 200)
        })
        val forecast = client.fetch(WeatherRequest.from(enabled)!!).data!!
        assertFalse(forecast.isDay)
        assertEquals("2026-03-08T01:30", forecast.observedAt.toString())
        assertEquals(listOf("03:00", "05:00"), forecast.hourly.map { it.time.toString() })
        assertEquals(listOf(20, 35), forecast.hourly.map { it.precipChance })
        assertEquals(listOf(false, true), forecast.hourly.map { it.isDay })
        assertEquals("2026-03-08", forecast.daily.single().date)
        assertEquals(WeatherIconKind.Rain, forecast.daily.single().kind)
    }

}
