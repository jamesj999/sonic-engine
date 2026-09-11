package com.openggf.game.sonic3k.runtime;

import com.openggf.game.PlayerCharacter;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.OptionalInt;

public final class LbzZoneRuntimeState implements S3kZoneRuntimeState {
    private static final int LEGACY_CAPTURE_BYTES = 12;
    private static final int LAUNCH_EXTENSION_VERSION_1 = 1;
    private static final int LAUNCH_EXTENSION_VERSION_2 = 2;
    private static final int LAUNCH_EXTENSION_VERSION_3 = 3;
    private static final int LAUNCH_EXTENSION_VERSION_4 = 4;
    private static final int LAUNCH_EXTENSION_VERSION_5 = 5;
    private static final int LAUNCH_EXTENSION_VERSION = 6;
    private static final int LAUNCH_EXTENSION_BYTES_V1 = 34;
    private static final int LAUNCH_EXTENSION_BYTES_V2 = 41;
    private static final int LAUNCH_EXTENSION_BYTES_V3 = 49;
    private static final int LAUNCH_EXTENSION_BYTES_V4 = 53;
    private static final int LAUNCH_EXTENSION_BYTES_V5 = 65;
    private static final int LAUNCH_EXTENSION_BYTES = 69;
    private static final int LAUNCH_FLAG_ACTIVE = 1;
    private static final int LAUNCH_FLAG_DEATH_EGG_RUMBLE = 1 << 1;
    private static final int LAUNCH_FLAG_START_REQUESTED = 1 << 2;
    private static final int LAUNCH_FLAG_PAD_COLLAPSE_REQUESTED = 1 << 3;
    private static final int LAUNCH_FLAG_FINAL_FALL_REQUESTED = 1 << 4;
    private static final int LAUNCH_FLAG_RIDER_ANCHOR_REGISTERED = 1 << 5;
    private static final int LAUNCH_FLAG_LBZ2_RIDE_ANIMATED_TILE_GATE_ACTIVE = 1 << 6;
    private static final int LAUNCH_FLAG_PAD_COLLAPSE_ACTIVE = 1 << 7;
    private static final int LAUNCH_FLAG_FINAL_FALL_ACTIVE = 1 << 8;
    private static final int LAUNCH_FLAG_DEATH_EGG_TERRAIN_SWAP_QUEUED = 1 << 9;
    private static final int LAUNCH_FLAG_DEATH_EGG_TERRAIN_SWAP_APPLIED = 1 << 10;
    private static final int LAUNCH_FLAG_WATER_DISABLED = 1 << 11;
    private static final int LAUNCH_FLAG_FINALE_CUTSCENE_ANCHOR_REGISTERED = 1 << 12;
    private static final int LAUNCH_FLAG_FALLING_ACCEL_ACTIVE = 1 << 13;
    private static final int LAUNCH_FLAG_LBZ1_KNUX_CONTROL_LOCKED = 1 << 14;
    private static final int LAUNCH_FLAG_LBZ1_KNUX_BOMB_SHAKE_ACTIVE = 1 << 15;
    private static final int LAUNCH_FLAG_LBZ1_KNUX_BOUNDARY_PUBLISH_PENDING = 1 << 16;

    private final int actIndex;
    private final PlayerCharacter playerCharacter;
    private boolean alarmAnimationActive;
    private int activeInteriorLayoutMod;
    private boolean interiorLayoutMod3Disabled;
    private boolean robotnikIntroActive;
    private boolean lbz2LayoutAdjustApplied;
    private boolean lbz2EntryCorridorApplied;
    private int rollingDrumP1Angle;
    private int rollingDrumP2Angle;
    private int pendingScreenShakeOffset;
    private boolean launchActive;
    private boolean deathEggRumble;
    private int fgLaunchSpeed;
    private int bgLaunchSpeed;
    private int fgAccum;
    private int bgAccum;
    private int launchYDelta;
    private int preLaunchDelay;
    private int detachScroll;
    private int detachWindowCopyTimer;
    private int waterTargetY;
    private boolean launchStartRequested;
    private boolean padCollapseStartRequested;
    private boolean finalFallRequested;
    private boolean launchRiderAnchorRegistered;
    private boolean lbz2RideAnimatedTileGateActive;
    private boolean padCollapseActive;
    private boolean finalFallActive;
    private boolean deathEggTerrainSwapQueued;
    private boolean deathEggTerrainSwapApplied;
    private boolean waterDisabled;
    private boolean lbz1KnucklesCutsceneControlLocked;
    private boolean lbz1KnucklesBombShakeActive;
    private boolean lbz1KnucklesBoundaryPublishPending;
    private int launchRiderAnchorId;
    private int launchRiderDelta;
    private boolean launchFallingAccelActive;
    private int deathEggDeformWrapLatch;
    private boolean finaleCutsceneAnchorRegistered;
    private int finaleCutsceneAnchorId;
    private int lbz2WaterlinePhase;
    private int lbz2ScrollArtPhaseSource;
    private int publishedBgCameraX;

    public LbzZoneRuntimeState(int actIndex, PlayerCharacter playerCharacter) {
        this.actIndex = actIndex;
        this.playerCharacter = Objects.requireNonNull(playerCharacter, "playerCharacter");
    }

    @Override public int zoneIndex() { return Sonic3kZoneIds.ZONE_LBZ; }
    @Override public int actIndex() { return actIndex; }
    @Override public PlayerCharacter playerCharacter() { return playerCharacter; }
    @Override public int getDynamicResizeRoutine() { return 0; }
    @Override public boolean isActTransitionFlagActive() { return false; }
    @Override public boolean advancesOscillationOnSeamlessTransition() { return true; }

    public boolean isAlarmAnimationActive() {
        return alarmAnimationActive;
    }

    public void setAlarmAnimationActive(boolean alarmAnimationActive) {
        this.alarmAnimationActive = alarmAnimationActive;
    }

    public int getActiveInteriorLayoutMod() {
        return activeInteriorLayoutMod;
    }

    public void setActiveInteriorLayoutMod(int activeInteriorLayoutMod) {
        this.activeInteriorLayoutMod = Math.max(0, Math.min(4, activeInteriorLayoutMod));
    }

    public boolean isInteriorLayoutMod3Disabled() {
        return interiorLayoutMod3Disabled;
    }

    public void setInteriorLayoutMod3Disabled(boolean interiorLayoutMod3Disabled) {
        this.interiorLayoutMod3Disabled = interiorLayoutMod3Disabled;
        if (interiorLayoutMod3Disabled && activeInteriorLayoutMod == 3) {
            activeInteriorLayoutMod = 0;
        }
    }

    /**
     * ROM {@code _unkFAAB}: set by {@code Obj_LBZ1Robotnik} init so the
     * standalone {@code Obj_LBZMinibossBox} deletes itself while the Robotnik
     * intro owns the miniboss handoff.
     */
    public boolean isRobotnikIntroActive() {
        return robotnikIntroActive;
    }

    public void setRobotnikIntroActive(boolean robotnikIntroActive) {
        this.robotnikIntroActive = robotnikIntroActive;
    }

    /** ROM Adjust_LBZ2Layout applied-once latch for the loaded LBZ2 layout. */
    public boolean isLbz2LayoutAdjustApplied() {
        return lbz2LayoutAdjustApplied;
    }

    public void setLbz2LayoutAdjustApplied(boolean applied) {
        this.lbz2LayoutAdjustApplied = applied;
    }

    /** ROM LBZ2_LayoutMod applied-once latch (Events_routine_fg 0 -> 4). */
    public boolean isLbz2EntryCorridorApplied() {
        return lbz2EntryCorridorApplied;
    }

    public void setLbz2EntryCorridorApplied(boolean applied) {
        this.lbz2EntryCorridorApplied = applied;
    }

    public int getRollingDrumAngle(int nativePlayerIndex) {
        return nativePlayerIndex == 0 ? rollingDrumP1Angle : rollingDrumP2Angle;
    }

    public void setRollingDrumAngle(int nativePlayerIndex, int angle) {
        int normalized = angle & 0xFF;
        if (nativePlayerIndex == 0) {
            rollingDrumP1Angle = normalized;
        } else {
            rollingDrumP2Angle = normalized;
        }
    }

    public void requestScreenShakeOffset(int offset) {
        if (Math.abs(offset) > Math.abs(pendingScreenShakeOffset)) {
            pendingScreenShakeOffset = offset;
        }
    }

    public int consumeScreenShakeOffset() {
        int offset = pendingScreenShakeOffset;
        pendingScreenShakeOffset = 0;
        return offset;
    }

    public boolean isLaunchActive() {
        return launchActive;
    }

    public void setLaunchActive(boolean launchActive) {
        this.launchActive = launchActive;
    }

    public boolean isDeathEggRumble() {
        return deathEggRumble;
    }

    public void setDeathEggRumble(boolean deathEggRumble) {
        this.deathEggRumble = deathEggRumble;
    }

    public int getFgLaunchSpeed() {
        return fgLaunchSpeed;
    }

    public void setFgLaunchSpeed(int fgLaunchSpeed) {
        this.fgLaunchSpeed = fgLaunchSpeed;
    }

    public int getBgLaunchSpeed() {
        return bgLaunchSpeed;
    }

    public void setBgLaunchSpeed(int bgLaunchSpeed) {
        this.bgLaunchSpeed = bgLaunchSpeed;
    }

    public int getFgAccum() {
        return fgAccum;
    }

    public void setFgAccum(int fgAccum) {
        this.fgAccum = fgAccum;
    }

    public int getBgAccum() {
        return bgAccum;
    }

    public void setBgAccum(int bgAccum) {
        this.bgAccum = bgAccum;
    }

    public int getLaunchYDelta() {
        return launchYDelta;
    }

    public void setLaunchYDelta(int launchYDelta) {
        this.launchYDelta = launchYDelta;
    }

    /**
     * ROM LBZ2_DeathEggMoveScreen: while Scroll_lock the FG launch delta is
     * added to the registered rider object's y_pos (the hang-ride ship).
     * Published by the launch event each frame and consumed by the ship.
     */
    public void setLaunchRiderDelta(int launchRiderDelta) {
        this.launchRiderDelta = launchRiderDelta;
    }

    public int consumeLaunchRiderDelta() {
        int delta = launchRiderDelta;
        launchRiderDelta = 0;
        return delta;
    }

    /** ROM: LBZ2_EndFallingAccel runs every frame once Events_fg_5 (2nd use) fires. */
    public boolean isLaunchFallingAccelActive() {
        return launchFallingAccelActive;
    }

    public void setLaunchFallingAccelActive(boolean launchFallingAccelActive) {
        this.launchFallingAccelActive = launchFallingAccelActive;
    }

    public int getPreLaunchDelay() {
        return preLaunchDelay;
    }

    public void setPreLaunchDelay(int preLaunchDelay) {
        this.preLaunchDelay = preLaunchDelay;
    }

    public int getDetachScroll() {
        return detachScroll;
    }

    public void setDetachScroll(int detachScroll) {
        this.detachScroll = detachScroll;
    }

    public int getDetachWindowCopyTimer() {
        return detachWindowCopyTimer;
    }

    public void setDetachWindowCopyTimer(int detachWindowCopyTimer) {
        this.detachWindowCopyTimer = Math.max(0, detachWindowCopyTimer);
    }

    public int getWaterTargetY() {
        return waterTargetY;
    }

    public void setWaterTargetY(int waterTargetY) {
        this.waterTargetY = waterTargetY & 0xFFFF;
    }

    public void setWaterTargetYForTest(int waterTargetY) {
        setWaterTargetY(waterTargetY);
    }

    public boolean isPadCollapseActive() {
        return padCollapseActive;
    }

    public void setPadCollapseActive(boolean padCollapseActive) {
        this.padCollapseActive = padCollapseActive;
    }

    public boolean isFinalFallActive() {
        return finalFallActive;
    }

    public void setFinalFallActive(boolean finalFallActive) {
        this.finalFallActive = finalFallActive;
    }

    public boolean isDeathEggTerrainSwapQueued() {
        return deathEggTerrainSwapQueued;
    }

    public void setDeathEggTerrainSwapQueued(boolean deathEggTerrainSwapQueued) {
        this.deathEggTerrainSwapQueued = deathEggTerrainSwapQueued;
    }

    public boolean isDeathEggTerrainSwapApplied() {
        return deathEggTerrainSwapApplied;
    }

    public void setDeathEggTerrainSwapApplied(boolean deathEggTerrainSwapApplied) {
        this.deathEggTerrainSwapApplied = deathEggTerrainSwapApplied;
    }

    public boolean isWaterDisabled() {
        return waterDisabled;
    }

    public void setWaterDisabled(boolean waterDisabled) {
        this.waterDisabled = waterDisabled;
    }

    /**
     * ROM {@code _unkFAA9}: set by {@code CutsceneKnux_LBZ1}'s range helper
     * while Knuckles owns player input, and cleared when Knuckles exits.
     */
    public boolean isLbz1KnucklesCutsceneControlLocked() {
        return lbz1KnucklesCutsceneControlLocked;
    }

    public void setLbz1KnucklesCutsceneControlLocked(boolean locked) {
        this.lbz1KnucklesCutsceneControlLocked = locked;
    }

    /**
     * Continuous ROM {@code Screen_shake_flag=-1} bridge for the LBZ1 Knuckles
     * bomb sequence from the post-drop wait through the completed building
     * collapse.
     */
    public boolean isLbz1KnucklesBombShakeActive() {
        return lbz1KnucklesBombShakeActive;
    }

    public void setLbz1KnucklesBombShakeActive(boolean active) {
        this.lbz1KnucklesBombShakeActive = active;
    }

    public int getDeathEggDeformWrapLatch() {
        return deathEggDeformWrapLatch;
    }

    public void setDeathEggDeformWrapLatch(int deathEggDeformWrapLatch) {
        this.deathEggDeformWrapLatch = deathEggDeformWrapLatch & 0xFFFF;
    }

    public void requestLaunchStart() {
        launchStartRequested = true;
    }

    public boolean consumeLaunchStartRequested() {
        boolean requested = launchStartRequested;
        launchStartRequested = false;
        return requested;
    }

    public void requestPadCollapseStart() {
        padCollapseStartRequested = true;
    }

    public boolean consumePadCollapseStartRequested() {
        boolean requested = padCollapseStartRequested;
        padCollapseStartRequested = false;
        return requested;
    }

    public void requestFinalFall() {
        finalFallRequested = true;
    }

    public boolean consumeFinalFallRequested() {
        boolean requested = finalFallRequested;
        finalFallRequested = false;
        return requested;
    }

    public void requestLbz2RideAnimatedTiles() {
        setLbz2RideAnimatedTileGateActive(true);
    }

    public boolean consumeLbz2RideAnimatedTilesRequested() {
        return isLbz2RideAnimatedTileGateActive();
    }

    public boolean isLbz2RideAnimatedTileGateActive() {
        return lbz2RideAnimatedTileGateActive;
    }

    public void requestLbz1KnucklesBoundaryPublish() {
        lbz1KnucklesBoundaryPublishPending = true;
    }

    public boolean isLbz1KnucklesBoundaryPublishPending() {
        return lbz1KnucklesBoundaryPublishPending;
    }

    public void clearLbz1KnucklesBoundaryPublishPending() {
        lbz1KnucklesBoundaryPublishPending = false;
    }

    public void setLbz2RideAnimatedTileGateActive(boolean active) {
        lbz2RideAnimatedTileGateActive = active;
    }

    /**
     * Publishes the ROM global values used by {@code AnimateTiles_LBZ2}.
     *
     * <p>{@code Events_bg+$10} drives the dynamic waterline art, while
     * {@code Events_bg+$12 - Camera_X_pos_BG_copy} drives the scroll-art phase.
     * The scroll/deform routine owns these values, including the Death Egg
     * deform path where they intentionally diverge from the normal camera
     * formula.
     */
    public void publishLbz2DeformOutputs(int waterlinePhase, int scrollArtPhaseSource, int bgCameraX) {
        this.lbz2WaterlinePhase = waterlinePhase & 0xFFFF;
        this.lbz2ScrollArtPhaseSource = scrollArtPhaseSource & 0xFFFF;
        this.publishedBgCameraX = bgCameraX & 0xFFFF;
    }

    public int lbz2WaterlinePhase() {
        return lbz2WaterlinePhase;
    }

    public int lbz2ScrollArtPhaseSource() {
        return lbz2ScrollArtPhaseSource;
    }

    public int publishedBgCameraX() {
        return publishedBgCameraX;
    }

    public int lbz2ScrollArtPhase() {
        return (lbz2ScrollArtPhaseSource - publishedBgCameraX) & 0x0F;
    }

    /**
     * Reference-free launch rider identity for later object/event agents.
     *
     * <p>The ROM uses an object pointer in {@code Events_bg+0}; the Java
     * runtime state stores only a caller-supplied stable anchor id so rewind
     * snapshots never capture object references.
     */
    public void registerLaunchRiderAnchor(int anchorId) {
        launchRiderAnchorRegistered = true;
        launchRiderAnchorId = anchorId;
    }

    public OptionalInt getLaunchRiderAnchorId() {
        return launchRiderAnchorRegistered ? OptionalInt.of(launchRiderAnchorId) : OptionalInt.empty();
    }

    public void clearLaunchRiderAnchor() {
        launchRiderAnchorRegistered = false;
        launchRiderAnchorId = 0;
    }

    public void registerFinaleCutsceneAnchor(int anchorId) {
        finaleCutsceneAnchorRegistered = true;
        finaleCutsceneAnchorId = anchorId;
    }

    public OptionalInt getFinaleCutsceneAnchorId() {
        return finaleCutsceneAnchorRegistered ? OptionalInt.of(finaleCutsceneAnchorId) : OptionalInt.empty();
    }

    public void clearFinaleCutsceneAnchor() {
        finaleCutsceneAnchorRegistered = false;
        finaleCutsceneAnchorId = 0;
    }

    @Override
    public byte[] captureBytes() {
        ByteBuffer buffer = ByteBuffer.allocate(LEGACY_CAPTURE_BYTES + LAUNCH_EXTENSION_BYTES);
        buffer.put((byte) (alarmAnimationActive ? 1 : 0));
        buffer.put((byte) activeInteriorLayoutMod);
        buffer.put((byte) (interiorLayoutMod3Disabled ? 1 : 0));
        buffer.put((byte) rollingDrumP1Angle);
        buffer.put((byte) rollingDrumP2Angle);
        buffer.putInt(pendingScreenShakeOffset);
        buffer.put((byte) (robotnikIntroActive ? 1 : 0));
        buffer.put((byte) (lbz2LayoutAdjustApplied ? 1 : 0));
        buffer.put((byte) (lbz2EntryCorridorApplied ? 1 : 0));
        buffer.put((byte) LAUNCH_EXTENSION_VERSION);
        buffer.putInt(launchFlags());
        buffer.putInt(fgLaunchSpeed);
        buffer.putInt(bgLaunchSpeed);
        buffer.putInt(fgAccum);
        buffer.putInt(bgAccum);
        buffer.putInt(launchYDelta);
        buffer.putInt(preLaunchDelay);
        buffer.putInt(detachScroll);
        buffer.putInt(detachWindowCopyTimer);
        buffer.putInt(waterTargetY);
        buffer.putInt(launchRiderAnchorId);
        buffer.putInt(deathEggDeformWrapLatch);
        buffer.putInt(finaleCutsceneAnchorId);
        buffer.putInt(launchRiderDelta);
        buffer.putInt(lbz2WaterlinePhase);
        buffer.putInt(lbz2ScrollArtPhaseSource);
        buffer.putInt(publishedBgCameraX);
        return buffer.array();
    }

    @Override
    public void restoreBytes(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return;
        }
        alarmAnimationActive = bytes[0] != 0;
        if (bytes.length == 3) {
            rollingDrumP1Angle = bytes[1] & 0xFF;
            rollingDrumP2Angle = bytes[2] & 0xFF;
            clearLaunchState();
            return;
        }
        if (bytes.length >= 2) {
            setActiveInteriorLayoutMod(bytes[1] & 0xFF);
        }
        if (bytes.length >= 3) {
            interiorLayoutMod3Disabled = bytes[2] != 0;
        }
        if (bytes.length >= 4) {
            rollingDrumP1Angle = bytes[3] & 0xFF;
        }
        if (bytes.length >= 5) {
            rollingDrumP2Angle = bytes[4] & 0xFF;
        }
        if (bytes.length >= 9) {
            pendingScreenShakeOffset = ByteBuffer.wrap(bytes).getInt(5);
        }
        if (bytes.length >= 10) {
            robotnikIntroActive = bytes[9] != 0;
        }
        if (bytes.length >= 12) {
            lbz2LayoutAdjustApplied = bytes[10] != 0;
            lbz2EntryCorridorApplied = bytes[11] != 0;
        }
        if (bytes.length < LEGACY_CAPTURE_BYTES + LAUNCH_EXTENSION_BYTES_V1) {
            clearLaunchState();
            return;
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        buffer.position(LEGACY_CAPTURE_BYTES);
        int version = buffer.get() & 0xFF;
        if (version == LAUNCH_EXTENSION_VERSION_1) {
            restoreLaunchFlags(buffer.get() & 0xFF);
            restoreLaunchInts(buffer, false, false, false);
            return;
        }
        if (version == LAUNCH_EXTENSION_VERSION_2) {
            restoreLaunchFlags(buffer.getInt());
            restoreLaunchInts(buffer, true, false, false);
            return;
        }
        if (version == LAUNCH_EXTENSION_VERSION_3
                && bytes.length >= LEGACY_CAPTURE_BYTES + LAUNCH_EXTENSION_BYTES_V3) {
            restoreLaunchFlags(buffer.getInt());
            restoreLaunchInts(buffer, true, true, false);
            launchRiderDelta = 0;
            return;
        }
        if (version == LAUNCH_EXTENSION_VERSION_4
                && bytes.length >= LEGACY_CAPTURE_BYTES + LAUNCH_EXTENSION_BYTES_V4) {
            restoreLaunchFlags(buffer.getInt());
            restoreLaunchInts(buffer, true, true, false);
            launchRiderDelta = buffer.getInt();
            lbz2WaterlinePhase = 0;
            lbz2ScrollArtPhaseSource = 0;
            publishedBgCameraX = 0;
            return;
        }
        if (version == LAUNCH_EXTENSION_VERSION_5
                && bytes.length >= LEGACY_CAPTURE_BYTES + LAUNCH_EXTENSION_BYTES_V5) {
            restoreLaunchFlags(buffer.getInt());
            restoreLaunchInts(buffer, true, true, false);
            launchRiderDelta = buffer.getInt();
            lbz2WaterlinePhase = buffer.getInt();
            lbz2ScrollArtPhaseSource = buffer.getInt();
            publishedBgCameraX = buffer.getInt();
            return;
        }
        if (version != LAUNCH_EXTENSION_VERSION || bytes.length < LEGACY_CAPTURE_BYTES + LAUNCH_EXTENSION_BYTES) {
            clearLaunchState();
            return;
        }
        int flags = buffer.getInt();
        restoreLaunchFlags(flags);
        restoreLaunchInts(buffer, true, true, true);
        launchRiderDelta = buffer.getInt();
        lbz2WaterlinePhase = buffer.getInt();
        lbz2ScrollArtPhaseSource = buffer.getInt();
        publishedBgCameraX = buffer.getInt();
    }

    private void restoreLaunchFlags(int flags) {
        launchActive = (flags & LAUNCH_FLAG_ACTIVE) != 0;
        deathEggRumble = (flags & LAUNCH_FLAG_DEATH_EGG_RUMBLE) != 0;
        launchStartRequested = (flags & LAUNCH_FLAG_START_REQUESTED) != 0;
        padCollapseStartRequested = (flags & LAUNCH_FLAG_PAD_COLLAPSE_REQUESTED) != 0;
        finalFallRequested = (flags & LAUNCH_FLAG_FINAL_FALL_REQUESTED) != 0;
        launchRiderAnchorRegistered = (flags & LAUNCH_FLAG_RIDER_ANCHOR_REGISTERED) != 0;
        lbz2RideAnimatedTileGateActive =
                (flags & LAUNCH_FLAG_LBZ2_RIDE_ANIMATED_TILE_GATE_ACTIVE) != 0;
        padCollapseActive = (flags & LAUNCH_FLAG_PAD_COLLAPSE_ACTIVE) != 0;
        finalFallActive = (flags & LAUNCH_FLAG_FINAL_FALL_ACTIVE) != 0;
        deathEggTerrainSwapQueued = (flags & LAUNCH_FLAG_DEATH_EGG_TERRAIN_SWAP_QUEUED) != 0;
        deathEggTerrainSwapApplied = (flags & LAUNCH_FLAG_DEATH_EGG_TERRAIN_SWAP_APPLIED) != 0;
        waterDisabled = (flags & LAUNCH_FLAG_WATER_DISABLED) != 0;
        finaleCutsceneAnchorRegistered =
                (flags & LAUNCH_FLAG_FINALE_CUTSCENE_ANCHOR_REGISTERED) != 0;
        launchFallingAccelActive = (flags & LAUNCH_FLAG_FALLING_ACCEL_ACTIVE) != 0;
        lbz1KnucklesCutsceneControlLocked =
                (flags & LAUNCH_FLAG_LBZ1_KNUX_CONTROL_LOCKED) != 0;
        lbz1KnucklesBombShakeActive =
                (flags & LAUNCH_FLAG_LBZ1_KNUX_BOMB_SHAKE_ACTIVE) != 0;
        lbz1KnucklesBoundaryPublishPending =
                (flags & LAUNCH_FLAG_LBZ1_KNUX_BOUNDARY_PUBLISH_PENDING) != 0;
    }

    private void restoreLaunchInts(ByteBuffer buffer,
                                   boolean hasWaterTarget,
                                   boolean hasDeathEggDeformState,
                                   boolean hasDetachWindowCopyTimer) {
        fgLaunchSpeed = buffer.getInt();
        bgLaunchSpeed = buffer.getInt();
        fgAccum = buffer.getInt();
        bgAccum = buffer.getInt();
        launchYDelta = buffer.getInt();
        preLaunchDelay = buffer.getInt();
        detachScroll = buffer.getInt();
        detachWindowCopyTimer = hasDetachWindowCopyTimer ? buffer.getInt() : 0;
        waterTargetY = hasWaterTarget ? buffer.getInt() : 0;
        launchRiderAnchorId = buffer.getInt();
        deathEggDeformWrapLatch = hasDeathEggDeformState ? buffer.getInt() : 0;
        finaleCutsceneAnchorId = hasDeathEggDeformState ? buffer.getInt() : 0;
    }

    private void clearLaunchState() {
        launchActive = false;
        deathEggRumble = false;
        fgLaunchSpeed = 0;
        bgLaunchSpeed = 0;
        fgAccum = 0;
        bgAccum = 0;
        launchYDelta = 0;
        preLaunchDelay = 0;
        detachScroll = 0;
        detachWindowCopyTimer = 0;
        waterTargetY = 0;
        launchStartRequested = false;
        padCollapseStartRequested = false;
        finalFallRequested = false;
        lbz2RideAnimatedTileGateActive = false;
        padCollapseActive = false;
        finalFallActive = false;
        deathEggTerrainSwapQueued = false;
        deathEggTerrainSwapApplied = false;
        waterDisabled = false;
        lbz1KnucklesCutsceneControlLocked = false;
        lbz1KnucklesBombShakeActive = false;
        lbz1KnucklesBoundaryPublishPending = false;
        deathEggDeformWrapLatch = 0;
        launchRiderDelta = 0;
        launchFallingAccelActive = false;
        lbz2WaterlinePhase = 0;
        lbz2ScrollArtPhaseSource = 0;
        publishedBgCameraX = 0;
        clearLaunchRiderAnchor();
        clearFinaleCutsceneAnchor();
    }

    private int launchFlags() {
        int flags = 0;
        if (launchActive) {
            flags |= LAUNCH_FLAG_ACTIVE;
        }
        if (deathEggRumble) {
            flags |= LAUNCH_FLAG_DEATH_EGG_RUMBLE;
        }
        if (launchStartRequested) {
            flags |= LAUNCH_FLAG_START_REQUESTED;
        }
        if (padCollapseStartRequested) {
            flags |= LAUNCH_FLAG_PAD_COLLAPSE_REQUESTED;
        }
        if (finalFallRequested) {
            flags |= LAUNCH_FLAG_FINAL_FALL_REQUESTED;
        }
        if (launchRiderAnchorRegistered) {
            flags |= LAUNCH_FLAG_RIDER_ANCHOR_REGISTERED;
        }
        if (lbz2RideAnimatedTileGateActive) {
            flags |= LAUNCH_FLAG_LBZ2_RIDE_ANIMATED_TILE_GATE_ACTIVE;
        }
        if (padCollapseActive) {
            flags |= LAUNCH_FLAG_PAD_COLLAPSE_ACTIVE;
        }
        if (finalFallActive) {
            flags |= LAUNCH_FLAG_FINAL_FALL_ACTIVE;
        }
        if (deathEggTerrainSwapQueued) {
            flags |= LAUNCH_FLAG_DEATH_EGG_TERRAIN_SWAP_QUEUED;
        }
        if (deathEggTerrainSwapApplied) {
            flags |= LAUNCH_FLAG_DEATH_EGG_TERRAIN_SWAP_APPLIED;
        }
        if (waterDisabled) {
            flags |= LAUNCH_FLAG_WATER_DISABLED;
        }
        if (finaleCutsceneAnchorRegistered) {
            flags |= LAUNCH_FLAG_FINALE_CUTSCENE_ANCHOR_REGISTERED;
        }
        if (launchFallingAccelActive) {
            flags |= LAUNCH_FLAG_FALLING_ACCEL_ACTIVE;
        }
        if (lbz1KnucklesCutsceneControlLocked) {
            flags |= LAUNCH_FLAG_LBZ1_KNUX_CONTROL_LOCKED;
        }
        if (lbz1KnucklesBombShakeActive) {
            flags |= LAUNCH_FLAG_LBZ1_KNUX_BOMB_SHAKE_ACTIVE;
        }
        if (lbz1KnucklesBoundaryPublishPending) {
            flags |= LAUNCH_FLAG_LBZ1_KNUX_BOUNDARY_PUBLISH_PENDING;
        }
        return flags;
    }
}
