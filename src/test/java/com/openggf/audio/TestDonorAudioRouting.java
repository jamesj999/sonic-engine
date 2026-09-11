package com.openggf.audio;

import com.openggf.audio.smps.AbstractSmpsData;
import com.openggf.audio.smps.CoordFlagContext;
import com.openggf.audio.smps.CoordFlagHandler;
import com.openggf.audio.smps.DacData;
import com.openggf.audio.smps.SmpsCoordFlagHandlerOwner;
import com.openggf.audio.smps.SmpsCoordFlagRuntimeState;
import com.openggf.audio.smps.SmpsLoader;
import com.openggf.audio.smps.SmpsSequencer;
import com.openggf.audio.smps.SmpsSequencerConfig;
import com.openggf.audio.presentation.AudioPresentationCommand;
import com.openggf.audio.presentation.AudioPresentationSourceFactory;
import com.openggf.audio.presentation.AudioPresentationSessionCommandApplier;
import com.openggf.audio.presentation.AudioVoiceRegistry;
import com.openggf.audio.presentation.ResolvedSmpsSfxSource;
import com.openggf.audio.presentation.SmpsAssetKey;
import com.openggf.audio.presentation.PresentationMode;
import com.openggf.audio.rewind.AudioCommand;
import com.openggf.audio.rewind.AudioLogicalSnapshot;
import com.openggf.audio.rewind.SmpsSourceDescriptor;
import com.openggf.audio.session.SmpsDriverSession;
import com.openggf.audio.session.SmpsSessionTestSupport;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.sonic3k.audio.Sonic3kAudioProfile;
import com.openggf.data.Rom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests donor audio routing in AudioManager.
 * Verifies that base game sounds take priority, donor sounds fill gaps,
 * and cleanup works correctly. No ROM or OpenGL required.
 */
public class TestDonorAudioRouting {

    private AudioManager audioManager;
    private RecordingBackend backend;

    private static final DacData EMPTY_DAC = new DacData(
            Collections.emptyMap(), Collections.emptyMap(), 288);

    @BeforeEach
    public void setUp() {
        audioManager = AudioManager.getInstance();
        audioManager.resetState();
        backend = new RecordingBackend();
        audioManager.setBackend(backend);
    }

    @AfterEach
    public void tearDown() {
        audioManager.resetState();
    }

    @Test
    public void testBaseMiss_DonorBindingExists_PlaysDonor() {
        // Base sound map does NOT contain SPINDASH_CHARGE (simulates S1)
        Map<GameSound, Integer> baseMap = new EnumMap<>(GameSound.class);
        baseMap.put(GameSound.JUMP, 0x90);
        audioManager.setSoundMap(baseMap);

        // Register donor loader with spindash
        StubSmpsLoader donorLoader = new StubSmpsLoader();
        donorLoader.sfxResults.put(0xE0, new StubSmpsData("donor-spindash"));
        audioManager.registerDonorLoader("s2", donorLoader, EMPTY_DAC);
        audioManager.registerDonorSound(GameSound.SPINDASH_CHARGE, "s2", 0xE0);

        audioManager.playSfx(GameSound.SPINDASH_CHARGE, 1.0f);

        assertEquals(AudioCommand.SfxRoute.DONOR_SMPS, lastSfx().route());
        assertEquals("s2", lastSfx().donorGameId());
    }

    @Test
    public void testBaseMiss_NoDonorBinding_FallsThrough() {
        // Base sound map does NOT contain SPINDASH_CHARGE, no donor registered
        Map<GameSound, Integer> baseMap = new EnumMap<>(GameSound.class);
        audioManager.setSoundMap(baseMap);

        audioManager.playSfx(GameSound.SPINDASH_CHARGE, 1.0f);

        assertEquals(AudioCommand.SfxRoute.FALLBACK_NAME, lastSfx().route());
        assertEquals("SPINDASH_CHARGE", lastSfx().sfxName());
    }

    @Test
    public void testBaseHit_DonorNotConsulted() {
        // Base sound map has SPINDASH_CHARGE mapped to a base SFX ID
        Map<GameSound, Integer> baseMap = new EnumMap<>(GameSound.class);
        baseMap.put(GameSound.SPINDASH_CHARGE, 0xA5);
        audioManager.setSoundMap(baseMap);

        // Set up a base SMPS loader that handles 0xA5
        StubSmpsLoader baseLoader = new StubSmpsLoader();
        baseLoader.sfxResults.put(0xA5, new StubSmpsData("base-roll"));
        audioManager.setAudioProfile(new StubAudioProfile(baseLoader));
        audioManager.setRom(new Rom());

        // Also register donor with the same sound
        StubSmpsLoader donorLoader = new StubSmpsLoader();
        donorLoader.sfxResults.put(0xE0, new StubSmpsData("donor-spindash"));
        audioManager.registerDonorLoader("s2", donorLoader, EMPTY_DAC);
        audioManager.registerDonorSound(GameSound.SPINDASH_CHARGE, "s2", 0xE0);

        audioManager.playSfx(GameSound.SPINDASH_CHARGE, 1.0f);

        assertEquals(AudioCommand.SfxRoute.BASE_SMPS_ID, lastSfx().route());
        assertEquals(0xA5, lastSfx().sfxId());
    }

    @Test
    public void testClearDonorAudio_RemovesAllState() {
        // Register donor
        StubSmpsLoader donorLoader = new StubSmpsLoader();
        donorLoader.sfxResults.put(0xE0, new StubSmpsData("donor-spindash"));
        audioManager.registerDonorLoader("s2", donorLoader, EMPTY_DAC);
        audioManager.registerDonorSound(GameSound.SPINDASH_CHARGE, "s2", 0xE0);

        // Clear
        audioManager.clearDonorAudio();

        // Now play â€” should fall through to backend
        Map<GameSound, Integer> baseMap = new EnumMap<>(GameSound.class);
        audioManager.setSoundMap(baseMap);
        audioManager.playSfx(GameSound.SPINDASH_CHARGE, 1.0f);
        assertEquals(AudioCommand.SfxRoute.FALLBACK_NAME, lastSfx().route());
    }

    @Test
    public void testSetRom_DoesNotWipeDonorBindings() {
        // Simulates real init: donor registered, THEN setRom called during level load.
        // setRom must NOT clear donor state.
        StubSmpsLoader donorLoader = new StubSmpsLoader();
        donorLoader.sfxResults.put(0xE0, new StubSmpsData("donor-spindash"));
        audioManager.registerDonorLoader("s2", donorLoader, EMPTY_DAC);
        audioManager.registerDonorSound(GameSound.SPINDASH_CHARGE, "s2", 0xE0);

        // Level load calls setAudioProfile + setRom + setSoundMap
        StubSmpsLoader baseLoader = new StubSmpsLoader();
        audioManager.setAudioProfile(new StubAudioProfile(baseLoader));
        audioManager.setRom(new Rom());  // This MUST NOT clear donor bindings

        Map<GameSound, Integer> baseMap = new EnumMap<>(GameSound.class);
        baseMap.put(GameSound.JUMP, 0x90);
        audioManager.setSoundMap(baseMap);

        // Donor spindash should still work
        audioManager.playSfx(GameSound.SPINDASH_CHARGE, 1.0f);
        assertEquals("s2", lastSfx().donorGameId(),
                "Donor spindash must survive setRom()");
    }

    @Test
    public void testMultipleDonorGames_CorrectLoaderUsed() {
        Map<GameSound, Integer> baseMap = new EnumMap<>(GameSound.class);
        audioManager.setSoundMap(baseMap);

        // Register S2 donor
        StubSmpsLoader s2Loader = new StubSmpsLoader();
        s2Loader.sfxResults.put(0xE0, new StubSmpsData("s2-spindash"));
        audioManager.registerDonorLoader("s2", s2Loader, EMPTY_DAC);
        audioManager.registerDonorSound(GameSound.SPINDASH_CHARGE, "s2", 0xE0);

        // Register S3K donor
        DacData s3kDac = new DacData(Collections.emptyMap(), Collections.emptyMap(), 297);
        StubSmpsLoader s3kLoader = new StubSmpsLoader();
        s3kLoader.sfxResults.put(0x54, new StubSmpsData("s3k-fire-shield"));
        audioManager.registerDonorLoader("s3k", s3kLoader, s3kDac);
        audioManager.registerDonorSound(GameSound.FIRE_SHIELD, "s3k", 0x54);

        // Play spindash â€” should route to S2
        audioManager.playSfx(GameSound.SPINDASH_CHARGE, 1.0f);
        assertEquals("s2", lastSfx().donorGameId());

        // Play fire shield â€” should route to S3K
        audioManager.playSfx(GameSound.FIRE_SHIELD, 1.0f);
        assertEquals("s3k", lastSfx().donorGameId());
    }

    @Test
    public void testDonorSfx_UsesProvidedSequencerConfig() {
        Map<GameSound, Integer> baseMap = new EnumMap<>(GameSound.class);
        audioManager.setSoundMap(baseMap);

        SmpsSequencerConfig donorConfig = new SmpsSequencerConfig.Builder()
                .tempoMode(SmpsSequencerConfig.TempoMode.OVERFLOW)
                .build();

        StubSmpsLoader donorLoader = new StubSmpsLoader();
        donorLoader.sfxResults.put(0xE0, new StubSmpsData("donor-spindash"));
        audioManager.registerDonorLoader("s3k", donorLoader, EMPTY_DAC, donorConfig);
        audioManager.registerDonorSound(GameSound.SPINDASH_CHARGE, "s3k", 0xE0);

        audioManager.playSfx(GameSound.SPINDASH_CHARGE, 1.0f);

        assertEquals(AudioCommand.SfxRoute.DONOR_SMPS, lastSfx().route());
        assertEquals("s3k", lastSfx().donorGameId());
    }

    @Test
    public void presentationFactoryPreservesDonorRouteAndSequencerConfig() {
        SmpsCoordFlagHandlerOwner handlers = new SmpsCoordFlagHandlerOwner(
                new SmpsCoordFlagRuntimeState());
        SmpsDriverSession session =
                SmpsSessionTestSupport.installed(48_000);
        AudioPresentationSourceFactory factory =
                new AudioPresentationSourceFactory(
                        () -> true, handlers,
                        AudioPresentationSourceFactory.Settings.defaults(),
                        session);
        SmpsSequencerConfig donorConfig =
                new SmpsSequencerConfig.Builder()
                        .tempoMode(SmpsSequencerConfig.TempoMode.OVERFLOW)
                        .build();
        SmpsAssetKey key = new SmpsAssetKey(
                "s2", SmpsAssetKey.Route.DONOR_ID, 0xE0, null);
        factory.registerSmpsSfxAsset(
                key, 0, new StubSmpsData("donor-spindash"),
                EMPTY_DAC, donorConfig, false);
        ResolvedSmpsSfxSource source = factory.resolveSmpsSfx(
                1, key, 1 << 16, 0x70, 0, 0, 2_048);
        AudioVoiceRegistry registry = new AudioVoiceRegistry(
                factory, factory, handlers, ignored -> {
                }, session);

        AudioPresentationSessionCommandApplier.apply(
                session, registry,
                new AudioPresentationCommand.AddSmpsSfx(source));

        assertEquals(SmpsSequencerConfig.TempoMode.OVERFLOW,
                session.captureLogicalSnapshot().sequencers().get(0)
                        .config().getTempoMode());
        assertEquals("s2",
                session.captureLogicalSnapshot().sequencers().get(0)
                        .source().donorGameId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"s1", "s2"})
    void hostSessionS3kDonorMusicAndSfxUseSharedOwnersOnBothPaths(
            String hostGameId) {
        HeadlessSmpsAudioBackend realBackend =
                new HeadlessSmpsAudioBackend(
                        SonicConfigurationService.getInstance(), null);
        audioManager.setBackend(realBackend);
        audioManager.setAudioProfile(
                new HostAudioProfile(hostGameId, new StubSmpsLoader()));
        audioManager.setRom(new Rom());

        StubSmpsLoader donor = new StubSmpsLoader();
        StubSmpsData music = new StubSmpsData("s3k-donor-music");
        music.setId(0x21);
        StubSmpsData sfx = new StubSmpsData("s3k-donor-sfx");
        sfx.setId(0xA4);
        donor.musicResults.put(0x21, music);
        donor.sfxResults.put(0xA4, sfx);
        Sonic3kAudioProfile donorProfile = new Sonic3kAudioProfile();
        audioManager.registerDonorLoader(
                "s3k", donor, EMPTY_DAC,
                donorProfile.getSequencerConfig(), donorProfile);

        audioManager.playDonorMusic("s3k", 0x21);
        audioManager.playDonorSfx("s3k", 0xA4);
        audioManager.presentFrame(PresentationMode.SILENT);

        var presentationOwner =
                audioManager.presentationCoordFlagHandlersForTesting();

        var shadowDriver =
                audioManager.shadowSmpsDriverSnapshotForTesting();
        assertNotNull(shadowDriver);
        assertEquals(2, shadowDriver.sequencers().size(),
                "shadow donor SFX must join shadow donor music's driver");
        var shadowMusicHandler = shadowDriver.sequencers().get(0)
                .config().getCoordFlagHandler();
        var shadowSfxHandler = shadowDriver.sequencers().get(1)
                .config().getCoordFlagHandler();
        assertSame(presentationOwner.handlerFor("s3k"),
                shadowMusicHandler);
        assertSame(shadowMusicHandler, shadowSfxHandler,
                "shadow donor music and SFX must share the counter owner");
        presentationOwner.state().setSpindashRevCounter(29);
        assertEquals(29, presentationOwner.state().spindashRevCounter());
        shadowSfxHandler.onSfxStart(0);
        assertEquals(0, presentationOwner.state().spindashRevCounter(),
                "the configured shadow SFX handler must mutate the shared "
                        + "presentation counter");
    }

    @Test
    void repeatedDonorMusicLoadsAndFreezesOncePerGeneration() {
        AtomicInteger materializations = new AtomicInteger();
        StubSmpsLoader firstLoader = new StubSmpsLoader();
        firstLoader.musicFactories.put(0x21,
                () -> new CountingDonorMusicData(
                        0x21, materializations, (byte) 0x21));
        SmpsSequencerConfig config =
                new SmpsSequencerConfig.Builder().build();
        audioManager.registerDonorLoader(
                "s3k", firstLoader, EMPTY_DAC, config);

        audioManager.playDonorMusic("s3k", 0x21);
        audioManager.presentFrame(PresentationMode.SILENT);
        AudioLogicalSnapshot rewindPoint =
                audioManager.captureLogicalSnapshot();
        long firstVoiceId = rewindPoint.presentation()
                .activeMusic().voiceId();
        var first = audioManager.shadowSmpsDriverSnapshotForTesting()
                .sequencers().getFirst();
        audioManager.update();

        audioManager.playDonorMusic("s3k", 0x21);
        audioManager.presentFrame(PresentationMode.SILENT);
        AudioLogicalSnapshot repeated = audioManager.captureLogicalSnapshot();
        long secondVoiceId = repeated.presentation()
                .activeMusic().voiceId();
        var second = audioManager.shadowSmpsDriverSnapshotForTesting()
                .sequencers().getFirst();

        assertEquals(1, firstLoader.musicLoadCount);
        assertEquals(1, materializations.get(),
                "a donor catalog hit must not freeze, hash, or compare again");
        assertNotEquals(firstVoiceId, secondVoiceId);
        assertSharedMusicDependencies(first, second);
        assertNotSame(first.snapshot(), second.snapshot());
        assertNotSame(first.snapshot().tracks(), second.snapshot().tracks());
        assertNotSame(first.snapshot().tracks().getFirst(),
                second.snapshot().tracks().getFirst());

        StubSmpsLoader replacementLoader = new StubSmpsLoader();
        replacementLoader.musicFactories.put(0x21,
                () -> new CountingDonorMusicData(
                        0x21, materializations, (byte) 0x22));
        audioManager.registerDonorLoader(
                "s3k", replacementLoader, EMPTY_DAC, config);
        audioManager.playDonorMusic("s3k", 0x21);
        audioManager.presentFrame(PresentationMode.SILENT);
        var replacement = audioManager.shadowSmpsDriverSnapshotForTesting()
                .sequencers().getFirst();

        assertEquals(1, replacementLoader.musicLoadCount,
                "the donor generation change must load exactly once");
        assertEquals(2, materializations.get(),
                "the donor generation change must freeze exactly once");
        assertNotEquals(first.source().dependencyGeneration(),
                replacement.source().dependencyGeneration());
        assertNotSame(first.smpsData(), replacement.smpsData());

        audioManager.restoreLogicalSnapshot(rewindPoint);
        var restored = audioManager.shadowSmpsDriverSnapshotForTesting()
                .sequencers().getFirst();
        assertSharedMusicDependencies(first, restored);
    }

    @Test
    void nullDonorMusicProbeRemainsANoOp() {
        StubSmpsLoader loader = new StubSmpsLoader();
        audioManager.registerDonorLoader(
                "s3k", loader, EMPTY_DAC,
                new SmpsSequencerConfig.Builder().build());
        int before = audioManager.commandTimeline().entryCount();

        audioManager.playDonorMusic("s3k", 0x21);

        assertEquals(before, audioManager.commandTimeline().entryCount());
        assertEquals(1, loader.musicLoadCount);
    }

    @Test
    void donorReplaceAndClearAdvanceOnlyTheirGenerationAndRetainOldSnapshots() {
        SmpsSequencerConfig config = new SmpsSequencerConfig.Builder().build();
        DacData s2FirstDac = dac(301);
        DacData s2SecondDac = dac(302);
        DacData s2ThirdDac = dac(303);
        DacData s3kDac = dac(304);
        audioManager.registerDonorLoader(
                "s2", loader(s2FirstDac, 0xE0, (byte) 0x11),
                s2FirstDac, config);
        ObservedSource first = observeDonorSfx("s2", 0xE0);
        AudioLogicalSnapshot oldSnapshot = audioManager.captureLogicalSnapshot();
        audioManager.registerDonorLoader(
                "s3k", loader(s3kDac, 0x54, (byte) 0x21),
                s3kDac, config);
        ObservedSource unrelated = observeDonorSfx("s3k", 0x54);

        audioManager.registerDonorLoader(
                "s2", loader(s2SecondDac, 0xE0, (byte) 0x12),
                s2SecondDac, config);
        ObservedSource replaced = observeDonorSfx("s2", 0xE0);
        ObservedSource unrelatedAfterReplace = observeDonorSfx("s3k", 0x54);

        assertTrue(replaced.descriptor().dependencyGeneration()
                > first.descriptor().dependencyGeneration());
        assertEquals(unrelated.descriptor().dependencyGeneration(),
                unrelatedAfterReplace.descriptor().dependencyGeneration());
        assertSame(s2FirstDac, first.dac());
        assertSame(s2SecondDac, replaced.dac());
        assertNotEquals(first.descriptor().dataHash(),
                replaced.descriptor().dataHash(),
                "replacement must preserve the new program identity");

        audioManager.clearDonorAudio();
        audioManager.registerDonorLoader(
                "s2", loader(s2ThirdDac, 0xE0, (byte) 0x13),
                s2ThirdDac, config);
        ObservedSource reregistered = observeDonorSfx("s2", 0xE0);
        assertTrue(reregistered.descriptor().dependencyGeneration()
                > replaced.descriptor().dependencyGeneration());
        assertSame(s2ThirdDac, reregistered.dac());

        audioManager.restoreLogicalSnapshot(oldSnapshot);
        ObservedSource restored = currentDonorSource("s2", 0xE0);
        assertEquals(first.descriptor(), restored.descriptor());
        assertSame(s2FirstDac, restored.dac());
    }

    @Test
    void equalBaseAndDonorIdsKeepRouteSpecificDependenciesAndPolicies() {
        DacData baseDac = dac(307);
        DacData donorDac = dac(308);
        SmpsSequencerConfig baseConfig =
                new SmpsSequencerConfig.Builder()
                        .tempoMode(SmpsSequencerConfig.TempoMode.TIMEOUT)
                        .build();
        SmpsSequencerConfig donorConfig =
                new SmpsSequencerConfig.Builder()
                        .tempoMode(SmpsSequencerConfig.TempoMode.OVERFLOW)
                        .build();
        StubSmpsLoader baseLoader = loader(
                baseDac, 0xA7, (byte) 0x27);
        audioManager.setAudioProfile(new PolicyAudioProfile(
                "shared", baseLoader, baseConfig));
        audioManager.setRom(new Rom());
        audioManager.registerDonorLoader(
                "shared", loader(donorDac, 0xB7, (byte) 0x37),
                donorDac, donorConfig);

        assertTrue(audioManager.playSfx(0xA7));
        audioManager.presentFrame(PresentationMode.SILENT);
        var baseSnapshot = audioManager.shadowSmpsDriverSnapshotForTesting();
        assertNotNull(baseSnapshot);
        var base = baseSnapshot.sequencers().stream()
                .filter(entry -> entry.source().kind()
                        == SmpsSourceDescriptor.Kind.BASE_SFX_ID)
                .findFirst().orElseThrow();
        audioManager.update();

        audioManager.playDonorSfx("shared", 0xB7);
        audioManager.presentFrame(PresentationMode.SILENT);
        var donorSnapshot = audioManager.shadowSmpsDriverSnapshotForTesting();
        assertNotNull(donorSnapshot);
        var donor = donorSnapshot.sequencers().stream()
                .filter(entry -> entry.source().kind()
                        == SmpsSourceDescriptor.Kind.DONOR_SFX_ID)
                .findFirst().orElseThrow();
        assertSame(baseDac, base.dacData());
        assertSame(donorDac, donor.dacData());
        assertEquals(SmpsSequencerConfig.TempoMode.TIMEOUT,
                base.config().getTempoMode());
        assertEquals(SmpsSequencerConfig.TempoMode.OVERFLOW,
                donor.config().getTempoMode());
        assertEquals(0x31, base.snapshot().sfxPriority());
        assertEquals(0x70, donor.snapshot().sfxPriority());
        assertTrue(base.snapshot().specialSfx());
        assertFalse(donor.snapshot().specialSfx());
        assertNotEquals(base.source().dataHash(), donor.source().dataHash());
    }

    @Test
    void donorBackendOrPresentationFailureRetainsThePreviousTupleAndGeneration() {
        SmpsSequencerConfig config = new SmpsSequencerConfig.Builder().build();
        DacData oldDac = dac(311);
        DonorProfile oldProfile = new DonorProfile("s2");
        audioManager.registerDonorLoader(
                "s2", loader(oldDac, 0xE1, (byte) 0x31),
                oldDac, config, oldProfile);
        ObservedSource initial = observeDonorSfx("s2", 0xE1);

        DacData backendCandidateDac = dac(312);
        DonorProfile backendCandidate = new DonorProfile("s2");
        backend.failDonorProfile = backendCandidate;
        assertThrows(IllegalStateException.class,
                () -> audioManager.registerDonorLoader(
                        "s2", loader(backendCandidateDac, 0xE1, (byte) 0x32),
                        backendCandidateDac, config, backendCandidate));
        assertEquals(java.util.List.of(backendCandidate, oldProfile),
                backend.lastDonorProfileAttempts(2));
        assertSame(oldProfile, backend.activeDonorProfile,
                "a backend that mutates before throwing must be restored");
        assertEquals(initial, observeDonorSfx("s2", 0xE1));

        backend.failDonorProfile = null;
        DacData presentationCandidateDac = dac(313);
        DonorProfile presentationCandidate = new DonorProfile("s2-v2");
        presentationCandidate.registerBeforeFailure = true;
        presentationCandidate.configureFailure =
                new IllegalStateException("presentation config failed");
        assertThrows(IllegalStateException.class,
                () -> audioManager.registerDonorLoader(
                        "s2", loader(presentationCandidateDac,
                                0xE1, (byte) 0x33),
                        presentationCandidateDac, config,
                        presentationCandidate));
        assertEquals(initial, observeDonorSfx("s2", 0xE1));
        assertThrows(IllegalArgumentException.class,
                () -> audioManager.presentationCoordFlagHandlersForTesting()
                        .handlerFor("s2-v2"),
                "a handler registered before the profile threw must roll back");

        presentationCandidate.configureFailure = null;
        audioManager.registerDonorLoader(
                "s2", loader(presentationCandidateDac, 0xE1, (byte) 0x33),
                presentationCandidateDac, config, presentationCandidate);
        ObservedSource retried = observeDonorSfx("s2", 0xE1);
        assertEquals(initial.descriptor().dependencyGeneration() + 1,
                retried.descriptor().dependencyGeneration());
        assertSame(presentationCandidateDac, retried.dac());
        assertNotNull(audioManager.presentationCoordFlagHandlersForTesting()
                .handlerFor("s2-v2"));
    }

    @Test
    void firstDonorBackendErrorRestoresTheExplicitEmptyConfiguration() {
        SmpsSequencerConfig config = new SmpsSequencerConfig.Builder().build();
        DonorProfile candidate = new DonorProfile("candidate");
        DacData candidateDac = dac(315);
        backend.failDonorErrorProfile = candidate;

        assertThrows(AssertionError.class,
                () -> audioManager.registerDonorLoader(
                        "candidate",
                        loader(candidateDac, 0xE3, (byte) 0x35),
                        candidateDac, config, candidate));

        assertEquals(2, backend.donorProfileAttempts.size());
        assertSame(candidate, backend.donorProfileAttempts.get(0));
        assertNull(backend.donorProfileAttempts.get(1));
        assertNull(backend.activeDonorProfile,
                "null is the backend's explicit no-donor rollback state");
        int commandCount = audioManager.commandTimeline().entryCount();
        audioManager.playDonorSfx("candidate", 0xE3);
        assertEquals(commandCount,
                audioManager.commandTimeline().entryCount());
    }

    @Test
    void reentrantDonorReplacementCannotMixOneResolutionTuple() {
        SmpsSequencerConfig oldConfig = new SmpsSequencerConfig.Builder()
                .tempoMode(SmpsSequencerConfig.TempoMode.OVERFLOW2)
                .build();
        SmpsSequencerConfig newConfig = new SmpsSequencerConfig.Builder()
                .tempoMode(SmpsSequencerConfig.TempoMode.OVERFLOW)
                .build();
        DacData oldDac = dac(316);
        DacData newDac = dac(317);
        StubSmpsLoader oldLoader = loader(
                oldDac, 0xE4, (byte) 0x36);
        StubSmpsLoader newLoader = loader(
                newDac, 0xE4, (byte) 0x37);
        oldLoader.sfxResults.put(
                0xE3, new StubSmpsData("donor-old", (byte) 0x36));
        newLoader.sfxResults.put(
                0xE3, new StubSmpsData("donor-new", (byte) 0x37));
        audioManager.registerDonorLoader(
                "s2", oldLoader, oldDac, oldConfig);
        ObservedSource initial = observeDonorSfx("s2", 0xE3);
        oldLoader.runOnSfxLoadNumber = oldLoader.sfxLoadCount + 1;
        oldLoader.onSfxLoad = () -> audioManager.registerDonorLoader(
                "s2", newLoader, newDac, newConfig);

        audioManager.playDonorSfx("s2", 0xE4);
        audioManager.presentFrame(PresentationMode.SILENT);
        var snapshot = audioManager.shadowSmpsDriverSnapshotForTesting();
        var reentrant = snapshot.sequencers().get(
                snapshot.sequencers().size() - 1);

        assertEquals(0xE4, reentrant.source().id(),
                "the reentrant donor command must be admitted");
        assertEquals(initial.descriptor().dependencyGeneration(),
                reentrant.source().dependencyGeneration());
        assertEquals(initial.descriptor().dataHash(),
                reentrant.source().dataHash(),
                "the old donor loader/program marker must retain its generation");
        assertSame(oldDac, reentrant.dacData());
        assertEquals(SmpsSequencerConfig.TempoMode.OVERFLOW2,
                reentrant.config().getTempoMode());
        audioManager.update();
        ObservedSource replacement = observeDonorSfx("s2", 0xE4);
        assertSame(newDac, replacement.dac());
        assertNotEquals(initial.descriptor().dataHash(),
                replacement.descriptor().dataHash());
    }

    @Test
    void donorPresentationFailureBeforeOwnerCreationPublishesNothing() {
        SmpsSequencerConfig config = new SmpsSequencerConfig.Builder().build();
        DacData candidateDac = dac(319);
        DonorProfile candidate = new DonorProfile("candidate");
        candidate.registerBeforeFailure = true;
        candidate.configureFailure =
                new IllegalStateException("presentation config failed");
        int backendAttempts = backend.donorProfileAttempts.size();

        assertThrows(IllegalStateException.class,
                () -> audioManager.registerDonorLoader(
                        "candidate", loader(candidateDac,
                                0xE5, (byte) 0x39),
                        candidateDac, config, candidate));

        assertEquals(backendAttempts, backend.donorProfileAttempts.size(),
                "presentation must be prepared before backend publication");
        int commandCount = audioManager.commandTimeline().entryCount();
        audioManager.playDonorSfx("candidate", 0xE5);
        assertEquals(commandCount, audioManager.commandTimeline().entryCount());
        assertThrows(IllegalArgumentException.class,
                () -> audioManager.presentationCoordFlagHandlersForTesting()
                        .handlerFor("candidate"));

        candidate.configureFailure = null;
        audioManager.registerDonorLoader(
                "candidate", loader(candidateDac, 0xE5, (byte) 0x39),
                candidateDac, config, candidate);
        ObservedSource retried = observeDonorSfx("candidate", 0xE5);
        assertEquals(1, retried.descriptor().dependencyGeneration());
        assertNotNull(audioManager.presentationCoordFlagHandlersForTesting()
                .handlerFor("candidate"));
    }

    @Test
    void donorRestoreFailureRemovesStaleEntryAndConsumesOneGeneration() {
        SmpsSequencerConfig config = new SmpsSequencerConfig.Builder().build();
        DacData oldDac = dac(321);
        DonorProfile oldProfile = new DonorProfile("s2");
        audioManager.registerDonorLoader(
                "s2", loader(oldDac, 0xE2, (byte) 0x41),
                oldDac, config, oldProfile);
        ObservedSource initial = observeDonorSfx("s2", 0xE2);
        DonorProfile candidate = new DonorProfile("s2");
        DacData candidateDac = dac(322);
        backend.failDonorProfile = candidate;
        backend.failDonorRestoreProfile = oldProfile;

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> audioManager.registerDonorLoader(
                        "s2", loader(candidateDac, 0xE2, (byte) 0x42),
                        candidateDac, config, candidate));

        assertEquals(1, failure.getSuppressed().length);
        int commandCount = audioManager.commandTimeline().entryCount();
        audioManager.playDonorSfx("s2", 0xE2);
        assertEquals(commandCount, audioManager.commandTimeline().entryCount(),
                "a donor with failed rollback must not remain selectable");
        backend.failDonorProfile = null;
        backend.failDonorRestoreProfile = null;
        audioManager.registerDonorLoader(
                "s2", loader(candidateDac, 0xE2, (byte) 0x42),
                candidateDac, config, candidate);
        ObservedSource retried = observeDonorSfx("s2", 0xE2);
        assertEquals(initial.descriptor().dependencyGeneration() + 2,
                retried.descriptor().dependencyGeneration());
    }

    @ParameterizedTest(name = "{0} callback rejects nested donor {1}")
    @MethodSource("donorMutationCallbackCases")
    void donorMutationCallbacksRejectNestedRegisterAndClearBeforePublication(
            DonorCallbackStage callbackStage,
            NestedDonorMutation nestedMutation) {
        SmpsSequencerConfig config = new SmpsSequencerConfig.Builder().build();
        DacData oldDac = dac(331);
        DonorProfile oldProfile = new DonorProfile("old");
        audioManager.registerDonorLoader(
                "s2", loader(oldDac, 0xE6, (byte) 0x51),
                oldDac, config, oldProfile);
        ObservedSource initial = observeDonorSfx("s2", 0xE6);
        DacData candidateDac = dac(332);
        DonorProfile candidateProfile = new DonorProfile("candidate");
        candidateProfile.registerBeforeFailure = true;
        DacData nestedDac = dac(333);
        Runnable nestedAttempt = switch (nestedMutation) {
            case REGISTER -> () -> audioManager.registerDonorLoader(
                    "nested",
                    loader(nestedDac, 0xE7, (byte) 0x53),
                    nestedDac, config);
            case CLEAR -> audioManager::clearDonorAudio;
        };
        if (callbackStage == DonorCallbackStage.HANDLER) {
            candidateProfile.onConfigure = nestedAttempt;
        } else {
            backend.callbackDonorProfile = candidateProfile;
            backend.onDonorProfileSet = nestedAttempt;
        }
        Runnable outerAttempt = () -> audioManager.registerDonorLoader(
                "s2", loader(candidateDac, 0xE6, (byte) 0x52),
                candidateDac, config, candidateProfile);

        assertThrows(IllegalStateException.class, outerAttempt::run);

        assertEquals(initial, observeDonorSfx("s2", 0xE6));
        assertSame(oldProfile, backend.activeDonorProfile);
        assertThrows(IllegalArgumentException.class,
                () -> audioManager.presentationCoordFlagHandlersForTesting()
                        .handlerFor("candidate"));
        int commandCount = audioManager.commandTimeline().entryCount();
        audioManager.playDonorSfx("nested", 0xE7);
        assertEquals(commandCount, audioManager.commandTimeline().entryCount(),
                "the rejected nested register must publish no donor");

        outerAttempt.run();
        ObservedSource retried = observeDonorSfx("s2", 0xE6);
        assertEquals(initial.descriptor().dependencyGeneration() + 1,
                retried.descriptor().dependencyGeneration(),
                "the rejected nested mutation must consume no generation");
        assertSame(candidateDac, retried.dac());
    }

    @Test
    void rejectedNestedDonorRegistrationDoesNotConsumeItsGeneration() {
        SmpsSequencerConfig config = new SmpsSequencerConfig.Builder().build();
        DacData nestedDac = dac(334);
        StubSmpsLoader nestedLoader = loader(
                nestedDac, 0xE8, (byte) 0x54);
        DonorProfile outerProfile = new DonorProfile("outer");
        outerProfile.registerBeforeFailure = true;
        outerProfile.onConfigure = () -> audioManager.registerDonorLoader(
                "nested", nestedLoader, nestedDac, config);
        DacData outerDac = dac(335);

        assertThrows(IllegalStateException.class,
                () -> audioManager.registerDonorLoader(
                        "outer", loader(outerDac, 0xE9, (byte) 0x55),
                        outerDac, config, outerProfile));

        audioManager.registerDonorLoader(
                "nested", nestedLoader, nestedDac, config);
        ObservedSource nested = observeDonorSfx("nested", 0xE8);
        assertEquals(1, nested.descriptor().dependencyGeneration(),
                "rejection must precede nested generation-counter publication");
    }

    @Test
    void rejectedNestedResetPreservesRetainedGenerationAfterClear() {
        SmpsSequencerConfig config = new SmpsSequencerConfig.Builder().build();
        DacData firstDac = dac(336);
        audioManager.registerDonorLoader(
                "cleared", loader(firstDac, 0xEA, (byte) 0x56),
                firstDac, config);
        ObservedSource first = observeDonorSfx("cleared", 0xEA);
        audioManager.clearDonorAudio();
        DonorProfile outerProfile = new DonorProfile("outer");
        outerProfile.registerBeforeFailure = true;
        outerProfile.onConfigure = audioManager::resetState;
        DacData outerDac = dac(337);

        assertThrows(IllegalStateException.class,
                () -> audioManager.registerDonorLoader(
                        "outer", loader(outerDac, 0xEB, (byte) 0x57),
                        outerDac, config, outerProfile));

        DacData replacementDac = dac(338);
        audioManager.registerDonorLoader(
                "cleared", loader(replacementDac, 0xEA, (byte) 0x58),
                replacementDac, config);
        ObservedSource replacement = observeDonorSfx("cleared", 0xEA);
        assertEquals(first.descriptor().dependencyGeneration() + 2,
                replacement.descriptor().dependencyGeneration(),
                "clear consumes one generation and rejected reset consumes none");
        assertSame(replacementDac, replacement.dac());
    }

    private static Stream<Arguments> donorMutationCallbackCases() {
        return Arrays.stream(DonorCallbackStage.values())
                .flatMap(stage -> Arrays.stream(NestedDonorMutation.values())
                        .map(nested -> Arguments.of(stage, nested)));
    }

    private ObservedSource observeDonorSfx(String gameId, int sfxId) {
        audioManager.playDonorSfx(gameId, sfxId);
        audioManager.presentFrame(PresentationMode.SILENT);
        ObservedSource observed = currentDonorSource(gameId, sfxId);
        audioManager.update();
        return observed;
    }

    private ObservedSource currentDonorSource(String gameId, int sfxId) {
        var snapshot = audioManager.shadowSmpsDriverSnapshotForTesting();
        assertNotNull(snapshot);
        var sequencer = snapshot.sequencers().stream()
                .filter(entry -> gameId.equals(entry.source().donorGameId())
                        && entry.source().id() == sfxId)
                .reduce((first, second) -> second)
                .orElseThrow();
        return new ObservedSource(sequencer.source(), sequencer.dacData());
    }

    private static DacData dac(int cycles) {
        return new DacData(Map.of(), Map.of(), cycles);
    }

    private static StubSmpsLoader loader(
            DacData dac, int sfxId, byte marker) {
        StubSmpsLoader loader = new StubSmpsLoader(dac);
        loader.sfxResults.put(sfxId,
                new StubSmpsData("donor-" + sfxId, marker));
        return loader;
    }

    private record ObservedSource(
            SmpsSourceDescriptor descriptor, DacData dac) {
    }

    private static void assertSharedMusicDependencies(
            com.openggf.audio.rewind.SmpsDriverSnapshot.SequencerEntry expected,
            com.openggf.audio.rewind.SmpsDriverSnapshot.SequencerEntry actual) {
        assertSame(expected.source(), actual.source());
        assertSame(expected.smpsData(), actual.smpsData());
        assertSame(expected.dacData(), actual.dacData());
        assertSame(expected.config(), actual.config());
    }

    private enum DonorCallbackStage {
        HANDLER,
        BACKEND
    }

    private enum NestedDonorMutation {
        REGISTER,
        CLEAR
    }

    // --- Test doubles ---

    private AudioCommand.PlaySfx lastSfx() {
        var entries = audioManager.commandTimeline().entries();
        return (AudioCommand.PlaySfx) entries.get(entries.size() - 1).command();
    }

    /** Minimal SmpsData stub that carries a name for assertion. */
    private static class StubSmpsData extends AbstractSmpsData {
        final String name;

        StubSmpsData(String name) {
            this(name, (byte) 0);
        }

        StubSmpsData(String name, byte marker) {
            super(new byte[] {marker}, 0);
            this.name = name;
        }

        @Override protected void parseHeader() {}
        @Override public byte[] getVoice(int voiceId) { return new byte[0]; }
        @Override public byte[] getPsgEnvelope(int id) { return new byte[0]; }
        @Override public int read16(int offset) { return 0; }
        @Override public int getBaseNoteOffset() { return 0; }

        @Override
        public String toString() {
            return name;
        }
    }

    /** SmpsLoader stub that returns pre-configured results by sfxId. */
    private static class StubSmpsLoader implements SmpsLoader {
        final Map<Integer, AbstractSmpsData> musicResults = new HashMap<>();
        final Map<Integer, Supplier<AbstractSmpsData>> musicFactories =
                new HashMap<>();
        final Map<Integer, AbstractSmpsData> sfxResults = new HashMap<>();
        final DacData dac;
        int musicLoadCount;
        int sfxLoadCount;
        int runOnSfxLoadNumber = -1;
        Runnable onSfxLoad;

        StubSmpsLoader() {
            this(EMPTY_DAC);
        }

        StubSmpsLoader(DacData dac) {
            this.dac = dac;
        }

        @Override
        public AbstractSmpsData loadMusic(int musicId) {
            musicLoadCount++;
            Supplier<AbstractSmpsData> factory =
                    musicFactories.get(musicId);
            return factory != null ? factory.get()
                    : musicResults.get(musicId);
        }

        @Override
        public AbstractSmpsData loadSfx(int sfxId) {
            AbstractSmpsData result = sfxResults.get(sfxId);
            sfxLoadCount++;
            if (sfxLoadCount == runOnSfxLoadNumber) {
                Runnable callback = onSfxLoad;
                onSfxLoad = null;
                if (callback != null) {
                    callback.run();
                }
            }
            return result;
        }

        @Override
        public AbstractSmpsData loadSfx(String sfxName) {
            return null;
        }

        @Override
        public DacData loadDacData() {
            return dac;
        }
    }

    private static final class CountingDonorMusicData
            extends AbstractSmpsData {
        private final AtomicInteger materializations;

        private CountingDonorMusicData(
                int id, AtomicInteger materializations, byte marker) {
            super(musicBytes(marker), 0);
            this.materializations = materializations;
            setId(id);
        }

        private static byte[] musicBytes(byte marker) {
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

    private static final class DonorProfile extends StubAudioProfile {
        private final String gameId;
        RuntimeException configureFailure;
        boolean registerBeforeFailure;
        Runnable onConfigure;

        private DonorProfile(String gameId) {
            super(new StubSmpsLoader());
            this.gameId = gameId;
        }

        @Override
        public String presentationGameId() {
            return gameId;
        }

        @Override
        public void configurePresentationCoordFlagHandlers(
                SmpsCoordFlagHandlerOwner owner) {
            if (registerBeforeFailure) {
                owner.register(gameId, ignored -> new ArbitraryHandler());
            }
            Runnable callback = onConfigure;
            onConfigure = null;
            if (callback != null) {
                callback.run();
            }
            if (configureFailure != null) {
                throw configureFailure;
            }
        }
    }

    private static final class ArbitraryHandler implements CoordFlagHandler {
        @Override
        public boolean handleFlag(
                CoordFlagContext context, SmpsSequencer.Track track,
                int command) {
            return false;
        }

        @Override
        public int flagParamLength(int command) {
            return -1;
        }
    }

    private static final class HostAudioProfile extends StubAudioProfile {
        private final String gameId;

        private HostAudioProfile(String gameId, SmpsLoader loader) {
            super(loader);
            this.gameId = gameId;
        }

        @Override
        public String presentationGameId() {
            return gameId;
        }

        @Override
        public SmpsSequencerConfig getSequencerConfig() {
            return new SmpsSequencerConfig.Builder().build();
        }
    }

    private static final class PolicyAudioProfile extends StubAudioProfile {
        private final String gameId;
        private final SmpsSequencerConfig config;

        private PolicyAudioProfile(
                String gameId,
                SmpsLoader loader,
                SmpsSequencerConfig config) {
            super(loader);
            this.gameId = gameId;
            this.config = config;
        }

        @Override
        public String presentationGameId() {
            return gameId;
        }

        @Override
        public SmpsSequencerConfig getSequencerConfig() {
            return config;
        }

        @Override
        public int getSfxPriority(int soundId) {
            return 0x31;
        }

        @Override
        public boolean isSpecialSfx(int soundId) {
            return true;
        }

        @Override
        public boolean isContinuousSfx(int soundId) {
            return true;
        }
    }

    /** Stub audio profile that returns the given loader. */
    private static class StubAudioProfile implements GameAudioProfile {
        private final SmpsLoader loader;

        StubAudioProfile(SmpsLoader loader) {
            this.loader = loader;
        }

        @Override
        public SmpsLoader createSmpsLoader(Rom rom) {
            return loader;
        }

        @Override
        public SmpsSequencerConfig getSequencerConfig() {
            return null;
        }

        @Override
        public int getSpeedShoesOnCommandId() { return -1; }

        @Override
        public int getSpeedShoesOffCommandId() { return -1; }

        @Override
        public int getInvincibilityMusicId() { return -1; }

        @Override
        public int getExtraLifeMusicId() { return -1; }

        @Override
        public int getDrowningMusicId() { return -1; }

        @Override
        public Map<GameSound, Integer> getSoundMap() {
            return Map.of();
        }
    }

    /** Records the last SFX played for assertion. */
    private static class RecordingBackend extends NullAudioBackend {
        String lastSfxName;
        String lastFallbackName;
        SmpsSequencerConfig lastDonorConfig;
        final java.util.List<GameAudioProfile> donorProfileAttempts =
                new java.util.ArrayList<>();
        GameAudioProfile failDonorProfile;
        GameAudioProfile failDonorRestoreProfile;
        GameAudioProfile failDonorErrorProfile;
        GameAudioProfile activeDonorProfile;
        GameAudioProfile callbackDonorProfile;
        Runnable onDonorProfileSet;

        @Override
        public void registerAudioProfileCoordHandlers(
                GameAudioProfile profile) {
            donorProfileAttempts.add(profile);
            activeDonorProfile = profile;
            if (profile == callbackDonorProfile) {
                callbackDonorProfile = null;
                Runnable callback = onDonorProfileSet;
                onDonorProfileSet = null;
                if (callback != null) {
                    callback.run();
                }
            }
            if (profile != null && profile == failDonorErrorProfile) {
                throw new AssertionError(
                        "backend rejected donor profile");
            }
            if ((failDonorProfile != null && profile == failDonorProfile)
                    || (failDonorRestoreProfile != null
                    && profile == failDonorRestoreProfile)) {
                throw new IllegalStateException(
                        "backend rejected donor profile");
            }
        }

        java.util.List<GameAudioProfile> lastDonorProfileAttempts(int count) {
            return java.util.List.copyOf(donorProfileAttempts.subList(
                    donorProfileAttempts.size() - count,
                    donorProfileAttempts.size()));
        }

        @Override
        public void playSfxSmps(AbstractSmpsData data, DacData dacData, float pitch) {
            lastSfxName = data.toString();
            lastFallbackName = null;
            lastDonorConfig = null;
        }

        @Override
        public void playSfxSmps(AbstractSmpsData data, DacData dacData, float pitch,
                                SmpsSequencerConfig config) {
            lastSfxName = data.toString();
            lastFallbackName = null;
            lastDonorConfig = config;
        }

        @Override
        public void playSfx(String sfxName, float pitch) {
            lastFallbackName = sfxName;
            lastSfxName = null;
            lastDonorConfig = null;
        }
    }
}
