package com.example.whispertoinput.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Swaps [Dispatchers.Main] for a [StandardTestDispatcher] so coroutine-based
 * code runs deterministically under JUnit/Robolectric. Coroutines are queued
 * and only execute when the test advances the scheduler
 * (advanceTimeBy / advanceUntilIdle / runCurrent), giving precise virtual-time
 * control over timing (e.g. BackspaceButton's delay()-driven long-press)
 * without real Thread.sleep.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val dispatcher: TestDispatcher =
        StandardTestDispatcher()
) : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(dispatcher)
    override fun finished(description: Description) = Dispatchers.resetMain()
}
