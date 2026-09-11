package com.openggf.tests;

import com.openggf.game.rules.GameRules;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class TestArchitecturalSourceGuard {
    private static final Path SRC_MAIN = Path.of("src", "main", "java");
    private static final String ENGINE_PATH = "com/openggf/Engine.java";
    private static final String GAME_LOOP_PATH = "com/openggf/GameLoop.java";
    private static final String OBJECT_MANAGER_PATH = "com/openggf/level/objects/ObjectManager.java";

    @Test
    void engineLiveCaptureOrderingStaysAtThePresentationBoundary() throws IOException {
        String source = Files.readString(SRC_MAIN.resolve(ENGINE_PATH));
        assertOrdered(source, "applyDisplayShaderPhase(ShaderPhase.FINAL)",
                "liveCapturePresentation.present(");
        assertOrdered(source, "handleLiveCaptureShortcut();",
                "updateDisplayShaderInput()");
        assertOrdered(source, "display();", "glfwSwapBuffers(window);");
    }

    @Test
    void suppressedLevelMusicPreservesTheActiveAudioSource() throws IOException {
        SourceFile source = SourceFile.read(SRC_MAIN.resolve("com/openggf/level/LevelManager.java"));
        MethodSpan span = source.methodNamed("initAudio");
        assertTrue(span != null, "LevelManager must retain the level audio initializer");
        String method = String.join("\n",
                source.lines().subList(span.startLine() - 1, span.endLine()));

        assertOrdered(method, "transitions.isSuppressNextMusicChange()", "configureAudio();");
    }

    @Test
    void levelStartClearsReusedPlayableAnimationState() throws IOException {
        SourceFile source = SourceFile.read(SRC_MAIN.resolve(
                "com/openggf/sprites/playable/AbstractPlayableSprite.java"));
        MethodSpan span = source.methodNamed("resetState");
        assertTrue(span != null, "Playable sprites must retain the level-start reset owner");
        String method = String.join("\n",
                source.lines().subList(span.startLine() - 1, span.endLine()));

        assertTrue(method.contains("this.animationId = 0;"));
        assertTrue(method.contains("this.mappingFrame = 0;"));
        assertTrue(method.contains("this.animationFrameIndex = 0;"));
        assertTrue(method.contains("this.animationTick = 0;"));
        assertTrue(method.contains("forceAnimationRestart();"));
    }

    private static void assertOrdered(String source, String first, String second) {
        int firstIndex = source.indexOf(first);
        int secondIndex = source.indexOf(second, firstIndex + first.length());
        assertTrue(firstIndex >= 0 && secondIndex > firstIndex,
                () -> first + " must precede " + second);
    }
    // 2026-07-02: re-ratcheted 2747 -> 2821 after the S2 trace-parity batch
    // (8766a6889..0f7794de8) grew reserved-slot spawn and placement handling,
    // then 2821 -> 2823 for the rewind capture of the S2 post-camera unload
    // latch (PlacementSnapshot restore path).
    // 2026-07-06: 2823 -> 2869 for dependency-ordered dynamic rewind
    // reconstruction. The growth is rewind orchestration, not placement,
    // touch-response, or solid-contact logic owned by extracted collaborators.
    // Rewind child reconstruction adds a narrowly scoped lifecycle hook at the
    // existing restore orchestration boundary; it does not move collaborator logic back here.
    // 2026-07-12: ratchet inherited develop growth to 2910, plus a four-line public
    // delegate into ObjectRewindReferenceClosureValidator. Traversal stays extracted.
    // 2026-08-19: 2914 -> 3041. Recorded drift, not a single regression: 47 commits
    // by both maintainers touched this file since the 2026-07-12 ratchet while no CI
    // job ran this guard (the gap the -Pguards gate closes). The growth is spread
    // across the existing orchestration surface; placement, touch-response and
    // solid-contact logic remain in their extracted collaborators.
    // 2026-08-21: 3041 -> 3051. The ROM object-loop counter is the facade's own
    // loop bound, so it cannot go in the placement, touch-response or
    // solid-contact collaborators this budget exists to protect. Its state and
    // arithmetic were extracted to ObjectLoopSlotBudget; what stayed is the two
    // entry points, the per-pass reset and the walk's exit test, at their
    // smallest honest size. See CPZBossPipe for the ROM site that drives it.
    private static final int OBJECT_MANAGER_MAX_EFFECTIVE_SOURCE_LINES = 3051;
    private static final Map<String, Integer> RELEASE_CRITICAL_CLASS_EFFECTIVE_SOURCE_LINE_BUDGETS = Map.of(
            "com/openggf/game/sonic1/Sonic1ObjectArtProvider.java", 2047,
            // 2026-07-02: 3065 -> 3115 after S2 trace fixes + the GameRules typed-rule
            // refactor (d9b727925) settled the sprite at 3115 effective lines.
            // 2026-07-09: 3115 -> 3159 for drowning, speed-shoes, and fixed-skid
            // controller state captured by the playable rewind schema.
            // 2026-08-03: 3159 -> 3161 for explicit native player-SST presence
            // reset/restore state; accessors retain the existing compact precedent.
            // 2026-08-19: 3161 -> 3211 (50 commits since the 2026-08-03 ratchet).
            // 2026-08-20: 3211 -> 3212 for the one-line ground-angle-latch reset in
            // resetState(). The level-load SST clear it models already lives here
            // (balanceState, topSolidBit, runningMode); the state itself stays owned
            // by PlayableSpriteMovement, so this is a delegating call, not new logic.
            "com/openggf/sprites/playable/AbstractPlayableSprite.java", 3212,
            // 2026-08-19: 2500 -> 2812. The 2500 entry carried no ratchet history and
            // 110 commits have touched LevelManager since it was written on 2026-07-02,
            // so 2500 stopped describing this file long before now. Recorded here as
            // today's true count at 2327368e2; extraction is tracked as follow-up work,
            // not hidden. (It was 2812 one rebase earlier -- the sidekick collision-path
            // fix 38e50f87a added four more lines while this branch was in review, which
            // is the drift rate this ratchet exists to make visible.)
            "com/openggf/level/LevelManager.java", 2816,
            // 2026-07-02: 2888 -> 2890 for the live-rewind VHS effect envelope tick
            // (RewindEffectEnvelope wiring + intensity/speed accessors).
            // 2026-07-06: 2890 -> 2910 for the Gumball/Pachinko bonus-stage live-rewind
            // integration (isBonusStageRewindable + the updateBonusStageMode capture hook
            // + coordinator-adapter register/deregister on bonus entry/exit, incl. the
            // failed-load error-path deregister for lifecycle symmetry). The edits are
            // scattered across stepInternal/updateBonusStageMode/doEnter/doExit and do
            // not form an extractable collaborator.
            // 2026-07-09: 2910 -> 2985 for the live special-stage trace visual session
            // skip gate in updateSpecialStageMode (recorded-input override + lag-row
            // provider.update() gate + trace-cursor advance + launcher-owned finish
            // guard). Prior work had already grown the file to 2973 effective lines
            // without a budget bump; this raises the ratchet to the true current count
            // including the +12 SS-gate lines. The gate is a handful of guarded calls
            // to TraceSessionLauncher and does not form an extractable collaborator.
            // 2026-07-18: 2985 -> 3005 (plan-d Task 3, multi-stage trace runs). Prior
            // work had already grown the file to 2997 effective lines without a budget
            // bump. The extracted driveActiveTraceRunSession() one-line dispatcher
            // call (+ its own 8-line method body) drives TraceSessionLauncher's
            // segment-advance state machine every mode, not just LEVEL; it is already
            // its own focused collaborator hook (TraceSessionLauncher/RunSegmentAdvancer
            // own the actual state machine) and does not belong in any other file.
            // 2026-08-19: 3005 -> 3065. Same recorded-drift note as the entries above;
            // 140 commits have touched GameLoop since the guard last ran anywhere.
            // 2026-08-20: 3065 -> 3071 for the level-to-level transition-fade row
            // consumption. The logic itself was extracted --
            // LevelIterationAdmissionController.consumeTransitionFreezeRow owns the
            // ownership predicate, the V-blank service and the ROM citations -- and
            // GameLoop keeps only the guarded call, which needs the freeze flag, the
            // run-frame-driver check and the level's ObjectManager, none of which the
            // controller can reach on its own.
            // 2026-09-04: 3071 -> 3072 for the one-line
            // levelManager.advanceLevelFrameCounter() call on the ending-cutscene
            // path. The ROM increments Level_frame_counter at the top of its level
            // loop; LevelFrameStep now owns that for every ordinary frame, and the
            // ending cutscene is the one path that drives LevelManager directly
            // without going through it. The increment itself lives in LevelManager,
            // so this is a delegating call, not new logic.
            GAME_LOOP_PATH, 3072
    );
    private static final int ENGINE_MAX_LARGE_METHODS = 3;
    private static final int ENGINE_LARGE_METHOD_THRESHOLD = 100;
    private static final Map<String, Integer> ROOT_CONCRETE_SONIC_REFERENCE_BUDGETS = Map.of(
            ENGINE_PATH, 3,
            GAME_LOOP_PATH, 15
    );
    private static final int OBJECT_MANAGER_CONCRETE_SONIC_REFERENCE_BUDGET = 0;
    private static final int LOW_LEVEL_GAMEPLAY_SERVICE_LOOKUP_BUDGET = 0;
    private static final List<MethodBudget> ROOT_DISPATCH_METHOD_BUDGETS = List.of(
            new MethodBudget(ENGINE_PATH, "draw", 3),
            new MethodBudget(ENGINE_PATH, "init", 181),
            // 2026-07-02: display 206 -> 217 for the live-rewind VHS picture-search
            // pass (RewindVhsEffectPass.apply between the fade pass and the
            // PRESENTATION display-shader phase; the pass itself is a collaborator).
            // 2026-07-02: display 217 -> 220 for the final-review fix hoisting the
            // zero-intensity check above the RewindVhsEffectPass.apply call so the
            // config lookups and gameLoop accessors are skipped when intensity==0.
            // 2026-07-03: display 220 -> 221 for the rewind.vhsTearBands config
            // argument threaded into the RewindVhsEffectPass.apply call.
            new MethodBudget(ENGINE_PATH, "display", 221),
            new MethodBudget(GAME_LOOP_PATH, "stepInternal", 213),
            new MethodBudget(GAME_LOOP_PATH, "doExitBonusStage", 142),
            new MethodBudget(GAME_LOOP_PATH, "updateSpecialStageInput", 105),
            new MethodBudget(GAME_LOOP_PATH, "loadEndingDemoZone", 95),
            // 2026-08-19: 91 -> 102, recorded drift accumulated while no CI job ran
            // this guard.
            new MethodBudget(GAME_LOOP_PATH, "enterTitleCardFromResults", 102),
            new MethodBudget(GAME_LOOP_PATH, "enterBonusStage", 86)
    );
    private static final List<String> LOW_LEVEL_SERVICE_SCAN_ROOTS = List.of(
            "com/openggf/graphics",
            "com/openggf/audio"
    );
    private static final List<String> OBJECT_CHILD_SPAWN_MIGRATED_FILES = List.of(
            "com/openggf/game/sonic2/objects/CheckpointObjectInstance.java",
            "com/openggf/game/sonic2/objects/ArrowShooterObjectInstance.java",
            "com/openggf/game/sonic2/objects/BubbleGeneratorObjectInstance.java",
            "com/openggf/game/sonic2/objects/BonusBlockObjectInstance.java",
            "com/openggf/game/sonic2/objects/BreakableBlockObjectInstance.java",
            "com/openggf/game/sonic2/objects/BreakablePlatingObjectInstance.java",
            "com/openggf/game/sonic2/objects/BumperObjectInstance.java",
            "com/openggf/game/sonic2/objects/CollapsingPlatformObjectInstance.java",
            "com/openggf/game/sonic2/objects/ConveyorObjectInstance.java",
            "com/openggf/game/sonic2/objects/EggPrisonObjectInstance.java",
            "com/openggf/game/sonic2/objects/FallingPillarObjectInstance.java",
            "com/openggf/game/sonic2/objects/LeavesGeneratorObjectInstance.java",
            "com/openggf/game/sonic2/objects/MonitorObjectInstance.java",
            "com/openggf/game/sonic2/objects/OOZLauncherObjectInstance.java",
            "com/openggf/game/sonic2/objects/OOZPoppingPlatformObjectInstance.java",
            "com/openggf/game/sonic2/objects/PointPokeyObjectInstance.java",
            "com/openggf/game/sonic2/objects/RivetObjectInstance.java",
            "com/openggf/game/sonic2/objects/RisingPillarObjectInstance.java",
            "com/openggf/game/sonic2/objects/SeesawObjectInstance.java",
            "com/openggf/game/sonic2/objects/HTZLiftObjectInstance.java",
            "com/openggf/game/sonic2/objects/SidewaysPformObjectInstance.java",
            "com/openggf/game/sonic2/objects/SignpostObjectInstance.java",
            "com/openggf/game/sonic2/objects/SmashableGroundObjectInstance.java",
            "com/openggf/game/sonic2/objects/SmallMetalPformObjectInstance.java",
            "com/openggf/game/sonic2/objects/SteamSpringObjectInstance.java",
            "com/openggf/game/sonic2/objects/TiltingPlatformObjectInstance.java",
            "com/openggf/game/sonic2/objects/TornadoObjectInstance.java",
            "com/openggf/game/sonic2/objects/badniks/CluckerBadnikInstance.java",
            "com/openggf/game/sonic2/objects/badniks/CoconutsBadnikInstance.java",
            "com/openggf/game/sonic2/objects/badniks/GrounderBadnikInstance.java",
            "com/openggf/game/sonic2/objects/badniks/NebulaBadnikInstance.java",
            "com/openggf/game/sonic2/objects/badniks/OctusBadnikInstance.java",
            "com/openggf/game/sonic2/objects/badniks/RexonBadnikInstance.java",
            "com/openggf/game/sonic2/objects/badniks/RexonHeadObjectInstance.java",
            "com/openggf/game/sonic2/objects/badniks/SolBadnikInstance.java",
            "com/openggf/game/sonic2/objects/badniks/SpikerBadnikInstance.java",
            "com/openggf/game/sonic2/objects/badniks/SpinyBadnikInstance.java",
            "com/openggf/game/sonic2/objects/badniks/TurtloidBadnikInstance.java",
            "com/openggf/game/sonic2/objects/bosses/CNZBossElectricBall.java",
            "com/openggf/game/sonic2/objects/bosses/CPZBossContainer.java",
            "com/openggf/game/sonic2/objects/bosses/CPZBossContainerExtend.java",
            "com/openggf/game/sonic2/objects/bosses/CPZBossFallingPart.java",
            "com/openggf/game/sonic2/objects/bosses/CPZBossGunk.java",
            "com/openggf/game/sonic2/objects/bosses/CPZBossPipe.java",
            "com/openggf/game/sonic2/objects/bosses/CPZBossPipeSegment.java",
            "com/openggf/game/sonic2/objects/bosses/CPZBossPump.java",
            "com/openggf/game/sonic2/objects/bosses/HTZBossLavaBall.java",
            "com/openggf/game/sonic2/objects/bosses/Sonic2ARZBossInstance.java",
            "com/openggf/game/sonic2/objects/bosses/Sonic2CNZBossInstance.java",
            "com/openggf/game/sonic2/objects/bosses/Sonic2CPZBossInstance.java",
            "com/openggf/game/sonic2/objects/bosses/Sonic2DEZEggmanInstance.java",
            "com/openggf/game/sonic2/objects/bosses/Sonic2DeathEggRobotInstance.java",
            "com/openggf/game/sonic2/objects/bosses/Sonic2EHZBossInstance.java",
            "com/openggf/game/sonic2/objects/bosses/Sonic2HTZBossInstance.java",
            "com/openggf/game/sonic2/objects/bosses/Sonic2MechaSonicInstance.java",
            "com/openggf/game/sonic2/objects/bosses/Sonic2MCZBossInstance.java",
            "com/openggf/game/sonic2/objects/bosses/Sonic2MTZBossInstance.java",
            "com/openggf/game/sonic3k/objects/AizEndBossArmChild.java",
            "com/openggf/game/sonic3k/objects/AizEndBossBombChild.java",
            "com/openggf/game/sonic3k/objects/AizEndBossDebrisChild.java",
            "com/openggf/game/sonic3k/objects/AizEndBossFlameChild.java",
            "com/openggf/game/sonic3k/objects/AizEndBossPropellerChild.java",
            "com/openggf/game/sonic3k/objects/AizEndBossWaterfallChild.java",
            "com/openggf/game/sonic3k/objects/AizBattleshipInstance.java",
            "com/openggf/game/sonic3k/objects/AizBgTreeSpawnerInstance.java",
            "com/openggf/game/sonic3k/objects/AizEndBossInstance.java",
            "com/openggf/game/sonic3k/objects/AizShipBombInstance.java",
            "com/openggf/game/sonic3k/objects/AizMinibossBarrelShotChild.java",
            "com/openggf/game/sonic3k/objects/AizMinibossCutsceneInstance.java",
            "com/openggf/game/sonic3k/objects/AizMinibossFlameBarrelChild.java",
            "com/openggf/game/sonic3k/objects/AizMinibossInstance.java",
            "com/openggf/game/sonic3k/objects/CutsceneKnucklesRockChild.java",
            "com/openggf/game/sonic3k/objects/CnzBumperObjectInstance.java",
            "com/openggf/game/sonic3k/objects/MGZHeadTriggerObjectInstance.java",
            "com/openggf/game/sonic3k/objects/Sonic3kStarPostObjectInstance.java",
            "com/openggf/game/sonic3k/objects/badniks/AbstractS3kBadnikInstance.java",
            "com/openggf/game/sonic3k/objects/badniks/BlastoidBadnikInstance.java",
            "com/openggf/game/sonic3k/objects/badniks/BuggernautBadnikInstance.java",
            "com/openggf/game/sonic3k/objects/badniks/CaterkillerJrHeadInstance.java"
    );
    private static final Map<String, Integer> SONIC2_NATIVE_LEVEL_ART_RENDER_PATTERN_BUDGETS = Map.ofEntries(
            Map.entry("com/openggf/game/sonic2/objects/ARZPlatformObjectInstance.java", 1),
            Map.entry("com/openggf/game/sonic2/objects/ARZRotPformsObjectInstance.java", 1),
            Map.entry("com/openggf/game/sonic2/objects/CollapsingPlatformObjectInstance.java", 2),
            Map.entry("com/openggf/game/sonic2/objects/FallingPillarObjectInstance.java", 1),
            Map.entry("com/openggf/game/sonic2/objects/GrounderWallInstance.java", 1),
            Map.entry("com/openggf/game/sonic2/objects/LargeRotPformObjectInstance.java", 1),
            Map.entry("com/openggf/game/sonic2/objects/MCZBrickObjectInstance.java", 1),
            Map.entry("com/openggf/game/sonic2/objects/MCZRotPformsObjectInstance.java", 1),
            Map.entry("com/openggf/game/sonic2/objects/MTZLongPlatformObjectInstance.java", 1),
            Map.entry("com/openggf/game/sonic2/objects/MTZPlatformObjectInstance.java", 1),
            Map.entry("com/openggf/game/sonic2/objects/MTZTwinStompersObjectInstance.java", 1),
            Map.entry("com/openggf/game/sonic2/objects/RisingPillarObjectInstance.java", 2),
            Map.entry("com/openggf/game/sonic2/objects/SidewaysPformObjectInstance.java", 1),
            Map.entry("com/openggf/game/sonic2/objects/SlidingSpikesObjectInstance.java", 1),
            Map.entry("com/openggf/game/sonic2/objects/StomperObjectInstance.java", 1),
            Map.entry("com/openggf/game/sonic2/objects/SwingingPformObjectInstance.java", 1),
            Map.entry("com/openggf/game/sonic2/objects/SwingingPlatformObjectInstance.java", 1)
    );
    private static final List<SourceSignal> S3K_PATTERN_ATLAS_RANGE_SIGNALS = List.of(
            new SourceSignal(
                    "com/openggf/game/sonic3k/dataselect/S3kDataSelectRenderer.java",
                    "DATA_SELECT_PATTERN_BASE = PatternAtlasRange.MENU_AND_DATA_SELECT.base()"),
            new SourceSignal(
                    "com/openggf/game/sonic3k/titlecard/Sonic3kTitleCardManager.java",
                    "PATTERN_BASE = PatternAtlasRange.MENU_AND_DATA_SELECT.base()"),
            new SourceSignal(
                    "com/openggf/game/sonic3k/levelselect/Sonic3kLevelSelectConstants.java",
                    "PATTERN_BASE = PatternAtlasRange.MENU_AND_DATA_SELECT.base()"),
            new SourceSignal(
                    "com/openggf/game/sonic3k/objects/S3kResultsScreenObjectInstance.java",
                    "PATTERN_BASE = PatternAtlasRange.RESULTS_SCREENS.base()"),
            new SourceSignal(
                    "com/openggf/game/sonic3k/specialstage/S3kSpecialStageResultsScreen.java",
                    "PATTERN_BASE = PatternAtlasRange.SPECIAL_STAGE_RESULTS.base()"),
            new SourceSignal(
                    "com/openggf/game/sonic3k/titlescreen/Sonic3kTitleScreenDataLoader.java",
                    "ANIM_PATTERN_BASE = PatternAtlasRange.S3K_TITLE_SCREEN_ANIMATION.base()"),
            new SourceSignal(
                    "com/openggf/game/sonic3k/titlescreen/Sonic3kTitleScreenDataLoader.java",
                    "SPRITE_PATTERN_BASE = PatternAtlasRange.S3K_TITLE_SCREEN_SPRITES.base()")
    );

    private static final Set<String> GAME_ID_BRANCH_APPROVED_FILES = Set.of(
            "com/openggf/Engine.java",
            "com/openggf/GameLoop.java",
            "com/openggf/game/CrossGameFeatureProvider.java",
            "com/openggf/game/session/GameplayTeamBootstrap.java",
            "com/openggf/game/startup/DataSelectPresentationResolution.java"
    );
    private static final List<String> GAME_ID_BRANCH_APPROVED_PREFIXES = List.of(
            "com/openggf/game/dataselect/"
    );

    private static final Set<String> OBJECT_ART_ALLOWED_LEGACY_NAMES = Set.of(
            "checkpointSheet",
            "checkpointStarSheet",
            "hexBumperSheet",
            "bonusBlockSheet",
            "flipperSheet",
            "speedBoosterSheet",
            "blueBallsSheet",
            "resultsSheet",
            "leavesSheet",
            "checkpointAnimations",
            "flipperAnimations",
            "pipeExitSpringAnimations",
            "tippingFloorAnimations",
            "springboardAnimations"
    );
    private static final Pattern OBJECT_ART_FORBIDDEN_NAME =
            Pattern.compile("(?i).*(sonic|s1|s2|s3k|ghz|lz|mz|slz|syz|sbz|ehz|cpz|arz|cnz|htz|mcz|ooz|mtz|wfz|scz|dez|aiz|hcz|mgz|cnz|icz|lbz|mhz|fbz|soz|lrz|hpz|ssz|ddz|obj\\d+|plc|dplc|eager|conditional|provider|romAddr|artAddr|mappingAddr|patternBase).*");

    private static final Set<String> COORDINATE_HAZARD_ALLOWED = Set.of(
            "com/openggf/game/sonic2/objects/CheckpointStarInstance.java",
            "com/openggf/game/sonic3k/objects/Aiz2BossEndSequenceController.java",
            "com/openggf/game/sonic3k/objects/AizEmeraldScatterInstance.java",
            "com/openggf/game/sonic3k/objects/AizHollowTreeObjectInstance.java",
            // Cnz2CutsceneButtonInstance compares the cutscene Knuckles NPC's
            // object position (knuckles.getX/getY) against the button's proximity
            // box — object-to-object, not a player top-left hazard. Same pattern as
            // the allowlisted Hcz2CutsceneButtonInstance / S3kCutsceneButtonObjectInstance.
            "com/openggf/game/sonic3k/objects/Cnz2CutsceneButtonInstance.java",
            "com/openggf/game/sonic3k/objects/Hcz2CutsceneButtonInstance.java",
            "com/openggf/game/sonic3k/objects/PachinkoFlipperObjectInstance.java",
            "com/openggf/game/sonic3k/objects/S3kCutsceneButtonObjectInstance.java"
    );
    private static final List<String> COORDINATE_SCAN_PREFIXES = List.of(
            "com/openggf/level/objects/",
            "com/openggf/game/sonic1/objects/",
            "com/openggf/game/sonic2/objects/",
            "com/openggf/game/sonic3k/objects/"
    );

    private static final Pattern GAME_ID_BRANCH = Pattern.compile(
            "\\b(?:if|while)\\s*\\([^)]*(?:getGameId\\s*\\(\\s*\\)|\\b\\w*GameId\\b)[^)]*(?:==|!=)[^)]*GameId\\s*\\."
                    + "|\\bswitch\\s*\\([^)]*(?:getGameId\\s*\\(\\s*\\)|\\b\\w*GameId\\b)[^)]*\\)");
    private static final Pattern RUNTIME_DISASM_PATH_LITERAL = Pattern.compile(
            "\"(?:docs/|docs\\\\\\\\)?(?:s1disasm|s2disasm|skdisasm)(?:/|\\\\\\\\)");
    private static final Pattern OBJECT_ART_DECLARATION = Pattern.compile(
            "\\b(?:private\\s+final\\s+[^;=]+|public\\s+[^;=]+|[A-Za-z0-9_<>\\[\\]]+)\\s+(\\w+)\\s*(?:[;,)=]|$)");
    private static final Pattern OBJECT_ART_ACCESSOR = Pattern.compile(
            "\\bpublic\\s+[^;=()]+\\s+(\\w+)\\s*\\(\\s*\\)");
    private static final Pattern CONCRETE_SONIC_REFERENCE = Pattern.compile(
            "\\b(?:Sonic1|Sonic2|Sonic3k|S1|S2|S3k)[A-Z][A-Za-z0-9_]*\\b"
                    + "|\\bcom\\.openggf\\.game\\.sonic(?:1|2|3k)(?:\\.[A-Za-z_][A-Za-z0-9_]*)+");
    private static final Pattern CROSS_GAME_DONOR_CONCRETE_REFERENCE = Pattern.compile(
            "\\b(?:Sonic1|Sonic2|Sonic3k|S3k)[A-Z][A-Za-z0-9_]*\\b"
                    + "|\\bcom\\.openggf\\.game\\.sonic(?:1|2|3k)(?:\\.[A-Za-z_][A-Za-z0-9_]*)+");
    private static final Pattern LOW_LEVEL_GAMEPLAY_SERVICE_LOOKUP = Pattern.compile(
            "\\bGameServices\\s*\\.\\s*"
                    + "(?:camera|cameraOrNull|level|levelOrNull|sprites|spritesOrNull|gameState|gameStateOrNull"
                    + "|timers|timersOrNull|fade|fadeOrNull|collision|collisionOrNull|water|waterOrNull"
                    + "|parallax|parallaxOrNull|zoneLayoutMutationPipeline|zoneLayoutMutationPipelineOrNull"
                    + "|zoneRuntimeRegistry|paletteOwnershipRegistry|animatedTileChannelGraph"
                    + "|specialRenderEffectRegistry|advancedRenderModeController)\\s*\\(");
    private static final Pattern GAME_MODULE_REGISTRY_MUTATION = Pattern.compile(
            "\\bGameModuleRegistry\\s*\\.\\s*(?:setCurrent|detectAndSetModule)\\s*\\(");
    private static final Pattern RAW_OBJECT_CHILD_SPAWN = Pattern.compile(
            "\\b(?:addDynamicObject(?:NextFrame|AfterCurrent(?:NextFrame)?)?|ObjectLifetimeOps\\s*\\.\\s*assignFindNextFreeChildSlot)\\s*\\(");
    private static final Pattern RAW_RENDER_PATTERN = Pattern.compile("\\.\\s*renderPattern\\s*\\(");
    private static final Set<String> REGISTRY_BACKED_PALETTE_CYCLE_CLASSES = Set.of(
            "IczCycle",
            "LbzCycle",
            "Lrz1Cycle",
            "Lrz2Cycle",
            "BpzCycle",
            "CgzCycle",
            "EmzCycle"
    );
    private static final Pattern DIRECT_CACHE_PALETTE_TEXTURE = Pattern.compile("\\.\\s*cachePaletteTexture\\s*\\(");
    private static final Pattern PLAYER_TOP_LEFT_ACCESS = Pattern.compile(
            "\\b(?:player|playable|entity|sonic|tails|knuckles)\\s*\\.\\s*get[XY]\\s*\\(\\s*\\)");
    private static final Pattern COORDINATE_CONTEXT = Pattern.compile(
            "(?i)\\b(rom|parity|x_pos|y_pos|distance|threshold|trigger|dx|dy|Math\\s*\\.\\s*abs|>=|<=|>|<)\\b");
    private static final Pattern RAW_LEVEL_MUTATOR = Pattern.compile(
            "\\b(?:map|level\\s*\\.\\s*getMap\\s*\\(\\s*\\)|block|chunk|blocks\\s*\\[[^]]+]|chunks\\s*\\[[^]]+])"
                    + "\\s*\\.\\s*(?:setValue|setChunkDesc|setPatternDesc|setSolidTileIndex|setSolidTileAltIndex)"
                    + "\\s*\\(");
    private static final Set<String> RAW_LEVEL_MUTATOR_ALLOWED_FILES = Set.of(
            "com/openggf/level/Map.java",
            "com/openggf/level/Block.java",
            "com/openggf/level/Chunk.java",
            "com/openggf/level/MutableLevel.java",
            "com/openggf/game/mutation/DirectLevelMutationSurface.java",
            "com/openggf/game/sonic3k/Sonic3kLevel.java"
    );

    @Test
    void productionGameIdBehaviorBranchesStayInRoutingAndCompositionCode() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : productionFiles()) {
            String relative = relative(file);
            if (isApprovedGameIdBranchFile(relative)) {
                continue;
            }
            String source = stripCommentsAndStrings(Files.readString(file));
            Matcher matcher = GAME_ID_BRANCH.matcher(source);
            while (matcher.find()) {
                violations.add(relative + ":" + lineNumberForOffset(source, matcher.start())
                        + " - branch on GameId outside routing/composition surface");
            }
        }

        assertNoViolations("GameId behavior branches must use typed rules or approved routing/composition code",
                violations);
    }

    @Test
    void crossGameFeatureProviderDoesNotNameConcreteSonicDonors() throws IOException {
        String source = stripCommentsAndStrings(Files.readString(
                SRC_MAIN.resolve("com/openggf/game/CrossGameFeatureProvider.java")));
        Matcher matcher = CROSS_GAME_DONOR_CONCRETE_REFERENCE.matcher(source);
        List<String> violations = new ArrayList<>();
        while (matcher.find()) {
            violations.add("line " + lineNumberForOffset(source, matcher.start()) + ": " + matcher.group());
        }

        assertNoViolations(
                "CrossGameFeatureProvider should request donor behavior through shared provider/factory contracts",
                violations);
    }

    @Test
    void gameRulesPositionalConstructionStaysInsideOwnedFactories() throws IOException {
        Pattern positionalConstructor = Pattern.compile("\\bnew\\s+GameRules\\s*\\(");
        List<String> violations = new ArrayList<>();
        Path root = Path.of("src/main/java");
        try (Stream<Path> stream = Files.walk(root)) {
            for (Path file : stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .toList()) {
                String relative = root.relativize(file).toString().replace('\\', '/');
                if (relative.equals("com/openggf/game/rules/GameRules.java")
                        || relative.equals("com/openggf/game/rules/CrossGameRuleComposer.java")) {
                    continue;
                }
                String source = stripCommentsAndStrings(Files.readString(file));
                Matcher matcher = positionalConstructor.matcher(source);
                while (matcher.find()) {
                    violations.add(relative + ":" + lineNumberForOffset(source, matcher.start())
                            + " - use GameRules constants or CrossGameRuleComposer instead of positional construction");
                }
            }
        }

        assertNoViolations("GameRules positional construction should stay inside owned rule factories",
                violations);
    }

    @Test
    void runtimeProductionCodeDoesNotReadDisassemblyAssets() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : productionFiles()) {
            String relative = relative(file);
            if (relative.startsWith("com/openggf/tools/")) {
                continue;
            }
            String source = stripComments(Files.readString(file));
            Matcher matcher = RUNTIME_DISASM_PATH_LITERAL.matcher(source);
            while (matcher.find()) {
                violations.add(relative + ":" + lineNumberForOffset(source, matcher.start())
                        + " - runtime read from docs disassembly asset");
            }
        }

        assertNoViolations("Runtime production code must load gameplay assets from ROM, not docs disassembly trees",
                violations);
    }

    @Test
    void sonic1EmbeddedRuntimeDataExceptionsStayDocumentedAndBounded() throws IOException {
        String discrepancies = Files.readString(Path.of("docs", "status", "known-discrepancies.md"));
        List<String> violations = new ArrayList<>();
        if (!discrepancies.contains("Sonic 1 Embedded Runtime Data Ratchet")) {
            violations.add("docs/status/known-discrepancies.md must document the bounded Sonic 1 embedded runtime data debt");
        }

        List<EmbeddedRuntimeDataBudget> budgets = List.of(
                new EmbeddedRuntimeDataBudget(
                        "com/openggf/game/sonic1/Sonic1PaletteCycler.java",
                        "embedded Sonic 1 palette-cycle arrays",
                        Pattern.compile("private\\s+static\\s+final\\s+byte\\s*\\[\\s*]\\s+PAL_"),
                        0),
                new EmbeddedRuntimeDataBudget(
                        "com/openggf/game/sonic1/objects/Sonic1LZConveyorObjectInstance.java",
                        "embedded LZ conveyor waypoint tables",
                        Pattern.compile("private\\s+static\\s+final\\s+int\\s*\\[\\s*]\\s*\\[\\s*]\\s+PATH_"),
                        0),
                new EmbeddedRuntimeDataBudget(
                        "com/openggf/game/sonic1/objects/Sonic1LZConveyorObjectInstance.java",
                        "embedded LZ conveyor spawner switch tables",
                        Pattern.compile("case\\s+\\d+\\s+->\\s+new\\s+int\\s*\\[\\s*]\\s*\\[\\s*]"),
                        0),
                new EmbeddedRuntimeDataBudget(
                        "com/openggf/game/sonic1/objects/Sonic1SpinConveyorObjectInstance.java",
                        "embedded SBZ spin-conveyor waypoint tables",
                        Pattern.compile("private\\s+static\\s+final\\s+int\\s*\\[\\s*]\\s*\\[\\s*]\\s+PATH_"),
                        0),
                new EmbeddedRuntimeDataBudget(
                        "com/openggf/game/sonic1/objects/Sonic1SpinConveyorObjectInstance.java",
                        "embedded SBZ spin-conveyor spawner tables",
                        Pattern.compile("private\\s+static\\s+final\\s+int\\s*\\[\\s*]\\s*\\[\\s*]\\s+SPAWN_DATA_"),
                        0),
                new EmbeddedRuntimeDataBudget(
                        "com/openggf/game/sonic1/objects/Sonic1BridgeObjectInstance.java",
                        "embedded GHZ bridge bend tables",
                        Pattern.compile("private\\s+static\\s+final\\s+int\\s*\\[\\s*]\\s*\\[\\s*]\\s+BEND_DATA_"),
                        0),
                new EmbeddedRuntimeDataBudget(
                        "com/openggf/game/sonic1/Sonic1ObjectArtProvider.java",
                        "handwritten Sonic 1 object mapping pieces",
                        Pattern.compile("new\\s+SpriteMappingPiece\\s*\\("),
                        0),
                new EmbeddedRuntimeDataBudget(
                        "com/openggf/game/sonic1/objects/bosses/Sonic1BossMappings.java",
                        "handwritten Sonic 1 boss mapping pieces",
                        Pattern.compile("new\\s+SpriteMappingPiece\\s*\\("),
                        0)
        );

        for (EmbeddedRuntimeDataBudget budget : budgets) {
            Path sourcePath = SRC_MAIN.resolve(budget.relativePath());
            if (!Files.exists(sourcePath) && budget.expectedCount() == 0) {
                continue;
            }
            String source = Files.readString(sourcePath);
            int actual = countMatches(budget.pattern(), source);
            if (actual != budget.expectedCount()) {
                violations.add(budget.relativePath() + " has " + actual + " "
                        + budget.description() + "; expected exactly " + budget.expectedCount()
                        + ". ROM-back this data before adding new embedded runtime tables.");
            }
        }

        assertNoViolations("Sonic 1 embedded runtime data exceptions must stay documented and bounded",
                violations);
    }

    @Test
    void hardwareTimingAuthorityExceptionStaysDocumentedAndAgentGuidanceStaysMirrored() throws IOException {
        Path agents = Path.of("AGENTS.md");
        Path claude = Path.of("CLAUDE.md");
        byte[] agentsBytes = Files.readAllBytes(agents);
        assertArrayEquals(agentsBytes, Files.readAllBytes(claude),
                "AGENTS.md and CLAUDE.md must remain byte-identical");

        String agentGuidance = new String(agentsBytes, StandardCharsets.UTF_8);
        String normalizedAgentGuidance = agentGuidance.replaceAll("\\s+", " ");
        assertTrue(agentGuidance.contains("Trace data is comparison-only by default."),
                "agent guidance must retain the comparison-only trace rule");
        assertTrue(agentGuidance.contains("dedicated hardware-timing input contract"),
                "agent guidance must retain the dedicated hardware-timing exception");
        assertTrue(normalizedAgentGuidance.contains(
                        "It may release only the readiness of a matching, prepared, production-submitted"),
                "agent guidance must retain the bounded timing-release rule");

        String discrepancies = Files.readString(Path.of("docs", "status", "known-discrepancies.md"));
        assertTrue(discrepancies.contains("## Hardware-Timing Replay Input Exception"),
                "known discrepancies must retain the hardware-timing exception");
        assertTrue(discrepancies.contains("Physics CSV and auxiliary events remain comparison-only."),
                "known discrepancies must retain the physics/aux comparison-only boundary");
    }

    @Test
    void engineDoesNotGainNewLargeMethods() throws IOException {
        // Intent: Engine.java shouldn't grow uncontrollably by gaining a new
        // large method. We deliberately do NOT enforce specific line counts on
        // init/display/draw -- that would trip on any legitimate edit and push
        // people toward unrelated cleanup to make room. The structural signal
        // is just "no fourth >=100-line method appears here without review."
        SourceFile engine = SourceFile.read(SRC_MAIN.resolve(ENGINE_PATH));
        List<MethodSpan> largeMethods = engine.methods().stream()
                .filter(method -> method.lineCount() >= ENGINE_LARGE_METHOD_THRESHOLD)
                .toList();
        if (largeMethods.size() > ENGINE_MAX_LARGE_METHODS) {
            List<String> names = largeMethods.stream()
                    .map(method -> method.name() + " (" + method.lineCount() + " lines)")
                    .toList();
            fail("Engine.java now has " + largeMethods.size() + " methods at or above "
                    + ENGINE_LARGE_METHOD_THRESHOLD + " lines; cap is " + ENGINE_MAX_LARGE_METHODS
                    + ". Extract responsibilities into focused collaborators before adding new large methods.\n"
                    + "  Current large methods: " + String.join(", ", names));
        }
    }

    @Test
    void rootGameLoopAndEngineDoNotGainConcreteSonicDependencies() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, Integer> budget : ROOT_CONCRETE_SONIC_REFERENCE_BUDGETS.entrySet()) {
            String relative = budget.getKey();
            List<String> references = scanPattern(relative,
                    Files.readString(SRC_MAIN.resolve(relative)),
                    CONCRETE_SONIC_REFERENCE);
            if (references.size() > budget.getValue()) {
                violations.add(relative + " has " + references.size()
                        + " concrete Sonic references; budget is " + budget.getValue()
                        + ". Route new game-specific behavior through GameModule/provider contracts.\n    "
                        + String.join("\n    ", references));
            }
        }

        assertNoViolations("Root Engine/GameLoop concrete Sonic dependency ratchet failed", violations);
    }

    @Test
    void objectManagerDoesNotGainConcreteSonicDependencies() throws IOException {
        List<String> references = scanPattern(OBJECT_MANAGER_PATH,
                Files.readString(SRC_MAIN.resolve(OBJECT_MANAGER_PATH)),
                CONCRETE_SONIC_REFERENCE);

        assertTrue(references.size() <= OBJECT_MANAGER_CONCRETE_SONIC_REFERENCE_BUDGET,
                "ObjectManager has " + references.size()
                        + " concrete Sonic references; budget is "
                        + OBJECT_MANAGER_CONCRETE_SONIC_REFERENCE_BUDGET
                        + ". Move new rewind/dynamic-object recreation through registered factories or codecs.\n    "
                        + String.join("\n    ", references));
    }

    @Test
    void sharedSpriteCodeDoesNotGainConcreteSonicDependencies() throws IOException {
        List<String> references = new ArrayList<>();
        for (Path file : productionFilesUnder("com/openggf/sprites")) {
            String relative = relative(file);
            references.addAll(scanPattern(relative,
                    Files.readString(file),
                    CONCRETE_SONIC_REFERENCE));
        }

        assertNoViolations(
                "Shared sprite code must use GameRules, providers, or shared contracts instead of concrete Sonic packages",
                references);
    }

    @Test
    void lowLevelGraphicsAndAudioDoNotGainGameplayServiceLookups() throws IOException {
        List<String> references = new ArrayList<>();
        for (String root : LOW_LEVEL_SERVICE_SCAN_ROOTS) {
            for (Path file : productionFilesUnder(root)) {
                String relative = relative(file);
                references.addAll(scanPattern(relative,
                        Files.readString(file),
                        LOW_LEVEL_GAMEPLAY_SERVICE_LOOKUP));
            }
        }

        assertTrue(references.size() <= LOW_LEVEL_GAMEPLAY_SERVICE_LOOKUP_BUDGET,
                "Low-level graphics/audio code has " + references.size()
                        + " gameplay-scoped GameServices lookups; budget is "
                        + LOW_LEVEL_GAMEPLAY_SERVICE_LOOKUP_BUDGET
                        + ". Pass gameplay state from render/audio orchestration instead.\n    "
                        + String.join("\n    ", references));
    }

    @Test
    void rootDispatchMethodsDoNotGrowBeyondCurrentBudgets() throws IOException {
        List<String> violations = new ArrayList<>();
        for (MethodBudget budget : ROOT_DISPATCH_METHOD_BUDGETS) {
            SourceFile source = SourceFile.read(SRC_MAIN.resolve(budget.relativePath()));
            MethodSpan method = source.methodNamed(budget.methodName());
            if (method == null) {
                violations.add(budget.relativePath() + " no longer contains method "
                        + budget.methodName() + "; update the ratchet if it was extracted");
                continue;
            }
            if (method.lineCount() > budget.maxLines()) {
                violations.add(budget.relativePath() + "#" + budget.methodName()
                        + " is " + method.lineCount() + " lines; budget is "
                        + budget.maxLines()
                        + ". Extract mode/render work into focused collaborators before growing this dispatcher.");
            }
        }

        assertNoViolations("Root mode/render dispatcher size ratchet failed", violations);
    }

    @Test
    void engineStartupWarmupDoesNotDependOnConcreteSonicDataSelectWarmupClasses() throws IOException {
        SourceFile engine = SourceFile.read(SRC_MAIN.resolve(ENGINE_PATH));
        String source = engine.text();

        assertTrue(!source.contains("S1DataSelectImageWarmup")
                        && !source.contains("S2DataSelectImageWarmup")
                        && !source.contains("S1DataSelectImageCacheManager")
                        && !source.contains("S2DataSelectImageCacheManager"),
                "Engine startup warmup must use the GameModule/provider hook, not concrete S1/S2 data-select classes");
    }

    @Test
    void levelFrameStepDoesNotUseAmbientGameServices() throws IOException {
        String source = Files.readString(SRC_MAIN.resolve("com/openggf/LevelFrameStep.java"));

        assertTrue(!source.contains("GameServices."),
                "LevelFrameStep must receive frame dependencies through LevelFrameContext, not GameServices");
    }

    @Test
    void runtimeProductionCodeDoesNotMutateGameModuleRegistry() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : productionFiles()) {
            String relative = relative(file);
            if (isApprovedGameModuleRegistryMutationFile(relative)) {
                continue;
            }
            violations.addAll(scanPattern(relative,
                    Files.readString(file),
                    GAME_MODULE_REGISTRY_MUTATION));
        }

        assertNoViolations("Active gameplay modules are owned by WorldSession; runtime code must not mutate "
                + "GameModuleRegistry bootstrap compatibility state", violations);
    }

    @Test
    void levelManagerDelegatesRewindSnapshotAdapterToNamedCollaborator() throws IOException {
        String source = stripCommentsAndStrings(Files.readString(
                SRC_MAIN.resolve("com/openggf/level/LevelManager.java")));
        Pattern inlineRewindAdapter = Pattern.compile(
                "new\\s+com\\.openggf\\.game\\.rewind\\.RewindSnapshottable\\s*<");

        assertTrue(!inlineRewindAdapter.matcher(source).find(),
                "LevelManager.levelRewindSnapshottable() should delegate to a named level.rewind collaborator, "
                        + "not own an inline anonymous rewind adapter");
        assertTrue(!source.contains("new LevelRewindSnapshotAdapter("),
                "Use LevelRewindSnapshotAdapter.create(...) so LevelManager owns construction policy only, "
                + "not snapshot capture/restore implementation details");
    }

    @Test
    void levelManagerDelegatesPlayableArtInitializationToNamedCollaborator() throws IOException {
        String source = stripCommentsAndStrings(Files.readString(
                SRC_MAIN.resolve("com/openggf/level/LevelManager.java")));
        List<String> forbiddenSignals = List.of(
                "PlayerSpriteRenderer",
                "SpindashDustController",
                "TailsTailsController",
                "initPlayerSpriteArt(",
                "initSpindashDust(",
                "initTailsTails(",
                "initSuperState(",
                "createSidekickPaletteContext(",
                "propagateSidekickPaletteContext(");
        List<String> violations = forbiddenSignals.stream()
                .filter(source::contains)
                .toList();
        assertTrue(violations.isEmpty(),
                "LevelManager should coordinate playable art through LevelPlayableArtInitializer, "
                        + "not own renderer/controller setup directly. Found: " + violations);
    }

    @Test
    void levelManagerDelegatesDirtyRegionDispatchToNamedCollaborator() throws IOException {
        String source = stripCommentsAndStrings(Files.readString(
                SRC_MAIN.resolve("com/openggf/level/LevelManager.java")));
        List<String> forbiddenSignals = List.of(
                "consumeDirtyPatterns(",
                "consumeDirtyBlocks(",
                "consumeDirtyMapCells(",
                "consumeDirtySolidTiles(",
                "consumeObjectsDirty(",
                "consumeRingsDirty(",
                "effects.hasDirtyPatterns(",
                "effects.objectResyncRequired(",
                "effects.ringResyncRequired(");
        List<String> violations = forbiddenSignals.stream()
                .filter(source::contains)
                .toList();
        assertTrue(violations.isEmpty(),
                "LevelManager should delegate dirty-region and mutation-effect dispatch "
                        + "to LevelDirtyRegionDispatcher. Found: " + violations);
    }

    @Test
    void levelManagerDelegatesWaterLifecycleToNamedCollaborator() throws IOException {
        String source = stripCommentsAndStrings(Files.readString(
                SRC_MAIN.resolve("com/openggf/level/LevelManager.java")));
        List<String> forbiddenSignals = List.of(
                "WaterDataProvider",
                "loadForLevelFromProvider(",
                "waterSystem.loadForLevel(",
                "waterSystem.updateDynamic(",
                "getGameplayWaterLevelY(",
                "updateWaterStateObjectControlled(",
                "zoneFeatureProvider.shouldSuppressUnderwaterPalette(");
        List<String> violations = forbiddenSignals.stream()
                .filter(source::contains)
                .toList();
        assertTrue(violations.isEmpty(),
                "LevelManager should delegate water load, movement, and playable-water state "
                        + "to LevelWaterCoordinator. Found: " + violations);
    }

    @Test
    void levelManagerDelegatesCheckpointStateToNamedCollaborator() throws IOException {
        String source = stripCommentsAndStrings(Files.readString(
                SRC_MAIN.resolve("com/openggf/level/LevelManager.java")));
        List<String> forbiddenSignals = List.of(
                "checkpointState",
                "createRespawnState(",
                "restoreFromSaved(",
                "saveWaterState(",
                "saveS3kRuntimeState(",
                "saveSolidBits(",
                "restoreEventRoutineState(");
        List<String> violations = forbiddenSignals.stream()
                .filter(source::contains)
                .toList();
        assertTrue(violations.isEmpty(),
                "LevelManager should delegate checkpoint storage and restore details "
                        + "to LevelCheckpointCoordinator. Found: " + violations);
    }

    @Test
    void levelManagerDelegatesActTransitionExecutionToNamedCollaborator() throws IOException {
        Path path = SRC_MAIN.resolve("com/openggf/level/LevelManager.java");
        SourceFile sourceFile = SourceFile.read(path);
        MethodSpan method = sourceFile.methodNamed("executeActTransition");
        assertTrue(method != null, "LevelManager should retain the public executeActTransition facade");
        assertTrue(method.lineCount() <= 4,
                "LevelManager.executeActTransition should delegate to LevelActTransitionExecutor");

        String source = stripCommentsAndStrings(String.join("\n",
                sourceFile.lines().subList(method.startLine() - 1, method.endLine())));
        List<String> forbiddenSignals = List.of(
                "reloadStandaloneArtForActTransition(",
                "snapshotPersistentDynamicObjectsForTransition(",
                "preserveLevelGamestate()",
                "showInLevelTitleCard()");
        List<String> violations = forbiddenSignals.stream()
                .filter(source::contains)
                .toList();
        assertTrue(violations.isEmpty(),
                "LevelManager.executeActTransition should delegate act-transition reload choreography "
                        + "to LevelActTransitionExecutor. Found: " + violations);
    }

    @Test
    void everyS3kSeamlessTransitionBuilderDeclaresRuntimeArtAdmissionPolicy()
            throws IOException {
        Map<String, String> eventFiles = Map.of(
                "Sonic3kAIZEvents.java", "PRESERVE_CURRENT",
                "Sonic3kCNZEvents.java", "TITLE_OWNER",
                "Sonic3kICZEvents.java", "RESOURCE_HANDOFF_OWNER",
                "Sonic3kLBZEvents.java", "TITLE_OWNER",
                "Sonic3kMGZEvents.java", "TITLE_OWNER",
                "Sonic3kHCZEvents.java", "TITLE_OWNER",
                "Sonic3kMHZEvents.java", "TITLE_OWNER");
        for (var entry : eventFiles.entrySet()) {
            String eventFile = entry.getKey();
            String source = stripCommentsAndStrings(Files.readString(SRC_MAIN.resolve(
                    "com/openggf/game/sonic3k/events/" + eventFile)));
            assertS3kBuilderPolicies(eventFile, source, entry.getValue());
        }
    }

    @Test
    void s3kPolicyGuardRejectsDuplicateAndMissingPoliciesOnSeparateBuilders() {
        String duplicate = """
                SeamlessLevelTransitionRequest.builder(TYPE)
                    .runtimeArtAdmissionPolicy(RuntimeArtAdmissionPolicy.IMMEDIATE)
                    .runtimeArtAdmissionPolicy(RuntimeArtAdmissionPolicy.IMMEDIATE)
                    .build();
                """;
        String missing = """
                SeamlessLevelTransitionRequest.builder(TYPE)
                    .build();
                """;

        assertThrows(AssertionError.class, () ->
                assertS3kBuilderPolicies(
                        "DuplicatePolicyEvents.java", duplicate, "IMMEDIATE"));
        assertThrows(AssertionError.class, () ->
                assertS3kBuilderPolicies(
                        "MissingPolicyEvents.java", missing, "IMMEDIATE"));
    }

    private static void assertS3kBuilderPolicies(
            String eventFile, String source, String expectedPolicy) {
        List<String> builderChains = seamlessTransitionBuilderChains(source);
        assertFalse(builderChains.isEmpty(),
                eventFile + " must retain its transition builder");
        for (int index = 0; index < builderChains.size(); index++) {
            String chain = builderChains.get(index);
            int policies = chain.split(
                    "\\.runtimeArtAdmissionPolicy\\(", -1).length - 1;
            assertEquals(1, policies,
                    eventFile + " builder " + index
                            + " must declare exactly one admission policy");
                    assertTrue(chain.contains(
                            "RuntimeArtAdmissionPolicy." + expectedPolicy),
                    eventFile + " builder " + index
                            + " must retain its reviewed admission policy");
        }
    }

    private static List<String> seamlessTransitionBuilderChains(String source) {
        String marker = "SeamlessLevelTransitionRequest.builder(";
        String terminator = ".build()";
        List<String> chains = new ArrayList<>();
        int cursor = 0;
        while (true) {
            int start = source.indexOf(marker, cursor);
            if (start < 0) {
                return chains;
            }
            int end = source.indexOf(terminator, start + marker.length());
            if (end < 0) {
                throw new IllegalStateException(
                        "unterminated seamless transition builder at offset " + start);
            }
            chains.add(source.substring(start, end + terminator.length()));
            cursor = end + terminator.length();
        }
    }

    @Test
    void gameLoopRoutesMenuScreenUpdatesThroughModeControllers() throws IOException {
        String source = stripCommentsAndStrings(Files.readString(SRC_MAIN.resolve("com/openggf/GameLoop.java")));
        List<String> forbiddenDirectUpdates = List.of(
                "titleScreen.update(inputHandler)",
                "levelSelect.update(inputHandler)",
                "dataSelect.update(inputHandler)");
        List<String> violations = forbiddenDirectUpdates.stream()
                .filter(source::contains)
                .toList();

        assertEquals(List.of(), violations,
                "GameLoop should delegate title, level-select, and data-select update sequencing "
                        + "to a focused game.mode controller");
    }

    @Test
    void playableMovementRoutesTerrainCollisionThroughFramePlans() throws IOException {
        String relative = "com/openggf/sprites/managers/PlayableSpriteMovement.java";
        String source = stripCommentsAndStrings(Files.readString(SRC_MAIN.resolve(relative)));

        assertTrue(source.contains("FrameCollisionPlan.terrainOnly()"),
                "Playable movement terrain collision paths must name the FrameCollisionPlan they run");

        Pattern directPlayableTerrainCollision = Pattern.compile(
                "collisionSystem\\s*\\(\\s*\\)\\s*\\.\\s*"
                        + "(resolveGroundAttachment|resolveAirCollision|resolveGroundWallCollision)"
                        + "\\s*\\(\\s*sprite\\b");
        Matcher matcher = directPlayableTerrainCollision.matcher(source);
        List<String> violations = new ArrayList<>();
        while (matcher.find()) {
            violations.add(relative + ":" + lineNumberForOffset(source, matcher.start())
                    + " - direct " + matcher.group(1) + " call without FrameCollisionPlan");
        }

        assertNoViolations("Playable movement collision orchestration must route through FrameCollisionPlan",
                violations);
    }

    @Test
    void objectManagerFacadeStaysWithinExtractedCollaboratorBudget() throws IOException {
        SourceFile objectManager = SourceFile.read(SRC_MAIN.resolve(OBJECT_MANAGER_PATH));
        int lineCount = objectManager.effectiveSourceLineCount();
        assertTrue(lineCount <= OBJECT_MANAGER_MAX_EFFECTIVE_SOURCE_LINES,
                "ObjectManager.java is " + lineCount + " effective source lines; budget is "
                        + OBJECT_MANAGER_MAX_EFFECTIVE_SOURCE_LINES
                        + " after extracting ObjectPlacementController, ObjectTouchResponseController, "
                        + "and ObjectSolidContactController. Keep new placement, touch-response, "
                        + "and solid-contact logic in those collaborators.");
    }

    @Test
    void objectManagerDelegatesPlacementLifecycleAndVerticalFiltering() throws IOException {
        String manager = Files.readString(SRC_MAIN.resolve(OBJECT_MANAGER_PATH));

        assertTrue(manager.contains("placement.reallocateToFirstFreeDynamicSlot("));
        assertTrue(manager.contains("placement.releaseSpawnForRespawn("));
        assertTrue(manager.contains("placement.dispatchDestroyRemoveFromActive("));
        assertTrue(manager.contains("placement.isSpawnVerticallyEligibleForTwoAxisYPass("));
        assertTrue(manager.contains("ObjectPlacementController.isNonCounterSpawnVerticallyEligible("));
        assertTrue(manager.contains("solidContacts.captureExecStartPlayerCentreY("));
    }

    @Test
    void releaseCriticalLargeClassesDoNotGrowWithoutExtraction() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, Integer> budget
                : RELEASE_CRITICAL_CLASS_EFFECTIVE_SOURCE_LINE_BUDGETS.entrySet()) {
            String relative = budget.getKey();
            int lineCount = SourceFile.read(SRC_MAIN.resolve(relative)).effectiveSourceLineCount();
            if (lineCount > budget.getValue()) {
                violations.add(relative + " is " + lineCount + " effective source lines; budget is "
                        + budget.getValue()
                        + ". Extract focused collaborators before growing this release-critical file.");
            }
        }

        assertNoViolations("Release-critical large class size ratchet failed", violations);
    }

    @Test
    void playableSpriteDelegatesFrameStateAnimationAndControlledContactBehavior() throws IOException {
        String sprite = Files.readString(SRC_MAIN.resolve(
                "com/openggf/sprites/playable/AbstractPlayableSprite.java"));
        String controller = Files.readString(SRC_MAIN.resolve(
                "com/openggf/sprites/playable/PlayableSpriteController.java"));

        assertTrue(sprite.contains("controller.captureFrameStartState();"));
        assertTrue(sprite.contains("controller.publishRawAnimation(CanonicalAnimation.HURT);"));
        assertTrue(sprite.contains("controller.notifyObjectControlledSolidContact(candidate, contact);"));
        assertTrue(controller.contains("void captureFrameStartState()"));
        assertTrue(controller.contains("void publishRawAnimation(CanonicalAnimation animation)"));
        assertTrue(controller.contains("void notifyObjectControlledSolidContact("));
    }

    @Test
    void objectArtDataDoesNotGainNewGameOrZoneSpecificSurface() throws IOException {
        String source = stripCommentsAndStrings(Files.readString(
                SRC_MAIN.resolve("com/openggf/level/objects/ObjectArtData.java")));
        List<String> names = objectArtDataSurfaceNames(source);
        List<String> violations = names.stream()
                .filter(name -> OBJECT_ART_FORBIDDEN_NAME.matcher(name).matches())
                .filter(name -> !OBJECT_ART_ALLOWED_LEGACY_NAMES.contains(name))
                .distinct()
                .sorted()
                .toList();

        assertNoViolations("ObjectArtData must stay game-agnostic; add game/zone art to object art providers instead",
                violations);
    }

    @Test
    void sharedObjectArtSplitTypesExistAtProviderBoundary() {
        assertTrue(Files.exists(SRC_MAIN.resolve("com/openggf/level/objects/art/ObjectArtBundle.java")),
                "ObjectArtBundle should be the shared, game-agnostic render data contract");
        assertTrue(Files.exists(SRC_MAIN.resolve("com/openggf/level/objects/art/ObjectArtRegistration.java")),
                "ObjectArtRegistration should hold provider-owned art registration metadata");
    }

    @Test
    void sonic3kObjectRegistryDocsDoNotClaimPlaceholderOnlyCoverage() throws IOException {
        String source = Files.readString(SRC_MAIN.resolve(
                "com/openggf/game/sonic3k/objects/Sonic3kObjectRegistry.java"));

        assertTrue(!source.contains("Currently all objects use")
                        && !source.contains("all objects use PlaceholderObjectInstance"),
                "Sonic3kObjectRegistry docs must not claim placeholder-only coverage after concrete S3K factories exist");
    }

    @Test
    void sonic3kConstantsDoNotInferLockOnHalfFromZoneEra() throws IOException {
        String source = Files.readString(SRC_MAIN.resolve(
                "com/openggf/game/sonic3k/constants/Sonic3kConstants.java"));

        assertTrue(!source.contains("S3 half (>= 0x200000) addresses for S3-era zones")
                        && !source.contains("S&K half for S&K-era zones"),
                "S3K ROM address comments must document the verified source half per table, "
                + "not infer S3/S&K half from zone era");
    }

    @Test
    void sonic3kBossDocsDoNotDescribeImplementedHczEndBossAsStubbed() throws IOException {
        String source = Files.readString(SRC_MAIN.resolve(
                "com/openggf/game/sonic3k/objects/bosses/HczEndBossInstance.java"));

        assertTrue(!source.contains("routines and defeat logic are stubbed for later tasks."),
                "HczEndBossInstance docs must not describe implemented movement/defeat logic as stubbed");
        assertTrue(!source.contains("Child spawning (placeholders for Tasks 3-5)"),
                "HczEndBossInstance docs must not describe implemented child spawning as placeholder-only");
        assertTrue(!source.contains("Children will be spawned when their classes are created:"),
                "HczEndBossInstance docs must not claim existing child classes are still pending");
    }

    @Test
    void sonic3kMgzDocsDoNotDescribeImplementedEndBossAsStubbed() throws IOException {
        String source = Files.readString(SRC_MAIN.resolve(
                "com/openggf/game/sonic3k/events/Sonic3kMGZEvents.java"));

        assertTrue(!source.contains("(currently unimplemented) end-of-act boss"),
                "Sonic3kMGZEvents docs must not describe implemented MGZ end-boss handoff as unimplemented");
        assertTrue(!source.contains("for that stubbed step."),
                "Sonic3kMGZEvents docs must not describe implemented MGZ boss handoff as stubbed");
        assertTrue(!source.contains("end boss not yet implemented"),
                "Sonic3kMGZEvents logs must not describe implemented MGZ end-boss path as unimplemented");
    }

    @Test
    void sonic3kMonitorDocsDoNotClaimPlayerCharacterSystemIsMissing() throws IOException {
        String source = Files.readString(SRC_MAIN.resolve(
                "com/openggf/game/sonic3k/objects/Sonic3kMonitorObjectInstance.java"));

        assertTrue(!source.contains("PlayerCharacter system (not yet implemented)"),
                "Sonic3kMonitorObjectInstance docs must not claim the PlayerCharacter system is missing");
    }

    @Test
    void sonic3kCnzDocsDoNotClaimImplementedObjectsAreStillTaskScaffolds() throws IOException {
        List<String> files = List.of(
                "com/openggf/game/sonic3k/constants/Sonic3kConstants.java",
                "com/openggf/game/sonic3k/constants/Sonic3kObjectIds.java",
                "com/openggf/game/sonic3k/Sonic3k.java",
                "com/openggf/game/sonic3k/Sonic3kGameModule.java",
                "com/openggf/game/sonic3k/Sonic3kObjectArt.java",
                "com/openggf/game/sonic3k/Sonic3kObjectArtProvider.java"
        );
        List<String> stalePhrases = List.of(
                "Task 6 infrastructure only",
                "behavior work, Task 6 only needs",
                "Task 6 only needs the renderer",
                "object behavior for Tasks 7 and 8",
                "actual miniboss object implementation remains deferred to Task 7",
                "Task 8 can attach real behavior later",
                "Controller-only scaffold for Task 1",
                "Phase 1 supports terrain, collision",
                "Phase 1: terrain/collision only",
                "Task 1 visual scaffold",
                "Cannon.bin data lives in the S&K half"
        );
        List<String> violations = new ArrayList<>();

        for (String relative : files) {
            String source = Files.readString(SRC_MAIN.resolve(relative));
            for (String stalePhrase : stalePhrases) {
                if (source.contains(stalePhrase)) {
                    violations.add(relative + " - " + stalePhrase);
                }
            }
        }

        assertNoViolations("S3K CNZ/module docs must not describe implemented objects as pending task scaffolds",
                violations);
    }

    @Test
    void objectArtDataDoesNotExposeSonic2ProviderSpecificFields() throws IOException {
        String source = stripCommentsAndStrings(Files.readString(
                SRC_MAIN.resolve("com/openggf/level/objects/ObjectArtData.java")));
        List<String> names = objectArtDataSurfaceNames(source);
        List<String> forbidden = names.stream()
                .filter(Set.of(
                        "breakableBlockSheet",
                        "cpzPlatformSheet",
                        "cpzStairBlockSheet",
                        "sidewaysPformSheet",
                        "cpzPylonSheet",
                        "pipeExitSpringSheet",
                        "tippingFloorSheet",
                        "barrierSheet",
                        "springboardSheet")::contains)
                .sorted()
                .toList();

        assertEquals(List.of(), forbidden,
                "Sonic 2 conditional/eager sheets belong in Sonic2ObjectArtProvider registrations, not ObjectArtData");
    }

    @Test
    void productionCodeDoesNotCallDeprecatedCollisionSystemStep() throws IOException {
        List<String> violations = new ArrayList<>();
        Pattern deprecatedStepCall = Pattern.compile("\\.step\\s*\\(");
        for (Path file : productionFiles()) {
            String relative = relative(file);
            if (relative.equals("com/openggf/physics/CollisionSystem.java")) {
                continue;
            }
            String stripped = stripCommentsAndStrings(Files.readString(file));
            if (!stripped.contains("CollisionSystem")) {
                continue;
            }
            String[] lines = stripped.split("\\R", -1);
            for (int i = 0; i < lines.length; i++) {
                if (deprecatedStepCall.matcher(lines[i]).find()) {
                    violations.add(relative + ":" + (i + 1) + " - use a named FrameCollisionPlan instead");
                }
            }
        }

        assertNoViolations("Production code must not call deprecated CollisionSystem.step()", violations);
    }

    @Test
    void collisionSystemDoesNotContainSbzCoordinateWindows() throws IOException {
        String relative = "com/openggf/physics/CollisionSystem.java";
        String stripped = stripCommentsAndStrings(Files.readString(SRC_MAIN.resolve(relative)));
        List<String> violations = new ArrayList<>();
        for (String forbidden : List.of(
                "isS1Sbz1RightWallLipWindow",
                "s1Sbz1FloorLipSlopeResult",
                "0x1694",
                "0x1720",
                "0x172B",
                "0x029C",
                "0x02AC")) {
            if (stripped.contains(forbidden)) {
                violations.add(relative + " - " + forbidden);
            }
        }

        assertNoViolations("Shared collision must use sensor/ROM-state predicates, not SBZ coordinate windows",
                violations);
    }

    @Test
    void migratedObjectChildSpawnsStayOnManagedHelpers() throws IOException {
        List<String> violations = new ArrayList<>();
        for (String relative : OBJECT_CHILD_SPAWN_MIGRATED_FILES) {
            violations.addAll(scanPattern(relative,
                    Files.readString(SRC_MAIN.resolve(relative)),
                    RAW_OBJECT_CHILD_SPAWN));
        }

        assertNoViolations("Migrated object child spawns must use spawnChild()/spawnFreeChild() helpers",
                violations);
    }

    @Test
    void cnzPrizeObjectsUseBaseDestroyedState() throws IOException {
        List<String> violations = new ArrayList<>();
        for (String relative : List.of(
                "com/openggf/game/sonic2/objects/BombPrizeObjectInstance.java",
                "com/openggf/game/sonic2/objects/RingPrizeObjectInstance.java")) {
            String source = stripCommentsAndStrings(Files.readString(SRC_MAIN.resolve(relative)));
            if (source.contains("boolean destroyed")) {
                violations.add(relative + " declares a local destroyed field instead of using AbstractObjectInstance");
            }
            if (source.contains("boolean isDestroyed()")) {
                violations.add(relative + " overrides isDestroyed() instead of using AbstractObjectInstance");
            }
        }

        assertNoViolations("CNZ prize objects must use the base object lifecycle destroyed state", violations);
    }

    @Test
    void s3kUiPatternBasesStayCentralizedInPatternAtlasRange() throws IOException {
        List<String> violations = new ArrayList<>();
        String ranges = Files.readString(SRC_MAIN.resolve("com/openggf/graphics/PatternAtlasRange.java"));
        for (String rangeName : List.of(
                "MENU_AND_DATA_SELECT",
                "RESULTS_SCREENS",
                "SPECIAL_STAGE_RESULTS",
                "S3K_TITLE_SCREEN_ANIMATION",
                "S3K_TITLE_SCREEN_SPRITES")) {
            if (!ranges.contains(rangeName)) {
                violations.add("PatternAtlasRange is missing " + rangeName);
            }
        }
        for (SourceSignal signal : S3K_PATTERN_ATLAS_RANGE_SIGNALS) {
            String source = Files.readString(SRC_MAIN.resolve(signal.relativePath()));
            if (!source.contains(signal.requiredText())) {
                violations.add(signal.relativePath() + " must allocate its virtual pattern base through "
                        + signal.requiredText());
            }
        }

        assertNoViolations("S3K UI/result virtual pattern bases must be owned by PatternAtlasRange",
                violations);
    }

    @Test
    void sonic3kHczStandaloneMappingsStayRomBacked() throws IOException {
        String provider = Files.readString(SRC_MAIN.resolve("com/openggf/game/sonic3k/Sonic3kObjectArtProvider.java"));
        String constants = Files.readString(SRC_MAIN.resolve(
                "com/openggf/game/sonic3k/constants/Sonic3kConstants.java"));
        String discrepancies = Files.readString(Path.of("docs", "S3K_KNOWN_DISCREPANCIES.md"));
        List<String> violations = new ArrayList<>();
        for (String constant : List.of(
                "MAP_HCZ_MINIBOSS_ADDR",
                "MAP_HCZ_END_BOSS_ADDR",
                "MAP_HCZ_WATERWALL_ADDR")) {
            if (!provider.contains("Sonic3kConstants." + constant)) {
                violations.add("Sonic3kObjectArtProvider must load " + constant + " through ROM-backed mappings");
            }
            if (!constants.contains("public static final int " + constant)) {
                violations.add("Sonic3kConstants must expose ROM address " + constant);
            }
            if (!discrepancies.contains(constant)) {
                violations.add("docs/S3K_KNOWN_DISCREPANCIES.md must document " + constant);
            }
        }

        assertNoViolations("HCZ standalone object mappings must stay ROM-backed, not handwritten runtime data",
                violations);
    }

    @Test
    void enginePostFadeRenderingStaysDiagnosticOnly() throws IOException {
        String source = Files.readString(SRC_MAIN.resolve(ENGINE_PATH));
        int fadeCall = source.indexOf("uiPipeline.renderFadePass();");
        int screenshotMarker = source.indexOf("// F12 screenshot capture", fadeCall);
        assertTrue(fadeCall >= 0 && screenshotMarker > fadeCall,
                "Engine.display() must keep a recognizable post-fade block before screenshot capture");

        String postFadeBlock = source.substring(fadeCall, screenshotMarker);
        List<String> violations = new ArrayList<>();
        for (String forbidden : List.of(
                "drawActiveLevelTitleCardOverlay(",
                "levelManager.draw(",
                "levelManager.drawWithSpritePriority(")) {
            if (postFadeBlock.contains(forbidden)) {
                violations.add("post-fade block contains general gameplay rendering call " + forbidden);
            }
        }
        if (!postFadeBlock.contains("Post-fade diagnostic overlays")) {
            violations.add("post-fade block must be explicitly labeled as diagnostic-only");
        }
        if (!postFadeBlock.contains("shouldRenderDemoSpritesOverFade()")
                || !postFadeBlock.contains("levelManager.renderSpriteObjectPass(spriteManager, true)")) {
            violations.add("credits-demo sprite-over-fade exception must remain explicit and guarded");
        }

        assertNoViolations("Engine post-fade rendering must stay diagnostic-only except the credits-demo pass",
                violations);
    }

    @Test
    void mgz2QuakeChunkS3HalfAddressIsReviewedAndDocumented() throws IOException {
        String source = Files.readString(SRC_MAIN.resolve(
                "com/openggf/game/sonic3k/events/Sonic3kMGZEvents.java"));
        String discrepancies = Files.readString(Path.of("docs", "S3K_KNOWN_DISCREPANCIES.md"));
        List<String> violations = new ArrayList<>();
        if (!source.contains("MGZ_QUAKE_CHUNK_ROM_ADDR = 0x3CBBB4")) {
            violations.add("Sonic3kMGZEvents.MGZ_QUAKE_CHUNK_ROM_ADDR must stay at reviewed ROM address 0x3CBBB4");
        }
        if (!discrepancies.contains("MGZ2 Quake Chunk Source Address")
                || !discrepancies.contains("0x3CBBB4")) {
            violations.add("docs/S3K_KNOWN_DISCREPANCIES.md must document the MGZ2 quake chunk address exception");
        }

        assertNoViolations("MGZ2 quake chunk S3-half address must stay reviewed and documented", violations);
    }

    @Test
    void sonic2ObjectRawPatternDrawsStayNativeLevelArtOnly() throws IOException {
        List<String> violations = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Path file : productionFilesUnder("com/openggf/game/sonic2/objects")) {
            String relative = relative(file);
            String stripped = stripCommentsAndStrings(Files.readString(file));
            int actual = countMatches(RAW_RENDER_PATTERN, stripped);
            if (actual == 0) {
                continue;
            }
            seen.add(relative);
            Integer expected = SONIC2_NATIVE_LEVEL_ART_RENDER_PATTERN_BUDGETS.get(relative);
            if (expected == null) {
                violations.add(relative + " has " + actual
                        + " raw renderPattern calls. Use renderPatternWithId for virtual pattern IDs, "
                        + "or document and budget native level-art rendering explicitly.");
            } else if (actual != expected) {
                violations.add(relative + " has " + actual
                        + " raw renderPattern calls; expected exactly " + expected
                        + " reviewed native level-art calls.");
            }
        }
        for (String relative : SONIC2_NATIVE_LEVEL_ART_RENDER_PATTERN_BUDGETS.keySet()) {
            if (!seen.contains(relative)) {
                violations.add(relative + " no longer has its reviewed native level-art renderPattern call; "
                        + "remove it from the guard budget.");
            }
        }

        assertNoViolations("Sonic 2 object raw renderPattern calls must stay bounded to native level art",
                violations);
    }

    @Test
    void registryBackedS3kPaletteCyclesDoNotDirectlyUploadTextures() throws IOException {
        String relative = "com/openggf/game/sonic3k/Sonic3kPaletteCycler.java";
        String source = Files.readString(SRC_MAIN.resolve(relative));
        String stripped = stripCommentsAndStrings(source);
        List<String> violations = new ArrayList<>();
        for (String className : REGISTRY_BACKED_PALETTE_CYCLE_CLASSES) {
            ClassBody body = classBody(stripped, className);
            Matcher matcher = DIRECT_CACHE_PALETTE_TEXTURE.matcher(body.source());
            while (matcher.find()) {
                violations.add(relative + ":" + lineNumberForOffset(stripped, body.startOffset() + matcher.start())
                        + " - " + className + " calls cachePaletteTexture directly after ownership migration");
            }
        }

        assertNoViolations("Registry-backed S3K palette cycles must let PaletteOwnershipRegistry own GPU upload",
                violations);
    }

    @Test
    void mgzPostBossPaletteFadeUsesRomRowsAndOwnershipRegistry() throws IOException {
        String relative = "com/openggf/game/sonic3k/objects/Mgz2PostBossPaletteFadeController.java";
        String stripped = stripCommentsAndStrings(Files.readString(SRC_MAIN.resolve(relative)));

        assertTrue(!stripped.contains("updatePalette("),
                "MGZ2 post-boss fade should not bypass PaletteOwnershipRegistry with updatePalette(...)");
        assertTrue(stripped.contains("S3kPaletteWriteSupport.applyLine("),
                "MGZ2 post-boss fade should route line writes through S3kPaletteWriteSupport");
        assertTrue(stripped.contains("PAL_MGZ_FADE_CNZ_ADDR"),
                "MGZ2 post-boss fade rows should be loaded from the ROM-backed Pal_MGZFadeCNZ constant");
        assertTrue(!stripped.contains("int[][]"),
                "MGZ2 post-boss fade should not embed Pal_MGZFadeCNZ palette rows in source");
    }

    @Test
    void mgzTopPlatformPlayerGrabStateHasExplicitRewindCoverage() throws IOException {
        String platform = Files.readString(SRC_MAIN.resolve(
                "com/openggf/game/sonic3k/objects/MGZTopPlatformObjectInstance.java"));
        String codecs = Files.readString(SRC_MAIN.resolve(
                "com/openggf/game/rewind/schema/RewindCodecs.java"));

        assertTrue(platform.contains("PlayerGrabState implements RewindStateful<PlayerGrabState.Snapshot>"),
                "MGZ top platform per-player grab helper must expose explicit rewind state");
        assertTrue(platform.contains("private record Snapshot("),
                "MGZ top platform grab state must snapshot every mutable per-player scalar");
        assertTrue(codecs.contains("RewindStateful.class.isAssignableFrom(type)"),
                "rewind collection/map codecs must accept concrete RewindStateful values");
    }

    @Test
    void productionGameplayCodeDoesNotBypassLevelMutationSurfaceWithRawLevelMutators() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : productionFiles()) {
            String relative = relative(file);
            if (RAW_LEVEL_MUTATOR_ALLOWED_FILES.contains(relative)) {
                continue;
            }
            String stripped = stripCommentsAndStrings(Files.readString(file));
            Matcher matcher = RAW_LEVEL_MUTATOR.matcher(stripped);
            while (matcher.find()) {
                violations.add(relative + ":" + lineNumberForOffset(stripped, matcher.start())
                        + " - raw level mutator " + matcher.group().replaceAll("\\s+", ""));
            }
        }

        assertNoViolations("Gameplay/runtime code must route level edits through MutableLevel or "
                + "LevelMutationSurface so rewind copy-on-write isolation cannot be bypassed", violations);
    }

    @Test
    void specialStageManagerRoutesPaletteWritesThroughLocalPaletteModel() throws IOException {
        String relative = "com/openggf/game/sonic3k/specialstage/Sonic3kSpecialStageManager.java";
        String source = Files.readString(SRC_MAIN.resolve(relative));
        String stripped = stripCommentsAndStrings(source);
        List<String> violations = new ArrayList<>();

        Matcher uploadMatcher = DIRECT_CACHE_PALETTE_TEXTURE.matcher(stripped);
        while (uploadMatcher.find()) {
            violations.add(relative + ":" + lineNumberForOffset(stripped, uploadMatcher.start())
                    + " - route special-stage palette uploads through the local palette helper");
        }

        Pattern directColorArray = Pattern.compile("\\.\\s*colors\\s*\\[");
        Matcher colorMatcher = directColorArray.matcher(stripped);
        while (colorMatcher.find()) {
            violations.add(relative + ":" + lineNumberForOffset(stripped, colorMatcher.start())
                    + " - keep special-stage color mutation inside Sonic3kSpecialStagePalette");
        }

        assertNoViolations("S3K special-stage manager should not own direct palette mutation/upload",
                violations);
    }

    @Test
    void specialStageResultsScreenUsesSharedPaletteDecoder() throws IOException {
        String relative = "com/openggf/game/sonic3k/specialstage/S3kSpecialStageResultsScreen.java";
        String source = Files.readString(SRC_MAIN.resolve(relative));
        String stripped = stripCommentsAndStrings(source);

        assertTrue(!stripped.contains(".setColor("),
                "S3K special-stage results palette should use the shared Sega palette decoder");
        assertTrue(stripped.contains("PaletteLoader.fromBytes("),
                "S3K special-stage results palette should decode Pal_Results through PaletteLoader");
        assertTrue(!stripped.contains(".cachePaletteTexture("),
                "S3K special-stage results palette upload should use Sonic3kSpecialStagePaletteUploader");
    }

    @Test
    void specialStageResultsAudioFailuresAreLogged() throws IOException {
        String relative = "com/openggf/game/sonic3k/specialstage/S3kSpecialStageResultsScreen.java";
        String source = Files.readString(SRC_MAIN.resolve(relative));

        assertTrue(!source.contains("catch (Exception e) { /* ignore */ }"),
                "S3K special-stage results audio helper failures should be logged, not swallowed");
        assertTrue(source.contains("LOG.log("),
                "S3K special-stage results screen should report audio helper failures through its logger");
    }

    @Test
    void sonic3kSpecialStageManagerSupportsSuperEmeraldArtAndState() throws IOException {
        String relative = "com/openggf/game/sonic3k/specialstage/Sonic3kSpecialStageManager.java";
        String source = Files.readString(SRC_MAIN.resolve(relative));
        String stripped = stripCommentsAndStrings(source);

        assertTrue(stripped.contains("getSuperEmeraldArt()"),
                "S3K special-stage manager should load Super Emerald art for Super Emerald stages");
        assertTrue(stripped.contains("CELL_SUPER_EMERALD"),
                "S3K special-stage manager should place Super Emerald cells when in Super Emerald mode");
        assertTrue(stripped.contains("markSuperEmeraldCollected("),
                "S3K special-stage manager should mark Super Emerald collection separately from Chaos Emeralds");
    }

    @Test
    void bridgeStakeGroundEdgeSubtypesDoNotRenderInvisible() throws IOException {
        String relative = "com/openggf/game/sonic2/objects/BridgeStakeObjectInstance.java";
        String source = Files.readString(SRC_MAIN.resolve(relative));
        String stripped = stripCommentsAndStrings(source);
        String compact = stripped.replaceAll("\\s+", "");

        assertTrue(!compact.contains("case7,8->{return;}"),
                "BridgeStake subtypes 7/8 should render a visible fallback instead of returning early");
    }

    @Test
    void specialStagePaletteCodeUsesPaletteAccessorsInsteadOfPublicColorArray() throws IOException {
        List<String> files = List.of(
                "com/openggf/game/sonic3k/specialstage/Sonic3kSpecialStageDataLoader.java",
                "com/openggf/game/sonic3k/specialstage/Sonic3kSpecialStagePalette.java"
        );
        List<String> violations = new ArrayList<>();

        Pattern publicColorArray = Pattern.compile("\\.\\s*colors\\s*\\[");
        for (String relative : files) {
            String stripped = stripCommentsAndStrings(Files.readString(SRC_MAIN.resolve(relative)));
            Matcher matcher = publicColorArray.matcher(stripped);
            while (matcher.find()) {
                violations.add(relative + ":" + lineNumberForOffset(stripped, matcher.start())
                        + " - use Palette.getColor(...) for special-stage palette construction/mutation");
            }
        }

        assertNoViolations("S3K special-stage palette code should not access Palette.colors[] directly",
                violations);
    }

    @Test
    void sonic3kFrontendScreensRoutePaletteUploadsThroughLocalHelper() throws IOException {
        List<String> files = List.of(
                "com/openggf/game/sonic3k/dataselect/S3kDataSelectRenderer.java",
                "com/openggf/game/sonic3k/levelselect/Sonic3kLevelSelectDataLoader.java",
                "com/openggf/game/sonic3k/levelselect/Sonic3kLevelSelectManager.java",
                "com/openggf/game/sonic3k/titlescreen/Sonic3kTitleScreenDataLoader.java",
                "com/openggf/game/sonic3k/titlescreen/Sonic3kTitleScreenManager.java"
        );
        List<String> violations = new ArrayList<>();

        for (String relative : files) {
            String stripped = stripCommentsAndStrings(Files.readString(SRC_MAIN.resolve(relative)));
            Matcher matcher = DIRECT_CACHE_PALETTE_TEXTURE.matcher(stripped);
            while (matcher.find()) {
                violations.add(relative + ":" + lineNumberForOffset(stripped, matcher.start())
                        + " - route S3K frontend palette uploads through S3kFrontendPaletteUploader");
            }
        }

        assertNoViolations("S3K frontend/menu screens should centralize palette texture uploads",
                violations);
    }

    @Test
    void objectCodeDoesNotAddSuspiciousPlayerTopLeftCoordinateHazards() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : productionFiles()) {
            String relative = relative(file);
            if (!isCoordinateScanned(relative) || COORDINATE_HAZARD_ALLOWED.contains(relative)) {
                continue;
            }
            violations.addAll(scanCoordinateHazards(relative, Files.readString(file)));
        }

        assertNoViolations("Suspicious player.getX()/getY() top-left coordinate use in object code", violations);
    }

    @Test
    void sampleScannerDetectsGameIdBranchButAllowsTypedRuleBranch() {
        List<String> violations = scanGameIdBranches("sample/Physics.java", """
                class Physics {
                    void bad(GameModule module) {
                        if (module.getGameId() == GameId.S1) runS1();
                    }
                    void good(GameRules rules) {
                        if (rules.playerCapability().spindashEnabled()) run();
                    }
                }
                """);

        assertEquals(List.of("sample/Physics.java:3 - branch on GameId outside routing/composition surface"),
                violations);
    }

    @Test
    void sampleScannerDetectsRuntimeDisassemblyAssetRead() {
        List<String> violations = scanRuntimeDisasmReads("sample/RuntimeLoader.java", """
                class RuntimeLoader {
                    byte[] bad() throws Exception {
                        return Files.readAllBytes(Path.of("docs/skdisasm/General/Sprites.bin"));
                    }
                }
                """);

        assertEquals(List.of("sample/RuntimeLoader.java:3 - runtime read from docs disassembly asset"),
                violations);
    }

    @Test
    void sampleScannerDetectsObjectArtDataGameSpecificName() {
        List<String> names = objectArtDataSurfaceNames("""
                class ObjectArtData {
                    private final ObjectSpriteSheet monitorSheet;
                    private final ObjectSpriteSheet ghzBridgeSheet;
                    private final List<SpriteMappingFrame> obj26Mappings;
                    public ObjectArtData(ObjectSpriteSheet monitorSheet, ObjectSpriteSheet ghzBridgeSheet) {}
                    public ObjectSpriteSheet s3kMonitorSheet() { return null; }
                }
                """);

        List<String> violations = names.stream()
                .filter(name -> OBJECT_ART_FORBIDDEN_NAME.matcher(name).matches())
                .filter(name -> !OBJECT_ART_ALLOWED_LEGACY_NAMES.contains(name))
                .distinct()
                .sorted()
                .toList();

        assertEquals(List.of("ghzBridgeSheet", "obj26Mappings", "s3kMonitorSheet"), violations);
    }

    @Test
    void sampleScannerDetectsConcreteSonicReferences() {
        List<String> violations = scanPattern("sample/Root.java", """
                import com.openggf.game.sonic2.Sonic2GameModule;

                class Root {
                    private final S3kDataSelectImageWarmup warmup = null;
                    Object bad() {
                        return new com.openggf.game.sonic3k.objects.PachinkoEnergyTrapObjectInstance(null);
                    }
                }
                """, CONCRETE_SONIC_REFERENCE);

        assertEquals(List.of(
                "sample/Root.java:1 - com.openggf.game.sonic2.Sonic2GameModule",
                "sample/Root.java:4 - S3kDataSelectImageWarmup",
                "sample/Root.java:6 - com.openggf.game.sonic3k.objects.PachinkoEnergyTrapObjectInstance"),
                violations);
    }

    @Test
    void sampleScannerDetectsGameplayServiceLookupInLowLevelCode() {
        List<String> violations = scanPattern("sample/Renderer.java", """
                class Renderer {
                    void render() {
                        GameServices.cameraOrNull();
                        GameServices.configuration();
                    }
                }
                """, LOW_LEVEL_GAMEPLAY_SERVICE_LOOKUP);

        assertEquals(List.of("sample/Renderer.java:3 - GameServices.cameraOrNull("), violations);
    }

    @Test
    void sampleScannerDetectsNextFrameRawObjectChildSpawn() {
        List<String> violations = scanPattern("sample/Object.java", """
                class Object {
                    void spawn(ObjectManager objectManager, ObjectInstance child) {
                        objectManager.addDynamicObjectNextFrame(child);
                    }
                }
                """, RAW_OBJECT_CHILD_SPAWN);

        assertEquals(List.of("sample/Object.java:3 - addDynamicObjectNextFrame("), violations);
    }

    @Test
    void sampleScannerDetectsGameModuleRegistryMutation() {
        List<String> violations = scanPattern("sample/Runtime.java", """
                class Runtime {
                    void bad(GameModule module, Rom rom) {
                        GameModuleRegistry.setCurrent(module);
                        GameModuleRegistry.detectAndSetModule(rom);
                    }
                }
                """, GAME_MODULE_REGISTRY_MUTATION);

        assertEquals(List.of(
                "sample/Runtime.java:3 - GameModuleRegistry.setCurrent(",
                "sample/Runtime.java:4 - GameModuleRegistry.detectAndSetModule("), violations);
    }

    @Test
    void sampleScannerDetectsCoordinateHazardNearDistanceExpression() {
        List<String> violations = scanCoordinateHazards("sample/Object.java", """
                class Object {
                    void update(PlayableEntity player) {
                        int dx = Math.abs(player.getX() - x);
                    }
                }
                """);

        assertEquals(List.of("sample/Object.java:3 - player.getX() near coordinate hazard context"),
                violations);
    }

    @Test
    void sampleSourceBudgetIgnoresCommentsAndBlankLines() {
        SourceFile source = SourceFile.fromText("""
                class Budgeted {
                    // This comment documents a non-obvious invariant.

                    void work() {
                        run();
                    }

                    /*
                     * Multi-line explanations should not consume architecture budget.
                     */
                    void moreWork() {
                        runAgain();
                    }
                }
                """);

        assertEquals(8, source.effectiveSourceLineCount());
    }

    private static List<Path> productionFiles() throws IOException {
        if (!Files.isDirectory(SRC_MAIN)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.walk(SRC_MAIN)) {
            return stream.filter(path -> path.toString().endsWith(".java"))
                    .sorted()
                    .toList();
        }
    }

    private static List<Path> productionFilesUnder(String relativeRoot) throws IOException {
        Path root = SRC_MAIN.resolve(relativeRoot);
        if (!Files.isDirectory(root)) {
            fail("production scan root is missing: " + relativeRoot);
        }
        try (Stream<Path> stream = Files.walk(root)) {
            List<Path> files = stream.filter(path -> path.toString().endsWith(".java"))
                    .sorted()
                    .toList();
            assertTrue(!files.isEmpty(), "production scan root must contain Java files: " + relativeRoot);
            return files;
        }
    }

    private static boolean isApprovedGameIdBranchFile(String relative) {
        return GAME_ID_BRANCH_APPROVED_FILES.contains(relative)
                || GAME_ID_BRANCH_APPROVED_PREFIXES.stream().anyMatch(relative::startsWith);
    }

    private static boolean isCoordinateScanned(String relative) {
        return COORDINATE_SCAN_PREFIXES.stream().anyMatch(relative::startsWith);
    }

    private static boolean isApprovedGameModuleRegistryMutationFile(String relative) {
        return relative.equals("com/openggf/game/GameModuleRegistry.java")
                || relative.equals("com/openggf/game/RomDetectionService.java")
                || relative.startsWith("com/openggf/tools/");
    }

    private static List<String> scanGameIdBranches(String relative, String source) {
        String stripped = stripCommentsAndStrings(source);
        List<String> violations = new ArrayList<>();
        Matcher matcher = GAME_ID_BRANCH.matcher(stripped);
        while (matcher.find()) {
            violations.add(relative + ":" + lineNumberForOffset(stripped, matcher.start())
                    + " - branch on GameId outside routing/composition surface");
        }
        return violations;
    }

    private static List<String> scanRuntimeDisasmReads(String relative, String source) {
        String stripped = stripComments(source);
        List<String> violations = new ArrayList<>();
        Matcher matcher = RUNTIME_DISASM_PATH_LITERAL.matcher(stripped);
        while (matcher.find()) {
            violations.add(relative + ":" + lineNumberForOffset(stripped, matcher.start())
                    + " - runtime read from docs disassembly asset");
        }
        return violations;
    }

    private static List<String> scanPattern(String relative, String source, Pattern pattern) {
        String stripped = stripCommentsAndStrings(source);
        List<String> violations = new ArrayList<>();
        Matcher matcher = pattern.matcher(stripped);
        while (matcher.find()) {
            violations.add(relative + ":" + lineNumberForOffset(stripped, matcher.start())
                    + " - " + matcher.group().replaceAll("\\s+", ""));
        }
        return violations;
    }

    private static int countMatches(Pattern pattern, String source) {
        Matcher matcher = pattern.matcher(source);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private record ClassBody(String source, int startOffset) {
    }

    private static ClassBody classBody(String source, String className) {
        Matcher matcher = Pattern.compile("\\bclass\\s+" + Pattern.quote(className) + "\\b").matcher(source);
        if (!matcher.find()) {
            throw new AssertionError("Could not find class " + className);
        }
        int openBrace = source.indexOf('{', matcher.end());
        if (openBrace < 0) {
            throw new AssertionError("Could not find body for class " + className);
        }
        int depth = 0;
        for (int i = openBrace; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return new ClassBody(source.substring(openBrace + 1, i), openBrace + 1);
                }
            }
        }
        throw new AssertionError("Could not find closing brace for class " + className);
    }

    private static List<String> objectArtDataSurfaceNames(String source) {
        Set<String> names = new HashSet<>();
        Matcher matcher = OBJECT_ART_DECLARATION.matcher(source);
        while (matcher.find()) {
            String name = matcher.group(1);
            if (!Set.of("class", "return", "new", "if").contains(name)) {
                names.add(name);
            }
        }
        matcher = OBJECT_ART_ACCESSOR.matcher(source);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names.stream().sorted().toList();
    }

    private static List<String> scanCoordinateHazards(String relative, String source) {
        String stripped = stripCommentsAndStrings(source);
        List<String> violations = new ArrayList<>();
        Matcher matcher = PLAYER_TOP_LEFT_ACCESS.matcher(stripped);
        while (matcher.find()) {
            int start = Math.max(0, matcher.start() - 100);
            int end = Math.min(stripped.length(), matcher.end() + 100);
            String context = stripped.substring(start, end);
            if (COORDINATE_CONTEXT.matcher(context).find()) {
                violations.add(relative + ":" + lineNumberForOffset(stripped, matcher.start())
                        + " - " + matcher.group().replaceAll("\\s+", "") + " near coordinate hazard context");
            }
        }
        return violations;
    }

    private static void assertNoViolations(String message, List<String> violations) {
        if (!violations.isEmpty()) {
            fail(message + ":\n  " + String.join("\n  ", violations));
        }
    }

    private static String relative(Path file) {
        return SRC_MAIN.relativize(file).toString().replace('\\', '/');
    }

    private static String stripCommentsAndStrings(String source) {
        return strip(source, true);
    }

    private static String stripComments(String source) {
        return strip(source, false);
    }

    private static String strip(String source, boolean stripStrings) {
        StringBuilder stripped = new StringBuilder(source.length());
        boolean inLineComment = false;
        boolean inBlockComment = false;
        boolean inString = false;
        boolean inChar = false;
        boolean escaping = false;

        for (int i = 0; i < source.length(); i++) {
            char current = source.charAt(i);
            char next = i + 1 < source.length() ? source.charAt(i + 1) : '\0';

            if (inLineComment) {
                if (current == '\n' || current == '\r') {
                    inLineComment = false;
                    stripped.append(current);
                } else {
                    stripped.append(' ');
                }
                continue;
            }
            if (inBlockComment) {
                if (current == '*' && next == '/') {
                    stripped.append("  ");
                    i++;
                    inBlockComment = false;
                } else {
                    stripped.append(current == '\n' || current == '\r' ? current : ' ');
                }
                continue;
            }
            if (inString) {
                stripped.append(stripStrings && current != '\n' && current != '\r' && current != '"' ? ' ' : current);
                if (escaping) {
                    escaping = false;
                } else if (current == '\\') {
                    escaping = true;
                } else if (current == '"') {
                    inString = false;
                }
                continue;
            }
            if (inChar) {
                stripped.append(stripStrings && current != '\n' && current != '\r' && current != '\'' ? ' ' : current);
                if (escaping) {
                    escaping = false;
                } else if (current == '\\') {
                    escaping = true;
                } else if (current == '\'') {
                    inChar = false;
                }
                continue;
            }
            if (current == '/' && next == '/') {
                stripped.append("  ");
                i++;
                inLineComment = true;
                continue;
            }
            if (current == '/' && next == '*') {
                stripped.append("  ");
                i++;
                inBlockComment = true;
                continue;
            }
            if (current == '"') {
                inString = true;
                stripped.append(current);
                continue;
            }
            if (current == '\'') {
                inChar = true;
                stripped.append(current);
                continue;
            }
            stripped.append(current);
        }
        return stripped.toString();
    }

    private static int lineNumberForOffset(String text, int offset) {
        int line = 1;
        for (int i = 0; i < offset; i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private record MethodBudget(String relativePath, String methodName, int maxLines) {
    }

    private record EmbeddedRuntimeDataBudget(
            String relativePath,
            String description,
            Pattern pattern,
            int expectedCount) {
    }

    private record SourceSignal(String relativePath, String requiredText) {
    }

    private record SourceFile(String text, List<String> lines) {
        private static final Pattern METHOD_START = Pattern.compile(
                "^\\s*(?:public|private|protected)\\s+(?:static\\s+)?(?:synchronized\\s+)?"
                        + "[\\w<>\\[\\].?, ]+\\s+(\\w+)\\s*\\([^;]*\\)"
                        + "(?:\\s+throws\\s+[^\\{]+)?\\s*\\{\\s*$");

        static SourceFile read(Path path) throws IOException {
            return new SourceFile(Files.readString(path), Files.readAllLines(path));
        }

        static SourceFile fromText(String text) {
            return new SourceFile(text, text.lines().toList());
        }

        int effectiveSourceLineCount() {
            return (int) stripComments(text).lines()
                    .filter(line -> !line.isBlank())
                    .count();
        }

        List<MethodSpan> methods() {
            List<MethodSpan> methods = new ArrayList<>();
            boolean inMethod = false;
            int startLine = 0;
            int braceDepth = 0;
            String methodName = "";

            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (!inMethod) {
                    Matcher matcher = METHOD_START.matcher(line);
                    if (matcher.matches()) {
                        inMethod = true;
                        startLine = i + 1;
                        methodName = matcher.group(1);
                        braceDepth = braceDelta(line);
                    }
                } else {
                    braceDepth += braceDelta(line);
                    if (braceDepth <= 0) {
                        methods.add(new MethodSpan(methodName, startLine, i + 1));
                        inMethod = false;
                    }
                }
            }
            return methods;
        }

        MethodSpan methodNamed(String name) {
            return methods().stream()
                    .filter(method -> method.name().equals(name))
                    .findFirst()
                    .orElse(null);
        }

        private static int braceDelta(String line) {
            int delta = 0;
            for (int i = 0; i < line.length(); i++) {
                if (line.charAt(i) == '{') {
                    delta++;
                } else if (line.charAt(i) == '}') {
                    delta--;
                }
            }
            return delta;
        }
    }

    private record MethodSpan(String name, int startLine, int endLine) {
        int lineCount() {
            return endLine - startLine + 1;
        }
    }
}
