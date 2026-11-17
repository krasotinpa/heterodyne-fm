package fm.heterodyne.app.receiver

import fm.heterodyne.app.audio.AudioSource
import fm.heterodyne.app.audio.UsbAudioPort
import fm.heterodyne.app.module.DeviceProfile
import fm.heterodyne.app.module.UsbTransport

/** Replaces hardware IO as one unit; normal builds never supply a fixture. */
class ReceiverFixture(
    val profile: DeviceProfile,
    val transport: UsbTransport,
    val source: AudioSource,
    val audioPort: UsbAudioPort,
)
