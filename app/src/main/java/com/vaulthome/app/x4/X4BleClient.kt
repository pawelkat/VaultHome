package com.vaulthome.app.x4

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.Build
import android.os.ParcelUuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * LE scan + GATT client for the X4 image that has no Wi-Fi MCP.
 * One operation at a time; always disconnects so the reader can advertise again.
 */
class X4BleClient(
    private val app: Application,
    private val scanTimeoutMs: Long = 6_000,
    private val connectTimeoutMs: Long = 12_000,
    private val sendTimeoutMs: Long = 30_000,
) : X4Gateway {
    private val lock = Mutex()

    override suspend fun probe(settings: X4Settings): X4Found = lock.withLock {
        scan(settings).toFound()
    }

    override suspend fun showText(settings: X4Settings, markdown: String): X4ShowResult = lock.withLock {
        val found = scan(settings)
        session(found.device) { gatt, events ->
            val svc = gatt.getService(X4Ble.SERVICE)
                ?: throw X4Exception("X4 is missing the note service")
            val text = svc.getCharacteristic(X4Ble.TEXT)
                ?: throw X4Exception("X4 is missing the text characteristic")
            val commit = svc.getCharacteristic(X4Ble.COMMIT)
                ?: throw X4Exception("X4 is missing the commit characteristic")
            val status = svc.getCharacteristic(X4Ble.STATUS)
                ?: throw X4Exception("X4 is missing the status characteristic")

            enableNotify(gatt, events, status)
            write(gatt, events, commit, byteArrayOf(X4Ble.COMMIT_CLEAR))

            val bytes = markdown.toByteArray(Charsets.UTF_8)
            var chunk = X4Ble.payloadSize(events.mtu)
            var offset = 0
            while (offset < bytes.size) {
                val end = (offset + chunk).coerceAtMost(bytes.size)
                val piece = bytes.copyOfRange(offset, end)
                val ok = runCatching { write(gatt, events, text, piece) }.isSuccess
                if (!ok && chunk > X4Ble.FALLBACK_CHUNK) {
                    chunk = X4Ble.FALLBACK_CHUNK
                    continue
                }
                if (!ok) throw X4Exception("X4 rejected a text write")
                offset = end
            }

            write(gatt, events, commit, byteArrayOf(X4Ble.COMMIT_SHOW))
            val raw = waitStatus(gatt, events, status)
            when (val parsed = X4Ble.parseNotify(raw)) {
                is X4Ble.Notify.Pages -> X4ShowResult(
                    message = raw.trim(),
                    pages = parsed.count,
                    cut = raw.contains("cut", ignoreCase = true),
                    status = X4Status(raw.trim()),
                )
                is X4Ble.Notify.Error -> throw X4Exception("X4: ${parsed.message}")
            }
        }
    }

    private data class Scanned(val device: BluetoothDevice, val name: String) {
        fun toFound(): X4Found = X4Found(name = name, address = device.address)
    }

    private fun advertName(result: ScanResult): String? =
        result.scanRecord?.deviceName?.takeIf { it.isNotBlank() }
            ?: result.device.name?.takeIf { it.isNotBlank() }

    @SuppressLint("MissingPermission")
    private suspend fun scan(settings: X4Settings): Scanned {
        if (!X4BlePermissions.granted(app)) throw X4Exception("Bluetooth permission needed")
        val manager = app.getSystemService(BluetoothManager::class.java)
            ?: throw X4Exception("This device has no Bluetooth")
        val adapter = manager.adapter ?: throw X4Exception("This device has no Bluetooth")
        if (!adapter.isEnabled) throw X4Exception("Bluetooth is off")
        val scanner = adapter.bluetoothLeScanner ?: throw X4Exception("Bluetooth LE scanner unavailable")

        val filters = buildList {
            if (settings.deviceName.isNotBlank()) {
                add(ScanFilter.Builder().setDeviceName(settings.deviceName).build())
            }
            if (X4Ble.isValidAddress(settings.addressHint)) {
                add(ScanFilter.Builder().setDeviceAddress(settings.addressHint).build())
            }
            add(ScanFilter.Builder().setServiceUuid(ParcelUuid(X4Ble.SERVICE)).build())
        }
        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        return withContext(Dispatchers.Main) {
            val found = Channel<Scanned>(Channel.CONFLATED)
            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    if (!X4Ble.matchesAdvert(advertName(result), result.device.address, settings)) return
                    found.trySend(Scanned(result.device, advertName(result) ?: settings.deviceName))
                }

                override fun onScanFailed(errorCode: Int) {
                    found.close(X4Exception("X4 scan failed ($errorCode)"))
                }
            }
            try {
                scanner.startScan(filters, scanSettings, callback)
                withTimeoutOrNull(scanTimeoutMs) { found.receive() }
                    ?: throw X4Exception("not advertising — press any key to wake it, then try again")
            } finally {
                runCatching { scanner.stopScan(callback) }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun <T> session(device: BluetoothDevice, block: suspend (BluetoothGatt, GattEvents) -> T): T =
        withContext(Dispatchers.Main) {
            val events = GattEvents()
            val callback = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    events.trySend(
                        if (newState == BluetoothProfile.STATE_CONNECTED) GattEv.Connected(status)
                        else GattEv.Disconnected(status),
                    )
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    events.trySend(GattEv.Services(status))
                }

                override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                    if (status == BluetoothGatt.GATT_SUCCESS) events.mtu = mtu
                    events.trySend(GattEv.Mtu(mtu, status))
                }

                @Deprecated("Deprecated in Java")
                @Suppress("DEPRECATION")
                override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                    events.trySend(GattEv.Notify(characteristic.uuid, characteristic.value ?: ByteArray(0)))
                }

                override fun onCharacteristicChanged(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    value: ByteArray,
                ) {
                    events.trySend(GattEv.Notify(characteristic.uuid, value))
                }

                override fun onCharacteristicWrite(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    status: Int,
                ) {
                    events.trySend(GattEv.Written(characteristic.uuid, status))
                }

                override fun onCharacteristicRead(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    value: ByteArray,
                    status: Int,
                ) {
                    events.trySend(GattEv.Read(characteristic.uuid, value, status))
                }

                @Deprecated("Deprecated in Java")
                @Suppress("DEPRECATION")
                override fun onCharacteristicRead(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    status: Int,
                ) {
                    events.trySend(GattEv.Read(characteristic.uuid, characteristic.value ?: ByteArray(0), status))
                }

                override fun onDescriptorWrite(
                    gatt: BluetoothGatt,
                    descriptor: BluetoothGattDescriptor,
                    status: Int,
                ) {
                    events.trySend(GattEv.DescWritten(descriptor.uuid, status))
                }
            }

            val gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(app, false, callback, BluetoothDevice.TRANSPORT_LE)
            } else {
                @Suppress("DEPRECATION")
                device.connectGatt(app, false, callback)
            } ?: throw X4Exception("Could not connect to the X4")

            try {
                events.wait(connectTimeoutMs) { ev ->
                    when (ev) {
                        is GattEv.Connected ->
                            if (ev.status == BluetoothGatt.GATT_SUCCESS) ev
                            else throw X4Exception("X4 connect failed (${ev.status})")
                        is GattEv.Disconnected -> throw X4Exception("X4 disconnected (${ev.status})")
                        else -> null
                    }
                }
                if (!gatt.discoverServices()) throw X4Exception("X4 service discovery did not start")
                events.wait(connectTimeoutMs) { ev ->
                    when (ev) {
                        is GattEv.Services ->
                            if (ev.status == BluetoothGatt.GATT_SUCCESS) ev
                            else throw X4Exception("X4 service discovery failed (${ev.status})")
                        is GattEv.Disconnected -> throw X4Exception("X4 disconnected (${ev.status})")
                        else -> null
                    }
                }
                gatt.requestMtu(517)
                events.waitOrNull(2_000) { ev ->
                    when (ev) {
                        is GattEv.Mtu -> ev
                        is GattEv.Disconnected -> throw X4Exception("X4 disconnected")
                        else -> null
                    }
                }
                block(gatt, events)
            } finally {
                runCatching { gatt.disconnect() }
                runCatching { gatt.close() }
            }
        }

    @SuppressLint("MissingPermission")
    private suspend fun enableNotify(
        gatt: BluetoothGatt,
        events: GattEvents,
        status: BluetoothGattCharacteristic,
    ) {
        if (!gatt.setCharacteristicNotification(status, true)) {
            throw X4Exception("Could not subscribe to X4 status")
        }
        val cccd = status.getDescriptor(X4Ble.CCCD) ?: return
        writeDescriptor(gatt, cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        events.wait(5_000) { ev ->
            when (ev) {
                is GattEv.DescWritten ->
                    if (ev.status == BluetoothGatt.GATT_SUCCESS) ev
                    else throw X4Exception("X4 status notify setup failed (${ev.status})")
                is GattEv.Disconnected -> throw X4Exception("X4 disconnected")
                else -> null
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun write(
        gatt: BluetoothGatt,
        events: GattEvents,
        char: BluetoothGattCharacteristic,
        value: ByteArray,
    ) {
        val type = when {
            char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 ->
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0 ->
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            else -> BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        val queued = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(char, value, type) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                char.writeType = type
                char.value = value
                gatt.writeCharacteristic(char)
            }
        }
        if (!queued) throw X4Exception("X4 write was not queued")
        if (type == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) return
        events.wait(8_000) { ev ->
            when (ev) {
                is GattEv.Written -> when {
                    ev.uuid != char.uuid -> null
                    ev.status == BluetoothGatt.GATT_SUCCESS -> ev
                    else -> throw X4Exception("X4 write failed (${ev.status})")
                }
                is GattEv.Disconnected -> throw X4Exception("X4 disconnected")
                else -> null
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeDescriptor(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, value: ByteArray) {
        val queued = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                descriptor.value = value
                gatt.writeDescriptor(descriptor)
            }
        }
        if (!queued) throw X4Exception("X4 notify setup was not queued")
    }

    @SuppressLint("MissingPermission")
    private suspend fun waitStatus(
        gatt: BluetoothGatt,
        events: GattEvents,
        status: BluetoothGattCharacteristic,
    ): String {
        val notified = events.waitOrNull(sendTimeoutMs) { ev ->
            when (ev) {
                is GattEv.Notify -> if (ev.uuid == X4Ble.STATUS) String(ev.value, Charsets.UTF_8) else null
                is GattEv.Disconnected -> throw X4Exception("X4 disconnected before status")
                else -> null
            }
        }
        if (notified != null) return notified as String
        val readQueued = gatt.readCharacteristic(status)
        if (!readQueued) throw X4Exception("X4 sent no status")
        val read = events.wait(5_000) { ev ->
            when (ev) {
                is GattEv.Read -> when {
                    ev.uuid != X4Ble.STATUS -> null
                    ev.status == BluetoothGatt.GATT_SUCCESS -> ev
                    else -> throw X4Exception("X4 status read failed (${ev.status})")
                }
                is GattEv.Notify ->
                    if (ev.uuid == X4Ble.STATUS) GattEv.Read(ev.uuid, ev.value, BluetoothGatt.GATT_SUCCESS) else null
                is GattEv.Disconnected -> throw X4Exception("X4 disconnected before status")
                else -> null
            }
        } as GattEv.Read
        return String(read.value, Charsets.UTF_8)
    }
}

private sealed interface GattEv {
    data class Connected(val status: Int) : GattEv
    data class Disconnected(val status: Int) : GattEv
    data class Services(val status: Int) : GattEv
    data class Mtu(val mtu: Int, val status: Int) : GattEv
    data class Written(val uuid: java.util.UUID, val status: Int) : GattEv
    data class DescWritten(val uuid: java.util.UUID, val status: Int) : GattEv
    data class Notify(val uuid: java.util.UUID, val value: ByteArray) : GattEv
    data class Read(val uuid: java.util.UUID, val value: ByteArray, val status: Int) : GattEv
}

private class GattEvents {
    val ch = Channel<GattEv>(Channel.UNLIMITED)
    @Volatile var mtu: Int = 23

    fun trySend(ev: GattEv) {
        ch.trySend(ev)
    }

    suspend fun wait(timeoutMs: Long, pick: (GattEv) -> Any?): Any =
        withTimeout(timeoutMs) {
            while (true) {
                pick(ch.receive())?.let { return@withTimeout it }
            }
            error("unreachable")
        }

    suspend fun waitOrNull(timeoutMs: Long, pick: (GattEv) -> Any?): Any? =
        withTimeoutOrNull(timeoutMs) {
            while (true) {
                pick(ch.receive())?.let { return@withTimeoutOrNull it }
            }
        }
}
