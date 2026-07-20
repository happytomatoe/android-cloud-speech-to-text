package org.futo.voiceinput

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.futo.voiceinput.settings.API_KEY
import org.futo.voiceinput.settings.ENDPOINT
import org.futo.voiceinput.settings.MODEL
import org.futo.voiceinput.settings.dataStore
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Cloud transcriber for sending audio to cloud STT APIs (Deepgram, Groq, etc.)
 */
class CloudTranscriber {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Start async transcription of audio file
     * @param context Android context
     * @param filename Path to the audio file
     * @param mediaType MIME type of the audio (e.g., "audio/wav", "audio/mp4")
     * @param callback Callback with transcription result
     * @param exceptionCallback Callback with error message
     */
    fun startAsync(
        context: Context,
        filename: String,
        mediaType: String,
        callback: (String?) -> Unit,
        exceptionCallback: (String) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val apiKey = context.dataStore.data
                    .map { it[API_KEY.key] ?: "" }
                    .first()

                val endpoint = context.dataStore.data
                    .map { it[ENDPOINT.key] ?: "https://api.deepgram.com/v1/listen" }
                    .first()

                val model = context.dataStore.data
                    .map { it[MODEL.key] ?: "nova-3" }
                    .first()

                if (apiKey.isEmpty()) {
                    with(Dispatchers.Main) {
                        exceptionCallback("API key not configured. Please set it in Settings.")
                    }
                    return@launch
                }

                val audioFile = File(filename)
                if (!audioFile.exists()) {
                    with(Dispatchers.Main) {
                        exceptionCallback("Audio file not found: $filename")
                    }
                    return@launch
                }

                Log.d("voice-input", "Starting cloud transcription: endpoint=$endpoint, model=$model")

                // Send the audio as a raw request body. Deepgram accepts raw audio when
                // the Content-Type matches the audio format; multipart/form-data was
                // rejected with "corrupt or unsupported data".
                val requestBody = audioFile.asRequestBody(mediaType.toMediaType())

                // Build URL with model parameter
                val url = if (endpoint.contains("?")) {
                    "$endpoint&model=$model"
                } else {
                    "$endpoint?model=$model"
                }

                val request = Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Token $apiKey")
                    .addHeader("Content-Type", mediaType)
                    .post(requestBody)
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string()

                if (!response.isSuccessful) {
                    val errorMsg = "API error ${response.code}: $responseBody"
                    Log.e("voice-input", errorMsg)
                    with(Dispatchers.Main) {
                        exceptionCallback(errorMsg)
                    }
                    return@launch
                }

                // Parse Deepgram-style response
                val transcript = parseTranscriptionResponse(responseBody)

                Log.d("voice-input", "Transcription result: $transcript")
                with(Dispatchers.Main) {
                    callback(transcript)
                }
            } catch (e: Exception) {
                Log.e("voice-input", "Cloud transcription failed", e)
                with(Dispatchers.Main) {
                    exceptionCallback("Cloud transcription failed: ${e.message}")
                }
            }
        }
    }

    /**
     * Parse transcription response from various APIs
     */
    private fun parseTranscriptionResponse(responseBody: String?): String? {
        if (responseBody == null) return null

        return try {
            // Try Deepgram-style response first
            // {"results":{"channels":[{"alternatives":[{"transcript":"hello world"}]}]}}
            val channelsIdx = responseBody.indexOf("\"channels\"")
            if (channelsIdx != -1) {
                val transcriptIdx = responseBody.indexOf("\"transcript\"", channelsIdx)
                if (transcriptIdx != -1) {
                    val startQuote = responseBody.indexOf("\"", transcriptIdx + 12)
                    val endQuote = responseBody.indexOf("\"", startQuote + 1)
                    if (startQuote != -1 && endQuote != -1) {
                        return responseBody.substring(startQuote + 1, endQuote)
                    }
                }
            }

            // Try OpenAI-style response
            // {"text": "hello world"}
            val textIdx = responseBody.indexOf("\"text\"")
            if (textIdx != -1) {
                val startQuote = responseBody.indexOf("\"", textIdx + 6)
                val endQuote = responseBody.indexOf("\"", startQuote + 1)
                if (startQuote != -1 && endQuote != -1) {
                    return responseBody.substring(startQuote + 1, endQuote)
                }
            }

            Log.w("voice-input", "Could not parse transcription response: $responseBody")
            null
        } catch (e: Exception) {
            Log.e("voice-input", "Failed to parse transcription response", e)
            null
        }
    }

    /**
     * Stop any ongoing transcription (currently no-op for cloud)
     */
    fun stop() {
        // Cloud requests are stateless, nothing to stop
    }
}
