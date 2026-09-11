package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.constants.Sonic3kAnimationIds;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.GraphicsManager;
import com.openggf.graphics.RenderPriority;
import com.openggf.graphics.SpriteMaskReplayRole;
import com.openggf.game.GameRng;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreateObjectLinks;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Object 0x86 - Gumball Machine (Sonic 3 &amp; Knuckles Gumball bonus stage).
 * <p>
 * ROM reference: sonic3k.asm Obj_GumballMachine (line 127399).
 * <p>
 * The parent gumball machine object implements a 3-state state machine driven
 * by ROM $38 bit flags (shared with children for the ball-ejection chain):
 * <ul>
 *   <li>IDLE: waiting for player to enter trigger range; gated by bit 1.</li>
 *   <li>SPIN: playing the spin animation; sets bit 3 at the end to signal container.</li>
 *   <li>POST_TRIGGER: waiting for container to clear bit 1 (loc_60EA2), then IDLE.</li>
 * </ul>
 * The container child watches the parent's bit 3, runs byte_6145B animation,
 * spawns the ball on the first frame, then calls back to clear bits 1+3.
 * Separately, springs set bit 1 on the dispenser when they crumble; the
 * dispenser sees its own bit 1, spawns 16 ejection effects, and self-destroys.
 * <p>
 * On init, spawns 7 children: dispenser, ball container display, exit trigger,
 * 3 upper platforms, and 1 extra platform.
 * <p>
 * ROM attributes (ObjDat3 for GumballMachine):
 * <ul>
 *   <li>Mappings: Map_GumballBonus</li>
 *   <li>Art tile: make_art_tile(ArtTile_BonusStage, 1, 1) = palette 1, high priority</li>
 *   <li>Priority: $0100</li>
 * </ul>
 */
public class GumballMachineObjectInstance extends AbstractObjectInstance implements SpawnRewindRecreatable {

    private static final Logger LOGGER = Logger.getLogger(GumballMachineObjectInstance.class.getName());

    // ===== State machine =====

    private enum State {
        IDLE,
        SPIN,
        POST_TRIGGER
    }

    // ===== ROM constants =====

    // ROM word_60D16: dc.w -$24, $48, -8, $10 = (xOffset=-36, width=72, yOffset=-8, height=16)
    // Check_PlayerInRange / sub_8592C (sonic3k.asm:179994-180031) builds the box as
    //   left = objX + xOffset, right = left + width, top = objY + yOffset, bottom = top + height
    // and tests it HALF-OPEN: `cmp right,px / bhs out` and `cmp bottom,py / bhs out`
    // reject px>=right and py>=bottom, so the inclusive edges are only the low ones
    // (left/top). The box is therefore [left,right) x [top,bottom). Using `<=` on the
    // high edges fires one frame early on an approach that lands flush on the bottom
    // edge (py == objY+8): the S3K gumball trace has the player rising through py=objY+8
    // one frame before it truly enters the ROM box, which dispenses the ball a frame
    // early and desyncs its fall position at the eventual player pickup.
    private static final int ACTIVATE_X_MIN = -36;  // left  = objX + xOffset   (inclusive)
    private static final int ACTIVATE_X_MAX = 36;   // right = objX + xOffset+width (exclusive)
    private static final int ACTIVATE_Y_MIN = -8;   // top    = objY + yOffset   (inclusive)
    private static final int ACTIVATE_Y_MAX = 8;    // bottom = objY + yOffset+height (exclusive)

    // ROM: ObjDat_GumballMachine priority $0100 → bucket 2 (same as Sonic).
    // Draw_Sprite uses priority as byte offset into Sprite_table_input ($80/bucket).
    // Machine apparatus, bumpers, dispenser, springs, container all share bucket 2.
    // Within the same bucket, VDP slot order puts Sonic on top.
    private static final int PRIORITY_BUCKET = 2;

    // ===== Debug: press F11 to cycle bucket/priority isolation =====
    // -1 = normal (all render), 0-7 = show ONLY that bucket, 8 = show only HIGH pri, 9 = show only LOW pri
    private static volatile int debugBucketFilter = -1;
    private static volatile int debugSourceFilter = -1;
    private static final java.util.logging.Logger DEBUG_LOG = java.util.logging.Logger.getLogger("GumballDebug");
    static final String DEBUG_SOURCE_MACHINE_MAIN = "machine-main";
    static final String DEBUG_SOURCE_DISPENSER = "dispenser";
    static final String DEBUG_SOURCE_EJECTION_EFFECT = "ejection-effect";
    static final String DEBUG_SOURCE_CONTAINER_GLASS = "container-glass";
    static final String DEBUG_SOURCE_PLATFORM = "platform";
    static final String DEBUG_SOURCE_PLATFORM_EXTRA = "platform-extra-16";
    static final String DEBUG_SOURCE_BODY_OVERLAY = "body-overlay-17";
    static final String DEBUG_SOURCE_SPRING = "spring";
    static final String DEBUG_SOURCE_ITEM = "item";
    static final String DEBUG_SOURCE_BUMPER = "bumper";
    private static final String[] DEBUG_SOURCE_FILTERS = {
            DEBUG_SOURCE_MACHINE_MAIN,
            DEBUG_SOURCE_CONTAINER_GLASS,
            DEBUG_SOURCE_PLATFORM_EXTRA,
            DEBUG_SOURCE_BODY_OVERLAY,
            DEBUG_SOURCE_ITEM,
            DEBUG_SOURCE_DISPENSER,
            DEBUG_SOURCE_EJECTION_EFFECT,
            DEBUG_SOURCE_PLATFORM,
            DEBUG_SOURCE_SPRING,
            DEBUG_SOURCE_BUMPER
    };

    /** Called from GameLoop on F11 press during BONUS_STAGE mode. */
    public static void cycleDebugFilter() {
        debugBucketFilter++;
        if (debugBucketFilter > 9) {
            debugBucketFilter = -1;
        }
        if (debugBucketFilter == -1) {
            DEBUG_LOG.info("Gumball debug: ALL buckets/priorities (normal)");
        } else if (debugBucketFilter <= 7) {
            DEBUG_LOG.info("Gumball debug: showing ONLY bucket " + debugBucketFilter);
        } else if (debugBucketFilter == 8) {
            DEBUG_LOG.info("Gumball debug: showing ONLY HIGH priority objects");
        } else {
            DEBUG_LOG.info("Gumball debug: showing ONLY LOW priority objects");
        }
    }

    /** Called from GameLoop on INSERT press during BONUS_STAGE mode. */
    public static void cycleDebugSourceFilter() {
        debugSourceFilter++;
        if (debugSourceFilter >= DEBUG_SOURCE_FILTERS.length) {
            debugSourceFilter = -1;
        }
        if (debugSourceFilter == -1) {
            DEBUG_LOG.info("Gumball debug: ALL child sources");
            return;
        }
        DEBUG_LOG.info("Gumball debug: showing ONLY source " + DEBUG_SOURCE_FILTERS[debugSourceFilter]);
    }

    /** Returns true if this object should render given current debug filter. */
    static boolean shouldDebugRender(int bucket, boolean highPriority) {
        return shouldDebugRender(bucket, highPriority, null);
    }

    /** Returns true if this object should render given current debug bucket + source filters. */
    static boolean shouldDebugRender(int bucket, boolean highPriority, String sourceKey) {
        int filter = debugBucketFilter;
        boolean bucketMatch;
        if (filter == -1) {
            bucketMatch = true;
        } else if (filter <= 7) {
            bucketMatch = bucket == filter;
        } else if (filter == 8) {
            bucketMatch = highPriority;
        } else {
            bucketMatch = !highPriority; // filter == 9
        }
        if (!bucketMatch) {
            return false;
        }
        int sourceFilter = debugSourceFilter;
        if (sourceFilter == -1) {
            return true;
        }
        return DEBUG_SOURCE_FILTERS[sourceFilter].equals(sourceKey);
    }

    static void resetDebugFiltersForTest() {
        debugBucketFilter = -1;
        debugSourceFilter = -1;
    }

    static String getCurrentDebugSourceFilterForTest() {
        if (debugSourceFilter == -1) {
            return null;
        }
        return DEBUG_SOURCE_FILTERS[debugSourceFilter];
    }

    // ROM: ObjDat3_6138C (platform/pile children) priority $0200 → bucket 4.
    // Art tile make_art_tile(ArtTile_BonusStage,0,0) → VDP priority 0 (LOW).
    // Piles/glass render behind high-priority FG tiles, visible through transparent areas.
    private static final int BODY_PRIORITY_BUCKET = 4;

    // ROM loc_60D1E runs Animate_RawNoSST(byte_61450), byte_61450 = [3, 5, 6, 7, $14, 5, $F4, ...].
    // First byte (3) is the per-frame timer; frames are 5, 6, 7, $14, 5, then the $F4
    // control byte invokes the object's $34 routine (loc_60D32) which sets machine bit 3
    // and dispenses the ball.
    // ROM Animate_RawNoSST (sonic3k.asm:177341): `subq.b #1,anim_frame_timer / bpl skip`,
    // else reload anim_frame_timer from the duration byte (3, i.e. held 4 calls) and
    // advance to the NEXT table entry. anim_frame_timer is 0 on SPIN entry
    // (SetUp_ObjAttributes clears it and the IDLE state never animates), so the FIRST
    // call's `subq.b #1` immediately goes negative and advances past table[0]=5 (never
    // displayed) straight to table[1]=6 -- table[0]=5 is only ever the frame set by
    // SetUp_ObjAttributes on entry into SPIN, held for zero Animate calls. Each
    // subsequent entry (7, $14, 5) is held for a full duration+1=4 calls, so the
    // displayed sequence is 6x4, 7x4, $14x4, 5x4 = 16 calls, and the 17th call is the
    // one that finally decodes the $F4 control byte (dispensing the ball) without
    // changing the displayed frame. Total: 16 held + 1 control-detect = 17.
    private static final int[] SPIN_FRAMES = {6, 7, 0x14, 5};
    private static final int SPIN_FRAME_DURATION = 4; // ROM timer=3 + 1 for bpl check
    // Verified against the recorded ROM trace: IDLE->SPIN at frame 122, ball dispensed at
    // frame 139 (delta 17); the previous 5x4,6x4,7x4,$14x4,5x1 model got the same total
    // (17) but the wrong per-value durations/order -- see SPIN_FRAMES citation above.
    private static final int SPIN_TOTAL_FRAMES = SPIN_FRAMES.length * SPIN_FRAME_DURATION + 1;

    // ROM: ObjDat_GumballMachine byte 2 = 5 — default mapping frame (machine body)
    private static final int IDLE_MAPPING_FRAME = 5;

    // ROM: Obj_GumballMachine init subtracts $100 from y_pos at spawn.
    // The visible machine and all children sit 256 pixels above the placement position.
    private static final int MACHINE_Y_OFFSET = -0x100;

    // ===== Child offsets from parent position =====

    // Dispenser: ABSOLUTE world position (ROM loc_60D58 hardcodes to $100, $310).
    // The dispenser is at the BOTTOM of the stage, far below the machine body.
    private static final int DISPENSER_ABSOLUTE_X = 0x100;
    private static final int DISPENSER_ABSOLUTE_Y = 0x310;


    // Ball container display: (0, +0x24)
    private static final int CONTAINER_OFFSET_X = 0;
    private static final int CONTAINER_OFFSET_Y = 0x24;

    // ContainerDisplayChild's own captured spawn (px+CONTAINER_OFFSET_X,
    // py+CONTAINER_OFFSET_Y where py = spawn.y()+MACHINE_Y_OFFSET) is never refreshed
    // after construction, and the machine candidate's own getX()/getY() (no override,
    // so the ObjectInstance default: getSpawn().x()/y()) is likewise always the raw,
    // undrifted placement spawn -- both sides derive from the SAME spawn.x()/y()
    // reference, so the difference is the EXACT fixed constant
    // |MACHINE_Y_OFFSET(-0x100) + CONTAINER_OFFSET_Y(0x24)| = 0xDC (220px) in Y, 0 in X,
    // regardless of where the ROM actually places this machine. 0x100 (256px) rounds
    // that up with headroom.
    private static final int CONTAINER_TO_MACHINE_MAX_DISTANCE = 0x100;

    // Exit trigger: (0, +0x2A0)
    private static final int EXIT_TRIGGER_OFFSET_X = 0;
    private static final int EXIT_TRIGGER_OFFSET_Y = 0x2A0;

    // Platform left: (-0x38, -0x2C)
    private static final int PLATFORM_LEFT_OFFSET_X = -0x38;
    private static final int PLATFORM_LEFT_OFFSET_Y = -0x2C;

    // Platform center: (0, -0x2C)
    private static final int PLATFORM_CENTER_OFFSET_X = 0;
    private static final int PLATFORM_CENTER_OFFSET_Y = -0x2C;

    // Platform right: (+0x38, -0x2C)
    private static final int PLATFORM_RIGHT_OFFSET_X = 0x38;
    private static final int PLATFORM_RIGHT_OFFSET_Y = -0x2C;

    // Platform extra: (0, -0x28)
    private static final int PLATFORM_EXTRA_OFFSET_X = 0;
    private static final int PLATFORM_EXTRA_OFFSET_Y = -0x28;

    // Springs: 4 vertical red springs at the bottom of the stage.
    // ROM: ChildObjDat_61424 spawns 4 springs as children of the dispenser (loc_60D58)
    // at offsets (-$30, -$18), (-$10, -$18), ($10, -$18), ($30, -$18).
    // The dispenser is at absolute (0x100, 0x310), so springs are at absolute (0xD0-0x130, 0x2F8).
    // Y offset from machine-adjusted Y: dispenser_y - machine_y - $18 = 0 - 0x18 = -$18
    private static final int[] SPRING_X_OFFSETS = { -0x30, -0x10, 0x10, 0x30 };
    private static final int SPRING_Y_OFFSET = -0x18;

    // A spring's own captured spawn (sx,sy = DISPENSER_ABSOLUTE_X/Y + the spring's own
    // fixed offset above) and DispenserChild's own captured spawn
    // (buildSpawnAt(DISPENSER_ABSOLUTE_X, DISPENSER_ABSOLUTE_Y), likewise never
    // refreshed after construction) are BOTH hardcoded absolute constants -- neither
    // side depends on the machine's actual placement spawn -- so the true distance
    // between any spring and the dispenser is the EXACT, fixed
    // sqrt(springX^2 + SPRING_Y_OFFSET^2), at most sqrt(0x30^2+0x18^2) =~ 54px (the
    // outermost spring). 0x40 (64px) rounds that up with headroom.
    private static final int SPRING_TO_DISPENSER_MAX_DISTANCE = 0x40;

    // ===== Machine Y drift / slot tracking =====
    //
    // ROM: 28 bytes at $FF2000..$FF201B, 14 word-sized "slots" (pairs of bumpers).
    // Initialized to 0xFF at machine spawn (ROM 127413-127419).
    // Tested by sub_6126C as words: word is "occupied" when non-zero.
    // Individual bumpers clear their byte when bumped (ROM 127692-127695).
    // ROM fills 36 bytes ($24) at $FF2000 at init. The iteration in sub_6126C only
    // reads 28 bytes (14 words), but the extra bytes are kept defensively so any
    // future subtype in [28..35] range won't corrupt adjacent memory.
    private static final int SLOT_COUNT_BYTES = 36;
    private static final int SLOT_WORD_COUNT = 14;
    private static final int DRIFT_PER_EMPTY_SLOT = 0x20;
    private static final int DRIFT_STEP_PER_FRAME = 4;

    private final byte[] slotRam = new byte[SLOT_COUNT_BYTES];

    // ROM $3A: original Y (saved at init, before any drift).
    private int savedY;

    // ROM $3C: current drift target (savedY + emptySlotPrefix*0x20).
    private int targetY;

    // ROM y_pos: current Y, updated +4/frame until >= targetY.
    private int currentY;

    // ROM bit 0 of $38: set by bumpers when they clear a slot; machine recounts.
    private boolean slotRecalcNeeded;

    private boolean driftInitialized;

    // ===== Instance state =====

    private State state = State.IDLE;
    private int spinTimer;
    private int currentFrame = IDLE_MAPPING_FRAME;
    private DispenserChild dispenser;

    // ROM $38 field bit flags — shared state with children for ball-ejection chain.
    private byte flagByte38;

    // ROM $FF2020: shared triangle-bumper cooldown timer. Negative means active.
    private int bumperCooldownTimer = -1;

    // Tracks active gumball springs for respawn on REP gumball collect.
    private final List<GumballSpringChild> springs = new ArrayList<>();
    private final List<int[]> springOriginalPositions = new ArrayList<>();

    private boolean childrenSpawned;

    /**
     * @return the current active gumball machine, or null if none
     */
    public static GumballMachineObjectInstance current(ObjectManager objectManager) {
        if (objectManager == null) {
            return null;
        }
        List<GumballMachineObjectInstance> machines =
                objectManager.activeObjectsOfType(GumballMachineObjectInstance.class);
        return machines.isEmpty() ? null : machines.get(0);
    }

    public GumballMachineObjectInstance(ObjectSpawn spawn) {
        super(spawn, "GumballMachine");
    }

    @Override
    protected void afterRewindRestoreSettled() {
        ObjectManager objectManager = services().objectManager();
        if (objectManager == null) {
            return;
        }
        dispenser = objectManager.getActiveObjects().stream()
                .filter(object -> object instanceof DispenserChild && !object.isDestroyed())
                .map(DispenserChild.class::cast)
                .findFirst()
                .orElse(null);
        springs.clear();
        objectManager.getActiveObjects().stream()
                .filter(object -> object instanceof GumballSpringChild && !object.isDestroyed())
                .map(GumballSpringChild.class::cast)
                .filter(spring -> spring.parent == this)
                .forEach(springs::add);
    }

    private void spawnChildren() {
        int px = spawn.x();
        int py = spawn.y() + MACHINE_Y_OFFSET;

        // 1. Dispenser — ABSOLUTE (0x100, 0x310) per ROM loc_60D58.
        // Independent of machine position; sits at the bottom of the stage.
        dispenser = spawnChild(() -> new DispenserChild(
                buildSpawnAt(DISPENSER_ABSOLUTE_X, DISPENSER_ABSOLUTE_Y)));

        // 2. Ball container display — follows machine (y+0x24 via Refresh_ChildPosition)
        spawnChild(() -> new ContainerDisplayChild(
                buildSpawnAt(px + CONTAINER_OFFSET_X, py + CONTAINER_OFFSET_Y),
                this, CONTAINER_OFFSET_Y));

        // 3. Exit trigger — relative to machine, +0x2A0 Y
        spawnChild(() -> new ExitTriggerChild(
                buildSpawnAt(px + EXIT_TRIGGER_OFFSET_X, py + EXIT_TRIGGER_OFFSET_Y)));

        // 4-7. Platforms — follow machine via Refresh_ChildPosition.
        // ROM sub_61362 + RawAni_61388 = [0, 1, 0, $16]: each platform child gets a
        // per-slot mapping frame indexed by (subtype - 6) / 2 where subtype is the
        // child's CreateChild1_Normal index (6, 8, $A, $C for the 4 platform slots).
        // Children 3 (left), 5 (right): frame 0 (top cap tiles).
        // Child 4 (center): frame 1 (top cap center tiles).
        // Child 6 (extra): frame 0x16 — the MAIN MACHINE BODY sprite.
        spawnChild(() -> new PlatformChild(
                buildSpawnAt(px + PLATFORM_LEFT_OFFSET_X, py + PLATFORM_LEFT_OFFSET_Y),
                "GumballPlatformLeft", PLATFORM_LEFT_OFFSET_Y, 0x00));
        spawnChild(() -> new PlatformChild(
                buildSpawnAt(px + PLATFORM_CENTER_OFFSET_X, py + PLATFORM_CENTER_OFFSET_Y),
                "GumballPlatformCenter", PLATFORM_CENTER_OFFSET_Y, 0x01));
        spawnChild(() -> new PlatformChild(
                buildSpawnAt(px + PLATFORM_RIGHT_OFFSET_X, py + PLATFORM_RIGHT_OFFSET_Y),
                "GumballPlatformRight", PLATFORM_RIGHT_OFFSET_Y, 0x00));
        spawnChild(() -> new PlatformChild(
                buildSpawnAt(px + PLATFORM_EXTRA_OFFSET_X, py + PLATFORM_EXTRA_OFFSET_Y),
                "GumballPlatformExtra", PLATFORM_EXTRA_OFFSET_Y, 0x16));
        // 5th overlay child for the extra platform: the glass dome shine (sprite-mask
        // effect). ROM sub_61362 detects frame=$16 on the child and calls
        // CreateChild6_Simple with ChildObjDat_6144A, which spawns loc_610B6 using
        // ObjDat3_613EC (mapping frame $17, palette 0, art_tile 0). The overlay
        // follows the extra platform's position (ROM loc_610C6 copies parent pos).
        spawnChild(() -> new BodyOverlayChild(
                buildSpawnAt(px + PLATFORM_EXTRA_OFFSET_X, py + PLATFORM_EXTRA_OFFSET_Y),
                PLATFORM_EXTRA_OFFSET_Y));

        // 4 springs at bottom of stage (ROM: ChildObjDat_61424 as children of dispenser).
        // Absolute positions: dispenser (0x100, 0x310) + offsets (-$30..$30, -$18).
        // Springs crumble on first use and are respawned by REP gumball.
        springs.clear();
        springOriginalPositions.clear();
        for (int springX : SPRING_X_OFFSETS) {
            final int sx = DISPENSER_ABSOLUTE_X + springX;
            final int sy = DISPENSER_ABSOLUTE_Y + SPRING_Y_OFFSET;
            springOriginalPositions.add(new int[]{sx, sy});
            final DispenserChild dispenserRef = dispenser;
            GumballSpringChild spring = spawnChild(() -> new GumballSpringChild(
                    buildSpawnAt(sx, sy), this, dispenserRef));
            springs.add(spring);
        }
    }

    // ===== State machine =====

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        // Initialize drift state on first update (separated from child spawning
        // so tests can exercise drift logic without requiring services()).
        if (!driftInitialized) {
            // ROM: Obj_GumballMachine seeds RNG_seed from V_int_run_count at init
            // (move.l (V_int_run_count).w,(RNG_seed).w, sonic3k.asm:127412). The
            // intent is to fold run-history entropy (VBlanks since power-on: menu
            // time, prior acts, etc.) into the bonus-stage RNG so the ball-subtype
            // roll (sub_612A8, sonic3k.asm:127988-128008) varies run-to-run.
            //
            // The engine's shared RNG ALREADY carries that run-history entropy when
            // the machine spawns: it has been advanced by all prior gameplay in live
            // play, and in trace replay the bootstrap has already primed it to the
            // recorded run's exact seed (V_int_run_count for that recording;
            // TraceReplaySessionBootstrap.applyInitialRngSeedForReplay, uniform for
            // every trace carrying metadata.rng_seed). So the ROM invariant
            // "RNG_seed == V_int_run_count" is already satisfied by the RNG's own
            // established state on this tick, and modeling the reseed here is a
            // read of that same value -- i.e. a no-op.
            //
            // The update parameter is now named vIntRunCount because it represents this
            // ROM clock. This terminology-only refactor deliberately does not change the
            // established shared-RNG ownership or add a reseed here; that behavior needs
            // separate ROM-parity validation before it can change safely.
            initDrift();
        }

        // Spawn children on first update — can't do it in constructor because
        // services() isn't available until ObjectManager injects them.
        if (!childrenSpawned) {
            childrenSpawned = true;
            spawnChildren();
        }

        // ROM sub_6126C: recount empty slots and apply drift each frame.
        applyDrift();
        bumperCooldownTimer--;

        switch (state) {
            case IDLE -> updateIdle(playerEntity);
            case SPIN -> updateSpin();
            case POST_TRIGGER -> updatePostTrigger();
        }
    }

    /**
     * ROM Obj_GumballMachine init (line 127399).
     * <ul>
     *   <li>line 127405: move.w y_pos(a0),$3A(a0) — save initial Y after -$100 offset.</li>
     *   <li>lines 127413-127419: init slot RAM ($FF2000..$FF201B) to 0xFF.</li>
     * </ul>
     * Package-private so unit tests can drive drift logic without requiring the
     * child-spawn path (which needs injected ObjectServices).
     */
    void initDrift() {
        driftInitialized = true;
        savedY = spawn.y() + MACHINE_Y_OFFSET;
        currentY = savedY;
        targetY = savedY;
        java.util.Arrays.fill(slotRam, (byte) 0xFF);
        slotRecalcNeeded = false;
        bumperCooldownTimer = -1;
    }

    /** ROM sub_6126C (line 127949). Recount empty-slot prefix, apply drift. */
    void applyDrift() {
        if (slotRecalcNeeded) {
            slotRecalcNeeded = false;
            int accum = 0;
            for (int i = 0; i < SLOT_WORD_COUNT; i++) {
                int lo = slotRam[i * 2] & 0xFF;
                int hi = slotRam[i * 2 + 1] & 0xFF;
                int word = (hi << 8) | lo;
                if (word != 0) {
                    break; // ROM: tst.w / bne.s loc_6128A
                }
                accum += DRIFT_PER_EMPTY_SLOT;
            }
            targetY = savedY + accum;
        }
        if (currentY < targetY) {
            currentY += DRIFT_STEP_PER_FRAME;
        }
    }

    // ===== ROM $38 field bit flags =====

    /** ROM bit 1: set when SPIN begins, cleared when container anim completes. Gates re-trigger. */
    public boolean isBit1Set() { return (flagByte38 & 0x02) != 0; }
    public void setMachineBit1() { flagByte38 |= 0x02; }

    /** ROM bit 3: set at end of SPIN, signals container to animate + spawn ball. */
    public boolean isBit3Set() { return (flagByte38 & 0x08) != 0; }
    public void setMachineBit3() { flagByte38 |= 0x08; }

    /** ROM loc_60EA2: container anim complete. */
    public void clearMachineBits1and3() { flagByte38 &= ~0x0A; }

    /** ROM bit 7: machine has seen first random=0 roll (for ball subtype selection). */
    public boolean isBit7Set() { return (flagByte38 & 0x80) != 0; }
    public void setBit7(boolean value) {
        if (value) {
            flagByte38 |= (byte) 0x80;
        } else {
            flagByte38 &= (byte) 0x7F;
        }
    }

    /** Called by GumballBumperObjectInstance on player bump. ROM lines 127692-127698. */
    public void onBumperHit(int subtype) {
        if (subtype < 0 || subtype >= SLOT_COUNT_BYTES) {
            return;
        }
        slotRam[subtype] = 0;
        flagByte38 |= 0x01;
        bumperCooldownTimer = 0x0F;
        slotRecalcNeeded = true;
    }

    boolean areBumpersActive() {
        return bumperCooldownTimer < 0;
    }

    /** Returns the machine's current (drifted) Y position. */
    public int getCurrentY() {
        return currentY;
    }

    /** Returns the machine's saved (initial) Y position. Package-private for tests. */
    int getSavedY() {
        return savedY;
    }

    /** Returns the current drift target Y. Package-private for tests. */
    int getTargetY() {
        return targetY;
    }

    /**
     * IDLE state: Check if player is within activation range.
     * ROM: checks player centre vs object position with asymmetric box.
     */
    private void updateIdle(PlayableEntity playerEntity) {
        if (playerEntity == null) {
            return;
        }

        // ROM: bit 1 on machine gates re-trigger. Container animation clears
        // bits 1+3 at loc_60EA2 when byte_6145B sequence completes. Until then,
        // the machine is locked out of re-spinning.
        if (isBit1Set()) {
            return;
        }

        int playerX = playerEntity.getCentreX();
        int playerY = playerEntity.getCentreY();
        int dx = playerX - spawn.x();
        int dy = playerY - currentY;

        // ROM sub_8592C: half-open box — low edges inclusive, high edges exclusive (bhs).
        if (dx >= ACTIVATE_X_MIN && dx < ACTIVATE_X_MAX
                && dy >= ACTIVATE_Y_MIN && dy < ACTIVATE_Y_MAX) {
            // ROM: play sfx_GumballTab, determine flip, transition to SPIN
            try {
                services().playSfx(Sonic3kSfx.GUMBALL_TAB.id);
            } catch (Exception e) {
                // Prevent audio failure from breaking game logic
            }

            spinTimer = 0;
            setMachineBit1();
            state = State.SPIN;
            LOGGER.fine("GumballMachine: IDLE -> SPIN (player in range)");
        }
    }

    /**
     * SPIN state: Animate the spin sequence.
     * ROM: frames [3,5,6,7,$14,5,$F4,$7F,5,5,$FC] with per-frame timing.
     * <p>
     * At end of animation, sets machine bit 3 to signal the container
     * (loc_60E5C -> loc_60E8C) to animate and spawn a ball.
     */
    private void updateSpin() {
        int frameIndex = spinTimer / SPIN_FRAME_DURATION;
        if (frameIndex < SPIN_FRAMES.length) {
            currentFrame = SPIN_FRAMES[frameIndex];
        }
        spinTimer++;

        if (spinTimer >= SPIN_TOTAL_FRAMES) {
            currentFrame = IDLE_MAPPING_FRAME;
            setMachineBit3();
            state = State.POST_TRIGGER;
            LOGGER.fine("GumballMachine: SPIN -> POST_TRIGGER (bit 3 set, container signaled)");
        }
    }

    /**
     * POST_TRIGGER state: Wait for container to clear bit 1 (ROM loc_60EA2).
     * While bit 1 remains set, the machine is busy. Once cleared, return to IDLE.
     */
    private void updatePostTrigger() {
        if (!isBit1Set()) {
            state = State.IDLE;
            LOGGER.fine("GumballMachine: POST_TRIGGER -> IDLE (container finished)");
        }
    }

    // ===== Ball subtype selection =====

    /**
     * ROM byte_612E0 — random index (0-15) to subtype mapping.
     * Applied by sub_612A8 to give gumball items a subtype distribution.
     */
    private static final int[] SUBTYPE_LOOKUP = {
            0, 3, 1, 4, 2, 4, 5, 4, 6, 3, 7, 4, 5, 6, 7, 2
    };

    /**
     * ROM sub_612A8 (line 127983): random 0-15 indexes byte_612E0 to choose ball subtype.
     * <p>
     * ROM behavior (bset #7 sets Z based on OLD bit value):
     * <ul>
     *   <li>On first zero-roll: bit 7 was CLEAR (old=0) → Z=1 → beq branches keeping d0=0
     *       → LUT[0] = 0 (EXTRA LIFE subtype)</li>
     *   <li>On subsequent zero-rolls: bit 7 was SET (old=1) → Z=0 → fall through
     *       → moveq #3,d0 → LUT[3] = 4 (push player subtype)</li>
     * </ul>
     */
    public int chooseBallSubtype() {
        GameRng rng = services().rng();
        int r = rng.nextInt(16);
        if (r == 0) {
            boolean wasSet = isBit7Set();
            setBit7(true);
            if (wasSet) {
                r = 3; // ROM: fall through with d0=3 → LUT[3]=4
            }
            // else: r stays 0 → LUT[0]=0 (first zero-roll gives EXTRA LIFE)
        }
        return SUBTYPE_LOOKUP[r];
    }

    /**
     * Called by ContainerDisplayChild when it activates (parent bit 3 set).
     * <p>
     * {@code parentYSupplier} lets the spawned ball track its spawning container's
     * LIVE y (ROM: {@code parent3(a0)}, the container/crank child), matching ROM
     * loc_60EE0's per-frame clamp that keeps the ball from rising above its
     * spawner while the machine itself may still be drifting downward
     * (sonic3k.asm:127609-127616).
     */
    public void onContainerSpawnBall(int x, int y, java.util.function.IntSupplier parentYSupplier) {
        int subtype = chooseBallSubtype();
        ObjectSpawn gumballSpawn = new ObjectSpawn(x, y, 0xEB, subtype, 0, false, 0);
        spawnChild(() -> new GumballItemObjectInstance(gumballSpawn, 0, true, parentYSupplier));
        LOGGER.fine("GumballMachine: container spawned ball, subtype=" + subtype);
    }

    /** ROM loc_60EA2: called by container when animation completes. */
    public void onContainerAnimComplete() {
        clearMachineBits1and3();
        LOGGER.fine("GumballMachine: container animation complete, bits 1+3 cleared");
    }

    /**
     * Respawns any destroyed gumball springs at their original positions.
     * Called when the REP (subtype 1) gumball is collected.
     * <p>
     * ROM: loc_61130 respawns the dispenser, which respawns its 4 spring
     * children via ChildObjDat_61424. We replicate that by iterating over
     * our tracked springs and re-spawning any that have been destroyed.
     */
    public void respawnSprings() {
        // Destroy any surviving old springs before replacing them, so stale
        // entries don't linger in the object manager.
        for (GumballSpringChild old : springs) {
            if (old != null && !old.isDestroyed()) {
                old.setDestroyed(true);
            }
        }
        springs.clear();

        // If dispenser was destroyed (by spring → bit 1 chain), respawn it first
        // so newly-spawned springs have a live parent to signal.
        if (dispenser == null || dispenser.isDestroyed()) {
            dispenser = spawnChild(() -> new DispenserChild(
                    buildSpawnAt(DISPENSER_ABSOLUTE_X, DISPENSER_ABSOLUTE_Y)));
        }

        // Spawn fresh springs linked to the live dispenser.
        for (int[] pos : springOriginalPositions) {
            final int sx = pos[0];
            final int sy = pos[1];
            final DispenserChild dispenserRef = dispenser;
            GumballSpringChild spring = spawnChild(() -> new GumballSpringChild(
                    buildSpawnAt(sx, sy), this, dispenserRef));
            springs.add(spring);
        }
        LOGGER.fine("GumballMachine: respawned dispenser + " + springs.size() + " springs");
    }

    // ===== Rendering =====

    @Override
    public boolean isPersistent() {
        // The gumball machine is the central object of the bonus stage; keep it active
        return true;
    }

    @Override
    public boolean isHighPriority() {
        // ROM: ObjDat_GumballMachine uses make_art_tile(ArtTile_BonusStage, 1, 1)
        // — VDP priority bit = 1. Must render in front of high-priority FG tiles
        // (the machine body chunks in the level layout).
        return true;
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(PRIORITY_BUCKET);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (!shouldDebugRender(PRIORITY_BUCKET, isHighPriority(), DEBUG_SOURCE_MACHINE_MAIN)) return;
        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.GUMBALL_BONUS);
        if (renderer == null) {
            return;
        }

        // Render at currentY so the machine visually slides as bumpers clear.
        int renderY = driftInitialized ? currentY : (spawn.y() + MACHINE_Y_OFFSET);
        GraphicsManager graphicsManager = services().graphicsManager();
        if (graphicsManager != null) {
            graphicsManager.setCurrentSpriteSatDebugSource(String.format(
                    "%s frame=0x%02X slot=%d bucket=%d high=%s",
                    name, currentFrame, getSlotIndex(), getPriorityBucket(), isHighPriority()));
        }
        try {
            renderer.drawFrameIndex(currentFrame, spawn.x(), renderY, false, false);
        } finally {
            if (graphicsManager != null) {
                graphicsManager.setCurrentSpriteSatDebugSource(null);
            }
        }
    }

    // =====================================================================
    // Inner child classes
    // =====================================================================

    /**
     * Dispenser child — solid platform at the machine's dispenser position.
     * <p>
     * ROM sub_61314 / loc_60D96: When a spring crumbles, it sets bit 1 on the
     * dispenser; the dispenser sees its own bit 1, spawns 16 ejection effects
     * from byte_61342 + sub_61320, and self-destroys.
     */
    static class DispenserChild extends AbstractObjectInstance
            implements SolidObjectProvider, SolidObjectListener, SpawnRewindRecreatable {

        // ROM sub_61314: d1=$4B (halfWidth=75), d2=$10 (airHalfHeight=16), d3=$11 (groundHalfHeight=17)
        private static final SolidObjectParams SOLID_PARAMS = new SolidObjectParams(75, 16, 17);
        private static final int MAPPING_FRAME = 0x13; // ROM ObjDat3_61398 byte 2

        private boolean springBitSet;  // ROM bit 1 of $38

        DispenserChild(ObjectSpawn spawn) {
            super(spawn, "GumballDispenser");
        }

        @Override
        public boolean isHighPriority() {
            // ROM: ObjDat3_61398 make_art_tile(ArtTile_BonusStage, 1, 1) — VDP priority 1
            return true;
        }

        /** ROM loc_60E44: called by spring when it crumbles. */
        public void setSpringBit() { this.springBitSet = true; }

        /** ROM btst #1, $38(a1) — siblings chain-crumble when this bit is set. */
        public boolean isSpringBitSet() { return springBitSet; }

        @Override
        public boolean isPersistent() {
            return true;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity playerEntity) {
            if (springBitSet) {
                // ROM loc_60D96: spawn 16 ejection effects + delete self.
                for (int i = 0; i < 16; i++) {
                    final int idx = i;
                    spawnChild(() -> new EjectionEffectChild(
                            buildSpawnAt(spawn.x(), spawn.y()), idx));
                }
                setDestroyed(true);
            }
        }

        @Override
        public boolean isSolidFor(PlayableEntity playerEntity) { return !springBitSet; }

        @Override
        public SolidObjectParams getSolidParams() {
            return SOLID_PARAMS;
        }

        @Override
        public boolean isTopSolidOnly() {
            // ROM sub_61314 calls SolidObjectFull — 4-sided collision.
            return false;
        }

        @Override
        public void onSolidContact(PlayableEntity player, SolidContact contact, int frameCounter) {
            // No action — pure platform
        }

        @Override
        public int getPriorityBucket() {
            return RenderPriority.clamp(PRIORITY_BUCKET);
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            if (!shouldDebugRender(getPriorityBucket(), isHighPriority(), DEBUG_SOURCE_DISPENSER)) return;
            PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.GUMBALL_BONUS);
            if (renderer == null) {
                return;
            }
            GraphicsManager graphicsManager = services().graphicsManager();
            if (graphicsManager != null) {
                graphicsManager.setCurrentSpriteSatDebugSource(String.format(
                        "%s frame=0x%02X slot=%d bucket=%d high=%s",
                        name, MAPPING_FRAME, getSlotIndex(), getPriorityBucket(), isHighPriority()));
            }
            try {
                renderer.drawFrameIndex(MAPPING_FRAME, spawn.x(), spawn.y(), false, false);
            } finally {
                if (graphicsManager != null) {
                    graphicsManager.setCurrentSpriteSatDebugSource(null);
                }
            }
        }
    }

    /**
     * ROM loc_6101E / loc_61032 / sub_61320 / byte_61342.
     * 16 spawned when dispenser deletes. Each has a different subtype (0-15) giving
     * a unique position offset and timer duration.
     */
    static class EjectionEffectChild extends AbstractObjectInstance implements RewindRecreatable {
        private static final int MAPPING_FRAME = 0x15;

        // ROM byte_61342: 16 signed (dx, dy) offsets
        private static final int[][] OFFSETS = {
                {  -8,    8}, {   8,    8}, {  -8,   -8}, {   8,   -8},
                {-0x18,   8}, { 0x18,   8}, {-0x18,  -8}, { 0x18,  -8},
                {-0x28,   8}, { 0x28,   8}, {-0x28,  -8}, { 0x28,  -8},
                {-0x38,   8}, { 0x38,   8}, {-0x38,  -8}, { 0x38,  -8}
        };

        private int timer;  // ROM $2E(a0)
        private int drawX;
        private int drawY;

        EjectionEffectChild(ObjectSpawn spawn, int subtype) {
            super(spawn, "GumballEjectionEffect");
            // ROM CreateChild6_Simple assigns subtypes via addq.w #2,d2 → 0,2,4,...,30.
            // Timer ($2E) = subtype, so effects live for subtype+1 frames (1..31).
            // We pass 0..15 as the OFFSETS index, so double it to get the ROM timer value.
            timer = subtype * 2;
            int[] off = OFFSETS[subtype];
            drawX = spawn.x() + off[0];
            drawY = spawn.y() + off[1];
        }

        @Override
        public EjectionEffectChild recreateForRewind(RewindRecreateContext ctx) {
            return new EjectionEffectChild(ctx.spawn(), 0);
        }

        @Override
        public boolean isHighPriority() {
            // ROM: ObjDat3_613D4 make_art_tile(ArtTile_BonusStage, 1, 1) — VDP priority 1
            return true;
        }

        @Override public boolean isPersistent() { return false; }

        @Override
        public void update(int vIntRunCount, PlayableEntity playerEntity) {
            timer--;
            if (timer < 0) {
                // ROM loc_61032: `subq.b #1,$2E(a0) / bpl draw / move.l #MoveChkDel,(a0)`.
                // After the timer expires, the ROM swaps the update routine to
                // MoveChkDel, which does: `MoveSprite` (applies gravity +$38 to y_vel
                // each frame, then updates pos from velocity) + `Sprite_CheckDeleteXY`
                // (delete when off-screen).
                //
                // CRITICAL: sub_61320 (the init routine) only writes timer ($2E) and
                // position offsets — it NEVER initializes x_vel or y_vel. So effects
                // start with zero velocity. Gravity (+$38/frame) accumulates slowly,
                // and the effect falls a short distance before the off-screen check
                // deletes it. With no horizontal velocity, off-screen only happens
                // vertically (stage camera is fixed in the gumball bonus stage).
                //
                // Our engine takes the shortcut of destroying the effect immediately
                // on timer expiry, which is visually indistinguishable for this
                // transient debris (no x drift, tiny y drop before deletion anyway).
                // This is intentional ROM-accurate-enough behaviour.
                setDestroyed(true);
            }
        }

        @Override public int getPriorityBucket() { return RenderPriority.clamp(PRIORITY_BUCKET); }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            if (!shouldDebugRender(getPriorityBucket(), isHighPriority(), DEBUG_SOURCE_EJECTION_EFFECT)) return;
            PatternSpriteRenderer r = getRenderer(Sonic3kObjectArtKeys.GUMBALL_BONUS);
            if (r == null) return;
            GraphicsManager graphicsManager = services().graphicsManager();
            if (graphicsManager != null) {
                graphicsManager.setCurrentSpriteSatDebugSource(String.format(
                        "%s frame=0x%02X slot=%d bucket=%d high=%s",
                        name, MAPPING_FRAME, getSlotIndex(), getPriorityBucket(), isHighPriority()));
            }
            try {
                r.drawFrameIndex(MAPPING_FRAME, drawX, drawY, false, false);
            } finally {
                if (graphicsManager != null) {
                    graphicsManager.setCurrentSpriteSatDebugSource(null);
                }
            }
        }
    }

    /**
     * Ball container display child — visual animation of the gumball container.
     * <p>
     * ROM: byte_6145B container animation (loc_60E5C -> loc_60E8C -> loc_60EA2).
     * Positioned at (0, +0x24) from parent machine.
     * <p>
     * State machine:
     * <ul>
     *   <li>DORMANT — idle, watches parent's bit 3; renders IDLE_FRAME.</li>
     *   <li>ANIMATING — runs byte_6145B pairs; on first frame, calls
     *       onContainerSpawnBall(). When complete, calls onContainerAnimComplete()
     *       (clears parent bits 1+3) and returns to DORMANT.</li>
     * </ul>
     */
    static class ContainerDisplayChild extends AbstractObjectInstance implements RewindRecreatable {

        // ROM byte_6145B (sonic3k.asm:128) — flat (mapping_frame, delay) pairs
        // terminated by the $F4 control byte (which runs the object's $34 routine,
        // loc_60EA2, clearing machine bits 1+3). Animate_RawNoSSTMultiDelay
        // (sonic3k.asm Animate_RawNoSSTMultiDelay) decrements anim_frame_timer each
        // call and, when it goes negative, does `addq.w #2,anim_frame` then loads the
        // next (frame,delay). The $F4 handler loc_845CC does `clr.b anim_frame`, so
        // every animation run restarts at offset 0 with a stale-negative timer — the
        // FIRST animating frame therefore advances immediately past the leading
        // (2,$3) pair to the (3,$3) pair. The visible run is frames 3,4,3,2 and it
        // lasts exactly 29 frames from ball spawn to bit-1 clear, verified against the
        // recorded ROM trace (container ANIM state spans f139-f167, f189-f217,
        // f627-f656, f675-f704 … = 29 frames every cycle). The prior model iterated
        // all five pairs (32 frames), making the machine dispense cycle 3 frames too
        // long; the cadence drifted enough to drop the f675 push-ball so the player
        // was never launched at f728.
        private static final int[] ANIM_TABLE = {2, 3, 3, 3, 4, 0xF, 3, 3, 2, 3, 0xF4};
        private static final int IDLE_FRAME = 2;

        private enum State { DORMANT, ANIMATING }
        private GumballMachineObjectInstance parent;
        private int offsetFromMachine; // Y offset (ROM: +$24)
        private State state = State.DORMANT;
        private int animStep;  // ROM anim_frame: byte offset into ANIM_TABLE
        private int animTimer;  // ROM anim_frame_timer
        private int currentFrame = IDLE_FRAME;

        ContainerDisplayChild(ObjectSpawn spawn, GumballMachineObjectInstance parent,
                              int offsetFromMachine) {
            super(spawn, "GumballContainer");
            this.parent = parent;
            this.offsetFromMachine = offsetFromMachine;
        }

        @Override
        public ContainerDisplayChild recreateForRewind(RewindRecreateContext ctx) {
            // Display child of the gumball machine. If the machine was swept before
            // capture there is no anchor to read position from, so drop the child
            // rather than throw. acceptDestroyed relinks to a restored-but-destroyed
            // machine when that is the captured parent. Bounded (see
            // CONTAINER_TO_MACHINE_MAX_DISTANCE) since this child's captured spawn is an
            // exact fixed offset from the machine's own spawn reference.
            return RewindRecreateObjectLinks.nearestObject(
                            ctx, GumballMachineObjectInstance.class, true, CONTAINER_TO_MACHINE_MAX_DISTANCE)
                    .map(machine -> new ContainerDisplayChild(ctx.spawn(), machine, CONTAINER_OFFSET_Y))
                    .orElse(null);
        }

        @Override
        public boolean isHighPriority() {
            // ROM: ObjDat3_613BC make_art_tile(ArtTile_BonusStage, 1, 1) — VDP priority 1
            return true;
        }

        @Override
        public boolean isPersistent() {
            return true;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity playerEntity) {
            if (state == State.DORMANT) {
                if (parent.isBit3Set()) {
                    state = State.ANIMATING;
                    // ROM: anim_frame is 0 (cleared by the previous run's $F4 handler)
                    // and anim_frame_timer is stale-negative, so the first ANIMATING
                    // frame advances at once. currentFrame stays IDLE this frame:
                    // loc_60E5C spawns the ball but does not animate the container
                    // until the following frame's loc_60E8C call.
                    animStep = 0;
                    animTimer = 0;
                    int spawnY = parent.getCurrentY() + offsetFromMachine;
                    parent.onContainerSpawnBall(spawn.x(), spawnY, this::getY);
                }
                return;
            }
            // ANIMATING — Animate_RawNoSSTMultiDelay emulation.
            animTimer--;                 // subq.b #1,anim_frame_timer
            if (animTimer < 0) {         // bpl skips the advance while timer stays >= 0
                animStep += 2;           // addq.w #2,anim_frame
                int frame = ANIM_TABLE[animStep];
                if (frame >= 0x80) {     // bmi: control byte ($F4)
                    // loc_845CC: run $34 (loc_60EA2 clears machine bits 1+3) + clr.b anim_frame.
                    parent.onContainerAnimComplete();
                    state = State.DORMANT;
                    currentFrame = IDLE_FRAME;
                    animStep = 0;
                    return;
                }
                currentFrame = frame;
                animTimer = ANIM_TABLE[animStep + 1];
            }
        }

        @Override
        public int getX() { return spawn.x(); }

        @Override
        public int getY() { return parent.getCurrentY() + offsetFromMachine; }

        @Override
        public int getPriorityBucket() {
            return RenderPriority.clamp(PRIORITY_BUCKET);
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            if (!shouldDebugRender(getPriorityBucket(), isHighPriority(), DEBUG_SOURCE_CONTAINER_GLASS)) return;
            PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.GUMBALL_BONUS);
            if (renderer == null) {
                return;
            }
            GraphicsManager graphicsManager = services().graphicsManager();
            if (graphicsManager != null) {
                graphicsManager.setCurrentSpriteSatDebugSource(String.format(
                        "%s frame=0x%02X slot=%d bucket=%d high=%s",
                        name, currentFrame, getSlotIndex(), getPriorityBucket(), isHighPriority()));
            }
            try {
                renderer.drawFrameIndex(currentFrame, spawn.x(), getY(), false, false);
            } finally {
                if (graphicsManager != null) {
                    graphicsManager.setCurrentSpriteSatDebugSource(null);
                }
            }
        }
    }

    /**
     * Exit trigger child — detects player in range and signals bonus stage completion.
     * <p>
     * ROM: Positioned at (0, +0x2A0) from parent. Detects player within
     * (-0x100/+0x200 X, -0x10/+0x40 Y) and calls requestExit() on the
     * bonus stage provider.
     * <p>
     * <b>CRITICAL:</b> This is how the bonus stage ends. Without it, the player
     * is stuck in the gumball stage permanently.
     */
    static class ExitTriggerChild extends AbstractObjectInstance implements SpawnRewindRecreatable {

        // ROM: Exit trigger detection range
        private static final int EXIT_X_MIN = -0x100;
        private static final int EXIT_X_MAX = 0x200;
        private static final int EXIT_Y_MIN = -0x10;
        private static final int EXIT_Y_MAX = 0x40;

        private boolean exitFired;

        ExitTriggerChild(ObjectSpawn spawn) {
            super(spawn, "GumballExitTrigger");
        }

        @Override
        public boolean isPersistent() {
            return true;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity playerEntity) {
            if (playerEntity == null || exitFired) {
                return;
            }

            int playerX = playerEntity.getCentreX();
            int playerY = playerEntity.getCentreY();
            int dx = playerX - spawn.x();
            int dy = playerY - spawn.y();

            if (dx >= EXIT_X_MIN && dx <= EXIT_X_MAX
                    && dy >= EXIT_Y_MIN && dy <= EXIT_Y_MAX) {
                exitFired = true;
                LOGGER.info("GumballExitTrigger: player in exit range, requesting bonus stage exit");
                try {
                    services().requestBonusStageExit();
                } catch (Exception e) {
                    LOGGER.warning("GumballExitTrigger: failed to request exit: " + e.getMessage());
                }
            }
        }

        @Override
        public int getPriorityBucket() {
            // Non-visible trigger; use low priority
            return RenderPriority.clamp(0);
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            // Invisible trigger — no rendering
        }
    }

    /**
     * Platform child — visual-only decoration.
     * <p>
     * ROM loc_61012 only does Refresh_ChildPosition + Draw_Sprite.
     * NO solid collision — the platforms are decorative, not standable.
     * <p>
     * Each of the 4 platform slots renders a DIFFERENT mapping frame from
     * RawAni_61388 [0, 1, 0, $16], set by sub_61362 based on the child's
     * subtype (spawn slot index in ChildObjDat_613F8).
     */
    static class PlatformChild extends AbstractObjectInstance implements RewindRecreatable {

        /** Y offset from the machine's savedY (machine-relative). */
        private int offsetFromMachine;

        /** Per-instance mapping frame from RawAni_61388 (ROM sub_61362). */
        private int mappingFrame;

        PlatformChild(ObjectSpawn spawn, String name, int offsetFromMachine, int mappingFrame) {
            super(spawn, name);
            this.offsetFromMachine = offsetFromMachine;
            this.mappingFrame = mappingFrame;
        }

        private PlatformChild() {
            this(new ObjectSpawn(0, 0, 0, 0, 0, false, 0),
                    "GumballPlatformRewind", 0, 0);
        }

        @Override
        public PlatformChild recreateForRewind(RewindRecreateContext ctx) {
            return new PlatformChild(ctx.spawn(), "GumballPlatformRewind", 0, 0);
        }

        @Override
        public boolean isPersistent() {
            return true;
        }

        @Override
        public boolean isHighPriority() {
            // ROM: ObjDat3_6138C uses make_art_tile(ArtTile_BonusStage,0,0) so the
            // object-wide art_tile priority bit is clear. Frame 0x16's mixed
            // layering comes from the mapping data itself, so keep the global
            // override LOW and let the per-piece priority bits drive compositing.
            return false;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity playerEntity) {
            // Static platform — no per-frame logic
        }

        @Override
        public int getPriorityBucket() {
            // ROM: ObjDat3_6138C priority $0200 → bucket 4 for all frames.
            return RenderPriority.clamp(BODY_PRIORITY_BUCKET);
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            String debugSourceKey = mappingFrame == 0x16 ? DEBUG_SOURCE_PLATFORM_EXTRA : DEBUG_SOURCE_PLATFORM;
            if (!shouldDebugRender(getPriorityBucket(), isHighPriority(), debugSourceKey)) return;
            PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.GUMBALL_BONUS);
            if (renderer == null) {
                return;
            }
            GraphicsManager graphicsManager = services().graphicsManager();
            GumballMachineObjectInstance machine =
                    GumballMachineObjectInstance.current(services().objectManager());
            int renderY = (machine != null)
                    ? machine.getCurrentY() + offsetFromMachine
                    : spawn.y();
            String debugSource = String.format("%s frame=0x%02X slot=%d bucket=%d high=%s",
                    name, mappingFrame, getSlotIndex(), getPriorityBucket(), isHighPriority());
            if (graphicsManager != null) {
                graphicsManager.setCurrentSpriteSatDebugSource(debugSource);
            }
            try {
                if (mappingFrame == 0x16) {
                    if (graphicsManager != null && graphicsManager.isSpriteSatCollectionActive()) {
                    // In the SAT/mask path, keep the original mapping-piece order intact.
                        renderer.drawFrameIndex(mappingFrame, spawn.x(), renderY, false, false, 0);
                        return;
                    }
                    // SonLVL and the ROM mapping both treat frame 0x16 as a true mixed-priority
                    // composition: LOW pieces form the interior pile/body layer, HIGH pieces form
                    // the front shell. Drawing the full frame as LOW duplicates the shell behind
                    // the FG body tiles, so split the frame strictly by the mapping bits.
                    renderer.drawFrameIndexFilteredByPriority(mappingFrame, spawn.x(), renderY,
                            false, false, 0, false);
                    renderer.drawFrameIndexFilteredByPriority(mappingFrame, spawn.x(), renderY,
                            false, false, 0, true);
                    return;
                }
                renderer.drawFrameIndex(mappingFrame, spawn.x(), renderY, false, false, 0);
            } finally {
                if (graphicsManager != null) {
                    graphicsManager.setCurrentSpriteSatDebugSource(null);
                }
            }
        }
    }

    /**
     * Glass dome shine overlay (sprite-mask shine effect) for the machine body.
     * <p>
     * ROM loc_610B6 / ObjDat3_613EC (mapping frame $17). Spawned by sub_61362 via
     * CreateChild6_Simple(ChildObjDat_6144A) when the extra platform child's
     * mapping frame is $16 (the body sprite). Follows the parent's position every
     * frame (ROM loc_610C6 copies x_pos/y_pos from parent3). Uses palette 0 /
     * art_tile 0 per the ObjDat3_613EC {@code make_art_tile($000, 0, 0)} definition and
     * sets the global Spritemask_flag on the VDP each frame.
     * <p>
     * ROM priority $0180 → bucket 3 (between apparatus at bucket 2 and piles at
     * bucket 4). VDP priority 0 (LOW) — renders behind high-priority FG tiles.
     */
    static class BodyOverlayChild extends AbstractObjectInstance implements RewindRecreatable {

        private static final int MAPPING_FRAME = 0x17;

        /** Y offset from the machine's current Y (matches extra platform offset). */
        private int offsetFromMachine;

        BodyOverlayChild(ObjectSpawn spawn, int offsetFromMachine) {
            super(spawn, "GumballBodyShine");
            this.offsetFromMachine = offsetFromMachine;
        }

        @Override
        public BodyOverlayChild recreateForRewind(RewindRecreateContext ctx) {
            return new BodyOverlayChild(ctx.spawn(), 0);
        }

        @Override
        public boolean isPersistent() {
            return true;
        }

        @Override
        public boolean isHighPriority() {
            // ROM: ObjDat3_613EC make_art_tile($000, 0, 0) — priority 0 (LOW).
            // Per-piece priority from mapping data handles mixed tiles.
            return false;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity playerEntity) {
            // ROM loc_610C6 just copies parent position; rendering reads the
            // machine's current Y live, so no per-frame state updates needed.
        }

        @Override
        public int getPriorityBucket() {
            // ROM: ObjDat3_613EC priority $0180 → bucket 3.
            return RenderPriority.clamp(3);
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            if (!shouldDebugRender(getPriorityBucket(), isHighPriority(), DEBUG_SOURCE_BODY_OVERLAY)) return;
            PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.GUMBALL_BONUS);
            if (renderer == null) {
                return;
            }
            GraphicsManager graphicsManager = services().graphicsManager();
            GumballMachineObjectInstance machine =
                    GumballMachineObjectInstance.current(services().objectManager());
            int renderY = (machine != null)
                    ? machine.getCurrentY() + offsetFromMachine
                    : spawn.y();
            // ROM ObjDat3_613EC uses make_art_tile($000, 0, 0) — palette 0.
            if (graphicsManager != null) {
                graphicsManager.requestSpriteMask();
                graphicsManager.setCurrentSpriteSatDebugSource(String.format(
                        "%s frame=0x%02X slot=%d bucket=%d high=%s",
                        name, MAPPING_FRAME, getSlotIndex(), getPriorityBucket(), isHighPriority()));
            }
            try {
                renderer.drawFrameIndex(MAPPING_FRAME, spawn.x(), renderY, false, false, 0);
            } finally {
                if (graphicsManager != null) {
                    graphicsManager.setCurrentSpriteSatDebugSource(null);
                }
            }
        }
    }

    /**
     * Gumball bonus stage crumbling spring.
     * <p>
     * ROM: loc_60DAC (dispenser's spring children). Uses SolidObjectFull2_1P
     * for solid-from-above collision. When player stands on it:
     * <ul>
     *   <li>Sets bit 5 on spring and plays bounce animation via sub_22F98</li>
     *   <li>Animation plays until prev_anim == 1 (end of bounce)</li>
     *   <li>Spring deletes self, sets bit 1 on parent (dispenser)</li>
     *   <li>Parent sees bit 1 → deletes itself (chained cleanup)</li>
     * </ul>
     * REP gumball (subtype 1, loc_61130) respawns the dispenser, which
     * respawns all 4 springs. In our implementation, the machine owns the
     * spring references and respawns them directly.
     * <p>
     * Solid params from ROM sub_22F98: halfWidth=$1B (27), airHalfHeight=8,
     * groundHalfHeight=$10 (16).
     */
    static class GumballSpringChild extends AbstractObjectInstance
            implements SolidObjectProvider, SolidObjectListener, RewindRecreatable {

        // ROM: Obj_Spring params — halfWidth=$1B, airHalfHeight=8, groundHalfHeight=$10
        private static final SolidObjectParams SOLID_PARAMS = new SolidObjectParams(27, 8, 16);

        // ROM: red spring uses strength -$1000
        private static final int BOUNCE_STRENGTH = -0x1000;

        // ROM: short bounce animation before crumbling.
        // Ani_Spring anim 1 is `dc.b 0, 1, 0, 0, 2, 2, 2, 2, 2, 2, $FD, 0` — 10 animation entries.
        private static final int CRUMBLE_DELAY_FRAMES = 10;

        // ROM: Map_Spring frames — frame 0 idle, frame 1 compressed (played on bounce)
        private static final int IDLE_FRAME = 0;
        private static final int COMPRESSED_FRAME = 1;
        private GumballMachineObjectInstance parent;
        private DispenserChild dispenser;
        private boolean triggered;
        private int crumbleTimer;
        private boolean signaledDispenser;
        private boolean falling;    // MoveChkDel state: falling with gravity
        private int fallY;          // current Y during fall
        private int fallYVel;       // Y velocity in subpixels during fall
        private static final int FALL_GRAVITY = 0x38; // ROM standard gravity

        GumballSpringChild(ObjectSpawn spawn, GumballMachineObjectInstance parent,
                           DispenserChild dispenser) {
            super(spawn, "GumballSpring");
            this.parent = parent;
            this.dispenser = dispenser;
        }

        private GumballSpringChild() {
            this(new ObjectSpawn(0, 0, 0, 0, 0, false, 0), null, null);
        }

        @Override
        public GumballSpringChild recreateForRewind(RewindRecreateContext ctx) {
            // Spring child of the gumball machine. Drop it if the machine was swept
            // before capture (no anchor to bind to). The dispenser link is optional —
            // the spring's update already null-checks it — so a missing dispenser
            // still yields a coherent spring. acceptDestroyed relinks to restored-but-
            // destroyed parts when those are the captured targets.
            //
            // Machine lookup is UNBOUNDED by design: this spring's own captured spawn
            // is a ROM-hardcoded absolute position (DISPENSER_ABSOLUTE_X/Y + a fixed
            // offset) with no relation to the machine's own spawn.x()/y() reference, so
            // no distance bound between the two is derivable without knowing this
            // specific ROM's ObjectSpawn placement value for the machine. Safe because
            // GumballMachineObjectInstance.current() already assumes exactly one live
            // machine per level (a bonus-stage singleton, like a boss) -- there is no
            // wrong-instance risk to bound against here.
            //
            // Dispenser lookup IS bounded (see SPRING_TO_DISPENSER_MAX_DISTANCE): both
            // sides are fixed ROM-hardcoded absolute constants, so the true distance is
            // exact and derivable regardless of the machine's placement.
            return RewindRecreateObjectLinks.nearestObjectUnbounded(ctx, GumballMachineObjectInstance.class, true)
                    .map(machine -> new GumballSpringChild(ctx.spawn(), machine,
                            RewindRecreateObjectLinks.nearestObject(
                                            ctx, DispenserChild.class, true, SPRING_TO_DISPENSER_MAX_DISTANCE)
                                    .orElse(null)))
                    .orElse(null);
        }

        @Override
        public boolean isPersistent() {
            return true;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity playerEntity) {
            // Falling state: apply gravity until off-screen, then destroy.
            if (falling) {
                fallYVel += FALL_GRAVITY;
                fallY += fallYVel >> 8;
                if (fallY > 0x500) {
                    setDestroyed(true);
                }
                return;
            }

            // ROM: btst #1, $38(a1) / bne.s loc_60E36 — if dispenser's spring bit
            // is set OR dispenser is destroyed, chain-crumble via MoveChkDel.
            if (dispenser != null && (dispenser.isSpringBitSet() || dispenser.isDestroyed())) {
                if (!signaledDispenser) {
                    dispenser.setSpringBit();
                    signaledDispenser = true;
                }
                startFalling();
                return;
            }

            if (triggered) {
                crumbleTimer--;
                if (crumbleTimer <= 0) {
                    if (!signaledDispenser && dispenser != null) {
                        dispenser.setSpringBit();
                        signaledDispenser = true;
                    }
                    startFalling();
                }
            }
        }

        /** ROM: move.l #MoveChkDel,(a0) — transition to gravity-fall + off-screen delete. */
        private void startFalling() {
            falling = true;
            fallY = spawn.y();
            fallYVel = 0;
        }

        @Override
        public SolidObjectParams getSolidParams() {
            return SOLID_PARAMS;
        }

        @Override
        public boolean isTopSolidOnly() {
            // ROM: SolidObjectFull2_1P — solid from above only
            return true;
        }

        @Override
        public boolean usesPlatformObjectLandingSnap() {
            // ROM loc_60DAC calls SolidObjectFull2_1P (sonic3k.asm:127528), whose
            // "d6 clear" fresh-contact path falls through to the shared
            // SolidObject_cont -> loc_1E154 top-landing branch (sonic3k.asm:41070-
            // 41072, 41399, 41611-41637): `subq.w #1,y_pos(a1) / sub.w d3,y_pos(a1)`
            // -- the same relative playerY-distY placement resolveContactInternal
            // already produces. It is NOT PlatformObject_ChkYRange's absolute
            // anchorY-groundHalfHeight-yRadius-1 snap, so that override must be
            // skipped here or it overwrites the correct landing Y before
            // sub_22F98's addq.w #8,y_pos(a1) bounce-compression nudge is applied,
            // landing the player 8px too high (S3K gumball bonus trace f895).
            return false;
        }

        @Override
        public boolean isSolidFor(PlayableEntity playerEntity) {
            return !triggered && !falling;
        }

        @Override
        public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
            if (triggered || falling || !contact.standing()) {
                return;
            }
            if (!(playerEntity instanceof AbstractPlayableSprite player)) {
                return;
            }

            // ROM sub_22F98 (sonic3k.asm lines 47714-47766) — full red vertical spring bounce.

            // addq.w #8, y_pos(a1) — nudge player DOWN 8 pixels (start of compression)
            player.setY((short) (player.getY() + 8));

            // move.w $30(a0), y_vel(a1) — upward velocity (-$1000 for red spring)
            player.setYSpeed((short) BOUNCE_STRENGTH);
            // ROM sub_22F98 does not clear ground_vel for plain vertical red spring
            // (subtype 0) — ground_vel is only touched for flip-related subtypes.

            // bset #1, status(a1) — Status_InAir
            player.setAir(true);

            // bclr #3, status(a1) — clear Status_OnObj
            player.setOnObject(false);

            // clr.b jumping(a1)
            player.setJumping(false);

            // clr.b spin_dash_flag(a1)
            player.setSpindash(false);

            // move.b #$10, anim(a1) — player SPRING animation
            player.setAnimationId(Sonic3kAnimationIds.SPRING);

            // ROM sub_22F98 does not apply move_lock — removed for ROM parity.

            // ROM: play sfx_Spring (0xB1)
            try {
                services().playSfx(Sonic3kSfx.SPRING.id);
            } catch (Exception e) {
                // Ignore
            }

            // ROM: bset #5,$38(a0) is ONLY done for Player_1 (line 127526).
            // Player_2 (sidekick) bounces but does NOT trigger crumble.
            // isCpuControlled() identifies the AI-driven sidekick.
            if (!player.isCpuControlled()) {
                triggered = true;
                crumbleTimer = CRUMBLE_DELAY_FRAMES;
                LOGGER.fine("GumballSpring: triggered by P1, will crumble in "
                        + CRUMBLE_DELAY_FRAMES + " frames");
            } else {
                LOGGER.fine("GumballSpring: sidekick bounced (no crumble)");
            }
        }

        @Override
        public int getPriorityBucket() {
            return RenderPriority.clamp(PRIORITY_BUCKET);
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            if (!shouldDebugRender(getPriorityBucket(), false, DEBUG_SOURCE_SPRING)) {
                return;
            }
            PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.GUMBALL_SPRING);
            if (renderer == null) {
                return;
            }
            int frame = triggered ? COMPRESSED_FRAME : IDLE_FRAME;
            int renderY = falling ? fallY : spawn.y();
            renderer.drawFrameIndex(frame, spawn.x(), renderY, false, false, 0);
        }
    }
}
