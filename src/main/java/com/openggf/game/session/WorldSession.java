package com.openggf.game.session;

import com.openggf.game.GameModule;
import com.openggf.game.save.SaveSessionContext;
import com.openggf.level.Level;
import com.openggf.game.GameServices;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.game.timing.LoadTimeSimulationMode;

import java.util.Objects;

public final class WorldSession {
    private final GameModule gameModule;
    private final SaveSessionContext saveSessionContext;
    private LoadTimeSimulationMode loadTimeSimulationMode;

    // Loaded-level metadata. Owned by WorldSession because the loaded zone/act
    // identity must survive editor mode swaps (per
    // docs/architecture/designs/2026-04-07-runtime-ownership-migration-design.md).
    // LevelManager mirrors these via write-through during level load; the
    // mirror exists for fast internal reads without touching session API.
    private int currentZone = 0;
    private int currentAct = 0;
    private int apparentAct = 0;
    // The currently-loaded Level (full record: tilemap, blocks, palettes,
    // mutable layout). Lives on WorldSession because the loaded world data
    // must survive editor mode swaps. LevelManager mirrors this via
    // write-through during level load; new LevelManagers inherit it on
    // construction.
    private Level currentLevel;

    public WorldSession(GameModule gameModule) {
        this(gameModule, null);
    }

    public WorldSession(GameModule gameModule, SaveSessionContext saveSessionContext) {
        this.gameModule = Objects.requireNonNull(gameModule, "gameModule");
        this.saveSessionContext = saveSessionContext;
    }

    /**
     * Returns the active gameplay module owned by this world session.
     */
    public GameModule getGameModule() {
        return gameModule;
    }

    /**
     * Returns the save session context associated with this world session,
     * or null if no save context was provided (e.g., non-save gameplay).
     */
    public SaveSessionContext getSaveSessionContext() {
        return saveSessionContext;
    }

    /**
     * Resolves normal-play load timing once for this world session. Editor
     * swaps and gameplay-context reconstruction therefore cannot silently
     * change timing because configuration was reloaded mid-session.
     */
    public synchronized LoadTimeSimulationMode loadTimeSimulationMode() {
        if (loadTimeSimulationMode == null) {
            loadTimeSimulationMode = LoadTimeSimulationMode.parse(
                    GameServices.configuration().getString(
                            SonicConfiguration.LOAD_TIME_SIMULATION));
        }
        return loadTimeSimulationMode;
    }

    public int getCurrentZone() {
        return currentZone;
    }

    public int getCurrentAct() {
        return currentAct;
    }

    public int getApparentAct() {
        return apparentAct;
    }

    public void setCurrentZone(int currentZone) {
        this.currentZone = currentZone;
    }

    public void setCurrentAct(int currentAct) {
        this.currentAct = currentAct;
    }

    public void setApparentAct(int apparentAct) {
        this.apparentAct = apparentAct;
    }

    /**
     * Returns the currently-loaded {@link Level}, or {@code null} if no level
     * has been loaded into this session yet.
     */
    public Level getCurrentLevel() {
        return currentLevel;
    }

    public void setCurrentLevel(Level currentLevel) {
        this.currentLevel = currentLevel;
    }
}
