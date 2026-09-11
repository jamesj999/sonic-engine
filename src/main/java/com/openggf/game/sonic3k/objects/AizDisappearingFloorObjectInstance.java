package com.openggf.game.sonic3k.objects;

import com.openggf.debug.DebugRenderContext;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
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
 * Object 0x29 — AIZ Disappearing Floor (Sonic 3 &amp; Knuckles).
 * <p>
 * ROM-faithful implementation. The parent is visual-only (no collision). It
 * animates a floor overlay that periodically appears and disappears. When the
 * parent reaches mapping frame 5, a solid child object is spawned that provides
 * collision ({@code SolidObjectFull}) and renders the water border visual
 * (Map_AIZDisappearingFloor2). The child moves off-screen when the parent's
 * mapping frame returns to 3 during the reappear sequence.
 * <p>
 * ROM references: Obj_AIZDisappearingFloor (sonic3k.asm:58320).
 */
public class AizDisappearingFloorObjectInstance extends AbstractObjectInstance
        implements RewindRecreatable {

    // ===== Period lookup table (word_2A232) =====
    private static final int[] PERIOD_TABLE = {
            0x0001, 0x0003, 0x0007, 0x000F,
            0x001F, 0x003F, 0x007F, 0x00FF,
            0x01FF, 0x03FF, 0x07FF, 0x0FFF,
            0x1FFF, 0x3FFF, 0x7FFF, 0xFFFF
    };

    // ===== Animation data (Ani_AIZDisappearingFloor) =====
    // Pairs of (mapping_frame, delay).
    // Anim 1 flows into anim 2 in ROM memory; merged here.
    private static final int[][] ANIM_IDLE = {{0, 0x1F}};
    private static final int[][] ANIM_CYCLE = {
            {1, 1}, {2, 1}, {3, 1}, {4, 1}, {5, 0x7F}, {5, 0x3F}, // anim 1
            {4, 3}, {3, 2}, {2, 1}, {1, 1}                         // anim 2
    };

    // ROM: move.w #$280,priority(a0) → bucket 5
    private static final int PRIORITY = 5;

    // ===== Instance state =====
    private int x;
    private int y;
    private int periodMask;    // $32(a0)
    private int phaseOffset;   // $34(a0)

    private int animIndex;           // 0 = idle, 1 = cycle
    private int animStep;
    private int frameTimer;
    int mappingFrame;                // package-private: child reads this
    private boolean childSpawned;    // $36(a0)
    private boolean initApplied;

    public AizDisappearingFloorObjectInstance(ObjectSpawn spawn) {
        super(spawn, "AIZDisappearingFloor");
        this.x = spawn.x();
        this.y = spawn.y();

        int subtype = spawn.subtype() & 0xFF;
        int periodIndex = subtype & 0x0F;
        this.periodMask = PERIOD_TABLE[periodIndex];
        int shift = Math.max(0, periodIndex - 3);
        int upperNibble = (subtype >> 4) & 0x0F;
        this.phaseOffset = upperNibble << shift;
    }

    @Override
    public AizDisappearingFloorObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new AizDisappearingFloorObjectInstance(ctx.spawn());
    }

    private int levelFrameCounter(int fallbackCounter) {
        // Obj_AIZDisappearingFloor reads Level_frame_counter, not
        // V_int_run_count (sonic3k.asm:58373-58375). LevelManager stores the
        // previous completed frame until the current Process_Sprites pass, so
        // the value visible to this object is its counter plus one.
        return services().levelManager() != null
                ? services().levelManager().getFrameCounter()
                : fallbackCounter;
    }

    // ROM: sonic3k.asm:58343-58353
    private void applyInitTimingCheck(int levelFrameCounter) {
        int masked = (levelFrameCounter + phaseOffset) & periodMask;
        if (masked == 0) return;
        int diff = masked - 0xC8;
        if (diff >= 0) return;
        this.frameTimer = (-diff) & 0xFF;
        this.mappingFrame = 5;
        this.animIndex = 1;
        this.animStep = 6; // reappear portion of merged cycle
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        int levelFrameCounter = levelFrameCounter(vIntRunCount);
        if (!initApplied) {
            initApplied = true;
            applyInitTimingCheck(levelFrameCounter);
        }

        // ROM: sonic3k.asm:58358-58368
        int masked = (levelFrameCounter + phaseOffset) & periodMask;
        if (masked == 0) {
            animIndex = 1;
            animStep = 0;
            frameTimer = 0;
            childSpawned = false;
            // ROM: tst.b render_flags(a0); bpl.s — play SFX if on-screen
            if (isOnScreen(0)) {
                services().playSfx(Sonic3kSfx.WATERFALL_SPLASH.id);
            }
        }

        // ROM: jsr (Animate_SpriteIrregularDelay).l
        updateIrregularAnimation();

        // ROM: sonic3k.asm:58373-58389 — spawn child at frame 5
        if (mappingFrame == 5 && !childSpawned) {
            childSpawned = true;
            spawnChild(() -> new BorderChild(
                    new ObjectSpawn(x, y, Sonic3kObjectIds.AIZ_DISAPPEARING_FLOOR, 0, 0, false, 0),
                    this));
        }
    }

    private void updateIrregularAnimation() {
        if (frameTimer > 0) {
            frameTimer--;
            return;
        }
        int[][] seq = (animIndex == 0) ? ANIM_IDLE : ANIM_CYCLE;
        if (animStep >= seq.length) {
            // ROM: $FD, 0 → jump to anim 0 (returns without reading next entry)
            animIndex = 0;
            animStep = 0;
            return;
        }
        int[] entry = seq[animStep];
        mappingFrame = entry[0];
        frameTimer = entry[1];
        animStep++;
        if (animIndex == 0 && animStep >= ANIM_IDLE.length) {
            frameTimer = Integer.MAX_VALUE;
        }
    }

    // ===== Rendering =====

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (mappingFrame == 0) return;
        PatternSpriteRenderer r = getRenderer(Sonic3kObjectArtKeys.AIZ_DISAPPEARING_FLOOR);
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
        ctx.drawCross(x, y, 4, 0f, 1f, 1f);
    }

    // ===== Child: solid border object (ROM loc_2A36C) =====

    /**
     * ROM-faithful child object spawned at parent mapping frame 5.
     * Provides {@code SolidObjectFull} collision and renders the water border
     * visual (Map_AIZDisappearingFloor2). Moved off-screen when the parent
     * reaches frame 3.
     * <p>
     * ROM references: loc_2A36C (sonic3k.asm:58395-58414).
     */
    static class BorderChild extends AbstractObjectInstance
            implements SolidObjectProvider, SolidObjectListener, RewindRecreatable, RomObjectCodePointerProvider {

        /**
         * Word 0 of this object's S3K SST holds its live ROM code pointer.
         * ROM {@code Obj_AIZDisappearingFloor} is installed from the S3K object pointer table at
         * {@code $0002A252} (table read from the user-supplied ROM; the
         * label is defined at docs/skdisasm/sonic3k.asm:58325).
         * Its whole code block lies in one bank, so the HIGH word that
         * {@code sub_13EFC} latches into {@code Tails_CPU_interact} and compares
         * on the next off-screen on-object frame is {@code $0002}
         * (docs/skdisasm/sonic3k.asm:26816-26843).
         */
        @Override
        public int romObjectCodePointerHighWord() {
            return 0x0002;
        }


        // ROM: move.w #$2B,d1 / move.w #$18,d2 / move.w #$19,d3
        private static final int HALF_WIDTH = 0x2B;
        private static final int HALF_HEIGHT_AIR = 0x18;
        private static final int HALF_HEIGHT_GROUND = 0x19;
        private static final int FRAME_DELAY = 3;
        private static final int HIDDEN_X = 0x7FF0;

        private int x;
        private int y;
        private final AizDisappearingFloorObjectInstance parent;
        private int frame;
        private int timer;

        BorderChild(ObjectSpawn spawn, AizDisappearingFloorObjectInstance parent) {
            super(spawn, "AIZDisappearingFloorBorder");
            this.x = spawn.x();
            this.y = spawn.y();
            this.parent = parent;
            // ROM: AllocateObjectAfterCurrent clears RAM → timer starts at 0
            this.timer = 0;
        }

        @Override
        public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
            AizDisappearingFloorObjectInstance restoredParent =
                    RewindRecreateObjectLinks.nearestLiveObject(
                            ctx, AizDisappearingFloorObjectInstance.class);
            return restoredParent == null ? null : new BorderChild(ctx.spawn(), restoredParent);
        }

        @Override
        public SolidObjectParams getSolidParams() {
            return SolidObjectParams.of(HALF_WIDTH, HALF_HEIGHT_AIR, HALF_HEIGHT_GROUND);
        }

        @Override
        public int getTopLandingHalfWidth(PlayableEntity player, int collisionHalfWidth) {
            // ROM Solid_Landed / loc_1E154 (sonic3k.asm:41611-41621) re-reads
            // width_pixels(a0) for the landing X gate. The border child is
            // spawned with width_pixels = $28 (sonic3k.asm:58390) while its
            // solid call passes d1 = $2B (sonic3k.asm:58413-58418), so the
            // default d1 - $B = $20 heuristic is 8px too narrow.
            return 0x28;
        }

        @Override
        public void onSolidContact(PlayableEntity player, SolidContact contact, int fc) {
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity playerEntity) {
            // ROM: cmpi.b #3,mapping_frame(a1) / move.w #$7FF0,x_pos(a0)
            // The child is moved off-screen, not deleted. SolidObjectFull still
            // runs below, which clears a continued ride on the same frame.
            if (parent.mappingFrame == 3) {
                x = HIDDEN_X;
            }
            // ROM: subq.b #1,anim_frame_timer(a0); bpl.s loc_2A394
            timer--;
            if (timer < 0) {
                timer = FRAME_DELAY;
                frame = (frame + 1) & 3;
            }
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            PatternSpriteRenderer r = getRenderer(
                    Sonic3kObjectArtKeys.AIZ_DISAPPEARING_FLOOR_BORDER);
            if (r == null) return;
            r.drawFrameIndex(frame, x, y, false, false);
        }

        @Override
        public int getX() { return x; }

        @Override
        public int getY() { return y; }

        // ROM: move.w #$200,priority(a1) → bucket 4
        @Override
        public int getPriorityBucket() { return RenderPriority.clamp(4); }

        @Override
        public void appendDebugRenderCommands(DebugRenderContext ctx) {
            if (ctx == null) return;
            ctx.drawRect(x, y, HALF_WIDTH, HALF_HEIGHT_AIR, 0f, 1f, 0f);
        }
    }
}
