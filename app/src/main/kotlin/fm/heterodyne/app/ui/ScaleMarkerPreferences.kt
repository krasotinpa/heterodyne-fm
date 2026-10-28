package fm.heterodyne.app.ui

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class ScaleMarker(val title: String) {
    Pin("Булавка"), Flag("Флажок"), Needle("Игла"), Drop("Капля"), Stripe("Полоска")
}

/** Appearance only; selecting a marker never changes the tuned frequency. */
class ScaleMarkerPreferences(private val preferences: SharedPreferences) {
    var marker by mutableStateOf(ScaleMarker.entries.firstOrNull {
        it.name == preferences.getString("marker", null)
    } ?: ScaleMarker.Pin)
        private set

    fun selectMarker(value: ScaleMarker) {
        preferences.edit().putString("marker", value.name).apply()
        marker = value
    }
}
