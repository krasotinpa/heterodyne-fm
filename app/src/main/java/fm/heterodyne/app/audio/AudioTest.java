package fm.heterodyne.app.audio;

/**
 * G2 receive loop. The audio thread reads one URB per call.
 * Frame alignment and byte handling follow G2; no drift compensation.
 */
final class AudioTest {
    static final int RATE = 48000, BYTES_PER_FRAME = 4;

    final AudioSource source;
    final AudioSink sink;
    volatile boolean stop;
    final byte[] urb = new byte[8 * 312];
    final int decide = RATE * BYTES_PER_FRAME / 10;      // 0.1 s decides the frame offset
    final byte[] pending = new byte[decide + urb.length];
    int have = 0, offset = -1, realigned = -1;
    long badSeen = 0;
    boolean playing = false;
    long bytes = 0, written = 0, sumSq = 0, samples = 0;
    long minBufferedFrames = Long.MAX_VALUE;

    AudioTest(AudioSource source, AudioSink sink) {
        this.source = source; this.sink = sink;
    }

    int receive() {
        if (stop) return 0;
        int n = source.read(urb);
        if (n <= 0) return n;
        bytes += n;
        long bad = source.badPackets();
        if (bad != badSeen) { badSeen = bad; offset = -1; have = 0; return n; }   // gap: drop and realign
        System.arraycopy(urb, 0, pending, have, n);
        have += n;
        if (offset < 0) {
            if (have < decide) return n;
            offset = Frames.frameOffset(pending, have);
            realigned++;
            System.arraycopy(pending, offset, pending, 0, have - offset);
            have -= offset;
        }
        int whole = have / BYTES_PER_FRAME * BYTES_PER_FRAME;
        for (int i = 0; i + 1 < whole; i += 2) { long v = Frames.s16(pending, i); sumSq += v * v; samples++; }
        if (playing) minBufferedFrames = Math.min(minBufferedFrames, sink.bufferedFrames());
        sink.write(pending, 0, whole);
        written += whole;
        if (!playing && written >= RATE * BYTES_PER_FRAME / 2) { sink.play(); playing = true; }   // 0.5 s prefilled, buffer holds 1 s
        System.arraycopy(pending, whole, pending, 0, have - whole);
        have -= whole;
        return n;
    }
}
