package fm.heterodyne.ui.compose

import fm.heterodyne.ui.contract.*

internal fun DisabledReason.text() = when (this) {
    DisabledReason.Unresponsive -> "Модуль не отвечает, переподключите USB"
    DisabledReason.Disconnected -> "Приёмник отключён"
    DisabledReason.PermissionPending -> "Нужно разрешение USB"
    DisabledReason.Connecting -> "Подключение идёт"
    DisabledReason.Unconfirmed -> "Нет ответа от приёмника"
    DisabledReason.EmptySelection -> "Подборка пуста"
}

internal fun DataIssue.text() = when (this) {
    DataIssue.LoadFailed -> "Не удалось прочитать Справочники"
    DataIssue.SaveFailed -> "Не удалось сохранить состояние"
    DataIssue.Recovered -> "Данные восстановлены: использованы значения по умолчанию"
}

internal fun RejectReason.text() = when (this) {
    RejectReason.WrongMode -> "Действие недоступно в этом режиме"
    RejectReason.NotForeground -> "Приложение в фоне"
    RejectReason.NotConnected -> "Приёмник отключён"
    RejectReason.Unsupported -> "Приёмник не поддерживает действие"
    RejectReason.Busy -> "Предыдущее действие ещё выполняется"
    RejectReason.RateLimited -> "Дождитесь окончания паузы"
    RejectReason.InvalidInput -> "Проверьте введённое значение"
    RejectReason.StaleContext -> "Данные изменились"
    RejectReason.IncompatibleState -> "Действие сейчас недоступно"
    RejectReason.TransportError -> "Ошибка связи с приёмником"
}
