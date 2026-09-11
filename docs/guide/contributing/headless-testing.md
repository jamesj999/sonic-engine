# Headless testing

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
