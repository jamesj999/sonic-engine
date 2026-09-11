# Testing

This page covers the engine's test infrastructure and how to write tests for new features.

All new or updated tests must use JUnit 5 / Jupiter. Do not add JUnit 4 tests, rules, runners, or `org.junit.*` imports.

## Running Tests

```bash
# Run all tests
mvn test

# Run a single test class
mvn test -Dtest=TestCollisionLogic

# Run a single test method
mvn test -Dtest=TestCollisionLogic#testSlopeAngle

# Run with full Maven output (disable silent extension)
mvn test -Dmse=off
```

Tests are configured for parallel execution across 4 JVM forks locally (`1` fork in CI).
This significantly speeds up the full test suite but means tests must be independent of
each other.

## Rewind Tests And Benchmark

The rewind system has both ordinary regression tests and an opt-in benchmark. See
[Rewind System](rewind-system.md) for usage, limitations, and architecture details.

Run the regular rewind suite:

```bash
mvn -Dmse=off "-Dtest=*Rewind*" test
```

Run the compact schema foundation tests when changing automatic rewind capture,
field policy classification, or value codecs:

```bash
mvn -Dmse=off "-Dtest=TestRewindStateBuffer,TestRewindSchemaRegistry,TestCompactFieldCapturer,TestCompactFieldCapturerPolicy,TestRewindRecordCodecs,TestRewindHelperCodecs,TestRewindCollectionCodecs,TestRewindPolicyRegistry,TestRewindPlayerReferenceCodecs,TestRewindObjectReferenceCodecs,TestRewindIdentityTable" test
```

Generate the runtime-owner field inventory when planning object/player rewind
coverage work:

```bash
mvn -Dmse=off -DskipTests test-compile exec:java \
  "-Dexec.mainClass=com.openggf.tools.rewind.RewindFieldInventoryTool"
```

Add `"-Dexec.args=--object-rollout-candidates"` to list default object subclass
capture candidates instead of unsupported fields. Use that list before adding
per-object rewind annotations or overrides; most scalar object state should move
through the central default-capture path.

Use `"-Dexec.args=--annotation-density"` to report `@RewindTransient` /
`@RewindDeferred` density by class, declared type, and package, including
redundant transient annotations that central policy already infers.

When object, boss, badnik, or trace work touches source guards, treat baselines as
shrink-only migration artifacts. New code should prefer `ObjectControlState`,
`ObjectPlayerQuery` / `ObjectPlayerParticipationPolicy`, `ObjectLifetimeOps`, and
canonical profiles under `com.openggf.game.profiles.*`; only add a baseline entry for a
documented legacy bridge or a temporary `level.objects` compatibility wrapper, and remove
entries when a migration closes them.

Run the child/spawn graph audit when planning object family coverage:

```bash
mvn -Dmse=off -DskipTests test-compile exec:java \
  "-Dexec.mainClass=com.openggf.tools.rewind.ChildGraphPolicyInventoryTool"
```

Run the focused encounter validation foundation:

```bash
mvn -Dmse=off "-Dtest=TestRewindEncounterValidation" test
```

Run the focused presentation checks after changing reverse audio, trace rewind,
or fade behaviour:

```bash
mvn -Dmse=off "-Dtest=com.openggf.TestTraceSessionLauncherRewindPresentation,com.openggf.graphics.TestFadeManagerRewindSnapshot,com.openggf.game.rewind.TestLiveRewindManagerAudioCleanup,com.openggf.audio.TestAudioManagerRewindSuppression" test
```

Run the benchmark only when you need timing, footprint, or long-tail determinism data:

```bash
mvn -Dmse=off "-Dtest=RewindBenchmark" \
  "-Dopenggf.rewind.benchmark.run=true" test
```

The benchmark accepts `-Dopenggf.rewind.benchmark.keyframeInterval=<frames>` to compare
memory and seek behaviour at intervals such as `60`, `30`, and `15`. Results are printed
to stdout and written to `target/rewind-benchmark-results.json`.

## ROM-Dependent Tests

Many tests require ROM files to load level data, object art, or audio. These tests
**skip gracefully** when ROMs are absent, so CI and contributors without ROMs can still
run the rest of the suite.

### `@RequiresRom` Annotation

Annotate the test class with `@RequiresRom` and declare which game's ROM is needed.
The attached JUnit 5 extension handles ROM loading, game module detection, and environment
reset automatically. When the ROM is absent, the entire class is skipped.

```java
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.Test;

@RequiresRom(SonicGame.SONIC_2)
class TestMyFeature {

    @Test
    void testSomething() {
        // ROM is loaded, game module configured, environment reset —
        // just write your test logic
    }
}
```

Available game values: `SonicGame.SONIC_1`, `SonicGame.SONIC_2`, `SonicGame.SONIC_3K`.

The annotation system provides several benefits over manual checks:
- **Automatic environment reset** — `TestEnvironment.resetAll()` runs before each test.
- **ROM caching** — ROMs are loaded once per JVM and shared across all tests via `RomCache`.
- **Clean skip reporting** — JUnit reports skipped tests with a clear reason rather than
  silently passing.
- **Game module setup** — `GameModuleRegistry.detectAndSetModule()` is called automatically.

### `@RequiresGameModule` (No Real ROM)

For tests that need a game module configured but don't need real ROM data (e.g., testing
logic that only depends on which game is active):

```java
import com.openggf.tests.rules.RequiresGameModule;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.Test;

@RequiresGameModule(SonicGame.SONIC_2)
class TestGameSpecificLogic {

    @Test
    void testSomething() {
        // Game module is set, but no ROM loaded
    }
}
```

Note: `@RequiresRom` and `@RequiresGameModule` are mutually exclusive on the same class.

### `@FullReset`

Use `@FullReset` when a test needs the full singleton/runtime reset path between methods.
This is the preferred replacement for older manual reset boilerplate.

```java
import com.openggf.tests.FullReset;
import org.junit.jupiter.api.Test;

@FullReset
class TestSomethingStateful {

    @Test
    void testSomething() {
        // The test environment is fully reset before this runs
    }
}
```

### Legacy: `RomTestUtils` (Manual Check)

Older tests use `RomTestUtils` to check for ROM availability inline. This still works but
is not recommended for new tests:

```java
import static com.openggf.tests.RomTestUtils.ensureRomAvailable;

@Test
void testEHZCollision() {
    File romFile = ensureRomAvailable();
    if (romFile == null) {
        return;  // Skip: ROM not present
    }
    // ... test logic
}
```

### ROM Path Configuration

ROM files are found automatically by filename in the working directory. You can also
specify paths via system properties or environment variables:

```bash
# System properties
mvn test -Dsonic1.rom.path="path/to/sonic1.gen"
mvn test -Dsonic2.rom.path="path/to/sonic2.gen"
mvn test -Ds3k.rom.path=s3k.gen

# Environment variables
export SONIC_1_ROM_PATH="path/to/sonic1.gen"
export SONIC_2_ROM_PATH="path/to/sonic2.gen"
export SONIC_3K_ROM_PATH="path/to/s3k.gen"
```

## HeadlessTestFixture

The `HeadlessTestFixture` provides a builder-pattern API for setting up a test level
without a GPU window. It initializes the game module, loads level data, and provides
frame-stepping controls.

### Basic Usage

```java
@Test
void testPlayerLandsOnGround() {
    HeadlessTestFixture fixture = HeadlessTestFixture.builder()
            .withZoneAndAct(ZONE_EHZ, 0)
            .startPosition((short) 0x100, (short) 0x200)
            .build();

    // Step 60 frames (1 second at 60fps)
    fixture.stepIdleFrames(60);

    // Assert the player landed on the ground
    AbstractPlayableSprite player = fixture.sprite();
    assertTrue(player.isOnGround());
    assertTrue(player.getY() > 0x200);  // Fell from starting position
}
```

### Setting Up Specific Scenarios

```java
// Set up fixture with specific start position
HeadlessTestFixture fixture = HeadlessTestFixture.builder()
        .withZoneAndAct(ZONE_HTZ, act)
        .startPosition((short) 0x500, (short) 0x300)
        .build();

// Snap camera to player
fixture.camera().setX(0x500 - 160);
fixture.camera().setY(0x300 - 112);
fixture.camera().updatePosition(true);  // Snap (no smooth scroll)

// Step frames with per-frame inspection
for (int i = 0; i < 120; i++) {
    fixture.stepFrame(false, false, false, false, false);  // no input
    if (fixture.sprite().getX() >= targetX) {
        break;
    }
}
```

### What HeadlessTestFixture Does

The fixture calls the same update sequence as the real game loop:
1. `Camera.updatePosition()` -- Update camera before level events.
2. `LevelEventManager.update()` -- Process dynamic boundaries, zone-specific triggers.
3. `ParallaxManager.update()` -- Calculate scroll offsets.
4. Object `update()` calls for all active objects.

This means level events, camera boundaries, and object interactions all work in tests
the same way they do in the real game.

## Test Organisation

Tests are grouped by the systems they exercise:

```
src/test/java/com/openggf/
  physics/         -- Physics and collision tests
  level/           -- Level loading, object spawning tests
  audio/           -- Audio regression tests
  game/
    sonic1/        -- S1-specific tests
    sonic2/        -- S2-specific tests
    sonic3k/       -- S3K-specific tests
```

### Naming Conventions

- `Test<Feature>.java` -- Unit tests for a specific feature.
- `Test<Zone><Feature>.java` -- Tests for zone-specific behavior (e.g., `TestHTZEarthquake`).
- `Test<Object>Instance.java` -- Tests for a specific object type.

## Physics Integration Tests

Physics tests verify that the engine produces the same player positions and velocities as
the original game for specific scenarios.

### Pattern: Step-Frame Position Assertion

```java
@Test
void testRollingDownSlope() {
    HeadlessTestFixture fixture = HeadlessTestFixture.builder()
            .withZoneAndAct(ZONE_EHZ, 0)
            .startPosition((short) 0x800, (short) 0x340)
            .build();

    // Set initial speed
    fixture.sprite().setGroundSpeed(0x200);

    fixture.stepIdleFrames(30);

    // Player should have accelerated down the slope
    int speed = fixture.sprite().getGroundSpeed();
    assertTrue(speed > 0x200, "Player should accelerate on downhill slope");
}
```

### Pattern: Collision Edge Case

```java
@Test
void testWallCollisionAtSpeed() {
    HeadlessTestFixture fixture = HeadlessTestFixture.builder()
            .withZoneAndAct(ZONE_EHZ, 0)
            .startPosition((short) (wallX - 100), (short) wallY)
            .build();

    fixture.sprite().setGroundSpeed(0xC00);  // 12 pixels/frame

    fixture.stepIdleFrames(30);

    // Player should stop at the wall, not pass through
    assertTrue(fixture.sprite().getX() <= wallX);
    assertEquals(0, fixture.sprite().getGroundSpeed());
}
```

## Trace Replay Tests

Trace replay tests run a BK2 movie through the engine and compare each frame against a trace
recorded from the original ROM in BizHawk. They are used for parity work where ordinary unit tests
are too local to expose the real divergence.

Current examples:

- `TestS1Ghz1TraceReplay`
- `TestS1Mz1TraceReplay`

Run them with:

```bash
mvn test -Dtest=TestS1Ghz1TraceReplay,TestS1Mz1TraceReplay
```

For the full workflow, including recording traces and reading divergence reports, see
[Trace Replay Testing](trace-replay.md).

## Visual Regression Tests

Visual regression tests capture rendered frames and compare them against reference images.
They verify that changes to the rendering pipeline or art loading do not introduce visual
regressions.

These tests require ROM files and a GPU context. They typically:
1. Load a level and set the camera to a known position.
2. Render one or more frames.
3. Compare the rendered output against a stored reference image.
4. Fail if the pixel difference exceeds a threshold.

Visual regression tests are slower and more environment-sensitive than headless tests.
They are primarily used for validating art loading, palette, and rendering pipeline changes.

## Audio Regression Tests

Audio tests verify that the SMPS sequencer produces correct output for specific songs.
The approach:

1. Load a music track from the ROM.
2. Run the sequencer for N frames.
3. Capture the register writes or audio samples.
4. Compare against expected output (from SMPSPlay or a known-good capture).

These tests catch regressions in tempo calculation, note mapping, modulation, and channel
state management.

## Tips

- **Keep tests independent.** Tests run in parallel across multiple JVM forks. Do not
  depend on state from another test.
- **Skip gracefully without ROMs.** Use `@RequiresRom` to gate ROM-dependent tests.
  Never let a test fail just because a ROM is absent.
- **Use constants from the disassembly.** When asserting positions or velocities, use the
  same hex values that appear in the ASM. This makes it easier to trace failures back to
  the source.
- **Test behavior, not implementation.** Assert that the player lands on the ground, not
  that a specific internal method was called.

## Next Steps

- [Dev Setup](dev-setup.md) -- Build and run configuration
- [Tutorial: Implement an Object](tutorial-implement-object.md) -- Testing is step 7
- [Trace Replay Testing](trace-replay.md) -- ROM-vs-engine parity workflow
