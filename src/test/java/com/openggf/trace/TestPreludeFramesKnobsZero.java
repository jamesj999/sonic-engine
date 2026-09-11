package com.openggf.trace;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Verifies replay-only title-card workaround knobs stay inactive for S3K. */
class TestPreludeFramesKnobsZero {

    @Test
    void nullTraceReturnsZeroForBothKnobs() {
        assertEquals(0, TraceReplayBootstrap.sidekickTitleCardPreludeFramesForTraceReplay(null));
        assertEquals(0, TraceReplayBootstrap.levelObjectTitleCardPreludeFramesForTraceReplay(null));
    }

    @Test
    void emptyFrameListReturnsZeroForBothKnobs() {
        TraceData s2Empty = TraceFixtures.trace(metadata("s2", "ehz", 0, 0, List.of("sonic", "tails")),
                List.of());
        TraceData s3kEmpty = TraceFixtures.trace(metadata("s3k", "cnz", 5, 0, List.of("sonic", "tails")),
                List.of());

        assertEquals(0, TraceReplayBootstrap.sidekickTitleCardPreludeFramesForTraceReplay(s2Empty));
        assertEquals(0, TraceReplayBootstrap.levelObjectTitleCardPreludeFramesForTraceReplay(s2Empty));
        assertEquals(0, TraceReplayBootstrap.sidekickTitleCardPreludeFramesForTraceReplay(s3kEmpty));
        assertEquals(0, TraceReplayBootstrap.levelObjectTitleCardPreludeFramesForTraceReplay(s3kEmpty));
    }

    @Test
    void s3kLegacySeedCapabilityCannotRequestReplayPrelude() {
        // Mirrors the old S3K CNZ metadata. The capability remains parseable,
        // but Task 1's production fresh-playable lifecycle now owns frame 0.
        TraceFrame seed = buildFrame(0, /* gfc */ 1,
                /* xSpeed */ (short) 0, /* ySpeed */ (short) 0, /* gSpeed */ (short) 0,
                /* xSub */ 0, /* ySub */ 0);
        TraceFrame next = buildFrame(1, /* gfc */ 2,
                (short) 0, (short) 0, (short) 0, 0, 0);
        TraceData trace = TraceFixtures.trace(
                metadata("s3k", "cnz", 5, 0, List.of("sonic", "tails"),
                        List.of("sidekick_seed_frame_prelude")),
                List.of(seed, next));

        assertEquals(0, TraceReplayBootstrap.sidekickTitleCardPreludeFramesForTraceReplay(trace));
        assertEquals(0, TraceReplayBootstrap.levelObjectTitleCardPreludeFramesForTraceReplay(trace));
        assertEquals(0, TraceReplayBootstrap.preTraceOscillationFramesForTraceReplay(trace, -1));
        assertEquals(TraceReplayBootstrap.ReplayStartState.DEFAULT,
                TraceReplayBootstrap.applyReplayStartStateForTraceReplay(trace, null));
    }

    @Test
    void s3kSonicAndTailsSeedFrameShapeWithoutExplicitMetadataReturnsZero() {
        TraceFrame seed = buildFrame(0, /* gfc */ 1,
                /* xSpeed */ (short) 0, /* ySpeed */ (short) 0, /* gSpeed */ (short) 0,
                /* xSub */ 0, /* ySub */ 0);
        TraceFrame next = buildFrame(1, /* gfc */ 2,
                (short) 0, (short) 0, (short) 0, 0, 0);
        TraceData trace = TraceFixtures.trace(
                metadata("s3k", "cnz", 5, 0, List.of("sonic", "tails")),
                List.of(seed, next));

        assertEquals(0, TraceReplayBootstrap.sidekickTitleCardPreludeFramesForTraceReplay(trace),
                "S3K replay scheduling must not infer a seed prelude from frame-zero outcome shape.");
        assertEquals(0, TraceReplayBootstrap.levelObjectTitleCardPreludeFramesForTraceReplay(trace));
        assertEquals(0, TraceReplayBootstrap.preTraceOscillationFramesForTraceReplay(trace, -1));
    }

    @Test
    void s3kCompleteRunVisibleVelocityHoldShapeDoesNotControlPhase() {
        TraceFrame seed = buildFrame(0, /* gfc */ 1,
                /* xSpeed */ (short) 0x18, (short) 0, (short) 0x18, 0, 0);
        TraceFrame next = buildFrame(1, /* gfc */ 1,
                (short) 0x18, (short) 0, (short) 0x18, 0, 0);
        TraceData trace = TraceFixtures.trace(
                metadataWithProfile(
                        "s3k", "mgz", 4, 0, List.of("sonic", "tails"), "complete_run"),
                List.of(seed, next));

        assertEquals(0, TraceReplayBootstrap.sidekickTitleCardPreludeFramesForTraceReplay(trace));
        assertEquals(0, TraceReplayBootstrap.levelObjectTitleCardPreludeFramesForTraceReplay(trace),
                "represented complete-run restoration is a semantic bootstrap envelope, "
                        + "not a metadata-selected replay prelude knob");
        assertEquals(TraceExecutionPhase.FULL_LEVEL_FRAME,
                TraceReplayBootstrap.phaseForReplay(trace, null, seed),
                "Matching frame-zero/next-row state with visible velocity is an outcome shape, "
                        + "not phase scheduling evidence.");
    }

    @Test
    void s2FirstFrameGameplayCounterOneRunsNativeSidekickPrelude() {
        // Mirrors S2 native-prelude traces whose first compared row has already
        // seen the title-card Obj01/Obj02 sidekick timing.
        TraceFrame seed = buildFrame(0, /* gfc */ 1,
                (short) 0, (short) 0, (short) 0, 0, 0);
        TraceFrame next = buildFrame(1, /* gfc */ 2,
                (short) 0, (short) 0, (short) 0, 0, 0);
        TraceData trace = TraceFixtures.trace(
                metadata("s2", "ehz", 0, 0, List.of("sonic", "tails"),
                        List.of("native_prelude_bootstrap")),
                List.of(seed, next));

        assertEquals(26, TraceReplayBootstrap.sidekickTitleCardPreludeFramesForTraceReplay(trace),
                "S2 native-prelude sidekick timing is a game-level title-card execution rule.");
        assertEquals(0, TraceReplayBootstrap.levelObjectTitleCardPreludeFramesForTraceReplay(trace),
                "S2 level-object metadata knob stays zero; Tornado object preludes need live ObjB2 shape.");
        assertEquals(26, TraceReplayBootstrap.s2GenericObjectTitleCardPreludeFramesForTraceReplay(trace),
                "Normal S2 routes fall back to generic title-card object ticks when no live Tornado prelude is active.");
    }

    @Test
    void s2FirstFrameGameplayCounterNotOneReturnsZero() {
        // S2 trace whose first frame already saw multiple LevelLoop ticks - old code also
        // returned 0 here; verify the knob still returns 0.
        TraceFrame seed = buildFrame(0, /* gfc */ 5,
                (short) 0, (short) 0, (short) 0, 0, 0);
        TraceData trace = TraceFixtures.trace(
                metadata("s2", "ehz", 0, 0, List.of("sonic", "tails")),
                List.of(seed));

        assertEquals(0, TraceReplayBootstrap.sidekickTitleCardPreludeFramesForTraceReplay(trace));
        assertEquals(0, TraceReplayBootstrap.levelObjectTitleCardPreludeFramesForTraceReplay(trace));
    }

    @Test
    void s2SoloRunWithoutSidekickReturnsZero() {
        // S2 single-character trace — old sidekick knob returned 0 because no sidekicks.
        TraceFrame seed = buildFrame(0, /* gfc */ 1,
                (short) 0, (short) 0, (short) 0, 0, 0);
        TraceData trace = TraceFixtures.trace(
                metadata("s2", "ehz", 0, 0, List.of("sonic")),
                List.of(seed));

        assertEquals(0, TraceReplayBootstrap.sidekickTitleCardPreludeFramesForTraceReplay(trace));
        // levelObject knob did not require sidekick; verify unconditional zero.
        assertEquals(0, TraceReplayBootstrap.levelObjectTitleCardPreludeFramesForTraceReplay(trace));
    }

    @Test
    void s1FirstGameplayRowRunsOneLevelStartObjectPrelude() {
        // S1 runs ObjPosLoad followed by ExecuteObjects once before
        // Level_MainLoop starts incrementing v_framecount
        // (docs/s1disasm/sonic.asm:2875-2876, 2985-3003).
        TraceFrame seed = buildFrame(0, /* gfc */ 1,
                (short) 0, (short) 0, (short) 0, 0, 0);
        TraceFrame next = buildFrame(1, /* gfc */ 2,
                (short) 0, (short) 0, (short) 0, 0, 0);
        TraceData trace = TraceFixtures.trace(
                metadata("s1", "ghz", 0, 0, List.of("sonic")),
                List.of(seed, next));

        assertEquals(0, TraceReplayBootstrap.sidekickTitleCardPreludeFramesForTraceReplay(trace));
        assertEquals(1, TraceReplayBootstrap.levelObjectTitleCardPreludeFramesForTraceReplay(trace));
    }

    @Test
    void s1LaterGameplayRowReturnsZeroObjectPrelude() {
        TraceFrame seed = buildFrame(0, /* gfc */ 5,
                (short) 0, (short) 0, (short) 0, 0, 0);
        TraceData trace = TraceFixtures.trace(
                metadata("s1", "ghz", 0, 0, List.of("sonic")),
                List.of(seed));

        assertEquals(0, TraceReplayBootstrap.sidekickTitleCardPreludeFramesForTraceReplay(trace));
        assertEquals(0, TraceReplayBootstrap.levelObjectTitleCardPreludeFramesForTraceReplay(trace));
    }

    @Test
    void s3kAizIntroTraceStillReturnsZero() {
        // Legacy AIZ intro trace path used to short-circuit to 0; verify that remains true.
        TraceFrame seed = buildFrame(0, /* gfc */ 0,
                (short) 0, (short) 0, (short) 0, 0, 0);
        TraceData trace = TraceFixtures.trace(
                metadata("s3k", "aiz", 0, 1, List.of("sonic", "tails")),
                List.of(seed));

        assertEquals(0, TraceReplayBootstrap.sidekickTitleCardPreludeFramesForTraceReplay(trace));
        assertEquals(0, TraceReplayBootstrap.levelObjectTitleCardPreludeFramesForTraceReplay(trace));
    }

    private static TraceFrame buildFrame(int frame, int gameplayFrameCounter,
                                          short xSpeed, short ySpeed, short gSpeed,
                                          int xSub, int ySub) {
        return new TraceFrame(
                frame,
                /* input */ 0,
                /* x */ (short) 0,
                /* y */ (short) 0,
                xSpeed,
                ySpeed,
                gSpeed,
                /* angle */ (byte) 0,
                /* air */ false,
                /* rolling */ false,
                /* groundMode */ 0,
                xSub,
                ySub,
                /* routine */ -1,
                /* cameraX */ -1,
                /* cameraY */ -1,
                /* rings */ -1,
                /* statusByte */ -1,
                gameplayFrameCounter,
                /* standOnObj */ -1,
                /* vblankCounter */ -1,
                /* lagCounter */ -1);
    }

    private static TraceMetadata metadata(String game, String zone, int zoneId, int act,
                                           List<String> characters) {
        return metadata(game, zone, zoneId, act, characters, null);
    }

    private static TraceMetadata metadata(String game, String zone, int zoneId, int act,
                                           List<String> characters,
                                           List<String> auxSchemaExtras) {
        return metadata(game, zone, zoneId, act, characters, auxSchemaExtras, null);
    }

    private static TraceMetadata metadataWithProfile(
            String game, String zone, int zoneId, int act,
            List<String> characters, String traceProfile) {
        return metadata(game, zone, zoneId, act, characters, null, traceProfile);
    }

    private static TraceMetadata metadata(String game, String zone, int zoneId, int act,
                                           List<String> characters,
                                           List<String> auxSchemaExtras,
                                           String traceProfile) {
        return new TraceMetadata(
                game,
                zone,
                zoneId,
                act,
                /* bk2FrameOffset */ 0,
                /* initialVblankCounter */ null,
                /* traceFrameCount */ 0,
                /* startXHex */ "0x0000",
                /* startYHex */ "0x0000",
                /* recordingDate */ null,
                /* recorder */ null,
                /* recorderVersion */ null,
                /* traceSchema */ 5,
                traceProfile,
                /* bizhawkVersion */ null,
                /* genesisCore */ null,
                /* auxSchemaExtras */ auxSchemaExtras,
                /* romZoneId */ null,
                /* route */ null,
                /* sourceBk2 */ null,
                /* romChecksum */ null,
                /* notes */ null,
                /* characters */ new ArrayList<>(characters),
                /* mainCharacter */ characters.isEmpty() ? null : characters.get(0),
                /* sidekicks */ characters.size() > 1
                        ? new ArrayList<>(characters.subList(1, characters.size()))
                        : List.of(),
                /* preTraceOscFrames */ 0,
                /* rngSeedHex */ null,
                /* traceType */ null,
                /* inputSource */ null,
                /* creditsDemoIndex */ null,
                /* creditsDemoSlug */ null,
                /* specialStageIndex */ null,
                /* runId */ null,
                /* segmentIndex */ null,
                /* bonusStageType */ null,
                /* freshLoad */ null,
                /* vIntRunCount */ null);
    }
}
