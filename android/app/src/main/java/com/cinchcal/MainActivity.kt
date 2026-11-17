package com.cinchcal

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import kotlin.math.roundToInt

@SuppressLint("MissingPermission", "SetTextI18n")
class MainActivity : Activity(), Ble.Listener {

    private lateinit var ble: Ble
    private val main = Handler(Looper.getMainLooper())
    private val G = 9.80665

    // device state
    private var features: Long? = null
    private val torqueContext get() = features?.let { it and (1L shl 16) != 0L }
    private var lastCrankRevs: Int? = null
    private var lastCrankTime: Int? = null
    private var lastCadenceAt = 0L
    private var lastAccTorque: Double? = null
    private var lastAccTorqueAt = 0L

    // static weight test state
    private enum class Step { NONE, UNLOADED, LOADED, REZERO }
    private var step = Step.NONE
    private var unloadedOffset: Int? = null

    // views
    private lateinit var status: TextView
    private lateinit var deviceList: LinearLayout
    private lateinit var info: TextView
    private lateinit var livePower: TextView
    private lateinit var liveDetail: TextView
    private lateinit var calResult: TextView
    private lateinit var crankEdit: EditText
    private lateinit var crankResult: TextView
    private lateinit var massEdit: EditText
    private lateinit var testCrankEdit: EditText
    private lateinit var testExpected: TextView
    private lateinit var testResult: TextView
    private lateinit var explorer: LinearLayout
    private lateinit var writeUuid: EditText
    private lateinit var writeHex: EditText
    private lateinit var logView: TextView
    private val logLines = ArrayDeque<String>()
    private val infoLines = linkedMapOf<String, String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        ble = Ble(this, this)
        buildUi()
        log("Ready. Wake the power meter (spin the cranks) and tap Scan.")
    }

    override fun onDestroy() { stopScan(); ble.disconnect(); super.onDestroy() }

    // ------------------------------------------------------------------ UI

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(40)) }
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)

        status = TextView(this).apply { textSize = 15f; setTypeface(null, Typeface.BOLD); text = "Not connected" }
        root.addView(status)

        // --- Connect
        root.addView(header("1 · Connect"))
        root.addView(row(button("Scan") { startScanChecked() }, button("Disconnect") { ble.disconnect(); onDisconnected() }))
        deviceList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(deviceList)

        // --- Info
        root.addView(header("Device info"))
        info = mono("—"); root.addView(info)

        // --- Live
        root.addView(header("2 · Live data"))
        livePower = TextView(this).apply { textSize = 40f; setTypeface(null, Typeface.BOLD); text = "— W"; gravity = Gravity.CENTER }
        root.addView(livePower)
        liveDetail = mono("Waiting for data …"); root.addView(liveDetail)

        // --- Calibration
        root.addView(header("3 · Calibrate (zero offset)"))
        root.addView(note("Bike upright, BOTH feet off the pedals (that is what the original Cinch app asked). " +
            "Wake the meter with a couple of crank turns, let the cranks stop, wait 3 s, then tap Zero. " +
            "For the Cinch this uses its own native zero command."))
        root.addView(row(button("Zero offset") { startOffset(Step.NONE) },
                         button("Enhanced zero") { cpWrite(byteArrayOf(Cps.OP_START_ENHANCED_OFFSET_COMP.toByte())) }))
        calResult = mono("—"); root.addView(calResult)

        // --- Power adjustment (Cinch / Sensitivus user setting)
        root.addView(header("4 · Power adjustment"))
        root.addView(note("The Cinch measures torque in the spindle, so crank length does not matter. " +
            "Instead it has a power adjustment (−50 … +50 %) applied to everything it reports. " +
            "Normally 0 %. Only change it if the weight test shows a consistent error."))
        crankEdit = numberEdit("0")
        root.addView(row(TextView(this).apply { text = "%:" }, crankEdit,
            button("Set") { setAdjustment() }, button("Read") { sgReadUser() }))
        crankResult = mono("—"); root.addView(crankResult)

        // --- Static weight test
        root.addView(header("5 · Static weight test"))
        root.addView(note("The Cinch is a spindle meter that measures the LEFT leg (and doubles it), so hang the " +
            "weight on the LEFT pedal with the left crank exactly horizontal, pointing forward. " +
            "Use a level on the crank. Hang the weight from the pedal axle, not the pedal edge."))
        massEdit = numberEdit("20")
        testCrankEdit = numberEdit("170")
        root.addView(row(TextView(this).apply { text = "Mass kg:" }, massEdit,
                         TextView(this).apply { text = "  Crank mm:" }, testCrankEdit))
        testExpected = mono(""); root.addView(testExpected)
        updateExpected()
        massEdit.setOnFocusChangeListener { _, _ -> updateExpected() }
        testCrankEdit.setOnFocusChangeListener { _, _ -> updateExpected() }
        root.addView(note("A) Unloaded reading: no weight, crank horizontal → tap A.\n" +
            "B) Hang the weight, let it stop swinging → tap B.\n" +
            "C) Remove the weight and tap C to re-zero properly (B leaves the meter zeroed WITH the weight on)."))
        root.addView(row(button("A · unloaded") { startOffset(Step.UNLOADED) },
                         button("B · loaded") { startOffset(Step.LOADED) },
                         button("C · re-zero") { startOffset(Step.REZERO) }))
        testResult = mono("—"); root.addView(testResult)
        root.addView(note("How it works (Cinch): each step zeroes the meter and reads its zero point (Z1). " +
            "Torque = A1 × ΔZ1 / 65536 using the meter's own factory gain A1 — the same formula the " +
            "original Sensitivus app uses. Repeat the test 2–3 times; only trust a consistent result."))

        // --- Explorer
        root.addView(header("6 · GATT explorer (advanced)"))
        root.addView(note("Every service/characteristic the meter exposes. Use this to find hidden " +
            "manufacturer features. Values appear in the log."))
        root.addView(row(button("Read all") { readAll() }, button("Subscribe all") { subscribeAll() }))
        explorer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(explorer)
        writeUuid = EditText(this).apply { hint = "char UUID (e.g. 2A66)"; textSize = 13f; isSingleLine = true }
        writeHex = EditText(this).apply { hint = "hex bytes (e.g. 0C)"; textSize = 13f; isSingleLine = true }
        root.addView(writeUuid); root.addView(writeHex)
        root.addView(row(button("Write") { rawWrite() }))

        // --- Log
        root.addView(header("Log"))
        root.addView(row(button("Share log") { shareLog() }, button("Clear") { logLines.clear(); logView.text = "" }))
        logView = mono(""); logView.textSize = 11f
        root.addView(logView)
    }

    private fun header(t: String) = TextView(this).apply {
        text = t; textSize = 18f; setTypeface(null, Typeface.BOLD); setTextColor(Color.rgb(20, 90, 160))
        setPadding(0, dp(18), 0, dp(4))
    }
    private fun note(t: String) = TextView(this).apply { text = t; textSize = 13f; setTextColor(Color.DKGRAY); setPadding(0, 0, 0, dp(6)) }
    private fun mono(t: String) = TextView(this).apply { text = t; typeface = Typeface.MONOSPACE; textSize = 13f; setTextIsSelectable(true) }
    private fun button(t: String, f: () -> Unit) = Button(this).apply { text = t; isAllCaps = false; setOnClickListener { f() } }
    private fun numberEdit(v: String) = EditText(this).apply {
        setText(v); inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL; minEms = 3
    }
    private fun row(vararg vs: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        vs.forEach { addView(it, LinearLayout.LayoutParams(if (it is Button) 0 else LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT, if (it is Button) 1f else 0f)) }
    }

    private fun log(msg: String) {
        val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        logLines.addFirst("$ts  $msg")
        while (logLines.size > 500) logLines.removeLast()
        if (::logView.isInitialized) logView.text = logLines.joinToString("\n")
    }

    private fun shareLog() {
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"; putExtra(Intent.EXTRA_SUBJECT, "Cinch power meter log")
            putExtra(Intent.EXTRA_TEXT, logLines.reversed().joinToString("\n"))
        }
        startActivity(Intent.createChooser(i, "Share log"))
    }

    private fun alert(title: String, msg: String) =
        AlertDialog.Builder(this).setTitle(title).setMessage(msg).setPositiveButton("OK", null).show()

    // ------------------------------------------------------------------ Scanning

    private val adapter: BluetoothAdapter? get() = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val found = linkedMapOf<String, ScanResult>()
    private var scanning = false

    private fun neededPerms(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun startScanChecked() {
        val missing = neededPerms().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) { requestPermissions(missing.toTypedArray(), 1); return }
        val a = adapter ?: return alert("Bluetooth", "This device has no Bluetooth.").let { }
        if (!a.isEnabled) { startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)); return }
        startScan(a)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) startScanChecked()
        else alert("Permission needed", "Bluetooth permission is required to talk to the power meter.")
    }

    private fun startScan(a: BluetoothAdapter) {
        stopScan()
        found.clear(); deviceList.removeAllViews()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        a.bluetoothLeScanner?.startScan(null, settings, scanCb) ?: return
        scanning = true
        status.text = "Scanning … (spin the cranks to wake the meter)"
        log("Scan started")
        main.postDelayed({ stopScan(); if (!ble.connected) status.text = "Scan finished — tap a device" }, 15000)
    }

    private fun stopScan() {
        if (scanning) { try { adapter?.bluetoothLeScanner?.stopScan(scanCb) } catch (_: Exception) {}; scanning = false }
    }

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, r: ScanResult) {
            val name = r.scanRecord?.deviceName ?: r.device.name
            val isCps = r.scanRecord?.serviceUuids?.any { it.uuid == Cps.SERVICE } == true
            if (name == null && !isCps) return  // hide anonymous junk
            val isNew = !found.containsKey(r.device.address)
            found[r.device.address] = r
            if (isNew) renderDevices()
        }
        override fun onScanFailed(errorCode: Int) { log("Scan failed: $errorCode") }
    }

    private fun renderDevices() {
        deviceList.removeAllViews()
        found.values.sortedWith(compareByDescending<ScanResult> { isLikelyMeter(it) }.thenByDescending { it.rssi }).forEach { r ->
            val name = r.scanRecord?.deviceName ?: r.device.name ?: "(no name)"
            val tag = if (r.scanRecord?.serviceUuids?.any { it.uuid == Cps.SERVICE } == true) "  ⚡ power" else ""
            deviceList.addView(Button(this).apply {
                isAllCaps = false; gravity = Gravity.START or Gravity.CENTER_VERTICAL
                text = "$name$tag\n${r.device.address}  ${r.rssi} dBm"
                if (isLikelyMeter(r)) setTypeface(null, Typeface.BOLD)
                setOnClickListener { stopScan(); deviceList.removeAllViews(); ble.connect(r.device); status.text = "Connecting to $name …" }
            })
        }
    }

    private fun isLikelyMeter(r: ScanResult): Boolean {
        val n = (r.scanRecord?.deviceName ?: r.device.name ?: "").lowercase()
        return "cinch" in n || r.scanRecord?.serviceUuids?.any { it.uuid == Cps.SERVICE } == true
    }

    // ------------------------------------------------------------------ BLE listener

    override fun onLog(msg: String) = log(msg)

    override fun onConnected(ready: Boolean) { status.text = if (ready) "Connected" else "Connected, discovering …" }

    override fun onDisconnected() {
        status.text = "Not connected"; explorer.removeAllViews(); features = null
        lastCrankRevs = null; lastAccTorque = null
        sgQueue.clear(); sgInFlight = null; sgSeq++; sgUser = null
    }

    override fun onServices(gatt: BluetoothGatt) {
        infoLines.clear()
        renderExplorer(gatt)
        val cps = gatt.getService(Cps.SERVICE)
        if (cps == null) {
            log("WARNING: no standard Cycling Power Service found. Use the explorer + log to inspect the device.")
            alert("No Cycling Power Service", "This device does not expose the standard BLE power service. " +
                "Check you picked the right device. The explorer section lists what it does have.")
        }
        // Device information + battery
        Cps.DIS_STRINGS.keys.forEach { u -> ble.find(u)?.let { ble.read(it) } }
        ble.find(Cps.BATTERY_LEVEL)?.let { ble.read(it) }
        if (cps != null) {
            cps.getCharacteristic(Cps.FEATURE)?.let { ble.read(it) }
            cps.getCharacteristic(Cps.SENSOR_LOCATION)?.let { ble.read(it) }
            cps.getCharacteristic(Cps.CONTROL_POINT)?.let { ble.subscribe(it) }  // indications, required before writes
            cps.getCharacteristic(Cps.MEASUREMENT)?.let { ble.subscribe(it) }
            if (cps.getCharacteristic(Cps.CONTROL_POINT) != null && gatt.getService(Sg.SERVICE) == null) {
                cpWrite(byteArrayOf(Cps.OP_REQ_CRANK_LENGTH.toByte()))
                cpWrite(byteArrayOf(Cps.OP_REQ_FACTORY_CAL_DATE.toByte()))
                cpWrite(byteArrayOf(Cps.OP_REQ_SAMPLING_RATE.toByte()))
            }
        }
        if (gatt.getService(Sg.SERVICE) != null) {
            ble.find(Sg.REPLY)?.let { ble.subscribe(it) }
            main.postDelayed({ if (ble.connected) sgStartup() }, 800)
        }
    }

    override fun onValue(uuid: UUID, value: ByteArray, isNotify: Boolean) {
        when (uuid) {
            Cps.MEASUREMENT -> onMeasurement(value)
            Sg.REPLY -> sgOnReply(value)
            Cps.CONTROL_POINT -> { log("← CP: ${Cps.hex(value)}"); onCpResponse(value) }
            Cps.FEATURE -> {
                val f = if (value.size >= 4) Cps.u32(value, 0) else if (value.size >= 2) Cps.u16(value, 0).toLong() else 0L
                features = f
                log("Features 0x%08X".format(f))
                setInfo("Features", Cps.decodeFeatures(f).joinToString("\n  ", prefix = "\n  "))
                updateExpected()
            }
            Cps.SENSOR_LOCATION -> setInfo("Location", Cps.LOCATIONS[Cps.u8(value, 0)] ?: "code ${Cps.u8(value, 0)}")
            Cps.BATTERY_LEVEL -> setInfo("Battery", "${Cps.u8(value, 0)} %")
            else -> {
                val label = Cps.DIS_STRINGS[uuid]
                if (label != null && !isNotify) setInfo(label, String(value, Charsets.UTF_8).trim { it <= ' ' })
                else log("${if (isNotify) "notify" else "read"} ${Ble.short(uuid)}: ${Cps.hex(value)}  \"${printable(value)}\"")
            }
        }
    }

    private fun printable(b: ByteArray) = b.map { val c = it.toInt() and 0xFF; if (c in 32..126) c.toChar() else '.' }.joinToString("")

    private fun setInfo(k: String, v: String) {
        infoLines[k] = v
        info.text = infoLines.entries.joinToString("\n") { "${it.key}: ${it.value}" }
    }

    // ------------------------------------------------------------------ Measurement

    private fun onMeasurement(b: ByteArray) {
        val m = Cps.parseMeasurement(b) ?: return log("bad measurement ${Cps.hex(b)}")
        livePower.text = "${m.powerW} W"
        val now = System.currentTimeMillis()
        val sb = StringBuilder()

        // cadence from crank revs
        var cadence: Double? = null
        if (m.crankRevs != null && m.crankEventTime != null) {
            val pr = lastCrankRevs; val pt = lastCrankTime
            if (pr != null && pt != null) {
                val dr = (m.crankRevs - pr) and 0xFFFF
                val dt = ((m.crankEventTime - pt) and 0xFFFF) / 1024.0
                if (dr > 0 && dt > 0) { cadence = dr * 60.0 / dt; lastCadenceAt = now }
                else if (now - lastCadenceAt > 3000) cadence = 0.0
            }
            lastCrankRevs = m.crankRevs; lastCrankTime = m.crankEventTime
            sb.append("Cadence  : ${cadence?.let { "%.0f rpm".format(it) } ?: "…"}   (revs ${m.crankRevs})\n")
        }
        m.balancePct?.let { sb.append("Balance  : %.1f %% (%s)\n".format(it, if (m.balanceIsLeft) "left" else "ref unknown")) }
        m.accTorqueNm?.let { t ->
            val prev = lastAccTorque
            var rate = ""
            if (prev != null) {
                var d = t - prev; if (d < 0) d += 65536 / 32.0
                rate = "  Δ %.2f Nm".format(d)
            }
            lastAccTorque = t; lastAccTorqueAt = now
            sb.append("Acc torq : %.2f Nm (%s)%s\n".format(t, if (m.accTorqueFromCrank) "crank" else "wheel", rate))
        }
        if (m.maxTorqueNm != null) sb.append("Torque   : max %.2f / min %.2f Nm\n".format(m.maxTorqueNm, m.minTorqueNm))
        if (m.maxForceN != null) sb.append("Force    : max ${m.maxForceN} / min ${m.minForceN} N\n")
        m.accEnergyKj?.let { sb.append("Energy   : $it kJ\n") }
        if (m.offsetCompIndicator) sb.append("Offset compensation indicator: ON\n")
        sb.append("Flags 0x%04X  raw %s".format(m.flags, Cps.hex(b)))
        liveDetail.text = sb.toString()
    }

    // ------------------------------------------------------------------ Control point

    private fun cpWrite(data: ByteArray) {
        val c = ble.find(Cps.CONTROL_POINT)
        if (c == null) { alert("Not available", if (ble.connected) "This device has no Cycling Power Control Point." else "Connect first."); return }
        ble.write(c, data, true)
    }

    private fun startOffset(s: Step) {
        if (hasSg) {
            step = Step.NONE
            calResult.text = "Zeroing … keep the bike still, no feet on pedals"
            if (s != Step.NONE) testResult.text = "Reading … keep still"
            sgStep(s); return
        }
        if (features?.let { it and (1L shl 9) == 0L } == true)
            log("Note: the meter does not advertise 'offset compensation' in its feature bits; trying anyway.")
        step = s
        val label = when (s) { Step.NONE -> "Zeroing"; Step.UNLOADED -> "A: reading unloaded"
            Step.LOADED -> "B: reading loaded"; Step.REZERO -> "C: re-zeroing" }
        calResult.text = "$label … keep the bike still"
        if (s != Step.NONE) testResult.text = "$label …"
        cpWrite(byteArrayOf(Cps.OP_START_OFFSET_COMP.toByte()))
    }

    private fun offsetToString(v: Int) = when (torqueContext) {
        true -> "%d raw = %.2f Nm".format(v, v / 32.0)
        false -> "$v N"
        null -> "$v (units unknown)"
    }

    private fun onCpResponse(b: ByteArray) {
        val r = Cps.parseCpResponse(b) ?: return
        log(r.describe())
        when (r.requestOp) {
            Cps.OP_START_OFFSET_COMP -> {
                val v = if (r.params.size >= 2) Cps.s16(r.params, 0) else null
                val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
                calResult.text = if (r.ok) "✔ Zero OK at $stamp\nOffset: ${v?.let { offsetToString(it) } ?: "(not reported)"}"
                                 else "✘ Zero failed: ${Cps.RESULT_NAMES[r.result] ?: r.result}\nWake the meter (spin cranks) and retry."
                if (r.ok && v != null) handleTestStep(v) else if (!r.ok && step != Step.NONE) testResult.text = "Step failed: ${r.describe()}"
                step = Step.NONE
            }
            Cps.OP_START_ENHANCED_OFFSET_COMP -> {
                calResult.text = if (r.ok) {
                    val v = if (r.params.size >= 2) offsetToString(Cps.s16(r.params, 0)) else "?"
                    val mfr = if (r.params.size >= 4) " mfr 0x%04X data %s".format(Cps.u16(r.params, 2), Cps.hex(r.params.copyOfRange(4, r.params.size))) else ""
                    "✔ Enhanced zero OK\nOffset: $v$mfr"
                } else {
                    val why = when (r.params.firstOrNull()?.toInt()?.and(0xFF)) { 0x01 -> "incorrect calibration position (rotate cranks)"
                        0xFF -> "manufacturer error ${Cps.hex(r.params.copyOfRange(1, r.params.size))}"; else -> "" }
                    "✘ Enhanced zero: ${Cps.RESULT_NAMES[r.result] ?: r.result} $why"
                }
            }
            Cps.OP_REQ_SAMPLING_RATE -> if (r.ok && r.params.isNotEmpty()) setInfo("Sampling rate", "${Cps.u8(r.params, 0)} Hz")
            Cps.OP_REQ_FACTORY_CAL_DATE -> if (r.ok && r.params.size >= 7) setInfo("Factory cal date",
                "%04d-%02d-%02d %02d:%02d".format(Cps.u16(r.params, 0), Cps.u8(r.params, 2), Cps.u8(r.params, 3), Cps.u8(r.params, 4), Cps.u8(r.params, 5)))
        }
    }

    // ------------------------------------------------------------------ Sensitivus (CINCH) protocol

    private class SgCmd(val bytes: ByteArray, val timeoutMs: Long, val cb: (ByteArray?) -> Unit)
    private val sgQueue = kotlin.collections.ArrayDeque<SgCmd>()
    private var sgInFlight: SgCmd? = null
    private var sgSeq = 0
    private var sgUser: Sg.UserSettings? = null
    private var sgA1: Int? = null
    private var testZ1A: Int? = null
    private val hasSg get() = ble.find(Sg.COMMAND) != null && ble.find(Sg.REPLY) != null

    private fun sg(bytes: ByteArray, timeoutMs: Long = 3000, cb: (ByteArray?) -> Unit) {
        sgQueue.addLast(SgCmd(bytes, timeoutMs, cb))
        if (sgInFlight == null) sgNext()
    }
    private fun sg(cmd: Int, timeoutMs: Long = 3000, cb: (ByteArray?) -> Unit) = sg(byteArrayOf(cmd.toByte()), timeoutMs, cb)

    private fun sgNext() {
        val c = sgQueue.removeFirstOrNull() ?: return
        val ch = ble.find(Sg.COMMAND) ?: run { sgQueue.clear(); c.cb(null); return }
        sgInFlight = c
        val seq = ++sgSeq
        ble.write(ch, c.bytes, true)
        main.postDelayed({
            if (sgInFlight === c && seq == sgSeq) { log("SG 0x%02X: no reply".format(c.bytes[0])); sgInFlight = null; c.cb(null); sgNext() }
        }, c.timeoutMs)
    }

    private fun sgOnReply(v: ByteArray) {
        log("← SG: ${Cps.hex(v)}")
        val c = sgInFlight
        if (c != null && v.isNotEmpty() && v[0] == c.bytes[0]) {
            sgInFlight = null; sgSeq++
            if (v.size >= 2 && v[1] == 0xFF.toByte()) log("SG 0x%02X: not supported by this meter".format(v[0]))
            c.cb(v); sgNext()
        }
    }

    private fun sgStartup() {
        log("Cinch/Sensitivus service found — using its native calibration commands")
        sg(Sg.CMD_GET_APP_VERSION) { r -> if (Sg.ok(r) && r!!.size >= 4) setInfo("SG app version", "${Cps.u8(r, 2)}.${Cps.u8(r, 3)}") }
        sg(Sg.CMD_GET_HWINFO) { r -> if (Sg.ok(r) && r!!.size >= 6) setInfo("Hardware type", "${Cps.u8(r, 2)} rev ${Cps.u8(r, 3)}") }
        sg(Sg.CMD_GET_POWER_CALIBRATION_1) { r -> Sg.parseFactory(r)?.let { f ->
            sgA1 = f.a1
            setInfo("Factory cal", "A1=${f.a1} Z1=${f.z1 ?: "?"}")
            if (f.z1 != null && (f.z1 < 1000 || f.z1 > 15000)) log("WARNING: zero point Z1=${f.z1} outside the normal 1000–15000 range")
        } }
        sgReadUser()
    }

    private fun sgReadUser() {
        if (!hasSg) { alert("Not available", "Connect to the Cinch first."); return }
        sg(Sg.CMD_GET_USER_SETTINGS) { r ->
            val u = Sg.parseUser(r)
            sgUser = u
            crankResult.text = if (u == null) "Could not read settings" else
                if (u.adjustPct == 0.0) "Current adjustment: 0 % (not adjusted)" else "Current adjustment: %+.1f %%".format(u.adjustPct)
            if (u != null) crankEdit.setText("%.1f".format(Locale.US, u.adjustPct))
        }
    }

    private fun setAdjustment() {
        val u = sgUser ?: run { alert("Read first", "Tap Read first so the other settings are preserved."); return }
        val pct = crankEdit.text.toString().replace(',', '.').toDoubleOrNull()
        if (pct == null || pct < -50 || pct > 50) { alert("Power adjustment", "Enter a value between -50 and 50 %"); return }
        AlertDialog.Builder(this).setTitle("Change power adjustment?")
            .setMessage("From %+.1f %% to %+.1f %%. All power the meter reports will be scaled by this. You can set it back to 0 any time.".format(u.adjustPct, pct))
            .setPositiveButton("Set") { _, _ ->
                sg(Sg.buildSetUser(u, pct)) { r -> log(if (Sg.ok(r)) "Adjustment saved" else "Adjustment NOT saved"); sgReadUser() }
            }.setNegativeButton("Cancel", null).show()
    }

    /** Original app's sequence: read factory cal → zero → read factory cal again. */
    private fun sgZero(done: (ok: Boolean, before: Sg.Factory?, after: Sg.Factory?, value: Int?) -> Unit) {
        sg(Sg.CMD_GET_POWER_CALIBRATION_1) { b ->
            val before = Sg.parseFactory(b)
            sg(Sg.CMD_ZERO_CALIBRATION, 8000) { z ->
                val ok = Sg.ok(z) && z!!.size >= 4
                val value = if (ok) Cps.s16(z!!, 2) else null
                sg(Sg.CMD_GET_POWER_CALIBRATION_1) { a ->
                    val after = Sg.parseFactory(a)
                    after?.let { sgA1 = it.a1; setInfo("Factory cal", "A1=${it.a1} Z1=${it.z1 ?: "?"}") }
                    done(ok, before, after, value)
                }
            }
        }
    }

    private fun sgStep(s: Step) {
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        sgZero { ok, before, after, value ->
            if (!ok) {
                calResult.text = "✘ Zero failed — wake the meter (spin cranks), keep it still, retry."
                if (s != Step.NONE) testResult.text = "Step failed — retry."
                return@sgZero
            }
            val z0 = before?.z1; val z1 = after?.z1; val a1 = after?.a1 ?: sgA1
            val moved = if (z0 != null && z1 != null && a1 != null) Sg.torqueNm(a1, z1 - z0) else null
            calResult.text = "✔ Zero OK at $stamp (value $value)\nZero point Z1: ${z0 ?: "?"} → ${z1 ?: "?"}" +
                (moved?.let { "\nZero moved by %.2f Nm".format(it) + if (abs(it) > 0.5) "  (significant — it was off)" else "  (was already fine)" } ?: "")
            when (s) {
                Step.UNLOADED -> { testZ1A = z1; testResult.text = "A recorded (Z1 = $z1).\nNow hang the weight, wait until still, tap B." }
                Step.LOADED -> sgTestResult(z1, a1)
                Step.REZERO -> testResult.text = testResult.text.toString().replace("⚠ Remove the weight and tap C now.", "") +
                    "\n✔ Re-zeroed without load. Ready to ride."
                Step.NONE -> {}
            }
        }
    }

    private fun sgTestResult(zB: Int?, a1: Int?) {
        val zA = testZ1A
        if (zA == null || zB == null || a1 == null) { testResult.text = "Missing data — do step A first, then B. Then C."; return }
        val m = massKg() ?: 0.0
        val measured = abs(Sg.torqueNm(a1, zB - zA))
        val expected = m * G * testCrankM()
        val sb = StringBuilder("Z1 unloaded $zA, loaded $zB (Δ ${zB - zA})\n")
        sb.append("Measured torque : %.2f Nm\nExpected torque : %.2f Nm\n".format(measured, expected))
        if (expected > 0 && measured > 0) {
            val err = (measured - expected) / expected * 100
            sb.append("Error: %+.1f %%\n".format(err))
            sb.append(when {
                abs(err) <= 2 -> "✔ Within ±2 % — accurate. No adjustment needed."
                abs(err) <= 5 -> "≈ Within ±5 % — re-check crank level / weight and repeat."
                else -> "✘ Off by more than 5 % — repeat 2–3 times. If consistent, use section 4."
            })
            val suggest = (expected / measured - 1) * 100
            sb.append("\nIf repeatable, adjustment to fix it: %+.1f %%".format(suggest))
            if (abs(err) > 2 && abs(suggest) <= 50) crankEdit.setText("%.1f".format(Locale.US, suggest + (sgUser?.adjustPct ?: 0.0)))
        }
        sb.append("\n\n⚠ Remove the weight and tap C now.")
        testResult.text = sb.toString()
        log("Static test (SG): Z1A=$zA Z1B=$zB A1=$a1 measured=%.3f expected=%.3f".format(measured, expected))
    }

    // ------------------------------------------------------------------ Static weight test

    private fun massKg() = massEdit.text.toString().toDoubleOrNull()
    private fun testCrankM() = (testCrankEdit.text.toString().toDoubleOrNull() ?: 170.0) / 1000.0

    private fun updateExpected() {
        val m = massKg() ?: return run { testExpected.text = "Enter the mass" }
        val f = m * G; val t = f * testCrankM()
        // Power the meter would show at 90 rpm with this constant torque on one leg (doubled):
        testExpected.text = "Expected force  : %.1f N\nExpected torque : %.2f Nm\n".format(f, t) +
            "(meter context: ${when (torqueContext) { true -> "torque"; false -> "force"; null -> "unknown until connected" }})"
    }

    private fun handleTestStep(v: Int) {
        when (step) {
            Step.UNLOADED -> { unloadedOffset = v; testResult.text = "A recorded: ${offsetToString(v)}\nNow hang the weight and tap B." }
            Step.LOADED -> {
                val a = unloadedOffset ?: return run { testResult.text = "Do step A first (unloaded). Then re-zero with C." }
                val d = v - a
                val m = massKg() ?: 0.0
                val sb = StringBuilder("A unloaded: ${offsetToString(a)}\nB loaded  : ${offsetToString(v)}\nDifference: ${offsetToString(d)}\n")
                val measured: Double?; val expected: Double; val unit: String
                when (torqueContext) {
                    true -> { measured = abs(d) / 32.0; expected = m * G * testCrankM(); unit = "Nm" }
                    false -> { measured = abs(d).toDouble(); expected = m * G; unit = "N" }
                    null -> { measured = null; expected = 0.0; unit = "" }
                }
                if (measured != null && expected > 0) {
                    val err = (measured - expected) / expected * 100
                    sb.append("Measured %.2f %s vs expected %.2f %s → %+.1f %%\n".format(measured, unit, expected, unit, err))
                    sb.append(when {
                        abs(err) <= 2.0 -> "✔ Within ±2 % — the meter looks accurate."
                        abs(err) <= 5.0 -> "≈ Within ±5 % — acceptable, re-check crank level & weight."
                        abs(err) > 80 -> "Large mismatch — firmware probably reports raw counts. Compare the ratio between two masses instead."
                        else -> "✘ Off by more than 5 % — repeat carefully; if consistent, the meter may need a factory slope calibration."
                    })
                    sb.append("\nScale factor (raw per kg): %.3f".format(abs(d) / m))
                }
                sb.append("\n\n⚠ Remove the weight and tap C now.")
                testResult.text = sb.toString()
                log("Static test: A=$a B=$v Δ=$d mass=$m kg")
            }
            Step.REZERO -> testResult.text = "✔ Re-zeroed without load: ${offsetToString(v)}. Ready to ride."
            Step.NONE -> {}
        }
    }

    // ------------------------------------------------------------------ Explorer

    private fun props(c: BluetoothGattCharacteristic): String {
        val p = c.properties; val l = mutableListOf<String>()
        if (p and BluetoothGattCharacteristic.PROPERTY_READ != 0) l += "R"
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) l += "W"
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) l += "Wnr"
        if (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) l += "N"
        if (p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) l += "I"
        return l.joinToString(",")
    }

    private fun renderExplorer(g: BluetoothGatt) {
        explorer.removeAllViews()
        g.services.forEach { s ->
            explorer.addView(TextView(this).apply { text = "Service ${Ble.short(s.uuid)}"; setTypeface(Typeface.MONOSPACE, Typeface.BOLD); textSize = 12f; setPadding(0, dp(8), 0, 0) })
            log("Service ${s.uuid}")
            s.characteristics.forEach { c ->
                log("   char ${c.uuid} [${props(c)}]")
                val p = c.properties
                val views = mutableListOf<View>(TextView(this).apply { text = "  ${Ble.short(c.uuid)} [${props(c)}]"; typeface = Typeface.MONOSPACE; textSize = 11f })
                if (p and BluetoothGattCharacteristic.PROPERTY_READ != 0) views += smallBtn("Read") { ble.read(c) }
                if (p and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) views += smallBtn("Sub") { ble.subscribe(c) }
                if (p and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0)
                    views += smallBtn("→W") { writeUuid.setText(c.uuid.toString()) }
                explorer.addView(LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; views.forEach { addView(it) } })
            }
        }
    }

    private fun smallBtn(t: String, f: () -> Unit) = Button(this, null, android.R.attr.buttonStyleSmall).apply {
        text = t; isAllCaps = false; textSize = 11f; minWidth = 0; minimumWidth = 0; setOnClickListener { f() }
    }

    private fun allChars(): List<BluetoothGattCharacteristic> = ble.allCharacteristics()

    private fun readAll() = allChars().filter { it.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0 }.forEach { ble.read(it) }
    private fun subscribeAll() = allChars().filter { it.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0 }
        .forEach { ble.subscribe(it) }

    private fun rawWrite() {
        val us = writeUuid.text.toString().trim()
        val uuid = try {
            if (us.length <= 6) Cps.uuid16(us.removePrefix("0x").toInt(16)) else UUID.fromString(us)
        } catch (e: Exception) { alert("UUID", "Bad UUID: $us"); return }
        val data = try { Cps.parseHex(writeHex.text.toString()) } catch (e: Exception) { alert("Hex", e.message ?: "bad hex"); return }
        val c = ble.find(uuid) ?: run { alert("Not found", "No characteristic $uuid on this device"); return }
        AlertDialog.Builder(this).setTitle("Write raw bytes?")
            .setMessage("Writing unknown bytes to manufacturer characteristics can change device settings. Write ${Cps.hex(data)} to ${Ble.short(uuid)}?")
            .setPositiveButton("Write") { _, _ -> ble.write(c, data, c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) }
            .setNegativeButton("Cancel", null).show()
    }
}
