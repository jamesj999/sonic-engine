package com.openggf.game;

import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.launch.LaunchProfileStore;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static com.openggf.game.TestMasterTitleHub.press;

class TestMasterTitleCarousel {
    private MasterTitleScreen screen(List<MasterTitleEntry> entries) {
        var config = SonicConfigurationService.createStandalone();
        var result = new MasterTitleScreen(config, new LaunchProfileStore(config), entries);
        result.setStateForTest(MasterTitleScreen.State.ACTIVE);
        return result;
    }

    private List<MasterTitleEntry> games(int count) {
        var entries = new ArrayList<MasterTitleEntry>();
        for (int i = 0; i < count; i++) entries.add(new MasterTitleEntry.Standalone("game-" + i,
                "Game " + i + " with a long descriptive title", false));
        return entries;
    }

    @Test void bothPanesWrapThroughArbitraryCatalogSizes() {
        for (int count : new int[]{1, 2, 3, 4, 37, 100}) {
            var screen = screen(games(count));
            press(screen, GLFW_KEY_LEFT);
            assertEquals("game-" + (count - 1), screen.getSelectedGameId());
            press(screen, GLFW_KEY_DOWN);
            press(screen, GLFW_KEY_RIGHT);
            assertEquals("game-0", screen.getSelectedGameId());
            for (int i = 0; i < count; i++) press(screen, GLFW_KEY_RIGHT);
            assertEquals("game-0", screen.getSelectedGameId());
        }
    }

    @Test void reorderedCatalogKeepsGameIdentityAndRemovalSelectsANeighbor() {
        var entries = games(5);
        var screen = screen(entries);
        screen.setSelectedIndexForTest(3);
        screen.replaceEntries(List.of(entries.get(3), entries.get(1), entries.get(4)));
        assertEquals("game-3", screen.getSelectedGameId());
        screen.replaceEntries(List.of(entries.get(1), entries.get(4)));
        assertEquals("game-1", screen.getSelectedGameId());
        press(screen, GLFW_KEY_LEFT);
        assertEquals("game-4", screen.getSelectedGameId());
    }

    @Test void emptyCatalogRetainsSystemActionsAndCanBeRepopulated() {
        var screen = screen(List.of());
        assertNull(screen.getSelectedGameId());
        press(screen, GLFW_KEY_LEFT);
        press(screen, GLFW_KEY_RIGHT);
        press(screen, GLFW_KEY_DOWN);
        press(screen, GLFW_KEY_ENTER);
        assertFalse(screen.isGameSelected());
        press(screen, GLFW_KEY_ESCAPE);
        for (int i = 0; i < 7; i++) press(screen, GLFW_KEY_DOWN);
        press(screen, GLFW_KEY_ENTER);
        press(screen, GLFW_KEY_DOWN);
        press(screen, GLFW_KEY_ENTER);
        assertTrue(screen.consumeQuitRequest());
        var populated = screen(games(2));
        populated.replaceEntries(List.of());
        assertNull(populated.getSelectedGameId());
        populated.replaceEntries(games(3));
        assertEquals("game-0", populated.getSelectedGameId());
    }

    @Test void returningFromModsRefreshesTheEffectiveCatalogWithoutReusingAnOldIndex() {
        var entries = games(4);
        var screen = screen(entries);
        screen.setSelectedIndexForTest(2);
        MasterTitleCatalog.bind(screen, () -> List.of(entries.get(2), entries.get(0)));
        screen.setModManagerScreenFactory(font -> new MasterTitleScreen.ModManagerView() {
            boolean close;
            public void update(com.openggf.control.InputHandler input) { close = true; }
            public void render() { }
            public void suppressInputUntilNeutral() { }
            public boolean consumeCloseRequested() { return close; }
        });
        press(screen, GLFW_KEY_DOWN);
        for (int i = 0; i < 4; i++) press(screen, GLFW_KEY_DOWN);
        press(screen, GLFW_KEY_ENTER);
        screen.update(new com.openggf.control.InputHandler());
        assertEquals(2, screen.entriesForTest().size());
        assertEquals("game-2", screen.getSelectedGameId());
        press(screen, GLFW_KEY_RIGHT);
        assertEquals("game-0", screen.getSelectedGameId());
    }

    @Test void duplicateIdentityCannotReplaceTheCatalog() {
        var entries = games(2);
        var screen = screen(entries);
        assertThrows(IllegalArgumentException.class,
                () -> screen.replaceEntries(List.of(entries.getFirst(), entries.getFirst())));
        assertEquals("game-0", screen.getSelectedGameId());
    }
}
