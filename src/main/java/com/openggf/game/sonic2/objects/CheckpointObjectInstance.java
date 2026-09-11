package com.openggf.game.sonic2.objects;
import com.openggf.level.objects.BoxObjectInstance;

import com.openggf.game.CheckpointState;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic2.audio.Sonic2Sfx;

import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;

import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;
import java.util.logging.Logger;

/**
 * Sonic 2 Starpost/Checkpoint (Object $79).
 * <p>
 * Based on disassembly analysis:
 * - Frame 0: pole + red ball (inactive)
 * - Frame 1: pole only (no ball - used while dongle is swinging)
 * - Frame 2: dongle ball alone
 * - Frame 3: head alone (unused by main post)
 * - Frame 4: pole + blue ball (active)
 * <p>
 * Animation scripts:
 * - Anim 0: frame 0, loop (idle red ball)
 * - Anim 1: frame 1, loop (no ball - during dongle)
 * - Anim 2: frames 0,4 alternating (blinking after dongle expires)
 * </p>
 */
public class CheckpointObjectInstance extends BoxObjectInstance implements RewindRecreatable {
    private static final Logger LOGGER = Logger.getLogger(CheckpointObjectInstance.class.getName());

    // Activation zone dimensions (ROM: x_delta + 8 < $10, y_delta + $40 < $68)
    private static final int ACTIVATION_HALF_WIDTH = 8;
    private static final int ACTIVATION_Y_ABOVE = 0x40;
    private static final int ACTIVATION_Y_BELOW = 0x28;

    // Animation IDs matching Ani_obj79
    private static final int ANIM_IDLE = 0; // Frame 0 (red ball), loop
    private static final int ANIM_NO_BALL = 1; // Frame 1 (no ball), loop
    private static final int ANIM_BLINKING = 2; // Frames 0, 4 alternating

    // Mapping frames from obj79_a.asm
    private static final int FRAME_RED_BALL = 0; // Pole + red ball
    private static final int FRAME_NO_BALL = 1; // Pole only
    private static final int FRAME_DONGLE = 2; // Dongle ball
    private static final int FRAME_HEAD = 3; // Head alone
    private static final int FRAME_BLUE_BALL = 4; // Pole + blue ball

    private int checkpointIndex;
    private boolean cameraLockFlag;
    private int animId;
    private int mappingFrame;
    private int animTimer;
    private int animFrameIndex;
    private boolean activated;
    private boolean dongleActive;
    private boolean initialized;

    public CheckpointObjectInstance(ObjectSpawn spawn, String name) {
        super(spawn, name, 8, 24, 0.25f, 0.9f, 0.35f, false);
        this.checkpointIndex = spawn.subtype() & 0x7F;
        this.cameraLockFlag = (spawn.subtype() & 0x80) != 0;

        this.animId = ANIM_IDLE;
        this.mappingFrame = FRAME_RED_BALL;
        this.animTimer = 0;
        this.animFrameIndex = 0;
        this.dongleActive = false;
    }

    @Override
    public CheckpointObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new CheckpointObjectInstance(ctx.spawn(), getName());
    }

    private void ensureInitialized() {
        if (initialized) {
            return;
        }
        initialized = true;

        // Check if already activated (respawn persistence)
        var checkpointState = services().checkpointState();
        if (checkpointState != null && checkpointState.getLastCheckpointIndex() >= this.checkpointIndex) {
            this.activated = true;
            this.animId = ANIM_BLINKING;
            this.mappingFrame = FRAME_RED_BALL;
        }
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        ensureInitialized();
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (!activated && player != null) {
            checkActivation(player);
        }
        updateAnimation();
    }

    private void updateAnimation() {
        // Decrement timer
        if (animTimer > 0) {
            animTimer--;
            return;
        }

        switch (animId) {
            case ANIM_IDLE:
                // Script 0: $F, 0, $FF - frame 0, delay 15, loop
                mappingFrame = FRAME_RED_BALL;
                animTimer = 15;
                break;
            case ANIM_NO_BALL:
                // Script 1: $F, 1, $FF - frame 1, delay 15, loop
                mappingFrame = FRAME_NO_BALL;
                animTimer = 15;
                break;
            case ANIM_BLINKING:
                // Script 2: 3, 0, 4, $FF - delay 3, alternate frames 0 and 4
                animFrameIndex = (animFrameIndex + 1) % 2;
                mappingFrame = (animFrameIndex == 0) ? FRAME_RED_BALL : FRAME_BLUE_BALL;
                animTimer = 3;
                break;
        }
    }

    private void checkActivation(AbstractPlayableSprite player) {
        // Guard: don't activate if a higher/equal checkpoint was already hit
        var checkpointState = services().checkpointState();
        if (checkpointState == null) {
            return;
        }
        if (checkpointState.getLastCheckpointIndex() >= this.checkpointIndex) {
            // Already activated a later checkpoint - show blinking
            if (!activated) {
                activated = true;
                animId = ANIM_BLINKING;
            }
            return;
        }

        // Check overlap zone (use center position to match original ROM behavior)
        int px = player.getCentreX();
        int py = player.getCentreY();
        int cx = spawn.x();
        int cy = spawn.y();

        int dx = px - cx;
        int dy = py - cy;

        // ROM check: x_delta + 8 < $10 => abs(dx) < 8
        if (dx + ACTIVATION_HALF_WIDTH < 0 || dx + ACTIVATION_HALF_WIDTH >= 16) {
            return;
        }
        // ROM check: y_delta + $40 < $68
        if (dy + ACTIVATION_Y_ABOVE < 0 || dy + ACTIVATION_Y_ABOVE >= 0x68) {
            return;
        }

        // Activate!
        if (checkpointState instanceof CheckpointState cs) {
            activate(player, cs);
        }
    }

    private void activate(AbstractPlayableSprite player, CheckpointState checkpointState) {
        activated = true;
        dongleActive = true;
        animId = ANIM_NO_BALL; // Show pole without ball while dongle is swinging
        mappingFrame = FRAME_NO_BALL;

        // Play checkpoint sound
        try {
            services().playSfx(Sonic2Sfx.CHECKPOINT.id);
        } catch (Exception e) {
            // Don't let audio failure break game logic
        }

        // Save checkpoint state
        checkpointState.saveCheckpoint(getCheckpointIndex(),
                getCenterX(), getCenterY(), hasCameraLockFlag());

        // Spawn dongle helper
        spawnDongle();

        // Spawn special stage stars if eligible
        if (shouldSpawnStars(player)) {
            spawnStars();
        }

        LOGGER.fine("Checkpoint " + checkpointIndex + " activated at (" + spawn.x() + ", " + spawn.y() + ")");
    }

    private void spawnDongle() {
        spawnFreeChild(() -> new CheckpointDongleInstance(this));
    }

    private boolean shouldSpawnStars(AbstractPlayableSprite player) {
        // ROM: not 2P, emeralds < 7, rings >= 50, AND not already used for SS entry
        var checkpointState = services().checkpointState();
        if (checkpointState instanceof CheckpointState cs && cs.isUsedForSpecialStage()) {
            return false;
        }
        int emeralds = services().gameState().getEmeraldCount();
        if (emeralds >= 7) {
            return false;
        }
        int rings = player.getRingCount();
        return rings >= 50;
    }

    /**
     * Marks this checkpoint as having been used for special stage entry.
     * Called by CheckpointStarInstance when the player touches a star.
     * Prevents stars from respawning when returning from special stage.
     */
    public void markUsedForSpecialStage() {
        var checkpointState = services().checkpointState();
        if (checkpointState instanceof CheckpointState cs) {
            cs.markUsedForSpecialStage();
        }
        LOGGER.fine("Checkpoint " + checkpointIndex + " marked as used for special stage entry");
    }

    private void spawnStars() {
        // Spawn 4 stars at angle offsets 0, 0x40, 0x80, 0xC0.
        // ROM Obj79_MakeSpecialStars allocates with AllocateObjectAfterCurrent, not
        // AllocateObject (docs/s2disasm/s2.asm:44841-44845; contrast the dongle at
        // s2.asm:44647, which does use AllocateObject). The stars therefore land in
        // slots ABOVE the star post's own and run Obj79_Star on the very frame they
        // are created, so objoff_36 is already 1 at that frame's end. Allocating them
        // lowest-free let them fall below the post, costing a frame of orbit phase.
        for (int i = 0; i < 4; i++) {
            int angleOffset = i * 0x40;
            spawnChild(() -> new CheckpointStarInstance(this, angleOffset));
        }
    }

    /**
     * Called by dongle when its lifetime expires.
     * Switch to blinking animation (anim 2: frames 0 and 4).
     */
    public void onDongleComplete() {
        dongleActive = false;
        animId = ANIM_BLINKING;
        animFrameIndex = 0; // Start with frame 0
        mappingFrame = FRAME_RED_BALL;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager == null) {
            super.appendRenderCommands(commands);
            return;
        }
        PatternSpriteRenderer renderer = renderManager.getCheckpointRenderer();
        if (renderer == null || !renderer.isReady()) {
            super.appendRenderCommands(commands);
            return;
        }
        renderer.drawFrameIndex(mappingFrame, spawn.x(), spawn.y(), false, false);
    }

    public int getCheckpointIndex() {
        return checkpointIndex;
    }

    public boolean hasCameraLockFlag() {
        return cameraLockFlag;
    }

    public int getCenterX() {
        return spawn.x();
    }

    public int getCenterY() {
        return spawn.y();
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(5);
    }
}
