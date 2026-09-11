package com.openggf.game.session;

import com.openggf.game.GameMode;
import com.openggf.game.GameModule;
import com.openggf.game.GameModuleRegistry;
import com.openggf.game.session.EngineContext;
import com.openggf.game.save.SaveSessionContext;
import com.openggf.game.save.SelectedTeam;
import com.openggf.game.sonic1.Sonic1GameModule;
import com.openggf.game.sonic2.Sonic2GameModule;
import com.openggf.game.timing.HardwareReadinessAdmissionPolicy;
import com.openggf.camera.Camera;
import com.openggf.graphics.FadeManager;
import com.openggf.graphics.GraphicsManager;
import com.openggf.tests.TestEnvironment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TestSessionManager {

    @BeforeEach
    void configureServices() {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
    }

    @AfterEach
    void tearDown() {
        SessionManager.clear();
        GameModuleRegistry.reset();
    }

    @Test
    void openGameplaySession_setsCurrentWorldAndGameplayMode() {
        GameModule module = new Sonic2GameModule();
        GameplayModeContext gameplay = SessionManager.openGameplaySession(module);

        assertNotNull(gameplay);
        assertNotNull(SessionManager.getCurrentWorldSession());
        assertSame(gameplay, SessionManager.getCurrentGameplayMode());
        assertEquals(GameMode.LEVEL, gameplay.getGameMode());
        assertSame(module, SessionManager.getCurrentWorldSession().getGameModule());
    }

    @Test
    void openEditorStub_replacesModeButPreservesWorld() {
        GameplayModeContext gameplay = SessionManager.openGameplaySession(new Sonic2GameModule());
        WorldSession world = SessionManager.getCurrentWorldSession();

        EditorModeContext editor = SessionManager.enterEditorMode(
                new EditorCursorState(128, 256));

        assertNotNull(editor);
        assertSame(world, SessionManager.getCurrentWorldSession());
        assertSame(editor, SessionManager.getCurrentEditorMode());
        assertNull(SessionManager.getCurrentGameplayMode());
        assertNotSame(gameplay, editor);
    }

    @Test
    void enterEditorMode_withStash_retainsCursorAndStash() {
        SessionManager.openGameplaySession(new Sonic2GameModule());
        WorldSession world = SessionManager.getCurrentWorldSession();
        EditorPlaytestStash stash = new EditorPlaytestStash(
                48, 96, 0x0200, -0x0040, false, 12, 1);

        EditorModeContext editor = SessionManager.enterEditorMode(new EditorCursorState(128, 256), stash);

        assertNotNull(editor);
        assertSame(world, editor.getWorldSession());
        assertEquals(128, editor.getCursor().x());
        assertEquals(256, editor.getCursor().y());
        assertTrue(editor.hasPlaytestStash());
        assertSame(stash, editor.getPlaytestStash());
    }

    @Test
    void openGameplaySession_replacesExistingEditorModeWithFreshWorld() {
        SessionManager.openGameplaySession(new Sonic2GameModule());
        EditorModeContext editor = SessionManager.enterEditorMode(new EditorCursorState(128, 256));

        GameplayModeContext gameplay = SessionManager.openGameplaySession(new Sonic2GameModule());

        assertNotNull(gameplay);
        assertSame(gameplay, SessionManager.getCurrentGameplayMode());
        assertNull(SessionManager.getCurrentEditorMode());
        assertNotSame(editor.getWorldSession(), SessionManager.getCurrentWorldSession());
    }

    @Test
    void enterEditorMode_withoutWorldSessionThrows() {
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> SessionManager.enterEditorMode(new EditorCursorState(1, 2)));

        assertEquals("Cannot enter editor mode without an active world session.", exception.getMessage());
    }

    @Test
    void requireCurrentGameModule_returnsWorldOwnedModule() {
        GameModule module = new Sonic2GameModule();
        SessionManager.openGameplaySession(module);

        assertSame(module, SessionManager.requireCurrentGameModule());
    }

    @Test
    void requireCurrentGameModule_withoutWorldSessionThrows() {
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                SessionManager::requireCurrentGameModule);

        assertEquals("No active WorldSession", exception.getMessage());
    }

    @Test
    void activeGameplayModeReturnsCurrentGameplayContext() {
        SessionManager.openGameplaySession(new Sonic2GameModule());
        GameplayModeContext gameplay = TestEnvironment.activeGameplayMode();

        assertNotNull(gameplay);
        assertNotNull(gameplay.getWorldSession());
        assertSame(SessionManager.getCurrentWorldSession(),
                gameplay.getWorldSession());
    }

    @Test
    void gameplayModeReturnsNullAfterSessionClear() {
        SessionManager.openGameplaySession(new Sonic2GameModule());
        assertNotNull(TestEnvironment.activeGameplayMode());

        SessionManager.clear();

        assertNull(SessionManager.getCurrentGameplayMode());
    }

    @Test
    void gameplayModeReturnsNullAfterEnteringEditorMode() {
        SessionManager.openGameplaySession(new Sonic2GameModule());
        assertNotNull(TestEnvironment.activeGameplayMode());

        SessionManager.enterEditorMode(new EditorCursorState(128, 256));

        assertNull(SessionManager.getCurrentGameplayMode());
    }

    @Test
    void activeGameplayModePreservesBootstrapModule() {
        Sonic1GameModule module = new Sonic1GameModule();
        GameModuleRegistry.setCurrent(module);

        GameplayModeContext gameplay = TestEnvironment.activeGameplayMode();

        assertNotNull(gameplay);
        assertSame(module, gameplay.getWorldSession().getGameModule());
        assertSame(module, SessionManager.requireCurrentGameModule());
    }

    @Test
    void gameModuleRegistry_setCurrentDoesNotReplaceActiveSessionModule() {
        GameModule sessionModule = new Sonic2GameModule();
        SessionManager.openGameplaySession(sessionModule);

        GameModuleRegistry.setCurrent(new Sonic1GameModule());

        assertSame(sessionModule, GameModuleRegistry.getCurrent());
    }

    @Test
    void openGameplaySession_rejectsNullModule() {
        assertThrows(NullPointerException.class, () -> SessionManager.openGameplaySession(null));
    }

    @Test
    void enterEditorMode_rejectsNullCursor() {
        SessionManager.openGameplaySession(new Sonic2GameModule());

        assertThrows(NullPointerException.class, () -> SessionManager.enterEditorMode(null));
    }

    @Test
    void gameplaySession_preservesSaveSessionContext() {
        GameModule module = new Sonic2GameModule();
        SaveSessionContext ctx = SaveSessionContext.forSlot("s3k", 1,
                new SelectedTeam("sonic", java.util.List.of("tails")), 0, 0);
        GameplayModeContext gameplay = SessionManager.openGameplaySession(module, ctx);
        assertEquals(1, gameplay.getWorldSession().getSaveSessionContext().activeSlot().orElseThrow());
    }

    @Test
    void closeGameplaySessionClearsWorldAndRuntimeGraphicsReferences() {
        SessionManager.openGameplaySession(new Sonic2GameModule());
        GraphicsManager graphics = EngineServices.current().graphics();
        FadeManager bootstrapFade = graphics.getFadeManager();
        FadeManager runtimeFade = new FadeManager();
        graphics.bindRuntimeManagedReferences(new Camera(EngineServices.current().configuration()), runtimeFade);

        SessionManager.closeGameplaySession();

        assertNull(SessionManager.getCurrentGameplayMode());
        assertNull(SessionManager.getCurrentEditorMode());
        assertNull(SessionManager.getCurrentWorldSession());
        assertSame(bootstrapFade, graphics.getFadeManager());
    }

    @ParameterizedTest
    @ValueSource(strings = {"clear", "close", "replace", "reopen", "editor"})
    void failedReplayCloseUnpublishesDisposedSessionAndAllowsFreshStart(String operation) {
        GameplayModeContext gameplay = SessionManager.openGameplaySession(new Sonic2GameModule());
        GraphicsManager graphics = EngineServices.current().graphics();
        FadeManager bootstrapFade = graphics.getFadeManager();
        graphics.bindRuntimeManagedReferences(
                new Camera(EngineServices.current().configuration()), new FadeManager());
        IllegalStateException failure = new IllegalStateException("Incomplete replay timing run");
        AtomicInteger closeCalls = new AtomicInteger();
        gameplay.setHardwareTimingReplayCloseHook(() -> {
            closeCalls.incrementAndGet();
            throw failure;
        });
        SessionManager.armNextGameplayAdmissionPolicy(HardwareReadinessAdmissionPolicy.RECORDED);

        assertSame(failure, assertThrows(IllegalStateException.class, () -> {
            switch (operation) {
                case "clear" -> SessionManager.clear();
                case "close" -> SessionManager.closeGameplaySession();
                case "replace" -> SessionManager.openGameplaySession(new Sonic2GameModule());
                case "reopen" -> SessionManager.reopenGameplaySession(HardwareReadinessAdmissionPolicy.RECORDED);
                case "editor" -> SessionManager.enterEditorMode(new EditorCursorState(128, 256));
                default -> fail("Unexpected operation: " + operation);
            }
        }));

        assertNull(SessionManager.getCurrentGameplayMode());
        assertNull(SessionManager.getCurrentEditorMode());
        assertNull(SessionManager.getCurrentWorldSession());
        assertSame(bootstrapFade, graphics.getFadeManager());
        assertEquals(1, closeCalls.get());
        GameplayModeContext fresh = SessionManager.openGameplaySession(new Sonic2GameModule());
        assertNotSame(gameplay, fresh);
        assertEquals(HardwareReadinessAdmissionPolicy.LIVE, fresh.hardwareTiming().admissionPolicy());
        assertDoesNotThrow(SessionManager::clear);
        assertDoesNotThrow(SessionManager::clear);
        assertEquals(1, closeCalls.get());
    }

    @Test
    void openGameplaySessionAcceptsAdmissionPolicyBeforeContextConstruction() {
        GameplayModeContext gameplay = SessionManager.openGameplaySession(
                new Sonic2GameModule(), HardwareReadinessAdmissionPolicy.RECORDED);

        assertEquals(HardwareReadinessAdmissionPolicy.RECORDED,
                gameplay.hardwareTiming().admissionPolicy());
        assertNotNull(gameplay.recordedCompletionAuthority());
    }
}
