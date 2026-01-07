package uk.co.jamesj999.sonic.game.sonic2.objects;

import uk.co.jamesj999.sonic.level.objects.*;

import uk.co.jamesj999.sonic.audio.AudioManager;
import uk.co.jamesj999.sonic.game.sonic2.CheckpointState;
import uk.co.jamesj999.sonic.game.sonic2.constants.Sonic2AudioConstants;
import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.graphics.RenderPriority;
import uk.co.jamesj999.sonic.level.LevelManager;
import uk.co.jamesj999.sonic.level.render.PatternSpriteRenderer;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

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
public class CheckpointObjectInstance extends BoxObjectInstance {
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

    private final int checkpointIndex;
    private final boolean cameraLockFlag;
    private int animId;
    private int mappingFrame;
    private int animTimer;
    private int animFrameIndex;
    private boolean activated;
    private boolean dongleActive;

    public CheckpointObjectInstance(ObjectSpawn spawn, String name) {
        super(spawn, name, 8, 24, 0.25f, 0.9f, 0.35f, false);
        this.checkpointIndex = spawn.subtype() & 0x7F;
        this.cameraLockFlag = (spawn.subtype() & 0x80) != 0;

        // Check if already activated (respawn persistence)
        CheckpointState checkpointState = LevelManager.getInstance().getCheckpointState();
        if (checkpointState != null && checkpointState.getLastCheckpointIndex() >= this.checkpointIndex) {
            this.activated = true;
            this.animId = ANIM_BLINKING;
            this.mappingFrame = FRAME_RED_BALL;
        } else {
            this.animId = ANIM_IDLE;
            this.mappingFrame = FRAME_RED_BALL;
        }
        this.animTimer = 0;
        this.animFrameIndex = 0;
        this.dongleActive = false;
    }

    @Override
    public void update(int frameCounter, AbstractPlayableSprite player) {
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
        CheckpointState checkpointState = LevelManager.getInstance().getCheckpointState();
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

        // Check overlap zone
        int px = player.getX();
        int py = player.getY();
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
        activate(player, checkpointState);
    }

    private void activate(AbstractPlayableSprite player, CheckpointState checkpointState) {
        activated = true;
        dongleActive = true;
        animId = ANIM_NO_BALL; // Show pole without ball while dongle is swinging
        mappingFrame = FRAME_NO_BALL;

        // Play checkpoint sound
        try {
            AudioManager.getInstance().playSfx(Sonic2AudioConstants.SFX_CHECKPOINT);
        } catch (Exception e) {
            // Don't let audio failure break game logic
        }

        // Save checkpoint state
        checkpointState.saveFromCheckpoint(this, player);

        // Spawn dongle helper
        spawnDongle();

        // Spawn special stage stars if eligible
        if (shouldSpawnStars(player)) {
            spawnStars();
        }

        LOGGER.fine("Checkpoint " + checkpointIndex + " activated at (" + spawn.x() + ", " + spawn.y() + ")");
    }

    private void spawnDongle() {
        ObjectManager objectManager = LevelManager.getInstance().getObjectManager();
        if (objectManager != null) {
            objectManager.addDynamicObject(new CheckpointDongleInstance(this));
        }
    }

    private boolean shouldSpawnStars(AbstractPlayableSprite player) {
        // ROM: not 2P, emeralds < 7, rings >= 50
        // We don't have 2P or emerald tracking yet, so just check rings
        int rings = player.getRingCount();
        return rings >= 50;
    }

    private void spawnStars() {
        ObjectManager objectManager = LevelManager.getInstance().getObjectManager();
        if (objectManager == null) {
            return;
        }
        // Spawn 4 stars at angle offsets 0, 0x40, 0x80, 0xC0
        for (int i = 0; i < 4; i++) {
            int angleOffset = i * 0x40;
            objectManager.addDynamicObject(new CheckpointStarInstance(this, angleOffset));
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
        ObjectRenderManager renderManager = LevelManager.getInstance().getObjectRenderManager();
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
