/*
 * This file is part of Whisper To Input, see <https://github.com/j3soon/whisper-to-input>.
 *
 * Copyright (c) 2023-2025 Yan-Bin Diau, Johnson Sun
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by the
 * the Free Software Foundation, either version 3 of the License, or
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

import android.text.Spannable
import android.text.style.ClickableSpan
import android.view.View
import android.view.inputmethod.InputMethodInfo
import android.view.inputmethod.InputMethodManager
import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.provider.Settings
import android.widget.EditText
import android.widget.Spinner
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import com.example.whispertoinput.util.MainDispatcherRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Verifies [MainActivity]'s settings cascading logic: selecting a backend from
 * the spinner auto-fills the endpoint/model/language fields and updates the API
 * key link; tapping Apply writes a dirty setting back to the DataStore.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class MainActivitySettingsTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    // Establish a clean, known config so the activity's auto-fill assertions are
    // deterministic regardless of what other test classes left in the shared
    // DataStore (e.g. WhisperTranscriberTest pointing ENDPOINT at a dead server).
    @Before
    fun seedKnownConfig() {
        runBlocking(Dispatchers.IO) {
            ctx.dataStore.edit {
                it[SPEECH_TO_TEXT_BACKEND] = ctx.getString(R.string.settings_option_voxtral)
                it[ENDPOINT] = ctx.getString(R.string.settings_option_voxtral_default_endpoint)
                it[MODEL] = ctx.getString(R.string.settings_option_voxtral_default_model)
                it[LANGUAGE_CODE] = ctx.getString(R.string.settings_option_voxtral_default_language)
                it[API_KEY] = ""
                it[ADD_TRAILING_SPACE] = false
            }
        }
    }

    // buildActivity(...).setup() triggers async DataStore reads; wait until the
    // endpoint field has been auto-filled (the clearest "setup finished" signal).
    private fun buildAndWait(): MainActivity {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val endpoint = activity.findViewById<EditText>(R.id.field_endpoint)
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            if (!endpoint.text.isNullOrEmpty()) return activity
            Thread.sleep(50)
        }
        throw AssertionError("MainActivity setup did not populate the endpoint field in time")
    }

    private fun selectBackend(activity: MainActivity, displayName: String) {
        val spinner = activity.findViewById<Spinner>(R.id.spinner_speech_to_text_backend)
        val index = (0 until spinner.adapter.count).indexOfFirst { spinner.adapter.getItem(it) == displayName }
        spinner.setSelection(index) // Robolectric fires OnItemSelectedListener
    }

    @Test
    fun selecting_backend_autofills_endpoint_model_and_language() {
        val activity = buildAndWait()
        val endpoint = activity.findViewById<EditText>(R.id.field_endpoint)
        val model = activity.findViewById<EditText>(R.id.field_model)
        val language = activity.findViewById<EditText>(R.id.field_language_code)

        selectBackend(activity, ctx.getString(R.string.settings_option_deepgram))

        assertEquals(
            "Deepgram endpoint should be auto-filled",
            ctx.getString(R.string.settings_option_deepgram_default_endpoint),
            endpoint.text.toString()
        )
        assertEquals(
            "Deepgram model should be auto-filled",
            ctx.getString(R.string.settings_option_deepgram_default_model),
            model.text.toString()
        )
        assertEquals(
            "Deepgram language should be auto-filled",
            ctx.getString(R.string.settings_option_deepgram_default_language),
            language.text.toString()
        )
    }

    @Test
    fun api_key_link_points_to_selected_provider() {
        val activity = buildAndWait()
        selectBackend(activity, ctx.getString(R.string.settings_option_deepgram))

        val link = activity.findViewById<android.widget.TextView>(R.id.link_api_key)
        val spannable = link.text as Spannable
        val spans = spannable.getSpans(0, spannable.length, ClickableSpan::class.java)
        assertEquals("link should carry exactly one ClickableSpan", 1, spans.size)

        // Trigger the clickable span and capture the launched intent.
        spans[0].onClick(link)
        val intent = shadowOf(activity).nextStartedActivity
        assertNotNull("selecting a backend should wire an API-key link intent", intent)
        assertTrue(
            "API key link should point to the Deepgram console (was ${intent.data})",
            intent.data.toString().contains("deepgram")
        )
    }

    @Test
    fun apply_writes_dirty_setting_to_datastore() {
        val activity = buildAndWait()
        val endpoint = activity.findViewById<EditText>(R.id.field_endpoint)

        // Mutate the field; the text-watcher marks the setting dirty (setup is done by now).
        val newValue = "https://example.com/custom-endpoint"
        endpoint.setText(newValue)

        // Tap Apply.
        activity.findViewById<android.widget.Button>(R.id.btn_settings_apply)
            .performClick()

        // The write happens on a DataStore IO dispatcher; poll until it lands.
        val deadline = System.currentTimeMillis() + 10_000
        var stored: String? = null
        while (System.currentTimeMillis() < deadline) {
            stored = runBlocking(Dispatchers.IO) { ctx.dataStore.data.first()[ENDPOINT] }
            if (stored == newValue) break
            Thread.sleep(50)
        }
        assertEquals("Apply should persist the edited endpoint to DataStore", newValue, stored)
    }

    @Test
    fun apply_shows_enable_keyboard_prompt_when_ime_not_enabled() {
        val activity = buildAndWait()
        // Robolectric reports no enabled IMEs by default.
        activity.findViewById<android.widget.Button>(R.id.btn_settings_apply)
            .performClick()

        val link = activity.findViewById<android.widget.TextView>(R.id.link_enable_keyboard)
        assertEquals(
            "enable-keyboard prompt should be visible when the IME is disabled",
            View.VISIBLE, link.visibility
        )

        val spannable = link.text as Spannable
        val spans = spannable.getSpans(0, spannable.length, ClickableSpan::class.java)
        assertEquals("enable-keyboard link should carry exactly one ClickableSpan", 1, spans.size)

        // Tapping the link should open the system input-method settings.
        spans[0].onClick(link)
        val intent = shadowOf(activity).nextStartedActivity
        assertNotNull("enable-keyboard link should launch an intent", intent)
        assertEquals(
            "enable-keyboard link should open input-method settings",
            Settings.ACTION_INPUT_METHOD_SETTINGS, intent.action
        )
    }

    @Test
    fun apply_hides_enable_keyboard_prompt_when_ime_enabled() {
        val activity = buildAndWait()

        // Pretend the Whisper Input keyboard is already enabled in system settings.
        val imm = activity.getSystemService(InputMethodManager::class.java)
        val shadow = shadowOf(imm)
        val serviceInfo = activity.packageManager.getServiceInfo(
            ComponentName(activity, WhisperInputService::class.java),
            PackageManager.GET_META_DATA
        )
        val resolveInfo = ResolveInfo().apply { this.serviceInfo = serviceInfo }
        shadow.setEnabledInputMethodInfoList(listOf(InputMethodInfo(activity, resolveInfo)))

        activity.findViewById<android.widget.Button>(R.id.btn_settings_apply)
            .performClick()

        val link = activity.findViewById<android.widget.TextView>(R.id.link_enable_keyboard)
        assertEquals(
            "enable-keyboard prompt should be hidden when the IME is enabled",
            View.GONE, link.visibility
        )
    }
}
