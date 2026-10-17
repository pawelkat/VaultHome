package com.vaulthome.wear.tile

import android.content.Context
import androidx.concurrent.futures.SuspendToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import com.vaulthome.panel.PanelFormat
import com.vaulthome.panel.PanelPaths
import com.vaulthome.wear.data.PanelStore
import com.vaulthome.wear.presentation.PanelActivity

/** Tile: date, Critical, Weather, and a short Calendar list when there is room. Tap opens the full panel. */
class PanelTileService : TileService() {
    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> =
        SuspendToFutureAdapter.launchFuture {
            val store = PanelStore(this@PanelTileService)
            val panel = store.load() ?: store.fetchFromPhone()
            val text = PanelFormat.tile(panel?.note, PanelPaths.today())
            TileBuilders.Tile.Builder()
                .setResourcesVersion(RESOURCES_VERSION)
                // Re-render at least every 30 min so the "(old)" marker appears after midnight.
                .setFreshnessIntervalMillis(30 * 60 * 1000L)
                .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout(text)))
                .build()
        }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest,
    ): ListenableFuture<ResourceBuilders.Resources> = SuspendToFutureAdapter.launchFuture {
        ResourceBuilders.Resources.Builder().setVersion(RESOURCES_VERSION).build()
    }

    private fun layout(text: PanelFormat.TileText): LayoutElementBuilders.LayoutElement {
        val column = LayoutElementBuilders.Column.Builder()
            .setWidth(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
        PanelTileLayout.rows(text).forEachIndexed { i, row ->
            if (i > 0) column.addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(2f)).build())
            column.addContent(textElement(row))
        }
        val open = ModifiersBuilders.Clickable.Builder()
            .setId("open_panel")
            .setOnClick(
                ActionBuilders.LaunchAction.Builder()
                    .setAndroidActivity(
                        ActionBuilders.AndroidActivity.Builder()
                            .setPackageName(packageName)
                            .setClassName(PanelActivity::class.java.name)
                            .build(),
                    )
                    .build(),
            )
            .build()
        return LayoutElementBuilders.Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setClickable(open)
                    .setPadding(ModifiersBuilders.Padding.Builder().setStart(dp(22f)).setEnd(dp(22f)).build())
                    .build(),
            )
            .addContent(column.build())
            .build()
    }

    private fun textElement(row: PanelTileLayout.Row): LayoutElementBuilders.Text {
        val (size, color, bold) = when (row.style) {
            PanelTileLayout.Style.HEADER -> Triple(16f, 0xFFFFFFFF.toInt(), true)
            PanelTileLayout.Style.CRITICAL -> Triple(14f, 0xFFFFB4A9.toInt(), true)
            PanelTileLayout.Style.BODY -> Triple(13f, 0xFFE0E0E0.toInt(), false)
            PanelTileLayout.Style.MUTED -> Triple(13f, 0xFFA0A0A0.toInt(), false)
        }
        val font = LayoutElementBuilders.FontStyle.Builder()
            .setSize(sp(size))
            .setColor(argb(color))
            .setWeight(if (bold) LayoutElementBuilders.FONT_WEIGHT_BOLD else LayoutElementBuilders.FONT_WEIGHT_NORMAL)
            .build()
        return LayoutElementBuilders.Text.Builder()
            .setText(row.text)
            .setFontStyle(font)
            .setMaxLines(row.maxLines)
            .setMultilineAlignment(LayoutElementBuilders.TEXT_ALIGN_CENTER)
            .setOverflow(LayoutElementBuilders.TEXT_OVERFLOW_ELLIPSIZE)
            .build()
    }

    companion object {
        private const val RESOURCES_VERSION = "1"

        fun requestUpdate(context: Context) {
            getUpdater(context).requestUpdate(PanelTileService::class.java)
        }
    }
}
