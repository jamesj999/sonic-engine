package uk.co.jamesj999.sonic.game;

import uk.co.jamesj999.sonic.audio.GameAudioProfile;
import uk.co.jamesj999.sonic.data.Game;
import uk.co.jamesj999.sonic.data.Rom;
import uk.co.jamesj999.sonic.data.RomByteReader;
import uk.co.jamesj999.sonic.level.objects.ObjectRegistry;
import uk.co.jamesj999.sonic.level.objects.PlaneSwitcherConfig;
import uk.co.jamesj999.sonic.level.objects.TouchResponseTable;

public interface GameModule {
    String getIdentifier();

    Game createGame(Rom rom);

    ObjectRegistry createObjectRegistry();

    GameAudioProfile getAudioProfile();

    TouchResponseTable createTouchResponseTable(RomByteReader romReader);

    int getPlaneSwitcherObjectId();

    PlaneSwitcherConfig getPlaneSwitcherConfig();

    /**
     * Returns the level event provider for this game.
     * Level events handle dynamic camera boundary changes, boss arena setup,
     * and other zone-specific runtime behaviors.
     *
     * @return the level event provider, or null if the game has no dynamic level events
     */
    LevelEventProvider getLevelEventProvider();

    /**
     * Creates a new respawn state instance for tracking checkpoint data.
     * Called when loading a new level to manage death/respawn behavior.
     *
     * @return a new RespawnState instance
     */
    RespawnState createRespawnState();

    /**
     * Creates a new level state instance for tracking transient level data.
     * Called when loading a new level to manage rings, time, etc.
     *
     * @return a new LevelState instance
     */
    LevelState createLevelState();

    /**
     * Returns the title card provider for this game.
     * Title cards display zone/act information when entering levels.
     *
     * @return the title card provider
     */
    TitleCardProvider getTitleCardProvider();
}
