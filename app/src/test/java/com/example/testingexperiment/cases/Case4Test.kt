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
 * Case 4 — asStateFlow(): no relay, Standard / Unconfined winning combination
 *
 * asStateFlow() exposes _uiState directly — no stateIn, no relay coroutine.
 * Turbine subscribes directly to _uiState. The dispatcher pairing at the one
 * and only layer is now:
 *
 *   Producer (incrementAsync → _uiState): StandardTestDispatcher via setMain
 *   Collector (Turbine inside test{}):     UnconfinedTestDispatcher  ← Turbine's internal
 *
 * Standard / Unconfined = WINNING combination.
 *
 * How each update is caught:
 *   _uiState.update(①) marks Turbine's slot NONE→PENDING.
 *   Turbine is Unconfined → runs INLINE inside the update call, before update returns.
 *   Turbine reads ①'s value and stores it in its channel buffer.
 *   Turbine's slot resets to NONE.
 *   _uiState.update(②) marks NONE→PENDING again → Turbine dispatches inline again → reads ②.
 *   Repeat for ③ and ④.
 *   No value is ever overwritten from Turbine's perspective.
 *
 * Trade-off: onStart semantics are gone. Any initialization that was in onStart must
 * be moved to an init {} block. This means it can no longer be tied to subscriber
 * lifecycle (e.g. stops when there are no subscribers).
 *
 * Expected: all 5 emissions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Case4Test {

    @JvmField
    @RegisterExtension
    val mainDispatcher = MainDispatcherExtension() // setMain(StandardTestDispatcher)

    @Test
    fun `CASE 4 - asStateFlow no relay - all 5 emissions observable`() = runTest {
        val viewModel = Case4ViewModel()

        viewModel.uiState.test {
            // ── Initial state ───────────────────────────────────────────────────────────
            // StateFlow replays its current value to new subscribers immediately.
            assertThat(awaitItem()).isEqualTo(CounterUiState(count = 0, isLoading = false))

            // ── Fire incrementAsync ──────────────────────────────────────────────────────
            viewModel.incrementAsync()
            // Each _uiState.update() marks Turbine's slot NONE→PENDING.
            // Turbine is Unconfined → dispatches INLINE inside that update call.
            // The slot resets to NONE before the next update fires.
            // No overwriting possible — Turbine reads each value independently.

            // ── ① (0, true) ─────────────────────────────────────────────────────────────
            val loading1 = awaitItem()
            assertThat(loading1.isLoading).isTrue()
            assertThat(loading1.count).isEqualTo(0)

            // ── ② (0, false) ────────────────────────────────────────────────────────────
            val notLoading = awaitItem()
            assertThat(notLoading.isLoading).isFalse()
            assertThat(notLoading.count).isEqualTo(0)

            // ── ③ (0, true) ─────────────────────────────────────────────────────────────
            val loading2 = awaitItem()
            assertThat(loading2.isLoading).isTrue()
            assertThat(loading2.count).isEqualTo(0)

            // ── ④ after delay(1000) — (1, false) ────────────────────────────────────────
            val done = awaitItem()
            assertThat(done.isLoading).isFalse()
            assertThat(done.count).isEqualTo(1)
        }
    }
}
