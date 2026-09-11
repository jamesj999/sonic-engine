package com.openggf.level.objects;


import com.openggf.camera.Camera;
import com.openggf.game.CollisionModel;
import com.openggf.game.GameStateManager;
import com.openggf.game.solid.ContactKind;
import com.openggf.game.solid.ObjectSolidExecutionContext;
import com.openggf.game.solid.PlayerSolidContactResult;
import com.openggf.game.solid.PlayerStandingState;
import com.openggf.game.solid.PostContactState;
import com.openggf.game.solid.PreContactState;
import com.openggf.game.solid.SolidCheckpointBatch;
import com.openggf.game.solid.SolidExecutionRegistry;
import com.openggf.graphics.FadeManager;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.spawn.AbstractPlacementManager;
import com.openggf.physics.ObjectTerrainUtils;
import com.openggf.physics.TerrainCheckResult;
import com.openggf.game.PlayableEntity;
import com.openggf.game.DamageCause;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.Knuckles;
import com.openggf.sprites.playable.SidekickCpuController;
import com.openggf.sprites.playable.Tails;
import com.openggf.sprites.NativePositionOps;
import com.openggf.sprites.managers.SpriteManager;
import com.openggf.game.GroundMode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Logger;

final class ObjectPlacementController extends AbstractPlacementManager<ObjectSpawn> {
    private static final Logger LOGGER = Logger.getLogger(ObjectPlacementController.class.getName());
    // ROM: ObjectsManager_GoingForward (s2.asm) uses addi.w #$280,d6 for forward load range.
    // Behind-window unload range is one chunk ($80) for forward movement.
    // At native width (320) EXTRA_AHEAD (0x140=320) + width (320) = 0x280 (640) = legacy window.
    private static final int EXTRA_AHEAD = 0x140; // 320; native -> 0x280 (640) window
    private static final int S1_COUNTER_LOAD_AHEAD = 0x280;
    private static final int UNLOAD_BEHIND = 0x80;
    private static final int CHUNK_MASK = 0xFF80;
    /** ROM: OPL_Next advances v_opl_screen by one chunk (0x80) per frame. */
    private static final int CHUNK_STEP = 0x80;

    private final BitSet remembered = new BitSet();
    /** Tracks spawns that should stay in active even when remembered (e.g. broken monitors). */
    private final BitSet stayActive = new BitSet();
    /** Tracks spawns destroyed while in the window - prevents respawn until they leave the window. */
    private final BitSet destroyedInWindow = new BitSet();
    private final BitSet pendingCursorLoad = new BitSet();
    private final ArrayList<Integer> pendingCursorLoadOrder = new ArrayList<>();
    /** Reused result buffer for {@link #pendingCursorLoadSpawns()}; see its contract. */
    private final ArrayList<ObjectSpawn> drainedCursorLoadScratch = new ArrayList<>();
    private final BitSet deferredVerticalLoad = new BitSet();
    /**
     * ROM parity: tracks spawns whose instance was deleted via out_of_range
     * during ExecuteObjects but whose cursor position hasn't changed.
     * In the ROM, DeleteObject zeroes the SST slot but ObjPosLoad's cursors
     * are unaware. The spawn stays "between cursors" as a dead slot. It is
     * NOT re-created until the cursor naturally retreats past it and then
     * re-advances (via the backward/forward scan cycle).
     * <p>
     * syncActiveSpawnsLoad must skip dormant spawns — only the cursor system
     * can clear dormant (when it re-processes the spawn position).
     */
    private final BitSet dormant = new BitSet();
    private int cursorIndex = 0;
    private int lastCameraX = Integer.MIN_VALUE;
    private int lastCameraChunk = Integer.MIN_VALUE;

    private boolean counterBasedRespawn;
    private boolean execThenLoadPlacement;
    private boolean twoAxisCursorPlacement;
    /**
     * ROM parity: per-game windowing strategy. When it
     * {@link ObjectWindowingStrategy#overridesLoadWindow() overrides the load
     * window}, the non-counter load/trim scan sources its load and trim
     * boundaries from the strategy (the final
     * {@code ObjectsManager_GoingForward}/{@code GoingBackward} cursor edges,
     * s2.asm:33095) instead of the symmetric widescreen-capped window. S2 only;
     * S1 (counter path) and S3K (two-axis) use {@link ObjectWindowingStrategy#LEGACY}
     * and keep their existing window source.
     */
    private ObjectWindowingStrategy windowingStrategy = ObjectWindowingStrategy.LEGACY;
    /**
     * ROM parity: when true, destroyedInWindow stays latched permanently
     * after a spawn is destroyed by the player (ROM's bit 7 of
     * Object_respawn_table; see sonic3k.asm loc_1BA40 / loc_1BA64
     * `bset #7,(a3)` which is set on every spawn and cleared only by
     * Sprite_OnScreen_Test family on the live instance going off-screen
     * -- so a player kill, which routes through Obj_Explosion +
     * Delete_Current_Sprite, leaves bit 7 set forever).
     * <p>
     * S3K: true. Every layout entry has its own respawn-table slot and
     * the cursor's spawn helpers always set bit 7, so destroyed badniks
     * never come back until level init wipes the table at loc_1B784.
     * <p>
     * S1 / S2: false. ROM's ObjPosLoad / ObjectsManager_Main only set
     * the bit-7 latch for spawns that explicitly opt in via the
     * "respawn-tracked" flag (S1 obj-id-byte bit 7;
     * S2 yWord bit 15 -- {@code tst.b 2(a0); bpl.s +} in
     * docs/s2disasm/s2.asm:33402); non-tracked spawns always re-spawn on
     * cursor entry. The engine models that opt-in via {@code remembered}.
     */
    private boolean permanentDestroyLatch;
    private java.util.function.IntSupplier usedSlotCounter;
    private int maxDynamicSlots = 96;

    /**
     * ROM parity: tracks scroll direction from last Placement.update().
     * S1 ObjPosLoad backward scan (camera scrolling left) processes objects
     * in DESCENDING X order (a0 -= 6), while forward scan uses ascending X.
     * syncActiveSpawnsLoad uses this to sort new spawns accordingly.
     */
    private boolean lastScrollBackward;

    /**
     * Callback for inline instance creation during cursor advancement.
     * ROM parity: ObjPosLoad creates objects immediately via FindFreeObj
     * during cursor scans. This callback eliminates the 1-frame pipeline
     * delay between cursor advancement and instance creation.
     */
    @FunctionalInterface
    interface SpawnCallback {
        /**
         * @param spawn the spawn to create
         * @param counterValue the counter assigned (-1 for non-tracked)
         * @return true if created successfully (false = FindFreeObj failure → stop scan)
         */
        boolean tryCreate(ObjectSpawn spawn, int counterValue);
    }

    /** Set during updateAndLoad to enable inline creation. Null = deferred mode. */
    private SpawnCallback inlineCallback;

    // ================================================================
    // S1 counter-based respawn state (ROM: v_objstate system)
    // ================================================================
    // ROM: v_opl_data+4 cursor; engine equivalent is leftCursorIndex.
    private int leftCursorIndex;
    // ROM: v_objstate[0] = forward counter, v_objstate[1] = backward counter.
    // Both start at 1 after OPL_Main init.
    private int fwdCounter;
    private int bwdCounter;
    // ROM: v_objstate[2..255] — per-counter-slot state.
    // Bit 7: set = object loaded or permanently destroyed.
    private final int[] objState = new int[256];
    // Maps active spawn (identity) → counter value assigned during load.
    // Used to clear objState bit when the object is normally unloaded.
    private final IdentityHashMap<ObjectSpawn, Integer> spawnToCounter = new IdentityHashMap<>();

    ObjectPlacementController(List<ObjectSpawn> spawns, java.util.function.IntSupplier widthSupplier) {
        super(spawns, EXTRA_AHEAD, UNLOAD_BEHIND, widthSupplier);
    }

    /**
     * Adjusts tracking state after a camera wrap-back so that the next
     * {@link #update(int)} call sees a small positive delta instead of a
     * large negative one, preventing a spurious {@link #refreshWindow(int)}.
     *
     * @param wrapDelta positive distance the camera X was decreased
     */
    void adjustForWrap(int wrapDelta) {
        if (lastCameraX != Integer.MIN_VALUE) {
            lastCameraX -= wrapDelta;
            lastCameraChunk = toCoarseChunk(lastCameraX);
        }
    }

    void enableCounterBasedRespawn() {
        this.counterBasedRespawn = true;
        this.execThenLoadPlacement = true;
    }

    void enableExecThenLoadPlacement() {
        this.execThenLoadPlacement = true;
    }

    void setTwoAxisCursorPlacement(boolean twoAxisCursorPlacement) {
        this.twoAxisCursorPlacement = twoAxisCursorPlacement;
    }

    void setWindowingStrategy(ObjectWindowingStrategy strategy) {
        this.windowingStrategy = strategy != null ? strategy : ObjectWindowingStrategy.LEGACY;
    }

    boolean reservedSlotWaitsForNextObjectPass(int slotIndex, boolean updating,
            int currentExecSlot, ObjectSlotLayout slotLayout) {
        if (!updating || currentExecSlot < 0 || !slotLayout.isDynamicSlot(slotIndex)) {
            return false;
        }
        int execIndex = slotLayout.toExecIndex(slotIndex);
        return execIndex >= 0 && execIndex <= currentExecSlot;
    }

    boolean reallocateToFirstFreeDynamicSlot(AbstractObjectInstance object,
            SlotAllocator slotAllocator, ObjectSlotLayout slotLayout,
            ObjectInstance[] execOrder, boolean updating,
            BitSet slotsFreedDuringObjectPass) {
        if (object == null || !slotLayout.isDynamicSlot(object.getSlotIndex())) {
            return false;
        }
        int oldSlot = object.getSlotIndex();
        if (updating) {
            slotsFreedDuringObjectPass.set(oldSlot);
        }
        slotAllocator.release(oldSlot);
        int newSlot = slotAllocator.allocate();
        if (newSlot < 0) {
            slotAllocator.reserve(oldSlot);
            return false;
        }
        object.setSlotIndex(newSlot);
        int oldExecIndex = slotLayout.toExecIndex(oldSlot);
        if (oldExecIndex >= 0 && oldExecIndex < execOrder.length
                && execOrder[oldExecIndex] == object) {
            execOrder[oldExecIndex] = null;
        }
        return true;
    }

    boolean releaseSpawnForRespawn(ObjectInstance transformedInstance, ObjectSpawn spawn,
            Map<ObjectSpawn, ObjectInstance> activeObjects,
            Map<ObjectInstance, ObjectSpawn> instanceToSpawn,
            List<ObjectInstance> dynamicObjects) {
        if (transformedInstance == null || spawn == null
                || activeObjects.get(spawn) != transformedInstance) {
            return false;
        }
        activeObjects.remove(spawn);
        instanceToSpawn.remove(transformedInstance);
        dynamicObjects.add(transformedInstance);
        removeFromActiveForUnload(spawn);
        if (twoAxisCursorPlacement) {
            // Camera_Y re-scans entries between the live X cursors after the
            // transformed instance clears its placement bit.
            markDeferredVerticalLoad(spawn);
        }
        return true;
    }

    boolean dispatchDestroyRemoveFromActive(ObjectInstance instance, ObjectSpawn spawn,
            boolean rememberRespawnTrackedSpawn) {
        if (instance.isDestroyedRespawnable()) {
            boolean retainForTwoAxisYPass = twoAxisCursorPlacement
                    && isBetweenLoadCursors(spawn);
            removeFromActiveForUnload(spawn);
            if (retainForTwoAxisYPass) {
                // ROM loc_1B982 reconsiders every clear respawn-table entry
                // inside the live X window on the next Y-coarse crossing.
                markDeferredVerticalLoad(spawn);
            }
            return false;
        }
        if (rememberRespawnTrackedSpawn && spawn != null && spawn.respawnTracked()) {
            markRemembered(spawn);
        }
        removeFromActive(spawn);
        return true;
    }

    boolean isSpawnVerticallyEligibleForTwoAxisYPass(ObjectSpawn spawn,
            int previousYCoarse, int currentYCoarse, Camera camera) {
        if (spawn == null || currentYCoarse == previousYCoarse) {
            return false;
        }
        int bandTop = currentYCoarse > previousYCoarse
                ? currentYCoarse + 0x180
                : currentYCoarse - 0x80;
        int bandBottom = currentYCoarse > previousYCoarse ? bandTop + 0x80 : currentYCoarse;
        int spawnY = spawn.rawYWord() & 0x0FFF;
        int wrapRange = camera != null && camera.isVerticalWrapEnabled()
                ? camera.getVerticalWrapRange()
                : 0;
        if (wrapRange > 0 && (short) (camera != null ? camera.getMinY() : 0) < 0) {
            int wrapMask = wrapRange - 1;
            bandTop &= wrapMask;
            bandBottom &= wrapMask;
            return bandTop <= bandBottom
                    ? spawnY >= bandTop && spawnY <= bandBottom
                    : spawnY >= bandTop || spawnY <= bandBottom;
        }
        return bandTop >= 0 && spawnY >= bandTop && spawnY <= bandBottom;
    }

    static boolean isNonCounterSpawnVerticallyEligible(ObjectSpawn spawn,
            int cameraY, int cameraMinY, int verticalWrapRange) {
        if ((spawn.rawYWord() & 0x8000) != 0) {
            return true;
        }
        int windowTop = (cameraY & 0xFF80) - 0x80;
        int windowBottom = (cameraY & 0xFF80) + 0x200;
        int spawnY = spawn.rawYWord() & 0x0FFF;
        if ((short) cameraMinY < 0) {
            int wrapRange = verticalWrapRange > 0 ? verticalWrapRange : 0x1000;
            int wrapMask = wrapRange - 1;
            if (windowTop < 0) {
                return spawnY >= (windowTop & wrapMask) || spawnY <= windowBottom;
            }
            if (windowBottom > wrapRange) {
                return spawnY >= windowTop || spawnY <= (windowBottom & wrapMask);
            }
        } else {
            windowTop = Math.max(0, windowTop);
        }
        return spawnY >= windowTop && spawnY <= windowBottom;
    }

    /**
     * ROM-exact S2 load/trim boundaries.
     * <p>
     * For S2 the non-counter scan consumes the windowing strategy's final
     * cursor edges directly:
     * <ul>
     *   <li>forward load / right trim edge = {@code loadCoarse + $280}
     *       ({@code ObjectsManager_GoingForward} right cursor, s2.asm:33099;
     *       {@code GoingBackward} right trim, s2.asm:33076)</li>
     *   <li>backward load / left trim edge = {@code loadCoarse - $80}
     *       ({@code GoingBackward} left cursor, s2.asm:33045;
     *       {@code GoingForward} left trim, s2.asm:33117)</li>
     * </ul>
     * Both directions are handled by the existing {@code spawnForward} /
     * {@code spawnBackwardNonCounter} / {@code trimLeftNonCounter} /
     * {@code trimRightNonCounter} strict-{@code <} comparisons, which mirror the
     * ROM {@code bls}/{@code bge}/{@code bgt} branches (exclusive load edge:
     * a spawn loads iff {@code spawn.x < forwardLoadEdge}).
     */
    @Override
    protected int getWindowEnd(int cameraX) {
        if (windowingStrategy.overridesLoadWindow()) {
            return windowingStrategy.loadWindowForwardEdge(cameraX);
        }
        return super.getWindowEnd(cameraX);
    }

    @Override
    protected int getLoadAhead() {
        if (counterBasedRespawn || twoAxisCursorPlacement) {
            // S1 and S3K ObjPosLoad use a fixed native right edge:
            // d6 = (v_opl_screen & $FF80) + $280
            // (docs/s1disasm/s1disasm/_inc/ObjPosLoad.asm, OPL_MovedRight;
            // docs/skdisasm/sonic3k.asm:37545-37628). S3K's Y-camera pass
            // filters candidates inside that fixed X cursor range; it does not
            // widen object allocation for a widescreen renderer.
            return S1_COUNTER_LOAD_AHEAD;
        }
        return super.getLoadAhead();
    }

    @Override
    protected int getWindowStart(int cameraX) {
        if (windowingStrategy.overridesLoadWindow()) {
            return Math.max(0, windowingStrategy.loadWindowLeftTrimEdge(cameraX));
        }
        return super.getWindowStart(cameraX);
    }

    /** See {@link #permanentDestroyLatch}. Enable for S3K only. */
    void enablePermanentDestroyLatch() {
        this.permanentDestroyLatch = true;
    }

    void enforceSlotLimit(java.util.function.IntSupplier counter) {
        this.usedSlotCounter = counter;
    }

    com.openggf.game.rewind.snapshot.ObjectManagerSnapshot.PlacementSnapshot captureRewindState(
            int twoAxisCameraYCoarse, int s2LatchedCameraX) {
        int[] activeIndices = active.stream()
                .mapToInt(this::getSpawnIndex)
                .filter(index -> index >= 0)
                .toArray();

        List<com.openggf.game.rewind.snapshot.ObjectManagerSnapshot.SpawnCounterEntry> counters =
                new ArrayList<>();
        for (Map.Entry<ObjectSpawn, Integer> entry : spawnToCounter.entrySet()) {
            int index = getSpawnIndex(entry.getKey());
            if (index >= 0) {
                counters.add(new com.openggf.game.rewind.snapshot.ObjectManagerSnapshot.SpawnCounterEntry(
                        index, entry.getValue()));
            }
        }
        counters.sort(Comparator.comparingInt(
                com.openggf.game.rewind.snapshot.ObjectManagerSnapshot.SpawnCounterEntry::spawnIndex));

        return new com.openggf.game.rewind.snapshot.ObjectManagerSnapshot.PlacementSnapshot(
                activeIndices,
                remembered.toLongArray(),
                stayActive.toLongArray(),
                destroyedInWindow.toLongArray(),
                dormant.toLongArray(),
                cursorIndex,
                lastCameraX,
                lastCameraChunk,
                counterBasedRespawn,
                execThenLoadPlacement,
                permanentDestroyLatch,
                maxDynamicSlots,
                lastScrollBackward,
                leftCursorIndex,
                fwdCounter,
                bwdCounter,
                compactObjState(),
                counters,
                pendingCursorLoad.toLongArray(),
                pendingCursorLoadOrder.stream()
                        .mapToInt(Integer::intValue)
                        .toArray(),
                deferredVerticalLoad.toLongArray(),
                twoAxisCameraYCoarse,
                s2LatchedCameraX);
    }

    /**
     * Captures just the persistent respawn-remember state (the engine's
     * {@code Object_respawn_table} model: {@link #remembered} and
     * {@link #stayActive}), excluding the windowing state (active set, cursors,
     * counters) that a reload rebuilds. Used to carry the respawn table across a
     * bonus-stage or special-stage round-trip reload; see
     * {@link PersistentRespawnState}.
     */
    PersistentRespawnState capturePersistentRespawn() {
        return new PersistentRespawnState(remembered.toLongArray(), stayActive.toLongArray(),
                destroyedInWindow.toLongArray());
    }

    /**
     * Re-establishes the respawn-remember state captured by
     * {@link #capturePersistentRespawn()} into this (freshly loaded) controller,
     * so a spawn remembered before a stage round-trip is not respawned intact on
     * return. OR-merges the bits, preserving anything the fresh load already
     * marked. Spawn indices are stable because the return reloads the same
     * zone/act, so the layout (and therefore the index of each spawn) is identical.
     */
    void restorePersistentRespawn(PersistentRespawnState state) {
        if (state == null) {
            return;
        }
        remembered.or(BitSet.valueOf(state.rememberedBits()));
        stayActive.or(BitSet.valueOf(state.stayActiveBits()));
        // S3K's permanent-destroy latch lives in destroyedInWindow, not in
        // `remembered`: the ROM keeps the whole Object_respawn_table across a
        // giant-ring special-stage round trip (Respawn_table_keep = 1,
        // docs/skdisasm/sonic3k.asm:128409-128412, which makes the reload skip
        // the table wipe at :37429-37438), so a bit 7 left set by
        // Delete_Current_Sprite must survive the return.
        destroyedInWindow.or(BitSet.valueOf(state.destroyedInWindowBits()));
    }

    int restoreRewindState(
            com.openggf.game.rewind.snapshot.ObjectManagerSnapshot.PlacementSnapshot snapshot) {
        active.clear();
        for (int index : snapshot.activeSpawnIndices()) {
            if (index >= 0 && index < spawns.size()) {
                active.add(spawns.get(index));
            }
        }

        remembered.clear();
        remembered.or(BitSet.valueOf(snapshot.rememberedBits()));
        stayActive.clear();
        stayActive.or(BitSet.valueOf(snapshot.stayActiveBits()));
        destroyedInWindow.clear();
        destroyedInWindow.or(BitSet.valueOf(snapshot.destroyedInWindowBits()));
        dormant.clear();
        dormant.or(BitSet.valueOf(snapshot.dormantBits()));
        pendingCursorLoad.clear();
        pendingCursorLoad.or(BitSet.valueOf(snapshot.pendingCursorLoadBits()));
        pendingCursorLoadOrder.clear();
        for (int index : snapshot.pendingCursorLoadOrder()) {
            if (index >= 0 && index < spawns.size() && pendingCursorLoad.get(index)) {
                pendingCursorLoadOrder.add(index);
            }
        }
        deferredVerticalLoad.clear();
        deferredVerticalLoad.or(BitSet.valueOf(snapshot.deferredVerticalLoadBits()));
        int restoredTwoAxisCameraYCoarse = snapshot.twoAxisCameraYCoarse();

        cursorIndex = snapshot.cursorIndex();
        lastCameraX = snapshot.lastCameraX();
        lastCameraChunk = snapshot.lastCameraChunk();
        counterBasedRespawn = snapshot.counterBasedRespawn();
        execThenLoadPlacement = snapshot.execThenLoadPlacement();
        permanentDestroyLatch = snapshot.permanentDestroyLatch();
        maxDynamicSlots = snapshot.maxDynamicSlots();
        lastScrollBackward = snapshot.lastScrollBackward();
        leftCursorIndex = snapshot.leftCursorIndex();
        fwdCounter = snapshot.fwdCounter();
        bwdCounter = snapshot.bwdCounter();
        Arrays.fill(objState, 0);
        for (int i = 0; i < snapshot.objState().length && i < objState.length; i++) {
            objState[i] = snapshot.objState()[i] & 0xFF;
        }

        spawnToCounter.clear();
        for (com.openggf.game.rewind.snapshot.ObjectManagerSnapshot.SpawnCounterEntry entry
                : snapshot.spawnCounters()) {
            int index = entry.spawnIndex();
            if (index >= 0 && index < spawns.size()) {
                spawnToCounter.put(spawns.get(index), entry.counter() & 0xFF);
            }
        }
        return restoredTwoAxisCameraYCoarse;
    }

    private byte[] compactObjState() {
        byte[] compact = new byte[objState.length];
        for (int i = 0; i < objState.length; i++) {
            compact[i] = (byte) objState[i];
        }
        return compact;
    }

    /** Replaces spawns and clears all tracking state. */
    void replaceSpawnsAndReset(List<ObjectSpawn> newSpawns) {
        replaceSpawns(newSpawns);
        remembered.clear();
        stayActive.clear();
        destroyedInWindow.clear();
        dormant.clear();
        pendingCursorLoad.clear();
        pendingCursorLoadOrder.clear();
        deferredVerticalLoad.clear();
        cursorIndex = 0;
        leftCursorIndex = 0;
        lastCameraX = Integer.MIN_VALUE;
        lastCameraChunk = Integer.MIN_VALUE;
        fwdCounter = 1;
        bwdCounter = 1;
        Arrays.fill(objState, 0);
        spawnToCounter.clear();
    }

    void reset(int cameraX) {
        reset(cameraX, null);
    }

    /**
     * Resets placement for a level (re)load, re-establishing {@code state}
     * before the initial-window scan runs.
     * <p>
     * The ordering is the point. On a round trip that sets
     * {@code Respawn_table_keep} the ROM never wipes
     * {@code Object_respawn_table} at all -- the wipe at
     * docs/skdisasm/sonic3k.asm:37429-37438 is skipped -- so the table is
     * already populated when {@code Load_Sprites} first scans the entry
     * window. Restoring after that scan instead would let a spawn the ROM
     * still has latched be re-created for the entry window.
     */
    void reset(int cameraX, PersistentRespawnState state) {
        active.clear();
        remembered.clear();
        stayActive.clear();
        destroyedInWindow.clear();
        dormant.clear();
        pendingCursorLoad.clear();
        pendingCursorLoadOrder.clear();
        deferredVerticalLoad.clear();
        spawnToCounter.clear();
        cursorIndex = 0;
        leftCursorIndex = 0;
        fwdCounter = 1;
        bwdCounter = 1;
        Arrays.fill(objState, 0);
        restorePersistentRespawn(state);

        if (counterBasedRespawn) {
            resetCounterBased(cameraX);
        } else {
            lastCameraX = cameraX;
            lastCameraChunk = toCoarseChunk(cameraX);
            refreshWindow(cameraX);
        }
    }

    /**
     * ROM: OPL_Main + first OPL_Next forward pass.
     * <p>
     * Positions both cursors and counters to match the ROM's initialization,
     * then performs the first-frame forward scan to load all objects in the
     * initial camera window.
     */
    private void resetCounterBased(int cameraX) {
        int cameraChunk = cameraX & CHUNK_MASK;
        // ROM: d6 = max(0, cameraX - 0x80) & 0xFF80
        int initD6 = Math.max(0, cameraX - 0x80) & CHUNK_MASK;

        // OPL_Main: Right cursor scan — skip past objects before initD6,
        // counting respawn-tracked entries to build fwdCounter.
        while (cursorIndex < spawns.size() && spawns.get(cursorIndex).x() < initD6) {
            if (spawns.get(cursorIndex).respawnTracked()) {
                fwdCounter = (fwdCounter + 1) & 0xFF;
            }
            cursorIndex++;
        }

        // OPL_Main: Left cursor scan — skip past objects before (initD6 - 0x80),
        // counting respawn-tracked entries to build bwdCounter.
        int leftD6 = initD6 - 0x80;
        if (leftD6 > 0) {
            while (leftCursorIndex < spawns.size()
                    && spawns.get(leftCursorIndex).x() < leftD6) {
                if (spawns.get(leftCursorIndex).respawnTracked()) {
                    bwdCounter = (bwdCounter + 1) & 0xFF;
                }
                leftCursorIndex++;
            }
        }

        // First OPL_Next forward scan: load objects from right cursor to
        // cameraChunk + getLoadAhead(), assigning counter values.
        int windowEnd = cameraChunk + getLoadAhead();
        while (cursorIndex < spawns.size()
                && spawns.get(cursorIndex).x() < windowEnd) {
            if (!spawnForwardEntry(cursorIndex)) {
                break;
            }
            cursorIndex++;
        }

        // First OPL_Next left cursor trim: advance to cameraChunk - 0x80.
        int leftTrimEdge = cameraChunk - 0x80;
        if (leftTrimEdge > 0) {
            while (leftCursorIndex < spawns.size()
                    && spawns.get(leftCursorIndex).x() < leftTrimEdge) {
                if (spawns.get(leftCursorIndex).respawnTracked()) {
                    bwdCounter = (bwdCounter + 1) & 0xFF;
                }
                leftCursorIndex++;
            }
        }

        lastCameraX = cameraX;
        lastCameraChunk = cameraChunk;
    }

    /**
     * ROM-accurate combined cursor advancement + inline instance creation.
     * Eliminates the 1-frame pipeline delay between cursor scan and instance creation.
     */
    void updateAndLoad(int cameraX, SpawnCallback callback) {
        this.inlineCallback = callback;
        try {
            update(cameraX);
        } finally {
            this.inlineCallback = null;
        }
    }

    void update(int cameraX) {
        if (spawns.isEmpty()) {
            return;
        }
        if (lastCameraX == Integer.MIN_VALUE) {
            reset(cameraX);
            return;
        }

        int cameraChunk = toCoarseChunk(cameraX);
        if (counterBasedRespawn
                && Math.abs((long) cameraX - lastCameraX) > (getLoadAhead() + UNLOAD_BEHIND)) {
            // Engine-specific catch-up for teleports/manual camera jumps.
            // The ROM only advances one 0x80 chunk at a time because camera
            // movement is continuous, but tests/editor flows can relocate the
            // camera by several chunks between frames. Rebuild the current
            // window immediately so the active set matches the jumped camera.
            lastScrollBackward = cameraX < lastCameraX;
            refreshCounterBased(cameraX);
            return;
        }
        if (cameraChunk == lastCameraChunk) {
            lastCameraX = cameraX;
            return;
        }

        if (counterBasedRespawn) {
            // S1 mode: two-cursor system with counter tracking.
            // ROM processes exactly one chunk step per frame via v_opl_screen.
            // Do not jump the cursor state directly to the current camera chunk.
            if (cameraChunk > lastCameraChunk) {
                lastScrollBackward = false;
                int oplChunk = Math.min(lastCameraChunk + CHUNK_STEP, cameraChunk);
                spawnForwardCountered(oplChunk);
                trimLeftCountered(oplChunk);
                lastCameraChunk = oplChunk;
            } else {
                lastScrollBackward = true;
                int oplChunk = Math.max(lastCameraChunk - CHUNK_STEP, cameraChunk);
                spawnBackwardCountered(oplChunk);
                trimRightCountered(oplChunk);
                lastCameraChunk = oplChunk;
            }
        } else {
            int delta = cameraX - lastCameraX;
            if (Math.abs(delta) > (getLoadAhead() + getUnloadBehind())) {
                lastScrollBackward = cameraX < lastCameraX;
                refreshWindow(cameraX);
            } else if (cameraChunk > lastCameraChunk) {
                lastScrollBackward = false;
                spawnForward(cameraX);
                trimLeftNonCounter(cameraX);
            } else if (cameraChunk < lastCameraChunk) {
                lastScrollBackward = true;
                spawnBackwardNonCounter(cameraX);
                trimRightNonCounter(cameraX);
            }
            // ROM parity: bit 7 of Object_respawn_table (sonic3k.asm
            // Touch_EnemyNormal line 20945; S2/S1 RememberState in
            // sub RememberState.asm) is set by spawn (loc_1BA40 et al.)
            // and cleared only by Sprite_OnScreen_Test family routines
            // when the OBJECT INSTANCE goes off-screen. Cursor advancement
            // and simple window-leave never clear it, so destroyed-by-
            // player badniks stay permanently absent for the rest of the
            // level (until level-init wipes Object_respawn_table at
            // sonic3k.asm loc_1B784). The dormant flag handles the
            // alive-offscreen case; destroyedInWindow stays latched.
            lastCameraChunk = cameraChunk;
        }

        lastCameraX = cameraX;
    }

    private void refreshCounterBased(int cameraX) {
        active.clear();
        dormant.clear();
        spawnToCounter.clear();
        cursorIndex = 0;
        leftCursorIndex = 0;
        fwdCounter = 1;
        bwdCounter = 1;
        Arrays.fill(objState, 0);

        int cameraChunk = cameraX & CHUNK_MASK;
        int initD6 = Math.max(0, cameraX - 0x80) & CHUNK_MASK;

        while (cursorIndex < spawns.size() && spawns.get(cursorIndex).x() < initD6) {
            if (spawns.get(cursorIndex).respawnTracked()) {
                fwdCounter = (fwdCounter + 1) & 0xFF;
            }
            cursorIndex++;
        }

        int leftD6 = initD6 - 0x80;
        if (leftD6 > 0) {
            while (leftCursorIndex < spawns.size()
                    && spawns.get(leftCursorIndex).x() < leftD6) {
                if (spawns.get(leftCursorIndex).respawnTracked()) {
                    bwdCounter = (bwdCounter + 1) & 0xFF;
                }
                leftCursorIndex++;
            }
        }

        int windowEnd = cameraChunk + getLoadAhead();
        while (cursorIndex < spawns.size()
                && spawns.get(cursorIndex).x() < windowEnd) {
            if (!spawnForwardEntry(cursorIndex)) {
                break;
            }
            cursorIndex++;
        }

        int leftTrimEdge = cameraChunk - UNLOAD_BEHIND;
        if (leftTrimEdge > 0) {
            while (leftCursorIndex < spawns.size()
                    && spawns.get(leftCursorIndex).x() < leftTrimEdge) {
                if (spawns.get(leftCursorIndex).respawnTracked()) {
                    bwdCounter = (bwdCounter + 1) & 0xFF;
                }
                leftCursorIndex++;
            }
        }

        // ROM parity: do NOT clear destroyedInWindow on window-leave (see update()).
        lastCameraX = cameraX;
        lastCameraChunk = cameraChunk;
    }

    public List<ObjectSpawn> getAllSpawns() {
        return spawns;
    }

    void markRemembered(ObjectSpawn spawn) {
        int index = getSpawnIndex(spawn);
        if (index < 0) {
            LOGGER.warning(() -> "markRemembered: spawn not found in placement list at ("
                    + spawn.x() + "," + spawn.y() + ") id=0x" + Integer.toHexString(spawn.objectId()));
            return;
        }
        if (!persistsDestruction(spawn)) {
            return;
        }
        remembered.set(index);
    }

    void markRemembered(ObjectSpawn spawn, ObjectInstance instance) {
        // Some objects (monitors, capsules) need to stay active to complete their
        // destruction/animation sequence even after being marked as remembered
        if (!instance.shouldStayActiveWhenRemembered()) {
            active.remove(spawn);
            clearCursorLoadState(spawn);
        }

        int index = getSpawnIndex(spawn);
        if (index < 0) {
            LOGGER.warning(() -> "markRemembered: spawn not found in placement list at ("
                    + spawn.x() + "," + spawn.y() + ") id=0x" + Integer.toHexString(spawn.objectId()));
            return;
        }
        if (!persistsDestruction(spawn)) {
            return;
        }
        remembered.set(index);
        if (instance.shouldStayActiveWhenRemembered()) {
            stayActive.set(index);
        }
    }

    /**
     * ROM parity: an object's destruction can only be remembered when its layout
     * entry owns a respawn-table slot.
     * <p>
     * S1 {@code ObjPosLoad} assigns {@code obRespawnNo} only when the layout id
     * byte has its remember bit set (bit 7); non-tracked entries keep
     * {@code obRespawnNo == 0} (docs/s1disasm/_incObj/ObjPosLoad.asm OPL_MakeItem,
     * {@code bpl.s .no_respawn_bit} -> {@code move.b d2,obRespawnNo}). When such an
     * object goes off-screen or is destroyed, {@code RememberState} sees
     * {@code obRespawnNo == 0} and simply branches to {@code DeleteObject} without
     * touching any respawn-table bit (docs/s1disasm/_incObj/sub RememberState.asm:16-21),
     * so the next ObjPosLoad cursor crossing re-creates a fresh copy. S2 mirrors
     * this with its yWord bit 15 respawn-tracked flag. Marking a non-respawn-tracked
     * spawn remembered would wrongly suppress that ROM re-creation (e.g. MZ3's
     * below-screen SmashBlock cluster, which the ROM reloads on the leftward
     * ObjPosLoad pass).
     */
    private boolean persistsDestruction(ObjectSpawn spawn) {
        // S3K Load_Sprites assigns every six-byte layout entry an a3 byte in
        // Object_respawn_table and always stores that address in respawn_addr
        // (sonic3k.asm:37513-37560,37741-37758). Unlike S1/S2, persistence is
        // not conditional on bit 15 of the layout Y word. The shared parser's
        // respawnTracked flag retains the S1/S2 encoding, so the two-axis S3K
        // placement mode must treat every real layout entry as tracked.
        return spawn != null && (twoAxisCursorPlacement || spawn.respawnTracked());
    }

    boolean isRemembered(ObjectSpawn spawn) {
        int index = getSpawnIndex(spawn);
        return index >= 0 && remembered.get(index);
    }

    boolean isStayActive(ObjectSpawn spawn) {
        int index = getSpawnIndex(spawn);
        return index >= 0 && stayActive.get(index);
    }

    /**
     * Clears the stayActive flag for a spawn. Called when a stayActive object
     * (e.g. EggPrison) self-destructs, so the remembered flag alone prevents respawn.
     */
    void clearStayActive(ObjectSpawn spawn) {
        int index = getSpawnIndex(spawn);
        if (index >= 0) {
            stayActive.clear(index);
        }
    }

    void clearRemembered() {
        remembered.clear();
    }

    /**
     * Removes a spawn from the active set.
     * <p>
     * When {@link #permanentDestroyLatch} is enabled (S3K), also latches
     * {@code destroyedInWindow} so the spawn can never re-spawn until
     * level reset. This mirrors ROM bit 7 of {@code Object_respawn_table}
     * (sonic3k.asm Touch_EnemyNormal line 20945; cursor helpers set the bit
     * on spawn at loc_1BA40 / loc_1BA64 and only the alive-offscreen
     * Sprite_OnScreen_Test family clears it -- a player kill leaves bit 7
     * set permanently because the badnik becomes Obj_Explosion which
     * never walks that path).
     * <p>
     * When the flag is disabled (S1 / S2), no latch is set: ROM's
     * ObjectsManager_Main only latches respawn-tracked spawns
     * (docs/s2disasm/s2.asm:33402 {@code tst.b 2(a0); bpl.s +}); the engine
     * models that opt-in via {@code remembered}.
     */
    void removeFromActive(ObjectSpawn spawn) {
        active.remove(spawn);
        clearCursorLoadState(spawn);
        if (permanentDestroyLatch) {
            int index = getSpawnIndex(spawn);
            if (index >= 0) {
                destroyedInWindow.set(index);
            }
        }
    }

    /**
     * Removes a spawn from the active set for normal out-of-range unloading.
     * <p>
     * Unlike {@link #removeFromActive}, does NOT set {@code destroyedInWindow}.
     * The object left naturally (ROM's out_of_range fired), so it can be
     * respawned when it comes back into the placement window.
     * <p>
     * ROM parity: When an object self-destructs via out_of_range, it calls
     * DeleteObject which zeroes the SST slot. If the object has RememberState,
     * it clears its objState bit before deleting. The cursor system's counters
     * are already correct (trimRightCountered/trimLeftCountered adjusted them).
     * The spawn just needs to be removed from the engine's active set so
     * future placement scans can re-create it.
     */
    void removeFromActiveForUnload(ObjectSpawn spawn) {
        active.remove(spawn);
        clearCursorLoadState(spawn);
    }

    /**
     * Removes a counter-based S1 spawn after an object-side {@code DeleteObject}
     * that deliberately leaves its respawn-table bit set.
     * <p>
     * ROM: ObjPosLoad's {@code OPL_SpawnObj} uses {@code bset #7,2(a2,d2.w)}
     * to test-and-set the counter slot. Objects such as Obj52 that delete
     * without {@code RememberState} leave that bit set, so later cursor scans
     * skip the entry instead of materializing a stale placement.
     */
    void removeFromActivePreservingCounterState(ObjectSpawn spawn) {
        active.remove(spawn);
        clearCursorLoadState(spawn);
    }

    /**
     * Marks a spawn as dormant after its instance was deleted via out_of_range.
     * The spawn stays in {@code active} but syncActiveSpawnsLoad will skip it.
     * <p>
     * The dormant bit is cleared when the placement cursor naturally re-processes
     * the spawn position:
     * <ul>
     *   <li>Counter-based (S1): cursor entry helpers clear dormant on forward/backward
     *       scan and trim (see spawnForwardEntry, spawnBackwardCountered,
     *       trimLeftCountered, trimRightCountered).</li>
     *   <li>Non-counter-based (S2/S3K): {@link #trySpawn(int)} clears dormant when
     *       the spawn is re-considered by spawnForward/refreshWindow/extendForPostCamera.
     *       This mirrors the ROM's MarkObjGone_P1 behavior of clearing bit 7 of
     *       Obj_respawn_data, allowing the next ObjPosLoad scan to re-create the object.</li>
     * </ul>
     */
    void markDormant(ObjectSpawn spawn) {
        int index = getSpawnIndex(spawn);
        if (index >= 0) {
            dormant.set(index);
        }
    }

    boolean isDormant(ObjectSpawn spawn) {
        int index = getSpawnIndex(spawn);
        return index >= 0 && dormant.get(index);
    }

    private void spawnForward(int cameraX) {
        int spawnLimit = getWindowEnd(cameraX);
        // ROM parity: ObjPosLoad forward scan uses `bls` (branch if lower or same)
        // on the comparison `cmp.w (a0), d6` where d6 = right_edge. This means
        // the loop continues when right_edge > object_x, i.e., object_x < right_edge.
        // Objects exactly AT the right boundary are NOT loaded — strict less-than.
        while (cursorIndex < spawns.size() && spawns.get(cursorIndex).x() < spawnLimit) {
            trySpawn(cursorIndex);
            cursorIndex++;
        }
    }

    private void trimLeftNonCounter(int cameraX) {
        int windowStart = getWindowStart(cameraX);
        int windowEnd = getWindowEnd(cameraX);
        // ROM parity: MarkObjGone_P1 (at $164A6) checks the object's CURRENT
        // x_pos(a0) — not its spawn position — against the camera window.
        // Moving objects (e.g. Buzzer flying 174 pixels west of its spawn)
        // survive as long as their current position is in range. The placement
        // cursor (spawnForward) still advances to track new spawns entering
        // from the right; removal from the active set is the instance's job
        // via its own out_of_range check during ExecuteObjects (see
        // runExecLoop's call to unloadObjectOutOfRange). Previously this
        // method removed spawns whose spawn.x() was out of window, which
        // prematurely killed moving badniks like Buzzer — their engine
        // lifecycle diverged from the ROM (unload/respawn cycling) while
        // the ROM instance stayed alive continuously.
        while (leftCursorIndex < cursorIndex
                && leftCursorIndex < spawns.size()
                && spawns.get(leftCursorIndex).x() < windowStart) {
            active.remove(spawns.get(leftCursorIndex));
            dormant.clear(leftCursorIndex);
            removePendingCursorLoad(leftCursorIndex);
            deferredVerticalLoad.clear(leftCursorIndex);
            leftCursorIndex++;
        }
        // ROM parity: do NOT clear destroyedInWindow here (see update()).
    }

    private void refreshWindow(int cameraX) {
        int windowStart = getWindowStart(cameraX);
        int windowEnd = getWindowEnd(cameraX);
        int start = lowerBound(windowStart);
        // ROM parity: objects exactly AT the right edge are excluded (strict <).
        // lowerBound(windowEnd) gives the first index where x >= windowEnd,
        // so indices [start, end) have windowStart <= x < windowEnd.
        int end = lowerBound(windowEnd);
        leftCursorIndex = start;
        cursorIndex = end;
        active.clear();
        pendingCursorLoad.clear();
        pendingCursorLoadOrder.clear();
        deferredVerticalLoad.clear();
        for (int i = start; i < end; i++) {
            trySpawn(i);
        }
        // ROM parity: do NOT clear destroyedInWindow on refresh (see update()).
    }

    private void spawnBackwardNonCounter(int cameraX) {
        int windowStart = getWindowStart(cameraX);
        while (leftCursorIndex > 0) {
            ObjectSpawn previous = spawns.get(leftCursorIndex - 1);
            // ROM loc_1B892 stops when the left edge is greater than or
            // equal to the previous object's X.
            if (windowStart >= previous.x()) {
                break;
            }
            leftCursorIndex--;
            trySpawn(leftCursorIndex);
        }
    }

    private void trimRightNonCounter(int cameraX) {
        int windowEnd = getWindowEnd(cameraX);
        while (cursorIndex > leftCursorIndex) {
            ObjectSpawn previous = spawns.get(cursorIndex - 1);
            // ROM loc_1B8BC retreats the front pointer while entries are
            // at or beyond the strict right edge.
            if (previous.x() < windowEnd) {
                break;
            }
            cursorIndex--;
            active.remove(previous);
            dormant.clear(cursorIndex);
            removePendingCursorLoad(cursorIndex);
            deferredVerticalLoad.clear(cursorIndex);
        }
        // ROM parity: do NOT clear destroyedInWindow here (see update()).
    }

    private void trySpawn(int index) {
        trySpawn(index, null);
    }

    private void trySpawn(int index, SpawnCallback callback) {
        ObjectSpawn spawn = spawns.get(index);
        // Clear dormant: this spawn is being re-considered by the non-counter
        // placement (spawnForward advancing the cursor, refreshWindow rebuilding
        // the active set, or extendForPostCamera extending to post-camera chunk).
        // ROM parity: S2/S3K's ObjectsManager_Main clears `bit #7 of Obj_respawn_data`
        // on unload (MarkObjGone_P1), so the next ObjPosLoad scan can re-create
        // the object. This mirrors the dormant.clear() calls in the counter-based
        // cursor entry helpers (spawnForwardEntry at line ~2541, spawnBackwardCountered
        // at line ~2614, trimLeftCountered at line ~2596, trimRightCountered at line ~2679).
        dormant.clear(index);
        if (remembered.get(index) && !stayActive.get(index)) {
            return;
        }
        // Don't respawn if destroyed while still in the window
        if (destroyedInWindow.get(index)) {
            return;
        }
        active.add(spawn);
        queueCursorLoad(index);
        if (callback != null) {
            boolean created = callback.tryCreate(spawn, -1);
            if (twoAxisCursorPlacement) {
                // Post-camera extension may see an S3K entry before it is
                // vertically loadable. ROM loc_1BA92 falls through without
                // setting the respawn bit or preserving X-pass priority; the
                // later loc_1B982 Y-camera pass scans the cursor-passed range
                // in object-list order (docs/skdisasm/sonic3k.asm:37723-37762).
                removePendingCursorLoad(index);
                if (created) {
                    deferredVerticalLoad.clear(index);
                } else {
                    deferredVerticalLoad.set(index);
                }
            }
        }
    }

    private void queueCursorLoad(int index) {
        if (!twoAxisCursorPlacement || pendingCursorLoad.get(index)) {
            return;
        }
        pendingCursorLoad.set(index);
        pendingCursorLoadOrder.add(index);
    }

    /**
     * Exposes the queued S3K X-cursor loads in queue order. Returns a reused
     * scratch list valid only until the next call: the sole consumer
     * ({@code ObjectManager.syncActiveSpawnsLoad}) iterates it synchronously
     * and must not retain the reference. Cursor ownership is retained until the
     * consumer reports a terminal construction outcome. In particular,
     * FindFreeObj failure must leave the entry pending for the next loader pass
     * instead of advancing past it permanently.
     */
    List<ObjectSpawn> pendingCursorLoadSpawns() {
        drainedCursorLoadScratch.clear();
        for (int i = 0; i < pendingCursorLoadOrder.size(); i++) {
            int index = pendingCursorLoadOrder.get(i);
            if (index >= 0 && index < spawns.size() && pendingCursorLoad.get(index)) {
                drainedCursorLoadScratch.add(spawns.get(index));
            }
        }
        return drainedCursorLoadScratch;
    }

    void completePendingCursorLoad(ObjectSpawn spawn) {
        if (spawn == null) {
            return;
        }
        int index = getSpawnIndex(spawn);
        if (index >= 0) {
            pendingCursorLoad.clear(index);
        }
    }

    void finishPendingCursorLoadBatch() {
        pendingCursorLoadOrder.removeIf(index -> index < 0
                || index >= spawns.size()
                || !pendingCursorLoad.get(index));
    }

    private void removePendingCursorLoad(int index) {
        pendingCursorLoad.clear(index);
        pendingCursorLoadOrder.removeIf(candidate -> candidate == index);
    }

    List<ObjectSpawn> getDeferredVerticalLoadSpawns() {
        ArrayList<ObjectSpawn> result = new ArrayList<>();
        for (int index = deferredVerticalLoad.nextSetBit(0);
             index >= 0;
             index = deferredVerticalLoad.nextSetBit(index + 1)) {
            if (index < spawns.size()) {
                result.add(spawns.get(index));
            }
        }
        return result;
    }

    void markDeferredVerticalLoad(ObjectSpawn spawn) {
        int index = getSpawnIndex(spawn);
        if (index >= 0) {
            deferredVerticalLoad.set(index);
        }
    }

    void clearDeferredVerticalLoad(ObjectSpawn spawn) {
        int index = getSpawnIndex(spawn);
        if (index >= 0) {
            deferredVerticalLoad.clear(index);
        }
    }

    private void clearCursorLoadState(ObjectSpawn spawn) {
        int index = getSpawnIndex(spawn);
        if (index >= 0) {
            removePendingCursorLoad(index);
            deferredVerticalLoad.clear(index);
        }
    }

    // ================================================================
    // S1 counter-based respawn methods
    // ================================================================

    /**
     * Forward scan with counters (ROM: loc_D9F6 forward path, loc_DA02 loop).
     * Advances the right cursor to cameraChunk + getLoadAhead(), spawning
     * new objects entering from the right.
     */
    private void spawnForwardCountered(int oplChunk) {
        int windowEnd = oplChunk + getLoadAhead();
        while (cursorIndex < spawns.size()
                && spawns.get(cursorIndex).x() < windowEnd) {
            if (!spawnForwardEntry(cursorIndex)) {
                break;
            }
            cursorIndex++;
        }
    }

    boolean isBetweenLoadCursors(ObjectSpawn spawn) {
        int index = getSpawnIndex(spawn);
        return index >= leftCursorIndex && index < cursorIndex;
    }

    /**
     * Helper: process one entry during forward scan.
     * ROM: assigns d2 = fwdCounter, then fwdCounter++ for respawn-tracked.
     */
    private boolean spawnForwardEntry(int index) {
        // Clear dormant: the cursor is re-scanning this position, matching
        // ROM behavior where ObjPosLoad re-processes the spawn entry.
        dormant.clear(index);
        ObjectSpawn spawn = spawns.get(index);
        if (spawn.respawnTracked()) {
            // ROM: OPL_MovedRight increments the forward respawn counter
            // before OPL_SpawnObj tests/skips the remembered object
            // (docs/s1disasm/s1disasm/_inc/ObjPosLoad.asm:195-203).
            int counter = fwdCounter & 0xFF;
            fwdCounter = (fwdCounter + 1) & 0xFF;
            return trySpawnCountered(index, counter);
        } else {
            // Non-tracked objects always spawn (ROM: loc_DA3C bpl → OPL_MakeItem)
            if (!(remembered.get(index) && !stayActive.get(index))
                    && !destroyedInWindow.get(index)) {
                active.add(spawn);
                if (inlineCallback != null) {
                    boolean created = inlineCallback.tryCreate(spawn, -1);
                    if (!created) {
                        active.remove(spawn);
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /**
     * Left cursor trim during forward movement (ROM: loc_DA24 loop).
     * Advances left cursor past objects that have left the window from
     * the left side, incrementing bwdCounter for respawn-tracked entries.
     * <p>
     * ROM parity: loc_DA24 ONLY advances the cursor and increments
     * bwdCounter. It does NOT destroy or unload the loaded objects.
     * Objects remain alive in the SST until they self-destruct via
     * their own out_of_range check in ExecuteObjects.
     * <p>
     * This is critical for moving objects (e.g. Batbrains) whose current
     * position (obX) differs from their spawn position. The engine's
     * cursor trim uses the spawn X, but the ROM's out_of_range uses the
     * object's current position. If a Batbrain has flown toward Sonic,
     * its current position may still be in range even though its spawn
     * position has passed the cursor boundary. Removing from active here
     * would prematurely unload the Batbrain.
     */
    private void trimLeftCountered(int oplChunk) {
        int leftEdge = oplChunk - UNLOAD_BEHIND;
        if (leftEdge <= 0) return;
        // ROM: `cmp.w (a0),d6; bls.s stop` → continues when leftEdge > entry.x
        while (leftCursorIndex < spawns.size()
                && spawns.get(leftCursorIndex).x() < leftEdge) {
            ObjectSpawn spawn = spawns.get(leftCursorIndex);
            if (spawn.respawnTracked()) {
                bwdCounter = (bwdCounter + 1) & 0xFF;
            }
            // ROM: loc_DA24 only advances cursor and bwdCounter.
            // Do NOT remove from active — objects stay alive until out_of_range.
            // ROM parity: destroyedInWindow models bit 7 of v_objstate /
            // objState[], which RememberState (sub RememberState.asm:14)
            // clears only via Sprite_OnScreen_Test on a LIVE object's
            // offscreen check. Cursor advancement past a destroyed badnik
            // must NOT clear the bit; ROM keeps it set permanently after
            // a player kill.
            // ROM: loc_DA24 (OPL_MovedRight's .loop_find_left) only walks the
            // left pointer forward; it never calls OPL_SpawnObj. An entry the
            // right-moving cursor leaves behind cannot be re-created until a
            // leftward pass (loc_D9A6) actually scans back over it, and that
            // path clears dormant itself. Clearing dormant here re-armed the
            // entry for engine-side materialization while both cursors were
            // already past it (SLZ1 f2522: a fresh Orbinaut for the 0xB90
            // layout entry appeared in the slot the 0xB90 staircase had just
            // freed, which ROM leaves empty).
            leftCursorIndex++;
        }
    }

    /**
     * Backward scan with counters (ROM: loc_D9A6 loop).
     * Retreats the left cursor to spawn objects entering the window
     * from the left as the camera scrolls backward.
     */
    private void spawnBackwardCountered(int oplChunk) {
        int leftEdge = oplChunk - UNLOAD_BEHIND;
        // ROM: `cmp.w -6(a0),d6; bge.s stop` → continues when leftEdge < prev.x
        while (leftCursorIndex > 0) {
            ObjectSpawn prev = spawns.get(leftCursorIndex - 1);
            if (leftEdge >= prev.x()) break;
            // Retreat cursor
            leftCursorIndex--;
            dormant.clear(leftCursorIndex); // cursor re-processing this entry
            if (prev.respawnTracked()) {
                bwdCounter = (bwdCounter - 1) & 0xFF;
                int counter = bwdCounter & 0xFF;
                boolean canContinue = trySpawnCountered(leftCursorIndex, counter);
                if (!canContinue) {
                    // ROM: loc_D9C6 — FindFreeObj failure undoes the cursor
                    // retreat and the counter decrement, then stops the scan.
                    // A bset/remember skip returns success from OPL_SpawnObj
                    // and continues; trySpawnCountered returns false only for
                    // the FindFreeObj-equivalent callback failure.
                    bwdCounter = (bwdCounter + 1) & 0xFF;
                    leftCursorIndex++;
                    break;
                }
            } else {
                if (!(remembered.get(leftCursorIndex) && !stayActive.get(leftCursorIndex))
                        && !destroyedInWindow.get(leftCursorIndex)) {
                    active.add(prev);
                    if (inlineCallback != null) {
                        boolean created = inlineCallback.tryCreate(prev, -1);
                        if (!created) {
                            active.remove(prev);
                            leftCursorIndex++;
                            break;
                        }
                    }
                }
            }
        }
    }

    /**
     * Right cursor trim during backward movement (ROM: loc_D9DE loop).
     * Retreats the right cursor past objects that have left the window
     * from the right side, decrementing fwdCounter for respawn-tracked.
     * <p>
     * ROM parity: loc_D9DE ONLY retreats the cursor and adjusts fwdCounter.
     * It does NOT destroy or unload the objects. The loaded ring/object
     * instances remain alive in the SST and continue executing during
     * ExecuteObjects until they self-destruct via their own out_of_range
     * check (e.g., Ring_Animate → out_of_range → Ring_Delete).
     * <p>
     * Previously, the engine removed spawns from the active set here,
     * which caused syncActiveSpawnsUnload to force-unload objects. This
     * freed their SST slots too early, causing downstream slot assignment
     * differences (e.g., Batbrain timing gates firing 1 frame early).
     * Objects are now kept active and unloaded via the separate
     * out_of_range check in syncActiveSpawnsUnload.
     */
    private void trimRightCountered(int oplChunk) {
        int rightEdge = oplChunk + getLoadAhead();
        // ROM: `cmp.w -6(a0),d6; bgt.s stop` → continues when rightEdge <= prev.x
        while (cursorIndex > 0) {
            ObjectSpawn prev = spawns.get(cursorIndex - 1);
            if (rightEdge > prev.x()) break;
            // Retreat cursor
            cursorIndex--;
            if (prev.respawnTracked()) {
                fwdCounter = (fwdCounter - 1) & 0xFF;
            }
            // ROM: loc_D9DE only retreats cursor and fwdCounter.
            // Do NOT remove from active — objects stay alive until out_of_range.
            // ROM parity: destroyedInWindow models bit 7 of v_objstate /
            // objState[], which RememberState clears only via
            // Sprite_OnScreen_Test on a LIVE object's offscreen check.
            // Cursor retreat past a destroyed badnik must NOT clear the
            // bit; ROM keeps it set permanently after a kill.
            // ROM: loc_D9DE (OPL_MovedLeft's .loop_find_right) only retreats the
            // right pointer; it never calls OPL_SpawnObj. Re-arming happens on
            // the next rightward scan (loc_DA02 -> spawnForwardEntry), which
            // clears dormant itself. See the note in trimLeftCountered.
        }
    }

    /**
     * Counter-aware spawn check (ROM: loc_DA3C with REV01 bset bug).
     * <p>
     * The ROM uses {@code bset #7,2(a2,d2.w)} which both TESTS and SETS
     * bit 7 in a single instruction. This means the first load sets the bit
     * as a side effect. If a later spawn attempt reuses the same counter
     * value (due to counter wrapping or camera backtracking through a
     * different object set), the bit is already set and the spawn is blocked.
     * <p>
     * This is the REV01 "remember sprite" bug documented at:
     * https://info.sonicretro.org/SCHG_How-to:Fix_a_remember_sprite_related_bug
     *
     * @param index   spawn list index
     * @param counter counter value (d2 register in ROM)
     * @return true if the object was added to the active set
     */
    private boolean trySpawnCountered(int index, int counter) {
        ObjectSpawn spawn = spawns.get(index);
        if (remembered.get(index) && !stayActive.get(index)) {
            return true;
        }
        // FLAG: FixBugs (docs/s1disasm/sonic.asm:20 -- 0 in the shipped ROM).
        // ENGINE IMPLEMENTS: the shipped (FixBugs = 0) branch of OPL_SpawnObj --
        // `bset #7,2(a2,d2.w)` TESTS AND SETS in one instruction, so a remembered
        // entry is marked the first time it is merely scanned, not when it is
        // actually destroyed. That is the "remember sprite" bug: such an entry
        // never reloads once the cursor has passed it. The paired FixBugs = 1
        // corrections (btst-only here, `bset` moved into OPL_MakeItem, and a
        // `subq.b #1,(a2)` counter roll-back when the scan loop overshoots) are
        // deliberately NOT implemented -- see docs/s1disasm/_inc/ObjPosLoad.asm:
        // 203-209, 260-266, 287-291.
        // ROM: bset #7,2(a2,d2.w) — test AND set bit 7
        boolean wasSet = (objState[counter & 0xFF] & 0x80) != 0;
        objState[counter & 0xFF] |= 0x80; // Side effect: always sets bit
        if (wasSet) {
            return true; // Bit was already set → skip, but scan continues
        }
        if (destroyedInWindow.get(index)) {
            return true;
        }
        spawnToCounter.put(spawn, counter & 0xFF);
        active.add(spawn);
        // ROM parity: inline instance creation during cursor scan.
        // This eliminates the 1-frame pipeline delay between cursor
        // advancement (placement.update) and instance creation (syncActiveSpawnsLoad).
        if (inlineCallback != null) {
            boolean created = inlineCallback.tryCreate(spawn, counter & 0xFF);
            if (!created) {
                active.remove(spawn);
                spawnToCounter.remove(spawn);
                return false;
            }
        }
        return true;
    }

    /**
     * Clears the objState bit for a normally-unloaded spawn.
     * ROM equivalent: RememberState's {@code bclr #7,2(a2,d0.w)}.
     * Called when an object leaves the camera window (not when destroyed).
     * The spawn-to-counter association is deliberately preserved while the
     * spawn remains between the placement cursors, so a same-window reload can
     * still write the correct obRespawnNo value.
     */
    void clearCounterForSpawn(ObjectSpawn spawn) {
        Integer counter = spawnToCounter.get(spawn);
        if (counter != null) {
            objState[counter] &= ~0x80;
        }
    }

    /**
     * Forgets the spawn-to-counter association without clearing bit 7.
     * ROM equivalent: object tails that call {@code DeleteObject} directly
     * instead of {@code RememberState}; their respawn-table bit remains set.
     */
    void forgetCounterForSpawn(ObjectSpawn spawn) {
        spawnToCounter.remove(spawn);
    }

    boolean isCounterStateBitSet(ObjectSpawn spawn, int bit) {
        Integer counter = spawnToCounter.get(spawn);
        if (counter == null || bit < 0 || bit > 7) {
            return false;
        }
        return (objState[counter] & (1 << bit)) != 0;
    }

    void setCounterStateBit(ObjectSpawn spawn, int bit) {
        Integer counter = spawnToCounter.get(spawn);
        if (counter != null && bit >= 0 && bit <= 7) {
            objState[counter] |= 1 << bit;
        }
    }

    /**
     * Returns the counter value assigned to a spawn during loading,
     * or -1 if not tracked. Used by ObjectManager to set respawnStateIndex.
     */
    int getCounterForSpawn(ObjectSpawn spawn) {
        Integer counter = spawnToCounter.get(spawn);
        return counter != null ? counter : -1;
    }

    boolean isCounterBasedRespawn() {
        return counterBasedRespawn;
    }

    boolean usesExecThenLoadPlacement() {
        return execThenLoadPlacement;
    }

    /**
     * ROM parity: true when the last chunk transition was leftward (backward).
     * Used by syncActiveSpawnsLoad to sort new spawns in descending X order,
     * matching ObjPosLoad/ObjectManager backward scan direction (a0 -= 6).
     */
    boolean isLastScrollBackward() {
        return lastScrollBackward;
    }

    // Diagnostic accessors for cursor state comparison
    int getCursorIndex() { return cursorIndex; }
    int getLeftCursorIndex() { return leftCursorIndex; }
    int getFwdCounter() { return fwdCounter; }
    int getBwdCounter() { return bwdCounter; }
    int getLastCameraChunk() { return lastCameraChunk; }

    private int toCoarseChunk(int cameraX) {
        return cameraX & CHUNK_MASK;
    }

    /**
     * Extend the active set for spawns visible with the post-camera position.
     * <p>
     * When the post-camera X is in a different chunk than the last processed
     * chunk, scans spawns in the gap exposed by the camera step and adds
     * eligible ones to the active set. Forward scans use the old/new right
     * edges; backward scans use the old/new left edges and descend through the
     * placement list, matching the ROM's right-to-left backward ObjPosLoad pass.
     * <p>
     * In counter mode, this is a full forward scan that advances the cursor
     * and updates lastCameraChunk/lastCameraX, because counter values must
     * not be assigned twice (the next frame's update would see the already-
     * updated cursor and skip re-processing).
     * <p>
     * In non-counter mode, cursor and lastCameraChunk are NOT updated,
     * preserving the primary placement pass's ability to process the chunk
     * boundary normally on the next frame.
     */
    void extendForPostCamera(int postCameraX, SpawnCallback callback) {
        if (counterBasedRespawn) {
            // ROM parity: S1's ObjPosLoad runs AFTER DeformLayers (camera).
            // Inline creation eliminates the one-frame pipeline delay
            // between cursor advancement and instance creation.
            updateAndLoad(postCameraX, callback);
            return;
        }
        extendForPostCamera(postCameraX, callback, false);
    }

    void extendForPostCamera(int postCameraX) {
        extendForPostCamera(postCameraX, null, true);
    }

    private void extendForPostCamera(int postCameraX, SpawnCallback callback, boolean legacyNoCreate) {
        if (counterBasedRespawn) {
            update(postCameraX);
            return;
        }
        int postChunk = toCoarseChunk(postCameraX);
        if (postChunk == lastCameraChunk) {
            return; // Camera didn't cross a chunk boundary
        }
        if (postChunk > lastCameraChunk) {
            int oldWindowEnd = getWindowEnd(lastCameraX);
            int newWindowEnd = postChunk + getLoadAhead();
            for (int i = cursorIndex; i < spawns.size(); i++) {
                int sx = spawns.get(i).x();
                if (sx >= newWindowEnd) {
                    break;
                }
                if (sx >= oldWindowEnd) {
                    if (legacyNoCreate) {
                        trySpawn(i);
                    } else {
                        trySpawn(i, callback);
                    }
                }
            }
        } else {
            int oldWindowStart = getWindowStart(lastCameraX);
            int newWindowStart = getWindowStart(postCameraX);
            for (int i = leftCursorIndex - 1; i >= 0; i--) {
                int sx = spawns.get(i).x();
                if (sx <= newWindowStart) {
                    break;
                }
                // The first backward pass stops on equality, so the catch-up
                // gap includes objects exactly on the previous left edge.
                if (sx <= oldWindowStart) {
                    if (legacyNoCreate) {
                        trySpawn(i);
                    } else {
                        trySpawn(i, callback);
                    }
                }
            }
        }
    }

    /**
     * Returns true if the given spawn index has the destroyedInWindow latch set.
     */
    boolean isDestroyedInWindow(int index) {
        return index >= 0 && destroyedInWindow.get(index);
    }


}

