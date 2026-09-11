package com.openggf.game.session;

import com.openggf.camera.Camera;
import com.openggf.game.BonusStageProvider;
import com.openggf.game.BonusStageState;
import com.openggf.game.BonusStageType;
import com.openggf.game.GameRng;
import com.openggf.game.GameStateManager;
import com.openggf.game.ObjectArtProvider;
import com.openggf.game.RuntimeArtCoordinator;
import com.openggf.game.animation.AnimatedTileCachePolicy;
import com.openggf.game.animation.AnimatedTileChannel;
import com.openggf.game.animation.AnimatedTileChannelGraph;
import com.openggf.game.animation.DestinationPlan;
import com.openggf.game.mutation.MutationEffects;
import com.openggf.game.mutation.ZoneLayoutMutationPipeline;
import com.openggf.game.NoOpBonusStageProvider;
import com.openggf.game.palette.PaletteOwnershipRegistry;
import com.openggf.game.rewind.CompositeSnapshot;
import com.openggf.game.rewind.RewindSnapshottable;
import com.openggf.game.rewind.RewindRegistry;
import com.openggf.game.render.AdvancedRenderFrameState;
import com.openggf.game.render.AdvancedRenderMode;
import com.openggf.game.render.AdvancedRenderModeContext;
import com.openggf.game.render.AdvancedRenderModeController;
import com.openggf.game.render.SpecialRenderEffect;
import com.openggf.game.render.SpecialRenderEffectContext;
import com.openggf.game.render.SpecialRenderEffectRegistry;
import com.openggf.game.render.SpecialRenderEffectStage;
import com.openggf.game.solid.DefaultSolidExecutionRegistry;
import com.openggf.game.sonic2.Sonic2GameModule;
import com.openggf.game.timing.HardwareTimingBoundaryObserver;
import com.openggf.game.timing.HardwareReadinessAdmissionPolicy;
import com.openggf.game.timing.HardwareTimingService;
import com.openggf.game.resources.DynamicArtLifecycleService;
import com.openggf.game.resources.QueueDiagnosticSnapshot;
import com.openggf.game.zone.NoOpZoneRuntimeState;
import com.openggf.game.zone.ZoneRuntimeRegistry;
import com.openggf.game.zone.ZoneRuntimeState;
import com.openggf.graphics.FadeManager;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.LevelManager;
import com.openggf.level.ParallaxManager;
import com.openggf.level.Pattern;
import com.openggf.level.WaterSystem;
import com.openggf.level.animation.AnimatedPatternManager;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectSpriteSheet;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.level.rings.RingManager;
import com.openggf.game.rewind.snapshot.ObjectManagerSnapshot;
import com.openggf.game.rewind.snapshot.RingSnapshot;
import com.openggf.physics.CollisionSystem;
import com.openggf.sprites.animation.SpriteAnimationSet;
import com.openggf.physics.TerrainCollisionManager;
import com.openggf.sprites.managers.SpriteManager;
import com.openggf.timer.TimerManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the {@link RewindRegistry} integration on
 * {@link GameplayModeContext}.
 *
 * <p>Tests verify that the nine always-available atomic adapters are
 * registered automatically when {@link GameplayModeContext#attachGameplayManagers}
 * is called, without requiring a full level load or ROM access.
 */
class TestGameplayModeContextRewindRegistry {

    @BeforeEach
    void configureServices() {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
    }

    private static GameplayModeContext buildAttachedContext() {
        WorldSession world = new WorldSession(new Sonic2GameModule());
        GameplayModeContext ctx = new GameplayModeContext(world);

        Camera camera = new Camera();
        TimerManager timers = new TimerManager();
        GameStateManager gameState = new GameStateManager();
        FadeManager fade = new FadeManager();
        GameRng rng = new GameRng(GameRng.Flavour.S1_S2);
        DefaultSolidExecutionRegistry solid = new DefaultSolidExecutionRegistry();

        ctx.attachGameplayManagers(camera, timers, gameState, fade, rng, solid);
        return ctx;
    }

    @Test
    void contextAcceptsCompositionModuleWithoutTypedRules() {
        com.openggf.game.GameModule module =
                mock(com.openggf.game.GameModule.class);
        when(module.createRuntimeArtCoordinator(
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(RuntimeArtCoordinator.NONE);

        GameplayModeContext context =
                new GameplayModeContext(new WorldSession(module));

        assertNotNull(context.plcFrameLifecycle());
    }

    @Test
    void registryIsNonNullAfterAttach() {
        GameplayModeContext ctx = buildAttachedContext();
        assertNotNull(ctx.getRewindRegistry());
    }

    @Test
    void registryHasAtomicAdaptersAfterAttach() {
        GameplayModeContext ctx = buildAttachedContext();
        RewindRegistry registry = ctx.getRewindRegistry();

        CompositeSnapshot snapshot = registry.capture();

        Set<String> expectedKeys = Set.of(
                "camera",
                "gamestate",
                "gamerng",
                "timermanager",
                "fademanager",
                "oscillation",
                HardwareTimingService.REWIND_KEY,
                DynamicArtLifecycleService.REWIND_KEY,
                ctx.seamlessTransitionResourceHandoffs().key(),
                com.openggf.game.sonic2.timing.Sonic2LevelMusicScheduler.REWIND_KEY,
                com.openggf.game.sonic2.slotmachine.CNZPrizeSoundState.REWIND_KEY,
                "solid-execution");
        assertTrue(snapshot.entries().keySet().containsAll(expectedKeys),
                "Expected all atomic adapter keys to be present, got: " + snapshot.entries().keySet());
    }

    @Test
    void exactlyTwelveAtomicKeysAfterAttach() {
        GameplayModeContext ctx = buildAttachedContext();
        RewindRegistry registry = ctx.getRewindRegistry();
        CompositeSnapshot snapshot = registry.capture();
        assertEquals(12, snapshot.entries().keySet().size(),
                "Expected exactly 12 atomic adapters, got: " + snapshot.entries().keySet());
    }

    @Test
    void actTransitionRebindMovesOnlyObjectAndRingManagers() {
        GameplayModeContext ctx = buildAttachedContext();
        RewindRegistry registry = ctx.getRewindRegistry();
        @SuppressWarnings("unchecked")
        RewindSnapshottable<ObjectManagerSnapshot> sourceObjects =
                mock(RewindSnapshottable.class);
        ObjectManagerSnapshot sourceObjectSnapshot = mock(ObjectManagerSnapshot.class);
        when(sourceObjects.key()).thenReturn("object-manager");
        when(sourceObjects.capture()).thenReturn(sourceObjectSnapshot);
        registry.register(sourceObjects);
        RingManager sourceRings = mock(RingManager.class);
        RingSnapshot sourceRingSnapshot = mock(RingSnapshot.class);
        when(sourceRings.key()).thenReturn("rings");
        when(sourceRings.capture()).thenReturn(sourceRingSnapshot);
        registry.register(sourceRings);
        RewindSnapshottable<Integer> title = adapter("s3k-title-card");
        RewindSnapshottable<Integer> provider = adapter("s3k-plc-art");
        RewindSnapshottable<Integer> event = adapter("level-event");
        RewindSnapshottable<Integer> solids = adapter("solid-objects");
        RewindSnapshottable<Integer> tilemap = adapter("tilemap-mutations");
        registry.register(title);
        registry.register(provider);
        registry.register(event);
        registry.register(solids);
        registry.register(tilemap);
        CompositeSnapshot sourceSnapshot = registry.capture();
        List<String> before = List.copyOf(sourceSnapshot.entries().keySet());

        ObjectManager replacementObjects = mock(ObjectManager.class);
        @SuppressWarnings("unchecked")
        RewindSnapshottable<ObjectManagerSnapshot> objectAdapter =
                mock(RewindSnapshottable.class);
        when(objectAdapter.key()).thenReturn("object-manager");
        when(objectAdapter.capture()).thenReturn(mock(ObjectManagerSnapshot.class));
        when(replacementObjects.rewindSnapshottable()).thenReturn(objectAdapter);
        RingManager replacementRings = mock(RingManager.class);
        when(replacementRings.key()).thenReturn("rings");
        when(replacementRings.capture()).thenReturn(mock(RingSnapshot.class));

        ctx.rebindActTransitionManagerAdapters(replacementObjects, replacementRings);

        List<String> after = List.copyOf(registry.capture().entries().keySet());
        assertEquals(withoutTransitionManagers(before), withoutTransitionManagers(after),
                "the narrow transition rebind must not move title, provider, event, solid, or tilemap owners");
        assertEquals(List.of("object-manager", "rings"), after.subList(after.size() - 2, after.size()));
        assertTrue(after.indexOf("s3k-title-card") < after.indexOf("s3k-plc-art"),
                "title-before-provider restore ordering must remain unchanged");

        registry.restore(sourceSnapshot);

        verify(objectAdapter).restore(sourceObjectSnapshot);
        verify(replacementRings).restore(sourceRingSnapshot);
        verify(sourceObjects, never()).restore(sourceObjectSnapshot);
        verify(sourceRings, never()).restore(sourceRingSnapshot);
        assertEquals(1, ((TrackingAdapter) title).restoreCount);
        assertEquals(1, ((TrackingAdapter) provider).restoreCount);
        assertEquals(1, ((TrackingAdapter) event).restoreCount);
        assertEquals(1, ((TrackingAdapter) solids).restoreCount);
        assertEquals(1, ((TrackingAdapter) tilemap).restoreCount);
    }

    @Test
    void registryIsNullBeforeAttach() {
        WorldSession world = new WorldSession(new Sonic2GameModule());
        GameplayModeContext ctx = new GameplayModeContext(world);
        assertNull(ctx.getRewindRegistry(),
                "Registry should be null until attachGameplayManagers is called");
    }

    private static RewindSnapshottable<Integer> adapter(String key) {
        return new TrackingAdapter(key);
    }

    private static final class TrackingAdapter implements RewindSnapshottable<Integer> {
        private final String key;
        private int restoreCount;

        private TrackingAdapter(String key) {
            this.key = key;
        }

        @Override
        public String key() {
            return key;
        }

        @Override
        public Integer capture() {
            return 1;
        }

        @Override
        public void restore(Integer snapshot) {
            restoreCount++;
        }
    }

    private static List<String> withoutTransitionManagers(List<String> keys) {
        return keys.stream()
                .filter(key -> !key.equals("object-manager") && !key.equals("rings"))
                .toList();
    }

    @Test
    void contextAttachmentAndTeardownOwnDynamicArtRunLifetime() {
        GameplayModeContext ctx = new GameplayModeContext(
                new WorldSession(new Sonic2GameModule()));
        assertFalse(ctx.dynamicArtLifecycle().isRunActive());

        ctx.attachGameplayManagers(
                new Camera(), new TimerManager(), new GameStateManager(),
                new FadeManager(), new GameRng(GameRng.Flavour.S1_S2),
                new DefaultSolidExecutionRegistry());
        assertTrue(ctx.dynamicArtLifecycle().isRunActive());

        ctx.tearDownManagers();
        assertFalse(ctx.dynamicArtLifecycle().isRunActive());
    }

    @Test
    void tearDownClearsRegistry() {
        GameplayModeContext ctx = buildAttachedContext();
        assertNotNull(ctx.getRewindRegistry());
        ctx.tearDownManagers();
        assertFalse(ctx.isGameplayRuntimeReady(),
                "A torn-down gameplay context must not be advertised as runtime-ready");
        assertNull(ctx.getRewindRegistry(),
                "Registry should be null after tearDownManagers");
    }

    @Test
    void tearDownClearsActiveBonusStageProvider() {
        GameplayModeContext ctx = buildAttachedContext();
        BonusStageProvider provider = new StubBonusStageProvider();
        ctx.setActiveBonusStageProvider(provider);
        assertSame(provider, ctx.getActiveBonusStageProvider());

        ctx.tearDownManagers();

        assertSame(NoOpBonusStageProvider.INSTANCE, ctx.getActiveBonusStageProvider(),
                "A torn-down gameplay context must not retain a session-scoped bonus stage provider");
    }

    @Test
    void reattachRebuildsRegistry() {
        GameplayModeContext ctx = buildAttachedContext();
        RewindRegistry first = ctx.getRewindRegistry();

        // Tear down and re-attach (simulates a resume-parked path)
        ctx.tearDownManagers();
        Camera camera2 = new Camera();
        TimerManager timers2 = new TimerManager();
        GameStateManager gameState2 = new GameStateManager();
        FadeManager fade2 = new FadeManager();
        GameRng rng2 = new GameRng(GameRng.Flavour.S1_S2);
        DefaultSolidExecutionRegistry solid2 = new DefaultSolidExecutionRegistry();
        ctx.attachGameplayManagers(camera2, timers2, gameState2, fade2, rng2, solid2);

        RewindRegistry second = ctx.getRewindRegistry();
        assertNotNull(second);
        assertNotSame(first, second, "Re-attach should produce a new RewindRegistry instance");
        // New registry should have the same eleven keys
        assertEquals(12, second.capture().entries().keySet().size());
    }

    @Test
    void contextOwnsOneHardwareTimingServiceAcrossManagerReattachment() {
        WorldSession world = new WorldSession(new Sonic2GameModule());
        GameplayModeContext ctx = new GameplayModeContext(world);
        HardwareTimingService service = ctx.hardwareTiming();

        ctx.attachGameplayManagers(
                new Camera(), new TimerManager(), new GameStateManager(),
                new FadeManager(), new GameRng(GameRng.Flavour.S1_S2),
                new DefaultSolidExecutionRegistry());
        ctx.tearDownManagers();
        ctx.attachGameplayManagers(
                new Camera(), new TimerManager(), new GameStateManager(),
                new FadeManager(), new GameRng(GameRng.Flavour.S1_S2),
                new DefaultSolidExecutionRegistry());

        assertSame(service, ctx.hardwareTiming());
        assertTrue(ctx.getRewindRegistry().capture().entries()
                .containsKey(HardwareTimingService.REWIND_KEY));
    }

    @Test
    void contextOwnsBoundaryObserverWithoutStaticOrCrossSessionState() {
        GameplayModeContext first = new GameplayModeContext(
                new WorldSession(new Sonic2GameModule()));
        GameplayModeContext second = new GameplayModeContext(
                new WorldSession(new Sonic2GameModule()));
        HardwareTimingBoundaryObserver observer = boundary -> {
        };

        first.setHardwareTimingBoundaryObserver(observer);

        assertSame(observer, first.hardwareTimingBoundaryObserver());
        assertSame(HardwareTimingBoundaryObserver.NO_OP,
                second.hardwareTimingBoundaryObserver());
        first.tearDownManagers();
        assertSame(HardwareTimingBoundaryObserver.NO_OP,
                first.hardwareTimingBoundaryObserver());
    }

    @Test
    void recordedPolicyBeginsAdmissionDuringContextConstruction() {
        GameplayModeContext context = new GameplayModeContext(
                new WorldSession(new Sonic2GameModule()),
                HardwareReadinessAdmissionPolicy.RECORDED);

        assertEquals(HardwareReadinessAdmissionPolicy.RECORDED,
                context.hardwareTiming().admissionPolicy());
        assertNotNull(context.recordedCompletionAuthority());
    }

    @Test
    void liveContextActivatesRecordedAdmissionWithoutReplacingRuntimeOwners() {
        GameplayModeContext context = new GameplayModeContext(
                new WorldSession(new Sonic2GameModule()));
        HardwareTimingService timing = context.hardwareTiming();
        RuntimeArtCoordinator runtimeArt = context.runtimeArtCoordinator();

        var authority = context.activateRecordedHardwareAdmission();

        assertSame(timing, context.hardwareTiming());
        assertSame(runtimeArt, context.runtimeArtCoordinator());
        assertSame(authority, context.recordedCompletionAuthority());
        assertEquals(HardwareReadinessAdmissionPolicy.RECORDED,
                timing.admissionPolicy());
        assertThrows(IllegalStateException.class,
                context::activateRecordedHardwareAdmission);
    }

    @Test
    void recordedAdmissionRequiresEveryRuntimeArtQueueToBeIdle() {
        QueueDiagnosticSnapshot busy = new QueueDiagnosticSnapshot(
                QueueDiagnosticSnapshot.Kind.S3K_KOS_MODULE,
                true, false, 0x1000, 0x2000, 8, 4,
                List.of(), List.of());
        QueueDiagnosticSnapshot prepared = new QueueDiagnosticSnapshot(
                QueueDiagnosticSnapshot.Kind.S3K_KOS_MODULE,
                true, true, 0x1000, 0x2000, 8, 0,
                List.of(), List.of());
        QueueDiagnosticSnapshot queued = new QueueDiagnosticSnapshot(
                QueueDiagnosticSnapshot.Kind.S3K_KOS_MODULE,
                true, false, -1, -1, -1, -1,
                List.of("queued"), List.of());

        for (QueueDiagnosticSnapshot active : List.of(busy, prepared, queued)) {
            IllegalStateException failure = assertThrows(
                    IllegalStateException.class,
                    () -> GameplayModeContext
                            .requireQueuesIdleForRecordedAdmission(
                                    List.of(active)));
            assertTrue(failure.getMessage().contains("not idle"));
        }
        GameplayModeContext.requireQueuesIdleForRecordedAdmission(List.of(
                QueueDiagnosticSnapshot.idle(
                        QueueDiagnosticSnapshot.Kind.S3K_KOS_MODULE,
                        List.of())));
    }

    @Test
    void contextTeardownInvokesReplayCloseHookExactlyOnce() {
        GameplayModeContext context = new GameplayModeContext(
                new WorldSession(new Sonic2GameModule()),
                HardwareReadinessAdmissionPolicy.RECORDED);
        AtomicInteger closes = new AtomicInteger();
        context.setHardwareTimingReplayCloseHook(closes::incrementAndGet);

        context.tearDownManagers();
        context.tearDownManagers();

        assertEquals(1, closes.get());
    }

    @Test
    void partialCoreReattachAfterTeardownDoesNotReportRuntimeReady() {
        GameplayModeContext ctx = buildAttachedContext();
        attachLevelManagers(ctx);
        attachSharedRegistries(ctx);
        assertTrue(ctx.isGameplayRuntimeReady(), "Fully attached context should be runtime-ready");

        ctx.tearDownManagers();
        ctx.attachGameplayManagers(new Camera(), new TimerManager(), new GameStateManager(),
                new FadeManager(), new GameRng(GameRng.Flavour.S1_S2), new DefaultSolidExecutionRegistry());

        assertFalse(ctx.isGameplayRuntimeReady(),
                "Core-manager reattach must not expose stale level managers or registries as runtime-ready");
    }

    @Test
    void sharedRuntimeRegistriesAreRewindRegisteredAfterAttach() {
        GameplayModeContext ctx = buildAttachedContext();
        attachSharedRegistries(ctx);

        CompositeSnapshot snapshot = ctx.getRewindRegistry().capture();

        assertTrue(snapshot.entries().keySet().containsAll(Set.of(
                "zone-runtime",
                "palette-ownership",
                "animated-tile-channels",
                "special-render",
                "advanced-render-mode",
                "mutation-pipeline")),
                "Expected all runtime-owned registry adapters, got: " + snapshot.entries().keySet());
    }

    @Test
    void tearDownClearsAllAttachedSharedRegistryState() {
        GameplayModeContext ctx = buildAttachedContext();
        ZoneRuntimeRegistry zoneRuntime = new ZoneRuntimeRegistry();
        PaletteOwnershipRegistry paletteOwnership = new PaletteOwnershipRegistry();
        AnimatedTileChannelGraph animatedTiles = new AnimatedTileChannelGraph();
        SpecialRenderEffectRegistry specialRender = new SpecialRenderEffectRegistry();
        AdvancedRenderModeController advancedRender = new AdvancedRenderModeController();
        ZoneLayoutMutationPipeline mutationPipeline = new ZoneLayoutMutationPipeline();

        ctx.attachSharedRegistries(zoneRuntime, paletteOwnership, animatedTiles,
                specialRender, advancedRender, mutationPipeline);

        zoneRuntime.install(new MutableZoneRuntimeState());
        paletteOwnership.setPaletteRotationDisabled(true);
        animatedTiles.install(List.of(dummyChannel()));
        specialRender.register(new StatefulEffect());
        advancedRender.register(new StatefulMode());
        mutationPipeline.queue(context -> MutationEffects.redrawAllTilemaps());

        ctx.tearDownManagers();

        assertSame(NoOpZoneRuntimeState.INSTANCE, zoneRuntime.current());
        assertFalse(paletteOwnership.isPaletteRotationDisabled(),
                "palette rotation disable state must not leak after gameplay teardown");
        assertTrue(animatedTiles.channels().isEmpty());
        assertTrue(specialRender.isEmpty());
        assertTrue(advancedRender.isEmpty());
        assertTrue(mutationPipeline.isEmpty());
    }

    @Test
    void specialRenderRegistryRestoresStatefulEffectSnapshots() {
        SpecialRenderEffectRegistry registry = new SpecialRenderEffectRegistry();
        StatefulEffect effect = new StatefulEffect();
        registry.register(effect);
        effect.value = 7;

        var snapshot = registry.capture();
        effect.value = 11;

        registry.restore(snapshot);

        assertEquals(7, effect.value);
    }

    @Test
    void advancedRenderControllerRestoresStatefulModeSnapshots() {
        AdvancedRenderModeController controller = new AdvancedRenderModeController();
        StatefulMode mode = new StatefulMode();
        controller.register(mode);
        mode.value = 5;

        var snapshot = controller.capture();
        mode.value = 9;

        controller.restore(snapshot);

        assertEquals(5, mode.value);
    }

    @Test
    void nullPlcArtAdapterRemovesPreviouslyRegisteredOptionalProvider() {
        GameplayModeContext ctx = buildAttachedContext();
        ctx.registerPlcArtAdapter(new SnapObjectArtProvider("s2-plc-art"));
        assertTrue(ctx.getRewindRegistry().capture().entries().containsKey("s2-plc-art"));

        ctx.registerPlcArtAdapter(null);

        assertFalse(ctx.getRewindRegistry().capture().entries().containsKey("s2-plc-art"),
                "A zone without PLC art must not retain the previous zone's PLC rewind adapter");
    }

    @Test
    void registersBonusCoordinatorAdapterOnlyForRewindSupportedStage() {
        GameplayModeContext ctx = buildAttachedContext();

        var slots = new com.openggf.game.sonic3k.Sonic3kBonusStageCoordinator();
        slots.onEnter(com.openggf.game.BonusStageType.SLOT_MACHINE, null);
        ctx.registerBonusStageAdapter(slots);
        assertFalse(ctx.getRewindRegistry().capture().entries().containsKey(
                com.openggf.game.rewind.BonusStageCoordinatorRewindAdapter.KEY),
                "Slots is not rewind-supported; no adapter should register");

        var gumball = new com.openggf.game.sonic3k.Sonic3kBonusStageCoordinator();
        gumball.onEnter(com.openggf.game.BonusStageType.GUMBALL, null);
        ctx.registerBonusStageAdapter(gumball);
        assertTrue(ctx.getRewindRegistry().capture().entries().containsKey(
                com.openggf.game.rewind.BonusStageCoordinatorRewindAdapter.KEY),
                "Gumball is rewind-supported; adapter must register");

        ctx.deregisterBonusStageAdapter();
        assertFalse(ctx.getRewindRegistry().capture().entries().containsKey(
                com.openggf.game.rewind.BonusStageCoordinatorRewindAdapter.KEY));
    }

    @Test
    void nullPatternAnimatorAdapterRemovesPreviouslyRegisteredOptionalAnimator() {
        GameplayModeContext ctx = buildAttachedContext();
        ctx.registerPatternAnimatorAdapter(new SnapPatternAnimator());
        assertTrue(ctx.getRewindRegistry().capture().entries().containsKey("pattern-animator"));

        ctx.registerPatternAnimatorAdapter(null);

        assertFalse(ctx.getRewindRegistry().capture().entries().containsKey("pattern-animator"),
                "A zone without an animated pattern manager must not retain the previous zone's animator adapter");
    }

    private static void attachSharedRegistries(GameplayModeContext ctx) {
        ctx.attachSharedRegistries(
                new ZoneRuntimeRegistry(),
                new PaletteOwnershipRegistry(),
                new AnimatedTileChannelGraph(),
                new SpecialRenderEffectRegistry(),
                new AdvancedRenderModeController(),
                new ZoneLayoutMutationPipeline());
    }

    private static void attachLevelManagers(GameplayModeContext ctx) {
        WaterSystem water = new WaterSystem();
        ParallaxManager parallax = new ParallaxManager();
        TerrainCollisionManager terrain = new TerrainCollisionManager();
        CollisionSystem collision = new CollisionSystem(terrain);
        SpriteManager sprites = new SpriteManager();
        LevelManager level = mock(LevelManager.class);
        ctx.attachLevelManagers(water, parallax, terrain, collision, sprites, level);
    }

    private static AnimatedTileChannel dummyChannel() {
        return new AnimatedTileChannel(
                "dummy",
                () -> true,
                context -> 0,
                DestinationPlan.single(0),
                AnimatedTileCachePolicy.ALWAYS,
                context -> {
                });
    }

    private static final class MutableZoneRuntimeState implements ZoneRuntimeState {
        @Override
        public String gameId() {
            return "s2";
        }

        @Override
        public int zoneIndex() {
            return 0;
        }

        @Override
        public int actIndex() {
            return 0;
        }
    }

    private static final class StatefulEffect
            implements SpecialRenderEffect, RewindSnapshottable<Integer> {
        private int value;

        @Override
        public SpecialRenderEffectStage stage() {
            return SpecialRenderEffectStage.AFTER_BACKGROUND;
        }

        @Override
        public void render(SpecialRenderEffectContext context) {
        }

        @Override
        public String key() {
            return "stateful-effect";
        }

        @Override
        public Integer capture() {
            return value;
        }

        @Override
        public void restore(Integer snapshot) {
            value = snapshot;
        }
    }

    private static final class StatefulMode
            implements AdvancedRenderMode, RewindSnapshottable<Integer> {
        private int value;

        @Override
        public String id() {
            return "stateful-mode";
        }

        @Override
        public void contribute(AdvancedRenderModeContext context, AdvancedRenderFrameState.Builder builder) {
        }

        @Override
        public String key() {
            return "stateful-mode";
        }

        @Override
        public Integer capture() {
            return value;
        }

        @Override
        public void restore(Integer snapshot) {
            value = snapshot;
        }
    }

    private static final class SnapPatternAnimator
            implements AnimatedPatternManager, RewindSnapshottable<Integer> {
        @Override
        public void update() {
        }

        @Override
        public String key() {
            return "pattern-animator";
        }

        @Override
        public Integer capture() {
            return 1;
        }

        @Override
        public void restore(Integer snapshot) {
        }
    }

    private static final class SnapObjectArtProvider
            implements ObjectArtProvider, RewindSnapshottable<Integer> {
        private final String key;

        private SnapObjectArtProvider(String key) {
            this.key = key;
        }

        @Override
        public void loadArtForZone(int zoneIndex) {
        }

        @Override
        public PatternSpriteRenderer getRenderer(String key) {
            return null;
        }

        @Override
        public ObjectSpriteSheet getSheet(String key) {
            return null;
        }

        @Override
        public SpriteAnimationSet getAnimations(String key) {
            return null;
        }

        @Override
        public int getZoneData(String key, int zoneIndex) {
            return -1;
        }

        @Override
        public Pattern[] getHudDigitPatterns() {
            return null;
        }

        @Override
        public Pattern[] getHudTextPatterns() {
            return null;
        }

        @Override
        public Pattern[] getHudLivesPatterns() {
            return null;
        }

        @Override
        public Pattern[] getHudLivesNumbers() {
            return null;
        }

        @Override
        public List<String> getRendererKeys() {
            return List.of();
        }

        @Override
        public int ensurePatternsCached(GraphicsManager graphicsManager, int baseIndex) {
            return baseIndex;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public String key() {
            return key;
        }

        @Override
        public Integer capture() {
            return 1;
        }

        @Override
        public void restore(Integer snapshot) {
        }
    }

    private static final class StubBonusStageProvider implements BonusStageProvider {
        @Override
        public boolean hasBonusStages() {
            return true;
        }

        @Override
        public BonusStageType selectBonusStage(int ringCount) {
            return BonusStageType.SLOT_MACHINE;
        }

        @Override
        public void onEnter(BonusStageType type, BonusStageState savedState) {
        }

        @Override
        public void onExit() {
        }

        @Override
        public void onFrameUpdate() {
        }

        @Override
        public boolean isStageComplete() {
            return false;
        }

        @Override
        public void requestExit() {
        }

        @Override
        public BonusStageRewards getRewards() {
            return BonusStageRewards.none();
        }

        @Override
        public int getZoneId(BonusStageType type) {
            return -1;
        }

        @Override
        public int getMusicId(BonusStageType type) {
            return -1;
        }

        @Override
        public BonusStageState getSavedState() {
            return null;
        }
    }
}
