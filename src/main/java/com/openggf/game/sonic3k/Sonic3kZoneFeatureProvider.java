package com.openggf.game.sonic3k;

import com.openggf.camera.Camera;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.data.Rom;
import com.openggf.data.RomByteReader;
import com.openggf.game.GameServices;
import com.openggf.game.PlayableEntity;
import com.openggf.game.session.ActiveGameplayTeamResolver;
import com.openggf.game.ZoneFeatureProvider;
import com.openggf.game.render.AdvancedRenderFrameState;
import com.openggf.game.render.AdvancedRenderMode;
import com.openggf.game.render.AdvancedRenderModeContext;
import com.openggf.game.render.AdvancedRenderModeController;
import com.openggf.game.render.SpecialRenderEffect;
import com.openggf.game.render.SpecialRenderEffectRegistry;
import com.openggf.game.sonic3k.features.AizBattleshipRenderFeature;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.features.AizTransitionRenderFeature;
import com.openggf.game.sonic3k.render.HczBgHighPriorityForegroundOverlayEffect;
import com.openggf.game.sonic3k.render.HczWallChaseBgOverlayEffect;
import com.openggf.game.sonic3k.bonusstage.slots.S3kSlotMachinePanelAnimator;
import com.openggf.game.sonic3k.features.HCZWaterSkimHandler;
import com.openggf.game.sonic3k.features.HCZWaterTunnelHandler;
import com.openggf.game.sonic3k.objects.AizPlaneIntroInstance;
import com.openggf.game.sonic3k.render.IczBigSnowPileBackgroundEffect;
import com.openggf.game.sonic3k.render.IczBigSnowPilePriorityMaskEffect;
import com.openggf.game.sonic3k.runtime.AizZoneRuntimeState;
import com.openggf.game.sonic3k.runtime.CnzZoneRuntimeState;
import com.openggf.game.sonic3k.runtime.HczZoneRuntimeState;
import com.openggf.game.sonic3k.events.Sonic3kCNZEvents;
import com.openggf.game.sonic3k.runtime.S3kRuntimeStates;
import com.openggf.graphics.GraphicsManager;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.WaterSystem;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.scroll.M68KMath;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Zone feature provider for Sonic 3 &amp; Knuckles.
 * Handles AIZ intro ocean phase detection, title card suppression,
 * and other S3K-specific zone features.
 */
public class Sonic3kZoneFeatureProvider implements ZoneFeatureProvider {
    private static final Logger LOGGER = Logger.getLogger(Sonic3kZoneFeatureProvider.class.getName());
    private static final int VDP_BG_PLANE_WIDTH_PX = 512;

    private final AizBattleshipRenderFeature aizBattleshipRenderFeature = new AizBattleshipRenderFeature();
    private final AizTransitionRenderFeature aizTransitionRenderFeature = new AizTransitionRenderFeature();
    private final SpecialRenderEffect hczBgHighPriorityForegroundOverlayEffect =
            new HczBgHighPriorityForegroundOverlayEffect();
    private final SpecialRenderEffect hczWallChaseBgOverlayEffect = new HczWallChaseBgOverlayEffect();
    private final SpecialRenderEffect iczBigSnowPileBackgroundEffect = new IczBigSnowPileBackgroundEffect();
    private final SpecialRenderEffect iczBigSnowPilePriorityMaskEffect = new IczBigSnowPilePriorityMaskEffect();
    private final AdvancedRenderMode slotMachineForegroundScrollMode = new AdvancedRenderMode() {
        @Override
        public String id() {
            return "s3k-slot-machine-foreground-scroll";
        }

        @Override
        public void contribute(AdvancedRenderModeContext context, AdvancedRenderFrameState.Builder builder) {
            if (context.zoneIndex() == Sonic3kZoneIds.ZONE_SLOT_MACHINE) {
                builder.enablePerLineForegroundScroll();
            }
        }
    };
    private final AdvancedRenderMode mgzCollapseForegroundVScrollMode = new AdvancedRenderMode() {
        @Override
        public String id() {
            return "s3k-mgz2-collapse-foreground-vscroll";
        }

        @Override
        public void contribute(AdvancedRenderModeContext context, AdvancedRenderFrameState.Builder builder) {
            if (context.zoneIndex() != Sonic3kZoneIds.ZONE_MGZ || context.actIndex() != 1) {
                return;
            }
            if (!(GameServices.module().getLevelEventProvider() instanceof Sonic3kLevelEventManager manager)
                    || manager.getMgzEvents() == null) {
                return;
            }

            short[] override = manager.getMgzEvents()
                    .buildCollapseForegroundVScrollOverride(context.camera().getX());
            if (override != null) {
                builder.setForegroundPerColumnVScrollOverride(override);
            }
        }
    };
    private final AdvancedRenderMode lbzEndingCollapseForegroundVScrollMode = new AdvancedRenderMode() {
        @Override
        public String id() {
            return "s3k-lbz1-ending-collapse-foreground-vscroll";
        }

        @Override
        public void contribute(AdvancedRenderModeContext context, AdvancedRenderFrameState.Builder builder) {
            if (context.zoneIndex() != Sonic3kZoneIds.ZONE_LBZ || context.actIndex() != 0) {
                return;
            }
            if (!(GameServices.module().getLevelEventProvider() instanceof Sonic3kLevelEventManager manager)
                    || manager.getLbzEvents() == null) {
                return;
            }

            short[] override = manager.getLbzEvents()
                    .buildEndingCollapseForegroundVScrollOverride(context.camera().getX());
            if (override != null) {
                builder.setForegroundPerColumnVScrollOverride(override);
            }
        }
    };
    private Sonic3kWaterSurfaceManager waterSurfaceManager;
    private final InitialWaveSplashSstOwner initialWaveSplashSstOwner =
            new InitialWaveSplashSstOwner() {
                @Override
                public boolean isRegistered() {
                    return waterSurfaceManager != null && waterSurfaceManager.isInitialized();
                }

                @Override
                public void processInitialWaveSplash(
                        com.openggf.sprites.managers.ProcessSpritesEpoch epoch) {
                    if (!isRegistered()) {
                        throw new IllegalStateException("wave splash fixed SST owner is not registered");
                    }
                    waterSurfaceManager.processInitialSstSlot(epoch);
                }
            };
    private final Set<AbstractPlayableSprite> forcedAizForestFrontBucketSprites = new HashSet<>();
    private S3kSlotMachinePanelAnimator slotMachinePanelAnimator;

    /**
     * S3K default is full-width BG (a single big tilemap copy of the layout's
     * BG layer). That works for zones whose BG interesting content lives in
     * the first 4 blocks (512px), but some S3K background routines refresh the
     * 64-cell VDP plane from an explicit BG source X.
     *
     * <p>Flipping those zones to the S2 wrap model (512px tilemap, rebuilt when
     * {@code bgTilemapBaseX} shifts) lets {@link SwScrlMgz#getBgCameraX()}
     * during state 8 relocate the 512px window so terrain cols come into
     * view. ICZ1's opening mountain BG similarly uses {@code d1=$1880} in
     * {@code ICZ1_BackgroundInit} before {@code Refresh_PlaneFull}.
     */
    @Override
    public boolean bgWrapsHorizontally() {
        var levelManager = GameServices.levelOrNull();
        if (levelManager == null) {
            return false;
        }
        int zoneId = levelManager.getFeatureZoneId();
        return zoneId == Sonic3kZoneIds.ZONE_MGZ
                || zoneId == Sonic3kZoneIds.ZONE_ICZ
                || isHcz2BackgroundPlaneWindowActive(zoneId)
                || isCnzBossBackgroundWindowActive(zoneId);
    }

    /**
     * HCZ2 keeps Plane B a 512px VDP window through every act-2 BG state.
     * During the wall chase, {@code HCZ2BGE_WallMove} refreshes the plane with
     * {@code DrawBGAsYouMove} at the wall BG camera
     * ({@code Camera_X_pos_BG_copy = camX - $200 + wall offset}), so the engine
     * window must follow {@code getBgCameraX()}; the post-chase states rebuild
     * it from source X={@code $000} ({@code HCZ2BGE_NormalRefresh}), where the
     * window stays anchored at 0 and wraps at the VDP's 512px width.
     */
    private boolean isHcz2BackgroundPlaneWindowActive(int zoneId) {
        if (zoneId != Sonic3kZoneIds.ZONE_HCZ || !GameServices.hasRuntime()) {
            return false;
        }
        return GameServices.zoneRuntimeRegistry()
                .currentAs(HczZoneRuntimeState.class)
                .map(HczZoneRuntimeState::backgroundPlaneWindowActive)
                .orElse(false);
    }

    @Override
    public boolean foregroundWrapsHorizontally() {
        return isAizBattleshipForestLoopActive();
    }

    @Override
    public int foregroundWorldWrapOffset() {
        if (!isAizBattleshipForestLoopActive()) {
            return 0;
        }
        AizZoneRuntimeState aizState = getAizState();
        return aizState != null ? aizState.getLevelRepeatOffset() : 0;
    }

    /**
     * AIZ2 {@code AIZ2_DoShipLoop} post-bombing forest loop active (ROM state only:
     * the auto-scroll loop is running with the post-bombing $46C0 wrap boundary).
     */
    private boolean isAizBattleshipForestLoopActive() {
        if (getFeatureZoneId() != Sonic3kZoneIds.ZONE_AIZ || !GameServices.hasRuntime()) {
            return false;
        }
        AizZoneRuntimeState aizState = getAizState();
        return aizState != null && aizState.isBattleshipForestLoopActive();
    }

    private boolean isCnzBossBackgroundWindowActive(int zoneId) {
        if (zoneId != Sonic3kZoneIds.ZONE_CNZ) {
            return false;
        }
        return isCnzBossBackgroundWindowActive();
    }

    private boolean isCnzBossBackgroundWindowActive() {
        if (getFeatureZoneId() != Sonic3kZoneIds.ZONE_CNZ || !GameServices.hasRuntime()) {
            return false;
        }
        CnzZoneRuntimeState state = S3kRuntimeStates.currentCnz(GameServices.zoneRuntimeRegistry()).orElse(null);
        if (state == null) {
            return false;
        }
        return state.bossBackgroundScrollActive();
    }

    @Override
    public boolean useLinearBackgroundLayoutOverflow(int zoneIndex) {
        // HCZ2's BG layout stores the wall strip at X=$200..$3FF and zero-filled
        // columns beyond; DrawBGAsYouMove reads those rows raw, so once the wall
        // BG camera runs past the data the ROM plane shows blank chunks. Linear
        // overflow reproduces that instead of wrapping back into the normal strip.
        return isCnzBossBackgroundWindowActive()
                || isHcz2BackgroundPlaneWindowActive(zoneIndex);
    }

    /**
     * CNZ {@code CNZ1BGE_Boss} (docs/skdisasm/sonic3k.asm:107498-107507) is the only
     * CNZ background phase that locks Plane B to a fixed 16-chunk band drawn from
     * layout Y={@code $200} and loops it via the VDP vertical scroll; the surrounding
     * {@code BossStart}/{@code AfterBoss}/refresh phases scroll the full layout via
     * {@code DrawBGAsYouMove}. Anchor the loop band only while the BG routine is
     * {@code BG_BOSS} so the looping carnival band excludes the room floor below it.
     */
    @Override
    public int backgroundLoopBandBaseY(int zoneIndex, int actIndex) {
        if (getFeatureZoneId() != Sonic3kZoneIds.ZONE_CNZ || !GameServices.hasRuntime()) {
            return -1;
        }
        CnzZoneRuntimeState state = S3kRuntimeStates.currentCnz(GameServices.zoneRuntimeRegistry()).orElse(null);
        if (state == null || state.backgroundRoutine() != Sonic3kCNZEvents.BG_BOSS) {
            return -1;
        }
        return Sonic3kCNZEvents.CNZ_BOSS_BG_LOOP_BAND_BASE_Y;
    }

    @Override
    public boolean useFullWidthBackgroundTilemapWindow(int zoneIndex,
                                                       int actIndex,
                                                       int bgCameraX,
                                                       int cachedBgContiguousWidthPx) {
        return zoneIndex == Sonic3kZoneIds.ZONE_MGZ
                && actIndex == 1
                && GameServices.hasRuntime()
                && S3kRuntimeStates.currentMgz(GameServices.zoneRuntimeRegistry())
                .map(state -> state.bgRiseRoutine() == 8)
                .orElse(false)
                && bgCameraX != Integer.MIN_VALUE
                && cachedBgContiguousWidthPx > VDP_BG_PLANE_WIDTH_PX;
    }

    @Override
    public void initZoneFeatures(Rom rom, int zoneIndex, int actIndex, int cameraX) throws IOException {
        aizTransitionRenderFeature.onZoneInit(zoneIndex, actIndex);

        // Initialize water surface manager for HCZ (zone 1)
        // From sonic3k.asm:7777-7787: only HCZ loads Obj_HCZWaveSplash
        // Use a static zone check (not hasWater()) because the WaterSystem
        // is loaded in a later init phase (InitWater runs after InitZoneFeatures).
        if (zoneHasWaterSurface(zoneIndex)) {
            initWaterSurfaceManager(rom, zoneIndex, actIndex);
            // Init water skim handler (Obj_HCZWaterSplash subtype 1)
            // ROM: sonic3k.asm:7786-7787 — spawned alongside Obj_HCZWaveSplash at HCZ init
            HCZWaterSkimHandler.init(rom, actIndex);
        }
        if (zoneIndex == Sonic3kZoneIds.ZONE_SLOT_MACHINE) {
            initSlotMachineRenderer(rom);
        }
    }

    private void initSlotMachineRenderer(Rom rom) {
        if (slotMachinePanelAnimator == null) {
            slotMachinePanelAnimator = new S3kSlotMachinePanelAnimator();
        }
        slotMachinePanelAnimator.init(rom);
    }

    @Override
    public void update(AbstractPlayableSprite player, int cameraX, int zoneIndex) {
        ObjectPlayerQuery playerQuery = playerQueryFromRuntime(player);
        if (zoneIndex == Sonic3kZoneIds.ZONE_AIZ
                && GameServices.module().getLevelEventProvider()
                instanceof Sonic3kLevelEventManager mgr) {
            var events = mgr.getAizEvents();
            if (events != null) {
                events.releaseBattleshipScrollLockCamera();
            }
        }
        if (zoneIndex == Sonic3kZoneIds.ZONE_HCZ && player != null && !player.getDead()) {
            var levelManager = GameServices.levelOrNull();
            int act = levelManager != null ? levelManager.getFeatureActId() : 0;
            HCZWaterTunnelHandler.update(act);
        }
        for (PlayableEntity participant :
                playerQuery.playersFor(ObjectPlayerParticipationPolicy.ALL_ENGINE_PLAYERS)) {
            if (participant instanceof AbstractPlayableSprite playable) {
                updateAizForestFrontPriority(playable, zoneIndex);
            }
        }
    }

    private ObjectPlayerQuery playerQueryFromRuntime(AbstractPlayableSprite player) {
        var spriteManager = GameServices.spritesOrNull();
        List<AbstractPlayableSprite> sidekicks = spriteManager != null
                ? List.copyOf(spriteManager.getSidekicks())
                : List.of();
        return new ObjectPlayerQuery(
                () -> player,
                () -> sidekicks);
    }

    @Override
    public void updatePrePhysics(AbstractPlayableSprite player, int cameraX, int zoneIndex) {
        var levelManager = GameServices.levelOrNull();
        if (zoneIndex == Sonic3kZoneIds.ZONE_AIZ && player != null && !player.getDead()) {
            int act = levelManager != null ? levelManager.getFeatureActId() : 0;
            if (GameServices.module().getLevelEventProvider()
                    instanceof Sonic3kLevelEventManager mgr) {
                mgr.ensureZoneRuntimeStateInstalled();
                var events = mgr.getAizEvents();
                if (events != null) {
                    events.updatePrePhysics(act);
                }
            }
        }
        if (zoneIndex == Sonic3kZoneIds.ZONE_HCZ && player != null && !player.getDead()) {
            int act = levelManager != null ? levelManager.getFeatureActId() : 0;
            HCZWaterSkimHandler.beginFrame();
            if (GameServices.module().getLevelEventProvider()
                    instanceof Sonic3kLevelEventManager mgr) {
                mgr.ensureZoneRuntimeStateInstalled();
                var events = mgr.getHczEvents();
                if (events != null) {
                    int frameCounter = levelManager != null ? levelManager.getFrameCounter() : 0;
                    events.updatePrePhysics(act, frameCounter);
                }
            }
        }
        if (zoneIndex == Sonic3kZoneIds.ZONE_MGZ && player != null && !player.getDead()) {
            int act = levelManager != null ? levelManager.getFeatureActId() : 0;
            if (GameServices.module().getLevelEventProvider()
                    instanceof Sonic3kLevelEventManager mgr) {
                mgr.ensureZoneRuntimeStateInstalled();
                var events = mgr.getMgzEvents();
                if (events != null) {
                    events.updatePrePhysics(act);
                }
            }
        }
    }

    @Override
    public void updateAfterPlayablePhysics(AbstractPlayableSprite player, int cameraX, int zoneIndex) {
        if (player == null || player.getDead()
                || (zoneIndex != Sonic3kZoneIds.ZONE_HCZ && zoneIndex != Sonic3kZoneIds.ZONE_ICZ)) {
            return;
        }
        var levelManager = GameServices.levelOrNull();
        int act = levelManager != null ? levelManager.getFeatureActId() : 0;
        if (GameServices.module().getLevelEventProvider()
                instanceof Sonic3kLevelEventManager mgr) {
            mgr.ensureZoneRuntimeStateInstalled();
            if (zoneIndex == Sonic3kZoneIds.ZONE_HCZ) {
                HCZWaterSkimHandler.updateAfterPlayablePhysics(player);
            }
        }
    }

    @Override
    public void updateAfterObjectExecution(AbstractPlayableSprite player, int cameraX, int zoneIndex) {
        if (player == null || player.getDead()
                || (zoneIndex != Sonic3kZoneIds.ZONE_HCZ && zoneIndex != Sonic3kZoneIds.ZONE_ICZ)) {
            return;
        }
        var levelManager = GameServices.levelOrNull();
        int act = levelManager != null ? levelManager.getFeatureActId() : 0;
        if (GameServices.module().getLevelEventProvider()
                instanceof Sonic3kLevelEventManager mgr) {
            mgr.ensureZoneRuntimeStateInstalled();
            if (zoneIndex == Sonic3kZoneIds.ZONE_HCZ) {
                var events = mgr.getHczEvents();
                if (events != null) {
                    events.updateSlideTerrainAfterPlayablePhysics(act, player);
                }
            } else {
                var events = mgr.getIczEvents();
                if (events != null) {
                    events.updateSlideTerrainAfterPlayablePhysics(act, player);
                }
            }
        }
    }

    protected int getFeatureZoneId() {
        var levelManager = GameServices.levelOrNull();
        return levelManager != null ? levelManager.getFeatureZoneId() : -1;
    }

    private void updateAizForestFrontPriority(AbstractPlayableSprite player, int zoneIndex) {
        if (zoneIndex != Sonic3kZoneIds.ZONE_AIZ || getFeatureActId() != 1 || player == null) {
            return;
        }

        AizZoneRuntimeState aizState = getAizState();
        boolean forestFrontPhaseActive = aizState != null && aizState.isBattleshipForestFrontPhaseActive();

        // During the post-boss cutscene (egg capsule, results, walk-right,
        // bridge collapse), keep the engine display bucket in front of the
        // forest sprites. Obj_PathSwap owns the independent ROM art_tile bit.
        boolean postBossCutsceneActive = com.openggf.game.sonic3k.objects
                .Aiz2BossEndSequenceState.isCutsceneOverrideObjectsActive();

        if (forestFrontPhaseActive || postBossCutsceneActive) {
            player.setPriorityBucket(RenderPriority.MIN);
            forcedAizForestFrontBucketSprites.add(player);
            return;
        }

        if (forcedAizForestFrontBucketSprites.contains(player)
                && canReleaseAizForestFrontBucket(player)) {
            player.setPriorityBucket(RenderPriority.PLAYER_DEFAULT);
            forcedAizForestFrontBucketSprites.remove(player);
        }
    }

    private boolean canReleaseAizForestFrontBucket(AbstractPlayableSprite player) {
        return !player.getDead()
                && !player.isHurt()
                && !player.isDrowningPreDeath()
                && !player.isDrowningDeath();
    }

    private void initWaterSurfaceManager(Rom rom, int zoneIndex, int actIndex) {
        try {
            RomByteReader reader = RomByteReader.fromRom(rom);
            waterSurfaceManager = new Sonic3kWaterSurfaceManager(rom, reader, zoneIndex, actIndex);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to initialize S3K water surface manager", e);
            waterSurfaceManager = null;
        }
    }

    @Override
    public void reset() {
        aizBattleshipRenderFeature.reset();
        aizTransitionRenderFeature.reset();
        waterSurfaceManager = null;
        forcedAizForestFrontBucketSprites.clear();
        if (slotMachinePanelAnimator != null) {
            slotMachinePanelAnimator.cleanup();
            slotMachinePanelAnimator = null;
        }
        HCZWaterTunnelHandler.reset();
        HCZWaterSkimHandler.reset();
    }

    @Override
    public boolean hasCollisionFeatures(int zoneIndex) {
        return false;
    }

    @Override
    public boolean hasWater(int zoneIndex) {
        // Static check: HCZ is the only S3K zone with water surface sprites.
        // Must not query WaterSystem here because this method may be called
        // before InitWater has run (e.g. from initZoneFeatures).
        // ROM: CheckLevelForWater (sonic3k.asm:9754) — zone 1 (HCZ) has water.
        return zoneHasWaterSurface(zoneIndex);
    }

    /**
     * Static check for zones that have water surface rendering.
     * Unlike {@link #hasWater(int)}, this never queries runtime state,
     * so it is safe to call during any init phase.
     */
    private static boolean zoneHasWaterSurface(int zoneIndex) {
        return zoneIndex == Sonic3kZoneIds.ZONE_HCZ;
    }

    public InitialWaveSplashSstOwner initialWaveSplashSstOwner() {
        return initialWaveSplashSstOwner;
    }

    @Override
    public int getWaterLevel(int zoneIndex, int actIndex) {
        WaterSystem waterSystem = GameServices.water();
        return waterSystem.getWaterLevelY(zoneIndex, actIndex);
    }

    @Override
    public void render(Camera camera, int frameCounter) {
        if (waterSurfaceManager != null && waterSurfaceManager.isInitialized()) {
            waterSurfaceManager.render(camera, frameCounter);
        }
        // Render water skim splash sprites (at water level, following player)
        HCZWaterSkimHandler.render(camera);
        var levelManager = GameServices.levelOrNull();
        if (levelManager == null || levelManager.getCurrentZone() != Sonic3kZoneIds.ZONE_SLOT_MACHINE) {
            return;
        }
        if (!(GameServices.module().getBonusStageProvider() instanceof Sonic3kBonusStageCoordinator coordinator)) {
            return;
        }
        if (coordinator.activeSlotRuntime() == null) {
            return;
        }
        if (slotMachinePanelAnimator != null && slotMachinePanelAnimator.isInitialized()) {
            coordinator.activeSlotRuntime().syncSlotMachinePanel(slotMachinePanelAnimator);
        }
        coordinator.activeSlotRuntime().renderSlotLayout(camera);
    }

    @Override
    public com.openggf.graphics.ForegroundWindow foregroundWindow() {
        var levelManager = GameServices.levelOrNull();
        if (levelManager != null && levelManager.getCurrentZone() == Sonic3kZoneIds.ZONE_LBZ
                && levelManager.getFeatureActId() == 1
                && GameServices.module().getLevelEventProvider() instanceof Sonic3kLevelEventManager manager
                && manager.getLbzEvents() != null) {
            return manager.getLbzEvents().foregroundWindow();
        }
        return null;
    }

    @Override
    public void renderAfterForeground(Camera camera) {
        var levelManager = GameServices.levelOrNull();
        if (levelManager == null) {
            return;
        }
        if (levelManager.getCurrentZone() != Sonic3kZoneIds.ZONE_SLOT_MACHINE) {
            return;
        }
        if (!(GameServices.module().getBonusStageProvider() instanceof Sonic3kBonusStageCoordinator coordinator)) {
            return;
        }
        if (coordinator.activeSlotRuntime() == null) {
            return;
        }
        coordinator.activeSlotRuntime().renderSlotMachineFaceForeground();
    }

    protected int resolveSlotDisplayOriginX(Camera camera) {
        var parallax = GameServices.parallaxOrNull();
        int[] hScroll = parallax != null ? parallax.getHScroll() : null;
        if (hScroll != null && hScroll.length > 0) {
            return -M68KMath.unpackFG(hScroll[0]);
        }
        return camera != null ? camera.getX() : 0;
    }

    protected int resolveSlotDisplayOriginY(Camera camera) {
        var parallax = GameServices.parallaxOrNull();
        if (parallax != null) {
            return parallax.getVscrollFactorFG();
        }
        return camera != null ? camera.getY() : 0;
    }

    @Override
    public void renderAfterBackground(Camera camera, int frameCounter) {
        var levelManager = GameServices.levelOrNull();
        if (levelManager != null
                && levelManager.getCurrentZone() == Sonic3kZoneIds.ZONE_SLOT_MACHINE
                && GameServices.currentOrBootstrapGameModule().getBonusStageProvider()
                instanceof Sonic3kBonusStageCoordinator coordinator
                && coordinator.activeSlotRuntime() != null) {
            coordinator.activeSlotRuntime().ensureForegroundGlassPriority();
        }
    }

    @Override
    public void registerSpecialRenderEffects(SpecialRenderEffectRegistry registry, int zoneIndex, int actIndex) {
        if (zoneIndex == Sonic3kZoneIds.ZONE_AIZ) {
            registry.register(aizTransitionRenderFeature);
            if (actIndex == 1) {
                registry.register(aizBattleshipRenderFeature);
            }
        }
        if (zoneIndex == Sonic3kZoneIds.ZONE_HCZ) {
            registry.register(hczBgHighPriorityForegroundOverlayEffect);
            registry.register(hczWallChaseBgOverlayEffect);
        }
        if (zoneIndex == Sonic3kZoneIds.ZONE_ICZ && actIndex == 0) {
            registry.register(iczBigSnowPileBackgroundEffect);
            registry.register(iczBigSnowPilePriorityMaskEffect);
        }
    }

    @Override
    public void registerAdvancedRenderModes(AdvancedRenderModeController controller, int zoneIndex, int actIndex) {
        if (zoneIndex == Sonic3kZoneIds.ZONE_AIZ) {
            controller.register(aizTransitionRenderFeature);
        }
        if (zoneIndex == Sonic3kZoneIds.ZONE_SLOT_MACHINE) {
            controller.register(slotMachineForegroundScrollMode);
        }
        if (zoneIndex == Sonic3kZoneIds.ZONE_MGZ && actIndex == 1) {
            controller.register(mgzCollapseForegroundVScrollMode);
        }
        if (zoneIndex == Sonic3kZoneIds.ZONE_LBZ && actIndex == 0) {
            controller.register(lbzEndingCollapseForegroundVScrollMode);
        }
    }

    @Override
    public int ensurePatternsCached(GraphicsManager graphicsManager, int baseIndex) {
        if (waterSurfaceManager != null) {
            baseIndex = waterSurfaceManager.ensurePatternsCached(graphicsManager, baseIndex);
        }
        baseIndex = HCZWaterSkimHandler.ensurePatternsCached(graphicsManager, baseIndex);
        return baseIndex;
    }

    @Override
    public boolean isIntroOceanPhaseActive(int zoneIndex, int actIndex) {
        if (zoneIndex != 0 || actIndex != 0) {
            return false;
        }
        if (AizPlaneIntroInstance.isMainLevelPhaseActive()) {
            return false;
        }
        return !GameServices.camera().isLevelStarted();
    }

    @Override
    public float getVdpNametableBase(int zoneIndex, int actIndex, int cameraX, int tilemapWidthTiles) {
        if (zoneIndex != 0 || actIndex != 0) {
            return 0.0f;
        }
        int introOffset = AizPlaneIntroInstance.getIntroScrollOffset();
        if (introOffset < 0) {
            return 0.0f;  // Pure ocean phase: no positions overwritten
        }
        // Overflow = total number of nametable column overwrites since scrolling began.
        // BG tile = cameraX / 16 (BG at half speed, 8px/tile).
        // First overwrite when bgTile = VDP_WRAP(64) - SCREEN_TILES(40) + 1 = 25.
        // NOT clamped: the shader decomposes overflow into gen/partial to handle
        // multiple wrap cycles (each position gets overwritten every 64 scroll steps).
        int bgTile = Math.floorDiv(cameraX, 16);
        return Math.max(0.0f, (float) (bgTile - 24));
    }

    @Override
    public boolean shouldSuppressHud(int zoneIndex, int actIndex) {
        if (zoneIndex != 0 || actIndex != 0) {
            return false;
        }
        // Hide HUD during AIZ intro until Camera marks level as started
        return !GameServices.camera().isLevelStarted();
    }

    @Override
    public boolean shouldSuppressInitialTitleCard(int zoneIndex, int actIndex) {
        // sonic3k.asm loc_62B6 (docs/skdisasm/sonic3k.asm:7730-7736): Level:
        // compares Current_zone_and_act against $1701 before installing
        // Obj_TitleCard, branching straight to loc_62FE when it matches. The
        // Super Emerald arena entered through loc_618AC's
        // Restart_level_flag/Special_bonus_entry_flag restart
        // (sonic3k.asm:128415-128421) therefore never creates the title-card
        // owner and never runs the loc_62CC wait loop: its load assembles and
        // presents the destination inside the same iteration.
        // The same branch also skips the card when Act3_flag is set; the
        // engine has no Act3_flag equivalent yet, so only the
        // Current_zone_and_act arm the ROM tests first is modelled here.
        if (zoneIndex == Sonic3kZoneIds.ZONE_DEZ_BOSS_SS_ARENA && actIndex == 1) {
            return true;
        }
        if (zoneIndex != 0 || actIndex != 0) {
            return false;
        }
        SonicConfigurationService configService = GameServices.configuration();
        if (configService.getBoolean(SonicConfiguration.S3K_SKIP_INTROS)) {
            return false;
        }
        String mainCharacter = ActiveGameplayTeamResolver.resolveMainCharacterCode(configService);
        return "sonic".equalsIgnoreCase(mainCharacter);
    }

    @Override
    public boolean shouldSuppressUnderwaterPalette(int zoneIndex, int actIndex) {
        if (zoneIndex != Sonic3kZoneIds.ZONE_HCZ) {
            return false;
        }
        // HCZ1 keeps real water gameplay and a visible surface from the start, but the
        // underwater palette split does not engage until the first water-height switch.
        WaterSystem ws = GameServices.water();
        return actIndex == 0 && ws != null && ws.getWaterLevelY(zoneIndex, actIndex) == 0x0500;
    }

    @Override
    public float getWaterlineOffset(int zoneIndex, int actIndex) {
        // S3K Handle_Onscreen_Water_Height uses Water_level - Camera_Y_pos
        // directly; object placement and foreground masking must align to that.
        return 0.0f;
    }

    @Override
    public boolean useSpriteSatMasking(int zoneIndex) {
        return zoneIndex == Sonic3kZoneIds.ZONE_GUMBALL;
    }

    protected AizZoneRuntimeState getAizState() {
        return GameServices.hasRuntime()
                ? S3kRuntimeStates.currentAiz(GameServices.zoneRuntimeRegistry()).orElse(null)
                : null;
    }

    protected int getFeatureActId() {
        var levelManager = GameServices.levelOrNull();
        return levelManager != null ? levelManager.getFeatureActId() : 0;
    }

}
