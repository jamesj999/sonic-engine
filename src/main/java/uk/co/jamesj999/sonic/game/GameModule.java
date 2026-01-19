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

    /**
     * Returns the zone registry for this game.
     * The zone registry provides metadata about zones, acts, and levels.
     *
     * @return the zone registry
     */
    ZoneRegistry getZoneRegistry();

    /**
     * Returns the special stage provider for this game.
     * Special stages award Chaos Emeralds when completed.
     *
     * @return the special stage provider, or null if this game has no special stages
     */
    SpecialStageProvider getSpecialStageProvider();

    /**
     * Returns the bonus stage provider for this game.
     * Bonus stages are accessed via checkpoints and award rings, shields, etc.
     *
     * @return the bonus stage provider, or null if this game has no bonus stages
     */
    BonusStageProvider getBonusStageProvider();

    /**
     * Returns the scroll handler provider for this game.
     * Provides zone-specific parallax scroll handlers.
     *
     * @return the scroll handler provider, or null if using default scrolling
     */
    ScrollHandlerProvider getScrollHandlerProvider();

    /**
     * Returns the zone feature provider for this game.
     * Provides zone-specific mechanics like bumpers, water, etc.
     *
     * @return the zone feature provider, or null if no zone features
     */
    ZoneFeatureProvider getZoneFeatureProvider();

    /**
     * Returns the ROM offset provider for this game.
     * Provides type-safe access to game-specific ROM addresses.
     *
     * @return the ROM offset provider
     */
    RomOffsetProvider getRomOffsetProvider();

    /**
     * Returns the debug mode provider for this game.
     * Provides game-specific debug modes and controls.
     *
     * @return the debug mode provider, or null if no game-specific debug modes
     */
    DebugModeProvider getDebugModeProvider();

    /**
     * Returns the debug overlay provider for this game.
     * Provides game-specific debug overlay content.
     *
     * @return the debug overlay provider, or null if using default overlays
     */
    DebugOverlayProvider getDebugOverlayProvider();

    /**
     * Returns the zone art provider for this game.
     * Provides zone-specific art configurations for objects.
     *
     * @return the zone art provider, or null if no zone-specific art
     */
    ZoneArtProvider getZoneArtProvider();

    /**
     * Returns the object art provider for this game.
     * Provides key-based access to object sprites, animations, and related data.
     * This abstracts away game-specific art loading to support multiple games.
     *
     * @return the object art provider, or null if this game has no object art
     */
    ObjectArtProvider getObjectArtProvider();

    /**
     * Called when a level is loaded to reset any game-specific object state.
     * Use this to clear static state in object classes that persists across
     * object load/unload cycles (e.g., sibling spawn tracking, timing sync).
     * <p>
     * Default implementation does nothing.
     */
    default void onLevelLoad() {
        // Default no-op
    }
}
