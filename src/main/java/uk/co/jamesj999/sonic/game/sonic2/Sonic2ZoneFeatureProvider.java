package uk.co.jamesj999.sonic.game.sonic2;

import uk.co.jamesj999.sonic.data.Rom;
import uk.co.jamesj999.sonic.game.ZoneFeatureProvider;
import uk.co.jamesj999.sonic.game.sonic2.constants.Sonic2ObjectIds;
import uk.co.jamesj999.sonic.game.sonic2.objects.CPZPylonObjectInstance;
import uk.co.jamesj999.sonic.game.sonic2.objects.Sonic2ObjectRegistry;
import uk.co.jamesj999.sonic.game.sonic2.scroll.Sonic2ZoneConstants;
import uk.co.jamesj999.sonic.level.LevelManager;
import uk.co.jamesj999.sonic.level.bumpers.CNZBumperCollisionManager;
import uk.co.jamesj999.sonic.level.bumpers.CNZBumperDataLoader;
import uk.co.jamesj999.sonic.level.bumpers.CNZBumperPlacementManager;
import uk.co.jamesj999.sonic.level.bumpers.CNZBumperSpawn;
import uk.co.jamesj999.sonic.level.objects.ObjectInstance;
import uk.co.jamesj999.sonic.level.objects.ObjectSpawn;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

import java.io.IOException;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Zone feature provider for Sonic 2.
 * Handles zone-specific mechanics like CNZ bumpers.
 *
 * <p>Current features:
 * <ul>
 *   <li>Casino Night Zone: Bumper collision system</li>
 * </ul>
 *
 * <p>Future features (not yet implemented):
 * <ul>
 *   <li>Aquatic Ruin Zone: Water mechanics</li>
 *   <li>Chemical Plant Zone: Mega Mack (purple liquid)</li>
 *   <li>Oil Ocean Zone: Oil mechanics</li>
 * </ul>
 */
public class Sonic2ZoneFeatureProvider implements ZoneFeatureProvider {
    private static final Logger LOGGER = Logger.getLogger(Sonic2ZoneFeatureProvider.class.getName());

    private CNZBumperCollisionManager cnzBumperManager;
    private ObjectInstance cpzPylon;
    private int currentZone = -1;
    private int currentAct = -1;

    @Override
    public void initZoneFeatures(Rom rom, int zoneIndex, int actIndex, int cameraX) throws IOException {
        // Only reinitialize if zone/act changed
        if (zoneIndex == currentZone && actIndex == currentAct) {
            return;
        }

        reset();
        currentZone = zoneIndex;
        currentAct = actIndex;

        // Initialize CNZ bumpers (ROM zone ID 0x0C)
        if (zoneIndex == Sonic2ZoneConstants.ROM_ZONE_CNZ) {
            initCNZBumpers(rom, actIndex, cameraX);
        }

        // Initialize CPZ pylon (ROM zone ID 0x0D)
        if (zoneIndex == Sonic2ZoneConstants.ROM_ZONE_CPZ) {
            initCPZPylon();
        }

        // TODO: Add water level initialization for ARZ, CPZ, etc.
    }

    private void initCNZBumpers(Rom rom, int actIndex, int cameraX) {
        try {
            CNZBumperDataLoader loader = new CNZBumperDataLoader();
            List<CNZBumperSpawn> bumpers = loader.load(rom, actIndex);

            if (bumpers.isEmpty()) {
                LOGGER.warning("No CNZ bumpers loaded for Act " + (actIndex + 1));
                cnzBumperManager = null;
                return;
            }

            CNZBumperPlacementManager placementManager = new CNZBumperPlacementManager(bumpers);
            placementManager.reset(cameraX);
            cnzBumperManager = new CNZBumperCollisionManager(placementManager);

            LOGGER.info("Initialized CNZ bumper system with " + bumpers.size() + " bumpers");
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Failed to load CNZ bumper data", e);
            cnzBumperManager = null;
        }
    }

    /**
     * Initializes the CPZ pylon decorative object.
     * The pylon is not loaded from level object data - it is created automatically
     * when CPZ loads and added to the dynamic objects list.
     */
    private void initCPZPylon() {
        try {
            // Create a synthetic ObjectSpawn for the pylon
            // Position doesn't matter - pylon uses camera-relative positioning
            // ObjectSpawn(x, y, objectId, subtype, renderFlags, respawnTracked, rawYWord)
            ObjectSpawn spawn = new ObjectSpawn(0, 0, Sonic2ObjectIds.CPZ_PYLON, 0, 0, false, 0);
            cpzPylon = new CPZPylonObjectInstance(spawn, "CPZPylon");

            // Add to ObjectManager's dynamic objects list
            LevelManager levelManager = LevelManager.getInstance();
            if (levelManager != null && levelManager.getObjectManager() != null) {
                levelManager.getObjectManager().addDynamicObject(cpzPylon);
                LOGGER.info("Initialized CPZ pylon");
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to initialize CPZ pylon", e);
            cpzPylon = null;
        }
    }

    @Override
    public void update(AbstractPlayableSprite player, int cameraX, int zoneIndex) {
        if (cnzBumperManager != null && zoneIndex == Sonic2ZoneConstants.ROM_ZONE_CNZ) {
            cnzBumperManager.update(player, cameraX, zoneIndex);
        }
    }

    @Override
    public void reset() {
        cnzBumperManager = null;
        cpzPylon = null;
        currentZone = -1;
        currentAct = -1;
    }

    @Override
    public boolean hasCollisionFeatures(int zoneIndex) {
        return zoneIndex == Sonic2ZoneConstants.ROM_ZONE_CNZ;
    }

    @Override
    public boolean hasWater(int zoneIndex) {
        // Zones with water in Sonic 2 (using ROM zone IDs)
        return zoneIndex == Sonic2ZoneConstants.ROM_ZONE_ARZ ||
               zoneIndex == Sonic2ZoneConstants.ROM_ZONE_CPZ ||  // Mega Mack (purple liquid)
               zoneIndex == Sonic2ZoneConstants.ROM_ZONE_HTZ;    // Lava (acts like water for drowning)
    }

    @Override
    public int getWaterLevel(int zoneIndex, int actIndex) {
        // TODO: Implement actual water levels from ROM data
        // For now, return MAX_VALUE (no water effect)
        return Integer.MAX_VALUE;
    }

    /**
     * Gets the CNZ bumper collision manager.
     * Used by LevelManager for direct access during the transition period.
     *
     * @return the bumper manager, or null if not in CNZ
     */
    public CNZBumperCollisionManager getCNZBumperManager() {
        return cnzBumperManager;
    }
}
