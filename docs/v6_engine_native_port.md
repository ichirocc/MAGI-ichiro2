# V6 Native Engine Port (`com.magi.app.v6`)

This is the complete native port of the Web **V6 / mirror** scheduling engine
(`magi_python_mirror.py` + the V6 web worker), brought over from the standalone
`MagiNative` reference build.

## Why a separate package

The existing engine on `main` lives in `com.magi.app.engine` and already defines
its own constraint model (`EngineConstraints.kt`: `C1`, `C2`, `C3`, `C41`, `C42`),
its own `DeltaEvaluator`, and its own `C3Run`. The V6 mirror engine defines
classes with the **same names** but different semantics (e.g. `c1` = in-window
minimum vs. the resolved engine's gap band, `c2` = lower bound vs. upper cap).

To avoid duplicate-declaration collisions — and to keep the two engine lineages
from silently disagreeing — the entire V6 engine is isolated in
**`com.magi.app.v6`**. The two packages share nothing except the model types in
`com.magi.app.model`, so each lineage keeps its own authoritative semantics.

This replaces the earlier `p11-bulk-sync` approach, which kept the engine in
`com.magi.app.engine` and renamed the constraint rows to `P11C1..P11C42`. That
partial sync shipped only a thinned subset (no `SaOptimizer`, no
`V6LateOperators`, no `V6PortAnalyzer`, a 133-line stub `V6NativeOptimizer`,
and only a smoke test). This port brings the full engine across verbatim.

## Contents

Main (`app/src/main/java/com/magi/app/v6/`):

- `Problem.kt`, `Evaluator.kt`, `DeltaEvaluator.kt`, `C3Run.kt` — resolved problem
  view + full / delta scoring.
- `MirrorCore.kt` — `UnifiedViolationChecker`, weighted score, shared helpers.
- `GreedyMirrorScheduler.kt`, `LightMirrorOptimizer.kt`, `SaOptimizer.kt` —
  schedule construction and the high-speed simulated-annealing optimizer.
- `V6NativeOptimizer.kt` — full multi-algorithm dispatcher (V5 / ALNS / RSI /
  RSI+) wiring the SA engine, late operators, and hotfix passes.
- `V6HotfixPasses.kt`, `V6LateOperators.kt` — post-optimization passes.
- `V6SanityPort.kt`, `V6PortAnalyzer.kt` — diagnostics.
- `Ws1Ops.kt`, `ScheduleCsvBridge.kt` — settings ops and CSV round-trip.
- `V6WebCompat.kt`, `V6FinalPort.kt` — stable façade for UI / tests
  (`handleCheck` / `handleSimple` / `handleOptimize`).

Tests (`app/src/test/java/com/magi/app/v6/`): `DeltaEvaluatorTest`,
`MirrorEngineTest`, `V6WebCompatTest`, `V6SanityPortTest`, `V6PortAnalyzerTest`,
`V6LateOperatorsTest`, `V6NativeOptimizerChoiceTest`, `V6FinalBridgePortTest`.

## UI wiring

`MagiApp` (Compose) now drives the V6 engine via `V6FinalPort`:

- **最適化する** → `V6FinalPort.handleOptimize(state, schedule, seconds = 30)`
  (replaces the old `WebSmartOptimizer` call).
- **簡易作成** → `V6FinalPort.handleSimple(state)` (greedy initial schedule).
- **違反チェック** → `V6FinalPort.handleCheck(state, schedule)` (evaluation only).

All three run on `Dispatchers.Default` from a `rememberCoroutineScope`, with a
busy flag that disables the buttons and shows a spinner while running. The
returned `ViolationReport` (HARD / SOFT / total / weighted score) is shown on
the Home and Schedule panels. The existing resolved-engine score, breakdown and
3-evaluator consistency check still power the Analysis tab unchanged.
