package uk.co.jamesj999.sonic.game.sonic2;

import uk.co.jamesj999.sonic.camera.Camera;
import uk.co.jamesj999.sonic.data.Rom;
import uk.co.jamesj999.sonic.data.RomByteReader;
import uk.co.jamesj999.sonic.game.ZoneFeatureProvider;
import uk.co.jamesj999.sonic.game.sonic2.constants.Sonic2ObjectIds;
import uk.co.jamesj999.sonic.game.sonic2.objects.CPZPylonObjectInstance;
import uk.co.jamesj999.sonic.game.sonic2.objects.Sonic2ObjectRegistry;
import uk.co.jamesj999.sonic.game.sonic2.scroll.Sonic2ZoneConstants;
import uk.co.jamesj999.sonic.graphics.GraphicsManager;
import uk.co.jamesj999.sonic.level.LevelManager;
import uk.co.jamesj999.sonic.level.Pattern;
import uk.co.jamesj999.sonic.level.bumpers.CNZBumperDataLoader;
import uk.co.jamesj999.sonic.level.bumpers.CNZBumperManager;
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

    private CNZBumperManager cnzBumperManager;
    private ObjectInstance cpzPylon;
    private WaterSurfaceManager waterSurfaceManager;
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

        // Initialize water surface manager for zones with water (CPZ Act 2, ARZ)
        if (hasWater(zoneIndex)) {
            initWaterSurfaceManager(rom, zoneIndex, actIndex);
        }
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

            cnzBumperManager = new CNZBumperManager(bumpers);
            cnzBumperManager.reset(cameraX);

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

    /**
     * Initialize water surface manager for zones with water (CPZ, ARZ).
     * Loads water surface patterns from ROM and creates the WaterSurfaceManager.
     *
     * @param rom The ROM to load patterns from
     * @param zoneIndex The current zone index
     * @param actIndex The current act index
     */
    private void initWaterSurfaceManager(Rom rom, int zoneIndex, int actIndex) {
        try {
            // Create a Sonic2ObjectArt instance to load water surface patterns
            RomByteReader reader = RomByteReader.fromRom(rom);
            Sonic2ObjectArt objectArt = new Sonic2ObjectArt(rom, reader);

            // Load water surface patterns
            Pattern[] cpzPatterns = objectArt.loadWaterSurfaceCPZPatterns();
            Pattern[] arzPatterns = objectArt.loadWaterSurfaceARZPatterns();

            LOGGER.info(String.format("Loaded water surface patterns: CPZ=%d, ARZ=%d",
                    cpzPatterns.length, arzPatterns.length));

            // Create water surface manager
            waterSurfaceManager = new WaterSurfaceManager(zoneIndex, actIndex, cpzPatterns, arzPatterns);

            LOGGER.info("Water surface manager initialized for zone " + zoneIndex + " act " + actIndex);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to initialize water surface manager", e);
            waterSurfaceManager = null;
        }
    }

    @Override
    public void render(Camera camera, int frameCounter) {
        if (waterSurfaceManager != null && waterSurfaceManager.isInitialized()) {
            waterSurfaceManager.render(camera, frameCounter);
        }
    }

    @Override
    public int ensurePatternsCached(GraphicsManager graphicsManager, int baseIndex) {
        if (waterSurfaceManager != null) {
            return waterSurfaceManager.ensurePatternsCached(graphicsManager, baseIndex);
        }
        return baseIndex;
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
        waterSurfaceManager = null;
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

    // Intentionally no public accessors for bumper system.
}
