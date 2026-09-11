package com.openggf.sprites.playable;

import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestObjectControlState {

    @Test
    void noneAppliesNoObjectControl() {
        assertState(ObjectControlState.none(), false, false, false, false);
    }

    @Test
    void nativeBit7FullControlSuppressesMovementAndTouch() {
        assertState(ObjectControlState.nativeBit7FullControl(), true, false, true, true);
    }

    @Test
    void nativeBits0To6CpuAllowedMovementSuppressedAllowsCpuAndTouch() {
        assertState(ObjectControlState.nativeBits0To6CpuAllowedMovementSuppressed(), true, true, true, false);
    }

    @Test
    void nativeBits0To6CpuAllowedMovementActiveAllowsCpuAndTouch() {
        assertState(ObjectControlState.nativeBits0To6CpuAllowedMovementActive(), true, true, false, false);
    }

    @Test
    void movementSuppressedOnlyDoesNotSetObjectControlOrTouchSuppression() {
        assertState(ObjectControlState.movementSuppressedOnly(), false, false, true, false);
    }

    @Test
    void engineScriptedTouchSuppressedMovementActiveSuppressesTouchOnly() {
        assertState(ObjectControlState.engineScriptedTouchSuppressedMovementActive(), true, false, false, true);
    }

    @Test
    void engineScriptedPreserveCpuMovementSuppressedKeepsPriorCpuAllowedState() {
        TestablePlayableSprite cpuAllowed = new TestablePlayableSprite("tails", (short) 0, (short) 0);
        cpuAllowed.setObjectControlAllowsCpu(true);
        cpuAllowed.applyObjectControlState(ObjectControlState.engineScriptedPreserveCpuMovementSuppressed());

        TestablePlayableSprite cpuSuppressed = new TestablePlayableSprite("sonic", (short) 0, (short) 0);
        cpuSuppressed.setObjectControlAllowsCpu(false);
        cpuSuppressed.applyObjectControlState(ObjectControlState.engineScriptedPreserveCpuMovementSuppressed());

        assertAll(
                () -> assertTrue(cpuAllowed.isObjectControlled(), "cpuAllowed objectControlled"),
                () -> assertTrue(cpuAllowed.isObjectControlAllowsCpu(), "cpuAllowed objectControlAllowsCpu"),
                () -> assertTrue(cpuAllowed.isObjectControlSuppressesMovement(), "cpuAllowed movement suppressed"),
                () -> assertFalse(cpuAllowed.isTouchResponseSuppressedByObjectControl(), "cpuAllowed touch suppressed"),
                () -> assertTrue(cpuSuppressed.isObjectControlled(), "cpuSuppressed objectControlled"),
                () -> assertFalse(cpuSuppressed.isObjectControlAllowsCpu(), "cpuSuppressed objectControlAllowsCpu"),
                () -> assertTrue(cpuSuppressed.isObjectControlSuppressesMovement(), "cpuSuppressed movement suppressed"),
                () -> assertTrue(cpuSuppressed.isTouchResponseSuppressedByObjectControl(), "cpuSuppressed touch suppressed")
        );
    }

    @Test
    void noneClearsCpuAllowedAndMovementSuppressionFromPriorNativeControl() {
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0, (short) 0);

        player.applyObjectControlState(ObjectControlState.nativeBits0To6CpuAllowedMovementSuppressed());
        player.applyObjectControlState(ObjectControlState.none());

        assertAll(
                () -> assertFalse(player.isObjectControlled(), "objectControlled"),
                () -> assertFalse(player.isObjectControlAllowsCpu(), "objectControlAllowsCpu"),
                () -> assertFalse(player.isObjectControlSuppressesMovement(), "objectControlSuppressesMovement"),
                () -> assertFalse(player.isTouchResponseSuppressedByObjectControl(), "touchSuppressed")
        );
    }

    @Test
    void movementSuppressedOnlyClearsCpuAllowedFromPriorNativeControl() {
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0, (short) 0);

        player.applyObjectControlState(ObjectControlState.nativeBits0To6CpuAllowedMovementSuppressed());
        player.applyObjectControlState(ObjectControlState.movementSuppressedOnly());

        assertAll(
                () -> assertFalse(player.isObjectControlled(), "objectControlled"),
                () -> assertFalse(player.isObjectControlAllowsCpu(), "objectControlAllowsCpu"),
                () -> assertTrue(player.isObjectControlSuppressesMovement(), "objectControlSuppressesMovement"),
                () -> assertFalse(player.isTouchResponseSuppressedByObjectControl(), "touchSuppressed")
        );
    }

    private static void assertState(ObjectControlState state,
                                    boolean objectControlled,
                                    boolean allowsCpu,
                                    boolean suppressesMovement,
                                    boolean suppressesTouch) {
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0, (short) 0);

        player.applyObjectControlState(state);

        assertAll(
                () -> assertBoolean(objectControlled, player.isObjectControlled(), "objectControlled"),
                () -> assertBoolean(allowsCpu, player.isObjectControlAllowsCpu(), "objectControlAllowsCpu"),
                () -> assertBoolean(suppressesMovement, player.isObjectControlSuppressesMovement(), "objectControlSuppressesMovement"),
                () -> assertBoolean(suppressesTouch, player.isTouchResponseSuppressedByObjectControl(), "touchSuppressed")
        );
    }

    private static void assertBoolean(boolean expected, boolean actual, String label) {
        if (expected) {
            assertTrue(actual, label);
        } else {
            assertFalse(actual, label);
        }
    }
}
