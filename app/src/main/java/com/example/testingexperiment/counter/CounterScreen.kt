package com.example.testingexperiment.counter

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun CounterScreen(
    viewModel: CounterViewModel = viewModel(),
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    CounterContent(
        uiState = uiState,
        onIncrement = viewModel::increment,
        onDecrement = viewModel::decrement,
        onIncrementAsync = viewModel::incrementAsync,
        onReset = viewModel::reset,
        modifier = modifier
    )
}

@Composable
fun CounterContent(
    uiState: CounterUiState,
    onIncrement: () -> Unit,
    onDecrement: () -> Unit,
    onIncrementAsync: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (uiState.isLoading) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(16.dp))
        }

        Text(
            text = "Count: ${uiState.count}",
            style = MaterialTheme.typography.headlineLarge
        )

        Spacer(modifier = Modifier.height(24.dp))

        Row {
            Button(onClick = onDecrement) {
                Text("-")
            }
            Spacer(modifier = Modifier.width(16.dp))
            Button(onClick = onIncrement) {
                Text("+")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(onClick = onIncrementAsync) {
            Text("Increment Async")
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(onClick = onReset) {
            Text("Reset")
        }
    }
}

