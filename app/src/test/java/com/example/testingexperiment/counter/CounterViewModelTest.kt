package com.example.testingexperiment.counter

import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import com.example.testingexperiment.MainDispatcherExtension
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@OptIn(ExperimentalCoroutinesApi::class)
@ExtendWith(MainDispatcherExtension::class)
class CounterViewModelTest {

    private lateinit var viewModel: CounterViewModel

    @BeforeEach
    fun setup() {
        viewModel = CounterViewModel()
    }

    @Nested
    @DisplayName("Synchronous operations")
    inner class SyncOperations {

        @Test
        @DisplayName("initial state is 0")
        fun `initial state is zero`() {
            assertThat(viewModel.uiState.value.count).isEqualTo(0)
        }

        @Test
        @DisplayName("increment increases count by 1")
        fun `increment increases count`() {
            viewModel.increment()
            assertThat(viewModel.uiState.value.count).isEqualTo(1)
        }

        @Test
        @DisplayName("decrement decreases count by 1")
        fun `decrement decreases count`() {
            viewModel.decrement()
            assertThat(viewModel.uiState.value.count).isEqualTo(-1)
        }

        @Test
        @DisplayName("reset sets count to 0")
        fun `reset sets count to zero`() {
            viewModel.increment()
            viewModel.increment()
            viewModel.reset()
            assertThat(viewModel.uiState.value.count).isEqualTo(0)
        }
    }

    @Nested
    @DisplayName("Async operations")
    inner class AsyncOperations {

        @Test
        @DisplayName("incrementAsync shows loading then increments")
        fun `incrementAsync shows loading then increments`() = runTest {
            viewModel.uiState.test {
                assertThat(awaitItem().count).isEqualTo(0)

                viewModel.incrementAsync()

                val loading = awaitItem()
                assertThat(loading.isLoading).isTrue()

                val notLoading = awaitItem()
                assertThat(notLoading.isLoading).isFalse()


                val loading2 = awaitItem()
                assertThat(loading2.isLoading).isTrue()

                val done = awaitItem()
                assertThat(done.isLoading).isFalse()
                assertThat(done.count).isEqualTo(1)
            }
        }

        @Test
        @DisplayName("incrementAsync completes with advanceUntilIdle")
        fun `incrementAsync with advanceUntilIdle`() = runTest {
            viewModel.incrementAsync()
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.count).isEqualTo(1)
            assertThat(viewModel.uiState.value.isLoading).isFalse()
        }
    }
}

