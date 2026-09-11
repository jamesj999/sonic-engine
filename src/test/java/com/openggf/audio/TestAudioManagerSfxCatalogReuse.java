package com.openggf.audio;

import com.openggf.audio.presentation.PresentationMode;
import com.openggf.audio.presentation.SmpsAssetKey;
import com.openggf.audio.rewind.AudioCommand;
import com.openggf.audio.smps.AbstractSmpsData;
import com.openggf.audio.smps.DacData;
import com.openggf.audio.smps.SmpsLoader;
import com.openggf.audio.smps.SmpsSequencerConfig;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.data.Rom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for lookup-first admission at public SFX entry points. */
class TestAudioManagerSfxCatalogReuse {
    private static final int BASE_ID = 0xA0;
    private static final int NAMED_ID = 0xA1;
    private static final int DONOR_ID = 0xE0;
    private static final String REQUESTED_NAME = "requested-name";
    private static final DacData EMPTY_DAC = new DacData(
            Collections.emptyMap(), Collections.emptyMap(), 288);

    private final AudioManager audio = AudioManager.getInstance();

    @BeforeEach
    void setUp() {
        audio.resetState();
        audio.setBackend(new NullAudioBackend());
    }

    @AfterEach
    void tearDown() {
        audio.resetState();
        audio.setBackend(new NullAudioBackend());
    }

    @Test
    void repeatedBaseIdLoadsAndMaterializesOncePerKeyAndGeneration() {
        AtomicInteger materializations = new AtomicInteger();
        CountingLoader loader = new CountingLoader(EMPTY_DAC);
        loader.idResults.put(BASE_ID,
                () -> new CountingSfxData(
                        BASE_ID, (byte) 0x11, materializations));
        installBase(loader);

        assertTrue(audio.playSfx(BASE_ID));
        audio.presentFrame(PresentationMode.SILENT);
        var first = currentSfx();
        assertTrue(audio.playSfx(BASE_ID));
        audio.presentFrame(PresentationMode.SILENT);
        var repeated = currentSfx();

        assertEquals(1, loader.idLoads.get());
        assertEquals(1, materializations.get());
        assertSame(first.source(), repeated.source());
        assertSame(first.smpsData(), repeated.smpsData());
        assertSame(first.dacData(), repeated.dacData());
        assertSame(first.config(), repeated.config());
        assertNotSame(first.snapshot(), repeated.snapshot());

        audio.setRom(new Rom());
        assertTrue(audio.playSfx(BASE_ID));
        audio.presentFrame(PresentationMode.SILENT);
        var nextGeneration = currentSfx();

        assertEquals(2, loader.idLoads.get());
        assertEquals(2, materializations.get());
        assertNotEquals(first.source().dependencyGeneration(),
                nextGeneration.source().dependencyGeneration());
        assertNotSame(first.smpsData(), nextGeneration.smpsData());
    }

    @Test
    void nullBaseIdReturnsFalseAndPublishesNoCommand() {
        CountingLoader loader = new CountingLoader(EMPTY_DAC);
        installBase(loader);

        assertFalse(audio.playSfx(BASE_ID));

        assertEquals(1, loader.idLoads.get());
        assertEquals(0, audio.commandTimeline().entryCount());
    }

    @Test
    void repeatedBaseNameUsesRequestedKeyAndResolvedProgramId() {
        AtomicInteger materializations = new AtomicInteger();
        CountingLoader loader = new CountingLoader(EMPTY_DAC);
        loader.nameResults.put(REQUESTED_NAME,
                () -> new CountingSfxData(
                        NAMED_ID, (byte) 0x21, materializations));
        installBase(loader);

        audio.playSfx(REQUESTED_NAME, 0.75f);
        audio.presentFrame(PresentationMode.SILENT);
        audio.playSfx(REQUESTED_NAME, 1.25f);
        audio.presentFrame(PresentationMode.SILENT);

        assertEquals(1, loader.nameLoads.get());
        assertEquals(1, materializations.get());
        AudioCommand.PlaySfx command = lastCommand();
        assertEquals(-1, command.sfxId());
        assertEquals(REQUESTED_NAME, command.sfxName());
        assertEquals(AudioCommand.SfxRoute.BASE_SMPS_NAME, command.route());
        assertEquals(REQUESTED_NAME,
                currentSfx().source().name());
        assertEquals(NAMED_ID, currentSfx().source().id(),
                "named registration keeps the requested key but resolves "
                        + "policy identity from the first program");
    }

    @Test
    void nullBaseNameRetainsFallbackWithoutConsultingTheLoader() {
        CountingLoader loader = new CountingLoader(EMPTY_DAC);
        installBase(loader);

        audio.playSfx((String) null, 0.5f);

        assertEquals(0, loader.nameLoads.get());
        AudioCommand.PlaySfx command = lastCommand();
        assertEquals(AudioCommand.SfxRoute.FALLBACK_NAME, command.route());
        assertEquals(null, command.sfxName());
    }

    @Test
    void repeatedDirectDonorLoadsOnceAndNullDonorIsANoOp() {
        AtomicInteger materializations = new AtomicInteger();
        CountingLoader loader = new CountingLoader(EMPTY_DAC);
        loader.idResults.put(DONOR_ID,
                () -> new CountingSfxData(
                        DONOR_ID, (byte) 0x31, materializations));
        registerDonor("s2", loader);

        audio.playDonorSfx("s2", DONOR_ID);
        audio.presentFrame(PresentationMode.SILENT);
        audio.playDonorSfx("s2", DONOR_ID);
        audio.presentFrame(PresentationMode.SILENT);

        assertEquals(1, loader.idLoads.get());
        assertEquals(1, materializations.get());
        assertEquals(AudioCommand.SfxRoute.DONOR_SMPS,
                lastCommand().route());

        int before = audio.commandTimeline().entryCount();
        audio.playDonorSfx("s2", DONOR_ID + 1);
        assertEquals(2, loader.idLoads.get(),
                "null misses need not be negatively cached");
        assertEquals(before, audio.commandTimeline().entryCount());
    }

    @Test
    void configlessDirectDonorCapturesBaseOwnerAndReplacesByGeneration() {
        CountingLoader baseLoader = new CountingLoader(EMPTY_DAC);
        CountingLoader firstLoader = new CountingLoader(dac(311));
        firstLoader.idResults.put(DONOR_ID,
                () -> new CountingSfxData(
                        DONOR_ID, (byte) 0x32, new AtomicInteger()));
        audio.registerDonorLoader("s2", firstLoader, firstLoader.dac);
        installBase(baseLoader);

        audio.playDonorSfx("s2", DONOR_ID);
        audio.presentFrame(PresentationMode.SILENT);
        var first = currentSfx();
        audio.playDonorSfx("s2", DONOR_ID);
        audio.presentFrame(PresentationMode.SILENT);

        assertEquals(1, firstLoader.idLoads.get());
        assertEquals(AudioCommand.SfxRoute.DONOR_SMPS,
                lastCommand().route());
        assertSame(first.dacData(), currentSfx().dacData());
        assertSame(first.config(), currentSfx().config());
        assertEquals(TestProfile.CONFIG.getTempoMode(),
                first.config().getTempoMode());

        CountingLoader replacementLoader = new CountingLoader(dac(312));
        replacementLoader.idResults.put(DONOR_ID,
                () -> new CountingSfxData(
                        DONOR_ID, (byte) 0x33, new AtomicInteger()));
        audio.registerDonorLoader(
                "s2", replacementLoader, replacementLoader.dac);
        audio.playDonorSfx("s2", DONOR_ID);
        audio.presentFrame(PresentationMode.SILENT);
        var replacement = currentSfx();

        assertEquals(1, replacementLoader.idLoads.get());
        assertNotEquals(first.source().dependencyGeneration(),
                replacement.source().dependencyGeneration());
        assertSame(replacementLoader.dac, replacement.dacData());

        int commands = audio.commandTimeline().entryCount();
        audio.playDonorSfx("s2", DONOR_ID + 1);
        assertEquals(2, replacementLoader.idLoads.get());
        assertEquals(commands, audio.commandTimeline().entryCount());
    }

    @Test
    void configlessDonorMusicDefersUntilBaseOwnerWithoutChangingGeneration() {
        int musicId = 0x21;
        CountingLoader donor = new CountingLoader(dac(314));
        donor.musicResults.put(musicId,
                () -> new CountingSfxData(
                        musicId, (byte) 0x34, new AtomicInteger()));
        audio.registerDonorLoader("s3k", donor, donor.dac);
        int beforeOwner = audio.commandTimeline().entryCount();

        assertDoesNotThrow(() -> audio.playDonorMusic("s3k", musicId));

        assertEquals(0, donor.musicLoads.get(),
                "a config-less donor must remain deferred without an owner");
        assertEquals(beforeOwner, audio.commandTimeline().entryCount());

        installBase(new CountingLoader(EMPTY_DAC));
        audio.playDonorMusic("s3k", musicId);
        audio.presentFrame(PresentationMode.SILENT);
        var first = audio.shadowSmpsDriverSnapshotForTesting()
                .sequencers().getFirst();
        audio.playDonorMusic("s3k", musicId);
        audio.presentFrame(PresentationMode.SILENT);

        assertEquals(1, donor.musicLoads.get());
        assertEquals(1, first.source().dependencyGeneration(),
                "deferred completion must retain the registration generation");
        assertSame(donor.dac, first.dacData());
        assertEquals(TestProfile.CONFIG.getTempoMode(),
                first.config().getTempoMode());

        int beforeNullMiss = audio.commandTimeline().entryCount();
        audio.playDonorMusic("s3k", musicId + 1);
        assertEquals(2, donor.musicLoads.get(),
                "null misses need not be negatively cached");
        assertEquals(beforeNullMiss, audio.commandTimeline().entryCount(),
                "a donor null result remains a no-op");
    }

    @Test
    void repeatedGameSoundDonorLoadsOnceAndNullDonorFallsBackByName() {
        AtomicInteger materializations = new AtomicInteger();
        CountingLoader loader = new CountingLoader(EMPTY_DAC);
        loader.idResults.put(DONOR_ID,
                () -> new CountingSfxData(
                        DONOR_ID, (byte) 0x41, materializations));
        registerDonor("s2", loader);
        audio.setSoundMap(Map.of());
        audio.registerDonorSound(
                GameSound.SPINDASH_CHARGE, "s2", DONOR_ID);

        audio.playSfx(GameSound.SPINDASH_CHARGE);
        audio.presentFrame(PresentationMode.SILENT);
        audio.playSfx(GameSound.SPINDASH_CHARGE);
        audio.presentFrame(PresentationMode.SILENT);

        assertEquals(1, loader.idLoads.get());
        assertEquals(1, materializations.get());
        assertEquals(GameSound.SPINDASH_CHARGE.name(),
                lastCommand().sfxName());
        assertEquals(AudioCommand.SfxRoute.DONOR_SMPS,
                lastCommand().route());

        audio.registerDonorSound(
                GameSound.FIRE_SHIELD, "s2", DONOR_ID + 1);
        audio.playSfx(GameSound.FIRE_SHIELD);

        assertEquals(AudioCommand.SfxRoute.FALLBACK_NAME,
                lastCommand().route());
        assertEquals(GameSound.FIRE_SHIELD.name(),
                lastCommand().sfxName());
    }

    @Test
    void configlessGameSoundDonorPresentsAndNullMissFallsBackByName() {
        CountingLoader baseLoader = new CountingLoader(EMPTY_DAC);
        installBase(baseLoader);
        CountingLoader donor = new CountingLoader(dac(313));
        donor.idResults.put(DONOR_ID,
                () -> new CountingSfxData(
                        DONOR_ID, (byte) 0x42, new AtomicInteger()));
        audio.registerDonorLoader("s2", donor, donor.dac);
        audio.setSoundMap(Map.of());
        audio.registerDonorSound(
                GameSound.SPINDASH_CHARGE, "s2", DONOR_ID);

        audio.playSfx(GameSound.SPINDASH_CHARGE);
        audio.presentFrame(PresentationMode.SILENT);
        audio.playSfx(GameSound.SPINDASH_CHARGE);
        audio.presentFrame(PresentationMode.SILENT);

        assertEquals(1, donor.idLoads.get());
        assertEquals(AudioCommand.SfxRoute.DONOR_SMPS,
                lastCommand().route());
        assertSame(donor.dac, currentSfx().dacData());
        assertEquals(TestProfile.CONFIG.getTempoMode(),
                currentSfx().config().getTempoMode());

        audio.registerDonorSound(
                GameSound.FIRE_SHIELD, "s2", DONOR_ID + 1);
        audio.playSfx(GameSound.FIRE_SHIELD);

        assertEquals(2, donor.idLoads.get());
        assertEquals(AudioCommand.SfxRoute.FALLBACK_NAME,
                lastCommand().route());
        assertEquals(GameSound.FIRE_SHIELD.name(),
                lastCommand().sfxName());
    }

    @Test
    void invalidRequestsProbeLoaderBeforeCatalogKeyValidation() {
        CountingLoader baseLoader = new CountingLoader(EMPTY_DAC);
        installBase(baseLoader);

        audio.playSfx("", 0.75f);

        assertEquals(1, baseLoader.nameLoads.get());
        assertEquals(AudioCommand.SfxRoute.FALLBACK_NAME,
                lastCommand().route());
        assertEquals("", lastCommand().sfxName());

        int commands = audio.commandTimeline().entryCount();
        assertFalse(audio.playSfx(-7));
        assertEquals(1, baseLoader.idLoads.get());
        assertEquals(commands, audio.commandTimeline().entryCount());

        CountingLoader donor = new CountingLoader(EMPTY_DAC);
        registerDonor("s2", donor);
        audio.playDonorSfx("s2", -8);
        assertEquals(1, donor.idLoads.get());
        assertEquals(commands, audio.commandTimeline().entryCount());

        audio.setSoundMap(Map.of());
        audio.registerDonorSound(GameSound.FIRE_SHIELD, "s2", -9);
        audio.playSfx(GameSound.FIRE_SHIELD);
        assertEquals(2, donor.idLoads.get());
        assertEquals(AudioCommand.SfxRoute.FALLBACK_NAME,
                lastCommand().route());
        assertEquals(GameSound.FIRE_SHIELD.name(),
                lastCommand().sfxName());
    }

    @Test
    void reentrantBaseReplacementCannotMixTheCapturedSourceTuple() {
        AtomicInteger firstMaterializations = new AtomicInteger();
        AtomicInteger secondMaterializations = new AtomicInteger();
        Rom firstRom = new Rom();
        Rom secondRom = new Rom();
        DacData firstDac = dac(301);
        DacData secondDac = dac(302);
        CountingLoader firstLoader = new CountingLoader(firstDac);
        CountingLoader secondLoader = new CountingLoader(secondDac);
        firstLoader.idResults.put(BASE_ID,
                () -> new CountingSfxData(
                        BASE_ID, (byte) 0x51, firstMaterializations));
        secondLoader.idResults.put(BASE_ID,
                () -> new CountingSfxData(
                        BASE_ID, (byte) 0x52, secondMaterializations));
        SwitchingProfile profile = new SwitchingProfile();
        profile.loaders.put(firstRom, firstLoader);
        profile.loaders.put(secondRom, secondLoader);
        audio.setAudioProfile(profile);
        audio.setRom(firstRom);
        firstLoader.afterIdLoad = () -> audio.setRom(secondRom);

        assertTrue(audio.playSfx(BASE_ID));
        audio.presentFrame(PresentationMode.SILENT);
        var captured = currentSfx();

        assertSame(firstDac, captured.dacData());
        assertEquals(1, firstLoader.idLoads.get());
        assertEquals(0, secondLoader.idLoads.get());
        assertEquals(1, firstMaterializations.get());
        assertEquals(0, secondMaterializations.get());

        assertTrue(audio.playSfx(BASE_ID));
        audio.presentFrame(PresentationMode.SILENT);
        var replacement = currentSfx();
        assertSame(secondDac, replacement.dacData());
        assertNotEquals(captured.source().dependencyGeneration(),
                replacement.source().dependencyGeneration());
    }

    @Test
    void registrationConflictOccursBeforeTimelinePublication() {
        AtomicInteger materializations = new AtomicInteger();
        CountingLoader loader = new CountingLoader(EMPTY_DAC);
        loader.idResults.put(BASE_ID,
                () -> new CountingSfxData(
                        BASE_ID, (byte) 0x61, materializations));
        installBase(loader);
        loader.afterIdLoad = () -> audio.shadowFactoryForTesting()
                .registerSmpsSfxAsset(
                        new SmpsAssetKey(
                                "base", SmpsAssetKey.Route.BASE_ID,
                                BASE_ID, null),
                        2,
                        new CountingSfxData(
                                BASE_ID, (byte) 0x62, materializations),
                        EMPTY_DAC,
                        TestProfile.CONFIG,
                        false);

        assertThrows(IllegalStateException.class,
                () -> audio.playSfx(BASE_ID));

        assertEquals(0, audio.commandTimeline().entryCount(),
                "a rejected registration must publish no logical command");
    }

    @Test
    void queuedAdmissionRetainsPolicyFrozenByManagerRegistration() {
        CountingLoader loader = new CountingLoader(EMPTY_DAC);
        loader.idResults.put(BASE_ID,
                () -> new CountingSfxData(
                        BASE_ID, (byte) 0x70, new AtomicInteger()));
        MutatingPolicyProfile profile = new MutatingPolicyProfile(loader);
        audio.setAudioProfile(profile);
        audio.setRom(new Rom());

        assertTrue(audio.playSfx(BASE_ID));
        audio.presentFrame(PresentationMode.SILENT);
        assertTrue(audio.playSfx(BASE_ID));
        audio.presentFrame(PresentationMode.SILENT);

        var state = audio.shadowSmpsDriverSnapshotForTesting();
        assertEquals(1, state.sequencers().size(),
                "the retained continuous policy must extend the first voice");
        var voice = state.sequencers().getFirst();
        assertEquals(0x23, voice.snapshot().sfxPriority());
        assertTrue(voice.snapshot().specialSfx());
        assertEquals(BASE_ID, state.continuousSfxId());
        assertTrue(state.continuousSfxFlag());
        assertEquals(1, loader.idLoads.get(),
                "the second admission must remain a catalog hit");
    }

    @Test
    void legacyCachedHitDispatchesTheRetainedProgramDacConfigAndPolicy() {
        RecordingBackend backend = new RecordingBackend();
        audio.setBackend(backend);
        DacData firstDac = dac(321);
        SmpsSequencerConfig firstConfig =
                new SmpsSequencerConfig.Builder()
                        .tempoMode(SmpsSequencerConfig.TempoMode.OVERFLOW)
                        .build();
        CountingLoader firstLoader = new CountingLoader(firstDac);
        firstLoader.idResults.put(BASE_ID,
                () -> new CountingSfxData(
                        BASE_ID, (byte) 0x71, new AtomicInteger()));
        audio.setAudioProfile(new PolicyProfile(
                firstLoader, firstConfig, 0x23));
        audio.setRom(new Rom());

        assertTrue(audio.playSfx(BASE_ID));
        audio.presentFrame(PresentationMode.SILENT);
        var retainedVoice = currentSfx();
        assertEquals(0x23, retainedVoice.snapshot().sfxPriority());
        var retainedPlayback = audio.shadowFactoryForTesting()
                .requireRegisteredSmpsSfxPlayback(
                        new SmpsAssetKey(
                                "base", SmpsAssetKey.Route.BASE_ID,
                                BASE_ID, null),
                        retainedVoice.source().dependencyGeneration());

        DacData replacementDac = dac(322);
        SmpsSequencerConfig replacementConfig =
                new SmpsSequencerConfig.Builder()
                        .tempoMode(SmpsSequencerConfig.TempoMode.TIMEOUT)
                        .build();
        CountingLoader replacementLoader =
                new CountingLoader(replacementDac);
        audio.setAudioProfile(new PolicyProfile(
                replacementLoader, replacementConfig, 0x66));
        audio.setRom(new Rom());

        audio.dispatchLiveRegisteredSfx(retainedPlayback, 0.8f);

        assertEquals(0, backend.currentConfigCalls,
                "a cached hit must not select the backend's current tuple");
        assertEquals(1, backend.explicitConfigCalls);
        assertSame(retainedPlayback.program(), backend.program);
        assertSame(retainedPlayback.dac(), backend.dac);
        assertSame(retainedPlayback.config(), backend.config);
        assertNotSame(replacementDac, backend.dac);
        assertNotSame(replacementConfig, backend.config);
    }

    @Test
    void legacyCachedHitMakesRealBackendUseRetainedCompletePolicy() {
        NoDeviceBackend backend = new NoDeviceBackend(
                SonicConfigurationService.createStandalone());
        backend.init();
        audio.setBackend(backend);
        CountingLoader firstLoader = new CountingLoader(EMPTY_DAC);
        firstLoader.idResults.put(BASE_ID,
                () -> new CountingSfxData(
                        BASE_ID, (byte) 0x73, new AtomicInteger()));
        audio.setAudioProfile(new PolicyProfile(
                firstLoader, TestProfile.CONFIG, 0x23, true, true));
        audio.setRom(new Rom());

        assertTrue(audio.playSfx(BASE_ID));
        audio.presentFrame(PresentationMode.SILENT);
        var retainedVoice = currentSfx();
        var retainedPlayback = audio.shadowFactoryForTesting()
                .requireRegisteredSmpsSfxPlayback(
                        new SmpsAssetKey(
                                "base", SmpsAssetKey.Route.BASE_ID,
                                BASE_ID, null),
                        retainedVoice.source().dependencyGeneration());
        assertEquals(new SmpsSfxPlaybackPolicy(
                0x23, true, true), retainedPlayback.policy());
        backend.stopAllSfx();

        CountingLoader replacementLoader = new CountingLoader(EMPTY_DAC);
        audio.setAudioProfile(new PolicyProfile(
                replacementLoader, TestProfile.CONFIG,
                0x66, false, false));
        audio.setRom(new Rom());
        audio.dispatchLiveRegisteredSfx(retainedPlayback, 1.0f);
        audio.dispatchLiveRegisteredSfx(retainedPlayback, 1.0f);

        var state = backend.stateForTesting();
        assertEquals(1, state.standaloneSfxSequencers().size(),
                "retained continuous policy must extend the existing SFX");
        assertEquals(0x23, state.standaloneSfxSequencers()
                .getFirst().getSfxPriority());
        assertTrue(state.standaloneSfxSequencers()
                .getFirst().isSpecialSfx());
        assertEquals(BASE_ID,
                state.standaloneSfxDriverSnapshot().continuousSfxId());
        assertTrue(state.standaloneSfxDriverSnapshot().continuousSfxFlag());
    }

    private void installBase(CountingLoader loader) {
        audio.setAudioProfile(new TestProfile(loader));
        audio.setRom(new Rom());
    }

    private void registerDonor(String gameId, CountingLoader loader) {
        audio.registerDonorLoader(
                gameId, loader, loader.dac, TestProfile.CONFIG);
    }

    private com.openggf.audio.rewind.SmpsDriverSnapshot.SequencerEntry
            currentSfx() {
        var sequencers = audio.shadowSmpsDriverSnapshotForTesting()
                .sequencers();
        return sequencers.get(sequencers.size() - 1);
    }

    private AudioCommand.PlaySfx lastCommand() {
        int index = audio.commandTimeline().entryCount() - 1;
        return (AudioCommand.PlaySfx)
                audio.commandTimeline().entryAt(index).command();
    }

    private static DacData dac(int rate) {
        return new DacData(
                Collections.emptyMap(), Collections.emptyMap(), rate);
    }

    private static final class CountingLoader implements SmpsLoader {
        private final DacData dac;
        private final Map<Integer, Supplier<AbstractSmpsData>> idResults =
                new java.util.HashMap<>();
        private final Map<Integer, Supplier<AbstractSmpsData>> musicResults =
                new java.util.HashMap<>();
        private final Map<String, Supplier<AbstractSmpsData>> nameResults =
                new java.util.HashMap<>();
        private final AtomicInteger idLoads = new AtomicInteger();
        private final AtomicInteger musicLoads = new AtomicInteger();
        private final AtomicInteger nameLoads = new AtomicInteger();
        private Runnable afterIdLoad;

        private CountingLoader(DacData dac) {
            this.dac = dac;
        }

        @Override
        public AbstractSmpsData loadMusic(int musicId) {
            musicLoads.incrementAndGet();
            Supplier<AbstractSmpsData> result = musicResults.get(musicId);
            return result != null ? result.get() : null;
        }

        @Override
        public AbstractSmpsData loadSfx(int sfxId) {
            idLoads.incrementAndGet();
            Supplier<AbstractSmpsData> result = idResults.get(sfxId);
            AbstractSmpsData data = result != null ? result.get() : null;
            Runnable callback = afterIdLoad;
            afterIdLoad = null;
            if (callback != null) {
                callback.run();
            }
            return data;
        }

        @Override
        public AbstractSmpsData loadSfx(String sfxName) {
            nameLoads.incrementAndGet();
            Supplier<AbstractSmpsData> result = nameResults.get(sfxName);
            return result != null ? result.get() : null;
        }

        @Override
        public DacData loadDacData() {
            return dac;
        }
    }

    private static final class CountingSfxData extends AbstractSmpsData {
        private final AtomicInteger materializations;

        private CountingSfxData(
                int id, byte marker, AtomicInteger materializations) {
            super(sfxBytes(marker), 0);
            this.materializations = materializations;
            setId(id);
        }

        private static byte[] sfxBytes(byte marker) {
            byte[] bytes = new byte[0x80];
            bytes[2] = 1;
            bytes[4] = 1;
            bytes[5] = (byte) 0x80;
            bytes[6] = 0x40;
            bytes[0x40] = marker;
            bytes[0x41] = (byte) 0xF2;
            return bytes;
        }

        @Override
        protected void parseHeader() {
            channels = data[2] & 0xFF;
            psgChannels = data[3] & 0xFF;
            dividingTiming = data[4] & 0xFF;
            tempo = data[5] & 0xFF;
            fmPointers = new int[] {read16(6)};
        }

        @Override
        public byte[] getData() {
            materializations.incrementAndGet();
            return super.getData();
        }

        @Override public byte[] getVoice(int voiceId) { return new byte[25]; }
        @Override public byte[] getPsgEnvelope(int id) {
            return new byte[] {(byte) 0x81};
        }
        @Override public int read16(int offset) {
            return (data[offset] & 0xFF)
                    | ((data[offset + 1] & 0xFF) << 8);
        }
        @Override public int getBaseNoteOffset() { return 0; }
    }

    private static class TestProfile implements GameAudioProfile {
        private static final SmpsSequencerConfig CONFIG =
                new SmpsSequencerConfig.Builder().build();
        private final SmpsLoader loader;

        private TestProfile(SmpsLoader loader) {
            this.loader = loader;
        }

        @Override public String presentationGameId() { return "base"; }
        @Override public SmpsLoader createSmpsLoader(Rom rom) { return loader; }
        @Override public SmpsSequencerConfig getSequencerConfig() {
            return CONFIG;
        }
        @Override public int getSpeedShoesOnCommandId() { return -1; }
        @Override public int getSpeedShoesOffCommandId() { return -1; }
        @Override public int getInvincibilityMusicId() { return -1; }
        @Override public int getExtraLifeMusicId() { return -1; }
        @Override public int getDrowningMusicId() { return -1; }
        @Override public Map<GameSound, Integer> getSoundMap() {
            return Map.of();
        }
    }

    private static final class SwitchingProfile extends TestProfile {
        private final Map<Rom, SmpsLoader> loaders = new IdentityHashMap<>();

        private SwitchingProfile() {
            super(null);
        }

        @Override
        public SmpsLoader createSmpsLoader(Rom rom) {
            return loaders.get(rom);
        }
    }

    private static final class PolicyProfile extends TestProfile {
        private final SmpsSequencerConfig config;
        private final int priority;
        private final boolean special;
        private final boolean continuous;

        private PolicyProfile(
                SmpsLoader loader,
                SmpsSequencerConfig config,
                int priority) {
            this(loader, config, priority, false, false);
        }

        private PolicyProfile(
                SmpsLoader loader,
                SmpsSequencerConfig config,
                int priority,
                boolean special,
                boolean continuous) {
            super(loader);
            this.config = config;
            this.priority = priority;
            this.special = special;
            this.continuous = continuous;
        }

        @Override public SmpsSequencerConfig getSequencerConfig() {
            return config;
        }

        @Override public int getSfxPriority(int sfxId) {
            return priority;
        }

        @Override public boolean isSpecialSfx(int sfxId) {
            return special;
        }

        @Override public boolean isContinuousSfx(int sfxId) {
            return continuous;
        }
    }

    private static final class MutatingPolicyProfile extends TestProfile {
        private int priority = 0x23;
        private boolean special = true;
        private boolean continuous = true;
        private boolean firstRegistrationPolicy = true;

        private MutatingPolicyProfile(SmpsLoader loader) {
            super(loader);
        }

        @Override public int getSfxPriority(int sfxId) {
            return priority;
        }

        @Override public boolean isSpecialSfx(int sfxId) {
            return special;
        }

        @Override public boolean isContinuousSfx(int sfxId) {
            boolean result = continuous;
            if (firstRegistrationPolicy) {
                firstRegistrationPolicy = false;
                priority = 0x66;
                special = false;
                continuous = false;
            }
            return result;
        }
    }

    private static final class NoDeviceBackend
            extends AbstractSmpsAudioBackend {
        private NoDeviceBackend(SonicConfigurationService configuration) {
            super(configuration, null);
        }

        @Override protected int getDeviceSampleRate() { return 48_000; }
        @Override protected void hookInitDevice() { }
        @Override protected void hookDestroyDevice() { }
        @Override protected void hookStartStream() { }
        @Override protected void hookStopStreamSource() { }
        @Override protected void hookUpdateStream() { }
        @Override protected void hookStopAndClearMusicSource() { }
        @Override protected void hookStopAndUnqueueAllMusicBuffers() { }
        @Override protected void hookStopAndClearAllMusicBuffers() { }
        @Override protected void hookRestartStreamIfDry() { }
        @Override protected void hookStopAndDeleteWavSfxSources() { }
        @Override protected void hookPlayWavSfx(
                String sfxName, float pitch) { }
        @Override protected void hookCleanupStoppedWavSfx() { }
        @Override protected void hookPause() { }
        @Override protected void hookResume() { }
    }

    private static final class RecordingBackend extends NullAudioBackend {
        private int currentConfigCalls;
        private int explicitConfigCalls;
        private AbstractSmpsData program;
        private DacData dac;
        private SmpsSequencerConfig config;

        @Override
        public void playSfxSmps(
                AbstractSmpsData data, DacData dacData, float pitch) {
            currentConfigCalls++;
            program = data;
            dac = dacData;
            config = null;
        }

        @Override
        public void playSfxSmps(
                AbstractSmpsData data,
                DacData dacData,
                float pitch,
                SmpsSequencerConfig sequencerConfig) {
            explicitConfigCalls++;
            program = data;
            dac = dacData;
            config = sequencerConfig;
        }
    }
}
