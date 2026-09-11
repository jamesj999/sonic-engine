package com.openggf.game.sonic1.objects;

import com.openggf.game.rewind.RewindTransient;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.LevelManager;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreateObjectLinks;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.game.PlayableEntity;

import java.util.List;

/**
 * Sonic 1 Lamppost twirl sparkle - the orbiting red ball after lamppost activation.
 * <p>
 * From docs/s1disasm/_incObj/79 Lamppost.asm (Lamp_Twirl, routine 6):
 * <ul>
 *   <li>Timer starts at $20, decrements each frame</li>
 *   <li>Angle starts at 0, decrements by $10 each frame</li>
 *   <li>Position: origX + cos(angle-$40)*$C00>>16, origY + sin(angle-$40)*$C00>>16</li>
 *   <li>Uses mapping frame 2 (.redballonly)</li>
 *   <li>Timer goes from $20 to -1 with bpl check, giving 33 frames of visible motion</li>
 * </ul>
 */
public class Sonic1LamppostTwirlInstance extends AbstractObjectInstance implements RewindRecreatable {

    // From disassembly: move.w #$20,lamp_time(a1) (79 Lamppost.asm:105)
    // Timer counts $20 → 0 (positive, bpl branches), then 0 → -1 (negative, falls through
    // to set routine 4 but still computes motion) = 33 frames total. (Previously 0x21,
    // an off-by-one that ran a 34th invocation and left the terminal orbit angle one
    // 22.5-degree step past ROM's -- the ball rested ~4.6px left of its correct spot.)
    private static final int INITIAL_LIFETIME = 0x20; // 33 frames of motion

    // From disassembly: subi.b #$10,obAngle(a0)
    private static final int ANGLE_DECREMENT = 0x10;

    // From disassembly: muls.w #$C00,d1 / muls.w #$C00,d0
    // After swap (>>16), effective radius = $C00 * sin/cos(256-scale) / 65536 = 12 pixels
    private static final int SWING_RADIUS = 0x0C00;

    // Mapping frame for red ball only
    private static final int TWIRL_FRAME = 2;

    // ROM lampposts never move, and this instance's own captured spawn (built once in
    // createDummySpawn(), never refreshed afterward -- the twirl has no
    // updateDynamicSpawn() call) is set EXACTLY to the true parent's
    // getCenterX()/getCenterY() at construction, so the true parent's live position is
    // always exactly 0px from this twirl's captured spawn. A few px of headroom covers
    // int-arithmetic slack only; anything farther away (e.g. a different lamppost still
    // loaded elsewhere in the act, docs/architecture/plans/s1-bug-batch-ledger-2026-07-05.md row 5's
    // "wrong lamppost" follow-up) is not a legitimate match and must drop instead of
    // silently adopting the wrong post.
    private static final int MAX_PARENT_RELINK_DISTANCE = 8;
    @RewindTransient(reason = "Structural parent link; relinked to the nearest live "
            + "S1 lamppost on rewind recreate. Scalar orbit state is reapplied by "
            + "the generic field capturer.")
    private final Sonic1LamppostObjectInstance parent;
    private int centerX;
    private int centerY;
    private int lifetime;
    private int angle;
    private int currentX;
    private int currentY;
    private boolean finished;

    public Sonic1LamppostTwirlInstance(Sonic1LamppostObjectInstance parent) {
        super(createDummySpawn(parent), "LamppostTwirl");
        this.parent = parent;
        this.centerX = parent.getCenterX();
        // From disassembly: subi.w #$18,lamp_origY(a1)
        this.centerY = parent.getCenterY() - Sonic1LamppostObjectInstance.TWIRL_Y_OFFSET;
        this.lifetime = INITIAL_LIFETIME;
        this.angle = 0; // obAngle defaults to 0 in freshly allocated object slot
        this.currentX = centerX;
        this.currentY = centerY;
    }

    private static ObjectSpawn createDummySpawn(Sonic1LamppostObjectInstance parent) {
        return new ObjectSpawn(parent.getCenterX(), parent.getCenterY(), 0x79, 0, 0, false, 0);
    }

    @Override
    public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        if (ctx == null || ctx.spawn() == null || ctx.objectServices() == null) {
            return null;
        }
        // Bounded relink: a live lamppost farther than MAX_PARENT_RELINK_DISTANCE from
        // this twirl's captured spawn cannot be the true parent (see the constant's
        // javadoc) -- drop the twirl rather than silently reattaching to the wrong post.
        return RewindRecreateObjectLinks.nearestObject(
                        ctx, Sonic1LamppostObjectInstance.class, false, MAX_PARENT_RELINK_DISTANCE)
                .<AbstractObjectInstance>map(Sonic1LamppostTwirlInstance::new)
                .orElse(null);
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        if (finished) {
            return;
        }

        lifetime--;
        if (lifetime < 0) {
            // docs/s1disasm/s1disasm/_incObj/79 Lamppost.asm:116-134:
            // Lamp_Twirl switches the child to Lamp_Finish, then still runs
            // the final CalcSine position update. Lamp_Finish only returns;
            // the child is not deleted here and keeps occupying its object slot.
            finished = true;
            parent.onTwirlComplete();
        }

        // From disassembly:
        // move.b obAngle(a0),d0        ; load current angle
        // subi.b #$10,obAngle(a0)      ; decrement for next frame
        // subi.b #$40,d0               ; offset before CalcSine
        int calcAngle = (angle - 0x40) & 0xFF;
        angle = (angle - ANGLE_DECREMENT) & 0xFF;

        // CalcSine: d0 = sin(angle), d1 = cos(angle)
        // 256-step angle table, convert to radians
        double radians = calcAngle * Math.PI * 2 / 256.0;
        double sinVal = Math.sin(radians);
        double cosVal = Math.cos(radians);

        // muls.w #$C00,d1; swap d1 → cos * $C00 / 65536 = cos * 12
        // muls.w #$C00,d0; swap d0 → sin * $C00 / 65536 = sin * 12
        int xOffset = (int) (cosVal * SWING_RADIUS) >> 8;
        int yOffset = (int) (sinVal * SWING_RADIUS) >> 8;

        currentX = centerX + xOffset;
        currentY = centerY + yOffset;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager == null) {
            return;
        }
        PatternSpriteRenderer renderer = renderManager.getCheckpointRenderer();
        if (renderer == null || !renderer.isReady()) {
            return;
        }
        renderer.drawFrameIndex(TWIRL_FRAME, currentX, currentY, false, false);
    }

    @Override
    public int getPriorityBucket() {
        // From disassembly: move.b #4,obPriority(a1)
        return RenderPriority.clamp(4);
    }

    @Override
    public String traceDebugDetails() {
        return String.format("twirl time=%d angle=%02X finished=%s", lifetime, angle & 0xFF, finished);
    }
}
