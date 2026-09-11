package com.openggf.game.sonic1;

import com.openggf.game.GameServices;
import com.openggf.game.rewind.CompositeSnapshot;
import com.openggf.game.rewind.RewindRegistry;
import com.openggf.tests.TestEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression coverage for the S1 switch RAM's production rewind registration.
 */
class TestSonic1SwitchStateRewindAdapter {
    @BeforeEach
    void setUp() {
        TestEnvironment.configureGameModuleFixture(new Sonic1GameModule());
    }

    @AfterEach
    void tearDown() {
        TestEnvironment.resetAll();
    }

    @Test
    void productionRegistryRoundTripRestoresAllSwitchBytesBeforeConsumersReadThem() {
        RewindRegistry rewindRegistry = TestEnvironment.activeGameplayMode().getRewindRegistry();
        assertTrue(rewindRegistry.capture().containsKey("s1-switch-state"),
                "S1 gameplay must register switch RAM in the production rewind registry");

        Sonic1SwitchManager switchManager = GameServices.module().getGameService(Sonic1SwitchManager.class);
        for (int index = 0; index < 16; index++) {
            switchManager.setBit(index, index & 1);
            switchManager.setBit(index, 7 - (index & 1));
        }
        switchManager.clearBit(4, 0);
        switchManager.clearBit(4, 7);
        CompositeSnapshot snapshot = rewindRegistry.capture();

        switchManager.reset();
        switchManager.setBit(0, 0);
        rewindRegistry.restore(snapshot);

        for (int index = 0; index < 16; index++) {
            int expected = index == 4
                    ? 0
                    : (1 << (index & 1)) | (1 << (7 - (index & 1)));
            assertEquals(expected, switchManager.getRaw(index) & 0xFF,
                    "restored switch byte " + index + " must be visible to consumers immediately");
            assertEquals(expected != 0, switchManager.isPressed(index),
                    "consumer-facing pressed gate must match restored switch byte " + index);
        }

        // A second divergent write must not make the first keyframe one-shot.
        switchManager.reset();
        switchManager.setBit(3, 0);
        switchManager.setBit(12, 7);
        rewindRegistry.restore(snapshot);
        for (int index = 0; index < 16; index++) {
            int expected = index == 4
                    ? 0
                    : (1 << (index & 1)) | (1 << (7 - (index & 1)));
            assertEquals(expected, switchManager.getRaw(index) & 0xFF,
                    "repeated restore must retain switch byte " + index);
        }
    }

    @Test
    void switchSnapshotAndMissingSnapshotPathResetWithoutAliasing() {
        Sonic1SwitchManager switchManager = GameServices.module()
                .getGameService(Sonic1SwitchManager.class);
        switchManager.setBit(0, 0);
        switchManager.setBit(15, 7);
        Sonic1SwitchManager.Snapshot snapshot = switchManager.captureRewindState();

        byte[] callerCopy = snapshot.switchState();
        callerCopy[0] = (byte) 0xFF;
        assertEquals(1, snapshot.switchState()[0] & 0xFF,
                "snapshot accessor must not expose mutable snapshot storage");

        switchManager.reset();
        new Sonic1SwitchStateRewindAdapter().restore(snapshot);
        assertEquals(1, switchManager.getRaw(0) & 0xFF,
                "restoring after mutating an accessor copy must retain captured bit 0");
        assertEquals(0x80, switchManager.getRaw(15) & 0xFF,
                "restoring after mutating an accessor copy must retain captured bit 7");

        RewindRegistry adapterOnlyRegistry = new RewindRegistry();
        adapterOnlyRegistry.register(new Sonic1SwitchStateRewindAdapter());
        adapterOnlyRegistry.restore(new CompositeSnapshot(Map.of()));
        for (int index = 0; index < 16; index++) {
            assertEquals(0, switchManager.getRaw(index),
                    "missing switch snapshot must reset byte " + index);
        }
    }
}
