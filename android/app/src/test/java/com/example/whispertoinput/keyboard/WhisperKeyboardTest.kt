package com.example.whispertoinput.keyboard

import android.view.LayoutInflater
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.example.whispertoinput.R
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment

/**
 * Locks down the keyboard button / state-machine logic that caused the reported
 * regression. Drives the REAL views returned by [WhisperKeyboard.setup] via
 * View.performClick() and captures the 11 state callbacks as lambdas — no
 * production-code changes required.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WhisperKeyboardTest {

    private lateinit var kb: WhisperKeyboard
    private lateinit var root: View

    private var started = 0
    private var cancelled = 0
    private val transcribed = mutableListOf<String>()
    private var backspaces = 0
    private var enters = 0
    private var spaceBars = 0
    private var switchedIme = 0
    private var openedSettings = 0

    @Before
    fun setUp() {
        val pair = buildKeyboard(shouldShowRetry = false)
        kb = pair.first
        root = pair.second
    }

    private fun buildKeyboard(shouldShowRetry: Boolean): Pair<WhisperKeyboard, View> {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val inflater = LayoutInflater.from(ctx)
        val keyboard = WhisperKeyboard()
        val view = keyboard.setup(
            inflater,
            shouldOfferImeSwitch = true,
            onStartRecording = { started++ },
            onCancelRecording = { cancelled++ },
            onStartTranscribing = { attachToEnd -> transcribed.add(attachToEnd) },
            onCancelTranscribing = { cancelled++ },
            onButtonBackspace = { backspaces++ },
            onEnter = { enters++ },
            onSpaceBar = { spaceBars++ },
            onSwitchIme = { switchedIme++ },
            onOpenSettings = { openedSettings++ },
            shouldShowRetry = { shouldShowRetry },
        )
        return keyboard to view
    }

    private fun click(@androidx.annotation.IdRes id: Int) =
        root.findViewById<View>(id).performClick()

    // ── Mic button ──────────────────────────────────────────────
    @Test fun mic_idle_starts_recording() {
        click(R.id.btn_mic)
        assertEquals(1, started)
    }

    @Test fun mic_recording_transcribes_empty() {
        click(R.id.btn_mic) // Idle -> Recording
        click(R.id.btn_mic) // Recording -> Transcribing("")
        assertEquals(listOf(""), transcribed)
    }

    @Test fun mic_transcribing_is_noop() {
        click(R.id.btn_mic)
        click(R.id.btn_mic)
        click(R.id.btn_mic) // Transcribing -> must be ignored
        assertEquals(1, transcribed.size)
    }

    // ── Cancel button ───────────────────────────────────────────
    @Test fun cancel_recording_cancels() {
        click(R.id.btn_mic)
        click(R.id.btn_cancel)
        assertEquals(1, cancelled)
    }

    @Test fun cancel_transcribing_cancels() {
        click(R.id.btn_mic)
        click(R.id.btn_mic)
        click(R.id.btn_cancel)
        assertEquals(1, cancelled)
    }

    @Test fun cancel_idle_noop() {
        click(R.id.btn_cancel)
        assertEquals(0, cancelled)
    }

    // ── Retry button ────────────────────────────────────────────
    @Test fun retry_idle_transcribes_empty() {
        click(R.id.btn_retry)
        assertEquals(listOf(""), transcribed)
    }

    @Test fun retry_recording_noop() {
        click(R.id.btn_mic)
        click(R.id.btn_retry)
        assertEquals(0, transcribed.size)
    }

    // ── Enter button ────────────────────────────────────────────
    @Test fun enter_recording_sends_newline() {
        click(R.id.btn_mic)
        click(R.id.btn_enter)
        assertEquals(listOf("\r\n"), transcribed)
    }

    @Test fun enter_idle_invokes_onEnter() {
        click(R.id.btn_enter)
        assertEquals(1, enters)
    }

    // ── Space bar ───────────────────────────────────────────────
    @Test fun space_recording_sends_space() {
        click(R.id.btn_mic)
        click(R.id.btn_space_bar)
        assertEquals(listOf(" "), transcribed)
    }

    @Test fun space_idle_invokes_onSpaceBar() {
        click(R.id.btn_space_bar)
        assertEquals(1, spaceBars)
    }

    // ── Stateless buttons ───────────────────────────────────────
    @Test fun settings_invokes_callback() {
        click(R.id.btn_settings)
        assertEquals(1, openedSettings)
    }

    @Test fun backspace_invokes_callback() {
        click(R.id.btn_backspace)
        assertEquals(1, backspaces)
    }

    @Test fun previous_ime_invokes_callback() {
        click(R.id.btn_previous_ime)
        assertEquals(1, switchedIme)
    }

    // ── Public state helpers ─────────────────────────────────────
    @Test fun reset_returns_to_idle() {
        click(R.id.btn_mic)
        kb.reset()
        click(R.id.btn_mic) // should start recording again
        assertEquals(2, started)
    }

    @Test fun updateAmplitude_outside_recording_is_noop() {
        // Must not throw when not Recording.
        kb.updateMicrophoneAmplitude(30000)
    }

    @Test fun updateAmplitude_while_recording_is_noop() {
        kb.tryStartRecording()
        kb.updateMicrophoneAmplitude(30000) // animates ripples, must not throw
    }

    // ── Retry visibility (driven by shouldShowRetry) ────────────
    @Test fun retry_hidden_when_shouldShowRetry_false() {
        // setUp() used shouldShowRetry = { false } -> retry INVISIBLE in Idle
        assertEquals(View.INVISIBLE, root.findViewById<View>(R.id.btn_retry).visibility)
    }

    @Test fun retry_visible_when_shouldShowRetry_true() {
        val pair = buildKeyboard(shouldShowRetry = true)
        assertEquals(View.VISIBLE, pair.second.findViewById<View>(R.id.btn_retry).visibility)
    }
}
