package fm.heterodyne.app.ui

import fm.heterodyne.app.module.Band
import fm.heterodyne.app.module.TuneTarget
import fm.heterodyne.app.stations.StationsState

fun topbarStation(confirmed: TuneTarget?, state: StationsState?): TuneTarget? =
    if (confirmed != null) confirmed.takeIf { it.band == Band.FM }
    else state?.lastStation?.let { TuneTarget(Band.FM, it.frequencyKhz) }
