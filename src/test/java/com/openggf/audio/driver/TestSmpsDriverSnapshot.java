package com.openggf.audio.driver;

import com.openggf.audio.AudioTestFixtures;
import com.openggf.audio.AudioManager;
import com.openggf.audio.rewind.SmpsDriverSnapshot;
import com.openggf.audio.rewind.SmpsSourceDescriptor;
import com.openggf.audio.smps.AbstractSmpsData;
import com.openggf.audio.smps.DacData;
import com.openggf.audio.smps.SmpsSequencer;
import com.openggf.audio.smps.SmpsSequencerConfig;
import com.openggf.audio.smps.SmpsSequencerTestAccess;
import com.openggf.audio.synth.ChipWriteObserver;
import com.openggf.audio.synth.VirtualSynthesizer;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TestSmpsDriverSnapshot {

    @Test
    void regionQueryTracksConfigurationSnapshotRestoreAndCommandRollback() {
        SmpsDriver driver = new SmpsDriver();
        assertEquals(SmpsSequencer.Region.NTSC, driver.getRegion());
        driver.setRegion(SmpsSequencer.Region.PAL);
        SmpsDriverSnapshot pal = driver.captureSnapshot();
        assertEquals(SmpsSequencer.Region.PAL, driver.getRegion());

        driver.setRegion(SmpsSequencer.Region.NTSC);
        driver.restoreSnapshot(pal, SmpsDriverSnapshot.liveReferences());
        assertEquals(SmpsSequencer.Region.PAL, driver.getRegion());

        SmpsDriver.LiveCommandMutationToken token = driver.captureLiveCommandMutation();
        driver.setRegion(SmpsSequencer.Region.NTSC);
        driver.rollbackLiveCommandMutation(token);
        assertEquals(SmpsSequencer.Region.PAL, driver.getRegion());
    }

    @Test
    void logicalSnapshotContainsNoPhysicalSynthState() {
        assertTrue(Arrays.stream(
                        SmpsDriverSnapshot.class.getRecordComponents())
                .noneMatch(component -> component.getType()
                        == VirtualSynthesizer.Snapshot.class));
    }

    @Test
    void resolvingLogicalMementoEmitsNoChipWrites() {
        AtomicInteger writes = new AtomicInteger();
        ChipWriteObserver observer = new ChipWriteObserver() {
            @Override
            public void onYm2612Write(int port, int register, int value) {
                writes.incrementAndGet();
            }

            @Override
            public void onPsgWrite(int value) {
                writes.incrementAndGet();
            }
        };
        SmpsDriver source = SmpsDriverTestAccess.create(48_000.0, observer);
        source.addSequencer(newSequencer("music", 0x81, source), false);
        SmpsDriverSnapshot memento = source.captureSnapshot();
        SmpsDriver target = SmpsDriverTestAccess.create(48_000.0, observer);
        writes.set(0);

        target.restoreSnapshot(
                memento, SmpsDriverSnapshot.liveReferences());

        assertEquals(0, writes.get());
    }

    @Test
    void precomputedTrustRequiresAnExplicitDescriptor() {
        CountingSmpsData data = new CountingSmpsData(
                new byte[] {1, 2, 3, 4}, 0x81);

        assertThrows(IllegalArgumentException.class,
                () -> new SmpsSequencer(
                        data, AudioTestFixtures.EMPTY_DAC,
                        new SmpsDriver(), AudioManager.getInstance(),
                        new SmpsSequencerConfig.Builder().build(), null,
                        SmpsSequencer.SourceDescriptorTrust
                                .PRECOMPUTED_IMMUTABLE));
    }

    @Test
    void legacyLiveReferenceRestoreRehashesAndRejectsSameObjectMutation() {
        CountingSmpsData data = new CountingSmpsData(
                new byte[] {1, 2, 3, 4}, 0x81);
        SmpsDriver sourceDriver = new SmpsDriver();
        sourceDriver.addSequencer(new SmpsSequencer(
                data, AudioTestFixtures.EMPTY_DAC, sourceDriver,
                AudioManager.getInstance(),
                new SmpsSequencerConfig.Builder().build()), false);
        SmpsDriverSnapshot snapshot = sourceDriver.captureSnapshot();

        data.getDataWithoutCounting()[1] = 9;
        data.resetDataReads();

        IllegalStateException mismatch = assertThrows(
                IllegalStateException.class,
                () -> new SmpsDriver().restoreSnapshot(
                        snapshot, SmpsDriverSnapshot.liveReferences()));

        assertTrue(mismatch.getMessage().contains(
                "resolved SMPS source does not match"));
        assertEquals(1, data.dataReads(),
                "legacy live references must be re-hashed on restore");
    }

    @Test
    void validatedEqualReplacementCannotInheritCatalogTrustAcrossSnapshots() {
        CountingSmpsData catalogData = new CountingSmpsData(
                new byte[] {1, 2, 3, 4}, 0x81);
        SmpsSourceDescriptor descriptor = SmpsSourceDescriptor.baseMusic(
                9, catalogData, catalogData.dataLength(),
                java.util.Arrays.hashCode(
                        catalogData.getDataWithoutCounting()));
        SmpsDriver catalogDriver = new SmpsDriver();
        catalogDriver.addSequencer(new SmpsSequencer(
                catalogData, AudioTestFixtures.EMPTY_DAC, catalogDriver,
                AudioManager.getInstance(),
                new SmpsSequencerConfig.Builder().build(), descriptor,
                SmpsSequencer.SourceDescriptorTrust.PRECOMPUTED_IMMUTABLE),
                false);
        SmpsDriverSnapshot catalogSnapshot = catalogDriver.captureSnapshot();
        CountingSmpsData equalReplacement = new CountingSmpsData(
                new byte[] {1, 2, 3, 4}, 0x81);
        equalReplacement.resetDataReads();

        SmpsDriver replacementDriver = new SmpsDriver();
        replacementDriver.restoreSnapshot(
                catalogSnapshot,
                replacingProgram(equalReplacement));
        assertEquals(1, equalReplacement.dataReads(),
                "a distinct replacement must be validated once");
        SmpsDriverSnapshot replacementSnapshot =
                replacementDriver.captureSnapshot();

        equalReplacement.getDataWithoutCounting()[1] = 9;
        equalReplacement.resetDataReads();

        assertThrows(IllegalStateException.class,
                () -> new SmpsDriver().restoreSnapshot(
                        replacementSnapshot,
                        SmpsDriverSnapshot.liveReferences()));
        assertEquals(1, equalReplacement.dataReads(),
                "replacement identity must remain untrusted after capture");
        assertEquals(SmpsSequencer.SourceDescriptorTrust.LEGACY_RECOMPUTE,
                replacementSnapshot.sequencers().getFirst()
                        .sourceDescriptorTrust());
    }

    @Test
    void storedGenerationDescriptorIsReusedWithoutHashingOnConstructionOrRestore() {
        CountingSmpsData data = new CountingSmpsData(
                new byte[] {1, 2, 3, 4}, 0x81);
        SmpsSourceDescriptor descriptor = SmpsSourceDescriptor.baseMusic(
                9, data, data.dataLength(),
                java.util.Arrays.hashCode(data.getDataWithoutCounting()));
        SmpsSequencerConfig config = new SmpsSequencerConfig.Builder().build();
        SmpsDriver sourceDriver = new SmpsDriver();
        data.resetDataReads();

        SmpsSequencer sequencer = new SmpsSequencer(
                data, AudioTestFixtures.EMPTY_DAC, sourceDriver,
                AudioManager.getInstance(), config, descriptor,
                SmpsSequencer.SourceDescriptorTrust.PRECOMPUTED_IMMUTABLE);
        sourceDriver.addSequencer(sequencer, false);
        SmpsDriverSnapshot snapshot = sourceDriver.captureSnapshot();

        assertEquals(0, data.dataReads());
        assertSame(descriptor, snapshot.sequencers().getFirst().source());
        assertEquals(
                SmpsSequencer.SourceDescriptorTrust.PRECOMPUTED_IMMUTABLE,
                snapshot.sequencers().getFirst().sourceDescriptorTrust());
        assertEquals(9, descriptor.dependencyGeneration());

        SmpsDriver restoredDriver = new SmpsDriver();
        restoredDriver.restoreSnapshot(
                snapshot, SmpsDriverSnapshot.liveReferences());
        SmpsDriverSnapshot restored = restoredDriver.captureSnapshot();

        assertEquals(0, data.dataReads(),
                "same registered program identity must not be re-hashed");
        assertSame(descriptor, restored.sequencers().getFirst().source());
        assertEquals(
                SmpsSequencer.SourceDescriptorTrust.PRECOMPUTED_IMMUTABLE,
                restored.sequencers().getFirst().sourceDescriptorTrust());
        assertSame(data, restored.sequencers().getFirst().smpsData());
        assertSame(config, restored.sequencers().getFirst().config());
    }

    @Test
    void captureAndRestoreRoundTripsSequencersLocksLatchesAndContinuousSfxState() {
        SmpsDriver driver = new SmpsDriver();
        SmpsSequencer music = newSequencer("music", 0x81, driver);
        SmpsSequencer sfx = newSequencer("sfx", 0xBC, driver);
        sfx.setFallbackVoiceData(music.getSmpsData());
        SmpsSequencer.Track musicFm3 = SmpsSequencerTestAccess.addActiveFmTrack(
                music, 2);
        musicFm3.fm3SpecialMode = true;
        musicFm3.customSsgEgPresent = true;
        musicFm3.customSsgEgPayload[0] = 0x22;
        musicFm3.customSsgEgPayloadKnown = true;

        driver.addSequencer(music, false);
        PreparedSfxAdmission admission =
                driver.prepareNewSfxAdmission(sfx, 0xBC, 3);
        sfx.beginSfxAdmission();
        driver.commitSfxAdmission(admission);
        assertTrue(driver.extendContinuousSfx(0xBC, 3));
        driver.decrementContSfxLoopCnt();
        driver.writeFm(sfx, 0, 0xA0, 0x22);
        driver.writePsg(sfx, 0x80 | (2 << 5) | 0x04);
        driver.writePsg(sfx, 0x06);

        SmpsDriverSnapshot snapshot = driver.captureSnapshot();

        driver.stopAll();
        driver.restoreSnapshot(snapshot);

        SmpsDriverSnapshot restored = driver.captureSnapshot();
        assertEquals(2, restored.sequencers().size());
        assertFalse(restored.sequencers().get(0).sfx());
        assertTrue(restored.sequencers().get(1).sfx());
        assertEquals(SmpsSourceDescriptor.from(music.getSmpsData()),
                restored.sequencers().get(0).source());
        assertEquals(SmpsSourceDescriptor.from(sfx.getSmpsData()),
                restored.sequencers().get(1).source());
        assertEquals(SmpsSourceDescriptor.from(music.getSmpsData()),
                restored.sequencers().get(1).fallbackVoiceSource());
        assertEquals(1, restored.fmLockSequencerIds()[0]);
        assertEquals(1, restored.psgLockSequencerIds()[2]);
        assertEquals(2, restored.sequencers().get(1).snapshot().psgLatchChannel());
        assertEquals(0xBC, restored.continuousSfxId());
        assertTrue(restored.continuousSfxFlag());
        assertEquals(2, restored.contSfxLoopCnt());
        assertTrue(restored.sequencers().getFirst().snapshot().tracks()
                .getFirst().fm3SpecialMode());
        assertTrue(restored.sequencers().getFirst().snapshot().tracks()
                .getFirst().customSsgEgPresent());
        assertArrayEquals(new int[] {0x22, 0, 0, 0}, restored.sequencers()
                .getFirst().snapshot().tracks().getFirst().customSsgEgPayload());
        assertTrue(restored.sequencers().getFirst().snapshot().tracks()
                .getFirst().customSsgEgPayloadKnown());
    }

    @Test
    void restoreCanResolveSequencerDependenciesFromSourceDescriptors() {
        SmpsDriver sourceDriver = new SmpsDriver();
        SmpsSequencer music = newSequencer("music", 0x81, sourceDriver);
        SmpsSequencer sfx = newSequencer("sfx", 0xBC, sourceDriver);
        sfx.setFallbackVoiceData(music.getSmpsData());
        sourceDriver.addSequencer(music, false);
        sourceDriver.addSequencer(sfx, true);

        SmpsDriverSnapshot snapshot = sourceDriver.captureSnapshot();
        AbstractSmpsData resolvedMusic = newData("resolved-music", 0x81);
        AbstractSmpsData resolvedSfx = newData("resolved-sfx", 0xBC);
        SmpsSequencerConfig resolvedConfig = new SmpsSequencerConfig.Builder()
                .tempoModBase(0x200)
                .build();
        var resolvedDac = new com.openggf.audio.smps.DacData(
                java.util.Collections.emptyMap(),
                java.util.Collections.emptyMap(),
                144);

        SmpsDriver targetDriver = new SmpsDriver();
        targetDriver.restoreSnapshot(snapshot, new SmpsDriverSnapshot.DependencyResolver() {
            @Override
            public AbstractSmpsData resolveSmpsData(SmpsDriverSnapshot.SequencerEntry entry) {
                return entry.source().id() == 0x81 ? resolvedMusic : resolvedSfx;
            }

            @Override
            public com.openggf.audio.smps.DacData resolveDacData(SmpsDriverSnapshot.SequencerEntry entry) {
                return resolvedDac;
            }

            @Override
            public AudioManager resolveAudioManager(SmpsDriverSnapshot.SequencerEntry entry) {
                return AudioManager.getInstance();
            }

            @Override
            public SmpsSequencerConfig resolveConfig(SmpsDriverSnapshot.SequencerEntry entry) {
                return resolvedConfig;
            }
        });

        SmpsDriverSnapshot restored = targetDriver.captureSnapshot();
        assertSame(resolvedMusic, restored.sequencers().get(0).smpsData());
        assertSame(resolvedSfx, restored.sequencers().get(1).smpsData());
        assertSame(resolvedDac, restored.sequencers().get(0).dacData());
        assertSame(resolvedConfig, restored.sequencers().get(1).config());
        assertEquals(SmpsSourceDescriptor.from(resolvedMusic),
                restored.sequencers().get(1).fallbackVoiceSource());
    }

    @Test
    void capturePreservesRouteAwareSourceDescriptorsAndFallbackRoute() {
        SmpsDriver driver = new SmpsDriver();
        SmpsSequencer music = newSequencer("music", 0x81, driver);
        SmpsSequencer sfx = newSequencer("sfx", 0xBC, driver);
        music.setSourceDescriptor(SmpsSourceDescriptor.baseMusic(music.getSmpsData()));
        sfx.setSourceDescriptor(SmpsSourceDescriptor.baseSfx(sfx.getSmpsData()));
        sfx.setFallbackVoiceData(music.getSmpsData());
        driver.addSequencer(music, false);
        driver.addSequencer(sfx, true);

        SmpsDriverSnapshot snapshot = driver.captureSnapshot();

        assertEquals(SmpsSourceDescriptor.Kind.BASE_MUSIC, snapshot.sequencers().get(0).source().kind());
        assertEquals(SmpsSourceDescriptor.Kind.BASE_SFX_ID, snapshot.sequencers().get(1).source().kind());
        assertEquals(SmpsSourceDescriptor.Kind.BASE_MUSIC,
                snapshot.sequencers().get(1).fallbackVoiceSource().kind());
    }

    @Test
    void sharedExternalFallbackIsDescribedOncePerCaptureAndRehashedAfterMutation() {
        SmpsDriver driver = new SmpsDriver();
        driver.addSequencer(newSequencer("music", 0x81, driver), false);
        CountingSmpsData fallback = new CountingSmpsData(new byte[4096], 0x90);
        for (int index = 0; index < 4; index++) {
            SmpsSequencer sfx = newSequencer("sfx-" + index, 0xB0 + index, driver);
            sfx.setFallbackVoiceData(fallback);
            driver.addSequencer(sfx, true);
        }

        fallback.resetDataReads();
        SmpsDriverSnapshot before = driver.captureSnapshot();
        assertEquals(1, fallback.dataReads(),
                "one external fallback identity should be hashed once per capture");
        int beforeHash = before.sequencers().get(1).fallbackVoiceSource().dataHash();
        for (int index = 1; index < before.sequencers().size(); index++) {
            assertEquals(beforeHash, before.sequencers().get(index).fallbackVoiceSource().dataHash());
        }

        fallback.getDataWithoutCounting()[17] = 1;
        fallback.resetDataReads();
        SmpsDriverSnapshot after = driver.captureSnapshot();

        assertEquals(1, fallback.dataReads(),
                "capture-local dedup must not become a persistent descriptor cache");
        int afterHash = after.sequencers().get(1).fallbackVoiceSource().dataHash();
        assertNotEquals(beforeHash, afterHash,
                "in-place fallback bytes must be re-hashed by the next capture");
        for (int index = 1; index < after.sequencers().size(); index++) {
            assertEquals(afterHash, after.sequencers().get(index).fallbackVoiceSource().dataHash());
        }
    }

    @Test
    void distinctExternalFallbacksAreEachDescribedOnce() {
        SmpsDriver driver = new SmpsDriver();
        driver.addSequencer(newSequencer("music", 0x81, driver), false);
        CountingSmpsData firstFallback = new CountingSmpsData(new byte[] {1, 2, 3}, 0x91);
        CountingSmpsData secondFallback = new CountingSmpsData(new byte[] {4, 5, 6}, 0x92);
        SmpsSequencer firstSfx = newSequencer("first-sfx", 0xB0, driver);
        firstSfx.setFallbackVoiceData(firstFallback);
        SmpsSequencer secondSfx = newSequencer("second-sfx", 0xB1, driver);
        secondSfx.setFallbackVoiceData(secondFallback);
        driver.addSequencer(firstSfx, true);
        driver.addSequencer(secondSfx, true);
        firstFallback.resetDataReads();
        secondFallback.resetDataReads();

        SmpsDriverSnapshot snapshot = driver.captureSnapshot();

        assertEquals(1, firstFallback.dataReads());
        assertEquals(1, secondFallback.dataReads());
        assertEquals(java.util.Arrays.hashCode(firstFallback.getDataWithoutCounting()),
                snapshot.sequencers().get(1).fallbackVoiceSource().dataHash());
        assertEquals(java.util.Arrays.hashCode(secondFallback.getDataWithoutCounting()),
                snapshot.sequencers().get(2).fallbackVoiceSource().dataHash());
    }

    @Test
    void restoreWithResolverIsAtomicWhenSourceIsMissing() {
        SmpsDriver sourceDriver = new SmpsDriver();
        sourceDriver.addSequencer(newSequencer("music", 0x81, sourceDriver), false);
        sourceDriver.addSequencer(newSequencer("sfx", 0xBC, sourceDriver), true);
        SmpsDriverSnapshot snapshot = sourceDriver.captureSnapshot();

        SmpsDriver targetDriver = new SmpsDriver();
        targetDriver.addSequencer(newSequencer("existing", 0x90, targetDriver), false);
        SmpsDriverSnapshot before = targetDriver.captureSnapshot();

        assertThrows(IllegalStateException.class, () -> targetDriver.restoreSnapshot(
                snapshot,
                new SmpsDriverSnapshot.DependencyResolver() {
                    @Override
                    public AbstractSmpsData resolveSmpsData(SmpsDriverSnapshot.SequencerEntry entry) {
                        if (entry.source().id() == 0xBC) {
                            throw new IllegalStateException("missing source");
                        }
                        return newData("resolved-music", 0x81);
                    }

                    @Override
                    public com.openggf.audio.smps.DacData resolveDacData(SmpsDriverSnapshot.SequencerEntry entry) {
                        return AudioTestFixtures.EMPTY_DAC;
                    }

                    @Override
                    public AudioManager resolveAudioManager(SmpsDriverSnapshot.SequencerEntry entry) {
                        return AudioManager.getInstance();
                    }

                    @Override
                    public SmpsSequencerConfig resolveConfig(SmpsDriverSnapshot.SequencerEntry entry) {
                        return new SmpsSequencerConfig.Builder().build();
                    }
                }));

        SmpsDriverSnapshot after = targetDriver.captureSnapshot();
        assertEquals(before.sequencers().size(), after.sequencers().size());
        assertEquals(before.sequencers().get(0).source(), after.sequencers().get(0).source());
    }

    private static SmpsSequencer newSequencer(String name, int id, SmpsDriver driver) {
        AbstractSmpsData data = newData(name, id);
        return new SmpsSequencer(
                data,
                AudioTestFixtures.EMPTY_DAC,
                driver,
                AudioManager.getInstance(),
                new SmpsSequencerConfig.Builder().build());
    }

    private static AbstractSmpsData newData(String name, int id) {
        AbstractSmpsData data = new AudioTestFixtures.StubSmpsData(name);
        data.setId(id);
        return data;
    }

    private static SmpsDriverSnapshot.DependencyResolver replacingProgram(
            AbstractSmpsData replacement) {
        return new SmpsDriverSnapshot.DependencyResolver() {
            @Override
            public AbstractSmpsData resolveSmpsData(
                    SmpsDriverSnapshot.SequencerEntry entry) {
                return replacement;
            }

            @Override
            public DacData resolveDacData(
                    SmpsDriverSnapshot.SequencerEntry entry) {
                return entry.dacData();
            }

            @Override
            public AudioManager resolveAudioManager(
                    SmpsDriverSnapshot.SequencerEntry entry) {
                return AudioManager.getInstance();
            }

            @Override
            public SmpsSequencerConfig resolveConfig(
                    SmpsDriverSnapshot.SequencerEntry entry) {
                return entry.config();
            }
        };
    }

    private static final class CountingSmpsData extends AbstractSmpsData {
        private int dataReads;

        private CountingSmpsData(byte[] data, int id) {
            super(data, 0);
            setId(id);
        }

        @Override public byte[] getData() { dataReads++; return super.getData(); }
        private byte[] getDataWithoutCounting() { return super.getData(); }
        private int dataReads() { return dataReads; }
        private void resetDataReads() { dataReads = 0; }
        @Override protected void parseHeader() { }
        @Override public byte[] getVoice(int voiceId) { return new byte[0]; }
        @Override public byte[] getPsgEnvelope(int id) { return new byte[0]; }
        @Override public int read16(int offset) { return 0; }
        @Override public int getBaseNoteOffset() { return 0; }
    }

    private static SmpsDriver configuredDriver() {
        SmpsDriver driver = new SmpsDriver();
        driver.setDacData(new DacData(
                Map.of(1, new byte[] { 0, 24, 64, 127, (byte) 255, (byte) 196, 96, 32, 8, 0 }),
                Map.of(0x81, new DacData.DacEntry(1, 4)),
                295));
        driver.setDacInterpolate(true);
        return driver;
    }

    private static void primeSynth(SmpsDriver driver) {
        driver.writeFm(driver, 0, 0x22, 0x0B);
        driver.writeFm(driver, 0, 0x2B, 0x80);
        driver.setInstrument(driver, 0, new byte[] {
                0x32,
                0x71, 0x0D, 0x33, 0x01,
                0x5F, 0x5F, 0x5F, 0x5F,
                0x14, 0x0E, 0x0E, 0x0E,
                0x08, 0x08, 0x08, 0x08,
                0x0F, 0x0F, 0x0F, 0x0F,
                0x1B, 0x16, 0x1F, 0x00
        });
        driver.writeFm(driver, 0, 0xA4, 0x22);
        driver.writeFm(driver, 0, 0xA0, 0x69);
        driver.writeFm(driver, 0, 0xB4, 0xC7);
        driver.writeFm(driver, 0, 0x28, 0xF0);
        driver.playDac(driver, 0x81);
        driver.writePsg(driver, 0x80 | 0x04);
        driver.writePsg(driver, 0x12);
        driver.writePsg(driver, 0x90 | 0x02);
        driver.writePsg(driver, 0xE4);
        driver.writePsg(driver, 0xF0 | 0x04);
    }

    private static void perturbSynth(SmpsDriver driver) {
        driver.writeFm(driver, 0, 0x2A, 0x5A);
        driver.writeFm(driver, 0, 0x40, 0x23);
        driver.writePsg(driver, 0xE7);
        driver.writePsg(driver, 0xF2);
        driver.playDac(driver, 0x81);
    }
}
