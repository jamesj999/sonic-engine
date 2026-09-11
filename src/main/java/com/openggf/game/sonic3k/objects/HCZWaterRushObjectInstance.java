package com.openggf.game.sonic3k.objects;

import com.openggf.debug.DebugRenderContext;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kLevelTriggerManager;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreateObjectLinks;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.RomObjectCodePointerProvider;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.render.PatternSpriteRenderer;

import java.util.List;

/**
 * Object 0x37 — HCZ Water Rush (Sonic 3 &amp; Knuckles, Hydrocity Zone).
 * <p>
 * The water current that sweeps Sonic through a tunnel in HCZ Act 1.
 * Consists of a visible water animation (this object) plus a solid block child
 * ({@link WaterRushBlockChild}) that rises when triggered.
 * <p>
 * <strong>Phase 1 (waiting):</strong> Checks {@code Level_trigger_array[0]}.
 * When trigger fires, transitions to Phase 2 and clears the breakable bar state.
 * <p>
 * <strong>Phase 2 (rushing):</strong> Animates water frames, advances x_pos by
 * 0x20 each animation cycle. Special corner transition at (0x580, 0x5A0) shifts
 * both x and y by -0x20. Destroys self when x_pos &gt;= 0x980.
 * <p>
 * ROM references: Obj_HCZWaterRush (sonic3k.asm:64743-64833).
 */
@com.openggf.game.rewind.RewindRecreateOnRestore(
        reason = "Constructor writes global zone state (HCZBreakableBarState.setState(3)) "
                + "in addition to spawning a child; only the child spawn is observable by "
                + "the restore-time construction side-effect latch.")
public class HCZWaterRushObjectInstance extends AbstractObjectInstance implements RewindRecreatable {

    // ===== Phase constants =====
    private static final int PHASE_WAITING = 0;
    private static final int PHASE_RUSHING = 1;

    // ROM: move.w #$80,priority(a0) → bucket 1
    private static final int PRIORITY = 1;

    // ===== Instance state =====
    private int x;
    private int y;
    private int phase;
    private int mappingFrame;
    private int animFrameTimer;

    public HCZWaterRushObjectInstance(ObjectSpawn spawn) {
        this(spawn, true);
    }

    private HCZWaterRushObjectInstance(ObjectSpawn spawn, boolean spawnBlock) {
        super(spawn, "HCZWaterRush");
        this.x = spawn.x();
        this.y = spawn.y();
        this.mappingFrame = 2;  // ROM: move.b #2,mapping_frame(a0)
        this.phase = PHASE_WAITING;

        // ROM: AllocateObjectAfterCurrent — spawn solid block child
        if (spawnBlock) {
            int childX = x - 0x30;
            int childY = y;
            spawnChild(() -> new WaterRushBlockChild(
                    new ObjectSpawn(childX, childY, Sonic3kObjectIds.HCZ_WATER_RUSH, 0, 0, false, 0),
                    this));
        }

        // ROM: move.b #3,(_unkF7C7).w
        HCZBreakableBarState.setState(3);
    }

    @Override
    public HCZWaterRushObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new HCZWaterRushObjectInstance(ctx.spawn(), false);
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        switch (phase) {
            case PHASE_WAITING -> updateWaiting();
            case PHASE_RUSHING -> updateRushing();
        }
    }

    // ROM: loc_2FE34
    private void updateWaiting() {
        // ROM: tst.b (Level_trigger_array).w
        if (!Sonic3kLevelTriggerManager.testAny(0)) {
            return;
        }
        // Trigger fired — transition to rushing phase
        mappingFrame = 3;
        animFrameTimer = 1;
        phase = PHASE_RUSHING;

        // ROM: move.b #0,(_unkF7C7).w
        HCZBreakableBarState.setState(0);

        // ROM: move.b #1,(Palette_cycle_counters+$00).w
        // Gates palette cycling effects elsewhere
        HCZWaterRushPaletteCycleGate.setActive(true);
    }

    // ROM: loc_2FE5E
    private void updateRushing() {
        animFrameTimer--;
        if (animFrameTimer < 0) {
            // ROM: move.b #1,anim_frame_timer(a0)
            animFrameTimer = 1;
            // ROM: addq.b #1,mapping_frame(a0)
            mappingFrame++;

            // ROM: cmpi.b #2,mapping_frame(a0)
            if (mappingFrame == 2) {
                // ROM: addi.w #$20,x_pos(a0)
                x += 0x20;

                // ROM: Special case corner transition
                if (x == 0x580 && y == 0x5A0) {
                    x -= 0x20;
                    y -= 0x20;
                }
            }

            // ROM: andi.b #1,mapping_frame(a0)
            mappingFrame &= 1;
        }

        // ROM: cmpi.w #$980,x_pos(a0) / blo.s loc_2FEAC
        if (x >= 0x980) {
            // ROM: move.w #$7F00,x_pos(a0) — move off screen (effectively destroy)
            x = 0x7F00;
        }
    }

    // ===== Rendering =====

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer r = getRenderer(Sonic3kObjectArtKeys.HCZ_WATER_RUSH);
        if (r == null) return;
        r.drawFrameIndex(mappingFrame, x, y, false, false);
    }

    @Override
    public int getX() { return x; }

    @Override
    public int getY() { return y; }

    @Override
    public int getPriorityBucket() { return RenderPriority.clamp(PRIORITY); }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        if (ctx == null) return;
        ctx.drawCross(x, y, 4, 0f, 0.7f, 1f);
    }

    // ===== Child: solid block (ROM loc_2FEB2 / loc_2FEBE) =====

    /**
     * Solid block child spawned below/left of the water rush parent.
     * <p>
     * <strong>Phase 1:</strong> Waits for trigger. Provides SolidObjectFull collision.
     * <p>
     * <strong>Phase 2:</strong> Moves up by 0x10 per frame until y_pos reaches 0x560,
     * then destroys self by moving off screen.
     * <p>
     * ROM references: loc_2FEB2 / loc_2FEBE (sonic3k.asm:64811-64832).
     */
    static class WaterRushBlockChild extends AbstractObjectInstance
            implements SolidObjectProvider, SolidObjectListener, RewindRecreatable, RomObjectCodePointerProvider {

        /**
         * Word 0 of this object's S3K SST holds its live ROM code pointer.
         * ROM {@code Obj_HCZWaterRush} is installed from the S3K object pointer table at
         * {@code $0002FDA4} (table read from the user-supplied ROM; the
         * label is defined at docs/skdisasm/sonic3k.asm:64748).
         * Its whole code block lies in one bank, so the HIGH word that
         * {@code sub_13EFC} latches into {@code Tails_CPU_interact} and compares
         * on the next off-screen on-object frame is {@code $0002}
         * (docs/skdisasm/sonic3k.asm:26816-26843).
         */
        @Override
        public int romObjectCodePointerHighWord() {
            return 0x0002;
        }


        private static final int PHASE_WAITING = 0;
        private static final int PHASE_RISING = 1;

        // ROM: move.b #$10,width_pixels(a1) / move.b #$20,height_pixels(a1)
        private static final int HALF_WIDTH = 0x10;
        private static final int HALF_HEIGHT = 0x20;

        // ROM: move.w #$280,priority(a1) → bucket 5
        private static final int PRIORITY = 5;

        private int x;
        private int y;
        private int phase;
        private final HCZWaterRushObjectInstance parent;

        WaterRushBlockChild(ObjectSpawn spawn, HCZWaterRushObjectInstance parent) {
            super(spawn, "HCZWaterRushBlock");
            this.x = spawn.x();
            this.y = spawn.y();
            this.parent = parent;
            this.phase = PHASE_WAITING;
        }

        @Override
        public WaterRushBlockChild recreateForRewind(RewindRecreateContext ctx) {
            HCZWaterRushObjectInstance restoredParent =
                    RewindRecreateObjectLinks.nearestLiveObject(ctx, HCZWaterRushObjectInstance.class);
            return new WaterRushBlockChild(ctx.spawn(), restoredParent);
        }

        @Override
        public SolidObjectParams getSolidParams() {
            // ROM: move.b width_pixels(a0),d1 / addi.w #$B,d1
            //      move.b height_pixels(a0),d2 / move.w d2,d3 / addq.w #1,d3
            int d1 = HALF_WIDTH + 0xB;
            int d2 = HALF_HEIGHT;
            int d3 = HALF_HEIGHT + 1;
            return SolidObjectParams.of(d1, d2, d3);
        }

        @Override
        public void onSolidContact(PlayableEntity player, SolidContact contact, int fc) {
            // No special behavior
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity playerEntity) {
            switch (phase) {
                case PHASE_WAITING -> updateWaiting();
                case PHASE_RISING -> updateRising();
            }
        }

        // ROM: loc_2FEB2
        private void updateWaiting() {
            if (!Sonic3kLevelTriggerManager.testAny(0)) {
                return;
            }
            phase = PHASE_RISING;
            updateRising();
        }

        // ROM: loc_2FEBE
        private void updateRising() {
            // ROM: subi.w #$10,y_pos(a0)
            y -= 0x10;
            // ROM: cmpi.w #$560,y_pos(a0) / bne.s loc_2FED2
            if (y == 0x560) {
                // ROM: move.w #$7F00,x_pos(a0) — destroy
                x = 0x7F00;
            }
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            PatternSpriteRenderer r = getRenderer(Sonic3kObjectArtKeys.HCZ_WATER_RUSH_BLOCK);
            if (r == null) return;
            // ROM: move.b #1,mapping_frame(a1)
            r.drawFrameIndex(1, x, y, false, false);
        }

        @Override
        public int getX() { return x; }

        @Override
        public int getY() { return y; }

        @Override
        public int getPriorityBucket() { return RenderPriority.clamp(PRIORITY); }

        @Override
        public void appendDebugRenderCommands(DebugRenderContext ctx) {
            if (ctx == null) return;
            ctx.drawRect(x, y, HALF_WIDTH, HALF_HEIGHT, 0f, 1f, 0.5f);
        }
    }

    // ===== Cross-object communication: breakable bar state =====

    /**
     * Static holder for the _unkF7C7 byte that controls HCZBreakableBar behavior.
     * Setting to 3 means both bits 0 and 1 are set (both player slots marked).
     * Clearing to 0 releases them.
     * <p>
     * ROM: (_unkF7C7).w at $FFFFF7C7.
     */
    public static final class HCZBreakableBarState {
        private static int state;

        private HCZBreakableBarState() {}

        public static void setState(int value) { state = value & 0xFF; }
        public static int getState() { return state; }
        /** ROM: bset d2,(_unkF7C7).w — set individual player bit */
        public static void setBit(int bit) { state |= (1 << bit); }
        /** ROM: bclr d2,(_unkF7C7).w — clear individual player bit */
        public static void clearBit(int bit) { state &= ~(1 << bit); }
        /** ROM: btst d5,(_unkF7C7).w — test individual player bit */
        public static boolean testBit(int bit) { return (state & (1 << bit)) != 0; }
        public static void reset() {
            state = 0;
        }

        /** Immutable rewind snapshot of the HCZ cross-object latch state. */
        public record Snapshot(int state) {
        }

        public static Snapshot snapshot() {
            return new Snapshot(state);
        }

        public static void restore(Snapshot snapshot) {
            state = snapshot.state();
        }
    }

    // ===== Cross-object communication: palette cycle gate =====

    /**
     * Static flag gating HCZ water palette cycling.
     * <p>
     * ROM: (Palette_cycle_counters+$00).w — when non-zero, the HCZ palette cycler
     * should be active (or its behavior modified).
     */
    public static final class HCZWaterRushPaletteCycleGate {
        private static boolean active;

        private HCZWaterRushPaletteCycleGate() {}

        public static void setActive(boolean value) { active = value; }
        public static boolean isActive() { return active; }
        public static void reset() { active = false; }

        /** Immutable rewind snapshot of the water-rush palette-cycle gate. */
        public record Snapshot(boolean active) {
        }

        public static Snapshot snapshot() {
            return new Snapshot(active);
        }

        public static void restore(Snapshot snapshot) {
            active = snapshot.active();
        }
    }
}
