package com.passpony.core

/**
 * Placeholder until P02 wires in the generated UniFFI bindings, whose
 * `coreVersion()` free function becomes the real source of truth (it reads
 * straight from PassPonyCore's Cargo.toml version). This object exists only
 * so the :core module has a compilable source set from commit zero.
 */
object CoreVersion {
    const val PLACEHOLDER = "unbound (P02 wires the real pass-ffi bindings in)"
}
