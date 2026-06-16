# MAGI Native (Kotlin/Android)

MAGI shift optimizer, native Android port.

This project contains a Kotlin/Jetpack Compose Android app that ports the MAGI web shift optimizer engine into native Kotlin.

## Status

- Engine core: Kotlin-native greedy + SA optimizer
- V6 web bridge compatibility: partially ported
- Constraint fidelity: Level Zero preserved for top-level constraints
- Input: JSON state via editor/sample assets
- Output: optimized assignments and diagnostics

## Build

```bash
./gradlew assembleDebug
```

## Run tests

```bash
./gradlew test
```

## Notes

This is a generated p11 project snapshot.
