#!/usr/bin/env python3
"""
Cinch power meter tool for Linux (Fedora) — zero-offset calibration, crank length,
static weight test and a GATT explorer, using the standard Bluetooth Cycling Power Service.

    sudo dnf install python3-pip bluez
    pip install --user bleak
    python3 cinch_tool.py
"""
import asyncio
import struct
import sys
import time

try:
    from bleak import BleakClient, BleakScanner
except ImportError:
    sys.exit("Missing dependency: run  pip install --user bleak")

def u(x): return f"0000{x:04x}-0000-1000-8000-00805f9b34fb"

CPS = u(0x1818); MEAS = u(0x2A63); FEAT = u(0x2A65); LOC = u(0x2A5D); CP = u(0x2A66)
BATT = u(0x2A19)
DIS = {u(0x2A29): "Manufacturer", u(0x2A24): "Model", u(0x2A25): "Serial",
       u(0x2A27): "Hardware", u(0x2A26): "Firmware", u(0x2A28): "Software"}
G = 9.80665

# Sensitivus "SG" protocol used by the CINCH (from the original Sensitivus/Team ZWATT app).
# Only safe commands are used: 0x01 version, 0x08 hw info, 0x21 zero, 0x24 read factory cal,
# 0x2B/0x2C read/write user settings (power adjustment).
def sgu(x): return f"{x}-990a-11e5-8994-feff819cdc9f"
SG_SVC = sgu("00000001"); SG_CMD = sgu("00000010"); SG_REPLY = sgu("00000013")

FEATURE_BITS = ["Pedal power balance", "Accumulated torque", "Wheel revolution data",
    "Crank revolution data", "Extreme magnitudes", "Extreme angles", "Dead spot angles",
    "Accumulated energy", "Offset compensation indicator", "Offset compensation (zero)",
    "Measurement content masking", "Multiple sensor locations", "Crank length adjustment",
    "Chain length adjustment", "Chain weight adjustment", "Span length adjustment",
    "Sensor context = TORQUE (else force)", "Instantaneous measurement direction",
    "Factory calibration date", "Enhanced offset compensation"]
RESULTS = {1: "Success", 2: "Op code not supported", 3: "Invalid parameter", 4: "Operation failed"}

def hexs(b): return " ".join(f"{x:02X}" for x in b)

def parse_measurement(b):
    if len(b) < 4: return None
    fl, power = struct.unpack_from("<Hh", b, 0); i = 4; m = {"flags": fl, "power": power}
    has = lambda bit: fl & (1 << bit)
    def take(fmt):
        nonlocal i
        n = struct.calcsize(fmt)
        if i + n > len(b): return None
        v = struct.unpack_from(fmt, b, i); i += n; return v
    if has(0): v = take("<B"); m["balance"] = v and v[0] / 2
    if has(2): v = take("<H"); m["acc_torque"] = v and v[0] / 32
    if has(4): take("<IH")
    if has(5): v = take("<HH"); m["crank"] = v
    if has(6): v = take("<hh"); m["force"] = v
    if has(7): v = take("<hh"); m["torque"] = v and (v[0] / 32, v[1] / 32)
    if has(8): take("<3s")
    if has(9): take("<H")
    if has(10): take("<H")
    if has(11): v = take("<H"); m["energy"] = v and v[0]
    m["offset_ind"] = bool(has(12))
    return m


class Meter:
    def __init__(self, client):
        self.c = client
        self.features = None
        self.cp_future = None
        self.last = None
        self.prev_crank = None
        self.cadence = None
        self.show_live = False
        self.sg = False
        self.sg_q = asyncio.Queue()

    def on_sg(self, _, data):
        self.sg_q.put_nowait(bytes(data))

    async def sgcmd(self, payload, timeout=5):
        while not self.sg_q.empty(): self.sg_q.get_nowait()
        await self.c.write_gatt_char(SG_CMD, bytes(payload), response=True)
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            try:
                r = await asyncio.wait_for(self.sg_q.get(), end - time.monotonic())
            except asyncio.TimeoutError:
                break
            if r and r[0] == payload[0]:
                if len(r) >= 2 and r[1] == 0xFF: print(f"  SG 0x{payload[0]:02X} not supported")
                return r
        print(f"  SG 0x{payload[0]:02X}: no reply"); return None

    async def factory(self):
        r = await self.sgcmd([0x24])
        if not r or len(r) < 14 or r[1] != 0: return None
        a1, b1 = struct.unpack_from("<ih", r, 2); c1 = struct.unpack_from("<i", r, 10)[0]
        z1 = struct.unpack_from("<i", r, 14)[0] if len(r) >= 18 else None
        return {"A1": a1, "B1": b1, "C1": c1, "Z1": z1}

    async def sg_zero(self):
        """Original app's sequence: read factory cal, zero, read again. Returns (ok, before, after)."""
        before = await self.factory()
        r = await self.sgcmd([0x21], timeout=8)
        ok = bool(r and len(r) >= 4 and r[1] == 0)
        after = await self.factory()
        if ok:
            print(f"  ✔ zero OK (value {struct.unpack_from('<h', r, 2)[0]})")
            if before and after and before["Z1"] is not None and after["Z1"] is not None:
                d = after["A1"] * (after["Z1"] - before["Z1"]) / 65536
                print(f"  zero point Z1 {before['Z1']} → {after['Z1']}  (moved {d:.2f} Nm)")
        else:
            print("  ✘ zero failed — spin cranks to wake it, keep still, retry")
        return ok, before, after

    async def user_settings(self):
        r = await self.sgcmd([0x2B])
        if not r or len(r) < 10 or r[1] != 0: return None
        adj, flags, avg, extra = struct.unpack_from("<hbbi", r, 2)
        return {"adj": adj / 10, "flags": flags, "avg": avg, "extra": extra}

    @property
    def torque_ctx(self):
        return None if self.features is None else bool(self.features & (1 << 16))

    def fmt_offset(self, v):
        if self.torque_ctx is True: return f"{v} raw = {v/32:.2f} Nm"
        if self.torque_ctx is False: return f"{v} N"
        return f"{v} (units unknown)"

    def on_meas(self, _, data):
        m = parse_measurement(bytes(data))
        if not m: return
        if "crank" in m and m["crank"]:
            r, t = m["crank"]
            if self.prev_crank:
                dr = (r - self.prev_crank[0]) & 0xFFFF; dt = ((t - self.prev_crank[1]) & 0xFFFF) / 1024
                if dr and dt: self.cadence = dr * 60 / dt
            self.prev_crank = (r, t)
        self.last = (m, bytes(data))
        if self.show_live:
            extra = ""
            if self.cadence is not None: extra += f"  {self.cadence:5.0f} rpm"
            if m.get("balance") is not None: extra += f"  bal {m['balance']:.1f}%"
            if m.get("acc_torque") is not None: extra += f"  accT {m['acc_torque']:.2f}Nm"
            if m.get("torque"): extra += f"  T {m['torque'][0]:.2f}/{m['torque'][1]:.2f}Nm"
            if m.get("force"): extra += f"  F {m['force'][0]}/{m['force'][1]}N"
            print(f"\r{m['power']:5d} W{extra}   raw {hexs(data)}      ", end="", flush=True)

    def on_cp(self, _, data):
        data = bytes(data)
        if len(data) >= 3 and data[0] == 0x20 and self.cp_future and not self.cp_future.done():
            self.cp_future.set_result((data[1], data[2], data[3:]))
        else:
            print(f"\n[CP] {hexs(data)}")

    async def cp(self, payload, timeout=10):
        self.cp_future = asyncio.get_running_loop().create_future()
        await self.c.write_gatt_char(CP, bytes(payload), response=True)
        try:
            op, res, params = await asyncio.wait_for(self.cp_future, timeout)
        except asyncio.TimeoutError:
            print("  no response from meter (timeout)"); return None
        print(f"  response op 0x{op:02X}: {RESULTS.get(res, res)} {hexs(params)}")
        return res, params

    async def setup(self):
        print("\n=== Device ===")
        for s in self.c.services:
            for ch in s.characteristics:
                if ch.uuid in DIS:
                    try: print(f"{DIS[ch.uuid]:13}: {(await self.c.read_gatt_char(ch)).decode(errors='replace').strip()}")
                    except Exception: pass
        try: print(f"Battery      : {(await self.c.read_gatt_char(BATT))[0]} %")
        except Exception: pass
        if self.c.services.get_service(SG_SVC):
            self.sg = True
            await self.c.start_notify(SG_REPLY, self.on_sg)
            print("Cinch/Sensitivus service found — using native commands")
            r = await self.sgcmd([0x01])
            if r and len(r) >= 4 and r[1] == 0: print(f"SG app version : {r[2]}.{r[3]}")
            r = await self.sgcmd([0x08])
            if r and len(r) >= 6 and r[1] == 0: print(f"Hardware type  : {r[2]} rev {r[3]}")
            f = await self.factory()
            if f: print(f"Factory cal    : A1={f['A1']} Z1={f['Z1']}")
            u_ = await self.user_settings()
            if u_: print(f"Power adjust   : {u_['adj']:+.1f} %")
        if not self.c.services.get_service(CPS):
            print("!! No standard Cycling Power Service. Use the explorer (option 7)."); return
        try:
            f = await self.c.read_gatt_char(FEAT)
            self.features = int.from_bytes(f[:4], "little")
            print(f"Features     : 0x{self.features:08X}")
            for i, n in enumerate(FEATURE_BITS):
                if self.features & (1 << i): print("   -", n)
        except Exception as e: print("features:", e)
        await self.c.start_notify(CP, self.on_cp)
        await self.c.start_notify(MEAS, self.on_meas)

    async def zero(self):
        if self.sg:
            ok, _, after = await self.sg_zero()
            return after["Z1"] if ok and after else None
        r = await self.cp([0x0C])
        if r and r[0] == 1 and len(r[1]) >= 2:
            v = struct.unpack("<h", r[1][:2])[0]; print(f"  ✔ zero OK, offset {self.fmt_offset(v)}"); return v
        print("  ✘ zero failed — spin the cranks to wake the meter and retry"); return None


async def pick_device():
    print("Scanning 10 s … spin the cranks to wake the meter.")
    found = await BleakScanner.discover(timeout=10, return_adv=True)
    devs = []
    for addr, (d, adv) in found.items():
        name = adv.local_name or d.name
        cps = CPS in [s.lower() for s in adv.service_uuids]
        if name or cps: devs.append((("cinch" in (name or "").lower()) or cps, adv.rssi, d, name, cps))
    devs.sort(key=lambda x: (not x[0], -x[1]))
    if not devs: sys.exit("Nothing found.")
    for i, (_, rssi, d, name, cps) in enumerate(devs):
        print(f" {i}: {name or '(no name)':24} {d.address}  {rssi} dBm {'⚡ power' if cps else ''}")
    return devs[int(input("Device number: ") or 0)][2]


async def ainput(prompt):
    return (await asyncio.get_running_loop().run_in_executor(None, input, prompt)).strip()


async def static_test(m: Meter):
    print("""
Static weight test. The Cinch measures the LEFT leg in the spindle, so:
 * Left crank horizontal, pointing forward (use a level).
 * Hang the weight from the LEFT pedal axle.""")
    kg = float(await ainput("Mass in kg [20]: ") or 20)
    mm = float(await ainput("Crank length mm [170]: ") or 170)
    ef, et = kg * G, kg * G * mm / 1000
    print(f"Expected: force {ef:.1f} N, torque {et:.2f} Nm")
    await ainput("A) NO weight, crank horizontal. Enter to read … ")
    a = await m.zero()
    if a is None: return
    await ainput("B) Hang the weight, wait until still. Enter to read … ")
    b = await m.zero()
    if b is not None and m.sg:
        f = await m.factory()
        meas = abs(f["A1"] * (b - a) / 65536)
        err = (meas - et) / et * 100
        print(f"  Z1 unloaded {a}, loaded {b}")
        print(f"  Measured {meas:.2f} Nm vs expected {et:.2f} Nm → {err:+.1f} %")
        print("  ✔ within ±2 %" if abs(err) <= 2 else "  ≈ within ±5 %, repeat" if abs(err) <= 5 else "  ✘ >5 % — repeat 2–3 times")
        print(f"  adjustment that would fix it (if repeatable): {(et/meas - 1)*100:+.1f} %")
    elif b is not None:
        d = b - a
        print(f"  Difference: {m.fmt_offset(d)}")
        if m.torque_ctx is not None:
            meas, exp, unit = (abs(d)/32, et, "Nm") if m.torque_ctx else (abs(d), ef, "N")
            err = (meas - exp) / exp * 100
            print(f"  Measured {meas:.2f} {unit} vs expected {exp:.2f} {unit} → {err:+.1f} %")
            if abs(err) <= 2: print("  ✔ within ±2 %")
            elif abs(err) <= 5: print("  ≈ within ±5 %")
            elif abs(err) > 80: print("  big mismatch: offset is probably raw counts; compare two masses for linearity")
            else: print("  ✘ off by >5 % — repeat carefully")
        print(f"  raw per kg: {abs(d)/kg:.3f}")
    await ainput("C) REMOVE the weight, Enter to re-zero without load … ")
    await m.zero()


async def explorer(m: Meter):
    for s in m.c.services:
        print(f"Service {s.uuid}  {s.description}")
        for ch in s.characteristics:
            val = ""
            if "read" in ch.properties:
                try:
                    v = await m.c.read_gatt_char(ch)
                    val = f" = {hexs(v)}  '{''.join(chr(x) if 32 <= x < 127 else '.' for x in v)}'"
                except Exception as e: val = f" (read err {e})"
            print(f"   {ch.uuid} {ch.properties}{val}")
    if (await ainput("Subscribe to all non-standard notify chars for 20 s? [y/N] ")).lower() == "y":
        subs = []
        for s in m.c.services:
            for ch in s.characteristics:
                if ("notify" in ch.properties or "indicate" in ch.properties) and ch.uuid not in (MEAS, CP):
                    try:
                        await m.c.start_notify(ch, lambda c, d: print(f"[{c.uuid}] {hexs(d)}")); subs.append(ch)
                    except Exception as e: print(ch.uuid, e)
        await asyncio.sleep(20)
        for ch in subs: await m.c.stop_notify(ch)


async def main():
    dev = await pick_device()
    async with BleakClient(dev, timeout=20) as client:
        m = Meter(client)
        await m.setup()
        while True:
            print("""
 1) Zero offset (calibrate)     2) Enhanced zero
 3) Live data (15 s)            4) Read power adjustment
 5) Set power adjustment        6) Static weight test
 7) GATT explorer               8) Raw write
 0) Quit""")
            c = await ainput("> ")
            try:
                if c == "1":
                    print("Bike upright, both feet off the pedals, cranks still."); await m.zero()
                elif c == "2":
                    await m.cp([0x10])
                elif c == "3":
                    m.show_live = True; await asyncio.sleep(15); m.show_live = False; print()
                elif c == "4":
                    u_ = await m.user_settings(); print(f"  power adjustment {u_['adj']:+.1f} %" if u_ else "  n/a")
                elif c == "5":
                    u_ = await m.user_settings()
                    if not u_: print("  n/a"); continue
                    pct = max(-50.0, min(50.0, float(await ainput(f"New % (now {u_['adj']:+.1f}): "))))
                    if (await ainput(f"Set power adjustment to {pct:+.1f} %? [y/N] ")).lower() == "y":
                        r = await m.sgcmd([0x2C] + list(struct.pack("<hbbi", round(pct * 10), u_["flags"], u_["avg"], u_["extra"])))
                        print("  saved" if r and r[1] == 0 else "  NOT saved")
                elif c == "6":
                    await static_test(m)
                elif c == "7":
                    await explorer(m)
                elif c == "8":
                    uu = await ainput("char UUID (16-bit like 2A66 or full): ")
                    if len(uu) <= 6: uu = u(int(uu.replace("0x", ""), 16))
                    data = bytes.fromhex((await ainput("hex bytes: ")).replace(" ", ""))
                    if (await ainput(f"Write {hexs(data)} to {uu}? [y/N] ")).lower() == "y":
                        await client.write_gatt_char(uu, data, response=True)
                elif c == "0":
                    break
            except Exception as e:
                print("error:", e)


if __name__ == "__main__":
    try: asyncio.run(main())
    except KeyboardInterrupt: pass
