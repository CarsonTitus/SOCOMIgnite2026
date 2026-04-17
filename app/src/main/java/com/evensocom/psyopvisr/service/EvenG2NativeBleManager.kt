package com.evensocom.psyopvisr.service

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * BLE transport for the Even Realities G2.
 *
 * Implements the aa-21 envelope protocol documented in g2-kit-unofficial:
 * - CRC-16/CCITT-FALSE (poly 0x1021, init 0xFFFF, no reflect, no final XOR)
 * - Fragment seq is a GROUP KEY (same value for every fragment of one message)
 * - magic is effectively uint8 (firmware uses only low byte)
 * - Write-without-response on fff2, notifications on fff1
 *
 * Modelled on Commute773/droidbridge BleManager.kt — per-device write queue
 * ensures only one outstanding GATT write at a time.
 */
@SuppressLint("MissingPermission")
class EvenG2NativeBleManager(private val context: Context) {
    companion object {
        private const val TAG = "EvenG2BLE"

        // EVEN Hub content channel — Service 5450 (from g2-kit ble.ts)
        // This is the main G2 protocol: commands, responses, EvenHub traffic
        val UUID_SERVICE_CMD      = UUID.fromString("00002760-08c2-11e1-9073-0e8ac72e5450")
        val UUID_CHAR_WRITE_CMD   = UUID.fromString("00002760-08c2-11e1-9073-0e8ac72e5401")
        val UUID_CHAR_NOTIFY_CMD  = UUID.fromString("00002760-08c2-11e1-9073-0e8ac72e5402")

        val UUID_CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // EVEN Hub render/audio channel — Service 6450
        // Audio PCM/LC3 from the G2 mic arrives on 6402 notifications.
        val UUID_SERVICE_AUDIO     = UUID.fromString("00002760-08c2-11e1-9073-0e8ac72e6450")
        val UUID_CHAR_NOTIFY_AUDIO = UUID.fromString("00002760-08c2-11e1-9073-0e8ac72e6402")

        // EVEN Hub input/touchpad channel — Service 7450
        // Touchpad gesture events (sid=0x0d) arrive on 7402 when IsEventCapture=1.
        val UUID_SERVICE_INPUT     = UUID.fromString("00002760-08c2-11e1-9073-0e8ac72e7450")
        val UUID_CHAR_NOTIFY_INPUT = UUID.fromString("00002760-08c2-11e1-9073-0e8ac72e7402")

        // Microphone enable command: sid=0x01, payload = 0x0F (enable mic streaming)
        // Confirmed from droidbridge EvenHubManager "startMicCapture" flow.
        private val MIC_ENABLE_PAYLOAD = byteArrayOf(0x0F)
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private var bluetoothGatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var audioWriteChar: BluetoothGattCharacteristic? = null
    private var leftAudioGatt: BluetoothGatt? = null
    private var leftAudioNotifyChar: BluetoothGattCharacteristic? = null
    private var negotiatedMtu = 23 // default; updated in onMtuChanged
    private var useWriteWithResponse = false
    @Volatile private var lastAudioNotifyMs: Long = 0L
    @Volatile private var lastAudioDataMs: Long = 0L

    // Serialized write queue (mirrors droidbridge pattern)
    private val writeLock = Any()
    private var writeLatch: CountDownLatch? = null
    private var writeStatus: Int = BluetoothGatt.GATT_FAILURE
    private val WRITE_TIMEOUT_MS = 5_000L

    // Pending CCCD descriptor writes (chained sequentially through onDescriptorWrite)
    private val pendingCccds = ArrayDeque<BluetoothGattDescriptor>()

    // Envelope seq counter — fresh value per logical message, constant across fragments
    private var seqCounter = 0

    // Connection state flow
    private val _connectionState = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
    val connectionState = _connectionState.asSharedFlow()

    // Notification flow — command channel (5402): acks, battery, EvenHub events
    private val _notifyFlow = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    val notifyFlow = _notifyFlow.asSharedFlow()

    // Audio notify flow — render/audio channel (6402): LC3/PCM mic frames from the G2
    private val _audioNotifyFlow = MutableSharedFlow<ByteArray>(extraBufferCapacity = 256)
    val audioNotifyFlow = _audioNotifyFlow.asSharedFlow()

    // ──────────────────── Connection ────────────────────

    // Track how many times we've failed so we know when to switch to autoConnect=true
    private var directConnectFailCount = 0
    private var connectTarget: BluetoothDevice? = null
    private var leftAudioTarget: BluetoothDevice? = null

    fun connectToRightArm() {
        val paired = bluetoothAdapter?.bondedDevices
        Log.i(TAG, "Scanning ${paired?.size ?: 0} bonded devices...")
        paired?.forEach { Log.i(TAG, "  bonded: ${it.name} [${it.address}] type=${it.type}") }

        // Prefer the right arm (_R_); fall back to any Even G2 device
        val target = paired?.firstOrNull {
            it.name?.contains("G2", ignoreCase = true) == true &&
            it.name?.contains("_R_", ignoreCase = true) == true
        } ?: paired?.firstOrNull {
            it.name?.contains("Even", ignoreCase = true) == true
        }

        if (target == null) {
            Log.e(TAG, "No EVEN G2 glasses found among bonded devices")
            return
        }

        connectTarget = target
        leftAudioTarget = paired?.firstOrNull {
            it.name?.contains("G2", ignoreCase = true) == true &&
                it.name?.contains("_L_", ignoreCase = true) == true
        }

        // After 2 failed direct-connect attempts, switch to autoConnect=true
        // autoConnect=true waits for the device to advertise — more reliable when G2 is idle
        val useAutoConnect = directConnectFailCount >= 2
        if (useAutoConnect) {
            Log.i(TAG, "Switching to autoConnect=true after $directConnectFailCount failures")
        }

        Log.i(TAG, "Connecting → ${target.name} [${target.address}] autoConnect=$useAutoConnect")

        // Refresh GATT cache via reflection to clear stale service data (fixes status 147)
        try {
            bluetoothGatt?.let { old ->
                old.javaClass.getMethod("refresh").invoke(old)
                old.close()
            }
        } catch (_: Exception) {}
        bluetoothGatt = null

        bluetoothGatt = target.connectGatt(
            context,
            useAutoConnect,
            gattCallback,
            BluetoothDevice.TRANSPORT_LE
        )

        val left = leftAudioTarget
        if (left != null) {
            try {
                leftAudioGatt?.close()
            } catch (_: Exception) {}
            leftAudioGatt = null
            Log.i(TAG, "Connecting LEFT arm audio channel → ${left.name} [${left.address}]")
            leftAudioGatt = left.connectGatt(
                context,
                useAutoConnect,
                leftAudioGattCallback,
                BluetoothDevice.TRANSPORT_LE
            )
        } else {
            Log.w(TAG, "No LEFT arm found — mic audio may be unavailable")
        }
    }

    // ──────────────────── GATT callbacks ────────────────────

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val addr = gatt.device.address
            Log.i(TAG, "onConnectionStateChange: addr=$addr status=$status state=$newState")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    directConnectFailCount = 0   // reset on success
                    Log.i(TAG, "Connected. Refreshing GATT cache then requesting MTU 244...")
                    // Refresh GATT cache so service discovery always gets fresh results
                    try { gatt.javaClass.getMethod("refresh").invoke(gatt) } catch (_: Exception) {}
                    gatt.requestMtu(244)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (status != BluetoothGatt.GATT_SUCCESS && status != 0) {
                        directConnectFailCount++
                        Log.w(TAG, "Disconnected from $addr (status=$status failCount=$directConnectFailCount)")
                    } else {
                        Log.w(TAG, "Disconnected from $addr")
                    }
                    writeChar = null
                    writeLatch?.countDown() // unblock any pending write
                    gatt.close()
                    try {
                        leftAudioGatt?.disconnect()
                        leftAudioGatt?.close()
                    } catch (_: Exception) {}
                    leftAudioGatt = null
                    leftAudioNotifyChar = null
                    _connectionState.tryEmit(false)
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            negotiatedMtu = mtu
            Log.i(TAG, "MTU negotiated: $mtu (payload max = ${mtu - 3})")
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            Log.i(TAG, "onServicesDiscovered status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed")
                return
            }

            // Log all discovered services
            gatt.services.forEach { svc ->
                Log.i(TAG, "  service: ${svc.uuid}")
                svc.characteristics.forEach { ch ->
                    Log.i(TAG, "    char: ${ch.uuid} props=${ch.properties}")
                }
            }

            // Service 5450: EVEN Hub content channel (g2-kit ble.ts)
            val cmdService = gatt.getService(UUID_SERVICE_CMD)
            if (cmdService == null) {
                Log.e(TAG, "Service 5450 NOT FOUND — cannot communicate!")
                return
            }

            writeChar = cmdService.getCharacteristic(UUID_CHAR_WRITE_CMD)
            val notifyChar = cmdService.getCharacteristic(UUID_CHAR_NOTIFY_CMD)

            if (writeChar == null || notifyChar == null) {
                Log.e(TAG, "Chars missing: write=${writeChar != null} notify=${notifyChar != null}")
                return
            }

            // Service 5450 write char has props=4 (WRITE with response)
            useWriteWithResponse = true
            Log.i(TAG, "Using service 5450: write=5401 (props=${writeChar?.properties}) notify=5402")

            // Queue CCCD for 5402 (command notify)
            pendingCccds.clear()
            gatt.setCharacteristicNotification(notifyChar, true)
            notifyChar.getDescriptor(UUID_CCCD)?.let { desc ->
                desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                pendingCccds.addLast(desc)
            }

            // Queue CCCD for 6402 (audio/render notify) if present
            val audioService = gatt.getService(UUID_SERVICE_AUDIO)
            if (audioService != null) {
                audioWriteChar = audioService.getCharacteristic(UUID.fromString("00002760-08c2-11e1-9073-0e8ac72e6401"))
                val audioNotifyChar = audioService.getCharacteristic(UUID_CHAR_NOTIFY_AUDIO)
                if (audioNotifyChar != null) {
                    gatt.setCharacteristicNotification(audioNotifyChar, true)
                    audioNotifyChar.getDescriptor(UUID_CCCD)?.let { desc ->
                        desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        pendingCccds.addLast(desc)
                    }
                    Log.i(TAG, "Audio service 6450 found — will subscribe 6402")
                } else {
                    Log.w(TAG, "Audio service 6450 present but 6402 char missing")
                }
            } else {
                Log.w(TAG, "Audio service 6450 not found — no mic stream")
            }

            // Queue CCCD for 7402 (input/touchpad notify) if present
            val inputService = gatt.getService(UUID_SERVICE_INPUT)
            if (inputService != null) {
                val inputNotifyChar = inputService.getCharacteristic(UUID_CHAR_NOTIFY_INPUT)
                if (inputNotifyChar != null) {
                    gatt.setCharacteristicNotification(inputNotifyChar, true)
                    inputNotifyChar.getDescriptor(UUID_CCCD)?.let { desc ->
                        desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        pendingCccds.addLast(desc)
                    }
                    Log.i(TAG, "Input service 7450 found — will subscribe 7402 for touchpad")
                } else {
                    Log.w(TAG, "Input service 7450 present but 7402 char missing")
                }
            } else {
                Log.w(TAG, "Input service 7450 not found — touchpad events may arrive on 5402")
            }

            // Kick off the CCCD chain; completion signals connection-ready
            if (pendingCccds.isNotEmpty()) {
                gatt.writeDescriptor(pendingCccds.first())
            } else {
                Log.w(TAG, "No CCCDs to write, signaling ready anyway")
                _connectionState.tryEmit(true)
            }
        }

        // Write ack — unblock the serialized write queue
        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            Log.d(TAG, "onCharacteristicWrite: status=$status char=${characteristic.uuid}")
            writeStatus = status
            writeLatch?.countDown()
        }

        // Notification (Android < 13 path)
        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val data = characteristic.value ?: return
            routeNotification(characteristic.uuid, data)
        }

        // Notification (Android 13+ path)
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            routeNotification(characteristic.uuid, value)
        }

        private fun routeNotification(charUuid: java.util.UUID, data: ByteArray) {
            val hex = data.take(16).joinToString(" ") { String.format("%02x", it) }
            val suffix = if (data.size > 16) "..." else ""
            Log.d(TAG, "NOTIFY ← ${data.size}B char=$charUuid hex=$hex$suffix")
            if (charUuid == UUID_CHAR_NOTIFY_AUDIO) {
                lastAudioNotifyMs = System.currentTimeMillis()
                if (data.size > 8) {
                    lastAudioDataMs = lastAudioNotifyMs
                    _audioNotifyFlow.tryEmit(data)
                } else {
                    Log.d(TAG, "Audio-channel control packet (${data.size}B) ignored")
                }
            } else {
                _notifyFlow.tryEmit(data)
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            val charUuid = descriptor.characteristic?.uuid
            Log.i(TAG, "CCCD written for char=$charUuid status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "CCCD write failed for $charUuid — continuing chain anyway")
            }

            // Remove the head we just wrote
            if (pendingCccds.isNotEmpty()) pendingCccds.removeFirst()

            if (pendingCccds.isNotEmpty()) {
                // Write the next descriptor in the queue
                gatt.writeDescriptor(pendingCccds.first())
            } else {
                // All CCCDs subscribed — connection fully ready
                Log.i(TAG, "All CCCD descriptors written. Connection fully ready.")
                _connectionState.tryEmit(true)
            }
        }
    }

    private val leftAudioGattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val addr = gatt.device.address
            Log.i(TAG, "LEFT onConnectionStateChange: addr=$addr status=$status state=$newState")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    gatt.requestMtu(244)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.w(TAG, "LEFT arm audio disconnected: status=$status")
                    if (leftAudioGatt === gatt) {
                        leftAudioNotifyChar = null
                    }
                    try { gatt.close() } catch (_: Exception) {}
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.i(TAG, "LEFT arm MTU negotiated: $mtu")
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "LEFT arm service discovery failed: $status")
                return
            }
            val audioService = gatt.getService(UUID_SERVICE_AUDIO)
            val notifyChar = audioService?.getCharacteristic(UUID_CHAR_NOTIFY_AUDIO)
            if (notifyChar == null) {
                Log.w(TAG, "LEFT arm missing 6402 notify char")
                return
            }
            leftAudioNotifyChar = notifyChar
            gatt.setCharacteristicNotification(notifyChar, true)
            val cccd = notifyChar.getDescriptor(UUID_CCCD)
            if (cccd != null) {
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(cccd)
                Log.i(TAG, "LEFT arm subscribing to 6402")
            } else {
                Log.w(TAG, "LEFT arm 6402 missing CCCD")
            }
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val data = characteristic.value ?: return
            routeLeftAudioNotification(characteristic.uuid, data)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            routeLeftAudioNotification(characteristic.uuid, value)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            val charUuid = descriptor.characteristic?.uuid
            Log.i(TAG, "LEFT CCCD written for char=$charUuid status=$status")
        }
    }

    private fun routeLeftAudioNotification(charUuid: java.util.UUID, data: ByteArray) {
        if (charUuid != UUID_CHAR_NOTIFY_AUDIO) return
        val hex = data.take(16).joinToString(" ") { String.format("%02x", it) }
        val suffix = if (data.size > 16) "..." else ""
        Log.d(TAG, "LEFT NOTIFY ← ${data.size}B char=$charUuid hex=$hex$suffix")
        lastAudioNotifyMs = System.currentTimeMillis()
        if (data.size > 8) {
            lastAudioDataMs = lastAudioNotifyMs
            _audioNotifyFlow.tryEmit(data)
        } else {
            Log.d(TAG, "LEFT audio control packet (${data.size}B) ignored")
        }
    }

    // ──────────────────── Serialized BLE write ────────────────────

    /**
     * Write a raw byte array to fff2 with WRITE_TYPE_NO_RESPONSE.
     * Serialized with a latch so we never overlap two GATT writes.
     */
    private fun writeBytes(
        data: ByteArray,
        characteristic: BluetoothGattCharacteristic? = writeChar
    ): Boolean {
        val gatt = bluetoothGatt ?: return false
        val char = characteristic ?: return false

        synchronized(writeLock) {
            char.writeType = if (useWriteWithResponse)
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            else
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            char.value = data

            val latch = CountDownLatch(1)
            writeLatch = latch
            writeStatus = BluetoothGatt.GATT_FAILURE

            val started = gatt.writeCharacteristic(char)
            if (!started) {
                Log.w(TAG, "writeCharacteristic rejected by kernel")
                writeLatch = null
                return false
            }

            // For WRITE_TYPE_NO_RESPONSE the callback fires immediately (kernel ack,
            // not peer ack). Timeout is a safety net.
            val completed = try {
                latch.await(WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) { false }

            writeLatch = null
            return completed
        }
    }

    /**
     * Write raw bytes directly to fff2, fragmenting at MTU-3.
     * Used for pre-enveloped payloads (e.g. the session prelude blob).
     */
    fun writeRawBytes(data: ByteArray) {
        val maxChunk = negotiatedMtu - 3
        var offset = 0
        while (offset < data.size) {
            val end = minOf(offset + maxChunk, data.size)
            val chunk = data.copyOfRange(offset, end)
            val ok = writeBytes(chunk)
            if (!ok) Log.w(TAG, "Raw write failed at offset=$offset")
            offset = end
        }
        Log.d(TAG, "Raw bytes sent: ${data.size} bytes")
    }

    fun writeRawBytesToAudioChannel(data: ByteArray): Boolean {
        val ok = writeBytes(data, audioWriteChar)
        if (!ok) {
            Log.w(TAG, "Audio channel raw write failed (${data.size} bytes)")
        }
        return ok
    }

    // ──────────────────── Envelope framing ────────────────────

    /**
     * Build envelope frames per the actual g2-kit envelope.ts format and send them.
     *
     * TX frame layout (8-byte header):
     *   aa 21 <seq> <len> <totalFrags> <fragIdx> <sid> <flag> <pb_chunk...>
     *
     * - seq: transport-layer group key (same for ALL fragments of one message)
     * - len: length of the pb_chunk in THIS fragment (including CRC bytes on last frag)
     * - totalFrags: total number of fragments for this message
     * - fragIdx: 1-indexed fragment number
     * - sid: subsystem id
     * - flag: 0x20 = REQUEST
     * - CRC-16/CCITT-FALSE of the FULL pb payload, appended LITTLE-ENDIAN to the LAST frag only
     *
     * chunkSize = 232 (matches Mirai's transport fragmentation)
     */
    fun sendEnvelope(sid: Byte, magic: Int, payload: ByteArray) {
        val chunkSize = 232
        val crc = crc16CcittFalse(payload, 0, payload.size)
        val crcBytes = byteArrayOf((crc and 0xFF).toByte(), ((crc shr 8) and 0xFF).toByte()) // LE
        val totalWithCrc = payload.size + 2
        val totalFrags = maxOf(1, (totalWithCrc + chunkSize - 1) / chunkSize)
        val seq = (seqCounter++ and 0xFF).toByte()

        var off = 0
        for (i in 0 until totalFrags) {
            val isLast = (i == totalFrags - 1)
            val chunk: ByteArray
            if (isLast) {
                // Last fragment: remaining pb bytes + CRC LE
                val remain = payload.copyOfRange(off, payload.size)
                chunk = ByteArray(remain.size + 2)
                System.arraycopy(remain, 0, chunk, 0, remain.size)
                chunk[remain.size] = crcBytes[0]
                chunk[remain.size + 1] = crcBytes[1]
                off = payload.size
            } else {
                chunk = payload.copyOfRange(off, off + chunkSize)
                off += chunkSize
            }

            // Build 8-byte header + chunk
            val frame = ByteArray(8 + chunk.size)
            frame[0] = 0xAA.toByte()     // sync[0]
            frame[1] = 0x21              // sync[1] = TX
            frame[2] = seq               // transport seq (group key)
            frame[3] = chunk.size.toByte() // length of this chunk
            frame[4] = totalFrags.toByte() // total fragments
            frame[5] = (i + 1).toByte()  // fragment index (1-based)
            frame[6] = sid               // subsystem id
            frame[7] = 0x20             // flag = FLAG_REQUEST

            System.arraycopy(chunk, 0, frame, 8, chunk.size)

            val ok = writeBytes(frame)
            if (!ok) {
                Log.w(TAG, "Fragment write failed at frag ${i + 1}/$totalFrags")
            }
        }

        Log.d(TAG, "Envelope sent: sid=0x${String.format("%02x", sid)} payloadLen=${payload.size} frags=$totalFrags seq=$seq")
    }

    // ──────────────────── CRC ────────────────────

    /**
     * CRC-16/CCITT-FALSE: poly=0x1021, init=0xFFFF, no reflect, no final XOR.
     */
    private fun crc16CcittFalse(data: ByteArray, offset: Int, length: Int): Int {
        var crc = 0xFFFF
        for (i in offset until offset + length) {
            crc = crc xor ((data[i].toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if ((crc and 0x8000) != 0) (crc shl 1) xor 0x1021 else crc shl 1
            }
        }
        return crc and 0xFFFF
    }

    // ──────────────────── Mic control ────────────────────

    /**
     * Send mic-enable command to the glasses.
     *
     * Based on droidbridge EvenHubManager: the G2 expects a one-byte payload 0x0F
     * wrapped in the aa-21 envelope with sid=0x01 to begin streaming audio on 6402.
     * Call this after the session prelude is complete.
     */
    fun enableMicrophone() {
        sendEnvelope(0x01.toByte(), (seqCounter and 0xFF), MIC_ENABLE_PAYLOAD)
        Log.i(TAG, "Mic-enable command sent (payload=0x0F)")
    }

    fun enableMicrophoneViaAudioChannel() {
        val ok = writeRawBytesToAudioChannel(MIC_ENABLE_PAYLOAD)
        Log.i(TAG, "Mic-enable via audio channel sent (payload=0x0F, ok=$ok)")
    }

    fun getLastAudioNotifyMs(): Long = lastAudioNotifyMs
    fun getLastAudioDataMs(): Long = lastAudioDataMs

    // ──────────────────── Cleanup ────────────────────

    fun disconnect() {
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        leftAudioGatt?.disconnect()
        leftAudioGatt?.close()
        bluetoothGatt = null
        leftAudioGatt = null
        leftAudioNotifyChar = null
        writeChar = null
        audioWriteChar = null
    }
}
