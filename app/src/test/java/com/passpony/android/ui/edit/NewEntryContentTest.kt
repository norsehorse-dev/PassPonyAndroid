package com.passpony.android.ui.edit

import org.junit.Assert.assertEquals
import org.junit.Test

class NewEntryContentTest {

    private val uri = "otpauth://totp/Example:kevin?secret=JBSWY3DPEHPK3PXP&issuer=Example"

    @Test
    fun assemble_passwordOnly() {
        val content = NewEntryContent.assemble("hunter2", username = "", url = "")
        assertEquals("hunter2\n", String(content, Charsets.UTF_8))
    }

    @Test
    fun assemble_appendsUsernameThenUrlWhenBothPresent() {
        val content = NewEntryContent.assemble("hunter2", username = "kevin", url = "example.com")
        assertEquals("hunter2\nusername: kevin\nurl: example.com\n", String(content, Charsets.UTF_8))
    }

    @Test
    fun assemble_usernameOnlyOmitsUrlLine() {
        val content = NewEntryContent.assemble("hunter2", username = "kevin", url = "")
        assertEquals("hunter2\nusername: kevin\n", String(content, Charsets.UTF_8))
    }

    @Test
    fun assemble_urlOnlyOmitsUsernameLine() {
        val content = NewEntryContent.assemble("hunter2", username = "", url = "example.com")
        assertEquals("hunter2\nurl: example.com\n", String(content, Charsets.UTF_8))
    }

    @Test
    fun assemble_otpauthGoesLast() {
        val content = NewEntryContent.assemble("hunter2", username = "kevin", url = "example.com", otpauth = uri)
        assertEquals("hunter2\nusername: kevin\nurl: example.com\n$uri\n", String(content, Charsets.UTF_8))
        val bare = NewEntryContent.assemble("hunter2", username = "", url = "", otpauth = uri)
        assertEquals("hunter2\n$uri\n", String(bare, Charsets.UTF_8))
    }

    @Test
    fun assemble_noPasswordAndACodeIsAnOtpOnlyEntry() {
        // The shape `pass otp insert` creates: the URI is line 1.
        val content = NewEntryContent.assemble("", username = "", url = "", otpauth = uri)
        assertEquals("$uri\n", String(content, Charsets.UTF_8))
        val withFields = NewEntryContent.assemble("", username = "kevin", url = "", otpauth = uri)
        assertEquals("$uri\nusername: kevin\n", String(withFields, Charsets.UTF_8))
    }

    @Test
    fun assemble_nullOtpauthChangesNothing() {
        val content = NewEntryContent.assemble("hunter2", username = "kevin", url = "", otpauth = null)
        assertEquals("hunter2\nusername: kevin\n", String(content, Charsets.UTF_8))
    }
}
