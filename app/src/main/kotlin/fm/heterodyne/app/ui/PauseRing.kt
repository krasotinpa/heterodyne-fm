package fm.heterodyne.app.ui

import androidx.compose.runtime.*
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import fm.heterodyne.app.module.arrow
import fm.heterodyne.app.module.Direction
import fm.heterodyne.app.module.TransitionSnapshot
import fm.heterodyne.app.module.TransitionState
import fm.heterodyne.app.receiver.CommandPause
import fm.heterodyne.app.receiver.PauseKind

/** Local monotonic clock. Receiver snapshots never tick for animation. */
internal class PauseRingClock(private val pause: CommandPause, private val clock: () -> Long = { android.os.SystemClock.elapsedRealtime() }) {
    fun remainingMillis() = (pause.remainingMillis - (clock() - pause.publishedAtMillis).coerceAtLeast(0)).coerceAtLeast(0)
    fun fraction() = (remainingMillis().toFloat() / pause.durationMillis).coerceIn(0f, 1f)
    fun description(): String = "Поиск ${pause.direction.arrow()}, пауза, осталось ${(remainingMillis() + 999) / 1000} с"
}

internal fun showsPauseRing(snapshot: TransitionSnapshot, direction: Direction) =
    snapshot.pause?.let { it.kind == PauseKind.Seek && it.direction == direction } == true

@Composable
internal fun SeekControl(direction: Direction, transition: TransitionSnapshot, connected: Boolean,
                         driving: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val pause = transition.pause
    val seeking = transition.state == TransitionState.Seeking
    val ring = connected && showsPauseRing(transition, direction)
    var elapsed by remember(transition) { mutableLongStateOf(0L) }
    val composedAt = remember(transition) { android.os.SystemClock.elapsedRealtime() }
    val timer = remember(transition) { pause?.let { PauseRingClock(it) { composedAt + elapsed } } }
    LaunchedEffect(transition, ring) {
        if (!ring) return@LaunchedEffect
        val start = withFrameNanos { it }
        do {
            elapsed = if (seeking) withInfiniteAnimationFrameNanos { (it - start) / 1_000_000 }
                else withFrameNanos { (it - start) / 1_000_000 }
        } while (seeking || (timer?.remainingMillis() ?: 0) > 0)
    }
    val remaining = timer?.remainingMillis() ?: 0
    val fraction = if (seeking) .28f else timer?.fraction() ?: 0f
    val color = LocalAppearanceColors.current["accent"]
    val description = if (ring && !seeking && remaining > 0) timer!!.description()
        else "Поиск ${direction.arrow()}"
    Control(direction.arrow(), connected && !driving && !seeking && pause?.kind != PauseKind.Seek,
        onClick, modifier.then(if (ring) Modifier.testTag("pause-ring-$direction").pauseRing(fraction, if (seeking) (elapsed % 1000) / 1000f else 0f, color) else Modifier),
        description = description)
}

/** Stroke follows the rounded button perimeter; a wrapped segment keeps the seeking arc continuous. */
private fun Modifier.pauseRing(fraction: Float, rotation: Float, color: Color) = drawWithContent {
    drawContent()
    val stroke = tokenDp("line.focus").toPx()
    val inset = stroke / 2
    val path = Path().apply { addRoundRect(RoundRect(Rect(inset, inset, size.width - inset, size.height - inset), CornerRadius((tokenDp("radius.m").toPx() - inset).coerceAtLeast(0f)))) }
    val measure = PathMeasure().apply { setPath(path, false) }
    val start = rotation * measure.length
    val end = start + fraction.coerceIn(0f, 1f) * measure.length
    val segment = Path()
    measure.getSegment(start, minOf(end, measure.length), segment)
    if (end > measure.length) measure.getSegment(0f, end - measure.length, segment)
    drawPath(segment, color, style = Stroke(stroke))
}
