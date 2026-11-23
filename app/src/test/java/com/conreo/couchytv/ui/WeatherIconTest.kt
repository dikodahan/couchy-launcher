package com.conreo.couchytv.ui

import com.conreo.couchytv.data.WeatherIconKind
import org.junit.Assert.assertEquals
import org.junit.Test

class WeatherIconTest {
    @Test
    fun clearWeatherUsesSunByDayAndMoonAtNight() {
        assertEquals("WeatherClear", weatherIconFor(WeatherIconKind.Clear, isDay = true).name)
        assertEquals("WeatherMoon", weatherIconFor(WeatherIconKind.Clear, isDay = false).name)
    }
}
