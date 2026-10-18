package fm.heterodyne.app.ui

enum class ThemeChoice(val title: String) { Auto("Авто"), Dark("Тёмная"), Light("Светлая") }
enum class ResolvedTheme(val token: String) { Dark("dark"), Light("light") }
enum class Palette(val token: String, val title: String) {
    Coral("coral", "Коралл"), Amber("amber", "Янтарь"), Plum("plum", "Слива"), Lime("lime", "Лайм")
}

/** null means the head unit has not supplied day/night data. */
fun resolveTheme(choice: ThemeChoice, headUnitNight: Boolean?): ResolvedTheme = when (choice) {
    ThemeChoice.Dark -> ResolvedTheme.Dark
    ThemeChoice.Light -> ResolvedTheme.Light
    ThemeChoice.Auto -> if (headUnitNight == false) ResolvedTheme.Light else ResolvedTheme.Dark
}

data class ControlColorTokens(val background: String, val foreground: String)

fun controlColorTokens(enabled: Boolean, selected: Boolean): ControlColorTokens = when {
    !enabled -> ControlColorTokens("surface", "disabledText")
    selected -> ControlColorTokens("accent", "onAccent")
    else -> ControlColorTokens("surface2", "text")
}
