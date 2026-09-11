package com.openggf.audio.presentation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.openggf.audio.AudioManager;
import com.openggf.audio.AudioTestFixtures;
import com.openggf.audio.ChannelType;
import com.openggf.audio.driver.PreparedSfxAdmission;
import com.openggf.audio.driver.SmpsDriver;
import com.openggf.audio.driver.SmpsDriverTestAccess;
import com.openggf.audio.presentation.AudioPresentationCommand.AddSmpsSfx;
import com.openggf.audio.presentation.AudioPresentationCommand.EndMusicOverride;
import com.openggf.audio.presentation.AudioPresentationCommand.HardReset;
import com.openggf.audio.presentation.AudioPresentationCommand.MusicVoiceEntry;
import com.openggf.audio.presentation.AudioPresentationCommand.PushMusicOverride;
import com.openggf.audio.presentation.AudioPresentationCommand.ReplaceMusic;
import com.openggf.audio.presentation.AudioPresentationCommand.ReplaceRawPcm;
import com.openggf.audio.presentation.AudioPresentationCommand.RestoreMusicOverride;
import com.openggf.audio.presentation.AudioPresentationCommand.ResetRingAlternation;
import com.openggf.audio.presentation.AudioPresentationCommand.SetSpeedMultiplier;
import com.openggf.audio.presentation.AudioPresentationCommand.SetSpeedShoes;
import com.openggf.audio.presentation.AudioPresentationCommand.StartSampleSfx;
import com.openggf.audio.presentation.AudioPresentationCommand.StopAllSfx;
import com.openggf.audio.presentation.AudioPresentationCommand.StopRawPcm;
import com.openggf.audio.presentation.AudioPresentationCommand.ToggleMute;
import com.openggf.audio.presentation.AudioPresentationCommand.ToggleSolo;
import com.openggf.audio.rewind.AudioSourceDescriptor;
import com.openggf.audio.smps.AbstractSmpsData;
import com.openggf.audio.smps.CoordFlagContext;
import com.openggf.audio.smps.CoordFlagHandler;
import com.openggf.audio.smps.DacData;
import com.openggf.audio.smps.SmpsCoordFlagHandlerOwner;
import com.openggf.audio.smps.SmpsCoordFlagRuntimeState;
import com.openggf.audio.smps.SmpsSequencer;
import com.openggf.audio.smps.SmpsSequencerConfig;
import com.openggf.audio.smps.SmpsSfxData;
import com.openggf.audio.synth.ChipWriteObserver;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestAudioVoiceRegistry {
    private static final int OUTPUT_RATE = 48_000;
    private static final int MAX_STEREO_FRAMES = 16;
    private static final ObjectMapper JSON = new ObjectMapper()
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

    @Test
    void completedTransactionsReleaseBackupVoiceReferencesWithoutAnotherCapture() throws Exception {
        for (boolean rollback : new boolean[] {false, true}) {
            AudioVoiceRegistry registry = registry(new RecordingInstantiation(), new ArrayList<>());
            registry.apply(new ReplaceMusic(fallbackMusic(1, 10, "base")));
            registry.apply(new PushMusicOverride(fallbackMusic(2, 20, "override")));
            registry.apply(raw(sample(30, 0, "raw")));
            registry.apply(start(sample(40, 1, "sfx")));
            var expected = JSON.valueToTree(registry.snapshot());
            var token = registry.captureLiveMutation();
            Field buffersField = AudioVoiceRegistry.class.getDeclaredField("mutationBuffers");
            buffersField.setAccessible(true);
            Object buffers = buffersField.get(registry);
            for (String name : List.of("overrides", "samples", "voices", "voiceStates")) {
                assertTrue(Arrays.stream(backupReferences(buffers, name)).anyMatch(java.util.Objects::nonNull),
                        name + " must contain actual captured state before completion");
            }
            registry.apply(new HardReset());
            if (rollback) {
                registry.rollbackLiveMutation(token);
                assertEquals(expected, JSON.valueToTree(registry.snapshot()));
            } else {
                registry.commitLiveMutation(token);
                assertEquals(0, registry.orderedVoiceCount());
                registry.publishCommittedDiagnostics(token);
                assertThrows(IllegalStateException.class, () -> registry.publishCommittedDiagnostics(token));
            }
            for (String name : List.of("overrides", "samples", "voices", "voiceStates")) {
                assertTrue(Arrays.stream(backupReferences(buffers, name)).allMatch(java.util.Objects::isNull),
                        name + " retained references after " + (rollback ? "rollback" : "commit"));
            }
            assertThrows(IllegalStateException.class, () -> registry.rollbackLiveMutation(token));
        }
    }

    private static Object[] backupReferences(Object buffers, String name) throws Exception {
        Field field = buffers.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return (Object[]) field.get(buffers);
    }

    @Test
    void iterationOrderIsMusicThenRawPcmThenSampleSfxByVoiceId() {
        RecordingInstantiation instantiation = new RecordingInstantiation();
        AudioVoiceRegistry registry = registry(instantiation, new ArrayList<>());
        registry.apply(new ReplaceMusic(music(20, 0x81, "music")));
        registry.apply(raw(sample(5, 0, "raw")));
        registry.apply(start(sample(50, 1, "late")));
        registry.apply(start(sample(40, 1, "early")));

        assertEquals(List.of(20L, 5L, 40L, 50L), orderedIds(registry));
    }

    @Test
    void nullSessionRejectsSmpsBeforeInstantiation() {
        RecordingInstantiation instantiation = new RecordingInstantiation();
        AudioVoiceRegistry registry = registry(instantiation, new ArrayList<>());

        assertThrows(IllegalArgumentException.class,
                () -> registry.apply(new AddSmpsSfx(source(10, 0xB0))));

        assertEquals(0, instantiation.cachedCalls);
        assertEquals(0, registry.orderedVoiceCount());
    }

    @Test
    void thirtySecondSampleSfxIsAdmittedAndThirtyThirdEqualPriorityIsRejected() {
        List<String> warnings = new ArrayList<>();
        AudioVoiceRegistry registry = registry(new RecordingInstantiation(), warnings);
        for (int index = 0; index < 32; index++) {
            registry.apply(start(sample(index, 4, "sample-" + index)));
        }
        SampleBackedVoice rejected = sample(32, 4, "rejected");

        registry.apply(start(rejected));
        registry.apply(start(rejected));

        assertEquals(32, registry.orderedVoiceCount());
        assertFalse(orderedIds(registry).contains(32L));
        assertEquals(1, warnings.size(), "a rejected voice id warns exactly once");
    }

    @Test
    void blockedSampleStartWarnsWithoutResolvingItsAsset() {
        RecordingInstantiation instantiation = new RecordingInstantiation();
        List<String> warnings = new ArrayList<>();
        AudioVoiceRegistry registry = registry(instantiation, warnings);
        registry.setSfxBlocked(true);
        instantiation.failingPcmAsset = "blocked";

        registry.apply(start(sample(40, 4, "blocked")));

        assertEquals(0, instantiation.pcmResolveCount);
        assertEquals(List.of("sample SFX blocked at presentation boundary"),
                warnings);
        assertEquals(0, registry.orderedVoiceCount());
    }

    @Test
    void fullSampleBankRejectsEqualAndLowerPriorityWithoutResolvingAssets() {
        RecordingInstantiation instantiation = new RecordingInstantiation();
        List<String> warnings = new ArrayList<>();
        AudioVoiceRegistry registry = registry(instantiation, warnings);
        for (int index = 0; index < 32; index++) {
            registry.apply(start(sample(index, 4, "sample-" + index)));
        }
        instantiation.pcmResolveCount = 0;
        instantiation.resolvedPcmAssets.clear();
        instantiation.failingPcmAsset = "equal";
        AudioPresentationCommandQueue queue = new AudioPresentationCommandQueue();
        queue.submit(start(sample(100, 4, "equal")), () -> true,
                registry::apply);
        queue.submit(start(sample(101, 3, "lower")), () -> true,
                registry::apply);
        queue.submit(new ToggleMute(ChannelType.FM, 3), () -> true,
                registry::apply);

        queue.applyPending(registry::apply);

        assertEquals(0, instantiation.pcmResolveCount);
        assertTrue(instantiation.resolvedPcmAssets.isEmpty());
        assertEquals(List.of(
                "sample SFX capacity rejected voice 100",
                "sample SFX capacity rejected voice 101"), warnings);
        assertEquals(32, registry.orderedVoiceCount());
        assertFalse(orderedIds(registry).contains(100L));
        assertFalse(orderedIds(registry).contains(101L));
        assertEquals(0, queue.size());
        assertEquals(1 << 3, registry.snapshot().fmMuteMask(),
                "rejected starts cannot stop later queued commands");
    }

    @Test
    void fullSampleBankResolvesAcceptedHigherPriorityExactlyOnce() {
        RecordingInstantiation instantiation = new RecordingInstantiation();
        AudioVoiceRegistry registry = registry(instantiation, new ArrayList<>());
        for (int index = 0; index < 32; index++) {
            registry.apply(start(sample(index, 4, "sample-" + index)));
        }
        instantiation.pcmResolveCount = 0;
        instantiation.resolvedPcmAssets.clear();

        registry.apply(start(sample(100, 5, "higher")));

        assertEquals(1, instantiation.pcmResolveCount);
        assertEquals(List.of("higher"), instantiation.resolvedPcmAssets);
        assertEquals(32, registry.orderedVoiceCount());
        assertFalse(orderedIds(registry).contains(0L),
                "oldest strictly lower-priority voice is replaced");
        assertTrue(orderedIds(registry).contains(100L));
    }

    @Test
    void failedAcceptedMaterializationPreservesBankAndQueueContinuation() {
        RecordingInstantiation instantiation = new RecordingInstantiation();
        AudioVoiceRegistry registry = registry(instantiation, new ArrayList<>());
        for (int index = 0; index < 32; index++) {
            registry.apply(start(sample(index, 4, "sample-" + index)));
        }
        List<Long> originalIds = orderedIds(registry);
        PresentationVoice oldest = registry.orderedVoiceAt(0);
        instantiation.pcmResolveCount = 0;
        instantiation.resolvedPcmAssets.clear();
        instantiation.failingPcmAsset = "failing";
        AudioPresentationCommandQueue queue = new AudioPresentationCommandQueue();
        queue.submit(start(sample(100, 5, "failing")), () -> true,
                registry::apply);
        queue.submit(start(sample(101, 6, "continuation")), () -> true,
                registry::apply);

        assertThrows(IllegalStateException.class,
                () -> queue.applyPending(registry::apply));

        assertEquals(originalIds, orderedIds(registry));
        assertFalse(oldest.isComplete(),
                "failed materialization cannot stop the replacement candidate");
        assertEquals(2, queue.size(),
                "the failed start and later commands remain pending");
        assertEquals(List.of("failing"), instantiation.resolvedPcmAssets);

        instantiation.failingPcmAsset = null;
        queue.applyPending(registry::apply);

        assertEquals(0, queue.size());
        assertFalse(orderedIds(registry).contains(0L));
        assertTrue(orderedIds(registry).contains(100L));
        assertTrue(orderedIds(registry).contains(101L));
        assertEquals(List.of("failing", "failing", "continuation"),
                instantiation.resolvedPcmAssets);
    }

    @Test
    void higherPrioritySampleReplacesOnlyOldestStrictlyLowerPrioritySample() {
        AudioVoiceRegistry registry = registry(new RecordingInstantiation(), new ArrayList<>());
        for (int index = 0; index < 32; index++) {
            int priority = index == 0 ? 2 : index <= 2 ? 1 : 7;
            registry.apply(start(sample(index, priority, "sample-" + index)));
        }

        registry.apply(start(sample(100, 3, "replacement")));

        assertTrue(orderedIds(registry).contains(0L));
        assertFalse(orderedIds(registry).contains(1L),
                "oldest voice at the lowest eligible priority is replaced");
        assertTrue(orderedIds(registry).contains(100L));
        assertEquals(32, registry.orderedVoiceCount());
    }

    @Test
    void sampleOverflowCannotEvictMusicOrRawPcm() {
        RecordingInstantiation instantiation = new RecordingInstantiation();
        AudioVoiceRegistry registry = registry(instantiation, new ArrayList<>());
        registry.apply(new ReplaceMusic(music(200, 0x81, "music")));
        registry.apply(raw(sample(202, 0, "raw")));
        for (int index = 0; index < 32; index++) {
            registry.apply(start(sample(index, 1, "sample-" + index)));
        }

        registry.apply(start(sample(300, 100, "replacement")));

        assertTrue(orderedIds(registry).containsAll(List.of(200L, 202L)));
        assertEquals(34, registry.orderedVoiceCount());
    }

    @Test
    void rawPcmReplacementStopsThePriorRawPcmVoice() {
        AudioVoiceRegistry registry = registry(new RecordingInstantiation(), new ArrayList<>());
        SampleBackedVoice first = sample(1, 0, "raw-1");
        SampleBackedVoice second = sample(2, 0, "raw-2");
        registry.apply(raw(first));
        PresentationVoice ownedFirst = registry.orderedVoiceAt(0);

        registry.apply(raw(second));

        assertNotSame(first, ownedFirst);
        assertTrue(ownedFirst.isComplete());
        assertFalse(first.isComplete(),
                "command submitter retains no mutable registry ownership");
        assertEquals(List.of(2L), orderedIds(registry));
    }

    @Test
    void stopAllSfxPreservesMusic() {
        AudioVoiceRegistry registry = registry(new RecordingInstantiation(), new ArrayList<>());
        MusicVoiceEntry music = music(50, 0x81, "music");
        registry.apply(new ReplaceMusic(music));
        registry.apply(raw(sample(51, 0, "raw")));
        registry.apply(start(sample(52, 1, "sample")));

        registry.apply(new StopAllSfx());

        assertEquals(List.of(50L), orderedIds(registry));
        assertEquals(50, registry.orderedVoiceAt(0).voiceId());
    }

    @Test
    void sixtyFourCompletionsUseDeferredSlots() {
        AudioVoiceRegistry registry =
                fullRegistry(new RecordingInstantiation());

        registry.beginRendering();
        int realCompletions = stopAndDeferEveryAdmittedVoice(registry);
        for (int notification = realCompletions; notification < 64; notification++) {
            registry.deferRemoval(1_000 + notification);
        }

        assertEquals(34, realCompletions,
                "all dedicated slots and every sample slot participate");
        assertEquals(34, registry.orderedVoiceCount(),
                "render traversal cannot mutate storage");
        assertFalse(registry.completionSweepRequired());
        registry.endRendering();
        assertEquals(0, registry.orderedVoiceCount());
        assertFalse(registry.completionSweepRequired());
        assertEquals(0, registry.completionSweepCount());
    }

    @Test
    void sixtyFiveCompletionsCollapseIntoOneDeterministicSweep() {
        AudioVoiceRegistry registry =
                fullRegistry(new RecordingInstantiation());

        registry.beginRendering();
        int realCompletions = stopAndDeferEveryAdmittedVoice(registry);
        for (int notification = realCompletions; notification < 65; notification++) {
            registry.deferRemoval(1_000 + notification);
        }

        assertEquals(34, realCompletions,
                "all dedicated slots and every sample slot participate");
        assertTrue(registry.completionSweepRequired());
        registry.endRendering();
        assertEquals(0, registry.orderedVoiceCount());
        assertFalse(registry.completionSweepRequired());
        assertEquals(1, registry.completionSweepCount());
    }

    @Test
    void crossThreadRemovalAndFailureCallbacksCannotMutateActiveTraversal()
            throws InterruptedException {
        List<String> warnings = new ArrayList<>();
        AudioVoiceRegistry registry =
                registry(new RecordingInstantiation(), warnings);
        registry.apply(start(longSample(7, 1, "sample")));
        PresentationVoice voice = registry.orderedVoiceAt(0);
        AtomicReference<Throwable> removalFailure = new AtomicReference<>();
        AtomicReference<Throwable> voiceFailure = new AtomicReference<>();

        registry.beginRendering();
        Thread intruder = new Thread(() -> {
            try {
                registry.deferRemoval(voice.voiceId());
            } catch (Throwable failure) {
                removalFailure.set(failure);
            }
            try {
                registry.onVoiceFailure(voice);
            } catch (Throwable failure) {
                voiceFailure.set(failure);
            }
        });
        intruder.start();
        intruder.join();

        assertTrue(removalFailure.get() instanceof IllegalStateException);
        assertTrue(voiceFailure.get() instanceof IllegalStateException);
        assertTrue(warnings.isEmpty(), "rejected callback cannot emit a warning");
        assertEquals(List.of(7L), orderedIds(registry));
        assertFalse(registry.completionSweepRequired());

        registry.endRendering();
        assertEquals(List.of(7L), orderedIds(registry),
                "rejected callbacks cannot leave deferred mutations behind");
    }

    @Test
    void snapshotRestorePreservesStructureAndDurableCursors() {
        AudioVoiceRegistry original = registry(new RecordingInstantiation(), new ArrayList<>());
        original.apply(new ReplaceMusic(music(1, 0x81, "music")));
        original.apply(raw(longSample(2, 2, "raw")));
        original.apply(start(longSample(3, 3, "sample")));
        mixFrames(original, 3);
        AudioPresentationSnapshot snapshot = original.snapshot();
        List<short[]> expected = mixPackets(original, 10);

        AudioVoiceRegistry restored = registry(new RecordingInstantiation(), new ArrayList<>());
        restored.restore(snapshot, new FixtureResolver());
        List<short[]> actual = mixPackets(restored, 10);

        assertEquals(List.of(1L, 2L, 3L), orderedIds(restored));
        for (int packet = 0; packet < 10; packet++) {
            assertArrayEquals(expected.get(packet), actual.get(packet));
        }
    }

    @Test
    void failedSnapshotDependencyResolutionDoesNotDestroyLiveRegistry() {
        AudioVoiceRegistry registry =
                registry(new RecordingInstantiation(), new ArrayList<>());
        registry.apply(new ReplaceMusic(music(1, 0x81, "music")));
        registry.apply(raw(longSample(2, 2, "raw")));
        mixFrames(registry, 3);
        AudioPresentationSnapshot before = registry.snapshot();
        SmpsCoordFlagRuntimeState.Snapshot beforeCoord =
                before.coordFlagRuntimeState();

        AudioPresentationDependencyResolver failing =
                new AudioPresentationDependencyResolver() {
                    @Override
                    public DecodedPcm resolvePcm(String assetId) {
                        throw new IllegalStateException("missing " + assetId);
                    }
                };

        assertThrows(IllegalStateException.class,
                () -> registry.restore(before, failing));
        assertEquals(before, registry.snapshot());
        assertEquals(beforeCoord,
                registry.snapshot().coordFlagRuntimeState());

        registry.restore(before, new FixtureResolver());
        assertEquals(before, registry.snapshot(),
                "a failed restore must leave the prior complete state retryable");
    }

    @Test
    void restoreIntoEmptyRegistryRecreatesEveryDedicatedAndSampleSlot() {
        RecordingInstantiation instantiation = new RecordingInstantiation();
        AudioVoiceRegistry original = registry(instantiation, new ArrayList<>());
        original.apply(new ReplaceMusic(music(10, 0x81, "base")));
        original.apply(new PushMusicOverride(music(11, 0x82, "override")));
        original.apply(raw(longSample(13, 0, "raw")));
        original.apply(start(longSample(14, 1, "sample")));
        AudioPresentationSnapshot snapshot = original.snapshot();
        FixtureResolver resolver = new FixtureResolver();

        AudioVoiceRegistry restored = registry(new RecordingInstantiation(), new ArrayList<>());
        restored.restore(snapshot, resolver);

        assertEquals(List.of(11L, 13L, 14L), orderedIds(restored));
        assertEquals(1, restored.snapshot().overrideStack().size());
        assertEquals(0, resolver.recreatedSmps);
    }

    @Test
    void snapshotIncludesMusicIdentityDescriptorMuteSoloAndOverrideFlags() {
        AudioVoiceRegistry registry = registry(new RecordingInstantiation(), new ArrayList<>());
        registry.apply(new ReplaceMusic(music(1, 0x81, "base")));
        registry.apply(new PushMusicOverride(music(2, 0x82, "override")));
        registry.apply(new ToggleMute(ChannelType.FM, 2));
        registry.apply(new ToggleSolo(ChannelType.PSG, 1));
        registry.apply(new SetSpeedShoes(true));
        registry.apply(new SetSpeedMultiplier(8));
        registry.setSfxBlocked(true);
        registry.setPendingRestore(true);

        AudioPresentationSnapshot snapshot = registry.snapshot();

        assertEquals(0x82, snapshot.activeMusic().musicId());
        assertEquals(AudioSourceDescriptor.baseMusic(0x82),
                snapshot.activeMusic().sourceDescriptor());
        assertEquals(2, snapshot.activeMusic().voiceId());
        assertEquals(0x81, snapshot.overrideStack().get(0).musicId());
        assertEquals(1 << 2, snapshot.fmMuteMask());
        assertEquals(1 << 1, snapshot.psgSoloMask());
        assertTrue(snapshot.sfxBlocked());
        assertTrue(snapshot.pendingRestore());
        assertTrue(snapshot.speedShoesEnabled());
        assertEquals(8, snapshot.speedMultiplier());
    }

    @Test
    void wrongVoiceKindFromSampleStartIsDisposedExactlyOnce() {
        CountingVoice wrong = new CountingVoice(40, 4);
        AudioVoiceRegistry registry =
                registryWithFixedRecreation(wrong);

        assertThrows(IllegalStateException.class,
                () -> registry.apply(start(sample(40, 4, "sample"))));

        assertEquals(1, wrong.stopCount);
        assertEquals(0, registry.orderedVoiceCount());
    }

    @Test
    void wrongVoiceKindFromRawPcmReplacementIsDisposedExactlyOnce() {
        CountingVoice wrong = new CountingVoice(41, 0);
        AudioVoiceRegistry registry =
                registryWithFixedRecreation(wrong);

        assertThrows(IllegalStateException.class,
                () -> registry.apply(raw(sample(41, 0, "raw"))));

        assertEquals(1, wrong.stopCount);
        assertEquals(0, registry.orderedVoiceCount());
    }

    @Test
    void wrongVoiceKindFromSampleBackedMusicReplacementIsDisposedExactlyOnce() {
        CountingVoice wrong = new CountingVoice(42, 0);
        AudioVoiceRegistry registry =
                registryWithFixedRecreation(wrong);
        MusicVoiceEntry entry = music(42, 0x84, "music");

        assertThrows(IllegalStateException.class,
                () -> registry.apply(new ReplaceMusic(entry)));

        assertEquals(1, wrong.stopCount);
        assertEquals(0, registry.orderedVoiceCount());
    }

    @Test
    void fullOverrideStackRejectsBeforeMaterializationAndRetainsSuccessors() {
        RecordingInstantiation instantiation = new RecordingInstantiation();
        AudioVoiceRegistry registry = registry(instantiation, new ArrayList<>());
        registry.apply(new ReplaceMusic(music(1, 0x80, "base")));
        for (int index = 0;
             index < AudioPresentationCommandQueue.CAPACITY;
             index++) {
            registry.apply(new PushMusicOverride(
                    music(index + 2L, 0x100 + index,
                            "override-" + index)));
        }
        AudioPresentationSnapshot full = registry.snapshot();
        instantiation.pcmResolveCount = 0;
        instantiation.resolvedPcmAssets.clear();
        AudioPresentationCommandQueue queue =
                new AudioPresentationCommandQueue();
        queue.submit(new PushMusicOverride(
                        music(1_000, 0x400, "overflow")),
                () -> true, registry::apply);
        queue.submit(new ResetRingAlternation(false),
                () -> true, registry::apply);

        assertThrows(IllegalStateException.class,
                () -> queue.applyPending(registry::apply));

        assertEquals(full, registry.snapshot());
        assertEquals(0, instantiation.pcmResolveCount,
                "capacity must be checked before materializing a voice");
        assertEquals(2, queue.size(),
                "the rejected override and its successor remain retryable");

        registry.apply(new RestoreMusicOverride());
        queue.applyPending(registry::apply);

        AudioPresentationSnapshot retried = registry.snapshot();
        assertEquals(0x400, retried.activeMusic().musicId());
        assertEquals(AudioPresentationCommandQueue.CAPACITY,
                retried.overrideStack().size());
        assertFalse(retried.ringLeft(),
                "a successful retry must continue through later commands");
        assertEquals(1, instantiation.pcmResolveCount);
        assertEquals(List.of("overflow"), instantiation.resolvedPcmAssets);
        assertEquals(0, queue.size());
    }

    @Test
    void nestedFallbackWavOverridesEndByMusicIdAndRestoreTheirOwnSlotMetadata() {
        AudioVoiceRegistry registry = registry(new RecordingInstantiation(), new ArrayList<>());
        registry.apply(new ReplaceMusic(fallbackMusic(1, 10, "base")));
        registry.apply(new PushMusicOverride(fallbackMusic(2, 20, "outer")));
        registry.apply(new PushMusicOverride(fallbackMusic(3, 30, "inner")));

        registry.apply(new EndMusicOverride(2));
        registry.apply(new RestoreMusicOverride());

        AudioPresentationSnapshot snapshot = registry.snapshot();
        assertEquals(1, snapshot.activeMusic().musicId());
        assertEquals(AudioSourceDescriptor.fallbackMusic(1),
                snapshot.activeMusic().sourceDescriptor());
        PresentationVoiceSnapshot.Sample active =
                sampleSnapshot(snapshot, snapshot.activeMusic().voiceId());
        assertEquals(1, active.musicId());
        assertEquals(AudioSourceDescriptor.fallbackMusic(1), active.sourceDescriptor());
        assertTrue(snapshot.overrideStack().isEmpty());
    }

    @Test
    void sameOverrideRetriggerDoesNotPushItselfAndEndRestoresBaseExactly() {
        AudioVoiceRegistry registry = registry(new RecordingInstantiation(), new ArrayList<>());
        registry.apply(new ReplaceMusic(fallbackMusic(1, 10, "base")));
        registry.apply(new PushMusicOverride(fallbackMusic(2, 20, "override-first")));
        registry.apply(new PushMusicOverride(fallbackMusic(2, 21, "override-retrigger")));

        registry.apply(new EndMusicOverride(2));

        AudioPresentationSnapshot snapshot = registry.snapshot();
        assertEquals(1, snapshot.activeMusic().musicId());
        assertEquals(10, snapshot.activeMusic().voiceId());
        assertEquals(AudioSourceDescriptor.fallbackMusic(1),
                snapshot.activeMusic().sourceDescriptor());
        assertTrue(snapshot.overrideStack().isEmpty());
    }

    @Test
    void removedRawPcmVoiceRecreatesFromItsRegisteredImmutableAsset() {
        AudioVoiceRegistry registry = registry(new RecordingInstantiation(), new ArrayList<>());
        registry.apply(raw(longSample(9, 0, "registered-raw")));
        AudioPresentationSnapshot snapshot = registry.snapshot();
        registry.apply(new StopRawPcm());
        FixtureResolver resolver = new FixtureResolver();

        AudioVoiceRegistry restored = registry(new RecordingInstantiation(), new ArrayList<>());
        restored.restore(snapshot, resolver);

        assertEquals(List.of("registered-raw"), resolver.resolvedAssets);
        assertEquals(9, restored.snapshot().rawPcmVoiceId());
    }

    @Test
    void restoreWithoutAnOverridePreservesDurableMusic() {
        AudioVoiceRegistry registry =
                registry(new RecordingInstantiation(), new ArrayList<>());
        registry.apply(new ReplaceMusic(fallbackMusic(1, 10, "base")));

        registry.apply(new RestoreMusicOverride());

        assertEquals(1, registry.snapshot().activeMusic().musicId());
        assertTrue(registry.snapshot().overrideStack().isEmpty());
    }

    /**
     * The sibling of {@code existingMuteAndSoloMasksApplyBeforeStandaloneSfx
     * Attachment} for the other way a driver becomes audible.
     * {@code applyDriverControlsAtomically} only reaches the <em>active</em>
     * music voice and the standalone SFX driver, so a base driver sitting under
     * an override never sees a toggle made while the override is playing.
     * {@code restoreMusicOverride}'s {@code applyMusicControls} is the sole
     * thing that re-masks it when it becomes audible again; without it a
     * rewind-era mute silently un-mutes on every override pop.
     */
    /**
     * Every real rewind restore targets a registry that is already holding
     * live, dirtied voices — never a fresh one. Restoring into that registry
     * must render bit-identically to restoring the same snapshot into a fresh
     * one, otherwise a reused driver instance carries residue across a seek.
     */
    @Test
    void restoringIntoADirtyRegistryRendersBitExactlyLikeAFreshRestore() {
        RecordingInstantiation instantiation = new RecordingInstantiation();
        AudioVoiceRegistry original = registry(instantiation, new ArrayList<>());
        original.apply(new ReplaceMusic(music(70, 0x81, "music")));
        original.apply(raw(longSample(71, 2, "raw")));
        AudioPresentationSnapshot snapshot = original.snapshot();

        AudioVoiceRegistry fresh =
                registry(new RecordingInstantiation(), new ArrayList<>());
        fresh.restore(snapshot, new FixtureResolver());
        List<short[]> expected = mixPackets(fresh, 12);

        // The dirty target: restored once, rendered, then perturbed with a
        // different live voice set before the snapshot is restored again.
        AudioVoiceRegistry dirty =
                registry(new RecordingInstantiation(), new ArrayList<>());
        dirty.restore(snapshot, new FixtureResolver());
        mixFrames(dirty, MAX_STEREO_FRAMES);
        dirty.apply(new ReplaceMusic(music(90, 0x83, "music")));
        dirty.apply(start(sample(91, 5, "sample")));
        mixFrames(dirty, MAX_STEREO_FRAMES);
        dirty.restore(snapshot, new FixtureResolver());
        List<short[]> actual = mixPackets(dirty, 12);

        assertTrue(expected.stream().flatMapToInt(packet -> {
            int[] samples = new int[packet.length];
            for (int index = 0; index < packet.length; index++) {
                samples[index] = packet[index];
            }
            return Arrays.stream(samples);
        }).anyMatch(sample -> sample != 0),
                "fixture must exercise audible driver state");
        for (int packet = 0; packet < expected.size(); packet++) {
            assertArrayEquals(expected.get(packet), actual.get(packet),
                    "dirty-registry restore diverged at packet " + packet);
        }
    }

    @Test
    void coordFlagRuntimeStateSnapshotsRestoresAndResetsWithRegistryLifecycle() {
        SmpsCoordFlagRuntimeState state = new SmpsCoordFlagRuntimeState();
        SmpsCoordFlagHandlerOwner owner = new SmpsCoordFlagHandlerOwner(state);
        AtomicInteger creations = new AtomicInteger();
        owner.register("s3k", shared -> {
            creations.incrementAndGet();
            return noOpHandler();
        });
        CoordFlagHandler handler = owner.handlerFor("s3k");
        state.setSpindashRevCounter(7);
        AudioVoiceRegistry registry =
                new AudioVoiceRegistry(new RecordingInstantiation(), owner, ignored -> {
                });
        AudioPresentationSnapshot snapshot = registry.snapshot();
        state.setSpindashRevCounter(99);

        registry.restore(snapshot, new FixtureResolver());

        assertEquals(7, state.spindashRevCounter());
        assertSame(handler, owner.handlerFor("s3k"));
        assertEquals(1, creations.get());
        registry.apply(new HardReset());
        assertEquals(0, state.spindashRevCounter());
        assertSame(handler, owner.handlerFor("s3k"),
                "hard reset zeros state without discarding session handler identity");
    }

    @Test
    void registryExposesOnlyIndexedPreallocatedVoiceStorageNotAMixBypass() {
        assertFalse(Arrays.stream(AudioVoiceRegistry.class.getDeclaredMethods())
                .map(Method::getName)
                .anyMatch("mixInto"::equals));
        for (Field field : AudioVoiceRegistry.class.getDeclaredFields()) {
            assertFalse(Collection.class.isAssignableFrom(field.getType()), field.getName());
            assertFalse(Map.class.isAssignableFrom(field.getType()), field.getName());
        }
        assertTrue(Arrays.stream(AudioVoiceRegistry.class.getDeclaredFields())
                .anyMatch(field -> field.getType() == SampleBackedVoice[].class));
        assertTrue(Arrays.stream(AudioVoiceRegistry.class.getDeclaredFields())
                .anyMatch(field -> field.getType() == PresentationVoice[].class));
        assertTrue(Arrays.stream(AudioVoiceRegistry.class.getDeclaredFields())
                .anyMatch(field -> field.getType() == long[].class));
    }

    private static AudioVoiceRegistry registry(RecordingInstantiation instantiation,
                                               List<String> warnings) {
        return new AudioVoiceRegistry(instantiation, instantiation,
                new SmpsCoordFlagHandlerOwner(new SmpsCoordFlagRuntimeState()),
                warnings::add);
    }

    private static AudioVoiceRegistry registryWithFixedRecreation(
            PresentationVoice voice) {
        AudioPresentationDependencyResolver resolver =
                new AudioPresentationDependencyResolver() {
                    @Override
                    public DecodedPcm resolvePcm(String assetId) {
                        throw new AssertionError(
                                "fixed recreation must bypass PCM resolution");
                    }

                    @Override
                    public PresentationVoice recreateVoice(
                            AudioPresentationCommand.VoiceDescriptor descriptor) {
                        return voice;
                    }
                };
        return new AudioVoiceRegistry(new RecordingInstantiation(), resolver,
                new SmpsCoordFlagHandlerOwner(
                        new SmpsCoordFlagRuntimeState()),
                ignored -> {
                });
    }

    private static AudioVoiceRegistry fullRegistry(
            RecordingInstantiation instantiation) {
        AudioVoiceRegistry registry = registry(instantiation, new ArrayList<>());
        registry.apply(new ReplaceMusic(music(100, 0x81, "music")));
        registry.apply(raw(longSample(102, 0, "raw")));
        for (int index = 0; index < 32; index++) {
            registry.apply(start(longSample(index, 1, "sample-" + index)));
        }
        return registry;
    }

    private static int stopAndDeferEveryAdmittedVoice(
            AudioVoiceRegistry registry) {
        int voiceCount = registry.orderedVoiceCount();
        for (int index = 0; index < voiceCount; index++) {
            PresentationVoice voice = registry.orderedVoiceAt(index);
            voice.stop();
            registry.deferRemoval(voice.voiceId());
        }
        return voiceCount;
    }

    private static FailingControlDriver populatedFailingControlDriver() {
        FailingControlDriver driver = new FailingControlDriver();
        AudioTestFixtures.StubSmpsData data =
                new AudioTestFixtures.StubSmpsData("failing-control");
        data.setId(0x82);
        driver.addSequencer(new SmpsSequencer(
                data, dacData(), driver, AudioManager.getInstance(),
                new SmpsSequencerConfig.Builder().build()), false);
        return driver;
    }

    private static FailingMutationDriver populatedFailingMutationDriver() {
        return populatedFailingMutationDriver(dacData());
    }

    private static FailingMutationDriver populatedFailingMutationDriver(
            DacData dacData) {
        FailingMutationDriver driver = new FailingMutationDriver();
        AudioTestFixtures.StubSmpsData data =
                new AudioTestFixtures.StubSmpsData("failing-mutation");
        data.setId(0x82);
        SmpsSequencer sequencer = new SmpsSequencer(
                data, dacData, driver, AudioManager.getInstance(),
                new SmpsSequencerConfig.Builder().build());
        sequencer.setSampleRate(60.0);
        driver.addSequencer(sequencer, false);
        return driver;
    }

    private static MusicVoiceEntry music(long voiceId, int musicId, String asset) {
        return MusicVoiceEntry.fromVoice(
                musicId, AudioSourceDescriptor.baseMusic(musicId),
                SampleBackedVoice.loopingMusic(voiceId,
                        pcm(asset, 100, 200, 300, 400), OUTPUT_RATE, 1.0f));
    }

    private static MusicVoiceEntry fallbackMusic(int musicId, long voiceId,
                                                 String asset) {
        return MusicVoiceEntry.fromVoice(
                musicId, AudioSourceDescriptor.fallbackMusic(musicId),
                SampleBackedVoice.loopingMusic(voiceId,
                        pcm(asset, 100, 200), OUTPUT_RATE, 1.0f));
    }

    private static SampleBackedVoice sample(long voiceId, int priority, String asset) {
        return SampleBackedVoice.oneShot(voiceId, priority, pcm(asset, 100),
                OUTPUT_RATE, 1.0f, 1.0f);
    }

    private static SampleBackedVoice longSample(long voiceId, int priority,
                                                String asset) {
        return SampleBackedVoice.oneShot(voiceId, priority,
                pcm(asset, 100, 200, 300, 400, 500, 600, 700, 800,
                        900, 1_000, 1_100, 1_200, 1_300, 1_400, 1_500, 1_600),
                OUTPUT_RATE, 1.0f, 1.0f);
    }

    private static StartSampleSfx start(SampleBackedVoice voice) {
        return StartSampleSfx.fromVoice(voice);
    }

    private static ReplaceRawPcm raw(SampleBackedVoice voice) {
        return ReplaceRawPcm.fromVoice(voice, new byte[] {0});
    }

    private static DecodedPcm pcm(String asset, int... samples) {
        short[] converted = new short[samples.length];
        for (int index = 0; index < samples.length; index++) {
            converted[index] = (short) samples[index];
        }
        return new DecodedPcm(asset, 1, OUTPUT_RATE, converted);
    }

    private static DacData dacData() {
        return dacData(new byte[] {0, 24, 64, 127});
    }

    private static DacData dacData(byte[] samples) {
        return new DacData(Map.of(1, samples),
                Map.of(0x81, new DacData.DacEntry(1, 4)), 295);
    }

    private static ResolvedSmpsSfxSource source(long standaloneVoiceId, int sfxId) {
        return new ResolvedSmpsSfxSource(standaloneVoiceId,
                new SmpsAssetKey("s3k", SmpsAssetKey.Route.BASE_ID, sfxId, null),
                1 << 16, 0x70, sfxId, 1, MAX_STEREO_FRAMES);
    }

    private static ResolvedSmpsSfxSource nonContinuousSource(
            long standaloneVoiceId, int sfxId) {
        return new ResolvedSmpsSfxSource(standaloneVoiceId,
                new SmpsAssetKey("s3k", SmpsAssetKey.Route.BASE_ID, sfxId, null),
                1 << 16, 0x70, 0, 1, MAX_STEREO_FRAMES);
    }

    private static List<Long> orderedIds(AudioVoiceRegistry registry) {
        List<Long> ids = new ArrayList<>();
        for (int index = 0; index < registry.orderedVoiceCount(); index++) {
            ids.add(registry.orderedVoiceAt(index).voiceId());
        }
        return ids;
    }

    private static void assertSequencerIdentities(
            List<SmpsSequencer> expected,
            List<SmpsSequencer> actual) {
        assertEquals(expected.size(), actual.size());
        for (int index = 0; index < expected.size(); index++) {
            assertSame(expected.get(index), actual.get(index),
                    "sequencer identity changed at index " + index);
        }
    }

    private static void assertPrimaryWithSuppressedDisposal(
            IllegalStateException failure) {
        assertEquals("injected driver control failure",
                failure.getMessage());
        assertEquals(1, failure.getSuppressed().length);
        assertEquals("injected disposal failure",
                failure.getSuppressed()[0].getMessage());
    }

    private static void mixFrames(AudioVoiceRegistry registry, int frames) {
        AudioPresentationMixer mixer = new AudioPresentationMixer(MAX_STEREO_FRAMES);
        mixer.mix(registry, frames);
    }

    private static List<short[]> mixPackets(AudioVoiceRegistry registry, int count) {
        AudioPresentationMixer mixer = new AudioPresentationMixer(MAX_STEREO_FRAMES);
        List<short[]> packets = new ArrayList<>();
        for (int packet = 0; packet < count; packet++) {
            packets.add(Arrays.copyOf(mixer.mix(registry, 1), 2));
        }
        return packets;
    }

    private static List<short[]> mixPcmPackets(
            AudioVoiceRegistry registry, int count) {
        AudioPresentationMixer mixer =
                new AudioPresentationMixer(MAX_STEREO_FRAMES);
        List<short[]> packets = new ArrayList<>();
        short[] sessionPcm = new short[2];
        for (int packet = 0; packet < count; packet++) {
            packets.add(Arrays.copyOf(mixer.mixPcmVoices(
                    registry, 1, sessionPcm, 0), 2));
        }
        return packets;
    }

    private static PresentationVoiceSnapshot.Sample sampleSnapshot(
            AudioPresentationSnapshot snapshot, long voiceId) {
        return snapshot.voices().stream()
                .filter(PresentationVoiceSnapshot.Sample.class::isInstance)
                .map(PresentationVoiceSnapshot.Sample.class::cast)
                .filter(voice -> voice.voiceId() == voiceId)
                .findFirst()
                .orElseThrow();
    }

    private static CoordFlagHandler noOpHandler() {
        return new CoordFlagHandler() {
            @Override
            public boolean handleFlag(CoordFlagContext ctx, SmpsSequencer.Track track,
                                      int command) {
                return false;
            }

            @Override
            public int flagParamLength(int command) {
                return -1;
            }
        };
    }

    private static class RecordingInstantiation
            implements SmpsSfxInstantiation, AudioPresentationDependencyResolver {
        private int cachedCalls;
        private final List<String> resolvedPcmAssets = new ArrayList<>();
        private int pcmResolveCount;
        private String failingPcmAsset;
        private SmpsDriver lastCachedOwner;
        private boolean cacheMiss;
        private boolean failIfCached;

        @Override
        public DecodedPcm resolvePcm(String assetId) {
            pcmResolveCount++;
            resolvedPcmAssets.add(assetId);
            if (assetId.equals(failingPcmAsset)) {
                throw new IllegalStateException(
                        "fixture PCM resolution failure for " + assetId);
            }
            if ("music".equals(assetId)) {
                return pcm(assetId, 100, 200, 300, 400);
            }
            if ("raw".equals(assetId) || "sample".equals(assetId)
                    || "registered-raw".equals(assetId)) {
                return pcm(assetId, 100, 200, 300, 400, 500, 600, 700, 800,
                        900, 1_000, 1_100, 1_200, 1_300, 1_400, 1_500, 1_600);
            }
            return pcm(assetId, 100, 200, 300, 400);
        }

        @Override
        public SmpsSequencer instantiateCached(ResolvedSmpsSfxSource source,
                                               SmpsDriver currentOwner) {
            if (failIfCached) {
                throw new AssertionError("cache lookup must not happen");
            }
            cachedCalls++;
            lastCachedOwner = currentOwner;
            return cacheMiss ? null : sequencer(source, currentOwner);
        }

    }

    private static final class TransactionalSfxInstantiation
            extends RecordingInstantiation {
        private final DacData sfxDac;
        private final CoordFlagHandler handler;

        private TransactionalSfxInstantiation(
                DacData sfxDac, CoordFlagHandler handler) {
            this.sfxDac = sfxDac;
            this.handler = handler;
        }

        @Override
        public SmpsSequencer instantiateCached(
                ResolvedSmpsSfxSource source,
                SmpsDriver currentOwner) {
            SmpsSequencer sequencer = new SmpsSequencer(
                    new FixtureSfxData(source.assetKey().sfxId()),
                    sfxDac, currentOwner, AudioManager.getInstance(),
                    new SmpsSequencerConfig.Builder()
                            .coordFlagHandler(handler)
                            .build());
            sequencer.setSfxPriority(source.priority());
            return sequencer;
        }
    }

    private static final class AdmissionOrderInstantiation
            extends RecordingInstantiation {
        private final List<String> events;

        private AdmissionOrderInstantiation(List<String> events) {
            this.events = events;
        }

        @Override
        public SmpsSequencer instantiateCached(
                ResolvedSmpsSfxSource source, SmpsDriver currentOwner) {
            return sequencerWithStartEvent(source, currentOwner, events);
        }
    }

    private static final class FixtureSfxData extends AbstractSmpsData
            implements SmpsSfxData {
        private final List<FixtureSfxTrack> tracks;

        private FixtureSfxData(int id) {
            this(id, new FixtureSfxTrack[0]);
        }

        private FixtureSfxData(int id, FixtureSfxTrack... tracks) {
            super(new byte[16], 0);
            setId(id);
            this.tracks = List.of(tracks);
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
            return new byte[25];
        }

        @Override
        public byte[] getPsgEnvelope(int id) {
            return new byte[0];
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

    private record FixtureSfxTrack(
            int channelMask, int pointer, int transpose, int volume)
            implements SmpsSfxData.SmpsSfxTrack {
    }

    private static final class ObserverSfxInstantiation
            extends RecordingInstantiation {
        private final int channelMask;
        private final CoordFlagHandler handler;

        private ObserverSfxInstantiation(
                int channelMask, CoordFlagHandler handler) {
            this.channelMask = channelMask;
            this.handler = handler;
        }

        @Override
        public SmpsSequencer instantiateCached(
                ResolvedSmpsSfxSource source, SmpsDriver currentOwner) {
            SmpsSequencer sequencer = new SmpsSequencer(
                    new FixtureSfxData(source.assetKey().sfxId(),
                            new FixtureSfxTrack(channelMask, 1, 0, 0)),
                    dacData(), currentOwner, AudioManager.getInstance(),
                    new SmpsSequencerConfig.Builder()
                            .coordFlagHandler(handler)
                            .build());
            sequencer.setSfxPriority(source.priority());
            return sequencer;
        }
    }

    private static final class FailOnceChipObserver
            implements ChipWriteObserver {
        private final boolean failPsg;
        private boolean failed;

        private FailOnceChipObserver(boolean failPsg) {
            this.failPsg = failPsg;
        }

        @Override
        public void onYm2612Write(int port, int register, int value) {
            if (!failPsg && !failed) {
                failed = true;
                throw new IllegalStateException(
                        "injected YM observer failure");
            }
        }

        @Override
        public void onPsgWrite(int value) {
            if (failPsg && !failed) {
                failed = true;
                throw new IllegalStateException(
                        "injected PSG observer failure");
            }
        }
    }

    private static SmpsSequencer sequencer(
            ResolvedSmpsSfxSource source, SmpsDriver owner) {
        AudioTestFixtures.StubSmpsData data =
                new AudioTestFixtures.StubSmpsData(source.assetKey().gameId());
        data.setId(source.assetKey().sfxId());
        SmpsSequencer sequencer = new SmpsSequencer(
                data, AudioTestFixtures.EMPTY_DAC, owner,
                AudioManager.getInstance(),
                new SmpsSequencerConfig.Builder().build());
        sequencer.setSfxPriority(source.priority());
        sequencer.setPitch(source.pitchQ16() / (float) (1 << 16));
        return sequencer;
    }

    private static SmpsSequencer sequencerWithStartEvent(
            ResolvedSmpsSfxSource source, SmpsDriver owner,
            List<String> events) {
        SmpsSequencer sequencer = new SmpsSequencer(
                new FixtureSfxData(source.assetKey().sfxId()), dacData(),
                owner, AudioManager.getInstance(),
                new SmpsSequencerConfig.Builder()
                        .coordFlagHandler(new CoordFlagHandler() {
                            @Override
                            public void onSfxStart(int sfxId) {
                                events.add("begin");
                            }

                            @Override
                            public boolean handleFlag(
                                    CoordFlagContext context,
                                    SmpsSequencer.Track track, int command) {
                                return false;
                            }

                            @Override
                            public int flagParamLength(int command) {
                                return -1;
                            }
                        })
                        .build());
        sequencer.setSfxPriority(source.priority());
        return sequencer;
    }

    private static final class RecordingDriver extends SmpsDriver {
        private final boolean extendsContinuous;
        private final boolean[] fmMutes = new boolean[6];
        private final boolean[] psgMutes = new boolean[4];
        private final boolean[] fmMuteAtFirstAttachment = new boolean[6];
        private final boolean[] psgMuteAtFirstAttachment = new boolean[4];
        private int extensionCalls;
        private int addedSequencers;

        private RecordingDriver(boolean extendsContinuous) {
            this.extendsContinuous = extendsContinuous;
        }

        @Override
        public PreparedSfxAdmission prepareContinuousSfxExtension(
                int sfxId, int trackCount) {
            extensionCalls++;
            return super.prepareContinuousSfxExtension(sfxId, trackCount);
        }

        private void primeContinuousSfx(int sfxId) {
            SmpsSequencer existing = new SmpsSequencer(
                    new FixtureSfxData(sfxId), dacData(), this,
                    AudioManager.getInstance(),
                    new SmpsSequencerConfig.Builder().build());
            super.addSequencer(existing, true);
            startContinuousSfx(sfxId, 1);
            extensionCalls = 0;
            addedSequencers = 0;
        }

        @Override
        public void setFmMute(int channel, boolean mute) {
            fmMutes[channel] = mute;
            super.setFmMute(channel, mute);
        }

        @Override
        public void setPsgMute(int channel, boolean mute) {
            psgMutes[channel] = mute;
            super.setPsgMute(channel, mute);
        }

        @Override
        public void addSequencer(SmpsSequencer sequencer, boolean sfx) {
            if (addedSequencers == 0) {
                System.arraycopy(fmMutes, 0, fmMuteAtFirstAttachment, 0,
                        fmMutes.length);
                System.arraycopy(psgMutes, 0, psgMuteAtFirstAttachment, 0,
                        psgMutes.length);
            }
            addedSequencers++;
            super.addSequencer(sequencer, sfx);
        }

        @Override
        public void commitSfxAdmission(PreparedSfxAdmission admission) {
            if (!admission.continuousExtension()) {
                if (addedSequencers == 0) {
                    System.arraycopy(fmMutes, 0, fmMuteAtFirstAttachment, 0,
                            fmMutes.length);
                    System.arraycopy(psgMutes, 0, psgMuteAtFirstAttachment, 0,
                            psgMutes.length);
                }
                addedSequencers++;
            }
            super.commitSfxAdmission(admission);
        }
    }

    private static final class CapturingDriver extends SmpsDriver {
        private final List<String> events;
        private int liveCommandMutationCaptures;

        private CapturingDriver() {
            this(null);
        }

        private CapturingDriver(List<String> events) {
            this.events = events;
        }

        @Override
        public LiveCommandMutationToken captureLiveCommandMutation() {
            liveCommandMutationCaptures++;
            return super.captureLiveCommandMutation();
        }

        @Override
        public void commitSfxAdmission(PreparedSfxAdmission admission) {
            if (events != null) {
                events.add("commit");
            }
            super.commitSfxAdmission(admission);
        }
    }

    private static final class FailingControlDriver extends SmpsDriver {
        private boolean failNextControl;

        private void failNextControl() {
            failNextControl = true;
        }

        @Override
        public void setFmMute(int channel, boolean mute) {
            super.setFmMute(channel, mute);
            if (failNextControl && channel == 2) {
                failNextControl = false;
                throw new IllegalStateException(
                        "injected driver control failure");
            }
        }
    }

    private static final class FailingControlAndStopDriver
            extends SmpsDriver {
        private boolean failControl = true;

        @Override
        public void setFmMute(int channel, boolean mute) {
            super.setFmMute(channel, mute);
            if (failControl && channel == 2) {
                failControl = false;
                throw new IllegalStateException(
                        "injected driver control failure");
            }
        }

        @Override
        public void stopAll() {
            super.stopAll();
            throw new IllegalStateException(
                    "injected disposal failure");
        }
    }

    private static final class FailingMutationDriver extends SmpsDriver {
        private boolean failNextControl;
        private boolean failNextSfxAttachment;
        private boolean failNextStopAll;
        private boolean failNextStopAllSfx;
        private int stopAllCalls;
        private int commitCalls;

        private void failNextControl() {
            failNextControl = true;
        }

        private void failNextSfxAttachment() {
            failNextSfxAttachment = true;
        }

        private void failNextStopAll() {
            failNextStopAll = true;
        }

        private void failNextStopAllSfx() {
            failNextStopAllSfx = true;
        }

        @Override
        public void setFmMute(int channel, boolean mute) {
            super.setFmMute(channel, mute);
            if (failNextControl && channel == 2) {
                failNextControl = false;
                throw new IllegalStateException(
                        "injected driver control failure");
            }
        }

        @Override
        public PreparedSfxAdmission prepareNewSfxAdmission(
                SmpsSequencer sequencer, int continuousSfxId,
                int trackCount) {
            if (failNextSfxAttachment) {
                failNextSfxAttachment = false;
                throw new IllegalStateException(
                        "injected SFX preparation failure");
            }
            return super.prepareNewSfxAdmission(
                    sequencer, continuousSfxId, trackCount);
        }

        @Override
        public void commitSfxAdmission(PreparedSfxAdmission admission) {
            commitCalls++;
            super.commitSfxAdmission(admission);
        }

        @Override
        public void stopAll() {
            stopAllCalls++;
            super.stopAll();
            if (failNextStopAll) {
                failNextStopAll = false;
                throw new IllegalStateException(
                        "injected stop-all failure");
            }
        }

        @Override
        public void stopAllSfx() {
            super.stopAllSfx();
            if (failNextStopAllSfx) {
                failNextStopAllSfx = false;
                throw new IllegalStateException(
                        "injected stop-all-SFX failure");
            }
        }
    }

    private static final class PreparedOnlyDriver extends SmpsDriver {
        private final List<String> events;

        private PreparedOnlyDriver(List<String> events) {
            this.events = events;
        }

        @Override
        public void addSequencer(SmpsSequencer sequencer, boolean sfx) {
            if (sfx) {
                throw new AssertionError(
                        "registry must not use legacy SFX attachment");
            }
            super.addSequencer(sequencer, false);
        }

        @Override
        public PreparedSfxAdmission prepareNewSfxAdmission(
                SmpsSequencer sequencer, int continuousSfxId,
                int trackCount) {
            events.add("prepare");
            return super.prepareNewSfxAdmission(
                    sequencer, continuousSfxId, trackCount);
        }

        @Override
        public void commitSfxAdmission(PreparedSfxAdmission admission) {
            events.add("commit");
            super.commitSfxAdmission(admission);
        }
    }

    private static final class OrderedSfxInstantiation
            extends RecordingInstantiation {
        private final List<String> events;

        private OrderedSfxInstantiation(List<String> events) {
            this.events = events;
        }

        @Override
        public SmpsSequencer instantiateCached(
                ResolvedSmpsSfxSource source, SmpsDriver currentOwner) {
            events.add("instantiate");
            return new SmpsSequencer(
                    new FixtureSfxData(source.assetKey().sfxId()),
                    dacData(), currentOwner, AudioManager.getInstance(),
                    new SmpsSequencerConfig.Builder()
                            .coordFlagHandler(new CoordFlagHandler() {
                                @Override
                                public void onSfxStart(int sfxId) {
                                    events.add("begin");
                                }

                                @Override
                                public boolean handleFlag(
                                        CoordFlagContext context,
                                        SmpsSequencer.Track track,
                                        int command) {
                                    return false;
                                }

                                @Override
                                public int flagParamLength(int command) {
                                    return -1;
                                }
                            })
                            .build());
        }
    }

    private static final class AdmissionBoundaryDriver extends SmpsDriver {
        private final SmpsCoordFlagRuntimeState state;
        private final List<String> events;
        private int commitCalls;

        private AdmissionBoundaryDriver(
                SmpsCoordFlagRuntimeState state, List<String> events) {
            this.state = state;
            this.events = events;
        }

        @Override
        public PreparedSfxAdmission prepareNewSfxAdmission(
                SmpsSequencer sequencer, int continuousSfxId,
                int trackCount) {
            events.add("prepare");
            state.setSpindashRevCounter(
                    state.spindashRevCounter() + 1);
            return super.prepareNewSfxAdmission(
                    sequencer, continuousSfxId, trackCount);
        }

        @Override
        public void commitSfxAdmission(PreparedSfxAdmission admission) {
            commitCalls++;
            super.commitSfxAdmission(admission);
        }
    }

    private static final class AdmissionBoundaryInstantiation
            extends RecordingInstantiation {
        private final SmpsCoordFlagRuntimeState state;
        private final List<String> events;

        private AdmissionBoundaryInstantiation(
                SmpsCoordFlagRuntimeState state, List<String> events) {
            this.state = state;
            this.events = events;
        }

        @Override
        public SmpsSequencer instantiateCached(
                ResolvedSmpsSfxSource source, SmpsDriver currentOwner) {
            events.add("instantiate");
            state.setSpindashRevCounter(
                    state.spindashRevCounter() + 1);
            return new SmpsSequencer(
                    new FixtureSfxData(source.assetKey().sfxId()),
                    dacData(), currentOwner, AudioManager.getInstance(),
                    new SmpsSequencerConfig.Builder()
                            .coordFlagHandler(new CoordFlagHandler() {
                                @Override
                                public void onSfxStart(int sfxId) {
                                    events.add("begin");
                                    state.setSpindashRevCounter(
                                            state.spindashRevCounter() + 1);
                                    throw new IllegalStateException(
                                            "injected begin failure");
                                }

                                @Override
                                public boolean handleFlag(
                                        CoordFlagContext context,
                                        SmpsSequencer.Track track,
                                        int command) {
                                    return false;
                                }

                                @Override
                                public int flagParamLength(int command) {
                                    return -1;
                                }
                            })
                            .build());
        }
    }

    private static final class CountingVoice implements PresentationVoice {
        private final long voiceId;
        private final int priority;
        private int stopCount;

        private CountingVoice(long voiceId, int priority) {
            this.voiceId = voiceId;
            this.priority = priority;
        }

        @Override
        public long voiceId() {
            return voiceId;
        }

        @Override
        public int priority() {
            return priority;
        }

        @Override
        public void mixInto(long[] accumulation, int stereoFrames) {
        }

        @Override
        public boolean isComplete() {
            return stopCount > 0;
        }

        @Override
        public void stop() {
            stopCount++;
        }

        @Override
        public PresentationVoiceSnapshot snapshot() {
            throw new AssertionError(
                    "wrong-kind voice must never be published");
        }
    }

    private static final class FixtureResolver
            implements AudioPresentationDependencyResolver {
        private final List<String> resolvedAssets = new ArrayList<>();
        private int recreatedSmps;
        private int lastMaxStereoFrames;

        @Override
        public DecodedPcm resolvePcm(String assetId) {
            resolvedAssets.add(assetId);
            return switch (assetId) {
                case "music" -> pcm(assetId, 100, 200, 300, 400);
                case "raw", "sample", "registered-raw" ->
                        pcm(assetId, 100, 200, 300, 400, 500, 600, 700, 800,
                                900, 1_000, 1_100, 1_200, 1_300, 1_400, 1_500, 1_600);
                default -> pcm(assetId, 100, 200);
            };
        }

    }
}
