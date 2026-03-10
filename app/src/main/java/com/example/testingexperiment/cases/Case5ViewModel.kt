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
 * Case 5 — stateIn(sharingScope) + delay() between every update: hard temporal barriers
 *
 * The root problem in Cases 1-3 was that ①②③ form an uninterrupted synchronous block —
 * no coroutine can be dispatched between two consecutive non-suspending lines, regardless
 * of scope or dispatcher configuration.
 *
 * Solution: insert delay() between each update. delay() is a HARD TEMPORAL BARRIER:
 *   - It parks incrementAsync at a specific virtual timestamp (e.g. T+1ms).
 *   - The coroutine is removed from the runnable queue entirely.
 *   - The relay's queue is now the ONLY thing left to run.
 *   - The relay drains, reads the current _uiState value, forwards to sharedState.
 *   - Virtual time then advances to T+1ms → incrementAsync resumes → next update fires.
 *
 * Why delay() works but yield() doesn't:
 *   yield() re-queues at the END of the current virtual-time queue. In the same virtual
 *   time slice, the scheduler may pick incrementAsync again before the relay — no ordering
 *   guarantee. delay() parks at a FUTURE time; incrementAsync cannot run again until time
 *   explicitly advances, so the relay has an exclusive window to run.
 *
 * Trade-off: adds artificial delays in production code, changing observable timing.
 *
 * OBSERVABLE: all 5 emissions — (0,false) (0,true)① (0,false)② (0,true)③ (1,false)④
 */
class Case5ViewModel(
    sharingDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate
) : ViewModel() {

    private val sharingScope = CoroutineScope(
        viewModelScope.coroutineContext + sharingDispatcher
    )

    private val _uiState = MutableStateFlow(CounterUiState())

    val uiState: StateFlow<CounterUiState> = _uiState
        .onStart { loadInitialCounter() }
        .stateIn(
            scope = sharingScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = _uiState.value
        )

    fun incrementAsync() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }   // ①
            delay(1)                                         // ← parks incrementAsync; relay drains ① exclusively
            _uiState.update { it.copy(isLoading = false) }  // ②
            delay(1)                                         // ← parks incrementAsync; relay drains ② exclusively
            _uiState.update { it.copy(isLoading = true) }   // ③
            delay(1000)                                      // ← parks incrementAsync; relay drains ③ exclusively
            _uiState.update { it.copy(count = it.count + 1, isLoading = false) } // ④
        }
    }

    private suspend fun loadInitialCounter() = Unit
}
