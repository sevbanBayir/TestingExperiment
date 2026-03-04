package com.example.testingexperiment.counter

import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import com.example.testingexperiment.MainDispatcherExtension
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

@OptIn(ExperimentalCoroutinesApi::class)
class CounterViewModelTest {

    @JvmField
    @RegisterExtension
    val mainDispatcherExtension = MainDispatcherExtension()

    private lateinit var viewModel: CounterViewModel

    @BeforeEach
    fun setup() {
        // The relay coroutine (inside stateIn) gets UnconfinedTestDispatcher:
        // it dispatches immediately when _uiState updates, so every intermediate
        // value reaches sharedState before the next update overwrites it.
        //
        // incrementAsync still uses viewModelScope directly → StandardTestDispatcher
        // via setMain → it is the "slow" producer.
        //
        // Result: Standard producer / Unconfined relay = winning combination.
        //
        // Sharing the same scheduler ensures delay() and advanceUntilIdle() in
        // runTest control virtual time across both coroutines.
        viewModel = CounterViewModel(
            sharingDispatcher = UnconfinedTestDispatcher(
                mainDispatcherExtension.testDispatcher.scheduler
            )
        )
    }


    @Test
    @DisplayName("incrementAsync shows loading then increments")
    fun `incrementAsync shows loading then increments`() = runTest {
        viewModel.uiState.test {
            val initial = awaitItem()
            assertThat(initial.count).isEqualTo(1)
            assertThat(initial.isLoading).isFalse()

            viewModel.incrementAsync()

            val loading = awaitItem()
            assertThat(loading.isLoading).isTrue()
            println(loading.count)

            val notLoading = awaitItem()
            assertThat(notLoading.isLoading).isFalse()
            assertThat(notLoading.count).isEqualTo(1)

            val loading2 = awaitItem()
            assertThat(loading2.isLoading).isTrue()

            val done = awaitItem()
            assertThat(done.isLoading).isFalse()
            assertThat(done.count).isEqualTo(2)
        }
    }
}

