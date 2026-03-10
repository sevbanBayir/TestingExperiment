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

/**
 * Case 2 — stateIn(viewModelScope): Unconfined/Unconfined losing combination
 *
 * The ViewModel code is intentionally identical to Case1ViewModel.
 * The difference is in the test: the test sets setMain(UnconfinedTestDispatcher),
 * making viewModelScope (and therefore BOTH incrementAsync and the relay) Unconfined.
 *
 * Hypothesis being disproved: "if the relay is Unconfined it can dispatch inline
 * between each _uiState.update call."
 *
 * Why it still fails:
 *   Unconfined dispatches a resumed coroutine immediately on the CURRENT thread.
 *   But "immediately" only means "when the thread is free", not "preempting the
 *   currently-running coroutine". incrementAsync holds the thread during ①②③.
 *   The relay (also Unconfined) is queued and can only run after incrementAsync
 *   suspends at delay(1000). By then _uiState holds only ③'s value.
 *
 * Unconfined/Unconfined = losing combination (same result as Standard/Standard).
 *
 * OBSERVABLE: (0,false) initial  →  (0,true) conflated-③  →  (1,false) ④
 *             = 3 of 5 emissions  (identical to Case 1 despite different dispatcher)
 */
class Case2ViewModel : ViewModel() {

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
            _uiState.update { it.copy(isLoading = true) }   // ①
            _uiState.update { it.copy(isLoading = false) }  // ②
            _uiState.update { it.copy(isLoading = true) }   // ③
            delay(1000)
            _uiState.update { it.copy(count = it.count + 1, isLoading = false) } // ④
        }
    }

    private suspend fun loadInitialCounter() = Unit
}
