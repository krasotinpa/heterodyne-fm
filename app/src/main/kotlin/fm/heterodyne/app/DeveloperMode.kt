package fm.heterodyne.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class DeveloperMode(initial: Boolean, private val save: (Boolean) -> Unit) {
    var enabled by mutableStateOf(initial)
        private set

    fun setEnabled(value: Boolean, parked: Boolean) {
        if ((value && !parked) || value == enabled) return
        enabled = value
        save(value)
    }
}
