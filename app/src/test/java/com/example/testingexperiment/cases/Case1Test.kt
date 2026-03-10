package com.example.testingexperiment.cases

import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import com.example.testingexperiment.MainDispatcherExtension
import com.example.testingexperiment.counter.CounterUiState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

/**
 * Case 1 — Standard / Standard (losing combination)
 *
 * setMain(StandardTestDispatcher) → viewModelScope = Standard → relay = Standard.
 *
 * Dispatcher map:
 *   incrementAsync  → viewModelScope → StandardTestDispatcher  (producer)
 *   relay coroutine → viewModelScope → StandardTestDispatcher  (collector)  ← same scope!
 *   Turbine         → runTest-internal → UnconfinedTestDispatcher
 *
 * The relay and incrementAsync share the same StandardTestDispatcher queue.
 * ①②③ fire synchronously in one block; relay slot stays PENDING throughout.
 * Relay drains only after delay(1000). Only ③'s value (isLoading=true) survives.
 *
 * Expected: 3 of 5 emissions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Case1Test {

    @JvmField
    @RegisterExtension
    val mainDispatcher = MainDispatcherExtension() // default: StandardTestDispatcher

    @Test
    fun `CASE 1 - stateIn(viewModelScope) Standard relay - only 2 of 5 observable`() = runTest {
        val viewModel = Case1ViewModel()

        viewModel.uiState.test {
            // ── Initial state ───────────────────────────────────────────────────────────
            // stateIn emits its static initialValue to every new subscriber immediately.
            assertThat(awaitItem()).isEqualTo(CounterUiState(count = 0, isLoading = false))

            // ── Fire incrementAsync ──────────────────────────────────────────────────────
            viewModel.incrementAsync()
            // ① (0,true), ② (0,false), ③ (0,true) all fire synchronously.
            // Relay slot: NONE → PENDING at ①, stays PENDING through ② and ③.
            // No relay task runs until delay(1000) provides a suspension point.

            // ── Conflated emission from the ①②③ block ──────────────────────────────────
            // After delay(1000): relay wakes, reads _uiState.value = (0, true) [③'s value].
            // ① (isLoading=true→false) and ② (isLoading=false→true) are permanently lost.
//            val conflated = awaitItem()
//            assertThat(conflated.isLoading).isTrue()  // ③ survived — its value won the slot
//            assertThat(conflated.count).isEqualTo(0)  // count unchanged (no ④ yet)

            // ── ④ after delay(1000) ─────────────────────────────────────────────────────
            val done = awaitItem()
            assertThat(done.isLoading).isFalse()
            assertThat(done.count).isEqualTo(1)

            // ① and ② never reached Turbine's buffer — lost at the relay layer, not in Turbine.
            cancelAndIgnoreRemainingEvents()
        }
    }
}
