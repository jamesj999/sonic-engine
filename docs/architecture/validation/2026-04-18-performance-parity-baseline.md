# Performance Parity Baseline

Captured on `2026-04-18` in isolated worktree `feature/ai-performance-parity-optimization`.

## Baseline Prep

- Fixed pre-existing validation harness drift in:
  - `src/test/java/com/openggf/tests/graphics/FadeManagerTest.java`
  - `src/test/java/com/openggf/tests/TestHTZBossTouchResponse.java`
  - `src/test/java/com/openggf/tests/TestSmpsDriver.java`
- Root cause: these tests called `RuntimeManager.createGameplay()` without first configuring `EngineServices`. They now bootstrap through `TestEnvironment.resetAll()`, matching the current runtime-owned test harness.

## Patch 1 - Pattern hot path

- Render suite command:

```powershell
mvn --% -q -Dmse=off -Dtest=com.openggf.tests.graphics.PatternAtlasFallbackTest,com.openggf.tests.graphics.RenderOrderTest,com.openggf.tests.graphics.FadeManagerTest,com.openggf.tests.TestPatternDesc,com.openggf.tests.TestPaletteCycling,com.openggf.tests.TestSwScrlHtzEarthquakeMode,com.openggf.tests.TestS3kCnzBossScrollHandler test
```

- Result: `PASS` on `2026-04-18`

## Patch 2 - Object bookkeeping allocations

- Object suite command:

```powershell
mvn --% -q -Dmse=off -Dtest=com.openggf.tests.TestObjectManagerExecLoopParity,com.openggf.tests.physics.CollisionSystemTest,com.openggf.tests.TestHTZBossTouchResponse,com.openggf.tests.TestSolidOrderingCollisionTraces,com.openggf.tests.TestSolidOrderingSentinelsHeadless,com.openggf.tests.TestS2Htz1Headless,com.openggf.tests.TestS3kAiz1SkipHeadless test
```

- Result: `PASS` on `2026-04-18`

- Trace suite command:

```powershell
mvn --% -q -Dmse=off -Dtest=com.openggf.tests.trace.s1.TestS1Ghz1TraceReplay,com.openggf.tests.trace.s1.TestS1Mz1TraceReplay test
```

- Result in isolated patch worktree: `FAIL` on `2026-04-18`
- Result in detached pre-Patch-2 baseline at `649e168c6`: `FAIL` on `2026-04-18`
- Classification: `PRE-EXISTING BRANCH BLOCKER`, not evidence that Patch 2 regressed parity

## Patch 3 - Typed provider lists

- Object suite command:

```powershell
mvn --% -q -Dmse=off -Dtest=com.openggf.tests.TestObjectManagerProviderIndexes,com.openggf.tests.physics.CollisionSystemTest,com.openggf.tests.TestHTZBossTouchResponse,com.openggf.tests.TestSolidOrderingCollisionTraces,com.openggf.tests.TestSolidOrderingSentinelsHeadless,com.openggf.tests.TestS2Htz1Headless,com.openggf.tests.TestS3kAiz1SkipHeadless test
```

- Result: `PASS` on `2026-04-18`

- Trace suite command:

```powershell
mvn --% -q -Dmse=off -Dtest=com.openggf.tests.trace.s1.TestS1Ghz1TraceReplay,com.openggf.tests.trace.s1.TestS1Mz1TraceReplay test
```

- Result in isolated patch worktree: `FAIL` on `2026-04-18`
- Result in detached pre-Patch-2 baseline at `649e168c6`: `FAIL` on `2026-04-18`
- Notes:
  - `TestS1Ghz1TraceReplay`: first error `frame 386`, `y_speed mismatch (expected=0x0300, actual=-0400)`
  - `TestS1Mz1TraceReplay`: first error `frame 376`, `angle mismatch (expected=0x0000, actual=0x00F8)`
  - Because both failures reproduce on the pre-Patch-2 baseline, this trace gate is currently branch debt rather than a Patch 3 regression signal.

## Patch 4 - Render/runtime cleanups

- Render suite command:

```powershell
mvn --% -q -Dmse=off -Dtest=com.openggf.tests.TestPerformanceProfilerGating,com.openggf.tests.graphics.RenderOrderTest,com.openggf.tests.graphics.FadeManagerTest,com.openggf.tests.graphics.PatternAtlasFallbackTest,com.openggf.tests.TestPaletteCycling,com.openggf.tests.TestSwScrlHtzEarthquakeMode,com.openggf.tests.TestS3kCnzBossScrollHandler test
```

- Result: `PASS` on `2026-04-18`

## Patch 5 - Audio block rendering

- Audio suite command:

```powershell
mvn --% -q -Dmse=off -Dtest=com.openggf.tests.TestSmpsDriver,com.openggf.tests.TestRomAudioIntegration,com.openggf.tests.TestPsgChipGpgxParity,com.openggf.tests.TestYm2612ChipBasics,com.openggf.tests.TestYm2612Attack,com.openggf.tests.TestYm2612AlgorithmRouting test
```

- Result: `PASS` on `2026-04-18`

- Manual smoke:
  - `NOT RUN`
