package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.S3kPaletteOwners;
import com.openggf.game.sonic3k.S3kPaletteWriteSupport;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.audio.Sonic3kMusic;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.constants.Sonic3kAnimationIds;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.graphics.GLCommand;
import com.openggf.level.Palette;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.SpawnCoordinateZeroScalarArgsRewindRecreatable;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.objects.SplashObjectInstance;
import com.openggf.level.objects.TouchActorContextPolicy;
import com.openggf.level.objects.TouchAttackBouncePolicy;
import com.openggf.level.objects.TouchCategoryDecodeMode;
import com.openggf.level.objects.TouchOverlapStopPolicy;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.objects.TouchResponseProfile;
import com.openggf.level.objects.TouchResponseResult;
import com.openggf.level.objects.TouchShieldDeflectCapability;
import com.openggf.level.objects.boss.AbstractBossInstance;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.ObjectTerrainUtils;
import com.openggf.physics.TerrainCheckResult;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.ObjectControlState;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Hydrocity Act 1 miniboss (object 0x99).
 *
 * <p>The implementation keeps the encounter self-contained: ROM trigger bounds,
 * camera lock, PLC-loaded art, multi-region hurt boxes, a drop/rise/dive attack
 * loop with orbiting rockets, the suction phase, custom palette flashing, and
 * a signpost-style defeat handoff.
 */
public class HczMinibossInstance extends AbstractBossInstance implements SpawnRewindRecreatable {
    private static final Logger LOG = Logger.getLogger(HczMinibossInstance.class.getName());

    private static final int ROUTINE_INIT = 0;
    private static final int ROUTINE_WAIT_TRIGGER = 2;
    private static final int ROUTINE_WAIT_FADE = 4;
    private static final int ROUTINE_DESCEND = 6;
    private static final int ROUTINE_WAIT = 8;
    private static final int ROUTINE_RISE = 10;
    private static final int ROUTINE_DIVE = 12;
    private static final int ROUTINE_STRAFE = 14;
    private static final int ROUTINE_ASCEND = 16;
    private static final int ROUTINE_PRE_VORTEX_DRIFT = 18;
    private static final int ROUTINE_VORTEX = 20;
    private static final int ROUTINE_COOLDOWN = 22;
    private static final int ROUTINE_SLOW_RISE = 24;
    private static final int ROUTINE_DEFEATED = 26;

    private static final int HIT_COUNT = 6;
    private static final int COLLISION_SIZE = 0x0F;
    private static final int CORE_COLLISION_FLAGS = COLLISION_SIZE;
    private static final int ENGINE_COLLISION_FLAGS = 0x92;
    private static final int ROCKET_COLLISION_FLAGS = 0x8B;
    private static final int INVULN_TIME = 0x20;
    private static final TouchResponseProfile SINGLE_REGION_TOUCH_PROFILE =
            TouchResponseProfile.standardEnemy();
    private static final TouchResponseProfile MULTI_REGION_TOUCH_PROFILE = new TouchResponseProfile(
            TouchCategoryDecodeMode.NORMAL,
            false,
            true,
            true,
            TouchShieldDeflectCapability.NONE,
            0,
            TouchAttackBouncePolicy.STANDARD_ENEMY_KILL,
            TouchActorContextPolicy.MAIN_FULL_SIDEKICK_HURT_ONLY,
            TouchOverlapStopPolicy.STOP_AFTER_FIRST_OVERLAP_FOR_MAIN_ONLY);

    private static final int TRIGGER_MIN_Y = 0x300;
    private static final int TRIGGER_MAX_Y = 0x400;
    private static final int TRIGGER_MIN_X = 0x3500;
    private static final int TRIGGER_MAX_X = 0x3700;
    private static final int ARENA_LOCK_X = 0x3680;
    private static final int ARENA_LOCK_Y = 0x638;
    private static final int FADE_OUT_TIME = 2 * 60;

    private static final int DESCEND_VEL = 0x100;
    private static final int DESCEND_TIME = 0xDF;
    private static final int WAIT_TIME = 60 - 1;
    private static final int RISE_VEL = -0x400;
    private static final int RISE_TIME = 0x37;
    private static final int DIVE_VEL = 0x400;
    private static final int DIVE_TIME = 0x47;
    private static final int STRAFE_TIME = 0x2F;
    private static final int VORTEX_TIME = 0x17F;
    private static final int COOLDOWN_TIME = 0x7F;
    private static final int REOPEN_TIME = 0x3F;

    private static final int ENGINE_OFFSET_Y = 0x24;
    private static final int ENGINE_FRAME = 0x15;
    private static final int WATER_EFFECT_OFFSET_Y = 0x148;
    private static final int WATER_EFFECT_BASE_FRAME = 0x16;
    private static final int WATER_EFFECT_ROUTINE_IDLE = 4;
    private static final int WATER_EFFECT_ROUTINE_WINDUP = 6;
    private static final int WATER_EFFECT_ROUTINE_PULL = 8;
    private static final int WATER_EFFECT_ROUTINE_COOLDOWN = 10;
    private static final int WATER_EFFECT_CALLBACK_COMMAND = 0xF4;
    private static final int[] WATER_EFFECT_WINDUP_SCRIPT = {
            0x16, 7, 0x17, 7, 0x18, 7,
            0x16, 6, 0x17, 6, 0x18, 6,
            0x16, 5, 0x17, 5, 0x18, 5,
            0x16, 4, 0x17, 4, 0x18, 4,
            0x16, 3, 0x17, 3, 0x18, 3,
            0x16, 2, 0x17, 2, 0x18, 2,
            WATER_EFFECT_CALLBACK_COMMAND
    };
    private static final int[] WATER_EFFECT_PULL_SCRIPT = {1, 0x16, 0x17, 0x18, 0xFC};
    private static final int FLOOR_CHECK_RADIUS = 0x28;
    private static final int ROCKET_PHASE_STEP = 4;
    private static final int VORTEX_PHASE_STEP = 2;
    private static final int VORTEX_PULL_X = 2;
    private static final int VORTEX_PULL_Y = 1;
    private static final int PRE_VORTEX_DRIFT_VEL = 0x180;
    private static final int PRE_VORTEX_DRIFT_GRAVITY = 0x20;
    private static final int SLOW_RISE_VEL = -0x20;
    private static final int VORTEX_APPROACH_Y = 0x108;
    private static final int CONTINUOUS_SFX_INTERVAL = 16;
    /**
     * ROM {@code BossDefeated}: {@code move.w #$3F,$2E(a0)}
     * (docs/skdisasm/sonic3k.asm:180821), reached from {@code loc_6ACA6}'s tail-jump through
     * {@code BossDefeated_StopTimer} (:140594-140599).
     *
     * <p>This was {@code REOPEN_TIME + 1}. Both halves of that were wrong and they cancelled:
     * the {@code +1} compensated for the defeated arm being dispatched on the install frame,
     * and {@code REOPEN_TIME} is the miniboss's <em>reopen</em> delay - an unrelated ROM
     * quantity that merely happens to share {@code $3F}, so the value looked sourced when it
     * was fitted.
     */
    private static final int BOSS_DEFEATED_WAIT = 0x3F;


    private static final int[][] ATTACK_PATTERNS = {
            {0x40, 1},
            {0x100, 1},
            {0x40, 0},
            {0x40, 1},
            {0x100, 0},
            {0x100, 1},
            {0x40, 0},
            {0x100, 0}
    };

    private static final int[] ROCKET_FRAMES = {
            0x01, 0x02, 0x03, 0x04,
            0x05, 0x06, 0x07, 0x08,
            0x09, 0x0A, 0x0B, 0x1A,
            0x1A, 0x1A, 0x0C, 0x0D
    };

    private static final int[] ENGINE_FRAMES = {
            0x0E, 0x0F, 0x10, 0x11,
            0x12, 0x11, 0x10, 0x0F,
            0x0E, 0x1A, 0x1A, 0x1A,
            0x0E, 0x0E, 0x1A, 0x1A
    };

    private static final int[] ENGINE_CHILD_OFFSETS = {
            3, 3,
            0, 0,
            6, 6,
            0x0C, 0x0C,
            0x12, 0x12,
            0x0C, 0x0C,
            8, 8,
            0, 0,
            3, 3,
            0, 0,
            0, 0,
            0, 0,
            -6, -6,
            -0x0A, -0x0A,
            0, 0,
            0, 0
    };

    private static final int[] ENGINE_CHILD_PRIORITIES = {
            0x280, 0x200, 0x200, 0x200,
            0x200, 0x180, 0x180, 0x180,
            0x180, 0x180, 0x180, 0x280,
            0x280, 0x280, 0x280, 0x280
    };

    /**
     * HCZMiniboss_RocketTwistLookup: 128-entry signed byte table used by sub_489BA.
     * For phase 0x00-0x7F, index directly. For 0x80-0xFF, mirror: index = 0xFF - phase.
     * The same table is used for BOTH X and Y offsets (not sin/cos).
     */
    private static final byte[] ROCKET_TWIST_LOOKUP = {
            0x18, 0x18, 0x18, 0x18, 0x18, 0x18, 0x18, 0x18, 0x18, 0x17, 0x17, 0x17, 0x17, 0x17, 0x17, 0x16,
            0x16, 0x16, 0x16, 0x15, 0x15, 0x15, 0x15, 0x14, 0x14, 0x14, 0x13, 0x13, 0x13, 0x12, 0x12, 0x11,
            0x11, 0x11, 0x10, 0x10, 0x0F, 0x0F, 0x0E, 0x0E, 0x0D, 0x0D, 0x0C, 0x0C, 0x0B, 0x0B, 0x0A, 0x0A,
            0x09, 0x09, 0x08, 0x08, 0x07, 0x06, 0x06, 0x05, 0x05, 0x04, 0x04, 0x03, 0x02, 0x02, 0x01, 0x01,
            0x00, -0x01, -0x01, -0x02, -0x02, -0x03, -0x04, -0x04, -0x05, -0x05, -0x06, -0x06, -0x07, -0x08, -0x08, -0x09,
            -0x09, -0x0A, -0x0A, -0x0B, -0x0B, -0x0C, -0x0C, -0x0D, -0x0D, -0x0E, -0x0E, -0x0F, -0x0F, -0x10, -0x10, -0x11,
            -0x11, -0x11, -0x12, -0x12, -0x13, -0x13, -0x13, -0x14, -0x14, -0x14, -0x15, -0x15, -0x15, -0x15, -0x16, -0x16,
            -0x16, -0x16, -0x17, -0x17, -0x17, -0x17, -0x17, -0x17, -0x18, -0x18, -0x18, -0x18, -0x18, -0x18, -0x18, -0x18
    };

    private static final int[] FLASH_INDICES = {4, 7, 9, 10, 11, 13, 14};
    private static final int[] FLASH_DARK = {0x0004, 0x0000, 0x000C, 0x0008, 0x0020, 0x0826, 0x0624};
    private static final int[] FLASH_BRIGHT = {0x0AAA, 0x0AAA, 0x0888, 0x0AAA, 0x0EEE, 0x0888, 0x0AAA};

    private RocketState[] rockets;
    private RocketTouchChild[] rocketTouchChildren;

    private int anchorY;
    private int waterLevelY;
    private int waitTimer = -1;
    private WaitCallback waitCallback = WaitCallback.NONE;
    private int ascendTargetY;
    private AscendCallback ascendCallback = AscendCallback.NONE;
    private int attackPatternIndex;
    private int storedHorizontalVelocity;
    private int passCounter;
    private boolean closedBody;
    private boolean arenaYLocked;
    private boolean arenaXLocked;
    private boolean customFlashDirty;
    private boolean vortexActive;
    private boolean defeatRenderComplete;
    private boolean crossedWaterThisPass;
    private boolean waterPaletteLoaded;
    private int waterEffectRoutine;
    private int waterEffectFrame;
    private int waterEffectAnimFrame;
    private int waterEffectAnimTimer;
    private boolean waterEffectPullReady;
    private boolean vortexFinalPullPending;
    private boolean vortexTrackedP1;
    private boolean vortexTrackedP2;
    private int defeatHandoffTimer;
    private boolean defeatHandoffStarted;
    /**
     * Consumes the dispatch that installed the defeat handler.
     *
     * <p>{@code Obj_HCZ_MinibossLoop} reads {@code routine(a0)} at its head, {@code jsr}s the
     * selected arm and only then {@code bsr.w sub_6AC48}
     * (docs/skdisasm/sonic3k.asm:139296-139302); when {@code collision_property} has reached
     * zero, {@code loc_6ACA6} installs {@code Wait_FadeToLevelMusic} into {@code (a0)}
     * (:140594-140599). The dispatcher has already called this slot's handler for the frame,
     * so that wait's first decrement belongs to the next object pass and the killing frame
     * ends at {@code $3F}.
     */
    private boolean pendingDefeatDispatch;
    private int lastVIntRunCount;
    private int lastHitFrame = -1;
    private int lastHitRoutine = -1;
    private int lastHitWaitTimer = -1;
    private int lastHitWaterEffectRoutine = -1;
    private String lastHitSource = "none";
    private List<VortexBubbleChild> vortexBubbles;
    private S3kBossExplosionController defeatExplosionController;

    private enum WaitCallback {
        NONE,
        START_FIGHT,
        FINISH_DESCENT,
        START_RISE,
        FINISH_RISE,
        SELECT_ATTACK_PATTERN,
        FINISH_STRAFE,
        START_VORTEX_WINDUP,
        BEGIN_VORTEX_SEQUENCE,
        END_VORTEX,
        START_POST_VORTEX_PAUSE,
        START_SLOW_RISE_FROM_VORTEX,
        START_ASCEND_TO_ANCHOR
    }

    private enum AscendCallback {
        NONE,
        START_PRE_VORTEX_DRIFT,
        START_DIVE,
        SELECT_ATTACK_PATTERN
    }

    private enum RocketSpeedCallback {
        NONE,
        WIND_UP_TO_ROUTINE_8,
        WIND_UP_ARM_SPEED_2,
        WIND_UP_TO_FULL_SPEED,
        WIND_DOWN_TO_SPEED_1,
        WIND_DOWN_RETURN_TO_INIT
    }

    private static final class RocketState implements com.openggf.game.rewind.RewindStateful<RocketState.RewindState> {
        private final int subtype;
        private int phaseX;
        private int phaseY;
        private int x;
        private int y;
        private int frame;
        private boolean front;
        private boolean hFlip;
        private boolean collisionArmed;
        private int routine;
        private int speed;
        private int timer;
        private RocketSpeedCallback callback = RocketSpeedCallback.NONE;

        private record RewindState(int phaseX, int phaseY, int x, int y, int frame,
                                   boolean front, boolean hFlip, boolean collisionArmed,
                                   int routine, int speed, int timer, RocketSpeedCallback callback) {}

        private RocketState(int subtype) {
            this.subtype = subtype;
            this.hFlip = subtype >= 4;
            this.frame = 1;
        }

        @Override
        public RewindState captureRewindStateValue() {
            return new RewindState(phaseX, phaseY, x, y, frame, front, hFlip, collisionArmed,
                    routine, speed, timer, callback);
        }

        @Override
        public void restoreRewindStateValue(RewindState state) {
            phaseX = state.phaseX();
            phaseY = state.phaseY();
            x = state.x();
            y = state.y();
            frame = state.frame();
            front = state.front();
            hFlip = state.hFlip();
            collisionArmed = state.collisionArmed();
            routine = state.routine();
            speed = state.speed();
            timer = state.timer();
            callback = state.callback();
        }
    }

    public HczMinibossInstance(ObjectSpawn spawn) {
        super(spawn, "HCZMiniboss");
    }

    @Override
    protected void initializeBossState() {
        ensureRocketState();
        anchorY = spawn.y();
        waterLevelY = anchorY + 0x100;
        state.routine = ROUTINE_INIT;
        state.hitCount = HIT_COUNT;
        state.renderFlags = 0;
        attackPatternIndex = 0;
        storedHorizontalVelocity = 0;
        passCounter = 0;
        closedBody = false;
        waitTimer = -1;
        waitCallback = WaitCallback.NONE;
        ascendTargetY = anchorY;
        ascendCallback = AscendCallback.NONE;
        arenaYLocked = false;
        arenaXLocked = false;
        customFlashDirty = false;
        vortexActive = false;
        defeatRenderComplete = false;
        crossedWaterThisPass = false;
        waterPaletteLoaded = false;
        waterEffectRoutine = WATER_EFFECT_ROUTINE_IDLE;
        waterEffectFrame = WATER_EFFECT_BASE_FRAME;
        waterEffectAnimFrame = 0;
        waterEffectAnimTimer = 0;
        waterEffectPullReady = false;
        vortexFinalPullPending = false;
        vortexTrackedP1 = false;
        vortexTrackedP2 = false;
        defeatHandoffTimer = -1;
        defeatHandoffStarted = false;
        pendingDefeatDispatch = false;
        lastHitFrame = -1;
        lastHitRoutine = -1;
        lastHitWaitTimer = -1;
        lastHitWaterEffectRoutine = -1;
        lastHitSource = "none";
        vortexBubbles = new ArrayList<>();
        defeatExplosionController = null;
        rocketTouchChildren = null;
        resetRocketPhases();
    }

    @Override
    protected int getInitialHitCount() {
        return HIT_COUNT;
    }

    @Override
    protected void onHitTaken(int remainingHits) {
        customFlashDirty = true;
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
    protected int getInvulnerabilityDuration() {
        return INVULN_TIME;
    }

    @Override
    protected int getPaletteLineForFlash() {
        return -1;
    }

    @Override
    public int getCollisionFlags() {
        return getCoreCollisionFlags();
    }

    @Override
    public TouchResponseProvider.TouchRegion[] getMultiTouchRegions() {
        if (!isFightVisible() || state.defeated) {
            return null;
        }

        List<TouchResponseProvider.TouchRegion> regions = new ArrayList<>(2);
        int coreFlags = getCoreCollisionFlags();
        if (coreFlags != 0) {
            regions.add(new TouchResponseProvider.TouchRegion(state.x, state.y, coreFlags));
        }

        if (!closedBody) {
            regions.add(new TouchResponseProvider.TouchRegion(
                    state.x, state.y + ENGINE_OFFSET_Y, ENGINE_COLLISION_FLAGS));
        }
        return regions.toArray(new TouchResponseProvider.TouchRegion[0]);
    }

    @Override
    public TouchResponseProfile getTouchResponseProfile() {
        return getTouchResponseProfile(getMultiTouchRegions() != null);
    }

    @Override
    public TouchResponseProfile getTouchResponseProfile(boolean multiRegionSource) {
        return multiRegionSource ? MULTI_REGION_TOUCH_PROFILE : SINGLE_REGION_TOUCH_PROFILE;
    }

    @Override
    public void onPlayerAttack(PlayableEntity playerEntity, TouchResponseResult result) {
        if (getCoreCollisionFlags() == 0 || state.invulnerable || state.defeated) {
            return;
        }

        state.hitCount--;
        lastHitFrame = services().objectManager() != null ? services().objectManager().getFrameCounter() : -1;
        lastHitRoutine = state.routine;
        lastHitWaitTimer = waitTimer;
        lastHitWaterEffectRoutine = waterEffectRoutine;
        lastHitSource = describeHitSource(playerEntity, result);
        state.invulnerabilityTimer = INVULN_TIME;
        state.invulnerable = true;
        paletteFlasher.startFlash();
        services().playSfx(Sonic3kSfx.BOSS_HIT.id);
        customFlashDirty = true;
        onHitTaken(state.hitCount);

        if (state.hitCount <= 0) {
            state.hitCount = 0;
            state.defeated = true;
            services().gameState().addScore(1000);
            onDefeatStarted();
        }
    }

    @Override
    protected void onDefeatStarted() {
        state.routine = ROUTINE_DEFEATED;
        state.xVel = 0;
        state.yVel = 0;
        waitTimer = -1;
        waitCallback = WaitCallback.NONE;
        vortexActive = false;
        waterEffectRoutine = WATER_EFFECT_ROUTINE_IDLE;
        waterEffectAnimFrame = 0;
        waterEffectAnimTimer = 0;
        waterEffectPullReady = false;
        vortexFinalPullPending = false;
        // Touch_Enemy sets the defeated status after Obj_HCZ_MinibossLoop has
        // already run. On the following object pass sub_6AC48 installs
        // Wait_FadeToLevelMusic, which counts the retained $3F wait before
        // loc_6A22A calls Obj_EndSignControl (sonic3k.asm:140574-140594,
        // 179651-179669,180372-180379). The explosion controller is a separate
        // child and must not gate this parent handoff.
        // Touch_Enemy runs from Draw_And_Touch_Sprite after the boss routine
        // dispatch, so Wait_FadeToLevelMusic's first decrement is necessarily
        // on the following object pass (sonic3k.asm:139242-139249,20900-20925).
        defeatHandoffTimer = BOSS_DEFEATED_WAIT;
        defeatHandoffStarted = false;
        pendingDefeatDispatch = true;
        state.invulnerable = false;
        state.invulnerabilityTimer = 0;
        loadBossPalette();
        defeatExplosionController = new S3kBossExplosionController(state.x, state.y, 0);
        services().fadeOutMusic();
        services().gameState().setCurrentBossId(0);
        // ROM loc_6ACA6: jmp (BossDefeated_StopTimer).l (sonic3k.asm:140649).
        stopLevelTimerOnBossDefeat();
    }

    @Override
    protected void updateBossLogic(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = playerEntity instanceof AbstractPlayableSprite aps ? aps : null;
        lastVIntRunCount = vIntRunCount;
        updateWaterLevel();
        ensureWaterEffectPalette();

        switch (state.routine) {
            case ROUTINE_INIT -> updateInit();
            case ROUTINE_WAIT_TRIGGER -> updateWaitTrigger();
            case ROUTINE_WAIT_FADE, ROUTINE_WAIT -> updateWaitOnly();
            case ROUTINE_COOLDOWN -> updateCooldown(player);
            case ROUTINE_DESCEND, ROUTINE_RISE -> updateMoveAndWait();
            case ROUTINE_DIVE -> updateDive();
            case ROUTINE_STRAFE -> updateStrafe();
            case ROUTINE_ASCEND -> updateAscend();
            case ROUTINE_PRE_VORTEX_DRIFT -> updatePreVortexDrift();
            case ROUTINE_VORTEX -> updateVortex(player);
            case ROUTINE_SLOW_RISE -> updateSlowRise();
            case ROUTINE_DEFEATED -> {
                if (pendingDefeatDispatch) {
                    // Wait_FadeToLevelMusic was installed after this frame's arm already ran.
                    pendingDefeatDispatch = false;
                } else {
                    updateDefeated();
                }
            }
            default -> {
            }
        }

        updateRocketOrbit();
        updateWaterEffect();
        updateCustomFlash();
        updateDynamicSpawn(state.x, state.y);
    }

    private void updateInit() {
        if (!isCameraInTriggerWindow()) {
            return;
        }
        state.routine = ROUTINE_WAIT_TRIGGER;
        services().camera().setMinY((short) TRIGGER_MIN_Y);
    }

    private void updateWaitTrigger() {
        var camera = services().camera();
        int cameraX = camera.getX();
        int cameraY = camera.getY();
        camera.setMinY((short) TRIGGER_MIN_Y);
        if (!arenaYLocked && cameraY >= ARENA_LOCK_Y) {
            camera.setMinY((short) ARENA_LOCK_Y);
            camera.setMaxY((short) ARENA_LOCK_Y);
            arenaYLocked = true;
        }

        if (!arenaXLocked) {
            camera.setMinX((short) cameraX);
            if (cameraX >= ARENA_LOCK_X) {
                camera.setMinX((short) ARENA_LOCK_X);
                camera.setMaxX((short) ARENA_LOCK_X);
                arenaXLocked = true;
            }
        }

        if (!arenaXLocked || !arenaYLocked) {
            return;
        }

        state.routine = ROUTINE_WAIT_FADE;
        services().fadeOutMusic();
        loadBossPalette();
        setWait(FADE_OUT_TIME, WaitCallback.START_FIGHT);
    }

    private void startFight() {
        services().gameState().setCurrentBossId(0x99);
        services().playMusic(Sonic3kMusic.MINIBOSS.id);
        resetRocketPhases();
        beginRocketWindUp();
        state.routine = ROUTINE_DESCEND;
        state.yVel = DESCEND_VEL;
        crossedWaterThisPass = false;
        spawnRocketTouchChildren();
        setWait(DESCEND_TIME, WaitCallback.FINISH_DESCENT);
    }

    private void finishDescent() {
        state.routine = ROUTINE_WAIT;
        state.yVel = 0;
        setWait(WAIT_TIME, WaitCallback.START_RISE);
    }

    private void startRise() {
        state.routine = ROUTINE_RISE;
        state.yVel = RISE_VEL;
        setWait(RISE_TIME, WaitCallback.FINISH_RISE);
    }

    private void finishRise() {
        state.routine = ROUTINE_WAIT;
        state.yVel = 0;
        setWait(WAIT_TIME, WaitCallback.SELECT_ATTACK_PATTERN);
    }

    /**
     * sub_48916: Reads next attack pattern entry, positions boss, stores direction and pass count.
     * Called once per attack cycle (before the first dive of the cycle).
     */
    private void selectAttackPattern() {
        int[] pattern = ATTACK_PATTERNS[attackPatternIndex];
        attackPatternIndex = (attackPatternIndex + 1) % ATTACK_PATTERNS.length;

        int xOffset = pattern[0];
        passCounter = pattern[1];
        storedHorizontalVelocity = (xOffset < 0xA0) ? 0x400 : -0x400;
        int targetX = services().camera().getX() + xOffset;
        setBossPosition(targetX, state.y);
        setFacingLeft(storedHorizontalVelocity < 0);

        startDive();
    }

    /**
     * loc_47E96: Start a dive. The timer/callback set here ($47 / loc_47EE8) are only
     * used as a dead fallback — ObjHitFloor_DoRoutine fires the callback directly
     * when the floor is hit, bypassing Obj_Wait entirely.
     */
    private void startDive() {
        state.routine = ROUTINE_DIVE;
        state.xVel = 0;
        state.yVel = DIVE_VEL;
        closedBody = true;
        crossedWaterThisPass = false;
        services().playSfx(Sonic3kSfx.ROLL.id);
    }

    /**
     * loc_47EE8: Called directly by ObjHitFloor_DoRoutine when the floor is hit during
     * the dive — NOT via timer. Starts horizontal strafe movement immediately.
     * Uses stored velocity then negates it for the next pass.
     */
    private void startStrafe() {
        state.routine = ROUTINE_STRAFE;
        state.xVel = storedHorizontalVelocity;
        storedHorizontalVelocity = -storedHorizontalVelocity;
        state.yVel = 0;
        crossedWaterThisPass = false;
        setWait(STRAFE_TIME, WaitCallback.FINISH_STRAFE);
    }

    /**
     * loc_47F28: After strafe completes, decrement pass counter.
     * If passes remain (>=0), ascend to anchor and dive again (routine $E).
     * If exhausted (<0), ascend to vortex position (routine $10).
     */
    private void finishStrafe() {
        state.xVel = 0;
        state.yVel = RISE_VEL;
        passCounter--;
        if (passCounter < 0) {
            startAscend(anchorY + VORTEX_APPROACH_Y, AscendCallback.START_PRE_VORTEX_DRIFT);
        } else {
            startAscend(anchorY, AscendCallback.START_DIVE);
        }
    }

    /**
     * loc_47F7A: Pre-vortex drift. Snaps Y to target (anchor + $108),
     * drifts horizontally based on $40(a0) sign. Does NOT set y_vel —
     * the ascend velocity (-$400) carries over, and gravity in
     * updatePreVortexDrift decelerates it into a parabolic arc.
     */
    private void startPreVortexDrift() {
        state.routine = ROUTINE_PRE_VORTEX_DRIFT;
        setBossPosition(state.x, anchorY + VORTEX_APPROACH_Y);
        crossedWaterThisPass = false;
        int driftVel = PRE_VORTEX_DRIFT_VEL;
        if (storedHorizontalVelocity < 0) {
            driftVel = -driftVel;
        }
        state.xVel = driftVel;
        setWait(REOPEN_TIME, WaitCallback.START_VORTEX_WINDUP);
    }

    /**
     * loc_47FBC: Vortex windup. bclr #3,$38(a0) closes the boss —
     * rockets detect this and return to home position, losing collision.
     */
    private void startVortexWindup() {
        state.routine = ROUTINE_WAIT;
        state.xVel = 0;
        state.yVel = 0;
        beginRocketWindDown();
        services().playSfx(Sonic3kSfx.DOOR_CLOSE.id);
        setWait(0x9F, WaitCallback.BEGIN_VORTEX_SEQUENCE);
    }

    private void beginVortexSequence() {
        state.routine = ROUTINE_VORTEX;
        state.xVel = 0;
        state.yVel = 0;
        vortexActive = true;
        waterEffectRoutine = WATER_EFFECT_ROUTINE_WINDUP;
        waterEffectAnimFrame = 0;
        waterEffectAnimTimer = 1;
        waterEffectPullReady = false;
        vortexFinalPullPending = false;
        waterEffectFrame = WATER_EFFECT_BASE_FRAME;
        crossedWaterThisPass = true;
        services().playSfx(Sonic3kSfx.BOSS_ROTATE.id);
        spawnVortexBubbleBatch();
        setWait(VORTEX_TIME, WaitCallback.END_VORTEX);
    }

    private void endVortex() {
        vortexActive = false;
        waterEffectRoutine = WATER_EFFECT_ROUTINE_COOLDOWN;
        waterEffectAnimFrame = 0;
        waterEffectAnimTimer = 0;
        waterEffectPullReady = false;
        vortexFinalPullPending = true;
        for (VortexBubbleChild bubble : vortexBubbles) {
            bubble.signalVortexEnd();
        }
        vortexBubbles.clear();
        state.routine = ROUTINE_COOLDOWN;
        setWait(COOLDOWN_TIME, WaitCallback.START_POST_VORTEX_PAUSE);
    }

    private void releaseVortexPlayers() {
        PlayableEntity focused = services().camera().getFocusedSprite();
        for (PlayableEntity entity : nativeParticipants(focused)) {
            if (entity instanceof AbstractPlayableSprite sprite && sprite.isObjectControlled()) {
                ObjectControlState.none().applyTo(sprite);
                sprite.setForcedAnimationId(-1);
            }
        }
    }

    /**
     * loc_48010: Post-vortex reopen. bset #3,$38(a0) reopens the boss —
     * rockets detect this and begin spin-up sequence, regaining collision.
     */
    private void startPostVortexPause() {
        state.routine = ROUTINE_WAIT;
        state.xVel = 0;
        state.yVel = 0;
        beginRocketWindUp();
        setWait(REOPEN_TIME, WaitCallback.START_SLOW_RISE_FROM_VORTEX);
    }

    private void startSlowRiseFromVortex() {
        state.routine = ROUTINE_SLOW_RISE;
        state.xVel = 0;
        state.yVel = SLOW_RISE_VEL;
        setWait(0x7F, WaitCallback.START_ASCEND_TO_ANCHOR);
    }

    /**
     * loc_4804A: Final ascend to anchor after vortex. Clears closed body, plays sfx.
     */
    private void startAscendToAnchor() {
        closedBody = false;
        services().playSfx(Sonic3kSfx.LAVA_BALL.id);
        startAscend(anchorY, AscendCallback.SELECT_ATTACK_PATTERN);
    }

    private void startAscend(int targetY, AscendCallback callback) {
        state.routine = ROUTINE_ASCEND;
        state.xVel = 0;
        state.yVel = RISE_VEL;
        ascendTargetY = targetY;
        ascendCallback = callback;
    }

    private void updateWaitOnly() {
        tickWait();
    }

    private void updateMoveAndWait() {
        int previousY = state.y;
        state.applyVelocity();
        updateWaterCrossing(previousY, state.y);
        tickWait();
    }

    /**
     * Routine $A (loc_47EC6): Dive — water check, MoveSprite2, ObjHitFloor_DoRoutine.
     * ObjHitFloor_DoRoutine fires the callback ($34 = loc_47EE8 = startStrafe) directly
     * when floor is hit. No Obj_Wait, no timer ticking during the dive.
     */
    private void updateDive() {
        if (!crossedWaterThisPass) {
            if (state.y >= waterLevelY) {
                triggerWaterSplash();
            }
        }
        state.applyVelocity();
        TerrainCheckResult floor = ObjectTerrainUtils.checkFloorDist(state.x, state.y, FLOOR_CHECK_RADIUS);
        if (floor.hasCollision()) {
            setBossPosition(state.x, state.y + floor.distance());
            startStrafe();
        }
    }

    private void updateStrafe() {
        state.applyVelocity();
        followFloorCurve();
        tickWait();
    }

    /**
     * Shared ascend handler for routines $E and $10.
     * Does NOT snap Y or clear velocity — matches the disasm where loc_47F48
     * jumps straight to loc_47E96 with current y_pos when y <= target.
     * Callbacks that need snapping (e.g. pre-vortex drift) do it themselves.
     */
    private void updateAscend() {
        checkWaterSplashDuringAscent();
        int previousY = state.y;
        state.applyVelocity();
        updateWaterCrossing(previousY, state.y);
        if (state.y > ascendTargetY) {
            return;
        }
        runAscendCallback();
    }

    private void updateVortex(AbstractPlayableSprite player) {
        if (waterEffectRoutine == WATER_EFFECT_ROUTINE_PULL
                && (lastVIntRunCount & (CONTINUOUS_SFX_INTERVAL - 1)) == 0
                && isOnScreen()) {
            services().playSfx(Sonic3kSfx.BOSS_ROTATE.id);
        }
        if (waterEffectRoutine == WATER_EFFECT_ROUTINE_PULL && waterEffectPullReady) {
            applyVortexPull(player);
        } else if (waterEffectRoutine == WATER_EFFECT_ROUTINE_PULL) {
            waterEffectPullReady = true;
        }
        tickWait();
    }

    private void updateCooldown(AbstractPlayableSprite player) {
        if (vortexFinalPullPending) {
            if (player != null) {
                applyVortexPull(player);
            }
            vortexFinalPullPending = false;
            releaseVortexPlayers();
        }
        updateWaitOnly();
    }

    private void updatePreVortexDrift() {
        state.yVel += PRE_VORTEX_DRIFT_GRAVITY;
        state.applyVelocity();
        tickWait();
    }

    private void updateSlowRise() {
        int previousY = state.y;
        state.applyVelocity();
        updateWaterCrossing(previousY, state.y);
        tickWait();
    }

    private void updateDefeated() {
        if (defeatExplosionController == null) {
            return;
        }
        defeatExplosionController.tick();
        for (var pending : defeatExplosionController.drainPendingExplosions()) {
            if (pending.playSfx()) {
                services().playSfx(Sonic3kSfx.EXPLODE.id);
            }
            spawnChild(() -> new S3kBossExplosionChild(pending.x(), pending.y()));
        }
        if (!defeatHandoffStarted) {
            defeatHandoffTimer--;
            if (defeatHandoffTimer < 0) {
                releaseTrackedVortexPlayersOnWaterEffectDelete();
                // loc_6A22A sets the global _unkFAA2 lock before entering
                // Obj_EndSignControl, freezing DynamicWaterHeight_HCZ through
                // the results-era act reload (sonic3k.asm:140574-140575).
                services().waterSystem().setDynamicWaterLocked(
                        services().featureZoneId(), services().featureActId(), true);
                defeatHandoffStarted = true;
                spawnChild(() -> new S3kBossDefeatSignpostFlow(
                        state.x, 0, S3kBossDefeatSignpostFlow.CleanupAction.NONE));
            }
        }
        if (!defeatHandoffStarted || !defeatExplosionController.isFinished()
                || defeatRenderComplete) {
            return;
        }
        defeatRenderComplete = true;
        setDestroyed(true);
    }

    private void tickWait() {
        if (waitTimer < 0) {
            return;
        }
        waitTimer--;
        if (waitTimer >= 0) {
            return;
        }
        WaitCallback callback = waitCallback;
        waitCallback = WaitCallback.NONE;
        waitTimer = -1;
        runWaitCallback(callback);
    }

    private void setWait(int frames, WaitCallback callback) {
        waitTimer = frames;
        waitCallback = callback;
    }

    private void runWaitCallback(WaitCallback callback) {
        switch (callback) {
            case START_FIGHT -> startFight();
            case FINISH_DESCENT -> finishDescent();
            case START_RISE -> startRise();
            case FINISH_RISE -> finishRise();
            case SELECT_ATTACK_PATTERN -> selectAttackPattern();
            case FINISH_STRAFE -> finishStrafe();
            case START_VORTEX_WINDUP -> startVortexWindup();
            case BEGIN_VORTEX_SEQUENCE -> beginVortexSequence();
            case END_VORTEX -> endVortex();
            case START_POST_VORTEX_PAUSE -> startPostVortexPause();
            case START_SLOW_RISE_FROM_VORTEX -> startSlowRiseFromVortex();
            case START_ASCEND_TO_ANCHOR -> startAscendToAnchor();
            case NONE -> {
            }
        }
    }

    private void runAscendCallback() {
        AscendCallback callback = ascendCallback;
        ascendCallback = AscendCallback.NONE;
        switch (callback) {
            case START_PRE_VORTEX_DRIFT -> startPreVortexDrift();
            case START_DIVE -> startDive();
            case SELECT_ATTACK_PATTERN -> selectAttackPattern();
            case NONE -> {
            }
        }
    }

    private void updateWaterLevel() {
        try {
            waterLevelY = services().waterSystem().getWaterLevelY(
                    services().featureZoneId(), services().featureActId());
        } catch (Exception e) {
            waterLevelY = anchorY + 0x100;
        }
    }

    private void updateWaterCrossing(int previousY, int currentY) {
        if (crossedWaterThisPass) {
            return;
        }
        if (previousY < waterLevelY && currentY >= waterLevelY) {
            triggerWaterSplash();
        }
    }

    /**
     * sub_488E4: During ascent, check if boss is at/below water level and trigger splash once.
     * In the disasm this fires while still underwater; bit 7 prevents repeat.
     */
    private void checkWaterSplashDuringAscent() {
        if (crossedWaterThisPass) {
            return;
        }
        if (state.y >= waterLevelY) {
            triggerWaterSplash();
        }
    }

    /**
     * sub_488FA: Creates splash child at the BOSS X position, not the player's.
     * Uses the player sprite only to obtain the shared dust/splash renderer.
     */
    private void triggerWaterSplash() {
        crossedWaterThisPass = true;
        services().playSfx(Sonic3kSfx.SPLASH.id);
        PlayableEntity focused = services().camera().getFocusedSprite();
        if (focused instanceof AbstractPlayableSprite aps) {
            var dustController = aps.getSpindashDustController();
            if (dustController != null && dustController.getRenderer() != null) {
                spawnChild(() -> new SplashObjectInstance(
                        state.x, waterLevelY, dustController.getRenderer(), false));
            }
        }
    }

    private void applyVortexPull(AbstractPlayableSprite player) {
        boolean trackedP1 = false;
        boolean trackedP2 = false;
        int nativeIndex = 0;
        for (PlayableEntity entity : nativeParticipants(player)) {
            if (entity instanceof AbstractPlayableSprite sprite) {
                boolean tracked = applyVortexPullTo(sprite);
                if (nativeIndex == 0) {
                    trackedP1 = tracked;
                } else if (nativeIndex == 1) {
                    trackedP2 = tracked;
                }
                nativeIndex++;
            }
        }
        // sub_6A9B8 begins with clr.l $42(a0), then records the native player
        // pointers that passed the current pull-height gates. The water-effect
        // child retains these words after the vortex ends (sonic3k.asm:140211-
        // 140233) and consumes them if its parent is defeated.
        vortexTrackedP1 = trackedP1;
        vortexTrackedP2 = trackedP2;
    }

    private List<PlayableEntity> nativeParticipants(PlayableEntity player) {
        ObjectPlayerQuery query = services().playerQuery();
        return new ObjectPlayerQuery(() -> player, query::sidekicks)
                .playersFor(ObjectPlayerParticipationPolicy.NATIVE_P1_P2);
    }

    /**
     * sub_487FC + sub_48844 + sub_48874: Full vortex interaction per player.
     * First contact: sets player airborne, under object control, tumble animation.
     * Each frame: accelerates player toward vortex X center, nudges Y toward center.
     */
    private boolean applyVortexPullTo(AbstractPlayableSprite sprite) {
        if (sprite.getDead()) {
            return false;
        }
        int vortexY = getWaterEffectY();
        int checkY = vortexY - 0x20;
        if (sprite.getY() < checkY) {
            return false;
        }

        boolean firstContact = !sprite.isObjectControlled();

        int vortexX = getWaterEffectX();
        int playerX = sprite.getCentreX();
        int xDist = playerX - vortexX;
        boolean playerLeft = xDist < 0;
        if (playerLeft) xDist = -xDist;

        int xAccel = 0x40;
        short xVel = sprite.getXSpeed();

        if (xDist <= 3) {
            if (xVel < 0) {
                xAccel = -xAccel;
            }
        } else {
            if (xDist > 0x70) {
                xVel = 0;
            }
            if (!playerLeft) {
                xAccel = -xAccel;
            }
        }

        xVel = (short) (xVel + xAccel);
        sprite.setXSpeed(xVel);
        sprite.move(xVel, (short) 0);

        int yDist = sprite.getCentreY() - vortexY;
        if (yDist < -0x10) {
            sprite.move((short) 0, (short) 0x80);
        } else if (yDist > 0x10) {
            sprite.move((short) 0, (short) -0x80);
        }

        // sub_6A9B8 calls sub_6AA30 before sub_6AA00, so first contact moves
        // the player once and then clears x/y/ground speed while setting control.
        if (firstContact) {
            ObjectControlState.nativeBits0To6CpuAllowedMovementSuppressed().applyTo(sprite);
            sprite.setAir(true);
            // sub_6AA00 writes the public anim byte; forced animation only
            // represents this object's continuing ownership on later frames.
            sprite.setAnimationId(Sonic3kAnimationIds.FLOAT2);
            sprite.setForcedAnimationId(Sonic3kAnimationIds.FLOAT2.id());
            sprite.setXSpeed((short) 0);
            sprite.setYSpeed((short) 0);
            sprite.setGSpeed((short) 0);
        }
        return true;
    }

    /**
     * Mirrors the water-effect child's defeated-parent cleanup. ROM sub_6A960
     * converts the child to its delete routine, then sub_6A996 sets
     * Status_InAir and clears object_control on the player pointers retained in
     * $42/$44 (sonic3k.asm:140174-140209).
     */
    void releaseTrackedVortexPlayersOnWaterEffectDelete() {
        int nativeIndex = 0;
        PlayableEntity focused = services().camera().getFocusedSprite();
        for (PlayableEntity entity : nativeParticipants(focused)) {
            if (entity instanceof AbstractPlayableSprite sprite) {
                boolean tracked = nativeIndex == 0 ? vortexTrackedP1
                        : nativeIndex == 1 && vortexTrackedP2;
                if (tracked) {
                    sprite.setAir(true);
                    ObjectControlState.none().applyTo(sprite);
                }
                nativeIndex++;
            }
        }
        vortexTrackedP1 = false;
        vortexTrackedP2 = false;
    }

    @Override
    public String traceDebugDetails() {
        StringBuilder rocketSummary = new StringBuilder();
        if (rockets != null) {
            int count = Math.min(rockets.length, 4);
            for (int i = 0; i < count; i++) {
                RocketState rocket = rockets[i];
                if (rocket == null) {
                    continue;
                }
                if (!rocketSummary.isEmpty()) {
                    rocketSummary.append(',');
                }
                rocketSummary.append(i)
                        .append(':')
                        .append(rocket.routine & 0xFF)
                        .append('/')
                        .append(rocket.speed);
            }
        }
        return String.format(
                "r=%02X hits=%d def=%s done=%s handoff=%s/%d tracked=%s/%s inv=%s/%d lastHit=%d/%s hr=%02X hw=%d hwr=%02X xV=%04X yV=%04X wait=%d cb=%s pass=%d closed=%s vortex=%s waterR=%02X water=%04X,%04X wf=%d wa=%02X/%02X pullReady=%s rockets=%s",
                state.routine & 0xFF,
                state.hitCount,
                state.defeated,
                defeatRenderComplete,
                defeatHandoffStarted,
                defeatHandoffTimer,
                vortexTrackedP1,
                vortexTrackedP2,
                state.invulnerable,
                state.invulnerabilityTimer,
                lastHitFrame,
                lastHitSource,
                lastHitRoutine & 0xFF,
                lastHitWaitTimer,
                lastHitWaterEffectRoutine & 0xFF,
                state.xVel & 0xFFFF,
                state.yVel & 0xFFFF,
                waitTimer,
                waitCallback,
                passCounter,
                closedBody,
                vortexActive,
                waterEffectRoutine & 0xFF,
                getWaterEffectX() & 0xFFFF,
                getWaterEffectY() & 0xFFFF,
                waterEffectFrame,
                waterEffectAnimFrame & 0xFF,
                waterEffectAnimTimer & 0xFF,
                waterEffectPullReady,
                rocketSummary.isEmpty() ? "none" : rocketSummary.toString());
    }

    private String describeHitSource(PlayableEntity playerEntity, TouchResponseResult result) {
        String actor = playerEntity != null ? playerEntity.getClass().getSimpleName() : "unknown";
        String category = result != null && result.category() != null ? result.category().name() : "unknown";
        return actor + ":" + category;
    }

    /**
     * sub_489BA: Fold phase into 0-$7F range and look up signed offset from
     * HCZMiniboss_RocketTwistLookup. Used for BOTH X and Y orbit offsets.
     */
    private static int rocketTwistOffset(int phase) {
        phase &= 0xFF;
        if (phase >= 0x80) {
            phase = 0xFF - phase;
        }
        return ROCKET_TWIST_LOOKUP[phase];
    }

    private void followFloorCurve() {
        TerrainCheckResult floor = ObjectTerrainUtils.checkFloorDist(state.x, state.y, FLOOR_CHECK_RADIUS);
        if (!floor.foundSurface()) {
            return;
        }
        setBossPosition(state.x, state.y + floor.distance());
    }

    /**
     * Rocket wind-down sequence: matches the disasm's rocket routine $C → 8 chain.
     * Rockets orbit to subtype-specific home phases at full speed, then each child
     * runs its own speed 2 → 1 → routine 2 wait chain.
     * Called when the boss closes for the vortex (bit 3 cleared / startVortexWindup).
     */
    private void beginRocketWindDown() {
        for (RocketState rocket : rockets()) {
            if (rocket.routine == 10) {
                rocket.routine = 12;
                rocket.speed = ROCKET_PHASE_STEP;
            }
        }
    }

    /**
     * Rocket wind-up sequence: matches the disasm's rocket routine 2 → 4 → 8 → $A chain.
     * Rockets start at ROM phases from word_6A344. Subtypes 0/2 advance during the
     * first wait; subtypes 4/6 hold in routine 6 before joining routine 8.
     * Called when the boss reopens after the vortex (bit 3 set / startPostVortexPause).
     */
    private void beginRocketWindUp() {
        for (RocketState rocket : rockets()) {
            initializeRocketWindUp(rocket);
        }
    }

    private void initializeRocketWindUp(RocketState rocket) {
        int initialPhase = switch (rocket.subtype) {
            case 0 -> 0x0000;
            case 2 -> 0x8080;
            case 4 -> 0x8000;
            case 6 -> 0x0080;
            default -> 0x0000;
        };
        rocket.phaseX = (initialPhase >> 8) & 0xFF;
        rocket.phaseY = initialPhase & 0xFF;
        rocket.routine = rocket.subtype >= 4 ? 6 : 4;
        rocket.speed = 1;
        rocket.timer = 0x3F;
        rocket.callback = RocketSpeedCallback.WIND_UP_TO_ROUTINE_8;
        rocket.collisionArmed = false;
        refreshRocketPosition(rocket);
    }

    private void tickRocketWait(RocketState rocket) {
        if (rocket.timer < 0) {
            return;
        }
        rocket.timer--;
        if (rocket.timer >= 0) {
            return;
        }
        RocketSpeedCallback callback = rocket.callback;
        rocket.callback = RocketSpeedCallback.NONE;
        rocket.timer = -1;
        runRocketCallback(rocket, callback);
    }

    private void runRocketCallback(RocketState rocket, RocketSpeedCallback callback) {
        switch (callback) {
            case WIND_UP_TO_ROUTINE_8 -> {
                rocket.routine = 8;
                rocket.timer = 0x3F;
                rocket.callback = RocketSpeedCallback.WIND_UP_ARM_SPEED_2;
            }
            case WIND_UP_ARM_SPEED_2 -> {
                rocket.speed = 2;
                rocket.collisionArmed = true;
                rocket.timer = 0x1F;
                rocket.callback = RocketSpeedCallback.WIND_UP_TO_FULL_SPEED;
            }
            case WIND_UP_TO_FULL_SPEED -> {
                rocket.routine = 10;
                rocket.speed = ROCKET_PHASE_STEP;
                rocket.collisionArmed = true;
            }
            case WIND_DOWN_TO_SPEED_1 -> {
                rocket.routine = rocket.subtype >= 4 ? 4 : 6;
                rocket.speed = 1;
                rocket.timer = 0x3F;
                rocket.callback = RocketSpeedCallback.WIND_DOWN_RETURN_TO_INIT;
            }
            case WIND_DOWN_RETURN_TO_INIT -> {
                rocket.routine = 2;
                rocket.collisionArmed = false;
            }
            case NONE -> {
            }
        }
    }

    /**
     * sub_4895E: Update rocket orbit using HCZMiniboss_RocketTwistLookup.
     * Both X and Y offsets use the SAME fold+lookup function (sub_489BA),
     * applied to phaseX and phaseY respectively. This creates the distinctive
     * "figure-8 twist" orbit where rockets cross in front of the boss.
     */
    private void updateRocketOrbit() {
        int engineIndex = 0;
        RocketState[] rocketStates = rockets();
        for (int i = 0; i < rocketStates.length; i++) {
            RocketState rocket = rocketStates[i];
            updateRocketState(rocket);
            if (i == 0) {
                engineIndex = (rocket.phaseY >> 3) & 0x0F;
            }
        }
        state.routineSecondary = engineIndex;
    }

    private void updateRocketState(RocketState rocket) {
        switch (rocket.routine) {
            case 4, 8 -> {
                advanceRocket(rocket, rocket.speed);
                tickRocketWait(rocket);
            }
            case 6 -> tickRocketWait(rocket);
            case 10 -> advanceRocket(rocket, rocket.speed);
            case 12 -> {
                advanceRocket(rocket, rocket.speed);
                if (rocket.phaseX == rocketWindDownTarget(rocket)) {
                    rocket.routine = 8;
                    rocket.collisionArmed = false;
                    rocket.speed = 2;
                    rocket.timer = 0x1F;
                    rocket.callback = RocketSpeedCallback.WIND_DOWN_TO_SPEED_1;
                }
            }
            default -> refreshRocketPosition(rocket);
        }
    }

    private void advanceRocketOrbit(int phaseStep) {
        int engineIndex = 0;
        RocketState[] rocketStates = rockets();
        for (int i = 0; i < rocketStates.length; i++) {
            RocketState rocket = rocketStates[i];
            advanceRocket(rocket, phaseStep);
            if (i == 0) {
                engineIndex = (rocket.phaseY >> 3) & 0x0F;
            }
        }
        state.routineSecondary = engineIndex;
    }

    private void advanceRocket(RocketState rocket, int phaseStep) {
        rocket.phaseX = (rocket.phaseX + phaseStep) & 0xFF;
        rocket.phaseY = (rocket.phaseY + phaseStep) & 0xFF;
        refreshRocketPosition(rocket);
    }

    private void refreshRocketPosition(RocketState rocket) {
        int offsetX = rocketTwistOffset(rocket.phaseX);
        int offsetY = rocketTwistOffset(rocket.phaseY);
        rocket.x = state.x + offsetX;
        rocket.y = state.y + offsetY;
        int frameIndex = (rocket.phaseY >> 4) & 0x0F;
        rocket.frame = ROCKET_FRAMES[frameIndex];
        rocket.front = frameIndex < 8;
    }

    private int rocketWindDownTarget(RocketState rocket) {
        return switch (rocket.subtype) {
            case 0 -> 0x80;
            case 2 -> 0x00;
            case 4 -> 0xC0;
            case 6 -> 0x40;
            default -> 0x80;
        };
    }

    private void refreshRocketOrbitPositions() {
        advanceRocketOrbit(0);
    }

    private void updateWaterEffect() {
        if (!isWaterEffectVisible()) {
            waterEffectRoutine = WATER_EFFECT_ROUTINE_IDLE;
            waterEffectFrame = WATER_EFFECT_BASE_FRAME;
            return;
        }
        switch (waterEffectRoutine) {
            case WATER_EFFECT_ROUTINE_WINDUP -> animateWaterEffectWindup();
            case WATER_EFFECT_ROUTINE_PULL -> animateWaterEffectPull();
            case WATER_EFFECT_ROUTINE_COOLDOWN -> {
                waterEffectFrame = WATER_EFFECT_BASE_FRAME;
                waterEffectRoutine = WATER_EFFECT_ROUTINE_IDLE;
            }
            default -> waterEffectFrame = WATER_EFFECT_BASE_FRAME;
        }
    }

    private void animateWaterEffectWindup() {
        waterEffectAnimTimer--;
        if (waterEffectAnimTimer >= 0) {
            return;
        }

        waterEffectAnimFrame += 2;
        int command = WATER_EFFECT_WINDUP_SCRIPT[waterEffectAnimFrame] & 0xFF;
        if (command < 0x80) {
            waterEffectFrame = command;
            waterEffectAnimTimer = WATER_EFFECT_WINDUP_SCRIPT[waterEffectAnimFrame + 1] & 0xFF;
            return;
        }

        waterEffectRoutine = WATER_EFFECT_ROUTINE_PULL;
        waterEffectAnimFrame = 0;
        waterEffectAnimTimer = 0;
        waterEffectPullReady = false;
        waterEffectFrame = WATER_EFFECT_BASE_FRAME;
    }

    private void animateWaterEffectPull() {
        if (!vortexActive) {
            waterEffectFrame = WATER_EFFECT_BASE_FRAME;
            return;
        }
        waterEffectAnimTimer--;
        if (waterEffectAnimTimer >= 0) {
            return;
        }

        waterEffectAnimFrame++;
        int command = WATER_EFFECT_PULL_SCRIPT[waterEffectAnimFrame] & 0xFF;
        if (command < 0x80) {
            waterEffectFrame = command;
            waterEffectAnimTimer = WATER_EFFECT_PULL_SCRIPT[0] & 0xFF;
            return;
        }

        waterEffectFrame = WATER_EFFECT_PULL_SCRIPT[1] & 0xFF;
        waterEffectAnimFrame = 1;
        waterEffectAnimTimer = WATER_EFFECT_PULL_SCRIPT[0] & 0xFF;
    }

    /**
     * ChildObjDat_48BD6: Spawns $1E (30) vortex bubble particles at once.
     * Each gets ROM-accurate random spread and vortex pull physics.
     */
    private void spawnVortexBubbleBatch() {
        int vortexCentreX = getWaterEffectX();
        int vortexCentreY = getWaterEffectY();
        vortexBubbles.clear();
        var rng = services().rng();
        for (int i = 0; i < 0x1E; i++) {
            int bubbleX = vortexCentreX + (byte) rng.nextInt(256);
            int bubbleY = vortexCentreY + (rng.nextInt(64) - 8);
            int bubbleFrame = rng.nextInt(4);
            VortexBubbleChild bubble = spawnChild(() -> new VortexBubbleChild(
                    bubbleX, bubbleY, bubbleFrame, vortexCentreX, vortexCentreY));
            if (bubble != null) {
                vortexBubbles.add(bubble);
            }
        }
    }

    /**
     * Vortex bubble particle — swirls toward the vortex centre using the same
     * pull physics as the player (sub_48874). Lives for the entire vortex duration:
     * Phase 1 ($1F frames): animated pull. Phase 2: static pull until vortex ends.
     * Phase 3 ($1F frames): dying pull, then delete.
     */
    private static final class VortexBubbleChild extends com.openggf.level.objects.AbstractObjectInstance
            implements com.openggf.game.rewind.RewindStateful<VortexBubbleChild.RewindState>,
            SpawnCoordinateZeroScalarArgsRewindRecreatable {
        private static final int PHASE_TIMER = 0x1F;
        private static final int PHASE_PULL = 0;
        private static final int PHASE_HOLD = 1;
        private static final int PHASE_DYING = 2;
        private int vortexX;
        private int vortexY;
        private int frame;
        private int phase;
        private int timer;
        private short xVel;
        private int xSub;
        private int ySub;
        private boolean vortexEnded;

        private record RewindState(
                int x, int y, int xSub, int ySub,
                int phase, int timer, short xVel, boolean vortexEnded) {}

        private VortexBubbleChild(ObjectSpawn spawn) {
            this(spawn.x(), spawn.y(), 0, 0, 0);
        }

        VortexBubbleChild(int x, int y, int frame, int vortexX, int vortexY) {
            super(new ObjectSpawn(x, y, 0, 0, 0, false, 0), "VortexBubble");
            this.vortexX = vortexX;
            this.vortexY = vortexY;
            this.frame = frame;
            this.phase = PHASE_PULL;
            this.timer = PHASE_TIMER;
            this.xVel = 0;
        }

        void signalVortexEnd() {
            vortexEnded = true;
        }

        @Override
        public void update(int vIntRunCount, com.openggf.game.PlayableEntity player) {
            applyVortexPull();
            switch (phase) {
                case PHASE_PULL -> {
                    timer--;
                    if (timer < 0) {
                        phase = PHASE_HOLD;
                    }
                }
                case PHASE_HOLD -> {
                    if (vortexEnded) {
                        phase = PHASE_DYING;
                        timer = PHASE_TIMER;
                    }
                }
                case PHASE_DYING -> {
                    timer--;
                    if (timer < 0) {
                        setDestroyed(true);
                    }
                }
            }
        }

        /**
         * sub_487EE → sub_48874: Same pull as player — accelerate toward vortex X,
         * nudge toward vortex Y.
         */
        private void applyVortexPull() {
            int curX = getSpawn().x();
            int curY = getSpawn().y();
            int xDist = curX - vortexX;
            boolean left = xDist < 0;
            if (left) xDist = -xDist;

            int xAccel = 0x40;
            if (xDist <= 3) {
                if (xVel < 0) xAccel = -xAccel;
            } else {
                if (xDist > 0x70) xVel = 0;
                if (!left) xAccel = -xAccel;
            }
            xVel = (short) (xVel + xAccel);

            // ROM sub_6AA30 sign-extends the 8.8 velocity, shifts it by eight,
            // and adds it to the full 16.16 x_pos (sonic3k.asm:140301-140315).
            // Retaining the low word is
            // essential here: distant left-side bubbles reset to +$40 every
            // frame, so dropping the fraction leaves them permanently still.
            int xFixed = (curX << 16) | (xSub & 0xFFFF);
            xFixed += ((int) xVel) << 8;
            curX = xFixed >> 16;
            xSub = xFixed & 0xFFFF;

            int yDist = curY - vortexY;
            int yFixed = (curY << 16) | (ySub & 0xFFFF);
            if (yDist < -0x10) {
                yFixed += 0x8000;
            } else if (yDist > 0x10) {
                yFixed -= 0x8000;
            }
            curY = yFixed >> 16;
            ySub = yFixed & 0xFFFF;

            updateDynamicSpawn(curX, curY);
        }

        @Override
        public void appendRenderCommands(java.util.List<GLCommand> commands) {
            PatternSpriteRenderer renderer = getRenderer(
                    Sonic3kObjectArtKeys.BUBBLER);
            if (renderer != null) {
                renderer.drawFrameIndex(frame, getSpawn().x(), getSpawn().y(), false, false);
            }
        }

        @Override
        public boolean isPersistent() {
            return false;
        }

        @Override
        public RewindState captureRewindStateValue() {
            return new RewindState(
                    getSpawn().x(), getSpawn().y(), xSub, ySub,
                    phase, timer, xVel, vortexEnded);
        }

        @Override
        public void restoreRewindStateValue(RewindState state) {
            updateDynamicSpawn(state.x(), state.y());
            xSub = state.xSub();
            ySub = state.ySub();
            phase = state.phase();
            timer = state.timer();
            xVel = state.xVel();
            vortexEnded = state.vortexEnded();
        }
    }

    private void spawnRocketTouchChildren() {
        if (rocketTouchChildren != null) {
            return;
        }
        // ROM allocates four Obj_HCZMiniboss_Rockets child slots via
        // CreateChild1_Normal; each child adds itself to Collision_response_list.
        RocketState[] rocketStates = rockets();
        rocketTouchChildren = new RocketTouchChild[rocketStates.length];
        for (int i = 0; i < rocketStates.length; i++) {
            final int childIndex = i;
            rocketTouchChildren[i] = spawnChild(() -> new RocketTouchChild(
                    childIndex, spawn.objectId(), spawn.layoutIndex()));
        }
    }

    private final class RocketTouchChild extends AbstractObjectInstance
            implements TouchResponseProvider, RewindRecreatable {
        // Non-final so the generic rewind field capturer can reapply the captured
        // spawn-derived values after the recreate hook rebuilds this child.
        private int rocketIndex;
        private int objectId;
        private int layoutIndex;

        private RocketTouchChild() {
            super(new ObjectSpawn(0, 0, 0, 0, 0, false, 0), "HCZMinibossRocketTouch");
        }

        private RocketTouchChild(int rocketIndex, int objectId, int layoutIndex) {
            super(new ObjectSpawn(
                    rockets()[rocketIndex].x,
                    rockets()[rocketIndex].y,
                    objectId,
                    rocketIndex * 2,
                    0,
                    false,
                    0),
                    "HCZMinibossRocketTouch");
            this.rocketIndex = rocketIndex;
            this.objectId = objectId;
            this.layoutIndex = layoutIndex;
        }

        @Override
        public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
            if (ctx == null || ctx.objectServices() == null || ctx.objectServices().objectManager() == null
                    || ctx.spawn() == null) {
                return null;
            }
            HczMinibossInstance parent = null;
            for (var object : ctx.objectServices().objectManager().getActiveObjects()) {
                if (object instanceof HczMinibossInstance candidate) {
                    parent = candidate;
                    break;
                }
            }
            if (parent == null) {
                return null;
            }

            ObjectSpawn capturedSpawn = ctx.spawn();
            int capturedRocketIndex = capturedSpawn.subtype() / 2;
            RocketState[] parentRockets = parent.rockets();
            if (capturedRocketIndex < 0 || capturedRocketIndex >= parentRockets.length) {
                return null;
            }

            RocketTouchChild child = parent.new RocketTouchChild(
                    capturedRocketIndex,
                    capturedSpawn.objectId(),
                    capturedSpawn.layoutIndex());
            if (parent.rocketTouchChildren == null
                    || parent.rocketTouchChildren.length != parentRockets.length) {
                parent.rocketTouchChildren = new RocketTouchChild[parentRockets.length];
            }
            parent.rocketTouchChildren[capturedRocketIndex] = child;
            return child;
        }

        @Override
        public int getX() {
            return rockets()[rocketIndex].x;
        }

        @Override
        public int getY() {
            return rockets()[rocketIndex].y;
        }

        @Override
        public ObjectSpawn getSpawn() {
            return new ObjectSpawn(
                    getX(),
                    getY(),
                    objectId,
                    rocketIndex * 2,
                    0,
                    false,
                    0,
                    layoutIndex);
        }

        @Override
        public int getCollisionFlags() {
            return getRocketCollisionFlags(rocketIndex);
        }

        @Override
        public int getCollisionProperty() {
            return 0;
        }

        @Override
        public TouchResponseProfile getTouchResponseProfile() {
            return TouchResponseProfile.standardEnemy();
        }

        @Override
        public boolean isPersistent() {
            return HczMinibossInstance.this.isPersistent();
        }

        @Override
        public boolean isDestroyed() {
            return HczMinibossInstance.this.isDestroyed() || defeatRenderComplete;
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            // The parent compositor draws rockets in its front/back priority passes.
        }
    }

    private void resetRocketPhases() {
        for (RocketState rocket : rockets()) {
            initializeRocketWindUp(rocket);
            rocket.routine = 2;
            rocket.timer = -1;
            rocket.callback = RocketSpeedCallback.NONE;
            rocket.collisionArmed = false;
        }
        refreshRocketOrbitPositions();
    }

    private RocketState[] rockets() {
        ensureRocketState();
        return rockets;
    }

    private void ensureRocketState() {
        if (rockets != null) {
            return;
        }
        rockets = new RocketState[] {
                new RocketState(0),
                new RocketState(2),
                new RocketState(4),
                new RocketState(6)
        };
    }

    private int getCoreCollisionFlags() {
        if (!isFightVisible() || state.invulnerable || state.defeated) {
            return 0;
        }
        return CORE_COLLISION_FLAGS;
    }

    private int getRocketCollisionFlags(int rocketIndex) {
        if (!isFightVisible() || state.defeated || !rockets()[rocketIndex].collisionArmed) {
            return 0;
        }
        return ROCKET_COLLISION_FLAGS;
    }

    private boolean isFightVisible() {
        return state.routine >= ROUTINE_DESCEND && !defeatRenderComplete;
    }

    private boolean isWaterEffectVisible() {
        if (state.routine == ROUTINE_DEFEATED || defeatRenderComplete) {
            return false;
        }
        return state.routine >= ROUTINE_WAIT_TRIGGER || isCameraInTriggerWindow();
    }

    private boolean isCameraInTriggerWindow() {
        var camera = services().camera();
        int cameraX = camera.getX();
        int cameraY = camera.getY();
        return cameraX >= TRIGGER_MIN_X && cameraX <= TRIGGER_MAX_X
                && cameraY >= TRIGGER_MIN_Y && cameraY <= TRIGGER_MAX_Y;
    }

    private void setBossPosition(int x, int y) {
        state.x = x;
        state.y = y;
        state.xFixed = x << 16;
        state.yFixed = y << 16;
    }

    private void setFacingLeft(boolean facingLeft) {
        if (facingLeft) {
            state.renderFlags |= 1;
        } else {
            state.renderFlags &= ~1;
        }
    }

    private void updateCustomFlash() {
        if (state.defeated) {
            return;
        }
        if (!state.invulnerable) {
            if (customFlashDirty) {
                loadBossPalette();
                customFlashDirty = false;
            }
            return;
        }

        var level = services().currentLevel();
        if (level == null || level.getPaletteCount() <= 1) {
            return;
        }

        int[] colors = ((state.invulnerabilityTimer & 1) != 0) ? FLASH_DARK : FLASH_BRIGHT;
        S3kPaletteWriteSupport.applyColors(
                services().paletteOwnershipRegistryOrNull(),
                level,
                services().graphicsManager(),
                S3kPaletteOwners.HCZ_MINIBOSS,
                S3kPaletteOwners.PRIORITY_OBJECT_OVERRIDE,
                1,
                FLASH_INDICES,
                colors);
        customFlashDirty = true;
    }

    private void loadBossPalette() {
        try {
            byte[] line = services().rom().readBytes(Sonic3kConstants.PAL_HCZ_MINIBOSS_ADDR, 32);
            S3kPaletteWriteSupport.applyLine(
                    services().paletteOwnershipRegistryOrNull(),
                    services().currentLevel(),
                    services().graphicsManager(),
                    S3kPaletteOwners.HCZ_MINIBOSS,
                    S3kPaletteOwners.PRIORITY_OBJECT_OVERRIDE,
                    1,
                    line);
        } catch (Exception e) {
            LOG.fine(() -> "HczMinibossInstance.loadBossPalette: " + e.getMessage());
        }
    }

    /**
     * loc_483A0: Loads Pal_HCZMinibossWater directly into Water_palette_line_2
     * (underwater palette line 1). Done once when the water column becomes visible.
     */
    private void ensureWaterEffectPalette() {
        if (waterPaletteLoaded || !isWaterEffectVisible()) {
            return;
        }
        try {
            byte[] line = services().rom().readBytes(Sonic3kConstants.PAL_HCZ_MINIBOSS_WATER_ADDR, 32);
            var waterSystem = services().waterSystem();
            var graphics = services().graphicsManager();
            var level = services().currentLevel();
            Palette[] uwPalettes = waterSystem.getUnderwaterPalette(
                    services().featureZoneId(), services().featureActId());
            if (uwPalettes != null && uwPalettes.length > 1 && level != null) {
                S3kPaletteWriteSupport.applyUnderwaterLine(
                        services().paletteOwnershipRegistryOrNull(),
                        level,
                        graphics,
                        uwPalettes,
                        S3kPaletteOwners.HCZ_MINIBOSS,
                        S3kPaletteOwners.PRIORITY_OBJECT_OVERRIDE,
                        1,
                        line,
                        true);
            }
            waterPaletteLoaded = true;
        } catch (Exception e) {
            LOG.fine(() -> "HczMinibossInstance.ensureWaterEffectPalette: " + e.getMessage());
        }
    }

    private Palette[] clonePalettes(Palette[] source) {
        Palette[] copy = new Palette[source.length];
        for (int i = 0; i < source.length; i++) {
            copy[i] = source[i] != null ? source[i].deepCopy() : null;
        }
        return copy;
    }

    private int getEngineChildFrame(RocketState rocket) {
        return ENGINE_FRAMES[(rocket.phaseY >> 4) & 0x0F];
    }

    private int getEngineChildOffsetX(RocketState rocket) {
        int index = (rocket.phaseY >> 3) & 0x1E;
        int offset = ENGINE_CHILD_OFFSETS[index];
        return rocket.hFlip ? -offset : offset;
    }

    private int getEngineChildOffsetY(RocketState rocket) {
        int index = (rocket.phaseY >> 3) & 0x1E;
        return ENGINE_CHILD_OFFSETS[index + 1];
    }

    private boolean isEngineChildFront(RocketState rocket) {
        int index = (rocket.phaseY >> 4) & 0x0F;
        return ENGINE_CHILD_PRIORITIES[index] >= 0x280;
    }

    private int getWaterEffectX() {
        return spawn.x();
    }

    private int getWaterEffectY() {
        return spawn.y() + WATER_EFFECT_OFFSET_Y;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.HCZ_MINIBOSS);
        if (renderer == null) {
            return;
        }

        if (isWaterEffectVisible()) {
            renderer.drawFrameIndex(waterEffectFrame, getWaterEffectX(), getWaterEffectY(), false, false);
        }
        if (!isFightVisible()) {
            return;
        }

        // Rockets orbit the boss with a front/back split matching VDP priority:
        // Back rockets (priority $200, phaseY index < 8) drawn BEHIND boss body.
        // Front rockets (priority $280, phaseY index >= 8) drawn IN FRONT of boss body.
        boolean showRocketExhaust = areRocketExhaustsVisible() && (lastVIntRunCount & 1) == 0;
        for (RocketState rocket : rockets()) {
            if (!rocket.front) {
                if (showRocketExhaust) {
                    renderer.drawFrameIndex(
                            getEngineChildFrame(rocket),
                            rocket.x + getEngineChildOffsetX(rocket),
                            rocket.y + getEngineChildOffsetY(rocket),
                            rocket.hFlip, false, 0);
                }
                renderer.drawFrameIndex(rocket.frame, rocket.x, rocket.y, rocket.hFlip, false);
            }
        }
        renderer.drawFrameIndex(0, state.x, state.y, false, false);
        if (!closedBody) {
            renderer.drawFrameIndex(ENGINE_FRAME, state.x, state.y + ENGINE_OFFSET_Y, false, false, 0);
        }
        for (RocketState rocket : rockets()) {
            if (rocket.front) {
                if (showRocketExhaust) {
                    renderer.drawFrameIndex(
                            getEngineChildFrame(rocket),
                            rocket.x + getEngineChildOffsetX(rocket),
                            rocket.y + getEngineChildOffsetY(rocket),
                            rocket.hFlip, false, 0);
                }
                renderer.drawFrameIndex(rocket.frame, rocket.x, rocket.y, rocket.hFlip, false);
            }
        }
    }

    private boolean areRocketExhaustsVisible() {
        for (RocketState rocket : rockets()) {
            if (rocket.collisionArmed) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isPersistent() {
        return !isDestroyed();
    }

    @Override
    public boolean isHighPriority() {
        return true;
    }

    @Override
    public int getPriorityBucket() {
        return 5;
    }

    @Override
    protected int getBossHitSfxId() {
        return Sonic3kSfx.BOSS_HIT.id;
    }

    @Override
    protected int getBossExplosionSfxId() {
        return Sonic3kSfx.EXPLODE.id;
    }
}
