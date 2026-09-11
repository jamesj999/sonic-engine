package com.openggf.game.sonic2.specialstage;

import com.openggf.game.sonic2.debug.Sonic2SpecialStageSpriteDebug;
import com.openggf.level.Palette;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TestSonic2SpecialStageRewindSnapshot {
    @Test
    void restoreReconstructsDerivedTrackPlanesForImmediateDrawAndReusesDecodeCache() throws Exception {
        Sonic2SpecialStageManager manager = new Sonic2SpecialStageManager();
        seedMinimalInitializedGraph(manager);
        Sonic2TrackAnimator animator = (Sonic2TrackAnimator) get(manager, "trackAnimator");
        set(animator, "orientationFlipped", true);
        int restoredFrameIndex = animator.getCurrentTrackFrameIndex();
        int alignmentFrameIndex = (restoredFrameIndex + 1) % Sonic2SpecialStageConstants.TRACK_FRAME_COUNT;
        byte[] restoredFrameData = minimalTrackFrame(3);
        byte[] alignmentFrameData = minimalTrackFrame(7);
        byte[][] trackFrames = new byte[Sonic2SpecialStageConstants.TRACK_FRAME_COUNT][];
        trackFrames[restoredFrameIndex] = restoredFrameData;
        trackFrames[alignmentFrameIndex] = alignmentFrameData;
        set(manager, "trackFrames", trackFrames);
        set(manager, "alignmentTestMode", true);
        set(manager, "alignmentTrackFrameIndex", alignmentFrameIndex);
        set(manager, "initialized", true);
        set(manager, "planeDebugMode", planeMode("PLANE_A_ONLY"));
        RecordingTrackRenderer renderer = new RecordingTrackRenderer();
        set(manager, "renderer", renderer);
        Sonic2TrackFrameDecoder.invalidateDecodedFrameCache();

        int[] expectedTrack = Sonic2TrackFrameDecoder.decodeFrame(restoredFrameData, true);
        int[] expectedAlignmentTrack = Sonic2TrackFrameDecoder.decodeFrame(alignmentFrameData, false);

        set(manager, "decodedTrackFrame", new int[] { 1, 2, 3 });
        set(manager, "lastDecodedFrameIndex", restoredFrameIndex);
        set(manager, "lastDecodedFlipped", true);
        set(manager, "alignmentDecodedTrackFrame", new int[] { 4, 5, 6 });
        set(manager, "alignmentLastDecodedFrameIndex", alignmentFrameIndex);

        Sonic2SpecialStageSnapshot snapshot = manager.captureRewindSnapshot();

        java.util.Set<String> snapshotFields = Arrays.stream(Sonic2SpecialStageSnapshot.class.getDeclaredFields())
                .map(Field::getName)
                .collect(java.util.stream.Collectors.toSet());
        assertFalse(snapshotFields.contains("decodedTrackFrame"));
        assertFalse(snapshotFields.contains("lastDecodedFrameIndex"));
        assertFalse(snapshotFields.contains("lastDecodedFlipped"));
        assertFalse(snapshotFields.contains("alignmentDecodedTrackFrame"));
        assertFalse(snapshotFields.contains("alignmentLastDecodedFrameIndex"));

        set(animator, "orientationFlipped", false);
        set(animator, "currentFrameInSegment", 1);
        set(manager, "alignmentTrackFrameIndex", (alignmentFrameIndex + 1)
                % Sonic2SpecialStageConstants.TRACK_FRAME_COUNT);
        manager.restoreRewindSnapshot(snapshot);

        int[] firstRestoredTrack = (int[]) get(manager, "decodedTrackFrame");
        int[] firstRestoredAlignment = (int[]) get(manager, "alignmentDecodedTrackFrame");
        assertNotNull(firstRestoredTrack);
        assertNotNull(firstRestoredAlignment);
        assertArrayEquals(expectedTrack, firstRestoredTrack);
        assertArrayEquals(expectedAlignmentTrack, firstRestoredAlignment);
        assertEquals(restoredFrameIndex, get(manager, "lastDecodedFrameIndex"));
        assertEquals(true, get(manager, "lastDecodedFlipped"));
        assertEquals(alignmentFrameIndex, get(manager, "alignmentLastDecodedFrameIndex"));
        assertEquals(2, Sonic2TrackFrameDecoder.decodedFrameBuildCountForTesting());

        // Exact-keyframe/held rewind restores must return safe manager-local copies
        // without rebuilding the unchanged global fixed-domain entries.
        firstRestoredTrack[0] ^= 0x7FFF;
        firstRestoredAlignment[0] ^= 0x7FFF;
        manager.restoreRewindSnapshot(snapshot);

        int[] secondRestoredTrack = (int[]) get(manager, "decodedTrackFrame");
        int[] secondRestoredAlignment = (int[]) get(manager, "alignmentDecodedTrackFrame");
        assertNotSame(firstRestoredTrack, secondRestoredTrack);
        assertNotSame(firstRestoredAlignment, secondRestoredAlignment);
        assertArrayEquals(expectedTrack, secondRestoredTrack);
        assertArrayEquals(expectedAlignmentTrack, secondRestoredAlignment);
        assertEquals(2, Sonic2TrackFrameDecoder.decodedFrameBuildCountForTesting());

        manager.draw();
        assertEquals(alignmentFrameIndex, renderer.lastTrackFrameIndex);
        assertSame(secondRestoredAlignment, renderer.lastTrackTiles);
        assertArrayEquals(expectedAlignmentTrack, renderer.lastTrackTiles);

        set(manager, "alignmentTestMode", false);
        renderer.lastTrackFrameIndex = -1;
        renderer.lastTrackTiles = null;
        manager.draw();
        assertEquals(restoredFrameIndex, renderer.lastTrackFrameIndex);
        assertSame(secondRestoredTrack, renderer.lastTrackTiles);
        assertArrayEquals(expectedTrack, renderer.lastTrackTiles);
    }

    @Test
    void managerSnapshotRestoresScalarsNestedStateAndPalettes() throws Exception {
        Sonic2SpecialStageManager manager = new Sonic2SpecialStageManager();
        manager.reset();
        seedFullInitializedGraph(manager);
        Palette[] palettes = createPalettes(10);
        set(manager, "palettes", palettes);
        byte capturedPaletteRed = palettes[3].getColor(11).r;

        Sonic2SpecialStageSnapshot snapshot = manager.captureRewindSnapshot();
        mutateManagerAwayFromSnapshot(manager, palettes);

        manager.restoreRewindSnapshot(snapshot);

        assertEquals(true, get(manager, "initialized"));
        assertEquals(2, get(manager, "currentStage"));
        assertEquals(Sonic2SpecialStageManager.ResultState.RUNNING, get(manager, "resultState"));
        assertEquals(true, get(manager, "emeraldCollected"));
        assertEquals(123, get(manager, "frameCounter"));
        assertEquals(87, get(manager, "renderFrameCounter"));
        assertEquals(0x11, get(manager, "heldButtons"));
        assertEquals(0x22, get(manager, "pressedButtons"));
        assertEquals(0x33, get(manager, "p2HeldButtons"));
        assertEquals(0x44, get(manager, "p2LogicalButtons"));
        assertEquals(true, get(manager, "recurringMainPassPending"));
        assertEquals(0x55, get(manager, "pendingMainHeldButtons"));
        assertEquals(0x66, get(manager, "pendingMainPressedButtons"));
        assertEquals(0x77, get(manager, "pendingMainP2HeldButtons"));
        assertEquals(0x88, get(manager, "pendingMainP2LogicalButtons"));
        assertEquals(true, get(manager, "pendingMainCheckpointStep"));
        assertEquals(0x99, get(manager, "previousPhysicalHeldButtons"));
        assertEquals(0xAA, get(manager, "previousPhysicalPressedButtons"));
        assertEquals(0xBB, get(manager, "previousPhysicalP2HeldButtons"));
        assertEquals(0xCC, get(manager, "previousPhysicalP2LogicalButtons"));
        assertEquals(7, get(manager, "tailsControlCounter"));
        assertArrayEquals(sequence(0x100, 16), (int[]) get(manager, "tailsCtrlRecordBuf"));
        assertEquals(5, get(manager, "lastDrawingIndex"));
        assertEquals(true, get(manager, "checkpointRainbowPaletteActive"));
        assertEquals(2, get(manager, "rainbowPaletteCycleIndex"));
        assertEquals(true, get(manager, "pendingCheckpoint"));
        assertEquals(3, get(manager, "pendingCheckpointNumber"));
        assertEquals(90, get(manager, "pendingRingRequirement"));
        assertEquals(75, get(manager, "pendingRingsCollected"));
        assertEquals(true, get(manager, "pendingFinalCheckpoint"));
        assertEquals(120, get(manager, "currentRingRequirement"));
        assertEquals(true, get(manager, "spriteDebugMode"));
        assertEquals(true, ((Sonic2SpecialStageSpriteDebug) get(manager, "debugSprites")).isEnabled());
        assertSame(planeMode("PLANE_A_ONLY"), get(manager, "planeDebugMode"));
        assertEquals(true, get(manager, "alignmentTestMode"));
        assertEquals(true, get(manager, "alignmentTestSavedRainbowPalette"));
        assertEquals(true, get(manager, "alignmentPendingCheckpoint"));
        assertEquals(3, get(manager, "alignmentFrameIndex"));
        assertEquals(4, get(manager, "alignmentFrameTimer"));
        assertEquals(5, get(manager, "alignmentTrackFrameIndex"));
        assertEquals(2, get(manager, "alignmentDrawingIndex"));
        assertEquals(9, get(manager, "alignmentTriggerOffsetFrames"));
        assertEquals(1.25, (double) get(manager, "alignmentRainbowSpeedScale"), 0.0001);
        assertEquals(0.5, (double) get(manager, "alignmentRainbowSpeedAccumulator"), 0.0001);
        assertEquals(true, get(manager, "alignmentStepByTrackFrame"));
        assertEquals(true, get(manager, "liveLagSimulationEnabled"));
        assertEquals(1_234_567L, get(manager, "diagnosticWallStartTime"));
        assertEquals(8, get(manager, "diagnosticUpdateCount"));
        assertEquals(9, get(manager, "diagnosticTrackAdvances"));
        assertEquals(2_345_678L, get(manager, "lastFrameTime"));
        assertEquals(10, get(manager, "frameSampleCount"));
        assertEquals(3_456_789L, get(manager, "frameSampleSum"));
        assertEquals(17, get(manager, "skydomeScrollX"));
        assertEquals(true, get(manager, "alternateScrollBuffer"));
        assertEquals(true, get(manager, "lastAlternateScrollBuffer"));
        assertEquals(4, get(manager, "drawingIndex"));
        assertEquals(Sonic2SpecialStageManager.PlayerBootstrapPhase.WAIT_SECOND_DURATION_WRAP,
                get(manager, "playerBootstrapPhase"));
        assertEquals(33, get(manager, "lastAnimFrame"));
        assertEquals(-7, get(manager, "vScrollBG"));
        assertEquals(101, get(manager, "hScrollDebugTotal"));
        assertEquals(11, get(manager, "hScrollDebugFrames"));
        assertEquals(12, get(manager, "lastDebugSegmentIndex"));

        Palette[] restoredPalettes = (Palette[]) get(manager, "palettes");
        assertEquals(capturedPaletteRed, restoredPalettes[3].getColor(11).r);
        assertNotSame(snapshot.palettes, restoredPalettes);

        Sonic2TrackAnimator animator = (Sonic2TrackAnimator) get(manager, "trackAnimator");
        assertEquals(4, get(animator, "currentSegmentIndex"));
        assertEquals(7, get(animator, "currentFrameInSegment"));
        assertEquals(9, get(animator, "speedFactor"));
        assertEquals(true, get(animator, "speedChangePending"));
        assertEquals(true, get(animator, "orientationFlipped"));

        Sonic2SpecialStagePlayer sonic = (Sonic2SpecialStagePlayer) get(manager, "sonicPlayer");
        Sonic2SpecialStagePlayer tails = (Sonic2SpecialStagePlayer) get(manager, "tailsPlayer");
        assertEquals(0x1234, get(sonic, "ssXPos"));
        assertEquals(0x2345, get(tails, "ssXPos"));
        assertSame(tails, sonic.getOtherPlayerForRewind());
        assertSame(sonic, tails.getOtherPlayerForRewind());

        Sonic2SpecialStageIntro intro = (Sonic2SpecialStageIntro) get(manager, "intro");
        assertEquals(Sonic2SpecialStageIntro.Phase.MESSAGE_FLYOUT, intro.getCurrentPhase());
        assertEquals(12, get(intro, "phaseTimer"));
        assertEquals(34, intro.getFrameCounter());

        Sonic2SpecialStageManager.Sonic2SpecialStageObjectManager objectManager =
                (Sonic2SpecialStageManager.Sonic2SpecialStageObjectManager) get(manager, "objectManager");
        assertEquals(44, objectManager.getRingsCollected());
        assertEquals(55, objectManager.getPerfectRingsTotal());
        assertEquals(3, objectManager.getCurrentSpecialAct());
        assertEquals(1, objectManager.getActiveObjects().size());
        Sonic2SpecialStageRing restoredRing =
                assertInstanceOf(Sonic2SpecialStageRing.class, objectManager.getActiveObjects().get(0));
        assertEquals(0x66, restoredRing.getAngle());

        Sonic2SpecialStageCheckpoint checkpoint =
                (Sonic2SpecialStageCheckpoint) get(manager, "checkpoint");
        assertEquals(Sonic2SpecialStageCheckpoint.MessagePhase.RAINBOW_RINGS, checkpoint.getPhase());
        assertEquals(33, get(checkpoint, "phaseTimer"));
        assertEquals(2, checkpoint.getCurrentCheckpoint());
        assertEquals(80, checkpoint.getRingRequirement());
        assertEquals(64, checkpoint.getRingsCollected());

        Sonic2SpecialStageCheckpoint alignmentCheckpoint =
                (Sonic2SpecialStageCheckpoint) get(manager, "alignmentCheckpoint");
        assertNotNull(alignmentCheckpoint);
        assertEquals(Sonic2SpecialStageCheckpoint.MessagePhase.RAINBOW_RINGS, alignmentCheckpoint.getPhase());
        assertEquals(21, get(alignmentCheckpoint, "phaseTimer"));

        Sonic2SpecialStageRenderer renderer = (Sonic2SpecialStageRenderer) get(manager, "renderer");
        assertSame(get(manager, "players"), get(renderer, "players"));
        assertSame(intro, get(renderer, "intro"));
        assertSame(alignmentCheckpoint, get(renderer, "checkpoint"));
    }

    @Test
    void managerSnapshotRestoresEmeraldAndCheckpointPalettePhases() throws Exception {
        assertPalettePhaseRoundTrips(false, 0, new int[] { 0x0EE, 0x044, 0x000 });
        assertPalettePhaseRoundTrips(false, 0, new int[] { 0x0EE, 0x088, 0x044 });
        assertPalettePhaseRoundTrips(true, 0, new int[] { 0x0EE, 0x0CC, 0x088 });
        assertPalettePhaseRoundTrips(true, 2, new int[] { 0x0EE, 0x044, 0x088 });
    }

    private static void assertPalettePhaseRoundTrips(boolean rainbowActive,
                                                     int rainbowCycleIndex,
                                                     int[] genesisColors) throws Exception {
        Sonic2SpecialStageManager manager = new Sonic2SpecialStageManager();
        seedMinimalInitializedGraph(manager);
        Palette[] palettes = createPalettes(0);
        for (int i = 0; i < genesisColors.length; i++) {
            palettes[3].setColor(11 + i,
                    Sonic2SpecialStagePalette.genesisColorToPaletteColor(genesisColors[i]));
        }
        set(manager, "palettes", palettes);
        set(manager, "checkpointRainbowPaletteActive", rainbowActive);
        set(manager, "rainbowPaletteCycleIndex", rainbowCycleIndex);

        Sonic2SpecialStageSnapshot snapshot = manager.captureRewindSnapshot();
        palettes[3].setColor(11, new Palette.Color((byte) 1, (byte) 2, (byte) 3));
        palettes[3].setColor(12, new Palette.Color((byte) 4, (byte) 5, (byte) 6));
        palettes[3].setColor(13, new Palette.Color((byte) 7, (byte) 8, (byte) 9));
        set(manager, "checkpointRainbowPaletteActive", !rainbowActive);
        set(manager, "rainbowPaletteCycleIndex", 99);

        manager.restoreRewindSnapshot(snapshot);

        Palette[] restored = (Palette[]) get(manager, "palettes");
        for (int i = 0; i < genesisColors.length; i++) {
            Palette.Color expected = Sonic2SpecialStagePalette.genesisColorToPaletteColor(genesisColors[i]);
            Palette.Color actual = restored[3].getColor(11 + i);
            assertEquals(expected.r, actual.r);
            assertEquals(expected.g, actual.g);
            assertEquals(expected.b, actual.b);
        }
        assertEquals(rainbowActive, get(manager, "checkpointRainbowPaletteActive"));
        assertEquals(rainbowCycleIndex, get(manager, "rainbowPaletteCycleIndex"));
    }

    @Test
    void restoreFailsFastWhenPlayerTopologyChanges() throws Exception {
        Sonic2SpecialStageManager manager = new Sonic2SpecialStageManager();
        seedMinimalInitializedGraph(manager);
        Sonic2SpecialStageSnapshot snapshot = manager.captureRewindSnapshot();
        ((java.util.List<?>) get(manager, "players")).clear();

        assertThrows(IllegalStateException.class, () -> manager.restoreRewindSnapshot(snapshot));
    }

    @Test
    void restoreBindsRendererToAlignmentCheckpointWhenAlignmentModeIsActive() throws Exception {
        Sonic2SpecialStageManager manager = new Sonic2SpecialStageManager();
        seedMinimalInitializedGraph(manager);
        Sonic2SpecialStageRenderer renderer = new Sonic2SpecialStageRenderer(null);
        Sonic2SpecialStageCheckpoint normalCheckpoint = new Sonic2SpecialStageCheckpoint();
        Sonic2SpecialStageCheckpoint alignmentCheckpoint = new Sonic2SpecialStageCheckpoint();
        set(manager, "renderer", renderer);
        set(manager, "checkpoint", normalCheckpoint);
        set(manager, "alignmentCheckpoint", alignmentCheckpoint);
        set(manager, "alignmentTestMode", true);
        renderer.setCheckpoint(normalCheckpoint);

        Sonic2SpecialStageSnapshot snapshot = manager.captureRewindSnapshot();
        renderer.setCheckpoint(normalCheckpoint);

        manager.restoreRewindSnapshot(snapshot);

        assertSame(alignmentCheckpoint, get(renderer, "checkpoint"));
    }

    private static void seedMinimalInitializedGraph(Sonic2SpecialStageManager manager) throws Exception {
        Sonic2TrackAnimator animator = new Sonic2TrackAnimator(null);
        animator.initializeWithMockLayout();
        set(manager, "trackAnimator", animator);

        Sonic2SpecialStagePlayer sonic = new Sonic2SpecialStagePlayer(
                Sonic2SpecialStagePlayer.PlayerType.SONIC, true, manager);
        ArrayList<Sonic2SpecialStagePlayer> players = new ArrayList<>();
        players.add(sonic);
        set(manager, "players", players);
        set(manager, "sonicPlayer", sonic);
        set(manager, "tailsPlayer", null);

        Sonic2SpecialStageIntro intro = new Sonic2SpecialStageIntro();
        intro.initialize(0, 50);
        set(manager, "intro", intro);

        Sonic2SpecialStageManager.Sonic2SpecialStageObjectManager objectManager =
                new Sonic2SpecialStageManager.Sonic2SpecialStageObjectManager(null);
        set(manager, "objectManager", objectManager);

        Sonic2SpecialStageCheckpoint checkpoint = new Sonic2SpecialStageCheckpoint();
        set(manager, "checkpoint", checkpoint);
        set(manager, "alignmentCheckpoint", null);
    }

    private static void seedFullInitializedGraph(Sonic2SpecialStageManager manager) throws Exception {
        Sonic2TrackAnimator animator = new Sonic2TrackAnimator(null);
        animator.initializeWithMockLayout();
        set(animator, "currentSegmentIndex", 4);
        set(animator, "currentFrameInSegment", 7);
        set(animator, "frameDelayCounter", 2);
        set(animator, "currentSegmentType", 3);
        set(animator, "currentSegmentFlipped", true);
        set(animator, "speedFactor", 9);
        set(animator, "speedChangePending", true);
        set(animator, "stageComplete", true);
        set(animator, "orientationFlipped", true);
        set(animator, "lastOrientationFrame", 12);
        set(manager, "trackAnimator", animator);

        Sonic2SpecialStagePlayer sonic = new Sonic2SpecialStagePlayer(
                Sonic2SpecialStagePlayer.PlayerType.SONIC, true, manager);
        Sonic2SpecialStagePlayer tails = new Sonic2SpecialStagePlayer(
                Sonic2SpecialStagePlayer.PlayerType.TAILS, false, manager);
        sonic.setOtherPlayer(tails);
        tails.setOtherPlayer(sonic);
        set(sonic, "ssXPos", 0x1234);
        set(tails, "ssXPos", 0x2345);
        ArrayList<Sonic2SpecialStagePlayer> players = new ArrayList<>();
        players.add(sonic);
        players.add(tails);
        set(manager, "players", players);
        set(manager, "sonicPlayer", sonic);
        set(manager, "tailsPlayer", tails);

        Sonic2SpecialStageIntro intro = new Sonic2SpecialStageIntro();
        intro.initialize(0, 50);
        set(intro, "currentPhase", Sonic2SpecialStageIntro.Phase.MESSAGE_FLYOUT);
        set(intro, "phaseTimer", 12);
        set(intro, "frameCounter", 34);
        set(manager, "intro", intro);

        Sonic2SpecialStageManager.Sonic2SpecialStageObjectManager objectManager =
                new Sonic2SpecialStageManager.Sonic2SpecialStageObjectManager(null);
        Sonic2SpecialStageRing ring = new Sonic2SpecialStageRing();
        ring.initialize(32, 0x40);
        set(ring, "angle", 0x66);
        objectManager.getActiveObjects().add(ring);
        set(objectManager, "objectLocationData", new byte[] { 1, 2, 3 });
        set(objectManager, "stageOffsets", new int[] { 10, 20, 30 });
        set(objectManager, "currentPosition", 9);
        set(objectManager, "currentStage", 2);
        set(objectManager, "lastProcessedSegment", 4);
        set(objectManager, "ringsCollected", 44);
        set(objectManager, "perfectRingsTotal", 55);
        set(objectManager, "currentSpecialAct", 3);
        set(objectManager, "noCheckpointFlag", true);
        set(objectManager, "noCheckpointMsgFlag", true);
        set(objectManager, "ringsToGoEnabled", true);
        set(objectManager, "emeraldSpawned", true);
        set(manager, "objectManager", objectManager);

        Sonic2SpecialStageCheckpoint checkpoint = new Sonic2SpecialStageCheckpoint();
        checkpoint.beginCheckpoint(2, 80, 64, false);
        checkpoint.update(true);
        set(checkpoint, "phaseTimer", 33);
        set(checkpoint, "ringRequirement", 80);
        set(checkpoint, "ringsCollected", 64);
        set(manager, "checkpoint", checkpoint);

        Sonic2SpecialStageCheckpoint alignmentCheckpoint = new Sonic2SpecialStageCheckpoint();
        alignmentCheckpoint.beginRainbowOnly();
        alignmentCheckpoint.update(true);
        set(alignmentCheckpoint, "phaseTimer", 21);
        set(manager, "alignmentCheckpoint", alignmentCheckpoint);

        Sonic2SpecialStageRenderer renderer = new Sonic2SpecialStageRenderer(null);
        renderer.setPlayers(players);
        renderer.setIntro(intro);
        renderer.setCheckpoint(checkpoint);
        set(manager, "renderer", renderer);

        set(manager, "initialized", true);
        set(manager, "currentStage", 2);
        set(manager, "resultState", Sonic2SpecialStageManager.ResultState.RUNNING);
        set(manager, "emeraldCollected", true);
        set(manager, "frameCounter", 123);
        set(manager, "renderFrameCounter", 87);
        set(manager, "heldButtons", 0x11);
        set(manager, "pressedButtons", 0x22);
        set(manager, "p2HeldButtons", 0x33);
        set(manager, "p2LogicalButtons", 0x44);
        set(manager, "recurringMainPassPending", true);
        set(manager, "pendingMainHeldButtons", 0x55);
        set(manager, "pendingMainPressedButtons", 0x66);
        set(manager, "pendingMainP2HeldButtons", 0x77);
        set(manager, "pendingMainP2LogicalButtons", 0x88);
        set(manager, "pendingMainCheckpointStep", true);
        set(manager, "previousPhysicalHeldButtons", 0x99);
        set(manager, "previousPhysicalPressedButtons", 0xAA);
        set(manager, "previousPhysicalP2HeldButtons", 0xBB);
        set(manager, "previousPhysicalP2LogicalButtons", 0xCC);
        set(manager, "tailsControlCounter", 7);
        System.arraycopy(sequence(0x100, 16), 0, (int[]) get(manager, "tailsCtrlRecordBuf"), 0, 16);
        set(manager, "lastDrawingIndex", 5);
        set(manager, "checkpointRainbowPaletteActive", true);
        set(manager, "rainbowPaletteCycleIndex", 2);
        set(manager, "pendingCheckpoint", true);
        set(manager, "pendingCheckpointNumber", 3);
        set(manager, "pendingRingRequirement", 90);
        set(manager, "pendingRingsCollected", 75);
        set(manager, "pendingFinalCheckpoint", true);
        set(manager, "currentRingRequirement", 120);
        set(manager, "spriteDebugMode", true);
        ((Sonic2SpecialStageSpriteDebug) get(manager, "debugSprites")).setEnabled(true);
        set(manager, "planeDebugMode", planeMode("PLANE_A_ONLY"));
        set(manager, "alignmentTestMode", true);
        set(manager, "alignmentTestSavedRainbowPalette", true);
        set(manager, "alignmentPendingCheckpoint", true);
        set(manager, "alignmentFrameIndex", 3);
        set(manager, "alignmentFrameTimer", 4);
        set(manager, "alignmentTrackFrameIndex", 5);
        set(manager, "alignmentLastDecodedFrameIndex", 6);
        set(manager, "alignmentDecodedTrackFrame", new int[] { 21, 22, 23 });
        set(manager, "alignmentDrawingIndex", 2);
        set(manager, "alignmentTriggerOffsetFrames", 9);
        set(manager, "alignmentRainbowSpeedScale", 1.25);
        set(manager, "alignmentRainbowSpeedAccumulator", 0.5);
        set(manager, "alignmentStepByTrackFrame", true);
        set(manager, "liveLagSimulationEnabled", true);
        set(manager, "diagnosticWallStartTime", 1_234_567L);
        set(manager, "diagnosticUpdateCount", 8);
        set(manager, "diagnosticTrackAdvances", 9);
        set(manager, "lastFrameTime", 2_345_678L);
        set(manager, "frameSampleCount", 10);
        set(manager, "frameSampleSum", 3_456_789L);
        set(manager, "skydomeScrollX", 17);
        set(manager, "alternateScrollBuffer", true);
        set(manager, "lastAlternateScrollBuffer", true);
        set(manager, "drawingIndex", 4);
        set(manager, "playerBootstrapPhase",
                Sonic2SpecialStageManager.PlayerBootstrapPhase.WAIT_SECOND_DURATION_WRAP);
        set(manager, "lastAnimFrame", 33);
        set(manager, "vScrollBG", -7);
        set(manager, "hScrollDebugTotal", 101);
        set(manager, "hScrollDebugFrames", 11);
        set(manager, "lastDebugSegmentIndex", 12);
        set(manager, "decodedTrackFrame", new int[] { 31, 32, 33 });
        set(manager, "lastDecodedFrameIndex", 13);
        set(manager, "lastDecodedFlipped", true);
    }

    private static void mutateManagerAwayFromSnapshot(
            Sonic2SpecialStageManager manager,
            Palette[] palettes) throws Exception {
        palettes[3].setColor(11, new Palette.Color((byte) 99, (byte) 99, (byte) 99));
        set(manager, "initialized", false);
        set(manager, "currentStage", 6);
        set(manager, "resultState", Sonic2SpecialStageManager.ResultState.FAILED);
        set(manager, "emeraldCollected", false);
        set(manager, "frameCounter", 999);
        set(manager, "renderFrameCounter", 999);
        set(manager, "heldButtons", 999);
        set(manager, "pressedButtons", 999);
        set(manager, "p2HeldButtons", 999);
        set(manager, "p2LogicalButtons", 999);
        set(manager, "recurringMainPassPending", false);
        set(manager, "pendingMainHeldButtons", 999);
        set(manager, "pendingMainPressedButtons", 999);
        set(manager, "pendingMainP2HeldButtons", 999);
        set(manager, "pendingMainP2LogicalButtons", 999);
        set(manager, "pendingMainCheckpointStep", false);
        set(manager, "previousPhysicalHeldButtons", 999);
        set(manager, "previousPhysicalPressedButtons", 999);
        set(manager, "previousPhysicalP2HeldButtons", 999);
        set(manager, "previousPhysicalP2LogicalButtons", 999);
        set(manager, "tailsControlCounter", 999);
        Arrays.fill((int[]) get(manager, "tailsCtrlRecordBuf"), 999);
        set(manager, "lastDrawingIndex", 999);
        set(manager, "checkpointRainbowPaletteActive", false);
        set(manager, "rainbowPaletteCycleIndex", 99);
        set(manager, "pendingCheckpoint", false);
        set(manager, "pendingCheckpointNumber", 99);
        set(manager, "pendingRingRequirement", 99);
        set(manager, "pendingRingsCollected", 99);
        set(manager, "pendingFinalCheckpoint", false);
        set(manager, "currentRingRequirement", 99);
        set(manager, "spriteDebugMode", false);
        ((Sonic2SpecialStageSpriteDebug) get(manager, "debugSprites")).setEnabled(false);
        set(manager, "planeDebugMode", planeMode("NONE"));
        set(manager, "alignmentTestMode", false);
        set(manager, "alignmentTestSavedRainbowPalette", false);
        set(manager, "alignmentPendingCheckpoint", false);
        set(manager, "alignmentFrameIndex", 99);
        set(manager, "alignmentFrameTimer", 99);
        set(manager, "alignmentTrackFrameIndex", 99);
        set(manager, "alignmentLastDecodedFrameIndex", 99);
        set(manager, "alignmentDecodedTrackFrame", new int[] { 9, 9, 9 });
        set(manager, "alignmentDrawingIndex", 99);
        set(manager, "alignmentTriggerOffsetFrames", 99);
        set(manager, "alignmentRainbowSpeedScale", 9.0);
        set(manager, "alignmentRainbowSpeedAccumulator", 9.0);
        set(manager, "alignmentStepByTrackFrame", false);
        set(manager, "liveLagSimulationEnabled", false);
        set(manager, "diagnosticWallStartTime", 99L);
        set(manager, "diagnosticUpdateCount", 99);
        set(manager, "diagnosticTrackAdvances", 99);
        set(manager, "lastFrameTime", 99L);
        set(manager, "frameSampleCount", 99);
        set(manager, "frameSampleSum", 99L);
        set(manager, "skydomeScrollX", 99);
        set(manager, "alternateScrollBuffer", false);
        set(manager, "lastAlternateScrollBuffer", false);
        set(manager, "drawingIndex", 99);
        set(manager, "playerBootstrapPhase", Sonic2SpecialStageManager.PlayerBootstrapPhase.INITIALIZED);
        set(manager, "lastAnimFrame", 99);
        set(manager, "vScrollBG", 99);
        set(manager, "hScrollDebugTotal", 99);
        set(manager, "hScrollDebugFrames", 99);
        set(manager, "lastDebugSegmentIndex", 99);
        set(manager, "decodedTrackFrame", new int[] { 9, 9, 9 });
        set(manager, "lastDecodedFrameIndex", 99);
        set(manager, "lastDecodedFlipped", false);

        Sonic2TrackAnimator animator = (Sonic2TrackAnimator) get(manager, "trackAnimator");
        set(animator, "currentSegmentIndex", 99);
        set(animator, "currentFrameInSegment", 99);
        set(animator, "speedFactor", 1);
        set(animator, "speedChangePending", false);
        set(animator, "orientationFlipped", false);

        Sonic2SpecialStagePlayer sonic = (Sonic2SpecialStagePlayer) get(manager, "sonicPlayer");
        Sonic2SpecialStagePlayer tails = (Sonic2SpecialStagePlayer) get(manager, "tailsPlayer");
        set(sonic, "ssXPos", 999);
        set(tails, "ssXPos", 999);
        sonic.setOtherPlayer(null);
        tails.setOtherPlayer(null);

        set(get(manager, "intro"), "phaseTimer", 99);
        ((Sonic2SpecialStageManager.Sonic2SpecialStageObjectManager) get(manager, "objectManager")).reset();
        ((Sonic2SpecialStageCheckpoint) get(manager, "checkpoint")).reset();
        set(manager, "alignmentCheckpoint", null);
    }

    private static Palette[] createPalettes(int seed) {
        Palette[] palettes = new Palette[4];
        for (int line = 0; line < palettes.length; line++) {
            palettes[line] = new Palette();
            for (int color = 0; color < Palette.PALETTE_SIZE; color++) {
                palettes[line].setColor(color, new Palette.Color(
                        (byte) (seed + line + color),
                        (byte) (seed + line + color + 1),
                        (byte) (seed + line + color + 2)));
            }
        }
        return palettes;
    }

    private static int[] sequence(int start, int count) {
        int[] values = new int[count];
        for (int i = 0; i < values.length; i++) {
            values[i] = start + i;
        }
        return values;
    }

    private static byte[] minimalTrackFrame(int uncIndex) {
        byte[] data = new byte[15];
        data[3] = 1;                 // one byte of source-selection flags
        data[4] = (byte) 0x80;      // first tile comes from the UNC stream
        data[8] = 1;                 // one byte of UNC index bits
        data[9] = (byte) (uncIndex << 1); // non-extended six-bit UNC index
        return data;
    }

    private static final class RecordingTrackRenderer extends Sonic2SpecialStageRenderer {
        int lastTrackFrameIndex = -1;
        int[] lastTrackTiles;

        RecordingTrackRenderer() {
            super(null);
        }

        @Override
        public void renderTrack(int trackFrameIndex, int[] frameTiles) {
            lastTrackFrameIndex = trackFrameIndex;
            lastTrackTiles = frameTiles;
        }

        @Override
        public void renderObjects() {
        }

        @Override
        public void renderPlayers() {
        }

        @Override
        public void renderIntroUI() {
        }
    }

    private static Object planeMode(String name) throws Exception {
        Field f = Sonic2SpecialStageManager.class.getDeclaredField("planeDebugMode");
        f.setAccessible(true);
        Object[] values = f.getType().getEnumConstants();
        for (Object value : values) {
            if (((Enum<?>) value).name().equals(name)) {
                return value;
            }
        }
        throw new IllegalArgumentException(name);
    }

    private static void set(Object target, String field, Object value) throws Exception {
        Field f = findField(target.getClass(), field);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static Object get(Object target, String field) throws Exception {
        Field f = findField(target.getClass(), field);
        f.setAccessible(true);
        return f.get(target);
    }

    private static Field findField(Class<?> type, String field) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(field);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(field);
    }
}
