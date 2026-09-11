package com.openggf.game.sonic3k.dataselect;

import com.openggf.game.sonic3k.audio.Sonic3kSfx;

import java.util.Objects;
import java.util.function.IntConsumer;

/**
 * Native S3K Data Select selector state.
 *
 * <p>Tracks the current entry and dispatches the movement SFX used by the original save menu.
 */
public final class S3kSaveScreenSelectorState {
    public static final int MIN_ENTRY = 0;
    public static final int MAX_ENTRY = 9;
    private static final int INITIAL_SELECTOR_X = 0xA8;
    private static final int ENTRY_STEPS = 13;
    private static final int STEP_PIXELS = 8;
    private static final int MAX_SELECTOR_X = 0x120;
    private static final int MAX_CAMERA_X = 0x2C0;
    private static final int SELECTOR_EDGE_LEFT = 0xF0;
    private static final int SELECTOR_EDGE_RIGHT = 0x148;
    private static final int MOVE_FRAMES = 13;

    private final IntConsumer sfxPlayer;
    private int currentEntry;
    private int selectorAnchorX;
    private int selectorBiasedX;
    private int cameraX;
    private int mappingFrame;
    private int movementDelta;
    private int movementFramesRemaining;
    private boolean visible = true;

    public S3kSaveScreenSelectorState(IntConsumer sfxPlayer) {
        this(sfxPlayer, MIN_ENTRY);
    }

    public S3kSaveScreenSelectorState(IntConsumer sfxPlayer, int initialEntry) {
        this.sfxPlayer = Objects.requireNonNull(sfxPlayer, "sfxPlayer");
        setCurrentEntry(initialEntry);
    }

    public int currentEntry() {
        return currentEntry;
    }

    public int selectorBiasedX() {
        return selectorBiasedX;
    }

    public int cameraX() {
        return cameraX;
    }

    public int mappingFrame() {
        return mappingFrame;
    }

    public boolean visible() {
        return visible;
    }

    public boolean isMoving() {
        return movementFramesRemaining > 0 && movementDelta != 0;
    }

    public void setVisible(boolean visible) {
        this.visible = visible;
    }

    public void setCurrentEntry(int entry) {
        currentEntry = clamp(entry);
        movementDelta = 0;
        movementFramesRemaining = 0;
        SelectorTarget target = computeSettledTarget(currentEntry);
        selectorAnchorX = target.selectorAnchorX();
        cameraX = target.cameraX();
        recomputeDerivedState();
    }

    public boolean moveLeft(boolean deleteMode) {
        int minEntry = deleteMode ? 1 : MIN_ENTRY;
        if (currentEntry <= minEntry) {
            return false;
        }
        currentEntry--;
        playMovementSfx(deleteMode);
        movementDelta = -STEP_PIXELS;
        movementFramesRemaining = MOVE_FRAMES;
        advanceFrame();
        return true;
    }

    public boolean moveRight(boolean deleteMode) {
        if (currentEntry >= MAX_ENTRY) {
            return false;
        }
        currentEntry++;
        playMovementSfx(deleteMode);
        movementDelta = STEP_PIXELS;
        movementFramesRemaining = MOVE_FRAMES;
        advanceFrame();
        return true;
    }

    public void advanceFrame() {
        if (movementFramesRemaining <= 0 || movementDelta == 0) {
            return;
        }
        if (movementDelta > 0) {
            int nextAnchorX = selectorAnchorX + movementDelta;
            if (nextAnchorX <= MAX_SELECTOR_X) {
                selectorAnchorX = nextAnchorX;
            } else {
                int nextCameraX = cameraX + movementDelta;
                if (nextCameraX <= MAX_CAMERA_X) {
                    cameraX = nextCameraX;
                } else {
                    selectorAnchorX = nextAnchorX;
                }
            }
        } else {
            int nextAnchorX = selectorAnchorX + movementDelta;
            if (nextAnchorX >= MAX_SELECTOR_X) {
                selectorAnchorX = nextAnchorX;
            } else {
                int nextCameraX = cameraX + movementDelta;
                if (nextCameraX >= 0) {
                    cameraX = nextCameraX;
                } else {
                    selectorAnchorX = nextAnchorX;
                }
            }
        }
        movementFramesRemaining--;
        if (movementFramesRemaining == 0) {
            SelectorTarget target = computeSettledTarget(currentEntry);
            selectorAnchorX = target.selectorAnchorX();
            cameraX = target.cameraX();
            movementDelta = 0;
        }
        recomputeDerivedState();
    }

    private void playMovementSfx(boolean deleteMode) {
        sfxPlayer.accept(deleteMode
                ? Sonic3kSfx.SMALL_BUMPERS.id
                : Sonic3kSfx.SLOT_MACHINE.id);
    }

    private void recomputeDerivedState() {
        int edgeOffset = currentEntry == MIN_ENTRY ? STEP_PIXELS
                : currentEntry == MAX_ENTRY ? -STEP_PIXELS : 0;
        selectorBiasedX = selectorAnchorX + edgeOffset;
        mappingFrame = selectorBiasedX >= SELECTOR_EDGE_LEFT && selectorBiasedX <= SELECTOR_EDGE_RIGHT ? 1 : 2;
    }

    private SelectorTarget computeSettledTarget(int entry) {
        int selectorX = INITIAL_SELECTOR_X;
        int scrollX = 0;
        int remainingEntries = entry - 1;
        while (remainingEntries-- >= 0) {
            for (int i = 0; i < ENTRY_STEPS; i++) {
                selectorX += STEP_PIXELS;
                if (selectorX > MAX_SELECTOR_X) {
                    selectorX -= STEP_PIXELS;
                    scrollX += STEP_PIXELS;
                    if (scrollX > MAX_CAMERA_X) {
                        scrollX -= STEP_PIXELS;
                        selectorX += STEP_PIXELS;
                    }
                }
            }
        }
        return new SelectorTarget(selectorX, scrollX);
    }

    private static int clamp(int entry) {
        return Math.max(MIN_ENTRY, Math.min(MAX_ENTRY, entry));
    }

    private record SelectorTarget(int selectorAnchorX, int cameraX) {
    }
}
