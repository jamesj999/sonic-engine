# Headless testing

For local check selection, use the [test category runner](../../../tools/testing/README.md#test-categories).
It selects ordinary tests from the change against the integration base and runs structural
guards separately. Focused categories do not replace affected trace fixtures or native tests;
CI and release full-suite coverage remains unchanged.


`HeadlessTestRunner` (`com.openggf.tests.HeadlessTestRunner`) runs physics and collision
integration tests without an OpenGL context.

```java
HeadlessTestRunner runner = new HeadlessTestRunner(sprite);
runner.stepFrame(up, down, left, right, jump);  // one frame
runner.stepIdleFrames(5);                       // several idle frames
```

Tests are JUnit 5 / Jupiter only — no JUnit 4 tests, rules, runners, or `org.junit.*`
imports.

## Preferred setup

Use `@ExtendWith(SingletonResetExtension.class)` or the `@FullReset` annotation for
automated singleton teardown between tests. Both call `resetState()` on all singletons.

```java
@ExtendWith(SingletonResetExtension.class)
class MyTest {
    @Test void testSomething() { /* singletons auto-reset */ }
}
```

### Class-level teardown is automatic

Every reset above runs *before* a test; nothing restores state after the last test of a
class. `HeadlessStateTeardownExtension` (registered globally through
`src/test/resources/META-INF/services/org.junit.jupiter.api.extension.Extension` with
`junit.jupiter.extensions.autodetection.enabled=true` in `junit-platform.properties`)
therefore calls `TestEnvironment.resetAll()` after every top-level test class, so a
`HeadlessTestFixture`, `SharedLevel`, gameplay session, or configuration write cannot leak
into the next class of a reused Surefire fork. It does not run after `@Nested` classes and
does not replace per-test hygiene: a class whose own tests depend on a clean baseline still
declares `SingletonResetExtension`, `@RequiresRom`, or an `@AfterEach`.
`TestHeadlessStateTeardownGuard` (run under `-Pguards`) pins the wiring and
`TestHeadlessStateTeardownExtension` proves it is active at runtime.

## Manual setup (legacy)

`HeadlessTestFixture.java` (`com.openggf.tests`) implements these steps and is the worked example to read.

1. Reset test state: `TestEnvironment.resetAll()` (use `resetState()`, **not** the
   deprecated `resetInstance()`).
2. Initialise headless graphics: `GameServices.graphics().initHeadless()`.
3. Create and register the playable sprite **first** — add the main sprite to
   `GameServices.sprites()` and set camera focus before `loadZoneAndAct(...)`; the current
   `LevelManager` load path requires it.
4. Load the level: `GameServices.level().loadZoneAndAct(zone, act)`.
5. Fix `GroundSensor`: `GroundSensor.setLevelManager(GameServices.level())` **after** the
   level load — it is a static field and goes stale between tests.
6. Update the camera: `GameServices.camera().updatePosition(true)` **after** the level load,
   since bounds are set during load. Failing to reset `Camera` can also leave `frozen=true`
   behind from a death sequence in a previous test.

## Test infrastructure

| Class | Purpose |
|---|---|
| `SingletonResetExtension` | JUnit 5 extension for automated singleton teardown |
| `@FullReset` | Annotation triggering a full engine reset |
| `HeadlessStateTeardownExtension` | Auto-registered; resets the engine baseline after every top-level test class |
| `StubObjectServices` | Test double for `ObjectServices` |
| `TestObjectServicesMigrationGuard` | Scanner-based guard preventing singleton access in objects |
| `TestNoServicesInObjectConstructors` | Ensures objects don't call `services()` during construction |
| `TestNoDirectMapMutationsInGameplay` | Enforces the level-mutation routing rule |

Tests live under `src/test/java/com/openggf/tests` (plus `com/openggf/game/` for the
physics suites) and cover ROM loading, decompression, collision, singleton lifecycle, and
services migration.

## ROM-backed tests

Pass the ROM path discovered at the project root via the relevant
`-D<game>.rom.path=...` property. `TestRomLogic` is skipped when its ROM is absent, and
`TestCollisionLogic` skips via an assumption when no ROM file is present — both are
accepted conditional skips, not disabled tests.

Set `startup.legalDisclaimer=false` in tests that boot the full `Engine`, or the boot path
will sit on `GameMode.LEGAL_DISCLAIMER`.


## Audio references and test concurrency

Ordinary and smoke runs exclude `audio-reference`, `audio-stress`, and `audio-local-wave` tests.
Synthetic chip scripts, minimal driver programs, mixer behavior, malformed input
and bounded comparator cases remain ordinary correctness checks. The expensive
streaming test retains its original size and 32 MiB child-JVM limit in the stress
lane:

```bash
mvn -Dmse=off -Paudio-stress test -B
```

Run game-reference comparisons with an external fixture root and verified local
ROM paths. The root uses `audio/parity/...` and `audio/nuked-opn2/port/...`; raw
captures are never Maven resources or public build inputs. The reference lane
must fail when its prerequisites are missing. Independent reference expectations
must not be regenerated from the engine under test to make a comparison pass.

```bash
mvn -Dmse=off -Paudio-reference -Dopenggf.audio.fixtures=/absolute/reference-root \
  -Dsonic1.rom.path=/absolute/s1.gen -Dsonic2.rom.path=/absolute/s2.gen \
  -Ds3k.rom.path=/absolute/s3k.gen test -B
```

The previous optional WAV comparisons use `-Paudio-local-wave` with WAV files
under `<external fixture root>/audio-reference`. They are local regression
snapshots, not independent chip/ROM oracles; none is bundled publicly. The WAV
generator also requires that external root. Missing files fail the explicitly
selected lane instead of silently skipping comparisons.

Run these profiles separately: Maven combines scalar profile settings by
precedence, so activating several audio profiles together does not run their union.

These are separate deeper validation commands; an ordinary-suite pass does not
claim audio-reference or memory-stress coverage. Run both for audio release
evidence. Existing Git history still contains earlier fixture versions; removing
files from the current tree does not erase that history or certify unrelated
trace fixtures as free of game assets.

For machines with room for two 3 GiB heaps plus Maven and native memory, use
`mvn -Dmse=off -Ptest-concurrent test -B`, or the category runner's `--workers 2`.
The default remains one worker. Tests stay serial inside each JVM because the
engine's global teardown is unsafe alongside another class. Multiple forks divide
classes, so one large test class remains a lower bound on elapsed time. Guards
continue with one JVM. Do not run two Maven processes in the same worktree.
