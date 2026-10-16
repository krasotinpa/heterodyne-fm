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
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.Arrays;
import java.util.Locale;

/**
 * G2 probe: reads system, USB and audio facts and shows them on screen.
 * The report is read-only. The "Тест звука" button starts ProbeService (ADR 0001 audio path plus MediaSession, focus and a journal, #34).
 * No radio commands are sent.
 */
public class MainActivity extends Activity {
    static final int MODULE_VID = 0x0483, MODULE_PID = 0x5740;
    static final String ACTION_PERMISSION = "io.github.krasotinpa.superradio.probe.USB_PERMISSION";

    TextView view, audioStatus, journalView;
    Button audioButton;
    Journal j;
    final Handler ui = new Handler(Looper.getMainLooper());
    final Runnable poll = new Runnable() {
        @Override public void run() {
            set(audioStatus, ProbeService.lastStatus);
            set(audioButton, ProbeService.running ? "Стоп" : "Тест звука 10 мин");
            set(journalView, j.text());   // setText only on change: keeps the selection and skips a 400-line relayout
            ui.postDelayed(this, 1000);
        }
    };

    static void set(TextView v, String s) { if (!s.contentEquals(v.getText())) v.setText(s); }
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
        j = ProbeService.journal(this);
        j.line("экран: onCreate, intent " + getIntent().getAction() + ", с загрузки ГУ " + android.os.SystemClock.elapsedRealtime() / 1000 + " с");
        view = new TextView(this);
        view.setTypeface(Typeface.MONOSPACE);
        view.setTextSize(16);
        view.setTextIsSelectable(true);
        view.setPadding(24, 24, 24, 24);
        audioButton = new Button(this);
        audioButton.setText("Тест звука 10 мин");
        audioButton.setOnClickListener(v -> toggleAudio());
        Button clear = new Button(this);
        clear.setText("Очистить журнал");
        clear.setOnClickListener(v -> { ProbeService.clearJournal(this); j.line("журнал очищен"); });
        journalView = new TextView(this);
        journalView.setTypeface(Typeface.MONOSPACE);
        journalView.setTextSize(16);
        journalView.setTextIsSelectable(true);
        journalView.setPadding(24, 8, 24, 8);
        audioStatus = new TextView(this);
        audioStatus.setTypeface(Typeface.MONOSPACE);
        audioStatus.setTextSize(16);
        audioStatus.setPadding(24, 8, 24, 8);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        LinearLayout buttons = new LinearLayout(this);
        buttons.addView(audioButton, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2));
        buttons.addView(clear, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        root.addView(buttons);
        root.addView(audioStatus);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.addView(journalView);
        body.addView(view);
        scroll.addView(body);
        root.addView(scroll);
        setContentView(root);
        IntentFilter f = new IntentFilter(ACTION_PERMISSION);
        f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        registerReceiver(receiver, f);
    }

    void toggleAudio() {
        if (!ProbeService.running) {
            UsbManager um = (UsbManager) getSystemService(USB_SERVICE);
            for (UsbDevice d : um.getDeviceList().values()) {
                if (d.getVendorId() == MODULE_VID && d.getProductId() == MODULE_PID && !um.hasPermission(d)) {
                    asked = false;   // render() asks again; the service refuses to start without permission
                    j.line("кнопка: нет разрешения USB, запрос повторён");
                    render();
                    return;
                }
            }
        }
        Intent i = new Intent(this, ProbeService.class).setAction(ProbeService.running ? ProbeService.ACTION_STOP : ProbeService.ACTION_START);
        j.line("кнопка: " + (ProbeService.running ? "стоп" : "тест звука"));
        startForegroundService(i);
    }

    @Override protected void onStart() { super.onStart(); j.line("экран: onStart"); ui.post(poll); }

    @Override protected void onStop() { ui.removeCallbacks(poll); j.line("экран: onStop"); super.onStop(); }

    @Override protected void onDestroy() {
        j.line("экран: onDestroy");
        unregisterReceiver(receiver);
        super.onDestroy();
    }

    @Override protected void onResume() {
        super.onResume();
        render();
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        j.line("экран: onNewIntent " + i.getAction());
        render();
    }

    void render() {
        StringBuilder s = new StringBuilder("Super Radio Probe 0.4 — отчёт только читает\n\n");
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
