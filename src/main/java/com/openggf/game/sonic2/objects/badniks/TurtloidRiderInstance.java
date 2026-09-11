package com.openggf.game.sonic2.objects.badniks;

import com.openggf.debug.DebugRenderContext;
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
import com.openggf.level.objects.TouchActorContextPolicy;
import com.openggf.level.objects.TouchAttackBouncePolicy;
import com.openggf.level.objects.TouchCategoryDecodeMode;
import com.openggf.level.objects.TouchOverlapStopPolicy;
import com.openggf.level.objects.TouchResponseAttackable;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.objects.TouchResponseProfile;
import com.openggf.level.objects.TouchResponseResult;
import com.openggf.level.objects.TouchShieldDeflectCapability;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import com.openggf.debug.DebugColor;
import java.util.List;

/**
 * Turtloid Rider (0x9B) - Rides on the Turtloid turtle in Sky Chase Zone.
 * Follows parent Turtloid at offset (+4, -$18). Has enemy touch response.
 *
 * Based on disassembly Obj9B (s2.asm:74420-74477).
 */
public class TurtloidRiderInstance extends AbstractObjectInstance
        implements TouchResponseProvider, TouchResponseAttackable, RewindRecreatable {

    // Offset from parent: word_37A2C = dc.w 4, dc.w -$18
    private static final int X_OFFSET = 4;
    private static final int Y_OFFSET = -0x18;

    // Collision: Obj9B_SubObjData collision=$1A -> enemy (0x00) + size 0x1A
    private static final int COLLISION_SIZE_INDEX = 0x1A;
    private static final TouchResponseProfile TOUCH_RESPONSE_PROFILE = new TouchResponseProfile(
            TouchCategoryDecodeMode.NORMAL,
            false,
            false,
            false,
            TouchShieldDeflectCapability.NONE,
            0,
            TouchAttackBouncePolicy.STANDARD_ENEMY_KILL,
            TouchActorContextPolicy.MAIN_FULL_SIDEKICK_HURT_ONLY,
            TouchOverlapStopPolicy.STOP_AFTER_FIRST_OVERLAP_FOR_ALL_ACTORS);

    private final TurtloidBadnikInstance parent;
    private int currentX;
    private int currentY;
    private int mappingFrame;
    private boolean destroyed;

    public TurtloidRiderInstance(ObjectSpawn spawn, TurtloidBadnikInstance parent) {
        super(spawn, "TurtloidRider");
        this.parent = parent;
        this.currentX = spawn.x();
        this.currentY = spawn.y();
        this.mappingFrame = 2; // Initial mapping_frame from disassembly
        this.destroyed = false;
    }

    @Override
    public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        if (ctx == null || ctx.spawn() == null || ctx.objectServices() == null) {
            return null;
        }
        TurtloidBadnikInstance liveParent =
                findNearestLiveParentForRewind(ctx.objectServices().objectManager(), ctx.spawn());
        return liveParent == null ? null : new TurtloidRiderInstance(ctx.spawn(), liveParent);
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
        if (destroyed || parent.isParentDestroyed()) {
            setDestroyed(true);
            return;
        }

        // Follow parent at fixed offset
        // ROM: loc_37A30 - copy parent position, add offsets from word_37A2C
        currentX = parent.getParentX() + X_OFFSET;
        currentY = parent.getParentY() + Y_OFFSET;
    }

    /** Called by parent when rider should change frame (e.g., shooting pose). */
    public void setMappingFrame(int frame) {
        this.mappingFrame = frame;
    }

    @Override
    public int getCollisionFlags() {
        // ENEMY category (0x00) + size index 0x1A
        return 0x00 | (COLLISION_SIZE_INDEX & 0x3F);
    }

    @Override
    public int getCollisionProperty() {
        return 0;
    }

    @Override
    public TouchResponseProfile getTouchResponseProfile() {
        return TOUCH_RESPONSE_PROFILE;
    }

    @Override
    public TouchResponseProfile getTouchResponseProfile(boolean multiRegionSource) {
        return TOUCH_RESPONSE_PROFILE;
    }

    @Override
    public boolean requiresRenderFlagForTouch() {
        return TOUCH_RESPONSE_PROFILE.requiresRenderFlagForTouch();
    }

    @Override
    public void onPlayerAttack(PlayableEntity playerEntity, TouchResponseResult result) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (destroyed) {
            return;
        }
        // ROM parity: destroying the rider should not destroy the turtle base.
        int hitX = getPreUpdateX();
        int hitY = getPreUpdateY();
        destroyed = true;
        setDestroyed(true);
        parent.onRiderDestroyed(hitX, hitY, player);
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
        // ROM: priority = 4 (Obj9B_SubObjData)
        return RenderPriority.clamp(4);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (destroyed) {
            return;
        }

        PatternSpriteRenderer renderer = getRenderer(Sonic2ObjectArtKeys.TURTLOID);
        if (renderer == null) return;

        // Rider uses frames 2 (normal) and 3 (shooting) from shared Turtloid sheet
        renderer.drawFrameIndex(mappingFrame, currentX, currentY, false, false);
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        ctx.drawRect(currentX, currentY, 12, 12, 1f, 0.5f, 0f);
        ctx.drawWorldLabel(currentX, currentY, -2, "Rider f" + mappingFrame, DebugColor.ORANGE);
    }
}
