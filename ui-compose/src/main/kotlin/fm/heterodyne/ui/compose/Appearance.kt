package fm.heterodyne.ui.compose

import fm.heterodyne.ui.contract.*

internal data class ControlColorTokens(val background: String, val foreground: String)

internal fun controlColorTokens(enabled: Boolean, selected: Boolean): ControlColorTokens = when {
    !enabled -> ControlColorTokens("surface", "disabledText")
    selected -> ControlColorTokens("accent", "onAccent")
    else -> ControlColorTokens("surface2", "text")
}

internal fun controlBackgroundToken(enabled: Boolean, selected: Boolean = false,
                                    waiting: Boolean = false, outlined: Boolean = false,
                                    faded: Boolean = false): String = when {
    outlined -> "bg"
    waiting && enabled -> "accentSoft"
    else -> controlColorTokens(enabled && !faded, selected).background
}

internal val ThemeChoice.title get() = when (this) {
    ThemeChoice.Auto -> "Авто"
    ThemeChoice.Dark -> "Тёмная"
    ThemeChoice.Light -> "Светлая"
}

internal val Palette.title get() = when (this) {
    Palette.Coral -> "Коралл"
    Palette.Amber -> "Янтарь"
    Palette.Plum -> "Слива"
    Palette.Lime -> "Лайм"
}

internal val ScaleMarker.title get() = when (this) {
    ScaleMarker.Pin -> "Булавка"
    ScaleMarker.Flag -> "Флажок"
    ScaleMarker.Needle -> "Игла"
    ScaleMarker.Drop -> "Капля"
    ScaleMarker.Stripe -> "Полоска"
}

internal val SignalChoice.title get() = when (this) {
    SignalChoice.Off -> "Выключено"
    SignalChoice.Tone -> "Тон"
    SignalChoice.KeyClick -> "Звук клавиши"
    SignalChoice.Silence -> "Тишина"
}
