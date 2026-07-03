package xyz.limo060719.goclaw.util

import android.content.Context
import android.content.res.Configuration
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App language (in-app locale override), independent of the app's other settings.
 *
 * Stored in a tiny [android.content.SharedPreferences] rather than DataStore because it must be
 * read **synchronously** from [android.app.Activity.attachBaseContext], long before Hilt injection
 * or DataStore coroutines are available.
 *
 * Values: [SYSTEM] follows the device language (Android then auto-selects `values/` = English or
 * `values-zh/` = Chinese, so a non-Chinese phone shows English out of the box); [ZH] / [EN] force
 * that locale regardless of the system.
 */
@Singleton
class LocaleManager @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Current selection: [SYSTEM] / [ZH] / [EN]. */
    var language: String
        get() = prefs.getString(KEY, SYSTEM) ?: SYSTEM
        set(value) { prefs.edit().putString(KEY, value).apply() }

    companion object {
        const val SYSTEM = "system"
        const val ZH = "zh"
        const val EN = "en"

        private const val PREFS = "goclaw_locale"
        private const val KEY = "app_language"

        /** Synchronous read for use in attachBaseContext (no Hilt / coroutines needed). */
        fun persisted(context: Context): String =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, SYSTEM) ?: SYSTEM

        /** Locale for a stored code, or null to follow the system default. */
        fun localeOf(code: String): Locale? = when (code) {
            ZH -> Locale.CHINESE
            EN -> Locale.ENGLISH
            else -> null
        }

        /**
         * Wraps [base] so its resources resolve in the chosen language. Returns [base] unchanged
         * for [SYSTEM] (let Android pick by system language). Call from attachBaseContext.
         */
        fun wrap(base: Context): Context {
            val locale = localeOf(persisted(base)) ?: return base
            Locale.setDefault(locale)
            val config = Configuration(base.resources.configuration)
            config.setLocale(locale)
            return base.createConfigurationContext(config)
        }
    }
}
