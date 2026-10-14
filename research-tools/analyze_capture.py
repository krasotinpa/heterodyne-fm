"""G0 offline observations over a raw capture.

Reports arithmetic facts about FA 55 candidates only. A candidate is a possible
frame under hypotheses H01 (FA 55 start), H02 (+4 length) and H04 (additive
trailer); nothing here assigns protocol semantics. "hex" holds the candidate
bytes when complete, even past a marker inside it; otherwise bytes up to the
next marker or EOF.

Usage: python3 research-tools/analyze_capture.py <capture.bin>  (prints JSON)
"""
import hashlib
import json
import sys
from collections import Counter

MARKER = b"\xfa\x55"


def analyze(data: bytes) -> dict:
    offsets = []
    i = data.find(MARKER)
    while i != -1:
        offsets.append(i)
        i = data.find(MARKER, i + 1)

    candidates = []
    prev_end = 0
    for k, s in enumerate(offsets):
        nxt = offsets[k + 1] if k + 1 < len(offsets) else len(data)
        length = data[s + 4] if s + 4 < len(data) else None
        end = s + 5 + length if length is not None else None
        complete = end is not None and end <= len(data)
        candidates.append({
            "offset": s,
            "next_marker_or_eof": nxt,
            "span": nxt - s,
            "inside_previous_candidate": s < prev_end,
            "length_byte": length,
            "end": end,
            "complete": complete,
            "end_equals_next_marker_or_eof": end == nxt,
            "type_byte": f"0x{data[s + 5]:02X}" if length and s + 5 < len(data) else None,
            "plus2_plus3_le": int.from_bytes(data[s + 2:s + 4], "little") if s + 4 <= len(data) else None,
            "zero_sum": sum(data[s + 5:end]) % 256 == 0 if complete and length else None,
            "hex": data[s:end if complete else nxt].hex(" ").upper(),
        })
        if complete:
            prev_end = max(prev_end, end)

    breaks = [
        {"from_offset": a["offset"], "from_value": a["plus2_plus3_le"],
         "to_offset": b["offset"], "to_value": b["plus2_plus3_le"]}
        for a, b in zip(candidates, candidates[1:])
        if None not in (a["plus2_plus3_le"], b["plus2_plus3_le"])
        and (b["plus2_plus3_le"] - a["plus2_plus3_le"]) % 65536 != 1
    ]
    return {
        "byte_size": len(data),
        "sha256": hashlib.sha256(data).hexdigest(),
        "marker_count": len(offsets),
        "bytes_before_first_marker": offsets[0] if offsets else len(data),
        "span_counts": {str(k): v for k, v in Counter(c["span"] for c in candidates).most_common()},
        "type_byte_counts": dict(sorted(Counter(c["type_byte"] for c in candidates if c["type_byte"]).items())),
        "complete_count": sum(c["complete"] for c in candidates),
        "end_equals_next_marker_or_eof_count": sum(c["end_equals_next_marker_or_eof"] for c in candidates),
        "zero_sum_count": sum(c["zero_sum"] is True for c in candidates),
        "inside_previous_candidate_count": sum(c["inside_previous_candidate"] for c in candidates),
        "plus2_plus3_le_breaks": breaks,
        "candidates": candidates,
    }


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    with open(sys.argv[1], "rb") as f:
        print(json.dumps(analyze(f.read()), indent=1))
