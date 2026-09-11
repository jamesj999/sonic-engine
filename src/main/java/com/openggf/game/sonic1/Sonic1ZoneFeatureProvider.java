package com.openggf.game.sonic1;

import com.openggf.camera.Camera;
import com.openggf.data.Rom;
import com.openggf.game.sonic1.events.Sonic1LZWaterEvents;
import com.openggf.game.ZoneFeatureProvider;
import com.openggf.game.sonic1.constants.Sonic1Constants;
import com.openggf.game.sonic1.scroll.Sonic1ZoneConstants;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.LevelManager;
import com.openggf.level.WaterSystem;
import com.openggf.physics.SensorResult;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;
import com.openggf.game.GameServices;

/**
 * Zone feature provider for Sonic 1.
 * Handles zone-specific mechanics, primarily water in Labyrinth Zone
 * and Scrap Brain Zone Act 3 (which reuses LZ water mechanics).
 *
 * <p>Water zones:
 * <ul>
 *   <li>Labyrinth Zone (zone ID 0x01, acts 0-2): Full water system with
 *       dynamic water levels driven by {@link Sonic1LZWaterEvents}.</li>
 *   <li>Scrap Brain Zone Act 3 (zone ID 0x05, act 2): Reuses LZ water
 *       mechanics with its own water heights and palette.</li>
 * </ul>
 */
public class Sonic1ZoneFeatureProvider implements ZoneFeatureProvider {
    private static final Logger LOGGER = Logger.getLogger(Sonic1ZoneFeatureProvider.class.getName());

    private Sonic1LZWaterEvents waterEvents;
    private Sonic1WaterSurfaceManager waterSurfaceManager;
    private int currentZone = -1;
    private int currentAct = -1;
    private boolean isSBZ3 = false;

    @Override
    public void initZoneFeatures(Rom rom, int zoneIndex, int actIndex, int cameraX) throws IOException {
        // Only reinitialize if zone/act changed
        if (zoneIndex == currentZone && actIndex == currentAct) {
            return;
        }

        reset();
        currentZone = zoneIndex;
        currentAct = actIndex;
        isSBZ3 = (zoneIndex == Sonic1Constants.ZONE_SBZ && actIndex == 2);

        if (hasWater(zoneIndex)) {
            // Water loading is handled by LevelManager.initWater() via
            // Sonic1WaterDataProvider (provider-based path). This provider
            // no longer calls WaterSystem.loadForLevelS1() directly.

            // Create the water event state machine for dynamic water levels
            waterEvents = new Sonic1LZWaterEvents();
            if (isSBZ3) {
                // SBZ3 uses its own event handler but shares the LZ water system.
                // Init with SBZ zone/act so WaterSystem lookups use the right key.
                waterEvents.init(zoneIndex, actIndex);
            } else {
                waterEvents.init(zoneIndex, actIndex);
            }

            // ROM spawns WaterSurface objects in LZ (Level_ChkWater in sonic.asm).
            // SBZ3 reuses LZ layout and water, so it also needs the surface sprite.
            if (zoneIndex == Sonic1Constants.ZONE_LZ || isSBZ3) {
                try {
                    waterSurfaceManager = new Sonic1WaterSurfaceManager(rom, zoneIndex, actIndex);
                } catch (Exception e) {
                    LOGGER.log(Level.SEVERE, "Failed to initialize S1 water surface manager", e);
                    waterSurfaceManager = null;
                }
            }

            LOGGER.info(String.format("Initialized S1 water features for zone %d act %d (SBZ3=%s)",
                    zoneIndex, actIndex, isSBZ3));
        }
    }

    /**
     * Pre-physics update: wind tunnels and water slides.
     *
     * <p>ROM order (sonic.asm:3042-3044):
     * <ol>
     *   <li>{@code LZWaterFeatures} — sets {@code f_slidemode} and {@code obInertia}</li>
     *   <li>{@code ExecuteObjects} — runs Sonic's movement (sees slide flag)</li>
     * </ol>
     * Wind tunnels and water slides must run before player physics so that
     * {@code Sonic_Move} / {@code Sonic_RollSpeed} see the correct sliding state
     * and gSpeed when they execute.
     */
    @Override
    public void updatePrePhysics(AbstractPlayableSprite player, int cameraX, int zoneIndex) {
        if (!hasWater(zoneIndex)) {
            return;
        }

        boolean playerAlive = player != null && !player.getDead();

        if (waterEvents != null && playerAlive) {
            // 1. Wind tunnels (horizontal underwater currents)
            if (isSBZ3) {
                waterEvents.updateWindTunnelsSBZ3();
            } else {
                waterEvents.updateWindTunnels();
            }

            // 2. Water slides (chunk-based slide mechanic)
            // ROM LZWaterSlides samples v_lvllayout from obX/obY only
            // (docs/s1disasm/_inc/LZWaterFeatures.asm:392-406). In this engine
            // those ROM fields map to centre coordinates, not sprite bounds.
            int chunkIdAtPlayer = -1;
            LevelManager levelManager = GameServices.levelOrNull();
            if (levelManager != null) {
                chunkIdAtPlayer = levelManager.getBlockIdAt(player.getCentreX(), player.getCentreY());
            }
            waterEvents.checkWaterSlide(chunkIdAtPlayer);
        }
    }

    @Override
    public void update(AbstractPlayableSprite player, int cameraX, int zoneIndex) {
        if (!hasWater(zoneIndex)) {
            return;
        }

        // 3. Dynamic water level state machine.
        // Water movement (MoveWater) is handled by LevelManager.update() which calls
        // WaterSystem.update() once per frame BEFORE this method, matching ROM order
        // (MoveWater before DynWaterHeight). Do NOT call WaterSystem.update() here
        // to avoid double movement (2px/frame instead of 1px/frame).

        // Run per-act water event state machine (DynWaterHeight — sets next target)
        if (waterEvents != null) {
            if (isSBZ3) {
                waterEvents.updateSBZ3();
            } else {
                waterEvents.update();
            }
        }

        // Note: player.updateWaterState() is called by LevelManager using
        // getVisualWaterLevelY(), matching the ROM's use of v_waterpos1 for
        // underwater detection. We don't call it here to avoid double-application.
    }

    @Override
    public void render(Camera camera, int frameCounter) {
        if (waterSurfaceManager != null && waterSurfaceManager.isInitialized()) {
            waterSurfaceManager.render(camera, frameCounter);
        }
    }

    @Override
    public void renderAfterForeground(Camera camera) {
        // No post-foreground rendering needed for S1
    }

    @Override
    public int ensurePatternsCached(GraphicsManager graphicsManager, int baseIndex) {
        if (waterSurfaceManager != null) {
            return waterSurfaceManager.ensurePatternsCached(graphicsManager, baseIndex);
        }
        return baseIndex;
    }

    @Override
    public void reset() {
        waterEvents = null;
        waterSurfaceManager = null;
        currentZone = -1;
        currentAct = -1;
        isSBZ3 = false;
    }

    @Override
    public boolean hasCollisionFeatures(int zoneIndex) {
        // S1 has no zone-specific collision features (like CNZ bumpers)
        return false;
    }

    @Override
    public boolean hasWater(int zoneIndex) {
        // LZ (zone 0x01) has water in all acts
        if (zoneIndex == Sonic1Constants.ZONE_LZ) {
            return true;
        }
        // SBZ Act 3 (zone 0x05, act 2) reuses LZ water mechanics.
        // We check against currentAct because hasWater() only receives zoneIndex.
        if (zoneIndex == Sonic1Constants.ZONE_SBZ && currentAct == 2) {
            return true;
        }
        return false;
    }

    @Override
    public boolean bgWrapsHorizontally() {
        return true;
    }

    @Override
    public int getWaterLevel(int zoneIndex, int actIndex) {
        if (hasWater(zoneIndex)) {
            return GameServices.water().getWaterLevelY(zoneIndex, actIndex);
        }
        return Integer.MAX_VALUE;
    }

    @Override
    public boolean shouldSuppressInitialTitleCard(int zoneIndex, int actIndex) {
        return zoneIndex == Sonic1ZoneConstants.ZONE_ENDING;
    }

    @Override
    public boolean shouldSuppressHud(int zoneIndex, int actIndex) {
        return zoneIndex == Sonic1ZoneConstants.ZONE_ENDING;
    }

    /**
     * Sets the LZ wind tunnel disable flag (ROM: f_wtunnelallow) when water events
     * are active. No-op outside water-enabled zones.
     */
    public void setWindTunnelDisabled(boolean disabled) {
        if (waterEvents != null) {
            waterEvents.setWindTunnelDisabled(disabled);
        }
    }

    /**
     * Returns the current LZ wind tunnel disable flag (ROM: f_wtunnelallow).
     */
    public boolean isWindTunnelDisabled() {
        return waterEvents != null && waterEvents.isWindTunnelDisabled();
    }

    @Override
    public boolean isWaterTunnelActive() {
        return waterEvents != null && waterEvents.isWindTunnelActive();
    }

    /**
     * Returns the LZ water events instance, or null if not in a water zone.
     * Used by credits demo to restore lamppost water routine state.
     */
    public Sonic1LZWaterEvents getWaterEvents() {
        return waterEvents;
    }

    @Override
    public boolean shouldTreatZeroDistanceAirLandingAsGround(AbstractPlayableSprite player,
                                                             SensorResult support) {
        return waterEvents != null && waterEvents.allowsFlatZeroDistanceLanding(player, support);
    }

    @Override
    public int getWaterRoutine() {
        return waterEvents != null ? waterEvents.getWaterRoutine() : 0;
    }

    @Override
    public void setWaterRoutine(int routine) {
        if (waterEvents != null) {
            waterEvents.setWaterRoutine(routine);
        }
    }
}
