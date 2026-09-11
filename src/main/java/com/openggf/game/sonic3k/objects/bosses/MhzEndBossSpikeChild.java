package com.openggf.game.sonic3k.objects.bosses;

import com.openggf.game.PlayableEntity;
import com.openggf.game.rewind.RewindTransient;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreateObjectLinks;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.TouchResponseProfile;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.render.PatternSpriteRenderer;

import java.util.List;

/**
 * Alternating spike child for S3K {@code Obj_MHZEndBoss}.
 *
 * <p>ROM reference: {@code ChildObjDat_76982 -> loc_7665E}. The two children
 * refresh from parent-relative offsets, expose {@code collision_flags=$8B}
 * only while parent {@code $38} bit 6 is set, and alternate active frames via
 * {@code V_int_run_count} parity with nonzero subtype inverted.
 */
public final class MhzEndBossSpikeChild extends AbstractObjectInstance
        implements TouchResponseProvider, RewindRecreatable {
    private static final int ACTIVE_COLLISION_FLAGS = 0x8B;
    private static final int DASH_PHASE_FLAG_OFFSET = 0x38;
    private static final int DASH_PHASE_FLAG = 0x40;
    private static final int MAPPING_FRAME = 0x11;
    private static final int RENDER_HALF_WIDTH = 0x10;
    private static final int RENDER_HALF_HEIGHT = 0x10;
    private static final int SHIELD_REACTION_FIRE = 0x10;

    @RewindTransient(reason = "Structural parent link; live position and collision derive from parent state.")
    private final MhzEndBossInstance parent;
    private int subtype;
    private int xOffset;
    private int yOffset;
    private int x;
    private int y;
    private int collisionFlags;

    private MhzEndBossSpikeChild() {
        this(placeholderParentForRewindProbe(), 0, 0, 0);
    }

    public MhzEndBossSpikeChild(MhzEndBossInstance parent, int subtype, int xOffset, int yOffset) {
        super(new ObjectSpawn(
                        parent.getX() + xOffset,
                        parent.getY() + yOffset,
                        Sonic3kObjectIds.MHZ_END_BOSS,
                        subtype,
                        0,
                        false,
                        0),
                "MHZEndBossSpike");
        this.parent = parent;
        this.subtype = subtype;
        this.xOffset = xOffset;
        this.yOffset = yOffset;
        refreshFromParent();
    }

    @Override
    public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        MhzEndBossInstance liveParent = RewindRecreateObjectLinks.nearestLiveObject(
                ctx, MhzEndBossInstance.class);
        return liveParent != null ? new MhzEndBossSpikeChild(liveParent, 0, 0, 0) : null;
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
        collisionFlags = resolveCollisionFlags(vIntRunCount);
        updateDynamicSpawn(x, y);
    }

    private void refreshFromParent() {
        x = parent.getX() + xOffset;
        y = parent.getY() + yOffset;
    }

    private int resolveCollisionFlags(int vIntRunCount) {
        if ((parent.getCustomFlag(DASH_PHASE_FLAG_OFFSET) & DASH_PHASE_FLAG) == 0) {
            return 0;
        }
        boolean oddFrame = (vIntRunCount & 1) != 0;
        boolean subtypeActive = subtype == 0 ? !oddFrame : oddFrame;
        return subtypeActive ? ACTIVE_COLLISION_FLAGS : 0;
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
        return collisionFlags;
    }

    @Override
    public int getCollisionProperty() {
        return 0;
    }

    @Override
    public int getShieldReactionFlags() {
        return SHIELD_REACTION_FIRE;
    }

    @Override
    public TouchResponseProfile getTouchResponseProfile() {
        return TouchResponseProfile.fromProvider(this);
    }

    @Override
    public TouchResponseProfile getTouchResponseProfile(boolean multiRegionSource) {
        return TouchResponseProfile.fromProvider(this, multiRegionSource);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (collisionFlags == 0) {
            return;
        }
        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.MHZ_END_BOSS);
        if (renderer == null) {
            return;
        }
        renderer.drawFrameIndex(MAPPING_FRAME, x, y, false, false);
    }

    @Override
    public int getPriorityBucket() {
        return 4; // ObjDat3_7694C priority $200
    }
}
