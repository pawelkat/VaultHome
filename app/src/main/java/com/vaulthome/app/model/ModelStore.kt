package com.vaulthome.app.model

import android.content.Context
import com.vaulthome.app.index.SearchConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class ModelStore(context: Context) {
    private val modelsDir = File(context.filesDir, "models").apply { mkdirs() }
    private val litertCache = File(context.cacheDir, "litertlm").apply { mkdirs() }
    private val mutex = Mutex()

    fun modelFile(): File = File(modelsDir, SearchConfig.MODEL_FILE)

    fun cacheDir(): File = litertCache

    fun isReady(): Boolean = modelFile().let { it.isFile && it.length() >= SearchConfig.MIN_MODEL_BYTES }

    suspend fun ensure(onProgress: (bytes: Long, total: Long) -> Unit): File = mutex.withLock {
        val target = modelFile()
        if (target.isFile && target.length() >= SearchConfig.MIN_MODEL_BYTES) {
            onProgress(target.length(), target.length())
            return target
        }
        withContext(Dispatchers.IO) {
            val partial = File(target.parentFile, "${target.name}.partial")
            if (partial.exists()) partial.delete()
            val connection = (URL(SearchConfig.MODEL_URL).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 30_000
                readTimeout = 60_000
                setRequestProperty("User-Agent", "VaultHome/0.1")
            }
            try {
                connection.connect()
                val code = connection.responseCode
                if (code !in 200..299) {
                    error("Model download failed ($code)")
                }
                val total = connection.contentLengthLong.coerceAtLeast(0L)
                connection.inputStream.use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var readTotal = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            readTotal += count
                            onProgress(readTotal, total)
                        }
                    }
                }
            } finally {
                connection.disconnect()
            }
            val downloaded = partial.length()
            if (downloaded < SearchConfig.MIN_MODEL_BYTES) {
                partial.delete()
                error("Model download was incomplete ($downloaded bytes)")
            }
            if (target.exists()) target.delete()
            if (!partial.renameTo(target)) {
                partial.copyTo(target, overwrite = true)
                partial.delete()
            }
            target
        }
    }
}
