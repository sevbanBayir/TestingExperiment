package com.example.testingexperiment.cases

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.testingexperiment.counter.CounterUiState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Case 3 — stateIn(sharingScope) with injectable dispatcher: scope injection improvement
 *
 * The relay coroutine is moved to a dedicated sharingScope whose dispatcher can be injected.
 * In tests, StandardTestDispatcher(shared scheduler) is injected — the relay gets its own
 * independent task queue separate from incrementAsync's viewModelScope queue.
 *
 * Why this helps vs Case 1:
 *   In Case 1, relay and incrementAsync share the same viewModelScope FIFO queue.
 *   Whichever is first in the queue runs uninterrupted. With a separate queue, the
 *   scheduler interleaves tasks between the two scopes differently — the relay can
 *   drain in between suspension points of incrementAsync.
 *
 * Why it still doesn't catch all 5:
 *   ①②③ form an UNINTERRUPTED synchronous block. No coroutine can be dispatched
 *   between lines with no suspension points, regardless of scope or dispatcher.
 *   The relay still conflates ①②③ into one emission (③'s value, the last written).
 *   Only ③'s value reaches Turbine before ④ fires.
 *
 * KEY FINDING: injecting Standard or Unconfined as sharingDispatcher produces the SAME
 * result (3/5) when loadInitialCounter has no suspension point before the _uiState
 * updates from incrementAsync fire. The structural change (separate scope) matters;
 * the specific dispatcher value does not.
 *
 * OBSERVABLE: (0,false) initial  →  (0,true) conflated-③  →  (1,false) ④
 *             = 3 of 5 emissions
 */
class Case3ViewModel(
    sharingDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate
) : ViewModel() {

    // Inherits viewModelScope's Job so the relay is cancelled when ViewModel is cleared.
    // The dispatcher is injected independently, giving the relay its own task queue.
    private val sharingScope = CoroutineScope(
        viewModelScope.coroutineContext + sharingDispatcher
    )

    private val _uiState = MutableStateFlow(CounterUiState())

    val uiState: StateFlow<CounterUiState> = _uiState
        .onStart { loadInitialCounter() }
        .stateIn(
            scope = sharingScope,                           // ← relay lives in sharingScope
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = _uiState.value
        )

    fun incrementAsync() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }   // ①
            _uiState.update { it.copy(isLoading = false) }  // ②  — relay slot already PENDING
            _uiState.update { it.copy(isLoading = true) }   // ③  — relay slot already PENDING
            delay(1000)                                      // ← relay drains here, reads ③'s value
            _uiState.update { it.copy(count = it.count + 1, isLoading = false) } // ④
        }
    }

    private suspend fun loadInitialCounter() = Unit
}
