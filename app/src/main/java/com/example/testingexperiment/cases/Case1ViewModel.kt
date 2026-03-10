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
 * Case 1 — stateIn(viewModelScope): Standard/Standard losing combination
 *
 * The relay coroutine created by stateIn lives inside viewModelScope.
 * In tests, viewModelScope inherits setMain(StandardTestDispatcher).
 * incrementAsync also uses viewModelScope.launch → also Standard.
 *
 * Both the producer (_uiState writer = incrementAsync) and the collector
 * (_uiState reader = relay) are on the SAME StandardTestDispatcher.
 *
 * Standard/Standard = losing combination:
 *   ①②③ fire in one synchronous block with no suspension points.
 *   The relay's slot is marked PENDING at ① and stays PENDING through ②③.
 *   The relay can only drain after delay(1000) suspends incrementAsync.
 *   At that point it reads _uiState.value which holds only ③'s value.
 *   ① and ② are permanently overwritten and never reach Turbine.
 *
 * OBSERVABLE: (0,false) initial  →  (0,true) conflated-③  →  (1,false) ④
 *             = 3 of 5 emissions
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

    // Empty — completes synchronously so the relay subscribes to _uiState at T=0.
    private suspend fun loadInitialCounter() {
        delay(1.seconds)
    }
}
