package com.passpony.android.ui.otp

import uniffi.pass_ffi.MigrationAccount
import uniffi.pass_ffi.MigrationBatch
import uniffi.pass_ffi.MigrationSkipped

/**
 * Collects the QR codes of one Google Authenticator export. An export of
 * many accounts spans several codes that share a batch id, each with its
 * own index out of the batch size. Plain logic, no Android types, so the
 * batch rules are unit-tested on the JVM.
 */
class MigrationCollector {
    private val parts = sortedMapOf<Int, MigrationBatch>()
    private var batchId: Long? = null
    private var size: Int = 0

    sealed class Outcome {
        /** A new code from this export; [scanned] of [total] so far. */
        data class Added(val scanned: Int, val total: Int) : Outcome()
        /** This code was already scanned. */
        data class Duplicate(val scanned: Int, val total: Int) : Outcome()
        /** A code from a different export than the ones already scanned. */
        data object OtherExport : Outcome()
    }

    val scanned: Int get() = parts.size
    val total: Int get() = size
    val complete: Boolean get() = size > 0 && parts.size >= size

    /** One flag per code of the export, true where that code is scanned. */
    fun progress(): List<Boolean> = (0 until size).map { parts.containsKey(it) }

    fun add(batch: MigrationBatch): Outcome {
        val id = batchId
        if (id != null && id != batch.batchId) return Outcome.OtherExport
        batchId = batch.batchId
        size = maxOf(size, batch.batchSize.toInt(), 1)
        val index = batch.batchIndex.toInt()
        if (parts.containsKey(index)) return Outcome.Duplicate(parts.size, size)
        parts[index] = batch
        return Outcome.Added(parts.size, size)
    }

    fun accounts(): List<MigrationAccount> = parts.values.flatMap { it.accounts }

    fun skipped(): List<MigrationSkipped> = parts.values.flatMap { it.skipped }

    fun clear() {
        parts.clear()
        batchId = null
        size = 0
    }
}

/**
 * Names chosen for imported accounts: blank, used twice in this import, or
 * already an entry in the store all block the import of that row.
 */
object ImportNames {
    enum class Problem { BLANK, DUPLICATE, EXISTS }

    fun problems(names: List<String>, exists: (String) -> Boolean): List<Problem?> {
        val trimmed = names.map { it.trim().trim('/') }
        return trimmed.mapIndexed { i, name ->
            when {
                name.isEmpty() -> Problem.BLANK
                trimmed.count { it == name } > 1 && trimmed.indexOf(name) != i -> Problem.DUPLICATE
                exists(name) -> Problem.EXISTS
                else -> null
            }
        }
    }
}
