package com.openggf.game.sonic1.objects;
import com.openggf.game.PlayableEntity;
import com.openggf.game.solid.PlayerSolidContactResult;
import com.openggf.game.solid.SolidCheckpointBatch;

import com.openggf.game.sonic1.Sonic1SwitchManager;
import com.openggf.game.sonic1.constants.Sonic1Constants;
import com.openggf.game.OscillationManager;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectLifetimeOps;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.PlatformBobHelper;
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
 * Object 18 - Platforms (GHZ, SYZ, SLZ).
 * <p>
 * Top-solid platforms with multiple movement subtypes including stationary,
 * horizontal/vertical oscillation, falling, and switch-activated rising.
 * Platforms nudge downward when stood on (sine-based spring effect).
 * <p>
 * Subtype (low nybble) controls movement behavior:
 * <ul>
 *   <li>0x00: Stationary</li>
 *   <li>0x01: Horizontal oscillation (self-driven, right-to-left start)</li>
 *   <li>0x02: Vertical oscillation (self-driven, down start)</li>
 *   <li>0x03: Fall when stood on (30-frame delay, transitions to 0x04)</li>
 *   <li>0x04: Falling (gravity 0x38, 32-frame countdown then drop player)</li>
 *   <li>0x05: Horizontal oscillation (self-driven, left-to-right start)</li>
 *   <li>0x06: Vertical oscillation (self-driven, up start)</li>
 *   <li>0x07: Switch-activated rising (high nybble = switch index)</li>
 *   <li>0x08: Rising after switch (rises 0x200 pixels at 2px/frame)</li>
 *   <li>0x0A: Vertical oscillation (self-driven, half amplitude)</li>
 *   <li>0x0B: Vertical oscillation (global oscillator, down start)</li>
 *   <li>0x0C: Vertical oscillation (global oscillator, up start)</li>
 * </ul>
 * <p>
 * Reference: docs/s1disasm/_incObj/18 Platforms.asm
 */
public class Sonic1PlatformObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener, SpawnRewindRecreatable {

    // From disassembly: move.b #$20,obActWid(a0)
    private static final int HALF_WIDTH = 0x20;

    // MvSonicOnPtfm2 hardcodes "subi.w #9,d0" for the ground half-height.
    private static final int HALF_HEIGHT = 9;

    // From disassembly: move.b #4,obPriority(a0)
    private static final int PRIORITY = 4;

    // Nudge physics handled by PlatformBobHelper (step=4, maxAngle=0x40, amplitude >>6)
    // ROM: move.w #$400,d1; muls.w d1,d0; swap d0 ≡ sinHex(angle) >> 6

    // Type 03: move.w #30,objoff_3A(a0)
    private static final int FALL_STAND_DELAY = 30;
    // Type 04: move.w #32,objoff_3A(a0) (countdown before dropping player)
    private static final int FALL_COUNTDOWN = 32;
    // Type 04: addi.w #$38,obVelY(a0)
    private static final int FALL_GRAVITY = 0x38;
    // Type 04: addi.w #$E0,d0 (delete threshold below bottom boundary)
    private static final int FALL_DELETE_OFFSET = 0xE0;

    // Type 07: move.w #60,objoff_3A(a0)
    private static final int SWITCH_DELAY = 60;
    // Type 08: subi.w #$200,d0 (rise distance)
    private static final int RISE_DISTANCE = 0x200;
    // Type 08: subq.w #2,objoff_2C(a0) (rise speed)
    private static final int RISE_SPEED = 2;

    // Oscillation indices (offset into OscillationManager data after bitfield)
    // v_oscillate+$1A -> data offset 0x18 (oscillator 6: freq=8, amp=0x40)
    private static final int OSC_SELF_DRIVEN = 0x18;
    // v_oscillate+$E -> data offset 0x0C (oscillator 3: freq=2, amp=0x30)
    private static final int OSC_GLOBAL = 0x0C;

    private int zoneIndex;

    // Dynamic position
    private int x;
    private int y;

    // Saved base positions (objoff_32 = spawn X, objoff_34 = spawn Y)
    private int baseX;
    private int baseY;
    // objoff_2C: working Y position (modified by vertical movement + nudge)
    private int workingY;

    // Movement subtype (low nybble of obSubtype)
    private int moveType;

    // Nudge displacement: sine-based vertical bob when player stands on platform
    private final PlatformBobHelper bobHelper = new PlatformBobHelper();

    // Timer (objoff_3A): multi-purpose timer for types 03, 04, 07
    private int timer;

    // Velocity for falling platform (type 04)
    private int yVelocity;
    // Fractional Y for type 04 falling (16.16 fixed point: high 16 = Y, low 16 = subpixel)
    private int yFrac;

    // Mapping frame: 0 = small platform, 1 = large column (GHZ only, subtype 0x0A)
    private int mappingFrame;

    // Whether player is currently standing on this platform (obStatus bit 3)
    private boolean playerStanding;

    // Cached oscillator value from previous frame (obAngle/objoff_26).
    // Self-driven movement reads this, then .chgmotion stores current value for next frame.
    private int cachedOscillator;

    // When true, platform is in routine 8 (Plat_Action) — nudge angle is frozen.
    // This happens after type 04 timer expires and detaches the player.
    private boolean inFallingRoutine;

    private boolean initialized;

    public Sonic1PlatformObjectInstance(ObjectSpawn spawn) {
        super(spawn, "Platform");

        this.baseX = spawn.x();
        this.baseY = spawn.y();
        this.workingY = spawn.y();
        this.x = spawn.x();
        this.y = spawn.y();

        this.timer = 0;
        this.yVelocity = 0;
        this.yFrac = 0;
        this.playerStanding = false;
        // Disasm: move.w #$80,obAngle(a0) — writes word $0080 at offset $26.
        // On 68000 big-endian, byte at $26 = $00, byte at $27 = $80.
        // Movement reads move.b obAngle(a0),d1 (byte at $26) = $00.
        this.cachedOscillator = 0x00;
        this.inFallingRoutine = false;

        updateDynamicSpawn(x, y);
    }

    private void ensureInitialized() {
        if (initialized) return;
        initialized = true;

        this.zoneIndex = services().romZoneId();

        // SLZ forces subtype 3 (fall-when-stood-on) regardless of placed subtype.
        // Disasm: move.b #3,obSubtype(a0) — overwrites full byte before frame selection.
        int effectiveSubtype;
        if (zoneIndex == Sonic1Constants.ZONE_SLZ) {
            effectiveSubtype = 3;
        } else {
            effectiveSubtype = spawn.subtype() & 0xFF;
        }
        this.moveType = effectiveSubtype & 0x0F;

        // Frame selection: full effective subtype == 0x0A uses frame 1 (large column), others use frame 0.
        // Disasm: cmpi.b #$A,d0 compares full byte after SLZ override.
        this.mappingFrame = effectiveSubtype == 0x0A ? 1 : 0;
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
        ensureInitialized();
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        var objectManager = services().objectManager();
        boolean wasPlayerRiding = objectManager != null && objectManager.isAnyPlayerRiding(this);

        if (!inFallingRoutine) {
            // Routine 2/4: update bob angle (frozen in routine 8 / Plat_Action).
            // Plat_Solid (routine 2) only subtracts from objoff_38 before
            // PlatformObject can create a new ride; the +4 nudge ramp runs in
            // Plat_Action2 (routine 4). The routine for THIS frame was decided by
            // last frame's standing/ExitPlatform result, so the ramp gate uses the
            // prior-frame standing latch (playerStanding from the previous update).
            // On the jump-off frame ROM is still in routine 4 (objoff_38 ramps +4
            // once more) before ExitPlatform resets it to routine 2 next frame,
            // matching wasPlayerRiding && (prior playerStanding).
            bobHelper.update(wasPlayerRiding && playerStanding);
        }

        // Apply movement
        applyMovement(player);

        // Apply nudge (sine-based vertical offset)
        applyNudge();

        updateDynamicSpawn(x, y);

        // ROM routine order (docs/s1disasm/_incObj/18 Platforms.asm:74-87): routine 4
        // (Plat_Action2) runs ExitPlatform, then Plat_Move/Plat_Nudge, then
        // unconditionally MvSonicOnPtfm2. Both the ExitPlatform detach test and the
        // MvSonicOnPtfm2 re-seat observe the POST-move platform surface, so a single
        // post-move checkpoint models them. Resolving after the move (and after the
        // dynamic-spawn position update above) ensures the airborne-rider carry on
        // the jump-off frame re-seats to the platform's new y, not its pre-move y.
        SolidCheckpointBatch batch = checkpointAll();
        playerStanding = hasStandingContact(batch);

        // ROM landing-frame timer start (type 03 only):
        // On the landing frame, Plat_Solid calls PlatformObject which sets obStatus bit 3
        // (standing), then falls through to Plat_Action where Plat_Move (.type03) reads
        // that bit and sets objoff_3A=30 (docs/s1disasm/_incObj/18 Platforms.asm:54-71,
        // docs/s1disasm/_incObj/18 Platforms.asm:200-209). In the engine,
        // moveFallOnStand() runs BEFORE the checkpoint, so it uses the previous frame's
        // playerStanding=false and misses the landing-frame timer start. The engine's
        // PlatformObject equivalent (checkpointAll) runs afterward, so the timer must
        // be started post-checkpoint when we detect the fresh first-time standing.
        if (moveType == 0x03 && timer == 0 && playerStanding) {
            timer = FALL_STAND_DELAY;
        }
    }

    @Override
    public SolidExecutionMode solidExecutionMode() {
        return SolidExecutionMode.MANUAL_CHECKPOINT;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(ObjectArtKeys.PLATFORM);
        if (renderer == null) return;

        renderer.drawFrameIndex(mappingFrame, x, y, false, false);
    }

    @Override
    public SolidObjectParams getSolidParams() {
        return SolidObjectParams.of(HALF_WIDTH, HALF_HEIGHT, HALF_HEIGHT);
    }

    @Override
    public boolean isTopSolidOnly() {
        return true;
    }

    @Override
    public boolean usesCollisionHalfWidthForTopLanding() {
        // Plat_Solid passes obActWid directly as PlatformObject's d1
        // (docs/s1disasm/_incObj/18 Platforms.asm:59-62), so the collision
        // half-width is already the standable width and must not receive the
        // generic SolidObject +$B narrowing.
        return true;
    }

    @Override
    public boolean usesPreUpdatePositionForSolidContact(PlayableEntity player) {
        // S1 Obj18 routine 2 calls PlatformObject before Plat_Move and
        // Plat_Nudge (docs/s1disasm/_incObj/18 Platforms.asm:54-67).
        // Continued riding still observes the post-move/post-nudge surface
        // through routine 4's ExitPlatform -> Plat_Move -> Plat_Nudge ->
        // MvSonicOnPtfm2 order (same file:74-87).
        return true;
    }

    @Override
    public boolean carriesAirborneRiderAfterExitPlatform() {
        // ROM Plat_Action2 (routine 4, docs/s1disasm/_incObj/18 Platforms.asm:74-87)
        // calls ExitPlatform first (which clears the on-object bit when the player
        // jumped this frame, docs/s1disasm/_incObj/sub ExitPlatform.asm:20-27),
        // then runs Plat_Move/Plat_Nudge, then unconditionally calls MvSonicOnPtfm2
        // (docs/s1disasm/_incObj/sub MvSonicOnPtfm.asm:18-41). MvSonicOnPtfm2 does
        // NOT check the rider's velocity, so on the jump-off frame it still pulls
        // Sonic's y_pos to platformY-9-obHeight using the platform's post-move
        // position, overwriting the Sonic_Jump rolling-radius adjust
        // (sonic.asm:1228 addq.w #sonic_height-sonic_roll_height,obY(a0)). This is
        // structurally identical to Obj52 MBlock_StandOn / Obj59 Elev_Action, which
        // already opt in (Sonic1MovingBlockObjectInstance / Sonic1ElevatorObjectInstance).
        //
        // Without this opt-in the engine applies only the +5 jump adjust and skips
        // the post-jump pull-up, leaving Sonic ~2px high when the platform moves up
        // on the launch frame (s1_ghz2 trace frame 2591: ROM y=0x0259, ENG y=0x0257;
        // BizHawk capture platY 0x026E->0x0270, height 0x13->0x0E). The engine carry
        // is implemented in ObjectSolidContactController.processInlineRidingObject /
        // applyRidingCarry once the provider opts in.
        return true;
    }

    @Override
    public boolean rejectsZeroDistanceTopSolidLanding() {
        // ROM PlatformObject/Plat_NoXCheck_AltY (docs/s1disasm/sonic.lst
        // 0x7B06-0x7B0A) gates the land band with an UNSIGNED cmpi.w #-16,d0 /
        // blo, which rejects the exact-touch case d0 = 0 (0x0000 <u 0xFFF0):
        // standable band is d0 in [-16,-1] (strict penetration). Combined with
        // the obY-8 detection offset (getTopLandingSnapAdjustment) applied to the
        // new-landing detection band, this makes the engine land on the same
        // frame as ROM. Verified by BizHawk capture of GHZ2-CR (BK2 8991 d0=0
        // keeps falling; BK2 8992 d0=-5 lands).
        return true;
    }

    @Override
    public int getTopLandingSnapAdjustment(PlayableEntity player, int solidTopYRadius) {
        // PlatformObject builds its entry surface from obY-8 and then snaps via
        // add.w d0,d2 / addq.w #3,d2 (docs/s1disasm/_incObj/sub PlatformObject.asm:17-42).
        // Continued riding still uses MvSonicOnPtfm2's obY-9 surface; this
        // adjustment applies only to the first landing snap (and, via the
        // controller's detection-band offset, to the matching new-landing detect).
        return -1;
    }

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        // Standing state is managed via isPlayerRiding() check in update()
    }

    @Override
    public boolean isSolidFor(PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        return !isDestroyed();
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(PRIORITY);
    }

    @Override
    public boolean isPersistent() {
        // out_of_range uses objoff_32 (spawn X), not current X
        return !isDestroyed() && isInRangeAt(baseX);
    }

    /**
     * Nudge: sinHex(bobAngle) >> 6, added to workingY.
     * Creates a downward spring effect when player stands on the platform.
     */
    private void applyNudge() {
        y = workingY + bobHelper.getOffset();
    }

    /**
     * Dispatches to the correct movement handler based on moveType.
     */
    private void applyMovement(AbstractPlayableSprite player) {
        switch (moveType) {
            case 0x00 -> { /* Stationary */ }
            case 0x01 -> moveHorizontalSelfDriven(false);
            case 0x02 -> moveVerticalSelfDriven(false, false);
            case 0x03 -> moveFallOnStand();
            case 0x04 -> moveFalling(player);
            case 0x05 -> moveHorizontalSelfDriven(true);
            case 0x06 -> moveVerticalSelfDriven(true, false);
            case 0x07 -> moveSwitchActivated();
            case 0x08 -> moveRising();
            // 0x09 maps to type00 (stationary) in the jump table
            case 0x09 -> { /* Stationary */ }
            case 0x0A -> moveVerticalSelfDriven(false, true);
            case 0x0B -> moveVerticalGlobal(false);
            case 0x0C -> moveVerticalGlobal(true);
        }
    }

    /**
     * Types 01 and 05: Horizontal oscillation using self-driven oscillator (v_oscillate+$1A).
     * Type 01: d1 = obAngle - $40 (rightward start)
     * Type 05: d1 = -obAngle + $40 (leftward start)
     * Uses cached oscillator value (obAngle) for 1-frame delay, then stores current value.
     */
    private void moveHorizontalSelfDriven(boolean reversed) {
        int d1;
        if (reversed) {
            // .type05: neg.b d1 / addi.b #$40,d1
            d1 = (byte) (-cachedOscillator + 0x40);
        } else {
            // .type01: subi.b #$40,d1
            d1 = (byte) (cachedOscillator - 0x40);
        }
        x = baseX + d1;
        // .chgmotion: move.b (v_oscillate+$1A).w,objoff_26(a0)
        cachedOscillator = OscillationManager.getByte(OSC_SELF_DRIVEN);
    }

    protected SolidCheckpointBatch checkpointAll() {
        return services().solidExecution().resolveSolidNowAll();
    }

    protected boolean hasStandingContact(SolidCheckpointBatch batch) {
        for (PlayerSolidContactResult result : batch.perPlayer().values()) {
            if (result != null && result.standingNow()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Types 02, 06, 0A: Vertical oscillation using self-driven oscillator (v_oscillate+$1A).
     * Type 02: d1 = obAngle - $40 (downward start)
     * Type 06: d1 = -obAngle + $40 (upward start)
     * Type 0A: same as 02 but with half amplitude (asr.w #1,d1)
     * Uses cached oscillator value (obAngle) for 1-frame delay, then stores current value.
     */
    private void moveVerticalSelfDriven(boolean reversed, boolean halfAmplitude) {
        int d1;
        if (reversed) {
            // .type06: neg.b d1 / addi.b #$40,d1
            d1 = (byte) (-cachedOscillator + 0x40);
        } else {
            // .type02: subi.b #$40,d1
            d1 = (byte) (cachedOscillator - 0x40);
        }
        if (halfAmplitude) {
            // .type0A: asr.w #1,d1
            d1 >>= 1;
        }
        workingY = baseY + d1;
        // .chgmotion: move.b (v_oscillate+$1A).w,objoff_26(a0)
        cachedOscillator = OscillationManager.getByte(OSC_SELF_DRIVEN);
    }

    /**
     * Types 0B and 0C: Vertical oscillation using global oscillator (v_oscillate+$E).
     * Type 0B: d1 = globalOsc - $30 (downward start)
     * Type 0C: d1 = -globalOsc + $30 (upward start)
     * These read directly from global oscillator (not cached), but still fall through
     * to .chgmotion which updates the cached oscillator from self-driven source.
     */
    private void moveVerticalGlobal(boolean reversed) {
        int motionVar = OscillationManager.getByte(OSC_GLOBAL);
        int d1;
        if (reversed) {
            // .type0C: neg.b d1 / addi.b #$30,d1
            d1 = (byte) (-motionVar + 0x30);
        } else {
            // .type0B: subi.b #$30,d1
            d1 = (byte) (motionVar - 0x30);
        }
        workingY = baseY + d1;
        // .chgmotion: move.b (v_oscillate+$1A).w,objoff_26(a0)
        // Types 0B/0C fall through .type02_move -> .chgmotion
        cachedOscillator = OscillationManager.getByte(OSC_SELF_DRIVEN);
    }

    /**
     * Type 03: Fall when stood on, with delay.
     * When player stands on platform, start 30-frame countdown.
     * When countdown expires, set 32-frame fall timer and transition to type 04.
     */
    private void moveFallOnStand() {
        if (timer > 0) {
            // .type03_wait: subq.w #1,objoff_3A / bne .type03_nomove
            timer--;
            if (timer == 0) {
                // Timer expired: start falling sequence
                timer = FALL_COUNTDOWN;
                moveType = 0x04; // addq.b #1,obSubtype(a0)
            }
            return;
        }

        // btst #3,obStatus(a0) - check if player is standing
        if (playerStanding) {
            timer = FALL_STAND_DELAY; // move.w #30,objoff_3A(a0)
        }
    }

    /**
     * Type 04: Falling platform with gravity.
     * Counts down timer, then detaches player and transitions to routine 8 (Plat_Action).
     * Uses 16.16 fixed-point for position (move.l objoff_2C / asl.l #8).
     * Deletes when below bottom boundary + $E0.
     */
    private void moveFalling(AbstractPlayableSprite player) {
        if (timer > 0) {
            timer--;
            if (timer == 0) {
                // Timer expired: detach player if standing
                if (playerStanding && player != null) {
                    // bset #1,obStatus(a1) - set player airborne
                    player.setAir(true);
                    // bclr #3,obStatus(a1) - clear player standing-on-object
                    // bclr #3,obStatus(a0) - clear object standing flag
                    var objectManager = services().objectManager();
                    if (objectManager != null) {
                        objectManager.clearRidingObject(player);
                    }
                    // move.w obVelY(a0),obVelY(a1) - transfer platform velocity to player
                    player.setYSpeed((short) yVelocity);
                    playerStanding = false;
                }
                // move.b #8,obRoutine(a0) — transition to routine 8 (Plat_Action)
                // In routine 8, nudge angle is frozen (no increment/decrement).
                inFallingRoutine = true;
            }
        }

        // Apply gravity to 32-bit position (objoff_2C as 16.16 fixed point)
        // move.l objoff_2C(a0),d3
        int yPos32 = (workingY << 16) | (yFrac & 0xFFFF);
        // move.w obVelY(a0),d0 / ext.l d0 / asl.l #8,d0 / add.l d0,d3
        int vel32 = (int) (short) yVelocity;
        yPos32 += vel32 << 8;
        // move.l d3,objoff_2C(a0)
        workingY = yPos32 >> 16;
        yFrac = yPos32 & 0xFFFF;
        // addi.w #$38,obVelY(a0)
        yVelocity += FALL_GRAVITY;

        // Check delete: cmp.w objoff_2C(a0),d0 / bhs.s .locret
        int bottomBoundary = getBottomBoundary();
        if (workingY > bottomBoundary + FALL_DELETE_OFFSET) {
            // move.b #6,obRoutine(a0) — Plat_Delete.
            // Clear the active spawn as well so the platform doesn't recreate
            // immediately while still inside the placement window.
            destroyWithWindowGatedRespawn();
        }
    }

    /**
     * Type 07: Switch-activated rising.
     * High nybble of subtype indexes into f_switch table.
     * When switch is pressed, start 60-frame delay, then transition to type 08.
     */
    private void moveSwitchActivated() {
        if (timer > 0) {
            // .type07_wait: subq.w #1,objoff_3A / bne .type07_nomove
            timer--;
            if (timer == 0) {
                moveType = 0x08; // addq.b #1,obSubtype(a0)
            }
            return;
        }

        // Check switch state: lsr.w #4,d0 / tst.b (a2,d0.w)
        int switchIndex = (spawn.subtype() >> 4) & 0x0F;
        if (services().gameService(Sonic1SwitchManager.class).isPressed(switchIndex)) {
            timer = SWITCH_DELAY;
        }
    }

    /**
     * Type 08: Rising after switch activation.
     * Rises at 2px/frame until it has moved $200 pixels above baseY.
     * Then transitions to type 00 (stationary).
     * Disasm uses bne (not-equal), so exact match is required.
     */
    private void moveRising() {
        // subq.w #2,objoff_2C(a0)
        workingY -= RISE_SPEED;

        // subi.w #$200,d0 / cmp.w objoff_2C(a0),d0 / bne.s .type08_nostop
        int targetY = baseY - RISE_DISTANCE;
        if (workingY == targetY) {
            moveType = 0x00; // clr.b obSubtype(a0) -> type 00 (stationary)
        }
    }

    /**
     * Object 18 uses DeleteObject once the faller passes v_limitbtm2+$E0.
     * Keep the spawn suppressed until it exits the placement window, matching
     * ROM-style "don't instantly respawn in place" behavior.
     */
    private void destroyWithWindowGatedRespawn() {
        if (!isDestroyed() ) {
            var objectManager = services().objectManager();
            ObjectLifetimeOps.removeSpawnFromActive(objectManager, spawn);
        }
        setDestroyed(true);
    }

    /**
     * Get the level's bottom boundary (v_limitbtm2 equivalent).
     */
    private int getBottomBoundary() {
        var camera = services().camera();
        return camera != null ? camera.getMaxY() : 0x700;
    }

}
