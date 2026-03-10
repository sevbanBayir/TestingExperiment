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
 * Case 1 — Standard / Standard + onStart delay: the real production pain point (2 of 5)
 *
 * setMain(StandardTestDispatcher) → viewModelScope = Standard → relay = Standard.
 *
 * Dispatcher map:
 *   incrementAsync  → viewModelScope → StandardTestDispatcher  (producer)
 *   relay coroutine → viewModelScope → StandardTestDispatcher  (collector)  ← same scope
 *   Turbine         → runTest-internal → UnconfinedTestDispatcher
 *
 * The relay parks inside onStart's delay(1.seconds) before subscribing to _uiState.
 * ①②③ fire at T=0 while the relay is not listening — invisible, not conflated.
 * Both delays expire at T=1s. Relay's onStart runs first (was parked earlier), subscribes,
 * and queues its first collection. But incrementAsync's T=1s continuation was already in
 * the queue BEFORE relay-collection was just appended.
 * ④ fires first → writes (2,false). Relay collection runs after → reads only (2,false).
 * The (1,true) loading snapshot is overwritten before the relay even runs once.
 *
 * Expected: 2 of 5 emissions — only initial and final.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Case1Test {

    @JvmField
    @RegisterExtension
    val mainDispatcher = MainDispatcherExtension() // default: StandardTestDispatcher

    @Test
    fun `CASE 1 - stateIn(viewModelScope) onStart delay - only 2 of 5 observable`() = runTest {
        val viewModel = Case1ViewModel()

        viewModel.uiState.test {
            // ── (1) stateIn's static initialValue ───────────────────────────────────────
            // Emitted before the relay has done anything.
            // The relay has already entered onStart and is parked at delay(1.seconds).
            assertThat(awaitItem()).isEqualTo(CounterUiState(count = 0, isLoading = false))

            // ── Fire incrementAsync ──────────────────────────────────────────────────────
            viewModel.incrementAsync()
            // T=0: ①②③ fire — relay is parked in onStart, NOT subscribed. Invisible.
            // T=0: delay(1000) parks incrementAsync at T=1s.
            //
            // T=1s: relay's onStart delay expires (parked first → runs first):
            //   • increment() → _uiState = (1, true)  [isLoading=true: ③'s residue]
            //   • relay subscribes → slot NONE→PENDING → relay-collection QUEUED
            // T=1s: incrementAsync continuation was already queued BEFORE relay-collection:
            //   • ④: _uiState = (2, false)  [relay slot stays PENDING — nothing new queued]
            // T=1s: relay-collection runs last:
            //   • reads _uiState.value = (2, false)  ← (1,true) snapshot already overwritten
            //   • forwards to sharedState → Turbine receives (2,false)

            // ── (2) Final state — the only other emission ────────────────────────────────
            // ①②③ were invisible (relay not subscribed).
            // The (1,true) loading snapshot was overwritten before relay's first collection.
            // Not conflation — the relay was never there for any of the intermediate states.
            val done = awaitItem()
            assertThat(done.isLoading).isFalse()
            assertThat(done.count).isEqualTo(2) // 0 (initial) +1 (onStart) +1 (④) = 2
        }
    }
}
