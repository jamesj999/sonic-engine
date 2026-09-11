package com.openggf.game.sonic1.objects;

import com.openggf.debug.DebugRenderContext;
import com.openggf.game.PlayableEntity;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectLifetimeOps;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.objects.TouchCategory;
import com.openggf.level.objects.TouchResponseListener;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.objects.TouchResponseResult;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;
import java.util.logging.Logger;

/**
 * Sonic 1 Giant Ring (Object 0x4B) - entry to special stage.
 * <p>
 * From docs/s1disasm/_incObj/4B Giant Ring.asm:
 * <ul>
 *   <li>Routine 0 (GRing_Main): Init - checks emeralds < 6 and rings >= 50</li>
 *   <li>Routine 2 (GRing_Animate): Synced rotation animation, collision active</li>
 *   <li>Routine 4 (GRing_Collect): Touch handler - spawns flash, plays SFX</li>
 *   <li>Routine 6 (GRing_Delete): Object deletion</li>
 * </ul>
 * <p>
 * Art: Uncompressed 98-tile ring (Art_BigRing) loaded at ArtTile_Giant_Ring ($400).
 * Animation: 4 mapping frames (front, angled, edge-on, angled reverse),
 * synced to global v_ani1_frame counter cycling every 8 game frames.
 * <p>
 * Collision type $52: SPECIAL category (0x40), size index 0x12 (8x16 from center).
 */
public class Sonic1GiantRingObjectInstance extends AbstractObjectInstance
        implements TouchResponseProvider, TouchResponseListener, SpawnRewindRecreatable {

    private static final Logger LOGGER = Logger.getLogger(Sonic1GiantRingObjectInstance.class.getName());

    // S1 has 6 chaos emeralds (v_emeralds check: cmpi.b #6)
    private static final int MAX_EMERALDS = 6;

    // Minimum ring count to activate: cmpi.w #50,(v_rings).w
    private static final int MIN_RINGS = 50;

    // Collision type: $52 = SPECIAL ($40) + size index $12
    private static final int COLLISION_FLAGS = 0x52;

    // Animation: v_ani1_frame cycles 0-3, advances every 8 game frames
    private static final int ANIM_FRAME_COUNT = 4;
    private static final int ANIM_FRAME_DURATION = 8; // v_ani1_time resets to 7, counts down

    // ROM: cmpi.w #90,flashtime(a0) - post-hurt invulnerability blocks collection
    private static final int INVULNERABLE_BLOCK_THRESHOLD = 90;

    // SFX ID: sfx_GiantRing = 0xC3
    private static final int SFX_GIANT_RING = 0xC3;

    private enum State {
        INIT,       // Routine 0: checking prerequisites
        ACTIVE,     // Routine 2: animating with collision
        COLLECT_PENDING, // ReactToItem advanced obRoutine; own slot has not run yet
        COLLECTED,  // Routine 4→2: flash spawned, collision cleared
        DELETED     // Routine 6: pending removal
    }

    private State state = State.INIT;
    private int collisionFlags = 0;

    // Self-managed animation timer (replicates v_ani1_frame / v_ani1_time behavior)
    private int animTimer = ANIM_FRAME_DURATION - 1;
    private int animFrame = 0;

    public Sonic1GiantRingObjectInstance(ObjectSpawn spawn) {
        super(spawn, "GiantRing");
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        switch (state) {
            case INIT -> updateInit(player);
            case COLLECT_PENDING -> {
                collect(player);
                updateAnimate();
            }
            case ACTIVE, COLLECTED -> updateAnimate();
            case DELETED -> setDestroyed(true);
        }
    }

    /**
     * GRing_Main (Routine 0): Check prerequisites before activating.
     * <p>
     * ROM: tst.b obRender(a0) / bpl.s GRing_Animate - skip if not on-screen.
     * cmpi.b #6,(v_emeralds).w - delete if all 6 emeralds collected.
     * cmpi.w #50,(v_rings).w - activate if >= 50 rings.
     */
    private void updateInit(AbstractPlayableSprite player) {
        if (!isOnScreenX()) {
            return; // Not visible yet, keep checking
        }

        var gameState = services().gameState();
        if (gameState.getEmeraldCount() >= MAX_EMERALDS) {
            // All emeralds collected - delete: beq.w GRing_Delete
            state = State.DELETED;
            return;
        }

        if (player == null) {
            return;
        }
        int rings = player.getRingCount();
        if (rings < MIN_RINGS) {
            return; // Not enough rings - stay in INIT: rts
        }

        // GRing_Okay: activate
        state = State.ACTIVE;
        collisionFlags = COLLISION_FLAGS;
    }

    /**
     * GRing_Animate (Routine 2): Tick animation and check range.
     * <p>
     * ROM: move.b (v_ani1_frame).w,obFrame(a0)
     *      out_of_range.w DeleteObject
     *      bra.w DisplaySprite
     */
    private void updateAnimate() {
        // Tick animation timer (replicates v_ani1_time / v_ani1_frame)
        if (animTimer > 0) {
            animTimer--;
        } else {
            animTimer = ANIM_FRAME_DURATION - 1;
            animFrame = (animFrame + 1) % ANIM_FRAME_COUNT;
        }

        // out_of_range check
        if (!isOnScreenX(0x80)) {
            state = State.DELETED;
        }
    }

    // ---- Touch Response ----

    @Override
    public int getCollisionFlags() {
        return collisionFlags;
    }

    @Override
    public int getCollisionProperty() {
        return 0;
    }

    @Override
    public void onTouchResponse(PlayableEntity playerEntity, TouchResponseResult result, int frameCounter) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (state != State.ACTIVE) {
            return;
        }
        if (result.category() != TouchCategory.SPECIAL) {
            return;
        }

        // ROM: cmpi.w #90,flashtime(a0) / bhs.w .invincible
        if (player.getInvulnerableFrames() >= INVULNERABLE_BLOCK_THRESHOLD) {
            return;
        }

        // ReactToItem only adds 2 to obRoutine. GRing_Collect executes later,
        // when ExecuteObjects reaches the giant ring's own SST slot. Deferring
        // the body preserves FindFreeObj ordering: a flash allocated into an
        // already-visited lower slot begins on the following frame.
        state = State.COLLECT_PENDING;
    }

    /**
     * GRing_Collect (Routine 4): Spawn flash object, play sound, disable collision.
     * <p>
     * ROM: subq.b #2,obRoutine(a0) - revert to routine 2 (keep animating)
     *      move.b #0,obColType(a0) - clear collision
     *      bsr.w FindFreeObj - spawn Ring Flash (id_RingFlash = 0x7C)
     *      move.w #sfx_GiantRing,d0 / jsr (QueueSound2).l
     */
    private void collect(AbstractPlayableSprite player) {
        // Revert to animate state with collision disabled
        state = State.COLLECTED;
        collisionFlags = 0;

        // Mark spawn as remembered to prevent respawning
        ObjectManager objectManager = services().objectManager();
        ObjectLifetimeOps.markSpawnRemembered(objectManager, spawn);

        // Spawn Ring Flash child object
        if (objectManager != null) {
            // ROM: Flash inherits position, gets parent pointer in objoff_3C
            // ROM: cmp.w obX(a0),d0 / blo.s GRing_PlaySnd / bset #0,obRender(a1)
            // If Sonic is to the right of the ring, flip the flash
            final boolean flashHFlip = (player.getCentreX() >= spawn.x());
            spawnFreeChild(() -> new Sonic1RingFlashObjectInstance(
                    this, spawn.x(), spawn.y(), flashHFlip));
        }

        // Play sound: move.w #sfx_GiantRing,d0 / jsr (QueueSound2).l
        try {
            services().playSfx(SFX_GIANT_RING);
        } catch (Exception e) {
            // Don't let audio failure break game logic
        }
    }

    /**
     * Called by the Ring Flash at animation frame 3 to advance parent to routine 6 (delete).
     * ROM: move.b #6,obRoutine(a1)
     */
    public void onFlashFrame3() {
        state = State.DELETED;
    }

    // ---- Rendering ----

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (state == State.INIT || state == State.DELETED) {
            return;
        }
        PatternSpriteRenderer renderer = getRenderer(ObjectArtKeys.GIANT_RING);
        if (renderer == null) return;
        renderer.drawFrameIndex(animFrame, spawn.x(), spawn.y(), false, false);
    }

    @Override
    public int getPriorityBucket() {
        // ROM: move.b #2,obPriority(a0)
        return RenderPriority.clamp(2);
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        if (state == State.INIT || state == State.DELETED) {
            return;
        }
        // Draw collision box: size index $12 = 8x16 from center
        float r = state == State.ACTIVE ? 0f : 0.5f;
        float g = state == State.ACTIVE ? 1f : 0.5f;
        float b = 0f;
        ctx.drawRect(spawn.x(), spawn.y(), 8, 0x10, r, g, b);
    }
}
