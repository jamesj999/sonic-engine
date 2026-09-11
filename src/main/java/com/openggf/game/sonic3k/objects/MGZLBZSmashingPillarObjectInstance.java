package com.openggf.game.sonic3k.objects;

import com.openggf.debug.DebugRenderContext;
import com.openggf.game.DamageCause;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
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

import java.util.List;

/**
 * Object 0x52 (SK Set 1 entry 82) and 0x20 (SK Set 1 entry 32) -
 * {@code Obj_MGZLBZSmashingPillar}.
 *
 * <p>ROM: {@code sonic3k.asm:56858-56951}. A single routine is shared between
 * Marble Garden Zone (MGZ, zone 2) and Launch Base Zone (LBZ, zone 6): the zone
 * check at {@code cmpi.b #2,(Current_zone).w} selects a 32x40 pillar for MGZ
 * vs. a 16x16 spiked tube for LBZ. Both forms drop from their spawn position
 * with accelerating gravity, stop after descending {@code subtype * 8} pixels,
 * then retract back to the starting position at one pixel per frame.
 *
 * <p>Collision follows {@code SolidObjectFull} + {@code swap d6 / andi.w #$C}:
 * full solid body, but a ceiling-crush contact (solid's bottom edge pushing
 * into the player's head) triggers {@code sub_24280} hurt. Standing on top
 * stays safe.
 */
public class MGZLBZSmashingPillarObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener, SpawnRewindRecreatable, RomObjectCodePointerProvider {

    /**
     * Word 0 of this object's S3K SST holds its live ROM code pointer.
     * ROM {@code Obj_MGZLBZSmashingPillar} is installed from the S3K object pointer table at
     * {@code $00029216} (table read from the user-supplied ROM; the
     * label is defined at docs/skdisasm/sonic3k.asm:56863).
     * Its whole code block lies in one bank, so the HIGH word that
     * {@code sub_13EFC} latches into {@code Tails_CPU_interact} and compares
     * on the next off-screen on-object frame is {@code $0002}
     * (docs/skdisasm/sonic3k.asm:26816-26843).
     */
    @Override
    public int romObjectCodePointerHighWord() {
        return 0x0002;
    }


    // ROM: gravity acceleration per frame (addi.w #$80,y_vel(a0)).
    private static final int GRAVITY = 0x80;
    // ROM: priority values for the two zone variants (after /$80 bucket mapping).
    private static final int PRIORITY_MGZ = 5;  // $280
    private static final int PRIORITY_LBZ = 1;  // $80

    // Zone-conditional dimensions from the ROM routine head.
    private static final int MGZ_HALF_WIDTH = 0x20;
    private static final int MGZ_HALF_HEIGHT = 0x28;
    private static final int LBZ_HALF_WIDTH = 0x10;
    private static final int LBZ_HALF_HEIGHT = 0x10;

    // ROM: $34(a0) is a 16:16 fixed-point displacement accumulator
    // (lo word = subpixels, hi word = whole pixels). Declared as a long so the
    // carry from subpixels into pixels matches the original add.l.
    private long displacementFixed;
    // ROM: $38(a0) = subtype * 8 = descent distance in whole pixels.
    private int targetDistance;
    // ROM: $30(a0) = saved initial y_pos.
    private int baseY;
    // ROM: y_vel(a0) - accumulates at +$80 per frame during descent.
    private int yVelocity;
    // ROM: $32(a0) - 0 while descending, non-zero during the pause/retract phase.
    private boolean retracting;

    // Zone-resolved fields. Populated lazily on first update() so the
    // constructor never calls services() (see TestNoServicesInObjectConstructors).
    private boolean resolved;
    private boolean isMgzVariant;
    private int halfWidth = MGZ_HALF_WIDTH;
    private int halfHeight = MGZ_HALF_HEIGHT;
    private String artKey = Sonic3kObjectArtKeys.MGZ_SMASHING_PILLAR;
    private int priorityBucket = PRIORITY_MGZ;
    private int landingSfx = Sonic3kSfx.CRASH.id;

    private int currentY;

    public MGZLBZSmashingPillarObjectInstance(ObjectSpawn spawn) {
        super(spawn, "MGZLBZSmashingPillar");
        this.baseY = spawn.y();
        this.currentY = spawn.y();
        // ROM: lsl.w #3,d0 on a byte-zero-extended subtype.
        this.targetDistance = (spawn.subtype() & 0xFF) << 3;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        if (isDestroyed()) {
            return;
        }
        ensureZoneResolved();

        if (!retracting) {
            advanceDescent();
        } else {
            advanceRetract();
        }

        currentY = baseY + (int) ((displacementFixed >> 16) & 0xFFFF);
        updateDynamicSpawn(getX(), currentY);
    }

    private void ensureZoneResolved() {
        if (resolved) {
            return;
        }
        resolved = true;
        isMgzVariant = services().romZoneId() == Sonic3kZoneIds.ZONE_MGZ;
        if (isMgzVariant) {
            halfWidth = MGZ_HALF_WIDTH;
            halfHeight = MGZ_HALF_HEIGHT;
            priorityBucket = PRIORITY_MGZ;
            artKey = Sonic3kObjectArtKeys.MGZ_SMASHING_PILLAR;
            landingSfx = Sonic3kSfx.CRASH.id;
        } else {
            halfWidth = LBZ_HALF_WIDTH;
            halfHeight = LBZ_HALF_HEIGHT;
            priorityBucket = PRIORITY_LBZ;
            artKey = Sonic3kObjectArtKeys.LBZ_SMASHING_SPIKES;
            landingSfx = Sonic3kSfx.MECHA_LAND.id;
        }
    }

    private void advanceDescent() {
        // ROM: d0 = y_vel; y_vel += $80; $34 += sign_extend(d0) << 8.
        int contribution = ((short) yVelocity) << 8;
        yVelocity = (yVelocity + GRAVITY) & 0xFFFF;
        displacementFixed = (displacementFixed + contribution) & 0xFFFFFFFFL;

        // ROM: move.w $34(a0),d2 reads the high word of the 32-bit accumulator
        // (big-endian layout): whole-pixel displacement. blo.s => unsigned compare.
        int wholePixels = (int) ((displacementFixed >> 16) & 0xFFFF);
        if (wholePixels < targetDistance) {
            return;
        }

        // Target reached: snap, enter retract phase, play landing sfx when on-screen.
        // ROM: move.w $38(a0),$34(a0) writes only the high word of the 32-bit
        // accumulator, leaving the subpixel low word untouched.
        yVelocity = 0;
        displacementFixed = (displacementFixed & 0xFFFFL)
                | ((long) (targetDistance & 0xFFFF) << 16);
        retracting = true;

        // ROM: tst.b render_flags(a0); bpl.s => skip sfx when bit 7 clear.
        if (isOnScreen()) {
            services().playSfx(landingSfx);
        }
    }

    private void advanceRetract() {
        // ROM: subq.w #1,d2; move.w d2,$34(a0). One pixel per frame towards 0.
        // The word-write updates only the high word, preserving subpixels so
        // the accumulated fractional descent rolls over into the next cycle.
        // When the high word reaches 0, clear the flag so descent restarts.
        int wholePixels = (int) ((displacementFixed >> 16) & 0xFFFF);
        if (wholePixels == 0) {
            retracting = false;
            return;
        }
        wholePixels = (wholePixels - 1) & 0xFFFF;
        displacementFixed = (displacementFixed & 0xFFFFL) | ((long) wholePixels << 16);
    }

    @Override
    public SolidObjectParams getSolidParams() {
        // ROM: addi.w #$B,d1 (width pad); addq.w #1,d3 (air height one greater).
        return SolidObjectParams.of(halfWidth + 0x0B, halfHeight, halfHeight + 1);
    }

    @Override
    public boolean usesInclusiveRightEdge() {
        // ROM SolidObjectFull_1P -> SolidObject_cont rejects the X bounding box
        // with bhi (unsigned strictly-greater): cmp.w d3,d0 / bhi.w loc_1E0A2
        // (sonic3k.asm:41405). A player shoved flush against the right edge has
        // d0 == width*2, which bhi keeps as a live side contact, so ROM re-sets
        // Status_Push every frame the player holds against the pillar. The
        // engine's default exclusive (>=) X gate would instead drop the contact
        // at the flush edge and let the player's standing-still push-clear win.
        return true;
    }

    @Override
    public boolean groundedSquashEdgeSideContactSetsPush() {
        // A shallow underside overlap with zero y_vel enters loc_1E126. When
        // abs(d0) < $10 it rejoins loc_1E042, whose grounded tail sets
        // Status_Push even when x_vel points away from the pillar. This is the
        // ordinary SolidObjectFull squash-edge result, driven by the live
        // contact geometry. sonic3k.asm:41594-41616,41473-41505.
        return true;
    }

    @Override
    public boolean usesInstanceSolidStateLatchKey() {
        // updateDynamicSpawn follows the moving y_pos, but ROM keeps the
        // standing/pushing bits in this object's fixed SST slot. Key the
        // engine latch by the live instance so a side contact can clear its
        // push bit on the first no-contact frame while the pillar retracts.
        return true;
    }

    @Override
    public int getTopLandingHalfWidth(PlayableEntity playerEntity, int collisionHalfWidth) {
        // ROM: top-surface retention uses width_pixels, not the padded d1.
        return halfWidth;
    }

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        // ROM: swap d6; andi.w #$C,d6; bne -> sub_24280 hurt.
        // Bits 2/3 of d6's high word are set by SolidObjectFull on ceiling-crush
        // paths - the solid's bottom edge collided with the player's top. In the
        // engine pipeline that is SolidContact.touchBottom().
        if (playerEntity == null || !contact.touchBottom() || playerEntity.getInvulnerable()) {
            return;
        }

        int sourceX = getX();
        if (playerEntity.isCpuControlled()) {
            playerEntity.applyHurt(sourceX);
            return;
        }

        boolean hadRings = playerEntity.getRingCount() > 0;
        if (hadRings && !playerEntity.hasShield()) {
            services().spawnLostRings(playerEntity, frameCounter);
        }
        playerEntity.applyHurtOrDeath(sourceX, DamageCause.NORMAL, hadRings);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(artKey);
        if (renderer == null) {
            return;
        }
        // ROM: art_tile is make_art_tile(base, 2, 0) for both variants — the
        // object-level priority bit is 0. At render time the ROM adds art_tile
        // to each piece's tile word (word-truncated), which zeroes the bit-15
        // priority bit that pieces 9/10 of the MGZ mapping carry. Force all
        // pieces to low VDP priority so the bottom spikes render behind
        // foreground tiles, matching the ROM result.
        renderer.drawFrameIndexForcedPriority(0, getX(), currentY,
                false, false, -1, false);
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        if (ctx == null) {
            return;
        }
        ctx.drawRect(getX(), currentY, halfWidth + 0x0B, halfHeight, 0.9f, 0.3f, 0.2f);
        if (retracting) {
            ctx.drawCross(getX(), currentY, 3, 0.4f, 0.8f, 1.0f);
        }
    }

    @Override
    public int getY() {
        return currentY;
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(priorityBucket);
    }
}
