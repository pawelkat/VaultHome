package com.vaulthome.app.vault

import android.content.Context
import android.net.Uri

class VaultStore(context: Context) {
    private val prefs = context.getSharedPreferences("vaulthome", Context.MODE_PRIVATE)
    private val resolver = context.contentResolver

    fun treeUri(): Uri? = prefs.getString(KEY_TREE, null)?.let(Uri::parse)

    fun hasReadAccess(): Boolean {
        val uri = treeUri() ?: return false
        return resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
    }

    fun setTreeUri(uri: Uri) {
        prefs.edit().putString(KEY_TREE, uri.toString()).apply()
    }

    private companion object {
        const val KEY_TREE = "tree_uri"
    }
}
