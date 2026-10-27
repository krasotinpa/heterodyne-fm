package fm.heterodyne.app.ui

import fm.heterodyne.app.module.Band
import fm.heterodyne.app.module.ConnectionState
import fm.heterodyne.app.module.TransitionState
import fm.heterodyne.app.stations.StationEntry

enum class DisabledReason(val text: String) {
    Unresponsive("Модуль не отвечает, переподключите USB"),
    Disconnected("Приёмник отключён"),
    PermissionPending("Нужно разрешение USB"),
    Connecting("Подключение идёт"),
    Unconfirmed("Нет ответа от приёмника"),
    NoFocus("Нет фокуса, нажмите Старт"),
    EmptySelection("Подборка пуста"),
}

fun disabledReason(connectionState: ConnectionState, transitionState: TransitionState,
                   band: Band?, entries: List<StationEntry>): DisabledReason? = when {
    transitionState == TransitionState.Unresponsive -> DisabledReason.Unresponsive
    connectionState == ConnectionState.Disconnected -> DisabledReason.Disconnected
    connectionState == ConnectionState.PermissionPending -> DisabledReason.PermissionPending
    connectionState == ConnectionState.DeviceFound || connectionState == ConnectionState.Open -> DisabledReason.Connecting
    transitionState == TransitionState.Unconfirmed -> DisabledReason.Unconfirmed
    band == null -> DisabledReason.Connecting
    entries.isEmpty() -> DisabledReason.EmptySelection
    else -> null
}
