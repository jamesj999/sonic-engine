package com.openggf.game.sonic1.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic1.Sonic1RingPlacement;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.TouchActorContextPolicy;
import com.openggf.level.objects.TouchAttackBouncePolicy;
import com.openggf.level.objects.TouchCategory;
import com.openggf.level.objects.TouchCategoryDecodeMode;
import com.openggf.level.objects.TouchOverlapStopPolicy;
import com.openggf.level.objects.TouchResponseProfile;
import com.openggf.level.objects.TouchShieldDeflectCapability;
import com.openggf.level.objects.TouchResponseListener;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.objects.TouchResponseResult;
import com.openggf.level.rings.RingManager;
import com.openggf.level.rings.RingSpawn;

import java.util.ArrayList;
import java.util.List;

/**
 * ROM-faithful ring object for Sonic 1. Each instance represents a single ring
 * occupying one dynamic slot, matching the ROM's Ring_Main / FindFreeObj behavior.
 *
 * <p>Ring rendering and collection detection are handled by {@link RingManager}
 * (unified across all games). This object manages:
 * <ul>
 *   <li>Slot allocation (parent spawns children via {@code spawnChild()})</li>
 *   <li>Touch response blocking (collision flags $47 for ReactToItem scan order)</li>
 *   <li>Sparkle countdown and self-destruction</li>
 * </ul>
 */
public class Sonic1RingInstance extends AbstractObjectInstance
        implements TouchResponseProvider, TouchResponseListener, RewindRecreatable {

    /**
     * The ring's published touch profile. Identical to what
     * {@code TouchResponseProfile.fromProvider(this)} derives — the ring uses the
     * normal category decode, has no shield reaction, no render-flag gate and a
     * single touch region — except that it is stated once here rather than
     * assembled from hooks, so the touch controller and the object agree on one
     * object of truth. {@code continuousCallbacks} is the ROM behaviour
     * documented on {@link #requiresContinuousTouchCallbacks()}.
     */
    private static final TouchResponseProfile TOUCH_RESPONSE_PROFILE = new TouchResponseProfile(
            TouchCategoryDecodeMode.NORMAL,
            // continuousCallbacks: the ROM re-check documented on
            // requiresContinuousTouchCallbacks() below.
            true,
            // requiresRenderFlagForTouch: the provider default; the ring is only
            // testable while the render-flag equivalent is set.
            true,
            false,
            TouchShieldDeflectCapability.NONE,
            0,
            TouchAttackBouncePolicy.STANDARD_ENEMY_KILL,
            TouchActorContextPolicy.MAIN_FULL_SIDEKICK_HURT_ONLY,
            TouchOverlapStopPolicy.STOP_AFTER_FIRST_OVERLAP_FOR_ALL_ACTORS);

    /** S1 ring collision type: $47 = powerup category ($40) + size index 7. */
    public static final int RING_COLLISION_FLAGS = 0x47;
    private static final int S1_OUT_OF_RANGE_LIMIT = 128 + 320 + 192;

    private enum State { INIT, ANIMATE, SPARKLE }

    private final RingSpawn ringSpawn;
    private final List<RingSpawn> childRingSpawns;
    // Un-finaled for rewind: outOfRangeAnchorX is the PARENT placement X, NOT recoverable
    // from a child ring's spawn (child spawn x = childX), so the generic field capturer
    // reapplies the captured value after the recreate hook uses a placeholder.
    private int outOfRangeAnchorX;

    private State state;

    /**
     * Creates a parent ring instance from a layout entry.
     *
     * @param spawn           the ObjectSpawn from layout data
     * @param allRingSpawns   expanded ring positions: index 0 = this ring, 1..N = children
     */
    public Sonic1RingInstance(ObjectSpawn spawn, List<RingSpawn> allRingSpawns) {
        super(spawn, "Ring");
        this.ringSpawn = allRingSpawns.get(0);
        this.childRingSpawns = allRingSpawns.size() > 1
                ? allRingSpawns.subList(1, allRingSpawns.size())
                : List.of();
        this.outOfRangeAnchorX = spawn.x();
        this.state = State.INIT;
    }

    /**
     * Creates a child ring instance spawned by a parent.
     *
     * @param spawn     dynamically built spawn at child position
     * @param ringSpawn the child's RingSpawn reference in RingManager
     */
    Sonic1RingInstance(ObjectSpawn spawn, RingSpawn ringSpawn, int outOfRangeAnchorX) {
        super(spawn, "Ring");
        this.ringSpawn = ringSpawn;
        this.childRingSpawns = List.of();
        this.outOfRangeAnchorX = outOfRangeAnchorX;
        this.state = State.ANIMATE;
    }

    private Sonic1RingInstance() {
        this(new ObjectSpawn(0, 0, 0, 0, 0, false, 0), new RingSpawn(0, 0), 0);
    }

    @Override
    public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        ObjectSpawn rewindSpawn = ctx.spawn();
        return new Sonic1RingInstance(
                rewindSpawn,
                resolveCanonicalRingSpawn(ctx, rewindSpawn.x(), rewindSpawn.y()),
                rewindSpawn.x());
    }

    /**
     * Resolves the canonical {@link RingSpawn} reference for this ring's position
     * from the restore-time {@link RingManager}, rather than constructing a fresh
     * instance. A freshly-built {@code RingSpawn} is structurally equal to the
     * canonical one RingManager tracks but never identity-equal, which permanently
     * routes every subsequent lookup for this restored ring through the equals-based
     * {@code getSpawnIndex} fallback (and its per-call warning log — the MZ3
     * "identity miss" spam after a rewind/checkpoint restore). Falls back to a fresh
     * {@code RingSpawn} only when the canonical reference cannot be resolved (no live
     * RingManager, or the coordinates aren't a registered ring spawn), matching the
     * pre-fix behavior.
     */
    private static RingSpawn resolveCanonicalRingSpawn(RewindRecreateContext ctx, int x, int y) {
        ObjectServices services = ctx.objectServices();
        RingManager ringManager = services != null ? services.ringManager() : null;
        RingSpawn canonical = ringManager != null ? ringManager.resolveCanonicalSpawn(x, y) : null;
        return canonical != null ? canonical : new RingSpawn(x, y);
    }

    @Override
    public int getReservedChildSlotCount() {
        return childRingSpawns.size();
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        switch (state) {
            case INIT -> {
                RingManager ringManager = services().ringManager();
                spawnChildren(ringManager);
                // ROM parity: Ring_Main clears the ring group's respawn-block flag
                // (bit 7 of its v_objstate entry) for every uncollected ring before
                // FindFreeObj — `bclr #7,(a2)` in Ring_Main / Ring_MakeRings
                // (docs/s1disasm/_incObj/25, 37 Rings.asm:91,99). OPL_SpawnObj's
                // REV01 `bset #7,2(a2,d2.w)` (docs/s1disasm/_inc/ObjPosLoad.asm:269)
                // set the bit when the group (re)spawned; the group clears it again
                // on execution so ObjPosLoad re-spawns it when the cursor re-scans
                // the entry (e.g. a camera backtrack). Without this, a still-loaded
                // or previously-loaded ring group keeps bit 7 set and the backward
                // (OPL_MovedLeft) scan skips it — shifting slot allocation (S1 LZ2
                // f217/SYZ3 tf6086 slot-cadence cascade). A fully-collected group
                // keeps bit 7 set (no `bclr`), matching ROM's Ring_SpawningDone.
                if (groupHasUncollectedRing(ringManager)) {
                    ObjectManager objectManager = services().objectManager();
                    if (objectManager != null) {
                        objectManager.clearSpawnCounterActiveBit(getSpawn());
                    }
                }
                if (ringManager != null && ringManager.isCollected(ringSpawn)) {
                    setDestroyed(true);
                    return;
                }
                state = State.ANIMATE;
            }
            case ANIMATE -> {
                RingManager ringManager = services().ringManager();
                if (ringManager != null && ringManager.isCollected(ringSpawn)) {
                    state = State.SPARKLE;
                }
            }
            case SPARKLE -> {
                RingManager ringManager = services().ringManager();
                int gameplayFrameCounter = vIntRunCount;
                ObjectManager objectManager = services().objectManager();
                if (objectManager != null) {
                    // ROM parity: Ring_Sparkle advances only when ExecuteObjects runs.
                    // Lag frames still bump v_vbla_byte, but they do not run the ring
                    // object's AnimateSprite/DeleteObject path. Use gameplay-frame
                    // time here so collected ring slots persist through lag exactly
                    // as long as the ROM object routine does.
                    gameplayFrameCounter = objectManager.getFrameCounter();
                }
                if (ringManager == null || ringManager.isCollectedAndSparkleDone(
                        ringSpawn, gameplayFrameCounter)) {
                    setDestroyed(true);
                }
            }
        }
    }

    /**
     * ROM Ring_Main runs {@code bclr #7,(a2)} only for rings that have not been
     * collected yet (each collected ring is skipped via the per-ring
     * "collected" bit). Returns true when at least one ring in this group
     * (parent or child) is still uncollected, so the respawn-block flag should
     * be cleared. A null ring manager (no collection state) is treated as
     * fully uncollected.
     */
    private boolean groupHasUncollectedRing(RingManager ringManager) {
        if (ringManager == null) {
            return true;
        }
        if (!ringManager.isCollected(ringSpawn)) {
            return true;
        }
        for (RingSpawn child : childRingSpawns) {
            if (!ringManager.isCollected(child)) {
                return true;
            }
        }
        return false;
    }

    private void spawnChildren(RingManager ringManager) {
        if (childRingSpawns.isEmpty()) {
            return;
        }
        int subtype = spawn.subtype();
        int[] spacing = Sonic1RingPlacement.getRingSpacing(subtype);
        int dx = spacing[0];
        int dy = spacing[1];
        int baseX = spawn.x();
        int baseY = spawn.y();

        List<ChildRingSpawn> liveChildRings = new ArrayList<>(childRingSpawns.size());
        for (int i = 0; i < childRingSpawns.size(); i++) {
            RingSpawn childRing = childRingSpawns.get(i);
            if (ringManager != null && ringManager.isCollected(childRing)) {
                continue;
            }
            liveChildRings.add(new ChildRingSpawn(i, childRing));
        }

        ObjectManager om = services().objectManager();
        if (om != null) {
            // ROM parity: Ring_Main checks each respawn bit before FindFreeObj.
            // Collected child rings skip Ring_SpawnRing and do not consume an SST slot.
            om.allocateChildSlots(spawn, liveChildRings.size());
        }
        int reservedSlotIndex = 0;
        for (ChildRingSpawn childRingSpawn : liveChildRings) {
            int childIndex = childRingSpawn.index();
            int childX = baseX + (childIndex + 1) * dx;
            int childY = baseY + (childIndex + 1) * dy;
            RingSpawn childRing = childRingSpawn.ring();
            if (om != null) {
                // Use the slots allocated above from the live SST state of this
                // exec sweep so child numbers match the ROM's FindFreeObj order.
                // The child constructor doesn't need services() — ring children only
                // use services() after construction, so it's safe to construct without
                // CONSTRUCTION_CONTEXT. setServices() is called by addDynamicObjectToReservedSlot.
                Sonic1RingInstance child = new Sonic1RingInstance(
                        buildSpawnAt(childX, childY), childRing, outOfRangeAnchorX);
                om.addDynamicObjectToReservedSlot(child, spawn, reservedSlotIndex++);
            } else {
                spawnChild(() -> new Sonic1RingInstance(
                        buildSpawnAt(childX, childY), childRing, outOfRangeAnchorX));
            }
        }
    }

    @Override
    public int getOutOfRangeReferenceX() {
        return outOfRangeAnchorX;
    }

    @Override
    public boolean usesCustomOutOfRangeCheck() {
        return true;
    }

    @Override
    public boolean isCustomOutOfRange(int cameraX) {
        if (state != State.ANIMATE) {
            // docs/s1disasm/s1disasm/_incObj/25, 37 Rings.asm:
            // Ring_Animate calls out_of_range after DisplaySprite, but
            // Ring_Sparkle only runs AnimateSprite and DisplaySprite until
            // Ring_Delete. Collected ring slots must survive camera drift.
            return false;
        }
        int objRounded = outOfRangeAnchorX & 0xFF80;
        int screenRounded = (cameraX - 128) & 0xFF80;
        int distance = (objRounded - screenRounded) & 0xFFFF;
        return distance > S1_OUT_OF_RANGE_LIMIT;
    }

    // ── TouchResponseProvider ─────────────────────────────────────────────

    @Override
    public int getCollisionFlags() {
        return state == State.ANIMATE ? RING_COLLISION_FLAGS : 0;
    }

    @Override
    public int getCollisionProperty() {
        return 0;
    }

    /**
     * ROM parity: {@code React_CollisionDetected}'s {@code col_item} ring branch
     * (docs/s1disasm/_incObj/"Sonic ReactToItem.asm":191-203) is re-evaluated on
     * every frame the hitboxes overlap. It carries no "already touched" latch:
     * the only gate is {@code cmpi.w #90,flashtime(a0) / bhs .return}, and when
     * that gate blocks the pickup the ring stays at {@code Ring_Animate} with its
     * {@code obColType} intact, so the very next frame checks again. The engine's
     * default edge-trigger would consume the overlap on the blocked frame and
     * never re-arm while Sonic stands still, losing the pickup entirely. Once the
     * ring is collected it advances to {@code Ring_Sparkle} and stops reporting
     * collision flags, which is what actually latches the response — exactly as
     * {@link #getCollisionFlags()} models above.
     */
    @Override
    public boolean requiresContinuousTouchCallbacks() {
        return true;
    }

    @Override
    public TouchResponseProfile getTouchResponseProfile() {
        return TOUCH_RESPONSE_PROFILE;
    }

    @Override
    public TouchResponseProfile getTouchResponseProfile(boolean multiRegionSource) {
        return TOUCH_RESPONSE_PROFILE;
    }

    @Override
    public void onTouchResponse(PlayableEntity playerEntity, TouchResponseResult result, int frameCounter) {
        if (state != State.ANIMATE || result.category() != TouchCategory.SPECIAL) {
            return;
        }
        if (!(playerEntity instanceof com.openggf.sprites.playable.AbstractPlayableSprite player)) {
            return;
        }
        RingManager ringManager = services().ringManager();
        if (ringManager == null || !ringManager.collectPlacedRing(ringSpawn, player, frameCounter)) {
            return;
        }
        // ROM: ReactToItem advances routine immediately on touch, so the ring
        // stops colliding in the same frame even though this engine runs the
        // ring object's normal update before the player touch pass.
        state = State.SPARKLE;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        // No rendering: handled by RingManager
    }

    @Override
    public boolean isHighPriority() {
        return false;
    }

    @Override
    public String traceDebugDetails() {
        return String.format("state=%s ring=@%04X,%04X children=%d anchor=%04X",
                state,
                ringSpawn.x() & 0xFFFF,
                ringSpawn.y() & 0xFFFF,
                childRingSpawns.size(),
                outOfRangeAnchorX & 0xFFFF);
    }

    private record ChildRingSpawn(int index, RingSpawn ring) {
    }
}
