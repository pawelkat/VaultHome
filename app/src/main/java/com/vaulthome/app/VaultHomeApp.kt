package com.vaulthome.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.vaulthome.app.index.IndexStatusStore
import com.vaulthome.app.index.IndexWorker
import com.vaulthome.app.index.Indexer
import com.vaulthome.app.index.VaultIndexDatabase
import com.vaulthome.app.model.Embedder
import com.vaulthome.app.model.ModelStore
import com.vaulthome.app.search.Searcher
import com.vaulthome.app.vault.VaultReader
import com.vaulthome.app.vault.VaultStore

class VaultHomeApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                IndexWorker.CHANNEL_ID,
                "Vault index",
                NotificationManager.IMPORTANCE_LOW,
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        graph = AppGraph(this)
    }
}

class AppGraph(app: Application) {
    val store = VaultStore(app)
    val reader = VaultReader(app, store)
    val models = ModelStore(app)
    val embedder = Embedder(models)
    val database = VaultIndexDatabase.create(app)
    val tags = com.vaulthome.app.index.TagIndex(java.io.File(app.filesDir, "tags.tsv"))
    val status = IndexStatusStore(app)
    val indexer = Indexer(
        store = store,
        reader = reader,
        dao = database.chunks(),
        models = models,
        embedder = embedder,
        status = status,
        tags = tags,
    )
    val searcher = Searcher(database.chunks(), embedder)
}
