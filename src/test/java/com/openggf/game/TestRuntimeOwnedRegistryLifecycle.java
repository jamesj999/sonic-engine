package com.openggf.game;

import com.openggf.game.animation.AnimatedTileCachePolicy;
import com.openggf.game.animation.AnimatedTileChannel;
import com.openggf.game.animation.DestinationPlan;
import com.openggf.game.mutation.MutationEffects;
import com.openggf.game.render.SpecialRenderEffect;
import com.openggf.game.render.SpecialRenderEffectContext;
import com.openggf.game.render.SpecialRenderEffectStage;
import com.openggf.game.session.GameplayModeContext;
import com.openggf.game.session.SessionManager;
import com.openggf.game.zone.NoOpZoneRuntimeState;
import com.openggf.game.zone.ZoneRuntimeState;
import com.openggf.tests.TestEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestRuntimeOwnedRegistryLifecycle {

    @BeforeEach
    void setUp() {
        TestEnvironment.resetAll();
    }

    @AfterEach
    void tearDown() {
        SessionManager.clear();
    }

    /**
     * Editor entry/exit replaces parking. The teardown+rebuild contract is:
     * destroyCurrent clears all gameplay-scoped state (including queued
     * mutations + registered render effects + render-mode contributions);
     * a fresh runtime built on resume starts with empty registries.
     * <p>
     * This is a stricter contract than the old parking flow, which preserved
     * registered render effects across the editor detour. The test pins the
     * new behavior so future refactors can't quietly regress it.
     */
    @Test
    void editorRoundTripDestroyAndRebuildClearsAllRegistryState() {
        GameplayModeContext first = TestEnvironment.activeGameplayMode();
        first.getZoneRuntimeRegistry().install(new TestZoneRuntimeState());
        first.getPaletteOwnershipRegistry().setPaletteRotationDisabled(true);
        first.getAnimatedTileChannelGraph().install(java.util.List.of(noOpAnimatedTileChannel()));
        first.getZoneLayoutMutationPipeline().queue(context -> MutationEffects.redrawAllTilemaps());
        first.getSpecialRenderEffectRegistry().register(noOpEffect());
        first.getAdvancedRenderModeController().register(noOpMode());
        var oldZoneRuntimeRegistry = first.getZoneRuntimeRegistry();
        var oldPaletteOwnershipRegistry = first.getPaletteOwnershipRegistry();
        var oldAnimatedTileChannelGraph = first.getAnimatedTileChannelGraph();
        var oldZoneLayoutMutationPipeline = first.getZoneLayoutMutationPipeline();
        var oldSpecialRenderEffectRegistry = first.getSpecialRenderEffectRegistry();
        var oldAdvancedRenderModeController = first.getAdvancedRenderModeController();

        assertNotSame(NoOpZoneRuntimeState.INSTANCE, first.getZoneRuntimeRegistry().current(),
                "precondition: zone runtime state should be live before editor entry");
        assertTrue(first.getPaletteOwnershipRegistry().isPaletteRotationDisabled(),
                "precondition: palette ownership state should be live before editor entry");
        assertFalse(first.getAnimatedTileChannelGraph().channels().isEmpty(),
                "precondition: animated tile channel should be live before editor entry");
        assertFalse(first.getZoneLayoutMutationPipeline().isEmpty(),
                "precondition: queued mutation should be live before editor entry");
        assertFalse(first.getSpecialRenderEffectRegistry().isEmpty(),
                "precondition: registered effect should be live before editor entry");
        assertFalse(first.getAdvancedRenderModeController().isEmpty(),
                "precondition: registered render mode should be live before editor entry");

        // Mirror Engine.enterEditorFromCurrentPlayer's teardown contract:
        // SessionManager.enterEditorMode preserves WorldSession but does NOT
        // re-publish gameplay-scoped registry state.
        SessionManager.enterEditorMode(new com.openggf.game.session.EditorCursorState(0, 0));

        // Old pipeline + registries belong to the destroyed gameplay mode.
        // tearDownManagers clears those objects, then detaches them from the
        // stale context so parked runtime code cannot keep using them.
        assertSame(NoOpZoneRuntimeState.INSTANCE, oldZoneRuntimeRegistry.current(),
                "destroyCurrent should clear zone runtime state");
        assertFalse(oldPaletteOwnershipRegistry.isPaletteRotationDisabled(),
                "destroyCurrent should clear palette ownership state");
        assertTrue(oldAnimatedTileChannelGraph.channels().isEmpty(),
                "destroyCurrent should clear animated tile channels");
        assertTrue(oldZoneLayoutMutationPipeline.isEmpty(),
                "destroyCurrent should clear queued mutations");
        assertTrue(oldSpecialRenderEffectRegistry.isEmpty(),
                "destroyCurrent should clear special render effects");
        assertTrue(oldAdvancedRenderModeController.isEmpty(),
                "destroyCurrent should clear advanced render-mode contributions");
        assertNull(first.getZoneRuntimeRegistry(),
                "destroyed gameplay context should detach zone runtime registry");
        assertNull(first.getPaletteOwnershipRegistry(),
                "destroyed gameplay context should detach palette ownership registry");
        assertNull(first.getAnimatedTileChannelGraph(),
                "destroyed gameplay context should detach animated tile graph");
        assertNull(first.getZoneLayoutMutationPipeline(),
                "destroyed gameplay context should detach mutation pipeline");
        assertNull(first.getSpecialRenderEffectRegistry(),
                "destroyed gameplay context should detach special render registry");
        assertNull(first.getAdvancedRenderModeController(),
                "destroyed gameplay context should detach advanced render-mode controller");

        // Mirror Engine.resumePlaytestFromEditor: build a fresh runtime over
        // the surviving WorldSession.
        GameplayModeContext resumed = SessionManager.resumeGameplayFromEditor();
        GameplayModeContext second = TestEnvironment.activeGameplayMode();

        assertNotSame(oldZoneRuntimeRegistry, second.getZoneRuntimeRegistry(),
                "rebuild must produce a fresh ZoneRuntimeRegistry");
        assertNotSame(oldPaletteOwnershipRegistry, second.getPaletteOwnershipRegistry(),
                "rebuild must produce a fresh PaletteOwnershipRegistry");
        assertNotSame(oldAnimatedTileChannelGraph, second.getAnimatedTileChannelGraph(),
                "rebuild must produce a fresh AnimatedTileChannelGraph");
        assertNotSame(oldZoneLayoutMutationPipeline, second.getZoneLayoutMutationPipeline(),
                "rebuild must produce a fresh ZoneLayoutMutationPipeline");
        assertNotSame(oldSpecialRenderEffectRegistry, second.getSpecialRenderEffectRegistry(),
                "rebuild must produce a fresh SpecialRenderEffectRegistry");
        assertNotSame(oldAdvancedRenderModeController, second.getAdvancedRenderModeController(),
                "rebuild must produce a fresh AdvancedRenderModeController");

        assertSame(NoOpZoneRuntimeState.INSTANCE, second.getZoneRuntimeRegistry().current(),
                "rebuilt zone runtime registry must start empty");
        assertFalse(second.getPaletteOwnershipRegistry().isPaletteRotationDisabled(),
                "rebuilt palette ownership registry must start empty");
        assertTrue(second.getAnimatedTileChannelGraph().channels().isEmpty(),
                "rebuilt animated tile graph must start empty");
        assertTrue(second.getZoneLayoutMutationPipeline().isEmpty(),
                "rebuilt pipeline must start empty");
        assertTrue(second.getSpecialRenderEffectRegistry().isEmpty(),
                "rebuilt special render registry must start empty");
        assertTrue(second.getAdvancedRenderModeController().isEmpty(),
                "rebuilt advanced render-mode controller must start empty");
    }

    private static SpecialRenderEffect noOpEffect() {
        return new SpecialRenderEffect() {
            @Override
            public SpecialRenderEffectStage stage() {
                return SpecialRenderEffectStage.AFTER_BACKGROUND;
            }

            @Override
            public void render(SpecialRenderEffectContext context) {
                // no-op
            }
        };
    }

    private static com.openggf.game.render.AdvancedRenderMode noOpMode() {
        return new com.openggf.game.render.AdvancedRenderMode() {
            @Override
            public String id() {
                return "test-mode";
            }

            @Override
            public void contribute(
                    com.openggf.game.render.AdvancedRenderModeContext context,
                    com.openggf.game.render.AdvancedRenderFrameState.Builder builder) {
                builder.enablePerLineForegroundScroll();
            }
        };
    }

    private static AnimatedTileChannel noOpAnimatedTileChannel() {
        return new AnimatedTileChannel(
                "test-channel",
                () -> true,
                context -> 0,
                DestinationPlan.single(0),
                AnimatedTileCachePolicy.ALWAYS,
                context -> {
                });
    }

    private static final class TestZoneRuntimeState implements ZoneRuntimeState {
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
}
