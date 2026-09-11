package com.openggf.game.sonic1.objects;
import com.openggf.game.PlayableEntity;

import com.openggf.debug.DebugRenderContext;
import com.openggf.game.sonic1.constants.Sonic1ObjectIds;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectLifetimeOps;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.objects.TouchActorContextPolicy;
import com.openggf.level.objects.TouchAttackBouncePolicy;
import com.openggf.level.objects.TouchCategoryDecodeMode;
import com.openggf.level.objects.TouchOverlapStopPolicy;
import com.openggf.level.objects.TouchResponseProfile;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.objects.TouchShieldDeflectCapability;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.level.LevelManager;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * Object 0x17 — Spiked Pole Helix (GHZ).
 * <p>
 * A horizontal row of spike balls rotating around a pole. Each spike cycles through
 * 8 animation frames driven by the global sync counter {@code v_ani0_frame}. Adjacent
 * spikes are phase-offset by 1, creating a helical rotation effect.
 * <p>
 * Only frame 0 (spike pointing straight up) is harmful ({@code obColType = $84}).
 * All other frames are harmless. Since each spike has a different phase offset, the
 * "harmful" position travels along the helix.
 * <p>
 * <b>Subtype:</b> Total number of spikes in the helix (default 16 = 0x10).
 * <p>
 * <b>Disassembly reference:</b> docs/s1disasm/_incObj/17 Spiked Pole Helix.asm
 */
public class Sonic1SpikedPoleHelixObjectInstance extends AbstractObjectInstance
        implements TouchResponseProvider, SpawnRewindRecreatable {

    // ---- Constants from disassembly ----

    // Spacing between spikes: addi.w #$10,d3
    private static final int SPIKE_SPACING = 0x10;

    // Display priority: move.b #3,obPriority(a0)
    private static final int DISPLAY_PRIORITY = 3;

    // Collision type when harmful (frame 0 only): move.b #$84,obColType(a0)
    // HURT ($80) + size index 4
    private static final int COLLISION_TYPE_HARMFUL = 0x84;

    private static final TouchResponseProfile SINGLE_REGION_HURT_PROFILE = hurtProfile(
            false, TouchOverlapStopPolicy.STOP_AFTER_FIRST_OVERLAP_FOR_ALL_ACTORS);

    // v_ani0_frame timer period: v_ani0_time resets to $0B (12 frames per tick)
    private static final int ANIM_FRAME_DURATION = 12;

    // Number of animation frames in the rotation cycle: andi.b #7,d0
    private static final int FRAME_COUNT = 8;

    // ---- Spike data ----

    // Per-spike state: positions and phase offsets
    private int spikeCount;       // Total number of spikes (including parent at center)
    private final int[] spikeX;         // X position of each spike
    private int spikeY;           // Y position (all share the same Y)
    private final int[] spikePhase;     // hel_frame per spike (0-7)
    private final int[] spikeFrame;     // Current display frame per spike (computed each update)
    private final boolean[] spikeHarmful; // Whether each spike is harmful this frame

    // Parent spike index (the one at the original spawn X position)
    private int parentIndex;

    // ROM Hel_Main .loopBuildHelix allocates ONE OST slot per non-parent spike
    // via FindFreeObj (REV01 FixBugs=0; docs/s1disasm/_incObj/17 GHZ Spiked Pole
    // Helix.asm:46-49,56-63,84-90), storing each child's 0-based slot index in
    // the parent's helix_children array. Each child runs routine 8
    // (Hel_ChildSpike) which only rotates its own frame and displays; deletion
    // is handled en masse by the parent's Hel_ChkDel .deleteHelix loop (:126-141).
    // Modelling the spikes as ONE instance with an internal array left the SST
    // 30 slots emptier than ROM in GHZ3, so RLoss_Count's FindFreeObj loop found
    // 31 free slots instead of 19 and scattered 12 extra rings on a hit
    // (ghz3_2 row 3,561 -> three of them re-collected -> 59 rings at
    // GotThroughAct against ROM's 56).
    private HelixSpikeChild[] spikeChildren;
    private boolean spikeChildrenSpawned;

    // v_ani0_frame is ROM's GLOBAL sync counter 0, ticked by SynchroAnimate every 12 gfc ticks.
    // It is NOT a per-object counter. ROM initialises v_ani0_time=0 and v_ani0_frame=0 at level
    // start (clearRAM v_timingandscreenvariables, sonic.asm:2725).
    //
    // ROM Level_MainLoop order (sonic.asm:2980-3010):
    //   addq.w #1,(v_framecount).w   ; gfc increments at top of loop (line 2984)
    //   ExecuteObjects               ; objects run here; read v_ani0_frame (line 2988)
    //   SynchroAnimate               ; updates v_ani0_frame AFTER objects (line 3010)
    //
    // So at loop iteration gfc=N, objects read v_ani0_frame = value after (N-1) SynchroAnimate
    // calls. SynchroAnimate (sonic.asm:3115-3119): subq.b #1 → bpl to skip (fires when result
    // goes negative, i.e., time=0→0xFF); reloads to 11; decrements v_ani0_frame mod 8.
    // Fires at calls 1, 13, 25, ..., giving ceil((N-1)/12) = (N-1+11)/12 = (N+10)/12 ticks.
    //   v_ani0_frame(N) = (-((N+10)/12)) & 7   (integer division)
    //
    // A per-object counter unseeded from the trace will diverge whenever the helix streams
    // in mid-level (its animCounter starts at 0 regardless of the actual gfc). Fix: compute
    // v_ani0_frame directly from levelManager.getFrameCounter()+1 (= current gfc) each frame,
    // matching the Electrocuter fix pattern (SBZ1 f1925, commit in CHANGELOG.md).
    //
    // animCounter holds the per-frame derived value; no longer needs per-object timer state.
    private int animCounter = 0;

    private static TouchResponseProfile hurtProfile(boolean multiRegionSource,
            TouchOverlapStopPolicy stopPolicy) {
        return new TouchResponseProfile(
                TouchCategoryDecodeMode.NORMAL,
                false,
                true,
                multiRegionSource,
                TouchShieldDeflectCapability.NONE,
                0,
                TouchAttackBouncePolicy.STANDARD_ENEMY_KILL,
                TouchActorContextPolicy.MAIN_FULL_SIDEKICK_HURT_ONLY,
                stopPolicy);
    }

    public Sonic1SpikedPoleHelixObjectInstance(ObjectSpawn spawn) {
        super(spawn, "SpikedPoleHelix");

        int subtype = spawn.subtype() & 0xFF;
        if (subtype == 0) {
            subtype = 0x10; // Default from SonLVL
        }
        this.spikeCount = subtype;
        this.spikeY = spawn.y();

        spikeX = new int[spikeCount];
        spikePhase = new int[spikeCount];
        spikeFrame = new int[spikeCount];
        spikeHarmful = new boolean[spikeCount];

        // Calculate leftmost X position:
        // d3 = obX(a0) - (helix_length / 2) * 16
        // Disasm: move.w d1,d0 / lsr.w #1,d0 / lsl.w #4,d0 / sub.w d0,d3
        int centerX = spawn.x();
        int halfOffset = (spikeCount / 2) * SPIKE_SPACING;
        int leftX = centerX - halfOffset;

        // Build spike positions and phase offsets.
        // The disasm creates children from leftmost to rightmost, skipping the center
        // position (which is the parent). Phase counter d6 increments sequentially.
        //
        // From Hel_Build:
        //   d6 starts at 0, increments by 1 per spike (AND #7 to wrap)
        //   When d3 == obX(a0), the parent gets the current d6 value,
        //   d6 increments again, and d3 advances past the center.
        int d6 = 0;
        int parentIdx = -1;
        int spikeIdx = 0;

        for (int i = 0; i < spikeCount; i++) {
            int x = leftX + i * SPIKE_SPACING;

            if (x == centerX && parentIdx == -1) {
                // This is the parent spike position
                parentIdx = spikeIdx;
                spikeX[spikeIdx] = x;
                spikePhase[spikeIdx] = d6 & 0x07;
                d6++;
                spikeIdx++;
            } else {
                // Child spike
                spikeX[spikeIdx] = x;
                spikePhase[spikeIdx] = d6 & 0x07;
                d6++;
                spikeIdx++;
            }
        }

        // If centerX wasn't exactly hit (e.g., odd count), the parent is at the center-most position
        if (parentIdx == -1) {
            parentIdx = spikeCount / 2;
        }
        this.parentIndex = parentIdx;

        // Compute initial frames
        updateSpikeFrames();
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (isDestroyed()) {
            return;
        }

        // Derive v_ani0_frame from the trace-seeded level frame counter (gfc).
        // ROM ExecuteObjects runs before SynchroAnimate (sonic.asm:2988 vs 3010), so objects
        // at loop iteration gfc=N read v_ani0_frame from after (N-1) SynchroAnimate calls:
        //   animCounter = (-((gfc + ANIM_FRAME_DURATION - 2) / ANIM_FRAME_DURATION)) & 7
        //               = (-((N+10) / 12)) & 7   (integer division)
        //
        // levelManager.getFrameCounter()+1 = current gfc (pre-increment, same as Electrocuter).
        LevelManager levelManager = services().levelManager();
        if (levelManager != null) {
            int gfc = levelManager.getFrameCounter();
            animCounter = (-(( gfc + ANIM_FRAME_DURATION - 2) / ANIM_FRAME_DURATION)) & 0x07;
        }

        updateSpikeFrames();
        // ROM Hel_Main is routine 0: it builds the children on the object's FIRST
        // execution, then advances to Hel_ParentSpike
        // (docs/s1disasm/_incObj/17 GHZ Spiked Pole Helix.asm:22-23,42-90).
        spawnSpikeChildren();
    }

    /**
     * Allocate one render-and-hurt OST-slot child per non-parent spike, matching
     * ROM {@code Hel_Main .loopBuildHelix} (FindFreeObj per spike, REV01
     * FixBugs=0, docs/s1disasm/_incObj/17 GHZ Spiked Pole Helix.asm:46-90).
     * The loop bails the moment object RAM is full ({@code bne.s
     * Hel_ParentSpike}, :50), so a helix that loads into a crowded SST really
     * does carry fewer spikes than its subtype asks for.
     */
    private void spawnSpikeChildren() {
        if (spikeChildrenSpawned) {
            return;
        }
        spikeChildrenSpawned = true;
        spikeChildren = new HelixSpikeChild[spikeCount];
        for (int i = 0; i < spikeCount; i++) {
            if (i == parentIndex) {
                continue;
            }
            final int idx = i;
            HelixSpikeChild child = spawnFreeChild(() -> new HelixSpikeChild(
                    spikeX[idx], spikeY, spikePhase[idx], spawn.x()));
            if (child == null || child.isDestroyed()) {
                // ROM: FindFreeObj returned "object RAM full" -> branch out of the
                // build loop with only the spikes allocated so far.
                break;
            }
            spikeChildren[i] = child;
        }
    }

    /**
     * Compute display frame and harmfulness for each spike.
     * <p>
     * From Hel_RotateSpikes:
     * <pre>
     *   move.b (v_ani0_frame).w,d0
     *   move.b #0,obColType(a0)        ; harmless by default
     *   add.b  hel_frame(a0),d0        ; add per-spike phase
     *   andi.b #7,d0                   ; wrap to 0-7
     *   move.b d0,obFrame(a0)          ; set display frame
     *   bne.s  locret_7DA6             ; if not frame 0, stay harmless
     *   move.b #$84,obColType(a0)      ; frame 0 = harmful
     * </pre>
     */
    private void updateSpikeFrames() {
        for (int i = 0; i < spikeCount; i++) {
            int frame = (animCounter + spikePhase[i]) & 0x07;
            spikeFrame[i] = frame;
            spikeHarmful[i] = (frame == 0);
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(ObjectArtKeys.SPIKED_POLE_HELIX);
        if (renderer == null) return;

        // ROM Hel_ParentSpike displays only the parent's own spike; every other
        // spike is a separate OST object that displays itself from
        // Hel_ChildSpike (docs/s1disasm/_incObj/17 GHZ Spiked Pole
        // Helix.asm:93-99,159-162).
        // Frame 6 is the invisible hack (empty mapping) — renderer handles 0-piece frames
        renderer.drawFrameIndex(spikeFrame[parentIndex], spikeX[parentIndex], spikeY,
                false, false);
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(DISPLAY_PRIORITY);
    }

    // ---- TouchResponseProvider ----
    // The parent spike reports its own collision state. The engine's touch response system
    // processes each provider once per frame. For multi-spike collision, each spike that
    // is harmful needs to participate. Since we render all spikes from one object instance,
    // we override getMultiTouchRegions() to report all harmful spike positions.

    @Override
    public TouchResponseProfile getTouchResponseProfile() {
        return SINGLE_REGION_HURT_PROFILE;
    }

    @Override
    public TouchResponseProfile getTouchResponseProfile(boolean multiRegionSource) {
        return SINGLE_REGION_HURT_PROFILE;
    }

    @Override
    public int getCollisionFlags() {
        // The parent spike's collision state
        return spikeHarmful[parentIndex] ? COLLISION_TYPE_HARMFUL : 0;
    }

    @Override
    public int getCollisionProperty() {
        return 0;
    }

    // ---- Persistence ----

    @Override
    public boolean isPersistent() {
        // Disasm: Hel_ChkDel: out_of_range.w Hel_DelAll
        // Uses the parent's (spawn) X position for range check
        return !isDestroyed() && isBaseXOnScreen();
    }

    private boolean isBaseXOnScreen() {
        return isInRangeAt(spawn.x());
    }

    @Override
    public void onUnload() {
        // ROM Hel_ChkDel -> .deleteHelix walks the parent's helix_children array
        // and DeleteChild's every stored child slot on the same frame the parent
        // leaves range, then deletes itself
        // (docs/s1disasm/_incObj/17 GHZ Spiked Pole Helix.asm:120-145). The
        // children run routine 8 (Hel_ChildSpike) which has no out_of_range check
        // of its own, so the parent owns their teardown; freeing the slots here
        // keeps downstream FindFreeObj allocation aligned with ROM. Mirrors
        // Sonic1SwingingPlatformObjectInstance.onUnload.
        ObjectManager objectManager = tryServices() != null
                ? tryServices().objectManager() : null;
        if (spikeChildren == null) {
            return;
        }
        for (HelixSpikeChild child : spikeChildren) {
            if (child != null) {
                ObjectLifetimeOps.expireDynamic(child);
                if (objectManager != null) {
                    objectManager.removeDynamicObject(child);
                }
            }
        }
    }

    // ---- Debug rendering ----

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        for (int i = 0; i < spikeCount; i++) {
            float r = spikeHarmful[i] ? 1.0f : 0.0f;
            float g = spikeHarmful[i] ? 0.0f : 1.0f;
            // Draw small cross at each spike position
            ctx.drawLine(spikeX[i] - 4, spikeY, spikeX[i] + 4, spikeY, r, g, 0.0f);
            ctx.drawLine(spikeX[i], spikeY - 4, spikeX[i], spikeY + 4, r, g, 0.0f);
        }
    }

    /**
     * One non-parent spike of the helix (ROM routine 8, {@code Hel_ChildSpike}).
     * <p>
     * ROM {@code Hel_Main} allocates a real OST slot per spike via
     * {@code FindFreeObj} and sets routine 8; the child then only calls
     * {@code Hel_RotateSpikes} (which rewrites its own frame and sets
     * {@code col_8x32|col_hurt} while the frame is 0) and {@code DisplaySprite}
     * (docs/s1disasm/_incObj/17 GHZ Spiked Pole Helix.asm:56-90,101-116,159-162).
     * It carries no {@code out_of_range} check of its own — the parent's
     * {@code Hel_ChkDel .deleteHelix} removes the whole assembly at once.
     */
    public static final class HelixSpikeChild extends AbstractObjectInstance
            implements TouchResponseProvider, SpawnRewindRecreatable {
        private int posX;
        private int posY;
        private int phase;
        private int frame;
        private boolean harmful;
        // ROM Hel_ChkDel feeds the PARENT's obX to out_of_range and deletes the
        // whole helix at once; a child never tests its own position. Keying the
        // child's range reference on the parent pivot keeps every spike unloading
        // on the parent's frame. Un-finaled so the generic rewind field capturer
        // can reapply it after a recreate.
        private int pivotBaseX;

        HelixSpikeChild(int x, int y, int phase, int pivotBaseX) {
            super(new ObjectSpawn(x, y, Sonic1ObjectIds.SPIKED_POLE_HELIX, 0, 0, false, 0),
                    "HelixSpike");
            this.posX = x;
            this.posY = y;
            this.phase = phase & 0x07;
            this.pivotBaseX = pivotBaseX;
        }

        HelixSpikeChild(ObjectSpawn spawn) {
            this(spawn.x(), spawn.y(), 0, spawn.x());
        }

        @Override
        public int getX() {
            return posX;
        }

        @Override
        public int getY() {
            return posY;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity player) {
            if (isDestroyed()) {
                return;
            }
            // Hel_RotateSpikes, run from the child's own SST slot.
            LevelManager levelManager = services().levelManager();
            int animFrame = 0;
            if (levelManager != null) {
                int gfc = levelManager.getFrameCounter();
                animFrame = (-((gfc + ANIM_FRAME_DURATION - 2) / ANIM_FRAME_DURATION)) & 0x07;
            }
            frame = (animFrame + phase) & 0x07;
            harmful = frame == 0;
        }

        @Override
        public boolean isPersistent() {
            // Routine 8 has no out_of_range check; the parent's Hel_ChkDel
            // .deleteHelix owns the teardown (see the parent's onUnload).
            return !isDestroyed();
        }

        @Override
        public int getOutOfRangeReferenceX() {
            return pivotBaseX;
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            PatternSpriteRenderer renderer = getRenderer(ObjectArtKeys.SPIKED_POLE_HELIX);
            if (renderer == null) {
                return;
            }
            renderer.drawFrameIndex(frame, posX, posY, false, false);
        }

        @Override
        public int getPriorityBucket() {
            return RenderPriority.clamp(DISPLAY_PRIORITY);
        }

        @Override
        public TouchResponseProfile getTouchResponseProfile() {
            return SINGLE_REGION_HURT_PROFILE;
        }

        @Override
        public TouchResponseProfile getTouchResponseProfile(boolean multiRegionSource) {
            return SINGLE_REGION_HURT_PROFILE;
        }

        @Override
        public int getCollisionFlags() {
            return harmful ? COLLISION_TYPE_HARMFUL : 0;
        }

        @Override
        public int getCollisionProperty() {
            return 0;
        }
    }
}
