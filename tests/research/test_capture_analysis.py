"""G0: offline observations over a raw capture. Run: python3 -m unittest discover -s tests/research"""
import hashlib
import json
import pathlib
import sys
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "research-tools"))
from analyze_capture import analyze  # noqa: E402

RAW = ROOT / "evidence" / "super-radio-com5.bin"
RAW_SHA256 = "f1697ad37d944abf4b1d09537caca7bd6f370793dd9aa3cc60b0f2a738e70d3d"

# Synthetic frames prove negative handling only, not the hardware protocol.
FRAME_24 = bytes.fromhex("FA55B800042400 00DC".replace(" ", ""))
MARKER_ONLY = b"\xfa\x55"


class SyntheticInput(unittest.TestCase):
    def test_empty(self):
        r = analyze(b"")
        self.assertEqual(r["byte_size"], 0)
        self.assertEqual(r["marker_count"], 0)
        self.assertEqual(r["bytes_before_first_marker"], 0)
        self.assertEqual(r["candidates"], [])

    def test_no_marker_counts_every_byte_as_unassigned(self):
        r = analyze(b"\x01\x02\x03")
        self.assertEqual(r["marker_count"], 0)
        self.assertEqual(r["bytes_before_first_marker"], 3)

    def test_garbage_before_first_marker(self):
        r = analyze(b"\x00\x11" + FRAME_24)
        self.assertEqual(r["bytes_before_first_marker"], 2)
        self.assertEqual(r["candidates"][0]["offset"], 2)
        self.assertTrue(r["candidates"][0]["complete"])

    def test_truncated_frame_stays_incomplete(self):
        r = analyze(FRAME_24[:-2])
        c = r["candidates"][0]
        self.assertFalse(c["complete"])
        self.assertIsNone(c["zero_sum"])
        self.assertEqual(r["complete_count"], 0)

    def test_marker_cut_before_length_byte(self):
        c = analyze(b"\xfa\x55\x01")["candidates"][0]
        self.assertIsNone(c["length_byte"])
        self.assertIsNone(c["type_byte"])
        self.assertFalse(c["complete"])

    def test_marker_inside_payload_is_flagged_not_trusted(self):
        # L=6 covers bytes +5..+10; a FA55 sits inside that range.
        data = bytes.fromhex("FA55010006 24 FA55 0000 8D".replace(" ", ""))
        r = analyze(data)
        self.assertEqual(r["marker_count"], 2)
        outer, inner = r["candidates"]
        self.assertTrue(outer["complete"])
        self.assertFalse(outer["end_equals_next_marker_or_eof"])
        self.assertTrue(inner["inside_previous_candidate"])
        self.assertTrue(outer["zero_sum"])
        self.assertEqual(outer["end"], 11)
        self.assertEqual(inner["end"], 6 + 5 + 0x8D)
        self.assertFalse(inner["complete"])

    def test_zero_length_has_no_type_and_no_sum(self):
        c = analyze(bytes.fromhex("FA55000000 FA55000000".replace(" ", "")))["candidates"][0]
        self.assertTrue(c["complete"])
        self.assertIsNone(c["type_byte"])
        self.assertIsNone(c["zero_sum"])

    def test_overlapping_marker_prefix(self):
        r = analyze(b"\xfa\xfa\x55\x00\x00\x00")
        self.assertEqual(r["bytes_before_first_marker"], 1)
        self.assertEqual(r["candidates"][0]["offset"], 1)

    def test_bare_marker_at_eof(self):
        c = analyze(MARKER_ONLY)["candidates"][0]
        self.assertIsNone(c["length_byte"])
        self.assertIsNone(c["plus2_plus3_le"])
        self.assertEqual(c["hex"], "FA 55")

    def test_counter_break_detection(self):
        frame = lambda n: b"\xfa\x55" + n.to_bytes(2, "little") + b"\x00"
        r = analyze(frame(1) + frame(2) + frame(5) + frame(0xFFFF) + frame(0))
        self.assertEqual(r["plus2_plus3_le_breaks"], [
            {"from_offset": 5, "from_value": 2, "to_offset": 10, "to_value": 5},
            {"from_offset": 10, "from_value": 5, "to_offset": 15, "to_value": 0xFFFF},
        ])

    def test_bad_trailer_is_reported(self):
        c = analyze(FRAME_24[:-1] + b"\x00")["candidates"][0]
        self.assertTrue(c["complete"])
        self.assertFalse(c["zero_sum"])


class RawCapture(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.data = RAW.read_bytes()
        cls.r = analyze(cls.data)

    def test_raw_is_unchanged(self):
        self.assertEqual(hashlib.sha256(self.data).hexdigest(), RAW_SHA256)
        self.assertEqual(self.r["sha256"], RAW_SHA256)
        self.assertEqual(self.r["byte_size"], 1720)

    def test_documented_measurements(self):
        r = self.r
        self.assertEqual(r["marker_count"], 104)
        self.assertEqual(r["bytes_before_first_marker"], 0)
        self.assertEqual((r["candidates"][0]["offset"], r["candidates"][-1]["offset"]), (0, 1711))
        self.assertEqual(r["span_counts"], {"25": 49, "9": 55})
        self.assertEqual(r["type_byte_counts"], {"0x24": 49, "0x26": 49, "0x29": 6})
        self.assertEqual(r["complete_count"], 104)
        self.assertEqual(r["end_equals_next_marker_or_eof_count"], 104)
        self.assertEqual(r["zero_sum_count"], 104)
        self.assertEqual(r["inside_previous_candidate_count"], 0)

    def test_counter_breaks(self):
        self.assertEqual(self.r["plus2_plus3_le_breaks"], [
            {"from_offset": 1158, "from_value": 253, "to_offset": 1183, "to_value": 0},
            {"from_offset": 1183, "from_value": 0, "to_offset": 1192, "to_value": 57},
        ])

    def test_matches_handoff_report_row_by_row(self):
        old = json.loads((ROOT / "evidence" / "capture-analysis.json").read_text())["rows"]
        new = self.r["candidates"]
        self.assertEqual(len(new), len(old))
        for o, n in zip(old, new):
            self.assertEqual(
                (n["offset"], n["length_byte"], n["end"], n["hex"]),
                (o["offset"], o["length_byte"], o["candidate_end"], o["hex"]),
            )


if __name__ == "__main__":
    unittest.main()
