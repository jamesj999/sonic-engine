package com.openggf.audio.session;

import com.openggf.audio.synth.ChipWriteObserver;
import com.openggf.game.sonic3k.audio.Sonic3kSmpsPhysicalPolicy;
import com.openggf.game.sonic3k.audio.Sonic3kStatefulCommandPolicy;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestSmpsStatefulCommandPolicy {
    @Test
    void configuredInertPolicyCapturesWithoutChangingSessionStateOrObservers() {
        SmpsPhysicalPolicy physical =
                LegacyCompatibilitySmpsPhysicalPolicy.INSTANCE;
        SmpsPhysicalDevice.Settings settings = SmpsSessionTestFixtures.settings();
        SmpsStatefulCommandPolicy policy = () ->
                new SmpsStatefulCommandPolicy.Identity("host-e4-phase-one");
        SmpsDriverSessionConfiguration configuration =
                new SmpsDriverSessionConfiguration(policy);
        SmpsSessionProfileFingerprint fingerprint =
                new SmpsSessionProfileFingerprint("test", 7,
                        physical.identity(), settings, policy.identity());
        AtomicInteger writes = new AtomicInteger();
        SmpsDriverSession session = new SmpsDriverSession(settings, physical,
                new ChipWriteObserver() {
                    @Override public void onYm2612Write(int port, int register, int value) {
                        writes.incrementAndGet();
                    }
                    @Override public void onPsgWrite(int value) {
                        writes.incrementAndGet();
                    }
                }, fingerprint, configuration);
        session.install();
        Object physicalIdentity = session.physicalIdentityForTesting();
        Object driverIdentity = session.logicalDriverForTesting();
        SmpsDriverSessionSnapshot beforeSession = session.captureSnapshot();
        var beforeLogical = session.captureLogicalSnapshot();
        writes.set(0);

        SmpsStatefulCommandOperation prepared = session.prepareStatefulCommand(
                new SmpsSessionCommand.StopSmpsSfx());

        assertFalse(prepared.handled(),
                "Phase 1 must not alter the established E4 command dispatch");
        assertEquals(0, writes.get());
        assertEquals(beforeSession, session.captureSnapshot());
        assertEquals(beforeLogical, session.captureLogicalSnapshot());
        assertSame(physicalIdentity, session.physicalIdentityForTesting());
        assertSame(driverIdentity, session.logicalDriverForTesting());
        session.close();
    }

    @Test
    void policyIdentityIsPartOfTheRestoreCompatibilityFingerprint() {
        SmpsPhysicalPolicy physical =
                LegacyCompatibilitySmpsPhysicalPolicy.INSTANCE;
        SmpsPhysicalDevice.Settings settings = SmpsSessionTestFixtures.settings();
        SmpsStatefulCommandPolicy first = () ->
                new SmpsStatefulCommandPolicy.Identity("host-a");
        SmpsStatefulCommandPolicy second = () ->
                new SmpsStatefulCommandPolicy.Identity("host-b");
        SmpsDriverSession session = new SmpsDriverSession(settings, physical,
                ChipWriteObserver.NONE, new SmpsSessionProfileFingerprint(
                        "test", 7, physical.identity(), settings,
                        first.identity()),
                new SmpsDriverSessionConfiguration(first));
        session.install();
        SmpsDriverSessionSnapshot captured = session.captureSnapshot();
        SmpsDriverSessionSnapshot incompatible = new SmpsDriverSessionSnapshot(
                captured.initialized(), captured.pendingGlobalCommand(),
                new SmpsSessionProfileFingerprint("test", 7,
                        physical.identity(), settings, second.identity()),
                captured.selectedDacSource(), captured.speedShoesEnabled(),
                captured.speedMultiplier(), captured.ringLeft(), captured.musicFmDacTrackCount(),
                captured.segaPcmTransport(), captured.physical());

        assertThrows(IllegalArgumentException.class,
                () -> session.prepareRestore(incompatible,
                        session.captureLogicalSnapshot(), ignored -> null));
        session.close();
    }

    @Test
    void s3kHostPolicyClaimsE4WhileOtherHostsRemainInert() {
        SmpsPhysicalPolicy physical = Sonic3kSmpsPhysicalPolicy.INSTANCE;
        SmpsPhysicalDevice.Settings settings = SmpsSessionTestFixtures.settings();
        SmpsStatefulCommandPolicy policy = Sonic3kStatefulCommandPolicy.INSTANCE;
        SmpsDriverSession session = new SmpsDriverSession(settings, physical,
                ChipWriteObserver.NONE, new SmpsSessionProfileFingerprint(
                        "s3k-host", 7, physical.identity(), settings,
                        policy.identity()),
                new SmpsDriverSessionConfiguration(policy));
        session.install();

        SmpsStatefulCommandOperation prepared = session.prepareStatefulCommand(
                new SmpsSessionCommand.StopSmpsSfx());

        assertTrue(prepared.handled());
        assertFalse(prepared.rejected());
        assertEquals(SmpsStatefulCommandOperation.Handling.STOP_SMPS_SFX,
                prepared.handling());
        assertTrue(prepared.writes().writes().isEmpty(),
                "an empty seven-slot projection still owns E4 without guessing writes");
        session.close();
    }
}
