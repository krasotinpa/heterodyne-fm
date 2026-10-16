package fm.heterodyne.app

class DeveloperMode(initial: Boolean, private val save: (Boolean) -> Unit) {
    var enabled = initial
        private set
    private var taps = 0
    fun tap(parked: Boolean) {
        if (!parked) { taps = 0; return }
        if (enabled) return
        if (++taps == 7) { enabled = true; taps = 0; save(true) }
    }
    fun disable() { enabled = false; taps = 0; save(false) }
}
