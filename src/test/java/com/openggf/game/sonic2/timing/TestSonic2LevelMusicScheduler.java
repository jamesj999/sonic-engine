package com.openggf.game.sonic2.timing;

import com.openggf.game.rewind.RewindRegistry;
import org.junit.jupiter.api.Test;

import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestSonic2LevelMusicScheduler {
    @Test
    void realRegistryRestoreReplaysTheExactRemainingTitleCardCountdown() {
        Sonic2LevelMusicScheduler scheduler = new Sonic2LevelMusicScheduler();
        RewindRegistry registry = new RewindRegistry();
        registry.register(scheduler);
        scheduler.arm(0x81, 3);
        assertEquals(OptionalInt.empty(), scheduler.serviceVBlank());
        var middle = registry.capture();

        assertEquals(OptionalInt.empty(), scheduler.serviceVBlank());
        assertEquals(OptionalInt.of(0x81), scheduler.serviceVBlank());
        assertFalse(scheduler.pending());

        registry.restore(middle);
        assertEquals(2, scheduler.capture().remainingVBlanks());
        assertEquals(OptionalInt.empty(), scheduler.serviceVBlank());
        assertEquals(OptionalInt.of(0x81), scheduler.serviceVBlank());
    }

    @Test
    void restoringAfterReleaseDoesNotRepublish() {
        Sonic2LevelMusicScheduler scheduler = new Sonic2LevelMusicScheduler();
        RewindRegistry registry = new RewindRegistry();
        registry.register(scheduler);
        scheduler.arm(0x81, 1);
        assertTrue(scheduler.serviceVBlank().isPresent());
        var released = registry.capture();

        scheduler.arm(0x82, 1);
        registry.restore(released);

        assertFalse(scheduler.pending());
        assertEquals(OptionalInt.empty(), scheduler.serviceVBlank());
    }
}
