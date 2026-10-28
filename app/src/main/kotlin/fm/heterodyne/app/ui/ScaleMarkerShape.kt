package fm.heterodyne.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import fm.heterodyne.app.module.TuneTarget

/** Non-interactive decoration: its tip stays at the frequency on the scale. */
@Composable
internal fun ScaleMarkerShape(marker: ScaleMarker, target: TuneTarget, outline: Boolean, faded: Boolean,
                              modifier: Modifier = Modifier) {
    val colors = LocalAppearanceColors.current
    val ink = colors[if (faded) "disabledText" else "accent"]
    val title = frequencyText(target)
    Box(modifier.size(tokenDp("size.scale.marker.width"), tokenDp("size.scale.marker.height"))
        .semantics { contentDescription = "${if (outline) "Цель" else "Подтверждённая Станция"} $title" }) {
        Canvas(Modifier.fillMaxSize().testTag("scale-marker-${marker.name}-${if (outline) "outline" else "filled"}")) {
            fun px(name: String) = tokenDp("size.scale.marker.$name").toPx()
            val x = size.width / 2
            val bottom = size.height
            val stroke = px("outline")
            val style = if (outline) Stroke(stroke) else Fill
            fun stem(width: Float, height: Float) {
                // A hollow stem keeps the same silhouette as its filled counterpart.
                drawRoundRect(ink, Offset(x - width / 2, bottom - height), Size(width, height),
                    CornerRadius(if (marker == ScaleMarker.Pin) width / 2 else 0f),
                    style = if (outline) Stroke(minOf(stroke, width / 3)) else Fill)
            }
            when (marker) {
                ScaleMarker.Pin -> {
                    stem(px("pinStem"), if (outline) px("pinHeadCenter") - px("pinHead") / 2 else px("pinStemHeight"))
                    drawCircle(ink, px("pinHead") / 2, Offset(x, bottom - px("pinHeadCenter")), style = style)
                }
                ScaleMarker.Flag -> {
                    stem(px("flagStem"), if (outline) px("flagBottom") else px("flagStemHeight"))
                    val flagWidth = px("flagWidth")
                    val flagHeight = px("flagHeight")
                    val flagBottom = bottom - px("flagBottom")
                    drawRoundRect(ink, Offset(x - flagWidth / 2, flagBottom - flagHeight),
                        Size(flagWidth, flagHeight), CornerRadius(px("flagRadius")), style = style)
                    val tip = px("flagTip")
                    drawPath(Path().apply {
                        moveTo(x - tip, flagBottom); lineTo(x + tip, flagBottom)
                        lineTo(x, flagBottom + tip); close()
                    }, ink, style = style)
                }
                ScaleMarker.Needle -> {
                    val center = Offset(x, bottom - px("needleHeadCenter"))
                    drawCircle(colors["accentSoft"], px("needleRing") / 2, center)
                    drawCircle(ink, px("needleRing") / 2 - stroke / 2, center, style = Stroke(stroke))
                    stem(px("needleStem"), px("needleStemHeight"))
                    drawCircle(ink, px("needleHead") / 2, center, style = style)
                }
                ScaleMarker.Drop -> {
                    val w = px("dropWidth")
                    val h = px("dropHeight")
                    val left = x - w / 2
                    val top = bottom - h
                    fun dx(v: Float) = left + v / 34 * w
                    fun dy(v: Float) = top + v / 50 * h
                    drawPath(Path().apply {
                        moveTo(dx(17f), dy(50f))
                        cubicTo(dx(17f), dy(50f), dx(2f), dy(30f), dx(2f), dy(17f))
                        arcTo(Rect(dx(2f), dy(2f), dx(32f), dy(32f)), 180f, 180f, false)
                        cubicTo(dx(32f), dy(30f), dx(17f), dy(50f), dx(17f), dy(50f)); close()
                    }, ink, style = style)
                    drawCircle(if (outline) ink else colors["onAccent"], px("dropDot") / 2,
                        Offset(dx(17f), dy(17f)), style = if (outline) Stroke(stroke) else Fill)
                }
                ScaleMarker.Stripe -> Unit // The legacy stripe remains in BandScale.
            }
        }
        if (marker == ScaleMarker.Flag) {
            Box(Modifier.align(Alignment.TopCenter)
                .padding(top = tokenDp("size.scale.marker.flagTop"))
                .size(tokenDp("size.scale.marker.flagWidth"), tokenDp("size.scale.marker.flagHeight")),
                contentAlignment = Alignment.Center) {
                BasicText(title.substringBefore(' '), style = designTextStyle("button",
                    colors[if (outline) "accentText" else if (faded) "surface" else "onAccent"]), maxLines = 1)
            }
        }
    }
}
