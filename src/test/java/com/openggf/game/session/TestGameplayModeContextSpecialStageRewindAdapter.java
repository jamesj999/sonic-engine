package com.openggf.game.session;

import com.openggf.camera.Camera;
import com.openggf.game.GameRng;
import com.openggf.game.GameStateManager;
import com.openggf.game.NoOpSpecialStageProvider;
import com.openggf.game.SpecialStageProvider;
import com.openggf.game.rewind.CompositeSnapshot;
import com.openggf.game.rewind.RewindSnapshottable;
import com.openggf.game.solid.DefaultSolidExecutionRegistry;
import com.openggf.game.sonic1.specialstage.Sonic1SpecialStageProvider;
import com.openggf.game.sonic2.Sonic2GameModule;
import com.openggf.game.sonic2.Sonic2SpecialStageProvider;
import com.openggf.game.sonic3k.specialstage.Sonic3kSpecialStageProvider;
import com.openggf.graphics.FadeManager;
import com.openggf.timer.TimerManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import com.openggf.tests.TestEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestGameplayModeContextSpecialStageRewindAdapter {

    @BeforeEach
    void configureServices() {
        TestEnvironment.resetAll();
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
    }

    @AfterEach
    void resetServices() {
        TestEnvironment.resetAll();
    }

    @Test
    void registersProviderOwnedSpecialStageRuntimeOnlyWhenProviderSupportsRewind() {
        GameplayModeContext context = buildAttachedContext();
        Sonic1SpecialStageProvider provider = new Sonic1SpecialStageProvider();

        context.registerSpecialStageAdapter(provider);

        CompositeSnapshot snapshot = context.getRewindRegistry().capture();
        assertTrue(snapshot.containsKey(SpecialStageProvider.SPECIAL_STAGE_REWIND_KEY),
                "Sonic 1 should register its provider-owned special-stage rewind runtime");

        context.registerSpecialStageAdapter(NoOpSpecialStageProvider.INSTANCE);
        snapshot = context.getRewindRegistry().capture();
        assertFalse(snapshot.containsKey(SpecialStageProvider.SPECIAL_STAGE_REWIND_KEY),
                "A non-rewindable provider must not retain a stale special-stage runtime adapter");

        context.registerSpecialStageAdapter(provider);
        assertTrue(context.getRewindRegistry().capture()
                .containsKey(SpecialStageProvider.SPECIAL_STAGE_REWIND_KEY));

        context.deregisterSpecialStageAdapter();
        snapshot = context.getRewindRegistry().capture();
        assertFalse(snapshot.containsKey(SpecialStageProvider.SPECIAL_STAGE_REWIND_KEY));
    }

    @Test
    void sonic1AdapterUsesGenericKeyAndKeepsThrowingMissingSnapshotDefault() {
        RewindSnapshottable<?> adapter = new Sonic1SpecialStageProvider()
                .rewindAdapter()
                .orElseThrow();

        assertEquals(SpecialStageProvider.SPECIAL_STAGE_REWIND_KEY, adapter.key());
        assertThrows(IllegalStateException.class, adapter::resetForMissingSnapshot);

        RewindSnapshottable<?> sonic2Adapter = new Sonic2SpecialStageProvider()
                .rewindAdapter()
                .orElseThrow();
        assertEquals(SpecialStageProvider.SPECIAL_STAGE_REWIND_KEY, sonic2Adapter.key());
        assertThrows(IllegalStateException.class, sonic2Adapter::resetForMissingSnapshot);
    }

    @Test
    void sonic3kAdapterUsesGenericKeyWithoutCapturingUninitializedManager() {
        RewindSnapshottable<?> adapter = new Sonic3kSpecialStageProvider()
                .rewindAdapter()
                .orElseThrow();

        assertEquals(SpecialStageProvider.SPECIAL_STAGE_REWIND_KEY, adapter.key());
        assertThrows(IllegalStateException.class, adapter::resetForMissingSnapshot);
    }

    @Test
    void registersSonic2ProviderOwnedSpecialStageRuntimeUnderGenericKey() {
        GameplayModeContext context = buildAttachedContext();
        Sonic2SpecialStageProvider provider = new Sonic2SpecialStageProvider();

        context.registerSpecialStageAdapter(provider);

        CompositeSnapshot snapshot = context.getRewindRegistry().capture();
        assertTrue(snapshot.containsKey(SpecialStageProvider.SPECIAL_STAGE_REWIND_KEY),
                "Sonic 2 should register its provider-owned special-stage rewind runtime");

        context.deregisterSpecialStageAdapter();
        assertFalse(context.getRewindRegistry().capture()
                .containsKey(SpecialStageProvider.SPECIAL_STAGE_REWIND_KEY));
    }

    private static GameplayModeContext buildAttachedContext() {
        WorldSession world = new WorldSession(new Sonic2GameModule());
        GameplayModeContext context = new GameplayModeContext(world);

        context.attachGameplayManagers(
                new Camera(),
                new TimerManager(),
                new GameStateManager(),
                new FadeManager(),
                new GameRng(GameRng.Flavour.S1_S2),
                new DefaultSolidExecutionRegistry());
        return context;
    }
}
