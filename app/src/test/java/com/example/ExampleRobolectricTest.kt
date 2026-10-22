package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("Redmi Buds Audio Fixer", appName)
  }

  @Test
  fun `shell executor executes placeholder command`() = runBlocking {
    val result = ShellExecutor.executeAudioRouting()
    assertNotNull(result)
    assertEquals("echo 'audio_trigger'", result.command)
    assertEquals(0, result.exitCode)
    assertTrue(result.output.contains("audio_trigger"))
    assertTrue(result.isSuccess)
  }

  @Test
  fun `audio routing service companion methods work safely`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    AudioRoutingService.startService(context, AudioOutputMode.AAC_A2DP)
    val state = AudioRoutingService.serviceState.value
    assertNotNull(state)
    AudioRoutingService.switchMode(context, AudioOutputMode.SCO_CALL)
    AudioRoutingService.stopService(context)
  }

  @Test
  fun `shizuku manager status check executes safely`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val status = ShizukuManager.checkStatus(context)
    assertNotNull(status)
  }
}
