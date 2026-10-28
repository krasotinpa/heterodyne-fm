package fm.heterodyne.app.ui

import fm.heterodyne.app.module.Band
import fm.heterodyne.app.module.TransitionState
import fm.heterodyne.app.module.TuneTarget

data class ScaleCursor(val target: TuneTarget, val faded: Boolean)

fun scaleCursor(confirmed: TuneTarget?, saved: TuneTarget?, state: TransitionState): ScaleCursor? {
    val target = (confirmed ?: saved)?.takeIf { it.band == Band.FM } ?: return null
    return ScaleCursor(target, confirmed == null || state in listOf(
        TransitionState.Unconfirmed, TransitionState.Unresponsive, TransitionState.Disconnected))
}
