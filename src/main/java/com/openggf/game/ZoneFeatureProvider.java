package com.openggf.game;

import com.openggf.camera.Camera;
import com.openggf.data.Rom;
import com.openggf.graphics.GraphicsManager;
import com.openggf.game.render.AdvancedRenderModeController;
import com.openggf.game.render.SpecialRenderEffectRegistry;
import com.openggf.physics.SensorResult;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.io.IOException;

/**
 * Interface for zone-specific features that require special initialization.
 *
 * <p>Examples of zone features:
 * <ul>
 *   <li>Casino Night Zone: Bumpers, flippers</li>
 *   <li>Aquatic Ruin Zone: Water level and underwater mechanics</li>
 *   <li>Oil Ocean Zone: Oil mechanics</li>
 *   <li>Labyrinth Zone (Sonic 1): Water level, currents</li>
 * </ul>
 *
 * <p>Zone features are separate from scroll handlers and object registries.
 * They provide collision systems and gameplay mechanics specific to certain zones.
 */
@com.openggf.game.ModApi
public interface ZoneFeatureProvider {
    /**
     * Called when entering a zone to initialize zone-specific features.
     *
     * @param rom the ROM for loading data
     * @param zoneIndex the zone being entered
     * @param actIndex the act within the zone
     * @param cameraX the camera X position
     * @throws IOException if initialization fails
     */
    void initZoneFeatures(Rom rom, int zoneIndex, int actIndex, int cameraX) throws IOException;

    /**
     * Updates zone features each frame.
     *
     * @param player the player sprite (may be null)
     * @param cameraX the camera X position
     * @param zoneIndex the current zone
     */
    void update(AbstractPlayableSprite player, int cameraX, int zoneIndex);

    /**
     * Resets all zone feature managers.
     * Called when leaving a zone or reloading.
     */
    void reset();

    /**
     * Checks if this zone has special collision features (bumpers, etc.).
     *
     * @param zoneIndex the zone to check
     * @return true if the zone has collision features
     */
    boolean hasCollisionFeatures(int zoneIndex);

    /**
     * Checks if this zone has water mechanics.
     *
     * @param zoneIndex the zone to check
     * @return true if the zone has water
     */
    boolean hasWater(int zoneIndex);

    /**
     * Gets the water level for a zone (if applicable).
     *
     * @param zoneIndex the zone
     * @param actIndex the act
     * @return the water level Y position, or Integer.MAX_VALUE if no water
     */
    int getWaterLevel(int zoneIndex, int actIndex);

    /**
     * Renders zone-specific visual features (e.g., water surface sprites).
     * Called during the draw phase after level rendering and all sprites.
     *
     * @param camera the camera for screen coordinates
     * @param frameCounter current frame number for animation
     */
    void render(Camera camera, int frameCounter);

    /**
     * Updates zone features that must run BEFORE player physics.
     *
     * <p>In the ROM, certain zone features (LZ water slides, wind tunnels) run before
     * Sonic's movement code ({@code ExecuteObjects}). These features set flags like
     * {@code f_slidemode} and overwrite {@code obInertia} so that {@code Sonic_Move}
     * sees the correct state when it runs.
     *
     * <p>Implementations should move any logic that modifies player velocity or
     * movement flags here. The default implementation does nothing, so existing
     * providers (S2, S3K) are unaffected.
     *
     * @param player the player sprite (may be null)
     * @param cameraX the camera X position
     * @param zoneIndex the current zone
     */
    default void updatePrePhysics(AbstractPlayableSprite player, int cameraX, int zoneIndex) {
        // Default implementation does nothing
    }

    /**
     * Returns true while a ROM water/wind tunnel mode flag is active.
     *
     * <p>S1 Obj0A consumes {@code f_wtunnelmode} directly while updating
     * drowning bubbles, advancing {@code drown_origX} before applying the
     * wobble table.
     */
    default boolean isWaterTunnelActive() {
        return false;
    }

    /**
     * Updates zone features that must observe a playable after its movement
     * slot, before the next playable's CPU/controller slot runs.
     *
     * <p>This is narrower than {@link #updatePrePhysics(AbstractPlayableSprite, int, int)}
     * and is intended for ROM pseudo-objects that consume the just-moved
     * player state but also publish velocity/status changes that later player
     * slots can read in the same object pass.
     *
     * @param player the playable sprite whose movement slot just finished
     * @param cameraX the camera X position
     * @param zoneIndex the current zone
     */
    default void updateAfterPlayablePhysics(AbstractPlayableSprite player, int cameraX, int zoneIndex) {
        // Default implementation does nothing
    }

    /**
     * Updates zone features that run after the complete object pass.
     *
     * <p>The ROM places some level-event routines after {@code Process_Sprites},
     * rather than immediately after an individual playable's movement slot.
     * The default implementation does nothing so providers without such a
     * post-object routine retain their existing ordering.
     *
     * @param player the playable sprite whose post-object state is being observed
     * @param cameraX the camera X position
     * @param zoneIndex the current zone
     */
    default void updateAfterObjectExecution(AbstractPlayableSprite player, int cameraX, int zoneIndex) {
        // Default implementation does nothing
    }

    /** Screen-fixed nametable replacing the lower foreground, or null for normal Plane A. */
    default com.openggf.graphics.ForegroundWindow foregroundWindow() {
        return null;
    }

    /**
     * Queues render commands for zone features that should appear after foreground tiles
     * but before sprites (e.g., slot machine display that covers corrupted tiles).
     * Called after high-priority foreground tilemap pass but before sprite passes.
     *
     * @param camera the camera for screen coordinates
     */
    default void renderAfterForeground(Camera camera) {
        // Default implementation does nothing
    }

    /**
     * Queues render commands for zone features that should appear after the background pass
     * but before any foreground tiles (for example, AIZ2's split-rendered bombership strip).
     *
     * @param camera the camera for screen coordinates
     * @param frameCounter current frame number for animation
     */
    default void renderAfterBackground(Camera camera, int frameCounter) {
        // Default implementation does nothing
    }

    /**
     * Registers staged special render effects for the current zone/act.
     * Default implementation does nothing.
     *
     * @param registry registry to register effects with
     * @param zoneIndex current feature zone id
     * @param actIndex current feature act id
     */
    default void registerSpecialRenderEffects(SpecialRenderEffectRegistry registry, int zoneIndex, int actIndex) {
        // Default implementation does nothing
    }

    /**
     * Registers advanced render-mode contributors for the current zone/act.
     * Default implementation does nothing.
     *
     * @param controller controller to register modes with
     * @param zoneIndex current feature zone id
     * @param actIndex current feature act id
     */
    default void registerAdvancedRenderModes(AdvancedRenderModeController controller, int zoneIndex, int actIndex) {
        // Default implementation does nothing
    }

    /**
     * Ensures zone feature patterns are cached in the graphics manager.
     * Called during level initialization.
     *
     * @param graphicsManager the graphics manager
     * @param baseIndex starting pattern index
     * @return next available pattern index after caching
     */
    int ensurePatternsCached(GraphicsManager graphicsManager, int baseIndex);

    /**
     * Whether the background layer wraps horizontally at VDP plane width (512px).
     * Sonic 2 uses a VDP plane redraw model where BG wraps at 512px.
     * Other games use full-width BG data.
     *
     * @return true if BG should wrap at VDP plane width
     */
    default boolean bgWrapsHorizontally() {
        return false;
    }

    /**
     * Whether the FOREGROUND (Plane A) layer renders as a persistent $200-wide
     * (VDP plane width, 64 cells) nametable ring for the current zone state.
     * Default false (the FG is a single full-width tilemap by world X).
     *
     * <p>S3K AIZ2's post-bombing ship loop ({@code AIZ2_DoShipLoop}, s3.asm:70956)
     * subtracts {@code $200} from {@code Camera_X_pos} each loop; because {@code $200}
     * equals the Plane A nametable width, the forest columns drawn at the camera's
     * leading edge ({@code 0x46Cx}) reappear at the wrapped {@code 0x44Cx} on
     * hardware. The engine samples the flat FG layout (a canopy gap at the wrapped
     * position), so the canopy dropped each loop. When this returns true, the FG
     * tilemap becomes a persistent $200 ring whose leading-edge column is drawn
     * incrementally as the camera advances (natural reveal) and whose cells are
     * retained across the {@code -$200} wrap (seamless loop) — the engine analog
     * of {@code DrawTilesAsYouMove} into Plane A (s3.asm:70638,70680). The player
     * {@code x_pos} also wraps {@code $200}, so collision parity is preserved.
     *
     * @return true if the FG should render as a $200 persistent nametable ring
     */
    default boolean foregroundWrapsHorizontally() {
        return false;
    }

    /**
     * World-X distance subtracted from the camera on this frame while a persistent
     * foreground nametable ring is active. A non-zero value tells the renderer that
     * a large backwards camera delta is an intentional level-repeat wrap, not a
     * rewind seek or teleport, so the VDP-equivalent ring contents must be retained.
     */
    default int foregroundWorldWrapOffset() {
        return 0;
    }

    /**
     * Whether this zone should select a full-width background tilemap window
     * while still using a per-line scrolled background path.
     */
    default boolean useFullWidthBackgroundTilemapWindow(int zoneIndex,
                                                        int actIndex,
                                                        int bgCameraX,
                                                        int cachedBgContiguousWidthPx) {
        return false;
    }

    /**
     * Whether wrapped background tilemap builds should emulate the ROM's raw
     * layout-row pointer overflow instead of wrapping X within the same row.
     */
    default boolean useLinearBackgroundLayoutOverflow(int zoneIndex) {
        return false;
    }

    /**
     * Base Y (in BG-layout pixels) at which the background loops a fixed-height
     * band instead of scrolling the full BG layout, or a negative value (default)
     * when no loop band is active.
     *
     * <p>S3K CNZ's miniboss uses this for {@code CNZ1BGE_Boss}
     * (docs/skdisasm/sonic3k.asm:107498-107507), which fills Plane B from layout
     * Y={@code $200} for {@code $10} (16) chunks and loops that 256px band via the
     * VDP vertical scroll. Anchoring/clamping the loop to that band keeps the
     * room floor (which sits below the band) out of the looping scroll.
     */
    default int backgroundLoopBandBaseY(int zoneIndex, int actIndex) {
        return -1;
    }

    /**
     * Whether the intro ocean phase is currently active (e.g. AIZ intro in S3K).
     * When active, the BG plane wraps at VDP width instead of full layout width.
     *
     * @param zoneIndex the current zone
     * @param actIndex the current act
     * @return true if an intro ocean phase is active
     */
    default boolean isIntroOceanPhaseActive(int zoneIndex, int actIndex) {
        return false;
    }

    /**
     * Returns the VDP nametable base tile for per-line-scroll BG wrapping.
     * During intro sequences with per-line scroll, this controls which 64-tile
     * window of the BG tilemap is visible, matching the VDP ring buffer position.
     *
     * @param zoneIndex the current zone
     * @param actIndex the current act
     * @param cameraX the camera X position
     * @param tilemapWidthTiles the BG tilemap width in tiles
     * @return the nametable base tile (0.0 by default)
     */
    default float getVdpNametableBase(int zoneIndex, int actIndex, int cameraX, int tilemapWidthTiles) {
        return 0.0f;
    }

    /**
     * Whether the initial title card should be suppressed for the given zone/act.
     * Used for intro sequences (e.g. AIZ intro in S3K) that should not show a title card.
     *
     * @param zoneIndex the current zone
     * @param actIndex the current act
     * @return true if the title card should be suppressed
     */
    default boolean shouldSuppressInitialTitleCard(int zoneIndex, int actIndex) {
        return false;
    }

    /**
     * Whether an airborne terrain probe that is exactly touching the surface
     * (distance 0) should count as a landing for the current zone feature state.
     *
     * <p>Default false to preserve the engine's normal "must penetrate floor"
     * air-landing rule.
     */
    default boolean shouldTreatZeroDistanceAirLandingAsGround(AbstractPlayableSprite player,
                                                              SensorResult support) {
        return false;
    }

    /**
     * Whether the HUD should be hidden for the given zone/act.
     * Used during intro cinematics (e.g. AIZ intro in S3K) where the HUD
     * should not be visible until gameplay begins.
     *
     * @param zoneIndex the current zone
     * @param actIndex the current act
     * @return true if the HUD should be hidden
     */
    default boolean shouldSuppressHud(int zoneIndex, int actIndex) {
        return false;
    }

    /**
     * Whether the shared global oscillation table should advance this frame for
     * the current zone/act.
     *
     * <p>Only override this when the ROM loop for a specific sequence genuinely
     * skips the oscillation update. Cinematics such as S3K AIZ1 still advance
     * the table every level frame even while {@code Level_started_flag} is
     * cleared.
     *
     * @param zoneIndex the current feature zone
     * @param actIndex the current feature act
     * @return true if the oscillation table should advance
     */
    default boolean shouldAdvanceGlobalOscillation(int zoneIndex, int actIndex) {
        return true;
    }

    /**
     * Whether the backdrop colour should be forced to black regardless of the
     * level's own backdrop colour.  Used for zones (e.g. MCZ in Sonic 2) whose
     * background is drawn entirely by sprites/tiles with no sky visible.
     *
     * @return true if the backdrop should be forced to black
     */
    default boolean isForceBlackBackdrop() {
        return false;
    }

    /**
     * Returns the current water routine index for checkpoint save/restore.
     * Only meaningful for games with dynamic water routines (e.g., Sonic 1 LZ).
     *
     * @return the water routine index, or 0 if not applicable
     */
    default int getWaterRoutine() {
        return 0;
    }

    /**
     * Sets the water routine index after checkpoint restore.
     * Only meaningful for games with dynamic water routines (e.g., Sonic 1 LZ).
     *
     * @param routine the water routine index to restore
     */
    default void setWaterRoutine(int routine) {
        // No-op by default
    }

    /**
     * Returns a zone-specific renderer for custom visual effects (e.g. CNZ slot machine).
     * Games/zones without custom renderers return {@link ZoneFeatureRenderer#NONE}.
     *
     * @return the zone feature renderer, never null
     */
    default ZoneFeatureRenderer getFeatureRenderer() {
        return ZoneFeatureRenderer.NONE;
    }

    /**
     * Whether the underwater palette split should be suppressed for this zone/act.
     * Used when a zone has water but its underwater palette is not yet active
     * (e.g. HCZ Act 1 before the first water-height switch).
     *
     * @param zoneIndex the current zone
     * @param actIndex the current act
     * @return true if the underwater palette should be suppressed
     */
    default boolean shouldSuppressUnderwaterPalette(int zoneIndex, int actIndex) {
        return false;
    }

    /**
     * Returns the pixel offset to apply to the water level when calculating the
     * underwater palette split line. The default S2/S3K value is -8 (split starts
     * 8px above water so the surface strip is tinted). S1 and zones with ROM-driven
     * water surface rendering use 0.
     *
     * @param zoneIndex the current zone
     * @param actIndex the current act
     * @return the waterline offset in pixels
     */
    default float getWaterlineOffset(int zoneIndex, int actIndex) {
        return -8.0f;
    }

    /**
     * Whether this zone uses VDP sprite-table-order (SAT) masking for draw ordering.
     * When true, sprites are drawn in SAT bucket order rather than painter order,
     * matching the hardware sprite priority model used by stages like the Gumball
     * bonus stage.
     *
     * @param zoneIndex the current zone
     * @return true if SAT masking should be used
     */
    default boolean useSpriteSatMasking(int zoneIndex) {
        return false;
    }
}
