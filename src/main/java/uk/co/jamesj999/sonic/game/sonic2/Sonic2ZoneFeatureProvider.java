package uk.co.jamesj999.sonic.game.sonic2;

import uk.co.jamesj999.sonic.camera.Camera;
import uk.co.jamesj999.sonic.data.Rom;
import uk.co.jamesj999.sonic.data.RomByteReader;
import uk.co.jamesj999.sonic.game.ZoneFeatureProvider;
import uk.co.jamesj999.sonic.game.sonic2.constants.Sonic2Constants;
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
import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.level.objects.ObjectInstance;
import uk.co.jamesj999.sonic.level.objects.ObjectSpawn;
import uk.co.jamesj999.sonic.level.slotmachine.CNZSlotMachineManager;
import uk.co.jamesj999.sonic.level.slotmachine.CNZSlotMachineRenderer;
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
    private CNZSlotMachineManager cnzSlotMachineManager;
    private CNZSlotMachineRenderer cnzSlotMachineRenderer;
    private ObjectInstance cpzPylon;
    private WaterSurfaceManager waterSurfaceManager;
    private int currentZone = -1;
    private int currentAct = -1;

    // Deferred slot machine renders (queued during object phase, rendered after tilemap)
    // Each entry: {worldX, worldY, offsetX, offsetY} - offset values are from cage to display
    private final java.util.List<int[]> pendingSlotRenders = new java.util.ArrayList<>();

    @Override
    public void initZoneFeatures(Rom rom, int zoneIndex, int actIndex, int cameraX) throws IOException {
        // Only reinitialize if zone/act changed
        if (zoneIndex == currentZone && actIndex == currentAct) {
            return;
        }

        reset();
        currentZone = zoneIndex;
        currentAct = actIndex;

        // Initialize CNZ features (ROM zone ID 0x0C)
        if (zoneIndex == Sonic2ZoneConstants.ROM_ZONE_CNZ) {
            initCNZBumpers(rom, actIndex, cameraX);
            initCNZSlotMachine(rom);
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
     * Initializes the CNZ slot machine manager and renderer.
     * The slot machine is a zone-level singleton that handles the slot machine state
     * when linked PointPokey cages (subtype 0x01) are triggered.
     */
    private void initCNZSlotMachine(Rom rom) {
        cnzSlotMachineManager = new CNZSlotMachineManager();

        // Initialize the visual renderer
        GraphicsManager graphicsManager = GraphicsManager.getInstance();
        cnzSlotMachineRenderer = graphicsManager.getCnzSlotMachineRenderer();
        if (cnzSlotMachineRenderer != null && !graphicsManager.isHeadlessMode()) {
            cnzSlotMachineRenderer.init(graphicsManager.getGraphics(), rom);
        }

        // The slot machine shader renders on top of the tilemap, so we don't need
        // to modify the underlying tiles at VRAM 0x0550-0x057F. Whatever garbage
        // or data is there will be covered by the shader when slots are active.
        LOGGER.info("Initialized CNZ slot machine system");
    }

    /**
     * Gets the CNZ slot machine manager for use by PointPokey objects.
     *
     * @return The slot machine manager, or null if not in CNZ
     */
    public CNZSlotMachineManager getSlotMachineManager() {
        return cnzSlotMachineManager;
    }

    /**
     * Gets the CNZ slot machine renderer for visual display.
     *
     * @return The slot machine renderer, or null if not in CNZ or not initialized
     */
    public CNZSlotMachineRenderer getSlotMachineRenderer() {
        return cnzSlotMachineRenderer;
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

    /**
     * Request a slot machine display render at the given world position.
     * Called by PointPokey objects during the object render phase.
     * Actual rendering is deferred to render() which runs after the tilemap.
     *
     * @param worldX  Cage center X position (world coordinates)
     * @param worldY  Cage center Y position (world coordinates)
     * @param offsetX X offset from cage center to slot display center
     * @param offsetY Y offset from cage center to slot display top-left
     */
    public void requestSlotRender(int worldX, int worldY, int offsetX, int offsetY) {
        pendingSlotRenders.add(new int[]{worldX, worldY, offsetX, offsetY});
    }

    @Override
    public void render(Camera camera, int frameCounter) {
        if (waterSurfaceManager != null && waterSurfaceManager.isInitialized()) {
            waterSurfaceManager.render(camera, frameCounter);
        }
        // Note: Slot machine rendering moved to renderAfterForeground() so it appears
        // behind sprites but on top of the corrupted foreground tiles
    }

    @Override
    public void renderAfterForeground(Camera camera) {
        // Queue slot machine display commands (executed during flush, after high-priority foreground but before sprites)
        if (!pendingSlotRenders.isEmpty() && cnzSlotMachineRenderer != null && cnzSlotMachineRenderer.isInitialized()) {
            GraphicsManager graphicsManager = GraphicsManager.getInstance();
            if (!graphicsManager.isHeadlessMode() && cnzSlotMachineManager != null) {
                Integer paletteTextureId = graphicsManager.getCombinedPaletteTextureId();
                if (paletteTextureId != null) {
                    for (int[] pos : pendingSlotRenders) {
                        int screenX = pos[0] - camera.getX();
                        int screenY = pos[1] - camera.getY();
                        int offsetX = pos[2];
                        int offsetY = pos[3];
                        GLCommand cmd = cnzSlotMachineRenderer.createRenderCommand(
                                cnzSlotMachineManager,
                                screenX,
                                screenY,
                                paletteTextureId,
                                offsetX,
                                offsetY
                        );
                        if (cmd != null) {
                            graphicsManager.registerCommand(cmd);
                        }
                    }
                }
            }
            pendingSlotRenders.clear();
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
        if (zoneIndex == Sonic2ZoneConstants.ROM_ZONE_CNZ) {
            if (cnzBumperManager != null) {
                cnzBumperManager.update(player, cameraX, zoneIndex);
            }
            if (cnzSlotMachineManager != null) {
                cnzSlotMachineManager.update();
            }
        }
    }

    @Override
    public void reset() {
        cnzBumperManager = null;
        cnzSlotMachineManager = null;
        cnzSlotMachineRenderer = null;
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
