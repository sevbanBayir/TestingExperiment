# StateFlow Testing: Conflation, Relay Coroutines & Dispatcher Mechanics

## How `StandardTestDispatcher` and `UnconfinedTestDispatcher` Work — and How They Differ From Real Life

Every behavior described in this report ultimately traces back to how these two dispatchers schedule coroutine continuations. Understanding them at a mechanical level is the foundation for understanding everything else.

---

### What a dispatcher actually does

A dispatcher answers one question: **when and on which thread does a resumed coroutine continuation run?**

When a coroutine is suspended and then resumed (e.g., a `StateFlow` update marks its collector's slot as PENDING), the coroutine's continuation is handed to the dispatcher. The dispatcher decides whether to run it immediately, queue it, or schedule it for later.

---

### `StandardTestDispatcher` — the lazy queue

**Mechanism:** Every continuation is placed into a **FIFO task queue** managed by a `TestCoroutineScheduler`. Nothing in the queue runs automatically. The queue only drains when something explicitly advances the scheduler:
- A `delay()` call parks the current coroutine and the scheduler picks the next task
- `advanceUntilIdle()` drains all currently queued tasks
- `runCurrent()` runs only the tasks queued at the current virtual time
- A suspension in the test body lets the scheduler pick the next queued task

**Key property:** Synchronous code within a coroutine runs completely uninterrupted. The scheduler cannot inject a task between two consecutive non-suspending lines. A coroutine runs until it hits a suspension point, then — and only then — does the scheduler pick the next queued task.

**Virtual time:** `StandardTestDispatcher` operates on **virtual time**. `delay(1000)` does not wait 1 real second — it parks the coroutine at virtual timestamp T+1000ms. `advanceUntilIdle()` jumps virtual time forward to drain all parked tasks. This makes tests fast and deterministic.

**Real-world equivalent:** This is how `Dispatchers.Main` behaves on Android in production. The main thread runs a `Looper` message queue — it processes one `Message`/`Runnable` at a time, draining the queue cooperatively. A `Handler.post { ... }` enqueues a task; it doesn't preempt what's currently running. `StandardTestDispatcher` is a faithful model of this: cooperative, queue-based, non-preemptive.

**What it models accurately:**
- The ordering guarantees of real coroutine execution on `Dispatchers.Main`
- The fact that `launch { }` does not start immediately — it queues
- The cooperative nature of coroutine scheduling

**What it does NOT model:**
- Parallelism (everything is sequential on one virtual thread)
- Real elapsed time (virtual time is instantaneous)

---

### `UnconfinedTestDispatcher` — the eager inline runner

**Mechanism:** When a continuation is resumed, it is dispatched **immediately and inline** on the current thread — before returning control to the caller. There is no queue. The resumed coroutine runs right now, to its next suspension point, and only then returns.

**Key property:** When `_uiState.update(...)` marks a collector's slot as PENDING and the collector is on `UnconfinedTestDispatcher`, the collector runs **synchronously inside the `update` call** — before `update` returns to the line of code that called it. This is what allows catching every intermediate `StateFlow` emission.

**Virtual time:** `UnconfinedTestDispatcher` still participates in the same `TestCoroutineScheduler` as `StandardTestDispatcher` when they share a scheduler instance. `delay()` still uses virtual time — only the dispatch behaviour (queue vs inline) differs.

**Real-world equivalent:** This resembles `Dispatchers.Unconfined` in production — a dispatcher that runs the coroutine on whichever thread resumed it, without re-dispatching. In practice, production code rarely uses `Dispatchers.Unconfined` intentionally. `UnconfinedTestDispatcher` is a **test-only construct** that has no direct production analogue at the architectural level — it exists purely to make collectors faster than producers in tests.

**What it models accurately:**
- A collector that is infinitely fast relative to its producer
- The theoretical maximum observability of a `StateFlow`

**What it does NOT model:**
- Real Android main thread scheduling
- Real-world dispatcher behavior for `viewModelScope`

---

### Side-by-side comparison

| Property | `StandardTestDispatcher` | `UnconfinedTestDispatcher` |
|---|---|---|
| Continuation dispatch | Queued, runs at next drain point | Inline, runs immediately |
| Synchronous code interrupted? | ❌ Never | ❌ Never (only suspension points matter) |
| Virtual time used? | ✅ Yes | ✅ Yes (if sharing scheduler) |
| `launch { }` starts immediately? | ❌ No — queued | ✅ Yes — runs eagerly |
| Real-world analogue | `Dispatchers.Main` (Looper queue) | `Dispatchers.Unconfined` (no dispatch) |
| Used for | Producing coroutines (viewModelScope) | Collecting coroutines (relay, Turbine) |
| Risk | Conflation between suspension points | Unexpected execution order in complex graphs |

---

### Why the combination matters for `StateFlow` testing

The entire problem space in this report exists because of one interaction:

> `StateFlow` conflates by design — its slot is binary (NONE/PENDING). If a collector's slot is already PENDING when a new value is written, the new value silently overwrites the old one in place. The collector will read only the latest value when it eventually runs.

Under `StandardTestDispatcher`, a collector's slot can stay PENDING across many updates — because the collector is queued and doesn't run until the scheduler drains. Every update that fires while the slot is PENDING is lost except the last.

Under `UnconfinedTestDispatcher`, the collector runs inline immediately after each update — the slot resets to NONE before the next update fires. Conflation never gets a chance to happen.

This is why the winning combination is always **Standard producer + Unconfined collector** — and why every case in this report is ultimately a variation of whether that combination is in place at the `_uiState` layer.

---

## Project Context

- **ViewModel**: `CounterViewModel` using `stateIn` with `SharingStarted.WhileSubscribed(5000)` and `onStart { loadInitialCounter() }`
- **Test setup**: JUnit 5 + Turbine + AssertK + `MainDispatcherExtension` (overrides `Dispatchers.Main` via `setMain`)
- **Test dispatcher (initial)**: `StandardTestDispatcher`

---

## The Core Function Under Test

```kotlin
fun incrementAsync() {
    viewModelScope.launch {
        _uiState.update { it.copy(isLoading = true) }   // ①
        _uiState.update { it.copy(isLoading = false) }  // ②
        _uiState.update { it.copy(isLoading = true) }   // ③
        delay(1000)
        _uiState.update { it.copy(count = it.count + 1, isLoading = false) } // ④
    }
}
```

Including initial state, there are **5 distinct emissions** to observe:

| # | Value |
|---|---|
| 0 | `(count=0, isLoading=false)` — initial |
| 1 | `(count=0, isLoading=true)` — update ① |
| 2 | `(count=0, isLoading=false)` — update ② |
| 3 | `(count=0, isLoading=true)` — update ③ |
| 4 | `(count=1, isLoading=false)` — update ④ |

---

## Background: What `stateIn` Creates

When you write:

```kotlin
val uiState: StateFlow<CounterUiState> = _uiState
    .onStart { loadInitialCounter() }
    .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = _uiState.value
    )
```

`stateIn` **silently creates a relay coroutine** inside `viewModelScope`. Its job is to sit between the cold upstream (`_uiState.onStart { ... }`) and an internal `sharedState: MutableStateFlow<T>`, collecting from the upstream and forwarding every value downstream. Collectors like Turbine subscribe to `sharedState`, not to `_uiState` directly.

**Two-layer topology:**
```
_uiState  ←── incrementAsync
    ↓
[relay coroutine]    ← lives inside stateIn's scope
    ↓
sharedState (internal MutableStateFlow)
    ↓
[Turbine collector]
```

With `asStateFlow()`, this collapses to one layer:
```
_uiState  ←── incrementAsync
    ↓
[Turbine collector]
```

---

## Background: How `StateFlow` Wakes Up Collectors

`StateFlowImpl` uses a **binary slot** per collector, not a queue:

- `NONE` — collector is sleeping
- `PENDING` — collector has been woken up and will run on next dispatch

When `_uiState.update(...)` is called:
1. The new value is written atomically to `_uiState`
2. The collector's slot is checked:
   - If `NONE` → flip to `PENDING`, enqueue the collector's continuation in the scheduler
   - If already `PENDING` → do nothing — the slot stays `PENDING`, no new queue entry is added

This is `StateFlow`'s **conflation mechanism by design**. The slot is a flag, not a counter. It has no memory of how many times it was marked pending. If the value changes again before the collector runs, the collector will read only the **current** (latest) value on wakeup.

---

## Background: `StandardTestDispatcher` vs `UnconfinedTestDispatcher`

### `StandardTestDispatcher`
- Continuations are placed in a **FIFO task queue**
- Nothing runs until the scheduler is explicitly drained — via `delay()`, `advanceUntilIdle()`, `runCurrent()`, or a suspension in the test body
- A coroutine can only be interrupted at **suspension points** — synchronous code within one coroutine runs uninterrupted

### `UnconfinedTestDispatcher`
- Continuations are dispatched **immediately and inline** — a resumed coroutine runs right now, on the current thread, before returning to the caller
- No queue, no waiting
- "Unconfined" means: the resumed code runs wherever it is resumed from, without waiting for a dispatch

### The Fundamental Rule (from [zsmb.co](https://zsmb.co/conflating-stateflows/))

> **The only way a collecting coroutine can avoid conflation is if the collector is on `UnconfinedTestDispatcher` while the producer is on `StandardTestDispatcher`.**

| Producer | Collector | Result |
|---|---|---|
| Standard | Unconfined | ✅ No conflation |
| Standard | Standard | ❌ Conflation |
| Unconfined | Unconfined | ❌ Conflation (producer holds thread) |
| Unconfined | Standard | ❌ Conflation |

---

## Case 1: Original Setup — `stateIn(viewModelScope)` + `StandardTestDispatcher`

### Setup

```kotlin
// ViewModel
val uiState: StateFlow<CounterUiState> = _uiState
    .onStart { loadInitialCounter() }
    .stateIn(scope = viewModelScope, ...)

// MainDispatcherExtension
StandardTestDispatcher()   // applied via setMain
```

### Dispatcher map

```
incrementAsync   → viewModelScope → StandardTestDispatcher (via setMain)
relay coroutine  → viewModelScope → StandardTestDispatcher (via setMain)
Turbine          → internally    → UnconfinedTestDispatcher (Turbine's internal impl)
```

### Observable emissions

| # | Emission | Observed? |
|---|---|---|
| 0 | `(count=0, isLoading=false)` | ✅ |
| 1 | `(count=0, isLoading=true)` | ❌ |
| 2 | `(count=0, isLoading=false)` | ❌ |
| 3 | `(count=0, isLoading=true)` | ❌ |
| 4 | `(count=1, isLoading=false)` | ✅ |

Only **2 of 5** emissions observed.

### Why

**Step-by-step execution trace:**

```
Test body reaches viewModel.uiState.test { awaitItem() } → suspends
Scheduler drains → incrementAsync coroutine starts running

① _uiState.update { isLoading=true }
   → _uiState.value = (isLoading=true)
   → relay slot: NONE → PENDING
   → relay continuation ENQUEUED in scheduler (not run yet)

② _uiState.update { isLoading=false }
   → _uiState.value = (isLoading=false)     ← overwrites ①
   → relay slot: already PENDING → nothing enqueued

③ _uiState.update { isLoading=true }
   → _uiState.value = (isLoading=true)       ← overwrites ②
   → relay slot: already PENDING → nothing enqueued

④ delay(1000) → incrementAsync SUSPENDS → scheduler drains

⑤ Relay continuation finally runs
   → reads _uiState.value = (isLoading=true) ← only sees ③'s value
   → emits ONE value into sharedState
   → Turbine observes (isLoading=true)... wait — actually Turbine
     also misses this because the relay itself on Standard still
     needs to forward through sharedState → Turbine only sees the
     final post-delay emission.
```

**Root cause:** The relay and `incrementAsync` share `viewModelScope`. Both are on `StandardTestDispatcher`. From the relay's perspective:

- Producer of `_uiState`: Standard (`incrementAsync`)
- Collector of `_uiState`: Standard (relay)
- **Standard/Standard = losing combination**

The relay conflates all three synchronous updates to one before Turbine ever receives them. Even though Turbine is Unconfined, it never sees the intermediate values because they are lost at the relay layer.

---

## Case 2: `setMain(UnconfinedTestDispatcher)` — Considered but Broken

### Hypothesis
If the relay is Unconfined, maybe it can dispatch inline between each `_uiState.update`.

### Why it fails

```
incrementAsync   → viewModelScope → UnconfinedTestDispatcher  ← producer
relay coroutine  → viewModelScope → UnconfinedTestDispatcher  ← collector
```

Both share `viewModelScope` → both get `UnconfinedTestDispatcher` via `setMain`.

When `incrementAsync` (Unconfined) holds the thread during `①②③`, the relay (also Unconfined) **cannot preempt a currently-executing Unconfined coroutine**. It queues up and only runs after `incrementAsync` suspends at `delay`. By then, all three values have been written and `_uiState` holds only ③'s value.

**Unconfined/Unconfined = losing combination.** Making both Unconfined does not help.

---

## Case 3: Scope Injection — `stateIn(sharingScope)` with any Dispatcher

### Setup

```kotlin
// ViewModel
class CounterViewModel(
    sharingDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate
) : ViewModel() {
    private val sharingScope = CoroutineScope(
        viewModelScope.coroutineContext + sharingDispatcher
    )
    val uiState = _uiState
        .onStart { loadInitialCounter() }
        .stateIn(scope = sharingScope, ...)
}

// Test — variant A
viewModel = CounterViewModel(
    sharingDispatcher = UnconfinedTestDispatcher(mainDispatcherExtension.testDispatcher.scheduler)
)

// Test — variant B (user-tested)
viewModel = CounterViewModel(
    sharingDispatcher = StandardTestDispatcher()
)
```

### Observed emissions (both variants A and B)

| # | Emission | Observed? |
|---|---|---|
| 0 | `(count=0, isLoading=false)` | ✅ |
| 1 | `(count=0, isLoading=true)` — just before delay | ✅ |
| 2 | `(count=0, isLoading=false)` | ❌ |
| 3 | `(count=0, isLoading=true)` | ❌ |
| 4 | `(count=1, isLoading=false)` | ✅ |

**3 of 5** observed — same result regardless of which dispatcher was injected.

### Key discovery

The user discovered that **the dispatcher injected did not matter**. Passing `UnconfinedTestDispatcher` or `StandardTestDispatcher` as `sharingDispatcher` produced identical results. This disproved the earlier assumption that the dispatcher value was the variable causing the improvement.

### Why the dispatcher doesn't matter here — but scope does

The variable that matters is that the relay now lives in **`sharingScope`** rather than `viewModelScope`. This gives the relay its own **independent task queue**, separate from `incrementAsync`'s queue:

```
viewModelScope queue  (Standard): [ incrementAsync tasks ]
sharingScope queue    (Standard): [ relay tasks ]          ← independent
```

When `incrementAsync` held the queue in Case 1, the relay was blocked behind it in the same single queue. With separate scopes, the scheduler interleaves tasks from both queues differently, allowing the relay to observe at least the last value before `delay`.

However, because `①②③` all happen synchronously without any suspension point between them, the relay **still cannot intercept between them** — it only wakes up after `delay` in `incrementAsync` creates a suspension point. The net result: only update ③'s value (the last synchronous update before `delay`) is forwarded to Turbine.

### Why updates ② and ③ are still missing

Updates `①②③` form a **single uninterrupted synchronous execution block**:

```kotlin
_uiState.update { it.copy(isLoading = true) }   // ①
_uiState.update { it.copy(isLoading = false) }  // ②  ← no suspension point
_uiState.update { it.copy(isLoading = true) }   // ③  ← no suspension point
delay(1000)  // ← FIRST suspension point
```

No coroutine — regardless of scope or dispatcher — can be dispatched between lines that contain no suspension points. The relay's slot is marked `PENDING` on update ①, stays `PENDING` through ② and ③, and only drains after `delay` creates a suspension. At that point, the relay reads `_uiState.value` which holds only ③'s value. Updates ① and ② are gone.

---

## Case 4: `asStateFlow()` — All 5 Emissions Observed

### Setup

```kotlin
val uiState = _uiState.asStateFlow()
// (loadInitialCounter moved to init {})
```

### Dispatcher map

```
incrementAsync  → viewModelScope → StandardTestDispatcher (via setMain)  ← Standard producer
Turbine         → internally    → UnconfinedTestDispatcher                ← Unconfined collector
```

No relay. One layer.

### Observable emissions

| # | Emission | Observed? |
|---|---|---|
| 0 | `(count=0, isLoading=false)` | ✅ |
| 1 | `(count=0, isLoading=true)` | ✅ |
| 2 | `(count=0, isLoading=false)` | ✅ |
| 3 | `(count=0, isLoading=true)` | ✅ |
| 4 | `(count=1, isLoading=false)` | ✅ |

**5 of 5** observed.

### Why

Turbine's `test` function internally uses `UnconfinedTestDispatcher` when called inside `runTest`. With `asStateFlow()`, Turbine **directly subscribes to `_uiState`** — there is no relay. So the dispatcher pairing is:

- Producer (`incrementAsync`): `StandardTestDispatcher` ✓
- Collector (Turbine): `UnconfinedTestDispatcher` ✓
- **Standard/Unconfined = winning combination**

Execution trace:
```
① _uiState.update { isLoading=true }
   → Turbine's slot: NONE → PENDING
   → Turbine is Unconfined → runs INLINE RIGHT NOW
   → Turbine observes (isLoading=true) before returning to incrementAsync ✓

② _uiState.update { isLoading=false }
   → same → Turbine observes (isLoading=false) ✓

③ _uiState.update { isLoading=true }
   → same → Turbine observes (isLoading=true) ✓

④ delay(1000) → time advances

⑤ _uiState.update { count=1, isLoading=false }
   → same → Turbine observes (count=1, isLoading=false) ✓
```

Because Turbine dispatches inline between each update, the value is read **before** `incrementAsync` reaches the next update line. No value is ever overwritten from Turbine's perspective.

---

## Case 5: Catching All 5 With `stateIn` — `yield()` vs `delay()` Between Updates

### Scenario

The user wanted to keep `stateIn` (with `sharingScope` injection) and still observe all 5 emissions. The idea: add suspension points between `①②③` so the relay gets a window to forward each value.

**Attempted with `yield()`:**
```kotlin
_uiState.update { it.copy(isLoading = true) }
yield()
_uiState.update { it.copy(isLoading = false) }
yield()
_uiState.update { it.copy(isLoading = true) }
delay(1000)
```
**Result: still failed to catch all 5.**

**Attempted with `delay()`:**
```kotlin
_uiState.update { it.copy(isLoading = true) }
delay(x)
_uiState.update { it.copy(isLoading = false) }
delay(x)
_uiState.update { it.copy(isLoading = true) }
delay(1000)
```
**Result: all 5 caught.**

### Why `yield()` fails

`yield()` suspends the current coroutine and **re-queues it at the end of the current scheduler queue**. Critically:

- **Virtual time does NOT advance**
- It only reshuffles the existing queue

With `sharingScope`, both `viewModelScope` and `sharingScope` compete for the `StandardTestDispatcher`. After `yield()`, `incrementAsync` is re-queued but the **order in which the scheduler picks between the two queues is not guaranteed**. The scheduler may pick `incrementAsync` again before the relay — meaning update ② fires before the relay ever reads update ①'s value.

`yield()` is a **polite suggestion** to the scheduler. It gives the relay a chance to run but provides no guarantee.

### Why `delay()` works

`delay()` suspends `incrementAsync` and **schedules its resumption at a future virtual time T+x**. The coroutine is completely removed from the current runnable queue — it is parked at a timestamp.

```
① _uiState.update { isLoading=true }  → relay queued
   delay(x) → incrementAsync PARKED at T+x, removed from current queue

Scheduler now:
  viewModelScope queue: [ empty — incrementAsync is parked ]
  sharingScope queue:   [ relay task ]

Only the relay can run. It runs, reads isLoading=true, forwards to
sharedState, Turbine observes. ✓

Virtual time advances to T+x → incrementAsync resumes → fires update ②
```

`delay()` creates a **hard temporal barrier** — `incrementAsync` is guaranteed not to proceed until time is explicitly advanced, giving the relay an exclusive window to drain.

| Suspension | Virtual time advances | Guarantees relay runs before next update |
|---|---|---|
| `yield()` | ❌ | ❌ — reshuffles queue, order not guaranteed |
| `delay()` | ✅ | ✅ — parks incrementAsync, relay has exclusive window |

---

## Case 6: Mutating `_uiState` Inside `onStart` and Its Effect on Tests

### Setup

```kotlin
suspend fun loadInitialCounter() {
    delay(1.seconds)
    increment()  // → _uiState.update { count + 1 }
}

val uiState: StateFlow<CounterUiState> = _uiState
    .onStart { loadInitialCounter() }
    .stateIn(
        scope = sharingScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = _uiState.value  // captured at construction: (count=0, isLoading=false)
    )
```

`incrementAsync` (unchanged) fires three synchronous updates before `delay(1000)`:

```kotlin
_uiState.update { it.copy(isLoading = true) }           // ①
_uiState.update { it.copy(isLoading = false) }          // ②
_uiState.update { it.copy(isLoading = true) }           // ③
delay(1000)
_uiState.update { it.copy(count = it.count + 1, isLoading = false) } // ④
```

### Critical timing: `onStart` runs before the relay subscribes to `_uiState`

`onStart` is an operator applied to the **upstream cold flow**. The relay coroutine enters the `onStart` block **before** it begins collecting from `_uiState`. This means:

- Any writes to `_uiState` that happen inside `onStart` are **invisible to the relay** — it has not subscribed yet
- When `onStart` finishes, the relay subscribes to `_uiState` and receives its **current snapshot value** via `StateFlow`'s replay-on-subscription behavior

`stateIn`'s `initialValue` is captured at **ViewModel construction time** (T=0). It does not update as `_uiState` changes. So the very first emission Turbine sees is always the value at construction — regardless of what `onStart` does later.

### Full execution trace (with `sharingScope` injection, `delay(1.seconds)` in `onStart` and `delay(1000)` in `incrementAsync` — both firing at T=1s)

```
T=0: Turbine subscribes to uiState
  → stateIn emits initialValue = (count=0, isLoading=false) immediately  ← emission 1
  → Relay enters onStart { loadInitialCounter() }
  → hits delay(1.second) → relay PARKS at T=1s
  → Relay has NOT subscribed to _uiState yet

T=0: viewModel.incrementAsync() called
  → coroutine launched on viewModelScope (Standard) → QUEUED

T=0: awaitItem() → test suspends → scheduler drains T=0 queue

  incrementAsync runs:
  ① _uiState.update { isLoading=true }  → _uiState=(0,  true)
     relay is still parked in onStart — NOT subscribed — no reaction
  ② _uiState.update { isLoading=false } → _uiState=(0, false)  — no reaction
  ③ _uiState.update { isLoading=true }  → _uiState=(0,  true)  — no reaction
  delay(1000) → incrementAsync PARKS at T=1s

T=1s: two things resume. Relay was scheduled first → runs first.

  onStart resumes:
  → increment() → _uiState.update { count+1 }
  → _uiState = (count=1, isLoading=true)   ← count goes 0→1; isLoading=true
                                              is the residue of ③ still in _uiState
  → onStart block finishes
  → Relay NOW subscribes to _uiState
  → _uiState replays its current value (count=1, isLoading=true) to the relay
  → Relay (Unconfined) forwards (1, true) to sharedState
  → Turbine observes (count=1, isLoading=true)                           ← emission 2

  incrementAsync resumes:
  ④ _uiState.update { count+1, isLoading=false }
  → _uiState = (count=2, isLoading=false)  ← count goes 1→2
  → Relay (Unconfined, now subscribed) forwards inline
  → Turbine observes (count=2, isLoading=false)                          ← emission 3
```

### Observable emissions

| # | Value | Reason |
|---|---|---|
| 1 | `(count=0, isLoading=false)` | `stateIn`'s `initialValue`, emitted before relay starts |
| 2 | `(count=1, isLoading=true)` | Relay subscribes at T=1s; reads current `_uiState` snapshot which has `count=1` (from `onStart`'s `increment()`) and `isLoading=true` (residue of ③) |
| 3 | `(count=2, isLoading=false)` | `incrementAsync`'s final update; `count` was already `1` so it becomes `2` |

### Why `loading.count` is already 1 at emission 2

Updates `①②③` all fired at T=0 while the relay was parked inside `onStart`, not yet subscribed to `_uiState`. They were completely invisible to the relay — it wasn't listening. The relay only learned the state of `_uiState` by receiving the **snapshot replay** at subscription time (T=1s). That snapshot reflected `count=1` set by `increment()` inside `onStart`, combined with `isLoading=true` left over from ③.

### Why `notLoading.count` is 2, not 1

`incrementAsync`'s final update is `count = it.count + 1`. By T=1s, `_uiState.count` is already `1` (mutated by `onStart`). So the final increment goes `1 → 2`.

### Why `①②③` are entirely absent

Three reasons compound here:
1. The relay was not yet subscribed to `_uiState` when `①②③` fired — they were invisible at the relay layer
2. Even if the relay had been subscribed, `①②③` are a synchronous block with no suspension points — they would have been conflated to one
3. `stateIn`'s `initialValue` is a static snapshot — it does not retroactively reflect `①②③`

### Key insight: `onStart` mutation creates a hidden state dependency

When you mutate `_uiState` inside `onStart`, the `initialValue` passed to `stateIn` and the actual first value the relay forwards **will diverge** — always. The subscriber sees a brief window (from subscription until `onStart` completes) where `uiState.value` reports `initialValue` while `_uiState` has already moved on. This is not a test artifact — it is the same in production.

### Note: what the first `awaitItem()` returns is strictly determined by suspension points in `onStart`

This is **not flaky** — it is structurally guaranteed in both directions:

**If `onStart` has a suspension point BEFORE the `_uiState` mutation:**

```
Turbine subscribes
  → stateIn writes initialValue=(count=0) into sharedState
  → relay enters onStart → hits suspension point → PARKS
  → sharedState is still (count=0) — nothing has touched it

test body calls awaitItem()
  → sharedState = (count=0) ← always reads this
  → relay is parked — it physically cannot write to sharedState yet

virtual time advances past suspension
  → onStart resumes → mutation fires → relay subscribes → sharedState updated
  → next awaitItem() returns the mutated value
```

The suspension point is a **hard stop** — not a race condition. The relay cannot proceed until virtual time is explicitly advanced by the test. `awaitItem()` always wins because the relay is not competing; it is completely stopped.

**If `onStart` has NO suspension point (synchronous `onStart`):**

```
Turbine subscribes
  → stateIn writes initialValue=(count=0) into sharedState
  → relay enters onStart → mutation fires synchronously
  → relay subscribes → _uiState replays mutated value → sharedState overwritten
  → ALL OF THIS completes before awaitItem() is ever called

test body calls awaitItem()
  → sharedState = mutated value ← always reads this
  → (count=0) was written and immediately overwritten in one uninterrupted block
```

The `initialValue` is overwritten before the test body can read it.

**The strict rule:**

| `onStart` before mutation | First `awaitItem()` returns | Deterministic? |
|---|---|---|
| Has suspension point | `initialValue` (non-mutated) | ✅ Always |
| No suspension point | Mutated value | ✅ Always |

Neither case involves scheduler timing or ordering uncertainty. The suspension point alone determines the outcome — making this behavior safe to assert on in tests without fear of flakiness.

### Disclaimer: behavior changes entirely when `onStart` has no suspension point

Everything in Case 6 above assumes `onStart` contains a suspension point (`delay(1.seconds)`). If you remove the `delay` — making `onStart` complete synchronously — the relay subscribes to `_uiState` **at T=0**, before `incrementAsync` ever fires. In that scenario the relay's dispatcher becomes the determining variable again, and the two `sharingDispatcher` variants produce **different** results:

```kotlin
suspend fun loadInitialCounter() {
    // delay removed — onStart now completes synchronously
    increment()
}
```

**With `sharingDispatcher = StandardTestDispatcher`** — relay subscribed at T=0 but Standard/Standard losing combo:

| # | Value | Observed? |
|---|---|---|
| 1 | `(count=0, isLoading=false)` | ✅ (initialValue before onStart update overwrites sharedState) |
| 2 | `(count=1, isLoading=true)` | ✅ (relay drains after delay, reads ③'s residue + count=1 from onStart) |
| 3 | `(count=2, isLoading=false)` | ✅ (final update, count goes 1→2) |

3 / 5 — `①②③` still conflated because Standard/Standard between `_uiState` and relay.

**With `sharingDispatcher = UnconfinedTestDispatcher`** — relay subscribed at T=0, Standard/Unconfined winning combo:

| # | Value | Observed? |
|---|---|---|
| 1 | `(count=1, isLoading=false)` | ✅ (onStart ran synchronously, sharedState already updated before first awaitItem) |
| 2 | `(count=1, isLoading=true)` | ✅ |
| 3 | `(count=1, isLoading=false)` | ✅ |
| 4 | `(count=1, isLoading=true)` | ✅ |
| 5 | `(count=2, isLoading=false)` | ✅ |

5 / 5 — relay dispatches inline between every `_uiState.update` call.

The root reason for the divergence:

| `onStart` | Relay subscribes during `①②③`? | Dispatcher matters? |
|---|---|---|
| Has `delay` | ❌ No — parked in `onStart` | ❌ Irrelevant — relay wasn't listening |
| No `delay` | ✅ Yes — subscribed synchronously at T=0 | ✅ Yes — winning/losing combo applies |

---

## Comprehensive Dispatcher/Setup Matrix

**With suspended `onStart` (`delay` present) — relay subscribes after `①②③` fire:**

| ViewModel setup | Test dispatcher (setMain) | sharingDispatcher | Emissions observed |
|---|---|---|---|
| `stateIn(viewModelScope)` | Standard | — | 2 / 5 |
| `stateIn(viewModelScope)` | Unconfined | — | broken (both Unconfined) |
| `stateIn(sharingScope)` | Standard | Standard | 3 / 5 |
| `stateIn(sharingScope)` | Standard | Unconfined (shared scheduler) | 3 / 5 |
| `stateIn(sharingScope)` + `delay()` between updates | Standard | Standard or Unconfined | 5 / 5 |
| `asStateFlow()` (no relay) | Standard | — | 5 / 5 |

**With synchronous `onStart` (no `delay`) — relay subscribes before `①②③` fire:**

| ViewModel setup | Test dispatcher (setMain) | sharingDispatcher | Emissions observed |
|---|---|---|---|
| `stateIn(sharingScope)` | Standard | Standard | 3 / 5 |
| `stateIn(sharingScope)` | Standard | Unconfined (shared scheduler) | 5 / 5 |

---

## Verified Invariants

1. **StateFlow's slot is binary (NONE/PENDING), not a counter.** Multiple rapid writes before a collector runs are collapsed to one — the collector reads only the current value at dispatch time. This is by design and not a test-only behavior.

2. **No coroutine can be dispatched between synchronous lines.** Regardless of scope, dispatcher, or scheduler configuration, the only points at which the scheduler can switch between coroutines are explicit suspension points (`delay`, `yield`, `await`, etc.).

3. **`stateIn` always introduces a relay coroutine.** It is not optional. Passing any scope to `stateIn` creates this relay. The relay adds an extra conflation point between `_uiState` and Turbine.

4. **Turbine's `test` function uses `UnconfinedTestDispatcher` internally** when called inside `runTest`. This is confirmed by [Márton Braun's article](https://zsmb.co/conflating-stateflows/). The test author does not need to configure this.

5. **The winning combination is: Standard producer, Unconfined collector.** Any other combination results in conflation. Both Unconfined is a losing combination because the producer holds the thread during its synchronous block.

6. **`setMain` applies only to `viewModelScope` and its children.** It does not control `runTest`'s own scope dispatcher.

7. **Injecting a separate scope into `stateIn` (regardless of dispatcher) improves observable emissions vs. passing `viewModelScope` directly.** The relay gets its own independent task queue, changing how the scheduler interleaves its tasks with `incrementAsync`. The specific dispatcher injected (Standard vs Unconfined) did not matter empirically in this case — the structural change of using a separate scope was the determining variable.

8. **`delay()` advances virtual time; `yield()` does not.** Under `StandardTestDispatcher`, only virtual time advancement guarantees a coroutine is parked long enough for another to run exclusively.

---

## Final Architecture — Production Code

```kotlin
class CounterViewModel(
    sharingDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate
) : ViewModel() {

    // sharingScope inherits viewModelScope's Job (cancelled with ViewModel)
    // but allows the relay coroutine's dispatcher to be injected independently.
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
```

## Final Architecture — Test Code

```kotlin
@OptIn(ExperimentalCoroutinesApi::class)
class CounterViewModelTest {

    @JvmField
    @RegisterExtension
    val mainDispatcherExtension = MainDispatcherExtension() // setMain(StandardTestDispatcher)

    @BeforeEach
    fun setup() {
        viewModel = CounterViewModel(
            sharingDispatcher = UnconfinedTestDispatcher(
                mainDispatcherExtension.testDispatcher.scheduler  // shared scheduler for virtual time
            )
        )
    }
}
```

The shared `scheduler` instance ensures `delay()` and `advanceUntilIdle()` in `runTest` control virtual time for both `viewModelScope` coroutines and `sharingScope` coroutines consistently.

---

## Key Takeaway

> The `stateIn` relay is an invisible extra coroutine that sits between your `MutableStateFlow` and any downstream collector. Under `StandardTestDispatcher`, it introduces a second conflation point that Turbine — despite being Unconfined — cannot bypass. The cleanest structural solution is `asStateFlow()` which eliminates the relay entirely. If `onStart` semantics are required, scope injection is the next best option, accepting that synchronous updates without suspension points between them will still be conflated at the relay layer.
