package com.cinchcal

import java.util.UUID

/** Bluetooth SIG Cycling Power Service (CPS) constants and decoders. */
object Cps {
    fun uuid16(v: Int): UUID = UUID.fromString(String.format("%08x-0000-1000-8000-00805f9b34fb", v))

    val SERVICE: UUID = uuid16(0x1818)
    val MEASUREMENT: UUID = uuid16(0x2A63)
    val FEATURE: UUID = uuid16(0x2A65)
    val SENSOR_LOCATION: UUID = uuid16(0x2A5D)
    val CONTROL_POINT: UUID = uuid16(0x2A66)
    val CCCD: UUID = uuid16(0x2902)

    val BATTERY_LEVEL: UUID = uuid16(0x2A19)
    val DIS_STRINGS = linkedMapOf(
        uuid16(0x2A29) to "Manufacturer",
        uuid16(0x2A24) to "Model",
        uuid16(0x2A25) to "Serial",
        uuid16(0x2A27) to "Hardware rev",
        uuid16(0x2A26) to "Firmware rev",
        uuid16(0x2A28) to "Software rev",
    )

    // Control point op codes
    const val OP_SET_CRANK_LENGTH = 0x04
    const val OP_REQ_CRANK_LENGTH = 0x05
    const val OP_START_OFFSET_COMP = 0x0C
    const val OP_REQ_SAMPLING_RATE = 0x0E
    const val OP_REQ_FACTORY_CAL_DATE = 0x0F
    const val OP_START_ENHANCED_OFFSET_COMP = 0x10
    const val OP_RESPONSE = 0x20

    val OP_NAMES = mapOf(
        0x01 to "Set Cumulative Value", 0x02 to "Update Sensor Location",
        0x03 to "Request Supported Sensor Locations", 0x04 to "Set Crank Length",
        0x05 to "Request Crank Length", 0x06 to "Set Chain Length", 0x07 to "Request Chain Length",
        0x08 to "Set Chain Weight", 0x09 to "Request Chain Weight", 0x0A to "Set Span Length",
        0x0B to "Request Span Length", 0x0C to "Start Offset Compensation",
        0x0D to "Mask Measurement Content", 0x0E to "Request Sampling Rate",
        0x0F to "Request Factory Calibration Date", 0x10 to "Start Enhanced Offset Compensation",
    )
    val RESULT_NAMES = mapOf(
        0x01 to "Success", 0x02 to "Op code not supported",
        0x03 to "Invalid parameter", 0x04 to "Operation failed",
    )

    val FEATURE_BITS = listOf(
        "Pedal power balance", "Accumulated torque", "Wheel revolution data",
        "Crank revolution data", "Extreme magnitudes", "Extreme angles",
        "Top/bottom dead spot angles", "Accumulated energy", "Offset compensation indicator",
        "Offset compensation (zero)", "Measurement content masking", "Multiple sensor locations",
        "Crank length adjustment", "Chain length adjustment", "Chain weight adjustment",
        "Span length adjustment", "Sensor context = TORQUE (else force)",
        "Instantaneous measurement direction", "Factory calibration date",
        "Enhanced offset compensation",
    )

    val LOCATIONS = mapOf(
        0 to "Other", 1 to "Top of shoe", 2 to "In shoe", 3 to "Hip", 4 to "Front wheel",
        5 to "Left crank", 6 to "Right crank", 7 to "Left pedal", 8 to "Right pedal",
        9 to "Front hub", 10 to "Rear dropout", 11 to "Chainstay", 12 to "Rear wheel",
        13 to "Rear hub", 14 to "Chest", 15 to "Spider", 16 to "Chain ring",
    )

    fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    fun u16(b: ByteArray, i: Int) = u8(b, i) or (u8(b, i + 1) shl 8)
    fun s16(b: ByteArray, i: Int) = u16(b, i).toShort().toInt()
    fun u32(b: ByteArray, i: Int) = u16(b, i).toLong() or (u16(b, i + 2).toLong() shl 16)

    fun hex(b: ByteArray?): String = b?.joinToString(" ") { String.format("%02X", it) } ?: "null"

    fun parseHex(s: String): ByteArray {
        val clean = s.replace("0x", "").replace(Regex("[^0-9a-fA-F]"), "")
        require(clean.length % 2 == 0) { "odd number of hex digits" }
        return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    fun decodeFeatures(f: Long): List<String> =
        FEATURE_BITS.mapIndexedNotNull { i, n -> if (f and (1L shl i) != 0L) n else null }

    data class Measurement(
        val flags: Int,
        val powerW: Int,
        val balancePct: Double? = null,
        val balanceIsLeft: Boolean = false,
        val accTorqueNm: Double? = null,
        val accTorqueFromCrank: Boolean = false,
        val crankRevs: Int? = null,
        val crankEventTime: Int? = null,
        val maxForceN: Int? = null,
        val minForceN: Int? = null,
        val maxTorqueNm: Double? = null,
        val minTorqueNm: Double? = null,
        val accEnergyKj: Int? = null,
        val offsetCompIndicator: Boolean = false,
    )

    fun parseMeasurement(b: ByteArray): Measurement? {
        if (b.size < 4) return null
        val fl = u16(b, 0)
        var i = 4
        fun has(bit: Int) = fl and (1 shl bit) != 0
        fun avail(n: Int) = i + n <= b.size
        var m = Measurement(fl, s16(b, 2), offsetCompIndicator = has(12),
            balanceIsLeft = has(1), accTorqueFromCrank = has(3))
        if (has(0) && avail(1)) { m = m.copy(balancePct = u8(b, i) / 2.0); i += 1 }
        if (has(2) && avail(2)) { m = m.copy(accTorqueNm = u16(b, i) / 32.0); i += 2 }
        if (has(4) && avail(6)) { i += 6 }
        if (has(5) && avail(4)) { m = m.copy(crankRevs = u16(b, i), crankEventTime = u16(b, i + 2)); i += 4 }
        if (has(6) && avail(4)) { m = m.copy(maxForceN = s16(b, i), minForceN = s16(b, i + 2)); i += 4 }
        if (has(7) && avail(4)) { m = m.copy(maxTorqueNm = s16(b, i) / 32.0, minTorqueNm = s16(b, i + 2) / 32.0); i += 4 }
        if (has(8) && avail(3)) i += 3
        if (has(9) && avail(2)) i += 2
        if (has(10) && avail(2)) i += 2
        if (has(11) && avail(2)) { m = m.copy(accEnergyKj = u16(b, i)); i += 2 }
        return m
    }

    data class CpResponse(val requestOp: Int, val result: Int, val params: ByteArray) {
        val ok get() = result == 0x01
        fun describe(): String {
            val op = OP_NAMES[requestOp] ?: String.format("op 0x%02X", requestOp)
            val res = RESULT_NAMES[result] ?: String.format("result 0x%02X", result)
            return "$op → $res" + if (params.isNotEmpty()) " [${hex(params)}]" else ""
        }
    }

    fun parseCpResponse(b: ByteArray): CpResponse? {
        if (b.size < 3 || u8(b, 0) != OP_RESPONSE) return null
        return CpResponse(u8(b, 1), u8(b, 2), b.copyOfRange(3, b.size))
    }
}
