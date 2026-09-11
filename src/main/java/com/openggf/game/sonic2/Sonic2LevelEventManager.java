package com.openggf.game.sonic2;

import com.openggf.game.sonic2.events.*;
import java.nio.ByteBuffer;
import com.openggf.game.sonic2.runtime.CnzRuntimeStateView;
import com.openggf.game.sonic2.runtime.HtzRuntimeStateView;
import com.openggf.game.sonic2.runtime.WfzRuntimeStateView;
import com.openggf.game.AbstractLevelEventManager;
import com.openggf.game.GameServices;
import com.openggf.game.PlayerCharacter;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.session.ActiveGameplayTeamResolver;
import com.openggf.game.zone.NoOpZoneRuntimeState;
import com.openggf.game.zone.ZoneRuntimeRegistry;
import com.openggf.game.zone.ZoneRuntimeState;
import com.openggf.game.sonic2.constants.Sonic2Constants;
import com.openggf.game.sonic2.resources.Sonic2PlcService;
import com.openggf.game.sonic2.resources.Sonic2RuntimePlcPublisher;
import com.openggf.level.LevelManager;

import java.util.logging.Logger;

/**
 * Sonic 2 implementation of dynamic level events.
 * ROM equivalent: RunDynamicLevelEvents (s2.asm:20297-20340)
 *
 * This system allows levels to dynamically adjust camera boundaries
 * based on player position, triggering boss arenas, vertical section
 * transitions, and other gameplay sequences.
 *
 * Each zone has its own event handler dispatched via the zone index.
 * Zone-specific logic is delegated to per-zone handler classes in
 * the {@code events} subpackage, following the S1 pattern.
 */
public class Sonic2LevelEventManager extends AbstractLevelEventManager {
    // Zone constants (matches Sonic2ZoneRegistry ordering: game progression, 0-based)
    public static final int ZONE_EHZ = 0;
    public static final int ZONE_CPZ = 1;
    public static final int ZONE_ARZ = 2;
    public static final int ZONE_CNZ = 3;
    public static final int ZONE_HTZ = 4;
    public static final int ZONE_MCZ = 5;
    public static final int ZONE_OOZ = 6;
    public static final int ZONE_MTZ = 7;
    public static final int ZONE_SCZ = 8;
    public static final int ZONE_WFZ = 9;
    public static final int ZONE_DEZ = 10;

    private static final Logger LOGGER = Logger.getLogger(Sonic2LevelEventManager.class.getName());
    private static final int LEGACY_HANDLER_BYTES = 11 * 8;
    private static final int HANDLER_BYTES = 11 * 12;
    private static final int HTZ_EXTRA_BYTES = 22;
    private static final int CPZ_EXTRA_BYTES = 1;
    private static final int CNZ_EXTRA_BYTES = 16;
    private static final int LEGACY_EXTRA_BYTES = LEGACY_HANDLER_BYTES
            + HTZ_EXTRA_BYTES + CPZ_EXTRA_BYTES + CNZ_EXTRA_BYTES;
    private static final int PENDING_HANDLER_EXTRA_BYTES = HANDLER_BYTES - LEGACY_HANDLER_BYTES;
    private static final int EXTRA_BYTES = LEGACY_EXTRA_BYTES
            + PENDING_HANDLER_EXTRA_BYTES
            + Sonic2WFZEvents.SNAPSHOT_BYTES
            + Sonic2FixedAirCountdownManager.REWIND_STATE_BYTES;

    /** Cached player character resolved from config (lazy init). */
    private PlayerCharacter resolvedPlayerCharacter;

    // Zone event handlers (one per zone, each owns its own eventRoutine)
    private final Sonic2EHZEvents ehzEvents;
    private final Sonic2CPZEvents cpzEvents;
    private final Sonic2HTZEvents htzEvents;
    private final Sonic2MCZEvents mczEvents;
    private final Sonic2ARZEvents arzEvents;
    private final Sonic2CNZEvents cnzEvents;
    private final Sonic2OOZEvents oozEvents;
    private final Sonic2MTZEvents mtzEvents;
    private final Sonic2WFZEvents wfzEvents;
    private final Sonic2DEZEvents dezEvents;
    private final Sonic2SCZEvents sczEvents;
    private final Sonic2FixedAirCountdownManager fixedAirCountdownManager =
            new Sonic2FixedAirCountdownManager();

    public Sonic2LevelEventManager() {
        super();
        ehzEvents = new Sonic2EHZEvents();
        cpzEvents = new Sonic2CPZEvents();
        htzEvents = new Sonic2HTZEvents();
        mczEvents = new Sonic2MCZEvents();
        arzEvents = new Sonic2ARZEvents();
        cnzEvents = new Sonic2CNZEvents();
        oozEvents = new Sonic2OOZEvents();
        mtzEvents = new Sonic2MTZEvents();
        wfzEvents = new Sonic2WFZEvents();
        dezEvents = new Sonic2DEZEvents();
        sczEvents = new Sonic2SCZEvents();
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
        return 6;
    }

    @Override
    protected int getEventDataBgSize() {
        return 0;
    }

    @Override
    public PlayerCharacter getPlayerCharacter() {
        if (resolvedPlayerCharacter == null) {
            SonicConfigurationService config = GameServices.configuration();
            resolvedPlayerCharacter = config != null
                    ? ActiveGameplayTeamResolver.resolvePlayerCharacter(config)
                    : PlayerCharacter.SONIC_AND_TAILS;
        }
        return resolvedPlayerCharacter;
    }

    @Override
    protected void onInitLevel(int zone, int act) {
        Sonic2ZoneEvents handler = getActiveHandler();
        if (handler != null) {
            handler.init(act);
        }
        fixedAirCountdownManager.reset();
        if (!GameServices.hasRuntime()) {
            return;
        }
        ZoneRuntimeRegistry registry = GameServices.zoneRuntimeRegistry();
        if (zone == ZONE_HTZ) {
            installOwnedRuntimeState(registry, new HtzRuntimeStateView(zone, act, htzEvents));
        } else if (registry.currentAs(HtzRuntimeStateView.class).isPresent()) {
            registry.clear();
        }
        if (zone == ZONE_CNZ) {
            installOwnedRuntimeState(registry, new CnzRuntimeStateView(zone, act, cnzEvents));
        } else if (registry.currentAs(CnzRuntimeStateView.class).isPresent()) {
            registry.clear();
        }
        if (zone == ZONE_WFZ) {
            installOwnedRuntimeState(registry, new WfzRuntimeStateView(zone, act, wfzEvents));
        } else if (registry.currentAs(WfzRuntimeStateView.class).isPresent()) {
            registry.clear();
        }
    }

    private static void installOwnedRuntimeState(ZoneRuntimeRegistry registry, ZoneRuntimeState state) {
        if (registry.current() == NoOpZoneRuntimeState.INSTANCE || isOwnedSonic2RuntimeState(registry.current())) {
            registry.install(state);
        }
    }

    private static boolean isOwnedSonic2RuntimeState(ZoneRuntimeState state) {
        return state instanceof HtzRuntimeStateView
                || state instanceof CnzRuntimeStateView
                || state instanceof WfzRuntimeStateView;
    }

    @Override
    protected void onUpdate() {
        // Dispatch to zone-specific event handler
        Sonic2ZoneEvents handler = getActiveHandler();
        if (handler != null) {
            handler.update(currentAct, frameCounter);
        }
    }

    @Override
    protected void onUpdatePrePhysics() {
        // ROM WaterEffects runs before RunObjects (docs/s2disasm/s2.asm:5094-5095);
        // OOZ OilSlides lives in that pre-object slot. Dispatch the pre-physics
        // portion of the active zone handler. frameCounter is advanced by the
        // post-physics onUpdate() later this frame, so use frameCounter here.
        Sonic2ZoneEvents handler = getActiveHandler();
        if (handler != null) {
            handler.updatePrePhysics(currentAct, frameCounter);
        }
    }

    @Override
    public void updateFixedInLevelObjectsBeforeDynamicObjects() {
        // ROM's reserved object-RAM band (CPZPylon / WaterSurface1 aliased with
        // Oil / WaterSurface2) sits between the player object slots and
        // Dynamic_Object_RAM (docs/s2disasm/s2.constants.asm:1128-1137), so it
        // runs after player physics and before every dynamic level object.
        Sonic2ZoneEvents handler = getActiveHandler();
        if (handler != null) {
            handler.updateReservedObjectSlots(currentAct, frameCounter);
        }
    }

    @Override
    public void updateFixedInLevelObjects() {
        fixedAirCountdownManager.update();
    }

    @Override
    public boolean ownsFixedDrowningBubbleCadence(AbstractPlayableSprite player) {
        return fixedAirCountdownManager.ownsCadenceFor(player);
    }

    // =========================================================================
    // SetLevelEndType / CheckLoadSignpostArt (docs/s2disasm/s2.asm:6127-6172)
    // =========================================================================

    /** ROM {@code subi.w #$100,d1}: trigger $100px before the right boundary. */
    private static final int SIGNPOST_ART_PRELOAD_DISTANCE = 0x100;


    /**
     * ROM {@code Two_player_mode}. The engine has no two-player competitive
     * gameplay mode, so this word is always zero at level time; the gate is
     * kept explicit so the {@code SetLevelEndType} / {@code CheckLoadSignpostArt}
     * ports stay line-for-line readable against the ROM.
     */
    private static boolean isTwoPlayerMode() {
        return false;
    }

    /**
     * Port of {@code SetLevelEndType} (docs/s2disasm/s2.asm:6127-6146). The
     * {@code nosignpost} entries (docs/s2disasm/s2.asm:6120-6124 for the macro)
     * are transcribed literally from the ROM's own end-of-act-type table; acts
     * are 0-based here, so ROM act 2 is index 1 and ROM act 3 is index 2.
     */
    private static boolean romLevelHasSignpost(int zone, int act) {
        if (isTwoPlayerMode()) {
            return true;
        }
        return switch (zone) {
            case ZONE_EHZ -> act != 1;  // nosignpost emerald_hill_zone_act_2
            case ZONE_MTZ -> act != 2;  // nosignpost metropolis_zone_act_3
            case ZONE_WFZ -> act != 0;  // nosignpost wing_fortress_zone_act_1
            case ZONE_HTZ -> act != 1;  // nosignpost hill_top_zone_act_2
            case ZONE_OOZ -> act != 1;  // nosignpost oil_ocean_zone_act_2
            case ZONE_MCZ -> act != 1;  // nosignpost mystic_cave_zone_act_2
            case ZONE_CNZ -> act != 1;  // nosignpost casino_night_zone_act_2
            case ZONE_CPZ -> act != 1;  // nosignpost chemical_plant_zone_act_2
            case ZONE_DEZ -> act != 0;  // nosignpost death_egg_zone_act_1
            case ZONE_ARZ -> act != 1;  // nosignpost aquatic_ruin_zone_act_2
            case ZONE_SCZ -> act != 0;  // nosignpost sky_chase_zone_act_1
            default -> true;
        };
    }

    /**
     * Port of {@code CheckLoadSignpostArt} (docs/s2disasm/s2.asm:6152-6172),
     * called from the {@code Level_MainLoop} tail slot.
     * <p>
     * Once the camera comes within $100px of the right level boundary the ROM
     * locks the left boundary to that value and submits {@code PLCID_Signpost}
     * through {@code LoadPLC2} — ClearPLC-then-copy, i.e. a replace, not an
     * append (docs/s2disasm/s2.asm:2103-2124). {@code PLCptr_Signpost} is index
     * 39 in {@code ArtLoadCues} (docs/s2disasm/s2.asm:89194-89262) and holds a
     * single {@code plreq ArtTile_ArtNem_Signpost, ArtNem_Signpost}
     * (docs/s2disasm/s2.asm:89658-89660), i.e. 78 patterns into tile $0434.
     * <p>
     * The locked left boundary is the ROM's own re-fire latch, so no extra
     * engine "already fired" flag exists (or is needed).
     */
    @Override
    public void updateAtLevelLoopTail() {
        // tst.w (Level_Has_Signpost).w / beq.s + ; rts -- SetLevelEndType writes
        // the word at level start purely from Current_ZoneAndAct, so deriving it
        // here is equivalent and keeps no extra rewindable state.
        if (!romLevelHasSignpost(currentZone, currentAct)) {
            return;
        }
        // tst.w (Debug_placement_mode).w / bne.s + ; rts
        var camera = GameServices.cameraOrNull();
        if (camera == null) {
            return;
        }
        AbstractPlayableSprite player = camera.getFocusedSprite();
        if (player != null && player.isDebugMode()) {
            return;
        }
        // move.w (Camera_Max_X_pos).w,d1 / subi.w #$100,d1 / cmp.w d1,d0 / blt.s
        int threshold = (camera.getMaxX() & 0xFFFF) - SIGNPOST_ART_PRELOAD_DISTANCE;
        if ((camera.getX() & 0xFFFF) < threshold) {
            return;
        }
        // tst.b (Update_HUD_timer).w / beq.s -- signpost already touched.
        LevelManager level = levelManager();
        var gamestate = level != null ? level.getLevelGamestate() : null;
        if (gamestate == null || gamestate.isTimerPaused()) {
            return;
        }
        // cmp.w (Camera_Min_X_pos).w,d1 / beq.s -- already locked.
        if ((camera.getMinX() & 0xFFFF) == threshold) {
            return;
        }
        // move.w d1,(Camera_Min_X_pos).w -- the ROM writes the boundary word
        // directly, so this is setMinX (immediate), not the eased setMinXTarget.
        camera.setMinX((short) threshold);
        // tst.w (Two_player_mode).w / bne.s + ; rts
        if (isTwoPlayerMode()) {
            return;
        }
        // moveq #PLCID_Signpost,d0 / bra.w LoadPLC2
        if (!GameServices.hasRuntime() || level == null || level.getCurrentLevel() == null) {
            return;
        }
        Sonic2PlcService plcService = GameServices.module().getGameService(Sonic2PlcService.class);
        if (plcService == null
                || !(GameServices.module().getObjectArtProvider() instanceof Sonic2ObjectArtProvider artProvider)) {
            return;
        }
        try {
            Sonic2RuntimePlcPublisher.transact(artProvider, plcService,
                    level::refreshObjectArtPatterns,
                    Sonic2PlcService.replaceOperation(Sonic2Constants.PLC_SIGNPOST));
        } catch (RuntimeException | java.io.IOException e) {
            // A ROM read failure leaves the boundary locked, matching the ROM's
            // single-shot latch; the eager signpost art path keeps rendering.
            LOGGER.fine(() -> "S2 signpost PLC deferred: " + e.getMessage());
        }
    }

    // =========================================================================
    // Zone handler dispatch
    // =========================================================================

    private Sonic2ZoneEvents getActiveHandler() {
        return switch (currentZone) {
            case ZONE_EHZ -> ehzEvents;
            case ZONE_CPZ -> cpzEvents;
            case ZONE_HTZ -> htzEvents;
            case ZONE_MCZ -> mczEvents;
            case ZONE_ARZ -> arzEvents;
            case ZONE_CNZ -> cnzEvents;
            case ZONE_OOZ -> oozEvents;
            case ZONE_MTZ -> mtzEvents;
            case ZONE_WFZ -> wfzEvents;
            case ZONE_DEZ -> dezEvents;
            case ZONE_SCZ -> sczEvents;
            default -> null;
        };
    }

    // =========================================================================
    // Public API - event routine delegation
    // =========================================================================

    /**
     * Get the current zone's event routine counter.
     * S2 delegates routine counters to per-zone handlers.
     */
    public int getEventRoutine() {
        Sonic2ZoneEvents handler = getActiveHandler();
        return handler != null ? handler.getEventRoutine() : 0;
    }

    /**
     * Set the current zone's event routine counter.
     */
    public void setEventRoutine(int routine) {
        Sonic2ZoneEvents handler = getActiveHandler();
        if (handler != null) {
            handler.setEventRoutine(routine);
        }
    }

    /**
     * Returns the HTZ event handler (test/diagnostic access).
     * The handler owns the canonical {@code earthquakeActive} flag previously
     * stored on {@code GameStateManager}.
     */
    public Sonic2HTZEvents getHtzEvents() {
        return htzEvents;
    }

    /** Returns the EHZ event handler (test/diagnostic access). */
    public Sonic2EHZEvents getEhzEventsForTest() {
        return ehzEvents;
    }

    /** Returns the CPZ event handler (test/diagnostic access). */
    public Sonic2CPZEvents getCpzEventsForTest() {
        return cpzEvents;
    }

    /** Returns the CNZ event handler (test/diagnostic access). */
    public Sonic2CNZEvents getCnzEventsForTest() {
        return cnzEvents;
    }

    public Sonic2WFZEvents getWfzEvents() {
        return wfzEvents;
    }

    // =========================================================================
    // RewindSnapshottable extra-state hooks (C.3)
    // =========================================================================

    /** Helper: write eventRoutine + bossSpawnDelay for one zone handler. */
    private static void writeHandler(ByteBuffer buf, Sonic2ZoneEvents h) {
        buf.putInt(h.getEventRoutine());
        buf.putInt(h.getBossSpawnDelay());
        buf.putInt(h.getPendingPlcIdForRewind());
    }

    /** Helper: restore eventRoutine + bossSpawnDelay for one zone handler. */
    private static void readHandler(ByteBuffer buf, Sonic2ZoneEvents h, boolean includesPendingPlc) {
        h.setEventRoutine(buf.getInt());
        h.setBossSpawnDelay(buf.getInt());
        if (includesPendingPlc) {
            h.setPendingPlcIdForRewind(buf.getInt());
        }
    }

    @Override
    protected byte[] captureExtra() {
        // 11 handlers x 12 bytes + HTZ (22) + CPZ (1) + CNZ (16) + WFZ (32)
        // + fixed Sonic/Tails Obj0A air-countdown sidecars (28).
        ByteBuffer buf = ByteBuffer.allocate(EXTRA_BYTES);
        writeHandler(buf, ehzEvents);
        writeHandler(buf, cpzEvents);
        writeHandler(buf, htzEvents);
        writeHandler(buf, mczEvents);
        writeHandler(buf, arzEvents);
        writeHandler(buf, cnzEvents);
        writeHandler(buf, oozEvents);
        writeHandler(buf, mtzEvents);
        writeHandler(buf, wfzEvents);
        writeHandler(buf, dezEvents);
        writeHandler(buf, sczEvents);
        // HTZ extra state
        buf.putInt(htzEvents.getCameraBgYOffsetRaw());
        buf.put((byte) (htzEvents.isHtzTerrainSinking() ? 1 : 0));
        buf.putInt(htzEvents.getHtzTerrainDelay());
        buf.put((byte) (htzEvents.isEarthquakeActiveRaw() ? 1 : 0));
        buf.putInt(htzEvents.getHtzCurrentRisenLimit());
        buf.putInt(htzEvents.getHtzCurrentSunkenLimit());
        buf.putInt(htzEvents.getHtzCurrentBgXOffset());
        // CPZ extra
        buf.put((byte) (cpzEvents.isCpzWaterTriggered() ? 1 : 0));
        // CNZ extra
        buf.putInt(cnzEvents.getCnzLeftWallX());
        buf.putInt(cnzEvents.getCnzLeftWallY());
        buf.putInt(cnzEvents.getCnzRightWallX());
        buf.putInt(cnzEvents.getCnzRightWallY());
        // WFZ extra
        wfzEvents.captureSnapshot(buf);
        fixedAirCountdownManager.writeRewindState(buf);
        return buf.array();
    }

    @Override
    protected void restoreExtra(byte[] extra) {
        if (extra == null || extra.length < LEGACY_EXTRA_BYTES) {
            return;
        }
        ByteBuffer buf = ByteBuffer.wrap(extra);
        boolean includesPendingPlc = extra.length >= LEGACY_EXTRA_BYTES + PENDING_HANDLER_EXTRA_BYTES;
        readHandler(buf, ehzEvents, includesPendingPlc);
        readHandler(buf, cpzEvents, includesPendingPlc);
        readHandler(buf, htzEvents, includesPendingPlc);
        readHandler(buf, mczEvents, includesPendingPlc);
        readHandler(buf, arzEvents, includesPendingPlc);
        readHandler(buf, cnzEvents, includesPendingPlc);
        readHandler(buf, oozEvents, includesPendingPlc);
        readHandler(buf, mtzEvents, includesPendingPlc);
        readHandler(buf, wfzEvents, includesPendingPlc);
        readHandler(buf, dezEvents, includesPendingPlc);
        readHandler(buf, sczEvents, includesPendingPlc);
        // HTZ extra
        htzEvents.setCameraBgYOffset(buf.getInt());
        htzEvents.setHtzTerrainSinking(buf.get() != 0);
        htzEvents.setHtzTerrainDelay(buf.getInt());
        htzEvents.setEarthquakeActiveRaw(buf.get() != 0);
        htzEvents.setHtzCurrentRisenLimit(buf.getInt());
        htzEvents.setHtzCurrentSunkenLimit(buf.getInt());
        htzEvents.setHtzCurrentBgXOffset(buf.getInt());
        // CPZ extra
        cpzEvents.setCpzWaterTriggered(buf.get() != 0);
        // CNZ extra
        cnzEvents.setCnzLeftWallX(buf.getInt());
        cnzEvents.setCnzLeftWallY(buf.getInt());
        cnzEvents.setCnzRightWallX(buf.getInt());
        cnzEvents.setCnzRightWallY(buf.getInt());
        // WFZ extra was added after the original S2 event snapshot schema.
        if (buf.remaining() >= Sonic2WFZEvents.SNAPSHOT_BYTES) {
            wfzEvents.restoreSnapshot(buf);
        }
        if (buf.remaining() >= Sonic2FixedAirCountdownManager.REWIND_STATE_BYTES) {
            fixedAirCountdownManager.readRewindState(buf);
        }
    }

    @Override
    public java.util.List<com.openggf.game.rewind.RewindSnapshottable<?>> extraRewindAdapters() {
        return java.util.List.of(new ButtonVineTriggerStaticAdapter());
    }
}
