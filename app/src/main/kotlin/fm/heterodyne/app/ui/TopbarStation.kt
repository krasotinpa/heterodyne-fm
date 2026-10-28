package fm.heterodyne.app.ui

import fm.heterodyne.app.module.Band
import fm.heterodyne.app.module.TuneTarget
import fm.heterodyne.app.stations.StationsState

fun topbarStation(confirmed: TuneTarget?, state: StationsState?): TuneTarget? =
    confirmed ?: state?.lastStation?.let { TuneTarget(Band.valueOf(it.band.name), it.frequencyKhz) }
