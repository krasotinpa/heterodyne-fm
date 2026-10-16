package fm.heterodyne.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val stations = application as StationsApplication
        setContent {
            val snapshot = stations.screen
            val current = snapshot.state
            val catalogs = snapshot.catalogs
            var linkError by remember { mutableStateOf<String?>(null) }
            val uriHandler = LocalUriHandler.current
            val style = TextStyle(color = Color.White)
            Column(Modifier.fillMaxSize().background(Color.Black).padding(16.dp)) {
                BasicText("Станции", style = style)
                if (snapshot.recovered) {
                    BasicText("Данные восстановлены: использованы значения по умолчанию", style = style)
                }
                (linkError ?: snapshot.error)?.let { BasicText(it, style = style) }
                if (current == null) {
                    BasicText("Загрузка…", style = style)
                } else {
                    val catalog = catalogs.first { it.region.id == current.selectedRegion }
                    LazyColumn {
                        items(catalogs, key = { it.region.id }) { option ->
                            BasicText(
                                (if (option.region.id == current.selectedRegion) "● " else "○ ") + option.region.name,
                                modifier = Modifier.clickable { stations.selectRegion(option.region.id) }.padding(12.dp),
                                style = style,
                            )
                        }
                        item { BasicText("Наборы", style = style) }
                        items(current.regions.getValue(current.selectedRegion).sets, key = { it.name }) { set ->
                            val active = current.regions.getValue(current.selectedRegion).activeName == set.name
                            BasicText(set.name + if (active) " — Активный" else "", Modifier.padding(12.dp), style)
                        }
                        item {
                            BasicText("Источник: ${catalog.source.text}",
                                Modifier.clickable {
                                    try { uriHandler.openUri(catalog.source.url) }
                                    catch (_: android.content.ActivityNotFoundException) { linkError = "Нет приложения для открытия ссылки" }
                                }.padding(12.dp), style)
                        }
                        item { BasicText("Справочник", style = style) }
                        items(catalog.stations, key = { it.id }) { station ->
                            BasicText("${station.name} · ${station.band} · ${station.frequencyKhz} кГц", Modifier.padding(12.dp), style)
                        }
                    }
                }
            }
        }
    }
}
