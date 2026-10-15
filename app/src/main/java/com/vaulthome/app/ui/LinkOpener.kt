package com.vaulthome.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.vaulthome.app.markdown.Links
import com.vaulthome.app.markdown.SafeLinks

/** Which app opens YouTube links. */
enum class YouTubeApp(val label: String, val pkg: String?) {
    ASK("Ask (system default)", null),
    NEWPIPE("NewPipe", Links.NEWPIPE),
    YOUTUBE("YouTube", Links.YOUTUBE);

    companion object {
        fun parse(v: String?): YouTubeApp = entries.firstOrNull { it.name == v } ?: ASK
    }
}

object LinkOpener {
    private const val PREFS = "vaulthome_ui"
    private const val KEY = "youtube_app"

    fun youTubeApp(context: Context): YouTubeApp =
        YouTubeApp.parse(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null))

    fun setYouTubeApp(context: Context, app: YouTubeApp) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, app.name).apply()
    }

    /** Opens a safe external URL; YouTube links go to the preferred app with fallback to the system chooser. */
    fun open(context: Context, raw: String) {
        if (!SafeLinks.isSafe(raw)) {
            Toast.makeText(context, "Link not opened (only https)", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = Uri.parse(Links.normalize(raw))
        val pkg = if (Links.isYouTube(raw)) youTubeApp(context).pkg else null
        if (pkg != null) {
            val targeted = Intent(Intent.ACTION_VIEW, uri).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try { context.startActivity(targeted); return } catch (_: ActivityNotFoundException) {
                val name = if (pkg == Links.NEWPIPE) "NewPipe" else "YouTube"
                Toast.makeText(context, "$name not installed, opening with default app", Toast.LENGTH_SHORT).show()
            }
        }
        val view = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(if (pkg != null) Intent.createChooser(view, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) else view)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "No app can open this link", Toast.LENGTH_SHORT).show()
        }
    }
}
