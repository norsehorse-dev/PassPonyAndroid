package com.passpony.android.autofill

import org.junit.Assert.assertEquals
import org.junit.Test
import uniffi.pass_ffi.EntryRef

class AutofillPickerRankingTest {

    private val github = EntryRef("web/github.com", false)
    private val gitlab = EntryRef("web/gitlab.com", false)
    private val email = EntryRef("email/work", false)
    private val hiddenGithub = EntryRef("web/github-old", true)

    @Test
    fun noSearchNoHint_returnsEveryVisibleEntryUnfiltered() {
        val entries = listOf(github, gitlab, email)
        assertEquals(entries, AutofillPickerRanking.filtered(entries, serviceHint = "", search = ""))
    }

    @Test
    fun noSearchNoHint_dropsHiddenEntries() {
        val entries = listOf(github, hiddenGithub, email)
        assertEquals(listOf(github, email), AutofillPickerRanking.filtered(entries, serviceHint = "", search = ""))
    }

    @Test
    fun noSearch_withHint_ranksMatchesFirstButKeepsEveryEntry() {
        val entries = listOf(email, github, gitlab)
        val result = AutofillPickerRanking.filtered(entries, serviceHint = "github.com", search = "")
        assertEquals(listOf(github, email, gitlab), result)
    }

    @Test
    fun withSearch_filtersDownToOnlyMatches() {
        val entries = listOf(github, gitlab, email)
        val result = AutofillPickerRanking.filtered(entries, serviceHint = "irrelevant", search = "git")
        assertEquals(listOf(github, gitlab), result)
    }

    @Test
    fun withSearch_matchingNothing_returnsEmptyList() {
        val entries = listOf(github, gitlab, email)
        assertEquals(emptyList<EntryRef>(), AutofillPickerRanking.filtered(entries, serviceHint = "", search = "nonexistent"))
    }

    @Test
    fun search_isCaseInsensitive() {
        val entries = listOf(github)
        assertEquals(listOf(github), AutofillPickerRanking.filtered(entries, serviceHint = "", search = "GITHUB"))
    }

    @Test
    fun search_takesPriorityOverHint() {
        // A non-empty search always wins as the needle, regardless of
        // what the service hint would have ranked -- "email" here
        // matches only the email entry, not github despite the hint.
        val entries = listOf(github, gitlab, email)
        val result = AutofillPickerRanking.filtered(entries, serviceHint = "github.com", search = "email")
        assertEquals(listOf(email), result)
    }
}
