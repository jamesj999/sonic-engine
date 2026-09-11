package com.openggf.game.sonic2.objects;

import com.openggf.debug.DebugRenderContext;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.MultiPieceSolidProvider;
import com.openggf.level.objects.ObjectLifetimeOps;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.PerObjectRewindSnapshot;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreateObjectLinks;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.ArrayList;
import java.util.List;

/**
 * Object 0x70 - Giant rotating cog from Metropolis Zone.
 * <p>
 * A large gear mechanism with 8 teeth arranged in a circle. The teeth rotate
 * around the center position, advancing one step every 16 frames. Each tooth
 * provides solid collision and renders using the MtzWheel art.
 * <p>
 * <b>Disassembly Reference:</b> s2.asm lines 54607-54783 (Obj70 code)
 * <p>
 * <b>Rotation direction:</b> Controlled by X-flip (render_flags bit 0):
 * <ul>
 *   <li>Normal (bit 0 clear): Clockwise rotation (phase advances by $18)</li>
 *   <li>X-flip (bit 0 set): Counter-clockwise rotation (phase decreases by $18)</li>
 * </ul>
 * <p>
 * <b>Structure:</b>
 * 8 teeth, each with 3-byte position entries (x_offset, y_offset, mapping_frame).
 * 4 rotation steps x 8 teeth = 32 position entries in Obj70_Positions table.
 * The rotation phase (objoff_36) cycles 0 -> $18 -> $30 -> $48 -> 0 (or reverse).
 * Each tooth has an objoff_34 offset (0,3,6,...,21) into the position table.
 * <p>
 * <b>Per-frame collision sizes from byte_28706:</b>
 * Most frames use 16x16, but frames 7/9 use 16x12 and frame 8 uses 16x8
 * (the tooth appears thinner when rotated to top/bottom position).
 */
public class CogObjectInstance extends AbstractObjectInstance
        implements MultiPieceSolidProvider, SolidObjectListener, RewindRecreatable {

    // Number of teeth on the cog
    private static final int NUM_TEETH = 8;

    // Phase advance per rotation step (every 16 frames)
    // From disassembly: addi.w #$18,d1 / subi.w #$18,d1
    private static final int PHASE_STEP = 0x18;

    // Maximum rotation phase before wrapping
    // From disassembly: cmpi.w #$60,d1 / blo.s (for CW); bcc.s (for CCW after sub)
    private static final int PHASE_MAX = 0x60;

    // Maximum position offset before wrapping
    // From disassembly: cmpi.w #$18,objoff_34(a0) / blo.s (for CW)
    private static final int OFFSET_MAX = 0x18;

    // Render priority from disassembly: move.b #4,priority(a1)
    private static final int PRIORITY = 4;

    // Obj70_Positions table (s2.asm lines 54741-54778)
    // 4 rotation steps x 8 teeth x 3 bytes (x_offset, y_offset, mapping_frame)
    // Values are signed bytes for x/y offsets.
    private static final byte[] POSITIONS = {
            // Step 0 (phase offset 0x00)
            0x00, (byte) 0xB8, 0x00,
            0x32, (byte) 0xCE, 0x04,
            0x48, 0x00, 0x08,
            0x32, 0x32, 0x0C,
            0x00, 0x48, 0x10,
            (byte) 0xCE, 0x32, 0x14,
            (byte) 0xB8, 0x00, 0x18,
            (byte) 0xCE, (byte) 0xCE, 0x1C,

            // Step 1 (phase offset 0x18)
            0x0D, (byte) 0xB8, 0x01,
            0x3F, (byte) 0xDA, 0x05,
            0x48, 0x0C, 0x09,
            0x27, 0x3C, 0x0D,
            (byte) 0xF3, 0x48, 0x11,
            (byte) 0xC1, 0x26, 0x15,
            (byte) 0xB8, (byte) 0xF4, 0x19,
            (byte) 0xD9, (byte) 0xC4, 0x1D,

            // Step 2 (phase offset 0x30)
            0x19, (byte) 0xBC, 0x02,
            0x46, (byte) 0xE9, 0x06,
            0x46, 0x17, 0x0A,
            0x19, 0x44, 0x0E,
            (byte) 0xE7, 0x44, 0x12,
            (byte) 0xBA, 0x17, 0x16,
            (byte) 0xBA, (byte) 0xE9, 0x1A,
            (byte) 0xE7, (byte) 0xBC, 0x1E,

            // Step 3 (phase offset 0x48)
            0x27, (byte) 0xC4, 0x03,
            0x48, (byte) 0xF4, 0x07,
            0x3F, 0x26, 0x0B,
            0x0D, 0x48, 0x0F,
            (byte) 0xD9, 0x3C, 0x13,
            (byte) 0xB8, 0x0C, 0x17,
            (byte) 0xC1, (byte) 0xDA, 0x1B,
            (byte) 0xF3, (byte) 0xB8, 0x1F,
    };

    // byte_28706: per-frame collision sizes {halfWidth, yRadius}
    // Indexed by mapping_frame (0-15 visible, mirrored in high frames)
    // From s2.asm lines 54723-54739
    private static final int[][] COLLISION_SIZES = {
            {0x10, 0x10},  // frame 0
            {0x10, 0x10},  // frame 1
            {0x10, 0x10},  // frame 2
            {0x10, 0x10},  // frame 3
            {0x10, 0x10},  // frame 4
            {0x10, 0x10},  // frame 5
            {0x10, 0x10},  // frame 6
            {0x10, 0x0C},  // frame 7
            {0x10, 0x08},  // frame 8
            {0x10, 0x0C},  // frame 9
            {0x10, 0x10},  // frame 10
            {0x10, 0x10},  // frame 11
            {0x10, 0x10},  // frame 12
            {0x10, 0x10},  // frame 13
            {0x10, 0x10},  // frame 14
            {0x10, 0x10},  // frame 15
    };

    // Instance state
    private int baseX;      // objoff_32 - center X position
    private int baseY;      // objoff_30 - center Y position
    private boolean ccw;    // Counter-clockwise rotation (status.npc.x_flip)

    // Rotation state
    private int rotationPhase;    // objoff_36 - current rotation phase (0, $18, $30, $48)

    // Per-tooth state (computed each frame)
    private final int[] toothX = new int[NUM_TEETH];
    private final int[] toothY = new int[NUM_TEETH];
    private final int[] toothFrame = new int[NUM_TEETH];
    // Per-tooth position offset into POSITIONS table (objoff_34 per child)
    private final int[] toothOffset = new int[NUM_TEETH];
    private final List<CogSlotChildInstance> slotChildren = new ArrayList<>();
    private boolean childrenSpawned;

    public CogObjectInstance(ObjectSpawn spawn, String name) {
        super(spawn, name);
        this.baseX = spawn.x();
        this.baseY = spawn.y();

        // X-flip controls rotation direction (status.npc.x_flip = render_flags bit 0)
        this.ccw = (spawn.renderFlags() & 0x01) != 0;

        // Initialize per-tooth position offsets (objoff_34)
        // From disassembly: d4 starts at 0, increments by 3 per tooth
        for (int i = 0; i < NUM_TEETH; i++) {
            toothOffset[i] = i * 3;
        }

        // Initial rotation phase is 0
        this.rotationPhase = 0;
        this.childrenSpawned = false;

        // Calculate initial positions
        updateToothPositions();
    }

    @Override
    public CogObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new CogObjectInstance(ctx.spawn(), getName());
    }

    @Override
    public int getX() {
        return baseX;
    }

    @Override
    public int getY() {
        return baseY;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (isDestroyed()) {
            return;
        }

        ensureSlotChildrenSpawned();

        // ROM Obj70_Main (s2.asm:54662-54665): move.b (Level_frame_counter+1).w,d0;
        // andi.w #$F,d0; bne loc_286CA. On 68k, +1 reads the low byte of the word
        // label, not an increment of the value, and LevelManager now advances its
        // counter at the loop top as the ROM does, so this reads the ROM's own
        // value with no adjustment.
        int levelFrameCounter = services().levelManager().getFrameCounter();
        if ((levelFrameCounter & 0x0F) == 0) {
            advanceRotation();
        }

        updateToothPositions();
    }

    private void ensureSlotChildrenSpawned() {
        if (childrenSpawned) {
            return;
        }

        // ROM Obj70_Init uses the current SST slot for tooth 0, then calls
        // AllocateObjectAfterCurrent seven times and copies Obj70 into each
        // child slot (docs/s2disasm/s2.asm:55039-55078). The parent keeps the
        // engine's unified multi-piece solid/render model; these children
        // preserve the ROM's dynamic slot pressure.
        for (int i = 1; i < NUM_TEETH; i++) {
            int toothIndex = i;
            ObjectSpawn childSpawn = buildCogChildSpawn(toothX[toothIndex], toothY[toothIndex]);
            CogSlotChildInstance child = spawnChild(() -> new CogSlotChildInstance(childSpawn, this));
            slotChildren.add(child);
        }
        childrenSpawned = true;
    }

    private void attachSlotChildForRewind(CogSlotChildInstance child) {
        if (!slotChildren.contains(child)) {
            slotChildren.add(child);
        }
    }


    private ObjectSpawn buildCogChildSpawn(int x, int y) {
        return new ObjectSpawn(
                x,
                y,
                spawn.objectId(),
                spawn.subtype(),
                spawn.renderFlags(),
                spawn.respawnTracked(),
                spawn.rawYWord());
    }

    /**
     * Advances the rotation phase and wraps the position offsets.
     * <p>
     * CW (normal): phase advances by $18, wraps at $60 -> 0.
     * When phase wraps, each tooth's offset advances by 3.
     * <p>
     * CCW (x_flip): phase decreases by $18, wraps below 0 -> $48.
     * When phase wraps, each tooth's offset decreases by 3.
     * <p>
     * Disassembly: s2.asm lines 54666-54686
     */
    private void advanceRotation() {
        if (ccw) {
            // Counter-clockwise: subi.w #$18,d1
            rotationPhase -= PHASE_STEP;
            if (rotationPhase < 0) {
                rotationPhase = 0x48;
                // Advance each tooth's offset backward: subq.w #3,objoff_34
                for (int i = 0; i < NUM_TEETH; i++) {
                    toothOffset[i] -= 3;
                    if (toothOffset[i] < 0) {
                        // Wrap: move.w #$15,objoff_34 (= 21 = 7*3)
                        toothOffset[i] = 0x15;
                    }
                }
            }
        } else {
            // Clockwise: addi.w #$18,d1
            rotationPhase += PHASE_STEP;
            if (rotationPhase >= PHASE_MAX) {
                rotationPhase = 0;
                // Advance each tooth's offset forward: addq.w #3,objoff_34
                for (int i = 0; i < NUM_TEETH; i++) {
                    toothOffset[i] += 3;
                    if (toothOffset[i] >= OFFSET_MAX) {
                        // Wrap: move.w #0,objoff_34
                        toothOffset[i] = 0;
                    }
                }
            }
        }
    }

    /**
     * Updates all tooth positions from the POSITIONS table.
     * <p>
     * Each tooth's table index = rotationPhase + toothOffset[i].
     * From disassembly: add.w objoff_34(a0),d1 / lea Obj70_Positions(pc,d1.w),a1
     */
    private void updateToothPositions() {
        for (int i = 0; i < NUM_TEETH; i++) {
            int tableIndex = rotationPhase + toothOffset[i];
            int xOff = POSITIONS[tableIndex];      // signed byte
            int yOff = POSITIONS[tableIndex + 1];   // signed byte
            int frame = POSITIONS[tableIndex + 2] & 0xFF;

            toothX[i] = baseX + xOff;
            toothY[i] = baseY + yOff;
            toothFrame[i] = frame;
        }
    }

    @Override
    public PerObjectRewindSnapshot captureRewindState() {
        return super.captureRewindState().withObjectSubclassExtra(
                new CogRewindExtra(baseX, baseY, ccw, rotationPhase, toothOffset.clone(), childrenSpawned));
    }

    @Override
    public void restoreRewindState(PerObjectRewindSnapshot snapshot) {
        super.restoreRewindState(snapshot);
        if (snapshot.objectSubclassExtra() instanceof CogRewindExtra extra) {
            baseX = extra.baseX();
            baseY = extra.baseY();
            ccw = extra.ccw();
            rotationPhase = extra.rotationPhase();
            int[] restoredOffsets = extra.toothOffset();
            System.arraycopy(restoredOffsets, 0, toothOffset, 0, Math.min(restoredOffsets.length, toothOffset.length));
            childrenSpawned = extra.childrenSpawned();
            updateToothPositions();
        }
    }

    /**
     * Returns collision parameters for a specific tooth based on its mapping frame.
     * From byte_28706 table (s2.asm line 54723).
     */
    private SolidObjectParams getParamsForFrame(int mappingFrame) {
        // ROM: mapping_frame * 2, andi.w #$1E - index into 16-entry table
        int index = mappingFrame & 0x0F;
        int halfWidth = COLLISION_SIZES[index][0];
        int yRadius = COLLISION_SIZES[index][1];
        return SolidObjectParams.of(halfWidth, yRadius, yRadius);
    }

    // MultiPieceSolidProvider implementation

    @Override
    public int getPieceCount() {
        return NUM_TEETH;
    }

    @Override
    public int getPieceX(int pieceIndex) {
        return toothX[pieceIndex];
    }

    @Override
    public int getPieceY(int pieceIndex) {
        return toothY[pieceIndex];
    }

    @Override
    public SolidObjectParams getPieceParams(int pieceIndex) {
        return getParamsForFrame(toothFrame[pieceIndex]);
    }

    @Override
    public boolean resolvesEarlierPiecesBeforeRidingPiece() {
        // ROM Obj70 creates one SST slot per tooth, then each slot calls
        // SolidObject in allocation order (s2.asm:54617-54651, 54691-54703).
        // Earlier teeth can side-push Sonic before the ridden tooth's
        // standing-bit branch performs its ExitPlatform bounds check.
        return true;
    }

    @Override
    public boolean usesPieceScopedStandingBits() {
        // Each ROM tooth is a separate SST slot with its own status(a0)
        // standing bits (s2.asm:55039-55078). Keep the engine latch per
        // tooth so an airborne stale-rider branch clears only that tooth and
        // skips SolidObject_cont instead of falling into a sibling side hit.
        return true;
    }

    @Override
    public SolidObjectParams getSolidParams() {
        // Default params (used if getPieceParams not called)
        return SolidObjectParams.of(0x10, 0x10, 0x10);
    }

    @Override
    public boolean isTopSolidOnly() {
        // Obj70 uses SolidObject (JmpTo16_SolidObject), fully solid from all sides
        return false;
    }

    @Override
    public boolean usesInclusiveRightEdge() {
        // ROM SolidObject_cont accepts relX == width*2; only relX > width*2
        // branches out via `bhi.w SolidObject_TestClearPush` (s2.asm:35150-35158).
        return true;
    }

    @Override
    public boolean usesCollisionHalfWidthForTopLanding() {
        // Obj70 passes byte_28706's d1=$10 into SolidObject, and Obj70_Init also
        // writes width_pixels=$10 (docs/s2disasm/s2.asm:55111, 55189-55209).
        // SolidObject_Landed re-checks width_pixels(a0) directly
        // (s2.asm:35588-35620), so the landing window is the same $10 as the
        // collision half-width, not the usual full-solid d1-$0B narrowing.
        return true;
    }

    @Override
    public boolean isSolidFor(PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        return !isDestroyed();
    }

    @Override
    public boolean airborneStaleStandingBitReturnsNoContact(PlayableEntity player) {
        // ROM Obj70 collides via the standard SolidObject helper
        // (JmpTo16_SolidObject, s2.asm:55132). SolidObject's standing branch
        // (s2.asm:35021-35044) runs first: when the ridden tooth's standing bit
        // d6 is set on the player AND Status_InAir is set, it branches to
        // loc_1975A (s2.asm:35035-35040) which clears Status_OnObj/d6, sets
        // Status_InAir and returns d4=0 WITHOUT reaching SolidObject_cont — so
        // the platform carry (MvSonicOnPtfm, s2.asm:35635-35659) and the
        // SolidObject_AtEdge side push (s2.asm:35432-35444) are both skipped on
        // the frame the rider jumps off. Without this, the engine treats the
        // just-jumped airborne rider as a fresh side contact against the rotated
        // tooth and shoves him one frame early (MTZ3 trace f2047 tails_x:
        // engine 0x07CA vs ROM 0x07BD, where ROM applies the displacement only
        // at f2048).
        return true;
    }

    @Override
    public boolean sideContactReturnsNoContact(PlayableEntity player) {
        // Obj70's eight ROM teeth are separate SST slots. When a sidekick is
        // airborne while a tooth's standing bit is still set, SolidObject clears
        // that bit and returns d4=0 before SolidObject_cont can apply the
        // airborne side correction (s2.asm:35021-35040, 55039-55141). The
        // engine compresses the teeth into one multi-piece solid, so suppress
        // this Obj70-only fresh side correction to preserve the ROM stale-rider
        // handoff instead of zeroing Tails' x velocity one frame early. The
        // solid controller gates this opt-in on Obj70's standing-bit latch so
        // ordinary grounded side contacts still reach SolidObject_StopCharacter
        // (s2.asm:35413-35429).
        return player.isCpuControlled();
    }

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        // No special handling needed
    }

    @Override
    public void onUnload() {
        for (CogSlotChildInstance child : slotChildren) {
            ObjectLifetimeOps.expireDynamic(child);
        }
        slotChildren.clear();
    }

    // Rendering

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(Sonic2ObjectArtKeys.MTZ_WHEEL);
        if (renderer == null) return;

        // Verified against obj70.asm (Obj70_MapUnc_28786): all 32 mapping entries point
        // to the same Map_obj70_0040, a single 32x32 spritePiece (tile 0) with hFlip=0,
        // vFlip=0. There are no per-piece flip bits to honor, so drawFrameIndex(...,false,
        // false) is correct - per-tooth orientation comes entirely from the position table,
        // not art flips. (Central ObjectManager despawn covers off-screen removal; Obj70
        // needs no per-object coarse-camera DeleteObject - see s2.asm:54717-54725.)
        for (int i = 0; i < NUM_TEETH; i++) {
            renderer.drawFrameIndex(toothFrame[i], toothX[i], toothY[i], false, false);
        }
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(PRIORITY);
    }

    @Override
    public boolean suppressesObjectEdgeBalance() {
        // Obj70_Init sets status.npc.no_balancing before copying that status to every
        // tooth, so a player standing at a tooth edge keeps the normal idle animation.
        // See docs/s2disasm/s2.asm Obj70_Init.
        return true;
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        // Draw collision bounds for each tooth
        for (int i = 0; i < NUM_TEETH; i++) {
            SolidObjectParams params = getParamsForFrame(toothFrame[i]);
            int left = toothX[i] - params.halfWidth();
            int right = toothX[i] + params.halfWidth();
            int top = toothY[i] - params.airHalfHeight();
            int bottom = toothY[i] + params.groundHalfHeight();

            ctx.drawLine(left, top, right, top, 0.0f, 1.0f, 0.0f);
            ctx.drawLine(right, top, right, bottom, 0.3f, 0.7f, 0.3f);
            ctx.drawLine(right, bottom, left, bottom, 0.3f, 0.7f, 0.3f);
            ctx.drawLine(left, bottom, left, top, 0.3f, 0.7f, 0.3f);
        }

        // Center cross (yellow)
        ctx.drawLine(baseX - 4, baseY, baseX + 4, baseY, 1.0f, 1.0f, 0.0f);
        ctx.drawLine(baseX, baseY - 4, baseX, baseY + 4, 1.0f, 1.0f, 0.0f);
    }

    private record CogRewindExtra(
            int baseX,
            int baseY,
            boolean ccw,
            int rotationPhase,
            int[] toothOffset,
            boolean childrenSpawned
    ) implements PerObjectRewindSnapshot.ObjectSubclassRewindExtra {
    }

    private static final class CogSlotChildInstance extends AbstractObjectInstance implements RewindRecreatable {

        // This child's captured spawn is frozen at whichever tooth position it was
        // spawned at (buildCogChildSpawn(toothX, toothY), never refreshed -- update()
        // only checks the parent's destroyed state, it never repositions itself). The
        // cog's own center (baseX/baseY) never moves, and every POSITIONS table entry
        // (the tooth offsets from center) has a signed-byte magnitude of at most 0x48
        // (72) on each axis, so the max possible radial distance from a tooth position
        // to the true cog's center is sqrt(72^2+72^2) =~ 102px. Round up for headroom.
        private static final int MAX_PARENT_RELINK_DISTANCE = 128;

        private final CogObjectInstance parent;

        CogSlotChildInstance(ObjectSpawn spawn, CogObjectInstance parent) {
            super(spawn, "CogSlot");
            this.parent = parent;
        }

        @Override
        public CogSlotChildInstance recreateForRewind(RewindRecreateContext ctx) {
            // Slot-pressure child of a Cog. If the parent Cog was swept before capture
            // there is nothing to relink to, so drop the child (its live update
            // self-expires with a dead parent) rather than throw. acceptDestroyed
            // relinks to a restored-but-destroyed Cog when that is the captured parent.
            // Bounded (see MAX_PARENT_RELINK_DISTANCE) since a live tooth position can
            // only ever be within the fixed POSITIONS table's radius of its true cog.
            return RewindRecreateObjectLinks.nearestObject(
                            ctx, CogObjectInstance.class, true, MAX_PARENT_RELINK_DISTANCE)
                    .map(parent -> {
                        CogSlotChildInstance child = new CogSlotChildInstance(ctx.spawn(), parent);
                        parent.attachSlotChildForRewind(child);
                        return child;
                    })
                    .orElse(null);
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity playerEntity) {
            if (parent.isDestroyed()) {
                ObjectLifetimeOps.expireDynamic(this);
            }
        }

        @Override
        public boolean usesCustomOutOfRangeCheck() {
            return true;
        }

        @Override
        public boolean isCustomOutOfRange(int cameraX) {
            return parent.isDestroyed();
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            // Slot-pressure child only; the parent renders all cog teeth.
        }
    }
}
