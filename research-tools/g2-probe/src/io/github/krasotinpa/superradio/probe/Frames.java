package io.github.krasotinpa.superradio.probe;

/** Frame alignment for the Module's continuous s16le stereo byte stream (G6a). Pure Java, checked on the JVM by FramesCheck. */
final class Frames {
    /**
     * Byte offset (0 or 2) of the first whole stereo frame. In a mono stretch the true frames have L == R exactly,
     * so the offset with more equal pairs wins. Stereo gives no signal: without more than half equal pairs at offset 0 the result is 2, the offset in all G6a captures.
     * A wrong pick only swaps channels with a one-sample delay.
     */
    static int frameOffset(byte[] b, int len) {
        int[] equal = new int[2];
        for (int o = 0; o < 2; o++) {
            for (int i = o * 2; i + 3 < len; i += 4) if (s16(b, i) == s16(b, i + 2)) equal[o]++;
        }
        int frames = len / 4;
        // Chance equal pairs in stereo are rare; offset 0 needs clear mono evidence.
        return equal[0] > frames / 2 && equal[0] > equal[1] ? 0 : 2;
    }

    static int s16(byte[] b, int i) { return (short) ((b[i] & 0xff) | (b[i + 1] << 8)); }
}
