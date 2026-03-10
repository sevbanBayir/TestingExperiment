package com.example.testingexperiment.cases

import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import com.example.testingexperiment.MainDispatcherExtension
import com.example.testingexperiment.counter.CounterUiState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

/**
 * Case 2 — Unconfined / Unconfined (losing combination despite Unconfined relay)
 *
 * setMain(UnconfinedTestDispatcher) → viewModelScope = Unconfined → relay = Unconfined.
 *
 * Dispatcher map:
 *   incrementAsync  → viewModelScope → UnconfinedTestDispatcher  (producer)
 *   relay coroutine → viewModelScope → UnconfinedTestDispatcher  (collector)  ← SAME scope
 *   Turbine         → runTest-internal → UnconfinedTestDispatcher
 *
 * ⚠️ Scheduler sharing is mandatory here.
 *   UnconfinedTestDispatcher has its own virtual clock (TestCoroutineScheduler).
 *   If setMain and runTest use DIFFERENT schedulers, delay() inside viewModelScope
 *   parks on a clock that runTest never advances — the test would hang forever.
 *   Solution: create ONE shared scheduler and pass it to BOTH setMain's dispatcher
 *   and runTest's context dispatcher.
 *
 * Hypothesis being disproved: "Unconfined relay dispatches inline between each update."
 *
 * Why it still fails:
 *   Unconfined dispatches immediately on the CURRENT thread — but only when the thread
 *   is free. incrementAsync holds the thread during ①②③ (it is currently executing).
 *   The relay is queued and can only run when incrementAsync suspends at delay(1000).
 *   "Immediately" ≠ "preemptively". Unconfined/Unconfined = losing combination.
 *
 * Result is identical to Case 1 despite a completely different dispatcher.
 * Expected: 3 of 5 emissions (same as Case 1).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Case2Test {

    // One shared scheduler so delay() in viewModelScope and runTest use the same virtual clock.
    private val sharedScheduler = TestCoroutineScheduler()

    @JvmField
    @RegisterExtension
    val mainDispatcher = MainDispatcherExtension(
        UnconfinedTestDispatcher(sharedScheduler) // setMain(Unconfined) — viewModelScope = Unconfined
    )

    @Test
    fun `CASE 2 - setMain(Unconfined) - still only 3 of 5, Unconfined does not help`() =
        // runTest uses StandardTestDispatcher with the SAME shared scheduler.
        // This ensures delay() inside viewModelScope advances with runTest's virtual time.
        runTest(StandardTestDispatcher(sharedScheduler)) {
            val viewModel = Case2ViewModel()

            viewModel.uiState.test {
                // ── Initial state ─────────────────────────────────────────────────────────
                assertThat(awaitItem()).isEqualTo(CounterUiState(count = 0, isLoading = false))

                // ── Fire incrementAsync ────────────────────────────────────────────────────
                // viewModelScope is Unconfined → launch body runs EAGERLY.
                // ①②③ execute inline before this line returns.
                // The relay is also Unconfined but cannot preempt a currently-running coroutine.
                viewModel.incrementAsync()

                // ── Conflated emission from ①②③ ──────────────────────────────────────────
                // delay(1000) suspends incrementAsync. Relay finally drains — reads ③'s value.
                // ① and ② are lost even though the relay is Unconfined.
                val conflated = awaitItem()
                assertThat(conflated.isLoading).isTrue()  // ③ survived; ① and ② were overwritten
                assertThat(conflated.count).isEqualTo(0)

                // ── ④ after delay(1000) ───────────────────────────────────────────────────
                val done = awaitItem()
                assertThat(done.isLoading).isFalse()
                assertThat(done.count).isEqualTo(1)

                cancelAndIgnoreRemainingEvents()
            }
        }
}
