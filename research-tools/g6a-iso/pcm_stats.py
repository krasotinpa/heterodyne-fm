"""Stats for raw s16le stereo 48 kHz PCM; writes a WAV next to it. Usage: python3 pcm_stats.py in.pcm"""
import array, math, sys, wave
path = sys.argv[1]
raw = open(path, 'rb').read()
odd = len(raw) % 4
a = array.array('h'); a.frombytes(raw[:len(raw) - len(raw) % 2])
frames = len(a) // 2
L, R = a[0:frames*2:2], a[1:frames*2:2]
def stats(x):
    peak = max(abs(v) for v in x); rms = math.sqrt(sum(v*v for v in x) / len(x))
    zeros = sum(1 for v in x if v == 0) / len(x)
    return peak, rms, zeros
print(f"bytes {len(raw)} (mod 4 = {odd}), frames {frames}, seconds {frames/48000:.3f}")
for n, x in (('L', L), ('R', R)):
    p, r, z = stats(x); print(f"{n}: peak {p} ({20*math.log10(p/32768) if p else -999:.1f} dBFS), rms {r:.1f} ({20*math.log10(r/32768) if r else -999:.1f} dBFS), zeros {z:.3%}")
same = sum(1 for l, r in zip(L, R) if l == r) / frames
print(f"L == R in {same:.2%} of frames")
# silence runs > 50 ms where both channels are exactly 0
run = longest = runs = 0
for l, r in zip(L, R):
    if l == 0 and r == 0: run += 1
    else:
        if run > 2400: runs += 1
        longest = max(longest, run); run = 0
print(f"zero runs >50 ms: {runs}, longest zero run {longest/48:.1f} ms")
# 1-second windows: rms per channel
print("rms per second L/R:", ' '.join(f"{math.sqrt(sum(v*v for v in L[i:i+48000])/48000):.0f}/{math.sqrt(sum(v*v for v in R[i:i+48000])/48000):.0f}" for i in range(0, frames - 47999, 48000)))
w = wave.open(path.rsplit('.', 1)[0] + '.wav', 'wb'); w.setnchannels(2); w.setsampwidth(2); w.setframerate(48000)
w.writeframes(raw[:frames*4]); w.close()
