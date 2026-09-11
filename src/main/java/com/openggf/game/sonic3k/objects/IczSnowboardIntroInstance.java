package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.events.S3kIczEventWriteSupport;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.constants.Sonic3kAnimationIds;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.objects.SubpixelMotion;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.ObjectControlState;

import java.util.List;

/**
 * Sonic-alone IceCap Act 1 snowboard intro controller.
 *
 * <p>ROM reference: {@code Obj_LevelIntroICZ1} at
 * {@code docs/skdisasm/sonic3k.asm:76984}. The object owns the startup lock,
 * launches Sonic onto the board, keeps input locked through the ride, applies
 * the two ROM slope tables, and releases Sonic after the crash at the end of
 * the snowboard route.
 */
public class IczSnowboardIntroInstance extends AbstractObjectInstance implements SpawnRewindRecreatable {
    public static final int INITIAL_SNOWBOARD_X = 0x00C0;
    public static final int INITIAL_SNOWBOARD_Y = 0x0170;

    private static final int PLAYER_INITIAL_X_SPEED = 0x0800;
    private static final int PLAYER_INITIAL_Y_SPEED = 0x0280;
    private static final int PLAYER_INITIAL_G_SPEED = 0x0800;
    private static final int PLAYER_JUMP_X_SPEED = 0x0400;
    private static final int PLAYER_JUMP_Y_SPEED = -0x0800;
    private static final int PLAYER_BOARD_LAUNCH_Y = 0x0159;
    private static final int PLAYER_BOARD_LAUNCH_Y_SUBPIXEL = 0x8800;
    private static final int BOARD_JUMP_Y_SPEED = -0x0600;
    private static final int SNOWBOARD_HANDOFF_X = 0x0184;
    private static final int FIRST_SCRIPT_MIN_X = 0x1310;
    private static final int FIRST_SCRIPT_MAX_X = 0x1330;
    private static final int SECOND_SCRIPT_MIN_X = 0x2210;
    private static final int SECOND_SCRIPT_MAX_X = 0x2230;
    private static final int CRASH_X = 0x3880;
    /** ROM loc_394A0 airborne branch: cmpi.w #$1000,x_vel / move.w #$1000,x_vel (sonic3k.asm:76802-76805). */
    private static final int AIRBORNE_MAX_X_SPEED = 0x1000;
    /** ROM loc_394E2: cmpi.w #-$200,y_vel / move.w #-$200,y_vel (sonic3k.asm:76806-76809). */
    private static final int AIRBORNE_MIN_Y_SPEED = -0x0200;
    private static final int POST_CRASH_X_SPEED = -0x0200;
    private static final int POST_CRASH_Y_SPEED = -0x0400;
    private static final int STARTUP_OBJECT_CONTROL_FRAMES = 30;
    private static final int MIN_SNOWBOARD_G_SPEED = 0x1000;
    private static final int FAST_FRAME_G_SPEED_DECEL = 8;
    private static final int QUIET_SKID_INTERVAL = 16;
    private static final int SNOWBOARD_OBJECT_GRAVITY = 0x28;

    // ROM byte_394F2: angle bucket -> Sonic snowboarding animation id.
    private static final int[] ANGLE_ANIMATION_TABLE = {
            4, 5, 2, 1, 1, 1, 1, 1, 0, 1, 2, 0, 1, 2, 0, 4
    };
    private static final int[][] SONIC_SNOWBOARD_ANIMATION_FRAMES = {
            {6, 7, 8},
            {9},
            {10},
            {6, 6, 6, 6, 6, 6, 6, 7, 8, 7},
            {11},
            {12}
    };
    // ROM byte_39482 ends with $FE,1. Animate_Sprite subtracts that backstep
    // from the terminator cursor, so animation 0 repeats frame 8 until landing.
    private static final int[] SONIC_SNOWBOARD_ANIMATION_LOOP_STARTS = {2, 0, 0, 0, 0, 0};

    // binclude "Levels/ICZ/Misc/ICZ Snowboard Slope 1.bin"
    private static final int[][] SLOPE_1 = {
            {0x1340, 0x0210, 0x01}, {0x1350, 0x0220, 0x01}, {0x135C, 0x0230, 0x01},
            {0x1361, 0x0240, 0x01}, {0x1367, 0x0250, 0x02}, {0x136C, 0x0260, 0x02},
            {0x136E, 0x0270, 0x02}, {0x136E, 0x0280, 0x02}, {0x136D, 0x0290, 0x02},
            {0x136A, 0x02A0, 0x03}, {0x1364, 0x02B0, 0x03}, {0x135D, 0x02C0, 0x03},
            {0x1340, 0x02D0, 0x03}, {0x1329, 0x02E0, 0x03}, {0x12F1, 0x02F0, 0x03},
            {0x12D5, 0x0300, 0x04}, {0x12C4, 0x0310, 0x04}, {0x12B7, 0x0320, 0x04},
            {0x12A9, 0x0330, 0x04}, {0x129F, 0x0340, 0x04}, {0x129A, 0x0350, 0x04},
            {0x1293, 0x0360, 0x02}, {0x1293, 0x0370, 0x02}, {0x1294, 0x0380, 0x02},
            {0x1296, 0x0390, 0x05}, {0x129C, 0x03A2, 0x05}, {0x12B2, 0x03C0, 0x05},
            {0x12CB, 0x03DC, 0x01}, {0x12E3, 0x03D9, 0x0C}, {0x12F9, 0x03E2, 0x0C},
            {0x1313, 0x03F0, 0x0C}, {0x1332, 0x0400, 0x0C}, {0x1342, 0x0408, 0x0C}
    };

    // binclude "Levels/ICZ/Misc/ICZ Snowboard Slope 2.bin"
    private static final int[][] SLOPE_2 = {
            {0x2240, 0x0190, 0x01}, {0x2250, 0x01A0, 0x01}, {0x225C, 0x01B0, 0x01},
            {0x2261, 0x01C0, 0x01}, {0x2267, 0x01D0, 0x02}, {0x226C, 0x01E0, 0x02},
            {0x226E, 0x01F0, 0x02}, {0x226E, 0x0200, 0x02}, {0x226D, 0x0210, 0x02},
            {0x226A, 0x0220, 0x03}, {0x2264, 0x0230, 0x03}, {0x225D, 0x0240, 0x03},
            {0x2240, 0x0250, 0x03}, {0x2229, 0x0260, 0x03}, {0x21F1, 0x0270, 0x03},
            {0x21D5, 0x0280, 0x04}, {0x21C4, 0x0290, 0x04}, {0x21B7, 0x02A0, 0x04},
            {0x21A9, 0x02B0, 0x04}, {0x219F, 0x02C0, 0x04}, {0x219A, 0x02D0, 0x04},
            {0x2193, 0x02E0, 0x02}, {0x2193, 0x02F0, 0x02}, {0x2194, 0x0300, 0x02},
            {0x2196, 0x0310, 0x05}, {0x219C, 0x0322, 0x05}, {0x21B2, 0x0340, 0x05},
            {0x21CB, 0x035C, 0x01}, {0x21E3, 0x0359, 0x0C}, {0x21F9, 0x0362, 0x0C},
            {0x2213, 0x0370, 0x0C}, {0x2232, 0x0380, 0x0C}, {0x2242, 0x0388, 0x0C}
    };

    private enum State {
        STARTUP_LOCK,
        WAIT_FOR_BOARD_JUMP,
        BOARD_LAUNCH,
        SNOWBOARDING,
        SCRIPTED_SLOPE,
        CRASHED
    }

    // ROM loc_39586/loc_3984E crash-release constants (the timed screen shake,
    // the board fling velocities and the board's downward reposition).
    private static final int CRASH_SCREEN_SHAKE_FRAMES = 0x14;
    private static final int BOARD_FLY_AWAY_Y_DROP = 0x14;
    private static final int BOARD_FLY_AWAY_X_SPEED = -0x0200;
    private static final int BOARD_FLY_AWAY_Y_SPEED = -0x0400;
    private static final int BOARD_FLY_AWAY_MAX_MAPPING_FRAME = 9;
    private static final int BOARD_ON_SCREEN_HALF_EXTENT = 0x10;

    private State state = State.STARTUP_LOCK;
    private final SubpixelMotion.State boardMotion = new SubpixelMotion.State(
            INITIAL_SNOWBOARD_X, INITIAL_SNOWBOARD_Y, 0, 0, 0, 0);
    private int startupTimer = STARTUP_OBJECT_CONTROL_FRAMES;
    private int boardFrameTimer;
    private int currentX = INITIAL_SNOWBOARD_X;
    private int currentY = INITIAL_SNOWBOARD_Y;
    private int currentMappingFrame = 8;
    private int activeSlope = 0;
    private int slopeIndex = 0;
    private int overlayAnimationId = 1;
    private int overlayAnimationTimer;
    private int overlayAnimationCursor;
    private int lastOverlayAnimationId = -1;
    private boolean initialized;
    private boolean sonicSnowboardOverlayActive;
    private boolean sonicSnowboardTouchedGround;

    public IczSnowboardIntroInstance(ObjectSpawn spawn) {
        super(spawn, "ICZSnowboardIntro");
        updateDynamicSpawn(currentX, currentY);
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = focusedPlayer();
        if (player == null) {
            setDestroyed(true);
            return;
        }
        if (!initialized) {
            initializePlayer(player);
        }

        switch (state) {
            case STARTUP_LOCK -> updateStartupLock(player);
            case WAIT_FOR_BOARD_JUMP -> updateWaitForBoardJump(player);
            case BOARD_LAUNCH -> updateBoardLaunch(player);
            case SNOWBOARDING -> updateSnowboarding(player, vIntRunCount);
            case SCRIPTED_SLOPE -> updateScriptedSlope(player);
            case CRASHED -> setDestroyed(true);
        }
        if (sonicSnowboardOverlayActive && !isDestroyed()) {
            // Native dust is an independent loc_398E6 child that runs for the
            // whole controlled snowboard ride, including scripted-slope states.
            spawnDustIfNeeded(player);
        }
        updateDynamicSpawn(currentX, currentY);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (sonicSnowboardOverlayActive) {
            PatternSpriteRenderer sonicRenderer = IczSnowboardArtLoader.sonicSnowboardRenderer(services());
            if (sonicRenderer != null && sonicRenderer.isReady()) {
                sonicRenderer.drawFrameIndex(clampFrame(currentMappingFrame,
                        Sonic3kConstants.MAP_SONIC_SNOWBOARD_FRAMES), currentX, currentY);
            }
        } else {
            PatternSpriteRenderer boardRenderer = IczSnowboardArtLoader.snowboardRenderer(services());
            if (boardRenderer != null && boardRenderer.isReady()) {
                boardRenderer.drawFrameIndex(clampFrame(currentMappingFrame,
                        Sonic3kConstants.MAP_SNOWBOARD_FRAMES), currentX, currentY);
            }
        }
    }

    @Override
    public boolean isHighPriority() {
        return false;
    }

    @Override
    public void setDestroyed(boolean destroyed) {
        if (destroyed && !isDestroyed()) {
            releasePlayerLocks();
        }
        super.setDestroyed(destroyed);
    }

    public int getCurrentMappingFrame() {
        return currentMappingFrame;
    }

    boolean isSonicSnowboardOverlayActiveForTest() {
        return sonicSnowboardOverlayActive;
    }

    String stateNameForTest() {
        return state.name();
    }

    int getCurrentXForTest() {
        return currentX;
    }

    int getCurrentYForTest() {
        return currentY;
    }

    static int groundSnowboardMappingFrameForAngle(int angle) {
        int bucket = ((angle + 5) & 0xFF) >> 4;
        int animationId = ANGLE_ANIMATION_TABLE[bucket & 0x0F];
        return firstSonicSnowboardMappingFrame(animationId);
    }

    public static void applyInitialPlayerLock(AbstractPlayableSprite player) {
        if (player == null) {
            return;
        }
        player.setXSpeed((short) PLAYER_INITIAL_X_SPEED);
        player.setYSpeed((short) PLAYER_INITIAL_Y_SPEED);
        player.setGSpeed((short) PLAYER_INITIAL_G_SPEED);
        player.setAir(true);
        player.setJumping(false);
        player.setAnimationId(Sonic3kAnimationIds.ROLL);
        player.setMappingFrame(0);
        int centreY = player.getCentreY();
        player.setRolling(true);
        player.setCentreYPreserveSubpixel((short) centreY);
        holdPlayerControlLock(player);
        // ROM Obj_LevelIntroICZ1 writes object_control = #3 here: low-bit object ownership,
        // not the bit-7 touch-response suppression state.
        ObjectControlState.nativeBits0To6CpuAllowedMovementSuppressed().applyTo(player);
        player.setObjectMappingFrameControl(true);
        player.clearForcedInputMask();
    }

    public void primePostStartupWaitForBoardHandoff(AbstractPlayableSprite player) {
        initialized = true;
        state = State.WAIT_FOR_BOARD_JUMP;
        startupTimer = 0;
        boardFrameTimer = 0;
        currentX = INITIAL_SNOWBOARD_X;
        currentY = INITIAL_SNOWBOARD_Y;
        currentMappingFrame = 8;
        activeSlope = 0;
        slopeIndex = 0;
        overlayAnimationId = 1;
        overlayAnimationTimer = 0;
        overlayAnimationCursor = 0;
        lastOverlayAnimationId = -1;
        sonicSnowboardOverlayActive = false;
        sonicSnowboardTouchedGround = false;
        boardMotion.x = INITIAL_SNOWBOARD_X;
        boardMotion.y = INITIAL_SNOWBOARD_Y;
        boardMotion.xSub = 0;
        boardMotion.ySub = 0;
        boardMotion.xVel = 0;
        boardMotion.yVel = 0;
        updateDynamicSpawn(currentX, currentY);
        if (player != null) {
            holdPlayerControlLock(player);
            releaseStartupObjectControl(player);
            player.clearForcedInputMask();
        }
    }

    private void initializePlayer(AbstractPlayableSprite player) {
        initialized = true;
        applyInitialPlayerLock(player);
    }

    private void updateStartupLock(AbstractPlayableSprite player) {
        holdPlayerControlLock(player);
        player.clearForcedInputMask();
        if (--startupTimer > 0) {
            return;
        }
        releaseStartupObjectControl(player);
        state = State.WAIT_FOR_BOARD_JUMP;
    }

    private void updateWaitForBoardJump(AbstractPlayableSprite player) {
        holdPlayerControlLock(player);
        releaseStartupObjectControl(player);
        player.clearForcedInputMask();
        if (!player.getAir()) {
            maintainPreBoardLaunchSpeed(player);
        }
        currentX = INITIAL_SNOWBOARD_X;
        currentY = INITIAL_SNOWBOARD_Y;
        if (player.getCentreX() < INITIAL_SNOWBOARD_X) {
            return;
        }
        currentX = player.getCentreX();
        boardMotion.x = player.getCentreX();
        boardMotion.y = INITIAL_SNOWBOARD_Y;
        boardMotion.xSub = 0;
        boardMotion.ySub = 0;
        boardMotion.xVel = 0;
        boardMotion.yVel = BOARD_JUMP_Y_SPEED;
        boardFrameTimer = 0;
        player.setCentreYPreserveSubpixel((short) PLAYER_BOARD_LAUNCH_Y);
        player.setSubpixelRaw(player.getXSubpixelRaw(), PLAYER_BOARD_LAUNCH_Y_SUBPIXEL);
        player.setAir(true);
        player.setXSpeed((short) PLAYER_JUMP_X_SPEED);
        player.setYSpeed((short) PLAYER_JUMP_Y_SPEED);
        state = State.BOARD_LAUNCH;
    }

    private void updateBoardLaunch(AbstractPlayableSprite player) {
        holdPlayerControlLock(player);
        releaseStartupObjectControl(player);
        player.clearForcedInputMask();
        currentX = player.getCentreX();
        boardMotion.x = currentX;
        if (player.getCentreX() >= SNOWBOARD_HANDOFF_X) {
            beginSnowboardingOverlay(player);
            return;
        }
        animateFreeBoard();
        SubpixelMotion.moveSprite2(boardMotion);
        boardMotion.yVel += SNOWBOARD_OBJECT_GRAVITY;
        currentY = boardMotion.y;
    }

    private void updateSnowboarding(AbstractPlayableSprite player, int vIntRunCount) {
        holdPlayerControlLock(player);
        player.clearForcedInputMask();
        currentX = player.getCentreX();
        currentY = player.getCentreY();
        // ROM loc_3943A follows Sonic while airborne; loc_394A0 speed maintenance starts after that routine handoff.
        boolean speedMaintenanceActive = sonicSnowboardTouchedGround;
        queueSnowboardJump(player);
        currentMappingFrame = resolveSonicSnowboardMappingFrame(player);

        if ((vIntRunCount & (QUIET_SKID_INTERVAL - 1)) == 0 && !player.getAir()) {
            services().playSfx(Sonic3kSfx.SLIDE_SKID_QUIET.id);
        }
        if (speedMaintenanceActive) {
            if (player.getGSpeed() < MIN_SNOWBOARD_G_SPEED) {
                player.setGSpeed((short) MIN_SNOWBOARD_G_SPEED);
            } else if (currentMappingFrame == 8 && player.getGSpeed() >= MIN_SNOWBOARD_G_SPEED) {
                player.setGSpeed((short) (player.getGSpeed() - FAST_FRAME_G_SPEED_DECEL));
            }
        }

        // ROM loc_394A0 (sonic3k.asm:76797-76815) branches on the player's air
        // bit BEFORE the scripted-slope x windows are ever looked at:
        //     btst #Status_InAir,status(a2)
        //     beq.s loc_39502      ; grounded -> skid sfx + the x window tests
        //     move.b #0,anim(a0)   ; airborne -> velocity caps, then bra loc_39554
        // The x window tests live entirely under loc_39502 (sonic3k.asm:76816),
        // so an airborne player crossing $1310..$132F never enters the slope
        // script. Testing the windows unconditionally made the engine hand the
        // player to the slope table mid-jump, freezing x_sub/y_sub (the table
        // writes only the position words) and stalling y_vel.
        if (player.getAir()) {
            // ROM loc_394A0 airborne branch (sonic3k.asm:76799-76811). Both caps
            // are writes to the player, so they belong here even though they are
            // no-ops while the board arc stays inside these limits.
            if (player.getXSpeed() != 0 && player.getXSpeed() >= AIRBORNE_MAX_X_SPEED) {
                player.setXSpeed((short) AIRBORNE_MAX_X_SPEED);
            }
            if (player.getYSpeed() < AIRBORNE_MIN_Y_SPEED) {
                player.setYSpeed((short) AIRBORNE_MIN_Y_SPEED);
            }
        } else {
            if (player.getCentreX() >= FIRST_SCRIPT_MIN_X && player.getCentreX() < FIRST_SCRIPT_MAX_X) {
                beginScriptedSlope(player, 1);
                return;
            }
            if (player.getCentreX() >= SECOND_SCRIPT_MIN_X && player.getCentreX() < SECOND_SCRIPT_MAX_X) {
                beginScriptedSlope(player, 2);
                return;
            }
        }
        if (player.getCentreX() >= CRASH_X && player.getXSpeed() == 0) {
            crash(player);
        }
    }

    private void beginScriptedSlope(AbstractPlayableSprite player, int slope) {
        activeSlope = slope;
        slopeIndex = 0;
        sonicSnowboardOverlayActive = true;
        player.setHidden(true);
        ObjectControlState.nativeBit7FullControl().applyTo(player);
        state = State.SCRIPTED_SLOPE;
    }

    private void updateScriptedSlope(AbstractPlayableSprite player) {
        holdPlayerControlLock(player);
        int[][] table = activeSlope == 1 ? SLOPE_1 : SLOPE_2;
        if (slopeIndex >= table.length) {
            player.setTopSolidBit((byte) 0x0E);
            player.setLrbSolidBit((byte) 0x0F);
            ObjectControlState.nativeBits0To6CpuAllowedMovementActive().applyTo(player);
            state = State.SNOWBOARDING;
            return;
        }
        int[] entry = table[slopeIndex++];
        currentX = entry[0];
        currentY = entry[1];
        currentMappingFrame = entry[2];
        player.setCentreXPreserveSubpixel((short) currentX);
        player.setCentreYPreserveSubpixel((short) currentY);
        if (slopeIndex >= table.length) {
            player.setTopSolidBit((byte) 0x0E);
            player.setLrbSolidBit((byte) 0x0F);
            ObjectControlState.nativeBits0To6CpuAllowedMovementActive().applyTo(player);
            state = State.SNOWBOARDING;
        }
    }

    private void crash(AbstractPlayableSprite player) {
        // ROM loc_39586: the Sonic-snowboard overlay releases the player (anim
        // $19, crash velocities), sets a #$14 timed screen shake, plays sfx_Crash
        // and deletes itself. In the ROM the separate board object (loc_3984E ->
        // loc_398A6) then flings the snowboard away; here that becomes an
        // independent child so this controller keeps its original crash lifetime.
        state = State.CRASHED;
        player.setAir(true);
        player.setXSpeed((short) POST_CRASH_X_SPEED);
        player.setYSpeed((short) POST_CRASH_Y_SPEED);
        player.setAnimationId(0x19);
        releasePlayerLocks(player);
        releaseDormantSidekicksForCrashHandoff();
        S3kIczEventWriteSupport.triggerScreenShake(services(), CRASH_SCREEN_SHAKE_FRAMES);
        services().playSfx(Sonic3kSfx.CRASH.id);
        spawnBoardFlyAway(player);
        setDestroyed(true);
    }

    /**
     * ROM loc_3984E -> loc_398A6: once the player releases object control, the
     * snowboard object drops #$14 pixels, is flung up and to the left, and spins
     * through its mapping frames until it scrolls off screen (loc_398B2 deletes
     * it when render_flags loses the on-screen bit). Spawned as a standalone
     * child so it outlives this controller's crash-frame destruction.
     */
    private void spawnBoardFlyAway(AbstractPlayableSprite player) {
        int startX = player.getCentreX();
        int startY = player.getCentreY() + BOARD_FLY_AWAY_Y_DROP;
        ObjectSpawn spawn = buildSpawnAt(startX, startY);
        spawnChild(() -> new SnowboardFlyAwayInstance(spawn, startX, startY));
    }


    private void releaseDormantSidekicksForCrashHandoff() {
        for (PlayableEntity sidekickEntity : services().playerQuery().playersFor(
                ObjectPlayerParticipationPolicy.ALL_ENGINE_PLAYERS)) {
            if (sidekickEntity instanceof AbstractPlayableSprite sidekick
                    && sidekick.getCpuController() != null) {
                sidekick.getCpuController().releaseDormantMarkerForLevelEvent();
            }
        }
    }

    private void spawnDustIfNeeded(AbstractPlayableSprite player) {
        if (player.getAir()) {
            return;
        }
        // loc_398FA calls sub_39924 twice on every grounded frame, then six
        // additional times for the heavy-spray snowboard frames 7 and 8.
        int count = (currentMappingFrame == 7 || currentMappingFrame == 8) ? 8 : 2;
        for (int i = 0; i < count; i++) {
            int random = services().rng().nextRaw();
            int x = player.getCentreX() - (random & 0x0F);
            int y = player.getCentreY() + 0x14 - ((random >>> 16) & 0x0F);
            int yVel = -(random & 0x01FF);
            ObjectSpawn spawn = new ObjectSpawn(x, y, 0, 0, 0, false, yVel);
            final int dustX = x;
            final int dustY = y;
            final int dustYVel = yVel;
            spawnChild(() -> new SnowboardDustInstance(spawn, dustX, dustY, -0x0100, dustYVel));
        }
    }

    private void queueSnowboardJump(AbstractPlayableSprite player) {
        if (player.getAir() || !player.isJumpJustPressed()) {
            return;
        }
        // ROM loc_3984E copies raw A/B/C/Start into Ctrl_1_logical after Sonic's
        // player slot has already run for the frame; the normal jump routine
        // consumes that logical edge on the following player frame.
        player.setForcedJumpPress(true);
        overlayAnimationId = 0;
        lastOverlayAnimationId = -1;
    }

    private void beginSnowboardingOverlay(AbstractPlayableSprite player) {
        currentX = player.getCentreX();
        currentY = player.getCentreY();
        currentMappingFrame = 9;
        overlayAnimationId = 1;
        overlayAnimationTimer = 0;
        overlayAnimationCursor = 0;
        lastOverlayAnimationId = -1;
        sonicSnowboardOverlayActive = true;
        sonicSnowboardTouchedGround = false;
        player.setMappingFrame(0);
        player.setHidden(true);
        // ROM loc_397FA writes object_control = #2 for the snowboard overlay.
        // Movement remains active in the engine, but touch responses still run.
        ObjectControlState.nativeBits0To6CpuAllowedMovementActive().applyTo(player);
        player.setObjectMappingFrameControl(true);
        holdPlayerControlLock(player);
        state = State.SNOWBOARDING;
    }

    private static void holdPlayerControlLock(AbstractPlayableSprite player) {
        player.setControlLocked(true);
        player.queueControlLockedForNextFrame(true);
    }

    private void releaseStartupObjectControl(AbstractPlayableSprite player) {
        player.setObjectMappingFrameControl(false);
        ObjectControlState.none().applyTo(player);
    }

    private void animateFreeBoard() {
        if (--boardFrameTimer >= 0) {
            return;
        }
        boardFrameTimer = 1;
        currentMappingFrame++;
        if (currentMappingFrame >= 9) {
            currentMappingFrame = 1;
        }
    }

    private int resolveSonicSnowboardMappingFrame(AbstractPlayableSprite player) {
        if (!sonicSnowboardTouchedGround) {
            if (!player.getAir()) {
                sonicSnowboardTouchedGround = true;
            }
            overlayAnimationId = 1;
            return advanceSonicSnowboardAnimation(overlayAnimationId);
        }
        if (player.getAir()) {
            overlayAnimationId = 0;
            clampAirborneSnowboardSpeed(player);
        } else {
            overlayAnimationId = angleAnimationId(player.getAngle() & 0xFF);
        }
        return advanceSonicSnowboardAnimation(overlayAnimationId);
    }

    private int advanceSonicSnowboardAnimation(int animationId) {
        boolean changedAnimation = false;
        if (animationId != lastOverlayAnimationId) {
            lastOverlayAnimationId = animationId;
            overlayAnimationTimer = 7;
            overlayAnimationCursor = 0;
            changedAnimation = true;
        }
        int[] frames = SONIC_SNOWBOARD_ANIMATION_FRAMES[
                Math.max(0, Math.min(animationId, SONIC_SNOWBOARD_ANIMATION_FRAMES.length - 1))];
        if (frames.length <= 1) {
            return frames[0];
        }
        if (!changedAnimation && --overlayAnimationTimer < 0) {
            overlayAnimationTimer = 7;
            overlayAnimationCursor++;
            if (overlayAnimationCursor >= frames.length) {
                overlayAnimationCursor = SONIC_SNOWBOARD_ANIMATION_LOOP_STARTS[
                        Math.max(0, Math.min(animationId, SONIC_SNOWBOARD_ANIMATION_LOOP_STARTS.length - 1))];
            }
        }
        return frames[overlayAnimationCursor];
    }

    private void clampAirborneSnowboardSpeed(AbstractPlayableSprite player) {
        if (player.getXSpeed() >= MIN_SNOWBOARD_G_SPEED) {
            player.setXSpeed((short) MIN_SNOWBOARD_G_SPEED);
        }
        if (player.getYSpeed() < -0x0200) {
            player.setYSpeed((short) -0x0200);
        }
    }

    private static int angleAnimationId(int angle) {
        int bucket = ((angle + 5) & 0xFF) >> 4;
        return ANGLE_ANIMATION_TABLE[bucket & 0x0F];
    }

    private static int firstSonicSnowboardMappingFrame(int animationId) {
        int[] frames = SONIC_SNOWBOARD_ANIMATION_FRAMES[
                Math.max(0, Math.min(animationId, SONIC_SNOWBOARD_ANIMATION_FRAMES.length - 1))];
        return frames[0];
    }

    private void maintainPreBoardLaunchSpeed(AbstractPlayableSprite player) {
        if (player.getCentreX() >= SNOWBOARD_HANDOFF_X) {
            return;
        }
        if (player.getXSpeed() < PLAYER_INITIAL_X_SPEED) {
            player.setXSpeed((short) PLAYER_INITIAL_X_SPEED);
        }
        if (player.getGSpeed() < PLAYER_INITIAL_G_SPEED) {
            player.setGSpeed((short) PLAYER_INITIAL_G_SPEED);
        }
        player.setRolling(true);
    }

    private AbstractPlayableSprite focusedPlayer() {
        return services().camera().getFocusedSprite();
    }

    private void releasePlayerLocks() {
        AbstractPlayableSprite player = focusedPlayer();
        if (player != null) {
            releasePlayerLocks(player);
        }
    }

    private void releasePlayerLocks(AbstractPlayableSprite player) {
        player.setControlLocked(false);
        ObjectControlState.none().applyTo(player);
        player.setObjectMappingFrameControl(false);
        player.setHidden(false);
        player.clearForcedInputMask();
        player.clearQueuedControlState();
    }

    private int clampFrame(int frame, int frameCount) {
        if (frameCount <= 0) {
            return 0;
        }
        if (frame < 0) {
            return 0;
        }
        return Math.min(frame, frameCount - 1);
    }

    /**
     * ROM Obj_LevelIntroICZ1 loc_398A6/loc_398B2: the discarded snowboard flung
     * away after the crash. It spins through mapping frames 1..8, moves via
     * MoveSprite2 under gravity, and deletes once it leaves the render bounds
     * (the ROM's {@code render_flags} on-screen bit check).
     */
    private static final class SnowboardFlyAwayInstance extends AbstractObjectInstance
            implements SpawnRewindRecreatable {
        private final SubpixelMotion.State motion;
        private int mappingFrame = 1;
        private int frameTimer = 1;

        private SnowboardFlyAwayInstance(ObjectSpawn spawn, int startX, int startY) {
            this(spawn);
            motion.x = startX;
            motion.y = startY;
            updateDynamicSpawn(startX, startY);
        }

        private SnowboardFlyAwayInstance(ObjectSpawn spawn) {
            super(spawn, "ICZSnowboardFlyAway");
            motion = new SubpixelMotion.State(spawn.x(), spawn.y(), 0, 0,
                    BOARD_FLY_AWAY_X_SPEED, BOARD_FLY_AWAY_Y_SPEED);
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity player) {
            if (--frameTimer < 0) {
                frameTimer = 1;
                if (++mappingFrame >= BOARD_FLY_AWAY_MAX_MAPPING_FRAME) {
                    mappingFrame = 1;
                }
            }
            SubpixelMotion.moveSprite2(motion);
            motion.yVel += SNOWBOARD_OBJECT_GRAVITY;
            updateDynamicSpawn(motion.x, motion.y);
            if (!isWithinRenderSpriteBounds(BOARD_ON_SCREEN_HALF_EXTENT, BOARD_ON_SCREEN_HALF_EXTENT)) {
                setDestroyed(true);
            }
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            PatternSpriteRenderer renderer = IczSnowboardArtLoader.snowboardRenderer(services());
            if (renderer != null && renderer.isReady()) {
                int frameCount = Sonic3kConstants.MAP_SNOWBOARD_FRAMES;
                int frame = frameCount > 0 ? Math.min(mappingFrame, frameCount - 1) : 0;
                renderer.drawFrameIndex(frame, motion.x, motion.y);
            }
        }

        @Override
        public boolean isHighPriority() {
            return false;
        }
    }

    private static final class SnowboardDustInstance extends AbstractObjectInstance implements SpawnRewindRecreatable {
        private int x;
        private int y;
        private int xSub;
        private int ySub;
        private int xVel;
        private int yVel;
        private int frame;

        private SnowboardDustInstance(ObjectSpawn spawn, int x, int y, int xVel, int yVel) {
            this(spawn);
            updateDynamicSpawn(x, y);
        }

        private SnowboardDustInstance(ObjectSpawn spawn) {
            super(spawn, "ICZSnowboardDust");
            this.x = spawn.x();
            this.y = spawn.y();
            this.xVel = -0x0100;
            this.yVel = (short) spawn.rawYWord();
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity player) {
            xSub += xVel;
            ySub += yVel;
            x += xSub >> 8;
            y += ySub >> 8;
            xSub &= 0xFF;
            ySub &= 0xFF;
            updateDynamicSpawn(x, y);
            if (++frame >= Sonic3kConstants.MAP_SNOWBOARD_DUST_FRAMES) {
                setDestroyed(true);
            }
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            PatternSpriteRenderer renderer = IczSnowboardArtLoader.dustRenderer(services());
            if (renderer != null && renderer.isReady()) {
                renderer.drawFrameIndex(frame, x, y);
            }
        }

        @Override
        public boolean isHighPriority() {
            return false;
        }
    }
}
