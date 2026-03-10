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
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

/**
 * Case 3 — stateIn(sharingScope) with Standard sharingDispatcher
 *
 * The relay is moved to a separate sharingScope with an injected StandardTestDispatcher
 * that shares the same TestCoroutineScheduler as viewModelScope.
 *
 * Dispatcher map:
 *   incrementAsync  → viewModelScope → StandardTestDispatcher (setMain)   (producer)
 *   relay coroutine → sharingScope   → StandardTestDispatcher (injected)  (collector)
 *   Turbine         → runTest-internal → UnconfinedTestDispatcher
 *
 * Why this is an improvement vs Case 1:
 *   The relay and incrementAsync now have INDEPENDENT task queues. The scheduler
 *   interleaves tasks from both queues, giving the relay a chance to run between
 *   incrementAsync's suspension points.
 *
 * Why it still doesn't catch all 5:
 *   ①②③ are a synchronous block — no suspension points between them.
 *   No coroutine (regardless of scope/dispatcher) can be dispatched between
 *   consecutive non-suspending lines. Relay still conflates ①②③ to one emission.
 *
 * KEY FINDING: injecting StandardTestDispatcher OR UnconfinedTestDispatcher as
 * sharingDispatcher produces the SAME result (3/5) when loadInitialCounter has
 * no suspension before increments fire. It is the SEPARATE SCOPE that matters,
 * not the specific dispatcher value.
 *
 * Expected: 3 of 5 emissions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Case3Test {

    @JvmField
    @RegisterExtension
    val mainDispatcher = MainDispatcherExtension() // setMain(StandardTestDispatcher)

    @Test
    fun `CASE 3 - stateIn(sharingScope) Standard sharingDispatcher - still 3 of 5`() = runTest {
        val viewModel = Case3ViewModel(
            // Share the same scheduler so runTest's advanceUntilIdle() controls both scopes.
            sharingDispatcher = StandardTestDispatcher(mainDispatcher.testDispatcher.scheduler)
        )

        viewModel.uiState.test {
            // ── Initial state ───────────────────────────────────────────────────────────
            assertThat(awaitItem()).isEqualTo(CounterUiState(count = 0, isLoading = false))

            // ── Fire incrementAsync ──────────────────────────────────────────────────────
            viewModel.incrementAsync()
            // ①②③ execute as one synchronous block. Relay slot: PENDING throughout.
            // The relay has an independent queue but still cannot run between
            // non-suspending lines inside incrementAsync's coroutine.

            // ── Conflated emission from the ①②③ block ──────────────────────────────────
            // After delay(1000): relay wakes and reads _uiState.value = (0,true) [③'s value].
            val conflated = awaitItem()
            assertThat(conflated.isLoading).isTrue()  // ③ survived; ①② lost to relay-layer conflation
            assertThat(conflated.count).isEqualTo(0)

            // ── ④ after delay(1000) ─────────────────────────────────────────────────────
            val done = awaitItem()
            assertThat(done.isLoading).isFalse()
            assertThat(done.count).isEqualTo(1)

            cancelAndIgnoreRemainingEvents()
        }
    }
}
