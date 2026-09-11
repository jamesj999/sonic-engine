# S3K Invincibility Stars Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement S3K-accurate invincibility star visuals — trailing position-history stars with per-star orbital sub-sprites.

**Architecture:** New `Sonic3kInvincibilityStarsObjectInstance` in `game.sonic3k.objects`, following the same pattern as `FireShieldObjectInstance` / `LightningShieldObjectInstance`. Art loaded from ROM via `Sonic3kObjectArtProvider`, registered under existing `ObjectArtKeys.INVINCIBILITY_STARS` key. `DefaultPowerUpSpawner` branched to create the S3K variant.

**Tech Stack:** Java 21, Maven, JUnit

---

## File Map

| Action | File | Responsibility |
|--------|------|----------------|
| Modify | `src/main/java/com/openggf/game/sonic3k/constants/Sonic3kConstants.java` | Add ROM addresses for invincibility star art + mappings |
| Create | `src/test/java/com/openggf/game/sonic3k/objects/TestSonic3kInvincibilityStars.java` | Unit tests for orbit, trailing, animation logic |
| Create | `src/main/java/com/openggf/game/sonic3k/objects/Sonic3kInvincibilityStarsObjectInstance.java` | S3K star rendering with trailing + orbit |
| Modify | `src/main/java/com/openggf/game/sonic3k/Sonic3kObjectArtProvider.java` | Load invincibility star art from ROM |
| Modify | `src/main/java/com/openggf/level/objects/DefaultPowerUpSpawner.java` | Branch to create S3K variant |

---

### Task 1: Find ROM Addresses and Add Constants

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/constants/Sonic3kConstants.java:757-811`

- [ ] **Step 1: Search for ArtUnc_Invincibility in ROM**

Run RomOffsetFinder to locate the uncompressed invincibility star art:

```bash
mvn exec:java "-Dexec.mainClass=com.openggf.tools.disasm.RomOffsetFinder" "-Dexec.args=--game s3k search Invincibility" -q
```

The art is `ArtUnc_Invincibility` — a `binclude` of `General/Sprites/Shields/Invincibility.bin`, 0x200 bytes (16 tiles × 32 bytes/tile), loaded to `ArtTile_Shield` ($079C). It should appear near the other shield art addresses ($18C000 range).

If the search returns no results, try:

```bash
mvn exec:java "-Dexec.mainClass=com.openggf.tools.disasm.RomOffsetFinder" "-Dexec.args=--game s3k search Shield" -q
```

And look for the invincibility entry. If that fails, the art is adjacent to the insta-shield art (which ends at `ART_UNC_INSTA_SHIELD_ADDR + ART_UNC_INSTA_SHIELD_SIZE` = `0x18C084 + 0x680` = `0x18C704`). That's where fire shield starts. Check nearby addresses.

As a last resort, search the ROM binary for the byte pattern at the start of Invincibility.bin:

```bash
mvn exec:java "-Dexec.mainClass=com.openggf.tools.disasm.RomOffsetFinder" "-Dexec.args=--game s3k find ArtUnc_Invincibility" -q
```

- [ ] **Step 2: Search for Map_Invincibility in ROM**

```bash
mvn exec:java "-Dexec.mainClass=com.openggf.tools.disasm.RomOffsetFinder" "-Dexec.args=--game s3k search Map_Invincibility" -q
```

Map_Invincibility is in `General/Sprites/Shields/Map - Invincibility.asm`. It has 9 frames with an offset table (first word should be 0x0012 = 9×2). This mapping file should be near the other shield mappings ($019A00 range).

If search fails, look near the shield mapping cluster: `MAP_FIRE_SHIELD_ADDR` (0x019AC6), `MAP_LIGHTNING_SHIELD_ADDR` (0x019DC8), `MAP_BUBBLE_SHIELD_ADDR` (0x019F82), `MAP_INSTA_SHIELD_ADDR` (0x01A0D0). The invincibility mapping may be before or after this cluster.

- [ ] **Step 3: Add constants to Sonic3kConstants.java**

Add the following block after the Insta-Shield constants (after line ~811):

```java
    // ArtUnc_Invincibility - Invincibility Stars art (16 tiles, uncompressed)
    // Verified by RomOffsetFinder, 2026-04-03
    public static final int ART_UNC_INVINCIBILITY_ADDR = 0x______;  // <-- fill from step 1
    public static final int ART_UNC_INVINCIBILITY_SIZE = 0x200;     // 16 tiles × 32 bytes

    // Map_Invincibility - 9 mapping frames for invincibility star sprites
    // Verified by RomOffsetFinder, 2026-04-03
    public static final int MAP_INVINCIBILITY_ADDR = 0x______;      // <-- fill from step 2
```

- [ ] **Step 4: Verify addresses compile**

```bash
mvn compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/constants/Sonic3kConstants.java
git commit -m "feat(s3k): add ROM addresses for invincibility star art and mappings"
```

---

### Task 2: Write Failing Tests

**Files:**
- Create: `src/test/java/com/openggf/game/sonic3k/objects/TestSonic3kInvincibilityStars.java`

- [ ] **Step 1: Write the test class**

```java
package com.openggf.game.sonic3k.objects;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Unit tests for S3K invincibility star orbit and trailing logic.
 * No ROM or OpenGL required — pure math tests.
 */
public class TestSonic3kInvincibilityStars {

    // ── Trailing position formula ──────────────────────────────────

    @Test
    public void trailingFramesBehind_matchesDisassemblyFormula() {
        // ROM: d1 = starIndex * 12 (bytes), Pos_table entries are 4 bytes each
        // So star trails by starIndex * 3 frames behind
        assertEquals(0, Sonic3kInvincibilityStarsObjectInstance.trailingFramesBehind(0));
        assertEquals(3, Sonic3kInvincibilityStarsObjectInstance.trailingFramesBehind(1));
        assertEquals(6, Sonic3kInvincibilityStarsObjectInstance.trailingFramesBehind(2));
        assertEquals(9, Sonic3kInvincibilityStarsObjectInstance.trailingFramesBehind(3));
    }

    // ── Orbit angle wrapping ──────────────────────────────────────

    @Test
    public void orbitAngle_wrapsAt32() {
        // Table has 32 entries; angle must wrap cleanly
        int angle = 0;
        // Parent rotates by 9 entries/frame (ROM: $12 byte offset / 2 bytes per entry)
        for (int i = 0; i < 100; i++) {
            angle = (angle + 9) % 32;
        }
        assertTrue(angle >= 0 && angle < 32);
    }

    @Test
    public void orbitAngle_childWrapsAt32() {
        // Children rotate by 1 entry/frame (ROM: $02 byte offset / 2)
        int angle = 0;
        for (int i = 0; i < 100; i++) {
            angle = (angle + 1) % 32;
        }
        assertTrue(angle >= 0 && angle < 32);
    }

    // ── Orbit table properties ──────────────────────────────────

    @Test
    public void orbitTable_has32Entries() {
        int[][] table = Sonic3kInvincibilityStarsObjectInstance.S3K_ORBIT_OFFSETS;
        assertEquals(32, table.length);
    }

    @Test
    public void orbitTable_entriesAreXYPairs() {
        int[][] table = Sonic3kInvincibilityStarsObjectInstance.S3K_ORBIT_OFFSETS;
        for (int[] entry : table) {
            assertEquals(2, entry.length);
        }
    }

    @Test
    public void orbitTable_isCircular() {
        // Entry 0 and entry 16 should be opposite signs on X axis (0° vs 180°)
        int[][] table = Sonic3kInvincibilityStarsObjectInstance.S3K_ORBIT_OFFSETS;
        // Entry 0: (15, 0), Entry 16: (-16, 0)
        assertTrue(table[0][0] > 0);
        assertTrue(table[16][0] < 0);
        assertEquals(0, table[0][1]);
        assertEquals(0, table[16][1]);
    }

    @Test
    public void orbitTable_subSpritePhaseOffset() {
        // Sub-sprite B is at angle + 16 (half circle), giving opposite position
        int[][] table = Sonic3kInvincibilityStarsObjectInstance.S3K_ORBIT_OFFSETS;
        // Entries 0 and 16 should have opposite X signs
        int xA = table[0][0];
        int xB = table[16][0];
        assertTrue("Sub-sprites should be on opposite sides", xA > 0 && xB < 0);
    }

    // ── Animation table structure ───────────────────────────────

    @Test
    public void parentAnimationTable_hasValidFrameIndices() {
        int[] parentAnim = Sonic3kInvincibilityStarsObjectInstance.PARENT_ANIM;
        for (int frame : parentAnim) {
            assertTrue("Frame index must be 0-8 (9 mapping frames)", frame >= 0 && frame <= 8);
        }
    }

    @Test
    public void childAnimationTables_haveValidFrameIndices() {
        int[][] childAnims = Sonic3kInvincibilityStarsObjectInstance.CHILD_PRIMARY_ANIMS;
        for (int[] anim : childAnims) {
            for (int frame : anim) {
                assertTrue("Frame index must be 0-8", frame >= 0 && frame <= 8);
            }
        }
    }

    // ── Direction reversal ──────────────────────────────────────

    @Test
    public void rotationDirection_reversesWhenFacingLeft() {
        // Parent rotation: +9 when right, -9 when left
        int angleRight = (0 + 9) % 32;
        int angleLeft = ((0 - 9) + 32) % 32;
        assertNotEquals(angleRight, angleLeft);
        assertEquals(9, angleRight);
        assertEquals(23, angleLeft);  // 32 - 9 = 23
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
mvn test -Dtest=TestSonic3kInvincibilityStars -q
```

Expected: FAIL — `Sonic3kInvincibilityStarsObjectInstance` class does not exist yet.

- [ ] **Step 3: Commit failing tests**

```bash
git add src/test/java/com/openggf/game/sonic3k/objects/TestSonic3kInvincibilityStars.java
git commit -m "test(s3k): add failing tests for invincibility star orbit and trailing logic"
```

---

### Task 3: Implement Sonic3kInvincibilityStarsObjectInstance

**Files:**
- Create: `src/main/java/com/openggf/game/sonic3k/objects/Sonic3kInvincibilityStarsObjectInstance.java`

- [ ] **Step 1: Create the implementation**

```java
package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.PowerUpObject;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.Direction;

import java.util.List;

/**
 * S3K Invincibility Stars — trailing position-history stars with orbital sub-sprites.
 * <p>
 * ROM reference: Obj_Invincibility (sonic3k.asm:33751).
 * <p>
 * Structure: 1 parent group (at player position) + 4 child groups (trailing via position
 * history at 0/3/6/9 frames behind). Each group renders 2 sub-sprites at opposite orbit
 * positions using a 32-entry circular offset table.
 * <p>
 * Parent rotates at 9 entries/frame (ROM: $12 byte offset in 2-byte table).
 * Children rotate at 1 entry/frame (ROM: $02 byte offset).
 * Rotation direction reverses when the player faces left.
 */
public class Sonic3kInvincibilityStarsObjectInstance extends AbstractObjectInstance implements PowerUpObject {

    private final PlayableEntity player;
    private final PatternSpriteRenderer renderer;

    // ── Data tables from sonic3k.asm ────────────────────────────────────

    /** Orbit offset table (byte_189A0): 32 signed X,Y pairs forming ~16px radius circle. */
    static final int[][] S3K_ORBIT_OFFSETS = {
            { 15,   0}, { 15,   3}, { 14,   6}, { 13,   8},
            { 11,  11}, {  8,  13}, {  6,  14}, {  3,  15},
            {  0,  16}, { -4,  15}, { -7,  14}, { -9,  13},
            {-12,  11}, {-14,   8}, {-15,   6}, {-16,   3},
            {-16,   0}, {-16,  -4}, {-15,  -7}, {-14,  -9},
            {-12, -12}, { -9, -14}, { -7, -15}, { -4, -16},
            { -1, -16}, {  3, -16}, {  6, -15}, {  8, -14},
            { 11, -12}, { 13,  -9}, { 14,  -7}, { 15,  -4}
    };

    private static final int ORBIT_TABLE_SIZE = S3K_ORBIT_OFFSETS.length; // 32
    private static final int SUB_SPRITE_PHASE = 16; // half circle ($20 byte offset / 2)

    /** Parent animation table (byte_189E0). Mapping frame indices, loops. */
    static final int[] PARENT_ANIM = {8, 5, 7, 6, 6, 7, 5, 8, 6, 7, 7, 6};

    /**
     * Child primary animation tables (byte_189ED, byte_18A02, byte_18A1B,
     * and the implicit 4th entry from off_187DE-6).
     * Each child's sub-sprite A uses the primary sequence.
     */
    static final int[][] CHILD_PRIMARY_ANIMS = {
            {8, 7, 6, 5, 4, 3, 4, 5, 6, 7},           // Star 0 (byte_189ED)
            {8, 7, 6, 5, 4, 3, 2, 3, 4, 5, 6, 7},     // Star 1 (byte_18A02)
            {7, 6, 5, 4, 3, 2, 1, 2, 3, 4, 5, 6},     // Star 2 (byte_18A1B)
            {8, 5, 7, 6, 6, 7, 5, 8, 6, 7, 7, 6}      // Star 3 (off_187DE-6, same as parent)
    };

    /**
     * Child secondary animation tables (second half of each byte_189xx table).
     * Each child's sub-sprite B uses the secondary sequence.
     */
    static final int[][] CHILD_SECONDARY_ANIMS = {
            {3, 4, 5, 6, 7, 8, 7, 6, 5, 4},           // Star 0
            {2, 3, 4, 5, 6, 7, 8, 7, 6, 5, 4, 3},     // Star 1
            {1, 2, 3, 4, 5, 6, 7, 6, 5, 4, 3, 2},     // Star 2
            {6, 7, 7, 6, 8, 5, 7, 6, 6, 7, 5, 8}      // Star 3
    };

    /** Initial orbit angles per child star (from off_187DE $34 bytes). */
    private static final int[] CHILD_INIT_ANGLES = {0x00, 0x00, 0x16 / 2, 0x2C / 2};
    // Note: $16 and $2C are byte offsets in 2-byte table → /2 for entry indices = 0, 0, 11, 22

    private static final int CHILD_COUNT = 4;
    private static final int PARENT_ROTATION_SPEED = 9;  // ROM: $12 / 2 = 9 entries/frame
    private static final int CHILD_ROTATION_SPEED = 1;   // ROM: $02 / 2 = 1 entry/frame

    // ── Mutable state ───────────────────────────────────────────────────

    private int parentAngle = 4; // ROM: init $34 = 4 (from line 33778)
    private int parentAnimIndex = 0;
    private final int[] childAngles = new int[CHILD_COUNT];
    private final int[] childAnimIndices = new int[CHILD_COUNT];

    public Sonic3kInvincibilityStarsObjectInstance(PlayableEntity player) {
        super(null, "S3kInvincibilityStars");
        this.player = player;

        ObjectRenderManager renderManager = getRenderManager();
        this.renderer = (renderManager != null)
                ? renderManager.getInvincibilityStarsRenderer()
                : null;

        // Initialize child angles from disassembly init values
        for (int i = 0; i < CHILD_COUNT; i++) {
            childAngles[i] = CHILD_INIT_ANGLES[i] % ORBIT_TABLE_SIZE;
        }
    }

    // ── Public API for tests ────────────────────────────────────────────

    /**
     * Returns how many frames behind the player a given child star trails.
     * ROM formula: starIndex * 12 bytes in Pos_table / 4 bytes per entry = starIndex * 3 frames.
     */
    public static int trailingFramesBehind(int starIndex) {
        return starIndex * 3;
    }

    // ── Update ──────────────────────────────────────────────────────────

    @Override
    public void update(int frameCounter, PlayableEntity player) {
        boolean facingLeft = player.getDirection() == Direction.LEFT;
        int dirSign = facingLeft ? -1 : 1;

        // Advance parent orbit angle
        parentAngle = wrapAngle(parentAngle + dirSign * PARENT_ROTATION_SPEED);

        // Advance parent animation
        parentAnimIndex = (parentAnimIndex + 1) % PARENT_ANIM.length;

        // Advance child orbit angles and animations
        for (int i = 0; i < CHILD_COUNT; i++) {
            childAngles[i] = wrapAngle(childAngles[i] + dirSign * CHILD_ROTATION_SPEED);
            childAnimIndices[i] = (childAnimIndices[i] + 1) % CHILD_PRIMARY_ANIMS[i].length;
        }
    }

    // ── Render ───────────────────────────────────────────────────────────

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (renderer == null || player == null) {
            return;
        }

        // Parent group: at player position, fast orbit
        drawStarGroup(player.getCentreX(), player.getCentreY(),
                parentAngle, PARENT_ANIM[parentAnimIndex], PARENT_ANIM[parentAnimIndex]);

        // 4 child groups: trailing positions, slow orbit
        for (int i = 0; i < CHILD_COUNT; i++) {
            int framesBehind = trailingFramesBehind(i);
            int cx = player.getCentreX(framesBehind);
            int cy = player.getCentreY(framesBehind);

            int primaryFrame = CHILD_PRIMARY_ANIMS[i][childAnimIndices[i]];
            int secondaryFrame = CHILD_SECONDARY_ANIMS[i][childAnimIndices[i]];

            drawStarGroup(cx, cy, childAngles[i], primaryFrame, secondaryFrame);
        }
    }

    /**
     * Draws a single star group: 2 sub-sprites at opposite orbit positions.
     */
    private void drawStarGroup(int centerX, int centerY, int angle,
                               int frameA, int frameB) {
        // Sub-sprite A
        int angleA = angle % ORBIT_TABLE_SIZE;
        int[] offsetA = S3K_ORBIT_OFFSETS[angleA];
        renderer.drawFrameIndex(frameA, centerX + offsetA[0], centerY + offsetA[1], false, false);

        // Sub-sprite B: phase-offset by half circle
        int angleB = (angle + SUB_SPRITE_PHASE) % ORBIT_TABLE_SIZE;
        int[] offsetB = S3K_ORBIT_OFFSETS[angleB];
        renderer.drawFrameIndex(frameB, centerX + offsetB[0], centerY + offsetB[1], false, false);
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private static int wrapAngle(int angle) {
        return ((angle % ORBIT_TABLE_SIZE) + ORBIT_TABLE_SIZE) % ORBIT_TABLE_SIZE;
    }

    // ── PowerUpObject / rendering boilerplate ────────────────────────────

    @Override
    public boolean isHighPriority() {
        return false;
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(1); // ROM: priority $80
    }

    @Override
    public void destroy() {
        setDestroyed(true);
    }

    @Override
    public void setVisible(boolean visible) {
        // Stars are always visible while alive; no-op.
    }
}
```

- [ ] **Step 2: Run the tests**

```bash
mvn test -Dtest=TestSonic3kInvincibilityStars -q
```

Expected: All 9 tests PASS.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/objects/Sonic3kInvincibilityStarsObjectInstance.java
git add src/test/java/com/openggf/game/sonic3k/objects/TestSonic3kInvincibilityStars.java
git commit -m "feat(s3k): implement Sonic3kInvincibilityStarsObjectInstance with trailing orbit"
```

---

### Task 4: Add Art Loading to Sonic3kObjectArtProvider

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kObjectArtProvider.java:127` and new method

- [ ] **Step 1: Add loadInvincibilityStarArt() call in loadArtForZone()**

In `Sonic3kObjectArtProvider.java`, find the line `loadShieldArt();` (line ~127) and add the invincibility star loading call right after it:

```java
        // Load shield art (DPLC-driven, same for all zones)
        loadShieldArt();

        // Load invincibility star art (non-DPLC, same for all zones)
        loadInvincibilityStarArt();
```

- [ ] **Step 2: Add the loadInvincibilityStarArt() method**

Add this method after `loadShieldArt()` (after line ~724):

```java
    /**
     * Loads invincibility star art from ROM (uncompressed art + ROM-parsed mappings).
     * ROM: ArtUnc_Invincibility binclude (16 tiles), Map_Invincibility (9 frames).
     * No DPLCs — static tile assignment like explosion art.
     */
    private void loadInvincibilityStarArt() {
        try {
            Rom rom = GameServices.rom().getRom();
            if (rom == null) {
                return;
            }
            Pattern[] patterns = loadUncompressedPatterns(rom,
                    Sonic3kConstants.ART_UNC_INVINCIBILITY_ADDR,
                    Sonic3kConstants.ART_UNC_INVINCIBILITY_SIZE);

            RomByteReader reader = RomByteReader.fromRom(rom);
            List<SpriteMappingFrame> rawMappings =
                    S3kSpriteDataLoader.loadMappingFrames(reader, Sonic3kConstants.MAP_INVINCIBILITY_ADDR);

            // Normalize tile indices to 0-based (ROM mappings reference ArtTile_Shield = $079C)
            List<SpriteMappingFrame> mappings = normalizeMappingTileIndices(rawMappings);

            ObjectSpriteSheet sheet = new ObjectSpriteSheet(patterns, mappings, 0, 1);
            registerSheet(ObjectArtKeys.INVINCIBILITY_STARS, sheet);
            LOG.info("Loaded S3K invincibility star art: " + patterns.length
                    + " tiles, " + mappings.size() + " mapping frames");
        } catch (Exception e) {
            LOG.warning("Failed to load invincibility star art: " + e.getMessage());
        }
    }

    /**
     * Normalizes mapping tile indices to be 0-based by subtracting the minimum
     * tile index found across all pieces. Required when ROM mappings reference
     * absolute VRAM tile positions (e.g. ArtTile_Shield = $079C).
     */
    private static List<SpriteMappingFrame> normalizeMappingTileIndices(List<SpriteMappingFrame> frames) {
        int minTile = Integer.MAX_VALUE;
        for (SpriteMappingFrame frame : frames) {
            for (SpriteMappingPiece piece : frame.getPieces()) {
                if (piece.startTile() < minTile) {
                    minTile = piece.startTile();
                }
            }
        }
        if (minTile == 0 || minTile == Integer.MAX_VALUE) {
            return frames;
        }
        final int offset = minTile;
        List<SpriteMappingFrame> normalized = new ArrayList<>(frames.size());
        for (SpriteMappingFrame frame : frames) {
            List<SpriteMappingPiece> pieces = new ArrayList<>(frame.getPieces().size());
            for (SpriteMappingPiece p : frame.getPieces()) {
                pieces.add(new SpriteMappingPiece(
                        p.y(), p.x(), p.widthTiles(), p.heightTiles(),
                        p.startTile() - offset, p.hFlip(), p.vFlip(),
                        p.paletteLine(), p.priority()));
            }
            normalized.add(new SpriteMappingFrame(pieces));
        }
        return normalized;
    }
```

- [ ] **Step 3: Add required imports**

Add these imports at the top of `Sonic3kObjectArtProvider.java` if not already present:

```java
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.render.SpriteMappingPiece;
```

The `RomByteReader`, `S3kSpriteDataLoader`, `SpriteMappingFrame`, `ObjectSpriteSheet`, and `PatternSpriteRenderer` imports should already be present from shield art loading.

- [ ] **Step 4: Verify it compiles**

```bash
mvn compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/Sonic3kObjectArtProvider.java
git commit -m "feat(s3k): load invincibility star art and mappings from ROM"
```

---

### Task 5: Wire Up DefaultPowerUpSpawner

**Files:**
- Modify: `src/main/java/com/openggf/level/objects/DefaultPowerUpSpawner.java:61-66`

- [ ] **Step 1: Add S3K branch to spawnInvincibilityStars()**

Replace the existing `spawnInvincibilityStars()` method:

```java
    @Override
    public PowerUpObject spawnInvincibilityStars(PlayableEntity player) {
        AbstractObjectInstance stars;
        if (GameModuleRegistry.getCurrent() instanceof Sonic3kGameModule) {
            stars = new Sonic3kInvincibilityStarsObjectInstance(player);
        } else {
            stars = new InvincibilityStarsObjectInstance(player);
        }
        objectManager.addDynamicObject(stars);
        return (PowerUpObject) stars;
    }
```

- [ ] **Step 2: Add required imports**

Add at the top of `DefaultPowerUpSpawner.java`:

```java
import com.openggf.game.GameModuleRegistry;
import com.openggf.game.sonic3k.Sonic3kGameModule;
import com.openggf.game.sonic3k.objects.Sonic3kInvincibilityStarsObjectInstance;
```

- [ ] **Step 3: Verify it compiles**

```bash
mvn compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/openggf/level/objects/DefaultPowerUpSpawner.java
git commit -m "feat(s3k): spawn S3K invincibility stars variant from DefaultPowerUpSpawner"
```

---

### Task 6: Build and Full Test Suite

**Files:** None (verification only)

- [ ] **Step 1: Run full test suite**

```bash
mvn test -q
```

Expected: BUILD SUCCESS, all tests pass. The new `TestSonic3kInvincibilityStars` tests pass alongside existing tests. The S1/S2 `TestInvincibilityStarsObjectInstance` tests remain green.

- [ ] **Step 2: Run S3K-specific tests**

```bash
mvn test -Dtest=TestSonic3kInvincibilityStars,TestInvincibilityStarsObjectInstance -q
```

Expected: All tests pass — both S3K and S2 invincibility star tests.

- [ ] **Step 3: Build the JAR**

```bash
mvn package -q
```

Expected: BUILD SUCCESS, JAR created at `target/sonic-engine-0.4.prerelease-jar-with-dependencies.jar`.
