package com.openggf.game.sonic1.objects;

import com.openggf.audio.AudioManager;
import com.openggf.game.sonic1.audio.Sonic1Sfx;
import com.openggf.game.PlayableEntity;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.LevelManager;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectLifetimeOps;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.ArrayList;
import java.util.List;

/**
 * Object 35 - Burning Grass (MZ).
 * <p>
 * Fireball that sits on the floor of sinking grassy platforms. Spawned by
 * Object 2F (LargeGrassyPlatform) Type 5 when the platform sinks to angle $20.
 * <p>
 * Two behaviors based on subtype:
 * <ul>
 *   <li>Subtype 0 (WALKER): The initial fire that walks rightward across the platform
 *       surface following the slope data, spawning subtype-1 children every 8 pixels.</li>
 *   <li>Subtype 1 (STATIONARY): A stationary flame that sits at a fixed position
 *       on the platform surface, bobbing with the platform's sinkOffset.</li>
 * </ul>
 * <p>
 * Both subtypes hurt Sonic on contact (obColType = $8B: HURT category, size index $0B).
 * <p>
 * Animation: Ani_GFire .burn: speed 5, frames { 0, $20, 1, $21, afEnd }.
 * Frame $20 = frame 0 with V-flip, $21 = frame 1 with V-flip.
 * <p>
 * Reference: docs/s1disasm/_incObj/35 Burning Grass.asm
 */
public class Sonic1GrassFireObjectInstance extends AbstractObjectInstance
        implements TouchResponseProvider, RewindRecreatable {

    // ========================================================================
    // ROM Constants
    // ========================================================================

    /** obColType = $8B: HURT category ($80) | size index $0B. */
    private static final int COLLISION_FLAGS = 0x8B;

    /** obPriority = 1 (from GFire_Main). */
    private static final int PRIORITY = 1;

    /** obActWid = 8 (from GFire_Main). */
    private static final int ACT_WIDTH = 8;

    /**
     * Animation speed 5 from Ani_GFire .burn (dc.b 5, ...).
     * In S1 AnimateSprite, this means the frame advances every (speed+1) = 6 game frames.
     */
    private static final int ANIM_SPEED = 6;

    /**
     * Animation sequence: frame 0, frame 0 H-flip, frame 1, frame 1 H-flip.
     * The ROM encodes this as { 0, $20, 1, $21 } where bit 5 ($20) maps to
     * obRender bit 0 (H-flip) via AnimateSprite's rol.b #3 transform.
     * (Bit 6 = $40 would be V-flip via obRender bit 1.)
     */
    private static final int[] ANIM_FRAMES = {0, 0, 1, 1};
    private static final boolean[] ANIM_HFLIP = {false, true, false, true};

    /**
     * Walker: X velocity = $10000 subpixels per frame (1 pixel/frame).
     * From loc_B238: addi.l #$10000,obX(a0).
     */
    private static final int WALKER_X_SPEED = 1;

    /**
     * Walker stops spawning children at d1 >= $84 (distance from origin X).
     * From loc_B238: cmpi.w #$84,d1 / bhs.s loc_B2B0.
     */
    private static final int WALKER_MAX_DISTANCE = 0x84;

    /**
     * Slope data offset added to the distance: addi.w #$C,d1.
     * This compensates for the platform starting offset.
     */
    private static final int SLOPE_OFFSET = 0x0C;

    /**
     * Child fire spawn check replicates the ROM's bit test:
     * <pre>
     *   move.l  obX(a0),d0      ; 32-bit X in 16.16 fixed-point
     *   addi.l  #$80000,d0      ; add 8.0 pixels
     *   andi.l  #$FFFFF,d0      ; mask lower 20 bits (4 int + 16 frac)
     *   bne.s   loc_B2B0        ; skip if nonzero
     * </pre>
     * Since fraction is always 0 (walker moves exactly $10000/frame), this
     * reduces to (currentX + 8) & 0xF == 0, i.e. every 16 pixels
     * (NOT 8 — $FFFFF masks 4 integer bits, not 8).
     */
    private static final int SPAWN_CHECK_OFFSET = 8;
    private static final int SPAWN_CHECK_MASK = 0xF;

    // ========================================================================
    // Instance State
    // ========================================================================

    /** Whether this is the walking fire (subtype 0) or stationary (subtype 1). */
    private boolean isWalker;

    /** Current X position (updated for walker). */
    private int currentX;

    /**
     * Base Y from the platform surface (objoff_2C in ROM = lgrass_origY + 5).
     * Un-finaled for rewind: NOT spawn-derivable (getSpawn() reports baseY + sinkOffset,
     * not baseY), so the generic field capturer reapplies the captured value after the
     * recreate hook uses a placeholder.
     */
    private int baseY;

    /** Vertical offset from parent platform sinking (objoff_3C). */
    private int sinkOffset;

    /** Current Y position (computed from baseY + sinkOffset). */
    private int currentY;

    /**
     * Starting X position (gfire_origX).
     * Un-finaled for rewind: NOT spawn-derivable (getSpawn() reports the walked currentX,
     * not originX), so the generic field capturer reapplies the captured value after the
     * recreate hook uses a placeholder.
     */
    private int originX;

    /** Slope height data from parent platform (objoff_30 pointer). */
    private byte[] slopeData;

    /** Reference to parent platform for child registration and sink tracking. */
    private Sonic1LargeGrassyPlatformObjectInstance parentPlatform;

    /** Animation timer. */
    private int animTimer;

    /** Current animation frame index (0-3 into ANIM_FRAMES/ANIM_VFLIP). */
    private int animIndex;

    /** Walker: tracks spawned children for cleanup. */
    private List<Sonic1GrassFireObjectInstance> children;

    /** Whether the sound has been played (only on init, routine 0). */
    private boolean soundPlayed;

    /**
     * Harmless probe constructor used by the generic rewind recreator before
     * delegating to {@link #recreateForRewind(RewindRecreateContext)}.
     */
    public Sonic1GrassFireObjectInstance() {
        this(0, 0, 0, null, null, true);
    }

    /**
     * Creates a GrassFire instance.
     *
     * @param x              Initial X position
     * @param baseY          Base surface Y (platform origY + 5)
     * @param sinkOffset     Current vertical offset from platform sinking
     * @param slopeData      Slope heightmap data from the parent platform
     * @param parentPlatform Reference to the parent platform
     * @param isWalker       true for subtype 0 (walks across surface), false for subtype 1 (stationary)
     */
    public Sonic1GrassFireObjectInstance(int x, int baseY, int sinkOffset,
            byte[] slopeData, Sonic1LargeGrassyPlatformObjectInstance parentPlatform,
            boolean isWalker) {
        super(new ObjectSpawn(x, baseY + sinkOffset, 0x35, isWalker ? 0 : 1, 0, false, 0),
                "BurningGrass");
        this.isWalker = isWalker;
        this.currentX = x;
        this.originX = x;
        this.baseY = baseY;
        this.sinkOffset = sinkOffset;
        this.currentY = baseY + sinkOffset;
        this.slopeData = slopeData;
        this.parentPlatform = parentPlatform;
        this.animTimer = 0;
        this.animIndex = 0;
        this.children = isWalker ? new ArrayList<>() : null;
        this.soundPlayed = false;
    }

    @Override
    public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        if (ctx == null || ctx.spawn() == null) {
            return null;
        }
        ObjectSpawn spawn = ctx.spawn();
        return new Sonic1GrassFireObjectInstance(
                spawn.x(), spawn.y(), 0, null, null, spawn.subtype() == 0);
    }

    // ========================================================================
    // Update Logic
    // ========================================================================

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        // Play burning sound on first frame (GFire_Main: jsr QueueSound2)
        if (!soundPlayed) {
            soundPlayed = true;
            services().playSfx(Sonic1Sfx.BURNING.id);
        }

        if (isWalker) {
            updateWalker();
        } else {
            updateStationary();
        }

        // Animate: cycle through 4 animation frames at speed 5 (6 frames per step)
        animTimer++;
        if (animTimer >= ANIM_SPEED) {
            animTimer = 0;
            animIndex = (animIndex + 1) % ANIM_FRAMES.length;
        }
    }

    /**
     * Subtype 0 (WALKER): Walks rightward across platform surface.
     * <p>
     * From loc_B238:
     * <pre>
     *   d1 = obX - gfire_origX + $C   (distance from origin + offset)
     *   d0 = slopeData[d1/2]           (slope height lookup)
     *   obY = -d0 + objoff_2C + objoff_3C  (surface Y + sink offset)
     *   if d1 >= $84: stop (no more movement)
     *   obX += 1 pixel
     *   if d1 >= $80: skip child spawn
     *   spawn child every 8 pixels (via subpixel accumulator check)
     * </pre>
     */
    private void updateWalker() {
        // Compute distance from origin + slope offset
        int d1 = currentX - originX + SLOPE_OFFSET;

        // Look up slope data to get surface Y offset
        int slopeIndex = d1 >> 1;
        if (slopeData != null && slopeIndex >= 0 && slopeIndex < slopeData.length) {
            int slopeHeight = slopeData[slopeIndex] & 0xFF;
            // neg.w d0 / add.w objoff_2C(a0),d0 / add.w objoff_3C(a0),d0
            currentY = -slopeHeight + baseY + sinkOffset;
        }

        // Check if we've reached the maximum walking distance
        if (d1 >= WALKER_MAX_DISTANCE) {
            return; // Stop walking but keep animating
        }

        // Advance X position: addi.l #$10000,obX(a0) = +1 pixel/frame
        currentX += WALKER_X_SPEED;

        // Spawn child fire while within spawn range (d1 < $80).
        // ROM check: (obX + $80000) & $FFFFF == 0, which with integer-only
        // movement reduces to (currentX + 8) & 0xF == 0 — every 16 pixels.
        if (d1 < 0x80 && ((currentX + SPAWN_CHECK_OFFSET) & SPAWN_CHECK_MASK) == 0) {
            spawnChildFire();
        }
    }

    /**
     * Subtype 1 (STATIONARY): Just sits at its position and updates Y from sink offset.
     * From GFire_Move: obY = objoff_2C + objoff_3C.
     */
    private void updateStationary() {
        currentY = baseY + sinkOffset;
    }

    /**
     * Spawns a child fire (subtype 1) at the walker's current position.
     * From loc_B238: FindNextFreeObj / _move.b #id_GrassFire,obID(a1) / ...
     */
    private void spawnChildFire() {
        if (services().objectManager() == null) {
            return;
        }

        // Compute the surface Y at current position for the child (d2 from slope lookup)
        int d1 = currentX - originX + SLOPE_OFFSET;
        int childBaseY = baseY;
        if (slopeData != null) {
            int slopeIndex = d1 >> 1;
            if (slopeIndex >= 0 && slopeIndex < slopeData.length) {
                int slopeHeight = slopeData[slopeIndex] & 0xFF;
                childBaseY = -slopeHeight + baseY;
            }
        }

        final int fChildBaseY = childBaseY;
        final int mySlot = getSlotIndex();
        Sonic1GrassFireObjectInstance child = spawnFreeChild(() -> {
            Sonic1GrassFireObjectInstance c = new Sonic1GrassFireObjectInstance(
                    currentX, fChildBaseY, sinkOffset, slopeData, parentPlatform, false);
            // ROM: FindNextFreeObj allocates a slot AFTER the current fire's slot.
            ObjectLifetimeOps.assignFindNextFreeChildSlot(services().objectManager(), c, mySlot);
            return c;
        });
        children.add(child);

        // Register child with parent platform for sink offset updates
        if (parentPlatform != null) {
            parentPlatform.registerFireChild(child);
        }
    }

    // ========================================================================
    // Public API for parent platform
    // ========================================================================

    /**
     * Called by the parent platform each frame to update the sink vertical offset.
     * ROM: move.w d1,objoff_3C(a1) in LGrass_Type05 loc_B086 loop.
     *
     * @param offset The current sink offset (CalcSine >> 4)
     */
    public void setSinkOffset(int offset) {
        this.sinkOffset = offset;
    }

    /**
     * Destroys this fire and all its children (walker only).
     * Called by the parent platform during LGrass_DelFlames cleanup.
     */
    public void destroyWithChildren() {
        setDestroyed(true);
        if (children != null) {
            for (Sonic1GrassFireObjectInstance child : children) {
                if (!child.isDestroyed()) {
                    child.setDestroyed(true);
                }
            }
            children.clear();
        }
    }

    // ========================================================================
    // TouchResponseProvider Implementation
    // ========================================================================

    @Override
    public int getCollisionFlags() {
        if (isDestroyed()) {
            return 0;
        }
        return COLLISION_FLAGS;
    }

    @Override
    public int getCollisionProperty() {
        return 0;
    }

    // ========================================================================
    // Rendering
    // ========================================================================

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(ObjectArtKeys.MZ_FIREBALL);
        if (renderer == null) return;

        int frameIndex = ANIM_FRAMES[animIndex];
        boolean hFlip = ANIM_HFLIP[animIndex];
        renderer.drawFrameIndex(frameIndex, currentX, currentY, hFlip, false);
    }

    @Override
    public ObjectSpawn getSpawn() {
        return new ObjectSpawn(currentX, currentY, 0x35, isWalker ? 0 : 1, 0, false, 0);
    }

    @Override
    public int getX() {
        return currentX;
    }

    @Override
    public int getY() {
        return currentY;
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(PRIORITY);
    }

    /**
     * ROM parity: GFire_Main (routine 0) for the walker (subtype 0) executes
     * {@code beq.s loc_B238} which falls through directly to the movement code.
     * The fire moves 1 pixel on its spawn frame. Without same-frame update, the
     * engine's fire is 1 pixel behind the ROM every frame, delaying the touch
     * response detection by 1 physics frame.
     */
    @Override
    public boolean requiresSameFrameUpdate() {
        return isWalker;
    }

    @Override
    public boolean isPersistent() {
        // ROM parity: GrassFire has NO out-of-range check. Fire objects persist
        // until the parent platform explicitly destroys them via LGrass_DelFlames.
        // The parent handles cleanup when IT goes off-screen.
        return !isDestroyed();
    }
}
