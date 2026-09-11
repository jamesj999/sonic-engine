package com.openggf.game.sonic3k.objects;

import com.openggf.game.DamageCause;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.GravityDebrisChild;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.objects.SpawnTrailingZeroIntsRewindRecreatable;
import com.openggf.level.objects.SubpixelMotion;
import com.openggf.level.objects.TouchActorContextPolicy;
import com.openggf.level.objects.TouchAttackBouncePolicy;
import com.openggf.level.objects.TouchCategoryDecodeMode;
import com.openggf.level.objects.TouchOverlapStopPolicy;
import com.openggf.level.objects.TouchResponseProfile;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.objects.TouchShieldDeflectCapability;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.Direction;
import com.openggf.physics.ObjectTerrainUtils;
import com.openggf.physics.TerrainCheckResult;
import com.openggf.sprites.NativePositionOps;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.ObjectControlState;

import java.util.ArrayList;
import java.util.List;

/**
 * Object 0xB2 - ICZ Freezer.
 *
 * <p>ROM reference: {@code Obj_ICZFreezer} at sonic3k.asm:188153.
 * The parent starts a frost jet when a player is within 0x40 pixels on X,
 * toggles the jet every 0x40 frames, spawns visible frost puffs every other
 * active frame, and creates a delayed capture cloud that freezes nearby players.
 */
public class IczFreezerObjectInstance extends AbstractObjectInstance
        implements TouchResponseProvider, SpawnRewindRecreatable {

    private static final String ART_KEY = Sonic3kObjectArtKeys.ICZ_PLATFORMS;
    private static final int OBJECT_ID = Sonic3kObjectIds.ICZ_FREEZER;

    // ObjDat_ICZFreezer: priority $280, width $10, height $0C, frame 6, collision $9A.
    private static final int PRIORITY_BUCKET = 5;
    private static final int HALF_WIDTH = 0x10;
    private static final int HALF_HEIGHT = 0x0C;
    private static final int MAPPING_FRAME = 6;
    private static final int COLLISION_FLAGS = 0x9A;

    // loc_8A656 / loc_8A67E.
    private static final int ACTIVATE_X_RANGE = 0x40;
    private static final int JET_PHASE_FRAMES = 0x40;
    private static final int FROST_PUFF_INTERVAL = 1;
    private static final int CAPTURE_CLOUD_OFFSET = 0x30;
    // sub_8A9C6 scans the ROM's fixed P1/P2 pair; engine sidekicks extend the
    // mechanically identical P2 interaction without disturbing that prefix.
    private static final ObjectPlayerParticipationPolicy PLAYER_PARTICIPATION =
            ObjectPlayerParticipationPolicy.MAIN_PLUS_ENGINE_SIDEKICKS_AS_NATIVE_P2_EXTENDED;

    private int x;
    private int y;
    private boolean hFlip;
    private boolean verticalFlip;

    private boolean frostCycleActive;
    private boolean freezeJetActive;
    private int phaseTimer;
    private int frostPuffTimer;
    private int frostPuffsSpawned;
    private int captureCloudsSpawned;
    private CaptureCloud lastCaptureCloud;
    private boolean waitingForOnscreen = true;
    private boolean placeholderRenderedOnscreen;

    public IczFreezerObjectInstance(ObjectSpawn spawn) {
        super(spawn, "ICZFreezer");
        this.x = spawn.x();
        this.y = spawn.y();
        this.hFlip = (spawn.renderFlags() & 0x01) != 0;
        this.verticalFlip = (spawn.renderFlags() & 0x02) != 0;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        // Obj_WaitOffscreen installs a $20-by-$20 placeholder. Render_Sprites
        // sets its on-screen bit after object execution, then the next object
        // pass restores Obj_ICZFreezer and returns; initialization begins on
        // the following pass. Isolated unit fixtures have no object manager
        // and enter the routine directly.
        ObjectServices objectServices = tryServices();
        if (waitingForOnscreen && objectServices != null && objectServices.objectManager() != null) {
            if (!placeholderRenderedOnscreen) {
                return;
            }
            waitingForOnscreen = false;
            placeholderRenderedOnscreen = false;
            return;
        }
        waitingForOnscreen = false;

        if (!frostCycleActive) {
            if (nearestPlayerXDistance(playerEntity) < ACTIVATE_X_RANGE) {
                startFrostCycle();
            }
            return;
        }

        if (nearestPlayerXDistance(playerEntity) >= ACTIVATE_X_RANGE) {
            stopFrostCycle();
            return;
        }

        updateJetPhase();
        updateFrostPuffs();
    }

    private void startFrostCycle() {
        frostCycleActive = true;
        freezeJetActive = false;
        phaseTimer = 0;
        frostPuffTimer = 0;
        playSfx(Sonic3kSfx.FROST_PUFF.id);
    }

    private void stopFrostCycle() {
        frostCycleActive = false;
        freezeJetActive = false;
    }

    private void updateJetPhase() {
        phaseTimer--;
        if (phaseTimer >= 0) {
            return;
        }

        phaseTimer = JET_PHASE_FRAMES;
        freezeJetActive = !freezeJetActive;
        if (freezeJetActive) {
            spawnCaptureCloud();
        }
    }

    private void updateFrostPuffs() {
        if (!freezeJetActive) {
            return;
        }

        frostPuffTimer--;
        if (frostPuffTimer >= 0) {
            return;
        }

        frostPuffTimer = FROST_PUFF_INTERVAL;
        spawnFrostPuff();
    }

    private void spawnCaptureCloud() {
        int cloudY = y + (verticalFlip ? -CAPTURE_CLOUD_OFFSET : CAPTURE_CLOUD_OFFSET);
        lastCaptureCloud = spawnChild(() -> new CaptureCloud(x, cloudY, hFlip, this));
        captureCloudsSpawned++;
    }

    private void detachCaptureCloud(CaptureCloud candidate) {
        if (lastCaptureCloud == candidate) {
            lastCaptureCloud = null;
        }
    }

    private void spawnFrostPuff() {
        int puffY = y + (verticalFlip ? -0x0C : 0x0C);
        spawnChild(() -> new FrostPuff(x, puffY, verticalFlip));
        frostPuffsSpawned++;
    }

    private int nearestPlayerXDistance(PlayableEntity playerEntity) {
        ObjectServices services = tryServices();
        ObjectPlayerQuery serviceQuery = services != null ? services.playerQuery() : null;
        ObjectPlayerQuery query = new ObjectPlayerQuery(
                () -> playerEntity,
                () -> serviceQuery != null ? serviceQuery.sidekicks() : List.of());
        int nearest = Integer.MAX_VALUE;
        for (PlayableEntity player : query.playersFor(PLAYER_PARTICIPATION)) {
            int projectedX = (player.getCentreX() + (player.getXSpeed() >> 8)) & 0xFFFF;
            int delta = (short) ((x - projectedX) & 0xFFFF);
            nearest = Math.min(nearest, Math.abs(delta));
        }
        return nearest;
    }

    private void playSfx(int sfxId) {
        ObjectServices services = tryServices();
        if (services != null) {
            services.playSfx(sfxId);
        }
    }

    @Override
    public int getCollisionFlags() {
        return COLLISION_FLAGS;
    }

    @Override
    public int getCollisionProperty() {
        return 0;
    }

    @Override
    public TouchResponseProfile getTouchResponseProfile() {
        return getTouchResponseProfile(true);
    }

    @Override
    public TouchResponseProfile getTouchResponseProfile(boolean multiRegionSource) {
        return new TouchResponseProfile(
                TouchCategoryDecodeMode.NORMAL,
                false,
                true,
                multiRegionSource,
                TouchShieldDeflectCapability.NONE,
                0,
                TouchAttackBouncePolicy.STANDARD_ENEMY_KILL,
                TouchActorContextPolicy.MAIN_FULL_SIDEKICK_HURT_ONLY,
                multiRegionSource
                        ? TouchOverlapStopPolicy.STOP_AFTER_FIRST_OVERLAP_FOR_MAIN_ONLY
                        : TouchOverlapStopPolicy.STOP_AFTER_FIRST_OVERLAP_FOR_ALL_ACTORS);
    }

    @Override
    public TouchRegion[] getMultiTouchRegions() {
        return new TouchRegion[] { new TouchRegion(x, y, COLLISION_FLAGS) };
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
    public int getOnScreenHalfWidth() {
        return HALF_WIDTH;
    }

    @Override
    public int getOnScreenHalfHeight() {
        return HALF_HEIGHT;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(ART_KEY);
        if (renderer != null) {
            renderer.drawFrameIndex(MAPPING_FRAME, x, y, hFlip, verticalFlip, 1);
        }
    }

    @Override
    public void refreshPostCameraRenderState() {
        if (waitingForOnscreen) {
            placeholderRenderedOnscreen = isWithinRenderSpriteBounds(0x20, 0x20);
        }
    }

    @Override
    public void onUnload() {
        frostCycleActive = false;
        freezeJetActive = false;
        // The independently allocated capture child survives its parent slot
        // and enters the ROM off-phase scanner. The parent must not retain that
        // now-detached SST identity if placement later rematerializes it.
        lastCaptureCloud = null;
    }

    public boolean isFrostCycleActiveForTesting() {
        return frostCycleActive;
    }

    public boolean isFreezeJetActiveForTesting() {
        return freezeJetActive;
    }

    public int frostPuffsSpawnedForTesting() {
        return frostPuffsSpawned;
    }

    public int captureCloudsSpawnedForTesting() {
        return captureCloudsSpawned;
    }

    public CaptureCloud lastCaptureCloudForTesting() {
        return lastCaptureCloud;
    }

    public CaptureCloud createCaptureCloudForTesting(int x, int y, boolean hFlip) {
        return new CaptureCloud(x, y, hFlip, this);
    }

    public AbstractObjectInstance createFrostPuffForTesting(int x, int y, boolean verticalFlip) {
        return new FrostPuff(x, y, verticalFlip);
    }

    private static IczFreezerObjectInstance nearestLiveFreezer(RewindRecreateContext ctx) {
        ObjectManager manager = ctx.objectServices() != null ? ctx.objectServices().objectManager() : null;
        if (manager == null) {
            return null;
        }
        IczFreezerObjectInstance nearest = null;
        int nearestDistance = Integer.MAX_VALUE;
        for (ObjectInstance object : manager.getActiveObjects()) {
            if (object instanceof IczFreezerObjectInstance freezer && !freezer.isDestroyed()) {
                int distance = Math.abs(freezer.getX() - ctx.spawn().x());
                if (distance < nearestDistance) {
                    nearest = freezer;
                    nearestDistance = distance;
                }
            }
        }
        return nearest;
    }

    public static final class CaptureCloud extends AbstractObjectInstance implements RewindRecreatable {
        // loc_8A748 starts at $1F, then scans after the underflow.
        private static final int CAPTURE_DELAY_FRAMES = 0x1F;
        private static final int OFF_PHASE_DELETE_DELAY = 0x1F;
        private static final int CAPTURE_MIN_X = -0x10;
        private static final int CAPTURE_MAX_X = 0x20;
        private static final int CAPTURE_MIN_Y = -0x28;
        private static final int CAPTURE_MAX_Y = 0x50;

        private final IczFreezerObjectInstance parent;
        private int x;
        private int y;
        private boolean hFlip;

        private int captureDelay = CAPTURE_DELAY_FRAMES;
        private int offPhaseDelay;
        private boolean offPhase;
        private FrozenPlayerBlock frozenBlock;

        private CaptureCloud(int x, int y, boolean hFlip, IczFreezerObjectInstance parent) {
            super(new ObjectSpawn(x, y, OBJECT_ID, 0, hFlip ? 1 : 0, false, y), "ICZFreezerCaptureCloud");
            this.x = x;
            this.y = y;
            this.hFlip = hFlip;
            this.parent = parent;
        }

        private CaptureCloud(ObjectSpawn spawn) {
            super(spawn, "ICZFreezerCaptureCloud");
            this.x = spawn.x();
            this.y = spawn.y();
            this.hFlip = (spawn.renderFlags() & 0x01) != 0;
            this.parent = null;
        }

        @Override
        public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
            IczFreezerObjectInstance liveParent = nearestLiveFreezer(ctx);
            return liveParent == null
                    ? null
                    : new CaptureCloud(
                            ctx.spawn().x(),
                            ctx.spawn().y(),
                            (ctx.spawn().renderFlags() & 0x01) != 0,
                            liveParent);
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity playerEntity) {
            if (isDestroyed() || frozenBlock != null) {
                return;
            }
            if (parent == null || !parent.freezeJetActive) {
                updateOffPhase(playerEntity);
                return;
            }
            offPhase = false;
            if (captureDelay-- >= 0) {
                return;
            }

            scanForCapture(playerEntity);
        }

        private void updateOffPhase(PlayableEntity playerEntity) {
            if (!offPhase) {
                offPhase = true;
                offPhaseDelay = OFF_PHASE_DELETE_DELAY;
                return;
            }
            if (offPhaseDelay-- < 0) {
                setDestroyed(true);
                return;
            }
            scanForCapture(playerEntity);
        }

        private void scanForCapture(PlayableEntity playerEntity) {
            ObjectServices services = tryServices();
            ObjectPlayerQuery serviceQuery = services != null ? services.playerQuery() : null;
            ObjectPlayerQuery query = new ObjectPlayerQuery(
                    () -> playerEntity,
                    () -> serviceQuery != null ? serviceQuery.sidekicks() : List.of());

            boolean capturedAny = false;
            List<PlayableEntity> participants = query.playersFor(PLAYER_PARTICIPATION);
            for (int index = 0; index < participants.size(); index++) {
                PlayableEntity participant = participants.get(index);
                AbstractPlayableSprite player = participant instanceof AbstractPlayableSprite sprite ? sprite : null;
                if (canCapture(player)) {
                    capturedAny |= capture(player, index >= 2);
                }
            }
            if (capturedAny) {
                setDestroyed(true);
            }
        }

        private boolean canCapture(AbstractPlayableSprite player) {
            if (player == null || player.isObjectControlled() || player.getDead() || player.isDebugMode()) {
                return false;
            }
            if (player.getInvulnerable() || player.getInvulnerableFrames() > 0 || player.getInvincibleFrames() > 0) {
                return false;
            }

            int dx = player.getCentreX() - x;
            int dy = player.getCentreY() - y;
            return dx >= CAPTURE_MIN_X && dx < CAPTURE_MAX_X
                    && dy >= CAPTURE_MIN_Y && dy < CAPTURE_MAX_Y;
        }

        private boolean capture(AbstractPlayableSprite player, boolean engineSidekickExtension) {
            int capturedX = player.getCentreX();
            int capturedY = player.getCentreY();
            if (engineSidekickExtension) {
                // Extra engine participants have no native player/SST pair.
                // Their gameplay block must not compete with native objects,
                // but it remains rewind-owned because it controls release.
                FrozenPlayerBlock block = new FrozenPlayerBlock(
                        player, capturedX, capturedY, parent.x, hFlip, false, true);
                services().objectManager().addRewindableAuxiliaryDynamicObject(block);
                applyCaptureState(player, capturedX, capturedY);
                retainFirstFrozenBlock(block);
                return true;
            }

            applyCaptureState(player, capturedX, capturedY);
            FrozenPlayerBlock block = spawnChild(
                    () -> new FrozenPlayerBlock(player, capturedX, capturedY, parent.x, hFlip));
            retainFirstFrozenBlock(block);
            return true;
        }

        private void applyCaptureState(AbstractPlayableSprite player, int capturedX, int capturedY) {
            ObjectControlState.nativeBit7FullControl().applyTo(player);
            player.setAir(true);
            player.setXSpeed((short) 0);
            player.setYSpeed((short) 0);
            player.setGSpeed((short) 0);
            player.setAnimationId(0x1A);
            NativePositionOps.writeXPosPreserveSubpixel(player, capturedX);
            NativePositionOps.writeYPosPreserveSubpixel(player, capturedY);
        }

        private void retainFirstFrozenBlock(FrozenPlayerBlock block) {
            if (!block.isDestroyed() && frozenBlock == null) {
                frozenBlock = block;
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
        public void appendRenderCommands(List<GLCommand> commands) {
            // The ROM capture child is an invisible range checker.
        }

        @Override
        public boolean isPersistent() {
            return true;
        }

        @Override
        public void onUnload() {
            if (parent != null) {
                parent.detachCaptureCloud(this);
            }
        }

        public FrozenPlayerBlock frozenBlockForTesting() {
            return frozenBlock;
        }
    }

    public static final class FrozenPlayerBlock extends AbstractObjectInstance implements RewindRecreatable {
        // ObjDat3_8AAAA: priority $80, width $14, height $10, frame 2.
        private static final int PRIORITY_BUCKET = 1;
        private static final int MAPPING_FRAME = 2;
        private static final int PALETTE = 2;
        private static final int INITIAL_X_SPEED = 0x200;
        private static final int INITIAL_Y_SPEED = -0x400;
        private static final int BREAK_TIMER = 0x7F;
        private static final int POST_BREAK_INVULNERABILITY = 2 * 60;
        private static final int DEBRIS_COUNT = 12;
        private static final int GRAVITY = 0x38;
        private static final int FLOOR_Y_RADIUS = 0x10;

        private AbstractPlayableSprite capturedPlayer;
        private final List<IceDebris> debris = new ArrayList<>(DEBRIS_COUNT);
        private final SubpixelMotion.State motion;

        private int breakTimer = BREAK_TIMER;
        private boolean landedOnTerrain;
        private boolean nativeInitPassPending;
        private boolean engineSidekickExtension;

        public FrozenPlayerBlock(AbstractPlayableSprite capturedPlayer, int capturedX, int capturedY,
                int parentX, boolean hFlip) {
            this(capturedPlayer, capturedX, capturedY, parentX, hFlip, false, false);
        }

        public FrozenPlayerBlock(AbstractPlayableSprite capturedPlayer, int capturedX, int capturedY,
                int parentX, boolean hFlip, boolean nativeInitPassPending) {
            this(capturedPlayer, capturedX, capturedY, parentX, hFlip, nativeInitPassPending, false);
        }

        public FrozenPlayerBlock(AbstractPlayableSprite capturedPlayer, int capturedX, int capturedY,
                int parentX, boolean hFlip, boolean nativeInitPassPending,
                boolean engineSidekickExtension) {
            super(new ObjectSpawn(capturedX, capturedY, OBJECT_ID, 0, hFlip ? 1 : 0, false, capturedY),
                    "ICZFreezerFrozenPlayer");
            this.capturedPlayer = capturedPlayer;
            int xSpeed = capturedX >= parentX ? INITIAL_X_SPEED : -INITIAL_X_SPEED;
            this.motion = new SubpixelMotion.State(capturedX, capturedY, 0, 0, xSpeed, INITIAL_Y_SPEED);
            this.nativeInitPassPending = nativeInitPassPending;
            this.engineSidekickExtension = engineSidekickExtension;
        }

        private FrozenPlayerBlock(ObjectSpawn spawn) {
            super(spawn, "ICZFreezerFrozenPlayer");
            this.capturedPlayer = null;
            this.motion = new SubpixelMotion.State(spawn.x(), spawn.y(), 0, 0, 0, INITIAL_Y_SPEED);
            this.nativeInitPassPending = false;
            this.engineSidekickExtension = false;
        }

        @Override
        public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
            IczFreezerObjectInstance liveParent = nearestLiveFreezer(ctx);
            int parentX = liveParent != null ? liveParent.getX() : ctx.spawn().x();
            return new FrozenPlayerBlock(
                    null,
                    ctx.spawn().x(),
                    ctx.spawn().y(),
                    parentX,
                    (ctx.spawn().renderFlags() & 0x01) != 0);
        }

        @Override
        protected boolean skipsSameFrameUpdateAfterSpawn() {
            return !nativeInitPassPending;
        }

        @Override
        public boolean isPersistent() {
            return true;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity playerEntity) {
            if (isDestroyed()) {
                return;
            }
            if (nativeInitPassPending) {
                nativeInitPassPending = false;
                return;
            }
            if (!landedOnTerrain) {
                applyCameraSideVelocityClamp();
                SubpixelMotion.moveSprite(motion, GRAVITY);
                snapToTerrainIfColliding();
            }
            syncCapturedPlayer();

            if (--breakTimer < 0) {
                breakOpen(vIntRunCount, true);
                return;
            }
            if (tryBreakFromOtherPlayer(playerEntity)) {
                breakOpen(vIntRunCount, false);
            }
        }

        /**
         * ROM {@code sub_8AA38}: the non-captured native player can shatter the
         * block while falling in a roll or spin-dash animation. Engine sidekicks
         * extend that mechanically identical P2 interaction. The successful contact
         * reflects that player's vertical velocity before releasing the captured
         * player without running the timer-expiry hurt path.
         */
        private boolean tryBreakFromOtherPlayer(PlayableEntity updatePlayer) {
            for (PlayableEntity candidate : captureParticipants(updatePlayer)) {
                if (!(candidate instanceof AbstractPlayableSprite attacker)
                        || attacker == capturedPlayer
                        || attacker.isObjectControlled()
                        || attacker.getYSpeed() < 0) {
                    continue;
                }
                int animation = attacker.getAnimationId();
                if (animation != 2 && animation != 9) {
                    continue;
                }
                int dx = (short) (attacker.getCentreX() - motion.x);
                int dy = (short) (attacker.getCentreY() - motion.y);
                if (dx < -0x1C || dx >= 0x1C || dy < -0x18 || dy >= 0x18) {
                    continue;
                }
                attacker.setYSpeed((short) -attacker.getYSpeed());
                return true;
            }
            return false;
        }

        private List<PlayableEntity> captureParticipants(PlayableEntity updatePlayer) {
            ObjectServices services = tryServices();
            if (services != null) {
                try {
                    ObjectPlayerQuery query = services.playerQuery();
                    if (query != null) {
                        return query.playersFor(
                                ObjectPlayerParticipationPolicy.MAIN_PLUS_ENGINE_SIDEKICKS_AS_NATIVE_P2_EXTENDED);
                    }
                } catch (RuntimeException ignored) {
                    // Lightweight object tests may not install a participation query.
                }
            }
            return updatePlayer == null ? List.of() : List.of(updatePlayer);
        }

        private void applyCameraSideVelocityClamp() {
            ObjectServices services = tryServices();
            if (services == null || services.camera() == null || motion.xVel == 0) {
                return;
            }
            int cameraX = services.camera().getX() & 0xFFFF;
            int threshold = (cameraX + (motion.xVel < 0 ? 0x20 : 0x128)) & 0xFFFF;
            int comparison = Integer.compareUnsigned(threshold, motion.x & 0xFFFF);
            boolean clamp = motion.xVel < 0 ? comparison >= 0 : comparison < 0;
            if (clamp) {
                motion.xVel = 0;
                motion.xSub = 0;
            }
        }

        private void snapToTerrainIfColliding() {
            TerrainCheckResult floor = ObjectTerrainUtils.checkFloorDist(motion.x, motion.y, FLOOR_Y_RADIUS);
            if (floor.foundSurface() && floor.distance() < 0) {
                motion.y += floor.distance();
                motion.ySub = 0;
                landedOnTerrain = true;
            }
        }

        private void syncCapturedPlayer() {
            if (capturedPlayer == null) {
                return;
            }
            ObjectControlState.nativeBit7FullControl().applyTo(capturedPlayer);
            NativePositionOps.writeXPosPreserveSubpixel(capturedPlayer, motion.x);
            NativePositionOps.writeYPosPreserveSubpixel(capturedPlayer, motion.y);
            capturedPlayer.setXSpeed((short) 0);
            capturedPlayer.setYSpeed((short) 0);
            capturedPlayer.setGSpeed((short) 0);
        }

        private void breakOpen(int vIntRunCount, boolean applyDamage) {
            if (capturedPlayer != null) {
                if (applyDamage) {
                    applyBreakDamage(vIntRunCount);
                }
                capturedPlayer.setAir(true);
                capturedPlayer.releaseFromObjectControl(vIntRunCount);
                capturedPlayer.setInvulnerableFrames(POST_BREAK_INVULNERABILITY);
            }
            spawnDebris();
            setDestroyed(true);
        }

        private void applyBreakDamage(int vIntRunCount) {
            boolean sidekick = capturedPlayer.isCpuControlled();
            boolean hadRings = !sidekick && capturedPlayer.getRingCount() > 0;
            if (hadRings && !capturedPlayer.hasShield() && !capturedPlayer.suppressesLostRingSpawnOnHurt()) {
                ObjectServices services = tryServices();
                if (services != null) {
                    services.spawnLostRings(capturedPlayer, vIntRunCount);
                }
            }
            boolean hurt = sidekick
                    ? capturedPlayer.applyHurt(getX(), DamageCause.SPIKE)
                    : capturedPlayer.applyHurtOrDeath(getX(), DamageCause.SPIKE, hadRings);
            if (hurt && capturedPlayer.getAnimationId() != 0x18) {
                // ROM loc_8A88A calls HurtCharacter, then overwrites x_vel from
                // render_flags bit 0 for freezer breaks.
                int xVel = capturedPlayer.getDirection() == Direction.LEFT ? 0x0200 : -0x0200;
                capturedPlayer.setXSpeed((short) xVel);
            }
        }

        private void spawnDebris() {
            for (int i = 0; i < DEBRIS_COUNT; i++) {
                int[] velocity = VELOCITY_INDEX[i];
                int debrisSubtype = i * 2;
                IceDebris child;
                if (engineSidekickExtension) {
                    child = new IceDebris(
                            getX(), getY(), debrisSubtype, velocity[0], velocity[1]);
                    services().objectManager().addRewindableAuxiliaryDynamicObject(child);
                } else {
                    child = spawnChild(() -> new IceDebris(
                            getX(), getY(), debrisSubtype, velocity[0], velocity[1]));
                }
                debris.add(child);
            }
        }

        @Override
        public int getX() {
            return motion.x;
        }

        @Override
        public int getY() {
            return motion.y;
        }

        @Override
        public int getPriorityBucket() {
            return RenderPriority.clamp(PRIORITY_BUCKET);
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            PatternSpriteRenderer renderer = getRenderer(ART_KEY);
            if (renderer != null) {
                renderer.drawFrameIndex(MAPPING_FRAME, getX(), getY(), false, false, PALETTE);
            }
        }

        public AbstractPlayableSprite capturedPlayerForTesting() {
            return capturedPlayer;
        }

        public int debrisSpawnedForTesting() {
            return debris.size();
        }
    }

    private static final class FrostPuff extends AbstractObjectInstance implements SpawnRewindRecreatable {
        private static final int PRIORITY_BUCKET = 1;
        private static final int PALETTE = 2;
        private static final int SCRIPT_END_OFFSET = 0x48;
        private static final int[][] ROM_OFFSETS = {
                {0x10, 0x16, 0x03},
                {0x10, 0x16, 0x03},
                {0x14, 0x16, 0x03},
                {0x18, 0x16, 0x03},
                {0x1C, 0x16, 0x07},
                {0x22, 0x17, 0x07},
                {0x26, 0x17, 0x07},
                {0x2E, 0x17, 0x07},
                {0x32, 0x17, 0x07},
                {0x3C, 0x18, 0x07},
                {0x40, 0x18, 0x0F},
                {0x4D, 0x19, 0x0F},
                {0x52, 0x19, 0x0F},
                {0x4B, 0x19, 0x0F},
                {0x44, 0x19, 0x0F},
                {0x42, 0x19, 0x0F},
                {0x3E, 0x18, 0x0F},
                {0x3B, 0x17, 0x0F},
                {0x38, 0x16, 0x0F}
        };

        private boolean verticalFlip;
        private int originX;
        private int originY;
        private int x;
        private int y;
        private int scriptOffset;
        private int mappingFrame = 0x16;
        private int scriptTimer;
        private boolean initialized;
        private boolean drawThisFrame;

        private FrostPuff(int x, int y, boolean verticalFlip) {
            super(new ObjectSpawn(x, y, OBJECT_ID, 0, verticalFlip ? 0x2 : 0, false, y),
                    "ICZFreezerFrostPuff");
            this.verticalFlip = verticalFlip;
            this.originX = x;
            this.originY = y + (verticalFlip ? 0x0C : -0x0C);
            this.x = x;
            this.y = y;
        }

        private FrostPuff(ObjectSpawn spawn) {
            super(spawn, "ICZFreezerFrostPuff");
            boolean verticalFlip = (spawn.renderFlags() & 0x2) != 0;
            this.verticalFlip = verticalFlip;
            this.originX = spawn.x();
            this.originY = spawn.y() + (verticalFlip ? 0x0C : -0x0C);
            this.x = spawn.x();
            this.y = spawn.y();
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity player) {
            drawThisFrame = false;
            if (!initialized) {
                initialized = true;
                return;
            }
            scriptTimer--;
            if (scriptTimer >= 0) {
                return;
            }
            scriptTimer = 2;
            scriptOffset += 4;
            if (scriptOffset >= SCRIPT_END_OFFSET) {
                setDestroyed(true);
                return;
            }

            int[] entry = ROM_OFFSETS[scriptOffset >> 2];
            int random = nextRandomRaw();
            int randomMask = entry[2];
            int centre = randomMask >> 1;
            int dx = (random & randomMask) - centre;
            int randomDy = ((random >>> 16) & randomMask) - centre;
            int dy = verticalFlip ? -entry[0] : entry[0];
            x = originX + dx;
            y = originY + dy + randomDy;
            mappingFrame = entry[1];
            drawThisFrame = true;
        }

        private int nextRandomRaw() {
            ObjectServices services = tryServices();
            return services != null && services.rng() != null ? services.rng().nextRaw() : 0;
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

        /**
         * {@code loc_8A72C} owns its lifetime and only deletes after the complete
         * {@code sub_8A916} displacement script. It does not run an out-of-range
         * tail while the parent/camera moves away.
         */
        @Override
        public boolean isPersistent() {
            return true;
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            if (isDestroyed() || !drawThisFrame) {
                return;
            }
            PatternSpriteRenderer renderer = getRenderer(ART_KEY);
            if (renderer != null) {
                renderer.drawFrameIndex(mappingFrame, x, y, false, verticalFlip, PALETTE);
            }
        }
    }

    private static final class IceDebris extends GravityDebrisChild
            implements SpawnTrailingZeroIntsRewindRecreatable {
        private static final int PRIORITY_BUCKET = 5;
        private static final int PALETTE = 2;
        private static final int GRAVITY = 0x38;
        private static final int[] RAW_ANIMATION_UPPER = {
                0, 0x27, 0x0C, 0x27, 0x0D, 0x27, 0x0E, 0xFC
        };
        private static final int[] RAW_ANIMATION_LOWER = {
                0, 0x27, 0x0F, 0x27, 0x10, 0x27, 0x11, 0xFC
        };

        private final int[] rawAnimation;
        private int mappingFrame = 0x0C;
        private int animFrame;
        private int animFrameTimer;

        private IceDebris(int x, int y, int subtype, int xVel, int yVel) {
            super(new ObjectSpawn(x, y, OBJECT_ID, subtype, 0, false, y),
                    "ICZFreezerIceDebris", xVel, yVel, GRAVITY);
            this.rawAnimation = subtype >= 8 ? RAW_ANIMATION_LOWER : RAW_ANIMATION_UPPER;
            this.animFrame = initialAnimFrame();
        }

        private IceDebris(ObjectSpawn spawn, int ignored) {
            super(spawn, "ICZFreezerIceDebris", 0, 0, GRAVITY);
            this.rawAnimation = (spawn.subtype() & 0xFF) >= 8 ? RAW_ANIMATION_LOWER : RAW_ANIMATION_UPPER;
            this.animFrame = initialAnimFrame();
        }

        private int initialAnimFrame() {
            ObjectServices services = constructionContext();
            return services != null && services.rng() != null ? services.rng().nextBits(3) : 0;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity player) {
            animateRaw();
            super.update(vIntRunCount, player);
        }

        private void animateRaw() {
            animFrameTimer--;
            if (animFrameTimer >= 0) {
                return;
            }

            animFrame = (animFrame + 1) & 0xFF;
            int scriptIndex = animFrame + 1;
            int value = scriptIndex < rawAnimation.length ? rawAnimation[scriptIndex] : 0xFC;
            if ((value & 0x80) != 0) {
                mappingFrame = rawAnimation[1];
                animFrameTimer = rawAnimation[0];
                animFrame = 0;
                return;
            }
            animFrameTimer = rawAnimation[0];
            mappingFrame = value;
        }

        @Override
        public int getPriorityBucket() {
            return RenderPriority.clamp(PRIORITY_BUCKET);
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            PatternSpriteRenderer renderer = getRenderer(ART_KEY);
            if (renderer != null) {
                renderer.drawFrameIndex(mappingFrame, getX(), getY(), false, false, PALETTE);
            }
        }
    }

    /**
     * {@code Obj_VelocityIndex} entries selected by {@code ChildObjDat_8AAEA}
     * children with {@code Set_IndexedVelocity(d0=$C)}.
     */
    private static final int[][] VELOCITY_INDEX = {
            { 0x200, -0x200},
            {-0x300, -0x200},
            { 0x300, -0x200},
            {-0x200, -0x200},
            {      0, -0x200},
            {-0x400, -0x300},
            { 0x400, -0x300},
            { 0x300, -0x300},
            {-0x400, -0x300},
            { 0x400, -0x300},
            {-0x200, -0x200},
            { 0x200, -0x200}
    };
}
