package uk.co.jamesj999.sonic.game;

import uk.co.jamesj999.sonic.data.RomManager;
import uk.co.jamesj999.sonic.debug.DebugOverlayManager;
import uk.co.jamesj999.sonic.timer.TimerManager;

public final class GameServices {
    private static final GameServices INSTANCE = new GameServices();

    private final GameStateManager gameStateManager = GameStateManager.getInstance();
    private final TimerManager timerManager = TimerManager.getInstance();
    private final RomManager romManager = RomManager.getInstance();
    private final DebugOverlayManager debugOverlayManager = DebugOverlayManager.getInstance();

    private GameServices() {
    }

    public static GameServices getInstance() {
        return INSTANCE;
    }

    public static GameStateManager gameState() {
        return INSTANCE.gameStateManager;
    }

    public static TimerManager timers() {
        return INSTANCE.timerManager;
    }

    public static RomManager rom() {
        return INSTANCE.romManager;
    }

    public static DebugOverlayManager debugOverlay() {
        return INSTANCE.debugOverlayManager;
    }
}

