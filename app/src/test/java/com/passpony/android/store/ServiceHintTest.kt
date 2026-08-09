package com.passpony.android.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port of the cases iOS's AutofillIdentities.serviceHint(for:) covers. */
class ServiceHintTest {

    @Test
    fun webFolder_dottedLeaf_returnsTheDomain() {
        assertEquals("github.com", ServiceHint.forEntryName("web/github.com"))
    }

    @Test
    fun dotlessLeaf_returnsTheLeafName() {
        assertEquals("home", ServiceHint.forEntryName("wifi/home"))
    }

    @Test
    fun nestedFolders_deepestDottedComponentWins() {
        assertEquals("chase.com", ServiceHint.forEntryName("personal/banking/chase.com"))
    }

    @Test
    fun dotlessName_noFolders_returnsTheNameItself() {
        assertEquals("work", ServiceHint.forEntryName("email/work"))
    }

    @Test
    fun topLevelEntry_withDot_returnsItself() {
        assertEquals("github.com", ServiceHint.forEntryName("github.com"))
    }

    @Test
    fun topLevelEntry_withoutDot_returnsItself() {
        assertEquals("notes", ServiceHint.forEntryName("notes"))
    }

    @Test
    fun dottedAncestor_beatsADotlessLeaf() {
        // "deepest component containing a dot" scans leaf-to-root, so a
        // dotted ancestor still wins over a dotless leaf underneath it.
        assertEquals("example.org", ServiceHint.forEntryName("example.org/admin"))
    }

    @Test
    fun emptyName_returnsEmptyString() {
        assertEquals("", ServiceHint.forEntryName(""))
    }

    @Test
    fun doubleSlash_ignoresTheEmptySegment() {
        assertEquals("github.com", ServiceHint.forEntryName("web//github.com"))
    }

    @Test
    fun matchesDomain_exactMatch() {
        assertTrue(ServiceHint.matchesDomain("github.com", "github.com"))
    }

    @Test
    fun matchesDomain_subdomainOfTheHintMatches() {
        assertTrue(ServiceHint.matchesDomain("github.com", "sso.github.com"))
    }

    @Test
    fun matchesDomain_isCaseInsensitive() {
        assertTrue(ServiceHint.matchesDomain("GitHub.com", "github.COM"))
    }

    @Test
    fun matchesDomain_unrelatedDomainDoesNotMatch() {
        assertFalse(ServiceHint.matchesDomain("github.com", "gitlab.com"))
    }

    @Test
    fun matchesDomain_hintAsSuffixButNotSubdomain_doesNotMatch() {
        // "evilgithub.com" merely ends with "github.com" as a raw string
        // suffix, not as a subdomain (no separating dot) -- must not match.
        assertFalse(ServiceHint.matchesDomain("github.com", "evilgithub.com"))
    }

    @Test
    fun matchesDomain_emptyHintOrDomainNeverMatches() {
        assertFalse(ServiceHint.matchesDomain("", "github.com"))
        assertFalse(ServiceHint.matchesDomain("github.com", ""))
        assertFalse(ServiceHint.matchesDomain("", ""))
    }
}
