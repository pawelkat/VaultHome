package com.vaulthome.wear.presentation

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.vaulthome.panel.PanelFormat
import com.vaulthome.wear.R
import com.vaulthome.wear.data.PanelStore
import com.vaulthome.wear.data.StoredPanel
import com.vaulthome.wear.notify.PanelNotifier
import com.vaulthome.wear.tile.PanelTileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Full panel, opened from the notification, the tile or the launcher. Plain Views (no Compose)
 * to keep the watch APK small. Asks for the notification permission once.
 */
class PanelActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PanelNotifier.ensureChannel(this)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val side = dp(24)
            setPadding(side, dp(28), side, dp(40))
        }
        setContentView(ScrollView(this).apply { isVerticalScrollBarEnabled = true; addView(content) })

        val store = PanelStore(this)
        render(store.load(), loaded = false)
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
        scope.launch {
            val before = store.load()
            // Pick up a DataItem the listener may have missed (e.g. published before install).
            val fetched = store.fetchFromPhone()
            if (fetched != null && fetched.hash != before?.hash) PanelTileService.requestUpdate(this@PanelActivity)
            val panel = fetched ?: before
            render(panel, loaded = true)
            // First open after install: notify for this panel (no-op if already notified or no permission yet).
            panel?.let { PanelNotifier.notifyIfNew(this@PanelActivity, store, it) }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATIONS && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            val store = PanelStore(this)
            store.load()?.let { PanelNotifier.notifyIfNew(this, store, it) }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun render(panel: StoredPanel?, loaded: Boolean) {
        content.removeAllViews()
        val note = panel?.note
        content.addView(text(note?.let { PanelFormat.shortDate(it.date) } ?: getString(R.string.tile_label), 16f, bold = true, center = true))
        if (note == null) {
            if (loaded) content.addView(text(getString(R.string.no_data), 13f, center = true))
            return
        }
        val sections = PanelFormat.orderedSections(note).filter { it.items.isNotEmpty() }
        if (sections.isEmpty()) content.addView(text("Nothing on the panel today.", 13f, center = true))
        sections.forEach { section ->
            content.addView(text(section.name, 13f, bold = true).apply { setPadding(0, dp(8), 0, dp(2)) })
            section.items.forEach { content.addView(text("• $it", 13f)) }
        }
    }

    private fun text(value: String, sizeSp: Float, bold: Boolean = false, center: Boolean = false) = TextView(this).apply {
        text = value
        setTextColor(if (bold) 0xFFFFFFFF.toInt() else 0xFFE0E0E0.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        if (center) gravity = Gravity.CENTER_HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val REQUEST_NOTIFICATIONS = 1
    }
}
