package com.openggf.game.sonic1.objects;
import com.openggf.game.PlayableEntity;

import com.openggf.debug.DebugRenderContext;
import com.openggf.game.sonic1.audio.Sonic1Sfx;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectLifetimeOps;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.SubpixelMotion;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import com.openggf.debug.DebugColor;
import java.util.List;

/**
 * Object 0x4D - Lava Geyser / Lavafall (MZ).
 * <p>
 * Spawned by GeyserMaker (0x4C). Has multiple roles based on its routine:
 * <ul>
 *   <li><b>Head piece (routine 2):</b> Rises/falls with gravity, animated bubbles</li>
 *   <li><b>Body piece (routine 4):</b> Follows head Y+0x60, selects column frame by height,
 *       has collision (obColType=0x93)</li>
 *   <li><b>Third piece (lavafall only):</b> Behind-priority body at Y+0x100</li>
 * </ul>
 * <p>
 * <b>Geyser (subtype 0):</b> Head rises at VelY=-0x500 (Geyser_Speeds[0]=$FB00),
 * body follows at Y+0x60. Gravity +0x18/frame.
 * <p>
 * <b>Lavafall (subtype 1):</b> Head starts at Y-0x250, VelY=0, falls under gravity.
 * Two body children: one at Y+0x60, one at Y+0x100.
 * <p>
 * <b>Animation:</b>
 * <ul>
 *   <li>Anim 2 (.end): speed 2, frames {6, 7}, afEnd</li>
 *   <li>Anim 5 (.bubble4): speed 2, frames {0x11, 0x12}, afEnd</li>
 * </ul>
 * Body pieces use manual frame selection (not AnimateSprite).
 * <p>
 * Reference: docs/s1disasm/_incObj/4C &amp; 4D Lava Geyser Maker.asm
 */
public class Sonic1LavaGeyserObjectInstance extends AbstractObjectInstance
        implements TouchResponseProvider, RewindRecreatable {

    // ========================================================================
    // Constants from disassembly
    // ========================================================================

    /** Gravity per frame: addi.w #$18,obVelY(a0). */
    private static final int GRAVITY = 0x18;

    /**
     * Geyser_Speeds table: dc.w $FB00, 0.
     * Index 0 = geyser (VelY = -0x500), Index 1 = lavafall (VelY = 0).
     * Note: $FB00 as signed 16-bit = -0x500.
     */
    private static final int[] GEYSER_SPEEDS = {-0x500, 0};

    /** obColType = $93: HURT category ($80) | size index $13. */
    private static final int COLLISION_FLAGS = 0x93;

    /** Body collision height: move.b #$80,obHeight(a1). */
    private static final int BODY_HEIGHT = 0x80;

    /** Body Y offset from head: addi.w #$60,obY(a1). */
    private static final int BODY_Y_OFFSET = 0x60;

    /** Third piece Y offset (lavafall): addi.w #$100,obY(a1). */
    private static final int THIRD_PIECE_Y_OFFSET = 0x100;

    /** Lavafall start Y offset: subi.w #$250,obY(a0). */
    private static final int LAVAFALL_START_Y_OFFSET = 0x250;

    /** Frame cycling timer: move.b #7,obTimeFrame(a0). */
    private static final int COLUMN_ANIM_PERIOD = 8;

    /** Animation speed for head anims (from Ani_Geyser: dc.b 2). */
    private static final int ANIM_SPEED = 3; // speed byte 2 -> every 3 frames

    // Animation frames for head pieces
    /** Anim 5 (.bubble4): frames {0x11, 0x12} - geyser head bubbles. */
    private static final int[] ANIM_BUBBLE4_FRAMES = {0x11, 0x12};

    /** Anim 2 (.end): frames {6, 7} - end/splash. */
    private static final int[] ANIM_END_FRAMES = {6, 7};

    /** Debug color (orange-red for lava). */
    private static final DebugColor DEBUG_COLOR = new DebugColor(255, 80, 0);

    // ========================================================================
    // Role enum
    // ========================================================================

    /** Role of this geyser piece in the hierarchy. */
    enum Role {
        /** Rising/falling head piece (routine 2 = Geyser_Action). */
        HEAD,
        /** Column body piece (routine 4 = loc_EFFC), follows head. */
        BODY
    }

    // ========================================================================
    // Instance State
    // ========================================================================

    private Role role;
    /** Mutable subtype: cleared from 1→0 on head after creating lavafall third piece. */
    private int subtype;

    /** Current position. */
    private int currentX;
    private int currentY;

    /** Y velocity (subpixels, signed 16-bit). */
    private int velY;

    /** Subpixel accumulators (xSub / ySub) for ROM-accurate 16.16 SpeedToPos integration. */
    private final SubpixelMotion.State motion = new SubpixelMotion.State(0, 0, 0, 0, 0, 0);

    /** Origin Y (objoff_30): used for deletion check and body column height. */
    private int originY;

    /** Parent reference: for HEAD, this is the GeyserMaker; for BODY, this is the HEAD piece. */
    private Sonic1LavaGeyserObjectInstance parentGeyser;

    /** The GeyserMaker that spawned us (for signaling anim change). */
    private Sonic1LavaGeyserMakerObjectInstance makerParent;

    /**
     * Head animation ID (5=bubble4 for geyser, 2=end for lavafall).
     * Stored separately from subtype because the head's subtype is cleared to 0
     * after creating the lavafall third piece, but the animation should not change.
     */
    private int headAnimId;

    /** Animation frame index (for head pieces). */
    private int animFrameIndex;

    /** Animation timer. */
    private int animTimer;

    /** Column animation frame offset (0 or 1, for body pieces). */
    private int columnAnimFrame;

    /** Column animation timer (body pieces). */
    private int columnAnimTimer;

    /** Current display frame index. */
    private int displayFrame;

    /** Whether this piece has been signaled to delete (routine 6). */
    private boolean pendingDelete;

    /** Whether this is the behind-priority third piece (lavafall). */
    private boolean behindPriority;

    // ========================================================================
    // Constructors
    // ========================================================================

    /**
     * Creates a HEAD piece (the main geyser/lavafall that rises/falls).
     * Called from GeyserMaker when spawning.
     */
    public Sonic1LavaGeyserObjectInstance(ObjectSpawn spawn) {
        this(spawn, Role.HEAD, null, null, false);
    }

    /**
     * Internal constructor for all roles.
     */
    Sonic1LavaGeyserObjectInstance(ObjectSpawn spawn, Role role,
                                    Sonic1LavaGeyserObjectInstance parentHead,
                                    Sonic1LavaGeyserMakerObjectInstance maker,
                                    boolean behindPriority) {
        super(spawn, "LavaGeyser");
        this.role = role;
        this.subtype = spawn.subtype() & 0xFF;
        this.currentX = spawn.x();
        this.currentY = spawn.y();
        this.parentGeyser = parentHead;
        this.makerParent = maker;
        this.behindPriority = behindPriority;

        // ROM Geyser_Main, routine 0, "4C, 4D MZ Lava Geyser and Maker.asm":157-172
        // captures geyser_origY BEFORE applying the lavafall start-height offset,
        // then falls straight through into Geyser_Action's own SpeedToPos +
        // DisplaySprite in the SAME pass that GMake_MakeLava's FindNextFreeObj
        // creates this object -- ROM never displays a lavafall head at its
        // un-shifted, eventual landing, Y. Apply that shift here, eagerly, in
        // the constructor (pure position arithmetic; no service lookups needed),
        // rather than relying on this newly spawned dynamic object's own first
        // per-frame refresh landing in the same frame as its parent's spawn call.
        // When the object manager's FindNextFreeObj-equivalent can't place the
        // child at a slot the current exec pass hasn't reached yet, dynamic
        // pool pressure, that first per-frame refresh is deferred to the next
        // frame, and the head would otherwise render one frame at the maker's
        // own Y -- the "single-frame flicker where a vertical lavafall will
        // land when it starts" bug. The lavafall THIRD piece, behindPriority
        // true, is constructed with an already-final Y from its sibling head
        // and must not be shifted again.
        if (role == Role.HEAD && !behindPriority && this.subtype != 0) {
            this.originY = spawn.y();
            this.currentY -= LAVAFALL_START_Y_OFFSET;
        }
    }

    @Override
    public Sonic1LavaGeyserObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        ObjectSpawn capturedSpawn = ctx != null && ctx.spawn() != null ? ctx.spawn() : getSpawn();
        Sonic1LavaGeyserMakerObjectInstance maker = restoredMaker(ctx, capturedSpawn);
        Sonic1LavaGeyserObjectInstance parentHead = restoredParentHeadForBody(ctx, capturedSpawn);
        boolean body = parentHead != null;
        boolean thirdPiece = !body && isRestoredLavafallThirdPiece(ctx, capturedSpawn, maker);
        return new Sonic1LavaGeyserObjectInstance(
                capturedSpawn,
                body ? Role.BODY : Role.HEAD,
                parentHead,
                maker,
                thirdPiece);
    }

    private static Sonic1LavaGeyserMakerObjectInstance restoredMaker(
            RewindRecreateContext ctx,
            ObjectSpawn capturedSpawn) {
        ObjectManager objectManager = restoredObjectManager(ctx);
        if (objectManager == null || capturedSpawn == null) {
            return null;
        }
        Sonic1LavaGeyserMakerObjectInstance best = null;
        long bestDistance = Long.MAX_VALUE;
        for (ObjectInstance object : objectManager.getActiveObjects()) {
            if (object instanceof Sonic1LavaGeyserMakerObjectInstance maker && !maker.isDestroyed()) {
                ObjectSpawn makerSpawn = maker.getSpawn();
                long dx = makerSpawn.x() - capturedSpawn.x();
                long dy = makerSpawn.y() - capturedSpawn.y();
                long distance = dx * dx + dy * dy;
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = maker;
                }
            }
        }
        return best;
    }

    private static Sonic1LavaGeyserObjectInstance restoredParentHeadForBody(
            RewindRecreateContext ctx,
            ObjectSpawn capturedSpawn) {
        ObjectManager objectManager = restoredObjectManager(ctx);
        if (objectManager == null || capturedSpawn == null) {
            return null;
        }
        for (ObjectInstance object : objectManager.getActiveObjects()) {
            if (object instanceof Sonic1LavaGeyserObjectInstance geyser
                    && !geyser.isDestroyed()
                    && geyser.role == Role.HEAD
                    && !geyser.behindPriority
                    && geyser.currentX == capturedSpawn.x()
                    && geyser.currentY + BODY_Y_OFFSET == capturedSpawn.y()) {
                return geyser;
            }
        }
        return null;
    }

    private static boolean isRestoredLavafallThirdPiece(
            RewindRecreateContext ctx,
            ObjectSpawn capturedSpawn,
            Sonic1LavaGeyserMakerObjectInstance maker) {
        ObjectManager objectManager = restoredObjectManager(ctx);
        if (objectManager == null || capturedSpawn == null || (capturedSpawn.subtype() & 0xFF) == 0) {
            return false;
        }
        for (ObjectInstance object : objectManager.getActiveObjects()) {
            if (object instanceof Sonic1LavaGeyserObjectInstance geyser
                    && !geyser.isDestroyed()
                    && geyser.role == Role.BODY
                    && geyser.currentX == capturedSpawn.x()
                    && (maker == null || geyser.makerParent == maker)) {
                return true;
            }
        }
        return false;
    }

    private static ObjectManager restoredObjectManager(RewindRecreateContext ctx) {
        if (ctx == null) {
            return null;
        }
        ObjectManager objectManager = ctx.objectManager();
        if (objectManager != null) {
            return objectManager;
        }
        ObjectServices services = ctx.objectServices();
        return services != null ? services.objectManager() : null;
    }

    private boolean initialized;

    /**
     * Initializes this geyser piece. Deferred to first update() so that
     * ObjectServices are available (injected by addDynamicObject).
     */
    private void ensureInitialized() {
        if (initialized) return;
        initialized = true;
        if (role == Role.HEAD) {
            initializeHead();
        }
    }

    private void initializeHead() {
        // Geyser_Main: Routine 0
        // move.w obY(a0),objoff_30(a0)
        // For the lavafall case (subtype != 0) the constructor already captured
        // originY and applied the "subi.w #$250,obY(a0)" start-height shift
        // eagerly (see the constructor's comment) so the position is correct
        // from the very first render, not just from the first update(). Only
        // the geyser case (subtype == 0, no shift) still needs it captured here.
        if (subtype == 0) {
            this.originY = currentY;
        }

        // Store animation ID based on current subtype BEFORE any clearing.
        // .makelava: move.b #5,obAnim(a1) / tst.b obSubtype / beq.s .fail / move.b #2,obAnim(a1)
        this.headAnimId = (subtype != 0) ? 2 : 5;

        // moveq #0,d0 / move.b obSubtype(a0),d0 / add.w d0,d0
        // move.w Geyser_Speeds(pc,d0.w),obVelY(a0)
        int speedIdx = Math.min(subtype, GEYSER_SPEEDS.length - 1);
        this.velY = GEYSER_SPEEDS[speedIdx];

        this.animFrameIndex = 0;
        this.animTimer = 0;

        // .activate: create body child at Y+0x60
        if (services().objectManager() != null) {
            // Create body piece (routine 4 = loc_EFFC)
            final int prevSlotInit = getSlotIndex();
            final int[] prevSlotHolder = { prevSlotInit };
            spawnFreeChild(() -> {
                ObjectSpawn bodySpawn = new ObjectSpawn(
                        currentX, currentY + BODY_Y_OFFSET,
                        0x4D, subtype, 0, false, 0);
                Sonic1LavaGeyserObjectInstance b = new Sonic1LavaGeyserObjectInstance(
                        bodySpawn, Role.BODY, this, makerParent, false);
                b.originY = this.originY + BODY_Y_OFFSET;
                b.columnAnimTimer = 7; // start with timer at 7 for immediate frame select
                b.columnAnimFrame = 0;
                // ROM: FindNextFreeObj allocates slot after head
                int childSlot = ObjectLifetimeOps.assignFindNextFreeChildSlot(
                        services().objectManager(), b, prevSlotHolder[0]);
                if (childSlot >= 0) {
                    prevSlotHolder[0] = childSlot;
                }
                return b;
            });

            // Lavafall: create third piece as independent HEAD at Y+0x100
            // ROM: moveq #0,d1 / bsr.w .loop (creates one piece via .makelava)
            // Then configures it: routine 2, tile offset +16, priority 0, parent = maker
            if (subtype != 0) {
                spawnFreeChild(() -> {
                    ObjectSpawn thirdSpawn = new ObjectSpawn(
                            currentX, currentY + THIRD_PIECE_Y_OFFSET,
                            0x4D, 1, 0, false, 0);
                    // Third piece is an independent HEAD (routine 2 = Geyser_Action)
                    // with subtype 1 → uses Type01 → signals maker anim 1 when past origin
                    Sonic1LavaGeyserObjectInstance third = new Sonic1LavaGeyserObjectInstance(
                            thirdSpawn, Role.HEAD, null, makerParent, true);
                    third.originY = this.originY; // move.w objoff_30(a0),objoff_30(a1)
                    third.headAnimId = 2; // .end animation (set by .makelava since subtype=1)
                    third.velY = 0; // starts stationary, falls under gravity
                    // ROM: addq.b #2,obRoutine(a1) — third piece starts at routine 2
                    // (Geyser_Action), skipping Geyser_Main. Mark as initialized to
                    // prevent ensureInitialized() from re-running initializeHead(),
                    // which would cascade-spawn infinite children.
                    third.initialized = true;
                    // ROM: FindNextFreeObj allocates slot after body
                    ObjectLifetimeOps.assignFindNextFreeChildSlot(
                            services().objectManager(), third, prevSlotHolder[0]);
                    return third;
                });

                // move.b #0,obSubtype(a0) — clear head's subtype to 0
                // Head now uses Type00 (signals maker anim 3/afRoutine when done)
                this.subtype = 0;
            }
        }

        // .sound: move.w #sfx_Burning,d0 / jsr (QueueSound2).l
        services().playSfx(Sonic1Sfx.BURNING.id);
    }

    // ========================================================================
    // Update Logic
    // ========================================================================

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        ensureInitialized();
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (pendingDelete) {
            setDestroyed(true);
            return;
        }

        // ROM Geyser_Main (routine 0, "4C, 4D MZ Lava Geyser and Maker.asm":157)
        // does NOT rts: it falls through .configureLavaObjects (172/206) ->
        // .sound (230) -> Geyser_Action (235), so the head applies its first
        // gravity (addi.w #$18,obVelY) + SpeedToPos on its SPAWN frame. The
        // engine therefore runs updateHead() immediately after ensureInitialized().
        switch (role) {
            case HEAD -> updateHead();
            case BODY -> updateBody();
        }
    }

    /**
     * Geyser_Action (Routine 2): Apply gravity, move, check if past origin,
     * signal maker when done.
     */
    private void updateHead() {
        // Type-specific logic
        if (subtype == 0) {
            updateType00();
        } else {
            updateType01();
        }

        // bsr.w SpeedToPos — ROM-accurate 16.16 fixed-point arithmetic (Y-only)
        motion.y = currentY;
        motion.yVel = velY;
        SubpixelMotion.speedToPosY(motion);
        currentY = motion.y;

        // AnimateSprite (head animation)
        updateHeadAnimation();

        // Geyser_ChkDel: out_of_range.w DeleteObject
        if (!isInRangeAt(currentX)) {
            setDestroyed(true);
        }
    }

    /**
     * Geyser_Type00: Geyser head rises then falls.
     * addi.w #$18,obVelY(a0)
     * move.w objoff_30(a0),d0 / cmp.w obY(a0),d0 / bhs.s locret
     * When head falls past origin: advance routine to 6 (delete), signal maker anim 3.
     */
    private void updateType00() {
        velY += GRAVITY;
        // cmp.w d1,d0 -> bhs = branch if d0 >= currentY (unsigned)
        // Delete when head falls past origin
        if (currentY > originY && velY > 0) {
            // addq.b #4,obRoutine(a0) -> routine 6 = delete
            // movea.l objoff_3C(a0),a1 / move.b #3,obAnim(a1)
            signalMakerAnim(3);
            pendingDelete = true;
        }
    }

    /**
     * Geyser_Type01: Lavafall head falls from ceiling.
     * Same gravity, signals maker anim 1 when past origin.
     */
    private void updateType01() {
        velY += GRAVITY;
        if (currentY > originY && velY > 0) {
            signalMakerAnim(1);
            pendingDelete = true;
        }
    }

    private void signalMakerAnim(int anim) {
        if (makerParent != null) {
            makerParent.setCurrentAnim(anim);
        }
    }

    /**
     * loc_EFFC (Routine 4): Body follows head, selects column frame by height.
     */
    private void updateBody() {
        // Check if head/parent is destroyed
        if (parentGeyser != null && (parentGeyser.isDestroyed() || parentGeyser.pendingDelete)) {
            setDestroyed(true);
            return;
        }

        if (parentGeyser != null) {
            // move.w obY(a1),d0 / addi.w #$60,d0 / move.w d0,obY(a0)
            currentY = parentGeyser.currentY + BODY_Y_OFFSET;
        }

        // Calculate distance for column frame selection
        // sub.w objoff_30(a0),d0 / neg.w d0
        int d0 = currentY - originY;
        int distance = -d0; // neg.w

        // Frame base selection based on distance
        int frameBase;
        // moveq #8,d1 (medium column)
        frameBase = 8;
        // cmpi.w #$40,d0 / bge.s loc_F026
        if (distance < 0x40) {
            // moveq #$B,d1 (short column)
            frameBase = 0x0B;
        }
        // cmpi.w #$80,d0 / ble.s loc_F02E
        if (distance > 0x80) {
            // moveq #$E,d1 (long column)
            frameBase = 0x0E;
        }

        // Animation cycling: subq.b #1,obTimeFrame / bpl.s loc_F04C
        columnAnimTimer--;
        if (columnAnimTimer < 0) {
            // move.b #7,obTimeFrame(a0)
            columnAnimTimer = 7;
            // addq.b #1,obAniFrame(a0)
            columnAnimFrame++;
            // cmpi.b #2,obAniFrame(a0) / blo.s loc_F04C
            if (columnAnimFrame >= 2) {
                // move.b #0,obAniFrame(a0)
                columnAnimFrame = 0;
            }
        }

        // move.b obAniFrame(a0),d0 / add.b d1,d0 / move.b d0,obFrame(a0)
        displayFrame = columnAnimFrame + frameBase;

        // Geyser_ChkDel: out_of_range.w DeleteObject
        if (!isInRangeAt(currentX)) {
            setDestroyed(true);
        }
    }

    /**
     * Animate head piece using stored headAnimId (not subtype, which may be cleared).
     * Anim 5 (.bubble4): {0x11, 0x12} for geyser head.
     * Anim 2 (.end): {6, 7} for lavafall head/third piece.
     */
    private void updateHeadAnimation() {
        int[] frames = (headAnimId == 5) ? ANIM_BUBBLE4_FRAMES : ANIM_END_FRAMES;

        animTimer++;
        if (animTimer >= ANIM_SPEED) {
            animTimer = 0;
            animFrameIndex = (animFrameIndex + 1) % frames.length;
        }
        displayFrame = frames[animFrameIndex];
    }

    // ========================================================================
    // TouchResponseProvider Implementation
    // ========================================================================

    @Override
    public int getCollisionFlags() {
        // Only body pieces have collision
        if (isDestroyed() || role == Role.HEAD) {
            return 0;
        }
        return COLLISION_FLAGS;
    }

    @Override
    public int getCollisionProperty() {
        return 0;
    }

    /**
     * ROM: the body/column piece runs {@code bset #4,obRender(a1)} in
     * {@code .configureLavaObjects} (docs/s1disasm/_incObj/"4C, 4D MZ Lava
     * Geyser and Maker.asm":214), so BuildSprites computes its on-screen render
     * flag from {@code obHeight} ({@code move.b #256/2,obHeight(a1)} -> $80),
     * not the 32px assumed band. The 256px-tall column's anchor sits above the
     * camera top yet must keep render_flags bit 7 set so ReactToItem (the
     * engine's touch-response render-flag gate) still hurts the player. Only the
     * BODY piece sets the flag and carries collision; the HEAD does not.
     */
    @Override
    protected boolean usesCustomRenderHeight() {
        return role == Role.BODY;
    }

    /**
     * Body column half-height for the custom-height on-screen test:
     * {@code obHeight = #256/2 = $80} from the disassembly
     * (docs/s1disasm/_incObj/"4C, 4D MZ Lava Geyser and Maker.asm":213).
     */
    @Override
    public int getOnScreenHalfHeight() {
        return (role == Role.BODY) ? BODY_HEIGHT : super.getOnScreenHalfHeight();
    }

    // ========================================================================
    // Rendering
    // ========================================================================

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(ObjectArtKeys.MZ_LAVA_GEYSER);
        if (renderer == null) return;

        renderer.drawFrameIndex(displayFrame, currentX, currentY, false, false);
    }

    @Override
    public ObjectSpawn getSpawn() {
        return new ObjectSpawn(currentX, currentY, 0x4D, subtype, 0, false, 0);
    }

    @Override
    public int getX() {
        return currentX;
    }

    @Override
    public int getY() {
        return currentY;
    }

    @Override
    public int getPriorityBucket() {
        // GeyserMaker uses priority 1; third piece uses priority 0 (behind)
        if (behindPriority) {
            return RenderPriority.clamp(0);
        }
        return RenderPriority.clamp(1);
    }

    @Override
    public boolean isPersistent() {
        return !isDestroyed() && isInRangeAt(currentX);
    }

    // ========================================================================
    // Debug Rendering
    // ========================================================================

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        String roleStr = role.name();
        ctx.drawRect(currentX, currentY, 0x20, (role == Role.HEAD) ? 0x14 : BODY_HEIGHT,
                1.0f, 0.3f, 0.0f);
        ctx.drawWorldLabel(currentX, currentY, -1,
                String.format("Geyser[%s] sub=%d vy=%d frm=%d",
                        roleStr, subtype, velY, displayFrame),
                DEBUG_COLOR);
    }
}
