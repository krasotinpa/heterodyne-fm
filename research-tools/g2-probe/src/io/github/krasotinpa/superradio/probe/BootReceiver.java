package io.github.krasotinpa.superradio.probe;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.SystemClock;

/** G2 autostart check (#63): journals BOOT_COMPLETED and USB_DEVICE_ATTACHED with uptime and the USB permission state. Starts nothing. */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        UsbManager um = (UsbManager) c.getSystemService(Context.USB_SERVICE);
        String perm = "Модуль не виден";
        for (UsbDevice d : um.getDeviceList().values()) {
            if (d.getVendorId() == MainActivity.MODULE_VID && d.getProductId() == MainActivity.MODULE_PID) perm = um.hasPermission(d) ? "разрешение USB есть" : "разрешения USB нет";
        }
        ProbeService.journal(c).line("приёмник: " + i.getAction() + ", с загрузки ГУ " + SystemClock.elapsedRealtime() / 1000 + " с, " + perm);
    }
}
