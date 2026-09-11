package com.openggf.tests;

import com.openggf.architecture.CompositionRoot;
import com.openggf.camera.Camera;
import com.openggf.game.GameStateManager;
import com.openggf.game.CheckpointState;
import com.openggf.game.GameServices;
import com.openggf.game.animation.AnimatedTileChannelGraph;
import com.openggf.game.dataselect.CrossGameDataSelectPresentations;
import com.openggf.game.mutation.ZoneLayoutMutationPipeline;
import com.openggf.game.palette.PaletteOwnershipRegistry;
import com.openggf.game.render.AdvancedRenderModeController;
import com.openggf.game.render.SpecialRenderEffectRegistry;
import com.openggf.game.rewind.RewindRegistry;
import com.openggf.game.rewind.RewindSnapshottable;
import com.openggf.game.sonic1.Sonic1GameModule;
import com.openggf.game.sonic2.Sonic2GameModule;
import com.openggf.game.sonic2.Sonic2LevelAnimationManager;
import com.openggf.game.sonic3k.Sonic3kLevelAnimationManager;
import com.openggf.game.sonic3k.Sonic3kLevelEventManager;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.dataselect.S3kDataSelectManager;
import com.openggf.game.session.EditorModeContext;
import com.openggf.game.session.EngineServices;
import com.openggf.game.session.GameplayModeContext;
import com.openggf.game.session.SessionManager;
import com.openggf.game.session.WorldSession;
import com.openggf.game.zone.ZoneRuntimeRegistry;
import com.openggf.graphics.FadeManager;
import com.openggf.graphics.PatternAtlasRange;
import com.openggf.level.LevelManager;
import com.openggf.level.LevelRenderer;
import com.openggf.level.WaterSystem;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectServices;
import com.openggf.physics.CollisionSystem;
import com.openggf.physics.TerrainCollisionManager;
import com.openggf.sprites.managers.SpriteManager;
import com.openggf.timer.TimerManager;
import com.openggf.level.ParallaxManager;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaConstructorCall;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.junit.CacheMode;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.freeze.FreezingArchRule;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bytecode-level architectural invariants.
 *
 * <p>Source-text hazards that should also fail on comments, unused imports, or
 * string literals stay in {@link TestArchitecturalReviewGuard}.
 */
@AnalyzeClasses(
        packages = "com.openggf",
        cacheMode = CacheMode.FOREVER,
        importOptions = ImportOption.DoNotIncludeTests.class)
class TestArchUnitRules {
    private static final String GAME_SERVICES = GameServices.class.getName();
    private static final String SESSION_MANAGER = SessionManager.class.getName();
    private static final String ENGINE_SERVICES = EngineServices.class.getName();
    private static final String WORLD_SESSION = WorldSession.class.getName();
    private static final String GAMEPLAY_MODE_CONTEXT = GameplayModeContext.class.getName();
    private static final String EDITOR_MODE_CONTEXT = EditorModeContext.class.getName();
    private static final String ZONE_RUNTIME_REGISTRY = ZoneRuntimeRegistry.class.getName();
    private static final String PALETTE_OWNERSHIP_REGISTRY = PaletteOwnershipRegistry.class.getName();
    private static final String ANIMATED_TILE_CHANNEL_GRAPH = AnimatedTileChannelGraph.class.getName();
    private static final String ZONE_LAYOUT_MUTATION_PIPELINE = ZoneLayoutMutationPipeline.class.getName();
    private static final String SPECIAL_RENDER_EFFECT_REGISTRY = SpecialRenderEffectRegistry.class.getName();
    private static final String ADVANCED_RENDER_MODE_CONTROLLER = AdvancedRenderModeController.class.getName();
    private static final String REWIND_REGISTRY = RewindRegistry.class.getName();
    private static final java.util.Set<String> CORE_RUNTIME_CYCLE_CLUSTER_SLICES = java.util.Set.of(
            "audio",
            "camera",
            "control",
            "data",
            "debug",
            "editor",
            "game",
            "graphics",
            "level",
            "physics",
            "sprites",
            "testmode",
            "timer",
            "tools",
            "trace",
            "util");
    private static final Set<String> CORE_RUNTIME_TOP_LEVEL_DEPENDENCY_EDGES = Set.of(
            "audio -> configuration",
            "audio -> data",
            "audio -> debug",
            "audio -> game",
            // Offline benchmark harness. `bench` is a leaf: it consumes the
            // profiler's raw-sample interface from `debug` and depends on
            // nothing else in the runtime, and only the CLI tools consume it.
            // Nothing in the engine may depend on `bench` — measurement code
            // must never become something gameplay code reaches for.
            "bench -> debug",
            "tools -> bench",
            "camera -> configuration",
            "camera -> game",
            "camera -> sprites",
            "capture -> audio",
            "data -> configuration",
            "data -> audio",
            "data -> game",
            "data -> level",
            "data -> sprites",
            "debug -> camera",
            "debug -> configuration",
            "debug -> control",
            "debug -> data",
            "debug -> game",
            "debug -> graphics",
            "debug -> level",
            "debug -> physics",
            "debug -> sprites",
            "editor -> camera",
            "editor -> control",
            "editor -> debug",
            "editor -> game",
            "editor -> graphics",
            "editor -> level",
            "game -> architecture",
            "game -> audio",
            "game -> camera",
            "game -> configuration",
            "game -> control",
            "game -> data",
            "game -> debug",
            "game -> graphics",
            "game -> level",
            "game -> physics",
            "game -> sprites",
            "game -> testmode",
            "game -> timer",
            "game -> tools",
            "game -> trace",
            "game -> util",
            "game -> version",
            "graphics -> camera",
            "graphics -> configuration",
            "graphics -> control",
            "graphics -> debug",
            "graphics -> game",
            "graphics -> level",
            "graphics -> sprites",
            "graphics -> util",
            "integration -> game",
            "integration -> level",
            "level -> audio",
            "level -> camera",
            "level -> configuration",
            "level -> data",
            "level -> debug",
            "level -> editor",
            "level -> game",
            "level -> graphics",
            "level -> physics",
            "level -> sprites",
            "level -> testmode",
            "level -> tools",
            "level -> trace",
            "level -> util",
            "physics -> camera",
            "physics -> game",
            "physics -> level",
            "physics -> sprites",
            "render -> debug",
            "render -> game",
            "sprites -> audio",
            "sprites -> camera",
            "sprites -> configuration",
            "sprites -> control",
            "sprites -> data",
            "sprites -> game",
            "sprites -> graphics",
            "sprites -> level",
            "sprites -> physics",
            "sprites -> timer",
            "sprites -> trace",
            "testmode -> camera",
            "testmode -> configuration",
            "testmode -> control",
            "testmode -> debug",
            "testmode -> game",
            "testmode -> graphics",
            "testmode -> sprites",
            "testmode -> trace",
            "timer -> audio",
            "timer -> camera",
            "timer -> game",
            "timer -> level",
            "timer -> sprites",
            "tools -> audio",
            "tools -> camera",
            "tools -> capture",
            "tools -> configuration",
            "tools -> control",
            "tools -> data",
            "tools -> debug",
            "tools -> game",
            "tools -> graphics",
            "tools -> level",
            "tools -> sprites",
            "tools -> trace",
            // Offline capture provenance consumes the leaf build-identity package;
            // version has no runtime back-edge and does not join the cycle cluster.
            "tools -> version",
            "trace -> camera",
            "trace -> configuration",
            "trace -> debug",
            "trace -> game",
            "trace -> level",
            "trace -> physics",
            "trace -> sprites",
            "util -> data",
            "util -> game",
            "util -> level",
            "util -> tools");
    private static final String[] RUNTIME_MANAGER_SINGLETONS = {
            Camera.class.getName(),
            LevelManager.class.getName(),
            SpriteManager.class.getName(),
            GameStateManager.class.getName(),
            TimerManager.class.getName(),
            FadeManager.class.getName(),
            CollisionSystem.class.getName(),
            TerrainCollisionManager.class.getName(),
            WaterSystem.class.getName(),
            ParallaxManager.class.getName()
    };

    private static DescribedPredicate<JavaConstructorCall> targetOwnerNamed(String... fullyQualifiedNames) {
        return new DescribedPredicate<>("target owner named " + String.join(", ", fullyQualifiedNames)) {
            @Override
            public boolean test(JavaConstructorCall input) {
                String targetOwnerName = input.getTargetOwner().getName();
                for (String fullyQualifiedName : fullyQualifiedNames) {
                    if (targetOwnerName.equals(fullyQualifiedName)) {
                        return true;
                    }
                }
                return false;
            }
        };
    }

    private static DescribedPredicate<JavaMethodCall> targetMethodNamedOnOwner(
            String methodName, String... fullyQualifiedNames) {
        return new DescribedPredicate<>(
                "target method named " + methodName + " on " + String.join(", ", fullyQualifiedNames)) {
            @Override
            public boolean test(JavaMethodCall input) {
                if (!input.getTarget().getName().equals(methodName)) {
                    return false;
                }
                String targetOwnerName = input.getTargetOwner().getName();
                for (String fullyQualifiedName : fullyQualifiedNames) {
                    if (targetOwnerName.equals(fullyQualifiedName)) {
                        return true;
                    }
                }
                return false;
            }
        };
    }

    private static DescribedPredicate<JavaClass> inTopLevelSlice(String slice) {
        return new DescribedPredicate<>("top-level com.openggf." + slice + " slice") {
            @Override
            public boolean test(JavaClass input) {
                return slice.equals(topLevelCoreRuntimeSlice(input));
            }
        };
    }

    private static DescribedPredicate<JavaConstructorCall> targetIsConcreteSonicProvider() {
        return new DescribedPredicate<>(
                "target is a concrete Sonic module, detector, profile, art, or object provider class") {
            @Override
            public boolean test(JavaConstructorCall input) {
                String packageName = input.getTargetOwner().getPackageName();
                if (!packageName.startsWith("com.openggf.game.sonic1")
                        && !packageName.startsWith("com.openggf.game.sonic2")
                        && !packageName.startsWith("com.openggf.game.sonic3k")) {
                    return false;
                }
                String simpleName = input.getTargetOwner().getSimpleName();
                return (simpleName.startsWith("Sonic1")
                        || simpleName.startsWith("Sonic2")
                        || simpleName.startsWith("Sonic3k")
                        || simpleName.startsWith("S3k"))
                        && (simpleName.contains("GameModule")
                        || simpleName.contains("RomDetector")
                        || simpleName.contains("Profile")
                        || simpleName.contains("ObjectArt")
                        || simpleName.contains("PlayerArt")
                        || simpleName.contains("DustArt")
                        || simpleName.contains("ObjectRegistry")
                        || simpleName.contains("ObjectPlacement")
                        || simpleName.contains("ObjectInstance"));
            }
        };
    }

    @ArchTest
    static final ArchRule sonic2_level_animation_manager_exposes_rewind_snapshots =
            classes().that().haveFullyQualifiedName(Sonic2LevelAnimationManager.class.getName())
                    .should().beAssignableTo(RewindSnapshottable.class);

    @ArchTest
    static final ArchRule sonic3k_level_animation_manager_exposes_rewind_snapshots =
            classes().that().haveFullyQualifiedName(Sonic3kLevelAnimationManager.class.getName())
                    .should().beAssignableTo(RewindSnapshottable.class);

    @ArchTest
    static final ArchRule object_services_interface_does_not_depend_on_game_services =
            noClasses().that().haveFullyQualifiedName(ObjectServices.class.getName())
                    .should().dependOnClassesThat().haveFullyQualifiedName(GameServices.class.getName());

    @ArchTest
    static final ArchRule checkpoint_state_does_not_depend_on_sonic3k_level_events =
            noClasses().that().haveFullyQualifiedName(CheckpointState.class.getName())
                    .should().dependOnClassesThat().haveFullyQualifiedName(Sonic3kLevelEventManager.class.getName());

    @ArchTest
    static final ArchRule shared_level_layer_does_not_depend_on_sonic3k_zone_constants =
            noClasses().that().haveFullyQualifiedName(LevelManager.class.getName())
                    .or().haveFullyQualifiedName(LevelRenderer.class.getName())
                    .should().dependOnClassesThat().haveFullyQualifiedName(Sonic3kZoneIds.class.getName());

    @ArchTest
    static final ArchRule sonic_one_and_two_modules_do_not_depend_on_s3k_data_select_manager =
            noClasses().that().haveFullyQualifiedName(Sonic1GameModule.class.getName())
                    .or().haveFullyQualifiedName(Sonic2GameModule.class.getName())
                    .should().dependOnClassesThat().haveFullyQualifiedName(S3kDataSelectManager.class.getName());

    @ArchTest
    static final ArchRule shared_data_select_layer_does_not_depend_on_sonic3k_delegates =
            noClasses().that().haveFullyQualifiedName(CrossGameDataSelectPresentations.class.getName())
                    .should().dependOnClassesThat().resideInAPackage("com.openggf.game.sonic3k..");

    @ArchTest
    static final ArchRule production_code_does_not_call_runtime_manager_singletons =
            noClasses().that()
                    .doNotHaveFullyQualifiedName(GAME_SERVICES)
                    .should().callMethodWhere(targetMethodNamedOnOwner("getInstance", RUNTIME_MANAGER_SINGLETONS))
                    .as("production code should not call runtime-owned manager getInstance() methods directly")
                    .because("runtime-owned managers should be reached through GameServices or injected ObjectServices");

    @ArchTest
    static final ArchRule package_slices_are_free_of_cycles =
            FreezingArchRule.freeze(slices().matching("com.openggf.(*)..")
                            .should().beFreeOfCycles()
                            .ignoreDependency(inTopLevelSlice("audio"), inTopLevelSlice("configuration"))
                            .ignoreDependency(inTopLevelSlice("audio"), inTopLevelSlice("data"))
                            .ignoreDependency(inTopLevelSlice("audio"), inTopLevelSlice("debug"))
                            .ignoreDependency(inTopLevelSlice("audio"), inTopLevelSlice("game"))
                            .ignoreDependency(inTopLevelSlice("camera"), inTopLevelSlice("configuration"))
                            .ignoreDependency(inTopLevelSlice("camera"), inTopLevelSlice("game"))
                            .ignoreDependency(inTopLevelSlice("camera"), inTopLevelSlice("sprites"))
                            .ignoreDependency(inTopLevelSlice("capture"), inTopLevelSlice("audio"))
                            .ignoreDependency(inTopLevelSlice("data"), inTopLevelSlice("configuration"))
                            .ignoreDependency(inTopLevelSlice("data"), inTopLevelSlice("audio"))
                            .ignoreDependency(inTopLevelSlice("data"), inTopLevelSlice("game"))
                            .ignoreDependency(inTopLevelSlice("data"), inTopLevelSlice("level"))
                            .ignoreDependency(inTopLevelSlice("data"), inTopLevelSlice("sprites"))
                            .ignoreDependency(inTopLevelSlice("debug"), inTopLevelSlice("camera"))
                            .ignoreDependency(inTopLevelSlice("debug"), inTopLevelSlice("configuration"))
                            .ignoreDependency(inTopLevelSlice("debug"), inTopLevelSlice("control"))
                            .ignoreDependency(inTopLevelSlice("debug"), inTopLevelSlice("data"))
                            .ignoreDependency(inTopLevelSlice("debug"), inTopLevelSlice("game"))
                            .ignoreDependency(inTopLevelSlice("debug"), inTopLevelSlice("graphics"))
                            .ignoreDependency(inTopLevelSlice("debug"), inTopLevelSlice("level"))
                            .ignoreDependency(inTopLevelSlice("debug"), inTopLevelSlice("physics"))
                            .ignoreDependency(inTopLevelSlice("debug"), inTopLevelSlice("sprites"))
                            .ignoreDependency(inTopLevelSlice("editor"), inTopLevelSlice("camera"))
                            .ignoreDependency(inTopLevelSlice("editor"), inTopLevelSlice("control"))
                            .ignoreDependency(inTopLevelSlice("editor"), inTopLevelSlice("debug"))
                            .ignoreDependency(inTopLevelSlice("editor"), inTopLevelSlice("game"))
                            .ignoreDependency(inTopLevelSlice("editor"), inTopLevelSlice("graphics"))
                            .ignoreDependency(inTopLevelSlice("editor"), inTopLevelSlice("level"))
                            .ignoreDependency(inTopLevelSlice("game"), inTopLevelSlice("architecture"))
                            .ignoreDependency(inTopLevelSlice("game"), inTopLevelSlice("audio"))
                            .ignoreDependency(inTopLevelSlice("game"), inTopLevelSlice("camera"))
                            .ignoreDependency(inTopLevelSlice("game"), inTopLevelSlice("configuration"))
                            .ignoreDependency(inTopLevelSlice("game"), inTopLevelSlice("control"))
                            .ignoreDependency(inTopLevelSlice("game"), inTopLevelSlice("data"))
                            .ignoreDependency(inTopLevelSlice("game"), inTopLevelSlice("debug"))
                            .ignoreDependency(inTopLevelSlice("game"), inTopLevelSlice("graphics"))
                            .ignoreDependency(inTopLevelSlice("game"), inTopLevelSlice("level"))
                            .ignoreDependency(inTopLevelSlice("game"), inTopLevelSlice("physics"))
                            .ignoreDependency(inTopLevelSlice("game"), inTopLevelSlice("sprites"))
                            .ignoreDependency(inTopLevelSlice("game"), inTopLevelSlice("testmode"))
                            .ignoreDependency(inTopLevelSlice("game"), inTopLevelSlice("timer"))
                            .ignoreDependency(inTopLevelSlice("game"), inTopLevelSlice("tools"))
                            .ignoreDependency(inTopLevelSlice("game"), inTopLevelSlice("trace"))
                            .ignoreDependency(inTopLevelSlice("game"), inTopLevelSlice("util"))
                            .ignoreDependency(inTopLevelSlice("game"), inTopLevelSlice("version"))
                            .ignoreDependency(inTopLevelSlice("graphics"), inTopLevelSlice("camera"))
                            .ignoreDependency(inTopLevelSlice("graphics"), inTopLevelSlice("configuration"))
                            .ignoreDependency(inTopLevelSlice("graphics"), inTopLevelSlice("control"))
                            .ignoreDependency(inTopLevelSlice("graphics"), inTopLevelSlice("debug"))
                            .ignoreDependency(inTopLevelSlice("graphics"), inTopLevelSlice("game"))
                            .ignoreDependency(inTopLevelSlice("graphics"), inTopLevelSlice("level"))
                            .ignoreDependency(inTopLevelSlice("graphics"), inTopLevelSlice("sprites"))
                            .ignoreDependency(inTopLevelSlice("graphics"), inTopLevelSlice("util"))
                            .ignoreDependency(inTopLevelSlice("integration"), inTopLevelSlice("game"))
                            .ignoreDependency(inTopLevelSlice("integration"), inTopLevelSlice("level"))
                            .ignoreDependency(inTopLevelSlice("level"), inTopLevelSlice("audio"))
                            .ignoreDependency(inTopLevelSlice("level"), inTopLevelSlice("camera"))
                            .ignoreDependency(inTopLevelSlice("level"), inTopLevelSlice("configuration"))
                            .ignoreDependency(inTopLevelSlice("level"), inTopLevelSlice("data"))
                            .ignoreDependency(inTopLevelSlice("level"), inTopLevelSlice("debug"))
                            .ignoreDependency(inTopLevelSlice("level"), inTopLevelSlice("editor"))
                            .ignoreDependency(inTopLevelSlice("level"), inTopLevelSlice("game"))
                            .ignoreDependency(inTopLevelSlice("level"), inTopLevelSlice("graphics"))
                            .ignoreDependency(inTopLevelSlice("level"), inTopLevelSlice("physics"))
                            .ignoreDependency(inTopLevelSlice("level"), inTopLevelSlice("sprites"))
                            .ignoreDependency(inTopLevelSlice("level"), inTopLevelSlice("testmode"))
                            .ignoreDependency(inTopLevelSlice("level"), inTopLevelSlice("tools"))
                            .ignoreDependency(inTopLevelSlice("level"), inTopLevelSlice("trace"))
                            .ignoreDependency(inTopLevelSlice("level"), inTopLevelSlice("util"))
                            .ignoreDependency(inTopLevelSlice("physics"), inTopLevelSlice("camera"))
                            .ignoreDependency(inTopLevelSlice("physics"), inTopLevelSlice("game"))
                            .ignoreDependency(inTopLevelSlice("physics"), inTopLevelSlice("level"))
                            .ignoreDependency(inTopLevelSlice("physics"), inTopLevelSlice("sprites"))
                            .ignoreDependency(inTopLevelSlice("render"), inTopLevelSlice("debug"))
                            .ignoreDependency(inTopLevelSlice("render"), inTopLevelSlice("game"))
                            .ignoreDependency(inTopLevelSlice("sprites"), inTopLevelSlice("audio"))
                            .ignoreDependency(inTopLevelSlice("sprites"), inTopLevelSlice("camera"))
                            .ignoreDependency(inTopLevelSlice("sprites"), inTopLevelSlice("configuration"))
                            .ignoreDependency(inTopLevelSlice("sprites"), inTopLevelSlice("control"))
                            .ignoreDependency(inTopLevelSlice("sprites"), inTopLevelSlice("data"))
                            .ignoreDependency(inTopLevelSlice("sprites"), inTopLevelSlice("game"))
                            .ignoreDependency(inTopLevelSlice("sprites"), inTopLevelSlice("graphics"))
                            .ignoreDependency(inTopLevelSlice("sprites"), inTopLevelSlice("level"))
                            .ignoreDependency(inTopLevelSlice("sprites"), inTopLevelSlice("physics"))
                            .ignoreDependency(inTopLevelSlice("sprites"), inTopLevelSlice("timer"))
                            .ignoreDependency(inTopLevelSlice("sprites"), inTopLevelSlice("trace"))
                            .ignoreDependency(inTopLevelSlice("testmode"), inTopLevelSlice("camera"))
                            .ignoreDependency(inTopLevelSlice("testmode"), inTopLevelSlice("configuration"))
                            .ignoreDependency(inTopLevelSlice("testmode"), inTopLevelSlice("control"))
                            .ignoreDependency(inTopLevelSlice("testmode"), inTopLevelSlice("debug"))
                            .ignoreDependency(inTopLevelSlice("testmode"), inTopLevelSlice("game"))
                            .ignoreDependency(inTopLevelSlice("testmode"), inTopLevelSlice("graphics"))
                            .ignoreDependency(inTopLevelSlice("testmode"), inTopLevelSlice("sprites"))
                            .ignoreDependency(inTopLevelSlice("testmode"), inTopLevelSlice("trace"))
                            .ignoreDependency(inTopLevelSlice("timer"), inTopLevelSlice("audio"))
                            .ignoreDependency(inTopLevelSlice("timer"), inTopLevelSlice("camera"))
                            .ignoreDependency(inTopLevelSlice("timer"), inTopLevelSlice("game"))
                            .ignoreDependency(inTopLevelSlice("timer"), inTopLevelSlice("level"))
                            .ignoreDependency(inTopLevelSlice("timer"), inTopLevelSlice("sprites"))
                            .ignoreDependency(inTopLevelSlice("tools"), inTopLevelSlice("audio"))
                            .ignoreDependency(inTopLevelSlice("tools"), inTopLevelSlice("camera"))
                            .ignoreDependency(inTopLevelSlice("tools"), inTopLevelSlice("capture"))
                            .ignoreDependency(inTopLevelSlice("tools"), inTopLevelSlice("configuration"))
                            .ignoreDependency(inTopLevelSlice("tools"), inTopLevelSlice("control"))
                            .ignoreDependency(inTopLevelSlice("tools"), inTopLevelSlice("data"))
                            .ignoreDependency(inTopLevelSlice("tools"), inTopLevelSlice("debug"))
                            .ignoreDependency(inTopLevelSlice("tools"), inTopLevelSlice("game"))
                            .ignoreDependency(inTopLevelSlice("tools"), inTopLevelSlice("graphics"))
                            .ignoreDependency(inTopLevelSlice("tools"), inTopLevelSlice("level"))
                            .ignoreDependency(inTopLevelSlice("tools"), inTopLevelSlice("sprites"))
                            .ignoreDependency(inTopLevelSlice("tools"), inTopLevelSlice("trace"))
                            .ignoreDependency(inTopLevelSlice("trace"), inTopLevelSlice("camera"))
                            .ignoreDependency(inTopLevelSlice("trace"), inTopLevelSlice("configuration"))
                            .ignoreDependency(inTopLevelSlice("trace"), inTopLevelSlice("debug"))
                            .ignoreDependency(inTopLevelSlice("trace"), inTopLevelSlice("game"))
                            .ignoreDependency(inTopLevelSlice("trace"), inTopLevelSlice("level"))
                            .ignoreDependency(inTopLevelSlice("trace"), inTopLevelSlice("physics"))
                            .ignoreDependency(inTopLevelSlice("trace"), inTopLevelSlice("sprites"))
                            .ignoreDependency(inTopLevelSlice("util"), inTopLevelSlice("data"))
                            .ignoreDependency(inTopLevelSlice("util"), inTopLevelSlice("game"))
                            .ignoreDependency(inTopLevelSlice("util"), inTopLevelSlice("level"))
                            .ignoreDependency(inTopLevelSlice("util"), inTopLevelSlice("tools"))
                            .as("top-level com.openggf package slices should be free of cycles"))
                    .because("package cycles make ownership boundaries and migration work hard to reason about; cycle:core-runtime freezes 16 existing top-level slices so new slices cannot join cycles");

    @ArchTest
    static void core_runtime_cycle_cluster_does_not_gain_top_level_edges(JavaClasses classes) {
        Set<String> currentEdges = topLevelCoreRuntimeDependencyEdges(classes);
        Set<String> unexpectedEdges = new TreeSet<>(currentEdges);
        unexpectedEdges.removeAll(CORE_RUNTIME_TOP_LEVEL_DEPENDENCY_EDGES);

        assertTrue(unexpectedEdges.isEmpty(),
                "core-runtime top-level dependency edges grew; route new dependencies through "
                        + "provider/service boundaries or consciously update the ratchet:\n  "
                        + String.join("\n  ", unexpectedEdges));
    }

    @ArchTest
    static final ArchRule low_level_layers_do_not_depend_on_runtime_layers =
            FreezingArchRule.freeze(layeredArchitecture()
                            .consideringOnlyDependenciesInLayers()
                            .layer("Audio").definedBy("com.openggf.audio..")
                            .layer("Graphics").definedBy("com.openggf.graphics..")
                            .layer("Data").definedBy("com.openggf.data..")
                            .layer("Runtime").definedBy(
                                    "com.openggf.level..",
                                    "com.openggf.sprites..",
                                    "com.openggf.game..",
                                    "com.openggf.game.sonic1..",
                                    "com.openggf.game.sonic2..",
                                    "com.openggf.game.sonic3k..")
                            .whereLayer("Audio").mayNotAccessAnyLayer()
                            .whereLayer("Graphics").mayNotAccessAnyLayer()
                            .whereLayer("Data").mayNotAccessAnyLayer()
                            .as("audio, graphics, and data layers should not depend on runtime gameplay layers"))
                    .because("lower-level services should stay reusable and should not know about level/sprite/game orchestration; frozen baseline: 209 violations (2026-07-02: +2 ROM-backed SEGA-boot PCM edges in AudioManager, solved edges pruned)");

    @ArchTest
    static final ArchRule object_instance_named_classes_extend_object_instance_base =
            FreezingArchRule.freeze(classes().that()
                            .resideInAnyPackage(
                                    "com.openggf.level.objects..",
                                    "com.openggf.game.sonic1.objects..",
                                    "com.openggf.game.sonic2.objects..",
                                    "com.openggf.game.sonic3k.objects..")
                            .and().haveSimpleNameEndingWith("Instance")
                            .and().areNotInterfaces()
                            .and().doNotHaveSimpleName("ObjectInstance")
                            .should().beAssignableTo(AbstractObjectInstance.class)
                            .as("object classes named *Instance should extend AbstractObjectInstance"))
                    .because("object lifecycle, services injection, collision, and rewind coverage assume object instances share the base class; frozen baseline: 1 violation");

    @ArchTest
    static final ArchRule abstract_named_classes_are_abstract =
            classes().that().haveSimpleNameStartingWith("Abstract")
                    .and().areNotInterfaces()
                    .should().haveModifier(JavaModifier.ABSTRACT)
                    .as("classes named Abstract* should be declared abstract");

    @ArchTest
    static final ArchRule sonic_named_classes_live_under_matching_game_packages =
            FreezingArchRule.freeze(classes().that()
                            .haveSimpleNameStartingWith("Sonic1")
                            .should().resideInAPackage("com.openggf.game.sonic1..")
                            .orShould().resideInAPackage("com.openggf.tools..")
                            .as("Sonic1* classes should live under the Sonic 1 package or explicit tooling"))
                    .because("game-specific names should not leak into shared packages; frozen baseline: 1 violation");

    @ArchTest
    static final ArchRule sonic2_named_classes_live_under_matching_game_packages =
            FreezingArchRule.freeze(classes().that()
                            .haveSimpleNameStartingWith("Sonic2")
                            .should().resideInAPackage("com.openggf.game.sonic2..")
                            .orShould().resideInAPackage("com.openggf.tools..")
                            .as("Sonic2* classes should live under the Sonic 2 package or explicit tooling"))
                    .because("game-specific names should not leak into shared packages; frozen baseline: 0 violations");

    @ArchTest
    static final ArchRule sonic3k_named_classes_live_under_matching_game_packages =
            FreezingArchRule.freeze(classes().that()
                            .haveSimpleNameStartingWith("Sonic3k")
                            .or().haveSimpleNameStartingWith("S3k")
                            .should().resideInAPackage("com.openggf.game.sonic3k..")
                            .orShould().resideInAPackage("com.openggf.tools..")
                            .as("Sonic3k*/S3k* classes should live under the Sonic 3&K package or explicit tooling"))
                    .because("game-specific names should not leak into shared packages; frozen baseline: 0 violations");

    @ArchTest
    static final ArchRule session_contexts_are_only_constructed_by_session_composition_roots =
            noClasses().that()
                    .resideOutsideOfPackage("com.openggf.game.session..")
                    .should().callConstructorWhere(targetOwnerNamed(
                            WORLD_SESSION,
                            GAMEPLAY_MODE_CONTEXT,
                            EDITOR_MODE_CONTEXT))
                    .as("WorldSession and mode contexts should only be created by session composition roots")
                    .because("session ownership must stay centralized in SessionManager/session factories");

    @ArchTest
    static final ArchRule world_session_does_not_depend_on_runtime_managers_or_registries =
            noClasses().that().haveFullyQualifiedName(WORLD_SESSION)
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "com.openggf.camera..",
                            "com.openggf.timer..",
                            "com.openggf.graphics..",
                            "com.openggf.physics..",
                            "com.openggf.sprites..",
                            "com.openggf.level.objects..",
                            "com.openggf.level.rings..",
                            "com.openggf.game.zone..",
                            "com.openggf.game.palette..",
                            "com.openggf.game.animation..",
                            "com.openggf.game.mutation..",
                            "com.openggf.game.render..",
                            "com.openggf.game.rewind..")
                    .as("WorldSession should not depend on gameplay runtime managers or runtime-owned registries")
                    .because("WorldSession owns durable world metadata, not disposable gameplay runtime state");

    @ArchTest
    static final ArchRule runtime_owned_framework_packages_do_not_access_global_service_roots =
            FreezingArchRule.freeze(noClasses().that()
                            .resideInAnyPackage(
                                    "com.openggf.game.zone..",
                                    "com.openggf.game.palette..",
                                    "com.openggf.game.animation..",
                                    "com.openggf.game.mutation..",
                                    "com.openggf.game.render..",
                                    "com.openggf.game.rewind..")
                            .should().accessClassesThat().haveFullyQualifiedName(GAME_SERVICES)
                            .orShould().accessClassesThat().haveFullyQualifiedName(SESSION_MANAGER)
                            .orShould().accessClassesThat().haveFullyQualifiedName(ENGINE_SERVICES)
                            .as("runtime-owned framework packages should not access GameServices, SessionManager, or EngineServices directly"))
                    .because("runtime-owned framework code should receive dependencies from GameplayModeContext or explicit collaborators; frozen baseline: 13 violations");

    @ArchTest
    static final ArchRule game_services_does_not_depend_on_concrete_game_packages_or_object_implementations =
            noClasses().that().haveFullyQualifiedName(GAME_SERVICES)
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "com.openggf.game.sonic1..",
                            "com.openggf.game.sonic2..",
                            "com.openggf.game.sonic3k..")
                    .as("GameServices should not depend on concrete game packages or object implementations")
                    .because("GameServices is a shared facade and should expose provider contracts only");

    @ArchTest
    static final ArchRule runtime_registry_controllers_are_only_constructed_by_runtime_composition_roots =
            FreezingArchRule.freeze(noClasses().that()
                            .resideOutsideOfPackages(
                                    "com.openggf.game.session..",
                                    "com.openggf.tests..")
                            .and().areNotAnnotatedWith(CompositionRoot.class)
                            .should().callConstructorWhere(targetOwnerNamed(
                                    ZONE_RUNTIME_REGISTRY,
                                    PALETTE_OWNERSHIP_REGISTRY,
                                    ANIMATED_TILE_CHANNEL_GRAPH,
                                    ZONE_LAYOUT_MUTATION_PIPELINE,
                                    SPECIAL_RENDER_EFFECT_REGISTRY,
                                    ADVANCED_RENDER_MODE_CONTROLLER,
                                    REWIND_REGISTRY))
                            .as("runtime-owned registries and controllers should only be constructed by runtime composition roots"))
                    .because("GameplayModeContext/session factories own these gameplay-scoped registries; frozen baseline: 4 violations");

    @ArchTest
    static final ArchRule runtime_shared_stateful_controllers_expose_rewind_snapshots =
            classes().that().haveFullyQualifiedName(ZONE_RUNTIME_REGISTRY)
                    .or().haveFullyQualifiedName(PALETTE_OWNERSHIP_REGISTRY)
                    .or().haveFullyQualifiedName(ANIMATED_TILE_CHANNEL_GRAPH)
                    .or().haveFullyQualifiedName(ZONE_LAYOUT_MUTATION_PIPELINE)
                    .or().haveFullyQualifiedName(SPECIAL_RENDER_EFFECT_REGISTRY)
                    .or().haveFullyQualifiedName(ADVANCED_RENDER_MODE_CONTROLLER)
                    .should().beAssignableTo(RewindSnapshottable.class)
                    .as("stateful runtime-shared registries and controllers should expose rewind snapshots")
                    .because("gameplay-scoped mutable runtime framework state must participate in rewind capture");

    @ArchTest
    static final ArchRule shared_code_does_not_construct_concrete_sonic_provider_classes =
            FreezingArchRule.freeze(noClasses().that()
                            .resideOutsideOfPackages(
                                    "com.openggf.game.sonic1..",
                                    "com.openggf.game.sonic2..",
                                    "com.openggf.game.sonic3k..",
                                    "com.openggf.game.session..",
                                    "com.openggf.tools..",
                                    "com.openggf.audio.debug..",
                                    "com.openggf.debug..")
                            .and().areNotAnnotatedWith(CompositionRoot.class)
                            .should().callConstructorWhere(targetIsConcreteSonicProvider())
                            .as("shared code should not construct concrete Sonic provider/art/object classes"))
                    .because("shared layers should obtain game-specific providers through GameModule or approved composition roots; frozen baseline: 0 violations");

    @ArchTest
    static final ArchRule object_packages_do_not_access_global_game_services =
            FreezingArchRule.freeze(noClasses().that()
                            .resideInAnyPackage(
                                    "com.openggf.level.objects..",
                                    "com.openggf.game.sonic1.objects..",
                                    "com.openggf.game.sonic2.objects..",
                                    "com.openggf.game.sonic3k.objects..")
                            .and().doNotHaveFullyQualifiedName("com.openggf.level.objects.AbstractObjectInstance")
                            .and().doNotHaveFullyQualifiedName("com.openggf.level.objects.DefaultObjectServices")
                            .and().doNotHaveFullyQualifiedName("com.openggf.level.objects.BootstrapObjectServices")
                            .and().doNotHaveFullyQualifiedName("com.openggf.level.objects.AbstractObjectRegistry")
                            .and().doNotHaveFullyQualifiedName("com.openggf.game.sonic1.objects.Sonic1ObjectRegistry")
                            .and().doNotHaveFullyQualifiedName("com.openggf.game.sonic2.objects.Sonic2ObjectRegistry")
                            .and().doNotHaveFullyQualifiedName("com.openggf.game.sonic3k.objects.Sonic3kObjectRegistry")
                            .should().accessClassesThat().haveFullyQualifiedName(GameServices.class.getName())
                            .as("object packages should not access global GameServices except approved bridges"))
                    .because("object instances should use injected ObjectServices; frozen baseline: 0 violations");

    @ArchTest
    static final ArchRule shared_layers_do_not_depend_on_game_specific_packages =
            FreezingArchRule.freeze(noClasses().that()
                            .resideInAnyPackage("com.openggf.level..", "com.openggf.game..")
                            .and().resideOutsideOfPackages(
                                    "com.openggf.game.sonic1..",
                                    "com.openggf.game.sonic2..",
                                    "com.openggf.game.sonic3k..")
                            .and().areNotAnnotatedWith(CompositionRoot.class)
                            .should().dependOnClassesThat().resideInAnyPackage(
                                    "com.openggf.game.sonic1..",
                                    "com.openggf.game.sonic2..",
                                    "com.openggf.game.sonic3k..")
                    .as("shared level and game layers should not depend on game-specific packages"))
                    .because("shared layers should depend on provider contracts or shared abstractions, not concrete game packages; frozen baseline: 9 violations");

    @ArchTest
    static final ArchRule per_game_packages_do_not_cross_depend =
            FreezingArchRule.freeze(slices().matching("com.openggf.game.(sonic*)..")
                            .should().notDependOnEachOther()
                            .as("per-game packages should not cross-depend"))
                    .because("cross-game donation and asset reuse must be explicit and must not grow accidentally; frozen baseline: 37 violations");

    @ArchTest
    static final ArchRule mgz_events_and_objects_do_not_depend_on_mgz_scroll_handler =
            noClasses().that()
                    .resideInAnyPackage(
                            "com.openggf.game.sonic3k.events..",
                            "com.openggf.game.sonic3k.objects..")
                    .should().dependOnClassesThat().haveFullyQualifiedName(
                            "com.openggf.game.sonic3k.scroll.SwScrlMgz")
                    .as("MGZ event/object code should publish through MgzZoneRuntimeState, not depend on SwScrlMgz");

    @ArchTest
    static void imported_classes_include_application_code(JavaClasses classes) {
        assertTrue(classes.contain(GameServices.class), "ArchUnit must import application classes");
        classes().that().haveFullyQualifiedName(GameServices.class.getName())
                .should().haveSimpleName("GameServices")
                .allowEmptyShould(false)
                .check(classes);
    }

    @ArchTest
    static void virtual_pattern_base_fields_are_backed_by_pattern_atlas_range(JavaClasses classes)
            throws ReflectiveOperationException {
        List<String> violations = new java.util.ArrayList<>();
        for (com.tngtech.archunit.core.domain.JavaClass javaClass : classes) {
            for (JavaField javaField : javaClass.getFields()) {
                if (!javaField.getRawType().isEquivalentTo(int.class)
                        || !javaField.getName().contains("PATTERN_BASE")) {
                    continue;
                }
                Class<?> owner = javaField.getOwner().reflect();
                Field field = owner.getDeclaredField(javaField.getName());
                int modifiers = field.getModifiers();
                if (!Modifier.isStatic(modifiers) || !Modifier.isFinal(modifiers)) {
                    continue;
                }
                field.setAccessible(true);
                int value = field.getInt(null);
                PatternAtlasRange range = patternAtlasRangeFor(value);
                if (range == null && value > 0x7FF) {
                    violations.add(javaField.getFullName() + " hard-codes ungoverned virtual pattern id 0x"
                            + Integer.toHexString(value));
                    continue;
                }
                if (range != null
                        && !owner.equals(PatternAtlasRange.class)
                        && !fieldInitializesFromPatternAtlasRange(javaField, range.name())) {
                    violations.add(javaField.getFullName() + " hard-codes virtual pattern id 0x"
                            + Integer.toHexString(value) + " inside PatternAtlasRange." + range.name()
                            + " [0x" + Integer.toHexString(range.base()) + ", 0x"
                            + Integer.toHexString(range.endExclusive()) + ")");
                }
            }
        }
        assertTrue(violations.isEmpty(),
                "Virtual pattern base fields should reference PatternAtlasRange instead of hard-coding or bypassing documented ranges:\n"
                        + String.join("\n", violations));
    }

    private static Set<String> topLevelCoreRuntimeDependencyEdges(JavaClasses classes) {
        Set<String> edges = new TreeSet<>();
        for (JavaClass origin : classes) {
            String originSlice = topLevelSlice(origin);
            if (originSlice == null) {
                continue;
            }
            for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
                String targetSlice = topLevelSlice(dependency.getTargetClass());
                if (targetSlice == null || originSlice.equals(targetSlice)) {
                    continue;
                }
                if (!CORE_RUNTIME_CYCLE_CLUSTER_SLICES.contains(originSlice)
                        && !CORE_RUNTIME_CYCLE_CLUSTER_SLICES.contains(targetSlice)) {
                    continue;
                }
                edges.add(originSlice + " -> " + targetSlice);
            }
        }
        return edges;
    }

    private static String topLevelCoreRuntimeSlice(JavaClass javaClass) {
        String topLevel = topLevelSlice(javaClass);
        return topLevel != null && CORE_RUNTIME_CYCLE_CLUSTER_SLICES.contains(topLevel) ? topLevel : null;
    }

    private static String topLevelSlice(JavaClass javaClass) {
        String packageName = javaClass.getPackageName();
        String prefix = "com.openggf.";
        if (!packageName.startsWith(prefix)) {
            return null;
        }
        String remainder = packageName.substring(prefix.length());
        int dot = remainder.indexOf('.');
        return dot >= 0 ? remainder.substring(0, dot) : remainder;
    }

    private static boolean fieldInitializesFromPatternAtlasRange(JavaField javaField, String rangeName) {
        Path source = Path.of("src/main/java")
                .resolve(javaField.getOwner().getPackageName().replace('.', '/'))
                .resolve(javaField.getOwner().getSimpleName() + ".java");
        if (!Files.exists(source)) {
            return false;
        }
        try {
            String text = Files.readString(source);
              String initializer = javaField.getName() + " = PatternAtlasRange." + rangeName + ".base()";
              return text.contains(initializer)
                      || text.contains(initializer + " +");
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Unable to inspect pattern range field source: " + source, e);
        }
    }

    private static PatternAtlasRange patternAtlasRangeFor(int value) {
        return Arrays.stream(PatternAtlasRange.values())
                .filter(range -> value >= range.base() && value < range.endExclusive())
                .findFirst()
                .orElse(null);
    }
}
