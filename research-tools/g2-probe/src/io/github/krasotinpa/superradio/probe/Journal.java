package io.github.krasotinpa.superradio.probe;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Date;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Event journal for the G2 media probe: newest line first, wall clock plus elapsed time, every line also goes to a sink (a file).
 * Pure Java, checked on the JVM by JournalCheck. Android constants are inlined so the class compiles without android.jar.
 */
final class Journal {
    static final int MAX = 400;

    private final ArrayDeque<String> lines = new ArrayDeque<>();
    private final long t0;
    private final Consumer<String> sink;
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT);

    Journal(long t0, Consumer<String> sink) { this.t0 = t0; this.sink = sink; }

    void line(String msg) { line(System.currentTimeMillis(), msg); }

    synchronized void line(long now, String msg) {
        String s = String.format(Locale.ROOT, "+%.1fs %s %s", (now - t0) / 1000.0, clock.format(new Date(now)), msg);
        lines.addFirst(s);
        if (lines.size() > MAX) lines.removeLast();
        if (sink != null) sink.accept(s);
    }

    /** Older lines restored from the sink file, newest first; they go below anything already logged. */
    synchronized void restore(Collection<String> old) {
        for (String s : old) { if (lines.size() >= MAX) break; lines.addLast(s); }
    }

    synchronized String text() { return String.join("\n", lines); }

    synchronized void clear() { lines.clear(); }

    static String describeKey(int action, int keyCode, int repeat, int flags) {
        String a = action == 0 ? "DOWN" : action == 1 ? "UP" : "action " + action;
        String k;
        switch (keyCode) {
            case 85: k = "PLAY_PAUSE"; break;
            case 86: k = "STOP"; break;
            case 87: k = "NEXT"; break;
            case 88: k = "PREVIOUS"; break;
            case 126: k = "PLAY"; break;
            case 127: k = "PAUSE"; break;
            case 24: k = "VOLUME_UP"; break;
            case 25: k = "VOLUME_DOWN"; break;
            default: k = "код " + keyCode;
        }
        return a + " " + k + " повтор " + repeat + ((flags & 0x80) != 0 ? " ДОЛГОЕ" : "");   // 0x80 = KeyEvent.FLAG_LONG_PRESS
    }

    static String describeFocus(int change) {
        switch (change) {
            case 1: return "GAIN";
            case -1: return "LOSS";
            case -2: return "LOSS_TRANSIENT";
            case -3: return "LOSS_TRANSIENT_CAN_DUCK";
            default: return "код " + change;
        }
    }

    static String describeUsage(int usage) {
        switch (usage) {   // android.media.AudioAttributes.USAGE_*
            case 1: return "медиа";
            case 2: return "голосовой вызов";
            case 4: return "будильник";
            case 5: return "уведомление";
            case 6: return "звонок";
            case 11: return "доступность";
            case 12: return "навигация";
            case 13: return "сигнал";
            case 14: return "игра";
            case 16: return "ассистент";
            default: return "usage " + usage;
        }
    }
}
