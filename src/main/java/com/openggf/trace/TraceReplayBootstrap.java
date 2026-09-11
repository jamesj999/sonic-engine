package com.openggf.trace;

import com.openggf.game.GameServices;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.trace.replay.TraceReplayFixture;

import java.util.List;

/**
 * Shared trace replay bootstrap helpers.
 *
 * <p>Trace rows are a read-only comparison ledger. This class may align replay
 * cursors and classify execution phases from trace metadata/events, but it must
 * not copy recorded player, sidekick, object, camera, RNG, or CPU state back
 * into the engine.
 */
public final class TraceReplayBootstrap {
    public record ReplayStartState(int startingTraceIndex, int seededTraceIndex) {
        public static final ReplayStartState DEFAULT = new ReplayStartState(0, -1);

        public boolean hasSeededTraceState() {
            return seededTraceIndex >= 0;
        }
    }

    public record SnapshotReport(int attempted, int matched, List<String> warnings) {
    }

    public record ReplayPrimaryState(
            short x,
            short y,
            short xSpeed,
            short ySpeed,
            short gSpeed,
            byte angle,
            boolean air,
            boolean rolling,
            int groundMode,
            int xSub,
            int ySub,
            String source) {

        public static ReplayPrimaryState fromSprite(AbstractPlayableSprite sprite) {
            return new ReplayPrimaryState(
                    sprite.getCentreX(),
                    sprite.getCentreY(),
                    sprite.getXSpeed(),
                    sprite.getYSpeed(),
                    sprite.getGSpeed(),
                    sprite.getAngle(),
                    sprite.getAir(),
                    sprite.getRolling(),
                    sprite.getGroundMode().ordinal(),
                    sprite.getXSubpixelRaw(),
                    sprite.getYSubpixelRaw(),
                    "player");
        }

    }

    private TraceReplayBootstrap() {
    }

    /**
     * Reports recorded pre-trace object SST snapshots without mutating engine
     * state. Trace rows are diagnostic comparison input only; replay bootstrap
     * must not copy recorded object bytes back into live objects.
     */
    public static SnapshotReport reportPreTraceObjectSnapshots(TraceData trace) {
        List<TraceEvent.ObjectStateSnapshot> snapshots = trace != null
                ? trace.preTraceObjectSnapshots()
                : List.of();
        return new SnapshotReport(snapshots.size(), 0, List.of());
    }

    /**
     * Compatibility name retained for replay bootstrap callers. Despite the
     * historical name, this only reports recorded pre-trace snapshots and never
     * applies trace data to engine state.
     */
    public static SnapshotReport applyPreTraceState(TraceData trace, TraceReplayFixture fixture) {
        return reportPreTraceObjectSnapshots(trace);
    }

    public static ReplayStartState applyReplayStartState(TraceData trace,
                                                         TraceReplayFixture fixture) {
        return applyReplayStartStateForTraceReplay(trace, fixture);
    }

    public static ReplayStartState applyReplayStartStateForTraceReplay(TraceData trace,
                                                                       TraceReplayFixture fixture) {
        return new ReplayStartState(replaySeedTraceIndexForTraceReplay(trace), -1);
    }

    /**
     * Compatibility entrypoint for callers that used to request a seeded replay
     * start. It now only returns the unseeded comparison cursor.
     */
    public static ReplayStartState applySeedReplayStartStateForTraceReplay(TraceData trace,
                                                                           TraceReplayFixture fixture) {
        return applyReplayStartStateForTraceReplay(trace, fixture);
    }

    public static int recordingStartFrameForTraceReplay(TraceData trace) {
        if (trace == null) {
            return 0;
        }
        int seedTraceIndex = replaySeedTraceIndexForTraceReplay(trace);
        return trace.metadata().bk2FrameOffset() + Math.max(0, seedTraceIndex - 1);
    }

    /**
     * Returns the first trace frame where a ZoneActState or Checkpoint event
     * reports {@code game_mode=0x0C} (LEVEL). Headless replay fixtures start
     * the engine directly in gamemode 0x0C, but the recorder usually runs
     * for some number of frames in SEGA/title/level-load gamemodes before
     * the ROM reaches LevelLoop for the first time. Many ROM systems
     * (OscillateNumDo, sprite placement cursor, random lookups) only tick
     * inside LevelLoop, so replay drivers need to know how many leading
     * frames to neutralise from the engine-side so the ROM and engine
     * stay phase-aligned over long traces.
     */
    public static int preLevelFrameCountForTraceReplay(TraceData trace) {
        if (trace == null || trace.frameCount() == 0) {
            return 0;
        }
        return findFirstLevelGameplayFrame(trace);
    }

    /**
     * Returns whether the recorded mode timeline begins outside live LEVEL mode
     * and later transitions into it.
     *
     * <p>This classification uses only recorder-observed {@code zone_act_state}
     * events. Legacy phase-control metadata remains parseable but does not
     * participate in replay scheduling.
     */
    public static boolean hasRecordedPreLevelPrefix(TraceData trace) {
        if (trace == null || trace.frameCount() == 0) {
            return false;
        }
        Boolean cached = trace.cachedPreLevelPrefix();
        if (cached != null) {
            return cached;
        }
        boolean computed = computeHasRecordedPreLevelPrefix(trace);
        trace.cachePreLevelPrefix(computed);
        return computed;
    }

    private static boolean computeHasRecordedPreLevelPrefix(TraceData trace) {
        Integer firstRecordedMode = null;
        for (int frame = 0; frame < trace.frameCount(); frame++) {
            for (TraceEvent event : trace.getEventsForFrame(frame)) {
                if (!(event instanceof TraceEvent.ZoneActState state)
                        || state.gameMode() == null) {
                    continue;
                }
                if (firstRecordedMode == null) {
                    firstRecordedMode = state.gameMode();
                    if (firstRecordedMode == 12) {
                        return false;
                    }
                    continue;
                }
                if (state.gameMode() == 12) {
                    return true;
                }
            }
        }
        return false;
    }

    public static int replaySeedTraceIndexForTraceReplay(TraceData trace) {
        if (trace == null || trace.frameCount() == 0) {
            return 0;
        }
        if (hasRecordedPreLevelPrefix(trace)) {
            // Pre-level-prefix fixtures record the intro/cutscene timeline from
            // trace frame 0. Replaying from the first in-level frame skips
            // recorded native state that the seed frame alone cannot reconstruct.
            return 0;
        }
        int firstLevelFrame = findFirstLevelGameplayFrame(trace);
        return Math.max(firstLevelFrame, 0);
    }

    public static int initialVblankCounterForTraceReplay(TraceData trace) {
        if (trace == null || trace.frameCount() == 0) {
            return 0;
        }
        return trace.initialVblankCounter() + 1;
    }

    public static int preTraceOscillationFramesForTraceReplay(TraceData trace,
                                                              int override) {
        if (override >= 0) {
            return override;
        }
        if (trace == null || trace.frameCount() == 0
                || hasRecordedPreLevelPrefix(trace)) {
            return 0;
        }
        if ("s3k".equals(trace.metadata().game())) {
            return 0;
        }
        int seedTraceIndex = replaySeedTraceIndexForTraceReplay(trace);
        if (seedTraceIndex < 0 || seedTraceIndex >= trace.frameCount()) {
            return 0;
        }
        int firstComparedGameplayFrame =
                trace.getFrame(seedTraceIndex).gameplayFrameCounter();
        // The replay loop steps the seed trace row before comparing it. A row
        // with gameplay_frame_counter=1 has already observed the ROM's first
        // LevelLoop tick, but the headless fixture will produce that same tick
        // natively when it steps the row. Only pre-advance ticks that completed
        // before the first compared row.
        return Math.max(0, firstComparedGameplayFrame - 1);
    }

    /**
     * Pre-level-prefix traces now drive their intro prefix from
     * trace frame 0, including the first native LevelLoop oscillator tick.
     * Applying an additional replay-local suppression would leave
     * Obj_FloatingPlatform one oscillator frame behind the ROM by the first
     * carry window.
     */
    public static int initialOscillationSuppressionFramesForTraceReplay(TraceData trace) {
        if (trace == null || trace.frameCount() == 0) {
            return 0;
        }
        return 0;
    }

    /**
     * Number of native sidekick-only object ticks that occur after level load
     * but before the first gameplay comparison frame.
     *
     * <p>S2 native-prelude traces are handled by the level-object prelude below;
     * zones that suppress CPU sidekicks must not run a hidden sidekick-only
     * warmup. S3K Sonic+Tails seed-row traces need one tick to reproduce the
     * ROM's first Process_Sprites pass without driving Sonic through a full
     * player physics frame.
     */
    public static int sidekickTitleCardPreludeFramesForTraceReplay(TraceData trace) {
        return resolveS2SidekickTitleCardPreludeFrames(trace);
    }

    /**
     * Sonic 1 runs one native object pass after initial ObjPosLoad and before
     * {@code Level_MainLoop} begins ticking {@code v_framecount}: see
     * {@code docs/s1disasm/sonic.asm:2875-2876} and
     * {@code docs/s1disasm/sonic.asm:2985-3003}. Headless replay starts at the
     * first compared gameplay row, so S1 traces whose first row has gameplay
     * counter 1 need that single native prelude object pass reproduced instead
     * of copied from trace diagnostics.
     *
     * <p>Sonic 2 runs level objects during the title-card sequence before
     * {@code Level_started_flag} is set and before {@code Level_frame_counter}
     * begins ticking in {@code Level_MainLoop}: see s2.asm:5004-5008,
     * s2.asm:5060-5066, and s2.asm:5077-5092. Headless replay starts directly
     * at gameplay frame 1, so it must reproduce that native object prelude
     * without copying pre-trace SST snapshots back into the engine.
     *
     * <p>S2 Tornado title-card object preludes depend on the live ObjB2
     * routine/subtype loaded for the route and are therefore selected by
     * {@code TraceReplaySessionBootstrap}, not by trace zone metadata here.
     *
     * <p>S3K replay never selects an object prelude from trace identity or
     * metadata. Fresh loads publish production setup authority through the
     * level lifecycle; represented complete-run state discards that authority
     * before restoration.
     */
    public static int levelObjectTitleCardPreludeFramesForTraceReplay(TraceData trace) {
        int s1PreludeFrames = resolveS1LevelStartObjectPreludeFrames(trace);
        if (s1PreludeFrames > 0) {
            return s1PreludeFrames;
        }
        return 0;
    }

    /**
     * Number of S2 SlotMachine title-card ticks needed before trace comparison
     * begins. Recorder support is detected by the advertised per-frame
     * slot-machine schema; replay advances the native short slot-init window
     * separately from the object prelude rather than copying slot RAM from the
     * trace.
     */
    public static int zoneFeatureTitleCardPreludeFramesForTraceReplay(TraceData trace) {
        if (trace == null || trace.metadata() == null) {
            return 0;
        }
        TraceMetadata meta = trace.metadata();
        if (!"s2".equals(meta.game()) || !meta.hasNativePreludeBootstrap()) {
            return 0;
        }
        if (!meta.hasPerFrameSlotMachineState()) {
            return 0;
        }
        // Bootstrap only Routine1's initialization (s2.asm:59320-59342).
        // Recorded row 0 is the following Level_MainLoop iteration, where
        // Routine2 performs the first reel draw (s2.asm:59345-59355).
        return 1;
    }

    public static int zoneFeatureTitleCardPreludeStartVblankOffsetForTraceReplay(TraceData trace) {
        if (zoneFeatureTitleCardPreludeFramesForTraceReplay(trace) == 0) {
            return 0;
        }
        return 10;
    }

    /**
     * Frame count of the ROM title-card object prelude that S2 traces sampled
     * at {@code frame -1}. The recorded {@code player_history_snapshot}
     * {@code history_pos=0x68 = 104} is the raw byte index of
     * {@code Sonic_Pos_Record_Index} (s2.asm:36043-36048). Each
     * {@code Sonic_RecordPos} call advances the index by 4 (one 4-byte
     * Pos_table entry), so {@code history_pos = 4 * frames_recorded}.
     * Therefore the actual prelude length is {@code 104 / 4 = 26} frames of
     * the ROM title-card {@code RunObjects} loop running Obj01_Control after
     * Obj01_Init has completed its 64-entry pre-fill.
     */
    private static final int S2_TITLE_CARD_PRELUDE_FRAMES = 26;

    /**
     * S2 native-prelude traces start comparison at the first gameplay row, but
     * headless replay does not run the ROM title-card object loop before that
     * row. Obj01_Control records Sonic's position before Obj02_Control advances
     * Tails during that loop; ten native sidekick-only ticks put Tails at the
     * same control/speed phase that frame 0 then advances and compares. The
     * recorder's frame -1 {@code Sonic_Pos_Record_Index} is {@code 0x68}; S2
     * advances the byte index by 4 per {@code Sonic_RecordPos} call, so the
     * native title-card window contributes 26 leader-history writes.
     */
    private static final int S2_SIDEKICK_TITLE_CARD_PRELUDE_FRAMES = 26;

    private static final int S1_LEVEL_START_OBJECT_PRELUDE_FRAMES = 1;

    private static final int S3K_COMPLETE_RUN_SETUP_ANIMATED_TILE_PRELUDE_FRAMES = 1;

    private static int resolveS1LevelStartObjectPreludeFrames(TraceData trace) {
        if (trace == null || trace.frameCount() == 0) {
            return 0;
        }
        TraceMetadata meta = trace.metadata();
        if (meta == null
                || !"s1".equals(meta.game())
                || replaySeedTraceIndexForTraceReplay(trace) != 0) {
            return 0;
        }
        TraceFrame firstFrame = trace.getFrame(0);
        return firstFrame.gameplayFrameCounter() == 1
                ? S1_LEVEL_START_OBJECT_PRELUDE_FRAMES
                : 0;
    }

    private static int resolveS2SidekickTitleCardPreludeFrames(TraceData trace) {
        if (trace == null || trace.frameCount() == 0) {
            return 0;
        }
        TraceMetadata meta = trace.metadata();
        if (meta == null
                || !"s2".equals(meta.game())
                || !meta.hasNativePreludeBootstrap()
                || meta.recordedSidekicks().isEmpty()
                || replaySeedTraceIndexForTraceReplay(trace) != 0) {
            return 0;
        }
        TraceFrame firstFrame = trace.getFrame(0);
        return firstFrame.gameplayFrameCounter() == 1
                ? S2_SIDEKICK_TITLE_CARD_PRELUDE_FRAMES
                : 0;
    }

    private static int resolveS2TitleCardPreludeFrames(TraceData trace) {
        if (trace == null) {
            return 0;
        }
        TraceMetadata meta = trace.metadata();
        if (meta == null || !"s2".equals(meta.game())) {
            return 0;
        }
        if (!meta.hasNativePreludeBootstrap()) {
            return 0;
        }
        if (meta.recordedSidekicks().isEmpty()) {
            return 0;
        }
        return S2_TITLE_CARD_PRELUDE_FRAMES;
    }

    public static int s2TornadoTitleCardPreludeFramesForTraceReplay(TraceData trace) {
        return isS2TornadoRideStartMetadataCandidate(trace)
                ? resolveS2TitleCardPreludeFrames(trace)
                : 0;
    }

    /**
     * Number of title-card-phase Level_MainLoop object ticks an S2 non-Tornado
     * native-prelude trace expects to have run before its first compared frame.
     *
     * <p>S2 ROM runs {@code Level_MainLoop} object ticks while the title card is
     * displayed before {@code Level_started_flag} is set
     * ({@code docs/s2disasm/s2.asm:5004-5008, 5060-5066, 5077-5092}). Headless
     * trace replay skips the title-card phase entirely and starts directly at
     * gameplay frame 1, so it must reproduce those native object ticks before
     * the comparison loop begins or every S2 trace diverges within ~100-200
     * frames from compounded post-title-card object/player state drift.
     *
     * <p>The caller must only use this when the live object manager did not
     * select a route-specific Tornado object prelude. The metadata-level
     * {@link #isS2TornadoRideStartMetadataCandidate(TraceData)} predicate is
     * intentionally broad because the live ObjB2 shape is the real authority;
     * treating that predicate alone as "Tornado active" suppresses the generic
     * title-card ticks for normal S2 routes such as MTZ.
     */
    public static int s2GenericObjectTitleCardPreludeFramesForTraceReplay(TraceData trace) {
        return resolveS2TitleCardPreludeFrames(trace);
    }

    /**
     * Number of native S3K {@code Animate_Tiles} calls that complete-run
     * segments observe before the first compared motion row.
     *
     * <p>The ROM's setup pass calls {@code Animate_Tiles} after
     * {@code Process_Sprites} and {@code Render_Sprites}, then unlocks
     * controls (docs/skdisasm/sonic3k.asm:7849-7860). Replay's structural
     * handoff row is also routed as VBlank-only, so it cannot run the normal
     * {@code LevelLoop} tail where S3K advances level animation
     * (sonic3k.asm:7884-7911). Advance the engine's native animated-pattern
     * manager for those skipped animation calls instead of copying recorded
     * animation RAM from the trace.
     */
    public static int s3kCompleteRunAnimatedTilePreludeFramesForTraceReplay(TraceData trace) {
        if (!isS3kCompleteRunSegment(trace)) {
            return 0;
        }
        int frames = S3K_COMPLETE_RUN_SETUP_ANIMATED_TILE_PRELUDE_FRAMES;
        if (isS3kCompleteRunHandoffCounterTickRow(trace)) {
            frames++;
        }
        return frames;
    }

    /**
     * Returns false because trace start state is comparison data only. Kept as
     * a named policy gate for callers that need to avoid legacy hydration paths.
     */
    public static boolean shouldUseTraceStartBootstrapForTraceReplay(TraceData trace) {
        return false;
    }

    public static boolean shouldSeedFrameZeroForTraceReplay(TraceData trace) {
        return false;
    }

    public static boolean shouldSeedReplayStartStateForTraceReplay(TraceData trace,
                                                                   int requestedSeedTraceIndex) {
        return false;
    }

    public static boolean requiresFreshLevelLoadForTraceReplay(TraceData trace) {
        return hasRecordedPreLevelPrefix(trace)
                && replaySeedTraceIndexForTraceReplay(trace) == 0;
    }

    public static boolean shouldApplyMetadataStartPositionForTraceReplay(TraceData trace) {
        return replaySeedTraceIndexForTraceReplay(trace) == 0
                && !hasRecordedPreLevelPrefix(trace)
                && !isS2TornadoRideStartMetadataCandidate(trace);
    }

    public static boolean shouldGroundSnapMetadataStartForTraceReplay(TraceData trace) {
        return !isS3kCompleteRunSegment(trace) && !isS3kBonusStageSegment(trace);
    }

    /**
     * Identifies an S3K bonus-stage trace segment (Gumball/Pachinko/Slots).
     * These fixtures start the player exactly at the ROM's post-Restart_level
     * spawn coordinates (sonic3k.asm:38160-38183 Get_LevelSizeStart), which for
     * Gumball/Pachinko is deliberately positioned at/beyond a terrain edge so
     * the player free-falls into the machine. ROM never runs a pre-LevelLoop
     * terrain probe against this spawn point: {@code SpawnLevelMainSprites}'s
     * zone-specific air/animation branches are skipped whenever
     * {@code Special_bonus_entry_flag} is set (sonic3k.asm:8117-8118), so the
     * player's ground/air transition is decided exclusively by the first
     * driven frame's own {@code Player_AnglePos} probe. The generic
     * bootstrap ground-snap (positiveThreshold 14) instead runs that same
     * detach-from-terrain probe one tick early, at bootstrap time, consuming
     * the transition frame's own ground-accel tick and leaving frame 0 to
     * run full air acceleration + gravity a tick ahead of the ROM.
     */
    private static boolean isS3kBonusStageSegment(TraceData trace) {
        if (trace == null || trace.metadata() == null) {
            return false;
        }
        TraceMetadata metadata = trace.metadata();
        return "s3k".equals(metadata.game())
                && "s3k_bonus_stage".equals(metadata.traceProfile());
    }

    /**
     * S3K complete-run CNZ/MHZ traces begin on a visible handoff row before the
     * first native motion row. Replay routes that row as {@code VBLANK_ONLY} and
     * does not drive or compare its gameplay, but ROM still ran a full LevelLoop
     * iteration on it — incrementing {@code Level_frame_counter} before
     * {@code Process_Sprites} (sonic3k.asm:7889-7894). The replay harness therefore
     * ticks the engine's frame counter on this row so the S3K Tails-CPU gates
     * observe the same {@code Level_frame_counter} edge the ROM did, without any
     * trace-profile-gated behaviour in the shared sidekick code.
     */
    public static boolean isS3kCompleteRunHandoffCounterTickRow(TraceData trace) {
        if (trace == null || trace.frameCount() == 0) {
            return false;
        }
        return isS3kCompleteRunInitialHandoffRow(trace, null, trace.getFrame(0));
    }

    /**
     * Native animated-tile ticks completed on an S3K complete-run handoff row
     * that replay skips for gameplay comparison. The ROM LevelLoop still reaches
     * {@code Animate_Tiles} after {@code Process_Sprites} on that row
     * (sonic3k.asm:7884-7906), so consumers of {@code Anim_Counters} such as
     * MHZ mushroom caps must see the same completed post-object phase before the
     * first driven row.
     */
    public static int s3kCompleteRunHandoffAnimatedTilePreludeFramesForTraceReplay(TraceData trace) {
        return isS3kCompleteRunHandoffCounterTickRow(trace) ? 1 : 0;
    }

    public static List<String> releaseBlockersForTraceReplay(TraceData trace) {
        return List.of();
    }

    public static boolean isS2TornadoRideStartMetadataCandidate(TraceData trace) {
        if (trace == null || trace.frameCount() == 0) {
            return false;
        }
        TraceMetadata metadata = trace.metadata();
        if (metadata == null
                || !"s2".equals(metadata.game())
                || replaySeedTraceIndexForTraceReplay(trace) != 0) {
            return false;
        }
        return metadata.hasNativePreludeBootstrap()
                && "level_gated_reset_aware".equals(metadata.traceProfile())
                && !metadata.recordedSidekicks().isEmpty();
    }

    public static int strictStartTraceIndexForTraceReplay(TraceData trace) {
        if (trace == null || trace.frameCount() == 0) {
            return 0;
        }
        if (hasRecordedPreLevelPrefix(trace)) {
            return findFirstLevelGameplayFrame(trace);
        }
        return replaySeedTraceIndexForTraceReplay(trace);
    }

    public static ReplayPrimaryState capturePrimaryReplayStateForComparison(TraceData trace,
                                                                            TraceFrame current,
                                                                            AbstractPlayableSprite sprite) {
        if (sprite == null) {
            throw new IllegalArgumentException("sprite must not be null");
        }
        return ReplayPrimaryState.fromSprite(sprite);
    }

    /**
     * Forwards the hardware-timing classification of the row about to be driven
     * to the PLC frame-closure boundary when no V-blank elapsed for it. Same
     * category of input as {@link #phaseForReplay} itself: recorder counters
     * only, no gameplay value and no queue readiness.
     *
     * @see TraceExecutionModel#isVblankStarvedRow
     */
    public static void markVblankStarvedIterationForReplay(
            TraceFrame previous, TraceFrame current) {
        if (!isVblankStarvedIterationForReplay(previous, current)) {
            return;
        }
        markReplayProductionIterationWithoutVblank();
    }

    /** Marks the next represented production closure as having no VBlank. */
    public static void markReplayProductionIterationWithoutVblank() {
        var gameplayMode = com.openggf.game.session.SessionManager.getCurrentGameplayMode();
        if (gameplayMode != null && gameplayMode.plcFrameLifecycle() != null) {
            gameplayMode.plcFrameLifecycle().markRepresentedIterationWithoutVblank();
        }
    }

    /** Returns the structural no-VBlank classification used by replay closure. */
    public static boolean isVblankStarvedIterationForReplay(
            TraceFrame previous, TraceFrame current) {
        return TraceExecutionModel.isVblankStarvedRow(previous, current);
    }

    /**
     * Forwards the hardware-timing classification of a row whose main-loop
     * iteration is still in flight when the next row is sampled, so the PLC
     * frame closure represents the iteration's loop tail on the closure where
     * the ROM actually ran it. Same category of input as
     * {@link #phaseForReplay}: recorder counters only.
     *
     * @see TraceExecutionModel#isIterationHeldIntoNextRow
     */
    public static void markIterationHeldIntoNextRowForReplay(
            TraceFrame current, TraceFrame next) {
        if (!isIterationHeldIntoNextRowForReplay(current, next)) {
            return;
        }
        markReplayIterationDefersLoopTailPreparation();
    }

    /** Holds the represented row's loop-tail PLC preparation for a later closure. */
    public static void markReplayIterationDefersLoopTailPreparation() {
        var gameplayMode = com.openggf.game.session.SessionManager.getCurrentGameplayMode();
        if (gameplayMode != null && gameplayMode.plcFrameLifecycle() != null) {
            gameplayMode.plcFrameLifecycle()
                    .markRepresentedIterationDefersLoopTailPreparation();
        }
    }

    /** Returns the structural in-flight-iteration classification used by replay closure. */
    public static boolean isIterationHeldIntoNextRowForReplay(
            TraceFrame current, TraceFrame next) {
        return TraceExecutionModel.isIterationHeldIntoNextRow(current, next);
    }

    public static TraceExecutionPhase phaseForReplay(TraceData trace,
                                                     TraceFrame previous,
                                                     TraceFrame current) {
        if (isPreLevelPrefixInputLatchRow(trace, previous, current)) {
            return TraceExecutionPhase.ADVANCE_ONLY;
        }
        if (shouldUsePreLevelIntroPrefix(trace, current)) {
            int firstLevelFrame = findFirstLevelGameplayFrame(trace);
            if (current.frame() < firstLevelFrame) {
                // The AIZ end-to-end trace starts while Game_Mode is $4C
                // (Level with transition bit set). Player_1/Player_2 RAM still
                // contains title-screen objects such as Obj_TitleBanner and
                // Obj_TitleSelection (sonic3k.asm:5995, 6168), not gameplay
                // Sonic/Tails. Advance the BK2/VBlank cursor for these frames,
                // but do not tick the loaded AIZ level until the first real
                // Level frame at the Obj_AIZPlaneIntro spawn point.
                return TraceExecutionPhase.VBLANK_ONLY;
            }
            if (current.frame() == firstLevelFrame) {
                // The first Level-mode frame (Game_Mode just transitioned from
                // $8C to $0C) is the boundary between ROM's synchronous setup
                // block (loc_62FE..loc_7882 -- Get_LevelSizeStart +
                // setup-DeformBgLayer + SpawnLevelMainSprites + Pal_FillBlack)
                // and the first LevelLoop iteration. ROM has already snapped
                // Camera_Y_pos via setup-DeformBgLayer (sonic3k.asm:7760), but
                // LevelLoop's Wait_VSync (sonic3k.asm:7888) -> DeformBgLayer
                // (sonic3k.asm:7897) doesn't run until the NEXT BK2 frame.
                //
                // The headless replay collapses ROM's two-phase setup into
                // initCameraForLevel + first LevelFrameStep, which already ran
                // by the time the comparator first checks this row. Treat this
                // boundary frame as VBLANK_ONLY so the engine's first physics
                // tick aligns with ROM's first LevelLoop iteration on the next
                // trace frame instead of double-counting the setup work.
                return TraceExecutionPhase.VBLANK_ONLY;
            }
            if (hasDirectLagCounterEvidence(previous, current)) {
                return TraceExecutionPhase.VBLANK_ONLY;
            }
            // Once the setup boundary has passed, the recorded intro runs the
            // ordinary native LevelLoop even while all sampled counters remain
            // pinned. Only direct lag evidence or the input-latch case above
            // may suppress that execution before gameplay_start.
            return TraceExecutionPhase.FULL_LEVEL_FRAME;
        }
        if (isSonic3kTransitionModeFrozenRow(trace, previous, current)) {
            return TraceExecutionPhase.VBLANK_ONLY;
        }
        if (isSonic3kMissingCpuExecutionLagRow(trace, previous, current)) {
            return TraceExecutionPhase.VBLANK_ONLY;
        }
        TraceExecutionPhase counterPhase =
                TraceExecutionModel.forGame(trace.metadata().game()).phaseFor(previous, current);
        if (counterPhase == TraceExecutionPhase.FULL_LEVEL_FRAME
                && isSidekickAnimationHeldAfterRawTransition(trace, previous, current)) {
            return TraceExecutionPhase.FULL_LEVEL_FRAME_WITH_SIDEKICK_ANIMATION_HELD;
        }
        if (counterPhase == TraceExecutionPhase.VBLANK_ONLY
                && (hasSidekickCpuExecutionHookWithoutInputEdge(trace, previous, current)
                        || hasPlayableSlotHistoryAdvanceWithoutInputEdge(
                                trace, previous, current))) {
            // The VBlank sample can land after the playable slots (and their
            // Animate calls) but before the rest of Process_Sprites completes.
            // The native Tails normal-step hook normally proves that prefix
            // ran. During ending routine $06 that hook is absent, but
            // Pos_table_index advancing by one Sonic_RecordPos entry proves the
            // same playable-slot prefix completed. Advance only playable
            // animation state; a complete level tick would also run later
            // object slots that are not yet visible in this sample.
            return TraceExecutionPhase.PLAYABLE_ANIMATION_ONLY;
        }
        if (counterPhase == TraceExecutionPhase.VBLANK_ONLY
                && hasSidekickCpuExecutionHookOnInputEdge(trace, previous, current)) {
            // A Tails CPU normal-step event is emitted from inside the native
            // player/object loop, so it is direct execution evidence. Some S3K
            // captures expose a plateaued gameplay counter and a VBlank-byte
            // change on the same row as a fresh input edge while the main player
            // is stationary; counter/state-only classification would call that
            // row VBlank-only even though the CPU routine, animation scripts, and
            // object logic all ran. A lag-counter advance remains authoritative:
            // recorder hook data on such rows belongs to the preceding completed
            // loop. Promote only input-edge rows carrying the execution hook --
            // sampled cpu_state values alone are not sufficient -- and keep trace
            // data comparison-only.
            return TraceExecutionPhase.FULL_LEVEL_FRAME;
        }
        return counterPhase;
    }

    /**
     * Detects a VBlank sample that lands after CPU-sidekick movement selected a
     * new raw animation but before the following {@code Animate_Tails} call.
     *
     * <p>The normal-step hook or one {@code Pos_table_index} entry proves the
     * playable prefix executed: {@code Tails_Control} runs CPU control and
     * movement, then {@code Sonic_RecordPos}, then {@code Animate_Tails}
     * (sonic3k.asm:26238-26284). The changed sidekick physics and three-row
     * animation transition prove the first mapping remained visible when the
     * sample interrupted that final animation dispatch. This is native
     * execution scheduling; no recorded value is copied into engine state.
     */
    private static boolean isSidekickAnimationHeldAfterRawTransition(
            TraceData trace, TraceFrame previous, TraceFrame current) {
        if (trace == null || previous == null || current == null
                || !"s3k".equals(trace.metadata().game())
                || current.input() != previous.input()
                || !(hasTailsCpuNormalStep(trace, current)
                        || hasPlayableSlotHistoryAdvanceWithoutInputEdge(
                                trace, previous, current))
                || current.sidekick() == null || previous.sidekick() == null
                || current.sidekick().physicsStateEquals(previous.sidekick())) {
            return false;
        }
        int index = traceIndexForFrame(trace, current);
        if (index < 2 || index + 1 >= trace.frameCount()) {
            return false;
        }
        TraceCharacterState beforeTransition = trace.getFrame(index - 2).sidekick();
        TraceCharacterState prior = previous.sidekick();
        TraceCharacterState sampled = current.sidekick();
        TraceCharacterState next = trace.getFrame(index + 1).sidekick();
        return beforeTransition != null && next != null
                && prior.present() && sampled.present() && next.present()
                && prior.animationId() >= 0 && prior.mappingFrame() >= 0
                // S3K raw anim 0 is the $FF Walk special handler. Unlike
                // ordinary delayed scripts, a fresh Walk dispatch publishes
                // its first mapping and advances anim_frame immediately.
                && prior.animationId() == 0
                // Duck ($08) is selected while the CPU sidekick is held down;
                // its movement release writes Walk before the bisected
                // Animate_Tails dispatch. Other object-owned exits can write
                // both anim and mapping directly and must not be held.
                && beforeTransition.animationId() == 0x08
                && sampled.animationId() == prior.animationId()
                && sampled.mappingFrame() == prior.mappingFrame()
                && next.animationId() == sampled.animationId()
                && next.mappingFrame() != sampled.mappingFrame();
    }

    private static boolean hasTailsCpuNormalStep(TraceData trace, TraceFrame frame) {
        for (TraceEvent event : trace.getEventsForFrame(frame.frame())) {
            if (event instanceof TraceEvent.TailsCpuNormalStep) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSonic3kMissingCpuExecutionLagRow(
            TraceData trace, TraceFrame previous, TraceFrame current) {
        if (trace == null || previous == null || current == null
                || !"s3k".equals(trace.metadata().game())
                || !trace.metadata().hasPerFrameTailsCpuNormalStep()
                || !current.stateEquals(previous)
                || current.gameplayFrameCounter() != previous.gameplayFrameCounter()
                || current.vblankCounter() != previous.vblankCounter()
                || current.lagCounter() != previous.lagCounter()
                || !hasRecordedVelocity(current)
                || (current.statusByte() & 0x08) != 0
                || (current.sidekick() != null
                        && (current.sidekick().statusByte() & 0x08) != 0)) {
            return false;
        }
        for (String sidekick : trace.metadata().recordedSidekicks()) {
            if (trace.tailsCpuNormalStepForFrame(previous.frame(), sidekick) != null
                    && trace.tailsCpuNormalStepForFrame(current.frame(), sidekick) == null) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasRecordedVelocity(TraceFrame frame) {
        if (frame.xSpeed() != 0 || frame.ySpeed() != 0 || frame.gSpeed() != 0) {
            return true;
        }
        TraceCharacterState sidekick = frame.sidekick();
        return sidekick != null
                && (sidekick.xSpeed() != 0 || sidekick.ySpeed() != 0 || sidekick.gSpeed() != 0);
    }

    private static boolean hasSidekickCpuExecutionHookOnInputEdge(
            TraceData trace, TraceFrame previous, TraceFrame current) {
        if (trace == null || previous == null || current == null
                || current.input() == previous.input()
                || (current.lagCounter() >= 0 && previous.lagCounter() >= 0
                        && current.lagCounter() > previous.lagCounter())) {
            return false;
        }
        for (TraceEvent event : trace.getEventsForFrame(current.frame())) {
            if (event instanceof TraceEvent.TailsCpuNormalStep) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasSidekickCpuExecutionHookWithoutInputEdge(
            TraceData trace, TraceFrame previous, TraceFrame current) {
        if (trace == null || previous == null || current == null
                || current.input() != previous.input()
                || (current.lagCounter() >= 0 && previous.lagCounter() >= 0
                        && current.lagCounter() > previous.lagCounter())) {
            return false;
        }
        for (TraceEvent event : trace.getEventsForFrame(current.frame())) {
            if (event instanceof TraceEvent.TailsCpuNormalStep) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasPlayableSlotHistoryAdvanceWithoutInputEdge(
            TraceData trace, TraceFrame previous, TraceFrame current) {
        if (trace == null || previous == null || current == null
                || current.input() != previous.input()
                || (current.lagCounter() >= 0 && previous.lagCounter() >= 0
                        && current.lagCounter() > previous.lagCounter())) {
            return false;
        }
        for (String sidekick : trace.metadata().recordedSidekicks()) {
            TraceEvent.CpuState before =
                    trace.cpuStateForFrame(previous.frame(), sidekick);
            TraceEvent.CpuState after =
                    trace.cpuStateForFrame(current.frame(), sidekick);
            if (before != null && after != null
                    && ((after.posTableIndex() - before.posTableIndex()) & 0xFF) == 4) {
                return true;
            }
        }
        return false;
    }

    private static boolean isS3kCompleteRunInitialHandoffRow(TraceData trace,
                                                             TraceFrame previous,
                                                             TraceFrame current) {
        if (!isS3kCompleteRunSegment(trace) || current == null) {
            return false;
        }
        if (previous != null || current.frame() != 0) {
            return false;
        }
        TraceFrame next = trace.getFrame(1);
        return (next == null || !current.stateEquals(next))
                && !hasNativeInitialVelocity(current);
    }

    private static boolean isS3kCompleteRunVisibleVelocityHoldRow(TraceData trace,
                                                                  TraceFrame previous,
                                                                  TraceFrame current) {
        if (!isS3kCompleteRunSegment(trace) || current == null || !hasNativeInitialVelocity(current)) {
            return false;
        }
        int index = traceIndexForFrame(trace, current);
        if (index < 0) {
            return false;
        }
        if (index == 0) {
            if (trace.frameCount() < 2) {
                return false;
            }
            TraceFrame next = trace.getFrame(1);
            return current.stateEquals(next) && executionCountersEqual(current, next);
        }
        if (previous == null
                || !current.stateEquals(previous)
                || !executionCountersEqual(current, previous)) {
            return false;
        }
        for (int i = 1; i <= index; i++) {
            TraceFrame prior = trace.getFrame(i - 1);
            TraceFrame row = trace.getFrame(i);
            if (!row.stateEquals(prior) || !executionCountersEqual(row, prior)) {
                return false;
            }
        }
        return true;
    }

    private static int traceIndexForFrame(TraceData trace, TraceFrame frame) {
        int frameNumber = frame.frame();
        if (frameNumber >= 0 && frameNumber < trace.frameCount()
                && trace.getFrame(frameNumber).frame() == frameNumber) {
            return frameNumber;
        }
        for (int i = 0; i < trace.frameCount(); i++) {
            if (trace.getFrame(i).frame() == frameNumber) {
                return i;
            }
        }
        return -1;
    }

    private static boolean executionCountersEqual(TraceFrame left, TraceFrame right) {
        return left.gameplayFrameCounter() == right.gameplayFrameCounter()
                && left.vblankCounter() == right.vblankCounter()
                && left.lagCounter() == right.lagCounter();
    }

    private static boolean hasNativeInitialVelocity(TraceFrame current) {
        return current.xSpeed() != 0
                || current.ySpeed() != 0
                || current.gSpeed() != 0;
    }

    private static boolean isSonic3kTransitionModeFrozenRow(
            TraceData trace, TraceFrame previous, TraceFrame current) {
        if (trace == null || previous == null || current == null
                || !"s3k".equals(trace.metadata().game())
                || current.gameplayFrameCounter() != previous.gameplayFrameCounter()
                || current.vblankCounter() != previous.vblankCounter()
                || current.lagCounter() != previous.lagCounter()) {
            return false;
        }
        TraceEvent.ZoneActState zoneActState = trace.latestZoneActStateAtOrBefore(current.frame());
        if (zoneActState == null || zoneActState.frame() == current.frame()
                || zoneActState.gameMode() == null) {
            return false;
        }
        int gameMode = zoneActState.gameMode();
        return (gameMode & 0x80) != 0 && (gameMode & 0x0C) == 0x0C;
    }

    /**
     * Returns how many of a segment's recorded rows the ROM produced from inside
     * its {@code LevelLoop} -- that is, the rows a live LEVEL comparator can ever
     * represent.
     *
     * <p>The recorder cuts a run's segments at the DESTINATION's first gameplay
     * row, so a segment that ends in a level reload carries the whole ROM
     * transition in its own tail. {@code Level:} opens with
     * {@code bset #7,(Game_mode).w} (sonic3k.asm:7505) and only clears that bit
     * again at the destination's first gameplay frame, which the recorder files
     * in the NEXT segment. Everything the ROM records between those two points
     * is inside {@code Level:}:
     *
     * <ul>
     *   <li>{@code Pal_FadeToBlack}'s {@code move.w #$15,d4} + {@code dbf} loop
     *       (sonic3k.asm:5042-5052, entered at :7522) -- one recorded row per
     *       {@code Wait_VSync} iteration, with the camera, the player and
     *       {@code Level_frame_counter} all frozen while VBlank keeps ticking;</li>
     *   <li>the {@code Clear_DisplayData} / {@code move.w d0,(Level_frame_counter).w}
     *       RAM wipe (sonic3k.asm:7532-7536);</li>
     *   <li>the destination level's own load.</li>
     * </ul>
     *
     * <p>None of that is a LevelLoop iteration of the SOURCE level, so no source
     * comparator can consume it: the engine has left LEVEL by then and performs
     * the reload synchronously. This is the ENTRY-side statement of the same fact
     * the bonus-exit handling already relies on.
     *
     * <p>The classification is read out of recorded ROM state -- the terminal
     * {@code zone_act_state} {@code game_mode} reload bit -- not out of a row
     * count, a fade length, a zone id, or a segment name, so it holds for a
     * recording with a different fade or load duration. The row that CARRIES the
     * transition is still counted: {@code LevelLoop} increments
     * {@code Level_frame_counter} (sonic3k.asm:7889) before it tests
     * {@code Restart_level_flag} and branches to {@code Level:} (:7893-7894), so
     * that row is a complete LevelLoop iteration and must still be compared.
     *
     * @return the number of leading in-level rows, which is the full row count
     *         for any segment that does not end inside a reload
     */
    public static int levelLoopRowCount(TraceData trace) {
        if (trace == null || trace.frameCount() == 0) {
            return 0;
        }
        int lastRowFrame = trace.getFrame(trace.frameCount() - 1).frame();
        TraceEvent.ZoneActState terminalState =
                trace.latestZoneActStateAtOrBefore(lastRowFrame);
        if (terminalState == null || terminalState.gameMode() == null
                || (terminalState.gameMode() & 0x80) == 0) {
            return trace.frameCount();
        }
        int rows = trace.frameCount();
        while (rows > 0 && trace.getFrame(rows - 1).frame() > terminalState.frame()) {
            rows--;
        }
        return rows;
    }

    public static boolean shouldCompareGameplayStateForReplay(TraceExecutionPhase phase) {
        return phase == TraceExecutionPhase.FULL_LEVEL_FRAME
                || phase == TraceExecutionPhase.PLAYABLE_ANIMATION_ONLY;
    }

    /**
     * Returns the frame values that should be compared after a replay step.
     *
     * <p>S1/S2 traces are sampled by Lua once per emulator frame, while the ROM
     * can expose a full Level_MainLoop row followed by a VBlank-only row with
     * the same gameplay counter and unchanged player state. In that split, the
     * first row owns gameplay state and the following row owns VBlank-updated
     * diagnostics such as camera position and ring count. The engine's
     * headless step presents those VBlank diagnostics together with the
     * gameplay step, so compare gameplay fields from {@code current} and
     * visual diagnostics from the immediately following VBlank-only row.
     */
    public static TraceFrame frameForGameplayComparison(TraceData trace,
                                                        int currentIndex,
                                                        TraceFrame previous,
                                                        TraceFrame current,
                                                        TraceExecutionPhase currentPhase) {
        if (trace == null || current == null
                || currentPhase != TraceExecutionPhase.FULL_LEVEL_FRAME
                || currentIndex + 1 >= trace.frameCount()) {
            return current;
        }

        TraceFrame next = trace.getFrame(currentIndex + 1);
        TraceExecutionPhase nextPhase = phaseForReplay(trace, current, next);
        if (nextPhase != TraceExecutionPhase.VBLANK_ONLY
                || !current.stateEquals(next)
                || current.gameplayFrameCounter() != next.gameplayFrameCounter()
                || current.cameraX() < 0 || current.cameraY() < 0
                || next.cameraX() < 0 || next.cameraY() < 0) {
            return current;
        }

        return current.withVisualDiagnosticsFrom(next);
    }

    /**
     * Returns the S3K frame values that should be compared after a replay step.
     *
     * <p>S3K can expose the same full-frame/VBlank-only split as S1/S2. Camera
     * diagnostics belong to the following VBlank row in that case, while ring
     * counts remain strict to the current gameplay row so a collection-timing
     * mismatch cannot be hidden by expected-value normalization.
     */
    public static TraceFrame s3kFrameForGameplayComparison(TraceData trace,
                                                            int currentIndex,
                                                            TraceFrame previous,
                                                            TraceFrame current,
                                                            TraceExecutionPhase currentPhase) {
        if (trace == null || current == null
                || currentPhase != TraceExecutionPhase.FULL_LEVEL_FRAME
                || currentIndex + 1 >= trace.frameCount()) {
            return current;
        }

        TraceFrame next = trace.getFrame(currentIndex + 1);
        TraceExecutionPhase nextPhase = phaseForReplay(trace, current, next);
        if (nextPhase != TraceExecutionPhase.VBLANK_ONLY
                || !current.stateEquals(next)
                || current.gameplayFrameCounter() != next.gameplayFrameCounter()
                || current.cameraX() < 0 || current.cameraY() < 0
                || next.cameraX() < 0 || next.cameraY() < 0) {
            return current;
        }

        return current.withCameraDiagnosticsFrom(next);
    }

    /**
     * Returns the S3K frame values that should be compared after a replay step.
     *
     * <p>S3K trace comparison is intentionally row-strict for ring counts. Earlier
     * release candidates borrowed a next-row ring diagnostic when the engine
     * already matched that next row, but that rewrote the expected value before
     * {@link TraceBinder} could report a real ring-count mismatch.
     */
    public static TraceFrame s3kFrameForRingDiagnosticComparison(TraceData trace,
                                                                 int currentIndex,
                                                                 TraceFrame current,
                                                                 EngineDiagnostics engineDiag) {
        return current;
    }

    private static boolean shouldUsePreLevelIntroPrefix(TraceData trace,
                                                        TraceFrame current) {
        if (trace == null || current == null || !hasRecordedPreLevelPrefix(trace)) {
            return false;
        }
        int gameplayStartFrame = findCheckpointFrame(trace, "gameplay_start");
        return gameplayStartFrame >= 0 && current.frame() <= gameplayStartFrame;
    }

    private static boolean isPreLevelPrefixInputLatchRow(TraceData trace,
                                                         TraceFrame previous,
                                                         TraceFrame current) {
        if (trace == null || previous == null || current == null
                || !shouldUsePreLevelIntroPrefix(trace, current)
                || current.input() == previous.input()) {
            return false;
        }
        return current.stateEquals(previous)
                && current.gameplayFrameCounter() == previous.gameplayFrameCounter()
                && current.vblankCounter() == previous.vblankCounter()
                && current.lagCounter() == previous.lagCounter();
    }

    public static boolean shouldUsePreviousRecordingInputForTraceReplay(TraceData trace) {
        return hasRecordedPreLevelPrefix(trace);
    }

    /**
     * Identifies an S3K Sonic+Tails complete-run per-zone segment. These
     * fixtures arm at a zone's first control-unlocked frame; five of the seven
     * are entered mid-run from the previous zone's seamless act/zone handoff.
     * The predicate keys off the recording's structural identity - S3K, a
     * recorded sidekick, and the {@code complete_run} trace profile - never a
     * zone id, route, or frame number. Pre-level-prefix traces drive their
     * cutscene prefix from trace frame 0 and are excluded.
     */
    public static boolean isS3kCompleteRunSegment(TraceData trace) {
        if (trace == null || trace.frameCount() == 0) {
            return false;
        }
        TraceMetadata metadata = trace.metadata();
        if (metadata == null
                || !"s3k".equals(metadata.game())
                || metadata.recordedSidekicks().isEmpty()
                || !"complete_run".equals(metadata.traceProfile())
                || hasRecordedPreLevelPrefix(trace)) {
            return false;
        }
        return replaySeedTraceIndexForTraceReplay(trace) == 0;
    }

    private static boolean hasDirectLagCounterEvidence(TraceFrame previous,
                                                       TraceFrame current) {
        return previous != null
                && current != null
                && previous.lagCounter() >= 0
                && current.lagCounter() > previous.lagCounter();
    }

    private static int findCheckpointFrame(TraceData trace, String checkpointName) {
        for (int frame = 0; frame < trace.frameCount(); frame++) {
            for (TraceEvent event : trace.getEventsForFrame(frame)) {
                if (event instanceof TraceEvent.Checkpoint checkpoint
                        && checkpointName.equals(checkpoint.name())) {
                    return frame;
                }
            }
        }
        return -1;
    }

    private static int findFirstLevelGameplayFrame(TraceData trace) {
        for (int frame = 0; frame < trace.frameCount(); frame++) {
            for (TraceEvent event : trace.getEventsForFrame(frame)) {
                if (event instanceof TraceEvent.ZoneActState state
                        && state.gameMode() != null
                        && state.gameMode() == 12) {
                    return frame;
                }
                if (event instanceof TraceEvent.Checkpoint checkpoint
                        && checkpoint.gameMode() != null
                        && checkpoint.gameMode() == 12) {
                    return frame;
                }
            }
        }
        return 0;
    }

}
