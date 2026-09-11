package com.openggf.game.sonic3k.objects;

import com.openggf.debug.DebugRenderContext;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.RomObjectCodePointerProvider;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.TrigLookupTable;

import java.util.List;

/**
 * Object 0x67 - HCZ Snake Blocks (Hydrocity Zone Act 2).
 *
 * <p>ROM: Obj_HCZSnakeBlocks (sonic3k.asm:50869-50996).
 *
 * <p>A solid 32x32 block that moves along a 128x128 pixel square path centered
 * on its spawn position. The path is divided into 4 quadrants; within each
 * quadrant the block slides along one edge using cosine interpolation (eased
 * motion) for the last 128 frames, then waits at the next corner for the first
 * 128 frames of the following quadrant while the angle is clamped to 0x80.
 *
 * <p>Multiple instances are placed in the level layout at the same base
 * position with staggered starting angles to create the visual "snake" of
 * blocks following each other around the square.
 *
 * <p>Uses {@code Map_HCZFloatingPlatform} frame 1 (32x32 block) with
 * {@code ArtTile_HCZ2BlockPlat} (tile 0x0028, palette 0).
 */
public class HCZSnakeBlocksObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener, SpawnRewindRecreatable, RomObjectCodePointerProvider {

    /**
     * Word 0 of this object's S3K SST holds its live ROM code pointer.
     * ROM {@code Obj_HCZSnakeBlocks} is installed from the S3K object pointer table at
     * {@code $000256BE} (table read from the user-supplied ROM; the
     * label is defined at docs/skdisasm/sonic3k.asm:50874).
     * Its whole code block lies in one bank, so the HIGH word that
     * {@code sub_13EFC} latches into {@code Tails_CPU_interact} and compares
     * on the next off-screen on-object frame is {@code $0002}
     * (docs/skdisasm/sonic3k.asm:26816-26843).
     */
    @Override
    public int romObjectCodePointerHighWord() {
        return 0x0002;
    }


    // ROM: make_art_tile(ArtTile_HCZ2BlockPlat, 0, 0) — palette 0, not the floating platform art
    private static final String ART_KEY = Sonic3kObjectArtKeys.HCZ_SNAKE_BLOCK;

    // ROM: move.w #$180,priority(a0)
    private static final int PRIORITY_BUCKET = 3;

    // ROM: move.b #$10,width_pixels(a0) / move.b #$10,height_pixels(a0)
    private static final int WIDTH_PIXELS = 0x10;
    private static final int HEIGHT_PIXELS = 0x10;

    // ROM: SolidObjectFull uses width_pixels + $B, height_pixels, height_pixels + 1.
    private static final int SOLID_HALF_WIDTH = WIDTH_PIXELS + 0x0B;
    private static final int SOLID_AIR_HALF_HEIGHT = HEIGHT_PIXELS;
    private static final int SOLID_GROUND_HALF_HEIGHT = HEIGHT_PIXELS + 1;

    // ROM: move.w #$280,$42(a0) — despawn once base X is more than 640px behind camera.
    private static final int OFFSCREEN_CULL_RANGE = 0x280;

    // ROM: move.b #1,mapping_frame(a0)
    private static final int MAPPING_FRAME = 1;

    // ROM: move.w #$40,d2 — constant displacement (64 pixels) for the fixed-axis offset.
    private static final int FIXED_OFFSET = 0x40;

    // ROM: cmpi.b #$80,d0 — angles below this are clamped, creating the corner wait.
    private static final int ANGLE_CLAMP_MIN = 0x80;

    private int baseX;
    private int baseY;
    private int direction; // +1 CW, -1 CCW (ROM: $40(a0))
    private int x;
    private int y;
    private int angle;   // ROM: angle(a0), 0x00-0xFF byte
    private int quadrant; // ROM: $2E(a0), 0-3

    public HCZSnakeBlocksObjectInstance(ObjectSpawn spawn) {
        super(spawn, "HCZSnakeBlocks");
        this.baseX = spawn.x();
        this.baseY = spawn.y();
        // ROM: subtype bit 7 sets direction negative (CCW)
        this.direction = ((byte) spawn.subtype()) < 0 ? -1 : 1;
        // ROM: andi.b #$7F,d1 / move.b d1,angle(a0)
        this.angle = spawn.subtype() & 0x7F;
        // ROM: move.b status(a0),$2E(a0) — flip bits encode starting quadrant
        this.quadrant = spawn.renderFlags() & 0x03;
        this.x = baseX;
        this.y = baseY;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        stepMotion();
        updateDynamicSpawn(x, y);

        if (!isBasePositionInRange()) {
            setDestroyed(true);
        }
    }

    /**
     * ROM: move.w $44(a0),d0 / andi.w #$FF80,d0 / sub.w (Camera_X_pos_coarse_back).w,d0 / cmp.w $42(a0),d0
     *
     * <p>The snake blocks use their original spawn X for lifetime, not their animated X,
     * and compare against the camera's coarse-back position ({@code cameraX - 128}, aligned
     * to 128-pixel chunks) using unsigned 16-bit arithmetic.
     */
    private boolean isBasePositionInRange() {
        int objAligned = baseX & 0xFF80;
        int screenAligned = (services().camera().getX() - 128) & 0xFF80;
        int dist = (objAligned - screenAligned) & 0xFFFF;
        return dist <= OFFSCREEN_CULL_RANGE;
    }

    /**
     * ROM: sub_25770 — Advance angle, clamp for corner wait, compute position.
     *
     * <p>The motion works as follows per quadrant:
     * <ul>
     *   <li>Angle increments (CW) or decrements (CCW) by 1 each frame.</li>
     *   <li>When angle wraps through 0, the quadrant advances/retreats (mod 4).</li>
     *   <li>Angles 0x00-0x7F are clamped to 0x80 before the trig lookup,
     *       which produces cos(0x80)=-256, yielding d1=-64 after /4.
     *       This means the block sits at the corner for 128 frames.</li>
     *   <li>Angles 0x80-0xFF sweep cosine from -256 to +253, giving d1 from
     *       -64 to ~+63 — the block slides along one edge of the square.</li>
     *   <li>d2 is a constant 0x40 (64) — the fixed-axis offset from base.</li>
     * </ul>
     */
    private void stepMotion() {
        int oldAngle = angle;
        angle = (angle + direction) & 0xFF;

        // ROM: quadrant transition on byte wrap.
        // CW (direction=+1): add.b d0,angle / bne.s (skip if non-zero) / addq.b #1,$2E
        // CCW (direction=-1): add.b d0,angle / bcs.s (skip if carry=no borrow) / subq.b #1,$2E
        if (direction > 0) {
            // CW: wrap from 0xFF->0x00 means angle==0 after add
            if (angle == 0 && oldAngle != 0) {
                quadrant = (quadrant + 1) & 0x03;
            }
        } else {
            // CCW: wrap from 0x00->0xFF means borrow (carry clear in 68k sub)
            // In 68k: add.b #-1 to 0x00 = 0xFF with carry CLEAR, so bcs fails
            if (oldAngle == 0 && angle == 0xFF) {
                quadrant = (quadrant - 1) & 0x03;
            }
        }

        // ROM: clamp angle to 0x80 minimum before GetSineCosine.
        // cmpi.b #$80,d0 / bhs.s loc_257BC / move.b #$80,d0
        int trigAngle = angle;
        if ((trigAngle & 0xFF) < ANGLE_CLAMP_MIN) {
            trigAngle = ANGLE_CLAMP_MIN;
        }

        // ROM: jsr (GetSineCosine).l — returns sin in d0, cos in d1 (both *256)
        // asr.w #2,d1 — d1 = cos/4
        // d2 was set to 0x40 earlier (constant, NOT cosine-derived)
        int cosDiv4 = TrigLookupTable.cosHex(trigAngle) >> 2; // d1
        int fixedD2 = FIXED_OFFSET;                            // d2 = 0x40

        // ROM: Apply displacements per quadrant.
        // Quadrant 0: x = baseX + d1, y = baseY + (-d2)   [top edge, left to right]
        // Quadrant 1: y = baseY + d1, x = baseX + d2      [right edge, top to bottom]
        // Quadrant 2: x = baseX + (-d1), y = baseY + d2   [bottom edge, right to left]
        // Quadrant 3: y = baseY + (-d1), x = baseX + (-d2) [left edge, bottom to top]
        switch (quadrant & 0x03) {
            case 0 -> {
                // ROM: add.w $30(a0),d1 / neg.w d2 / add.w $34(a0),d2
                x = baseX + cosDiv4;
                y = baseY + (-fixedD2);
            }
            case 1 -> {
                // ROM: add.w $34(a0),d1 / add.w $30(a0),d2
                y = baseY + cosDiv4;
                x = baseX + fixedD2;
            }
            case 2 -> {
                // ROM: neg.w d1 / add.w $30(a0),d1 / add.w $34(a0),d2
                x = baseX + (-cosDiv4);
                y = baseY + fixedD2;
            }
            default -> {
                // ROM: neg.w d1 / add.w $34(a0),d1 / neg.w d2 / add.w $30(a0),d2
                y = baseY + (-cosDiv4);
                x = baseX + (-fixedD2);
            }
        }
    }

    // No isSolidFor override — matches the ROM exactly.
    // The ROM's Obj_HCZSnakeBlocks calls SolidObjectFull with standard
    // parameters and no conditional solidity. The level layout places blocks
    // in ascending angle order, so the leading block (highest angle) gets the
    // highest SST slot and wins the solid collision each frame. The ROM's
    // inline SolidObjectFull processing with MvSonicOnPtfm naturally
    // maintains riding via geometric defence.

    @Override
    public SolidObjectParams getSolidParams() {
        return SolidObjectParams.of(SOLID_HALF_WIDTH, SOLID_AIR_HALF_HEIGHT,
                SOLID_GROUND_HALF_HEIGHT);
    }

    @Override
    public boolean carriesRiderOnHorizontalMove(PlayableEntity player) {
        // loc_25724 moves the block first, then loads the updated x_pos into
        // d4 immediately before SolidObjectFull. The continued-ride path copies
        // d4 to d2, and MvSonicOnPtfm subtracts that same current x_pos, so the
        // horizontal carry delta is zero (sonic3k.asm:50893-50910,
        // 41016-41042,41642-41679).
        return false;
    }

    @Override
    public void onSolidContact(PlayableEntity player, SolidContact contact, int frameCounter) {
        // No additional contact side effects in the ROM routine.
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(ART_KEY);
        if (renderer != null) {
            renderer.drawFrameIndex(MAPPING_FRAME, x, y, false, false);
        }
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        if (ctx == null) {
            return;
        }
        ctx.drawCross(baseX, baseY, 4, 0.5f, 0.8f, 1.0f);
        ctx.drawRect(x, y, SOLID_HALF_WIDTH, SOLID_AIR_HALF_HEIGHT, 0.2f, 0.9f, 0.6f);
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
    public int getPriorityBucket() {
        return RenderPriority.clamp(PRIORITY_BUCKET);
    }
}
