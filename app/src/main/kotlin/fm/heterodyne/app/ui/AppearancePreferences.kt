package fm.heterodyne.app.ui

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Appearance only; Region remains owned by the station store. */
class AppearancePreferences(private val preferences: SharedPreferences) {
    var theme by mutableStateOf(ThemeChoice.entries.firstOrNull { it.name == preferences.getString("theme", null) } ?: ThemeChoice.Auto)
        private set
    var palette by mutableStateOf(Palette.entries.firstOrNull { it.token == preferences.getString("palette", null) } ?: Palette.Coral)
        private set

    fun selectTheme(value: ThemeChoice) {
        preferences.edit().putString("theme", value.name).apply()
        theme = value
    }

    fun selectPalette(value: Palette) {
        preferences.edit().putString("palette", value.token).apply()
        palette = value
    }
}
