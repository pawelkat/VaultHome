package com.vaulthome.panel

/** Wearable Data Layer contract between the phone (:app) and the watch (:wear). */
object PanelContract {
    const val PATH_PANEL = "/vaulthome/panel"

    /** ISO date of the panel, e.g. `2026-10-17`. */
    const val KEY_DATE = "date"

    /** [PanelHash.of] of the parsed note; the watch only re-notifies when it changes. */
    const val KEY_HASH = "hash"

    /** [PanelCodec.encode] of the parsed note. */
    const val KEY_PAYLOAD = "payload"

    /** Payload format version, bumped if [PanelCodec] changes incompatibly. */
    const val KEY_VERSION = "version"
    const val VERSION = 1
}
