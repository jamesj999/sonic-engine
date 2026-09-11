package com.openggf.game.sonic3k.objects;

import com.openggf.game.rewind.RewindTransient;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.game.PlayableEntity;

import java.util.List;

/**
 * Orbiting star child spawned when a S3K StarPost is activated.
 * <p>
 * ROM routine 6 (loc_2D10A / loc_2D12E):
 * <ul>
 *   <li>Center position: parent starpost (x, y - 0x14)</li>
 *   <li>Mapping frame: 2 (star ball)</li>
 *   <li>Lifetime: 0x20 frames (countdown via $36)</li>
 *   <li>Angle decrements by 0x10 each frame</li>
 *   <li>Angle offset by -0x40 before CalcSine</li>
 *   <li>Radius: 0xC00 (fixed-point, muls.w #$C00 then swap = >>16)</li>
 *   <li>On expiry: set parent anim to 2 (spinning), mapping_frame to 0</li>
 * </ul>
 * <p>
 * This matches the S2 CheckpointDongleInstance behavior with identical ROM math.
 */
public class Sonic3kStarPostStarChild extends AbstractObjectInstance implements RewindRecreatable {

    // ROM: move.w #$20,$36(a1) (line 61633)
    private static final int INITIAL_LIFETIME = 0x20;

    // ROM: subi.b #$10,angle(a0) (line 61682)
    private static final int ANGLE_DECREMENT = 0x10;

    // ROM: muls.w #$C00,d1 (line 61685)
    private static final int ORBIT_RADIUS = 0x0C00;

    // ROM: move.b #2,mapping_frame(a1) (line 61632)
    private static final int STAR_FRAME = 2;
    @RewindTransient(reason = "Structural parent link; relinked to the nearest live "
            + "S3K starpost on rewind recreate. Scalar orbit state is reapplied by "
            + "the generic field capturer.")
    private final Sonic3kStarPostObjectInstance parent;
    private int centerX;
    private int centerY;
    private int lifetime;
    private int angle;
    private int currentX;
    private int currentY;

    public Sonic3kStarPostStarChild(Sonic3kStarPostObjectInstance parent) {
        super(createDummySpawn(parent), "StarPostStar");
        this.parent = parent;
        this.centerX = parent.getCenterX();
        // ROM: subi.w #$14,$32(a1) (line 61625)
        this.centerY = parent.getCenterY() - 0x14;
        this.lifetime = INITIAL_LIFETIME;
        this.angle = 0;
        this.currentX = centerX;
        this.currentY = centerY;
    }

    private static ObjectSpawn createDummySpawn(Sonic3kStarPostObjectInstance parent) {
        return new ObjectSpawn(parent.getCenterX(), parent.getCenterY(), 0x34, 0, 0, false, 0);
    }

    @Override
    public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        if (ctx == null || ctx.spawn() == null || ctx.objectServices() == null) {
            return null;
        }
        Sonic3kStarPostObjectInstance liveParent =
                findNearestLiveParentForRewind(ctx.objectServices().objectManager(), ctx.spawn());
        return liveParent == null ? null : new Sonic3kStarPostStarChild(liveParent);
    }

    static Sonic3kStarPostObjectInstance findNearestLiveParentForRewind(
            ObjectManager objectManager,
            ObjectSpawn spawn) {
        if (objectManager == null || spawn == null) {
            return null;
        }
        Sonic3kStarPostObjectInstance best = null;
        long bestDistance = Long.MAX_VALUE;
        for (ObjectInstance instance : objectManager.getActiveObjects()) {
            if (!(instance instanceof Sonic3kStarPostObjectInstance parent) || parent.isDestroyed()) {
                continue;
            }
            long dx = parent.getCenterX() - spawn.x();
            long dy = parent.getCenterY() - spawn.y();
            long distance = dx * dx + dy * dy;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = parent;
            }
        }
        return best;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (isDestroyed()) {
            return;
        }

        // loc_2D10A: subq.w #1,$36(a0) / bpl.s loc_2D12E
        // ROM: decrement first, then branch if >= 0 (signed).
        // Initial $36 = 0x20: orbits 33 frames (0x1F down to 0), deletes at -1.
        lifetime--;

        if (lifetime < 0) {
            // $36 went negative: set parent anim, delete self
            // ROM: move.b #2,anim(a1) / move.b #0,mapping_frame(a1)
            parent.onStarComplete();
            setDestroyed(true);
            return;
        }

        // loc_2D12E: angle decrement and CalcSine
        // ROM: move.b angle(a0),d0
        //      subi.b #$10,angle(a0)
        //      subi.b #$40,d0
        //      jsr (GetSineCosine).l
        int calcAngle = (angle - 0x40) & 0xFF;
        angle = (angle - ANGLE_DECREMENT) & 0xFF;

        // GetSineCosine: d0 = sin(angle), d1 = cos(angle)
        double radians = calcAngle * Math.PI * 2 / 256.0;
        double sinVal = Math.sin(radians);
        double cosVal = Math.cos(radians);

        // ROM: muls.w #$C00,d1 / swap d1 => multiply by 0xC00 then >>16
        // Since our sin/cos return [-1.0, 1.0], multiply by ORBIT_RADIUS then >>8
        // (swap on a 32-bit value after muls.w of a 16-bit input = >>16,
        //  but we're using floating point [-1,1] * ORBIT_RADIUS, so >>8 to get pixels)
        int xOffset = (int) (cosVal * ORBIT_RADIUS) >> 8;
        int yOffset = (int) (sinVal * ORBIT_RADIUS) >> 8;

        // ROM: add.w $30(a0),d1 => centerX + xOffset
        //      add.w $32(a0),d0 => centerY + yOffset
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
            // Fallback: small dot
            appendFallbackDot(commands);
            return;
        }
        renderer.drawFrameIndex(STAR_FRAME, currentX, currentY, false, false);
    }

    /**
     * Fallback debug rendering: small dot at current position.
     */
    private void appendFallbackDot(List<GLCommand> commands) {
        int half = 3;
        float r = 1.0f, g = 1.0f, b = 0.0f;
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                r, g, b, currentX - half, currentY, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                r, g, b, currentX + half, currentY, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                r, g, b, currentX, currentY - half, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                r, g, b, currentX, currentY + half, 0, 0));
    }

    @Override
    public int getPriorityBucket() {
        // ROM: move.w #$200,priority(a1) (line 61631)
        return RenderPriority.clamp(4);
    }
}
