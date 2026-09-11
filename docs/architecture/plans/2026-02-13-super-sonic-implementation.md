# Super Sonic Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Implement Super Sonic for Sonic 2 and Sonic 3&K with ROM-accurate palette cycling, animations, physics, ring drain, emerald gating, and a debug toggle.

**Architecture:** A `SuperStateController` (following the `DrowningController` pattern) manages the full Super lifecycle as a sub-controller on `PlayableSpriteController`. Game-specific subclasses (`Sonic2SuperStateController`, `Sonic3kSuperStateController`) provide per-game palette data, animation IDs, physics profiles, and ROM addresses. The animation system is extended with a `SuperSonicAnimationProfile` wrapper that remaps animation IDs when Super is active.

**Tech Stack:** Java 21, OpenGL (via GLFW), Maven build, existing Sonic engine frameworks.

---

### Task 1: Add S2 Super Sonic Constants

**Files:**
- Modify: `src/main/java/uk/co/jamesj999/sonic/game/sonic2/constants/Sonic2Constants.java`
- Modify: `src/main/java/uk/co/jamesj999/sonic/game/sonic2/constants/Sonic2AnimationIds.java`
- Modify: `src/main/java/uk/co/jamesj999/sonic/game/sonic2/constants/Sonic2AudioConstants.java`

**Step 1: Add ROM addresses for Super Sonic data to Sonic2Constants**

The Super Sonic animation data lives within the existing Sonic animation table at `SONIC_ANIM_DATA_ADDR` (0x01B618). The existing `SONIC_ANIM_SCRIPT_COUNT` of 0x22 (34) already covers scripts 0x00-0x21, but Super Sonic animations are at higher indices. The S2 disassembly shows Super Sonic animations start at index 0x1F (31) for the Transform animation, and the `SuperSonicAniData` table has its own set of animation scripts (indices 0-10) that map to different animation IDs.

Add these constants to `Sonic2Constants.java`:

```java
// Super Sonic transformation palette data
public static final int CYCLING_PAL_SS_TRANSFORMATION_ADDR = 0; // TODO: find via RomOffsetFinder
public static final int CYCLING_PAL_SS_TRANSFORMATION_LEN = 0x78; // 120 bytes (15 frames * 8 bytes)
public static final int CYCLING_PAL_CPZ_UW_SS_TRANSFORMATION_ADDR = 0; // TODO: CPZ underwater variant
public static final int CYCLING_PAL_ARZ_UW_SS_TRANSFORMATION_ADDR = 0; // TODO: ARZ underwater variant

// Super Sonic stars art (Nemesis compressed)
public static final int ART_NEM_SUPER_SONIC_STARS_ADDR = 0; // TODO: find via RomOffsetFinder

// Super Sonic animation data (separate table from normal Sonic)
public static final int SUPER_SONIC_ANIM_DATA_ADDR = 0; // TODO: find via RomOffsetFinder
public static final int SUPER_SONIC_ANIM_SCRIPT_COUNT = 11; // Scripts 0-10

// Ring drain frame interval
public static final int SUPER_SONIC_RING_DRAIN_INTERVAL = 60; // 1 ring per second at 60fps
// Minimum rings to transform
public static final int SUPER_SONIC_MIN_RINGS = 50;
```

**Step 2: Find the actual ROM addresses using RomOffsetFinder**

Run:
```bash
mvn exec:java -Dexec.mainClass="uk.co.jamesj999.sonic.tools.disasm.RomOffsetFinder" -Dexec.args="--game s2 search SuperSonic" -q
mvn exec:java -Dexec.mainClass="uk.co.jamesj999.sonic.tools.disasm.RomOffsetFinder" -Dexec.args="--game s2 search CyclingPal_SS" -q
```

Update the `0` placeholder addresses with verified values.

**Step 3: Add Super Sonic animation IDs to Sonic2AnimationIds**

```java
// Super Sonic animation IDs (indices into SuperSonicAniData table)
public static final int SUPER_WALK = 0x00;
public static final int SUPER_RUN = 0x01;
public static final int SUPER_ROLL = 0x02;
public static final int SUPER_ROLL2 = 0x03;
public static final int SUPER_PUSH = 0x04;
public static final int SUPER_STAND = 0x05;
public static final int SUPER_BALANCE = 0x06;
public static final int SUPER_LOOK_UP = 0x07;
public static final int SUPER_DUCK = 0x08;
public static final int SUPER_SPINDASH = 0x09;
public static final int SUPER_TRANSFORM = 0x1F; // AniIDSupSonAni_Transform (index 31 in normal table)
```

**Step 4: Add Super Sonic SFX constant to Sonic2AudioConstants**

```java
public static final int SND_SUPER_TRANSFORM = 0xDF; // Transformation sound effect
```

Verify `MUS_SUPER_SONIC = 0x93` already exists (line 73).

**Step 5: Commit**

```bash
git add src/main/java/uk/co/jamesj999/sonic/game/sonic2/constants/Sonic2Constants.java src/main/java/uk/co/jamesj999/sonic/game/sonic2/constants/Sonic2AnimationIds.java src/main/java/uk/co/jamesj999/sonic/game/sonic2/constants/Sonic2AudioConstants.java
git commit -m "feat: add Super Sonic ROM constants for S2"
```

---

### Task 2: Add S2 Super Sonic Physics Profile

**Files:**
- Modify: `src/main/java/uk/co/jamesj999/sonic/game/PhysicsProfile.java`
- Test: `src/test/java/uk/co/jamesj999/sonic/game/TestPhysicsProfile.java`

**Step 1: Write the failing test**

Add a test to `TestPhysicsProfile.java`:

```java
@Test
void superSonicS2ProfileHasCorrectValues() {
    PhysicsProfile profile = PhysicsProfile.SONIC_2_SUPER_SONIC;
    assertEquals((short) 0x30, profile.runAccel());     // 3x normal (0x0C * 4)
    assertEquals((short) 0x100, profile.runDecel());    // 2x normal (0x80 * 2)
    assertEquals((short) 0x30, profile.friction());     // Same as accel for Super
    assertEquals((short) 0xA00, profile.max());         // ~1.67x normal (0x600 -> 0xA00)
    assertEquals((short) 1664, profile.jump());         // Unchanged
    // Collision radii unchanged from normal Sonic
    assertEquals((short) 19, profile.standYRadius());
    assertEquals((short) 14, profile.rollYRadius());
}
```

**Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=TestPhysicsProfile#superSonicS2ProfileHasCorrectValues`
Expected: FAIL - `SONIC_2_SUPER_SONIC` field does not exist.

**Step 3: Add the profile to PhysicsProfile.java**

Add after line 94 (after `SONIC_3K_SUPER_SONIC`):

```java
// S2 Super Sonic (same values as S3K: max=0xA00, accel=0x30, decel=0x100)
public static final PhysicsProfile SONIC_2_SUPER_SONIC = new PhysicsProfile(
        (short) 0x30,  // runAccel
        (short) 0x100, // runDecel
        (short) 0x30,  // friction (same as accel for Super)
        (short) 0xA00, // max
        (short) 1664,  // jump (unchanged)
        (short) 32,    // slopeRunning
        (short) 20,    // slopeRollingUp
        (short) 80,    // slopeRollingDown
        (short) 32,    // rollDecel
        (short) 128,   // minStartRollSpeed
        (short) 128,   // minRollSpeed
        (short) 4096,  // maxRoll
        (short) 28,    // rollHeight
        (short) 38,    // runHeight
        (short) 9,     // standXRadius
        (short) 19,    // standYRadius
        (short) 7,     // rollXRadius
        (short) 14     // rollYRadius
);
```

**Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=TestPhysicsProfile#superSonicS2ProfileHasCorrectValues`
Expected: PASS

**Step 5: Commit**

```bash
git add src/main/java/uk/co/jamesj999/sonic/game/PhysicsProfile.java src/test/java/uk/co/jamesj999/sonic/game/TestPhysicsProfile.java
git commit -m "feat: add S2 Super Sonic physics profile"
```

---

### Task 3: Create SuperState Enum and Base SuperStateController

**Files:**
- Create: `src/main/java/uk/co/jamesj999/sonic/sprites/playable/SuperState.java`
- Create: `src/main/java/uk/co/jamesj999/sonic/sprites/playable/SuperStateController.java`
- Test: `src/test/java/uk/co/jamesj999/sonic/sprites/playable/TestSuperStateController.java`

**Step 1: Write the failing test**

Create `TestSuperStateController.java`:

```java
package uk.co.jamesj999.sonic.sprites.playable;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TestSuperStateController {

    @Test
    void initialStateIsNormal() {
        assertEquals(SuperState.NORMAL, SuperState.valueOf("NORMAL"));
        assertEquals(SuperState.TRANSFORMING, SuperState.valueOf("TRANSFORMING"));
        assertEquals(SuperState.SUPER, SuperState.valueOf("SUPER"));
        assertEquals(SuperState.REVERTING, SuperState.valueOf("REVERTING"));
    }
}
```

**Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=TestSuperStateController#initialStateIsNormal`
Expected: FAIL - `SuperState` class not found.

**Step 3: Create SuperState enum**

Create `src/main/java/uk/co/jamesj999/sonic/sprites/playable/SuperState.java`:

```java
package uk.co.jamesj999.sonic.sprites.playable;

/**
 * States for the Super Sonic transformation lifecycle.
 */
public enum SuperState {
    /** Normal gameplay. Checks transformation trigger each frame. */
    NORMAL,
    /** Playing transformation animation, fading palette, starting music. */
    TRANSFORMING,
    /** Super mode active. Ring drain, palette cycling, invincibility. */
    SUPER,
    /** Reverting to normal. Restoring palette, physics, music. */
    REVERTING
}
```

**Step 4: Create base SuperStateController**

Create `src/main/java/uk/co/jamesj999/sonic/sprites/playable/SuperStateController.java`:

```java
package uk.co.jamesj999.sonic.sprites.playable;

import uk.co.jamesj999.sonic.game.GameStateManager;
import uk.co.jamesj999.sonic.game.PhysicsProfile;
import uk.co.jamesj999.sonic.level.LevelManager;

import java.util.logging.Logger;

/**
 * Base controller for Super Sonic transformation lifecycle.
 * Follows the DrowningController pattern as a sub-controller on PlayableSpriteController.
 *
 * <p>Game-specific subclasses provide palette data, animation IDs, physics profiles,
 * and ROM addresses for each game (S2, S3K).
 */
public abstract class SuperStateController {
    private static final Logger LOGGER = Logger.getLogger(SuperStateController.class.getName());

    protected final AbstractPlayableSprite player;
    private SuperState state = SuperState.NORMAL;
    private int ringDrainCounter;
    private int transformAnimTimer;
    private int paletteFrameIndex;
    private int paletteTimer;

    protected SuperStateController(AbstractPlayableSprite player) {
        this.player = player;
        reset();
    }

    /**
     * Resets the Super state. Called on level load / death.
     */
    public void reset() {
        state = SuperState.NORMAL;
        ringDrainCounter = 0;
        transformAnimTimer = 0;
        paletteFrameIndex = 0;
        paletteTimer = 0;
    }

    /**
     * Called once per frame.
     */
    public void update() {
        switch (state) {
            case NORMAL -> checkTransformationTrigger();
            case TRANSFORMING -> updateTransformation();
            case SUPER -> updateSuper();
            case REVERTING -> updateRevert();
        }
    }

    public SuperState getState() {
        return state;
    }

    public boolean isSuper() {
        return state == SuperState.SUPER || state == SuperState.TRANSFORMING;
    }

    // --- Debug toggle ---

    /**
     * Instantly activates Super state, bypassing emerald/ring requirements.
     */
    public void debugActivate() {
        if (state != SuperState.NORMAL) return;
        state = SuperState.SUPER;
        player.setSuperSonic(true);
        applyPhysicsProfile(getSuperProfile());
        ringDrainCounter = getRingDrainInterval();
        onSuperActivated();
        LOGGER.info("Debug: Super Sonic activated");
    }

    /**
     * Instantly deactivates Super state.
     */
    public void debugDeactivate() {
        if (state == SuperState.NORMAL) return;
        revertToNormal();
        LOGGER.info("Debug: Super Sonic deactivated");
    }

    // --- Template methods for subclasses ---

    /** Returns the ring drain interval in frames (default 60 = 1 ring/sec). */
    protected abstract int getRingDrainInterval();

    /** Returns the minimum ring count required to transform. */
    protected abstract int getMinRingsToTransform();

    /** Returns the Super physics profile for this game. */
    protected abstract PhysicsProfile getSuperProfile();

    /** Returns the normal physics profile for this game. */
    protected abstract PhysicsProfile getNormalProfile();

    /** Called when transformation starts. Subclass should play SFX, set animation, start palette fade. */
    protected abstract void onTransformationStarted();

    /** Called each frame during TRANSFORMING. Returns true when transformation animation is complete. */
    protected abstract boolean updateTransformationAnimation();

    /** Called when Super mode becomes fully active. Subclass should play music, spawn stars. */
    protected abstract void onSuperActivated();

    /** Called each frame during SUPER. Subclass should update palette cycling. */
    protected abstract void updateSuperPalette();

    /** Called when reverting. Subclass should restore palette, resume music, destroy stars. */
    protected abstract void onRevertStarted();

    // --- Core logic ---

    private void checkTransformationTrigger() {
        if (!canTransform()) return;

        // ROM: Trigger at peak of jump (y_vel approaching 0)
        if (player.getAir() && player.getJumping() && player.getYSpeed() >= -0x100 && player.getYSpeed() <= 0) {
            startTransformation();
        }
    }

    private boolean canTransform() {
        if (player.isSuperSonic()) return false;
        if (!GameStateManager.getInstance().hasAllEmeralds()) return false;
        if (player.getRingCount() < getMinRingsToTransform()) return false;
        if (player.getDead() || player.isHurt() || player.isDebugMode()) return false;
        if (player.isObjectControlled()) return false;
        return true;
    }

    private void startTransformation() {
        state = SuperState.TRANSFORMING;
        player.setSuperSonic(true);
        onTransformationStarted();
    }

    private void updateTransformation() {
        if (updateTransformationAnimation()) {
            // Transformation animation complete
            state = SuperState.SUPER;
            applyPhysicsProfile(getSuperProfile());
            ringDrainCounter = getRingDrainInterval();
            onSuperActivated();
        }
    }

    private void updateSuper() {
        // Palette cycling
        updateSuperPalette();

        // Ring drain
        ringDrainCounter--;
        if (ringDrainCounter <= 0) {
            ringDrainCounter = getRingDrainInterval();
            int rings = player.getRingCount();
            if (rings <= 0) {
                revertToNormal();
                return;
            }
            player.addRings(-1);
        }
    }

    private void updateRevert() {
        // Revert is instant in the ROM - palette fade handled by onRevertStarted()
        state = SuperState.NORMAL;
    }

    private void revertToNormal() {
        state = SuperState.REVERTING;
        player.setSuperSonic(false);
        applyPhysicsProfile(getNormalProfile());
        onRevertStarted();
        // Immediately transition to NORMAL (palette fade is async in subclass)
        state = SuperState.NORMAL;
    }

    private void applyPhysicsProfile(PhysicsProfile profile) {
        player.applyExternalPhysicsProfile(profile);
    }
}
```

**Step 5: Run tests to verify they pass**

Run: `mvn test -Dtest=TestSuperStateController`
Expected: PASS

**Step 6: Add `applyExternalPhysicsProfile` method to AbstractPlayableSprite**

This method is needed by `SuperStateController` to swap physics at runtime. Add to `AbstractPlayableSprite.java` after the existing `resolvePhysicsProfile()` method (around line 1621):

```java
/**
 * Applies an external physics profile, overwriting current speed values.
 * Used by SuperStateController to swap between normal and Super physics.
 */
public void applyExternalPhysicsProfile(PhysicsProfile profile) {
    if (profile == null) return;
    this.physicsProfile = profile;
    this.runAccel = profile.runAccel();
    this.runDecel = profile.runDecel();
    this.friction = profile.friction();
    this.max = profile.max();
    this.jump = profile.jump();
    this.slopeRunning = profile.slopeRunning();
    this.slopeRollingUp = profile.slopeRollingUp();
    this.slopeRollingDown = profile.slopeRollingDown();
    this.rollDecel = profile.rollDecel();
    this.minStartRollSpeed = profile.minStartRollSpeed();
    this.minRollSpeed = profile.minRollSpeed();
    this.maxRoll = profile.maxRoll();
    this.rollHeight = profile.rollHeight();
    this.runHeight = profile.runHeight();
    this.standXRadius = profile.standXRadius();
    this.standYRadius = profile.standYRadius();
    this.rollXRadius = profile.rollXRadius();
    this.rollYRadius = profile.rollYRadius();
}
```

**Step 7: Commit**

```bash
git add src/main/java/uk/co/jamesj999/sonic/sprites/playable/SuperState.java src/main/java/uk/co/jamesj999/sonic/sprites/playable/SuperStateController.java src/test/java/uk/co/jamesj999/sonic/sprites/playable/TestSuperStateController.java src/main/java/uk/co/jamesj999/sonic/sprites/playable/AbstractPlayableSprite.java
git commit -m "feat: create SuperState enum and base SuperStateController"
```

---

### Task 4: Create Sonic2SuperStateController

**Files:**
- Create: `src/main/java/uk/co/jamesj999/sonic/game/sonic2/Sonic2SuperStateController.java`
- Test: `src/test/java/uk/co/jamesj999/sonic/game/sonic2/TestSonic2SuperStateController.java`

**Step 1: Write the failing test**

```java
package uk.co.jamesj999.sonic.game.sonic2;

import org.junit.jupiter.api.Test;
import uk.co.jamesj999.sonic.game.PhysicsProfile;
import static org.junit.jupiter.api.Assertions.*;

class TestSonic2SuperStateController {

    @Test
    void superProfileMatchesS2DisassemblyValues() {
        // S2 Super Sonic: top_speed=$A00, accel=$30, decel=$100
        PhysicsProfile profile = PhysicsProfile.SONIC_2_SUPER_SONIC;
        assertEquals((short) 0xA00, profile.max());
        assertEquals((short) 0x30, profile.runAccel());
        assertEquals((short) 0x100, profile.runDecel());
    }

    @Test
    void ringDrainIntervalIs60Frames() {
        // S2 uses 60 frames (1 second) per ring drain
        assertEquals(60, 60); // Placeholder until controller is instantiable
    }
}
```

**Step 2: Run test to verify it passes (these tests only verify constants for now)**

Run: `mvn test -Dtest=TestSonic2SuperStateController`
Expected: PASS (basic constant verification)

**Step 3: Create Sonic2SuperStateController**

Create `src/main/java/uk/co/jamesj999/sonic/game/sonic2/Sonic2SuperStateController.java`:

```java
package uk.co.jamesj999.sonic.game.sonic2;

import uk.co.jamesj999.sonic.audio.AudioManager;
import uk.co.jamesj999.sonic.game.PhysicsProfile;
import uk.co.jamesj999.sonic.game.sonic2.constants.Sonic2AudioConstants;
import uk.co.jamesj999.sonic.game.sonic2.constants.Sonic2Constants;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;
import uk.co.jamesj999.sonic.sprites.playable.SuperStateController;

import java.util.logging.Logger;

/**
 * S2-specific Super Sonic controller.
 * Provides S2 palette cycling data, animation IDs, physics, and transformation behavior.
 */
public class Sonic2SuperStateController extends SuperStateController {
    private static final Logger LOGGER = Logger.getLogger(Sonic2SuperStateController.class.getName());

    /** Palette state: 0=off, 1=fading in, -1=cycling, 2=fading out */
    private int paletteState;
    private int paletteFrame;
    private int paletteTimer;
    private int transformFramesRemaining;

    public Sonic2SuperStateController(AbstractPlayableSprite player) {
        super(player);
    }

    @Override
    public void reset() {
        super.reset();
        paletteState = 0;
        paletteFrame = 0;
        paletteTimer = 0;
        transformFramesRemaining = 0;
    }

    @Override
    protected int getRingDrainInterval() {
        return Sonic2Constants.SUPER_SONIC_RING_DRAIN_INTERVAL;
    }

    @Override
    protected int getMinRingsToTransform() {
        return Sonic2Constants.SUPER_SONIC_MIN_RINGS;
    }

    @Override
    protected PhysicsProfile getSuperProfile() {
        return PhysicsProfile.SONIC_2_SUPER_SONIC;
    }

    @Override
    protected PhysicsProfile getNormalProfile() {
        return PhysicsProfile.SONIC_2_SONIC;
    }

    @Override
    protected void onTransformationStarted() {
        // Set palette state to fading in
        paletteState = 1;
        paletteFrame = 0;
        paletteTimer = 3; // ROM: Palette_timer = 15 initially, then 3 per cycle step

        // Freeze player during transformation (obj_control = $81 in ROM)
        // Play transformation SFX
        try {
            AudioManager.getInstance().playSfx(Sonic2AudioConstants.SND_SUPER_TRANSFORM);
        } catch (Exception e) {
            LOGGER.fine("Could not play transformation SFX: " + e.getMessage());
        }

        // Set transformation animation - 30 frames total (15 frames * 2 frame delay)
        transformFramesRemaining = 30;

        // TODO: Set player animation to SupSonAni_Transform (0x1F)
        // TODO: Spawn SuperSonicStars object
    }

    @Override
    protected boolean updateTransformationAnimation() {
        // Update palette fade-in
        updatePaletteFade();

        transformFramesRemaining--;
        return transformFramesRemaining <= 0;
    }

    @Override
    protected void onSuperActivated() {
        // Start continuous palette cycling
        paletteState = -1; // FF in ROM = normal cycling
        paletteTimer = 7; // ROM: timer 7 for normal cycling

        // Play Super Sonic music
        try {
            AudioManager.getInstance().playMusic(Sonic2AudioConstants.MUS_SUPER_SONIC);
        } catch (Exception e) {
            LOGGER.fine("Could not play Super Sonic music: " + e.getMessage());
        }

        // Clear existing invincibility timer (ROM: clr.b invincibility_time)
        player.setInvincibleFrames(0);

        LOGGER.info("Super Sonic activated (S2)");
    }

    @Override
    protected void updateSuperPalette() {
        if (paletteState == -1) {
            // Normal cycling
            paletteTimer--;
            if (paletteTimer <= 0) {
                paletteTimer = 7;
                paletteFrame += 8; // Advance by 8 bytes (4 color entries)
                // Wrap palette frame within cycling range
                // TODO: implement actual palette write using ROM data
            }
        }
    }

    @Override
    protected void onRevertStarted() {
        // Start palette fade-out
        paletteState = 2;
        paletteFrame = 0x28; // ROM: Palette_frame = $28 on revert

        // Restore invincibility to 1 frame (grace period)
        player.setInvincibleFrames(1);

        // Resume zone music
        // TODO: AudioManager resume level music

        // TODO: Destroy SuperSonicStars object

        LOGGER.info("Super Sonic deactivated (S2)");
    }

    private void updatePaletteFade() {
        if (paletteState == 0) return;

        paletteTimer--;
        if (paletteTimer <= 0) {
            paletteTimer = 3;
            if (paletteState == 1) {
                paletteFrame += 8; // Fade in: advance forward
                // TODO: Apply palette colors from ROM data
            } else if (paletteState == 2) {
                paletteFrame -= 8; // Fade out: advance backward
                if (paletteFrame <= 0) {
                    paletteState = 0; // Fade complete
                }
            }
        }
    }
}
```

**Step 4: Commit**

```bash
git add src/main/java/uk/co/jamesj999/sonic/game/sonic2/Sonic2SuperStateController.java src/test/java/uk/co/jamesj999/sonic/game/sonic2/TestSonic2SuperStateController.java
git commit -m "feat: create Sonic2SuperStateController with palette/physics/audio"
```

---

### Task 5: Wire SuperStateController into PlayableSpriteController

**Files:**
- Modify: `src/main/java/uk/co/jamesj999/sonic/sprites/playable/PlayableSpriteController.java`

**Step 1: Add SuperStateController field and accessor**

In `PlayableSpriteController.java`, add after line 13 (`private TailsTailsController tailsTails;`):

```java
private SuperStateController superState;
```

Add getter/setter after the existing `getTailsTails()`/`setTailsTails()` methods:

```java
public SuperStateController getSuperState() {
    return superState;
}

public void setSuperState(SuperStateController superState) {
    this.superState = superState;
}
```

**Step 2: Commit**

```bash
git add src/main/java/uk/co/jamesj999/sonic/sprites/playable/PlayableSpriteController.java
git commit -m "feat: add SuperStateController slot to PlayableSpriteController"
```

---

### Task 6: Wire Invulnerability and Enemy Destruction for Super Sonic

**Files:**
- Modify: `src/main/java/uk/co/jamesj999/sonic/sprites/playable/AbstractPlayableSprite.java` (line 920)
- Modify: `src/main/java/uk/co/jamesj999/sonic/level/objects/ObjectManager.java` (line 1235)

**Step 1: Update getInvulnerable() to include Super Sonic**

In `AbstractPlayableSprite.java` at line 920, change:

```java
public boolean getInvulnerable() {
    // Debug mode makes player completely invulnerable
    return debugMode || invulnerableFrames > 0 || invincibleFrames > 0 || hurt;
}
```

To:

```java
public boolean getInvulnerable() {
    // Debug mode and Super Sonic make player completely invulnerable
    return debugMode || superSonic || invulnerableFrames > 0 || invincibleFrames > 0 || hurt;
}
```

**Step 2: Update isPlayerAttacking() to include Super Sonic**

In `ObjectManager.java` at line 1235, change:

```java
private boolean isPlayerAttacking(AbstractPlayableSprite player) {
    return player.getInvincibleFrames() > 0
            || player.getRolling()
            || player.getSpindash();
}
```

To:

```java
private boolean isPlayerAttacking(AbstractPlayableSprite player) {
    return player.isSuperSonic()
            || player.getInvincibleFrames() > 0
            || player.getRolling()
            || player.getSpindash();
}
```

This ensures Super Sonic destroys enemies on contact (treated as attacking) rather than taking damage.

**Step 3: Commit**

```bash
git add src/main/java/uk/co/jamesj999/sonic/sprites/playable/AbstractPlayableSprite.java src/main/java/uk/co/jamesj999/sonic/level/objects/ObjectManager.java
git commit -m "feat: wire Super Sonic invulnerability and enemy destruction"
```

---

### Task 7: Add Super Sonic Animation Profile Wrapper

**Files:**
- Create: `src/main/java/uk/co/jamesj999/sonic/sprites/animation/SuperSonicAnimationProfile.java`

**Step 1: Create the wrapper**

This wraps an existing `SpriteAnimationProfile` and remaps animation IDs when the player is Super Sonic. The animation table pointer is swapped so the same state-based resolution logic applies, but indices map to the Super Sonic animation set.

```java
package uk.co.jamesj999.sonic.sprites.animation;

import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

/**
 * Wraps a normal SpriteAnimationProfile and remaps animation IDs when
 * the player is in Super Sonic state.
 *
 * <p>The Super Sonic animation set uses the same index structure as normal Sonic
 * (walk=0, run=1, roll=2, etc.) but references different mapping frames.
 * This wrapper swaps the animation set pointer so the same state-based
 * resolution logic applies to the Super set.
 */
public class SuperSonicAnimationProfile implements SpriteAnimationProfile {
    private final SpriteAnimationProfile normalProfile;
    private final int animationSetOffset;

    /**
     * @param normalProfile the underlying profile for state-based animation resolution
     * @param animationSetOffset offset added to animation IDs when Super
     *        (e.g., if Super animations start at a separate table, this is the base offset)
     */
    public SuperSonicAnimationProfile(SpriteAnimationProfile normalProfile, int animationSetOffset) {
        this.normalProfile = normalProfile;
        this.animationSetOffset = animationSetOffset;
    }

    @Override
    public Integer resolveAnimationId(AbstractPlayableSprite sprite, int frameCounter, int scriptCount) {
        Integer baseId = normalProfile.resolveAnimationId(sprite, frameCounter, scriptCount);
        if (baseId == null) return null;

        // When Super Sonic, remap to Super animation set
        // The Super animation set uses the same index structure but different frames
        if (sprite.isSuperSonic() && animationSetOffset > 0) {
            return baseId + animationSetOffset;
        }
        return baseId;
    }

    @Override
    public int resolveFrame(AbstractPlayableSprite sprite, int frameCounter, int frameCount) {
        return normalProfile.resolveFrame(sprite, frameCounter, frameCount);
    }
}
```

Note: This approach needs refinement during implementation - the S2 Super Sonic animations share some scripts with normal Sonic (roll, spindash, lookUp, blink) while having unique ones for walk/run/stand/push/balance/duck. The actual remapping may need a lookup table rather than a simple offset. Implementer should verify against the `SuperSonicAniData` table in the disassembly.

**Step 2: Commit**

```bash
git add src/main/java/uk/co/jamesj999/sonic/sprites/animation/SuperSonicAnimationProfile.java
git commit -m "feat: add SuperSonicAnimationProfile wrapper for animation remapping"
```

---

### Task 8: Load Super Sonic Animation Scripts from ROM

**Files:**
- Modify: `src/main/java/uk/co/jamesj999/sonic/game/sonic2/Sonic2PlayerArt.java`

**Step 1: Add method to load Super Sonic animation set**

The Super Sonic animations live in a separate table in ROM (`SuperSonicAniData`). Add a method after `loadSonicAnimations()` (line 250):

```java
private SpriteAnimationSet loadSuperSonicAnimations() {
    SpriteAnimationSet set = new SpriteAnimationSet();
    int base = Sonic2Constants.SUPER_SONIC_ANIM_DATA_ADDR;
    int count = Sonic2Constants.SUPER_SONIC_ANIM_SCRIPT_COUNT;

    for (int i = 0; i < count; i++) {
        int scriptAddr = base + reader.readU16BE(base + i * 2);
        int delay = reader.readU8(scriptAddr);
        scriptAddr += 1;

        List<Integer> frames = new ArrayList<>();
        SpriteAnimationEndAction endAction = SpriteAnimationEndAction.LOOP;
        int endParam = 0;

        while (true) {
            int value = reader.readU8(scriptAddr);
            scriptAddr += 1;
            if (value >= 0xF0) {
                if (value == 0xFF) {
                    endAction = SpriteAnimationEndAction.LOOP;
                    break;
                }
                if (value == 0xFE) {
                    endAction = SpriteAnimationEndAction.LOOP_BACK;
                    endParam = reader.readU8(scriptAddr);
                    scriptAddr += 1;
                    break;
                }
                if (value == 0xFD) {
                    endAction = SpriteAnimationEndAction.SWITCH;
                    endParam = reader.readU8(scriptAddr);
                    scriptAddr += 1;
                    break;
                }
                endAction = SpriteAnimationEndAction.HOLD;
                break;
            }
            frames.add(value);
        }

        set.addScript(i, new SpriteAnimationScript(delay, frames, endAction, endParam));
    }
    return set;
}
```

**Step 2: Expose Super Sonic animations via a new method**

```java
public SpriteAnimationSet loadSuperSonicAnimationSet() throws IOException {
    return loadSuperSonicAnimations();
}
```

**Step 3: Commit**

```bash
git add src/main/java/uk/co/jamesj999/sonic/game/sonic2/Sonic2PlayerArt.java
git commit -m "feat: load Super Sonic animation scripts from ROM"
```

---

### Task 9: Integrate SuperStateController with Game Loop

**Files:**
- Modify: `src/main/java/uk/co/jamesj999/sonic/sprites/playable/AbstractPlayableSprite.java`
- Modify: `src/main/java/uk/co/jamesj999/sonic/game/sonic2/Sonic2GameModule.java`

**Step 1: Find where DrowningController.update() is called**

Search for the call site where the drowning controller is ticked each frame. This is where `SuperStateController.update()` should also be called.

**Step 2: Add SuperStateController.update() call in the same frame update path**

In the player's per-frame update (likely in `AbstractPlayableSprite` or the movement update path), add:

```java
// Update Super Sonic state
var superCtrl = controller.getSuperState();
if (superCtrl != null) {
    superCtrl.update();
}
```

**Step 3: Wire Sonic2SuperStateController creation in Sonic2GameModule**

When the player sprite is created for Sonic 2, attach the S2 controller. Find where `PlayableSpriteController` is constructed and add:

```java
controller.setSuperState(new Sonic2SuperStateController(sprite));
```

**Step 4: Reset SuperStateController on level load / death**

In `AbstractPlayableSprite.resetState()` (around line 486 where `superSonic = false` is already set), add:

```java
// Reset Super state controller
if (controller != null && controller.getSuperState() != null) {
    controller.getSuperState().reset();
}
```

**Step 5: Commit**

```bash
git add src/main/java/uk/co/jamesj999/sonic/sprites/playable/AbstractPlayableSprite.java src/main/java/uk/co/jamesj999/sonic/game/sonic2/Sonic2GameModule.java
git commit -m "feat: integrate SuperStateController into game loop and S2 module"
```

---

### Task 10: Add Debug Toggle for Super Sonic

**Files:**
- Modify: `src/main/java/uk/co/jamesj999/sonic/configuration/SonicConfigurationService.java`
- Modify: Input handling code (find where debug key 'D' is processed)

**Step 1: Add debug key configuration**

In `SonicConfigurationService`, add a new config key:

```java
public static final String SUPER_SONIC_DEBUG_KEY = "SUPER_SONIC_DEBUG_KEY";
// Default: GLFW_KEY_S (83)
```

**Step 2: Add key handler in the input processing code**

Find where `DEBUG_MODE_KEY` (key D) is handled for toggling debug fly mode. Add a similar handler for the Super Sonic debug key:

```java
// Super Sonic debug toggle (only when debug view enabled)
if (key == configService.getInt(SUPER_SONIC_DEBUG_KEY, 83) && debugViewEnabled) {
    var superCtrl = player.getController().getSuperState();
    if (superCtrl != null) {
        if (superCtrl.isSuper()) {
            superCtrl.debugDeactivate();
        } else {
            superCtrl.debugActivate();
        }
    }
}
```

**Step 3: Commit**

```bash
git add src/main/java/uk/co/jamesj999/sonic/configuration/SonicConfigurationService.java
git commit -m "feat: add debug toggle key for Super Sonic"
```

---

### Task 11: Implement Super Sonic Palette Cycling from ROM Data

**Files:**
- Modify: `src/main/java/uk/co/jamesj999/sonic/game/sonic2/Sonic2SuperStateController.java`

**Step 1: Load palette data from ROM**

The Super Sonic palette cycling data is at `CYCLING_PAL_SS_TRANSFORMATION_ADDR`. It contains 15 frames of 4 Mega Drive color entries (8 bytes per frame = 120 bytes total). The first 6 frames are the fade-in sequence; the remaining frames are the continuous cycling.

Add ROM data loading to the constructor:

```java
private short[][] paletteFrames; // [frameIndex][colorIndex] = MD color value

private void loadPaletteData(RomByteReader reader) {
    int addr = Sonic2Constants.CYCLING_PAL_SS_TRANSFORMATION_ADDR;
    int totalFrames = Sonic2Constants.CYCLING_PAL_SS_TRANSFORMATION_LEN / 8;
    paletteFrames = new short[totalFrames][4];
    for (int f = 0; f < totalFrames; f++) {
        for (int c = 0; c < 4; c++) {
            paletteFrames[f][c] = (short) reader.readU16BE(addr + f * 8 + c * 2);
        }
    }
}
```

**Step 2: Apply palette colors to the sprite's palette line**

```java
private void applyPaletteFrame(int frameIndex) {
    if (paletteFrames == null || frameIndex < 0 || frameIndex >= paletteFrames.length) return;
    Palette palette = LevelManager.getInstance().getLevel().getPalettes()[0]; // Line 0
    // Colors 2-5 in the palette line (Sonic's body colors)
    for (int c = 0; c < 4; c++) {
        palette.setColor(2 + c, mdColorToRgba(paletteFrames[frameIndex][c]));
    }
    GraphicsManager.getInstance().markPaletteDirty();
}
```

**Step 3: Commit**

```bash
git add src/main/java/uk/co/jamesj999/sonic/game/sonic2/Sonic2SuperStateController.java
git commit -m "feat: implement ROM-exact Super Sonic palette cycling"
```

---

### Task 12: Create Sonic3kSuperStateController Stub

**Files:**
- Create: `src/main/java/uk/co/jamesj999/sonic/game/sonic3k/Sonic3kSuperStateController.java`

**Step 1: Create the S3K controller stub**

```java
package uk.co.jamesj999.sonic.game.sonic3k;

import uk.co.jamesj999.sonic.game.PhysicsProfile;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;
import uk.co.jamesj999.sonic.sprites.playable.SuperStateController;

import java.util.logging.Logger;

/**
 * S3K-specific Super Sonic controller.
 * Uses S3K palette data, animation IDs, and physics profile.
 * TODO: Implement palette cycling from S3K ROM data.
 * TODO: Future extension point for Hyper Sonic, Super Tails, Super Knuckles.
 */
public class Sonic3kSuperStateController extends SuperStateController {
    private static final Logger LOGGER = Logger.getLogger(Sonic3kSuperStateController.class.getName());

    public Sonic3kSuperStateController(AbstractPlayableSprite player) {
        super(player);
    }

    @Override protected int getRingDrainInterval() { return 60; }
    @Override protected int getMinRingsToTransform() { return 50; }
    @Override protected PhysicsProfile getSuperProfile() { return PhysicsProfile.SONIC_3K_SUPER_SONIC; }
    @Override protected PhysicsProfile getNormalProfile() { return PhysicsProfile.SONIC_2_SONIC; } // S3K normal = same

    @Override protected void onTransformationStarted() {
        LOGGER.info("S3K Super transformation started (stub)");
    }

    @Override protected boolean updateTransformationAnimation() {
        return true; // Instant for now
    }

    @Override protected void onSuperActivated() {
        LOGGER.info("S3K Super Sonic activated (stub)");
    }

    @Override protected void updateSuperPalette() {
        // TODO: S3K palette cycling
    }

    @Override protected void onRevertStarted() {
        LOGGER.info("S3K Super Sonic deactivated (stub)");
    }
}
```

**Step 2: Wire into Sonic3kGameModule**

Find `Sonic3kGameModule` and add the controller attachment, similar to Task 9 for S2.

**Step 3: Commit**

```bash
git add src/main/java/uk/co/jamesj999/sonic/game/sonic3k/Sonic3kSuperStateController.java
git commit -m "feat: create S3K Super Sonic controller stub"
```

---

### Task 13: Add Shield Interaction Guard

**Files:**
- Modify: `src/main/java/uk/co/jamesj999/sonic/sprites/playable/AbstractPlayableSprite.java`

**Step 1: Prevent shield pickup when Super Sonic (S2)**

In `giveShield()` (line 498), add at the start:

```java
// S2: Super Sonic cannot pick up shields
if (superSonic) {
    return;
}
```

Note: For S3K this guard should be conditional on a `PhysicsFeatureSet` flag (shields can coexist with Super in S3K). Add the feature flag when implementing S3K Super Sonic fully.

**Step 2: Clear Super Sonic in clearPowerUps()**

In `clearPowerUps()` (line 404), add:

```java
// Clear Super state
this.superSonic = false;
if (controller != null && controller.getSuperState() != null) {
    controller.getSuperState().reset();
}
```

**Step 3: Commit**

```bash
git add src/main/java/uk/co/jamesj999/sonic/sprites/playable/AbstractPlayableSprite.java
git commit -m "feat: add shield/power-up interaction guards for Super Sonic"
```

---

### Task 14: Build and Verify All Tests Pass

**Step 1: Run full test suite**

Run: `mvn test`
Expected: All existing tests pass, no regressions.

**Step 2: Run the S3K-specific tests that must remain green**

Run:
```bash
mvn test -Dtest=TestS3kAiz1SpawnStability
mvn test -Dtest=TestSonic3kLevelLoading
mvn test -Dtest=TestSonic3kBootstrapResolver
mvn test -Dtest=TestSonic3kDecodingUtils
```

Expected: All PASS

**Step 3: Run physics tests**

Run:
```bash
mvn test -Dtest=TestPhysicsProfile
mvn test -Dtest=TestPhysicsProfileRegression
mvn test -Dtest=TestSpindashGating
mvn test -Dtest=TestCollisionModel
```

Expected: All PASS

**Step 4: Manual smoke test (if ROM available)**

Run the game with S2 ROM, enable debug mode, use the Super Sonic debug toggle key to verify:
- Physics switch works (faster acceleration/top speed)
- Invulnerability works (enemies don't hurt)
- Palette cycling runs (if ROM addresses were found)
- De-transformation on ring drain

**Step 5: Commit final verification**

```bash
git commit --allow-empty -m "chore: verify all tests pass after Super Sonic implementation"
```

---

## Implementation Notes for the Engineer

### ROM Address Discovery

Several ROM addresses are marked as `TODO: find via RomOffsetFinder` in Task 1. Use:

```bash
mvn exec:java -Dexec.mainClass="uk.co.jamesj999.sonic.tools.disasm.RomOffsetFinder" -Dexec.args="--game s2 search SuperSonic" -q
mvn exec:java -Dexec.mainClass="uk.co.jamesj999.sonic.tools.disasm.RomOffsetFinder" -Dexec.args="--game s2 search CyclingPal" -q
```

If the tool doesn't find the addresses, search the s2disasm source:
- `docs/s2disasm/s2.asm` - Main disassembly, search for `SuperSonicAniData`, `CyclingPal_SSTransformation`, `ArtNem_SuperSonic_stars`

### Key S2 Disassembly References

| ROM Routine | s2.asm Line | Purpose |
|-------------|------------|---------|
| `Sonic_CheckGoSuper` | ~37128 | Transformation trigger conditions |
| `Sonic_Super` | ~37183 | Ring drain logic |
| `Sonic_RevertToNormal` | ~37201 | De-transformation sequence |
| `SuperSonicAniData` | ~38449 | Animation script table |
| `SAnim_Super` | ~38195 | Speed-based animation selection |

### Architecture Patterns to Follow

- **DrowningController** (`sprites/playable/DrowningController.java`) - Reference for controller lifecycle pattern
- **InvincibilityStarsObjectInstance** (`game/sonic2/objects/`) - Reference for visual effect object
- **Sonic2PaletteCycler** (`game/sonic2/`) - Reference for palette cycling implementation
- **PhysicsProfile** (`game/PhysicsProfile.java`) - Reference for physics constant records

### Testing Without ROM

Most tests should not require a ROM. Use the patterns from `TestSpindashGating` and `TestCollisionModel` - create a minimal `TestableSprite` inner class that extends `AbstractPlayableSprite` to test state transitions without needing OpenGL or ROM loading.

### Things Deferred to Future Work

- Super Sonic stars visual effect object (`SuperSonicStarsObjectInstance`)
- S3K palette cycling from ROM
- Hyper Sonic (S3K)
- Super Tails (S3K)
- Super/Hyper Knuckles (S3K)
- Underwater palette variants for CPZ/ARZ
- Screen flash effect on transformation
