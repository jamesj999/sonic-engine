package com.openggf.audio.presentation;

import com.openggf.audio.AudioManager;
import com.openggf.audio.AudioTestFixtures;
import com.openggf.audio.driver.SmpsDriver;
import com.openggf.audio.driver.SmpsDriverTestAccess;
import com.openggf.audio.rewind.AudioSourceDescriptor;
import com.openggf.audio.rewind.SmpsSourceDescriptor;
import com.openggf.audio.smps.AbstractSmpsData;
import com.openggf.audio.smps.DacData;
import com.openggf.audio.smps.SmpsCoordFlagHandlerOwner;
import com.openggf.audio.smps.SmpsCoordFlagRuntimeState;
import com.openggf.audio.smps.SmpsSequencer;
import com.openggf.audio.smps.SmpsSequencerConfig;
import com.openggf.audio.smps.SmpsSfxData;
import com.openggf.audio.session.SmpsSessionTestSupport;
import com.openggf.audio.synth.ChipWriteObserver;
import com.openggf.game.sonic1.audio.Sonic1SmpsSequencerConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestSmpsAssetCatalog {
    private static final long GENERATION = 7;

    @AfterEach
    void tearDown() {
        AudioManager.getInstance().resetState();
    }

    @Test
    void immutableConfigCopyPreservesSfxAdmissionPolicies() {
        SmpsSequencerConfig source = new SmpsSequencerConfig.Builder()
                .sfxChannelOwnershipMode(
                        SmpsSequencerConfig.SfxChannelOwnershipMode.ADMISSION)
                .psg3SfxAdmissionWriteMode(
                        SmpsSequencerConfig.Psg3SfxAdmissionWriteMode
                                .SILENCE_TONE_AND_NOISE)
                .psgSfxTakeoverMode(
                        SmpsSequencerConfig.PsgSfxTakeoverMode
                                .S1_PSG3_SILENCE_PAIR)
                .build();

        SmpsSequencerConfig copy =
                SmpsAssetCatalog.copyConfigWithoutHandler(source);

        assertEquals(SmpsSequencerConfig.SfxChannelOwnershipMode.ADMISSION,
                copy.getSfxChannelOwnershipMode());
        assertEquals(
                SmpsSequencerConfig.Psg3SfxAdmissionWriteMode
                        .SILENCE_TONE_AND_NOISE,
                copy.getPsg3SfxAdmissionWriteMode());
        assertEquals(
                SmpsSequencerConfig.PsgSfxTakeoverMode
                        .S1_PSG3_SILENCE_PAIR,
                copy.getPsgSfxTakeoverMode());
    }

    @Test
    void copiedSonic1ConfigKeepsOnePsg3PairOnAdmissionAndReplacement() {
        SmpsSequencerConfig copy = SmpsAssetCatalog.copyConfigWithoutHandler(
                Sonic1SmpsSequencerConfig.CONFIG);
        SmpsDriver driver = SmpsDriverTestAccess.create(48_000);
        List<Integer> writes = new ArrayList<>();
        SmpsDriverTestAccess.setChipWriteObserver(driver, new ChipWriteObserver() {
            @Override
            public void onYm2612Write(int port, int register, int value) {
            }

            @Override
            public void onPsgWrite(int value) {
                writes.add(value);
            }
        });
        SmpsSequencer oldSfx = psg3Sequencer(driver, copy, 0xC1);
        SmpsSequencer replacement = psg3Sequencer(driver, copy, 0xC2);

        driver.addSequencer(oldSfx, true);
        assertEquals(List.of(0xDF, 0xFF), writes,
                "the frozen S1 config must retain its explicit header pair");
        driver.writePsg(oldSfx, 0xC0);
        writes.clear();

        driver.addSequencer(replacement, true);
        assertEquals(List.of(0xDF, 0xFF), writes,
                "replacement must not add generic cleanup around S1's pair");
    }

    @Test
    void firstRegistrationFreezesOnceAndSameSourceRepeatIsConstantWork() {
        SmpsAssetCatalog catalog = catalog();
        CountingSfxData source = CountingSfxData.sfx(0xA0, 1);
        DacData dac = dac(1);
        SmpsSequencerConfig config = config();
        SmpsAssetCatalog.ProgramKey key = programKey(
                new SmpsAssetKey("base", SmpsAssetKey.Route.BASE_ID,
                        0xA0, null), GENERATION);

        SmpsAssetCatalog.ProgramEntry first = catalog.register(
                key, source, dac, config, false);
        int readsAfterFirstRegistration = source.dataReads();
        SmpsAssetCatalog.ProgramEntry repeated = catalog.register(
                key, source, dac, config, false);

        assertSame(first, repeated);
        assertEquals(readsAfterFirstRegistration, source.dataReads(),
                "same provenance must return before any program-sized read");
        assertNotSame(source, first.program());
        assertSame(first.program(), first.programView());
        assertSame(dac, first.dac());
        assertEquals(0xA0, first.assetId());
        assertEquals(1, first.trackCount());
        assertFalse(first.specialSfx());
        assertEquals(GENERATION,
                first.sourceDescriptor().dependencyGeneration());
        assertSame(first, catalog.find(key));
        assertSame(first, catalog.require(first.sourceDescriptor()));
    }

    @Test
    void reconstructedEqualProgramReusesEntryButConflictingBytesAreRejected() {
        SmpsAssetCatalog catalog = catalog();
        DacData dac = dac(1);
        SmpsSequencerConfig config = config();
        SmpsAssetCatalog.ProgramKey key = programKey(
                new SmpsAssetKey("base", SmpsAssetKey.Route.BASE_ID,
                        0xA0, null), GENERATION);
        SmpsAssetCatalog.ProgramEntry first = catalog.register(
                key, CountingSfxData.sfx(0xA0, 1), dac, config, false);

        SmpsAssetCatalog.ProgramEntry equal = catalog.register(
                key, CountingSfxData.sfx(0xA0, 1), dac, config, false);
        IllegalStateException conflict = assertThrows(
                IllegalStateException.class,
                () -> catalog.register(key,
                        CountingSfxData.sfx(0xA0, 2), dac, config, false));

        assertSame(first, equal);
        assertTrue(conflict.getMessage().contains("BASE_ID"));
        assertTrue(conflict.getMessage().contains("generation=7"));
        assertSame(first, catalog.find(key));
    }

    @Test
    void oneGenerationRejectsDifferentDependencyObjectsBeforePublishingProgram() {
        SmpsAssetCatalog catalog = catalog();
        DacData dac = dac(1);
        SmpsSequencerConfig config = config();
        CountingSfxData original = CountingSfxData.sfx(0xA0, 1);
        catalog.register(programKey(new SmpsAssetKey(
                        "base", SmpsAssetKey.Route.BASE_ID, 0xA0, null),
                        GENERATION),
                original, dac, config, false);
        SmpsAssetCatalog.ProgramKey unpublished = programKey(
                new SmpsAssetKey("base", SmpsAssetKey.Route.BASE_ID,
                        0xA1, null), GENERATION);

        IllegalStateException differentDac = assertThrows(
                IllegalStateException.class,
                () -> catalog.register(unpublished,
                        CountingSfxData.sfx(0xA1, 1), dac(1), config,
                        false));
        IllegalStateException differentConfig = assertThrows(
                IllegalStateException.class,
                () -> catalog.register(unpublished,
                        CountingSfxData.sfx(0xA1, 1), dac, config(),
                        false));
        IllegalStateException sameProgramDifferentDac = assertThrows(
                IllegalStateException.class,
                () -> catalog.register(programKey(new SmpsAssetKey(
                                "base", SmpsAssetKey.Route.BASE_ID,
                                0xA0, null), GENERATION),
                        original, dac(2), config, false));

        assertTrue(differentDac.getMessage().contains("DependencyKey"));
        assertTrue(differentConfig.getMessage().contains("DependencyKey"));
        assertTrue(sameProgramDifferentDac.getMessage()
                .contains("DependencyKey"));
        assertNull(catalog.find(unpublished));
    }

    @Test
    void sfxProgramsShareOneFrozenDependencyWithinAProvenanceGeneration() {
        SmpsAssetCatalog catalog = catalog();
        DacData dac = dac(1);
        SmpsSequencerConfig config = config();

        SmpsAssetCatalog.ProgramEntry first = catalog.register(
                programKey(new SmpsAssetKey("base",
                        SmpsAssetKey.Route.BASE_ID, 0xA0, null), GENERATION),
                CountingSfxData.sfx(0xA0, 1), dac, config, false);
        SmpsAssetCatalog.ProgramEntry second = catalog.register(
                programKey(new SmpsAssetKey("base",
                        SmpsAssetKey.Route.BASE_ID, 0xA1, null), GENERATION),
                CountingSfxData.sfx(0xA1, 2), dac, config, true);

        assertNotSame(first.program(), second.program());
        assertSame(first.dac(), second.dac());
        assertSame(first.staticConfig(), second.staticConfig());
        assertFalse(first.specialSfx());
        assertTrue(second.specialSfx());
        assertNotEquals(first.sourceDescriptor(), second.sourceDescriptor());
    }

    @Test
    void routesAndGenerationsRemainDistinctAndOldProgramsStayResolvable() {
        SmpsAssetCatalog catalog = catalog();
        DacData baseDac = dac(1);
        SmpsSequencerConfig baseConfig = config();
        DacData donorDac = dac(2);
        SmpsSequencerConfig donorConfig = config();
        AbstractSmpsData bytes = CountingMusicData.music(0, 1);

        SmpsAssetCatalog.ProgramEntry baseMusic = catalog.register(
                programKey(new SmpsAssetKey("same",
                        SmpsAssetKey.Route.BASE_MUSIC, 0x81, null), 1),
                bytes, baseDac, baseConfig, false);
        SmpsAssetCatalog.ProgramEntry donorMusic = catalog.register(
                programKey(new SmpsAssetKey("same",
                        SmpsAssetKey.Route.DONOR_MUSIC, 0x81, null), 1),
                CountingMusicData.music(0, 1), donorDac, donorConfig,
                false);
        SmpsAssetCatalog.ProgramEntry baseSfx = catalog.register(
                programKey(new SmpsAssetKey("same",
                        SmpsAssetKey.Route.BASE_ID, 0x81, null), 1),
                CountingSfxData.sfx(0x81, 1), baseDac, baseConfig, false);
        SmpsAssetCatalog.ProgramEntry namedSfx = catalog.register(
                programKey(new SmpsAssetKey("same",
                        SmpsAssetKey.Route.BASE_NAME, -1, "named"), 1),
                CountingSfxData.sfx(0x81, 1), baseDac, baseConfig, true);
        SmpsAssetCatalog.ProgramEntry donorSfx = catalog.register(
                programKey(new SmpsAssetKey("same",
                        SmpsAssetKey.Route.DONOR_ID, 0x81, null), 1),
                CountingSfxData.sfx(0x81, 1), donorDac, donorConfig,
                false);
        SmpsAssetCatalog.ProgramEntry nextGeneration = catalog.register(
                programKey(new SmpsAssetKey("same",
                        SmpsAssetKey.Route.BASE_MUSIC, 0x81, null), 2),
                CountingMusicData.music(0, 2), dac(3), config(), false);

        assertEquals(0x81, baseMusic.assetId());
        assertEquals(0x81, baseMusic.sourceDescriptor().id());
        assertEquals(0x81, donorMusic.assetId());
        assertEquals(0x81, donorMusic.sourceDescriptor().id());
        assertEquals(SmpsSourceDescriptor.Kind.BASE_MUSIC,
                baseMusic.sourceDescriptor().kind());
        assertEquals(SmpsSourceDescriptor.Kind.DONOR_MUSIC,
                donorMusic.sourceDescriptor().kind());
        assertEquals(SmpsSourceDescriptor.Kind.BASE_SFX_ID,
                baseSfx.sourceDescriptor().kind());
        assertEquals(SmpsSourceDescriptor.Kind.BASE_SFX_NAME,
                namedSfx.sourceDescriptor().kind());
        assertEquals(SmpsSourceDescriptor.Kind.DONOR_SFX_ID,
                donorSfx.sourceDescriptor().kind());
        assertNotEquals(baseMusic.sourceDescriptor(),
                donorMusic.sourceDescriptor());
        assertNotEquals(baseMusic.sourceDescriptor(),
                baseSfx.sourceDescriptor());
        assertNotEquals(baseSfx.sourceDescriptor(),
                namedSfx.sourceDescriptor());
        assertNotEquals(baseSfx.sourceDescriptor(),
                donorSfx.sourceDescriptor());
        assertNotSame(baseMusic.program(), nextGeneration.program());
        assertEquals(1, baseMusic.sourceDescriptor().dependencyGeneration());
        assertEquals(2,
                nextGeneration.sourceDescriptor().dependencyGeneration());
        assertSame(baseMusic, catalog.require(baseMusic.sourceDescriptor()));
        assertSame(nextGeneration,
                catalog.require(nextGeneration.sourceDescriptor()));
    }

    @Test
    void descriptorCollisionAcrossDifferentBaseDependenciesIsRejected() {
        SmpsAssetCatalog catalog = catalog();
        SmpsAssetCatalog.ProgramEntry first = catalog.register(
                programKey(new SmpsAssetKey("base-a",
                        SmpsAssetKey.Route.BASE_ID, 0xA0, null), 1),
                CountingSfxData.sfx(0xA0, 1), dac(1), config(), false);

        IllegalStateException collision = assertThrows(
                IllegalStateException.class,
                () -> catalog.register(
                        programKey(new SmpsAssetKey("base-b",
                                SmpsAssetKey.Route.BASE_ID, 0xA0, null), 1),
                        CountingSfxData.sfx(0xA0, 1), dac(2), config(),
                        false));

        assertTrue(collision.getMessage().contains("descriptor collision"));
        assertSame(first, catalog.require(first.sourceDescriptor()));
    }

    @Test
    void unversionedConflictCannotConsumeOrAdvertiseRealGenerationOne() {
        AudioPresentationSourceFactory factory = factory();
        SmpsAssetKey key = new SmpsAssetKey(
                "base", SmpsAssetKey.Route.BASE_ID, 0xA0, null);
        DacData generationZeroDac = dac(1);
        SmpsSequencerConfig generationZeroConfig = config();
        SmpsAssetCatalog.ProgramEntry generationZero =
                factory.registerSmpsSfxAsset(
                        key, CountingSfxData.sfx(0xA0, 1),
                        generationZeroDac, generationZeroConfig, false);

        assertThrows(IllegalStateException.class,
                () -> factory.registerSmpsSfxAsset(
                        key, CountingSfxData.sfx(0xA0, 2),
                        dac(2), config(), false));

        assertEquals(0,
                generationZero.sourceDescriptor().dependencyGeneration());
        assertNull(factory.findRegisteredSmpsSfxAsset(key, 1));

        DacData generationOneDac = dac(3);
        SmpsSequencerConfig generationOneConfig = config();
        SmpsAssetCatalog.ProgramEntry generationOne =
                factory.registerSmpsSfxAsset(
                        key, 1, CountingSfxData.sfx(0xA0, 2),
                        generationOneDac, generationOneConfig, false);

        assertEquals(1,
                generationOne.sourceDescriptor().dependencyGeneration());
        assertSame(generationOneDac, generationOne.dac());
        assertSame(generationOne,
                factory.findRegisteredSmpsSfxAsset(key, 1));
    }

    @Test
    void rejectedUnversionedReplacementCannotRetargetQueuedSource() {
        AudioPresentationSourceFactory factory = factory();
        SmpsAssetKey key = new SmpsAssetKey(
                "base", SmpsAssetKey.Route.BASE_ID, 0xA0, null);
        SmpsAssetCatalog.ProgramEntry registeredA =
                factory.registerSmpsSfxAsset(
                        key, CountingSfxData.sfx(0xA0, 1),
                        dac(1), config(), false);
        ResolvedSmpsSfxSource queuedA = factory.resolveSmpsSfx(
                1, key, 1 << 16, 0x70, 0, 1, 32);

        assertThrows(IllegalStateException.class,
                () -> factory.registerSmpsSfxAsset(
                        key, CountingSfxData.sfx(0xA0, 2),
                        dac(2), config(), false));
        SmpsSequencer instantiatedA = factory.instantiateCached(
                queuedA, new com.openggf.audio.driver.SmpsDriver());

        assertSame(registeredA.program(), instantiatedA.getSmpsData());
        assertSame(registeredA.sourceDescriptor(),
                instantiatedA.getSourceDescriptor());
        assertEquals(1, instantiatedA.programView().dataByteAt(1));
    }

    @Test
    void repeatedBaseAndDonorMusicShareAssetsButNotMutablePlaybackState() {
        AudioPresentationSourceFactory factory = factory();
        assertMusicSharing(factory, "base", SmpsAssetKey.Route.BASE_MUSIC,
                AudioSourceDescriptor.baseMusic(0x81), 3, dac(1), config());
        assertMusicSharing(factory, "donor", SmpsAssetKey.Route.DONOR_MUSIC,
                AudioSourceDescriptor.donorMusic("donor", 0x81), 4,
                dac(2), config());
    }

    private static void assertMusicSharing(
            AudioPresentationSourceFactory factory,
            String gameId,
            SmpsAssetKey.Route route,
            AudioSourceDescriptor logicalDescriptor,
            long generation,
            DacData dac,
            SmpsSequencerConfig config) {
        CountingMusicData firstSource = CountingMusicData.music(0x81, 1);
        AudioPresentationCommand.MusicVoiceEntry firstEntry =
                factory.musicSmps(gameId, 0x81, 10 + generation,
                        generation, firstSource, dac, config,
                        logicalDescriptor, 32);
        AudioPresentationCommand.MusicVoiceEntry secondEntry =
                factory.musicSmps(gameId, 0x81, 20 + generation,
                        generation, CountingMusicData.music(0x81, 1),
                        dac, config, logicalDescriptor, 32);
        var firstSequencer = ((AudioPresentationCommand.SmpsVoiceDescriptor)
                firstEntry.voiceDescriptor()).activation().incomingMusic();
        var secondSequencer = ((AudioPresentationCommand.SmpsVoiceDescriptor)
                secondEntry.voiceDescriptor()).activation().incomingMusic();
        SmpsAssetCatalog.ProgramEntry registered =
                factory.findRegisteredSmpsMusicAsset(
                        new SmpsAssetKey(gameId, route, 0x81, null),
                        generation);

        assertSame(registered.program(), firstSequencer.smpsData());
        assertSame(registered.program(), secondSequencer.smpsData());
        assertSame(registered.dac(), firstSequencer.dacData());
        assertSame(registered.dac(), secondSequencer.dacData());
        assertSame(registered.staticConfig(), firstSequencer.config());
        assertSame(registered.staticConfig(), secondSequencer.config());
        assertSame(registered.sourceDescriptor(),
                firstSequencer.source());
        assertSame(registered.sourceDescriptor(),
                secondSequencer.source());
        assertNotSame(firstSequencer, secondSequencer);
        assertNotSame(firstSequencer.snapshot(), secondSequencer.snapshot());
        assertEquals(firstSequencer.snapshot().normalTempo(),
                secondSequencer.snapshot().normalTempo());
        assertEquals(firstSequencer.snapshot().tracks().getFirst().pos(),
                secondSequencer.snapshot().tracks().getFirst().pos());
    }

    private static SmpsAssetCatalog catalog() {
        return new SmpsAssetCatalog(new SmpsCoordFlagHandlerOwner(
                new SmpsCoordFlagRuntimeState()));
    }

    private static AudioPresentationSourceFactory factory() {
        return new AudioPresentationSourceFactory(
                () -> true,
                new SmpsCoordFlagHandlerOwner(new SmpsCoordFlagRuntimeState()),
                new AudioPresentationSourceFactory.Settings(
                        48_000, SmpsSequencer.Region.NTSC,
                        false, false, false, 1,
                        AudioManager.getInstance(), new DecodedPcmCache(),
                        ignored -> null),
                SmpsSessionTestSupport.installed(48_000));
    }

    private static SmpsAssetCatalog.ProgramKey programKey(
            SmpsAssetKey key, long generation) {
        return new SmpsAssetCatalog.ProgramKey(key, generation);
    }

    private static DacData dac(int value) {
        return new DacData(Map.of(1, new byte[] {(byte) value}),
                Map.of(0x81, new DacData.DacEntry(1, value)), 288 + value);
    }

    private static SmpsSequencerConfig config() {
        return new SmpsSequencerConfig.Builder()
                .fmChannelOrder(new int[] {0})
                .psgChannelOrder(new int[] {0x80})
                .build();
    }

    private static SmpsSequencer psg3Sequencer(
            SmpsDriver driver, SmpsSequencerConfig config, int id) {
        return new SmpsSequencer(new Psg3SfxData(id),
                AudioTestFixtures.EMPTY_DAC, driver, () -> {}, config);
    }

    private static final class Psg3SfxData extends AbstractSmpsData
            implements SmpsSfxData {
        private Psg3SfxData(int id) {
            super(new byte[] {0, (byte) 0xF2}, 0);
            setId(id);
        }

        @Override public int getTickMultiplier() { return 1; }
        @Override public List<? extends SmpsSfxTrack> getTrackEntries() {
            return List.of(new SfxTrack(0xC0, 1, 0, 0));
        }
        @Override protected void parseHeader() { dividingTiming = 1; tempo = 1; }
        @Override public byte[] getVoice(int voiceId) { return new byte[25]; }
        @Override public byte[] getPsgEnvelope(int id) { return null; }
        @Override public int read16(int offset) { return 0; }
        @Override public int getBaseNoteOffset() { return 0; }
    }

    private abstract static class CountingData extends AbstractSmpsData {
        private static final byte[] VOICE = {1, 2, 3};
        private static final byte[] PSG_ENVELOPE = {4, 5};
        private static final byte[] MOD_ENVELOPE = {6, 7};
        private int dataReads;

        private CountingData(int id, int marker) {
            super(new byte[] {0, (byte) marker, (byte) 0xF2}, 0);
            this.id = id;
            channels = 1;
            psgChannels = 0;
            dividingTiming = 1;
            tempo = 0x80;
            fmPointers = new int[] {1};
            fmKeyOffsets = new int[] {2};
            fmVolumeOffsets = new int[] {3};
        }

        @Override
        public byte[] getData() {
            dataReads++;
            return super.getData();
        }

        int dataReads() {
            return dataReads;
        }

        @Override protected void parseHeader() { }
        @Override public byte[] getVoice(int voiceId) {
            return voiceId == 0 ? VOICE.clone() : null;
        }
        @Override public byte[] getPsgEnvelope(int id) {
            return id == 1 ? PSG_ENVELOPE.clone() : null;
        }
        @Override public byte[] getModEnvelope(int id) {
            return id == 2 ? MOD_ENVELOPE.clone() : null;
        }
        @Override public int read16(int offset) {
            return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
        }
        @Override public int getBaseNoteOffset() { return 12; }
        @Override public int getPsgBaseNoteOffset() { return 24; }
    }

    private static final class CountingMusicData extends CountingData {
        private CountingMusicData(int id, int marker) {
            super(id, marker);
        }

        static CountingMusicData music(int id, int marker) {
            return new CountingMusicData(id, marker);
        }
    }

    private static final class CountingSfxData extends CountingData
            implements SmpsSfxData {
        private CountingSfxData(int id, int marker) {
            super(id, marker);
        }

        static CountingSfxData sfx(int id, int marker) {
            return new CountingSfxData(id, marker);
        }

        @Override public int getTickMultiplier() { return 1; }
        @Override public List<? extends SmpsSfxTrack> getTrackEntries() {
            return List.of(new SfxTrack(0, 1, 0, 0));
        }
    }

    private record SfxTrack(
            int channelMask, int pointer, int transpose, int volume)
            implements SmpsSfxData.SmpsSfxTrack {
    }
}
