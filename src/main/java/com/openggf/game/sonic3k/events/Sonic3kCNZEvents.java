package com.openggf.game.sonic3k.events;

import com.openggf.camera.Camera;
import com.openggf.game.GameModule;
import com.openggf.game.PlayerCharacter;
import com.openggf.game.mutation.LayoutMutationContext;
import com.openggf.game.mutation.LevelMutationSurface;
import com.openggf.game.mutation.MutationEffects;
import com.openggf.game.save.SaveReason;
import com.openggf.game.save.SessionSaveRequests;
import com.openggf.game.sonic3k.S3kPaletteOwners;
import com.openggf.game.sonic3k.S3kPaletteWriteSupport;
import com.openggf.game.sonic3k.audio.Sonic3kMusic;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.objects.CnzMinibossScrollControlInstance;
import com.openggf.game.sonic3k.objects.S3kSignpostInstance;
import com.openggf.level.Block;
import com.openggf.level.ChunkDesc;
import com.openggf.level.Level;
import com.openggf.level.LevelManager;
import com.openggf.level.SeamlessLevelTransitionRequest;
import com.openggf.game.RuntimeArtAdmissionPolicy;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.sprites.Sprite;
import com.openggf.sprites.managers.SpriteManager;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.SidekickCarryTrigger;
import com.openggf.sprites.playable.SidekickCpuController;
import com.openggf.sprites.playable.Tails;

import java.io.IOException;
import java.util.Arrays;
import java.util.logging.Logger;

/**
 * CNZ (Carnival Night Zone) dynamic level events.
 *
 * <p>This class models the ROM-shaped CNZ act-state split needed for the
 * current bring-up:
 * <ul>
 *   <li>Act 1 miniboss entry and post-boss handoff</li>
 *   <li>Act 1 seamless reload request</li>
 *   <li>Act 2 Knuckles teleporter route markers</li>
 * </ul>
 */
public class Sonic3kCNZEvents extends Sonic3kZoneEvents {
    private static final Logger LOG = Logger.getLogger(Sonic3kCNZEvents.class.getName());

    /** CNZ1_BackgroundEvent stage 0. */
    public static final int BG_NORMAL = 0x00;
    /** CNZ1_BackgroundEvent stage 4. */
    public static final int BG_BOSS_START = 0x04;
    /** CNZ1_BackgroundEvent stage 8. */
    public static final int BG_BOSS = 0x08;
    /** CNZ1_BackgroundEvent stage 12. */
    public static final int BG_AFTER_BOSS = 0x0C;
    /** CNZ1_BackgroundEvent stage 16. */
    public static final int BG_FG_REFRESH = 0x10;
    /** CNZ1_BackgroundEvent stage 20. */
    public static final int BG_FG_REFRESH_2 = 0x14;
    /** CNZ1_BackgroundEvent stage 24. */
    public static final int BG_DO_TRANSITION = 0x18;

    /**
     * BG-layout Y that {@code CNZ1BGE_Boss} fills Plane B from when looping the boss-room
     * background ({@code move.w #$200,d1} at docs/skdisasm/sonic3k.asm:107504). The looping
     * carnival tunnel band starts here; the room floor sits below it.
     */
    public static final int CNZ_BOSS_BG_LOOP_BAND_BASE_Y = 0x200;

    /** CNZ2_ScreenEvent stage 0. */
    public static final int FG_ACT2_ENTRY = 0x00;
    /** CNZ2_ScreenEvent stage 4. */
    public static final int FG_ACT2_KNUCKLES_ROUTE = 0x04;
    /** CNZ2_ScreenEvent stage 8. */
    public static final int FG_ACT2_NORMAL = 0x08;

    /**
     * Camera X threshold that arms the miniboss arena gate.
     *
     * <p>ROM: {@code Obj_CNZMiniboss} (sonic3k.asm:144824) reads
     * {@code move.w #$31E0,d0} then {@code cmp.w (Camera_X_pos).w,d0} and
     * branches to {@code loc_6D9A8} when the camera reaches the threshold.
     * The ROM value is exposed through {@link Sonic3kConstants#CNZ_MINIBOSS_ARENA_MIN_X}
     * and reused below for the {@code Camera_min_X_pos} clamp so the arena lock
     * lines up with the same coordinate the ROM uses.
     *
     * <p>The arming threshold is held a little earlier than the arena clamp so
     * the scroll handler can observe {@link BossBackgroundMode#ACT1_MINIBOSS_PATH}
     * during the approach window that drives the early refresh phase the
     * {@code SwScrlCnz} boss scroll path covers.
     */
    private static final int MINIBOSS_EARLY_TUNNEL_X_THRESHOLD = 0x3000;
    /*
     * ROM writes #2*60 into the original Obj_Wait object (sonic3k.asm:144838-144840).
     * This event handler arms and ticks the engine-side mirror in the same update,
     * so the stored value is one larger to leave the first visible release frame
     * aligned with Obj_CNZMinibossGo installing Obj_CNZMinibossStart.
     */
    private static final int MINIBOSS_START_RELEASE_DELAY = (2 * 60) + 1;
    private static final int MINIBOSS_LOWER_ROUTE_Y_THRESHOLD = 0x054C;
    private static final int MINIBOSS_LOWER_ROUTE_Y_REMAP = 0x0700;
    private static final int MINIBOSS_BOSS_BG_SCROLL_THRESHOLD = 0x01E0;
    private static final int POST_BOSS_END_SIGN_X = 0x32C0;
    private static final int KNUCKLES_ROUTE_MIN_X = 0x4750;
    private static final int KNUCKLES_ROUTE_MAX_X = 0x48E0;
    private static final int ACT2_BOSS_ARENA_Y_START_EXIT_X = 0x0940;
    private static final int ACT2_Y_START_SKIP_X = 0x4600;
    private static final int ACT2_OLD_BOSS_ARENA_MIN_Y = 0x0580;
    private static final int ARENA_CHUNK_CELL_SIZE = 0x20;
    private static final int CHUNK_PIXEL_SIZE = 0x10;
    private static final int POST_BOSS_COPY_SOURCE_LAYER = 1;
    private static final int POST_BOSS_COPY_DEST_LAYER = 0;
    private static final int POST_BOSS_COPY_SOURCE_X = 0x0200 / 0x80;
    private static final int POST_BOSS_COPY_SOURCE_Y = 0x0180 / 0x80;
    private static final int POST_BOSS_COPY_DEST_X = 0x3180 / 0x80;
    private static final int POST_BOSS_COPY_DEST_Y = 0x0280 / 0x80;
    private static final int POST_BOSS_COPY_COLUMNS = 5;
    private static final int POST_BOSS_COPY_ROWS = 6;
    private static final int POST_BOSS_REFRESH_INITIAL_ROWCOUNT = 0x0F;
    private static final int POST_BOSS_REFRESH_FRAME_ENTRY_REMAINDER = POST_BOSS_REFRESH_INITIAL_ROWCOUNT;
    private static final int POST_BOSS_VERTICAL_REMAP = 0x01C0;

    /**
     * Saved {@code Camera_max_X_pos} captured when the arena lock fires.
     *
     * <p>ROM: {@code loc_6D9A8} (sonic3k.asm:144831) writes
     * {@code Camera_max_X_pos} into {@code Camera_stored_max_X_pos}; we mirror
     * that here so the falling-edge release can restore the natural camera
     * extent when {@link CnzMinibossInstance#onEndGo} clears
     * {@link #bossFlag}.
     */
    private short cameraStoredMaxXPos;
    private short cameraStoredMinXPos;
    private short cameraStoredMinYPos;
    private short cameraStoredMaxYPos;
    private boolean cameraClampsActive;
    private boolean bossFlagPrev;
    private boolean sidekickBoundsPublishAfterCameraEasing;

    /**
     * CNZ-local foreground routine mirror.
     *
     * <p>The ROM keeps separate FG/BG routines. CNZ stores the FG routine
     * locally so the event manager and runtime state can expose it without
     * relying on the base class counters.
     */
    private int fgRoutine;

    /**
     * CNZ-local background routine mirror.
     *
     * <p>This tracks the Act 1 boss chain and the later seamless reload gate.
     */
    private int bgRoutine;

    /** ROM: Events_fg_5. */
    private boolean eventsFg5;

    /**
     * Published deform phase source later consumed by AnimateTiles_CNZ.
     */
    private int deformPhaseBgX;

    /**
     * Published stabilized BG camera X copy.
     */
    private int publishedBgCameraX;

    /**
     * ROM-shaped boss scroll offset and velocity values.
     */
    private int bossScrollOffsetY;
    private int bossScrollVelocityY;
    private boolean minibossArenaLocked;
    private int minibossStartReleaseTimer;
    private boolean minibossStartReleased;
    private boolean minibossScrollControlSpawned;
    private boolean minibossLowerRouteRemapped;
    private boolean minibossDefeatSignalForScrollControl;

    /** Suppresses wall-grab interactions during the miniboss path. */
    private boolean wallGrabSuppressed;

    /** Latest requested water target. */
    private int waterTargetY;

    /** Bridge flag for the Act 1 water button helper. */
    private boolean waterButtonArmed;

    /**
     * Timed screen-shake countdown.
     *
     * <p>ROM: {@code Screen_shake_flag} written {@code #$14} by the CNZ cutscene
     * button ({@code loc_65C78}) and the vacuum-tube button ({@code loc_65CAC}).
     * Positive values drive the timed {@code ShakeScreen_Setup} countdown that
     * indexes {@link #SCREEN_SHAKE_ARRAY}. Mirrors the AIZ implementation so the
     * shake routes through the shared {@code ParallaxManager} -> {@code Camera}
     * shake plumbing and the CNZ scroll handler.
     */
    private int screenShakeTimer;
    private int screenShakeOffsetY;
    private int screenShakeAppliedOffsetY;

    /** Boss ownership mirror used by later slices. */
    private boolean bossFlag;

    /** True once the Knuckles-only Act 2 teleporter route is active. */
    private boolean knucklesTeleporterRouteActive;

    /** True once the teleporter beam child has spawned. */
    private boolean teleporterBeamSpawned;

    /** True once the seamless Act 1 -> Act 2 reload has been requested. */
    private boolean act2TransitionRequested;
    private int pendingZoneActWord;
    private int transitionWorldOffsetX;
    private int transitionWorldOffsetY;

    /** Teleporter route clamp values mirrored for tests. */
    private int cameraMinXClamp;
    private int cameraMaxXClamp;

    /**
     * Last pending arena chunk coordinates. Slice 0 intentionally exposes a
     * single pending request instead of a queue.
     */
    private int arenaChunkWorldX;
    private int arenaChunkWorldY;
    private boolean arenaChunkDestructionQueued;
    private int lastArenaChunkClearX;
    private int lastArenaChunkClearY;
    private static final int ARENA_CLEAR_HISTORY_SIZE = 8;
    private final int[] arenaClearHistoryX = new int[ARENA_CLEAR_HISTORY_SIZE];
    private final int[] arenaClearHistoryY = new int[ARENA_CLEAR_HISTORY_SIZE];
    private final int[] arenaClearHistoryFrame = new int[ARENA_CLEAR_HISTORY_SIZE];
    private int arenaClearHistoryCursor;
    private int arenaClearHistoryCount;
    private int postBossFgRefreshRowsRemaining;
    private int postBossFgRefresh2RowsRemaining;
    private boolean postBossForegroundVisualCopied;
    /**
     * Accumulated destroyed arena height in pixels.
     *
     * <p>ROM: {@code Obj_CNZMinibossTop} only ever reports impacts snapped to the
     * arena block grid before {@code CNZMiniboss_BlockExplosion} removes the
     * touched chunk. Task 7 keeps a single scalar here instead of replaying the
     * full live-layout mutation, because the current tests only need to prove
     * that each top hit contributes one 0x20-pixel row toward the lowering
     * sequence consumed by the base object.
     */
    private int destroyedArenaRows;

    /** Current boss background scroll mode. */
    private BossBackgroundMode bossBackgroundMode = BossBackgroundMode.NORMAL;

    /**
     * Background scroll modes used by CNZ.
     */
    public enum BossBackgroundMode {
        NORMAL,
        ACT1_MINIBOSS_PATH,
        ACT1_POST_BOSS,
        ACT2_KNUCKLES_TELEPORTER
    }

    @Override
    public void init(int act) {
        super.init(act);
        fgRoutine = 0;
        bgRoutine = 0;
        eventsFg5 = false;
        deformPhaseBgX = 0;
        publishedBgCameraX = 0;
        bossScrollOffsetY = 0;
        bossScrollVelocityY = 0;
        minibossArenaLocked = false;
        minibossStartReleaseTimer = 0;
        minibossStartReleased = false;
        minibossScrollControlSpawned = false;
        minibossLowerRouteRemapped = false;
        minibossDefeatSignalForScrollControl = false;
        wallGrabSuppressed = false;
        waterTargetY = 0;
        waterButtonArmed = false;
        screenShakeTimer = 0;
        screenShakeOffsetY = 0;
        screenShakeAppliedOffsetY = 0;
        bossFlag = false;
        bossFlagPrev = false;
        cameraStoredMaxXPos = 0;
        cameraStoredMinXPos = 0;
        cameraStoredMinYPos = 0;
        cameraStoredMaxYPos = 0;
        sidekickBoundsPublishAfterCameraEasing = false;
        cameraClampsActive = false;
        knucklesTeleporterRouteActive = false;
        teleporterBeamSpawned = false;
        act2TransitionRequested = false;
        pendingZoneActWord = 0;
        transitionWorldOffsetX = 0;
        transitionWorldOffsetY = 0;
        cameraMinXClamp = 0;
        cameraMaxXClamp = 0;
        arenaChunkWorldX = 0;
        arenaChunkWorldY = 0;
        arenaChunkDestructionQueued = false;
        lastArenaChunkClearX = 0;
        lastArenaChunkClearY = 0;
        Arrays.fill(arenaClearHistoryX, 0);
        Arrays.fill(arenaClearHistoryY, 0);
        Arrays.fill(arenaClearHistoryFrame, -1);
        arenaClearHistoryCursor = 0;
        arenaClearHistoryCount = 0;
        postBossFgRefreshRowsRemaining = -1;
        postBossFgRefresh2RowsRemaining = -1;
        postBossForegroundVisualCopied = false;
        destroyedArenaRows = 0;
        bossBackgroundMode = BossBackgroundMode.NORMAL;
        // NOTE: the solo-Sonic carry-in Tails is NOT spawned here. init() runs in
        // the engine's initLevelEvents load step, which is immediately followed by
        // the spawnSidekicks step whose first action is
        // SpriteManager.removeTemporarySidekicks() — that would delete the carrier
        // before the first gameplay frame. The carrier is instead spawned from
        // Sonic3kLevelEventManager.applyZonePlayerState() (the ROM
        // SpawnLevelMainSprites loc_68D8 location, which runs AFTER sidekick
        // placement). See spawnSoloLeaderCarryInTailsIfNeeded().
    }

    /** Unique code for the throwaway CNZ1 carry-in Tails so it never collides
     *  with a configured "tails_p2" sidekick slot. */
    private static final String SOLO_CARRY_TAILS_CODE = "tails_cnz_carry";

    /**
     * ROM SpawnLevelMainSprites loc_68D8 (sonic3k.asm:8187-8197): at CNZ Act 1 the
     * intro carry fires for both Sonic+Tails and solo Sonic. In the solo case
     * (Player_mode==1) the ROM still writes {@code Obj_Tails} into the Player_2
     * slot at Sonic's position so Tails carries him in; after the drop ROM
     * loc_14068 routes that throwaway carrier to routine $10 (fly off + self-
     * delete) rather than the normal follow AI.
     *
     * <p>The engine has no Tails sprite at all in solo mode, so we spawn a
     * temporary one here — mirroring the MGZ2 boss-transition rescue Tails
     * pattern ({@code Sonic3kMGZEvents.ensureBossTransitionTails}) — flagged
     * {@link SidekickCpuController#setTransientCarrySidekick(boolean)} so the
     * controller flies it off-screen and removes it once Sonic lands.
     *
     * <p><b>Call site:</b> invoked from
     * {@code Sonic3kLevelEventManager.applyZonePlayerState()} (the engine's
     * initZonePlayerState load step), NOT from {@link #init(int)}. The ROM spawns
     * the throwaway Tails inside {@code SpawnLevelMainSprites} (loc_68D8), which
     * runs after {@code SpawnLevelMainSprites_SpawnPlayers} has placed the
     * sidekicks. The engine mirrors that: the spawnSidekicks load step (which
     * begins with {@code SpriteManager.removeTemporarySidekicks()}) runs between
     * initLevelEvents and initZonePlayerState, so spawning the temporary carrier
     * during init() would let the very next step delete it before gameplay.
     */
    public void spawnSoloLeaderCarryInTailsIfNeeded(int act) {
        if (act != 0 || playerCharacter() != PlayerCharacter.SONIC_ALONE) {
            return;
        }
        GameModule gameModule = module();
        if (gameModule == null || gameModule.getSidekickCarryTrigger() == null) {
            return;
        }
        SpriteManager sprites = spriteManager();
        if (sprites == null || camera().getFocusedSprite() == null) {
            return;
        }
        // Don't double-spawn if a sidekick already exists (configured team) or a
        // prior carry-in Tails is still registered (e.g. seamless re-init).
        if (!sprites.getRegisteredSidekicks().isEmpty()) {
            return;
        }
        AbstractPlayableSprite carrier = buildSoloCarryInTails();
        if (carrier == null) {
            return;
        }
        // Register with a re-creation factory so a rewind keyframe captured while
        // the carrier is alive can rebuild it after it has flown off and been
        // removed (ROM routine $10 self-delete). Without it, rewinding back into
        // the carry would leave Sonic object-controlled with no carrier.
        sprites.addTemporarySidekick(carrier, "tails", this::recreateSoloCarryInTails);
        refreshSoloCarryInTailsArt();
    }

    /** Builds and fully wires (but does NOT register) the throwaway carry-in Tails. */
    private AbstractPlayableSprite buildSoloCarryInTails() {
        GameModule gameModule = module();
        if (gameModule == null) {
            return null;
        }
        SidekickCarryTrigger carryTrigger = gameModule.getSidekickCarryTrigger();
        AbstractPlayableSprite leader = camera() != null ? camera().getFocusedSprite() : null;
        if (carryTrigger == null || leader == null) {
            return null;
        }
        Tails carrier = new Tails(SOLO_CARRY_TAILS_CODE,
                (short) Math.max(0, leader.getX() - 0x20),
                (short) (leader.getY() + 4));
        carrier.setCpuControlled(true);
        carrier.setAir(true);
        SidekickCpuController controller = new SidekickCpuController(carrier, leader);
        controller.setCarryTrigger(carryTrigger);
        controller.setTransientCarrySidekick(true);
        carrier.setCpuController(controller);
        return carrier;
    }

    /**
     * Rewind re-creation factory (see {@code SpriteManager.addTemporarySidekick}
     * with a recreator): rebuilds, registers, and re-uploads art for the carry-in
     * Tails when a restored keyframe still references it. Returns the registered
     * carrier (its per-sprite rewind state is reapplied by the caller), or null.
     */
    private AbstractPlayableSprite recreateSoloCarryInTails() {
        SpriteManager sprites = spriteManager();
        AbstractPlayableSprite carrier = buildSoloCarryInTails();
        if (sprites == null || carrier == null) {
            return null;
        }
        sprites.addTemporarySidekick(carrier, "tails");
        refreshSoloCarryInTailsArt();
        return carrier;
    }

    private void refreshSoloCarryInTailsArt() {
        // The art-load step already ran (no Tails was in the team), so upload the
        // carrier's sprite art now — same as the MGZ2 rescue Tails path.
        LevelManager manager = levelManager();
        if (manager != null) {
            manager.refreshPlayableSpriteArt();
        }
    }

    /**
     * ROM: {@code ScreenShakeArray} — signed byte Y offsets indexed by the
     * {@code Screen_shake_flag} countdown. Amplitude tapers from ±5 down to ±1
     * as the timer runs out. Shared with the AIZ timed-shake pattern.
     */
    private static final int[] SCREEN_SHAKE_ARRAY = {
            1, -1, 1, -1, 2, -2, 2, -2, 3, -3, 3, -3, 4, -4, 4, -4, 5, -5, 5, -5
    };

    @Override
    public void update(int act, int frameCounter) {
        // LevelLoop runs ScreenEvents after Process_Sprites. CNZ2_ScreenEvent
        // consumes the offset produced by the preceding background event;
        // ShakeScreen_Setup then publishes the sample for the next frame.
        screenShakeAppliedOffsetY = screenShakeOffsetY;
        tickScreenShake();
        if (act == 0) {
            updateAct1Bg(frameCounter);
            updateMinibossStartRelease();
        } else {
            updateAct2Fg();
        }
        // Falling-edge: when the boss object clears Boss_flag (via
        // CnzMinibossInstance.onEndGo, ROM sonic3k.asm:144998), only release
        // wall-grab suppression. ROM Obj_CNZMinibossEndGo calls
        // AfterBoss_Cleanup, and AfterBoss_CNZ is an rts; it does not restore
        // the stored horizontal camera bounds here (sonic3k.asm:144996-145001,
        // 176489-176557). The arena X clamp remains in force until the later
        // CNZ1BGE_DoTransition offset/reload path consumes it.
        if (bossFlagPrev && !bossFlag) {
            wallGrabSuppressed = false;
        }
        bossFlagPrev = bossFlag;
    }

    private void updateAct1Bg(int frameCounter) {
        processQueuedArenaChunkDestruction(frameCounter);
        switch (bgRoutine) {
            case BG_BOSS_START -> handleBossScrollStartStage();
            case BG_BOSS -> handleAct1Entry();
            case BG_AFTER_BOSS -> handleAfterBossStage();
            case BG_FG_REFRESH -> advanceRefreshStageToSecondPass();
            case BG_FG_REFRESH_2 -> advanceRefreshStageToTransitionGate();
            case BG_DO_TRANSITION -> handleSeamlessReloadStage();
            default -> handleAct1Entry();
        }
    }

    private void handleBossScrollStartStage() {
        int bossBgY = (camera().getY() & 0xFFFF) - 0x0100 + bossScrollOffsetY;
        if (bossBgY >= MINIBOSS_BOSS_BG_SCROLL_THRESHOLD) {
            bgRoutine = BG_BOSS;
        }
        handleAct1Entry();
    }

    /**
     * Handles the normal Act 1 entry path and the miniboss threshold gate.
     *
     * <p>Parity note: in the ROM the arena setup runs from inside
     * {@code Obj_CNZMiniboss} (sonic3k.asm:144823), so it only fires if that
     * object is live in the active window. Tests and debug teleports that
     * drop the camera strictly past the arena's far wall
     * ({@link Sonic3kConstants#CNZ_MINIBOSS_ARENA_MAX_X}) would normally not
     * reach that object — mirror the ROM by short-circuiting straight to the
     * post-boss mode instead of tripping the one-shot arena clamp for a
     * player that was never gated through the entry window.
     */
    private void handleAct1Entry() {
        switch (bossBackgroundMode) {
            case NORMAL -> {
                int camX = camera().getX();
                if (camX > Sonic3kConstants.CNZ_MINIBOSS_ARENA_MAX_X) {
                    // Camera already past the arena's right wall — the
                    // miniboss object would not be live here. Skip the
                    // arena lock and hand off to post-boss mode.
                    bossBackgroundMode = BossBackgroundMode.ACT1_POST_BOSS;
                    bgRoutine = BG_AFTER_BOSS;
                } else if (camX >= MINIBOSS_EARLY_TUNNEL_X_THRESHOLD) {
                    enterMinibossTunnelApproach();
                }
            }
            case ACT1_MINIBOSS_PATH -> {
                if (eventsFg5) {
                    enterPostBossForegroundRefresh();
                    LOG.info("CNZ: post-boss handoff entered");
                }
            }
            case ACT1_POST_BOSS -> handleAfterBossStage();
            case ACT2_KNUCKLES_TELEPORTER -> updateAct2Fg();
        }
    }

    private void enterMinibossTunnelApproach() {
        remapLowerRouteIntoBossTunnel(camera());
        camera().setMinY((short) Sonic3kConstants.CNZ_MINIBOSS_ARENA_MIN_Y);
        wallGrabSuppressed = true;
        installMinibossPalette();
        invalidateMinibossArenaTilemaps();
        bossBackgroundMode = BossBackgroundMode.ACT1_MINIBOSS_PATH;
        bgRoutine = BG_BOSS_START;
        LOG.info("CNZ: camera reached miniboss tunnel approach threshold");
    }

    /**
     * Executes {@code Obj_CNZMiniboss}'s camera-threshold branch from the
     * placed object's Process_Sprites slot.
     *
     * <p>ROM writes {@code Camera_min_X_pos} after both playable slots have
     * already run ({@code sonic3k.asm:144823-144840}), so Player 2 consumes
     * the new left boundary on the following frame.
     */
    public void enterMinibossArenaFromObjectSlot() {
        int cameraX = camera().getX() & 0xFFFF;
        if (minibossArenaLocked
                || cameraX < Sonic3kConstants.CNZ_MINIBOSS_ARENA_MIN_X) {
            return;
        }
        enterMinibossArena();
    }

    /**
     * ROM: {@code loc_6D9A8} (sonic3k.asm:144830) — arena setup invoked
     * when {@code Obj_CNZMiniboss}'s outer gate succeeds.
     *
     * <p>Mirrors the ROM sequence: stash {@code Camera_max_X_pos}, clamp the
     * camera to the arena rectangle, fade the music, set
     * {@code Boss_flag}, suppress wall-grab, load PLC {@code 0x5D}, and
     * install {@code Pal_CNZMiniboss} into palette line 1.
     */
    private void enterMinibossArena() {
        if (minibossArenaLocked) {
            return;
        }
        Camera camera = camera();
        remapLowerRouteIntoBossTunnel(camera);
        cameraStoredMaxXPos = camera.getMaxX();
        cameraStoredMinXPos = camera.getMinX();
        cameraStoredMinYPos = camera.getMinY();
        cameraStoredMaxYPos = camera.getMaxY();
        cameraClampsActive = true;

        camera.setMinX((short) Sonic3kConstants.CNZ_MINIBOSS_ARENA_MIN_X);
        camera.setMaxX((short) Sonic3kConstants.CNZ_MINIBOSS_ARENA_MAX_X);
        camera.setMinY((short) Sonic3kConstants.CNZ_MINIBOSS_ARENA_MIN_Y);
        camera.setMaxY((short) Sonic3kConstants.CNZ_MINIBOSS_ARENA_MAX_Y);
        camera.setMaxYTarget((short) Sonic3kConstants.CNZ_MINIBOSS_ARENA_MAX_Y);

        bossBackgroundMode = BossBackgroundMode.ACT1_MINIBOSS_PATH;
        bgRoutine = BG_BOSS_START;
        wallGrabSuppressed = true;
        // ROM sonic3k.asm:144843 — `move.b #1,(Boss_flag).w`. Setting the
        // mirrored event-state bit lets CnzMinibossInstance and downstream
        // CNZ scripts observe the lock without a separate global flag.
        bossFlag = true;
        bossFlagPrev = true;
        minibossArenaLocked = true;
        minibossStartReleased = false;
        minibossStartReleaseTimer = MINIBOSS_START_RELEASE_DELAY;
        minibossScrollControlSpawned = false;

        // ROM sonic3k.asm:144841 — `moveq #cmd_FadeOut,d0; jsr Play_Music`.
        // Mirror the music fade through the engine's helper. The miniboss
        // theme starts from updateMinibossStartRelease after the ROM wait.
        if (audio() != null) {
            audio().fadeOutMusic();
        }
        // Miniboss audio handoff is now handled by updateMinibossStartRelease.
        // (Sonic3kMusic.MINIBOSS) once the boss music handoff lands; the
        // fade-out above already mirrors sonic3k.asm:144841. Workstream D
        // shipped the boss without this fade-in by design (out of scope for
        // D — see the workstream-D entries in CHANGELOG.md and the
        // post-D baseline doc at docs/architecture/validation/s3k-zones/cnz-post-workstream-d.md).

        // ROM sonic3k.asm:144844 — `moveq #$5D,d0; jsr Load_PLC`.
        applyPlc(Sonic3kConstants.PLC_CNZ_MINIBOSS);
        invalidateMinibossArenaTilemaps();

        // ROM sonic3k.asm:144846-144847 — `lea Pal_CNZMiniboss(pc),a1; jmp
        // (PalLoad_Line1).l`. PalLoad_Line1 writes one VDP palette line
        // (32 bytes) into line 1.
        installMinibossPalette();

        LOG.info("CNZ: camera reached miniboss threshold; arena lock + Boss_flag set");
    }

    private void updateMinibossStartRelease() {
        if (!minibossArenaLocked || minibossStartReleased) {
            return;
        }
        if (minibossStartReleaseTimer > 0) {
            minibossStartReleaseTimer--;
        }
        if (minibossStartReleaseTimer > 0) {
            return;
        }

        minibossStartReleased = true;
        if (audio() != null) {
            audio().playMusic(Sonic3kMusic.MINIBOSS.id);
        }
        spawnMinibossScrollControlOnce();
    }

    private void spawnMinibossScrollControlOnce() {
        if (minibossScrollControlSpawned) {
            return;
        }
        minibossScrollControlSpawned = true;
        ObjectSpawn spawn = new ObjectSpawn(
                Sonic3kConstants.CNZ_MINIBOSS_ARENA_MIN_X,
                Sonic3kConstants.CNZ_MINIBOSS_ARENA_MAX_Y,
                0, 0, 0, false, 0);
        spawnObject(() -> new CnzMinibossScrollControlInstance(spawn));
    }

    /**
     * ROM: {@code CNZ1BGE_Normal} subtracts {@code $700} from both players and
     * the foreground camera when the boss gate is reached from the lower route
     * ({@code Camera_Y_pos >= $54C}). This maps the lower-path coordinates into
     * the vertically scrolling boss tunnel before the arena Y clamps are set.
     */
    private void remapLowerRouteIntoBossTunnel(Camera camera) {
        if (minibossLowerRouteRemapped) {
            return;
        }
        if ((camera.getY() & 0xFFFF) < MINIBOSS_LOWER_ROUTE_Y_THRESHOLD) {
            return;
        }

        for (Sprite sprite : spriteManager().getAllSprites()) {
            if (sprite instanceof AbstractPlayableSprite playable) {
                playable.setCentreYPreserveSubpixel(
                        (short) (playable.getCentreY() - MINIBOSS_LOWER_ROUTE_Y_REMAP));
            }
        }
        camera.setY((short) (camera.getY() - MINIBOSS_LOWER_ROUTE_Y_REMAP));
        minibossLowerRouteRemapped = true;
    }

    private void installMinibossPalette() {
        try {
            byte[] line = rom().readBytes(Sonic3kConstants.PAL_CNZ_MINIBOSS_ADDR, 32);
            Level level = levelManager() != null ? levelManager().getCurrentLevel() : null;
            if (level == null) {
                return;
            }
            S3kPaletteWriteSupport.applyLine(
                    paletteRegistryOrNull(),
                    level,
                    graphics(),
                    S3kPaletteOwners.CNZ_MINIBOSS,
                    S3kPaletteOwners.PRIORITY_ZONE_EVENT,
                    1,
                    line);
        } catch (Exception e) {
            LOG.warning("CNZ: failed to install Pal_CNZMiniboss: " + e.getMessage());
        }
    }

    /**
     * Falling-edge release of the arena camera lock.
     *
     * <p>ROM: the post-boss path restores the natural camera extents after
     * {@code Obj_CNZMinibossEnd} completes. The engine snapshots the prior
     * clamp values in {@link #enterMinibossArena()} and restores them here
     * once the boss object clears {@link #bossFlag} via
     * {@code CnzMinibossInstance.onEndGo}.
     */
    private void releaseArenaCameraClamps() {
        if (!cameraClampsActive) {
            return;
        }
        Camera camera = camera();
        camera.setMinX(cameraStoredMinXPos);
        camera.setMaxX(cameraStoredMaxXPos);
        camera.setMinY(cameraStoredMinYPos);
        camera.setMaxY(cameraStoredMaxYPos);
        camera.setMaxYTarget(cameraStoredMaxYPos);
        cameraClampsActive = false;
    }

    /**
     * ROM: CNZ1BGE_AfterBoss.
     *
     * <p>The first Events_fg_5 enters the refresh chain and immediately falls
     * through into {@code CNZ1BGE_FGRefresh}; it does not request the act reload.
     */
    private void handleAfterBossStage() {
        if (!eventsFg5) {
            return;
        }
        enterPostBossForegroundRefresh();
    }

    /**
     * The CNZ post-boss background owner has completed both foreground refresh
     * passes and is polling the results object's second {@code Events_fg_5}.
     */
    public boolean isAwaitingSeamlessReloadSignal() {
        return bossBackgroundMode == BossBackgroundMode.ACT1_POST_BOSS
                && bgRoutine == BG_DO_TRANSITION;
    }

    private void enterPostBossForegroundRefresh() {
        eventsFg5 = false;
        bossBackgroundMode = BossBackgroundMode.ACT1_POST_BOSS;
        bgRoutine = BG_FG_REFRESH;
        // ROM loc_51D6E primes Draw_delayed_rowcount=$F and falls through to
        // CNZ1BGE_FGRefresh. The engine observes this handoff after object
        // updates, so keep the post-first-draw remainder before the completion
        // copy can take the bmi branch (sonic3k.asm:107510-107534).
        postBossFgRefreshRowsRemaining = POST_BOSS_REFRESH_FRAME_ENTRY_REMAINDER;
        advanceRefreshStageToSecondPass();
    }

    /**
     * ROM: CNZ1BGE_FGRefresh.
     *
     * <p>The real game copies arena data back into the foreground only after
     * {@code Draw_PlaneVertSingleBottomUp} has decremented
     * {@code Draw_delayed_rowcount} below zero. Until then it keeps the
     * background collision plane live (sonic3k.asm:103436-103452,
     * 107527-107539).
     */
    private void advanceRefreshStageToSecondPass() {
        if (!consumePostBossRefreshRow(BG_FG_REFRESH)) {
            return;
        }
        copyPostBossBackgroundLayoutToForeground();
        clearPostBossBackgroundCollisionFlag();
        remapPostBossTunnelToForeground();
        postBossFgRefresh2RowsRemaining = POST_BOSS_REFRESH_FRAME_ENTRY_REMAINDER;
        bgRoutine = BG_FG_REFRESH_2;
    }

    /**
     * ROM: CNZ1BGE_FGRefresh2.
     *
     * <p>The real game finishes the foreground handoff here after the second
     * delayed draw finishes (sonic3k.asm:107576-107601).
     */
    private void advanceRefreshStageToTransitionGate() {
        if (!consumePostBossRefreshRow(BG_FG_REFRESH_2)) {
            return;
        }
        // ROM CNZ1BGE_FGRefresh2 allocates Obj_EndSign and writes x_pos=$32C0
        // immediately before advancing to CNZ1BGE_DoTransition
        // (docs/skdisasm/sonic3k.asm:107590-107601).
        spawnObject(() -> new S3kSignpostInstance(POST_BOSS_END_SIGN_X, 0, true));
        bgRoutine = BG_DO_TRANSITION;
    }

    private boolean consumePostBossRefreshRow(int routine) {
        if (routine == BG_FG_REFRESH) {
            if (postBossFgRefreshRowsRemaining < 0) {
                postBossFgRefreshRowsRemaining = POST_BOSS_REFRESH_FRAME_ENTRY_REMAINDER;
            }
            postBossFgRefreshRowsRemaining--;
            return postBossFgRefreshRowsRemaining < 0;
        }
        if (postBossFgRefresh2RowsRemaining < 0) {
            postBossFgRefresh2RowsRemaining = POST_BOSS_REFRESH_INITIAL_ROWCOUNT;
        }
        postBossFgRefresh2RowsRemaining--;
        return postBossFgRefresh2RowsRemaining < 0;
    }

    private void remapPostBossTunnelToForeground() {
        // ROM loc_51DAE clears Events_bg+$08 and adds $1C0 to both players
        // and Camera_Y_pos/Camera_Y_pos_copy after the BG->FG copy
        // (sonic3k.asm:107562-107568).
        bossScrollOffsetY = 0;
        bossScrollVelocityY = 0;
        for (Sprite sprite : spriteManager().getAllSprites()) {
            if (sprite instanceof AbstractPlayableSprite playable) {
                playable.setCentreYPreserveSubpixel(
                        (short) (playable.getCentreY() + POST_BOSS_VERTICAL_REMAP));
            }
        }
        camera().setY((short) (camera().getY() + POST_BOSS_VERTICAL_REMAP));
    }

    /**
     * ROM: CNZ1BGE_DoTransition.
     *
     * <p>The second Events_fg_5 loads the transition PLCs, publishes the
     * seamless reload metadata, and moves the handler into the Act 2 route
     * state.
     */
    private void handleSeamlessReloadStage() {
        if (!eventsFg5) {
            return;
        }
        eventsFg5 = false;
        applyPlc(0x18);
        applyPlc(0x19);
        act2TransitionRequested = true;
        pendingZoneActWord = 0x0301;
        transitionWorldOffsetX = -0x3000;
        transitionWorldOffsetY = 0x0200;
        fgRoutine = FG_ACT2_ENTRY;
        bgRoutine = BG_NORMAL;
        bossBackgroundMode = BossBackgroundMode.ACT2_KNUCKLES_TELEPORTER;
        wallGrabSuppressed = false;

        // ROM CNZ1BGE_DoTransition sets Current_zone_and_act=$301, reloads
        // level/solids/water, then offsets both players/camera by d0=$3000
        // and d1=-$200 without recentering the camera
        // (docs/skdisasm/sonic3k.asm:107603-107653).
        //
        // The current Obj_LevelResults / Obj_EndSignControl objects survive
        // that ROM reload: loc_2DD06 later clears _unkFAA8, and
        // Obj_EndSignControlAwaitStart restores P1/P2 control
        // (docs/skdisasm/sonic3k.asm:62708-62720,180407-180412).
        // The engine rebuilds the object manager for the reload. The persistent
        // results SST is carried into the target manager, while this CNZ event
        // bridge retains the removed EndSignControl ownership until the
        // results object publishes the native _unkFAA8-clear boundary.
        S3kTransitionWriteSupport.requestCnzPostTransitionRelease(
                module().getLevelEventProvider());
        Camera camera = camera();
        int postTransitionMinX = offsetCameraBoundWord(camera.getMinX(), transitionWorldOffsetX);
        int postTransitionMaxX = offsetCameraBoundWord(camera.getMaxX(), transitionWorldOffsetX);
        int postTransitionMinY = offsetCameraBoundWord(camera.getMinY(), transitionWorldOffsetY);
        int postTransitionMaxY = offsetCameraBoundWord(camera.getMaxY(), transitionWorldOffsetY);
        SeamlessLevelTransitionRequest request = SeamlessLevelTransitionRequest.builder(
                        SeamlessLevelTransitionRequest.TransitionType.RELOAD_TARGET_LEVEL)
                .targetZoneAct(Sonic3kZoneIds.ZONE_CNZ, 1)
                .runtimeArtAdmissionPolicy(RuntimeArtAdmissionPolicy.TITLE_OWNER)
                .deactivateLevelNow(false)
                .preserveMusic(true)
                .preserveLevelGamestate(true)
                .showInLevelTitleCard(false)
                .preserveOffsetCameraPosition(true)
                // CNZ's retained EndSignControl reaches Change_Act2Sizes
                // directly after the title owner retires; it has no extra
                // preloaded-camera Wait2 tail (sonic3k.asm:62244-62279,
                // 180407-180419).
                .inLevelTitleCardPreloadedActCameraReleaseDispatches(0)
                // CNZ1BGE_DoTransition offsets the live camera bounds after
                // Load_Level, and copies the offset max Y into the target max
                // (docs/skdisasm/sonic3k.asm:107638-107646).
                .postTransitionMinX(postTransitionMinX)
                .postTransitionMaxX(postTransitionMaxX)
                .postTransitionMinY(postTransitionMinY)
                .postTransitionMaxY(postTransitionMaxY)
                .postTransitionMaxYTarget(postTransitionMaxY)
                .playerOffset(transitionWorldOffsetX, transitionWorldOffsetY)
                .cameraOffset(transitionWorldOffsetX, transitionWorldOffsetY)
                .build();
        applyOrRequestCnzActTransition(request);
    }

    private static int offsetCameraBoundWord(short value, int offset) {
        return ((value & 0xFFFF) + offset) & 0xFFFF;
    }

    private void applyOrRequestCnzActTransition(SeamlessLevelTransitionRequest request) {
        SessionSaveRequests.requestCurrentSessionSave(SaveReason.PROGRESSION_SAVE);
        if (levelManager().getCurrentLevel() == null) {
            levelManager().requestSeamlessTransition(request);
            return;
        }
        try {
            // ROM CNZ1BGE_DoTransition performs Load_Level and the coordinate
            // offsets inside the BG event routine, not on the next frame
            // (docs/skdisasm/sonic3k.asm:107603-107653).
            levelManager().executeActTransition(request);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to apply CNZ act transition", e);
        }
    }

    /**
     * CNZ Act 2 foreground logic.
     *
     * <p>The current bring-up only needs the route selection and clamp
     * publication. Later slices will add the teleporter and capsule object
     * sequence.
     */
    private void updateAct2Fg() {
        updateAct2YStartLock();
        switch (fgRoutine) {
            case FG_ACT2_ENTRY -> {
                if (knucklesTeleporterRouteActive) {
                    fgRoutine = FG_ACT2_KNUCKLES_ROUTE;
                    publishKnucklesTeleporterClamp();
                } else {
                    fgRoutine = FG_ACT2_NORMAL;
                }
            }
            case FG_ACT2_KNUCKLES_ROUTE -> publishKnucklesTeleporterClamp();
            case FG_ACT2_NORMAL -> {
                // Normal Act 2 draw path.
            }
            default -> {
                if (knucklesTeleporterRouteActive) {
                    fgRoutine = FG_ACT2_KNUCKLES_ROUTE;
                    publishKnucklesTeleporterClamp();
                }
            }
        }
    }

    private void invalidateMinibossArenaTilemaps() {
        if (levelManager() != null) {
            levelManager().invalidateAllTilemaps();
        }
    }

    private void updateAct2YStartLock() {
        AbstractPlayableSprite player = camera().getFocusedSprite();
        if (player == null) {
            return;
        }
        int playerX = player.getCentreX() & 0xFFFF;
        if (playerX >= ACT2_Y_START_SKIP_X) {
            return;
        }
        int minY = playerX < ACT2_BOSS_ARENA_Y_START_EXIT_X
                ? ACT2_OLD_BOSS_ARENA_MIN_Y
                : 0;
        if ((camera().getMinY() & 0xFFFF) != minY) {
            camera().setMinY((short) minY);
        }
    }

    /** Returns the current boss background scroll mode. */
    public BossBackgroundMode getBossBackgroundMode() {
        return bossBackgroundMode;
    }

    /** CNZ-local foreground routine mirror. */
    public int getForegroundRoutine() {
        return fgRoutine;
    }

    /** CNZ-local background routine mirror. */
    public int getBackgroundRoutine() {
        return bgRoutine;
    }

    /** Restores the CNZ-local background routine. */
    public void setBackgroundRoutine(int routine) {
        this.bgRoutine = routine;
    }

    /** Test hook for the foreground routine. */
    public void forceForegroundRoutine(int routine) {
        this.fgRoutine = routine;
    }

    /** Test hook for the background routine. */
    public void forceBackgroundRoutine(int routine) {
        this.bgRoutine = routine;
        if (routine == BG_FG_REFRESH) {
            this.postBossFgRefreshRowsRemaining = POST_BOSS_REFRESH_FRAME_ENTRY_REMAINDER;
            this.postBossForegroundVisualCopied = false;
        } else if (routine == BG_FG_REFRESH_2) {
            this.postBossFgRefresh2RowsRemaining = POST_BOSS_REFRESH_FRAME_ENTRY_REMAINDER;
        }
    }

    /** Test hook for the boss background mode. */
    public void forceBossBackgroundMode(BossBackgroundMode mode) {
        this.bossBackgroundMode = mode;
    }

    /** Test hook for the CNZ miniboss Obj_Wait -> Start release gate. */
    public void forceMinibossStartGateForTest(boolean arenaLocked, boolean startReleased) {
        this.minibossArenaLocked = arenaLocked;
        this.minibossStartReleased = startReleased;
    }

    /** Publishes the deform inputs consumed by later CNZ systems. */
    public void setPublishedDeformInputs(int phaseSourceX, int bgCameraX) {
        this.deformPhaseBgX = phaseSourceX;
        this.publishedBgCameraX = bgCameraX;
    }

    public int getDeformPhaseBgX() {
        return deformPhaseBgX;
    }

    public int getPublishedBgCameraX() {
        return publishedBgCameraX;
    }

    /** Publishes the boss-scroll Y state used by the miniboss path. */
    public void setBossScrollState(int offsetY, int velocityY) {
        this.bossScrollOffsetY = offsetY;
        this.bossScrollVelocityY = velocityY;
    }

    public void signalMinibossDefeatedForScrollControl() {
        this.minibossDefeatSignalForScrollControl = true;
    }

    public boolean consumeMinibossDefeatSignalForScrollControl() {
        boolean signal = minibossDefeatSignalForScrollControl;
        minibossDefeatSignalForScrollControl = false;
        return signal;
    }

    public boolean isMinibossDefeatSignalForScrollControlPending() {
        return minibossDefeatSignalForScrollControl;
    }

    public void advanceMinibossBackgroundRoutineAfterScrollSnap() {
        // ROM Obj_CNZMinibossScrollWait2 advances Events_routine_bg when it
        // snaps Events_bg+$08, restores Camera_target_max_Y_pos, and enables
        // Background_collision_flag (sonic3k.asm:107814-107828).
        bgRoutine += 4;
    }

    public int getBossScrollOffsetY() {
        return bossScrollOffsetY;
    }

    public int getBossScrollVelocityY() {
        return bossScrollVelocityY;
    }

    public boolean isMinibossStartReleased() {
        return minibossStartReleased;
    }

    public boolean isMinibossArenaLocked() {
        return minibossArenaLocked;
    }

    public int getMinibossStartReleaseTimer() {
        return minibossStartReleaseTimer;
    }

    public boolean isWallGrabSuppressed() {
        return wallGrabSuppressed;
    }

    public void setWallGrabSuppressed(boolean wallGrabSuppressed) {
        this.wallGrabSuppressed = wallGrabSuppressed;
    }

    public int getWaterTargetY() {
        return waterTargetY;
    }

    public void setWaterTargetY(int waterTargetY) {
        this.waterTargetY = waterTargetY;
        /**
         * ROM anchors:
         * {@code Obj_CNZWaterLevelCorkFloor} writes {@code Target_water_level}
         * directly to {@code $0958}, and {@code Obj_CNZWaterLevelButton} writes
         * {@code $0A58} once the arming flag has been set. The engine mirrors
         * those writes into both the CNZ event state and the shared water system
         * so tests and later runtime consumers observe the same explicit source
         * of truth.
         */
        waterSystem().setWaterLevelTarget(levelManager().getRomZoneId(),
                levelManager().getCurrentAct(),
                waterTargetY);
    }

    /**
     * Rewind-restore path: sets {@code waterTargetY} without touching the
     * shared water system.  Use this only from snapshot restore code that
     * runs outside an active gameplay session.
     */
    public void setWaterTargetYRaw(int waterTargetY) {
        this.waterTargetY = waterTargetY;
    }

    /**
     * Seeds {@code Mean_water_level} directly so the flood appears at its new
     * height immediately instead of easing up from the off-screen start.
     *
     * <p>ROM: {@code loc_65C78} writes {@code Mean_water_level = Camera_Y + $100}
     * before {@code Target_water_level = $350}, so the water is already risen by
     * the time the player walks into it.
     */
    public void setWaterMeanLevel(int meanY) {
        waterSystem().setWaterLevelDirect(levelManager().getRomZoneId(),
                levelManager().getCurrentAct(),
                meanY);
    }

    public boolean isWaterButtonArmed() {
        return waterButtonArmed;
    }

    /**
     * ROM: {@code move.w #$14,(Screen_shake_flag).w} in {@code loc_65C78}.
     * Starts the timed screen shake driven through {@link #tickScreenShake()}.
     */
    public void triggerScreenShake(int frames) {
        screenShakeTimer = frames;
    }

    /**
     * Current vertical shake offset. Read by {@link CnzZoneRuntimeState} so the
     * CNZ scroll handler and the shared {@code ParallaxManager} -> {@code Camera}
     * shake propagation move the foreground, sprites, and background in sync.
     */
    public int getScreenShakeOffsetY() {
        return screenShakeAppliedOffsetY;
    }

    private void tickScreenShake() {
        if (screenShakeTimer <= 0) {
            screenShakeOffsetY = 0;
            return;
        }
        screenShakeTimer--;
        // ROM ShakeScreen_Setup (positive flag): index ScreenShakeArray by the
        // remaining countdown so the amplitude tapers off.
        screenShakeOffsetY = screenShakeTimer < SCREEN_SHAKE_ARRAY.length
                ? SCREEN_SHAKE_ARRAY[screenShakeTimer]
                : 0;
    }

    public void setWaterButtonArmed(boolean waterButtonArmed) {
        this.waterButtonArmed = waterButtonArmed;
    }

    public boolean isBossFlag() {
        return bossFlag;
    }

    public void setBossFlag(boolean bossFlag) {
        /**
         * Task 8 boundary note:
         * CNZ's end-boss implementation currently owns only the startup gate and
         * defeat handoff, so this flag is the explicit shared seam between the
         * bounded boss wrapper and the wider CNZ event script.
         */
        this.bossFlag = bossFlag;
    }

    /**
     * Enters the Knuckles-only Act 2 teleporter route.
     *
     * <p>The route is represented by a dedicated FG routine plus the camera
     * clamp values the ROM applies while the teleporter sequence is active.
     */
    public void beginKnucklesTeleporterRoute() {
        knucklesTeleporterRouteActive = true;
        bossBackgroundMode = BossBackgroundMode.ACT2_KNUCKLES_TELEPORTER;
        fgRoutine = FG_ACT2_KNUCKLES_ROUTE;
        /**
         * ROM: the late Knuckles route clamps the camera to the teleporter lane
         * while {@code Obj_CNZTeleporter} owns the cutscene-specific player and
         * palette state. Publishing the route transition here keeps the object
         * dependency explicit instead of burying it in object-local booleans.
         */
        publishKnucklesTeleporterClamp();
    }

    public void endKnucklesTeleporterRoute() {
        knucklesTeleporterRouteActive = false;
        teleporterBeamSpawned = false;
        fgRoutine = FG_ACT2_NORMAL;
        cameraMinXClamp = 0;
        cameraMaxXClamp = 0;
    }

    public boolean isKnucklesTeleporterRouteActive() {
        return knucklesTeleporterRouteActive;
    }

    /**
     * Returns whether Task 8's teleporter object should own the route palette
     * override.
     *
     * <p>The override window begins when the Knuckles-only route starts and
     * ends once the beam object has been spawned. Publishing that seam here
     * keeps the later palette handoff explicit in CNZ event state instead of
     * introducing another hidden object-local flag.
     */
    public boolean shouldApplyTeleporterPaletteOverride() {
        return knucklesTeleporterRouteActive && !teleporterBeamSpawned;
    }

    public void markTeleporterBeamSpawned() {
        /**
         * Once the shared beam exists, the teleporter-specific palette override
         * is no longer the active owner. Task 8 uses this explicit event seam
         * so tests can observe the parent -> beam handoff without depending on
         * hidden object state.
         */
        teleporterBeamSpawned = true;
    }

    public boolean isTeleporterBeamSpawned() {
        return teleporterBeamSpawned;
    }

    public boolean isAct2TransitionRequested() {
        return act2TransitionRequested;
    }

    public int getPendingZoneActWord() {
        return pendingZoneActWord;
    }

    public int getTransitionWorldOffsetX() {
        return transitionWorldOffsetX;
    }

    public int getTransitionWorldOffsetY() {
        return transitionWorldOffsetY;
    }

    public int getCameraMinXClamp() {
        return cameraMinXClamp;
    }

    public int getCameraMaxXClamp() {
        return cameraMaxXClamp;
    }

    public void setPendingArenaChunkDestruction(int chunkWorldX, int chunkWorldY) {
        arenaChunkWorldX = chunkWorldX;
        arenaChunkWorldY = chunkWorldY;
        arenaChunkDestructionQueued = true;
        /**
         * ROM: {@code Obj_CNZMinibossTop} snaps the impact coordinates to the
         * 0x20-pixel block grid before calling {@code CNZMiniboss_BlockExplosion}.
         * Task 7 uses that same block height as the destroyed-row accumulator so
         * the miniboss base can react to a ROM-sized arena step without the full
         * mutation pipeline from later slices.
         */
        destroyedArenaRows += 0x20;
    }

    private void processQueuedArenaChunkDestruction(int frameCounter) {
        if (!arenaChunkDestructionQueued) {
            return;
        }
        // Obj_CNZMinibossTop only stores Events_bg+$00/$02 and spawns the
        // explosion child; CNZ1_ScreenEvent performs the chunk-descriptor clear
        // on the next screen-event pass (sonic3k.asm:145182-145184,
        // 145204-145216, 107340-107365). Keeping the terrain mutation here
        // prevents same-frame object updates from erasing landing collision
        // before player/sidekick terrain collision observes it.
        int chunkWorldX = arenaChunkWorldX;
        int chunkWorldY = arenaChunkWorldY;
        arenaChunkDestructionQueued = false;
        lastArenaChunkClearX = chunkWorldX;
        lastArenaChunkClearY = chunkWorldY;
        recordArenaClearHistory(frameCounter, chunkWorldX, chunkWorldY);
        mutateArenaBlockCollision(chunkWorldX, chunkWorldY);
    }

    private void recordArenaClearHistory(int frameCounter, int chunkWorldX, int chunkWorldY) {
        arenaClearHistoryFrame[arenaClearHistoryCursor] = frameCounter;
        arenaClearHistoryX[arenaClearHistoryCursor] = chunkWorldX;
        arenaClearHistoryY[arenaClearHistoryCursor] = chunkWorldY;
        arenaClearHistoryCursor = (arenaClearHistoryCursor + 1) % ARENA_CLEAR_HISTORY_SIZE;
        if (arenaClearHistoryCount < ARENA_CLEAR_HISTORY_SIZE) {
            arenaClearHistoryCount++;
        }
    }

    private void mutateArenaBlockCollision(int snappedWorldX, int snappedWorldY) {
        if (!isWithinMinibossArenaMutationBounds(snappedWorldX, snappedWorldY)) {
            return;
        }
        Level level = levelManager() != null ? levelManager().getCurrentLevel() : null;
        if (level == null || level.getMap() == null || zoneLayoutMutationPipelineOrNull() == null) {
            return;
        }

        LevelMutationSurface surface = LevelMutationSurface.forLevel(level);
        LayoutMutationContext context = new LayoutMutationContext(surface, effects -> {
            if (levelManager() != null) {
                levelManager().applyMutationEffects(effects);
            }
        });
        zoneLayoutMutationPipeline().applyImmediately(ctx ->
                clearArenaCollisionCell(ctx.surface(), level, snappedWorldX, snappedWorldY), context);
    }

    private boolean isWithinMinibossArenaMutationBounds(int snappedWorldX, int snappedWorldY) {
        return snappedWorldX >= Sonic3kConstants.CNZ_MINIBOSS_TOP_ARENA_LEFT
                && snappedWorldX < Sonic3kConstants.CNZ_MINIBOSS_TOP_ARENA_RIGHT
                && snappedWorldY >= 0x0300
                && snappedWorldY < Sonic3kConstants.CNZ_MINIBOSS_TOP_ARENA_BOTTOM;
    }

    private MutationEffects clearArenaCollisionCell(LevelMutationSurface surface, Level level,
                                                   int snappedWorldX, int snappedWorldY) {
        int rawWorldX = snappedWorldX - (ARENA_CHUNK_CELL_SIZE / 2);
        int rawWorldY = snappedWorldY - (ARENA_CHUNK_CELL_SIZE / 2);
        int blockPixelSize = level.getBlockPixelSize();
        int blockX = Math.floorDiv(rawWorldX, blockPixelSize);
        int blockY = Math.floorDiv(rawWorldY, blockPixelSize);
        if (blockX < 0 || blockY < 0
                || blockX >= level.getLayerWidthBlocks(0)
                || blockY >= level.getLayerHeightBlocks(0)) {
            return MutationEffects.NONE;
        }

        int blockIndex = level.getMap().getValue(0, blockX, blockY) & 0xFF;
        if (blockIndex <= 0 || blockIndex >= level.getBlockCount()) {
            return MutationEffects.NONE;
        }

        Block block = level.getBlock(blockIndex);
        int[] state = block.saveState();

        int chunkMask = blockPixelSize - 1;
        int chunkX = ((rawWorldX & chunkMask) / CHUNK_PIXEL_SIZE) & ~1;
        int chunkY = ((rawWorldY & chunkMask) / CHUNK_PIXEL_SIZE) & ~1;
        int gridSide = block.getGridSide();
        for (int y = 0; y < 2; y++) {
            for (int x = 0; x < 2; x++) {
                int targetX = chunkX + x;
                int targetY = chunkY + y;
                if (targetX < gridSide && targetY < gridSide) {
                    state[targetY * gridSide + targetX] = ChunkDesc.EMPTY.get();
                }
            }
        }
        return surface.restoreBlockState(blockIndex, state);
    }

    private void copyPostBossBackgroundLayoutToForeground() {
        if (postBossForegroundVisualCopied) {
            return;
        }
        Level level = levelManager() != null ? levelManager().getCurrentLevel() : null;
        if (level == null || level.getMap() == null
                || level.getMap().getLayerCount() <= POST_BOSS_COPY_SOURCE_LAYER
                || zoneLayoutMutationPipelineOrNull() == null) {
            return;
        }

        LevelMutationSurface surface = LevelMutationSurface.forLevel(level);
        LayoutMutationContext context = new LayoutMutationContext(surface, effects -> {
            if (levelManager() != null) {
                levelManager().applyMutationEffects(effects);
            }
        });
        zoneLayoutMutationPipeline().applyImmediately(ctx -> {
            copyPostBossBackgroundLayoutToForeground(ctx.surface(), level);
            return MutationEffects.foregroundRedraw();
        }, context);
        postBossForegroundVisualCopied = true;
    }

    private void clearPostBossBackgroundCollisionFlag() {
        if (gameStateOrNull() != null) {
            // ROM clears Background_collision_flag after copying BG layout bytes
            // into FG collision (sonic3k.asm:107556-107563).
            gameState().setBackgroundCollisionFlag(false);
        }
    }

    private void copyPostBossBackgroundLayoutToForeground(LevelMutationSurface surface, Level level) {
        for (int row = 0; row < POST_BOSS_COPY_ROWS; row++) {
            int sourceY = POST_BOSS_COPY_SOURCE_Y + row;
            int destY = POST_BOSS_COPY_DEST_Y + row;
            if (sourceY >= level.getLayerHeightBlocks(POST_BOSS_COPY_SOURCE_LAYER)
                    || destY >= level.getLayerHeightBlocks(POST_BOSS_COPY_DEST_LAYER)) {
                continue;
            }
            for (int column = 0; column < POST_BOSS_COPY_COLUMNS; column++) {
                int sourceX = POST_BOSS_COPY_SOURCE_X + column;
                int destX = POST_BOSS_COPY_DEST_X + column;
                if (sourceX >= level.getLayerWidthBlocks(POST_BOSS_COPY_SOURCE_LAYER)
                        || destX >= level.getLayerWidthBlocks(POST_BOSS_COPY_DEST_LAYER)) {
                    continue;
                }
                int blockIndex = level.getMap()
                        .getValue(POST_BOSS_COPY_SOURCE_LAYER, sourceX, sourceY) & 0xFF;
                surface.setBlockInMapWithoutRedraw(POST_BOSS_COPY_DEST_LAYER, destX, destY, blockIndex);
            }
        }
    }

    public boolean isArenaChunkDestructionQueued() {
        return arenaChunkDestructionQueued;
    }

    public int getArenaChunkWorldX() {
        return arenaChunkWorldX;
    }

    public int getArenaChunkWorldY() {
        return arenaChunkWorldY;
    }

    /**
     * Alias used by the Task 7 headless tests.
     *
     * <p>The plan originally named these accessors after the queue contract
     * rather than the world-coordinate storage field. Keeping both names avoids
     * forcing later CNZ slices to rewrite their state vocabulary.
     */
    public int getPendingArenaChunkX() {
        return arenaChunkWorldX;
    }

    /**
     * Alias used by the Task 7 headless tests.
     */
    public int getPendingArenaChunkY() {
        return arenaChunkWorldY;
    }

    public int getLastArenaChunkClearX() {
        return lastArenaChunkClearX;
    }

    public int getLastArenaChunkClearY() {
        return lastArenaChunkClearY;
    }

    public int[] getArenaClearHistorySnapshot() {
        int[] snapshot = new int[arenaClearHistoryCount * 3];
        for (int i = 0; i < arenaClearHistoryCount; i++) {
            int source = Math.floorMod(arenaClearHistoryCursor - arenaClearHistoryCount + i,
                    ARENA_CLEAR_HISTORY_SIZE);
            snapshot[i * 3] = arenaClearHistoryFrame[source];
            snapshot[i * 3 + 1] = arenaClearHistoryX[source];
            snapshot[i * 3 + 2] = arenaClearHistoryY[source];
        }
        return snapshot;
    }

    /**
     * Returns the accumulated destroyed arena height in pixels.
     *
     * <p>Each queued top-piece impact contributes exactly one 0x20-pixel row,
     * matching the block-sized destruction seam exported from
     * {@code Obj_CNZMinibossTop}.
     */
    public int getDestroyedArenaRows() {
        return destroyedArenaRows;
    }

    public void setEventsFg5(boolean flag) {
        eventsFg5 = flag;
        if (flag) {
            LOG.info("CNZ: Events_fg_5 set externally");
        }
    }

    public boolean isEventsFg5() {
        return eventsFg5;
    }

    @Override
    public int getDynamicResizeRoutine() {
        return fgRoutine;
    }

    @Override
    public void setDynamicResizeRoutine(int routine) {
        fgRoutine = routine;
    }

    /**
     * Publishes the clamp values used by the teleporter route.
     */
    private void publishKnucklesTeleporterClamp() {
        cameraMinXClamp = KNUCKLES_ROUTE_MIN_X;
        cameraMaxXClamp = KNUCKLES_ROUTE_MAX_X;
    }

    // =========================================================================
    // Rewind accessors (C.4)
    // =========================================================================

    public void    setForegroundRoutine(int v)          { fgRoutine = v; }
    public boolean isCameraClampsActive()               { return cameraClampsActive; }
    public void    setCameraClampsActive(boolean v)     { cameraClampsActive = v; }
    public boolean isBossFlagPrev()                     { return bossFlagPrev; }
    public void    setBossFlagPrev(boolean v)           { bossFlagPrev = v; }
    public short   getCameraStoredMaxXPos()             { return cameraStoredMaxXPos; }
    public void    setCameraStoredMaxXPos(short v)      { cameraStoredMaxXPos = v; }
    public short   getCameraStoredMinXPos()             { return cameraStoredMinXPos; }
    public void    setCameraStoredMinXPos(short v)      { cameraStoredMinXPos = v; }
    public short   getCameraStoredMinYPos()             { return cameraStoredMinYPos; }
    public void    setCameraStoredMinYPos(short v)      { cameraStoredMinYPos = v; }
    public short   getCameraStoredMaxYPos()             { return cameraStoredMaxYPos; }
    public void    setCameraStoredMaxYPos(short v)      { cameraStoredMaxYPos = v; }
    public void requestSidekickBoundsPublishAfterCameraEasing() {
        sidekickBoundsPublishAfterCameraEasing = true;
    }
    public boolean consumeSidekickBoundsPublishAfterCameraEasing() {
        boolean pending = sidekickBoundsPublishAfterCameraEasing;
        sidekickBoundsPublishAfterCameraEasing = false;
        return pending;
    }
    public void    setKnucklesTeleporterRouteActive(boolean v){ knucklesTeleporterRouteActive = v; }
    public void    setTeleporterBeamSpawned(boolean v)  { teleporterBeamSpawned = v; }
    public void    setAct2TransitionRequested(boolean v){ act2TransitionRequested = v; }
    public void    setPendingZoneActWordRaw(int v)      { pendingZoneActWord = v; }
    public void    setTransitionWorldOffsetX(int v)     { transitionWorldOffsetX = v; }
    public void    setTransitionWorldOffsetY(int v)     { transitionWorldOffsetY = v; }
    public void    setCameraMinXClamp(int v)            { cameraMinXClamp = v; }
    public void    setCameraMaxXClamp(int v)            { cameraMaxXClamp = v; }
    public void    setArenaChunkWorldX(int v)           { arenaChunkWorldX = v; }
    public void    setArenaChunkWorldY(int v)           { arenaChunkWorldY = v; }
    public void    setArenaChunkDestructionQueued(boolean v){ arenaChunkDestructionQueued = v; }
    public void    setDestroyedArenaRows(int v)         { destroyedArenaRows = v; }
    public void    setBossBackgroundMode(BossBackgroundMode v){ bossBackgroundMode = v; }
}
