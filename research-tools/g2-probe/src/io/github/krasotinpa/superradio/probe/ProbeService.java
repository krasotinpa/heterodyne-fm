package io.github.krasotinpa.superradio.probe;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.IBinder;
import android.view.KeyEvent;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Foreground service for the G2 media checks (#34): keeps AudioTest alive while the navigator is on screen,
 * owns a MediaSession (steering wheel keys), holds audio focus and journals every event. No radio commands.
 */
public class ProbeService extends Service {
    static final String ACTION_START = "io.github.krasotinpa.superradio.probe.START", ACTION_STOP = "io.github.krasotinpa.superradio.probe.STOP";
    static final int SECONDS = 600;
    static volatile String lastStatus = "";
    static volatile boolean running;
    private static Journal journal;

    /** Process-wide journal backed by files/journal.txt; the file survives a process kill during sleep. */
    static synchronized Journal journal(Context c) {
        if (journal == null) {
            File f = new File(c.getFilesDir(), "journal.txt");
            List<String> old = new ArrayList<>();
            try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                for (String l; (l = r.readLine()) != null; ) old.add(0, l);
            } catch (Exception ignored) { }
            journal = new Journal(System.currentTimeMillis(), s -> {
                try (FileWriter w = new FileWriter(f, true)) { w.write(s); w.write('\n'); } catch (Exception ignored) { }
            });
            journal.restore(old);
            journal.line("журнал открыт, старых строк " + old.size());
        }
        return journal;
    }

    static void clearJournal(Context c) {
        journal(c).clear();
        new File(c.getFilesDir(), "journal.txt").delete();
    }

    Journal j;
    MediaSession session;
    AudioManager am;
    AudioFocusRequest focus;
    AudioTest test;
    final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { j.line("экран ГУ: " + i.getAction().replace("android.intent.action.", "")); }
    };
    final AudioManager.AudioPlaybackCallback playback = new AudioManager.AudioPlaybackCallback() {
        @Override public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
            StringBuilder s = new StringBuilder("активные плееры:");
            for (AudioPlaybackConfiguration c : configs) {
                AudioAttributes a = c.getAudioAttributes();
                s.append(' ').append(Journal.describeUsage(a.getUsage())).append('/').append(a.getContentType());
            }
            j.line(configs.isEmpty() ? "активных плееров нет" : s.toString());
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        j = journal(this);
        j.line("сервис: onCreate");
        am = (AudioManager) getSystemService(AUDIO_SERVICE);
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("probe", "Super Radio Probe", NotificationManager.IMPORTANCE_LOW));
        session = new MediaSession(this, "probe");
        session.setCallback(new MediaSession.Callback() {
            @Override public boolean onMediaButtonEvent(Intent i) {
                KeyEvent k = i.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                j.line("руль: " + (k == null ? "нет KeyEvent" : Journal.describeKey(k.getAction(), k.getKeyCode(), k.getRepeatCount(), k.getFlags())));
                return true;
            }
        });
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        registerReceiver(screen, f);
        am.registerAudioPlaybackCallback(playback, null);
    }

    @Override public int onStartCommand(Intent i, int flags, int startId) {
        String action = i == null ? "null" : i.getAction();
        j.line("сервис: onStartCommand " + action);
        if (ACTION_STOP.equals(action)) { stopTest(); return START_NOT_STICKY; }
        Notification n = new Notification.Builder(this, "probe").setContentTitle("Super Radio Probe").setContentText("тест звука")
            .setSmallIcon(android.R.drawable.ic_media_play).setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken())).build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK); else startForeground(1, n);
        startTest();
        return START_NOT_STICKY;
    }

    void startTest() {
        if (running) return;
        UsbManager um = (UsbManager) getSystemService(USB_SERVICE);
        UsbDevice module = null;
        for (UsbDevice d : um.getDeviceList().values()) {
            if (d.getVendorId() == MainActivity.MODULE_VID && d.getProductId() == MainActivity.MODULE_PID) module = d;
        }
        if (module == null || !um.hasPermission(module)) {
            lastStatus = module == null ? "Тест звука: Модуль не подключён" : "Тест звука: нет разрешения USB";
            j.line(lastStatus);
            stopSelf();
            return;
        }
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener(c -> j.line("фокус: " + Journal.describeFocus(c))).build();
        j.line("фокус: запрос -> " + (am.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED ? "GRANTED" : "отказ"));
        session.setPlaybackState(state(PlaybackState.STATE_PLAYING));
        session.setActive(true);
        j.line("сессия: активна, PLAYING");
        running = true;
        test = new AudioTest(um, module, SECONDS, msg -> lastStatus = msg);
        AudioTest t = test;
        new Thread(() -> {
            j.line("тест звука: старт, " + SECONDS + " с");
            t.run();
            j.line(lastStatus.replace('\n', ' '));
            running = false;
            session.setPlaybackState(state(PlaybackState.STATE_STOPPED));
            session.setActive(false);
            am.abandonAudioFocusRequest(focus);
            j.line("сессия: неактивна; фокус отдан");
            stopForeground(true);
            stopSelf();
        }, "audio-test").start();
    }

    void stopTest() { if (test != null) test.stop = true; else stopSelf(); }

    static PlaybackState state(int s) {
        return new PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
            | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_STOP).setState(s, 0, 1f).build();
    }

    @Override public void onTaskRemoved(Intent root) { j.line("сервис: onTaskRemoved"); }

    @Override public void onDestroy() {
        j.line("сервис: onDestroy, тест " + (running ? "ещё идёт" : "не идёт"));
        if (test != null) test.stop = true;
        unregisterReceiver(screen);
        am.unregisterAudioPlaybackCallback(playback);
        session.release();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent i) { return null; }
}
