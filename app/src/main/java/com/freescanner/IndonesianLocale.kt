package com.freescanner

import android.app.Application
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/** This app is Indonesian-only, independently of the phone's system language. */
internal object IndonesianLocale {
    private val locale = Locale.forLanguageTag("id-ID")
    private val locales = LocaleList(locale)

    fun wrap(base: Context): Context {
        if (Locale.getDefault() != locale) Locale.setDefault(locale)
        if (base.resources.configuration.locales[0] == locale) return base
        val configuration = Configuration(base.resources.configuration).apply {
            setLocales(IndonesianLocale.locales)
        }
        return base.createConfigurationContext(configuration)
    }

    fun applyTo(application: Application) {
        if (Build.VERSION.SDK_INT >= 33) {
            val manager = application.getSystemService(LocaleManager::class.java)
            if (manager.applicationLocales != locales) manager.applicationLocales = locales
        }
    }
}
