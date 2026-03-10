package com.example.testingexperiment.cases

import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import com.example.testingexperiment.MainDispatcherExtension
import com.example.testingexperiment.counter.CounterUiState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

/**
 * Case 6 — onStart mutation with delay: the relay parks before subscribing to _uiState
 *
 * loadInitialCounter() has delay(1.seconds) BEFORE mutating _uiState.
 * onStart runs before the relay subscribes to _uiState.
 * The relay enters onStart and parks at delay(1.seconds) — it has NOT subscribed yet.
 *
 * This means ANY _uiState update that fires while the relay is parked in onStart is
 * completely INVISIBLE to the relay — not conflated, but literally not observed.
 *
 * The relay's dispatcher is IRRELEVANT for the ①②③ block:
 *   The relay wasn't listening at all. Standard or Unconfined sharingDispatcher
 *   produces the same 3 observed emissions in this scenario.
 *   (Unconfined is used here to show the relay runs inline after subscribing —
 *    which makes the subsequent emissions immediate and easy to reason about.)
 *
 * Surprising assertions:
 *   • loading.count == 1 (not 0): onStart's increment() already ran and bumped count.
 *     The relay subscribed AFTER that mutation, so the snapshot it reads has count=1.
 *   • done.count == 2 (not 1): ④ does count+1, but the count was already 1 from onStart.
 *   • ①②③ are entirely absent: the relay was parked and not listening when they fired.
 *
 * Expected: 3 emissions — (0,false) stateIn's initialValue  /  (1,true) onStart snapshot  /  (2,false) ④
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Case6Test {

    @JvmField
    @RegisterExtension
    val mainDispatcher = MainDispatcherExtension() // setMain(StandardTestDispatcher)

    @Test
    fun `CASE 6 - onStart delay mutation - 3 emissions with count offset`() = runTest {
        val viewModel = Case6ViewModel(
            // Unconfined relay: after subscribing at T=1s, relay forwards every update inline.
            // Note: with Standard as sharingDispatcher the result is identical — the key
            // point is the relay wasn't subscribed during ①②③ regardless of dispatcher.
            sharingDispatcher = UnconfinedTestDispatcher(mainDispatcher.testDispatcher.scheduler)
        )

        viewModel.uiState.test {
            // ── (1) stateIn's static initialValue ───────────────────────────────────────
            // Captured at ViewModel construction time (T=0): (count=0, isLoading=false).
            // This is emitted to new subscribers before relay has done anything.
            val initial = awaitItem()
            assertThat(initial.count).isEqualTo(0)
            assertThat(initial.isLoading).isFalse()

            // ── Fire incrementAsync ──────────────────────────────────────────────────────
            viewModel.incrementAsync()
            // T=0: ①②③ fire and write into _uiState. Relay is PARKED in onStart's delay(1s).
            //      Relay has NOT subscribed to _uiState yet. ①②③ are invisible to the relay.
            // T=0: delay(1000) parks incrementAsync at T=1s.

            // ── (2) onStart snapshot at T=1s ────────────────────────────────────────────
            // T=1s: onStart's delay(1s) expires → increment() runs → _uiState.count = 0+1 = 1
            //       onStart returns → relay SUBSCRIBES to _uiState
            //       _uiState replays current snapshot: (count=1, isLoading=true)
            //           ↑ isLoading=true is ③'s residue still in _uiState from T=0
            //       relay (Unconfined) forwards inline → Turbine sees (1, true)
            val loading = awaitItem()
            assertThat(loading.count).isEqualTo(1)    // count=1 from onStart's increment()
            assertThat(loading.isLoading).isTrue()    // ③'s residue — isLoading was left true

            // ── (3) ④ fires at T=1s ─────────────────────────────────────────────────────
            // T=1s: incrementAsync resumes → ④: count = it.count + 1 = 1 + 1 = 2
            //       (NOT 0+1, because onStart already incremented count to 1)
            //       relay (Unconfined, now subscribed) forwards inline
            val done = awaitItem()
            assertThat(done.isLoading).isFalse()
            assertThat(done.count).isEqualTo(2)       // 1+1=2, not 0+1=1

            // ①②③ emissions are absent — they were never observed by the relay.
            // This is NOT conflation — the relay was never subscribed when they fired.
        }
    }
}
