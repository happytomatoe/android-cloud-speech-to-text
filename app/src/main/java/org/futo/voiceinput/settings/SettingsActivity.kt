package org.futo.voiceinput.settings

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import org.futo.voiceinput.R
import org.futo.voiceinput.payments.BillingManager
import org.futo.voiceinput.theme.UixThemeAuto
import org.futo.voiceinput.updates.scheduleUpdateCheckingJob
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.futo.voiceinput.ACTION_CONFIGURE_CLOUD
import org.futo.voiceinput.ACTION_TOGGLE_RECORDING
import org.futo.voiceinput.BuildConfig
import org.futo.voiceinput.startTestFileTranscription
import org.futo.voiceinput.settings.API_KEY
import org.futo.voiceinput.settings.ENDPOINT
import org.futo.voiceinput.settings.MODEL
import org.futo.voiceinput.settings.TEST_FILE_PATH
import org.futo.voiceinput.settings.USE_TEST_FILE
import org.futo.voiceinput.settings.setSetting

class SettingsActivity : ComponentActivity() {
    internal lateinit var billing: BillingManager
    private fun updateContent() {
        setContent {
            UixThemeAuto {
                // A surface container using the 'background' color from the theme
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SetupOrMain(billing = billing)
                }
            }
        }
    }

    private val permission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            viewModel.onResume()
        }


    private val voiceIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
    private val runVoiceIntent =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            viewModel.onIntentResult(
                when (it.resultCode) {
                    RESULT_OK -> {
                        val result =
                            it.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                        if (result.isNullOrEmpty()) {
                            getString(R.string.intent_result_is_null_or_empty)
                        } else {
                            result[0]
                        }
                    }

                    RESULT_CANCELED -> getString(R.string.intent_was_cancelled)
                    else -> getString(R.string.unknown_intent_result)
                }
            )
        }

    internal fun requestPermission() {
        permission.launch(Manifest.permission.RECORD_AUDIO)
    }

    internal fun launchVoiceIntent() {
        runVoiceIntent.launch(voiceIntent)
    }

    private lateinit var viewModel: SettingsViewModel

    // Debug-only E2E receivers (registered while the activity is in the foreground,
    // so the test-file transcription works without the IME service being bound).
    private val configureCloudReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_CONFIGURE_CLOUD) return
            if (!BuildConfig.DEBUG) {
                android.util.Log.w("voice-input", "CONFIGURE_CLOUD ignored: not a debug build")
                return
            }
            android.util.Log.d("voice-input", "onReceive: configure cloud")
            val endpoint = intent.getStringExtra("endpoint")
            val apiKey = intent.getStringExtra("api_key")
            val model = intent.getStringExtra("model")
            val useTestFile = intent.getBooleanExtra("use_test_file", false)
            val testFilePath = intent.getStringExtra("test_file_path")
            val ctx = this@SettingsActivity
            CoroutineScope(Dispatchers.IO).launch {
                if (!endpoint.isNullOrEmpty()) ctx.setSetting(ENDPOINT, endpoint)
                if (!apiKey.isNullOrEmpty()) ctx.setSetting(API_KEY, apiKey)
                ctx.setSetting(USE_TEST_FILE, useTestFile)
                if (!model.isNullOrEmpty()) ctx.setSetting(MODEL, model)
                if (!testFilePath.isNullOrEmpty()) ctx.setSetting(TEST_FILE_PATH, testFilePath)
                android.util.Log.d("voice-input", "Cloud settings configured via broadcast (useTestFile=$useTestFile)")
            }
        }
    }

    private val toggleRecordingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_TOGGLE_RECORDING) {
                android.util.Log.d("voice-input", "onReceive: toggle recording")
                startTestFileTranscription(this@SettingsActivity)
            }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        billing = BillingManager(this, lifecycleScope)

        viewModel = viewModels<SettingsViewModel>().value

        registerReceiver(configureCloudReceiver, IntentFilter(ACTION_CONFIGURE_CLOUD), Context.RECEIVER_EXPORTED)
        registerReceiver(toggleRecordingReceiver, IntentFilter(ACTION_TOGGLE_RECORDING), Context.RECEIVER_EXPORTED)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect {
                    updateContent()
                }
            }
        }

        scheduleUpdateCheckingJob(applicationContext)
    }

    override fun onStart() {
        super.onStart()

        billing.startConnection {
            it.checkAlreadyOwnsProduct()
        }
    }

    override fun onResume() {
        super.onResume()

        billing.onResume()
        viewModel.onResume()
    }

    override fun onRestart() {
        super.onRestart()

        billing.onResume()
        viewModel.onResume()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(configureCloudReceiver)
        unregisterReceiver(toggleRecordingReceiver)
    }
}
