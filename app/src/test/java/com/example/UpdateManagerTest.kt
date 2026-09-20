package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UpdateManagerTest {

    @Test
    fun `test app current version retrieval`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val updateManager = UpdateManager(context)

        val code = updateManager.getCurrentVersionCode()
        val name = updateManager.getCurrentVersionName()

        assertTrue(code >= 1)
        assertTrue(name.isNotEmpty())
    }

    @Test
    fun `test default cloud server url format`() {
        assertEquals("https://watchroom-server.onrender.com", AppConfig.DEFAULT_CLOUD_SERVER_URL)
        assertTrue(AppConfig.DEFAULT_CLOUD_SERVER_URL.startsWith("https://"))
    }
}
