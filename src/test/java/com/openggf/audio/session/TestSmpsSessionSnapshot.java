package com.openggf.audio.session;

import com.openggf.audio.driver.SmpsDriver;
import com.openggf.audio.presentation.AudioPresentationDependencyResolver;
import com.openggf.audio.presentation.AudioPresentationSnapshot;
import com.openggf.audio.presentation.AudioPresentationSourceFactory;
import com.openggf.audio.presentation.AudioVoiceRegistry;
import com.openggf.audio.presentation.DecodedPcm;
import com.openggf.audio.presentation.PresentationVoiceSnapshot;
import com.openggf.audio.rewind.AudioSourceDescriptor;
import com.openggf.audio.rewind.SmpsDriverSnapshot;
import com.openggf.audio.smps.SmpsCoordFlagHandlerOwner;
import com.openggf.audio.smps.SmpsCoordFlagRuntimeState;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestSmpsSessionSnapshot {
    @Test
    void inertSnapshotContainsOnlySessionAndOnePhysicalState() {
        SmpsPhysicalPolicy policy =
                LegacyCompatibilitySmpsPhysicalPolicy.INSTANCE;
        SmpsPhysicalDevice.Settings settings =
                SmpsSessionTestFixtures.settings();
        SmpsSessionProfileFingerprint profile =
                new SmpsSessionProfileFingerprint(
                        "sonic-test", 11, policy.identity(), settings);
        SmpsSessionTestFixtures.RecordingObserver observer =
                new SmpsSessionTestFixtures.RecordingObserver();
        SmpsDriverSession session = new SmpsDriverSession(
                settings, policy, observer, profile,
                SmpsDriverSessionConfiguration.DEFAULT);

        SmpsDriverSessionSnapshot snapshot = session.captureSnapshot();

        assertTrue(observer.events().isEmpty());
        assertEquals(false, snapshot.initialized());
        assertEquals(SmpsPendingGlobalCommand.NONE,
                snapshot.pendingGlobalCommand());
        assertSame(profile, snapshot.profile());
        assertNull(snapshot.selectedDacSource());
        assertEquals(settings, snapshot.physical().settings());
    }

    @Test
    void selectedDacProvenanceIsSeparateFromThePhysicalSnapshot() {
        SmpsDriverSession session = SmpsSessionTestFixtures.session(
                new SmpsSessionTestFixtures.RecordingObserver());
        SmpsPhysicalPort port = session.openTestEpoch(
                SmpsSessionTestFixtures.owner(8));

        port.selectDac(new SmpsDacSelection(
                SmpsSessionTestFixtures.source(9),
                SmpsSessionTestFixtures.dac()));
        SmpsDriverSessionSnapshot snapshot = session.captureSnapshot();

        assertEquals(SmpsSessionTestFixtures.source(9),
                snapshot.selectedDacSource());
        assertEquals(SmpsSessionTestFixtures.settings(),
                snapshot.physical().settings());
    }

    @Test
    void presentationSnapshotHasOnePhysicalSnapshotAndFingerprint() {
        SmpsDriverSession session = SmpsSessionTestFixtures.session(
                new SmpsSessionTestFixtures.RecordingObserver());
        session.install();
        SmpsCoordFlagHandlerOwner handlers = new SmpsCoordFlagHandlerOwner(
                new SmpsCoordFlagRuntimeState());
        AudioPresentationSourceFactory factory =
                new AudioPresentationSourceFactory(
                        () -> true, handlers,
                        AudioPresentationSourceFactory.Settings.defaults(),
                        session);
        AudioVoiceRegistry registry = new AudioVoiceRegistry(
                factory, factory, handlers, ignored -> { }, session);

        AudioPresentationSnapshot snapshot = registry.snapshot();

        assertSame(session.captureSnapshot().profile(),
                snapshot.smpsSession().profile());
        assertEquals(session.captureSnapshot().physical(),
                snapshot.smpsSession().physical());
        assertEquals(session.captureLogicalSnapshot(),
                snapshot.smpsLogical());
        assertTrue(snapshot.voices().isEmpty());
        assertFalse(Arrays.stream(
                        SmpsDriverSnapshot.class.getRecordComponents())
                .anyMatch(component -> component.getType()
                        == SmpsPhysicalDevice.Snapshot.class));
    }

    @Test
    void prepareCommitDiscardAndFailedRestoreAreWriteFreeAndAtomic() {
        SmpsSessionTestFixtures.RecordingObserver observer =
                new SmpsSessionTestFixtures.RecordingObserver();
        SmpsDriverSession session = SmpsSessionTestFixtures.session(observer);
        session.install();
        Object identity = session.logicalDriverForTesting();
        SmpsPhysicalPort port = session.openTestEpoch(
                SmpsSessionTestFixtures.owner(31));
        port.selectDac(new SmpsDacSelection(
                SmpsSessionTestFixtures.source(32),
                SmpsSessionTestFixtures.dac()));
        session.closeTestEpoch(port.epoch());
        SmpsDriverSessionSnapshot physical = session.captureSnapshot();
        SmpsDriverSnapshot logical = session.captureLogicalSnapshot();
        observer.clear();

        SmpsDriverSession.PreparedRestore discarded = session.prepareRestore(
                physical, logical, ignored -> SmpsSessionTestFixtures.dac());
        assertTrue(observer.events().isEmpty());
        assertEquals(logical, session.captureLogicalSnapshot());
        assertEquals(physical, session.captureSnapshot());
        assertTrue(discarded.savedOverrides().isEmpty());

        SmpsDriverSession.PreparedRestore committed = session.prepareRestore(
                physical, logical, ignored -> SmpsSessionTestFixtures.dac());
        session.commitRestore(committed);
        assertTrue(observer.events().isEmpty());
        assertSame(identity, session.logicalDriverForTesting());
        assertEquals(logical, session.captureLogicalSnapshot());
        assertEquals(physical, session.captureSnapshot());

        var beforePhysical = SmpsSessionTestFixtures.json(
                session.captureSnapshot());
        var beforeLogical = session.captureLogicalSnapshot();
        assertThrows(IllegalStateException.class,
                () -> session.prepareRestore(physical, logical,
                        ignored -> {
                            throw new IllegalStateException(
                                    "dependency failed");
                        }));
        assertTrue(observer.events().isEmpty());
        assertEquals(beforePhysical, SmpsSessionTestFixtures.json(
                session.captureSnapshot()));
        assertEquals(beforeLogical, session.captureLogicalSnapshot());
        assertSame(identity, session.logicalDriverForTesting());
    }

    @Test
    void staleProfileRestoreFailsBeforeMutation() {
        SmpsSessionTestFixtures.RecordingObserver observer =
                new SmpsSessionTestFixtures.RecordingObserver();
        SmpsDriverSession session = SmpsSessionTestFixtures.session(observer);
        session.install();
        SmpsDriverSessionSnapshot current = session.captureSnapshot();
        SmpsSessionProfileFingerprint stale =
                new SmpsSessionProfileFingerprint(
                        current.profile().baseGameId(),
                        current.profile().sourceGeneration() + 1,
                        current.profile().physicalPolicyId(),
                        current.profile().settings());
        SmpsDriverSessionSnapshot staleSnapshot =
                new SmpsDriverSessionSnapshot(
                        current.initialized(), current.pendingGlobalCommand(),
                        stale, current.selectedDacSource(),
                        current.speedShoesEnabled(), current.speedMultiplier(),
                        current.ringLeft(), current.musicFmDacTrackCount(), current.segaPcmTransport(),
                        current.physical());
        var before = SmpsSessionTestFixtures.json(current);
        observer.clear();

        assertThrows(IllegalArgumentException.class,
                () -> session.prepareRestore(
                        staleSnapshot, session.captureLogicalSnapshot(),
                        ignored -> SmpsSessionTestFixtures.dac()));

        assertTrue(observer.events().isEmpty());
        assertEquals(before, SmpsSessionTestFixtures.json(
                session.captureSnapshot()));
    }
}
