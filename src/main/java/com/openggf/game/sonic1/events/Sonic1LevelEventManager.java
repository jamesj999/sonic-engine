package com.openggf.game.sonic1.events;

import com.openggf.game.AbstractLevelEventManager;
import com.openggf.game.PlayerCharacter;
import com.openggf.game.sonic1.Sonic1LoopManager;
import com.openggf.game.sonic1.Sonic1StomperDoorSingletonRewindAdapter;
import com.openggf.game.sonic1.resources.Sonic1PlcService;
import com.openggf.game.sonic1.objects.Sonic1FixedEndCardSlot;
import com.openggf.game.sonic1.scroll.Sonic1ZoneConstants;
import com.openggf.level.LevelManager;
import com.openggf.level.objects.ObjectManager;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Sonic 1 implementation of dynamic level events.
 * ROM equivalent: DynamicLevelEvents (_inc/DynamicLevelEvents.asm)
 *
 * Each zone has its own event handler that adjusts camera boundaries
 * based on player/camera position. Act 3 boss zones use a state machine
 * pattern (eventRoutine incremented by 2, matching ROM behavior).
 *
 * The Camera class handles smooth boundary easing for the bottom boundary
 * (matching the ROM's +/-2px/frame behavior). Top, left, and right boundaries
 * are set immediately (no easing) to match the original.
 *
 * Note: S1 delegates eventRoutine to per-zone handler classes rather than
 * using the base class's eventRoutineFg. Each zone handler owns its own
 * counter, which is needed because S1 zones can revert routines independently.
 */
public class Sonic1LevelEventManager extends AbstractLevelEventManager {
    // Zone event handlers (one per zone, each owns its own eventRoutine)
    private final Sonic1GHZEvents ghzEvents;
    private final Sonic1LZEvents lzEvents;
    private final Sonic1MZEvents mzEvents;
    private final Sonic1SLZEvents slzEvents;
    private final Sonic1SYZEvents syzEvents;
    private final Sonic1SBZEvents sbzEvents;
    private final Sonic1EndingEvents endingEvents;
    private final Sonic1FixedAirCountdownManager fixedAirCountdownManager =
            new Sonic1FixedAirCountdownManager(Sonic1ZoneEvents::focusedSpriteOrNull);
    private final Sonic1FixedTitleCardManager fixedTitleCardManager =
            new Sonic1FixedTitleCardManager(() -> gameService(Sonic1PlcService.class));

    // Loop/plane switching manager
    private final Sonic1LoopManager loopManager = new Sonic1LoopManager();

    // Guard against re-triggering the SBZ2->SBZ3 pit death intercept during fade
    private boolean sbz3TransitionRequested;

    public Sonic1LevelEventManager() {
        super();
        ghzEvents = new Sonic1GHZEvents();
        lzEvents = new Sonic1LZEvents();
        mzEvents = new Sonic1MZEvents();
        slzEvents = new Sonic1SLZEvents();
        syzEvents = new Sonic1SYZEvents();
        sbzEvents = new Sonic1SBZEvents();
        endingEvents = new Sonic1EndingEvents();
    }

    // =========================================================================
    // AbstractLevelEventManager contract
    // =========================================================================

    @Override
    protected int getRoutineStride() {
        return 2;
    }

    @Override
    protected int getEventDataFgSize() {
        return 0;
    }

    @Override
    protected int getEventDataBgSize() {
        return 0;
    }

    @Override
    public PlayerCharacter getPlayerCharacter() {
        return PlayerCharacter.SONIC_AND_TAILS;
    }

    @Override
    protected void onInitLevel(int zone, int act) {
        // Reset all zone handlers (only one is active at a time,
        // but reset all for clean state)
        ghzEvents.init();
        lzEvents.init();
        mzEvents.init();
        slzEvents.init();
        syzEvents.init();
        sbzEvents.init();
        endingEvents.init();
        loopManager.initLevel(zone, act);
        fixedAirCountdownManager.reset();
        sbz3TransitionRequested = false;
    }

    @Override
    protected void onUpdate() {
        // Dispatch to zone-specific event handler (ROM: DLE_Index)
        // currentZone is the gameplay progression index from LevelManager
        switch (currentZone) {
            case Sonic1ZoneConstants.ZONE_GHZ -> ghzEvents.update(currentAct);
            case Sonic1ZoneConstants.ZONE_LZ -> lzEvents.update(currentAct);
            case Sonic1ZoneConstants.ZONE_MZ -> mzEvents.update(currentAct);
            case Sonic1ZoneConstants.ZONE_SLZ -> slzEvents.update(currentAct);
            case Sonic1ZoneConstants.ZONE_SYZ -> syzEvents.update(currentAct);
            case Sonic1ZoneConstants.ZONE_SBZ -> sbzEvents.update(currentAct);
            // Zone 6 (FZ) = Final Zone in our engine
            // ROM treats FZ as SBZ act 2; our engine has it as zone 6
            case Sonic1ZoneConstants.ZONE_FZ -> sbzEvents.updateFZ();
            case Sonic1ZoneConstants.ZONE_ENDING -> endingEvents.update(currentAct);
            default -> { /* DLE_Ending: rts */ }
        }
    }

    @Override
    public void updateFixedInLevelObjectsBeforeDynamicObjects() {
        fixedAirCountdownManager.update();
        // ExecuteObjects keeps running the fixed title-card slots after
        // Level_StartGame (docs/s1disasm/sonic.asm:2969-2995); the level-name
        // element's Card_Wait/Card_MoveOut tail re-queues the explosion and
        // animal art (docs/s1disasm/_incObj/34 Title Cards.asm:122-168).
        fixedTitleCardManager.update();
        LevelManager level = levelManager();
        if (level != null && level.getObjectManager() != null) {
            Sonic1FixedEndCardSlot.updateFixedPass(
                    level.getObjectManager(),
                    level.getObjectManager().getVblaCounter(),
                    Sonic1ZoneEvents.focusedSpriteOrNull());
        }
    }

    /** ROM {@code act3} (docs/s1disasm/sonic.asm:3186); acts are 0-based here. */
    private static final int ACT_3 = 2;

    /**
     * ROM {@code plcid_Signpost}: index 18 into {@code ArtLoadCues}
     * (docs/s1disasm/_inc/Pattern Load Cues.asm:28-50). The list holds exactly
     * three entries — Nem_SignPost, Nem_Bonus, Nem_BigFlash
     * (docs/s1disasm/_inc/Pattern Load Cues.asm:295-299).
     */
    private static final int PLC_ID_SIGNPOST = 18;

    /** ROM {@code subi.w #$100,d1}: trigger $100px before the right boundary. */
    private static final int SIGNPOST_ART_PRELOAD_DISTANCE = 0x100;

    /**
     * ROM {@code SignpostArtLoad} (docs/s1disasm/sonic.asm:3183-3201), called
     * from the {@code Level_MainLoop} tail after {@code SynchroAnimate}
     * (docs/s1disasm/sonic.asm:3032).
     * <p>
     * Once the camera comes within $100px of the right level boundary the ROM
     * locks the left boundary to that value and submits {@code plcid_Signpost}
     * through {@code NewPLC} — ClearPLC-then-copy, i.e.
     * {@link Sonic1PlcService#replaceQueued(int)}
     * (docs/s1disasm/sonic.asm:1332-1352). {@code ProcessPLC_3Tiles} then drains
     * it at three tiles per frame (docs/s1disasm/sonic.asm:1439-1460).
     * <p>
     * The locked left boundary is the ROM's own re-fire latch, so no extra
     * engine "already fired" flag exists (or is needed). The eager
     * {@code Sonic1ObjectArtProvider.loadSignpostArt} path is deliberately left
     * alone: this makes the runtime PLC queue ROM-faithful without changing
     * which art is resident.
     */
    @Override
    public void updateAtLevelLoopTail() {
        // The tail slot belongs to Level_MainLoop. A level whose ROM game mode
        // runs its own loop — the ending sequence's End_MainLoop
        // (docs/s1disasm/sonic.asm:3662-3680) — never reaches this call at all.
        Sonic1ZoneEvents handler = getActiveHandler();
        if (handler != null && !handler.runsLevelMainLoopTail()) {
            return;
        }
        // tst.w (v_debuguse).w / bne.w .return
        AbstractPlayableSprite player = Sonic1ZoneEvents.focusedSpriteOrNull();
        if (player != null && player.isDebugMode()) {
            return;
        }
        // cmpi.b #act3,(v_act).w / beq.s .return -- boss fight owns act 3's art.
        // Read the ROM-effective act, not the engine's logical act: the ROM has
        // no separate Final Zone level, it is SBZ act 3 (v_zone=5, v_act=act3),
        // whereas the engine models FZ as its own logical zone with act 0. Using
        // the raw act let FZ fall through this gate and submit plcid_Signpost
        // into an active Nemesis decoder, which throws.
        LevelManager actLevel = levelManager();
        int romAct = actLevel != null ? actLevel.getRomActId() : currentAct;
        if (romAct == ACT_3) {
            return;
        }
        var camera = cameraOrNull();
        if (camera == null) {
            return;
        }
        // move.w (v_limitright2).w,d1 / subi.w #$100,d1 / cmp.w d1,d0 / blt.s .return
        int threshold = (camera.getMaxX() & 0xFFFF) - SIGNPOST_ART_PRELOAD_DISTANCE;
        if ((camera.getX() & 0xFFFF) < threshold) {
            return;
        }
        // tst.b (f_timecount).w / beq.s .return -- signpost already touched.
        LevelManager level = levelManager();
        var gamestate = level != null ? level.getLevelGamestate() : null;
        if (gamestate == null || gamestate.isTimerPaused()) {
            return;
        }
        // cmp.w (v_limitleft2).w,d1 / beq.s .return -- already locked.
        if ((camera.getMinX() & 0xFFFF) == threshold) {
            return;
        }
        // move.w d1,(v_limitleft2).w -- ROM writes the boundary word directly,
        // so this is setMinX (immediate), not the eased setMinXTarget.
        camera.setMinX((short) threshold);
        // moveq #plcid_Signpost,d0 / bra.w NewPLC
        Sonic1PlcService plcService = gameService(Sonic1PlcService.class);
        if (plcService != null) {
            try {
                plcService.replaceQueued(PLC_ID_SIGNPOST);
            } catch (IOException ignored) {
                // A ROM read failure leaves the boundary locked, matching the
                // ROM's single-shot latch; the eager art path keeps rendering.
            }
        }
    }

    /**
     * Arms the fixed title-card object tail at the ROM's {@code Level_StartGame}
     * boundary (docs/s1disasm/sonic.asm:2969-2972), whether or not the engine
     * presented the sliding card sprites.
     */
    public void armTitleCardArtReloadAtLevelStart(
            int progressionZone, int progressionAct, int romZoneId) {
        fixedTitleCardManager.armAtLevelStart(progressionZone, progressionAct, romZoneId);
    }

    /** Whether the fixed level-name title-card element is still executing. */
    public boolean isTitleCardArtReloadPending() {
        return fixedTitleCardManager.isLive();
    }

    @Override
    public boolean ownsFixedDrowningBubbleCadence(AbstractPlayableSprite player) {
        return fixedAirCountdownManager.ownsCadenceFor(player);
    }

    // =========================================================================
    // S1-specific accessors
    // =========================================================================

    /**
     * Get the current zone's event routine counter.
     * S1 delegates routine counters to per-zone handlers.
     * Used by lamppost save/restore (ROM: v_dle_routine).
     */
    public int getEventRoutine() {
        return getActiveHandler() != null ? getActiveHandler().getEventRoutine() : 0;
    }

    /**
     * Set the current zone's event routine counter.
     * Used by lamppost restore.
     */
    public void setEventRoutine(int routine) {
        var handler = getActiveHandler();
        if (handler != null) {
            handler.setEventRoutine(routine);
        }
    }

    // =========================================================================
    // RewindSnapshottable extra-state hooks (C.2)
    // =========================================================================

    /**
     * Packs S1-specific extra state into a byte array:
     * <ol>
     *   <li>1 byte: sbz3TransitionRequested flag</li>
     *   <li>7 × 4 bytes: per-zone handler eventRoutine (ghz, lz, mz, slz, syz, sbz, ending)</li>
     *   <li>1 byte: sbzEvents.fzTransitionRequested</li>
     *   <li>1 byte: endingEvents.bootstrapApplied</li>
     *   <li>1 byte: endingEvents.endingSonicSpawned</li>
     *   <li>14 bytes: fixed v_sonicbubbles countdown sidecar</li>
     *   <li>7 × 4 bytes: pending S1 PLC work for each handler</li>
     *   <li>9 bytes: fixed title-card object tail (routine, timer, X, final X, v_zone)</li>
     * </ol>
     */
    @Override
    protected byte[] captureExtra() {
        ByteBuffer buf = ByteBuffer.allocate(1 + 7 * 4 + 3
                + Sonic1FixedAirCountdownManager.REWIND_STATE_BYTES + 7 * 4
                + Sonic1FixedTitleCardManager.REWIND_STATE_BYTES);
        buf.put((byte) (sbz3TransitionRequested ? 1 : 0));
        buf.putInt(ghzEvents.eventRoutine);
        buf.putInt(lzEvents.eventRoutine);
        buf.putInt(mzEvents.eventRoutine);
        buf.putInt(slzEvents.eventRoutine);
        buf.putInt(syzEvents.eventRoutine);
        buf.putInt(sbzEvents.eventRoutine);
        buf.putInt(endingEvents.eventRoutine);
        buf.put((byte) (sbzEvents.isFzTransitionRequested() ? 1 : 0));
        buf.put((byte) (endingEvents.isBootstrapApplied() ? 1 : 0));
        buf.put((byte) (endingEvents.isEndingSonicSpawned() ? 1 : 0));
        fixedAirCountdownManager.writeRewindState(buf);
        buf.putInt(ghzEvents.getPendingPlcIdForRewind());
        buf.putInt(lzEvents.getPendingPlcIdForRewind());
        buf.putInt(mzEvents.getPendingPlcIdForRewind());
        buf.putInt(slzEvents.getPendingPlcIdForRewind());
        buf.putInt(syzEvents.getPendingPlcIdForRewind());
        buf.putInt(sbzEvents.getPendingPlcIdForRewind());
        buf.putInt(endingEvents.getPendingPlcIdForRewind());
        fixedTitleCardManager.writeRewindState(buf);
        return buf.array();
    }

    @Override
    protected void restoreExtra(byte[] extra) {
        int baseSize = 1 + 7 * 4 + 3;
        if (extra == null || extra.length < baseSize) {
            return;
        }
        ByteBuffer buf = ByteBuffer.wrap(extra);
        sbz3TransitionRequested         = buf.get() != 0;
        ghzEvents.eventRoutine          = buf.getInt();
        lzEvents.eventRoutine           = buf.getInt();
        mzEvents.eventRoutine           = buf.getInt();
        slzEvents.eventRoutine          = buf.getInt();
        syzEvents.eventRoutine          = buf.getInt();
        sbzEvents.eventRoutine          = buf.getInt();
        endingEvents.eventRoutine       = buf.getInt();
        sbzEvents.setFzTransitionRequested(buf.get() != 0);
        endingEvents.setBootstrapApplied(buf.get() != 0);
        endingEvents.setEndingSonicSpawned(buf.get() != 0);
        if (buf.remaining() >= Sonic1FixedAirCountdownManager.REWIND_STATE_BYTES) {
            fixedAirCountdownManager.readRewindState(buf);
        }
        if (buf.remaining() >= 7 * 4) {
            ghzEvents.setPendingPlcIdForRewind(buf.getInt());
            lzEvents.setPendingPlcIdForRewind(buf.getInt());
            mzEvents.setPendingPlcIdForRewind(buf.getInt());
            slzEvents.setPendingPlcIdForRewind(buf.getInt());
            syzEvents.setPendingPlcIdForRewind(buf.getInt());
            sbzEvents.setPendingPlcIdForRewind(buf.getInt());
            endingEvents.setPendingPlcIdForRewind(buf.getInt());
        }
        if (buf.remaining() >= Sonic1FixedTitleCardManager.REWIND_STATE_BYTES) {
            fixedTitleCardManager.readRewindState(buf);
        }
    }

    private Sonic1ZoneEvents getActiveHandler() {
        return switch (currentZone) {
            case Sonic1ZoneConstants.ZONE_GHZ -> ghzEvents;
            case Sonic1ZoneConstants.ZONE_LZ -> lzEvents;
            case Sonic1ZoneConstants.ZONE_MZ -> mzEvents;
            case Sonic1ZoneConstants.ZONE_SLZ -> slzEvents;
            case Sonic1ZoneConstants.ZONE_SYZ -> syzEvents;
            case Sonic1ZoneConstants.ZONE_SBZ, Sonic1ZoneConstants.ZONE_FZ -> sbzEvents;
            case Sonic1ZoneConstants.ZONE_ENDING -> endingEvents;
            default -> null;
        };
    }

    /**
     * ROM: Sonic_LevelBound intercepts bottom boundary death in SBZ act 2.
     * When Sonic falls through the collapsing floor and X >= 0x2000,
     * the game transitions to SBZ3 (LZ act 3) instead of killing Sonic.
     */
    @Override
    public boolean interceptPitDeath(AbstractPlayableSprite player) {
        if (sbz3TransitionRequested) {
            return true; // Already requested; suppress death until fade completes
        }
        AbstractPlayableSprite progressionOwner = camera().getFocusedSprite();
        if (player == null || player != progressionOwner) {
            return false;
        }
        if (currentZone == Sonic1ZoneConstants.ZONE_SBZ && currentAct == 1) {
            int playerX = player.getCentreX() & 0xFFFF;
            if (playerX >= 0x2000) {
                // Transition to SBZ3 (our engine: zone SBZ, act 2)
                sbz3TransitionRequested = true;
                Sonic1ZoneEvents.lockPlayersForTransition(progressionOwner);
                levelManager().requestZoneAndAct(
                        Sonic1ZoneConstants.ZONE_SBZ, 2);
                return true;
            }
        }
        return false;
    }

    public Sonic1LoopManager getLoopManager() {
        return loopManager;
    }

    /**
     * S1's floor routines write {@code move.b #id_Walk,obAnim(a0)} unconditionally
     * when Sonic lands ({@code Sonic_Floor}/{@code Sonic_FloorLeft}/
     * {@code Sonic_FloorRight} .landed, docs/s1disasm/_incObj/"01 Sonic.asm":1563,
     * 1692, 1825). Level_MainLoop runs LZWaterFeatures before ExecuteObjects
     * (docs/s1disasm/sonic.asm:3005-3006), so the wind tunnel's
     * {@code move.b #id_Float2,obAnim(a1)}
     * (docs/s1disasm/_inc/LZWaterFeatures.asm:409 -- the only writer of
     * {@code id_Float2} in the disassembly) is clobbered again in the same frame
     * whenever Sonic re-lands. The engine models that byte with a sticky forced
     * animation, so release the tunnel's own write here; Sonic_Control's repair
     * ({@code obAnim==0 -> obPrevAni}, "01 Sonic.asm":86-90) is modelled by
     * PlayableSpriteAnimation.restoreWaterTunnelPreviousAnimation and runs next.
     */
    @Override
    public void onPlayableLandingAnimationWrite(AbstractPlayableSprite playable) {
        if (playable == null
                || playable.getForcedAnimationId()
                        != com.openggf.game.sonic1.constants.Sonic1AnimationIds.FLOAT2.id()) {
            return;
        }
        playable.setForcedAnimationId(-1);
        playable.setAnimationId(com.openggf.game.sonic1.constants.Sonic1AnimationIds.WALK);
    }

    @Override
    public java.util.List<com.openggf.game.rewind.RewindSnapshottable<?>> extraRewindAdapters() {
        return java.util.List.of(
                new com.openggf.game.sonic1.Sonic1ConveyorStateRewindAdapter(),
                new com.openggf.game.sonic1.Sonic1FloatingBlockStateRewindAdapter());
    }

    @Override
    public void reconcileAfterRewindRestore() {
        LevelManager level = levelManager();
        ObjectManager objectManager = level != null ? level.getObjectManager() : null;
        Sonic1StomperDoorSingletonRewindAdapter.rebindAfterObjectRestore(objectManager);
    }
}
