package com.vaulthome.wear.data

import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService
import com.vaulthome.panel.PanelContract
import com.vaulthome.wear.notify.PanelNotifier
import com.vaulthome.wear.tile.PanelTileService

/** Phone published a Panel note → store it, notify if the hash is new, refresh the tile. */
class PanelListenerService : WearableListenerService() {
    override fun onDataChanged(dataEvents: DataEventBuffer) {
        val panel = dataEvents
            .filter { it.type == DataEvent.TYPE_CHANGED && it.dataItem.uri.path == PanelContract.PATH_PANEL }
            .mapNotNull { PanelStore.fromDataMap(DataMapItem.fromDataItem(it.dataItem).dataMap) }
            .lastOrNull() ?: return
        val store = PanelStore(this)
        store.save(panel)
        PanelNotifier.notifyIfNew(this, store, panel)
        PanelTileService.requestUpdate(this)
    }
}
