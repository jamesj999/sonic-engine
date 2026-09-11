package com.openggf.game.sonic3k.events;

import com.openggf.camera.Camera;
import com.openggf.game.mutation.LevelMutationSurface;
import com.openggf.game.mutation.LayoutMutationContext;
import com.openggf.game.mutation.MutationEffects;
import com.openggf.game.sonic3k.S3kPaletteOwners;
import com.openggf.game.sonic3k.S3kPaletteWriteSupport;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.objects.MhzMinibossInstance;
import com.openggf.game.sonic3k.objects.bosses.MhzEndBossArenaHelperInstance;
import com.openggf.game.sonic3k.objects.MhzShipPropellerInstance;
import com.openggf.game.sonic3k.objects.MhzShipSequenceControllerInstance;
import com.openggf.game.save.SaveReason;
import com.openggf.game.save.SessionSaveRequests;
import com.openggf.level.Chunk;
import com.openggf.level.Level;
import com.openggf.level.LevelConstants;
import com.openggf.level.LevelManager;
import com.openggf.level.Pattern;
import com.openggf.level.SeamlessLevelTransitionRequest;
import com.openggf.game.RuntimeArtAdmissionPolicy;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.resources.LoadOp;
import com.openggf.level.resources.ResourceLoader;
import com.openggf.sprites.NativePositionOps;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.io.IOException;
import java.util.Arrays;
import java.util.BitSet;

/**
 * MHZ (Mushroom Hill Zone) dynamic level events.
 *
 * <p>This starts with the route-critical MHZ1 screen event path:
 * dynamic left boundary, lower-route max-Y clamp, and miniboss arena arming.
 */
public class Sonic3kMHZEvents extends Sonic3kZoneEvents {
    public enum SeasonPaletteMode {
        GREEN,
        AUTUMN,
        GOLD
    }

    private static final int ACT1_MINIBOSS_CAMERA_X = 0x4298;
    private static final int ACT1_MINIBOSS_CAMERA_Y = 0x0710;
    private static final int ACT1_LOWER_END_X = 0x4100;
    private static final int ACT1_MIN_X_KNUCKLES_ROUTE = 0x0680;
    private static final int ACT1_MIN_X_UPPER_ROUTE = 0x00C0;
    private static final int ACT1_MIN_X_LOWER_ROUTE = 0x0000;
    private static final int ACT1_MIN_X_PLAYER_Y_THRESHOLD = 0x0580;
    private static final int ACT1_MAX_Y_NORMAL = 0x0AA0;
    private static final int ACT1_MAX_Y_LOWER_END = 0x0710;
    private static final int ACT1_MINIBOSS_SPECIAL_EVENTS_ROUTINE = 0x08;
    private static final int ACT1_MINIBOSS_REPEAT_THRESHOLD_X = 0x4400;
    private static final int ACT1_MINIBOSS_REPEAT_OFFSET_X = 0x0200;
    private static final int ACT1_TO_ACT2_TRANSITION_OFFSET_X = -0x4200;
    private static final int ACT2_INITIAL_ROUTINE = 0x04;
    private static final int ACT2_INIT_AUTUMN_X = 0x09C0;
    private static final int ACT2_INIT_GOLD_X = 0x2940;
    private static final int ACT2_INIT_GREEN_Y = 0x0600;
    private static final int ACT2_FINAL_GATE_CAMERA_X = 0x3C90;
    private static final int ACT2_FINAL_GATE_MIN_Y = 0x0280;
    private static final int ACT2_EARLY_CAMERA_X = 0x0380;
    private static final int ACT2_LATE_CAMERA_X = 0x3600;
    private static final int ACT2_MIN_Y_EARLY = 0x0620;
    private static final int ACT2_MIN_Y_MIDDLE = 0x0000;
    private static final int ACT2_MIN_Y_LATE = 0x01A8;
    private static final int ACT2_MIN_X_UPPER_ROUTE = 0x0380;
    private static final int ACT2_MIN_X_LOWER_ROUTE = 0x0098;
    private static final int ACT2_MIN_X_PLAYER_Y_THRESHOLD = 0x05C0;
    private static final int ACT2_MAX_Y_NORMAL = 0x09A0;
    private static final int ACT2_MAX_Y_FINAL_UPPER = 0x0280;
    private static final int ACT2_FINAL_UPPER_PLAYER_X = 0x3A97;
    private static final int ACT2_FINAL_BYPASS_PLAYER_X = 0x3AC0;
    private static final int ACT2_FINAL_BYPASS_PLAYER_Y = 0x0300;
    private static final int ACT2_SHIP_START_ROUTINE = 0x0C;
    private static final int ACT2_SHIP_ACTIVE_ROUTINE = 0x10;
    private static final int ACT2_SHIP_REDRAW_POSITION = 0x0320;
    private static final int ACT2_SHIP_REDRAW_ROWCOUNT = 0x000A;
    private static final int ACT2_SHIP_H_INT_COUNTER = 0x0080;
    private static final int ACT2_SHIP_PALETTE_LINE = 1;
    private static final int ACT2_SHIP_CONTROLLER_INITIAL_SWING_SPEED = 0x04C0;
    private static final int ACT2_SHIP_CONTROLLER_INITIAL_MOTION = 0x4000;
    private static final int ACT2_SHIP_SECONDARY_BG_X_FIXED = 0x00400000;
    private static final int ACT2_SHIP_SECONDARY_BG_Y = 0x0080;
    private static final int ACT2_SHIP_SCROLL_LOCK_BG_X_HIGH_WORD = -0x03E6;
    private static final int ACT2_SHIP_PRIMARY_HSCROLL_BIAS = 0x01E0;
    private static final int ACT2_SHIP_PROPELLER_ONE_BASE_X = 0x46B8;
    private static final int ACT2_SHIP_PROPELLER_TWO_BASE_X = 0x45B8;
    private static final int ACT2_SHIP_PROPELLER_X_BIAS = 0x005C;
    private static final int ACT2_SHIP_PROPELLER_Y_BASE = 0x0158;
    private static final int ACT2_BG_INITIAL_REDRAW_ROUTINE = 0x04;
    private static final int ACT2_BG_CUSTOM_LAYOUT_ROUTINE = 0x08;
    private static final int ACT2_BG_CUSTOM_LAYOUT_AIR_WAIT_MIN_Y = 0x0420;
    private static final int ACT2_BG_CUSTOM_LAYOUT_AIR_WAIT_MAX_Y = 0x0500;
    private static final int ACT2_BG_CUSTOM_LAYOUT_REDRAW_ROUTINE = 0x0C;
    private static final int ACT2_BG_CUSTOM_LAYOUT_REDRAW_ROWCOUNT = 0x000F;
    private static final int ACT2_BG_END_BOSS_ARENA_ROUTINE = 0x10;
    private static final int ACT2_BG_END_BOSS_CAMERA_X = 0x3F00;
    private static final int ACT2_BG_END_BOSS_INIT_PLAYER_X = 0x3700;
    private static final int ACT2_BG_END_BOSS_INIT_PLAYER_Y = 0x0500;
    private static final int ACT2_BG_END_BOSS_DRAW_POSITION = 0x01A0;
    private static final int ACT2_BG_END_BOSS_DRAW_ROWCOUNT = 0x0002;
    private static final int ACT2_BG_END_BOSS_OBJECT_DRAW_ROUTINE = 0x14;
    private static final int ACT2_BG_END_BOSS_OBJECT_DRAW_POSITION = 0x0080;
    private static final int ACT2_BG_END_BOSS_OBJECT_DRAW_ROWCOUNT = 0x0002;
    private static final int ACT2_BG_END_BOSS_POST_OBJECT_ROUTINE = 0x18;
    private static final int ACT2_BG_END_BOSS_RESTORE_DRAW_ROUTINE = 0x1C;
    private static final int ACT2_BG_END_BOSS_RESTORE_DRAW_POSITION = 0x0180;
    private static final int ACT2_BG_END_BOSS_RESTORE_DRAW_ROWCOUNT = 0x0002;
    private static final int ACT2_BG_END_BOSS_FINAL_DRAW_ROUTINE = 0x20;
    private static final int ACT2_BG_END_BOSS_FINAL_DRAW_POSITION = 0x0280;
    private static final int ACT2_BG_END_BOSS_FINAL_DRAW_ROWCOUNT = 0x0002;
    private static final int ACT2_BG_END_BOSS_SPECIAL_EVENTS_ROUTINE = 0x0C;
    private static final int ACT2_END_BOSS_REPEAT_THRESHOLD_X = 0x4280;
    private static final int ACT2_END_BOSS_REPEAT_OFFSET_X = 0x0200;
    private static final int ACT2_END_BOSS_ESCAPE_THRESHOLD_X = 0x4420;
    private static final int ACT2_END_BOSS_ESCAPE_MAX_X = 0x45A0;
    private static final int ACT2_END_BOSS_PLAYER_MIN_OFFSET_X = 0x0018;
    private static final int ACT2_END_BOSS_PLAYER_MAX_OFFSET_X = 0x00C0;
    private static final int ACT2_END_BOSS_FORCED_GROUND_SPEED = 0x0400;
    private static final int ACT2_END_BOSS_KNUCKLES_FORCED_GROUND_SPEED = 0x0500;
    private static final int ACT2_END_BOSS_WAIT_ANIMATION = 0x05;
    private static final int[] ACT2_BG_END_BOSS_SPIKE_TIERS = {2, 2, 1, 1, 0, 0};
    private static final boolean[] ACT2_BG_END_BOSS_SPIKE_ALTERNATE_SIDES = {
            false, true, false, true, false, true
    };
    private static final int[] ACT2_END_BOSS_SPIKE_Y_TABLE = {
            0x0336, 0x0334, 0x0333, 0,
            0x0320, 0x0320, 0x0320, 0,
            0x02F6, 0x02F8, 0x02FA, 0,
            0x02C9, 0x02CF, 0x02D4, 0,
            0x02C9, 0x02CF, 0x02D4, 0
    };
    private static final int[] ACT2_END_BOSS_SCROLL_DATA_NORMAL = {
            0x01, 0x02, 0x00, 0x02, 0x01, 0x02, 0x01, 0x00,
            0x02, 0x00, 0x01, 0x02, 0x01, 0x02, 0x02, 0x00
    };
    private static final int[] ACT2_END_BOSS_SCROLL_DATA_KNUCKLES = {
            0x01, 0x02, 0x00, 0x03, 0x01, 0x04, 0x02, 0x00,
            0x04, 0x01, 0x03, 0x04, 0x02, 0x00, 0x04, 0x02
    };
    private static final byte[] SCREEN_SHAKE_ARRAY_TIMED = {
            1, -1, 1, -1, 2, -2, 2, -2, 3, -3, 3, -3, 4, -4, 4, -4,
            5, -5, 5, -5
    };
    private static final byte[] SCREEN_SHAKE_ARRAY_CONTINUOUS = {
            1, 2, 1, 3, 1, 2, 2, 1, 2, 3, 1, 2, 1, 2, 0, 0,
            2, 0, 3, 2, 2, 3, 2, 2, 1, 3, 0, 0, 1, 0, 1, 3,
            1, 2, 1, 3, 1, 2, 2, 1, 2, 3, 1, 2, 1, 2, 0, 0,
            2, 0, 3, 2, 2, 3, 2, 2, 1, 3, 0, 0, 1, 0, 1, 3
    };
    private static final int SEASON_PALETTE_START_LINE = 2;
    private static final int PALETTE_LINE_SIZE = 32;
    private static final int[][] ACT2_SEASON_TRIGGERS = {
            {0x0420, 0x04A0, 0x0640, 0x06C0, 0x0680},
            {0x0980, 0x0A00, 0x07C0, 0x0800, 0x09C0},
            {0x2900, 0x2980, 0x0280, 0x0300, 0x2940},
            {0x2B00, 0x2B80, 0x0540, 0x0580, 0x2B40},
            {0x2800, 0x2980, 0x07C0, 0x0840, 0x0800}
    };

    private boolean bossFlag;
    private boolean actTransitionFlag;
    private int specialEventsRoutine;
    private boolean seasonFlag;
    private boolean autumnTriggerFlag;
    private boolean shipTransitionFlag;
    private int shipRedrawPosition;
    private int shipRedrawRowCount;
    private boolean shipHIntActive;
    private int shipHIntCounter;
    private int shipSecondaryBgCameraXFixed;
    private int shipEffectiveBgY;
    private boolean shipScrollLockSet;
    private boolean shipControllerSignalFlag;
    private int endBossWalkoffPrepEventFlag;
    private int screenShakeFlag;
    private int screenShakeOffset;
    private int screenShakeLastOffset;
    private int shipHScrollCameraCopy;
    private int shipPrimaryHScroll;
    private int shipPlayerCarryBgY;
    private int shipPropellerOneX;
    private int shipPropellerTwoX;
    private int shipPropellerY;
    private int act2BackgroundRoutine;
    private boolean endBossCustomLayoutQueued;
    private boolean endBossArenaBackgroundActive;
    private int endBossArenaDrawPosition;
    private int endBossArenaDrawRowCount;
    private int endBossArenaScrollDataByte;
    private int endBossArenaScrollDataIndex;
    private boolean endBossPillarArtQueued;
    private boolean endBossArenaForegroundRefreshActive;
    private boolean endBossArenaHScrollCleared;
    private int endBossArenaPillarControllerCount;
    private int endBossArenaTallSupportCount;
    private int[] endBossArenaSpikeTiers = new int[0];
    private boolean[] endBossArenaSpikeAlternateSides = new boolean[0];
    private boolean[] endBossArenaSpikeActive = new boolean[0];
    private int[] endBossArenaSpikeY = new int[0];
    private boolean endBossArenaSpikeDeletionFlag;
    private boolean endBossArenaRestoreRequested;
    private boolean leafBlowerCutsceneFlag;
    private int levelRepeatOffset;
    private SeasonPaletteMode seasonPaletteMode = SeasonPaletteMode.GREEN;

    @Override
    public void init(int act) {
        super.init(act);
        bossFlag = false;
        actTransitionFlag = false;
        specialEventsRoutine = 0;
        seasonFlag = false;
        autumnTriggerFlag = false;
        shipTransitionFlag = false;
        shipRedrawPosition = 0;
        shipRedrawRowCount = 0;
        shipHIntActive = false;
        shipHIntCounter = 0;
        shipSecondaryBgCameraXFixed = 0;
        shipEffectiveBgY = 0;
        shipScrollLockSet = false;
        shipControllerSignalFlag = false;
        endBossWalkoffPrepEventFlag = 0;
        screenShakeFlag = 0;
        screenShakeOffset = 0;
        screenShakeLastOffset = 0;
        shipHScrollCameraCopy = 0;
        shipPrimaryHScroll = 0;
        shipPlayerCarryBgY = 0;
        shipPropellerOneX = 0;
        shipPropellerTwoX = 0;
        shipPropellerY = 0;
        act2BackgroundRoutine = 0;
        endBossCustomLayoutQueued = false;
        endBossArenaBackgroundActive = false;
        endBossArenaDrawPosition = 0;
        endBossArenaDrawRowCount = 0;
        endBossArenaScrollDataByte = 0;
        endBossArenaScrollDataIndex = 0;
        endBossPillarArtQueued = false;
        endBossArenaForegroundRefreshActive = false;
        endBossArenaHScrollCleared = false;
        endBossArenaPillarControllerCount = 0;
        endBossArenaTallSupportCount = 0;
        endBossArenaSpikeTiers = new int[0];
        endBossArenaSpikeAlternateSides = new boolean[0];
        endBossArenaSpikeActive = new boolean[0];
        endBossArenaSpikeY = new int[0];
        endBossArenaSpikeDeletionFlag = false;
        endBossArenaRestoreRequested = false;
        leafBlowerCutsceneFlag = false;
        levelRepeatOffset = 0;
        seasonPaletteMode = SeasonPaletteMode.GREEN;
        if (act == 1) {
            initAct2SeasonState();
        }
    }

    @Override
    public void update(int act, int frameCounter) {
        levelRepeatOffset = 0;
        if (act == 0) {
            updateAct1ScreenEvent();
            updateAct1BackgroundEvent();
            updateAct1SpecialEvent();
        } else if (act == 1) {
            updateAct2ScreenEvent();
            updateAct2BackgroundEvent(frameCounter);
            updateAct2SpecialEvent();
        }
    }

    private void updateAct1ScreenEvent() {
        if (bossFlag || actTransitionFlag) {
            return;
        }

        updateAct1DynamicMinX();
        updateAct1MaxY();
        armAct1MinibossArenaIfReached();
    }

    private void updateAct1DynamicMinX() {
        Camera camera = camera();
        AbstractPlayableSprite player = focusedPlayer();
        int playerY = player == null ? 0 : player.getCentreY() & 0xFFFF;
        int minX = playerCharacter() == com.openggf.game.PlayerCharacter.KNUCKLES
                ? ACT1_MIN_X_KNUCKLES_ROUTE
                : playerY >= ACT1_MIN_X_PLAYER_Y_THRESHOLD
                        ? ACT1_MIN_X_LOWER_ROUTE
                        : ACT1_MIN_X_UPPER_ROUTE;
        camera.setMinX((short) minX);
        camera.setMinXTarget((short) minX);
    }

    private void updateAct1MaxY() {
        AbstractPlayableSprite player = focusedPlayer();
        int playerX = player == null ? 0 : player.getCentreX() & 0xFFFF;
        int maxY = playerX >= ACT1_LOWER_END_X ? ACT1_MAX_Y_LOWER_END : ACT1_MAX_Y_NORMAL;
        camera().setMaxY((short) maxY);
        camera().setMaxYTarget((short) maxY);
    }

    private void armAct1MinibossArenaIfReached() {
        Camera camera = camera();
        if ((camera.getY() & 0xFFFF) < ACT1_MINIBOSS_CAMERA_Y
                || (camera.getX() & 0xFFFF) < ACT1_MINIBOSS_CAMERA_X) {
            return;
        }

        camera.setMinY((short) ACT1_MINIBOSS_CAMERA_Y);
        camera.setMinYTarget((short) ACT1_MINIBOSS_CAMERA_Y);
        specialEventsRoutine = ACT1_MINIBOSS_SPECIAL_EVENTS_ROUTINE;
        bossFlag = true;
        spawnMhzMiniboss();
    }

    private void spawnMhzMiniboss() {
        LevelManager levelManager = levelManager();
        if (levelManager == null || levelManager.getObjectManager() == null) {
            return;
        }
        levelManager.getObjectManager().addDynamicObject(new MhzMinibossInstance(new ObjectSpawn(
                0, 0, Sonic3kObjectIds.MHZ_MINIBOSS, 0, 0, false, 0)));
    }

    private void updateAct1SpecialEvent() {
        if (specialEventsRoutine == ACT1_MINIBOSS_SPECIAL_EVENTS_ROUTINE) {
            updateAct1MinibossRepeatSpecialEvent();
        }
    }

    private void updateAct1MinibossRepeatSpecialEvent() {
        Camera camera = camera();
        int cameraX = camera.getX() & 0xFFFF;
        if (cameraX >= ACT1_MINIBOSS_REPEAT_THRESHOLD_X) {
            cameraX = (cameraX - ACT1_MINIBOSS_REPEAT_OFFSET_X) & 0xFFFF;
            levelRepeatOffset = ACT1_MINIBOSS_REPEAT_OFFSET_X;
            shiftLevelRepeatPlayers(-ACT1_MINIBOSS_REPEAT_OFFSET_X);
            LevelManager levelManager = levelManager();
            if (levelManager != null && levelManager.getObjectManager() != null) {
                levelManager.getObjectManager().applyLevelRepeatOffsetToActiveObjects(
                        -ACT1_MINIBOSS_REPEAT_OFFSET_X, 0);
            }
            camera.setX((short) cameraX);
            camera.setMaxX((short) ACT1_MINIBOSS_CAMERA_X);
        }
        camera.setMinX((short) cameraX);
    }

    private void updateAct1BackgroundEvent() {
        if (!actTransitionFlag) {
            return;
        }

        actTransitionFlag = false;
        bossFlag = false;
        specialEventsRoutine = 0;
        applyPlc(0x28);
        SessionSaveRequests.requestCurrentSessionSave(SaveReason.PROGRESSION_SAVE);
        levelManager().requestSeamlessTransition(
                SeamlessLevelTransitionRequest.builder(
                                SeamlessLevelTransitionRequest.TransitionType.RELOAD_TARGET_LEVEL)
                        .targetZoneAct(Sonic3kZoneIds.ZONE_MHZ, 1)
                        .runtimeArtAdmissionPolicy(RuntimeArtAdmissionPolicy.TITLE_OWNER)
                        .deactivateLevelNow(false)
                        .preserveMusic(true)
                        .showInLevelTitleCard(true)
                        .playerOffset(ACT1_TO_ACT2_TRANSITION_OFFSET_X, 0)
                        .cameraOffset(ACT1_TO_ACT2_TRANSITION_OFFSET_X, 0)
                        .build());
    }

    private void initAct2SeasonState() {
        eventRoutine = ACT2_INITIAL_ROUTINE;
        AbstractPlayableSprite player = focusedPlayer();
        int playerX = player == null ? 0 : player.getCentreX() & 0xFFFF;
        int playerY = player == null ? 0 : player.getCentreY() & 0xFFFF;
        if (playerX >= ACT2_BG_END_BOSS_INIT_PLAYER_X && playerY < ACT2_BG_END_BOSS_INIT_PLAYER_Y) {
            act2BackgroundRoutine = ACT2_BG_CUSTOM_LAYOUT_ROUTINE;
        }

        if (playerX >= ACT2_INIT_GOLD_X) {
            applySeasonState(SeasonPaletteMode.GOLD);
            return;
        }

        if (playerX < ACT2_INIT_AUTUMN_X && playerY >= ACT2_INIT_GREEN_Y) {
            applySeasonState(SeasonPaletteMode.GREEN);
            return;
        }

        applySeasonState(SeasonPaletteMode.AUTUMN);
    }

    private void updateAct2ScreenEvent() {
        if (eventRoutine == ACT2_INITIAL_ROUTINE) {
            if (shipTransitionFlag) {
                startAct2ShipSequence();
                return;
            }
            updateAct2Routine4CameraBounds();
            updateAct2SeasonTriggers();
        } else if (eventRoutine == ACT2_SHIP_START_ROUTINE) {
            completeAct2ShipSetup();
        } else if (eventRoutine == ACT2_SHIP_ACTIVE_ROUTINE) {
            updateAct2ShipActiveScrollState();
        }
    }

    private void updateAct2BackgroundEvent(int frameCounter) {
        if (act2BackgroundRoutine == 0) {
            updateAct2InitialBackgroundEvent();
        } else if (act2BackgroundRoutine == ACT2_BG_INITIAL_REDRAW_ROUTINE) {
            updateAct2InitialBackgroundRedrawEvent();
        } else if (act2BackgroundRoutine == ACT2_BG_CUSTOM_LAYOUT_ROUTINE) {
            updateAct2EndBossCustomLayoutEvent();
        } else if (act2BackgroundRoutine == ACT2_BG_CUSTOM_LAYOUT_REDRAW_ROUTINE) {
            updateAct2EndBossCustomLayoutRedrawEvent();
        } else if (act2BackgroundRoutine == ACT2_BG_END_BOSS_ARENA_ROUTINE) {
            updateAct2EndBossArenaBackgroundEvent();
        } else if (act2BackgroundRoutine == ACT2_BG_END_BOSS_OBJECT_DRAW_ROUTINE) {
            updateAct2EndBossArenaObjectDrawEvent();
        } else if (act2BackgroundRoutine == ACT2_BG_END_BOSS_POST_OBJECT_ROUTINE) {
            updateAct2EndBossArenaRestoreSignalEvent();
        } else if (act2BackgroundRoutine == ACT2_BG_END_BOSS_RESTORE_DRAW_ROUTINE) {
            updateAct2EndBossArenaRestoreDrawEvent();
        } else if (act2BackgroundRoutine == ACT2_BG_END_BOSS_FINAL_DRAW_ROUTINE) {
            updateAct2EndBossArenaFinalDrawEvent();
        }
        updateScreenShakeSetup(frameCounter);
    }

    private void updateAct2InitialBackgroundEvent() {
        AbstractPlayableSprite player = focusedPlayer();
        if (player == null
                || (player.getCentreX() & 0xFFFF) < ACT2_BG_END_BOSS_INIT_PLAYER_X
                || (player.getCentreY() & 0xFFFF) >= ACT2_BG_END_BOSS_INIT_PLAYER_Y) {
            return;
        }

        endBossArenaDrawPosition = computeAct2BackgroundDrawPosition();
        endBossArenaDrawRowCount = ACT2_BG_CUSTOM_LAYOUT_REDRAW_ROWCOUNT;
        act2BackgroundRoutine = ACT2_BG_INITIAL_REDRAW_ROUTINE;
    }

    private void updateAct2InitialBackgroundRedrawEvent() {
        if (--endBossArenaDrawRowCount < 0) {
            act2BackgroundRoutine = ACT2_BG_CUSTOM_LAYOUT_ROUTINE;
        }
    }

    private void updateScreenShakeSetup(int frameCounter) {
        screenShakeLastOffset = screenShakeOffset;
        if (screenShakeFlag == 0) {
            screenShakeOffset = 0;
            return;
        }
        if (screenShakeFlag < 0) {
            screenShakeOffset = SCREEN_SHAKE_ARRAY_CONTINUOUS[frameCounter & 0x3F];
            return;
        }

        screenShakeFlag--;
        screenShakeOffset = screenShakeFlag < SCREEN_SHAKE_ARRAY_TIMED.length
                ? SCREEN_SHAKE_ARRAY_TIMED[screenShakeFlag]
                : 0;
    }

    private void updateAct2EndBossCustomLayoutEvent() {
        if (shouldWaitBeforeEndBossCustomLayout()) {
            return;
        }
        if (shouldRedrawBeforeEndBossCustomLayout()) {
            endBossArenaDrawPosition = computeAct2BackgroundDrawPosition();
            endBossArenaDrawRowCount = ACT2_BG_CUSTOM_LAYOUT_REDRAW_ROWCOUNT;
            act2BackgroundRoutine = ACT2_BG_CUSTOM_LAYOUT_REDRAW_ROUTINE;
            return;
        }
        endBossCustomLayoutQueued = true;
        queueEndBossCustomLayoutMutation();
        act2BackgroundRoutine = ACT2_BG_END_BOSS_ARENA_ROUTINE;
        updateAct2EndBossArenaBackgroundEvent();
    }

    private void queueEndBossCustomLayoutMutation() {
        if (zoneLayoutMutationPipelineOrNull() == null) {
            return;
        }

        byte[] customLayout;
        byte[] customBlocks16x16;
        byte[] customChunks128x128;
        byte[] customArt;
        try {
            customLayout = rom().readBytes(Sonic3kConstants.MHZ_CUSTOM_LAYOUT_ADDR,
                    Sonic3kConstants.LEVEL_LAYOUT_TOTAL_SIZE);
            ResourceLoader loader = new ResourceLoader(rom());
            customBlocks16x16 = loader.loadSingle(
                    LoadOp.kosinskiBase(Sonic3kConstants.MHZ_CUSTOM_BLOCKS_16X16_KOS_ADDR));
            customChunks128x128 = loader.loadSingle(
                    LoadOp.kosinskiBase(Sonic3kConstants.MHZ_CUSTOM_CHUNKS_128X128_KOS_ADDR));
            customArt = loader.loadSingle(
                    LoadOp.kosinskiMBase(Sonic3kConstants.MHZ_CUSTOM_ART_KOSM_ADDR));
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to read MHZ end-boss custom resources from S3K ROM", ex);
        }

        zoneLayoutMutationPipeline().queue(context -> {
            MutationEffects effects = applyCustomEndBossResourceLoads(
                    context, customBlocks16x16, customChunks128x128, customArt);
            int fgCols = readWord(customLayout, 0);
            int bgCols = readWord(customLayout, 2);
            int fgRows = readWord(customLayout, 4);
            int bgRows = readWord(customLayout, 6);
            copyCustomLayoutLayer(context.surface(), customLayout, 0, 8, fgCols, fgRows);
            copyCustomLayoutLayer(context.surface(), customLayout, 1, 10, bgCols, bgRows);
            return mergeEffects(effects, MutationEffects.redrawAllTilemaps());
        });
    }

    private MutationEffects applyCustomEndBossResourceLoads(LayoutMutationContext context,
                                                            byte[] customBlocks16x16,
                                                            byte[] customChunks128x128,
                                                            byte[] customArt) {
        MutationEffects effects = MutationEffects.NONE;
        effects = mergeEffects(effects, copyCustom16x16Blocks(context, customBlocks16x16));
        effects = mergeEffects(effects, copyCustom128x128Chunks(context, customChunks128x128));
        return mergeEffects(effects, copyCustomArt(context, customArt));
    }

    private MutationEffects copyCustom16x16Blocks(LayoutMutationContext context, byte[] customBlocks16x16) {
        Level level = levelManager() != null ? levelManager().getCurrentLevel() : null;
        if (level == null) {
            return MutationEffects.NONE;
        }
        LevelMutationSurface surface = context.surface();
        MutationEffects effects = MutationEffects.NONE;
        int startChunkIndex = Sonic3kConstants.MHZ_CUSTOM_BLOCK_TABLE_DEST_OFFSET / Chunk.CHUNK_SIZE_IN_ROM;
        int chunkCount = customBlocks16x16.length / Chunk.CHUNK_SIZE_IN_ROM;
        for (int i = 0; i < chunkCount; i++) {
            int chunkIndex = startChunkIndex + i;
            int srcOffset = i * Chunk.CHUNK_SIZE_IN_ROM;
            int[] state = new int[Chunk.PATTERNS_PER_CHUNK + 2];
            for (int pattern = 0; pattern < Chunk.PATTERNS_PER_CHUNK; pattern++) {
                state[pattern] = readWord(customBlocks16x16, srcOffset + pattern * 2);
            }
            if (chunkIndex < level.getChunkCount()) {
                Chunk existing = level.getChunk(chunkIndex);
                state[Chunk.PATTERNS_PER_CHUNK] = existing.getSolidTileIndex();
                state[Chunk.PATTERNS_PER_CHUNK + 1] = existing.getSolidTileAltIndex();
            }
            effects = mergeEffects(effects, surface.restoreChunkState(chunkIndex, state));
        }
        return effects;
    }

    private MutationEffects copyCustom128x128Chunks(LayoutMutationContext context, byte[] customChunks128x128) {
        LevelMutationSurface surface = context.surface();
        MutationEffects effects = MutationEffects.NONE;
        int startBlockIndex = Sonic3kConstants.MHZ_CUSTOM_CHUNK_TABLE_DEST_OFFSET
                / LevelConstants.BLOCK_SIZE_IN_ROM;
        int blockCount = customChunks128x128.length / LevelConstants.BLOCK_SIZE_IN_ROM;
        int chunksPerBlock = LevelConstants.BLOCK_SIZE_IN_ROM / 2;
        for (int i = 0; i < blockCount; i++) {
            int blockIndex = startBlockIndex + i;
            int srcOffset = i * LevelConstants.BLOCK_SIZE_IN_ROM;
            int[] state = new int[chunksPerBlock];
            for (int chunk = 0; chunk < chunksPerBlock; chunk++) {
                state[chunk] = readWord(customChunks128x128, srcOffset + chunk * 2);
            }
            effects = mergeEffects(effects, surface.restoreBlockState(blockIndex, state));
        }
        return effects;
    }

    private MutationEffects copyCustomArt(LayoutMutationContext context, byte[] customArt) {
        return copyArtToPatternTile(context, customArt, Sonic3kConstants.MHZ_CUSTOM_ART_TILE);
    }

    private MutationEffects copyArtToPatternTile(LayoutMutationContext context, byte[] art, int startPatternIndex) {
        LevelMutationSurface surface = context.surface();
        MutationEffects effects = MutationEffects.NONE;
        int patternCount = art.length / Pattern.PATTERN_SIZE_IN_ROM;
        for (int i = 0; i < patternCount; i++) {
            int patternIndex = startPatternIndex + i;
            int srcOffset = i * Pattern.PATTERN_SIZE_IN_ROM;
            Pattern pattern = new Pattern();
            pattern.fromSegaFormat(Arrays.copyOfRange(art, srcOffset,
                    srcOffset + Pattern.PATTERN_SIZE_IN_ROM));
            effects = mergeEffects(effects, surface.setPattern(patternIndex, pattern));
        }
        return effects;
    }

    private void copyCustomLayoutLayer(LevelMutationSurface surface, byte[] layoutData, int layer, int rowPtrOffset,
                                       int cols, int rows) {
        LevelManager manager = levelManager();
        if (manager == null || manager.getCurrentLevel() == null || manager.getCurrentLevel().getMap() == null) {
            return;
        }
        com.openggf.level.Map map = manager.getCurrentLevel().getMap();
        int maxRows = Math.min(rows, map.getHeight());
        int maxCols = Math.min(cols, map.getWidth());
        for (int row = 0; row < maxRows; row++) {
            int ptrPos = rowPtrOffset + row * 4;
            if (ptrPos + 1 >= layoutData.length) {
                break;
            }
            int rowDataAddr = decodeLayoutRowOffset(readWord(layoutData, ptrPos));
            if (rowDataAddr < 0 || rowDataAddr >= layoutData.length) {
                continue;
            }
            for (int col = 0; col < maxCols; col++) {
                int srcIdx = rowDataAddr + col;
                if (srcIdx >= layoutData.length) {
                    break;
                }
                surface.setBlockInMapWithoutRedraw(layer, col, row, layoutData[srcIdx] & 0xFF);
            }
        }
    }

    private static int decodeLayoutRowOffset(int rowPointerWord) {
        int pointer = rowPointerWord & Sonic3kConstants.LEVEL_LAYOUT_ROW_POINTER_MASK;
        if (pointer == 0) {
            return -1;
        }
        if (pointer >= Sonic3kConstants.LEVEL_LAYOUT_RAM_BASE) {
            return pointer - Sonic3kConstants.LEVEL_LAYOUT_RAM_BASE;
        }
        return pointer;
    }

    private static int readWord(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }

    private static MutationEffects mergeEffects(MutationEffects first, MutationEffects second) {
        if (first == null || first.isEmpty()) {
            return second == null ? MutationEffects.NONE : second;
        }
        if (second == null || second.isEmpty()) {
            return first;
        }
        BitSet dirtyPatterns = first.dirtyPatterns();
        dirtyPatterns.or(second.dirtyPatterns());
        return new MutationEffects(dirtyPatterns,
                first.dirtyRegionProcessingRequired() || second.dirtyRegionProcessingRequired(),
                first.foregroundRedrawRequired() || second.foregroundRedrawRequired(),
                first.allTilemapsRedrawRequired() || second.allTilemapsRedrawRequired(),
                first.patternLookupRefreshRequired() || second.patternLookupRefreshRequired(),
                first.objectResyncRequired() || second.objectResyncRequired(),
                first.ringResyncRequired() || second.ringResyncRequired());
    }

    private void updateAct2EndBossCustomLayoutRedrawEvent() {
        endBossArenaDrawRowCount--;
        if (endBossArenaDrawRowCount >= 0) {
            return;
        }
        act2BackgroundRoutine = 0;
    }

    private boolean shouldRedrawBeforeEndBossCustomLayout() {
        AbstractPlayableSprite player = focusedPlayer();
        return player != null && (player.getCentreY() & 0xFFFF) >= ACT2_BG_CUSTOM_LAYOUT_AIR_WAIT_MAX_Y;
    }

    private boolean shouldWaitBeforeEndBossCustomLayout() {
        AbstractPlayableSprite player = focusedPlayer();
        if (player == null) {
            return false;
        }
        int playerY = player.getCentreY() & 0xFFFF;
        return playerY > ACT2_BG_CUSTOM_LAYOUT_AIR_WAIT_MIN_Y
                && playerY < ACT2_BG_CUSTOM_LAYOUT_AIR_WAIT_MAX_Y
                && player.getAir();
    }

    private int computeAct2BackgroundDrawPosition() {
        int cameraY = camera().getY() & 0xFFFF;
        return (cameraY + 0x00E0) & 0x0FFF;
    }

    private void updateAct2EndBossArenaBackgroundEvent() {
        if (endBossArenaBackgroundActive) {
            advanceAct2EndBossArenaBottomUpDraw();
            return;
        }
        if ((camera().getX() & 0xFFFF) < ACT2_BG_END_BOSS_CAMERA_X) {
            return;
        }

        eventRoutine += 4;
        endBossArenaDrawPosition = ACT2_BG_END_BOSS_DRAW_POSITION;
        endBossArenaDrawRowCount = ACT2_BG_END_BOSS_DRAW_ROWCOUNT;
        endBossArenaBackgroundActive = true;
        endBossArenaScrollDataIndex = 0;
        endBossArenaScrollDataByte = currentEndBossScrollData()[endBossArenaScrollDataIndex];
        camera().setFrozen(true);
        specialEventsRoutine = ACT2_BG_END_BOSS_SPECIAL_EVENTS_ROUTINE;
        endBossPillarArtQueued = true;
        queueEndBossPillarArtMutation();
    }

    private void queueEndBossPillarArtMutation() {
        if (zoneLayoutMutationPipelineOrNull() == null) {
            return;
        }

        byte[] pillarArt;
        try {
            ResourceLoader loader = new ResourceLoader(rom());
            pillarArt = loader.loadSingle(
                    LoadOp.kosinskiMBase(Sonic3kConstants.ART_KOSM_MHZ_END_BOSS_PILLAR_ADDR));
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to read MHZ end-boss pillar art from S3K ROM", ex);
        }

        zoneLayoutMutationPipeline().queue(context ->
                copyArtToPatternTile(context, pillarArt, Sonic3kConstants.ART_TILE_MHZ_END_BOSS_PILLAR));
    }

    private void advanceAct2EndBossArenaBottomUpDraw() {
        endBossArenaDrawRowCount--;
        if (endBossArenaDrawRowCount >= 0) {
            return;
        }
        endBossArenaDrawPosition = ACT2_BG_END_BOSS_OBJECT_DRAW_POSITION;
        endBossArenaDrawRowCount = ACT2_BG_END_BOSS_OBJECT_DRAW_ROWCOUNT;
        endBossArenaForegroundRefreshActive = true;
        act2BackgroundRoutine = ACT2_BG_END_BOSS_OBJECT_DRAW_ROUTINE;
    }

    private void updateAct2EndBossArenaObjectDrawEvent() {
        endBossArenaDrawRowCount--;
        if (endBossArenaDrawRowCount >= 0) {
            return;
        }

        endBossArenaHScrollCleared = true;
        endBossArenaPillarControllerCount = 1;
        endBossArenaTallSupportCount = 1;
        endBossArenaSpikeTiers = ACT2_BG_END_BOSS_SPIKE_TIERS.clone();
        endBossArenaSpikeAlternateSides = ACT2_BG_END_BOSS_SPIKE_ALTERNATE_SIDES.clone();
        endBossArenaSpikeDeletionFlag = false;
        updateEndBossArenaSpikeState();
        spawnEndBossArenaHelpers();
        act2BackgroundRoutine = ACT2_BG_END_BOSS_POST_OBJECT_ROUTINE;
    }

    private void spawnEndBossArenaHelpers() {
        spawnObject(() -> MhzEndBossArenaHelperInstance.pillar(this));
        spawnObject(() -> MhzEndBossArenaHelperInstance.tallSupport(this));
        for (int i = 0; i < endBossArenaSpikeTiers.length; i++) {
            int spikeIndex = i;
            spawnObject(() -> MhzEndBossArenaHelperInstance.spike(
                    this,
                    spikeIndex,
                    endBossArenaSpikeTiers[spikeIndex],
                    endBossArenaSpikeAlternateSides[spikeIndex]));
        }
    }

    private void updateAct2EndBossArenaRestoreSignalEvent() {
        if (!endBossArenaRestoreRequested) {
            return;
        }

        endBossArenaDrawPosition = ACT2_BG_END_BOSS_RESTORE_DRAW_POSITION;
        endBossArenaDrawRowCount = ACT2_BG_END_BOSS_RESTORE_DRAW_ROWCOUNT;
        act2BackgroundRoutine = ACT2_BG_END_BOSS_RESTORE_DRAW_ROUTINE;
    }

    private void updateAct2EndBossArenaRestoreDrawEvent() {
        endBossArenaDrawRowCount--;
        if (endBossArenaDrawRowCount >= 0) {
            return;
        }

        endBossArenaForegroundRefreshActive = false;
        endBossArenaDrawPosition = ACT2_BG_END_BOSS_FINAL_DRAW_POSITION;
        endBossArenaDrawRowCount = ACT2_BG_END_BOSS_FINAL_DRAW_ROWCOUNT;
        act2BackgroundRoutine = ACT2_BG_END_BOSS_FINAL_DRAW_ROUTINE;
    }

    private void updateAct2EndBossArenaFinalDrawEvent() {
        endBossArenaDrawRowCount--;
        if (endBossArenaDrawRowCount >= 0) {
            return;
        }

        endBossArenaBackgroundActive = false;
        eventRoutine = 0;
        act2BackgroundRoutine += 4;
    }

    private void updateAct2SpecialEvent() {
        if (specialEventsRoutine == ACT2_BG_END_BOSS_SPECIAL_EVENTS_ROUTINE) {
            updateAct2EndBossRepeatSpecialEvent();
        }
    }

    private void updateAct2EndBossRepeatSpecialEvent() {
        Camera camera = camera();
        int nextCameraX = ((camera.getX() & 0xFFFF) + 4) & 0xFFFF;
        if (playerCharacter() == com.openggf.game.PlayerCharacter.KNUCKLES) {
            nextCameraX = (nextCameraX + 1) & 0xFFFF;
        }

        if (nextCameraX >= ACT2_END_BOSS_REPEAT_THRESHOLD_X) {
            if (endBossArenaRestoreRequested && nextCameraX >= ACT2_END_BOSS_ESCAPE_THRESHOLD_X) {
                nextCameraX = ACT2_END_BOSS_ESCAPE_THRESHOLD_X;
                camera.setMaxX((short) ACT2_END_BOSS_ESCAPE_MAX_X);
                camera.setFrozen(true);
                specialEventsRoutine = 0;
                applyEndBossCameraX(nextCameraX, false);
                clampEndBossArenaPlayers(nextCameraX);
                return;
            }
            if (!endBossArenaRestoreRequested) {
                nextCameraX = (nextCameraX - ACT2_END_BOSS_REPEAT_OFFSET_X) & 0xFFFF;
                levelRepeatOffset = ACT2_END_BOSS_REPEAT_OFFSET_X;
                shiftLevelRepeatPlayers(-ACT2_END_BOSS_REPEAT_OFFSET_X);
                LevelManager levelManager = levelManager();
                if (levelManager != null && levelManager.getObjectManager() != null) {
                    levelManager.getObjectManager().applyLevelRepeatOffsetToActiveObjects(
                            -ACT2_END_BOSS_REPEAT_OFFSET_X, 0);
                }
                advanceEndBossScrollDataByte();
                transferShipSignalToArenaSpikeDeletion();
            }
        }

        applyEndBossCameraX(nextCameraX, true);
        clampEndBossArenaPlayers(nextCameraX);
    }

    private void advanceEndBossScrollDataByte() {
        endBossArenaScrollDataIndex = (endBossArenaScrollDataIndex + 1) & 0x0F;
        endBossArenaScrollDataByte = currentEndBossScrollData()[endBossArenaScrollDataIndex];
        updateEndBossArenaSpikeState();
    }

    private void transferShipSignalToArenaSpikeDeletion() {
        if (!shipControllerSignalFlag) {
            return;
        }
        endBossArenaSpikeDeletionFlag = true;
        shipControllerSignalFlag = false;
        updateEndBossArenaSpikeState();
    }

    private void updateEndBossArenaSpikeState() {
        endBossArenaSpikeActive = new boolean[endBossArenaSpikeTiers.length];
        endBossArenaSpikeY = new int[endBossArenaSpikeTiers.length];
        for (int i = 0; i < endBossArenaSpikeY.length; i++) {
            endBossArenaSpikeY[i] = -1;
        }
        if (endBossArenaSpikeDeletionFlag || !endBossArenaForegroundRefreshActive) {
            return;
        }

        for (int i = 0; i < endBossArenaSpikeTiers.length; i++) {
            int scrollData = endBossArenaScrollDataByte;
            if (endBossArenaSpikeAlternateSides[i]) {
                if (scrollData != 4) {
                    continue;
                }
                scrollData = 0;
            }
            int tableIndex = scrollData * 4 + endBossArenaSpikeTiers[i];
            if (tableIndex < 0 || tableIndex >= ACT2_END_BOSS_SPIKE_Y_TABLE.length) {
                continue;
            }
            endBossArenaSpikeActive[i] = true;
            endBossArenaSpikeY[i] = ACT2_END_BOSS_SPIKE_Y_TABLE[tableIndex];
        }
    }

    private int[] currentEndBossScrollData() {
        return playerCharacter() == com.openggf.game.PlayerCharacter.KNUCKLES
                ? ACT2_END_BOSS_SCROLL_DATA_KNUCKLES
                : ACT2_END_BOSS_SCROLL_DATA_NORMAL;
    }

    private void applyEndBossCameraX(int cameraX, boolean mirrorMaxX) {
        Camera camera = camera();
        if (mirrorMaxX) {
            camera.setMaxX((short) cameraX);
        }
        camera.setX((short) cameraX);
        camera.setMinX((short) cameraX);
    }

    private void shiftLevelRepeatPlayers(int deltaX) {
        AbstractPlayableSprite player = focusedPlayer();
        if (player != null) {
            NativePositionOps.addXPosPreserveSubpixel(player, deltaX);
        }
        for (AbstractPlayableSprite sidekick : spriteManager().getSidekicks()) {
            NativePositionOps.addXPosPreserveSubpixel(sidekick, deltaX);
        }
    }

    private void clampEndBossArenaPlayers(int cameraX) {
        clampEndBossArenaPlayer(focusedPlayer(), cameraX);
        for (AbstractPlayableSprite sidekick : spriteManager().getSidekicks()) {
            clampEndBossArenaPlayer(sidekick, cameraX);
        }
    }

    private void clampEndBossArenaPlayer(AbstractPlayableSprite player, int cameraX) {
        if (player == null) {
            return;
        }
        if (player.getAnimationId() == ACT2_END_BOSS_WAIT_ANIMATION) {
            player.setAnimationId(0);
        }
        int minX = (cameraX + ACT2_END_BOSS_PLAYER_MIN_OFFSET_X) & 0xFFFF;
        int maxX = (cameraX + ACT2_END_BOSS_PLAYER_MAX_OFFSET_X) & 0xFFFF;
        int playerX = player.getCentreX() & 0xFFFF;
        if (playerX < minX) {
            NativePositionOps.writeXPosPreserveSubpixel(player, minX);
            player.setGSpeed((short) endBossForcedGroundSpeed());
        } else if (playerX >= maxX) {
            NativePositionOps.writeXPosPreserveSubpixel(player, maxX);
        }
    }

    private int endBossForcedGroundSpeed() {
        return playerCharacter() == com.openggf.game.PlayerCharacter.KNUCKLES
                ? ACT2_END_BOSS_KNUCKLES_FORCED_GROUND_SPEED
                : ACT2_END_BOSS_FORCED_GROUND_SPEED;
    }

    private void updateAct2Routine4CameraBounds() {
        Camera camera = camera();
        int cameraX = camera.getX() & 0xFFFF;
        int cameraY = camera.getY() & 0xFFFF;
        if (cameraX >= ACT2_FINAL_GATE_CAMERA_X) {
            if (cameraX == ACT2_FINAL_GATE_CAMERA_X) {
                setMinX(ACT2_FINAL_GATE_CAMERA_X);
                if (cameraY >= ACT2_FINAL_GATE_MIN_Y) {
                    setMinY(ACT2_FINAL_GATE_MIN_Y);
                }
            }
            return;
        }

        setMinY(computeAct2Routine4MinY(cameraX));
        updateAct2Routine4MinX(cameraX);
        updateAct2Routine4MaxY();
    }

    private int computeAct2Routine4MinY(int cameraX) {
        if (cameraX < ACT2_EARLY_CAMERA_X) {
            return ACT2_MIN_Y_EARLY;
        }
        if (cameraX < ACT2_LATE_CAMERA_X) {
            return ACT2_MIN_Y_MIDDLE;
        }
        return ACT2_MIN_Y_LATE;
    }

    private void updateAct2Routine4MinX(int cameraX) {
        if (cameraX >= ACT2_LATE_CAMERA_X) {
            return;
        }
        AbstractPlayableSprite player = focusedPlayer();
        int playerY = player == null ? 0 : player.getCentreY() & 0xFFFF;
        int minX = playerY >= ACT2_MIN_X_PLAYER_Y_THRESHOLD
                ? ACT2_MIN_X_LOWER_ROUTE
                : ACT2_MIN_X_UPPER_ROUTE;
        setMinX(minX);
    }

    private void updateAct2Routine4MaxY() {
        AbstractPlayableSprite player = focusedPlayer();
        int playerX = player == null ? 0 : player.getCentreX() & 0xFFFF;
        int playerY = player == null ? 0 : player.getCentreY() & 0xFFFF;
        int maxY = ACT2_MAX_Y_NORMAL;
        if (playerX >= ACT2_FINAL_UPPER_PLAYER_X) {
            maxY = ACT2_MAX_Y_FINAL_UPPER;
            if (playerX < ACT2_FINAL_BYPASS_PLAYER_X && playerY >= ACT2_FINAL_BYPASS_PLAYER_Y) {
                maxY = ACT2_MAX_Y_NORMAL;
            }
        }
        Camera camera = camera();
        if ((camera.getMaxY() & 0xFFFF) != maxY) {
            camera.setMaxY((short) maxY);
            camera.setMaxYTarget((short) maxY);
        }
    }

    private void setMinX(int minX) {
        Camera camera = camera();
        if ((camera.getMinX() & 0xFFFF) != minX) {
            camera.setMinX((short) minX);
            camera.setMinXTarget((short) minX);
        }
    }

    private void setMinY(int minY) {
        Camera camera = camera();
        if ((camera.getMinY() & 0xFFFF) != minY) {
            camera.setMinY((short) minY);
            camera.setMinYTarget((short) minY);
        }
    }

    private void updateAct2SeasonTriggers() {
        AbstractPlayableSprite player = focusedPlayer();
        if (player == null) {
            return;
        }
        int playerX = player.getCentreX() & 0xFFFF;
        int playerY = player.getCentreY() & 0xFFFF;
        for (int i = 0; i < ACT2_SEASON_TRIGGERS.length; i++) {
            int[] trigger = ACT2_SEASON_TRIGGERS[i];
            if (playerX >= trigger[0] && playerX <= trigger[1]
                    && playerY >= trigger[2] && playerY < trigger[3]) {
                applyAct2SeasonTrigger(i, playerX, playerY, trigger[4]);
                return;
            }
        }
    }

    private void applyAct2SeasonTrigger(int triggerIndex, int playerX, int playerY, int threshold) {
        switch (triggerIndex) {
            case 0 -> applyVerticalGreenAutumnTrigger(playerY, threshold);
            case 1 -> applyHorizontalGreenAutumnTrigger(playerX, threshold);
            case 2, 3 -> applyHorizontalAutumnGoldTrigger(playerX, threshold);
            case 4 -> applyVerticalAutumnGoldTrigger(playerY, threshold);
            default -> {
            }
        }
    }

    private void applyVerticalGreenAutumnTrigger(int playerY, int threshold) {
        if (!autumnTriggerFlag) {
            if (playerY < threshold) {
                applySeasonState(SeasonPaletteMode.AUTUMN);
            }
            return;
        }
        if (playerY >= threshold) {
            applySeasonState(SeasonPaletteMode.GREEN);
        }
    }

    private void applyHorizontalGreenAutumnTrigger(int playerX, int threshold) {
        if (!autumnTriggerFlag) {
            if (playerX >= threshold) {
                applySeasonState(SeasonPaletteMode.AUTUMN);
            }
            return;
        }
        if (playerX < threshold) {
            applySeasonState(SeasonPaletteMode.GREEN);
        }
    }

    private void applyHorizontalAutumnGoldTrigger(int playerX, int threshold) {
        if (!autumnTriggerFlag) {
            if (playerX < threshold) {
                applySeasonState(SeasonPaletteMode.AUTUMN);
            }
            return;
        }
        if (playerX >= threshold) {
            applySeasonState(SeasonPaletteMode.GOLD);
        }
    }

    private void applyVerticalAutumnGoldTrigger(int playerY, int threshold) {
        if (!autumnTriggerFlag) {
            if (playerY < threshold) {
                applySeasonState(SeasonPaletteMode.AUTUMN);
            }
            return;
        }
        if (playerY >= threshold) {
            applySeasonState(SeasonPaletteMode.GOLD);
        }
    }

    private void applySeasonState(SeasonPaletteMode mode) {
        seasonPaletteMode = mode;
        switch (mode) {
            case GREEN -> {
                autumnTriggerFlag = false;
                seasonFlag = false;
                applySeasonPaletteBlock(Sonic3kConstants.PAL_MHZ1_LINE3_ADDR);
            }
            case AUTUMN -> {
                autumnTriggerFlag = true;
                seasonFlag = true;
                applySeasonPaletteBlock(Sonic3kConstants.PAL_MHZ2_LINE3_ADDR);
            }
            case GOLD -> {
                autumnTriggerFlag = false;
                seasonFlag = true;
                applySeasonPaletteBlock(Sonic3kConstants.PAL_MHZ2_GOLD_ADDR);
            }
        }
    }

    public void applySeasonStateForTest(SeasonPaletteMode mode) {
        applySeasonState(mode);
    }

    private void startAct2ShipSequence() {
        shipTransitionFlag = false;
        shipRedrawPosition = ACT2_SHIP_REDRAW_POSITION;
        shipRedrawRowCount = ACT2_SHIP_REDRAW_ROWCOUNT;
        eventRoutine = ACT2_SHIP_START_ROUTINE;
        completeAct2ShipSetup();
    }

    private void completeAct2ShipSetup() {
        loadPalette(ACT2_SHIP_PALETTE_LINE, Sonic3kConstants.PAL_MHZ2_SHIP_ADDR);
        spawnShipSequenceObjects();
        shipHIntActive = true;
        shipHIntCounter = ACT2_SHIP_H_INT_COUNTER;
        shipSecondaryBgCameraXFixed = ACT2_SHIP_SECONDARY_BG_X_FIXED;
        shipEffectiveBgY = ACT2_SHIP_SECONDARY_BG_Y;
        eventRoutine = ACT2_SHIP_ACTIVE_ROUTINE;
    }

    private void spawnShipSequenceObjects() {
        spawnObject(() -> new MhzShipSequenceControllerInstance(
                ACT2_SHIP_CONTROLLER_INITIAL_SWING_SPEED,
                ACT2_SHIP_CONTROLLER_INITIAL_MOTION));
        spawnObject(() -> new MhzShipPropellerInstance(0));
        spawnObject(() -> new MhzShipPropellerInstance(1));
    }

    public void applyShipControllerFrame(int motionAccumulator, int swingOffset) {
        shipSecondaryBgCameraXFixed -= motionAccumulator;
        shipEffectiveBgY = (ACT2_SHIP_SECONDARY_BG_Y + swingOffset + 5) & 0xFFFF;
        int highWord = (short) (shipSecondaryBgCameraXFixed >> 16);
        if (!shipScrollLockSet && highWord <= ACT2_SHIP_SCROLL_LOCK_BG_X_HIGH_WORD) {
            shipScrollLockSet = true;
            shipControllerSignalFlag = true;
            camera().setFrozen(true);
        }
    }

    private void updateAct2ShipActiveScrollState() {
        int cameraXCopy = act2ScreenCameraXCopy();
        int secondaryBgX = (short) (shipSecondaryBgCameraXFixed >> 16);
        int previousShipPrimaryHScroll = shipPrimaryHScroll;
        int previousPlayerCarryBgY = shipPlayerCarryBgY;
        shipHScrollCameraCopy = cameraXCopy;
        shipPrimaryHScroll = (cameraXCopy + ACT2_SHIP_PRIMARY_HSCROLL_BIAS + secondaryBgX) & 0xFFFF;
        shipPropellerY = (ACT2_SHIP_PROPELLER_Y_BASE - shipEffectiveBgY) & 0xFFFF;
        shipPropellerOneX = computeShipPropellerX(ACT2_SHIP_PROPELLER_ONE_BASE_X);
        shipPropellerTwoX = computeShipPropellerX(ACT2_SHIP_PROPELLER_TWO_BASE_X);
        carryObjectControlledPlayerWithShipScroll(previousShipPrimaryHScroll, previousPlayerCarryBgY);
        shipPlayerCarryBgY = shipEffectiveBgY;
    }

    private int computeShipPropellerX(int baseX) {
        int delta = (baseX - shipPrimaryHScroll) & 0xFFFF;
        if (delta > 0x7FFF) {
            return 0;
        }
        return (delta + ACT2_SHIP_PROPELLER_X_BIAS) & 0x01FF;
    }

    private int act2ScreenCameraXCopy() {
        return ((camera().getX() & 0xFFFF) + screenShakeOffset) & 0xFFFF;
    }

    private void carryObjectControlledPlayerWithShipScroll(int previousShipPrimaryHScroll, int previousShipEffectiveBgY) {
        AbstractPlayableSprite player = focusedPlayer();
        if (player == null || !player.isObjectControlled() || player.isObjectControlAllowsCpu()) {
            return;
        }
        NativePositionOps.addYPosPreserveSubpixel(
                player,
                (short) ((previousShipEffectiveBgY - shipEffectiveBgY) & 0xFFFF));
        NativePositionOps.addXPosPreserveSubpixel(
                player,
                (short) ((previousShipPrimaryHScroll - shipPrimaryHScroll) & 0xFFFF));
    }

    private void applySeasonPaletteBlock(int romAddr) {
        try {
            byte[] block = rom().readBytes(romAddr, PALETTE_LINE_SIZE * 2);
            LevelManager levelManager = levelManager();
            byte[] line3 = new byte[PALETTE_LINE_SIZE];
            byte[] line4 = new byte[PALETTE_LINE_SIZE];
            System.arraycopy(block, 0, line3, 0, PALETTE_LINE_SIZE);
            System.arraycopy(block, PALETTE_LINE_SIZE, line4, 0, PALETTE_LINE_SIZE);
            S3kPaletteWriteSupport.applyLine(
                    paletteRegistryOrNull(),
                    levelManager.getCurrentLevel(),
                    graphics(),
                    S3kPaletteOwners.ZONE_EVENT_PALETTE_LOAD,
                    S3kPaletteOwners.PRIORITY_ZONE_EVENT,
                    SEASON_PALETTE_START_LINE,
                    line3);
            S3kPaletteWriteSupport.applyLine(
                    paletteRegistryOrNull(),
                    levelManager.getCurrentLevel(),
                    graphics(),
                    S3kPaletteOwners.ZONE_EVENT_PALETTE_LOAD,
                    S3kPaletteOwners.PRIORITY_ZONE_EVENT,
                    SEASON_PALETTE_START_LINE + 1,
                    line4);
            S3kPaletteWriteSupport.resolvePendingWritesNow(
                    paletteRegistryOrNull(),
                    levelManager.getCurrentLevel(),
                    graphics());
        } catch (IOException | RuntimeException ignored) {
            // Palette loads are best-effort during partial headless setup.
        }
    }

    private AbstractPlayableSprite focusedPlayer() {
        return camera().getFocusedSprite();
    }

    public boolean isBossFlag() {
        return bossFlag;
    }

    public void setBossFlag(boolean bossFlag) {
        this.bossFlag = bossFlag;
    }

    public boolean isActTransitionFlagActive() {
        return actTransitionFlag;
    }

    public void setActTransitionFlag(boolean actTransitionFlag) {
        this.actTransitionFlag = actTransitionFlag;
    }

    public int getSpecialEventsRoutine() {
        return specialEventsRoutine;
    }

    public void setSpecialEventsRoutine(int specialEventsRoutine) {
        this.specialEventsRoutine = specialEventsRoutine;
    }

    public boolean isSeasonFlagSet() {
        return seasonFlag;
    }

    public void clearSeasonFlag() {
        seasonFlag = false;
    }

    public boolean isAutumnTriggerFlagSet() {
        return autumnTriggerFlag;
    }

    public SeasonPaletteMode getSeasonPaletteMode() {
        return seasonPaletteMode;
    }

    public boolean isShipTransitionFlagSet() {
        return shipTransitionFlag;
    }

    public void setShipTransitionFlag(boolean shipTransitionFlag) {
        this.shipTransitionFlag = shipTransitionFlag;
    }

    public int getShipRedrawPosition() {
        return shipRedrawPosition;
    }

    public int getShipRedrawRowCount() {
        return shipRedrawRowCount;
    }

    public boolean isShipHIntActive() {
        return shipHIntActive;
    }

    public int getShipHIntCounter() {
        return shipHIntCounter;
    }

    public int getShipSecondaryBgCameraXFixed() {
        return shipSecondaryBgCameraXFixed;
    }

    public int getShipEffectiveBgY() {
        return shipEffectiveBgY;
    }

    public boolean isShipScrollLockSet() {
        return shipScrollLockSet;
    }

    public boolean isShipControllerSignalFlagSet() {
        return shipControllerSignalFlag;
    }

    public void signalEndBossWalkoffPrep() {
        endBossWalkoffPrepEventFlag = 0x55;
    }

    public int getEndBossWalkoffPrepEventFlag() {
        return endBossWalkoffPrepEventFlag;
    }

    public int getScreenShakeOffset() {
        return screenShakeOffset;
    }

    public int getScreenShakeLastOffset() {
        return screenShakeLastOffset;
    }

    public void setScreenShakeFlagForTest(int screenShakeFlag) {
        this.screenShakeFlag = screenShakeFlag;
    }

    public void setScreenShakeOffsetForTest(int screenShakeOffset) {
        this.screenShakeOffset = screenShakeOffset;
    }

    public int getShipHScrollCameraCopy() {
        return shipHScrollCameraCopy;
    }

    public int getShipPrimaryHScroll() {
        return shipPrimaryHScroll;
    }

    public int getShipPropellerOneX() {
        return shipPropellerOneX;
    }

    public int getShipPropellerTwoX() {
        return shipPropellerTwoX;
    }

    public int getShipPropellerY() {
        return shipPropellerY;
    }

    public int getAct2BackgroundRoutine() {
        return act2BackgroundRoutine;
    }

    /**
     * True when {@code MHZ2_BackgroundEvent}'s dispatch table
     * (sonic3k.asm:112861-113104) routes vertical BG deform through
     * {@code sub_554B8} for the current {@code Events_routine_bg} value,
     * instead of the shared {@code MHZ_Deform} routine.
     *
     * <p>This is every routine value except two, both of which stay on
     * standard {@code MHZ_Deform}:
     * <ul>
     *   <li>Routine {@code 0} (loc_551EE, asm 112883-112899): conditional on
     *       {@code P1.x >= $3700 && P1.y < $500} in the ROM, but that exact
     *       condition is what {@link #updateAct2InitialBackgroundEvent()}
     *       already tests to decide whether to advance to routine {@code 4}
     *       this same frame — so by the time this predicate is read,
     *       {@code act2BackgroundRoutine} only remains {@code 0} when the
     *       ROM condition was false (standard deform).</li>
     *   <li>Routine {@code $C} (loc_552F8, asm 112977-112986): unconditional
     *       standard {@code MHZ_Deform}. Routine {@code 8}'s own
     *       {@code y >= $500} branch (loc_55250, asm 112923-112932) also
     *       calls {@code MHZ_Deform} inline before advancing to {@code $C},
     *       for the same reason.</li>
     * </ul>
     *
     * <p>Routine {@code 4} (loc_55236, asm 112910-112911) is unconditional
     * {@code sub_554B8}. Routine {@code 8}'s other two ROM exits both leave
     * this predicate true: the {@code $420 < P1.y < $500 && status bit 1}
     * branch (loc_552E0, asm 112938-112966) calls {@code sub_554B8} directly
     * and leaves {@code Events_routine_bg} at {@code 8}; the remaining case
     * (loc_5528A, asm 112941-112962) advances to routine {@code $10} within
     * the same frame, and {@link #updateAct2EndBossArenaBackgroundEvent()}
     * mirrors the ROM chain into loc_55312, which funnels to {@code sub_554B8}
     * via loc_55486 (asm 113099) like every routine {@code >= $10}.
     */
    public boolean isBossAreaBackgroundDeformActive() {
        return act2BackgroundRoutine != 0
                && act2BackgroundRoutine != ACT2_BG_CUSTOM_LAYOUT_REDRAW_ROUTINE;
    }

    public boolean isEndBossCustomLayoutQueued() {
        return endBossCustomLayoutQueued;
    }

    public void setAct2BackgroundRoutineForTest(int act2BackgroundRoutine) {
        this.act2BackgroundRoutine = act2BackgroundRoutine;
    }

    public boolean isEndBossArenaBackgroundActive() {
        return endBossArenaBackgroundActive;
    }

    public int getEndBossArenaDrawPosition() {
        return endBossArenaDrawPosition;
    }

    public int getEndBossArenaDrawRowCount() {
        return endBossArenaDrawRowCount;
    }

    public int getEndBossArenaScrollDataByte() {
        return endBossArenaScrollDataByte;
    }

    public boolean isEndBossPillarArtQueued() {
        return endBossPillarArtQueued;
    }

    public boolean isEndBossArenaForegroundRefreshActive() {
        return endBossArenaForegroundRefreshActive;
    }

    public void setEndBossArenaForegroundRefreshActiveForTest(boolean endBossArenaForegroundRefreshActive) {
        this.endBossArenaForegroundRefreshActive = endBossArenaForegroundRefreshActive;
    }

    public boolean isEndBossArenaHScrollCleared() {
        return endBossArenaHScrollCleared;
    }

    public int getEndBossArenaPillarControllerCount() {
        return endBossArenaPillarControllerCount;
    }

    public int getEndBossArenaTallSupportCount() {
        return endBossArenaTallSupportCount;
    }

    public int getEndBossArenaSpikeHelperCount() {
        return endBossArenaSpikeTiers.length;
    }

    public void validateRewindSidecarState() {
        if (endBossArenaSpikeTiers == null) {
            throw new IllegalArgumentException("MHZ rewind sidecar restored null endBossArenaSpikeTiers");
        }
        if (endBossArenaSpikeAlternateSides == null) {
            throw new IllegalArgumentException("MHZ rewind sidecar restored null endBossArenaSpikeAlternateSides");
        }
        if (endBossArenaSpikeActive == null) {
            throw new IllegalArgumentException("MHZ rewind sidecar restored null endBossArenaSpikeActive");
        }
        if (endBossArenaSpikeY == null) {
            throw new IllegalArgumentException("MHZ rewind sidecar restored null endBossArenaSpikeY");
        }
        int spikeCount = endBossArenaSpikeTiers.length;
        if (endBossArenaSpikeAlternateSides.length != spikeCount
                || endBossArenaSpikeActive.length != spikeCount
                || endBossArenaSpikeY.length != spikeCount) {
            throw new IllegalArgumentException("MHZ rewind sidecar restored mismatched end-boss spike array lengths");
        }
    }

    public int[] getEndBossArenaSpikeTiersForTest() {
        return endBossArenaSpikeTiers.clone();
    }

    public boolean[] getEndBossArenaSpikeAlternateSidesForTest() {
        return endBossArenaSpikeAlternateSides.clone();
    }

    public boolean[] getEndBossArenaSpikeActiveForTest() {
        return endBossArenaSpikeActive.clone();
    }

    public int[] getEndBossArenaSpikeYForTest() {
        return endBossArenaSpikeY.clone();
    }

    public boolean isEndBossArenaRestoreRequested() {
        return endBossArenaRestoreRequested;
    }

    public boolean isEndBossArenaSpikeDeletionFlagSet() {
        return endBossArenaSpikeDeletionFlag;
    }

    public void setEndBossArenaRestoreRequested(boolean endBossArenaRestoreRequested) {
        this.endBossArenaRestoreRequested = endBossArenaRestoreRequested;
    }

    public boolean isLeafBlowerCutsceneFlagSet() {
        return leafBlowerCutsceneFlag;
    }

    public void setLeafBlowerCutsceneFlag(boolean leafBlowerCutsceneFlag) {
        this.leafBlowerCutsceneFlag = leafBlowerCutsceneFlag;
    }

    public int getLevelRepeatOffset() {
        return levelRepeatOffset;
    }
}
