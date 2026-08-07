package com.passpony.android.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import uniffi.pass_ffi.EntryRef

/**
 * EntryRef is a plain Kotlin data class (no native library involved in
 * constructing one directly), so these run as ordinary JVM unit tests.
 */
class BrowseModelTest {

    private val sampleEntries = listOf(
        EntryRef("web/github.com", false),
        EntryRef("web/gitlab.com", false),
        EntryRef("web/google.com", false),
        EntryRef("mail/fastmail.com", false),
        EntryRef("wifi/home", false),
        EntryRef(".hidden/secret", true),
    )

    @Test
    fun visibleEntries_dropsHiddenEntries() {
        val visible = BrowseModel.visibleEntries(sampleEntries, searchText = "")
        assertEquals(5, visible.size)
        assertFalse(visible.any { it.hidden })
    }

    @Test
    fun visibleEntries_emptySearchReturnsEverythingVisible() {
        val visible = BrowseModel.visibleEntries(sampleEntries, searchText = "")
        assertEquals(
            listOf("web/github.com", "web/gitlab.com", "web/google.com", "mail/fastmail.com", "wifi/home"),
            visible.map { it.name }
        )
    }

    @Test
    fun visibleEntries_searchIsCaseInsensitiveSubstring() {
        val visible = BrowseModel.visibleEntries(sampleEntries, searchText = "GIT")
        assertEquals(
            setOf("web/github.com", "web/gitlab.com"),
            visible.map { it.name }.toSet()
        )
    }

    @Test
    fun visibleEntries_searchNeverMatchesHiddenEntries() {
        val visible = BrowseModel.visibleEntries(sampleEntries, searchText = "secret")
        assertEquals(emptyList<EntryRef>(), visible)
    }

    @Test
    fun level_atRootGroupsFoldersBeforeSortedEntries() {
        val visible = BrowseModel.visibleEntries(sampleEntries, searchText = "")
        val level = BrowseModel.level(visible, prefix = "")
        assertEquals(listOf("mail", "web", "wifi"), level.folders)
        assertEquals(emptyList<String>(), level.entries)
    }

    @Test
    fun level_insideFolderListsLeafEntriesSorted() {
        val visible = BrowseModel.visibleEntries(sampleEntries, searchText = "")
        val level = BrowseModel.level(visible, prefix = "web/")
        assertEquals(emptyList<String>(), level.folders)
        assertEquals(listOf("github.com", "gitlab.com", "google.com"), level.entries)
    }

    @Test
    fun countInFolder_countsOnlyThatFoldersEntries() {
        val visible = BrowseModel.visibleEntries(sampleEntries, searchText = "")
        assertEquals(3, BrowseModel.countInFolder(visible, prefix = "", folder = "web"))
        assertEquals(1, BrowseModel.countInFolder(visible, prefix = "", folder = "wifi"))
    }
}
