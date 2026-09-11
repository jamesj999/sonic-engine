package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.RomObjectCodePointerProvider;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.Direction;
import com.openggf.physics.TrigLookupTable;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.ObjectControlState;

import java.util.List;

/**
 * Object 0x47 - CNZ Cylinder ({@code Obj_CNZCylinder}).
 *
 * <p>This class ports the ROM motion families from {@code sub_321E2} and the
 * rider-control seam from {@code sub_324C0}.</p>
 */
public final class CnzCylinderInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener, SpawnRewindRecreatable, RomObjectCodePointerProvider {

    /**
     * Word 0 of this object's S3K SST holds its live ROM code pointer.
     * ROM {@code Obj_CNZCylinder} is installed from the S3K object pointer table at
     * {@code $000320F2} (table read from the user-supplied ROM; the
     * label is defined at docs/skdisasm/sonic3k.asm:67619).
     * Its whole code block lies in one bank, so the HIGH word that
     * {@code sub_13EFC} latches into {@code Tails_CPU_interact} and compares
     * on the next off-screen on-object frame is {@code $0003}
     * (docs/skdisasm/sonic3k.asm:26816-26843).
     */
    @Override
    public int romObjectCodePointerHighWord() {
        return 0x0003;
    }

    private static final SolidObjectParams SOLID_PARAMS =
            SolidObjectParams.of(0x2B, 0x20, 0x21);
    private static final int PLAYER_CAPTURE_PRIORITY = RenderPriority.PLAYER_DEFAULT;
    private static final int OBJECT_PRIORITY_BUCKET = 5; // Obj_CNZCylinder: priority $280.
    private static final int PLAYER_TWIST_PRIORITY = RenderPriority.PLAYER_DEFAULT - 1;
    private static final int PRIORITY_THRESHOLD_SOURCE = 0x60;
    private static final int RELEASE_Y_SPEED = -0x680;
    private static final int[] MODE0_SPEED_CAPS = {
            0x04E0, 0x06F0, 0x0870, 0x09C0, 0x0AE0, 0x0C00, 0x0CF0, 0x0DE0
    };
    private static final int[] PLAYER_TWIST_FRAMES = {
            0x55, 0x59, 0x5A, 0x5B, 0x5A, 0x59, 0x55, 0x56, 0x57, 0x58, 0x57, 0x56
    };
    private static final boolean[] PLAYER_TWIST_FLIPS = {
            false, true, true, false, false, false, true, true, true, false, false, false
    };

    private static final int CIRCULAR_HALF_EXTENT = 0x20;

    private static final class RiderSlot {
        private boolean active;
        private boolean contactLatched;
        private int twistAngle;
        private int horizontalDistance;
        private int priorityThresholdSource;
        private AbstractPlayableSprite player;
        private boolean jumpPressedLastFrame;
        private boolean externalAirRetirePending;
    }

    private int baseX;
    private int baseY;
    private int motionSelector;
    private int speedCap;
    private boolean circularRoute;
    private int angleStep;
    private final RiderSlot playerOneSlot = new RiderSlot();
    private final RiderSlot playerTwoSlot = new RiderSlot();
    private AbstractPlayableSprite releasedJumpSolidSkipPlayer;

    private int routeQuadrant;
    private int centerX;
    private int centerY;
    private int standingMaskCache;
    private int standingMask;
    private int nextStandingMask;
    private int standingMaskDeferredBySkippedSolidPass;
    private int heldInputMask;
    private int nextHeldInputMask;
    private int capturedThisUpdateMask;
    private int mode0Velocity;
    private int mode0YSubpixel;
    private int currentYVelocity;
    private int angle;
    private int mappingFrame;
    private int animFrameTimer = 0;
    private String playerTwoDiagnosticBranch = "none";
    private int playerTwoPreX = -1;
    private int playerTwoPreXSubpixel = -1;
    private int playerTwoPostX = -1;
    private int playerTwoPostXSubpixel = -1;
    private int playerTwoPreStatus = -1;
    private int playerTwoPostStatus = -1;
    private int playerTwoPreObjectControl = -1;
    private int playerTwoPostObjectControl = -1;

    public CnzCylinderInstance(ObjectSpawn spawn) {
        super(spawn, "CNZCylinder");
        this.baseX = spawn.x();
        this.baseY = spawn.y();
        this.centerX = spawn.x();
        this.centerY = spawn.y();
        int subtype = spawn.subtype() & 0xFF;
        this.motionSelector = (subtype << 1) & 0x1E;
        this.speedCap = MODE0_SPEED_CAPS[((subtype >>> 3) & 0x0E) >>> 1];
        this.circularRoute = motionSelector >= 0x12;
        this.routeQuadrant = ((subtype & 0x0F) - 0x0A) & 0x03;
        int step = (subtype & 0xF0) << 2;
        if ((spawn.renderFlags() & 0x01) != 0) {
            step = -step;
        }
        this.angleStep = step;
        this.mode0Velocity = 0;
        this.mode0YSubpixel = 0;
        this.currentYVelocity = 0;
        updateDynamicSpawn(centerX, centerY);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Self-contained: all state (including the {@code final} geometry fields) is
     * derived deterministically from the captured spawn, so re-running the constructor
     * reproduces it exactly. Mutable scalar fields are reapplied by the standard
     * scalar-restore pass after recreate. Replaces the former explicit dynamic restore path
     * (Phase-2 codec-deletion batch 2).
     */

    @Override
    public boolean isSkipSolidContactThisFrame() {
        // ROM: Obj_CNZCylinder falls through from init into loc_32188 and calls
        // SolidObjectFull immediately; it does not guard the first frame on obRender bit 7.
        return false;
    }

    @Override
    public void snapshotPreUpdatePosition() {
        super.snapshotPreUpdatePosition();
        releasedJumpSolidSkipPlayer = null;
    }

    @Override
    public int getOutOfRangeReferenceX() {
        // ROM loc_32188 calls Sprite_OnScreen_Test2 with $2E(a0), the saved
        // placement X, after the cylinder has moved away from its current x_pos.
        return baseX;
    }

    @Override
    public int getOnScreenHalfWidth() {
        // ROM Obj_CNZCylinder init writes width_pixels=$20 before loc_32188's
        // SolidObjectFull pass (sonic3k.asm:67634-67641, 67656-67672).
        return 0x20;
    }

    @Override
    public int getOnScreenHalfHeight() {
        // ROM Obj_CNZCylinder init writes height_pixels=$20 before loc_32188's
        // SolidObjectFull pass (sonic3k.asm:67634-67641, 67656-67672).
        return 0x20;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        capturedThisUpdateMask = 0;
        // ROM sub_324C0 / SolidObjectFull (sonic3k.asm:41006-41008): when a
        // rider is offscreen (`tst.b render_flags(a1); bpl.w locret_1DCB4`)
        // the entire SolidObjectFull pass for that rider is skipped, so the
        // cylinder's per-rider standing bit is NOT cleared. The engine's
        // SolidContacts pass also skips offscreen objects (line 4444 gate),
        // so `nextStandingMask` will be 0 even though the rider is still
        // logically anchored to the cylinder. Preserve the previous frame's
        // bits for any rider whose render_flags bit 7 is currently clear so
        // the alternating capture/release cycle in updateRiderSlot can
        // continue to re-capture the offscreen rider each frame.
        //
        int preservedStanding = standingMaskDeferredBySkippedSolidPass;
        int deferredByThisSkippedSolidPass = 0;
        if (playerOneSlot.player != null
                && !riderRenderFlagOnScreen(playerOneSlot.player)) {
            preservedStanding |= (standingMask & 0x01);
            deferredByThisSkippedSolidPass |= standingMask & 0x01;
        }
        if (playerTwoSlot.player != null
                && !riderRenderFlagOnScreen(playerTwoSlot.player)) {
            preservedStanding |= (standingMask & 0x02);
            deferredByThisSkippedSolidPass |= standingMask & 0x02;
        }
        standingMaskDeferredBySkippedSolidPass = deferredByThisSkippedSolidPass;
        preservedStanding |= activeGroundedHeldStandingMask();

        standingMask = nextStandingMask | preservedStanding;
        heldInputMask = nextHeldInputMask;
        nextStandingMask = 0;
        nextHeldInputMask = 0;

        primeDefaultRiderSlots(playerEntity);
        int previousCenterY = centerY;
        updateMotion();
        currentYVelocity = motionSelector == 0 ? mode0Velocity : ((centerY - previousCenterY) << 8);
        updateRiderSlots(vIntRunCount);
        advanceAnimation();
    }

    private static boolean riderRenderFlagOnScreen(AbstractPlayableSprite rider) {
        return !rider.hasRenderFlagOnScreenState() || rider.isRenderFlagOnScreen();
    }

    private int activeGroundedHeldStandingMask() {
        // ROM loc_32208 compares status(a0)&standing_mask with $3C(a0)
        // before sub_324C0 and SolidObjectFull run (sonic3k.asm:67709-67718,
        // 67656-67672). While sub_324C0 holds a grounded rider with
        // object_control=$03, that cylinder status bit remains a continuous
        // standing bit; a missing engine-side solid callback must not create a
        // false 0->standing transition and reapply loc_32208's +$400 boost.
        return activeGroundedHeldStandingMask(playerOneSlot, 0x01)
                | activeGroundedHeldStandingMask(playerTwoSlot, 0x02);
    }

    private int activeGroundedHeldStandingMask(RiderSlot slot, int mask) {
        AbstractPlayableSprite player = slot.player;
        if (!slot.active
                || player == null
                || !player.isObjectControlled()
                || player.getAir()) {
            return 0;
        }
        return standingMask & mask;
    }

    private void updateMotion() {
        if (circularRoute) {
            updateCircularRoute();
        } else {
            switch (motionSelector) {
                case 0x00 -> updateMode0VerticalController();
                case 0x02 -> updateHorizontalShift(3);
                case 0x04 -> updateHorizontalShift(2);
                case 0x06 -> updateHorizontalThreeEighths();
                case 0x08 -> updateHorizontalShift(1);
                case 0x0A -> updateVerticalShift(3);
                case 0x0C -> updateVerticalShift(2);
                case 0x0E -> updateVerticalThreeEighths();
                case 0x10 -> updateVerticalShift(1);
                default -> updateMode0VerticalController();
            }
        }

        updateDynamicSpawn(centerX, centerY);
    }

    private void updateMode0VerticalController() {
        int standingMask = currentStandingMask();
        if (standingMask != standingMaskCache) {
            int delta = standingMask - standingMaskCache;
            standingMaskCache = standingMask;
            // ROM sonic3k.asm:67725-67729 (loc_32208): only apply the +0x400
            // player-landing boost when the cylinder is within ±0x40 pixels of
            // its base. The ROM check is `addi.w #$40, d0; cmpi.w #$80, d0; bhs`
            // i.e. the boost only fires when (centerY - baseY) is in [-0x40, 0x40).
            // Without this gate the engine over-injects downward velocity when
            // Tails lands during the cylinder's return upswing, which was the
            // root cause of CNZ trace F1685 tails_y_speed mismatch (the cylinder's
            // upward velocity at jump-release was 0x22F too low).
            // Applied BEFORE moveMode0Sprite2() to match ROM ordering at loc_32254.
            int preMoveOffset = (centerY - baseY) + 0x40;
            boolean withinBoostBand = preMoveOffset >= 0 && preMoveOffset < 0x80;
            if (delta > 0 && Math.abs(mode0Velocity) < 0x200 && withinBoostBand) {
                mode0Velocity += 0x400;
                if (mode0Velocity > speedCap) {
                    mode0Velocity = speedCap;
                }
            }
        }

        centerX = baseX;
        moveMode0Sprite2();

        int offset = centerY - baseY;
        if (offset < 0) {
            if (mode0Velocity < speedCap) {
                mode0Velocity += 0x20;
                if (mode0Velocity < 0) {
                    mode0Velocity += 0x10;
                } else if ((collectHeldInputMask() & AbstractPlayableSprite.INPUT_DOWN) != 0) {
                    mode0Velocity += 0x20;
                }
            }
            return;
        }

        if (offset > 0) {
            int negativeCap = -speedCap;
            if (mode0Velocity > negativeCap) {
                mode0Velocity -= 0x20;
                // ROM loc_322AC/loc_322D2 uses BPL after subtracting $20, so
                // zero is treated as non-negative and takes the fixed -$10
                // deceleration instead of the UP-held -$20 branch
                // (docs/skdisasm/sonic3k.asm:67772-67782).
                if (mode0Velocity >= 0) {
                    mode0Velocity -= 0x10;
                } else if ((collectHeldInputMask() & AbstractPlayableSprite.INPUT_UP) != 0) {
                    mode0Velocity -= 0x20;
                }
            }
            return;
        }

        if (Math.abs(mode0Velocity) < 0x80) {
            mode0Velocity = 0;
        }
    }

    private void moveMode0Sprite2() {
        // ROM loc_32254 calls MoveSprite2, so y_pos carries the low byte of
        // y_vel between frames even though the visible object centre is a word.
        int yTotal = (mode0YSubpixel & 0xFF) + (mode0Velocity & 0xFF);
        centerY += (mode0Velocity >> 8) + (yTotal >> 8);
        mode0YSubpixel = yTotal & 0xFF;
    }

    private void updateHorizontalShift(int shift) {
        int sine = TrigLookupTable.sinHex((angle >> 8) & 0xFF);
        centerX = baseX + (sine >> shift);
        centerY = baseY;
        angle = (angle + angleStep) & 0xFFFF;
    }

    private void updateHorizontalThreeEighths() {
        int sine = TrigLookupTable.sinHex((angle >> 8) & 0xFF);
        int shifted = sine >> 2;
        centerX = baseX + shifted + (shifted >> 1);
        centerY = baseY;
        angle = (angle + angleStep) & 0xFFFF;
    }

    private void updateVerticalShift(int shift) {
        int sine = TrigLookupTable.sinHex((angle >> 8) & 0xFF);
        centerX = baseX;
        centerY = baseY + (sine >> shift);
        angle = (angle + angleStep) & 0xFFFF;
    }

    private void updateVerticalThreeEighths() {
        int sine = TrigLookupTable.sinHex((angle >> 8) & 0xFF);
        int shifted = sine >> 2;
        centerX = baseX;
        centerY = baseY + shifted + (shifted >> 1);
        angle = (angle + angleStep) & 0xFFFF;
    }

    private void updateCircularRoute() {
        angle = (angle + angleStep) & 0xFFFF;
        int angleByte = (angle >> 8) & 0xFF;

        if (angleStep < 0) {
            if (angleByte < 0x80) {
                angleByte = (angleByte & 0x7F) + 0x80;
                angle = (angle & 0x00FF) | (angleByte << 8);
                routeQuadrant = (routeQuadrant - 1) & 0x03;
            }
        } else if (angleStep > 0) {
            if (angleByte < 0x80) {
                angleByte = (angleByte & 0x7F) + 0x80;
                angle = (angle & 0x00FF) | (angleByte << 8);
                routeQuadrant = (routeQuadrant + 1) & 0x03;
            }
        }

        int varying = TrigLookupTable.cosHex(angleByte) >> 3;
        switch (routeQuadrant & 0x03) {
            case 0 -> {
                centerX = baseX + varying;
                centerY = baseY - CIRCULAR_HALF_EXTENT;
            }
            case 1 -> {
                centerX = baseX + CIRCULAR_HALF_EXTENT;
                centerY = baseY + varying;
            }
            case 2 -> {
                centerX = baseX - varying;
                centerY = baseY + CIRCULAR_HALF_EXTENT;
            }
            default -> {
                centerX = baseX - CIRCULAR_HALF_EXTENT;
                centerY = baseY - varying;
            }
        }
    }

    private int currentStandingMask() {
        return standingMask;
    }

    private int collectHeldInputMask() {
        // ROM loc_32254 reads Ctrl_1_held_logical / Ctrl_2_held_logical after
        // MoveSprite2 using the cylinder's current standing bits; it does not
        // reuse a held-input byte latched by the prior SolidObjectFull pass
        // (sonic3k.asm:67736-67752, 67772-67782). The engine still latches
        // standing feedback across the split object/solid phases, but the
        // mode-0 acceleration must see this frame's UP/DOWN transition.
        int mask = 0;
        boolean foundLiveStandingRider = false;
        if ((standingMask & 0x01) != 0 && playerOneSlot.player != null) {
            foundLiveStandingRider = true;
            mask |= heldInputMaskFor(playerOneSlot.player);
        }
        if ((standingMask & 0x02) != 0 && playerTwoSlot.player != null) {
            foundLiveStandingRider = true;
            mask |= heldInputMaskFor(playerTwoSlot.player);
        }
        return foundLiveStandingRider ? mask : heldInputMask;
    }

    private void updateRiderSlots(int vIntRunCount) {
        updateRiderSlot(playerOneSlot, vIntRunCount);
        updateRiderSlot(playerTwoSlot, vIntRunCount);
    }

    private void updateRiderSlot(RiderSlot slot, int vIntRunCount) {
        AbstractPlayableSprite player = slot.player;
        if (isInvalidRider(player)) {
            if (slot.active) {
                beginPlayerTwoDiagnostic(slot, "release_invalid", player);
                releaseInvalidSlot(slot, vIntRunCount);
                endPlayerTwoDiagnostic(slot, player);
            }
            slot.contactLatched = false;
            return;
        }

        boolean latchedContact = slot.contactLatched;
        slot.contactLatched = false;

        // ROM sub_324C0 loc_32538 (sonic3k.asm:68019-68022): when the captured
        // rider is offscreen (`tst.b render_flags(a1); bpl.w loc_325F2`), the
        // cylinder takes the release branch every frame. ROM SolidObjectFull
        // (sonic3k.asm:41006-41008) ALSO skips Player_2 when his render_flags
        // bit 7 is clear, so the cylinder's p2_standing_bit stays set from the
        // last on-screen frame. The next frame's sub_324C0 (a2)==0 path then
        // re-captures from that preserved standing bit, producing the
        // alternating Status_InAir 0/1 pattern observed at CNZ1 F4489+ when
        // Tails (X=0x1BB9) is just past the right edge of the screen.
        boolean playerOnScreen = !player.hasRenderFlagOnScreenState()
                || player.isRenderFlagOnScreen();

        boolean standing = latchedContact || hasStandingBit(player);
        if (slot.active) {
            if (slot.externalAirRetirePending) {
                beginPlayerTwoDiagnostic(slot, "retire_external_air", player);
                clearSlotOnly(slot);
                endPlayerTwoDiagnostic(slot, player);
                return;
            }
            if (player.getAir() && !player.isObjectControlled()) {
                // External launchers can preempt the cylinder hold before this
                // object's pass. CNZ balloon sub_317AE writes y_vel=-$700,
                // sets Status_InAir, and clears object_control (sonic3k.asm:
                // 66804-66810). ROM still applies loc_32538's held X/twist
                // write before that external launch is observed in the final
                // frame state (sonic3k.asm:68026-68038), but loc_32604 only
                // clears the cylinder's rider byte (sonic3k.asm:
                // 68024-68025,68076-68078); it does not zero the player's
                // velocity. Preserve that external launch.
                beginPlayerTwoDiagnostic(slot, "release_external_air", player);
                holdSlotPositionOnly(slot);
                clearStaleCylinderSupport(player);
                // The balloon runs before this cylinder, but SolidObjectFull
                // clears the cylinder standing status only after sub_324C0
                // has published this final twist. Keep the rider until the
                // next cylinder dispatch reaches loc_32604; release only the
                // mapping latch now so the earlier Player_1 slot can run
                // Animate_Sonic first (sonic3k.asm:66809-66816,
                // 68024-68025,68076-68083).
                player.setObjectMappingFrameControl(false);
                slot.externalAirRetirePending = true;
                endPlayerTwoDiagnostic(slot, player);
                return;
            }
            if (!playerOnScreen) {
                // ROM loc_325F2: bset Status_InAir, object_control=0, (a2)=0.
                beginPlayerTwoDiagnostic(slot, "release_offscreen", player);
                releaseSlot(slot, vIntRunCount, false);
                endPlayerTwoDiagnostic(slot, player);
                return;
            }
            standing = standing || !player.getAir();
            if (!standing) {
                beginPlayerTwoDiagnostic(slot, "release_no_standing", player);
                clearStaleCylinderSupport(player);
                releaseSlot(slot, vIntRunCount, false);
                endPlayerTwoDiagnostic(slot, player);
                return;
            }
            beginPlayerTwoDiagnostic(slot, "hold", player);
            short preHoldReleaseY = player.getCentreY();
            boolean jumpPressed = player.isLogicalJumpPressActive();
            holdSlot(slot, !jumpPressed);
            endPlayerTwoDiagnostic(slot, player);
            // Obj_CNZCylinder passes Ctrl_1_logical/Ctrl_2_logical in d5 to
            // sub_324C0, and loc_325B6 branches on the low-byte A/B/C press
            // bits (sonic3k.asm:67656-67672, 68059-68064). Held raw jump or a
            // live raw edge is insufficient here; the low byte of the logical
            // word must carry the A/B/C press bits that Obj_CNZCylinder passed
            // in d5.
            slot.jumpPressedLastFrame = player.isJumpPressed();
            if (jumpPressed) {
                beginPlayerTwoDiagnostic(slot, "release_jump", player);
                releaseSlot(slot, vIntRunCount, true, preHoldReleaseY);
                endPlayerTwoDiagnostic(slot, player);
                return;
            }
            return;
        }

        // Tails_FlySwim_Unknown can write object_control=$81 before
        // Obj_CNZCylinder's P2 sub_324C0 pass (sonic3k.asm:26651-26653,
        // 67656-67672). The inactive-cylinder path tests only the cylinder's
        // preserved standing bit before replacing object_control with $03 and
        // clearing Status_InAir (sonic3k.asm:67985-68005), whether the prior
        // BuildSprites result is on-screen or off-screen.
        if (!standing) {
            clearStaleCylinderSupport(player);
            return;
        }
        // ROM sub_324C0 (a2)==0 path re-captures immediately - no ROM cooldown.
        // It only tests the cylinder standing bit before writing object_control=3
        // and clearing Status_InAir/x_vel/y_vel/ground_vel (sonic3k.asm:
        // 67985-68005). Tails CPU can write its offscreen despawn marker earlier
        // in the same frame (sub_13ECA, sonic3k.asm:26800-26809), then
        // Obj_CNZCylinder runs its P2 sub_324C0 pass afterward
        // (sonic3k.asm:67656-67672). Let the standing bit recapture immediately
        // for both on-screen and offscreen riders.
        if (playerOnScreen) {
            beginPlayerTwoDiagnostic(slot, "capture", player);
            applyP2CpuNudgeBeforeFirstCapture(slot, player);
        } else {
            beginPlayerTwoDiagnostic(slot, "capture_offscreen", player);
        }
        captureSlot(slot, player, latchedContact);
        endPlayerTwoDiagnostic(slot, player);
    }

    private static boolean isInvalidRider(AbstractPlayableSprite player) {
        // Player movement runs before Obj47. A grounded hurt landing can clear
        // the live hurt flag during that earlier player slot, but the cylinder
        // must still consume the invalid rider state that entered this frame.
        return player == null
                || player.getDead()
                || player.isHurt()
                || player.getHurtAtFrameStart();
    }

    private boolean hasStandingBit(AbstractPlayableSprite player) {
        int bit = standingMaskBitFor(player);
        return bit != 0 && (standingMask & bit) != 0;
    }

    private void applyP2CpuNudgeBeforeFirstCapture(RiderSlot slot, AbstractPlayableSprite player) {
        if (slot != playerTwoSlot || player == null || !player.isCpuControlled()
                || player.getAir() || player.getGSpeed() == 0) {
            return;
        }
        var cpu = player.getCpuController();
        if (cpu == null) {
            return;
        }
        int nudge = cpu.consumePendingGroundedFollowNudge(1);
        if (nudge == 0) {
            return;
        }

        // ROM Tails CPU runs before Obj_CNZCylinder's P2 sub_324C0 pass
        // (sonic3k.asm:26195-26208, 67656-67672). Its FollowLeft/FollowRight
        // branches nudge x_pos by one pixel when the delayed target is on the
        // facing side and ground_vel is nonzero (sonic3k.asm:26717-26724,
        // 26734-26741). The engine discovers this cylinder standing contact
        // after the CPU pass, so apply only the CPU-recorded pending nudge
        // immediately before the first P2 capture consumes the standing bit.
        if (nudge < 0 && player.getDirection() == Direction.LEFT) {
            player.shiftX(-1);
        } else if (nudge > 0 && player.getDirection() == Direction.RIGHT) {
            player.shiftX(1);
        }
    }

    private void primeDefaultRiderSlots(PlayableEntity playerEntity) {
        if (playerOneSlot.player == null && playerEntity instanceof AbstractPlayableSprite sprite) {
            playerOneSlot.player = sprite;
        }

        ObjectServices svc = tryServices();
        AbstractPlayableSprite focused = svc != null && svc.camera() != null
                ? svc.camera().getFocusedSprite()
                : null;
        if (playerOneSlot.player == null && focused != null) {
            playerOneSlot.player = focused;
        }

        if (playerTwoSlot.player == null) {
            AbstractPlayableSprite firstSidekick = getFirstSidekick();
            if (firstSidekick != null && firstSidekick != playerOneSlot.player) {
                playerTwoSlot.player = firstSidekick;
            }
        }
    }

    private AbstractPlayableSprite getFirstSidekick() {
        ObjectServices svc = tryServices();
        if (svc == null) {
            return null;
        }
        return svc.playerQuery().nativeP2OrNull() instanceof AbstractPlayableSprite sprite
                ? sprite
                : null;
    }

    private void captureSlot(RiderSlot slot, AbstractPlayableSprite player, boolean latchedContact) {
        slot.player = player;
        slot.active = true;
        capturedThisUpdateMask |= slotMask(slot);
        int captureCenterX = firstCaptureDistanceAnchorX(player, latchedContact);
        slot.twistAngle = player.getCentreX() < captureCenterX ? 0x80 : 0x00;
        slot.horizontalDistance = Math.min(0xFF, Math.abs(player.getCentreX() - captureCenterX));
        slot.priorityThresholdSource = getPriorityThresholdSource();
        slot.jumpPressedLastFrame = player.isJumpPressed();
        // ROM Obj_CNZCylinder (sonic3k.asm:67668-67672) calls SolidObjectFull
        // every frame, which sets the cylinder's per-rider standing bit on
        // capture. The engine's SolidObject framework blocks the contact pass
        // for object-controlled players (ObjectManager.java:4120-4131
        // `blocksSolidContacts`), so {@link #onSolidContact} never fires once
        // we mark the rider as objectControlled. Set the standing bit
        // explicitly here so the next frame's update() sees it for
        // preservation across both on-screen and off-screen states. Without
        // this, captureSlot setting objectControlled=true creates a
        // "standing-bit cleared, slot.active=true" inconsistency on the very
        // next frame and breaks the cylinder's alternation semantics when the
        // test reseed preserves objectControlled.
        standingMask |= slotMask(slot);

        ObjectServices svc = tryServices();
        if (svc != null && svc.objectManager() != null) {
            svc.objectManager().clearRidingObject(player);
        }

        ObjectControlState.nativeBits0To6CpuAllowedMovementSuppressed().applyTo(player);
        // ROM sub_324C0 writes object_control=$03 here (sonic3k.asm:
        // 67999-68005). It does not set Ctrl_locked, so keep the engine's
        // logical input latch open while object-control movement suppression
        // holds the rider in the cylinder.
        player.setControlLocked(false);
        player.setObjectMappingFrameControl(true);
        // ROM sub_324C0 restores default_y_radius/default_x_radius and clears
        // Status_Roll while the player is held in the twist animation.
        player.restoreDefaultRadii();
        player.setRolling(false);
        player.setAir(false);
        player.setPushing(false);
        player.setRollingJump(false);
        player.setJumping(false);
        // sub_324C0 itself never writes y_pos. The later SolidObjectFull pass
        // owns any MvSonicOnPtfm snap when the rider still overlaps; a stale
        // standing bit can instead be consumed and cleared without moving the
        // rider (sonic3k.asm:67656-67672,67985-68005,41016-41040).
        if (latchedContact && riderRenderFlagOnScreen(player)) {
            player.setCentreYPreserveSubpixel((short) (heldSupportAnchorY() + SOLID_PARAMS.offsetY()
                    - SOLID_PARAMS.groundHalfHeight() - player.getYRadius()));
        }
        player.setAnimationId(0);
        player.setForcedAnimationId(-1);
        player.setXSpeed((short) 0);
        player.setYSpeed((short) 0);
        player.setGSpeed((short) 0);
        player.setPriorityBucket(PLAYER_CAPTURE_PRIORITY);
        applyTwistFrame(player, slot.twistAngle);
        if (!latchedContact && riderRenderFlagOnScreen(player)) {
            // A standing bit preserved across an off-screen SolidObjectFull
            // skip can be consumed on the first on-screen pass even though the
            // rider no longer overlaps. Native sub_324C0 captures first, then
            // the later SolidObjectFull active-rider branch clears standing
            // and restores Status_InAir without clearing object_control.
            player.setAir(true);
        }
    }

    private int firstCaptureDistanceAnchorX(AbstractPlayableSprite player, boolean latchedContact) {
        // sub_321E2 updates x_pos before sub_324C0 captures the rider distance
        // in the same object slot (sonic3k.asm:67656-67672, 67985-67998).
        return centerX;
    }

    private void holdSlot(RiderSlot slot) {
        holdSlot(slot, true);
    }

    private void holdSlot(RiderSlot slot, boolean publishTwistMapping) {
        AbstractPlayableSprite player = slot.player;
        if (player == null) {
            return;
        }

        int sine = TrigLookupTable.sinHex(slot.twistAngle);
        int cosine = TrigLookupTable.cosHex(slot.twistAngle);
        int thresholdByte = ((sine + 0x100) >> 2) & 0xFF;
        // ROM loc_32538 stores the threshold byte at 3(a2), then reads the
        // combined word at 2(a2) as the horizontal distance multiplier.
        int distanceWord = ((slot.horizontalDistance & 0xFF) << 8) | thresholdByte;
        int xOffset = (cosine * distanceWord) >> 16;
        player.setCentreXPreserveSubpixel((short) (heldAnchorX(slot) + xOffset));
        if (!player.getAir()) {
            player.setCentreYPreserveSubpixel((short) (heldSupportAnchorY() + SOLID_PARAMS.offsetY()
                    - SOLID_PARAMS.groundHalfHeight() - player.getYRadius()));
        }
        player.setXSpeed((short) 0);
        player.setYSpeed((short) 0);
        player.setGSpeed((short) cylinderLaunchGroundSpeed(player));
        player.setPushing(false);

        int objectThreshold = slot.priorityThresholdSource & 0xFF;
        player.setPriorityBucket(thresholdByte < objectThreshold
                ? PLAYER_TWIST_PRIORITY
                : PLAYER_CAPTURE_PRIORITY);
        // loc_32538 uses the current byte for GetSineCosine/position, then
        // addq.b #2 before loc_3260A selects PlayerTwistFrames.
        slot.twistAngle = (slot.twistAngle + 2) & 0xFF;
        // A/B/C takes loc_325B6 -> loc_325F2 before loc_3260A, so the jump
        // release row keeps the prior mapping_frame even though the twist byte
        // itself has already advanced (sonic3k.asm:68019-68078).
        if (publishTwistMapping) {
            applyTwistFrame(player, slot.twistAngle);
        }
    }

    private void holdSlotPositionOnly(RiderSlot slot) {
        AbstractPlayableSprite player = slot.player;
        if (player == null) {
            return;
        }

        int sine = TrigLookupTable.sinHex(slot.twistAngle);
        int cosine = TrigLookupTable.cosHex(slot.twistAngle);
        int thresholdByte = ((sine + 0x100) >> 2) & 0xFF;
        int distanceWord = ((slot.horizontalDistance & 0xFF) << 8) | thresholdByte;
        int xOffset = (cosine * distanceWord) >> 16;
        player.setCentreXPreserveSubpixel((short) (heldAnchorX(slot) + xOffset));

        int objectThreshold = slot.priorityThresholdSource & 0xFF;
        player.setPriorityBucket(thresholdByte < objectThreshold
                ? PLAYER_TWIST_PRIORITY
                : PLAYER_CAPTURE_PRIORITY);
        slot.twistAngle = (slot.twistAngle + 2) & 0xFF;
        applyTwistFrame(player, slot.twistAngle);
    }

    private int heldAnchorX(RiderSlot slot) {
        // sub_321E2 updates x_pos before loc_32538 positions a held rider in
        // the same object slot (sonic3k.asm:67656-67672, 68019-68038).
        return centerX;
    }

    private int heldSupportAnchorY() {
        // Motion, rider control, and SolidObjectFull share one ROM object slot;
        // the latter two therefore observe the current post-motion y_pos.
        return centerY;
    }

    private int cylinderLaunchGroundSpeed(AbstractPlayableSprite player) {
        // ROM sub_324C0 loc_32594 (sonic3k.asm:68045-68056): ground_vel is
        // cleared, then set to $800 only while the rider is grounded and
        // abs(y_vel(a0)) has reached the cylinder launch threshold.
        if (player.getAir() || Math.abs((short) romStoredYVelocity()) < 0x480) {
            return 0;
        }
        return 0x800;
    }

    private int romStoredYVelocity() {
        // ROM loc_32594/loc_325B6 read y_vel(a0), but only mode 0's
        // loc_32208 controller updates that field. Sine/circular routes such
        // as loc_3238C write y_pos(a0) directly and leave y_vel(a0) unchanged
        // (sonic3k.asm:67709-67804, 67865-67872, 68045-68068).
        return motionSelector == 0 ? currentYVelocity : 0;
    }

    private void clearStaleCylinderSupport(AbstractPlayableSprite player) {
        if (!isLatchedToThisCylinder(player)) {
            return;
        }
        // ROM sub_324C0 loc_32538 (sonic3k.asm:68019-68025) exits the
        // rider-control path when the cylinder standing bit is clear. Clear
        // only this cylinder's engine-side latch so the shared SolidObject
        // finalizer cannot preserve stale object support for a released rider.
        ObjectServices svc = tryServices();
        if (svc != null && svc.objectManager() != null) {
            svc.objectManager().clearRidingObject(player);
        }
        player.setOnObject(false);
        player.setLatchedSolidObjectId(0);
    }

    private boolean isLatchedToThisCylinder(AbstractPlayableSprite player) {
        if (player == null || player.getLatchedSolidObjectId() != (spawn.objectId() & 0xFF)) {
            return false;
        }
        return player.getLatchedSolidObjectInstance() == this;
    }

    private int getPriorityThresholdSource() {
        return PRIORITY_THRESHOLD_SOURCE;
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(OBJECT_PRIORITY_BUCKET);
    }

    private void clearSlotOnly(RiderSlot slot) {
        slot.active = false;
        slot.externalAirRetirePending = false;
    }

    private void releaseInvalidSlot(RiderSlot slot, int vIntRunCount) {
        AbstractPlayableSprite player = slot.player;
        if (player != null) {
            clearCylinderReleaseSupport(player);
        }
        releaseSlot(slot, vIntRunCount, false);
    }

    private void releaseSlot(RiderSlot slot, int vIntRunCount, boolean jumpedOff) {
        releaseSlot(slot, vIntRunCount, jumpedOff, (short) 0);
    }

    private void releaseSlot(RiderSlot slot, int vIntRunCount, boolean jumpedOff, short jumpReleaseY) {
        AbstractPlayableSprite player = slot.player;
        if (player == null) {
            slot.active = false;
            return;
        }

        slot.active = false;
        player.setObjectMappingFrameControl(false);
        player.releaseFromObjectControl(vIntRunCount);
        player.setControlLocked(false);
        player.setPriorityBucket(RenderPriority.PLAYER_DEFAULT);
        player.setForcedAnimationId(-1);
        player.setPushing(false);

        if (jumpedOff) {
            short releaseX = player.getCentreX();
            // ROM loc_325B6 changes y_radius/x_radius for the jump, then
            // falls through to loc_325F2 without writing y_pos (sonic3k.asm:
            // 68059-68076). Use the y_pos from before the engine's local hold
            // rewrite; moving cylinders must not recompute release Y from the
            // current object centre on the jump frame.
            short releaseY = jumpReleaseY;
            clearCylinderReleaseSupport(player);
            // The same Obj_CNZCylinder pass still calls SolidObjectFull after
            // sub_324C0 (sonic3k.asm:67656-67672). Since the cylinder standing
            // bit was set for loc_32538, SolidObjectFull_1P takes loc_1DC98
            // for the now-airborne rider and returns d4=0 without applying
            // loc_1E154's upward-velocity lift (sonic3k.asm:41016-41034).
            releasedJumpSolidSkipPlayer = player;
            player.setAir(true);
            player.setJumping(true);
            player.applyRollingRadii(false);
            player.setRolling(true);
            player.setCentreXPreserveSubpixel(releaseX);
            player.setCentreYPreserveSubpixel(releaseY);
            player.setAnimationId(2);
            player.setYSpeed((short) (romStoredYVelocity() + RELEASE_Y_SPEED));
            player.setXSpeed((short) 0);
            player.setGSpeed((short) 0);
            player.suppressNextJumpPress();
        } else {
            // ROM loc_325F2 is also the off-screen/invalid-rider release tail.
            // It sets Status_InAir, restores priority, and clears
            // object_control, but does not touch x_vel, y_vel, or ground_vel
            // (sonic3k.asm:68019-68025,68069-68078). In particular, a rider
            // released just after loc_32594 must retain its $0800 launch
            // ground speed for the following CPU slot.
            player.setAir(true);
            player.setJumping(false);
        }
    }

    private void clearCylinderReleaseSupport(AbstractPlayableSprite player) {
        ObjectServices svc = tryServices();
        if (svc != null && svc.objectManager() != null) {
            svc.objectManager().clearRidingObject(player);
        }
        player.setOnObject(false);
        player.setLatchedSolidObjectId(0);
    }

    private void applyTwistFrame(AbstractPlayableSprite player, int twistAngle) {
        int frameIndex = ((twistAngle + 0x0B) & 0xFF) / 0x16;
        if (frameIndex < 0 || frameIndex >= PLAYER_TWIST_FRAMES.length) {
            frameIndex = 0;
        }

        player.setMappingFrame(PLAYER_TWIST_FRAMES[frameIndex]);
        boolean flipLeft = PLAYER_TWIST_FLIPS[frameIndex];
        // loc_32610 writes only render_flags bits 0-1 from PlayerTwistFlip;
        // it never mutates Status_Facing. Keep gameplay facing independent
        // while the cylinder directly owns the visual twist frame.
        player.setRenderFlips(flipLeft, false);
    }

    private void advanceAnimation() {
        animFrameTimer--;
        if (animFrameTimer >= 0) {
            return;
        }
        animFrameTimer = 1;
        mappingFrame = (mappingFrame + 1) & 0x03;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.CNZ_CYLINDER);
        if (renderer == null) {
            return;
        }

        boolean hFlip = (spawn.renderFlags() & 0x01) != 0;
        boolean vFlip = (spawn.renderFlags() & 0x02) != 0;
        renderer.drawFrameIndex(mappingFrame, getX(), getY(), hFlip, vFlip);
    }

    @Override
    public SolidObjectParams getSolidParams() {
        return SOLID_PARAMS;
    }

    @Override
    public boolean usesInclusiveRightEdge() {
        // Obj_CNZCylinder calls SolidObjectFull, whose new-contact path enters
        // SolidObject_cont. Its horizontal gate rejects only with `bhi`, so
        // relX == d1*2 remains a zero-distance side contact and grounded
        // players retain Status_Push (sonic3k.asm:67656-67672,
        // 41383-41400,41468-41501).
        return true;
    }

    @Override
    public boolean isSolidFor(PlayableEntity player) {
        return player != releasedJumpSolidSkipPlayer
                && (!(player instanceof AbstractPlayableSprite sprite) || !isInvalidRider(sprite));
    }

    @Override
    public boolean allowsObjectControlledSolidContacts() {
        // ROM Obj_CNZCylinder writes object_control=$03 on capture
        // (sonic3k.asm:68002), then still calls SolidObjectFull every frame
        // after sub_324C0 (sonic3k.asm:67656-67672). SolidObjectFull's active
        // rider branch can clear the cylinder standing bit when the rider is
        // airborne or leaves bounds (sonic3k.asm:41016-41033), which sub_324C0
        // consumes on the next active-slot check (sonic3k.asm:68019-68025).
        return true;
    }

    @Override
    public boolean rejectsBit7ObjectControlSideContact(PlayableEntity player) {
        // ROM SolidObject_cont rejects signed object_control values before side
        // separation (`tst.b object_control(a1); bmi.w loc_1E0A2`,
        // sonic3k.asm:41438-41440). This is narrower than the normal
        // object-controlled opt-in above: CNZCylinder's bit-7-clear captured
        // states such as $03 still need SolidObjectFull feedback, while Tails'
        // $81 flight/despawn marker must not be pushed sideways by the cylinder.
        return true;
    }

    @Override
    public boolean rejectsBit7ObjectControlNewSolidContact(PlayableEntity player) {
        // Obj_CNZCylinder reaches SolidObjectFull after sub_324C0
        // (sonic3k.asm:67656-67672). For new contacts, SolidObject_cont tests
        // signed object_control before both side separation and top landing
        // (`tst.b object_control(a1); bmi.w loc_1E0A2`,
        // sonic3k.asm:41394-41440). This must not block bit-7-clear captured
        // riders because their standing-bit branch is consumed before
        // SolidObject_cont.
        return true;
    }

    @Override
    public boolean isTopSolidOnly() {
        return false;
    }

    @Override
    public boolean usesInstanceSolidStateLatchKey() {
        // Obj_CNZCylinder keeps its standing/pushing bits in the live SST
        // status byte while sub_321E2 changes x_pos/y_pos every object pass
        // (sonic3k.asm:67656-67672). The engine mirrors that moving body by
        // rebuilding its dynamic ObjectSpawn coordinates, so spawn identity
        // cannot own the native solid bits: a coordinate change would orphan
        // the prior push latch before loc_1E0A2 can clear Status_Push.
        return true;
    }

    @Override
    public boolean usesPreUpdatePositionForSolidContact(PlayableEntity player) {
        // Obj_CNZCylinder calls sub_321E2 before sub_324C0 and SolidObjectFull,
        // so every rider and free-contact branch observes the post-motion
        // x_pos/y_pos from the current object slot (sonic3k.asm:67656-67672).
        return false;
    }

    private boolean isHorizontalOscillator() {
        return motionSelector == 0x02
                || motionSelector == 0x04
                || motionSelector == 0x06
                || motionSelector == 0x08;
    }

    private boolean isVerticalOscillator() {
        return motionSelector == 0x0A
                || motionSelector == 0x0C
                || motionSelector == 0x0E
                || motionSelector == 0x10;
    }

    @Override
    public void onSolidContact(PlayableEntity player, SolidContact contact, int frameCounter) {
        if (!contact.standing() || !(player instanceof AbstractPlayableSprite sprite)) {
            return;
        }

        RiderSlot slot = resolveContactSlot(sprite);
        if (slot == null) {
            return;
        }

        slot.player = sprite;
        slot.contactLatched = true;

        int mask = slotMask(slot);
        nextStandingMask |= mask;
        nextHeldInputMask |= heldInputMaskFor(sprite);

        // ROM Obj_CNZCylinder positions captured riders in sub_324C0 before
        // calling SolidObjectFull with d4 = current x_pos(a0)
        // (sonic3k.asm:67656-67672, 68026-68038). SolidObjectFull_1P then
        // calls MvSonicOnPtfm with that same current anchor, so the platform
        // X delta is zero for captured riders (sonic3k.asm:41038-41040,
        // 41667-41679). Keep the standing bit feedback, but do not let the
        // engine's previous-X riding tracker apply a second carry delta next
        // frame.
        ObjectServices svc = tryServices();
        if (svc != null && svc.objectManager() != null && sprite.isObjectControlled()) {
            svc.objectManager().clearRidingObject(sprite);
        }
        if (sprite.isObjectControlled() && !sprite.getAir()
                && (capturedThisUpdateMask & mask) == 0
                && Math.abs((short) currentYVelocity) >= 0x480) {
            sprite.setGSpeed((short) 0x0800);
        }
    }

    private RiderSlot resolveContactSlot(AbstractPlayableSprite sprite) {
        if (playerOneSlot.player == sprite) {
            return playerOneSlot;
        }
        if (playerTwoSlot.player == sprite) {
            return playerTwoSlot;
        }
        ObjectServices svc = tryServices();
        AbstractPlayableSprite focused = svc != null && svc.camera() != null
                ? svc.camera().getFocusedSprite()
                : null;
        if (sprite == focused) {
            return playerOneSlot;
        }
        if (isFirstSidekick(sprite)) {
            return playerTwoSlot;
        }
        if (playerOneSlot.player == null) {
            return playerOneSlot;
        }
        if (playerTwoSlot.player == null) {
            return playerTwoSlot;
        }
        return null;
    }

    private boolean isFirstSidekick(AbstractPlayableSprite sprite) {
        ObjectServices svc = tryServices();
        if (svc == null) {
            return false;
        }
        return svc.playerQuery().nativeP2OrNull() == sprite;
    }

    private int slotMask(RiderSlot slot) {
        return slot == playerOneSlot ? 0x01 : 0x02;
    }

    private int standingMaskBitFor(AbstractPlayableSprite sprite) {
        ObjectServices svc = tryServices();
        AbstractPlayableSprite focused = svc != null && svc.camera() != null
                ? svc.camera().getFocusedSprite()
                : null;
        if (playerOneSlot.player == sprite
                || (sprite == focused && playerTwoSlot.player != sprite)) {
            return 0x01;
        }
        if (playerTwoSlot.player == sprite || isFirstSidekick(sprite)) {
            return 0x02;
        }
        if (svc != null) {
            PlayableEntity main = svc.playerQuery().mainPlayerOrNull();
            for (PlayableEntity candidate : svc.playerQuery().playersFor(
                    ObjectPlayerParticipationPolicy.MAIN_PLUS_ENGINE_SIDEKICKS_AS_NATIVE_P2_EXTENDED)) {
                if (candidate != main && candidate == sprite) {
                    return 0x02;
                }
            }
        }
        return 0;
    }

    private int heldInputMaskFor(AbstractPlayableSprite sprite) {
        int mask = 0;
        if (sprite.isUpPressed()) {
            mask |= AbstractPlayableSprite.INPUT_UP;
        }
        if (sprite.isDownPressed()) {
            mask |= AbstractPlayableSprite.INPUT_DOWN;
        }
        return mask;
    }

    private void beginPlayerTwoDiagnostic(RiderSlot slot, String branch,
                                          AbstractPlayableSprite player) {
        if (slot != playerTwoSlot || player == null) {
            return;
        }
        playerTwoDiagnosticBranch = branch;
        playerTwoPreX = player.getCentreX() & 0xFFFF;
        playerTwoPreXSubpixel = player.getXSubpixelRaw() & 0xFFFF;
        playerTwoPreStatus = diagnosticStatusByte(player);
        playerTwoPreObjectControl = player.isObjectControlled() ? 0x03 : 0x00;
        playerTwoPostX = playerTwoPreX;
        playerTwoPostXSubpixel = playerTwoPreXSubpixel;
        playerTwoPostStatus = playerTwoPreStatus;
        playerTwoPostObjectControl = playerTwoPreObjectControl;
    }

    private void endPlayerTwoDiagnostic(RiderSlot slot, AbstractPlayableSprite player) {
        if (slot != playerTwoSlot || player == null) {
            return;
        }
        playerTwoPostX = player.getCentreX() & 0xFFFF;
        playerTwoPostXSubpixel = player.getXSubpixelRaw() & 0xFFFF;
        playerTwoPostStatus = diagnosticStatusByte(player);
        playerTwoPostObjectControl = player.isObjectControlled() ? 0x03 : 0x00;
    }

    private static int diagnosticStatusByte(AbstractPlayableSprite player) {
        int status = 0;
        if (player.getDirection() == Direction.LEFT) {
            status |= 0x01;
        }
        if (player.getAir()) {
            status |= 0x02;
        }
        if (player.getRolling()) {
            status |= 0x04;
        }
        if (player.isOnObject()) {
            status |= 0x08;
        }
        if (player.isInWater()) {
            status |= 0x40;
        }
        return status;
    }

    @Override
    public String traceDebugDetails() {
        return String.format(
                "cyl sub=%02X base=%04X,%04X angle=%04X q=%d center=%04X,%04X yv=%04X masks=%02X/%02X p2=%s pre=%04X.%02X,%02X/%02X post=%04X.%02X,%02X/%02X slot=%s,%02X,%02X,%02X",
                spawn.subtype() & 0xFF,
                baseX & 0xFFFF,
                baseY & 0xFFFF,
                angle & 0xFFFF,
                routeQuadrant,
                centerX & 0xFFFF,
                centerY & 0xFFFF,
                currentYVelocity & 0xFFFF,
                standingMask & 0xFF,
                nextStandingMask & 0xFF,
                playerTwoDiagnosticBranch,
                playerTwoPreX & 0xFFFF,
                playerTwoPreXSubpixel & 0xFF,
                playerTwoPreStatus & 0xFF,
                playerTwoPreObjectControl & 0xFF,
                playerTwoPostX & 0xFFFF,
                playerTwoPostXSubpixel & 0xFF,
                playerTwoPostStatus & 0xFF,
                playerTwoPostObjectControl & 0xFF,
                playerTwoSlot.active,
                playerTwoSlot.twistAngle & 0xFF,
                playerTwoSlot.horizontalDistance & 0xFF,
                playerTwoSlot.priorityThresholdSource & 0xFF);
    }
}
