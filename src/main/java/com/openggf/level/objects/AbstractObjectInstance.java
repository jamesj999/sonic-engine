package com.openggf.level.objects;

import com.openggf.configuration.SonicConfigurationService;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.data.Rom;
import com.openggf.data.RomManager;
import com.openggf.debug.DebugOverlayManager;
import com.openggf.graphics.GLCommand;
import com.openggf.game.GameModule;
import com.openggf.game.GameServices;
import com.openggf.game.rules.GameRules;
import com.openggf.game.rules.ObjectInteractionRules;
import com.openggf.game.rewind.GenericFieldCapturer;
import com.openggf.game.rewind.GenericRewindEligibility;
import com.openggf.game.rewind.schema.RewindCaptureContext;
import com.openggf.game.rewind.schema.RewindObjectStateBlob;
import com.openggf.level.LevelManager;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.game.PlayableEntity;
import com.openggf.game.solid.PlayerSolidContactResult;
import com.openggf.game.solid.SolidCheckpointBatch;

import java.util.List;
import java.util.logging.Logger;

public abstract class AbstractObjectInstance implements ObjectInstance {
    private static final Logger LOG = Logger.getLogger(AbstractObjectInstance.class.getName());

    /**
     * Construction-time services context. Set by {@link ObjectManager} before calling
     * a factory, cleared in a finally block after construction completes.
     * <p>
     * This allows {@link #services()} to work during construction without requiring
     * constructor injection through every factory and subclass constructor.
     * Package-private: only {@link ObjectManager} should set/clear this.
     */
    static final ThreadLocal<ObjectServices> CONSTRUCTION_CONTEXT = new ThreadLocal<>();

    /**
     * Pre-allocated slot index for the object being constructed.
     * Set by {@link ObjectManager#syncActiveSpawnsLoad()} before calling the factory,
     * consumed (and cleared) by the first {@code super()} call in the constructor.
     * <p>
     * This ensures the parent object already has its slot assigned when its constructor
     * spawns children (e.g., GlassBlock's reflection). Without this, children would get
     * LOWER slots than the parent (FindFreeObj starts from bit 0), but the ROM's
     * FindNextFreeObj gives children HIGHER slots (scanning from the parent forward).
     * Package-private: only {@link ObjectManager} should set this.
     */
    static final ThreadLocal<Integer> PRE_ALLOCATED_SLOT = new ThreadLocal<>();

    /**
     * Cached camera bounds, updated once per frame by ObjectManager.
     * Avoids repeated camera lookups when checking visibility.
     */
    private static CameraBounds cameraBounds = new CameraBounds(0, 0, 320, 224);

    /**
     * Y-axis half-margin used by {@link #isOnScreenForTouch()} to mirror ROM's
     * BuildSprites {@code .assumeHeight} band. ROM (S1
     * {@code docs/s1disasm/_inc/BuildSprites.asm:71-78}, S2/S3K equivalents)
     * computes {@code obY - cameraY + 0x80} and checks the result against
     * {@code [0x60, 0x180)} -- equivalently, an object's Y must fall within
     * {@code [cameraY - 32, cameraY + 224 + 32)} for {@code obRender} bit 7 to
     * be set. The 32-pixel padding above and below the visible 224-line
     * viewport is what this constant captures.
     * <p>
     * This margin is deliberately coarser than ROM's {@code btst #4}
     * explicit-height path (which uses each object's per-object half-height
     * read from {@code height_pixels}). The trade-off is intentional: the
     * touch gate accepts touch tests for slightly more objects than ROM
     * would, but never rejects an object ROM would accept (i.e. it never
     * wrongly skips a touch). False positives are filtered by the
     * subsequent collision-flags / box test inside {@code TouchResponses};
     * false negatives would silently break game-state parity and have no
     * downstream filter. For per-object override semantics see
     * {@link #getOnScreenHalfHeight()}, which the solid-contact gate
     * ({@link #isOnScreen()}) consults instead -- the touch gate
     * intentionally uses this constant rather than the per-object height.
     */
    private static final int TOUCH_RESPONSE_Y_MARGIN = 32;
    protected final ObjectSpawn spawn;
    protected final String name;
    private boolean destroyed;
    /**
     * ROM parity: true when this destroy was triggered by an off-screen check
     * (Sprite_OnScreen_Test family in sonic3k.asm, e.g. loc_1B5A0), where ROM
     * clears bit 7 of the respawn-table entry ({@code bclr #7,(a2)} at
     * sonic3k.asm:37275) so the object can be re-spawned by the placement
     * system when the camera returns. Without this flag, the engine's
     * {@code permanentDestroyLatch} (S3K) treats every destroy as a latched
     * "do not respawn" (modeling player-kill explosions which never clear
     * the respawn bit). Off-screen self-deletes are explicitly NOT a
     * latched destroy in the ROM and must be respawnable.
     */
    private boolean destroyedRespawnable;
    private ObjectSpawn dynamicSpawn;
    private ObjectServices services;

    /**
     * Pre-update position snapshot, saved by ObjectManager before the object update loop.
     * Used by touch response collision checks to match ROM ordering (ReactToItem runs
     * before ExecuteObjects in the ROM, so objects are at their pre-update positions
     * during touch collision).
     */
    private int preUpdateX;
    private int preUpdateY;
    private boolean preUpdateValid;

    /**
     * Set when this object receives a same-frame update after being spawned mid-loop.
     * While true, touch response collision checks skip this object (ROM parity:
     * Sonic's ReactToItem at slot 0 runs before the parent creates this child).
     * Cleared on the next call to {@link #snapshotPreUpdatePosition()}.
     */
    private boolean skipTouchThisFrame;

    /**
     * ROM parity: a child spawned mid-loop into a slot at or below the parent's
     * current execution slot is not reached by this frame's ExecuteObjects pass,
     * so it does not run (and therefore does not call DisplaySprite to set
     * {@code obRender} bit 7) until the NEXT frame's pass. ReactToItem
     * ({@code docs/s1disasm/_incObj/sub ReactToItem.asm:50-51}) gates on
     * {@code tst.b obRender(a1) / bpl.s .next}, so such a child stays
     * touch-ineligible for one extra frame relative to a same-frame (higher-slot)
     * child: it cannot be touched until the frame AFTER its first own execution.
     * <p>
     * Set true when the child is registered (see
     * {@link #markAwaitingFirstTouchExecution()}); cleared once the object has run
     * its first {@code update()} (see {@link #clearAwaitingFirstTouchExecution()}).
     * While true, {@link #isOnScreenForTouch()} (the engine's {@code obRender}
     * bit-7 equivalent) returns false so the touch scan skips the object.
     */
    private boolean awaitingFirstTouchExecution;

    /**
     * ROM parity: Objects skip SolidObject on their first frame because obRender bit 7
     * (set by DisplaySprite) hasn't been set yet. The object's init routine sets
     * obRender to 4 (no bit 7), then DisplaySprite sets bit 7 if on-screen. On the next
     * frame, the routine checks bit 7 and proceeds with SolidObject.
     * <p>
     * This flag starts true and is cleared after the first {@link #snapshotPreUpdatePosition()}.
     */
    private boolean solidContactFirstFrame = true;

    /**
     * Pre-update collision flags snapshot. ROM parity: ReactToItem runs before other
     * objects update, so it sees enemies at their previous frame's collision type.
     * -1 means no snapshot (use current flags).
     */
    private int preUpdateCollisionFlags = -1;

    /**
     * ROM parity: Object slot index matching the Mega Drive's Object Status Table.
     * <p>
     * In the ROM, ExecuteObjects processes slots 0-127 sequentially. The d7 register
     * holds the loop counter: d7 = 127 for slot 0 (Sonic), d7 = 126 for slot 1, etc.
     * Some objects (e.g. Batbrain/Basaran) use d7 for frame-based randomization gates:
     * {@code (v_vbla_byte + d7) & 7 == 0}.
     * <p>
     * Assigned by {@link ObjectManager} when objects are created. -1 means unassigned
     * (should not happen for objects created through ObjectManager).
     */
    private int slotIndex = -1;

    /**
     * ROM parity (S1): Index into the respawn state table (v_objstate+2).
     * <p>
     * In S1, ObjPosLoad assigns each remember-state object a counter-based index
     * via {@code move.b d2,obRespawnNo(a1)}. When the object goes off-screen,
     * RememberState clears bit 7 at this index, allowing respawn. When the object
     * is destroyed (e.g. via .chkdel), the bit stays set, preventing respawn.
     * <p>
     * -1 means not tracked (non-remember-state object or S2/S3K mode).
     */
    private int respawnStateIndex = -1;

    protected AbstractObjectInstance(ObjectSpawn spawn, String name) {
        this.spawn = spawn;
        this.name = name;
        // ROM parity: consume the pre-allocated slot so that getSlotIndex()
        // returns the correct value if the constructor spawns children.
        // Only the first super() call gets the slot; child constructors see null.
        Integer preSlot = ObjectConstructionContext.consumePreAllocatedSlot();
        if (preSlot != null) {
            this.slotIndex = preSlot;
        }
    }

    /**
     * Updates the cached camera bounds in place. Called once per frame by ObjectManager
     * before any object updates run.
     *
     * @param verticalWrapRange Vertical wrap range in pixels (0 = no wrapping).
     *                          When > 0, Y visibility checks use modular arithmetic.
     */
    public static void updateCameraBounds(int left, int top, int right, int bottom, int verticalWrapRange) {
        cameraBounds.update(left, top, right, bottom);
        cameraBounds.setVerticalWrapRange(verticalWrapRange);
    }

    /**
     * Reset the previous-frame snapshot so the next
     * {@link #updateCameraBounds(int, int, int, int, int)} call mirrors the
     * current camera. Used by test infrastructure that recycles the static
     * camera bounds across fresh fixtures (each fixture starts with a fresh
     * level / camera and should not inherit the prior fixture's snapshot).
     */
    public static void resetCameraBoundsForTests() {
        cameraBounds.update(0, 0, 320, 224);
    }

    @Override
    public ObjectSpawn getSpawn() {
        return dynamicSpawn != null ? dynamicSpawn : spawn;
    }

    /**
     * Lazily updates the dynamic spawn to track the object's current position.
     * After this call, {@link #getSpawn()} returns a spawn at (x, y) instead of
     * the original placement position. No allocation occurs if the position is unchanged.
     */
    protected void updateDynamicSpawn(int x, int y) {
        if (dynamicSpawn == null || dynamicSpawn.x() != x || dynamicSpawn.y() != y) {
            dynamicSpawn = buildSpawnAt(x, y);
        }
    }

    /**
     * Rebuilds dynamic spawn metadata even when the tracked coordinates did not
     * move. Subclasses whose spawn identity depends on restored local state
     * should use this after restoring that state.
     */
    protected void rebuildDynamicSpawn(int x, int y) {
        dynamicSpawn = buildSpawnAt(x, y);
    }

    @Override
    public boolean participatesInLevelRepeatOffset() {
        return false;
    }

    @Override
    public void applyLevelRepeatOffset(int offsetX, int offsetY) {
        updateDynamicSpawn((getX() + offsetX) & 0xFFFF, (getY() + offsetY) & 0xFFFF);
    }

    protected boolean skipsSameFrameUpdateAfterSpawn() {
        return false;
    }

    /**
     * Called by {@code ObjectManager.restore()} when this instance was constructed as
     * a pending rewind-reconstruction child (registered via
     * {@code ObjectManager.registerRewindReconstructionChild}, see {@link #spawnChild})
     * but no captured {@code DynamicObjectEntry} matched it during the step-4
     * dynamic-object reconciliation, so it is being dropped rather than adopted.
     * Default no-op. Owners that keep their own reference list of such children
     * (e.g. {@code AbstractBossChild} removing itself from its parent's
     * {@code childComponents}) must override this so the drop does not leave a live,
     * still-updating orphan referenced nowhere but that list.
     */
    protected void onDroppedAsUnmatchedRewindReconstructionChild() {
    }

    /**
     * Called by {@code ObjectManager.restore()} immediately after this instance was
     * produced by the generic {@code recreateForRewind()} dynamic-object recreate path
     * (i.e. NOT adopted from a pending rewind-reconstruction candidate --
     * {@link #onDroppedAsUnmatchedRewindReconstructionChild()} is this hook's symmetric
     * counterpart for the candidate side of that same fork). Default no-op. Owners that
     * keep their own back-reference list of same-lifetime children (e.g.
     * {@code AbstractBossChild} adding itself to its parent's {@code childComponents})
     * must override this so a {@code recreateForRewind()} implementation that only
     * constructs the instance -- without separately re-registering it -- does not leave
     * a live object the manager tracks but the owner no longer drives or reads. Centralizing
     * the re-registration here (rather than requiring every {@code recreateForRewind()}
     * override to remember it, as {@code Sonic2DeathEggRobotInstance}'s per-subclass
     * {@code addChildComponentOnce()} calls do) covers every current and future
     * {@code AbstractBossChild} subclass by construction.
     */
    protected void onRecreatedForRewind() {
    }

    /**
     * Called when {@link ObjectManager} permanently removes this instance from
     * its active object graph. Subclasses with owner-held object references may
     * detach themselves here so those references cannot outlive the manager
     * identity they point at.
     */
    protected void onRemovedFromObjectManager() {
    }

    /**
     * Rebuilds constructor-equivalent child objects while the rewind manager's
     * reconstruction pool is active. Objects whose ROM child slots are created
     * on their first update rather than in their Java constructor can override
     * this hook so captured children are adopted with their original identities.
     */
    protected void recreateConstructionChildrenForRewind() {
    }

    /**
     * Called by {@code ObjectManager.restore()} once EVERY object's phase-2 field-blob
     * restore has completed (so any of this instance's own children have their final,
     * captured scalar state settled) but before the restored snapshot resumes ticking.
     * Default no-op. Owners that derive some of their own state FROM their children's
     * settled state (e.g. {@code AbstractBossInstance} re-deriving
     * {@code childSpawnOrdinalCounters} from each live child's restored
     * {@code AbstractBossChild#getChildOrdinal()}) must override this, since that
     * derivation is only correct once every child's own restore has already run --
     * restore order between a parent and its children is not guaranteed, so doing it
     * inline in this instance's own {@code restoreRewindState()} would race.
     */
    protected void afterRewindRestoreSettled() {
    }

    public String getName() {
        return name;
    }

    @Override
    public void snapshotPreUpdatePosition() {
        // Guard: some dynamic objects (effects, projectiles) may have null spawn
        if (getSpawn() == null) return;
        preUpdateX = getX();
        preUpdateY = getY();
        preUpdateValid = true;
        // Snapshot collision flags for touch response timing parity.
        // ROM: ReactToItem sees enemies at their pre-update collision type.
        if (this instanceof TouchResponseProvider trp) {
            preUpdateCollisionFlags = trp.getCollisionFlags();
        }
        // Clear same-frame spawn flag: this object has survived one full frame
        // and is now eligible for touch collision checks.
        skipTouchThisFrame = false;
        // Clear first-frame flag: object has completed one frame cycle.
        // ROM: obRender bit 7 is now set by DisplaySprite, so SolidObject runs.
        solidContactFirstFrame = false;
    }

    @Override
    public void snapshotTouchResponseState() {
        if (getSpawn() == null) return;
        preUpdateX = getX();
        preUpdateY = getY();
        preUpdateValid = true;
        if (this instanceof TouchResponseProvider trp) {
            preUpdateCollisionFlags = trp.getCollisionFlags();
        }
        // Frame-start ReactToItem snapshots happen before object execution.
        // A child created after the player slot in the previous object pass is
        // no longer same-frame-spawned here and must be touch-eligible.
        skipTouchThisFrame = false;
    }

    @Override
    public int getPreUpdateX() {
        return preUpdateValid ? preUpdateX : getX();
    }

    @Override
    public int getPreUpdateY() {
        return preUpdateValid ? preUpdateY : getY();
    }

    @Override
    public int getPreUpdateCollisionFlags() {
        return preUpdateCollisionFlags;
    }

    @Override
    public boolean isSkipTouchThisFrame() {
        return skipTouchThisFrame;
    }

    @Override
    public void clearSpawnTouchSkip() {
        skipTouchThisFrame = false;
    }

    @Override
    public boolean isSkipSolidContactThisFrame() {
        return solidContactFirstFrame;
    }

    /**
     * Marks this object as having received a same-frame update.
     * Called by ObjectManager when processing pending children in the finally block.
     */
    public void setSkipTouchThisFrame(boolean skip) {
        this.skipTouchThisFrame = skip;
    }

    /**
     * Marks a mid-loop child that was placed into a slot already passed by this
     * frame's ExecuteObjects pass (so it will not execute, and thus not set
     * {@code obRender} bit 7, until the next frame). Keeps the object
     * touch-ineligible until its first own execution completes. See
     * {@link #awaitingFirstTouchExecution}.
     */
    public void markAwaitingFirstTouchExecution() {
        this.awaitingFirstTouchExecution = true;
    }

    /**
     * Cleared by {@link ObjectManager} after the object runs its first
     * {@code update()} (the engine equivalent of DisplaySprite setting
     * {@code obRender} bit 7). No-op once already cleared.
     */
    public void clearAwaitingFirstTouchExecution() {
        this.awaitingFirstTouchExecution = false;
    }

    /**
     * Returns this object's slot index in the Object Status Table (0-127).
     * <p>
     * Use {@code 127 - getSlotIndex()} to compute the ROM's d7 register value
     * for randomization gates.
     *
     * @return slot index, or -1 if not yet assigned
     */
    public int getSlotIndex() {
        return slotIndex;
    }

    /**
     * The object id currently held in this object's SST slot ({@code obID(a0)}).
     * <p>
     * Defaults to the id the object was spawned with. A handful of ROM objects rewrite
     * {@code obID} in place partway through their life instead of deleting themselves and
     * allocating a replacement, so the slot keeps its contents (and its scratch RAM) while
     * reporting a different id. S1 {@code BossSpikeball_Explode} is the canonical case:
     * {@code move.b #id_Explosion,obID(a0)}
     * (docs/s1disasm/_incObj/"7A, 7B Boss - SLZ Main and Spike Balls.asm":883-887).
     * Override this — never the spawn record — when modelling such an object.
     *
     * @return the live SST object id, or -1 when the object has no spawn identity
     */
    public int getLiveObjectId() {
        return getSpawn() == null ? -1 : (getSpawn().objectId() & 0xFF);
    }

    /**
     * Returns the SST slot whose turn should execute this object.
     * <p>
     * Most engine objects map one Java instance to one ROM object slot. A small
     * number of consolidated multi-slot objects keep the parent slot for
     * lifecycle/allocation bookkeeping while executing gameplay from a child
     * slot that owned the ROM routine.
     */
    public int getExecutionSlotIndex() {
        return slotIndex;
    }

    /**
     * Assigns this object's slot index. Called by ObjectManager during object creation.
     *
     * @param index slot index (0-127 for ROM-matching slots, may exceed 127 for overflow)
     */
    public void setSlotIndex(int index) {
        this.slotIndex = index;
    }

    /**
     * Returns this object's respawn state table index (S1 counter-based system).
     * @return respawn state index, or -1 if not tracked
     */
    public int getRespawnStateIndex() {
        return respawnStateIndex;
    }

    /**
     * Sets this object's respawn state table index (S1 counter-based system).
     * Called by ObjectManager when creating objects during counter-based spawn.
     */
    public void setRespawnStateIndex(int index) {
        this.respawnStateIndex = index;
    }

    /**
     * Sets the injectable services handle. Called by ObjectManager after construction.
     */
    public void setServices(ObjectServices services) {
        this.services = services;
    }

    /**
     * Returns the injectable services handle. Safe to call at any point in the
     * object lifecycle — during construction, update, or rendering — as long as
     * the object was created through {@link ObjectManager} or
     * {@link ObjectManager#addDynamicObject}.
     * <p>
     * During construction, falls back to the {@link #CONSTRUCTION_CONTEXT} ThreadLocal
     * set by ObjectManager before calling the factory. After construction,
     * uses the instance field set by {@link #setServices}.
     */
    protected ObjectServices services() {
        if (services != null) {
            return services;
        }
        ObjectServices ctx = CONSTRUCTION_CONTEXT.get();
        if (ctx != null) {
            return ctx;
        }
        throw new IllegalStateException(
                getClass().getSimpleName() + ": services not available — "
                + "object must be created through ObjectManager");
    }

    protected ObjectServices tryServices() {
        if (services != null) {
            return services;
        }
        return CONSTRUCTION_CONTEXT.get();
    }

    /**
     * Returns the construction-time services context.
     * Usable from static factory methods called during object construction.
     */
    protected static ObjectServices constructionContext() {
        return CONSTRUCTION_CONTEXT.get();
    }

    protected SonicConfigurationService config() {
        return services().configuration();
    }

    /**
     * Returns the debug overlay manager.
     */
    protected DebugOverlayManager debugOverlay() {
        return services().debugOverlay();
    }

    /**
     * Static accessor for debug view config check, usable from field initializers.
     */
    protected static boolean staticDebugViewEnabled() {
        ObjectServices ctx = constructionContext();
        SonicConfigurationService configuration = ctx != null ? ctx.configuration() : null;
        if (configuration == null) {
            configuration = GameServices.configuration();
        }
        return configuration.getBoolean(SonicConfiguration.DEBUG_VIEW_ENABLED);
    }

    /**
     * Static accessor for debug overlay manager, usable from field initializers.
     */
    protected static DebugOverlayManager staticDebugOverlay() {
        ObjectServices ctx = constructionContext();
        DebugOverlayManager overlayManager = ctx != null ? ctx.debugOverlay() : null;
        return overlayManager != null ? overlayManager : GameServices.debugOverlay();
    }

    /**
     * Sets the construction context for child objects created during update.
     * Must be paired with {@link #clearConstructionContext()} in a finally block.
     */
    protected static void setConstructionContext(ObjectServices services) {
        ObjectConstructionContext.setConstructionContext(services);
    }

    /**
     * Clears the construction context after child object creation.
     */
    protected static void clearConstructionContext() {
        ObjectConstructionContext.clearConstructionContext();
    }

    /**
     * Returns the current construction-context services, or {@code null} when
     * none is set. Package-visible so {@link ObjectConstructionContext} can
     * save-and-restore a nested context rather than blindly clearing it.
     */
    static ObjectServices currentConstructionContext() {
        return CONSTRUCTION_CONTEXT.get();
    }

    /**
     * Static accessor for RomManager, usable from helper methods during object setup.
     */
    protected static RomManager staticRomManager() {
        ObjectServices ctx = constructionContext();
        RomManager romManager = ctx != null ? ctx.romManager() : null;
        return romManager != null ? romManager : GameServices.rom();
    }

    /**
     * Static accessor for LevelManager's ObjectManager, usable from static factory methods.
     * Keeps runtime lookups out of leaf-class bytecode.
     */
    protected static ObjectManager staticObjectManager() {
        LevelManager lm = GameServices.levelOrNull();
        return lm != null ? lm.getObjectManager() : null;
    }

    /**
     * Static accessor for LevelManager, usable from object helper methods that are
     * invoked outside a live object-services context.
     */
    protected static LevelManager staticLevelManager() {
        return GameServices.levelOrNull();
    }

    /**
     * Static accessor for RingManager, usable from constructors/static helpers while
     * keeping singleton access out of leaf-class bytecode.
     */
    protected static com.openggf.level.rings.RingManager staticRingManager() {
        LevelManager lm = staticLevelManager();
        return lm != null ? lm.getRingManager() : null;
    }

    public void setDestroyed(boolean destroyed) {
        this.destroyed = destroyed;
        if (!destroyed) {
            this.destroyedRespawnable = false;
        }
    }

    /**
     * Marks this object as destroyed via an off-screen check
     * (ROM Sprite_OnScreen_Test family, sonic3k.asm:37262 etc.). The placement
     * system will release the slot but will NOT latch the spawn into
     * {@code destroyedInWindow}, so when the camera re-enters the placement
     * window the object can re-spawn. This mirrors ROM's
     * {@code bclr #7,(a2)} at loc_1B5A0 (sonic3k.asm:37275).
     */
    public void setDestroyedByOffscreen() {
        this.destroyed = true;
        this.destroyedRespawnable = true;
    }

    @Override
    public boolean isDestroyed() {
        return destroyed;
    }

    /**
     * Returns true when the most recent destroy was an off-screen self-delete
     * (Sprite_OnScreen_Test) and the spawn should remain re-spawnable. See
     * {@link #setDestroyedByOffscreen()}.
     */
    @Override
    public boolean isDestroyedRespawnable() {
        return destroyedRespawnable;
    }

    @Override
    public boolean isHighPriority() {
        return false;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        // Default no-op.
    }

    /**
     * Hydrates this instance from a pre-trace ROM SST slot snapshot.
     * <p>
     * Used by the trace replay test harness to restore state-machine progress that
     * the ROM accumulates during title card / level-init iterations before the
     * Lua recorder begins emitting trace frames. Invoked once per slot, immediately
     * after the object is constructed and registered with the ObjectManager, and
     * before trace frame 0 is driven.
     * <p>
     * Default implementation is a no-op for objects that either hold no significant
     * pre-trace state or that have not yet been wired for snapshot hydration.
     * Subclasses override to read canonical fields (position, velocity, routine,
     * status, per-object state variables) and copy them onto their engine-side state.
     * <p>
     * <b>Must not spawn children, play audio, or emit render commands</b> — this is
     * a pure data copy. Any derived state (animation timers, render caches) should
     * be rebuilt lazily on the next {@link #update} / render call.
     *
     * @param snapshot immutable snapshot of SST bytes/words for this slot
     */
    public void hydrateFromRomSnapshot(RomObjectSnapshot snapshot) {
        // Default: no hydration. Subclasses override.
    }

    @Override
    public abstract void appendRenderCommands(List<GLCommand> commands);

    /**
     * Checks if this object is currently visible on screen.
     * ROM: render_flags.on_screen bit is set by MarkObjGone when object
     * is within camera bounds. Used by PlaySoundLocal (s2.asm line 1555)
     * to prevent off-screen objects from playing audio.
     * <p>
     * Uses pre-computed camera bounds (updated once per frame) for efficiency.
     *
     * @return true if the object is within the camera viewport
     */
    protected boolean isOnScreen() {
        return cameraBounds.contains(getX(), getY());
    }

    /**
     * S1 {@code BuildSprites} render-flag bit 7 test
     * (docs/s1disasm/_inc/BuildSprites.asm:47-91).
     * <p>
     * BuildSprites clears {@code sprite_rendered_bit} for every queued object and
     * re-sets it only when the object survives two bounds tests, both of which use
     * the object's own extents rather than a fixed margin:
     * <ul>
     *   <li>X (lines 47-58): skip when {@code obX - camX + obActWid} is negative, or
     *       when {@code obX - camX - obActWid >= 320}. The right edge is rejected
     *       with {@code bge}, so it is exclusive.</li>
     *   <li>Y: when {@code sprite_customheight_bit} is set (lines 61-73) the band is
     *       {@code obHeight} either side of the 224px screen, again with an exclusive
     *       bottom edge ({@code bge}); otherwise {@code .assumeHeight} (lines 84-91)
     *       uses a fixed 32px band with an exclusive {@code bhs} bottom edge.</li>
     * </ul>
     * Objects whose ROM lifetime is {@code tst.b obRender(a0) / bpl <delete>} must
     * consume this predicate rather than a hand-picked pixel margin, because the ROM
     * band is the object's own {@code obActWid}/{@code obHeight} pair.
     *
     * @param x            object centre X ({@code obX})
     * @param y            object centre Y ({@code obY})
     * @param actWidth     {@code obActWid}, the render half-width
     * @param heightExtent {@code obHeight} for custom-height objects, or 32 for the
     *                     {@code .assumeHeight} path
     */
    protected static boolean isWithinBuildSpritesBounds(
            int x, int y, int actWidth, int heightExtent) {
        return cameraBounds.containsRenderSpriteBounds(x, y, actWidth, heightExtent);
    }

    /**
     * The frame's camera origin, the engine equivalent of the ROM's
     * {@code Camera_X_pos} / {@code Camera_Y_pos}. Objects that port a ROM
     * offscreen band literally (coarse-masked horizontal distance, +$80
     * vertical distance) need the raw origin rather than a bounds predicate.
     */
    protected static int cameraLeft() {
        return cameraBounds.left();
    }

    protected static int cameraTop() {
        return cameraBounds.top();
    }

    /**
     * ROM parity for SolidObject_OnScreenTest (s2.asm:35140-35145,
     * sonic3k.asm:41390-41392 loc_1DF88, s1disasm/_incObj/sub SolidObject.asm
     * Solid_ChkEnter / SolidObject2F): returns true when the object's render
     * box currently overlaps the camera viewport. ROM equivalent is render_flags
     * bit 7 which Render_Sprites sets each frame based on bounding-box
     * overlap. Used by the inline solid contact path to skip side / top / bottom
     * resolution for objects the camera has already scrolled past, matching the
     * ROM's "if Sonic outruns the screen then he can phase through solid objects"
     * optimisation that the S2 disassembly explicitly documents.
     */
    public boolean isWithinSolidContactBounds() {
        // ROM Render_Sprites (sonic3k.asm:36336-36370 SolidObject_OnScreenTest,
        // s2.asm:35140-35145, s1disasm Solid_ChkEnter / SolidObject2F) sets
        // render_flags bit 7 when the object's bounding box overlaps the
        // 320x224 screen rectangle. The bounding box is centered on x_pos with
        // half-width = width_pixels(a0) (sonic3k.asm:36347 reads width_pixels
        // into d2, then 36350/36353 add/subtract d2 from (x_pos - cam) before
        // comparing against [0, 320]). Most gameplay objects use width_pixels=16,
        // but larger sprites (e.g. the CNZ horizontal door at sonic3k.asm:66167
        // byte_30FCE = $20, $08) use a wider rendered half-width and stay
        // on-screen longer than a hardcoded 16-px margin allows. Defer to the
        // per-object on-screen half-width so collision parity matches the ROM
        // for both small and large sprites.
        //
        // ObjectManager runs before the current frame's camera step, so the
        // cached camera bounds already represent the prior Render_Sprites pass
        // that set render_flags bit 7. The extra previous-frame snapshot lags
        // the ROM gate by two frames in inline object order.
        return cameraBounds.containsRenderSpriteBounds(
                getX(), getY(), getOnScreenHalfWidth(), getOnScreenHalfHeight());
    }

    /**
     * Per-object rendered half-width used by the on-screen / solid-contact
     * gate. ROM equivalent: {@code width_pixels(a0)} as read by Render_Sprites
     * (sonic3k.asm:36347 / s2.asm equivalent). Defaults to the widely shared
     * gameplay sprite half-width of 16 px so existing call sites stay
     * unchanged; objects with a wider rendered footprint (e.g. CNZ horizontal
     * door byte_30FCE = $20, $08 at sonic3k.asm:66167) override this to match
     * the ROM-side on-screen test.
     */
    public int getOnScreenHalfWidth() {
        return 16;
    }

    /**
     * Per-object {@code width_pixels(a1)} as read by the player on-object
     * balance routines (S1 {@code obActWid} at
     * docs/s1disasm/_incObj/01 Sonic.asm:340-351; S2
     * s2.asm:36259-36271; S3K sonic3k.asm:22460-22473). This is the object's
     * own width byte, which is NOT necessarily the rendered on-screen footprint
     * nor the possibly-extended full-solid X-collision width.
     *
     * <p>Top-solid platform helpers pass their standable half-width as
     * {@link SolidObjectParams#halfWidth()}, so use that by default. Full-solid
     * objects that extend their collision width beyond the ROM width byte (for
     * example SmashableGround's {@code width_pixels=$10} plus SolidObject side
     * padding) should continue to override this method with the exact
     * disassembly-backed width.
     * Using the wrong width here shifts the {@code d1 = player_x + width -
     * object_x} edge test and makes the player balance/flip facing on the wrong
     * object edge.
     */
    public int getBalanceWidthPixels() {
        if (this instanceof SolidObjectProvider provider && provider.isTopSolidOnly()) {
            SolidObjectParams params = provider.getSolidParams();
            if (params != null) {
                return params.halfWidth();
            }
        }
        return getOnScreenHalfWidth();
    }

    /**
     * ROM {@code status.npc.no_balancing}: when set, player look/duck logic skips
     * the object-edge balance branch for riders and falls through to up/down input
     * handling instead.
     */
    public boolean suppressesObjectEdgeBalance() {
        return false;
    }

    /**
     * Per-object rendered half-height used by the on-screen / solid-contact
     * gate. ROM equivalent: {@code height_pixels(a0)} as read by Render_Sprites
     * while setting render_flags bit 7.
     */
    public int getOnScreenHalfHeight() {
        return 16;
    }

    /**
     * ROM parity for the BuildSprites custom-height render path
     * ({@code docs/s1disasm/_inc/BuildSprites.asm:61-73}): objects that set
     * {@code obRender} bit 4 ({@code bset #4,obRender}) have their on-screen
     * render flag computed from the object's own {@code obHeight} half-extent
     * rather than the 32px {@code .assumeHeight} band. Defaults to
     * {@code false} (the shared assumed-height path). Tall S1 objects that set
     * the flag (e.g. the 256px MZ lava geyser column) override this to
     * {@code true} and supply their half-extent via
     * {@link #getOnScreenHalfHeight()} so the touch-response render-flag gate
     * ({@link #isOnScreenForTouch()}) matches the ROM. Not a zone carve-out:
     * the predicate models the ROM render flag, not a level id.
     */
    protected boolean usesCustomRenderHeight() {
        return false;
    }

    /**
     * ROM parity for ReactToItem: returns true if the object was on-screen
     * as of the pre-update snapshot (equivalent to obRender bit 7 from
     * the previous frame's BuildSprites).
     * <p>
     * The render-flag-driven Y gate is S1-specific. ROM S1's
     * {@code ReactToItem} ({@code docs/s1disasm/_incObj/sub
     * ReactToItem.asm:26-27}) reads {@code obRender(a1) / bpl.s .next}
     * and skips objects whose bit 7 has been cleared by
     * {@code BuildSprites} ({@code docs/s1disasm/_inc/BuildSprites.asm:71-78},
     * {@code .assumeHeight} branch when {@code obRender} bit 4 is clear).
     * That bit clears for any object whose Y falls outside
     * {@code [cameraY - 32, cameraY + 256)} (the visible 224-line viewport
     * plus a 32-px margin above and below). ROM S2 {@code Touch_Loop}
     * ({@code docs/s2disasm/s2.asm} ~84502-84551) has no equivalent
     * render-flag gate; S3K {@code TouchResponse}
     * ({@code docs/skdisasm/sonic3k.asm:20655}) consumes a pre-built
     * {@code Collision_response_list} where the gate happens upstream
     * during list build, not at touch time.
     * <p>
     * The engine therefore branches on
     * {@link ObjectInteractionRules#touchResponseUsesRenderFlagYGate()}: S1
     * gets the X+Y check; S2/S3K fall back to the X-only check the
     * engine used pre-Task-3 (commits b4ff4ea01/86871035c). Without this
     * gating the universal X+Y check filters S3K objects ROM allows to
     * interact with Tails, regressing MGZ trace replay first-fail from
     * frame 2395 to frame 1659.
     * <p>
     * Uses pre-update position so the gate matches the previous frame's
     * BuildSprites pass, mirroring the ROM ordering where the render
     * flag set this frame would not be observable until the next frame's
     * ReactToItem. The S1 xMargin uses {@link #getOnScreenHalfWidth()}
     * (default 16 px = ROM {@code width_pixels} for typical sprites) and
     * the yMargin uses 32 to mirror the {@code .assumeHeight} 32-pixel
     * band; this makes the gate slightly more inclusive than the
     * {@code btst #4} explicit-height path (which uses the per-object
     * half-height) but never more restrictive, so it will not introduce
     * false-negative collision skips for objects whose ROM render flag
     * would have been set.
     */
    public boolean isOnScreenForTouch() {
        // ROM parity: obRender bit 7 stays clear until the object runs its own
        // execution (DisplaySprite). A child dropped into an already-passed slot
        // this frame does not execute until next frame, so it must stay
        // touch-ineligible until that first execution completes. See
        // awaitingFirstTouchExecution (docs/s1disasm/_incObj/sub ReactToItem.asm:50-51).
        if (awaitingFirstTouchExecution) return false;
        if (!preUpdateValid) return false; // No snapshot → first frame, skip
        if (resolveTouchResponseUsesRenderFlagYGate()) {
            if (usesCustomRenderHeight()) {
                // S1 BuildSprites custom-height path (btst #4 set,
                // docs/s1disasm/_inc/BuildSprites.asm:61-73): the Y on-screen
                // test uses the object's own obHeight half-extent instead of the
                // 32px .assumeHeight band, so a tall object whose anchor sits
                // above the camera top (e.g. the 256px MZ lava geyser column,
                // obHeight=$80) keeps render_flags bit 7 set and stays
                // touchable. Mirror the ROM custom-height bounds test.
                return cameraBounds.containsRenderSpriteBounds(preUpdateX, preUpdateY,
                        getOnScreenHalfWidth(), getOnScreenHalfHeight());
            }
            // S1: include the BuildSprites .assumeHeight Y band.
            return cameraBounds.contains(preUpdateX, preUpdateY,
                    getOnScreenHalfWidth(), TOUCH_RESPONSE_Y_MARGIN);
        }
        // S2/S3K: pre-Task-3 X-only behaviour. Matches ROM S2 Touch_Loop
        // (no render-flag gate) and S3K Collision_response_list (gate
        // happens upstream during list build, not at touch time).
        return cameraBounds.containsX(preUpdateX);
    }

    /**
     * Resolves whether the active game gates {@link #isOnScreenForTouch()}
     * on the BuildSprites Y-band. Defaults to {@code true} when no game
     * module / game rules are available so test fixtures (which often run
     * without a fully-bootstrapped runtime) keep the stricter S1 gate the
     * regression suite was calibrated against.
     */
    private boolean resolveTouchResponseUsesRenderFlagYGate() {
        ObjectServices ctx = tryServices();
        GameModule module = ctx != null ? ctx.gameModule() : null;
        if (module == null) {
            return true;
        }
        GameRules rules = module.getRules();
        if (rules == null || rules.objectInteraction() == null) {
            return true;
        }
        return rules.objectInteraction().touchResponseUsesRenderFlagYGate();
    }

    /**
     * Checks if this object's X is within the camera viewport.
     * Matches ROM's MarkObjGone which only checks X distance for the on_screen flag.
     * Use this for detection checks where Y proximity is handled separately.
     */
    protected boolean isOnScreenX() {
        return cameraBounds.containsX(getX());
    }

    protected boolean isOnScreenX(int margin) {
        return cameraBounds.containsX(getX(), margin);
    }

    /**
     * Checks if this object is within the camera viewport with a margin.
     * Useful for projectiles that should persist slightly off-screen.
     *
     * @param margin pixels of extra space beyond camera bounds
     * @return true if the object is within the extended camera viewport
     */
    protected boolean isOnScreen(int margin) {
        return cameraBounds.contains(getX(), getY(), margin);
    }

    /**
     * Checks this object against Render_Sprites-style bounds, where the right
     * and bottom edges are exclusive. Use for routines that observe the ROM
     * render_flags on-screen bit rather than MarkObjGone's inclusive point test.
     */
    protected boolean isWithinRenderSpriteBounds(int xMargin, int yMargin) {
        return cameraBounds.containsRenderSpriteBounds(getX(), getY(), xMargin, yMargin);
    }

    /**
     * Render_Sprites bounds at the frame-start object position. Moving objects
     * can use this when their solid helper observes the render flag produced
     * before the current object routine changes x_pos/y_pos.
     */
    /**
     * Whether a frame-start position snapshot exists for this object yet.
     *
     * <p><b>This is not a "has never executed" predicate, and must not be used
     * as one.</b> It said so until 2026-08-21 and the claim was never true:
     * {@link ObjectManager} calls {@link #snapshotPreUpdatePosition()} on a
     * mid-update child the moment it is registered into a slot the frame has
     * not reached yet, precisely so the touch and solid helpers have a
     * frame-start position for the pass the child is about to run. Measured on
     * the {@code arz2} level-select fixture, it is already {@code true} on the
     * first {@code update()} of all 54 bubbles that segment creates.
     *
     * <p>Callers modelling the ROM's {@code render_flags} bit 7 want a
     * different fact. {@code BuildSprites} only rewrites that bit for objects
     * that queued themselves through {@code DisplaySprite} during the frame, so
     * an object that has not executed keeps whatever its setup data seeded --
     * and several setup rows seed it SET. That is a per-object execution fact,
     * not a snapshot fact. Model it with an object-local flag set where the
     * object's own routine reaches its {@code DisplaySprite} equivalent and
     * consumed by {@link ObjectInstance#refreshPostCameraRenderState()}; see
     * {@code BubbleGeneratorObjectInstance.romDisplayedLastPass} and
     * {@code BubbleObjectInstance.romDisplayedLastPass} for the shape, and
     * docs/architecture/audits/trace/2026-08-21-carried-render-flag-family.md
     * for why the flag phase and that guard are only correct together.
     */
    protected boolean hasPreUpdateSnapshot() {
        return preUpdateValid;
    }

    protected boolean isPreUpdateWithinRenderSpriteBounds(int xMargin, int yMargin) {
        return preUpdateValid && cameraBounds.containsRenderSpriteBounds(
                preUpdateX, preUpdateY, xMargin, yMargin);
    }

    /**
     * Configured viewport width in pixels (cameraBounds is updated each frame from
     * camera.getWidth()). Returns 320 at native (NATIVE_4_3), 528 at ULTRA_21_9.
     */
    protected int viewportWidth() {
        return cameraBounds.right() - cameraBounds.left();
    }

    /**
     * Configured viewport height in pixels. Returns 224 (fixed across all aspect ratios).
     */
    protected int viewportHeight() {
        return cameraBounds.bottom() - cameraBounds.top();
    }

    /**
     * ROM out_of_range despawn check for an arbitrary object X (chunk-aligned),
     * width-driven: limit = 128 + viewportWidth() + 192 (= 640 at native 320).
     * <p>
     * Use this for objects that test a custom coordinate (spawnX/origX/baseX)
     * rather than {@link #getX()}. Matches the S1/S2 {@code out_of_range} macro
     * (Macros.asm) exactly when called with {@code getX()}.
     * <p>
     * See docs/status/known-discrepancies.md entry #14 (Object Despawn and Visibility Windows).
     *
     * @param objectX the X coordinate to check (typically a custom spawnX/origX/baseX)
     * @return true if the coordinate is within range (should NOT be deleted)
     */
    protected boolean isInRangeAt(int objectX) {
        int objAligned = objectX & 0xFF80;
        int screenAligned = (cameraBounds.left() - 128) & 0xFF80;
        // ROM does a 16-bit sub.w followed by unsigned bhi, so preserve wrap semantics.
        int dist = (objAligned - screenAligned) & 0xFFFF;
        return dist <= (128 + viewportWidth() + 192);
    }

    /**
     * Variant of {@link #isInRangeAt(int)} that additionally keeps the object
     * alive when its chunk-aligned reference X is up to {@code leftChunks}
     * chunks (0x80 each) to the LEFT of the standard window's left edge.
     * <p>
     * Models ROM out_of_range tails that, instead of deleting on the
     * {@code bhi exit}, test {@code cmpi.w #-(leftChunks*$80),d0 / bhs Display}
     * to keep an object that has just scrolled off the left edge — e.g. the
     * LZ Conveyor / SBZ Spin Conveyor act-3 path
     * (docs/s1disasm/_incObj/63 LZ Conveyor.asm:16-20;
     * docs/s1disasm/_incObj/6F SBZ Spin Platform Conveyor.asm:17-21).
     *
     * @param objectX    the chunk-aligned reference X to test
     * @param leftChunks how many 0x80-px chunks of left slack to allow (ROM {@code $80} -> 1)
     * @return true if in range under the extended-left window
     */
    protected boolean isInRangeAtWithLeftExtension(int objectX, int leftChunks) {
        if (isInRangeAt(objectX)) {
            return true;
        }
        int objAligned = objectX & 0xFF80;
        int screenAligned = (cameraBounds.left() - 128) & 0xFF80;
        int dist = (objAligned - screenAligned) & 0xFFFF;
        // ROM bhs #-(leftChunks*$80): keep when dist (unsigned) >= 0x10000 - leftChunks*0x80.
        return dist >= (0x10000 - leftChunks * 0x80);
    }

    /**
     * ROM-accurate {@code ChkObjectVisible} check.
     * <p>
     * Returns true if the object position falls within the exact screen rectangle:
     * {@code 0 <= (obX - cameraX) < viewportWidth} AND {@code 0 <= (obY - cameraY) < viewportHeight}.
     * No margin, exclusive upper bounds (matching {@code bge.s .offscreen}).
     * <p>
     * At native viewport width (320 px, {@code DISPLAY_ASPECT = NATIVE_4_3}) this is
     * byte-identical to the ROM: {@code 0 <= dx < 320} and {@code 0 <= dy < 224}.
     * At widescreen widths the rectangle widens to match the configured viewport
     * (declared divergence — see docs/status/known-discrepancies.md "Object Despawn and
     * Visibility Windows").
     * <p>
     * Used by objects that call {@code ChkObjectVisible} in the ROM
     * (lava ball maker, gargoyle, invisible barriers).
     * <p>
     * Reference: docs/s1disasm/_incObj/sub ChkObjectVisible.asm
     */
    protected boolean isChkObjectVisible() {
        int dx = getX() - cameraBounds.left();
        if (dx < 0 || dx >= viewportWidth()) return false;
        int dy = getY() - cameraBounds.top();
        return dy >= 0 && dy < viewportHeight();
    }

    /**
     * ROM-accurate out_of_range check (X-only, chunk-aligned).
     * <p>
     * Matches the S1/S2 {@code out_of_range} macro (Macros.asm):
     * <pre>
     *   move.w  obX(a0),d0
     *   andi.w  #$FF80,d0           ; chunk-align object X
     *   move.w  (v_screenposx).w,d1
     *   subi.w  #128,d1
     *   andi.w  #$FF80,d1           ; chunk-align (screenX - 128)
     *   sub.w   d1,d0
     *   cmpi.w  #128+320+192,d0     ; 640 = total range at native 320 px width
     *   bhi     exit
     * </pre>
     * The {@code 320} in the ROM constant is the native screen width.  At native
     * viewport width ({@code DISPLAY_ASPECT = NATIVE_4_3}, viewportWidth = 320)
     * the limit evaluates to exactly 640, reproducing the ROM constant bit-for-bit.
     * At widescreen widths the limit widens to {@code 128 + viewportWidth + 192}
     * so objects near the visible right edge are not incorrectly despawned
     * (declared divergence — see docs/status/known-discrepancies.md "Object Despawn and
     * Visibility Windows").
     *
     * @return true if object is within range (should NOT be deleted)
     */
    protected boolean isInRange() {
        return isInRangeAt(getX());
    }

    /**
     * Signed-compare variant of {@link #isInRangeAt(int)}.
     * <p>
     * The shared {@code out_of_range} macro ends in {@code bhi} — an UNSIGNED
     * compare, so an object left of the window wraps to a huge distance and is
     * deleted (docs/s1disasm/Macros.asm:278-295). A handful of objects carry
     * their own inlined copy of {@code RememberState} that ends in {@code bgt}
     * instead, a SIGNED compare: a negative distance is never greater than
     * {@code $280}, so those objects cannot despawn off the LEFT edge at all.
     * <p>
     * Both S1 disassemblies are built with {@code FixBugs = 0}
     * (docs/s1disasm/sonic.asm:20), which is the branch that keeps the
     * copy-pasted {@code bgt}; the {@code FixBugs} branch replaces the whole
     * block with a plain {@code bra.w RememberState} and would despawn on the
     * left like every other object. The shipped ROM — and therefore every
     * trace — takes the {@code bgt} path, so that is what the engine models.
     *
     * @param objectX the chunk-aligned reference X to test
     * @return true if in range under the signed window (should NOT be deleted)
     */
    protected boolean isInRangeAtSigned(int objectX) {
        int objAligned = objectX & 0xFF80;
        int screenAligned = (cameraBounds.left() - 128) & 0xFF80;
        short dist = (short) (objAligned - screenAligned);
        return dist <= (128 + viewportWidth() + 192);
    }

    /**
     * Adds an already-constructed object using FindNextFreeObj semantics.
     * <p>
     * <b>Does NOT set {@link #CONSTRUCTION_CONTEXT}.</b> If the object's constructor
     * needs {@link #services()}, use {@link #spawnChild(java.util.function.Supplier)}
     * or {@link #spawnFreeChild(java.util.function.Supplier)} instead.
     * <p>
     * Safe to call in test environments where LevelManager may not be initialized.
     *
     * @param object the already-constructed object instance to spawn
     */
    protected void spawnDynamicObject(AbstractObjectInstance object) {
        try {
            ObjectManager om = services().objectManager();
            if (om != null) {
                // A throwaway RewindRecreatable probe instance's construction must never leak a
                // live or pooled side-effect object under its own (about-to-be-discarded)
                // identity -- see ObjectConstructionContext.isProbeConstruction().
                if (ObjectConstructionContext.isProbeConstruction()) {
                    return;
                }
                // During an active-object rewind restore, route construction children to the
                // reconstruction-child scratch so the step-4 reconciliation loop adopts them in
                // place with exact captured state (no double-spawn, parent reference preserved).
                // See spawnChild and ObjectManager.registerRewindReconstructionChild.
                if (ObjectConstructionContext.isRewindActiveRestore()) {
                    om.registerRewindReconstructionChild(object);
                } else {
                    om.addDynamicObjectAfterCurrent(object);
                }
            }
        } catch (IllegalStateException e) {
            // Fallback for test environments or objects not managed by ObjectManager
            try {
                LevelManager lm = staticLevelManager();
                if (lm != null && lm.getObjectManager() != null) {
                    lm.getObjectManager().addDynamicObjectAfterCurrent(object);
                }
            } catch (Exception ex) {
                LOG.fine("Could not spawn dynamic object (test env?): " + ex.getMessage());
            }
        }
    }

    /**
     * Adds an already-constructed object using the ROM's plain {@code AllocateObject}
     * semantics -- a rescan from the base of the dynamic SST array that returns the
     * LOWEST free slot, which may sit below the spawning object's own slot.
     * <p>
     * Use this where the disassembly calls {@code AllocateObject} / {@code FindFreeObj}
     * rather than {@code AllocateObjectAfterCurrent}: because the object-execution walk
     * runs slots in ascending order, a child placed below the parent does not run until
     * the next frame, and that one-frame difference is visible to any routine the child
     * drives. Same probe/rewind-restore guards as {@link #spawnDynamicObject}.
     *
     * @param object the already-constructed object instance to spawn
     */
    protected void spawnDynamicObjectLowestFreeSlot(AbstractObjectInstance object) {
        try {
            ObjectManager om = services().objectManager();
            if (om != null) {
                if (ObjectConstructionContext.isProbeConstruction()) {
                    return;
                }
                if (ObjectConstructionContext.isRewindActiveRestore()) {
                    om.registerRewindReconstructionChild(object);
                } else {
                    om.addDynamicObject(object);
                }
            }
        } catch (IllegalStateException e) {
            try {
                LevelManager lm = staticLevelManager();
                if (lm != null && lm.getObjectManager() != null) {
                    lm.getObjectManager().addDynamicObject(object);
                }
            } catch (Exception ex) {
                LOG.fine("Could not spawn dynamic object (test env?): " + ex.getMessage());
            }
        }
    }

    /**
     * Creates a dynamic child object with FindNextFreeObj semantics.
     * The supplier is called with the {@link #CONSTRUCTION_CONTEXT} set, so the
     * child's constructor can safely call {@link #services()}.
     * <p>
     * Use this when the ROM object calls FindNextFreeObj and expects the child to
     * allocate from the current slot forward.
     *
     * @param factory supplier that constructs the child object
     * @return the constructed child, already added to the object manager
     * @param <T> the child type
     */
    protected <T extends AbstractObjectInstance> T spawnChild(java.util.function.Supplier<T> factory) {
        return spawnChildAfterSlot(getSlotIndex(), factory);
    }

    /**
     * Creates a dynamic child object with FindNextFreeObj semantics scanning forward from an
     * <em>explicit</em> parent slot rather than this object's own slot.
     * <p>
     * A handful of ROM routines load some <em>other</em> object's SST pointer into {@code a0}
     * immediately before calling {@code FindNextFreeObj}, so the scan starts at that object's
     * slot, not the running object's. S1 {@code BSLZ_MakeBall} is the canonical case:
     * {@code lea (a2),a0 / jsr (FindNextFreeObj).l} with {@code a2} holding the target seesaw
     * (docs/s1disasm/_incObj/"7A, 7B Boss - SLZ Main and Spike Balls.asm":291-295).
     *
     * @param parentSlot the SST slot the scan starts strictly after; negative falls back to the
     *                   running object's slot
     * @param factory supplier that constructs the child object
     * @return the constructed child, already added to the object manager
     * @param <T> the child type
     */
    protected <T extends AbstractObjectInstance> T spawnChildAfterSlot(
            int parentSlot, java.util.function.Supplier<T> factory) {
        ObjectServices svc = services();
        return ObjectConstructionContext.construct(svc, () -> {
            T child = factory.get();
            ObjectManager om = svc.objectManager();
            // A throwaway RewindRecreatable probe instance's construction must never leak a
            // live or pooled side-effect object under its own (about-to-be-discarded)
            // identity -- see ObjectConstructionContext.isProbeConstruction().
            if (om != null && !ObjectConstructionContext.isProbeConstruction()) {
                // During an active-object rewind restore the parent is reconstructed to re-derive
                // its non-captured structural state (including its back-references to these
                // children). The children themselves are registered and given their EXACT captured
                // state by the step-4 dynamic-object reconciliation loop, which reuses the
                // instances spawned here. We still register the construction child (so the boss
                // back-reference points at a managed instance the reconciliation can adopt), but
                // mark it as a restore-reconstruction child so the restore loop adopts it in place
                // instead of recreating a duplicate. See ObjectManager.restore() step 4.
                if (ObjectConstructionContext.isRewindActiveRestore()) {
                    om.registerRewindReconstructionChild(child);
                } else if (child.skipsSameFrameUpdateAfterSpawn()) {
                    om.addDynamicObjectAfterCurrentNextFrame(child);
                } else {
                    if (parentSlot >= 0) {
                        om.addDynamicObjectAfterSlot(child, parentSlot);
                    } else {
                        // Isolated tests and unmanaged helpers have no SST identity.
                        om.addDynamicObjectAfterCurrent(child);
                    }
                }
            }
            return child;
        });
    }

    /**
     * Creates a dynamic child object with FindFreeObj semantics.
     * The supplier is called with the {@link #CONSTRUCTION_CONTEXT} set, so the
     * child's constructor can safely call {@link #services()}.
     * <p>
     * Use this when the ROM object calls FindFreeObj and expects the child to
     * take the lowest free SST slot, even if that slot is below the parent.
     *
     * @param factory supplier that constructs the child object
     * @return the constructed child, already added to the object manager
     * @param <T> the child type
     */
    protected <T extends AbstractObjectInstance> T spawnFreeChild(java.util.function.Supplier<T> factory) {
        ObjectServices svc = services();
        return ObjectConstructionContext.construct(svc, () -> {
            T child = factory.get();
            ObjectManager om = svc.objectManager();
            // A throwaway RewindRecreatable probe instance's construction must never leak a
            // live or pooled side-effect object under its own (about-to-be-discarded)
            // identity -- see ObjectConstructionContext.isProbeConstruction().
            if (om != null && !ObjectConstructionContext.isProbeConstruction()) {
                // See spawnChild: during an active-object rewind restore register the construction
                // child as a reconstruction child (no fresh slot allocation) so the step-4
                // reconciliation loop adopts it in place with exact captured state, keeping the
                // boss back-reference valid and avoiding a double-spawn.
                if (ObjectConstructionContext.isRewindActiveRestore()) {
                    om.registerRewindReconstructionChild(child);
                } else if (child.skipsSameFrameUpdateAfterSpawn()) {
                    om.addDynamicObjectNextFrame(child);
                } else {
                    om.addDynamicObject(child);
                }
            }
            return child;
        });
    }

    /**
     * Builds an ObjectSpawn at the given position, preserving all other fields from the
     * original spawn. Use in getSpawn() overrides and dynamic spawn tracking.
     */
    protected ObjectSpawn buildSpawnAt(int x, int y) {
        return new ObjectSpawn(x, y, spawn.objectId(), spawn.subtype(),
                spawn.renderFlags(), spawn.respawnTracked(), spawn.rawYWord(),
                spawn.layoutIndex());
    }

    /**
     * Returns true if any player is currently riding (standing on) this object.
     */
    protected boolean isPlayerRiding() {
        ObjectManager om = services().objectManager();
        return om != null && om.isAnyPlayerRiding(this);
    }

    protected SolidCheckpointBatch checkpointAll() {
        return services().solidExecution().resolveSolidNowAll();
    }

    protected boolean hasStandingContact(SolidCheckpointBatch batch) {
        for (PlayerSolidContactResult result : batch.perPlayer().values()) {
            if (result != null && result.standingNow()) {
                return true;
            }
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Rewind snapshot support
    // -------------------------------------------------------------------------

    /**
     * Captures this object's standard mutable gameplay state for a rewind snapshot.
     *
     * <p>The default implementation covers every field declared on
     * {@code AbstractObjectInstance}: destruction flags, dynamic spawn position,
     * pre-update position cache, touch/solid-contact gating flags, slot index,
     * and respawn state index.
     *
     * <p><strong>Subclass contract:</strong> Subclasses that hold private
     * gameplay-relevant state (boss phase counters, badnik AI timers, sub-state
     * machine indices, etc.) <em>must</em> override this method (and the matching
     * {@link #restoreRewindState}) to include their own fields — otherwise that
     * state will silently fail to round-trip across a rewind.
     *
     * <p>Known subclasses likely to require overrides (non-exhaustive):
     * <ul>
     *   <li>Any boss instance — phase counters, arena/boundary flags, hit counters</li>
     *   <li>{@code AbstractBadnikInstance} subclasses with multi-phase AI — per-frame
     *       timers beyond {@code animTimer}, direction change state</li>
     *   <li>CNZ bumper — reload timer</li>
     *   <li>HTZ earthquake object — oscillation accumulator</li>
     *   <li>Any object that uses {@code objoff_*} scratch fields for state machines</li>
     * </ul>
     *
     * <p>The current default path is centrally gated by
     * {@link GenericRewindEligibility#usesDefaultObjectSubclassCapture(Class)}:
     * subclasses without a concrete rewind override automatically capture fields
     * accepted by {@link GenericFieldCapturer#captureObjectSubclassScalars(AbstractObjectInstance)}.
     *
     * @return immutable snapshot of this object's standard mutable field surface
     */
    public PerObjectRewindSnapshot captureRewindState() {
        return captureRewindState(RewindCaptureContext.none());
    }

    public PerObjectRewindSnapshot captureRewindState(RewindCaptureContext context) {
        PerObjectRewindSnapshot snapshot = new PerObjectRewindSnapshot(
                destroyed,
                destroyedRespawnable,
                dynamicSpawn != null,
                dynamicSpawn != null ? dynamicSpawn.x() : 0,
                dynamicSpawn != null ? dynamicSpawn.y() : 0,
                preUpdateX,
                preUpdateY,
                preUpdateValid,
                preUpdateCollisionFlags,
                skipTouchThisFrame,
                solidContactFirstFrame,
                slotIndex,
                respawnStateIndex,
                null,  // Base class does not capture badnik extra; subclass overrides if needed
                null,  // Base class does not capture badnik subclass extra
                null   // Base class does not capture player extra; subclass overrides if needed
        );
        if (GenericRewindEligibility.usesDefaultObjectSubclassCapture(getClass())) {
            if (GenericRewindEligibility.usesCompactDefaultSubclassCapture(getClass())) {
                RewindObjectStateBlob compactState =
                        GenericFieldCapturer.captureObjectSubclassScalarsCompact(this, context).orElseThrow();
                snapshot = snapshot.withCompactGenericState(compactState);
            } else {
                var genericState = GenericFieldCapturer.captureObjectSubclassScalars(this);
                if (!genericState.keys().isEmpty()) {
                    snapshot = snapshot.withGenericState(genericState);
                }
            }
        }
        return snapshot;
    }

    /**
     * Restores this object's standard mutable gameplay state from a rewind snapshot.
     *
     * <p>See {@link #captureRewindState()} for the subclass contract.
     *
     * @param s the snapshot to restore from
     */
    public void restoreRewindState(PerObjectRewindSnapshot s) {
        restoreRewindState(s, RewindCaptureContext.none());
    }

    public void restoreRewindState(PerObjectRewindSnapshot s, RewindCaptureContext context) {
        this.destroyed = s.destroyed();
        this.destroyedRespawnable = s.destroyedRespawnable();
        if (s.hasDynamicSpawn()) {
            updateDynamicSpawn(s.dynamicSpawnX(), s.dynamicSpawnY());
        } else {
            this.dynamicSpawn = null;
        }
        this.preUpdateX = s.preUpdateX();
        this.preUpdateY = s.preUpdateY();
        this.preUpdateValid = s.preUpdateValid();
        this.preUpdateCollisionFlags = s.preUpdateCollisionFlags();
        this.skipTouchThisFrame = s.skipTouchThisFrame();
        this.solidContactFirstFrame = s.solidContactFirstFrame();
        this.slotIndex = s.slotIndex();
        this.respawnStateIndex = s.respawnStateIndex();
        if (s.compactGenericState() != null) {
            GenericFieldCapturer.restoreObjectSubclassScalarsCompact(this, s.compactGenericState(), context);
        } else if (s.genericState() != null) {
            GenericFieldCapturer.restore(this, s.genericState());
        }
        afterGenericRewindStateRestored(context);
        // badnikExtra is handled by subclass overrides; base class does nothing
    }

    /**
     * Rebuilds state derived from restored generic fields without requiring a full
     * {@link #restoreRewindState(PerObjectRewindSnapshot, RewindCaptureContext)} override.
     */
    protected void afterGenericRewindStateRestored(RewindCaptureContext context) {
    }

    /** Concrete classes already warned about a context-less getRenderManager() call. */
    private static final java.util.Set<String> RENDER_MANAGER_UNAVAILABLE_WARNED =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Returns the ObjectRenderManager, or null if not available.
     * <p>
     * Resolves through per-instance services. If called before services are set
     * (i.e. during construction with no {@code CONSTRUCTION_CONTEXT}), this returns
     * null — an object that caches the result in a field here will then render
     * invisibly. That happens when a renderer-capturing object is spawned via raw
     * {@code new} + {@code addDynamicObject(...)} instead of
     * {@code spawnChild}/{@code spawnFreeChild}/{@code createDynamicObject}. We log
     * once per class so the otherwise-silent failure is visible.
     */
    protected ObjectRenderManager getRenderManager() {
        ObjectServices services = tryServices();
        if (services == null) {
            if (RENDER_MANAGER_UNAVAILABLE_WARNED.add(getClass().getName())) {
                LOG.warning(getClass().getSimpleName() + ": getRenderManager() called with no services and no "
                        + "construction context — returns null. If this is a constructor caching the renderer, "
                        + "spawn via spawnChild/spawnFreeChild/createDynamicObject (not raw addDynamicObject), "
                        + "or fetch the renderer lazily in appendRenderCommands().");
            }
            return null;
        }
        return services.renderManager();
    }

    /**
     * Returns the ready PatternSpriteRenderer for the given art key, or null
     * if the render manager or renderer is unavailable/not ready.
     */
    protected PatternSpriteRenderer getRenderer(String artKey) {
        ObjectRenderManager rm = getRenderManager();
        if (rm == null) return null;
        PatternSpriteRenderer renderer = rm.getRenderer(artKey);
        return (renderer != null && renderer.isReady()) ? renderer : null;
    }
}
