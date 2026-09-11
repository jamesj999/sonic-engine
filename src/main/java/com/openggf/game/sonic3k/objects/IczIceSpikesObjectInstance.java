package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.RomObjectCodePointerProvider;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.render.PatternSpriteRenderer;

import java.util.List;

/**
 * Object 0xB7 - ICZ ice spikes.
 * <p>
 * ROM reference: {@code Obj_ICZIceSpikes} at sonic3k.asm:189535
 * ({@code loc_8B2A8}). Subtype 0 is a solid spike base with a separate
 * {@code collision_flags=$98} hurt child. Nonzero subtypes use
 * {@code collision_flags=$92}, arm when the nearest player is within 0x40 px on
 * the X axis, shake for 16 frames, then switch to {@code MoveTouchChkDel}.
 */
public class IczIceSpikesObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, TouchResponseProvider, RewindRecreatable, RomObjectCodePointerProvider {

    /**
     * Word 0 of this object's S3K SST holds its live ROM code pointer.
     * ROM {@code Obj_ICZIceSpikes} is installed from the S3K object pointer table at
     * {@code $0008B2A0} (table read from the user-supplied ROM; the
     * label is defined at docs/skdisasm/sonic3k.asm:189540).
     * Its whole code block lies in one bank, so the HIGH word that
     * {@code sub_13EFC} latches into {@code Tails_CPU_interact} and compares
     * on the next off-screen on-object frame is {@code $0008}
     * (docs/skdisasm/sonic3k.asm:26816-26843).
     */
    @Override
    public int romObjectCodePointerHighWord() {
        return 0x0008;
    }


    private static final String ART_KEY = Sonic3kObjectArtKeys.ICZ_WALL_AND_COLUMN;
    private static final int PRIORITY_BUCKET = 5; // ObjDat_ICZIceSpikes priority $280.
    private static final int PALETTE_LINE = 2;

    // ObjDat_ICZIceSpikes: width=$0C, height=$10, frame=5, collision_flags=0.
    private static final int MAPPING_FRAME = 5;
    private static final int ATTRIBUTE_HALF_WIDTH = 0x0C;
    private static final int ATTRIBUTE_HALF_HEIGHT = 0x10;

    // loc_8B2DA: SolidObjectFull(d1=$17,d2=8,d3=8,d4=x_pos).
    private static final SolidObjectParams SUBTYPE_ZERO_SOLID =
            SolidObjectParams.of(0x17, 0x08, 0x08);

    // loc_8B2CC and loc_8B330.
    private static final int FALLING_COLLISION_FLAGS = 0x92;
    private static final int CHILD_COLLISION_FLAGS = 0x98;

    private static final int ARM_DISTANCE_X = 0x40;
    private static final int SHAKE_TIMER_INITIAL = 0x0F;
    private static final ObjectPlayerParticipationPolicy PLAYER_PARTICIPATION =
            ObjectPlayerParticipationPolicy.NATIVE_P1_P2;

    private boolean subtypeZero;
    private boolean hFlip;
    private boolean vFlip;
    private int originalX;
    private int originalY;

    private int x;
    private int y;
    private Phase phase;
    private int waitTimer;
    private boolean childSpawned;
    private SpikeHurtChild hurtChild;

    public IczIceSpikesObjectInstance(ObjectSpawn spawn) {
        super(spawn, "ICZIceSpikes");
        this.subtypeZero = (spawn.subtype() & 0xFF) == 0;
        this.hFlip = (spawn.renderFlags() & 0x01) != 0;
        this.vFlip = (spawn.renderFlags() & 0x02) != 0;
        this.originalX = spawn.x();
        this.originalY = spawn.y();
        this.x = originalX;
        this.y = originalY;
        this.phase = subtypeZero ? Phase.SOLID_BASE : Phase.WAIT_FOR_PLAYER;
    }

    @Override
    public IczIceSpikesObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new IczIceSpikesObjectInstance(ctx.spawn());
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        if (subtypeZero) {
            spawnHurtChildOnce();
            return;
        }

        switch (phase) {
            case WAIT_FOR_PLAYER -> {
                if (nearestPlayerWithinArmDistance(player)) {
                    phase = Phase.SHAKING;
                    waitTimer = SHAKE_TIMER_INITIAL;
                }
            }
            case SHAKING -> {
                // ROM loc_8B310 alternates +2/-2 using V_int_run_count bit 0.
                int delta = (vIntRunCount & 1) == 0 ? 2 : -2;
                x = (x + delta) & 0xFFFF;
                waitTimer--;
                if (waitTimer < 0) {
                    phase = Phase.MOVE_TOUCH;
                }
            }
            case MOVE_TOUCH, SOLID_BASE -> {
                // MoveTouchChkDel runs MoveSprite; this object has no ROM velocity writes.
            }
        }
    }

    private boolean nearestPlayerWithinArmDistance(PlayableEntity player) {
        int nearest = nearestPlayerXDistance(player);
        return nearest < ARM_DISTANCE_X;
    }

    private int nearestPlayerXDistance(PlayableEntity player) {
        ObjectServices services = tryServices();
        ObjectPlayerQuery serviceQuery = services != null ? services.playerQuery() : null;
        ObjectPlayerQuery query = new ObjectPlayerQuery(
                () -> player,
                () -> serviceQuery != null ? serviceQuery.sidekicks() : List.of());
        return query.nearestByRomX(PLAYER_PARTICIPATION, x).distance();
    }

    private void spawnHurtChildOnce() {
        if (childSpawned) {
            return;
        }
        childSpawned = true;
        int childY = (originalY + (vFlip ? -0x0C : 0x0C)) & 0xFFFF;
        hurtChild = spawnChild(() -> new SpikeHurtChild(this, originalX, childY));
    }

    void rewindAttachHurtChild(SpikeHurtChild child) {
        hurtChild = child;
        childSpawned = true;
    }

    @Override
    public SolidObjectParams getSolidParams() {
        return subtypeZero ? SUBTYPE_ZERO_SOLID : SolidObjectParams.of(0, 0, 0);
    }

    @Override
    public boolean isSolidFor(PlayableEntity player) {
        return subtypeZero && !isDestroyed();
    }

    @Override
    public boolean skipsCpuSidekickWhenRenderFlagOffScreen() {
        return true;
    }

    @Override
    public int getCollisionFlags() {
        if (isDestroyed() || subtypeZero) {
            return 0;
        }
        return FALLING_COLLISION_FLAGS;
    }

    @Override
    public int getCollisionProperty() {
        return 0;
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
    public ObjectSpawn getSpawn() {
        return buildSpawnAt(x, y);
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(PRIORITY_BUCKET);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(ART_KEY);
        if (renderer != null) {
            renderer.drawFrameIndex(MAPPING_FRAME, x, y, hFlip, vFlip, PALETTE_LINE);
        }
    }

    public String getArtKeyForTesting() {
        return ART_KEY;
    }

    public int getMappingFrameForTesting() {
        return MAPPING_FRAME;
    }

    public int getAttributeHalfWidthForTesting() {
        return ATTRIBUTE_HALF_WIDTH;
    }

    public int getAttributeHalfHeightForTesting() {
        return ATTRIBUTE_HALF_HEIGHT;
    }

    public boolean isShakeActiveForTesting() {
        return phase == Phase.SHAKING;
    }

    public boolean isMoveTouchActiveForTesting() {
        return phase == Phase.MOVE_TOUCH;
    }

    public int getShakeTimerForTesting() {
        return waitTimer;
    }

    public SpikeHurtChild getHurtChildForTesting() {
        return hurtChild;
    }

    private enum Phase {
        SOLID_BASE,
        WAIT_FOR_PLAYER,
        SHAKING,
        MOVE_TOUCH
    }

    public static final class SpikeHurtChild extends AbstractObjectInstance
            implements TouchResponseProvider, RewindRecreatable {
        private IczIceSpikesObjectInstance parent;
        // Non-final so the generic rewind field capturer reapplies the captured
        // (spawn-derived) values after the recreate hook rebuilds this child; the hook
        // already passes them via the ctor, so this is idempotent.
        private int x;
        private int y;

        private SpikeHurtChild(ObjectSpawn spawn) {
            super(spawn, "ICZIceSpikesHurtChild");
            this.parent = null;
            this.x = spawn.x();
            this.y = spawn.y();
        }

        private SpikeHurtChild(IczIceSpikesObjectInstance parent, int x, int y) {
            super(new ObjectSpawn(x, y, Sonic3kObjectIds.ICZ_ICE_SPIKES, 0, 0, false, y),
                    "ICZIceSpikesHurtChild");
            this.parent = parent;
            this.x = x;
            this.y = y;
        }

        @Override
        public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
            IczIceSpikesObjectInstance liveParent = findNearestLiveParentForRewind(ctx);
            if (liveParent == null) {
                return null;
            }
            ObjectSpawn spawn = ctx.spawn();
            int restoredX = spawn != null ? spawn.x() : liveParent.originalX;
            int restoredY = spawn != null ? spawn.y() : liveParent.originalY;
            SpikeHurtChild restored = new SpikeHurtChild(liveParent, restoredX, restoredY);
            liveParent.rewindAttachHurtChild(restored);
            return restored;
        }

        private static IczIceSpikesObjectInstance findNearestLiveParentForRewind(RewindRecreateContext ctx) {
            if (ctx == null || ctx.objectServices() == null || ctx.objectServices().objectManager() == null) {
                return null;
            }
            ObjectSpawn spawn = ctx.spawn();
            IczIceSpikesObjectInstance best = null;
            long bestDistance = Long.MAX_VALUE;
            for (ObjectInstance instance : ctx.objectServices().objectManager().getActiveObjects()) {
                if (!(instance instanceof IczIceSpikesObjectInstance candidate) || candidate.isDestroyed()) {
                    continue;
                }
                if (spawn == null) {
                    return candidate;
                }
                long dx = (long) candidate.getX() - spawn.x();
                long dy = (long) candidate.getY() - spawn.y();
                long distance = dx * dx + dy * dy;
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = candidate;
                }
            }
            return best;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity player) {
            if (parent == null || parent.isDestroyed()) {
                setDestroyed(true);
            }
        }

        @Override
        public int getCollisionFlags() {
            return isDestroyed() ? 0 : CHILD_COLLISION_FLAGS;
        }

        @Override
        public int getCollisionProperty() {
            return 0;
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
        public int getPriorityBucket() {
            return RenderPriority.clamp(PRIORITY_BUCKET);
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            // ROM loc_8B330 only adds this child to the collision response list.
        }
    }
}
