package com.freescanner

import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.text.DateFormatSymbols
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IndonesianLocaleTest {
    @Test fun englishDeviceConfigurationUsesIndonesianSystemControlsAndDates() {
        val app = ApplicationProvider.getApplicationContext<ScannerApplication>()
        val english = Configuration(app.resources.configuration).apply { setLocale(Locale.US) }
        val context = IndonesianLocale.wrap(app.createConfigurationContext(english))
        assertEquals("id-ID", context.resources.configuration.locales[0].toLanguageTag())
        assertEquals("Batal", context.getString(android.R.string.cancel))
        assertEquals("Januari", DateFormatSymbols.getInstance().months[0])
    }
}
