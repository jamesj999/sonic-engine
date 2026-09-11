package com.openggf.game.sonic2.audio;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestSonic2SoundRequestPipeline {
    @Test
    void startsWithReadyQueueAndNoPayloadWithoutARequestByte() {
        Sonic2SoundRequestPipeline<String> pipeline = new Sonic2SoundRequestPipeline<>();

        Sonic2SoundRequestPipeline.Snapshot<String> snapshot = pipeline.snapshot();

        assertEquals(0x80, snapshot.queueToPlay().requestByte());
        assertEquals(0, snapshot.music0().requestByte());
        assertNull(snapshot.music0().payload());
        assertEquals(0, snapshot.sfxPriorityValue());
        assertEquals(0, snapshot.voiceTablePointer());
    }

    @Test
    void musicAndSoundSubmissionUseTheShippedOverwriteSlots() {
        Sonic2SoundRequestPipeline<String> pipeline = new Sonic2SoundRequestPipeline<>();

        pipeline.submitMusic(0x82, "primary");
        pipeline.submitMusic(0x93, "fallback-old");
        pipeline.submitMusic(0x94, "fallback-new");
        pipeline.submitSound(0xB5, "sound-old");
        pipeline.submitSound(0xA0, "sound-new");
        pipeline.submitSound2(0xCC, "sound2-old");
        pipeline.submitSound2(0xBF, "sound2-new");

        Sonic2SoundRequestPipeline.Snapshot<String> snapshot = pipeline.snapshot();
        assertEquals(new Sonic2SoundRequestPipeline.SlotState<>(0x82, "primary"), snapshot.music0());
        assertEquals(new Sonic2SoundRequestPipeline.SlotState<>(0x94, "fallback-new"), snapshot.music1());
        assertEquals(new Sonic2SoundRequestPipeline.SlotState<>(0xA0, "sound-new"), snapshot.sfx0());
        assertEquals(new Sonic2SoundRequestPipeline.SlotState<>(0xBF, "sound2-new"), snapshot.sfx1());
    }

    @Test
    void bridgePromotesMusicThenWalksSlotsThreeToZeroIncludingTheVoicePointerAlias() {
        Sonic2SoundRequestPipeline<String> pipeline = new Sonic2SoundRequestPipeline<>();
        pipeline.submitMusic(0x82, "music-primary");
        pipeline.submitMusic(0x93, "music-fallback");
        pipeline.submitSound(0xA0, "jump");
        pipeline.submitSound2(0xB5, "ring");
        pipeline.submitSfx2ForTesting(0xCC, "spring");

        Sonic2SoundRequestPipeline.BridgeResult<String> result = pipeline.bridge();

        assertEquals(new Sonic2SoundRequestPipeline.MusicBridgeResult<>(
                Sonic2SoundRequestPipeline.MusicBridgeKind.PROMOTED, 0x82, "music-primary"), result.music());
        assertEquals(List.of(
                new Sonic2SoundRequestPipeline.PhysicalTransfer<>(
                        Sonic2SoundRequestPipeline.SourceSlot.MUSIC1, 3, 0x93, true, "music-fallback"),
                new Sonic2SoundRequestPipeline.PhysicalTransfer<>(
                        Sonic2SoundRequestPipeline.SourceSlot.SFX2, 2, 0xCC, false, "spring"),
                new Sonic2SoundRequestPipeline.PhysicalTransfer<>(
                        Sonic2SoundRequestPipeline.SourceSlot.SFX1, 1, 0xB5, false, "ring"),
                new Sonic2SoundRequestPipeline.PhysicalTransfer<>(
                        Sonic2SoundRequestPipeline.SourceSlot.SFX0, 0, 0xA0, false, "jump")), result.transfers());
        Sonic2SoundRequestPipeline.Snapshot<String> snapshot = pipeline.snapshot();
        assertEquals(0x93, snapshot.voiceTablePointer());
        assertEquals(new Sonic2SoundRequestPipeline.SlotState<>(0, null), snapshot.music1());
        assertEquals(new Sonic2SoundRequestPipeline.QueueToPlayState<>(0x82, "music-primary"), snapshot.queueToPlay());
    }

    @Test
    void bridgeLeavesSourcesIntactWhenThePhysicalDestinationIsOccupied() {
        Sonic2SoundRequestPipeline<String> pipeline = new Sonic2SoundRequestPipeline<>();
        pipeline.submitSound(0xA0, "first");
        pipeline.bridge();
        pipeline.submitSound(0xB5, "second");

        Sonic2SoundRequestPipeline.BridgeResult<String> result = pipeline.bridge();

        assertTrue(result.transfers().isEmpty());
        assertEquals(new Sonic2SoundRequestPipeline.SlotState<>(0xB5, "second"), pipeline.snapshot().sfx0());
        assertEquals(new Sonic2SoundRequestPipeline.SlotState<>(0xA0, "first"), pipeline.snapshot().queue0());
    }

    @Test
    void bridgeConvertsPauseAndUnpauseAndDefersMusicWhenTheReadySlotIsBusy() {
        Sonic2SoundRequestPipeline<String> pipeline = new Sonic2SoundRequestPipeline<>();
        pipeline.submitMusic(0xFE, "pause");
        assertEquals(Sonic2SoundRequestPipeline.MusicBridgeKind.PAUSE, pipeline.bridge().music().kind());
        assertEquals(0x7F, pipeline.snapshot().stopMusic());

        pipeline.submitMusic(0xFF, "unpause");
        assertEquals(Sonic2SoundRequestPipeline.MusicBridgeKind.UNPAUSE, pipeline.bridge().music().kind());
        assertEquals(0x80, pipeline.snapshot().stopMusic());

        pipeline.submitSound(0xA0, "jump");
        pipeline.bridge();
        pipeline.cycleQueue();
        pipeline.submitMusic(0x82, "busy-music");
        assertEquals(Sonic2SoundRequestPipeline.MusicBridgeKind.DEFERRED, pipeline.bridge().music().kind());
        assertEquals(new Sonic2SoundRequestPipeline.SlotState<>(0x82, "busy-music"), pipeline.snapshot().music0());
    }

    @Test
    void queueCycleClearsInvalidBytesThenPromotesMusicAndCommands() {
        Sonic2SoundRequestPipeline<String> pipeline = new Sonic2SoundRequestPipeline<>();
        pipeline.submitSound(0x01, "invalid");
        pipeline.submitSound2(0x93, "boss");
        pipeline.bridge();

        Sonic2SoundRequestPipeline.CycleResult<String> music = pipeline.cycleQueue();
        assertEquals(Sonic2SoundRequestPipeline.DecisionKind.PROMOTED_MUSIC, music.kind());
        assertEquals(List.of(new Sonic2SoundRequestPipeline.Request<>(
                Sonic2SoundRequestPipeline.QueueSlot.QUEUE0, 0x01, "invalid")), music.invalidDiscards());
        assertEquals(0x93, music.request().requestByte());
        assertEquals("boss", music.request().payload());
        pipeline.dispatchQueuedRequest();

        pipeline.submitSound(0xF8, "stop-sfx");
        pipeline.bridge();
        Sonic2SoundRequestPipeline.CycleResult<String> command = pipeline.cycleQueue();
        assertEquals(Sonic2SoundRequestPipeline.DecisionKind.PROMOTED_COMMAND, command.kind());
        assertEquals(0xF8, command.request().requestByte());
    }

    @Test
    void queueCycleOnlyPromotesOneRequestAndKeepsLaterSlotsForTheNextService() {
        Sonic2SoundRequestPipeline<String> pipeline = new Sonic2SoundRequestPipeline<>();
        pipeline.submitSound(0xA0, "jump");
        pipeline.submitSound2(0xB5, "ring");
        pipeline.bridge();

        Sonic2SoundRequestPipeline.CycleResult<String> jump = pipeline.cycleQueue();
        assertEquals(Sonic2SoundRequestPipeline.DecisionKind.ACCEPTED_SFX, jump.kind());
        assertEquals(0xA0, jump.request().requestByte());
        assertEquals(0xB5, pipeline.snapshot().queue1().requestByte());
        pipeline.dispatchQueuedRequest();

        Sonic2SoundRequestPipeline.CycleResult<String> ring = pipeline.cycleQueue();
        assertEquals(Sonic2SoundRequestPipeline.DecisionKind.ACCEPTED_SFX, ring.kind());
        assertEquals(0xB5, ring.request().requestByte());
    }

    @Test
    void sfxPriorityRejectsLowerAcceptsEqualAndHigherAndDoesNotLatchJumpPriority() {
        Sonic2SoundRequestPipeline<String> pipeline = pipelineWithRingPriority();
        assertEquals(0x70, pipeline.snapshot().sfxPriorityValue());

        pipeline.submitSound(0xD1, "lower");
        pipeline.bridge();
        assertEquals(Sonic2SoundRequestPipeline.DecisionKind.REJECTED_SFX, pipeline.cycleQueue().kind());
        assertEquals(0x70, pipeline.snapshot().sfxPriorityValue());

        pipeline.submitSound(0xCC, "equal");
        pipeline.bridge();
        assertEquals(Sonic2SoundRequestPipeline.DecisionKind.ACCEPTED_SFX, pipeline.cycleQueue().kind());
        pipeline.dispatchQueuedRequest();

        pipeline.submitSound(0xBF, "higher");
        pipeline.bridge();
        assertEquals(Sonic2SoundRequestPipeline.DecisionKind.ACCEPTED_SFX, pipeline.cycleQueue().kind());
        assertEquals(0x7F, pipeline.snapshot().sfxPriorityValue());
        pipeline.dispatchQueuedRequest();

        pipeline.submitSound(0xA0, "jump");
        pipeline.bridge();
        assertEquals(Sonic2SoundRequestPipeline.DecisionKind.ACCEPTED_SFX, pipeline.cycleQueue().kind());
        assertEquals(0x7F, pipeline.snapshot().sfxPriorityValue());
    }

    @Test
    void queueCycleUsesTheShippedF1ToF7PriorityTableOverreadButLeavesTheirDispatchAsANoOp() {
        int[][] sourceClosedPriorities = {
                {0xF1, 0x43}, {0xF2, 0x10}, {0xF3, 0x5A}, {0xF4, 0x10},
                {0xF5, 0x61}, {0xF6, 0x10}, {0xF7, 0x72}
        };

        for (int[] sourceClosedPriority : sourceClosedPriorities) {
            Sonic2SoundRequestPipeline<String> pipeline = new Sonic2SoundRequestPipeline<>();
            pipeline.submitSound(sourceClosedPriority[0], "overread-" + sourceClosedPriority[0]);
            pipeline.bridge();

            Sonic2SoundRequestPipeline.CycleResult<String> result = pipeline.cycleQueue();

            assertEquals(Sonic2SoundRequestPipeline.DecisionKind.ACCEPTED_SFX, result.kind());
            assertEquals(sourceClosedPriority[0], result.request().requestByte());
            assertEquals(sourceClosedPriority[1], pipeline.snapshot().sfxPriorityValue());
            assertEquals(Sonic2SoundRequestPipeline.DispatchKind.IGNORED_UNDEFINED_ID,
                    pipeline.dispatchQueuedRequest().kind());
        }
    }

    @Test
    void semanticPriorityClearCausesClearTheLatchButMusicInitialisationPreservesItAndDropsQueue2() {
        Sonic2SoundRequestPipeline<String> pipeline = pipelineWithRingPriority();
        pipeline.onSfxTrackStopped();
        assertEquals(0, pipeline.snapshot().sfxPriorityValue());

        pipeline = pipelineWithRingPriority();
        pipeline.onStopAllSfx();
        assertEquals(0, pipeline.snapshot().sfxPriorityValue());

        pipeline = pipelineWithRingPriority();
        pipeline.onSfxSuppressedDuringOneUpOrFadeIn();
        assertEquals(0, pipeline.snapshot().sfxPriorityValue());

        pipeline = pipelineWithRingPriority();
        pipeline.onOneUpStarted();
        assertEquals(0, pipeline.snapshot().sfxPriorityValue());

        pipeline = pipelineWithRingPriority();
        pipeline.submitSfx2ForTesting(0xCC, "queue2");
        pipeline.bridge();
        pipeline.onMusicPlaybackInitialized();
        assertEquals(0x70, pipeline.snapshot().sfxPriorityValue());
        assertEquals(new Sonic2SoundRequestPipeline.SlotState<>(0, null), pipeline.snapshot().queue2());

        pipeline = pipelineWithRingPriority();
        pipeline.submitMusic(0x93, "pointer-primary");
        pipeline.submitMusic(0x94, "pointer-alias");
        pipeline.bridge();
        assertEquals(0x94, pipeline.snapshot().voiceTablePointer());
        pipeline.onMusicPlaybackInitialized();
        assertEquals(0, pipeline.snapshot().voiceTablePointer());

        pipeline = new Sonic2SoundRequestPipeline<>();
        pipeline.submitMusic(0xFE, "pause");
        pipeline.bridge();
        assertEquals(0x7F, pipeline.snapshot().stopMusic());
        pipeline.onMusicPlaybackInitialized();
        assertEquals(0, pipeline.snapshot().stopMusic());
    }

    @Test
    void dispatchBoundaryModelsRingGloopAndSpindashSelectionWithoutCreatingPlayback() {
        Sonic2SoundRequestPipeline<String> pipeline = new Sonic2SoundRequestPipeline<>();

        assertEquals(0xCE, dispatch(pipeline, 0xB5, "ring-one").selectedRequestByte());
        assertEquals(0xB5, dispatch(pipeline, 0xB5, "ring-two").selectedRequestByte());
        pipeline.onSfxTrackStopped();
        assertEquals(Sonic2SoundRequestPipeline.DispatchKind.NOT_YET_DISPATCHED,
                dispatch(pipeline, 0xDA, "gloop-one").kind());
        assertEquals(Sonic2SoundRequestPipeline.DispatchKind.SUPPRESSED_GLOOP,
                dispatch(pipeline, 0xDA, "gloop-two").kind());
        assertEquals(0, dispatch(pipeline, 0xE0, "rev-one").spindashTransposeOffset());
        assertEquals(0x3C, pipeline.snapshot().spindashPlayingCounter());
        assertEquals(0, pipeline.snapshot().spindashExtraFrequencyIndex());
        assertTrue(pipeline.snapshot().spindashActive());
        for (int i = 0; i < 30; i++) {
            pipeline.finishDriverInvocation();
        }
        assertEquals(1, dispatch(pipeline, 0xE0, "rev-two").spindashTransposeOffset());
    }

    @Test
    void snapshotRestoreReplaysTheSameSourceQueuePointerAndTransformBoundariesWithoutEvents() {
        Sonic2SoundRequestPipeline<String> pipeline = new Sonic2SoundRequestPipeline<>();
        pipeline.submitMusic(0x82, "source");
        Sonic2SoundRequestPipeline.Snapshot<String> sourcePending = pipeline.snapshot();
        Sonic2SoundRequestPipeline.BridgeResult<String> firstBridge = pipeline.bridge();
        pipeline.restore(sourcePending);
        assertEquals(firstBridge, pipeline.bridge());

        pipeline.dispatchQueuedRequest();
        pipeline.submitSound(0xB5, "queue");
        pipeline.bridge();
        Sonic2SoundRequestPipeline.Snapshot<String> queuePending = pipeline.snapshot();
        Sonic2SoundRequestPipeline.CycleResult<String> firstCycle = pipeline.cycleQueue();
        pipeline.restore(queuePending);
        assertEquals(firstCycle, pipeline.cycleQueue());

        Sonic2SoundRequestPipeline.Snapshot<String> promoted = pipeline.snapshot();
        Sonic2SoundRequestPipeline.DispatchResult<String> promotedDispatch = pipeline.dispatchQueuedRequest();
        pipeline.restore(promoted);
        assertEquals(promotedDispatch, pipeline.dispatchQueuedRequest());

        Sonic2SoundRequestPipeline<String> rejectedPipeline = pipelineWithRingPriority();
        rejectedPipeline.submitSound(0xD1, "rejected");
        rejectedPipeline.bridge();
        rejectedPipeline.cycleQueue();
        Sonic2SoundRequestPipeline.Snapshot<String> rejected = rejectedPipeline.snapshot();
        rejectedPipeline.onSfxTrackStopped();
        rejectedPipeline.restore(rejected);
        assertEquals(0x70, rejectedPipeline.snapshot().sfxPriorityValue());

        pipeline.dispatchQueuedRequest();
        pipeline.submitMusic(0x93, "pointer-primary");
        pipeline.submitMusic(0x94, "pointer-alias");
        Sonic2SoundRequestPipeline.Snapshot<String> pointerPending = pipeline.snapshot();
        pipeline.bridge();
        assertEquals(0x94, pipeline.snapshot().voiceTablePointer());
        pipeline.restore(pointerPending);
        assertEquals(0, pipeline.snapshot().voiceTablePointer());
        pipeline.bridge();
        pipeline.dispatchQueuedRequest();

        dispatch(pipeline, 0xB5, "transform-one");
        Sonic2SoundRequestPipeline.Snapshot<String> transformPending = pipeline.snapshot();
        Sonic2SoundRequestPipeline.DispatchResult<String> expected = dispatch(pipeline, 0xB5, "transform-two");
        pipeline.restore(transformPending);
        assertEquals(expected, dispatch(pipeline, 0xB5, "transform-two"));
    }

    @Test
    void snapshotRejectsMismatchedByteAndPayloadState() {
        assertThrows(IllegalArgumentException.class, () -> new Sonic2SoundRequestPipeline.SlotState<String>(1, null));
        assertThrows(IllegalArgumentException.class, () -> new Sonic2SoundRequestPipeline.SlotState<>(0, "payload"));
        assertThrows(IllegalArgumentException.class, () -> new Sonic2SoundRequestPipeline.Snapshot<>(
                new Sonic2SoundRequestPipeline.SlotState<>(0, null),
                new Sonic2SoundRequestPipeline.SlotState<>(0, null),
                new Sonic2SoundRequestPipeline.SlotState<>(0, null),
                new Sonic2SoundRequestPipeline.SlotState<>(0, null),
                new Sonic2SoundRequestPipeline.SlotState<>(0, null),
                new Sonic2SoundRequestPipeline.SlotState<>(0, null),
                new Sonic2SoundRequestPipeline.SlotState<>(0, null),
                new Sonic2SoundRequestPipeline.SlotState<>(0, null),
                new Sonic2SoundRequestPipeline.QueueToPlayState<>(0x80, "not-ready"),
                0, 0, 0, 0, 0, 0, 0, false, 0, false));
        assertThrows(IllegalArgumentException.class, () -> new Sonic2SoundRequestPipeline.Snapshot<>(
                new Sonic2SoundRequestPipeline.SlotState<>(0, null),
                new Sonic2SoundRequestPipeline.SlotState<>(0, null),
                new Sonic2SoundRequestPipeline.SlotState<>(0, null),
                new Sonic2SoundRequestPipeline.SlotState<>(0, null),
                new Sonic2SoundRequestPipeline.SlotState<>(0, null),
                new Sonic2SoundRequestPipeline.SlotState<>(0, null),
                new Sonic2SoundRequestPipeline.SlotState<>(0, null),
                new Sonic2SoundRequestPipeline.SlotState<>(0, null),
                new Sonic2SoundRequestPipeline.QueueToPlayState<>(0x80, null),
                0, 0, 0, 0, 0, 0, 0x0C, false, 0, false));
    }

    private static Sonic2SoundRequestPipeline<String> pipelineWithRingPriority() {
        Sonic2SoundRequestPipeline<String> pipeline = new Sonic2SoundRequestPipeline<>();
        pipeline.submitSound(0xB5, "ring");
        pipeline.bridge();
        pipeline.cycleQueue();
        pipeline.dispatchQueuedRequest();
        return pipeline;
    }

    private static Sonic2SoundRequestPipeline.DispatchResult<String> dispatch(
            Sonic2SoundRequestPipeline<String> pipeline, int requestByte, String payload) {
        pipeline.submitSound(requestByte, payload);
        pipeline.bridge();
        pipeline.cycleQueue();
        return pipeline.dispatchQueuedRequest();
    }
}
