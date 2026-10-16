package fm.heterodyne.app.module

/** Worker-owned restoration. Only a sent return probe opens the band-reply window. */
class SessionRestart {
    private var saved: TuneTarget? = null
    private var returnStation: TuneTarget? = null
    private var waitingSince: Long? = null
    private var bandReceived = false
    private var manual = false

    fun restore(station: TuneTarget?) { saved = station }
    fun connection(state: ConnectionState) {
        if (state == ConnectionState.Disconnected) {
            manual = false
            waitingSince = null
            bandReceived = false
            returnStation = null
        } else if (state == ConnectionState.Observing) {
            // RX saved during a deferred probe must not replace this connection's target.
            returnStation = saved
        }
    }
    fun returnProbeSent(now: Long) {
        if (manual) return
        waitingSince = now
        bandReceived = false
    }
    fun bandReceived() {
        if (waitingSince != null) bandReceived = true
    }
    fun manualSelection() { manual = true; waitingSince = null }
    fun take(now: Long): TuneTarget? {
        val start = waitingSince ?: return null
        if (now - start >= 3000) { waitingSince = null; return null }
        if (!bandReceived) return null
        waitingSince = null
        return returnStation
    }
}
