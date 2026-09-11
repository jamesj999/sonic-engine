package com.openggf.game.sonic2;

import com.openggf.camera.Camera;
import com.openggf.data.Rom;
import com.openggf.data.RomByteReader;
import com.openggf.game.ZoneFeatureProvider;
import com.openggf.game.ZoneFeatureRenderer;
import com.openggf.game.render.SpecialRenderEffect;
import com.openggf.game.render.SpecialRenderEffectContext;
import com.openggf.game.render.SpecialRenderEffectRegistry;
import com.openggf.game.render.SpecialRenderEffectStage;
import com.openggf.game.LevelEventProvider;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.game.sonic2.constants.Sonic2AnimationIds;
import com.openggf.game.sonic2.objects.CPZPylonObjectInstance;
import com.openggf.game.sonic2.render.HtzEarthquakeBgOverlayEffect;
import com.openggf.game.sonic2.scroll.Sonic2ZoneConstants;
import com.openggf.graphics.GraphicsManager;
import com.openggf.graphics.ShaderProgram;
import com.openggf.level.LevelManager;
import com.openggf.level.Pattern;
import com.openggf.level.WaterSystem;
import com.openggf.game.sonic2.bumpers.CNZBumperDataLoader;
import com.openggf.game.sonic2.bumpers.CNZBumperManager;
import com.openggf.game.sonic2.bumpers.CNZBumperSpawn;
import com.openggf.game.sonic2.slotmachine.CNZSlotMachineManager;
import com.openggf.game.sonic2.slotmachine.CNZSlotMachineRenderer;
import com.openggf.graphics.GLCommandable;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.io.IOException;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import com.openggf.game.GameServices;

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
    private static final String CNZ_SLOTS_SHADER_PATH = "shaders/shader_cnz_slots.glsl";

    private CNZBumperManager cnzBumperManager;
    private CNZSlotMachineManager cnzSlotMachineManager;
    private CNZSlotMachineRenderer cnzSlotMachineRenderer;
    private ShaderProgram cnzSlotsShaderProgram;
    private ObjectInstance cpzPylon;
    private WaterSurfaceManager waterSurfaceManager;
    private int currentZone = -1;
    private int currentAct = -1;
    private boolean wfzWindTunnelActive;
    private boolean wfzWindTunnelHolding;

    // Deferred slot machine renders (queued during object phase, rendered after tilemap)
    // Each entry: {worldX, worldY, offsetX, offsetY} - offset values are from cage to display
    private static final int SLOT_RENDER_STRIDE = 4;
    private int[] pendingSlotRenders = new int[16];
    private int pendingSlotRenderCount;
    private final SpecialRenderEffect cnzSlotOverlayEffect = new SpecialRenderEffect() {
        @Override
        public SpecialRenderEffectStage stage() {
            return SpecialRenderEffectStage.AFTER_FOREGROUND;
        }

        @Override
        public void render(SpecialRenderEffectContext context) {
            renderCnzSlotOverlay(context.camera());
        }
    };
    private final SpecialRenderEffect htzEarthquakeBgOverlayEffect = new HtzEarthquakeBgOverlayEffect();
    private final SpecialRenderEffect waterSurfaceEffect = new SpecialRenderEffect() {
        @Override
        public SpecialRenderEffectStage stage() {
            return SpecialRenderEffectStage.AFTER_SPRITES;
        }

        @Override
        public void render(SpecialRenderEffectContext context) {
            renderWaterSurface(context.camera(), context.frameCounter());
        }
    };

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

        // Initialize the visual renderer (owned by this provider, not GraphicsManager)
        if (cnzSlotMachineRenderer == null) {
            cnzSlotMachineRenderer = new CNZSlotMachineRenderer();
        }
        GraphicsManager graphicsManager = GameServices.graphics();
        if (!graphicsManager.isHeadlessMode()) {
            // Lazily initialize the shader
            if (cnzSlotsShaderProgram == null && graphicsManager.isGlInitialized()) {
                try {
                    cnzSlotsShaderProgram = new ShaderProgram(
                            ShaderProgram.FULLSCREEN_VERTEX_SHADER, CNZ_SLOTS_SHADER_PATH);
                    cnzSlotMachineRenderer.setShader(cnzSlotsShaderProgram);
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Failed to load CNZ slot shader", e);
                }
            }
            cnzSlotMachineRenderer.init(rom);
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

    @Override
    public ZoneFeatureRenderer getFeatureRenderer() {
        if (cnzSlotMachineRenderer != null) {
            return cnzSlotMachineRenderer;
        }
        return ZoneFeatureRenderer.NONE;
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
            LevelManager levelManager = GameServices.level();
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
        int offset = pendingSlotRenderCount * SLOT_RENDER_STRIDE;
        if (offset + SLOT_RENDER_STRIDE > pendingSlotRenders.length) {
            pendingSlotRenders = java.util.Arrays.copyOf(pendingSlotRenders, pendingSlotRenders.length * 2);
        }
        pendingSlotRenders[offset] = worldX;
        pendingSlotRenders[offset + 1] = worldY;
        pendingSlotRenders[offset + 2] = offsetX;
        pendingSlotRenders[offset + 3] = offsetY;
        pendingSlotRenderCount++;
    }

    int[] pendingSlotRenderStorage() { return pendingSlotRenders; }
    int pendingSlotRenderCount() { return pendingSlotRenderCount; }
    void clearPendingSlotRenders() { pendingSlotRenderCount = 0; }

    @Override
    public void render(Camera camera, int frameCounter) {
        // Water surfaces now render through the staged special render effect registry.
    }

    @Override
    public void renderAfterForeground(Camera camera) {
        // Slot rendering is now dispatched through the staged special render effect
        // registry. This remains as a compatibility no-op for the provider hook.
    }

    @Override
    public void registerSpecialRenderEffects(SpecialRenderEffectRegistry registry, int zoneIndex, int actIndex) {
        if (zoneIndex == Sonic2ZoneConstants.ROM_ZONE_CNZ) {
            registry.register(cnzSlotOverlayEffect);
        }
        if (zoneIndex == Sonic2ZoneConstants.ROM_ZONE_HTZ) {
            registry.register(htzEarthquakeBgOverlayEffect);
        }
        if ((zoneIndex == Sonic2ZoneConstants.ROM_ZONE_CPZ && actIndex == 1)
                || zoneIndex == Sonic2ZoneConstants.ROM_ZONE_ARZ) {
            registry.register(waterSurfaceEffect);
        }
    }

    private void renderWaterSurface(Camera camera, int frameCounter) {
        if (waterSurfaceManager != null && waterSurfaceManager.isInitialized()) {
            waterSurfaceManager.render(camera, frameCounter);
        }
    }

    private void renderCnzSlotOverlay(Camera camera) {
        if (pendingSlotRenderCount == 0) {
            return;
        }
        try {
            if (cnzSlotMachineRenderer != null && cnzSlotMachineRenderer.isInitialized()) {
            GraphicsManager graphicsManager = GameServices.graphics();
            if (!graphicsManager.isHeadlessMode() && cnzSlotMachineManager != null) {
                Integer paletteTextureId = graphicsManager.getCombinedPaletteTextureId();
                if (paletteTextureId != null) {
                    for (int i = 0; i < pendingSlotRenderCount; i++) {
                        int pos = i * SLOT_RENDER_STRIDE;
                        int screenX = pendingSlotRenders[pos] - camera.getX();
                        int screenY = pendingSlotRenders[pos + 1] - camera.getY();
                        int offsetX = pendingSlotRenders[pos + 2];
                        int offsetY = pendingSlotRenders[pos + 3];
                        GLCommandable cmd = cnzSlotMachineRenderer.createRenderCommand(
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
            }
        } finally {
            clearPendingSlotRenders();
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
    public void updatePrePhysics(AbstractPlayableSprite player, int cameraX, int zoneIndex) {
        if (zoneIndex == Sonic2ZoneConstants.ROM_ZONE_WFZ) {
            updateWfzPrePhysicsLevelEvents();
            updateWfzWindTunnel(player);
        } else {
            wfzWindTunnelActive = false;
        }
    }

    @Override
    public void updateAfterObjectExecution(AbstractPlayableSprite player, int cameraX, int zoneIndex) {
        if (zoneIndex != Sonic2ZoneConstants.ROM_ZONE_CNZ || cnzSlotMachineManager == null) {
            return;
        }
        // Level_MainLoop runs RunObjects before DeformBgLayer (s2.asm:5095,
        // 5098); DeformBgLayer dispatches LevEvents_CNZ and its SlotMachine
        // call (s2.asm:15175, 21511-21512). ObjD6 therefore observes the
        // previous SlotMachine result during this frame's object pass, while
        // SlotMachine reads the V_int_run_count published by that same pass.
        // LevelManager invokes this callback for each playable, but the ROM
        // routine is zone-global. CNZSlotMachineManager suppresses the second
        // callback for the same V-int count.
        cnzSlotMachineManager.update();
    }

    private void updateWfzPrePhysicsLevelEvents() {
        LevelEventProvider provider = GameServices.module().getLevelEventProvider();
        if (provider instanceof Sonic2LevelEventManager events) {
            events.getWfzEvents().updatePrePhysicsControlLock();
        }
    }

    private void updateWfzWindTunnel(AbstractPlayableSprite player) {
        if (player == null) {
            wfzWindTunnelActive = false;
            return;
        }

        int x = player.getCentreX() & 0xFFFF;
        int y = player.getCentreY() & 0xFFFF;
        if (!isInsideWfzWindTunnel(x, y)) {
            leaveWfzWindTunnel(player);
            return;
        }
        // ObjC1 sets WindTunnel_holding_flag while the player hangs from the
        // plating. WindTunnel returns immediately at that gate: it neither
        // reapplies Float2 nor runs the leave path's Walk/flag clear.
        if (wfzWindTunnelHolding) {
            return;
        }
        if (player.isHurt()
                || player.getDead()) {
            leaveWfzWindTunnel(player);
            return;
        }

        // ROM WindTunnel (s2.asm:5474-5524): this level-event routine runs
        // before Obj01_Control. It nudges the player left, forces airborne
        // wind velocity, then normal airborne input immediately adjusts x_vel.
        wfzWindTunnelActive = true;
        player.shiftX(-4);
        player.setXSpeed((short) -0x400);
        player.setYSpeed((short) 0);
        player.setAnimationId(Sonic2AnimationIds.FLOAT2);
        player.setAir(true);
        if (player.isUpPressed()) {
            player.shiftY(-1);
        }
        if (player.isDownPressed()) {
            player.shiftY(1);
        }
    }

    private void leaveWfzWindTunnel(AbstractPlayableSprite player) {
        if (wfzWindTunnelActive) {
            player.setAnimationId(Sonic2AnimationIds.WALK);
        }
        wfzWindTunnelActive = false;
    }

    public void setWfzWindTunnelHolding(boolean holding) {
        wfzWindTunnelHolding = holding;
    }

    public boolean isWfzWindTunnelHolding() {
        return wfzWindTunnelHolding;
    }

    private boolean isInsideWfzWindTunnel(int x, int y) {
        return (x >= 0x1510 && x < 0x1AF0 && y >= 0x0400 && y < 0x0580)
                || (x >= 0x20F0 && x < 0x2500 && y >= 0x0618 && y < 0x0680);
    }

    @Override
    public void update(AbstractPlayableSprite player, int cameraX, int zoneIndex) {
        // CNZ map bumpers run from updateAfterPlayablePhysics so their bounce
        // velocity is visible to later playable slots in the same frame.
    }

    @Override
    public void updateAfterPlayablePhysics(AbstractPlayableSprite player, int cameraX, int zoneIndex) {
        if (zoneIndex == Sonic2ZoneConstants.ROM_ZONE_CNZ) {
            if (cnzBumperManager != null) {
                cnzBumperManager.update(player, cameraX, zoneIndex);
            }
        }
    }

    @Override
    public void reset() {
        cnzBumperManager = null;
        cnzSlotMachineManager = null;
        clearPendingSlotRenders();
        if (cnzSlotMachineRenderer != null) {
            cnzSlotMachineRenderer.cleanup();
            cnzSlotMachineRenderer = null;
        }
        if (cnzSlotsShaderProgram != null) {
            cnzSlotsShaderProgram.cleanup();
            cnzSlotsShaderProgram = null;
        }
        cpzPylon = null;
        waterSurfaceManager = null;
        wfzWindTunnelActive = false;
        wfzWindTunnelHolding = false;
        currentZone = -1;
        currentAct = -1;
    }

    @Override
    public boolean hasCollisionFeatures(int zoneIndex) {
        return zoneIndex == Sonic2ZoneConstants.ROM_ZONE_CNZ;
    }

    @Override
    public boolean hasWater(int zoneIndex) {
        // Water_flag is set for CPZ Act 2, ARZ, and HPZ only (s2.asm Level_InitWater).
        // HTZ lava is a background effect, not water.
        return zoneIndex == Sonic2ZoneConstants.ROM_ZONE_ARZ ||
               zoneIndex == Sonic2ZoneConstants.ROM_ZONE_CPZ;  // Mega Mack (purple liquid)
    }

    @Override
    public int getWaterLevel(int zoneIndex, int actIndex) {
        return GameServices.water().getWaterLevelY(zoneIndex, actIndex);
    }

    @Override
    public float getWaterlineOffset(int zoneIndex, int actIndex) {
        // S2's palette split follows the visual water level. The separately
        // rendered surface strip must not move the sprite palette boundary.
        return 0.0f;
    }

    @Override
    public boolean bgWrapsHorizontally() {
        return true;
    }

    @Override
    public boolean isForceBlackBackdrop() {
        return currentZone == Sonic2ZoneConstants.ROM_ZONE_MCZ;
    }

    // Intentionally no public accessors for bumper system.
}
