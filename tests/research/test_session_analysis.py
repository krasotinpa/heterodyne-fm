"""G1b: model B frames, SEQ steps and USBPcap traces. Run: python3 -m unittest discover -s tests/research"""
import hashlib
import pathlib
import struct
import sys
import tempfile
import unittest
from collections import Counter

ROOT = pathlib.Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "research-tools"))
from analyze_session import build_frame, frame_times, module_traffic, parse_frames, read_usbpcap, seq_steps  # noqa: E402

EVIDENCE = ROOT / "evidence" / "g1b-20261014"


def frame(seq: int, body_wo_sum: bytes) -> bytes:
    body = body_wo_sum + bytes([-sum(body_wo_sum) % 256])
    return b"\xfa\x55" + bytes([seq]) + len(body).to_bytes(2, "big") + body


class Frames(unittest.TestCase):
    def test_build_request_all_info(self):
        # Utils.requestAllInfo body 80 85 07; DabService.send fills SEQ and the sum.
        self.assertEqual(build_frame(0, bytes.fromhex("808507")).hex(" "), "fa 55 00 00 04 80 85 07 f4")

    def test_build_tuneup(self):
        # Utils.TUNEUP body 80 88; SEQ 1 in the G4-2 schedule.
        self.assertEqual(build_frame(1, bytes.fromhex("8088")).hex(" "), "fa 55 01 00 03 80 88 f8")

    def test_build_tunedown(self):
        # Utils.TUNEDOWN body 80 89; SEQ 1 in the G4-3 schedule.
        self.assertEqual(build_frame(1, bytes.fromhex("8089")).hex(" "), "fa 55 01 00 03 80 89 f7")

    def test_g4_2_schedule_matches_build_frame(self):
        plan = (ROOT / "docs" / "plans" / "g4-2-windows-plan.md").read_text()
        for seq, body in ((0, "808507"), (1, "8088"), (2, "808507")):
            self.assertIn(build_frame(seq, bytes.fromhex(body)).hex("-").upper(), plan)

    def test_build_frame_rejects_seq_outside_a_byte(self):
        with self.assertRaises(ValueError):
            build_frame(256, bytes.fromhex("808507"))

    def test_built_frame_parses_back(self):
        f = parse_frames(build_frame(17, bytes.fromhex("808507")))["frames"]
        self.assertEqual([(x["seq"], x["length"], x["body"]) for x in f], [(17, 4, "80 85 07 f4")])

    def test_sequential_parse(self):
        data = frame(0, b"\x24\x00\x00") + frame(1, b"\x26\x04\x00" + b" " * 16)
        f = parse_frames(data)["frames"]
        self.assertEqual([(x["offset"], x["seq"], x["length"], x["type"]) for x in f],
                         [(0, 0, 4, "0x24"), (9, 1, 20, "0x26")])

    def test_junk_is_skipped_and_counted(self):
        r = parse_frames(b"\x01\x02" + frame(5, b"\x24\x00\x00"))
        self.assertEqual(r["junk"], [{"offset": 0, "length": 2}])
        self.assertEqual(r["frames"][0]["offset"], 2)

    def test_truncated_frame_resyncs_on_inner_marker(self):
        cut = frame(64, b"\x26\x04\x00" + b" " * 16)[:11]
        whole = frame(70, b"\x26\x04\x00" + b" " * 16) + frame(71, b"\x24\x00\x00")
        r = parse_frames(cut + whole)
        self.assertEqual([x["seq"] for x in r["frames"]], [70, 71])
        self.assertEqual(r["junk"], [{"offset": 0, "length": 11}])

    def test_bad_sum_then_good_frame(self):
        bad = frame(1, b"\x24\x00\x00")[:-1] + b"\x00"
        r = parse_frames(bad + frame(2, b"\x24\x00\x00"))
        self.assertEqual([x["seq"] for x in r["frames"]], [2])
        self.assertEqual(r["junk"], [{"offset": 0, "length": 9}])

    def test_bad_sum_at_end_is_tail(self):
        good = frame(0, b"\x24\x00\x00")
        r = parse_frames(good + good[:-1] + b"\x00")
        self.assertEqual((len(r["frames"]), r["junk"], r["tail"]), (1, [], 9))

    def test_length_below_two_is_not_a_frame(self):
        r = parse_frames(b"\xfa\x55\x01\x00\x00" + frame(2, b"\x24\x00\x00"))
        self.assertEqual([x["seq"] for x in r["frames"]], [2])
        self.assertEqual(r["junk"], [{"offset": 0, "length": 5}])

    def test_frame_times_reject_short_reads(self):
        with self.assertRaises(ValueError):
            frame_times([{"offset": 0, "length": 4}], "utc,elapsed_ms,event,offset,count,markers\nt,1,read,0,3,0\n")

    def test_incomplete_tail(self):
        r = parse_frames(frame(0, b"\x24\x00\x00")[:6])
        self.assertEqual(r["frames"], [])
        self.assertEqual(r["tail"], 6)

    def test_frame_times_use_the_read_holding_the_last_byte(self):
        frames = [{"offset": 0, "length": 4}, {"offset": 9, "length": 4}]
        reads = ("utc,elapsed_ms,event,offset,count,markers\n"
                 "t0,0,open,0,0,0\n"
                 "t1,10,read,0,12,1\n"
                 "t2,20,read,12,6,2\n")
        self.assertEqual(frame_times(frames, reads), [("t1", 10), ("t2", 20)])

    def test_seq_steps(self):
        seqs = [252, 253, 0, 1, 7]
        self.assertEqual(seq_steps(seqs), [
            {"index": 1, "from": 253, "to": 0, "step": 3},
            {"index": 3, "from": 1, "to": 7, "step": 6},
        ])


def pcapng(packets) -> bytes:
    """Minimal pcapng: SHB, one IDB (USBPcap), EPBs with microsecond timestamps."""
    def block(btype, body):
        body += b"\x00" * (-len(body) % 4)
        n = len(body) + 12
        return struct.pack("<II", btype, n) + body + struct.pack("<I", n)
    out = block(0x0A0D0D0A, struct.pack("<IHHq", 0x1A2B3C4D, 1, 0, -1))
    out += block(1, struct.pack("<HHI", 249, 0, 0))
    for ts, hdr_extra, info, dev, ep, transfer, data in packets:
        hl = 27 + len(hdr_extra)
        p = struct.pack("<HQIHBHHBBI", hl, 1, 0, 0, info, 1, dev, ep, transfer, len(data)) + hdr_extra + data
        out += block(6, struct.pack("<IIIII", 0, ts >> 32, ts & 0xFFFFFFFF, len(p), len(p)) + p)
    return out


DEVICE_DESC = bytes.fromhex("12 01 00 02 00 00 00 40 83 04 40 57 00 02 01 02 03 01")


class UsbPcap(unittest.TestCase):
    def test_module_traffic(self):
        pk = [
            (1_000_000, b"\x00", 0, 3, 0x80, 2, bytes.fromhex("8006000100001200")),   # setup
            (1_000_100, b"\x03", 1, 3, 0x80, 2, DEVICE_DESC),                         # complete
            (1_000_200, b"\x00", 0, 7, 0x80, 2, bytes.fromhex("8006000100001200")),   # other device
            (2_000_000, b"\x00", 0, 3, 0x00, 2, bytes.fromhex("2122020002000000")),   # RTS=1
            (3_000_000, b"", 1, 3, 0x83, 3, b"\xfa\x55\x00"),
            (3_000_100, b"", 1, 3, 0x83, 3, b"\x00\x04"),
            (3_000_200, b"", 0, 3, 0x03, 3, b""),
            (3_000_300, b"", 1, 7, 0x83, 3, b"\xee"),                                  # other device
        ]
        with tempfile.TemporaryDirectory() as d:
            path = pathlib.Path(d) / "t.pcapng"
            path.write_bytes(pcapng(pk))
            t = module_traffic(read_usbpcap(path))
        self.assertEqual(t["devices"], [{"iface": 0, "bus": 1, "dev": 3}])
        self.assertEqual(t["bulk_in"], b"\xfa\x55\x00\x00\x04")
        self.assertEqual(t["bulk_out_with_data"], 0)
        self.assertEqual(t["bulk_out"], [])
        self.assertEqual([s["setup"] for s in t["setups"]],
                         ["80 06 00 01 00 00 12 00", "21 22 02 00 02 00 00 00"])
        self.assertEqual(t["setups"][1]["utc"], "1970-01-01T00:00:02.000000Z")

    def test_bulk_out_with_data_is_counted(self):
        pk = [
            (1, b"\x03", 1, 3, 0x80, 2, DEVICE_DESC),
            (2, b"", 0, 3, 0x03, 3, b"\xfa\x55"),
        ]
        with tempfile.TemporaryDirectory() as d:
            path = pathlib.Path(d) / "t.pcapng"
            path.write_bytes(pcapng(pk))
            t = module_traffic(read_usbpcap(path))
            self.assertEqual(t["bulk_out_with_data"], 1)
            self.assertEqual(t["bulk_out"], [{"utc": "1970-01-01T00:00:00.000002Z", "ep": "0x03", "data": "fa 55"}])


class Evidence(unittest.TestCase):
    """Committed G1b receive data; SHA-256 values match raw/g1b SHA256SUMS.txt."""

    def check(self, name, sha):
        data = (EVIDENCE / name).read_bytes()
        self.assertEqual(hashlib.sha256(data).hexdigest(), sha)
        return parse_frames(data)

    def test_session_a_no_antenna(self):
        r = self.check("A-rx.bin", A_SHA)
        f = r["frames"]
        self.assertEqual(len(f), 384)
        self.assertEqual(r["junk"], [{"offset": 1229, "length": 11}])
        self.assertEqual(seq_steps([x["seq"] for x in f]), [
            {"index": 63, "from": 63, "to": 70, "step": 7},
            {"index": 247, "from": 253, "to": 0, "step": 3},
        ])
        self.assertEqual(Counter(x["body"] for x in f if x["type"] == "0x24"),
                         {"24 00 00 dc": 175, "24 01 64 77": 1, "24 00 0f cd": 1})

    def test_session_b_antenna(self):
        r = self.check("B-rx.bin", B_SHA)
        f = r["frames"]
        self.assertEqual((len(f), r["junk"], r["tail"]), (369, [], 0))
        self.assertEqual(seq_steps([x["seq"] for x in f]), [{"index": 253, "from": 253, "to": 0, "step": 3}])
        self.assertEqual(Counter(x["body"] for x in f if x["type"] == "0x24"),
                         {"24 00 04 d8": 167, "24 01 64 77": 1, "24 00 0f cd": 1, "24 00 03 d9": 1})


class G4Evidence(unittest.TestCase):
    """G4-1: one requestAllInfo at rx offset 2296 (tx-begin in g4-reads.csv)."""

    def test_response_follows_the_single_send(self):
        data = (ROOT / "evidence" / "g4-20261014T181220Z" / "g4-rx.bin").read_bytes()
        self.assertEqual(hashlib.sha256(data).hexdigest(),
                         "b6b7cd73487213e9937b20de7d66744417703c2096133112a6e0ed667c8d6365")
        after = [x for x in parse_frames(data)["frames"] if x["offset"] >= 2296][:5]
        self.assertEqual([(x["seq"], x["type"]) for x in after],
                         [(162, "0x27"), (163, "0x22"), (164, "0x24"), (165, "0x21"), (166, "0x26")])

    def test_trace_summary_shows_one_send(self):
        import json
        usb = json.loads((ROOT / "evidence" / "g4-20261014T181220Z" / "g4-analysis.json").read_text())["usb"]
        self.assertEqual((usb["bulk_out_with_data"], usb["bulk_in_equals_rx"]), (1, True))


class G42Evidence(unittest.TestCase):
    """G4-2: requestAllInfo at rx 2058, TUNEUP at 2720, requestAllInfo at 3319 (tx-begin in g4-2-reads.csv)."""

    DIR = ROOT / "evidence" / "g4-2-20261014T184648Z"

    def frames(self):
        data = (self.DIR / "g4-2-rx.bin").read_bytes()
        self.assertEqual(hashlib.sha256(data).hexdigest(),
                         "51a931a749982ed7c10a879e4dfc6a93f91d2a3393a1e24a027ff3abdd7d6e16")
        return parse_frames(data)["frames"]

    def first_0x22(self, frames, offset):
        return bytes.fromhex(next(x for x in frames if x["offset"] >= offset and x["type"] == "0x22")["body"])

    def test_tuneup_lowers_the_am_station_by_one_cid(self):
        frames = self.frames()
        bodies = [self.first_0x22(frames, o) for o in (2058, 2720, 3319)]
        self.assertEqual([b[1:3] for b in bodies], [b"\x00\x3a", b"\x00\x39", b"\x00\x39"])
        self.assertEqual([b[6] >> 5 & 3 for b in bodies], [0, 0, 0])
        # Frame offsets +30 and +50 are body indices 25 and 45; their meaning is unknown.
        self.assertEqual([(b[25], b[45]) for b in bodies], [(0x16, 0xae), (0xf7, 0x00), (0x00, 0xae)])
        self.assertEqual([b" 1044 KHz" in b for b in bodies], [True, False, False])
        self.assertEqual([b" 1035 KHz" in b for b in bodies], [False, True, True])

    def test_seq_steps_show_two_repeats_and_the_wrap(self):
        steps = seq_steps([x["seq"] for x in self.frames()])
        self.assertEqual([(s["from"], s["to"]) for s in steps], [(211, 211), (251, 250), (253, 0)])

    def test_trace_summary_shows_three_sends(self):
        import json
        usb = json.loads((self.DIR / "g4-2-analysis.json").read_text())["usb"]
        self.assertEqual([p["data"] for p in usb["bulk_out"]],
                         ["fa 55 00 00 04 80 85 07 f4", "fa 55 01 00 03 80 88 f8", "fa 55 02 00 04 80 85 07 f4"])
        self.assertEqual((usb["bulk_in_bytes"], usb["bulk_in_equals_rx"]), (5724, False))


class G43Evidence(unittest.TestCase):
    """G4-3: requestAllInfo at rx 2016, TUNEDOWN at 2678, requestAllInfo at 3277 (tx-begin in g4-3-reads.csv)."""

    DIR = ROOT / "evidence" / "g4-3-20261014T191717Z"

    def test_tunedown_returns_the_am_station_by_one_cid(self):
        data = (self.DIR / "g4-3-rx.bin").read_bytes()
        self.assertEqual(hashlib.sha256(data).hexdigest(),
                         "4d99268d30770842e0f42e7047cf392164e88b3790369dffbdda9cb1e2eeb844")
        frames = parse_frames(data)["frames"]
        bodies = [bytes.fromhex(next(x for x in frames if x["offset"] >= o and x["type"] == "0x22")["body"])
                  for o in (0, 2016, 2678, 3277)]
        self.assertEqual([b[1:3] for b in bodies], [b"\x00\x39", b"\x00\x39", b"\x00\x3a", b"\x00\x3a"])
        self.assertEqual([b" 1044 KHz" in b for b in bodies], [False, False, True, True])
        # Frame offsets +30 and +50 are body indices 25 and 45; their meaning is unknown.
        self.assertEqual([(b[25], b[45]) for b in bodies], [(0x16, 0xae), (0x00, 0xae), (0x00, 0x00), (0x16, 0xae)])

    def test_trace_summary_shows_three_sends(self):
        import json
        usb = json.loads((self.DIR / "g4-3-analysis.json").read_text())["usb"]
        self.assertEqual([p["data"] for p in usb["bulk_out"]],
                         ["fa 55 00 00 04 80 85 07 f4", "fa 55 01 00 03 80 89 f7", "fa 55 02 00 04 80 85 07 f4"])
        self.assertEqual((usb["bulk_in_bytes"], usb["bulk_in_equals_rx"]), (5579, False))


A_SHA = "8b2e17d5ae8c372eaf4090b139660804c33977d86bb7bbcf48cb59ae063ba7d7"
B_SHA = "af12a4332beab2097e427fee4dfd3b9b744649a34d9e0d1150b363e160949551"

if __name__ == "__main__":
    unittest.main()
