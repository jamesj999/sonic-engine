package com.openggf.game.session;

import com.openggf.game.GameModuleRegistry;
import com.openggf.game.sonic2.Sonic2GameModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TestEditorModeContextLifecycle {

    @AfterEach
    void tearDown() {
        SessionManager.clear();
        GameModuleRegistry.reset();
    }

    @Test
    void resumeGameplayFromEditor_usesCursorAsSpawnAndPreservesResumeStash() {
        SessionManager.openGameplaySession(new Sonic2GameModule());
        EditorPlaytestStash stash = new EditorPlaytestStash(
                100, 200, 0x0400, -0x0080, true, 53, 2);
        EditorModeContext editor = SessionManager.enterEditorMode(new EditorCursorState(320, 640), stash);

        GameplayModeContext gameplay = SessionManager.resumeGameplayFromEditor();

        assertNotNull(gameplay);
        assertSame(editor.getWorldSession(), gameplay.getWorldSession());
        assertEquals(320, gameplay.getSpawnX());
        assertEquals(640, gameplay.getSpawnY());
        assertTrue(gameplay.hasResumeStash());
        assertSame(stash, gameplay.getResumeStash().orElseThrow());
        assertEquals(100, gameplay.getResumeStash().orElseThrow().playerX());
        assertEquals(200, gameplay.getResumeStash().orElseThrow().playerY());
    }

    @Test
    void restartGameplayFromBeginning_discardsStashAndResetsSpawnToOrigin() {
        SessionManager.openGameplaySession(new Sonic2GameModule());
        EditorPlaytestStash stash = new EditorPlaytestStash(
                500, 900, 0x0180, 0x0020, false, 7, 3);
        EditorModeContext editor = SessionManager.enterEditorMode(new EditorCursorState(320, 640), stash);

        GameplayModeContext gameplay = SessionManager.restartGameplayFromBeginning();

        assertNotNull(gameplay);
        assertSame(editor.getWorldSession(), gameplay.getWorldSession());
        assertEquals(0, gameplay.getSpawnX());
        assertEquals(0, gameplay.getSpawnY());
        assertFalse(gameplay.hasResumeStash());
        assertTrue(gameplay.getResumeStash().isEmpty());
    }

    @Test
    void exitEditorMode_remainsCompatibleWithResumeGameplaySemantics() {
        SessionManager.openGameplaySession(new Sonic2GameModule());
        EditorPlaytestStash stash = new EditorPlaytestStash(
                11, 22, 0x0030, 0x0040, true, 5, 0);
        SessionManager.enterEditorMode(new EditorCursorState(77, 88), stash);

        GameplayModeContext gameplay = SessionManager.exitEditorMode();

        assertEquals(77, gameplay.getSpawnX());
        assertEquals(88, gameplay.getSpawnY());
        assertTrue(gameplay.hasResumeStash());
        assertSame(stash, gameplay.getResumeStash().orElseThrow());
    }

    @Test
    void enterEditorMode_preservesWorldAndOwnsEditorManagers() {
        GameplayModeContext gameplay = SessionManager.openGameplaySession(new Sonic2GameModule());
        WorldSession world = gameplay.getWorldSession();

        EditorModeContext editor = SessionManager.enterEditorMode(new EditorCursorState(77, 88));

        assertSame(world, editor.getWorldSession());
        assertNotNull(editor.getCamera());
        assertNotNull(editor.getSpriteManager());
        assertNotNull(editor.getLevelManager());
    }

    @Test
    void editorModeDestroyResetsEveryEditorOwnedManager() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/openggf/game/session/EditorModeContext.java"));
        String destroy = methodBody(source, "public void destroy()");

        for (String resetCall : new String[] {
                "levelManager.resetGameplayState()",
                "spriteManager.resetState()",
                "collisionSystem.resetState()",
                "terrainCollisionManager.resetState()",
                "parallaxManager.resetState()",
                "waterSystem.reset()",
                "gameStateManager.resetState()",
                "camera.resetState()"
        }) {
            assertTrue(destroy.contains(resetCall), "EditorModeContext.destroy must call " + resetCall);
        }
    }

    @Test
    void editorModeContext_retainsCursorAndPlaytestStash() {
        WorldSession world = new WorldSession(new Sonic2GameModule());
        EditorPlaytestStash stash = new EditorPlaytestStash(
                100, 200, 0x0400, -0x0080, true, 53, 2);

        EditorModeContext editor = new EditorModeContext(world, new EditorCursorState(320, 640), stash);

        assertSame(world, editor.getWorldSession());
        assertEquals(320, editor.getCursor().x());
        assertEquals(640, editor.getCursor().y());
        assertTrue(editor.hasPlaytestStash());
        assertNotNull(editor.getPlaytestStash());
        assertEquals(100, editor.getPlaytestStash().playerX());
        assertEquals(200, editor.getPlaytestStash().playerY());
        assertEquals(0x0400, editor.getPlaytestStash().xVelocity());
        assertEquals(-0x0080, editor.getPlaytestStash().yVelocity());
        assertEquals(53, editor.getPlaytestStash().rings());
        assertEquals(2, editor.getPlaytestStash().shieldState());
    }

    @Test
    void editorModeContext_cursorCanBeUpdatedThroughSessionResumeFlow() {
        SessionManager.openGameplaySession(new Sonic2GameModule());
        EditorPlaytestStash stash = new EditorPlaytestStash(
                100, 200, 0x0400, -0x0080, true, 53, 2);
        EditorModeContext editor = SessionManager.enterEditorMode(new EditorCursorState(320, 640), stash);

        editor.setCursor(new EditorCursorState(400, 768));

        GameplayModeContext gameplay = SessionManager.resumeGameplayFromEditor();

        assertNotNull(gameplay);
        assertSame(editor.getWorldSession(), gameplay.getWorldSession());
        assertEquals(400, gameplay.getSpawnX());
        assertEquals(768, gameplay.getSpawnY());
        assertTrue(gameplay.hasResumeStash());
        assertSame(stash, gameplay.getResumeStash().orElseThrow());
    }

    @Test
    void editorModeContext_rejectsNullCursorUpdates() {
        WorldSession world = new WorldSession(new Sonic2GameModule());
        EditorModeContext editor = new EditorModeContext(world, new EditorCursorState(320, 640), null);

        assertThrows(NullPointerException.class, () -> editor.setCursor(null));
    }

    @Test
    void legacyEditorModeContextConstructorLeavesPlaytestStashEmpty() {
        WorldSession world = new WorldSession(new Sonic2GameModule());

        EditorModeContext editor = new EditorModeContext(world, new EditorCursorState(12, 34));

        assertSame(world, editor.getWorldSession());
        assertEquals(12, editor.getCursor().x());
        assertEquals(34, editor.getCursor().y());
        assertFalse(editor.hasPlaytestStash());
        assertNull(editor.getPlaytestStash());
    }

    private static String methodBody(String text, String signature) {
        int start = text.indexOf(signature);
        assertTrue(start >= 0, "Missing method signature: " + signature);
        int brace = text.indexOf('{', start);
        assertTrue(brace >= 0, "Missing method body: " + signature);
        int depth = 0;
        for (int i = brace; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(brace + 1, i);
                }
            }
        }
        throw new AssertionError("Unclosed method body: " + signature);
    }
}


