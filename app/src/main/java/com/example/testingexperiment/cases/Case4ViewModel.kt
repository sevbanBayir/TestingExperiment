package com.example.testingexperiment.cases

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.testingexperiment.counter.CounterUiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Case 4 — asStateFlow(): no relay, Standard/Unconfined winning combination
 *
 * Replacing stateIn with asStateFlow() removes the relay coroutine entirely.
 * Turbine subscribes directly to _uiState. The dispatcher pairing becomes:
 *
 *   Producer (incrementAsync → _uiState): StandardTestDispatcher via setMain
 *   Collector (Turbine, inside runTest.test{}): UnconfinedTestDispatcher (Turbine's internal)
 *
 * Standard/Unconfined = WINNING combination:
 *   When incrementAsync calls _uiState.update(①), Turbine's slot goes NONE→PENDING.
 *   Turbine is Unconfined → it runs INLINE, inside the update call, before update returns.
 *   Turbine reads ①'s value and buffers it. The slot resets to NONE.
 *   The next update (②) marks the slot PENDING again → Turbine runs inline again → reads ②.
 *   Every update gets its own inline dispatch before the next one can overwrite anything.
 *
 * Trade-off: onStart semantics are lost. Any initialization logic that was in onStart
 * must move to an init {} block and be launched explicitly.
 *
 * OBSERVABLE: all 5 emissions — (0,false) (0,true)① (0,false)② (0,true)③ (1,false)④
 */
class Case4ViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(CounterUiState())

    // Direct exposure — no relay coroutine, no stateIn, no onStart.
    val uiState: StateFlow<CounterUiState> = _uiState.asStateFlow()

    init {
        // Initialization that was previously in onStart must move here.
        viewModelScope.launch {
            loadInitialCounter()
        }
    }

    fun incrementAsync() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }   // ① Turbine runs INLINE here
            _uiState.update { it.copy(isLoading = false) }  // ② Turbine runs INLINE here
            _uiState.update { it.copy(isLoading = true) }   // ③ Turbine runs INLINE here
            delay(1000)
            _uiState.update { it.copy(count = it.count + 1, isLoading = false) } // ④ Turbine inline
        }
    }

    // It can suspend — but now there is no relay to park.
    // loadInitialCounter runs concurrently with anything else launched from viewModelScope.
    private suspend fun loadInitialCounter() {
        delay(2000) // simulate async load — does NOT affect _uiState in this case
    }
}
