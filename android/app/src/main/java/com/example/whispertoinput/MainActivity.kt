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

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import androidx.core.app.ActivityCompat
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.*
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.widget.doOnTextChanged
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

import com.example.whispertoinput.BuildConfig

// 200 and 201 are an arbitrary values, as long as they do not conflict with each other
private const val MICROPHONE_PERMISSION_REQUEST_CODE = 200
private const val NOTIFICATION_PERMISSION_REQUEST_CODE = 201
val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")
val SPEECH_TO_TEXT_BACKEND = stringPreferencesKey("speech-to-text-backend")
val ENDPOINT = stringPreferencesKey("endpoint")
val LANGUAGE_CODE = stringPreferencesKey("language-code")
val API_KEY = stringPreferencesKey("api-key")
val MODEL = stringPreferencesKey("model")
val AUTO_RECORDING_START = booleanPreferencesKey("is-auto-recording-start")
val AUTO_SWITCH_BACK = booleanPreferencesKey("auto-switch-back")
val ADD_TRAILING_SPACE = booleanPreferencesKey("add-trailing-space")
val USE_TEST_FILE = booleanPreferencesKey("use-test-file")
val TEST_FILE_PATH = stringPreferencesKey("test-file-path")

class MainActivity : AppCompatActivity() {
    private var setupSettingItemsDone: Boolean = false

    /**
     * Completes once [setupSettingItems] has finished populating all settings.
     * Tests can await this instead of polling a side-effect field.
     */
    @androidx.annotation.VisibleForTesting
    val settingsReady = CompletableDeferred<Unit>()

    // Set once the user taps Apply, so the enable-keyboard prompt only appears on request.
    private var keyboardPromptRequested: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setupSettingItems()
        setupEnableKeyboardLink()
        checkPermissions()

        // Show debug-only fields in debug builds
        if (BuildConfig.DEBUG) {
            val debugLabel = findViewById<View>(R.id.label_debug_output)
            val debugField = findViewById<EditText>(R.id.field_debug_output)
            debugLabel?.visibility = View.VISIBLE
            debugField?.visibility = View.VISIBLE
            // Show test-file settings
            listOf(
                R.id.label_use_test_file, R.id.description_use_test_file,
                R.id.spinner_use_test_file, R.id.label_test_file_path,
                R.id.description_test_file_path, R.id.field_test_file_path
            ).forEach { findViewById<View>(it)?.visibility = View.VISIBLE }
        }
    }

    private fun updateApiKeyLink(provider: String) {
        val linkView = findViewById<TextView>(R.id.link_api_key)
        val url = when (provider) {
            getString(R.string.settings_option_voxtral) -> getString(R.string.settings_option_voxtral_api_key_url)
            getString(R.string.settings_option_elevenlabs) -> getString(R.string.settings_option_elevenlabs_api_key_url)
            getString(R.string.settings_option_deepgram) -> getString(R.string.settings_option_deepgram_api_key_url)
            getString(R.string.settings_option_groq) -> getString(R.string.settings_option_groq_api_key_url)
            getString(R.string.settings_option_60db) -> getString(R.string.settings_option_60db_api_key_url)
            else -> return
        }
        val label = getString(R.string.settings_api_key_url_label)
        val spannable = SpannableString(label)
        spannable.setSpan(object : ClickableSpan() {
            override fun onClick(widget: View) {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                startActivity(intent)
            }
        }, 0, label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        linkView.text = spannable
        linkView.movementMethod = LinkMovementMethod.getInstance()
    }

    override fun onResume() {
        super.onResume()
        // Keep the enable-keyboard prompt in sync once the user has been prompted.
        if (keyboardPromptRequested) {
            updateKeyboardEnabledPrompt()
        }
        // Update debug field with last transcription result or error
        if (BuildConfig.DEBUG) {
            val debugField = findViewById<EditText>(R.id.field_debug_output)
            val error = WhisperInputService.lastTranscriptionError
            val result = WhisperInputService.lastTranscriptionResult
            when {
                error != null -> debugField?.setText("ERROR: $error")
                result != null -> debugField?.setText(result)
            }
        }
    }

    /**
     * Wires the (initially hidden) enable-keyboard link so tapping it opens the
     * system input-method settings where the user can enable this keyboard.
     */
    private fun setupEnableKeyboardLink() {
        val linkView = findViewById<TextView>(R.id.link_enable_keyboard)
        val text = getString(R.string.settings_keyboard_enable_prompt)
        val spannable = SpannableString(text)
        spannable.setSpan(object : ClickableSpan() {
            override fun onClick(widget: View) {
                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            }
        }, 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        linkView.text = spannable
        linkView.movementMethod = LinkMovementMethod.getInstance()
    }

    /**
     * Returns true if the "Whisper Input" IME is enabled in system settings.
     */
    private fun isWhisperKeyboardEnabled(): Boolean {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        return imm.enabledInputMethodList.any {
            it.serviceInfo.packageName == packageName &&
                it.serviceInfo.name.endsWith("WhisperInputService")
        }
    }

    /**
     * Shows the enable-keyboard link when this IME is not yet enabled.
     */
    private fun updateKeyboardEnabledPrompt() {
        val linkView = findViewById<TextView>(R.id.link_enable_keyboard)
        linkView.visibility = if (isWhisperKeyboardEnabled()) View.GONE else View.VISIBLE
    }

    // The onClick event of the grant permission button.
    // Opens up the app settings panel to manually configure permissions.
    fun onRequestMicrophonePermission(view: View) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        with(intent) {
            data = Uri.fromParts("package", packageName, null)
            addCategory(Intent.CATEGORY_DEFAULT)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
            addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
        }

        startActivity(intent)
    }

    // Checks whether permissions are granted. If not, automatically make a request.
    private fun checkPermissions() {
        val permission_and_code = arrayOf(
            Pair(Manifest.permission.RECORD_AUDIO, MICROPHONE_PERMISSION_REQUEST_CODE),
            Pair(Manifest.permission.POST_NOTIFICATIONS, NOTIFICATION_PERMISSION_REQUEST_CODE),
        )
        for ((permission, code) in permission_and_code) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    permission
                ) == PackageManager.PERMISSION_DENIED
            ) {
                // Shows a popup for permission request.
                // If the permission has been previously (hard-)denied, the popup will not show.
                // onRequestPermissionsResult will be called in either case.
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(permission),
                    code
                )
            }
        }
    }

    // Handles the results of permission requests.
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        var msg: String

        // Only handles requests marked with the unique code.
        if (requestCode == MICROPHONE_PERMISSION_REQUEST_CODE) {
            msg = getString(R.string.mic_permission_required)
        } else if (requestCode == NOTIFICATION_PERMISSION_REQUEST_CODE) {
            msg = getString(R.string.notification_permission_required)
        } else {
            return
        }

        // All permissions should be granted.
        for (result in grantResults) {
            if (result != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                return
            }
        }
    }

    // Below are settings related functions
    abstract inner class SettingItem() {
        protected var isDirty: Boolean = false
        abstract fun setup() : Job
        abstract suspend fun apply()
        protected suspend fun <T> readSetting(key: Preferences.Key<T>): T? {
            // work is moved to `Dispatchers.IO` under the hood
            // Ref: https://developer.android.com/codelabs/android-preferences-datastore#3
            return dataStore.data.map { preferences ->
                preferences[key]
            }.first()
        }
        protected suspend fun <T> writeSetting(key: Preferences.Key<T>, newValue: T) {
            // work is moved to `Dispatchers.IO` under the hood
            // Ref: https://developer.android.com/codelabs/android-preferences-datastore#3
            dataStore.edit { settings ->
                settings[key] = newValue
            }
        }
    }

    inner class SettingText(
        private val viewId: Int,
        private val preferenceKey: Preferences.Key<String>,
        private val defaultValue: String = ""
    ): SettingItem() {
        override fun setup(): Job {
            return CoroutineScope(Dispatchers.Main).launch {
                val btnApply: Button = findViewById(R.id.btn_settings_apply)
                val editText = findViewById<EditText>(viewId)
                editText.isEnabled = false

                // Read data. If none, apply default value.
                val settingValue: String? = readSetting(preferenceKey)
                val value: String = settingValue ?: defaultValue
                if (settingValue == null) {
                    writeSetting(preferenceKey, defaultValue)
                }
                editText.setText(value)
                editText.isEnabled = true
                editText.doOnTextChanged { _, _, _, _ ->
                    if (!setupSettingItemsDone) return@doOnTextChanged
                    isDirty = true
                    btnApply.isEnabled = true
                }
            }
        }
        override suspend fun apply() {
            if (!isDirty) return
            val newValue: String = findViewById<EditText>(viewId).text.toString()
            writeSetting(preferenceKey, newValue)
            isDirty = false
        }
    }

    inner class SettingDropdown(
        private val viewId: Int,
        private val preferenceKey: Preferences.Key<Boolean>,
        private val stringToValue: HashMap<String, Boolean>,
        private val defaultValue: Boolean = true
    ): SettingItem() {
        override fun setup(): Job {
            return CoroutineScope(Dispatchers.Main).launch {
                val btnApply: Button = findViewById(R.id.btn_settings_apply)
                val spinner = findViewById<Spinner>(viewId)
                spinner.isEnabled = false
                spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>, view: View?, pos: Int, id: Long) {
                        if (!setupSettingItemsDone) return
                        isDirty = true
                        btnApply.isEnabled = true
                    }
                    override fun onNothingSelected(parent: AdapterView<*>) { }
                }

                val valueToString = stringToValue.map { (k, v) -> v to k }.toMap()
                // Read data. If none, apply default value.
                val settingValue: Boolean? = readSetting(preferenceKey)
                val value: Boolean = settingValue ?: defaultValue
                val string: String = valueToString[value]!!
                if (settingValue == null) {
                    writeSetting(preferenceKey, defaultValue)
                }
                val index: Int? = (0 until spinner.adapter.count).firstOrNull {
                    spinner.adapter.getItem(it) == string
                }
                spinner.setSelection(index!!, false)
                spinner.isEnabled = true
            }
        }
        override suspend fun apply() {
            if (!isDirty) return
            val selectedItem = findViewById<Spinner>(viewId).selectedItem
            val newValue: Boolean = stringToValue[selectedItem]!!
            writeSetting(preferenceKey, newValue)
            isDirty = false
        }
    }

    inner class SettingStringDropdown(
        private val viewId: Int,
        private val preferenceKey: Preferences.Key<String>,
        private val optionValues: List<String>,
        private val defaultValue: String = ""
    ): SettingItem() {
        override fun setup(): Job {
            return CoroutineScope(Dispatchers.Main).launch {
                val btnApply: Button = findViewById(R.id.btn_settings_apply)
                val spinner = findViewById<Spinner>(viewId)
                spinner.isEnabled = false
                spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>, view: View?, pos: Int, id: Long) {
                        if (!setupSettingItemsDone) return
                        isDirty = true
                        btnApply.isEnabled = true
                        if (parent.id == R.id.spinner_speech_to_text_backend) {
                            updateApiKeyLink(parent.getItemAtPosition(pos).toString())
                        }
                        // Deal with individual spinner
                        if (parent.id == R.id.spinner_speech_to_text_backend) {
                            val selectedItem = parent.getItemAtPosition(pos)
                            if (selectedItem == getString(R.string.settings_option_voxtral)) {
                                val endpointEditText: EditText = findViewById<EditText>(R.id.field_endpoint)
                                if (endpointEditText.text.isEmpty() ||
                                    endpointEditText.text.toString() == getString(R.string.settings_option_elevenlabs_default_endpoint) ||
                                    endpointEditText.text.toString() == getString(R.string.settings_option_deepgram_default_endpoint) ||
                                    endpointEditText.text.toString() == getString(R.string.settings_option_groq_default_endpoint) ||
                                    endpointEditText.text.toString() == getString(R.string.settings_option_60db_default_endpoint)
                                ) {
                                    endpointEditText.setText(getString(R.string.settings_option_voxtral_default_endpoint))
                                }
                                val modelEditText: EditText = findViewById<EditText>(R.id.field_model)
                                modelEditText.setText(getString(R.string.settings_option_voxtral_default_model))
                                val languageCodeEditText: EditText = findViewById<EditText>(R.id.field_language_code)
                                languageCodeEditText.setText(getString(R.string.settings_option_voxtral_default_language))
                            } else if (selectedItem == getString(R.string.settings_option_elevenlabs)) {
                                val endpointEditText: EditText = findViewById<EditText>(R.id.field_endpoint)
                                if (endpointEditText.text.isEmpty() ||
                                    endpointEditText.text.toString() == getString(R.string.settings_option_voxtral_default_endpoint) ||
                                    endpointEditText.text.toString() == getString(R.string.settings_option_deepgram_default_endpoint) ||
                                    endpointEditText.text.toString() == getString(R.string.settings_option_groq_default_endpoint) ||
                                    endpointEditText.text.toString() == getString(R.string.settings_option_60db_default_endpoint)
                                ) {
                                    endpointEditText.setText(getString(R.string.settings_option_elevenlabs_default_endpoint))
                                }
                                val modelEditText: EditText = findViewById<EditText>(R.id.field_model)
                                modelEditText.setText(getString(R.string.settings_option_elevenlabs_default_model))
                                val languageCodeEditText: EditText = findViewById<EditText>(R.id.field_language_code)
                                languageCodeEditText.setText(getString(R.string.settings_option_elevenlabs_default_language))
                            } else if (selectedItem == getString(R.string.settings_option_deepgram)) {
                                val endpointEditText: EditText = findViewById<EditText>(R.id.field_endpoint)
                                if (endpointEditText.text.isEmpty() ||
                                    endpointEditText.text.toString() == getString(R.string.settings_option_voxtral_default_endpoint) ||
                                    endpointEditText.text.toString() == getString(R.string.settings_option_elevenlabs_default_endpoint) ||
                                    endpointEditText.text.toString() == getString(R.string.settings_option_groq_default_endpoint) ||
                                    endpointEditText.text.toString() == getString(R.string.settings_option_60db_default_endpoint)
                                ) {
                                    endpointEditText.setText(getString(R.string.settings_option_deepgram_default_endpoint))
                                }
                                val modelEditText: EditText = findViewById<EditText>(R.id.field_model)
                                modelEditText.setText(getString(R.string.settings_option_deepgram_default_model))
                                val languageCodeEditText: EditText = findViewById<EditText>(R.id.field_language_code)
                                languageCodeEditText.setText(getString(R.string.settings_option_deepgram_default_language))
                            } else if (selectedItem == getString(R.string.settings_option_groq)) {
                                val endpointEditText: EditText = findViewById<EditText>(R.id.field_endpoint)
                                if (endpointEditText.text.isEmpty() ||
                                    endpointEditText.text.toString() == getString(R.string.settings_option_voxtral_default_endpoint) ||
                                    endpointEditText.text.toString() == getString(R.string.settings_option_elevenlabs_default_endpoint) ||
                                    endpointEditText.text.toString() == getString(R.string.settings_option_deepgram_default_endpoint) ||
                                    endpointEditText.text.toString() == getString(R.string.settings_option_60db_default_endpoint)
                                ) {
                                    endpointEditText.setText(getString(R.string.settings_option_groq_default_endpoint))
                                }
                                val modelEditText: EditText = findViewById<EditText>(R.id.field_model)
                                modelEditText.setText(getString(R.string.settings_option_groq_default_model))
                                val languageCodeEditText: EditText = findViewById<EditText>(R.id.field_language_code)
                                languageCodeEditText.setText(getString(R.string.settings_option_groq_default_language))
                            } else if (selectedItem == getString(R.string.settings_option_60db)) {
                                val endpointEditText: EditText = findViewById<EditText>(R.id.field_endpoint)
                                if (endpointEditText.text.isEmpty() ||
                                    endpointEditText.text.toString() == getString(R.string.settings_option_voxtral_default_endpoint) ||
                                    endpointEditText.text.toString() == getString(R.string.settings_option_elevenlabs_default_endpoint) ||
                                    endpointEditText.text.toString() == getString(R.string.settings_option_deepgram_default_endpoint) ||
                                    endpointEditText.text.toString() == getString(R.string.settings_option_groq_default_endpoint)
                                ) {
                                    endpointEditText.setText(getString(R.string.settings_option_60db_default_endpoint))
                                }
                                val modelEditText: EditText = findViewById<EditText>(R.id.field_model)
                                modelEditText.setText(getString(R.string.settings_option_60db_default_model))
                                val languageCodeEditText: EditText = findViewById<EditText>(R.id.field_language_code)
                                languageCodeEditText.setText(getString(R.string.settings_option_60db_default_language))
                            }
                        }
                    }
                    override fun onNothingSelected(parent: AdapterView<*>) { }
                }

                // Read data. If none, apply default value.
                val settingValue: String? = readSetting(preferenceKey)
                val value: String = settingValue ?: defaultValue
                if (settingValue == null) {
                    writeSetting(preferenceKey, defaultValue)
                }
                val index: Int? = (0 until spinner.adapter.count).firstOrNull {
                    spinner.adapter.getItem(it) == value
                }
                spinner.setSelection(index ?: 0, false)
                if (viewId == R.id.spinner_speech_to_text_backend) {
                    updateApiKeyLink(spinner.selectedItem.toString())
                }
                spinner.isEnabled = true
            }
        }
        override suspend fun apply() {
            if (!isDirty) return
            val selectedItem = findViewById<Spinner>(viewId).selectedItem
            val newValue: String = selectedItem.toString()
            writeSetting(preferenceKey, newValue)
            isDirty = false
        }
    }

    private fun setupSettingItems() {
        setupSettingItemsDone = false
        // Add setting items here to apply functions to them
        CoroutineScope(Dispatchers.Main).launch {
            val settingItems = arrayOf(
                SettingStringDropdown(R.id.spinner_speech_to_text_backend, SPEECH_TO_TEXT_BACKEND, listOf(
                    getString(R.string.settings_option_voxtral),
                    getString(R.string.settings_option_elevenlabs),
                    getString(R.string.settings_option_deepgram),
                    getString(R.string.settings_option_groq),
                    getString(R.string.settings_option_60db)
                ), getString(R.string.settings_option_voxtral)),
                SettingText(R.id.field_endpoint, ENDPOINT, getString(R.string.settings_option_voxtral_default_endpoint)),
                SettingText(R.id.field_language_code, LANGUAGE_CODE, getString(R.string.settings_option_voxtral_default_language)),
                SettingText(R.id.field_api_key, API_KEY),
                SettingText(R.id.field_model, MODEL, getString(R.string.settings_option_voxtral_default_model)),
                SettingDropdown(R.id.spinner_auto_recording_start, AUTO_RECORDING_START, hashMapOf(
                    getString(R.string.settings_option_yes) to true,
                    getString(R.string.settings_option_no) to false,
                )),
                SettingDropdown(R.id.spinner_auto_switch_back, AUTO_SWITCH_BACK, hashMapOf(
                    getString(R.string.settings_option_yes) to true,
                    getString(R.string.settings_option_no) to false,
                ), false),
                SettingDropdown(R.id.spinner_add_trailing_space, ADD_TRAILING_SPACE, hashMapOf(
                    getString(R.string.settings_option_yes) to true,
                    getString(R.string.settings_option_no) to false,
                ), false),
                if (BuildConfig.DEBUG) SettingDropdown(R.id.spinner_use_test_file, USE_TEST_FILE, hashMapOf(
                    getString(R.string.settings_option_yes) to true,
                    getString(R.string.settings_option_no) to false,
                ), true) else null,
                if (BuildConfig.DEBUG) SettingText(R.id.field_test_file_path, TEST_FILE_PATH, cacheDir.resolve("test-speech-loud.wav").absolutePath) else null,
            ).filterNotNull()
            val btnApply: Button = findViewById(R.id.btn_settings_apply)
            btnApply.isEnabled = false
            btnApply.setOnClickListener {
                CoroutineScope(Dispatchers.Main).launch {
                    btnApply.isEnabled = false
                    for (settingItem in settingItems) {
                        settingItem.apply()
                    }
                    keyboardPromptRequested = true
                    updateKeyboardEnabledPrompt()
                    btnApply.isEnabled = false
                }
                Toast.makeText(this@MainActivity, R.string.successfully_set, Toast.LENGTH_SHORT).show()
            }
            settingItems.map { settingItem -> settingItem.setup() }.joinAll()
            setupSettingItemsDone = true
            settingsReady.complete(Unit)
        }
    }
}
