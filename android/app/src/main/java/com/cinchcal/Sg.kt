package com.cinchcal

import java.util.UUID

/**
 * Sensitivus "SG" command protocol used by the CINCH power meter (manufacturer: Sensitivus).
 * Learned by reading the original Sensitivus / Team ZWATT Android app.
 *
 * Commands are written to SG_COMMAND (…0010, write-with-response). Replies arrive as
 * notifications on SG_REPLY (…0013) and look like [command, status, data…], status 0 = OK,
 * status 0xFF = command not supported.
 *
 * ONLY the safe commands below are used. The protocol also contains bootloader, firmware-write
 * and factory-calibration-write commands; this app never sends those.
 */
object Sg {
    private fun u(s: String) = UUID.fromString("$s-990a-11e5-8994-feff819cdc9f")
    val SERVICE: UUID = u("00000001")
    val COMMAND: UUID = u("00000010")
    val REPLY: UUID = u("00000013")

    const val CMD_GET_APP_VERSION = 0x01
    const val CMD_GET_HWINFO = 0x08
    const val CMD_ZERO_CALIBRATION = 0x21
    const val CMD_GET_POWER_CALIBRATION_1 = 0x24
    const val CMD_GET_USER_SETTINGS = 0x2B
    const val CMD_SET_USER_SETTINGS = 0x2C

    /** Factory calibration, channel 1. Torque[Nm] = A1 * deltaZ1 / 65536 (hardware type 52 = CINCH52). */
    data class Factory(val a1: Int, val b1: Int, val c1: Int, val z1: Int?)

    fun s32(b: ByteArray, i: Int): Int =
        (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8) or
        ((b[i + 2].toInt() and 0xFF) shl 16) or ((b[i + 3].toInt() and 0xFF) shl 24)

    fun ok(r: ByteArray?) = r != null && r.size >= 2 && r[1].toInt() == 0

    fun parseFactory(r: ByteArray?): Factory? {
        if (!ok(r) || r!!.size < 14) return null
        return Factory(s32(r, 2), Cps.s16(r, 6), s32(r, 10), if (r.size >= 18) s32(r, 14) else null)
    }

    /** User settings reply: [2B, 0, adj(int16, 0.1 %), flags, averaging, int32]. */
    data class UserSettings(val adjustPct: Double, val flags: Byte, val averaging: Byte, val extra: Int)

    fun parseUser(r: ByteArray?): UserSettings? {
        if (!ok(r) || r!!.size < 10) return null
        return UserSettings(Cps.s16(r, 2) / 10.0, r[4], r[5], s32(r, 6))
    }

    fun buildSetUser(s: UserSettings, newAdjustPct: Double): ByteArray {
        val v = (newAdjustPct.coerceIn(-50.0, 50.0) * 10).toInt()
        return byteArrayOf(CMD_SET_USER_SETTINGS.toByte(), (v and 0xFF).toByte(), (v shr 8).toByte(),
            s.flags, s.averaging,
            (s.extra and 0xFF).toByte(), (s.extra shr 8).toByte(), (s.extra shr 16).toByte(), (s.extra shr 24).toByte())
    }

    fun torqueNm(a1: Int, dz: Int) = a1.toDouble() * dz / 65536.0
}
