package com.openggf.level.objects;

import com.openggf.game.session.EngineServices;
import static org.lwjgl.opengl.GL11.GL_LINES;
import com.openggf.camera.Camera;
import com.openggf.debug.DebugOverlayManager;
import com.openggf.debug.DebugOverlayToggle;
import com.openggf.game.CollisionModel;
import com.openggf.game.GameStateManager;
import com.openggf.game.PowerUpObject;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.game.rewind.identity.SpawnRefId;
import com.openggf.game.solid.ContactKind;
import com.openggf.level.objects.boss.BossChildComponent;
import com.openggf.game.solid.ObjectSolidExecutionContext;
import com.openggf.game.solid.PlayerSolidContactResult;
import com.openggf.game.solid.PlayerStandingState;
import com.openggf.game.solid.PostContactState;
import com.openggf.game.solid.PreContactState;
import com.openggf.game.solid.SolidCheckpointBatch;
import com.openggf.game.solid.SolidExecutionRegistry;
import com.openggf.game.session.SessionManager;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.GLCommandGroup;
import com.openggf.graphics.FadeManager;
import com.openggf.graphics.GraphicsManager;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.LevelManager;
import com.openggf.level.ParallaxManager;
import com.openggf.level.WaterSystem;
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

import java.lang.reflect.Modifier;
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
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Logger;

public class ObjectManager {
    private static final int BUCKET_COUNT = RenderPriority.MAX - RenderPriority.MIN + 1;
    static final int ANIM_ROLL = 0x02;
    static final int ANIM_SPINDASH = 0x09;
    private final ObjectPlacementController placement;
    private final ObjectRegistry registry;
    private final GraphicsManager graphicsManager;
    private final Camera camera;
    private final Map<ObjectSpawn, ObjectInstance> activeObjects = new IdentityHashMap<>();
    private final Map<ObjectInstance, ObjectSpawn> instanceToSpawn = new IdentityHashMap<>();
    private final List<ObjectInstance> dynamicObjects = new ArrayList<>();
    private final DynamicObjectOwnership dynamicOwnership = new DynamicObjectOwnership();
    private final List<ObjectInstance> dynamicFallbackScratch = new ArrayList<>();
    private final List<ObjectInstance> activeFallbackScratch = new ArrayList<>();
    // Per-frame scratch collections reused to avoid steady-state allocation.
    // Ownership stays inside the populating method (cleared in finally / at the
    // single reset point); never returned to callers that retain references.
    private final Set<ObjectInstance> processedInExecLoopScratch =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private final BitSet slotsFreedDuringObjectPass = new BitSet();
    private final List<PlayableEntity> activePlayersScratch = new ArrayList<>(4);
    private final List<ObjectSpawn> newSpawnsScratch = new ArrayList<>();
    private final List<ObjectInstance> postPlayerHooksScratch = new ArrayList<>();
    // Cached spawn-order comparators (built lazily; placement is constructor-set).
    private Comparator<ObjectSpawn> forwardSpawnOrder;
    private Comparator<ObjectSpawn> backwardSpawnOrder;
    private final List<GLCommand> renderCommands = new ArrayList<>();
    private int frameCounter;
    /**
     * Models the ROM's V-int run counter -- {@code Vint_runcount} in Sonic 2
     * ({@code docs/s2disasm/s2.asm:508}), {@code v_vblank_count} in Sonic 1
     * ({@code docs/s1disasm/sonic.asm:682}) and {@code V_int_run_count} in
     * Sonic 3&amp;K ({@code docs/skdisasm/sonic3k.asm:543}). In all three the
     * increment sits at V-int exit and runs once per serviced V-blank
     * regardless of which V-int routine the mode jump table dispatched.
     *
     * <p><b>Invariant: exactly one tick per serviced V-blank.</b> The
     * gameplay row ticks it inside {@link #update} via
     * {@link #advanceVblaCounter()}; every row where the level loop did not
     * run but the V-int was still serviced (lag skip, bonus-stage lag,
     * bonus-exit fade hold, title-card overlay, seamless-reload transition,
     * trace VBLANK_ONLY / PLAYABLE_ANIMATION_ONLY) must call
     * {@link #advanceVblaCounter()} exactly once and must not also route
     * through {@link #update}. A new V-blank-only path that forgets the call
     * de-phases every consumer of {@link #vblaCounter()} -- it is handed to
     * every object instance each frame, and feeds spilled-ring floor probes,
     * S3K bonus-stage RNG seeding and rewind snapshots.
     *
     * <p>Known deliberate divergences: PAUSE rows and seamless-boundary LAG
     * rows service the V-int in the ROM but do not tick here. See
     * {@code docs/status/known-discrepancies.md}.
     */
    private int vblaCounter;
    private boolean updating;
    private final InitialObjectDispatchController initialDispatch =
            new InitialObjectDispatchController(this);
    private final ObjectExecutionController execution = new ObjectExecutionController(this);

    // ROM parity: slot-ordered execution array for ExecuteObjects emulation.
    // The dynamic slot window is game-specific and comes from ObjectRegistry.
    // When a child is spawned at a higher slot, it's placed here directly
    // so the ongoing loop reaches it naturally (same-frame execution).
    private final ObjectSlotLayout slotLayout;
    /**
     * Per-game object load/unload windowing boundary (shared abstraction;
     * {@link ObjectWindowingStrategy#LEGACY} for S1/S3K, the ROM-exact S2
     * strategy for S2). Injected from the {@link ObjectRegistry} so this shared
     * manager never depends on a game-specific package.
     */
    private final ObjectWindowingStrategy windowingStrategy;
    private final ObjectInstance[] execOrder;
    /**
     * Parallel to {@link #execOrder}, but keyed by an object's OWN SST slot rather
     * than the slot it executes from. Only objects that borrow a different
     * execution slot appear here; see the retirement note in
     * {@link #updateCounterBasedExecThenLoad}.
     */
    private final ObjectInstance[] ownSlotRetireOrder;
    /** Child slots whose release is deferred to their own position in the ascending exec walk. */
    private final java.util.BitSet pendingChildSlotRelease;
    private final int[] playerCentreXAtSlotStart;
    private final int[] playerCentreYAtSlotStart;
    private final boolean[] playerCentreAtSlotStartValid;
    private int currentExecSlot = -1; // -1 when not in update loop

    /** ROM object-loop counter for the current pass; see ObjectLoopSlotBudget. */
    private final ObjectLoopSlotBudget objectLoopBudget = new ObjectLoopSlotBudget();

    /**
     * Rewrites the remaining length of this frame's object walk from the calling
     * object's own slot, modelling a ROM routine that writes the loop counter
     * register. {@code remainingSlots} is the ROM's own number; callers cite
     * where it comes from and this method invents nothing.
     */
    public void overrideRemainingObjectLoopSlots(ObjectInstance source, int remainingSlots) {
        if (source instanceof AbstractObjectInstance aoi) objectLoopBudget.overrideFrom(
                executionSlotIndex(aoi), remainingSlots);
    }

    /**
     * Whether this frame's walk got past the managed dynamic window and so would
     * have reached the fixed-in-level slots the ROM places straight after it --
     * S2 {@code LevelOnly_Object_RAM} (docs/s2disasm/s2.constants.asm:1145-1150),
     * S3K {@code Level_object_RAM}.
     */
    public boolean objectLoopReachedFixedInLevelSlots() {
        return objectLoopBudget.reaches(slotLayout.lastProcessSlotExclusive());
    }
    private final boolean skipVerticalSpawnLoadFilterForGame;

    private final ObjectServices objectServices;

    // Pre-bucketed lists for O(n) rendering instead of O(n*buckets)
    @SuppressWarnings("unchecked")
    private final List<ObjectInstance>[] lowPriorityBuckets = new ArrayList[BUCKET_COUNT];
    @SuppressWarnings("unchecked")
    private final List<ObjectInstance>[] highPriorityBuckets = new ArrayList[BUCKET_COUNT];
    private boolean bucketsDirty = true;
    private final ObjectRenderBucketSnapshot renderBucketSnapshot =
            new ObjectRenderBucketSnapshot();

    // Cached combined active objects list to avoid allocation in getActiveObjects()
    private final List<ObjectInstance> cachedActiveObjects = new ArrayList<>();
    private final List<ObjectInstance> cachedSolidProviderObjects = new ArrayList<>();
    private final List<ObjectInstance> cachedTouchResponseObjects = new ArrayList<>();
    private final ObjectCollisionResponseList collisionResponseList = new ObjectCollisionResponseList();
    private boolean activeObjectsCacheDirty = true;
    private final Set<ObjectInstance> deferredDynamicExecThisFrame =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private final ObjectManagerRuntimeState runtimeState = new ObjectManagerRuntimeState();

    // ROM parity: dynamic object slot tracking for the current game's allocatable
    // SST window. S1 uses 32..127, S2 uses 16..127, and S3K uses 4..92.
    // Occupancy/allocation authority; ObjectManager retains execOrder + objectIdInSlot
    // as the slot->occupant identity authority.
    private final SlotAllocator slotAllocator;
    private int s2LatchedObjectManagerCameraX = Integer.MIN_VALUE;
    private int twoAxisCameraYCoarse = Integer.MIN_VALUE;
    private int vIntRunCounterPhaseOffset;

    // ROM parity: Tracks child slots reserved by objects with getReservedChildSlotCount() > 0.
    // In S1, ring objects (obj25) allocate child ring slots via FindFreeObj. These slots
    // must be occupied to match the ROM's SST layout and give subsequent objects correct
    // slot numbers (affecting timing gates like (v_vbla_byte + d7) & 7).
    private final Map<ObjectSpawn, int[]> reservedChildSlots = new IdentityHashMap<>();

    // Rewind: captured DynamicObjectEntry payloads for player-bound dynamics
    // (Shield, Stars) that are NOT recreated by generic dynamic restore. The
    // post-restore callback in
    // AbstractPlayableSprite#refreshPowerUpObjectsAfterRewindRestore relinks a
    // live matching shield when one exists, otherwise re-spawns via the power-up
    // spawner. The spawner consumes the captured entry via
    // {@link #consumePendingPlayerBoundEntry(Class)} so the new instance lands
    // at the same slot the reference run had AND has the captured field surface
    // restored on top of its fresh-construction state. Without that restore,
    // animation cursors and similar non-construction-set scalars would reset to
    // zero on every rewind.
    private final Map<Class<?>, java.util.ArrayDeque<
            com.openggf.game.rewind.snapshot.ObjectManagerSnapshot.DynamicObjectEntry>>
            pendingPlayerBoundEntries = new java.util.HashMap<>();

    // Rewind: in-place restore support. Matching live instances are reused by the
    // snapshot restore instead of destroy/recreate when the class passes the
    // non-captured-field audit (see isRewindInPlaceReuseSafeClass). The scratch map
    // indexes live instances by spawn identity for one restore pass; the
    // side-effect map latches classes whose constructors spawn children / reserve
    // child slots during an in-restore recreate (observed per session) onto the
    // recreate path, because reuse would skip those construction side effects.
    private boolean rewindInPlaceRestoreEnabled = true;
    private final Map<ObjectSpawn, ObjectInstance> rewindRestoreReuseScratch = new IdentityHashMap<>();
    private final Map<Class<?>, Boolean> rewindRestoreConstructionSideEffects = new HashMap<>();

    // Rewind: monotonically-increasing counter assigned once per object when it is
    // added to the live object set (activeObjects or dynamicObjects). Combined with
    // the spawn's layoutIndex it forms a stable ObjectRefId via ObjectRefId.forObject()
    // that survives capture→restore→re-simulation cycles because re-simulation adds
    // objects in the same order, re-minting the same ids. Captured in the snapshot
    // and restored so the counter never accidentally aliases a pre-restore id.
    private int dynamicObjectIdCounter = 0;
    // Per-object id registry: maps every live ObjectInstance to its assigned ObjectRefId
    // so capture can build the identity table without re-scanning or re-allocating ids.
    // Cleared on reset() and pruned when objects are removed.
    private final IdentityHashMap<ObjectInstance, ObjectRefId> rewindObjectIds = new IdentityHashMap<>();

    // Rewind: construction-spawned boss/object children produced while an active object is
    // reconstructed during restore. The reconstructed parent wires its back-references
    // (childComponents, named child fields) to THESE instances; the step-4 dynamic-object
    // reconciliation loop then adopts each one in place — registering it at its captured slot
    // and applying its EXACT captured state — instead of recreating a duplicate via generic restore.
    // This gives exact-state fidelity for construction children while keeping the parent's
    // back-references valid (they point at the very instances the loop restores onto), and
    // avoids the double-spawn that generic recreate would cause. Cleared at the start and end
    // of each restore so it never leaks instances across passes.
    private final List<AbstractObjectInstance> rewindReconstructionChildren = new ArrayList<>();
    private boolean rewindReconstructionChildCapture;
    private final FixedSstObjectInstaller fixedSstObjects;
    private final PlaneSwitchers planeSwitchers;
    private final ObjectSolidContactController solidContacts;
    private final ObjectTouchResponseController touchResponses;

    private static final Comparator<ObjectInstance> RENDER_SLOT_DESCENDING = (a, b) -> {
        int slotA = a instanceof AbstractObjectInstance aoiA ? aoiA.getSlotIndex() : Integer.MAX_VALUE;
        int slotB = b instanceof AbstractObjectInstance aoiB ? aoiB.getSlotIndex() : Integer.MAX_VALUE;
        return Integer.compare(slotB, slotA);
    };

    public ObjectManager(List<ObjectSpawn> spawns, ObjectRegistry registry,
            int planeSwitcherObjectId, PlaneSwitcherConfig planeSwitcherConfig,
            TouchResponseTable touchResponseTable, GraphicsManager graphicsManager,
            Camera camera, ObjectServices objectServices) {
        this.registry = registry;
        this.graphicsManager = graphicsManager;
        this.camera = camera;
        this.objectServices = objectServices;
        this.slotLayout = registry != null ? registry.objectSlotLayout() : ObjectSlotLayout.SONIC_1;
        this.windowingStrategy = registry != null
                ? registry.objectWindowingStrategy()
                : ObjectWindowingStrategy.LEGACY;
        this.placement = new ObjectPlacementController(spawns,
                camera != null ? camera::getWidth : com.openggf.level.spawn.PlacementViewportWidth::current);
        this.placement.setTwoAxisCursorPlacement(slotLayout.twoAxisCursorPlacement());
        this.placement.setWindowingStrategy(windowingStrategy);
        this.execOrder = new ObjectInstance[slotLayout.dynamicSlotCount()];
        this.ownSlotRetireOrder = new ObjectInstance[slotLayout.dynamicSlotCount()];
        this.pendingChildSlotRelease = new java.util.BitSet(slotLayout.dynamicSlotCount());
        this.playerCentreXAtSlotStart = new int[execOrder.length];
        this.playerCentreYAtSlotStart = new int[execOrder.length];
        this.playerCentreAtSlotStartValid = new boolean[execOrder.length];
        this.slotAllocator = new SlotAllocator(slotLayout,
                slotLayout.twoAxisCursorPlacement()
                        ? SlotEmptyPredicate.ROUTINE_POINTER
                        : SlotEmptyPredicate.ID_BYTE);
        this.fixedSstObjects = new FixedSstObjectInstaller(
                registry, objectServices, slotLayout, slotAllocator,
                this::addDynamicObject, this::releaseSlot);
        this.planeSwitchers = planeSwitcherConfig != null
                ? new PlaneSwitchers(placement, planeSwitcherObjectId, planeSwitcherConfig)
                : null;
        this.solidContacts = new ObjectSolidContactController(this);
        this.touchResponses = touchResponseTable != null
                ? new ObjectTouchResponseController(this, touchResponseTable)
                : null;
        this.skipVerticalSpawnLoadFilterForGame = slotLayout == ObjectSlotLayout.SONIC_2;
        // Initialize bucket arrays
        for (int i = 0; i < BUCKET_COUNT; i++) {
            lowPriorityBuckets[i] = new ArrayList<>();
            highPriorityBuckets[i] = new ArrayList<>();
        }
    }

    public ObjectManager(List<ObjectSpawn> spawns, ObjectRegistry registry,
            int planeSwitcherObjectId, PlaneSwitcherConfig planeSwitcherConfig,
            TouchResponseTable touchResponseTable) {
        this(spawns, registry, planeSwitcherObjectId, planeSwitcherConfig, touchResponseTable,
                defaultServices());
    }

    private ObjectManager(List<ObjectSpawn> spawns, ObjectRegistry registry,
            int planeSwitcherObjectId, PlaneSwitcherConfig planeSwitcherConfig,
            TouchResponseTable touchResponseTable, ObjectServices services) {
        this(spawns, registry, planeSwitcherObjectId, planeSwitcherConfig, touchResponseTable,
                services.graphicsManager(),
                services.camera(),
                services);
    }

    private static ObjectServices defaultServices() {
        var gameplayMode = SessionManager.getCurrentGameplayMode();
        if (gameplayMode != null) {
            return new DefaultObjectServices(gameplayMode, EngineServices.current());
        }
        return new BootstrapObjectServices();
    }

    private boolean isManagedDynamicSlot(int slotIndex) {
        return slotLayout.isDynamicSlot(slotIndex);
    }

    private int execIndexForSlot(int slotIndex) {
        return slotLayout.toExecIndex(slotIndex);
    }

    private int executionSlotIndex(AbstractObjectInstance instance) {
        return instance.getExecutionSlotIndex();
    }

    private int slotIndexForExec(int execIndex) {
        return slotLayout.toSlotIndex(execIndex);
    }

    /**
     * Records an object that runs from a slot other than the one it owns, so its
     * {@code out_of_range} retirement can still be evaluated at its OWN slot in
     * the ascending {@code ExecuteObjects} walk.
     */
    private void indexOwnSlotRetirement(AbstractObjectInstance aoi) {
        int ownSlot = aoi.getSlotIndex();
        if (ownSlot == executionSlotIndex(aoi) || !isManagedDynamicSlot(ownSlot)) {
            return;
        }
        ownSlotRetireOrder[execIndexForSlot(ownSlot)] = aoi;
    }

    /**
     * ROM {@code ExecuteObjects} walks the SST in ascending slot order and each
     * object's {@code out_of_range ...,DeleteObject} tail therefore frees THAT
     * object's slot at THAT slot's position in the walk
     * (docs/s1disasm/_inc/ExecuteObjects.asm:10-30).
     *
     * <p>A few engine objects consolidate a ROM parent plus its children into one
     * instance and borrow a reserved child slot as their execution slot to get
     * the ROM's solid-pass ordering right (S1 {@code Sonic1StaircaseObjectInstance},
     * whose blocks share the {@code Staircase} entry that runs
     * {@code out_of_range.w DeleteObject,stair_origX(a0)} for every block,
     * docs/s1disasm/_incObj/"5B SLZ Staircase.asm":6-12,39-66). Retiring such an
     * object only at the borrowed slot moved the ROM parent block's slot release
     * far later in the walk, so lowest-free {@code FindFreeObj} allocations made
     * between the two positions — notably {@code Ring_Main}'s per-ring children
     * (docs/s1disasm/_incObj/"25, 37 Rings.asm":251) — saw the parent slot as
     * still occupied and skipped it.
     *
     * <p>The retirement anchor is the object's own slot; where it runs is
     * unchanged. This is safe for the same reason ROM's result is
     * position-independent here: these objects feed {@code out_of_range} a fixed
     * origin ({@code stair_origX}) that their routine never writes, so the check
     * yields the same answer either side of the routine.
     *
     * @return {@code true} if an object was retired at this slot
     */
    private boolean retireBorrowedExecutionSlotOwnerAtOwnSlot(int cameraX) {
        ObjectInstance owner = ownSlotRetireOrder[currentExecSlot];
        if (owner == null) {
            return false;
        }
        ownSlotRetireOrder[currentExecSlot] = null;
        if (!(owner instanceof AbstractObjectInstance aoi) || owner.isDestroyed()) {
            return false;
        }
        int execSlot = executionSlotIndex(aoi);
        if (!isManagedDynamicSlot(execSlot)) {
            return false;
        }
        int execIndex = execIndexForSlot(execSlot);
        if (execOrder[execIndex] != owner) {
            return false;
        }
        ObjectSpawn spawn = instanceToSpawn.get(owner);
        if (!unloadCounterBasedOutOfRange(owner, spawn, execSlot, cameraX)) {
            return false;
        }
        execOrder[execIndex] = null;
        return true;
    }

    public void reset(int cameraX) { reset(cameraX, null); }

    /**
     * Resets this manager and reapplies a persistent respawn table before the
     * initial placement window is materialized. Bonus-stage returns use this
     * atomic path so remembered layout objects cannot be created during reset
     * and left live by a later bit-only restore.
     */
    public void reset(int cameraX, PersistentRespawnState persistentRespawnState) {
        clearActiveObjects();
        dynamicObjects.clear();
        dynamicOwnership.clear();
        deferredDynamicExecThisFrame.clear();
        runtimeState.clearPostExecDynamicSpawns();
        reservedChildSlots.clear();
        slotAllocator.clear();
        Arrays.fill(execOrder, null);
        Arrays.fill(playerCentreAtSlotStartValid, false);
        cachedActiveObjects.clear();
        activeObjectsCacheDirty = true;
        bucketsDirty = true;
        frameCounter = 0;
        dynamicObjectIdCounter = 0;
        rewindObjectIds.clear();
        s2LatchedObjectManagerCameraX = cameraX;
        twoAxisCameraYCoarse = Integer.MIN_VALUE;
        placement.reset(cameraX, persistentRespawnState);
        if (registry != null) {
            registry.reportCoverage(placement.getAllSpawns());
        }
        if (planeSwitchers != null) {
            planeSwitchers.reset();
        }
        solidContacts.reset();
        if (touchResponses != null) {
            touchResponses.reset();
        }
        fixedSstObjects.installConfiguredObjects();
        // Materialize the current ObjectPlacementController window immediately after reset.
        // S1 needs this for ROM parity at level start; for S2/S3K it keeps
        // manual camera resets and headless probes from sitting on an empty
        // active window until a later ObjectPlacementController delta occurs. The
        // camera-Y filter is S3K-only (S2's loader has none at all, see
        // isSpawnVerticallyEligibleForLoad), so it applies here for S3K only.
        syncActiveSpawnsLoad(false);
    }

    ObjectServices services() {
        return objectServices;
    }

    /** The services this manager injects into every object it owns. */
    public ObjectServices getObjectServices() {
        return objectServices;
    }

    public boolean usesTwoAxisCursorPlacement() {
        return slotLayout.twoAxisCursorPlacement();
    }

    public boolean usesCounterBasedRespawn() {
        return placement.isCounterBasedRespawn();
    }

    /**
     * Captures the persistent respawn-remember state (broken/collected/remembered
     * spawns) to carry across a bonus-stage or special-stage round-trip reload. See
     * {@link PersistentRespawnState} for the ROM {@code Respawn_table_keep} model.
     */
    public PersistentRespawnState capturePersistentRespawn() {
        return placement.capturePersistentRespawn();
    }

    /**
     * Re-establishes respawn-remember state captured by
     * {@link #capturePersistentRespawn()} onto this (freshly reloaded) manager's
     * placement, so stage-return respawns broken/collected objects in the state
     * the player left them (e.g. a monitor broken before the bonus stays broken).
     */
    public void restorePersistentRespawn(PersistentRespawnState state) {
        placement.restorePersistentRespawn(state);
    }

    /**
     * Instantiates all spawns currently in the ObjectPlacementController window. Intended for
     * trace replay and editor state restoration that need engine object
     * instances to exist before the first {@link #update} call, so external
     * state (ROM SST snapshots, editor bookmarks) can be hydrated onto them.
     *
     * <p>For S1 (counter-based respawn, {@code UNIFIED} collision model) this
     * work is already done inside {@link #reset(int)}. For S2/S3K
     * ({@code DUAL_PATH}) spawn→instance conversion is normally deferred until
     * the first {@link #update} tick; calling this method brings S2/S3K in
     * line with that ROM behavior ahead of time.
     *
     * <p>Idempotent: objects that already exist are not re-created.
     */
    public void preloadInitialSpawnsForHydration() {
        syncActiveSpawnsLoad(skipVerticalSpawnLoadFilterForGame);
    }

    /**
     * Replaces the spawn list with a new one from the editor.
     * Clears remembered/destroyed state so edited spawns can respawn.
     * Existing active objects are cleared — {@code syncActiveSpawns()} on
     * the next frame will re-instantiate objects in the camera window.
     */
    public void resyncSpawnList(List<ObjectSpawn> newSpawns) {
        clearActiveObjects();
        cachedActiveObjects.clear();
        activeObjectsCacheDirty = true;
        bucketsDirty = true;
        twoAxisCameraYCoarse = Integer.MIN_VALUE;
        placement.replaceSpawnsAndReset(newSpawns);
    }

    void resetTouchResponses() {
        if (touchResponses != null) {
            touchResponses.reset();
        }
    }

    /**
     * Forces cached render buckets to rebuild on the next draw.
     *
     * Object priority can change during update (or by following another entity's
     * priority), so add/remove-based invalidation alone is not sufficient.
     */
    public void invalidateRenderBuckets() {
        bucketsDirty = true;
    }

    /**
     * Marks the cached render buckets dirty only when their inputs actually
     * changed since the last rebuild. Object priority is exposed through
     * virtual {@link ObjectInstance#isHighPriority()} /
     * {@link ObjectInstance#getPriorityBucket()} implementations (many follow
     * other entities' state live), so there is no central mutation hook; this
     * cheap once-per-frame scan replaces the previous unconditional per-frame
     * rebuild while staying immune to untracked priority changes.
     */
    public void refreshRenderBucketsIfChanged() {
        if (bucketsDirty) {
            return;
        }
        if (renderBucketSnapshot.inputsChanged(activeObjects.values(), dynamicObjects)) {
            bucketsDirty = true;
        }
    }

    public void update(int cameraX, PlayableEntity player, List<? extends PlayableEntity> sidekicks, int touchFrameCounter) {
        update(cameraX, player, sidekicks, touchFrameCounter, true);
    }

    /**
     * Run touch responses for a single player outside the main update loop.
     * ROM order: ReactToItem runs during each player's slot within ExecuteObjects,
     * after their physics but before other objects' solid checks.
     */
    public void runTouchResponsesForPlayer(PlayableEntity player, int touchFrameCounter) {
        runTouchResponsesForPlayer(player, touchFrameCounter, false);
    }

    /**
     * Runs the inline player-slot touch pass against the frame-start snapshot.
     * ROM order: player slots scan before later level-object slots update.
     */
    public void runTouchResponsesForPlayer(PlayableEntity player, int touchFrameCounter,
                                           boolean usePreUpdateState) {
        if (touchResponses == null) {
            return;
        }
        touchResponses.getDebugState().setEnabled(
                objectServices.debugOverlay().isEnabled(DebugOverlayToggle.TOUCH_RESPONSE));
        // CPU sidekick uses separate overlap tracking and Hurt_Sidekick handling.
        if (player.isCpuControlled()) {
            touchResponses.updateSidekick(player, touchFrameCounter, usePreUpdateState);
        } else {
            touchResponses.update(player, touchFrameCounter, usePreUpdateState);
        }
    }

    /**
     * Refreshes frame-start object/camera state for inline-order touch checks.
     * S3K can instead preserve the previous dynamic Collision_response_list
     * snapshot because player slots run before that list is rebuilt.
     */
    public void snapshotTouchResponseState() { snapshotTouchResponseState(false); }

    public void snapshotTouchResponseState(boolean preservePreviousCollisionResponseList) {
        solidContacts.clearSameFrameMonitorBreakBounces();
        updateCameraBounds();
        collisionResponseList.setUsePrevious(preservePreviousCollisionResponseList);
        for (ObjectInstance inst : activeObjects.values()) {
            refreshTouchResponseSnapshot(inst);
        }
        for (ObjectInstance inst : dynamicObjects) {
            refreshTouchResponseSnapshot(inst);
        }
    }

    private void refreshTouchResponseSnapshot(ObjectInstance inst) {
        // ROM Touch_Loop stores object RAM POINTERS, not snapshots: `movea.w (a4)+,a1`
        // then `x_pos(a1)` (docs/skdisasm/sonic3k.asm:20660-20663, 20674-20681; the S1
        // ReactToItem and S2 Touch_Response forms are the same shape). Every placed
        // object sits in a slot after the player, so the player always sees each
        // object's END-OF-PREVIOUS-FRAME position.
        //
        // This runs from LevelManager.prepareTouchResponseSnapshots before any object
        // updates this frame, so the snapshot captures exactly that position for every
        // object, whatever phase its own update falls in. The S3K previous-list path
        // used to skip it, leaving the cache holding whatever the object's own last
        // update wrote -- still end-of-previous-frame for an object updated before the
        // touch pass, but one pass older for a spawnChild-created object updated after
        // it. Collision flags are unaffected: the previous-list path reads them live at
        // the touch site rather than from this cache.
        inst.snapshotTouchResponseState();
    }

    public void refreshPostCameraRenderState() {
        updateCameraBounds();
        for (ObjectInstance inst : activeObjects.values()) {
            inst.refreshPostCameraRenderState();
        }
        for (ObjectInstance inst : dynamicObjects) {
            inst.refreshPostCameraRenderState();
        }
    }

    public void update(int cameraX, PlayableEntity player, List<? extends PlayableEntity> sidekicks,
            int touchFrameCounter, boolean enableTouchResponses) {
        update(cameraX, player, sidekicks, touchFrameCounter, enableTouchResponses, false, false);
    }

    public void update(int cameraX, PlayableEntity player, List<? extends PlayableEntity> sidekicks,
            int touchFrameCounter, boolean enableTouchResponses,
            boolean inlineSolidResolution, boolean solidPostMovement) {
        update(cameraX, player, sidekicks, touchFrameCounter, enableTouchResponses,
                inlineSolidResolution, solidPostMovement, null);
    }

    public void update(int cameraX, PlayableEntity player, List<? extends PlayableEntity> sidekicks,
            int touchFrameCounter, boolean enableTouchResponses,
            boolean inlineSolidResolution, boolean solidPostMovement,
            Runnable afterExecBeforePlacement) {
        List<? extends PlayableEntity> activeSidekicks = sidekicks != null ? sidekicks : List.of();
        frameCounter++;
        // Gameplay row of the "exactly one tick per serviced V-blank"
        // invariant documented on the vblaCounter field. Every V-blank-only
        // row calls advanceVblaCounter() directly instead of reaching here.
        advanceVblaCounter();
        // Inline-physics path: snapshotTouchResponseState() ran earlier this
        // frame and already refreshed the cached camera bounds. The second
        // call here is harmless redundancy (the post-camera-step bounds
        // haven't shifted between snapshot and update) -- it's kept so the
        // non-inline path (which doesn't snapshot) still gets fresh bounds
        // before the exec loop. Do not consolidate without verifying both
        // call paths first.
        updateCameraBounds();
        // Snapshot each player's centre Y at the start of the object exec pass
        // (post-physics, before any object re-seats him this frame). ROM objects
        // that re-position the player (e.g. the SLZ staircase ride-seat) run in
        // their own slot; an object reading the player's position before that
        // re-seat (because its slot is lower than the re-seating object) sees
        // this pre-seat value. The engine folds the staircase into a lower slot
        // than the fan, so the fan would otherwise read the already-seated Y.
        // Objects that need ROM's "player position when my slot ran, before
        // later-slot objects moved him" read this via getPlayerCentreYAtExecStart.
        solidContacts.captureExecStartPlayerCentreY(player, activeSidekicks);
        SolidExecutionRegistry solidExecutionRegistry = objectServices.solidExecutionRegistry();
        solidExecutionRegistry.beginFrame(
                frameCounter,
                execution.collectActivePlayers(player, activeSidekicks, activePlayersScratch));
        boolean counterBased = placement.isCounterBasedRespawn();
        boolean execThenLoad = placement.usesExecThenLoadPlacement();

        if (inlineSolidResolution) {
            solidContacts.beginInlineFrame(player, activeSidekicks, solidPostMovement);
        }
        try {
            if (counterBased) {
                updateCounterBasedExecThenLoad(
                        cameraX,
                        player,
                        activeSidekicks,
                        inlineSolidResolution,
                        solidPostMovement);
            } else if (execThenLoad) {
                if (slotLayout.twoAxisCursorPlacement()) {
                    // S3K Load_Sprites runs before Process_Sprites and performs
                    // the X-cursor pass before the Y-camera pass
                    // (docs/skdisasm/sonic3k.asm:7884-7894, 37640-37762).
                    // S3K stays load-then-exec.
                    runTwoAxisLoadThenExecutePlacement(cameraX, false);
                }
                // S2: NO pre-exec load. ROM S2 is RunObjects (s2.asm:5095) then
                // exactly one ObjectsManager (s2.asm:5112) = exec -> one load.
                // The single S2 load runs in the post-block below
                // (RunObjects -> ObjectsManager position), after runExecLoop has
                // applied the object-side MarkObjGone self-deletes.
                cleanupDestroyedDynamicObjects();
                runExecLoop(cameraX, player, activeSidekicks, inlineSolidResolution, solidPostMovement);
                runAfterExecBeforePlacement(afterExecBeforePlacement);
            } else {
                syncActiveSpawnsUnload();
                cleanupDestroyedDynamicObjects();
                syncActiveSpawnsLoad(true);
                runExecLoop(cameraX, player, activeSidekicks, inlineSolidResolution, solidPostMovement);
            }
            flushPostExecDynamicSpawns();
        } finally {
            if (inlineSolidResolution) {
                solidContacts.finishInlineFrame(player, activeSidekicks);
            }
            solidExecutionRegistry.finishFrame();
        }

        // Solid contacts now resolve during SpriteManager.update(), before animation.
        if (enableTouchResponses && touchResponses != null) {
            touchResponses.getDebugState().setEnabled(
                    objectServices.debugOverlay().isEnabled(DebugOverlayToggle.TOUCH_RESPONSE));
            touchResponses.update(player, touchFrameCounter);
            for (PlayableEntity sk : activeSidekicks) {
                touchResponses.updateSidekick(sk, touchFrameCounter);
            }
        }

        // Stream objects for the next frame; counter-based S1 defers to the
        // post-camera placement pass, while S2 keeps its single exec->load pass.
        if (!counterBased) {
            if (!execThenLoad || !slotLayout.twoAxisCursorPlacement()) {
                placement.update(cameraX);
            }
            if (execThenLoad && !slotLayout.twoAxisCursorPlacement()) {
                cleanupDestroyedDynamicObjects();
                syncActiveSpawnsLoad(true);
            }
        }
        captureCollisionResponseListForNextFrame();
    }

    public InitialObjectDispatchScope beginInitialProcessSprites(
            int cameraX, PlayableEntity player,
            List<? extends PlayableEntity> sidekicks) {
        return initialDispatch.begin(cameraX, player, sidekicks);
    }

    public void loadInitialDynamicSlots(InitialObjectDispatchScope scope) {
        initialDispatch.loadDynamicSlots(scope);
    }

    public void processInitialAbsoluteDynamicSlot3(InitialObjectDispatchScope scope) {
        initialDispatch.processAbsoluteDynamicSlot3(scope);
    }

    public void processInitialDynamicSlots(InitialObjectDispatchScope scope) {
        initialDispatch.processDynamicSlots(scope);
    }

    public void finishInitialProcessSprites(InitialObjectDispatchScope scope) {
        initialDispatch.finish(scope);
    }

    public void freezeInitialCollisionResponseReadView() {
        initialDispatch.freezeCollisionReadView();
    }

    public void resetInitialCollisionResponseBuild() {
        initialDispatch.resetCollisionBuild();
    }

    public void markInitialDynamicCollisionBuildComplete() {
        initialDispatch.markDynamicCollisionBuildComplete();
    }

    public void captureInitialCollisionResponseBuild() {
        initialDispatch.captureCollisionBuild();
    }

    SolidExecutionRegistry beginInitialSolidExecution(
            PlayableEntity player, List<? extends PlayableEntity> sidekicks) {
        frameCounter++;
        updateCameraBounds();
        solidContacts.captureExecStartPlayerCentreY(player, sidekicks);
        SolidExecutionRegistry registry = objectServices.solidExecutionRegistry();
        registry.beginFrame(
                frameCounter,
                execution.collectActivePlayers(player, sidekicks, activePlayersScratch));
        return registry;
    }

    int firstDynamicSlot() {
        return slotLayout.firstDynamicSlot();
    }

    ObjectCollisionResponseList initialCollisionResponseList() {
        return collisionResponseList;
    }

    void runTwoAxisLoadThenExecutePlacement(int cameraX, boolean observeSetupOrder) {
        placement.update(cameraX);
        syncActiveSpawnsLoad(false);
    }

    private void runAfterExecBeforePlacement(Runnable afterExecBeforePlacement) {
        if (afterExecBeforePlacement != null) {
            afterExecBeforePlacement.run();
        }
    }

    void executeObjectWithSolidContext(ObjectInstance instance, PlayableEntity player,
            List<? extends PlayableEntity> sidekicks,
            boolean inlineSolidResolution, boolean solidPostMovement) {
        execution.execute(
                instance, player, sidekicks, inlineSolidResolution, solidPostMovement);
    }

    SolidExecutionRegistry solidExecutionRegistry() {
        return objectServices.solidExecutionRegistry();
    }

    int vblaCounter() {
        return vblaCounter;
    }

    SolidCheckpointBatch processManualSolidCheckpoint(
            ObjectInstance instance,
            PlayableEntity player,
            List<? extends PlayableEntity> sidekicks,
            boolean solidPostMovement) {
        return solidContacts.processManualCheckpoint(
                instance, player, sidekicks, solidPostMovement);
    }

    SolidCheckpointBatch processCompatibilitySolidCheckpoint(
            ObjectInstance instance,
            PlayableEntity player,
            List<? extends PlayableEntity> sidekicks,
            boolean solidPostMovement) {
        return solidContacts.processCompatibilityCheckpoint(
                instance, player, sidekicks, solidPostMovement);
    }

    void publishInitialCollisionResponse(ObjectInstance instance) {
        if (initialDispatch.isActive()) {
            collisionResponseList.addToCurrentBuild(instance);
        }
    }

    /**
     * ROM-accurate update flow for S1 counter-based respawn.
     * <p>
     * Matches the ROM's Level_MainLoop order:
     * <ol>
     *   <li><b>ExecuteObjects</b> — runs objects in slot order. Objects that are
     *       out of range call DeleteObject during their own routine, freeing their
     *       slot immediately. Child allocations (Ring_Main FindFreeObj, CStom FindNextFreeObj)
     *       see these freed slots in the same pass.</li>
     *   <li><b>ObjPosLoad</b> — loads new objects using FindFreeObj, which sees the
     *       post-ExecuteObjects slot landscape (all frees and child allocations applied).</li>
     * </ol>
     * <p>
     * The previous approach batch-freed all out-of-range objects before the exec loop,
     * then loaded new objects before exec. New objects could fill freed slots that should
     * have been available for child allocations, causing cumulative slot offset drift.
     */
    private void updateCounterBasedExecThenLoad(int cameraX, PlayableEntity player,
            List<? extends PlayableEntity> sidekicks,
            boolean inlineSolidResolution, boolean solidPostMovement) {
        // Phase 1: Snapshot positions and build exec order from EXISTING objects.
        for (ObjectInstance inst : activeObjects.values()) {
            inst.snapshotPreUpdatePosition();
        }
        for (ObjectInstance inst : dynamicObjects) {
            inst.snapshotPreUpdatePosition();
        }

        Arrays.fill(execOrder, null);
        Arrays.fill(ownSlotRetireOrder, null);
        Arrays.fill(playerCentreAtSlotStartValid, false);
        // Per-pass scratch: which managed slots were freed during THIS object
        // pass. Reset here for the same reason runExecLoop resets it -- bits
        // left over from an earlier frame would make a later reallocation see
        // a slot as freed-this-pass when it was not.
        slotsFreedDuringObjectPass.clear();
        pendingChildSlotRelease.clear();
        for (ObjectInstance inst : activeObjects.values()) {
            if (initialDispatch.excludesFromDynamicPass(inst)) {
                continue;
            }
            if (inst instanceof AbstractObjectInstance aoi && isManagedDynamicSlot(executionSlotIndex(aoi))) {
                execOrder[execIndexForSlot(executionSlotIndex(aoi))] = inst;
                indexOwnSlotRetirement(aoi);
            }
        }
        for (ObjectInstance inst : dynamicObjects) {
            if (initialDispatch.excludesFromDynamicPass(inst)) {
                continue;
            }
            if (inst instanceof AbstractObjectInstance aoi && isManagedDynamicSlot(executionSlotIndex(aoi))) {
                execOrder[execIndexForSlot(executionSlotIndex(aoi))] = inst;
                indexOwnSlotRetirement(aoi);
            }
        }
        // Phase 2: ExecuteObjects — run objects in slot order with inline out_of_range.
        updating = true;
        boolean objectsRemoved = false;
        objectLoopBudget.reset();
        try {
            for (currentExecSlot = 0; currentExecSlot < execOrder.length; currentExecSlot++) {
                if (objectLoopBudget.walkEndedBefore(slotIndexForExec(currentExecSlot))) break;
                capturePlayerCentreAtSlotStart(player);
                if (pendingChildSlotRelease.get(currentExecSlot)) {
                    pendingChildSlotRelease.clear(currentExecSlot);
                    releaseSlot(slotIndexForExec(currentExecSlot));
                }
                if (retireBorrowedExecutionSlotOwnerAtOwnSlot(cameraX)) {
                    objectsRemoved = true;
                }
                ObjectInstance instance = execOrder[currentExecSlot];
                if (instance == null) continue;

                // ROM parity: most S1 objects check out_of_range at the START of
                // their routine during ExecuteObjects (static scenery / fixed
                // anchors), so the pre-execute check on the previous frame's
                // position matches ROM. Freeing the slot here (not in a batch
                // pre-pass) ensures child allocations from higher slots see the
                // correct set of available slots.
                //
                // Objects that move then check out_of_range at the END of their
                // routine (RememberState-after-SpeedToPos badniks) opt out via
                // checksOutOfRangeAfterRoutine(); for them the check runs
                // post-execute below, on the moved position, matching ROM
                // (docs/s1disasm/_incObj/sub RememberState.asm:9). Checking such
                // an object before its routine runs unloaded it one frame late
                // (S1 LZ2 Jaws f196 -> f1068 slot cascade).
                ObjectSpawn spawn = instanceToSpawn.get(instance);
                boolean checksOutOfRangeAfter = instance.checksOutOfRangeAfterRoutine();
                if (!checksOutOfRangeAfter
                        && unloadCounterBasedOutOfRange(instance, spawn,
                                slotIndexForExec(currentExecSlot), cameraX)) {
                    execOrder[currentExecSlot] = null;
                    objectsRemoved = true;
                    continue;
                }

                executeObjectWithSolidContext(
                        instance, player, sidekicks, inlineSolidResolution, solidPostMovement);

                if (checksOutOfRangeAfter
                        && !instance.isDestroyed()
                        && unloadCounterBasedOutOfRange(instance, spawn,
                                slotIndexForExec(currentExecSlot), cameraX)) {
                    execOrder[currentExecSlot] = null;
                    objectsRemoved = true;
                    continue;
                }

                if (instance.isDestroyed()) {
                    int slotIndex = slotIndexForExec(currentExecSlot);
                    // Release the instance's OWN SST slot; the exec-slot comparison
                    // only confirms this entry belongs to it. See the note in
                    // unloadCounterBasedOutOfRange -- an object executing from a
                    // reserved child slot owns a different slot than it runs from.
                    if (instance instanceof AbstractObjectInstance aoi3
                            && executionSlotIndex(aoi3) == slotIndex
                            && isManagedDynamicSlot(aoi3.getSlotIndex())) {
                        releaseSlot(aoi3.getSlotIndex());
                    }
                    instance.onUnload();
                    execOrder[currentExecSlot] = null;

                    if (spawn != null) {
                        freeAllReservedChildSlots(spawn);
                        placement.clearStayActive(spawn);
                        resetRespawnStateForOffscreenSelfDelete(instance, spawn);
                        dispatchDestroyRemoveFromActive(instance, spawn);
                        removeActiveObject(spawn);
                    } else {
                        removeDynamicObjectInstance(instance);
                    }
                    objectsRemoved = true;
                }
            }

            // Fallback: process dynamic objects without valid slots
            populateDynamicFallbackScratch();
            for (ObjectInstance inst : dynamicFallbackScratch) {
                if (initialDispatch.excludesFromDynamicPass(inst)) {
                    continue;
                }
                if (inst.isDestroyed()) {
                    releaseSlotIfManaged(inst);
                    inst.onUnload();
                    removeDynamicObjectInstance(inst);
                    objectsRemoved = true;
                    continue;
                }
                executeObjectWithSolidContext(
                        inst, player, sidekicks, inlineSolidResolution, solidPostMovement);
                if (unloadCounterBasedOutOfRange(inst, null, -1, cameraX)) {
                    objectsRemoved = true;
                    continue;
                }
                if (inst.isDestroyed()) {
                    releaseSlotIfManaged(inst);
                    inst.onUnload();
                    removeDynamicObjectInstance(inst);
                    objectsRemoved = true;
                }
            }
            // Fallback: process active objects without valid slots
            populateActiveFallbackScratch();
            for (ObjectInstance inst : activeFallbackScratch) {
                if (initialDispatch.excludesFromDynamicPass(inst)) {
                    continue;
                }
                ObjectSpawn spawn = instanceToSpawn.get(inst);
                if (spawn == null) {
                    continue;
                }
                if (inst.isDestroyed()) {
                    inst.onUnload();
                    placement.clearStayActive(spawn);
                    resetRespawnStateForOffscreenSelfDelete(inst, spawn);
                    dispatchDestroyRemoveFromActive(inst, spawn);
                    removeActiveObject(spawn);
                    objectsRemoved = true;
                    continue;
                }
                executeObjectWithSolidContext(
                        inst, player, sidekicks, inlineSolidResolution, solidPostMovement);
                if (unloadCounterBasedOutOfRange(inst, spawn, -1, cameraX)) {
                    objectsRemoved = true;
                    continue;
                }
                if (inst.isDestroyed()) {
                    inst.onUnload();
                    placement.clearStayActive(spawn);
                    resetRespawnStateForOffscreenSelfDelete(inst, spawn);
                    dispatchDestroyRemoveFromActive(inst, spawn);
                    removeActiveObject(spawn);
                    objectsRemoved = true;
                }
            }
        } finally {
            for (int e = pendingChildSlotRelease.nextSetBit(0); e >= 0;
                    e = pendingChildSlotRelease.nextSetBit(e + 1)) {
                releaseSlot(slotIndexForExec(e));
            }
            pendingChildSlotRelease.clear();
            currentExecSlot = -1;
            updating = false;
            deferredDynamicExecThisFrame.clear();
            if (objectsRemoved) {
                bucketsDirty = true;
                activeObjectsCacheDirty = true;
            }
        }

        // Phase 3: ObjPosLoad — load new objects AFTER ExecuteObjects.
        // Slots freed during the exec loop and child slots allocated during exec
        // are now reflected in the allocator. New objects get the correct slot numbers.
        syncActiveSpawnsLoad(false);

    }

    /**
     * Runs the standard exec loop for non-counter-based respawn (S2/S3K).
     * This preserves the existing behavior where unload and load happen before exec.
     */
    void runExecLoop(int cameraX, PlayableEntity player,
            List<? extends PlayableEntity> sidekicks,
            boolean inlineSolidResolution, boolean solidPostMovement) {
        int unloadCameraX = objectUnloadCameraX(cameraX);
        // ROM parity: Snapshot all objects' positions BEFORE their updates run.
        for (ObjectInstance inst : activeObjects.values()) {
            inst.snapshotPreUpdatePosition();
        }
        for (ObjectInstance inst : dynamicObjects) {
            inst.snapshotPreUpdatePosition();
        }

        // ROM parity: Build slot-ordered execution array.
        slotsFreedDuringObjectPass.clear();
        Arrays.fill(execOrder, null);
        Arrays.fill(ownSlotRetireOrder, null);
        Arrays.fill(playerCentreAtSlotStartValid, false);
        pendingChildSlotRelease.clear();
        for (ObjectInstance inst : activeObjects.values()) {
            if (initialDispatch.excludesFromDynamicPass(inst)) {
                continue;
            }
            if (inst instanceof AbstractObjectInstance aoi && isManagedDynamicSlot(executionSlotIndex(aoi))) {
                execOrder[execIndexForSlot(executionSlotIndex(aoi))] = inst;
                indexOwnSlotRetirement(aoi);
            }
        }
        for (ObjectInstance inst : dynamicObjects) {
            if (initialDispatch.excludesFromDynamicPass(inst)) {
                continue;
            }
            if (inst instanceof AbstractObjectInstance aoi && isManagedDynamicSlot(executionSlotIndex(aoi))) {
                execOrder[execIndexForSlot(executionSlotIndex(aoi))] = inst;
                indexOwnSlotRetirement(aoi);
            }
        }
        updating = true;
        boolean objectsRemoved = false;
        // Track objects processed by the slot-based loop so the fallback loop
        // doesn't double-update objects that lost their slot mid-frame.
        // Reused per frame; cleared in the finally block below.
        Set<ObjectInstance> processedInExecLoop = processedInExecLoopScratch;
        objectLoopBudget.reset();
        try {
            // ROM parity: Iterate slots in ascending order, matching ExecuteObjects.
            for (currentExecSlot = 0; currentExecSlot < execOrder.length; currentExecSlot++) {
                if (objectLoopBudget.walkEndedBefore(slotIndexForExec(currentExecSlot))) break;
                capturePlayerCentreAtSlotStart(player);
                // A reserved child slot freed earlier in this pass is released
                // here, at the exec position its own ROM object would have
                // reached in the ascending ExecuteObjects walk
                // (docs/s1disasm/_inc/ExecuteObjects.asm:10-30) -- see
                // releaseChildSlotAtItsOwnExecPosition. Without this (and the
                // finally-block flush below) the deferred bit set by
                // releaseChildSlotAtItsOwnExecPosition was never acted on in
                // this loop, so every parent unloaded on this path leaked its
                // whole reserved child block for the rest of the act.
                if (pendingChildSlotRelease.get(currentExecSlot)) {
                    pendingChildSlotRelease.clear(currentExecSlot);
                    releaseSlot(slotIndexForExec(currentExecSlot));
                }
                // A consolidated parent that runs from a borrowed child slot is
                // retired at its OWN slot's position in the ascending walk -- see
                // retireBorrowedExecutionSlotOwnerAtOwnSlot. S2/S3K use the same
                // ascending ExecuteObjects walk as S1, and S3K's consolidated
                // Obj_AIZGiantRideVine is exactly the S1 staircase shape: the ROM
                // root's range check and DeleteChain run at the START of
                // AIZGiantRideVine_Main at the PARENT slot on an x_pos the
                // routine never writes (docs/skdisasm/sonic3k.asm:46813-46822),
                // while the engine executes the consolidated object at the handle
                // child slot for solid-pass ordering. Without this the parent slot
                // was released later in the walk than ROM, so lowest-free
                // allocations between the two positions saw it as occupied.
                if (retireBorrowedExecutionSlotOwnerAtOwnSlot(unloadCameraX)) {
                    objectsRemoved = true;
                }
                ObjectInstance instance = execOrder[currentExecSlot];
                if (instance == null) continue;
                processedInExecLoop.add(instance);

                executeObjectWithSolidContext(
                        instance, player, sidekicks, inlineSolidResolution, solidPostMovement);

                // ROM parity: each object calls RememberState / out_of_range
                // at the END of its routine, AFTER updating position. S2/S3K
                // objects end their routines with `jmpto JmpTo_MarkObjGone_P1`
                // (docs/s2disasm/s2.asm MarkObjGone_P1 line ~30080), which
                // checks the object's CURRENT x_pos(a0) — not its spawn x —
                // against the camera window. Moving objects (e.g. Buzzer
                // flying 174 px west of spawn) survive as long as their
                // current position is in range. This check runs for both
                // counter-based (S1) and non-counter (S2/S3K) placement.
                if (!instance.isDestroyed()) {
                    ObjectSpawn oorSpawn = instanceToSpawn.get(instance);
                    if (unloadCounterBasedOutOfRange(instance, oorSpawn,
                            slotIndexForExec(currentExecSlot), unloadCameraX)) {
                        execOrder[currentExecSlot] = null;
                        objectsRemoved = true;
                        continue;
                    }
                }

                if (instance.isDestroyed()) {
                    int slotIndex = slotIndexForExec(currentExecSlot);
                    // Release the instance's OWN SST slot; the exec-slot comparison
                    // only confirms this entry belongs to it. See the note in
                    // unloadCounterBasedOutOfRange -- an object executing from a
                    // reserved child slot owns a different slot than it runs from.
                    if (instance instanceof AbstractObjectInstance aoi3
                            && executionSlotIndex(aoi3) == slotIndex
                            && isManagedDynamicSlot(aoi3.getSlotIndex())) {
                        releaseSlot(aoi3.getSlotIndex());
                    }
                    instance.onUnload();
                    execOrder[currentExecSlot] = null;

                    ObjectSpawn spawn = instanceToSpawn.get(instance);
                    if (spawn != null) {
                        freeAllReservedChildSlots(spawn);
                        placement.clearStayActive(spawn);
                        resetRespawnStateForOffscreenSelfDelete(instance, spawn);
                        dispatchDestroyRemoveFromActive(instance, spawn);
                        removeActiveObject(spawn);
                    } else {
                        removeDynamicObjectInstance(instance);
                    }
                    objectsRemoved = true;
                }
            }

            // Fallback: process objects without valid slots
            populateDynamicFallbackScratch();
            for (ObjectInstance inst : dynamicFallbackScratch) {
                if (initialDispatch.excludesFromDynamicPass(inst)) {
                    continue;
                }
                if (inst.isDestroyed()) {
                    releaseSlotIfManaged(inst);
                    inst.onUnload();
                    removeDynamicObjectInstance(inst);
                    objectsRemoved = true;
                    continue;
                }
                executeObjectWithSolidContext(
                        inst, player, sidekicks, inlineSolidResolution, solidPostMovement);
                if (!inst.isDestroyed()
                        && unloadCounterBasedOutOfRange(inst, null, -1, unloadCameraX)) {
                    objectsRemoved = true;
                    continue;
                }
                if (inst.isDestroyed()) {
                    releaseSlotIfManaged(inst);
                    inst.onUnload();
                    removeDynamicObjectInstance(inst);
                    objectsRemoved = true;
                }
            }
            populateActiveFallbackScratch();
            for (ObjectInstance inst : activeFallbackScratch) {
                if (initialDispatch.excludesFromDynamicPass(inst)) {
                    continue;
                }
                ObjectSpawn spawn = instanceToSpawn.get(inst);
                if (spawn == null) {
                    continue;
                }
                // Skip objects already processed in the slot-based exec loop.
                // This prevents double-updates when an object releases its slot mid-frame.
                if (processedInExecLoop.contains(inst)) {
                    continue;
                }
                if (inst.isDestroyed()) {
                    inst.onUnload();
                    placement.clearStayActive(spawn);
                    resetRespawnStateForOffscreenSelfDelete(inst, spawn);
                    dispatchDestroyRemoveFromActive(inst, spawn);
                    removeActiveObject(spawn);
                    objectsRemoved = true;
                    continue;
                }
                executeObjectWithSolidContext(
                        inst, player, sidekicks, inlineSolidResolution, solidPostMovement);
                if (!inst.isDestroyed()
                        && unloadCounterBasedOutOfRange(inst, spawn, -1, unloadCameraX)) {
                    objectsRemoved = true;
                    continue;
                }
                if (inst.isDestroyed()) {
                    inst.onUnload();
                    placement.clearStayActive(spawn);
                    resetRespawnStateForOffscreenSelfDelete(inst, spawn);
                    dispatchDestroyRemoveFromActive(inst, spawn);
                    removeActiveObject(spawn);
                    objectsRemoved = true;
                }
            }
        } finally {
            // Child slots whose exec position the walk had already passed when
            // the parent unloaded: ROM has no remaining position in this frame's
            // walk at which to free them, so they are released as the pass ends.
            for (int e = pendingChildSlotRelease.nextSetBit(0); e >= 0;
                    e = pendingChildSlotRelease.nextSetBit(e + 1)) {
                releaseSlot(slotIndexForExec(e));
            }
            pendingChildSlotRelease.clear();
            currentExecSlot = -1;
            updating = false;
            deferredDynamicExecThisFrame.clear();
            processedInExecLoopScratch.clear();
            if (objectsRemoved) {
                bucketsDirty = true;
                activeObjectsCacheDirty = true;
            }
        }
    }

    private int objectUnloadCameraX(int cameraX) {
        if (slotLayout != ObjectSlotLayout.SONIC_2) {
            return cameraX;
        }
        // S2 RunObjects consumes Camera_X_pos_coarse from the previous
        // ObjectsManager pass. ObjectsManager_Main rewrites that coarse value
        // after BuildSprites (docs/s2disasm/s2.asm:5111-5112, 33033-33036),
        // so object-side MarkObjGone/MarkObjGone2 checks in the next
        // RunObjects pass must use the latched post-camera value rather than
        // recomputing from the live pre-camera value.
        return s2LatchedObjectManagerCameraX != Integer.MIN_VALUE
                ? s2LatchedObjectManagerCameraX
                : cameraX;
    }

    /**
     * Extend the ObjectPlacementController active set using the post-camera X position.
     * <p>
     * ROM parity: {@code ObjPosLoad} runs <b>after</b> {@code DeformLayers}
     * (camera update), so it sees the camera's post-update position. The primary
     * {@code placement.update()} inside {@link #update} uses the pre-camera
     * position. When the camera crosses a 128px chunk boundary between those
     * two positions, the post-camera spawn window exposes a right-side gap
     * while moving forward or a left-side gap while moving backward. Objects in
     * that gap must materialize before the frame ends, matching ROM ObjPosLoad
     * timing.
     * <p>
     * This method scans the gap region (between the old and new window right
     * edges) and materializes any eligible spawns into the current frame's
     * object table, WITHOUT updating the ObjectPlacementController's internal state
     * (cursor, lastCameraChunk). This ensures that the primary ObjectPlacementController pass
     * in the next frame still processes the chunk boundary normally
     * (including left-edge removal), while the gap spawns already exist with
     * ROM-accurate end-of-frame timing. Because this runs after
     * {@code ObjectManager.update(...)} for the current frame, the newly
     * created instances do not execute until the following frame's
     * ExecuteObjects pass.
     *
     * @param postCameraX camera X position after the camera update step
     */
    public void postCameraPlacementUpdate(int postCameraX) {
        if (slotLayout == ObjectSlotLayout.SONIC_2) {
            s2LatchedObjectManagerCameraX = postCameraX;
        }
        if (slotLayout.twoAxisCursorPlacement()) {
            // S3K Load_Sprites runs at the start of LevelLoop before
            // Process_Sprites/DeformBgLayer, so post-camera ObjectPlacementController catch-up
            // would create objects one ROM loader call early
            // (docs/skdisasm/sonic3k.asm:7884-7895).
            return;
        }
        placement.extendForPostCamera(postCameraX, this::inlineCreateObject);
    }

    /**
     * Inline creation callback for ROM-accurate ObjPosLoad.
     * Called during ObjectPlacementController cursor advancement to create instances immediately.
     * <p>
     * This is the post-camera ({@link #postCameraPlacementUpdate}) gap-scan
     * creation path. It must apply the SAME vertical-eligibility policy as the
     * primary {@link #syncActiveSpawnsLoad}{@code (true)} load so that a spawn
     * entering the horizontal load window on a chunk-crossing frame is
     * materialized on the SAME frame in both paths. For S2 the ROM
     * {@code ObjectsManager_GoingForward}/{@code GoingBackward} calls
     * {@code ChkLoadObj} immediately after the X-window scan with no
     * {@code Camera_Y_pos} filter (docs/s2disasm/s2.asm:33095-33136), so S2 loads
     * bypass the vertical filter ({@code allowVerticalLoadBypassForS2 = true}).
     * Passing {@code false} here previously deferred a horizontally-in-window
     * but not-yet-vertically-near spawn to the next frame's pre-camera load,
     * costing the object one update relative to the ROM (e.g. CNZ {@code ObjD4}
     * arriving 1px behind, s2.asm:58759-58799).
     */
    private boolean inlineCreateObject(ObjectSpawn spawn, int counterValue) {
        if (activeObjects.containsKey(spawn)) {
            return true; // Already exists
        }
        if (!isSpawnVerticallyEligibleForLoad(spawn, true)) {
            return false;
        }
        int preSlot = allocateSlot();
        if (preSlot < 0) {
            return false; // FindFreeObj failure equivalent
        }
        ObjectInstance instance = ObjectConstructionContext.with(objectServices, preSlot,
                () -> registry != null ? registry.create(spawn) : null);
        if (instance != null) {
            if (instance instanceof AbstractObjectInstance aoi) {
                aoi.setServices(objectServices);
                if (aoi.getSlotIndex() < 0) {
                    aoi.setSlotIndex(preSlot);
                }
                if (counterValue >= 0) {
                    aoi.setRespawnStateIndex(counterValue);
                }
            } else {
                releaseSlot(preSlot);
            }
            registerActiveObject(spawn, instance);
            bucketsDirty = true;
            activeObjectsCacheDirty = true;
            return true;
        } else {
            releaseSlot(preSlot);
            return false;
        }
    }

    /**
     * Returns ObjectPlacementController cursor diagnostics for ROM↔engine comparison.
     * Only meaningful for S1 counter-based respawn mode.
     */
    public int[] getPlacementCursorState() {
        if (!placement.isCounterBasedRespawn()) return null;
        return new int[] {
            placement.getCursorIndex(),
            placement.getLeftCursorIndex(),
            placement.getFwdCounter(),
            placement.getBwdCounter(),
            placement.getLastCameraChunk()
        };
    }

    public void applyPlaneSwitchers(PlayableEntity player) {
        // Sonic 2 Obj03 tracks separate crossover state for MainCharacter and
        // Sidekick (objoff_34 / objoff_35) and updates both each frame.
        if (planeSwitchers != null) {
            planeSwitchers.update(player);
        }
    }

    /** Applies one loaded ROM plane-switch object at its ExecuteObjects slot. */
    public void applyPlaneSwitcher(ObjectSpawn spawn, PlayableEntity player) {
        if (planeSwitchers != null) {
            planeSwitchers.update(spawn, player);
        }
    }

    public int getPlaneSwitcherSideState(ObjectSpawn spawn) {
        if (planeSwitchers == null) {
            return -1;
        }
        return planeSwitchers.getSideState(spawn);
    }

    public void drawLowPriority() {
        ensureBucketsPopulated();
        for (int bucket = RenderPriority.MAX; bucket >= RenderPriority.MIN; bucket--) {
            drawPriorityBucket(bucket, false);
        }
    }

    public void drawHighPriority() {
        ensureBucketsPopulated();
        for (int bucket = RenderPriority.MAX; bucket >= RenderPriority.MIN; bucket--) {
            drawPriorityBucket(bucket, true);
        }
    }

    private void ensureBucketsPopulated() {
        if (!bucketsDirty) {
            return;
        }
        bucketsDirty = false;

        // Clear all buckets
        for (int i = 0; i < BUCKET_COUNT; i++) {
            lowPriorityBuckets[i].clear();
            highPriorityBuckets[i].clear();
        }

        // Bucket active objects
        for (ObjectInstance instance : activeObjects.values()) {
            int bucket = RenderPriority.clamp(instance.getPriorityBucket());
            int idx = bucket - RenderPriority.MIN;
            if (instance.isHighPriority()) {
                highPriorityBuckets[idx].add(instance);
            } else {
                lowPriorityBuckets[idx].add(instance);
            }
        }

        // Bucket dynamic objects
        for (ObjectInstance instance : dynamicObjects) {
            int bucket = RenderPriority.clamp(instance.getPriorityBucket());
            int idx = bucket - RenderPriority.MIN;
            if (instance.isHighPriority()) {
                highPriorityBuckets[idx].add(instance);
            } else {
                lowPriorityBuckets[idx].add(instance);
            }
        }

        // ROM parity: lower sprite-table indices render in front. Objects execute and
        // call Draw_Sprite in slot order, so lower SST slots must be drawn later in
        // painter's-algorithm order. Sort each bucket descending by slot so lower
        // slot indices appear on top.
        for (int i = 0; i < BUCKET_COUNT; i++) {
            lowPriorityBuckets[i].sort(RENDER_SLOT_DESCENDING);
            highPriorityBuckets[i].sort(RENDER_SLOT_DESCENDING);
        }

        renderBucketSnapshot.capture(activeObjects.values(), dynamicObjects);
    }

    public void drawPriorityBucket(int bucket, boolean highPriority) {
        ensureBucketsPopulated();
        int targetBucket = RenderPriority.clamp(bucket);
        int idx = targetBucket - RenderPriority.MIN;
        List<ObjectInstance>[] buckets = highPriority ? highPriorityBuckets : lowPriorityBuckets;
        drawBucketInstancesWithPriority(buckets[idx], graphicsManager);
    }

    /**
     * Draw all objects in a single unified bucket, regardless of their isHighPriority flag.
     * Calls the provided callback before drawing each object with its high priority status.
     *
     * This supports ROM-accurate sprite-to-sprite ordering where bucket number determines
     * draw order independently of the sprite-to-tile priority (isHighPriority flag).
     *
     * @param bucket   The priority bucket to draw (0-7)
     * @param callback Called before each object draw with (object, isHighPriority)
     */
    public void drawUnifiedBucket(int bucket, ObjectDrawCallback callback) {
        ensureBucketsPopulated();
        int targetBucket = RenderPriority.clamp(bucket);
        int idx = targetBucket - RenderPriority.MIN;

        // Draw low-priority objects first (they appear behind)
        drawBucketInstances(lowPriorityBuckets[idx], false, callback);

        // Draw high-priority objects second (they appear in front)
        drawBucketInstances(highPriorityBuckets[idx], true, callback);
    }

    private void drawBucketInstances(List<ObjectInstance> instances, boolean highPriority, ObjectDrawCallback callback) {
        if (instances.isEmpty()) {
            return;
        }

        enableVerticalWrapIfNeeded();
        try {
            renderCommands.clear();
            for (ObjectInstance instance : instances) {
                if (callback != null) {
                    callback.beforeDraw(instance, highPriority);
                }
                instance.appendRenderCommands(renderCommands);
            }

            if (!renderCommands.isEmpty()) {
                graphicsManager.enqueueDebugLineState();
                graphicsManager.registerCommand(new GLCommandGroup(GL_LINES, renderCommands));
                graphicsManager.enqueueDefaultShaderState();
            }
        } finally {
            graphicsManager.disableVerticalWrapAdjust();
        }
    }

    /**
     * Callback interface for unified object drawing.
     * Called before each object is drawn to allow setting up shader uniforms.
     */
    public interface ObjectDrawCallback {
        /**
         * Called before drawing an object.
         *
         * @param instance     The object instance about to be drawn
         * @param highPriority True if object should appear above high-priority tiles
         */
        void beforeDraw(ObjectInstance instance, boolean highPriority);
    }

    /**
     * Draw all objects in a single unified bucket with per-instance priority.
     * Priority is now handled per-instance in the shader, so no batch flushing
     * is needed when switching between low and high priority objects.
     *
     * @param bucket The priority bucket to draw (0-7)
     * @param gfx    The graphics manager to use for priority state
     */
    public void drawUnifiedBucketWithPriority(int bucket, GraphicsManager gfx) {
        ensureBucketsPopulated();
        int idx = RenderPriority.clamp(bucket) - RenderPriority.MIN;

        // The BatchedPatternRenderer uses a single tile-occlusion uniform for
        // the entire batch — it cannot vary per-instance. The helper flushes
        // at every mask transition while retaining SST slot order.
        // (The InstancedPatternRenderer bakes priority per-instance and doesn't
        // need the flush, but it's harmless — empty flushes are no-ops.)

        if (!lowPriorityBuckets[idx].isEmpty()) {
            gfx.flushPatternBatch();
            gfx.setCurrentSpriteHighPriority(false);
            gfx.beginPatternBatch();
            drawBucketInstancesWithPriority(lowPriorityBuckets[idx], gfx);
        }

        if (!highPriorityBuckets[idx].isEmpty()) {
            gfx.flushPatternBatch();
            gfx.setCurrentSpriteHighPriority(true);
            gfx.beginPatternBatch();
            drawBucketInstancesWithPriority(highPriorityBuckets[idx], gfx);
        }
    }

    private void drawBucketInstancesWithPriority(List<ObjectInstance> instances, GraphicsManager gfx) {
        if (instances.isEmpty()) {
            return;
        }

        enableVerticalWrapIfNeeded();
        try {
            renderCommands.clear();
            for (ObjectInstance instance : instances) {
                int mask = instance.getTileOcclusionPaletteMask();
                if (gfx.getCurrentSpriteTileOcclusionPaletteMask() != mask) {
                    gfx.flushPatternBatch();
                    gfx.setCurrentSpriteTileOcclusionPaletteMask(mask);
                    gfx.beginPatternBatch();
                }
                instance.appendRenderCommands(renderCommands);
            }

            if (!renderCommands.isEmpty()) {
                graphicsManager.enqueueDebugLineState();
                graphicsManager.registerCommand(new GLCommandGroup(GL_LINES, renderCommands));
                graphicsManager.enqueueDefaultShaderState();
            }
        } finally {
            graphicsManager.disableVerticalWrapAdjust();
        }
    }

    public Collection<ObjectInstance> getActiveObjects() {
        rebuildActiveObjectCaches();
        return cachedActiveObjects;
    }

    /**
     * ROM-parity slot dereference: returns the object-pointer-table id byte of
     * the object currently occupying SST slot {@code slot}, or {@code -1} when
     * no live object occupies it.
     *
     * <p>This models the 68000 pointer arithmetic in S2
     * {@code TailsCPU_CheckDespawn} / {@code TailsCPU_UpdateObjInteract}
     * (docs/s2disasm/s2.asm:39409-39419,39435-39446) and the S3K analogue
     * {@code sub_13EFC} (docs/skdisasm/sonic3k.asm:26816-26833):
     * {@code a3 = Object_RAM + interact(a0)*object_size}; {@code id(a3)} is the
     * byte at the slot. When ROM {@code DeleteObject} (s2.asm:30324-30339) has
     * zeroed the slot, {@code id(a3)} reads {@code 0}; the engine has no
     * persistent zeroed bytes, so an empty engine slot returns {@code -1} here.
     * The sidekick despawn comparator maps that {@code -1} back to ROM id
     * {@code 0} (an emptied/deleted slot is a real id change, not "slot
     * unchanged"), so an off-screen {@code DeleteObject} of the ridden object
     * fires {@code TailsCPU_Despawn} exactly as ROM does
     * (s2.asm:39403-39429). A still-loaded same-id object returns its real id
     * and matches the snapshot, deferring to the off-screen respawn timer.
     */
    public int objectIdInSlot(int slot) {
        if (slot < 0) {
            return -1;
        }
        for (ObjectInstance instance : getActiveObjects()) {
            if (instance instanceof AbstractObjectInstance aoi
                    && aoi.getSlotIndex() == slot
                    && !instance.isDestroyed()
                    && instance.getSpawn() != null) {
                return instance.getSpawn().objectId() & 0xFF;
            }
        }
        return -1;
    }

    /**
     * Read-only snapshot of every live dynamic-slot occupant as {@code slot ->
     * (spawn.objectId() & 0xFF)}. This is the bulk analogue of
     * {@link #objectIdInSlot(int)}: it walks the same {@link #getActiveObjects()}
     * scan and applies the same liveness predicate (non-destroyed,
     * spawn-backed), but restricts the result to managed dynamic slots
     * ({@link ObjectSlotLayout#isDynamicSlot(int)}) so it lines up with the
     * SST window the {@link SlotAllocator} owns.
     *
     * <p>It is the engine side of the comparison-only occupancy oracle's
     * extra-occupant detection: a slot present here but absent from the ROM
     * trace timeline means the engine kept an object loaded that the ROM had
     * already unloaded (the MTZ off-screen-unload failure mode). The returned
     * map is freshly built and never aliases internal state, so callers cannot
     * mutate manager state through it.
     */
    public java.util.Map<Integer, Integer> occupiedDynamicSlotIds() {
        java.util.Map<Integer, Integer> occupancy = new java.util.HashMap<>();
        for (ObjectInstance instance : getActiveObjects()) {
            if (instance instanceof AbstractObjectInstance aoi
                    && !instance.isDestroyed()
                    && instance.getSpawn() != null) {
                int slot = aoi.getSlotIndex();
                if (slotLayout.isDynamicSlot(slot)) {
                    // The LIVE obID, not the spawn id: ROM objects that rewrite obID(a0) in
                    // place (S1 BossSpikeball_Explode -> id_Explosion) keep their slot and
                    // report the new id in the SST. See AbstractObjectInstance#getLiveObjectId.
                    int liveId = aoi.getLiveObjectId();
                    occupancy.put(slot, liveId >= 0 ? liveId : (instance.getSpawn().objectId() & 0xFF));
                }
            }
        }
        return occupancy;
    }

    /** Sentinel id for a dynamic slot held by a parent's child reservation with no live instance yet. */
    public static final int SLOT_STATE_RESERVED_CHILD = -2;

    /** Sentinel id for a dynamic slot the allocator holds that no live instance or reservation explains. */
    public static final int SLOT_STATE_UNATTRIBUTED = -3;

    /**
     * Read-only occupancy snapshot that folds in the two non-instance states the
     * {@link SlotAllocator} bitset tracks but {@link #occupiedDynamicSlotIds()}
     * cannot see: parent-held child reservations (ROM {@code FindFreeObj} /
     * {@code FindNextFreeObj} has already consumed the slot even though the child
     * object has not been constructed yet) and any residual allocator bit with no
     * owner. Comparison-only diagnostic: mirrors the ROM SST occupancy the
     * recorder's {@code slot_dump} samples, and never mutates manager state.
     */
    public java.util.Map<Integer, Integer> occupiedDynamicSlotIdsWithReservations() {
        java.util.Map<Integer, Integer> occupancy = occupiedDynamicSlotIds();
        for (int[] childSlots : reservedChildSlots.values()) {
            if (childSlots == null) {
                continue;
            }
            for (int slot : childSlots) {
                if (slotLayout.isDynamicSlot(slot) && !occupancy.containsKey(slot)) {
                    occupancy.put(slot, SLOT_STATE_RESERVED_CHILD);
                }
            }
        }
        int lastDynamic = slotLayout.firstDynamicSlot() + slotLayout.dynamicSlotCount();
        for (int slot = slotLayout.firstDynamicSlot(); slot < lastDynamic; slot++) {
            if (slotLayout.isDynamicSlot(slot)
                    && !slotAllocator.isEmpty(slot)
                    && !occupancy.containsKey(slot)) {
                occupancy.put(slot, SLOT_STATE_UNATTRIBUTED);
            }
        }
        return occupancy;
    }

    public List<ObjectInstance> snapshotPersistentDynamicObjectsForTransition() {
        List<ObjectInstance> snapshot = new ArrayList<>();
        for (ObjectInstance instance : dynamicObjects) {
            if (instance == null || instance.isDestroyed() || !instance.isPersistent()) {
                continue;
            }
            // ROM Load_Level clears Dynamic_object_RAM, so a boss object group does
            // not survive a level reload. Boss component children report persistent
            // only so they survive the off-screen cull during the fixed-arena fight
            // (see AbstractBossChild.isPersistent); they must NOT ride a seamless act
            // reload. Carrying them strands them un-offset in the new act — concretely
            // the placed AIZ1 miniboss cutscene is dropped on the AIZ1->AIZ2 fire
            // reload while its persistent body/arm/flame-barrel children were carried,
            // leaving an art-less (invisible) body and still-hurting flame barrels
            // partway through AIZ2.
            if (instance instanceof BossChildComponent) {
                continue;
            }
            snapshot.add(instance);
        }
        return snapshot;
    }

    /**
     * Runs post-player hooks for legacy object-order modules after the main
     * playable update has completed for the current frame.
     */
    public void runPostPlayerHooks(PlayableEntity player, int frameCounter) {
        if (player == null) {
            return;
        }
        // Snapshot the pre-hook population into a reused scratch list so hooks
        // can mutate the active set without ConcurrentModification while the
        // per-frame copy allocation is avoided. Cleared in finally; the scratch
        // never escapes this method.
        List<ObjectInstance> snapshot = postPlayerHooksScratch;
        snapshot.clear();
        snapshot.addAll(getActiveObjects());
        try {
            for (ObjectInstance instance : snapshot) {
                if (instance == null || instance.isDestroyed()) {
                    continue;
                }
                if (instance instanceof PostPlayerUpdateHook hook) {
                    hook.updatePostPlayer(frameCounter, player);
                }
            }
        } finally {
            postPlayerHooksScratch.clear();
        }
    }

    /**
     * Applies a ROM-style level-repeat coordinate shift to active level-space
     * objects. S3K MHZ uses this during the forced-scroll loop when the camera
     * wraps by $200 and the ROM's helper scans dynamic object RAM for objects
     * with {@code render_flags} bit 2 set.
     */
    public void applyLevelRepeatOffsetToActiveObjects(int offsetX, int offsetY) {
        List<ObjectInstance> snapshot = new ArrayList<>(getActiveObjects());
        for (ObjectInstance instance : snapshot) {
            if (instance == null || instance.isDestroyed() || !instance.participatesInLevelRepeatOffset()) {
                continue;
            }
            instance.applyLevelRepeatOffset(offsetX, offsetY);
        }
    }

    List<ObjectInstance> getSolidProviderObjects() {
        rebuildActiveObjectCaches();
        return cachedSolidProviderObjects;
    }

    List<ObjectInstance> getTouchResponseObjects() {
        rebuildActiveObjectCaches();
        return collisionResponseList.touchResponseObjects(cachedTouchResponseObjects);
    }

    boolean touchUsesPreviousCollisionResponseList() { return collisionResponseList.usesPrevious(); }

    /**
     * Publishes the {@code Collision_response_list} that a <em>represented</em>
     * (already-reconstructed, never dispatched) initial {@code Process_Sprites}
     * pass would have built, so the first {@code LevelLoop} pass's player slots
     * read a populated list rather than an empty one.
     * <p>
     * ROM: level entry runs {@code Load_Sprites} then {@code Process_Sprites}
     * once at {@code loc_6468} (docs/skdisasm/sonic3k.asm:7848-7854), before
     * {@code LevelLoop} begins. Every object executed in that pass tail-calls
     * {@code Add_SpriteToCollisionResponseList}
     * (docs/skdisasm/sonic3k.asm:21199-21207), so the list is already populated
     * when the first {@code LevelLoop} pass's Player_1/Player_2 slots walk it in
     * {@code Touch_Response} (docs/skdisasm/sonic3k.asm:20656).
     * <p>
     * Callers that <em>execute</em> the setup pass must not call this — the
     * dispatch publishes the list itself. It exists only for entry paths that
     * reconstruct the pass's resulting object state directly and would otherwise
     * leave S3K's previous-list read view empty for one pass.
     */
    public void publishRepresentedInitialCollisionResponseList() {
        captureCollisionResponseListForNextFrame();
    }

    private void captureCollisionResponseListForNextFrame() {
        rebuildActiveObjectCaches();
        collisionResponseList.captureForNextFrame(cachedTouchResponseObjects);
    }

    private void rebuildActiveObjectCaches() {
        if (activeObjectsCacheDirty) {
            cachedActiveObjects.clear();
            cachedActiveObjects.addAll(activeObjects.values());
            cachedActiveObjects.addAll(dynamicObjects);
            // ReactToItem and SolidObject scan in SST slot order; slotless objects sort last.
            cachedActiveObjects.sort((a, b) -> {
                int slotA = a instanceof AbstractObjectInstance aoiA ? aoiA.getSlotIndex() : Integer.MAX_VALUE;
                int slotB = b instanceof AbstractObjectInstance aoiB ? aoiB.getSlotIndex() : Integer.MAX_VALUE;
                return Integer.compare(slotA, slotB);
            });
            cachedSolidProviderObjects.clear();
            cachedTouchResponseObjects.clear();
            for (ObjectInstance instance : cachedActiveObjects) {
                if (instance instanceof SolidObjectProvider) {
                    cachedSolidProviderObjects.add(instance);
                }
                if (instance instanceof TouchResponseProvider) {
                    cachedTouchResponseObjects.add(instance);
                }
            }
            activeObjectsCacheDirty = false;
        }
    }

    public int getFrameCounter() {
        return frameCounter;
    }

    public boolean hasActiveInitialProcessSpritesDispatch() {
        return initialDispatch.isActive();
    }

    public int getActiveObjectSlotCount() {
        return slotAllocator.activeCount();
    }

    public int getPeakObjectSlotCount() {
        return slotAllocator.peakSlotCount();
    }

    public int getObjectSlotCapacity() {
        return execOrder.length;
    }

    /**
     * Captures the live Player 1 target visible when each SST slot begins.
     * {@code Obj_Attracted_Ring} reads Player 1 directly in its own object
     * routine, so a carrier in an earlier slot has already moved that target
     * while one in a later slot has not (sonic3k.asm:35710-35728,
     * 35795-35841).
     */
    private void capturePlayerCentreAtSlotStart(PlayableEntity player) {
        if (player == null) {
            return;
        }
        playerCentreXAtSlotStart[currentExecSlot] = player.getCentreX();
        playerCentreYAtSlotStart[currentExecSlot] = player.getCentreY();
        playerCentreAtSlotStartValid[currentExecSlot] = true;
    }

    public boolean hasPlayerCentreAtObjectSlotStart(int slotIndex) {
        int execIndex = execIndexForSlot(slotIndex);
        return execIndex >= 0
                && execIndex < playerCentreAtSlotStartValid.length
                && playerCentreAtSlotStartValid[execIndex];
    }

    public int getPlayerCentreXAtObjectSlotStart(int slotIndex) {
        return playerCentreXAtSlotStart[execIndexForSlot(slotIndex)];
    }

    public int getPlayerCentreYAtObjectSlotStart(int slotIndex) {
        return playerCentreYAtSlotStart[execIndexForSlot(slotIndex)];
    }

    public Collection<ObjectSpawn> getActiveSpawns() {
        return placement.getActiveSpawns();
    }

    public List<ObjectSpawn> getAllSpawns() {
        return placement.getAllSpawns();
    }

    /**
     * Test seam: drive a standalone load/trim-windowing {@link ObjectPlacementController} (native
     * 320px viewport) under the supplied {@link ObjectWindowingStrategy} through a
     * sequence of camera-X positions and return the sorted X positions of the
     * spawns active after the final step.
     * <p>
     * Exercises the real {@code spawnForward}/{@code spawnBackwardNonCounter}/
     * {@code trimLeftNonCounter}/{@code trimRightNonCounter} scan with the
     * strategy's final cursor boundaries (Task 1.4b), so a unit test can pass the
     * S2 strategy and assert the ROM <em>exclusive</em> load edge
     * ({@code spawn.x < forwardLoadEdge}) without a ROM/level harness. Keeping the
     * strategy a parameter means this shared seam stays game-agnostic.
     */
    public static int[] runWindowingScanForTest(int[] spawnXs, int[] cameraSequence,
            ObjectWindowingStrategy strategy) {
        List<ObjectSpawn> spawnList = new ArrayList<>(spawnXs.length);
        for (int x : spawnXs) {
            // respawnTracked=false so every in-window spawn re-loads on cursor
            // entry (no remembered/destroyed latch interfering with the boundary).
            spawnList.add(new ObjectSpawn(x, 0x100, 0x01, 0, 0, false, 0));
        }
        ObjectPlacementController p = new ObjectPlacementController(spawnList, () -> 320);
        p.setWindowingStrategy(strategy);
        for (int cameraX : cameraSequence) {
            p.update(cameraX);
        }
        return p.getActiveSpawns().stream()
                .mapToInt(ObjectSpawn::x)
                .sorted()
                .toArray();
    }

    public ObjectInstance getActiveObjectForRewind(ObjectSpawn spawn) {
        return activeObjects.get(spawn);
    }

    public void addDynamicObject(ObjectInstance object) {
        addDynamicObjectInternal(object, false, true);
    }

    /**
     * True when {@code object} sits in an SST slot this frame's ExecuteObjects walk
     * has already passed, so the object will not run until the next frame.
     *
     * <p>ROM parity: {@code FindFreeObj} scans the SST from the start, so a child
     * allocated by a parent can land BELOW the parent. Callers that need the ROM's
     * one-off catch-up for such a child (see {@code SmashObject}) ask this
     * immediately after spawning it, while the parent is still the executing slot.
     */
    public boolean isSlotAlreadyExecutedThisFrame(ObjectInstance object) {
        if (!updating || currentExecSlot < 0 || !(object instanceof AbstractObjectInstance aoi)) {
            return false;
        }
        int slot = aoi.getSlotIndex();
        if (slot < 0 || !isManagedDynamicSlot(slot)) {
            return false;
        }
        int execIdx = execIndexForSlot(slot);
        return execIdx >= 0 && execIdx <= currentExecSlot;
    }

    public <T extends ObjectInstance> T createDynamicObject(Supplier<T> factory) {
        return ObjectConstructionContext.construct(objectServices, () -> {
            T object = factory.get();
            addDynamicObject(object);
            return object;
        });
    }

    /**
     * Constructs an object in a ROM-selected dynamic SST slot.
     *
     * <p>This is for fixed-slot setup paths, not ordinary {@code AllocateObject}
     * calls. For example, S3K's {@code SpawnLevelMainSprites} writes the AIZ
     * intro controller directly to dynamic object slot 2 (absolute SST slot 6)
     * instead of scanning for the first free slot.
     */
    public <T extends ObjectInstance> T createDynamicObjectAtSlot(
            Supplier<T> factory, int slotIndex) {
        return fixedSstObjects.create(factory, slotIndex);
    }

    /**
     * Constructs a dynamic object after reserving its SST slot.
     * <p>
     * Use only for ROM paths where the parent object already occupies {@code a0}
     * before constructor-time side effects spawn children. Ordinary event and
     * helper spawns should use {@link #createDynamicObject(Supplier)} so existing
     * constructor-time child allocation remains unchanged.
     */
    public <T extends ObjectInstance> T createDynamicObjectWithReservedSlot(Supplier<T> factory) {
        return ObjectConstructionContext.construct(objectServices, () -> {
            int reservedSlot = allocateSlot();
            if (reservedSlot < 0) {
                return null;
            }
            T object;
            try {
                object = factory.get();
            } catch (RuntimeException | Error ex) {
                releaseSlot(reservedSlot);
                throw ex;
            }
            if (object == null) {
                releaseSlot(reservedSlot);
                return null;
            }
            if (object instanceof AbstractObjectInstance aoi && aoi.getSlotIndex() < 0) {
                aoi.setSlotIndex(reservedSlot);
            } else {
                releaseSlot(reservedSlot);
            }
            addDynamicObject(object);
            return object;
        });
    }

    public void removeDynamicObject(ObjectInstance object) {
        if (object == null) {
            return;
        }
        boolean removed = removeDynamicObjectInstance(object);
        if (!removed) {
            return;
        }
        deferredDynamicExecThisFrame.remove(object);
        if (object instanceof AbstractObjectInstance aoi) {
            int slot = aoi.getSlotIndex();
            if (isManagedDynamicSlot(slot)) {
                releaseSlot(slot);
                int execIdx = execIndexForSlot(slot);
                if (execIdx >= 0 && execIdx < execOrder.length) {
                    execOrder[execIdx] = null;
                }
                aoi.setSlotIndex(-1);
            }
        }
        object.onUnload();
        bucketsDirty = true;
        activeObjectsCacheDirty = true;
    }

    private boolean removeDynamicObjectInstance(ObjectInstance object) {
        boolean removed = dynamicObjects.remove(object);
        if (removed) {
            dynamicOwnership.remove(object);
            rewindObjectIds.remove(object);
            notifyObjectManagerRemoval(object);
        }
        return removed;
    }

    private static void notifyObjectManagerRemoval(ObjectInstance object) {
        if (object instanceof AbstractObjectInstance instance) {
            instance.onRemovedFromObjectManager();
        }
    }

    /**
     * Adds a dynamic object using the slot immediately after the current exec slot when
     * called from an object's update, matching ROM AllocateObjectAfterCurrent behavior.
     */
    public void addDynamicObjectAfterCurrent(ObjectInstance object) {
        addDynamicObjectInternal(object, true, true);
    }

    public void addDynamicObjectAfterCurrentNextFrame(ObjectInstance object) {
        addDynamicObjectInternal(object, true, false);
    }

    /**
     * Queues an allocation for the tail of the current Process_Sprites pass.
     * S3K fixed player-effect SSTs execute after Dynamic_object_RAM, so children
     * they create reserve slots only after every ordinary dynamic object has run.
     */
    public void queueDynamicObjectAfterExec(ObjectInstance object) { runtimeState.queueDynamicObjectAfterExec(object); }

    /**
     * Routes an engine-owned power-up object through its native fixed SST slot
     * during the initial pass instead of the managed dynamic fallback.
     */
    public void registerInitialFixedDispatchObject(ObjectInstance object) {
        initialDispatch.registerFixedObject(object);
    }

    public void processInitialFixedDispatchObject(
            InitialObjectDispatchScope scope, ObjectInstance object) {
        initialDispatch.processFixedObject(scope, object);
    }

    public void processInitialFixedDispatchObject(ObjectInstance object) {
        initialDispatch.processFixedObject(object);
    }

    void flushPostExecDynamicSpawns() {
        runtimeState.drainPostExecDynamicSpawns().forEach(this::addDynamicObjectNextFrame);
    }

    /**
     * Adds a dynamic object using AllocateObjectAfterCurrent semantics anchored
     * to an explicit parent slot. Use when ROM code calls
     * AllocateObjectAfterCurrent from a touch/collision callback rather than
     * from this manager's current object-execution cursor.
     */
    public void addDynamicObjectAfterSlot(ObjectInstance object, int parentSlot) {
        addDynamicObjectInternal(object, true, true, parentSlot);
    }

    /**
     * Adds a dynamic object that should reserve a real SST slot immediately but
     * not execute until the next frame.
     * <p>
     * Sonic 1 ExplosionItem uses this path for spawned animal/points children:
     * the slots exist in the same frame's slot dump, but their first update
     * happens on the following ExecuteObjects pass.
     */
    public void addDynamicObjectNextFrame(ObjectInstance object) {
        addDynamicObjectInternal(object, false, false);
    }

    /**
     * Adds an engine-owned auxiliary dynamic object without allocating a ROM SST slot.
     * <p>
     * Use this only for non-standard runtime extensions that must update/render with
     * objects but are not part of the original game's object pool, such as extra
     * sidekick-only overlays. ROM-modeled children, projectiles, effects, shields, and
     * other object routines must continue through {@link #addDynamicObject(ObjectInstance)}
     * or one of the explicit slot-allocation variants so trace-visible slot pressure
     * stays faithful to the original game.
     */
    public void addAuxiliaryDynamicObject(ObjectInstance object) {
        if (dynamicOwnership.addNonRewindable(object, objectServices, dynamicObjects)) {
            bucketsDirty = activeObjectsCacheDirty = true;
        }
    }

    /**
     * Adds a rewind-owned engine extension without consuming a ROM SST slot.
     *
     * <p>Unlike {@link #addAuxiliaryDynamicObject(ObjectInstance)}, this path is
     * included in object rewind snapshots. Use it for extra-player gameplay
     * objects whose lifetime must survive rewind but must not alter native slot
     * pressure.
     */
    public void addRewindableAuxiliaryDynamicObject(ObjectInstance object) {
        if (dynamicOwnership.addRewindable(object, objectServices, dynamicObjects,
                deferredDynamicExecThisFrame, updating,
                candidate -> assignRewindObjectId(candidate, candidate.getSpawn()))) {
            bucketsDirty = activeObjectsCacheDirty = true;
        }
    }

    private void addDynamicObjectInternal(ObjectInstance object,
            boolean allocateAfterCurrent,
            boolean allowSameFrameExec) {
        addDynamicObjectInternal(object, allocateAfterCurrent, allowSameFrameExec, -1);
    }

    private void addDynamicObjectInternal(ObjectInstance object,
            boolean allocateAfterCurrent,
            boolean allowSameFrameExec,
            int explicitParentSlot) {
        if (object instanceof AbstractObjectInstance aoi) {
            aoi.setServices(objectServices);
            // ROM parity: FindFreeObj allocates an SST slot for EVERY object,
            // including children spawned by other objects (lava balls, projectiles,
            // explosion effects, etc.). Without this, child objects don't consume
            // slots in the allocator, causing subsequent OPL allocations to get lower
            // slot numbers than the ROM, shifting d7 values and breaking timing
            // gates like (v_vbla_byte + d7) & 7.
            if (aoi.getSlotIndex() < 0) {
                int slot;
                if (allocateAfterCurrent && explicitParentSlot >= 0) {
                    slot = allocateSlotAfter(explicitParentSlot);
                } else if (allocateAfterCurrent && updating && currentExecSlot >= 0) {
                    slot = allocateSlotAfter(slotIndexForExec(currentExecSlot));
                } else {
                    slot = allocateSlot();
                }
                if (slot >= 0) {
                    aoi.setSlotIndex(slot);
                } else {
                    aoi.setDestroyed(true);
                    return;
                }
            } else {
                // Pre-assigned slot (e.g. from addDynamicObjectAtSlot for badnik
                // replacement). Ensure the slot is marked as used in the allocator —
                // it may have been released when the original object was destroyed.
                int slot = aoi.getSlotIndex();
                slotAllocator.reserveOrMarkUsed(slot);
            }
        }
        assignRewindObjectId(object, object.getSpawn());
        dynamicObjects.add(object);
        if (!allowSameFrameExec && updating) {
            deferredDynamicExecThisFrame.add(object);
            if (object instanceof AbstractObjectInstance aoi2) {
                aoi2.setSkipTouchThisFrame(true);
            }
        }
        if (allowSameFrameExec && updating && object instanceof AbstractObjectInstance aoi2
                && isManagedDynamicSlot(aoi2.getSlotIndex())) {
            // ROM parity: FindFreeObj places the child directly into the SST.
            // The ExecuteObjects loop processes slots sequentially, so a child
            // at a HIGHER slot than the parent will be reached and updated in
            // the same frame. A child at a LOWER slot (already processed) won't
            // run until the next frame.
            int execIdx = execIndexForSlot(aoi2.getSlotIndex());
            if (execIdx < execOrder.length && execIdx > currentExecSlot) {
                object.snapshotPreUpdatePosition();
                aoi2.setSkipTouchThisFrame(true);
                execOrder[execIdx] = object;
            } else {
                // Child placed into a slot at or below the parent's current
                // execution slot: ExecuteObjects has already passed it this frame,
                // so it will not run (nor set obRender bit 7 via DisplaySprite)
                // until the next frame's pass. ROM ReactToItem skips objects whose
                // obRender bit 7 is clear, so this child must stay touch-ineligible
                // for one extra frame relative to a same-frame (higher-slot) child
                // -- i.e. until the frame after its first own execution.
                // (docs/s1disasm/_incObj/sub ReactToItem.asm:50-51)
                aoi2.markAwaitingFirstTouchExecution();
            }
        }
        bucketsDirty = true;
        activeObjectsCacheDirty = true;
    }

    /**
     * Adds a dynamic object at a specific slot index.
     * ROM parity: badnik destruction changes obID in-place, keeping the SST slot.
     */
    public void addDynamicObjectAtSlot(ObjectInstance object, int slotIndex) {
        if (object instanceof AbstractObjectInstance aoi) {
            aoi.setServices(objectServices);
            aoi.setSlotIndex(slotIndex);
        }
        addDynamicObject(object);
    }

    /**
     * Restores a captured dynamic SST occupant without minting a throwaway
     * identity or advancing the next-spawn ordinal.
     */
    void addRestoredDynamicObjectAtSlot(
            ObjectInstance object, int slotIndex, ObjectRefId capturedId) {
        if (capturedId == null) {
            throw new IllegalArgumentException("restored dynamic object identity is required");
        }
        rewindObjectIds.put(object, capturedId);
        addDynamicObjectAtSlot(object, slotIndex);
        collisionResponseList.bindRestoredObject(capturedId, object);
    }

    /**
     * Allocates the next available dynamic slot index for the current game's layout.
     * Equivalent to ROM's FindFreeObj — searches from the first dynamic slot forward.
     * Returns -1 if all slots are in use (overflow).
     */
    private int allocateSlot() {
        return slotAllocator.allocate();
    }

    /**
     * Non-mutating ROM FindFreeObj probe: true if a free dynamic SST slot is
     * available (an {@code allocate()} would succeed). Used by object routines
     * that branch on FindFreeObj success/failure without spawning, for example
     * the S1 LZ drowning countdown retries its bubble RNG when the pool is full
     * (docs/s1disasm/_incObj/0A LZ Drowning Countdown.asm:283-284).
     */
    public boolean hasFreeDynamicSlot() {
        return slotAllocator.hasFreeSlot();
    }

    /** Non-mutating {@code FindFreeObj} result for ROM owner-boundary decisions. */
    public int firstFreeDynamicSlot() {
        return slotAllocator.firstFreeSlot();
    }

    /**
     * Reserves the next available dynamic slot for non-ObjectInstance systems
     * that still occupy ROM SST slots. Equivalent to S3K AllocateObject
     * (sonic3k.asm:37906-37909), used by attracted rings whose logic is owned
     * by {@code RingManager} rather than {@code ObjectManager}.
     */
    public int allocateDynamicSlot() {
        return slotAllocator.allocate();
    }

    /**
     * Returns whether a newly reserved SST slot is behind the live object-loop
     * cursor and therefore cannot execute until the next Process_Sprites pass.
     */
    public boolean reservedSlotWaitsForNextObjectPass(int slotIndex) {
        return placement.reservedSlotWaitsForNextObjectPass(
                slotIndex, updating, currentExecSlot, slotLayout);
    }

    /**
     * Whether a live spilled-ring object currently occupies {@code slotIndex}.
     *
     * <p>Read-only. The ring manager uses it to clear the legacy pool entry that
     * mirrors a {@link com.openggf.level.rings.LostRingObjectInstance} once the
     * object has gone; the pair share one reserved slot, so the object's absence
     * from that slot is the retirement signal. This does not release anything --
     * the slot belongs to the object.
     */
    public boolean hasLiveLostRingAtSlot(int slotIndex) {
        if (slotIndex < 0) {
            return false;
        }
        for (ObjectInstance inst : dynamicObjects) {
            if (inst instanceof com.openggf.level.rings.LostRingObjectInstance ring
                    && !ring.isDestroyed()
                    && ring.getSlotIndex() == slotIndex) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reassigns an already-loaded placement object through the ROM's first-free
     * allocator. This is used when a widened engine placement window materializes
     * an initializer before its native camera gate; once that gate succeeds, the
     * object must occupy the slot it would have received from the native load pass.
     */
    public void reallocateToFirstFreeDynamicSlot(AbstractObjectInstance object) {
        if (placement.reallocateToFirstFreeDynamicSlot(
                object, slotAllocator, slotLayout, execOrder,
                updating, slotsFreedDuringObjectPass)) {
            bucketsDirty = true;
            activeObjectsCacheDirty = true;
        }
    }

    /**
     * Reserves the next free dynamic slot for a deferred S2 Obj37 owner. S2
     * {@code HurtCharacter} calls {@code AllocateObject} before later dynamic slots
     * finish and delete themselves; reserving the owner slot immediately captures
     * the first slot that is free at the ROM hurt instant.
     * Slots already freed earlier in the pass are visible to the ROM allocator, so
     * the scan must use the live allocator state instead of the per-pass freed-slot
     * ledger. {@code Obj37_Init} can then use later holes for child rings via ordinary
     * {@code AllocateObject}
     * (docs/s2disasm/s2.asm:85444-85461, 25125-25146).
     */
    public int allocateDynamicSlotAvoidingCurrentPassFrees() {
        return allocateDynamicSlot();
    }

    /**
     * Re-reserves a specific dynamic slot while restoring a subsystem-owned SST
     * occupant from a rewind snapshot.
     */
    public boolean reserveDynamicSlot(int slotIndex) {
        return slotAllocator.reserve(slotIndex);
    }

    /**
     * Registers a spilled lost-ring object (ROM Obj37) into the dynamic-object exec
     * loop on a slot that was <em>already reserved</em> by the caller via
     * {@link #allocateDynamicSlot()} or {@link #allocateSlotAfter(int)}.
     * <p>
     * Unlike {@link #addDynamicObjectAtSlot}, this does NOT re-allocate or
     * mark-used the slot — {@code RingManager.LostRingPool} reserves the slot
     * up-front so the legacy {@code LostRing[]}
     * twin and the object share the same slot during the parallel cutover stage
     * (total slot consumption unchanged from today).
     * <p>
     * The ring is added to {@link #dynamicObjects} (NOT {@code activeObjects}):
     * rewind capture restores {@code dynamicObjects} entries through the
     * {@code LostRingObjectInstance} generic recreate path, whereas
     * {@code activeObjects} entries are restored through the placement registry —
     * a lost ring placed in {@code activeObjects} would be recreated by the wrong
     * path. {@code execOrder} is wired only when same-frame execution is needed.
     */
    public void spawnLostRingObjectAtSlot(AbstractObjectInstance ring, int reservedSlot) {
        if (ring == null) {
            return;
        }
        ring.setServices(objectServices);
        // Slot is already reserved by RingManager — do NOT re-allocate.
        ring.setSlotIndex(reservedSlot);
        assignRewindObjectId(ring, ring.getSpawn());
        dynamicObjects.add(ring);
        if (updating && isManagedDynamicSlot(reservedSlot)) {
            int execIdx = execIndexForSlot(reservedSlot);
            if (execIdx >= 0 && execIdx < execOrder.length && execIdx > currentExecSlot) {
                ring.snapshotPreUpdatePosition();
                ring.setSkipTouchThisFrame(true);
                execOrder[execIdx] = ring;
            }
        }
        bucketsDirty = true;
        activeObjectsCacheDirty = true;
    }

    /**
     * Registers an Obj37 continuation that has no distinct Java allocator slot.
     * S3K's after-current spill ordering can outlive the engine's managed-slot
     * projection when other native SST occupants are represented by consolidated
     * Java objects. The ring remains ordered logically by the caller, executes in
     * the slotless fallback, and neither consumes nor releases a physical slot.
     */
    public void spawnLogicalLostRingOverflow(AbstractObjectInstance ring) {
        if (ring == null) {
            return;
        }
        ring.setServices(objectServices);
        ring.setSlotIndex(-1);
        assignRewindObjectId(ring, ring.getSpawn());
        dynamicObjects.add(ring);
        bucketsDirty = true;
        activeObjectsCacheDirty = true;
    }

    /**
     * Returns all live objects of the given concrete type, in ascending slot order.
     */
    public <T extends ObjectInstance> List<T> activeObjectsOfType(Class<T> type) {
        return ObjectInstanceQueries.activeObjectsOfType(activeObjects, dynamicObjects, type);
    }

    /**
     * Test helper: reserve dynamic slots (from the front of the pool) until exactly
     * {@code freeSlots} remain free. Used by the spilled-ring atomic-allocation tests
     * to drive {@code allocateSlotAfter} into returning {@code -1} (slot exhaustion)
     * after a known number of successful ring allocations. No-op once the pool already
     * has {@code <= freeSlots} free.
     */
    public void reserveAllButNFreeSlots(int freeSlots) {
        int target = Math.max(0, freeSlots);
        while ((slotLayout.dynamicSlotCount() - slotAllocator.activeCount()) > target) {
            int slot = slotAllocator.allocate();
            if (slot < 0) {
                break;
            }
        }
    }

    /**
     * Allocates the next available dynamic slot AFTER the given parent slot.
     * Equivalent to ROM's FindNextFreeObj — used by segmented objects
     * (Caterkiller body, boss sub-parts) that spawn children into slots
     * immediately following the parent's position in the SST.
     *
     * @param parentSlot the parent object's slot index
     * @return the allocated slot index, or -1 if no slot is available
     */
    public int allocateSlotAfter(int parentSlot) {
        return slotAllocator.allocateAfter(parentSlot);
    }

    /**
     * Releases a previously allocated dynamic slot index.
     */
    private void releaseSlot(int slotIndex) {
        if (updating && isManagedDynamicSlot(slotIndex)) {
            slotsFreedDuringObjectPass.set(slotIndex);
        }
        slotAllocator.release(slotIndex);
    }

    private void releaseSlotIfManaged(ObjectInstance instance) {
        if (instance instanceof AbstractObjectInstance aoi) {
            int slot = aoi.getSlotIndex();
            if (isManagedDynamicSlot(slot)) {
                releaseSlot(slot);
            }
        }
    }

    /**
     * Releases a dynamic slot that was reserved outside the normal object lifecycle.
     * Used by systems such as spilled lost rings that occupy SST slots without
     * existing as {@link ObjectInstance} entries in this manager.
     */
    public void releaseDynamicSlot(int slotIndex) {
        if (!isManagedDynamicSlot(slotIndex)) {
            return;
        }
        releaseSlot(slotIndex);
        int execIdx = execIndexForSlot(slotIndex);
        if (execIdx >= 0 && execIdx < execOrder.length) {
            execOrder[execIdx] = null;
        }
    }

    /**
     * Enables counter-based respawn tracking (S1 v_objstate system).
     */
    public void enableCounterBasedRespawn() {
        placement.enableCounterBasedRespawn();
    }

    /**
     * Enables ROM object-order semantics for games that do not use the S1
     * counter table: ExecuteObjects runs first, then ObjPosLoad materializes
     * newly streamed spawns for the following frame.
     */
    public void enableExecThenLoadPlacement() {
        placement.enableExecThenLoadPlacement();
    }

    /**
     * Enables the permanent destroy-latch on {@code destroyedInWindow}, matching
     * S3K's ROM behavior where bit 7 of {@code Object_respawn_table} stays set
     * for the rest of the level after a player kill (see
     * {@link ObjectPlacementController#permanentDestroyLatch}).
     * <p>
     * Call this once at level setup when the active game module is S3K. S1 and
     * S2 must NOT enable it because their ROM only latches respawn-tracked
     * spawns (modeled by the engine's {@code remembered} flag); non-tracked
     * spawns must be allowed to re-spawn on cursor re-entry.
     */
    public void enablePermanentDestroyLatch() {
        placement.enablePermanentDestroyLatch();
    }

    /**
     * Adjusts the ObjectPlacementController system's tracking state after a camera wrap-back.
     * <p>
     * ROM parity: when Level_repeat_offset is non-zero, the ROM's ObjPosLoad
     * adjusts its cursor boundaries by the wrap distance so that the forward/backward
     * scan sees a continuous camera motion instead of a discontinuous jump.
     * Without this adjustment, the engine's ObjectPlacementController system detects a negative
     * camera delta, triggering a full {@code refreshWindow()} that can re-spawn
     * objects already in the scene (e.g., the AIZ2 end boss during the bombing
     * sequence camera loop).
     *
     * @param wrapDelta the positive distance the camera was moved backward
     */
    public void adjustPlacementTrackingForWrap(int wrapDelta) {
        placement.adjustForWrap(wrapDelta);
    }

    /**
     * Enables slot limit enforcement (ROM FindFreeObj simulation).
     */
    public void enforceSlotLimit() {
        placement.enforceSlotLimit(this::getDynamicObjectCount);
    }

    private int getDynamicObjectCount() {
        return dynamicOwnership.nativeCount(dynamicObjects.size());
    }

    /**
     * Releases this object's own slot back to the pool while keeping the object
     * alive. Used by objects (e.g., ChainedStomper) that need to continue monitoring
     * children after their parent slot should be reusable.
     * <p>
     * ROM parity: In the ROM, each ring calls DeleteObject individually after
     * Ring_Sparkle completes. The parent ring's SST slot is freed when collected,
     * but the obj25 continues monitoring children until they too complete.
     */
    public void releaseSlot(ObjectInstance object) {
        if (object instanceof AbstractObjectInstance aoi) {
            int slot = aoi.getSlotIndex();
            if (isManagedDynamicSlot(slot)) {
                releaseSlot(slot);
                // Clear execOrder so the slot can be reused
                int execIdx = execIndexForSlot(slot);
                if (execIdx >= 0 && execIdx < execOrder.length) {
                    execOrder[execIdx] = null;
                }
                // Clear the object's slot index to prevent double-release.
                // Without this, the object would be placed back into execOrder
                // on the next frame (line ~239), and when eventually destroyed
                // or unloaded, would release the slot AGAIN — potentially
                // corrupting a different object that reused the slot number.
                // With slotIndex=-1, the object falls through to the fallback
                // activeObjects loop for continued updates outside the managed slot window.
                aoi.setSlotIndex(-1);
            }
        }
    }

    /**
     * Releases an object's parent slot independently from any child slots.
     * <p>
     * ROM parity: In S1, Ring_Delete → DeleteObject frees the parent ring's SST
     * slot independently from child rings. The parent and children are separate
     * SST entries with independent lifecycles. This method releases the parent's
     * slot, allowing the object to continue running slotlessly to manage remaining
     * child lifecycles.
     *
     * @param instance the object whose parent slot should be released
     */
    public void releaseParentSlot(AbstractObjectInstance instance) {
        int slot = instance.getSlotIndex();
        if (isManagedDynamicSlot(slot)) {
            releaseSlot(slot);
            instance.setSlotIndex(-1);
        }
    }

    /**
     * Frees a reserved child slot for an object that used getReservedChildSlotCount().
     * <p>
     * ROM parity: In S1, each child object calls DeleteObject after its sequence
     * completes, freeing its SST slot. This method replicates that slot release
     * for objects using the reserved child slot mechanism (e.g., ChainedStomper).
     *
     * @param spawn the parent object's spawn (used as key for the child slot array)
     * @param index the child index (0-based) to free
     */
    public void freeReservedChildSlot(ObjectSpawn spawn, int index) {
        int[] childSlots = reservedChildSlots.get(spawn);
        if (childSlots != null && index >= 0 && index < childSlots.length) {
            int slot = childSlots[index];
            if (isManagedDynamicSlot(slot)) {
                releaseSlot(slot);
                childSlots[index] = -1; // Mark as freed
            }
        }
    }

    /**
     * Adds a dynamic child object using a pre-allocated reserved slot.
     * <p>
     * ROM parity: when ring parent objects spawn children during ExecuteObjects,
     * those children must occupy the same slot numbers allocated during the
     * parent's update via {@link #allocateChildSlots(ObjectSpawn, int)}.
     * This method places the child into the pre-allocated slot at {@code childIndex},
     * replacing the phantom reservation with a real object.
     *
     * @param object      the child object to add
     * @param parentSpawn the parent's spawn (key for the reserved slot table)
     * @param childIndex  which reserved child slot to use (0-based)
     */
    public void addDynamicObjectToReservedSlot(ObjectInstance object, ObjectSpawn parentSpawn, int childIndex) {
        int[] childSlots = reservedChildSlots.get(parentSpawn);
        if (childSlots != null && childIndex >= 0 && childIndex < childSlots.length) {
            int reservedSlot = childSlots[childIndex];
            if (isManagedDynamicSlot(reservedSlot)) {
                if (object instanceof AbstractObjectInstance aoi) {
                    aoi.setServices(objectServices);
                    aoi.setSlotIndex(reservedSlot);
                    // Slot is already marked used in the allocator from pre-allocation;
                    // no need to call allocateSlot() again.
                }
                // Mark slot as consumed in the reservation table so freeAllReservedChildSlots
                // won't double-free this slot (the real child object now owns it).
                childSlots[childIndex] = -1;
                assignRewindObjectId(object, object.getSpawn());
                dynamicObjects.add(object);
                if (updating) {
                    int execIdx = execIndexForSlot(reservedSlot);
                    if (execIdx >= 0 && execIdx < execOrder.length && execIdx > currentExecSlot) {
                        object.snapshotPreUpdatePosition();
                        execOrder[execIdx] = object;
                    }
                }
                bucketsDirty = true;
                activeObjectsCacheDirty = true;
                return;
            }
        }
        // Fallback: no pre-allocated slot, use normal allocation.
        // Record a consumed sentinel in the reservation table so that
        // subsequent calls won't attempt to allocate phantom slots for this
        // parent in later frames.
        if (childSlots == null) {
            reservedChildSlots.put(parentSpawn, new int[]{-1});
        }
        addDynamicObject(object);
    }

    /**
     * Allocates reserved child slots for an object during the exec loop.
     * <p>
     * ROM parity: In S1, Ring_Main runs during ExecuteObjects and allocates
     * child ring slots via FindFreeObj. This method should be called from
     * the object's first update() to match the ROM's allocation timing.
     * ObjPosLoad allocates parent slots BEFORE ExecuteObjects runs, but
     * child slots are allocated DURING ExecuteObjects.
     *
     * @param spawn the parent object's spawn
     * @param childCount number of child slots to allocate
     * @return the allocated slot indices (may contain -1 for failed allocations)
     */
    public int[] allocateChildSlots(ObjectSpawn spawn, int childCount) {
        // Guard: if already allocated, return existing
        int[] existing = reservedChildSlots.get(spawn);
        if (existing != null) {
            return existing;
        }
        int[] childSlots = new int[childCount];
        for (int c = 0; c < childCount; c++) {
            childSlots[c] = allocateSlot();
        }
        reservedChildSlots.put(spawn, childSlots);
        return childSlots;
    }

    /**
     * Allocates child slots starting from the given parent slot, matching ROM's
     * FindNextFreeObj which scans from the parent's slot forward. Used by objects
     * like CStom_MakeParts that create children in slots adjacent to the parent.
     *
     * @param spawn      the parent's ObjectSpawn (for tracking)
     * @param childCount number of child slots to allocate
     * @param parentSlot the parent object's slot index
     * @return the allocated slot indices (may contain -1 for failed allocations)
     */
    public int[] allocateChildSlotsAfter(ObjectSpawn spawn, int childCount, int parentSlot) {
        int[] childSlots = new int[childCount];
        int lastSlot = parentSlot;
        for (int c = 0; c < childCount; c++) {
            childSlots[c] = allocateSlotAfter(lastSlot);
            if (childSlots[c] >= 0) {
                lastSlot = childSlots[c]; // Next child starts after this one
            }
        }
        reservedChildSlots.put(spawn, childSlots);
        return childSlots;
    }

    /**
     * Returns peak dynamic slot count seen during this level.
     */
    public int getPeakSlotCount() {
        return slotAllocator.peakSlotCount();
    }

    public int getAllocatedSlotCount() {
        return slotAllocator.activeCount();
    }

    public int getLastDynamicSlotExclusive() {
        return slotLayout.lastDynamicSlotExclusive();
    }

    public int getLastProcessSlotExclusive() {
        return slotLayout.lastProcessSlotExclusive();
    }

    public boolean preallocatesLostRingOwnerSlot() {
        return slotLayout.preallocatesLostRingOwnerSlot();
    }

    public boolean lostRingRemainderAllocatesAfterOwnerSlot() {
        return slotLayout.lostRingRemainderAllocatesAfterOwnerSlot();
    }

    /**
     * Frees all reserved child slots for a given spawn, removing the tracking entry.
     * Called when the parent object is destroyed or unloaded.
     */
    private void freeAllReservedChildSlots(ObjectSpawn spawn) {
        int[] childSlots = reservedChildSlots.remove(spawn);
        if (childSlots != null) {
            for (int slot : childSlots) {
                if (isManagedDynamicSlot(slot)) {
                    releaseChildSlotAtItsOwnExecPosition(slot);
                }
            }
        }
    }

    /**
     * Releases a reserved child slot at the position its ROM object would have
     * reached in the ascending {@code ExecuteObjects} walk.
     *
     * <p>ROM parity: an engine instance that consolidates a ROM parent plus its
     * {@code FindNextFreeObj} children is <em>one</em> object holding several SST
     * slots, but in ROM each of those slots holds a separate object with its own
     * {@code out_of_range ...,DeleteObject} tail. {@code ExecuteObjects} walks the
     * SST in ascending slot order (docs/s1disasm/_inc/ExecuteObjects.asm:10-30), so
     * a group whose blocks share one origin (S1 {@code Staircase} feeds every block
     * {@code stair_origX(a0)}, docs/s1disasm/_incObj/"5B SLZ Staircase.asm":6-12,
     * 39-66) goes out of range on the same frame but frees each slot at that
     * slot's own position in the walk -- the parent slot early, the child slots
     * later. Allocations made in between (the parent-slot {@code FindNextFreeObj}
     * of a group loaded further right, {@code Ring_Main}'s per-ring
     * {@code FindFreeObj}) therefore still see the child slots as occupied.
     *
     * <p>Freeing every child slot at the parent's position made those in-between
     * allocations land in slots ROM still had filled (SLZ1 f2522: the 0xE90
     * staircase took 51/52/54 instead of ROM's 56/57/58, shifting the 0xEC0
     * group's four slots down by three).
     *
     * <p>Outside the exec pass, or for a slot the walk has already passed this
     * frame, the release is immediate -- ROM has no remaining position in this
     * frame's walk at which to free it.
     */
    private void releaseChildSlotAtItsOwnExecPosition(int slot) {
        if (updating) {
            int execIndex = execIndexForSlot(slot);
            if (execIndex > currentExecSlot && execIndex < execOrder.length) {
                pendingChildSlotRelease.set(execIndex);
                return;
            }
        }
        releaseSlot(slot);
    }

    /**
     * Initializes the VBla frame counter to match ROM's v_vbla_byte at trace start.
     */
    public void initVblaCounter(int initialValue) {
        this.vblaCounter = initialValue;
    }

    /**
     * Advances the VBla counter by one, mirroring the ROM's V-int-exit
     * increment ({@code docs/s2disasm/s2.asm:508},
     * {@code docs/s1disasm/sonic.asm:682},
     * {@code docs/skdisasm/sonic3k.asm:543}).
     *
     * <p>This is the single mutation point for the counter. Call it exactly
     * once per serviced V-blank -- see the invariant on the
     * {@code vblaCounter} field. Rows that run the level loop get their tick
     * from {@link #update}; V-blank-only rows must call this directly.
     */
    public void advanceVblaCounter() {
        this.vblaCounter++;
    }

    /**
     * Returns the current VBla counter value.
     */
    public int getVblaCounter() {
        return vblaCounter;
    }

    /**
     * Initializes the low-bit V-int phase observed by spilled-ring floor probes.
     * Trace recorders capture this independently because legacy S3K CSV fixtures
     * stored the adjacent V-int word rather than {@code V_int_run_count+2}.
     */
    public void initRingFloorCheckCounterPhase(int phase) { runtimeState.initRingFloorCheckCounterPhase(phase); }

    public void inheritRingFloorCheckCounterPhase(int phase) { runtimeState.inheritRingFloorCheckCounterPhase(phase); }

    public int getRingFloorCheckCounterPhase() { return runtimeState.ringFloorCheckCounterPhase(); }

    public boolean hasInheritedRingCounterPhase() { return runtimeState.hasInheritedRingCounterPhase(); }

    /**
     * Sets the phase difference between the general object-update clock and
     * S3K's {@code V_int_run_count}. They normally advance together, but old
     * trace schemas captured the adjacent life-count word instead of the
     * run counter and therefore need an independently reconstructed low bit.
     */
    public void initVIntRunCounterPhaseOffset(int phaseOffset) {
        vIntRunCounterPhaseOffset = phaseOffset;
    }

    public int getVIntRunCounterPhaseOffset() {
        return vIntRunCounterPhaseOffset;
    }

    public boolean isRemembered(ObjectSpawn spawn) {
        return placement.isRemembered(spawn);
    }

    public boolean isDormant(ObjectSpawn spawn) {
        return placement.isDormant(spawn);
    }

    public int getSpawnCounter(ObjectSpawn spawn) {
        return placement.getCounterForSpawn(spawn);
    }

    public boolean isSpawnStateBitSet(ObjectSpawn spawn, int bit) {
        return placement.isCounterStateBitSet(spawn, bit);
    }

    public void setSpawnStateBit(ObjectSpawn spawn, int bit) {
        placement.setCounterStateBit(spawn, bit);
    }

    public void clearSpawnCounterActiveBit(ObjectSpawn spawn) {
        placement.clearCounterForSpawn(spawn);
    }

    /**
     * Mirrors an object-local S1 {@code bclr #7,2(a2,d0.w)} while preserving
     * ObjPosLoad cursor cadence until the cursor naturally reprocesses the entry.
     */
    public void clearSpawnCounterActiveBitAndMarkDormant(ObjectSpawn spawn) {
        if (!placement.isCounterBasedRespawn()) {
            return;
        }
        placement.clearCounterForSpawn(spawn);
        placement.markDormant(spawn);
    }

    public void markRemembered(ObjectSpawn spawn) {
        // Look up the instance to check if it should stay active.
        // activeObjects is an IdentityHashMap so try identity first.
        ObjectInstance instance = activeObjects.get(spawn);
        if (instance == null) {
            // Fallback: scan by equals() in case the caller's spawn reference
            // differs from the canonical key stored in the IdentityHashMap.
            for (Map.Entry<ObjectSpawn, ObjectInstance> entry : activeObjects.entrySet()) {
                if (entry.getKey().equals(spawn)) {
                    instance = entry.getValue();
                    break;
                }
            }
        }
        if (instance != null) {
            placement.markRemembered(spawn, instance);
        } else {
            placement.markRemembered(spawn);
        }
    }

    public void clearRemembered() {
        placement.clearRemembered();
    }

    /**
     * Removes a spawn from the active set without marking it as remembered.
     * The spawn can still respawn when the camera leaves and re-enters the area.
     * Used for badniks which should respawn on camera re-entry but not immediately.
     */
    public void removeFromActiveSpawns(ObjectSpawn spawn) {
        placement.removeFromActive(spawn);
    }

    /**
     * Clears a placement's loaded/active bit while leaving its current object
     * instance alive.
     * <p>
     * Some ROM objects transform their original SST slot into a fragment and
     * then clear {@code respawn_addr} bit 7. Object placement may therefore
     * create a fresh copy while the transformed instance is still falling.
     * This is deliberately different from {@link #removeFromActiveSpawns},
     * which records an in-window destruction latch.
     */
    public void releaseSpawnForRespawn(ObjectInstance transformedInstance, ObjectSpawn spawn) {
        if (placement.releaseSpawnForRespawn(transformedInstance, spawn,
                activeObjects, instanceToSpawn, dynamicObjects)) {
            bucketsDirty = true;
            activeObjectsCacheDirty = true;
        }
    }

    /**
     * The player's centre Y at the start of this frame's object exec pass —
     * post-physics, before any object re-seated him. Objects whose ROM slot runs
     * before a player-re-seating object (e.g. the SLZ Fan reading Sonic's Y
     * before the higher-slot staircase ride-seat lifts him) should read this
     * instead of the live centre Y, so the engine's folded-into-a-lower-slot
     * re-seating object does not feed them an already-updated position. Falls
     * back to the live centre Y when no snapshot exists (player absent at
     * exec-start, e.g. spawned mid-pass).
     */
    public int getPlayerCentreYAtExecStart(PlayableEntity player) {
        return solidContacts.getPlayerCentreYAtExecStart(player);
    }

    /** Is this player riding any object? */
    public boolean isRidingObject(PlayableEntity player) {
        return solidContacts.isRidingObject(player);
    }

    /** Is this specific player riding this specific object? */
    public boolean isRidingObject(PlayableEntity player, ObjectInstance instance) {
        return solidContacts.isPlayerRiding(player, instance);
    }

    /** Returns whether this object currently owns ROM's standing bit for the player. */
    public boolean hasObjectStandingBit(PlayableEntity player, ObjectInstance instance) {
        return solidContacts.hasStandingLatch(player, instance);
    }

    /**
     * Returns whether a solid object's native per-player pushing latch is live.
     * This distinguishes a Status_Push bit owned by SolidObject processing from
     * stale player-only residue without inspecting an object id or zone.
     */
    public boolean hasObjectPushingBit(PlayableEntity player) {
        return solidContacts.hasPushingLatch(player);
    }

    /** Is ANY player riding anything? */
    public boolean isAnyPlayerRiding() {
        return solidContacts.isAnyPlayerRiding();
    }

    /** Is ANY player riding this specific object? */
    public boolean isAnyPlayerRiding(ObjectInstance instance) {
        return solidContacts.isAnyPlayerRiding(instance);
    }

    public boolean isActiveObjectInstance(ObjectInstance instance) {
        return instance != null && activeObjects.containsValue(instance);
    }

    /** Clear this player's riding state. */
    public void clearRidingObject(PlayableEntity player) {
        solidContacts.clearRidingObject(player);
    }

    /**
     * Run the shared ROM {@code CheckPlayerReleaseFromObj} terrain-release
     * operation for the player's current solid-object ride.
     */
    public boolean checkPlayerReleaseFromObjectFloor(PlayableEntity player) {
        return solidContacts.checkPlayerReleaseFromObjectFloor(player);
    }

    /**
     * Clear support established by an earlier solid slot after a later controller
     * object has made the player airborne in the same ExecuteObjects pass.
     */
    public void clearRidingObjectAfterControllerAirborneRelease(
            PlayableEntity player, ObjectInstance formerSupport) {
        solidContacts.clearRidingObjectAfterControllerAirborneRelease(player, formerSupport);
    }

    /**
     * One-time native bootstrap for route starts that begin with the player
     * already riding a ROM solid object.
     *
     * <p>This must only be used before the first replay/gameplay frame. It
     * publishes the same standing/riding state that SolidObject would have
     * established during the title-card object prelude; it does not copy
     * per-frame trace comparison data into the engine.
     */
    public void forceRidingObjectForBootstrap(PlayableEntity player, ObjectInstance instance) {
        solidContacts.forceRidingObjectForBootstrap(player, instance);
    }

    /**
     * Clear riding state for Sonic_Jump's status-bit release.
     * <p>
     * Most engine ride records can be removed immediately. Objects whose ROM
     * routine still applies {@code MvSonicOnPtfm2} after {@code ExitPlatform}
     * keep the record until their own inline checkpoint consumes the release.
     */
    public void clearRidingObjectForJump(PlayableEntity player) {
        solidContacts.clearRidingObjectForJump(player);
    }

    public void forceAirOnStaleObjectSupportLoss(PlayableEntity player) {
        solidContacts.forceAirOnStaleObjectSupportLoss(player);
    }

    public boolean hasPendingStaleObjectSupportLoss(PlayableEntity player) { return solidContacts.hasPendingStaleObjectSupportLoss(player); }

    public ObjectSolidContactController solidContacts() { return solidContacts; }

    /**
     * Preserves a non-solid object's ownership of the player's on-object state for the
     * current inline ExecuteObjects pass.
     * <p>
     * Some ROM objects (for example S2 Obj06 spiral) set {@code status.player.on_object}
     * without going through SolidObject. Inline solid cleanup must not clear that state at
     * frame end, so these objects can mark the player as supported for the current pass.
     */
    public void markObjectSupportThisFrame(PlayableEntity player) {
        solidContacts.markObjectSupportThisFrame(player);
    }

    /**
     * Get the object that this player is currently standing on (riding).
     * Used for balance detection at object edges.
     *
     * @param player The player to check
     * @return The object being ridden, or null if not standing on any object
     */
    public ObjectInstance getRidingObject(PlayableEntity player) {
        return solidContacts.getRidingObject(player);
    }

    /**
     * Get the piece index this player is riding on a multi-piece object, or -1.
     * Used for balance detection at piece edges (e.g., CPZ Staircase).
     */
    public int getRidingPieceIndex(PlayableEntity player) {
        return solidContacts.getRidingPieceIndex(player);
    }

    public boolean hasStandingContact(PlayableEntity player) {
        return solidContacts.hasStandingContact(player);
    }

    public int getHeadroomDistance(PlayableEntity player, int hexAngle) {
        return solidContacts.getHeadroomDistance(player, hexAngle);
    }

    /** Player y-radius captured before the currently executing solid checkpoint. */
    public int getPreContactYRadius() {
        return solidContacts.getPreContactYRadius();
    }

    public boolean latestStandingSnapshot(PlayableEntity player) {
        return solidContacts.latestStandingSnapshot(player);
    }

    public boolean hasGroundingObjectSupport(PlayableEntity player) {
        return solidContacts.hasGroundingObjectSupport(player);
    }

    public int latestHeadroomSnapshot(PlayableEntity player, int hexAngle) {
        return solidContacts.latestHeadroomSnapshot(player, hexAngle);
    }

    /**
     * Run solid contacts resolution for a player sprite.
     * This is called by the CollisionSystem as part of the unified collision pipeline.
     */
    public void updateSolidContacts(PlayableEntity player) {
        solidContacts.setDeferSideToPostMovement(false);
        solidContacts.update(player, false);
    }

    /**
     * Update solid contacts with an explicit post-movement flag. When {@code postMovement}
     * is true, the velocity classification adjustment is skipped because the player's
     * position already reflects their velocity (movement has already happened).
     * <p>Used by the S1 UNIFIED model where solid objects run AFTER Sonic's movement,
     * unlike S2/S3K where solid contacts run before movement.
     *
     * @param deferSideToPostMovement when true, side collision effects (speed zeroing,
     *     position correction) are skipped in this pass because a post-movement pass will
     *     handle them. This is used for the pre-movement pass in S1 UNIFIED, where the ROM
     *     processes solid objects AFTER Sonic's movement — side collisions at the pre-movement
     *     position are spurious.
     */
    public void updateSolidContacts(PlayableEntity player, boolean postMovement,
                                     boolean deferSideToPostMovement) {
        solidContacts.setDeferSideToPostMovement(deferSideToPostMovement);
        solidContacts.update(player, postMovement);
    }

    /**
     * Resolves a newly-created ROM helper's inline solid checkpoint immediately.
     * <p>
     * Some S3K event routines allocate an object from a background/event path after
     * this engine's normal object pass has already run for the frame, while the ROM
     * object still executes its {@code SolidObjectTop} call in that same frame. The
     * AIZ transition floor is allocated at docs/skdisasm/sonic3k.asm:104685-104687,
     * then its object routine calls {@code SolidObjectTop} at 104777-104790.
     */
    public void processImmediateInlineSolidCheckpoint(ObjectInstance object,
            PlayableEntity player, List<? extends PlayableEntity> sidekicks) {
        if (object == null) {
            return;
        }
        List<? extends PlayableEntity> activeSidekicks = sidekicks != null ? sidekicks : List.of();
        solidContacts.processCompatibilityCheckpoint(object, player, activeSidekicks, true);
    }

    /**
     * Refresh the ObjectSolidContactController riding tracking position for an object after it has moved itself.
     * Prevents the delta from that movement being double-applied to riding players.
     */
    public void refreshRidingTrackingPosition(ObjectInstance object) {
        solidContacts.refreshRidingTrackingPosition(object);
    }

    /**
     * Pre-contact player X speed, captured before solid contact resolution zeroes it.
     * ROM: objects save player velocity BEFORE SolidObjectFull (e.g. Obj_AIZLRZEMZRock $30(a0)).
     */
    public short getPreContactXSpeed() { return solidContacts.getPreContactXSpeed(); }

    /** Pre-contact player Y speed. */
    public short getPreContactYSpeed() { return solidContacts.getPreContactYSpeed(); }

    /** Pre-contact player rolling state, before landing clears it. */
    public boolean getPreContactRolling() { return solidContacts.getPreContactRolling(); }

    /** Pre-contact player animation ID, before solid contact resolution can change it. */
    public int getPreContactAnimationId() { return solidContacts.getPreContactAnimationId(); }

    public TouchResponseDebugState getTouchResponseDebugState() {
        return touchResponses != null ? touchResponses.getDebugState() : null;
    }

    boolean hadSpecialTouchThisFrame(PlayableEntity player) {
        return touchResponses != null && touchResponses.hadSpecialTouchThisFrame(player);
    }

    /**
     * Phase 1 of spawn window sync for non-counter placement.
     * <p>
     * For S2/S3K-style ObjectPlacementController, {@code activeSpawns} only tracks the current
     * ObjPosLoad candidate window. Live objects are retired by their own
     * RememberState/out_of_range-equivalent code paths during execution, not by
     * leaving the spawn candidate set.
     */
    private void syncActiveSpawnsUnload() {
        // Intentionally empty. For non-counter ObjectPlacementController, live objects are kept
        // alive until their own execution tail path retires them.
        /*
        Iterator<Map.Entry<ObjectSpawn, ObjectInstance>> iterator = activeObjects.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<ObjectSpawn, ObjectInstance> entry = iterator.next();
            ObjectSpawn spawn = entry.getKey();
            ObjectInstance instance = entry.getValue();

            boolean removedFromPlacement = !activeSpawns.contains(spawn);
            // ROM parity: do NOT run a second out-of-range check here for S1.
            // The ROM only checks out_of_range during ExecuteObjects (each
            // object's own RememberState call). The centralized check here
            // was causing 20+ extra unloads during camera backtracking because
            // it runs BEFORE objects execute — objects that would survive the
            // ROM's single check (by having moved closer to Sonic) get killed
            // before they get a chance to update their position this frame.
            boolean outOfRange = false;

            if ((removedFromPlacement || outOfRange) && !instance.isPersistent()) {
                // Release the SST slot so it can be reused by future objects
                if (instance instanceof AbstractObjectInstance aoi) {
                    int slot = aoi.getSlotIndex();
                    if (slot >= 0) {
                        releaseSlot(slot);
                    }
                }
                // Also release any reserved child slots for this spawn
                freeAllReservedChildSlots(spawn);
                // ROM parity: RememberState clears bit 7 when the object
                // actually unloads (goes off-screen). This is the engine's
                // equivalent — the ObjectInstance is being released because
                // it left the screen. Destroyed objects are handled separately
                // (removeFromActive path) and never reach here, so their bits
                // stay set.
                if (counterBased) {
                    placement.clearCounterForSpawn(spawn);
                }
                if (outOfRange) {
                    placement.removeFromActiveForUnload(spawn);
                }
                instance.onUnload();
                instanceToSpawn.remove(instance);
                iterator.remove();
                changed = true;
            }
        }

        if (changed) {
            bucketsDirty = true;
            activeObjectsCacheDirty = true;
        }
        */
    }

    /**
     * Frees slots of dynamic objects explicitly marked as destroyed.
     * <p>
     * Called after {@link #syncActiveSpawnsUnload()} to release slots held by
     * dynamic children whose parent's {@code onUnload()} set them to destroyed.
     * This is a targeted pass — only objects with {@code isDestroyed() == true}
     * are freed, matching the ROM's ExecuteObjects behavior where these objects
     * would delete themselves before ObjPosLoad runs.
     */
    void cleanupDestroyedDynamicObjects() {
        boolean changed = false;
        Iterator<ObjectInstance> iter = dynamicObjects.iterator();
        while (iter.hasNext()) {
            ObjectInstance inst = iter.next();
            if (inst.isDestroyed()) {
                if (inst instanceof AbstractObjectInstance aoi) {
                    int slot = aoi.getSlotIndex();
                    if (slot >= 0) {
                        releaseSlot(slot);
                    }
                }
                inst.onUnload();
                solidContacts.evictLatchForDestroyedInstance(inst);
                dynamicOwnership.remove(inst);
                rewindObjectIds.remove(inst);
                notifyObjectManagerRemoval(inst);
                iter.remove();
                changed = true;
            }
        }
        if (changed) {
            bucketsDirty = true;
            activeObjectsCacheDirty = true;
        }
    }

    /**
     * Pure-function limit for the viewport-scaled {@code out_of_range} variant:
     * {@code 128 (behind-camera) + viewportWidth (screen) + 192 (ahead)}.
     * <p>
     * At native viewport width (320 px, {@code DISPLAY_ASPECT = NATIVE_4_3}) this
     * returns exactly {@code 640}, reproducing the ROM {@code cmpi.w #$280,d0}
     * constant bit-for-bit.  At widescreen widths the limit widens with the
     * configured viewport so objects near the visible right edge are not
     * incorrectly despawned (declared divergence — see
     * docs/status/known-discrepancies.md "Object Despawn and Visibility Windows", entry #14).
     * <p>
     * Used by {@link #isOutOfRangeS1} for non-S1-counter placement and mirrored
     * in {@link AbstractObjectInstance#isInRange()}.
     */
    private static final int S1_NATIVE_OUT_OF_RANGE_LIMIT = 128 + 320 + 192;

    static int outOfRangeLimit(int viewportWidth) {
        return 128 + viewportWidth + 192;
    }

    /**
     * ROM parity: S1 {@code out_of_range} macro (Macros.asm line 261).
     * <p>
     * Computes unsigned 16-bit distance between object and screen position:
     * <pre>
     *   d0 = obX & 0xFF80
     *   d1 = (v_screenposx - 128) & 0xFF80
     *   distance = (d0 - d1) & 0xFFFF   (unsigned 16-bit)
     *   out_of_range when distance > limit  (bhi = unsigned greater)
     * </pre>
     * S1 counter-based placement uses the ROM's fixed limit
     * {@code 128 + 320 + 192}; other legacy paths keep the viewport-scaled
     * declared divergence so objects near a wider visible right edge are not
     * incorrectly despawned (see KNOWN_DISCREPANCIES.md entry #14
     * "Object Despawn and Visibility Windows").
     * Catches both left (negative wraps to large unsigned) and right out of range.
     */
    private boolean isOutOfRangeS1(int objX, int cameraX) {
        int objRounded = objX & 0xFF80;
        int screenRounded = (cameraX - 128) & 0xFF80;
        int distance = (objRounded - screenRounded) & 0xFFFF;
        int limit = placement.isCounterBasedRespawn()
                ? S1_NATIVE_OUT_OF_RANGE_LIMIT
                : outOfRangeLimit(camera.getWidth());
        return distance > limit;
    }

    /**
     * Shared out-of-range delete decision used by the standard (non-custom)
     * unload path.
     * <p>
     * S2 routes the per-instance off-screen unload through the ROM object-side
     * {@code MarkObjGone} window (the injected {@link ObjectWindowingStrategy},
     * base {@code (Camera_X_pos - $80) & $FF80}, first deleting bucket {@code $300};
     * docs/s2disasm/s2.asm MarkObjGone). S1/S3K use the {@link ObjectWindowingStrategy#LEGACY}
     * strategy and keep the S1 {@code out_of_range} macro ({@link #isOutOfRangeS1}).
     * The two share the same reference X.
     * <p>
     * <b>Coordinate semantics:</b> ROM {@code MarkObjGone} (and {@code out_of_range})
     * read {@code x_pos(a0)} — the object's ROM centre X. Both branches consume
     * {@link #outOfRangeReferenceX(ObjectInstance, ObjectSpawn)} (the object's
     * explicit ROM reference X, defaulting to its centre-aligned {@code getX()}),
     * never a sprite top-left bound, so the window is not shifted by half-width.
     */
    private boolean isObjectOutOfRange(ObjectInstance instance, ObjectSpawn spawn, int cameraX) {
        int referenceX = outOfRangeReferenceX(instance, spawn);
        if (windowingStrategy.overridesUnloadWindow()) {
            return windowingStrategy.isOutsideUnloadWindow(referenceX, cameraX);
        }
        return isOutOfRangeS1(referenceX, cameraX);
    }

    /**
     * ROM parity dispatcher for the destroy-from-active path.
     *
     * <p>When an object self-destroys via an off-screen check
     * (Sprite_OnScreen_Test family in sonic3k.asm -- see loc_1B5A0 at
     * sonic3k.asm:37271), ROM clears bit 7 of the respawn-table entry
     * ({@code bclr #7,(a2)} at sonic3k.asm:37275) so the ObjectPlacementController system
     * can re-spawn the object when the camera returns. The engine mirrors
     * this by routing those destroys to {@link ObjectPlacementController#removeFromActiveForUnload}
     * which leaves {@code destroyedInWindow} cleared.
     *
     * <p>All other destroy reasons (player kills via Touch_EnemyNormal /
     * Obj_Explosion, monitor breaks, etc.) latch through
     * {@link ObjectPlacementController#removeFromActive} so {@code permanentDestroyLatch}
     * (S3K) can lock the spawn out for the rest of the level. This matches
     * ROM's loc_1BA40 / loc_1BA64 pattern where bit 7 stays set after
     * routing through {@code Delete_Current_Sprite} without going through
     * Sprite_OnScreen_Test.
     */
    private void dispatchDestroyRemoveFromActive(ObjectInstance instance, ObjectSpawn spawn) {
        boolean latchedDestroy = placement.dispatchDestroyRemoveFromActive(
                instance, spawn, slotLayout == ObjectSlotLayout.SONIC_2);
        if (latchedDestroy) {
            solidContacts.evictLatchForDestroyedSpawn(spawn);
        }
    }

    /**
     * Mirror of the S1 {@code RememberState}/{@code Cat_Despawn} respawn-table
     * bit-7 clear for objects that delete themselves off-screen instead of
     * routing through {@link #unloadCounterBasedOutOfRange}.
     * <p>
     * Some S1 objects own their {@code out_of_range} tail (they set
     * {@link ObjectInstance#usesCustomOutOfRangeCheck()} with a no-op
     * {@link ObjectInstance#isCustomOutOfRange(int)}) and instead call
     * {@code setDestroyedByOffscreen()} from their own routine — e.g. the
     * Caterkiller, whose {@code Cat_Despawn} (docs/s1disasm/_incObj/78 Badnik -
     * Caterkiller.asm:139-148) runs after its fragment-entry check and clears
     * {@code bit 7} of its {@code v_objstate} entry so the placement cursor
     * re-spawns it when the player returns. Those self-deletes reach the
     * destroyed-removal path, which previously only called
     * {@code clearStayActive} and left the counter bit latched, so the badnik
     * never respawned. Reset the placement counter exactly as the
     * out_of_range path does, but only for off-screen self-deletes
     * ({@code destroyedRespawnable}); player kills keep the bit set and must
     * not respawn.
     */
    private void resetRespawnStateForOffscreenSelfDelete(ObjectInstance instance, ObjectSpawn spawn) {
        if (spawn == null
                || !placement.isCounterBasedRespawn()
                || !instance.isDestroyedRespawnable()
                || !instance.clearsRespawnStateOnCounterBasedOutOfRange()) {
            return;
        }
        placement.clearCounterForSpawn(spawn);
        // ROM parity: bit 7 is cleared but the cursor may still sit between this
        // spawn's entry and the window edge, so keep it dormant until ObjPosLoad
        // reprocesses it (matches unloadCounterBasedOutOfRange).
        placement.markDormant(spawn);
    }

    private boolean unloadCounterBasedOutOfRange(ObjectInstance instance, ObjectSpawn spawn,
            int expectedSlotIndex, int cameraX) {
        ObjectSpawn positionSpawn = spawn != null ? spawn : instance.getSpawn();
        // Fallback dynamic children may exist briefly without any spawn-backed
        // identity. They still need update() calls, but cannot participate in
        // S1's out_of_range unload check.
        if (positionSpawn == null) {
            return false;
        }
        boolean persistent;
        try {
            persistent = instance.isPersistent();
        } catch (NullPointerException e) {
            throw new IllegalStateException(describeCounterBasedUnloadObject(instance, spawn), e);
        }
        boolean outOfRange = instance.usesCustomOutOfRangeCheck()
                ? instance.isCustomOutOfRange(cameraX)
                : isObjectOutOfRange(instance, spawn, cameraX);
        if (persistent || !outOfRange) {
            return false;
        }
        if (instance instanceof AbstractObjectInstance aoi) {
            int slotIndex = aoi.getSlotIndex();
            // expectedSlotIndex identifies WHICH exec entry is being retired, so it
            // must be matched against the instance's EXECUTION slot, not its own SST
            // slot. The two differ for parents that run from a reserved child slot
            // (ROM Stair_Main keeps the parent block in a0 while its three children
            // occupy the FindNextFreeObj slots after it,
            // docs/s1disasm/_incObj/5B SLZ Staircase.asm:30-66), and comparing against
            // getSlotIndex() silently skipped the release for exactly those objects --
            // leaking one SST slot per staircase unload for the rest of the act.
            if (isManagedDynamicSlot(slotIndex)
                    && (expectedSlotIndex < 0
                            || executionSlotIndex(aoi) == expectedSlotIndex)) {
                releaseSlot(slotIndex);
            }
        }
        instance.onUnload();
        if (spawn != null) {
            freeAllReservedChildSlots(spawn);
            boolean clearsRespawnState = instance.clearsRespawnStateOnCounterBasedOutOfRange();
            if (clearsRespawnState) {
                placement.clearCounterForSpawn(spawn);
                // ROM parity: RememberState clears bit 7 but the cursor may
                // still be between this spawn's entry and the current window
                // edge, so keep it dormant until ObjPosLoad reprocesses it.
                placement.markDormant(spawn);
            } else {
                // ROM parity: direct DeleteObject tails skip RememberState,
                // leaving ObjPosLoad's bset-tested counter bit latched. Remove
                // the stale placement entry so the later bset-skip cannot be
                // materialized by syncActiveSpawnsLoad.
                placement.forgetCounterForSpawn(spawn);
                placement.removeFromActivePreservingCounterState(spawn);
            }
            removeActiveObject(spawn);
        } else {
            removeDynamicObjectInstance(instance);
        }
        return true;
    }

    private int outOfRangeReferenceX(ObjectInstance instance, ObjectSpawn spawn) {
        try {
            // ROM parity: most objects feed obX(a0) to out_of_range, but some
            // S1 objects store an alternate anchor/origin in objoff_30/32/3A.
            // Use the object's explicit ROM reference X when provided.
            return instance.getOutOfRangeReferenceX();
        } catch (NullPointerException e) {
            throw new IllegalStateException(describeCounterBasedUnloadObject(instance, spawn), e);
        }
    }

    private static String describeCounterBasedUnloadObject(ObjectInstance instance, ObjectSpawn spawn) {
        StringBuilder sb = new StringBuilder("Counter-based out_of_range check failed for ");
        sb.append(instance.getClass().getName());
        if (instance instanceof AbstractObjectInstance aoi) {
            sb.append(" slot=").append(aoi.getSlotIndex());
            sb.append(" name=").append(aoi.getName());
        }
        sb.append(" managerSpawn=");
        if (spawn == null) {
            sb.append("null");
        } else {
            sb.append(String.format("0x%04X,0x%04X id=0x%02X",
                    spawn.x() & 0xFFFF, spawn.y() & 0xFFFF, spawn.objectId() & 0xFF));
        }
        ObjectSpawn instanceSpawn = instance.getSpawn();
        sb.append(" instanceSpawn=");
        if (instanceSpawn == null) {
            sb.append("null");
        } else {
            sb.append(String.format("0x%04X,0x%04X id=0x%02X",
                    instanceSpawn.x() & 0xFFFF, instanceSpawn.y() & 0xFFFF, instanceSpawn.objectId() & 0xFF));
        }
        return sb.toString();
    }

    /**
     * Phase 2 of spawn window sync: load new objects from the ObjectPlacementController window.
     * <p>
     * ROM parity: ObjPosLoad runs AFTER ExecuteObjects. By loading new objects
     * after the exec loop, children allocated during the exec loop (Ring_Main,
     * CStom_Main, etc.) get slots BEFORE new ObjectPlacementController objects, matching the
     * ROM's slot assignment order. Objects loaded here don't execute until the
     * next frame, matching ROM timing where ObjPosLoad objects first run during
     * the following frame's ExecuteObjects.
     */
    private void syncActiveSpawnsLoad(boolean allowVerticalLoadBypassForS2) {
        Collection<ObjectSpawn> activeSpawns = placement.getActiveSpawns();
        boolean changed = false;

        if (slotLayout.twoAxisCursorPlacement()) {
            // S3K Load_Sprites advances the X cursor before the Camera_Y pass
            // (sonic3k.asm:37640-37656, 37675-37758). X-pass entries use the
            // broad camera-Y band from loc_1B7F2/loc_1BA92; the later Y pass
            // runs only when Camera_Y_pos_coarse changes and scans the one
            // newly exposed chunk strip (sonic3k.asm:37545-37588, 37723-37771).
            int previousYCoarse = twoAxisCameraYCoarse;
            int currentYCoarse = currentCameraYCoarseForTwoAxisPlacement();
            if (previousYCoarse == Integer.MIN_VALUE) {
                previousYCoarse = currentYCoarse;
            }
            for (ObjectSpawn spawn : placement.pendingCursorLoadSpawns()) {
                changed |= tryLoadPlacementSpawn(spawn, allowVerticalLoadBypassForS2);
            }
            placement.finishPendingCursorLoadBatch();
            if (currentYCoarse != previousYCoarse) {
                for (ObjectSpawn spawn : placement.getDeferredVerticalLoadSpawns()) {
                    changed |= tryLoadPlacementSpawnForTwoAxisYPass(spawn, previousYCoarse, currentYCoarse);
                }
            }
            twoAxisCameraYCoarse = currentYCoarse;
            if (changed) {
                bucketsDirty = true;
                activeObjectsCacheDirty = true;
            }
            return;
        }

        // ROM parity: OPL/ObjectManager forward scans process entries
        // left-to-right (a0 += 6), while backward scans process right-to-left
        // (a0 -= 6). Preserve that direction so FindFreeObj assigns the same
        // slot numbers the ROM would have used.
        // Reused scratch list + cached comparators: no constructor runs from
        // this loop ever re-enters syncActiveSpawnsLoad, and the list is
        // cleared in finally so it never leaks past this call.
        List<ObjectSpawn> sortedNewSpawns = newSpawnsScratch;
        sortedNewSpawns.clear();
        for (ObjectSpawn spawn : activeSpawns) {
            if (!activeObjects.containsKey(spawn)
                    && !(placement.isRemembered(spawn) && !placement.isStayActive(spawn))
                    && !placement.isDormant(spawn)) {
                sortedNewSpawns.add(spawn);
            }
        }
        if (forwardSpawnOrder == null) {
            forwardSpawnOrder = Comparator
                    .comparingInt(ObjectSpawn::x)
                    .thenComparingInt(placement::getSpawnIndex);
            backwardSpawnOrder = Comparator
                    .comparingInt(ObjectSpawn::x)
                    .reversed()
                    .thenComparing(Comparator.comparingInt(placement::getSpawnIndex).reversed());
        }
        sortedNewSpawns.sort(
                placement.isLastScrollBackward() ? backwardSpawnOrder : forwardSpawnOrder);

        // Allocate parent slots for all new objects (matching ObjPosLoad).
        // ROM parity: ObjPosLoad assigns one slot per object in X order.
        // Pre-allocate each parent slot BEFORE running the constructor, so that
        // if the constructor spawns children (e.g., GlassBlock's reflection),
        // getSlotIndex() returns the correct value and allocateSlotAfter()
        // gives the child a HIGHER slot (matching ROM's FindNextFreeObj).
        try {
            for (ObjectSpawn spawn : sortedNewSpawns) {
                if (!isSpawnVerticallyEligibleForLoad(spawn, allowVerticalLoadBypassForS2)) {
                    if (slotLayout.twoAxisCursorPlacement()) {
                        placement.markDeferredVerticalLoad(spawn);
                    }
                    continue;
                }
                // Pre-allocate parent slot — consumed by AbstractObjectInstance's
                // constructor via the PRE_ALLOCATED_SLOT ThreadLocal.
                int preSlot = allocateSlot();
                ObjectInstance instance = ObjectConstructionContext.with(objectServices, preSlot,
                        () -> registry != null ? registry.create(spawn) : null);
                if (instance != null) {
                    if (instance instanceof AbstractObjectInstance aoi) {
                        aoi.setServices(objectServices);
                        // Slot already set by constructor via PRE_ALLOCATED_SLOT.
                        // Ensure it's set (defensive, in case constructor didn't consume it).
                        if (aoi.getSlotIndex() < 0 && preSlot >= 0) {
                            aoi.setSlotIndex(preSlot);
                        }
                        // ROM: S1 OPL_MakeItem stores the counter value as
                        // obRespawnNo for RememberState to use on unload.
                        if (placement.isCounterBasedRespawn()) {
                            int counter = placement.getCounterForSpawn(spawn);
                            if (counter >= 0) {
                                aoi.setRespawnStateIndex(counter);
                            }
                        }
                    } else {
                        // Non-AbstractObjectInstance: release pre-allocated slot
                        if (preSlot >= 0) {
                            releaseSlot(preSlot);
                        }
                    }
                    registerActiveObject(spawn, instance);
                    changed = true;
                } else {
                    // Creation failed: release pre-allocated slot
                    if (preSlot >= 0) {
                        releaseSlot(preSlot);
                    }
                }
            }
        } finally {
            newSpawnsScratch.clear();
        }

        if (changed) {
            bucketsDirty = true;
            activeObjectsCacheDirty = true;
        }
    }

    private int currentCameraYCoarseForTwoAxisPlacement() {
        return camera != null ? (camera.getY() & 0xFF80) : 0;
    }

    private boolean tryLoadPlacementSpawn(ObjectSpawn spawn, boolean allowVerticalLoadBypassForS2) {
        if (spawn == null
                || activeObjects.containsKey(spawn)
                || (placement.isRemembered(spawn) && !placement.isStayActive(spawn))
                || placement.isDormant(spawn)) {
            placement.completePendingCursorLoad(spawn);
            return false;
        }
        if (!isSpawnVerticallyEligibleForLoad(spawn, allowVerticalLoadBypassForS2)) {
            if (slotLayout.twoAxisCursorPlacement()) {
                placement.markDeferredVerticalLoad(spawn);
            }
            placement.completePendingCursorLoad(spawn);
            return false;
        }
        // Pre-allocate parent slot — consumed by AbstractObjectInstance's
        // constructor via the PRE_ALLOCATED_SLOT ThreadLocal.
        int preSlot = allocateSlot();
        if (preSlot < 0) {
            return false;
        }
        ObjectInstance instance = ObjectConstructionContext.with(objectServices, preSlot,
                () -> registry != null ? registry.create(spawn) : null);
        if (instance != null) {
            if (instance instanceof AbstractObjectInstance aoi) {
                aoi.setServices(objectServices);
                if (aoi.getSlotIndex() < 0 && preSlot >= 0) {
                    aoi.setSlotIndex(preSlot);
                }
                if (placement.isCounterBasedRespawn()) {
                    int counter = placement.getCounterForSpawn(spawn);
                    if (counter >= 0) {
                        aoi.setRespawnStateIndex(counter);
                    }
                }
            } else if (preSlot >= 0) {
                releaseSlot(preSlot);
            }
            registerActiveObject(spawn, instance);
            placement.clearDeferredVerticalLoad(spawn);
            placement.completePendingCursorLoad(spawn);
            return true;
        }
        if (preSlot >= 0) {
            releaseSlot(preSlot);
        }
        placement.completePendingCursorLoad(spawn);
        return false;
    }

    private boolean tryLoadPlacementSpawnForTwoAxisYPass(ObjectSpawn spawn, int previousYCoarse, int currentYCoarse) {
        if (spawn == null
                || activeObjects.containsKey(spawn)
                || (placement.isRemembered(spawn) && !placement.isStayActive(spawn))
                || placement.isDormant(spawn)) {
            return false;
        }
        if (!isSpawnVerticallyEligibleForTwoAxisYPass(spawn, previousYCoarse, currentYCoarse)) {
            placement.markDeferredVerticalLoad(spawn);
            return false;
        }
        int preSlot = allocateSlot();
        if (preSlot < 0) {
            return false;
        }
        ObjectInstance instance = ObjectConstructionContext.with(objectServices, preSlot,
                () -> registry != null ? registry.create(spawn) : null);
        if (instance != null) {
            if (instance instanceof AbstractObjectInstance aoi) {
                aoi.setServices(objectServices);
                if (aoi.getSlotIndex() < 0 && preSlot >= 0) {
                    aoi.setSlotIndex(preSlot);
                }
                if (placement.isCounterBasedRespawn()) {
                    int counter = placement.getCounterForSpawn(spawn);
                    if (counter >= 0) {
                        aoi.setRespawnStateIndex(counter);
                    }
                }
            } else if (preSlot >= 0) {
                releaseSlot(preSlot);
            }
            registerActiveObject(spawn, instance);
            placement.clearDeferredVerticalLoad(spawn);
            return true;
        }
        if (preSlot >= 0) {
            releaseSlot(preSlot);
        }
        return false;
    }

    private boolean isSpawnVerticallyEligibleForTwoAxisYPass(ObjectSpawn spawn,
            int previousYCoarse, int currentYCoarse) {
        return placement.isSpawnVerticallyEligibleForTwoAxisYPass(
                spawn, previousYCoarse, currentYCoarse, camera);
    }


        private boolean isSpawnVerticallyEligibleForLoad(ObjectSpawn spawn, boolean allowVerticalLoadBypassForS2) {
            if (spawn == null || placement.isCounterBasedRespawn() || camera == null) {
                return true;
            }
            if (skipVerticalSpawnLoadFilterForGame) {
                // S2 has NO camera-Y load filter anywhere in its object loader.
                // ObjectsManager_Init falls straight through into
                // ObjectsManager_Main (docs/s2disasm/s2.asm:33062-33068 falls into
                // ObjectsManager_Main at s2.asm:33032), and both
                // ObjectsManager_GoingForward (s2.asm:33101-33124) and
                // GoingBackward (s2.asm:33047-33075) call ChkLoadObj immediately
                // after the X-window scan with no Camera_Y_pos test at all. There is
                // only one loader path, so this holds for the level-start / post-
                // special-stage reload exactly as it does for the running per-frame
                // load. Applying the S3K Load_Sprites vertical band at reset dropped
                // every layout entry whose y sat outside the initial camera band and
                // re-loaded it later, which reordered FindFreeObj slot assignment.
                // SCZ depends on the same rule: high-Y badniks are spawned while the
                // scripted camera is still at y=$0000, then survive until the Tornado
                // route descends into them.
                return true;
            }
            int wrapRange = camera.isVerticalWrapEnabled() ? camera.getVerticalWrapRange() : 0;
            return ObjectPlacementController.isNonCounterSpawnVerticallyEligible(
                    spawn, camera.getY(), camera.getMinY(), wrapRange);
        }

        static boolean isNonCounterSpawnVerticallyEligible(ObjectSpawn spawn, int cameraY, int cameraMinY) {
            return ObjectPlacementController.isNonCounterSpawnVerticallyEligible(
                    spawn, cameraY, cameraMinY, 0);
        }

        static boolean isNonCounterSpawnVerticallyEligible(ObjectSpawn spawn, int cameraY, int cameraMinY,
                int verticalWrapRange) {
            return ObjectPlacementController.isNonCounterSpawnVerticallyEligible(
                    spawn, cameraY, cameraMinY, verticalWrapRange);
        }

    /**
     * Enables vertical wrap Y adjustment on GraphicsManager if the camera has
     * vertical wrapping active. Called before object rendering to ensure objects
     * on the "wrong side" of a wrap boundary render at correct screen positions.
     */
    private void enableVerticalWrapIfNeeded() {
        if (camera.isVerticalWrapEnabled()) {
            graphicsManager.enableVerticalWrapAdjust(camera.getVerticalWrapRange(), camera.getY());
        }
    }

    private void registerActiveObject(ObjectSpawn spawn, ObjectInstance instance) {
        activeObjects.put(spawn, instance);
        instanceToSpawn.put(instance, spawn);
        assignRewindObjectId(instance, spawn);
    }

    private void removeActiveObject(ObjectSpawn spawn) {
        // ROM parity: an object leaving the SST releases its whole slot block. A
        // parent that pre-reserved child slots via the ROM's FindNextFreeObj chain
        // (Stair_Main's four blocks, docs/s1disasm/_incObj/5B SLZ Staircase.asm:30-66)
        // is deleted together with those children by its out_of_range/DeleteObject
        // tail (line 11), so the reservation can never outlive the parent. Only the
        // two "destroyed inside the exec loop" branches used to free them, so every
        // parent unloaded through unloadCounterBasedOutOfRange leaked its block
        // permanently -- SLZ1 accumulated 30+ dead reservations by frame 5667, which
        // pushed every later FindFreeObj pick above ROM's slot. Freeing here covers
        // all removal paths: this is the single point at which a spawn leaves
        // activeObjects.
        freeAllReservedChildSlots(spawn);
        ObjectInstance removed = activeObjects.remove(spawn);
        if (removed != null) {
            if (planeSwitchers != null) {
                planeSwitchers.remove(spawn);
            }
            instanceToSpawn.remove(removed);
            // ROM parity: an object's per-player pushing bits live in its SST
            // slot's status byte, and every game's delete routine zeroes the
            // whole slot -- S2 DeleteObject/DeleteObject2 (s2.asm:30329-30345),
            // S1 DeleteObject/DeleteChild (_incObj/sub DeleteObject.asm:10-20),
            // S3K Delete_Current_Sprite/Delete_Referenced_Sprite
            // (sonic3k.asm:36108-36125). So a solid that unloads and is later
            // reloaded from the same layout entry comes back with its pushing
            // bits CLEAR, and its first SolidObject_TestClearPush takes the
            // `beq SolidObject_NoCollision` exit (s2.asm:35462-35466) without
            // writing the Walk/Run animation word. The engine keys that bit on
            // the persistent ObjectSpawn record, which outlives the instance, so
            // without this the stale bit survives the unload and the reloaded
            // object publishes a release the ROM never performs.
            solidContacts.releaseObjectPushLatchForAllPlayers(removed);
            // Same delete-routine citation, standing half. The object-side
            // standing bit is in that same zeroed status byte, so a reloaded
            // solid must not answer "this player is standing on me" on the
            // strength of a bit its predecessor set. Object-side only: none of
            // the three routines touches the player's slot, so the player's own
            // Status_OnObj and the engine's riding state are left alone.
            solidContacts.releaseObjectStandingLatchForAllPlayers(removed);
            notifyObjectManagerRemoval(removed);
            // Prune the live-map so rewindObjectIds stays lean during normal play.
            // (Not strictly required — stale entries are harmless since rewindCaptureContext
            //  only iterates activeObjects/dynamicObjects, but trimming prevents unbounded growth.)
            rewindObjectIds.remove(removed);
        }
    }

    private void clearActiveObjects() {
        activeObjects.clear();
        instanceToSpawn.clear();
        if (planeSwitchers != null) {
            planeSwitchers.reset();
        }
    }

    /**
     * Assigns a stable rewind identity to {@code instance} the first time it enters
     * the live object set. The id encodes the spawn's layout index plus the current
     * {@link #dynamicObjectIdCounter} value (incremented after assignment), making
     * it unique within a session and re-mintable in the same order on re-simulation.
     *
     * <p>Internally-constructed dynamics such as player-bound power-up SST owners
     * have no placement spawn. They still receive a stable dynamic identity using
     * their allocated slot plus the monotonic dynamic ordinal. Their captured
     * dynamic entry carries that identity through recreation.
     */
    private void assignRewindObjectId(ObjectInstance instance, ObjectSpawn spawn) {
        if (!rewindObjectIds.containsKey(instance)) {
            if (spawn != null) {
                SpawnRefId spawnRef = SpawnRefId.fromSpawn(spawn);
                rewindObjectIds.put(
                        instance, ObjectRefId.forObject(spawnRef, dynamicObjectIdCounter++));
            } else {
                int slot = instance instanceof AbstractObjectInstance object
                        ? object.getSlotIndex() : -1;
                rewindObjectIds.put(
                        instance, ObjectRefId.dynamic(slot, 0, dynamicObjectIdCounter++));
            }
        }
    }

    private void populateDynamicFallbackScratch() {
        dynamicFallbackScratch.clear();
        for (ObjectInstance inst : dynamicObjects) {
            if (deferredDynamicExecThisFrame.contains(inst)) {
                continue;
            }
            if (inst instanceof AbstractObjectInstance aoi && (isManagedDynamicSlot(executionSlotIndex(aoi))
                    || FixedRuntimeObjectInstance.ownsFixedPass(aoi, slotLayout))) {
                continue;
            }
            dynamicFallbackScratch.add(inst);
        }
    }

    private void populateActiveFallbackScratch() {
        activeFallbackScratch.clear();
        for (ObjectInstance inst : activeObjects.values()) {
            if (inst instanceof AbstractObjectInstance aoi
                    && isManagedDynamicSlot(executionSlotIndex(aoi))) {
                continue;
            }
            activeFallbackScratch.add(inst);
        }
    }

    private void updateCameraBounds() {
        int left = camera.getX();
        int top = camera.getY();
        int right = left + camera.getWidth();
        int bottom = top + camera.getHeight();
        int wrapRange = camera.isVerticalWrapEnabled() ? camera.getVerticalWrapRange() : 0;
        AbstractObjectInstance.updateCameraBounds(left, top, right, bottom, wrapRange);
    }

    public static int decodePlaneSwitcherHalfSpan(int subtype) {
        return PlaneSwitchers.decodeHalfSpan(subtype);
    }

    public static boolean isPlaneSwitcherHorizontal(int subtype) {
        return PlaneSwitchers.isHorizontal(subtype);
    }

    public static int decodePlaneSwitcherPath(int subtype, int side) {
        return PlaneSwitchers.decodePath(subtype, side);
    }

    public static boolean decodePlaneSwitcherPriority(int subtype, int side) {
        return PlaneSwitchers.decodePriority(subtype, side);
    }

    public static boolean planeSwitcherGroundedOnly(int subtype) {
        return PlaneSwitchers.onlySwitchWhenGrounded(subtype);
    }

    public static char formatPlaneSwitcherLayer(byte layer) {
        return PlaneSwitchers.formatLayer(layer);
    }

    public static char formatPlaneSwitcherPriority(boolean highPriority) {
        return PlaneSwitchers.formatPriority(highPriority);
    }

    // -------------------------------------------------------------------------
    // Rewind snapshot adapter
    // -------------------------------------------------------------------------

    /**
     * Returns a {@link com.openggf.game.rewind.RewindSnapshottable} adapter for
     * this ObjectManager.
     *
     * <p><strong>Capture</strong> records the current slot inventory (the
     * {@link SlotAllocator} occupancy BitSet as {@code long[]}), per-instance state for every active ObjectPlacementController-managed
     * object (via {@link AbstractObjectInstance#captureRewindState()}), scalar counters
     * ({@code frameCounter}, {@code vblaCounter}, {@code currentExecSlot},
     * {@code peakSlotCount}), the render-cache dirty flag, and reserved child-slot entries.
     *
     * <p><strong>Restore</strong>:
     * <ol>
     *   <li>Clears the current active object table (without triggering ObjectPlacementController state
     *       side-effects).</li>
     *   <li>Restores scalar counters and {@link SlotAllocator} occupancy.</li>
     *   <li>Reuses the live instance in place when the snapshot entry matches it on
     *       (spawn identity, slot index, class) and the class passes the
     *       non-captured-field audit ({@link #isRewindInPlaceReuseSafeClass});
     *       otherwise re-instantiates from its {@link ObjectSpawn} using the
     *       same {@link ObjectRegistry#create} pipeline used by {@code syncActiveSpawnsLoad},
     *       with the slot pre-assigned from the snapshot.</li>
     *   <li>Calls {@link AbstractObjectInstance#restoreRewindState} on each restored
     *       instance to hydrate the captured field surface.</li>
     *   <li>Restores {@code reservedChildSlots} entries.</li>
     * </ol>
     *
     * <p><strong>Holder re-resolution contract:</strong> Holders of direct
     * {@link ObjectInstance} references (Camera target, {@code LevelEventManager} boss
     * reference, etc.) <em>must</em> re-resolve their references after restore — an
     * instance may be a fresh Java object even though it represents the same logical
     * slot (in-place reuse keeps identities only for audited matching entries).
     * Camera already re-resolves its target via {@code SpriteManager} on each snapshot
     * restore (Track C). Other subsystems should do the same.
     *
     * <p>ObjectPlacementController-managed objects are restored through their original spawn.
     * Non-ObjectPlacementController dynamic objects are restored when their class implements
     * {@link RewindRecreatable}; unsupported entries remain diagnostic-only.
     */
    public com.openggf.game.rewind.RewindSnapshottable<com.openggf.game.rewind.snapshot.ObjectManagerSnapshot> rewindSnapshottable() {
        return new com.openggf.game.rewind.RewindSnapshottable<>() {
            @Override
            public String key() {
                return "object-manager";
            }

            @Override
            public com.openggf.game.rewind.snapshot.ObjectManagerSnapshot capture() {
                com.openggf.game.rewind.schema.RewindCaptureContext rewindContext = rewindCaptureContext();
                // Capture per-active-slot state
                List<com.openggf.game.rewind.snapshot.ObjectManagerSnapshot.PerSlotEntry> slots = new ArrayList<>();
                for (Map.Entry<ObjectSpawn, ObjectInstance> entry : activeObjects.entrySet()) {
                    ObjectSpawn spawn = entry.getKey();
                    ObjectInstance inst = entry.getValue();
                    if (inst instanceof AbstractObjectInstance aoi) {
                        slots.add(new com.openggf.game.rewind.snapshot.ObjectManagerSnapshot.PerSlotEntry(
                                aoi.getSlotIndex(),
                                spawn,
                                aoi.getClass().getName(),
                                ObjectRewindTypeSafety.capture(aoi, rewindContext),
                                rewindObjectIds.get(inst)
                        ));
                    }
                }
                // Memoized sort keys: a plain extractor would rebuild the
                // spawn-key string per comparison (O(n log n) string builds
                // per capture). Keys are computed once per entry; the sorted
                // order — part of snapshot determinism — is unchanged.
                Map<Object, String> slotSpawnKeys = new java.util.IdentityHashMap<>();
                slots.sort(Comparator
                        .comparingInt(com.openggf.game.rewind.snapshot.ObjectManagerSnapshot.PerSlotEntry::slotIndex)
                        .thenComparing(entry -> slotSpawnKeys.computeIfAbsent(
                                entry, e -> stableSpawnKey(entry.spawn()))));

                // Capture reservedChildSlots
                List<com.openggf.game.rewind.snapshot.ObjectManagerSnapshot.ChildSpawnEntry> childSpawns = new ArrayList<>();
                for (Map.Entry<ObjectSpawn, int[]> entry : reservedChildSlots.entrySet()) {
                    int[] slotArray = entry.getValue();
                    childSpawns.add(new com.openggf.game.rewind.snapshot.ObjectManagerSnapshot.ChildSpawnEntry(
                            entry.getKey(),
                            Arrays.copyOf(slotArray, slotArray.length)
                    ));
                }
                Map<Object, String> childParentKeys = new java.util.IdentityHashMap<>();
                Map<Object, String> childSlotKeys = new java.util.IdentityHashMap<>();
                childSpawns.sort(Comparator
                        .comparing((com.openggf.game.rewind.snapshot.ObjectManagerSnapshot.ChildSpawnEntry entry) ->
                                childParentKeys.computeIfAbsent(
                                        entry, e -> stableSpawnKey(entry.parentSpawn())))
                        .thenComparing(entry -> childSlotKeys.computeIfAbsent(
                                entry, e -> Arrays.toString(entry.reservedSlots()))));

                List<com.openggf.game.rewind.snapshot.ObjectManagerSnapshot.DynamicObjectEntry> dynamicEntries =
                        new ArrayList<>();
                for (ObjectInstance inst : dynamicObjects) {
                    if (dynamicOwnership.excludesFromRewind(inst)) {
                        continue;
                    }
                    if (inst instanceof AbstractObjectInstance aoi) {
                        dynamicEntries.add(
                                new com.openggf.game.rewind.snapshot.ObjectManagerSnapshot.DynamicObjectEntry(
                                        inst.getClass().getName(),
                                        inst.getSpawn(),
                                        aoi.getSlotIndex(),
                                        ObjectRewindTypeSafety.capture(aoi, rewindContext),
                                        playerBoundOwner(inst),
                                        rewindObjectIds.get(inst),
                                        dynamicOwnership.isRewindableAuxiliary(inst)));
                    }
                }

                long[] bits = captureOwnedUsedSlotBits();
                com.openggf.game.rewind.snapshot.ObjectManagerSnapshot.SolidContactState solidContactState =
                        solidContacts.captureRewindState();

                // No List.copyOf wrappers here: the ObjectManagerSnapshot
                // compact constructor already copies its list components.
                return new com.openggf.game.rewind.snapshot.ObjectManagerSnapshot(
                        bits,
                        slots,
                        frameCounter,
                        vblaCounter,
                        currentExecSlot,
                        slotAllocator.peakSlotCount(),
                        bucketsDirty,
                        childSpawns,
                        dynamicEntries,
                        placement.captureRewindState(twoAxisCameraYCoarse,
                                s2LatchedObjectManagerCameraX),
                        solidContactState.riding(),
                        solidContactState,
                        planeSwitchers != null
                                ? planeSwitchers.captureRewindState()
                                : com.openggf.game.rewind.snapshot.ObjectManagerSnapshot.PlaneSwitcherSnapshot.empty(),
                        touchResponses != null
                                ? touchResponses.captureRewindState()
                                : com.openggf.game.rewind.snapshot.ObjectManagerSnapshot.TouchResponseOverlapState.empty(),
                        dynamicObjectIdCounter,
                        collisionResponseList.captureRewindState(rewindObjectIds::get)
                );
            }

            @Override
            public void restore(com.openggf.game.rewind.snapshot.ObjectManagerSnapshot s) {
                // The restore is TWO-PHASE so object-reference resolution is order-independent.
                //   Phase 1 recreates (reuse / adopt / genericRecreate / registry) every
                //           captured object — active and dynamic — and registers each under its
                //           captured ObjectRefId in a fresh restore identity table, WITHOUT yet
                //           applying any field blob.
                //   Phase 2 applies each object's compact field blob against that fully-populated
                //           table, so an ObjectReferenceCodec ref resolves to the RESTORED target
                //           regardless of recreate order (forward refs included).
                // A single-pass restore could only resolve refs whose target was recreated earlier
                // in the loop; a forward ref to a later object resolved to the orphaned pre-restore
                // instance (or threw). See TestTwoPhaseRestoreOrdering.
                //
                // The restore table registers the live players (unchanged across a restore) plus
                // every restored object; it is the resolution table phase 2 hands to restore.
                com.openggf.game.rewind.identity.RewindIdentityTable restoreTable =
                        new com.openggf.game.rewind.identity.RewindIdentityTable();
                registerPlayersInto(restoreTable);
                com.openggf.game.rewind.schema.RewindCaptureContext rewindContext =
                        com.openggf.game.rewind.schema.RewindCaptureContext.withIdentityTable(restoreTable);
                // Phase-2 work item: a recreated instance paired with the entry whose field
                // blob must be applied to it once every id is registered.
                record RestoreStatePair<E>(AbstractObjectInstance instance, E entry) {}
                List<RestoreStatePair<com.openggf.game.rewind.snapshot.ObjectManagerSnapshot.PerSlotEntry>>
                        activeStateWork = new ArrayList<>();
                List<RestoreStatePair<com.openggf.game.rewind.snapshot.ObjectManagerSnapshot.DynamicObjectEntry>>
                        dynamicStateWork = new ArrayList<>();

                // 0. Index live instances by spawn identity so matching snapshot entries
                //    can reuse them in place instead of paying a full reconstruction.
                Map<ObjectSpawn, ObjectInstance> previousActive = rewindRestoreReuseScratch;
                previousActive.clear();
                if (rewindInPlaceRestoreEnabled && !activeObjects.isEmpty()) {
                    previousActive.putAll(activeObjects);
                }
                // 1. Clear current active objects (without mutating ObjectPlacementController state).
                //    Extra live objects absent from the snapshot are dropped here exactly as the
                //    pre-reuse path dropped them (reference drop, no onUnload), via not being
                //    re-registered below.
                clearActiveObjects();
                dynamicObjects.clear();
                dynamicOwnership.clear();
                Arrays.fill(execOrder, null);
                pendingPlayerBoundEntries.clear();
                // Capture construction-spawned children produced while active objects are
                // reconstructed below, so the dynamic reconciliation loop can adopt them in
                // place instead of recreating duplicates. (See registerRewindReconstructionChild.)
                rewindReconstructionChildren.clear();
                rewindReconstructionChildCapture = true;

                // 2. Restore scalar counters and slot occupancy
                slotAllocator.restoreFromLongArray(s.usedSlotsBits());
                frameCounter = s.frameCounter();
                vblaCounter = s.vblaCounter();
                currentExecSlot = s.currentExecSlot();
                slotAllocator.restorePeakSlotCount(s.peakSlotCount());
                bucketsDirty = s.bucketsDirty();
                activeObjectsCacheDirty = true;
                // Restore the object-id counter so any new objects spawned after restore
                // (in replay or fresh gameplay) do not alias the restored objects' ids.
                dynamicObjectIdCounter = s.dynamicObjectIdCounter();
                rewindObjectIds.clear();

                // 3. PHASE 1 (active objects): reuse the live instance in place when it
                //    is provably equivalent to a fresh reconstruction, otherwise recreate
                //    from the spawn as before. Register the captured id into the restore table
                //    and defer the field-blob application to phase 2. The finally-clear
                //    guarantees the scratch map never retains stale instance refs past this
                //    restore, even when a factory throws.
                try {
                    for (com.openggf.game.rewind.snapshot.ObjectManagerSnapshot.PerSlotEntry entry : s.slots()) {
                        ObjectSpawn spawn = entry.spawn();
                        int targetSlot = entry.slotIndex();
                        ObjectInstance previous = previousActive.get(spawn);
                        ObjectInstance inst;
                        if (previous instanceof AbstractObjectInstance previousAoi
                                && canReuseForRewindRestore(previousAoi, entry)) {
                            // Reused instances keep their injected services and renderer wiring.
                            inst = previous;
                        } else {
                            // Use PRE_ALLOCATED_SLOT so the constructor picks up the correct slot
                            int dynamicCountBefore = dynamicObjects.size();
                            int reservedChildBefore = reservedChildSlots.size();
                            int reconstructionChildBefore = rewindReconstructionChildren.size();
                            inst = ObjectConstructionContext.withRewindActiveRestore(
                                    () -> ObjectConstructionContext.with(objectServices, targetSlot,
                                            () -> registry != null ? registry.create(spawn) : null));
                            if (inst != null) {
                                if (inst instanceof AbstractObjectInstance constructed) {
                                    constructed.setServices(objectServices);
                                    ObjectConstructionContext.withRewindActiveRestore(() -> {
                                        constructed.recreateConstructionChildrenForRewind();
                                        return null;
                                    });
                                }
                                // Constructors that spawn children or reserve child slots have
                                // restore-relevant construction side effects that in-place reuse
                                // would skip; latch those classes onto the recreate path. Under
                                // rewind restore, child spawns are routed to
                                // rewindReconstructionChildren (not dynamicObjects), so count that
                                // growth too — otherwise a child-spawning boss would look
                                // side-effect-free and wrongly become reuse-eligible.
                                boolean constructionSideEffects =
                                        dynamicObjects.size() != dynamicCountBefore
                                        || reservedChildSlots.size() != reservedChildBefore
                                        || rewindReconstructionChildren.size() != reconstructionChildBefore;
                                rewindRestoreConstructionSideEffects.merge(
                                        inst.getClass(), constructionSideEffects, Boolean::logicalOr);
                            }
                        }
                        if (inst instanceof AbstractObjectInstance aoi) {
                            aoi.setServices(objectServices);
                            if (aoi.getSlotIndex() < 0 && targetSlot >= 0) {
                                aoi.setSlotIndex(targetSlot);
                            }
                            // Install a captured identity before registration so
                            // registerActiveObject does not mint and consume a
                            // throwaway dynamic ordinal. Legacy null-id entries
                            // deliberately fall through and mint exactly once.
                            if (entry.objectId() != null) {
                                rewindObjectIds.put(inst, entry.objectId());
                            }
                            registerActiveObject(spawn, inst);
                            if (entry.objectId() != null) {
                                restoreTable.registerObject(inst, entry.objectId());
                            }
                            // Wire into execOrder if within the managed slot window
                            int execIdx = execIndexForSlot(aoi.getSlotIndex());
                            if (execIdx >= 0 && execIdx < execOrder.length) {
                                execOrder[execIdx] = aoi;
                            }
                            // Defer per-instance field-blob application to phase 2.
                            activeStateWork.add(new RestoreStatePair<>(aoi, entry));
                        } else if (inst != null) {
                            if (entry.objectId() != null) {
                                rewindObjectIds.put(inst, entry.objectId());
                            }
                            registerActiveObject(spawn, inst);
                            if (entry.objectId() != null) {
                                restoreTable.registerObject(inst, entry.objectId());
                            }
                        }
                    }
                } finally {
                    previousActive.clear();
                }

                // 4. Restore reservedChildSlots
                reservedChildSlots.clear();
                for (com.openggf.game.rewind.snapshot.ObjectManagerSnapshot.ChildSpawnEntry ce : s.childSpawns()) {
                    reservedChildSlots.put(ce.parentSpawn(),
                            Arrays.copyOf(ce.reservedSlots(), ce.reservedSlots().length));
                }

                // 5. PHASE 1 (dynamic objects): recreate each captured dynamic object and register
                //    its captured id, still deferring field-blob application to phase 2.
                //
                // A captured entry can depend on a LATER entry in this same list finishing its
                // own reconstruction first: a dynamically-spawned parent (e.g. a boss spawned by
                // a zone event rather than the zone's static object layout table -- see
                // Sonic1SYZBossInstance, GHZBossWreckingBall's owner, Sonic3kAIZ/MHZEvents
                // minibosses) is itself a captured DynamicObjectEntry processed in this loop, and
                // its OWN construction-spawned children were captured as EARLIER entries (inserted
                // into dynamicObjects during the parent's constructor, before the parent itself was
                // appended -- see registerRewindReconstructionChild). A first-pass attempt for such
                // a child entry therefore used to run before the parent had been reconstructed at
                // all: adoptRewindReconstructionChild found an empty pool (the parent hadn't run
                // its constructor yet to populate it), and any RewindRecreatable#recreateForRewind()
                // sibling-search fallback (e.g. nearestLiveBossForRewind) also failed (no live
                // parent to find) -- silently discarding the child's entire captured state. A
                // STATIC layout-spawned boss never hits this: it reconstructs in the Phase-1 ACTIVE
                // loop above (step 3), before this dynamic loop starts, so its children's pool
                // entries are already populated by the time this loop's adoption attempts run.
                //
                // reconstructDynamicEntry keeps the reconstruction-child pool active (via
                // withRewindActiveRestore) across a dynamically-spawned parent's OWN
                // reconstruction below, not just the pre-Phase-1 static case, so its fresh
                // construction-spawned children land in the pool instead of live-spawning as
                // unrelated new dynamic objects. Unresolved entries are parked and retried in a
                // fixed-point loop (not just once): a single retry pass is not enough for a
                // 3+-level captured chain (e.g. a grandchild whose intermediate parent entry
                // ITSELF only resolves during the retry pass) -- the grandchild's one retry
                // attempt would run before that intermediate parent's own retry has populated
                // the pool with the grandchild's real captured sibling, dropping it exactly like
                // the bug this loop exists to fix. Looping until a pass makes zero progress
                // resolves any finite dependency depth; the parked count strictly decreases on
                // every productive pass, so the loop terminates, and whatever remains unresolved
                // once a pass resolves nothing has a permanently missing dependency (its owning
                // parent could not itself reconstruct at all) and falls through to the existing
                // clean unmatched-drop path below.
                List<com.openggf.game.rewind.snapshot.ObjectManagerSnapshot.DynamicObjectEntry>
                        parkedDynamicEntries = new ArrayList<>();
                java.util.function.Predicate<com.openggf.game.rewind.snapshot.ObjectManagerSnapshot.DynamicObjectEntry>
                        reconstructDynamicEntry = entry -> {
                    // Prefer adopting the construction-spawned child the reconstructed parent
                    // already created and back-references. Adopting it in place gives exact
                    // captured state AND keeps the parent's child reference valid, with no
                    // double-spawn. Routine-spawned children (no construction counterpart) fall
                    // back to the generic recreate path.
                    AbstractObjectInstance adopted = adoptRewindReconstructionChild(entry);
                    ObjectInstance inst = adopted != null
                            ? adopted
                            : ObjectConstructionContext.withRewindActiveRestore(
                                    () -> recreateDynamicObject(entry));
                    if (!(inst instanceof AbstractObjectInstance aoi)) {
                        return false;
                    }
                    aoi.setServices(objectServices);
                    if (adopted != null) {
                        // Adopt at the captured slot; the construction spawn did not allocate
                        // one (the slot allocator is already restored from the snapshot).
                        int slot = entry.slotIndex();
                        if (slot >= 0) {
                            aoi.setSlotIndex(slot);
                        }
                    } else {
                        ObjectConstructionContext.withRewindActiveRestore(() -> {
                            aoi.recreateConstructionChildrenForRewind();
                            return null;
                        });
                        // Went through the generic recreateForRewind() path, not adoption --
                        // give owners that keep their own back-reference list (e.g.
                        // AbstractBossChild -> parent.childComponents) a chance to re-register
                        // this instance. Adopted candidates were already registered by the
                        // owning parent's own construction-time spawn, so this is skipped for
                        // them (see onRecreatedForRewind()'s javadoc).
                        aoi.onRecreatedForRewind();
                    }
                    // Restore the captured rewind id so the identity table built from
                    // the next rewindCaptureContext() re-uses the pre-restore id, and
                    // register it in the restore table so phase-2 refs resolve here.
                    // A null captured id means the object had no identity at capture, so no
                    // captured ref points at it — minting a fresh id is enough (and it is not
                    // added to the restore table because nothing resolves against it).
                    if (entry.objectId() != null) {
                        rewindObjectIds.put(aoi, entry.objectId());
                        restoreTable.registerObject(aoi, entry.objectId());
                    } else {
                        assignRewindObjectId(aoi, aoi.getSpawn());
                    }
                    dynamicObjects.add(aoi);
                    if (entry.rewindableAuxiliary()) {
                        dynamicOwnership.markRestoredRewindable(aoi);
                    }
                    activeObjectsCacheDirty = true;
                    int execIdx = execIndexForSlot(aoi.getSlotIndex());
                    if (execIdx >= 0 && execIdx < execOrder.length) {
                        execOrder[execIdx] = aoi;
                    }
                    // Defer per-instance field-blob application to phase 2.
                    dynamicStateWork.add(new RestoreStatePair<>(aoi, entry));
                    return true;
                };
                for (com.openggf.game.rewind.snapshot.ObjectManagerSnapshot.DynamicObjectEntry entry
                        : s.dynamicObjects()) {
                    if (!reconstructDynamicEntry.test(entry)) {
                        parkedDynamicEntries.add(entry);
                    }
                }
                boolean parkedPassMadeProgress = true;
                while (parkedPassMadeProgress && !parkedDynamicEntries.isEmpty()) {
                    parkedPassMadeProgress = false;
                    List<com.openggf.game.rewind.snapshot.ObjectManagerSnapshot.DynamicObjectEntry>
                            stillParked = new ArrayList<>();
                    for (com.openggf.game.rewind.snapshot.ObjectManagerSnapshot.DynamicObjectEntry entry
                            : parkedDynamicEntries) {
                        if (reconstructDynamicEntry.test(entry)) {
                            parkedPassMadeProgress = true;
                        } else {
                            stillParked.add(entry);
                        }
                    }
                    parkedDynamicEntries = stillParked;
                }
                // Stop routing further child spawns into the reconstruction-children scratch:
                // any spawns triggered from here on (Phase 2 state restore,
                // afterRewindRestoreSettled, or later gameplay) are normal wiring, not
                // reconstruction side effects to adopt.
                rewindReconstructionChildCapture = false;
                // Any reconstruction children NOT matched by a captured entry after BOTH passes
                // were spawned by a parent whose child was not in dynamicObjects at capture (e.g.
                // a same-class sibling destroyed and pruned before capture -- see
                // adoptRewindReconstructionChild), or by a parent that could not itself
                // reconstruct at all (its own captured entry above also returned false from both
                // passes, so it is dropped the very same way, and the orphaned pool children it
                // already spawned are dropped cleanly here rather than left dangling). They are
                // unregistered here (never added to dynamicObjects/activeObjects), but the
                // parent's OWN back-reference to them (e.g. AbstractBossInstance.childComponents)
                // was already set unconditionally at construction time and is otherwise never told
                // about the drop -- notify each so an owner tracking its own child-reference list
                // can drop it too, or it remains a live orphan that still receives update() every
                // frame. Clearing the scratch afterward guarantees no instances leak across
                // restore passes.
                for (AbstractObjectInstance unmatched : rewindReconstructionChildren) {
                    unmatched.onDroppedAsUnmatchedRewindReconstructionChild();
                }
                rewindReconstructionChildren.clear();

                // 6. PHASE 2: every captured id is now registered in restoreTable, so applying
                //    the field blobs resolves all object/player references — including forward
                //    references to objects recreated later in phase 1.
                for (RestoreStatePair<com.openggf.game.rewind.snapshot.ObjectManagerSnapshot.PerSlotEntry>
                        work : activeStateWork) {
                    ObjectRewindTypeSafety.restore(
                            work.instance(), work.entry().state(), rewindContext);
                }
                for (RestoreStatePair<com.openggf.game.rewind.snapshot.ObjectManagerSnapshot.DynamicObjectEntry>
                        work : dynamicStateWork) {
                    ObjectRewindTypeSafety.restore(
                            work.instance(), work.entry().state(), rewindContext);
                }

                // 6b. Every object's own field-blob restore (including any of its children's)
                // is now settled, so owners that derive state FROM their children (e.g.
                // AbstractBossInstance re-deriving its child-spawn ordinal counters from each
                // live child's restored identity) can safely do so now -- doing it inline
                // during phase 2 above would race, since restore order between a parent and
                // its own children is not guaranteed.
                for (RestoreStatePair<com.openggf.game.rewind.snapshot.ObjectManagerSnapshot.PerSlotEntry>
                        work : activeStateWork) {
                    work.instance().afterRewindRestoreSettled();
                }
                for (RestoreStatePair<com.openggf.game.rewind.snapshot.ObjectManagerSnapshot.DynamicObjectEntry>
                        work : dynamicStateWork) {
                    work.instance().afterRewindRestoreSettled();
                }

                if (s.placement() != null) {
                    twoAxisCameraYCoarse = placement.restoreRewindState(s.placement());
                    // The S2 post-camera coarse-X unload latch must rewind with the
                    // placement cursors; a stale later-frame latch makes the first
                    // replayed frames' MarkObjGone checks unload against a future
                    // camera edge (legacy snapshots carry MIN_VALUE = live fallback).
                    s2LatchedObjectManagerCameraX = s.placement().s2LatchedCameraX();
                }

                solidContacts.restoreRewindState(s.solidContactState());
                if (planeSwitchers != null) {
                    planeSwitchers.restoreRewindState(s.planeSwitchers());
                }

                // ObjectTouchResponseController' double-buffer overlap state must be restored
                // AFTER object restoration so slot lookup resolves to live
                // post-restore instances. See iter-1631 root-cause analysis in
                // docs/architecture/plans/2026-05-07-rewind-encounter-validation.md.
                if (touchResponses != null) {
                    touchResponses.restoreRewindState(s.touchResponseOverlap());
                }
                collisionResponseList.restoreRewindState(
                        s.collisionResponseState(), restoreTable::resolve);

                bucketsDirty = true;
                activeObjectsCacheDirty = true;
            }
        };
    }

    /**
     * Test hook: toggles the in-place reuse fast path of the rewind restore.
     * When disabled, every snapshot entry is destroy/recreated (the pre-reuse
     * behavior), which the in-place restore equivalence test uses as the
     * reference path.
     */
    public void setRewindInPlaceRestoreEnabledForTest(boolean enabled) {
        this.rewindInPlaceRestoreEnabled = enabled;
    }

    private boolean canReuseForRewindRestore(AbstractObjectInstance previous,
            com.openggf.game.rewind.snapshot.ObjectManagerSnapshot.PerSlotEntry entry) {
        if (previous.getSlotIndex() != entry.slotIndex()) {
            return false;
        }
        Class<?> type = previous.getClass();
        if (entry.className() == null || !type.getName().equals(entry.className())) {
            return false;
        }
        // Require one observed in-restore reconstruction of this class without
        // construction side effects before reusing instances of it.
        Boolean observed = rewindRestoreConstructionSideEffects.get(type);
        if (observed == null || observed) { // null = never observed in-restore, true = has side effects
            return false;
        }
        return isRewindInPlaceReuseSafeClass(type);
    }

    /**
     * Returns true when instances of {@code type} may be reused in place by the
     * rewind restore instead of destroy/recreated.
     *
     * <p>The destroy/recreate path resets every non-captured field to its
     * constructor value on each restore; in-place reuse keeps the live value.
     * The two are equivalent only when every field declared below
     * {@link AbstractObjectInstance} is either (a) captured by the default
     * capture path (restored from the snapshot on both paths) or (b) a
     * {@code final} construction-constant — an immutable value or a structural
     * reference the constructor derives deterministically (renderer handles,
     * mapping/sheet data, services). Classes with concrete capture/restore
     * overrides (unprovable hand-written coverage), non-final non-captured
     * fields (frame-coupled mutable state), or non-captured object/player
     * references, collections, or arrays (mutable or identity-coupled content)
     * fall back to recreate.
     *
     * <p>{@code AbstractObjectInstance} itself is fully covered by the base
     * snapshot record (its only non-captured members are the final
     * {@code spawn}/{@code name} and the intentionally retained
     * {@code services} handle). {@code AbstractBadnikInstance} is hand-audited:
     * its movement fields ride {@code BadnikRewindExtra}, {@code destructionConfig}
     * is a final structural config, and the three {@code cachedDebug*} fields are
     * self-correcting render caches (the label regenerates whenever
     * {@code animFrame}/{@code facingLeft} change, so a stale cache is only kept
     * when it is already correct).
     *
     * <p>Public for the in-place restore audit test.
     */
    public static boolean isRewindInPlaceReuseSafeClass(Class<?> type) {
        return ObjectRewindTypeSafety.isSafe(type);
    }

    private long[] captureOwnedUsedSlotBits() {
        BitSet owned = new BitSet(execOrder.length);
        for (ObjectInstance inst : activeObjects.values()) {
            markOwnedSlot(owned, inst);
        }
        for (ObjectInstance inst : dynamicObjects) {
            markOwnedSlot(owned, inst);
        }
        for (int[] slots : reservedChildSlots.values()) {
            if (slots == null) {
                continue;
            }
            for (int slot : slots) {
                if (isManagedDynamicSlot(slot)) {
                    owned.set(execIndexForSlot(slot));
                }
            }
        }
        return owned.toLongArray();
    }

    /**
     * Builds a fresh {@link com.openggf.game.rewind.schema.RewindCaptureContext} reflecting
     * the current live object + player set. The returned context's
     * {@link com.openggf.game.rewind.identity.RewindIdentityTable} maps every live object
     * to a stable {@link com.openggf.game.rewind.identity.ObjectRefId}.
     *
     * <p>This method is called internally by the capture/restore path and is also
     * exposed publicly so test harnesses can inspect the identity table without
     * triggering a full snapshot capture.
     */
    public com.openggf.game.rewind.schema.RewindCaptureContext captureIdentityContext() {
        return rewindCaptureContext();
    }

    /**
     * Verifies that every identity-bearing field captured by the compact default
     * object path points at an object or player registered in the current rewind
     * identity context.
     */
    public void validateRewindReferenceClosure() {
        ObjectRewindReferenceClosureValidator.validate(
                activeObjects.values(), dynamicObjects,
                dynamicOwnership.nonRewindableObjects(), rewindCaptureContext());
    }

    private com.openggf.game.rewind.schema.RewindCaptureContext rewindCaptureContext() {
        com.openggf.game.rewind.identity.RewindIdentityTable table =
                new com.openggf.game.rewind.identity.RewindIdentityTable();
        registerPlayersInto(table);
        // Register every live object (placed + dynamic) so object-reference fields can
        // be captured as stable ObjectRefIds. The id was assigned when the object entered
        // the live set (via registerActiveObject / addDynamicObjectInternal).
        for (ObjectInstance inst : activeObjects.values()) {
            ObjectRefId id = rewindObjectIds.get(inst);
            if (id != null) {
                table.registerObject(inst, id);
            }
        }
        for (ObjectInstance inst : dynamicObjects) {
            ObjectRefId id = rewindObjectIds.get(inst);
            if (id != null) {
                table.registerObject(inst, id);
            }
        }
        return com.openggf.game.rewind.schema.RewindCaptureContext.withIdentityTable(table);
    }

    /**
     * Registers the live player + sidekick references into {@code table} under their stable
     * {@link com.openggf.game.rewind.identity.PlayerRefId}s. Players are not part of the
     * object set rebuilt by a restore, so the same registration is valid for both the capture
     * context and the restore context — player-reference fields resolve to the live players
     * either way.
     */
    private void registerPlayersInto(com.openggf.game.rewind.identity.RewindIdentityTable table) {
        com.openggf.sprites.playable.AbstractPlayableSprite main = null;
        List<PlayableEntity> sidekicks = List.of();
        if (objectServices != null) {
            var camera = objectServices.camera();
            main = camera != null ? camera.getFocusedSprite() : null;
            List<PlayableEntity> serviceSidekicks = objectServices.sidekicks();
            if (serviceSidekicks != null) {
                sidekicks = serviceSidekicks;
            }
        }
        if (main != null) {
            table.registerPlayer(main, com.openggf.game.rewind.identity.PlayerRefId.mainPlayer());
        }
        for (int i = 0; i < sidekicks.size(); i++) {
            PlayableEntity sidekick = sidekicks.get(i);
            if (sidekick == null || sidekick == main) {
                continue;
            }
            table.registerPlayer(sidekick, com.openggf.game.rewind.identity.PlayerRefId.sidekick(i));
        }
    }

    private void markOwnedSlot(BitSet owned, ObjectInstance inst) {
        if (inst instanceof AbstractObjectInstance aoi && isManagedDynamicSlot(aoi.getSlotIndex())) {
            owned.set(execIndexForSlot(aoi.getSlotIndex()));
        }
    }

    private static String stableSpawnKey(ObjectSpawn spawn) {
        if (spawn == null) {
            return "";
        }
        return spawn.layoutIndex()
                + ":" + spawn.objectId()
                + ":" + spawn.x()
                + ":" + spawn.y()
                + ":" + spawn.subtype()
                + ":" + spawn.renderFlags()
                + ":" + spawn.rawYWord();
    }

    static boolean isRewindRestorableDynamicObject(ObjectInstance inst) {
        return isRewindRestorableDynamicObject(inst, null);
    }

    static boolean isRewindRestorableDynamicObject(ObjectInstance inst, ObjectRegistry registry) {
        return inst instanceof RewindRecreatable;
    }

    ObjectServices objectServicesForRewind() {
        return objectServices;
    }

    /**
     * Returns the game-specific {@link ObjectRegistry} for use by
     * {@link ObjectRewindDynamicCodecs#genericRecreate} during rewind restore.
     * Package-private: accessed only through {@link DynamicObjectRecreateContext#objectRegistry()}.
     */
    ObjectRegistry rewindObjectRegistry() {
        return registry;
    }

    /**
     * Enqueues a captured {@link com.openggf.game.rewind.snapshot.ObjectManagerSnapshot.DynamicObjectEntry}
     * for a player-bound dynamic class whose post-restore re-spawn happens
     * after object-manager restore (currently Shield + Stars). Called by the
     * recreate path.
     */
    void enqueuePendingPlayerBoundEntry(Class<?> baseType,
            com.openggf.game.rewind.snapshot.ObjectManagerSnapshot.DynamicObjectEntry entry) {
        if (entry == null || entry.slotIndex() < 0) return;
        pendingPlayerBoundEntries
                .computeIfAbsent(baseType, k -> new java.util.ArrayDeque<>())
                .add(entry);
    }

    /**
     * Pops the next captured entry for the given base type, or returns
     * {@code null} if none is pending. Called by {@code DefaultPowerUpSpawner}
     * during the post-restore re-spawn so the freshly-constructed instance
     * lands at the captured slot AND has its captured field surface
     * (animation cursor, timers, visibility flags, etc.) reapplied via
     * {@link AbstractObjectInstance#restoreRewindState}.
     */
    public com.openggf.game.rewind.snapshot.ObjectManagerSnapshot.DynamicObjectEntry
            consumePendingPlayerBoundEntry(Class<?> baseType) {
        return consumePendingPlayerBoundEntry(baseType, entry -> true);
    }

    /**
     * Pops the first captured entry for the given player-bound base type that
     * satisfies {@code matcher}. Shield restore uses this to match owner and
     * concrete class instead of consuming another player's pending same-type
     * shield from the FIFO queue.
     */
    public com.openggf.game.rewind.snapshot.ObjectManagerSnapshot.DynamicObjectEntry
            consumePendingPlayerBoundEntry(Class<?> baseType,
                    Predicate<com.openggf.game.rewind.snapshot.ObjectManagerSnapshot.DynamicObjectEntry> matcher) {
        java.util.ArrayDeque<com.openggf.game.rewind.snapshot.ObjectManagerSnapshot.DynamicObjectEntry>
                queue = pendingPlayerBoundEntries.get(baseType);
        if (queue == null || queue.isEmpty()) return null;
        Iterator<com.openggf.game.rewind.snapshot.ObjectManagerSnapshot.DynamicObjectEntry> iterator =
                queue.iterator();
        while (iterator.hasNext()) {
            com.openggf.game.rewind.snapshot.ObjectManagerSnapshot.DynamicObjectEntry entry = iterator.next();
            if (matcher.test(entry)) {
                iterator.remove();
                if (queue.isEmpty()) {
                    pendingPlayerBoundEntries.remove(baseType);
                }
                return entry;
            }
        }
        return null;
    }

    private static PlayableEntity playerBoundOwner(ObjectInstance inst) {
        if (inst instanceof ShieldObjectInstance shield) {
            return shield.getPlayer();
        }
        if (inst instanceof PowerUpObject powerUp && powerUp.isInvincibilityStars()) {
            return powerUp.boundPlayer();
        }
        return null;
    }

    private ObjectInstance recreateDynamicObject(
            com.openggf.game.rewind.snapshot.ObjectManagerSnapshot.DynamicObjectEntry entry) {
        DynamicObjectRecreateContext context = new DynamicObjectRecreateContext(this);
        // Phase-2 generic recreate: only classes that explicitly implement
        // RewindRecreatable opt into dynamic restore. Unsupported dynamic classes
        // still drop on restore and remain visible in coverage diagnostics.
        if (isRewindRecreatableClassName(entry.className())) {
            return ObjectRewindDynamicCodecs.genericRecreate(entry, context);
        }
        return null;
    }

    /**
     * Returns true if the captured class name resolves to a concrete object class that
     * implements {@link RewindRecreatable}. Used to opt the class into the generic
     * recreate fallback without referencing any concrete game package.
     */
    private static boolean isRewindRecreatableClassName(String className) {
        if (className == null) {
            return false;
        }
        try {
            return RewindRecreatable.class.isAssignableFrom(Class.forName(className));
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /**
     * Records a child constructed while an active object is being reconstructed during a
     * rewind restore. The child is NOT inserted into {@link #dynamicObjects} and is NOT given
     * a freshly allocated slot here — the slot allocator was already restored from the
     * snapshot in restore() step 2, and the child is registered at its captured slot when the
     * step-4 reconciliation loop adopts it (see {@link #adoptRewindReconstructionChild}).
     *
     * <p>Called by {@link AbstractObjectInstance#spawnChild}/{@code spawnFreeChild} only when
     * {@link ObjectConstructionContext#isRewindActiveRestore()} is true. The reconstructed
     * parent still receives this instance as its back-reference, so adopting it in place keeps
     * the parent's child references valid while giving the child its exact captured state.
     */
    public void registerRewindReconstructionChild(AbstractObjectInstance child) {
        if (child != null && rewindReconstructionChildCapture) {
            rewindReconstructionChildren.add(child);
        }
    }

    /**
     * Finds and removes the pending reconstruction child whose runtime class matches
     * {@code entry.className()}, preferring an EXACT match on {@code entry.spawn().subtype()}
     * over plain construction-order FIFO.
     *
     * <p>Construction order is deterministic and normally equals the dynamic-object capture
     * order, so first-in-order FIFO alone pairs each captured
     * {@link com.openggf.game.rewind.snapshot.ObjectManagerSnapshot.DynamicObjectEntry} with the
     * same logical child the parent re-spawned -- UNLESS the parent has multiple indistinguishable
     * same-class siblings (e.g. EHZ's 3 {@code EHZBossWheel} children) and one of them was
     * destroyed and pruned before the frame being captured: the fixed-order re-spawn still
     * produces as many candidates as the class originally had, but there are now fewer captured
     * entries, so FIFO silently shifts captured state onto the wrong sibling position (a mismatch
     * invisible to the generic scalar-field restore that follows, but fatal to any {@code final}
     * field the constructor derived from the ORIGINAL position's identity, e.g.
     * {@code EHZBossWheel.animationState}).
     *
     * <p>{@link com.openggf.level.objects.boss.AbstractBossChild} threads a stable per-(parent,
     * runtime-class) construction ordinal through {@code ObjectSpawn.subtype()} (a field it does
     * not otherwise use) specifically so this can discriminate exactly. Object types that don't
     * participate in that convention report {@code subtype 0} for every instance, so the "no exact
     * match" branch below falls back to today's plain FIFO, preserving existing behavior for every
     * other {@code spawnChild} caller (e.g. {@code SidewaysPformObjectInstance}, {@code ConveyorObjectInstance}).
     *
     * <p>Returns {@code null} when no construction child of that class is pending (e.g.
     * routine-spawned children, which fall back to the generic recreate path).
     */
    private AbstractObjectInstance adoptRewindReconstructionChild(
            com.openggf.game.rewind.snapshot.ObjectManagerSnapshot.DynamicObjectEntry entry) {
        String className = entry.className();
        if (className == null) {
            return null;
        }
        int capturedSubtype = entry.spawn() != null ? entry.spawn().subtype() : 0;
        int fifoFallbackIndex = -1;
        for (int i = 0; i < rewindReconstructionChildren.size(); i++) {
            AbstractObjectInstance child = rewindReconstructionChildren.get(i);
            if (!child.getClass().getName().equals(className)) {
                continue;
            }
            if (fifoFallbackIndex < 0) {
                fifoFallbackIndex = i;
            }
            ObjectSpawn childSpawn = child.getSpawn();
            if (childSpawn != null && childSpawn.subtype() == capturedSubtype) {
                return rewindReconstructionChildren.remove(i);
            }
        }
        return fifoFallbackIndex >= 0 ? rewindReconstructionChildren.remove(fifoFallbackIndex) : null;
    }

    ObjectInstance findRestoredRidingObject(ObjectSpawn spawn, int slotIndex) {
        if (spawn != null) {
            ObjectInstance active = activeObjects.get(spawn);
            if (active != null) {
                return active;
            }
            for (ObjectInstance dynamic : dynamicObjects) {
                if (dynamic != null && dynamic.getSpawn() == spawn) {
                    return dynamic;
                }
            }
        }
        if (isManagedDynamicSlot(slotIndex)) {
            int execIdx = execIndexForSlot(slotIndex);
            if (execIdx >= 0 && execIdx < execOrder.length) {
                return execOrder[execIdx];
            }
        }
        return null;
    }


    static final class PlaneSwitchers {
        private static final Logger LOGGER = Logger.getLogger(PlaneSwitchers.class.getName());
        private static final int[] HALF_SPANS = new int[]{0x20, 0x40, 0x80, 0x100};
        private static final int MASK_SIZE = 0x03;
        private static final int MASK_HORIZONTAL = 0x04;
        private static final int MASK_PATH_SIDE1 = 0x08;
        private static final int MASK_PATH_SIDE0 = 0x10;
        private static final int MASK_PRIORITY_SIDE1 = 0x20;
        private static final int MASK_PRIORITY_SIDE0 = 0x40;
        private static final int MASK_GROUNDED_ONLY = 0x80;

        private final ObjectPlacementController placement;
        private final int objectId;
        private final PlaneSwitcherConfig config;
        private final Map<ObjectSpawn, PlaneSwitcherState> states = new HashMap<>();

        PlaneSwitchers(ObjectPlacementController placement, int objectId, PlaneSwitcherConfig config) {
            this.placement = placement;
            this.objectId = objectId & 0xFF;
            this.config = config;
        }

        void reset() {
            states.clear();
        }

        com.openggf.game.rewind.snapshot.ObjectManagerSnapshot.PlaneSwitcherSnapshot captureRewindState() {
            List<com.openggf.game.rewind.snapshot.ObjectManagerSnapshot.PlaneSwitcherEntry> entries =
                    new ArrayList<>();
            for (Map.Entry<ObjectSpawn, PlaneSwitcherState> entry : states.entrySet()) {
                PlaneSwitcherState state = entry.getValue();
                List<com.openggf.game.rewind.snapshot.ObjectManagerSnapshot.PlaneSwitcherPlayerSideEntry>
                        playerSides = new ArrayList<>();
                for (Map.Entry<PlayableEntity, Byte> sideEntry : state.sideStates.entrySet()) {
                    playerSides.add(
                            new com.openggf.game.rewind.snapshot.ObjectManagerSnapshot.PlaneSwitcherPlayerSideEntry(
                                    sideEntry.getKey(),
                                    sideEntry.getValue() & 0xFF));
                }
                playerSides.sort(Comparator
                        .comparing((com.openggf.game.rewind.snapshot.ObjectManagerSnapshot.PlaneSwitcherPlayerSideEntry e)
                                -> stablePlayerKey(e.player()))
                        .thenComparingInt(e -> e.sideState()));
                entries.add(new com.openggf.game.rewind.snapshot.ObjectManagerSnapshot.PlaneSwitcherEntry(
                        entry.getKey(),
                        state.getLastSideState(),
                        state.hasLastSideState(),
                        List.copyOf(playerSides)));
            }
            entries.sort(Comparator.comparing(entry -> stableSpawnKey(entry.spawn())));
            return new com.openggf.game.rewind.snapshot.ObjectManagerSnapshot.PlaneSwitcherSnapshot(
                    List.copyOf(entries));
        }

        void restoreRewindState(
                com.openggf.game.rewind.snapshot.ObjectManagerSnapshot.PlaneSwitcherSnapshot snapshot) {
            states.clear();
            if (snapshot == null) {
                return;
            }
            for (com.openggf.game.rewind.snapshot.ObjectManagerSnapshot.PlaneSwitcherEntry entry
                    : snapshot.entries()) {
                ObjectSpawn spawn = entry.spawn();
                if (spawn == null) {
                    continue;
                }
                PlaneSwitcherState state = new PlaneSwitcherState(decodeHalfSpan(spawn.subtype()));
                if (entry.hasLastSideState()) {
                    state.setLastSideState(entry.lastSideState());
                }
                for (com.openggf.game.rewind.snapshot.ObjectManagerSnapshot.PlaneSwitcherPlayerSideEntry side
                        : entry.playerSides()) {
                    if (side.player() != null) {
                        state.setSideState(side.player(), side.sideState());
                    }
                }
                states.put(spawn, state);
            }
        }

        private static String stablePlayerKey(PlayableEntity player) {
            if (player instanceof AbstractPlayableSprite sprite) {
                return sprite.getCode();
            }
            return player == null ? "" : player.getClass().getName();
        }

        void update(PlayableEntity player) {
            if (placement == null || player == null || config == null) {
                return;
            }
            Collection<ObjectSpawn> active = placement.getActiveSpawns();
            if (active.isEmpty()) {
                return;
            }

            for (ObjectSpawn spawn : active) {
                update(spawn, player);
            }

            states.keySet().removeIf(spawn -> spawn.objectId() == objectId && !active.contains(spawn));
        }

        void update(ObjectSpawn spawn, PlayableEntity player) {
            if (spawn == null || player == null || config == null || spawn.objectId() != objectId) {
                return;
            }

            int playerX = player.getCentreX();
            int playerY = player.getCentreY();
            int subtype = spawn.subtype();
            PlaneSwitcherState state = states.computeIfAbsent(spawn,
                    key -> new PlaneSwitcherState(decodeHalfSpan(subtype)));

            boolean horizontal = isHorizontal(subtype);
            int sideNow = horizontal
                    ? (playerY >= spawn.y() ? 1 : 0)
                    : (playerX >= spawn.x() ? 1 : 0);
            int previousSide = state.getSideState(player);
            if (previousSide < 0) {
                state.setSideState(player, sideNow);
                state.setLastSideState(sideNow);
                return;
            }

            int half = state.halfSpanPixels;
            boolean inSpan = horizontal
                    ? (playerX >= spawn.x() - half && playerX < spawn.x() + half)
                    : (playerY >= spawn.y() - half && playerY < spawn.y() + half);
            boolean groundedGate = onlySwitchWhenGrounded(subtype) && player.getAir();

            if (inSpan && !groundedGate && sideNow != previousSide) {
                boolean skipCollisionChange = (spawn.renderFlags() & 0x1) != 0;
                if (!skipCollisionChange) {
                    int path = decodePath(subtype, sideNow);
                    player.setLayer((byte) path);
                    if (path == 0) {
                        player.setTopSolidBit(config.getPath0TopSolidBit());
                        player.setLrbSolidBit(config.getPath0LrbSolidBit());
                    } else {
                        player.setTopSolidBit(config.getPath1TopSolidBit());
                        player.setLrbSolidBit(config.getPath1LrbSolidBit());
                    }
                    LOGGER.fine(() -> String.format(
                            "PlaneSwitcher path=%d: player(%d,%d) obj(%d,%d) sub=0x%02X side=%d→%d air=%b mode=%s",
                            path, player.getCentreX(), player.getCentreY(),
                            spawn.x(), spawn.y(), subtype, previousSide, sideNow,
                            player.getAir(), player.getGroundMode()));
                }
                boolean highPriority = decodePriority(subtype, sideNow);
                player.setHighPriority(highPriority);
            }

            state.setSideState(player, sideNow);
            state.setLastSideState(sideNow);
        }

        void remove(ObjectSpawn spawn) {
            states.remove(spawn);
        }

        int getSideState(ObjectSpawn spawn) {
            PlaneSwitcherState state = states.get(spawn);
            if (state == null || !state.hasLastSideState()) {
                return -1;
            }
            return state.getLastSideState();
        }

        static int decodeHalfSpan(int subtype) {
            int index = subtype & MASK_SIZE;
            if (index < 0 || index >= HALF_SPANS.length) {
                index = 0;
            }
            return HALF_SPANS[index];
        }

        static boolean isHorizontal(int subtype) {
            return (subtype & MASK_HORIZONTAL) != 0;
        }

        static int decodePath(int subtype, int side) {
            int mask = side == 1 ? MASK_PATH_SIDE1 : MASK_PATH_SIDE0;
            return (subtype & mask) != 0 ? 1 : 0;
        }

        static boolean decodePriority(int subtype, int side) {
            int mask = side == 1 ? MASK_PRIORITY_SIDE1 : MASK_PRIORITY_SIDE0;
            return (subtype & mask) != 0;
        }

        static boolean onlySwitchWhenGrounded(int subtype) {
            return (subtype & MASK_GROUNDED_ONLY) != 0;
        }

        static char formatLayer(byte layer) {
            return layer == 0 ? 'A' : 'B';
        }

        static char formatPriority(boolean highPriority) {
            return highPriority ? 'H' : 'L';
        }

        private static final class PlaneSwitcherState {
            private final int halfSpanPixels;
            private final IdentityHashMap<PlayableEntity, Byte> sideStates = new IdentityHashMap<>();
            private byte lastSideState = 0;
            private boolean hasLastSideState = false;

            private PlaneSwitcherState(int halfSpanPixels) {
                this.halfSpanPixels = halfSpanPixels;
            }

            private int getSideState(PlayableEntity player) {
                Byte value = sideStates.get(player);
                return value != null ? value & 0xFF : -1;
            }

            private void setSideState(PlayableEntity player, int sideState) {
                sideStates.put(player, (byte) sideState);
            }

            private void setLastSideState(int sideState) {
                this.lastSideState = (byte) sideState;
                this.hasLastSideState = true;
            }

            private boolean hasLastSideState() {
                return hasLastSideState;
            }

            private int getLastSideState() {
                return lastSideState & 0xFF;
            }
        }
    }
}
