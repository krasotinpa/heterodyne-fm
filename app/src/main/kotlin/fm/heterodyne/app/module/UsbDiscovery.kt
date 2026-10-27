package fm.heterodyne.app.module

import fm.heterodyne.app.receiver.ReceiverKind
import fm.heterodyne.app.receiver.ReceiverProfiles

data class UsbEndpointDescription(val address: Int, val type: Int, val maxPacketSize: Int)
data class UsbInterfaceDescription(
    val number: Int,
    val deviceClass: Int,
    val alternate: Int = 0,
    val endpoints: List<UsbEndpointDescription> = emptyList(),
    val subclass: Int = 0,
    val protocol: Int = 0,
)
data class UsbDescription(val id: String, val vendorId: Int, val productId: Int, val interfaces: List<UsbInterfaceDescription>)

/** Only discovery can construct a verified profile. Union descriptors are deliberately unused. */
class DeviceProfile internal constructor(val deviceId: String, val kind: ReceiverKind = ReceiverKind.Module) {
    val controlInterface = 2
    val dataInterface = 3
    val bulkOut = 0x03
    val bulkIn = 0x83
}

object UsbDiscovery {
    fun find(device: UsbDescription, profiles: ReceiverProfiles = ReceiverProfiles.Module): DeviceProfile? =
        profiles.find(device)

    internal fun matchesModule(device: UsbDescription): Boolean {
        val control = device.interfaces.singleOrNull { it.number == 2 && it.alternate == 0 } ?: return false
        val data = device.interfaces.singleOrNull { it.number == 3 && it.alternate == 0 } ?: return false
        if (control.deviceClass != 0x02 || control.subclass != 0x02 || control.protocol != 0x01) return false
        if (control.endpoints != listOf(UsbEndpointDescription(0x84, 3, 8))) return false
        if (data.deviceClass != 0x0a || data.endpoints.size != 2) return false
        if (data.endpoints.toSet() != setOf(UsbEndpointDescription(0x03, 2, 64), UsbEndpointDescription(0x83, 2, 64))) return false
        return true
    }
}
