package fm.heterodyne.app.receiver

import fm.heterodyne.app.module.DeviceProfile

/** Выбор фиксируется до явной смены. Отключение не выбирает другой Приёмник. */
class ReceiverSelection(
    val kinds: List<ReceiverKind>,
    saved: ReceiverKind?,
    private val remember: (ReceiverKind) -> Unit,
) {
    @Volatile var selectedKind: ReceiverKind? = saved?.takeIf { it in kinds } ?: kinds.singleOrNull()
        private set
    var discovered = false
        private set

    fun discover(devices: List<DeviceProfile>) {
        if (discovered) return
        val connected = devices.map { it.kind }.distinct().filter { it in kinds }
        if (connected.isEmpty()) return
        discovered = true
        if (connected.size == 1) {
            selectedKind = connected.single()
            remember(connected.single())
        }
    }

    fun select(kind: ReceiverKind, parked: Boolean): Boolean {
        if (!parked || kind !in kinds || kind == selectedKind) return false
        discovered = true
        selectedKind = kind
        remember(kind)
        return true
    }
}
