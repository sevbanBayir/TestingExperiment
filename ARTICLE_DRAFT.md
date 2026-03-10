# Testing StateFlows Built with `stateIn`: The Conflation Problem Nobody Warns You About

We were [told](https://proandroiddev.com/loading-initial-data-part-2-clear-all-your-doubts-0f621bfd06a0) to load initial data from network like this :

```kotlin
    private val _uiState = MutableStateFlow(CounterUiState())
    val uiState: StateFlow<CounterUiState> = _uiState
        .onStart { loadInitialCounter() }
        .stateIn(
            scope = sharingScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = _uiState.value
        )
```

But this is problematic when it comes to testing. Normally we would do smt like this to define the top level state of the related screen and update it when necessary :

```kotlin
    private val _uiState = MutableStateFlow(CounterUiState())
    val uiState: StateFlow<CounterUiState> = _uiState.asStateFlow()
        )
```

This is a pure backing property and does not have any side effects. The former one on the other hand, introduces an implicit boundary to a new coroutine and this new coroutine (i will call it the "relay coroutine" from now on)
does not actually subscribe to the upstream flow which is the actual source of truth for our state emissions until the onStart function really finishes. This causes us to miss intermediate state updates to _uiState and effectively lower confidence in our tests. Yes, we don't really need all the emissions all the time but on very specific and vital business logics we definetely do. In this article I will try to reduce (but not completely take it down unfortunately) this side effects and try to explain what actually happens under the hood. So, in some sense this should give you more confidence than not knowing anything and blindly asserting on only the final state of the StateFlows.

---

## The Setup

This is a toy ViewModel from the reproduction repo that simulates frequent updates to state flows and how to test them.

First things first, even without all the drama above, testing stateflows is require special setup and attention as Marton Braun states in his article very clearly: Collector of the stateflow must be faster than the producer. Which means using UnconfinedDispatcher when collecting in our tests and the viewmodel that sends updates must do this in a slower StandardTestDispatcher.

To be able to test this viewmodel: 

```kotlin
class CounterViewModel() : ViewModel() {

    private val _uiState = MutableStateFlow(CounterUiState())
    val uiState: StateFlow<CounterUiState> = _uiState.asStateFlow()

    fun incrementAsync() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }   // ①
            _uiState.update { it.copy(isLoading = false) }  // ②
            _uiState.update { it.copy(isLoading = true) }   // ③
            delay(1000)
            _uiState.update { it.copy(count = it.count + 1, isLoading = false) } // ④
        }
    }
}
```
Our minimal setup would be like this : 

- Set main dispatcher with a JUnit5 extension:

```kotlin
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherExtension(
    val testDispatcher: TestDispatcher = StandardTestDispatcher()
) : BeforeEachCallback, AfterEachCallback {

    override fun beforeEach(context: ExtensionContext?) {
        Dispatchers.setMain(testDispatcher)
    }

    override fun afterEach(context: ExtensionContext?) {
        Dispatchers.resetMain()
    }
}

```
Since this will be the producer of the updates (viewmodelScope == Dispatchers.Main.immediate) we set it to StandardTestDispatcher().

- Collect with a faster coroutine context which is the Turbine in our case. Turbine uses UnconfinedTestDispatcher when it is called from inside a runTest block:

With these setups this test case passes: 

```kotlin
@Test
fun `Case 4 - asStateFlow - all 5 emissions observed`() = runTest {
    val viewModel = CounterViewModel()

    viewModel.uiState.test {
        assertThat(awaitItem()).isEqualTo(CounterUiState()) 

        viewModel.incrementAsync()

        
        assertThat(awaitItem().isLoading).isTrue()   
        assertThat(awaitItem().isLoading).isFalse() 
        assertThat(awaitItem().isLoading).isTrue()   

        val done = awaitItem()                       
        assertThat(done.isLoading).isFalse()
        assertThat(done.count).isEqualTo(1)          
    }
}
```
Which means you can catch every single emission as you expected and which is normal and intuitive.
At this point if you don't need any fine-grained assertion you'd simply go assert on .value property of the StateFlow and if you do need fine-grained access to emission they are simply there deterministically.

---

---

## When Things Get Messed Up

### The Setup

Here is the same viewmodel but let's go try to load initial data as Skydoves' article suggests:


```kotlin
class CounterViewModel() : ViewModel() {

    private val _uiState = MutableStateFlow(CounterUiState())
    val uiState: StateFlow<CounterUiState> = _uiState
        .onStart { loadInitialCounter() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = _uiState.value
        )

    fun incrementAsync() {
        // ... Same content as before.
    }

    suspend fun loadInitialCounter() {
        delay(1.seconds)
        // loads initial data
        // Be careful, we are not yet making updates here
    }
}
```

### The Shocking Truth

With this simple change, the number of emissions you can catch is down to 2. You can only catch the first and last emissions. 
Here you can see the missing ones: 

| # | Emission | Observed? |
|---|---|---|
| 0 | `(count=0, isLoading=false)` | ✅ |
| 1 | `(count=0, isLoading=true)` | ❌ |
| 2 | `(count=0, isLoading=false)` | ❌ |
| 3 | `(count=0, isLoading=true)` | ❌ |
| 4 | `(count=1, isLoading=false)` | ✅ |

## Understanding Why: Two Dispatchers, One Rule

Before getting into what `stateIn` specifically does, it is worth understanding the two test dispatchers involved, because the entire problem comes down to their interaction.

### `StandardTestDispatcher` the lazy queue

Every coroutine continuation is placed into a FIFO task queue. Nothing runs until the scheduler is explicitly advanced — by a `delay()`, `advanceUntilIdle()`, or a suspension in the test body. A coroutine runs completely uninterrupted until it hits a suspension point; the scheduler cannot insert anything between two non-suspending lines.

This faithfully models how `Dispatchers.Main` works on Android in production: the Looper processes one message at a time, cooperatively. `launch { }` posts a message — it does not preempt what is running.

### `UnconfinedTestDispatcher` the eager inline runner

When a coroutine is resumed, it runs **immediately and inline** on the current thread before returning to the caller. There is no queue. This is a test-only construct with no direct real-world analogue — it exists purely to make a collector faster than its producer.

### The fundamental rule

As I stated at the beginning of this article : 

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

---

## What `stateIn` Actually Does

This is the part that is not obvious from the documentation.

When you write:

```kotlin
val uiState = _uiState
    .onStart { loadInitialCounter() }
    .stateIn(scope = someScope, ...)
```

`stateIn` silently creates a **relay coroutine** inside `someScope`. Its job is to collect from `_uiState.onStart { ... }` and forward every value into an internal `sharedState: MutableStateFlow<T>`. Collectors like Turbine subscribe to `sharedState` not to `_uiState` directly.

```
_uiState  ←── incrementAsync
    ↓
[relay coroutine]           ← inserted by stateIn
    ↓
sharedState (internal)
    ↓
[Turbine]
```

With `asStateFlow()` there is no relay coroutine:

```
_uiState  ←── incrementAsync
    ↓
[Turbine]
```

The relay introduces an **extra conflation point**. Even if Turbine is Unconfined, it never sees an intermediate value that the relay already conflated before forwarding to `sharedState`.

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

---

## Attempts to Fix It

### Attempt 1: `setMain(UnconfinedTestDispatcher)`

If the relay is Unconfined, it should dispatch inline between each update, right?

The problem: both the relay and `incrementAsync` share `viewModelScope`. Changing `setMain` to `UnconfinedTestDispatcher` makes **both** Unconfined. When `incrementAsync` (Unconfined) holds the thread during `①②③`, the relay (also Unconfined) cannot preempt it. **Unconfined/Unconfined is a losing combination.**

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

These two last attempts will never make in to production since we must strictly avoid changing prod code in favor of testing. Plus, they may not  be ever needed. 

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
