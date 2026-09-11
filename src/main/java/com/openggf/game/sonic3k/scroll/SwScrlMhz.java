package com.openggf.game.sonic3k.scroll;

import com.openggf.game.GameServices;
import com.openggf.game.sonic3k.runtime.MhzZoneRuntimeState;
import com.openggf.game.sonic3k.runtime.S3kRuntimeStates;
import com.openggf.level.scroll.AbstractZoneScrollHandler;
import com.openggf.level.scroll.compose.ScrollEffectComposer;

import static com.openggf.level.scroll.M68KMath.VISIBLE_LINES;
import static com.openggf.level.scroll.M68KMath.negWord;

/**
 * Mushroom Hill Zone scroll handler.
 *
 * <p>Ports the shared {@code MHZ_Deform} routine used by MHZ1 and normal MHZ2
 * play, plus the MHZ2 ship/end-boss H-int foreground overrides driven by
 * event/runtime state.
 */
public class SwScrlMhz extends AbstractZoneScrollHandler {

    private final ScrollEffectComposer composer = new ScrollEffectComposer();
    private int bgCameraX = Integer.MIN_VALUE;
    private int band1CameraX;
    private int band2CameraX;
    private boolean loopScrollInitialized;
    private int loopActualCameraX;
    private int loopAdjustedCameraX;

    @Override
    public void init(int actId, int cameraX, int cameraY) {
        loopActualCameraX = cameraX & 0xFFFF;
        loopAdjustedCameraX = loopActualCameraX;
        loopScrollInitialized = true;
    }

    @Override
    public void update(int[] horizScrollBuf,
                       int cameraX,
                       int cameraY,
                       int frameCounter,
                       int actId) {
        resetScrollTracking();
        composer.reset();

        short fgScroll = negWord(cameraX);
        MhzZoneRuntimeState state = currentRuntimeState();
        DeformOutputs outputs = computeMhzDeform(adjustBgDuringLoop(cameraX), cameraY,
                state == null ? 0 : state.screenShakeOffset(),
                state != null && state.isBossAreaBackgroundDeformActive());
        bgCameraX = outputs.bgCameraX();
        band1CameraX = outputs.band1CameraX();
        band2CameraX = outputs.band2CameraX();
        publishDeformOutputs(state);

        composer.setVscrollFactorBG(outputs.bgCameraY());
        composer.fillPackedScrollWords(0, VISIBLE_LINES, fgScroll, negWord(bgCameraX));
        if (state != null && state.isShipSequenceActive()) {
            applyShipHIntOverride(state);
        } else {
            applyEndBossArenaHIntOverride(state, cameraX);
        }
        composer.copyPackedScrollWordsTo(horizScrollBuf);

        vscrollFactorBG = composer.getVscrollFactorBG();
        minScrollOffset = composer.getMinScrollOffset();
        maxScrollOffset = composer.getMaxScrollOffset();
    }

    @Override
    public int getBgCameraX() {
        return bgCameraX;
    }

    int getMiddleBgCameraX() {
        return band1CameraX;
    }

    int getNearBgCameraX() {
        return band2CameraX;
    }

    private int adjustBgDuringLoop(int cameraX) {
        int currentCameraX = cameraX & 0xFFFF;
        if (!loopScrollInitialized) {
            loopActualCameraX = currentCameraX;
            loopAdjustedCameraX = currentCameraX;
            loopScrollInitialized = true;
            return loopAdjustedCameraX;
        }

        int delta = (short) (currentCameraX - loopActualCameraX);
        loopActualCameraX = currentCameraX;
        if (delta < 0) {
            int magnitude = -delta;
            if (magnitude >= 0x0100) {
                magnitude -= 0x0200;
            }
            loopAdjustedCameraX = (loopAdjustedCameraX - magnitude) & 0xFFFF;
        } else {
            int magnitude = delta;
            if (magnitude >= 0x0100) {
                magnitude -= 0x0200;
            }
            loopAdjustedCameraX = (loopAdjustedCameraX + magnitude) & 0xFFFF;
        }
        return loopAdjustedCameraX;
    }

    private static DeformOutputs computeMhzDeform(int cameraX, int cameraY, int screenShakeOffset,
                                                   boolean bossAreaDeformActive) {
        short bgY = bossAreaDeformActive
                ? computeBgY(cameraY, 0x280, 0x180)
                : computeBgY(cameraY, 0, 0x76);
        int adjustedCameraX = cameraX - screenShakeOffset;
        int d0 = ((short) adjustedCameraX) << 16;
        d0 >>= 1;
        int d1 = d0;
        d1 >>= 2;
        d0 -= d1;
        d1 >>= 1;

        short bgX = (short) (highWord(d0) + screenShakeOffset);
        d0 -= d1;
        short band1 = highWord(d0);
        d0 -= d1;
        short band2 = highWord(d0);
        return new DeformOutputs(bgY, bgX, band1, band2);
    }

    private static short computeBgY(int cameraY, int yOffset, int baseY) {
        int d0 = ((short) (cameraY - yOffset)) << 16;
        d0 >>= 3;
        int d1 = d0;
        d1 >>= 2;
        d0 += d1;
        return (short) (highWord(d0) + baseY);
    }

    private static short highWord(int value) {
        return (short) (value >> 16);
    }

    private void publishDeformOutputs(MhzZoneRuntimeState state) {
        if (state != null) {
            state.publishDeformOutputs(bgCameraX, band1CameraX, band2CameraX);
        }
    }

    private void applyShipHIntOverride(MhzZoneRuntimeState state) {
        short shipScroll = negWord(state.shipPrimaryHScroll());
        int lineCount = Math.min(VISIBLE_LINES, state.shipHIntCounter() & 0xFFFF);
        for (int line = 0; line < lineCount; line++) {
            short bgScroll = (short) composer.packedScrollWordAt(line);
            composer.writePackedScrollWord(line, shipScroll, bgScroll);
        }
    }

    private void applyEndBossArenaHIntOverride(MhzZoneRuntimeState state, int cameraXCopy) {
        if (state == null || !state.isEndBossArenaBackgroundActive()) {
            return;
        }
        short bgCameraScroll = negWord(bgCameraX);
        int lineCount = Math.min(VISIBLE_LINES, 0x30);
        for (int line = 0; line < lineCount; line++) {
            short bgScroll = (short) composer.packedScrollWordAt(line);
            composer.writePackedScrollWord(line, bgCameraScroll, bgScroll);
        }
        applyEndBossPillarHScrollRamp(state, cameraXCopy);
    }

    private void applyEndBossPillarHScrollRamp(MhzZoneRuntimeState state, int cameraXCopy) {
        if (!state.isEndBossArenaForegroundRefreshActive()) {
            return;
        }
        int rampAccumulator = (negWord(cameraXCopy - 0x80) & 0xFFFF) << 16;
        int rampStep = computeEndBossPillarRampStep(cameraXCopy);
        int lineCount = Math.min(VISIBLE_LINES, 0x30);
        for (int line = 0; line < lineCount; line++) {
            short fgScroll = (short) (composer.packedScrollWordAt(line) >> 16);
            short rampScroll = (short) (rampAccumulator >> 16);
            composer.writePackedScrollWord(line, fgScroll, rampScroll);
            rampAccumulator += rampStep;
        }
        publishEndBossArenaHelperXPositions(state, cameraXCopy);
    }

    private void publishEndBossArenaHelperXPositions(MhzZoneRuntimeState state, int cameraX) {
        int[] spikeX = new int[6];
        spikeX[0] = computeEndBossArenaHelperX(cameraX, 40);
        spikeX[1] = spikeX[0];
        spikeX[2] = computeEndBossArenaHelperX(cameraX, 30);
        spikeX[3] = spikeX[2];
        spikeX[4] = computeEndBossArenaHelperX(cameraX, 17);
        spikeX[5] = spikeX[4];
        state.publishEndBossArenaHelperXPositions(computeEndBossArenaHelperX(cameraX, 47), spikeX);
    }

    private int computeEndBossArenaHelperX(int cameraX, int hScrollLine) {
        short bgScroll = (short) composer.packedScrollWordAt(hScrollLine);
        return (((bgScroll - 0x48) & 0x1FF) + cameraX) & 0xFFFF;
    }

    private static int computeEndBossPillarRampStep(int cameraX) {
        int distance = (short) (cameraX - 0x4180);
        int scaled = distance * 0x5600;
        scaled += scaled;
        int scaledSwapped = swapWords(scaled);
        int deltaWord = (short) (distance - (short) scaledSwapped);
        deltaWord = (short) (deltaWord - 0x18);
        int magnitude = deltaWord << 16;
        boolean negative = magnitude < 0;
        if (negative) {
            magnitude = -magnitude;
        }
        int highQuotient = ((magnitude >>> 16) & 0xFFFF) / 0x30;
        int lowQuotient = (magnitude & 0xFFFF) / 0x30;
        int step = (highQuotient << 16) | lowQuotient;
        return negative ? -step : step;
    }

    private static int swapWords(int value) {
        return (value << 16) | ((value >>> 16) & 0xFFFF);
    }

    private MhzZoneRuntimeState currentRuntimeState() {
        if (!GameServices.hasRuntime()) {
            return null;
        }
        return S3kRuntimeStates.currentMhz(GameServices.zoneRuntimeRegistry()).orElse(null);
    }

    private record DeformOutputs(short bgCameraY, short bgCameraX, short band1CameraX, short band2CameraX) {
    }
}
