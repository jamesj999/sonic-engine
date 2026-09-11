package com.openggf.game.render;

import com.openggf.camera.Camera;
import com.openggf.game.GameServices;
import com.openggf.game.session.GameplayModeContext;
import com.openggf.game.session.SessionManager;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.LevelManager;
import com.openggf.tests.TestEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TestSpecialRenderEffectRegistry {

    @BeforeEach
    void setUp() {
        TestEnvironment.resetAll();
    }

    @AfterEach
    void tearDown() {
        SessionManager.clear();
    }

    @Test
    void registryDispatchesEffectsInRegistrationOrderWithinStage() {
        TestEnvironment.activeGameplayMode();
        SpecialRenderEffectRegistry registry = GameServices.specialRenderEffectRegistry();
        List<String> calls = new ArrayList<>();
        List<Integer> frameCounters = new ArrayList<>();

        registry.register(new RecordingEffect("bg-1", SpecialRenderEffectStage.AFTER_BACKGROUND, calls, frameCounters));
        registry.register(new RecordingEffect("bg-2", SpecialRenderEffectStage.AFTER_BACKGROUND, calls, frameCounters));
        registry.register(new RecordingEffect("fg-1", SpecialRenderEffectStage.AFTER_FOREGROUND, calls, frameCounters));

        SpecialRenderEffectContext context = contextForCurrent();
        registry.dispatch(SpecialRenderEffectStage.AFTER_BACKGROUND, context);
        registry.dispatch(SpecialRenderEffectStage.AFTER_FOREGROUND, context);

        assertEquals(List.of("bg-1", "bg-2", "fg-1"), calls);
        assertEquals(List.of(42, 42, 42), frameCounters);
    }

    @Test
    void clearRemovesRegisteredEffects() {
        TestEnvironment.activeGameplayMode();
        SpecialRenderEffectRegistry registry = GameServices.specialRenderEffectRegistry();
        List<String> calls = new ArrayList<>();

        registry.register(new RecordingEffect("bg", SpecialRenderEffectStage.AFTER_BACKGROUND, calls, new ArrayList<>()));
        assertFalse(registry.isEmpty());

        registry.clear();
        registry.dispatch(SpecialRenderEffectStage.AFTER_BACKGROUND, contextForCurrent());

        assertTrue(registry.isEmpty());
        assertTrue(calls.isEmpty());
    }

    @Test
    void runtimeExposesAndClearsRegistryThroughServices() {
        GameplayModeContext gameplayMode = TestEnvironment.activeGameplayMode();

        assertSame(gameplayMode.getSpecialRenderEffectRegistry(), GameServices.specialRenderEffectRegistry());
        assertSame(gameplayMode.getSpecialRenderEffectRegistry(), GameServices.specialRenderEffectRegistryOrNull());

        SpecialRenderEffectRegistry registry = gameplayMode.getSpecialRenderEffectRegistry();
        registry.register(new RecordingEffect(
                "bg",
                SpecialRenderEffectStage.AFTER_BACKGROUND,
                new ArrayList<>(),
                new ArrayList<>()));
        assertFalse(registry.isEmpty());

        SessionManager.clear();
        // Post-migration: GameServices accessors throw only when the gameplay
        // mode is gone. The destroyed context detaches cleared managers so stale
        // runtime state is not exposed through direct getters either.
        assertNull(GameServices.specialRenderEffectRegistryOrNull());
        assertThrows(IllegalStateException.class, GameServices::specialRenderEffectRegistry);
        assertTrue(registry.isEmpty());
        assertNull(gameplayMode.getSpecialRenderEffectRegistry());
    }

    @Test
    void contextCarriesFrameLocalRenderState() {
        TestEnvironment.activeGameplayMode();
        SpecialRenderEffectContext context = contextForCurrent();

        assertSame(GameServices.camera(), context.camera());
        assertSame(GameServices.level(), context.levelManager());
        assertSame(GameServices.graphics(), context.graphicsManager());
        assertEquals(42, context.frameCounter());
    }

    private static SpecialRenderEffectContext contextForCurrent() {
        Camera camera = GameServices.camera();
        LevelManager levelManager = GameServices.level();
        GraphicsManager graphicsManager = GameServices.graphics();
        return new SpecialRenderEffectContext(camera, 42, levelManager, graphicsManager);
    }

    private static final class RecordingEffect implements SpecialRenderEffect {
        private final String name;
        private final SpecialRenderEffectStage stage;
        private final List<String> calls;
        private final List<Integer> frameCounters;

        private RecordingEffect(
                String name,
                SpecialRenderEffectStage stage,
                List<String> calls,
                List<Integer> frameCounters) {
            this.name = name;
            this.stage = stage;
            this.calls = calls;
            this.frameCounters = frameCounters;
        }

        @Override
        public SpecialRenderEffectStage stage() {
            return stage;
        }

        @Override
        public String debugName() {
            return name;
        }

        @Override
        public void render(SpecialRenderEffectContext context) {
            calls.add(name);
            frameCounters.add(context.frameCounter());
        }
    }
}
