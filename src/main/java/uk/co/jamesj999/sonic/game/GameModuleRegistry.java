package uk.co.jamesj999.sonic.game;

import uk.co.jamesj999.sonic.game.sonic2.Sonic2GameModule;

public final class GameModuleRegistry {
    private static GameModule current = new Sonic2GameModule();

    private GameModuleRegistry() {
    }

    public static GameModule getCurrent() {
        return current;
    }

    public static void setCurrent(GameModule module) {
        if (module != null) {
            current = module;
        }
    }
}
