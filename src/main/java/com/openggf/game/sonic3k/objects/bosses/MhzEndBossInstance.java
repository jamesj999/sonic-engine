package com.openggf.game.sonic3k.objects.bosses;

import com.openggf.game.LevelState;
import com.openggf.game.PlayableEntity;
import com.openggf.game.PlayerCharacter;
import com.openggf.game.sonic3k.S3kPaletteOwners;
import com.openggf.game.sonic3k.S3kPaletteWriteSupport;
import com.openggf.game.sonic3k.Sonic3kLevelEventManager;
import com.openggf.game.sonic3k.Sonic3kObjectArtProvider;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.constants.Sonic3kAnimationIds;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.objects.S3kBossExplosionChild;
import com.openggf.game.sonic3k.objects.SongFadeTransitionInstance;
import com.openggf.game.sonic3k.runtime.MhzZoneRuntimeState;
import com.openggf.game.sonic3k.runtime.S3kZoneRuntimeState;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreateObjectLinks;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.objects.ZeroArgRewindRecreatable;
import com.openggf.level.objects.boss.AbstractBossInstance;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.Direction;
import com.openggf.physics.SwingMotion;
import com.openggf.sprites.NativePositionOps;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.ObjectControlState;

import java.util.List;

/**
 * S3K SKL object $93 - MHZ Act 2 end boss.
 *
 * <p>ROM reference: {@code Obj_MHZEndBoss}. The active core initializes with
 * collision size $0F and nine hits at {@code loc_76004}; this class ports the
 * arena setup, weather-machine gate, alternating dash loop, final defeat,
 * capsule handoff, and Robotnik ship escape onto the shared boss path.
 */
public final class MhzEndBossInstance extends AbstractBossInstance implements SpawnRewindRecreatable {
    private static final int HIT_COUNT = 9;
    private static final int COLLISION_SIZE = 0x0F;
    private static final int INITIAL_X_OFFSET = 0xC0;
    private static final int ACTIVE_CORE_MAPPING_FRAME = 1;
    private static final int PRIORITY_BUCKET = 5; // ObjDat3_76934 priority $280
    private static final int ACTIVE_CORE_RENDER_HALF_WIDTH = 0x80;
    private static final int ACTIVE_CORE_RENDER_HALF_HEIGHT = 0x80;
    private static final int CAMERA_RANGE_MIN_Y = 0x0000; // word_769F4
    private static final int CAMERA_RANGE_MAX_Y = 0x0300;
    private static final int CAMERA_RANGE_MIN_X = 0x3AA0;
    private static final int CAMERA_RANGE_MAX_X = 0x3D90;
    private static final int ROUTINE_WAIT_FOR_CHILD_SIGNAL = 0x00;
    private static final int ROUTINE_RISE_PREP_WAIT = 0x04;
    private static final int ROUTINE_UPWARD_LAUNCH_WAIT = 0x06;
    private static final int ROUTINE_SWING_WAIT = 0x08;
    private static final int ROUTINE_DASH_WAIT = 0x0A;
    private static final int ROUTINE_CAMERA_APPROACH_SWING = 0x0C;
    private static final int ROUTINE_ALTERNATING_DASH_WAIT = 0x0E;
    private static final int FLAG_CHILD_SIGNAL = 0x04; // $38 bit 2
    private static final int FLAG_SWING_DIRECTION_DOWN = 0x01; // $38 bit 0
    private static final int FLAG_ARENA_ANCHORED = 0x08; // $38 bit 3
    private static final int FLAG_DASH_PHASE = 0x40; // $38 bit 6
    private static final int FLAG_ARENA_PASS = 0x80; // $38 bit 7
    private static final int TIMER_OFFSET = 0x2E;
    private static final int STATE_FLAGS_OFFSET = 0x38;
    private static final int SWING_MAX_VELOCITY_OFFSET = 0x3E;
    private static final int SWING_ACCELERATION_OFFSET = 0x40;
    private static final int INITIAL_CHILDREN_SPAWNED_OFFSET = 0x100;
    private static final int SPIKE_ART_QUEUE_PENDING_OFFSET = 0x102;
    private static final int SPIKE_ART_CAMERA_THRESHOLD_X = 0x4010;
    private static final int FINAL_DEFEAT_THRESHOLD_X = 0x44D0;
    private static final int FADE_TO_LEVEL_MUSIC_FRAMES = 2 * 60;
    private static final int FINAL_PHASE_DASH_TO_THRESHOLD = 0;
    private static final int FINAL_PHASE_WAIT_FADE_TO_LEVEL_MUSIC = 1;
    private static final int FINAL_PHASE_CLIMB_AWAY = 2;
    private static final int FINAL_PHASE_POST_BOSS_CAMERA_SCROLL = 3;
    private static final int FINAL_PHASE_WAIT_CAPSULE_RESULTS_FLAG = 4;
    private static final int FINAL_PHASE_WAIT_ROBOTNIK_SHIP_TIMER = 5;
    private static final int FINAL_PHASE_ROBOTNIK_SHIP_ESCAPE = 6;
    private static final int FINAL_PHASE_ROBOTNIK_SHIP_CAMERA_FLAG_SET = 7;
    private static final int FINAL_PHASE_PLAYER_STOPPED_FOR_WALKOFF = 8;
    private static final int FINAL_PHASE_PLAYER_WALKOFF_LAUNCH = 9;
    private static final int FINAL_PHASE_PLAYER_GRAB_WAIT = 10;
    private static final int POST_BOSS_CAMERA_TRIGGER_Y_OFFSET = 0x120;
    private static final int POST_BOSS_CAMERA_SCROLL_TARGET_X = 0x45A0;
    private static final int POST_BOSS_CAMERA_SCROLL_STEP = 4;
    private static final int POST_BOSS_CAPSULE_X = 0x4640;
    private static final int POST_BOSS_CAPSULE_Y = 0x0320;
    private static final int POST_CAPSULE_ESCAPE_CAMERA_TARGET_X = 0x5000;
    private static final int ROBOTNIK_SHIP_WAIT_FRAMES = 0x1BF;
    private static final int FINAL_TRANSITION_ZONE = Sonic3kZoneIds.ZONE_FBZ;
    private static final int FINAL_TRANSITION_ACT = 0;
    private static final int FINAL_HIT_EXPLOSION_TIMER = 0x80; // CreateBossExp20
    private static final int FINAL_HIT_EXPLOSION_RANGE_X = 0x20;
    private static final int FINAL_HIT_EXPLOSION_RANGE_Y = 0x20;
    private static final int FINAL_HIT_EXPLOSION_INTERVAL = 3;
    private static final int[] HIT_FLASH_COLOR_INDICES = {4, 10, 11, 13, 14};
    private static final int[][] HIT_FLASH_WORDS = {
            {0x0E42, 0x0228, 0x0000, 0x0C40, 0x0820},
            {0x0888, 0x0AAA, 0x0EEE, 0x0888, 0x0AAA}
    };

    private int priorityBucket;
    private boolean highPriority;
    private int finalDefeatPhase;
    private boolean cameraRangePassed;
    private boolean paletteLoaded;
    private boolean arenaSetupApplied;
    private boolean finalHitHandoffFlag;
    private int finalHitExplosionTimer;
    private int finalHitExplosionIntervalCounter;

    public MhzEndBossInstance(ObjectSpawn spawn) {
        super(spawn, "MHZEndBoss");
    }

    @Override
    protected void initializeBossState() {
        state.x = (spawn.x() + INITIAL_X_OFFSET) & 0xFFFF;
        state.y = spawn.y();
        state.xFixed = state.x << 16;
        state.yFixed = state.y << 16;
        state.hitCount = HIT_COUNT;
        state.routine = 0;
        priorityBucket = PRIORITY_BUCKET;
        highPriority = false;
        finalDefeatPhase = FINAL_PHASE_DASH_TO_THRESHOLD;
        cameraRangePassed = false;
        paletteLoaded = false;
        arenaSetupApplied = false;
        finalHitHandoffFlag = false;
        finalHitExplosionTimer = -1;
        finalHitExplosionIntervalCounter = FINAL_HIT_EXPLOSION_INTERVAL - 1;
    }

    @Override
    protected void updateBossLogic(int vIntRunCount, PlayableEntity player) {
        if (!cameraRangePassed) {
            if (state.defeated || state.routine != ROUTINE_WAIT_FOR_CHILD_SIGNAL
                    || getCustomFlag(INITIAL_CHILDREN_SPAWNED_OFFSET) != 0) {
                cameraRangePassed = true;
            } else if (!isCameraInRange()) {
                return;
            } else {
                cameraRangePassed = true;
            }
        }
        loadBossPalette();
        updateMhzHitFlash();
        applyInitialArenaSetup();
        updateMhzSpikeArtQueue();
        if (state.defeated) {
            tickFinalHitExplosionController();
            updateFinalDefeatDash(player);
            return;
        }
        spawnInitialChildrenOnce();

        if (state.routine == ROUTINE_WAIT_FOR_CHILD_SIGNAL) {
            updateWaitForChildSignal();
        } else if (state.routine == ROUTINE_RISE_PREP_WAIT) {
            waitThen(this::startUpwardLaunch);
        } else if (state.routine == ROUTINE_UPWARD_LAUNCH_WAIT) {
            moveSprite2();
            waitThen(this::startSwingWait);
        } else if (state.routine == ROUTINE_SWING_WAIT) {
            swingUpAndDown();
            moveSprite2();
            waitThen(this::startDashWait);
        } else if (state.routine == ROUTINE_DASH_WAIT) {
            swingUpAndDown();
            moveSprite2();
            waitThen(this::startCameraApproachSwing);
        } else if (state.routine == ROUTINE_CAMERA_APPROACH_SWING) {
            updateCameraApproachSwing();
        } else if (state.routine == ROUTINE_ALTERNATING_DASH_WAIT) {
            updateAlternatingDashWait();
        }
        applyLevelRepeatOffset(mhzLevelRepeatOffset());
    }

    private boolean isCameraInRange() {
        var services = tryServices();
        if (services == null || services.camera() == null) {
            return true;
        }
        int cameraX = Short.toUnsignedInt(services.camera().getX());
        int cameraY = Short.toUnsignedInt(services.camera().getY());
        return cameraY >= CAMERA_RANGE_MIN_Y && cameraY <= CAMERA_RANGE_MAX_Y
                && cameraX >= CAMERA_RANGE_MIN_X && cameraX <= CAMERA_RANGE_MAX_X;
    }

    private void applyInitialArenaSetup() {
        if (arenaSetupApplied) {
            return;
        }
        arenaSetupApplied = true;
        var services = tryServices();
        if (services == null) {
            return;
        }
        if (services.camera() != null) {
            services.camera().setMinX(services.camera().getX());
        }
        if (services.levelEventProvider() instanceof Sonic3kLevelEventManager manager) {
            manager.setBossFlag(true);
        }
    }

    private void updateWaitForChildSignal() {
        if ((getCustomFlag(STATE_FLAGS_OFFSET) & FLAG_CHILD_SIGNAL) == 0) {
            return;
        }

        state.routine = ROUTINE_RISE_PREP_WAIT;
        setCustomFlag(TIMER_OFFSET, 0x3F);
    }

    private void spawnInitialChildrenOnce() {
        if (getCustomFlag(INITIAL_CHILDREN_SPAWNED_OFFSET) != 0) {
            return;
        }
        var services = tryServices();
        if (services == null || services.objectManager() == null) {
            return;
        }
        setCustomFlag(INITIAL_CHILDREN_SPAWNED_OFFSET, 1);
        spawnChild(() -> new MhzEndBossRobotnikHeadChild(this));
        spawnChild(() -> new MhzEndBossSpikeChild(this, 0, -0x14, 0x18));
        spawnChild(() -> new MhzEndBossSpikeChild(this, 1, -0x2C, 0x18));
        spawnChild(() -> new MhzEndBossWeatherMachineChild(this));
        spawnChild(() -> new MhzEndBossHitProxyChild(this));
        spawnChild(() -> new MhzEndBossVisualChild(this, 1, 5, true));
        spawnChild(() -> new MhzEndBossVisualChild(this, 2, 6, false));
        spawnChild(() -> new MhzEndBossVisualChild(this, 3, 4, false));
    }

    private void waitThen(Runnable callback) {
        int timer = getCustomFlag(TIMER_OFFSET) - 1;
        setCustomFlag(TIMER_OFFSET, timer);
        if (timer < 0) {
            callback.run();
        }
    }

    private void startUpwardLaunch() {
        state.routine = ROUTINE_UPWARD_LAUNCH_WAIT;
        state.yVel = -0x200;
        setCustomFlag(TIMER_OFFSET, 0x23);
        services().playSfx(Sonic3kSfx.RISING.id);
    }

    private void startSwingWait() {
        state.routine = ROUTINE_SWING_WAIT;
        setCustomFlag(TIMER_OFFSET, 0x3F);
        swingSetup1();
    }

    private void swingSetup1() {
        state.yVel = 0xC0;
        setCustomFlag(SWING_MAX_VELOCITY_OFFSET, 0xC0);
        setCustomFlag(SWING_ACCELERATION_OFFSET, 0x10);
        setCustomFlag(STATE_FLAGS_OFFSET, getCustomFlag(STATE_FLAGS_OFFSET) & ~FLAG_SWING_DIRECTION_DOWN);
    }

    private void swingUpAndDown() {
        int flags = getCustomFlag(STATE_FLAGS_OFFSET);
        SwingMotion.Result result = SwingMotion.update(
                getCustomFlag(SWING_ACCELERATION_OFFSET),
                state.yVel,
                getCustomFlag(SWING_MAX_VELOCITY_OFFSET),
                (flags & FLAG_SWING_DIRECTION_DOWN) != 0);
        state.yVel = result.velocity();
        if (result.directionDown()) {
            flags |= FLAG_SWING_DIRECTION_DOWN;
        } else {
            flags &= ~FLAG_SWING_DIRECTION_DOWN;
        }
        setCustomFlag(STATE_FLAGS_OFFSET, flags);
    }

    private void startDashWait() {
        state.routine = ROUTINE_DASH_WAIT;
        setCustomFlag(STATE_FLAGS_OFFSET, getCustomFlag(STATE_FLAGS_OFFSET) | FLAG_DASH_PHASE);
        state.xVel = 0x400;
        setCustomFlag(TIMER_OFFSET, 0x3F);
        services().playSfx(Sonic3kSfx.DASH.id);
    }

    private void startCameraApproachSwing() {
        state.routine = ROUTINE_CAMERA_APPROACH_SWING;
        setCustomFlag(STATE_FLAGS_OFFSET,
                getCustomFlag(STATE_FLAGS_OFFSET) | FLAG_ARENA_PASS | FLAG_ARENA_ANCHORED);
        state.x = 0x4180;
        state.y = 0x02E0;
        state.xFixed = state.x << 16;
        state.yFixed = state.y << 16;
        state.xVel = 0;
        setCustomFlag(SPIKE_ART_QUEUE_PENDING_OFFSET, 1);
        var services = tryServices();
        if (services != null && services.camera() != null) {
            services.camera().setMaxXTarget((short) 0x45A0);
        }
    }

    private void updateMhzSpikeArtQueue() {
        if (getCustomFlag(SPIKE_ART_QUEUE_PENDING_OFFSET) == 0) {
            return;
        }
        var services = tryServices();
        if (services == null || services.camera() == null) {
            return;
        }
        if (Short.toUnsignedInt(services.camera().getX()) < SPIKE_ART_CAMERA_THRESHOLD_X) {
            return;
        }
        setCustomFlag(SPIKE_ART_QUEUE_PENDING_OFFSET, 0);
        ensureStandaloneArtLoaded(Sonic3kObjectArtKeys.MHZ_END_BOSS_SPIKES);
    }

    private void ensureStandaloneArtLoaded(String artKey) {
        var services = tryServices();
        if (services == null || services.renderManager() == null) {
            return;
        }
        if (services.renderManager().getArtProvider() instanceof Sonic3kObjectArtProvider artProvider) {
            artProvider.ensureStandaloneArtLoaded(artKey);
        }
    }

    private void ensureBossExplosionArtLoaded() {
        var services = tryServices();
        if (services == null || services.renderManager() == null) {
            return;
        }
        if (services.renderManager().getArtProvider() instanceof Sonic3kObjectArtProvider artProvider) {
            artProvider.ensureBossExplosionArtLoaded();
        }
    }

    private void updateCameraApproachSwing() {
        var services = tryServices();
        if (services != null && services.camera() != null
                && Short.toUnsignedInt(services.camera().getX()) + 0xE0 >= state.x) {
            state.routine = ROUTINE_ALTERNATING_DASH_WAIT;
            setCustomFlag(TIMER_OFFSET, 0);
            updateAlternatingDashWait();
            return;
        }

        swingUpAndDown();
        moveSprite2();
    }

    private void updateAlternatingDashWait() {
        swingUpAndDown();
        moveSprite2();
        waitThen(this::setupAlternatingDash);
    }

    private void setupAlternatingDash() {
        setCustomFlag(TIMER_OFFSET, 0x5F);
        int velocity = currentPlayerCharacter() == PlayerCharacter.KNUCKLES ? 0x500 : 0x400;
        int flags = getCustomFlag(STATE_FLAGS_OFFSET);
        if ((flags & FLAG_DASH_PHASE) != 0) {
            flags &= ~FLAG_DASH_PHASE;
            velocity -= 0x100;
        } else {
            flags |= FLAG_DASH_PHASE;
            velocity += 0x100;
        }
        setCustomFlag(STATE_FLAGS_OFFSET, flags);
        state.xVel = velocity;
    }

    private PlayerCharacter currentPlayerCharacter() {
        var services = tryServices();
        if (services != null && services.zoneRuntimeState() instanceof S3kZoneRuntimeState s3kState) {
            return s3kState.playerCharacter();
        }
        return PlayerCharacter.SONIC_AND_TAILS;
    }

    private void moveSprite2() {
        state.applyVelocity();
    }

    @Override
    protected int getInitialHitCount() {
        return HIT_COUNT;
    }

    @Override
    protected void onHitTaken(int remainingHits) {
    }

    @Override
    protected void onDefeatStarted() {
        state.invulnerable = false;
        state.invulnerabilityTimer = 0;
        setCustomFlag(STATE_FLAGS_OFFSET, getCustomFlag(STATE_FLAGS_OFFSET) & ~FLAG_DASH_PHASE);
        state.xVel = currentPlayerCharacter() == PlayerCharacter.KNUCKLES ? 0x500 : 0x400;
        finalDefeatPhase = FINAL_PHASE_DASH_TO_THRESHOLD;
        finalHitHandoffFlag = true;
        finalHitExplosionTimer = FINAL_HIT_EXPLOSION_TIMER;
        finalHitExplosionIntervalCounter = FINAL_HIT_EXPLOSION_INTERVAL - 1;
        spawnFreeChild(() -> new MhzEndBossWalkoffPrepChild(this));
    }

    private void tickFinalHitExplosionController() {
        if (finalHitExplosionTimer <= 0) {
            return;
        }
        if ((getCustomFlag(STATE_FLAGS_OFFSET) & 0x20) != 0) {
            finalHitExplosionTimer = -1;
            return;
        }
        finalHitExplosionIntervalCounter--;
        if (finalHitExplosionIntervalCounter >= 0) {
            return;
        }

        finalHitExplosionTimer--;
        if (finalHitExplosionTimer <= 0) {
            return;
        }
        var services = tryServices();
        if (services != null) {
            int random = services.rng().nextRaw();
            int xOffset = (random & ((FINAL_HIT_EXPLOSION_RANGE_X * 2) - 1)) - FINAL_HIT_EXPLOSION_RANGE_X;
            int yOffset = ((random >> 8) & ((FINAL_HIT_EXPLOSION_RANGE_Y * 2) - 1)) - FINAL_HIT_EXPLOSION_RANGE_Y;
            services.playSfx(Sonic3kSfx.EXPLODE.id);
            spawnChild(() -> new S3kBossExplosionChild(state.x + xOffset, state.y + yOffset));
        }
        finalHitExplosionIntervalCounter = FINAL_HIT_EXPLOSION_INTERVAL - 1;
    }

    private void updateFinalDefeatDash(PlayableEntity player) {
        if (finalDefeatPhase == FINAL_PHASE_DASH_TO_THRESHOLD) {
            updateFinalDefeatDashToThreshold();
        } else if (finalDefeatPhase == FINAL_PHASE_WAIT_FADE_TO_LEVEL_MUSIC) {
            updateFinalDefeatFadeWait();
        } else if (finalDefeatPhase == FINAL_PHASE_CLIMB_AWAY) {
            updateFinalDefeatClimbAway();
        } else if (finalDefeatPhase == FINAL_PHASE_POST_BOSS_CAMERA_SCROLL) {
            updatePostBossCameraScroll(player);
        } else if (finalDefeatPhase == FINAL_PHASE_WAIT_CAPSULE_RESULTS_FLAG) {
            updateWaitForCapsuleResultsFlag();
        } else if (finalDefeatPhase == FINAL_PHASE_WAIT_ROBOTNIK_SHIP_TIMER) {
            updateRobotnikShipTimer();
        } else if (finalDefeatPhase == FINAL_PHASE_ROBOTNIK_SHIP_ESCAPE) {
            updateRobotnikShipEscape();
        } else if (finalDefeatPhase == FINAL_PHASE_ROBOTNIK_SHIP_CAMERA_FLAG_SET) {
            updateRobotnikShipCameraFlagSet(player);
        } else if (finalDefeatPhase == FINAL_PHASE_PLAYER_STOPPED_FOR_WALKOFF) {
            updatePlayerStoppedForWalkoff(player);
        } else if (finalDefeatPhase == FINAL_PHASE_PLAYER_WALKOFF_LAUNCH) {
            updatePlayerWalkoffLaunch(player);
        } else if (finalDefeatPhase == FINAL_PHASE_PLAYER_GRAB_WAIT) {
            updatePlayerGrabWait();
        }
    }

    private void updateFinalDefeatDashToThreshold() {
        swingUpAndDown();
        moveSprite2();
        if (state.x >= FINAL_DEFEAT_THRESHOLD_X) {
            startFinalDefeatFadeWait();
        }
    }

    private void startFinalDefeatFadeWait() {
        finalDefeatPhase = FINAL_PHASE_WAIT_FADE_TO_LEVEL_MUSIC;
        var services = tryServices();
        if (services != null) {
            if (services.gameState() != null) {
                services.gameState().setScreenShakeActive(true);
            }
            LevelState levelState = services.levelGamestate();
            if (levelState != null) {
                levelState.pauseTimer();
            }
        }
    }

    private void updateFinalDefeatFadeWait() {
        int timer = getCustomFlag(TIMER_OFFSET) - 1;
        setCustomFlag(TIMER_OFFSET, timer);
        if (timer >= 0) {
            return;
        }
        setCustomFlag(TIMER_OFFSET, FADE_TO_LEVEL_MUSIC_FRAMES - 1);
        spawnFreeChild(() -> new SongFadeTransitionInstance(FADE_TO_LEVEL_MUSIC_FRAMES, resolveLevelMusicId()));
        startFinalDefeatClimbAway();
    }

    private void startFinalDefeatClimbAway() {
        finalDefeatPhase = FINAL_PHASE_CLIMB_AWAY;
        priorityBucket = 2;
        highPriority = true;
        state.xVel = 0;
        state.yVel = -0x200;
        setCustomFlag(STATE_FLAGS_OFFSET, getCustomFlag(STATE_FLAGS_OFFSET) | 0x10);
        ensureStandaloneArtLoaded(Sonic3kObjectArtKeys.EGG_CAPSULE);
        ensureBossExplosionArtLoaded();
        spawnDefeatFragments();
    }

    private void spawnDefeatFragments() {
        var services = tryServices();
        if (services == null || services.objectManager() == null) {
            return;
        }
        for (int subtype = 0; subtype < 6; subtype++) {
            int childSubtype = subtype;
            spawnChild(() -> new MhzEndBossDefeatFragmentChild(this, childSubtype));
        }
    }

    private void updateFinalDefeatClimbAway() {
        moveSprite2();
        var services = tryServices();
        if (services == null || services.camera() == null) {
            return;
        }
        int triggerY = (Short.toUnsignedInt(services.camera().getY()) + POST_BOSS_CAMERA_TRIGGER_Y_OFFSET) & 0xFFFF;
        if (Integer.compareUnsigned(triggerY, state.y & 0xFFFF) >= 0) {
            return;
        }
        startPostBossCameraScrollAndCapsule();
    }

    private void startPostBossCameraScrollAndCapsule() {
        finalDefeatPhase = FINAL_PHASE_POST_BOSS_CAMERA_SCROLL;
        setCustomFlag(STATE_FLAGS_OFFSET, getCustomFlag(STATE_FLAGS_OFFSET) | 0x20);
        var services = tryServices();
        if (services != null) {
            if (services.gameState() != null) {
                services.gameState().setCurrentBossId(0);
            }
            if (services.levelEventProvider() instanceof Sonic3kLevelEventManager manager) {
                manager.setBossFlag(false);
            }
        }
        spawnFreeChild(() -> new MhzEndBossEggCapsuleInstance(POST_BOSS_CAPSULE_X, POST_BOSS_CAPSULE_Y));
    }

    private void updatePostBossCameraScroll(PlayableEntity player) {
        var services = tryServices();
        if (services == null || services.camera() == null) {
            return;
        }
        int nextCameraX = Short.toUnsignedInt(services.camera().getX()) + POST_BOSS_CAMERA_SCROLL_STEP;
        if (nextCameraX >= POST_BOSS_CAMERA_SCROLL_TARGET_X) {
            nextCameraX = POST_BOSS_CAMERA_SCROLL_TARGET_X;
            services.camera().setMinX((short) POST_BOSS_CAMERA_SCROLL_TARGET_X);
            services.camera().setMaxX((short) POST_BOSS_CAMERA_SCROLL_TARGET_X);
            services.camera().setFrozen(false);
            clearPostBossCameraEndpointControls(player);
            finalDefeatPhase = FINAL_PHASE_WAIT_CAPSULE_RESULTS_FLAG;
        }
        services.camera().setX((short) nextCameraX);
    }

    private void clearPostBossCameraEndpointControls(PlayableEntity player) {
        if (player instanceof AbstractPlayableSprite sprite) {
            sprite.setControlLocked(false);
            sprite.setForcedInputMask(0);
        }
        var services = tryServices();
        if (services == null) {
            return;
        }
        try {
            if (services.playerQuery().nativeP2OrNull() instanceof AbstractPlayableSprite sidekick) {
                sidekick.setControlLocked(false);
            }
        } catch (RuntimeException ignored) {
            // Partial object-unit harnesses may not provide player-query services.
        }
    }

    private void updateWaitForCapsuleResultsFlag() {
        var services = tryServices();
        if (services == null || services.gameState() == null || !services.gameState().isEndOfLevelFlag()) {
            return;
        }
        startPostCapsuleEscapeSetup();
    }

    private void startPostCapsuleEscapeSetup() {
        finalDefeatPhase = FINAL_PHASE_WAIT_ROBOTNIK_SHIP_TIMER;
        finalHitHandoffFlag = false;
        var services = tryServices();
        if (services != null && services.camera() != null) {
            int cameraX = Short.toUnsignedInt(services.camera().getX());
            int cameraY = Short.toUnsignedInt(services.camera().getY());
            state.x = (cameraX - 0x40) & 0xFFFF;
            state.y = (cameraY + 0x40) & 0xFFFF;
            state.xFixed = state.x << 16;
            state.yFixed = state.y << 16;
            services.camera().setMaxXTarget((short) POST_CAPSULE_ESCAPE_CAMERA_TARGET_X);
        }
        int levelMusic = resolveLevelMusicId();
        if (levelMusic >= 0 && services != null) {
            services.playMusic(levelMusic);
        }
        lockPostCapsuleNativePlayersUp();
        setCustomFlag(TIMER_OFFSET, ROBOTNIK_SHIP_WAIT_FRAMES);
        signalShipTransitionEvent();
        setCustomFlag(STATE_FLAGS_OFFSET, getCustomFlag(STATE_FLAGS_OFFSET) & ~0x20);
        spawnChild(() -> new MhzEndBossRobotnikHeadChild(this));
        ensureStandaloneArtLoaded(Sonic3kObjectArtKeys.ROBOTNIK_SHIP);
    }

    private void signalShipTransitionEvent() {
        var services = tryServices();
        if (services != null && services.zoneRuntimeState() instanceof MhzZoneRuntimeState state) {
            state.signalShipTransition();
        }
    }

    private void lockPostCapsuleNativePlayersUp() {
        var services = tryServices();
        if (services == null) {
            return;
        }
        try {
            lockPostCapsulePlayerUp(services.playerQuery().mainPlayerOrNull());
            lockPostCapsulePlayerUp(services.playerQuery().nativeP2OrNull());
        } catch (RuntimeException ignored) {
            // Partial object-unit harnesses may not provide player-query services.
        }
    }

    private void lockPostCapsulePlayerUp(PlayableEntity player) {
        if (player instanceof AbstractPlayableSprite sprite) {
            sprite.setControlLocked(true);
            sprite.setForcedInputMask(AbstractPlayableSprite.INPUT_UP);
        }
    }

    private void updateRobotnikShipTimer() {
        int timer = getCustomFlag(TIMER_OFFSET) - 1;
        setCustomFlag(TIMER_OFFSET, timer);
        if (timer < 0) {
            startRobotnikShipEscape();
        }
    }

    private void startRobotnikShipEscape() {
        finalDefeatPhase = FINAL_PHASE_ROBOTNIK_SHIP_ESCAPE;
        setCustomFlag(STATE_FLAGS_OFFSET, getCustomFlag(STATE_FLAGS_OFFSET) & ~0x10);
        state.renderFlags |= 1;
        state.xVel = 0x400;
        state.yVel = 0;
        spawnChild(() -> new MhzEndBossRobotnikShipFlameInstance(this));
    }

    private void updateRobotnikShipEscape() {
        services().playSfx(Sonic3kSfx.ROBOTNIK_SIREN.id);
        moveSpriteCustomGravity(resolveRobotnikShipEscapeGravity());
        int levelRepeatOffset = mhzLevelRepeatOffset();
        var services = tryServices();
        if (services == null || services.camera() == null) {
            applyLevelRepeatOffset(levelRepeatOffset);
            return;
        }
        int cameraThresholdY = (Short.toUnsignedInt(services.camera().getY()) - 0x40) & 0xFFFF;
        if (Integer.compareUnsigned(cameraThresholdY, state.y & 0xFFFF) < 0) {
            applyLevelRepeatOffset(levelRepeatOffset);
            return;
        }
        finalDefeatPhase = FINAL_PHASE_ROBOTNIK_SHIP_CAMERA_FLAG_SET;
        setCustomFlag(STATE_FLAGS_OFFSET, getCustomFlag(STATE_FLAGS_OFFSET) | 0x20);
        spawnFreeChild(MhzEndBossSidekickLockChild::new);
        applyLevelRepeatOffset(levelRepeatOffset);
    }

    private void updateRobotnikShipCameraFlagSet(PlayableEntity player) {
        if (player == null) {
            return;
        }
        if (Integer.compareUnsigned(Short.toUnsignedInt(player.getCentreX()), 0x4778) < 0) {
            forceWalkoffRight(player);
            return;
        }
        finalDefeatPhase = FINAL_PHASE_PLAYER_STOPPED_FOR_WALKOFF;
        stopPlayerForWalkoff(player);
    }

    private void forceWalkoffRight(PlayableEntity player) {
        if (player instanceof AbstractPlayableSprite sprite) {
            sprite.setForcedInputMask(AbstractPlayableSprite.INPUT_RIGHT);
        }
    }

    private void stopPlayerForWalkoff(PlayableEntity player) {
        player.setXSpeed((short) 0);
        player.setYSpeed((short) 0);
        player.setGSpeed((short) 0);
        if (player instanceof AbstractPlayableSprite sprite) {
            sprite.setForcedInputMask(0);
        }
    }

    private void updatePlayerStoppedForWalkoff(PlayableEntity player) {
        if (isShipControllerSignalFlagSet()) {
            startPlayerWalkoffLaunch(player);
            return;
        }
        if (player instanceof AbstractPlayableSprite sprite) {
            sprite.setForcedInputMask(AbstractPlayableSprite.INPUT_UP);
        }
    }

    private boolean isShipControllerSignalFlagSet() {
        var services = tryServices();
        return services != null
                && services.zoneRuntimeState() instanceof MhzZoneRuntimeState state
                && state.isShipControllerSignalFlagSet();
    }

    private void startPlayerWalkoffLaunch(PlayableEntity player) {
        finalDefeatPhase = FINAL_PHASE_PLAYER_WALKOFF_LAUNCH;
        if (player == null) {
            return;
        }
        player.setXSpeed((short) 0x200);
        if (player instanceof AbstractPlayableSprite sprite) {
            sprite.setForcedInputMask(AbstractPlayableSprite.INPUT_JUMP);
        }
    }

    private void updatePlayerWalkoffLaunch(PlayableEntity player) {
        if (!(player instanceof AbstractPlayableSprite sprite)) {
            return;
        }
        sprite.setForcedInputMask(AbstractPlayableSprite.INPUT_JUMP);
        if (player.getYSpeed() < 0) {
            return;
        }
        startPlayerGrabWait(sprite);
    }

    private void startPlayerGrabWait(AbstractPlayableSprite player) {
        finalDefeatPhase = FINAL_PHASE_PLAYER_GRAB_WAIT;
        player.setXSpeed((short) 0);
        player.setYSpeed((short) 0);
        player.setGSpeed((short) 0);
        ObjectControlState.nativeBit7FullControl().applyTo(player);
        player.setAnimationId(Sonic3kAnimationIds.HANG);
        player.setDirection(Direction.LEFT);
        setCustomFlag(TIMER_OFFSET, 0x5F);
        services().playSfx(Sonic3kSfx.GRAB.id);
        spawnFreeChild(MhzEndBossPlayerTwoCarryChild::new);
        updatePlayerGrabWait();
    }

    private void updatePlayerGrabWait() {
        int timer = getCustomFlag(TIMER_OFFSET) - 1;
        setCustomFlag(TIMER_OFFSET, timer);
        if (timer < 0) {
            setDestroyed(true);
            services().requestZoneAndAct(FINAL_TRANSITION_ZONE, FINAL_TRANSITION_ACT, true);
        }
    }

    private int resolveRobotnikShipEscapeGravity() {
        var services = tryServices();
        if (services == null || services.camera() == null) {
            return 0;
        }
        int cameraThresholdX = (Short.toUnsignedInt(services.camera().getX()) + 0x80) & 0xFFFF;
        if (Integer.compareUnsigned(cameraThresholdX, state.x & 0xFFFF) >= 0) {
            return 0;
        }
        return -0x10;
    }

    private int mhzLevelRepeatOffset() {
        var services = tryServices();
        if (services == null || !(services.zoneRuntimeState() instanceof MhzZoneRuntimeState state)) {
            return 0;
        }
        return state.levelRepeatOffset();
    }

    private void applyLevelRepeatOffset(int levelRepeatOffset) {
        if (levelRepeatOffset == 0) {
            return;
        }
        state.x = (state.x - levelRepeatOffset) & 0xFFFF;
        state.xFixed = state.x << 16;
    }

    private void moveSpriteCustomGravity(int gravity) {
        state.xFixed += state.xVel << 8;
        state.x = state.xFixed >> 16;
        int previousYVelocity = state.yVel;
        state.yVel += gravity;
        state.yFixed += previousYVelocity << 8;
        state.y = state.yFixed >> 16;
    }

    private int resolveLevelMusicId() {
        try {
            return services().getCurrentLevelMusicId();
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private void signalEndBossWalkoffPrepEvent() {
        var services = tryServices();
        if (services != null && services.zoneRuntimeState() instanceof MhzZoneRuntimeState state) {
            state.signalEndBossWalkoffPrep();
        }
    }

    /**
     * ROM: {@code lea Pal_MHZEndBoss(pc),a1 / jsr PalLoad_Line1} during
     * {@code Obj_MHZEndBoss} setup (sonic3k.asm:156905-156906). S&K-side ROM
     * offset 0x0769D4 verified by RomOffsetFinder search-rom.
     */
    private void loadBossPalette() {
        if (paletteLoaded) {
            return;
        }
        try {
            byte[] line = services().rom().readBytes(Sonic3kConstants.PAL_MHZ_END_BOSS_ADDR, 32);
            S3kPaletteWriteSupport.applyLine(
                    services().paletteOwnershipRegistryOrNull(),
                    services().currentLevel(),
                    services().graphicsManager(),
                    S3kPaletteOwners.MHZ_END_BOSS,
                    S3kPaletteOwners.PRIORITY_OBJECT_OVERRIDE,
                    1,
                    line);
            paletteLoaded = true;
        } catch (Exception ignored) {
            // Palette loading is best-effort for partial object-unit harnesses.
        }
    }

    @Override
    public void onPlayerAttack(PlayableEntity player, com.openggf.level.objects.TouchResponseResult result) {
        int previousHitCount = state.hitCount;
        boolean wasInvulnerable = state.invulnerable;
        boolean wasDefeated = state.defeated;
        super.onPlayerAttack(player, result);
        if (!wasInvulnerable && !wasDefeated && state.hitCount < previousHitCount && player != null) {
            player.setXSpeed((short) 0);
        }
    }

    @Override
    protected int getCollisionSizeIndex() {
        return COLLISION_SIZE;
    }

    @Override
    protected boolean usesDefeatSequencer() {
        return false;
    }

    @Override
    protected boolean usesBaseHitHandler() {
        return false;
    }

    private void updateMhzHitFlash() {
        if (!state.invulnerable) {
            return;
        }
        // ROM loc_767E8: btst #0,$20(a0) reads the countdown BEFORE it is decremented below.
        // Bit0 clear (even) selects the white/flash row; bit0 set (odd) selects the real-color
        // row, so the final write (at $20==1, odd) restores real colors as $20 underflows to 0.
        int flashSet = (state.invulnerabilityTimer & 1) == 0 ? 1 : 0;
        S3kPaletteWriteSupport.applyColors(
                services().paletteOwnershipRegistryOrNull(),
                services().currentLevel(),
                services().graphicsManager(),
                S3kPaletteOwners.MHZ_END_BOSS,
                S3kPaletteOwners.PRIORITY_OBJECT_OVERRIDE,
                1,
                HIT_FLASH_COLOR_INDICES,
                HIT_FLASH_WORDS[flashSet]);
        state.invulnerabilityTimer--;
        if (state.invulnerabilityTimer <= 0) {
            state.invulnerable = false;
        }
    }

    @Override
    protected int getBossHitSfxId() {
        return Sonic3kSfx.BOSS_HIT.id;
    }

    @Override
    protected int getBossExplosionSfxId() {
        return Sonic3kSfx.EXPLODE.id;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.MHZ_END_BOSS);
        if (renderer == null) {
            return;
        }
        renderer.drawFrameIndex(ACTIVE_CORE_MAPPING_FRAME, state.x, state.y, false, false);
    }

    @Override
    public int getPriorityBucket() {
        return priorityBucket;
    }

    @Override
    public int getOnScreenHalfWidth() {
        return ACTIVE_CORE_RENDER_HALF_WIDTH;
    }

    @Override
    public int getOnScreenHalfHeight() {
        return ACTIVE_CORE_RENDER_HALF_HEIGHT;
    }

    @Override
    public boolean isHighPriority() {
        return highPriority;
    }

    private static final class MhzEndBossSidekickLockChild extends AbstractObjectInstance
            implements ZeroArgRewindRecreatable {
        private boolean lockIssued;

        private MhzEndBossSidekickLockChild() {
            super(new ObjectSpawn(0, 0, Sonic3kObjectIds.MHZ_END_BOSS, 0, 0, false, 0),
                    "MHZEndBossSidekickLock");
        }

        @Override
        public int getX() {
            return 0;
        }

        @Override
        public int getY() {
            return 0;
        }

        @Override
        public boolean isPersistent() {
            return true;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity player) {
            AbstractPlayableSprite sidekick = nativeSidekickOrNull();
            if (sidekick == null) {
                setDestroyed(true);
                return;
            }
            if (!lockIssued) {
                sidekick.setControlLocked(true);
                lockIssued = true;
            }
            if (sidekick.isControlLocked()) {
                sidekick.setForcedInputMask(0);
                return;
            }
            sidekick.setForcedInputMask(0);
            setDestroyed(true);
        }

        private AbstractPlayableSprite nativeSidekickOrNull() {
            try {
                PlayableEntity sidekick = services().playerQuery().nativeP2OrNull();
                if (sidekick instanceof AbstractPlayableSprite sprite) {
                    return sprite;
                }
            } catch (RuntimeException ignored) {
                // Partial object-unit harnesses may not provide player-query services.
            }
            return null;
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            // Invisible controller for shared ROM helper loc_863C0.
        }
    }

    private static final class MhzEndBossWalkoffPrepChild extends AbstractObjectInstance
            implements RewindRecreatable {
        private static final int WALKOFF_PREP_X = 0x4600;

        private MhzEndBossInstance parent;
        private boolean forcingWalkoff;

        private MhzEndBossWalkoffPrepChild(MhzEndBossInstance parent) {
            super(new ObjectSpawn(0, 0, Sonic3kObjectIds.MHZ_END_BOSS, 0, 0, false, 0),
                    "MHZEndBossWalkoffPrep");
            this.parent = parent;
        }

        @Override
        public MhzEndBossWalkoffPrepChild recreateForRewind(RewindRecreateContext ctx) {
            return new MhzEndBossWalkoffPrepChild(
                    RewindRecreateObjectLinks.nearestLiveObject(ctx, MhzEndBossInstance.class));
        }

        @Override
        public int getX() {
            return 0;
        }

        @Override
        public int getY() {
            return 0;
        }

        @Override
        public boolean isPersistent() {
            return true;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity player) {
            if (parent.finalHitHandoffFlag || !(player instanceof AbstractPlayableSprite sprite)) {
                return;
            }
            if (!forcingWalkoff) {
                forcingWalkoff = true;
                sprite.setDirection(Direction.RIGHT);
                sprite.setRolling(false);
                sprite.setControlLocked(true);
                parent.signalEndBossWalkoffPrepEvent();
            }
            if (Integer.compareUnsigned(Short.toUnsignedInt(sprite.getCentreX()), WALKOFF_PREP_X) < 0) {
                sprite.setForcedInputMask(AbstractPlayableSprite.INPUT_RIGHT);
                return;
            }
            NativePositionOps.writeXPosPreserveSubpixel(sprite, WALKOFF_PREP_X);
            sprite.setXSpeed((short) 0);
            sprite.setYSpeed((short) 0);
            sprite.setGSpeed((short) 0);
            sprite.setForcedInputMask(0);
            setDestroyed(true);
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            // Invisible controller for ROM loc_768B6/loc_768FE.
        }
    }

    private static final class MhzEndBossPlayerTwoCarryChild extends AbstractObjectInstance
            implements ZeroArgRewindRecreatable {
        private boolean initialized;

        private MhzEndBossPlayerTwoCarryChild() {
            super(new ObjectSpawn(0, 0, Sonic3kObjectIds.MHZ_END_BOSS, 0, 0, false, 0),
                    "MHZEndBossP2Carry");
        }

        @Override
        public int getX() {
            return 0;
        }

        @Override
        public int getY() {
            return 0;
        }

        @Override
        public boolean isPersistent() {
            return true;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity player) {
            if (!(services().playerQuery().nativeP2OrNull() instanceof AbstractPlayableSprite sidekick)) {
                return;
            }
            if (!initialized) {
                initialized = true;
                ObjectControlState.nativeBit7FullControl().applyTo(sidekick);
                sidekick.setAnimationId(Sonic3kAnimationIds.FLY);
                sidekick.setDirection(Direction.RIGHT);
                return;
            }
            NativePositionOps.addXPosPreserveSubpixel(sidekick, 1);
            NativePositionOps.addYPosPreserveSubpixel(sidekick, -1);
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            // Invisible controller for ROM loc_7646E/loc_76492.
        }
    }
}
