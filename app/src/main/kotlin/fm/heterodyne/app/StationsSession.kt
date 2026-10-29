package fm.heterodyne.app

import android.os.Handler
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import fm.heterodyne.app.module.Band
import fm.heterodyne.app.module.TuneTarget
import fm.heterodyne.app.stations.AssetsCatalogReader
import fm.heterodyne.app.stations.AtomicStationsFile
import fm.heterodyne.app.stations.Catalog
import fm.heterodyne.app.stations.CatalogStore
import fm.heterodyne.app.stations.LoadStatus
import fm.heterodyne.app.stations.SessionStation
import fm.heterodyne.app.stations.StationsFile
import fm.heterodyne.app.stations.StationsRepository
import fm.heterodyne.app.stations.StationsState
import fm.heterodyne.app.stations.initialSelections
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors

data class StationsScreenState(
    val state: StationsState? = null,
    val catalogs: List<Catalog> = emptyList(),
    val recovered: Boolean = false,
    val error: String? = null,
    val nameError: String? = null,
    val frequencyError: String? = null,
    val catalogUpdateBusy: Boolean = false,
    val catalogUpdateResult: String? = null,
) {
    val regions: List<fm.heterodyne.app.stations.Region> get() {
        val active = catalogs.map { it.region }
        val obsolete = state?.regions.orEmpty().keys.filter { id -> active.none { it.id == id } }
            .map { fm.heterodyne.app.stations.Region(it, state?.regionNames?.get(it) ?: it) }
        val russian = java.text.Collator.getInstance(java.util.Locale.forLanguageTag("ru")) as java.text.RuleBasedCollator
        val order = java.text.RuleBasedCollator(russian.rules + "& е , Е < ё , Ё").apply {
            strength = java.text.Collator.SECONDARY
        }
        return (active + obsolete).sortedWith { a, b -> order.compare(a.name, b.name) }
    }
}

/** Owns the repository and its single storage queue for this process. */
class StationsSession(
    private val context: android.content.Context,
    private val main: Handler,
    private val storage: Executor = Executors.newSingleThreadExecutor(),
    file: StationsFile = AtomicStationsFile(File(context.filesDir, "stations.json")),
    private val network: Executor = Executors.newSingleThreadExecutor(),
    private val onlineCatalog: () -> fm.heterodyne.app.stations.CatalogReader = { fm.heterodyne.app.stations.HttpsCatalogReader() },
    private val catalogStore: CatalogStore = CatalogStore(File(context.filesDir, "catalog"),
        AssetsCatalogReader { context.assets.open("catalogs/$it") }),
) {
    private val repository = StationsRepository(file)
    // Only the storage queue reads or changes these values.
    private var current = StationsScreenState()
    private var pendingLastPlayed: Boolean? = null
    var screen by mutableStateOf(StationsScreenState())
        private set
    val activeEntries get() = screen.state?.let { it.regionSelections(it.selectedRegion).active.entries }.orEmpty()

    private val updateLock = Any()
    private var updateAllowed = false
    private var updateGeneration = 0L
    private var updating = false
    private var download: fm.heterodyne.app.stations.CatalogReader? = null

    /** Вызывается при выходе из стоянки, начале движения и уходе Activity в фон. */
    fun allowCatalogUpdate(allowed: Boolean) = synchronized(updateLock) {
        if (updateAllowed == allowed) return@synchronized
        updateAllowed = allowed
        if (!allowed) {
            updateGeneration++
            download?.close()
            download = null
            updating = false
            storage.execute {
                current = current.copy(catalogUpdateBusy = false)
                publish()
            }
        }
    }

    fun updateCatalog() = synchronized(updateLock) {
        if (!updateAllowed || updating) return@synchronized
        updating = true
        val generation = ++updateGeneration
        storage.execute {
            current = current.copy(catalogUpdateBusy = true, catalogUpdateResult = null)
            publish()
        }
        network.execute {
            val result = runCatching {
                val reader = synchronized(updateLock) {
                    check(updateAllowed && generation == updateGeneration)
                    onlineCatalog().also { download = it }
                }
                reader.use {
                    val files = linkedMapOf<String, ByteArray>()
                    val candidate = fm.heterodyne.app.stations.loadCatalogSet(fm.heterodyne.app.stations.CatalogReader { name ->
                        synchronized(updateLock) { check(updateAllowed && generation == updateGeneration) }
                        reader.read(name).copyOf().also { files[name] = it }
                    })
                    candidate to files
                }
            }
            storage.execute {
                val outcome = runCatching {
                    synchronized(updateLock) { check(updateAllowed && generation == updateGeneration) }
                    val (candidate, files) = result.getOrThrow()
                    if (candidate.revision <= catalogStore.active().revision) null
                    else catalogStore.install(fm.heterodyne.app.stations.CatalogReader { files.getValue(it) },
                        publish = { commit ->
                            synchronized(updateLock) {
                                check(updateAllowed && generation == updateGeneration)
                                commit()
                            }
                        }).getOrThrow()
                }
                synchronized(updateLock) {
                    if (!updateAllowed || generation != updateGeneration) return@execute
                    download = null
                    val installed = outcome.getOrNull()
                    if (installed != null) current = current.copy(catalogs = installed.catalogs)
                    val message = when {
                        outcome.isFailure -> "Не удалось обновить Справочник"
                        installed == null -> "Нет обновлений"
                        else -> "Справочник обновлён: Ревизия ${installed.revision}"
                    }
                    updating = false
                    current = current.copy(catalogUpdateBusy = false, catalogUpdateResult = message)
                    publish()
                }
            }
        }
    }

    fun activateSelection(name: String) {
        storage.execute {
            val state = current.state ?: return@execute
            // Молчаливый выход при NOT_FOUND: в Режиме движения Подборки не правят, значит случай недостижим.
            // В Режиме стоянки это гонка быстрых нажатий («Удалить», сразу «Сделать активной»); экран уже обновился.
            val region = state.regionSelections(state.selectedRegion).activate(name).value ?: return@execute
            save(state.copy(regions = state.regions + (state.selectedRegion to region)))
        }
    }

    fun clearNameError() {
        storage.execute {
            if (current.nameError == null && current.frequencyError == null) return@execute
            current = current.copy(nameError = null, frequencyError = null)
            publish()
        }
    }

    fun createSelection(regionId: String, name: String) = changeRegion(regionId) { it.createSelection(name) }
    fun renameSelection(regionId: String, name: String, newName: String) = changeRegion(regionId) { it.rename(name, newName) }
    fun deleteSelection(regionId: String, name: String) = changeRegion(regionId) { it.delete(name) }
    fun addStation(regionId: String, name: String, entry: fm.heterodyne.app.stations.StationEntry) = changeSelection(regionId, name) {
        val trimmed = entry.name?.trim().orEmpty()
        when {
            trimmed.isEmpty() || trimmed.length > 32 -> fm.heterodyne.app.stations.Change(error = fm.heterodyne.app.stations.ChangeError.INVALID_NAME)
            !fm.heterodyne.app.module.isSelectableFrequency(Band.FM, entry.frequencyKhz) ->
                fm.heterodyne.app.stations.Change(error = fm.heterodyne.app.stations.ChangeError.INVALID_FREQUENCY)
            else -> it.add(entry.copy(name = trimmed))
        }
    }
    fun editStation(regionId: String, name: String, entry: fm.heterodyne.app.stations.StationEntry, newName: String) =
        changeSelection(regionId, name) { it.editStation(entry, newName) }
    fun removeStation(regionId: String, name: String, entry: fm.heterodyne.app.stations.StationEntry) = changeSelection(regionId, name) { it.remove(entry) }
    fun moveStation(regionId: String, name: String, entry: fm.heterodyne.app.stations.StationEntry, to: Int) = changeSelection(regionId, name) { selection ->
        val from = selection.entries.indexOfFirst { it.frequencyKhz == entry.frequencyKhz }
        selection.move(from, to)
    }

    private fun changeSelection(regionId: String, name: String, change: (fm.heterodyne.app.stations.StationSelection) -> fm.heterodyne.app.stations.Change<fm.heterodyne.app.stations.StationSelection>) =
        changeRegion(regionId, station = true) { region ->
            val selection = region.selections.firstOrNull { it.name == name }
            val result = selection?.let(change)
            fm.heterodyne.app.stations.Change(result?.value?.let { updated ->
                fm.heterodyne.app.stations.RegionSelections.restore(region.selections.map {
                    if (it.name == name) updated else it
                }, region.activeName)
            }, result?.error ?: if (selection == null) fm.heterodyne.app.stations.ChangeError.NOT_FOUND else null)
        }

    private fun changeRegion(regionId: String, station: Boolean = false, change: (fm.heterodyne.app.stations.RegionSelections) -> fm.heterodyne.app.stations.Change<fm.heterodyne.app.stations.RegionSelections>) {
        storage.execute {
            // Молчаливые выходы, сообщения пользователю нет:
            // 1. state == null бывает только при загрузке; экран показывает «Загрузка…», кнопок правки нет.
            // 2. Устаревший Регион — гонка двух быстрых нажатий в Режиме стоянки; экран уже показывает новый Регион,
            //    и сообщение лишь запутало бы пользователя.
            val state = current.state ?: return@execute
            if (regionId != state.selectedRegion) return@execute
            val result = change(state.regionSelections(regionId))
            val region = result.value
            if (region != null) save(state.copy(regions = state.regions + (regionId to region)))
            else {
                val message = when (result.error) {
                    fm.heterodyne.app.stations.ChangeError.INVALID_NAME -> if (station) "Введите имя Станции: от 1 до 32 символов" else "Введите имя Подборки"
                    fm.heterodyne.app.stations.ChangeError.INVALID_FREQUENCY -> "Введите FM-частоту 87,5–108,0 МГц с шагом 0,1"
                    fm.heterodyne.app.stations.ChangeError.DUPLICATE_NAME -> "Подборка с таким именем уже есть"
                    fm.heterodyne.app.stations.ChangeError.DUPLICATE_FREQUENCY -> "Частота уже есть в Подборке"
                    fm.heterodyne.app.stations.ChangeError.LAST_SELECTION -> "Нельзя удалить последнюю Подборку"
                    else -> "Подборка или Станция не найдены"
                }
                current = when (result.error) {
                    fm.heterodyne.app.stations.ChangeError.INVALID_NAME,
                    fm.heterodyne.app.stations.ChangeError.DUPLICATE_NAME -> current.copy(nameError = message, frequencyError = null)
                    fm.heterodyne.app.stations.ChangeError.DUPLICATE_FREQUENCY,
                    fm.heterodyne.app.stations.ChangeError.INVALID_FREQUENCY -> current.copy(frequencyError = message, nameError = null)
                    else -> current.copy(error = message)
                }
                publish()
            }
        }
    }

    fun load(loaded: (TuneTarget?) -> Unit) {
        storage.execute {
            try {
                val catalogs = catalogStore.active().catalogs
                val loaded = repository.load()
                val state = if (catalogs.any { it.region.id == loaded.state.selectedRegion }) loaded.state
                    else loaded.state.copy(selectedRegion = catalogs.firstOrNull { it.region.id == "moskva" }?.region?.id
                        ?: catalogs.first().region.id)
                current = StationsScreenState(state, catalogs, loaded.status == LoadStatus.RECOVERED)
                if (loaded.status == LoadStatus.MISSING) save(initialSelections(loaded.copy(state = state), catalogs))
                else if (loaded.status == LoadStatus.LOADED) save(state)
            } catch (_: Exception) {
                current = current.copy(error = "Не удалось прочитать Справочники")
            }
            publish()
            val station = current.state?.lastStation
            loaded(station?.let { TuneTarget(Band.FM, it.frequencyKhz) })
        }
    }

    /** Serialize with Stop and station saves; reopening must not use the process's original flag. */
    fun readLaunchStation(ready: (TuneTarget?) -> Unit) {
        storage.execute {
            val saved = repository.load()
            val station = saved.state.lastStation?.takeIf {
                current.state != null && pendingLastPlayed == null &&
                    saved.status == LoadStatus.LOADED && saved.state.lastPlayed
            }
            ready(station?.let { TuneTarget(Band.FM, it.frequencyKhz) })
        }
    }

    fun rememberStation(band: Band, frequency: Int, saved: () -> Unit) {
        storage.execute {
            val state = current.state ?: return@execute
            if (!fm.heterodyne.app.module.isSelectableFrequency(band, frequency)) return@execute
            val station = SessionStation(frequency)
            if (save(state.copy(lastStation = station))) saved()
        }
    }

    fun setLastPlayed(value: Boolean) {
        storage.execute {
            val state = current.state ?: return@execute
            pendingLastPlayed = value
            save(state)
        }
    }

    fun selectRegion(id: String) {
        storage.execute {
            val state = current.state ?: return@execute
            if (current.catalogs.none { it.region.id == id } && id !in state.regions) return@execute // устаревшее действие UI отбрасывается молча, как в changeRegion
            val next = state.copy(selectedRegion = id, regions = state.regions + (id to state.regionSelections(id)))
            save(next)
        }
    }

    fun deleteObsoleteRegion(id: String) {
        storage.execute {
            val state = current.state ?: return@execute
            if (id !in state.regions || current.catalogs.any { it.region.id == id }) return@execute
            val selected = if (state.selectedRegion == id)
                current.catalogs.firstOrNull { it.region.id == "moskva" }?.region?.id
                    ?: current.catalogs.first().region.id
                else state.selectedRegion
            save(state.copy(selectedRegion = selected, regions = state.regions - id, regionNames = state.regionNames - id))
        }
    }

    private fun save(state: StationsState): Boolean {
        val named = state.copy(regionNames = state.regionNames + current.catalogs
            .filter { it.region.id in state.regions }.associate { it.region.id to it.region.name })
        val next = pendingLastPlayed?.let { named.copy(lastPlayed = it) } ?: named
        if (pendingLastPlayed == null && next == current.state && !current.recovered && current.error == null && current.nameError == null && current.frequencyError == null) return true
        val saved = repository.save(next)
        if (saved) pendingLastPlayed = null
        current = if (saved) current.copy(state = next, error = null, nameError = null, frequencyError = null)
        else current.copy(error = "Не удалось сохранить состояние")
        publish()
        return saved
    }

    private fun publish() {
        val snapshot = current
        main.post { screen = snapshot }
    }
}
