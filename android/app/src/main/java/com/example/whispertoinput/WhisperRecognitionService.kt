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

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.RecognitionService.Callback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.example.whispertoinput.recorder.RecorderManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File

private const val TAG = "WhisperRecognitionService"
private const val RECORDED_AUDIO_FILENAME = "recorded_recognition.m4a"
private const val AUDIO_MEDIA_TYPE = "audio/mp4"

/**
 * System-wide voice recognition service that uses Whisper for transcription.
 * This allows the app to be selected as a voice input provider in Android settings
 * (System > Languages > Speech > Voice Input) and used by apps that call
 * SpeechRecognizer or RecognizerIntent.ACTION_RECOGNIZE_SPEECH.
 */
class WhisperRecognitionService : RecognitionService() {

    private val recorderManager = RecorderManager()
    private val whisperTranscriber = WhisperTranscriber()
    private var currentCallback: Callback? = null
    private var recordedAudioPath: String = ""

    override fun onStartListening(intent: Intent, callback: Callback) {
        Log.d(TAG, "onStartListening")
        currentCallback = callback

        if (!recorderManager.allPermissionsGranted(this)) {
            Log.e(TAG, "Permissions not granted")
            callback.error(SpeechRecognizer.ERROR_CLIENT)
            return
        }

        recordedAudioPath = "${externalCacheDir?.absolutePath}/$RECORDED_AUDIO_FILENAME"

        // Start recording
        recorderManager.start(this, recordedAudioPath, false)
        Log.d(TAG, "Recording started")

        try {
            callback.beginningOfSpeech()
        } catch (e: Exception) {
            Log.e(TAG, "beginningOfSpeech failed: ${e.message}")
        }

        // Auto-stop after 30 seconds (Whisper limit)
        CoroutineScope(Dispatchers.Main).launch {
            kotlinx.coroutines.delay(30_000)
            if (recorderManager.isRecording) {
                Log.d(TAG, "Auto-stopping after 30s timeout")
                stopAndTranscribe()
            }
        }
    }

    override fun onStopListening(callback: Callback) {
        Log.d(TAG, "onStopListening")
        if (recorderManager.isRecording) {
            stopAndTranscribe()
        } else {
            callback.error(SpeechRecognizer.ERROR_NO_MATCH)
        }
    }

    override fun onCancel(callback: Callback) {
        Log.d(TAG, "onCancel")
        if (recorderManager.isRecording) {
            recorderManager.stop()
            // Clean up recorded file
            File(recordedAudioPath).delete()
        }
        currentCallback = null
    }

    private fun stopAndTranscribe() {
        recorderManager.stop()
        val callback = currentCallback ?: return

        try {
            callback.endOfSpeech()
        } catch (e: Exception) {
            Log.e(TAG, "endOfSpeech failed: ${e.message}")
        }

        // Transcribe the recorded audio
        whisperTranscriber.startAsync(
            this,
            recordedAudioPath,
            AUDIO_MEDIA_TYPE,
            "",
            { text ->
                Log.d(TAG, "Transcription result: '$text'")
                if (!text.isNullOrEmpty()) {
                    val results = Bundle().apply {
                        putStringArrayList(
                            SpeechRecognizer.RESULTS_RECOGNITION,
                            arrayListOf(text)
                        )
                        // Add confidence score
                        putFloatArray(
                            SpeechRecognizer.CONFIDENCE_SCORES,
                            floatArrayOf(1.0f)
                        )
                    }
                    try {
                        callback.results(results)
                    } catch (e: Exception) {
                        Log.e(TAG, "callback.results failed: ${e.message}")
                    }
                } else {
                    callback.error(SpeechRecognizer.ERROR_NO_MATCH)
                }
                currentCallback = null
            },
            { errorMsg ->
                Log.e(TAG, "Transcription error: $errorMsg")
                callback.error(SpeechRecognizer.ERROR_CLIENT)
                currentCallback = null
            }
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        whisperTranscriber.stop()
        if (recorderManager.isRecording) {
            recorderManager.stop()
        }
        currentCallback = null
    }
}
