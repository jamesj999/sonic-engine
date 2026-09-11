package com.openggf.game.sonic3k.objects;

import com.openggf.debug.DebugRenderContext;
import com.openggf.game.PlayableEntity;
import com.openggf.game.rewind.RewindTransient;
import com.openggf.game.solid.PlayerSolidContactResult;
import com.openggf.game.solid.SolidCheckpointBatch;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreateObjectLinks;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.RomObjectCodePointerProvider;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidExecutionMode;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.objects.SubpixelMotion;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * Object 0xBA - ICZ tension platform.
 *
 * <p>ROM reference: {@code Obj_ICZTensionPlatform} at sonic3k.asm:190143-190429.
 * The parent platform stores its original {@code y_pos} in {@code $30}, spawns
 * two visual support children at {@code x +/- $38}, then runs a spring-like
 * vertical response before its inline {@code SolidObjectTop} call.
 */
public class IczTensionPlatformObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener, SpawnRewindRecreatable, RomObjectCodePointerProvider {

    /**
     * Word 0 of this object's S3K SST holds its live ROM code pointer.
     * ROM {@code Obj_ICZTensionPlatform} is installed from the S3K object pointer table at
     * {@code $0008B89A} (table read from the user-supplied ROM; the
     * label is defined at docs/skdisasm/sonic3k.asm:190148).
     * Its whole code block lies in one bank, so the HIGH word that
     * {@code sub_13EFC} latches into {@code Tails_CPU_interact} and compares
     * on the next off-screen on-object frame is {@code $0008}
     * (docs/skdisasm/sonic3k.asm:26816-26843).
     */
    @Override
    public int romObjectCodePointerHighWord() {
        return 0x0008;
    }


    private static final String PLATFORM_ART_KEY = Sonic3kObjectArtKeys.ICZ_PLATFORMS_MISC2;
    private static final String SUPPORT_ART_KEY = Sonic3kObjectArtKeys.ICZ_PLATFORMS;

    private static final int OBJECT_ID = Sonic3kObjectIds.ICZ_TENSION_PLATFORM;
    private static final int PRIORITY_BUCKET = 5; // ObjDat_ICZTensionPlatform priority $280.
    private static final int PLATFORM_FRAME = 0x1F;
    private static final int SUPPORT_FRAME = 8;
    private static final int PALETTE_LINE = 2;

    // sub_8BA1C: d1=$23, d2=$14, d3=$B before SolidObjectTop.
    private static final SolidObjectParams SOLID_PARAMS = new SolidObjectParams(0x23, 0x14, 0x0B);

    // ChildObjDat_8BAD0.
    private static final int SUPPORT_LEFT_X_OFFSET = -0x38;
    private static final int SUPPORT_RIGHT_X_OFFSET = 0x38;
    private static final int SUPPORT_SEGMENT_LEFT_OFFSET = 0x0C;
    private static final int SUPPORT_SEGMENT_RIGHT_OFFSET = 0x18;

    // sub_8B950 spring constants.
    private static final int TARGET_STEP_PER_RIDER = 8;
    private static final int SPRING_ACCEL = 0x80;
    private static final int MAX_Y_VELOCITY = 0x0900;
    private static final int STRONG_REBOUND_THRESHOLD = -0x0400;
    private static final int CLOSE_ENOUGH_DISTANCE = 1;
    private static final int FAR_DISTANCE = 0x50;
    private static final int FAR_REBOUND_Y_VELOCITY = -0x0100;

    private int x;
    private int baseY;
    private boolean hFlip;

    private int y;
    private int ySub;
    private int yVel;
    private int targetY;
    private boolean springActive;
    private boolean movingTowardPositiveY;
    private boolean spawnedSupports;

    public IczTensionPlatformObjectInstance(ObjectSpawn spawn) {
        super(spawn, "ICZTensionPlatform");
        this.x = spawn.x();
        this.y = spawn.y();
        this.baseY = spawn.y();
        this.targetY = spawn.y();
        this.hFlip = (spawn.renderFlags() & 0x01) != 0;
        updateDynamicSpawn(x, y);
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        if (isDestroyed()) {
            return;
        }

        spawnSupportsOnce();
        updateSpringMotion();
        SolidCheckpointBatch batch = checkpointAll();
        applyContactTransitions(batch);
        updateDynamicSpawn(x, y);
    }

    private void spawnSupportsOnce() {
        if (spawnedSupports) {
            return;
        }
        ObjectServices services = tryServices();
        ObjectManager manager = services != null ? services.objectManager() : null;
        if (manager == null) {
            return;
        }
        spawnedSupports = true;
        spawnSupport(SUPPORT_LEFT_X_OFFSET);
        spawnSupport(SUPPORT_RIGHT_X_OFFSET);
    }

    private void spawnSupport(int xOffset) {
        spawnChild(() -> new SupportChild(this, x + xOffset, baseY, xOffset > 0));
    }

    private void updateSpringMotion() {
        if (!springActive) {
            return;
        }

        int accel = SPRING_ACCEL;
        int distance = y - targetY;
        boolean positiveDirection = y < targetY;
        if (!positiveDirection) {
            accel = -accel;
        } else {
            distance = -distance;
        }

        if (positiveDirection != movingTowardPositiveY) {
            yVel = sign16(yVel + accel + accel);
            if (yVel < -SPRING_ACCEL) {
                if (yVel <= STRONG_REBOUND_THRESHOLD) {
                    launchCurrentRiders(yVel);
                }
            } else if (yVel <= SPRING_ACCEL && distance <= CLOSE_ENOUGH_DISTANCE) {
                springActive = false;
            }
            movingTowardPositiveY = positiveDirection;
        }

        yVel = clampSigned16(yVel + accel, -MAX_Y_VELOCITY, MAX_Y_VELOCITY);
        SubpixelMotion.State motion = new SubpixelMotion.State(x, y, 0, ySub, 0, yVel);
        SubpixelMotion.moveSprite2(motion);
        y = motion.y;
        ySub = motion.ySub;
        yVel = sign16(motion.yVel);

        if (distance >= FAR_DISTANCE) {
            yVel = FAR_REBOUND_Y_VELOCITY;
        }
    }

    private void applyContactTransitions(SolidCheckpointBatch batch) {
        if (batch == null || batch.perPlayer().isEmpty()) {
            return;
        }

        int standingCount = 0;
        boolean changed = false;
        for (PlayerSolidContactResult result : batch.perPlayer().values()) {
            if (result == null) {
                continue;
            }
            if (result.standingNow()) {
                standingCount++;
            }
            if (result.standingNow() != result.standingLastFrame()) {
                changed = true;
            }
        }

        if (!changed) {
            return;
        }

        targetY = baseY + (standingCount * TARGET_STEP_PER_RIDER);
        springActive = true;
        movingTowardPositiveY = false;

        for (var entry : batch.perPlayer().entrySet()) {
            PlayerSolidContactResult result = entry.getValue();
            if (result == null || result.standingNow() == result.standingLastFrame()) {
                continue;
            }
            applyPlayerImpulse(entry.getKey(), result);
        }
    }

    private void applyPlayerImpulse(PlayableEntity player, PlayerSolidContactResult result) {
        int preContactYSpeed = result.preContact().ySpeed();
        int impulse = preContactYSpeed;

        // sub_8BA7E: when a leaving rider has upward y_vel, the platform y_vel
        // is added back into the player and the impulse sign is inverted.
        if (!result.standingNow() && preContactYSpeed < 0) {
            if (player != null) {
                player.setYSpeed((short) sign16(player.getYSpeed() + yVel));
            }
            impulse = -preContactYSpeed;
        }

        int nextVelocity = sign16(yVel + impulse);
        yVel = sign16(nextVelocity - (nextVelocity >> 2));
    }

    private void launchCurrentRiders(int platformVelocity) {
        ObjectServices services = tryServices();
        if (services == null) {
            return;
        }
        ObjectManager manager = services.objectManager();
        if (manager == null) {
            return;
        }
        for (PlayableEntity player : services.playerQuery().playersFor(
                ObjectPlayerParticipationPolicy.MAIN_PLUS_ENGINE_SIDEKICKS_AS_NATIVE_P2_EXTENDED)) {
            if (player != null && manager.isRidingObject(player, this)) {
                player.setYSpeed((short) platformVelocity);
                player.setAir(true);
                player.setOnObject(false);
                if (player instanceof AbstractPlayableSprite playable) {
                    playable.setJumping(false);
                    playable.setAnimationId(0x10);
                } else {
                    player.forceAnimationRestart();
                }
                manager.clearRidingObject(player);
            }
        }
    }

    @Override
    public SolidObjectParams getSolidParams() {
        return SOLID_PARAMS;
    }

    @Override
    public int getBalanceWidthPixels() {
        // SetUp_ObjAttributes copies ObjDat_ICZTensionPlatform's
        // width_pixels=$18 into the parent SST. Sonic_Move reads that byte for
        // object-edge balance, not sub_8BA1C's extended d1=$23 solid width.
        return 0x18;
    }

    @Override
    public SolidExecutionMode solidExecutionMode() {
        return SolidExecutionMode.MANUAL_CHECKPOINT;
    }

    @Override
    public boolean isTopSolidOnly() {
        return true;
    }

    @Override
    public boolean usesGroundHalfHeightForTopSolidContact() {
        // SolidObjectTop's new-contact branch subtracts d3 from y_pos before
        // comparing the player's feet (sonic3k.asm loc_1E44C-loc_1E45A).
        return true;
    }

    @Override
    public boolean rejectsZeroDistanceTopSolidLanding() {
        // At d0 == 0, the preceding bhi does not branch, but the unsigned
        // cmpi.w #-$10,d0 / blo still rejects the contact at loc_1E45A.
        return true;
    }

    @Override
    public boolean isSolidFor(PlayableEntity player) {
        return !isDestroyed();
    }

    @Override
    public void onSolidContact(PlayableEntity player, SolidContact contact, int frameCounter) {
        // Manual checkpoints drive the ROM inline SolidObjectTop timing from update().
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed()) {
            return;
        }
        PatternSpriteRenderer renderer = getRenderer(PLATFORM_ART_KEY);
        if (renderer != null) {
            renderer.drawFrameIndex(PLATFORM_FRAME, x, y, hFlip, false, PALETTE_LINE);
        }
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        if (ctx != null) {
            ctx.drawRect(x, y, SOLID_PARAMS.halfWidth(), SOLID_PARAMS.groundHalfHeight(),
                    0.2f, 0.8f, 1.0f);
        }
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
    public int getOutOfRangeReferenceX() {
        return x;
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(PRIORITY_BUCKET);
    }

    public int getYVelocityForTesting() {
        return yVel;
    }

    public int getTargetYForTesting() {
        return targetY;
    }

    public boolean isSpringActiveForTesting() {
        return springActive;
    }

    private static int clampSigned16(int value, int min, int max) {
        return sign16(Math.max(min, Math.min(max, value)));
    }

    private static int sign16(int value) {
        value &= 0xFFFF;
        return value >= 0x8000 ? value - 0x10000 : value;
    }

    public static final class SupportChild extends AbstractObjectInstance implements RewindRecreatable {
        @RewindTransient(reason = "Structural parent pointer; support positions are derived from live parent state.")
        private final IczTensionPlatformObjectInstance parent;
        private int x;
        private int y;
        private boolean hFlip;

        private SupportChild(IczTensionPlatformObjectInstance parent, int x, int y, boolean hFlip) {
            super(new ObjectSpawn(x, y, OBJECT_ID, 0, hFlip ? 1 : 0, false, y),
                    "ICZTensionPlatformSupport");
            this.parent = parent;
            this.x = x;
            this.y = y;
            this.hFlip = hFlip;
        }

        @Override
        public SupportChild recreateForRewind(RewindRecreateContext ctx) {
            IczTensionPlatformObjectInstance liveParent =
                    RewindRecreateObjectLinks.nearestLiveObject(ctx, IczTensionPlatformObjectInstance.class);
            if (liveParent == null) {
                return null;
            }
            ObjectSpawn spawn = ctx.spawn();
            int restoredX = spawn != null ? spawn.x() : liveParent.x;
            int restoredY = spawn != null ? spawn.y() : liveParent.baseY;
            boolean restoredHFlip = spawn != null && (spawn.renderFlags() & 0x01) != 0;
            return new SupportChild(liveParent, restoredX, restoredY, restoredHFlip);
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity playerEntity) {
            if (parent.isDestroyed()) {
                setDestroyed(true);
            }
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            if (isDestroyed()) {
                return;
            }
            PatternSpriteRenderer renderer = getRenderer(SUPPORT_ART_KEY);
            if (renderer == null) {
                return;
            }

            int midY = y + ((parent.y - y) / 2);
            renderer.drawFrameIndex(SUPPORT_FRAME, x + signedSupportOffset(SUPPORT_SEGMENT_LEFT_OFFSET),
                    midY, hFlip, false, PALETTE_LINE);
            renderer.drawFrameIndex(SUPPORT_FRAME, x + signedSupportOffset(SUPPORT_SEGMENT_RIGHT_OFFSET),
                    parent.y, hFlip, false, PALETTE_LINE);
        }

        private int signedSupportOffset(int offset) {
            return hFlip ? -offset : offset;
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
        public int getOutOfRangeReferenceX() {
            return parent.getX();
        }

        @Override
        public int getPriorityBucket() {
            return RenderPriority.clamp(PRIORITY_BUCKET);
        }
    }
}
