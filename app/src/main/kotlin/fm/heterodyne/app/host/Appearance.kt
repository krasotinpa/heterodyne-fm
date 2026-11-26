package fm.heterodyne.app.host

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

/** The Android night configuration is the available head-unit day/night input. */
fun headUnitNight(uiMode: Int): Boolean? = when (uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) {
    android.content.res.Configuration.UI_MODE_NIGHT_YES -> true
    android.content.res.Configuration.UI_MODE_NIGHT_NO -> false
    else -> null
}
