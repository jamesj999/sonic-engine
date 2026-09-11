package com.openggf.game.sonic2.objects.badniks;

import com.openggf.level.objects.AbstractBadnikInstance;

import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.game.PlayableEntity;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.TouchResponseResult;

import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.ObjectControlState;
import com.openggf.game.sonic2.constants.Sonic2AnimationIds;

import java.util.List;

/**
 * Grabber (0xA7) - Spider badnik from CPZ.
 * Patrols horizontally on ceiling, detects player below, dives to grab,
 * carries player back up, releases on button mashing or timeout.
 *
 * Based on ObjA7 from Sonic 2 disassembly.
 */
public class GrabberBadnikInstance extends AbstractBadnikInstance implements SpawnRewindRecreatable {
    private static final int COLLISION_SIZE_INDEX = 0x0B; // From disassembly

    // Movement constants from disassembly
    private static final int PATROL_VELOCITY = 0x40;        // x_vel = $40 in subpixels
    private static final int PATROL_TIMER_INIT = 0xFF;      // 255 frames before turning
    private static final int DIVE_VELOCITY = 0x200;         // y_vel = $200 during dive
    private static final int DIVE_DELAY_INIT = 0x10;        // 16 frames delay before dive
    private static final int DIVE_TIMER_INIT = 0x40;        // 64 frames max dive time
    private static final int INPUT_CHECK_INTERVAL = 0x20;   // 32 frames between input checks
    private static final int BLINK_COUNT_INIT = 0x10;       // 16 blinks before timeout/destruction
    private static final int ESCAPE_BUTTON_COUNT = 4;       // Direction toggles to escape

    // Detection ranges
    private static final int DETECT_RANGE_X = 0x80;         // 128 pixels horizontal
    private static final int DETECT_RANGE_Y = 0x80;         // Player must be below within 128 pixels

    private enum State {
        PATROL,         // State 0: Hunting for player
        DELAY,          // State 2: Wind-up before dive
        DIVING,         // State 4: Diving toward player
        CARRYING,       // State 6: Carrying grabbed player
        RELEASING,      // State 8: Releasing player
        DEATH           // State A: Exploding
    }

    private State state;
    private int patrolTimer;
    private int diveTimer;
    private int delayTimer;
    private int inputCheckTimer;        // 32-frame interval for checking escape input (objoff_37)
    private int blinkCounter;           // Frames until next blink (objoff_2A)
    private int blinkCount;             // Blinks remaining before timeout (objoff_2B)
    private int directionToggleCount;   // Button presses in current check window (objoff_38)
    private boolean inputDetectedThisCycle; // Has input been detected this cycle (objoff_31)
    private int lastDirectionBits;          // Last direction pressed as bitmask (objoff_36)
    // Previous-frame HELD direction bits, used to derive the freshly-PRESSED edge
    // the ROM escape window actually consumes. loc_390BC reads `move.w (Ctrl_1_Held),d0`
    // then `andi.b #$C,d0` — the .b operates on the LOW byte of the word, which is
    // Ctrl_1_Press (the newly-pressed edge), NOT Ctrl_1_Held. So the escape both
    // latches (loc_390E6) and tallies toggles on pressed edges. See the Ctrl_1 word
    // layout (Ctrl_1_Held=high byte, Ctrl_1_Press=low byte) at
    // s2.constants.asm:1387-1389, the `move.w (a1),d0` read at s2.asm:76916, and
    // loc_390BC s2.asm:76915-76930.
    private int prevHeldDirectionBits;
    // ROM parity (s2.asm:76947): on escape, loc_390FA executes `clr.b collision_flags(a0)`,
    // making the Grabber non-interactive so the freed (still-rolling) player does NOT
    // touch-kill it and receive a spurious enemy bounce. The engine's touch scanner
    // skips any object whose getCollisionFlags() returns 0 (ObjectManager.java:5181),
    // so we mirror the ROM clear with this latch.
    private boolean collisionFlagsCleared;
    private boolean paletteFlipped;         // Palette bit toggle for blink effect (not visibility)
    private int anchorY;            // Y position of anchor point (where Grabber starts)
    private AbstractPlayableSprite grabbedPlayer;
    // ROM parity: the legs touch->collision_property->grab handshake is a
    // frame-delayed pipeline. TouchResponse runs at the end of a frame and sets
    // the legs' collision_property; ObjA8 loc_38F88 reads that property on the
    // FOLLOWING frame to set objoff_30 (the grab flag), which the body's dive
    // routine then consumes. We reproduce that one-frame deferral so the grab
    // (and the resulting velocity zero) lands on the same frame as the ROM
    // rather than one frame early. See s2.asm:76756-76777 (ObjA8) and the
    // TouchResponse pipeline ordering at s2.asm:84955-85049.
    private AbstractPlayableSprite pendingGrabPlayer;

    // Sub-object positions
    private int legsFrame;          // Frame index for legs (3 or 4)
    private int stringFrame;        // Frame index for string (0-8 based on distance)

    // Subpixel positions for accurate movement (16.8 fixed point)
    private int xSubpixel;          // X subpixel position (0-255)
    private int ySubpixel;          // Y subpixel position (0-255)

    // ObjA7_Init's three LoadChildObject calls (see reserveRomChildSlots).
    private boolean childSlotsReserved;

    public GrabberBadnikInstance(ObjectSpawn spawn) {
        super(spawn, "Grabber", Sonic2BadnikConfig.DESTRUCTION);
        this.currentX = spawn.x();
        this.currentY = spawn.y();
        this.anchorY = spawn.y();

        // Initial direction based on x_flip flag
        boolean xFlip = (spawn.renderFlags() & 0x01) != 0;
        this.facingLeft = !xFlip;
        this.xVelocity = facingLeft ? -PATROL_VELOCITY : PATROL_VELOCITY;

        this.state = State.PATROL;
        this.patrolTimer = PATROL_TIMER_INIT;
        this.diveTimer = 0;
        this.delayTimer = 0;
        this.inputCheckTimer = 0;
        this.blinkCounter = 0;
        this.blinkCount = 0;
        this.directionToggleCount = 0;
        this.inputDetectedThisCycle = false;
        this.lastDirectionBits = 0;
        this.prevHeldDirectionBits = 0;
        this.collisionFlagsCleared = false;
        this.paletteFlipped = false;
        this.grabbedPlayer = null;
        this.legsFrame = 3;
        this.stringFrame = 0;
        this.xSubpixel = 0;
        this.ySubpixel = 0;
    }

    /**
     * ROM {@code ObjA7_Init} calls {@code LoadChildObject} three times, for
     * {@code ChildObject_391E0} (ObjID_GrabberBox, $A9), {@code ChildObject_391E4}
     * (ObjID_GrabberLegs, $A8) and {@code ChildObject_391E8}
     * (ObjID_GrabberString, $AA) — s2.asm:76659-76675 and the child-object data at
     * s2.asm:77129-77131. {@code LoadChildObject} allocates via
     * {@code AllocateObjectAfterCurrent} (s2.asm:73012-73023), an ascending
     * first-free scan that starts after the parent's own slot.
     * <p>
     * The engine draws the legs, box and string from this instance rather than as
     * separate objects, so the three SST slots the ROM consumes must still be
     * reserved: every later {@code AllocateObject} in the level lands on a
     * different slot otherwise, which shifts the {@code d7} spreading term that
     * gates Obj37's floor probe (s2.asm:25209-25217) and the ascending
     * {@code RunObjects} execution order.
     */
    private int reservedChildSlotCount() {
        return 3;
    }

    @Override
    public int getReservedChildSlotCount() {
        return reservedChildSlotCount();
    }

    private void reserveRomChildSlots() {
        if (childSlotsReserved) {
            return;
        }
        childSlotsReserved = true;
        var optionalServices = tryServices();
        var objectManager = optionalServices != null ? optionalServices.objectManager() : null;
        if (objectManager != null && spawn != null && getSlotIndex() >= 0) {
            objectManager.allocateChildSlotsAfter(spawn, reservedChildSlotCount(), getSlotIndex());
        }
    }

    @Override
    protected void updateMovement(int vIntRunCount, PlayableEntity playerEntity) {
        reserveRomChildSlots();
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        switch (state) {
            case PATROL -> updatePatrol(player);
            case DELAY -> updateDelay(player);
            case DIVING -> updateDiving(player);
            case CARRYING -> updateCarrying();
            case RELEASING -> updateReleasing();
            case DEATH -> updateDeath();
        }

        // Update string frame based on distance from anchor
        updateStringFrame();
    }

    private void updatePatrol(AbstractPlayableSprite player) {
        // Check if player is in range
        if (player != null && checkPlayerInRange(player)) {
            // Start attack sequence
            state = State.DELAY;
            delayTimer = DIVE_DELAY_INIT;
            xVelocity = 0; // Stop horizontal movement
            return;
        }

        // Continue patrolling
        patrolTimer--;
        if (patrolTimer < 0) {
            // Turn around
            patrolTimer = PATROL_TIMER_INIT;
            xVelocity = -xVelocity;
            facingLeft = !facingLeft;
        }

        // Move horizontally using 16.8 fixed point math (like ObjectMove in disassembly)
        // Position is stored as (pixel << 8) | subpixel, velocity is added directly
        int xPos32 = (currentX << 8) | (xSubpixel & 0xFF);
        xPos32 += xVelocity;
        currentX = xPos32 >> 8;
        xSubpixel = xPos32 & 0xFF;
    }

    private boolean checkPlayerInRange(AbstractPlayableSprite player) {
        // d2 = object X - player X (positive when player is to the LEFT)
        // d3 = object Y - player Y (positive when player is ABOVE, negative when BELOW)
        int dx = currentX - player.getCentreX();
        int dy = currentY - player.getCentreY();

        // Horizontal check: addi.w #$40,d2 / cmpi.w #$80,d2 / bhs.s (fail)
        // Range: -$40 to +$3F (-64 to +63 pixels)
        int adjustedDx = dx + 0x40;
        if (adjustedDx < 0 || adjustedDx >= 0x80) {
            return false;
        }

        // Vertical check: cmpi.w #-$80,d3 / bhi.s (attack)
        // Since Grabber is on ceiling and d3 = grabber_y - player_y:
        //   - Negative d3 = player is BELOW grabber (attack if in range)
        //   - Range: d3 must be in (-$7F to 0), meaning player 1-127 pixels below
        return dy > -DETECT_RANGE_Y && dy <= 0;
    }

    private void updateDelay(AbstractPlayableSprite player) {
        delayTimer--;
        if (delayTimer < 0) {
            // Start diving
            state = State.DIVING;
            diveTimer = DIVE_TIMER_INIT;
            yVelocity = DIVE_VELOCITY;
            legsFrame = 4; // Switch to larger legs frame during dive

            // ROM parity: ObjA7_Main runs the sub-state routine and then calls
            // ObjectMove UNCONDITIONALLY every frame (s2.asm:76677-76684), so the
            // frame that ENDS the delay already travels by the y_vel that
            // loc_38EA2 (s2.asm:76745-76749) just installed -- it sets
            // y_vel = $200 and objoff_2C = $40 and returns straight into that
            // ObjectMove. Returning here without moving cost the dive one frame,
            // putting the whole descent (and therefore the legs' touch overlap,
            // the objoff_30 handshake and the grab itself) a frame behind the
            // ROM. objoff_2C is NOT decremented on this frame -- loc_38EB4 only
            // sees it from the next one -- so diveTimer stays at its initial
            // value here, exactly as the ROM's counter does.
            //
            // The legs latch on this frame too: ObjA8 runs after the body in the
            // same frame, and loc_38F88's gate (s2.asm:76770) is
            // cmpi.b #4,routine_secondary(a1), which the line above has just
            // satisfied. Its collision_property was set by the player's own
            // TouchResponse earlier in the frame, i.e. against the pre-ObjectMove
            // position -- so the check runs before the move below, exactly as it
            // does on every other diving frame.
            if (player != null && checkGrabCollision(player)) {
                pendingGrabPlayer = player;
            }
            applyDiveMove();
        }
    }

    /**
     * The vertical half of ObjA7_Main's trailing ObjectMove
     * (s2.asm:76677-76684): 16.8 fixed-point position += y_vel.
     */
    private void applyDiveMove() {
        int yPos32 = (currentY << 8) | (ySubpixel & 0xFF);
        yPos32 += yVelocity;
        currentY = yPos32 >> 8;
        ySubpixel = yPos32 & 0xFF;
    }

    private void updateDiving(AbstractPlayableSprite player) {
        // ROM parity (one-frame grab deferral): the legs touch overlap is latched
        // by TouchResponse at the end of one frame and only consumed by the body
        // (ObjA7_GrabCharacter, s2.asm:76653-76655 -> 76676) on the next frame.
        // So a previously-latched overlap grabs FIRST, before this frame's dive
        // movement, exactly as loc_38EB4 tests objoff_30 before ObjectMove.
        if (pendingGrabPlayer != null) {
            AbstractPlayableSprite p = pendingGrabPlayer;
            pendingGrabPlayer = null;
            if (p != null && !p.getInvulnerable()) {
                grabPlayer(p);
                return;
            }
        }

        // Detect the legs touch overlap this frame; it is applied next frame.
        if (player != null && checkGrabCollision(player)) {
            pendingGrabPlayer = player;
        }

        diveTimer--;
        if (diveTimer <= 0) {
            // Abort dive, return to patrol
            returnToPatrol();
            return;
        }

        // At midpoint, reverse direction
        if (diveTimer == DIVE_TIMER_INIT / 2) {
            yVelocity = -yVelocity;
        }

        // Move vertically using 16.8 fixed point math (ObjA7_Main's ObjectMove)
        applyDiveMove();
    }

    private boolean checkGrabCollision(AbstractPlayableSprite player) {
        // ROM parity: the grab is NOT a self-computed loose AABB. In the ROM the
        // capture is driven by the Grabber's LEGS sub-object (ObjA8) through the
        // normal TouchResponse pipeline, then gated on the body actively diving:
        //   - ObjA8 loc_38F88 (s2.asm:76756-76777) only sets the grab flag
        //     (objoff_30) when its collision_property was set by a touch overlap
        //     AND cmpi.b #4,routine_secondary(a1) (the body is in the DIVING
        //     sub-state).
        //   - The body's dive routine loc_38EB4 (s2.asm:76653-76655) consumes
        //     objoff_30 -> ObjA7_GrabCharacter.
        // This method is only ever called from updateDiving(), so the "body is
        // diving" gate is already satisfied; here we reproduce the ObjA8 legs
        // touch box exactly so a fast rolling Sonic merely flying past the legs
        // does NOT get captured (the previous 48x32 box grabbed him; the ROM box
        // is far tighter).
        //
        // ObjA8 legs collision_flags = $D7 (ObjA7_SubObjData2, s2.asm:77042).
        // Touch index = $D7 & $3F = $17 -> Touch_Sizes[$17] = (8,8) half-extents
        // (s2.asm:85008-85010, 85077). The body aligns the legs at body_y + $10
        // via Obj_AlignChildXY (moveq #$10,d1 at s2.asm:76593-76595), matching
        // the legsY = currentY + 16 used elsewhere here.
        if (player.getInvulnerable()) {
            return false;
        }

        final int legsBoxHalfWidth = 0x08;   // Touch_Sizes[$17] width  half-extent
        final int legsBoxHalfHeight = 0x08;  // Touch_Sizes[$17] height half-extent
        int legsX = currentX;
        int legsY = currentY + 0x10;

        // Reproduce TouchResponse's player box (s2.asm:84966-84987):
        //   d2 = player_x - 8 ; d4 = $10 (player box spans player_x-8 .. player_x+8)
        //   d5 = (y_radius - 3) ; player box spans player_y-d5 .. player_y+d5
        int playerX = player.getCentreX();
        int playerY = player.getCentreY();
        int d2 = playerX - 8;
        int d4 = 0x10;
        int d5 = (player.getYRadius() & 0xFF) - 3;

        // Width check (s2.asm:85016-85030): object box [legsX - hw, legsX + hw]
        // overlaps player box [d2, d2 + d4].
        int wd0 = legsX - legsBoxHalfWidth - d2;
        if (wd0 < 0) {
            wd0 += legsBoxHalfWidth * 2;
            if (wd0 < 0) {
                return false; // fully left of player box
            }
        } else if (wd0 > d4) {
            return false; // fully right of player box
        }

        // Height check (s2.asm:85032-85047): object box [legsY - hh, legsY + hh]
        // overlaps player box [player_y - d5, player_y + d5] i.e. d3 = player_y - d5
        // spanning 2*d5.
        int d3 = playerY - d5;
        int hd0 = legsY - legsBoxHalfHeight - d3;
        if (hd0 < 0) {
            hd0 += legsBoxHalfHeight * 2;
            return hd0 >= 0;
        }
        return hd0 <= (d5 * 2);
    }

    private void grabPlayer(AbstractPlayableSprite player) {
        state = State.CARRYING;
        grabbedPlayer = player;

        // Initialize timers per disassembly (ObjA7_GrabCharacter, lines 76240-76245)
        inputCheckTimer = INPUT_CHECK_INTERVAL;  // objoff_37 = $20 (check every 32 frames)
        blinkCount = BLINK_COUNT_INIT;           // objoff_2B = $10 (16 blinks before timeout)
        blinkCounter = blinkCount;               // objoff_2A = objoff_2B (start with full interval)
        directionToggleCount = 0;                // objoff_38 = 0 (button press counter)
        inputDetectedThisCycle = false;          // objoff_31 = 0
        lastDirectionBits = 0;                   // objoff_36 = 0
        // Seed the held-edge history from the player's held direction bits AT the
        // grab frame. The ROM escape consumes Ctrl_1_Pressed, a GLOBAL edge computed
        // every frame as (held & ~heldPrev) independent of the grab. So on the first
        // carry frame the pressed edge is taken against the true previous frame's
        // held state, not against zero. Seeding from 0 would make a direction the
        // player was already holding at grab spuriously read as freshly pressed and
        // over-count the first window's toggles, firing the escape several frames
        // early (CPZ2: seed=0 escapes at csvrow 1612, ROM/seed-from-grab at 1619).
        prevHeldDirectionBits = heldDirectionBits(player);

        // Lock player movement (obj_control = $81)
        ObjectControlState.nativeBit7FullControl().applyTo(player);
        player.setXSpeed((short) 0);
        player.setYSpeed((short) 0);
        player.setAnimationId(Sonic2AnimationIds.FLOAT);  // Per disassembly line 76221

        // Reverse dive direction to go back up. ObjA7_GrabCharacter does this
        // BEFORE returning into ObjA7_Main's ObjectMove (s2.asm:76771-76779):
        // tst.w y_vel / neg.w y_vel, then the objoff_2C reflection.
        if (yVelocity > 0) {
            yVelocity = -yVelocity;
            // Recalculate remaining time
            int remaining = DIVE_TIMER_INIT - diveTimer;
            diveTimer = remaining + 1;
        }

        // ROM parity: on the frame the grab is consumed the body still travels --
        // ObjA7_Main's trailing ObjectMove (s2.asm:76677-76684) runs after the
        // routine, now with the negated y_vel -- and only THEN does ObjA8, aligned
        // to the moved body by Obj_AlignChildXY (body_y + $10, s2.asm:76681-76684),
        // pin the grabbed player's x_pos/y_pos to itself in loc_38FE8
        // (s2.asm:76790-76799). Pinning before that move left the player two
        // pixels below the ROM on the grab frame.
        applyDiveMove();
        player.setX((short) (currentX - player.getWidth() / 2));
        player.setY((short) (currentY + 0x10 - player.getHeight() / 2));

        animFrame = 1; // Closed claws frame
        // Note: Per disassembly, no sound effect plays on grab
    }

    /**
     * Held direction bits (left=$04, right=$08, mask $0C), mirroring the high byte
     * of the ROM {@code Ctrl_1_Held} word that loc_390BC reads (s2.asm:76916).
     */
    private static int heldDirectionBits(AbstractPlayableSprite player) {
        int bits = 0;
        if (player.isLeftPressed()) bits |= 0x04;
        if (player.isRightPressed()) bits |= 0x08;
        return bits & 0x0C;
    }

    private void updateCarrying() {
        if (grabbedPlayer == null) {
            returnToPatrol();
            return;
        }

        // If player entered debug mode while grabbed, release them
        if (grabbedPlayer.isDebugMode()) {
            releasePlayerForDebug();
            return;
        }

        // === Blink mechanism (ObjA7_CheckExplode, s2.asm:76967-76975) ===
        // ROM ordering: the carry routines loc_38F3E (s2.asm:76708-76710) and
        // loc_38F58 (s2.asm:76722-76724) call ObjA7_CheckExplode BEFORE loc_390BC
        // every frame. Run the blink countdown first so a same-frame blink-timeout
        // (ObjA7_Poof) wins over the escape evaluation exactly as the ROM does.
        blinkCounter--;
        if (blinkCounter <= 0) {
            // Reload counter and decrement blink count
            blinkCounter = blinkCount;
            blinkCount--;
            paletteFlipped = !paletteFlipped;  // Toggle palette bit (bchg #palette_bit_0)

            if (blinkCount <= 0) {
                // Timeout! Grabber explodes and HURTS the player
                hurtAndReleasePlayer();
                // Trigger destruction (Grabber transforms to explosion)
                triggerDestruction();
                return;
            }
        }

        // === Escape-window input checking (loc_390BC, s2.asm:76915-76957) ===
        // Track directional input changes using a bitmask like the disassembly:
        // Bit 2 = left ($04), Bit 3 = right ($08), mask = $0C (objoff_36/objoff_38).
        //
        // ROM-faithful PRESSED-edge input: loc_390BC does `move.w (Ctrl_1_Held),d0`
        // then `andi.b #$C,d0`. The `.b` masks the LOW byte of that word, which is
        // Ctrl_1_Pressed (the freshly-pressed edge), NOT the held byte. So both the
        // first-input latch (loc_390E6 s2.asm:76933-76940) and the toggle tally
        // (loc_390BC s2.asm:76922-76928) operate on newly-pressed direction edges.
        // isLeftPressed()/isRightPressed() return HELD state, so derive the press
        // edge here: pressed = held & ~prevHeld. Consuming held bits (the prior
        // engine behaviour) over-counted toggles and fired the escape ~11 frames
        // early (CPZ2 release at csvrow 1609 vs ROM 1619/gfc 0x0652).
        int heldDirectionBits = heldDirectionBits(grabbedPlayer);
        int currentDirectionBits = heldDirectionBits & ~prevHeldDirectionBits;
        prevHeldDirectionBits = heldDirectionBits;

        // ROM gate (s2.asm:76918-76919): tst.b objoff_31 / beq loc_390E6.
        // The 32-frame escape window (objoff_37) only begins counting AFTER the
        // first directional press of the cycle has been latched into objoff_31.
        // On that first-input frame the ROM takes the loc_390E6 path
        // (s2.asm:76933-76940) which sets objoff_31 + objoff_36 and returns
        // WITHOUT decrementing objoff_37 and WITHOUT counting a toggle. Only on
        // subsequent frames (objoff_31 already set) does loc_390BC decrement
        // objoff_37 and tally toggles. The previous engine code decremented the
        // timer unconditionally every carrying frame, which started the window at
        // the grab frame instead of at first input and fired the escape ~12 frames
        // early (CPZ2 f1607 divergence: ROM keeps Sonic pinned, engine released).
        if (!inputDetectedThisCycle) {
            // loc_390E6: waiting for the first directional press of this cycle.
            if (currentDirectionBits != 0) {
                inputDetectedThisCycle = true;          // st.b objoff_31
                lastDirectionBits = currentDirectionBits; // move.b d0,objoff_36
            }
            // No objoff_37 decrement on the latch (or idle) frame.
        } else {
            // objoff_31 set: now the window counts down (subq.b #1,objoff_37).
            inputCheckTimer--;
            if (inputCheckTimer <= 0) {
                // loc_390FA (s2.asm:76942-76956): window expired. Escape only when
                // >=4 toggles were tallied this window; either way reset the timer
                // to $20 AND clear the first-input latch + toggle count so the next
                // window again waits for first input.
                if (directionToggleCount >= ESCAPE_BUTTON_COUNT) {
                    // Player escaped: obj_control=0, in_air, Walk anim, NO velocity
                    // imparted (s2.asm:76945-76951). Sonic drops from rest.
                    releasePlayer(true);
                    return;
                }
                inputCheckTimer = INPUT_CHECK_INTERVAL;  // move.b #$20,objoff_37
                directionToggleCount = 0;                // clr.b objoff_38
                inputDetectedThisCycle = false;          // clr.b objoff_31
            } else if (currentDirectionBits != 0
                    && currentDirectionBits != lastDirectionBits) {
                // s2.asm:76922-76928: count a toggle only when a direction is held
                // this frame and it differs from the last latched direction.
                directionToggleCount++;
                lastDirectionBits = currentDirectionBits;
            }
        }

        // === Movement back to anchor ===
        if (currentY > anchorY) {
            int yPos32 = (currentY << 8) | (ySubpixel & 0xFF);
            yPos32 += yVelocity;
            currentY = yPos32 >> 8;
            ySubpixel = yPos32 & 0xFF;
        }

        // Update grabbed player position. ROM ObjA8 loc_38FE8 (s2.asm:76790-76799)
        // pins the grabbed player's x_pos/y_pos directly to the LEGS sub-object's
        // x_pos/y_pos. The legs are aligned to body_y + $10 via Obj_AlignChildXY
        // (moveq #$10,d1 at s2.asm:76593-76595), so the player's ROM-centre is
        // (currentX, currentY + 16), not body + 24.
        grabbedPlayer.setX((short) (currentX - grabbedPlayer.getWidth() / 2));
        grabbedPlayer.setY((short) (currentY + 0x10 - grabbedPlayer.getHeight() / 2));
    }

    /**
     * Called when blink sequence completes - player takes damage.
     * Per disassembly ObjA7_Poof: player is released and hurt by the explosion.
     */
    private void hurtAndReleasePlayer() {
        if (grabbedPlayer != null) {
            ObjectControlState.none().applyTo(grabbedPlayer);
            grabbedPlayer.setAir(true);

            // ROM: Hurt_Sidekick - CPU Tails only gets knockback, no ring scatter or death
            if (grabbedPlayer.isCpuControlled()) {
                grabbedPlayer.applyHurt(currentX);
            } else {
                // Hurt the player - this is the punishment for not escaping
                boolean hadRings = grabbedPlayer.getRingCount() > 0;
                if (hadRings && !grabbedPlayer.hasShield()) {
                    services().spawnLostRings(grabbedPlayer, 0);
                }
                grabbedPlayer.applyHurtOrDeath(currentX, true, hadRings);
            }

            grabbedPlayer = null;
        }
    }

    /**
     * Called when the blink sequence completes - Grabber transforms to explosion.
     */
    private void triggerDestruction() {
        state = State.DEATH;
        setDestroyed(true);
        // Grabber transforms to explosion object (no animal spawned)
    }

    private void releasePlayer(boolean escaped) {
        if (grabbedPlayer != null) {
            ObjectControlState.none().applyTo(grabbedPlayer);
            grabbedPlayer.setAir(true);
            if (escaped) {
                // loc_390FA publishes Walk while releasing object control;
                // velocity is deliberately left untouched.
                grabbedPlayer.setAnimationId(Sonic2AnimationIds.WALK);
            }
            // Per disassembly: player just becomes airborne and falls naturally
            // No velocity change on escape
            grabbedPlayer = null;
        }

        if (escaped) {
            // ROM loc_390FA (s2.asm:76942-76951): on a successful button-mash escape
            // the Grabber clears its own collision_flags (`clr.b collision_flags(a0)`,
            // s2.asm:76947) and frees the player WITHOUT imparting any velocity. The
            // freed player is still rolling, so without this clear the engine's
            // per-frame ENEMY touch poll would touch-kill the Grabber on the very
            // next frame and give the player a spurious enemy-destruction bounce
            // (-0x00C8). Latch the clear so getCollisionFlags() returns 0 and the
            // touch scanner skips this object (ObjectManager.java:5181).
            collisionFlagsCleared = true;

            // ROM loc_390FA sets routine_secondary=$A (s2.asm:76945). Routine $A is
            // BranchTo_ObjA7_CheckExplode (s2.asm:76610, off_38E46 entry $A): the
            // Grabber does NOT return to patrol or hunt again — it only continues the
            // ObjA7_CheckExplode blink countdown until ObjA7_Poof transforms it into
            // an explosion. Routing escape into State.DEATH (= routine $A) instead of
            // RELEASING/returnToPatrol prevents the engine from re-diving and
            // re-grabbing the just-freed player (CPZ2 second-grab at frame 1737).
            state = State.DEATH;
            animFrame = 0; // Open claws
            // Keep the blink countdown (blinkCounter/blinkCount) where it is so the
            // remaining objoff_2A/objoff_2B frames run out exactly as in routine $A.
            return;
        }

        state = State.RELEASING;
        animFrame = 0; // Open claws
        paletteFlipped = false;  // Reset palette
    }

    @Override
    public int getCollisionFlags() {
        if (collisionFlagsCleared) {
            // ROM loc_390FA `clr.b collision_flags(a0)` — Grabber is no longer
            // interactive after a button-mash escape (s2.asm:76947).
            return 0;
        }
        return super.getCollisionFlags();
    }

    /**
     * Releases player without modifying their state (for debug mode).
     * Player's state was already reset by toggleDebugMode().
     */
    private void releasePlayerForDebug() {
        grabbedPlayer = null;
        state = State.RELEASING;
        animFrame = 0; // Open claws
        paletteFlipped = false;  // Reset palette
    }

    private void updateReleasing() {
        // Brief pause before returning to patrol
        returnToPatrol();
    }

    private void returnToPatrol() {
        state = State.PATROL;
        patrolTimer = PATROL_TIMER_INIT;
        xVelocity = facingLeft ? -PATROL_VELOCITY : PATROL_VELOCITY;
        yVelocity = 0;
        animFrame = 0;
        legsFrame = 3;
        paletteFlipped = false;  // Reset palette

        // Return to anchor position
        currentY = anchorY;
        ySubpixel = 0;
    }

    private void updateDeath() {
        // ROM routine_secondary=$A (BranchTo_ObjA7_CheckExplode, off_38E46 entry $A
        // at s2.asm:76610): the Grabber runs ONLY ObjA7_CheckExplode each frame
        // (s2.asm:76967-76975) — no patrol, no dive, no escape window. The blink
        // countdown (objoff_2A/objoff_2B) continues from where the escape left it,
        // and when objoff_2B reaches 0 ObjA7_Poof (s2.asm:76977-76989) transforms the
        // Grabber into an Explosion. Because the player was freed at escape
        // (objoff_32 cleared, loc_390FA s2.asm:76951), Poof's player-hurt branch
        // (`tst.w objoff_32; beq +`, s2.asm:76981-76982) is skipped: NO player hurt.
        if (grabbedPlayer != null) {
            // Safety: if somehow still holding a player when entering DEATH via a
            // non-escape path, release without imparting velocity.
            ObjectControlState.none().applyTo(grabbedPlayer);
            grabbedPlayer.setAir(true);
            grabbedPlayer = null;
        }

        // Continue the ObjA7_CheckExplode blink countdown.
        blinkCounter--;
        if (blinkCounter <= 0) {
            blinkCounter = blinkCount;
            blinkCount--;
            paletteFlipped = !paletteFlipped;  // bchg #palette_bit_0,art_tile(a0)
            if (blinkCount <= 0) {
                // ObjA7_Poof: transform into an explosion (no animal, no hurt).
                triggerDestruction();
            }
        }
    }

    private void updateStringFrame() {
        // Per disassembly: string object Y = anchorY - 8
        // Frame = (grabberY - stringY) / 16 = (currentY - anchorY + 8) / 16
        int stringY = anchorY - 8;
        int distance = currentY - stringY;
        if (distance < 0) {
            stringFrame = 0;
        } else {
            // Each frame represents 16 pixels of string
            stringFrame = Math.min(distance >> 4, 8);
        }
    }

    @Override
    protected void updateAnimation(int vIntRunCount) {
        // Animation is simple: frame 0 = open claws, frame 1 = closed claws
        // Already handled in state transitions
        if (state == State.CARRYING) {
            animFrame = 1;
        } else {
            // Animate between frames 0 and 1 when patrolling
            if (state == State.PATROL) {
                animFrame = ((vIntRunCount / 8) & 1);
            }
        }
    }

    @Override
    protected int getCollisionSizeIndex() {
        return COLLISION_SIZE_INDEX;
    }

    @Override
    public void onPlayerAttack(PlayableEntity playerEntity, TouchResponseResult result) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (isDestroyed()) {
            return;
        }

        // ROM parity: Touch_Enemy / Touch_KillEnemy (s2.asm:84807-84890) destroys
        // the badnik whenever Sonic is rolling, spindashing, or invincible — the
        // touch response is direction-agnostic.  Only the bounce-back direction
        // depends on Sonic's relative position; the kill itself is unconditional.
        // The Grabber is no exception (collision_flags=$B in ObjA7_SubObjData at
        // s2.asm:76603, which is the standard "kill on roll" mask).  The previous
        // "only destroy from above" guard rejected legitimate roll-jump kills
        // from below (CPZ f680 trace divergence: Sonic kept his roll arc through
        // the Grabber but the engine never killed it, then DIVING/grabPlayer
        // zeroed his speeds).  ROM destroys the Grabber the moment Sonic's roll
        // sensor overlaps it regardless of vertical direction.

        // Release any grabbed player before destruction.
        if (grabbedPlayer != null) {
            releasePlayer(true);
        }
        super.onPlayerAttack(player, result);
    }

    @Override
    public int getPriorityBucket() {
        // Per disassembly: priority = 4 (renders in front of player at priority 2)
        return RenderPriority.clamp(4);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed()) {
            return;
        }

        ObjectServices svc = tryServices();
        ObjectRenderManager renderManager = svc != null ? svc.renderManager() : null;
        if (renderManager == null) {
            return;
        }

        // Palette blink: toggle between palette 1 (normal) and palette 0 (flipped)
        // This matches disassembly: bchg #palette_bit_0,art_tile(a0)
        int paletteOverride = paletteFlipped ? 0 : -1;  // -1 means use default (palette 1)

        // Draw string - per disassembly, string object is at anchorY - 8 and always renders
        // String frames extend downward from that position to connect to the grabber body
        PatternSpriteRenderer stringRenderer = renderManager.getRenderer(Sonic2ObjectArtKeys.GRABBER_STRING);
        if (stringRenderer != null && stringRenderer.isReady()) {
            stringRenderer.drawFrameIndex(stringFrame, currentX, anchorY - 8, false, false, paletteOverride);
        }

        // Draw anchor box (frame 2)
        PatternSpriteRenderer grabberRenderer = renderManager.getRenderer(Sonic2ObjectArtKeys.GRABBER);
        if (grabberRenderer == null || !grabberRenderer.isReady()) {
            return;
        }
        grabberRenderer.drawFrameIndex(2, currentX, anchorY - 12, false, false, paletteOverride);

        // Draw main body
        grabberRenderer.drawFrameIndex(animFrame, currentX, currentY, !facingLeft, false, paletteOverride);

        // Draw legs (frame 3 or 4)
        grabberRenderer.drawFrameIndex(legsFrame, currentX, currentY + 16, !facingLeft, false, paletteOverride);
    }
}
