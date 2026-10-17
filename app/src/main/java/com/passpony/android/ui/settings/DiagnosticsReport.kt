package com.passpony.android.ui.settings

import java.net.URI

/**
 * What the Diagnostics screen shows and what Copy report puts on the
 * clipboard: the facts a support thread needs and nothing a support
 * thread should ever see. Pure, so the redaction is a plain JVM unit
 * test: no entry names, no key fingerprints, no path outside the
 * app-private store root, and of a git remote only its host, never the
 * userinfo the core warns may carry a token.
 *
 * The rendered text is English on purpose. It is written for the
 * developer reading a bug report, and the screen itself shows the same
 * values under localized labels.
 */
data class DiagnosticsReport(
    val appVersion: String,
    val versionCode: Int,
    val flavor: String,
    val coreVersion: String,
    val androidRelease: String,
    val sdkInt: Int,
    val device: String,
    val abi: String,
    val format: String,
    val storePath: String,
    val entryCount: Int,
    /** Null when the store has no git repository. */
    val git: GitInfo?,
    val autofillEnabled: Boolean,
    /** A BCP 47 tag, or empty for "follow the system". */
    val languageTag: String,
    val keys: KeyInfo,
    /** The SSH key's algorithm (`ssh-ed25519`), or null with none. Never the key. */
    val sshKey: String? = null,
) {
    data class GitInfo(
        val ahead: Int,
        val behind: Int,
        val dirty: Boolean,
        /** Already reduced to a host by [remoteHost]; null when no remote. */
        val remoteHost: String?,
        /** `https`, `ssh`, ...; null when no remote. Never the URL's user. */
        val scheme: String? = null,
        /** SSH remotes: whether the host's key is pinned. Never the key. */
        val hostPinned: Boolean? = null,
    )

    sealed class KeyInfo {
        /** pass: how many OpenPGP key files are imported. Never which. */
        data class Pass(val keyFiles: Int) : KeyInfo()

        /** passage: whether the age identities file exists. Never its content. */
        data class Passage(val identitiesPresent: Boolean) : KeyInfo()
    }

    fun render(): String {
        val lines = mutableListOf<String>()
        lines += "PassPony Android diagnostics"
        lines += "App: $appVersion ($versionCode, $flavor)"
        lines += "Core: $coreVersion"
        lines += "Android: $androidRelease (API $sdkInt)"
        lines += "Device: $device"
        lines += "ABI: $abi"
        lines += "Format: $format"
        lines += "Store path: $storePath"
        lines += "Entries: $entryCount"
        lines += "Git: " + when (git) {
            null -> "not configured"
            else -> buildString {
                append("ahead ${git.ahead}, behind ${git.behind}")
                if (git.dirty) append(", dirty")
                append(", remote ")
                git.scheme?.let { append(it).append(' ') }
                append(git.remoteHost ?: "none")
                git.hostPinned?.let { append(if (it) " (host pinned)" else " (host not pinned)") }
            }
        }
        lines += "Autofill: " + if (autofillEnabled) "on" else "off"
        lines += "Language: " + languageTag.ifEmpty { "system" }
        lines += "Keys: " + when (keys) {
            is KeyInfo.Pass -> "${keys.keyFiles} OpenPGP key files"
            is KeyInfo.Passage -> if (keys.identitiesPresent) "identities file present" else "no identities file"
        }
        lines += "SSH key: " + (sshKey ?: "none")
        return lines.joinToString("\n")
    }

    companion object {
        /**
         * The host of a git remote and nothing else. Handles the URL forms
         * the app writes (`https://user:token@host/you/store.git`) and the
         * scp-like form a cloned config may carry (`git@host:you/store.git`).
         * Anything unparsable becomes null rather than leaking through.
         */
        fun remoteHost(url: String?): String? {
            if (url.isNullOrBlank()) return null
            if (url.contains("://")) {
                val host = runCatching { URI(url).host }.getOrNull()
                return host?.takeIf { it.isNotBlank() }
            }
            val at = url.indexOf('@')
            val rest = if (at >= 0) url.substring(at + 1) else url
            val host = rest.takeWhile { it != ':' && it != '/' }
            return host.takeIf { it.isNotBlank() && !it.contains(' ') }
        }
    }
}
