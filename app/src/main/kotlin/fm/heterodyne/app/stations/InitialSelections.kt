package fm.heterodyne.app.stations

/** Copies the owner's selection from the catalogs only for a new installation. */
internal fun initialSelections(loaded: StationsLoad, catalogs: List<Catalog>): StationsState {
    if (loaded.status != LoadStatus.MISSING) return loaded.state
    val ids = mapOf(
        "moskva" to listOf(
            "moskva-biznes-fm", "moskva-radio-rekord", "moskva-moskva-fm",
            "moskva-pervoe-sportivnoe-radio", "moskva-detskoe-radio", "moskva-radio-romantika",
            "moskva-dfm", "moskva-mayak", "moskva-capital-fm", "moskva-militseyskaya-volna"),
        "yaroslavl" to listOf(
            "yaroslavl-radio-rodnyh-dorog", "yaroslavl-radio-iskatel", "yaroslavl-radio-vera",
            "yaroslavl-radio-rossii", "yaroslavl-love-radio", "yaroslavl-radio-7",
            "yaroslavl-radio-dacha", "yaroslavl-avtoradio", "yaroslavl-marusya-fm", "yaroslavl-mayak"),
    )
    return loaded.state.copy(regions = loaded.state.regions + ids.mapValues { (regionId, _) ->
        val catalog = catalogs.single { it.region.id == regionId }
        val selection = ids.getValue(regionId).fold(StationSelection.empty("Основной")) { selection, id ->
            selection.add(catalog.stations.single { it.id == id }.copyEntry()).value!!
        }
        loaded.state.regionSelections(regionId).replaceActive(selection)
    })
}
