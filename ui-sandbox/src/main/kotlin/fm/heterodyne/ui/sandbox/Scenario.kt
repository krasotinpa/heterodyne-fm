package fm.heterodyne.ui.sandbox

import fm.heterodyne.ui.contract.*

internal const val WAIT_MILLIS = 300L
internal const val REPLY_MILLIS = 1000L
internal const val TIMEOUT_MILLIS = 3000L
internal const val SEEK_SECONDS = 20

internal class Cue(val label: String, val atMillis: Long? = null, val apply: ScriptedRadioUi.() -> Unit = {})

internal enum class Scenario(val title: String, vararg val cues: Cue) {
    Working("Приёмник работает",
        Cue("Старт") { act(RadioAction.Start) }, Cue("Стоп") { act(RadioAction.Stop) }),
    Offline("Нет USB", Cue("Сменить Подборку") { act(RadioAction.ActivateSelection("stand", "Вторая")) }),
    Tune("Переход", Cue("Ожидание", WAIT_MILLIS), Cue("Ответ", REPLY_MILLIS)),
    TuneTimeout("Ошибка Перехода", Cue("Ожидание", WAIT_MILLIS), Cue("Нет подтверждения", TIMEOUT_MILLIS)),
    Seek("Идёт Поиск", Cue("Предел Поиска", SEEK_SECONDS * 1000L)),
    NoReply("Станция не найдена в сценарии", Cue("Предел без ответа", SEEK_SECONDS * 1000L)),
    Focus("Потерян фокус",
        Cue("TRANSIENT") { focus(FocusEffect.Muted) }, Cue("GAIN") { focus(FocusEffect.Normal) },
        Cue("DUCK") { focus(FocusEffect.Ducking) }, Cue("LOSS") { focus(FocusEffect.PermanentLossContinuing) }),
    Selection("Изменена Подборка", Cue("Сменить Подборку") { act(RadioAction.ActivateSelection("stand", "Вторая")) }),
    Profiles("Два профиля", Cue("Отключить выбранный приёмник") { detach() }),
    Motion("Движение и стоянка", Cue("Начать движение") { moving(true) }, Cue("Остановиться") { moving(false) }),
    Themes("Светлая и тёмная Темы", Cue("Тёмная тема") { theme(ThemeChoice.Dark) }),
}

internal val otherProfile = ReceiverOption(ReceiverId("fixture"), "Без Поиска")
internal val module = ReceiverOption(ReceiverId("module"), "Модуль")
internal val first = StationView(90300, "Маяк стенда")
internal val second = StationView(101200, "Орбита стенда")

internal fun initialSnapshot(scenario: Scenario): RadioUiState {
    val entries = listOf(first, second)
    val selections = listOf(SelectionView("Первая", entries), SelectionView("Вторая", entries.reversed()))
    val stations = StationsView(
        loaded = true, regions = listOf(RegionView("stand", "Регион стенда", false)), activeRegionId = "stand",
        selections = selections, activeSelectionName = selections.first().name,
        catalog = entries.map { CatalogStationView(it.frequencyKhz.toString(), it.frequencyKhz, requireNotNull(it.name)) },
        catalogSource = null, dataIssue = null, editIssue = null, catalogUpdate = CatalogUpdate.Idle,
    )
    val inParking = scenario == Scenario.Profiles || scenario == Scenario.Motion
    val profiles = if (scenario == Scenario.Profiles) listOf(module, otherProfile) else listOf(module)
    val light = scenario == Scenario.Themes
    return RadioUiState(
        if (inParking) ScreenMode.Parking else ScreenMode.Efir, moving = false, ReceiverView(module.id, profiles, Connection.Observing, setOf(Feature.Seek)),
        TuningView(first, null, first, TuningPhase.Idle, null, null),
        PlaybackView(PlaybackStatus.Stopped, FocusEffect.None), stations,
        SettingsView(if (light) ThemeChoice.Light else ThemeChoice.Dark, if (light) ResolvedTheme.Light else ResolvedTheme.Dark, Palette.Coral, true, false, ScaleMarker.Pin, false, SEEK_SECONDS, SignalChoice.Off),
        DiagnosticsView("UI Sandbox", scenario == Scenario.Motion, ExportState.Idle, null), emptySet(), null, null,
    )
}
