package com.openggf.game.sonic3k.objects.bosses;

import com.openggf.game.PlayableEntity;
import com.openggf.game.rewind.RewindTransient;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreateObjectLinks;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.TouchResponseAttackable;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.objects.TouchResponseResult;

import java.util.List;

/**
 * Vulnerable hit proxy for S3K {@code Obj_MHZEndBoss}.
 *
 * <p>ROM reference: {@code ChildObjDat_76982 -> loc_764A0}. The child refreshes
 * from parent offset {@code +$21,-$10}, uses {@code word_76964}
 * {@code collision_flags=$25}, and forwards accepted attacks to the parent
 * boss state the same way {@code sub_76782} walks through {@code parent3}.
 */
public final class MhzEndBossHitProxyChild extends AbstractObjectInstance
        implements TouchResponseProvider, TouchResponseAttackable, RewindRecreatable {
    private static final int X_OFFSET = 0x21;
    private static final int Y_OFFSET = -0x10;
    private static final int ACTIVE_COLLISION_FLAGS = 0x25;
    private static final int COLLISION_PROPERTY = -1;
    private static final int RENDER_HALF_WIDTH = 0x18;
    private static final int RENDER_HALF_HEIGHT = 0x28;

    @RewindTransient(reason = "Structural parent link; live collision derives from parent boss state.")
    private final MhzEndBossInstance parent;
    private int x;
    private int y;

    private MhzEndBossHitProxyChild() {
        this(placeholderParentForRewindProbe());
    }

    MhzEndBossHitProxyChild(MhzEndBossInstance parent) {
        super(new ObjectSpawn(
                        parent.getX() + X_OFFSET,
                        parent.getY() + Y_OFFSET,
                        Sonic3kObjectIds.MHZ_END_BOSS,
                        0,
                        0,
                        false,
                        0),
                "MHZEndBossHitProxy");
        this.parent = parent;
        refreshFromParent();
    }

    @Override
    public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        MhzEndBossInstance liveParent = RewindRecreateObjectLinks.nearestLiveObject(
                ctx, MhzEndBossInstance.class);
        return liveParent != null ? new MhzEndBossHitProxyChild(liveParent) : null;
    }

    private static MhzEndBossInstance placeholderParentForRewindProbe() {
        return new MhzEndBossInstance(new ObjectSpawn(
                -0xC0,
                0,
                Sonic3kObjectIds.MHZ_END_BOSS,
                0,
                0,
                false,
                0));
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        if (parent.isDestroyed()) {
            setDestroyed(true);
            return;
        }
        refreshFromParent();
        updateDynamicSpawn(x, y);
    }

    private void refreshFromParent() {
        x = parent.getX() + X_OFFSET;
        y = parent.getY() + Y_OFFSET;
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
    public int getOnScreenHalfWidth() {
        return RENDER_HALF_WIDTH;
    }

    @Override
    public int getOnScreenHalfHeight() {
        return RENDER_HALF_HEIGHT;
    }

    @Override
    public int getCollisionFlags() {
        return parent.getState().invulnerable || parent.getState().defeated ? 0 : ACTIVE_COLLISION_FLAGS;
    }

    @Override
    public int getCollisionProperty() {
        return COLLISION_PROPERTY;
    }

    @Override
    public void onPlayerAttack(PlayableEntity player, TouchResponseResult result) {
        if (getCollisionFlags() == 0) {
            return;
        }
        parent.onPlayerAttack(player, result);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        // ROM loc_764C4 ends at Child_AddToTouchList, not a draw helper.
    }

    @Override
    public int getPriorityBucket() {
        return 4; // word_76964 priority $200
    }
}
