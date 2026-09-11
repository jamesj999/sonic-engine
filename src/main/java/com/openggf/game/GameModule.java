package com.openggf.game;

import com.openggf.audio.GameAudioProfile;
import com.openggf.data.Game;
import com.openggf.data.Rom;
import com.openggf.data.RomByteReader;
import com.openggf.game.dataselect.DataSelectHostProfile;
import com.openggf.game.dataselect.DataSelectPresentationProvider;
import com.openggf.game.profiles.trace.TracePlaybackProfile;
import com.openggf.game.startup.DonatedDataSelectWarmupTask;
import com.openggf.level.LevelManager;
import com.openggf.level.InitialFixedSstDispatcher;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectRegistry;
import com.openggf.level.objects.PlaneSwitcherConfig;
import com.openggf.level.objects.TouchResponseTable;
import com.openggf.sprites.art.SpriteArtSet;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.SuperStateController;
import com.openggf.sprites.managers.SpriteManager;
import com.openggf.game.timing.HardwareTimingService;
import com.openggf.game.rewind.RewindSnapshottable;
import com.openggf.game.timing.LoadTimeProfile;
import com.openggf.game.timing.LoadTimeProfileFactory;
import com.openggf.game.timing.LoadTimeSimulationMode;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

public interface GameModule {
    String getIdentifier();

    Game createGame(Rom rom);

    /**
     * Returns session-owned game services whose mutable state participates in
     * a gameplay rewind. The composition root registers these adapters once
     * per {@link com.openggf.game.session.WorldSession}; games without such
     * services remain empty.
     */
    default List<RewindSnapshottable<?>> rewindAdapters() {
        return List.of();
    }

    /** Creates this game's session-owned runtime-art coordinator. */
    default RuntimeArtCoordinator createRuntimeArtCoordinator(
            HardwareTimingService timing) {
        return RuntimeArtCoordinator.NONE;
    }

    /** Resolves a fresh session-owned normal-play load-time profile. */
    default LoadTimeProfile createLoadTimeProfile(
            LoadTimeSimulationMode mode,
            LoadTimeProfile profiled,
            Consumer<String> warningSink) {
        return LoadTimeProfileFactory.resolve(mode, profiled, warningSink);
    }

    default LoadTimeProfile createLoadTimeProfile(
            LoadTimeSimulationMode mode,
            Consumer<String> warningSink) {
        return createLoadTimeProfile(mode, LoadTimeProfile.IMMEDIATE, warningSink);
    }

    ObjectRegistry createObjectRegistry();

    GameAudioProfile getAudioProfile();

    TouchResponseTable createTouchResponseTable(RomByteReader romReader);

    int getPlaneSwitcherObjectId();

    /**
     * Returns the object ID used for checkpoints/lampposts in this game.
     * Used by debug teleportation and respawn logic.
     *
     * @return the checkpoint object ID, or 0 if checkpoints are not implemented
     */
    default int getCheckpointObjectId() {
        return 0;
    }

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
     * Supplies the game-owned fixed-SST inventory for the initial native
     * Process_Sprites pass. Games without that setup lifecycle remain inert.
     */
    default InitialFixedSstDispatcher createInitialFixedSstDispatcher(
            SpriteManager sprites,
            ObjectManager objects,
            ZoneFeatureProvider zoneFeatures) {
        return epoch -> { };
    }

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
    default TitleCardProvider getTitleCardProvider() {
        return NoOpTitleCardProvider.INSTANCE;
    }

    /**
     * Owner of the GAME OVER / TIME OVER card spawn for this game, or
     * {@code null} when the game has no such flow yet. See
     * {@link GameOverFlowProvider}.
     */
    default GameOverFlowProvider getGameOverFlowProvider() {
        return null;
    }

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
     * @return the special stage provider
     */
    default SpecialStageProvider getSpecialStageProvider() {
        return NoOpSpecialStageProvider.INSTANCE;
    }

    /**
     * Returns the number of special stages used for stage index cycling.
     * Sonic 1 uses 6, Sonic 2 uses 7.
     *
     * @return special stage cycle count
     */
    default int getSpecialStageCycleCount() {
        return 7;
    }

    /**
     * Returns the RNG flavour used by this game's RandomNumber routine.
     * Sonic 1 and Sonic 2 share the S1/S2 variant; S3K overrides this.
     */
    default GameRng.Flavour rngFlavour() {
        return GameRng.Flavour.S1_S2;
    }

    /**
     * Returns the number of Chaos Emeralds required for "all emeralds".
     * Sonic 1 uses 6, Sonic 2/Sonic 3&K use 7.
     *
     * @return chaos emerald target count
     */
    default int getChaosEmeraldCount() {
        return 7;
    }

    /**
     * Returns the bonus stage provider for this game.
     * Bonus stages are accessed via checkpoints and award rings, shields, etc.
     *
     * @return the bonus stage provider
     */
    default BonusStageProvider getBonusStageProvider() {
        return NoOpBonusStageProvider.INSTANCE;
    }

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
     * Returns the water data provider for this game.
     * Provides zone-specific water heights, palettes, and dynamic handlers.
     *
     * @return the water data provider, or null if water not supported
     */
    default WaterDataProvider getWaterDataProvider() {
        return null;
    }

    /**
     * Returns the ROM offset provider for this game.
     * Provides type-safe access to game-specific ROM addresses.
     *
     * @return the ROM offset provider
     */
    default RomOffsetProvider getRomOffsetProvider() {
        return NoOpRomOffsetProvider.INSTANCE;
    }

    /**
     * Returns the debug mode provider for this game.
     * Provides game-specific debug modes and controls.
     *
     * @return the debug mode provider
     */
    default DebugModeProvider getDebugModeProvider() {
        return NoOpDebugModeProvider.INSTANCE;
    }

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
     * @return the zone art provider
     */
    default ZoneArtProvider getZoneArtProvider() {
        return NoOpZoneArtProvider.INSTANCE;
    }

    /**
     * Returns the object art provider for this game.
     * Provides key-based access to object sprites, animations, and related data.
     * This abstracts away game-specific art loading to support multiple games.
     *
     * @return the object art provider, or null if this game has no object art
     */
    ObjectArtProvider getObjectArtProvider();

    /** Construct a fresh ROM-owned Continue screen for this session transition. */
    default ContinueScreenProvider createContinueScreenProvider() {
        return null;
    }

    /**
     * Returns the title screen provider for this game.
     * Provides the game-specific title screen with ROM-accurate
     * art, palettes, and scrolling.
     *
     * @return the title screen provider, or null if not implemented
     */
    default TitleScreenProvider getTitleScreenProvider() {
        return NoOpTitleScreenProvider.INSTANCE;
    }

    /**
     * Returns the data select provider for this game.
     * Provides the save file selection screen (S3K-style).
     *
     * @return the data select provider
     */
    default DataSelectProvider getDataSelectProvider() {
        return getDataSelectPresentationProvider();
    }

    /** Returns the presentation provider used to render and update data select. */
    default DataSelectPresentationProvider getDataSelectPresentationProvider() {
        return new DataSelectPresentationProvider(ignored -> NoOpDataSelectProvider.INSTANCE, null);
    }

    /** Returns the host-owned data select profile for this game, if supported. */
    default DataSelectHostProfile getDataSelectHostProfile() {
        return null;
    }

    /**
     * Returns the save snapshot provider for this game.
     * Captures game-specific state into a map for save file serialization.
     *
     * @return the save snapshot provider
     */
    default com.openggf.game.save.SaveSnapshotProvider getSaveSnapshotProvider() {
        return (reason, ctx) -> java.util.Map.of();
    }

    /**
     * Returns the level select provider for this game.
     * Provides the game-specific level select screen with ROM-accurate
     * menu layout, text, and navigation.
     *
     * @return the level select provider, or null if not implemented
     */
    default LevelSelectProvider getLevelSelectProvider() {
        return NoOpLevelSelectProvider.INSTANCE;
    }

    /**
     * Returns the physics provider for this game.
     * Provides per-character physics profiles, modifier rules (water/speed shoes),
     * and typed game rules (spindash availability, collision model, and runtime gates).
     *
     * @return the physics provider
     */
    PhysicsProvider getPhysicsProvider();

    default com.openggf.game.rules.GameRules getRules() {
        PhysicsProvider provider = getPhysicsProvider();
        if (provider == null) {
            throw new IllegalStateException("No PhysicsProvider for " + getIdentifier());
        }
        return provider.getRules();
    }

    /**
     * Returns measured movie-replay timing facts for this game. Games opt in
     * only when their ROM lifecycle timing has been established from captures.
     */
    default TracePlaybackProfile getTracePlaybackProfile() {
        return TracePlaybackProfile.DISABLED;
    }

    /**
     * Creates a Super Sonic state controller for the given player sprite.
     * Returns null if this game does not support Super Sonic.
     *
     * @param player the player sprite
     * @return a new SuperStateController, or null
     */
    default SuperStateController createSuperStateController(
            AbstractPlayableSprite player) {
        return null;
    }

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

    /**
     * Called before a gameplay session is fully torn down to clear
     * module-scoped static caches or other process-wide helper state.
     * Default implementation does nothing.
     */
    default void resetModuleScopedState() {
        // Default no-op
    }

    /**
     * Supplies the sidekick-carry trigger for this game module. Defaults to
     * {@code null} (no carry mechanic). Only Sonic 3 &amp; Knuckles overrides
     * this to port the Tails-carry-Sonic CNZ1 intro.
     *
     * @see com.openggf.sprites.playable.SidekickCarryTrigger
     */
    default com.openggf.sprites.playable.SidekickCarryTrigger getSidekickCarryTrigger() {
        return null;
    }

    /**
     * Applies game-specific plane switching logic for the given player sprite.
     * Called each frame from LevelManager.applyPlaneSwitchers(), after any
     * object-based plane switching (Sonic 2 style).
     *
     * <p>Sonic 1 uses this for loop-based plane switching (Sonic_Loops).
     * Default implementation does nothing (Sonic 2/3K use object-based switching).
     *
     * @param player the player sprite to apply plane switching to
     */
    default void applyPlaneSwitching(AbstractPlayableSprite player) {
        // Default no-op
    }

    /**
     * Returns whether the given zone/act/level combination represents a
     * "remapped zone" scenario where the physical level data comes from a
     * different zone than the logical zone.
     * <p>
     * Sonic 1 SBZ act 3 is loaded from LZ zone data, so feature systems
     * (water, palettes) need to be told the logical zone (SBZ) rather than
     * the physical level zone (LZ).
     *
     * @param logicalZone the zone the game considers the player to be in
     * @param act the act index
     * @param levelZoneIndex the zone index stored in the level data
     * @return effective zone index for feature lookups, or -1 if no remapping
     */
    default int getRemappedFeatureZone(int logicalZone, int act, int levelZoneIndex) {
        return -1;
    }

    /**
     * Returns the effective act for the remapped feature zone, or -1 if no remapping.
     *
     * @param logicalZone the zone the game considers the player to be in
     * @param act the act index
     * @param levelZoneIndex the zone index stored in the level data
     * @return effective act for feature lookups, or -1 if no remapping
     */
    default int getRemappedFeatureAct(int logicalZone, int act, int levelZoneIndex) {
        return -1;
    }

    /**
     * Returns the ROM's {@code v_act} for the current level, or -1 when the
     * logical act index already is it.
     * <p>
     * This is the ROM level identity, not the feature identity: it pairs with
     * {@link com.openggf.level.LevelManager#getRomZoneId()} to give the
     * ({@code v_zone}, {@code v_act}) word that ROM routines compare against.
     * Games whose logical zone/act numbering differs from the ROM's level table
     * override this; {@link #getRemappedFeatureAct} answers a different
     * question and must not be reused for it.
     *
     * @param logicalZone the zone the game considers the player to be in
     * @param act the logical act index
     * @param levelZoneIndex the zone index stored in the level data
     * @return the ROM act index, or -1 when it equals {@code act}
     */
    default int getRomAct(int logicalZone, int act, int levelZoneIndex) {
        return -1;
    }

    /**
     * Returns whether this game supports separate Tails tail art (Obj05 with
     * independent art/mapping/DPLC). S3K uses a completely separate set;
     * S2 reuses the main Tails art at a different VRAM base.
     *
     * @return true if Tails tail art is loaded from a separate source
     */
    default boolean hasSeparateTailsTailArt() {
        return false;
    }

    /**
     * Loads the separate Tails tail appendage art set (Obj05).
     * Only meaningful when {@link #hasSeparateTailsTailArt()} returns true.
     *
     * @return the tail art set, or {@link SpriteArtSet#EMPTY} if not available
     */
    default SpriteArtSet loadTailsTailArt() {
        return SpriteArtSet.EMPTY;
    }

    /**
     * Returns the VRAM base tile index for the Tails tail appendage (Obj05)
     * when the game reuses the main Tails body art at a different VRAM offset.
     * <p>
     * Only meaningful when {@link #hasSeparateTailsTailArt()} returns false
     * (i.e. S2-style shared art). S3K overrides to return -1 since it uses
     * completely separate art via {@link #loadTailsTailArt()}.
     *
     * @return the VRAM base tile index (e.g. 0x07B0 for S2), or -1 if not applicable
     */
    default int getTailsTailVramBase() {
        return -1;
    }

    /**
     * Returns the ending/credits provider for this game.
     * Manages the full ending sequence: cutscene, credits text,
     * demo playback, and post-credits screens.
     *
     * @return the ending provider, or null if not implemented
     */
    default EndingProvider getEndingProvider() {
        return null;
    }

    /**
     * Returns whether invincibility stars use a trail-based animation pattern
     * (following behind the player) rather than orbital animation.
     *
     * @return true if invincibility stars use trail mode
     */
    default boolean hasTrailInvincibilityStars() {
        return false;
    }

    /**
     * Returns a factory that constructs the invincibility-stars power-up object
     * for the player. Games may override to return a game-specific subclass
     * (e.g. S3K returns {@code Sonic3kInvincibilityStarsObjectInstance}).
     *
     * <p>The default returns the game-agnostic
     * {@link com.openggf.level.objects.InvincibilityStarsObjectInstance} used
     * by S1/S2.
     *
     * @return a factory creating an {@link com.openggf.level.objects.AbstractObjectInstance}
     *         for the given player
     */
    default java.util.function.Function<PlayableEntity, com.openggf.level.objects.AbstractObjectInstance>
            getInvincibilityStarsFactory() {
        return com.openggf.level.objects.InvincibilityStarsObjectInstance::new;
    }

    /**
     * Returns a factory that constructs the shield power-up object for the
     * player and requested {@link ShieldType}. Games with elemental shields
     * (S3K) override this to map {@code FIRE}/{@code LIGHTNING}/{@code BUBBLE}
     * to their concrete object classes; the default builds the game-agnostic
     * {@link com.openggf.level.objects.ShieldObjectInstance} for every type.
     *
     * @return a factory creating the shield object for the given player and type
     */
    default java.util.function.BiFunction<AbstractPlayableSprite, ShieldType,
            com.openggf.level.objects.ShieldObjectInstance> getShieldFactory() {
        return (player, type) -> new com.openggf.level.objects.ShieldObjectInstance(player);
    }

    /**
     * Returns a factory that constructs the persistent insta-shield object for
     * the player, or {@code null} when the game has no such object. The result
     * must implement {@link InstaShieldHandle}. Only games whose
     * {@code PlayerCapabilityRules.instaShieldEnabled()} is set are asked for
     * one, so the default returns {@code null}.
     *
     * @return a factory creating the insta-shield object, or {@code null}
     */
    default java.util.function.Function<AbstractPlayableSprite,
            com.openggf.level.objects.AbstractObjectInstance> getInstaShieldFactory() {
        return null;
    }

    /**
     * Returns a factory that constructs the water-entry splash as a level object
     * from {@code (playerX, waterY)}, or {@code null} when the game draws its
     * splash through the fixed dust object instead
     * ({@code PowerUpRules.waterSplashUsesFixedDustObject()}). Sonic 1 overrides
     * this with its LZ splash object (ROM object 0x08).
     *
     * @return a factory creating the splash object, or {@code null}
     */
    default java.util.function.BiFunction<Integer, Integer,
            com.openggf.level.objects.AbstractObjectInstance> getWaterSplashFactory() {
        return null;
    }

    /**
     * Returns whether this game natively supports a sidekick character (e.g., Tails).
     * Games without sidekick art/logic should return false.
     *
     * @return true if sidekick characters are supported
     */
    default boolean supportsSidekick() {
        return false;
    }

    /**
     * Returns whether sidekick characters should be suppressed in the given zone.
     * Some zones (e.g., S2 Sky Chase, Wing Fortress, Death Egg) have no sidekick
     * during gameplay.
     *
     * @param zoneId the current zone index
     * @return true if sidekicks should be hidden and inactive
     */
    default boolean isSidekickSuppressedForZone(int zoneId) {
        return false;
    }

    /** Returns the ROM-derived level initialization profile for this game. */
    default LevelInitProfile getLevelInitProfile() {
        return AbstractLevelInitProfile.EMPTY;
    }

    /**
     * Returns the donor capabilities for this game, describing which characters,
     * moves, and art assets it can supply when used as a sprite donor in
     * cross-game feature donation.
     *
     * <p>Returns {@code null} by default, meaning this game does not support
     * acting as a donor. Override in game modules that implement donation.</p>
     *
     * @return a {@link DonorCapabilities} instance, or {@code null} if not supported
     */
    default DonorCapabilities getDonorCapabilities() {
        return null;
    }

    /**
     * Returns the provider used when this module acts as a cross-game donor.
     *
     * <p>The provider owns construction of game-specific donor art loaders,
     * palettes, audio profiles, and super-state controllers so shared donation
     * orchestration does not name concrete Sonic implementation classes.</p>
     *
     * @return a donor provider, or {@code null} if this module cannot donate
     * cross-game features
     */
    default CrossGameDonorProvider getCrossGameDonorProvider() {
        return null;
    }

    /**
     * Applies a seamless mutation to the given level manager using the provided key.
     * Called when a seamless zone transition requires applying level data mutations.
     *
     * <p>Default implementation is a no-op for games without seamless mutations.
     *
     * @param levelManager the level manager to apply the mutation to
     * @param mutationKey  the key identifying which mutation to apply
     */
    default void applySeamlessMutation(LevelManager levelManager, String mutationKey) {
        // No-op for games without seamless mutations
    }

    /**
     * Returns a game-specific service by type, or null if not registered.
     * Used to expose game-specific singletons (e.g., Sonic1SwitchManager,
     * Sonic2SpecialStageManager) to object code via
     * {@link com.openggf.level.objects.ObjectServices#gameService(Class)}.
     *
     * @param type the service class
     * @param <T>  the service type
     * @return the service instance, or null
     */
    default <T> T getGameService(Class<T> type) { return null; }

    /**
     * Returns the donated S3K data-select preview image warmup, if this module
     * renders S3K-style data select using host-owned generated preview images.
     */
    default Optional<DonatedDataSelectWarmupTask> getDonatedDataSelectWarmupTask() {
        return Optional.empty();
    }

    /** Returns the GameId for this module. */
    GameId getGameId();

    /**
     * Initial {@code anim_frame_duration} loaded by the badnik-death explosion
     * (Obj27 / ExplosionItem) on its first/setup frame, expressed in the ROM's
     * {@code subq.b #1 / bpl} predecrement convention (the explosion's frame 0
     * is shown for {@code value + 1} game frames before the first advance).
     *
     * <p>This is per-game object animation data, not a behaviour branch: S1
     * {@code ExItem_Main} loads {@code move.b #7,obTimeFrame}
     * (docs/s1disasm/_incObj/24, 27 &amp; 3F Explosions.asm), whereas S2
     * {@code Obj27_Init} loads {@code move.b #3,anim_frame_duration}
     * (docs/s2disasm/s2.asm:46672) and S3K {@code loc_1E626} loads
     * {@code move.b #3,anim_frame_timer} (docs/skdisasm/sonic3k.asm:42195).
     * All three subsequently reload {@code 7} and delete at mapping_frame 5.
     *
     * <p>Default is the S2/S3K value ({@code 3}); {@code Sonic1GameModule}
     * overrides it to {@code 7}.
     */
    default int explosionInitialAnimDuration() {
        return 3;
    }

    /**
     * Resolves a canonical animation to this game's native animation ID.
     * Used by game-agnostic code (sidekick controller) to avoid hardcoding
     * game-specific animation IDs.
     *
     * @param canonical the cross-game animation identifier
     * @return the native animation ID, or -1 if not supported
     */
    default int resolveAnimationId(CanonicalAnimation canonical) {
        DonorCapabilities donor = getDonorCapabilities();
        if (donor == null) return -1;
        int id = donor.resolveNativeId(canonical);
        if (id >= 0) return id;
        // Direct lookup failed — try the fallback chain (e.g. BUBBLE -> GET_AIR for S1)
        java.util.Map<CanonicalAnimation, CanonicalAnimation> fallbacks = donor.getAnimationFallbacks();
        CanonicalAnimation fallback = fallbacks.get(canonical);
        if (fallback != null && fallback != canonical) {
            return donor.resolveNativeId(fallback);
        }
        return -1;
    }
}
