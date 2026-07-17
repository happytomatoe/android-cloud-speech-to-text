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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Verifies [WhisperTranscriber]: per-backend request building (auth scheme +
 * body shape) and response parsing, plus the error / attachToEnd / trailing-space
 * branches. The transcription HTTP endpoint is stubbed with MockWebServer so no
 * real network call escapes. All config is written into the isolated test
 * Context's DataStore.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class WhisperTranscriberTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private lateinit var server: MockWebServer
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // Write backend config into the test Context's DataStore, pointing ENDPOINT at MockWebServer.
    private fun writeConfig(
        backend: String,
        apiKey: String,
        model: String,
        lang: String,
        trailing: Boolean
    ) {
        runBlocking(Dispatchers.IO) {
            ctx.dataStore.edit { prefs ->
                prefs[SPEECH_TO_TEXT_BACKEND] = backend
                prefs[ENDPOINT] = server.url("/").toString()
                prefs[API_KEY] = apiKey
                prefs[MODEL] = model
                prefs[LANGUAGE_CODE] = lang
                prefs[ADD_TRAILING_SPACE] = trailing
            }
        }
    }

    private fun fakeWav(): String {
        val f = File.createTempFile("test-speech", ".wav")
        f.writeBytes(ByteArray(1024) { it.toByte() })
        f.deleteOnExit()
        return f.absolutePath
    }

    // Waits for the success callback (the transcription text); ignores the error callback.
    private fun transcribeExpectingResult(attachToEnd: String): String? {
        var result: String? = null
        val latch = CountDownLatch(1)
        WhisperTranscriber().startAsync(
            ctx, fakeWav(), "audio/wav", attachToEnd,
            { result = it; latch.countDown() },
            { /* error path is irrelevant for success tests */ }
        )
        latch.await(30, TimeUnit.SECONDS)
        return result
    }

    // Waits for the error callback; ignores the (null) success callback.
    private fun transcribeExpectingError(attachToEnd: String): String? {
        var error: String? = null
        val latch = CountDownLatch(1)
        WhisperTranscriber().startAsync(
            ctx, fakeWav(), "audio/wav", attachToEnd,
            { /* success path irrelevant for error tests */ },
            { error = it; latch.countDown() }
        )
        latch.await(30, TimeUnit.SECONDS)
        return error
    }

    private fun voxtral() = ctx.getString(R.string.settings_option_voxtral)
    private fun elevenlabs() = ctx.getString(R.string.settings_option_elevenlabs)
    private fun deepgram() = ctx.getString(R.string.settings_option_deepgram)
    private fun groq() = ctx.getString(R.string.settings_option_groq)
    private fun sixtyDb() = ctx.getString(R.string.settings_option_60db)

    // ── Response parsing per backend ────────────────────────────

    @Test
    fun voxtral_parses_text_field() {
        server.enqueue(MockResponse().setBody("""{"text":"hello world"}"""))
        writeConfig(voxtral(), "k", "m", "auto", false)
        val result = transcribeExpectingResult("")
        assertNull("success callback should not carry an error message", null)
        assertEquals("hello world", result)
        val req = server.takeRequest()
        assertTrue(req.headers["Authorization"]!!.startsWith("Bearer "))
        assertTrue("multipart body should contain the audio file part", req.body.readUtf8().contains("file"))
    }

    @Test
    fun elevenlabs_parses_text_field() {
        server.enqueue(MockResponse().setBody("""{"text":"hello world"}"""))
        writeConfig(elevenlabs(), "k", "scribe_v1", "auto", false)
        assertEquals("hello world", transcribeExpectingResult(""))
        val req = server.takeRequest()
        assertEquals("k", req.headers["xi-api-key"])
        assertTrue(req.body.readUtf8().contains("file"))
    }

    @Test
    fun deepgram_parses_transcript_and_uses_token_auth() {
        server.enqueue(
            MockResponse().setBody(
                """{"results":{"channels":[{"alternatives":[{"transcript":"hi"}]}]}}"""
            )
        )
        writeConfig(deepgram(), "k", "nova-3", "en", false)
        assertEquals("hi", transcribeExpectingResult(""))
        val req = server.takeRequest()
        assertEquals("Token k", req.headers["Authorization"])
        assertTrue("deepgram request path must carry the model query param", req.path!!.contains("model=nova-3"))
    }

    @Test
    fun groq_returns_raw_text() {
        server.enqueue(MockResponse().setBody("hello world"))
        writeConfig(groq(), "k", "whisper-large-v3-turbo", "auto", false)
        assertEquals("hello world", transcribeExpectingResult(""))
        val req = server.takeRequest()
        assertTrue(req.headers["Authorization"]!!.startsWith("Bearer "))
        assertTrue(req.body.readUtf8().contains("file"))
    }

    @Test
    fun sixtyDb_parses_data_text_field() {
        server.enqueue(MockResponse().setBody("""{"data":{"text":"hello world"}}"""))
        writeConfig(sixtyDb(), "k", "60db-stt-v01", "auto", false)
        assertEquals("hello world", transcribeExpectingResult(""))
        val req = server.takeRequest()
        assertTrue(req.headers["Authorization"]!!.startsWith("Bearer "))
        assertTrue(req.body.readUtf8().contains("file"))
    }

    // ── Error branches ──────────────────────────────────────────

    @Test
    fun empty_api_key_throws() {
        // Voxtral with an empty API key must raise the "API Key is not set" error
        // before any network request is made.
        writeConfig(voxtral(), "", "m", "auto", false)
        val error = transcribeExpectingError("")
        assertNotNull("error callback should fire for empty API key", error)
        assertTrue("error should mention the API key ($error)", error!!.contains("API Key"))
        // No request should have reached the server.
        assertEquals("no network request expected when API key is blank", 0, server.requestCount)
    }

    @Test
    fun http_error_invokes_error_callback() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("unauthorized"))
        writeConfig(voxtral(), "k", "m", "auto", false)
        val error = transcribeExpectingError("")
        assertNotNull("error callback should fire on HTTP 401", error)
        assertTrue("error should contain the server body ($error)", error!!.contains("unauthorized"))
    }

    // ── attachToEnd / trailing space ────────────────────────────

    @Test
    fun attachToEnd_appends_newline() {
        server.enqueue(MockResponse().setBody("""{"text":"hello world"}"""))
        writeConfig(voxtral(), "k", "m", "auto", false)
        assertEquals("hello world\r\n", transcribeExpectingResult("\r\n"))
    }

    @Test
    fun addTrailingSpace_appends_space() {
        server.enqueue(MockResponse().setBody("""{"text":"hello world"}"""))
        writeConfig(voxtral(), "k", "m", "auto", true)
        assertEquals("hello world ", transcribeExpectingResult(""))
    }
}
