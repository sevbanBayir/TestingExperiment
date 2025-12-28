package com.example.testingexperiment.counter

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CounterUiState(
    val count: Int = 0,
    val isLoading: Boolean = false
)

class CounterViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(CounterUiState())
    val uiState: StateFlow<CounterUiState> = _uiState.stateIn(
        scope = viewModelScope,
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
}

