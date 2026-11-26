package fm.heterodyne.ui.compose

import fm.heterodyne.ui.contract.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp

@Composable
internal fun AboutScreen(version: String, source: CatalogSourceView?) {
    val uri = LocalUriHandler.current
    var error by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(DesignTokens.number("space.section").dp)) {
        Label("О приложении", 24)
        ParkingCard(Modifier.fillMaxWidth()) {
            Label("Heterodyne FM", 22)
            Label("Версия $version", 18)
        }
        ParkingCard(Modifier.fillMaxWidth()) {
            Label("Справочник Станций", 18)
            source?.let {
                Control("Источник: ${it.name}", true, {
                    try { uri.openUri(it.url) } catch (_: Exception) { error = true }
                })
            }
            if (error) Label("Нет приложения для открытия ссылки", 18)
        }
    }
}
