package com.example.simplemediadownloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApplicationVersionAndIdentityTest {

    @Test
    fun `verify application version code and name are bumped beyond 2_5_0`() {
        assertEquals("2.5.1", BuildConfig.VERSION_NAME)
        assertEquals(251, BuildConfig.VERSION_CODE)
        assertTrue("versionCode must be strictly greater than 250", BuildConfig.VERSION_CODE > 250)
    }

    @Test
    fun `verify application package identity is preserved for upgrade compatibility`() {
        assertEquals("com.example.simplemediadownloader", BuildConfig.APPLICATION_ID)
    }
}
