package com.openggf.game.sonic3k;

import com.openggf.audio.GameSound;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.data.AnimatedPaletteProvider;
import com.openggf.data.AnimatedPatternProvider;
import com.openggf.data.Game;
import com.openggf.data.PlayerSpriteArtProvider;
import com.openggf.data.Rom;
import com.openggf.data.RomByteReader;
import com.openggf.data.SpindashDustArtProvider;
import com.openggf.data.compression.KosinskiInspectionCache;
import com.openggf.game.DynamicStartPositionProvider;
import com.openggf.game.GameServices;
import com.openggf.game.LevelLoadPaletteOverrideProvider;
import com.openggf.game.session.SessionManager;
import com.openggf.sprites.art.SpriteArtSet;
import com.openggf.game.sonic3k.audio.Sonic3kAudioProfile;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.Level;
import com.openggf.level.Palette;
import com.openggf.level.animation.AnimatedPaletteManager;
import com.openggf.level.animation.AnimatedPatternManager;
import com.openggf.level.resources.DeferredLevelResourceTracker;
import com.openggf.level.resources.DeferredLevelResourceLoader;
import com.openggf.level.resources.DeferredLevelResourceManifest;
import com.openggf.level.resources.PreparableLevelLoader;
import com.openggf.level.resources.PreparedLevelBuild;
import com.openggf.level.resources.LevelResourcePlan;
import com.openggf.level.resources.LoadOp;
import com.openggf.game.sonic3k.events.S3kSeamlessMutationExecutor;
import com.openggf.game.sonic3k.objects.AizIntroTerrainSwap;
import com.openggf.game.sonic3k.resources.Sonic3kDeferredLevelResourceProfile;
import com.openggf.game.sonic3k.resources.S3kRuntimeArtCoordinator;

import java.util.concurrent.Callable;
import java.util.LinkedHashSet;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Game implementation for Sonic 3 &amp; Knuckles.
 *
 * <p>Handles level loading by reading the LevelLoadBlock table to determine
 * ROM addresses for all level resources, then using LevelResourcePlan to
 * compose them via the standard resource loading pipeline.
 *
 * <p>The current module loads level resources, objects, rings, animation and
 * palette providers, zone features, and bonus-stage coordination through the
 * shared runtime pipeline.
 */
public class Sonic3k extends Game implements PlayerSpriteArtProvider, SpindashDustArtProvider,
        DynamicStartPositionProvider, AnimatedPatternProvider, AnimatedPaletteProvider,
        LevelLoadPaletteOverrideProvider, DeferredLevelResourceLoader, PreparableLevelLoader {
    private static final Logger LOG = Logger.getLogger(Sonic3k.class.getName());
    private static final int[] ICZ1_LOCK_ON_INTRO_PALETTE_LINE4_COLORS_1_TO_15 = {
            0x0EEE, 0x0EEC, 0x0EEA, 0x0ECA, 0x0EC8,
            0x0EA6, 0x0E86, 0x0E64, 0x0E40, 0x0E00,
            0x0C00, 0x0000, 0x0AEC, 0x0CEA, 0x0E80
    };

    private final Rom rom;
    private final Sonic3kZoneRegistry fallbackZoneRegistry = new Sonic3kZoneRegistry();
    private Sonic3kPlayerArt playerArt;
    private Sonic3kDustArt dustArt;
    private Sonic3kRingArt ringArt;
    private Sonic3kLevelAnimationManager levelAnimationManager;
    // ROM AIZ_vine_angle ($FFFFFEBA) is deliberately OUTSIDE the level-init
    // clear: Level does clearRAM Oscillating_table,(AIZ_vine_angle-Oscillating_table)
    // (sonic3k.asm:10609) and the special stage repeats the same bounded clear
    // (sonic3k.asm:10606-10607 region), both stopping one word short of it, so the
    // word free-runs for the whole session. A Game instance does NOT: LevelManager
    // rebuilds one on every load (LevelManager.java:459), so this state is injected
    // by the session-lived Sonic3kGameModule instead of being owned here.
    private final Sonic3kGlobalAnimationState globalAnimationState;
    private Level levelAnimationLevel;
    private int levelAnimationZone = -1;

    public Sonic3k(Rom rom) throws IOException {
        this(rom, new Sonic3kGlobalAnimationState());
    }

    /**
     * Session-scoped construction: the module supplies the
     * {@code AIZ_vine_angle} carrier so it survives the level loads that
     * replace this {@code Game}.
     */
    Sonic3k(Rom rom, Sonic3kGlobalAnimationState globalAnimationState) throws IOException {
        this.rom = rom;
        this.globalAnimationState = globalAnimationState;
        ensureAddressTablesReady();
    }

    /** Package-private view for the module-ownership pin in {@code TestSonic3kVineAngleOwnership}. */
    Sonic3kGlobalAnimationState globalAnimationState() {
        return globalAnimationState;
    }

    @Override
    public Rom getRom() {
        return rom;
    }

    @Override
    public boolean isCompatible() {
        return true; // Detection already handled by Sonic3kRomDetector
    }

    @Override
    public String getIdentifier() {
        return "Sonic3k";
    }

    @Override
    public List<String> getTitleCards() {
        return List.of(
                "Angel Island Zone - Act 1", "Angel Island Zone - Act 2",
                "Hydrocity Zone - Act 1", "Hydrocity Zone - Act 2",
                "Marble Garden Zone - Act 1", "Marble Garden Zone - Act 2",
                "Carnival Night Zone - Act 1", "Carnival Night Zone - Act 2",
                "Flying Battery Zone - Act 1", "Flying Battery Zone - Act 2",
                "IceCap Zone - Act 1", "IceCap Zone - Act 2",
                "Launch Base Zone - Act 1", "Launch Base Zone - Act 2",
                "Mushroom Hill Zone - Act 1", "Mushroom Hill Zone - Act 2",
                "Sandopolis Zone - Act 1", "Sandopolis Zone - Act 2",
                "Lava Reef Zone - Act 1", "Lava Reef Zone - Act 2",
                "Sky Sanctuary Zone - Act 1", "Sky Sanctuary Zone - Act 2",
                "Death Egg Zone - Act 1", "Death Egg Zone - Act 2",
                "The Doomsday Zone");
    }

    @Override
    public int getMusicId(int levelIdx) throws IOException {
        // Convert levelIdx to zone/act
        int s3kIdx = levelIdx;
        if (levelIdx >= 0xC0) {
            s3kIdx = levelIdx - 0xC0;
        }

        int zone = s3kIdx / 2;
        int act = s3kIdx % 2;

        if (GameServices.hasRuntime()) {
            return GameServices.module().getZoneRegistry().getMusicId(zone, act);
        }
        return fallbackZoneRegistry.getMusicId(zone, act);
    }

    @Override
    public Map<GameSound, Integer> getSoundMap() {
        return new Sonic3kAudioProfile().getSoundMap();
    }

    @Override
    public SpriteArtSet loadPlayerSpriteArt(String characterCode) throws IOException {
        if (playerArt == null) {
            playerArt = new Sonic3kPlayerArt(RomByteReader.fromRom(rom));
        }
        return playerArt.loadForCharacter(characterCode);
    }

    @Override
    public SpriteArtSet loadSpindashDustArt(String characterCode) throws IOException {
        if (dustArt == null) {
            dustArt = new Sonic3kDustArt(RomByteReader.fromRom(rom));
        }
        return dustArt.loadForCharacter(characterCode);
    }

    @Override
    public Palette loadCharacterPalette(String characterCode) {
        if (characterCode == null) {
            return null;
        }
        int paletteAddr;
        int paletteSize = Palette.PALETTE_SIZE_IN_ROM;
        if ("knuckles".equalsIgnoreCase(characterCode)) {
            paletteAddr = Sonic3kConstants.KNUCKLES_PALETTE_ADDR;
            paletteSize = 32; // Pal_Knuckles: 1 palette line
        } else {
            paletteAddr = Sonic3kConstants.SONIC_PALETTE_ADDR;
        }
        try {
            byte[] data = rom.readBytes(paletteAddr, paletteSize);
            Palette palette = new Palette();
            palette.fromSegaFormat(data);
            return palette;
        } catch (IOException e) {
            LOG.warning("Failed to load character palette for " + characterCode + ": " + e.getMessage());
            return null;
        }
    }

    Palette loadActiveMainCharacterPalette() {
        return loadCharacterPalette(resolveActiveMainCharacterCode());
    }

    com.openggf.level.rings.RingSpriteSheet loadRingSpriteSheet() throws IOException {
        return ringArt().load();
    }

    @Override
    public AnimatedPatternManager loadAnimatedPatternManager(Level level, int zoneIndex) throws IOException {
        if (level == null) {
            return null;
        }
        return getOrCreateLevelAnimationManager(level, zoneIndex);
    }

    @Override
    public AnimatedPaletteManager loadAnimatedPaletteManager(Level level, int zoneIndex) throws IOException {
        if (level == null) {
            return null;
        }
        return getOrCreateLevelAnimationManager(level, zoneIndex);
    }

    private Sonic3kLevelAnimationManager getOrCreateLevelAnimationManager(Level level, int zoneIndex) throws IOException {
        if (levelAnimationManager != null
                && levelAnimationLevel == level
                && levelAnimationZone == zoneIndex) {
            return levelAnimationManager;
        }
        int actIndex = GameServices.level().getCurrentAct();
        Sonic3kLoadBootstrap bootstrap = Sonic3kBootstrapResolver.resolve(zoneIndex, actIndex);
        levelAnimationManager = new Sonic3kLevelAnimationManager(
                RomByteReader.fromRom(rom), level, zoneIndex, actIndex, bootstrap.isSkipIntro(),
                globalAnimationState);
        levelAnimationLevel = level;
        levelAnimationZone = zoneIndex;
        return levelAnimationManager;
    }

    @Override
    public Level loadLevel(int levelIdx) throws IOException {
        return loadLevelWithDeferredResources(
                levelIdx, DeferredLevelResourceTracker.none());
    }

    /**
     * Publishes the fresh level terrain KosM streams through the native S3K
     * runtime queue. The level loader has already decoded the same ROM sources
     * synchronously; this submission models the ROM's hardware-owned handoff
     * without making gameplay depend on trace data.
     */
    @Override
    public void queueFreshLevelRuntimeArt(int levelIdx) throws IOException {
        int s3kIdx = levelIdx >= 0xC0 ? levelIdx - 0xC0 : levelIdx;
        int zone = s3kIdx / 2;
        int act = s3kIdx % 2;
        Sonic3kLoadBootstrap bootstrap = Sonic3kBootstrapResolver.resolve(zone, act);
        int llbIndex = resolveLevelLoadBlockIndex(zone, act, bootstrap);
        int llbAddr = Sonic3kConstants.LEVEL_LOAD_BLOCK_ADDR
                + llbIndex * Sonic3kConstants.LEVEL_LOAD_BLOCK_ENTRY_SIZE;

        int primaryArtAddr = rom.read32BitAddr(llbAddr) & 0x00FFFFFF;
        int secondaryArtAddr = rom.read32BitAddr(llbAddr + 4) & 0x00FFFFFF;
        ResolvedGameplayOverlay overlay = resolveGameplayOverlay(
                zone, act, bootstrap, llbIndex, secondaryArtAddr, -1);
        secondaryArtAddr = overlay.secondaryArtAddr();
        S3kRuntimeArtCoordinator coordinator = S3kRuntimeArtCoordinator.current();
        // LoadLevelLoadBlock queues both parents at the call and only then
        // blocks at loc_7870 until Kos_modules_left reaches zero
        // (docs/skdisasm/sonic3k.asm:9727 and 9734 queue the two parents,
        // 9736-9743 is the wait; Level: calls it at :7761), so the
        // parents are in the FIFO before anything else can reach it. Nothing
        // in Level: defers that queueing, and the enemy art that follows comes
        // later still, from Obj_TitleCardWait2's LoadEnemyArt (:62298). A
        // deferred publication inverted that order: it released the slots it
        // had, and the following level frames' object art took all four before
        // the deferred batch could retry, leaving the terrain art permanently
        // starved behind a full FIFO.
        // Reaching LoadLevelLoadBlock means the card's own modules have already
        // drained (Obj_TitleCardCreate waits on Kos_modules_left, :62169-62171).
        // A caller that arrives while they are still outstanding is ahead of
        // that gate, and waits for the queue rather than queueing behind it.
        if (coordinator.freshLevelArtWaitsForModuleQueue()) {
            coordinator.deferFreshLevelRuntimeArt(
                    rom, primaryArtAddr, secondaryArtAddr);
        } else {
            coordinator.submitFreshLevelRuntimeArt(
                    rom, primaryArtAddr, secondaryArtAddr);
        }
    }

    @Override
    public Level loadLevelWithDeferredResources(
            int levelIdx,
            DeferredLevelResourceTracker deferredResources)
            throws IOException {
        Sonic3kLevel level = buildLevel(levelIdx, deferredResources, true);
        finishLevelLoad(level, levelIdx);
        return level;
    }

    /** Captures live character/bootstrap choices before the ROM worker starts. */
    @Override
    public Callable<PreparedLevelBuild> prepareLevelBuildTask(int levelIndex, String mutationKey) {
        LevelBuildInputs inputs = captureLevelBuildInputs(levelIndex);
        return () -> {
            Sonic3kLevel level = buildLevel(
                    levelIndex, DeferredLevelResourceTracker.none(), false, inputs);
            S3kSeamlessMutationExecutor.prepareLayoutForMutation(level, mutationKey);
            int[] zoneAct = zoneAndActOf(levelIndex);
            warmKosInspections(zoneAct[0], zoneAct[1], inputs.bootstrap());
            return new PreparedSonic3kLevelBuild(levelIndex, level);
        };
    }

    /** Publishes graphics and builds live-service-dependent art on the frame thread. */
    @Override
    public Level installPreparedLevel(PreparedLevelBuild build) throws IOException {
        if (!(build instanceof PreparedSonic3kLevelBuild prepared)) {
            throw new IllegalArgumentException("not an S3K prepared level build: " + build);
        }
        Sonic3kLevel level = prepared.level();
        level.publishGraphics(GameServices.graphics());
        int[] zoneAct = zoneAndActOf(prepared.levelIndex());
        level.attachPreparedLevelArt(Sonic3kObjectArtProvider.buildLevelArtSheets(
                level, zoneAct[0], zoneAct[1], RomByteReader.fromRom(rom)));
        finishLevelLoad(level, prepared.levelIndex());
        return level;
    }

    private record PreparedSonic3kLevelBuild(int levelIndex, Sonic3kLevel level)
            implements PreparedLevelBuild {
    }

    private record LevelBuildInputs(Sonic3kLoadBootstrap.Mode bootstrapMode, String mainCharacter,
                                    boolean omitSecondaryLevelPlc) {
        Sonic3kLoadBootstrap bootstrap() {
            // Level construction consumes only the mode, never the mutable start-position array.
            return new Sonic3kLoadBootstrap(bootstrapMode, null);
        }
    }

    private LevelBuildInputs captureLevelBuildInputs(int levelIndex) {
        int[] zoneAct = zoneAndActOf(levelIndex);
        var levelManager = GameServices.levelOrNull();
        var transitionRequest = levelManager != null
                ? levelManager.getExecutingSeamlessTransitionRequest() : null;
        return new LevelBuildInputs(
                Sonic3kBootstrapResolver.resolve(zoneAct[0], zoneAct[1]).mode(),
                resolveActiveMainCharacterCode(),
                transitionRequest != null && transitionRequest.omitSecondaryLevelPlc());
    }

    /**
     * Inspects the target level's LevelLoadBlock Kosinski streams into
     * {@link KosinskiInspectionCache} so the transition owner's later
     * {@code Queue_Kos}/{@code Queue_Kos_Module} submissions of the same
     * streams do not decode them on the submitting frame. Values only; the
     * submissions themselves still happen where the ROM makes them.
     */
    private void warmKosInspections(int zone, int act, Sonic3kLoadBootstrap bootstrap) throws IOException {
        int llbIndex = resolveLevelLoadBlockIndex(zone, act, bootstrap);
        int llbAddr = Sonic3kConstants.LEVEL_LOAD_BLOCK_ADDR
                + llbIndex * Sonic3kConstants.LEVEL_LOAD_BLOCK_ENTRY_SIZE;
        int primaryArt = rom.read32BitAddr(llbAddr) & 0x00FFFFFF;
        int secondaryArt = rom.read32BitAddr(llbAddr + 4) & 0x00FFFFFF;
        int primaryBlocks = rom.read32BitAddr(llbAddr + 8) & 0x00FFFFFF;
        int secondaryBlocks = rom.read32BitAddr(llbAddr + 12) & 0x00FFFFFF;
        int primaryChunks = rom.read32BitAddr(llbAddr + 16) & 0x00FFFFFF;
        int secondaryChunks = rom.read32BitAddr(llbAddr + 20) & 0x00FFFFFF;
        for (int source : new int[] {primaryBlocks, secondaryBlocks, primaryChunks, secondaryChunks}) {
            if (source > 0) {
                KosinskiInspectionCache.inspectStandard(rom, source);
            }
        }
        for (int source : new int[] {primaryArt, secondaryArt}) {
            if (source > 0) {
                KosinskiInspectionCache.inspectModuled(rom, source);
            }
        }
    }

    /** Converts a level index (with the optional 0xC0 lock-on offset) to {zone, act}. */
    private static int[] zoneAndActOf(int levelIdx) {
        int s3kIdx = levelIdx >= 0xC0 ? levelIdx - 0xC0 : levelIdx;
        return new int[] {s3kIdx / 2, s3kIdx % 2};
    }

    /**
     * Load-time steps that follow level construction on the frame thread:
     * lock-on palette overrides and the AIZ intro overlay preload.
     */
    private void finishLevelLoad(Sonic3kLevel level, int levelIdx) {
        int[] zoneAct = zoneAndActOf(levelIdx);
        int zone = zoneAct[0];
        int act = zoneAct[1];
        Sonic3kLoadBootstrap bootstrap = Sonic3kBootstrapResolver.resolve(zone, act);
        applyLockOnStartupPalette(level, zone, act);

        // Pre-decompress AIZ intro overlay data during level load so the
        // terrain swap at camera X=0x1400 doesn't pay the Kosinski decode cost.
        // This path also runs in raw level-loading tests without a gameplay
        // runtime, so keep it ROM-backed rather than resolving ObjectServices.
        boolean isAizIntro = zone == 0 && act == 0
                && bootstrap != null
                && bootstrap.mode() == Sonic3kLoadBootstrap.Mode.INTRO;
        if (isAizIntro) {
            AizIntroTerrainSwap.preloadOverlayData(rom);
        }
    }

    private synchronized Sonic3kRingArt ringArt() {
        if (ringArt == null) {
            ringArt = new Sonic3kRingArt(rom);
        }
        return ringArt;
    }

    /**
     * ROM-only level construction shared by the synchronous load and the
     * prepared (off-thread) build. Reads the ROM and immutable configuration
     * only; {@code publishGraphics} false defers palette/pattern publication
     * to {@link Sonic3kLevel#publishGraphics}.
     */
    private Sonic3kLevel buildLevel(
            int levelIdx,
            DeferredLevelResourceTracker deferredResources,
            boolean publishGraphics)
            throws IOException {
        return buildLevel(levelIdx, deferredResources, publishGraphics,
                captureLevelBuildInputs(levelIdx));
    }

    private Sonic3kLevel buildLevel(
            int levelIdx,
            DeferredLevelResourceTracker deferredResources,
            boolean publishGraphics,
            LevelBuildInputs inputs) throws IOException {
        int[] zoneAct = zoneAndActOf(levelIdx);
        int zone = zoneAct[0];
        int act = zoneAct[1];
        Sonic3kLoadBootstrap bootstrap = inputs.bootstrap();
        Sonic3kLevelResourceProfile resourceProfile =
                Sonic3kLevelResourceProfile.resolve(zone, act);
        int resourceZone = resourceProfile.romZone();
        int resourceAct = resourceProfile.romAct();

        LOG.info(String.format("Loading S3K level: zone=%d act=%d (levelIdx=0x%X)", zone, act, levelIdx));

        // Read LevelLoadBlock entry for this zone/act
        int llbIndex = resolveLevelLoadBlockIndex(zone, act, bootstrap);
        if (llbIndex != zone * Sonic3kConstants.ACTS_PER_ZONE_STRIDE + act) {
            LOG.info(String.format("  Using alternate LevelLoadBlock index %d for bootstrap mode %s",
                    llbIndex, bootstrap.mode()));
        }
        int llbAddr = Sonic3kConstants.LEVEL_LOAD_BLOCK_ADDR
                + llbIndex * Sonic3kConstants.LEVEL_LOAD_BLOCK_ENTRY_SIZE;

        // Parse 24-byte LevelLoadBlock entry
        int word0 = rom.read32BitAddr(llbAddr);       // (plc1 << 24) | primaryArtAddr
        int word1 = rom.read32BitAddr(llbAddr + 4);   // (plc2 << 24) | secondaryArtAddr
        int word2 = rom.read32BitAddr(llbAddr + 8);   // (palette << 24) | primaryBlocksAddr
        int word3 = rom.read32BitAddr(llbAddr + 12);  // (palette << 24) | secondaryBlocksAddr
        int word4 = rom.read32BitAddr(llbAddr + 16);  // primaryChunksAddr
        int word5 = rom.read32BitAddr(llbAddr + 20);  // secondaryChunksAddr

        int plcPrimary = (word0 >>> 24) & 0xFF;
        int plcSecondary = (word1 >>> 24) & 0xFF;
        int primaryArtAddr = word0 & 0x00FFFFFF;
        int secondaryArtAddr = word1 & 0x00FFFFFF;
        int paletteIndex = (word2 >> 24) & 0xFF;
        int primaryBlocksAddr = word2 & 0x00FFFFFF;
        int secondaryBlocksAddr = word3 & 0x00FFFFFF;
        int primaryChunksAddr = word4 & 0x00FFFFFF;
        int secondaryChunksAddr = word5 & 0x00FFFFFF;
        var customResources = resourceProfile.customResources().orElse(null);
        if (customResources != null) {
            // HPZS is assembled from explicit ROM sources by the special-stage
            // return path; consume the typed profile instead of relying on the
            // coincidentally adjacent generic table layout.
            plcPrimary = customResources.primaryPlc();
            plcSecondary = customResources.secondaryPlc();
            primaryArtAddr = customResources.primaryArtAddress();
            secondaryArtAddr = customResources.secondaryArtAddress();
            primaryBlocksAddr = customResources.primaryBlocksAddress();
            secondaryBlocksAddr = customResources.secondaryBlocksAddress();
            primaryChunksAddr = customResources.primaryChunksAddress();
            secondaryChunksAddr = customResources.secondaryChunksAddress();
        }
        Sonic3kDeferredLevelResourceProfile deferredProfile =
                Sonic3kDeferredLevelResourceProfile.forLevelLoadBlock(
                        llbIndex);
        SecondaryResourceSources deferredSources =
                deferredProfile != null
                        ? readSecondaryResourceSources(
                                deferredProfile
                                        .deferredSourceLevelLoadBlockIndex())
                        : null;
        DeferredLevelResourceManifest expectedDeferredManifest =
                deferredProfile != null
                        ? deferredProfile.manifest(
                                deferredSources.art(),
                                deferredSources.chunks(),
                                deferredSources.blocks())
                        : DeferredLevelResourceManifest.EMPTY;
        DeferredLevelResourceTracker activeDeferredResources =
                deferredResources != null
                        ? deferredResources
                        : DeferredLevelResourceTracker.none();
        if (!activeDeferredResources.hasExplicitPolicy()
                && deferredProfile != null
                && deferredProfile.initiallyDeferred()) {
            activeDeferredResources =
                    expectedDeferredManifest.newTracker();
        }
        if (activeDeferredResources.hasExplicitPolicy()
                && deferredProfile != null) {
            activeDeferredResources.verifyExactRequest(
                    expectedDeferredManifest);
        }

        ResolvedGameplayOverlay overlay = resolveGameplayOverlay(
                zone, act, bootstrap, llbIndex,
                secondaryArtAddr, secondaryBlocksAddr);
        secondaryArtAddr = overlay.secondaryArtAddr();
        secondaryBlocksAddr = overlay.secondaryBlocksAddr();
        if (overlay.applied()) {
            LOG.info(String.format("  AIZ1 overlay bridge active: art2=0x%06X blocks2=0x%06X",
                    secondaryArtAddr, secondaryBlocksAddr));
        }

        LOG.info(String.format("  LLB entry: plc1=0x%02X plc2=0x%02X art1=0x%06X art2=0x%06X " +
                        "blocks1=0x%06X blocks2=0x%06X chunks1=0x%06X chunks2=0x%06X pal=%d",
                plcPrimary, plcSecondary,
                primaryArtAddr, secondaryArtAddr, primaryBlocksAddr, secondaryBlocksAddr,
                primaryChunksAddr, secondaryChunksAddr, paletteIndex));

        // Build resource plan
        LevelResourcePlan.Builder planBuilder = LevelResourcePlan.builder();
        // Patterns (KosM)
        planBuilder.addPatternOp(LoadOp.kosinskiMBase(primaryArtAddr));
        if (secondaryArtAddr != primaryArtAddr && secondaryArtAddr > 0) {
            boolean deferred = deferredProfile != null
                    && deferredProfile.defersPatterns()
                    && activeDeferredResources.omitIfRequested(
                            deferredProfile.patternDescriptor(
                                    secondaryArtAddr));
            if (!deferred) {
                planBuilder.addPatternOp(
                        LoadOp.kosinskiMAppend(secondaryArtAddr));
            }
        }
        addLevelPlcPatternOps(planBuilder, zone, act, bootstrap, plcPrimary, plcSecondary,
                inputs.mainCharacter(), inputs.omitSecondaryLevelPlc());

        // Blocks (16x16, Kosinski) - "chunks" in engine terminology.
        // ROM LoadLevelLoadBlock2 reuses a1 across both Kos_Decomp calls, so the
        // second stream appends immediately after the first in Block_table.
        planBuilder.addChunkOp(LoadOp.kosinskiBase(primaryBlocksAddr));
        if (secondaryBlocksAddr != primaryBlocksAddr && secondaryBlocksAddr > 0) {
            boolean deferred = deferredProfile != null
                    && deferredProfile.defersChunks()
                    && activeDeferredResources.omitIfRequested(
                            deferredProfile.chunkDescriptor(
                                    secondaryBlocksAddr));
            if (!deferred) {
                planBuilder.addChunkOp(
                        LoadOp.kosinskiAppend(secondaryBlocksAddr));
            }
        }

        // Chunks (128x128, Kosinski) - "blocks" in engine terminology.
        // Same as above: destination pointer is preserved by Kos_Decomp, so
        // the secondary stream appends after primary in RAM_start.
        planBuilder.addBlockOp(LoadOp.kosinskiBase(primaryChunksAddr));
        if (secondaryChunksAddr != primaryChunksAddr && secondaryChunksAddr > 0) {
            boolean deferred = deferredProfile != null
                    && deferredProfile.defersBlocks()
                    && activeDeferredResources.omitIfRequested(
                            deferredProfile.blockDescriptor(
                                    secondaryChunksAddr));
            if (!deferred) {
                planBuilder.addBlockOp(
                        LoadOp.kosinskiAppend(secondaryChunksAddr));
            }
        }
        if (deferredProfile != null
                && deferredProfile.deferredSourceLevelLoadBlockIndex()
                        != llbIndex) {
            for (var descriptor
                    : expectedDeferredManifest.descriptors()) {
                if (!activeDeferredResources.omitIfRequested(descriptor)) {
                    throw new IllegalStateException(
                            "target profile did not request its deferred declaration: "
                                    + descriptor);
                }
            }
        }
        activeDeferredResources.verifyFullyConsumed();

        // Collision indices (loaded directly, not through resource plan)
        // Returns [primaryAddr, secondaryAddr, interleavedFlag]
        CollisionAddressInfo collisionInfo =
                getCollisionAddresses(resourceZone, resourceAct);
        int primaryCollisionAddr = collisionInfo.primaryAddress();
        int secondaryCollisionAddr = collisionInfo.secondaryAddress();
        boolean interleavedCollision = collisionInfo.interleaved();

        LevelResourcePlan plan = planBuilder.build();

        // Get layout address from LevelPtrs
        int layoutAddr = customResources != null
                ? customResources.layoutAddress()
                : getLayoutAddr(resourceZone, resourceAct);

        // Get level boundaries address from LevelSizes
        int boundariesAddr =
                getLevelBoundariesAddr(resourceZone, resourceAct, bootstrap);
        Integer boundariesMinXOverride = null;
        if (zone == 0 && act == 0
                && bootstrap != null
                && bootstrap.mode() == Sonic3kLoadBootstrap.Mode.INTRO) {
            // ROM Get_LevelSizeStart uses AIZ intro LevelSizes profile, then overrides min X to 0
            // for the intro start (x=$40, y=$420).
            boundariesMinXOverride = 0;
        } else if (zone == Sonic3kZoneIds.ZONE_MHZ && act == 0
                && !"knuckles".equalsIgnoreCase(inputs.mainCharacter())) {
            // ROM Get_LevelSizeStart loc_1BF1E (sonic3k.asm:38214-38225): for MHZ1
            // (Current_zone_and_act==$0700) played as Sonic/Tails (Player_mode<3,
            // cmpi.w #3/bhs.s skip) with Sonic 3 locked on (SK_alone_flag==0), the
            // level-load routine overrides Camera_min_X_pos to $C0 and forces the
            // initial Camera_X_pos to $C0 (d1=$160, then subi.w #$A0 at :38246).
            // The engine only models the locked-on ROM, so SK_alone_flag is always
            // 0. Overriding the loaded min-X to $C0 both pins the left boundary and,
            // via the level-load force-position clamp (Camera.updatePosition(force)
            // -> clampAxisWithWrap), snaps the initial camera X up from
            // playerCentreX-$A0 to $C0. The MHZ1 LevelSizes xstart is 0
            // (sonic3k.asm:38111), so without this the camera starts too far left.
            boundariesMinXOverride = 0xC0;
        }

        // Get palette address
        int levelPaletteAddr = customResources != null
                ? customResources.introPaletteAddress()
                : getLevelPaletteAddr(paletteIndex);

        // Character palette — Knuckles uses Pal_Knuckles, Sonic/Tails share Pal_SonicTails
        String mainCharCode = inputs.mainCharacter();
        int characterPaletteAddr;
        if ("knuckles".equalsIgnoreCase(mainCharCode)) {
            characterPaletteAddr = Sonic3kConstants.KNUCKLES_PALETTE_ADDR;
        } else {
            characterPaletteAddr = Sonic3kConstants.SONIC_PALETTE_ADDR;
        }

        // Load objects and rings
        RomByteReader romReader = RomByteReader.fromRom(rom);
        var objectPlacement = new Sonic3kObjectPlacement(romReader);
        var objectSpawns = objectPlacement.load(resourceZone, resourceAct);

        var ringPlacement = new Sonic3kRingPlacement(romReader);
        var ringSpawns = ringPlacement.load(resourceZone, resourceAct);

        var ringSpriteSheet = ringArt().load();

        LOG.info(String.format("  S3K loaded %d objects, %d rings for zone=%d act=%d",
                objectSpawns.size(), ringSpawns.size(), zone, act));

        Sonic3kLevel level = new Sonic3kLevel(rom, zone, plan,
                primaryCollisionAddr, secondaryCollisionAddr, interleavedCollision,
                layoutAddr, boundariesAddr,
                characterPaletteAddr, levelPaletteAddr,
                boundariesMinXOverride,
                objectSpawns, ringSpawns, ringSpriteSheet,
                publishGraphics);
        validateCustomBounds(level, customResources);
        return level;
    }

    private static void validateCustomBounds(
            Sonic3kLevel level,
            Sonic3kLevelResourceProfile.CustomLevelResources resources) {
        if (resources == null) {
            return;
        }
        if (level.getMinX() != resources.minX()
                || level.getMaxX() != resources.maxX()
                || level.getMinY() != resources.minY()
                || level.getMaxY() != resources.maxY()) {
            throw new IllegalStateException(String.format(
                    "custom S3K bounds drift: loaded=[%04X,%04X,%04X,%04X] profile=[%04X,%04X,%04X,%04X]",
                    level.getMinX(), level.getMaxX(), level.getMinY(), level.getMaxY(),
                    resources.minX(), resources.maxX(), resources.minY(), resources.maxY()));
        }
    }

    @Override
    public void applyLevelLoadPaletteOverrides(Level level, int zone, int act) {
        if (level instanceof Sonic3kLevel sonic3kLevel) {
            applyLockOnStartupPalette(sonic3kLevel, zone, act);
            applyHpzSanctuaryPaletteLifecycle(zone, act);
        }
    }

    private void applyHpzSanctuaryPaletteLifecycle(int zone, int act) {
        if (zone != Sonic3kZoneIds.ZONE_HPZ || act != 1) {
            return;
        }
        var resources = Sonic3kLevelResourceProfile.resolve(zone, act)
                .requireCustomResources();
        var registry = GameServices.paletteOwnershipRegistryOrNull();
        if (registry == null) {
            return;
        }
        try {
            // ROM: lea (Pal_HPZ+$20),a1 / target destination
            // Normal_palette_line_3. Character and target line 2 are untouched.
            byte[] mainPalette = rom.readBytes(
                    resources.sanctuaryPaletteAddress(true)
                            + Palette.PALETTE_SIZE_IN_ROM,
                    2 * Palette.PALETTE_SIZE_IN_ROM);
            for (int line = 0; line < 2; line++) {
                registry.applyTargetPatch(
                        S3kPaletteOwners.HPZ_PALETTE_CONTROL,
                        line + 2,
                        0,
                        java.util.Arrays.copyOfRange(
                                mainPalette,
                                line * Palette.PALETTE_SIZE_IN_ROM,
                                (line + 1) * Palette.PALETTE_SIZE_IN_ROM));
            }
            // Obj_HPZSSEntryControl writes these over target line 4 before
            // the fade-in. Pal_HPZ's source colors are the red placeholder.
            registry.applyTargetPatch(
                    S3kPaletteOwners.HPZ_PALETTE_CONTROL,
                    3,
                    1,
                    new byte[]{0x06, (byte) 0xA0, 0x06, 0x60});
        } catch (IOException e) {
            throw new IllegalStateException(
                    "failed to stage HPZ sanctuary target palette", e);
        }
    }

    private static void applyLockOnStartupPalette(Sonic3kLevel level, int zone, int act) {
        if (zone == Sonic3kZoneIds.ZONE_ICZ && act == 0) {
            // Lockon S3/Screen Events.asm ICZ1_SetIntroPal updates line 4 after
            // Pal_ICZ1 loads; without it the opening mountain BG uses the cave colors.
            GraphicsManager graphics = GameServices.graphics();
            S3kPaletteWriteSupport.applyContiguousPatch(
                    GameServices.paletteOwnershipRegistryOrNull(),
                    level,
                    graphics,
                    S3kPaletteOwners.ICZ_STARTUP_PALETTE,
                    S3kPaletteOwners.PRIORITY_ZONE_EVENT,
                    3,
                    1,
                    paletteWordsToBytes(ICZ1_LOCK_ON_INTRO_PALETTE_LINE4_COLORS_1_TO_15));
            S3kPaletteWriteSupport.resolvePendingWritesNow(
                    GameServices.paletteOwnershipRegistryOrNull(),
                    level,
                    graphics);
        }
    }

    private static byte[] paletteWordsToBytes(int[] segaWords) {
        byte[] colorData = new byte[segaWords.length * Palette.BYTES_PER_COLOR];
        for (int i = 0; i < segaWords.length; i++) {
            int segaWord = segaWords[i];
            int offset = i * Palette.BYTES_PER_COLOR;
            colorData[offset] = (byte) ((segaWord >>> 8) & 0xFF);
            colorData[offset + 1] = (byte) (segaWord & 0xFF);
        }
        return colorData;
    }

    @Override
    public int[] getBackgroundScroll(int levelIdx, int cameraX, int cameraY) {
        // Simple parallax: BG at half camera speed
        return new int[]{cameraX / 2, cameraY / 2};
    }

    @Override
    public boolean canRelocateLevels() {
        return false;
    }

    @Override
    public boolean canSave() {
        return false;
    }

    @Override
    public boolean relocateLevels(boolean unsafe) {
        return false;
    }

    @Override
    public boolean save(int levelIdx, Level level) {
        return false;
    }

    @Override
    public int[] getStartPosition(int zoneIndex, int actIndex) throws IOException {
        Sonic3kLoadBootstrap bootstrap = Sonic3kBootstrapResolver.resolve(zoneIndex, actIndex);
        if (bootstrap.hasIntroStartPosition()) {
            return bootstrap.introStartPosition();
        }

        int startTableAddr = getCharacterStartTableAddr();
        if (!isReadable(startTableAddr, Sonic3kConstants.START_LOCATION_ENTRY_SIZE)) {
            return null;
        }

        Sonic3kLevelResourceProfile resourceProfile =
                Sonic3kLevelResourceProfile.resolve(zoneIndex, actIndex);
        int entryIndex = resourceProfile.tableIndex();
        int entryAddr = startTableAddr + entryIndex * Sonic3kConstants.START_LOCATION_ENTRY_SIZE;

        if (!isReadable(entryAddr, Sonic3kConstants.START_LOCATION_ENTRY_SIZE)) {
            return null;
        }

        int x = rom.read16BitAddr(entryAddr);
        int y = rom.read16BitAddr(entryAddr + 2);
        return new int[]{x, y};
    }

    // ===== Private helpers =====

    private void ensureAddressTablesReady() throws IOException {
        if (hasValidCoreTables()) {
            LOG.info("S3K core tables validated using verified constants.");
            return;
        }

        LOG.warning("S3K constants failed sanity checks; running ROM scanner fallback.");
        Sonic3kConstants.setScanned(false);
        new Sonic3kRomScanner(rom).scan();

        if (!hasValidCoreTables()) {
            throw new IOException("S3K address table validation failed after scanner fallback.");
        }
    }

    private boolean hasValidCoreTables() throws IOException {
        return hasValidLevelLoadBlock()
                && hasValidLevelPointers()
                && hasValidSolidIndexes()
                && hasValidStartLocations();
    }

    private boolean hasValidLevelLoadBlock() throws IOException {
        int llbAddr = Sonic3kConstants.LEVEL_LOAD_BLOCK_ADDR;
        if (!isReadable(llbAddr, Sonic3kConstants.LEVEL_LOAD_BLOCK_ENTRY_SIZE)) {
            return false;
        }

        int word0 = rom.read32BitAddr(llbAddr);
        int word2 = rom.read32BitAddr(llbAddr + 8);
        int word4 = rom.read32BitAddr(llbAddr + 16);

        int primaryArtAddr = word0 & 0x00FFFFFF;
        int primaryBlocksAddr = word2 & 0x00FFFFFF;
        int primaryChunksAddr = word4 & 0x00FFFFFF;

        return isReadable(primaryArtAddr, 2)
                && isReadable(primaryBlocksAddr, 2)
                && isReadable(primaryChunksAddr, 2);
    }

    private boolean hasValidLevelPointers() throws IOException {
        int levelPtrsAddr = Sonic3kConstants.LEVEL_PTRS_ADDR;
        if (!isReadable(levelPtrsAddr, Sonic3kConstants.LEVEL_PTRS_ENTRY_SIZE)) {
            return false;
        }

        int layoutAddr = rom.read32BitAddr(levelPtrsAddr);
        if (!isReadable(layoutAddr, Sonic3kConstants.LEVEL_LAYOUT_HEADER_SIZE)) {
            return false;
        }

        int fgCols = rom.read16BitAddr(layoutAddr);
        int bgCols = rom.read16BitAddr(layoutAddr + 2);
        int fgRows = rom.read16BitAddr(layoutAddr + 4);
        int bgRows = rom.read16BitAddr(layoutAddr + 6);
        return fgCols > 0 && fgCols <= Sonic3kConstants.MAP_WIDTH
                && bgCols > 0 && bgCols <= Sonic3kConstants.MAP_WIDTH
                && fgRows > 0 && fgRows <= Sonic3kConstants.MAP_HEIGHT
                && bgRows > 0 && bgRows <= Sonic3kConstants.MAP_HEIGHT;
    }

    private boolean hasValidSolidIndexes() throws IOException {
        int solidIndexesAddr = Sonic3kConstants.SOLID_INDEXES_ADDR;
        if (!isReadable(solidIndexesAddr, Sonic3kConstants.SOLID_INDEXES_ENTRY_SIZE)) {
            return false;
        }

        int rawPtr = rom.read32BitAddr(solidIndexesAddr);
        CollisionAddressInfo decoded = decodeCollisionPointer(rawPtr);
        int primarySize = decoded.interleaved()
                ? Sonic3kConstants.COLLISION_INDEX_SIZE * 2
                : Sonic3kConstants.COLLISION_INDEX_SIZE;
        int secondarySize = primarySize;
        return isReadable(decoded.primaryAddress(), primarySize)
                && isReadable(decoded.secondaryAddress(), secondarySize);
    }

    private boolean hasValidStartLocations() throws IOException {
        int startAddr = Sonic3kConstants.SONIC_START_LOCATIONS_ADDR;
        if (!isReadable(startAddr, Sonic3kConstants.START_LOCATION_ENTRY_SIZE)) {
            return false;
        }
        int x = rom.read16BitAddr(startAddr);
        int y = rom.read16BitAddr(startAddr + 2);
        return x > 0 && y > 0;
    }

    private boolean isReadable(int addr, int size) throws IOException {
        if (addr <= 0 || size <= 0) {
            return false;
        }
        long romSize = rom.getSize();
        long start = Integer.toUnsignedLong(addr);
        long end = start + size;
        return start < romSize && end <= romSize;
    }

    private int getCharacterStartTableAddr() {
        String mainCharacterCode = resolveActiveMainCharacterCode();
        if ("knuckles".equalsIgnoreCase(mainCharacterCode)) {
            return Sonic3kConstants.KNUX_START_LOCATIONS_ADDR;
        }
        return Sonic3kConstants.SONIC_START_LOCATIONS_ADDR;
    }

    private Aiz1GameplayOverlay readAiz1GameplayOverlayFromIntroEntry() throws IOException {
        int introIndex = Sonic3kConstants.LEVEL_LOAD_BLOCK_AIZ1_INTRO_INDEX;
        SecondaryResourceSources sources =
                readSecondaryResourceSources(introIndex);

        int entryAddr = Sonic3kConstants.LEVEL_LOAD_BLOCK_ADDR
                + introIndex * Sonic3kConstants.LEVEL_LOAD_BLOCK_ENTRY_SIZE;
        if (!isReadable(entryAddr, Sonic3kConstants.LEVEL_LOAD_BLOCK_ENTRY_SIZE)) {
            return null;
        }
        if (!isReadable(sources.art(), 2)
                || !isReadable(sources.chunks(), 2)) {
            return null;
        }

        return new Aiz1GameplayOverlay(
                sources.art(), sources.chunks());
    }

    private ResolvedGameplayOverlay resolveGameplayOverlay(
            int zone,
            int act,
            Sonic3kLoadBootstrap bootstrap,
            int levelLoadBlockIndex,
            int secondaryArtAddr,
            int secondaryBlocksAddr) throws IOException {
        boolean applyAiz1OverlayBridge = zone == 0
                && act == 0
                && bootstrap != null
                && bootstrap.isSkipIntro()
                && levelLoadBlockIndex
                != Sonic3kConstants.LEVEL_LOAD_BLOCK_AIZ1_INTRO_INDEX;
        if (!applyAiz1OverlayBridge) {
            return new ResolvedGameplayOverlay(
                    secondaryArtAddr, secondaryBlocksAddr, false);
        }
        Aiz1GameplayOverlay override = readAiz1GameplayOverlayFromIntroEntry();
        if (override == null) {
            LOG.warning("AIZ1 overlay bridge requested but intro overlay entry was invalid.");
            return new ResolvedGameplayOverlay(
                    secondaryArtAddr, secondaryBlocksAddr, false);
        }
        return new ResolvedGameplayOverlay(
                override.secondaryArtAddr(),
                secondaryBlocksAddr < 0
                        ? secondaryBlocksAddr
                        : override.secondaryBlocksAddr(),
                true);
    }

    private SecondaryResourceSources readSecondaryResourceSources(
            int levelLoadBlockIndex) throws IOException {
        int entryAddr = Sonic3kConstants.LEVEL_LOAD_BLOCK_ADDR
                + levelLoadBlockIndex
                * Sonic3kConstants.LEVEL_LOAD_BLOCK_ENTRY_SIZE;
        if (!isReadable(
                entryAddr,
                Sonic3kConstants.LEVEL_LOAD_BLOCK_ENTRY_SIZE)) {
            throw new IOException(
                    "Deferred LevelLoadBlock entry is outside the ROM: "
                            + levelLoadBlockIndex);
        }
        return new SecondaryResourceSources(
                rom.read32BitAddr(entryAddr + 4) & 0x00FF_FFFF,
                rom.read32BitAddr(entryAddr + 12) & 0x00FF_FFFF,
                rom.read32BitAddr(entryAddr + 20) & 0x00FF_FFFF);
    }

    /**
     * Gets the primary and secondary collision data addresses for a zone.
     *
     * <p>SolidIndexes entries are 32-bit pointers to collision index data, one per act
     * (indexed as zone*2+act). The format is determined by address comparison, matching
     * the original LoadSolids routine (sonic3k.asm:9549-9558):
     * <ul>
     *   <li>Address >= S3_LEVEL_SOLID_DATA (0x260000): non-interleaved (S3 zones)</li>
     *   <li>Address < S3_LEVEL_SOLID_DATA: interleaved (SK zones)</li>
     * </ul>
     *
     * <p>Non-interleaved: primary 0x600 bytes, then secondary 0x600 bytes.
     * Interleaved: primary and secondary alternate bytes in 0xC00 block.
     *
     * @return CollisionAddressInfo with decoded addresses and format
     */
    private CollisionAddressInfo getCollisionAddresses(int zone, int act) throws IOException {
        int solidIndexesAddr = Sonic3kConstants.SOLID_INDEXES_ADDR;
        if (solidIndexesAddr == 0) {
            LOG.warning("SolidIndexes address not set - no collision data");
            return new CollisionAddressInfo(0, 0, false);
        }

        // SolidIndexes has one entry per act: index = zone*2 + act
        int entryAddr = solidIndexesAddr + (zone * 2 + act) * Sonic3kConstants.SOLID_INDEXES_ENTRY_SIZE;
        int rawPtr = rom.read32BitAddr(entryAddr);
        CollisionAddressInfo decoded = decodeCollisionPointer(rawPtr);

        LOG.info(String.format("  Collision: raw=0x%08X primary=0x%06X secondary=0x%06X interleaved=%b",
                rawPtr, decoded.primaryAddress(), decoded.secondaryAddress(), decoded.interleaved()));

        return decoded;
    }

    static CollisionAddressInfo decodeCollisionPointer(int rawPtr) {
        int pointerNoHighBit = rawPtr & 0x7FFFFFFF;
        int address = pointerNoHighBit & 0x00FFFFFF;
        boolean highBitMarker = (rawPtr & 0x80000000) != 0;
        boolean lowBitMarker = (address & 1) != 0;

        // Matches both S3Complete and non-S3Complete pointer encodings:
        // - low bit marker (+1) for non-interleaved entries
        // - high bit marker for S3Complete builds
        // - address threshold fallback for standard combined ROMs
        boolean nonInterleaved = highBitMarker
                || lowBitMarker
                || address >= Sonic3kConstants.S3_LEVEL_SOLID_DATA;

        if (nonInterleaved) {
            return new CollisionAddressInfo(
                    address,
                    address + Sonic3kConstants.COLLISION_INDEX_SIZE,
                    false
            );
        }
        return new CollisionAddressInfo(address, address + 1, true);
    }

    static record CollisionAddressInfo(int primaryAddress, int secondaryAddress, boolean interleaved) {}
    private record Aiz1GameplayOverlay(int secondaryArtAddr, int secondaryBlocksAddr) {}

    private record ResolvedGameplayOverlay(
            int secondaryArtAddr, int secondaryBlocksAddr, boolean applied) {}
    private record SecondaryResourceSources(int art, int chunks, int blocks) {}

    /**
     * Gets the layout data ROM address for a zone/act from the LevelPtrs table.
     */
    private int getLayoutAddr(int zone, int act) throws IOException {
        int levelPtrsAddr = Sonic3kConstants.LEVEL_PTRS_ADDR;
        if (levelPtrsAddr == 0) {
            LOG.warning("LevelPtrs address not set");
            return 0;
        }

        int index = zone * Sonic3kConstants.ACTS_PER_ZONE_STRIDE + act;
        int ptr = rom.read32BitAddr(levelPtrsAddr + index * Sonic3kConstants.LEVEL_PTRS_ENTRY_SIZE) & 0x00FFFFFF;

        LOG.info(String.format("  Layout pointer: zone=%d act=%d -> 0x%06X", zone, act, ptr));
        return ptr;
    }

    static int resolveLevelLoadBlockIndex(int zone, int act, Sonic3kLoadBootstrap bootstrap) {
        int baseIndex = Sonic3kLevelResourceProfile.resolve(zone, act).tableIndex();

        // ROM parity (LoadLevelLoadBlock / LoadLevelLoadBlock2):
        // For AIZ1 Sonic/Tails with no starpost (intro path), d0 is NOT overridden
        // to $0D00, so resources come from normal AIZ1 (zone/act entry 0).
        // The $0D00 override (entry 26) applies to post-intro/skip-intro paths.
        if (zone == 0
                && act == 0
                && bootstrap != null
                && bootstrap.mode() == Sonic3kLoadBootstrap.Mode.SKIP_INTRO) {
            return Sonic3kConstants.LEVEL_LOAD_BLOCK_AIZ1_INTRO_INDEX;
        }
        return baseIndex;
    }

    /**
     * Gets the level boundaries address from the LevelSizes table.
     */
    private int getLevelBoundariesAddr(int zone, int act, Sonic3kLoadBootstrap bootstrap) {
        int levelSizesAddr = Sonic3kConstants.LEVEL_SIZES_ADDR;
        if (levelSizesAddr == 0) {
            return 0;
        }

        int index = zone * Sonic3kConstants.ACTS_PER_ZONE_STRIDE + act;
        // ROM parity: Get_LevelSizeStart always indexes by Current_zone_and_act,
        // so AIZ1 always uses entry 0 (minX=$1308) regardless of intro/skip state.
        // Entry 26 is only used by LoadLevelLoadBlock for *resource* loading.
        // Mode.INTRO overrides min X to 0 via boundariesMinXOverride.
        return levelSizesAddr + index * Sonic3kConstants.LEVEL_SIZES_ENTRY_SIZE;
    }

    /**
     * Gets the level palette ROM address from the palette index.
     *
     * <p>S3K palette pointers are stored in PalPointers table, format:
     * dc.l sourceAddr, dc.w ramDest, dc.w (count-1)
     * (8 bytes per entry, same as S2).
     *
     * <p>If PalPointers address is not yet scanned, returns 0.
     */
    private int getLevelPaletteAddr(int paletteIndex) throws IOException {
        int palPointersAddr = Sonic3kConstants.PAL_POINTERS_ADDR;
        if (palPointersAddr == 0) {
            LOG.fine("PalPointers address not set - using default palette");
            return 0;
        }
        int entryAddr = palPointersAddr + paletteIndex * 8;
        return rom.read32BitAddr(entryAddr) & 0x00FFFFFF;
    }

    private void addLevelPlcPatternOps(LevelResourcePlan.Builder planBuilder,
                                       int zone,
                                       int act,
                                       Sonic3kLoadBootstrap bootstrap,
                                       int plcPrimary,
                                       int plcSecondary,
                                       String mainCharacterCode,
                                       boolean omitSecondaryForTransition) {
        boolean aizIntro = zone == 0
                && act == 0
                && bootstrap != null
                && bootstrap.mode() == Sonic3kLoadBootstrap.Mode.INTRO;
        if (aizIntro) {
            // ROM Level startup sequence for AIZ intro:
            // 1) Level PLC is requested from LevelLoadBlock (PLC_0B for AIZ1),
            // 2) then Load_PLC_2 #1 clears the queue and loads common HUD art,
            // 3) then Load_PLC #$0A appends intro-sprite art.
            // So effective startup overlays are PLC_01 + PLC_0A only.
            //
            // PLC 0x0A (intro waves, 344 tiles at $03D1-$0528) overlaps with
            // spikes/springs ($0494-$04C3). In the ROM, PLCs process
            // incrementally over VBlanks so the overwrite is staggered. In our
            // engine, all overlays apply at once, so we load intro sprites FIRST
            // and spikes/springs PLC LAST to keep them correct for gameplay.
            appendPlcPatternOps(planBuilder, 0x0A);
            appendPlcPatternOps(planBuilder, 0x01);
            appendPlcPatternOps(planBuilder, 0x4E);
            return;
        }

        LinkedHashSet<Integer> plcOrder = new LinkedHashSet<>();
        plcOrder.addAll(selectLevelPlcs(plcPrimary, plcSecondary, omitSecondaryForTransition));
        // ROM startup path always loads active-character PLC after level PLC setup.
        plcOrder.add(resolveStartupCharacterPlcIndex(mainCharacterCode));
        // PLC 0x4E (spikes/springs art). In the ROM this is loaded at runtime by
        // specific objects (signpost, boss defeat) via Load_PLC_Raw, but since those
        // aren't implemented yet, load it at startup so spikes/springs render.
        plcOrder.add(0x4E);

        for (int plcIndex : plcOrder) {
            appendPlcPatternOps(planBuilder, plcIndex);
        }
    }

    static java.util.List<Integer> selectLevelPlcs(int primary, int secondary,
                                                    boolean omitSecondary) {
        LinkedHashSet<Integer> selected = new LinkedHashSet<>();
        if (primary != 0) selected.add(primary);
        if (!omitSecondary && secondary != 0) selected.add(secondary);
        return java.util.List.copyOf(selected);
    }

    private static int resolveStartupCharacterPlcIndex(String mainCharacterCode) {
        if ("knuckles".equalsIgnoreCase(mainCharacterCode)) {
            return 0x05;
        }
        if ("tails".equalsIgnoreCase(mainCharacterCode)) {
            return 0x07;
        }
        return 0x01;
    }

    private String resolveActiveMainCharacterCode() {
        var worldSession = SessionManager.getCurrentWorldSession();
        if (worldSession != null
                && worldSession.getSaveSessionContext() != null
                && worldSession.getSaveSessionContext().selectedTeam() != null) {
            return worldSession.getSaveSessionContext().selectedTeam().mainCharacter();
        }
        return GameServices.configuration().getString(SonicConfiguration.MAIN_CHARACTER_CODE);
    }

    private void appendPlcPatternOps(LevelResourcePlan.Builder planBuilder, int plcIndex) {
        try {
            var plc = Sonic3kPlcLoader.parsePlc(rom, plcIndex);
            planBuilder.recordPatternLoadCue(plcIndex);
            List<LoadOp> ops = Sonic3kPlcLoader.toPatternOps(plc);
            for (LoadOp op : ops) {
                planBuilder.addPatternOp(op);
            }
        } catch (IOException e) {
            LOG.warning(String.format("Failed to parse PLC 0x%02X from ROM: %s", plcIndex, e.getMessage()));
        }
    }

}
