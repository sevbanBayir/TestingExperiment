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
 * Case 5 — delay() between updates: hard temporal barriers expose all 5 emissions
 *
 * The root problem in Cases 1-3: ①②③ form a synchronous uninterrupted block.
 * No coroutine can be dispatched between non-suspending lines.
 *
 * Fix: insert delay(x) between each update in incrementAsync.
 * delay() parks incrementAsync at a specific virtual timestamp, removing it from
 * the runnable queue entirely. The relay then has an EXCLUSIVE window to drain.
 *
 * Why delay() works but yield() doesn't:
 *   delay(1) → parks at T+1ms. incrementAsync cannot run again until time advances.
 *              Relay has guaranteed exclusive access to the scheduler queue.
 *   yield()  → re-queues at end of CURRENT virtual-time queue (T+0ms). Scheduler
 *              may pick incrementAsync again before the relay. No ordering guarantee.
 *
 * Here we inject StandardTestDispatcher as sharingDispatcher (not Unconfined) —
 * the delay() calls are what make this work, not the dispatcher. You could use
 * Unconfined and get the same result.
 *
 * Expected: all 5 emissions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Case5Test {

    @JvmField
    @RegisterExtension
    val mainDispatcher = MainDispatcherExtension() // setMain(StandardTestDispatcher)

    @Test
    fun `CASE 5 - delay() between updates - hard barriers expose all 5 emissions`() = runTest {
        val viewModel = Case5ViewModel(
            // sharingDispatcher can be Standard or Unconfined here — delay() is the actual fix.
            sharingDispatcher = StandardTestDispatcher(mainDispatcher.testDispatcher.scheduler)
        )

        viewModel.uiState.test {
            // ── Initial state ───────────────────────────────────────────────────────────
            assertThat(awaitItem()).isEqualTo(CounterUiState(count = 0, isLoading = false))

            // ── Fire incrementAsync ──────────────────────────────────────────────────────
            viewModel.incrementAsync()
            // ① fires → relay queued
            // delay(1): parks incrementAsync at T+1ms → relay has exclusive window → drains ①

            // ── ① (0, true) — relay drained its window after first delay(1) ───────────
            val loading1 = awaitItem()
            assertThat(loading1.isLoading).isTrue()
            assertThat(loading1.count).isEqualTo(0)

            // T+1ms: ② fires → relay queued
            // delay(1): parks incrementAsync at T+2ms → relay has exclusive window → drains ②

            // ── ② (0, false) — relay drained its window after second delay(1) ──────────
            val notLoading = awaitItem()
            assertThat(notLoading.isLoading).isFalse()
            assertThat(notLoading.count).isEqualTo(0)

            // T+2ms: ③ fires → relay queued
            // delay(1000): parks incrementAsync at T+1002ms → relay drains ③

            // ── ③ (0, true) — relay drained its window after delay(1000) starts ────────
            val loading2 = awaitItem()
            assertThat(loading2.isLoading).isTrue()
            assertThat(loading2.count).isEqualTo(0)

            // T+1002ms: ④ fires → relay drains ④

            // ── ④ (1, false) ─────────────────────────────────────────────────────────────
            val done = awaitItem()
            assertThat(done.isLoading).isFalse()
            assertThat(done.count).isEqualTo(1)
        }
    }
}
