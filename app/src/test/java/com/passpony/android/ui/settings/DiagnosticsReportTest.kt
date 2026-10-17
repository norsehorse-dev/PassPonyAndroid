package com.passpony.android.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsReportTest {

    private fun report(
        git: DiagnosticsReport.GitInfo? = DiagnosticsReport.GitInfo(2, 0, false, "github.com"),
        keys: DiagnosticsReport.KeyInfo = DiagnosticsReport.KeyInfo.Pass(2),
        languageTag: String = "",
    ) = DiagnosticsReport(
        appVersion = "1.1.0",
        versionCode = 110,
        flavor = "foss",
        coreVersion = "1.1.0",
        androidRelease = "17",
        sdkInt = 37,
        device = "Google Pixel 9 Pro",
        abi = "arm64-v8a",
        format = "pass",
        storePath = "/data/user/0/com.passpony.android/files/pass-store",
        entryCount = 42,
        git = git,
        autofillEnabled = true,
        languageTag = languageTag,
        keys = keys,
    )

    @Test
    fun remoteHost_dropsUserinfoPathAndScheme() {
        assertEquals("github.com", DiagnosticsReport.remoteHost("https://kevin:ghp_secrettoken@github.com/you/store.git"))
        assertEquals("github.com", DiagnosticsReport.remoteHost("https://github.com/you/store.git"))
        assertEquals("git.example.org", DiagnosticsReport.remoteHost("git@git.example.org:you/store.git"))
        assertEquals("git.example.org", DiagnosticsReport.remoteHost("ssh://git@git.example.org:2222/you/store.git"))
        assertNull(DiagnosticsReport.remoteHost(null))
        assertNull(DiagnosticsReport.remoteHost(""))
        assertNull(DiagnosticsReport.remoteHost("   "))
        assertNull(DiagnosticsReport.remoteHost("not a url at all"))
    }

    @Test
    fun render_neverContainsRemoteCredentials() {
        val url = "https://kevin:ghp_secrettoken@github.com/you/store.git"
        val text = report(
            git = DiagnosticsReport.GitInfo(1, 3, true, DiagnosticsReport.remoteHost(url))
        ).render()
        assertFalse(text.contains("ghp_secrettoken"))
        assertFalse(text.contains("kevin:"))
        assertFalse(text.contains("/you/store"))
        assertTrue(text.contains("Git: ahead 1, behind 3, dirty, remote github.com"))
    }

    @Test
    fun render_coversEveryRow() {
        val text = report().render()
        val expected = """
            PassPony Android diagnostics
            App: 1.1.0 (110, foss)
            Core: 1.1.0
            Android: 17 (API 37)
            Device: Google Pixel 9 Pro
            ABI: arm64-v8a
            Format: pass
            Store path: /data/user/0/com.passpony.android/files/pass-store
            Entries: 42
            Git: ahead 2, behind 0, remote github.com
            Autofill: on
            Language: system
            Keys: 2 OpenPGP key files
            SSH key: none
        """.trimIndent()
        assertEquals(expected, text)
    }

    @Test
    fun render_variants() {
        assertTrue(report(git = null).render().contains("Git: not configured"))
        assertTrue(
            report(git = DiagnosticsReport.GitInfo(0, 0, false, null)).render()
                .contains("Git: ahead 0, behind 0, remote none")
        )
        assertTrue(report(languageTag = "es").render().contains("Language: es"))
        assertTrue(
            report(keys = DiagnosticsReport.KeyInfo.Passage(true)).render()
                .contains("Keys: identities file present")
        )
        assertTrue(
            report(keys = DiagnosticsReport.KeyInfo.Passage(false)).render()
                .contains("Keys: no identities file")
        )
    }

    @Test
    fun render_sshRemoteShowsSchemeAndPinningOnly() {
        val url = "ssh://deploy@git.example.org:2222/you/store.git"
        val text = report(
            git = DiagnosticsReport.GitInfo(0, 0, false, DiagnosticsReport.remoteHost(url), "ssh", true)
        ).copy(sshKey = "ssh-ed25519").render()
        assertTrue(text.contains("Git: ahead 0, behind 0, remote ssh git.example.org (host pinned)"))
        assertTrue(text.contains("SSH key: ssh-ed25519"))
        assertFalse(text.contains("deploy"))
        assertFalse(text.contains("2222"))
        assertFalse(text.contains("SHA256"))
        assertTrue(
            report(git = DiagnosticsReport.GitInfo(0, 0, false, "h", "ssh", false)).render()
                .contains("remote ssh h (host not pinned)")
        )
    }

    @Test
    fun render_hasNoEntryNamesOrFingerprints() {
        // The type cannot even hold them: nothing in the data class takes a
        // name list or a fingerprint. This pins that shape.
        val fields = DiagnosticsReport::class.java.declaredFields.map { it.name }
        assertFalse(fields.any { it.contains("name", ignoreCase = true) })
        assertFalse(fields.any { it.contains("fingerprint", ignoreCase = true) })
    }
}
