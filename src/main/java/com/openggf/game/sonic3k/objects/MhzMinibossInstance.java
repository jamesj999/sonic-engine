package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.PlayerCharacter;
import com.openggf.game.sonic3k.S3kPaletteOwners;
import com.openggf.game.sonic3k.S3kPaletteWriteSupport;
import com.openggf.game.sonic3k.Sonic3kLevelEventManager;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.audio.Sonic3kMusic;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.runtime.S3kZoneRuntimeState;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.game.zone.ZoneRuntimeState;
import com.openggf.level.objects.boss.AbstractBossInstance;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.ObjectTerrainUtils;
import com.openggf.physics.TerrainCheckResult;

import java.util.List;

/**
 * S3K SKL object $92 - MHZ Act 1 miniboss.
 *
 * <p>ROM reference: {@code Obj_MHZMiniboss}. This follows the ROM boss
 * routine table, hit handling, defeat explosion controller, and act-end
 * signpost handoff while reusing the engine's shared boss touch-response path.
 */
public final class MhzMinibossInstance extends AbstractBossInstance implements SpawnRewindRecreatable {
    private static final int HIT_COUNT = 6;
    private static final int COLLISION_SIZE = 0x0F;
    private static final int PRIORITY_BUCKET = 4; // ObjDat_MHZMiniboss priority $200
    private static final int INIT_X_CAMERA_OFFSET = 0x110;
    private static final int INIT_Y_CAMERA_OFFSET = -0x78;
    private static final int INIT_Y_VELOCITY = 0x100;
    private static final int INIT_TIMER = 0x97;
    private static final int FIRST_SWING_Y_VELOCITY = 0x80;
    private static final int FIRST_SWING_ACCELERATION = 0x10;
    private static final int LEVEL_MUSIC_FADE_TIME = 2 * 60;
    private static final int DEFEAT_EXPLOSION_TIMER = 0x20; // CreateBossExp10
    private static final int DEFEAT_EXPLOSION_X_RANGE = 0x20;
    private static final int DEFEAT_EXPLOSION_Y_RANGE = 0x20;
    private static final int DEFEAT_EXPLOSION_INTERVAL = 3;
    private static final int CHOPPING_CALLBACK_COMMAND = 0xF4;
    // loc_845E4: re-point the script base by a signed relative offset, then fall through to $FC.
    private static final int SCRIPT_JUMP_COMMAND = 0xF8;
    // loc_845F2: re-emit the frame/delay pair at the (possibly just re-pointed) script base.
    private static final int SCRIPT_REPEAT_BASE_COMMAND = 0xFC;
    private static final int[] CHOPPING_SCRIPT = {
            0x04, 0x27,
            0x04, 0x27,
            0x07, 0x02,
            0x09, 0x02,
            0x0C, 0x02,
            0x0D, 0x3B,
            0x0B, 0x02,
            0x09, 0x02,
            0x07, 0x02,
            0x04, 0x27,
            CHOPPING_CALLBACK_COMMAND
    };
    private static final int[] FINAL_STUCK_SCRIPT = {
            0x04, 0x27,
            0x04, 0x27,
            0x07, 0x02,
            0x09, 0x02,
            0x0C, 0x02,
            0x0D, 0x27,
            0x10, 0x03,
            0x11, 0x07,
            0x12, 0x07,
            0x11, 0x07,
            0x12, 0x07,
            0x11, 0x07,
            0x12, 0x07,
            0x11, 0x07,
            0x12, 0x07,
            0x11, 0x07,
            0x12, 0x31,
            CHOPPING_CALLBACK_COMMAND
    };
    private static final int[] FINAL_LAUNCH_SCRIPT = {
            0x0A, 0x03,
            0x0A, 0x03,
            0x08, 0x03,
            0x06, 0x03,
            0xF8, 0x0A,
            0x06, 0x7F,
            0x06, 0x7F,
            0xFC
    };
    private static final int[] FINAL_RETURN_WAIT_SCRIPT = {
            0x05, 0x7F,
            0x05, 0x7F,
            0xFC
    };
    private static final int[] FINAL_ESCAPE_SCRIPT = {
            0x0F, 0x31,
            0x0F, 0x31,
            0x13, 0x04,
            0x14, 0x04,
            0x15, 0x04,
            CHOPPING_CALLBACK_COMMAND
    };
    private static final int[] FINAL_CAMERA_RISE_SCRIPT = {
            0x00, 0x0B,
            0x01, 0x05,
            0x02, 0x05,
            0x03, 0x0B,
            0x02, 0x05,
            0x01, 0x05,
            0xFC
    };
    private static final int SCRATCH_2E = 0x2E;
    private static final int SCRATCH_38 = 0x38;
    private static final int SCRATCH_39 = 0x39;
    private static final int SCRATCH_3A = 0x3A;
    private static final int SCRATCH_3C = 0x3C;
    private static final int SCRATCH_3E = 0x3E;
    private static final int SCRATCH_40 = 0x40;
    private static final int SCRATCH_42 = 0x42;
    private static final int ROUTINE_WAIT_AND_FALL = 2;
    private static final int ROUTINE_FIRST_SWING = 4;
    private static final int ROUTINE_HORIZONTAL_DASH_WAIT = 6;
    private static final int ROUTINE_OFFSCREEN_DASH_WAIT = 8;
    private static final int ROUTINE_DECELERATION_DASH = 0x0A;
    private static final int ROUTINE_CAMERA_APPROACH_SWING = 0x0C;
    private static final int ROUTINE_CHOPPING = 0x0E;
    private static final int ROUTINE_SWING_PEAK_WAIT = 0x10;
    private static final int ROUTINE_FALL_RECOVERY_WAIT = 0x12;
    private static final int ROUTINE_FINAL_STUCK_ANIMATION = 0x14;
    private static final int ROUTINE_FINAL_STUCK_LAUNCH = 0x16;
    private static final int ROUTINE_FINAL_BOUNCE_THRESHOLD = 0x18;
    private static final int ROUTINE_FINAL_WAIT_BEFORE_RETURN = 0x1A;
    private static final int ROUTINE_FINAL_RETURN_BOUNCE = 0x1C;
    private static final int ROUTINE_FINAL_RETURN_WAIT = 0x1E;
    private static final int ROUTINE_FINAL_ESCAPE_SWING = 0x20;
    private static final int ROUTINE_FINAL_ESCAPE_ANIMATE = 0x22;
    private static final int ROUTINE_FINAL_ESCAPE_WAIT = 0x24;
    private static final int ROUTINE_FINAL_ESCAPE_SIGNAL_SWING = 0x26;
    private static final int ROUTINE_FINAL_ESCAPE_CAMERA_RISE = 0x28;
    private static final int ROUTINE_FINAL_ESCAPE_CAMERA_WAIT = 0x2A;
    private static final int ROUTINE_FINAL_ESCAPE_UPWARD = 0x2C;
    private static final int ROUTINE_FINAL_ESCAPE_RETURN_WAIT = 0x2E;

    private boolean cameraInitPending;
    private int mappingFrame;
    private int animFrame;
    private int animFrameTimer;
    // ROM $30(a0): the active raw-animation script's base pointer. Normally the script's own
    // origin (0), but the $F8 command re-points it, and that re-point persists across calls
    // until the next Set_Raw_Animation-equivalent reset (a fresh script switch).
    private int scriptBase;
    private boolean defeatHandoffQueued;
    private int defeatExplosionTimer;
    private int defeatExplosionIntervalCounter;
    private boolean paletteLoaded;

    public MhzMinibossInstance(ObjectSpawn spawn) {
        super(spawn, "MHZMiniboss");
    }

    @Override
    protected void initializeBossState() {
        state.x = spawn.x();
        state.y = spawn.y();
        state.xFixed = state.x << 16;
        state.yFixed = state.y << 16;
        state.hitCount = HIT_COUNT;
        state.routine = 0;
        state.yVel = INIT_Y_VELOCITY;
        mappingFrame = 0;
        animFrame = 0;
        animFrameTimer = 0;
        scriptBase = 0;
        paletteLoaded = false;
        defeatHandoffQueued = false;
        defeatExplosionTimer = -1;
        defeatExplosionIntervalCounter = DEFEAT_EXPLOSION_INTERVAL - 1;
        setCustomFlag(SCRATCH_2E, INIT_TIMER);
        setCustomFlag(SCRATCH_42, 5);
        cameraInitPending = true;
    }

    @Override
    protected void updateBossLogic(int vIntRunCount, PlayableEntity player) {
        if (state.defeated) {
            updateDefeatHandoff();
            return;
        }
        if (applyPendingCameraInit()) {
            return;
        }
        if (state.routine == ROUTINE_WAIT_AND_FALL) {
            updateWaitAndFall();
        } else if (state.routine == ROUTINE_FIRST_SWING) {
            updateFirstSwing();
        } else if (state.routine == ROUTINE_HORIZONTAL_DASH_WAIT) {
            updateHorizontalDashWait();
        } else if (state.routine == ROUTINE_OFFSCREEN_DASH_WAIT) {
            updateOffscreenDashWait();
        } else if (state.routine == ROUTINE_DECELERATION_DASH) {
            updateDecelerationDash();
        } else if (state.routine == ROUTINE_CAMERA_APPROACH_SWING) {
            updateCameraApproachSwing();
        } else if (state.routine == ROUTINE_CHOPPING) {
            updateChoppingAnimation();
        } else if (state.routine == ROUTINE_SWING_PEAK_WAIT) {
            updateSwingPeakWait();
        } else if (state.routine == ROUTINE_FALL_RECOVERY_WAIT) {
            updateFallRecoveryWait();
        } else if (state.routine == ROUTINE_FINAL_STUCK_ANIMATION) {
            updateFinalStuckAnimation();
        } else if (state.routine == ROUTINE_FINAL_STUCK_LAUNCH) {
            updateFinalStuckLaunch();
        } else if (state.routine == ROUTINE_FINAL_BOUNCE_THRESHOLD) {
            updateFinalBounceThreshold();
        } else if (state.routine == ROUTINE_FINAL_WAIT_BEFORE_RETURN) {
            updateFinalWaitBeforeReturn();
        } else if (state.routine == ROUTINE_FINAL_RETURN_BOUNCE) {
            updateFinalReturnBounce();
        } else if (state.routine == ROUTINE_FINAL_RETURN_WAIT) {
            updateFinalReturnWait();
        } else if (state.routine == ROUTINE_FINAL_ESCAPE_SWING) {
            updateFinalEscapeSwing();
        } else if (state.routine == ROUTINE_FINAL_ESCAPE_ANIMATE) {
            updateFinalEscapeAnimate();
        } else if (state.routine == ROUTINE_FINAL_ESCAPE_WAIT) {
            updateFinalEscapeWait();
        } else if (state.routine == ROUTINE_FINAL_ESCAPE_SIGNAL_SWING) {
            updateFinalEscapeSignalSwing();
        } else if (state.routine == ROUTINE_FINAL_ESCAPE_CAMERA_RISE) {
            updateFinalEscapeCameraRise();
        } else if (state.routine == ROUTINE_FINAL_ESCAPE_CAMERA_WAIT) {
            updateFinalEscapeCameraWait();
        } else if (state.routine == ROUTINE_FINAL_ESCAPE_UPWARD) {
            updateFinalEscapeUpward();
        } else if (state.routine == ROUTINE_FINAL_ESCAPE_RETURN_WAIT) {
            updateFinalEscapeReturnWait();
        }
    }

    @Override
    protected int getInitialHitCount() {
        return HIT_COUNT;
    }

    @Override
    protected void onHitTaken(int remainingHits) {
        // sub_75D80 handles flash/collision restore; the fatal branch is onDefeatStarted().
    }

    @Override
    protected void onDefeatStarted() {
        state.invulnerable = false;
        state.invulnerabilityTimer = 0;
        defeatHandoffQueued = false;
        defeatExplosionTimer = DEFEAT_EXPLOSION_TIMER;
        defeatExplosionIntervalCounter = DEFEAT_EXPLOSION_INTERVAL - 1;
        ObjectServices svc = tryServices();
        if (svc != null && svc.levelGamestate() != null) {
            svc.levelGamestate().pauseTimer();
        }
    }

    @Override
    protected int getCollisionSizeIndex() {
        return COLLISION_SIZE;
    }

    @Override
    public boolean participatesInLevelRepeatOffset() {
        return true;
    }

    @Override
    public void applyLevelRepeatOffset(int offsetX, int offsetY) {
        state.x = (state.x + offsetX) & 0xFFFF;
        state.y = (state.y + offsetY) & 0xFFFF;
        state.xFixed += offsetX << 16;
        state.yFixed += offsetY << 16;
    }

    @Override
    protected boolean usesDefeatSequencer() {
        return false;
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
        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.MHZ_MINIBOSS);
        if (renderer == null) {
            return;
        }
        renderer.drawFrameIndex(mappingFrame, state.x, state.y, false, false);
    }

    @Override
    public int getPriorityBucket() {
        return PRIORITY_BUCKET;
    }

    int getMappingFrameForChildSprites() {
        return mappingFrame;
    }

    private boolean applyPendingCameraInit() {
        if (!cameraInitPending) {
            return false;
        }
        ObjectServices svc = tryServices();
        if (svc == null || svc.camera() == null) {
            return false;
        }

        state.x = (svc.camera().getX() & 0xFFFF) + INIT_X_CAMERA_OFFSET;
        state.y = (svc.camera().getY() & 0xFFFF) + INIT_Y_CAMERA_OFFSET;
        state.xFixed = state.x << 16;
        state.yFixed = state.y << 16;
        state.yVel = INIT_Y_VELOCITY;
        setCustomFlag(SCRATCH_2E, INIT_TIMER);
        setCustomFlag(SCRATCH_42, 5);
        if (svc.levelEventProvider() instanceof Sonic3kLevelEventManager manager) {
            manager.setBossFlag(true);
        }
        spawnChild(() -> new MhzMinibossFlameInstance(this, 0));
        spawnChild(() -> new MhzMinibossFlameInstance(this, 1));
        svc.playMusic(Sonic3kMusic.MINIBOSS.id);
        loadBossPalette();
        state.routine = ROUTINE_WAIT_AND_FALL;
        cameraInitPending = false;
        return true;
    }

    /**
     * ROM: {@code lea Pal_MHZMiniboss(pc),a1 / jsr PalLoad_Line1} during
     * {@code Obj_MHZMiniboss} setup (sonic3k.asm:155660-155661). S&K-side ROM
     * offset 0x075F28 was verified by searching the 32-byte palette payload.
     */
    private void loadBossPalette() {
        if (paletteLoaded) {
            return;
        }
        try {
            byte[] line = services().rom().readBytes(Sonic3kConstants.PAL_MHZ_MINIBOSS_ADDR, 32);
            S3kPaletteWriteSupport.applyLine(
                    services().paletteOwnershipRegistryOrNull(),
                    services().currentLevel(),
                    services().graphicsManager(),
                    S3kPaletteOwners.MHZ_MINIBOSS,
                    S3kPaletteOwners.PRIORITY_OBJECT_OVERRIDE,
                    1,
                    line);
            paletteLoaded = true;
        } catch (Exception ignored) {
            // Palette loading is best-effort for partial object-unit harnesses.
        }
    }

    private void updateWaitAndFall() {
        state.applyVelocity();
        int timer = getCustomFlag(SCRATCH_2E) - 1;
        setCustomFlag(SCRATCH_2E, timer);
        if (timer < 0) {
            setupFirstSwingState();
        }
    }

    private void setupFirstSwingState() {
        state.routine = ROUTINE_FIRST_SWING;
        state.yVel = 0;
        setCustomFlag(SCRATCH_3A, state.x & 0xFF);
        setCustomFlag(SCRATCH_3C, state.y);
        setCustomFlag(SCRATCH_39, 3);
        setupSwingVelocity();
    }

    private void updateFirstSwing() {
        if (swingUpAndDownCount()) {
            setupHorizontalDashWait();
            return;
        }
        state.applyVelocity();
    }

    private boolean swingUpAndDownCount() {
        boolean reachedPeak = swingUpAndDown();
        if (!reachedPeak) {
            return false;
        }

        int count = getCustomFlag(SCRATCH_39) - 1;
        setCustomFlag(SCRATCH_39, count);
        return count < 0;
    }

    private boolean swingUpAndDown() {
        int flags = getCustomFlag(SCRATCH_38);
        int acceleration = getCustomFlag(SCRATCH_40);
        int velocity = state.yVel;
        int maxVelocity = getCustomFlag(SCRATCH_3E);
        boolean reachedPeak = false;

        if ((flags & 1) != 0) {
            velocity += acceleration;
            if (velocity >= maxVelocity) {
                flags &= ~1;
                velocity -= acceleration;
                reachedPeak = true;
            }
        } else {
            velocity -= acceleration;
            if (velocity <= -maxVelocity) {
                flags |= 1;
                velocity += acceleration;
                reachedPeak = true;
            }
        }

        state.yVel = velocity;
        setCustomFlag(SCRATCH_38, flags);
        return reachedPeak;
    }

    private void setupHorizontalDashWait() {
        state.routine = ROUTINE_HORIZONTAL_DASH_WAIT;
        state.y = getCustomFlag(SCRATCH_3C);
        state.yFixed = state.y << 16;
        state.xVel = 0x400;
        state.yVel = 0;
        setCustomFlag(SCRATCH_2E, 0x1F);
    }

    private void updateHorizontalDashWait() {
        state.applyVelocity();
        int timer = getCustomFlag(SCRATCH_2E) - 1;
        setCustomFlag(SCRATCH_2E, timer);
        if (timer < 0) {
            setupOffscreenDashWait();
        }
    }

    private void setupOffscreenDashWait() {
        state.routine = ROUTINE_OFFSCREEN_DASH_WAIT;
        mappingFrame = 5;
        setCustomFlag(SCRATCH_2E, 0x4F);

        ObjectServices svc = tryServices();
        if (svc != null && svc.camera() != null) {
            svc.camera().setMaxXTarget((short) 0x6000);
        }
    }

    private void updateOffscreenDashWait() {
        if ((state.renderFlags & 0x80) == 0) {
            return;
        }

        state.applyVelocity();
        int timer = getCustomFlag(SCRATCH_2E) - 1;
        setCustomFlag(SCRATCH_2E, timer);
        if (timer < 0) {
            setupDecelerationDash();
        }
    }

    private void setupDecelerationDash() {
        state.routine = ROUTINE_DECELERATION_DASH;
        mappingFrame = 4;
        state.x += 2;
        state.xFixed += 2 << 16;
    }

    private void updateDecelerationDash() {
        state.xVel -= 0x20;
        if (state.xVel == 0) {
            setupCameraApproachSwing();
            return;
        }
        state.applyVelocity();
    }

    private void setupCameraApproachSwing() {
        state.routine = ROUTINE_CAMERA_APPROACH_SWING;
        state.x = (state.x & 0xFF00) | (getCustomFlag(SCRATCH_3A) & 0xFF);
        state.xFixed = state.x << 16;
        state.xVel = 0;
        setupSwingVelocity();
    }

    private void setupSwingVelocity() {
        setCustomFlag(SCRATCH_3E, FIRST_SWING_Y_VELOCITY);
        state.yVel = FIRST_SWING_Y_VELOCITY;
        setCustomFlag(SCRATCH_40, FIRST_SWING_ACCELERATION);
        setCustomFlag(SCRATCH_38, getCustomFlag(SCRATCH_38) & ~1);
    }

    private void updateCameraApproachSwing() {
        swingUpAndDown();
        state.applyVelocity();

        ObjectServices svc = tryServices();
        if (svc != null && svc.camera() != null
                && state.x - (svc.camera().getX() & 0xFFFF) <= INIT_X_CAMERA_OFFSET) {
            setupChoppingAnimation();
        }
    }

    private void setupChoppingAnimation() {
        state.routine = ROUTINE_CHOPPING;
        animFrame = 0;
        animFrameTimer = 0;
        scriptBase = 0;
    }

    private void updateChoppingAnimation() {
        swingUpAndDown();
        state.applyVelocity();
        int animateResult = animateRawMultiDelay(CHOPPING_SCRIPT);
        if (animateResult == 1 && animFrame == 0x0A) {
            setCustomFlag(SCRATCH_42, getCustomFlag(SCRATCH_42) - 1);
            ObjectServices svc = tryServices();
            if (svc != null) {
                svc.playSfx(Sonic3kSfx.CHOP_TREE.id);
            }
        }
    }

    private int animateRawMultiDelay(int[] script) {
        return animateRawMultiDelay(script, true);
    }

    private int animateRawMultiDelay(int[] script, boolean runCallback) {
        animFrameTimer--;
        if (animFrameTimer >= 0) {
            return 0;
        }

        animFrame += 2;
        int command = script[scriptBase + animFrame] & 0xFF;
        if (command < 0x80) {
            mappingFrame = command;
            animFrameTimer = script[scriptBase + animFrame + 1] & 0xFF;
            return 1;
        }

        if (command == SCRIPT_JUMP_COMMAND) {
            // loc_845E4: script[scriptBase + animFrame + 1] is a signed relative offset added to
            // the CURRENT script base (not the command's own position), and the new base persists
            // in scriptBase (ROM $30(a0)) for subsequent calls. Falls through to loc_845F2 ($FC)
            // to emit the frame/delay pair now sitting at the re-pointed base.
            int offset = (byte) script[scriptBase + animFrame + 1];
            scriptBase += offset;
            command = SCRIPT_REPEAT_BASE_COMMAND;
        }

        if (command == SCRIPT_REPEAT_BASE_COMMAND) {
            // loc_845F2: re-emit the frame/delay pair sitting at the script base (unchanged for a
            // bare $FC, or freshly re-pointed by a preceding $F8), looping the animation there.
            mappingFrame = script[scriptBase] & 0xFF;
            animFrameTimer = script[scriptBase + 1] & 0xFF;
            animFrame = 0;
            return 1;
        }

        animFrame = 0;
        if (command == CHOPPING_CALLBACK_COMMAND) {
            if (runCallback) {
                runRawAnimationCallback();
            }
            return -1;
        }
        return 0;
    }

    private void runRawAnimationCallback() {
        setupSwingPeakWait();
    }

    private void setupSwingPeakWait() {
        state.routine = ROUTINE_SWING_PEAK_WAIT;
    }

    private void updateSwingPeakWait() {
        if (swingUpAndDown()) {
            setupFallRecoveryWait();
            return;
        }
        state.applyVelocity();
    }

    private void setupFallRecoveryWait() {
        state.routine = ROUTINE_FALL_RECOVERY_WAIT;
        state.yVel = 0x200;
        if (getCustomFlag(SCRATCH_42) != 1) {
            setCustomFlag(SCRATCH_2E, 0x0F);
        } else {
            setCustomFlag(SCRATCH_2E, 7);
        }
    }

    private void updateFallRecoveryWait() {
        state.applyVelocity();
        int timer = getCustomFlag(SCRATCH_2E) - 1;
        setCustomFlag(SCRATCH_2E, timer);
        if (timer >= 0) {
            return;
        }

        if (getCustomFlag(SCRATCH_42) != 1) {
            state.routine = ROUTINE_CHOPPING;
            setupSwingVelocity();
        } else if (currentPlayerCharacter() == PlayerCharacter.KNUCKLES) {
            state.routine = ROUTINE_FINAL_ESCAPE_SWING;
            setupSwingVelocity();
        } else {
            state.routine = ROUTINE_FINAL_STUCK_ANIMATION;
        }
    }

    private PlayerCharacter currentPlayerCharacter() {
        ObjectServices svc = tryServices();
        if (svc == null) {
            return PlayerCharacter.SONIC_AND_TAILS;
        }

        ZoneRuntimeState state = svc.zoneRuntimeState();
        if (state instanceof S3kZoneRuntimeState s3kState) {
            return s3kState.playerCharacter();
        }
        return PlayerCharacter.SONIC_AND_TAILS;
    }

    private void updateFinalStuckAnimation() {
        int animateResult = animateRawMultiDelay(FINAL_STUCK_SCRIPT);
        if (animateResult == 0) {
            return;
        }
        if (mappingFrame == 0x12) {
            ObjectServices svc = tryServices();
            if (svc != null) {
                svc.playSfx(Sonic3kSfx.CHOP_STUCK.id);
            }
        }
        if (animFrame == 0x0C) {
            state.x -= 0x20;
            state.xFixed -= 0x20 << 16;
        } else if (animateResult < 0) {
            setupFinalStuckLaunch();
        }
    }

    private void setupFinalStuckLaunch() {
        state.routine = ROUTINE_FINAL_STUCK_LAUNCH;
        setCustomFlag(SCRATCH_38, getCustomFlag(SCRATCH_38) | 0x40);
        state.xVel = -0x400;
        state.yVel = -0x400;
        ObjectServices svc = tryServices();
        if (svc != null) {
            svc.playSfx(Sonic3kSfx.CHOP_STUCK.id);
        }
    }

    private void updateFinalStuckLaunch() {
        animateRawMultiDelay(FINAL_LAUNCH_SCRIPT);
        applyVelocityWithGravity();
        if (state.yVel < 0) {
            return;
        }

        TerrainCheckResult floor = ObjectTerrainUtils.checkFloorDist(state.x, state.y, COLLISION_SIZE);
        if (floor.hasCollision() && floor.distance() < 0) {
            state.routine = ROUTINE_FINAL_BOUNCE_THRESHOLD;
            state.xVel = -0x200;
            state.yVel = -0x300;
        }
    }

    private void updateFinalBounceThreshold() {
        applyVelocityWithGravity();
        if (state.yVel >= 0x100) {
            setupFinalWaitBeforeReturn();
        }
    }

    private void setupFinalWaitBeforeReturn() {
        state.routine = ROUTINE_FINAL_WAIT_BEFORE_RETURN;
        setCustomFlag(SCRATCH_38, getCustomFlag(SCRATCH_38) & ~0x40);
        state.xVel = 0x100;
        state.yVel = -0x400;
        setCustomFlag(SCRATCH_2E, 0x10);
    }

    private void updateFinalWaitBeforeReturn() {
        state.applyVelocity();
        int timer = getCustomFlag(SCRATCH_2E) - 1;
        setCustomFlag(SCRATCH_2E, timer);
        if (timer < 0) {
            setupFinalReturnBounce();
        }
    }

    private void setupFinalReturnBounce() {
        state.routine = ROUTINE_FINAL_RETURN_BOUNCE;
        setCustomFlag(SCRATCH_38, getCustomFlag(SCRATCH_38) | 0x40);
    }

    private void updateFinalReturnBounce() {
        applyVelocityWithGravity();
        if (state.yVel < 0 || state.y < getCustomFlag(SCRATCH_3C)) {
            return;
        }

        state.routine = ROUTINE_FINAL_RETURN_WAIT;
        mappingFrame = 5;
        setCustomFlag(SCRATCH_38, getCustomFlag(SCRATCH_38) & ~0x40);
        state.y = getCustomFlag(SCRATCH_3C);
        state.yFixed = state.y << 16;
        state.yVel = 0;
        state.xVel = 0x400;
        setCustomFlag(SCRATCH_2E, 0x2A);
        animFrame = 0;
        animFrameTimer = 0;
        scriptBase = 0;
    }

    private void updateFinalReturnWait() {
        animateRawMultiDelay(FINAL_RETURN_WAIT_SCRIPT);
        state.applyVelocity();
        int timer = getCustomFlag(SCRATCH_2E) - 1;
        setCustomFlag(SCRATCH_2E, timer);
        if (timer < 0) {
            setupHorizontalDashWait();
        }
    }

    private void updateFinalEscapeSwing() {
        swingUpAndDown();
        state.applyVelocity();
        int animateResult = animateRawMultiDelay(CHOPPING_SCRIPT, false);
        if (animateResult == 1 && animFrame == 0x0A) {
            state.routine = ROUTINE_FINAL_ESCAPE_ANIMATE;
            mappingFrame = 0x0F;
            animFrame = 0;
            animFrameTimer = 0;
            scriptBase = 0;
            setCustomFlag(SCRATCH_42, getCustomFlag(SCRATCH_42) - 1);
            spawnChild(() -> new MhzMinibossEscapeShardInstance(state.x, state.y - 8, this));
        }
    }

    private void updateFinalEscapeAnimate() {
        swingUpAndDown();
        state.applyVelocity();
        if (animateRawMultiDelay(FINAL_ESCAPE_SCRIPT, false) < 0) {
            setupFinalEscapeWait();
        }
    }

    private void setupFinalEscapeWait() {
        state.routine = ROUTINE_FINAL_ESCAPE_WAIT;
        state.yVel = -0x80;
        setCustomFlag(SCRATCH_2E, 0x3F);
    }

    private void updateFinalEscapeWait() {
        animateRawMultiDelay(FINAL_ESCAPE_SCRIPT);
        state.applyVelocity();
        int timer = getCustomFlag(SCRATCH_2E) - 1;
        setCustomFlag(SCRATCH_2E, timer);
        if (timer < 0) {
            state.routine = ROUTINE_FINAL_ESCAPE_SIGNAL_SWING;
            setupSwingVelocity();
        }
    }

    private void updateFinalEscapeSignalSwing() {
        swingUpAndDown();
        state.applyVelocity();
        if ((getCustomFlag(SCRATCH_38) & 0x04) != 0) {
            state.routine = ROUTINE_FINAL_ESCAPE_CAMERA_RISE;
            mappingFrame = 0;
            state.yVel = -0x200;
        }
    }

    private void updateFinalEscapeCameraRise() {
        animateRawMultiDelay(FINAL_CAMERA_RISE_SCRIPT);
        state.applyVelocity();

        ObjectServices svc = tryServices();
        if (svc == null || svc.camera() == null) {
            return;
        }

        int targetY = (svc.camera().getY() & 0xFFFF) + 0x30;
        if (targetY >= state.y) {
            state.routine = ROUTINE_FINAL_ESCAPE_CAMERA_WAIT;
            state.y = targetY;
            state.yFixed = state.y << 16;
            setCustomFlag(SCRATCH_2E, 0x1F);
        }
    }

    private void updateFinalEscapeCameraWait() {
        animateRawMultiDelay(FINAL_CAMERA_RISE_SCRIPT);
        int timer = getCustomFlag(SCRATCH_2E) - 1;
        setCustomFlag(SCRATCH_2E, timer);
        if (timer < 0) {
            state.routine = ROUTINE_FINAL_ESCAPE_UPWARD;
            state.xVel = -0x400;
            state.yVel = 0x700;
        }
    }

    private void updateFinalEscapeUpward() {
        animateRawMultiDelay(FINAL_CAMERA_RISE_SCRIPT);
        int nextYVelocity = state.yVel - 0x48;
        if (nextYVelocity >= -0x700) {
            state.yVel = nextYVelocity;
        }
        state.applyVelocity();

        ObjectServices svc = tryServices();
        if (svc == null || svc.camera() == null) {
            return;
        }

        int targetY = (svc.camera().getY() & 0xFFFF) - 0x80;
        if (targetY >= state.y) {
            state.routine = ROUTINE_FINAL_ESCAPE_RETURN_WAIT;
            mappingFrame = 5;
            state.x = (svc.camera().getX() & 0xFFFF) + 0x30;
            state.y = (svc.camera().getY() & 0xFFFF) - 0x5C;
            state.xFixed = state.x << 16;
            state.yFixed = state.y << 16;
            state.xVel = 0x400;
            state.yVel = 0x400;
            setCustomFlag(SCRATCH_2E, 0x37);
            setCustomFlag(SCRATCH_38, getCustomFlag(SCRATCH_38) & ~0x04);
            animFrame = 0;
            animFrameTimer = 0;
            scriptBase = 0;
        }
    }

    private void updateFinalEscapeReturnWait() {
        int nextYVelocity = state.yVel - 0x10;
        if (nextYVelocity >= 0) {
            state.yVel = nextYVelocity;
        }
        state.applyVelocity();
        int timer = getCustomFlag(SCRATCH_2E) - 1;
        setCustomFlag(SCRATCH_2E, timer);
        if (timer < 0) {
            setupHorizontalDashWait();
        }
    }

    private void updateDefeatHandoff() {
        tickDefeatExplosionController();
        if (defeatHandoffQueued) {
            return;
        }

        int timer = getCustomFlag(SCRATCH_2E) - 1;
        setCustomFlag(SCRATCH_2E, timer);
        if (timer >= 0) {
            return;
        }

        state.renderFlags &= ~0x80;
        setCustomFlag(SCRATCH_2E, LEVEL_MUSIC_FADE_TIME - 1);
        ObjectServices svc = tryServices();
        if (svc != null) {
            int levelMusicId = svc.getCurrentLevelMusicId();
            if (levelMusicId > 0) {
                spawnChild(() -> new SongFadeTransitionInstance(LEVEL_MUSIC_FADE_TIME, levelMusicId));
            }
            spawnChild(() -> new S3kBossDefeatSignpostFlow(
                    state.x, svc.currentAct(), S3kBossDefeatSignpostFlow.CleanupAction.NONE));
        }
        defeatHandoffQueued = true;
        setDestroyed(true);
    }

    private void tickDefeatExplosionController() {
        if (defeatExplosionTimer <= 0) {
            return;
        }
        defeatExplosionIntervalCounter--;
        if (defeatExplosionIntervalCounter >= 0) {
            return;
        }

        defeatExplosionTimer--;
        if (defeatExplosionTimer <= 0) {
            return;
        }

        ObjectServices svc = tryServices();
        if (svc != null) {
            int random = svc.rng().nextRaw();
            int xOffset = (random & ((DEFEAT_EXPLOSION_X_RANGE * 2) - 1)) - DEFEAT_EXPLOSION_X_RANGE;
            int yOffset = ((random >> 8) & ((DEFEAT_EXPLOSION_Y_RANGE * 2) - 1)) - DEFEAT_EXPLOSION_Y_RANGE;
            svc.playSfx(Sonic3kSfx.EXPLODE.id);
            spawnChild(() -> new S3kBossExplosionChild(state.x + xOffset, state.y + yOffset));
        }
        defeatExplosionIntervalCounter = DEFEAT_EXPLOSION_INTERVAL - 1;
    }

    private void applyVelocityWithGravity() {
        state.xFixed += state.xVel << 8;
        state.yFixed += state.yVel << 8;
        state.yVel += 0x38;
        state.updatePositionFromFixed();
    }
}
