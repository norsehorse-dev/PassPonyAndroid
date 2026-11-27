package com.passpony.android.store

import android.net.Uri

/**
 * 3.0: where a format's store lives. Each format has app storage or one
 * folder, never both; the choice is saved per format in `passpony_prefs`
 * (see [StorePaths.location]).
 */
sealed class StoreLocation {
    /** The app's private files directory. The only place git sync works. */
    data object AppStorage : StoreLocation()

    /**
     * A folder the person picked, reached through the Storage Access
     * Framework by its tree URI. Opened in place: PassPony reads and
     * writes the files there and keeps no copy. A folder store has no
     * git; it syncs through whatever syncs the folder.
     */
    data class Folder(val treeUri: Uri) : StoreLocation()
}
