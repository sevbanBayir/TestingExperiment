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
import kotlin.time.Duration.Companion.seconds

/**
 * Case 6 — onStart mutation: relay parks before subscribing to _uiState
 *
 * loadInitialCounter() suspends (delay(1.seconds)) BEFORE mutating _uiState.
 * onStart runs BEFORE the relay subscribes to _uiState. The relay enters onStart
 * and immediately parks at delay(1.seconds). At this point the relay has NOT yet
 * subscribed to _uiState — it will only subscribe after onStart returns.
 *
 * Consequence: any _uiState updates that fire while the relay is parked inside onStart
 * are completely invisible to the relay. Not conflated — literally not observed.
 *
 * Execution trace (with incrementAsync called at T=0):
 *   T=0:  relay parks in onStart's delay(1s). NOT subscribed to _uiState.
 *   T=0:  incrementAsync runs: ①②③ write to _uiState. Relay not listening.
 *   T=0:  delay(1000) parks incrementAsync at T=1s.
 *   T=1s: onStart resumes → increment() → _uiState.count = 0+1 = 1
 *         onStart returns → relay SUBSCRIBES to _uiState
 *         _uiState replays current value: (count=1, isLoading=true)  ← ③'s residue
 *         relay (Unconfined) forwards inline → Turbine sees (1, true)
 *   T=1s: incrementAsync resumes → ④: count = 1+1 = 2 (NOT 0+1=1 !)
 *         relay (Unconfined, now subscribed) forwards inline → Turbine sees (2, false)
 *
 * Key conclusions:
 *   1. ①②③ are entirely absent — relay was parked, not conflated.
 *   2. The first item Turbine sees from incrementAsync is NOT count=0 — onStart's
 *      mutation already ran and left count=1 in _uiState before relay subscribed.
 *   3. ④'s count is 2, not 1, because onStart's increment() already bumped it.
 *   4. stateIn's initialValue (count=0) is a STATIC snapshot from construction time —
 *      it doesn't track onStart mutations.
 *   5. The relay's dispatcher is IRRELEVANT here — the relay wasn't listening at all
 *      during ①②③, so Standard vs Unconfined changes nothing for those emissions.
 *
 * OBSERVABLE: (0,false) stateIn initialValue  →  (1,true) onStart snapshot  →  (2,false) ④
 *             = 3 emissions, with count values offset by onStart's mutation
 */
class Case6ViewModel(
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
            initialValue = _uiState.value                   // captured at T=0: (count=0, isLoading=false)
        )

    fun incrementAsync() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }   // ① fires at T=0; relay not subscribed yet
            _uiState.update { it.copy(isLoading = false) }  // ② fires at T=0; relay not subscribed yet
            _uiState.update { it.copy(isLoading = true) }   // ③ fires at T=0; relay not subscribed yet
            delay(1000)                                      // parks at T=1s; relay subscribes at T=1s
            _uiState.update { it.copy(count = it.count + 1, isLoading = false) }  // ④ count=1+1=2
        }
    }

    private suspend fun loadInitialCounter() {
        delay(1.seconds)             // ← relay parks HERE before subscribing to _uiState
        _uiState.update { it.copy(count = it.count + 1) }  // count: 0 → 1
    }
}
