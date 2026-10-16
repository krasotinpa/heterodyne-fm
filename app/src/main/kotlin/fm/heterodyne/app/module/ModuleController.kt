package fm.heterodyne.app.module

import fm.heterodyne.app.DiagnosticLog

enum class ConnectionState { Disconnected, DeviceFound, PermissionPending, Open, Observing }

/** Single owner: the Android adapter serializes all calls on its worker. No radio TX queue exists. */
class ModuleController(
    private val transport: UsbTransport,
    private val log: DiagnosticLog,
    private val changed: (ConnectionState) -> Unit = {},
) {
    var state = ConnectionState.Disconnected; private set
    private var profile: DeviceProfile? = null
    private var generation = 0L
    private var pending: Long? = null
    val pendingCount get() = if (pending == null) 0 else 1
    val deviceId get() = profile?.deviceId

    private fun safeLog(event: String) {
        try { log.event(event) } catch (_: Exception) {
            // Journal failures must never prevent USB cleanup or state changes.
        }
    }

    private fun transition(next: ConnectionState) { state = next; changed(next) }
    fun found(device: DeviceProfile) {
        if (profile?.deviceId == device.deviceId) return
        if (profile != null) disconnect()
        profile = device
        safeLog("USB found")
        transition(ConnectionState.DeviceFound)
    }
    fun requestPermission(): Long? {
        if (profile == null || pending != null || state == ConnectionState.Observing) return null
        transition(ConnectionState.DeviceFound)
        pending = ++generation
        safeLog("USB permission requested")
        transition(ConnectionState.PermissionPending)
        return pending
    }
    fun permissionResult(request: Long, granted: Boolean) {
        if (pending != request) return
        pending = null
        if (!granted) {
            safeLog("USB permission denied")
            transition(ConnectionState.Disconnected)
            return
        }
        safeLog("USB permission granted")
        try {
            transport.open(checkNotNull(profile))
            safeLog("USB open")
            transition(ConnectionState.Open)
            transition(ConnectionState.Observing)
        } catch (_: Exception) { error() }
    }
    fun detach(id: String) {
        if (profile?.deviceId != id) return
        try {
            safeLog("USB detach")
            disconnect()
        } finally { profile = null }
    }
    fun error() {
        // Exception messages may contain device paths or identifiers.
        safeLog("USB error")
        disconnect()
    }
    fun close() { try { disconnect() } finally { profile = null } }
    private fun disconnect() {
        pending = null
        generation++
        try { transport.close() } catch (_: Exception) { safeLog("USB error") }
        finally { safeLog("USB close"); transition(ConnectionState.Disconnected) }
    }
}
