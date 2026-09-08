package com.openggf.game.sonic3k.events;

import com.openggf.game.PlayerCharacter;
import com.openggf.game.rewind.RewindTransient;
import com.openggf.game.mutation.LayoutMutationContext;
import com.openggf.game.mutation.LevelMutationSurface;
import com.openggf.game.mutation.MutationEffects;
import com.openggf.game.save.SaveReason;
import com.openggf.game.save.SessionSaveRequests;
import com.openggf.game.sonic3k.Sonic3kObjectArt;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.Sonic3kObjectArtProvider;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.objects.LbzInvisibleBarrierInstance;
import com.openggf.game.sonic3k.runtime.LbzZoneRuntimeState;
import com.openggf.game.sonic3k.runtime.S3kRuntimeStates;
import com.openggf.game.sonic3k.resources.S3kKosDecompressionQueue;
import com.openggf.game.sonic3k.resources.S3kKosModuleQueue;
import com.openggf.game.sonic3k.resources.S3kKosRamDestinations;
import com.openggf.game.sonic3k.resources.S3kKosTransitionPreflight;
import com.openggf.game.timing.HardwareWorkHandle;
import com.openggf.game.timing.HardwareWorkKind;
import com.openggf.camera.Camera;
import com.openggf.game.sonic3k.Sonic3kLevel;
import com.openggf.data.Rom;
import com.openggf.level.AbstractLevel;
import com.openggf.level.Chunk;
import com.openggf.level.Level;
import com.openggf.level.LevelConstants;
import com.openggf.level.Map;
import com.openggf.level.Pattern;
import com.openggf.level.PatternDesc;
import com.openggf.level.SeamlessLevelTransitionRequest;
import com.openggf.game.RuntimeArtAdmissionPolicy;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.data.RomByteReader;

import java.io.IOException;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Optional;

/**
 * Launch Base Zone dynamic level events.
 *
 * <p>ROM: {@code LBZ1_ScreenEvent} / {@code LBZ1_CheckLayoutMod}
 * (docs/skdisasm/s3.asm:74713-75083). LBZ1's "interior reveal" spaces are
 * foreground layout copies: entering one of four player rectangles copies
 * chunk-index bytes from hidden staging rows into the visible foreground
 * layout, and leaving the matching exit X range restores the covered variant.
 */
public final class Sonic3kLBZEvents extends Sonic3kZoneEvents {
    private static final int FG_LAYER = 0;
    private static final int BG_LAYER = 1;
    private static final int ENDING_CLEAR_X = 0x74;
    private static final int ENDING_CLEAR_Y = 0;
    private static final int ENDING_CLEAR_WIDTH = 4;
    private static final int ENDING_CLEAR_HEIGHT = 12;
    private static final int ENDING_COPY_SOURCE_X = 0x98;
    private static final int ENDING_COPY_SOURCE_Y = 0;
    private static final int ENDING_COPY_DEST_X = 0x74;
    private static final int ENDING_COPY_DEST_Y = 9;
    private static final int ENDING_COPY_WIDTH = 4;
    private static final int ENDING_COPY_HEIGHT = 3;
    private static final int ENDING_COLLAPSE_START_X = 0x3B60;
    private static final int ENDING_COLLAPSE_COLUMN_COUNT = 10;
    private static final int ENDING_COLLAPSE_MAX_SCROLL = -0x300;
    private static final int ENDING_COLLAPSE_GLOBAL_ACCEL = 0x100;
    private static final int ENDING_COLLAPSE_RUMBLE_MASK = 0x0F;
    private static final int[] ENDING_COLLAPSE_SCROLL_SPEED = {
            0x1EE, 0x1F2, 0x0C7, 0x1B3, 0x1B7, 0x198, 0x00E, 0x139
    };
    private static final byte[] SCREEN_SHAKE_CONTINUOUS = {
            1, 2, 1, 3, 1, 2, 2, 1, 2, 3, 1, 2, 1, 2, 0, 0,
            2, 0, 3, 2, 2, 3, 2, 2, 1, 3, 0, 0, 1, 0, 1, 3,
            1, 2, 1, 3, 1, 2, 2, 1, 2, 3, 1, 2, 1, 2, 0, 0,
            2, 0, 3, 2, 2, 3, 2, 2, 1, 3, 0, 0, 1, 0, 1, 3
    };

    /** ROM LBZ1_ScreenEvent Events_fg_4=$55: FG block ($7D, 2) becomes chunk $DA. */
    private static final int BOX_OPENED_CHUNK_X = 0x7D;
    private static final int BOX_OPENED_CHUNK_Y = 2;
    private static final int BOX_OPENED_CHUNK_VALUE = 0xDA;
    /** ROM loc_53F50 (Knuckles variant): copy FG ($78, 19) into ($78..$7A, 20). */
    private static final int KNUX_BOX_OPENED_SRC_X = 0x78;
    private static final int KNUX_BOX_OPENED_SRC_Y = 19;
    private static final int KNUX_BOX_OPENED_DEST_Y = 20;
    private static final int KNUX_BOX_OPENED_DEST_WIDTH = 3;
    /** ROM LBZ1BGE_DoTransition: world shift applied to players/objects/camera. */
    private static final int LBZ2_TRANSITION_OFFSET_X = -0x3A00;
    /** ROM LBZ2SE_FromTransition: LBZ2_LayoutMod applies once Player_1 x >= $60A. */
    private static final int LBZ2_ENTRY_CORRIDOR_GATE_X = 0x60A;
    /** ROM LBZ1 screen init: restart past $3B60 re-applies the ending layout. */
    private static final int RESTART_ENDING_LAYOUT_CAMERA_X = 0x3B60;
    /** ROM Adjust_LBZ2Layout chunk-table rotation: chunk $DB shifted by 24 words. */
    private static final int LBZ2_ADJUST_ROTATED_BLOCK = 0xDB;
    private static final int LBZ2_ADJUST_ROTATE_BY = 24;
    /** ROM LBZ2_LayoutMod: rows 0-5, six staging columns $94.. copied to columns 6.. */
    private static final int LBZ2_CORRIDOR_SRC_X = 0x94;
    private static final int LBZ2_CORRIDOR_DEST_X = 6;
    private static final int LBZ2_CORRIDOR_SIZE = 6;
    /** ROM LBZ2_Resize: Death Egg terrain/art swap threshold. */
    private static final int LBZ2_DEATH_EGG_SWAP_CAMERA_X = 0x3BC0;
    private static final int LBZ2_DEATH_EGG_SWAP_CAMERA_Y = 0x0500;
    private static final int LBZ2_LAUNCH_CAMERA_MAX_X = 0x4390;
    private static final int LBZ2_LAUNCH_CAMERA_MAX_Y = 0x0668;
    private static final int LBZ2_LAUNCH_PRE_DELAY = 0x3C;
    private static final int LBZ2_LAUNCH_FG_SPEED = 0x1E00;
    private static final int LBZ2_LAUNCH_BG_SPEED = 0x6200;
    private static final int LBZ2_LAUNCH_BG_SCROLL_LOCK_SPEED = 0x2200;
    private static final int LBZ2_LAUNCH_BG_MIN_FALL_SPEED = -0x18000;
    private static final int LBZ2_LAUNCH_DECEL = 0x100;
    private static final int LBZ2_WATER_DRAIN_LIMIT = 0x0F00;
    private static final int LBZ2_WATER_DRAIN_CLAMP = 0x0F80;
    private static final int LBZ2_PAD_DETACH_SCROLL_DONE = 0x28;
    private static final int LBZ2_PAD_WINDOW_COPY_FRAMES = 0x1C;
    private static final int LBZ2_FINAL_FALL_CAMERA_DY = -2;
    private static final int LBZ2_PAD_CLEAR_X = 0x87;
    private static final int LBZ2_PAD_CLEAR_Y = 0x0B;
    private static final int LBZ2_PAD_CLEAR_WIDTH = 3;
    private static final int LBZ2_PAD_CLEAR_HEIGHT = 2;
    private static final int LBZ2_DEATH_EGG_BG_REFRAME_SOURCE_ROW_0 = 2;
    private static final int LBZ2_DEATH_EGG_BG_REFRAME_SOURCE_ROW_1 = 3;
    private static final int LBZ2_DEATH_EGG_BG_REFRAME_DEST_ROW_0 = 0;
    private static final int LBZ2_DEATH_EGG_BG_REFRAME_DEST_ROW_1 = 1;

    private boolean endingCollapseActive;
    private boolean endingCollapseFinished;
    private boolean endingBarrierSpawnPending;
    private int endingCollapseGlobalFixed;
    private int endingCollapsePhase;
    private boolean eventsFg5;
    private boolean lbz2TransitionArtQueued;
    @RewindTransient(reason = "queue facades are rebound from captured ordinals")
    private S3kKosDecompressionQueue lbz2TransitionDirectQueue;
    @RewindTransient(reason = "queue facades are rebound from captured ordinals")
    private S3kKosModuleQueue lbz2TransitionModuleQueue;
    @RewindTransient(reason = "handles are rebound from captured ordinals")
    private HardwareWorkHandle lbz2TransitionChunkHandle;
    @RewindTransient(reason = "handles are rebound from captured ordinals")
    private HardwareWorkHandle lbz2TransitionBlockHandle;
    @RewindTransient(reason = "handles are rebound from captured ordinals")
    private HardwareWorkHandle lbz2TransitionArtHandle;
    private long lbz2TransitionChunkOrdinal = -1;
    private long lbz2TransitionBlockOrdinal = -1;
    private long lbz2TransitionArtOrdinal = -1;
    @RewindTransient(reason = "queue facades are rebound from captured ordinals")
    private S3kKosDecompressionQueue deathEggTerrainDirectQueue;
    @RewindTransient(reason = "queue facades are rebound from captured ordinals")
    private S3kKosModuleQueue deathEggTerrainModuleQueue;
    @RewindTransient(reason = "handles are rebound from captured ordinals")
    private HardwareWorkHandle deathEggBlocksHandle;
    @RewindTransient(reason = "handles are rebound from captured ordinals")
    private HardwareWorkHandle deathEggChunksHandle;
    @RewindTransient(reason = "handles are rebound from captured ordinals")
    private HardwareWorkHandle deathEggTerrainArtHandle;
    @RewindTransient(reason = "queue facades are rebound from captured ordinals")
    private S3kKosModuleQueue deathEggLaunchArtQueue;
    @RewindTransient(reason = "handles are rebound from captured ordinals")
    private HardwareWorkHandle deathEggLaunchArtHandle;
    private long deathEggBlocksOrdinal = -1;
    private long deathEggChunksOrdinal = -1;
    private long deathEggTerrainArtOrdinal = -1;
    private long deathEggLaunchArtOrdinal = -1;
    private boolean restartInitChecked;
    private int[] lbz2CopiedWindowDescriptors;
    private int lbz2CopiedWindowScreenX;
    private int lbz2CopiedWindowScreenY;
    private boolean lbz2CopiedWindowActive;
    private int activeAct;
    private boolean postTitleAct2SizeChangeActive;
    private int postTitleAct2TargetMaxX;
    private int postTitleAct2TargetMinY;
    private int postTitleAct2TargetMaxY;
    private boolean postTitleAct2WorkersCreatedThisPass;
    private boolean postTitleAct2BigArmCreationEntriesSeeded;
    private int act2MaxXAccumulator;
    private int act2MinYAccumulator;
    private int act2MaxYAccumulator;
    private boolean act2MaxXWorkerActive;
    private boolean act2MinYWorkerActive;
    private boolean act2MaxYWorkerActive;
    private boolean act2MaxXWorkerCompleted;
    private boolean act2MinYWorkerCompleted;
    private boolean act2MaxYWorkerCompleted;
    private final PatternDesc lbz2CopiedWindowPatternDesc = new PatternDesc();
    private final int[] endingCollapseFixed = new int[ENDING_COLLAPSE_COLUMN_COUNT];
    private final int[] endingCollapseScroll = new int[ENDING_COLLAPSE_COLUMN_COUNT];

    /**
     * S3K layout row pointers are interleaved FG/BG word pairs. In the ROM,
     * {@code a3 + 4 * row} addresses an FG row pointer after {@code a3} is set
     * to {@code Level_layout_main}, so {@link CopySpec} {@code sourceY}/{@code destY}
     * are absolute FG block rows.
     *
     * <p>The ROM {@code LBZ1_DoModN} routines copy hidden staging rows into the
     * <em>visible</em> foreground: mods 2/3/4 write into {@code (a3)} (FG row 0)
     * and mod 1 into {@code 8(a3)} (FG row 2), regardless of which staging row
     * they read from. Destination rows must therefore stay anchored to the
     * visible door rows (0/2) and never track the source staging row, or the
     * door chunks are never swapped and the door stays solid.
     */
    private static final LayoutMod[] LBZ1_LAYOUT_MODS = {
            new LayoutMod(
                    1,
                    new TriggerRange(0x13E0, 0x16A0, 0x0100, 0x0580),
                    new ExitRange(0x1376, 0x170A),
                    new CopySpec(0x80, 0, 0x26, 2, 8, 9),
                    new CopySpec(0x88, 0, 0x26, 2, 8, 9),
                    true),
            new LayoutMod(
                    2,
                    new TriggerRange(0x2160, 0x2520, 0x0000, 0x0700),
                    new ExitRange(0x20F6, 0x258A),
                    new CopySpec(0x80, 9, 0x42, 0, 10, 14),
                    new CopySpec(0x8A, 9, 0x42, 0, 10, 14),
                    false),
            new LayoutMod(
                    3,
                    new TriggerRange(0x3A60, 0x3BA0, 0x0000, 0x0600),
                    new ExitRange(0x39F6, 0x3C0A),
                    new CopySpec(0x94, 0, 0x74, 0, 4, 12),
                    new CopySpec(0x98, 0, 0x74, 0, 4, 12),
                    false),
            new LayoutMod(
                    4,
                    new TriggerRange(0x3DE0, 0x3FA0, 0x0000, 0x0300),
                    new ExitRange(0x3D76, 0x400A),
                    new CopySpec(0x94, 12, 0x7A, 0, 6, 6),
                    new CopySpec(0x9A, 12, 0x7A, 0, 6, 6),
                    false)
    };

    @Override
    public void init(int act) {
        super.init(act);
        activeAct = act;
        postTitleAct2SizeChangeActive = false;
        postTitleAct2TargetMaxX = 0;
        postTitleAct2TargetMinY = 0;
        postTitleAct2TargetMaxY = 0;
        postTitleAct2WorkersCreatedThisPass = false;
        postTitleAct2BigArmCreationEntriesSeeded = false;
        act2MaxXAccumulator = 0;
        act2MinYAccumulator = 0;
        act2MaxYAccumulator = 0;
        act2MaxXWorkerActive = false;
        act2MinYWorkerActive = false;
        act2MaxYWorkerActive = false;
        act2MaxXWorkerCompleted = false;
        act2MinYWorkerCompleted = false;
        act2MaxYWorkerCompleted = false;
        clearTransitionKosOwnership();
        clearDeathEggTerrainKosOwnership();
        clearDeathEggLaunchArtOwnership();
    }

    @Override
    public void update(int act, int frameCounter) {
        if (!hasRuntime()) {
            return;
        }
        if (act != 0) {
            LbzZoneRuntimeState state = currentLbzRuntimeState().orElse(null);
            if (state != null) {
                // LBZ2_ScreenEvent consumes the previously prepared offset
                // into Camera_Y_pos_copy before its BG event runs.
                state.applyTimedScreenShakeForeground();
            }
            updateAct2EntryLayout();
            updateAct2DeathEggTerrainSwap();
            updateAct2Launch(frameCounter);
            if (state != null) {
                prepareTimedScreenShake(state, camera().getFocusedSprite());
            }
            return;
        }
        applyRestartFromBossAreaInitIfNeeded();
        if (handleSeamlessReloadStage()) {
            return;
        }
        updateEndingCollapse(frameCounter);

        LbzZoneRuntimeState state = S3kRuntimeStates.currentLbz(zoneRuntimeRegistry()).orElse(null);
        AbstractPlayableSprite player = camera().getFocusedSprite();
        if (state == null || player == null) {
            return;
        }
        if (endingCollapseActive) {
            return;
        }

        int playerX = player.getCentreX();
        int playerY = player.getCentreY();
        int activeMod = state.getActiveInteriorLayoutMod();
        if (activeMod != 0) {
            LayoutMod mod = modById(activeMod);
            if (mod != null && !mod.exitRange().contains(playerX)) {
                applyLayoutCopy(mod.coveredCopy());
                state.setActiveInteriorLayoutMod(0);
            }
            return;
        }

        for (LayoutMod mod : LBZ1_LAYOUT_MODS) {
            if (mod.id() == 3 && state.isInteriorLayoutMod3Disabled()) {
                continue;
            }
            if (mod.matches(playerX, playerY)) {
                applyLayoutCopy(mod.revealCopy());
                state.setActiveInteriorLayoutMod(mod.id());
                return;
            }
        }
    }

    /**
     * ROM {@code Change_Act2Sizes}: publish LBZ2's loaded level bounds and
     * retain the three gradual boundary workers created by the ending sign.
     */
    public void preparePostTitleAct2SizeChange() {
        if (activeAct != 1) {
            return;
        }
        Level level = levelManager().getCurrentLevel();
        if (level == null) {
            return;
        }
        prepareAct2SizeChange(level.getMaxX(), level.getMinY(), level.getMaxY());
    }

    /** Native Big Arm {@code loc_74DA4}: literal stored targets, not current bounds. */
    public void prepareBigArmFloorTransition() {
        if (activeAct == 1) {
            boolean alreadyActive = postTitleAct2SizeChangeActive;
            prepareAct2SizeChange(0x6000, 0, 0x1000);
            if (!alreadyActive && postTitleAct2SizeChangeActive) {
                // The settling floor creates the gradual workers in later SST
                // slots, so their creation entries run later in this same
                // Process_Sprites pass (AllocateObjectAfterCurrent).
                act2MaxXAccumulator += 0x4000;
                act2MinYAccumulator += 0x4000;
                act2MaxYAccumulator += 0x8000;
                postTitleAct2BigArmCreationEntriesSeeded = true;
            }
        }
    }

    private void prepareAct2SizeChange(int targetMaxX, int targetMinY, int targetMaxY) {
        if (postTitleAct2SizeChangeActive) {
            return;
        }
        postTitleAct2TargetMaxX = targetMaxX;
        postTitleAct2TargetMinY = targetMinY;
        postTitleAct2TargetMaxY = targetMaxY;
        postTitleAct2SizeChangeActive = true;
        postTitleAct2WorkersCreatedThisPass = true;
        postTitleAct2BigArmCreationEntriesSeeded = false;
        act2MaxXAccumulator = 0;
        act2MinYAccumulator = 0;
        act2MaxYAccumulator = 0;
        act2MaxXWorkerActive = true;
        act2MinYWorkerActive = true;
        act2MaxYWorkerActive = true;
        act2MaxXWorkerCompleted = false;
        act2MinYWorkerCompleted = false;
        act2MaxYWorkerCompleted = false;
        camera().setMaxYTarget((short) postTitleAct2TargetMaxY);
    }

    /** Native {@code Load_PLC $71} at Big Arm's autowalk target. */
    public void loadBigArmPostGatePlc() {
        if (activeAct == 1) {
            applyPlc(Sonic3kConstants.PLC_LBZ2_FINAL_BOSS_1);
        }
    }

    public void startBigArmTimedShake(int frames) {
        if (activeAct == 1) {
            currentLbzRuntimeState().ifPresent(state -> state.startTimedScreenShake(frames));
        }
    }

    private void prepareTimedScreenShake(
            LbzZoneRuntimeState state, AbstractPlayableSprite player) {
        boolean eligible = player != null && nativePlayerRoutine(player) < 6;
        int offset = 0;
        if (eligible && state.getTimedShakeCountdown() > 0) {
            int index = state.getTimedShakeCountdown() - 1;
            try {
                offset = rom().readByte(Sonic3kConstants.SCREEN_SHAKE_ARRAY_ADDR + index);
            } catch (IOException ex) {
                throw new IllegalStateException(
                        "Unable to read ScreenShakeArray from the verified S3K ROM", ex);
            }
        }
        state.prepareTimedScreenShakeBackground(eligible, offset);
    }

    private static int nativePlayerRoutine(AbstractPlayableSprite player) {
        if (player.getObjectRoutineOverride() != null) {
            return player.getObjectRoutineOverride() & 0xFF;
        }
        if (player.getDead()) {
            return 6;
        }
        return player.isHurt() ? 4 : 2;
    }

    /** Runs the retained {@code Child1_Act2LevelSize} slots before the camera step. */
    public void updatePostTitleAct2SizeWorkers() {
        if (!postTitleAct2SizeChangeActive) {
            return;
        }
        if (postTitleAct2WorkersCreatedThisPass) {
            postTitleAct2WorkersCreatedThisPass = false;
            if (!postTitleAct2BigArmCreationEntriesSeeded) {
                // Generic title-card completion creates the retained workers
                // outside their eligible object-loop pass. This centralized
                // call consumes that creation marker without running an entry.
                return;
            }
            // Big Arm's later-slot children already ran their creation entries;
            // this centralized call is their following entry.
            postTitleAct2BigArmCreationEntriesSeeded = false;
        }
        updatePostTitleAct2WorkerEntries();
    }

    private void updatePostTitleAct2WorkerEntries() {
        if (act2MaxXWorkerActive && updateGradualMaxX()) {
            act2MaxXWorkerActive = false;
            act2MaxXWorkerCompleted = true;
        }
        if (act2MinYWorkerActive && updateGradualMinY()) {
            act2MinYWorkerActive = false;
            act2MinYWorkerCompleted = true;
        }
        if (act2MaxYWorkerActive && updateGradualMaxY()) {
            act2MaxYWorkerActive = false;
            act2MaxYWorkerCompleted = true;
        }
        postTitleAct2SizeChangeActive = act2MaxXWorkerActive
                || act2MinYWorkerActive || act2MaxYWorkerActive;
    }

    public boolean isPostTitleAct2SizeChangeActiveForTest() {
        return postTitleAct2SizeChangeActive;
    }

    public boolean werePostTitleAct2WorkersCreatedThisPassForTest() {
        return postTitleAct2WorkersCreatedThisPass;
    }

    public int[] postTitleAct2TargetsForTest() {
        return new int[]{
                postTitleAct2TargetMaxX,
                postTitleAct2TargetMinY,
                postTitleAct2TargetMaxY
        };
    }

    public int[] postTitleAct2WorkerAccumulatorsForTest() {
        return new int[]{act2MaxXAccumulator, act2MinYAccumulator, act2MaxYAccumulator};
    }

    public boolean[] postTitleAct2WorkerPhasesForTest() {
        return new boolean[]{
                act2MaxXWorkerActive, act2MinYWorkerActive, act2MaxYWorkerActive,
                act2MaxXWorkerCompleted, act2MinYWorkerCompleted, act2MaxYWorkerCompleted
        };
    }

    private boolean updateGradualMaxX() {
        int current = camera().getMaxX() & 0xFFFF;
        act2MaxXAccumulator += 0x4000;
        int next = current + (act2MaxXAccumulator >>> 16);
        if (next >= postTitleAct2TargetMaxX) {
            camera().setMaxX((short) postTitleAct2TargetMaxX);
            return true;
        }
        camera().setMaxX((short) next);
        return false;
    }

    private boolean updateGradualMinY() {
        int current = camera().getMinY() & 0xFFFF;
        act2MinYAccumulator += 0x4000;
        int next = current - (act2MinYAccumulator >>> 16);
        if (next <= postTitleAct2TargetMinY) {
            camera().setMinY((short) postTitleAct2TargetMinY);
            return true;
        }
        camera().setMinY((short) next);
        return false;
    }

    private boolean updateGradualMaxY() {
        int current = camera().getMaxY() & 0xFFFF;
        act2MaxYAccumulator += 0x8000;
        int next = current + (act2MaxYAccumulator >>> 16);
        if (next > postTitleAct2TargetMaxY) {
            camera().setMaxY((short) postTitleAct2TargetMaxY);
            return true;
        }
        camera().setMaxY((short) next);
        camera().setMaxYTarget((short) postTitleAct2TargetMaxY);
        return false;
    }

    /**
     * ROM: {@code LBZ1_ModEndingLayout} sets {@code Events_bg+$02}, which
     * prevents layout mod 3 from re-entering after the boss/ending building
     * has been cleared.
     */
    public void disableInteriorLayoutMod3() {
        if (!hasRuntime()) {
            return;
        }
        S3kRuntimeStates.currentLbz(zoneRuntimeRegistry())
                .ifPresent(state -> {
                    state.setInteriorLayoutMod3Disabled(true);
                    if (state.getActiveInteriorLayoutMod() == 3) {
                        state.setActiveInteriorLayoutMod(0);
                    }
                });
    }

    /**
     * Applies the post-collapse LBZ1 boss-area building layout.
     *
     * <p>ROM: {@code LBZ1_ModEndingLayout} clears a 4x12 strip at FG columns
     * {@code $74..$77}, then copies three lower rows from hidden staging columns
     * {@code $98..$9B} into rows 9-11. It also disables layout mod 3 so the
     * interior reveal logic cannot restore the demolished building.
     */
    public void applyEndingLayout() {
        if (!hasRuntime()) {
            return;
        }
        Level level = levelManager().getCurrentLevel();
        if (level == null || level.getMap() == null) {
            return;
        }
        LayoutMutationContext context = new LayoutMutationContext(
                LevelMutationSurface.forLevel(level),
                levelManager()::applyMutationEffects);
        zoneLayoutMutationPipeline().applyImmediately(
                mutationContext -> applyEndingLayout(level.getMap(), mutationContext.surface()),
                context);
        disableInteriorLayoutMod3();
    }

    /**
     * ROM: {@code Events_fg_4 = -1} arms {@code LBZ1_EventVScroll}. The screen
     * event animates ten foreground VScroll columns down to {@code -$300};
     * {@code LBZ1_ModEndingLayout} is called only after every column has
     * finished falling.
     */
    public void startEndingCollapse() {
        if (!hasRuntime() || endingCollapseActive || endingCollapseFinished) {
            return;
        }
        endingCollapseActive = true;
        endingCollapseGlobalFixed = 0;
        endingCollapsePhase = 0;
        Arrays.fill(endingCollapseFixed, 0);
        Arrays.fill(endingCollapseScroll, 0);
        // Robotnik writes Events_fg_4=-1 in his object slot. The following
        // screen-event pass enters LBZ1_EventVScroll and allocates the barrier;
        // it does not execute in Robotnik's already-active object pass.
        endingBarrierSpawnPending = true;
        currentLbzRuntimeState().ifPresent(state -> state.setLbz1KnucklesBombShakeActive(true));
    }

    public boolean isEndingCollapseActive() {
        return endingCollapseActive;
    }

    public boolean isEndingCollapseFinished() {
        return endingCollapseFinished;
    }

    public int[] getEndingCollapseScrollForTest() {
        return Arrays.copyOf(endingCollapseScroll, endingCollapseScroll.length);
    }

    public short[] buildEndingCollapseForegroundVScrollOverride(int cameraX) {
        if (!endingCollapseActive) {
            return null;
        }

        short[] override = new short[20];
        int firstScreenColumn = (ENDING_COLLAPSE_START_X >> 4) - Math.floorDiv(cameraX + 15, 16);
        boolean anyVisible = false;
        boolean anyScrolled = false;
        for (int i = 0; i < ENDING_COLLAPSE_COLUMN_COUNT; i++) {
            int screenColumn = firstScreenColumn + i;
            if (screenColumn < 0 || screenColumn >= override.length) {
                continue;
            }
            int scroll = endingCollapseScroll[i];
            override[screenColumn] = (short) scroll;
            anyVisible = true;
            anyScrolled |= scroll != 0;
        }
        return anyVisible && anyScrolled ? override : null;
    }

    private void updateEndingCollapse(int frameCounter) {
        if (!endingCollapseActive) {
            return;
        }
        if (endingBarrierSpawnPending) {
            endingBarrierSpawnPending = false;
            spawnInvisibleBarrier();
        }

        int globalFixedDelta = endingCollapseGlobalFixed;
        endingCollapseGlobalFixed += ENDING_COLLAPSE_GLOBAL_ACCEL;
        int phaseWord = endingCollapsePhase >> 6;
        endingCollapsePhase++;

        int unfinishedColumns = ENDING_COLLAPSE_COLUMN_COUNT;
        for (int i = 0; i < ENDING_COLLAPSE_COLUMN_COUNT; i++) {
            int speedIndex = ((phaseWord + ((i + 1) * 2)) & 0x0E) >> 1;
            int deltaFixed = (ENDING_COLLAPSE_SCROLL_SPEED[speedIndex] << 4) + globalFixedDelta;
            endingCollapseFixed[i] -= deltaFixed;
            int scroll = endingCollapseFixed[i] >> 16;
            if (scroll <= ENDING_COLLAPSE_MAX_SCROLL) {
                scroll = ENDING_COLLAPSE_MAX_SCROLL;
                endingCollapseFixed[i] = ENDING_COLLAPSE_MAX_SCROLL << 16;
                unfinishedColumns--;
            }
            endingCollapseScroll[i] = scroll;
        }

        if (((frameCounter - 1) & ENDING_COLLAPSE_RUMBLE_MASK) == 0 && unfinishedColumns > 0) {
            audio().playSfx(Sonic3kSfx.BIG_RUMBLE.id);
        }
        if (unfinishedColumns == 0) {
            finishEndingCollapse();
        }
    }

    private void finishEndingCollapse() {
        endingCollapseActive = false;
        endingCollapseFinished = true;
        endingBarrierSpawnPending = false;
        currentLbzRuntimeState().ifPresent(state -> state.setLbz1KnucklesBombShakeActive(false));
        Arrays.fill(endingCollapseScroll, 0);
        applyEndingLayout();
        audio().playSfx(Sonic3kSfx.CRASH.id);
    }

    /**
     * ROM: Obj_LevelResultsCreate sets Events_fg_5 for act-1 zones; the LBZ1
     * background event consumes it to run the LBZ1 -> LBZ2 seamless reload.
     */
    public void setEventsFg5(boolean flag) {
        eventsFg5 = flag;
    }

    public void requestLaunchStart() {
        currentLbzRuntimeState().ifPresent(LbzZoneRuntimeState::requestLaunchStart);
    }

    public boolean consumeLaunchStartRequested() {
        return currentLbzRuntimeState()
                .map(LbzZoneRuntimeState::consumeLaunchStartRequested)
                .orElse(false);
    }

    public void requestPadCollapseStart() {
        currentLbzRuntimeState().ifPresent(state -> {
            state.requestPadCollapseStart();
            lbz2CopiedWindowDescriptors = captureLaunchPadVisualTilemap();
        });
    }

    public boolean consumePadCollapseStartRequested() {
        return currentLbzRuntimeState()
                .map(LbzZoneRuntimeState::consumePadCollapseStartRequested)
                .orElse(false);
    }

    public void requestFinalFall() {
        currentLbzRuntimeState().ifPresent(LbzZoneRuntimeState::requestFinalFall);
    }

    public boolean consumeFinalFallRequested() {
        return currentLbzRuntimeState()
                .map(LbzZoneRuntimeState::consumeFinalFallRequested)
                .orElse(false);
    }

    public void requestLbz2RideAnimatedTiles() {
        currentLbzRuntimeState().ifPresent(state -> state.setLbz2RideAnimatedTileGateActive(true));
    }

    public boolean consumeLbz2RideAnimatedTilesRequested() {
        return currentLbzRuntimeState()
                .map(LbzZoneRuntimeState::consumeLbz2RideAnimatedTilesRequested)
                .orElse(false);
    }

    public boolean isLaunchActive() {
        return currentLbzRuntimeState()
                .map(LbzZoneRuntimeState::isLaunchActive)
                .orElse(false);
    }

    public int getLaunchYDelta() {
        return currentLbzRuntimeState()
                .map(LbzZoneRuntimeState::getLaunchYDelta)
                .orElse(0);
    }

    public void registerLaunchRiderAnchor(int anchorId) {
        currentLbzRuntimeState().ifPresent(state -> state.registerLaunchRiderAnchor(anchorId));
    }

    /**
     * ROM: LBZ1 screen init. When the player restarts from the lamppost past
     * the collapsed building (Camera_X_pos >= $3B60, non-Knuckles), the init
     * path allocates Obj_LBZ1InvisibleBarrier and applies LBZ1_ModEndingLayout.
     */
    private void applyRestartFromBossAreaInitIfNeeded() {
        if (restartInitChecked) {
            return;
        }
        restartInitChecked = true;
        if (playerCharacter() == PlayerCharacter.KNUCKLES) {
            return;
        }
        if ((camera().getX() & 0xFFFF) < RESTART_ENDING_LAYOUT_CAMERA_X) {
            return;
        }
        endingCollapseFinished = true;
        currentLbzRuntimeState().ifPresent(state -> state.setLbz1KnucklesBombShakeActive(false));
        spawnInvisibleBarrier();
        applyEndingLayout();
    }

    /**
     * ROM: LBZ1_EventVScroll allocates Obj_LBZ1InvisibleBarrier when the
     * collapse is armed (and the screen-init restart path mirrors it).
     */
    void spawnInvisibleBarrier() {
        spawnObject(() -> new LbzInvisibleBarrierInstance(
                new ObjectSpawn(0x3BC0, 0x0100, 0, 0, 0, false, 0)));
    }

    /**
     * ROM: LBZ1_ScreenEvent Events_fg_4=$55 handling. The Sonic/Tails variant
     * swaps the boss-area box chunk to $DA; the Knuckles variant (loc_53F50)
     * copies the staging chunk across the three Knuckles boss-area columns.
     */
    public void applyMinibossBoxOpenedChunkSwap(boolean knuckles) {
        if (!hasRuntime()) {
            return;
        }
        Level level = levelManager().getCurrentLevel();
        if (level == null || level.getMap() == null) {
            return;
        }
        Map map = level.getMap();
        LayoutMutationContext context = new LayoutMutationContext(
                LevelMutationSurface.forLevel(level),
                levelManager()::applyMutationEffects);
        zoneLayoutMutationPipeline().applyImmediately(
                mutationContext -> {
                    LevelMutationSurface surface = mutationContext.surface();
                    if (!knuckles) {
                        return surface.setBlockInMap(
                                FG_LAYER, BOX_OPENED_CHUNK_X, BOX_OPENED_CHUNK_Y, BOX_OPENED_CHUNK_VALUE);
                    }
                    int value = map.getValue(FG_LAYER, KNUX_BOX_OPENED_SRC_X, KNUX_BOX_OPENED_SRC_Y) & 0xFF;
                    MutationEffects combined = MutationEffects.NONE;
                    for (int i = 0; i < KNUX_BOX_OPENED_DEST_WIDTH; i++) {
                        combined = mergeEffects(combined, surface.setBlockInMap(
                                FG_LAYER, KNUX_BOX_OPENED_SRC_X + i, KNUX_BOX_OPENED_DEST_Y, value));
                    }
                    return combined;
                },
                context);
    }

    /**
     * ROM: LBZ1BGE_Normal consumes Events_fg_5 (queues the LBZ2 secondary
     * art/block/chunk data) and LBZ1BGE_DoTransition then reloads the level as
     * $0601, shifting the world left by $3A00. The engine's level reload loads
     * the full LBZ2 resource set, so both stages collapse into one here.
     *
     * @return true when the reload was performed this frame
     */
    private boolean handleSeamlessReloadStage() {
        if (eventsFg5) {
            // LBZ1BGE_Normal queues the secondary 128x128, 16x16 and 8x8
            // streams, then advances Events_routine_bg. DoTransition is first
            // polled on the following ScreenEvents pass.
            eventsFg5 = false;
            lbz2TransitionArtQueued = true;
            queueLbz2TransitionResources();
            return false;
        }
        if (!lbz2TransitionArtQueued) {
            return false;
        }
        rebindTransitionKosAfterRewind();
        if (lbz2TransitionModuleQueue.modulesLeft()) {
            return false;
        }
        claimTransitionKos();
        lbz2TransitionArtQueued = false;

        Camera camera = camera();
        int postTransitionMinX = offsetCameraBoundWord(camera.getMinX(), LBZ2_TRANSITION_OFFSET_X);
        int postTransitionMaxX = offsetCameraBoundWord(camera.getMaxX(), LBZ2_TRANSITION_OFFSET_X);
        // ROM LBZ1BGE_DoTransition only offsets the X bounds; the Y bounds keep
        // their act-1 values until the results exit applies Change_Act2Sizes.
        int postTransitionMinY = camera.getMinY() & 0xFFFF;
        int postTransitionMaxY = camera.getMaxY() & 0xFFFF;
        SeamlessLevelTransitionRequest request = SeamlessLevelTransitionRequest.builder(
                        SeamlessLevelTransitionRequest.TransitionType.RELOAD_TARGET_LEVEL)
                .targetZoneAct(Sonic3kZoneIds.ZONE_LBZ, 1)
                .runtimeArtAdmissionPolicy(RuntimeArtAdmissionPolicy.TITLE_OWNER)
                .deactivateLevelNow(false)
                .preserveMusic(true)
                .preserveLevelGamestate(true)
                .objectSurvivalPolicy(
                        SeamlessLevelTransitionRequest.ObjectSurvivalPolicy.PERSISTENT_EXACT_SST)
                // Obj_LevelResults and Obj_EndSignControl survive
                // LBZ1BGE_DoTransition's Load_Level. Level_end_flag therefore
                // remains set until the carried results owner finishes, while
                // End_of_level_flag is cleared for the later title-card edge.
                .preserveEndOfLevelActive(true)
                .showInLevelTitleCard(false)
                .preserveOffsetCameraPosition(true)
                .postTransitionMinX(postTransitionMinX)
                .postTransitionMaxX(postTransitionMaxX)
                .postTransitionMinY(postTransitionMinY)
                .postTransitionMaxY(postTransitionMaxY)
                .postTransitionMaxYTarget(postTransitionMaxY)
                .playerOffset(LBZ2_TRANSITION_OFFSET_X, 0)
                .cameraOffset(LBZ2_TRANSITION_OFFSET_X, 0)
                .build();

        SessionSaveRequests.requestCurrentSessionSave(SaveReason.PROGRESSION_SAVE);
        if (levelManager().getCurrentLevel() == null) {
            levelManager().requestSeamlessTransition(request);
            return true;
        }
        try {
            // ROM LBZ1BGE_DoTransition performs Load_Level and the coordinate
            // offsets inside the BG event routine, not on the next frame.
            levelManager().executeActTransition(request);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to apply LBZ act transition", e);
        }
        // ROM: Adjust_LBZ2Layout runs immediately after Load_Level; the entry
        // corridor mod stays gated on Player_1 x >= $60A (LBZ2SE_FromTransition).
        applyLbz2TransitionLayout();
        LbzZoneRuntimeState state = S3kRuntimeStates.currentLbz(zoneRuntimeRegistry()).orElse(null);
        if (state != null) {
            state.setLbz2LayoutAdjustApplied(true);
        }
        return true;
    }

    private void queueLbz2TransitionResources() {
        try {
            lbz2TransitionDirectQueue = directKosQueue();
            lbz2TransitionModuleQueue = moduleKosQueue();
            S3kKosTransitionPreflight.validate(
                    rom(), lbz2TransitionDirectQueue, lbz2TransitionModuleQueue,
                    Sonic3kConstants.KOS_LBZ2_CHUNK_ADDR,
                    Sonic3kConstants.KOS_LBZ2_SECONDARY_BLOCK_ADDR,
                    Sonic3kConstants.KOSM_LBZ2_SECONDARY_ART_ADDR);
            lbz2TransitionChunkHandle = lbz2TransitionDirectQueue.queueStandardKos(
                    rom(), Sonic3kConstants.KOS_LBZ2_CHUNK_ADDR,
                    S3kKosRamDestinations.RAM_START);
            lbz2TransitionChunkOrdinal = lbz2TransitionChunkHandle.ordinal();
            lbz2TransitionBlockHandle = lbz2TransitionDirectQueue.queueStandardKos(
                    rom(), Sonic3kConstants.KOS_LBZ2_SECONDARY_BLOCK_ADDR,
                    S3kKosRamDestinations.blockTableOffset(0x6B8));
            lbz2TransitionBlockOrdinal = lbz2TransitionBlockHandle.ordinal();
            lbz2TransitionArtHandle = lbz2TransitionModuleQueue.queue(
                    rom(), Sonic3kConstants.KOSM_LBZ2_SECONDARY_ART_ADDR, 0x19D);
            lbz2TransitionArtOrdinal = lbz2TransitionArtHandle.ordinal();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to queue LBZ2 transition resources", e);
        }
    }

    private void claimTransitionKos() {
        if (!lbz2TransitionDirectQueue.isReady(lbz2TransitionChunkHandle)
                || !lbz2TransitionDirectQueue.isReady(lbz2TransitionBlockHandle)
                || !lbz2TransitionModuleQueue.isReady(lbz2TransitionArtHandle)) {
            throw new IllegalStateException(
                    "LBZ2 queue emptied before owned payloads were ready");
        }
        lbz2TransitionDirectQueue.claim(lbz2TransitionChunkHandle);
        lbz2TransitionDirectQueue.claim(lbz2TransitionBlockHandle);
        lbz2TransitionModuleQueue.claim(lbz2TransitionArtHandle);
        clearTransitionKosOwnership();
    }

    private void rebindTransitionKosAfterRewind() {
        if (lbz2TransitionArtOrdinal < 0 || lbz2TransitionModuleQueue != null) {
            return;
        }
        var timing = hardwareTiming();
        lbz2TransitionChunkHandle = timing.pendingHandle(
                HardwareWorkKind.KOS_DECOMPRESSION_QUEUE, lbz2TransitionChunkOrdinal).orElseThrow();
        lbz2TransitionBlockHandle = timing.pendingHandle(
                HardwareWorkKind.KOS_DECOMPRESSION_QUEUE, lbz2TransitionBlockOrdinal).orElseThrow();
        lbz2TransitionArtHandle = timing.pendingHandle(
                HardwareWorkKind.KOS_MODULE_QUEUE, lbz2TransitionArtOrdinal).orElseThrow();
        lbz2TransitionDirectQueue = directKosQueue();
        lbz2TransitionModuleQueue = moduleKosQueue();
    }

    public void discardHardwareWorkFacadesAfterRewind() {
        lbz2TransitionDirectQueue = null;
        lbz2TransitionModuleQueue = null;
        lbz2TransitionChunkHandle = null;
        lbz2TransitionBlockHandle = null;
        lbz2TransitionArtHandle = null;
        deathEggTerrainDirectQueue = null;
        deathEggTerrainModuleQueue = null;
        deathEggBlocksHandle = null;
        deathEggChunksHandle = null;
        deathEggTerrainArtHandle = null;
        deathEggLaunchArtQueue = null;
        deathEggLaunchArtHandle = null;
    }

    private void clearTransitionKosOwnership() {
        discardHardwareWorkFacadesAfterRewind();
        lbz2TransitionChunkOrdinal = -1;
        lbz2TransitionBlockOrdinal = -1;
        lbz2TransitionArtOrdinal = -1;
    }

    /**
     * LBZ2 entry layout state machine.
     *
     * <p>Direct act-2 starts mirror ROM {@code LBZ2_ScreenInit}: apply
     * {@code Adjust_LBZ2Layout} and {@code LBZ2_LayoutMod} immediately. After a
     * transition ({@code Adjust} already applied), the corridor mod waits for
     * {@code Player_1 x >= $60A} — or applies immediately for Knuckles
     * ({@code loc_543C2}).
     */
    private void updateAct2EntryLayout() {
        LbzZoneRuntimeState state = S3kRuntimeStates.currentLbz(zoneRuntimeRegistry()).orElse(null);
        if (state == null) {
            return;
        }
        if (!state.isLbz2LayoutAdjustApplied()) {
            applyLbz2TransitionLayout();
            applyLbz2EntryCorridorMod();
            state.setLbz2LayoutAdjustApplied(true);
            state.setLbz2EntryCorridorApplied(true);
            return;
        }
        if (state.isLbz2EntryCorridorApplied()) {
            return;
        }
        AbstractPlayableSprite player = camera().getFocusedSprite();
        boolean gateReached = playerCharacter() == PlayerCharacter.KNUCKLES
                || (player != null && (player.getCentreX() & 0xFFFF) >= LBZ2_ENTRY_CORRIDOR_GATE_X);
        if (gateReached) {
            applyLbz2EntryCorridorMod();
            state.setLbz2EntryCorridorApplied(true);
        }
    }

    private void updateAct2DeathEggTerrainSwap() {
        LbzZoneRuntimeState state = S3kRuntimeStates.currentLbz(zoneRuntimeRegistry()).orElse(null);
        if (state == null || state.isDeathEggTerrainSwapApplied()) {
            return;
        }
        Camera camera = camera();
        if ((camera.getX() & 0xFFFF) < LBZ2_DEATH_EGG_SWAP_CAMERA_X
                || (camera.getY() & 0xFFFF) < LBZ2_DEATH_EGG_SWAP_CAMERA_Y) {
            return;
        }
        if (!state.isDeathEggTerrainSwapQueued()) {
            state.setDeathEggTerrainSwapQueued(true);
            queueDeathEggTerrainResources();
        }
        applyDeathEggTerrainSwapHook(state);
    }

    private void applyDeathEggTerrainSwapHook(LbzZoneRuntimeState state) {
        Level level = levelManager().getCurrentLevel();
        if (level == null) {
            return;
        }
        rebindDeathEggTerrainKosAfterRewind();
        if (deathEggTerrainDirectQueue == null
                || deathEggTerrainModuleQueue == null
                || deathEggBlocksHandle == null
                || deathEggChunksHandle == null
                || deathEggTerrainArtHandle == null
                || !deathEggTerrainDirectQueue.isReady(deathEggBlocksHandle)
                || !deathEggTerrainDirectQueue.isReady(deathEggChunksHandle)
                || !deathEggTerrainModuleQueue.isReady(deathEggTerrainArtHandle)) {
            return;
        }

        byte[] blocks16x16 = deathEggTerrainDirectQueue.claim(deathEggBlocksHandle);
        byte[] chunks128x128 = deathEggTerrainDirectQueue.claim(deathEggChunksHandle);
        byte[] terrainArt = deathEggTerrainModuleQueue.claim(deathEggTerrainArtHandle);
        validateResourceSize("LBZ2_16x16_DeathEgg_Kos",
                blocks16x16, Sonic3kConstants.LBZ2_16X16_DEATH_EGG_OUTPUT_SIZE);
        validateResourceSize("LBZ2_128x128_DeathEgg_Kos",
                chunks128x128, Sonic3kConstants.LBZ2_128X128_DEATH_EGG_OUTPUT_SIZE);
        validateResourceSize("LBZ2_8x8_DeathEgg_KosM",
                terrainArt, Sonic3kConstants.LBZ2_8X8_DEATH_EGG_OUTPUT_SIZE);

        LayoutMutationContext context = new LayoutMutationContext(
                LevelMutationSurface.forLevel(level),
                levelManager()::applyMutationEffects);
        zoneLayoutMutationPipeline().applyImmediately(
                mutationContext -> applyDeathEggTerrainResources(
                        mutationContext, blocks16x16, chunks128x128, terrainArt),
                context);
        state.setDeathEggTerrainSwapApplied(true);
        clearDeathEggTerrainKosOwnership();
    }

    private void queueDeathEggTerrainResources() {
        try {
            Rom rom = rom();
            deathEggTerrainDirectQueue = directKosQueue();
            deathEggTerrainModuleQueue = moduleKosQueue();
            deathEggBlocksHandle = deathEggTerrainDirectQueue.queueStandardKos(
                    rom,
                    Sonic3kConstants.LBZ2_16X16_DEATH_EGG_KOS_ADDR,
                    S3kKosRamDestinations.BLOCK_TABLE);
            deathEggBlocksOrdinal = deathEggBlocksHandle.ordinal();
            deathEggChunksHandle = deathEggTerrainDirectQueue.queueStandardKos(
                    rom,
                    Sonic3kConstants.LBZ2_128X128_DEATH_EGG_KOS_ADDR,
                    S3kKosRamDestinations.RAM_START);
            deathEggChunksOrdinal = deathEggChunksHandle.ordinal();
            deathEggTerrainArtHandle = deathEggTerrainModuleQueue.queue(
                    rom,
                    Sonic3kConstants.LBZ2_8X8_DEATH_EGG_KOSM_ADDR,
                    Sonic3kConstants.LBZ2_8X8_DEATH_EGG_DEST_TILE);
            deathEggTerrainArtOrdinal = deathEggTerrainArtHandle.ordinal();
            // LBZ2_Resize queues the one-module Death Egg 2 art immediately
            // after the terrain KosM parent, so it remains behind that parent
            // in the native module FIFO (sonic3k.asm:39529-39543).
            queueDeathEggLaunchArt();
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Unable to queue LBZ2 Death Egg terrain resources", e);
        }
    }

    private void rebindDeathEggTerrainKosAfterRewind() {
        if (deathEggTerrainArtOrdinal < 0 || deathEggTerrainModuleQueue != null) {
            return;
        }
        var timing = hardwareTiming();
        deathEggBlocksHandle = timing.pendingHandle(
                        HardwareWorkKind.KOS_DECOMPRESSION_QUEUE, deathEggBlocksOrdinal)
                .orElseThrow(() -> new IllegalStateException(
                        "restored LBZ Death Egg terrain owner cannot find block Kos ordinal "
                                + deathEggBlocksOrdinal));
        deathEggChunksHandle = timing.pendingHandle(
                        HardwareWorkKind.KOS_DECOMPRESSION_QUEUE, deathEggChunksOrdinal)
                .orElseThrow(() -> new IllegalStateException(
                        "restored LBZ Death Egg terrain owner cannot find chunk Kos ordinal "
                                + deathEggChunksOrdinal));
        deathEggTerrainArtHandle = timing.pendingHandle(
                        HardwareWorkKind.KOS_MODULE_QUEUE, deathEggTerrainArtOrdinal)
                .orElseThrow(() -> new IllegalStateException(
                        "restored LBZ Death Egg terrain owner cannot find art KosM ordinal "
                                + deathEggTerrainArtOrdinal));
        deathEggTerrainDirectQueue = directKosQueue();
        deathEggTerrainModuleQueue = moduleKosQueue();
    }

    private void clearDeathEggTerrainKosOwnership() {
        deathEggTerrainDirectQueue = null;
        deathEggTerrainModuleQueue = null;
        deathEggBlocksHandle = null;
        deathEggChunksHandle = null;
        deathEggTerrainArtHandle = null;
        deathEggBlocksOrdinal = -1;
        deathEggChunksOrdinal = -1;
        deathEggTerrainArtOrdinal = -1;
    }

    private void refreshLbzKnuxPillarSheet(Level level) {
        if (levelManager() == null
                || levelManager().getObjectRenderManager() == null
                || !(levelManager().getObjectRenderManager().getArtProvider()
                        instanceof Sonic3kObjectArtProvider provider)) {
            return;
        }
        try {
            Sonic3kObjectArt art = new Sonic3kObjectArt(level, RomByteReader.fromRom(rom()));
            var freshSheet = art.buildLevelArtSheetFromRom(
                    Sonic3kConstants.MAP_LBZ_KNUX_PILLAR_ADDR,
                    Sonic3kConstants.ART_TILE_LBZ_KNUX_PILLAR,
                    2);
            provider.refreshSheetPatterns(Sonic3kObjectArtKeys.LBZ_KNUX_PILLAR, freshSheet);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to refresh LBZ Knuckles pillar art from S3K ROM", ex);
        }
    }

    private MutationEffects applyDeathEggTerrainResources(LayoutMutationContext context,
                                                          byte[] blocks16x16,
                                                          byte[] chunks128x128,
                                                          byte[] terrainArt) {
        MutationEffects effects = MutationEffects.NONE;
        effects = mergeEffects(effects, copyDeathEgg16x16Blocks(context, blocks16x16));
        effects = mergeEffects(effects, copyDeathEgg128x128Chunks(context, chunks128x128));
        effects = mergeEffects(effects, copyArtToPatternTile(
                context, terrainArt, Sonic3kConstants.LBZ2_8X8_DEATH_EGG_DEST_TILE));
        return mergeEffects(effects, MutationEffects.redrawAllTilemaps());
    }

    private MutationEffects copyDeathEgg16x16Blocks(LayoutMutationContext context, byte[] blocks16x16) {
        Level level = levelManager() != null ? levelManager().getCurrentLevel() : null;
        if (level == null) {
            return MutationEffects.NONE;
        }
        LevelMutationSurface surface = context.surface();
        MutationEffects effects = MutationEffects.NONE;
        int startChunkIndex = Sonic3kConstants.LBZ2_16X16_DEATH_EGG_DEST_BLOCK;
        int chunkCount = blocks16x16.length / Chunk.CHUNK_SIZE_IN_ROM;
        ensureChunkCapacity(level, startChunkIndex + chunkCount);
        for (int i = 0; i < chunkCount; i++) {
            int chunkIndex = startChunkIndex + i;
            int srcOffset = i * Chunk.CHUNK_SIZE_IN_ROM;
            int[] state = new int[Chunk.PATTERNS_PER_CHUNK + 2];
            for (int pattern = 0; pattern < Chunk.PATTERNS_PER_CHUNK; pattern++) {
                state[pattern] = readWord(blocks16x16, srcOffset + pattern * 2);
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

    private static void ensureChunkCapacity(Level level, int minChunkCount) {
        if (level.getChunkCount() >= minChunkCount || !(level instanceof AbstractLevel abstractLevel)) {
            return;
        }
        Chunk[] chunks = Arrays.copyOf(abstractLevel.chunksReference(), minChunkCount);
        for (int i = level.getChunkCount(); i < minChunkCount; i++) {
            chunks[i] = new Chunk();
        }
        abstractLevel.replaceChunks(chunks);
    }

    private MutationEffects copyDeathEgg128x128Chunks(LayoutMutationContext context, byte[] chunks128x128) {
        LevelMutationSurface surface = context.surface();
        MutationEffects effects = MutationEffects.NONE;
        int startBlockIndex = Sonic3kConstants.LBZ2_128X128_DEATH_EGG_DEST_CHUNK;
        int blockCount = chunks128x128.length / LevelConstants.BLOCK_SIZE_IN_ROM;
        int chunksPerBlock = LevelConstants.BLOCK_SIZE_IN_ROM / 2;
        for (int i = 0; i < blockCount; i++) {
            int blockIndex = startBlockIndex + i;
            int srcOffset = i * LevelConstants.BLOCK_SIZE_IN_ROM;
            int[] state = new int[chunksPerBlock];
            for (int chunk = 0; chunk < chunksPerBlock; chunk++) {
                state[chunk] = readWord(chunks128x128, srcOffset + chunk * 2);
            }
            effects = mergeEffects(effects, surface.restoreBlockState(blockIndex, state));
        }
        return effects;
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

    private static void validateResourceSize(String name, byte[] resource, int expectedSize) {
        if (resource.length != expectedSize) {
            throw new IllegalStateException(String.format(
                    "%s decompressed to 0x%X bytes, expected 0x%X",
                    name, resource.length, expectedSize));
        }
    }

    private void updateAct2Launch(int frameCounter) {
        LbzZoneRuntimeState state = S3kRuntimeStates.currentLbz(zoneRuntimeRegistry()).orElse(null);
        if (state == null) {
            return;
        }
        serviceDeathEggLaunchArt();
        if (state.consumeLaunchStartRequested()) {
            startLaunch(state);
        }
        if (state.consumePadCollapseStartRequested()) {
            state.setPadCollapseActive(true);
            state.setLaunchFallingAccelActive(true);
            state.setWaterDisabled(false);
            state.setDetachScroll(0);
            state.setDetachWindowCopyTimer(0);
            lbz2CopiedWindowActive = false;
            lbz2CopiedWindowScreenX = 0;
            lbz2CopiedWindowScreenY = 0;
            if (lbz2CopiedWindowDescriptors == null) {
                lbz2CopiedWindowDescriptors = captureLaunchPadVisualTilemap();
            }
        }
        consumeFinalFallIfDetachFinished(state);
        if (state.isFinalFallActive()) {
            applyFinalFallFrame();
            return;
        }
        if (!state.isLaunchActive()) {
            return;
        }
        updateDeathEggRumble(state, frameCounter);
        // ROM sub_545DA: LBZ2_EndFallingAccel runs every frame from the
        // pad-collapse signal onward (through detach and beyond).
        if (state.isLaunchFallingAccelActive()) {
            updateLaunchFallingAccel(state);
            if (!state.isWaterDisabled() && state.getBgLaunchSpeed() < 0) {
                state.setWaterDisabled(true);
                state.setDetachWindowCopyTimer(LBZ2_PAD_WINDOW_COPY_FRAMES);
                captureLbz2CopiedWindowPlatform();
            }
        }
        updateLaunchMotion(state);
        if (state.isPadCollapseActive()) {
            updatePadCollapse(state, frameCounter);
        }
        if (consumeFinalFallIfDetachFinished(state)) {
            applyFinalFallFrame();
        }
    }

    private boolean consumeFinalFallIfDetachFinished(LbzZoneRuntimeState state) {
        if (state.isPadCollapseActive()
                || !state.isWaterDisabled()
                || state.getDetachWindowCopyTimer() > 0
                || !state.consumeFinalFallRequested()) {
            return false;
        }
        state.setFinalFallActive(true);
        return true;
    }

    private void applyFinalFallFrame() {
        // ROM LBZ2BGE_Falling: Scroll_lock + camera.y -= 2; the launch
        // motion/deform no longer runs once Events_fg_5 (3rd use) is set.
        Camera camera = camera();
        camera.setFrozen(true);
        camera.captureRenderCopy();
        camera.setYAfterRenderCopy((short) ((camera.getY() & 0xFFFF) + LBZ2_FINAL_FALL_CAMERA_DY));
    }

    private void startLaunch(LbzZoneRuntimeState state) {
        clearLbz2CopiedWindowPlatform();
        state.setLaunchActive(true);
        state.setDeathEggRumble(true);
        state.setPreLaunchDelay(LBZ2_LAUNCH_PRE_DELAY);
        state.setFgLaunchSpeed(LBZ2_LAUNCH_FG_SPEED);
        state.setBgLaunchSpeed(LBZ2_LAUNCH_BG_SPEED);
        // ROM loc_54A74: the per-frame deltas accumulate onto the existing
        // Target_water_level, not the camera position.
        int initialWaterTarget = 0;
        try {
            initialWaterTarget = waterSystem().getWaterLevelTarget(Sonic3kZoneIds.ZONE_LBZ, 1) & 0xFFFF;
        } catch (RuntimeException ignored) {
            // Unit-test contexts may not have an LBZ water configuration.
        }
        if (initialWaterTarget == 0) {
            initialWaterTarget = camera().getY() & 0xFFFF;
        }
        state.setWaterTargetY(initialWaterTarget);
        gameState().setScreenShakeActive(true);
        Camera camera = camera();
        camera.setMaxX((short) LBZ2_LAUNCH_CAMERA_MAX_X);
        camera.setMaxY((short) LBZ2_LAUNCH_CAMERA_MAX_Y);
        camera.setMaxYTarget((short) LBZ2_LAUNCH_CAMERA_MAX_Y);
        camera.setFrozen(true);
        applyDeathEggSmallBackgroundReframe();
    }

    private void queueDeathEggLaunchArt() {
        if (deathEggLaunchArtOrdinal >= 0 || deathEggLaunchArtHandle != null) {
            return;
        }
        try {
            deathEggLaunchArtQueue = moduleKosQueue();
            deathEggLaunchArtHandle = deathEggLaunchArtQueue.queue(
                    rom(),
                    Sonic3kConstants.ART_KOSM_LBZ2_DEATH_EGG_2_8X8_ADDR,
                    Sonic3kConstants.ART_TILE_LBZ2_DEATH_EGG_2);
            deathEggLaunchArtOrdinal = deathEggLaunchArtHandle.ordinal();
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Unable to queue LBZ2 Death Egg launch art", e);
        }
    }

    private void serviceDeathEggLaunchArt() {
        if (deathEggLaunchArtOrdinal < 0 && deathEggLaunchArtHandle == null) {
            return;
        }
        rebindDeathEggLaunchArtAfterRewind();
        if (deathEggLaunchArtQueue == null
                || deathEggLaunchArtHandle == null
                || !deathEggLaunchArtQueue.isReady(deathEggLaunchArtHandle)) {
            return;
        }
        Level level = levelManager().getCurrentLevel();
        if (level == null) {
            return;
        }
        byte[] art = deathEggLaunchArtQueue.claim(deathEggLaunchArtHandle);
        validateResourceSize("ArtKosM_LBZ2DeathEgg2_8x8",
                art, Sonic3kConstants.ART_KOSM_LBZ2_DEATH_EGG_2_8X8_OUTPUT_SIZE);
        LayoutMutationContext context = new LayoutMutationContext(
                LevelMutationSurface.forLevel(level),
                levelManager()::applyMutationEffects);
        zoneLayoutMutationPipeline().applyImmediately(
                mutationContext -> mergeEffects(
                        copyArtToPatternTile(
                                mutationContext,
                                art,
                                Sonic3kConstants.ART_TILE_LBZ2_DEATH_EGG_2),
                        MutationEffects.redrawAllTilemaps()),
                context);
        refreshLbzKnuxPillarSheet(level);
        clearDeathEggLaunchArtOwnership();
    }

    private void rebindDeathEggLaunchArtAfterRewind() {
        if (deathEggLaunchArtOrdinal < 0 || deathEggLaunchArtQueue != null) {
            return;
        }
        deathEggLaunchArtHandle = hardwareTiming().pendingHandle(
                HardwareWorkKind.KOS_MODULE_QUEUE,
                deathEggLaunchArtOrdinal).orElseThrow(() -> new IllegalStateException(
                        "restored LBZ Death Egg launch owner cannot find KosM ordinal "
                                + deathEggLaunchArtOrdinal));
        deathEggLaunchArtQueue = moduleKosQueue();
    }

    private void clearDeathEggLaunchArtOwnership() {
        deathEggLaunchArtQueue = null;
        deathEggLaunchArtHandle = null;
        deathEggLaunchArtOrdinal = -1;
    }

    private void applyDeathEggSmallBackgroundReframe() {
        Level level = levelManager().getCurrentLevel();
        if (level == null || level.getMap() == null) {
            return;
        }
        Map map = level.getMap();
        LayoutMutationContext context = new LayoutMutationContext(
                LevelMutationSurface.forLevel(level),
                levelManager()::applyMutationEffects);
        zoneLayoutMutationPipeline().applyImmediately(
                mutationContext -> {
                    LevelMutationSurface surface = mutationContext.surface();
                    MutationEffects combined = copyBackgroundRow(
                            map,
                            surface,
                            LBZ2_DEATH_EGG_BG_REFRAME_SOURCE_ROW_0,
                            LBZ2_DEATH_EGG_BG_REFRAME_DEST_ROW_0);
                    return mergeEffects(combined, copyBackgroundRow(
                            map,
                            surface,
                            LBZ2_DEATH_EGG_BG_REFRAME_SOURCE_ROW_1,
                            LBZ2_DEATH_EGG_BG_REFRAME_DEST_ROW_1));
                },
                context);
    }

    private static MutationEffects copyBackgroundRow(Map map,
                                                     LevelMutationSurface surface,
                                                     int sourceRow,
                                                     int destRow) {
        if (sourceRow >= map.getHeight() || destRow >= map.getHeight()) {
            return MutationEffects.NONE;
        }
        MutationEffects combined = MutationEffects.NONE;
        for (int col = 0; col < map.getWidth(); col++) {
            int blockIndex = map.getValue(BG_LAYER, col, sourceRow) & 0xFF;
            combined = mergeEffects(combined, surface.setBlockInMap(BG_LAYER, col, destRow, blockIndex));
        }
        return combined;
    }

    private void updateLaunchMotion(LbzZoneRuntimeState state) {
        AbstractPlayableSprite player = camera().getFocusedSprite();
        if (player != null && player.getDead()) {
            state.setLaunchYDelta(0);
            return;
        }
        Camera camera = camera();
        if (camera.getFrozen() && player != null) {
            int targetCameraX = (player.getCentreX() & 0xFFFF) - 0xA0;
            int dx = Math.max(0, targetCameraX - (camera.getX() & 0xFFFF));
            if (dx > 0) {
                camera.setX((short) ((camera.getX() & 0xFFFF) + dx));
            }
        }
        if ((camera.getX() & 0xFFFF) >= LBZ2_LAUNCH_CAMERA_MAX_X
                || (camera.getY() & 0xFFFF) >= LBZ2_LAUNCH_CAMERA_MAX_Y) {
            camera.setFrozen(false);
        }
        if (state.getPreLaunchDelay() > 0) {
            state.setPreLaunchDelay(state.getPreLaunchDelay() - 1);
            state.setLaunchYDelta(0);
            return;
        }
        if ((camera.getX() & 0xFFFF) >= LBZ2_LAUNCH_CAMERA_MAX_X) {
            camera.setMinX(camera.getMaxX());
        }
        if ((camera.getY() & 0xFFFF) >= LBZ2_LAUNCH_CAMERA_MAX_Y) {
            camera.setMinY(camera.getMaxY());
        }

        int oldFgHigh = state.getFgAccum() >> 16;
        int fgAccum = state.getFgAccum() + state.getFgLaunchSpeed();
        state.setFgAccum(fgAccum);
        int fgDelta = (fgAccum >> 16) - oldFgHigh;

        int oldBgHigh = state.getBgAccum() >> 16;
        int bgStep = camera.getFrozen() ? LBZ2_LAUNCH_BG_SCROLL_LOCK_SPEED : state.getBgLaunchSpeed();
        int bgAccum = state.getBgAccum() + bgStep;
        state.setBgAccum(bgAccum);
        int bgDelta = (bgAccum >> 16) - oldBgHigh;

        if (camera.getFrozen()) {
            // ROM loc_54A74 prelude: while Scroll_lock the FG delta moves the
            // screen AND the registered rider object (the hang-ride ship).
            if (fgDelta != 0) {
                camera.setY((short) ((camera.getY() & 0xFFFF) + fgDelta));
            }
            state.setLaunchRiderDelta(fgDelta);
        } else {
            state.setLaunchRiderDelta(0);
        }
        int combinedDelta = fgDelta + bgDelta;
        int waterTarget = state.getWaterTargetY() + combinedDelta;
        if (waterTarget >= LBZ2_WATER_DRAIN_LIMIT) {
            waterTarget = LBZ2_WATER_DRAIN_CLAMP;
        }
        state.setWaterTargetY(waterTarget);
        try {
            waterSystem().setWaterLevelTarget(Sonic3kZoneIds.ZONE_LBZ, 1, state.getWaterTargetY());
        } catch (RuntimeException ignored) {
            // Unit-test contexts may not have an LBZ water configuration.
        }
        state.setLaunchYDelta(combinedDelta);
    }

    private static void updateLaunchFallingAccel(LbzZoneRuntimeState state) {
        if (state.getFgLaunchSpeed() > 0) {
            state.setFgLaunchSpeed(state.getFgLaunchSpeed() - LBZ2_LAUNCH_DECEL);
        } else if (state.getBgLaunchSpeed() > LBZ2_LAUNCH_BG_MIN_FALL_SPEED) {
            state.setBgLaunchSpeed(state.getBgLaunchSpeed() - LBZ2_LAUNCH_DECEL);
        }
    }

    private void updateDeathEggRumble(LbzZoneRuntimeState state, int frameCounter) {
        if (!state.isDeathEggRumble()) {
            return;
        }
        if (!gameState().isScreenShakeActive()) {
            state.setDeathEggRumble(false);
            return;
        }
        requestScreenShakeOffset(frameCounter);
        if (((frameCounter - 1) & 0x0F) == 0) {
            audio().playSfx(Sonic3kSfx.DEATH_EGG_RISE_LOUD.id);
        }
    }

    private void updatePadCollapse(LbzZoneRuntimeState state, int frameCounter) {
        if (!state.isWaterDisabled()) {
            return;
        }
        if (state.getDetachWindowCopyTimer() > 0) {
            state.setDetachWindowCopyTimer(state.getDetachWindowCopyTimer() - 1);
            if (state.getDetachWindowCopyTimer() == 0) {
                activateLbz2CopiedWindowPlatform();
            }
            return;
        }
        if ((frameCounter & 3) != 0) {
            return;
        }
        int detachScroll = state.getDetachScroll();
        if (detachScroll < LBZ2_PAD_DETACH_SCROLL_DONE) {
            state.setDetachScroll(detachScroll + 1);
            return;
        }
        state.setDetachScroll(0);
        state.setPadCollapseActive(false);
        clearLaunchPadTerrain();
        lbz2CopiedWindowActive = false;
    }

    private void clearLaunchPadTerrain() {
        Level level = levelManager().getCurrentLevel();
        if (level == null || level.getMap() == null) {
            return;
        }
        if (lbz2CopiedWindowDescriptors == null) {
            captureLbz2CopiedWindowPlatform();
        }
        LayoutMutationContext context = new LayoutMutationContext(
                LevelMutationSurface.forLevel(level),
                levelManager()::applyMutationEffects);
        zoneLayoutMutationPipeline().applyImmediatelyWithoutRedraw(
                mutationContext -> {
                    MutationEffects combined = MutationEffects.NONE;
                    LevelMutationSurface surface = mutationContext.surface();
                    for (int row = 0; row < LBZ2_PAD_CLEAR_HEIGHT; row++) {
                        for (int col = 0; col < LBZ2_PAD_CLEAR_WIDTH; col++) {
                            combined = mergeEffects(combined, surface.setBlockInMap(
                                    FG_LAYER,
                                    LBZ2_PAD_CLEAR_X + col,
                                    LBZ2_PAD_CLEAR_Y + row,
                                    0));
                        }
                    }
                    return combined;
                },
                context);
    }

    public void renderLbz2CopiedWindowPlatform(Camera camera) {
        if (camera == null || !lbz2CopiedWindowActive || lbz2CopiedWindowDescriptors == null) {
            return;
        }
        int tileColumns = LBZ2_PAD_CLEAR_WIDTH * LevelConstants.BLOCK_WIDTH / Pattern.PATTERN_WIDTH;
        int tileRows = LBZ2_PAD_CLEAR_HEIGHT * LevelConstants.BLOCK_HEIGHT / Pattern.PATTERN_HEIGHT;
        int index = 0;
        for (int row = 0; row < tileRows; row++) {
            int worldY = getLbz2CopiedWindowRenderWorldYForTest(camera, row);
            for (int col = 0; col < tileColumns; col++) {
                int descriptor = lbz2CopiedWindowDescriptors[index++];
                if (descriptor == 0) {
                    continue;
                }
                int worldX = getLbz2CopiedWindowRenderWorldXForTest(camera, col);
                int patternIndex = descriptor & 0x7FF;
                lbz2CopiedWindowPatternDesc.set(descriptor);
                graphics().renderPatternWithId(
                        patternIndex, lbz2CopiedWindowPatternDesc, worldX, worldY);
            }
        }
    }

    public int getLbz2CopiedWindowDescriptorForTest(int worldX, int worldY) {
        if (lbz2CopiedWindowDescriptors == null) {
            return 0;
        }
        int originX = LBZ2_PAD_CLEAR_X * LevelConstants.BLOCK_WIDTH;
        int originY = LBZ2_PAD_CLEAR_Y * LevelConstants.BLOCK_HEIGHT;
        int localX = worldX - originX;
        int localY = worldY - originY;
        int width = LBZ2_PAD_CLEAR_WIDTH * LevelConstants.BLOCK_WIDTH;
        int height = LBZ2_PAD_CLEAR_HEIGHT * LevelConstants.BLOCK_HEIGHT;
        if (localX < 0 || localY < 0 || localX >= width || localY >= height) {
            return 0;
        }
        int tileColumns = width / Pattern.PATTERN_WIDTH;
        int col = localX / Pattern.PATTERN_WIDTH;
        int row = localY / Pattern.PATTERN_HEIGHT;
        return lbz2CopiedWindowDescriptors[row * tileColumns + col];
    }

    int getLbz2CopiedWindowRenderWorldXForTest(Camera camera, int tileColumn) {
        return (camera.getX() & 0xFFFF) + lbz2CopiedWindowScreenX + tileColumn * Pattern.PATTERN_WIDTH;
    }

    int getLbz2CopiedWindowRenderWorldYForTest(Camera camera, int tileRow) {
        // ROM LBZ2BGE_PlatformDetach adds Events_bg+$16 to Scroll A's V-scroll
        // (SwScrlLbz applies the same +detachScroll to the FG plane). The copied
        // Death Egg band must ride that scroll up off the top of the screen in
        // lockstep with the rest of Scroll A, otherwise it stays pinned for the
        // ~$28 detach window and then pops out when the overlay is dropped.
        int detachScroll = currentLbzRuntimeState()
                .map(LbzZoneRuntimeState::getDetachScroll)
                .orElse(0);
        return (camera.getY() & 0xFFFF) + lbz2CopiedWindowScreenY + tileRow * Pattern.PATTERN_HEIGHT
                - detachScroll;
    }

    private void captureLbz2CopiedWindowPlatform() {
        if (lbz2CopiedWindowDescriptors == null) {
            lbz2CopiedWindowDescriptors = captureLaunchPadVisualTilemap();
        }
    }

    private void activateLbz2CopiedWindowPlatform() {
        captureLbz2CopiedWindowPlatform();
        lbz2CopiedWindowScreenX = LBZ2_PAD_CLEAR_X * LevelConstants.BLOCK_WIDTH - (camera().getX() & 0xFFFF);
        lbz2CopiedWindowScreenY = LBZ2_PAD_CLEAR_Y * LevelConstants.BLOCK_HEIGHT - (camera().getY() & 0xFFFF);
        clearLbz2ScrollAPlatformTilemap();
        lbz2CopiedWindowActive = true;
    }

    boolean isLbz2CopiedWindowActiveForTest() {
        return lbz2CopiedWindowActive;
    }

    private void clearLbz2CopiedWindowPlatform() {
        lbz2CopiedWindowDescriptors = null;
        lbz2CopiedWindowScreenX = 0;
        lbz2CopiedWindowScreenY = 0;
        lbz2CopiedWindowActive = false;
    }

    private void clearLbz2ScrollAPlatformTilemap() {
        int width = LBZ2_PAD_CLEAR_WIDTH * LevelConstants.BLOCK_WIDTH;
        int height = LBZ2_PAD_CLEAR_HEIGHT * LevelConstants.BLOCK_HEIGHT;
        int originX = LBZ2_PAD_CLEAR_X * LevelConstants.BLOCK_WIDTH;
        int originY = LBZ2_PAD_CLEAR_Y * LevelConstants.BLOCK_HEIGHT;
        boolean changed = false;
        for (int y = 0; y < height; y += Pattern.PATTERN_HEIGHT) {
            for (int x = 0; x < width; x += Pattern.PATTERN_WIDTH) {
                changed |= levelManager().setForegroundTileDescriptorAtWorld(originX + x, originY + y, 0);
            }
        }
        if (changed) {
            levelManager().uploadForegroundTilemap();
        }
    }

    private int[] captureLaunchPadVisualTilemap() {
        int tileColumns = LBZ2_PAD_CLEAR_WIDTH * LevelConstants.BLOCK_WIDTH / Pattern.PATTERN_WIDTH;
        int tileRows = LBZ2_PAD_CLEAR_HEIGHT * LevelConstants.BLOCK_HEIGHT / Pattern.PATTERN_HEIGHT;
        int[] descriptors = new int[tileColumns * tileRows];
        int originX = LBZ2_PAD_CLEAR_X * LevelConstants.BLOCK_WIDTH;
        int originY = LBZ2_PAD_CLEAR_Y * LevelConstants.BLOCK_HEIGHT;
        int index = 0;
        for (int row = 0; row < tileRows; row++) {
            int worldY = originY + row * Pattern.PATTERN_HEIGHT;
            for (int col = 0; col < tileColumns; col++) {
                int worldX = originX + col * Pattern.PATTERN_WIDTH;
                descriptors[index++] = levelManager().getForegroundTileDescriptorAtWorld(worldX, worldY);
            }
        }
        return descriptors;
    }

    /**
     * ROM: {@code Adjust_LBZ2Layout} — fixes up the LBZ2 layout around the
     * act-transition seam: moves the chunk at FG (5,18) down a row and replaces
     * it with chunk $DB, rotates chunk $DB's chunk-table definition by 24
     * entries ({@code LBZ1_RotateChunks}), copies FG (4,19) across (4..6,20),
     * and writes chunks $58/$55 at FG ($8A,7)/($8A,8).
     */
    public void applyLbz2TransitionLayout() {
        if (!hasRuntime()) {
            return;
        }
        Level level = levelManager().getCurrentLevel();
        if (level == null || level.getMap() == null) {
            return;
        }
        Map map = level.getMap();
        LayoutMutationContext context = new LayoutMutationContext(
                LevelMutationSurface.forLevel(level),
                levelManager()::applyMutationEffects);
        zoneLayoutMutationPipeline().applyImmediately(
                mutationContext -> {
                    LevelMutationSurface surface = mutationContext.surface();
                    MutationEffects combined = MutationEffects.NONE;
                    int seamChunk = map.getValue(FG_LAYER, 5, 18) & 0xFF;
                    combined = mergeEffects(combined, surface.setBlockInMap(FG_LAYER, 5, 19, seamChunk));
                    combined = mergeEffects(combined,
                            surface.setBlockInMap(FG_LAYER, 5, 18, LBZ2_ADJUST_ROTATED_BLOCK));
                    combined = mergeEffects(combined, rotateBlockDefinition(
                            level, LBZ2_ADJUST_ROTATED_BLOCK, LBZ2_ADJUST_ROTATE_BY));
                    int floorChunk = map.getValue(FG_LAYER, 4, 19) & 0xFF;
                    for (int x = 4; x <= 6; x++) {
                        combined = mergeEffects(combined,
                                surface.setBlockInMap(FG_LAYER, x, 20, floorChunk));
                    }
                    combined = mergeEffects(combined, surface.setBlockInMap(FG_LAYER, 0x8A, 7, 0x58));
                    combined = mergeEffects(combined, surface.setBlockInMap(FG_LAYER, 0x8A, 8, 0x55));
                    return combined;
                },
                context);
    }

    /**
     * ROM: {@code LBZ2_LayoutMod} — copies the 6x6 staging area at FG columns
     * $94.. into columns 6.. for rows 0-5, opening the act-2 entry corridor.
     */
    public void applyLbz2EntryCorridorMod() {
        if (!hasRuntime()) {
            return;
        }
        applyLayoutCopy(new CopySpec(
                LBZ2_CORRIDOR_SRC_X, 0, LBZ2_CORRIDOR_DEST_X, 0,
                LBZ2_CORRIDOR_SIZE, LBZ2_CORRIDOR_SIZE));
    }

    /**
     * ROM: {@code LBZ1_RotateChunks} rotates the 64-word chunk-table definition
     * of chunk {@code $DB} right by 24 entries (the chunk graphics shift down
     * three 16x16 rows). Routed through {@link Sonic3kLevel} so copy-on-write
     * rewind isolation is preserved.
     */
    private static MutationEffects rotateBlockDefinition(Level level, int blockIndex, int rotateBy) {
        if (!(level instanceof Sonic3kLevel sonic3kLevel)) {
            return MutationEffects.NONE;
        }
        sonic3kLevel.rotateBlockChunkDescs(blockIndex, rotateBy);
        return MutationEffects.redrawAllTilemaps();
    }

    private static int offsetCameraBoundWord(short value, int offset) {
        return ((value & 0xFFFF) + offset) & 0xFFFF;
    }

    private void requestScreenShakeOffset(int frameCounter) {
        if (!hasRuntime()) {
            return;
        }
        S3kRuntimeStates.currentLbz(zoneRuntimeRegistry())
                .ifPresent(state -> state.requestScreenShakeOffset(
                        SCREEN_SHAKE_CONTINUOUS[frameCounter & 0x3F]));
    }

    private Optional<LbzZoneRuntimeState> currentLbzRuntimeState() {
        if (!hasRuntime()) {
            return Optional.empty();
        }
        return S3kRuntimeStates.currentLbz(zoneRuntimeRegistry());
    }

    private static LayoutMod modById(int id) {
        for (LayoutMod mod : LBZ1_LAYOUT_MODS) {
            if (mod.id() == id) {
                return mod;
            }
        }
        return null;
    }

    private void applyLayoutCopy(CopySpec spec) {
        Level level = levelManager().getCurrentLevel();
        if (level == null || level.getMap() == null) {
            return;
        }
        LayoutMutationContext context = new LayoutMutationContext(
                LevelMutationSurface.forLevel(level),
                levelManager()::applyMutationEffects);
        zoneLayoutMutationPipeline().applyImmediately(
                mutationContext -> copyRect(level.getMap(), mutationContext.surface(), spec),
                context);
    }

    private static MutationEffects copyRect(Map map, LevelMutationSurface surface, CopySpec spec) {
        MutationEffects combined = MutationEffects.NONE;
        for (int row = 0; row < spec.height(); row++) {
            for (int col = 0; col < spec.width(); col++) {
                int blockIndex = map.getValue(FG_LAYER, spec.sourceX() + col, spec.sourceY() + row) & 0xFF;
                combined = mergeEffects(combined, surface.setBlockInMap(
                        FG_LAYER,
                        spec.destX() + col,
                        spec.destY() + row,
                        blockIndex));
            }
        }
        return combined;
    }

    private static MutationEffects applyEndingLayout(Map map, LevelMutationSurface surface) {
        MutationEffects combined = MutationEffects.NONE;
        for (int row = 0; row < ENDING_CLEAR_HEIGHT; row++) {
            for (int col = 0; col < ENDING_CLEAR_WIDTH; col++) {
                combined = mergeEffects(combined, surface.setBlockInMap(
                        FG_LAYER,
                        ENDING_CLEAR_X + col,
                        ENDING_CLEAR_Y + row,
                        0));
            }
        }
        for (int row = 0; row < ENDING_COPY_HEIGHT; row++) {
            for (int col = 0; col < ENDING_COPY_WIDTH; col++) {
                int blockIndex = map.getValue(
                        FG_LAYER,
                        ENDING_COPY_SOURCE_X + col,
                        ENDING_COPY_SOURCE_Y + row) & 0xFF;
                combined = mergeEffects(combined, surface.setBlockInMap(
                        FG_LAYER,
                        ENDING_COPY_DEST_X + col,
                        ENDING_COPY_DEST_Y + row,
                        blockIndex));
            }
        }
        return combined;
    }

    private static MutationEffects mergeEffects(MutationEffects left, MutationEffects right) {
        if (left == null || left.isEmpty()) {
            return right != null ? right : MutationEffects.NONE;
        }
        if (right == null || right.isEmpty()) {
            return left;
        }
        BitSet dirtyPatterns = (BitSet) left.dirtyPatterns().clone();
        dirtyPatterns.or(right.dirtyPatterns());
        return new MutationEffects(
                dirtyPatterns,
                left.dirtyRegionProcessingRequired() || right.dirtyRegionProcessingRequired(),
                left.foregroundRedrawRequired() || right.foregroundRedrawRequired(),
                left.allTilemapsRedrawRequired() || right.allTilemapsRedrawRequired(),
                left.patternLookupRefreshRequired() || right.patternLookupRefreshRequired(),
                left.objectResyncRequired() || right.objectResyncRequired(),
                left.ringResyncRequired() || right.ringResyncRequired());
    }

    private static int readWord(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }

    private record LayoutMod(int id,
                             TriggerRange triggerRange,
                             ExitRange exitRange,
                             CopySpec revealCopy,
                             CopySpec coveredCopy,
                             boolean hasLowerRightCornerExclusion) {
        boolean matches(int playerX, int playerY) {
            if (!triggerRange.contains(playerX, playerY)) {
                return false;
            }
            return !hasLowerRightCornerExclusion
                    || playerX < 0x1580
                    || playerY < 0x0400;
        }
    }

    private record TriggerRange(int minX, int maxX, int minY, int maxY) {
        boolean contains(int x, int y) {
            return x >= minX && x <= maxX && y >= minY && y <= maxY;
        }
    }

    private record ExitRange(int minX, int maxX) {
        boolean contains(int x) {
            return x >= minX && x <= maxX;
        }
    }

    private record CopySpec(int sourceX, int sourceY, int destX, int destY, int width, int height) {
    }
}
