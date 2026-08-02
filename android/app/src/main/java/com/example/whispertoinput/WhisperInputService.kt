/*
 * This file is part of Whisper To Input, see <https://github.com/j3soon/whisper-to-input>.
 *
 * Copyright (c) 2023-2025 Yan-Bin Diau, Johnson Sun
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.example.whispertoinput

import android.inputmethodservice.InputMethodService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.View
import android.widget.Toast
import androidx.datastore.preferences.core.Preferences
import com.example.whispertoinput.recorder.RecorderManager
import com.example.whispertoinput.keyboard.WhisperKeyboard
import com.github.liuyueyi.quick.transfer.ChineseUtils
import com.github.liuyueyi.quick.transfer.constants.TransType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.whispertoinput.BuildConfig

private const val RECORDED_AUDIO_FILENAME_M4A = "recorded.m4a"
private const val RECORDED_AUDIO_FILENAME_OGG = "recorded.ogg"
private const val AUDIO_MEDIA_TYPE_M4A = "audio/mp4"
private const val AUDIO_MEDIA_TYPE_OGG = "audio/ogg"
private const val AUDIO_MEDIA_TYPE_WAV = "audio/wav"

/**
 * Voice input method service with tap-to-toggle recording.
 * Tap mic to start, tap again to stop and transcribe.
 */
class WhisperInputService : InputMethodService() {
    private val whisperTranscriber: WhisperTranscriber = WhisperTranscriber()
    private var recorderManager: RecorderManager = RecorderManager()
    private var recordedAudioFilename: String = ""
    private var audioMediaType: String = AUDIO_MEDIA_TYPE_M4A
    private var useOggFormat: Boolean = false
    private var testFileModeRecording: Boolean = false  // Track test file recording state

    // Key Manager
    private var keyManagerClient: KeyManagerClient? = null

    // Keyboard
    private val whisperKeyboard = WhisperKeyboard()

    private val toggleReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            android.util.Log.d("whisper-input", "onReceive: action=${intent?.action}")
            if (intent?.action == ACTION_TOGGLE_RECORDING) {
                // External toggle: toggle recording state
                whisperKeyboard.toggleRecording()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        android.util.Log.d("whisper-input", "onCreate: registering receiver")
        registerReceiver(toggleReceiver, IntentFilter(ACTION_TOGGLE_RECORDING), Context.RECEIVER_EXPORTED)
        android.util.Log.d("whisper-input", "onCreate: receiver registered")

        // Bind to Key Manager
        keyManagerClient = KeyManagerClient(this)
        keyManagerClient?.bind()
    }

    private fun transcriptionCallback(text: String?) {
        if (!text.isNullOrEmpty()) {
            lastTranscriptionResult = text
            lastTranscriptionError = null
            currentInputConnection?.commitText(text, 1)
            CoroutineScope(Dispatchers.Main).launch {
                val autoSwitchBack = dataStore.data.map { preferences: Preferences ->
                    preferences[AUTO_SWITCH_BACK] ?: false
                }.first()
                if (autoSwitchBack) {
                    switchToPreviousInputMethod()
                }
            }
        }
        whisperKeyboard.reset()
    }

    private fun transcriptionExceptionCallback(message: String) {
        // Store error for display in MainActivity debug field
        lastTranscriptionError = message
        android.util.Log.e("whisper-input", "Transcription error: $message")
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        whisperKeyboard.reset()
    }

    companion object {
        const val ACTION_TOGGLE_RECORDING = "com.example.whispertoinput.action.TOGGLE_RECORDING"
        var lastTranscriptionResult: String? = null
        var lastTranscriptionError: String? = null
    }

    private suspend fun updateAudioFormat() {
        val backend = dataStore.data.map { preferences: Preferences ->
            preferences[SPEECH_TO_TEXT_BACKEND] ?: getString(R.string.settings_option_openai_api)
        }.first()

        useOggFormat = backend == getString(R.string.settings_option_nvidia_nim)
        if (useOggFormat) {
            recordedAudioFilename = "${externalCacheDir?.absolutePath}/${RECORDED_AUDIO_FILENAME_OGG}"
            audioMediaType = AUDIO_MEDIA_TYPE_OGG
        } else {
            recordedAudioFilename = "${externalCacheDir?.absolutePath}/${RECORDED_AUDIO_FILENAME_M4A}"
            audioMediaType = AUDIO_MEDIA_TYPE_M4A
        }
    }

    override fun onCreateInputView(): View {
        android.util.Log.d("whisper-input", "onCreateInputView: creating keyboard view")

        // Preload conversion table
        ChineseUtils.preLoad(true, TransType.SIMPLE_TO_TAIWAN)
        ChineseUtils.preLoad(true, TransType.TAIWAN_TO_SIMPLE)

        CoroutineScope(Dispatchers.Main).launch {
            updateAudioFormat()
        }

        val view = whisperKeyboard.setup(
            layoutInflater = layoutInflater,
            shouldOfferImeSwitch = true,
            onStartRecording = { startRecording() },
            onCancelRecording = { cancelRecording() },
            onStartTranscribing = { attachToEnd -> startTranscribing(attachToEnd) },
            onCancelTranscribing = { cancelTranscribing() },
            onButtonBackspace = { performBackspace() },
            onEnter = { performEnter() },
            onSpaceBar = { performSpace() },
            onSwitchIme = { switchToPreviousInputMethod() },
            onOpenSettings = { launchMainActivity() },
            shouldShowRetry = { lastTranscriptionError != null }
        )

        android.util.Log.d("whisper-input", "onCreateInputView: keyboard view created")
        return view
    }

    override fun onStartInputView(info: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // Auto-start recording when keyboard appears
        whisperKeyboard.tryStartRecording()
    }

    private fun startRecording() {
        if (!recorderManager.allPermissionsGranted(this)) {
            launchMainActivity()
            return
        }

        CoroutineScope(Dispatchers.Main).launch {
            val useTestFile = if (BuildConfig.DEBUG) {
                dataStore.data.map { it[USE_TEST_FILE] ?: false }.first()
            } else false

            if (useTestFile) {
                testFileModeRecording = true
            } else {
                updateAudioFormat()
                recorderManager.start(this@WhisperInputService, recordedAudioFilename, useOggFormat)
            }
        }
    }

    private fun cancelRecording() {
        if (recorderManager.isRecording) {
            recorderManager.stop()
        }
        testFileModeRecording = false
    }

    private fun startTranscribing(attachToEnd: String) {
        CoroutineScope(Dispatchers.Main).launch {
            // Get valid API key from key-manager
            val apiKey = withContext(Dispatchers.IO) {
                keyManagerClient?.getValidApiKey()
            }

            if (apiKey == null) {
                // Key-manager not available or not configured
                val message = if (keyManagerClient?.bind() == false) {
                    "Key Manager app not installed"
                } else {
                    "Key Manager not configured. Open Key Manager app to set credentials."
                }
                
                lastTranscriptionError = message
                Toast.makeText(this@WhisperInputService, message, Toast.LENGTH_LONG).show()
                whisperKeyboard.reset()
                return@launch
            }

            val useTestFile = if (BuildConfig.DEBUG) {
                dataStore.data.map { it[USE_TEST_FILE] ?: false }.first()
            } else false

            if (useTestFile) {
                // Test file mode: stop recording and transcribe test file
                testFileModeRecording = false
                val testFilePath = dataStore.data
                    .map { it[TEST_FILE_PATH] ?: "/sdcard/test-speech-loud.wav" }
                    .first()

                whisperTranscriber.startAsync(this@WhisperInputService,
                    testFilePath,
                    AUDIO_MEDIA_TYPE_WAV,
                    attachToEnd,
                    apiKey,
                    { text ->
                        android.util.Log.d("whisper-input", "Transcription result: '$text'")
                        transcriptionCallback(text)
                    },
                    { msg ->
                        android.util.Log.e("whisper-input", "Transcription error: $msg")
                        transcriptionExceptionCallback(msg)
                    })
            } else if (recorderManager.isRecording) {
                // Normal mode: stop recording and transcribe
                recorderManager.stop()

                whisperTranscriber.startAsync(this@WhisperInputService,
                    recordedAudioFilename,
                    audioMediaType,
                    attachToEnd,
                    apiKey,
                    { text ->
                        android.util.Log.d("whisper-input", "Transcription result: '$text'")
                        transcriptionCallback(text)
                    },
                    { msg ->
                        android.util.Log.e("whisper-input", "Transcription error: $msg")
                        transcriptionExceptionCallback(msg)
                    })
            }
        }
    }

    private fun cancelTranscribing() {
        whisperTranscriber.stop()
    }

    private fun performBackspace() {
        currentInputConnection?.deleteSurroundingText(1, 0)
    }

    private fun performEnter() {
        currentInputConnection?.commitText("\n", 1)
    }

    private fun performSpace() {
        currentInputConnection?.commitText(" ", 1)
    }

    private fun launchMainActivity() {
        val dialogIntent = Intent(this, MainActivity::class.java)
        dialogIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(dialogIntent)
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        android.util.Log.d("whisper-input", "onWindowHidden: isRecording=${recorderManager.isRecording}")
        if (recorderManager.isRecording) {
            recorderManager.stop()
        }
        whisperKeyboard.reset()
    }

    override fun onDestroy() {
        keyManagerClient?.unbind()
        whisperTranscriber.stop()
        recorderManager.stop()
        unregisterReceiver(toggleReceiver)
        super.onDestroy()
    }
}
