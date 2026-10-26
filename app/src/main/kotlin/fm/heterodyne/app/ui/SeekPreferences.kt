package fm.heterodyne.app.ui

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Search duration only; command rate limits remain owned by ModuleController. */
class SeekPreferences(private val preferences: SharedPreferences) {
    var seconds by mutableStateOf(readSeconds(preferences))
        private set

    fun selectSeconds(value: Int) {
        require(value in choices)
        preferences.edit().putInt("seekLimitSeconds", value).apply()
        seconds = value
    }

    companion object {
        val choices = (10..60 step 5).toList()
        fun readSeconds(preferences: SharedPreferences): Int =
            preferences.getInt("seekLimitSeconds", 20).takeIf { it in choices } ?: 20
    }
}
