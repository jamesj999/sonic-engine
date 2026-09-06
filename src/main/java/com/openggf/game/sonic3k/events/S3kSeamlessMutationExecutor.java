package com.openggf.game.sonic3k.events;

import com.openggf.data.Rom;
import com.openggf.game.GameServices;
import com.openggf.game.mutation.LayoutMutationContext;
import com.openggf.game.mutation.LayoutMutationIntent;
import com.openggf.game.mutation.LevelMutationSurface;
import com.openggf.game.mutation.MutationEffects;
import com.openggf.game.sonic3k.Sonic3kLevel;
import com.openggf.game.sonic3k.Sonic3kPlcLoader;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.objects.AizTransitionFloorObjectInstance;
import com.openggf.level.Level;
import com.openggf.level.LevelManager;
import com.openggf.level.Pattern;
import com.openggf.level.resources.LoadOp;
import com.openggf.level.resources.ResourceLoader;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.logging.Logger;

/**
 * Deterministic mutation handler for S3K seamless in-place transitions.
 */
public final class S3kSeamlessMutationExecutor {
    private static final Logger LOG = Logger.getLogger(S3kSeamlessMutationExecutor.class.getName());

    public static final String MUTATION_AIZ1_FIRE_TRANSITION_STAGE = "s3k.aiz1.fire_transition_stage";
    public static final String MUTATION_AIZ1_FIRE_TERRAIN_READY = "s3k.aiz1.fire_terrain_ready";
    public static final String MUTATION_AIZ1_POST_RELOAD_ACT2 = "s3k.aiz1.post_reload_act2";
    private static final int LLB_PRIMARY_CHUNKS = 8;
    private static final int LLB_SECONDARY_CHUNKS = 12;
    private static final int LLB_PRIMARY_BLOCKS = 16;
    private static final int AIZ2_LEVEL_LOAD_BLOCK_INDEX = 1;
    private static final int AIZ_SECONDARY_ART_DEST_TILE = 0x01FC;
    private static final int AIZ_SECONDARY_CHUNK_DEST_BYTES = 0x0AB8;
    private static final int AIZ_FIRE_OVERLAY_DEST_TILE = 0x0500;
    private static final int PAL_POINTER_AIZ_FIRE_INDEX = 0x0B;
    private static final int PLC_SPIKES_SPRINGS = 0x4E;

    private static volatile AizFireTerrainData cachedAizFireTerrain;

    private S3kSeamlessMutationExecutor() {
    }

    private static Rom rom() throws IOException {
        return GameServices.rom().getRom();
    }

    public static void apply(LevelManager levelManager, String mutationKey) {
        if (mutationKey == null || mutationKey.isBlank() || levelManager == null) {
            return;
        }
        switch (mutationKey) {
            case MUTATION_AIZ1_FIRE_TRANSITION_STAGE -> applyAiz1FireTransitionStage(levelManager);
            case MUTATION_AIZ1_FIRE_TERRAIN_READY -> applyAiz1FireTerrainReady(levelManager);
            case MUTATION_AIZ1_POST_RELOAD_ACT2 -> applyAiz1PostReloadAct2(levelManager);
            default -> LOG.warning("Unknown S3K seamless mutation key: " + mutationKey);
        }
    }

    /**
     * Applies the layout portion of {@code mutationKey} to a level being built
     * ahead of its install (see
     * {@link com.openggf.level.resources.PreparableLevelLoader}), so tilemaps
     * built from that level already match what {@link #apply} leaves behind
     * after the install. The install still runs the full mutation: its layout
     * writes copy from source cells the mutation never changes, so re-applying
     * them is a no-op.
     */
    public static void prepareLayoutForMutation(Level level, String mutationKey) {
        if (level == null || mutationKey == null || level.getMap() == null) {
            return;
        }
        if (MUTATION_AIZ1_POST_RELOAD_ACT2.equals(mutationKey)) {
            LayoutMutationContext context = new LayoutMutationContext(
                    LevelMutationSurface.forLevel(level), effects -> { });
            AizAct2LayoutAdjuster.apply(context, level.getMap());
        }
    }

    private static void applyAiz1FireTerrainReady(LevelManager levelManager) {
        Level level = levelManager.getCurrentLevel();
        if (!(level instanceof Sonic3kLevel sonic3kLevel)) {
            return;
        }

        try {
            Rom rom = rom();
            if (rom == null) {
                return;
            }
            AizFireTerrainData overlay = loadAizFireTerrainData(rom);
            if (overlay == null) {
                return;
            }

            applyImmediateMutation(levelManager, context -> {
                // AIZ1BGE_FireTransition queues these Kosinski streams into
                // RAM_start and Block_table before the module-art wait
                // (sonic3k.asm:104664-104681). Once that queue has drained,
                // collision observes the AIZ2 128x128/16x16 definitions even
                // though Current_act and the live layout are still AIZ1.
                sonic3kLevel.applyBlockOverlay(overlay.blocks128x128(), 0, false);
                sonic3kLevel.applyChunkOverlay(overlay.primaryChunks16x16(), 0, false);
                sonic3kLevel.applyChunkOverlay(
                        overlay.secondaryChunks16x16(), AIZ_SECONDARY_CHUNK_DEST_BYTES, false);
                return MutationEffects.NONE;
            });
            LOG.info("Applied decompressed AIZ2 block/chunk tables during AIZ1 fire handoff");
        } catch (Exception e) {
            LOG.warning("Failed to apply AIZ1 fire terrain tables: " + e.getMessage());
        }
    }

    private static void applyAiz1FireTransitionStage(LevelManager levelManager) {
        Level level = levelManager.getCurrentLevel();
        if (!(level instanceof Sonic3kLevel)) {
            return;
        }

        // AIZ1BGE_FireTransition queues AIZ2 block/chunk/art work, then
        // allocates Obj_AIZTransitionFloor and enters delayed fire refresh
        // (docs/skdisasm/sonic3k.asm:104664-104691, 104701-104714).
        // The module art is not visible until Kos_modules_left reaches zero.
        // Keep this stage to the non-art state owned before that wait.
        Sonic3kZoneEvents.loadPaletteFromPalPointers(PAL_POINTER_AIZ_FIRE_INDEX);
        Sonic3kAIZEvents.applyFireTransitionPaletteLine4(levelManager);
        Sonic3kZoneEvents.applyPlc(PLC_SPIKES_SPRINGS);
        spawnAizTransitionFloor(levelManager);
        LOG.info("Applied AIZ1 fire transition palette, PLC, and transition floor");
    }

    /**
     * Publishes the AIZ2 module-art payloads after both timing handles have
     * crossed POST readiness and been claimed by the AIZ event owner.
     */
    public static void applyAiz1FireTransitionPreparedArt(
            LevelManager levelManager,
            byte[] primaryTiles8x8,
            byte[] secondaryTiles8x8) {
        Objects.requireNonNull(levelManager, "levelManager");
        Objects.requireNonNull(primaryTiles8x8, "primaryTiles8x8");
        Objects.requireNonNull(secondaryTiles8x8, "secondaryTiles8x8");
        if (primaryTiles8x8.length % Pattern.PATTERN_SIZE_IN_ROM != 0
                || secondaryTiles8x8.length % Pattern.PATTERN_SIZE_IN_ROM != 0) {
            throw new IllegalArgumentException(
                    "prepared AIZ2 module art must contain whole patterns");
        }
        Level level = levelManager.getCurrentLevel();
        if (!(level instanceof Sonic3kLevel sonic3kLevel)) {
            throw new IllegalStateException(
                    "prepared AIZ2 module art requires a live Sonic3kLevel");
        }

        applyImmediateMutation(levelManager, context -> {
            sonic3kLevel.applyPatternOverlay(primaryTiles8x8, 0, false);
            sonic3kLevel.applyPatternOverlay(
                    secondaryTiles8x8,
                    AIZ_SECONDARY_ART_DEST_TILE * Pattern.PATTERN_SIZE_IN_ROM,
                    false);
            return MutationEffects.redrawAllTilemaps();
        });

        List<Sonic3kPlcLoader.TileRange> overlayRanges = new ArrayList<>();
        int primaryTileCount = primaryTiles8x8.length / Pattern.PATTERN_SIZE_IN_ROM;
        if (primaryTileCount > 0) {
            overlayRanges.add(new Sonic3kPlcLoader.TileRange(0, primaryTileCount));
        }
        int secondaryTileCount = secondaryTiles8x8.length / Pattern.PATTERN_SIZE_IN_ROM;
        if (secondaryTileCount > 0) {
            overlayRanges.add(new Sonic3kPlcLoader.TileRange(
                    AIZ_SECONDARY_ART_DEST_TILE, secondaryTileCount));
        }
        Sonic3kPlcLoader.refreshAffectedRenderers(overlayRanges, levelManager);
        LOG.info("Published prepared AIZ2 primary and secondary module art");
    }

    /** Publishes the one prepared fire-overlay payload queued by the ROM. */
    static int applyAiz1FireOverlayPreparedArt(
            LevelManager levelManager,
            byte[] fireOverlayTiles8x8) {
        return applyAiz1FireOverlay(levelManager, fireOverlayTiles8x8);
    }

    private static void spawnAizTransitionFloor(LevelManager levelManager) {
        if (levelManager == null || levelManager.getObjectManager() == null) {
            return;
        }
        boolean alreadyActive = levelManager.getObjectManager().getActiveObjects().stream()
                .anyMatch(AizTransitionFloorObjectInstance.class::isInstance);
        if (!alreadyActive) {
            AizTransitionFloorObjectInstance floor = new AizTransitionFloorObjectInstance();
            levelManager.getObjectManager().addDynamicObject(floor);
        }
    }

    private static void applyAiz1PostReloadAct2(LevelManager levelManager) {
        Level level = levelManager.getCurrentLevel();
        if (!(level instanceof Sonic3kLevel)) {
            return;
        }

        byte[] fireOverlayTiles8x8 =
                AizPreparedTransitionArtBridge.current(
                                levelManager.getGameModule().getLevelEventProvider())
                        .aizFireOverlayCopy();
        if (applyAiz1FireOverlay(levelManager, fireOverlayTiles8x8) == 0) {
            throw new IllegalStateException(
                    "AIZ fire continuation has no prepared fire-overlay payload");
        }
        applyImmediateMutation(levelManager, context -> {
            AizAct2LayoutAdjuster.apply(context, level.getMap());
            return MutationEffects.redrawAllTilemaps();
        });
        // Load the full fire palette (PalPointers #$0B) first, then overlay fire line 4.
        // Without PalPointers the act 2 reload keeps the normal AIZ2 palette, leaving
        // palette line 3 with green vegetation colors instead of fire colors.
        Sonic3kZoneEvents.loadPaletteFromPalPointers(PAL_POINTER_AIZ_FIRE_INDEX);
        Sonic3kAIZEvents.applyFireTransitionPaletteLine4(levelManager);
        LOG.info("Applied AIZ1 post-reload act 2 layout adjustment and fire palette");
    }

    private static int applyAiz1FireOverlay(
            LevelManager levelManager,
            byte[] tiles8x8) {
        if (tiles8x8 == null || tiles8x8.length == 0) {
            return 0;
        }
        if (tiles8x8.length % Pattern.PATTERN_SIZE_IN_ROM != 0) {
            throw new IllegalArgumentException(
                    "prepared AIZ fire overlay must contain whole patterns");
        }
        Level level = levelManager != null ? levelManager.getCurrentLevel() : null;
        if (!(level instanceof Sonic3kLevel sonic3kLevel)) {
            throw new IllegalStateException(
                    "prepared AIZ fire overlay requires a live Sonic3kLevel");
        }
        applyImmediateMutation(levelManager, context -> {
            sonic3kLevel.applyPatternOverlay(
                    tiles8x8,
                    AIZ_FIRE_OVERLAY_DEST_TILE * Pattern.PATTERN_SIZE_IN_ROM,
                    false);
            // Pattern data only: tilemap cells keep their indices, so refresh
            // the atlas lookup instead of rebuilding both full-level tilemaps.
            return MutationEffects.patternLookupRefresh();
        });
        return tiles8x8.length / Pattern.PATTERN_SIZE_IN_ROM;
    }

    private static void applyImmediateMutation(LevelManager levelManager, LayoutMutationIntent intent) {
        Level level = levelManager != null ? levelManager.getCurrentLevel() : null;
        if (level == null) {
            return;
        }

        LayoutMutationContext context = new LayoutMutationContext(
                LevelMutationSurface.forLevel(level),
                levelManager::applyMutationEffects);
        if (GameServices.hasRuntime()) {
            GameServices.zoneLayoutMutationPipeline().applyImmediately(intent, context);
            return;
        }
        levelManager.applyMutationEffects(intent.apply(context));
    }

    private static synchronized AizFireTerrainData loadAizFireTerrainData(Rom rom) throws IOException {
        if (cachedAizFireTerrain != null) {
            return cachedAizFireTerrain;
        }

        int entryAddr = Sonic3kConstants.LEVEL_LOAD_BLOCK_ADDR
                + AIZ2_LEVEL_LOAD_BLOCK_INDEX * Sonic3kConstants.LEVEL_LOAD_BLOCK_ENTRY_SIZE;
        int primaryChunksAddr = rom.read32BitAddr(entryAddr + LLB_PRIMARY_CHUNKS) & 0x00FFFFFF;
        int secondaryChunksAddr = rom.read32BitAddr(entryAddr + LLB_SECONDARY_CHUNKS) & 0x00FFFFFF;
        int blocksAddr = rom.read32BitAddr(entryAddr + LLB_PRIMARY_BLOCKS) & 0x00FFFFFF;

        ResourceLoader loader = new ResourceLoader(rom);
        byte[] primaryChunks16x16 = loader.loadSingle(LoadOp.kosinskiBase(primaryChunksAddr));
        byte[] secondaryChunks16x16 = loader.loadSingle(LoadOp.kosinskiBase(secondaryChunksAddr));
        byte[] blocks128x128 = loader.loadSingle(LoadOp.kosinskiBase(blocksAddr));

        cachedAizFireTerrain = new AizFireTerrainData(
                primaryChunks16x16,
                secondaryChunks16x16,
                blocks128x128);
        return cachedAizFireTerrain;
    }

    private record AizFireTerrainData(
            byte[] primaryChunks16x16,
            byte[] secondaryChunks16x16,
            byte[] blocks128x128) {
    }

    private static final class AizAct2LayoutAdjuster {
        private static final int LAYER_FOREGROUND = 0;
        private static final int LAYER_BACKGROUND = 1;

        private AizAct2LayoutAdjuster() {
        }

        private static void apply(LayoutMutationContext context, com.openggf.level.Map map) {
            if (map == null) {
                return;
            }
            copyCell(context, map, LAYER_FOREGROUND, 127, 1, 99, 14);
            copyRowPrefix(context, map, LAYER_BACKGROUND, 0, 5, 4);
            copyRowPrefix(context, map, LAYER_BACKGROUND, 1, 6, 4);
            copyRowPrefix(context, map, LAYER_BACKGROUND, 2, 7, 4);
        }

        private static void copyCell(LayoutMutationContext context,
                                     com.openggf.level.Map map,
                                     int layer,
                                     int sourceX,
                                     int sourceY,
                                     int targetX,
                                     int targetY) {
            if (!isInBounds(map, sourceX, sourceY) || !isInBounds(map, targetX, targetY)) {
                return;
            }
            int sourceVal = map.getValue(layer, sourceX, sourceY) & 0xFF;
            context.surface().setBlockInMap(layer, targetX, targetY, sourceVal);
        }

        private static void copyRowPrefix(LayoutMutationContext context,
                                          com.openggf.level.Map map,
                                          int layer,
                                          int sourceY,
                                          int targetY,
                                          int count) {
            if (count <= 0 || sourceY < 0 || targetY < 0
                    || sourceY >= map.getHeight() || targetY >= map.getHeight()) {
                return;
            }
            int width = Math.min(count, map.getWidth());
            // Capture source row values before writing — preserves read-then-write
            // semantics in case source and target rows overlap.
            int[] sourceVals = new int[width];
            for (int x = 0; x < width; x++) {
                sourceVals[x] = map.getValue(layer, x, sourceY) & 0xFF;
            }
            for (int x = 0; x < width; x++) {
                context.surface().setBlockInMap(layer, x, targetY, sourceVals[x]);
            }
        }

        private static boolean isInBounds(com.openggf.level.Map map, int x, int y) {
            return x >= 0 && y >= 0 && x < map.getWidth() && y < map.getHeight();
        }
    }
}
