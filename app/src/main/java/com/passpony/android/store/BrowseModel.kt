package com.passpony.android.store

import uniffi.pass_ffi.EntryRef

/**
 * Pure functions over the store index: search filtering and folder-level
 * grouping. Kept free of PassStore/CryptoBackend and Android framework
 * types so unit tests can construct EntryRef values directly, without
 * loading the native library. Mirrors AppModel.visibleEntries and
 * StoreListView's FolderLevelSections.computeLevel from PassPony iOS.
 */
object BrowseModel {

    /** Hidden entries never appear; a non-empty query filters everything else. */
    fun visibleEntries(all: List<EntryRef>, searchText: String): List<EntryRef> {
        val base = all.filter { !it.hidden }
        if (searchText.isEmpty()) return base
        val needle = searchText.lowercase()
        return base.filter { it.name.lowercase().contains(needle) }
    }

    data class Level(val folders: List<String>, val entries: List<String>)

    /**
     * Subfolders and direct entries at one level of the tree, from the
     * flat index: folders first, then entries, both sorted, mirroring
     * `pass ls`. `prefix` is the path so far, with a trailing slash (or
     * empty for the root level).
     */
    fun level(visible: List<EntryRef>, prefix: String): Level {
        val folders = sortedSetOf<String>()
        val entries = mutableListOf<String>()
        for (entry in visible) {
            if (!entry.name.startsWith(prefix)) continue
            val rest = entry.name.substring(prefix.length)
            val slash = rest.indexOf('/')
            if (slash >= 0) {
                folders.add(rest.substring(0, slash))
            } else {
                entries.add(rest)
            }
        }
        return Level(folders.toList(), entries.sorted())
    }

    /** Entry count inside one subfolder at this level, for the row's trailing count. */
    fun countInFolder(visible: List<EntryRef>, prefix: String, folder: String): Int {
        val folderPrefix = "$prefix$folder/"
        return visible.count { it.name.startsWith(folderPrefix) }
    }
}
