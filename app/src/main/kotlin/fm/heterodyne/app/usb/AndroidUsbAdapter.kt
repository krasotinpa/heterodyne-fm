package fm.heterodyne.app.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import fm.heterodyne.app.DiagnosticLog
import fm.heterodyne.app.module.*
import java.util.concurrent.Executors

/** Application owns one adapter. Discovery, permission and open run on its worker, outside UI. */
class AndroidUsbAdapter(private val context: Context, changed: (ConnectionState) -> Unit) {
    private val manager = context.getSystemService(UsbManager::class.java)
    private val worker = Executors.newSingleThreadExecutor()
    private val log by lazy {
        DiagnosticLog(java.io.File(context.filesDir, "module-diagnostics")).also {
            it.buildVersion(context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown")
        }
    }
    private val transport = AndroidUsbTransport(manager)
    private val controller by lazy { ModuleController(transport, log, changed) }
    private val permissionAction = "${context.packageName}.USB_PERMISSION"
    private val instance = java.util.UUID.randomUUID().toString()
    private var started = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
            worker.execute {
                try {
                    when (intent.action) {
                        UsbManager.ACTION_USB_DEVICE_ATTACHED -> discover()
                        UsbManager.ACTION_USB_DEVICE_DETACHED -> device?.let { controller.detach(it.deviceName); discover() }
                        permissionAction -> {
                            val uri = intent.data
                            if (uri?.authority != instance) return@execute
                            val token = uri.lastPathSegment?.toLongOrNull() ?: return@execute
                            val current = manager.deviceList[controller.deviceId]
                            controller.permissionResult(token, current != null && manager.hasPermission(current))
                        }
                    }
                } catch (_: Exception) { controller.error() }
            }
        }
    }
    fun start() {
        if (started) return
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(permissionAction)
        }
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else context.registerReceiver(receiver, filter)
        started = true
        retry()
    }
    fun retry() { worker.execute { try { discover() } catch (_: Exception) { controller.error() } } }
    private fun discover() {
        val device = manager.deviceList.values.firstOrNull { UsbDiscovery.find(it.description()) != null } ?: return
        if (controller.deviceId != null && controller.deviceId != device.deviceName) return
        controller.found(UsbDiscovery.find(device.description())!!)
        val token = controller.requestPermission() ?: return
        if (manager.hasPermission(device)) controller.permissionResult(token, true)
        else {
            val intent = Intent(permissionAction).setPackage(context.packageName)
                .setData(Uri.parse("heterodyne-usb://$instance/$token"))
            val pending = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            manager.requestPermission(device, pending)
        }
    }
    fun close() {
        if (!started) return
        context.unregisterReceiver(receiver)
        started = false
        worker.execute { controller.close() }
        worker.shutdown()
    }
}

private fun UsbDevice.description() = UsbDescription(deviceName, vendorId, productId,
    (0 until interfaceCount).map { index ->
        val item = getInterface(index)
        UsbInterfaceDescription(
            number = item.id,
            deviceClass = item.interfaceClass,
            alternate = item.alternateSetting,
            subclass = item.interfaceSubclass,
            protocol = item.interfaceProtocol,
            endpoints = (0 until item.endpointCount).map {
                val ep = item.getEndpoint(it)
                UsbEndpointDescription(ep.address, ep.type, ep.maxPacketSize)
            },
        )
    })

/**
 * USB lifecycle only, independently of radio commands (empty TX whitelist for #77).
 * openDevice -> claim IF 2 and IF 3, force=false; close releases those claims and connection.
 * No line coding, DTR/RTS, control transfers or audio interfaces. Not tested on hardware.
 */
private class AndroidUsbTransport(private val manager: UsbManager) : UsbTransport {
    private var connection: UsbDeviceConnection? = null
    private val claimed = mutableListOf<UsbInterface>()
    private var input: android.hardware.usb.UsbEndpoint? = null
    override fun open(profile: DeviceProfile) {
        check(connection == null)
        val device = checkNotNull(manager.deviceList[profile.deviceId])
        check(manager.hasPermission(device))
        check(UsbDiscovery.find(device.description()) != null)
        connection = checkNotNull(manager.openDevice(device))
        try {
            for (number in listOf(profile.controlInterface, profile.dataInterface)) {
                val item = (0 until device.interfaceCount).map(device::getInterface)
                    .single { it.id == number && it.alternateSetting == 0 }
                check(connection!!.claimInterface(item, false))
                claimed.add(item)
                if (number == profile.dataInterface) input = (0 until item.endpointCount)
                    .map(item::getEndpoint).single { it.address == profile.bulkIn }
            }
        } catch (failure: Exception) { close(); throw failure }
    }
    override fun receive(): ByteArray {
        val bytes = ByteArray(64)
        val count = checkNotNull(connection).bulkTransfer(checkNotNull(input), bytes, bytes.size, 250)
        check(count >= 0) { "USB receive failed" }
        return bytes.copyOf(count)
    }
    override fun send(bytes: ByteArray): Unit = error("Radio TX is disabled")
    override fun close() {
        val opened = connection ?: return
        connection = null
        input = null
        try { claimed.forEach { opened.releaseInterface(it) } }
        finally { claimed.clear(); opened.close() }
    }
}
