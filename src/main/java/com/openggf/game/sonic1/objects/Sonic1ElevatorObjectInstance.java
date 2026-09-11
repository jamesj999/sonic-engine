package com.openggf.game.sonic1.objects;

import com.openggf.game.PlayableEntity;

import com.openggf.debug.DebugRenderContext;
import com.openggf.game.solid.SolidCheckpointBatch;
import com.openggf.game.sonic1.constants.Sonic1ObjectIds;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidExecutionMode;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * Object 0x59 -- Platforms that move when you stand on them (SLZ).
 * <p>
 * A top-solid platform that sits idle until the player stands on it, then
 * accelerates in a direction determined by its subtype. The platform uses
 * an acceleration/deceleration movement model (Elev_Move): speed ramps up
 * from 0 to $800 in increments of $10/frame, then decelerates back to 0
 * when the halfway point is reached.
 * <p>
 * When subtype bit 7 is set, the object acts as a spawner (Elev_MakeMulti):
 * it periodically creates new elevator instances with subtype $0E (type 9 —
 * move up then delete).
 * <p>
 * <b>Subtype encoding (normal, bit 7 clear):</b>
 * <ul>
 *   <li>Bits 4-6: Index into Elev_Var1 (width/frame) — only entry 0: width=$28, frame=0</li>
 *   <li>Bits 0-3: Index into Elev_Var2 (distance/action type)</li>
 * </ul>
 * <p>
 * <b>Action types (from Elev_Types):</b>
 * <ul>
 *   <li>0: Stationary</li>
 *   <li>1: Wait for player, then activate (used as trigger for types 2/4/6/8)</li>
 *   <li>2: Move upward (negative Y offset from origin)</li>
 *   <li>3: Same as type 1 (trigger)</li>
 *   <li>4: Move downward (positive Y offset from origin)</li>
 *   <li>5: Same as type 1 (trigger)</li>
 *   <li>6: Diagonal — half-Y up + X right</li>
 *   <li>7: Same as type 1 (trigger)</li>
 *   <li>8: Diagonal — half-Y down + X left</li>
 *   <li>9: Move upward, then delete when distance*2 reached</li>
 * </ul>
 * <p>
 * <b>Disassembly reference:</b> docs/s1disasm/_incObj/59 SLZ Elevators.asm
 */
public class Sonic1ElevatorObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener, SpawnRewindRecreatable {

    // From disassembly Elev_Var1: dc.b $28, 0
    private static final int HALF_WIDTH_DEFAULT = 0x28;

    // Platform surface height (thin platform for solid contact)
    private static final int HALF_HEIGHT = 0x08;
    // MvSonicOnPtfm2 uses a 9px standing snap for continued riding.
    private static final int GROUND_HALF_HEIGHT = 0x09;

    // From disassembly: move.b #4,obPriority(a0)
    private static final int PRIORITY = 4;

    // Elev_Move: addi.w #$10,d0 — acceleration per frame
    private static final int ACCELERATION = 0x10;

    // Elev_Move: cmpi.w #$800,d0 — maximum speed
    private static final int MAX_SPEED = 0x800;

    // Elev_Var2 table: (distance_byte, action_type) per subtype index
    // From disassembly lines 24-38
    private static final byte[][] ELEV_VAR2 = {
            {0x10, 1}, {0x20, 1}, {0x34, 1},   // subtypes 0-2
            {0x10, 3}, {0x20, 3}, {0x34, 3},   // subtypes 3-5
            {0x14, 1}, {0x24, 1}, {0x2C, 1},   // subtypes 6-8
            {0x14, 3}, {0x24, 3}, {0x2C, 3},   // subtypes 9-11
            {0x20, 5}, {0x20, 7}, {0x30, 9},   // subtypes 12-14
    };

    // Saved original positions (elev_origX = objoff_32, elev_origY = objoff_30)
    private int origX;
    private int origY;

    // Half-width for platform collision
    private int halfWidth;

    // Current dynamic position
    private int x;
    private int y;

    // Distance to travel (elev_dist = objoff_3C), already multiplied by 4
    private int elevDist;

    // Current action type (low nybble of effective obSubtype after Elev_Var2 lookup)
    private int actionType;

    // Whether this is a spawner (Elev_MakeMulti mode, bit 7 of original subtype)
    private boolean isSpawner;

    // Spawner fields (Elev_MakeMulti: routine 6)
    private int spawnTimer;       // elev_dist when in spawner mode
    private int spawnInterval;    // objoff_3E: reset value

    // Elev_Move state: 32-bit position accumulator (objoff_34 as 16.16 fixed point)
    // objoff_34 = 32-bit position accumulator
    private int movePosAccum;

    // objoff_38 = current speed (word)
    private int moveSpeed;

    // objoff_3A = deceleration flag (byte): 0 = accelerating, 1 = decelerating
    private boolean decelerating;

    // Routine state: 2 = platform (waiting), 4 = action (riding)
    private int routine;

    /**
     * Creates a normal elevator from a level placement spawn.
     */
    public Sonic1ElevatorObjectInstance(ObjectSpawn spawn) {
        super(spawn, "Elevator");

        int subtype = spawn.subtype() & 0xFF;
        this.origX = spawn.x();
        this.origY = spawn.y();
        this.x = origX;
        this.y = origY;

        if ((subtype & 0x80) != 0) {
            // Elev_MakeMulti mode (routine 6)
            this.isSpawner = true;
            this.halfWidth = HALF_WIDTH_DEFAULT;
            // andi.w #$7F,d0 / mulu.w #6,d0
            int spawnerValue = (subtype & 0x7F) * 6;
            this.spawnTimer = spawnerValue;
            this.spawnInterval = spawnerValue;
            this.elevDist = 0;
            this.actionType = 0;
            this.routine = 6;
        } else {
            this.isSpawner = false;

            // Elev_Var1 lookup: lsr.w #3,d0 / andi.w #$1E,d0
            // Only offset 0 is possible for subtypes 0-14, yielding width=$28, frame=0
            this.halfWidth = HALF_WIDTH_DEFAULT;

            // Elev_Var2 lookup: add.w d0,d0 / andi.w #$1E,d0
            int var2Index = (subtype * 2) & 0x1E;
            var2Index >>= 1; // convert byte offset back to array index
            if (var2Index >= ELEV_VAR2.length) {
                var2Index = 0;
            }
            byte distByte = ELEV_VAR2[var2Index][0];
            byte typeByte = ELEV_VAR2[var2Index][1];

            // lsl.w #2,d0 — distance byte * 4
            this.elevDist = (distByte & 0xFF) << 2;
            this.actionType = typeByte & 0xFF;
            this.routine = 2;
        }

        this.movePosAccum = 0;
        this.moveSpeed = 0;
        this.decelerating = false;

        updateDynamicSpawn(x, y);
    }

    /**
     * Creates a dynamically spawned elevator (from Elev_MakeMulti).
     * These always have subtype $0E (type 9: move up then delete).
     */
    public Sonic1ElevatorObjectInstance(int spawnX, int spawnY) {
        super(new ObjectSpawn(spawnX, spawnY, Sonic1ObjectIds.SLZ_ELEVATOR, 0x0E, 0, false, 0),
                "Elevator");

        this.origX = spawnX;
        this.origY = spawnY;
        this.x = spawnX;
        this.y = spawnY;
        this.isSpawner = false;
        this.halfWidth = HALF_WIDTH_DEFAULT;

        // Subtype $0E: var2Index = ($0E * 2) & $1E / 2 = $1C / 2 = 14 -> entry [14] = {$30, 9}
        this.elevDist = 0x30 << 2; // $C0
        this.actionType = 9;
        this.routine = 2;

        this.movePosAccum = 0;
        this.moveSpeed = 0;
        this.decelerating = false;

        updateDynamicSpawn(x, y);
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
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (isDestroyed()) {
            return;
        }

        if (isSpawner) {
            updateSpawner();
            return;
        }

        if (routine == 4) {
            // ROM Elev_Action runs ExitPlatform, then movement, then MvSonicOnPtfm2.
            // Resolve the solid contact after movement so the riding player is
            // snapped to the platform's moved position in the same frame.
            //
            // ExitPlatform (docs/s1disasm/_incObj/sub ExitPlatform.asm:9-39) tests the
            // player's pre-move state: if airborne (jumped off) OR horizontally outside
            // the platform width, it does `move.b #2,obRoutine(a0)` -- reverting the
            // elevator to Elev_Platform (routine 2). Capture that condition before the
            // move (ExitPlatform runs first in Elev_Action), apply the routine revert
            // after this frame's move+seat (the routine change takes effect next frame,
            // exactly as the ROM continues into Elev_Types/MvSonicOnPtfm2 after setting
            // the routine byte). Without this the elevator stays in routine 4 forever
            // after the first ride, so re-landing detection (checkpointAll) keeps running
            // AFTER the upward move instead of before it (routine 2's PlatformObject
            // order), seating a falling rolling-jump player one frame early
            // (SLZ3 f4026: detect at post-move elevY=0x0103/top 0xFB lands the player,
            // ROM detects at pre-move elevY=0x0105/top 0xFD and stays airborne to f4027).
            boolean exited = playerExitedPlatform(player);
            executeActionTypes(player);
            updateDynamicSpawn(x, y);
            checkpointAll();
            if (exited && !isDestroyed()) {
                routine = 2;
            }
        } else if (routine == 2) {
            // Routine 2 (Elev_Platform): PlatformObject runs before Elev_Types.
            // Manual checkpoints do not invoke the legacy onSolidContact callback, so
            // advance into routine 4 directly from the standing result before type dispatch.
            SolidCheckpointBatch batch = checkpointAll();
            if (hasStandingContact(batch)) {
                routine = 4;
            }
            if (routine == 4) {
                executeActionTypes(player);
            } else {
                executeWaitingTypes(player);
            }
            updateDynamicSpawn(x, y);
        }
    }

    /**
     * ROM ExitPlatform exit test (docs/s1disasm/_incObj/sub ExitPlatform.asm:23-34):
     * the rider has left the platform when airborne (jumped off) or when their X
     * position is outside the platform's [x-halfWidth, x+halfWidth) span.
     */
    private boolean playerExitedPlatform(AbstractPlayableSprite player) {
        if (player == null) {
            return false;
        }
        // btst #1,obStatus(a1) / bne .exitedPlatform -- airborne riders exit immediately.
        if (player.getAir()) {
            return true;
        }
        // move.w obX(a1),d0 / sub.w obX(a0),d0 / add.w d1,d0 / bmi .exited;
        // cmp.w d2,d0 / blo .return  (d1 = obActWid, d2 = 2*obActWid)
        int rel = player.getCentreX() - x + halfWidth;
        return rel < 0 || rel >= (halfWidth * 2);
    }

    /**
     * In routine 2, type 1/3/5/7 check if player is standing and increment subtype.
     * This transitions from "wait" (odd type) to "move" (even type).
     */
    private void executeWaitingTypes(AbstractPlayableSprite player) {
        switch (actionType & 0x0F) {
            case 0 -> { /* type 0: stationary, nothing to do */ }
            case 1, 3, 5, 7 -> {
                // These types do nothing in routine 2 — the transition happens
                // when the player stands on the platform (routine changes to 4),
                // and then type 1 increments actionType to 2.
            }
            case 2 -> applyType02();
            case 4 -> applyType04();
            case 6 -> applyType06();
            case 8 -> applyType08();
            case 9 -> applyType09(player);
        }
    }

    /**
     * Routine 4: ExitPlatform + movement + MvSonicOnPtfm2.
     * This routine handles active movement while the player is riding.
     */
    private void executeActionTypes(AbstractPlayableSprite player) {
        switch (actionType & 0x0F) {
            case 0 -> { /* type 0: stationary */ }
            case 1, 3, 5, 7 -> {
                // cmpi.b #4,obRoutine(a0) / bne.s .notstanding
                // We're in routine 4, so player IS standing
                // addq.b #1,obSubtype(a0) — increment to the next even type
                actionType++;
            }
            case 2 -> applyType02();
            case 4 -> applyType04();
            case 6 -> applyType06();
            case 8 -> applyType08();
            case 9 -> applyType09(player);
        }
    }

    /**
     * Type 02: Move upward. Y = origY - moveOffset.
     * From disassembly: bsr Elev_Move / neg.w d0 / add.w elev_origY,d0 / move.w d0,obY
     */
    private void applyType02() {
        elevMove();
        int offset = (movePosAccum >> 16) & 0xFFFF;
        // neg.w d0
        y = origY + (short) (-offset);
    }

    /**
     * Type 04: Move downward. Y = origY + moveOffset.
     * From disassembly: bsr Elev_Move / add.w elev_origY,d0 / move.w d0,obY
     */
    private void applyType04() {
        elevMove();
        int offset = (movePosAccum >> 16) & 0xFFFF;
        y = origY + (short) offset;
    }

    /**
     * Type 06: Diagonal — half-Y up + X right.
     * From disassembly: asr.w #1,d0 for Y, full d0 for X.
     * Y = origY - (moveOffset/2), X = origX + moveOffset
     */
    private void applyType06() {
        elevMove();
        int offset = (short) ((movePosAccum >> 16) & 0xFFFF);
        // asr.w #1,d0 / neg.w d0 / add.w elev_origY,d0
        y = origY + (short) (-(offset >> 1));
        // Full offset for X: add.w elev_origX,d0
        x = origX + (short) offset;
    }

    /**
     * Type 08: Diagonal — half-Y down + X left.
     * From disassembly: asr.w #1,d0 for Y (no negate), neg d0 for X.
     * Y = origY + (moveOffset/2), X = origX - moveOffset
     */
    private void applyType08() {
        elevMove();
        int offset = (short) ((movePosAccum >> 16) & 0xFFFF);
        // asr.w #1,d0 / add.w elev_origY,d0
        y = origY + (short) (offset >> 1);
        // neg.w d0 / add.w elev_origX,d0
        x = origX + (short) (-offset);
    }

    /**
     * Type 09: Move upward, delete when subtype becomes 0.
     * When Elev_Move clears obSubtype (at distance*2), type 09 checks
     * if player is riding and detaches them, then deletes the object.
     */
    private void applyType09(AbstractPlayableSprite player) {
        elevMove();
        int offset = (short) ((movePosAccum >> 16) & 0xFFFF);
        // neg.w d0 / add.w elev_origY,d0
        y = origY + (short) (-offset);

        // tst.b obSubtype(a0) / beq.w .typereset
        if (actionType == 0) {
            // .typereset: btst #3,obStatus(a0) / beq.s .delete
            if (isPlayerRiding() && player != null) {
                // bset #1,obStatus(a1) — set player airborne
                // bclr #3,obStatus(a1) — clear standing-on-object
                // move.b #2,obRoutine(a1) — reset player routine
                player.setAir(true);
                var objectManager = services().objectManager();
                if (objectManager != null) {
                    objectManager.clearRidingObject(player);
                }
            }
            // .delete: bra.w DeleteObject
            setDestroyed(true);
        }
    }

    /**
     * Elev_Move subroutine: acceleration/deceleration movement.
     * <p>
     * Speed starts at 0, accelerates by $10/frame up to $800.
     * When position reaches elev_dist, deceleration flag is set and speed
     * decreases by $10/frame back to 0. When position reaches elev_dist*2,
     * the subtype/actionType is cleared to 0 (movement complete).
     * <p>
     * State: moveSpeed (objoff_38), decelerating (objoff_3A),
     *        movePosAccum (objoff_34 as 32-bit fixed point).
     */
    private void elevMove() {
        int speed = moveSpeed;

        if (!decelerating) {
            // cmpi.w #$800,d0 / bhs.s loc_10CD0
            if (speed < MAX_SPEED) {
                // addi.w #$10,d0
                speed += ACCELERATION;
            }
        } else {
            // tst.w d0 / beq.s loc_10CD0
            if (speed > 0) {
                // subi.w #$10,d0
                speed -= ACCELERATION;
            }
        }

        moveSpeed = speed;

        // ext.l d0 / asl.l #8,d0 / add.l objoff_34(a0),d0
        long speedExtended = (int) (short) speed;
        long shifted = speedExtended << 8;
        long newAccum = ((long) movePosAccum) + shifted;
        movePosAccum = (int) newAccum;

        // swap d0 — get high word of accumulator
        int posHighWord = (int) ((newAccum >> 16) & 0xFFFF);

        // cmp.w d2,d0 / bls.s loc_10CF0
        if ((posHighWord & 0xFFFF) > (elevDist & 0xFFFF)) {
            // move.b #1,objoff_3A(a0)
            decelerating = true;
        }

        // add.w d2,d2 / cmp.w d2,d0
        int doubleDist = (elevDist * 2) & 0xFFFF;
        if (posHighWord == doubleDist) {
            // clr.b obSubtype(a0)
            actionType = 0;
        }
    }

    /**
     * Elev_MakeMulti (routine 6): Periodically spawns new elevator objects.
     * Counts down spawnTimer. When it reaches 0, resets to spawnInterval
     * and creates a new elevator at the spawner's position with subtype $0E.
     */
    private void updateSpawner() {
        // subq.w #1,elev_dist(a0)
        spawnTimer--;
        if (spawnTimer <= 0) {
            // move.w objoff_3E(a0),elev_dist(a0)
            spawnTimer = spawnInterval;

            // bsr.w FindFreeObj / bne.s .chkdel
            // _move.b #id_Elevator,obID(a1)
            // move.w obX(a0),obX(a1)
            // move.w obY(a0),obY(a1)
            // move.b #$E,obSubtype(a1)
            if (services().objectManager() != null) {
                spawnFreeChild(() -> new Sonic1ElevatorObjectInstance(origX, origY));
            }
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        // Spawner objects are invisible
        if (isSpawner) {
            return;
        }

        PatternSpriteRenderer renderer = getRenderer(ObjectArtKeys.SLZ_ELEVATOR);
        if (renderer == null) return;

        // Render elevator at current position (single frame: frame 0)
        renderer.drawFrameIndex(0, x, y, false, false);
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(PRIORITY);
    }

    // ---- SolidObjectProvider ----

    @Override
    public SolidObjectParams getSolidParams() {
        return SolidObjectParams.of(halfWidth, HALF_HEIGHT, GROUND_HALF_HEIGHT);
    }

    @Override
    public int getBalanceWidthPixels() {
        // ROM Sonic_Move edge-balance reads the stood-on object's obActWid
        // (docs/s1disasm/_incObj/01 Sonic.asm:414-431). The elevator sets obActWid
        // from Elev_Var1 (= 80/2 = $28; docs/s1disasm/_incObj/59 SLZ Elevators.asm:22,59),
        // which equals halfWidth here. The shared default getBalanceWidthPixels()
        // returns getOnScreenHalfWidth() (16), which is far narrower than the elevator's
        // $28 platform — that shifted the balance window so the player was treated as
        // edge-balancing while centered on the platform, suppressing the ROM look-up/
        // look-down camera pan (SLZ3 f3085: ducking on a rising elevator).
        return halfWidth;
    }

    @Override
    public boolean isTopSolidOnly() {
        return true;
    }

    @Override
    public boolean usesCollisionHalfWidthForTopLanding() {
        // ROM: Elev_Platform / Elev_Action load d1 directly from obActWid before
        // calling PlatformObject / ExitPlatform, so the collision half-width is
        // already the correct Solid_Landed standing width.
        return true;
    }

    @Override
    public boolean rejectsZeroDistanceTopSolidLanding() {
        // ROM Elev_Platform (routine 2) lands the player through PlatformObject
        // (docs/s1disasm/_incObj/59 SLZ Elevators.asm:77-80 ->
        // docs/s1disasm/_incObj/sub PlatformObject.asm:36-52), whose Y land band
        // is gated by an UNSIGNED `cmpi.w #-16,d0 / blo Plat_Exit` (top of platform
        // = obY-8) AFTER a signed `bhi Plat_Exit` on d0>0. That rejects the
        // exact-touch case d0=0 (`0x0000 <u 0xFFF0`): the standable band is d0 in
        // [-16,-1] (strict penetration -- the player's bottom edge must be at least
        // 1px below the obY-8 surface). The engine default accepts detectionDistY=0,
        // so a fast-falling player whose bottom edge reaches exactly the surface
        // seats one frame early. SLZ2 f3927: a rolling-jump player falling at
        // ~18px/frame past the elevator at (0x1B80,0x0368) hits d0=0 at f3927 (feet
        // 0x0360 == surface 0x0368-8=0x0360) -- ROM rejects and keeps falling
        // (y_speed 0x11E0), the engine landed him (air 1->0, g_speed FC0B->FE8C).
        // The elevator was the only PlatformObject-family S1 object missing this
        // override (Obj 18/52/53/5A/63/6C/1A already have it). The elevator's
        // top-landing detection already uses airHalfHeight=8 (= obY-8), matching
        // ROM's subq.w #8, so no getTopLandingSnapAdjustment is needed here.
        return true;
    }

    @Override
    public boolean carriesAirborneRiderAfterExitPlatform() {
        // ROM Elev_Action (docs/s1disasm/_incObj/59 SLZ Elevators.asm:84-101)
        // is structurally identical to MBlock_StandOn (docs/s1disasm/_incObj/52
        // Moving Blocks.asm:65-83): both call ExitPlatform first, then run their
        // movement, then unconditionally jump into MvSonicOnPtfm2
        // (docs/s1disasm/_incObj/15 Swinging Platforms.asm:177-194). The
        // platform-carry runs even after ExitPlatform has cleared the player's
        // on-object bit because the player jumped this frame, so the rider's
        // y_pos still tracks the elevator's post-move position on the launch
        // frame. The Sonic_Jump rolling-radius adjust (sonic.asm:1166
        // addq.w #5, obY(a0)) is applied earlier in the player update and is
        // overwritten by MvSonicOnPtfm2's elevatorY-9-obHeight write.
        //
        // Without this opt-in the engine misses the post-jump pull-up while
        // still applying the +5 adjust, leaving the player a couple of pixels
        // below ROM whenever the elevator moves up at the same time as the
        // jump (s1_credits_04_slz3 trace frame 500: ROM y=0x01F0,
        // ENG y=0x01F2). See ObjectManager.processInlineRidingObject /
        // applyRidingCarry which already implements the equivalent of
        // MvSonicOnPtfm2 once the provider opts in.
        return true;
    }

    @Override
    public boolean isSolidFor(PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        return !isDestroyed() && !isSpawner;
    }

    @Override
    public SolidExecutionMode solidExecutionMode() {
        return SolidExecutionMode.MANUAL_CHECKPOINT;
    }

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (contact.standing() && routine == 2) {
            // Transition from Elev_Platform (routine 2) to Elev_Action (routine 4)
            // when player starts standing on the platform.
            routine = 4;
        }
    }

    // ---- Persistence ----

    @Override
    public boolean isPersistent() {
        // Disasm: out_of_range.w DeleteObject,elev_origX(a0)
        // Uses stored original X (not current X) for range check
        return !isDestroyed() && isInRangeAt(origX);
    }

    // ---- Debug rendering ----

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        // Draw origin anchor point (yellow cross)
        ctx.drawLine(origX - 4, origY, origX + 4, origY, 1.0f, 1.0f, 0.0f);
        ctx.drawLine(origX, origY - 4, origX, origY + 4, 1.0f, 1.0f, 0.0f);

        // Draw line from origin to current position (cyan)
        ctx.drawLine(origX, origY, x, y, 0.0f, 1.0f, 1.0f);

        // Draw collision box (green for solid platform)
        int left = x - halfWidth;
        int right = x + halfWidth;
        int top = y - HALF_HEIGHT;
        int bottom = y + HALF_HEIGHT;
        ctx.drawLine(left, top, right, top, 0.0f, 1.0f, 0.0f);
        ctx.drawLine(right, top, right, bottom, 0.0f, 1.0f, 0.0f);
        ctx.drawLine(right, bottom, left, bottom, 0.0f, 1.0f, 0.0f);
        ctx.drawLine(left, bottom, left, top, 0.0f, 1.0f, 0.0f);

        // Draw platform center (red cross)
        ctx.drawLine(x - 4, y, x + 4, y, 1.0f, 0.0f, 0.0f);
        ctx.drawLine(x, y - 4, x, y + 4, 1.0f, 0.0f, 0.0f);
    }


}
