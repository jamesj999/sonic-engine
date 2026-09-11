package com.openggf.game.sonic2.objects.badniks;

import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.game.PlayableEntity;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * Turtloid Jet Exhaust (0x9C) - Animated jet flame on the back of the Turtloid.
 * Follows parent Turtloid position and animates between two flame frames.
 *
 * Based on disassembly Obj9C (s2.asm:74480-74523).
 * Animation: Ani_obj9A = dc.b 1, 6, 7, $FF (alternates frames 6 and 7, speed 1).
 */
public class TurtloidJetInstance extends AbstractObjectInstance implements RewindRecreatable {

    // Animation: frames 6 and 7 from shared Turtloid sheet, speed = 1 (every other frame)
    private static final int JET_FRAME_1 = 6;
    private static final int JET_FRAME_2 = 7;
    private static final int ANIM_SPEED = 1; // Ani_obj9A: dc.b 1, ...
    private final TurtloidBadnikInstance parent;
    private int currentX;
    private int currentY;
    private int animFrame;
    private int animTimer;

    public TurtloidJetInstance(ObjectSpawn spawn, TurtloidBadnikInstance parent) {
        super(spawn, "TurtloidJet");
        this.parent = parent;
        this.currentX = spawn.x();
        this.currentY = spawn.y();
        this.animFrame = JET_FRAME_1;
        this.animTimer = ANIM_SPEED;
    }

    @Override
    public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        if (ctx == null || ctx.spawn() == null || ctx.objectServices() == null) {
            return null;
        }
        TurtloidBadnikInstance liveParent =
                findNearestLiveParentForRewind(ctx.objectServices().objectManager(), ctx.spawn());
        return liveParent == null ? null : new TurtloidJetInstance(ctx.spawn(), liveParent);
    }

    private static TurtloidBadnikInstance findNearestLiveParentForRewind(
            ObjectManager objectManager,
            ObjectSpawn spawn) {
        if (objectManager == null || spawn == null) {
            return null;
        }
        TurtloidBadnikInstance best = null;
        long bestDistance = Long.MAX_VALUE;
        for (ObjectInstance instance : objectManager.getActiveObjects()) {
            if (!(instance instanceof TurtloidBadnikInstance turtloid) || turtloid.isDestroyed()) {
                continue;
            }
            long dx = turtloid.getX() - spawn.x();
            long dy = turtloid.getY() - spawn.y();
            long distance = dx * dx + dy * dy;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = turtloid;
            }
        }
        return best;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (parent.isParentDestroyed()) {
            setDestroyed(true);
            return;
        }

        // Follow parent position exactly (Obj9C copies parent pos in ROM)
        currentX = parent.getParentX();
        currentY = parent.getParentY();

        // Animate jet exhaust: alternate between frames 6 and 7
        animTimer--;
        if (animTimer < 0) {
            animTimer = ANIM_SPEED;
            animFrame = (animFrame == JET_FRAME_1) ? JET_FRAME_2 : JET_FRAME_1;
        }
    }

    @Override
    public ObjectSpawn getSpawn() {
        return buildSpawnAt(currentX, currentY);
    }

    @Override
    public int getX() { return currentX; }

    @Override
    public int getY() { return currentY; }

    @Override
    public int getPriorityBucket() {
        // ROM: priority = 5 (Obj9C_SubObjData)
        return RenderPriority.clamp(5);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(Sonic2ObjectArtKeys.TURTLOID);
        if (renderer == null) return;

        // Jet exhaust frames 6-7 from shared Turtloid sheet
        renderer.drawFrameIndex(animFrame, currentX, currentY, false, false);
    }
}
