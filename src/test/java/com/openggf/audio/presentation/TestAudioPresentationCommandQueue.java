package com.openggf.audio.presentation;

import com.openggf.audio.ChannelType;
import com.openggf.audio.presentation.AudioPresentationCommand.AddSmpsSfx;
import com.openggf.audio.presentation.AudioPresentationCommand.ChangeMusicTempo;
import com.openggf.audio.presentation.AudioPresentationCommand.EndMusicOverride;
import com.openggf.audio.presentation.AudioPresentationCommand.FadeMusic;
import com.openggf.audio.presentation.AudioPresentationCommand.HardReset;
import com.openggf.audio.presentation.AudioPresentationCommand.PushMusicOverride;
import com.openggf.audio.presentation.AudioPresentationCommand.ReplaceMusic;
import com.openggf.audio.presentation.AudioPresentationCommand.ReplaceRawPcm;
import com.openggf.audio.presentation.AudioPresentationCommand.ResetRingAlternation;
import com.openggf.audio.presentation.AudioPresentationCommand.RestoreMusicOverride;
import com.openggf.audio.presentation.AudioPresentationCommand.RewindBoundary;
import com.openggf.audio.presentation.AudioPresentationCommand.SetSpeedMultiplier;
import com.openggf.audio.presentation.AudioPresentationCommand.SetSpeedShoes;
import com.openggf.audio.presentation.AudioPresentationCommand.SetVoiceGain;
import com.openggf.audio.presentation.AudioPresentationCommand.SetVoicePitch;
import com.openggf.audio.presentation.AudioPresentationCommand.StartSampleSfx;
import com.openggf.audio.presentation.AudioPresentationCommand.StopAllSfx;
import com.openggf.audio.presentation.AudioPresentationCommand.StopMusic;
import com.openggf.audio.presentation.AudioPresentationCommand.StopRawPcm;
import com.openggf.audio.presentation.AudioPresentationCommand.ReferenceLimitation;
import com.openggf.audio.presentation.AudioPresentationCommand.RetainGlobalStop;
import com.openggf.audio.presentation.AudioPresentationCommand.StopRawPcmAndRetainGlobalStop;
import com.openggf.audio.presentation.AudioPresentationCommand.StopSmpsSfx;
import com.openggf.audio.presentation.AudioPresentationCommand.SilencePsg;
import com.openggf.audio.presentation.AudioPresentationCommand.ToggleMute;
import com.openggf.audio.presentation.AudioPresentationCommand.ToggleSolo;
import com.openggf.audio.rewind.AudioCommand;
import com.openggf.audio.session.PreparedSmpsMusicActivation;
import com.openggf.audio.session.PreparedSmpsSfxProgram;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestAudioPresentationCommandQueue {

    @Test
    void normalCommandsCannotConsumeFinalThirtyTwoStructuralSlots() {
        AudioPresentationCommandQueue queue = new AudioPresentationCommandQueue();

        for (int index = 0; index < AudioPresentationCommandQueue.CAPACITY; index++) {
            queue.submit(StartSampleSfx.fromVoice(sample(index, 1)), () -> true, ignored -> {
            });
        }

        assertEquals(AudioPresentationCommandQueue.CAPACITY
                        - AudioPresentationCommandQueue.STRUCTURAL_RESERVE,
                queue.size());
    }

    @Test
    void structuralAdmissionEvictsOldestDroppableSampleStart() {
        AudioPresentationCommandQueue queue = new AudioPresentationCommandQueue();
        for (int index = 0; index < 224; index++) {
            queue.submit(StartSampleSfx.fromVoice(sample(index, 1)), () -> true, ignored -> {
            });
        }
        for (int index = 0; index < 32; index++) {
            queue.submit(new FadeMusic(index, 1), () -> true, ignored -> {
            });
        }

        queue.submit(new StopMusic(), () -> true, ignored -> {
        });
        List<AudioPresentationCommand> applied = new ArrayList<>();
        queue.applyPending(applied::add);

        assertEquals(256, applied.size());
        assertInstanceOf(StartSampleSfx.class, applied.get(0));
        assertEquals(1, ((StartSampleSfx) applied.get(0)).voice().voiceId());
        assertFalse(applied.stream()
                .filter(StartSampleSfx.class::isInstance)
                .map(StartSampleSfx.class::cast)
                .anyMatch(command -> command.voice().voiceId() == 0));
        assertInstanceOf(StopMusic.class, applied.get(255));
    }

    @Test
    void fullStructuralQueueSynchronouslyDrainsAtAssertedOwnerBoundary() {
        AudioPresentationCommandQueue queue = new AudioPresentationCommandQueue();
        List<Integer> applied = new ArrayList<>();
        for (int index = 0; index < 256; index++) {
            queue.submit(new FadeMusic(index, 1), () -> true,
                    command -> applied.add(((FadeMusic) command).steps()));
        }

        queue.submit(new FadeMusic(256, 1), () -> true,
                command -> applied.add(((FadeMusic) command).steps()));

        assertEquals(range(0, 256), applied);
        assertEquals(1, queue.size());
        queue.applyPending(command -> applied.add(((FadeMusic) command).steps()));
        assertEquals(range(0, 257), applied);
    }

    @Test
    void fullStructuralQueueRejectsSubmissionDuringRendering() {
        AtomicBoolean rendering = new AtomicBoolean();
        AudioPresentationCommandQueue queue =
                new AudioPresentationCommandQueue(rendering::get);
        for (int index = 0; index < 256; index++) {
            queue.submit(new FadeMusic(index, 1), () -> true, ignored -> {
            });
        }

        rendering.set(true);

        assertThrows(IllegalStateException.class,
                () -> queue.submit(new FadeMusic(256, 1), () -> false, ignored -> {
                }));
        assertEquals(256, queue.size());
    }

    @Test
    void sameTargetScalarCommandsCoalesceWithoutCrossingAnotherCommand() {
        AudioPresentationCommandQueue queue = new AudioPresentationCommandQueue();

        queue.submit(new SetVoiceGain(4, 10), () -> true, ignored -> {
        });
        queue.submit(new SetVoiceGain(4, 20), () -> true, ignored -> {
        });
        queue.submit(new StopAllSfx(), () -> true, ignored -> {
        });
        queue.submit(new SetVoiceGain(4, 30), () -> true, ignored -> {
        });
        queue.submit(new SetVoicePitch(4, 40), () -> true, ignored -> {
        });
        queue.submit(new SetVoiceGain(4, 50), () -> true, ignored -> {
        });

        List<AudioPresentationCommand> applied = new ArrayList<>();
        queue.applyPending(applied::add);

        assertEquals(List.of(
                new SetVoiceGain(4, 20),
                new StopAllSfx(),
                new SetVoiceGain(4, 50),
                new SetVoicePitch(4, 40)), applied);
    }

    @Test
    void moreThanBothQueueRegionsAppliesEveryStructuralCommandInOriginalOrder() {
        AudioPresentationCommandQueue queue = new AudioPresentationCommandQueue();
        List<Integer> applied = new ArrayList<>();

        for (int index = 0; index < 300; index++) {
            queue.submit(new FadeMusic(index, 1), () -> true,
                    command -> applied.add(((FadeMusic) command).steps()));
        }
        queue.applyPending(command -> applied.add(((FadeMusic) command).steps()));

        assertEquals(range(0, 300), applied);
    }

    @Test
    void renderingNeverDrainsExternalCommands() {
        AtomicBoolean rendering = new AtomicBoolean();
        AudioPresentationCommandQueue queue =
                new AudioPresentationCommandQueue(rendering::get);
        queue.submit(new StopMusic(), () -> true, ignored -> {
        });
        List<AudioPresentationCommand> applied = new ArrayList<>();

        rendering.set(true);
        assertThrows(IllegalStateException.class, () -> queue.applyPending(applied::add));

        assertTrue(applied.isEmpty());
        assertEquals(1, queue.size());
        rendering.set(false);
        queue.applyPending(applied::add);
        assertEquals(List.of(new StopMusic()), applied);
    }

    @Test
    void failedApplyRetainsFailingCommandWithoutReplayingAppliedPredecessors() {
        AudioPresentationCommandQueue queue =
                new AudioPresentationCommandQueue();
        queue.submit(new FadeMusic(1, 1), () -> true, ignored -> {
        });
        queue.submit(new FadeMusic(2, 1), () -> true, ignored -> {
        });
        queue.submit(new FadeMusic(3, 1), () -> true, ignored -> {
        });
        AtomicBoolean failSecond = new AtomicBoolean(true);
        List<Integer> applied = new ArrayList<>();
        Consumer<AudioPresentationCommand> flaky = command -> {
            int steps = ((FadeMusic) command).steps();
            if (steps == 2 && failSecond.getAndSet(false)) {
                throw new IllegalStateException("injected apply failure");
            }
            applied.add(steps);
        };

        assertThrows(IllegalStateException.class,
                () -> queue.applyPending(flaky));

        assertEquals(List.of(1), applied,
                "successfully applied commands must not remain queued");
        assertEquals(2, queue.size(),
                "the failing command and its successors must remain retryable");

        queue.applyPending(flaky);

        assertEquals(List.of(1, 2, 3), applied,
                "retry must neither double-apply a predecessor nor lose the failure");
        assertEquals(0, queue.size());
    }

    @Test
    void capturedBatchStaysInExactOrderUntilCompositeCommit() {
        AudioPresentationCommandQueue queue =
                new AudioPresentationCommandQueue();
        for (int steps : new int[] {1, 2, 3}) {
            queue.submit(new FadeMusic(steps, 1),
                    () -> true, ignored -> { });
        }
        List<Integer> attempts = new ArrayList<>();
        AudioPresentationCommandQueue.PendingBatch first =
                queue.capturePendingBatch();

        queue.applyPendingBatch(first, command -> attempts.add(
                ((FadeMusic) command).steps()));
        assertEquals(3, queue.size(),
                "application alone must not consume the captured prefix");
        queue.rollbackPendingBatch(first);

        AudioPresentationCommandQueue.PendingBatch retry =
                queue.capturePendingBatch();
        queue.applyPendingBatch(retry, command -> attempts.add(
                ((FadeMusic) command).steps()));
        queue.preparePendingBatchCommit(retry);
        queue.commitPendingBatch(retry);

        assertEquals(List.of(1, 2, 3, 1, 2, 3), attempts,
                "rollback leaves every command at its original retry position");
        assertEquals(0, queue.size());
    }

    @Test
    void appliedBatchRemainsRetryableWhenCompositeRenderFails() {
        AudioPresentationCommandQueue queue =
                new AudioPresentationCommandQueue();
        queue.submit(new SetSpeedMultiplier(4),
                () -> true, ignored -> { });
        AudioPresentationCommandQueue.PendingBatch batch =
                queue.capturePendingBatch();

        assertThrows(IllegalStateException.class, () -> {
            try {
                queue.applyPendingBatch(batch, ignored -> { });
                throw new IllegalStateException(
                        "injected physical render failure");
            } catch (RuntimeException failure) {
                queue.rollbackPendingBatch(batch);
                throw failure;
            }
        });

        assertEquals(1, queue.size(),
                "render completion precedes command consumption");
        List<AudioPresentationCommand> retry = new ArrayList<>();
        queue.applyPendingAtomically(retry::add);
        assertEquals(List.of(new SetSpeedMultiplier(4)), retry);
    }

    @Test
    void everyAudioCommandVariantHasOneResolvedPresentationCommand() {
        Set<String> resolvedNames = Arrays.stream(AudioPresentationCommand.class.getPermittedSubclasses())
                .map(Class::getSimpleName)
                .collect(java.util.stream.Collectors.toSet());

        assertTrue(resolvedNames.containsAll(Set.of(
                ReplaceMusic.class.getSimpleName(),
                PushMusicOverride.class.getSimpleName(),
                AddSmpsSfx.class.getSimpleName(),
                StartSampleSfx.class.getSimpleName(),
                FadeMusic.class.getSimpleName(),
                StopMusic.class.getSimpleName(),
                StopAllSfx.class.getSimpleName(),
                EndMusicOverride.class.getSimpleName(),
                RestoreMusicOverride.class.getSimpleName(),
                SetSpeedShoes.class.getSimpleName(),
                SetSpeedMultiplier.class.getSimpleName(),
                ChangeMusicTempo.class.getSimpleName(),
                ResetRingAlternation.class.getSimpleName())));
        assertEquals(18, AudioCommand.class.getPermittedSubclasses().length);
    }

    @Test
    void resolvedCommandsContainNoConsumerRunnableOrMutableClosure() {
        Set<Class<?>> expectedRecords = Set.of(
                ReplaceMusic.class, PushMusicOverride.class, RestoreMusicOverride.class,
                EndMusicOverride.class, AddSmpsSfx.class, StartSampleSfx.class,
                ReplaceRawPcm.class, StopRawPcm.class,
                StopRawPcmAndRetainGlobalStop.class,
                RetainGlobalStop.class, StopMusic.class, StopAllSfx.class,
                StopSmpsSfx.class, SilencePsg.class,
                ReferenceLimitation.class,
                FadeMusic.class, SetVoiceGain.class, SetVoicePitch.class,
                SetSpeedShoes.class, SetSpeedMultiplier.class, ChangeMusicTempo.class,
                ResetRingAlternation.class, ToggleMute.class, ToggleSolo.class,
                RewindBoundary.class, HardReset.class);

        assertEquals(expectedRecords,
                Set.of(AudioPresentationCommand.class.getPermittedSubclasses()));
        for (Class<?> commandType : expectedRecords) {
            assertTrue(commandType.isRecord());
            assertRecursivelyImmutablePayload(commandType, new HashSet<>());
        }

        assertTrue(new ToggleMute(ChannelType.FM, 0).structural());
        assertTrue(new ToggleSolo(ChannelType.PSG, 0).structural());
    }

    @Test
    void queuedVoiceCommandsOwnImmutableSnapshotsAfterOriginalVoicesMutate() {
        AudioPresentationCommandQueue queue = new AudioPresentationCommandQueue();
        SampleBackedVoice sample = sample(70, 5);
        SampleBackedVoice raw = sample(71, 0);
        SampleBackedVoice musicVoice = SampleBackedVoice.loopingMusic(72,
                new DecodedPcm("music", 1, 48_000, new short[] {10, 20}),
                48_000, 1.0f);
        AudioPresentationCommand.MusicVoiceEntry music =
                AudioPresentationCommand.MusicVoiceEntry.fromVoice(
                        0x81, com.openggf.audio.rewind.AudioSourceDescriptor.baseMusic(0x81),
                        musicVoice);
        PresentationVoiceSnapshot.Sample initialSample =
                (PresentationVoiceSnapshot.Sample) sample.snapshot();
        PresentationVoiceSnapshot.Sample initialRaw =
                (PresentationVoiceSnapshot.Sample) raw.snapshot();
        PresentationVoiceSnapshot.Sample initialMusic =
                (PresentationVoiceSnapshot.Sample) musicVoice.snapshot();

        queue.submit(StartSampleSfx.fromVoice(sample), () -> true, ignored -> {
        });
        queue.submit(ReplaceRawPcm.fromVoice(raw, new byte[] {0}), () -> true, ignored -> {
        });
        queue.submit(new ReplaceMusic(music), () -> true, ignored -> {
        });
        sample.setGain(0.25f);
        raw.setPitch(2.0f, 48_000);
        musicVoice.setGain(0.5f);
        sample.stop();
        raw.stop();
        musicVoice.stop();

        List<AudioPresentationCommand> applied = new ArrayList<>();
        queue.applyPending(applied::add);

        PresentationVoiceSnapshot.Sample queuedSample =
                ((StartSampleSfx) applied.get(0)).voice().snapshot();
        PresentationVoiceSnapshot.Sample queuedRaw =
                ((ReplaceRawPcm) applied.get(1)).voice().snapshot();
        PresentationVoiceSnapshot.Sample queuedMusic =
                ((AudioPresentationCommand.SampleVoiceDescriptor)
                        ((ReplaceMusic) applied.get(2)).music()
                                .voiceDescriptor()).snapshot();
        assertFalse(queuedSample.stopped());
        assertFalse(queuedRaw.stopped());
        assertFalse(queuedMusic.stopped());
        assertEquals(70, queuedSample.voiceId());
        assertEquals(71, queuedRaw.voiceId());
        assertEquals(72, queuedMusic.voiceId());
        assertEquals(initialSample.gainQ16(), queuedSample.gainQ16());
        assertEquals(initialRaw.sourceStepQ32(), queuedRaw.sourceStepQ32());
        assertEquals(initialMusic.gainQ16(), queuedMusic.gainQ16());
    }

    private static SampleBackedVoice sample(long id, int priority) {
        return SampleBackedVoice.oneShot(id, priority,
                new DecodedPcm("sample-" + id, 1, 48_000, new short[] {1}),
                48_000, 1.0f, 1.0f);
    }

    private static List<Integer> range(int startInclusive, int endExclusive) {
        List<Integer> values = new ArrayList<>();
        for (int value = startInclusive; value < endExclusive; value++) {
            values.add(value);
        }
        return values;
    }

    private static void assertRecursivelyImmutablePayload(
            Class<?> type, Set<Class<?>> visited) {
        if (!visited.add(type) || type.isPrimitive() || type.isEnum()
                || type == String.class || Number.class.isAssignableFrom(type)
                || type == Boolean.class || type == Character.class
                || type == PreparedSmpsMusicActivation.class
                || type == PreparedSmpsSfxProgram.class) {
            return;
        }
        assertFalse(PresentationVoice.class.isAssignableFrom(type), type.getName());
        assertFalse(Runnable.class.isAssignableFrom(type), type.getName());
        assertFalse(Consumer.class.isAssignableFrom(type), type.getName());
        assertFalse(type.getName().startsWith("java.util.function."), type.getName());
        assertFalse(Set.of("AbstractSmpsData", "DacData", "SmpsSequencerConfig",
                "SmpsSequencer", "SmpsDriver").contains(type.getSimpleName()), type.getName());
        if (type.isSealed()) {
            for (Class<?> permitted : type.getPermittedSubclasses()) {
                assertRecursivelyImmutablePayload(permitted, visited);
            }
            return;
        }
        if (type.isRecord()) {
            for (RecordComponent component : type.getRecordComponents()) {
                assertRecursivelyImmutablePayload(component.getType(), visited);
            }
        }
    }
}
