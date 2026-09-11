package com.openggf.game.sonic3k;

import com.openggf.game.AbstractLevelEventManager;
import com.openggf.game.CheckpointRuntimeStateProvider;
import com.openggf.game.GameServices;
import com.openggf.game.PlayerCharacter;
import com.openggf.game.PlayableEntity;
import com.openggf.game.rewind.schema.ZoneEventSchemaSidecar;
import com.openggf.game.session.ActiveGameplayTeamResolver;
import com.openggf.game.sonic3k.constants.Sonic3kAnimationIds;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.events.AizObjectEventBridge;
import com.openggf.game.sonic3k.events.AizPreparedTransitionArtBridge;
import com.openggf.game.sonic3k.events.AizPreparedTransitionArtState;
import com.openggf.game.sonic3k.events.CnzObjectEventBridge;
import com.openggf.game.sonic3k.events.IczObjectEventBridge;
import com.openggf.game.sonic3k.events.HczObjectEventBridge;
import com.openggf.game.sonic3k.events.MgzObjectEventBridge;
import com.openggf.game.sonic3k.events.Sonic3kAIZEvents;
import com.openggf.game.sonic3k.events.Sonic3kCNZEvents;
import com.openggf.game.sonic3k.events.Sonic3kHCZEvents;
import com.openggf.game.sonic3k.events.Sonic3kICZEvents;
import com.openggf.game.sonic3k.events.Sonic3kLBZEvents;
import com.openggf.game.sonic3k.events.Sonic3kMHZEvents;
import com.openggf.game.sonic3k.events.Sonic3kMGZEvents;
import com.openggf.game.sonic3k.events.S3kTransitionEventBridge;
import com.openggf.game.sonic3k.runtime.AizZoneRuntimeState;
import com.openggf.game.sonic3k.runtime.CnzZoneRuntimeState;
import com.openggf.game.sonic3k.runtime.HczZoneRuntimeState;
import com.openggf.game.sonic3k.runtime.IczZoneRuntimeState;
import com.openggf.game.sonic3k.runtime.LbzZoneRuntimeState;
import com.openggf.game.sonic3k.runtime.MhzZoneRuntimeState;
import com.openggf.game.sonic3k.runtime.MgzZoneRuntimeState;
import com.openggf.game.sonic3k.runtime.S3kRuntimeStates;
import com.openggf.game.sonic3k.runtime.S3kZoneRuntimeState;
import com.openggf.game.sonic3k.sidekick.Sonic3kSidekickFollowContext;
import com.openggf.game.sonic3k.titlecard.Sonic3kTitleCardManager;
import com.openggf.game.zone.ZoneRuntimeRegistry;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.game.sonic3k.features.HCZWaterSkimHandler;
import com.openggf.game.sonic3k.features.HCZWaterTunnelHandler;
import com.openggf.game.sonic3k.objects.Aiz2BossEndSequenceState;
import com.openggf.game.sonic3k.objects.AizCollapsingLogBridgeObjectInstance;
import com.openggf.game.sonic3k.objects.AizHollowTreeObjectInstance;
import com.openggf.game.sonic3k.objects.AizPlaneIntroInstance;
import com.openggf.game.sonic3k.objects.CutsceneKnucklesCnz2AInstance;
import com.openggf.game.sonic3k.objects.CutsceneKnucklesCnz2BInstance;
import com.openggf.game.sonic3k.objects.CutsceneKnucklesHcz2Instance;
import com.openggf.game.sonic3k.objects.HCZConveyorBeltObjectInstance;
import com.openggf.game.sonic3k.objects.HCZWaterRushObjectInstance;
import com.openggf.game.sonic3k.objects.IczSnowboardArtLoader;
import com.openggf.game.sonic3k.objects.IczSnowboardIntroInstance;
import com.openggf.game.sonic3k.objects.Lbz1GroundLaunchIntroInstance;
import com.openggf.game.sonic3k.objects.MgzDrillingRobotnikInstance;
import com.openggf.camera.Camera;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.sprites.managers.SpriteManager;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.ObjectControlState;
import com.openggf.sprites.playable.SidekickCarryTrigger;
import com.openggf.sprites.playable.SidekickCpuController;
import com.openggf.sprites.playable.TailsCarryController;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Sonic 3&K implementation of dynamic level events.
 * ROM equivalent: ScreenEvents (sonic3k.asm:102228)
 *
 * S3K uses dual foreground/background event routines (Events_routine_fg
 * and Events_routine_bg) with a stride of 4 per state transition.
 * Each zone has two parallel event handlers:
 * <ul>
 *   <li>ScreenEvent (FG) - terrain changes, object spawning, camera boundaries</li>
 *   <li>BackgroundEvent (BG) - parallax, deformation, water level, boss arenas</li>
 * </ul>
 *
 * S3K also uses Boss_flag to gate FG events during boss fights, and
 * branches on Player_mode for character-specific event paths (Sonic/Tails
 * vs Knuckles take different routes through most zones).
 *
 * Phase 1 implements bootstrap selection for AIZ1 intro-skip parity.
 * Zone event handlers will be added incrementally per zone.
 */
public class Sonic3kLevelEventManager extends AbstractLevelEventManager
        implements CheckpointRuntimeStateProvider,
        AizObjectEventBridge, CnzObjectEventBridge, HczObjectEventBridge, MgzObjectEventBridge,
        IczObjectEventBridge, S3kTransitionEventBridge, AizPreparedTransitionArtBridge {
    private static final Logger LOG = Logger.getLogger(Sonic3kLevelEventManager.class.getName());
    private static final int PACHINKO_TOP_EXIT_Y = -0x20;
    private static final int CNZ2_CAMERA_MIN_X = 0x0000;
    private static final int CNZ2_CAMERA_MAX_X = 0x6000;
    private static final int CNZ2_CAMERA_MIN_Y = 0x0580;
    private static final int CNZ2_CAMERA_MAX_Y = 0x1000;
    private static final int EXTRA_MANAGER_BYTES = 30;

    private Sonic3kLoadBootstrap bootstrap = Sonic3kLoadBootstrap.NORMAL;
    private Sonic3kAIZEvents aizEvents;
    private Sonic3kCNZEvents cnzEvents;
    private Sonic3kHCZEvents hczEvents;
    private Sonic3kICZEvents iczEvents;
    private Sonic3kLBZEvents lbzEvents;
    private Sonic3kMGZEvents mgzEvents;
    private Sonic3kMHZEvents mhzEvents;
    private final AizPreparedTransitionArtState aizPreparedTransitionArt =
            new AizPreparedTransitionArtState();
    private final S3kFixedAirCountdownManager fixedAirCountdownManager =
            new S3kFixedAirCountdownManager();
    private int fixedAirCountdownZone = -1;
    private int fixedAirCountdownAct = -1;

    // Tracks whether the intro-fall forced animation is active on each player.
    // Cleared per-player when they land (air → ground transition).
    private boolean introFallActiveOnPlayer;
    private boolean introFallActiveOnSidekick;

    // Set by HCZ Act 1 transition: after the seamless reload to Act 2, the
    // whirlpool descent cutscene should play. Consumed on the first onUpdate()
    // after the transition completes.
    private boolean hczPendingPostTransitionCutscene;

    // Set by MGZ Act 1 transition: after the seamless reload to Act 2, the
    // player (still in signpost victory pose) must be released so they can
    // resume playing. Consumed on the first onUpdate() in MGZ Act 2.
    private boolean mgzPendingPostTransitionRelease;

    // Set by CNZ Act 1 transition: after the seamless reload to Act 2, the
    // ROM's surviving results/end-sign-control object chain later clears
    // _unkFAA8 and restores player control. The engine reload rebuild removes
    // that object chain, so the event manager carries its pending handoff.
    private boolean cnzPendingPostTransitionRelease;
    private int cnzPendingPostTransitionAct2SizeFrames;
    private boolean cnzPostTransitionAct2SizeActive;
    private int cnzAct2MinXAccumulator;
    private int cnzAct2MaxXAccumulator;
    private int cnzAct2MinYAccumulator;
    private int cnzAct2MaxYAccumulator;

    public Sonic3kLevelEventManager() {
        super();
    }

    @Override
    public void initLevel(int zone, int act) {
        super.initLevel(zone, act);
        // Shared-level fixtures may reinitialize the provider after the player
        // roster already exists. Keep the setup presentation decision at the
        // event-provider boundary so the next initial Process_Sprites pass
        // cannot expose a dormant intro sidekick.
        primeSidekickIntroPresentation();
    }

    // =========================================================================
    // AbstractLevelEventManager contract
    // =========================================================================

    @Override
    protected int getRoutineStride() {
        return 4;
    }

    @Override
    protected int getEventDataFgSize() {
        return 6; // Events_fg_0..5
    }

    @Override
    protected int getEventDataBgSize() {
        return 24; // Events_bg[24]
    }

    @Override
    public PlayerCharacter getPlayerCharacter() {
        return ActiveGameplayTeamResolver.resolvePlayerCharacter(GameServices.configuration());
    }

    @Override
    protected void onInitLevel(int zone, int act) {
        bootstrap = Sonic3kBootstrapResolver.resolve(zone, act);
        introFallActiveOnPlayer = false;
        introFallActiveOnSidekick = false;
        boolean seamlessActAdvance = fixedAirCountdownZone == zone
                && fixedAirCountdownAct + 1 == act;
        if (!seamlessActAdvance) {
            fixedAirCountdownManager.reset();
        }
        fixedAirCountdownZone = zone;
        fixedAirCountdownAct = act;

        // ROM: Level_FromSavedGame skips intro when Last_star_post_hit != 0.
        // This covers both special stage return (big ring) and bonus stage return.
        // Guard with hasRuntime() so initLevel can be called from unit tests
        // or snapshot-restore paths that have no active gameplay mode.
        if (bootstrap.mode() == Sonic3kLoadBootstrap.Mode.INTRO
                && GameServices.hasRuntime()
                && (GameServices.level().hasBigRingReturn()
                    || GameServices.level().isBonusStageReturn())) {
            bootstrap = new Sonic3kLoadBootstrap(Sonic3kLoadBootstrap.Mode.SKIP_INTRO, null);
            LOG.info("S3K bootstrap: skipping intro (returning from stage)");
        }

        if (bootstrap.isSkipIntro()) {
            LOG.info("S3K bootstrap: skipping intro for zone " + zone + " act " + act);
        }

        // Create zone-specific event handlers after bootstrap resolution
        if (zone == Sonic3kZoneIds.ZONE_AIZ) {
            aizEvents = new Sonic3kAIZEvents(bootstrap);
            aizEvents.init(act);
        } else {
            aizEvents = null;
        }
        if (zone == Sonic3kZoneIds.ZONE_CNZ) {
            cnzEvents = new Sonic3kCNZEvents();
            cnzEvents.init(act);
        } else {
            cnzEvents = null;
        }
        if (zone == Sonic3kZoneIds.ZONE_HCZ) {
            hczEvents = new Sonic3kHCZEvents(() -> GameServices.rng().nextRaw());
            hczEvents.init(act);
        } else {
            hczEvents = null;
        }
        if (zone == Sonic3kZoneIds.ZONE_ICZ) {
            iczEvents = new Sonic3kICZEvents();
            iczEvents.init(act);
        } else {
            iczEvents = null;
        }
        if (zone == Sonic3kZoneIds.ZONE_LBZ) {
            lbzEvents = new Sonic3kLBZEvents();
            lbzEvents.init(act);
        } else {
            lbzEvents = null;
        }
        if (zone == Sonic3kZoneIds.ZONE_MGZ) {
            mgzEvents = new Sonic3kMGZEvents();
            mgzEvents.init(act);
        } else {
            mgzEvents = null;
        }
        if (zone == Sonic3kZoneIds.ZONE_MHZ) {
            mhzEvents = new Sonic3kMHZEvents();
            mhzEvents.init(act);
        } else {
            mhzEvents = null;
        }

        // Install typed zone runtime state into the registry.
        // Uses getActiveRuntime() to avoid the mode-checking side effects of
        // getCurrent() which can destroy the runtime during level loading.
        installZoneRuntimeState(zone, act);
    }

    @Override
    public void updateFixedInLevelObjects() {
        fixedAirCountdownManager.update();
    }

    void processInitialFixedAirSlot(int playerIndex, AbstractPlayableSprite owner) {
        if (playerIndex == 0) {
            fixedAirCountdownManager.processInitialP1Slot(owner);
        } else if (playerIndex == 1) {
            fixedAirCountdownManager.processInitialP2Slot(owner);
        } else {
            throw new IllegalArgumentException("fixed air slot player index must be 0 or 1");
        }
    }

    @Override
    public boolean ownsFixedDrowningBubbleCadence() {
        return true;
    }

    @Override
    public boolean ownsFixedDrowningBubbleCadence(AbstractPlayableSprite player) {
        return fixedAirCountdownManager.ownsCadenceFor(player);
    }

    @Override
    public boolean isSidekickObjectOrderFollowSteeringContext(
            AbstractPlayableSprite sidekick,
            AbstractPlayableSprite effectiveLeader) {
        return Sonic3kSidekickFollowContext.isObjectOrderFollowSteeringContext(sidekick, effectiveLeader);
    }

    @Override
    public boolean isSidekickObjectOrderFollowNudgeContext(
            AbstractPlayableSprite sidekick,
            AbstractPlayableSprite effectiveLeader) {
        return Sonic3kSidekickFollowContext.isObjectOrderFollowNudgeContext(sidekick, effectiveLeader);
    }

    @Override
    public boolean isSidekickDoorSupportGraceFollowSteeringContext(
            AbstractPlayableSprite sidekick,
            ObjectInstance ridingObject) {
        return Sonic3kSidekickFollowContext.isDoorSupportGraceFollowSteeringContext(sidekick, ridingObject);
    }

    @Override
    public boolean usesSidekickRomVisibleCatchUpMarkerFrameCounterBridge(AbstractPlayableSprite sidekick) {
        return Sonic3kSidekickFollowContext.usesRomVisibleCatchUpMarkerFrameCounterBridge(sidekick);
    }

    @Override
    public boolean shouldEnterSidekickDormantMarker(AbstractPlayableSprite sidekick) {
        return (aizEvents != null && aizEvents.shouldEnterIntroSidekickDormantMarker(sidekick))
                || (iczEvents != null && iczEvents.shouldEnterIntroSidekickDormantMarker(sidekick));
    }

    private void installZoneRuntimeState(int zone, int act) {
        if (!GameServices.hasRuntime()) {
            LOG.fine("Skipping S3K zone runtime registration because no active runtime is installed");
            return;
        }
        ZoneRuntimeRegistry registry = GameServices.zoneRuntimeRegistry();
        PlayerCharacter playerCharacter = getPlayerCharacter();
        if (zone == Sonic3kZoneIds.ZONE_AIZ && aizEvents != null) {
            registry.install(new AizZoneRuntimeState(act, playerCharacter, aizEvents));
        } else if (zone == Sonic3kZoneIds.ZONE_CNZ && cnzEvents != null) {
            registry.install(new CnzZoneRuntimeState(act, playerCharacter, cnzEvents));
        } else if (zone == Sonic3kZoneIds.ZONE_HCZ && hczEvents != null) {
            registry.install(new HczZoneRuntimeState(act, playerCharacter, hczEvents));
        } else if (zone == Sonic3kZoneIds.ZONE_MGZ && mgzEvents != null) {
            registry.install(new MgzZoneRuntimeState(act, playerCharacter, mgzEvents));
        } else if (zone == Sonic3kZoneIds.ZONE_ICZ && iczEvents != null) {
            registry.install(new IczZoneRuntimeState(act, playerCharacter, iczEvents));
        } else if (zone == Sonic3kZoneIds.ZONE_MHZ && mhzEvents != null) {
            registry.install(new MhzZoneRuntimeState(act, playerCharacter, mhzEvents));
        } else if (zone == Sonic3kZoneIds.ZONE_LBZ) {
            registry.install(new LbzZoneRuntimeState(act, playerCharacter));
        } else {
            registry.clear();
        }
    }

    @Override
    public void updateFixedInLevelObjectsBeforeDynamicObjects() {
        if (iczEvents != null) {
            iczEvents.updatePostTitleAct2SizeWorkers();
        }
        if (lbzEvents != null) {
            lbzEvents.updatePostTitleAct2SizeWorkers();
        }
        if (hczEvents != null) {
            hczEvents.updateRetainedCarrierObjectPass(currentAct);
        }
        if (mgzEvents != null) {
            mgzEvents.updateBgRiseObjectAfterPlayerPhysics(currentAct);
            mgzEvents.updateBossTransitionObjectBeforeDynamicObjects(currentAct);
        }
    }

    @Override
    public void updateAfterObjectsBeforeCamera() {
        // The retained owner arms the gradual workers from the title-completion
        // flag in the ordinary ScreenEvents pass. Once armed, the worker itself
        // runs in the object pass before DeformBgLayer consumes its boundary.
        if (currentZone == Sonic3kZoneIds.ZONE_CNZ
                && currentAct == 1) {
            updatePendingCnzAct2LevelSizeChange();
        }
        if (currentZone == Sonic3kZoneIds.ZONE_MGZ
                && currentAct == 1
                && mgzEvents != null) {
            mgzEvents.updateAct2LevelSizeChangeBeforeCamera(currentAct);
        }
    }

    @Override
    protected void onUpdate() {
        handleBonusStageTopExit();
        // After HCZ seamless transition to Act 2: start the whirlpool descent
        // cutscene that spirals Sonic down into the Act 2 starting area.
        var hczTitleCardProvider = GameServices.module().getTitleCardProvider();
        boolean hczRuntimeArtAdmissionPublished = hczTitleCardProvider
                instanceof Sonic3kTitleCardManager titleCard
                && titleCard.hasPublishedInLevelRuntimeArtAdmission();
        if (hczPendingPostTransitionCutscene && hczEvents != null
                && !GameServices.gameState().isEndOfLevelActive()
                && hczTitleCardProvider.ownsInLevelPlayerControlLock()
                && (!hczTitleCardProvider.isOverlayActive()
                || hczRuntimeArtAdmissionPublished)) {
            hczPendingPostTransitionCutscene = false;
            hczEvents.startPostTransitionCutscene();
        }

        // Clear intro-fall forced animation when players land
        updateIntroFallState();

        // ROM: ScreenEvents dispatches to both FG and BG handlers each frame.
        // Boss_flag gates FG events during boss fights.
        if (aizEvents != null && currentZone == Sonic3kZoneIds.ZONE_AIZ) {
            aizEvents.update(currentAct, frameCounter);
        }
        if (cnzEvents != null && currentZone == Sonic3kZoneIds.ZONE_CNZ) {
            cnzEvents.update(currentAct, frameCounter);
        }
        if (hczEvents != null && currentZone == Sonic3kZoneIds.ZONE_HCZ) {
            hczEvents.update(currentAct, frameCounter);
        }
        if (iczEvents != null && currentZone == Sonic3kZoneIds.ZONE_ICZ) {
            iczEvents.update(currentAct, frameCounter);
        }
        if (lbzEvents != null && currentZone == Sonic3kZoneIds.ZONE_LBZ) {
            lbzEvents.update(currentAct, frameCounter);
        }
        if (mgzEvents != null && currentZone == Sonic3kZoneIds.ZONE_MGZ) {
            mgzEvents.updateAfterDynamicObjects(currentAct, frameCounter);
            mgzEvents.updateBackgroundCollisionObjectRelease(currentAct);
        }
        if (mhzEvents != null && currentZone == Sonic3kZoneIds.ZONE_MHZ) {
            mhzEvents.update(currentAct, frameCounter);
        }
        releasePendingMgzPostTransition();
        syncSidekickBoundsToCamera();
    }

    @Override
    public void advanceVblankOnlyState() {
        if (aizEvents != null && currentZone == Sonic3kZoneIds.ZONE_AIZ) {
            aizEvents.advanceVblankOnlyState();
        }
        if (mgzEvents != null && currentZone == Sonic3kZoneIds.ZONE_MGZ && currentAct == 1) {
            mgzEvents.advanceInLevelTitleCardState();
        }
    }

    @Override
    public void updateAfterCameraBoundaryEasing() {
        ZoneRuntimeRegistry registry = GameServices.zoneRuntimeRegistryOrNull();
        LbzZoneRuntimeState state = registry != null
                ? S3kRuntimeStates.currentLbz(registry).orElse(null)
                : null;
        Camera camera = GameServices.cameraOrNull();
        boolean cnzPublishPending = cnzEvents != null
                && cnzEvents.consumeSidekickBoundsPublishAfterCameraEasing();
        boolean lbzPublishPending = state != null
                && state.isLbz1KnucklesBoundaryPublishPending();
        if (!cnzPublishPending && !lbzPublishPending) {
            return;
        }
        if (camera == null) {
            return;
        }
        boolean boundsMovedPastSidekickMirror = sidekickSpritesFor(
                ObjectPlayerParticipationPolicy.ALL_ENGINE_PLAYERS).stream()
                .map(AbstractPlayableSprite::getCpuController)
                .filter(java.util.Objects::nonNull)
                .anyMatch(cpu -> Math.abs((short) (camera.getMaxY()
                        - cpu.getMaxYBound(camera.getMaxY()))) > 8);
        if (lbzPublishPending) {
            if (boundsMovedPastSidekickMirror || camera.getMaxY() == camera.getMaxYTarget()) {
                state.clearLbz1KnucklesBoundaryPublishPending();
            }
        }
        // Tails_Check_Screen_Boundaries reads the live Camera_* words on the
        // following player slot. A producer that moves the death plane before
        // DynamicLevelEvents explicitly publishes that post-easing value;
        // unrelated gradual resize owners retain their native cadence
        // (sonic3k.asm:28410-28443).
        if (cnzPublishPending || boundsMovedPastSidekickMirror) {
            syncSidekickBoundsToCamera();
        }
    }

    /**
     * Requests publication of the post-easing camera globals to the sidekick
     * controller mirror. ROM object routines can change a boundary target
     * before the DynamicLevelEvents tail; the next Tails slot then reads the
     * resulting live Camera_* values.
     */
    @Override
    public void requestSidekickBoundsPublishAfterCameraEasing() {
        if (cnzEvents != null) {
            cnzEvents.requestSidekickBoundsPublishAfterCameraEasing();
        }
        ZoneRuntimeRegistry registry = GameServices.zoneRuntimeRegistryOrNull();
        if (registry != null) {
            S3kRuntimeStates.currentLbz(registry)
                    .ifPresent(LbzZoneRuntimeState::requestLbz1KnucklesBoundaryPublish);
        }
    }

    /**
     * Keep CPU sidekick level bounds aligned with S3K's dynamic camera bounds.
     * Unlike S2, S3K currently has no zone-specific sidekick bound overrides, so
     * mirroring the live camera bounds each frame prevents stale respawn/death
     * limits after resize scripts move the arena.
     */
    private void syncSidekickBoundsToCamera() {
        Camera camera = GameServices.cameraOrNull();
        if (camera == null) {
            return;
        }
        int minX = camera.getMinX();
        int maxX = camera.getMaxX();
        // Tails_Check_Screen_Boundaries reads Camera_max_Y_pos directly;
        // Camera_target_max_Y_pos is only the resize destination and must not
        // loosen the current-frame sidekick death plane.
        int maxY = camera.getMaxY();
        for (AbstractPlayableSprite sidekick : sidekickSpritesFor(ObjectPlayerParticipationPolicy.ALL_ENGINE_PLAYERS)) {
            if (sidekick.getCpuController() != null) {
                sidekick.getCpuController().setLevelBounds(minX, maxX, maxY);
            }
        }
    }

    private void handleBonusStageTopExit() {
        if (currentZone != Sonic3kZoneIds.ZONE_GLOWING_SPHERE) {
            return;
        }
        AbstractPlayableSprite player = GameServices.camera().getFocusedSprite();
        if (player == null || player.getCentreY() >= PACHINKO_TOP_EXIT_Y) {
            return;
        }
        var provider = GameServices.bonusStageOrNull();
        if (provider != null) {
            provider.requestExit();
        }
    }

    /**
     * Clears the forced intro-fall animation on each player once they land.
     * In the ROM, normal movement code overwrites obAnim on landing; here the
     * profile-based animation system needs the forced override cleared so it
     * can resolve the correct ground animation.
     */
    private void updateIntroFallState() {
        if (introFallActiveOnPlayer) {
            AbstractPlayableSprite player = GameServices.camera().getFocusedSprite();
            if (player != null && !player.getAir()) {
                player.setForcedAnimationId(-1);
                player.setAnimationId(Sonic3kAnimationIds.WALK);
                introFallActiveOnPlayer = false;
            }
        }
        if (introFallActiveOnSidekick) {
            boolean anySidekickStillFalling = false;
            for (AbstractPlayableSprite sidekick : sidekickSpritesFor(ObjectPlayerParticipationPolicy.ALL_ENGINE_PLAYERS)) {
                if (sidekick.getAir()) {
                    anySidekickStillFalling = true;
                } else if (sidekick.getForcedAnimationId() >= 0) {
                    sidekick.setForcedAnimationId(-1);
                    sidekick.setAnimationId(Sonic3kAnimationIds.WALK);
                }
            }
            if (!anySidekickStillFalling) {
                introFallActiveOnSidekick = false;
            }
        }
    }

    /**
     * S3K {@code Player_TouchFloor_Check_Spindash} writes {@code anim=Walk}
     * before the current player slot reaches Animate
     * (sonic3k.asm:24325-24329). The ordinary movement path already consumes
     * that write; this callback releases forced-animation owners and mirrors
     * CNZ's later-slot carried-Sonic handoff.
     */
    @Override
    public void onPlayableLandingAnimationWrite(AbstractPlayableSprite playable) {
        // HCZ's wind-tunnel exit byte is a one-shot animation owner. The zone
        // feature pass runs after the player slot, so consume its live exit
        // latch here before this landing reaches Animate.
        HCZWaterTunnelHandler.consumeExitAnimationOnLanding(playable);
        AbstractPlayableSprite focused = GameServices.camera().getFocusedSprite();
        // A carried main player lands in Tails's later object slot, after its
        // own Animate pass, and remains object-controlled until the following
        // CPU pass. Preserve the ROM-visible Walk/previous-animation handoff so
        // release does not restart the shared carried frame/timer. Ordinary
        // player-slot landings still let Animate observe the byte change and
        // reset normally.
        if (playable == focused) {
            for (AbstractPlayableSprite sidekick :
                    sidekickSpritesFor(ObjectPlayerParticipationPolicy.ALL_ENGINE_PLAYERS)) {
                if (sidekick.getTailsCarryController() != null
                        && sidekick.getTailsCarryController().isCarryingMainCharacter()
                        && sidekick.getTailsCarryController().getContext()
                        == TailsCarryController.CarryContext.CNZ) {
                    playable.setAnimationId(Sonic3kAnimationIds.WALK);
                    playable.getAnimationManager()
                            .publishPreviousAnimationId(Sonic3kAnimationIds.WALK.id());
                    break;
                }
            }
        }
        if (introFallActiveOnPlayer && playable == focused) {
            playable.setForcedAnimationId(-1);
            playable.setAnimationId(Sonic3kAnimationIds.WALK);
            introFallActiveOnPlayer = false;
            return;
        }
        if (!introFallActiveOnSidekick) {
            return;
        }
        for (AbstractPlayableSprite sidekick :
                sidekickSpritesFor(ObjectPlayerParticipationPolicy.ALL_ENGINE_PLAYERS)) {
            if (sidekick == playable) {
                sidekick.setForcedAnimationId(-1);
                sidekick.setAnimationId(Sonic3kAnimationIds.WALK);
                return;
            }
        }
    }

    // =========================================================================
    // SpawnLevelMainSprites — zone-specific player state
    // =========================================================================

    /**
     * ROM equivalent: SpawnLevelMainSprites zone-specific branches
     * (sonic3k.asm:8132–8178).
     *
     * <p>Sets animation, airborne flag, and jumping state for zones where
     * the player starts mid-air (falling intros). Called after both the main
     * player and sidekicks have been spawned.
     *
     * <p>Zones handled:
     * <ul>
     *   <li>AIZ1 ($0000): Player 2 enters the dormant marker from the first
     *       ordinary CPU-control dispatch while Obj_AIZPlaneIntro owns the opening pan</li>
     *   <li>HCZ1 ($0100): Sonic/Tails anim $1B (tumble), Knuckles anim $21 (glide drop)</li>
     *   <li>MGZ1 ($0200): anim $1B, airborne (loc_68A6)</li>
     *   <li>ICZ1 ($0500): Sonic player modes spawn Obj_LevelIntroICZ1 and
     *       park CPU Tails in routine-$0A dormant marker (loc_690A / loc_13A74)</li>
     *   <li>LRZ1 ($0900) non-Knuckles: anim $1B, airborne (loc_68A6); Knuckles
     *       is explicitly excluded by the ROM's {@code Player_mode} gate</li>
     *   <li>LRZ3 boss ($1600): anim $1B, airborne (loc_68A6); this manager's
     *       standard level bootstrap does not expose that boss slot</li>
     * </ul>
     */
    public void applyZonePlayerState() {
        // ROM Level_FromSavedGame leaves Last_star_post_hit-owned player state
        // intact on checkpoint/special-stage/bonus returns.  This is runtime
        // saved-state, distinct from the user-facing skip-intros bootstrap
        // mode. No zone-specific intro branch may run first.
        if (hasSavedPlayerReturnState()) {
            return;
        }
        if (currentZone == Sonic3kZoneIds.ZONE_HCZ && currentAct == 0) {
            applyHcz1IntroState();
        }
        // ROM: sonic3k.asm loc_68A6 — simple falling intro (anim $1B + airborne).
        // MGZ1 is unconditional; LRZ1 explicitly skips Knuckles at
        // sonic3k.asm:8161-8165 (Player_mode != 3).
        if (currentZone == Sonic3kZoneIds.ZONE_MGZ && currentAct == 0) {
            applySimpleFallingIntro("MGZ1");
        }
        if (currentZone == Sonic3kZoneIds.ZONE_ICZ && currentAct == 0
                && iczEvents != null && iczEvents.hasSonicSnowboardIntroPlayerMode()) {
            // ROM SpawnLevelMainSprites tests Last_star_post_hit before reaching
            // loc_690A, so checkpoint respawns never create Obj_LevelIntroICZ1.
            // This must happen here, after RestoreCheckpoint, rather than from
            // Sonic3kICZEvents.init() while the new checkpoint owner is empty.
            iczEvents.spawnSonicSnowboardIntro();
            IczSnowboardIntroInstance.applyInitialPlayerLock(GameServices.camera().getFocusedSprite());
            applyIczIntroSidekickDormantMarkersAfterSpawn();
        }
        if (currentZone == Sonic3kZoneIds.ZONE_LBZ && currentAct == 0) {
            spawnLbz1GroundLaunchIntro(false);
        }
        // ROM SpawnLevelMainSprites loc_68D8 (sonic3k.asm:8187-8197): at CNZ Act 1
        // a throwaway Player_2 Tails is spawned to carry solo Sonic in. This runs
        // after the spawnSidekicks load step (which clears temporary sidekicks),
        // so the carrier survives. The handler self-gates on act 0 + SONIC_ALONE.
        if (currentZone == Sonic3kZoneIds.ZONE_CNZ && cnzEvents != null) {
            cnzEvents.spawnSoloLeaderCarryInTailsIfNeeded(currentAct);
        }
        if (currentZone == Sonic3kZoneIds.ZONE_LRZ
                && currentAct == 0
                && getPlayerCharacter() != PlayerCharacter.KNUCKLES) {
            applySimpleFallingIntro("LRZ1");
        }
        primeSidekickIntroPresentation();
    }

    private void primeSidekickIntroPresentation() {
        SpriteManager spriteManager = GameServices.spritesOrNull();
        if (spriteManager == null) {
            return;
        }
        for (AbstractPlayableSprite sidekick : spriteManager.getRegisteredSidekicks()) {
            SidekickCpuController controller = sidekick.getCpuController();
            if (controller != null) {
                controller.suppressInitialLevelEventPresentationIfNeeded();
            }
        }
    }

    private boolean hasSavedPlayerReturnState() {
        if (!GameServices.hasRuntime() || GameServices.levelOrNull() == null) {
            return false;
        }
        var level = GameServices.level();
        return level.hasBigRingReturn()
                || level.isBonusStageReturn()
                || level.getCheckpointState().isActive();
    }

    private void applyIczIntroSidekickDormantMarkersAfterSpawn() {
        SpriteManager spriteManager = GameServices.spritesOrNull();
        if (spriteManager == null || iczEvents == null) {
            return;
        }
        for (AbstractPlayableSprite sidekick : spriteManager.getRegisteredSidekicks()) {
            SidekickCpuController controller = sidekick.getCpuController();
            if (controller != null && shouldEnterSidekickDormantMarker(sidekick)) {
                controller.applyLevelEventDormantMarkerForBootstrap();
            }
        }
    }

    public void applyZonePlayerStateAfterTitleCard() {
        // The title-card release is another caller of the ROM player bootstrap;
        // saved-state returns must suppress the complete seam, including LBZ1's
        // launch intro, before any zone-specific work is attempted.
        if (hasSavedPlayerReturnState()) {
            return;
        }
        applyZonePlayerState();
        if (currentZone == Sonic3kZoneIds.ZONE_LBZ && currentAct == 0) {
            spawnLbz1GroundLaunchIntro(true);
        }
    }

    public void applyCompleteRunSegmentPlayerStateAfterTitleCard() {
        applyZonePlayerStateAfterTitleCard();
        releaseCompleteRunSegmentStartupLatchesAfterTitleCard();
    }

    public void restoreCompleteRunSegmentObjectsAfterPreludeReset() {
        if (currentZone == Sonic3kZoneIds.ZONE_AIZ && currentAct == 0 && aizEvents != null) {
            aizEvents.restoreIntroObjectAfterPreludeReset();
        }
        if (currentZone == Sonic3kZoneIds.ZONE_ICZ && currentAct == 0 && iczEvents != null) {
            iczEvents.restoreSnowboardIntroPostPreludeReset(GameServices.camera().getFocusedSprite());
        }
        if (currentZone == Sonic3kZoneIds.ZONE_LBZ && currentAct == 0) {
            spawnLbz1GroundLaunchIntroForSetupPrelude();
        }
    }

    private void releaseIczStartupObjectControlAfterTitleCard() {
        AbstractPlayableSprite player = GameServices.camera().getFocusedSprite();
        if (player == null) {
            return;
        }
        ObjectControlState.none().applyTo(player);
        player.setObjectMappingFrameControl(false);
        player.clearForcedInputMask();
    }

    private void releaseCompleteRunSegmentStartupLatchesAfterTitleCard() {
        if (currentZone == Sonic3kZoneIds.ZONE_ICZ && currentAct == 0
                && iczEvents != null && iczEvents.hasSonicSnowboardIntroPlayerMode()) {
            releaseIczStartupObjectControlAfterTitleCard();
        }
    }

    /**
     * Binds the carry trigger for complete-run handoffs into a carry-intro
     * zone (CNZ1 / MHZ1) without pre-running any Tails CPU tick.
     *
     * <p>The ROM's first gameplay frame of these segments is the CPU
     * <em>init</em> tick: {@code Tails_CPU_Control} dispatches routine 0 to
     * {@code loc_13A32} / {@code loc_13A8E}, which place Tails at the zone's
     * pickup coordinates and write {@code Tails_CPU_routine = $0C} before
     * {@code rts} (sonic3k.asm:26400-26436). Routine {@code $0C}'s body
     * ({@code loc_13FC2}: {@code x_vel=$100}, {@code sub_1459E} pickup, then
     * fall-through to {@code $0E}) only runs on the frame after that.
     *
     * <p>This previously pre-armed {@code $0C} on the assumption that the
     * recorded handoff began after ROM's init tick. The fixtures say
     * otherwise: MHZ complete-run row 0 records {@code cpu_routine 0x0C} with
     * Sonic still un-grabbed at the raw start location {@code (0xD8, 0x500)}
     * and {@code anim 0x05}, and CNZ complete-run row 0 records the same shape
     * at {@code (0x18, 0x600)}. Row 0 is therefore the init tick itself, and
     * pre-arming ran the {@code $0C} body a frame early — which offset the
     * carried player's x by one pixel for the rest of the carry and delayed
     * every downstream trigger that reads it.
     *
     * <p>Placement, the airborne status and the zeroed velocities all belong
     * to that init tick and are applied by {@code SidekickCpuController}'s own
     * INIT handler, so this only has to make sure the trigger is bound.
     */
    public void armCarryIntroHandoffAfterTitleCard() {
        AbstractPlayableSprite player = GameServices.camera().getFocusedSprite();
        SidekickCarryTrigger carryTrigger = GameServices.module().getSidekickCarryTrigger();
        if (player == null || carryTrigger == null) {
            return;
        }
        PlayerCharacter character = getPlayerCharacter();
        for (AbstractPlayableSprite sidekick : sidekickSpritesFor(ObjectPlayerParticipationPolicy.ALL_ENGINE_PLAYERS)) {
            SidekickCpuController controller = sidekick.getCpuController();
            if (controller == null
                    || !carryTrigger.shouldEnterCarry(currentZone, currentAct, character)
                    || !carryTrigger.isLeaderAtIntroPosition(player)) {
                continue;
            }
            controller.setCarryTrigger(carryTrigger);
        }
    }

    private void spawnLbz1GroundLaunchIntro(boolean armImmediately) {
        ObjectManager objectManager = GameServices.level().getObjectManager();
        if (objectManager == null) {
            return;
        }
        for (ObjectInstance object : objectManager.getActiveObjects()) {
            if (object instanceof Lbz1GroundLaunchIntroInstance intro && !intro.isDestroyed()) {
                if (armImmediately) {
                    intro.applyInitialHoldForLevelStart();
                }
                return;
            }
        }
        ObjectSpawn spawn = new ObjectSpawn(0, 0, 0, 0, 0, false, 0);
        Lbz1GroundLaunchIntroInstance intro =
                objectManager.createDynamicObject(() -> new Lbz1GroundLaunchIntroInstance(spawn));
        if (intro != null && armImmediately) {
            intro.applyInitialHoldForLevelStart();
        }
    }

    private void spawnLbz1GroundLaunchIntroForSetupPrelude() {
        ObjectManager objectManager = GameServices.level().getObjectManager();
        if (objectManager == null) {
            return;
        }
        for (ObjectInstance object : objectManager.getActiveObjects()) {
            if (object instanceof Lbz1GroundLaunchIntroInstance intro && !intro.isDestroyed()) {
                intro.applyInitialHoldForNativeSetupPass();
                return;
            }
        }
        ObjectSpawn spawn = new ObjectSpawn(0, 0, 0, 0, 0, false, 0);
        Lbz1GroundLaunchIntroInstance intro =
                objectManager.createDynamicObject(() -> new Lbz1GroundLaunchIntroInstance(spawn));
        if (intro != null) {
            intro.applyInitialHoldForNativeSetupPass();
        }
    }

    /**
     * HCZ Act 1 intro: all characters start falling from near the top of the
     * level (Y=$0020). ROM: sonic3k.asm loc_6834–loc_6886.
     *
     * <ul>
     *   <li>Sonic/Tails: animation $1B (HURT_FALL — tumble/flail), airborne</li>
     *   <li>Knuckles: animation $21 (GLIDE_DROP), anim_frame 1, airborne</li>
     *   <li>Tails-alone (Player_mode 2): additionally sets jumping=true</li>
     *   <li>Player 2 (sidekick Tails): animation $1B, airborne, jumping=true</li>
     * </ul>
     */
    private void applyHcz1IntroState() {
        AbstractPlayableSprite player = GameServices.camera().getFocusedSprite();
        if (player == null) {
            return;
        }

        PlayerCharacter character = getPlayerCharacter();

        if (character == PlayerCharacter.KNUCKLES) {
            // ROM: move.w #($21<<8)|$21,anim(a1)  — anim=$21, prev_anim=$21
            //      move.b #1,anim_frame(a1)
            player.setForcedAnimationId(Sonic3kAnimationIds.GLIDE_DROP);
        } else {
            // ROM: move.b #$1B,anim(a1)
            player.setForcedAnimationId(Sonic3kAnimationIds.HURT_FALL);
        }
        player.setAir(true);
        introFallActiveOnPlayer = true;

        // ROM: Tails alone (Player_mode == 2) gets jumping=1 so flight is available
        if (character == PlayerCharacter.TAILS_ALONE) {
            player.setJumping(true);
        }

        // Sidekick (Player 2): anim $1B, airborne, jumping=1
        // ROM: sonic3k.asm:8153–8158
        for (AbstractPlayableSprite sidekick : sidekickSpritesFor(ObjectPlayerParticipationPolicy.ALL_ENGINE_PLAYERS)) {
            sidekick.setForcedAnimationId(Sonic3kAnimationIds.HURT_FALL);
            sidekick.setAir(true);
            sidekick.setJumping(true);
            introFallActiveOnSidekick = true;
        }

        LOG.info("HCZ1 intro: set falling state on player(s)");
    }

    /**
     * Simple falling intro shared by MGZ1 and LRZ1 (non-Knuckles).
     * ROM: sonic3k.asm loc_68A6.
     *
     * <p>Sets animation $1B (HURT_FALL) and airborne on both players.
     * Unlike HCZ1, no Knuckles-specific animation or jumping flag.
     */
    private void applySimpleFallingIntro(String zoneName) {
        AbstractPlayableSprite player = GameServices.camera().getFocusedSprite();
        if (player == null) {
            return;
        }

        player.setForcedAnimationId(Sonic3kAnimationIds.HURT_FALL);
        player.setAir(true);
        introFallActiveOnPlayer = true;

        for (AbstractPlayableSprite sidekick : sidekickSpritesFor(ObjectPlayerParticipationPolicy.ALL_ENGINE_PLAYERS)) {
            sidekick.setForcedAnimationId(Sonic3kAnimationIds.HURT_FALL);
            sidekick.setAir(true);
            introFallActiveOnSidekick = true;
        }

        LOG.info(zoneName + " intro: set falling state on player(s)");
    }

    // =========================================================================
    // S3K-specific accessors
    // =========================================================================

    public Sonic3kLoadBootstrap getBootstrap() {
        return bootstrap;
    }

    /** Returns the AIZ zone events handler, or null if not in AIZ. */
    public Sonic3kAIZEvents getAizEvents() {
        return aizEvents;
    }

    @Override
    public void reconcileAfterRewindRestore() {
        if (aizEvents != null) {
            aizEvents.reconcileSequenceAfterRewindRestore();
        }
    }

    @Override
    public java.util.List<com.openggf.game.rewind.RewindSnapshottable<?>> extraRewindAdapters() {
        return java.util.List.of(
                aizPreparedTransitionArt,
                new com.openggf.game.sonic3k.objects.Aiz2BossEndSequenceStaticAdapter(),
                new Sonic3kLevelTriggerStaticAdapter(),
                new com.openggf.game.sonic3k.features.HCZWaterSkimStaticAdapter(),
                new com.openggf.game.sonic3k.features.HCZWaterTunnelStaticAdapter(),
                new com.openggf.game.sonic3k.objects.HCZBreakableBarStaticAdapter(),
                new com.openggf.game.sonic3k.objects.HCZWaterRushPaletteCycleStaticAdapter());
    }

    @Override
    public void setBossFlag(boolean value) {
        if (aizEvents != null) {
            aizEvents.setBossFlag(value);
        }
        if (cnzEvents != null) {
            cnzEvents.setBossFlag(value);
        }
    }

    @Override
    public void setEventsFg5(boolean value) {
        if (aizEvents != null) {
            aizEvents.setEventsFg5(value);
        }
        if (cnzEvents != null) {
            cnzEvents.setEventsFg5(value);
        }
        if (iczEvents != null) {
            iczEvents.setEventsFg5(value);
        }
    }

    @Override
    public void triggerScreenShake(int frames) {
        if (aizEvents != null) {
            aizEvents.triggerScreenShake(frames);
        }
        if (cnzEvents != null) {
            cnzEvents.triggerScreenShake(frames);
        }
        if (iczEvents != null) {
            iczEvents.triggerScreenShake(frames);
        }
    }

    @Override
    public void onBattleshipComplete() {
        if (aizEvents != null) {
            aizEvents.onBattleshipComplete();
        }
    }

    @Override
    public void onBossSmallComplete() {
        if (aizEvents != null) {
            aizEvents.onBossSmallComplete();
        }
    }

    @Override
    public boolean isFireTransitionActive() {
        return aizEvents != null && aizEvents.isFireTransitionActive();
    }

    public boolean isEventsFg5() {
        if (aizEvents != null) {
            return aizEvents.isEventsFg5();
        }
        return iczEvents != null && iczEvents.isEventsFg5();
    }

    @Override
    public boolean isAct2TransitionRequested() {
        if (aizEvents != null) {
            return aizEvents.isAct2TransitionRequested();
        }
        if (cnzEvents != null) {
            return cnzEvents.isAct2TransitionRequested();
        }
        return iczEvents != null && iczEvents.isAct2TransitionRequested();
    }

    /** Returns the CNZ zone events handler, or null if not in CNZ. */
    public Sonic3kCNZEvents getCnzEvents() {
        return cnzEvents;
    }

    @Override
    public void setPendingArenaChunkDestruction(int chunkWorldX, int chunkWorldY) {
        if (cnzEvents != null) {
            cnzEvents.setPendingArenaChunkDestruction(chunkWorldX, chunkWorldY);
        }
    }

    @Override
    public void setBossScrollState(int offsetY, int velocityY) {
        if (cnzEvents != null) {
            cnzEvents.setBossScrollState(offsetY, velocityY);
        }
    }

    @Override
    public void signalMinibossDefeatedForScrollControl() {
        if (cnzEvents != null) {
            cnzEvents.signalMinibossDefeatedForScrollControl();
        }
    }

    @Override
    public boolean consumeMinibossDefeatSignalForScrollControl() {
        return cnzEvents != null && cnzEvents.consumeMinibossDefeatSignalForScrollControl();
    }

    @Override
    public void advanceMinibossBackgroundRoutineAfterScrollSnap() {
        if (cnzEvents != null) {
            cnzEvents.advanceMinibossBackgroundRoutineAfterScrollSnap();
        }
    }

    public void ensureZoneRuntimeStateInstalled() {
        if (!GameServices.hasRuntime()) {
            return;
        }
        var current = GameServices.zoneRuntimeRegistry().current();
        if (current instanceof S3kZoneRuntimeState s3kState
                && s3kState.zoneIndex() == currentZone
                && s3kState.actIndex() == currentAct
                && currentRuntimeStateUsesThisEventInstance(s3kState)) {
            return;
        }
        installZoneRuntimeState(currentZone, currentAct);
    }

    private boolean currentRuntimeStateUsesThisEventInstance(S3kZoneRuntimeState state) {
        return switch (currentZone) {
            case Sonic3kZoneIds.ZONE_AIZ ->
                    state instanceof AizZoneRuntimeState aizState && aizState.isBackedBy(aizEvents);
            case Sonic3kZoneIds.ZONE_CNZ ->
                    state instanceof CnzZoneRuntimeState cnzState && cnzState.isBackedBy(cnzEvents);
            case Sonic3kZoneIds.ZONE_HCZ ->
                    state instanceof HczZoneRuntimeState hczState && hczState.isBackedBy(hczEvents);
            case Sonic3kZoneIds.ZONE_MGZ ->
                    state instanceof MgzZoneRuntimeState mgzState && mgzState.isBackedBy(mgzEvents);
            case Sonic3kZoneIds.ZONE_ICZ ->
                    state instanceof IczZoneRuntimeState iczState && iczState.isBackedBy(iczEvents);
            case Sonic3kZoneIds.ZONE_MHZ ->
                    state instanceof MhzZoneRuntimeState mhzState && mhzState.isBackedBy(mhzEvents);
            case Sonic3kZoneIds.ZONE_LBZ -> state instanceof LbzZoneRuntimeState;
            default -> false;
        };
    }

    @Override
    public void setWallGrabSuppressed(boolean value) {
        if (cnzEvents != null) {
            cnzEvents.setWallGrabSuppressed(value);
        }
    }

    @Override
    public void setWaterButtonArmed(boolean value) {
        if (cnzEvents != null) {
            cnzEvents.setWaterButtonArmed(value);
        }
    }

    @Override
    public boolean isWaterButtonArmed() {
        return cnzEvents != null && cnzEvents.isWaterButtonArmed();
    }

    @Override
    public void setWaterTargetY(int targetY) {
        if (cnzEvents != null) {
            cnzEvents.setWaterTargetY(targetY);
        }
    }

    @Override
    public void setWaterMeanLevel(int meanY) {
        if (cnzEvents != null) {
            cnzEvents.setWaterMeanLevel(meanY);
        }
    }

    @Override
    public void beginKnucklesTeleporterRoute() {
        if (cnzEvents != null) {
            cnzEvents.beginKnucklesTeleporterRoute();
        }
    }

    @Override
    public void endKnucklesTeleporterRoute() {
        if (cnzEvents != null) {
            cnzEvents.endKnucklesTeleporterRoute();
        }
    }

    @Override
    public void markTeleporterBeamSpawned() {
        if (cnzEvents != null) {
            cnzEvents.markTeleporterBeamSpawned();
        }
    }

    /** Returns the HCZ zone events handler, or null if not in HCZ. */
    public Sonic3kMGZEvents getMgzEvents() {
        return mgzEvents;
    }

    /** Returns the LBZ zone events handler, or null if not in LBZ. */
    public Sonic3kLBZEvents getLbzEvents() {
        return lbzEvents;
    }

    @Override
    public void triggerBossCollapseHandoff() {
        if (mgzEvents != null) {
            mgzEvents.triggerBossCollapseHandoff();
        }
    }

    @Override
    public void completeDrillingRobotnikFlee() {
        if (mgzEvents != null) {
            mgzEvents.completeDrillingRobotnikFlee();
        }
    }

    public Sonic3kHCZEvents getHczEvents() {
        return hczEvents;
    }

    public Sonic3kICZEvents getIczEvents() {
        return iczEvents;
    }

    public Sonic3kMHZEvents getMhzEvents() {
        return mhzEvents;
    }

    @Override
    public void setHczBossFlag(boolean value) {
        if (hczEvents != null) {
            hczEvents.setBossFlag(value);
        }
    }

    /**
     * Sets/clears the pending post-transition cutscene flag for HCZ Act 1→2.
     */
    public void setHczPendingPostTransitionCutscene(boolean pending) {
        this.hczPendingPostTransitionCutscene = pending;
    }

    /**
     * Sets Events_fg_5 on the current zone's event handler.
     * ROM: Obj_LevelResultsCreate sets this for Act 1 zones (except AIZ and ICZ)
     * to trigger the background event act transition.
     */
    public void setEventsFg5ForActTransition() {
        if (cnzEvents != null) {
            cnzEvents.setEventsFg5(true);
        }
        if (hczEvents != null) {
            hczEvents.setEventsFg5(true);
        }
        if (mgzEvents != null) {
            mgzEvents.setEventsFg5(true);
        }
        if (mhzEvents != null) {
            mhzEvents.setActTransitionFlag(true);
        }
        if (lbzEvents != null) {
            lbzEvents.setEventsFg5(true);
        }
        // Other zones' event handlers will be added here as implemented.
    }

    @Override
    public int resultsCreateGateDispatches() {
        return cnzEvents != null && cnzEvents.isAwaitingSeamlessReloadSignal() ? 8 : 9;
    }

    @Override
    public void signalActTransition() {
        setEventsFg5ForActTransition();
    }

    @Override
    public void requestHczPostTransitionCutscene() {
        setHczPendingPostTransitionCutscene(true);
    }

    @Override
    public boolean restorePendingPostResultsPlayerControl() {
        boolean titleCardCompletionFlagStillOwned = hczPendingPostTransitionCutscene;
        if (hczPendingPostTransitionCutscene && hczEvents != null) {
            hczEvents.restorePostResultsPlayerControl();
        }
        if (mgzPendingPostTransitionRelease) {
            // The carried results owner clears its active transition state at
            // this publication boundary; release the players on that same
            // native owner pass and let the following player slot select its
            // movement animation.
            releasePendingMgzPostTransition();
        }
        if (cnzPendingPostTransitionRelease) {
            // CNZ's retained Obj_EndSignControl follows the carried results
            // owner in the same Process_Sprites pass. Consume the modeled
            // _unkFAA8-clear publication directly instead of estimating its
            // arrival from elapsed frames.
            releasePendingCnzPostTransition();
        }
        return titleCardCompletionFlagStillOwned;
    }

    @Override
    public void preparePreloadedActTitleCardCompletion() {
        if (iczEvents != null) {
            iczEvents.preparePostTitleAct2SizeChange();
        }
        if (lbzEvents != null) {
            lbzEvents.preparePostTitleAct2SizeChange();
        }
    }

    @Override
    public void preparePreloadedActTitleCardRuntimeArtAdmission() {
        if (lbzEvents != null) {
            lbzEvents.preparePostTitleAct2SizeChange();
        }
    }

    @Override
    public int preloadedActCameraReleaseAdditionalDispatches() {
        return iczEvents == null
                ? S3kTransitionEventBridge.super
                        .preloadedActCameraReleaseAdditionalDispatches()
                : iczEvents.preloadedActCameraReleaseAdditionalDispatches();
    }

    @Override
    public void requestMgzPostTransitionRelease() {
        this.mgzPendingPostTransitionRelease = true;
    }

    @Override
    public void requestCnzPostTransitionRelease() {
        this.cnzPendingPostTransitionRelease = true;
        // Wait on the ROM-owned in-level title-card completion flag. A fixed
        // elapsed-frame estimate drifts when object-slot/Kos queue timing moves.
        this.cnzPendingPostTransitionAct2SizeFrames = -1;
        this.cnzPostTransitionAct2SizeActive = false;
        this.cnzAct2MinXAccumulator = 0;
        this.cnzAct2MaxXAccumulator = 0;
        this.cnzAct2MinYAccumulator = 0;
        this.cnzAct2MaxYAccumulator = 0;
    }

    /**
     * After the MGZ1 → MGZ2 seamless reload, release the player (and sidekicks)
     * from the signpost victory pose so normal play resumes. The ROM's
     * MGZ1BGE_Transition does not run a cutscene; the player simply continues
     * under their own control once the level has reloaded.
     */
    private void releasePendingMgzPostTransition() {
        if (!mgzPendingPostTransitionRelease) {
            return;
        }
        if (currentZone != Sonic3kZoneIds.ZONE_MGZ || currentAct != 1) {
            return;
        }
        // MGZ1BGE_Transition reloads Act 2 behind the still-live
        // Obj_LevelResults owner. Retain both ending poses until that owner
        // clears _unkFAA8 at its actual post-tally exit.
        if (GameServices.gameState().isEndOfLevelActive()) {
            return;
        }
        mgzPendingPostTransitionRelease = false;
        if (mgzEvents != null) {
            // The retained EndSignControl next waits for the in-level title
            // card's End_of_level_flag before running Change_Act2Sizes. The
            // engine's shared results exit publishes that flag to trigger
            // seamless handlers; native MGZ1 results does not retain it after
            // Load_Level, so clear that consumed transition signal here.
            GameServices.gameState().setEndOfLevelFlag(false);
            mgzEvents.armAct2LevelSizeChange();
        }

        AbstractPlayableSprite player = GameServices.camera().getFocusedSprite();
        if (player != null) {
            ObjectControlState.none().applyTo(player);
            player.setControlLocked(false);
            player.setForcedAnimationId(-1);
            player.setAnimationId(Sonic3kAnimationIds.WAIT);
        }
        for (AbstractPlayableSprite sidekick : sidekickSpritesFor(ObjectPlayerParticipationPolicy.ALL_ENGINE_PLAYERS)) {
            ObjectControlState.none().applyTo(sidekick);
            sidekick.setControlLocked(false);
            sidekick.setForcedAnimationId(-1);
            sidekick.setAnimationId(Sonic3kAnimationIds.WAIT);
        }
        LOG.info("MGZ: released player from victory pose after Act 1 → Act 2 reload");
    }

    /**
     * CNZ1's act reload happens while Obj_LevelResults and Obj_EndSignControl
     * are still alive in ROM. Later, LevelResults loc_2DD06 clears _unkFAA8 and
     * EndSignControlAwaitStart calls Restore_PlayerControl for P1/P2
     * (docs/skdisasm/sonic3k.asm:62708-62720,180407-180412,180359-180367).
     * The engine reload rebuilds the object manager, so this local handoff
     * consumes the results owner's publication boundary without retaining the
     * act-1 controller object.
     */
    private void releasePendingCnzPostTransition() {
        if (!cnzPendingPostTransitionRelease) {
            return;
        }
        cnzPendingPostTransitionRelease = false;

        AbstractPlayableSprite player = GameServices.camera().getFocusedSprite();
        if (player != null) {
            restoreControlAfterCnzActTransition(player);
        }
        for (AbstractPlayableSprite sidekick : sidekickSpritesFor(ObjectPlayerParticipationPolicy.ALL_ENGINE_PLAYERS)) {
            restoreControlAfterCnzActTransition(sidekick);
        }
        LOG.info("CNZ: released player control after Act 1 → Act 2 results handoff");
    }

    private void restoreControlAfterCnzActTransition(AbstractPlayableSprite sprite) {
        ObjectControlState.none().applyTo(sprite);
        sprite.setControlLocked(false);
        sprite.setForcedAnimationId(-1);
        sprite.setAir(false);
        // Restore_PlayerControl2 writes both anim and prev_anim to WAIT ($05)
        // before clearing their frame state (docs/skdisasm/sonic3k.asm:180359-180367).
        sprite.setAnimationId(Sonic3kAnimationIds.WAIT);
    }

    /**
     * ROM: after the surviving EndSignControl object has restored control, its
     * DoStart phase waits for the in-level title-card End_of_level_flag, calls
     * Change_Act2Sizes, and spawns the gradual level-size children. CNZ's
     * seamless reload removes that object chain in the engine, so this bridge
     * mirrors the later Change_Act2Sizes/Obj_*Gradual sequence locally
     * (docs/skdisasm/sonic3k.asm:180415-180419,180575-180632,
     * 178154-178168,178192-178224,197460-197468).
     */
    private void updatePendingCnzAct2LevelSizeChange() {
        if (currentZone != Sonic3kZoneIds.ZONE_CNZ || currentAct != 1) {
            return;
        }
        if (cnzPendingPostTransitionAct2SizeFrames < 0) {
            if (!GameServices.gameState().isEndOfLevelFlag()) {
                return;
            }
            // Obj_TitleCardWait2 publishes End_of_level_flag from its own slot
            // inside Process_Sprites (docs/skdisasm/sonic3k.asm:62244-62279),
            // and the retained Obj_EndSignControl slot is walked *ahead* of it
            // in the ascending Process_Sprites walk
            // (docs/skdisasm/sonic3k.asm:35965-35995; see the same slot
            // relationship recorded in Sonic3kTitleCardManager). Its
            // `tst.b (End_of_level_flag)` poll for the publishing pass has
            // therefore already run and taken the `beq` exit
            // (docs/skdisasm/sonic3k.asm:180419-180424), so DoStart is first
            // satisfied on the following pass. Latch the publication here and
            // run the DoStart body on the next dispatch.
            GameServices.gameState().setEndOfLevelFlag(false);
            cnzPendingPostTransitionAct2SizeFrames = 1;
            return;
        }
        if (cnzPendingPostTransitionAct2SizeFrames > 0) {
            // The pass on which Obj_EndSignControlDoStart actually observes the
            // flag: it calls Change_Act2Sizes, which falls through into
            // Make_LevelSizeObj (docs/skdisasm/sonic3k.asm:180580-180604).
            // Make_LevelSizeObj allocates the gradual children with
            // AllocateObjectAfterCurrent (:176924-176950,:37917-37930), i.e.
            // into slots *after* the current one, so they are reached by this
            // same ascending Process_Sprites walk and take their first update
            // on this dispatch. There is no further delay after this point --
            // do not reintroduce a countdown here.
            cnzPendingPostTransitionAct2SizeFrames = 0;
            cnzPostTransitionAct2SizeActive = true;
            cnzAct2MinXAccumulator = 0;
            cnzAct2MaxXAccumulator = 0;
            cnzAct2MinYAccumulator = 0;
            cnzAct2MaxYAccumulator = 0;
        }
        if (!cnzPostTransitionAct2SizeActive) {
            return;
        }

        Camera camera = GameServices.cameraOrNull();
        if (camera == null) {
            return;
        }

        boolean minXDone = decrementCameraMinXGradual(camera);
        boolean maxXDone = incrementCameraMaxXGradual(camera);
        boolean minYDone = decrementCameraMinYGradual(camera);
        boolean maxYDone = incrementCameraMaxYGradual(camera);
        camera.setMaxYTarget((short) CNZ2_CAMERA_MAX_Y);
        if (minXDone && maxXDone && minYDone && maxYDone) {
            cnzPostTransitionAct2SizeActive = false;
        }
    }

    private boolean decrementCameraMinXGradual(Camera camera) {
        int current = camera.getMinX() & 0xFFFF;
        cnzAct2MinXAccumulator += 0x4000;
        int delta = cnzAct2MinXAccumulator >> 16;
        int next = current - delta;
        if (next <= CNZ2_CAMERA_MIN_X) {
            camera.setMinX((short) CNZ2_CAMERA_MIN_X);
            return true;
        }
        camera.setMinX((short) next);
        return false;
    }

    private boolean incrementCameraMaxXGradual(Camera camera) {
        int current = camera.getMaxX() & 0xFFFF;
        cnzAct2MaxXAccumulator += 0x4000;
        int delta = cnzAct2MaxXAccumulator >> 16;
        int next = current + delta;
        if (next >= CNZ2_CAMERA_MAX_X) {
            camera.setMaxX((short) CNZ2_CAMERA_MAX_X);
            return true;
        }
        camera.setMaxX((short) next);
        return false;
    }

    private boolean decrementCameraMinYGradual(Camera camera) {
        int current = camera.getMinY() & 0xFFFF;
        cnzAct2MinYAccumulator += 0x4000;
        int delta = cnzAct2MinYAccumulator >> 16;
        int next = current - delta;
        if (next <= CNZ2_CAMERA_MIN_Y) {
            camera.setMinY((short) CNZ2_CAMERA_MIN_Y);
            return true;
        }
        camera.setMinY((short) next);
        return false;
    }

    private boolean incrementCameraMaxYGradual(Camera camera) {
        int current = camera.getMaxY() & 0xFFFF;
        cnzAct2MaxYAccumulator += 0x8000;
        int delta = cnzAct2MaxYAccumulator >> 16;
        int next = current + delta;
        if (next >= CNZ2_CAMERA_MAX_Y) {
            camera.setMaxY((short) CNZ2_CAMERA_MAX_Y);
            return true;
        }
        camera.setMaxY((short) next);
        return false;
    }

    private List<AbstractPlayableSprite> sidekickSpritesFor(ObjectPlayerParticipationPolicy policy) {
        ObjectPlayerQuery query = playerQueryFromGameServices();
        PlayableEntity mainPlayer = query.mainPlayerOrNull();
        List<AbstractPlayableSprite> sidekicks = new ArrayList<>();
        for (PlayableEntity participant : query.playersFor(policy)) {
            if (participant != mainPlayer && participant instanceof AbstractPlayableSprite sidekick) {
                sidekicks.add(sidekick);
            }
        }
        return sidekicks;
    }

    private ObjectPlayerQuery playerQueryFromGameServices() {
        Camera camera = GameServices.cameraOrNull();
        AbstractPlayableSprite mainPlayer = camera != null ? camera.getFocusedSprite() : null;
        SpriteManager sprites = GameServices.spritesOrNull();
        List<? extends PlayableEntity> sidekicks = sprites != null
                ? List.copyOf(sprites.getSidekicks())
                : List.of();
        return new ObjectPlayerQuery(
                () -> mainPlayer,
                () -> sidekicks);
    }

    /**
     * Returns the current Dynamic_resize_routine value from the active zone
     * events handler. ROM: Saved2_dynamic_resize_routine.
     */
    public int getDynamicResizeRoutine() {
        if (aizEvents != null) {
            return aizEvents.getDynamicResizeRoutine();
        }
        if (cnzEvents != null) {
            return cnzEvents.getDynamicResizeRoutine();
        }
        if (hczEvents != null) {
            return hczEvents.getDynamicResizeRoutine();
        }
        if (iczEvents != null) {
            return iczEvents.getDynamicResizeRoutine();
        }
        if (mgzEvents != null) {
            return mgzEvents.getDynamicResizeRoutine();
        }
        return 0;
    }

    @Override
    public int checkpointDynamicResizeRoutine() {
        return getDynamicResizeRoutine();
    }

    /**
     * Restores the Dynamic_resize_routine after a big ring special stage return.
     * Must be called AFTER initLevel() (which resets it to 0).
     */
    public void setDynamicResizeRoutine(int routine) {
        if (aizEvents != null) {
            aizEvents.setDynamicResizeRoutine(routine);
        }
        if (cnzEvents != null) {
            cnzEvents.setDynamicResizeRoutine(routine);
        }
        if (hczEvents != null) {
            hczEvents.setDynamicResizeRoutine(routine);
        }
        if (iczEvents != null) {
            iczEvents.setDynamicResizeRoutine(routine);
        }
        if (mgzEvents != null) {
            mgzEvents.setDynamicResizeRoutine(routine);
        }
    }

    /**
     * S3K zone handlers maintain their own routine counters independently of
     * the base class fields. Override to read from the active zone handler so
     * callers (GameLoop bonus stage capture) get the correct value.
     */
    @Override
    public int getEventRoutineFg() {
        return getDynamicResizeRoutine();
    }

    /**
     * S3K zone handlers maintain their own BG routine counters independently of
     * the base class fields. CNZ now persists a local background routine for
     * save/restore parity, so callers must read from the active zone handler.
     */
    @Override
    public int getEventRoutineBg() {
        if (cnzEvents != null) {
            return cnzEvents.getBackgroundRoutine();
        }
        return super.getEventRoutineBg();
    }

    /**
     * Restores the event routine state after a bonus/special stage return.
     * Propagates to the active zone handler so its internal state machine
     * resumes from the saved position instead of replaying from 0.
     */
    @Override
    public void restoreEventRoutineState(int routineFg, int routineBg) {
        super.restoreEventRoutineState(routineFg, routineBg);
        setDynamicResizeRoutine(routineFg);
        if (cnzEvents != null) {
            cnzEvents.setBackgroundRoutine(routineBg);
        }
    }

    /**
     * Resets mutable state including static/global state in S3K event helpers.
     * Extends the base {@link AbstractLevelEventManager#resetState()} to also
     * clear cross-object cutscene refs, HCZ helper gates, AIZ fire/tree/intro
     * state, and other runtime handoff state that would otherwise leak across
     * level loads and test iterations.
     */
    @Override
    public void resetState() {
        super.resetState();
        aizPreparedTransitionArt.reset();
        introFallActiveOnPlayer = false;
        introFallActiveOnSidekick = false;
        clearPostTransitionHandoffState();
        Sonic3kAIZEvents.resetGlobalState();
        CutsceneKnucklesCnz2AInstance.clearActiveInstance();
        CutsceneKnucklesCnz2BInstance.clearActiveInstance();
        CutsceneKnucklesHcz2Instance.clearActiveInstance();
        HCZWaterTunnelHandler.reset();
        HCZWaterSkimHandler.reset();
        HCZWaterRushObjectInstance.HCZBreakableBarState.reset();
        HCZWaterRushObjectInstance.HCZWaterRushPaletteCycleGate.reset();
        HCZConveyorBeltObjectInstance.resetLoadArray();
        Aiz2BossEndSequenceState.reset();
        AizCollapsingLogBridgeObjectInstance.setDrawBridgeBurnActive(false);
        AizHollowTreeObjectInstance.resetTreeRevealCounter();
        AizPlaneIntroInstance.resetIntroPhaseState();
        IczSnowboardArtLoader.reset();
    }

    @Override
    public void retainAizFireOverlay(byte[] tiles8x8) {
        aizPreparedTransitionArt.retainFireOverlay(tiles8x8);
    }

    @Override
    public byte[] aizFireOverlayCopy() {
        return aizPreparedTransitionArt.fireOverlayCopy();
    }

    @Override
    public int aizFireOverlayTileCount() {
        return aizPreparedTransitionArt.fireOverlayTileCount();
    }

    private void clearPostTransitionHandoffState() {
        hczPendingPostTransitionCutscene = false;
        mgzPendingPostTransitionRelease = false;
        cnzPendingPostTransitionRelease = false;
        cnzPendingPostTransitionAct2SizeFrames = 0;
        cnzPostTransitionAct2SizeActive = false;
        cnzAct2MinXAccumulator = 0;
        cnzAct2MaxXAccumulator = 0;
        cnzAct2MinYAccumulator = 0;
        cnzAct2MaxYAccumulator = 0;
    }
    /**
     * Intercepts pit death in S3K bonus stages (Gumball, Pachinko, Slots).
     * ROM: Obj_GumballMachine init does st (Disable_death_plane).w — bonus
     * stages don't kill the player for falling off the bottom. Instead,
     * falling through triggers the stage exit via the exit trigger child.
     * <p>
     * If the player falls below the exit trigger (past the bottom of the stage),
     * force the stage to end.
     */
    @Override
    public boolean interceptPitDeath(AbstractPlayableSprite player) {
        if (mgzEvents != null && mgzEvents.isBossTransitionDeathPlaneDisabled()) {
            return true;
        }
        // ROM loc_6C4BE sets Disable_death_plane in the later MGZ end-boss
        // object slot. Player_LevelBound runs earlier in the same RunObjects
        // pass, so expose that already-determined write from the boss's native
        // routine/terrain state instead of briefly killing and reviving Sonic.
        ObjectManager objectManager = GameServices.levelOrNull() != null
                ? GameServices.levelOrNull().getObjectManager()
                : null;
        if (mgzEvents != null && objectManager != null
                && objectManager.activeObjectsOfType(MgzDrillingRobotnikInstance.class).stream()
                .anyMatch(MgzDrillingRobotnikInstance::willPublishDeathPlaneDisableThisObjectPass)) {
            return true;
        }
        if (isInBonusStage()) {
            // Trigger bonus stage exit if player has fallen out of the arena
            com.openggf.game.BonusStageProvider provider =
                    com.openggf.game.GameServices.bonusStageOrNull();
            if (provider != null) {
                provider.requestExit();
            }
            return true; // Suppress death
        }
        return false;
    }

    private boolean isInBonusStage() {
        return currentZone == Sonic3kZoneIds.ZONE_GUMBALL
                || currentZone == Sonic3kZoneIds.ZONE_GLOWING_SPHERE
                || currentZone == Sonic3kZoneIds.ZONE_SLOT_MACHINE;
    }

    // =========================================================================
    // RewindSnapshottable extra-state hooks (C.4)
    // =========================================================================

    /** Accessor for test/diagnostic use — returns the S3K zone event handler for AIZ. */
    public Sonic3kAIZEvents getAizEventsForTest()  { return aizEvents; }
    /** Accessor for test/diagnostic use — returns the S3K zone event handler for HCZ. */
    public Sonic3kHCZEvents getHczEventsForTest()  { return hczEvents; }
    /** Accessor for test/diagnostic use — returns the S3K zone event handler for CNZ. */
    public Sonic3kCNZEvents getCnzEventsForTest()  { return cnzEvents; }
    /** Accessor for test/diagnostic use — returns the S3K zone event handler for MGZ. */
    public Sonic3kMGZEvents getMgzEventsForTest()  { return mgzEvents; }
    /** Accessor for test/diagnostic use — returns the S3K zone event handler for MHZ. */
    public Sonic3kMHZEvents getMhzEventsForTest()  { return mhzEvents; }

    @Override
    protected byte[] captureExtra() {
        // Layout:
        //   30 bytes  manager-level (bootstrap mode ordinal + 4 booleans + CNZ release/size counters + size-change state)
        //   1 byte    aiz handler present flag
        //   4 bytes   aiz schema payload length, when present
        //   N bytes   aiz schema payload, when present
        //   1 byte    hcz handler present flag
        //   4 bytes   hcz schema payload length, when present
        //   N bytes   hcz schema payload, when present
        //   1 byte    cnz handler present flag
        //   4 bytes   cnz schema payload length, when present
        //   N bytes   cnz schema payload, when present
        //   1 byte    mgz handler present flag
        //   4 bytes   mgz schema payload length, when present
        //   N bytes   mgz schema payload, when present
        //   1 byte    mhz handler present flag
        //   4 bytes   mhz schema payload length, when present
        //   N bytes   mhz schema payload, when present
        //   1 byte    icz handler present flag
        //   4 bytes   icz schema payload length, when present
        //   N bytes   icz schema payload, when present
        //   1 byte    lbz handler present flag
        //   4 bytes   lbz schema payload length, when present
        //   N bytes   lbz schema payload, when present
        //   28 bytes  fixed Breathing_bubbles/Breathing_bubbles_P2 sidecars
        //   4 bytes   fixed-air countdown owning zone
        //   4 bytes   fixed-air countdown owning act
        byte[] aizBytes = aizEvents != null ? ZoneEventSchemaSidecar.capture(aizEvents) : null;
        byte[] hczBytes = hczEvents != null ? ZoneEventSchemaSidecar.capture(hczEvents) : null;
        byte[] cnzBytes = cnzEvents != null ? ZoneEventSchemaSidecar.capture(cnzEvents) : null;
        byte[] mgzBytes = mgzEvents != null ? ZoneEventSchemaSidecar.capture(mgzEvents) : null;
        byte[] mhzBytes = mhzEvents != null ? ZoneEventSchemaSidecar.capture(mhzEvents) : null;
        byte[] iczBytes = iczEvents != null ? ZoneEventSchemaSidecar.capture(iczEvents) : null;
        byte[] lbzBytes = lbzEvents != null ? ZoneEventSchemaSidecar.capture(lbzEvents) : null;
        int size = EXTRA_MANAGER_BYTES;
        size += aizBytes != null ? 1 + Integer.BYTES + aizBytes.length : 1;
        size += hczBytes != null ? 1 + Integer.BYTES + hczBytes.length : 1;
        size += cnzBytes != null ? 1 + Integer.BYTES + cnzBytes.length : 1;
        size += mgzBytes != null ? 1 + Integer.BYTES + mgzBytes.length : 1;
        size += mhzBytes != null ? 1 + Integer.BYTES + mhzBytes.length : 1;
        size += iczBytes != null ? 1 + Integer.BYTES + iczBytes.length : 1;
        size += lbzBytes != null ? 1 + Integer.BYTES + lbzBytes.length : 1;
        size += S3kFixedAirCountdownManager.REWIND_STATE_BYTES + 2 * Integer.BYTES;
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(size);
        // Manager-level
        buf.put((byte) bootstrap.mode().ordinal());
        buf.put((byte) (introFallActiveOnPlayer  ? 1 : 0));
        buf.put((byte) (introFallActiveOnSidekick ? 1 : 0));
        buf.put((byte) (hczPendingPostTransitionCutscene ? 1 : 0));
        buf.put((byte) (mgzPendingPostTransitionRelease  ? 1 : 0));
        // Keep the existing four-byte snapshot field width for compatibility.
        buf.putInt(cnzPendingPostTransitionRelease ? 1 : 0);
        buf.putInt(cnzPendingPostTransitionAct2SizeFrames);
        buf.put((byte) (cnzPostTransitionAct2SizeActive ? 1 : 0));
        buf.putInt(cnzAct2MinXAccumulator);
        buf.putInt(cnzAct2MaxXAccumulator);
        buf.putInt(cnzAct2MinYAccumulator);
        buf.putInt(cnzAct2MaxYAccumulator);
        // AIZ
        if (aizBytes != null) {
            buf.put((byte) 1);
            buf.putInt(aizBytes.length);
            buf.put(aizBytes);
        } else {
            buf.put((byte) 0);
        }
        // HCZ
        if (hczBytes != null) {
            buf.put((byte) 1);
            buf.putInt(hczBytes.length);
            buf.put(hczBytes);
        } else {
            buf.put((byte) 0);
        }
        // CNZ
        if (cnzBytes != null) {
            buf.put((byte) 1);
            buf.putInt(cnzBytes.length);
            buf.put(cnzBytes);
        } else {
            buf.put((byte) 0);
        }
        // MGZ
        if (mgzBytes != null) {
            buf.put((byte) 1);
            buf.putInt(mgzBytes.length);
            buf.put(mgzBytes);
        } else {
            buf.put((byte) 0);
        }
        // MHZ
        if (mhzBytes != null) {
            buf.put((byte) 1);
            buf.putInt(mhzBytes.length);
            buf.put(mhzBytes);
        } else {
            buf.put((byte) 0);
        }
        // ICZ
        if (iczBytes != null) {
            buf.put((byte) 1);
            buf.putInt(iczBytes.length);
            buf.put(iczBytes);
        } else {
            buf.put((byte) 0);
        }
        // LBZ
        if (lbzBytes != null) {
            buf.put((byte) 1);
            buf.putInt(lbzBytes.length);
            buf.put(lbzBytes);
        } else {
            buf.put((byte) 0);
        }
        fixedAirCountdownManager.writeRewindState(buf);
        buf.putInt(fixedAirCountdownZone);
        buf.putInt(fixedAirCountdownAct);
        return buf.array();
    }

    @Override
    protected void restoreExtra(byte[] extra) {
        if (extra == null || extra.length < 5) {
            return;
        }
        if (!hasValidExtraFraming(extra)) {
            LOG.warning("Skipping malformed S3K level-event rewind extra: invalid sidecar framing");
            return;
        }
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(extra);
        // Manager-level
        int modeOrdinal = buf.get() & 0xFF;
        Sonic3kLoadBootstrap.Mode[] modes = Sonic3kLoadBootstrap.Mode.values();
        Sonic3kLoadBootstrap.Mode mode = (modeOrdinal < modes.length) ? modes[modeOrdinal] : Sonic3kLoadBootstrap.Mode.NORMAL;
        bootstrap = new Sonic3kLoadBootstrap(mode, bootstrap.introStartPosition());
        introFallActiveOnPlayer           = buf.get() != 0;
        introFallActiveOnSidekick         = buf.get() != 0;
        hczPendingPostTransitionCutscene  = buf.get() != 0;
        mgzPendingPostTransitionRelease   = buf.get() != 0;
        cnzPendingPostTransitionRelease = buf.remaining() >= Integer.BYTES && buf.getInt() != 0;
        cnzPendingPostTransitionAct2SizeFrames = buf.remaining() >= Integer.BYTES ? buf.getInt() : 0;
        cnzPostTransitionAct2SizeActive = buf.remaining() >= 1 && buf.get() != 0;
        cnzAct2MinXAccumulator = buf.remaining() >= Integer.BYTES ? buf.getInt() : 0;
        cnzAct2MaxXAccumulator = buf.remaining() >= Integer.BYTES ? buf.getInt() : 0;
        cnzAct2MinYAccumulator = buf.remaining() >= Integer.BYTES ? buf.getInt() : 0;
        cnzAct2MaxYAccumulator = buf.remaining() >= Integer.BYTES ? buf.getInt() : 0;
        // AIZ
        if (buf.remaining() >= 1) {
            boolean aizPresent = buf.get() != 0;
            if (aizPresent) {
                if (buf.remaining() < Integer.BYTES) {
                    return;
                }
                int aizLength = buf.getInt();
                if (aizLength < 0 || buf.remaining() < aizLength) {
                    return;
                }
                byte[] bytes = new byte[aizLength];
                buf.get(bytes);
                if (aizEvents != null) {
                    restoreAizSidecar(bytes);
                }
            }
        }
        // HCZ
        if (buf.remaining() >= 1) {
            boolean hczPresent = buf.get() != 0;
            if (hczPresent) {
                if (buf.remaining() < Integer.BYTES) {
                    return;
                }
                int hczLength = buf.getInt();
                if (hczLength < 0 || buf.remaining() < hczLength) {
                    return;
                }
                byte[] bytes = new byte[hczLength];
                buf.get(bytes);
                if (hczEvents != null) {
                    restoreHczSidecar(bytes);
                }
            }
        }
        // CNZ
        if (buf.remaining() >= 1) {
            boolean cnzPresent = buf.get() != 0;
            if (cnzPresent) {
                if (buf.remaining() < Integer.BYTES) {
                    return;
                }
                int cnzLength = buf.getInt();
                if (cnzLength < 0 || buf.remaining() < cnzLength) {
                    return;
                }
                byte[] bytes = new byte[cnzLength];
                buf.get(bytes);
                if (cnzEvents != null) {
                    restoreCnzSidecar(bytes);
                }
            }
        }
        // MGZ
        if (buf.remaining() >= 1) {
            boolean mgzPresent = buf.get() != 0;
            if (mgzPresent) {
                if (buf.remaining() < Integer.BYTES) {
                    return;
                }
                int mgzLength = buf.getInt();
                if (mgzLength < 0 || buf.remaining() < mgzLength) {
                    return;
                }
                byte[] bytes = new byte[mgzLength];
                buf.get(bytes);
                if (mgzEvents != null) {
                    restoreMgzSidecar(bytes);
                }
            }
        }
        // MHZ
        if (buf.remaining() >= 1) {
            boolean mhzPresent = buf.get() != 0;
            if (mhzPresent) {
                if (buf.remaining() < Integer.BYTES) {
                    return;
                }
                int mhzLength = buf.getInt();
                if (mhzLength < 0 || buf.remaining() < mhzLength) {
                    return;
                }
                byte[] bytes = new byte[mhzLength];
                buf.get(bytes);
                if (mhzEvents != null) {
                    restoreMhzSidecar(bytes);
                }
            }
        }
        // ICZ
        if (buf.remaining() >= 1) {
            boolean iczPresent = buf.get() != 0;
            if (iczPresent) {
                if (buf.remaining() < Integer.BYTES) {
                    return;
                }
                int iczLength = buf.getInt();
                if (iczLength < 0 || buf.remaining() < iczLength) {
                    return;
                }
                byte[] bytes = new byte[iczLength];
                buf.get(bytes);
                if (iczEvents != null) {
                    restoreIczSidecar(bytes);
                }
            }
        }
        // LBZ
        if (buf.remaining() >= 1) {
            boolean lbzPresent = buf.get() != 0;
            if (lbzPresent) {
                if (buf.remaining() < Integer.BYTES) {
                    return;
                }
                int lbzLength = buf.getInt();
                if (lbzLength < 0 || buf.remaining() < lbzLength) {
                    return;
                }
                byte[] bytes = new byte[lbzLength];
                buf.get(bytes);
                if (lbzEvents != null) {
                    restoreLbzSidecar(bytes);
                }
            }
        }
        if (buf.remaining() >= S3kFixedAirCountdownManager.REWIND_STATE_BYTES) {
            fixedAirCountdownManager.readRewindState(buf);
        }
        if (buf.remaining() >= 2 * Integer.BYTES) {
            fixedAirCountdownZone = buf.getInt();
            fixedAirCountdownAct = buf.getInt();
        } else {
            // Older snapshots predate the owner metadata. Treat the restored
            // countdown as unowned so a later init cannot mistake it for a
            // seamless continuation in the wrong zone.
            fixedAirCountdownZone = -1;
            fixedAirCountdownAct = -1;
        }
    }

    private boolean hasValidExtraFraming(byte[] extra) {
        if (extra.length < 5) {
            return false;
        }
        if (extra.length < EXTRA_MANAGER_BYTES) {
            return true;
        }
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(extra);
        buf.position(EXTRA_MANAGER_BYTES);
        if (!skipLengthPrefixedSidecar(buf,
                () -> expectedSidecarBytes(aizEvents, Sonic3kLevelEventManager::newAizFramingProbe))) {
            return false;
        }
        if (!skipLengthPrefixedSidecar(buf,
                () -> expectedSidecarBytes(hczEvents, Sonic3kLevelEventManager::newHczFramingProbe))) {
            return false;
        }
        if (!skipLengthPrefixedSidecar(buf,
                () -> expectedSidecarBytes(cnzEvents, Sonic3kLevelEventManager::newCnzFramingProbe))) {
            return false;
        }
        if (!skipLengthPrefixedSidecar(buf,
                () -> expectedSidecarBytes(mgzEvents, Sonic3kLevelEventManager::newMgzFramingProbe))) {
            return false;
        }
        if (!skipVariableLengthSchemaSidecar(buf, Sonic3kMHZEvents.class)) {
            return false;
        }
        if (!skipLengthPrefixedSidecar(buf,
                () -> expectedSidecarBytes(iczEvents, Sonic3kLevelEventManager::newIczFramingProbe))) {
            return false;
        }
        if (!skipLengthPrefixedSidecar(buf,
                () -> expectedSidecarBytes(lbzEvents, Sonic3kLevelEventManager::newLbzFramingProbe))) {
            return false;
        }
        return buf.remaining() == 0
                || buf.remaining() >= S3kFixedAirCountdownManager.REWIND_STATE_BYTES;
    }

    private static boolean skipLengthPrefixedSidecar(
            java.nio.ByteBuffer buf,
            java.util.function.IntSupplier expectedLength) {
        if (buf.remaining() < 1) {
            return true;
        }
        boolean present = buf.get() != 0;
        if (!present) {
            return true;
        }
        if (buf.remaining() < Integer.BYTES) {
            return false;
        }
        int length = buf.getInt();
        if (length != expectedLength.getAsInt() || buf.remaining() < length) {
            return false;
        }
        buf.position(buf.position() + length);
        return true;
    }

    private static int expectedSidecarBytes(
            Object handler,
            java.util.function.Supplier<Object> fallback) {
        return ZoneEventSchemaSidecar.capture(handler != null ? handler : fallback.get()).length;
    }

    private static Object newAizFramingProbe() {
        return new Sonic3kAIZEvents(Sonic3kLoadBootstrap.NORMAL);
    }

    private static Object newHczFramingProbe() {
        Sonic3kHCZEvents probe = new Sonic3kHCZEvents(() -> 0);
        probe.init(0);
        return probe;
    }

    private static Object newCnzFramingProbe() {
        Sonic3kCNZEvents probe = new Sonic3kCNZEvents();
        probe.init(0);
        return probe;
    }

    private static Object newMgzFramingProbe() {
        Sonic3kMGZEvents probe = new Sonic3kMGZEvents();
        probe.init(1);
        return probe;
    }

    private static Object newIczFramingProbe() {
        return new Sonic3kICZEvents();
    }

    private static Object newLbzFramingProbe() {
        return new Sonic3kLBZEvents();
    }

    private static boolean skipVariableLengthSchemaSidecar(
            java.nio.ByteBuffer buf,
            Class<?> handlerType) {
        if (buf.remaining() < 1) {
            return true;
        }
        boolean present = buf.get() != 0;
        if (!present) {
            return true;
        }
        if (buf.remaining() < Integer.BYTES) {
            return false;
        }
        int length = buf.getInt();
        if (length < 0 || buf.remaining() < length) {
            return false;
        }
        byte[] bytes = new byte[length];
        buf.get(bytes);
        return ZoneEventSchemaSidecar.hasValidVariableLengthPayload(handlerType, bytes);
    }

    private void restoreAizSidecar(byte[] bytes) {
        byte[] before = ZoneEventSchemaSidecar.capture(aizEvents);
        try {
            ZoneEventSchemaSidecar.restore(aizEvents, bytes);
            aizEvents.discardHardwareWorkFacadesAfterRewind();
        } catch (RuntimeException e) {
            try {
                ZoneEventSchemaSidecar.restore(aizEvents, before);
                aizEvents.discardHardwareWorkFacadesAfterRewind();
            } catch (RuntimeException rollbackFailure) {
                e.addSuppressed(rollbackFailure);
            }
            LOG.warning("Skipping malformed AIZ zone-event rewind sidecar: " + e.getMessage());
        }
    }

    private void restoreHczSidecar(byte[] bytes) {
        byte[] before = ZoneEventSchemaSidecar.capture(hczEvents);
        try {
            ZoneEventSchemaSidecar.restore(hczEvents, bytes);
            hczEvents.discardHardwareWorkFacadesAfterRewind();
        } catch (RuntimeException e) {
            try {
                ZoneEventSchemaSidecar.restore(hczEvents, before);
                hczEvents.discardHardwareWorkFacadesAfterRewind();
            } catch (RuntimeException rollbackFailure) {
                e.addSuppressed(rollbackFailure);
            }
            LOG.warning("Skipping malformed HCZ zone-event rewind sidecar: " + e.getMessage());
        }
    }

    private void restoreCnzSidecar(byte[] bytes) {
        byte[] before = ZoneEventSchemaSidecar.capture(cnzEvents);
        try {
            ZoneEventSchemaSidecar.restore(cnzEvents, bytes);
        } catch (RuntimeException e) {
            try {
                ZoneEventSchemaSidecar.restore(cnzEvents, before);
            } catch (RuntimeException rollbackFailure) {
                e.addSuppressed(rollbackFailure);
            }
            LOG.warning("Skipping malformed CNZ zone-event rewind sidecar: " + e.getMessage());
        }
    }

    private void restoreMgzSidecar(byte[] bytes) {
        byte[] before = ZoneEventSchemaSidecar.capture(mgzEvents);
        try {
            ZoneEventSchemaSidecar.restore(mgzEvents, bytes);
            mgzEvents.discardHardwareWorkFacadesAfterRewind();
        } catch (RuntimeException e) {
            try {
                ZoneEventSchemaSidecar.restore(mgzEvents, before);
                mgzEvents.discardHardwareWorkFacadesAfterRewind();
            } catch (RuntimeException rollbackFailure) {
                e.addSuppressed(rollbackFailure);
            }
            LOG.warning("Skipping malformed MGZ zone-event rewind sidecar: " + e.getMessage());
        }
    }

    private void restoreMhzSidecar(byte[] bytes) {
        byte[] before = ZoneEventSchemaSidecar.capture(mhzEvents);
        try {
            ZoneEventSchemaSidecar.restoreVariableLength(mhzEvents, bytes);
            mhzEvents.validateRewindSidecarState();
        } catch (RuntimeException e) {
            try {
                ZoneEventSchemaSidecar.restoreVariableLength(mhzEvents, before);
            } catch (RuntimeException rollbackFailure) {
                e.addSuppressed(rollbackFailure);
            }
            LOG.warning("Skipping malformed MHZ zone-event rewind sidecar: " + e.getMessage());
        }
    }

    private void restoreIczSidecar(byte[] bytes) {
        byte[] before = ZoneEventSchemaSidecar.capture(iczEvents);
        try {
            ZoneEventSchemaSidecar.restore(iczEvents, bytes);
            iczEvents.discardHardwareWorkFacadesAfterRewind();
        } catch (RuntimeException e) {
            try {
                ZoneEventSchemaSidecar.restore(iczEvents, before);
                iczEvents.discardHardwareWorkFacadesAfterRewind();
            } catch (RuntimeException rollbackFailure) {
                e.addSuppressed(rollbackFailure);
            }
            LOG.warning("Skipping malformed ICZ zone-event rewind sidecar: " + e.getMessage());
        }
    }

    private void restoreLbzSidecar(byte[] bytes) {
        byte[] before = ZoneEventSchemaSidecar.capture(lbzEvents);
        try {
            ZoneEventSchemaSidecar.restore(lbzEvents, bytes);
            lbzEvents.discardHardwareWorkFacadesAfterRewind();
        } catch (RuntimeException e) {
            try {
                ZoneEventSchemaSidecar.restore(lbzEvents, before);
                lbzEvents.discardHardwareWorkFacadesAfterRewind();
            } catch (RuntimeException rollbackFailure) {
                e.addSuppressed(rollbackFailure);
            }
            LOG.warning("Skipping malformed LBZ zone-event rewind sidecar: " + e.getMessage());
        }
    }
}
