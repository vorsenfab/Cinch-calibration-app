package com.cinchcal

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.UUID

/**
 * Thin GATT client with a serial operation queue (Android only allows one
 * outstanding GATT operation at a time). All callbacks are delivered on the main thread.
 */
@SuppressLint("MissingPermission")
class Ble(private val ctx: Context, private val listener: Listener) {

    interface Listener {
        fun onLog(msg: String)
        fun onConnected(ready: Boolean)
        fun onDisconnected()
        fun onServices(gatt: BluetoothGatt)
        fun onValue(uuid: UUID, value: ByteArray, isNotify: Boolean)
    }

    private val main = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private val queue = ArrayDeque<Pair<String, () -> Boolean>>()
    private var busy = false
    private var opSeq = 0

    val connected get() = gatt != null

    fun connect(device: BluetoothDevice) {
        disconnect()
        listener.onLog("Connecting to ${device.name ?: device.address} …")
        gatt = device.connectGatt(ctx, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        gatt?.let { it.disconnect(); it.close() }
        gatt = null
        queue.clear(); busy = false
    }

    fun find(uuid: UUID): BluetoothGattCharacteristic? =
        gatt?.services?.firstNotNullOfOrNull { s -> s.characteristics.firstOrNull { it.uuid == uuid } }

    fun allCharacteristics(): List<BluetoothGattCharacteristic> =
        gatt?.services?.flatMap { it.characteristics } ?: emptyList()

    // ---- queued operations ----

    private fun enqueue(name: String, op: () -> Boolean) {
        queue.addLast(name to op)
        if (!busy) next()
    }

    private fun next() {
        val (name, op) = queue.removeFirstOrNull() ?: run { busy = false; return }
        busy = true
        val seq = ++opSeq
        val started = try { op() } catch (e: Exception) { listener.onLog("$name failed: $e"); false }
        if (!started) { listener.onLog("$name could not be started"); busy = false; next(); return }
        // Watchdog so one lost callback can't freeze the queue.
        main.postDelayed({ if (busy && seq == opSeq) { listener.onLog("$name timed out"); done() } }, 6000)
    }

    private fun done() { main.post { busy = false; next() } }

    fun read(c: BluetoothGattCharacteristic) = enqueue("read ${short(c.uuid)}") { gatt?.readCharacteristic(c) == true }

    @Suppress("DEPRECATION")
    fun write(c: BluetoothGattCharacteristic, data: ByteArray, withResponse: Boolean = true) =
        enqueue("write ${short(c.uuid)}") {
            val g = gatt ?: return@enqueue false
            val type = if (withResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                       else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            listener.onLog("→ ${short(c.uuid)}: ${Cps.hex(data)}")
            if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(c, data, type) == BluetoothStatusCodes.SUCCESS
            else { c.writeType = type; c.value = data; g.writeCharacteristic(c) }
        }

    /** Enable notifications (or indications, if that is all the characteristic supports). */
    @Suppress("DEPRECATION")
    fun subscribe(c: BluetoothGattCharacteristic) = enqueue("subscribe ${short(c.uuid)}") {
        val g = gatt ?: return@enqueue false
        g.setCharacteristicNotification(c, true)
        val d = c.getDescriptor(Cps.CCCD) ?: run { listener.onLog("${short(c.uuid)} has no CCCD"); return@enqueue false }
        val v = if (c.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0 &&
                    c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0)
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, v) == BluetoothStatusCodes.SUCCESS
        else { d.value = v; g.writeDescriptor(d) }
    }

    // ---- callback ----

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) = main.post {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                listener.onLog("Connected (status $status). Discovering services …")
                listener.onConnected(false)
                g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                listener.onLog("Disconnected (status $status)")
                g.close()
                if (gatt == g) gatt = null
                queue.clear(); busy = false
                listener.onDisconnected()
            }
        }.let { }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = main.post {
            listener.onLog("Services discovered (status $status): ${g.services.size}")
            listener.onConnected(true)
            listener.onServices(g)
        }.let { }

        @Deprecated("pre-33")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            @Suppress("DEPRECATION") val v = c.value ?: ByteArray(0)
            handleRead(c, v, status)
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) =
            handleRead(c, value, status)

        private fun handleRead(c: BluetoothGattCharacteristic, v: ByteArray, status: Int) {
            main.post {
                if (status == BluetoothGatt.GATT_SUCCESS) listener.onValue(c.uuid, v, false)
                else listener.onLog("read ${short(c.uuid)} failed, status $status")
            }
            done()
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            main.post { if (status != BluetoothGatt.GATT_SUCCESS) listener.onLog("write ${short(c.uuid)} failed, status $status" +
                if (status == 0xFD) " (indications not enabled)" else "") }
            done()
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            main.post { listener.onLog("subscribe ${short(d.characteristic.uuid)}: " + if (status == 0) "ok" else "failed ($status)") }
            done()
        }

        @Deprecated("pre-33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION") val v = c.value?.copyOf() ?: return
            main.post { listener.onValue(c.uuid, v, true) }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            val v = value.copyOf()
            main.post { listener.onValue(c.uuid, v, true) }
        }
    }

    companion object {
        fun short(u: UUID): String {
            val s = u.toString()
            return if (s.endsWith("-0000-1000-8000-00805f9b34fb") && s.startsWith("0000")) "0x" + s.substring(4, 8).uppercase() else s
        }
    }
}
