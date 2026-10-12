package com.vaulthome.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.vaulthome.app.VaultHomeApp
import com.vaulthome.app.markdown.Images
import com.vaulthome.app.markdown.MdBlock
import com.vaulthome.app.markdown.SafeLinks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

private const val MAX_DOWNLOAD = 15_000_000

/** Loads a vault image (SAF, filename match anywhere) or an https image, downsampled to the slot width. */
@Composable
fun VaultImage(img: MdBlock.Image) {
    val context = LocalContext.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val slotDp = img.width?.dp?.coerceAtMost(maxWidth) ?: maxWidth
        val reqPx = with(LocalDensity.current) { slotDp.roundToPx() }
        var bmp by remember(img.target) { mutableStateOf<Bitmap?>(null) }
        var failed by remember(img.target) { mutableStateOf<String?>(null) }
        LaunchedEffect(img.target, reqPx) {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes: ByteArray? = if (img.target.startsWith("http", true)) {
                        if (!SafeLinks.isSafe(img.target)) null else download(img.target)
                    } else {
                        val reader = (context.applicationContext as VaultHomeApp).graph.reader
                        val files = reader.scanImages()
                        val path = Images.resolve(img.target, files.map { it.relativePath })
                        files.firstOrNull { it.relativePath == path }?.let { f ->
                            context.contentResolver.openInputStream(f.uri)?.use { it.readBytes() }
                        }
                    }
                    bytes?.let { decode(it, reqPx) }
                }.getOrNull()
            }
            if (result != null) bmp = result else failed = img.target
        }
        val b = bmp
        if (b != null) {
            Image(
                b.asImageBitmap(), contentDescription = img.alt, contentScale = ContentScale.FillWidth,
                modifier = Modifier.width(slotDp.coerceAtMost(with(LocalDensity.current) { b.width.toDp() }.coerceAtLeast(slotDp.coerceAtMost(48.dp)))),
            )
        } else {
            Box(Modifier.widthIn(max = slotDp).background(Color(0xFF1E1E1E)).padding(10.dp)) {
                Text(
                    (if (failed != null) "\uD83D\uDDBC Image not found: " else "\uD83D\uDDBC Loading ") + img.alt,
                    style = MaterialTheme.typography.bodySmall, color = Color(0xFF9A9A9A),
                )
            }
        }
    }
}

private fun download(url: String): ByteArray? {
    val c = URL(url).openConnection() as HttpURLConnection
    c.connectTimeout = 10_000; c.readTimeout = 15_000; c.instanceFollowRedirects = true
    return try {
        if (c.responseCode !in 200..299 || c.url.protocol != "https") return null
        c.inputStream.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buf); if (n < 0) break
                out.write(buf, 0, n); if (out.size() > MAX_DOWNLOAD) return null
            }
            out.toByteArray()
        }
    } finally { c.disconnect() }
}

private fun decode(bytes: ByteArray, reqWidth: Int): Bitmap? {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
    if (o.outWidth <= 0) return null
    val opts = BitmapFactory.Options().apply { inSampleSize = Images.sampleSize(o.outWidth, o.outHeight, reqWidth) }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
}
