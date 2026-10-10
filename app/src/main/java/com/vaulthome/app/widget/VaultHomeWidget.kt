package com.vaulthome.app.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.vaulthome.app.MainActivity
import com.vaulthome.app.R

/**
 * Home-screen search surface. Glance has no reliable text field on One UI,
 * so the row opens the search activity with the keyboard field focused.
 */
class VaultHomeWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            VaultHomeWidgetContent()
        }
    }
}

@Composable
private fun VaultHomeWidgetContent() {
    val context = LocalContext.current
    val openSearch = actionStartActivity(Intent(context, MainActivity::class.java))
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(Color(0xFF000000))
            .padding(10.dp)
            .clickable(openSearch),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = GlanceModifier
                .fillMaxWidth()
                .background(ImageProvider(R.drawable.search_frame))
                .cornerRadius(24.dp)
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .clickable(openSearch),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                provider = ImageProvider(R.drawable.ic_widget_search),
                contentDescription = "Search",
                modifier = GlanceModifier.size(20.dp),
            )
            Spacer(GlanceModifier.width(10.dp))
            Text(
                text = "Search the vault",
                style = TextStyle(
                    color = ColorProvider(Color(0xFF8A8A8A)),
                    fontSize = 16.sp,
                ),
            )
        }
    }
}
