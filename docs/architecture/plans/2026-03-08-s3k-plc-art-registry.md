# S3K PLC Art Registry Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Build a data-driven `Sonic3kPlcArtRegistry` that declares per-zone art entries so `Sonic3kObjectArtProvider` loads all 14 zones without zone-conditional `if` blocks.

**Architecture:** Three record types (`StandaloneArtEntry`, `LevelArtEntry`, `ZoneArtPlan`) describe each zone's art needs. A static registry method `getPlan(zoneIndex, actIndex)` returns the combined plan. `Sonic3kObjectArtProvider` iterates the plan instead of using `if (zoneIndex == 0x00)` blocks.

**Tech Stack:** Java 21, existing `Sonic3kObjectArt` builders, `S3kSpriteDataLoader`, `PlcParser`, RomOffsetFinder for address discovery.

**Design doc:** `docs/architecture/designs/2026-03-08-s3k-plc-art-registry-design.md`

---

### Task 1: Create Sonic3kPlcArtRegistry with Records and Shared Entries

**Files:**
- Create: `src/main/java/com/openggf/game/sonic3k/Sonic3kPlcArtRegistry.java`
- Test: `src/test/java/com/openggf/game/sonic3k/TestSonic3kPlcArtRegistry.java`

**Step 1: Write the failing test**

```java
package com.openggf.game.sonic3k;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class TestSonic3kPlcArtRegistry {

    @Test
    public void sharedLevelArtEntriesIncludeSpikesAndSprings() {
        Sonic3kPlcArtRegistry.ZoneArtPlan plan =
                Sonic3kPlcArtRegistry.getPlan(0x00, 0); // AIZ Act 1
        assertNotNull(plan);

        // Shared level-art entries (spikes + 6 springs) should be present in every zone
        assertTrue("Plan should have level-art entries",
                plan.levelArt().size() >= 7);

        // Check spikes entry exists with correct key
        boolean hasSpikes = plan.levelArt().stream()
                .anyMatch(e -> e.key().equals(Sonic3kObjectArtKeys.SPIKES));
        assertTrue("Plan should include spikes", hasSpikes);
    }

    @Test
    public void unknownZoneReturnsSharedOnlyPlan() {
        // Zone 0xFF doesn't exist — should still return shared spikes/springs
        Sonic3kPlcArtRegistry.ZoneArtPlan plan =
                Sonic3kPlcArtRegistry.getPlan(0xFF, 0);
        assertNotNull(plan);
        assertTrue("Unknown zone should still have shared level art",
                plan.levelArt().size() >= 7);
        assertTrue("Unknown zone should have no standalone art",
                plan.standaloneArt().isEmpty());
    }
}
```

**Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=TestSonic3kPlcArtRegistry -q`
Expected: FAIL — `Sonic3kPlcArtRegistry` class does not exist.

**Step 3: Write minimal implementation**

Create `Sonic3kPlcArtRegistry.java`:

```java
package com.openggf.game.sonic3k;

import com.openggf.game.sonic3k.constants.Sonic3kConstants;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class Sonic3kPlcArtRegistry {

    // === Records ===

    public enum CompressionType {
        KOSINSKI_MODULED, NEMESIS, UNCOMPRESSED
    }

    /**
     * Standalone art (badniks, bosses) — decompressed independently from ROM.
     * @param key        art key for sheet lookup
     * @param artAddr    ROM address of compressed/uncompressed art
     * @param compression compression type
     * @param artSize    byte size (only for UNCOMPRESSED, -1 otherwise)
     * @param mappingAddr ROM address of S3K mapping table
     * @param palette    palette line (0-3)
     * @param dplcAddr   -1 if no DPLC, else ROM address for DPLC remap
     */
    public record StandaloneArtEntry(
            String key, int artAddr, CompressionType compression,
            int artSize, int mappingAddr, int palette, int dplcAddr) {}

    /**
     * Level-art object — references patterns already in the level buffer.
     * @param key          art key for sheet lookup
     * @param mappingAddr  ROM address of mapping table (-1 for hardcoded builder)
     * @param artTileBase  level buffer tile base index
     * @param palette      palette line (0-3)
     * @param builderName  name of hardcoded builder method (null if ROM-parsed)
     */
    public record LevelArtEntry(
            String key, int mappingAddr, int artTileBase,
            int palette, String builderName) {}

    /** Complete art plan for one zone+act. */
    public record ZoneArtPlan(
            List<StandaloneArtEntry> standaloneArt,
            List<LevelArtEntry> levelArt) {}

    // === Shared level-art entries (present in all zones) ===

    private static final List<LevelArtEntry> SHARED_LEVEL_ART = List.of(
            new LevelArtEntry(Sonic3kObjectArtKeys.SPIKES,
                    -1, Sonic3kConstants.ARTTILE_SPIKES_SPRINGS, 0, "buildSpikesSheet"),
            new LevelArtEntry(Sonic3kObjectArtKeys.SPRING_VERTICAL,
                    -1, Sonic3kConstants.ARTTILE_SPIKES_SPRINGS + 0x10, 0, "buildSpringVerticalSheet"),
            new LevelArtEntry(Sonic3kObjectArtKeys.SPRING_VERTICAL_YELLOW,
                    -1, Sonic3kConstants.ARTTILE_SPIKES_SPRINGS + 0x10, 0, "buildSpringVerticalYellowSheet"),
            new LevelArtEntry(Sonic3kObjectArtKeys.SPRING_HORIZONTAL,
                    -1, Sonic3kConstants.ARTTILE_SPIKES_SPRINGS + 0x20, 0, "buildSpringHorizontalSheet"),
            new LevelArtEntry(Sonic3kObjectArtKeys.SPRING_HORIZONTAL_YELLOW,
                    -1, Sonic3kConstants.ARTTILE_SPIKES_SPRINGS + 0x20, 0, "buildSpringHorizontalYellowSheet"),
            new LevelArtEntry(Sonic3kObjectArtKeys.SPRING_DIAGONAL,
                    -1, Sonic3kConstants.ARTTILE_DIAGONAL_SPRING, 0, "buildSpringDiagonalSheet"),
            new LevelArtEntry(Sonic3kObjectArtKeys.SPRING_DIAGONAL_YELLOW,
                    -1, Sonic3kConstants.ARTTILE_DIAGONAL_SPRING, 0, "buildSpringDiagonalYellowSheet")
    );

    private Sonic3kPlcArtRegistry() {}

    /**
     * Returns the combined art plan for a zone+act.
     * Always includes shared entries (spikes/springs).
     * Zone-specific entries are added when registered.
     */
    public static ZoneArtPlan getPlan(int zoneIndex, int actIndex) {
        List<StandaloneArtEntry> standalone = new ArrayList<>();
        List<LevelArtEntry> levelArt = new ArrayList<>(SHARED_LEVEL_ART);

        // Zone-specific entries will be added in subsequent tasks
        addZoneEntries(zoneIndex, actIndex, standalone, levelArt);

        return new ZoneArtPlan(
                Collections.unmodifiableList(standalone),
                Collections.unmodifiableList(levelArt));
    }

    private static void addZoneEntries(int zoneIndex, int actIndex,
            List<StandaloneArtEntry> standalone, List<LevelArtEntry> levelArt) {
        // Populated in Task 2+
    }
}
```

**Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=TestSonic3kPlcArtRegistry -q`
Expected: PASS

**Step 5: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/Sonic3kPlcArtRegistry.java src/test/java/com/openggf/game/sonic3k/TestSonic3kPlcArtRegistry.java
git commit -m "feat(s3k): add Sonic3kPlcArtRegistry with shared spike/spring entries"
```

---

### Task 2: Populate AIZ Entries in Registry

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kPlcArtRegistry.java`
- Modify: `src/test/java/com/openggf/game/sonic3k/TestSonic3kPlcArtRegistry.java`

**Step 1: Write the failing test**

Add to `TestSonic3kPlcArtRegistry.java`:

```java
@Test
public void aiz1PlanIncludesBadnikAndLevelArt() {
    Sonic3kPlcArtRegistry.ZoneArtPlan plan =
            Sonic3kPlcArtRegistry.getPlan(0x00, 0); // AIZ Act 1

    // AIZ has 3 standalone badniks: Bloominator, Rhinobot, MonkeyDude
    assertEquals("AIZ should have 3 standalone badnik entries",
            3, plan.standaloneArt().size());

    // Verify Bloominator entry details
    Sonic3kPlcArtRegistry.StandaloneArtEntry bloominator = plan.standaloneArt().stream()
            .filter(e -> e.key().equals(Sonic3kObjectArtKeys.BLOOMINATOR))
            .findFirst().orElse(null);
    assertNotNull("Bloominator should be in AIZ standalone art", bloominator);
    assertEquals(Sonic3kPlcArtRegistry.CompressionType.KOSINSKI_MODULED,
            bloominator.compression());

    // Verify Rhinobot uses DPLC (uncompressed + DPLC remap)
    Sonic3kPlcArtRegistry.StandaloneArtEntry rhinobot = plan.standaloneArt().stream()
            .filter(e -> e.key().equals(Sonic3kObjectArtKeys.RHINOBOT))
            .findFirst().orElse(null);
    assertNotNull("Rhinobot should be in AIZ standalone art", rhinobot);
    assertEquals(Sonic3kPlcArtRegistry.CompressionType.UNCOMPRESSED,
            rhinobot.compression());
    assertTrue("Rhinobot should have DPLC addr", rhinobot.dplcAddr() > 0);

    // AIZ level art should include zone-specific entries beyond shared
    // Shared = 7 (spikes + 6 springs), AIZ adds: rideVine, animStill, tree, zipline,
    // fgPlant, rock1, rock2, collapse1, collapse2 = 9 more
    assertTrue("AIZ should have shared + zone-specific level art",
            plan.levelArt().size() > 7);
}

@Test
public void aiz2PlanHasAct2SpecificEntries() {
    Sonic3kPlcArtRegistry.ZoneArtPlan plan =
            Sonic3kPlcArtRegistry.getPlan(0x00, 1); // AIZ Act 2

    // Both acts share the same badniks
    assertEquals(3, plan.standaloneArt().size());

    // ACT 2 has its own rock and collapse platform entries
    boolean hasAiz2Rock = plan.levelArt().stream()
            .anyMatch(e -> e.key().equals(Sonic3kObjectArtKeys.AIZ2_ROCK));
    assertTrue("AIZ2 should have act-2 rock", hasAiz2Rock);
}
```

**Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=TestSonic3kPlcArtRegistry -q`
Expected: FAIL — AIZ standalone art list is empty.

**Step 3: Implement AIZ entries in addZoneEntries()**

In `Sonic3kPlcArtRegistry.java`, populate the `addZoneEntries` method:

```java
private static void addZoneEntries(int zoneIndex, int actIndex,
        List<StandaloneArtEntry> standalone, List<LevelArtEntry> levelArt) {
    switch (zoneIndex) {
        case 0x00 -> addAizEntries(actIndex, standalone, levelArt);
        // Other zones added in subsequent tasks
    }
}

private static void addAizEntries(int actIndex,
        List<StandaloneArtEntry> standalone, List<LevelArtEntry> levelArt) {
    // --- Standalone badniks ---
    standalone.add(new StandaloneArtEntry(
            Sonic3kObjectArtKeys.BLOOMINATOR,
            Sonic3kConstants.ART_KOSM_AIZ_BLOOMINATOR_ADDR,
            CompressionType.KOSINSKI_MODULED, -1,
            Sonic3kConstants.MAP_BLOOMINATOR_ADDR, 1, -1));
    standalone.add(new StandaloneArtEntry(
            Sonic3kObjectArtKeys.RHINOBOT,
            Sonic3kConstants.ART_UNC_AIZ_RHINOBOT_ADDR,
            CompressionType.UNCOMPRESSED,
            Sonic3kConstants.ART_UNC_AIZ_RHINOBOT_SIZE,
            Sonic3kConstants.MAP_RHINOBOT_ADDR, 1,
            Sonic3kConstants.DPLC_RHINOBOT_ADDR));
    standalone.add(new StandaloneArtEntry(
            Sonic3kObjectArtKeys.MONKEY_DUDE,
            Sonic3kConstants.ART_KOSM_AIZ_MONKEY_DUDE_ADDR,
            CompressionType.KOSINSKI_MODULED, -1,
            Sonic3kConstants.MAP_MONKEY_DUDE_ADDR, 1, -1));

    // --- Level-art objects (shared across AIZ acts) ---
    levelArt.add(new LevelArtEntry(
            Sonic3kObjectArtKeys.AIZ_RIDE_VINE,
            Sonic3kConstants.MAP_AIZ_MHZ_RIDE_VINE_ADDR,
            Sonic3kConstants.ARTTILE_AIZ_SWING_VINE, 0, null));
    levelArt.add(new LevelArtEntry(
            Sonic3kObjectArtKeys.ANIMATED_STILL_SPRITES,
            -1, Sonic3kConstants.ARTTILE_AIZ_MISC2, 3,
            "buildAnimatedStillSpritesSheet"));
    levelArt.add(new LevelArtEntry(
            Sonic3kObjectArtKeys.AIZ_FOREGROUND_PLANT,
            -1, Sonic3kConstants.ARTTILE_AIZ_MISC1, 2,
            "buildAizForegroundPlantSheet"));

    // --- Act-specific level art ---
    if (actIndex == 0) {
        levelArt.add(new LevelArtEntry(
                Sonic3kObjectArtKeys.AIZ1_TREE,
                -1, 1, 2, "buildAiz1TreeSheet"));
        levelArt.add(new LevelArtEntry(
                Sonic3kObjectArtKeys.AIZ1_ZIPLINE_PEG,
                -1, Sonic3kConstants.ARTTILE_AIZ_SLIDE_ROPE, 2,
                "buildAiz1ZiplinePegSheet"));
        levelArt.add(new LevelArtEntry(
                Sonic3kObjectArtKeys.AIZ1_ROCK,
                Sonic3kConstants.MAP_AIZ_ROCK_ADDR,
                Sonic3kConstants.ARTTILE_AIZ_MISC1, 1, null));
        levelArt.add(new LevelArtEntry(
                Sonic3kObjectArtKeys.COLLAPSING_PLATFORM_AIZ1,
                Sonic3kConstants.MAP_AIZ_COLLAPSING_PLATFORM_ADDR,
                1, 2, null));
    } else {
        levelArt.add(new LevelArtEntry(
                Sonic3kObjectArtKeys.AIZ2_ROCK,
                Sonic3kConstants.MAP_AIZ_ROCK2_ADDR,
                Sonic3kConstants.ARTTILE_AIZ_MISC2, 2, null));
        levelArt.add(new LevelArtEntry(
                Sonic3kObjectArtKeys.COLLAPSING_PLATFORM_AIZ2,
                Sonic3kConstants.MAP_AIZ_COLLAPSING_PLATFORM2_ADDR,
                1, 2, null));
    }
}
```

**Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=TestSonic3kPlcArtRegistry -q`
Expected: PASS

**Step 5: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/Sonic3kPlcArtRegistry.java src/test/java/com/openggf/game/sonic3k/TestSonic3kPlcArtRegistry.java
git commit -m "feat(s3k): populate AIZ standalone and level-art entries in registry"
```

---

### Task 3: Refactor Sonic3kObjectArtProvider to Use Registry

This is the key refactor — replace zone-conditional blocks with registry iteration.

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kObjectArtProvider.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kObjectArt.java`

**Step 1: Run existing tests to establish baseline**

Run: `mvn test -Dtest=TestSonic3kLevelLoading,TestS3kAiz1SkipHeadless -Ds3k.rom.path="Sonic and Knuckles & Sonic 3 (W) [!].gen" -q`
Expected: PASS (baseline — all existing behavior works)

**Step 2: Add registry-driven standalone loading method to Sonic3kObjectArtProvider**

Add a new method `loadStandaloneFromRegistry()` that iterates `ZoneArtPlan.standaloneArt()`:

```java
/**
 * Loads standalone art sheets (badniks, bosses) from registry entries.
 * Replaces zone-conditional badnik loading blocks.
 */
private void loadStandaloneFromRegistry(Sonic3kPlcArtRegistry.ZoneArtPlan plan) {
    Rom rom;
    try {
        rom = GameServices.rom().getRom();
    } catch (IOException e) {
        LOG.warning("Failed to get ROM for standalone art: " + e.getMessage());
        return;
    }
    if (rom == null) return;

    RomByteReader reader;
    try {
        reader = RomByteReader.fromRom(rom);
    } catch (IOException e) {
        LOG.warning("Failed to create RomByteReader: " + e.getMessage());
        return;
    }

    Sonic3kObjectArt art = new Sonic3kObjectArt(null, reader);

    for (Sonic3kPlcArtRegistry.StandaloneArtEntry entry : plan.standaloneArt()) {
        try {
            ObjectSpriteSheet sheet = art.loadStandaloneSheet(rom, entry);
            registerSheet(entry.key(), sheet);
        } catch (IOException e) {
            LOG.warning("Failed to load standalone art '" + entry.key() + "': " + e.getMessage());
        }
    }
}
```

**Step 3: Add `loadStandaloneSheet()` to Sonic3kObjectArt**

This method dispatches based on the entry's compression type and DPLC presence:

```java
/**
 * Loads a standalone art sheet from a registry entry.
 * Dispatches based on compression type and DPLC presence.
 */
public ObjectSpriteSheet loadStandaloneSheet(Rom rom,
        Sonic3kPlcArtRegistry.StandaloneArtEntry entry) throws IOException {
    if (rom == null || reader == null) return null;

    Pattern[] patterns;
    switch (entry.compression()) {
        case KOSINSKI_MODULED ->
            patterns = loadKosinskiModuledPatterns(rom, entry.artAddr());
        case NEMESIS ->
            patterns = loadNemesisPatterns(rom, entry.artAddr());
        case UNCOMPRESSED ->
            patterns = loadUncompressedPatterns(rom, entry.artAddr(), entry.artSize());
        default -> { return null; }
    }
    if (patterns == null || patterns.length == 0) return null;

    List<SpriteMappingFrame> mappings =
            S3kSpriteDataLoader.loadMappingFrames(reader, entry.mappingAddr());

    if (entry.dplcAddr() > 0) {
        List<SpriteDplcFrame> dplcFrames = loadObjectDplcFrames(reader, entry.dplcAddr());
        mappings = applyDplcRemap(mappings, dplcFrames);
    }

    return new ObjectSpriteSheet(patterns, mappings, entry.palette(), 1);
}
```

Make `loadObjectDplcFrames` and `applyDplcRemap` package-visible (remove `private`).

**Step 4: Add registry-driven level-art loading to Sonic3kObjectArtProvider**

Add a method `loadLevelArtFromRegistry()` that iterates `ZoneArtPlan.levelArt()`:

```java
/**
 * Registers level-art sheets from registry entries.
 * Replaces zone-conditional level-art blocks.
 */
private void loadLevelArtFromRegistry(Sonic3kPlcArtRegistry.ZoneArtPlan plan,
        Sonic3kObjectArt art) {
    for (Sonic3kPlcArtRegistry.LevelArtEntry entry : plan.levelArt()) {
        ObjectSpriteSheet sheet;
        if (entry.builderName() != null) {
            // Hardcoded builder — dispatch by name
            sheet = invokeBuilder(art, entry.builderName());
        } else if (entry.mappingAddr() > 0) {
            // ROM-parsed mappings
            sheet = art.buildLevelArtSheetFromRom(
                    entry.mappingAddr(), entry.artTileBase(), entry.palette());
        } else {
            LOG.warning("LevelArtEntry '" + entry.key() + "' has no builder or mapping addr");
            continue;
        }
        registerLevelArtSheet(entry.key(), sheet, art);
    }
}

private ObjectSpriteSheet invokeBuilder(Sonic3kObjectArt art, String builderName) {
    return switch (builderName) {
        case "buildSpikesSheet" -> art.buildSpikesSheet();
        case "buildSpringVerticalSheet" -> art.buildSpringVerticalSheet();
        case "buildSpringVerticalYellowSheet" -> art.buildSpringVerticalYellowSheet();
        case "buildSpringHorizontalSheet" -> art.buildSpringHorizontalSheet();
        case "buildSpringHorizontalYellowSheet" -> art.buildSpringHorizontalYellowSheet();
        case "buildSpringDiagonalSheet" -> art.buildSpringDiagonalSheet();
        case "buildSpringDiagonalYellowSheet" -> art.buildSpringDiagonalYellowSheet();
        case "buildAiz1TreeSheet" -> art.buildAiz1TreeSheet();
        case "buildAiz1ZiplinePegSheet" -> art.buildAiz1ZiplinePegSheet();
        case "buildAizForegroundPlantSheet" -> art.buildAizForegroundPlantSheet();
        case "buildAnimatedStillSpritesSheet" -> art.buildAnimatedStillSpritesSheet();
        default -> {
            LOG.warning("Unknown builder: " + builderName);
            yield null;
        }
    };
}
```

**Step 5: Replace zone-conditional blocks in loadArtForZone()**

Replace lines 96-99 in `loadArtForZone()`:

```java
// Before:
if (zoneIndex == 0x00) {
    loadAizBadnikArt();
    loadAizMinibossArtFromPlc();
}

// After:
Sonic3kPlcArtRegistry.ZoneArtPlan plan =
        Sonic3kPlcArtRegistry.getPlan(zoneIndex, 0); // act resolved later
loadStandaloneFromRegistry(plan);
if (zoneIndex == 0x00) {
    loadAizMinibossArtFromPlc(); // PLC-based boss art stays separate for now
}
```

**Step 6: Replace zone-conditional blocks in registerLevelArtSheets()**

Replace the entire `registerLevelArtSheets()` body:

```java
public void registerLevelArtSheets(Level level, int zoneIndex) {
    if (level == null) return;

    RomByteReader reader = null;
    try {
        Rom rom = GameServices.rom().getRom();
        if (rom != null) reader = RomByteReader.fromRom(rom);
    } catch (IOException e) {
        LOG.warning("Failed to create RomByteReader for level art: " + e.getMessage());
    }
    Sonic3kObjectArt art = new Sonic3kObjectArt(level, reader);

    // Determine act index from level (stored in currentActIndex during loadLevel)
    int actIndex = currentActIndex;

    Sonic3kPlcArtRegistry.ZoneArtPlan plan =
            Sonic3kPlcArtRegistry.getPlan(zoneIndex, actIndex);
    loadLevelArtFromRegistry(plan, art);

    LOG.info("Sonic3kObjectArtProvider registered " + rendererKeys.size()
            + " level-art sheets for zone " + zoneIndex);
}
```

Note: This requires tracking the act index. Add a field `private int currentActIndex = 0;` and set it in `loadArtForZone()` (or pass it through the method signature if the caller provides it).

**Step 7: Run tests to verify refactor is behavior-preserving**

Run: `mvn test -Dtest=TestSonic3kLevelLoading,TestS3kAiz1SkipHeadless,TestSonic3kPlcArtRegistry -Ds3k.rom.path="Sonic and Knuckles & Sonic 3 (W) [!].gen" -q`
Expected: PASS — all tests green. Registry-driven loading produces identical results.

**Step 8: Delete the now-unused loadAizBadnikArt() method**

Remove `loadAizBadnikArt()` from `Sonic3kObjectArtProvider` since standalone loading is now registry-driven.

**Step 9: Run tests again**

Run: `mvn test -Dtest=TestSonic3kLevelLoading,TestS3kAiz1SkipHeadless -Ds3k.rom.path="Sonic and Knuckles & Sonic 3 (W) [!].gen" -q`
Expected: PASS

**Step 10: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/Sonic3kObjectArtProvider.java src/main/java/com/openggf/game/sonic3k/Sonic3kObjectArt.java
git commit -m "refactor(s3k): replace zone-conditional art loading with registry-driven iteration"
```

---

### Task 4: Add Act Index Tracking

The refactor in Task 3 needs the act index in `registerLevelArtSheets()`. This task ensures the plumbing is correct.

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kObjectArtProvider.java`

**Step 1: Check how act index reaches the provider**

Search for call sites of `loadArtForZone` and `registerLevelArtSheets` to see if act index is already available. If not, the `ObjectArtProvider` interface may need an overload.

The approach depends on what the call site looks like. Options:
- a) Add `loadArtForZone(int zoneIndex, int actIndex)` and store `currentActIndex`
- b) Add `registerLevelArtSheets(Level level, int zoneIndex, int actIndex)` directly

**Step 2: Implement the cleanest approach**

Add a `currentActIndex` field and setter. Update `loadArtForZone` signature or add a `setActIndex()` method called by `Sonic3k.loadLevel()` before art loading.

**Step 3: Run tests**

Run: `mvn test -Dtest=TestSonic3kLevelLoading,TestS3kAiz1SkipHeadless -Ds3k.rom.path="Sonic and Knuckles & Sonic 3 (W) [!].gen" -q`
Expected: PASS

**Step 4: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/Sonic3kObjectArtProvider.java
git commit -m "feat(s3k): thread act index into art provider for registry plan lookup"
```

---

### Task 5: Discover and Add HCZ ROM Addresses

Use RomOffsetFinder to find HCZ badnik and level-art ROM addresses, then add them to `Sonic3kConstants`.

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/constants/Sonic3kConstants.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kObjectArtKeys.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kPlcArtRegistry.java`

**Step 1: Run RomOffsetFinder searches**

```bash
# HCZ badniks (from PLCKosM inventory)
mvn exec:java -Dexec.mainClass="com.openggf.tools.disasm.RomOffsetFinder" -Dexec.args="--game s3k search Blastoid" -q
mvn exec:java -Dexec.mainClass="com.openggf.tools.disasm.RomOffsetFinder" -Dexec.args="--game s3k search TurboSpiker" -q
mvn exec:java -Dexec.mainClass="com.openggf.tools.disasm.RomOffsetFinder" -Dexec.args="--game s3k search MegaChopper" -q
mvn exec:java -Dexec.mainClass="com.openggf.tools.disasm.RomOffsetFinder" -Dexec.args="--game s3k search Pointdexter" -q
mvn exec:java -Dexec.mainClass="com.openggf.tools.disasm.RomOffsetFinder" -Dexec.args="--game s3k search Jawz" -q
# HCZ level art
mvn exec:java -Dexec.mainClass="com.openggf.tools.disasm.RomOffsetFinder" -Dexec.args="--game s3k search HCZ" -q
```

**Step 2: Add verified ROM addresses to Sonic3kConstants**

Add constants following the existing pattern:
```java
// ===== HCZ Badnik Art (verified via RomOffsetFinder) =====
public static final int ART_KOSM_HCZ_BLASTOID_ADDR = 0x...;
public static final int MAP_BLASTOID_ADDR = 0x...;
// ... etc for each HCZ badnik
```

**Step 3: Add art keys to Sonic3kObjectArtKeys**

```java
// HCZ badniks
public static final String HCZ_BLASTOID = "hcz_blastoid";
public static final String HCZ_TURBO_SPIKER = "hcz_turbo_spiker";
public static final String HCZ_MEGA_CHOPPER = "hcz_mega_chopper";
public static final String HCZ_POINTDEXTER = "hcz_pointdexter";
public static final String HCZ_JAWZ = "hcz_jawz";
```

**Step 4: Add HCZ entries to registry**

Add `case 0x01 -> addHczEntries(actIndex, standalone, levelArt);` to the switch.

Implement `addHczEntries()` with standalone entries for PLCKosM badniks per the design doc:
- HCZ1: Blastoid, TurboSpiker, MegaChopper, Pointdexter
- HCZ2: Jawz, TurboSpiker, MegaChopper, Pointdexter

**Step 5: Write test**

Add to `TestSonic3kPlcArtRegistry.java`:

```java
@Test
public void hcz1HasCorrectBadniks() {
    Sonic3kPlcArtRegistry.ZoneArtPlan plan =
            Sonic3kPlcArtRegistry.getPlan(0x01, 0);
    assertEquals(4, plan.standaloneArt().size()); // Blastoid, TurboSpiker, MegaChopper, Pointdexter
}

@Test
public void hcz2HasDifferentBadnikSet() {
    Sonic3kPlcArtRegistry.ZoneArtPlan plan =
            Sonic3kPlcArtRegistry.getPlan(0x01, 1);
    // HCZ2: Jawz replaces Blastoid
    boolean hasJawz = plan.standaloneArt().stream()
            .anyMatch(e -> e.key().equals(Sonic3kObjectArtKeys.HCZ_JAWZ));
    assertTrue(hasJawz);
}
```

**Step 6: Run tests**

Run: `mvn test -Dtest=TestSonic3kPlcArtRegistry -q`
Expected: PASS

**Step 7: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/constants/Sonic3kConstants.java src/main/java/com/openggf/game/sonic3k/Sonic3kObjectArtKeys.java src/main/java/com/openggf/game/sonic3k/Sonic3kPlcArtRegistry.java src/test/java/com/openggf/game/sonic3k/TestSonic3kPlcArtRegistry.java
git commit -m "feat(s3k): add HCZ badnik art addresses and registry entries"
```

---

### Task 6: Add MGZ and CNZ Entries

Same pattern as Task 5, for zones 0x02 (MGZ) and 0x03 (CNZ).

**PLCKosM inventory to populate:**
```
MGZ1: Spiker, MGZMiniboss, MGZMiniBossDebris
MGZ2: Spiker, Mantis
CNZ: Sparkle, Batbot, ClamerShot(+$70), CNZBalloon
```

**DPLC badnik:** BubblesBadnik (MGZ) — uses Perform_DPLC pattern like Rhinobot.

**Step 1: RomOffsetFinder searches for all MGZ/CNZ art and mapping addresses**

**Step 2: Add constants, keys, and registry entries following Task 5 pattern**

**Step 3: Add test cases for MGZ and CNZ badnik counts**

**Step 4: Run tests and commit**

```bash
git commit -m "feat(s3k): add MGZ and CNZ art addresses and registry entries"
```

---

### Task 7: Add FBZ and ICZ Entries

Zones 0x04 (FBZ) and 0x05 (ICZ).

**PLCKosM inventory:**
```
FBZ: Blaster, Technosqueek, FBZButton
ICZ: ICZSnowdust, StarPointer
```

**DPLC badnik:** Penguinator (ICZ) — uses Perform_DPLC pattern.

**Zone overrides to implement:**
- **FBZ spikes:** Override the shared spikes entry with `artTileBase=0x0200` for FBZ. In `addFbzEntries()`, add a FBZ-specific `LevelArtEntry` for spikes before the shared entries, or filter/replace the shared spikes entry.
- **ICZ collapsing bridge:** Already exists as `COLLAPSING_PLATFORM_ICZ` — move from hardcoded to registry.

**Step 1: RomOffsetFinder searches**

**Step 2: Add constants, keys, registry entries**

**Step 3: Handle FBZ spike override**

In the registry, when building the FBZ plan, replace the shared spikes `LevelArtEntry` with an FBZ-specific one that uses `artTileBase=0x0200`:

```java
private static void addFbzEntries(int actIndex,
        List<StandaloneArtEntry> standalone, List<LevelArtEntry> levelArt) {
    // Replace shared spikes with FBZ-specific spikes (ArtTile_FBZSpikes = $0200)
    levelArt.removeIf(e -> e.key().equals(Sonic3kObjectArtKeys.SPIKES));
    levelArt.add(new LevelArtEntry(Sonic3kObjectArtKeys.SPIKES,
            -1, 0x0200, 0, "buildSpikesSheet"));

    // ... FBZ standalone and level-art entries
}
```

Note: This requires `SHARED_LEVEL_ART` to be added to a mutable list (which `getPlan()` already does via `new ArrayList<>(SHARED_LEVEL_ART)`).

**Step 4: Add test for FBZ spike override**

```java
@Test
public void fbzUsesOverriddenSpikeArtTile() {
    Sonic3kPlcArtRegistry.ZoneArtPlan plan =
            Sonic3kPlcArtRegistry.getPlan(0x04, 0);
    Sonic3kPlcArtRegistry.LevelArtEntry spikes = plan.levelArt().stream()
            .filter(e -> e.key().equals(Sonic3kObjectArtKeys.SPIKES))
            .findFirst().orElse(null);
    assertNotNull(spikes);
    assertEquals("FBZ spikes should use ArtTile_FBZSpikes ($0200)",
            0x0200, spikes.artTileBase());
}
```

**Step 5: Run tests and commit**

```bash
git commit -m "feat(s3k): add FBZ/ICZ entries with FBZ spike override"
```

---

### Task 8: Add LBZ, MHZ, SOZ Entries

Zones 0x06 (LBZ), 0x07 (MHZ), 0x08 (SOZ).

**PLCKosM inventory:**
```
LBZ: SnaleBlaster, Orbinaut, Ribot, Corkey
MHZ1: Madmole, Mushmeanie, Dragonfly
MHZ2: CluckoidArrow(+$22), Madmole, Mushmeanie, Dragonfly
SOZ: Skorp, Sandworm, Rockn
```

**Zone override:** MGZ/MHZ diagonal spring uses `ArtTile_MGZMHZDiagonalSpring ($0478)` instead of `ArtTile_DiagonalSpring ($043A)`. Override the shared diagonal spring entries in `addMhzEntries()` and `addMgzEntries()` (Task 6 if not done already).

**Step 1-5: Same pattern — RomOffsetFinder, constants, keys, registry, test, commit**

```bash
git commit -m "feat(s3k): add LBZ, MHZ, SOZ entries with MHZ diagonal spring override"
```

---

### Task 9: Add LRZ, SSZ, DEZ, DDZ, HPZ Entries

Zones 0x09 (LRZ), 0x0A (SSZ), 0x0B (DEZ), 0x0C (DDZ), 0x0D (HPZ).

**PLCKosM inventory:**
```
LRZ: FirewormSegments, Iwamodoki, Toxomister
SSZ: EggRoboBadnik
DEZ: Spikebonker, Chainspike
DDZ: EggRoboBadnik
```

**Existing level-art to migrate:**
- `LRZ1_ROCK` and `LRZ2_ROCK` — already implemented as hardcoded entries, move to registry.

**Step 1-5: Same pattern**

```bash
git commit -m "feat(s3k): add LRZ, SSZ, DEZ, DDZ, HPZ entries"
```

---

### Task 10: Add Registry Coverage Test

Validate that every PLCKosM entry from the design doc has a matching registry entry.

**Files:**
- Add to: `src/test/java/com/openggf/game/sonic3k/TestSonic3kPlcArtRegistry.java`

**Step 1: Write coverage test**

```java
@Test
public void allZonesReturnNonNullPlan() {
    // All 14 zones (0x00-0x0D) should return valid plans
    for (int zone = 0; zone <= 0x0D; zone++) {
        for (int act = 0; act <= 1; act++) {
            Sonic3kPlcArtRegistry.ZoneArtPlan plan =
                    Sonic3kPlcArtRegistry.getPlan(zone, act);
            assertNotNull("Zone " + zone + " act " + act + " should return plan", plan);
            assertTrue("Zone " + zone + " act " + act + " should have shared level art",
                    plan.levelArt().size() >= 7);
        }
    }
}

@Test
public void allZonesHaveStandaloneEntries() {
    // Every zone except HPZ (0x0D) should have at least one standalone entry
    int[] zonesWithBadniks = {0x00, 0x01, 0x02, 0x03, 0x04, 0x05,
                               0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B, 0x0C};
    for (int zone : zonesWithBadniks) {
        Sonic3kPlcArtRegistry.ZoneArtPlan plan =
                Sonic3kPlcArtRegistry.getPlan(zone, 0);
        assertTrue("Zone " + zone + " should have standalone badnik entries",
                !plan.standaloneArt().isEmpty());
    }
}

@Test
public void noDuplicateKeysInPlan() {
    for (int zone = 0; zone <= 0x0D; zone++) {
        for (int act = 0; act <= 1; act++) {
            Sonic3kPlcArtRegistry.ZoneArtPlan plan =
                    Sonic3kPlcArtRegistry.getPlan(zone, act);

            List<String> allKeys = new ArrayList<>();
            plan.standaloneArt().forEach(e -> allKeys.add(e.key()));
            plan.levelArt().forEach(e -> allKeys.add(e.key()));

            long uniqueCount = allKeys.stream().distinct().count();
            assertEquals("Zone " + zone + " act " + act + " should have no duplicate keys",
                    allKeys.size(), uniqueCount);
        }
    }
}
```

**Step 2: Run tests**

Run: `mvn test -Dtest=TestSonic3kPlcArtRegistry -q`
Expected: PASS (all zones populated, no duplicates)

**Step 3: Commit**

```bash
git add src/test/java/com/openggf/game/sonic3k/TestSonic3kPlcArtRegistry.java
git commit -m "test(s3k): add registry coverage tests for all 14 zones"
```

---

### Task 11: Run Full S3K Test Suite and Visual Verification

**Step 1: Run all S3K tests**

```bash
mvn test -Dtest=TestSonic3kLevelLoading,TestS3kAiz1SkipHeadless,TestSonic3kBootstrapResolver,TestSonic3kDecodingUtils,TestSonic3kPlcArtRegistry -Ds3k.rom.path="Sonic and Knuckles & Sonic 3 (W) [!].gen" -q
```

Expected: ALL PASS

**Step 2: Run the full test suite to check for regressions**

```bash
mvn test -q
```

Expected: ALL PASS — no S1/S2 regressions from the S3K changes.

**Step 3: Commit any fixups needed**

---

## Key Implementation Notes

### Builder Name Dispatch vs Functional Interface

The S2 registry uses `SheetBuilder` functional interfaces (`Sonic2ObjectArt::loadBuzzerSheet`). For S3K, we use builder name strings in `LevelArtEntry.builderName` because:
- Level-art builders need a `Level` instance (not available at registry definition time)
- The `invokeBuilder()` switch handles dispatch without reflection
- New builder methods just need a case added to the switch

Standalone entries don't need builder names — `loadStandaloneSheet()` handles all three compression types generically based on the entry's fields.

### Zone Override Pattern

Shared entries are added to a mutable `ArrayList`. Zone-specific `addXxxEntries()` methods can:
- Add new entries (most common)
- Replace shared entries via `removeIf()` + `add()` (FBZ spikes, MHZ diagonal springs)

### What's NOT in the Registry

These remain manually loaded (not data-driven):
- **HUD art** — raw patterns, not sprite sheets
- **Monitor art** — composite construction (base + life icon overlay)
- **Shield art** — DPLC-driven rendering, not standard sprite sheets
- **Boss PLC art** — loaded via `PlcParser.decompressAll()` pattern (AIZ miniboss)
- **Explosion art** — shared across all zones, hardcoded mappings

These could be registry-driven in a future pass but are orthogonal to the zone-conditional elimination goal.
