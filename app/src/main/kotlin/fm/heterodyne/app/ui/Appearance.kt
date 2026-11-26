package fm.heterodyne.app.ui

data class ControlColorTokens(val background: String, val foreground: String)

fun controlColorTokens(enabled: Boolean, selected: Boolean): ControlColorTokens = when {
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
