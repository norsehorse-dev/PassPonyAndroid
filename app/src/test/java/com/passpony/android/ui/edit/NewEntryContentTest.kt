package com.passpony.android.ui.edit

import org.junit.Assert.assertEquals
import org.junit.Test

class NewEntryContentTest {
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
}
