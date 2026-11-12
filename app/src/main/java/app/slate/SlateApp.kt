package app.slate

import android.app.Application
import android.content.Context
import androidx.work.Configuration
import app.slate.core.io.DocIO
import app.slate.data.FavoritesRepository
import app.slate.data.RecentsRepository
import app.slate.data.SettingsRepository
import app.slate.data.SlateDb
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Tiny manual service locator — no DI framework needed for an app this size (faster cold start). */
class SlateApp : Application(), Configuration.Provider {
    val db by lazy { SlateDb.create(this) }
    val recents by lazy { RecentsRepository(this, db.recents()) }
    val favorites by lazy { FavoritesRepository(db.favorites()) }
    val settings by lazy { SettingsRepository(this) }
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile private var pdfBoxReady = false

    override fun onCreate() {
        super.onCreate()
        appScope.launch(Dispatchers.IO) {
            // Purge stale temp files from previous sessions (secure temp hygiene).
            DocIO.secureTemp(this@SlateApp).listFiles()?.forEach {
                if (System.currentTimeMillis() - it.lastModified() > 6 * 60 * 60 * 1000L) it.deleteRecursively()
            }
        }
    }

    /** PDFBox needs its font/resource loader initialised once; done lazily on first PDF use. */
    fun ensurePdfBox() {
        if (pdfBoxReady) return
        synchronized(this) {
            if (!pdfBoxReady) { PDFBoxResourceLoader.init(this); pdfBoxReady = true }
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setMinimumLoggingLevel(android.util.Log.ERROR).build()

    companion object {
        fun get(context: Context): SlateApp = context.applicationContext as SlateApp
    }
}
