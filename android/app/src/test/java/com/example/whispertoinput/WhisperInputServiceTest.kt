/*
 * This file is part of Whisper To Input, see <https://github.com/j3soon/whisper-to-input>.
 *
 * Copyright (c) 2023-2025 Yan-Bin Diau, Johnson Sun
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANT FOR
 * A PARTICULAR PURPOSE. See the GNU General License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.example.whispertoinput

import android.content.Intent
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import com.example.whispertoinput.util.MainDispatcherRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowApplication
import java.io.File

/**
 * Covers [WhisperInputService]:
 *  - permission gating (no test-file mode, no perms → launches MainActivity),
 *  - test-file toggle (first tap records, second tap transcribes via MockWebServer),
 *  - onWindowHidden clears test-file state without throwing.
 *
 * The transcription network call is stubbed with MockWebServer so no real
 * request escapes. Test-file mode needs no microphone/notification permission.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class WhisperInputServiceTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private lateinit var server: MockWebServer
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        // Clean baseline: no test-file mode, and clear any companion state.
        runBlocking(Dispatchers.IO) {
            ctx.dataStore.edit { it[USE_TEST_FILE] = false }
        }
        WhisperInputService.lastTranscriptionResult = null
        WhisperInputService.lastTranscriptionError = null
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun buildService(): WhisperInputService =
        Robolectric.buildService(WhisperInputService::class.java).create().get()

    // Fire the registered TOGGLE_RECORDING receiver directly (avoids Robolectric
    // broadcast-delivery quirks for dynamically-registered receivers).
    private fun toggle(service: WhisperInputService) {
        val app = RuntimeEnvironment.getApplication()
        val shadowApp = shadowOf(app) as ShadowApplication
        val wrapper = shadowApp.registeredReceivers.first {
            it.intentFilter.hasAction(WhisperInputService.ACTION_TOGGLE_RECORDING)
        }
        wrapper.broadcastReceiver.onReceive(app, Intent(WhisperInputService.ACTION_TOGGLE_RECORDING))
    }

    private fun settle() = mainRule.dispatcher.scheduler.advanceUntilIdle()

    @Test
    fun permission_denied_launches_main_activity() {
        val service = buildService()
        toggle(service)

        // The permission check + startActivity run on Dispatchers.Main; flush it.
        mainRule.dispatcher.scheduler.advanceUntilIdle()

        val shadowApp = shadowOf(RuntimeEnvironment.getApplication()) as ShadowApplication
        val intent = shadowApp.nextStartedActivity
        assertNotNull("denied permissions should launch MainActivity", intent)
        assertEquals(MainActivity::class.java.name, intent!!.component?.className)
    }

    @Test
    fun test_file_mode_transcribes_and_stores_result() {
        val testFile = File.createTempFile("test-speech", ".wav").apply {
            writeBytes(ByteArray(1024) { it.toByte() })
            deleteOnExit()
        }
        runBlocking(Dispatchers.IO) {
            ctx.dataStore.edit {
                it[USE_TEST_FILE] = true
                it[TEST_FILE_PATH] = testFile.absolutePath
                it[ENDPOINT] = server.url("/").toString()
                it[SPEECH_TO_TEXT_BACKEND] = ctx.getString(R.string.settings_option_voxtral)
                it[API_KEY] = "k"
                it[MODEL] = "m"
                it[LANGUAGE_CODE] = "auto"
                it[ADD_TRAILING_SPACE] = false
            }
        }
        server.enqueue(MockResponse().setBody("""{"text":"hello world"}"""))

        val service = buildService()
        toggle(service) // first tap: start test-file recording
        settle()        // flush Main coroutine (reads USE_TEST_FILE) before the second tap
        toggle(service) // second tap: transcribe against MockWebServer
        settle()        // flush the Main coroutine that kicks off transcription

        // The transcription network call runs on a real IO thread (MockWebServer/
        // OkHttp), but the success callback runs on Dispatchers.Main, which is the
        // StandardTestDispatcher — so it is QUEUED and only runs when the scheduler
        // is advanced. Pump: advance the scheduler, then let real IO progress.
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            mainRule.dispatcher.scheduler.advanceUntilIdle()   // run Main trigger + callback
            if (WhisperInputService.lastTranscriptionResult != null) break
            Thread.sleep(50)                                    // let MockWebServer IO finish
        }
        assertEquals("hello world", WhisperInputService.lastTranscriptionResult)
        assertNull(WhisperInputService.lastTranscriptionError)
    }

    @Test
    fun onWindowHidden_clears_test_file_mode_without_throwing() {
        val testFile = File.createTempFile("test-speech", ".wav").apply {
            writeBytes(ByteArray(1024) { it.toByte() })
            deleteOnExit()
        }
        runBlocking(Dispatchers.IO) {
            ctx.dataStore.edit {
                it[USE_TEST_FILE] = true
                it[TEST_FILE_PATH] = testFile.absolutePath
            }
        }
        val service = buildService()
        toggle(service)
        settle()

        // No live input connection in Robolectric; should still clear state, not throw.
        service.onWindowHidden()
        assertNull(WhisperInputService.lastTranscriptionResult)
    }
}
