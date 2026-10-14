"""G1b offline analysis: model B frames from a COM receive and Module traffic from a USBPcap trace.

Model B: FA 55 | SEQ (+2) | LEN (+3..+4, big-endian) | BODY (LEN >= 2 bytes: type .. sum); the BODY sum is 0 mod 256.
The parser resyncs on the next FA 55 when a candidate is cut short or fails its sum.

Usage: python3 research-tools/analyze_session.py <rx.bin> [reads.csv] [usb.pcapng]   (prints JSON)
"""
import datetime
import json
import struct
import sys
from collections import Counter

MARKER = b"\xfa\x55"
MODULE_ID = b"\x83\x04\x40\x57"  # idVendor 0x0483, idProduct 0x5740, little-endian


def parse_frames(data: bytes) -> dict:
    frames, junk = [], []
    i = 0
    while i < len(data):
        j = data.find(MARKER, i)
        if j == -1:
            break
        if j > i:
            junk.append({"offset": i, "length": j - i})
        if j + 5 > len(data):
            i = j
            break
        length = int.from_bytes(data[j + 3:j + 5], "big")
        end = j + 5 + length
        body = data[j + 5:end]
        if end > len(data) or length < 2 or sum(body) % 256:
            nxt = data.find(MARKER, j + 2)
            if nxt == -1:
                i = j
                break
            junk.append({"offset": j, "length": nxt - j})
            i = nxt
            continue
        frames.append({"offset": j, "seq": data[j + 2], "length": end - j - 5,
                       "type": f"0x{body[0]:02x}", "body": body.hex(" ")})
        i = end
    return {"frames": frames, "junk": junk, "tail": len(data) - i if i < len(data) else 0}


def frame_times(frames: list, reads_csv: str) -> list:
    """(utc, elapsed_ms) of the read that delivered each frame's last byte, from rx.ps1 reads.csv."""
    reads = [r.split(",") for r in reads_csv.splitlines()[1:] if ",read," in r]
    ends = [(int(r[3]) + int(r[4]), r[0], int(r[1])) for r in reads]
    out, k = [], 0
    for f in frames:
        last = f["offset"] + 5 + f["length"]
        while k < len(ends) and ends[k][0] < last:
            k += 1
        if k == len(ends):
            raise ValueError(f"reads.csv ends before the frame at offset {f['offset']}")
        out.append((ends[k][1], ends[k][2]))
    return out


def seq_steps(seqs: list) -> list:
    return [{"index": k, "from": a, "to": b, "step": (b - a) % 256}
            for k, (a, b) in enumerate(zip(seqs, seqs[1:])) if (b - a) % 256 != 1]


def _blocks(d: bytes):
    i = 0
    while i + 12 <= len(d):
        btype, n = struct.unpack_from("<II", d, i)
        yield btype, d[i + 8:i + n - 4]
        i += n


def read_usbpcap(path) -> list:
    """USBPcap packets from a pcapng file. Assumes microsecond timestamps (pcapng default)."""
    out = []
    for btype, b in _blocks(open(path, "rb").read()):
        if btype == 1:
            i = 8
            while i + 4 <= len(b):
                code, n = struct.unpack_from("<HH", b, i)
                if code == 9 and b[i + 4] != 6:
                    raise ValueError("only microsecond timestamps (if_tsresol 6) are supported")
                if code == 0:
                    break
                i += 4 + n + (-n % 4)
        if btype != 6:
            continue
        iface, hi, lo, cap = struct.unpack_from("<IIII", b, 0)
        p = b[20:20 + cap]
        hl, _irp, _st, _fn, info, bus, dev, ep, transfer, dl = struct.unpack_from("<HQIHBHHBBI", p, 0)
        out.append({"iface": iface, "ts_us": hi << 32 | lo, "from_device": bool(info & 1),
                    "bus": bus, "dev": dev, "ep": ep, "transfer": transfer,
                    "stage": p[27] if transfer == 2 and hl >= 28 else None,
                    "data": p[hl:hl + dl]})
    return out


def _utc(ts_us: int) -> str:
    t = datetime.datetime.fromtimestamp(ts_us / 1e6, datetime.timezone.utc)
    return t.strftime("%Y-%m-%dT%H:%M:%S.%fZ")


def module_traffic(packets: list) -> dict:
    """Traffic of every address whose device descriptor carries 0483:5740 (re-enumeration gives a new one)."""
    keys = sorted({(p["iface"], p["bus"], p["dev"]) for p in packets
                   if p["transfer"] == 2 and p["data"][:2] == b"\x12\x01" and p["data"][8:12] == MODULE_ID})
    mine = [p for p in packets if (p["iface"], p["bus"], p["dev"]) in keys]
    return {
        "devices": [dict(zip(("iface", "bus", "dev"), k)) for k in keys],
        "setups": [{"utc": _utc(p["ts_us"]), "setup": p["data"][:8].hex(" "),
                    "data": p["data"][8:].hex(" ")}
                   for p in mine if p["transfer"] == 2 and p["stage"] == 0],
        "bulk_in": b"".join(p["data"] for p in mine if p["transfer"] == 3 and p["ep"] & 0x80 and p["from_device"]),
        "bulk_out_with_data": sum(1 for p in mine if p["transfer"] == 3 and not p["ep"] & 0x80 and p["data"]),
        "interrupt_in_with_data": sum(1 for p in mine if p["transfer"] == 1 and p["from_device"] and p["data"]),
    }


if __name__ == "__main__":
    if not 2 <= len(sys.argv) <= 4:
        sys.exit(__doc__)
    rx = open(sys.argv[1], "rb").read()
    r = parse_frames(rx)
    f = r["frames"]
    report = {
        "rx_bytes": len(rx), "frames": len(f), "junk": r["junk"], "tail": r["tail"],
        "types": dict(sorted(Counter(x["type"] for x in f).items())),
        "seq_first_last": [f[0]["seq"], f[-1]["seq"]] if f else None,
        "seq_steps": seq_steps([x["seq"] for x in f]),
        "bodies": dict(Counter(x["body"] for x in f).most_common()),
    }
    if len(sys.argv) >= 3:
        times = frame_times(f, open(sys.argv[2]).read())
        report["timeline"] = [{"utc": u, "elapsed_ms": ms, "seq": x["seq"], "body": x["body"]}
                              for (u, ms), x in zip(times, f) if x["type"] != "0x26"]
    if len(sys.argv) == 4:
        t = module_traffic(read_usbpcap(sys.argv[3]))
        report["usb"] = {"devices": t["devices"], "setups": t["setups"],
                         "bulk_in_bytes": len(t["bulk_in"]), "bulk_in_equals_rx": t["bulk_in"] == rx,
                         "bulk_out_with_data": t["bulk_out_with_data"],
                         "interrupt_in_with_data": t["interrupt_in_with_data"]}
    print(json.dumps(report, indent=1, ensure_ascii=False))
