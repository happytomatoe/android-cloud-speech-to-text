package com.example.whispertoinput.keyboard

import android.content.Context
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import com.example.whispertoinput.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies BackspaceButton's touch-driven long-press quick-delete:
 * a short tap fires the callback once; a long press repeats every
 * QUICK_BACKSPACE_DELAY (80ms) after DELAY_BEFORE_QUICK_BACKSPACE (600ms);
 * lifting the finger (ACTION_UP) stops the repeats.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class BackspaceButtonTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private fun ctx(): Context = ApplicationProvider.getApplicationContext()
    private fun attrs() = Robolectric.buildAttributeSet().build()
    private fun down() = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 0f, 0f, 0)
    private fun up() = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_UP, 0f, 0f, 0)

    @Test
    fun short_tap_fires_once() {
        val btn = BackspaceButton(ctx(), attrs())
        var count = 0
        btn.setBackspaceCallback { count++ }
        btn.dispatchTouchEvent(down())
        btn.dispatchTouchEvent(up())
        assertEquals("short tap must invoke callback exactly once", 1, count)
    }

    @Test
    fun long_press_repeats_then_stops() {
        val btn = BackspaceButton(ctx(), attrs())
        var count = 0
        btn.setBackspaceCallback { count++ }
        btn.dispatchTouchEvent(down())                 // count=1 + starts long-press detector
        mainRule.dispatcher.scheduler.advanceTimeBy(600) // crosses DELAY_BEFORE_QUICK_BACKSPACE
        mainRule.dispatcher.scheduler.advanceTimeBy(80)  // quick backspace #1
        mainRule.dispatcher.scheduler.advanceTimeBy(80)  // quick backspace #2
        assertEquals("initial tap + 3 repeats", 4, count)
        btn.dispatchTouchEvent(up())                   // abort detector
        mainRule.dispatcher.scheduler.advanceTimeBy(1000)
        assertEquals("no repeats after lift", 4, count)
    }
}
