package com.passpony.android.store

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgeHeaderTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun ageFile(vararg stanzas: String): ByteArray {
        val header = buildString {
            append("age-encryption.org/v1\n")
            for (s in stanzas) append("-> ").append(s).append(" AAAA\nBBBB\n")
            append("--- MACMACMAC\n")
        }
        return header.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0, 10, 45, 62, 32, 88, 10)
    }

    @Test
    fun readsStanzaTypesAndStopsAtTheMac() {
        assertEquals(listOf("X25519", "X25519"), AgeHeader.stanzaTypes(ageFile("X25519", "X25519")))
        assertEquals(listOf("mlkem768x25519"), AgeHeader.stanzaTypes(ageFile("mlkem768x25519")))
        assertTrue(AgeHeader.stanzaTypes("-----BEGIN AGE ENCRYPTED FILE-----\n".toByteArray()).isEmpty())
        assertTrue(AgeHeader.stanzaTypes(ByteArray(0)).isEmpty())
    }

    @Test
    fun tellsPostQuantumFromClassic() {
        assertTrue(AgeHeader.isPostQuantum(ageFile("mlkem768x25519")))
        assertTrue(AgeHeader.isPostQuantum(ageFile("mlkem768p256tag", "mlkem768x25519")))
        assertFalse(AgeHeader.isPostQuantum(ageFile("X25519")))
        assertFalse(AgeHeader.isPostQuantum(ageFile("p256tag", "X25519")))
    }

    @Test
    fun sniffsAStoreFromItsFirstEntry() {
        val root = tmp.newFolder("store")
        assertNull(AgeHeader.sniffStore(root))
        File(root, ".git").mkdirs()
        File(root, ".git/trap.age").writeBytes(ageFile("mlkem768x25519"))
        assertNull(AgeHeader.sniffStore(root))
        File(root, "web").mkdirs()
        File(root, "web/site.age").writeBytes(ageFile("X25519"))
        assertEquals(false, AgeHeader.sniffStore(root))
        File(root, "web/site.age").writeBytes(ageFile("mlkem768x25519"))
        assertEquals(true, AgeHeader.sniffStore(root))
    }
}
