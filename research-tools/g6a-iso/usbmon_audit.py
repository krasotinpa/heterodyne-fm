"""Audit a usbmon text log (/sys/kernel/debug/usb/usbmon/0u): control requests and bulk OUT sent to devices.
Usage: python3 usbmon_audit.py usbmon.txt   (root hubs, device address 001, are skipped)"""
import sys
from collections import Counter


def audit(lines):
    control, bulk_out, iso, other = Counter(), [], Counter(), Counter()
    for line in lines:
        p = line.split()
        if len(p) < 4 or p[2] != "S":
            continue
        typ, _bus, dev, ep = p[3].split(":")
        if dev == "001":
            continue
        if typ in ("Ci", "Co") and p[4] == "s":
            bm, req, wv, wi = int(p[5], 16), p[6], p[7], p[8]
            kind = ("standard", "class", "vendor", "reserved")[(bm >> 5) & 3]
            rcpt = ("device", "interface", "endpoint", "other")[min(bm & 0x1F, 3)]
            control[(kind, rcpt, p[5], req, wv if kind != "standard" or req in ("0b", "01", "03") else "*", wi)] += 1
        elif typ == "Bo":
            bulk_out.append((ep, " ".join(p[p.index("=") + 1:]) if "=" in p else ""))
        elif typ.startswith("Z"):
            iso[f"{typ}:{ep}"] += 1
        else:
            other[f"{typ}:{ep}"] += 1
    return control, bulk_out, iso, other


def report(control, bulk_out, iso, other):
    out = ["control requests to the Module (kind, recipient, bmRequestType, bRequest, wValue, wIndex): count"]
    out += [f"  {k} {v}" for k, v in sorted(control.items())]
    out.append(f"vendor requests: {sum(v for k, v in control.items() if k[0] == 'vendor')}")
    out.append(f"class requests to IF 2/3: {sum(v for k, v in control.items() if k[0] == 'class' and k[1] == 'interface' and k[5] in ('0002', '0003'))}")
    out.append(f"bulk OUT submissions: {len(bulk_out)}")
    out += [f"  ep {e} {d}" for e, d in bulk_out]
    out.append(f"iso submissions: {dict(iso)}")
    out.append(f"other: {dict(other)}")
    return "\n".join(out)


if __name__ == "__main__":
    print(report(*audit(open(sys.argv[1]).read().splitlines())))
