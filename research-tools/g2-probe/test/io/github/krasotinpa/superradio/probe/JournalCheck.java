package io.github.krasotinpa.superradio.probe;

import java.util.ArrayList;
import java.util.List;

/** JVM check for Journal: formatting, ring size, key/focus/usage descriptions. Run: java -ea (see tests/research/test_session_analysis.py). */
public class JournalCheck {
    public static void main(String[] a) {
        List<String> sunk = new ArrayList<>();
        Journal j = new Journal(1_000_000L, sunk::add);
        j.line(1_000_000L, "старт");
        j.line(1_012_345L, "кнопка");
        String t = j.text();
        assert t.startsWith("+12.3s") : t;                       // newest first, elapsed since t0
        assert t.contains("кнопка\n") && t.endsWith("старт") : t;
        assert t.matches("(?s).*\\d\\d:\\d\\d:\\d\\d\\.\\d\\d\\d.*") : t;   // wall clock with ms
        assert sunk.size() == 2 && sunk.get(1).endsWith("кнопка") : sunk;

        for (int i = 0; i < Journal.MAX + 5; i++) j.line(2_000_000L + i, "n" + i);
        assert j.text().split("\n").length == Journal.MAX : "ring keeps MAX lines";
        assert j.text().startsWith("+1000.4s") : "newest kept: " + j.text().substring(0, 12);
        j.clear();
        assert j.text().isEmpty();

        assert Journal.describeKey(0, 87, 0, 0).equals("DOWN NEXT повтор 0") : Journal.describeKey(0, 87, 0, 0);
        assert Journal.describeKey(0, 88, 1, 0x80).equals("DOWN PREVIOUS повтор 1 ДОЛГОЕ") : Journal.describeKey(0, 88, 1, 0x80);
        assert Journal.describeKey(1, 85, 0, 0).equals("UP PLAY_PAUSE повтор 0");
        assert Journal.describeKey(1, 4, 0, 0).equals("UP код 4 повтор 0") : "unknown key keeps the code";
        assert Journal.describeFocus(-3).equals("LOSS_TRANSIENT_CAN_DUCK");
        assert Journal.describeFocus(1).equals("GAIN");
        assert Journal.describeFocus(7).equals("код 7");
        assert Journal.describeUsage(12).equals("навигация");
        assert Journal.describeUsage(1).equals("медиа");
        assert Journal.describeUsage(99).equals("usage 99");
        System.out.println("JournalCheck ok");
    }
}
