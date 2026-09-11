package com.openggf.audio.driver;

import com.openggf.audio.AudioManager;
import com.openggf.audio.AudioTestFixtures;
import com.openggf.audio.rewind.SmpsDriverSnapshot;
import com.openggf.audio.smps.AbstractSmpsData;
import com.openggf.audio.smps.CoordFlagContext;
import com.openggf.audio.smps.CoordFlagHandler;
import com.openggf.audio.smps.SmpsSequencer;
import com.openggf.audio.smps.SmpsSequencerConfig;
import com.openggf.audio.smps.SmpsSfxData;
import com.openggf.audio.synth.ChipWriteObserver;
import com.openggf.audio.synth.PsgChip;
import com.openggf.audio.synth.VirtualSynthesizer;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestPreparedSfxAdmission {
    private static final int[] ALLOCATION_LIVE_COUNTS = {0, 1, 8, 32, 128};
    private static final long VM_ALLOCATION_TOLERANCE_BYTES_PER_OP = 128;
    private static volatile Object allocationSink;

    @Test
    void sfxConstructionAndPreparationDoNotMutateDriverSynthOrCoordination() {
        SmpsDriver driver = SmpsDriverTestAccess.create(48_000);
        AtomicInteger starts = new AtomicInteger();
        SmpsSequencerConfig config = config(countingHandler(starts));
        Object synthBefore = SmpsDriverTestAccess.captureSynthSnapshot(driver);
        SmpsDriverSnapshot driverBefore = driver.captureSnapshot();

        SmpsSequencer sequencer = sequencer(driver, 0xA0, config,
                track(0, 1), track(0xC0, 2));
        Object sequencerBefore = sequencer.captureSnapshot();

        assertDeepEquals(synthBefore,
                SmpsDriverTestAccess.captureSynthSnapshot(driver));
        assertDriverStateEquals(driverBefore, driver.captureSnapshot());
        assertDeepEquals(sequencerBefore, sequencer.captureSnapshot());
        assertEquals(0, starts.get(),
                "construction must not publish the SFX start");

        PreparedSfxAdmission admission = driver.prepareNewSfxAdmission(
                sequencer, 0, 2);

        assertSame(driver, admission.owner());
        assertSame(sequencer, admission.sequencer());
        assertFalse(admission.continuousExtension());
        assertEquals(0b000001, admission.affectedFmMask());
        assertEquals(0b0100, admission.affectedPsgMask());
        assertDeepEquals(synthBefore,
                SmpsDriverTestAccess.captureSynthSnapshot(driver));
        assertDriverStateEquals(driverBefore, driver.captureSnapshot());
        assertDeepEquals(sequencerBefore, sequencer.captureSnapshot());
        assertEquals(0, starts.get(),
                "preparation must not publish the SFX start");
    }

    @Test
    void preparationFindsSameIdAndFmPsgConflictsWithoutApplyingThem() {
        SmpsDriver driver = SmpsDriverTestAccess.create(48_000);
        SmpsSequencer sameId = sequencer(driver, 0xA0, config(null),
                track(1, 1));
        SmpsSequencer contended = sequencer(driver, 0xA1, config(null),
                track(0, 1), track(0xC0, 2));
        driver.addSequencer(sameId, true);
        driver.addSequencer(contended, true);
        SmpsSequencer.Track fmTrack = contended.getTracks().get(0);
        SmpsSequencer.Track psgTrack = contended.getTracks().get(1);
        driver.writeFm(contended, 0, 0xA0, 0x22);
        driver.writePsg(contended, 0xC4);
        VirtualSynthesizer.Snapshot synthBefore =
                SmpsDriverTestAccess.captureSynthSnapshot(driver);
        SmpsDriverSnapshot before = driver.captureSnapshot();
        List<SmpsSequencer> orderBefore = driver.sequencersForTesting();
        SmpsSequencer replacement = sequencer(driver, 0xA0, config(null),
                track(0, 1), track(0xC0, 2));

        PreparedSfxAdmission admission = driver.prepareNewSfxAdmission(
                replacement, 0, 2);

        assertEquals(0b000001, admission.affectedFmMask());
        assertEquals(0b0100, admission.affectedPsgMask());
        assertIdentityOrder(orderBefore, driver.sequencersForTesting());
        assertTrue(fmTrack.active);
        assertTrue(psgTrack.active);
        assertDriverStateEquals(before, driver.captureSnapshot());
        assertDeepEquals(synthBefore,
                SmpsDriverTestAccess.captureSynthSnapshot(driver));

        replacement.beginSfxAdmission();
        driver.commitSfxAdmission(admission);

        assertEquals(List.of(replacement), driver.sequencersForTesting());
        assertFalse(fmTrack.active,
                "FM contention must retire the displaced track at commit");
        assertFalse(psgTrack.active,
                "PSG contention must retire the displaced track at commit");
    }

    @Test
    void admissionOwnershipTransfersOccupiedAndRetriggeredChannelsWithoutWrites() {
        SmpsDriver driver = SmpsDriverTestAccess.create(48_000);
        SmpsSequencerConfig config = admissionConfig();
        SmpsSequencer first = sequencer(
                driver, 0xA0, config, track(0, 1));
        driver.addSequencer(first, true);
        List<String> writes = new ArrayList<>();
        SmpsDriverTestAccess.setChipWriteObserver(driver, new ChipWriteObserver() {
            @Override
            public void onYm2612Write(int port, int register, int value) {
                writes.add("YM:" + port + ":" + register + ":" + value);
            }

            @Override
            public void onPsgWrite(int value) {
                writes.add("PSG:" + value);
            }
        });

        SmpsSequencer displaced = sequencer(
                driver, 0xA1, config, track(0, 1));
        driver.addSequencer(displaced, true);

        assertTrue(writes.isEmpty(),
                "accepted channel displacement mutates driver RAM only");

        SmpsSequencer retrigger = sequencer(
                driver, 0xA1, config, track(0, 1));
        driver.addSequencer(retrigger, true);

        assertTrue(writes.isEmpty(),
                "same-ID replacement must retain the zero-write admission boundary");
        assertEquals(List.of(retrigger), driver.sequencersForTesting());
    }

    @Test
    void admissionSameIdReplacementReleasesOldOnlyChannelWithoutWrites() {
        SmpsDriver driver = SmpsDriverTestAccess.create(48_000);
        SmpsSequencerConfig config = admissionConfig();
        SmpsSequencer music = sequencer(
                driver, 0x81, config(null), track(0, 1), track(1, 2));
        driver.addSequencer(music, false);
        SmpsSequencer existing = sequencer(
                driver, 0xA0, config, track(0, 1));
        driver.addSequencer(existing, true);
        assertTrue(music.trackAt(0).overridden);
        List<String> writes = new ArrayList<>();
        SmpsDriverTestAccess.setChipWriteObserver(driver, new ChipWriteObserver() {
            @Override
            public void onYm2612Write(int port, int register, int value) {
                writes.add("YM:" + port + ":" + register + ":" + value);
            }

            @Override
            public void onPsgWrite(int value) {
                writes.add("PSG:" + value);
            }
        });
        SmpsSequencer replacement = sequencer(
                driver, 0xA0, config, track(1, 1));

        driver.addSequencer(replacement, true);

        assertTrue(writes.isEmpty(),
                "old-only ownership is released as driver RAM, not a chip write");
        assertFalse(music.trackAt(0).overridden,
                "the dropped role is visible to the next music service");
        assertTrue(music.trackAt(1).overridden,
                "the replacement's newly claimed role is already hidden");
        assertEquals(List.of(music, replacement),
                driver.sequencersForTesting());
        assertEquals(List.of(-1, 1, -1, -1, -1, -1),
                Arrays.stream(driver.captureSnapshot().fmLockSequencerIds())
                        .boxed().toList(),
                "the replacement owns only its newly declared channel");
    }

    @Test
    void firstWriteDiagnosticsPreferAnInterposedOwnerOverDeferredHistory() {
        SmpsDriver driver = SmpsDriverTestAccess.create(48_000);
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
        SmpsSequencer displacedDac = sequencer(
                driver, 0xA0, config(null), track(0x16, 1));
        driver.addSequencer(displacedDac, true);
        driver.writeFm(displacedDac, 1, 0xA2, 0x22);
        SmpsSequencer challengerDac = sequencer(
                driver, 0xA1, config(null), track(0x16, 1));
        driver.addSequencer(challengerDac, true);
        SmpsSequencer interposedFm6 = sequencer(
                driver, 0xA2, config(null), track(5, 1));
        driver.addSequencer(interposedFm6, true);
        driver.writeFm(interposedFm6, 1, 0xA2, 0x44);
        SfxContentionObserver.Source interposedSource = admissions.stream()
                .map(SfxContentionObserver.Admission::source)
                .filter(source -> source.descriptor().id() == 0xA2)
                .findFirst().orElseThrow();
        arbitrations.clear();

        driver.writeFm(challengerDac, 1, 0xA2, 0x66);

        assertEquals(1, arbitrations.size());
        assertEquals(interposedSource, arbitrations.getFirst().previousOwner(),
                "the actual FM6 owner supersedes deferred DAC displacement history");
        assertFalse(arbitrations.getFirst().acquired(),
                "the older equal-priority challenger cannot steal from the interposer");
    }

    @Test
    void mixedFm6DacAndDuplicateNewHeadersStopEachExactConflictOnceInHeaderOrder() {
        OrderedStopDriver driver = new OrderedStopDriver();
        SmpsSequencer existing = sequencer(driver, 0xA0, config(null),
                track(6, 1), track(0x16, 2));
        driver.addSequencer(existing, true);
        driver.watch(existing);
        SmpsSequencer replacement = sequencer(driver, 0xA1, config(null),
                track(0x16, 1), track(6, 2), track(6, 3));

        PreparedSfxAdmission admission = driver.prepareNewSfxAdmission(
                replacement, 0, 3);

        assertEquals(3, conflictArrayCapacity(admission),
                "ordered action storage must be sized by the new header");

        replacement.beginSfxAdmission();
        driver.commitSfxAdmission(admission);

        assertEquals(List.of("DAC", "FM"), driver.stopOrder,
                "duplicate FM6 headers must not stop the old FM6 track twice");
        assertFalse(existing.trackAt(0).active);
        assertFalse(existing.trackAt(1).active);
        assertEquals(List.of(replacement), driver.sequencersForTesting());
    }

    @Test
    void reversedPsgHeadersPreserveLegacyWritesAndFinalChipLatch() {
        SmpsDriver driver = SmpsDriverTestAccess.create(48_000);
        SmpsSequencer existing = sequencer(driver, 0xA0, config(null),
                track(0x80, 1), track(0xA0, 2));
        driver.addSequencer(existing, true);
        SmpsSequencer replacement = sequencer(driver, 0xA1, config(null),
                track(0xA0, 1), track(0x80, 2));
        PreparedSfxAdmission admission = driver.prepareNewSfxAdmission(
                replacement, 0, 2);
        var before = SmpsDriverTestAccess.captureSynthSnapshot(driver);
        PsgChip legacyOracle = new PsgChip();
        legacyOracle.restoreSnapshot(before.psg());
        legacyOracle.write(0xBF);
        legacyOracle.write(0xBF);
        legacyOracle.write(0x9F);
        legacyOracle.write(0x9F);
        List<Integer> psgWrites = new java.util.ArrayList<>();
        SmpsDriverTestAccess.setChipWriteObserver(driver, new ChipWriteObserver() {
            @Override
            public void onYm2612Write(int port, int register, int value) {
            }

            @Override
            public void onPsgWrite(int value) {
                psgWrites.add(value);
            }
        });

        replacement.beginSfxAdmission();
        driver.commitSfxAdmission(admission);

        assertEquals(List.of(0xBF, 0xBF, 0x9F, 0x9F), psgWrites,
                "contention silence writes retain new-header order");
        assertDeepEquals(legacyOracle.captureSnapshot(),
                SmpsDriverTestAccess.captureSynthSnapshot(driver).psg());
        assertEquals(1, SmpsDriverTestAccess.captureSynthSnapshot(driver)
                        .psg().latch(),
                "the final PSG latch must belong to header-last channel 0");
    }

    @Test
    void commitRemovesAnUnrelatedZeroTrackSfxBeforeAddingDifferentId() {
        SmpsDriver driver = SmpsDriverTestAccess.create(48_000);
        SmpsSequencer empty = sequencer(driver, 0xA0, config(null));
        driver.addSequencer(empty, true);
        SmpsSequencer candidate = sequencer(
                driver, 0xA1, config(null), track(0, 1));
        PreparedSfxAdmission admission = driver.prepareNewSfxAdmission(
                candidate, 0, 1);

        candidate.beginSfxAdmission();
        driver.commitSfxAdmission(admission);

        assertEquals(List.of(candidate), driver.sequencersForTesting(),
                "legacy admission cleans an unrelated zero-track SFX");
    }

    @Test
    void commitRemovesEveryFullyInactiveUnrelatedSfxButKeepsPartialOwner() {
        SmpsDriver driver = SmpsDriverTestAccess.create(48_000);
        SmpsSequencer inactive = sequencer(
                driver, 0xA0, config(null), track(0x80, 1));
        inactive.trackAt(0).active = false;
        driver.addSequencer(inactive, true);
        SmpsSequencer partial = sequencer(driver, 0xA1, config(null),
                track(0xA0, 1), track(0xC0, 2));
        partial.trackAt(0).active = false;
        driver.addSequencer(partial, true);
        SmpsSequencer candidate = sequencer(
                driver, 0xA2, config(null), track(0, 1));
        PreparedSfxAdmission admission = driver.prepareNewSfxAdmission(
                candidate, 0, 1);

        candidate.beginSfxAdmission();
        driver.commitSfxAdmission(admission);

        assertEquals(List.of(partial, candidate),
                driver.sequencersForTesting(),
                "only owners with no active tracks are legacy-cleaned");
        assertTrue(partial.trackAt(1).active,
                "cleanup must retain a partially active unrelated owner");
    }

    @Test
    void inactiveOwnerLocksReleaseInLegacyCandidateDeathOrder() {
        SmpsDriver driver = SmpsDriverTestAccess.create(48_000);
        OwnerPair owners = reverseHashOrderedOwners(driver);
        SmpsSequencer first = owners.first();
        SmpsSequencer second = owners.second();
        driver.addSequencer(first, true);
        driver.addSequencer(second, true);

        first.trackAt(1).active = false;
        driver.writePsg(first, 0xDF);
        second.trackAt(1).active = false;
        driver.writeFm(second, 1, 0xA1, 0x22);
        driver.writePsg(second, 0xFF);

        SmpsSequencer candidate = sequencer(driver, 0xA2, config(null),
                track(0x80, 1), track(0xA0, 2));
        PreparedSfxAdmission admission = driver.prepareNewSfxAdmission(
                candidate, 0, 2);
        VirtualSynthesizer.Snapshot before =
                SmpsDriverTestAccess.captureSynthSnapshot(driver);
        List<String> writes = new ArrayList<>();
        SmpsDriverTestAccess.setChipWriteObserver(driver, new ChipWriteObserver() {
            @Override
            public void onYm2612Write(
                    int port, int register, int value) {
                writes.add("YM:" + port + ":" + register + ":" + value);
            }

            @Override
            public void onPsgWrite(int value) {
                writes.add("PSG:" + value);
            }
        });

        candidate.beginSfxAdmission();
        driver.commitSfxAdmission(admission);

        assertEquals(List.of(
                "PSG:159",
                "PSG:159",
                "PSG:191",
                "PSG:191",
                "PSG:223",
                "YM:1:129:255",
                "YM:1:137:255",
                "YM:1:133:255",
                "YM:1:141:255",
                "YM:1:65:127",
                "YM:1:73:127",
                "YM:1:69:127",
                "YM:1:77:127",
                "YM:0:40:5",
                "PSG:255"), writes,
                "dead owners release in the candidate-header action "
                        + "that exhausted them");

        VirtualSynthesizer legacyOracle = new VirtualSynthesizer();
        legacyOracle.restoreSynthSnapshot(before);
        legacyOracle.writePsg(null, 0x9F);
        legacyOracle.writePsg(null, 0xBF);
        legacyOracle.writePsg(null, 0xDF);
        legacyOracle.writeFm(null, 1, 0x81, 0xFF);
        legacyOracle.writeFm(null, 1, 0x89, 0xFF);
        legacyOracle.writeFm(null, 1, 0x85, 0xFF);
        legacyOracle.writeFm(null, 1, 0x8D, 0xFF);
        legacyOracle.writeFm(null, 1, 0x41, 0x7F);
        legacyOracle.writeFm(null, 1, 0x49, 0x7F);
        legacyOracle.writeFm(null, 1, 0x45, 0x7F);
        legacyOracle.writeFm(null, 1, 0x4D, 0x7F);
        legacyOracle.writeFm(null, 0, 0x28, 5);
        legacyOracle.writePsg(null, 0xFF);
        assertDeepEquals(
                legacyOracle.captureSynthSnapshot(),
                SmpsDriverTestAccess.captureSynthSnapshot(driver));
        assertEquals(7, SmpsDriverTestAccess.captureSynthSnapshot(driver)
                        .psg().latch(),
                "header-later owner leaves PSG3 volume as the final latch");
        assertEquals(List.of(candidate), driver.sequencersForTesting());
    }

    @Test
    void continuousExtensionPreparesWithoutASequencerOrCoordinationStart() {
        SmpsDriver driver = SmpsDriverTestAccess.create(48_000);
        SmpsSequencer existing = sequencer(driver, 0xBC, config(null),
                track(0, 1));
        driver.addSequencer(existing, true);
        driver.startContinuousSfx(0xBC, 1);
        SmpsDriverSnapshot before = driver.captureSnapshot();

        PreparedSfxAdmission admission =
                driver.prepareContinuousSfxExtension(0xBC, 1);

        assertNotNull(admission);
        assertTrue(admission.continuousExtension());
        assertNull(admission.sequencer());
        assertEquals(0, admission.affectedFmMask());
        assertEquals(0, admission.affectedPsgMask());
        assertDriverStateEquals(before, driver.captureSnapshot());

        driver.commitSfxAdmission(admission);

        SmpsDriverSnapshot committed = driver.captureSnapshot();
        assertEquals(0xBC, committed.continuousSfxId());
        assertTrue(committed.continuousSfxFlag());
        assertEquals(1, committed.contSfxLoopCnt());
        assertEquals(List.of(existing), driver.sequencersForTesting());
    }

    @Test
    void zeroTrackContinuousExtensionSkipsSequencerStart() {
        SmpsDriver driver = SmpsDriverTestAccess.create(48_000);
        AtomicInteger starts = new AtomicInteger();
        SmpsSequencer existing = sequencer(
                driver, 0xBC, config(countingHandler(starts)));
        driver.addSequencer(existing, true);
        starts.set(0);
        driver.startContinuousSfx(0xBC, 0);

        PreparedSfxAdmission admission =
                driver.prepareContinuousSfxExtension(0xBC, 0);

        assertNotNull(admission);
        assertTrue(admission.continuousExtension());
        assertNull(admission.sequencer());
        assertEquals(0, admission.trackCount());
        driver.commitSfxAdmission(admission);
        assertEquals(0, starts.get());
        assertTrue(driver.isContinuousSfxFlagSet());
        assertEquals(0, driver.captureSnapshot().contSfxLoopCnt());
    }

    @Test
    void nonMatchingOrDeadContinuousSfxDoesNotPrepareAnExtension() {
        SmpsDriver driver = SmpsDriverTestAccess.create(48_000);
        SmpsSequencer existing = sequencer(driver, 0xBC, config(null),
                track(0, 1));
        driver.addSequencer(existing, true);
        driver.startContinuousSfx(0xBC, 1);

        assertNull(driver.prepareContinuousSfxExtension(0xBD, 1));

        driver.stopAllSfx();
        assertNull(driver.prepareContinuousSfxExtension(0xBC, 1));
    }

    @Test
    void preparationRejectsInvalidPointersChannelsPriorityAndContinuousMetadata() {
        SmpsDriver driver = SmpsDriverTestAccess.create(48_000);
        SmpsSequencer badPointer = sequencer(driver, 0xA0, config(null),
                track(0, 99));
        SmpsSequencer badChannel = sequencer(driver, 0xA1, config(null),
                track(0x20, 1));
        SmpsSequencer badPriority = sequencer(driver, 0xA2, config(null),
                track(0, 1));
        badPriority.setSfxPriority(-1);

        assertThrows(IllegalArgumentException.class,
                () -> driver.prepareNewSfxAdmission(badPointer, 0, 1));
        assertThrows(IllegalArgumentException.class,
                () -> driver.prepareNewSfxAdmission(badChannel, 0, 1));
        assertThrows(IllegalArgumentException.class,
                () -> driver.prepareNewSfxAdmission(badPriority, 0, 1));
        assertThrows(IllegalArgumentException.class,
                () -> driver.prepareContinuousSfxExtension(-1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> driver.prepareContinuousSfxExtension(0xBC, -1));
    }

    @Test
    void commitRejectsAnotherDriverAndASecondCommitBeforeMutation() {
        SmpsDriver owner = SmpsDriverTestAccess.create(48_000);
        SmpsDriverTestAccess.setChipWriteObserver(owner, new ChipWriteObserver() {
            @Override
            public void onYm2612Write(int port, int register, int value) {
            }

            @Override
            public void onPsgWrite(int value) {
            }
        });
        SmpsSequencer sequencer = sequencer(owner, 0xA0, config(null),
                track(0, 1));
        PreparedSfxAdmission admission = owner.prepareNewSfxAdmission(
                sequencer, 0, 1);

        assertThrows(IllegalArgumentException.class,
                () -> new SmpsDriver().commitSfxAdmission(admission));
        assertTrue(owner.sequencersForTesting().isEmpty());

        sequencer.beginSfxAdmission();
        owner.commitSfxAdmission(admission);
        assertThrows(IllegalStateException.class,
                () -> owner.commitSfxAdmission(admission));
        assertEquals(List.of(sequencer), owner.sequencersForTesting());
    }

    @Test
    void observerFreeCommitDoesNotCaptureFallbackState() {
        SmpsDriver driver = SmpsDriverTestAccess.create(48_000);
        SmpsSequencer sequencer = sequencer(driver, 0xA0, config(null),
                track(0, 1));
        PreparedSfxAdmission admission = driver.prepareNewSfxAdmission(
                sequencer, 0, 1);

        sequencer.beginSfxAdmission();
        driver.commitSfxAdmission(admission);

        assertEquals(List.of(sequencer), driver.sequencersForTesting());
    }

    @Test
    void observedContentionRollbackRestoresAffectedMusicOverride() {
        SmpsDriver driver = SmpsDriverTestAccess.create(48_000);
        SmpsSequencer music = sequencer(
                driver, 0x81, config(null), track(0, 1));
        driver.addSequencer(music, false);
        music.writeFm(0, 0xA2, 0x22);
        SmpsSequencer candidate = sequencer(
                driver, 0xA0, config(null), track(0, 1));
        PreparedSfxAdmission admission = driver.prepareNewSfxAdmission(
                candidate, 0, 1);
        driver.setSfxContentionObserver(new SfxContentionObserver() {
            @Override
            public void onSfxAdmitted(Admission ignored) {
                throw new IllegalStateException("stop after commit");
            }
        });

        candidate.beginSfxAdmission();
        assertThrows(IllegalStateException.class,
                () -> driver.commitSfxAdmission(admission));

        assertFalse(music.trackAt(0).overridden,
                "rollback must restore the affected music channel override");
    }

    @Test
    void sameIdReplacementJournalsOldOnlyChannelState() {
        SmpsDriver driver = SmpsDriverTestAccess.create(48_000);
        SmpsSequencer existing = sequencer(
                driver, 0xA0, config(null), track(0, 1));
        driver.addSequencer(existing, true);
        existing.writeFm(0, 0xA2, 0x22);
        SmpsSequencer replacement = sequencer(
                driver, 0xA0, config(null), track(1, 1));
        PreparedSfxAdmission admission = driver.prepareNewSfxAdmission(
                replacement, 0, 1);
        SmpsDriverSnapshot before = driver.captureSnapshot();
        SmpsDriverTestAccess.setChipWriteObserver(driver, new ChipWriteObserver() {
            @Override
            public void onYm2612Write(int port, int register, int value) { }

            @Override
            public void onPsgWrite(int value) { }
        });
        AtomicInteger failAdmission = new AtomicInteger();
        driver.setSfxContentionObserver(new SfxContentionObserver() {
            @Override
            public void onSfxAdmitted(Admission ignored) {
                if (failAdmission.get() != 0) {
                    throw new IllegalStateException("after old-only release");
                }
            }
        });
        failAdmission.set(1);

        replacement.beginSfxAdmission();
        assertThrows(IllegalStateException.class,
                () -> driver.commitSfxAdmission(admission));

        assertDriverStateEquals(before, driver.captureSnapshot());
        assertEquals(List.of(existing), driver.sequencersForTesting());
    }

    @Test
    void failedReplacementRestoresDeferredConflictAttribution() {
        SmpsDriver driver = SmpsDriverTestAccess.create(48_000);
        SmpsSequencer displaced = sequencer(
                driver, 0xA0, config(null), track(0x80, 1));
        driver.addSequencer(displaced, true);
        displaced.writePsg(0x90);
        SmpsSequencer first = sequencer(
                driver, 0xA1, config(null), track(0x80, 1));
        PreparedSfxAdmission firstAdmission = driver.prepareNewSfxAdmission(
                first, 0, 1);
        List<SfxContentionObserver.Arbitration> arbitrations =
                new ArrayList<>();
        AtomicInteger failAdmissions = new AtomicInteger();
        driver.setSfxContentionObserver(new SfxContentionObserver() {
            @Override
            public void onSfxAdmitted(Admission ignored) {
                if (failAdmissions.get() != 0) {
                    throw new IllegalStateException("replace failed");
                }
            }

            @Override
            public void onRoleArbitrated(Arbitration arbitration) {
                arbitrations.add(arbitration);
            }
        });
        first.beginSfxAdmission();
        driver.commitSfxAdmission(firstAdmission);

        SmpsSequencer replacement = sequencer(
                driver, 0xA1, config(null), track(0xA0, 1));
        PreparedSfxAdmission replacementAdmission =
                driver.prepareNewSfxAdmission(replacement, 0, 1);
        failAdmissions.set(1);
        replacement.beginSfxAdmission();
        assertThrows(IllegalStateException.class,
                () -> driver.commitSfxAdmission(replacementAdmission));

        failAdmissions.set(0);
        arbitrations.clear();
        first.writePsg(0x90);
        assertEquals(1, arbitrations.size());
        assertEquals(displaced.getSourceDescriptor(),
                arbitrations.getFirst().previousOwner().descriptor());
    }

    @Test
    void preparedStateUsesOnlyChannelBoundedArraysAndNoGeneralCollections() {
        for (Field field : PreparedSfxAdmission.class.getDeclaredFields()) {
            assertFalse(Collection.class.isAssignableFrom(field.getType()),
                    () -> field + " must not scale with unrelated live state");
            assertFalse(Map.class.isAssignableFrom(field.getType()),
                    () -> field + " must not scale with unrelated live state");
        }
    }

    @Test
    void retainedConflictStorageDoesNotGrowWithUnrelatedLiveSfx() {
        for (int unrelatedCount : new int[] {0, 1, 8, 32, 128}) {
            AllocationFixture fixture = allocationFixture(unrelatedCount);
            SmpsDriver driver = fixture.driver;
            SmpsSequencer candidate = fixture.candidate;

            assertEquals(unrelatedCount,
                    driver.sequencersForTesting().size());

            for (int repetition = 0; repetition < 20; repetition++) {
                PreparedSfxAdmission admission =
                        driver.prepareNewSfxAdmission(candidate, 0, 1);

                assertEquals(candidate.trackCount(),
                        admission.displacedOwners.length,
                        "owner storage must stay new-track-bounded at live size "
                                + unrelatedCount);
                assertEquals(candidate.trackCount(),
                        admission.displacedTracks.length,
                        "track storage must stay new-track-bounded at live size "
                                + unrelatedCount);
                assertNull(admission.displacedOwners[0]);
                assertNull(admission.displacedTracks[0]);
            }
        }
    }

    @Test
    void preparationAllocationSlopeIsIndependentOfUnrelatedLiveSfx() {
        java.lang.management.ThreadMXBean baseBean =
                ManagementFactory.getThreadMXBean();
        org.junit.jupiter.api.Assumptions.assumeTrue(
                baseBean instanceof com.sun.management.ThreadMXBean);
        com.sun.management.ThreadMXBean bean =
                (com.sun.management.ThreadMXBean) baseBean;
        org.junit.jupiter.api.Assumptions.assumeTrue(
                bean.isThreadAllocatedMemorySupported());
        if (!bean.isThreadAllocatedMemoryEnabled()) {
            bean.setThreadAllocatedMemoryEnabled(true);
        }

        AllocationFixture[] fixtures =
                new AllocationFixture[ALLOCATION_LIVE_COUNTS.length];
        for (int index = 0; index < ALLOCATION_LIVE_COUNTS.length; index++) {
            fixtures[index] = allocationFixture(
                    ALLOCATION_LIVE_COUNTS[index]);
            warmPreparation(fixtures[index], 50_000);
        }

        long[] minimumBytes = new long[fixtures.length];
        Arrays.fill(minimumBytes, Long.MAX_VALUE);
        long controlBeforeMinimum = Long.MAX_VALUE;
        long controlAfterMinimum = Long.MAX_VALUE;
        for (int repetition = 0; repetition < 7; repetition++) {
            controlBeforeMinimum = Math.min(controlBeforeMinimum,
                    allocatedPerPreparation(bean, fixtures[0], 5_000));
            for (int step = 0; step < fixtures.length; step++) {
                int index = (repetition & 1) == 0
                        ? step : fixtures.length - 1 - step;
                minimumBytes[index] = Math.min(minimumBytes[index],
                        allocatedPerPreparation(
                                bean, fixtures[index], 5_000));
            }
            controlAfterMinimum = Math.min(controlAfterMinimum,
                    allocatedPerPreparation(bean, fixtures[0], 5_000));
        }

        long minimum = Arrays.stream(minimumBytes).min().orElseThrow();
        long maximum = Arrays.stream(minimumBytes).max().orElseThrow();
        long controlSpread = Math.abs(
                controlBeforeMinimum - controlAfterMinimum);
        long allowedSlope = controlSpread
                + VM_ALLOCATION_TOLERANCE_BYTES_PER_OP;
        assertTrue(maximum - minimum <= allowedSlope,
                () -> "preparation allocation grew with unrelated live SFX: "
                        + Arrays.toString(minimumBytes)
                        + " bytes/op for "
                        + Arrays.toString(ALLOCATION_LIVE_COUNTS)
                        + "; controlSpread=" + controlSpread
                        + ", vmTolerance="
                        + VM_ALLOCATION_TOLERANCE_BYTES_PER_OP);
    }

    private static AllocationFixture allocationFixture(int unrelatedCount) {
        SmpsDriver driver = SmpsDriverTestAccess.create(48_000);
        List<SmpsDriverSnapshot.SequencerEntry> entries =
                new ArrayList<>(unrelatedCount);
        for (int index = 0; index < unrelatedCount; index++) {
            SmpsSequencer unrelated = sequencer(
                    driver, 0x200 + index, config(null));
            entries.add(new SmpsDriverSnapshot.SequencerEntry(
                    true,
                    unrelated.getSourceDescriptor(),
                    null,
                    unrelated.getSmpsData(),
                    unrelated.getDacData(),
                    unrelated.getAudioManager(),
                    unrelated.getConfig(),
                    unrelated.captureSnapshot()));
        }
        int[] fmLocks = {-1, -1, -1, -1, -1, -1};
        int[] psgLocks = {-1, -1, -1, -1};
        driver.restoreSnapshot(new SmpsDriverSnapshot(
                SmpsSequencer.Region.NTSC,
                SmpsDriver.ReadMode.HYBRID,
                0,
                false,
                0,
                entries,
                fmLocks,
                psgLocks));
        return new AllocationFixture(driver, sequencer(
                driver, 0xA0, config(null), track(0, 1)));
    }

    private static void warmPreparation(
            AllocationFixture fixture, int iterations) {
        for (int index = 0; index < iterations; index++) {
            allocationSink = fixture.driver.prepareNewSfxAdmission(
                    fixture.candidate, 0, 1);
        }
    }

    private static long allocatedPerPreparation(
            com.sun.management.ThreadMXBean bean,
            AllocationFixture fixture,
            int iterations) {
        long threadId = Thread.currentThread().threadId();
        long before = bean.getThreadAllocatedBytes(threadId);
        warmPreparation(fixture, iterations);
        return (bean.getThreadAllocatedBytes(threadId) - before)
                / iterations;
    }

    private static SmpsSequencer sequencer(
            SmpsDriver driver,
            int id,
            SmpsSequencerConfig config,
            FixtureTrack... tracks) {
        FixtureSfxData data = new FixtureSfxData(id, List.of(tracks));
        SmpsSequencer sequencer = new SmpsSequencer(
                data, AudioTestFixtures.EMPTY_DAC, driver,
                AudioManager.getInstance(), config);
        sequencer.setSfxPriority(0x70);
        return sequencer;
    }

    private static OwnerPair reverseHashOrderedOwners(SmpsDriver driver) {
        for (int attempt = 0; attempt < 1_000; attempt++) {
            SmpsSequencer first = sequencer(driver, 0xA0, config(null),
                    track(0x80, 1), track(0xC0, 2));
            SmpsSequencer second = sequencer(driver, 0xA1, config(null),
                    track(0xA0, 1), track(5, 2));
            HashSet<SmpsSequencer> hashOrder = new HashSet<>();
            hashOrder.add(first);
            hashOrder.add(second);
            if (hashOrder.iterator().next() == second) {
                return new OwnerPair(first, second);
            }
        }
        throw new AssertionError("could not construct reverse HashSet order");
    }

    private static FixtureTrack track(int channelMask, int pointer) {
        return new FixtureTrack(channelMask, pointer, 0, 0);
    }

    private static SmpsSequencerConfig config(CoordFlagHandler handler) {
        return new SmpsSequencerConfig.Builder()
                .coordFlagHandler(handler)
                .build();
    }

    private static SmpsSequencerConfig admissionConfig() {
        return new SmpsSequencerConfig.Builder()
                .sfxChannelOwnershipMode(
                        SmpsSequencerConfig.SfxChannelOwnershipMode.ADMISSION)
                .build();
    }

    private static CoordFlagHandler countingHandler(AtomicInteger starts) {
        return new CoordFlagHandler() {
            @Override
            public void onSfxStart(int sfxId) {
                starts.incrementAndGet();
            }

            @Override
            public boolean handleFlag(CoordFlagContext context,
                    SmpsSequencer.Track track, int command) {
                return false;
            }

            @Override
            public int flagParamLength(int command) {
                return -1;
            }
        };
    }

    private static void assertDriverStateEquals(
            SmpsDriverSnapshot expected, SmpsDriverSnapshot actual) {
        assertDeepEquals(expected, actual);
    }

    private static void assertIdentityOrder(
            List<?> expected, List<?> actual) {
        assertEquals(expected.size(), actual.size());
        for (int index = 0; index < expected.size(); index++) {
            assertSame(expected.get(index), actual.get(index));
        }
    }

    private static int conflictArrayCapacity(
            PreparedSfxAdmission admission) {
        int capacity = -1;
        for (Field field : PreparedSfxAdmission.class.getDeclaredFields()) {
            if (!field.getType().isArray()) {
                continue;
            }
            Class<?> component = field.getType().componentType();
            if (component != SmpsSequencer.class
                    && component != SmpsSequencer.Track.class) {
                continue;
            }
            try {
                field.setAccessible(true);
                int length = Array.getLength(field.get(admission));
                if (capacity == -1) {
                    capacity = length;
                } else {
                    assertEquals(capacity, length,
                            "every ordered conflict array shares one bound");
                }
            } catch (IllegalAccessException failure) {
                throw new AssertionError(failure);
            }
        }
        return capacity;
    }

    private static void assertDeepEquals(Object expected, Object actual) {
        assertDeepEquals(expected, actual, new IdentityHashMap<>());
    }

    private static void assertDeepEquals(
            Object expected, Object actual, Map<Object, Object> seen) {
        if (expected == actual) {
            return;
        }
        assertNotNull(expected);
        assertNotNull(actual);
        assertEquals(expected.getClass(), actual.getClass());
        if (expected.getClass().isArray()) {
            assertEquals(Array.getLength(expected), Array.getLength(actual));
            for (int index = 0; index < Array.getLength(expected); index++) {
                assertDeepEquals(Array.get(expected, index),
                        Array.get(actual, index), seen);
            }
            return;
        }
        if (expected instanceof Iterable<?> expectedValues
                && actual instanceof Iterable<?> actualValues) {
            var expectedIterator = expectedValues.iterator();
            var actualIterator = actualValues.iterator();
            while (expectedIterator.hasNext()) {
                assertTrue(actualIterator.hasNext());
                assertDeepEquals(expectedIterator.next(),
                        actualIterator.next(), seen);
            }
            assertFalse(actualIterator.hasNext());
            return;
        }
        if (!expected.getClass().isRecord()) {
            assertEquals(expected, actual);
            return;
        }
        if (seen.put(expected, actual) != null) {
            return;
        }
        for (RecordComponent component
                : expected.getClass().getRecordComponents()) {
            try {
                assertDeepEquals(component.getAccessor().invoke(expected),
                        component.getAccessor().invoke(actual), seen);
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError(failure);
            }
        }
    }

    private static final class FixtureSfxData extends AbstractSmpsData
            implements SmpsSfxData {
        private final List<FixtureTrack> tracks;

        private FixtureSfxData(int id, List<FixtureTrack> tracks) {
            super(new byte[16], 0);
            setId(id);
            this.tracks = tracks;
        }

        @Override
        public int getTickMultiplier() {
            return 1;
        }

        @Override
        public List<? extends SmpsSfxTrack> getTrackEntries() {
            return tracks;
        }

        @Override
        protected void parseHeader() {
        }

        @Override
        public byte[] getVoice(int voiceId) {
            return voiceId == 0 ? new byte[25] : null;
        }

        @Override
        public byte[] getPsgEnvelope(int id) {
            return null;
        }

        @Override
        public int read16(int offset) {
            return 0;
        }

        @Override
        public int getBaseNoteOffset() {
            return 0;
        }
    }

    private record AllocationFixture(
            SmpsDriver driver, SmpsSequencer candidate) {
    }

    private record OwnerPair(
            SmpsSequencer first, SmpsSequencer second) {
    }

    private record FixtureTrack(
            int channelMask, int pointer, int transpose, int volume)
            implements SmpsSfxData.SmpsSfxTrack {
    }

    private static final class OrderedStopDriver extends SmpsDriver {
        private final List<String> stopOrder = new java.util.ArrayList<>();
        private SmpsSequencer watched;

        private void watch(SmpsSequencer sequencer) {
            watched = sequencer;
        }

        @Override
        public void writeFm(Object source, int port, int reg, int val) {
            if (source == watched && reg == 0x28) {
                stopOrder.add("FM");
            }
            super.writeFm(source, port, reg, val);
        }

        @Override
        public void stopDac(Object source) {
            if (source == watched) {
                stopOrder.add("DAC");
            }
            super.stopDac(source);
        }
    }

}
