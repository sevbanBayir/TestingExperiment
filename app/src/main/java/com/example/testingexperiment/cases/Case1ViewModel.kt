package com.example.testingexperiment.cases

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.testingexperiment.counter.CounterUiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

/**
 * Case 1 — stateIn(viewModelScope) + onStart delay: the real production pain point
 *
 * This is the scenario that hurts most in practice.
 * loadInitialCounter() has delay(1.seconds) BEFORE touching _uiState.
 * onStart runs BEFORE the relay subscribes to _uiState.
 * The relay parks inside onStart's delay — it is NOT subscribed yet.
 *
 * Dispatcher map:
 *   incrementAsync  → viewModelScope → StandardTestDispatcher  (producer)
 *   relay coroutine → viewModelScope → StandardTestDispatcher  (collector) ← same scope
 *   Turbine         → runTest-internal → UnconfinedTestDispatcher
 *
 * Timeline at T=0:
 *   relay parks in onStart's delay(1s). Has NOT subscribed to _uiState.
 *   incrementAsync fires ①②③ → _uiState written, relay not listening at all.
 *   delay(1000) parks incrementAsync at T=1s.
 *
 * Timeline at T=1s (both delays expire; relay was parked first → runs first):
 *   onStart resumes → increment() → _uiState.count = 1
 *   relay subscribes → slot NONE→PENDING → relay-collection continuation QUEUED
 *   incrementAsync continuation was already in the T=1s queue BEFORE relay-collection
 *   → incrementAsync runs next: ④ count+1 = 2, isLoading=false → _uiState = (2,false)
 *   → relay slot was PENDING, no new entry (the (1,true) snapshot is overwritten)
 *   relay-collection runs last: reads _uiState.value = (2,false) → forwards to Turbine
 *
 * OBSERVABLE: (0,false) initialValue  →  (2,false) final
 *             = 2 of 5 emissions — the worst case
 *
 * count=2 in the final emission because:
 *   onStart's increment() already bumped count to 1 before relay subscribed,
 *   and ④ then does count+1 = 1+1 = 2.
 */
class Case1ViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(CounterUiState())

    val uiState: StateFlow<CounterUiState> = _uiState
        .onStart { loadInitialCounter() }
        .stateIn(
            scope = viewModelScope,                         // ← relay lives in viewModelScope
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = _uiState.value
        )

    fun incrementAsync() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }   // ① NONE→PENDING on relay slot
            _uiState.update { it.copy(isLoading = false) }  // ② relay slot already PENDING — lost
            _uiState.update { it.copy(isLoading = true) }   // ③ relay slot already PENDING — lost
            delay(1000)                                      // ← first suspension; relay drains here
            _uiState.update { it.copy(count = it.count + 1, isLoading = false) } // ④
        }
    }

    // delay(1.seconds) parks the relay inside onStart BEFORE it subscribes to _uiState.
    // Any _uiState mutation that fires before this delay expires is invisible to the relay.
    private suspend fun loadInitialCounter() {
        delay(1.seconds)                                     // ← relay parks here, not subscribed yet
        _uiState.update { it.copy(count = it.count + 1) }   // count: 0 → 1 (fires at T=1s)
    }
}
