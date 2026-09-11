package com.openggf.game;

import com.openggf.graphics.GraphicsManager;
import com.openggf.level.Level;
import com.openggf.level.Palette;
import com.openggf.level.Pattern;
import com.openggf.level.objects.HudStaticArt;
import com.openggf.level.objects.ObjectSpriteSheet;
import com.openggf.level.objects.art.ObjectArtBundle;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.animation.SpriteAnimationSet;

import java.io.IOException;
import java.util.List;

/**
 * Provider interface for game-specific object art.
 * Abstracts the loading and access of object sprites, animations, and related data
 * to support multiple games (Sonic 1, Sonic 2, Sonic 3&K, etc.).
 * <p>
 * Implementations wrap game-specific art loaders and expose renderers/sheets via
 * string keys for flexible lookup.
 */
public interface ObjectArtProvider {

    /**
     * Processes one frame of provider-owned runtime art work.
     * Implementations with no queued runtime decompression remain no-ops.
     */
    default void processRuntimeArtQueue() {
    }

    /**
     * Opens any runtime-art admission held behind level-presentation work.
     *
     * <p>A normal level load calls this when title-card art retires. A
     * title-card-free seamless load calls it immediately because there is no
     * presentation queue ahead of the runtime art.
     */
    default void onTitleCardArtRetired() {
    }

    /**
     * Signals that the title-card <em>presentation</em> was omitted while the
     * title-card owner object's ROM lifetime still runs.
     *
     * <p>Omitting the presentation does not delete the owner: it keeps
     * executing as an ordinary level object and opens runtime-art admission
     * only when its own state machine retires it. Providers that model that
     * lifetime override this; the default treats retirement as immediate.
     */
    default void onTitleCardPresentationSkipped() {
        onTitleCardArtRetired();
    }

    /**
     * Signals that an in-level title card's owner reached its final
     * {@code Obj_TitleCardWait2} dispatch — the one that falls through to
     * {@code LoadEnemyArt} (docs/skdisasm/sonic3k.asm:62302-62312).
     *
     * <p>An in-level card runs its whole presentation over live gameplay, so
     * runtime-art admission opens here rather than at art retirement. The
     * The exact production-issued lease prevents a completed owner from
     * releasing a replacement batch that became current later.
     */
    default void onInLevelTitleCardCompleted(RuntimeArtAdmissionLease lease) {
        consumeRuntimeArtAdmission(
                lease, RuntimeArtAdmissionOwnerKind.TITLE_OWNER);
    }

    /**
     * Registers transition runtime art under the request's semantic owner.
     * Providers without lease-backed queues retain their existing behavior.
     */
    default RuntimeArtAdmissionLease prepareRuntimeArtForActTransition(
            int zoneIndex, RuntimeArtAdmissionPolicy policy) {
        if (policy == RuntimeArtAdmissionPolicy.PRESERVE_CURRENT) {
            return null;
        }
        reloadStandaloneArtForActTransition(zoneIndex);
        if (policy == RuntimeArtAdmissionPolicy.IMMEDIATE) {
            onTitleCardArtRetired();
        }
        return null;
    }

    /**
     * Prepares the next production-owned admission for an in-level title card.
     *
     * <p>The results owner can create an in-level title after an earlier
     * runtime-art batch has already been admitted. Providers with exact
     * admission leases may issue the presentation's lease here and, when the
     * earlier batch has retired, admit the next ROM-owned runtime-art batch;
     * the title-card owner still binds and consumes that lease through the
     * typed APIs below. Providers without lease-backed queues retain their
     * existing behavior.
     */
    default void prepareRuntimeArtForInLevelTitleCard() {
    }

    /** Binds the provider's one pending lease during owner initialization. */
    default RuntimeArtAdmissionLease bindPendingRuntimeArtAdmission(
            RuntimeArtAdmissionOwnerKind ownerKind) {
        throw new IllegalStateException("runtime-art admission leases are not supported");
    }

    /** Binds a known scalar lease id to a production owner. */
    default RuntimeArtAdmissionLease bindRuntimeArtAdmission(
            long leaseId, RuntimeArtAdmissionOwnerKind ownerKind) {
        throw new IllegalStateException("runtime-art admission leases are not supported");
    }

    /** Rebinds a rewind-restored owner to the same scalar lease identity. */
    default RuntimeArtAdmissionLease rebindRuntimeArtAdmission(
            long leaseId, RuntimeArtAdmissionOwnerKind ownerKind) {
        throw new IllegalStateException("runtime-art admission leases are not supported");
    }

    /** Consumes one exact production-issued lease. */
    default void consumeRuntimeArtAdmission(
            RuntimeArtAdmissionLease lease,
            RuntimeArtAdmissionOwnerKind ownerKind) {
        throw new IllegalStateException("runtime-art admission leases are not supported");
    }

    /**
     * Loads object art for the specified zone.
     *
     * @param zoneIndex the zone index (-1 for default/non-zone-specific)
     * @throws IOException if loading fails
     */
    void loadArtForZone(int zoneIndex) throws IOException;

    /**
     * Gets a renderer by its key.
     *
     * @param key the renderer key (e.g., "monitor", "spring_vertical")
     * @return the renderer, or null if not found
     */
    PatternSpriteRenderer getRenderer(String key);

    /**
     * Gets a sprite sheet by its key.
     *
     * @param key the sheet key (e.g., "monitor", "spring_vertical")
     * @return the sprite sheet, or null if not found
     */
    ObjectSpriteSheet getSheet(String key);

    /**
     * Gets an animation set by its key.
     *
     * @param key the animation key (e.g., "monitor", "spring", "checkpoint")
     * @return the animation set, or null if not found
     */
    SpriteAnimationSet getAnimations(String key);

    /**
     * Gets the game-agnostic art bundle currently exposed by this provider.
     * Providers may build this from their own registries after zone-specific loading.
     */
    default ObjectArtBundle getArtBundle() {
        return ObjectArtBundle.empty();
    }

    /**
     * Gets zone-specific integer data.
     *
     * @param key       the data key (e.g., "animal_type_a", "animal_type_b")
     * @param zoneIndex the zone index
     * @return the data value, or -1 if not found
     */
    int getZoneData(String key, int zoneIndex);

    /**
     * Gets HUD digit patterns for score/time/ring display.
     *
     * @return the digit patterns array
     */
    Pattern[] getHudDigitPatterns();

    /**
     * Gets HUD text patterns for label display.
     *
     * @return the text patterns array
     */
    Pattern[] getHudTextPatterns();

    /**
     * Gets HUD lives icon patterns.
     *
     * @return the lives icon patterns array
     */
    Pattern[] getHudLivesPatterns();

    /**
     * Gets HUD lives number patterns.
     *
     * @return the lives number patterns array
     */
    Pattern[] getHudLivesNumbers();

    /**
     * Gets the shared static HUD art bundle used by the mapping-driven HUD renderer.
     *
     * @return the static HUD art bundle, or null if the provider still uses legacy HUD wiring
     */
    default HudStaticArt getHudStaticArt() {
        return null;
    }

    /**
     * Gets the ROM-native hex-digit font used by the debug HUD (player/camera coords).
     * Tile layout is ASCII-aligned: digits 0-9 at offsets 0-9 and A-F at offsets
     * 17-22 (see {@link HudRenderManager#setHexDigitsPatternIndex(int)}).
     *
     * @return the hex digit pattern array, or null when not loaded
     */
    default Pattern[] getHudHexDigitPatterns() {
        return null;
    }

    /**
     * Optional palette override used only while drawing the lives HUD.
     * This is for cases where donated life-icon art needs a different palette
     * contract than the rest of the shared in-level palette line.
     */
    default Palette getHudLivesPaletteOverride() {
        return null;
    }

    /**
     * Gets all available renderer keys.
     *
     * @return list of renderer keys
     */
    List<String> getRendererKeys();

    /**
     * Returns the number of regular object patterns cached contiguously from the
     * supplied object-art base. This query must not load or cache art.
     *
     * @return the regular object pattern count
     * @throws UnsupportedOperationException when a provider does not expose the count
     */
    default int getRegularPatternCount() {
        throw new UnsupportedOperationException("Regular object pattern count is not exposed");
    }

    /**
     * Caches all patterns to GPU memory.
     *
     * @param graphicsManager the graphics manager
     * @param baseIndex       the base pattern index
     * @return the next available pattern index after caching
     */
    int ensurePatternsCached(GraphicsManager graphicsManager, int baseIndex);

    /**
     * Checks if the provider has loaded and is ready to render.
     *
     * @return true if ready
     */
    boolean isReady();

    /**
     * Gets the palette line used for HUD text labels (SCORE/TIME/RINGS).
     * Sonic 2 uses palette line 1 (yellow), Sonic 1 uses palette line 0 (yellow).
     *
     * @return palette line (0-3), default 1
     */
    default int getHudTextPaletteLine() {
        return 1;
    }

    /**
     * Gets the palette line used for HUD icon and flash state.
     * This is the alternate palette shown when rings = 0 flashes.
     *
     * @return palette line (0-3), default 0
     */
    default int getHudFlashPaletteLine() {
        return 0;
    }

    /**
     * Registers object art sheets that depend on level tile data (e.g., smashable
     * ground, collapsing ledges, platforms that reuse level patterns).
     * Called after level load when the level's Pattern[] is available.
     *
     * @param level     the loaded level (provides pattern data)
     * @param zoneIndex the current zone index
     */
    default void registerLevelTileArt(Level level, int zoneIndex) {
        // Default no-op — games without level-tile-based object art need not override
    }

    /**
     * Reloads standalone (ROM-compressed) object art for the current act.
     * Called during seamless act transitions to pick up act-specific badnik art
     * (e.g., HCZ Act 1 has Blastoid, Act 2 has Jawz) that differs from the
     * previous act's art plan.
     *
     * @param zoneIndex the current zone index
     */
    default void reloadStandaloneArtForActTransition(int zoneIndex) {
        // Default no-op — only S3K has act-specific standalone art plans
    }
}
