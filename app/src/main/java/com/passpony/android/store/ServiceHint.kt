package com.passpony.android.store

/**
 * Best domain guess from an entry name, port of iOS's
 * AutofillIdentities.serviceHint(for:). Used to rank/match entries
 * against an autofill request's webDomain and to pre-fill the picker's
 * search: the deepest path component containing a dot wins
 * (`web/github.com` -> `github.com`, scanning leaf-to-root so a
 * dotted ancestor still wins over a dotless leaf, e.g.
 * `example.org/admin` -> `example.org`); otherwise the leaf name.
 * The long tail of site-matching heuristics lives here eventually --
 * the picker fallback always works regardless of how good this guess is.
 */
object ServiceHint {
    fun forEntryName(name: String): String {
        val parts = name.split("/").filter { it.isNotEmpty() }
        return parts.asReversed().firstOrNull { it.contains(".") } ?: parts.lastOrNull() ?: name
    }

    /**
     * True when an entry's [hint] (its [forEntryName] result) matches an
     * autofill request's [domain] (its webDomain) -- suffix-tolerant so
     * a "github.com" entry also matches a "sso.github.com" request:
     * exact match, or [domain] ending with "." + [hint]. Package-name
     * requests never reach this (v1 picker-only path); both empty
     * strings never match.
     */
    fun matchesDomain(hint: String, domain: String): Boolean {
        if (hint.isEmpty() || domain.isEmpty()) return false
        return hint.equals(domain, ignoreCase = true) || domain.endsWith(".$hint", ignoreCase = true)
    }
}
