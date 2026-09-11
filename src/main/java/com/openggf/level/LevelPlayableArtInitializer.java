package com.openggf.level;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.data.Game;
import com.openggf.data.PlayerSpriteArtProvider;
import com.openggf.data.Rom;
import com.openggf.data.RomByteReader;
import com.openggf.data.SpindashDustArtProvider;
import com.openggf.game.CrossGameFeatureProvider;
import com.openggf.game.GameId;
import com.openggf.game.GameModule;
import com.openggf.game.GameServices;
import com.openggf.game.resources.DynamicArtDecisionOwner;
import com.openggf.game.resources.DynamicArtLifecycleService;
import com.openggf.game.rules.GameRules;
import com.openggf.game.rules.PowerUpRules;
import com.openggf.graphics.GraphicsManager;
import com.openggf.graphics.RenderContext;
import com.openggf.sprites.Sprite;
import com.openggf.sprites.art.SpriteArtSet;
import com.openggf.sprites.managers.SpindashDustController;
import com.openggf.sprites.managers.SpriteManager;
import com.openggf.sprites.managers.TailsTailsController;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.Tails;
import com.openggf.sprites.render.PlayerSpriteRenderer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import static java.util.logging.Level.SEVERE;

/**
 * Owns playable sprite renderer, DPLC bank, palette context, and auxiliary
 * playable-art setup for level loads and runtime playable refreshes.
 */
final class LevelPlayableArtInitializer {
    private static final Logger LOGGER = LevelManager.LOGGER;

    private final LevelManager levelManager;
    private final SpriteManager spriteManager;
    private final GraphicsManager graphicsManager;
    private final SonicConfigurationService configService;
    private final CrossGameFeatureProvider crossGameFeatures;

    private int sidekickPatternBankCursor;
    private int dustBankCount;
    private int tailsTailBankCount;

    LevelPlayableArtInitializer(LevelManager levelManager,
                                SpriteManager spriteManager,
                                GraphicsManager graphicsManager,
                                SonicConfigurationService configService,
                                CrossGameFeatureProvider crossGameFeatures) {
        this.levelManager = levelManager;
        this.spriteManager = spriteManager;
        this.graphicsManager = graphicsManager;
        this.configService = configService;
        this.crossGameFeatures = crossGameFeatures;
    }

    void initialize() {
        RenderContext.clearSidekickContexts();
        levelManager.clearPlayerArtDplcOwners();
        dustBankCount = 0;
        tailsTailBankCount = 0;
        sidekickPatternBankCursor = 0;

        CrossGameFeatureProvider crossGame = crossGameFeatures;
        PlayerSpriteArtProvider artProvider;
        Game game = levelManager.game;
        if (CrossGameFeatureProvider.isActive()) {
            artProvider = crossGame;
        } else if (game instanceof PlayerSpriteArtProvider p) {
            artProvider = p;
        } else {
            return;
        }

        Sprite player = spriteManager.getSprite(resolveMainCharacterCode());
        if (!(player instanceof AbstractPlayableSprite playable)) {
            return;
        }
        initializeMainPlayable(playable, artProvider, crossGame);
        initializeSidekicks(artProvider, crossGame);
        RenderContext.uploadDonorPalettes(graphicsManager);
    }

    /**
     * Mints the DPLC owner for a character art bank that has no playable sprite
     * in this level.
     *
     * <p>The ROM's player art banks are permanently allocated VRAM regions
     * ({@code ArtTile_ArtUnc_Sonic} / {@code ArtTile_ArtUnc_Tails}) with their
     * own {@code Sonic_LastLoadedDPLC} / {@code Tails_LastLoadedDPLC} dedupe
     * word, and {@code LoadSonicDynPLC_Part2} / {@code LoadTailsDynPLC_Part2}
     * are shared entry points object code jumps into
     * (docs/s2disasm/s2.asm:38829-38862, 41659-41697). A bank therefore stays
     * submittable even where {@code InitPlayers} omits that character's object
     * (docs/s2disasm/s2.asm:5177-5198) — the Tornado pilot
     * ({@code ObjB2_Animate_Pilot}, docs/s2disasm/s2.asm:79538-79565) is the
     * only submitter into the Tails bank in SCZ/WFZ/DEZ.
     *
     * <p>Created on demand so levels that never submit into an absent
     * character's bank allocate nothing and keep their existing pattern-bank
     * layout unchanged.
     */
    DynamicArtDecisionOwner createAbsentCharacterBankOwner(String character) {
        PlayerSpriteArtProvider artProvider;
        Game game = levelManager.game;
        if (CrossGameFeatureProvider.isActive()) {
            artProvider = crossGameFeatures;
        } else if (game instanceof PlayerSpriteArtProvider p) {
            artProvider = p;
        } else {
            return null;
        }
        try {
            SpriteArtSet artSet = artProvider.loadPlayerSpriteArt(character);
            if (artSet == null || artSet.bankSize() <= 0
                    || artSet.dplcFrames().isEmpty()) {
                return null;
            }
            return createDynamicArtOwner(character, new PlayerSpriteRenderer(artSet));
        } catch (IOException e) {
            LOGGER.log(SEVERE, "Failed to load art bank for absent character: "
                    + character, e);
            return null;
        }
    }

    int reserveSidekickPatternBank(int bankSize) {
        int safeSize = Math.max(0, bankSize);
        int base = LevelManager.SIDEKICK_PATTERN_BASE + sidekickPatternBankCursor;
        sidekickPatternBankCursor += safeSize;
        return base;
    }

    static List<Integer> computeSidekickBankOffsets(List<Integer> bankSizes) {
        List<Integer> offsets = new ArrayList<>(bankSizes.size());
        int running = 0;
        for (int size : bankSizes) {
            offsets.add(running);
            running += size;
        }
        return offsets;
    }

    private void initializeMainPlayable(AbstractPlayableSprite playable,
                                        PlayerSpriteArtProvider artProvider,
                                        CrossGameFeatureProvider crossGame) {
        try {
            SpriteArtSet artSet = artProvider.loadPlayerSpriteArt(playable.getCode());
            if (artSet == null || artSet.bankSize() <= 0 || artSet.mappingFrames().isEmpty()
                    || artSet.dplcFrames().isEmpty()) {
                playable.setSpriteRenderer(null);
                return;
            }
            PlayerSpriteRenderer renderer = new PlayerSpriteRenderer(artSet);
            if (CrossGameFeatureProvider.isActive()) {
                renderer.setRenderContext(crossGame.getDonorRenderContext());
            }
            renderer.ensureCached(graphicsManager);
            applyPlayableArt(playable, renderer, artSet, false);
            initSpindashDust(playable);
            initTailsTails(playable, artSet);
            initSuperState(playable);
        } catch (IOException e) {
            LOGGER.log(SEVERE, "Failed to load player sprite art.", e);
        }
    }

    private void initializeSidekicks(PlayerSpriteArtProvider artProvider, CrossGameFeatureProvider crossGame) {
        List<AbstractPlayableSprite> sidekicks = spriteManager.getSidekicks();
        String mainCharName = resolveMainCharacterCode();
        List<String> sidekickCharNames = new ArrayList<>(sidekicks.size());
        for (AbstractPlayableSprite sidekick : sidekicks) {
            String name = spriteManager.getSidekickCharacterName(sidekick);
            if (name == null) {
                name = configService.getString(SonicConfiguration.SIDEKICK_CHARACTER_CODE);
            }
            sidekickCharNames.add(name);
        }

        java.util.Map<String, SpriteArtSet> artCache = new java.util.HashMap<>();
        List<SpriteArtSet> sidekickSourceArts = new ArrayList<>(sidekicks.size());
        for (String sidekickCharName : sidekickCharNames) {
            SpriteArtSet sourceArt = artCache.computeIfAbsent(
                    sidekickCharName.toLowerCase(),
                    key -> {
                        try {
                            return artProvider.loadPlayerSpriteArt(key);
                        } catch (IOException e) {
                            LOGGER.log(SEVERE, "Failed to load art for sidekick character: " + key, e);
                            return null;
                        }
                    });
            boolean valid = sourceArt != null && sourceArt.bankSize() > 0
                    && !sourceArt.mappingFrames().isEmpty()
                    && !sourceArt.dplcFrames().isEmpty();
            sidekickSourceArts.add(valid ? sourceArt : null);
        }

        for (int i = 0; i < sidekicks.size(); i++) {
            AbstractPlayableSprite sidekick = sidekicks.get(i);
            String sidekickCharName = sidekickCharNames.get(i);
            SpriteArtSet sourceArt = sidekickSourceArts.get(i);
            if (sourceArt == null) {
                LOGGER.warning("Skipping art init for sidekick " + i
                        + " (" + sidekickCharName + "): art unavailable or empty.");
                continue;
            }
            initializeSidekick(sidekick, sidekickCharName, mainCharName, sourceArt, artProvider, crossGame, i);
        }
    }

    private void initializeSidekick(AbstractPlayableSprite sidekick,
                                   String sidekickCharName,
                                   String mainCharName,
                                   SpriteArtSet sourceArt,
                                   PlayerSpriteArtProvider artProvider,
                                   CrossGameFeatureProvider crossGame,
                                   int index) {
        try {
            int shiftedBase = reserveSidekickPatternBank(sourceArt.bankSize());
            SpriteArtSet sidekickArt = new SpriteArtSet(
                    sourceArt.artTiles(),
                    sourceArt.mappingFrames(),
                    sourceArt.dplcFrames(),
                    sourceArt.paletteIndex(),
                    shiftedBase,
                    sourceArt.frameDelay(),
                    sourceArt.bankSize(),
                    sourceArt.animationProfile(),
                    sourceArt.animationSet());
            PlayerSpriteRenderer sidekickRenderer = new PlayerSpriteRenderer(sidekickArt);
            RenderContext sidekickPaletteCtx = createSidekickPaletteContext(
                    artProvider, sidekickCharName, mainCharName);
            if (sidekickPaletteCtx != null) {
                sidekickRenderer.setRenderContext(sidekickPaletteCtx);
            } else if (CrossGameFeatureProvider.isActive()) {
                sidekickRenderer.setRenderContext(crossGame.getDonorRenderContext());
            }
            sidekickRenderer.ensureCached(graphicsManager);
            applyPlayableArt(sidekick, sidekickRenderer, sidekickArt, true);
            initSpindashDust(sidekick);
            initTailsTails(sidekick, sidekickArt);
            if (sidekickPaletteCtx != null) {
                propagateSidekickPaletteContext(sidekick, sidekickPaletteCtx);
            }
            initSuperState(sidekick);
        } catch (Exception e) {
            LOGGER.log(SEVERE, "Failed to load sidekick sprite art for index " + index + ".", e);
        }
    }

    private void applyPlayableArt(AbstractPlayableSprite playable,
                                  PlayerSpriteRenderer renderer,
                                  SpriteArtSet artSet,
                                  boolean sidekick) {
        playable.setSpriteRenderer(renderer);
        String identity = characterIdentity(playable.getCode());
        DynamicArtDecisionOwner bankOwner = createDynamicArtOwner(identity, renderer);
        // The character's art bank always has exactly one dedupe state
        // (Sonic_LastLoadedDPLC / Tails_LastLoadedDPLC, docs/s2disasm/s2.asm:26039-26041),
        // shared by every jump into LoadSonicDynPLC_Part2 / LoadTailsDynPLC_Part2.
        // Register it for object submitters (ObjB2_Animate_Pilot) even where
        // InitPlayers omits the sidekick object itself (s2.asm:5177-5198), so the
        // pilot in SCZ/WFZ/DEZ submits through the same owner rather than a
        // second, private one.
        levelManager.registerPlayerArtDplcOwner(identity, bankOwner);
        playable.getAnimationManager().setDynamicArtDecisionOwner(
                sidekick && isSidekickSuppressed() ? null : bankOwner);
        playable.setMappingFrame(0);
        playable.setAnimationFrameCount(artSet.mappingFrames().size());
        playable.setAnimationProfile(artSet.animationProfile());
        playable.setAnimationSet(artSet.animationSet());
        playable.setAnimationId(0);
        playable.setAnimationFrameIndex(0);
        playable.setAnimationTick(0);
    }

    private RenderContext createSidekickPaletteContext(
            PlayerSpriteArtProvider artProvider,
            String sidekickCharName, String mainCharName) {
        if (sidekickCharName.equalsIgnoreCase(mainCharName)) {
            return null;
        }
        Palette sidekickPalette = artProvider.loadCharacterPalette(sidekickCharName);
        if (sidekickPalette == null) {
            return null;
        }
        Palette mainPalette = artProvider.loadCharacterPalette(mainCharName);
        if (mainPalette != null && sidekickPalette.dataEquals(mainPalette)) {
            return null;
        }
        GameModule activeModule = levelManager.gameModule;
        if (activeModule == null && GameServices.hasRuntime()) {
            activeModule = GameServices.module();
        }
        GameId gameId = activeModule != null ? activeModule.getGameId() : null;
        RenderContext ctx = RenderContext.createSidekickContext(gameId);
        ctx.setPalette(0, sidekickPalette);
        return ctx;
    }

    private static void propagateSidekickPaletteContext(AbstractPlayableSprite sidekick, RenderContext ctx) {
        if (sidekick.getSpindashDustController() != null
                && sidekick.getSpindashDustController().getRenderer() != null) {
            sidekick.getSpindashDustController().getRenderer().setRenderContext(ctx);
        }
        if (sidekick.getTailsTailsController() != null
                && sidekick.getTailsTailsController().getRenderer() != null) {
            sidekick.getTailsTailsController().getRenderer().setRenderContext(ctx);
        }
    }

    private void initSpindashDust(AbstractPlayableSprite playable) {
        CrossGameFeatureProvider crossGame = crossGameFeatures;
        SpindashDustArtProvider dustProv;
        Game game = levelManager.game;
        if (CrossGameFeatureProvider.isActive()) {
            dustProv = crossGame;
        } else if (game instanceof SpindashDustArtProvider d) {
            dustProv = d;
        } else {
            playable.setSpindashDustController(null);
            return;
        }
        try {
            String characterCode = playable.getCode().endsWith("_p2")
                    ? playable.getCode().substring(0, playable.getCode().length() - 3)
                    : playable.getCode();
            SpriteArtSet dustArt = dustProv.loadSpindashDustArt(characterCode);
            if (dustArt == null || dustArt.bankSize() <= 0 || dustArt.mappingFrames().isEmpty()
                    || dustArt.dplcFrames().isEmpty()) {
                playable.setSpindashDustController(null);
                return;
            }
            if (dustBankCount > 0) {
                int shiftedBase = reserveSidekickPatternBank(dustArt.bankSize());
                dustArt = new SpriteArtSet(
                        dustArt.artTiles(),
                        dustArt.mappingFrames(),
                        dustArt.dplcFrames(),
                        dustArt.paletteIndex(),
                        shiftedBase,
                        dustArt.frameDelay(),
                        dustArt.bankSize(),
                        dustArt.animationProfile(),
                        dustArt.animationSet());
            }
            dustBankCount++;
            PlayerSpriteRenderer dustRenderer = new PlayerSpriteRenderer(dustArt);
            if (CrossGameFeatureProvider.isActive()) {
                dustRenderer.setRenderContext(crossGame.getDonorRenderContext());
            }
            dustRenderer.ensureCached(graphicsManager);
            playable.setSpindashDustController(new SpindashDustController(
                    playable, dustRenderer, fixedDustSlotFor(playable)));
        } catch (IOException e) {
            LOGGER.log(SEVERE, "Failed to load spindash dust art.", e);
            playable.setSpindashDustController(null);
        }
    }

    private int fixedDustSlotFor(AbstractPlayableSprite playable) {
        if (playable == null) {
            return -1;
        }
        PowerUpRules rules = powerUpRulesFor(levelManager.activeGameModule());
        if (rules == null || !rules.waterSplashUsesFixedDustObject()) {
            return -1;
        }
        if (!playable.isCpuControlled()) {
            return rules.fixedDustSlotIndex(false);
        }
        List<AbstractPlayableSprite> sidekicks = spriteManager != null ? spriteManager.getSidekicks() : List.of();
        return !sidekicks.isEmpty() && sidekicks.get(0) == playable
                ? rules.fixedDustSlotIndex(true)
                : -1;
    }

    private void initTailsTails(AbstractPlayableSprite playable, SpriteArtSet artSet) {
        if (!(playable instanceof Tails)) {
            playable.setTailsTailsController(null);
            return;
        }
        CrossGameFeatureProvider crossGame = crossGameFeatures;
        GameModule gameModule = levelManager.gameModule;
        boolean isS3k = CrossGameFeatureProvider.isActive()
                ? crossGame.hasSeparateTailsTailArt()
                : gameModule.hasSeparateTailsTailArt();
        SpriteArtSet tailsArt;
        if (isS3k) {
            if (CrossGameFeatureProvider.isActive()) {
                try {
                    tailsArt = crossGame.loadTailsTailArt();
                } catch (IOException e) {
                    LOGGER.log(SEVERE, "Failed to load cross-game tails tail art.", e);
                    tailsArt = null;
                }
            } else {
                tailsArt = gameModule.loadTailsTailArt();
            }
            if (tailsArt == null || tailsArt.isEmpty()) {
                playable.setTailsTailsController(null);
                return;
            }
        } else {
            tailsArt = new SpriteArtSet(
                    artSet.artTiles(),
                    artSet.mappingFrames(),
                    artSet.dplcFrames(),
                    artSet.paletteIndex(),
                    gameModule.getTailsTailVramBase(),
                    artSet.frameDelay(),
                    artSet.bankSize(),
                    null,
                    null
            );
        }
        if (tailsTailBankCount > 0) {
            int shiftedBase = reserveSidekickPatternBank(tailsArt.bankSize());
            tailsArt = new SpriteArtSet(
                    tailsArt.artTiles(),
                    tailsArt.mappingFrames(),
                    tailsArt.dplcFrames(),
                    tailsArt.paletteIndex(),
                    shiftedBase,
                    tailsArt.frameDelay(),
                    tailsArt.bankSize(),
                    tailsArt.animationProfile(),
                    tailsArt.animationSet()
            );
        }
        tailsTailBankCount++;
        PlayerSpriteRenderer tailsRenderer = new PlayerSpriteRenderer(tailsArt);
        if (CrossGameFeatureProvider.isActive()) {
            tailsRenderer.setRenderContext(crossGame.getDonorRenderContext());
        }
        tailsRenderer.ensureCached(graphicsManager);
        // ROM: Obj05 exists exactly when Obj02 does - Tails' init spawns
        // ObjID_TailsTails and sets its parent (s2.asm:38945-38946), and
        // InitPlayers omits Obj02 entirely in WFZ/DEZ/SCZ (s2.asm:5177-5198).
        // A suppressed sidekick therefore has no tails-tails DPLC owner, matching
        // applyPlayableArt's gate above.
        playable.setTailsTailsController(new TailsTailsController(
                playable, tailsRenderer, isS3k,
                isSidekickSuppressed()
                        ? null
                        : createDynamicArtOwner("tails-tails", tailsRenderer)));
    }

    /**
     * The ROM's player DPLC routines are keyed by the character whose art bank
     * they own, not by a team slot: {@code LoadSonicDynPLC} stores through
     * {@code Sonic_LastLoadedDPLC} into ArtTile_ArtUnc_Sonic
     * (docs/s2disasm/s2.asm:38829-38840) and {@code LoadTailsDynPLC} through
     * {@code Tails_LastLoadedDPLC} into ArtTile_ArtUnc_Tails
     * (docs/s2disasm/s2.asm:41659-41690). S1 (docs/s1disasm/_incObj/01 Sonic.asm:2391-2398)
     * and S3K (docs/skdisasm/sonic3k.asm:25216-25218) use the same
     * changed-frame predicate against a per-character byte.
     * <p>
     * The engine names a CPU team slot {@code <character>_pN}, so the raw
     * sprite code never matched the character-keyed owner set and the sidekick
     * silently ran with no DPLC owner at all. Normalize the slot suffix away
     * here only — art bank and VRAM base selection stay with the renderer, as
     * the ROM's own 2P path retargets those via {@code Adjust2PArtPointer}.
     */
    private static String characterIdentity(String spriteCode) {
        if (spriteCode == null) {
            return null;
        }
        int slot = spriteCode.lastIndexOf("_p");
        if (slot <= 0 || slot + 2 >= spriteCode.length()) {
            return spriteCode;
        }
        for (int i = slot + 2; i < spriteCode.length(); i++) {
            if (!Character.isDigit(spriteCode.charAt(i))) {
                return spriteCode;
            }
        }
        return spriteCode.substring(0, slot);
    }

    /**
     * {@code InitPlayers} creates the sidekick object only where the ROM loads
     * it (docs/s2disasm/s2.asm:5177-5198): zones that skip Obj02 never run
     * {@code LoadTailsDynPLC} and so never queue a Tails DMA transfer. The
     * engine still spawns a sidekick sprite for presentation, so ask the
     * owning game module's existing sidekick-suppression predicate rather than
     * attaching a DPLC owner that would submit art the ROM never submits.
     */
    private boolean isSidekickSuppressed() {
        GameModule module = levelManager.gameModule;
        return module != null
                && module.isSidekickSuppressedForZone(levelManager.getCurrentZone());
    }

    private DynamicArtDecisionOwner createDynamicArtOwner(
            String owner,
            PlayerSpriteRenderer renderer) {
        GameModule module = levelManager.gameModule;
        DynamicArtLifecycleService lifecycle =
                GameServices.dynamicArtLifecycleOrNull();
        if (module == null || lifecycle == null || owner == null) {
            return null;
        }
        String normalizedOwner = owner.toLowerCase(java.util.Locale.ROOT);
        boolean supportedOwner = "sonic".equals(normalizedOwner)
                || "tails".equals(normalizedOwner)
                || "tails-tails".equals(normalizedOwner);
        if (!supportedOwner || !module.getRules().dynamicArtDmaService()
                .supportsPlayerDynamicArtAudit()) {
            return null;
        }
        return new DynamicArtDecisionOwner(
                lifecycle, module.getGameId(), normalizedOwner, renderer);
    }

    private void initSuperState(AbstractPlayableSprite playable) {
        GameModule gameModule = levelManager.gameModule;
        if (gameModule == null) {
            return;
        }
        var superCtrl = gameModule.createSuperStateController(playable);
        playable.setSuperStateController(superCtrl);

        if (superCtrl != null && !superCtrl.isRomDataPreLoaded()) {
            try {
                Rom rom = GameServices.rom().getRom();
                RomByteReader reader = RomByteReader.fromRom(rom);
                superCtrl.loadRomData(reader);
            } catch (Exception e) {
                LOGGER.fine("Could not load Super Sonic ROM data: " + e.getMessage());
            }
        }
    }

    private String resolveMainCharacterCode() {
        return com.openggf.game.session.ActiveGameplayTeamResolver.resolveMainCharacterCode(configService);
    }

    private static PowerUpRules powerUpRulesFor(GameModule module) {
        GameRules rules = module != null ? module.getRules() : null;
        return rules != null ? rules.powerUp() : null;
    }
}
