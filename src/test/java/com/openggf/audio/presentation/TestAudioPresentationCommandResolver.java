package com.openggf.audio.presentation;

import com.openggf.audio.AudioManager;
import com.openggf.audio.driver.SmpsDriver;
import com.openggf.audio.presentation.AudioPresentationCommand.AddSmpsSfx;
import com.openggf.audio.presentation.AudioPresentationCommand.MusicVoiceEntry;
import com.openggf.audio.presentation.AudioPresentationCommand.PushMusicOverride;
import com.openggf.audio.presentation.AudioPresentationCommand.ReplaceMusic;
import com.openggf.audio.presentation.AudioPresentationCommand.ReplaceRawPcm;
import com.openggf.audio.presentation.AudioPresentationCommand.StartSampleSfx;
import com.openggf.audio.rewind.AudioCommand;
import com.openggf.audio.rewind.AudioSourceDescriptor;
import com.openggf.audio.rewind.SmpsDriverSnapshot;
import com.openggf.audio.smps.AbstractSmpsData;
import com.openggf.audio.smps.CoordFlagContext;
import com.openggf.audio.smps.CoordFlagHandler;
import com.openggf.audio.smps.DacData;
import com.openggf.audio.smps.SmpsCoordFlagHandlerOwner;
import com.openggf.audio.smps.SmpsCoordFlagRuntimeState;
import com.openggf.audio.smps.SmpsSequencer;
import com.openggf.audio.smps.SmpsSequencerConfig;
import com.openggf.audio.smps.LoadedSmpsMusic;
import com.openggf.audio.smps.SmpsLoadReadiness;
import com.openggf.audio.smps.SmpsSfxData;
import com.openggf.audio.session.SmpsDriverSession;
import com.openggf.audio.session.SmpsSessionTestSupport;
import com.openggf.game.sonic3k.audio.smps.Sonic3kCoordFlagHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestAudioPresentationCommandResolver {
    private static final DacData EMPTY_DAC =
            new DacData(Collections.emptyMap(), Collections.emptyMap(), 297);

    @AfterEach
    void tearDown() {
        AudioManager.getInstance().resetState();
    }

    @Test
    void resolvesBaseDonorAndFallbackMusic() {
        Fixture fixture = fixture();
        fixture.sources.baseMusic = music(0x81);
        fixture.sources.donorMusic = music(0x91);

        fixture.resolver.submit(new AudioCommand.PlayMusic(
                0x81, AudioCommand.MusicRoute.BASE_SMPS, false, null));
        fixture.resolver.submit(new AudioCommand.PlayMusic(
                0x91, AudioCommand.MusicRoute.DONOR_SMPS, true, "s3k"));
        fixture.resolver.submit(new AudioCommand.PlayMusic(
                0x71, AudioCommand.MusicRoute.FALLBACK_WAV, false, null));

        List<AudioPresentationCommand> commands = drain(fixture.queue);
        assertInstanceOf(ReplaceMusic.class, commands.get(0));
        assertInstanceOf(PushMusicOverride.class, commands.get(1));
        assertInstanceOf(ReplaceMusic.class, commands.get(2));
        assertEquals(AudioSourceDescriptor.baseMusic(0x81),
                ((ReplaceMusic) commands.get(0)).music().sourceDescriptor());
        assertEquals(AudioSourceDescriptor.donorMusic("s3k", 0x91),
                ((PushMusicOverride) commands.get(1)).music().sourceDescriptor());
        assertEquals(AudioSourceDescriptor.fallbackMusic(0x71),
                ((ReplaceMusic) commands.get(2)).music().sourceDescriptor());
    }

    @Test
    void donorMusicCannotImportItsLoadReadinessIntoTheHostSession() {
        Fixture fixture = fixture();
        fixture.sources.donorMusic = music(0x91);
        fixture.sources.musicReadiness = new SmpsLoadReadiness() {
            @Override public boolean immediate() { return false; }
            @Override public int compressedByteCount() { return 1; }
            @Override public int workUnitCount() { return 1; }
            @Override public long minimumTStates(Context context) { return 1; }
            @Override public Work begin(Context context) {
                throw new AssertionError("donor readiness must not begin");
            }
            @Override public String provenance() { return "poison-donor"; }
        };

        fixture.resolver.submit(new AudioCommand.PlayMusic(
                0x91, AudioCommand.MusicRoute.DONOR_SMPS, true, "s2"));

        PushMusicOverride command = assertInstanceOf(
                PushMusicOverride.class, drain(fixture.queue).getFirst());
        AudioPresentationCommand.SmpsVoiceDescriptor voice = assertInstanceOf(
                AudioPresentationCommand.SmpsVoiceDescriptor.class,
                command.music().voiceDescriptor());
        assertTrue(voice.activation().readiness().immediate());
    }

    @Test
    void resolvesBaseNameBaseIdDonorFallbackAndAlternatingRingSfx() {
        Fixture fixture = fixture();
        fixture.sources.baseSfx = sfx(0xA0, (byte) 0xF2);
        fixture.sources.namedSfx = sfx(0xA1, (byte) 0xF2);
        fixture.sources.donorSfx = sfx(0xB0, (byte) 0xF2);

        fixture.resolver.submit(new AudioCommand.PlaySfx(
                0xA0, null, AudioCommand.SfxRoute.BASE_SMPS_ID, 1.0f, null));
        fixture.resolver.submit(new AudioCommand.PlaySfx(
                -1, "JUMP", AudioCommand.SfxRoute.BASE_SMPS_NAME, 0.5f, null));
        fixture.resolver.submit(new AudioCommand.PlaySfx(
                0xB0, null, AudioCommand.SfxRoute.DONOR_SMPS, 1.25f, "s3k"));
        fixture.resolver.submit(new AudioCommand.PlaySfx(
                -1, "SKID", AudioCommand.SfxRoute.FALLBACK_NAME, 0.75f, null));
        fixture.resolver.submit(new AudioCommand.PlaySfx(
                -1, "RING_LEFT", AudioCommand.SfxRoute.RING_RESOLVED, 1.0f, null));
        fixture.resolver.submit(new AudioCommand.PlaySfx(
                -1, "RING_RIGHT", AudioCommand.SfxRoute.RING_RESOLVED, 1.0f, null));

        List<AudioPresentationCommand> commands = drain(fixture.queue);
        assertEquals(8, commands.size());
        assertEquals(SmpsAssetKey.Route.BASE_ID,
                ((AddSmpsSfx) commands.get(0)).source().assetKey().route());
        assertEquals(SmpsAssetKey.Route.BASE_NAME,
                ((AddSmpsSfx) commands.get(1)).source().assetKey().route());
        assertEquals(SmpsAssetKey.Route.DONOR_ID,
                ((AddSmpsSfx) commands.get(2)).source().assetKey().route());
        assertInstanceOf(StartSampleSfx.class, commands.get(3));
        assertEquals(new AudioPresentationCommand.ResetRingAlternation(false),
                commands.get(4));
        assertInstanceOf(StartSampleSfx.class, commands.get(5));
        assertEquals(new AudioPresentationCommand.ResetRingAlternation(true),
                commands.get(6));
        assertInstanceOf(StartSampleSfx.class, commands.get(7));
    }

    @Test
    void repeatedNamedSfxLoadsAndMaterializesOncePerGeneration() {
        Fixture fixture = fixture();
        AtomicInteger materializations = new AtomicInteger();
        fixture.sources.namedSfxFactory = () -> countingSfx(
                0xA1, materializations, (byte) 0xF2);

        fixture.resolver.submit(new AudioCommand.PlaySfx(
                -1, "JUMP", AudioCommand.SfxRoute.BASE_SMPS_NAME,
                0.5f, null));
        fixture.resolver.submit(new AudioCommand.PlaySfx(
                -1, "JUMP", AudioCommand.SfxRoute.BASE_SMPS_NAME,
                1.25f, null));

        List<AudioPresentationCommand> firstGeneration = drain(fixture.queue);
        AddSmpsSfx first = assertInstanceOf(
                AddSmpsSfx.class, firstGeneration.get(0));
        AddSmpsSfx second = assertInstanceOf(
                AddSmpsSfx.class, firstGeneration.get(1));
        assertEquals(1, fixture.sources.calls.get(),
                "a registered named route must not call the loader again");
        assertEquals(1, materializations.get(),
                "a catalog hit must not hash or compare reconstructed data");
        assertNotEquals(first.source().standaloneVoiceId(),
                second.source().standaloneVoiceId());
        assertEquals(0xA1,
                fixture.factory.findRegisteredSmpsSfxAsset(
                        first.source().assetKey(), 0).assetId());
        assertEquals(1, first.source().trackCount());

        fixture.sources.baseGeneration = 1;
        fixture.resolver.submit(new AudioCommand.PlaySfx(
                -1, "JUMP", AudioCommand.SfxRoute.BASE_SMPS_NAME,
                1.0f, null));
        fixture.resolver.submit(new AudioCommand.PlaySfx(
                -1, "JUMP", AudioCommand.SfxRoute.BASE_SMPS_NAME,
                1.0f, null));
        List<AudioPresentationCommand> secondGeneration = drain(fixture.queue);

        assertEquals(2, fixture.sources.calls.get(),
                "a new generation must cause exactly one additional load");
        assertEquals(2, materializations.get(),
                "a new generation must cause exactly one additional freeze");
        assertEquals(1, ((AddSmpsSfx) secondGeneration.get(0)).source()
                .dependencyGeneration());
        assertEquals(1, ((AddSmpsSfx) secondGeneration.get(1)).source()
                .dependencyGeneration());

        SmpsAssetCatalog.ProgramEntry registered =
                fixture.factory.findRegisteredSmpsSfxAsset(
                        first.source().assetKey(), 1);
        SmpsAssetCatalog.ProgramEntry duplicate =
                fixture.factory.registerSmpsSfxAsset(
                        first.source().assetKey(), 1,
                        countingSfx(0xA1, materializations, (byte) 0xF2),
                        fixture.sources.baseDac,
                        fixture.sources.baseConfig, false);
        assertSame(registered, duplicate,
                "explicit reconstructed-equal registration must reuse the entry");
    }

    @Test
    void repeatedNumericSfxLoadsAndMaterializesOnlyOnce() {
        Fixture fixture = fixture();
        AtomicInteger materializations = new AtomicInteger();
        fixture.sources.baseSfxFactory = () -> countingSfx(
                0xA0, materializations, (byte) 0xF2);

        fixture.resolver.submit(new AudioCommand.PlaySfx(
                0xA0, null, AudioCommand.SfxRoute.BASE_SMPS_ID,
                1.0f, null));
        fixture.resolver.submit(new AudioCommand.PlaySfx(
                0xA0, null, AudioCommand.SfxRoute.BASE_SMPS_ID,
                1.0f, null));

        List<AudioPresentationCommand> commands = drain(fixture.queue);
        assertEquals(1, fixture.sources.calls.get());
        assertEquals(1, materializations.get());
        assertNotEquals(((AddSmpsSfx) commands.get(0)).source()
                        .standaloneVoiceId(),
                ((AddSmpsSfx) commands.get(1)).source()
                        .standaloneVoiceId());
    }

    @Test
    void privateResolutionBatchRollsBackCatalogAndVoiceCursorExactly() {
        Fixture fixture = fixture();
        fixture.sources.baseSfx = sfx(0xA0, (byte) 0xF2);
        AudioCommand.PlaySfx request = new AudioCommand.PlaySfx(
                0xA0, null, AudioCommand.SfxRoute.BASE_SMPS_ID,
                1.0f, null);

        var failed = fixture.resolver.beginResolutionBatch();
        failed.resolve(request);
        assertEquals(0, fixture.queue.size(),
                "a request batch must not enter the durable command queue");
        var firstOutcome = failed.apply();
        AddSmpsSfx first = assertInstanceOf(AddSmpsSfx.class,
                firstOutcome.commands().getFirst());
        assertFalse(firstOutcome.commands().isEmpty());
        failed.rollback();

        assertEquals(null, fixture.factory.findRegisteredSmpsSfxAsset(
                first.source().assetKey(), 0),
                "a rejected attempt must not publish its ROM program");

        var retry = fixture.resolver.beginResolutionBatch();
        retry.resolve(request);
        var replayOutcome = retry.apply();
        assertFalse(replayOutcome.commands().isEmpty(), fixture.warnings.toString());
        AddSmpsSfx replay = assertInstanceOf(AddSmpsSfx.class,
                replayOutcome.commands().getFirst());
        assertEquals(first.source().standaloneVoiceId(),
                replay.source().standaloneVoiceId(),
                "rollback must restore the resolver allocation cursor");
        retry.rollback();
    }

    @Test
    void rollbackRestoresBatchOrdinalWithoutMakingIdentityTransplantable()
            throws Exception {
        Fixture fixture = fixture();
        fixture.sources.baseSfx = sfx(0xA0, (byte) 0xF2);
        AudioCommand.PlaySfx request = new AudioCommand.PlaySfx(
                0xA0, null, AudioCommand.SfxRoute.BASE_SMPS_ID,
                1.0f, null);

        var rejected = fixture.resolver.beginResolutionBatch();
        rejected.resolve(request);
        var rejectedReservation = rejected.reservation();
        long rejectedOrdinal = reservationOrdinal(rejectedReservation);
        rejected.rollback();

        var retry = fixture.resolver.beginResolutionBatch();
        retry.resolve(request);
        var retryReservation = retry.reservation();
        assertEquals(rejectedOrdinal, reservationOrdinal(retryReservation),
                "rollback must restore the batch allocation cursor");
        assertNotSame(rejectedReservation, retryReservation,
                "a retry must retain an opaque batch identity");
        var outcome = retry.apply();
        assertTrue(outcome.belongsTo(retryReservation));
        assertFalse(outcome.belongsTo(rejectedReservation),
                "equal ordinals cannot transplant across batch identities");
        retry.rollback();
    }

    private static long reservationOrdinal(
            AudioPresentationCommandResolver.OutcomeReservation reservation)
            throws Exception {
        Field ordinal = reservation.getClass().getDeclaredField("ordinal");
        ordinal.setAccessible(true);
        return ordinal.getLong(reservation);
    }

    @Test
    void nullAndThrowingMusicReturnTypedFailureWithoutWarningsOrEffects() {
        for (boolean throwing : List.of(false, true)) {
            Fixture fixture = fixture();
            fixture.sources.throwMusic = throwing;
            AudioCommand.PlayMusic request = new AudioCommand.PlayMusic(
                    0x81, AudioCommand.MusicRoute.BASE_SMPS, false, null);
            var rejected = fixture.resolver.beginResolutionBatch();

            assertInstanceOf(AudioPresentationCommandResolver.Failure.class,
                    rejected.resolve(request));
            assertEquals(List.of(), fixture.warnings);
            assertEquals(0, fixture.queue.size());
            assertEquals(List.of(), fixture.synchronouslyApplied);
            assertThrows(IllegalStateException.class, rejected::apply);
            rejected.rollback();

            fixture.sources.throwMusic = false;
            fixture.sources.baseMusic = music(0x81);
            var retry = fixture.resolver.beginResolutionBatch();
            assertInstanceOf(
                    AudioPresentationCommandResolver.CompleteSuccess.class,
                    retry.resolve(request));
            var outcome = retry.apply();
            assertEquals(2, outcome.commands().size());
            assertInstanceOf(AudioPresentationCommand.StopAllSfx.class,
                    outcome.commands().get(0));
            ReplaceMusic replacement = assertInstanceOf(
                    ReplaceMusic.class, outcome.commands().get(1));
            assertEquals(1L,
                    replacement.music().voiceDescriptor().voiceId());
            retry.rollback();
        }
    }

    @Test
    void nullAndThrowingOrdinarySfxReturnTypedFailureAndRetryExactly() {
        for (boolean throwing : List.of(false, true)) {
            Fixture fixture = fixture();
            fixture.sources.throwSfx = throwing;
            AudioCommand.PlaySfx request = new AudioCommand.PlaySfx(
                    0xA0, null, AudioCommand.SfxRoute.BASE_SMPS_ID,
                    1.0f, null);
            var rejected = fixture.resolver.beginResolutionBatch();

            assertInstanceOf(AudioPresentationCommandResolver.Failure.class,
                    rejected.resolve(request));
            assertEquals(List.of(), fixture.warnings);
            assertEquals(0, fixture.queue.size());
            assertThrows(IllegalStateException.class, rejected::apply);
            rejected.rollback();

            fixture.sources.throwSfx = false;
            fixture.sources.baseSfx = sfx(0xA0, (byte) 0xF2);
            var retry = fixture.resolver.beginResolutionBatch();
            assertInstanceOf(
                    AudioPresentationCommandResolver.CompleteSuccess.class,
                    retry.resolve(request));
            var outcome = retry.apply();
            AddSmpsSfx replay = assertInstanceOf(
                    AddSmpsSfx.class, outcome.commands().getFirst());
            assertEquals(1L, replay.source().standaloneVoiceId());
            retry.rollback();
        }
    }

    @Test
    void failedRingFallbackNeverPublishesResetWithoutPlayback() {
        for (boolean throwing : List.of(false, true)) {
            Fixture fixture = fixture();
            if (throwing) {
                fixture.assets.throwing.add("sfx/ring.wav");
            } else {
                fixture.assets.malformed.add("sfx/ring.wav");
            }
            AudioCommand.PlaySfx request = new AudioCommand.PlaySfx(
                    -1, "RING_LEFT", AudioCommand.SfxRoute.RING_RESOLVED,
                    1.0f, null);
            var rejected = fixture.resolver.beginResolutionBatch();

            assertInstanceOf(AudioPresentationCommandResolver.Failure.class,
                    rejected.resolve(request));
            assertEquals(List.of(), fixture.warnings);
            assertEquals(List.of(), fixture.synchronouslyApplied,
                    "ring alternation cannot escape without playback");
            rejected.rollback();

            fixture.assets.throwing.clear();
            fixture.assets.malformed.clear();
            var retry = fixture.resolver.beginResolutionBatch();
            assertInstanceOf(
                    AudioPresentationCommandResolver.CompleteSuccess.class,
                    retry.resolve(request));
            var outcome = retry.apply();
            assertEquals(2, outcome.commands().size());
            assertInstanceOf(AudioPresentationCommand.ResetRingAlternation.class,
                    outcome.commands().get(0));
            StartSampleSfx playback = assertInstanceOf(
                    StartSampleSfx.class, outcome.commands().get(1));
            assertEquals(1L, playback.voice().voiceId());
            retry.rollback();
        }
    }

    @Test
    void repeatedDonorSfxLoadsAndMaterializesOnlyOnce() {
        Fixture fixture = fixture();
        AtomicInteger materializations = new AtomicInteger();
        fixture.sources.donorSfxFactory = () -> countingSfx(
                0xB0, materializations, (byte) 0xF2);

        fixture.resolver.submit(new AudioCommand.PlaySfx(
                0xB0, null, AudioCommand.SfxRoute.DONOR_SMPS,
                1.0f, "s3k"));
        fixture.resolver.submit(new AudioCommand.PlaySfx(
                0xB0, null, AudioCommand.SfxRoute.DONOR_SMPS,
                1.0f, "s3k"));

        List<AudioPresentationCommand> commands = drain(fixture.queue);
        assertEquals(1, fixture.sources.calls.get());
        assertEquals(1, materializations.get());
        assertNotEquals(((AddSmpsSfx) commands.get(0)).source()
                        .standaloneVoiceId(),
                ((AddSmpsSfx) commands.get(1)).source()
                        .standaloneVoiceId());
    }

    @Test
    void sourceGenerationSeparatesReplacedBaseProgramsAndRetainsOldCommands() {
        Fixture fixture = fixture();
        fixture.sources.baseGeneration = 7;
        fixture.sources.baseSfx = sfx(0xA0, (byte) 0xF2);
        fixture.resolver.submit(new AudioCommand.PlaySfx(
                0xA0, null, AudioCommand.SfxRoute.BASE_SMPS_ID,
                1.0f, null));
        AddSmpsSfx oldCommand = assertInstanceOf(
                AddSmpsSfx.class, drain(fixture.queue).get(0));

        fixture.sources.baseGeneration = 8;
        fixture.sources.baseSfx = sfx(
                0xA0, (byte) 0xE9, (byte) 0xF2);
        fixture.resolver.submit(new AudioCommand.PlaySfx(
                0xA0, null, AudioCommand.SfxRoute.BASE_SMPS_ID,
                1.0f, null));
        AddSmpsSfx newCommand = assertInstanceOf(
                AddSmpsSfx.class, drain(fixture.queue).get(0));

        assertEquals(7, oldCommand.source().dependencyGeneration());
        assertEquals(8, newCommand.source().dependencyGeneration());
        AudioVoiceRegistry oldRegistry = fixture.registry();
        AudioVoiceRegistry newRegistry = fixture.registry();
        apply(fixture, oldRegistry, oldCommand);
        assertEquals(7, fixture.session.captureLogicalSnapshot()
                .sequencers().get(0).source().dependencyGeneration());
        apply(fixture, newRegistry, newCommand);
        assertEquals(8, fixture.session.captureLogicalSnapshot()
                .sequencers().get(0).source().dependencyGeneration());
    }

    @Test
    void baseAndDonorGenerationsRemainRouteSpecificForEqualGameIds() {
        Fixture fixture = fixture(new SmpsCoordFlagHandlerOwner(
                new SmpsCoordFlagRuntimeState()), "shared");
        fixture.sources.baseGeneration = 3;
        fixture.sources.donorGenerations.put("shared", 11L);
        fixture.sources.baseSfx = sfx(0xA0, (byte) 0xE9, (byte) 0xF2);
        fixture.sources.donorSfx = sfx(0xB0, (byte) 0xEA, (byte) 0xF2);
        fixture.sources.baseDac = new DacData(
                Collections.emptyMap(), Collections.emptyMap(), 301);
        DacData donorDac = new DacData(
                Collections.emptyMap(), Collections.emptyMap(), 302);
        fixture.sources.donorDacs.put("shared", donorDac);
        fixture.sources.baseConfig = new SmpsSequencerConfig.Builder()
                .tempoMode(SmpsSequencerConfig.TempoMode.TIMEOUT)
                .build();
        SmpsSequencerConfig donorConfig = new SmpsSequencerConfig.Builder()
                .tempoMode(SmpsSequencerConfig.TempoMode.OVERFLOW)
                .build();
        fixture.sources.donorConfigs.put("shared", donorConfig);
        fixture.sources.basePriority = 0x31;
        fixture.sources.donorPriorities.put("shared", 0x42);
        fixture.sources.baseSpecial = false;
        fixture.sources.donorSpecial.put("shared", true);
        fixture.sources.baseContinuous = true;
        fixture.sources.donorContinuous.put("shared", false);

        fixture.resolver.submit(new AudioCommand.PlaySfx(
                0xA0, null, AudioCommand.SfxRoute.BASE_SMPS_ID,
                1.0f, null));
        fixture.resolver.submit(new AudioCommand.PlaySfx(
                0xB0, null, AudioCommand.SfxRoute.DONOR_SMPS,
                1.0f, "shared"));

        List<AudioPresentationCommand> commands = drain(fixture.queue);
        AddSmpsSfx baseCommand = (AddSmpsSfx) commands.get(0);
        AddSmpsSfx donorCommand = (AddSmpsSfx) commands.get(1);
        assertEquals(3, baseCommand.source().dependencyGeneration());
        assertEquals(11, donorCommand.source().dependencyGeneration());
        assertEquals(0x31, baseCommand.source().priority());
        assertEquals(0x42, donorCommand.source().priority());
        assertEquals(0xA0, baseCommand.source().continuousSfxId());
        assertEquals(0, donorCommand.source().continuousSfxId());

        SmpsAssetCatalog.ProgramEntry baseEntry =
                fixture.factory.findRegisteredSmpsSfxAsset(
                        baseCommand.source().assetKey(), 3);
        SmpsAssetCatalog.ProgramEntry donorEntry =
                fixture.factory.findRegisteredSmpsSfxAsset(
                        donorCommand.source().assetKey(), 11);
        assertSame(fixture.sources.baseDac, baseEntry.dac());
        assertSame(donorDac, donorEntry.dac());
        assertEquals(SmpsSequencerConfig.TempoMode.TIMEOUT,
                baseEntry.staticConfig().getTempoMode());
        assertEquals(SmpsSequencerConfig.TempoMode.OVERFLOW,
                donorEntry.staticConfig().getTempoMode());
        assertFalse(baseEntry.specialSfx());
        assertTrue(donorEntry.specialSfx());
        assertNotEquals(baseEntry.sourceDescriptor().dataHash(),
                donorEntry.sourceDescriptor().dataHash(),
                "route-specific catalog entries must retain their program bytes");
    }

    @Test
    void baseResolutionRetainsOneSourceTupleAcrossReentrantLoaderMutation() {
        Fixture fixture = fixture();
        DacData oldDac = new DacData(
                Collections.emptyMap(), Collections.emptyMap(), 311);
        SmpsSequencerConfig oldConfig = new SmpsSequencerConfig.Builder()
                .tempoMode(SmpsSequencerConfig.TempoMode.TIMEOUT)
                .build();
        fixture.sources.baseGeneration = 4;
        fixture.sources.baseSfx = sfx(0xA4, (byte) 0x14, (byte) 0xF2);
        fixture.sources.baseDac = oldDac;
        fixture.sources.baseConfig = oldConfig;
        fixture.sources.basePriority = 0x34;
        fixture.sources.baseSpecial = true;
        fixture.sources.baseContinuous = true;
        fixture.sources.afterBaseSfxLoad = () -> {
            fixture.sources.baseGeneration = 5;
            fixture.sources.baseSfx = sfx(
                    0xA4, (byte) 0x25, (byte) 0xF2);
            fixture.sources.baseDac = new DacData(
                    Collections.emptyMap(), Collections.emptyMap(), 312);
            fixture.sources.baseConfig = new SmpsSequencerConfig.Builder()
                    .tempoMode(SmpsSequencerConfig.TempoMode.OVERFLOW)
                    .build();
            fixture.sources.basePriority = 0x45;
            fixture.sources.baseSpecial = false;
            fixture.sources.baseContinuous = false;
        };

        fixture.resolver.submit(new AudioCommand.PlaySfx(
                0xA4, null, AudioCommand.SfxRoute.BASE_SMPS_ID,
                1.0f, null));
        AddSmpsSfx oldCommand = assertInstanceOf(
                AddSmpsSfx.class, drain(fixture.queue).get(0));
        SmpsAssetCatalog.ProgramEntry oldEntry =
                fixture.factory.findRegisteredSmpsSfxAsset(
                        oldCommand.source().assetKey(), 4);

        fixture.resolver.submit(new AudioCommand.PlaySfx(
                0xA4, null, AudioCommand.SfxRoute.BASE_SMPS_ID,
                1.0f, null));
        AddSmpsSfx newCommand = assertInstanceOf(
                AddSmpsSfx.class, drain(fixture.queue).get(0));
        SmpsAssetCatalog.ProgramEntry newEntry =
                fixture.factory.findRegisteredSmpsSfxAsset(
                        newCommand.source().assetKey(), 5);

        assertEquals(4, oldCommand.source().dependencyGeneration());
        assertEquals(0x34, oldCommand.source().priority());
        assertEquals(0xA4, oldCommand.source().continuousSfxId());
        assertSame(oldDac, oldEntry.dac());
        assertEquals(SmpsSequencerConfig.TempoMode.TIMEOUT,
                oldEntry.staticConfig().getTempoMode());
        assertTrue(oldEntry.specialSfx());
        assertEquals(5, newCommand.source().dependencyGeneration());
        assertEquals(0x45, newCommand.source().priority());
        assertNotEquals(oldEntry.sourceDescriptor().dataHash(),
                newEntry.sourceDescriptor().dataHash(),
                "the captured old loader must register the old program");
    }

    @Test
    void donorResolutionRetainsOneSourceTupleAcrossReentrantReplacement() {
        Fixture fixture = fixture();
        DacData oldDac = new DacData(
                Collections.emptyMap(), Collections.emptyMap(), 321);
        SmpsSequencerConfig oldConfig = new SmpsSequencerConfig.Builder()
                .tempoMode(SmpsSequencerConfig.TempoMode.OVERFLOW2)
                .build();
        fixture.sources.donorGenerations.put("s2", 7L);
        fixture.sources.donorSfx = sfx(0xE0, (byte) 0x17, (byte) 0xF2);
        fixture.sources.donorDacs.put("s2", oldDac);
        fixture.sources.donorConfigs.put("s2", oldConfig);
        fixture.sources.donorPriorities.put("s2", 0x37);
        fixture.sources.donorSpecial.put("s2", true);
        fixture.sources.donorContinuous.put("s2", true);
        fixture.sources.afterDonorSfxLoad = () -> {
            fixture.sources.donorGenerations.put("s2", 8L);
            fixture.sources.donorSfx = sfx(
                    0xE0, (byte) 0x28, (byte) 0xF2);
            fixture.sources.donorDacs.put("s2", new DacData(
                    Collections.emptyMap(), Collections.emptyMap(), 322));
            fixture.sources.donorConfigs.put("s2",
                    new SmpsSequencerConfig.Builder()
                            .tempoMode(SmpsSequencerConfig.TempoMode.OVERFLOW)
                            .build());
            fixture.sources.donorPriorities.put("s2", 0x48);
            fixture.sources.donorSpecial.put("s2", false);
            fixture.sources.donorContinuous.put("s2", false);
        };

        fixture.resolver.submit(new AudioCommand.PlaySfx(
                0xE0, null, AudioCommand.SfxRoute.DONOR_SMPS,
                1.0f, "s2"));
        AddSmpsSfx oldCommand = assertInstanceOf(
                AddSmpsSfx.class, drain(fixture.queue).get(0));
        SmpsAssetCatalog.ProgramEntry oldEntry =
                fixture.factory.findRegisteredSmpsSfxAsset(
                        oldCommand.source().assetKey(), 7);

        fixture.resolver.submit(new AudioCommand.PlaySfx(
                0xE0, null, AudioCommand.SfxRoute.DONOR_SMPS,
                1.0f, "s2"));
        AddSmpsSfx newCommand = assertInstanceOf(
                AddSmpsSfx.class, drain(fixture.queue).get(0));
        SmpsAssetCatalog.ProgramEntry newEntry =
                fixture.factory.findRegisteredSmpsSfxAsset(
                        newCommand.source().assetKey(), 8);

        assertEquals(7, oldCommand.source().dependencyGeneration());
        assertEquals(0x37, oldCommand.source().priority());
        assertEquals(0xE0, oldCommand.source().continuousSfxId());
        assertSame(oldDac, oldEntry.dac());
        assertEquals(SmpsSequencerConfig.TempoMode.OVERFLOW2,
                oldEntry.staticConfig().getTempoMode());
        assertTrue(oldEntry.specialSfx());
        assertEquals(8, newCommand.source().dependencyGeneration());
        assertEquals(0x48, newCommand.source().priority());
        assertNotEquals(oldEntry.sourceDescriptor().dataHash(),
                newEntry.sourceDescriptor().dataHash(),
                "the captured old donor loader must register the old program");
    }

    @Test
    void queuedGenerationIsRejectedWhenOnlyAnotherGenerationIsRegistered() {
        Fixture fixture = fixture();
        SmpsAssetKey key = new SmpsAssetKey(
                "base", SmpsAssetKey.Route.BASE_ID, 0xA0, null);
        fixture.factory.registerSmpsSfxAsset(
                key, 4, sfx(0xA0, (byte) 0xF2), EMPTY_DAC,
                fixture.sources.baseConfig, false);
        ResolvedSmpsSfxSource stale = fixture.factory.resolveSmpsSfx(
                1, key, 5, 1 << 16, 0x70, 0, 1, 64);
        AudioVoiceRegistry registry = fixture.registry();

        registry.apply(new AddSmpsSfx(stale));

        assertEquals(0, registry.orderedVoiceCount());
        assertEquals(0, fixture.warnings.size());
    }

    @Test
    void musicOwnedSmpsSfxAttachesOnlyWhenRegistryAppliesCommand() {
        Fixture fixture = fixture();
        fixture.sources.baseMusic = music(0x81);
        fixture.sources.baseSfx = sfx(0xA0, (byte) 0xF2);
        fixture.resolver.submit(new AudioCommand.PlayMusic(
                0x81, AudioCommand.MusicRoute.BASE_SMPS, false, null));
        fixture.resolver.submit(new AudioCommand.PlaySfx(
                0xA0, null, AudioCommand.SfxRoute.BASE_SMPS_ID, 1.0f, null));
        List<AudioPresentationCommand> commands = drain(fixture.queue);

        AudioVoiceRegistry registry = fixture.registry();
        apply(fixture, registry, commands.get(0));
        assertEquals(1, fixture.session.captureLogicalSnapshot()
                .sequencers().size());

        apply(fixture, registry, commands.get(1));

        assertEquals(2, fixture.session.captureLogicalSnapshot()
                .sequencers().size());
    }

    @Test
    void noMusicSmpsSfxUsesTheSessionDriverWithoutAnOrderedCarrier() {
        Fixture fixture = fixture();
        fixture.sources.baseSfx = sfx(0xA0, (byte) 0xF2);
        fixture.resolver.submit(new AudioCommand.PlaySfx(
                0xA0, null, AudioCommand.SfxRoute.BASE_SMPS_ID, 1.0f, null));
        fixture.resolver.submit(new AudioCommand.PlaySfx(
                0xA0, null, AudioCommand.SfxRoute.BASE_SMPS_ID, 1.0f, null));

        AudioVoiceRegistry registry = fixture.registry();
        drain(fixture.queue).forEach(command ->
                apply(fixture, registry, command));

        assertEquals(0, registry.orderedVoiceCount());
        assertEquals(1, fixture.session.captureLogicalSnapshot()
                .sequencers().size());
    }

    @Test
    void resolvesFadeStopRestoreTempoSpeedAndOverrideCommands() {
        Fixture fixture = fixture();
        fixture.resolver.submit(new AudioCommand.FadeOutMusic(7, 3));
        fixture.resolver.submit(new AudioCommand.StopMusic());
        fixture.resolver.submit(new AudioCommand.StopAllSfx());
        fixture.resolver.submit(new AudioCommand.RestoreMusic(
                AudioCommand.RestoreCause.EXPLICIT));
        fixture.resolver.submit(new AudioCommand.SetSpeedShoes(true));
        fixture.resolver.submit(new AudioCommand.SetSpeedMultiplier(8));
        fixture.resolver.submit(new AudioCommand.ChangeMusicTempo(5));
        fixture.resolver.submit(new AudioCommand.SilencePsg(0xE3));

        List<AudioPresentationCommand> commands = drain(fixture.queue);
        assertEquals(List.of(
                        AudioPresentationCommand.FadeMusic.class,
                        AudioPresentationCommand.StopMusic.class,
                        AudioPresentationCommand.StopAllSfx.class,
                        AudioPresentationCommand.RestoreMusicOverride.class,
                        AudioPresentationCommand.SetSpeedShoes.class,
                        AudioPresentationCommand.SetSpeedMultiplier.class,
                        AudioPresentationCommand.ChangeMusicTempo.class,
                        AudioPresentationCommand.SilencePsg.class),
                commands.stream().map(Object::getClass).toList());
    }

    @Test
    void resolvesEndOverrideTempoAndRingResetToExplicitImmutableRecords() {
        Fixture fixture = fixture();
        fixture.resolver.submit(new AudioCommand.EndMusicOverride(0x91));
        fixture.resolver.submit(new AudioCommand.ChangeMusicTempo(3));
        fixture.resolver.submit(new AudioCommand.ResetRingAlternation(false));

        List<AudioPresentationCommand> commands = drain(fixture.queue);
        assertEquals(new AudioPresentationCommand.EndMusicOverride(0x91),
                commands.get(0));
        assertEquals(new AudioPresentationCommand.ChangeMusicTempo(3),
                commands.get(1));
        assertEquals(new AudioPresentationCommand.ResetRingAlternation(false),
                commands.get(2));
    }

    @Test
    void resolvesRawSegaPcmReplaceAndStop() {
        Fixture fixture = fixture();
        byte[] pcm = {0, (byte) 0x80, (byte) 0xFF};

        fixture.resolver.submitRawPcm(pcm, 16_500);
        fixture.resolver.stopRawPcm();

        List<AudioPresentationCommand> commands = drain(fixture.queue);
        ReplaceRawPcm replace = assertInstanceOf(
                ReplaceRawPcm.class, commands.get(0));
        assertTrue(replace.voice().snapshot().assetId().startsWith("sega-pcm:"));
        assertInstanceOf(AudioPresentationCommand.StopRawPcm.class,
                commands.get(1));
        assertNotNull(fixture.factory.resolvePcm(
                replace.voice().snapshot().assetId()));
    }

    @Test
    void rawPcmIdentityDoesNotAliasArraysWithTheSameArraysHashCode() {
        Fixture fixture = fixture();

        fixture.resolver.submitRawPcm(new byte[] {0, 31}, 16_500);
        fixture.resolver.submitRawPcm(new byte[] {1, 0}, 16_500);

        List<AudioPresentationCommand> commands = drain(fixture.queue);
        String first = ((ReplaceRawPcm) commands.get(0))
                .voice().snapshot().assetId();
        String second = ((ReplaceRawPcm) commands.get(1))
                .voice().snapshot().assetId();
        assertNotSame(first, second);
        assertFalse(first.equals(second));
    }

    @Test
    void systemMusicCommandIsExplicitlyRejectedWithoutQueueMutation() {
        Fixture fixture = fixture();

        fixture.resolver.submit(new AudioCommand.PlayMusic(
                0xFE, AudioCommand.MusicRoute.SYSTEM_COMMAND, false, null));

        assertEquals(0, fixture.queue.size());
        assertEquals(1, fixture.warnings.size());
        assertTrue(fixture.warnings.get(0).contains("system music command"));
    }

    @Test
    void structuralOverflowDrainsAtInjectedOwnerBoundary() {
        Fixture fixture = fixture();

        for (int index = 0;
             index < AudioPresentationCommandQueue.CAPACITY + 17;
             index++) {
            fixture.resolver.submit(
                    new AudioCommand.EndMusicOverride(index));
        }

        assertEquals(AudioPresentationCommandQueue.CAPACITY,
                fixture.synchronouslyApplied.size());
        assertEquals(17, fixture.queue.size());
        assertEquals(new AudioPresentationCommand.EndMusicOverride(0),
                fixture.synchronouslyApplied.get(0));
    }

    @Test
    void queueCapacityFailureIsNotMisreportedAsAssetResolutionFailure() {
        Fixture fixture = fixture();
        fixture.sources.baseMusic = music(0x81);
        for (int index = 0;
             index < AudioPresentationCommandQueue.CAPACITY;
             index++) {
            fixture.resolver.submit(
                    new AudioCommand.EndMusicOverride(index));
        }
        fixture.ownerThreadBoundary.set(false);

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> fixture.resolver.submit(new AudioCommand.PlayMusic(
                        0x81, AudioCommand.MusicRoute.BASE_SMPS,
                        false, null)));

        assertTrue(failure.getMessage().contains("owner boundary"));
        assertTrue(fixture.warnings.isEmpty());
    }

    @Test
    void malformedFallbackAssetWarnsAndRejectsOnlyThatVoice() {
        Fixture fixture = fixture();
        fixture.assets.malformed.add("sfx/skid.wav");

        fixture.resolver.submit(new AudioCommand.PlaySfx(
                -1, "SKID", AudioCommand.SfxRoute.FALLBACK_NAME, 1.0f, null));
        fixture.resolver.submit(new AudioCommand.PlaySfx(
                -1, "JUMP", AudioCommand.SfxRoute.FALLBACK_NAME, 1.0f, null));

        List<AudioPresentationCommand> commands = drain(fixture.queue);
        assertEquals(1, commands.size());
        assertInstanceOf(StartSampleSfx.class, commands.get(0));
        assertEquals(1, fixture.warnings.size());
    }

    @Test
    void resolvedCommandsNeverContainConsumerRunnableOrCallbackFields() {
        for (Class<?> commandType :
                AudioPresentationCommand.class.getPermittedSubclasses()) {
            for (Field field : commandType.getDeclaredFields()) {
                assertFalse(Runnable.class.isAssignableFrom(field.getType()));
                assertFalse(Consumer.class.isAssignableFrom(field.getType()));
                assertFalse(java.util.function.Function.class
                        .isAssignableFrom(field.getType()));
            }
        }
    }

    @Test
    void resolvingSmpsSfxDoesNotMutateTheOwnerDriverBeforeQueueApply() {
        Fixture fixture = fixture();
        fixture.sources.baseMusic = music(0x81);
        fixture.sources.baseSfx = sfx(0xA0, (byte) 0xF2);
        fixture.resolver.submit(new AudioCommand.PlayMusic(
                0x81, AudioCommand.MusicRoute.BASE_SMPS, false, null));
        AudioVoiceRegistry registry = fixture.registry();
        apply(fixture, registry, drain(fixture.queue).get(0));
        SmpsDriverSnapshot before = fixture.session.captureLogicalSnapshot();

        fixture.resolver.submit(new AudioCommand.PlaySfx(
                0xA0, null, AudioCommand.SfxRoute.BASE_SMPS_ID, 1.0f, null));

        assertEquals(1, before.sequencers().size());
        SmpsDriverSnapshot after = fixture.session.captureLogicalSnapshot();
        assertEquals(before.sequencers().size(), after.sequencers().size());
        assertEquals(before.sequencers().get(0).source(),
                after.sequencers().get(0).source());
    }

    @Test
    void queuedSfxContainsOnlyAssetKeyAndPrimitiveMetadata() {
        for (Field field : ResolvedSmpsSfxSource.class.getDeclaredFields()) {
            assertTrue(field.getType().isPrimitive()
                            || field.getType() == SmpsAssetKey.class,
                    field.toString());
            assertTrue(Modifier.isPrivate(field.getModifiers()));
            assertTrue(Modifier.isFinal(field.getModifiers()));
        }
    }

    @Test
    void mutatingOriginalLoadedObjectsAfterRegistrationAndQueueDoesNotChangeAppliedSfx() {
        Fixture fixture = fixture();
        MutableSfxData original = sfx(0xA0, (byte) 0xF2);
        HashSet<Integer> mutableEndFlags = new HashSet<>(List.of(0xEE));
        fixture.sources.baseConfig = new SmpsSequencerConfig.Builder()
                .extraTrkEndFlags(mutableEndFlags)
                .fmSfxTakeoverMode(
                        SmpsSequencerConfig.FmSfxTakeoverMode.REGISTER_SEQUENCE)
                .build();
        byte[] mutableDacBytes = { 0x12 };
        Map<Integer, byte[]> mutableDacSamples = new HashMap<>();
        mutableDacSamples.put(1, mutableDacBytes);
        DacData immutableDac = new DacData(
                mutableDacSamples,
                Map.of(0x81, new DacData.DacEntry(1, 4)),
                297);
        fixture.sources.baseDac = immutableDac;
        fixture.sources.baseSfx = original;
        fixture.resolver.submit(new AudioCommand.PlaySfx(
                0xA0, null, AudioCommand.SfxRoute.BASE_SMPS_ID, 1.0f, null));
        AudioPresentationCommand command = drain(fixture.queue).get(0);

        original.getData()[0x40] = (byte) 0xE9;
        mutableEndFlags.clear();
        mutableDacBytes[0] = 0x55;
        mutableDacSamples.clear();

        AudioVoiceRegistry registry = fixture.registry();
        assertDoesNotThrow(() -> apply(fixture, registry, command));
        SmpsDriverSnapshot snapshot =
                fixture.session.captureLogicalSnapshot();
        assertEquals((byte) 0xF2,
                snapshot.sequencers().get(0).smpsData().getData()[0x40]);
        assertNotSame(original, snapshot.sequencers().get(0).smpsData());
        assertSame(immutableDac, snapshot.sequencers().get(0).dacData());
        assertEquals((byte) 0x12, snapshot.sequencers().get(0)
                .dacData().sample(1).byteAt(0));
        assertEquals(Set.of(0xEE), snapshot.sequencers().get(0)
                .config().getExtraTrkEndFlags());
        assertEquals(SmpsSequencerConfig.FmSfxTakeoverMode.REGISTER_SEQUENCE,
                snapshot.sequencers().get(0).config().getFmSfxTakeoverMode());
    }

    @Test
    void reconstructedConfigsShareTheSessionCoordFlagHandlerOwner() {
        Fixture fixture = fixtureWithS3kOwner();
        MutableSfxData first = sfx(0xA0, (byte) 0xF2);
        MutableSfxData second = sfx(0xA1, (byte) 0xF2);
        fixture.factory.registerSmpsSfxAsset(
                new SmpsAssetKey("s3k", SmpsAssetKey.Route.BASE_ID,
                        0xA0, null),
                first, EMPTY_DAC, s3kConfig(new ArbitraryHandler()));
        fixture.factory.registerSmpsSfxAsset(
                new SmpsAssetKey("s3k", SmpsAssetKey.Route.BASE_ID,
                        0xA1, null),
                second, EMPTY_DAC, s3kConfig(new ArbitraryHandler()));
        SmpsDriver owner = new SmpsDriver(48_000);

        SmpsSequencer firstSeq = fixture.factory.instantiateCached(
                fixture.factory.resolveSmpsSfx(1,
                        new SmpsAssetKey("s3k", SmpsAssetKey.Route.BASE_ID,
                                0xA0, null),
                        1 << 16, 0x70, 0, 1, 64), owner);
        SmpsSequencer secondSeq = fixture.factory.instantiateCached(
                fixture.factory.resolveSmpsSfx(2,
                        new SmpsAssetKey("s3k", SmpsAssetKey.Route.BASE_ID,
                                0xA1, null),
                        1 << 16, 0x70, 0, 1, 64), owner);

        assertSame(firstSeq.getConfig().getCoordFlagHandler(),
                secondSeq.getConfig().getCoordFlagHandler());
        assertSame(fixture.handlers.handlerFor("s3k"),
                firstSeq.getConfig().getCoordFlagHandler());
    }

    @Test
    void offOwnerThreadInstantiationIsRejectedBeforeCacheLookup() throws Exception {
        Fixture fixture = fixture();
        AtomicBoolean owner = fixture.ownerThreadBoundary;
        owner.set(false);
        AtomicInteger assetLookup = fixture.factory.cacheLookupCountForTesting();
        int before = assetLookup.get();
        ResolvedSmpsSfxSource missing = fixture.factory.resolveSmpsSfx(
                1, new SmpsAssetKey("base", SmpsAssetKey.Route.BASE_ID,
                        0xEE, null),
                1 << 16, 0x70, 0, 1, 64);

        assertThrows(IllegalStateException.class,
                () -> fixture.factory.instantiateCached(
                        missing, new SmpsDriver(48_000)));
        assertEquals(before, assetLookup.get());
    }

    @Test
    void cacheMissAtApplyRejectsWithoutLoaderRomDecodeOrFallbackCalls() {
        Fixture fixture = fixture();
        AtomicInteger calls = fixture.sources.calls;
        ResolvedSmpsSfxSource missing = fixture.factory.resolveSmpsSfx(
                99, new SmpsAssetKey("base", SmpsAssetKey.Route.BASE_ID,
                        0xEE, null),
                1 << 16, 0x70, 0, 1, 64);
        AudioVoiceRegistry registry = fixture.registry();
        int before = calls.get() + fixture.assets.opens.get();

        registry.apply(new AddSmpsSfx(missing));

        assertEquals(0, registry.orderedVoiceCount());
        assertEquals(before, calls.get() + fixture.assets.opens.get());
        assertEquals(0, fixture.warnings.size());
    }

    @Test
    void replacementAndOverrideCommandsBeforeSfxSelectTheFinalCurrentDriver() {
        Fixture fixture = fixture();
        fixture.sources.baseMusic = music(0x81);
        fixture.sources.donorMusic = music(0x91);
        fixture.sources.baseSfx = sfx(0xA0, (byte) 0xF2);
        fixture.resolver.submit(new AudioCommand.PlayMusic(
                0x81, AudioCommand.MusicRoute.BASE_SMPS, false, null));
        fixture.resolver.submit(new AudioCommand.PlayMusic(
                0x91, AudioCommand.MusicRoute.DONOR_SMPS, true, "s3k"));
        fixture.resolver.submit(new AudioCommand.PlaySfx(
                0xA0, null, AudioCommand.SfxRoute.BASE_SMPS_ID, 1.0f, null));
        AudioVoiceRegistry registry = fixture.registry();

        drain(fixture.queue).forEach(command ->
                apply(fixture, registry, command));

        assertEquals(2, fixture.session.captureLogicalSnapshot()
                .sequencers().size());
    }

    @Test
    void overlappingNoMusicSfxUseTheSessionDriverAndArbitrate() {
        Fixture fixture = fixture();
        fixture.sources.baseSfx = sfx(0xA0, (byte) 0xF2);
        fixture.sources.namedSfx = sfx(0xA1, (byte) 0xF2);
        fixture.resolver.submit(new AudioCommand.PlaySfx(
                0xA0, null, AudioCommand.SfxRoute.BASE_SMPS_ID, 1.0f, null));
        fixture.resolver.submit(new AudioCommand.PlaySfx(
                -1, "JUMP", AudioCommand.SfxRoute.BASE_SMPS_NAME, 1.0f, null));
        AudioVoiceRegistry registry = fixture.registry();

        drain(fixture.queue).forEach(command ->
                apply(fixture, registry, command));

        assertEquals(0, registry.orderedVoiceCount());
        assertEquals(1, fixture.session.captureLogicalSnapshot()
                .sequencers().size());
    }

    @Test
    void continuousRetriggerExtendsMusicAndStandaloneWithoutDuplicateSequencer() {
        for (boolean withMusic : List.of(false, true)) {
            Fixture fixture = fixture();
            fixture.sources.baseContinuous = true;
            fixture.sources.baseSfx = sfx(0xBC, (byte) 0xFC, (byte) 0x40, (byte) 0,
                    (byte) 0xF2);
            if (withMusic) {
                fixture.sources.baseMusic = music(0x81);
                fixture.resolver.submit(new AudioCommand.PlayMusic(
                        0x81, AudioCommand.MusicRoute.BASE_SMPS, false, null));
            }
            fixture.resolver.submit(new AudioCommand.PlaySfx(
                    0xBC, null, AudioCommand.SfxRoute.BASE_SMPS_ID, 1.0f, null));
            fixture.resolver.submit(new AudioCommand.PlaySfx(
                    0xBC, null, AudioCommand.SfxRoute.BASE_SMPS_ID, 1.0f, null));
            AudioVoiceRegistry registry = fixture.registry();

            drain(fixture.queue).forEach(command ->
                    apply(fixture, registry, command));

            SmpsDriverSnapshot snapshot =
                    fixture.session.captureLogicalSnapshot();
            assertEquals(withMusic ? 2 : 1,
                    snapshot.sequencers().size());
            assertTrue(snapshot.continuousSfxFlag());
        }
    }

    @Test
    void musicMutationAndResetThenSfxObservesTheSamePresentationCounter() {
        Fixture fixture = fixtureWithS3kOwner();
        fixture.sources.baseConfig = s3kConfig(new ArbitraryHandler());
        fixture.sources.baseMusic = musicWithTrack(0x81,
                (byte) 0xE9, (byte) 0xE9, (byte) 0xF2);
        fixture.sources.baseSfx = sfx(0xA0, (byte) 0xE9, (byte) 0xF2);
        fixture.resolver.submit(new AudioCommand.PlayMusic(
                0x81, AudioCommand.MusicRoute.BASE_SMPS, false, null));
        fixture.resolver.submit(new AudioCommand.PlaySfx(
                0xA0, null, AudioCommand.SfxRoute.BASE_SMPS_ID, 1.0f, null));
        AudioVoiceRegistry registry = fixture.registry();
        drain(fixture.queue).forEach(command ->
                apply(fixture, registry, command));

        mix(fixture, registry, 1024);

        assertTrue(fixture.handlers.state().spindashRevCounter() > 0);
    }

    @Test
    void sfxMutationThenMusicObservesTheSamePresentationCounter() {
        Fixture fixture = fixtureWithS3kOwner();
        fixture.sources.baseConfig = s3kConfig(new ArbitraryHandler());
        fixture.sources.baseSfx = sfx(0xA0,
                (byte) 0xE9, (byte) 0xE9, (byte) 0xF2);
        fixture.resolver.submit(new AudioCommand.PlaySfx(
                0xA0, null, AudioCommand.SfxRoute.BASE_SMPS_ID, 1.0f, null));
        AudioVoiceRegistry registry = fixture.registry();
        drain(fixture.queue).forEach(command ->
                apply(fixture, registry, command));
        mix(fixture, registry, 1024);
        int afterSfx = fixture.handlers.state().spindashRevCounter();
        fixture.sources.baseMusic = musicWithTrack(0x81,
                (byte) 0xE9, (byte) 0xF2);
        fixture.resolver.submit(new AudioCommand.PlayMusic(
                0x81, AudioCommand.MusicRoute.BASE_SMPS, false, null));
        drain(fixture.queue).forEach(command ->
                apply(fixture, registry, command));

        mix(fixture, registry, 1024);

        assertTrue(fixture.handlers.state().spindashRevCounter() >= afterSfx);
    }

    @Test
    void arbitraryHandlerEmbeddedInProfileConfigIsNeverUsedForPresentation() {
        Fixture fixture = fixtureWithS3kOwner();
        ArbitraryHandler arbitrary = new ArbitraryHandler();
        fixture.sources.baseConfig = s3kConfig(arbitrary);
        fixture.sources.baseMusic = music(0x81);

        fixture.resolver.submit(new AudioCommand.PlayMusic(
                0x81, AudioCommand.MusicRoute.BASE_SMPS, false, null));
        ReplaceMusic command =
                (ReplaceMusic) drain(fixture.queue).get(0);
        var activation = ((AudioPresentationCommand.SmpsVoiceDescriptor)
                command.music().voiceDescriptor()).activation();
        CoordFlagHandler actual = activation.incomingMusic()
                .config().getCoordFlagHandler();
        assertNotSame(arbitrary, actual);
        assertSame(fixture.handlers.handlerFor("s3k"), actual);
    }

    private static Fixture fixture() {
        return fixture(new SmpsCoordFlagHandlerOwner(
                new SmpsCoordFlagRuntimeState()), "base");
    }

    private static Fixture fixtureWithS3kOwner() {
        SmpsCoordFlagHandlerOwner handlers = new SmpsCoordFlagHandlerOwner(
                new SmpsCoordFlagRuntimeState());
        handlers.register("s3k", Sonic3kCoordFlagHandler::new);
        return fixture(handlers, "s3k");
    }

    private static Fixture fixture(
            SmpsCoordFlagHandlerOwner handlers, String baseGameId) {
        AtomicBoolean owner = new AtomicBoolean(true);
        FakeAssets assets = new FakeAssets();
        DecodedPcmCache pcm = new DecodedPcmCache();
        AudioPresentationSourceFactory.Settings settings =
                new AudioPresentationSourceFactory.Settings(
                        48_000, SmpsSequencer.Region.NTSC,
                        false, false, false, 1,
                        AudioManager.getInstance(), pcm, assets);
        SmpsDriverSession session = SmpsSessionTestSupport.installed(48_000);
        AudioPresentationSourceFactory factory =
                new AudioPresentationSourceFactory(owner::get, handlers,
                        settings, session);
        FakeSources sources = new FakeSources(baseGameId);
        AudioPresentationCommandQueue queue =
                new AudioPresentationCommandQueue();
        List<String> warnings = new ArrayList<>();
        List<AudioPresentationCommand> synchronouslyApplied =
                new ArrayList<>();
        AudioPresentationCommandResolver resolver =
                new AudioPresentationCommandResolver(
                        queue, factory, sources, warnings::add,
                        owner::get, synchronouslyApplied::add);
        AudioVoiceRegistry registry = new AudioVoiceRegistry();
        int maxFrames = (48_000 + 60 - 1) / 60;
        AudioPresentationProducer producer = new AudioPresentationProducer(
                48_000, 60, 48_000, 1, registry,
                new AudioPresentationCommandQueue(registry::isRendering),
                new AudioPresentationMixer(maxFrames,
                        registry::onVoiceFailure),
                new com.openggf.audio.output.NoDeviceAudioSink(48_000),
                synchronouslyApplied::add);
        resolver.bindForwardExecutor(producer::applyResolvedForwardCommand);
        return new Fixture(queue, factory, resolver, sources, assets, handlers,
                session,
                warnings, owner, synchronouslyApplied);
    }

    private record Fixture(
            AudioPresentationCommandQueue queue,
            AudioPresentationSourceFactory factory,
            AudioPresentationCommandResolver resolver,
            FakeSources sources,
            FakeAssets assets,
            SmpsCoordFlagHandlerOwner handlers,
            SmpsDriverSession session,
            List<String> warnings,
            AtomicBoolean ownerThreadBoundary,
            List<AudioPresentationCommand> synchronouslyApplied) {
        AudioVoiceRegistry registry() {
            return new AudioVoiceRegistry(
                    factory, factory, handlers, warnings::add, session);
        }
    }

    private static final class FakeSources
            implements AudioPresentationCommandResolver.Sources {
        final AtomicInteger calls = new AtomicInteger();
        final String baseGameId;
        AbstractSmpsData baseMusic;
        AbstractSmpsData donorMusic;
        AbstractSmpsData baseSfx;
        AbstractSmpsData namedSfx;
        AbstractSmpsData donorSfx;
        Supplier<AbstractSmpsData> baseSfxFactory;
        Supplier<AbstractSmpsData> namedSfxFactory;
        Supplier<AbstractSmpsData> donorSfxFactory;
        DacData baseDac = EMPTY_DAC;
        SmpsSequencerConfig baseConfig =
                new SmpsSequencerConfig.Builder().build();
        long baseGeneration;
        final Map<String, Long> donorGenerations = new HashMap<>();
        final Map<String, DacData> donorDacs = new HashMap<>();
        final Map<String, SmpsSequencerConfig> donorConfigs = new HashMap<>();
        final Map<String, Integer> donorPriorities = new HashMap<>();
        final Map<String, Boolean> donorSpecial = new HashMap<>();
        final Map<String, Boolean> donorContinuous = new HashMap<>();
        int basePriority = 0x70;
        boolean baseSpecial;
        boolean baseContinuous;
        boolean throwMusic;
        SmpsLoadReadiness musicReadiness;
        boolean throwSfx;
        Runnable afterBaseSfxLoad;
        Runnable afterDonorSfxLoad;

        FakeSources(String baseGameId) {
            this.baseGameId = baseGameId;
        }

        @Override
        public AudioPresentationCommandResolver.SourceAccess sourceFor(
                SmpsAssetKey.Route route, String donorGameId) {
            boolean donor = route == SmpsAssetKey.Route.DONOR_MUSIC
                    || route == SmpsAssetKey.Route.DONOR_ID;
            String gameId = donor ? donorGameId : baseGameId;
            long generation = donor
                    ? donorGenerations.getOrDefault(gameId, 0L)
                    : baseGeneration;
            DacData dac = donor
                    ? donorDacs.getOrDefault(gameId, baseDac) : baseDac;
            SmpsSequencerConfig config = donor
                    ? donorConfigs.getOrDefault(gameId, baseConfig)
                    : baseConfig;
            int priority = donor
                    ? donorPriorities.getOrDefault(gameId, 0x70)
                    : basePriority;
            boolean special = donor
                    ? donorSpecial.getOrDefault(gameId, false) : baseSpecial;
            boolean continuous = donor
                    ? donorContinuous.getOrDefault(gameId, false)
                    : baseContinuous;
            AbstractSmpsData capturedMusic = donor
                    ? donorMusic : baseMusic;
            AbstractSmpsData capturedSfx = donor
                    ? donorSfx : baseSfx;
            AbstractSmpsData capturedNamedSfx = namedSfx;
            Supplier<AbstractSmpsData> capturedSfxFactory = donor
                    ? donorSfxFactory : baseSfxFactory;
            Supplier<AbstractSmpsData> capturedNamedSfxFactory =
                    namedSfxFactory;
            Runnable capturedSfxCallback = donor
                    ? afterDonorSfxLoad : afterBaseSfxLoad;
            if (donor) {
                afterDonorSfxLoad = null;
            } else {
                afterBaseSfxLoad = null;
            }
            com.openggf.audio.smps.SmpsLoader loader =
                    new com.openggf.audio.smps.SmpsLoader() {
                        @Override
                        public AbstractSmpsData loadMusic(int musicId) {
                            calls.incrementAndGet();
                            if (throwMusic) {
                                throw new IllegalStateException(
                                        "seeded music source failure");
                            }
                            return capturedMusic;
                        }

                        @Override
                        public LoadedSmpsMusic loadMusicWithReadiness(
                                int musicId) {
                            AbstractSmpsData music = loadMusic(musicId);
                            return music == null ? null : new LoadedSmpsMusic(
                                    music, musicReadiness == null
                                            ? SmpsLoadReadiness.immediatePlan()
                                            : musicReadiness);
                        }

                        @Override
                        public AbstractSmpsData loadSfx(int sfxId) {
                            calls.incrementAndGet();
                            if (throwSfx) {
                                throw new IllegalStateException(
                                        "seeded SFX source failure");
                            }
                            if (capturedSfxCallback != null) {
                                capturedSfxCallback.run();
                            }
                            return capturedSfxFactory != null
                                    ? capturedSfxFactory.get() : capturedSfx;
                        }

                        @Override
                        public AbstractSmpsData loadSfx(String name) {
                            calls.incrementAndGet();
                            return capturedNamedSfxFactory != null
                                    ? capturedNamedSfxFactory.get()
                                    : capturedNamedSfx;
                        }

                        @Override public DacData loadDacData() { return dac; }
                    };
            AudioPresentationCommandResolver.SfxPolicy policy =
                    new AudioPresentationCommandResolver.SfxPolicy() {
                        @Override public int priority(int sfxId) {
                            return priority;
                        }

                        @Override public boolean special(int sfxId) {
                            return special;
                        }

                        @Override public boolean continuous(int sfxId) {
                            return continuous;
                        }
                    };
            return new AudioPresentationCommandResolver.SourceAccess(
                    gameId, generation, loader, dac, config, policy);
        }

        @Override
        public int maxStereoFrames() {
            return 2_048;
        }
    }

    private static final class FakeAssets
            implements AudioPresentationSourceFactory.WavAssets {
        final AtomicInteger opens = new AtomicInteger();
        final List<String> malformed = new ArrayList<>();
        final List<String> throwing = new ArrayList<>();

        @Override
        public InputStream open(String assetId) {
            opens.incrementAndGet();
            if (throwing.contains(assetId)) {
                throw new IllegalStateException(
                        "seeded fallback source failure");
            }
            if (malformed.contains(assetId)) {
                return new ByteArrayInputStream(new byte[] {1, 2, 3});
            }
            return new ByteArrayInputStream(wav(
                    new short[] {100, 200, 300, 400}, 1, 48_000));
        }
    }

    private static final class ArbitraryHandler implements CoordFlagHandler {
        @Override
        public boolean handleFlag(
                CoordFlagContext ctx, SmpsSequencer.Track track, int command) {
            return false;
        }

        @Override
        public int flagParamLength(int command) {
            return -1;
        }
    }

    private static MutableMusicData music(int id) {
        return musicWithTrack(id, (byte) 0xF2);
    }

    private static MutableMusicData musicWithTrack(int id, byte... track) {
        byte[] data = new byte[0x100];
        data[2] = 1;
        data[4] = 1;
        data[5] = (byte) 0x80;
        data[6] = 0x40;
        System.arraycopy(track, 0, data, 0x40, track.length);
        MutableMusicData result = new MutableMusicData(data);
        result.setId(id);
        return result;
    }

    private static MutableSfxData sfx(int id, byte... track) {
        return sfx(id, MutableSfxData::new, track);
    }

    private static MutableSfxData countingSfx(
            int id, AtomicInteger materializations, byte... track) {
        return sfx(id,
                data -> new CountingSfxData(data, materializations), track);
    }

    private static MutableSfxData sfx(
            int id,
            java.util.function.Function<byte[], MutableSfxData> factory,
            byte... track) {
        byte[] data = new byte[0x100];
        data[2] = 1;
        data[3] = 1;
        data[4] = (byte) 0x80;
        data[5] = 2;
        data[6] = 0x40;
        System.arraycopy(track, 0, data, 0x40, track.length);
        MutableSfxData result = factory.apply(data);
        result.setId(id);
        return result;
    }

    private static SmpsSequencerConfig s3kConfig(CoordFlagHandler handler) {
        return new SmpsSequencerConfig.Builder()
                .tempoMode(SmpsSequencerConfig.TempoMode.OVERFLOW)
                .volMode(SmpsSequencerConfig.VolMode.BIT7)
                .psgEnvCmd80(SmpsSequencerConfig.PsgEnvCmd80.RESET)
                .noteOnPrevent(SmpsSequencerConfig.NoteOnPrevent.HOLD)
                .delayFreq(SmpsSequencerConfig.DelayFreq.KEEP)
                .coordFlagHandler(handler)
                .modAlgo(SmpsSequencerConfig.ModAlgo.MOD_Z80)
                .build();
    }

    private static void apply(
            Fixture fixture,
            AudioVoiceRegistry registry,
            AudioPresentationCommand command) {
        AudioPresentationSessionCommandApplier.apply(
                fixture.session, registry, command);
    }

    private static void mix(
            Fixture fixture, AudioVoiceRegistry registry, int frames) {
        registry.beginRendering();
        try {
            registry.serviceOuterFrame();
            fixture.session.serviceForward();
            short[] smpsPcm = new short[frames * 2];
            fixture.session.renderFrames(smpsPcm, 0, frames);
            new AudioPresentationMixer(frames).mixPcmVoices(
                    registry, frames, smpsPcm, 0);
        } finally {
            registry.endRendering();
        }
    }

    private static List<AudioPresentationCommand> drain(
            AudioPresentationCommandQueue queue) {
        List<AudioPresentationCommand> commands = new ArrayList<>();
        queue.applyPending(commands::add);
        return commands;
    }

    private static byte[] wav(
            short[] samples, int channels, int sampleRate) {
        int dataBytes = samples.length * Short.BYTES;
        ByteBuffer out = ByteBuffer.allocate(44 + dataBytes)
                .order(ByteOrder.LITTLE_ENDIAN);
        out.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        out.putInt(36 + dataBytes);
        out.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII));
        out.putInt(16);
        out.putShort((short) 1);
        out.putShort((short) channels);
        out.putInt(sampleRate);
        out.putInt(sampleRate * channels * Short.BYTES);
        out.putShort((short) (channels * Short.BYTES));
        out.putShort((short) 16);
        out.put("data".getBytes(StandardCharsets.US_ASCII));
        out.putInt(dataBytes);
        for (short sample : samples) {
            out.putShort(sample);
        }
        return out.array();
    }

    private static class MutableMusicData extends AbstractSmpsData {
        MutableMusicData(byte[] data) {
            super(data, 0);
        }

        @Override
        protected void parseHeader() {
            voicePtr = 0;
            channels = data[2] & 0xFF;
            psgChannels = data[3] & 0xFF;
            dividingTiming = Math.max(1, data[4] & 0xFF);
            tempo = data[5] & 0xFF;
            fmPointers = channels == 0
                    ? new int[0] : new int[] {read16(6)};
            fmKeyOffsets = new int[channels];
            fmVolumeOffsets = new int[channels];
            psgPointers = new int[0];
            psgKeyOffsets = new int[0];
            psgVolumeOffsets = new int[0];
            psgModEnvs = new int[0];
            psgInstruments = new int[0];
        }

        @Override
        public byte[] getVoice(int voiceId) {
            return new byte[25];
        }

        @Override
        public byte[] getPsgEnvelope(int id) {
            return new byte[] {(byte) 0x81};
        }

        @Override
        public int read16(int offset) {
            return (data[offset] & 0xFF)
                    | ((data[offset + 1] & 0xFF) << 8);
        }

        @Override
        public int getBaseNoteOffset() {
            return 0;
        }
    }

    private static class MutableSfxData extends MutableMusicData
            implements SmpsSfxData {
        private final List<SmpsSfxTrack> tracks;

        MutableSfxData(byte[] data) {
            super(data);
            tracks = List.of(new FixtureTrack(
                    data[5] & 0xFF, read16(6), data[8], data[9]));
            channels = 1;
            psgChannels = 0;
        }

        @Override
        public int getTickMultiplier() {
            return 1;
        }

        @Override
        public List<? extends SmpsSfxTrack> getTrackEntries() {
            return tracks;
        }
    }

    private static final class CountingSfxData extends MutableSfxData {
        private final AtomicInteger materializations;

        private CountingSfxData(
                byte[] data, AtomicInteger materializations) {
            super(data);
            this.materializations = materializations;
        }

        @Override
        public byte[] getData() {
            materializations.incrementAndGet();
            return super.getData();
        }
    }

    private record FixtureTrack(
            int channelMask, int pointer, int transpose, int volume)
            implements SmpsSfxData.SmpsSfxTrack {
    }
}
