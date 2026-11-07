package com.vaulthome.app.x4

import java.util.UUID

/**
 * BLE layout for the image flashed 2026-11-07 (`baac775`), from the vault note "Grok Bot - X4 BLE".
 * No pairing. Each write to [TEXT] is appended; [COMMIT] `0x00` clears the buffer, `0x01` shows the note;
 * [STATUS] notifies `pages:N` or a short error.
 */
object X4Ble {
    const val DEFAULT_NAME = "VaultHome X4"
    /** Static random address of Pawel's unit; a hint, not the only way to match. */
    const val DEFAULT_ADDRESS = "C2:45:6B:2D:4E:FC"

    val SERVICE: UUID = UUID.fromString("8a1f0001-4b6a-4c1e-9c3a-6d5e7f8091a2")
    val TEXT: UUID = UUID.fromString("8a1f0002-4b6a-4c1e-9c3a-6d5e7f8091a2")
    val COMMIT: UUID = UUID.fromString("8a1f0003-4b6a-4c1e-9c3a-6d5e7f8091a2")
    val STATUS: UUID = UUID.fromString("8a1f0004-4b6a-4c1e-9c3a-6d5e7f8091a2")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val COMMIT_CLEAR: Byte = 0x00
    const val COMMIT_SHOW: Byte = 0x01

    /** ATT default payload when the MTU is unknown or a long write was rejected. */
    const val FALLBACK_CHUNK = 20

    private val ADDRESS = Regex("^[0-9A-F]{2}(:[0-9A-F]{2}){5}$")
    private val PAGES = Regex("""(?i)pages\s*:\s*(\d+)""")

    fun normalizeName(raw: String): String = raw.trim().ifEmpty { DEFAULT_NAME }

    /** Uppercases and accepts `-` or `:`. Blank becomes [DEFAULT_ADDRESS]. */
    fun normalizeAddress(raw: String): String {
        val s = raw.trim().uppercase().replace('-', ':')
        return s.ifEmpty { DEFAULT_ADDRESS }
    }

    fun isValidAddress(address: String): Boolean = ADDRESS.matches(address)

    /** ATT payload bytes for a given negotiated MTU (3-byte ATT header). */
    fun payloadSize(mtu: Int): Int = (mtu - 3).coerceAtLeast(FALLBACK_CHUNK)

    /** Split [data] into pieces of at most [size] bytes (UTF-8-safe at commit time: the device appends raw bytes). */
    fun chunks(data: ByteArray, size: Int): List<ByteArray> {
        val n = size.coerceAtLeast(1)
        if (data.isEmpty()) return emptyList()
        val out = ArrayList<ByteArray>((data.size + n - 1) / n)
        var i = 0
        while (i < data.size) {
            val end = (i + n).coerceAtMost(data.size)
            out.add(data.copyOfRange(i, end))
            i = end
        }
        return out
    }

    sealed interface Notify {
        data class Pages(val count: Int) : Notify
        data class Error(val message: String) : Notify
    }

    /** Parse a status notify/read. Success is only `pages:N`. */
    fun parseNotify(raw: String): Notify {
        val text = raw.trim()
        val pages = PAGES.find(text)?.groupValues?.get(1)?.toIntOrNull()
        if (pages != null) return Notify.Pages(pages)
        return Notify.Error(text.ifBlank { "empty status" })
    }

    fun matchesAdvert(name: String?, address: String?, settings: X4Settings): Boolean {
        val nameOk = !name.isNullOrBlank() && name.equals(settings.deviceName, ignoreCase = true)
        val addrOk = isValidAddress(settings.addressHint) &&
            !address.isNullOrBlank() &&
            address.equals(settings.addressHint, ignoreCase = true)
        return nameOk || addrOk
    }
}
