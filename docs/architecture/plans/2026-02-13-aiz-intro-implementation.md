# AIZ1 Intro Cinematic Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Implement the full AIZ1 intro cinematic (plane arrival, Super Sonic transform, Knuckles emerald theft) matching the S3K ROM pixel-for-pixel.

**Architecture:** Three independent objects orchestrate the cutscene: `AizPlaneIntroInstance` (14-routine master), `CutsceneKnucklesAiz1Instance` (7 routines), and `AizEmeraldScatterInstance` (7 instances). A `Sonic3kAIZEvents` zone handler spawns the intro object. See `docs/architecture/designs/2026-02-13-aiz-intro-design.md` for full design.

**Tech Stack:** Java 21, existing `AbstractLevelEventManager` framework, `AbstractObjectInstance` for cutscene objects, KosinskiM decompression for art loading.

**Key reference files:**
- Design: `docs/architecture/designs/2026-02-13-aiz-intro-design.md`
- Discrepancies: `docs/S3K_KNOWN_DISCREPANCIES.md`
- ROM disassembly: `docs/skdisasm/sonic3k.asm` (lines 135464-136023 for intro, 128584-128752 for Knuckles)
- S2 event pattern: `src/main/java/uk/co/jamesj999/sonic/game/sonic2/events/Sonic2EHZEvents.java`
- S2 zone base: `src/main/java/uk/co/jamesj999/sonic/game/sonic2/events/Sonic2ZoneEvents.java`

**Base path:** `src/main/java/uk/co/jamesj999/sonic/`
**Test path:** `src/test/java/uk/co/jamesj999/sonic/`

---

### Task 1: S3K Zone Event Base Class

Create the S3K equivalent of `Sonic2ZoneEvents` - a base class for per-zone event handlers.

**Files:**
- Create: `game/sonic3k/events/Sonic3kZoneEvents.java`
- Test: `game/sonic3k/events/TestSonic3kZoneEvents.java`

**Step 1: Write the test**

```java
package uk.co.jamesj999.sonic.game.sonic3k.events;

import org.junit.jupiter.api.Test;
import uk.co.jamesj999.sonic.camera.Camera;
import static org.junit.jupiter.api.Assertions.*;

public class TestSonic3kZoneEvents {

    // Concrete subclass for testing
    static class TestableZoneEvents extends Sonic3kZoneEvents {
        int updateCallCount = 0;

        TestableZoneEvents() {
            super(Camera.getInstance());
        }

        @Override
        public void update(int act, int frameCounter) {
            updateCallCount++;
        }
    }

    @Test
    public void initResetsEventRoutine() {
        var events = new TestableZoneEvents();
        events.setEventRoutine(6);
        events.init(0);
        assertEquals(0, events.getEventRoutine());
    }

    @Test
    public void eventRoutineGetterSetter() {
        var events = new TestableZoneEvents();
        events.setEventRoutine(4);
        assertEquals(4, events.getEventRoutine());
    }
}
```

**Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=TestSonic3kZoneEvents -pl . -q`
Expected: FAIL - class `Sonic3kZoneEvents` does not exist

**Step 3: Implement the base class**

Mirror `Sonic2ZoneEvents` structure. Key fields: `camera`, `eventRoutine`, `bossSpawnDelay`. Abstract method: `update(int act, int frameCounter)`. Methods: `init(act)`, `getEventRoutine()`, `setEventRoutine()`, `spawnObject()`, `loadPalette()`.

```java
package uk.co.jamesj999.sonic.game.sonic3k.events;

import uk.co.jamesj999.sonic.camera.Camera;
import uk.co.jamesj999.sonic.game.GameServices;
import uk.co.jamesj999.sonic.level.LevelManager;
import uk.co.jamesj999.sonic.level.objects.ObjectInstance;

import java.util.logging.Logger;

/**
 * Base class for Sonic 3&K per-zone dynamic level events.
 * S3K equivalent of Sonic2ZoneEvents.
 *
 * Each zone has its own handler with an eventRoutine counter that tracks
 * progress through cutscene/boss sequences. S3K uses stride 4 per routine
 * advance (ROM: addq.b #4,routine(a0)).
 */
public abstract class Sonic3kZoneEvents {
    private static final Logger LOGGER = Logger.getLogger(Sonic3kZoneEvents.class.getName());
    private static final int PALETTE_LINE_SIZE = 32;

    protected final Camera camera;
    protected int eventRoutine;
    protected int bossSpawnDelay;

    protected Sonic3kZoneEvents(Camera camera) {
        this.camera = camera;
    }

    public void init(int act) {
        eventRoutine = 0;
        bossSpawnDelay = 0;
    }

    public abstract void update(int act, int frameCounter);

    public int getEventRoutine() { return eventRoutine; }
    public void setEventRoutine(int routine) { this.eventRoutine = routine; }

    protected void spawnObject(ObjectInstance object) {
        LevelManager lm = LevelManager.getInstance();
        if (lm.getObjectManager() != null) {
            lm.getObjectManager().addDynamicObject(object);
        }
    }

    protected static void loadPalette(int paletteLine, int romAddr) {
        try {
            byte[] data = GameServices.rom().getRom().readBytes(romAddr, PALETTE_LINE_SIZE);
            LevelManager.getInstance().updatePalette(paletteLine, data);
        } catch (Exception e) {
            LOGGER.warning("Failed to load palette from 0x" +
                    Integer.toHexString(romAddr) + ": " + e.getMessage());
        }
    }
}
```

**Step 4: Run tests**

Run: `mvn test -Dtest=TestSonic3kZoneEvents -pl . -q`
Expected: PASS

**Step 5: Commit**

```bash
git add src/main/java/uk/co/jamesj999/sonic/game/sonic3k/events/Sonic3kZoneEvents.java src/test/java/uk/co/jamesj999/sonic/game/sonic3k/events/TestSonic3kZoneEvents.java
git commit -m "feat: add S3K zone event base class"
```

---

### Task 2: AIZ Event Handler + Dispatch Wiring

Create the AIZ zone event handler and wire it into `Sonic3kLevelEventManager`.

**Files:**
- Create: `game/sonic3k/events/Sonic3kAIZEvents.java`
- Modify: `game/sonic3k/Sonic3kLevelEventManager.java`
- Test: `game/sonic3k/events/TestSonic3kAIZEvents.java`

**Step 1: Write the test**

```java
package uk.co.jamesj999.sonic.game.sonic3k.events;

import org.junit.jupiter.api.Test;
import uk.co.jamesj999.sonic.camera.Camera;
import uk.co.jamesj999.sonic.game.sonic3k.Sonic3kLoadBootstrap;
import static org.junit.jupiter.api.Assertions.*;

public class TestSonic3kAIZEvents {

    @Test
    public void initWithIntroSkipDoesNotSpawnIntroObject() {
        Camera camera = Camera.getInstance();
        var events = new Sonic3kAIZEvents(camera,
                new Sonic3kLoadBootstrap(Sonic3kLoadBootstrap.Mode.AIZ1_GAMEPLAY_AFTER_INTRO));
        events.init(0);
        // No crash, no spawn (ObjectManager is null in test)
        assertEquals(0, events.getEventRoutine());
    }

    @Test
    public void initForAct1WithNoneBootstrapRequestsIntro() {
        Camera camera = Camera.getInstance();
        var events = new Sonic3kAIZEvents(camera, Sonic3kLoadBootstrap.NONE);
        // When bootstrap is NONE and act is 0, intro should be requested
        assertTrue(events.shouldSpawnIntro(0));
    }

    @Test
    public void initForAct2DoesNotRequestIntro() {
        Camera camera = Camera.getInstance();
        var events = new Sonic3kAIZEvents(camera, Sonic3kLoadBootstrap.NONE);
        assertFalse(events.shouldSpawnIntro(1));
    }
}
```

**Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=TestSonic3kAIZEvents -pl . -q`
Expected: FAIL - class not found

**Step 3: Implement AIZ events**

```java
package uk.co.jamesj999.sonic.game.sonic3k.events;

import uk.co.jamesj999.sonic.camera.Camera;
import uk.co.jamesj999.sonic.game.sonic3k.Sonic3kLoadBootstrap;

import java.util.logging.Logger;

/**
 * Angel Island Zone dynamic level events.
 * ROM: ScreenEvent_AIZ (sonic3k.asm)
 *
 * Act 1: Intro cinematic (when bootstrap is NONE + Sonic character).
 * Act 2: Boss arena + fire transition (future work).
 */
public class Sonic3kAIZEvents extends Sonic3kZoneEvents {
    private static final Logger LOG = Logger.getLogger(Sonic3kAIZEvents.class.getName());

    private final Sonic3kLoadBootstrap bootstrap;
    private boolean introSpawned;

    public Sonic3kAIZEvents(Camera camera, Sonic3kLoadBootstrap bootstrap) {
        super(camera);
        this.bootstrap = bootstrap;
    }

    @Override
    public void init(int act) {
        super.init(act);
        introSpawned = false;
        if (shouldSpawnIntro(act)) {
            LOG.info("AIZ1 intro: will spawn intro object");
            // Actual spawn happens in update() on first frame,
            // after ObjectManager is ready
        }
    }

    @Override
    public void update(int act, int frameCounter) {
        if (act == 0 && !introSpawned && shouldSpawnIntro(act)) {
            spawnIntroObject();
            introSpawned = true;
        }
        // Post-intro gameplay events (dynamic boundaries, boss) - future work
    }

    boolean shouldSpawnIntro(int act) {
        return act == 0 && !bootstrap.isAiz1GameplayAfterIntro();
    }

    private void spawnIntroObject() {
        // TODO: spawnObject(new AizPlaneIntroInstance(...));
        LOG.info("AIZ1 intro: spawning plane intro object");
    }
}
```

**Step 4: Wire dispatch in Sonic3kLevelEventManager**

Add to `Sonic3kLevelEventManager.java`:

```java
// Add field:
private Sonic3kAIZEvents aizEvents;

// In onInitLevel(), after bootstrap resolution:
aizEvents = new Sonic3kAIZEvents(camera, bootstrap);
aizEvents.init(act);

// In onUpdate():
if (currentZone == Sonic3kZoneIds.ZONE_AIZ && aizEvents != null) {
    aizEvents.update(currentAct, frameCounter);
}
```

Import `Sonic3kZoneIds` and `Sonic3kAIZEvents`.

**Step 5: Run all S3K tests**

Run: `mvn test -Dtest="TestSonic3kAIZEvents,TestSonic3kBootstrapResolver,TestSonic3kDecodingUtils" -pl . -q`
Expected: ALL PASS

**Step 6: Commit**

```bash
git add src/main/java/uk/co/jamesj999/sonic/game/sonic3k/events/Sonic3kAIZEvents.java src/test/java/uk/co/jamesj999/sonic/game/sonic3k/events/TestSonic3kAIZEvents.java src/main/java/uk/co/jamesj999/sonic/game/sonic3k/Sonic3kLevelEventManager.java
git commit -m "feat: add AIZ event handler with zone dispatch wiring"
```

---

### Task 3: SwingMotion Utility

Port of the ROM's `Swing_UpAndDown` subroutine. Used by the plane's oscillating descent.

**Files:**
- Create: `physics/SwingMotion.java`
- Test: `physics/TestSwingMotion.java`

**Step 1: Write the test**

The ROM logic (sonic3k.asm:177851-177880):
- Input: acceleration (`$40`), velocity (`y_vel`), max velocity (`$3E`), direction (bit 0 of `$38`)
- When direction=0 (swinging up): subtract acceleration from velocity. If velocity <= -max, flip direction.
- When direction=1 (swinging down): add acceleration to velocity. If velocity >= max, flip direction.
- Returns d3=1 when direction changed (peak reached), d3=0 otherwise.

```java
package uk.co.jamesj999.sonic.physics;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class TestSwingMotion {

    @Test
    public void swingsDownFromZeroVelocity() {
        // direction=true (down), accel=0x18, vel=0, max=0x100
        var result = SwingMotion.update(0x18, 0, 0x100, true);
        assertEquals(0x18, result.velocity());
        assertTrue(result.directionDown());
        assertFalse(result.directionChanged());
    }

    @Test
    public void reversesAtMaxVelocity() {
        // direction=true (down), vel is near max
        var result = SwingMotion.update(0x18, 0xF0, 0x100, true);
        // 0xF0 + 0x18 = 0x108 >= 0x100, should reverse
        assertEquals(0xF0 + 0x18, result.velocity()); // velocity still updated
        assertFalse(result.directionDown()); // direction flipped
        assertTrue(result.directionChanged());
    }

    @Test
    public void swingsUpFromZero() {
        // direction=false (up), accel=0x18, vel=0, max=0x100
        var result = SwingMotion.update(0x18, 0, 0x100, false);
        assertEquals(-0x18, result.velocity());
        assertFalse(result.directionDown());
        assertFalse(result.directionChanged());
    }

    @Test
    public void reversesAtNegativeMaxVelocity() {
        // direction=false (up), vel is near -max
        var result = SwingMotion.update(0x18, -0xF0, 0x100, false);
        // -0xF0 - 0x18 = -0x108, magnitude >= 0x100, should reverse
        assertTrue(result.directionDown());
        assertTrue(result.directionChanged());
    }
}
```

**Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=TestSwingMotion -pl . -q`
Expected: FAIL

**Step 3: Implement SwingMotion**

```java
package uk.co.jamesj999.sonic.physics;

/**
 * Port of Swing_UpAndDown (sonic3k.asm:177851).
 * Oscillating motion utility for pendulum/bobbing objects.
 *
 * The object swings between +max and -max velocity, reversing direction
 * at each peak. Used by AIZ intro plane, swinging platforms, etc.
 */
public final class SwingMotion {
    private SwingMotion() {}

    public record Result(int velocity, boolean directionDown, boolean directionChanged) {}

    /**
     * Update swing motion for one frame.
     *
     * @param acceleration per-frame acceleration magnitude (ROM: $40(a0))
     * @param velocity     current velocity (ROM: y_vel)
     * @param maxVelocity  peak velocity magnitude (ROM: $3E(a0))
     * @param directionDown true=swinging down/positive, false=swinging up/negative (ROM: bit 0 of $38)
     * @return updated velocity, direction, and whether direction changed this frame
     */
    public static Result update(int acceleration, int velocity, int maxVelocity, boolean directionDown) {
        boolean changed = false;

        if (!directionDown) {
            // Swinging up: subtract acceleration
            velocity -= acceleration;
            if (velocity <= -maxVelocity) {
                directionDown = true;
                changed = true;
            }
        } else {
            // Swinging down: add acceleration
            velocity += acceleration;
            if (velocity >= maxVelocity) {
                directionDown = false;
                changed = true;
            }
        }

        return new Result(velocity, directionDown, changed);
    }
}
```

**Step 4: Run tests**

Run: `mvn test -Dtest=TestSwingMotion -pl . -q`
Expected: PASS

**Step 5: Commit**

```bash
git add src/main/java/uk/co/jamesj999/sonic/physics/SwingMotion.java src/test/java/uk/co/jamesj999/sonic/physics/TestSwingMotion.java
git commit -m "feat: add SwingMotion utility (port of Swing_UpAndDown)"
```

---

### Task 4: Find S3K Intro ROM Addresses

Use `RomOffsetFinder` to locate all art, mapping, and palette addresses needed by the intro. This is a research task - no code changes, just documenting addresses.

**Step 1: Search for AIZ intro art labels**

```bash
mvn exec:java -Dexec.mainClass="uk.co.jamesj999.sonic.tools.disasm.RomOffsetFinder" -Dexec.args="--game s3k search AIZIntro" -q
mvn exec:java -Dexec.mainClass="uk.co.jamesj999.sonic.tools.disasm.RomOffsetFinder" -Dexec.args="--game s3k search CutsceneKnux" -q
mvn exec:java -Dexec.mainClass="uk.co.jamesj999.sonic.tools.disasm.RomOffsetFinder" -Dexec.args="--game s3k search PalCycle_Super" -q
mvn exec:java -Dexec.mainClass="uk.co.jamesj999.sonic.tools.disasm.RomOffsetFinder" -Dexec.args="--game s3k search Map_SuperSonic" -q
```

**Step 2: Cross-reference with disassembly**

Search `docs/skdisasm/sonic3k.asm` for:
- `ArtKosM_AIZIntroPlane` - KosinskiM art address
- `ArtKosM_AIZIntroEmeralds` - KosinskiM art address
- `Map_AIZIntroPlane` - Mapping data address
- `Map_AIZIntroWaves` - Mapping data address
- `Map_CutsceneKnux` - Mapping data address
- `Pal_CutsceneKnux` - Palette data address
- `Pal_AIZIntroEmeralds` - Palette data address
- `PalCycle_SuperSonic` - Super Sonic palette cycle data
- `ArtTile_AIZIntroPlane` - VRAM tile destination
- `ArtTile_AIZIntroEmeralds` - VRAM tile destination
- `ArtTile_AIZIntroSprites` - VRAM tile destination
- `ArtTile_CutsceneKnux` - VRAM tile destination
- `byte_67A9B` - Wave animation script
- `byte_666A9`, `byte_666AF`, `byte_666B9` - Knuckles animation scripts

Use `grep` in the disasm to find each label's data and calculate ROM offsets.

**Step 3: Add constants to Sonic3kConstants.java**

Add a new section for AIZ intro addresses. Pattern: match existing constant style.

```java
// === AIZ Intro Cinematic ===
public static final int AIZ_INTRO_PLANE_ART_ADDR = 0x??????;  // ArtKosM_AIZIntroPlane
public static final int AIZ_INTRO_EMERALDS_ART_ADDR = 0x??????;  // ArtKosM_AIZIntroEmeralds
public static final int AIZ_INTRO_PLANE_MAP_ADDR = 0x??????;  // Map_AIZIntroPlane
public static final int AIZ_INTRO_WAVES_MAP_ADDR = 0x??????;  // Map_AIZIntroWaves
public static final int CUTSCENE_KNUX_MAP_ADDR = 0x??????;  // Map_CutsceneKnux
public static final int PAL_CUTSCENE_KNUX_ADDR = 0x??????;  // Pal_CutsceneKnux
public static final int PAL_AIZ_INTRO_EMERALDS_ADDR = 0x??????;  // Pal_AIZIntroEmeralds
public static final int PAL_CYCLE_SUPER_SONIC_ADDR = 0x??????;  // PalCycle_SuperSonic (60 bytes)
```

**Step 4: Verify addresses against ROM**

For each address found, verify by:
1. Reading the data from ROM at that offset
2. Checking it matches expected format (KosinskiM header for art, valid colors for palettes)
3. Attempting decompression for compressed assets

**Step 5: Commit**

```bash
git add src/main/java/uk/co/jamesj999/sonic/game/sonic3k/constants/Sonic3kConstants.java
git commit -m "feat: add AIZ intro ROM address constants"
```

---

### Task 5: S3K Intro Palette Cycling

Implement the `sub_679B8` Super palette cycling used by the intro object. This is separate from the `SuperStateController` - it's a standalone helper for the intro cutscene.

**Files:**
- Create: `game/sonic3k/objects/AizIntroPaletteCycler.java`
- Test: `game/sonic3k/objects/TestAizIntroPaletteCycler.java`

**Step 1: Write the test**

The ROM cycles `PalCycle_SuperSonic` data (10 entries x 3 words = 60 bytes). Cycling range in word offsets: 0x24-0x36, timer=6 frames, wraps from >0x36 back to 0x24.

Each entry is 6 bytes (3 Mega Drive colors). The palette frame index advances by 6 each cycle.

```java
package uk.co.jamesj999.sonic.game.sonic3k.objects;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class TestAizIntroPaletteCycler {

    @Test
    public void initialPaletteFrameIs0x24() {
        var cycler = new AizIntroPaletteCycler();
        cycler.init();
        assertEquals(0x24, cycler.getPaletteFrame());
    }

    @Test
    public void timerCountsDownFrom6() {
        var cycler = new AizIntroPaletteCycler();
        cycler.init();
        // Advance 5 frames - timer should decrement but not trigger
        for (int i = 0; i < 5; i++) {
            cycler.advance();
        }
        assertEquals(0x24, cycler.getPaletteFrame()); // Not advanced yet
    }

    @Test
    public void paletteAdvancesAfter6Frames() {
        var cycler = new AizIntroPaletteCycler();
        cycler.init();
        // Advance 7 frames (timer starts at 6, fires on reaching 0)
        for (int i = 0; i < 7; i++) {
            cycler.advance();
        }
        assertEquals(0x2A, cycler.getPaletteFrame()); // 0x24 + 6
    }

    @Test
    public void paletteWrapsFrom0x36BackTo0x24() {
        var cycler = new AizIntroPaletteCycler();
        cycler.init();
        cycler.setPaletteFrame(0x36);
        // Force one more cycle
        for (int i = 0; i < 7; i++) {
            cycler.advance();
        }
        // 0x36 + 6 = 0x3C > 0x36, should wrap to 0x24
        assertEquals(0x24, cycler.getPaletteFrame());
    }

    @Test
    public void mappingFrameAlternatesOnVblankParity() {
        var cycler = new AizIntroPaletteCycler();
        assertEquals(0x21, cycler.getMappingFrame(0)); // even frame
        assertEquals(0x22, cycler.getMappingFrame(1)); // odd frame
        assertEquals(0x21, cycler.getMappingFrame(2)); // even frame
    }
}
```

**Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=TestAizIntroPaletteCycler -pl . -q`

**Step 3: Implement the cycler**

```java
package uk.co.jamesj999.sonic.game.sonic3k.objects;

/**
 * Palette cycling for the AIZ1 intro's Super Sonic visual effect.
 * Port of sub_679B8 (sonic3k.asm:135904).
 *
 * This is NOT the SuperStateController palette cycling - it's a standalone
 * helper used only by the intro cutscene object. Cycles through
 * PalCycle_SuperSonic entries at 6-frame intervals.
 */
public class AizIntroPaletteCycler {
    private static final int TIMER_PERIOD = 6;
    private static final int FRAME_ADVANCE = 6;   // bytes per cycle step
    private static final int CYCLE_MIN = 0x24;     // cycling range start
    private static final int CYCLE_MAX = 0x36;     // cycling range end (inclusive)
    private static final int MAPPING_FRAME_EVEN = 0x21;
    private static final int MAPPING_FRAME_ODD = 0x22;

    private int paletteTimer;
    private int paletteFrame;

    public void init() {
        paletteTimer = TIMER_PERIOD;
        paletteFrame = CYCLE_MIN;
    }

    /**
     * Advance one frame. Decrements timer; on expiry, advances palette frame
     * and resets timer. Wraps frame index within cycling range.
     */
    public void advance() {
        paletteTimer--;
        if (paletteTimer < 0) {
            paletteTimer = TIMER_PERIOD;
            paletteFrame += FRAME_ADVANCE;
            if (paletteFrame > CYCLE_MAX) {
                paletteFrame = CYCLE_MIN;
            }
        }
    }

    /** Get the Super Sonic mapping frame based on V-blank parity. */
    public int getMappingFrame(int frameCounter) {
        return (frameCounter & 1) != 0 ? MAPPING_FRAME_ODD : MAPPING_FRAME_EVEN;
    }

    public int getPaletteFrame() { return paletteFrame; }
    public void setPaletteFrame(int frame) { this.paletteFrame = frame; }
}
```

**Step 4: Run tests**

Run: `mvn test -Dtest=TestAizIntroPaletteCycler -pl . -q`
Expected: PASS

**Step 5: Commit**

```bash
git add src/main/java/uk/co/jamesj999/sonic/game/sonic3k/objects/AizIntroPaletteCycler.java src/test/java/uk/co/jamesj999/sonic/game/sonic3k/objects/TestAizIntroPaletteCycler.java
git commit -m "feat: add AIZ intro palette cycler (port of sub_679B8)"
```

---

### Task 6: AizPlaneIntroInstance - Core State Machine

Create the master intro object with 14-routine dispatch. Start with the state machine skeleton and player control flow. Art loading and rendering are separate (Task 9).

**Files:**
- Create: `game/sonic3k/objects/AizPlaneIntroInstance.java`
- Test: `game/sonic3k/objects/TestAizPlaneIntroInstance.java`

**Step 1: Write the test**

Focus on state machine transitions and player control, NOT rendering.

```java
package uk.co.jamesj999.sonic.game.sonic3k.objects;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uk.co.jamesj999.sonic.level.objects.ObjectSpawn;
import static org.junit.jupiter.api.Assertions.*;

public class TestAizPlaneIntroInstance {

    private AizPlaneIntroInstance intro;

    @BeforeEach
    public void setUp() {
        intro = new AizPlaneIntroInstance(
                new ObjectSpawn(0x60, 0x30, 0, 0, 0, false, 0));
    }

    @Test
    public void initialRoutineIsZero() {
        assertEquals(0, intro.getRoutine());
    }

    @Test
    public void initSetsCorrectPosition() {
        assertEquals(0x60, intro.getX());
        assertEquals(0x30, intro.getY());
    }

    @Test
    public void routineAdvancesBy2() {
        intro.advanceRoutine();
        assertEquals(2, intro.getRoutine());
    }

    @Test
    public void waveSpawnIntervalIs5Frames() {
        assertEquals(5, AizPlaneIntroInstance.WAVE_SPAWN_INTERVAL);
    }

    @Test
    public void knucklesSpawnTriggerAt0x918() {
        assertEquals(0x918, AizPlaneIntroInstance.KNUCKLES_SPAWN_X);
    }

    @Test
    public void explosionTriggerAt0x13D0() {
        assertEquals(0x13D0, AizPlaneIntroInstance.EXPLOSION_TRIGGER_X);
    }
}
```

**Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=TestAizPlaneIntroInstance -pl . -q`

**Step 3: Implement the state machine**

Create `AizPlaneIntroInstance` extending `AbstractObjectInstance`. Key structure:

- `routine` field (0-13, maps to ROM routines 0x00-0x1A by dividing by 2)
- Per-routine update methods dispatched via switch
- Position (`x`, `y`), velocity (`xVel`, `yVel`), timer fields
- `AizIntroPaletteCycler` instance
- Wave spawn interval counter
- References to spawned children (plane, Knuckles)

The object does NOT need `AbstractPlayableSprite` - it's a cutscene orchestrator.

ROM routine mapping: our routine 0 = ROM 0x00, routine 1 = ROM 0x02, etc. (stride 2 in ROM, but we use sequential integers internally).

**Critical constants from ROM disassembly:**
```java
static final int WAVE_SPAWN_INTERVAL = 5;
static final int KNUCKLES_SPAWN_X = 0x918;
static final int PLANE_ADJUST_X = 0x1240;
static final int EXPLOSION_TRIGGER_X = 0x13D0;
static final int WALK_RIGHT_TARGET = 0x200;
static final int WALK_LEFT_TARGET = 0x120;
static final int WALK_SPEED = 4;
static final int DESCENT_DECEL = 0x18;
static final int HORIZ_DECEL = 0x40;
static final int GROUND_Y = 0x130;
```

**Player control transitions** (from design doc control flow table):
- Routine 0: Lock player (`controlLocked=true`, `objectControlled=true`)
- Routine 10 end: Release player (`controlLocked=false`, `objectControlled=false`), activate Super Sonic
- Routine 13: Lock player again, disable Super Sonic, set velocities

Reference: `AbstractPlayableSprite.setControlLocked()`, `.setObjectControlled()`, `.setSuperSonic()`, `.setXVelocity()`, `.setYVelocity()`.

Access player via: `Camera.getInstance().getFocusedSprite()`

**Step 4: Run tests**

Run: `mvn test -Dtest=TestAizPlaneIntroInstance -pl . -q`
Expected: PASS

**Step 5: Commit**

```bash
git add src/main/java/uk/co/jamesj999/sonic/game/sonic3k/objects/AizPlaneIntroInstance.java src/test/java/uk/co/jamesj999/sonic/game/sonic3k/objects/TestAizPlaneIntroInstance.java
git commit -m "feat: add AizPlaneIntroInstance core state machine"
```

---

### Task 7: Wire Intro Spawn in AIZ Events

Connect `Sonic3kAIZEvents.spawnIntroObject()` to actually create and spawn `AizPlaneIntroInstance`.

**Files:**
- Modify: `game/sonic3k/events/Sonic3kAIZEvents.java`

**Step 1: Update spawnIntroObject()**

Replace the TODO stub:

```java
private void spawnIntroObject() {
    ObjectSpawn spawn = new ObjectSpawn(0x60, 0x30, 0, 0, 0, false, 0);
    AizPlaneIntroInstance intro = new AizPlaneIntroInstance(spawn);
    spawnObject(intro);
    LOG.info("AIZ1 intro: spawned plane intro object");
}
```

Add import for `AizPlaneIntroInstance` and `ObjectSpawn`.

**Step 2: Run existing S3K tests**

Run: `mvn test -Dtest="TestSonic3kBootstrapResolver,TestSonic3kDecodingUtils,TestSonic3kAIZEvents" -pl . -q`
Expected: ALL PASS (intro-skip tests unaffected since they use `AIZ1_GAMEPLAY_AFTER_INTRO`)

**Step 3: Commit**

```bash
git add src/main/java/uk/co/jamesj999/sonic/game/sonic3k/events/Sonic3kAIZEvents.java
git commit -m "feat: wire intro object spawn in AIZ event handler"
```

---

### Task 8: AizEmeraldScatterInstance

The 7 emerald objects that scatter from Sonic at the explosion and get collected by Knuckles.

**Files:**
- Create: `game/sonic3k/objects/AizEmeraldScatterInstance.java`
- Test: `game/sonic3k/objects/TestAizEmeraldScatterInstance.java`

**Step 1: Write the test**

```java
package uk.co.jamesj999.sonic.game.sonic3k.objects;

import org.junit.jupiter.api.Test;
import uk.co.jamesj999.sonic.level.objects.ObjectSpawn;
import static org.junit.jupiter.api.Assertions.*;

public class TestAizEmeraldScatterInstance {

    @Test
    public void mappingFrameIsDerivedFromSubtype() {
        // ROM: mapping_frame = subtype >> 1
        for (int subtype = 0; subtype < 7; subtype++) {
            var spawn = new ObjectSpawn(100, 100, 0, subtype, 0, false, 0);
            var emerald = new AizEmeraldScatterInstance(spawn);
            assertEquals(subtype >> 1, emerald.getMappingFrame());
        }
    }

    @Test
    public void startsInFallingPhase() {
        var spawn = new ObjectSpawn(100, 100, 0, 0, 0, false, 0);
        var emerald = new AizEmeraldScatterInstance(spawn);
        assertEquals(AizEmeraldScatterInstance.Phase.FALLING, emerald.getPhase());
    }

    @Test
    public void pickupCheckUsesSubtypeBit1ForDirection() {
        // subtype bit 1 = 0: collected when Knuckles moves right (positive x_vel)
        // subtype bit 1 = 1: collected when Knuckles moves left (negative x_vel)
        var spawn0 = new ObjectSpawn(100, 100, 0, 0, 0, false, 0);
        var em0 = new AizEmeraldScatterInstance(spawn0);
        assertTrue(em0.canBeCollectedByVelocity(0x600));   // positive vel, bit1=0
        assertFalse(em0.canBeCollectedByVelocity(-0x600)); // negative vel, bit1=0

        var spawn2 = new ObjectSpawn(100, 100, 0, 2, 0, false, 0);
        var em2 = new AizEmeraldScatterInstance(spawn2);
        assertTrue(em2.canBeCollectedByVelocity(-0x600));  // negative vel, bit1=1
        assertFalse(em2.canBeCollectedByVelocity(0x600));  // positive vel, bit1=1
    }

    @Test
    public void proximityCheckIs8Pixels() {
        assertEquals(8, AizEmeraldScatterInstance.PICKUP_PROXIMITY);
    }
}
```

**Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=TestAizEmeraldScatterInstance -pl . -q`

**Step 3: Implement**

Key ROM logic from `loc_67900` and `loc_6795C`:
- Init: Position at player coordinates, set velocity from lookup table, `y_radius=4`
- Falling: Apply gravity (`y_vel += GRAVITY`), check floor. On landing, transition to grounded.
- Grounded: Each frame, check Knuckles proximity (within 8px X). If `subtype bit 1` matches Knuckles' velocity direction, delete self.

**Velocity table** (from `Set_IndexedVelocity` with index 0x40 - 7 entries):
This needs to be extracted from the ROM. The velocities determine the scatter pattern. Search for the velocity table at or near the indexed velocity data in the disassembly.

```java
// Placeholder - exact values from ROM velocity table
private static final int[][] SCATTER_VELOCITIES = {
    {-0x200, -0x400}, {-0x100, -0x500}, {-0x080, -0x300},
    {0x000, -0x600},
    {0x080, -0x300}, {0x100, -0x500}, {0x200, -0x400}
};
```

**Step 4: Run tests**

Run: `mvn test -Dtest=TestAizEmeraldScatterInstance -pl . -q`
Expected: PASS

**Step 5: Commit**

```bash
git add src/main/java/uk/co/jamesj999/sonic/game/sonic3k/objects/AizEmeraldScatterInstance.java src/test/java/uk/co/jamesj999/sonic/game/sonic3k/objects/TestAizEmeraldScatterInstance.java
git commit -m "feat: add emerald scatter objects with Knuckles proximity pickup"
```

---

### Task 9: CutsceneKnucklesAiz1Instance

The Knuckles cutscene object with 7 routines.

**Files:**
- Create: `game/sonic3k/objects/CutsceneKnucklesAiz1Instance.java`
- Test: `game/sonic3k/objects/TestCutsceneKnucklesAiz1Instance.java`

**Step 1: Write the test**

```java
package uk.co.jamesj999.sonic.game.sonic3k.objects;

import org.junit.jupiter.api.Test;
import uk.co.jamesj999.sonic.level.objects.ObjectSpawn;
import static org.junit.jupiter.api.Assertions.*;

public class TestCutsceneKnucklesAiz1Instance {

    @Test
    public void initPositionIsCorrect() {
        var spawn = new ObjectSpawn(0x1400, 0x440, 0, 0, 0, false, 0);
        var knux = new CutsceneKnucklesAiz1Instance(spawn);
        assertEquals(0x1400, knux.getX());
        assertEquals(0x440, knux.getY());
    }

    @Test
    public void startsInWaitRoutine() {
        var spawn = new ObjectSpawn(0x1400, 0x440, 0, 0, 0, false, 0);
        var knux = new CutsceneKnucklesAiz1Instance(spawn);
        assertEquals(0, knux.getRoutine());
    }

    @Test
    public void paceTimerIs0x29Frames() {
        assertEquals(0x29, CutsceneKnucklesAiz1Instance.PACE_TIMER);
    }

    @Test
    public void standTimerIs0x7FFrames() {
        assertEquals(0x7F, CutsceneKnucklesAiz1Instance.STAND_TIMER);
    }

    @Test
    public void laughTimerIs0x3FFrames() {
        assertEquals(0x3F, CutsceneKnucklesAiz1Instance.LAUGH_TIMER);
    }

    @Test
    public void paceVelocityIs0x600() {
        assertEquals(0x600, CutsceneKnucklesAiz1Instance.PACE_VELOCITY);
    }

    @Test
    public void fallVelocityYIsMinus0x600() {
        assertEquals(-0x600, CutsceneKnucklesAiz1Instance.FALL_INIT_Y_VEL);
    }

    @Test
    public void fallVelocityXIs0x80() {
        assertEquals(0x80, CutsceneKnucklesAiz1Instance.FALL_INIT_X_VEL);
    }
}
```

**Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=TestCutsceneKnucklesAiz1Instance -pl . -q`

**Step 3: Implement**

7-routine state machine from ROM `CutsceneKnux_AIZ1` (sonic3k.asm:128608-128752):

| Routine | Field | Action |
|---------|-------|--------|
| 0 | Init | Set up attributes, position at (0x1400, 0x440), wait for trigger |
| 1 | Wait | Poll for parent trigger (intro object's explosion). When triggered: set fall velocities. |
| 2 | Fall | Apply gravity, animate fall frames. On floor: stand timer. |
| 3 | Stand | Wait 0x7F frames. Then: flip, walk left. |
| 4 | Pace | Walk left 0x29 frames, reverse, walk right 0x29 frames. Emeralds collected during this. Then: laugh setup. |
| 5 | Laugh | Animate laugh 0x3F frames. Then: walk right, fade to level music. |
| 6 | Exit | Walk right until offscreen. Then: unlock controls, spawn title card, delete self. |

**Trigger mechanism:** The intro object sets a `triggered` flag (or uses `setDestroyed()`) that Knuckles checks. In our implementation, store a reference to the intro object and check `isDestroyed()` or a dedicated flag.

**Music fade:** At routine 5 end, call `AudioManager.getInstance().fadeToMusic(aizMusicId)` or equivalent.

**Cleanup at exit:** `camera.getFocusedSprite().setControlLocked(false)`, clear forced input, set `levelStarted = true`.

**Step 4: Run tests**

Run: `mvn test -Dtest=TestCutsceneKnucklesAiz1Instance -pl . -q`
Expected: PASS

**Step 5: Commit**

```bash
git add src/main/java/uk/co/jamesj999/sonic/game/sonic3k/objects/CutsceneKnucklesAiz1Instance.java src/test/java/uk/co/jamesj999/sonic/game/sonic3k/objects/TestCutsceneKnucklesAiz1Instance.java
git commit -m "feat: add Knuckles AIZ1 cutscene object (7 routines)"
```

---

### Task 10: Art Loading Infrastructure

Load the intro-specific art from ROM. This is the bridge between the state machines (Tasks 5-9) and rendering.

**Files:**
- Create: `game/sonic3k/objects/AizIntroArtLoader.java`
- Modify: `game/sonic3k/constants/Sonic3kConstants.java` (if not done in Task 4)

**Step 1: Implement art loader**

Centralize all intro art loading in one class. Called during `AizPlaneIntroInstance.init()`.

```java
package uk.co.jamesj999.sonic.game.sonic3k.objects;

import uk.co.jamesj999.sonic.data.Rom;
import uk.co.jamesj999.sonic.game.GameServices;
import uk.co.jamesj999.sonic.game.sonic3k.constants.Sonic3kConstants;
import uk.co.jamesj999.sonic.graphics.GraphicsManager;
import uk.co.jamesj999.sonic.level.Pattern;
import uk.co.jamesj999.sonic.level.resources.CompressionType;
import uk.co.jamesj999.sonic.level.resources.ResourceLoader;

import java.io.IOException;
import java.util.logging.Logger;

/**
 * Loads all art, mappings, and palettes needed by the AIZ1 intro cinematic.
 * Called once during intro object initialization.
 *
 * See S3K_KNOWN_DISCREPANCIES.md #3 (Immediate Art Loading).
 */
public class AizIntroArtLoader {
    private static final Logger LOG = Logger.getLogger(AizIntroArtLoader.class.getName());

    // Pattern base IDs for intro-specific art (using extended range to avoid conflicts)
    public static final int PLANE_PATTERN_BASE = 0x30000;
    public static final int EMERALD_PATTERN_BASE = 0x30100;
    public static final int WAVE_PATTERN_BASE = 0x30200;
    public static final int KNUCKLES_PATTERN_BASE = 0x30300;

    public static void loadAllIntroArt() {
        try {
            loadPlaneArt();
            loadEmeraldArt();
            // Wave art uses intro sprite tiles (shared)
            // Knuckles art loaded separately when Knuckles spawns
        } catch (IOException e) {
            LOG.warning("Failed to load AIZ intro art: " + e.getMessage());
        }
    }

    public static void loadKnucklesArt() {
        try {
            // Load Knuckles cutscene art from ROM
            // Address from Sonic3kConstants
            Rom rom = GameServices.rom().getRom();
            // ... decompress and cache patterns
        } catch (IOException e) {
            LOG.warning("Failed to load Knuckles cutscene art: " + e.getMessage());
        }
    }

    private static void loadPlaneArt() throws IOException {
        // Decompress KosinskiM plane art and write to pattern table
        // ... implementation depends on ROM address from Task 4
    }

    private static void loadEmeraldArt() throws IOException {
        // Decompress KosinskiM emerald art
    }
}
```

**Note:** The exact implementation depends on ROM addresses found in Task 4. The pattern base IDs use the extended range (0x30000+) following the project's convention for non-level art (see `KNOWN_DISCREPANCIES.md` Pattern ID Ranges).

**Step 2: Load palettes**

Add palette loading methods:
- `loadSuperSonicPaletteCycleData()` - reads 60 bytes of `PalCycle_SuperSonic` from ROM, returns `short[]` (3 colors per frame, 10 frames)
- `loadCutsceneKnucklesPalette()` - loads `Pal_CutsceneKnux` into palette line 1
- `loadEmeraldIntoPalette()` - loads `Pal_AIZIntroEmeralds` into palette line 4

**Step 3: Commit**

```bash
git add src/main/java/uk/co/jamesj999/sonic/game/sonic3k/objects/AizIntroArtLoader.java
git commit -m "feat: add AIZ intro art loader infrastructure"
```

---

### Task 11: Child Objects - Plane, Waves, Glow

Create the visual child objects spawned by the intro orchestrator.

**Files:**
- Create: `game/sonic3k/objects/AizIntroPlaneChild.java`
- Create: `game/sonic3k/objects/AizIntroWaveChild.java`
- Create: `game/sonic3k/objects/AizIntroEmeraldGlowChild.java`

**Step 1: Implement Plane Child**

The plane sprite that swings during descent and spirals away. Uses `SwingMotion` utility.

Key behavior (from `loc_6777A` in sonic3k.asm):
- Init: Set up swing parameters, load plane art, spawn 2 emerald glow children
- Update: Call `SwingMotion.update()` to oscillate, `MoveSprite2` to move
- Eventually spirals offscreen and self-deletes

**Step 2: Implement Wave Child**

Splash sprites spawned every 5 frames (from `loc_678A0`):
- Init: Use `Map_AIZIntroWaves` mappings, priority 0x100, width 0x10
- Update: Scroll left by parent's scroll speed. Animate. Self-delete when X < 0x60.

**Step 3: Implement Emerald Glow Children**

2 children spawned by the plane child (from `ChildObjDat_67A62`):
- Follow plane position with fixed offsets
- Visual glow effect on the plane's emeralds

**Step 4: Commit**

```bash
git add src/main/java/uk/co/jamesj999/sonic/game/sonic3k/objects/AizIntroPlaneChild.java src/main/java/uk/co/jamesj999/sonic/game/sonic3k/objects/AizIntroWaveChild.java src/main/java/uk/co/jamesj999/sonic/game/sonic3k/objects/AizIntroEmeraldGlowChild.java
git commit -m "feat: add plane, wave, and emerald glow child objects"
```

---

### Task 12: Knuckles Rock Child

The breakable rock spawned by Knuckles that shatters on trigger.

**Files:**
- Create: `game/sonic3k/objects/CutsceneKnucklesRockChild.java`

**Step 1: Implement**

From `loc_61F60` in the disassembly:
- Init: Set up attributes from `ObjDat3_66432`. Invisible initially.
- Wait: Poll parent (Knuckles) `status bit 7`. When set: break into pieces.
- Break: Use `BreakObjectToPieces` equivalent - spawn multiple fragment sprites with scattered velocities.

If the engine doesn't have a `BreakObjectToPieces` utility, implement a simple version: spawn N small sprites with random velocities that fall with gravity and self-delete offscreen.

**Step 2: Commit**

```bash
git add src/main/java/uk/co/jamesj999/sonic/game/sonic3k/objects/CutsceneKnucklesRockChild.java
git commit -m "feat: add Knuckles rock child (breakable object)"
```

---

### Task 13: Full Integration

Wire everything together and verify the complete sequence works.

**Files:**
- Modify: `game/sonic3k/objects/AizPlaneIntroInstance.java` - wire child spawning
- Modify: `game/sonic3k/events/Sonic3kAIZEvents.java` - verify spawn chain
- Verify: existing S3K tests still pass

**Step 1: Wire child spawning in AizPlaneIntroInstance**

In `routine0Init()`:
```java
// Spawn plane child
AizIntroPlaneChild plane = new AizIntroPlaneChild(
        new ObjectSpawn(x - 0x22, y + 0x2C, 0, 0, 0, false, 0), this);
spawnChild(plane);

// Load all intro art
AizIntroArtLoader.loadAllIntroArt();
```

In the wave spawn callback (every 5 frames):
```java
if (x >= 0x80) {
    AizIntroWaveChild wave = new AizIntroWaveChild(
            new ObjectSpawn(x, y + 0x18, 0, 0, 0, false, 0), this);
    spawnChild(wave);
}
```

In routine 11 (Knuckles spawn):
```java
CutsceneKnucklesAiz1Instance knuckles = new CutsceneKnucklesAiz1Instance(
        new ObjectSpawn(0x1400, 0x440, 0, 0, 0, false, 0));
knuckles.setParentIntro(this);
spawnChild(knuckles);
AizIntroArtLoader.loadKnucklesArt();
```

In routine 13 (emerald explosion):
```java
AbstractPlayableSprite player = Camera.getInstance().getFocusedSprite();
int px = player.getCentreX();
int py = player.getCentreY();
for (int i = 0; i < 7; i++) {
    AizEmeraldScatterInstance emerald = new AizEmeraldScatterInstance(
            new ObjectSpawn(px, py, 0, i, 0, false, 0));
    spawnChild(emerald);
}
```

**Step 2: Run ALL existing S3K tests**

```bash
mvn test -Dtest="TestS3kAiz1SpawnStability,TestSonic3kBootstrapResolver,TestSonic3kDecodingUtils,TestSonic3kLevelLoading" -pl . -q
```

Expected: ALL PASS. The intro-skip test (`TestS3kAiz1SpawnStability`) uses `S3K_SKIP_AIZ1_INTRO=true`, so the intro object is never spawned and existing behavior is unchanged.

**Step 3: Manual visual test**

```bash
# Run with intro enabled (requires S3K ROM in working directory)
java -jar target/sonic-engine-*-jar-with-dependencies.jar
# In config.json, set S3K_SKIP_AIZ1_INTRO=false, load S3K ROM
# Verify: plane descends, Super Sonic transforms, Knuckles appears, emeralds scatter
```

**Step 4: Commit**

```bash
git add -A
git commit -m "feat: complete AIZ1 intro cinematic integration"
```

---

### Task 14: Verification and Cleanup

Final pass to ensure everything is correct and no existing tests are broken.

**Step 1: Run full test suite**

```bash
mvn test -pl . -q
```

Expected: ALL PASS including:
- `TestS3kAiz1SpawnStability` (intro-skip path unchanged)
- `TestSonic3kBootstrapResolver` (bootstrap logic unchanged)
- `TestSonic3kDecodingUtils`
- `TestSonic3kLevelLoading`
- All new tests from this plan

**Step 2: Verify S3K_KNOWN_DISCREPANCIES.md is up to date**

Check that `docs/S3K_KNOWN_DISCREPANCIES.md` documents all 4 discrepancies identified in the design doc.

**Step 3: Final commit**

```bash
git add -A
git commit -m "chore: AIZ1 intro cinematic verification pass"
```

---

## Dependency Graph

```
Task 1 (Zone base) ──→ Task 2 (AIZ events) ──→ Task 7 (Wire spawn)
Task 3 (SwingMotion) ──────────────────────────→ Task 11 (Plane child)
Task 4 (ROM addresses) ──→ Task 10 (Art loader) ──→ Task 11 (Children)
Task 5 (Palette cycler) ──→ Task 6 (Intro object) ──→ Task 13 (Integration)
Task 8 (Emerald scatter) ──────────────────────────→ Task 13
Task 9 (Knuckles) ──→ Task 12 (Rock child) ──────→ Task 13
                                                      ↓
                                                   Task 14 (Verify)
```

**Critical path:** Tasks 1→2→7→6→13→14
**Parallelizable:** Tasks 3, 4, 5 can run in parallel. Tasks 8, 9, 11, 12 can run in parallel after their deps.
