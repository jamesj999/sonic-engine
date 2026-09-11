package com.openggf.game.sonic3k.titlecard;

import com.openggf.data.RomManager;
import com.openggf.game.GameServices;
import com.openggf.game.RuntimeArtAdmissionLease;
import com.openggf.game.RuntimeArtAdmissionOwnerKind;
import com.openggf.game.sonic3k.Sonic3kGameModule;
import com.openggf.game.sonic3k.Sonic3kObjectArtProvider;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests bonus mode behavior in Sonic3kTitleCardManager.
 * These tests need a session-owned admission stub but no ROM or OpenGL; they
 * otherwise exercise the title-card state machine only.
 */
public class TestSonic3kBonusTitleCard {

    private Sonic3kTitleCardManager manager;

    @BeforeEach
    public void setUp() throws Exception {
        TestEnvironment.configureGameModuleFixture(SonicGame.SONIC_3K);
        // This state-machine fixture does not service the KosM queues. Keep a
        // ROM left by an earlier class in the reused fork from starting them.
        RomManager.getInstance().setRom(null);
        Sonic3kGameModule module = (Sonic3kGameModule) GameServices.module();
        Field providerField = Sonic3kGameModule.class.getDeclaredField("objectArtProvider");
        providerField.setAccessible(true);
        providerField.set(module, new LeaseOnlyObjectArtProvider());
        manager = new Sonic3kTitleCardManager();
        manager.reset();
    }

    @Test
    public void initializeBonusSetsSlideInState() {
        manager.initializeBonus();
        assertFalse(manager.isComplete(), "Should not be complete after init");
        assertFalse(manager.shouldReleaseControl(), "Should not release control during slide-in");
    }

    @Test
    public void bonusModeReleasesControlOnExit() {
        manager.initializeBonus();

        // Advance through SLIDE_IN (elements slide from right to target)
        // Max distance is 360-168=192 at 16px/frame = 12 frames
        for (int i = 0; i < 20; i++) {
            manager.update();
        }
        // Advance through DISPLAY hold (90 frames, fade runs in last 22)
        for (int i = 0; i < 90; i++) {
            manager.update();
        }
        // Should now be in EXIT phase â€” control released
        assertTrue(manager.shouldReleaseControl(), "Should release control during exit");
    }

    @Test
    public void bonusModeCompletesAfterFullAnimation() {
        manager.initializeBonus();
        // Run enough frames for full animation cycle:
        // SLIDE_IN (~12 frames) + DISPLAY (90 frames) + EXIT (~10 frames)
        for (int i = 0; i < 150; i++) {
            manager.update();
        }
        assertTrue(manager.isComplete(), "Should be complete after full animation");
    }

    @Test
    public void normalModeStillWorksAfterBonusMode() {
        // First run bonus mode
        manager.initializeBonus();
        for (int i = 0; i < 150; i++) {
            manager.update();
        }
        assertTrue(manager.isComplete());

        // Then run normal mode
        manager.initialize(0, 0); // AIZ act 1
        assertFalse(manager.isComplete(), "Should not be complete after normal init");
    }

    @Test
    public void shouldNotRunPlayerPhysicsInBonusMode() {
        manager.initializeBonus();
        assertFalse(manager.shouldRunPlayerPhysics());
    }

    @Test
    public void inLevelEndFlagPredictionUsesParentObservationTick() {
        manager.initializeInLevel(0, 1);

        for (int i = 0; i < 200 && !manager.willSetInLevelEndOfLevelFlagThisUpdate(); i++) {
            manager.update();
        }

        assertTrue(manager.willSetInLevelEndOfLevelFlagThisUpdate(),
                "AIZ camera release must wait until the parent observes that all title children are gone");
        assertEquals(13, manager.getExitPhaseCounter(),
                "native render bounds remove the last title child on phase 12 and the parent observes it on 13");
        assertFalse(manager.isComplete(), "the parent completion dispatch remains one manager tick later");
    }

    private static final class LeaseOnlyObjectArtProvider
            extends Sonic3kObjectArtProvider {
        private final RuntimeArtAdmissionLease lease = new RuntimeArtAdmissionLease(
                1, 1, 1, RuntimeArtAdmissionOwnerKind.TITLE_OWNER);

        @Override
        public RuntimeArtAdmissionLease bindPendingRuntimeArtAdmission(
                RuntimeArtAdmissionOwnerKind ownerKind) {
            assertEquals(RuntimeArtAdmissionOwnerKind.TITLE_OWNER, ownerKind);
            return lease;
        }

        @Override
        public RuntimeArtAdmissionLease rebindRuntimeArtAdmission(
                long leaseId,
                RuntimeArtAdmissionOwnerKind ownerKind) {
            assertEquals(lease.id(), leaseId);
            assertEquals(RuntimeArtAdmissionOwnerKind.TITLE_OWNER, ownerKind);
            return lease;
        }

        @Override
        public void consumeRuntimeArtAdmission(
                RuntimeArtAdmissionLease consumed,
                RuntimeArtAdmissionOwnerKind ownerKind) {
            assertEquals(lease, consumed);
            assertEquals(RuntimeArtAdmissionOwnerKind.TITLE_OWNER, ownerKind);
        }
    }
}
