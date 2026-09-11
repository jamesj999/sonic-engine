package com.openggf.graphics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TestFadeManagerRewindSnapshot {
    private FadeManager fadeManager;

    @BeforeEach
    void setUp() {
        fadeManager = new FadeManager();
    }

    @Test
    void testFadeManagerSnapshotRoundTripFadeToBlack() {
        // Start a fade to black
        fadeManager.startFadeToBlack(null, 5, 0);

        // Simulate a few frames of fade
        for (int i = 0; i < 3; i++) {
            fadeManager.update();
        }

        // Capture snapshot
        FadeManagerSnapshot snapshot = fadeManager.capture();

        // Mutate state by continuing fade
        for (int i = 0; i < 5; i++) {
            fadeManager.update();
        }

        // Restore from snapshot
        fadeManager.restore(snapshot);

        // Verify state matches captured values
        assertEquals(snapshot.state(), fadeManager.getState());
        assertEquals(snapshot.frameCount(), fadeManager.getFrameCount());
        assertEquals(snapshot.fadeR(), fadeManager.getFadeColor()[0], 0.001f);
        assertEquals(snapshot.fadeG(), fadeManager.getFadeColor()[1], 0.001f);
        assertEquals(snapshot.fadeB(), fadeManager.getFadeColor()[2], 0.001f);
    }

    @Test
    void testFadeManagerSnapshotRoundTripFadeToWhite() {
        // Start a fade to white
        fadeManager.startFadeToWhite(null);

        // Simulate some frames
        for (int i = 0; i < 5; i++) {
            fadeManager.update();
        }

        // Capture snapshot
        FadeManagerSnapshot snapshot = fadeManager.capture();

        // Mutate state
        fadeManager.cancel();

        // Restore from snapshot
        fadeManager.restore(snapshot);

        // Verify state
        assertEquals(FadeManager.FadeState.FADING_TO_WHITE, fadeManager.getState());
        assertEquals(FadeManager.FadeType.WHITE, fadeManager.getFadeType());
        assertEquals(5, fadeManager.getFrameCount());
    }

    @Test
    void testFadeManagerSnapshotKey() {
        assertEquals("fademanager", fadeManager.key());
    }

    @Test
    void testFadeManagerNoneState() {
        // Default state should be NONE
        FadeManagerSnapshot snapshot = fadeManager.capture();
        assertEquals(FadeManager.FadeState.NONE, snapshot.state());

        // Mutate to something else
        fadeManager.startFadeToBlack(null);

        // Restore to NONE
        fadeManager.restore(snapshot);
        assertEquals(FadeManager.FadeState.NONE, fadeManager.getState());
    }

    @Test
    void testFadeManagerHoldDuration() {
        fadeManager.startFadeToBlack(null, 10);

        // Advance to hold state
        for (int i = 0; i < 22; i++) {
            fadeManager.update();
        }

        FadeManagerSnapshot snapshot = fadeManager.capture();

        // Mutate hold duration
        fadeManager.cancel();

        // Restore
        fadeManager.restore(snapshot);

        assertEquals(10, fadeManager.capture().holdDuration());
    }

    @Test
    void restoredFadeSnapshotDoesNotAdvanceOnFirstUpdateAfterRestore() {
        fadeManager.startFadeToBlack(null, 0, 0);
        for (int i = 0; i < 5; i++) {
            fadeManager.update();
        }
        FadeManagerSnapshot snapshot = fadeManager.capture();

        fadeManager.update();
        fadeManager.restore(snapshot);
        fadeManager.update();

        assertEquals(snapshot.frameCount(), fadeManager.getFrameCount());
        assertArrayEquals(
                new float[] {snapshot.fadeR(), snapshot.fadeG(), snapshot.fadeB()},
                fadeManager.getFadeColor(),
                0.001f);

        fadeManager.update();

        assertEquals(snapshot.frameCount() + 1, fadeManager.getFrameCount());
    }

    @Test
    void reversePresentationFreezesDisplayDrivenFadeUpdatesUntilReleased() {
        fadeManager.startFadeFromBlack(null);
        for (int i = 0; i < 5; i++) {
            fadeManager.update();
        }
        FadeManagerSnapshot rewindFrame = fadeManager.capture();

        fadeManager.beginReversePresentation();
        for (int i = 0; i < 3; i++) {
            fadeManager.restore(rewindFrame);
            fadeManager.update();
            assertEquals(rewindFrame.frameCount(), fadeManager.getFrameCount());
            assertArrayEquals(
                    new float[] {rewindFrame.fadeR(), rewindFrame.fadeG(), rewindFrame.fadeB()},
                    fadeManager.getFadeColor(),
                    0.001f);
        }

        fadeManager.endReversePresentation();
        fadeManager.update();

        assertEquals(rewindFrame.frameCount() + 1, fadeManager.getFrameCount());
    }
}
