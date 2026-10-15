package io.github.krasotinpa.superradio.probe;

/** JVM check for Frames.frameOffset. Run: java -ea (see tests/research/test_session_analysis.py). */
public class FramesCheck {
    static byte[] stream(int lead, boolean mono) {
        byte[] b = new byte[lead + 4800 * 4];
        for (int n = 0; n < 4800; n++) {
            short l = (short) (8000 * Math.sin(n / 7.0)), r = mono ? l : (short) (8000 * Math.cos(n / 5.0));
            int i = lead + n * 4;
            b[i] = (byte) l; b[i + 1] = (byte) (l >> 8); b[i + 2] = (byte) r; b[i + 3] = (byte) (r >> 8);
        }
        return b;
    }

    public static void main(String[] a) {
        byte[] s;
        s = stream(0, true);  assert Frames.frameOffset(s, s.length) == 0 : "mono, aligned";
        s = stream(2, true);  assert Frames.frameOffset(s, s.length) == 2 : "mono, 2-byte lead";
        s = stream(0, false); assert Frames.frameOffset(s, s.length) == 2 : "stereo: tie picks 2";
        s = new byte[19200];  assert Frames.frameOffset(s, s.length) == 2 : "silence: tie picks 2";
        assert Frames.s16(new byte[] {(byte) 0xff, (byte) 0x7f}, 0) == 32767;
        assert Frames.s16(new byte[] {0x00, (byte) 0x80}, 0) == -32768;
        System.out.println("FramesCheck ok");
    }
}
