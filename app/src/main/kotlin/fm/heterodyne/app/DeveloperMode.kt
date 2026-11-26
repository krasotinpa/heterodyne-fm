package fm.heterodyne.app


class DeveloperMode(initial: Boolean, private val save: (Boolean) -> Unit) {
    var enabled = initial
        private set

    fun setEnabled(value: Boolean, parked: Boolean) {
        if ((value && !parked) || value == enabled) return
        enabled = value
        save(value)
    }
}
