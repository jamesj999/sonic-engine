package com.openggf.level.objects;

import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.rings.RingManager;
import com.openggf.game.PlayableEntity;

import java.util.List;

/**
 * Ring sparkle effect used by signpost during spin.
 * Uses ring sparkle animation frames.
 * Self-destructs after animation completes.
 */
public class SignpostSparkleObjectInstance extends AbstractObjectInstance
        implements ZeroScalarArgsRewindRecreatable {

    /**
     * The {@code Ani_Ring} sparkle script's duration byte, which is 5 in both games
     * (S1 {@code docs/s1disasm/_anim/Rings.asm:7}, S2 {@code docs/s2disasm/s2.asm}
     * {@code Ani_Ring}: {@code dc.b 5, 4,5,6,7, $FC}).
     *
     * <p>{@code AnimateSprite} does {@code subq.b #1,obTimeFrame / bpl Anim_Wait}
     * (docs/s1disasm/_incObj/"sub AnimateSprite.asm"), so a duration of 5 holds each
     * animation frame for SIX executions, not five: the reload happens only once the
     * decrement goes negative.
     */
    private static final int SPARKLE_FRAME_DURATION = 5;

    /** ROM {@code obTimeFrame}; a fresh SST slot starts cleared, so the first execution loads frame 0. */
    private int timeFrame = 0;
    /** ROM {@code obAniFrame}, the script index -- ahead of the displayed frame by one. */
    private int scriptIndex = 0;
    /** Set when the script's {@code afRoutine} advanced the object to {@code Ring_Delete}. */
    private boolean pendingDelete;
    private int animFrame = 0;
    private int totalFrames = 4; // Default, will be updated from RingManager
    private int sparkleStartIndex = 4; // Default sparkle frame start
    // Non-final so GenericFieldCapturer captures them and restoreObjectRewindState reapplies
    // them after a rewind recreate (the base `spawn` is null, so position is not spawn-derivable).
    private int worldX;
    private int worldY;

    public SignpostSparkleObjectInstance(int x, int y) {
        super(null, "signpost_sparkle_" + x + "_" + y);
        this.worldX = x;
        this.worldY = y;

        // Try to get actual sparkle frame info from RingManager
        RingManager ringManager = staticRingManager();
        if (ringManager != null) {
            int count = ringManager.getSparkleFrameCount();
            if (count > 0) {
                totalFrames = count;
            }
            sparkleStartIndex = ringManager.getSparkleStartIndex();
        }
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        // Ring_Delete is its own routine, so DeleteObject costs one further execution
        // after afRoutine advanced it (docs/s1disasm/_incObj/"25, 37 Rings.asm":166-167).
        if (pendingDelete) {
            ObjectLifetimeOps.expireDynamic(this);
            return;
        }
        // Anim_Run: subq.b #1,obTimeFrame / bpl.s Anim_Wait
        if (--timeFrame >= 0) {
            return;
        }
        // Anim_LoadNextFrame reloads the duration before reading the script byte, so
        // the afRoutine entry also costs a full frame's wait before the routine advances.
        timeFrame = SPARKLE_FRAME_DURATION;
        if (scriptIndex >= totalFrames) {
            // Anim_End_FF -> afRoutine: advance obRoutine to Ring_Delete.
            pendingDelete = true;
            return;
        }
        animFrame = scriptIndex;
        scriptIndex++;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        RingManager ringManager = staticRingManager();
        if (ringManager == null) {
            return;
        }
        // Render current sparkle frame
        int frame = sparkleStartIndex + animFrame;
        ringManager.drawFrameIndex(frame, worldX, worldY);
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(2); // Above background, below player
    }

    @Override
    public int getX() {
        return worldX;
    }

    @Override
    public int getY() {
        return worldY;
    }
}
