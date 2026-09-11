package com.openggf.tests;

import com.openggf.game.GameServices;
import com.openggf.game.session.SessionManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runtime proof that {@link HeadlessStateTeardownExtension} is active without
 * any {@code @ExtendWith}: this class declares no extension, so its
 * {@code beforeAll} can only have run through the auto-detected service
 * registration that the ordinary suite and {@code -Pguards} both use.
 */
class TestHeadlessStateTeardownExtension {

    @Test
    void globalRegistrationObservedThisClass() {
        assertTrue(HeadlessStateTeardownExtension.observed(getClass()),
                "HeadlessStateTeardownExtension did not run beforeAll for "
                        + getClass().getName()
                        + "; check junit-platform.properties and the "
                        + "META-INF/services registration");
    }

    @Test
    void resetAllLeavesNoLevelRegistered() {
        TestEnvironment.activeGameplayMode();
        TestEnvironment.resetAll();
        assertNull(GameServices.levelOrNull() == null
                        ? null
                        : GameServices.levelOrNull().getCurrentLevel(),
                "resetAll must leave no loaded level for the next class");
        assertTrue(SessionManager.getCurrentGameplayMode() != null,
                "resetAll reopens a clean gameplay session");
    }
}
