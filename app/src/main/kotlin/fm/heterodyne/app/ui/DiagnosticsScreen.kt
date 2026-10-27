package fm.heterodyne.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Canvas
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import fm.heterodyne.app.BuildConfig
import fm.heterodyne.app.StationsDiagnostics

@Composable
fun DiagnosticsScreen(app: StationsDiagnostics, parked: Boolean, connected: Boolean, export: () -> Unit,
                      receiverKind: fm.heterodyne.app.receiver.ReceiverKind? = fm.heterodyne.app.receiver.ReceiverKind.Module,
                      receiverName: (fm.heterodyne.app.receiver.ReceiverKind) -> String = fm.heterodyne.app.receiver.ReceiverProfiles.Module::displayName) {
    DiagnosticsContent(parked, connected, app.developerMode.enabled, app.exportBusy, app.diagnosticMessage,
        { app.developerMode.setEnabled(it, parked) }, export, receiverKind, receiverName)
}

@Composable
internal fun DiagnosticsContent(parked: Boolean, connected: Boolean, developerEnabled: Boolean,
                                exportBusy: Boolean, message: String?, setDeveloper: (Boolean) -> Unit,
                                export: () -> Unit, receiverKind: fm.heterodyne.app.receiver.ReceiverKind? = fm.heterodyne.app.receiver.ReceiverKind.Module,
                      receiverName: (fm.heterodyne.app.receiver.ReceiverKind) -> String = fm.heterodyne.app.receiver.ReceiverProfiles.Module::displayName) {
    val colors = LocalAppearanceColors.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(DesignTokens.number("space.section").dp)) {
        Label("Диагностика", 24)
        Row(horizontalArrangement = Arrangement.spacedBy(DesignTokens.number("space.section").dp)) {
            ParkingCard(Modifier.weight(1f).testTag("diagnostics-version")) {
                Label("Версия приложения", 16)
                Label(BuildConfig.VERSION_NAME, 22)
            }
            ParkingCard(Modifier.weight(1f)) {
                Label(receiverKind?.let(receiverName) ?: "Приёмник не выбран", 16)
                Label("Связь с приёмником", 16)
                Label(if (connected) "Приёмник подключён" else "Приёмник не подключён", 22)
            }
        }
        ParkingCard(Modifier.fillMaxWidth()) {
            Label("Журнал сохраняется в Download внутренней памяти.", 16)
            Label("Скопируйте файл на SSD файловым менеджером.", 16)
            Control(if (exportBusy) "Экспорт…" else "Экспорт Журнала",
                parked && !exportBusy, export)
            message?.let { Label(it, 16) }
        }
        ParkingCard(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().heightIn(min = DesignTokens.number("size.touch.min").dp)
                .testTag("developer-switch")
                .toggleable(developerEnabled, enabled = parked, role = Role.Switch) {
                    if (parked) setDeveloper(it)
                }, verticalAlignment = Alignment.CenterVertically) {
                BasicText("Режим разработчика: ${if (developerEnabled) "Вкл" else "Выкл"}",
                    Modifier.weight(1f), style = designTextStyle("body", colors["text"]))
                Canvas(Modifier.size(DesignTokens.number("size.touch.min").dp)) {
                    val inset = DesignTokens.number("space.section").dp.toPx()
                    val radius = DesignTokens.number("space.plateGap").dp.toPx()
                    drawRoundRect(if (developerEnabled) colors["accent"] else colors["edge"],
                        topLeft = Offset(0f, inset), size = Size(size.width, size.height - inset * 2),
                        cornerRadius = CornerRadius(inset))
                    drawCircle(if (developerEnabled) colors["onAccent"] else colors["surface"], radius,
                        center = Offset(if (developerEnabled) size.width - inset else inset, size.height / 2))
                }
            }
            Label("Поток кадров, счётчики звука и кнопки команд. В Эфире виден значок «РАЗРАБОТЧИК».", 16)
        }
    }
}
