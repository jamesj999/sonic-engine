package com.openggf.game.sonic3k.events;

import com.openggf.game.PlayerCharacter;
import com.openggf.game.RuntimeArtAdmissionLease;
import com.openggf.game.RuntimeArtAdmissionOwnerKind;
import com.openggf.game.mutation.LayoutMutationContext;
import com.openggf.game.mutation.LevelMutationSurface;
import com.openggf.game.mutation.MutationEffects;
import com.openggf.game.rewind.RewindTransient;
import com.openggf.game.save.SaveReason;
import com.openggf.game.save.SessionSaveRequests;
import com.openggf.game.sonic3k.S3kPaletteOwners;
import com.openggf.game.sonic3k.S3kPaletteWriteSupport;
import com.openggf.game.sonic3k.Sonic3kLevel;
import com.openggf.game.sonic3k.Sonic3kLevelEventManager;
import com.openggf.game.sonic3k.Sonic3kPlcLoader;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.constants.Sonic3kAnimationIds;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.objects.IczBigSnowPileInstance;
import com.openggf.game.sonic3k.objects.IczSnowboardIntroInstance;
import com.openggf.game.sonic3k.resources.S3kKosDecompressionQueue;
import com.openggf.game.sonic3k.resources.S3kKosModuleQueue;
import com.openggf.game.sonic3k.resources.S3kKosRamDestinations;
import com.openggf.game.timing.HardwareWorkHandle;
import com.openggf.game.timing.HardwareWorkKind;
import com.openggf.level.Level;
import com.openggf.level.LevelManager;
import com.openggf.level.Palette;
import com.openggf.level.Pattern;
import com.openggf.level.SeamlessLevelTransitionRequest;
import com.openggf.game.RuntimeArtAdmissionPolicy;
import com.openggf.level.SeamlessTransitionResourceHandoffId;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.physics.Direction;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.io.IOException;
import java.util.List;

/**
 * IceCap Zone dynamic level events.
 *
 * <p>ROM references:
 * <ul>
 *   <li>{@code sonic3k.asm:76984} {@code Obj_LevelIntroICZ1}</li>
 *   <li>{@code sonic3k.asm:110150} {@code ICZ1_BackgroundEvent}</li>
 *   <li>{@code sonic3k.asm:110433} {@code Obj_ICZ1BigSnowPile}</li>
 *   <li>{@code sonic3k.asm:39416} {@code ICZ1_Resize}</li>
 *   <li>{@code sonic3k.asm:39454} {@code ICZ2_Resize}</li>
 * </ul>
 *
 * <p>This pass enables the ICZ1 snowboard intro for the ROM Sonic player modes.
 * Tails-alone and Knuckles branches remain inactive until their ROM paths are ported.
 */
public class Sonic3kICZEvents extends Sonic3kZoneEvents {
    private static final int ICZ1_EVENTS_FG5_CAMERA_X_1 = 0x3700;
    private static final int ICZ1_EVENTS_FG5_CAMERA_Y_1 = 0x068C;
    private static final int ICZ1_EVENTS_FG5_CAMERA_X_2 = 0x3940;
    private static final int ICZ1_INDOOR_PALETTE_X = 0x3940;
    private static final int ICZ1_BG_INTRO = 0;
    private static final int ICZ1_BG_SNOW_FALL = 4;
    private static final int ICZ1_BG_REFRESH = 8;
    private static final int ICZ1_BG_REFRESH_2 = 12;
    private static final int ICZ1_BG_NORMAL = 16;
    private static final int ICZ1_BG_TRANSITION = 20;
    private static final int ICZ1_TRANSITION_CAMERA_X = 0x6900;
    private static final int ICZ2_LEVEL_LOAD_BLOCK_INDEX =
            Sonic3kZoneIds.ZONE_ICZ * 2 + 1;
    private static final int ICZ2_SECONDARY_CHUNK_DEST_BYTES = 0x0A00;
    private static final int ICZ2_SECONDARY_BLOCK_DEST_BYTES = 0x0408;
    private static final int ICZ2_SECONDARY_ART_DEST_TILE = 0x0122;
    private static final int ICZ1_TO_ICZ2_OFFSET_X = -0x6880;
    private static final int ICZ1_TO_ICZ2_OFFSET_Y = 0x0100;
    private static final int ICZ2_CAMERA_MIN_X = 0x0000;
    private static final int ICZ2_CAMERA_MAX_X = 0x7000;
    private static final int ICZ2_CAMERA_MIN_Y = 0x0000;
    private static final int ICZ2_CAMERA_MAX_Y = 0x0B20;
    private static final int ICZ1_BIG_SNOW_FINAL_OFFSET = -0x012E;
    private static final int ICZ1_BIG_SNOW_ACCELERATION = 0x2400;
    private static final int ICZ1_BIG_SNOW_RUMBLE_MASK = 0x000F;
    private static final int ICZ_SLIDE_EXIT_MOVE_LOCK = 5;
    private static final int ICZ_SLIDE_FRICTION = 4;
    private static final int ICZ_SLIDE_ANIMATION = 0x19;
    private static final int[] ICZ1_SLIDE_BLOCKS = {
            0x2E, 0xC6, 0x33, 0xC5, 0x24, 0x2A, 0x44, 0x1F, 0x27, 0x2B
    };
    private static final int[] ICZ1_SLIDE_SPEEDS = {
            -8, -8, 8, 8, -12, -12, -12, 12, 12, 12
    };
    private static final int ICZ2_INDOOR_X_MIN = 0x1000;
    private static final int ICZ2_INDOOR_X_MAX = 0x3600;
    private static final int ICZ2_INDOOR_Y = 0x0720;
    private static final int ICZ2_START_INDOOR_X_MAX = 0x3600;
    private static final int ICZ2_START_INDOOR_Y_HIGH = 0x0720;
    private static final int ICZ2_START_INDOOR_X_LOW = 0x1000;
    private static final int ICZ2_START_INDOOR_Y_LOW = 0x0580;
    private static final int ICZ2_INDOOR_HYSTERESIS_X_MIN = 0x1900;
    private static final int ICZ2_INDOOR_HYSTERESIS_X_MAX = 0x1B80;
    private static final int ICZ2_MIN_X_LOCK_CAMERA_X = 0x0740;
    private static final int ICZ2_MIN_X_LOCK_CAMERA_Y = 0x0400;
    private static final int LINE_4_PALETTE_INDEX = 3;
    private static final int LINE_4_BG_COLOR_START = 1;
    private static final int[] ICZ1_INDOOR_LINE4_COLORS_1_TO_11 = {
            0x0EC0, 0x0E40, 0x0E04, 0x0C00, 0x0600, 0x0200,
            0x0000, 0x0E64, 0x0E24, 0x0A02, 0x0402
    };
    private static final int[] ICZ2_OUTDOOR_LINE4_COLORS_1_TO_10 = {
            0x0EEE, 0x0EEA, 0x0EC8, 0x0EA4, 0x0C82,
            0x0C60, 0x0C40, 0x0E20, 0x0A00, 0x0E00
    };
    private static final int[] ICZ2_INDOOR_LINE4_COLORS_1_TO_11 = {
            0x0EE2, 0x0E24, 0x0E04, 0x0E02, 0x0402, 0x0200,
            0x0000, 0x0E20, 0x0E40, 0x0840, 0x0600
    };
    private static final int[] ICZ2_FROM_ICZ1_LINE4_COLORS_1_TO_10 = {
            0x0EEC, 0x0CC6, 0x0C80, 0x0C60, 0x0C40,
            0x0A40, 0x0820, 0x0620, 0x0200, 0x0600
    };

    /**
     * ROM: {@code ScreenShakeArray} (sonic3k.asm:104262) — signed byte Y offsets
     * indexed by the positive {@code Screen_shake_flag} countdown. Amplitude
     * tapers from ±5 down to ±1 as the timer runs out. Shared with AIZ/CNZ.
     */
    private static final int[] SCREEN_SHAKE_ARRAY = {
            1, -1, 1, -1, 2, -2, 2, -2, 3, -3, 3, -3, 4, -4, 4, -4, 5, -5, 5, -5
    };

    /**
     * ROM: {@code ScreenShakeArray2} (sonic3k.asm:104265) — 64-byte pseudo-random
     * offsets (0–3px) indexed by {@code Level_frame_counter & $3F}. Drives the
     * constant/negative {@code Screen_shake_flag} mode, used while the ICZ1 big
     * snow pile is dropping (ICZ1_BigSnowFall sets the flag with {@code st}).
     */
    private static final int[] SCREEN_SHAKE_ARRAY_CONSTANT = {
            1, 2, 1, 3, 1, 2, 2, 1, 2, 3, 1, 2, 1, 2, 0, 0,
            2, 0, 3, 2, 2, 3, 2, 2, 1, 3, 0, 0, 1, 0, 1, 3,
            1, 2, 1, 3, 1, 2, 2, 1, 2, 3, 1, 2, 1, 2, 0, 0,
            2, 0, 3, 2, 2, 3, 2, 2, 1, 3, 0, 0, 1, 0, 1, 3
    };

    /** ROM: the ICZ1_BigSnowFall settle shake set once the pile lands. */
    private static final int SNOW_PILE_LAND_SHAKE_FRAMES = 4;

    private boolean eventsFg5;
    private boolean introSpawned;
    private boolean indoorPaletteCyclingActive;
    private int backgroundRoutine;
    private int bigSnowOffset;
    private int bigSnowOffsetSubpixels;
    private int bigSnowVelocity;
    private boolean bigSnowPileSpawned;
    private boolean act2TransitionRequested;
    private boolean act2TransitionDirectPublished;
    private boolean act2TransitionArtPublished;
    @RewindTransient(reason = "queue facade is rebound to the restored session ledger by captured ordinals")
    private S3kKosDecompressionQueue act2TransitionDirectQueue;
    @RewindTransient(reason = "handle is rebound to the restored session ledger by captured ordinal")
    private HardwareWorkHandle act2TransitionChunkHandle;
    @RewindTransient(reason = "handle is rebound to the restored session ledger by captured ordinal")
    private HardwareWorkHandle act2TransitionBlockHandle;
    private long act2TransitionChunkOrdinal = -1;
    private long act2TransitionBlockOrdinal = -1;
    @RewindTransient(reason = "queue facade is rebound to the restored session ledger by captured ordinal")
    private S3kKosModuleQueue act2TransitionArtQueue;
    @RewindTransient(reason = "handle is rebound to the restored session ledger by captured ordinal")
    private HardwareWorkHandle act2TransitionArtHandle;
    private long act2TransitionArtOrdinal = -1;
    private long act2TransitionHandoffId = -1;
    private long act2TransitionAdmissionLeaseId = -1;
    private long act2TransitionAdmissionGeneration = -1;
    private long act2TransitionAdmissionBatchFingerprint;
    private boolean act2TransitionAdmissionAccepted;
    private boolean act2TransitionPublicationFailed;
    private int activeAct;
    private boolean postTitleAct2SizeChangeActive;
    private int act2MaxXAccumulator;
    private int act2MinYAccumulator;
    private int act2MaxYAccumulator;
    @RewindTransient(
            reason = "live snowboard intro object reference; object lifetime/state is captured by ObjectManager rewind")
    private IczSnowboardIntroInstance snowboardIntro;
    // ROM Screen_shake_flag: 0 = off, positive = timed countdown (ScreenShakeArray),
    // negative = constant jitter (ScreenShakeArray2). The snowboard crash writes
    // #$14 (sonic3k.asm:76896); the snow pile drop writes it via ICZ1_BigSnowFall.
    private int screenShakeFlag;
    private int screenShakeOffsetY;
    private int screenShakeAppliedOffsetY;

    @Override
    public void init(int act) {
        super.init(act);
        activeAct = act;
        eventsFg5 = false;
        introSpawned = false;
        snowboardIntro = null;
        backgroundRoutine = 0;
        bigSnowOffset = 0;
        bigSnowOffsetSubpixels = 0;
        bigSnowVelocity = 0;
        bigSnowPileSpawned = false;
        act2TransitionRequested = false;
        act2TransitionDirectPublished = false;
        act2TransitionArtPublished = false;
        act2TransitionDirectQueue = null;
        act2TransitionChunkHandle = null;
        act2TransitionBlockHandle = null;
        act2TransitionChunkOrdinal = -1;
        act2TransitionBlockOrdinal = -1;
        act2TransitionArtQueue = null;
        act2TransitionArtHandle = null;
        act2TransitionArtOrdinal = -1;
        act2TransitionHandoffId = -1;
        act2TransitionAdmissionLeaseId = -1;
        act2TransitionAdmissionGeneration = -1;
        act2TransitionAdmissionBatchFingerprint = 0;
        act2TransitionAdmissionAccepted = false;
        act2TransitionPublicationFailed = false;
        postTitleAct2SizeChangeActive = false;
        act2MaxXAccumulator = 0;
        act2MinYAccumulator = 0;
        act2MaxYAccumulator = 0;
        screenShakeFlag = 0;
        screenShakeOffsetY = 0;
        screenShakeAppliedOffsetY = 0;
        indoorPaletteCyclingActive = initialIndoorPaletteCycleState(act);
        applyInitialBackgroundPalette(act);
    }

    @Override
    public void update(int act, int frameCounter) {
        rebindHardwareWorkIfNeeded();
        if (act == 1) {
            publishTransferredIcz2Resources();
        }
        // ROM LevelLoop runs ShakeScreen_Setup after the sprites; the scroll
        // handler consumes the previously published sample this frame while the
        // countdown produces the next one (matching the AIZ/CNZ shake ordering).
        screenShakeAppliedOffsetY = screenShakeOffsetY;
        tickScreenShake(frameCounter);
        if (act == 0) {
            updateAct1Resize();
            updateAct1ScreenEvent();
            updateAct1BackgroundEvent(frameCounter);
        } else if (act == 1) {
            updateAct2Resize();
        }
        updateIndoorPaletteCycleGate(act);
    }

    /**
     * ROM: {@code move.w #frames,(Screen_shake_flag).w} — start a timed screen
     * shake. The snowboard-crash release (sonic3k.asm:76896) writes {@code #$14}.
     */
    public void triggerScreenShake(int frames) {
        screenShakeFlag = frames;
    }

    /**
     * Current vertical shake offset (ROM {@code Screen_shake_offset}). Read by
     * {@link com.openggf.game.sonic3k.runtime.IczZoneRuntimeState} so the ICZ
     * scroll handler folds it into the background and the shared
     * {@code ParallaxManager} -> {@code Camera} propagation shakes the foreground
     * tiles and sprites together.
     */
    public int getScreenShakeOffsetY() {
        return screenShakeAppliedOffsetY;
    }

    /**
     * ROM {@code ShakeScreen_Setup} (sonic3k.asm:104219): a zero flag produces no
     * offset, a negative flag is a constant jitter driven by {@code ScreenShakeArray2}
     * indexed by the frame counter, and a positive flag is a timed countdown that
     * tapers through {@code ScreenShakeArray}.
     */
    private void tickScreenShake(int frameCounter) {
        if (screenShakeFlag == 0) {
            screenShakeOffsetY = 0;
            return;
        }
        if (screenShakeFlag < 0) {
            screenShakeOffsetY = SCREEN_SHAKE_ARRAY_CONSTANT[frameCounter & 0x3F];
            return;
        }
        screenShakeFlag--;
        screenShakeOffsetY = screenShakeFlag < SCREEN_SHAKE_ARRAY.length
                ? SCREEN_SHAKE_ARRAY[screenShakeFlag]
                : 0;
    }

    public boolean isEventsFg5() {
        return eventsFg5;
    }

    public void setEventsFg5(boolean value) {
        eventsFg5 = value;
    }

    /**
     * ROM: Events_bg+$16. AnPal_ICZ uses this word to decide whether palette
     * line 4 colors 12-15 are allowed to cycle.
     */
    public boolean isIndoorPaletteCyclingActive() {
        return indoorPaletteCyclingActive;
    }

    public void setIndoorPaletteCyclingActive(boolean value) {
        indoorPaletteCyclingActive = value;
    }

    public int getIcz1BigSnowOffset() {
        return bigSnowOffset;
    }

    public int getIcz1BackgroundRoutine() {
        return backgroundRoutine;
    }

    public void forceAct1NormalBackgroundRoutineForTest() {
        backgroundRoutine = ICZ1_BG_NORMAL;
    }

    public boolean isAct2TransitionRequested() {
        return act2TransitionRequested;
    }

    /**
     * The shared title owner already models the first post-child Wait2 poll;
     * ICZ's retained EndSignControl path has two further polls before release.
     */
    public int preloadedActCameraReleaseAdditionalDispatches() {
        return 2;
    }

    /**
     * ROM {@code Change_Act2Sizes}: install the three independently allocated
     * gradual level-size workers retained by {@code Obj_EndSignControl}.
     */
    public void preparePostTitleAct2SizeChange() {
        if (activeAct != 1 || postTitleAct2SizeChangeActive) {
            return;
        }
        postTitleAct2SizeChangeActive = true;
        act2MaxXAccumulator = 0;
        act2MinYAccumulator = 0;
        act2MaxYAccumulator = 0;
        camera().setMaxYTarget((short) ICZ2_CAMERA_MAX_Y);
    }

    /** Runs the retained Child1_Act2LevelSize slots before the camera step. */
    public void updatePostTitleAct2SizeWorkers() {
        if (!postTitleAct2SizeChangeActive) {
            return;
        }

        boolean maxXDone = updateGradualMaxX();
        boolean minYDone = updateGradualMinY();
        boolean maxYDone = updateGradualMaxY();
        postTitleAct2SizeChangeActive = !(maxXDone && minYDone && maxYDone);
    }

    private boolean updateGradualMaxX() {
        int current = camera().getMaxX() & 0xFFFF;
        act2MaxXAccumulator += 0x4000;
        int next = current + (act2MaxXAccumulator >>> 16);
        if (next >= ICZ2_CAMERA_MAX_X) {
            camera().setMaxX((short) ICZ2_CAMERA_MAX_X);
            return true;
        }
        camera().setMaxX((short) next);
        return false;
    }

    private boolean updateGradualMinY() {
        int current = camera().getMinY() & 0xFFFF;
        act2MinYAccumulator += 0x4000;
        int next = current - (act2MinYAccumulator >>> 16);
        if (next <= ICZ2_CAMERA_MIN_Y) {
            camera().setMinY((short) ICZ2_CAMERA_MIN_Y);
            return true;
        }
        camera().setMinY((short) next);
        return false;
    }

    private boolean updateGradualMaxY() {
        int current = camera().getMaxY() & 0xFFFF;
        act2MaxYAccumulator += 0x8000;
        int next = current + (act2MaxYAccumulator >>> 16);
        if (next > ICZ2_CAMERA_MAX_Y) {
            camera().setMaxY((short) ICZ2_CAMERA_MAX_Y);
            return true;
        }
        camera().setMaxY((short) next);
        // Change_Act2Sizes writes Camera_target_max_Y_pos before allocating
        // this child; Camera#setMaxY also updates the target, so restore it.
        camera().setMaxYTarget((short) ICZ2_CAMERA_MAX_Y);
        return false;
    }

    public void spawnSonicSnowboardIntro() {
        if (introSpawned) {
            return;
        }
        introSpawned = true;
        ObjectSpawn spawn = new ObjectSpawn(
                IczSnowboardIntroInstance.INITIAL_SNOWBOARD_X,
                IczSnowboardIntroInstance.INITIAL_SNOWBOARD_Y,
                0, 0, 0, false,
                IczSnowboardIntroInstance.INITIAL_SNOWBOARD_Y);
        snowboardIntro = spawnObject(() -> new IczSnowboardIntroInstance(spawn));
    }

    public boolean shouldEnterIntroSidekickDormantMarker(AbstractPlayableSprite sidekick) {
        return sidekick != null && activeAct == 0 && hasSonicSnowboardIntroPlayerMode();
    }

    public void primeSnowboardIntroPostStartupForCompleteRun(AbstractPlayableSprite player) {
        if (snowboardIntro != null && !snowboardIntro.isDestroyed()) {
            snowboardIntro.primePostStartupWaitForBoardHandoff(player);
        }
    }

    public void restoreSnowboardIntroPostPreludeReset(AbstractPlayableSprite player) {
        introSpawned = false;
        snowboardIntro = null;
        spawnSonicSnowboardIntro();
    }

    /**
     * ROM: {@code sub_714E -> loc_71D2 -> sub_71E4}. ICZ1 slide terrain runs
     * as a level event after the current player slot has moved. It sets
     * {@code status_secondary} bit 7 for the next frame's {@code Sonic_Move},
     * which skips input/friction but leaves the just-finished friction frame
     * intact.
     */
    public void updateSlideTerrainAfterPlayablePhysics(int act, AbstractPlayableSprite player) {
        if (act != 0 || player == null || player.getDead() || player.isDebugMode()) {
            return;
        }
        LevelManager manager = levelManager();
        int blockId = manager != null ? manager.getBlockIdAt(player.getCentreX(), player.getCentreY()) : -1;
        applyIcz1SlideTerrainForBlock(player, blockId);
    }

    static void applyIcz1SlideTerrainForBlock(AbstractPlayableSprite player, int blockId) {
        if (player == null) {
            return;
        }
        if (player.getAir() || player.isOnObject()) {
            exitIczSlide(player);
            return;
        }
        int tableIndex = findIcz1SlideTableIndex(blockId);
        if (tableIndex < 0) {
            exitIczSlide(player);
            return;
        }

        int targetSpeed = ICZ1_SLIDE_SPEEDS[tableIndex];
        if (targetSpeed != 0) {
            applyDirectionalIczSlide(player, targetSpeed);
        } else {
            applyFrictionIczSlide(player);
        }
    }

    static int findIcz1SlideTableIndex(int blockId) {
        for (int i = 0; i < ICZ1_SLIDE_BLOCKS.length; i++) {
            if (ICZ1_SLIDE_BLOCKS[i] == (blockId & 0xFF)) {
                return i;
            }
        }
        return -1;
    }

    private static void applyDirectionalIczSlide(AbstractPlayableSprite player, int targetSpeedHighByte) {
        int inertia = player.getGSpeed();
        int inertiaHigh = (byte) (inertia >> 8);
        // ROM loc_723E moves ground_vel by $40 toward the signed high-byte
        // table target, then stores status_secondary bit 7 for next Sonic_Move.
        if (targetSpeedHighByte < 0) {
            if (inertiaHigh > targetSpeedHighByte) {
                inertia -= 0x40;
            }
        } else if (inertiaHigh < targetSpeedHighByte) {
            inertia += 0x40;
        }
        player.setGSpeed((short) inertia);
        player.setDirection(inertiaHigh < 0 ? Direction.LEFT : Direction.RIGHT);
        player.setAnimationId(ICZ_SLIDE_ANIMATION);
        player.setSliding(true);
    }

    private static void applyFrictionIczSlide(AbstractPlayableSprite player) {
        int inertia = player.getGSpeed();
        if ((player.getLogicalInputState() & AbstractPlayableSprite.INPUT_LEFT) != 0) {
            player.setAnimationId(Sonic3kAnimationIds.WALK.id());
            player.setDirection(Direction.LEFT);
            inertia -= ICZ_SLIDE_FRICTION;
            if (inertia < 0) {
                inertia -= ICZ_SLIDE_FRICTION;
            }
        }
        if ((player.getLogicalInputState() & AbstractPlayableSprite.INPUT_RIGHT) != 0) {
            player.setAnimationId(Sonic3kAnimationIds.WALK.id());
            player.setDirection(Direction.RIGHT);
            inertia += ICZ_SLIDE_FRICTION;
            if (inertia >= 0) {
                inertia += ICZ_SLIDE_FRICTION;
            }
        }
        if (inertia > 0) {
            inertia -= ICZ_SLIDE_FRICTION;
            if (inertia <= 0) {
                inertia = 0;
                player.setAnimationId(Sonic3kAnimationIds.WAIT.id());
            }
        } else if (inertia < 0) {
            inertia += ICZ_SLIDE_FRICTION;
            if (inertia >= 0) {
                inertia = 0;
                player.setAnimationId(Sonic3kAnimationIds.WAIT.id());
            }
        } else {
            player.setAnimationId(Sonic3kAnimationIds.WAIT.id());
        }
        player.setGSpeed((short) inertia);
        player.setSliding(true);
    }

    private static void exitIczSlide(AbstractPlayableSprite player) {
        if (player.isSliding()) {
            player.setMoveLockTimer(ICZ_SLIDE_EXIT_MOVE_LOCK);
            player.setSliding(false);
        }
    }

    public boolean hasSonicSnowboardIntroPlayerMode() {
        PlayerCharacter character = playerCharacter();
        return character == PlayerCharacter.SONIC_AND_TAILS
                || character == PlayerCharacter.SONIC_ALONE;
    }

    private void updateAct1Resize() {
        int cameraX = camera().getX();
        int cameraY = camera().getY();
        switch (eventRoutine) {
            case 0 -> {
                if (cameraX >= ICZ1_EVENTS_FG5_CAMERA_X_1
                        && cameraY >= ICZ1_EVENTS_FG5_CAMERA_Y_1) {
                    eventsFg5 = true;
                    eventRoutine = 2;
                }
            }
            case 2 -> {
                // In the ROM, the quake lock prevents Sonic from carrying the camera
                // into the indoor refresh trigger before Obj_ICZ1BigSnowPile has
                // finished and released him. The snowboard intro object can otherwise
                // cross $3940 during its object-control handoff in the engine.
                if (backgroundRoutine == ICZ1_BG_SNOW_FALL
                        && (bigSnowOffset > ICZ1_BIG_SNOW_FINAL_OFFSET || isFocusedPlayerControlLocked())) {
                    return;
                }
                if (cameraX >= ICZ1_EVENTS_FG5_CAMERA_X_2) {
                    eventsFg5 = true;
                    eventRoutine = 4;
                }
            }
            default -> {
                // ICZ1 routine 4 is a ROM rts terminal state.
            }
        }
    }

    private void updateAct1BackgroundEvent(int frameCounter) {
        switch (backgroundRoutine) {
            case ICZ1_BG_INTRO -> updateAct1BackgroundIntro(frameCounter);
            case ICZ1_BG_SNOW_FALL -> updateAct1BackgroundSnowFall(frameCounter);
            case ICZ1_BG_REFRESH -> updateAct1BackgroundRefresh();
            case ICZ1_BG_REFRESH_2 -> updateAct1BackgroundRefresh2();
            case ICZ1_BG_NORMAL -> updateAct1BackgroundNormal();
            case ICZ1_BG_TRANSITION -> updateIcz2TransitionQueue();
            default -> {
                // ICZ1 unknown background stages are terminal until ported.
            }
        }
    }

    private void updateAct1BackgroundIntro(int frameCounter) {
        if (!eventsFg5) {
            return;
        }
        eventsFg5 = false;
        if (playerCharacter() == PlayerCharacter.KNUCKLES) {
            backgroundRoutine = ICZ1_BG_SNOW_FALL;
            return;
        }

        spawnBigSnowPile();
        bigSnowOffset = 0;
        bigSnowOffsetSubpixels = 0;
        bigSnowVelocity = 0;
        updateBigSnowFall(frameCounter);
        lockFocusedPlayerForIntroQuake();
        backgroundRoutine = ICZ1_BG_SNOW_FALL;
    }

    private void updateAct1BackgroundSnowFall(int frameCounter) {
        if (eventsFg5) {
            eventsFg5 = false;
            backgroundRoutine = ICZ1_BG_REFRESH;
            gameState().setScreenShakeActive(false);
            return;
        }
        if (playerCharacter() != PlayerCharacter.KNUCKLES) {
            updateBigSnowFall(frameCounter);
        }
    }

    private void updateAct1BackgroundRefresh() {
        backgroundRoutine = ICZ1_BG_REFRESH_2;
    }

    private void updateAct1BackgroundRefresh2() {
        indoorPaletteCyclingActive = true;
        applyLine4BackgroundPalette(ICZ1_INDOOR_LINE4_COLORS_1_TO_11);
        backgroundRoutine = ICZ1_BG_NORMAL;
    }

    private void updateAct1BackgroundNormal() {
        if ((camera().getX() & 0xFFFF) < ICZ1_TRANSITION_CAMERA_X) {
            return;
        }
        queueIcz2TransitionResources();
        backgroundRoutine = ICZ1_BG_TRANSITION;
    }

    private void updateIcz2TransitionQueue() {
        if (act2TransitionHandoffId >= 0
                && ((IczSeamlessTransitionResourceHandoff)
                        seamlessTransitionResourceHandoffs()
                                .peek(new SeamlessTransitionResourceHandoffId(
                                        act2TransitionHandoffId)))
                        .directQueueEmpty()) {
            requestIcz2Transition();
        }
    }

    private void queueIcz2TransitionResources() {
        if (act2TransitionChunkHandle != null
                || act2TransitionBlockHandle != null
                || act2TransitionArtHandle != null) {
            return;
        }
        try {
            int entry = Sonic3kConstants.LEVEL_LOAD_BLOCK_ADDR
                    + ICZ2_LEVEL_LOAD_BLOCK_INDEX
                    * Sonic3kConstants.LEVEL_LOAD_BLOCK_ENTRY_SIZE;
            int artSource = rom().read32BitAddr(entry + 4) & 0x00FF_FFFF;
            int blockSource = rom().read32BitAddr(entry + 12) & 0x00FF_FFFF;
            int chunkSource = rom().read32BitAddr(entry + 20) & 0x00FF_FFFF;

            act2TransitionDirectQueue = directKosQueue();
            act2TransitionChunkHandle = act2TransitionDirectQueue.queueStandardKos(
                    rom(), chunkSource,
                    S3kKosRamDestinations.RAM_START
                            + ICZ2_SECONDARY_CHUNK_DEST_BYTES);
            act2TransitionChunkOrdinal = act2TransitionChunkHandle.ordinal();
            act2TransitionBlockHandle = act2TransitionDirectQueue.queueStandardKos(
                    rom(), blockSource,
                    S3kKosRamDestinations.blockTableOffset(
                            ICZ2_SECONDARY_BLOCK_DEST_BYTES));
            act2TransitionBlockOrdinal = act2TransitionBlockHandle.ordinal();

            act2TransitionArtQueue = moduleKosQueue();
            act2TransitionArtHandle =
                    act2TransitionArtQueue.queueForIczSeamlessHandoff(
                            rom(), artSource,
                            ICZ2_SECONDARY_ART_DEST_TILE);
            act2TransitionArtOrdinal = act2TransitionArtHandle.ordinal();
            act2TransitionHandoffId =
                    seamlessTransitionResourceHandoffs()
                            .register(
                                    new IczSeamlessTransitionResourceHandoff(
                                            act2TransitionDirectQueue,
                                            act2TransitionChunkHandle,
                                            act2TransitionBlockHandle,
                                            act2TransitionArtQueue,
                                            act2TransitionArtHandle,
                                            eventManager()))
                            .value();
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Unable to queue ICZ2 seamless-transition resources",
                    exception);
        }
    }

    private void requestIcz2Transition() {
        if (act2TransitionRequested) {
            return;
        }
        act2TransitionRequested = true;

        SeamlessLevelTransitionRequest request = SeamlessLevelTransitionRequest.builder(
                        SeamlessLevelTransitionRequest.TransitionType.RELOAD_TARGET_LEVEL)
                .targetZoneAct(Sonic3kZoneIds.ZONE_ICZ, 1)
                .runtimeArtAdmissionPolicy(
                        RuntimeArtAdmissionPolicy.RESOURCE_HANDOFF_OWNER)
                .deactivateLevelNow(false)
                .preserveMusic(true)
                .preserveLevelGamestate(true)
                .showInLevelTitleCard(false)
                // Obj_TitleCardWait2 has a three-dispatch tail after the last
                // child retires. The title manager owns the first drained-child
                // observation, so this request carries the two remaining polls.
                .inLevelTitleCardPreloadedActCameraReleaseDispatches(2)
                .preserveOffsetCameraPosition(true)
                .postTransitionMinX(ICZ2_CAMERA_MIN_X)
                .postTransitionMaxX(ICZ2_CAMERA_MAX_X)
                .postTransitionMinY(ICZ2_CAMERA_MIN_Y)
                .postTransitionMaxY(ICZ2_CAMERA_MAX_Y)
                .postTransitionMaxYTarget(ICZ2_CAMERA_MAX_Y)
                .playerOffset(ICZ1_TO_ICZ2_OFFSET_X, ICZ1_TO_ICZ2_OFFSET_Y)
                .cameraOffset(ICZ1_TO_ICZ2_OFFSET_X, ICZ1_TO_ICZ2_OFFSET_Y)
                .resourceHandoff(
                        new SeamlessTransitionResourceHandoffId(
                                act2TransitionHandoffId))
                .build();

        SessionSaveRequests.requestCurrentSessionSave(SaveReason.PROGRESSION_SAVE);
        if (levelManager().getCurrentLevel() == null) {
            levelManager().requestSeamlessTransition(request);
            return;
        }
        try {
            levelManager().executeActTransition(request);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to apply ICZ act transition", e);
        }
    }

    void acceptTransferredIcz2Resources(
            S3kKosDecompressionQueue directQueue,
            HardwareWorkHandle chunkHandle,
            HardwareWorkHandle blockHandle,
            S3kKosModuleQueue artQueue,
            HardwareWorkHandle artHandle,
            RuntimeArtAdmissionLease admissionLease) {
        if (activeAct != 1
                || act2TransitionAdmissionAccepted
                || act2TransitionPublicationFailed
                || act2TransitionChunkHandle != null
                || act2TransitionBlockHandle != null
                || act2TransitionArtHandle != null) {
            throw new IllegalStateException(
                    "ICZ2 resource owner cannot accept duplicate or out-of-act work");
        }
        if (admissionLease == null
                || admissionLease.ownerKind()
                        != RuntimeArtAdmissionOwnerKind.RESOURCE_HANDOFF_OWNER) {
            throw new IllegalStateException(
                    "ICZ2 resource owner requires an exact resource-owner lease");
        }
        RuntimeArtAdmissionLease rebound = module()
                .getObjectArtProvider()
                .rebindRuntimeArtAdmission(
                        admissionLease.id(),
                        RuntimeArtAdmissionOwnerKind.RESOURCE_HANDOFF_OWNER);
        if (!rebound.equals(admissionLease)) {
            throw new IllegalStateException(
                    "ICZ2 resource-owner lease generation or batch does not match");
        }
        act2TransitionDirectQueue = directQueue;
        act2TransitionChunkHandle = chunkHandle;
        act2TransitionBlockHandle = blockHandle;
        act2TransitionChunkOrdinal = chunkHandle.ordinal();
        act2TransitionBlockOrdinal = blockHandle.ordinal();
        act2TransitionArtQueue = artQueue;
        act2TransitionArtHandle = artHandle;
        act2TransitionArtOrdinal = artHandle.ordinal();
        act2TransitionAdmissionLeaseId = admissionLease.id();
        act2TransitionAdmissionGeneration = admissionLease.generation();
        act2TransitionAdmissionBatchFingerprint =
                admissionLease.batchFingerprint();
        act2TransitionAdmissionAccepted = true;
    }

    private void publishTransferredIcz2Resources() {
        if (act2TransitionPublicationFailed) {
            throw new IllegalStateException(
                    "ICZ2 resource publication failed terminally");
        }
        if (!act2TransitionDirectPublished
                && act2TransitionChunkHandle != null
                && act2TransitionBlockHandle != null
                && act2TransitionArtHandle != null) {
            requirePendingTransferredHardwareWork();
            if (!(act2TransitionArtQueue.isReady(act2TransitionArtHandle)
                && act2TransitionDirectQueue.isReady(act2TransitionChunkHandle)
                && act2TransitionDirectQueue.isReady(act2TransitionBlockHandle))) {
                return;
            }
            boolean publicationStarted = false;
            try {
                publicationStarted = true;
                byte[] chunks128x128 = claimIcz2ChunkPayload();
                byte[] blocks16x16 = claimIcz2BlockPayload();
                byte[] tiles8x8 = claimIcz2ArtPayload();
                applyIcz2PreparedTerrain(chunks128x128, blocks16x16);
                applyIcz2PreparedArt(tiles8x8);
                RuntimeArtAdmissionLease lease = restoredAdmissionLease();
                module().getObjectArtProvider()
                        .consumeRuntimeArtAdmission(
                                lease,
                                RuntimeArtAdmissionOwnerKind
                                        .RESOURCE_HANDOFF_OWNER);
            } catch (RuntimeException failure) {
                if (publicationStarted) {
                    act2TransitionPublicationFailed = true;
                }
                throw failure;
            }
            act2TransitionChunkHandle = null;
            act2TransitionBlockHandle = null;
            act2TransitionArtHandle = null;
            act2TransitionDirectQueue = null;
            act2TransitionArtQueue = null;
            act2TransitionDirectPublished = true;
            act2TransitionArtPublished = true;
        }
    }

    private void requirePendingTransferredHardwareWork() {
        var timing = hardwareTiming();
        if (timing.isPending(act2TransitionChunkHandle)
                && timing.isPending(act2TransitionBlockHandle)
                && timing.isPending(act2TransitionArtHandle)) {
            return;
        }
        act2TransitionPublicationFailed = true;
        throw new IllegalStateException(
                "ICZ2 transferred hardware work is missing or already claimed");
    }

    private RuntimeArtAdmissionLease restoredAdmissionLease() {
        if (!act2TransitionAdmissionAccepted
                || act2TransitionAdmissionLeaseId < 0
                || act2TransitionAdmissionGeneration < 0) {
            throw new IllegalStateException(
                    "ICZ2 resource-owner admission lease is missing");
        }
        return new RuntimeArtAdmissionLease(
                act2TransitionAdmissionLeaseId,
                act2TransitionAdmissionGeneration,
                act2TransitionAdmissionBatchFingerprint,
                RuntimeArtAdmissionOwnerKind.RESOURCE_HANDOFF_OWNER);
    }

    protected byte[] claimIcz2ChunkPayload() {
        return act2TransitionDirectQueue.claim(act2TransitionChunkHandle);
    }

    protected byte[] claimIcz2BlockPayload() {
        return act2TransitionDirectQueue.claim(act2TransitionBlockHandle);
    }

    protected byte[] claimIcz2ArtPayload() {
        return act2TransitionArtQueue.claim(act2TransitionArtHandle);
    }

    protected void applyIcz2PreparedTerrain(
            byte[] chunks128x128,
            byte[] blocks16x16) {
        LevelManager manager = levelManager();
        Level level = manager.getCurrentLevel();
        if (!(level instanceof Sonic3kLevel sonic3kLevel)) {
            throw new IllegalStateException(
                    "ICZ2 transition terrain requires a live Sonic3kLevel");
        }
        LayoutMutationContext context = new LayoutMutationContext(
                LevelMutationSurface.forLevel(level),
                manager::applyMutationEffects);
        zoneLayoutMutationPipeline().applyImmediately(mutationContext -> {
            sonic3kLevel.applyBlockOverlay(
                    chunks128x128, ICZ2_SECONDARY_CHUNK_DEST_BYTES, false);
            sonic3kLevel.applyChunkOverlay(
                    blocks16x16, ICZ2_SECONDARY_BLOCK_DEST_BYTES, false);
            return MutationEffects.redrawAllTilemaps();
        }, context);
    }

    protected void applyIcz2PreparedArt(byte[] tiles8x8) {
        if (tiles8x8.length % Pattern.PATTERN_SIZE_IN_ROM != 0) {
            throw new IllegalArgumentException(
                    "prepared ICZ2 module art must contain whole patterns");
        }
        LevelManager manager = levelManager();
        Level level = manager.getCurrentLevel();
        if (!(level instanceof Sonic3kLevel sonic3kLevel)) {
            throw new IllegalStateException(
                    "ICZ2 transition art requires a live Sonic3kLevel");
        }
        LayoutMutationContext context = new LayoutMutationContext(
                LevelMutationSurface.forLevel(level),
                manager::applyMutationEffects);
        zoneLayoutMutationPipeline().applyImmediately(mutationContext -> {
            sonic3kLevel.applyPatternOverlay(
                    tiles8x8,
                    ICZ2_SECONDARY_ART_DEST_TILE
                            * Pattern.PATTERN_SIZE_IN_ROM,
                    false);
            return MutationEffects.redrawAllTilemaps();
        }, context);
        Sonic3kPlcLoader.refreshAffectedRenderers(
                List.of(new Sonic3kPlcLoader.TileRange(
                        ICZ2_SECONDARY_ART_DEST_TILE,
                        tiles8x8.length / Pattern.PATTERN_SIZE_IN_ROM)),
                manager);
    }

    private void rebindHardwareWorkIfNeeded() {
        if (!act2TransitionDirectPublished
                && !act2TransitionPublicationFailed
                && (((act2TransitionChunkOrdinal >= 0
                        || act2TransitionBlockOrdinal >= 0)
                && act2TransitionDirectQueue == null)
                || (act2TransitionArtOrdinal >= 0
                        && act2TransitionArtQueue == null))) {
            rebindHardwareWorkAfterRewind();
        }
    }

    public void rebindHardwareWorkAfterRewind() {
        try {
            var timing = hardwareTiming();
            act2TransitionChunkHandle = restoredHardwareHandle(
                    timing, HardwareWorkKind.KOS_DECOMPRESSION_QUEUE,
                    act2TransitionChunkOrdinal, "ICZ2 secondary chunks");
            act2TransitionBlockHandle = restoredHardwareHandle(
                    timing, HardwareWorkKind.KOS_DECOMPRESSION_QUEUE,
                    act2TransitionBlockOrdinal, "ICZ2 secondary blocks");
            act2TransitionDirectQueue =
                    act2TransitionChunkHandle != null
                                    || act2TransitionBlockHandle != null
                            ? directKosQueue()
                            : null;
            act2TransitionArtHandle = restoredHardwareHandle(
                    timing, HardwareWorkKind.KOS_MODULE_QUEUE,
                    act2TransitionArtOrdinal, "ICZ2 secondary art");
            act2TransitionArtQueue = act2TransitionArtHandle != null
                    ? moduleKosQueue()
                    : null;
        } catch (RuntimeException failure) {
            act2TransitionPublicationFailed = true;
            throw failure;
        }
    }

    public void discardHardwareWorkFacadesAfterRewind() {
        act2TransitionDirectQueue = null;
        act2TransitionChunkHandle = null;
        act2TransitionBlockHandle = null;
        act2TransitionArtQueue = null;
        act2TransitionArtHandle = null;
    }

    private static HardwareWorkHandle restoredHardwareHandle(
            com.openggf.game.timing.HardwareTimingService timing,
            HardwareWorkKind kind,
            long ordinal,
            String owner) {
        if (ordinal < 0) {
            return null;
        }
        return timing.pendingHandle(kind, ordinal)
                .orElseThrow(() -> new IllegalStateException(
                        "restored " + owner + " owner cannot find "
                                + kind + " ordinal " + ordinal));
    }

    /**
     * ROM {@code ICZ1SE_Init} (sonic3k.asm:110095-110101): while the screen
     * event is still on its first routine, a live {@code Screen_shake_flag}
     * with {@code Ctrl_1_locked} clear means the quake came from the snowboard
     * wall crash, so controller 1 is locked and {@code Ctrl_1_logical} cleared
     * until {@code Obj_ICZ1BigSnowPile} releases it (sonic3k.asm:110468).
     *
     * <p>The predicate is ROM {@code Screen_shake_flag} — the same word
     * {@code loc_39BEE} writes {@code #$14} into (sonic3k.asm:77370) and
     * {@code ShakeScreen_Setup} counts down (sonic3k.asm:104193-104198) — not
     * the shared {@code GameStateManager} shaking flag, which models the
     * unrelated S2 {@code Screen_Shaking_Flag} and is never written here.
     */
    private void updateAct1ScreenEvent() {
        if (backgroundRoutine != ICZ1_BG_INTRO || screenShakeFlag == 0) {
            return;
        }
        lockFocusedPlayerForIntroQuake();
    }

    private void lockFocusedPlayerForIntroQuake() {
        AbstractPlayableSprite player = camera().getFocusedSprite();
        if (player == null || player.isControlLocked()) {
            return;
        }
        player.setControlLocked(true);
        player.clearLogicalInputState();
    }

    private boolean isFocusedPlayerControlLocked() {
        AbstractPlayableSprite player = camera().getFocusedSprite();
        return player != null && player.isControlLocked();
    }

    private void spawnBigSnowPile() {
        if (bigSnowPileSpawned) {
            return;
        }
        bigSnowPileSpawned = true;
        ObjectSpawn spawn = new ObjectSpawn(
                IczBigSnowPileInstance.X_POSITION,
                IczBigSnowPileInstance.BASE_Y,
                0, 0, 0, false,
                IczBigSnowPileInstance.BASE_Y);
        spawnObject(() -> new IczBigSnowPileInstance(spawn, this));
    }

    private void updateBigSnowFall(int frameCounter) {
        if (bigSnowOffset > ICZ1_BIG_SNOW_FINAL_OFFSET) {
            gameState().setScreenShakeActive(true);
            // ROM ICZ1_BigSnowFall: st (Screen_shake_flag) — constant jitter
            // while the pile is still dropping onto Sonic.
            screenShakeFlag = -1;
            bigSnowVelocity += ICZ1_BIG_SNOW_ACCELERATION;
            bigSnowOffsetSubpixels -= bigSnowVelocity;
            bigSnowOffset = bigSnowOffsetSubpixels >> 16;
            if (((frameCounter - 1) & ICZ1_BIG_SNOW_RUMBLE_MASK) == 0) {
                audio().playSfx(Sonic3kSfx.RUMBLE_2.id);
            }
        }

        if (bigSnowOffset <= ICZ1_BIG_SNOW_FINAL_OFFSET) {
            gameState().setScreenShakeActive(true);
            // ROM: once landed, convert the still-constant shake into a short
            // timed settle (tst/bpl skip; move.w #4). A positive flag already set
            // by the wall-crash release is left to finish its own countdown.
            if (screenShakeFlag < 0) {
                screenShakeFlag = SNOW_PILE_LAND_SHAKE_FRAMES;
            }
            bigSnowOffset = ICZ1_BIG_SNOW_FINAL_OFFSET;
            bigSnowOffsetSubpixels = ICZ1_BIG_SNOW_FINAL_OFFSET << 16;
        }
    }

    private void updateAct2Resize() {
        if (eventRoutine != 0) {
            return;
        }
        if (camera().getX() >= ICZ2_MIN_X_LOCK_CAMERA_X
                && camera().getY() < ICZ2_MIN_X_LOCK_CAMERA_Y) {
            camera().setMinX((short) ICZ2_MIN_X_LOCK_CAMERA_X);
            eventRoutine = 2;
        }
    }

    private boolean initialIndoorPaletteCycleState(int act) {
        try {
            int x = camera().getX() & 0xFFFF;
            int y = camera().getY() & 0xFFFF;
            if (act == 0) {
                return x >= ICZ1_INDOOR_PALETTE_X;
            }
            if (act == 1) {
                return shouldAct2StartIndoors(x, y);
            }
        } catch (IllegalStateException ignored) {
            // Some tests construct event state before a gameplay camera exists.
        }
        return false;
    }

    private void updateIndoorPaletteCycleGate(int act) {
        int x = camera().getX() & 0xFFFF;
        int y = camera().getY() & 0xFFFF;
        if (act == 0) {
            if (!indoorPaletteCyclingActive && x >= ICZ1_INDOOR_PALETTE_X) {
                indoorPaletteCyclingActive = true;
                applyIcz1IndoorPalette();
            }
            return;
        }
        if (act == 1) {
            boolean nextState = isAct2IndoorPaletteCycleActive(x, y);
            if (nextState != indoorPaletteCyclingActive) {
                indoorPaletteCyclingActive = nextState;
                if (nextState) {
                    applyIcz2IndoorPalette();
                } else {
                    applyIcz2OutdoorPalette(x);
                }
            }
        }
    }

    private void applyInitialBackgroundPalette(int act) {
        try {
            int x = camera().getX() & 0xFFFF;
            int y = camera().getY() & 0xFFFF;
            if (act == 0) {
                if (indoorPaletteCyclingActive) {
                    applyIcz1IndoorPalette();
                }
                return;
            }
            if (act == 1) {
                if (shouldAct2StartIndoors(x, y)) {
                    applyIcz2IndoorPalette();
                } else {
                    applyIcz2OutdoorPalette(x);
                }
            }
        } catch (IllegalStateException ignored) {
            // Some tests construct event state before a gameplay camera exists.
        }
    }

    private void applyIcz1IndoorPalette() {
        applyLine4BackgroundPalette(ICZ1_INDOOR_LINE4_COLORS_1_TO_11);
    }

    private void applyIcz2IndoorPalette() {
        applyLine4BackgroundPalette(ICZ2_INDOOR_LINE4_COLORS_1_TO_11);
    }

    private void applyIcz2OutdoorPalette(int cameraX) {
        if (cameraX < 0x0720) {
            applyLine4BackgroundPalette(ICZ2_FROM_ICZ1_LINE4_COLORS_1_TO_10);
        } else {
            applyLine4BackgroundPalette(ICZ2_OUTDOOR_LINE4_COLORS_1_TO_10);
        }
    }

    private void applyLine4BackgroundPalette(int[] segaWords) {
        Level level = levelManager().getCurrentLevel();
        if (level == null || segaWords == null) {
            return;
        }
        Palette palette = level.getPalette(LINE_4_PALETTE_INDEX);
        if (palette == null) {
            return;
        }
        byte[] patch = new byte[segaWords.length * 2];
        for (int i = 0; i < segaWords.length; i++) {
            int offset = i * 2;
            patch[offset] = (byte) ((segaWords[i] >>> 8) & 0xFF);
            patch[offset + 1] = (byte) (segaWords[i] & 0xFF);
        }
        S3kPaletteWriteSupport.applyContiguousPatch(
                paletteRegistryOrNull(),
                level,
                graphics(),
                S3kPaletteOwners.ZONE_EVENT_PALETTE_LOAD,
                S3kPaletteOwners.PRIORITY_ZONE_EVENT,
                LINE_4_PALETTE_INDEX,
                LINE_4_BG_COLOR_START,
                patch);
        S3kPaletteWriteSupport.resolvePendingWritesNow(paletteRegistryOrNull(), level, graphics());
    }

    private boolean shouldAct2StartIndoors(int x, int y) {
        if (x >= ICZ2_START_INDOOR_X_MAX) {
            return false;
        }
        if (y >= ICZ2_START_INDOOR_Y_HIGH) {
            return true;
        }
        return x >= ICZ2_START_INDOOR_X_LOW && y >= ICZ2_START_INDOOR_Y_LOW;
    }

    private boolean isAct2IndoorPaletteCycleActive(int x, int y) {
        if (!indoorPaletteCyclingActive) {
            return x >= ICZ2_INDOOR_X_MIN && x < ICZ2_INDOOR_X_MAX && y >= ICZ2_INDOOR_Y;
        }
        if (x >= ICZ2_INDOOR_HYSTERESIS_X_MIN && x < ICZ2_INDOOR_HYSTERESIS_X_MAX) {
            return true;
        }
        return !(x < ICZ2_INDOOR_X_MIN || x >= ICZ2_INDOOR_X_MAX || y < ICZ2_INDOOR_Y);
    }
}
