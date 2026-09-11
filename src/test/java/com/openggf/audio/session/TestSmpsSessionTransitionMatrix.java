package com.openggf.audio.session;

import com.openggf.audio.AudioManager;
import com.openggf.audio.AudioTestFixtures;
import com.openggf.audio.driver.SmpsDriver;
import com.openggf.audio.driver.SmpsDriverServiceObserver;
import com.openggf.audio.rewind.SmpsDriverSnapshot;
import com.openggf.audio.rewind.SmpsSourceDescriptor;
import com.openggf.audio.smps.SmpsSequencer;
import com.openggf.audio.smps.SmpsSequencerConfig;
import com.openggf.audio.smps.SmpsLoadReadiness;
import com.openggf.game.sonic2.audio.Sonic2SmpsCompatibilityPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestSmpsSessionTransitionMatrix {

    @Test
    void segaRestoresDacDispositionAfterCompletedMusicAndRewind() {
        var writes = new SmpsSessionTestFixtures.RecordingObserver();
        var policy = Sonic2SmpsCompatibilityPolicy.INSTANCE;
        var settings = SmpsSessionTestFixtures.settings();
        var session = new SmpsDriverSession(settings, policy, writes,
                new SmpsSessionProfileFingerprint("s2", 1, policy.identity(), settings),
                SmpsDriverSessionConfiguration.DEFAULT);
        session.install();
        // Empty tracks complete immediately; the prepared six-channel header
        // still sets the driver's persistent FM6/DAC disposition on load.
        var prepared = activation(0x81, SourcePolicy.S2, false);
        session.queueActivation(new PreparedSmpsMusicActivation(
                new SmpsMusicActivation(prepared.activation().source(), 6),
                prepared.incomingMusic(), prepared.logicalPolicy(), prepared.selectedDac()));
        session.serviceForward();
        assertTrue(session.captureLogicalSnapshot().sequencers().isEmpty());
        var snapshot = session.captureSnapshot();
        var logical = session.captureLogicalSnapshot();
        session.applyCommand(new SmpsSessionCommand.HardReset());
        session.commitRestore(session.prepareRestore(snapshot, logical, ignored -> SmpsSessionTestFixtures.dac()));
        session.beginSegaPcmTransport(new byte[] {(byte) 0x80});
        writes.clear();
        session.renderFrames(new short[64], 0, 32);
        assertEquals("YM:0:2B:80", writes.events().getLast());
    }

    @Test
    void delayedLoadCrossingBecomesOneRewindableInFlightService() {
        SmpsSessionTestFixtures.RecordingObserver writes =
                new SmpsSessionTestFixtures.RecordingObserver();
        SmpsDriverSession session = SmpsSessionTestFixtures.session(writes);
        List<Long> serviceEnds = new ArrayList<>();
        session.setDriverServiceObserver(new SmpsDriverServiceObserver() {
            @Override
            public void onServiceEnd(ServiceEvent event,
                    SmpsDriverSnapshot snapshot) {
                serviceEnds.add(event.ordinal());
            }
        });
        session.install();
        PreparedSmpsMusicActivation base = activation(
                0x81, SourcePolicy.S2, false);
        session.queueActivation(new PreparedSmpsMusicActivation(
                base.activation(), base.incomingMusic(), base.logicalPolicy(),
                base.selectedDac(), twoPresentationReadiness()));
        int loadSilenceWrites = writes.events().size();

        assertEquals(SmpsServiceOutcome.LOAD_PENDING,
                session.serviceForward());
        assertEquals(SmpsServiceOutcome.SERVICE_IN_FLIGHT,
                session.serviceForward());
        assertTrue(session.hasPendingActivation());
        assertEquals(0, session.captureLogicalSnapshot()
                .pendingService().remainingTStates());
        assertEquals(loadSilenceWrites, writes.events().size());
        assertTrue(serviceEnds.isEmpty());

        SmpsDriverSessionSnapshot readyPhysical = session.captureSnapshot();
        SmpsDriverSnapshot readyInFlight = session.captureLogicalSnapshot();
        assertEquals(SmpsServiceOutcome.ORDINARY, session.serviceForward());
        assertFalse(session.hasPendingActivation());
        assertFalse(serviceEnds.isEmpty());
        int endsAfterFirstService = serviceEnds.size();

        SmpsDriverSession.PreparedRestore restore = session.prepareRestore(
                readyPhysical, readyInFlight,
                new SmpsDriverSession.DacDependencyResolver() {
                    @Override public com.openggf.audio.smps.DacData resolve(
                            SmpsSourceDescriptor source) {
                        return base.selectedDac().data();
                    }

                    @Override public SmpsLoadReadiness resolveReadiness(
                            SmpsSourceDescriptor source) {
                        return twoPresentationReadiness();
                    }
                });
        session.commitRestore(restore);
        assertEquals(SmpsServiceOutcome.ORDINARY, session.serviceForward());
        assertEquals(endsAfterFirstService * 2, serviceEnds.size(),
                "restoring READY_IN_FLIGHT must replay exactly one first service");
    }
    private enum SourcePolicy {
        S1(true), S2(false), S3K(false);

        private final boolean preservesSfx;

        SourcePolicy(boolean preservesSfx) {
            this.preservesSfx = preservesSfx;
        }
    }

    static Stream<Arguments> baseDonorPairings() {
        return Arrays.stream(SourcePolicy.values()).flatMap(base ->
                Arrays.stream(SourcePolicy.values()).map(donor ->
                        Arguments.of(base, donor)));
    }

    @ParameterizedTest(name = "host={0}, donor={1}")
    @MethodSource("baseDonorPairings")
    void hostPolicyWinsForEveryBaseDonorPairing(
            SourcePolicy host, SourcePolicy donor) {
        SmpsPhysicalPolicy physicalPolicy =
                LegacyCompatibilitySmpsPhysicalPolicy.INSTANCE;
        SmpsPhysicalDevice.Settings settings =
                SmpsSessionTestFixtures.settings();
        SmpsSessionProfileFingerprint fingerprint =
                new SmpsSessionProfileFingerprint(
                        host.name(), 19, physicalPolicy.identity(), settings);
        SmpsDriverSession session = new SmpsDriverSession(
                settings, physicalPolicy,
                new SmpsSessionTestFixtures.RecordingObserver(), fingerprint,
                SmpsDriverSessionConfiguration.DEFAULT);
        session.install();
        Object device = session.physicalIdentityForTesting();
        SmpsDriver driver = session.logicalDriverForTesting();

        session.queueActivation(activation(
                0x80 + donor.ordinal(), donor, true));
        session.serviceForward();

        assertSame(device, session.physicalIdentityForTesting());
        assertSame(driver, session.logicalDriverForTesting());
        assertSame(fingerprint, session.captureSnapshot().profile());
        assertEquals(host.name(), session.captureSnapshot()
                .profile().baseGameId());
    }

    @ParameterizedTest(name = "base={0}, donor={1}")
    @MethodSource("baseDonorPairings")
    void baseMusicAfterDonorSfxAndDonorMusicAfterBaseSfx(
            SourcePolicy base, SourcePolicy donor) {
        assertMusicTransition(base, donor, false);
        assertMusicTransition(base, donor, true);
    }

    @Test
    void overridePopEmitsSourceOwnedFirstServiceAndExactNextPcm() {
        SmpsSessionTestFixtures.RecordingObserver expectedWrites =
                new SmpsSessionTestFixtures.RecordingObserver();
        SmpsSessionTestFixtures.RecordingObserver actualWrites =
                new SmpsSessionTestFixtures.RecordingObserver();
        SmpsDriverSession expected =
                SmpsSessionTestFixtures.session(expectedWrites);
        SmpsDriverSession actual =
                SmpsSessionTestFixtures.session(actualWrites);
        expected.install();
        actual.install();
        Object physical = actual.physicalIdentityForTesting();
        SmpsDriver logical = actual.logicalDriverForTesting();
        PreparedSmpsMusicActivation base = activation(
                0x81, SourcePolicy.S1, false);
        expected.queueActivation(base);
        actual.queueActivation(base);
        expected.serviceForward();
        actual.serviceForward();

        actual.applyCommand(new SmpsSessionCommand.PushOverride(
                activation(0x82, SourcePolicy.S2, true)));
        actual.serviceForward();
        actualWrites.clear();
        actual.applyCommand(new SmpsSessionCommand.RestoreOverride());
        assertTrue(actualWrites.events().isEmpty(),
                "override pop itself is write-free");
        expectedWrites.clear();

        expected.serviceForward();
        actual.serviceForward();
        short[] expectedPcm = new short[256];
        short[] actualPcm = new short[256];
        expected.renderFrames(expectedPcm, 0, 128);
        actual.renderFrames(actualPcm, 0, 128);

        assertSame(physical, actual.physicalIdentityForTesting());
        assertSame(logical, actual.logicalDriverForTesting());
        assertEquals(expectedWrites.events(), actualWrites.events());
        assertArrayEquals(expectedPcm, actualPcm);
    }

    @Test
    void delayedSonic2MusicSilencesAtLoadStartAndServicesOnlyWhenReady() {
        SmpsSessionTestFixtures.RecordingObserver writes =
                new SmpsSessionTestFixtures.RecordingObserver();
        SmpsPhysicalPolicy policy = Sonic2SmpsCompatibilityPolicy.INSTANCE;
        SmpsPhysicalDevice.Settings settings = SmpsSessionTestFixtures.settings();
        SmpsDriverSession session = new SmpsDriverSession(
                settings, policy, writes,
                new SmpsSessionProfileFingerprint(
                        "S2", 19, policy.identity(), settings),
                SmpsDriverSessionConfiguration.DEFAULT);
        session.install();
        PreparedSmpsMusicActivation base = activation(
                0x81, SourcePolicy.S2, false);
        PreparedSmpsMusicActivation delayed = new PreparedSmpsMusicActivation(
                base.activation(), base.incomingMusic(), base.logicalPolicy(),
                base.selectedDac(), twoPresentationReadiness());

        writes.clear();
        session.queueActivation(delayed);
        assertEquals(202, writes.events().size());
        assertTrue(session.blocksForwardRequestConsumption());
        assertTrue(session.captureLogicalSnapshot().sequencers().isEmpty());
        assertEquals(SmpsServiceOutcome.LOAD_PENDING, session.serviceForward());
        assertTrue(session.blocksForwardRequestConsumption());
        assertTrue(session.captureLogicalSnapshot().sequencers().isEmpty());
        assertEquals(SmpsServiceOutcome.SERVICE_IN_FLIGHT,
                session.serviceForward());
        assertTrue(session.blocksForwardRequestConsumption());
        assertTrue(session.captureLogicalSnapshot().sequencers().isEmpty());
        assertEquals(SmpsServiceOutcome.ORDINARY, session.serviceForward());
        assertFalse(session.blocksForwardRequestConsumption());
        assertFalse(session.hasPendingActivation());
        assertEquals(5, session.captureLogicalSnapshot().palUpdateCounter());
    }

    @Test
    void delayedLoadRollbackRestoresExactRemainingWork() {
        SmpsSessionTestFixtures.RecordingObserver writes =
                new SmpsSessionTestFixtures.RecordingObserver();
        SmpsDriverSession session = SmpsSessionTestFixtures.session(writes);
        session.install();
        PreparedSmpsMusicActivation base = activation(
                0x81, SourcePolicy.S2, false);
        session.queueActivation(new PreparedSmpsMusicActivation(
                base.activation(), base.incomingMusic(), base.logicalPolicy(),
                base.selectedDac(), twoPresentationReadiness()));

        SmpsDriverSession.LiveMutationToken mutation =
                session.captureLiveMutation();
        assertEquals(SmpsServiceOutcome.LOAD_PENDING,
                session.serviceForward());
        session.rollbackLiveMutation(mutation);

        assertEquals(SmpsServiceOutcome.LOAD_PENDING,
                session.serviceForward());
        assertEquals(SmpsServiceOutcome.SERVICE_IN_FLIGHT,
                session.serviceForward());
        assertEquals(SmpsServiceOutcome.ORDINARY,
                session.serviceForward());
    }

    @Test
    void delayedLoadRewindRestoresExactRemainingWorkAndProvenance() {
        SmpsDriverSession session = SmpsSessionTestFixtures.session(
                new SmpsSessionTestFixtures.RecordingObserver());
        session.install();
        PreparedSmpsMusicActivation base = activation(
                0x81, SourcePolicy.S2, false);
        SmpsLoadReadiness readiness = twoPresentationReadiness();
        session.queueActivation(new PreparedSmpsMusicActivation(
                base.activation(), base.incomingMusic(), base.logicalPolicy(),
                base.selectedDac(), readiness));
        SmpsDriverSessionSnapshot sessionSnapshot = session.captureSnapshot();
        SmpsDriverSnapshot logicalSnapshot =
                session.captureLogicalSnapshot();
        assertEquals(SmpsServiceOutcome.LOAD_PENDING,
                session.serviceForward());

        SmpsDriverSession.PreparedRestore restore = session.prepareRestore(
                sessionSnapshot, logicalSnapshot,
                new SmpsDriverSession.DacDependencyResolver() {
                    @Override public com.openggf.audio.smps.DacData resolve(
                            SmpsSourceDescriptor source) {
                        return base.selectedDac().data();
                    }
                    @Override public SmpsLoadReadiness resolveReadiness(
                            SmpsSourceDescriptor source) {
                        return readiness;
                    }
                });
        session.commitRestore(restore);

        assertEquals(SmpsServiceOutcome.LOAD_PENDING,
                session.serviceForward());
        assertEquals(SmpsServiceOutcome.SERVICE_IN_FLIGHT,
                session.serviceForward());
        assertEquals(SmpsServiceOutcome.ORDINARY,
                session.serviceForward());
    }

    @Test
    void delayedLoadStartSilenceRollsBackAndRetryPublishesExactlyOnce() {
        SmpsSessionTestFixtures.RecordingObserver writes =
                new SmpsSessionTestFixtures.RecordingObserver();
        SmpsPhysicalPolicy policy = Sonic2SmpsCompatibilityPolicy.INSTANCE;
        SmpsPhysicalDevice.Settings settings = SmpsSessionTestFixtures.settings();
        SmpsDriverSession session = new SmpsDriverSession(
                settings, policy, writes,
                new SmpsSessionProfileFingerprint(
                        "S2", 19, policy.identity(), settings),
                SmpsDriverSessionConfiguration.DEFAULT);
        session.install();
        PreparedSmpsMusicActivation base = activation(
                0x81, SourcePolicy.S2, false);
        PreparedSmpsMusicActivation delayed = new PreparedSmpsMusicActivation(
                base.activation(), base.incomingMusic(), base.logicalPolicy(),
                base.selectedDac(), twoPresentationReadiness());
        writes.clear();

        SmpsDriverSession.LiveMutationToken rejected =
                session.captureLiveMutation();
        session.queueActivation(delayed);
        session.rollbackLiveMutation(rejected);
        assertTrue(writes.events().isEmpty());
        assertFalse(session.blocksForwardRequestConsumption());

        SmpsDriverSession.LiveMutationToken retry =
                session.captureLiveMutation();
        session.queueActivation(delayed);
        session.prepareLiveMutationCommit(retry);
        session.commitLiveMutation(retry);
        session.publishCommittedDiagnostics();
        assertEquals(202, writes.events().size());
        assertTrue(session.blocksForwardRequestConsumption());
    }

    @Test
    void globalStopSupersedesDelayedLoadWithoutActivation() {
        SmpsDriverSession session = SmpsSessionTestFixtures.session(
                new SmpsSessionTestFixtures.RecordingObserver());
        session.install();
        PreparedSmpsMusicActivation base = activation(
                0x81, SourcePolicy.S2, false);
        session.queueActivation(new PreparedSmpsMusicActivation(
                base.activation(), base.incomingMusic(), base.logicalPolicy(),
                base.selectedDac(), twoPresentationReadiness()));

        session.retainGlobalStop();

        assertEquals(SmpsServiceOutcome.GLOBAL_STOP_CONSUMED,
                session.serviceForward());
        assertFalse(session.blocksForwardRequestConsumption());
        assertTrue(session.captureLogicalSnapshot().sequencers().isEmpty());
    }

    private static SmpsLoadReadiness twoPresentationReadiness() {
        return new SmpsLoadReadiness() {
            @Override public boolean immediate() { return false; }
            @Override public int compressedByteCount() { return 1; }
            @Override public int workUnitCount() { return 1; }
            @Override public long minimumTStates(Context context) { return 2; }
            @Override public String provenance() { return "test-two-step"; }
            @Override public Work begin(Context context) { return countdown(2); }
            @Override public Work resume(Context context, long remaining) {
                return countdown(remaining);
            }
        };
    }

    private static SmpsLoadReadiness.Work countdown(long initial) {
        return new SmpsLoadReadiness.Work() {
            private long remaining = initial;
            @Override public boolean ready() { return remaining == 0; }
            @Override public boolean advanceOnePresentation() {
                if (remaining > 0) remaining--;
                return ready();
            }
            @Override public long remainingTStates() { return remaining; }
            @Override public SmpsLoadReadiness.Work copy() {
                return countdown(remaining);
            }
        };
    }

    private static void assertMusicTransition(
            SourcePolicy base,
            SourcePolicy donor,
            boolean donorMusicAfterBaseSfx) {
        SmpsDriverSession session = SmpsSessionTestFixtures.session(
                new SmpsSessionTestFixtures.RecordingObserver());
        session.install();
        Object device = session.physicalIdentityForTesting();
        SmpsDriver driver = session.logicalDriverForTesting();
        SourcePolicy sfxPolicy = donorMusicAfterBaseSfx ? base : donor;
        SourcePolicy musicPolicy = donorMusicAfterBaseSfx ? donor : base;
        boolean musicIsDonor = donorMusicAfterBaseSfx;
        SmpsDriverSnapshot current = snapshotWith(
                entry(0xA0 + sfxPolicy.ordinal(), sfxPolicy,
                        !donorMusicAfterBaseSfx, true));
        SmpsDriverSession.PreparedRestore prepared = session.prepareRestore(
                session.captureSnapshot(), current, ignored -> null);
        session.commitRestore(prepared);

        session.queueActivation(activation(
                0x81 + musicPolicy.ordinal(), musicPolicy, musicIsDonor));

        List<SmpsDriverSnapshot.SequencerEntry> sequencers =
                session.captureLogicalSnapshot().sequencers();
        assertSame(device, session.physicalIdentityForTesting());
        assertSame(driver, session.logicalDriverForTesting());
        assertFalse(sequencers.getFirst().sfx());
        assertEquals(musicPolicy.preservesSfx ? 2 : 1,
                sequencers.size());
        assertEquals(musicPolicy.preservesSfx,
                sequencers.stream().anyMatch(
                        SmpsDriverSnapshot.SequencerEntry::sfx));
    }

    private static PreparedSmpsMusicActivation activation(
            int id, SourcePolicy policy, boolean donor) {
        SmpsDriverSnapshot.SequencerEntry incoming = entry(
                id, policy, donor, false);
        return new PreparedSmpsMusicActivation(
                new SmpsMusicActivation(incoming.source(), 0),
                incoming,
                SmpsLogicalTransitionPolicies.forConfig(incoming.config()),
                new SmpsDacSelection(
                        incoming.source(), incoming.dacData()));
    }

    private static SmpsDriverSnapshot snapshotWith(
            SmpsDriverSnapshot.SequencerEntry entry) {
        return new SmpsDriverSnapshot(
                SmpsSequencer.Region.NTSC,
                SmpsDriver.ReadMode.SAMPLE_ACCURATE,
                entry.sfx() ? entry.source().id() : 0,
                false, 0, 5, List.of(entry),
                new int[] {-1, -1, -1, -1, -1, -1},
                new int[] {-1, -1, -1, -1});
    }

    private static SmpsDriverSnapshot.SequencerEntry entry(
            int id,
            SourcePolicy policy,
            boolean donor,
            boolean sfx) {
        AudioTestFixtures.StubSmpsData data =
                new AudioTestFixtures.StubSmpsData(
                        (donor ? "donor-" : "base-") + id);
        data.setId(id);
        SmpsSourceDescriptor source = new SmpsSourceDescriptor(
                sfx
                        ? donor ? SmpsSourceDescriptor.Kind.DONOR_SFX_ID
                                : SmpsSourceDescriptor.Kind.BASE_SFX_ID
                        : donor ? SmpsSourceDescriptor.Kind.DONOR_MUSIC
                                : SmpsSourceDescriptor.Kind.BASE_MUSIC,
                id, null, donor ? "donor" : null,
                data.getZ80StartAddress(), data.getData().length,
                Arrays.hashCode(data.getData()), false, 7);
        SmpsSequencerConfig config = new SmpsSequencerConfig.Builder()
                .direct68kDriver(policy.preservesSfx)
                .build();
        SmpsDriver detached = new SmpsDriver();
        SmpsSequencer sequencer = new SmpsSequencer(
                data, SmpsSessionTestFixtures.dac(), detached, detached,
                AudioManager.getInstance(), config, source,
                SmpsSequencer.SourceDescriptorTrust.PRECOMPUTED_IMMUTABLE);
        sequencer.setIsSfx(sfx);
        return new SmpsDriverSnapshot.SequencerEntry(
                sfx, source,
                SmpsSequencer.SourceDescriptorTrust.PRECOMPUTED_IMMUTABLE,
                null, data, SmpsSessionTestFixtures.dac(),
                AudioManager.getInstance(), config,
                sequencer.captureSnapshot());
    }
}
