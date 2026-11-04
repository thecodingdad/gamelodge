package io.github.thecodingdad.gamelodge.ui.screens.stats

import java.util.Calendar
import java.util.Locale
import org.junit.Test
import org.junit.Assert.assertEquals

class DayMonthFormatTest {
    private val oct29 = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 29) }.time

    @Test
    fun englishShowsMonthThenDay() = assertEquals("10/29", dayMonthFormat(Locale.US).format(oct29))

    @Test
    fun germanShowsDayThenMonth() = assertEquals("29.10", dayMonthFormat(Locale.GERMANY).format(oct29))

    @Test
    fun yearFirstLanguagesKeepMonthAndDay() = assertEquals("10/29", dayMonthFormat(Locale.JAPAN).format(oct29))
}
