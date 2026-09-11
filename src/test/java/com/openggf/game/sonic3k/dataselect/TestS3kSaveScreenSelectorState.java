package com.openggf.game.sonic3k.dataselect;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS3kSaveScreenSelectorState {

    @Test
    void selector_rightMove_advancesEntryAndQueuesSlotMachineSfx() {
        RecordingSfxRecorder recorder = new RecordingSfxRecorder();
        S3kSaveScreenSelectorState selector = new S3kSaveScreenSelectorState(recorder::accept);

        boolean moved = selector.moveRight(false);

        assertTrue(moved);
        assertEquals(1, selector.currentEntry());
        assertEquals(List.of(0xB7), recorder.sfxIds());
    }

    @Test
    void selector_moveInDeleteMode_usesSmallBumpersSfx() {
        RecordingSfxRecorder recorder = new RecordingSfxRecorder();
        S3kSaveScreenSelectorState selector = new S3kSaveScreenSelectorState(recorder::accept);
        selector.setCurrentEntry(2);

        boolean moved = selector.moveLeft(true);

        assertTrue(moved);
        assertEquals(1, selector.currentEntry());
        assertEquals(List.of(0x7B), recorder.sfxIds());
    }

    @Test
    void selector_rightMove_tweensAcrossThirteenFramesInsteadOfSnapping() {
        RecordingSfxRecorder recorder = new RecordingSfxRecorder();
        S3kSaveScreenSelectorState selector = new S3kSaveScreenSelectorState(recorder::accept);

        selector.moveRight(false);

        assertEquals(1, selector.currentEntry());
        assertEquals(0xB0, selector.selectorBiasedX(),
                "first movement frame should only advance one 8px step from the no-save position");

        selector.advanceFrame();
        assertEquals(0xB8, selector.selectorBiasedX());

        for (int i = 0; i < 11; i++) {
            selector.advanceFrame();
        }

        assertEquals(0x110, selector.selectorBiasedX(),
                "after 13 movement frames the selector should settle on slot 1");
        assertEquals(0, selector.cameraX());
    }

    private static final class RecordingSfxRecorder {
        private final List<Integer> sfxIds = new ArrayList<>();

        void accept(int sfxId) {
            sfxIds.add(sfxId);
        }

        List<Integer> sfxIds() {
            return List.copyOf(sfxIds);
        }
    }
}
