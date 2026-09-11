package com.openggf.game.sonic3k.scroll;

import com.openggf.game.GameServices;
import com.openggf.game.sonic3k.Sonic3kBonusStageCoordinator;
import com.openggf.game.sonic3k.bonusstage.slots.S3kSlotBonusStageRuntime;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.level.LevelManager;
import com.openggf.level.scroll.AbstractZoneScrollHandler;
import com.openggf.level.scroll.compose.ScrollEffectComposer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static com.openggf.level.scroll.M68KMath.negWord;

/**
 * ROM-shaped Slot Machine bonus-stage screen/background handler.
 *
 * <p>This ports the state owned by Slots_ScreenInit/Event and
 * Slots_BackgroundInit/Event into the engine scroll handler rather than
 * synthesizing a new wobble model in the runtime.
 */
public final class SwScrlSlots extends AbstractZoneScrollHandler {
    private static final int VISIBLE_LINES = 224;
    private static final int SCREEN_ORIGIN = 0x400;
    private static final int SCREEN_STEP_LIMIT = 0x20;
    private static final int BG_SCROLL_STEP = 0x400;
    private static final int BG_SCROLL_LIMIT = 0x8000;
    private static final int BG_DEFORM_SENTINEL = 0x7FFF;
    private static final int[] BG_DEFORM_SEGMENTS = {
            0x20, 0x20, 0x20, 0x20, 0x30, 0x20, 0x10,
            0x20, 0x20, 0x20, 0x20, 0x20, 0x30, 0x20, 0x10, BG_DEFORM_SENTINEL
    };

    private static final BandConfig[] BAND_CONFIGS = {
            new BandConfig(0x48, 0x0000C000, 0x0008, 0x0008, 0xE000, -2, true, 0x0000, 0x0019, true),
            new BandConfig(0x48, 0x0000C000, 0x0009, 0x0008, 0xE200, -2, true, 0x0020, 0x0019, true),
            new BandConfig(0x50, 0x0000A000, 0x000B, 0x0010, 0xE400, 0, true, 0x0040, 0x0019, true),
            new BandConfig(0x40, 0x0000E000, 0x0004, 0x0008, 0, 0, false, 0, 0, false),
            new BandConfig(0x40, 0x00010000, 0x0003, 0x0008, 0, 0, false, 0, 0, false),
            new BandConfig(0x48, 0x0000C000, 0x000A, 0x0008, 0xEB00, -2, true, 0x00B0, 0x0019, true),
            new BandConfig(0x58, 0x00008000, 0x0007, 0x0004, 0xED00, -2, false, 0x00D0, 0x0019, true),
            new BandConfig(0x40, 0x0000E000, 0x0005, 0x0008, 0, 0, false, 0, 0, false)
    };

    private final short[] perLineVScroll = new short[VISIBLE_LINES];
    private final ScrollEffectComposer composer = new ScrollEffectComposer();
    private final BandState[] bandStates = createBandStates();
    private final int[] bandScrollValues = new int[BAND_CONFIGS.length];
    private final int[] expandedBandScrollValues = new int[BG_DEFORM_SEGMENTS.length];
    private final List<BgPlaneRowUpdate> lastBgPlaneRowUpdates = new ArrayList<>(8);

    private S3kSlotBonusStageRuntime activeRuntime;
    private boolean screenInitialized;
    private boolean backgroundInitialized;
    private int foregroundOriginX;
    private int foregroundOriginY;
    private int backgroundVelocity;
    private int backgroundCameraYFixed;
    private int lastForegroundOriginX;
    private int lastForegroundOriginY;
    private int lastBackgroundOriginX;
    private int lastBackgroundOriginY;

    @Override
    public void update(int[] horizScrollBuf, int cameraX, int cameraY, int frameCounter, int actId) {
        applyScrollState(horizScrollBuf, cameraX, cameraY, activeSlotRuntime());
    }

    void updateForTest(S3kSlotBonusStageRuntime runtime, int cameraX, int cameraY, int frameCounter) {
        applyScrollState(null, cameraX, cameraY, runtime);
    }

    void updateForTest(S3kSlotBonusStageRuntime runtime, int[] horizScrollBuf, int cameraX, int cameraY,
                       int frameCounter) {
        applyScrollState(horizScrollBuf, cameraX, cameraY, runtime);
    }

    private void applyScrollState(int[] horizScrollBuf, int cameraX, int cameraY, S3kSlotBonusStageRuntime runtime) {
        resetScrollTracking();
        composer.reset();
        lastBgPlaneRowUpdates.clear();
        ensureRuntimeState(runtime, cameraX, cameraY);

        S3kSlotBonusStageRuntime.SlotVisualState visualState = runtime != null
                ? runtime.slotVisualState()
                : new S3kSlotBonusStageRuntime.SlotVisualState(0x10, 0x2D, 0x40, false);

        int targetX = (SCREEN_ORIGIN - visualState.eventsBgX()) + cameraX;
        int targetY = (SCREEN_ORIGIN - visualState.eventsBgY()) + cameraY;
        if (!screenInitialized) {
            foregroundOriginX = targetX;
            foregroundOriginY = targetY;
            screenInitialized = true;
        } else {
            foregroundOriginX = stepToward(foregroundOriginX, targetX, SCREEN_STEP_LIMIT);
            foregroundOriginY = stepToward(foregroundOriginY, targetY, SCREEN_STEP_LIMIT);
        }

        tickBackground(visualState.scalarIndex1());
        applyPendingBackgroundPlaneUpdates();

        lastForegroundOriginX = foregroundOriginX;
        lastForegroundOriginY = foregroundOriginY;
        lastBackgroundOriginX = foregroundOriginX + bandScrollValues[0];
        lastBackgroundOriginY = backgroundCameraY();
        composer.setVscrollFactorBG((short) backgroundCameraY());
        Arrays.fill(perLineVScroll, (short) 0);

        if (horizScrollBuf != null) {
            fillPackedScrollBuffer(horizScrollBuf);
        } else {
            // Still publish vscroll factor + scroll bookkeeping when running in headless probes.
            vscrollFactorBG = composer.getVscrollFactorBG();
            minScrollOffset = composer.getMinScrollOffset();
            maxScrollOffset = composer.getMaxScrollOffset();
        }
    }

    @Override
    public short[] getPerLineVScrollBG() {
        return perLineVScroll;
    }

    int lastForegroundOriginXForTest() {
        return lastForegroundOriginX;
    }

    int lastForegroundOriginYForTest() {
        return lastForegroundOriginY;
    }

    int lastBackgroundOriginXForTest() {
        return lastBackgroundOriginX;
    }

    int lastBackgroundOriginYForTest() {
        return lastBackgroundOriginY;
    }

    int backgroundVelocityForTest() {
        return backgroundVelocity;
    }

    List<BgPlaneRowUpdate> lastBgPlaneRowUpdatesForTest() {
        return Collections.unmodifiableList(lastBgPlaneRowUpdates);
    }

    @Override
    public short getVscrollFactorFG() {
        return (short) foregroundOriginY;
    }

    private void ensureRuntimeState(S3kSlotBonusStageRuntime runtime, int cameraX, int cameraY) {
        if (runtime == activeRuntime) {
            return;
        }
        activeRuntime = runtime;
        screenInitialized = false;
        backgroundInitialized = false;
        foregroundOriginX = cameraX;
        foregroundOriginY = cameraY;
        backgroundVelocity = 0;
        backgroundCameraYFixed = 0;
        Arrays.fill(bandScrollValues, 0);
        Arrays.fill(expandedBandScrollValues, 0);
        for (BandState bandState : bandStates) {
            bandState.reset();
        }
    }

    private void tickBackground(int scalarIndex1) {
        if (!backgroundInitialized) {
            backgroundInitialized = true;
            backgroundVelocity = 0;
            backgroundCameraYFixed = 0;
            Arrays.fill(bandScrollValues, 0);
            expandBandScrollTable();
            return;
        }

        if (scalarIndex1 == 0) {
            expandBandScrollTable();
            return;
        }

        int step = scalarIndex1 < 0 ? -BG_SCROLL_STEP : BG_SCROLL_STEP;
        backgroundVelocity = clampSignedLong(backgroundVelocity + step, BG_SCROLL_LIMIT);
        backgroundCameraYFixed += backgroundVelocity;
        backgroundCameraYFixed &= 0x00FF_FFFF;

        boolean backgroundEventLatched = false;
        for (int i = 0; i < BAND_CONFIGS.length; i++) {
            backgroundEventLatched = tickBand(bandStates[i], BAND_CONFIGS[i], backgroundEventLatched);
            // ROM loc_5997C copies word 0 of each $C-byte band state plus
            // word 8 into the deformation table. Word 0 is the high word of
            // the long accumulator updated at loc_598B4.
            bandScrollValues[i] = toSignedWord(bandStates[i].accumulatorHighWord() + bandStates[i].baseScroll);
        }
        expandBandScrollTable();
    }

    private void fillPackedScrollBuffer(int[] horizScrollBuf) {
        short fgScroll = negWord(foregroundOriginX);
        int segmentIndex = 0;
        int segmentOffset = backgroundCameraY();
        while (segmentIndex < BG_DEFORM_SEGMENTS.length - 1 && segmentOffset >= BG_DEFORM_SEGMENTS[segmentIndex]) {
            segmentOffset -= BG_DEFORM_SEGMENTS[segmentIndex];
            segmentIndex++;
        }

        int line = 0;
        while (line < VISIBLE_LINES && segmentIndex < BG_DEFORM_SEGMENTS.length) {
            int segmentLength = BG_DEFORM_SEGMENTS[segmentIndex];
            if (segmentLength == BG_DEFORM_SENTINEL) {
                segmentLength = VISIBLE_LINES - line;
            } else {
                segmentLength -= segmentOffset;
            }
            if (segmentLength <= 0) {
                segmentIndex++;
                segmentOffset = 0;
                continue;
            }

            short bgScroll = negWord(expandedBandScrollValues[segmentIndex]);
            int linesToWrite = Math.min(segmentLength, VISIBLE_LINES - line);
            composer.fillPackedScrollWords(line, linesToWrite, fgScroll, bgScroll);
            line += linesToWrite;
            segmentIndex++;
            segmentOffset = 0;
        }

        composer.copyPackedScrollWordsTo(horizScrollBuf);
        vscrollFactorBG = composer.getVscrollFactorBG();
        minScrollOffset = composer.getMinScrollOffset();
        maxScrollOffset = composer.getMaxScrollOffset();
    }

    private void expandBandScrollTable() {
        for (int i = 0; i < expandedBandScrollValues.length; i++) {
            expandedBandScrollValues[i] = bandScrollValues[i & 0x7];
        }
    }

    private int backgroundCameraY() {
        return (backgroundCameraYFixed >>> 16) & 0xFF;
    }

    private boolean tickBand(BandState state, BandConfig config, boolean backgroundEventLatched) {
        long sum = (state.accumulator & 0xFFFF_FFFFL) + (config.deltaLong & 0xFFFF_FFFFL);
        state.accumulator = (int) sum;
        boolean carry = (sum & 0x1_0000_0000L) != 0;
        if (carry) {
            state.setAccumulatorHighWord(state.accumulatorHighWord() + config.stepWord);
            state.pendingAdvance = true;
        } else if (state.accumulatorHighWord() >= config.stepWord) {
            state.setAccumulatorHighWord(state.accumulatorHighWord() - config.stepWord);
            state.pendingAdvance = true;
        }

        boolean blockedByFrameLatch = config.usesFrameLatch && backgroundEventLatched;
        if (blockedByFrameLatch) {
            return backgroundEventLatched;
        }

        state.countdown--;
        if (state.countdown < 0) {
            state.countdown = config.resetCountdown;
        } else if (!state.pendingAdvance) {
            return backgroundEventLatched;
        }

        state.pendingAdvance = false;
        state.phase++;
        if (state.phase >= config.phaseLimit) {
            state.phase = 0;
        }
        if (!config.usesFrameLatch) {
            state.baseScroll = toSignedWord(state.baseScroll + config.stepWord);
        } else if (config.hasPlaneUpdate()) {
            queuePlaneRowUpdate(state, config);
        }
        return true;
    }

    private void queuePlaneRowUpdate(BandState state, BandConfig config) {
        int destVramAddress = config.destVramAddress;
        if ((state.phase & 1) != 0) {
            destVramAddress += config.oddPhaseDestDelta;
        }
        int sourceX = toSignedWord(state.phase * config.stepWord);
        lastBgPlaneRowUpdates.add(new BgPlaneRowUpdate(sourceX, config.sourceY,
                destVramAddress, config.longWordCount));
        if (config.doubleRow) {
            lastBgPlaneRowUpdates.add(new BgPlaneRowUpdate(sourceX, config.sourceY + 0x10,
                    destVramAddress + 0x100, config.longWordCount));
        }
    }

    private void applyPendingBackgroundPlaneUpdates() {
        if (lastBgPlaneRowUpdates.isEmpty()) {
            return;
        }
        try {
            LevelManager levelManager = GameServices.level();
            if (levelManager.getCurrentZone() != Sonic3kZoneIds.ZONE_SLOT_MACHINE
                    && levelManager.getRomZoneId() != Sonic3kZoneIds.ZONE_SLOT_MACHINE) {
                return;
            }
            boolean changed = false;
            for (BgPlaneRowUpdate update : lastBgPlaneRowUpdates) {
                changed |= levelManager.copyBackgroundTileRowFromWorldToVdpPlane(update.sourceX, update.sourceY,
                        update.destVramAddress, update.longWordCount);
            }
            if (changed) {
                levelManager.uploadBackgroundTilemap();
            }
        } catch (IllegalStateException ignored) {
            // Unit tests and headless scroll probes can run without a gameplay session.
        }
    }

    private static int clampSignedLong(int value, int limit) {
        if (value > limit) {
            return limit;
        }
        if (value < -limit) {
            return -limit;
        }
        return value;
    }

    private static int stepToward(int current, int target, int maxStep) {
        int delta = target - current;
        if (delta > maxStep) {
            return current + maxStep;
        }
        if (delta < -maxStep) {
            return current - maxStep;
        }
        return target;
    }

    private static int toSignedWord(int value) {
        return (short) value;
    }

    private static BandState[] createBandStates() {
        BandState[] states = new BandState[BAND_CONFIGS.length];
        for (int i = 0; i < states.length; i++) {
            states[i] = new BandState();
        }
        return states;
    }

    private S3kSlotBonusStageRuntime activeSlotRuntime() {
        if (!(GameServices.module().getBonusStageProvider() instanceof Sonic3kBonusStageCoordinator coordinator)) {
            return null;
        }
        return coordinator.activeSlotRuntime();
    }

    private static final class BandState {
        private int accumulator;
        private int countdown;
        private int phase;
        private int baseScroll;
        private boolean pendingAdvance;

        private void reset() {
            accumulator = 0;
            countdown = 0;
            phase = 0;
            baseScroll = 0;
            pendingAdvance = false;
        }

        private int accumulatorHighWord() {
            return (accumulator >>> 16) & 0xFFFF;
        }

        private void setAccumulatorHighWord(int value) {
            accumulator = ((value & 0xFFFF) << 16) | (accumulator & 0xFFFF);
        }
    }

    record BgPlaneRowUpdate(int sourceX, int sourceY, int destVramAddress, int longWordCount) {
    }

    private record BandConfig(int stepWord, int deltaLong, int resetCountdown, int phaseLimit,
                              int destVramAddress, int oddPhaseDestDelta, boolean doubleRow,
                              int sourceY, int longWordCount, boolean usesFrameLatch) {
        private boolean hasPlaneUpdate() {
            return destVramAddress != 0 && longWordCount > 0;
        }
    }
}
