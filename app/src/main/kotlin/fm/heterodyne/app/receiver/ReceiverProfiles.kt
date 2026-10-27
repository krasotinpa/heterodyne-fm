package fm.heterodyne.app.receiver

import fm.heterodyne.app.module.DeviceProfile
import fm.heterodyne.app.module.UsbDescription
import fm.heterodyne.app.module.UsbDiscovery

/** VID/PID alone never establishes a supported receiver. */
class ReceiverProfile(
    val kind: ReceiverKind,
    val vendorId: Int,
    val productId: Int,
    private val matchesDescriptor: (UsbDescription) -> Boolean,
) {
    internal fun matches(device: UsbDescription): Boolean =
        device.vendorId == vendorId && device.productId == productId && matchesDescriptor(device)
}

class ReceiverProfiles(profiles: List<ReceiverProfile>) {
    private val profiles = profiles.toList()
    init { require(this.profiles.map { it.kind }.distinct().size == this.profiles.size) }

    fun withProfile(profile: ReceiverProfile): ReceiverProfiles = ReceiverProfiles(profiles + profile)

    fun find(device: UsbDescription): DeviceProfile? {
        val profile = profiles.singleOrNull { it.matches(device) } ?: return null
        return DeviceProfile(device.id, profile.kind)
    }

    companion object {
        val Module = ReceiverProfiles(listOf(
            ReceiverProfile(ReceiverKind.Module, 0x0483, 0x5740, UsbDiscovery::matchesModule),
        ))
    }
}
