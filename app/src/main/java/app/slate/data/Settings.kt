package app.slate.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.slate.ui.theme.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private val Context.store by preferencesDataStore("settings")

enum class SortBy(val label: String) { NAME("Name"), DATE("Date"), SIZE("Size"), TYPE("Type") }

data class Settings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val gridView: Boolean = false,
    val sortBy: SortBy = SortBy.NAME,
    val sortDescending: Boolean = false,
    val showHidden: Boolean = false,
    val autoSave: Boolean = true,
    val keepScreenOn: Boolean = false,
    val rememberPosition: Boolean = true,
)

class SettingsRepository(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private object K {
        val theme = stringPreferencesKey("theme")
        val grid = booleanPreferencesKey("grid")
        val sort = stringPreferencesKey("sort")
        val desc = booleanPreferencesKey("desc")
        val hidden = booleanPreferencesKey("hidden")
        val autosave = booleanPreferencesKey("autosave")
        val screenOn = booleanPreferencesKey("screen_on")
        val remember = booleanPreferencesKey("remember_pos")
    }

    val settings: Flow<Settings> = context.store.data.map { p ->
        Settings(
            theme = p[K.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
            gridView = p[K.grid] ?: false,
            sortBy = p[K.sort]?.let { runCatching { SortBy.valueOf(it) }.getOrNull() } ?: SortBy.NAME,
            sortDescending = p[K.desc] ?: false,
            showHidden = p[K.hidden] ?: false,
            autoSave = p[K.autosave] ?: true,
            keepScreenOn = p[K.screenOn] ?: false,
            rememberPosition = p[K.remember] ?: true,
        )
    }

    fun setTheme(m: ThemeMode) = edit { it[K.theme] = m.name }
    fun setGrid(v: Boolean) = edit { it[K.grid] = v }
    fun setSort(s: SortBy, desc: Boolean) = edit { it[K.sort] = s.name; it[K.desc] = desc }
    fun setShowHidden(v: Boolean) = edit { it[K.hidden] = v }
    fun setAutoSave(v: Boolean) = edit { it[K.autosave] = v }
    fun setKeepScreenOn(v: Boolean) = edit { it[K.screenOn] = v }
    fun setRememberPosition(v: Boolean) = edit { it[K.remember] = v }

    private fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        scope.launch { context.store.edit { block(it) } }
    }
}
