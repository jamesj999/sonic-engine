package com.openggf.level.objects;

import com.openggf.audio.AudioManager;
import com.openggf.audio.GameMusic;
import com.openggf.audio.GameSound;
import com.openggf.camera.Camera;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.data.Rom;
import com.openggf.data.RomByteReader;
import com.openggf.data.RomManager;
import com.openggf.debug.DebugOverlayManager;
import com.openggf.game.BonusStageProvider;
import com.openggf.game.BonusStageType;
import com.openggf.game.CrossGameFeatureProvider;
import com.openggf.game.session.EngineContext;
import com.openggf.game.GameRng;
import com.openggf.game.GameStateManager;
import com.openggf.game.GameModule;
import com.openggf.game.LevelEventProvider;
import com.openggf.game.LevelState;
import com.openggf.game.PlayableEntity;
import com.openggf.game.RespawnState;
import com.openggf.game.TitleCardProvider;
import com.openggf.game.ZoneFeatureProvider;
import com.openggf.game.palette.PaletteOwnershipRegistry;
import com.openggf.game.save.SaveReason;
import com.openggf.game.session.WorldSession;
import com.openggf.game.solid.ObjectSolidExecutionContext;
import com.openggf.game.solid.SolidExecutionRegistry;
import com.openggf.game.mutation.ZoneLayoutMutationPipeline;
import com.openggf.game.zone.ZoneRuntimeRegistry;
import com.openggf.game.zone.ZoneRuntimeState;
import com.openggf.game.timing.HardwareTimingService;
import com.openggf.game.RuntimeArtCoordinator;
import com.openggf.game.resources.NativeFadeLifecycle;
import com.openggf.graphics.FadeManager;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.BigRingReturnState;
import com.openggf.level.Level;
import com.openggf.level.LevelManager;
import com.openggf.level.ParallaxManager;
import com.openggf.level.SeamlessLevelTransitionRequest;
import com.openggf.level.WaterSystem;
import com.openggf.level.rings.RingManager;
import com.openggf.physics.CollisionSystem;
import com.openggf.sprites.managers.SpriteManager;
import java.io.IOException;
import java.util.List;

/**
 * Injectable service handle for game objects. Provides access to level sub-managers,
 * rendering, audio, and gameplay systems without requiring singleton lookups.
 * <p>
 * Available during construction (via ThreadLocal context), update, and rendering.
 * Injected by {@link ObjectManager} via
 * {@link AbstractObjectInstance#setServices(ObjectServices)}.
 */
public interface ObjectServices {
    // Object management
    ObjectManager objectManager();
    ObjectRenderManager renderManager();

    // Level state
    LevelState levelGamestate();
    RespawnState checkpointState();
    LevelManager levelManager();
    Level currentLevel();
    int romZoneId();
    int currentAct();

    /**
     * Returns ROM {@code Apparent_act}. During seamless S3K act reloads this
     * can intentionally differ from {@link #currentAct()}.
     */
    default int apparentAct() {
        LevelManager manager = levelManager();
        return manager != null ? manager.getApparentAct() : currentAct();
    }

    int featureZoneId();
    int featureActId();
    ZoneFeatureProvider zoneFeatureProvider();

    // Audio
    void playSfx(int soundId);
    void playSfx(GameSound sound);
    void playMusic(int musicId);

    /**
     * Submits a native ROM sound id through the music mailbox, as the ROM's
     * {@code PlayMusic} does at several {@code SndID_} call sites
     * (docs/s2disasm/s2.asm:1517-1527).
     */
    default void playMusicMailboxNativeRequest(int nativeRequestId) {
        audioManager().playMusicMailboxNativeRequest(nativeRequestId);
    }
    default boolean playMusic(GameMusic music) {
        return audioManager().playMusic(music);
    }
    void fadeOutMusic();
    AudioManager audioManager();

    // Gameplay
    void spawnLostRings(PlayableEntity player, int frameCounter);

    /**
     * Returns S3K's {@code V_int_run_count} as observed at a given object-execution
     * instant. Callers pass the counter value the object manager handed them —
     * {@code ObjectExecutionController} dispatches {@code instance.update(vblaCounter, ...)},
     * and that value already equals ROM {@code V_int_run_count}. This method performs no
     * conversion between two clocks; it only re-applies the low-bit phase offset that old
     * S3K trace schemas need because they captured the adjacent V-int word rather than the
     * run counter itself. The offset is zero in all normal gameplay, so this is then a
     * pass-through.
     */
    default int resolveVIntRunCount(int vIntRunCountAtObservation) {
        ObjectManager manager = objectManager();
        int phaseOffset = manager != null ? manager.getVIntRunCounterPhaseOffset() : 0;
        return vIntRunCountAtObservation + phaseOffset;
    }

    default void spawnLostRingsAfterCurrentFrame(PlayableEntity player, int frameCounter) {
        spawnLostRings(player, frameCounter);
    }

    /** Queues a spill whose Obj37 owner defers the native ring-count clear. */
    default void spawnLostRingsWithDeferredOwner(PlayableEntity player, int frameCounter) {
        spawnLostRingsAfterCurrentFrame(player, frameCounter);
    }

    /** Returns the runtime-owned ROM-accurate pseudo-random number generator. */
    GameRng rng();

    ZoneRuntimeRegistry zoneRuntimeRegistry();

    ZoneRuntimeState zoneRuntimeState();

    PaletteOwnershipRegistry paletteOwnershipRegistryOrNull();

    ZoneLayoutMutationPipeline zoneLayoutMutationPipeline();

    SolidExecutionRegistry solidExecutionRegistry();

    default ObjectSolidExecutionContext solidExecution() {
        return solidExecutionRegistry().currentObject();
    }

    // Context-specific managers
    /**
     * Returns the camera for position queries and bounds checks.
     * <p>
     * <b>Governance:</b> Object instance code (subclasses of {@link AbstractObjectInstance})
     * should use this injected method. The static game-service facade is for
     * non-object code (HUD, level loading, etc.).
     */
    Camera camera();

    /**
     * Returns the game state manager for score, lives, and emerald tracking.
     * <p>
     * <b>Governance:</b> Object instance code should use this injected method.
     */
    GameStateManager gameState();

    /** Returns the active world session backing the current runtime. */
    WorldSession worldSession();

    /** Returns the active game module owned by the current world session. */
    GameModule gameModule();

    /** Session-owned hardware preparation/readiness service. */
    default HardwareTimingService hardwareTiming() {
        throw new IllegalStateException(
                "hardware timing is unavailable in these object services");
    }

    /** Game-owned runtime-art coordinator for this gameplay session. */
    default RuntimeArtCoordinator runtimeArtCoordinator() {
        throw new IllegalStateException(
                "runtime-art coordination is unavailable in these object services");
    }

    default NativeFadeLifecycle nativeFadeLifecycle() {
        throw new IllegalStateException(
                "native fade lifecycle is unavailable in these object services");
    }

    // Player/sidekick access
    List<PlayableEntity> sidekicks();

    /**
     * Returns the preferred object-facing player participation query API.
     * <p>
     * Raw {@link #sidekicks()} remains available while object code migrates to
     * explicit participation policies through this query layer.
     */
    default ObjectPlayerQuery playerQuery() {
        return ObjectPlayerQuery.from(this);
    }

    /** Returns the sprite manager for player sprite access. */
    SpriteManager spriteManager();

    /**
     * Returns the active collision system for object-local ROM handoffs that
     * must reuse terrain/wall probes.
     */
    CollisionSystem collisionSystem();

    // --- Rendering ---

    /** Returns the graphics manager for pattern caching and rendering. */
    GraphicsManager graphicsManager();

    /** Returns the fade manager for screen transitions. */
    FadeManager fadeManager();

    /** Returns the active engine-level service bundle backing process-wide services. */
    EngineContext engineServices();

    /** Returns the configuration service. */
    SonicConfigurationService configuration();

    /** Returns the debug overlay manager. */
    DebugOverlayManager debugOverlay();

    /** Returns the ROM manager. */
    RomManager romManager();

    /** Returns the cross-game feature provider. */
    CrossGameFeatureProvider crossGameFeatures();

    // --- ROM data ---

    /** Returns the current ROM instance. */
    Rom rom() throws IOException;

    /** Returns a ROM byte reader for the current ROM. */
    RomByteReader romReader() throws IOException;

    // --- Level subsystems ---

    /** Returns the water system for water level queries. */
    WaterSystem waterSystem();

    /** Returns the parallax manager for scroll offset queries. */
    ParallaxManager parallaxManager();

    // --- Level actions ---

    /** Requests transition to the next level. Wraps IOException as unchecked. */
    void advanceToNextLevel();

    /** Requests transition to the credits/ending sequence. */
    void requestCreditsTransition();

    /** Requests entry into a special stage. */
    void requestSpecialStageEntry();

    /** Invalidates the cached foreground tilemap (e.g., after block changes). */
    void invalidateForegroundTilemap();

    /** Updates a palette line in the level's palette table. */
    void updatePalette(int paletteIndex, byte[] paletteData);

    /** Returns the ring manager for ring-related operations. */
    RingManager ringManager();

    /** Returns the current zone index (rom-mapped). Alias for {@link #romZoneId()}. */
    default int currentZone() { return romZoneId(); }

    /**
     * Sets the apparent act for title card display.
     * ROM: {@code move.b #n,(Apparent_act).w} — used by the results screen
     * to update the display act after act 1 completion.
     */
    void setApparentAct(int act);

    /** Returns true if all rings in the current level have been collected. */
    boolean areAllRingsCollected();

    // --- Level transition actions ---

    /**
     * Advances zone/act counters without loading the new level.
     * Used when entering a special stage after results screen (ROM: Got_NextLevel).
     */
    void advanceZoneActOnly();

    /**
     * Advances an end-of-act results card to its special-stage entry routine,
     * whose body — the game-mode write — runs on the following frame.
     * Wraps {@link com.openggf.level.LevelTransitionCoordinator#advanceToSpecialStageEntryRoutine()}.
     */
    void advanceToSpecialStageEntryRoutine();

    /**
     * Requests entry into a bonus stage of the given type.
     * Wraps {@link com.openggf.level.LevelTransitionCoordinator#requestBonusStageEntry(BonusStageType)}.
     *
     * @param type the bonus stage type to enter
     */
    void requestBonusStageEntry(BonusStageType type);

    /**
     * Requests exit from the current bonus stage.
     * Wraps {@link com.openggf.game.BonusStageProvider#requestExit()}.
     */
    void requestBonusStageExit();

    /**
     * Returns the active bonus-stage provider, or {@code null} when no bonus
     * stage is active. Exposes the gameplay-scoped provider through the injected
     * object-service handle so object/restore code can resolve bonus-stage state
     * without a global {@code GameServices.bonusStageOrNull()} lookup.
     */
    default BonusStageProvider bonusStageProviderOrNull() {
        return null;
    }

    /**
     * Adds rings to the bonus stage coordinator's saved ring count.
     * ROM equivalent: {@code add.w d0,(Saved_ring_count).w}.
     * No-op when not in a bonus stage.
     *
     * @param count the number of rings to add
     */
    void addBonusStageRings(int count);

    /**
     * Records the shield awarded by a bonus-stage gumball pickup so the
     * exit handler can restore it after the level reload clears the player
     * state. No-op when not in a bonus stage.
     *
     * @param type the shield type awarded
     */
    void setBonusStageShield(com.openggf.game.ShieldType type);

    /**
     * Requests transition to a specific zone and act.
     *
     * @param zone the zone index (0-based)
     * @param act  the act index (0-based)
     */
    void requestZoneAndAct(int zone, int act);

    /**
     * Requests transition to a specific zone and act, optionally freezing level updates.
     *
     * @param zone               the zone index (0-based)
     * @param act                the act index (0-based)
     * @param deactivateLevelNow true to freeze level updates until the transition completes
     */
    void requestZoneAndAct(int zone, int act, boolean deactivateLevelNow);

    /**
     * Requests a zone/act transition whose destination music must be started
     * after loading completes.
     */
    default void requestZoneAndAct(int zone, int act, boolean deactivateLevelNow, int musicId) {
        levelManager().requestZoneAndAct(zone, act, deactivateLevelNow, musicId);
    }

    /**
     * Requests an in-place seamless transition. Use for ROM paths that reload
     * or mutate the level without entering the fade transition loop.
     *
     * @param request transition request to enqueue
     */
    default void requestSeamlessTransition(SeamlessLevelTransitionRequest request) {
    }

    // --- Level queries ---

    /**
     * Returns the music ID for the current level, or -1 if unknown.
     */
    int getCurrentLevelMusicId();

    /** Returns the track selected by ROM {@code Apparent_zone_and_act}. */
    default int getApparentLevelMusicId() {
        LevelManager manager = levelManager();
        return manager != null ? manager.getApparentLevelMusicId() : -1;
    }

    /**
     * Searches the level's foreground tilemap for a pattern within a radius.
     *
     * @param refX         reference X position (world coordinates)
     * @param refY         reference Y position (world coordinates)
     * @param minTileIdx   minimum tile index to match
     * @param maxTileIdx   maximum tile index to match
     * @param searchRadius search radius in tiles
     * @return {offsetX, offsetY} from ref to pattern center, or null if not found
     */
    int[] findPatternOffset(int refX, int refY, int minTileIdx, int maxTileIdx, int searchRadius);

    /**
     * Saves the player/camera position and ring count for returning from a
     * big ring special stage (ROM: Save_Level_Data2 -> Saved2_* variables).
     */
    void saveBigRingReturn(BigRingReturnState state);

    /**
     * ROM {@code move.b #0,(Last_star_post_hit).w} (skdisasm/sonic3k.asm:128414):
     * clears the flag that gates the saved-position restore on the next level
     * load ({@code loc_1BE46}, sonic3k.asm:38148-38151).
     */
    void clearLastStarPostHit();

    /** Requests a save at an exact gameplay write point. */
    void requestSessionSave(SaveReason reason);

    // --- Game-specific providers ---

    /**
     * Returns the level event provider for the current game.
     * Object code may cast to the game-specific type (e.g., Sonic2LevelEventManager)
     * for methods not on the base interface.
     *
     * @return the level event provider, or null if unavailable
     */
    default LevelEventProvider levelEventProvider() { return null; }

    /**
     * Returns the title card provider for the current game.
     *
     * @return the title card provider, or null if unavailable
     */
    default TitleCardProvider titleCardProvider() { return null; }

    /**
     * Returns a game-specific service by type, or null if not available.
     * Used for game-specific singletons (e.g., Sonic1SwitchManager, Sonic2SpecialStageManager)
     * that don't have cross-game abstract interfaces.
     * <p>
     * The service is resolved through the current {@link com.openggf.game.GameModule}.
     *
     * @param type the service class
     * @param <T>  the service type
     * @return the service instance, or null if not registered for the current game
     */
    default <T> T gameService(Class<T> type) { return null; }
}
