package com.example.testingexperiment.counter

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import kotlinx.coroutines.yield
import kotlin.time.Duration.Companion.seconds

data class CounterUiState(
    val count: Int = 0,
    val isLoading: Boolean = false
)

class CounterViewModel(
    sharingDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate
) : ViewModel() {

    // A child scope of viewModelScope but with its own dispatcher.
    // This lets the relay coroutine (inside stateIn) run on a different
    // dispatcher than incrementAsync, which still uses viewModelScope directly.
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

    fun increment() {
        _uiState.update { it.copy(count = it.count + 1) }
    }

    fun decrement() {
        _uiState.update { it.copy(count = it.count - 1) }
    }

    fun incrementAsync() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            _uiState.update { it.copy(isLoading = false) }
            _uiState.update { it.copy(isLoading = true) }
            delay(1000)
            _uiState.update { it.copy(count = it.count + 1, isLoading = false) }
        }
    }

    fun reset() {
        _uiState.value = CounterUiState()
    }

    suspend fun loadInitialCounter() {
//        delay(1.seconds)
        // loaded data no matter what
        increment()
    }
}

