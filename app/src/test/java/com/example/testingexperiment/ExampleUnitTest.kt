package com.example.testingexperiment

import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * Example unit tests demonstrating JUnit5, AssertK, Coroutines Test, and Turbine.
 */
class ExampleUnitTest {

    @Nested
    @DisplayName("JUnit5 + AssertK")
    inner class AssertKTests {

        @Test
        @DisplayName("basic assertion with AssertK")
        fun `addition is correct`() {
            assertThat(2 + 2).isEqualTo(4)
        }

        @ParameterizedTest
        @ValueSource(ints = [1, 2, 3, 4, 5])
        @DisplayName("parameterized test")
        fun `number is positive`(number: Int) {
            assertThat(number > 0).isTrue()
        }
    }

    @Nested
    @DisplayName("Coroutines Test")
    inner class CoroutinesTests {

        @Test
        @DisplayName("runTest skips delay")
        fun `coroutine test with delay`() = runTest {
            val start = testScheduler.currentTime
            delay(1000)
            val elapsed = testScheduler.currentTime - start
            assertThat(elapsed).isEqualTo(1000)
        }
    }

    @Nested
    @DisplayName("Turbine Flow Testing")
    inner class TurbineTests {

        @Test
        @DisplayName("test flow emissions with Turbine")
        fun `flow emits correct values`() = runTest {
            val testFlow = flow {
                emit(1)
                emit(2)
                emit(3)
            }

            testFlow.test {
                assertThat(awaitItem()).isEqualTo(1)
                assertThat(awaitItem()).isEqualTo(2)
                assertThat(awaitItem()).isEqualTo(3)
                awaitComplete()
            }
        }
    }
}
