package fm.heterodyne.app

import android.os.Handler
import fm.heterodyne.ui.contract.*
import fm.heterodyne.app.stations.ChangeError
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
    val editIssue: EditIssue? = null,
    val dataIssue: DataIssue? = null,
    val update: CatalogUpdate = CatalogUpdate.Idle,
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
    // Storage writes immutable snapshots. The receiver worker can validate a station reference.
    @Volatile private var current = StationsScreenState()
    private var pendingLastPlayed: Boolean? = null
    internal var changed: () -> Unit = {}
    internal var captureAccess: (Boolean) -> (() -> Boolean) = { { true } }
    private var editIssueAccess: () -> Boolean = { true }
    private fun edit(parkedOnly: Boolean = true, task: (() -> Boolean) -> Unit) {
        val valid = captureAccess(parkedOnly)
        storage.execute { if (valid()) task(valid) }
    }
    var screen = StationsScreenState()
        private set
    val activeEntries get() = screen.state?.let { it.regionSelections(it.selectedRegion).active.entries }.orEmpty()

    internal fun stationAccess(action: RadioAction, frequency: Int): () -> Boolean {
        val region = screen.state?.selectedRegion
        val selection = screen.state?.regionSelections(region.orEmpty())?.active
        return {
            val latest = current
            when (action) {
                is RadioAction.SelectStation -> action.regionId == latest.state?.selectedRegion && when (val ref = action.ref) {
                    is StationRef.Selection -> latest.state.regionSelections(action.regionId).selections
                        .any { it.name == ref.selectionName && it.entries.any { station -> station.frequencyKhz == ref.frequencyKhz } }
                    is StationRef.Catalog -> latest.catalogs.firstOrNull { it.region.id == action.regionId }?.stations
                        ?.any { it.id == ref.stationId && it.frequencyKhz == frequency } == true
                }
                is RadioAction.MoveInSelection -> region != null && latest.state?.selectedRegion == region &&
                    latest.state.regionSelections(region).active == selection
                else -> true
            }
        }
    }

    private val updateLock = Any()
    private var updateAllowed = false
    private var updateGeneration = 0L
    @Volatile private var updating = false
    internal val catalogBusy get() = updating
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
                current = current.copy(catalogUpdateBusy = false, update = CatalogUpdate.Idle)
                publish()
            }
        }
    }

    fun updateCatalog() = synchronized(updateLock) {
        if (!updateAllowed || updating) return@synchronized
        updating = true
        val valid = captureAccess(true)
        val generation = ++updateGeneration
        storage.execute {
            current = current.copy(update = CatalogUpdate.InProgress, catalogUpdateBusy = true, catalogUpdateResult = null)
            publish()
        }
        network.execute {
            val result = runCatching {
                val reader = synchronized(updateLock) {
                    check(valid() && updateAllowed && generation == updateGeneration)
                    onlineCatalog().also { download = it }
                }
                reader.use {
                    val files = linkedMapOf<String, ByteArray>()
                    val candidate = fm.heterodyne.app.stations.loadCatalogSet(fm.heterodyne.app.stations.CatalogReader { name ->
                        synchronized(updateLock) { check(valid() && updateAllowed && generation == updateGeneration) }
                        reader.read(name).copyOf().also { files[name] = it }
                    })
                    candidate to files
                }
            }
            storage.execute {
                val outcome = runCatching {
                    synchronized(updateLock) { check(valid() && updateAllowed && generation == updateGeneration) }
                    val (candidate, files) = result.getOrThrow()
                    if (candidate.revision <= catalogStore.active().revision) null
                    else catalogStore.install(fm.heterodyne.app.stations.CatalogReader { files.getValue(it) },
                        publish = { commit ->
                            synchronized(updateLock) {
                                check(valid() && updateAllowed && generation == updateGeneration)
                                commit()
                            }
                        }).getOrThrow()
                }
                // A successful pointer swap commits the catalog even if its UI context just closed.
                val installed = outcome.getOrNull()
                if (installed != null) current = current.copy(catalogs = installed.catalogs)
                synchronized(updateLock) {
                    if (!updateAllowed || generation != updateGeneration) {
                        if (installed != null) publish()
                        return@execute
                    }
                    download = null
                    if (!valid()) {
                        updating = false
                        current = current.copy(catalogUpdateBusy = false, update = CatalogUpdate.Idle)
                        publish()
                        return@execute
                    }
                    val message = when {
                        outcome.isFailure -> "Не удалось обновить Справочник"
                        installed == null -> "Нет обновлений"
                        else -> "Справочник обновлён: Ревизия ${installed.revision}"
                    }
                    updating = false
                    current = current.copy(catalogUpdateBusy = false, catalogUpdateResult = message, update = when {
                        outcome.isFailure -> CatalogUpdate.Failed
                        installed == null -> CatalogUpdate.NoUpdate
                        else -> CatalogUpdate.Updated(installed.revision)
                    })
                    publish()
                }
            }
        }
    }

    fun activateSelection(name: String, regionId: String? = null) {
        edit(parkedOnly = false) {
            val state = current.state ?: return@edit
            if (regionId != null && regionId != state.selectedRegion) return@edit
            // Молчаливый выход при NOT_FOUND: в Режиме движения Подборки не правят, значит случай недостижим.
            // В Режиме стоянки это гонка быстрых нажатий («Удалить», сразу «Сделать активной»); экран уже обновился.
            val region = state.regionSelections(state.selectedRegion).activate(name).value ?: return@edit
            save(state.copy(regions = state.regions + (state.selectedRegion to region)))
        }
    }

    fun clearNameError() {
        edit {
            if (current.editIssue == null && current.nameError == null && current.frequencyError == null) return@edit
            current = current.copy(nameError = null, frequencyError = null, editIssue = null, error = current.error.takeIf { current.dataIssue != null })
            publish()
        }
    }

    fun createSelection(regionId: String, name: String) = changeRegion(regionId) { it.createSelection(name) }
    fun renameSelection(regionId: String, name: String, newName: String) = changeRegion(regionId) { it.rename(name, newName) }
    fun deleteSelection(regionId: String, name: String) = changeRegion(regionId) { it.delete(name) }
    fun addStation(regionId: String, name: String, entry: fm.heterodyne.app.stations.StationEntry) = changeSelection(regionId, name) {
        val trimmed = entry.name?.trim().orEmpty()
        when {
            entry.catalogId != null && current.catalogs.firstOrNull { it.region.id == regionId }?.stations?.none {
                it.id == entry.catalogId && it.frequencyKhz == entry.frequencyKhz
            } != false -> fm.heterodyne.app.stations.Change(error = ChangeError.NOT_FOUND)
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
        edit { valid ->
            // Молчаливые выходы, сообщения пользователю нет:
            // 1. state == null бывает только при загрузке; экран показывает «Загрузка…», кнопок правки нет.
            // 2. Устаревший Регион — гонка двух быстрых нажатий в Режиме стоянки; экран уже показывает новый Регион,
            //    и сообщение лишь запутало бы пользователя.
            val state = current.state ?: return@edit
            if (regionId != state.selectedRegion) return@edit
            val result = change(state.regionSelections(regionId))
            val region = result.value
            if (region != null) save(state.copy(regions = state.regions + (regionId to region)))
            else {
                editIssueAccess = valid
                val form = current.copy(nameError = null, frequencyError = null,
                    error = current.error.takeIf { current.dataIssue != null })
                current = when (result.error) {
                    ChangeError.INVALID_NAME -> form.copy(editIssue = EditIssue.InvalidName,
                        nameError = if (station) "Введите имя Станции: от 1 до 32 символов" else "Введите имя Подборки")
                    ChangeError.INVALID_FREQUENCY -> form.copy(editIssue = EditIssue.InvalidFrequency,
                        frequencyError = "Введите FM-частоту 87,5–108,0 МГц с шагом 0,1")
                    ChangeError.DUPLICATE_NAME -> form.copy(editIssue = EditIssue.DuplicateName,
                        nameError = "Подборка с таким именем уже есть")
                    ChangeError.DUPLICATE_FREQUENCY -> form.copy(editIssue = EditIssue.DuplicateFrequency,
                        frequencyError = "Частота уже есть в Подборке")
                    ChangeError.LAST_SELECTION -> form.copy(editIssue = EditIssue.LastSelection,
                        error = "Нельзя удалить последнюю Подборку")
                    else -> form.copy(editIssue = EditIssue.NotFound, error = "Подборка или Станция не найдены")
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
                current = current.copy(dataIssue = DataIssue.LoadFailed, error = "Не удалось прочитать Справочники")
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

    fun rememberStation(band: Band, frequency: Int, valid: () -> Boolean = { true }, saved: () -> Unit) {
        storage.execute {
            val state = current.state ?: return@execute
            if (!valid() || !fm.heterodyne.app.module.isSelectableFrequency(band, frequency)) return@execute
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
        edit {
            val state = current.state ?: return@edit
            if (current.catalogs.none { it.region.id == id } && id !in state.regions) return@edit // устаревшее действие UI отбрасывается молча, как в changeRegion
            val next = state.copy(selectedRegion = id, regions = state.regions + (id to state.regionSelections(id)))
            save(next)
        }
    }

    fun deleteObsoleteRegion(id: String) {
        edit {
            val state = current.state ?: return@edit
            if (id !in state.regions || current.catalogs.any { it.region.id == id }) return@edit
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
        current = if (saved) current.copy(state = next, dataIssue = null, editIssue = null, error = null, nameError = null, frequencyError = null)
        else current.copy(dataIssue = DataIssue.SaveFailed, error = "Не удалось сохранить состояние")
        publish()
        return saved
    }

    private fun StationsScreenState.withoutEditIssue() = if (editIssue == null) this else
        copy(nameError = null, frequencyError = null, editIssue = null, error = error.takeIf { dataIssue != null })

    internal fun clearPublishedEditIssue() { screen = screen.withoutEditIssue() }

    private fun publish() {
        val snapshot = current
        val valid = editIssueAccess
        main.post {
            screen = if (valid()) snapshot else snapshot.withoutEditIssue()
            changed()
        }
    }
}
