package com.openggf.audio.driver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.openggf.audio.AudioManager;
import com.openggf.audio.AudioTestFixtures;
import com.openggf.audio.rewind.SmpsDriverSnapshot;
import com.openggf.audio.smps.AbstractSmpsData;
import com.openggf.audio.smps.SmpsSequencer;
import com.openggf.audio.smps.SmpsSequencerConfig;
import com.openggf.audio.smps.SmpsSfxData;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TestSfxContentionObserver {

    @Test
    void serviceIdentityStorageStaysBoundedAcrossThousandsOfLifecycles() {
        // Break caught: append-only diagnostic identities retained every historical SFX.
        SmpsDriver driver = SmpsDriverTestAccess.create(60.0);
        List<Long> tickIdentities = new ArrayList<>();
        driver.setServiceObserver(new SmpsDriverServiceObserver() {
            @Override
            public void onServiceBegin(ServiceEvent event) {
                if (event.kind() == ServiceKind.SEQUENCER_TICK) {
                    tickIdentities.add(event.sequencer().instanceOrdinal());
                }
            }
        });

        for (int iteration = 0; iteration < 1_000; iteration++) {
            driver.addSequencer(oneTickSfx(driver), true);
            SmpsDriverTestAccess.read(driver, new short[2]);
            assertEquals(0, driver.sequencersForTesting().size());
            assertEquals(0, driver.trackedServiceSequencerCountForTesting());
        }

        for (int iteration = 0; iteration < 1_000; iteration++) {
            driver.addSequencer(longRunningSfx(driver), true);
            SmpsDriverTestAccess.read(driver, new short[2]);
            assertEquals(1, driver.sequencersForTesting().size());
            assertEquals(1, driver.trackedServiceSequencerCountForTesting(),
                    "same-ID replacement must release the displaced identity");
        }

        SmpsDriverSnapshot rewindPoint = driver.captureSnapshot();
        for (int iteration = 0; iteration < 1_000; iteration++) {
            driver.restoreSnapshot(rewindPoint);
            assertEquals(0, driver.trackedServiceSequencerCountForTesting(),
                    "snapshot reconstruction starts with no historical identity");
            SmpsDriverTestAccess.read(driver, new short[2]);
            assertEquals(1, driver.sequencersForTesting().size());
            assertEquals(1, driver.trackedServiceSequencerCountForTesting());
        }

        assertEquals(3_000, tickIdentities.size());
        for (int index = 1; index < tickIdentities.size(); index++) {
            assertTrue(tickIdentities.get(index) > tickIdentities.get(index - 1),
                    "a removed or restored sequencer must receive a fresh monotonic identity");
        }
        assertEquals(3_000,
                driver.nextServiceSequencerOrdinalForTesting());

        driver.stopAllSfx();
        assertEquals(0, driver.trackedServiceSequencerCountForTesting());
    }

    @Test
    void disabledServiceObserverAllocatesNoSequencerIdentityStorage() {
        SmpsDriver driver = SmpsDriverTestAccess.create(60.0);
        driver.addSequencer(longRunningSfx(driver), true);

        SmpsDriverTestAccess.read(driver, new short[2]);

        assertEquals(SmpsDriverServiceObserver.NONE,
                driver.serviceObserver());
        assertEquals(0, driver.trackedServiceSequencerCountForTesting());
        assertEquals(0, driver.nextServiceSequencerOrdinalForTesting());
    }

    @Test
    void rollbackDropsOnlyProvisionalIdentityAndNeverReusesItsOrdinal() {
        SmpsDriver driver = SmpsDriverTestAccess.create(60.0);
        List<SmpsDriverServiceObserver.ServiceEvent> ticks =
                new ArrayList<>();
        driver.setServiceObserver(new SmpsDriverServiceObserver() {
            @Override
            public void onServiceBegin(ServiceEvent event) {
                if (event.kind() == ServiceKind.SEQUENCER_TICK) {
                    ticks.add(event);
                }
            }
        });
        driver.addSequencer(longRunningMusic(driver, 0x81), false);
        SmpsDriverTestAccess.read(driver, new short[2]);
        SmpsDriver.LiveCommandMutationToken rollback =
                driver.captureLiveCommandMutation();

        driver.addSequencer(longRunningSfx(driver, 0xA1), true);
        SmpsDriverTestAccess.read(driver, new short[2]);
        assertEquals(2, driver.trackedServiceSequencerCountForTesting());

        driver.rollbackLiveCommandMutation(rollback);
        assertEquals(1, driver.trackedServiceSequencerCountForTesting());
        driver.addSequencer(longRunningSfx(driver, 0xA2), true);
        SmpsDriverTestAccess.read(driver, new short[2]);

        List<Long> musicIdentities = ticks.stream()
                .filter(event -> event.sequencer().source().id() == 0x81)
                .map(event -> event.sequencer().instanceOrdinal())
                .distinct().toList();
        long a2Identity = ticks.stream()
                .filter(event -> event.sequencer().source().id() == 0xA2)
                .map(event -> event.sequencer().instanceOrdinal())
                .findFirst().orElseThrow();
        assertEquals(List.of(0L), musicIdentities,
                "the surviving live sequencer keeps its stable identity");
        assertEquals(2L, a2Identity,
                "the rolled-back identity ordinal is not reused");

        driver.stopAll();
        assertEquals(0, driver.trackedServiceSequencerCountForTesting());
    }

    @Test
    void observerIsDisabledByDefaultAndDoesNotChangeSnapshotIdentity() {
        // Break caught: diagnostic observation becomes live driver state or is enabled implicitly.
        SmpsDriver driver = new SmpsDriver();
        SmpsSequencer music = sequencer("music", 0x81, driver);
        SmpsSequencer sfx = sequencer("sfx", 0xA0, driver);
        driver.addSequencer(music, false);
        SmpsDriverSnapshot before = driver.captureSnapshot();

        driver.addSequencer(sfx, true);
        driver.writeFm(sfx, 0, 0xA2, 0x22);

        assertEquals(SfxContentionObserver.NONE, driver.sfxContentionObserver());
        assertEquals(before.sequencers().size() + 1,
                driver.captureSnapshot().sequencers().size());
        driver.setSfxContentionObserver(SfxContentionObserver.NONE);
        SmpsDriverSnapshot afterFirstDisabledInstall = driver.captureSnapshot();
        driver.setSfxContentionObserver(SfxContentionObserver.NONE);
        SmpsDriverSnapshot afterSecondDisabledInstall = driver.captureSnapshot();
        assertEquals(afterFirstDisabledInstall.sequencers(), afterSecondDisabledInstall.sequencers(),
                "installing the disabled observer must not participate in rewind snapshots");
        assertEquals(afterFirstDisabledInstall.continuousSfxId(), afterSecondDisabledInstall.continuousSfxId());
    }

    @Test
    void observerBookkeepingIsReleasedWithSfxLifecycle() {
        // Break caught: opt-in diagnostic identities retain dead SFX sequencers.
        SmpsDriver driver = new SmpsDriver();
        driver.setSfxContentionObserver(new SfxContentionObserver() { });
        SmpsSequencer sfx = sequencer("sfx", 0xA0, driver);
        driver.addSequencer(sfx, true);
        assertEquals(1, driver.trackedSfxAdmissionCountForTesting());
        driver.stopAllSfx();
        assertEquals(0, driver.trackedSfxAdmissionCountForTesting());
        driver.addSequencer(sequencer("again", 0xA0, driver), true);
        driver.stopAll();
        assertEquals(0, driver.trackedSfxAdmissionCountForTesting());
    }

    @Test
    void reportsOrderedAdmissionAndPerRoleDisplacementForOverlappingSfx() {
        // Break caught: same-frame SFX requests lose source order or a later normal SFX's FM3 steal.
        SmpsDriver driver = new SmpsDriver();
        List<Object> events = new ArrayList<>();
        driver.setSfxContentionObserver(new SfxContentionObserver() {
            @Override
            public void onSfxAdmitted(SfxContentionObserver.Admission admission) {
                events.add(admission);
            }

            @Override
            public void onRoleArbitrated(SfxContentionObserver.Arbitration arbitration) {
                events.add(arbitration);
            }
        });
        SmpsSequencer music = sequencer("music", 0x81, driver);
        SmpsSequencer first = realTrackSequencer("first", 0xA0, driver);
        SmpsSequencer second = realTrackSequencer("second", 0xA1, driver);
        first.setSfxPriority(0x20);
        second.setSfxPriority(0x60);
        driver.addSequencer(music, false);
        driver.addSequencer(first, true);
        first.writeFm(0, 0xA2, 0x22);
        driver.addSequencer(second, true);
        second.writeFm(0, 0xA2, 0x44);

        assertEquals(4, events.size());
        SfxContentionObserver.Admission firstAdmission =
                (SfxContentionObserver.Admission) events.get(0);
        SfxContentionObserver.Arbitration firstRole =
                (SfxContentionObserver.Arbitration) events.get(1);
        SfxContentionObserver.Admission secondAdmission =
                (SfxContentionObserver.Admission) events.get(2);
        SfxContentionObserver.Arbitration secondRole =
                (SfxContentionObserver.Arbitration) events.get(3);
        assertEquals(0xA0, firstAdmission.source().descriptor().id());
        assertEquals(0, firstAdmission.source().admissionOrdinal());
        assertEquals(SfxContentionObserver.Bus.FM, firstRole.bus());
        assertEquals(2, firstRole.channel());
        assertTrue(firstRole.acquired());
        assertNull(firstRole.previousOwner(),
                "an unlocked role remains music-owned in the presentation snapshot, not the SFX lock table");
        assertEquals(0xA1, secondAdmission.source().descriptor().id());
        assertEquals(1, secondAdmission.source().admissionOrdinal());
        assertTrue(secondRole.acquired());
        assertEquals(firstAdmission.source(), secondRole.previousOwner());
        assertEquals(secondAdmission.source(), secondRole.challenger());
        assertEquals(1, events.stream()
                .filter(SfxContentionObserver.Arbitration.class::isInstance)
                .map(SfxContentionObserver.Arbitration.class::cast)
                .filter(event -> event.challenger().equals(secondAdmission.source()))
                .count(), "one production lock decision reports the same-frame B takeover");
    }

    @Test
    void sameIdProductionRetriggerReportsTheDisplacedOldSfxIdentityOnce() {
        SmpsDriver driver = new SmpsDriver();
        List<SfxContentionObserver.Admission> admissions = new ArrayList<>();
        List<SfxContentionObserver.Arbitration> arbitrations = new ArrayList<>();
        driver.setSfxContentionObserver(new SfxContentionObserver() {
            @Override public void onSfxAdmitted(SfxContentionObserver.Admission admission) {
                admissions.add(admission);
            }
            @Override public void onRoleArbitrated(SfxContentionObserver.Arbitration arbitration) {
                arbitrations.add(arbitration);
            }
        });
        SmpsSequencer oldSfx = realTrackSequencer("old", 0xA0, driver);
        SmpsSequencer retrigger = realTrackSequencer("retrigger", 0xA0, driver);
        driver.addSequencer(oldSfx, true);
        oldSfx.writeFm(0, 0xA2, 0x22);
        driver.addSequencer(retrigger, true);
        retrigger.writeFm(0, 0xA2, 0x44);

        SfxContentionObserver.Source challenger = admissions.get(1).source();
        List<SfxContentionObserver.Arbitration> challengerDecisions = arbitrations.stream()
                .filter(event -> event.challenger().equals(challenger)).toList();
        assertEquals(1, challengerDecisions.size(), "the production lock path is the sole arbitration authority");
        assertTrue(challengerDecisions.getFirst().acquired());
        assertEquals(admissions.getFirst().source(), challengerDecisions.getFirst().previousOwner(),
                "same-ID replacement must displace the old SFX, not music/null");
    }

    @Test
    void admissionOwnershipRetainsDisplacedOwnerForFirstRoleDecision() {
        SmpsDriver driver = new SmpsDriver();
        List<SfxContentionObserver.Admission> admissions = new ArrayList<>();
        List<SfxContentionObserver.Arbitration> arbitrations = new ArrayList<>();
        driver.setSfxContentionObserver(new SfxContentionObserver() {
            @Override
            public void onSfxAdmitted(Admission admission) {
                admissions.add(admission);
            }

            @Override
            public void onRoleArbitrated(Arbitration arbitration) {
                arbitrations.add(arbitration);
            }
        });
        SmpsSequencerConfig config = new SmpsSequencerConfig.Builder()
                .fmChannelOrder(new int[] {2})
                .sfxChannelOwnershipMode(
                        SmpsSequencerConfig.SfxChannelOwnershipMode.ADMISSION)
                .build();
        SmpsSequencer first = realTrackSequencer(
                "first", 0xA0, driver, config);
        SmpsSequencer second = realTrackSequencer(
                "second", 0xA1, driver, config);
        driver.addSequencer(first, true);
        first.writeFm(0, 0xA2, 0x22);
        driver.addSequencer(second, true);
        second.writeFm(0, 0xA2, 0x44);

        SfxContentionObserver.Source challenger = admissions.get(1).source();
        SfxContentionObserver.Arbitration decision = arbitrations.stream()
                .filter(event -> event.challenger().equals(challenger))
                .findFirst().orElseThrow();
        assertEquals(admissions.getFirst().source(), decision.previousOwner());
        assertTrue(decision.acquired());
    }

    @Test
    void admissionOwnershipRollbackRetainsDisplacedOwnerForRetriedFirstWrite() {
        SmpsDriver driver = new SmpsDriver();
        List<SfxContentionObserver.Admission> admissions = new ArrayList<>();
        List<SfxContentionObserver.Arbitration> arbitrations = new ArrayList<>();
        boolean[] failAdmission = {false};
        driver.setSfxContentionObserver(new SfxContentionObserver() {
            @Override
            public void onSfxAdmitted(Admission admission) {
                admissions.add(admission);
                if (failAdmission[0]) {
                    throw new IllegalStateException("injected admission failure");
                }
            }

            @Override
            public void onRoleArbitrated(Arbitration arbitration) {
                arbitrations.add(arbitration);
            }
        });
        SmpsSequencerConfig config = new SmpsSequencerConfig.Builder()
                .fmChannelOrder(new int[] {2})
                .sfxChannelOwnershipMode(
                        SmpsSequencerConfig.SfxChannelOwnershipMode.ADMISSION)
                .build();
        SmpsSequencer displaced = realTrackSequencer(
                "displaced", 0xA0, driver, config);
        SmpsSequencer challenger = realTrackSequencer(
                "challenger", 0xA1, driver, config);
        driver.addSequencer(displaced, true);
        displaced.writeFm(0, 0xA2, 0x22);
        SfxContentionObserver.Source displacedSource = admissions.getFirst().source();
        arbitrations.clear();
        PreparedSfxAdmission admission = driver.prepareNewSfxAdmission(
                challenger, 0, 1);

        challenger.beginSfxAdmission();
        failAdmission[0] = true;
        assertThrows(IllegalStateException.class,
                () -> driver.commitSfxAdmission(admission));
        assertEquals(List.of(displaced), driver.sequencersForTesting(),
                "failed admission restores the displaced owner");

        failAdmission[0] = false;
        arbitrations.clear();
        driver.commitSfxAdmission(admission);
        challenger.writeFm(0, 0xA2, 0x44);

        assertEquals(1, arbitrations.size());
        assertEquals(displacedSource, arbitrations.getFirst().previousOwner(),
                "retry consumes the restored displaced-owner attribution exactly once");
        assertTrue(arbitrations.getFirst().acquired());
    }

    @Test
    void rollbackPreservesSurvivingAdmissionIdentityWithoutReusingOrdinals() {
        // Break caught: rollback reidentified a surviving sequencer or reused a reverted admission ordinal.
        SmpsDriver driver = new SmpsDriver();
        List<SfxContentionObserver.Admission> admissions = new ArrayList<>();
        driver.setSfxContentionObserver(new SfxContentionObserver() {
            @Override
            public void onSfxAdmitted(SfxContentionObserver.Admission admission) {
                admissions.add(admission);
            }
        });
        SmpsSequencer survivor = sequencer("survivor", 0xA0, driver);
        driver.addSequencer(survivor, true);
        SmpsDriver.LiveCommandMutationToken rollback = driver.captureLiveCommandMutation();
        driver.addSequencer(sequencer("discarded", 0xA1, driver), true);

        driver.rollbackLiveCommandMutation(rollback);
        driver.addSequencer(sequencer("after-rollback", 0xA2, driver), true);

        assertEquals(0, admissions.getFirst().source().admissionOrdinal());
        assertEquals(1, admissions.get(1).source().admissionOrdinal());
        assertEquals(2, admissions.get(2).source().admissionOrdinal());
        assertEquals(0xA0, admissions.getFirst().source().descriptor().id());
    }

    @Test
    void restoreReadmitsSfxInSnapshotOrderWithFreshMonotonicIdentities() {
        // Break caught: reconstructed SFX inherited -1 or HashSet-order admission identities.
        SmpsDriver driver = new SmpsDriver();
        List<SfxContentionObserver.Admission> admissions = new ArrayList<>();
        driver.setSfxContentionObserver(new SfxContentionObserver() {
            @Override
            public void onSfxAdmitted(SfxContentionObserver.Admission admission) {
                admissions.add(admission);
            }
        });
        driver.addSequencer(sequencer("first", 0xA0, driver), true);
        driver.addSequencer(sequencer("second", 0xA1, driver), true);
        SmpsDriverSnapshot snapshot = driver.captureSnapshot();
        admissions.clear();

        driver.restoreSnapshot(snapshot);

        assertEquals(List.of(0xA0, 0xA1), admissions.stream()
                .map(admission -> admission.source().descriptor().id()).toList());
        assertEquals(List.of(2L, 3L), admissions.stream()
                .map(admission -> admission.source().admissionOrdinal()).toList());
        assertTrue(admissions.stream().allMatch(admission -> admission.source().admissionOrdinal() >= 0));
        assertNotEquals(admissions.getFirst().source(), admissions.get(1).source());
    }

    private static SmpsSequencer sequencer(String name, int id, SmpsDriver driver) {
        AbstractSmpsData data = new AudioTestFixtures.StubSmpsData(name);
        data.setId(id);
        return new SmpsSequencer(data, AudioTestFixtures.EMPTY_DAC, driver,
                AudioManager.getInstance(), new SmpsSequencerConfig.Builder().build());
    }

    private static SmpsSequencer realTrackSequencer(String name, int id, SmpsDriver driver) {
        return realTrackSequencer(name, id, driver,
                new SmpsSequencerConfig.Builder()
                        .fmChannelOrder(new int[] {2}).build());
    }

    private static SmpsSequencer realTrackSequencer(
            String name, int id, SmpsDriver driver,
            SmpsSequencerConfig config) {
        AbstractSmpsData data = new SingleFmTrackData(name);
        data.setId(id);
        return new SmpsSequencer(data, AudioTestFixtures.EMPTY_DAC, driver,
                AudioManager.getInstance(), config);
    }

    private static SmpsSequencer oneTickSfx(SmpsDriver driver) {
        return sfxSequencer(new LifecycleSfxData(true), driver);
    }

    private static SmpsSequencer longRunningSfx(SmpsDriver driver) {
        return longRunningSfx(driver, 0xA0);
    }

    private static SmpsSequencer longRunningSfx(
            SmpsDriver driver, int id) {
        return sfxSequencer(new LifecycleSfxData(false, id), driver);
    }

    private static SmpsSequencer longRunningMusic(
            SmpsDriver driver, int id) {
        AbstractSmpsData data = new LifecycleMusicData(id);
        SmpsSequencer sequencer = new SmpsSequencer(
                data, AudioTestFixtures.EMPTY_DAC, driver,
                AudioManager.getInstance(), new SmpsSequencerConfig.Builder()
                .tempoMode(SmpsSequencerConfig.TempoMode.TIMEOUT)
                .fmChannelOrder(new int[] {2}).build());
        sequencer.setSampleRate(60.0);
        return sequencer;
    }

    private static SmpsSequencer sfxSequencer(
            AbstractSmpsData data, SmpsDriver driver) {
        SmpsSequencer sequencer = new SmpsSequencer(
                data, AudioTestFixtures.EMPTY_DAC, driver,
                AudioManager.getInstance(), new SmpsSequencerConfig.Builder()
                .tempoMode(SmpsSequencerConfig.TempoMode.TIMEOUT)
                .fmChannelOrder(new int[] {2}).build());
        sequencer.setSampleRate(60.0);
        return sequencer;
    }

    private static final class LifecycleSfxData extends AbstractSmpsData
            implements SmpsSfxData {
        private LifecycleSfxData(boolean oneTick) {
            this(oneTick, 0xA0);
        }

        private LifecycleSfxData(boolean oneTick, int id) {
            super(oneTick
                    ? new byte[] {0, (byte) 0xF2}
                    : new byte[] {0, (byte) 0x81, 0x7F, (byte) 0xF2}, 0);
            setId(id);
        }

        @Override public int getTickMultiplier() { return 1; }
        @Override public List<? extends SmpsSfxTrack> getTrackEntries() {
            return List.of(new SfxTrack(5, 1, 0, 0));
        }
        @Override protected void parseHeader() { dividingTiming = 1; tempo = 1; }
        @Override public byte[] getVoice(int voiceId) { return new byte[25]; }
        @Override public byte[] getPsgEnvelope(int id) { return null; }
        @Override public int read16(int offset) { return 0; }
        @Override public int getBaseNoteOffset() { return 0; }
    }

    private record SfxTrack(
            int channelMask, int pointer, int transpose, int volume)
            implements SmpsSfxData.SmpsSfxTrack {
    }

    private static final class LifecycleMusicData extends AbstractSmpsData {
        private LifecycleMusicData(int id) {
            super(new byte[] {0, (byte) 0x81, 0x7F, (byte) 0xF2}, 0);
            setId(id);
        }

        @Override protected void parseHeader() {
            channels = 1;
            dividingTiming = 1;
            tempo = 1;
            fmPointers = new int[] {1};
            fmKeyOffsets = new int[] {0};
            fmVolumeOffsets = new int[] {0};
        }
        @Override public byte[] getVoice(int voiceId) { return new byte[25]; }
        @Override public byte[] getPsgEnvelope(int id) { return null; }
        @Override public int read16(int offset) { return 0; }
        @Override public int getBaseNoteOffset() { return 0; }
    }

    private static final class SingleFmTrackData extends AbstractSmpsData {
        private final String name;

        private SingleFmTrackData(String name) {
            super(new byte[] {0}, 0);
            this.name = name;
        }

        @Override
        protected void parseHeader() {
            channels = 1;
            tempo = 1;
            fmPointers = new int[] {0};
            fmKeyOffsets = new int[] {0};
            fmVolumeOffsets = new int[] {0};
        }

        @Override public byte[] getVoice(int voiceId) { return new byte[0]; }
        @Override public byte[] getPsgEnvelope(int id) { return new byte[0]; }
        @Override public int read16(int offset) { return 0; }
        @Override public int getBaseNoteOffset() { return 0; }
        @Override public String toString() { return name; }
    }
}
