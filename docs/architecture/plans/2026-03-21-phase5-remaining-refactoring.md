# Phase 5 Remaining Refactoring Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extract remaining cross-game duplication found after Phase 4, targeting ~90 files and ~2,000 lines of duplicated code across 16 identified patterns.

**Architecture:** Structural refactoring only — no behavioral changes. Tasks are mostly independent. Tests must pass identically before and after each task.

**Tech Stack:** Java 21, JUnit 5 / Jupiter only, Maven

---

## File Structure

**New files:**
- None for Task 1 (method added to existing `AbstractObjectInstance.java`)
- None for Task 2 (method added to existing `PatternDecompressor.java`)

**Modified files (by task):**
- Task 1: `AbstractObjectInstance.java` + ~45 object files with `refreshDynamicSpawn()`
- Task 2: `PatternDecompressor.java` + ~14 files with private `loadNemesisPatterns()`
- Task 3: ~5 S1 boss files + potentially `AbstractBossInstance.java`
- Task 4: 4 collapsing platform files (extract inner fragment classes)
- Task 5: `SubpixelMotion.java` + ~18 files with inline gravity fall
- Task 6: 2 ring placement files + 2 object placement files
- Task 7: 3 title card manager files
- Task 8: 3 conveyor files

---

## Tier 1: High Value

### Task 1: Extract refreshDynamicSpawn() into AbstractObjectInstance

**Files:**
- Modify: `src/main/java/com/openggf/level/objects/AbstractObjectInstance.java`
- Modify: ~45 object files with `refreshDynamicSpawn()` method

45 moving solid objects each declare a `private ObjectSpawn dynamicSpawn` field, a `refreshDynamicSpawn()` method, and a `getSpawn()` override — all identical.

- [ ] **Step 1: Add dynamic position tracking to AbstractObjectInstance**

Add to `AbstractObjectInstance.java`:

```java
private ObjectSpawn dynamicSpawn;

/**
 * Updates the cached dynamic spawn to track the object's current position.
 * Call at end of update() for objects that move. The getSpawn() method will
 * automatically return the tracked position when this has been called.
 */
protected void updateDynamicSpawn(int x, int y) {
    if (dynamicSpawn == null || dynamicSpawn.x() != x || dynamicSpawn.y() != y) {
        dynamicSpawn = buildSpawnAt(x, y);
    }
}

/**
 * Returns true if dynamic spawn tracking is active.
 */
protected boolean hasDynamicSpawn() {
    return dynamicSpawn != null;
}
```

Override `getSpawn()` in the base to return `dynamicSpawn` when set:
```java
@Override
public ObjectSpawn getSpawn() {
    return dynamicSpawn != null ? dynamicSpawn : spawn;
}
```

**Note:** `getSpawn()` may already exist on the base or be defined on `ObjectInstance`. Check the current state — if `getSpawn()` is already final or has a different contract, add a separate `getDynamicSpawn()` and have callers use it. The key goal is eliminating the 45 private copies.

- [ ] **Step 2: Find all files with refreshDynamicSpawn**

```bash
grep -rn "refreshDynamicSpawn\|private ObjectSpawn dynamicSpawn" src/main/java --include="*.java" -l
```

- [ ] **Step 3: Migrate each file**

For each file:
1. Delete the `private ObjectSpawn dynamicSpawn;` field
2. Delete the `private void refreshDynamicSpawn()` method
3. Delete the `getSpawn()` override (if it just returns `dynamicSpawn`)
4. Replace calls to `refreshDynamicSpawn()` with `updateDynamicSpawn(x, y)` where `x`/`y` are the object's current position
5. Remove now-unused imports

- [ ] **Step 4: Verify build**

Run: `mvn test -Dmse=off`

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "$(cat <<'EOF'
refactor: extract refreshDynamicSpawn() into AbstractObjectInstance

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

### Task 2: Add loadNemesisPatterns() to PatternDecompressor

**Files:**
- Modify: `src/main/java/com/openggf/util/PatternDecompressor.java`
- Modify: ~14 files with private `loadNemesisPatterns()` copies

- [ ] **Step 1: Read existing PatternDecompressor**

Read `src/main/java/com/openggf/util/PatternDecompressor.java` to see existing methods.

- [ ] **Step 2: Add the shared static method**

Add to `PatternDecompressor`:

```java
/**
 * Decompresses Nemesis-compressed art from ROM into Pattern array.
 * @param rom the ROM to read from
 * @param address ROM address of the compressed data
 * @param name descriptive name for error logging
 * @return Pattern array, or empty array on failure
 */
public static Pattern[] nemesis(Rom rom, int address, String name) {
    try {
        byte[] compressed = rom.readBytes(address, 8192);
        try (ByteArrayInputStream bais = new ByteArrayInputStream(compressed);
             ReadableByteChannel channel = Channels.newChannel(bais)) {
            byte[] decompressed = NemesisReader.decompress(channel);
            return fromBytes(decompressed);
        }
    } catch (IOException e) {
        LOGGER.warning("Failed to load " + name + " patterns: " + e.getMessage());
        return new Pattern[0];
    }
}
```

Note: `fromBytes()` already exists on `PatternDecompressor` (added in Phase 1-3). If it doesn't handle the `fromSegaFormat` step, add that. Check the actual implementation.

- [ ] **Step 3: Find all files with private copies**

```bash
grep -rn "private Pattern\[\] loadNemesisPatterns\|private static Pattern\[\] loadNemesisPatterns" src/main/java --include="*.java" -l
```

Also check for variant names:
```bash
grep -rn "NemesisReader.decompress" src/main/java --include="*.java" -l
```

- [ ] **Step 4: Migrate each file**

Replace each private `loadNemesisPatterns()` copy with `PatternDecompressor.nemesis(rom, address, name)`. Remove the now-unused method and imports (`ByteArrayInputStream`, `Channels`, `ReadableByteChannel`, `NemesisReader`, `Arrays`).

Some files may use a different read size (not 8192). If so, add an overload `nemesis(Rom rom, int address, int readSize, String name)`.

- [ ] **Step 5: Verify build**

Run: `mvn test -Dmse=off`

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "$(cat <<'EOF'
refactor: add PatternDecompressor.nemesis(), eliminate 14 private copies

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

### Task 3: Extract S1 Eggman boss shared methods

**Files:**
- Modify: 5 S1 boss files in `src/main/java/com/openggf/game/sonic1/objects/bosses/`
- Possibly create: `src/main/java/com/openggf/game/sonic1/objects/bosses/AbstractS1EggmanBossInstance.java`

- [ ] **Step 1: Read all 5 S1 boss files**

Read: `Sonic1GHZBossInstance.java`, `Sonic1MZBossInstance.java`, `Sonic1SYZBossInstance.java`, `Sonic1SLZBossInstance.java`, `Sonic1LZBossInstance.java`. Identify the byte-identical methods: `bossMove()`, `isBossOnScreen()`, `getFaceFrame()`, `getFlameFrame()`, the `appendRenderCommands()` Eggman ship rendering, `updateDefeatWait()`, and the camera-expand escape pattern.

- [ ] **Step 2: Create AbstractS1EggmanBossInstance (or add to existing boss base)**

Extract the shared methods into a new intermediate base class or add them as protected helpers to the existing boss base. The choice depends on the class hierarchy — read the S1 boss base first.

Shared methods to extract:
- `bossMove()` — applies velocity to fixed-point position
- `isBossOnScreen()` — camera-relative bounds check
- `getFaceFrame()` — animation→frame mapping
- `getFlameFrame()` — animation→frame mapping
- `renderEggmanShip()` — the 25-line ship+face+flame rendering
- `runDefeatExplosions(frameCounter)` — countdown + random explosions
- `runCameraExpandEscape(int endBoundary)` — camera unlock + off-screen check

- [ ] **Step 3: Migrate 5 boss files**

Each deletes its private copies and calls the inherited methods.

- [ ] **Step 4: Verify build**

Run: `mvn test -Dmse=off`

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "$(cat <<'EOF'
refactor: extract shared S1 Eggman boss methods into base class

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

### Task 4: Extract AbstractFallingFragment

**Files:**
- Create: `src/main/java/com/openggf/level/objects/AbstractFallingFragment.java`
- Modify: 4 files with inner fragment classes

- [ ] **Step 1: Read the inner fragment classes**

Read the inner fragment classes in:
- `Sonic1CollapsingFloorObjectInstance.java` (inner `CollapsingFloorFragmentInstance`)
- `Sonic1CollapsingLedgeObjectInstance.java` (similar inner class)
- `CollapsingPlatformObjectInstance.java` (S2 inner class)
- `Sonic3kCollapsingPlatformObjectInstance.java` (inner `CollapsingPlatformFragment`)

- [ ] **Step 2: Create AbstractFallingFragment**

Similar to `GravityDebrisChild` but with a delay timer before falling begins:

```java
package com.openggf.level.objects;

public abstract class AbstractFallingFragment extends AbstractObjectInstance {
    protected int delayTimer;
    protected int y, yFrac, velY;
    protected final int gravity;
    protected boolean falling;

    protected AbstractFallingFragment(ObjectSpawn spawn, String name,
            int startX, int startY, int delay, int gravity) {
        super(spawn, name);
        this.y = startY;
        this.delayTimer = delay;
        this.gravity = gravity;
    }

    @Override
    public void update(int frameCounter, AbstractPlayableSprite player) {
        if (!falling) {
            if (delayTimer-- <= 0) falling = true;
            return;
        }
        // 16.16 gravity fall
        velY += gravity;
        int y32 = (y << 16) | (yFrac & 0xFFFF);
        y32 += ((int)(short)velY) << 8;
        y = y32 >> 16;
        yFrac = y32 & 0xFFFF;

        if (!isOnScreen(128)) setDestroyed(true);
    }
}
```

Adjust the exact gravity-fall math after reading the actual inner classes.

- [ ] **Step 3: Migrate inner fragment classes**

Each inner class extends `AbstractFallingFragment`, keeping only its `appendRenderCommands()`.

- [ ] **Step 4: Verify build**

Run: `mvn test -Dmse=off`

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "$(cat <<'EOF'
refactor: extract AbstractFallingFragment for collapsing platform fragments

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

### Task 5: Add applyObjectFall() to SubpixelMotion

**Files:**
- Modify: `src/main/java/com/openggf/level/objects/SubpixelMotion.java`
- Modify: ~18 files with inline 16.16 gravity fall blocks

- [ ] **Step 1: Add the method**

Add to `SubpixelMotion`:

```java
/**
 * ObjectFall: apply gravity to Y velocity, then update Y position (16.16 fixed-point).
 * This is distinct from moveSprite() which uses 16.8 fixed-point.
 * Used by collapsing platforms, falling pillars, and similar heavy objects.
 *
 * @param s mutable state (y, ySub, yVel updated in place)
 * @param gravity gravity in subpixels per frame
 */
public static void objectFall(State s, int gravity) {
    s.yVel += gravity;
    int y32 = (s.y << 16) | (s.ySub & 0xFFFF);
    y32 += ((int)(short)s.yVel) << 8;
    s.y = y32 >> 16;
    s.ySub = y32 & 0xFFFF;
}
```

**Note:** Verify the exact arithmetic by reading the actual inline blocks first. The key difference from `moveSprite()` is 16.16 vs 16.8 fixed-point for the position accumulator.

- [ ] **Step 2: Find and migrate files**

```bash
grep -rn "y32 += .*velY.*<< 8\|yFixed += .*yVel" src/main/java --include="*.java" -l
```

Replace inline blocks with `SubpixelMotion.objectFall(state, GRAVITY)`.

- [ ] **Step 3: Verify build**

Run: `mvn test -Dmse=off`

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "$(cat <<'EOF'
refactor: add SubpixelMotion.objectFall(), migrate 16.16 gravity blocks

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Tier 2: Medium Value

### Task 6: Extract shared ring/object placement parsers

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic2/Sonic2RingPlacement.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kRingPlacement.java`
- Modify: `src/main/java/com/openggf/game/sonic2/Sonic2ObjectPlacement.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kObjectPlacement.java`

- [ ] **Step 1: Read all 4 files and identify the shared parsing loops**

Confirm the ring record format (4-byte, `0xFFFF` terminator, `RING_SPACING=0x18`) and object record format (6-byte, same terminator, Y-word field layout) are identical between S2 and S3K.

- [ ] **Step 2: Extract shared static parse methods**

Add to a shared location (e.g., `com.openggf.game.common.CommonPlacementParser` or add methods to existing `CommonSpriteDataLoader`):
- `static List<RingSpawn> parseRingRecords(RomByteReader rom, int addr)`
- `static List<ObjectSpawn> parseObjectRecords(RomByteReader rom, int addr)`

- [ ] **Step 3: Migrate S2/S3K files to use shared parsers**

Each keeps its game-specific pointer table resolution but delegates the inner record loop.

- [ ] **Step 4: Verify build and commit**

```bash
mvn test -Dmse=off
git add -A && git commit -m "$(cat <<'EOF'
refactor: extract shared ring/object placement record parsers

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

### Task 7: Extract shared title card sprite rendering

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic1/Sonic1TitleCardManager.java`
- Modify: `src/main/java/com/openggf/game/sonic2/TitleCardManager.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kTitleCardManager.java`

- [ ] **Step 1: Read the renderSpritePiece() method in all 3 managers**

Identify the shared column-major VDP rendering loop.

- [ ] **Step 2: Extract static utility method**

Add to a shared location (e.g., `com.openggf.graphics.TitleCardSpriteRenderer` or a static method on an existing graphics utility):

```java
public static void renderSpritePieces(GraphicsManager gm, SpritePiece[] pieces,
        int originX, int originY, int patternBase, int paletteIndex) {
    // column-major VDP tile rendering with flip handling
}
```

- [ ] **Step 3: Migrate 3 title card managers**

- [ ] **Step 4: Verify build and commit**

```bash
mvn test -Dmse=off
git add -A && git commit -m "$(cat <<'EOF'
refactor: extract shared title card sprite rendering utility

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

### Task 8: Extract WaypointPathFollower for conveyor objects

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic2/objects/ConveyorObjectInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic1/objects/Sonic1LZConveyorObjectInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic1/objects/Sonic1SpinConveyorObjectInstance.java`

- [ ] **Step 1: Read all 3 conveyor files**

Identify the shared `calculateVelocity()`/`changeDirection()` dominant-axis math and `wrapWaypointOffset()`/`advanceWaypoint()` logic.

- [ ] **Step 2: Create WaypointPathFollower**

Create `src/main/java/com/openggf/level/objects/WaypointPathFollower.java`:

A utility class with:
- `calculateVelocity(int dx, int dy, int speed)` returning `(xVel, yVel)` — dominant-axis proportional velocity
- `wrapWaypointIndex(int index, int pathLength)` — boundary wrapping

- [ ] **Step 3: Migrate 3 conveyor files**

- [ ] **Step 4: Verify build and commit**

```bash
mvn test -Dmse=off
git add -A && git commit -m "$(cat <<'EOF'
refactor: extract WaypointPathFollower for conveyor objects

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```
