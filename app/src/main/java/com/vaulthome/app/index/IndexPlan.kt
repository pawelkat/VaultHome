package com.vaulthome.app.index

data class IndexPlan(
    val changed: List<String>,
    val removed: List<String>,
)

fun planIndex(existingMtime: Map<String, Long>, scannedMtime: Map<String, Long>): IndexPlan {
    val changed = scannedMtime
        .filter { (path, mtime) -> existingMtime[path] != mtime }
        .keys
        .sorted()
    val removed = (existingMtime.keys - scannedMtime.keys).sorted()
    return IndexPlan(changed = changed, removed = removed)
}
