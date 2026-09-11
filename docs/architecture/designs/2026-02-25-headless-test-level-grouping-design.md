
> Historical note: this document may mention legacy JUnit 4 migration details. New and updated tests in this repository must use JUnit 5 / Jupiter only; do not create new JUnit 4 tests, rules, or runners.


**Date:** 2026-02-25
**Goal:** Reduce test suite execution time by eliminating redundant level loads across headless tests.

## Problem

Each of the 31 HeadlessTestRunner test classes independently resets all singletons and loads its
level from ROM (involving Kosinski/Nemesis/KosinskiM decompression). Tests that share the same
zone+act reload identical level data unnecessarily.

## Approach

Use JUnit 4 `@ClassRule` to load the level once per test class. Group tests that share the same
game + zone + act into a single class. Per-test setup only resets the cheap state (sprite, camera,
game state) — level data stays loaded.

## Level Groups

| Group | Game | Zone/Act | Source Classes | Tests | Loads Saved |
|-------|------|----------|:--------------:|:-----:|:-----------:|
| S1_GHZ1 | S1 | zone 0, act 0 | 6 | 25 | 5 |
| S1_MZ2 | S1 | zone 1, act 1 | 1 | 1 | 0 |
| S1_LZ3 | S1 | zone 3, act 2 | 1 | 1 | 0 |
| S1_SBZ1 | S1 | zone 5, act 0 | 1 | 1 | 0 |
| S2_EHZ1 | S2 | zone 0, act 0 | 4 | 24 | 3 |
| S2_ARZ1 | S2 | zone 2, act 0 | 3 | 5 | 2 |
| S2_CNZ1 | S2 | zone 3, act 0 | 3 | 3 | 2 |
| S2_HTZ1 | S2 | zone 4, act 0 | 2 | 6 | 1 |
| S2_HTZ2 | S2 | zone 4, act 1 | 1 | 1 | 0 |
| S2_SCZ1 | S2 | zone 8, act 0 | 1 | 2 | 0 |
| S3K_AIZ1_SKIP | S3K | zone 0, act 0 (intro skip) | 2 | 7 | 1 |
| S3K_AIZ1_INTRO | S3K | zone 0, act 0 (intro enabled) | 4 | 5 | 3 |

**Total: 31 level loads → 12 level loads (19 eliminated, ~61% reduction)**

## Two-Layer Reset

### Once per class (`@BeforeClass` / `@ClassRule`)

- `TestEnvironment.resetAll()` — full singleton reset
- ROM load + game module detection (via RomCache)
- `GraphicsManager.getInstance().initHeadless()`
- `LevelManager.getInstance().loadZoneAndAct(zone, act)`
- `GroundSensor.setLevelManager(...)`

### Once per test (`@Before`)

New `TestEnvironment.resetPerTest()` resets only cheap per-test state:

- SpriteManager (clear sprites)
- Camera (reset position, unfreeze)
- CollisionSystem (reset instance)
- GameServices.gameState() (score, lives)
- TimerManager
- WaterSystem
- LevelEventManager (reset routine counters)
- ParallaxManager (reset shake state)

Does NOT touch: GameModuleRegistry, AudioManager, LevelManager, GraphicsManager,
GroundSensor.setLevelManager().

## Files To Create/Modify

### Modified

- `TestEnvironment.java` — add `resetPerTest()` method
- `RequiresRomRule.java` — support `@ClassRule` (static) usage

### New test classes (merged from multiple source classes)

| New Class | Merges From |
|-----------|-------------|
| `TestS1Ghz1Headless` | TestSonic1GhzSlopeTopDiagnostic, TestSonic1GhzTunnel, TestHeadlessSonic1PushStability, TestHeadlessSonic1EdgeBalance, TestHeadlessSonic1ObjectCollision, TestCrabmeatSpawnPosition |
| `TestS2Ehz1Headless` | TestHeadlessWallCollision, TestHeadlessStaticObjectPushStability, TestTailsCpuController, TestSignpostWalkOff |
| `TestS2Arz1Headless` | TestArzRunRight, TestArzSpringLoop, TestArzDebug |
| `TestS2Cnz1Headless` | TestCNZCeilingStateExit, TestCNZFlipperLaunch, TestCNZForcedSpinTunnel |
| `TestS2Htz1Headless` | TestHtzDropOnFloor, TestHTZInvisibleWallBug |
| `TestS3kAiz1SkipHeadless` | TestS3kAiz1SpawnStability, TestS3kAizHollowLogTraversal |
| `TestS3kAiz1IntroHeadless` | TestS3kAizIntroStateTimeline, TestS3kAizIntroCoverage, TestS3kAizIntroVisibleDiagnostics, TestAizIntroEmeraldCollection |

### Single-class groups (adopt @ClassRule, keep own files)

TestHeadlessMZ2PushBlockGap, TestS1SpikeDoubleHit, TestSbz1CreditsDemoBug,
TestHtzSpringLoop, TestSczSpawnOnTornado.

### Deleted (after migration)

All source test classes listed in "Merges From" above.

## Constraints

- Test method bodies move verbatim — no logic changes
- Test method names preserved for git blame traceability
- All grouped classes in `com.openggf.tests` package
- HeadlessTestRunner unchanged
- RomCache unchanged
- Non-headless tests untouched
- TODO/Ignore tests (TestTodo8, TestTodo35) untouched
