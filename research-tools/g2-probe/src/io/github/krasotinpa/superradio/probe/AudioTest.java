package io.github.krasotinpa.superradio.probe;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.SystemClock;

import java.util.Locale;
import java.util.function.Consumer;

/**
 * Plays the Module's audio for a fixed time (ADR 0001): claim IF 1, alt 1, usbfs iso reads, AudioTrack.
 * USB requests: claim and SET_INTERFACE only. No radio commands.
 * Frame alignment is decided from 0.1 s of data and re-decided after any lost packet (ADR 0001, decision 4).
 * ponytail: no drift compensation; fine for a 30 s test, AudioTrack under/overrun shows up in the counters.
 */
final class AudioTest implements Runnable {
    static final int RATE = 48000, BYTES_PER_FRAME = 4;

    final UsbManager um;
    final UsbDevice dev;
    final int seconds;
    final Consumer<String> status;
    volatile boolean stop;

    AudioTest(UsbManager um, UsbDevice dev, int seconds, Consumer<String> status) {
        this.um = um; this.dev = dev; this.seconds = seconds; this.status = status;
    }

    @Override public void run() {
        UsbDeviceConnection conn = null;
        UsbInterface if1 = null, alt0 = null, alt1 = null;
        AudioTrack track = null;
        boolean isoOpen = false;
        String result;
        try {
            for (int i = 0; i < dev.getInterfaceCount(); i++) {
                UsbInterface f = dev.getInterface(i);
                if (f.getId() == 1 && f.getAlternateSetting() == 0) alt0 = f;
                if (f.getId() == 1 && f.getAlternateSetting() == 1) alt1 = f;
            }
            if (alt0 == null || alt1 == null) throw new IllegalStateException("нет IF 1 alt 0/1");
            conn = um.openDevice(dev);
            if (conn == null) throw new IllegalStateException("openDevice вернул null");
            if (!conn.claimInterface(alt1, true)) throw new IllegalStateException("claimInterface IF 1 не удался");
            if1 = alt1;
            if (!conn.setInterface(alt1)) throw new IllegalStateException("setInterface alt 1 не удался");
            int r = UsbIso.open(conn.getFileDescriptor());
            isoOpen = r != -16;   // -EBUSY: another test owns the native session; do not close it
            if (r < 0) throw new IllegalStateException("usbfs open: errno " + -r);

            int minBuf = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                .setBufferSizeInBytes(Math.max(minBuf, RATE * BYTES_PER_FRAME))   // 1 s
                .setTransferMode(AudioTrack.MODE_STREAM).build();
            track.setVolume(0.3f);   // car tests run at low volume

            byte[] urb = new byte[8 * 312];
            final int decide = RATE * BYTES_PER_FRAME / 10;      // 0.1 s decides the frame offset
            byte[] pending = new byte[decide + urb.length];
            int have = 0, offset = -1, realigned = -1;
            long badSeen = 0;
            boolean playing = false;
            long bytes = 0, sumSq = 0, samples = 0;
            long start = SystemClock.elapsedRealtime(), lastUi = 0;
            while (!stop && SystemClock.elapsedRealtime() - start < seconds * 1000L) {
                int n = UsbIso.read(urb);
                if (n < 0) throw new IllegalStateException("usbfs read: errno " + -n);
                bytes += n;
                long bad = UsbIso.badPackets();
                if (bad != badSeen) { badSeen = bad; offset = -1; have = 0; continue; }   // gap: drop and realign
                System.arraycopy(urb, 0, pending, have, n);
                have += n;
                if (offset < 0) {
                    if (have < decide) continue;
                    offset = Frames.frameOffset(pending, have);
                    realigned++;
                    System.arraycopy(pending, offset, pending, 0, have - offset);
                    have -= offset;
                }
                int whole = have / BYTES_PER_FRAME * BYTES_PER_FRAME;
                for (int i = 0; i + 1 < whole; i += 2) { long v = Frames.s16(pending, i); sumSq += v * v; samples++; }
                track.write(pending, 0, whole);
                if (!playing && bytes > RATE * BYTES_PER_FRAME / 4) { track.play(); playing = true; }   // 0.25 s prefilled, buffer holds 1 s
                System.arraycopy(pending, whole, pending, 0, have - whole);
                have -= whole;
                long now = SystemClock.elapsedRealtime();
                if (now - lastUi > 1000) {
                    lastUi = now;
                    status.accept(report("идёт", now - start, bytes, offset, realigned, sumSq, samples, track));
                }
            }
            result = report("завершён", SystemClock.elapsedRealtime() - start, bytes, offset, realigned, sumSq, samples, track);
        } catch (Throwable t) {
            result = "Тест звука: ошибка — " + t;
        } finally {
            if (isoOpen) UsbIso.close();
            if (track != null) { try { track.stop(); } catch (IllegalStateException ignored) { } track.release(); }
            if (conn != null) {
                if (alt0 != null && if1 != null) conn.setInterface(alt0);
                if (if1 != null) conn.releaseInterface(if1);
                conn.close();
            }
        }
        status.accept(result);
    }

    static String report(String state, long ms, long bytes, int offset, int realigned, long sumSq, long samples, AudioTrack t) {
        double rms = samples > 0 ? Math.sqrt((double) sumSq / samples) : 0;
        return String.format(Locale.ROOT,
            "Тест звука: %s, %.1f с\nбайт %d (ожидалось ~%d), плохих пакетов %d\nсмещение кадра %d, повторных выравниваний %d, уровень %.1f dBFS, недогрузок AudioTrack %d",
            state, ms / 1000.0, bytes, ms * RATE * BYTES_PER_FRAME / 1000, UsbIso.badPackets(), offset, Math.max(realigned, 0),
            rms > 0 ? 20 * Math.log10(rms / 32768) : -99.0, t.getUnderrunCount());
    }
}
