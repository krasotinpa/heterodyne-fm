package io.github.krasotinpa.superradio.probe;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Typeface;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.Arrays;
import java.util.Locale;

/**
 * G2 probe: reads system, USB and audio facts and shows them on screen.
 * Read-only: it may ask for USB permission, but never opens, claims or writes to any device.
 */
public class MainActivity extends Activity {
    static final int MODULE_VID = 0x0483, MODULE_PID = 0x5740;
    static final String ACTION_PERMISSION = "io.github.krasotinpa.superradio.probe.USB_PERMISSION";

    TextView view;
    String permissionResult = "не запрашивалось";
    boolean asked;

    final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (ACTION_PERMISSION.equals(i.getAction())) {
                permissionResult = i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) ? "выдано" : "отказано";
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(i.getAction())) {
                asked = false;
                permissionResult = "не запрашивалось";
            }
            render();
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        view = new TextView(this);
        view.setTypeface(Typeface.MONOSPACE);
        view.setTextSize(16);
        view.setTextIsSelectable(true);
        view.setPadding(24, 24, 24, 24);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(view);
        setContentView(scroll);
        IntentFilter f = new IntentFilter(ACTION_PERMISSION);
        f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        registerReceiver(receiver, f);
    }

    @Override protected void onDestroy() {
        unregisterReceiver(receiver);
        super.onDestroy();
    }

    @Override protected void onResume() {
        super.onResume();
        render();
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        render();
    }

    void render() {
        StringBuilder s = new StringBuilder("Super Radio Probe 0.1 — только чтение\n\n");
        // A vendor HAL may throw anywhere; keep the rest of the report instead of crashing.
        try { system(s); } catch (Throwable t) { s.append("ошибка: ").append(t).append("\n\n"); }
        try { usb(s); } catch (Throwable t) { s.append("ошибка: ").append(t).append("\n\n"); }
        try { audio(s); } catch (Throwable t) { s.append("ошибка: ").append(t).append("\n\n"); }
        view.setText(s);
    }

    void system(StringBuilder s) {
        s.append("== Система\n")
            .append("MANUFACTURER ").append(Build.MANUFACTURER).append('\n')
            .append("MODEL ").append(Build.MODEL).append('\n')
            .append("DEVICE ").append(Build.DEVICE).append('\n')
            .append("PRODUCT ").append(Build.PRODUCT).append('\n')
            .append("BOARD ").append(Build.BOARD).append('\n')
            .append("HARDWARE ").append(Build.HARDWARE).append('\n')
            .append("Android ").append(Build.VERSION.RELEASE).append(", API ").append(Build.VERSION.SDK_INT).append('\n')
            .append("Патч ").append(Build.VERSION.SECURITY_PATCH).append('\n')
            .append("Тип сборки ").append(Build.TYPE).append(", ").append(Build.TAGS).append('\n')
            .append("FINGERPRINT ").append(Build.FINGERPRINT).append('\n')
            .append("Ядро ").append(System.getProperty("os.version")).append('\n')
            .append("ABI ").append(Arrays.toString(Build.SUPPORTED_ABIS)).append('\n')
            .append("Ядер CPU ").append(Runtime.getRuntime().availableProcessors()).append('\n')
            .append("cpuinfo Hardware ").append(cpuHardware()).append('\n');
        ActivityManager.MemoryInfo m = new ActivityManager.MemoryInfo();
        ((ActivityManager) getSystemService(ACTIVITY_SERVICE)).getMemoryInfo(m);
        s.append("ОЗУ ").append(m.totalMem / (1024 * 1024)).append(" МБ\n")
            .append("usb.host ").append(getPackageManager().hasSystemFeature("android.hardware.usb.host")).append("\n\n");
    }

    static String cpuHardware() {
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/cpuinfo"))) {
            for (String l; (l = r.readLine()) != null; ) {
                if (l.startsWith("Hardware")) return l.substring(l.indexOf(':') + 1).trim();
            }
            return "нет строки Hardware";
        } catch (Exception e) {
            return "ошибка: " + e;
        }
    }

    void usb(StringBuilder s) {
        UsbManager um = (UsbManager) getSystemService(USB_SERVICE);
        s.append("== USB\n");
        if (um == null) {
            s.append("UsbManager недоступен\n\n");
            return;
        }
        s.append("Устройств: ").append(um.getDeviceList().size()).append('\n');
        for (UsbDevice d : um.getDeviceList().values()) {
            boolean module = d.getVendorId() == MODULE_VID && d.getProductId() == MODULE_PID;
            boolean granted = um.hasPermission(d);
            s.append(String.format(Locale.ROOT, "%s %04x:%04x класс %d%s\n", d.getDeviceName(), d.getVendorId(), d.getProductId(),
                d.getDeviceClass(), module ? "  <- Модуль" : ""));
            s.append("  разрешение: ").append(granted).append('\n');
            if (granted) {
                s.append("  ").append(d.getManufacturerName()).append(" / ").append(d.getProductName()).append('\n');
            }
            for (int i = 0; i < d.getInterfaceCount(); i++) {
                UsbInterface f = d.getInterface(i);
                s.append(String.format(Locale.ROOT, "  IF %d alt %d класс %d/%d/%d\n", f.getId(), f.getAlternateSetting(),
                    f.getInterfaceClass(), f.getInterfaceSubclass(), f.getInterfaceProtocol()));
                for (int e = 0; e < f.getEndpointCount(); e++) {
                    UsbEndpoint ep = f.getEndpoint(e);
                    s.append(String.format(Locale.ROOT, "    EP 0x%02x тип %d max %d\n", ep.getAddress(), ep.getType(), ep.getMaxPacketSize()));
                }
            }
            if (module && !granted && !asked) {
                asked = true;
                permissionResult = "запрошено";
                um.requestPermission(d, PendingIntent.getBroadcast(this, 0,
                    new Intent(ACTION_PERMISSION).setPackage(getPackageName()), 0));
            }
        }
        s.append("Запрос разрешения: ").append(permissionResult).append("\n\n");
    }

    void audio(StringBuilder s) {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        s.append("== Аудиоустройства (тип 11 = USB_DEVICE, 12 = USB_ACCESSORY, 22 = USB_HEADSET)\n");
        for (AudioDeviceInfo a : am.getDevices(AudioManager.GET_DEVICES_ALL)) {
            s.append(a.isSource() ? "вход  " : "выход ").append("тип ").append(a.getType())
                .append(" \"").append(a.getProductName()).append("\" id ").append(a.getId())
                .append(" частоты ").append(Arrays.toString(a.getSampleRates()))
                .append(" каналы ").append(Arrays.toString(a.getChannelCounts()));
            if (Build.VERSION.SDK_INT >= 28) s.append(" адрес ").append(a.getAddress());
            s.append('\n');
        }
    }
}
