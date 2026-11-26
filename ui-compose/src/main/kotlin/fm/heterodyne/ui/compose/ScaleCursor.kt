package fm.heterodyne.ui.compose

import fm.heterodyne.ui.contract.*

internal data class ScaleCursor(val frequencyKhz: Int, val faded: Boolean)

internal fun scaleCursor(confirmed: StationView?, saved: StationView?, state: TuningPhase): ScaleCursor? {
    val target = (confirmed ?: saved) ?: return null
    return ScaleCursor(target.frequencyKhz, confirmed == null || state in listOf(
        TuningPhase.Unconfirmed, TuningPhase.Unresponsive, TuningPhase.Disconnected))
}
