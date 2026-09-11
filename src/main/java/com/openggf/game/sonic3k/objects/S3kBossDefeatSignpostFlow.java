package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.S3kPaletteOwners;
import com.openggf.game.sonic3k.S3kPaletteWriteSupport;
import com.openggf.game.sonic3k.constants.Sonic3kAnimationIds;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.events.S3kAizEventWriteSupport;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnCoordinateDefaultArgsRewindRecreatable;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.ObjectControlState;

import java.util.List;
import java.util.logging.Logger;

/**
 * Orchestrates the boss-defeat-to-signpost flow.
 *
 * <p>ROM: Obj_EndSignControl (sonic3k.asm) — invisible state machine that
 * manages the transition from boss defeat (explosions, music fade) through
 * signpost spawn, results screen, and act transition.
 *
 * <p>4-phase state machine:
 * <ol>
 *   <li><b>WAIT_FADE</b> — counts down 0x77 frames while music fades</li>
 *   <li><b>SPAWN_SIGNPOST</b> — clears boss flag, spawns signpost, runs cleanup</li>
 *   <li><b>AWAIT_RESULTS</b> — polls until results screen clears endOfLevelActive</li>
 *   <li><b>AWAIT_ACT_TRANSITION</b> — polls until endOfLevelFlag is set, then self-destructs</li>
 * </ol>
 */
public class S3kBossDefeatSignpostFlow extends AbstractObjectInstance
        implements SpawnCoordinateDefaultArgsRewindRecreatable {
    private static final Logger LOG = Logger.getLogger(S3kBossDefeatSignpostFlow.class.getName());

    private enum Phase { WAIT_FADE, SPAWN_SIGNPOST, AWAIT_RESULTS, AWAIT_ACT_TRANSITION }

    public enum CleanupAction {
        NONE,
        RESTORE_AIZ_FIRE_PALETTE,
        RESTORE_ICZ2_OBJECT_PALETTE,
        /**
         * ROM: AfterBoss_LBZ falls through to AfterBoss_MHZ and loads the first
         * line of Pal_MHZ2 via PalLoad_Line1. For LBZ1 this is an original-game
         * bug ("LBZ uses a post-boss routine meant for MHZ") that the engine
         * replicates for accuracy.
         */
        LOAD_MHZ2_OBJECT_PALETTE
    }

    /** ROM: Obj_EndSignControl timer = $77 (119 frames). */
    private static final int FADE_TIMER = 0x77;

    /** Y offset above camera top for initial signpost spawn position. */
    private static final int SIGNPOST_Y_OFFSET = 0x20;

    private Phase phase;
    private int timer;
    private int signpostX;
    // Non-final: apparentAct/cleanupAction are not derivable from the carried
    // ObjectSpawn (only getX()/getY() are captured -> signpostX/0). The rewind
    // recreate hook passes placeholders (0, CleanupAction.NONE) and the
    // GenericFieldCapturer reapplies these captured values after recreate.
    private int apparentAct;
    private CleanupAction cleanupAction;
    private int initialWaitCatchUpEntries;
    private int signpostResultsTimerCatchUpEntries;
    private int resultsWaitDurationAdjustment;
    private int resultsPostControlHandoffDelayEntries;
    private boolean preservesGroundedResultsDispatchBoundary;
    private boolean usesShortResultsChildRetireTail;
    private int nativeControlSlot = -1;
    private boolean initialized;
    private boolean nativeResultsControlRestored;

    /**
     * Creates the defeat-to-signpost flow orchestrator.
     *
     * @param signpostX           world X position where the signpost should appear
     * @param apparentAct         ROM's Apparent_act value (0 = act 1, 1 = act 2 display).
     *                            For mid-act bosses like AIZ1 miniboss, this is 0 even though
     *                            the engine may have reloaded act 2 resources.
     * @param cleanupAction action to run after spawning the signpost (e.g. palette restore)
     */
    public S3kBossDefeatSignpostFlow(int signpostX, int apparentAct, CleanupAction cleanupAction) {
        this(signpostX, apparentAct, cleanupAction, 0, 0, 0, 0);
    }

    S3kBossDefeatSignpostFlow(int signpostX, int apparentAct, CleanupAction cleanupAction,
            int signpostResultsTimerCatchUpEntries, int resultsWaitDurationAdjustment,
            int resultsPostControlHandoffDelayEntries) {
        this(signpostX, apparentAct, cleanupAction, 0,
                signpostResultsTimerCatchUpEntries, resultsWaitDurationAdjustment,
                resultsPostControlHandoffDelayEntries);
    }

    S3kBossDefeatSignpostFlow(int signpostX, int apparentAct, CleanupAction cleanupAction,
            int initialWaitCatchUpEntries, int signpostResultsTimerCatchUpEntries,
            int resultsWaitDurationAdjustment, int resultsPostControlHandoffDelayEntries) {
        this(signpostX, apparentAct, cleanupAction, initialWaitCatchUpEntries,
                signpostResultsTimerCatchUpEntries, resultsWaitDurationAdjustment,
                resultsPostControlHandoffDelayEntries, false);
    }

    S3kBossDefeatSignpostFlow(int signpostX, int apparentAct, CleanupAction cleanupAction,
            int initialWaitCatchUpEntries, int signpostResultsTimerCatchUpEntries,
            int resultsWaitDurationAdjustment, int resultsPostControlHandoffDelayEntries,
            boolean preservesGroundedResultsDispatchBoundary) {
        this(signpostX, apparentAct, cleanupAction, initialWaitCatchUpEntries,
                signpostResultsTimerCatchUpEntries, resultsWaitDurationAdjustment,
                resultsPostControlHandoffDelayEntries,
                preservesGroundedResultsDispatchBoundary, false);
    }

    S3kBossDefeatSignpostFlow(int signpostX, int apparentAct, CleanupAction cleanupAction,
            int initialWaitCatchUpEntries, int signpostResultsTimerCatchUpEntries,
            int resultsWaitDurationAdjustment, int resultsPostControlHandoffDelayEntries,
            boolean preservesGroundedResultsDispatchBoundary,
            boolean usesShortResultsChildRetireTail) {
        super(new ObjectSpawn(signpostX, 0, 0, 0, 0, false, 0), "S3kBossDefeatSignpostFlow");
        this.signpostX = signpostX;
        this.apparentAct = apparentAct;
        this.cleanupAction = cleanupAction == null ? CleanupAction.NONE : cleanupAction;
        this.initialWaitCatchUpEntries = Math.max(0, initialWaitCatchUpEntries);
        this.signpostResultsTimerCatchUpEntries = Math.max(0, signpostResultsTimerCatchUpEntries);
        this.resultsWaitDurationAdjustment = Math.max(0, resultsWaitDurationAdjustment);
        this.resultsPostControlHandoffDelayEntries = Math.max(0, resultsPostControlHandoffDelayEntries);
        this.preservesGroundedResultsDispatchBoundary = preservesGroundedResultsDispatchBoundary;
        this.usesShortResultsChildRetireTail = usesShortResultsChildRetireTail;
        this.phase = Phase.WAIT_FADE;
        this.timer = FADE_TIMER;
    }

    private S3kBossDefeatSignpostFlow() {
        this(0, 0, CleanupAction.NONE);
    }

    S3kBossDefeatSignpostFlow withNativeControlSlot(int slot) {
        nativeControlSlot = slot;
        return this;
    }

    @Override
    public int getX() {
        return signpostX;
    }

    @Override
    public int getY() {
        return 0;
    }

    @Override
    public boolean isPersistent() {
        return true;
    }

    private void ensureInitialized() {
        if (initialized) {
            return;
        }
        initialized = true;
        // Signal that the end-of-level sequence is active
        services().gameState().setEndOfLevelActive(true);
        LOG.fine("S3K defeat flow started — WAIT_FADE, timer=" + timer);
    }

    int waitTimerAfterInitialization() {
        return timer - initialWaitCatchUpEntries;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        if (!initialized) {
            ensureInitialized();
            timer -= initialWaitCatchUpEntries;
            // Obj_EndSignControl installs Obj_EndSignControlWait and returns;
            // its $77 timer is first decremented on the following object pass.
            return;
        }
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (isDestroyed()) {
            return;
        }

        switch (phase) {
            case WAIT_FADE -> updateWaitFade();
            case SPAWN_SIGNPOST -> updateSpawnSignpost();
            case AWAIT_RESULTS -> updateAwaitResults(player);
            case AWAIT_ACT_TRANSITION -> updateAwaitActTransition();
        }
    }

    // =========================================================================
    // Phase 1: WAIT_FADE
    // =========================================================================

    private void updateWaitFade() {
        timer--;
        if (timer <= 0) {
            // ROM: boss_saved_mus played when timer expires (sonic3k.asm:180484-180486).
            // Restore the zone music before the signpost spawns.
            resumeZoneMusic();
            phase = Phase.SPAWN_SIGNPOST;
            LOG.fine("S3K defeat flow WAIT_FADE -> SPAWN_SIGNPOST");
        }
    }

    /**
     * Plays the current zone's act music (ROM: boss_saved_mus).
     * Each boss saves the level music ID on spawn; after defeat the same
     * track is restored before the signpost appears.
     */
    private void resumeZoneMusic() {
        try {
            int zone = services().romZoneId();
            int act = services().currentAct();
            var zoneRegistry = services().gameModule().getZoneRegistry();
            int musicId = zoneRegistry.getMusicId(zone, act);
            if (musicId >= 0) {
                services().playMusic(musicId);
            }
        } catch (Exception e) {
            LOG.fine("Could not resume zone music: " + e.getMessage());
        }
    }

    // =========================================================================
    // Phase 2: SPAWN_SIGNPOST
    // =========================================================================

    private void updateSpawnSignpost() {
        // Clear boss flag and boss ID so level events resume normal behavior
        S3kAizEventWriteSupport.setBossFlag(services(), false);
        services().gameState().setCurrentBossId(0);

        // Spawn signpost above camera
        S3kSignpostInstance signpost = new S3kSignpostInstance(
                signpostX, apparentAct, signpostResultsTimerCatchUpEntries, resultsWaitDurationAdjustment,
                resultsPostControlHandoffDelayEntries, preservesGroundedResultsDispatchBoundary,
                usesShortResultsChildRetireTail);
        signpost.preserveNativeControlAllocationBoundary(nativeControlSlot);
        spawnDynamicObject(signpost);
        LOG.fine("S3K defeat flow spawned signpost at X=" + signpostX);

        runCleanupAction();

        phase = Phase.AWAIT_RESULTS;
        LOG.fine("S3K defeat flow SPAWN_SIGNPOST -> AWAIT_RESULTS");
    }

    private void runCleanupAction() {
        try {
            switch (cleanupAction) {
                case NONE -> {
                    // No zone-specific cleanup.
                }
                case RESTORE_AIZ_FIRE_PALETTE -> restoreAizFirePalette();
                case RESTORE_ICZ2_OBJECT_PALETTE -> restoreIcz2ObjectPalette();
                case LOAD_MHZ2_OBJECT_PALETTE -> loadMhz2ObjectPalette();
            }
        } catch (Exception e) {
            LOG.fine("Zone cleanup action failed: " + e.getMessage());
        }
    }

    private void restoreAizFirePalette() throws Exception {
        // AfterBoss_AIZ2: restore fire palette to palette line 1.
        // ROM: lea (Pal_AIZFire).l,a1 / jsr (PalLoad_Line1).l.
        byte[] palData = services().rom().readBytes(Sonic3kConstants.PAL_AIZ_FIRE_ADDR, 32);
        S3kPaletteWriteSupport.applyLine(
                services().paletteOwnershipRegistryOrNull(),
                services().currentLevel(),
                services().graphicsManager(),
                S3kPaletteOwners.AIZ_MINIBOSS,
                S3kPaletteOwners.PRIORITY_CUTSCENE_OVERRIDE,
                1,
                palData);
    }

    private void loadMhz2ObjectPalette() throws Exception {
        // ROM AfterBoss_MHZ (shared by AfterBoss_LBZ): lea (Pal_MHZ2).l,a1 /
        // jmp (PalLoad_Line1).l — only the first 32-byte line is loaded.
        int entryAddr = Sonic3kConstants.PAL_POINTERS_ADDR
                + Sonic3kConstants.PAL_POINTERS_MHZ2_INDEX * Sonic3kConstants.PAL_POINTER_ENTRY_SIZE;
        int sourceAddr = services().rom().read32BitAddr(entryAddr) & 0x00FFFFFF;
        byte[] palData = services().rom().readBytes(sourceAddr, 32);
        S3kPaletteWriteSupport.applyLine(
                services().paletteOwnershipRegistryOrNull(),
                services().currentLevel(),
                services().graphicsManager(),
                S3kPaletteOwners.LBZ_MINIBOSS,
                S3kPaletteOwners.PRIORITY_CUTSCENE_OVERRIDE,
                1,
                palData);
    }

    private void restoreIcz2ObjectPalette() throws Exception {
        // AfterBoss_ICZ2: lea (Pal_ICZ2).l,a1 / jmp (PalLoad_Line1).l.
        int entryAddr = Sonic3kConstants.PAL_POINTERS_ADDR
                + Sonic3kConstants.PAL_POINTERS_ICZ2_INDEX * Sonic3kConstants.PAL_POINTER_ENTRY_SIZE;
        int sourceAddr = services().rom().read32BitAddr(entryAddr) & 0x00FFFFFF;
        byte[] palData = services().rom().readBytes(sourceAddr, 32);
        S3kPaletteWriteSupport.applyLine(
                services().paletteOwnershipRegistryOrNull(),
                services().currentLevel(),
                services().graphicsManager(),
                S3kPaletteOwners.ICZ_MINIBOSS,
                S3kPaletteOwners.PRIORITY_CUTSCENE_OVERRIDE,
                1,
                palData,
                true);
    }

    // =========================================================================
    // Phase 3: AWAIT_RESULTS
    // =========================================================================

    private void updateAwaitResults(AbstractPlayableSprite player) {
        if (restoreNativeControlAtResultsBoundary(player)) {
            // Obj_EndSignControlAwaitStart owns this boundary independently of
            // Obj_LevelResults' publication pass. Keep polling End_of_level_active
            // so the results owner can publish and the normal handoff can set the
            // next phase on its following owner dispatch.
            return;
        }
        if (!services().gameState().isEndOfLevelActive()) {
            if (!nativeResultsControlRestored) {
                restoreNativePlayerControlIfNeeded(player);
                if (services().playerQuery().nativeP2OrNull() instanceof AbstractPlayableSprite nativeP2
                        && nativeP2 != player) {
                    restoreNativePlayerControlIfNeeded(nativeP2);
                }
            }
            phase = Phase.AWAIT_ACT_TRANSITION;
            LOG.fine("S3K defeat flow AWAIT_RESULTS -> AWAIT_ACT_TRANSITION");
        }
    }

    private boolean restoreNativeControlAtResultsBoundary(AbstractPlayableSprite player) {
        if (services().objectManager() == null) {
            return false;
        }
        boolean ready = services().objectManager()
                .activeObjectsOfType(S3kResultsScreenObjectInstance.class).stream()
                .anyMatch(S3kResultsScreenObjectInstance::isEndSignControlRestoreBoundaryReady);
        if (!ready) {
            return false;
        }
        return restoreNativeControlAtResultsPublication(player);
    }

    /**
     * Completes the native {@code Obj_EndSignControlAwaitStart} handoff when
     * the results owner publishes its next routine in the same object pass.
     * The ordinary polling path uses the same operation when the owner is
     * observed before publication; this entry point covers the native slot
     * ordering where {@code Obj_LevelResultsWait2} clears the latch first.
     */
    boolean restoreNativeControlAtResultsPublication(AbstractPlayableSprite player) {
        completeNativeSignpostPoseHandoff();
        if (nativeResultsControlRestored) {
            return true;
        }
        restoreNativePlayerControlIfNeeded(player);
        if (services().playerQuery().nativeP2OrNull() instanceof AbstractPlayableSprite nativeP2
                && nativeP2 != player) {
            restoreNativePlayerControlIfNeeded(nativeP2);
        }
        nativeResultsControlRestored = true;
        return true;
    }

    private static void restoreNativePlayerControlIfNeeded(AbstractPlayableSprite player) {
        if (player == null || (!player.isObjectControlled() && !player.isControlLocked())) {
            return;
        }
        restoreNativePlayerControl(player);
    }

    private void completeNativeSignpostPoseHandoff() {
        var objectManager = services().objectManager();
        if (objectManager == null) {
            return;
        }
        for (S3kSignpostInstance signpost :
                objectManager.activeObjectsOfType(S3kSignpostInstance.class)) {
            if (!signpost.isDestroyed()) {
                signpost.completeNativeResultsControlRestore();
            }
        }
    }

    /**
     * ROM: {@code Obj_EndSignControlAwaitStart} calls
     * {@code Restore_PlayerControl} / {@code Restore_PlayerControl2} as soon
     * as {@code _unkFAA8} clears. The routine leaves the title-card controller
     * lock and velocities independently owned, while clearing object control,
     * interaction and in-air state and publishing a fresh Wait animation
     * (docs/skdisasm/sonic3k.asm:180361-180424).
     */
    static void restoreNativePlayerControl(AbstractPlayableSprite player) {
        if (player == null) {
            return;
        }
        ObjectControlState.none().applyTo(player);
        player.setInteractSlotIndex(0);
        player.clearAirForNativeControlRestore();
        player.setAnimationId(Sonic3kAnimationIds.WAIT);
        player.getAnimationManager().publishPreviousAnimationId(
                Sonic3kAnimationIds.WAIT.id());
        player.setAnimationFrameIndex(0);
        player.setAnimationTick(0);
    }

    // =========================================================================
    // Phase 4: AWAIT_ACT_TRANSITION
    // =========================================================================

    private void updateAwaitActTransition() {
        if (services().gameState().isEndOfLevelFlag()) {
            setDestroyed(true);
            LOG.fine("S3K defeat flow complete — destroyed");
        }
    }

    // =========================================================================
    // Rendering (invisible orchestrator)
    // =========================================================================

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        // No visual rendering — this is an invisible orchestrator object.
    }
}
