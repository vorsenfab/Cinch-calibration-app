# Cinch Calibrator

**Zero-calibrate and test the Easton / Race Face CINCH spindle power meter, now that the official Cinch Power Meter and Team ZWATT apps are gone.**

Free, open-source replacement for the discontinued apps. Works on **Android** (app) and **Linux** (Python script, tested target: Fedora).

> Keywords: Easton Cinch power meter, Race Face Cinch power meter, CINCH spindle power meter app, Cinch calibration, zero offset, Sensitivus, Team ZWATT replacement, Garmin cannot calibrate Cinch, "Op code not supported".

---

## Why this exists

The CINCH power meter (electronics by **Sensitivus**) does **not** support the standard Bluetooth zero command.
Garmin, Wahoo, Zwift, TrainerRoad etc. therefore cannot calibrate it over Bluetooth (you get
*"Op code not supported"* or nothing happens), and the official apps were removed from the app stores.

Without zeroing, the meter drifts. On the meter this tool was developed with, the zero had drifted by
**1.08 Nm**, which made it read roughly **+10–20 W too high** at normal cadence.

This app talks to the meter using its own native (Sensitivus) command protocol, the same way the original app did.

## Features

| Feature | What it does |
|---|---|
| **Zero offset** | Native CINCH zero calibration. Shows the old → new zero point and how far (in Nm) it moved. |
| **Static weight test** | Hang a known weight on the pedal; compares measured torque with `mass × 9.81 × crank length`. |
| **Power adjustment** | Read / set the meter's built-in power scaling (−50 … +50 %), if a weight test shows a consistent error. |
| **Live data** | Power, cadence, raw packets. |
| **Device info** | Manufacturer, firmware, battery, hardware type, factory calibration values. |
| **GATT explorer + log** | For debugging other meters; shareable log. |

It also works as a basic standard Bluetooth (Cycling Power Service) zero tool for other power meters.

## Download / install

### Android (8.0+)
1. Download [`releases/CinchCalibrator-v2.0.apk`](releases/CinchCalibrator-v2.0.apk) to your phone.
2. Open it and allow *Install unknown apps* for your browser / file manager.
3. Allow the Bluetooth permission when asked.

Or build it yourself: open `android/` in Android Studio (or run `gradle assembleRelease` with the Android SDK installed).

### Linux (Fedora, Ubuntu, …)
```bash
sudo dnf install python3-pip bluez        # Ubuntu: sudo apt install python3-pip bluez
pip install --user bleak
python3 linux/cinch_tool.py
```

## How to use

**Close every other app / head unit connection first.** The meter accepts only one Bluetooth connection.

### Zero (calibrate)
1. Spin the cranks to wake the meter, tap **Scan**, pick `CINCH…`.
2. Bike upright, **both feet off the pedals**, **left crank pointing straight down (6 o'clock)**.
   Vertical matters: horizontal adds the crank + pedal weight (~0.5–0.8 Nm) into the zero.
3. Tap **Zero offset**. "Zero moved by" > 0.5 Nm means it had drifted. Zero again: it should now move ≤ ~0.15 Nm (normal noise).

Zero again when the temperature changes a lot (warm house → cold ride).

### Static weight test (accuracy check)
1. Lock the rear wheel (strap the rear brake lever).
2. Enter the **mass** (include strap/bucket) and crank length.
3. **A** – left crank horizontal pointing forward (use a level), no weight → tap A.
4. **B** – hang the weight from the **middle of the left pedal axle**, wait until still → tap B.
5. **C** – remove the weight, left crank to 6 o'clock → tap C (required: B leaves the meter zeroed with the weight on).

Use **10–20 kg** (water: 1 L = 1 kg). Lighter weights are dominated by noise (±0.15 Nm ≈ ±3 % at 2.7 kg).
Only change the power adjustment if 2–3 runs agree on an error beyond ±2 %.

## Compatibility

Developed and verified with:

| Field | Value |
|---|---|
| Model | `CINCH52` (hardware type 52 rev 2) |
| Manufacturer string | Sensitivus |
| Firmware | 7.93 / software 1.28 |
| BLE name | `CINCH5xxxx` |

Other CINCH variants and Sensitivus-based meters will probably work. Please open an issue with your
*Device info* screenshot and the shared log if yours does or doesn't.

## Feedback, questions, "it works on my meter too"

Feedback is very welcome, especially from owners of other CINCH variants.

* **Report a problem or ask a question:** [open an issue](../../issues/new/choose). Please attach a
  screenshot of *Device info* and the text from **Share log** (section *Log* in the app).
* **"Works on my meter":** use the *Compatibility report* issue form, so the compatibility table can grow.
* **Code improvements:** pull requests are welcome.

You need a (free) GitHub account to open an issue.

## Protocol

See [docs/PROTOCOL.md](docs/PROTOCOL.md) for the documented commands.

## Safety

The Sensitivus protocol also contains firmware-update, bootloader and factory-calibration-write commands.
**This app never sends them.** It only uses: version/hardware info, zero calibration, read factory
calibration, and read/write the user power-adjustment setting.

Use at your own risk. No warranty (see [LICENSE](LICENSE)).

## Disclaimer

Not affiliated with, endorsed by, or supported by Easton, Race Face, Fox Factory, Sensitivus or Team ZWATT.
All product names are trademarks of their respective owners and are used only to describe compatibility.
The protocol was documented for interoperability so owners can keep using hardware they own. No code from
the original apps is included.
