# CINCH / Sensitivus BLE protocol notes

Observed on a `CINCH52` (manufacturer string *Sensitivus*, firmware 7.93). Documented for interoperability.

## Services

| Service | UUID | Notes |
|---|---|---|
| Cycling Power (standard) | `0x1818` | Power + crank revolution data only (feature bits `0x00000008`). Control point answers **"Op code not supported"** for zero offset (`0x0C`), enhanced zero (`0x10`), crank length, sampling rate, factory cal date. |
| Battery | `0x180F` | |
| Device Information | `0x180A` | Manufacturer `Sensitivus`, model `CINCH52` |
| **Sensitivus "SG"** | `00000001-990a-11e5-8994-feff819cdc9f` | Native command channel |

### SG characteristics

| UUID | Properties | Role |
|---|---|---|
| `00000010-990a-11e5-8994-feff819cdc9f` | Write | **Command** |
| `00000011-990a-11e5-8994-feff819cdc9f` | Write w/o response | Bulk data (firmware) — not used |
| `00000012-990a-11e5-8994-feff819cdc9f` | Notify | Stream data — not used |
| `00000013-990a-11e5-8994-feff819cdc9f` | Notify | **Reply** |

## Framing

1. Enable notifications on `…0013`.
2. Write the command to `…0010` (write with response). Byte 0 is the command.
3. The reply arrives on `…0013`: `[command, status, data…]`. Status `0x00` means OK and `0xFF` means not supported.

No authentication is needed for the commands below. All multi-byte values are little-endian.

## Commands used by this app

| Cmd | Name | Request | Reply data (after cmd, status) |
|---|---|---|---|
| `0x01` | Get application version | `01` | `major, minor` |
| `0x08` | Get hardware info | `08` | `hwType, hwRev, clientCode(u16), …` (CINCH52 → type 52) |
| `0x21` | **Zero calibration** | `21` | `int16` (purpose unclear, observed `-1`). Allow ~5 s. |
| `0x24` | Get power calibration 1 | `24` | `A1(i32), B1(i16), pad(2), C1(i32), Z1(i32), Z3(i16)` |
| `0x2B` | Get user settings | `2B` | `adjust(i16, 0.1 %), flags(u8), averaging(u8), extra(i32)` |
| `0x2C` | Set user settings | `2C adjust(i16) flags averaging extra(i32)` | — (read first, write back the unchanged fields) |

### Zero calibration and torque

* `Z1` is the zero point in raw sensor counts (normal range 1000–15000).
* Torque change (Nm) = `A1 × ΔZ1 / 65536` (hardware type 52). On the test meter `A1 = −2431`, so 1 count ≈ 0.037 Nm.
* The original app's flow is: read `0x24`, ask for "bike upright, both feet off the pedals", send `0x21`, read `0x24` again, and report the change.
* A weight hung on the forward, horizontal left pedal (forward torque) **lowered** Z1, the same direction as the drift removed by zeroing.
* The zero point survives sleep: the meter still reported its old Z1 after a long time.

## Commands deliberately NOT used

Bootloader switch (`0x22`), image write/info (`0x09`, `0x0A`), set factory calibration (`0x27`–`0x2A`),
specialize (`0x3E`), testbed parameters (`0x3B`), memory read (`0x50`), flight mode (`0x31`).
Sending these can brick the meter or corrupt its factory calibration.
