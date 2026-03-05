# Testing StateFlows Built with `stateIn`: The Conflation Problem Nobody Warns You About

*[PERSONAL INTRO — share what led you to discover this issue, what you were building, what went wrong in your tests]*

---

## The Setup

I had a ViewModel that looked roughly like this:

```kotlin
class CounterViewModel(
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
            _uiState.update { it.copy(isLoading = false) }  // ②
            _uiState.update { it.copy(isLoading = true) }   // ③
            delay(1000)
            _uiState.update { it.copy(count = it.count + 1, isLoading = false) } // ④
        }
    }

    suspend fun loadInitialCounter() {
        delay(1.seconds)
        // loads initial data
    }
}
```

*[YOUR COMMENT — why you chose `stateIn` + `onStart` for initial loading, what problem it was solving]*

---

## What I Expected to Test

Including the initial state, `incrementAsync` should produce 5 distinct emissions:

| # | Value |
|---|---|
| 0 | `(count=0, isLoading=false)` — initial |
| 1 | `(count=0, isLoading=true)` — loading starts |
| 2 | `(count=0, isLoading=false)` — briefly not loading |
| 3 | `(count=0, isLoading=true)` — loading again |
| 4 | `(count=1, isLoading=false)` — done |

*[YOUR COMMENT — why you wanted to assert on all intermediate states, what business logic they represented]*

---

## What I Actually Got

With the standard test setup using `MainDispatcherExtension` and `StandardTestDispatcher`:

```kotlin
@ExtendWith(MainDispatcherExtension::class)
class CounterViewModelTest {
    @Test
    fun `incrementAsync shows loading then increments`() = runTest {
        viewModel.uiState.test {
            assertThat(awaitItem().count).isEqualTo(0)   // ✅ passes

            viewModel.incrementAsync()

            val loading = awaitItem()
            assertThat(loading.isLoading).isTrue()       // ❌ times out / wrong value
            // ...
        }
    }
}
```

**Observed: only 2 of 5 emissions.**

| # | Emission | Observed? |
|---|---|---|
| 0 | `(count=0, isLoading=false)` | ✅ |
| 1 | `(count=0, isLoading=true)` | ❌ |
| 2 | `(count=0, isLoading=false)` | ❌ |
| 3 | `(count=0, isLoading=true)` | ❌ |
| 4 | `(count=1, isLoading=false)` | ✅ |

*[YOUR COMMENT — what you thought initially, how confusing/frustrating this was, your first assumptions about what was wrong]*

---

## The First Clue: Swapping `stateIn` for `asStateFlow()`

Out of curiosity I tried replacing `stateIn` with a simple `asStateFlow()` (moving `loadInitialCounter` to `init {}`):

```kotlin
private val _uiState = MutableStateFlow(CounterUiState())
val uiState = _uiState.asStateFlow()

init {
    viewModelScope.launch { loadInitialCounter() }
}
```

All 5 emissions became observable. Same test, same dispatcher setup.

*[YOUR COMMENT — your reaction to this, what it told you, whether this was a viable option for your real code]*

---

## Understanding Why: Two Dispatchers, One Rule

Before getting into what `stateIn` specifically does, it is worth understanding the two test dispatchers involved, because the entire problem comes down to their interaction.

### `StandardTestDispatcher` — the lazy queue

Every coroutine continuation is placed into a FIFO task queue. Nothing runs until the scheduler is explicitly advanced — by a `delay()`, `advanceUntilIdle()`, or a suspension in the test body. A coroutine runs completely uninterrupted until it hits a suspension point; the scheduler cannot insert anything between two non-suspending lines.

This faithfully models how `Dispatchers.Main` works on Android in production: the Looper processes one message at a time, cooperatively. `launch { }` posts a message — it does not preempt what is running.

### `UnconfinedTestDispatcher` — the eager inline runner

When a coroutine is resumed, it runs **immediately and inline** on the current thread before returning to the caller. There is no queue. This is a test-only construct with no direct real-world analogue — it exists purely to make a collector faster than its producer.

### The fundamental rule

> **The only way to avoid `StateFlow` conflation in tests is: collector on `UnconfinedTestDispatcher`, producer on `StandardTestDispatcher`.**

| Producer | Collector | Result |
|---|---|---|
| Standard | Unconfined | ✅ No conflation |
| Standard | Standard | ❌ Conflation |
| Unconfined | Unconfined | ❌ Conflation |
| Unconfined | Standard | ❌ Conflation |

This is because `StateFlow` uses a **binary slot** (not a queue) per collector:
- `NONE` — collector is sleeping
- `PENDING` — collector has been woken up

If the slot is already `PENDING` when a new value is written, the new value silently overwrites the old one. The slot is a flag, not a counter — it has no memory of intermediate values.

Under `StandardTestDispatcher`, the slot stays PENDING across multiple rapid updates because the collector is queued and cannot run between non-suspending lines. Under `UnconfinedTestDispatcher`, the collector runs inline after every update, resetting the slot to NONE before the next update fires.

*[YOUR COMMENT — whether this rule was intuitive to you, how it changed how you think about coroutine testing]*

---

## What `stateIn` Actually Does

This is the part that is not obvious from the documentation.

When you write:

```kotlin
val uiState = _uiState
    .onStart { loadInitialCounter() }
    .stateIn(scope = someScope, ...)
```

`stateIn` silently creates a **relay coroutine** inside `someScope`. Its job is to collect from `_uiState.onStart { ... }` and forward every value into an internal `sharedState: MutableStateFlow<T>`. Collectors like Turbine subscribe to `sharedState` — not to `_uiState` directly.

```
_uiState  ←── incrementAsync
    ↓
[relay coroutine]           ← inserted by stateIn
    ↓
sharedState (internal)
    ↓
[Turbine]
```

With `asStateFlow()` there is no relay:

```
_uiState  ←── incrementAsync
    ↓
[Turbine]
```

The relay introduces an **extra conflation point**. Even if Turbine is Unconfined, it never sees an intermediate value that the relay already conflated before forwarding to `sharedState`.

*[YOUR COMMENT — your reaction when you realised this, whether the "invisible relay" concept surprised you]*

---

## The Root Cause: Who Is Actually Collecting `_uiState`?

In the original setup, `stateIn` is passed `viewModelScope`:

```
incrementAsync   → viewModelScope → StandardTestDispatcher (via setMain)  ← producer
relay            → viewModelScope → StandardTestDispatcher (via setMain)  ← collector of _uiState
Turbine          → internally    → UnconfinedTestDispatcher
```

Both `incrementAsync` and the relay share `viewModelScope`. Both are on `StandardTestDispatcher`. So from `_uiState`'s perspective:

- Producer: Standard
- Collector (relay): Standard
- **Losing combination**

Turbine being Unconfined is irrelevant — the intermediate values are already conflated before they ever reach `sharedState`.

### Why the three synchronous updates are always collapsed

Updates `①②③` have no suspension points between them:

```kotlin
_uiState.update { it.copy(isLoading = true) }   // ①
_uiState.update { it.copy(isLoading = false) }  // ②  ← no suspension
_uiState.update { it.copy(isLoading = true) }   // ③  ← no suspension
delay(1000)                                      // ← first suspension point
```

When ① fires, the relay's slot flips to PENDING and the relay is queued. When ② fires, the slot is already PENDING — the value is overwritten, nothing new is enqueued. Same for ③. When `delay(1000)` finally suspends `incrementAsync`, the relay runs for the first time and reads `_uiState.value` — which is only ③'s value. Updates ① and ② are gone.

*[YOUR COMMENT — whether this "invisible history loss" concerned you for production behaviour, or only for testing]*

---

## Attempts to Fix It

### Attempt 1: `setMain(UnconfinedTestDispatcher)`

If the relay is Unconfined, it should dispatch inline between each update, right?

The problem: both the relay and `incrementAsync` share `viewModelScope`. Changing `setMain` to `UnconfinedTestDispatcher` makes **both** Unconfined. When `incrementAsync` (Unconfined) holds the thread during `①②③`, the relay (also Unconfined) cannot preempt it. **Unconfined/Unconfined is a losing combination.**

*[YOUR COMMENT — whether you tried this, how long it took to realize it wouldn't work]*

### Attempt 2: Scope Injection

Give `stateIn` its own scope so the relay lives separately from `incrementAsync`:

```kotlin
class CounterViewModel(
    sharingDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate
) : ViewModel() {

    private val sharingScope = CoroutineScope(
        viewModelScope.coroutineContext + sharingDispatcher  // inherits Job for lifecycle
    )

    val uiState = _uiState
        .onStart { loadInitialCounter() }
        .stateIn(scope = sharingScope, ...)
}
```

In tests:

```kotlin
viewModel = CounterViewModel(
    sharingDispatcher = UnconfinedTestDispatcher(
        mainDispatcherExtension.testDispatcher.scheduler
    )
)
```

**Result: 3 of 5 emissions observed** — regardless of whether `Standard` or `Unconfined` was passed. The dispatcher injected did not matter.

*[YOUR COMMENT — this "didn't matter" finding was probably surprising — share your reaction]*

#### Why the dispatcher didn't matter here

`loadInitialCounter()` contains `delay(1.seconds)`. This means:

> The relay is parked inside `onStart` when `incrementAsync` fires `①②③`. It has not yet subscribed to `_uiState`. The relay's dispatcher is irrelevant during that window — it isn't listening.

When `onStart`'s `delay` completes (T=1s), the relay finally subscribes to `_uiState` and receives a single snapshot of the current value — which is ③'s residue. Whether the relay is Standard or Unconfined, it sees only one value because `①②③` already happened before it subscribed.

| `onStart` | Relay subscribed during `①②③`? | Dispatcher matters? |
|---|---|---|
| Has `delay` | ❌ No — parked | ❌ Irrelevant |
| No `delay` | ✅ Yes — subscribed at T=0 | ✅ Yes — winning/losing combo applies |

*[YOUR COMMENT — the onStart timing insight, whether this changed how you reason about `onStart` in general]*

### Attempt 3: `yield()` between updates

Add suspension points between `①②③` so the relay has a window:

```kotlin
_uiState.update { it.copy(isLoading = true) }
yield()
_uiState.update { it.copy(isLoading = false) }
yield()
_uiState.update { it.copy(isLoading = true) }
```

**Result: still failed.** `yield()` reshuffles the scheduler queue but does not advance virtual time. The order in which the scheduler picks between `viewModelScope`'s queue and `sharingScope`'s queue is not guaranteed. `incrementAsync` may be picked again before the relay.

### Attempt 4: `delay()` between updates

```kotlin
_uiState.update { it.copy(isLoading = true) }
delay(x)
_uiState.update { it.copy(isLoading = false) }
delay(x)
_uiState.update { it.copy(isLoading = true) }
```

**Result: all 5 caught.** `delay()` parks `incrementAsync` at a future virtual timestamp, removing it from the current runnable queue entirely. The relay has an exclusive window to drain.

| Suspension | Virtual time advances | Guarantees relay runs before next update |
|---|---|---|
| `yield()` | ❌ | ❌ — reshuffles queue, order not guaranteed |
| `delay()` | ✅ | ✅ — parks producer, relay has exclusive window |

*[YOUR COMMENT — whether adding `delay()` to production code for testability felt like a good or bad trade-off]*

---

## A Subtle Side Effect: `onStart` Mutation and `initialValue`

When `loadInitialCounter()` mutates `_uiState` (e.g., increments the count) and contains a suspension point, there is an important timing effect on what Turbine observes.

`stateIn`'s `initialValue` is captured at **ViewModel construction time**. It does not update as `_uiState` changes. So the subscription sequence is:

```
Turbine subscribes
  → stateIn emits initialValue=(count=0) into sharedState immediately
  → relay enters onStart → hits delay → PARKS
  → sharedState is still (count=0)

test calls awaitItem()
  → reads (count=0) ← always, because relay is parked

virtual time advances → onStart resumes → increment() fires
  → _uiState=(count=1)
  → relay subscribes → reads snapshot → forwards (count=1) to sharedState
  → next awaitItem() returns (count=1)
```

**This behavior is strictly deterministic — not flaky:**

| `onStart` before mutation | First `awaitItem()` | Second `awaitItem()` (after advancing time) |
|---|---|---|
| Has suspension point | `initialValue` (non-mutated) | Mutated value |
| No suspension point | Mutated value (overwrites `initialValue` before first read) | Next emission from producer |

The suspension point is a hard guarantee. The relay physically cannot write to `sharedState` until virtual time is advanced. `awaitItem()` always wins because the relay is not racing — it is stopped.

*[YOUR COMMENT — whether this `initialValue` / `onStart` timing was something you actually ran into in your own test, what assertion you had to fix]*

---

## The Final Setup That Works

```kotlin
// Production ViewModel
class CounterViewModel(
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
}

// Test setup
@OptIn(ExperimentalCoroutinesApi::class)
class CounterViewModelTest {

    @JvmField
    @RegisterExtension
    val mainDispatcherExtension = MainDispatcherExtension()

    @BeforeEach
    fun setup() {
        viewModel = CounterViewModel(
            sharingDispatcher = UnconfinedTestDispatcher(
                mainDispatcherExtension.testDispatcher.scheduler
            )
        )
    }
}
```

The shared `scheduler` instance is critical — it ensures `delay()` and `advanceUntilIdle()` control virtual time consistently across both `viewModelScope` and `sharingScope` coroutines.

With `delay()` between updates in production code and `Unconfined` relay in tests, all 5 emissions are observable. Without `delay()` between updates and with a suspended `onStart`, only 3 are observable — which may be acceptable depending on what you actually need to assert.

*[YOUR COMMENT — which approach you ended up going with in your real code and why]*

---

## What I Learned

*[YOUR SUMMARY — key takeaways in your own words, what surprised you most, what you would tell a colleague who hits this same issue]*

---

## References

- [The conflation problem of testing StateFlows — Márton Braun (zsmb.co)](https://zsmb.co/conflating-stateflows/)
- [Testing Kotlin coroutines on Android — Android Developers](https://developer.android.com/kotlin/coroutines/test)
- [Testing StateFlows — Android Developers](https://developer.android.com/kotlin/flow/test#stateflows)
- [Turbine — CashApp](https://github.com/cashapp/turbine)
