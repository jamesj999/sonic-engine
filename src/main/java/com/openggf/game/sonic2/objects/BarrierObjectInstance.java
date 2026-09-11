package com.openggf.game.sonic2.objects;

import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.game.PlayableEntity;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Object 2D - One way barrier from CPZ, HTZ, MTZ, ARZ, and DEZ.
 * <p>
 * A vertical barrier platform that rises when the player enters its detection zone
 * and returns to its base position when the player leaves.
 * <p>
 * Based on Sonic 2 disassembly (s2.asm lines 24128-24287).
 * <p>
 * Subtypes determine zone-specific art and width:
 * - 0: HTZ (8px width)
 * - 1: MTZ (12px width)
 * - 2: CPZ/DEZ (8px width)
 * - 3: ARZ (8px width)
 * <p>
 * Behavior:
 * - Detection zone: 64 pixels tall (Y +/- 32 from base)
 * - X boundaries: x-512 to x+24 (normal) or x-488 to x+488 (flipped)
 * - Movement rate: 8 pixels per frame
 * - Max rise: 64 pixels (0x40)
 * - Solid collision enabled while visible
 */
public class BarrierObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener, RewindRecreatable {
    private static final Logger LOGGER = Logger.getLogger(BarrierObjectInstance.class.getName());

    // Movement constants (from disassembly)
    private static final int RISE_SPEED = 8;           // addq.w #8,objoff_30(a0)
    private static final int MAX_RISE = 0x40;          // cmpi.w #$40,objoff_30(a0)
    private static final int DETECTION_Y_HALF = 0x20;  // subi.w #$20,d4 / addi.w #$20,d5

    // X detection boundaries (from disassembly)
    private static final int X_LEFT_OFFSET = 0x200;    // subi.w #$200,d2
    private static final int X_RIGHT_OFFSET = 0x18;    // addi.w #$18,d3
    private static final int X_FLIP_LEFT_ADD = 0x1E8;  // subi.w #-$1E8,d2 (which adds 0x1E8)
    private static final int X_FLIP_RIGHT_ADD = 0x1E8; // addi.w #$1E8,d3
    private static final ObjectPlayerParticipationPolicy PLAYER_PARTICIPATION =
            ObjectPlayerParticipationPolicy.MAIN_PLUS_ENGINE_SIDEKICKS_AS_NATIVE_P2_EXTENDED;

    // Solid collision constants (from disassembly)
    private static final int SOLID_EXTRA_WIDTH = 0x0B; // addi.w #$B,d1
    private static final int SOLID_HALF_HEIGHT = 0x20; // move.w #$20,d2

    // Subtype configurations: {width_pixels}
    private static final int[] SUBTYPE_WIDTH = {
            8,   // Subtype 0: HTZ
            12,  // Subtype 1: MTZ
            8,   // Subtype 2: CPZ/DEZ
            8    // Subtype 3: ARZ
    };

    private int x;
    private int y;
    private int baseY;          // objoff_32 - original Y position
    private int riseOffset;     // objoff_30 - current rise amount (0 to MAX_RISE)
    private int leftBoundary;   // objoff_38 - left X boundary for detection
    private int rightBoundary;  // objoff_3A - right X boundary for detection
    private int widthPixels;
    private int mappingFrame;
    private boolean movingUp;   // routine_secondary - true when moving up
    private boolean wasMovingUp; // Previous frame's movingUp state for detection boundary calculation
    private boolean xFlip;

    public BarrierObjectInstance(ObjectSpawn spawn, String name) {
        super(spawn, name);
        init();
    }

    @Override
    public BarrierObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new BarrierObjectInstance(ctx.spawn(), getName());
    }

    @Override
    public int getX() {
        return x;
    }

    @Override
    public int getY() {
        return y;
    }
    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        // Calculate detection boundaries using PREVIOUS frame's movingUp state
        // ROM behavior: detection boundaries are calculated using routine_secondary from the previous frame,
        // then routine_secondary is cleared to 0, then character detection is performed.
        // This allows the expanded detection zone to persist for one extra frame.
        int detectLeft, detectRight;
        if (!xFlip) {
            // Normal: check from left boundary to current x (or right boundary if was moving)
            detectLeft = leftBoundary;
            detectRight = wasMovingUp ? rightBoundary : x;
        } else {
            // Flipped: check from current x (or left boundary if was moving) to right boundary
            detectLeft = wasMovingUp ? leftBoundary : x;
            detectRight = rightBoundary;
        }

        // Reset movement flag for this frame (ROM clears routine_secondary here)
        movingUp = false;

        int detectTop = baseY - DETECTION_Y_HALF;
        int detectBottom = baseY + DETECTION_Y_HALF;

        // Check both main character and sidekick (from s2.asm lines 24222-24225):
        //   lea (MainCharacter).w,a1
        //   bsr.s Obj2D_CheckCharacter
        //   lea (Sidekick).w,a1
        //   bsr.s Obj2D_CheckCharacter
        for (PlayableEntity participant : detectionParticipants(playerEntity)) {
            checkCharacter((AbstractPlayableSprite) participant, detectLeft, detectRight, detectTop, detectBottom);
        }

        // Update position based on movement state
        if (movingUp) {
            // Move up if not at max height
            if (riseOffset < MAX_RISE) {
                riseOffset += RISE_SPEED;
                if (riseOffset > MAX_RISE) {
                    riseOffset = MAX_RISE;
                }
            }
        } else {
            // Move down if not at base position
            if (riseOffset > 0) {
                riseOffset -= RISE_SPEED;
                if (riseOffset < 0) {
                    riseOffset = 0;
                }
            }
        }

        // Update Y position: y_pos = base_y - rise_offset
        y = baseY - riseOffset;

        // Save current movingUp state for next frame's detection boundary calculation
        wasMovingUp = movingUp;

        updateDynamicSpawn(x, y);
    }

    private List<PlayableEntity> detectionParticipants(PlayableEntity updatePlayer) {
        List<PlayableEntity> participants = services().playerQuery().playersFor(PLAYER_PARTICIPATION);
        if (updatePlayer != null && !participants.contains(updatePlayer)) {
            ArrayList<PlayableEntity> withUpdatePlayer = new ArrayList<>(participants.size() + 1);
            withUpdatePlayer.add(updatePlayer);
            withUpdatePlayer.addAll(participants);
            return withUpdatePlayer;
        }
        return participants;
    }

    /**
     * Checks if a character is within the detection zone.
     * If so, sets the barrier to move up.
     * <p>
     * From Obj2D_CheckCharacter (s2.asm lines 24259-24277)
     */
    private void checkCharacter(AbstractPlayableSprite character, int left, int right, int top, int bottom) {
        if (character == null) {
            return;
        }

        // ROM uses x_pos(a1) and y_pos(a1) which are CENTER coordinates
        // Must use getCentreX/getCentreY to match ROM behavior (see CLAUDE.md)
        int charX = character.getCentreX();
        int charY = character.getCentreY();

        // Check X boundaries (character must be >= left and < right)
        if (charX < left || charX >= right) {
            return;
        }

        // Check Y boundaries (character must be >= top and < bottom)
        if (charY < top || charY >= bottom) {
            return;
        }

        // Check if character is not dead/disabled (obj_control bit 7 clear)
        // In our engine, we check if the player is controllable/alive
        if (character.getDead()) {
            return;
        }

        // Character is in zone - set barrier to move up
        movingUp = true;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(Sonic2ObjectArtKeys.BARRIER);
        if (renderer == null) return;
        // mappingFrame is the subtype (0=HTZ, 1=MTZ, 2=CPZ/DEZ, 3=ARZ)
        renderer.drawFrameIndex(mappingFrame, x, y, xFlip, false);
    }

    @Override
    public SolidObjectParams getSolidParams() {
        // From disassembly: d1 = width_pixels + $B, d2 = $20, d3 = $21
        int halfWidth = widthPixels + SOLID_EXTRA_WIDTH;
        int airHalfHeight = SOLID_HALF_HEIGHT;
        int groundHalfHeight = SOLID_HALF_HEIGHT + 1;
        return SolidObjectParams.of(halfWidth, airHalfHeight, groundHalfHeight);
    }

    @Override
    public boolean isTopSolidOnly() {
        // Barrier is a full solid object, not top-only
        return false;
    }

    @Override
    public boolean usesInclusiveRightEdge() {
        // Obj2D passes width_pixels+$B to the standard S2 SolidObject helper.
        // Its BHI reject keeps relX==2*d1 as a zero-distance side contact; HTZ2
        // reaches that exact edge while holding Status_Push.
        return true;
    }

    @Override
    public boolean preservesEdgeSubpixelMotion() {
        // At relX==2*d1, SolidObject_AtEdge reaches Solid_Centre with d0=0.
        // It sets Status_Push but does not enter StopCharacter, so x_vel and
        // inertia remain unchanged (s2.asm:35193-35207).
        return true;
    }

    @Override
    public boolean fullSolidBottomOverlapUsesCurrentYRadiusOnly(PlayableEntity player) {
        // Obj2D calls the shared S2 SolidObject helper (s2.asm:117D6-117E8).
        // SolidObject_cont builds both vertical reject halves from live
        // y_radius(a1) after adding it to d2 (s2.asm:35156-35169), so rolling
        // players use the smaller rolling radius on the lower bound too.
        return true;
    }

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        // Solid collision is handled by ObjectManager
    }

    @Override
    public boolean isSolidFor(PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        return !isDestroyed();
    }

    private void init() {
        x = spawn.x();
        baseY = spawn.y();
        y = baseY;
        riseOffset = 0;
        movingUp = false;
        wasMovingUp = false;

        // Get subtype (mapping frame)
        mappingFrame = spawn.subtype() & 0x03;
        if (mappingFrame < 0 || mappingFrame >= SUBTYPE_WIDTH.length) {
            mappingFrame = 0;
        }
        widthPixels = SUBTYPE_WIDTH[mappingFrame];

        // Check X-flip status
        xFlip = (spawn.renderFlags() & 0x01) != 0;

        // Calculate X detection boundaries
        // Normal: left = x - $200, right = x + $18
        // Flipped: left = x - $200 + $1E8 = x - $18, right = x + $18 + $1E8 = x + $200
        if (!xFlip) {
            leftBoundary = x - X_LEFT_OFFSET;
            rightBoundary = x + X_RIGHT_OFFSET;
        } else {
            // When flipped, the disassembly does:
            // subi.w #-$1E8,d2  ; This actually adds $1E8 (subtracting a negative)
            // addi.w #$1E8,d3
            // So: left = x - $200 + $1E8, right = x + $18 + $1E8
            leftBoundary = x - X_LEFT_OFFSET + X_FLIP_LEFT_ADD;
            rightBoundary = x + X_RIGHT_OFFSET + X_FLIP_RIGHT_ADD;
        }

        updateDynamicSpawn(x, y);
    }
}
