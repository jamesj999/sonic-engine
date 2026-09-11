package com.openggf.game.sonic2.objects;

import com.openggf.debug.DebugRenderContext;
import com.openggf.game.sonic2.S2SpriteDataLoader;
import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.game.sonic2.constants.Sonic2Constants;
import com.openggf.game.sonic2.scroll.Sonic2ZoneConstants;
import com.openggf.game.PlayableEntity;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.PatternDesc;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.level.render.SpriteMappingFrame;
import com.openggf.level.render.SpritePieceRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.util.LazyMappingHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Object 0x6A - MCZ Rotating Platforms / MTZ Moving Platforms.
 * <p>
 * In MCZ (mystic_cave_zone): Large wooden crates (64x64) that move continuously
 * once initialised (routine 4 = loc_27C66 — no standing gate). Subtype 0x18
 * spawns 2 child platforms at (+/-64, +64) with subtypes 6 and C.
 * <p>
 * In MTZ (metropolis_zone): Uses level art, smaller y_radius, faster four-phase
 * cycle. Movement is gated on the player walking off (routine 2 = loc_27BDE).
 * Subtype 0x18 child-spawning is skipped (Obj6A_Init branches to loc_27BC4 at
 * s2.asm:53697 before the child-spawn block).
 * <p>
 * <b>Disassembly Reference:</b> s2.asm lines 53670-53871 (Obj6A code + tables).
 * <p>
 * <b>Movement Tables (each entry is 3 words = 6 bytes: x_vel, y_vel, duration):</b>
 * <ul>
 *   <li>byte_27CDC: MTZ - 4 phases (s2.asm:53853)</li>
 *   <li>byte_27CF4: MCZ counter-clockwise (no x_flip) - 5 declared, 4 active (s2.asm:53859)</li>
 *   <li>byte_27D12: MCZ clockwise (x_flip set) - 5 declared, 4 active (s2.asm:53866)</li>
 * </ul>
 * Phase advance wraps at {@code cmpi.b #6*4,objoff_38} (s2.asm:53846), so only
 * the first 4 entries of each table are reachable through normal cycling.
 * <p>
 * <b>Property table indexing pitfall:</b> ROM stores the full {@code subtype}
 * byte into {@code objoff_38} (s2.asm:53750), then uses it as a byte offset
 * into the velocity table. The engine must NOT collapse this to {@code subtype
 * & 0x0F} or treat the index as an array index. Initial subtypes from layout
 * (0, 6, 0xC, 0x18) directly land on table entries 0, 1, 2 (and 3-via-wrap for
 * the parent's first phase load).
 */
public class MCZRotPformsObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener, RewindRecreatable {

    private static final Logger LOGGER = Logger.getLogger(MCZRotPformsObjectInstance.class.getName());

    // Movement tables -- format: {x_vel, y_vel, duration} per phase (16-bit
    // signed values, x_vel/y_vel in 16.8 subpixel-per-frame fixed point).

    // byte_27CDC: MTZ - 4 active phases (faster down/turn cycle).
    private static final int[][] MOVE_TABLE_MTZ = {
            {0x0000, 0x0400, 0x10},
            {0x0400, -0x0200, 0x20},
            {0x0000, 0x0400, 0x10},
            {-0x0400, -0x0200, 0x20},
    };

    // byte_27CF4: MCZ counter-clockwise (no x_flip) - 4 active phases.
    // ROM wraps at cmpi.b #6*4,objoff_38 so phase 4 (5th entry) is unreachable
    // by normal cycling; include it so a starting objoff_38 of 0x18 reads it
    // before the wrap.
    private static final int[][] MOVE_TABLE_MCZ_CCW = {
            {0x0000, 0x0100, 0x40},
            {-0x0100, 0x0000, 0x80},
            {0x0000, -0x0100, 0x40},
            {0x0100, 0x0000, 0x80},
            {0x0100, 0x0000, 0x40},
    };

    // byte_27D12: MCZ clockwise (x_flip set) - 4 active phases (+ unreachable 5th).
    private static final int[][] MOVE_TABLE_MCZ_CW = {
            {0x0000, 0x0100, 0x40},
            {0x0100, 0x0000, 0x80},
            {0x0000, -0x0100, 0x40},
            {-0x0100, 0x0000, 0x80},
            {-0x0100, 0x0000, 0x40},
    };

    // Collision parameters from disassembly.
    //   MTZ (s2.asm:53692-53693): width_pixels = 0x20, y_radius = 0x0C
    //   MCZ (s2.asm:53701-53702): width_pixels = 0x20, y_radius = 0x20
    // Collision half-width = width_pixels + 0x0B (s2.asm:53797-53798 / 53822-53823).
    private static final int WIDTH_PIXELS = 0x20;
    private static final int MTZ_Y_RADIUS = 0x0C;
    private static final int MCZ_Y_RADIUS = 0x20;
    private static final int HALF_WIDTH = WIDTH_PIXELS + 0x0B;

    // Phase wrap threshold: s2.asm:53846 -- cmpi.b #6*4,objoff_38.
    private static final int PHASE_WRAP_THRESHOLD = 24;

    /** Shared Obj65_a level-art mappings for MTZ rendering, lazily loaded from ROM. */
    private static final LazyMappingHolder MAPPINGS = new LazyMappingHolder();

    // Position state (16.8 fixed point for subpixel accuracy).
    private int x;
    private int y;
    private int xFixed;
    private int yFixed;
    private int xVel;
    private int yVel;

    // Original spawn position (objoff_32, objoff_30 in disassembly).
    private int baseX;
    private int baseY;

    // Movement state.
    private int phaseIndex;         // objoff_38: byte-offset cursor into objoff_2C table
    private int phaseDuration;      // objoff_34: frame counter for current phase
    private boolean activated;      // objoff_36: MTZ gate -- 0 = wait, 1 = move
    private int prevStandingFlags;  // objoff_3C: previous frame's player standing status

    // Zone-derived configuration. Initialized lazily after ObjectServices has
    // been injected; constructors must not call services().
    private boolean initialized;
    private boolean isMtz;
    private int[][] moveTable;
    private int yRadius;
    private boolean xFlip;
    private boolean yFlip;
    private boolean isParent;  // Only MCZ subtype 0x18 acts as parent.

    // Child tracking for cleanup on unload.
    private final List<MCZRotPformsObjectInstance> children = new ArrayList<>();
    private MCZRotPformsObjectInstance owner;
    private boolean childrenSpawned;

    // ROM parity: Obj6A_Init (s2.asm:53686-53751) sets up phase params via
    // loc_27CA2 but does NOT call ObjectMove. The first move happens on the
    // NEXT frame when the appropriate routine (2 for MTZ, 4 for MCZ) runs.
    // Without this skip, the engine's spawn frame applies an extra move because
    // syncActiveSpawnsLoad runs before runExecLoop, so the constructor's phase
    // load + the same-frame update() call would move twice relative to ROM's
    // init-then-next-frame ordering.
    private boolean spawnFrameSkipPending;

    public MCZRotPformsObjectInstance(ObjectSpawn spawn, String name) {
        this(spawn, name, spawn.x(), spawn.y());
    }

    private MCZRotPformsObjectInstance(
            ObjectSpawn spawn, String name, int unloadAnchorX, int unloadAnchorY) {
        super(spawn, name);

        this.xFlip = (spawn.renderFlags() & 0x01) != 0;
        this.yFlip = (spawn.renderFlags() & 0x02) != 0;

        this.baseX = unloadAnchorX;
        this.baseY = unloadAnchorY;
        this.x = spawn.x();
        this.y = spawn.y();
        this.xFixed = x << 8;
        this.yFixed = y << 8;

        // ROM s2.asm:53750 -- move.b subtype(a0),objoff_38(a0).
        // The FULL subtype byte is the byte-offset cursor, not subtype & 0x0F.
        // Subtypes 0, 6, 0xC, 0x18 land on table entries 0, 1, 2, (3-via-wrap).
        this.phaseIndex = spawn.subtype() & 0xFF;
        this.phaseDuration = 0;
        this.activated = false;
        this.prevStandingFlags = 0;
        this.xVel = 0;
        this.yVel = 0;

        this.childrenSpawned = false;
        // Suppress the spawn-frame move; see field docstring above.
        this.spawnFrameSkipPending = true;

        updateDynamicSpawn(x, y);
    }

    @Override
    public MCZRotPformsObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new MCZRotPformsObjectInstance(ctx.spawn(), getName());
    }

    private void ensureInitialized() {
        if (initialized) {
            return;
        }

        // Zone determines which table, y_radius, and gating behavior to use.
        // s2.asm:53696 -- cmpi.b #mystic_cave_zone,(Current_Zone).w
        // services().currentZone() returns the ROM zone id.
        int zoneId = services().currentZone();
        this.isMtz = (zoneId != Sonic2ZoneConstants.ROM_ZONE_MCZ);

        if (isMtz) {
            this.moveTable = MOVE_TABLE_MTZ;
            this.yRadius = MTZ_Y_RADIUS;
            // MTZ skips the subtype-0x18 child-spawn block (bne.w loc_27BC4
            // at s2.asm:53697 jumps past lines 53698-53731).
            this.isParent = false;
        } else {
            // MCZ selects table from x_flip (s2.asm:53703-53706).
            this.moveTable = xFlip ? MOVE_TABLE_MCZ_CW : MOVE_TABLE_MCZ_CCW;
            this.yRadius = MCZ_Y_RADIUS;
            this.isParent = (spawn.subtype() == 0x18);
        }

        // Activation routing matches ROM routine selection:
        //   MTZ (routine 2, loc_27BDE): wait for player to walk off.
        //   MCZ (routine 4, loc_27C66): move unconditionally from the start.
        this.activated = !isMtz;

        // Init's tail call to loc_27CA2 (s2.asm:53751) preloads the first
        // phase's velocity/duration and advances objoff_38 by 6.
        loadPhaseParameters();
        initialized = true;

        LOGGER.fine(() -> String.format(
                "Obj6A init: pos=(%d,%d), subtype=0x%02X, xFlip=%b, isParent=%b, isMtz=%b",
                baseX, baseY, spawn.subtype(), xFlip, isParent, isMtz));
    }

    @Override
    public int getX() {
        return x;
    }

    @Override
    public int getY() {
        return y;
    }

    @Override
    public int getOutOfRangeReferenceX() {
        // ROM Obj6A stores the initial x_pos in objoff_32 and passes that to
        // MarkObjGone2 after each movement/collision step.
        return baseX;
    }

    @Override
    public SolidObjectParams getSolidParams() {
        ensureInitialized();
        // s2.asm:53797-53802 (MTZ) and 53822-53827 (MCZ):
        //   d1 = width_pixels + 0x0B  -> collision half-width
        //   d2 = y_radius              -> collision half-height (top)
        //   d3 = y_radius + 1          -> collision half-height (bottom)
        return SolidObjectParams.of(HALF_WIDTH, yRadius, yRadius + 1);
    }

    @Override
    public int getBalanceWidthPixels() {
        return 0x20; // Obj6A MTZ init width_pixels, s2.asm:53686-53702
    }

    @Override
    public boolean isTopSolidOnly() {
        // ROM uses JmpTo13_SolidObject (fully solid from all sides).
        return false;
    }

    @Override
    public boolean usesInclusiveRightEdge() {
        // Obj6A reaches JmpTo13_SolidObject after moving in MCZ and MTZ
        // (s2.asm:54276,54301). SolidObject_cont rejects only with `bhi`
        // (s2.asm:35354), so relX == width*2 remains a live contact.
        return true;
    }

    @Override
    public boolean usesInstanceSolidStateLatchKey() {
        // Obj6A rewrites dynamic spawn coordinates via updateDynamicSpawn as it
        // moves, while ROM stores standing/pushing bits in the live SST status
        // byte for this slot. Keep the latch keyed to the Java instance/slot,
        // not the mutable spawn coordinates.
        return true;
    }

    @Override
    public boolean preservesSidekickCpuPushGraceWhileRiding(PlayableEntity player) {
        // TailsCPU_Normal reads Status_Push before the later Obj6A
        // SolidObject pass can clear or refresh the object's live status byte
        // (s2.asm:39297-39300, Obj6A solid call at s2.asm:54301).
        return player != null && player.isCpuControlled();
    }

    @Override
    public int sidekickCpuPushGraceMinimumFramesWhileRiding(PlayableEntity player) {
        return preservesSidekickCpuPushGraceWhileRiding(player) ? 8 : Integer.MAX_VALUE;
    }

    @Override
    public boolean isSolidFor(PlayableEntity playerEntity) {
        ensureInitialized();
        // ROM parity: the MCZ subtype-0x18 #$18 check
        // (cmpi.b #$18,subtype / bne.w loc_27BD0) gates ONLY the child-spawn
        // block (s2.asm:54184-54204). After allocating its two children the parent
        // falls through (bra.s loc_27BC4 -> loc_27BD0 -> loc_27CA2) and runs
        // routine 4 (loc_27C66, s2.asm:54283-54305) every frame as a full
        // moving/solid/rendering platform via JmpTo13_SolidObject. It is NOT
        // an invisible non-solid spawner, so the parent collides like any
        // other Obj6A platform.
        return !isDestroyed();
    }

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        // Standing detection is polled in update() via ObjectManager.isAnyPlayerRiding.
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        ensureInitialized();
        if (isDestroyed()) {
            return;
        }

        // MCZ subtype 0x18 parent: Obj6A_Init allocates its two child platforms
        // (s2.asm:54184-54204), falls through the shared init tail
        // (s2.asm:54204,54217-54224), and then runs as a normal moving/solid
        // platform on routine 4 (s2.asm:54283-54305). So spawn the children
        // once, then continue into the same move/collide path the children use --
        // the parent is NOT an invisible non-moving spawner.
        if (isParent) {
            ensureChildrenSpawned();
        }

        // ROM parity: Obj6A_Init returns without calling ObjectMove. Skip the
        // first update() invocation so the constructor's loadPhaseParameters()
        // does not combine with a same-frame move.
        if (spawnFrameSkipPending) {
            spawnFrameSkipPending = false;
            updateDynamicSpawn(x, y);
            return;
        }

        boolean playerStanding = isPlayerStandingOnUs();

        if (isMtz) {
            updateMtz(playerStanding);
        } else {
            updateMcz();
        }

        updateDynamicSpawn(x, y);
    }

    /**
     * MTZ routine 2 (loc_27BDE @ s2.asm:53754): wait for the player to walk off,
     * then cycle through the movement table from the next frame onward.
     */
    private void updateMtz(boolean playerStanding) {
        if (activated) {
            // loc_27C2E (s2.asm:53786): move, decrement, advance phase on hit.
            applyMovement();
        } else {
            // Standing-state edge detection (lines 53756-53783): activation
            // triggers when prev_standing was set but current is clear.
            // ROM masks status with standing_mask (BOTH p1_standing and p2_standing),
            // so the walk-off edge fires for EITHER player. isPlayerStandingOnUs()
            // wraps isAnyPlayerRiding(), which is the exact equivalent — no per-player
            // edge tracking is required.
            if (!playerStanding && prevStandingFlags != 0) {
                activated = true;
            }
            // ROM sets objoff_36=1 same frame but branches via loc_27C3E,
            // skipping the move; equivalent here to "activate but don't move
            // this frame". The first move happens next update.
        }
        prevStandingFlags = playerStanding ? 1 : 0;
    }

    /**
     * MCZ routine 4 (loc_27C66 @ s2.asm:53810): move unconditionally every frame.
     */
    private void updateMcz() {
        applyMovement();
    }

    /**
     * Apply velocity (16.8) and tick duration. When the duration expires,
     * advance to the next phase via {@link #loadPhaseParameters}.
     */
    private void applyMovement() {
        // ROM: jsr (ObjectMove).l adds x_vel/y_vel to x_pos/y_pos.
        xFixed += xVel;
        yFixed += yVel;
        x = xFixed >> 8;
        y = yFixed >> 8;

        // s2.asm:53788 -- subq.w #1,objoff_34(a0); bne.s loc_27C3E.
        phaseDuration--;
        if (phaseDuration <= 0) {
            loadPhaseParameters();
        }
    }

    /**
     * Implements loc_27CA2 (s2.asm:53835): read the current 6-byte entry from
     * the velocity table at byte offset {@code phaseIndex}, advance the cursor
     * by 6, wrap at {@code 6*4 = 24}, AND clear the activation flag
     * ({@code move.b #0,objoff_36(a0)} at s2.asm:53844). The activation reset
     * is critical for MTZ behavior: each completed phase requires the player
     * to walk on then off again before the next phase can run. Without it,
     * the engine moves platforms continuously after the first activation,
     * cycling through all 4 phases unattended.
     */
    private void loadPhaseParameters() {
        // ROM uses the raw byte offset to index a packed word table. Each
        // entry is 6 bytes (3 words), so the array index is phaseIndex / 6.
        int entryIndex = phaseIndex / 6;

        if (entryIndex >= 0 && entryIndex < moveTable.length) {
            int[] phase = moveTable[entryIndex];
            xVel = phase[0];
            yVel = phase[1];
            phaseDuration = phase[2];
        } else {
            // Defensive: indices > table length only occur if the subtype
            // byte was set beyond the cycle range; mirror ROM by wrapping.
            xVel = 0;
            yVel = 0;
            phaseDuration = 0;
        }

        // s2.asm:53844 -- move.b #0,objoff_36(a0). Phase end clears the
        // activation gate so MTZ platforms must wait for another walk-off
        // event before the next phase runs. MCZ keeps activated=true via
        // the unconditional routine 4 path (loc_27C66 ignores objoff_36),
        // so only re-arm the gate for MTZ.
        if (isMtz) {
            activated = false;
        }

        // s2.asm:53845-53848: addq.b #6,objoff_38; cmpi.b #6*4,objoff_38;
        // blo continue; else move.b #0,objoff_38.
        phaseIndex += 6;
        if (phaseIndex >= PHASE_WRAP_THRESHOLD) {
            phaseIndex = 0;
        }
    }

    private boolean isPlayerStandingOnUs() {
        ObjectManager manager = services().objectManager();
        return manager != null && manager.isAnyPlayerRiding(this);
    }

    /**
     * MCZ subtype 0x18 (s2.asm:54184-54204): spawn two child platforms at
     * (+/-64, +64) with subtypes 6 and C. Obj6A_InitSubObject first copies the
     * parent's x_pos/y_pos into each child's objoff_32/30 unload anchor, then
     * the caller shifts only live x_pos/y_pos (s2.asm:54207-54213).
     */
    private void ensureChildrenSpawned() {
        if (!childrenSpawned && spawnChildren()) {
            childrenSpawned = true;
        }
    }

    private boolean spawnChildren() {
        ObjectManager manager = services().objectManager();
        if (manager == null) {
            return false;
        }

        int child1Subtype = xFlip ? 0x0C : 0x06;
        ObjectSpawn child1Spawn = new ObjectSpawn(
                baseX + 0x40,
                baseY + 0x40,
                spawn.objectId(),
                child1Subtype,
                spawn.renderFlags(),
                spawn.respawnTracked(),
                spawn.rawYWord());
        MCZRotPformsObjectInstance child1 = spawnChild(
                () -> new MCZRotPformsObjectInstance(child1Spawn, "MCZRotPforms", baseX, baseY));
        attachChild(child1);

        int child2Subtype = xFlip ? 0x06 : 0x0C;
        ObjectSpawn child2Spawn = new ObjectSpawn(
                baseX - 0x40,
                baseY + 0x40,
                spawn.objectId(),
                child2Subtype,
                spawn.renderFlags(),
                spawn.respawnTracked(),
                spawn.rawYWord());
        MCZRotPformsObjectInstance child2 = spawnChild(
                () -> new MCZRotPformsObjectInstance(child2Spawn, "MCZRotPforms", baseX, baseY));
        attachChild(child2);
        return true;
    }

    private void attachChild(MCZRotPformsObjectInstance child) {
        child.owner = this;
        children.add(child);
    }

    @Override
    public void onUnload() {
        MCZRotPformsObjectInstance previousOwner = owner;
        owner = null;
        if (previousOwner != null) {
            previousOwner.children.remove(this);
        }

        // Obj6A's two AllocateObjectAfterCurrent children are independent SST
        // entries (docs/s2disasm/s2.asm:54184-54204). They execute separately,
        // even though their MarkObjGone2 tails all read the shared parent anchor
        // copied into objoff_32 (s2.asm:54207-54213,54278-54280,54303-54305).
        List<MCZRotPformsObjectInstance> ownedChildren = new ArrayList<>(children);
        for (MCZRotPformsObjectInstance child : ownedChildren) {
            if (child.owner == this) {
                child.owner = null;
            }
            child.setDestroyed(true);
        }
        children.clear();
    }

    @Override
    protected void afterRewindRestoreSettled() {
        // children is the authoritative captured graph edge. Rebuild the deferred
        // inverse after all Obj6A instances have been recreated/restored so a later
        // child MarkObjGone2 delete detaches from the restored parent, never a stale
        // pre-seek instance (Obj6A delete tails, s2.asm:54278-54280,54303-54305).
        for (MCZRotPformsObjectInstance child : children) {
            child.owner = this;
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ensureInitialized();
        // ROM Obj6A_Init sets the Crate art tile and mapping_frame=0 for the
        // MCZ subtype-0x18 parent with no invisibility flag (s2.asm:54115-54124),
        // so the parent renders its wooden crate just like its children.

        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager == null) {
            return;
        }

        if (isMtz) {
            // MTZ Obj6A renders the shared Obj65_a level-art mappings at mapping_frame 1
            // (s2.asm:53688-53695: Obj65_Obj6A_Obj6B_MapUnc_26EC8, ArtKos_LevelArt line 3,
            // mapping_frame=1) against level art (base tile 0) on VDP palette line 3.
            List<SpriteMappingFrame> mappings = MAPPINGS.get(
                    Sonic2Constants.MAP_UNC_MTZ_PLATFORM_LEVELART_ADDR,
                    S2SpriteDataLoader::loadMappingFrames, "Obj65a");
            int frame = 1;
            if (mappings.isEmpty() || frame >= mappings.size()) {
                return;
            }
            SpriteMappingFrame mapFrame = mappings.get(frame);
            if (mapFrame == null || mapFrame.pieces().isEmpty()) {
                return;
            }
            GraphicsManager graphicsManager = services().graphicsManager();
            if (graphicsManager == null) {
                return;
            }
            SpritePieceRenderer.renderPieces(
                    mapFrame.pieces(),
                    x, y,
                    0,   // level art starts at tile 0
                    3,   // palette line 3
                    xFlip, yFlip,
                    (patternIndex, pieceHFlip, pieceVFlip, paletteIndex, px, py) -> {
                        int descIndex = patternIndex & 0x7FF;
                        if (pieceHFlip) {
                            descIndex |= 0x800;
                        }
                        if (pieceVFlip) {
                            descIndex |= 0x1000;
                        }
                        descIndex |= (paletteIndex & 0x3) << 13;
                        graphicsManager.renderPattern(new PatternDesc(descIndex), px, py);
                    });
            return;
        }

        // MCZ uses the wooden-crate Nemesis art (ArtNem_Crate).
        PatternSpriteRenderer renderer = renderManager.getRenderer(Sonic2ObjectArtKeys.MCZ_CRATE);
        if (renderer != null && renderer.isReady()) {
            renderer.drawFrameIndex(0, x, y, xFlip, yFlip);
        }
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        int left = x - HALF_WIDTH;
        int right = x + HALF_WIDTH;
        int top = y - yRadius;
        int bottom = y + yRadius + 1;

        float r = 0.2f, g = 0.8f, b = 0.2f;
        if (activated && isMtz) {
            r = 0.8f;
            g = 0.8f;
            b = 0.2f;
        }

        ctx.drawLine(left, top, right, top, r, g, b);
        ctx.drawLine(right, top, right, bottom, r, g, b);
        ctx.drawLine(right, bottom, left, bottom, r, g, b);
        ctx.drawLine(left, bottom, left, top, r, g, b);

        ctx.drawLine(x - 4, y, x + 4, y, 0.0f, 1.0f, 1.0f);
        ctx.drawLine(x, y - 4, x, y + 4, 0.0f, 1.0f, 1.0f);
    }
}
