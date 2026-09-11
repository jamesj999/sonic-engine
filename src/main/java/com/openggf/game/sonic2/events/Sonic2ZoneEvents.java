package com.openggf.game.sonic2.events;

import com.openggf.camera.Camera;
import com.openggf.audio.AudioManager;
import com.openggf.data.Rom;
import com.openggf.data.RomManager;
import com.openggf.game.GameServices;
import com.openggf.game.GameStateManager;
import com.openggf.game.ObjectArtProvider;
import com.openggf.game.mutation.ZoneLayoutMutationPipeline;
import com.openggf.game.sonic2.Sonic2ObjectArtProvider;
import com.openggf.game.sonic2.resources.Sonic2PlcService;
import com.openggf.game.sonic2.resources.Sonic2RuntimePlcPublisher;
import com.openggf.level.LevelManager;
import com.openggf.level.ParallaxManager;
import com.openggf.level.WaterSystem;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.sprites.managers.SpriteManager;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.io.IOException;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Base class for Sonic 2 per-zone dynamic level events.
 * Each zone has its own event routine counter (ROM: eventRoutine)
 * that tracks progress through Act 2 boss sequences.
 */
public abstract class Sonic2ZoneEvents {
    private static final Logger LOGGER = Logger.getLogger(Sonic2ZoneEvents.class.getName());

    /** VDP palette line size: 16 colors × 2 bytes each = 32 bytes */
    private static final int PALETTE_LINE_SIZE = 32;

    protected int eventRoutine;
    protected int bossSpawnDelay;
    /** Deferred logical/eager cue after a transition has performed its one-shot effects. */
    private Integer pendingPlcId;

    protected Sonic2ZoneEvents() {
    }

    /**
     * Returns the current Camera singleton. Always call this accessor rather
     * than caching the reference, so it survives singleton replacement.
     */
    protected Camera camera() {
        return GameServices.camera();
    }

    protected LevelManager levelManager() {
        return GameServices.level();
    }

    protected AudioManager audio() {
        return GameServices.audio();
    }

    protected GameStateManager gameState() {
        return GameServices.gameState();
    }

    protected WaterSystem waterSystem() {
        return GameServices.water();
    }

    protected ParallaxManager parallax() {
        return GameServices.parallax();
    }

    protected SpriteManager spriteManager() {
        return GameServices.sprites();
    }

    protected ZoneLayoutMutationPipeline mutationPipeline() {
        return GameServices.zoneLayoutMutationPipeline();
    }

    protected Rom rom() throws IOException {
        return GameServices.rom().getRom();
    }

    /** Reset event state for a new level load. */
    public void init(int act) {
        eventRoutine = 0;
        bossSpawnDelay = 0;
        pendingPlcId = null;
    }

    /** Run per-frame event logic for the given act. */
    public abstract void update(int act, int frameCounter);

    /**
     * Run pre-physics event logic for the given act, BEFORE the player object
     * physics step. Mirrors the ROM routines that {@code WaterEffects} executes
     * just before {@code RunObjects} (docs/s2disasm/s2.asm:5094-5095). Default
     * is a no-op; only OOZ (OilSlides) currently overrides this.
     */
    public void updatePrePhysics(int act, int frameCounter) {
        // Default no-op
    }

    /**
     * Run the zone's RESERVED object-RAM slots, after the player object slots
     * and before the dynamic level objects.
     * <p>
     * ROM keeps a small band of fixed slots between the player objects and
     * {@code Dynamic_Object_RAM} - {@code Oil} (the OOZ oil surface, Obj07)
     * shares the {@code WaterSurface1} slot there
     * (docs/s2disasm/s2.constants.asm:1131-1137). Those slots therefore execute
     * before every dynamic object AND before {@code Tails_Tails} (Obj05), which
     * lives further on in {@code LevelOnly_Object_RAM}
     * (docs/s2disasm/s2.constants.asm:1144-1152). Default is a no-op; only OOZ
     * currently occupies a reserved slot.
     */
    public void updateReservedObjectSlots(int act, int frameCounter) {
        // Default no-op
    }

    public int getEventRoutine() {
        return eventRoutine;
    }

    public void setEventRoutine(int routine) {
        this.eventRoutine = routine;
    }

    public int getBossSpawnDelay() {
        return bossSpawnDelay;
    }

    public void setBossSpawnDelay(int delay) {
        this.bossSpawnDelay = delay;
    }

    /** Rewind sidecar for deferred native PLC publication. */
    public final int getPendingPlcIdForRewind() {
        return pendingPlcId == null ? -1 : pendingPlcId;
    }

    /** Restores deferred native PLC publication without replaying owner effects. */
    public final void setPendingPlcIdForRewind(int plcId) {
        pendingPlcId = plcId < 0 ? null : plcId;
    }

    /** Spawn a dynamic object into the level. */
    protected void spawnObject(ObjectInstance object) {
        LevelManager lm = levelManager();
        if (lm.getObjectManager() != null) {
            lm.getObjectManager().addDynamicObject(object);
        }
    }

    protected <T extends ObjectInstance> T spawnObject(Supplier<T> factory) {
        LevelManager lm = levelManager();
        if (lm.getObjectManager() == null) {
            return null;
        }
        return lm.getObjectManager().createDynamicObject(factory);
    }

    /**
     * Loads a boss palette from ROM and applies it to the specified palette line.
     * ROM equivalent: PalLoad_Now
     */
    protected static void loadBossPalette(int paletteLine, int romAddr) {
        try {
            Rom rom = GameServices.rom().getRom();
            LevelManager levelManager = GameServices.level();
            byte[] paletteData = rom.readBytes(romAddr, PALETTE_LINE_SIZE);
            levelManager.updatePalette(paletteLine, paletteData);
        } catch (Exception e) {
            if (RomManager.isConfiguredRomMissing(e)) {
                LOGGER.fine(() -> "Skipped boss palette load from ROM offset 0x"
                        + Integer.toHexString(romAddr) + ": " + e.getMessage());
            } else {
                LOGGER.warning("Failed to load boss palette from ROM offset 0x" +
                        Integer.toHexString(romAddr) + ": " + e.getMessage());
            }
        }
    }

    protected void setSidekickBounds(Integer minX, Integer maxX, Integer maxY) {
        setSidekickBounds(minX, maxX, null, maxY);
    }

    protected void setSidekickBounds(Integer minX, Integer maxX, Integer minY, Integer maxY) {
        for (AbstractPlayableSprite sidekick : spriteManager().getSidekicks()) {
            if (sidekick.getCpuController() != null) {
                sidekick.getCpuController().setLevelBounds(minX, maxX, minY, maxY);
            }
        }
    }

    protected void syncSidekickBoundsToCamera() {
        Camera cam = camera();
        setSidekickBounds((int) cam.getMinX(), (int) cam.getMaxX(),
                (int) cam.getMaxYTarget());
    }

    /** Retries a deferred one-shot cue without consuming the current DLE frame. */
    protected void retryPendingPlc() {
        if (pendingPlcId == null) {
            return;
        }
        if (publishSonic2Plc(pendingPlcId)) {
            pendingPlcId = null;
        }
    }

    protected boolean requestSonic2Plc(int plcId) {
        if (pendingPlcId != null) {
            if (pendingPlcId == plcId && publishSonic2Plc(plcId)) {
                pendingPlcId = null;
                return true;
            }
            return false;
        }
        if (publishSonic2Plc(plcId)) {
            return true;
        }
        pendingPlcId = plcId;
        return false;
    }

    private boolean publishSonic2Plc(int plcId) {
        try {
            if (!GameServices.hasRuntime()) {
                return true;
            }
            LevelManager levelManager = GameServices.levelOrNull();
            if (levelManager == null || levelManager.getCurrentLevel() == null) {
                return true;
            }
            ObjectArtProvider provider = GameServices.module().getObjectArtProvider();
            if (provider instanceof Sonic2ObjectArtProvider sonic2Provider) {
                Sonic2PlcService plcService = GameServices.module().getGameService(Sonic2PlcService.class);
                if (plcService == null) return true;
                Sonic2RuntimePlcPublisher.append(
                        sonic2Provider, plcService, levelManager::refreshObjectArtPatterns, plcId);
            }
            return true;
        } catch (RuntimeException | IOException e) {
            LOGGER.fine(() -> "S2 PLC request " + plcId + " deferred: " + e.getMessage());
            return false;
        }
    }
}
