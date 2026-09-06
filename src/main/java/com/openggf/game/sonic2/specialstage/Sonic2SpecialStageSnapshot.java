package com.openggf.game.sonic2.specialstage;

import com.openggf.level.Palette;

import java.util.List;

final class Sonic2SpecialStageSnapshot {
    final boolean initialized;
    final int currentStage;
    final Sonic2SpecialStageManager.ResultState resultState;
    final boolean emeraldCollected;
    final int frameCounter;
    final int renderFrameCounter;
    final int heldButtons;
    final int pressedButtons;
    final int p2HeldButtons;
    final int p2LogicalButtons;
    final boolean recurringMainPassPending;
    final boolean introFirstRecurringPassDeferred;
    final int pendingMainHeldButtons;
    final int pendingMainPressedButtons;
    final int pendingMainP2HeldButtons;
    final int pendingMainP2LogicalButtons;
    final boolean pendingMainCheckpointStep;
    final int previousPhysicalHeldButtons;
    final int previousPhysicalPressedButtons;
    final int previousPhysicalP2HeldButtons;
    final int previousPhysicalP2LogicalButtons;
    final int tailsControlCounter;
    final int[] tailsCtrlRecordBuf;
    final int swapPositionsFlag;
    final int lastDrawingIndex;
    final boolean checkpointRainbowPaletteActive;
    final int rainbowPaletteCycleIndex;
    final boolean pendingCheckpoint;
    final int pendingCheckpointNumber;
    final int pendingRingRequirement;
    final int pendingRingsCollected;
    final boolean pendingFinalCheckpoint;
    final int currentRingRequirement;
    final boolean spriteDebugMode;
    final Object planeDebugMode;
    final boolean alignmentTestMode;
    final boolean alignmentTestSavedRainbowPalette;
    final boolean alignmentPendingCheckpoint;
    final int alignmentFrameIndex;
    final int alignmentFrameTimer;
    final int alignmentTrackFrameIndex;
    final int alignmentDrawingIndex;
    final int alignmentTriggerOffsetFrames;
    final double alignmentRainbowSpeedScale;
    final double alignmentRainbowSpeedAccumulator;
    final boolean alignmentStepByTrackFrame;
    final boolean liveLagSimulationEnabled;
    final int liveEntryLoadHoldFrames;
    final long diagnosticWallStartTime;
    final int diagnosticUpdateCount;
    final int diagnosticTrackAdvances;
    final long lastFrameTime;
    final int frameSampleCount;
    final long frameSampleSum;
    final int skydomeScrollX;
    final boolean alternateScrollBuffer;
    final boolean lastAlternateScrollBuffer;
    final int drawingIndex;
    final boolean speedPromotionPending;
    final int pendingSpeedFactor;
    final boolean initialPlayerSpawnPending;
    final Sonic2SpecialStageManager.PlayerBootstrapPhase playerBootstrapPhase;
    final int lastAnimFrame;
    final int vScrollBG;
    final int hScrollDebugTotal;
    final int hScrollDebugFrames;
    final int lastDebugSegmentIndex;
    final Palette[] palettes;
    final TrackAnimatorSnapshot trackAnimator;
    final PlayerTopologySnapshot playerTopology;
    final List<PlayerSnapshot> players;
    final IntroSnapshot intro;
    final ObjectManagerSnapshot objectManager;
    final CheckpointSnapshot checkpoint;
    final CheckpointSnapshot alignmentCheckpoint;

    Sonic2SpecialStageSnapshot(
            boolean initialized,
            int currentStage,
            Sonic2SpecialStageManager.ResultState resultState,
            boolean emeraldCollected,
            int frameCounter,
            int renderFrameCounter,
            int heldButtons,
            int pressedButtons,
            int p2HeldButtons,
            int p2LogicalButtons,
            boolean recurringMainPassPending,
            boolean introFirstRecurringPassDeferred,
            int pendingMainHeldButtons,
            int pendingMainPressedButtons,
            int pendingMainP2HeldButtons,
            int pendingMainP2LogicalButtons,
            boolean pendingMainCheckpointStep,
            int previousPhysicalHeldButtons,
            int previousPhysicalPressedButtons,
            int previousPhysicalP2HeldButtons,
            int previousPhysicalP2LogicalButtons,
            int tailsControlCounter,
            int[] tailsCtrlRecordBuf,
            int swapPositionsFlag,
            int lastDrawingIndex,
            boolean checkpointRainbowPaletteActive,
            int rainbowPaletteCycleIndex,
            boolean pendingCheckpoint,
            int pendingCheckpointNumber,
            int pendingRingRequirement,
            int pendingRingsCollected,
            boolean pendingFinalCheckpoint,
            int currentRingRequirement,
            boolean spriteDebugMode,
            Object planeDebugMode,
            boolean alignmentTestMode,
            boolean alignmentTestSavedRainbowPalette,
            boolean alignmentPendingCheckpoint,
            int alignmentFrameIndex,
            int alignmentFrameTimer,
            int alignmentTrackFrameIndex,
            int alignmentDrawingIndex,
            int alignmentTriggerOffsetFrames,
            double alignmentRainbowSpeedScale,
            double alignmentRainbowSpeedAccumulator,
            boolean alignmentStepByTrackFrame,
            boolean liveLagSimulationEnabled,
            int liveEntryLoadHoldFrames,
            long diagnosticWallStartTime,
            int diagnosticUpdateCount,
            int diagnosticTrackAdvances,
            long lastFrameTime,
            int frameSampleCount,
            long frameSampleSum,
            int skydomeScrollX,
            boolean alternateScrollBuffer,
            boolean lastAlternateScrollBuffer,
            int drawingIndex,
            boolean speedPromotionPending,
            int pendingSpeedFactor,
            boolean initialPlayerSpawnPending,
            Sonic2SpecialStageManager.PlayerBootstrapPhase playerBootstrapPhase,
            int lastAnimFrame,
            int vScrollBG,
            int hScrollDebugTotal,
            int hScrollDebugFrames,
            int lastDebugSegmentIndex,
            Palette[] palettes,
            TrackAnimatorSnapshot trackAnimator,
            PlayerTopologySnapshot playerTopology,
            List<PlayerSnapshot> players,
            IntroSnapshot intro,
            ObjectManagerSnapshot objectManager,
            CheckpointSnapshot checkpoint,
            CheckpointSnapshot alignmentCheckpoint) {
        this.initialized = initialized;
        this.currentStage = currentStage;
        this.resultState = resultState;
        this.emeraldCollected = emeraldCollected;
        this.frameCounter = frameCounter;
        this.renderFrameCounter = renderFrameCounter;
        this.heldButtons = heldButtons;
        this.pressedButtons = pressedButtons;
        this.p2HeldButtons = p2HeldButtons;
        this.p2LogicalButtons = p2LogicalButtons;
        this.recurringMainPassPending = recurringMainPassPending;
        this.introFirstRecurringPassDeferred = introFirstRecurringPassDeferred;
        this.pendingMainHeldButtons = pendingMainHeldButtons;
        this.pendingMainPressedButtons = pendingMainPressedButtons;
        this.pendingMainP2HeldButtons = pendingMainP2HeldButtons;
        this.pendingMainP2LogicalButtons = pendingMainP2LogicalButtons;
        this.pendingMainCheckpointStep = pendingMainCheckpointStep;
        this.previousPhysicalHeldButtons = previousPhysicalHeldButtons;
        this.previousPhysicalPressedButtons = previousPhysicalPressedButtons;
        this.previousPhysicalP2HeldButtons = previousPhysicalP2HeldButtons;
        this.previousPhysicalP2LogicalButtons = previousPhysicalP2LogicalButtons;
        this.tailsControlCounter = tailsControlCounter;
        this.tailsCtrlRecordBuf = cloneIntArray(tailsCtrlRecordBuf);
        this.swapPositionsFlag = swapPositionsFlag;
        this.lastDrawingIndex = lastDrawingIndex;
        this.checkpointRainbowPaletteActive = checkpointRainbowPaletteActive;
        this.rainbowPaletteCycleIndex = rainbowPaletteCycleIndex;
        this.pendingCheckpoint = pendingCheckpoint;
        this.pendingCheckpointNumber = pendingCheckpointNumber;
        this.pendingRingRequirement = pendingRingRequirement;
        this.pendingRingsCollected = pendingRingsCollected;
        this.pendingFinalCheckpoint = pendingFinalCheckpoint;
        this.currentRingRequirement = currentRingRequirement;
        this.spriteDebugMode = spriteDebugMode;
        this.planeDebugMode = planeDebugMode;
        this.alignmentTestMode = alignmentTestMode;
        this.alignmentTestSavedRainbowPalette = alignmentTestSavedRainbowPalette;
        this.alignmentPendingCheckpoint = alignmentPendingCheckpoint;
        this.alignmentFrameIndex = alignmentFrameIndex;
        this.alignmentFrameTimer = alignmentFrameTimer;
        this.alignmentTrackFrameIndex = alignmentTrackFrameIndex;
        this.alignmentDrawingIndex = alignmentDrawingIndex;
        this.alignmentTriggerOffsetFrames = alignmentTriggerOffsetFrames;
        this.alignmentRainbowSpeedScale = alignmentRainbowSpeedScale;
        this.alignmentRainbowSpeedAccumulator = alignmentRainbowSpeedAccumulator;
        this.alignmentStepByTrackFrame = alignmentStepByTrackFrame;
        this.liveLagSimulationEnabled = liveLagSimulationEnabled;
        this.liveEntryLoadHoldFrames = liveEntryLoadHoldFrames;
        this.diagnosticWallStartTime = diagnosticWallStartTime;
        this.diagnosticUpdateCount = diagnosticUpdateCount;
        this.diagnosticTrackAdvances = diagnosticTrackAdvances;
        this.lastFrameTime = lastFrameTime;
        this.frameSampleCount = frameSampleCount;
        this.frameSampleSum = frameSampleSum;
        this.skydomeScrollX = skydomeScrollX;
        this.alternateScrollBuffer = alternateScrollBuffer;
        this.lastAlternateScrollBuffer = lastAlternateScrollBuffer;
        this.drawingIndex = drawingIndex;
        this.speedPromotionPending = speedPromotionPending;
        this.pendingSpeedFactor = pendingSpeedFactor;
        this.initialPlayerSpawnPending = initialPlayerSpawnPending;
        this.playerBootstrapPhase = playerBootstrapPhase;
        this.lastAnimFrame = lastAnimFrame;
        this.vScrollBG = vScrollBG;
        this.hScrollDebugTotal = hScrollDebugTotal;
        this.hScrollDebugFrames = hScrollDebugFrames;
        this.lastDebugSegmentIndex = lastDebugSegmentIndex;
        this.palettes = clonePalettes(palettes);
        this.trackAnimator = trackAnimator;
        this.playerTopology = playerTopology;
        this.players = copyList(players);
        this.intro = intro;
        this.objectManager = objectManager;
        this.checkpoint = checkpoint;
        this.alignmentCheckpoint = alignmentCheckpoint;
    }

    record TrackAnimatorSnapshot(
            byte[] stageLayout,
            int layoutLength,
            int currentSegmentIndex,
            int currentFrameInSegment,
            int frameDelayCounter,
            int playerAnimFrameTimer,
            int currentSegmentType,
            boolean currentSegmentFlipped,
            int speedFactor,
            boolean speedChangePending,
            boolean stageComplete,
            boolean orientationFlipped,
            int lastOrientationFrame) {
        TrackAnimatorSnapshot {
            stageLayout = Sonic2SpecialStageSnapshot.cloneByteArray(stageLayout);
        }
    }

    record PlayerSlotSnapshot(
            Sonic2SpecialStagePlayer.PlayerType type,
            boolean mainCharacter) {
    }

    record PlayerTopologySnapshot(
            List<PlayerSlotSnapshot> slots,
            int sonicSlotIndex,
            int tailsSlotIndex,
            boolean playersLinked) {
        PlayerTopologySnapshot {
            slots = List.copyOf(slots);
        }

        static PlayerTopologySnapshot capture(
                List<Sonic2SpecialStagePlayer> players,
                Sonic2SpecialStagePlayer sonicPlayer,
                Sonic2SpecialStagePlayer tailsPlayer) {
            java.util.ArrayList<PlayerSlotSnapshot> slots = new java.util.ArrayList<>();
            int sonicIndex = -1;
            int tailsIndex = -1;
            for (int i = 0; i < players.size(); i++) {
                Sonic2SpecialStagePlayer player = players.get(i);
                slots.add(new PlayerSlotSnapshot(player.getPlayerType(), player.isMainCharacter()));
                if (player == sonicPlayer) {
                    sonicIndex = i;
                }
                if (player == tailsPlayer) {
                    tailsIndex = i;
                }
            }
            boolean linked = sonicPlayer != null && tailsPlayer != null
                    && sonicPlayer.getOtherPlayerForRewind() == tailsPlayer
                    && tailsPlayer.getOtherPlayerForRewind() == sonicPlayer;
            return new PlayerTopologySnapshot(slots, sonicIndex, tailsIndex, linked);
        }
    }

    record PlayerSnapshot(
            Sonic2SpecialStagePlayer.PlayerType playerType,
            boolean mainCharacter,
            boolean spawned,
            Sonic2SpecialStagePlayer.RoutineState routine,
            int routineSecondary,
            int ssXPos,
            int ssXSub,
            int ssYPos,
            int ssYSub,
            int ssZPos,
            int xPos,
            int yPos,
            int xVel,
            int yVel,
            int inertia,
            int angle,
            int ssSlideTimer,
            int ssHurtTimer,
            int ssDplcTimer,
            int ssInitFlipTimer,
            int ssFlipTimer,
            int ssLastAngleIndex,
            int anim,
            int prevAnim,
            int animFrame,
            int animFrameDuration,
            int mappingFrame,
            int tailsTailsAnim,
            int tailsTailsPrevAnim,
            int tailsTailsAnimFrame,
            int tailsTailsFrameDuration,
            int tailsTailsMappingFrame,
            int yRadius,
            int xRadius,
            int priority,
            boolean statusXFlip,
            boolean statusYFlip,
            boolean statusJumping,
            boolean statusSlowing,
            boolean renderXFlip,
            boolean renderYFlip,
            int collisionProperty,
            int rings,
            int globalAnimFrameTimer,
            int[] ctrlRecordBuf,
            int ctrlRecordIndex,
            int invulnerabilityCountdown) {
        PlayerSnapshot {
            ctrlRecordBuf = Sonic2SpecialStageSnapshot.cloneIntArray(ctrlRecordBuf);
        }
    }

    record IntroMessageLetterSnapshot(
            int x,
            int y,
            int tileOffset,
            double flyoutAngle,
            int flyoutSpeed,
            boolean visible) {
    }

    record IntroBannerLetterSnapshot(
            int x,
            int y,
            int frame,
            double flyoutAngle,
            int flyoutSpeed,
            boolean visible) {
    }

    record IntroSnapshot(
            Sonic2SpecialStageIntro.Phase currentPhase,
            int phaseTimer,
            int frameCounter,
            boolean specialStageStarted,
            int bannerX,
            int bannerY,
            boolean bannerVisible,
            int messageX,
            int messageY,
            boolean messageVisible,
            int ringRequirement,
            boolean lettersFlying,
            int letterFlyoutProgress,
            boolean messageFlyoutInitialized,
            boolean bannerFlyoutInitialized,
            List<IntroMessageLetterSnapshot> messageLetters,
            List<IntroBannerLetterSnapshot> bannerLetters) {
        IntroSnapshot {
            messageLetters = List.copyOf(messageLetters);
            bannerLetters = List.copyOf(bannerLetters);
        }
    }

    record CheckpointMessageLetterSnapshot(
            int x,
            int y,
            int tileOffset,
            int flyoutAngle,
            int flyoutSpeed,
            boolean visible) {
    }

    record CheckpointRainbowRingSnapshot(
            int baseIndex,
            int frameIndex,
            int positionOffset,
            int mappingFrame,
            int x,
            int y,
            boolean active) {
    }

    record CheckpointSnapshot(
            Sonic2SpecialStageCheckpoint.MessagePhase phase,
            int phaseTimer,
            Sonic2SpecialStageCheckpoint.Result lastResult,
            int currentCheckpoint,
            int ringRequirement,
            int ringsCollected,
            List<CheckpointMessageLetterSnapshot> messageLetters,
            boolean showCheckpointHand,
            int handX,
            int handY,
            int handTargetY,
            boolean handThumbsUp,
            boolean handMovingDown,
            List<CheckpointRainbowRingSnapshot> rainbowRings,
            int pendingRingRequirement,
            int pendingRingsCollected,
            boolean pendingFinalCheckpoint,
            boolean rainbowOnly) {
        CheckpointSnapshot {
            messageLetters = List.copyOf(messageLetters);
            rainbowRings = List.copyOf(rainbowRings);
        }
    }

    enum SpecialStageObjectType {
        RING,
        BOMB,
        EMERALD
    }

    record BaseObjectSnapshot(
            Sonic2SpecialStageObject.State state,
            int angle,
            long depthFixed,
            int screenX,
            int screenY,
            int trackFloorY,
            int animIndex,
            int animFrame,
            int animTimer,
            boolean onScreen,
            boolean highPriority) {
    }

    record ObjectSnapshot(
            SpecialStageObjectType type,
            BaseObjectSnapshot base,
            Integer ringSpinFrame,
            Sonic2SpecialStageEmerald.EmeraldPhase emeraldPhase,
            int emeraldPhaseTimer,
            int emeraldBobbingOffset,
            int emeraldBobbingCounter,
            int emeraldRingRequirement,
            boolean emeraldMusicFaded,
            boolean emeraldAwarded) {
    }

    record ObjectManagerSnapshot(
            byte[] objectLocationData,
            int[] stageOffsets,
            int currentPosition,
            int currentStage,
            int lastProcessedSegment,
            int ringsCollected,
            int perfectRingsTotal,
            int currentSpecialAct,
            boolean noCheckpointFlag,
            boolean noCheckpointMsgFlag,
            boolean ringsToGoEnabled,
            boolean emeraldSpawned,
            List<ObjectSnapshot> activeObjects) {
        ObjectManagerSnapshot {
            objectLocationData = Sonic2SpecialStageSnapshot.cloneByteArray(objectLocationData);
            stageOffsets = Sonic2SpecialStageSnapshot.cloneIntArray(stageOffsets);
            activeObjects = List.copyOf(activeObjects);
        }

        public byte[] objectLocationData() {
            return Sonic2SpecialStageSnapshot.cloneByteArray(objectLocationData);
        }

        public int[] stageOffsets() {
            return Sonic2SpecialStageSnapshot.cloneIntArray(stageOffsets);
        }
    }

    static byte[] cloneByteArray(byte[] source) {
        return source != null ? source.clone() : null;
    }

    static int[] cloneIntArray(int[] source) {
        return source != null ? source.clone() : null;
    }

    static Palette[] clonePalettes(Palette[] source) {
        if (source == null) {
            return null;
        }
        Palette[] copy = new Palette[source.length];
        for (int i = 0; i < source.length; i++) {
            copy[i] = source[i] != null ? source[i].deepCopy() : null;
        }
        return copy;
    }

    static <T> List<T> copyList(List<T> source) {
        return source != null ? List.copyOf(source) : List.of();
    }
}
