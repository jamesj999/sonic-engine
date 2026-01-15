package uk.co.jamesj999.sonic.game.sonic2.objects;

import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.graphics.RenderPriority;
import uk.co.jamesj999.sonic.level.LevelManager;
import uk.co.jamesj999.sonic.level.objects.AbstractObjectInstance;
import uk.co.jamesj999.sonic.level.objects.ObjectRenderManager;
import uk.co.jamesj999.sonic.level.objects.ObjectSpawn;
import uk.co.jamesj999.sonic.level.render.PatternSpriteRenderer;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

import java.util.List;
import java.util.logging.Logger;

/**
 * Special stage star - spirals around checkpoint when >= 50 rings.
 * <p>
 * ROM behavior (Obj79_Star / loc_1F536):
 * - objoff_30/32: center X/Y (checkpoint position - 0x30 Y)
 * - objoff_34: angle (starts at 0, 0x40, 0x80, 0xC0 for each star, increments
 * by 0xA per frame)
 * - objoff_36: lifetime counter (collision at 0x80, shrink at 0x180, delete at
 * 0x200)
 * - Complex orbital motion with 3D-like spiral effect
 * - Animation: frames 0, 1, 2, 1 based on (frame & 6) >> 1, with 3 -> 1 mapping
 * </p>
 */
public class CheckpointStarInstance extends AbstractObjectInstance {
    private static final Logger LOGGER = Logger.getLogger(CheckpointStarInstance.class.getName());

    // ROM constants (from disassembly)
    private static final int COLLISION_START = 0x80; // Enable collision at this lifetime
    private static final int SHRINK_START = 0x180; // Start shrinking
    private static final int DELETE_AT = 0x200; // Delete when lifetime reaches this
    private static final int ANGLE_INCREMENT = 0xA; // Add to angle each frame

    private final CheckpointObjectInstance parentCheckpoint; // Reference to parent for marking as used
    private final int centerX; // objoff_30
    private final int centerY; // objoff_32
    private int angle; // objoff_34 (starts at angleOffset, increments by 0xA)
    private int lifetime; // objoff_36
    private int animFrame; // anim_frame counter for animation cycling
    private int currentX;
    private int currentY;
    private int mappingFrame;
    private boolean collisionEnabled;

    public CheckpointStarInstance(CheckpointObjectInstance parent, int angleOffset) {
        super(createDummySpawn(parent), "CheckpointStar");
        this.parentCheckpoint = parent;
        this.centerX = parent.getCenterX();
        this.centerY = parent.getCenterY() - 0x30; // Y offset from ROM
        this.angle = angleOffset; // Starts at 0, 0x40, 0x80, or 0xC0
        this.lifetime = 0;
        this.animFrame = 0;
        this.mappingFrame = 1; // ROM starts with mapping_frame = 1
        this.collisionEnabled = false;

        // Initial position at center
        this.currentX = centerX;
        this.currentY = centerY;
    }

    private static ObjectSpawn createDummySpawn(CheckpointObjectInstance parent) {
        return new ObjectSpawn(parent.getCenterX(), parent.getCenterY(), 0x79, 0, 0, false, 0);
    }

    @Override
    public void update(int frameCounter, AbstractPlayableSprite player) {
        // Line 44411: addi.w #$A, objoff_34(a0)
        angle = (angle + ANGLE_INCREMENT) & 0xFFFF;

        // Calculate orbital position using ROM algorithm
        updatePosition();

        // Line 44443: addq.w #1, objoff_36(a0)
        lifetime++;

        // Line 44445-44447: check collision and deletion states
        if (lifetime == COLLISION_START) {
            collisionEnabled = true;
            // ROM: move.b #$D8, collision_flags(a0)
        }

        // Check for deletion (ROM: loc_1F5C4 -> JmpTo10_DeleteObject)
        if (lifetime > SHRINK_START) {
            int adjusted = -lifetime + DELETE_AT;
            if (adjusted < 0) {
                setDestroyed(true);
                return;
            }
        }

        // Update animation frame (ROM: lines 44476-44484)
        updateAnimation();

        // Check for player collision to trigger special stage entry
        if (collisionEnabled && player != null && isPlayerInRange(player)) {
            LOGGER.info("Player touched special stage star - requesting special stage entry");
            // Mark the parent checkpoint as used for special stage entry
            // This prevents stars from respawning when returning from special stage
            if (parentCheckpoint != null) {
                parentCheckpoint.markUsedForSpecialStage();
            }
            LevelManager.getInstance().requestSpecialStageFromCheckpoint();
            setDestroyed(true);
        }
    }

    private void updatePosition() {
        // ROM algorithm (Obj79_Star, starting at loc_1F554):
        // d0 = sin(angle & 0xFF), d1 = cos(angle & 0xFF)
        // Complex calculation for 3D-style orbital motion

        int angleForCalc = angle & 0xFF;
        double radians = angleForCalc * Math.PI * 2 / 256.0;
        double sinVal = Math.sin(radians);
        double cosVal = Math.cos(radians);

        // ROM: asr.w #5, d0 (sin) and asr.w #3, d1 (cos)
        // This gives approximate ranges for the offsets
        int d0 = (int) (sinVal * 256) >> 5; // Y base offset from sine
        int d3 = (int) (cosVal * 256) >> 3; // X base offset from cosine

        // ROM: Complex bit manipulation for figure-8/spiral effect
        // This creates a 3D-like twisting spiral motion
        int d2 = (angle >> 5) & 0x1F; // bits 5-9 of angle
        int d4 = 0;
        int d5 = 2;
        int d1 = d3;

        // ROM: cmpi.w #$10, d2 / ble.s + / neg.w d1
        if (d2 > 0x10) {
            d1 = -d1;
        }

        // ROM: andi.w #$F, d2 / cmpi.w #8, d2 / ble.s loc_1F594 / neg.w d2, andi.w #7,
        // d2
        d2 &= 0xF;
        if (d2 > 8) {
            d2 = -(d2 & 7);
        }

        // ROM loop at loc_1F594: accumulates d1 into d4 based on d2 bits
        for (int i = 0; i <= d5; i++) {
            d2 >>= 1;
            if (d2 != 0) {
                d4 += d1;
            }
            d1 <<= 1;
        }

        d4 >>= 4;
        d0 += d4;

        // Apply lifetime-based scaling (ROM: loc_1F5B4 or loc_1F5C4)
        int scaleFactor;
        if (lifetime < COLLISION_START) {
            // Growing phase: scale proportional to lifetime
            scaleFactor = lifetime;
        } else if (lifetime <= SHRINK_START) {
            // Full size
            scaleFactor = COLLISION_START;
        } else {
            // Shrinking phase
            scaleFactor = DELETE_AT - lifetime;
            if (scaleFactor < 0)
                scaleFactor = 0;
        }

        // ROM: muls.w d1, d0/d3 and asr.w #7, d0/d3
        d0 = (d0 * scaleFactor) >> 7;
        d3 = (d3 * scaleFactor) >> 7;

        // Apply to center position
        currentX = centerX + d3;
        currentY = centerY + d0;
    }

    private void updateAnimation() {
        // ROM: lines 44476-44484
        // addq.b #1, anim_frame(a0)
        // move.b anim_frame(a0), d0
        // andi.w #6, d0
        // lsr.w #1, d0
        // cmpi.b #3, d0 / bne.s + / moveq #1, d0
        // move.b d0, mapping_frame(a0)

        animFrame++;
        int frame = (animFrame & 6) >> 1; // 0, 1, 2, 3 cycling
        if (frame == 3) {
            frame = 1; // 3 maps back to 1, so we get: 0, 1, 2, 1
        }
        mappingFrame = frame;
    }

    private boolean isPlayerInRange(AbstractPlayableSprite player) {
        int dx = Math.abs(player.getX() - currentX);
        int dy = Math.abs(player.getY() - currentY);
        return dx < 16 && dy < 16;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectRenderManager renderManager = LevelManager.getInstance().getObjectRenderManager();
        if (renderManager == null) {
            return;
        }
        PatternSpriteRenderer renderer = renderManager.getCheckpointStarRenderer();
        if (renderer == null || !renderer.isReady()) {
            return;
        }
        renderer.drawFrameIndex(mappingFrame, currentX, currentY, false, false);
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(5);
    }
}
