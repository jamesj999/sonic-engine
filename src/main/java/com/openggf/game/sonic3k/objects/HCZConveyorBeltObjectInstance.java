package com.openggf.game.sonic3k.objects;

import com.openggf.debug.DebugColor;
import com.openggf.debug.DebugRenderContext;
import com.openggf.game.PlayableEntity;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.sprites.NativePositionOps;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.ObjectControlState;

import java.util.List;

/**
 * Object 0x3E &mdash; HCZ Conveyor Belt (Sonic 3 &amp; Knuckles, Hydrocity Zone).
 * <p>
 * A non-rendered logic object that controls player interaction with conveyor belt
 * level geometry. Supports both standing-on-top and hanging-below states, with
 * automatic horizontal movement and player animation control via direct mapping
 * frame manipulation.
 * <p>
 * The object uses a shared load array to prevent duplicate instances, since conveyor
 * belts are wide and the object spawner may attempt to load them multiple times.
 * <p>
 * Fan interaction: When the player is on the belt (object-controlled), any fan
 * blowing on them sets {@code ground_vel = 1}. The belt's animation routine reads
 * this value to drive the animation phase toward the hanging center ($80) rather
 * than the standing center ($00), creating a visual transition between standing
 * and hanging frames.
 * <p>
 * ROM references: Obj_HCZConveyorBelt (sonic3k.asm:66306-66625).
 * <p>
 * Subtype encoding: bits 0-3 select from 16 conveyor belt configurations in
 * the data table ({@code word_31124}). Each entry defines left/right X boundaries.
 * Status bit 0 (render_flags bit 0) controls orientation: 0 = rightward, 1 = leftward.
 */
public class HCZConveyorBeltObjectInstance extends AbstractObjectInstance
        implements SpawnRewindRecreatable {

    // ===== Conveyor belt boundary data table (word_31124, sonic3k.asm:66287-66303) =====
    // Each entry: { leftX, rightX } defining the horizontal bounds of a belt.
    private static final int[][] BELT_BOUNDS = {
            {0x0B28, 0x0CD8},  // subtype 0
            {0x0BA8, 0x0CD8},  // subtype 1
            {0x0BA8, 0x0CD8},  // subtype 2
            {0x0EA8, 0x1058},  // subtype 3
            {0x11A8, 0x12D8},  // subtype 4
            {0x1928, 0x19D8},  // subtype 5
            {0x21A8, 0x2358},  // subtype 6
            {0x21A8, 0x2358},  // subtype 7
            {0x22A8, 0x2458},  // subtype 8
            {0x23A8, 0x2558},  // subtype 9
            {0x2528, 0x26D8},  // subtype 10
            {0x26A8, 0x27D8},  // subtype 11
            {0x26A8, 0x2958},  // subtype 12
            {0x2728, 0x28D8},  // subtype 13
            {0x3328, 0x3458},  // subtype 14
            {0x3328, 0x33D8},  // subtype 15
    };

    // ===== Shared load array (Conveyor_belt_load_array, sonic3k.constants.asm:317) =====
    // The ROM indexes this with the full subtype byte before masking the low nibble
    // for the bounds table. HCZ uses paired placements like 0x00/0x10 for the
    // top/bottom belt pair, so they must not collide here.
    private static final boolean[] loadArray = new boolean[0x100];

    // ===== Player animation frame table (byte_314D2, sonic3k.asm:66619) =====
    // 32 entries: maps animation phase (upper nibble of phase byte + frame set offset) to
    // player mapping frame. Two sets of 16 frames, alternating via 8(a2).
    private static final int[] FRAME_TABLE = {
            0x94, 0x63, 0x64, 0x64, 0x65, 0x65, 0x65, 0x66,
            0x66, 0x66, 0x66, 0x67, 0x67, 0x67, 0x68, 0x68,
            0x95, 0x63, 0x64, 0x64, 0x65, 0x65, 0x65, 0x66,
            0x66, 0x66, 0x66, 0x67, 0x67, 0x67, 0x68, 0x68,
    };

    // ===== Player Y offset table (byte_314F2, sonic3k.asm:66622) =====
    // 16 signed byte offsets applied to player Y during belt animation.
    // Creates bobbing motion as the player traverses the belt's curved surface.
    private static final int[] Y_OFFSET_TABLE = {
            0x14, 0x14, 0x0B, 0x0B, -0x0F, -0x0F, -0x0F, -0x14,
            -0x14, -0x14, -0x14, -0x0C, -0x0C, -0x0C, -0x02, -0x02,
    };

    // ===== Jump velocity constants =====
    // ROM: move.w #-$500,y_vel(a1) (sonic3k.asm:66435)
    private static final short JUMP_VELOCITY = -0x500;
    // ROM: move.w #-$200,y_vel(a1) (sonic3k.asm:66438) — underwater
    private static final short JUMP_VELOCITY_UNDERWATER = -0x200;

    // ===== Auto-movement speed =====
    // ROM: moveq #2,d0 (sonic3k.asm:66413) — pixels per frame
    private static final int AUTO_MOVE_SPEED = 2;

    // ===== Animation counter reset value =====
    // ROM: move.b #7,6(a2) (sonic3k.asm:66396)
    private static final int ANIM_COUNTER_RESET = 7;

    // ===== Frame set toggle mask =====
    // ROM: andi.b #$10,8(a2) (sonic3k.asm:66398)
    private static final int FRAME_SET_MASK = 0x10;

    // ===== Animation phase decay rate =====
    // ROM: addi.b #6,d0 / subi.b #6,d0 (sonic3k.asm:66560-66569)
    private static final int PHASE_DECAY_RATE = 6;

    // ===== Hanging mode phase base =====
    // ROM: $80 = hanging center (sonic3k.asm:66538)
    private static final int HANGING_PHASE_BASE = 0x80;

    // ===== Cooldown timers =====
    // ROM: move.b #60,2(a2) (sonic3k.asm:66442)
    private static final int COOLDOWN_NORMAL = 60;
    // ROM: move.b #90,2(a2) (sonic3k.asm:66445)
    private static final int COOLDOWN_UNDERWATER = 90;

    // ===== Y offset from object for standing/hanging detection =====
    // ROM: addi.w #$14,d0 (sonic3k.asm:66476,66496)
    private static final int Y_OFFSET_STAND = 0x14;
    // ROM: subi.w #$14,d0 (sonic3k.asm:66516,66532)
    private static final int Y_OFFSET_HANG = 0x14;
    // ROM: addi.w #$10,d0 (sonic3k.asm:66479,66519) — detection range height
    private static final int Y_DETECTION_RANGE = 0x10;

    // ===== Rolling radii (applied on jump release) =====
    // ROM: move.b #$E,y_radius(a1) (sonic3k.asm:66451)
    private static final int ROLL_Y_RADIUS = 0x0E;
    // ROM: move.b #7,x_radius(a1) (sonic3k.asm:66452)
    private static final int ROLL_X_RADIUS = 7;

    // ===== Camera culling =====
    // ROM: subi.w #$280,d0 (sonic3k.asm:66358) — camera range margin
    private static final int CAMERA_MARGIN = 0x280;

    // ===== Mapping frame constants =====
    // ROM: move.b #$63,mapping_frame(a1) (sonic3k.asm:66499) — standing idle
    private static final int FRAME_STAND_IDLE = 0x63;
    // ROM: move.b #$65,mapping_frame(a1) (sonic3k.asm:66537) — hanging idle
    private static final int FRAME_HANG_IDLE = 0x65;

    // ===== Per-player state =====
    // ROM stores this at $32(a0) with offsets per player.
    // Each player gets: active(1), unused(1), cooldown(1), unused(1),
    //                    phase(1), unused(1), animCounter(1), unused(1), frameSetOffset(1)
    private final PlayerBeltState p1State = new PlayerBeltState();
    private final PlayerBeltState p2State = new PlayerBeltState();

    // ===== Instance fields =====
    private int objY;                     // Object Y position
    private boolean flipped;              // status bit 0: 0=rightward, 1=leftward
    private int leftBound;                // $3C(a0): left X from table
    private int rightBound;               // $3E(a0): right X from table
    private int activeLeftBound;          // $40(a0): adjusted left X for detection
    private int activeRightBound;         // $42(a0): adjusted right X for detection
    private int rawSubtype;               // Full subtype byte for load-array indexing
    private int subtypeIndex;             // Low nibble used by the bounds table
    private boolean initialized = false;

    public HCZConveyorBeltObjectInstance(ObjectSpawn spawn) {
        super(spawn, "HCZConveyorBelt");
        this.objY = spawn.y();
        this.flipped = (spawn.renderFlags() & 0x01) != 0;
        this.rawSubtype = spawn.subtype() & 0xFF;
        this.subtypeIndex = rawSubtype & 0x0F;

        int[] bounds = BELT_BOUNDS[subtypeIndex];
        this.leftBound = bounds[0];
        this.rightBound = bounds[1];

        // ROM: Adjust active bounds based on orientation (sonic3k.asm:66328-66341)
        if (!flipped) {
            // Normal orientation: subtract 8 from left bound
            this.activeLeftBound = leftBound - 8;
            this.activeRightBound = rightBound;
        } else {
            // Flipped orientation: add 8 to right bound
            this.activeLeftBound = leftBound;
            this.activeRightBound = rightBound + 8;
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Self-contained: all state (including the {@code final} bound fields) is
     * derived deterministically from the captured spawn, so re-running the constructor
     * reproduces it exactly. Replaces the former explicit dynamic restore path
     * (Phase-2 codec-deletion batch 2).
     */

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        // ROM: First-frame initialization (sonic3k.asm:66306-66342)
        if (!initialized) {
            initialized = true;
            // ROM: tst.b (a1,d0.w) / beq.s loc_31186 — check if already loaded
            if (loadArray[rawSubtype]) {
                // Another instance is already loaded — delete self
                // ROM: loc_31180 clears respawn_addr bit 7 before
                // Delete_Current_Sprite (sonic3k.asm:66317-66323).
                setDestroyedByOffscreen();
                return;
            }
            // ROM: move.b #1,(a1,d0.w) — mark as loaded
            loadArray[rawSubtype] = true;
        }

        NativePlayerSlots slots = nativePlayerSlots(playerEntity);

        // ROM: loc_311C4 (sonic3k.asm:66344-66365)
        // Process Player 1
        if (slots.p1 != null) {
            processPlayer(slots.p1, p1State, vIntRunCount);
        }

        // Process native Player 2 only. Extended engine sidekicks need their own
        // per-sidekick state before they can safely participate in this object.
        if (slots.p2 != null) {
            processPlayer(slots.p2, p2State, vIntRunCount);
        }

        // Camera culling (sonic3k.asm:66355-66364)
        // ROM reads Camera_X_pos_coarse_back, which Load_Sprites recomputes as
        // (Camera_X_pos - $80) & $FF80 before Process_Sprites.
        int cameraX = ((services().camera().getX() & 0xFFFF) - 0x80) & 0xFF80;
        int leftCheck = (leftBound & 0xFF80) - CAMERA_MARGIN;
        if (cameraX < leftCheck) {
            unloadBelt();
            return;
        }
        int rightCheck = rightBound & 0xFF80;
        if (cameraX > rightCheck) {
            unloadBelt();
        }
    }

    private NativePlayerSlots nativePlayerSlots(PlayableEntity updatePlayer) {
        ObjectPlayerQuery query = services().playerQuery();
        PlayableEntity main = query.mainPlayerOrNull();
        if (!(main instanceof AbstractPlayableSprite) && updatePlayer instanceof AbstractPlayableSprite) {
            main = updatePlayer;
        }

        AbstractPlayableSprite p1 = (main instanceof AbstractPlayableSprite sprite) ? sprite : null;
        AbstractPlayableSprite p2 = null;
        for (PlayableEntity candidate : query.playersFor(ObjectPlayerParticipationPolicy.NATIVE_P1_P2)) {
            if (candidate == main || !(candidate instanceof AbstractPlayableSprite sprite)) {
                continue;
            }
            p2 = sprite;
            break;
        }
        if (p2 == p1) {
            p2 = null;
        }
        return new NativePlayerSlots(p1, p2);
    }

    private record NativePlayerSlots(AbstractPlayableSprite p1, AbstractPlayableSprite p2) {
    }

    /**
     * Processes interaction for a single player against this conveyor belt.
     * <p>
     * ROM: sub_31226 (sonic3k.asm:66384-66547).
     */
    private void processPlayer(AbstractPlayableSprite player, PlayerBeltState state,
                               int vIntRunCount) {
        if (state.active) {
            // Player is currently on the belt — handle input and movement
            processOnBelt(player, state, vIntRunCount);
        } else {
            // Player is not on the belt — check for capture
            processOffBelt(player, state, vIntRunCount);
        }
    }

    /**
     * Handles player input and movement while on the conveyor belt.
     * <p>
     * ROM: sub_31226, active path (sonic3k.asm:66384-66457).
     */
    private void processOnBelt(AbstractPlayableSprite player, PlayerBeltState state,
                               int vIntRunCount) {
        // ROM: tst.w (Debug_placement_mode).w (sonic3k.asm:66387-66388)
        if (player.isDebugMode()) {
            releaseBelt(player, state, vIntRunCount);
            return;
        }
        // ROM: cmpi.b #4,routine(a1) (sonic3k.asm:66389-66390)
        if (player.isHurt() || player.getDead()) {
            releaseBelt(player, state, vIntRunCount);
            return;
        }

        // ROM: Left input (sonic3k.asm:66391-66399)
        if (player.isLeftPressed()) {
            // ROM: subq.w #1,x_pos(a1)
            NativePositionOps.addXPosPreserveSubpixel(player, -1);
            advanceAnimOnInput(state);
        }

        // ROM: Right input (sonic3k.asm:66400-66408)
        if (player.isRightPressed()) {
            // ROM: addq.w #1,x_pos(a1)
            NativePositionOps.addXPosPreserveSubpixel(player, 1);
            advanceAnimOnInput(state);
        }

        // ROM: loc_311C4 loads Ctrl_1_logical / Ctrl_2_logical into d1, then
        // sub_31226 tests the low-byte A/B/C press bits (sonic3k.asm:66344-66354,
        // 66411-66412). Object execution happens after player movement in the
        // engine, so the transient raw edge may already be consumed; retain the
        // ROM-visible logical word published for this update instead.
        if (player.isLogicalJumpPressActive()) {
            // ROM: loc_312C0 (sonic3k.asm:66434-66438)
            if (player.isInWater()) {
                player.setYSpeed(JUMP_VELOCITY_UNDERWATER);
            } else {
                player.setYSpeed(JUMP_VELOCITY);
            }
            releaseBelt(player, state, vIntRunCount);
            return;
        }

        // ROM: Auto-movement (sonic3k.asm:66413-66419)
        int autoMove = AUTO_MOVE_SPEED;
        if (flipped) {
            // ROM: btst #0,status(a0) / beq.s / neg.w d0 (sonic3k.asm:66414-66416)
            autoMove = -autoMove;
        }
        NativePositionOps.addXPosPreserveSubpixel(player, autoMove);

        // ROM: Bounds check (sonic3k.asm:66420-66424)
        int playerX = player.getCentreX() & 0xFFFF;
        if (playerX < activeLeftBound || playerX >= activeRightBound) {
            // Player has moved off the belt
            releaseBelt(player, state, vIntRunCount);
            return;
        }

        // ROM: Update animation and DPLC (sonic3k.asm:66425-66431)
        updateBeltAnimation(player, state);
    }

    /**
     * Checks for player capture when not currently on the belt.
     * <p>
     * ROM: loc_31322 (sonic3k.asm:66460-66547).
     */
    private void processOffBelt(AbstractPlayableSprite player, PlayerBeltState state,
                                int vIntRunCount) {
        // ROM: tst.b 2(a2) / beq.s loc_3132E (sonic3k.asm:66460-66464)
        if (state.cooldownTimer > 0) {
            state.cooldownTimer--;
            return;
        }

        // ROM: X bounds check (sonic3k.asm:66468-66472)
        int playerX = player.getCentreX() & 0xFFFF;
        if (playerX < activeLeftBound || playerX >= activeRightBound) {
            return;
        }

        // ROM: cmpi.w #1,ground_vel(a1) / beq.w loc_313D6 (sonic3k.asm:66473-66474)
        if (player.getGSpeed() == 1) {
            tryCapturHanging(player, state, vIntRunCount);
            return;
        }

        // ROM: Standing-on-top detection (sonic3k.asm:66475-66511)
        tryCapturStanding(player, state, vIntRunCount);
    }

    /**
     * Attempts to capture the player standing on top of the belt.
     * <p>
     * ROM: loc_3132E, standing path (sonic3k.asm:66475-66511).
     */
    private void tryCapturStanding(AbstractPlayableSprite player, PlayerBeltState state,
                                   int vIntRunCount) {
        int playerY = player.getCentreY() & 0xFFFF;

        // ROM: Y range check for standing on top
        // move.w y_pos(a0),d0 / addi.w #$14,d0 / cmp.w y_pos(a1),d0 / bhs.s locret
        int standTop = (objY + Y_OFFSET_STAND) & 0xFFFF;
        if (playerY <= standTop) {
            return;
        }
        // ROM: addi.w #$10,d0 / cmp.w y_pos(a1),d0 / blo.s locret
        // blo branches when d0 < playerY, so valid range includes playerY == standBottom
        int standBottom = (standTop + Y_DETECTION_RANGE) & 0xFFFF;
        if (playerY > standBottom) {
            return;
        }

        // ROM: State checks (sonic3k.asm:66482-66489)
        if (player.isDebugMode()) return;
        if (player.isHurt() || player.getDead()) return;
        if (player.isObjectControlled()) return;
        if (player.getYSpeed() < 0) return;

        // Capture player — standing on top (sonic3k.asm:66490-66511)
        capturePlayer(player, state, standTop, FRAME_STAND_IDLE, 0);
    }

    /**
     * Attempts to capture the player hanging below the belt.
     * <p>
     * ROM: loc_313D6 (sonic3k.asm:66514-66547).
     * Entered when {@code ground_vel == 1} (set by fan push).
     */
    private void tryCapturHanging(AbstractPlayableSprite player, PlayerBeltState state,
                                  int vIntRunCount) {
        int playerY = player.getCentreY() & 0xFFFF;

        // ROM: Y range check for hanging below
        // move.w y_pos(a0),d0 / subi.w #$14,d0
        int hangTop = (objY - Y_OFFSET_HANG) & 0xFFFF;
        if (playerY <= hangTop) {
            return;
        }
        // ROM: blo branches when d0 < playerY, so valid range includes playerY == hangBottom
        int hangBottom = (hangTop + Y_DETECTION_RANGE) & 0xFFFF;
        if (playerY > hangBottom) {
            return;
        }

        // ROM: State checks (sonic3k.asm:66522-66527)
        if (player.isDebugMode()) return;
        if (player.isHurt() || player.getDead()) return;
        if (player.isObjectControlled()) return;

        // Capture player — hanging below (sonic3k.asm:66528-66547)
        // ROM: move.b #$80,4(a2) — phase starts at hanging center
        capturePlayer(player, state, hangTop, FRAME_HANG_IDLE, HANGING_PHASE_BASE);
    }

    /**
     * Captures the player onto the conveyor belt (shared between standing and hanging).
     * <p>
     * ROM: loc_3132E/loc_313D6 capture sequences (sonic3k.asm:66490-66547).
     */
    private void capturePlayer(AbstractPlayableSprite player, PlayerBeltState state,
                               int snapY, int initialFrame, int initialPhase) {
        // ROM: clr.w x_vel(a1) / clr.w y_vel(a1) / clr.w ground_vel(a1)
        player.setXSpeed((short) 0);
        player.setYSpeed((short) 0);
        player.setGSpeed((short) 0);

        // ROM: andi.b #$FC,render_flags(a1) — clear facing and V-flip
        player.setRenderFlips(false, false);

        // ROM: move.w d0,y_pos(a1) — snap to belt surface
        NativePositionOps.writeYPosPreserveSubpixel(player, snapY);

        // ROM: move.b #0,anim(a1) — idle animation base
        player.setAnimationId(0);

        // ROM: move.b #3,object_control(a1) — bits 0-6, movement suppressed but CPU/touch remain allowed.
        ObjectControlState.nativeBits0To6CpuAllowedMovementSuppressed().applyTo(player);

        // ROM: move.b #$63/$65,mapping_frame(a1) — initial frame
        player.setObjectMappingFrameControl(true);
        player.setMappingFrame(initialFrame);

        // ROM: Initialize per-player state bytes (sonic3k.asm:66500-66503, 66538-66541)
        state.active = true;
        state.capturedPlayer = player;
        state.phase = initialPhase;
        state.animCounter = 0;
        state.frameSetOffset = 0;
    }

    /**
     * Releases the player from the conveyor belt (jump or exit).
     * <p>
     * ROM: loc_312D4 (sonic3k.asm:66440-66457).
     */
    private void releaseBelt(AbstractPlayableSprite player, PlayerBeltState state,
                             int vIntRunCount) {
        int releaseCentreY = player.getCentreY() & 0xFFFF;
        // ROM: loc_312D4 does NOT modify ground_vel on release. The alternation
        // between top/bottom surfaces is driven by external forces (the HCZ fan)
        // setting ground_vel=1 each frame while the player is in its push column.
        state.active = false;
        state.capturedPlayer = null;

        // ROM: move.b #60,2(a2) / btst #Status_Underwater / move.b #90,2(a2)
        state.cooldownTimer = player.isInWater() ? COOLDOWN_UNDERWATER : COOLDOWN_NORMAL;

        // ROM: andi.b #$FC,object_control(a1) — clear object control
        player.releaseFromObjectControl(vIntRunCount);

        // ROM: bset #Status_InAir,status(a1) (sonic3k.asm:66449)
        player.setAir(true);

        // ROM: move.b #1,jumping(a1) (sonic3k.asm:66450)
        player.setJumping(true);

        // ROM: move.b #$E,y_radius / #7,x_radius (sonic3k.asm:66451-66452)
        player.applyCustomRadii(ROLL_X_RADIUS, ROLL_Y_RADIUS);

        // ROM: move.b #2,anim(a1) (sonic3k.asm:66453) — rolling animation
        player.setAnimationId(2);

        // ROM: bset #Status_Roll,status(a1) (sonic3k.asm:66454)
        player.setRolling(true);
        // Engine setRolling(true) shrinks top-left-based sprite dimensions, but
        // ROM loc_312D4 only writes radii/status/anim and leaves y_pos unchanged.
        NativePositionOps.writeYPosPreserveSubpixel(player, releaseCentreY);

        // ROM: bclr #Status_RollJump,status(a1) (sonic3k.asm:66455)
        player.setRollingJump(false);

        // ROM: move.b #0,flip_angle(a1) (sonic3k.asm:66456)
        player.setFlipAngle(0);

        // Release mapping frame control back to animation system
        player.setObjectMappingFrameControl(false);
    }

    /**
     * Advances animation state when the player presses left or right input.
     * <p>
     * ROM: Input-driven animation advance (sonic3k.asm:66394-66398, 66404-66408).
     */
    private void advanceAnimOnInput(PlayerBeltState state) {
        // ROM: subq.b #1,6(a2) / bpl.s (sonic3k.asm:66394-66395)
        state.animCounter--;
        if (state.animCounter >= 0) {
            return;
        }
        // ROM: move.b #7,6(a2)
        state.animCounter = ANIM_COUNTER_RESET;
        // ROM: addi.b #$10,8(a2) / andi.b #$10,8(a2)
        state.frameSetOffset = (state.frameSetOffset + FRAME_SET_MASK) & FRAME_SET_MASK;
    }

    /**
     * Updates player animation frame and Y position based on conveyor belt state.
     * <p>
     * ROM: sub_3145A (sonic3k.asm:66554-66616).
     * <p>
     * Two modes determined by {@code ground_vel}:
     * <ul>
     *   <li>{@code ground_vel == 0}: Phase decays toward 0 (standing center)</li>
     *   <li>{@code ground_vel != 0}: Phase decays toward $80 (hanging center); ground_vel cleared.
     *       This is set by the HCZ fan object when the player passes through its push area
     *       while on the belt.</li>
     * </ul>
     */
    private void updateBeltAnimation(AbstractPlayableSprite player, PlayerBeltState state) {
        int gSpeed = player.getGSpeed();

        if (gSpeed == 0) {
            // ROM: tst.w ground_vel(a1) / bne.s loc_31480 (sonic3k.asm:66555-66556)
            // Normal mode: phase decays toward 0 (standing center)
            int phase = state.phase & 0xFF;
            if (phase != 0) {
                if ((phase & 0x80) != 0) {
                    // ROM: Negative range (128-255): add toward 0 (wrapping around)
                    // addi.b #6,d0 / bcc.s / moveq #0,d0 (sonic3k.asm:66560-66562)
                    phase = (phase + PHASE_DECAY_RATE) & 0xFF;
                    if (phase < PHASE_DECAY_RATE) {
                        // Carry: wrapped past 0
                        phase = 0;
                    }
                } else {
                    // ROM: Positive range (1-127): subtract toward 0
                    // subi.b #6,d0 / bcc.s / moveq #0,d0 (sonic3k.asm:66568-66570)
                    phase = phase - PHASE_DECAY_RATE;
                    if (phase < 0) {
                        phase = 0;
                    }
                }
                state.phase = phase;
            }
        } else {
            // ROM: loc_31480 (sonic3k.asm:66580-66601)
            // Belt-driven mode (fan active): phase decays toward $80 (hanging center)
            // ROM: clr.w ground_vel(a1) (sonic3k.asm:66581)
            player.setGSpeed((short) 0);

            int phase = state.phase & 0xFF;
            int offset = (phase - HANGING_PHASE_BASE) & 0xFF;
            // Interpret as signed byte relative to $80
            int signedOffset = (byte) offset;

            if (signedOffset != 0) {
                if (signedOffset < 0) {
                    // Below $80: add toward $80
                    // ROM: addi.b #6,d0 / bcc.s / moveq #0,d0 (sonic3k.asm:66586-66588)
                    offset = (offset + PHASE_DECAY_RATE) & 0xFF;
                    if ((byte) offset > 0 || offset == 0) {
                        offset = 0;
                    }
                } else {
                    // Above $80: subtract toward $80
                    // ROM: subi.b #6,d0 / bcc.s / moveq #0,d0 (sonic3k.asm:66594-66596)
                    offset = offset - PHASE_DECAY_RATE;
                    if (offset < 0) {
                        offset = 0;
                    }
                }
                // ROM: addi.b #$80,d0 (sonic3k.asm:66600)
                state.phase = (offset + HANGING_PHASE_BASE) & 0xFF;
            }
        }

        // ROM: Frame lookup (sonic3k.asm:66603-66615)
        int phaseIndex = (state.phase & 0xFF) >>> 4;  // upper nibble
        int tableIndex = phaseIndex + state.frameSetOffset;
        if (tableIndex >= FRAME_TABLE.length) {
            tableIndex = FRAME_TABLE.length - 1;
        }

        // ROM: move.b byte_314D2(pc,d0.w),d1 / move.b d1,mapping_frame(a1)
        int mappingFrame = FRAME_TABLE[tableIndex];
        player.setMappingFrame(mappingFrame);

        // ROM: andi.w #$F,d0 / move.b byte_314F2(pc,d0.w),d1
        int yIndex = phaseIndex & 0x0F;
        int yOffset = Y_OFFSET_TABLE[yIndex];

        // ROM: ext.w d1 / add.w y_pos(a0),d1 / move.w d1,y_pos(a1)
        NativePositionOps.writeYPosPreserveSubpixel(player, objY + yOffset);
    }

    /**
     * Unloads the belt object when it goes off-screen.
     * <p>
     * ROM: loc_31204 (sonic3k.asm:66368-66379).
     */
    private void unloadBelt() {
        // ROM: move.b #0,(a1,d0.w) — clear load flag
        loadArray[rawSubtype] = false;

        // Release any captured players
        // (In the original, players are released by the state checks in processOnBelt
        //  on the next frame. We proactively release here for safety.)
        // ROM: loc_31204 clears respawn_addr bit 7 before Delete_Current_Sprite
        // (sonic3k.asm:66656-66665), so the placement remains re-spawnable.
        setDestroyedByOffscreen();
    }

    /**
     * Prevents the ObjectManager's standard out-of-range check from unloading this
     * belt. The belt can be very wide (leftBound to rightBound, up to 688px) but
     * {@link #getX()} returns leftBound, so the ObjectManager's isOutOfRangeS1
     * check (which uses a 640px window from getX()) may kill the belt when the
     * camera is near the right edge. The belt handles its own camera culling
     * correctly using both leftBound and rightBound with a 0x280 margin in
     * {@link #update}, so the external check must be bypassed entirely.
     */
    @Override
    public boolean isPersistent() {
        return true;
    }

    /**
     * Called by ObjectManager when the belt is unloaded (out-of-range or level teardown).
     * Must release any captured players to prevent them getting stuck with
     * {@code objectControlled=true} and no controlling object.
     */
    @Override
    public void onUnload() {
        releaseAllCapturedPlayers();
        loadArray[rawSubtype] = false;
    }

    @Override
    public void setDestroyed(boolean destroyed) {
        if (destroyed && !isDestroyed()) {
            releaseAllCapturedPlayers();
            loadArray[rawSubtype] = false;
        }
        super.setDestroyed(destroyed);
    }

    /**
     * Releases all captured players. Called from both {@link #onUnload()} and
     * {@link #setDestroyed} to ensure players are never left stuck.
     */
    private void releaseAllCapturedPlayers() {
        safeReleaseCapturedPlayer(p1State);
        safeReleaseCapturedPlayer(p2State);
    }

    /**
     * Safely releases a captured player if this belt is being removed.
     * Restores the player to a valid airborne state so physics can resume.
     */
    private void safeReleaseCapturedPlayer(PlayerBeltState state) {
        if (state.active && state.capturedPlayer != null) {
            ObjectControlState.none().applyTo(state.capturedPlayer);
            state.capturedPlayer.setObjectMappingFrameControl(false);
            state.capturedPlayer.setAir(true);
            state.active = false;
            state.capturedPlayer = null;
        }
    }

    // ===== Rendering =====
    // This object has no visual representation — the belt is rendered by level geometry.

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        // No rendering — pure logic object
    }

    @Override
    public int getX() { return leftBound; }

    @Override
    public int getY() { return objY; }

    @Override
    public ObjectSpawn getSpawn() {
        return buildSpawnAt(leftBound, objY);
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        if (ctx == null) return;

        // Draw active detection bounds
        int width = (activeRightBound - activeLeftBound) / 2;
        int centreX = activeLeftBound + width;
        ctx.drawRect(centreX, objY, width, Y_OFFSET_STAND + Y_DETECTION_RANGE / 2,
                0.8f, 0.6f, 0.2f);

        // Draw orientation arrow
        String dir = flipped ? "<-- LEFT" : "RIGHT -->";
        ctx.drawWorldLabel(centreX, objY - 20, 0, "Belt " + subtypeIndex + " " + dir,
                DebugColor.YELLOW);

        // Show per-player state
        if (p1State.active) {
            ctx.drawWorldLabel(centreX, objY + 16, 0,
                    "P1: ph=" + Integer.toHexString(p1State.phase & 0xFF),
                    DebugColor.GREEN);
        }
    }

    /**
     * Resets the shared load array. Must be called on level reset/load.
     */
    public static void resetLoadArray() {
        java.util.Arrays.fill(loadArray, false);
    }

    // ===== Per-player belt state =====

    /**
     * Tracks per-player conveyor belt interaction state.
     * <p>
     * ROM layout at $32(a0) per player (sonic3k.asm):
     * <pre>
     * Offset 0: active flag (0=off belt, 1=on belt)
     * Offset 2: cooldown timer (frames before re-capture)
     * Offset 4: animation phase byte (0=standing center, $80=hanging center)
     * Offset 6: animation frame counter (counts down from 7)
     * Offset 8: frame set offset (0 or $10, toggles between two frame sets)
     * </pre>
     */
    private static class PlayerBeltState {
        boolean active;       // byte 0(a2): on belt flag
        int cooldownTimer;    // byte 2(a2): re-capture cooldown
        int phase;            // byte 4(a2): animation phase (0-255)
        int animCounter;      // byte 6(a2): frame animation counter
        int frameSetOffset;   // byte 8(a2): 0 or $10
        AbstractPlayableSprite capturedPlayer;  // reference for safe cleanup
    }
}
