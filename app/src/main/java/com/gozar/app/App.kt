package com.gozar.app

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.gozar.app.core.IranRanges
import com.gozar.app.data.GozarDatabase
import com.gozar.app.data.SettingsRepository
import com.gozar.app.net.SourceFetcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class App : Application() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        instance = this

        scope.launch {
            // Parsed once at startup so the latency sweep can classify entry
            // points without touching the disk per server.
            IranRanges.load()

            val database = GozarDatabase.get(this@App)
            val settings = SettingsRepository(this@App)
            SourceFetcher.seedDefaults(database, settings)

            applyLanguage(settings.current().language)
        }
    }

    companion object {
        lateinit var instance: App
            private set

        /**
         * AppCompat backports per-app locales below API 33 and delegates to the
         * platform above it, so one call covers every supported version.
         */
        fun applyLanguage(tag: String) {
            AppCompatDelegate.setApplicationLocales(
                LocaleListCompat.forLanguageTags(tag),
            )
        }
    }
}
