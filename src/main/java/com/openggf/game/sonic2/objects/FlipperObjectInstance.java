package com.openggf.game.sonic2.objects;
import com.openggf.game.PlayableEntity;
import com.openggf.game.solid.ContactKind;
import com.openggf.game.solid.PlayerSolidContactResult;
import com.openggf.game.solid.SolidCheckpointBatch;
import com.openggf.level.objects.BoxObjectInstance;
import com.openggf.level.objects.ObjectAnimationState;

import com.openggf.audio.GameSound;
import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.*;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.Direction;
import com.openggf.physics.TrigLookupTable;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.ObjectControlState;

import java.io.IOException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

/**
 * CNZ Flipper Object (Obj86).
 * <p>
 * Launches the player when activated. Two types exist:
 * <ul>
 *   <li><b>Vertical Flipper (subtype 0x00)</b>: Player stands on it, launches upward with angle-based velocity</li>
 *   <li><b>Horizontal Flipper (subtype 0x01)</b>: Player pushes against it, launches horizontally</li>
 * </ul>
 * <p>
 * <b>Disassembly Reference:</b> s2.asm lines 57800-58058
 */
public class FlipperObjectInstance extends BoxObjectInstance
        implements SolidObjectProvider, SolidObjectListener, SlopedSolidProvider, RewindRecreatable {

    private static final int TYPE_VERTICAL = 0;
    private static final int TYPE_HORIZONTAL = 1;
    private static final int SLOPE_CURVE_0_ROM_ADDR = 0x2B3C6;
    private static final int SLOPE_CURVE_1_ROM_ADDR = 0x2B3EA;
    private static final int SLOPE_CURVE_2_ROM_ADDR = 0x2B40E;

    // Slope curves from s2.asm byte_2B3C6, byte_2B3EA, byte_2B40E
    private static final byte[] SLOPE_CURVE_0 = {
            7, 7, 7, 7, 7, 7, 7, 8, 9, 10, 11, 10, 9, 8, 7, 6,
            5, 4, 3, 2, 1, 0, -1, -2, -3, -4, -5, -6, -7, -8, -9, -10,
            -11, -12, -13, -14
    };

    private static final byte[] SLOPE_CURVE_1 = {
            6, 6, 6, 6, 6, 6, 7, 8, 9, 9, 9, 9, 9, 9, 8, 8,
            8, 8, 8, 8, 7, 7, 7, 7, 6, 6, 6, 6, 5, 5, 4, 4,
            4, 4, 4, 4
    };

    private static final byte[] SLOPE_CURVE_2 = {
            5, 5, 5, 5, 5, 6, 7, 8, 9, 10, 11, 11, 12, 12, 13, 13,
            14, 14, 15, 15, 16, 16, 17, 17, 18, 18, 17, 17, 16, 16, 16, 16,
            16, 16, 16, 16
    };

    private static final int ANIM_VERTICAL_IDLE = 0;
    private static final int ANIM_VERTICAL_TRIGGER = 1;
    private static final int ANIM_HORIZONTAL_IDLE = 2;
    private static final int ANIM_HORIZONTAL_TRIGGER_LEFT = 3;
    private static final int ANIM_HORIZONTAL_TRIGGER_RIGHT = 4;
    private static final ObjectPlayerParticipationPolicy PLAYER_PARTICIPATION =
            ObjectPlayerParticipationPolicy.MAIN_PLUS_ENGINE_SIDEKICKS_AS_NATIVE_P2_EXTENDED;

    private ObjectAnimationState animationState;
    private boolean animInitialized;
    private int idleAnimId;
    private int mappingFrame;
    // ROM parity: Obj86 has no global cooldown; each player is tracked
    // independently via objoff_36 (P1) / objoff_37 (P2) in s2.asm:57870-57879.
    // The engine previously used a single launchCooldown counter that would
    // suppress the contact callback for ALL players for 16 frames after any
    // launch, so a Tails-vs-flipper push immediately after a Sonic-vs-flipper
    // launch silently dropped (CNZ trace f201, slot 23 horizontal flipper at
    // @0280,0368).  Switch to a per-player cooldown so a launch on one
    // character cannot starve the other.
    private final IdentityHashMap<AbstractPlayableSprite, Integer> launchCooldown =
            new IdentityHashMap<>();

    // Vertical flipper state tracking (per loc_2B20A in s2.asm).
    // ROM parity: Obj86_UpwardsType calls loc_2B20A TWICE -- once for the
    // MainCharacter with its own standing-state byte objoff_36(a0), once for
    // the Sidekick with objoff_37(a0) (s2.asm:58286-58295).  loc_2B20A reads
    // (a3) (that player's byte): 0 means "not yet standing" -> the first-stand
    // branch that sets rolling and addq.w #5,y_pos; nonzero means "already
    // on" -> the jump/slide branch (s2.asm:58315-58316).  The state must
    // therefore be PER PLAYER: when Sonic is already riding the flipper a
    // descending Tails must still run the first-stand branch and get the +5
    // nudge / rolling bit (CNZ trace f1775).  A single shared int let Sonic's
    // stand suppress Tails' first-stand seating, so Tails seated 5 px too high
    // with no rolling bit (st=09 vs ROM st=0D).
    // 0 = not standing, 1 = standing/rolling on flipper.
    private final IdentityHashMap<AbstractPlayableSprite, Integer> playerFlipperState =
            new IdentityHashMap<>();

    // ROM objoff_38(a0): a SINGLE shared launch-pending byte (s2.asm:58361-58362,
    // 58296-58303), NOT per-player.  loc_2B23C sets objoff_38=1 via loc_2B288
    // when EITHER standing player presses jump (the gate reads that player's
    // Ctrl word: Ctrl_1_Logical for the leader, Ctrl_2 for the sidekick --
    // s2.asm:58288-58295).  After both players are processed, Obj86_UpwardsType
    // checks objoff_38 once and, if set, calls loc_2B290 for the Sidekick AND
    // the MainCharacter, launching EVERY player whose flipper standing bit is
    // still set (loc_2B290 bclr's that bit and returns early if it was clear).
    // So one player's jump launches BOTH players standing on the flipper.  The
    // engine previously gated launch per player on isJumpPressed(); the CPU
    // sidekick has no synthetic jump, so a leader-jump launched Sonic but left
    // Tails seated forever (CNZ trace f1783: tails_air expected=1, actual=0).
    private boolean verticalLaunchTriggered = false;

    // Track the player(s) currently locked by this flipper.
    // ROM: loc_2B20A runs every frame and checks the standing bit even when the player
    // has moved away. Our onSolidContact callback only fires when there IS a contact,
    // so we must check in update() whether the player has left and release the lock.
    // Per-player so Sonic and Tails can each be locked simultaneously
    // (objoff_36 / objoff_37 are independent).
    private final IdentityHashMap<AbstractPlayableSprite, Boolean> lockedPlayerPrevSuppressed =
            new IdentityHashMap<>();
    private final IdentityHashMap<AbstractPlayableSprite, Boolean> lockedPlayerPrevPinballMode =
            new IdentityHashMap<>();

    public FlipperObjectInstance(ObjectSpawn spawn, String name) {
        super(spawn, name, 8, 8, 0.8f, 0.4f, 0.2f, false);
        this.idleAnimId = isHorizontal() ? ANIM_HORIZONTAL_IDLE : ANIM_VERTICAL_IDLE;
        this.mappingFrame = isHorizontal() ? 4 : 0;
    }

    @Override
    public FlipperObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new FlipperObjectInstance(ctx.spawn(), "Flipper");
    }

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        // Manual checkpoints drive Obj86 state; callbacks are intentionally
        // passive so the inline and normal object paths share one state machine.
    }

    @Override
    public SolidExecutionMode solidExecutionMode() {
        return SolidExecutionMode.MANUAL_CHECKPOINT;
    }

    private void applyCheckpointContact(AbstractPlayableSprite player, PlayerSolidContactResult result) {
        if (player == null || result == null) {
            return;
        }

        // If player entered debug mode while on the flipper, reset flipper state
        if (player.isDebugMode() && playerFlipperState.getOrDefault(player, 0) != 0) {
            releaseLockedPlayer(player);
            playerFlipperState.remove(player);
            return;
        }

        if (isHorizontal()) {
            // Horizontal flipper: launch on push (loc_2B35C)
            if (result.pushingNow() && launchCooldown.getOrDefault(player, 0) <= 0) {
                applyHorizontalLaunch(player);
            }
        } else {
            // Vertical flipper per-player pass (loc_2B20A): stand / slide /
            // jump-detect only.  The actual launch is a shared second pass run
            // once after every player is processed (see processVerticalLaunch()),
            // matching ROM's objoff_38 trigger at s2.asm:58296-58303.
            if (result.standingNow()) {
                // Obj01_Control skips the player movement dispatch while
                // obj_control bit 0 is set, but Ctrl_1 -> Ctrl_1_Logical was
                // already copied before that per-player bit is tested
                // (s2.asm:36227-36237). Do not toggle global Control_Locked.
                suppressMovementForLockedPlayer(player);

                if (playerFlipperState.getOrDefault(player, 0) == 0) {
                    applySkippedRideObjectRollClear(player, result);
                    // First frame standing: enter rolling state (loc_2B20A)
                    // We use pinball_mode to prevent rolling from being cleared
                    player.clearObjectPreservedRollingHandoff();
                    player.setPinballMode(true);
                    // ROM: bset #status.player.rolling / bne.s loc_2B238 / addq.w #5,y_pos
                    // (s2.asm:58323-58325): on the first stand frame the flipper
                    // sets the rolling bit and, ONLY if rolling was not already
                    // set, nudges y_pos down by a fixed 5 px -- the same
                    // character-independent literal the horizontal launch uses
                    // at loc_2B3BC (s2.asm:58040-58042).  Engine setRolling()
                    // shrinks the visual height, which already shifts the centre
                    // by (runHeight - rollHeight)/2 (10/2=5 for Sonic, 2/2=1 for
                    // Tails), so the legacy getRollHeightAdjustment() nudge only
                    // netted the correct +5 centre for Sonic and seated the Tails
                    // sidekick 4 px too high (CNZ trace f1775: tails_y
                    // expected=0x0423, got=0x041E).  Mirror the proven
                    // horizontal-path fix: capture pre-roll centre and write
                    // centre += 5 directly so both characters seat at the ROM y.
                    if (!player.getRolling()) {
                        short preCentreY = player.getCentreY();
                        player.setRolling(true);
                        player.setCentreYPreserveSubpixel((short) (preCentreY + 5));
                    }
                    playerFlipperState.put(player, 1);
                } else {
                    // Already on flipper: check for jump button (loc_2B23C ->
                    // loc_2B288 sets the SHARED objoff_38 trigger).  ROM tests
                    // d5 & (A|B|C): the leader's d5 is Ctrl_1_Logical, the
                    // sidekick's is raw Ctrl_2 (s2.asm:58288/58293,58333).
                    if (hasVerticalLaunchPress(player)) {
                        verticalLaunchTriggered = true;
                    } else {
                        // Slide player based on animation frame (loc_2B254)
                        applyFlipperSlide(player);
                    }
                }
            } else {
                // Player left the vertical flipper's standing contact without
                // jumping (loc_2B23C). ROM gates release on Obj86's per-player
                // standing bit, not on all contact being absent; a side overlap
                // after sliding off must still clear obj_control before the next
                // Obj01_Control publishes Ctrl_1_Logical.
                if (playerFlipperState.getOrDefault(player, 0) != 0) {
                    releaseLockedPlayer(player);
                    playerFlipperState.remove(player);
                }
            }
        }
    }

    private boolean hasVerticalLaunchPress(AbstractPlayableSprite player) {
        if (player == null) {
            return false;
        }
        // Obj86 reads the low byte of Ctrl_1_Logical for the MainCharacter and
        // raw Ctrl_2 for the Sidekick (s2.asm:58345-58350,58390). CPU Tails'
        // follow jump is written to Ctrl_2_Logical, never raw Ctrl_2, so it must
        // not trigger the shared objoff_38 launch.
        if (player.isCpuControlled()) {
            return player.isRawControllerJumpJustPressed();
        }
        return player.isLogicalJumpPressActive();
    }

    private void applySkippedRideObjectRollClear(AbstractPlayableSprite player, PlayerSolidContactResult result) {
        if (player == null || result == null
                || !result.preContact().air()
                || !result.preContact().rolling()
                || !player.getRolling()) {
            return;
        }
        // ROM RideObject_SetRide calls Tails/Sonic_ResetOnFloor_Part2 directly
        // for airborne object landings (s2.asm:35986-36030), bypassing the
        // terrain landing pinball_mode guard. If the shared landing path
        // preserved pinball rolling, restore the direct Part2 side effect here
        // before Obj86's first-stand bset/addq #5 branch runs (s2.asm:58371-58386).
        player.setRolling(false);
        player.setY((short) (player.getY() - player.getRollHeightAdjustment()));
    }

    /**
     * Shared vertical-launch pass (ROM Obj86_UpwardsType s2.asm:58296-58303 ->
     * loc_2B290 s2.asm:58366-58407).  Run once per frame after every player has
     * been through {@link #applyCheckpointContact}.  ROM clears objoff_38 then
     * calls loc_2B290 for the Sidekick and the MainCharacter; loc_2B290 launches
     * each player whose flipper standing bit is still set (bclr d6 / beq return),
     * so a single jump from EITHER standing player launches BOTH.
     */
    private void processVerticalLaunch(List<PlayableEntity> participants) {
        if (!verticalLaunchTriggered) {
            return;
        }
        verticalLaunchTriggered = false;
        for (PlayableEntity participant : participants) {
            if (participant instanceof AbstractPlayableSprite player
                    && playerFlipperState.getOrDefault(player, 0) != 0
                    && launchCooldown.getOrDefault(player, 0) <= 0) {
                applyVerticalLaunch(player);
            }
        }
    }

    /**
     * Slides the player along the flipper surface based on animation frame.
     * ROM: loc_2B254 - applies small X velocity based on mapping_frame
     */
    private void applyFlipperSlide(AbstractPlayableSprite player) {
        int slideAmount = mappingFrame - 1;
        if (!isFlippedHorizontal()) {
            slideAmount = -slideAmount;
            // ROM sets x_flip, then clears it for non-flipped Obj86 before
            // writing the slide speed (s2.asm:57922-57934).
            player.setDirection(Direction.RIGHT);
        } else {
            player.setDirection(Direction.LEFT);
        }
        player.setX((short)(player.getX() + slideAmount));
        player.setXSpeed((short)(slideAmount << 8));
        player.setGSpeed((short)(slideAmount << 8));
        player.setYSpeed((short) 0);
    }

    private void applyVerticalLaunch(AbstractPlayableSprite player) {
        int dx = player.getCentreX() - spawn.x();
        if (isFlippedHorizontal()) {
            dx = -dx;
        }

        int adjustedDistance = dx + 0x23;
        int cappedDistance = Math.min(adjustedDistance, 0x40);

        int velocityMagnitude = -(0x800 + (cappedDistance << 5));

        int angle = (adjustedDistance >> 2) + 0x40;

        // ROM uses integer CalcSine, then muls/asr.l #8
        // (s2.asm:57966-57982). Floating point trig rounds differently here.
        int yVel = (TrigLookupTable.sinHex(angle) * velocityMagnitude) >> 8;
        int xVel = (TrigLookupTable.cosHex(angle) * velocityMagnitude) >> 8;

        if (isFlippedHorizontal()) {
            xVel = -xVel;
        }

        player.setYSpeed((short) yVel);
        player.setXSpeed((short) xVel);
        player.setAir(true);
        player.setOnObject(false);
        // ROM Obj86 launch writes x_vel/y_vel, sets in_air, clears on_object,
        // and restores obj_control without clearing Status_Push
        // (docs/s2disasm/s2.asm:58420-58449). TailsCPU_Normal can read the
        // live push bit before the next movement/animation pass.

        // ROM: move.b #0,obj_control(a1) at loc_2B2E2 - release control lock
        releaseLockedPlayer(player);

        // Clear solid object riding state to prevent the object system from
        // continuing to track the player's position relative to the flipper.
        // This matches the ROM behavior of clearing status.player.on_object (loc_2B2E2).
        var objectManager = services().objectManager();
        if (objectManager != null) {
            objectManager.clearRidingObject(player);
        }

        // Reset flipper state for this player (ROM clears objoff_36/37 per player)
        playerFlipperState.remove(player);

        triggerVerticalAnimation();
        playFlipperSound();
        launchCooldown.put(player, 16);
    }

    private void applyHorizontalLaunch(AbstractPlayableSprite player) {
        // ROM default: xVel = -0x1000 (LEFT) at loc_2B35C
        int xVel = -0x1000;

        int newX = player.getX() + 8;

        // ROM: If player is RIGHT of flipper (flipper.x - player.x < 0), negate velocity
        // This ensures player is always launched AWAY from the flipper
        boolean playerIsRightOfFlipper = spawn.x() - player.getCentreX() < 0;

        if (playerIsRightOfFlipper) {
            // Player is RIGHT of flipper: launch them RIGHT (away)
            newX -= 16;
            xVel = -xVel;  // +0x1000 (RIGHT)
            player.setDirection(Direction.RIGHT);
        } else {
            // Player is LEFT of flipper: keep xVel = -0x1000 (LEFT, away)
            player.setDirection(Direction.LEFT);
        }

        player.setX((short) newX);
        player.setXSpeed((short) xVel);
        player.setGSpeed((short) xVel);
        // NOTE: y_vel is NOT cleared in the ROM for horizontal flippers (loc_2B35C-loc_2B3BC)
        // The player stays grounded and rolls at high speed - y_vel is handled by the movement system
        // Obj86's horizontal branch does not clear Status_Push after the
        // SolidObject side-push launch (s2.asm:58021-58045). TailsCPU_Normal
        // runs before the following movement/animation pass and reads that
        // live push bit at s2.asm:38943.

        // ROM: move.w #$F,move_lock(a1) - lock player input for 15 frames
        player.setMoveLockTimer(15);
        player.clearObjectPreservedRollingHandoff();
        // ROM: bset #status.player.rolling / bne.s loc_2B3BC / addq.w #5,y_pos
        // Only adjust Y if not already rolling
        if (!player.getRolling()) {
            // ROM parity (s2.asm:58040-58042 set rolling / bne loc_2B3BC /
            // addq.w #5, y_pos): on first contact the flipper pushes
            // y_pos down by a fixed 5 px, regardless of character.  Engine
            // setRolling() shrinks the visual height which already moves
            // the centre by (runHeight - rollHeight)/2 (10/2=5 for Sonic,
            // 2/2=1 for Tails), so applying getRollHeightAdjustment() here
            // only nets +5 centre for Sonic.  Capture pre-roll centre and
            // write centre += 5 directly so Tails matches ROM as well.
            short preCentreY = player.getCentreY();
            player.setRolling(true);
            player.setCentreYPreserveSubpixel((short)(preCentreY + 5));
        }
        // ROM always explicitly sets collision radii (y=14, x=7) at loc_2B3BC
        player.applyRollingRadii(false);

        triggerHorizontalAnimation(playerIsRightOfFlipper);
        playFlipperSound();
        launchCooldown.put(player, 16);
    }

    private void triggerVerticalAnimation() {
        animationState.setAnimId(ANIM_VERTICAL_TRIGGER);
    }

    private void triggerHorizontalAnimation(boolean launchRight) {
        animationState.setAnimId(launchRight ? ANIM_HORIZONTAL_TRIGGER_RIGHT : ANIM_HORIZONTAL_TRIGGER_LEFT);
    }

    private void playFlipperSound() {
        try {
            services().playSfx(GameSound.FLIPPER);
        } catch (Exception e) {
            // Prevent audio failure from breaking game logic
        }
    }

    private boolean isHorizontal() {
        return (spawn.subtype() & 0x01) != 0;
    }

    private boolean isFlippedHorizontal() {
        return (spawn.renderFlags() & 0x1) != 0;
    }

    @Override
    public boolean isSolidFor(PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        return true;
    }

    @Override
    public SolidObjectParams getSolidParams() {
        if (isHorizontal()) {
            // ROM: d1=#$13 (19), d2=#$18 (24), d3=#$19 (25) at loc_2B312
            return SolidObjectParams.of(19, 24, 25);
        }
        // ROM: d1=#$23 (35), d2=#6 at loc_2B1B6
        return SolidObjectParams.of(35, 6, 6);
    }

    @Override
    public byte[] getSlopeData() {
        if (isHorizontal()) {
            return null;
        }
        int frame = mappingFrame % 3;
        return switch (frame) {
            case 1 -> SLOPE_CURVE_1;
            case 2 -> SLOPE_CURVE_2;
            default -> SLOPE_CURVE_0;
        };
    }

    @Override
    public Integer sampleSlopeByte(int sampleIndex) {
        byte[] slopeData = getSlopeData();
        if (slopeData == null) {
            return null;
        }
        if (sampleIndex >= 0 && sampleIndex < slopeData.length) {
            return (int) (byte) slopeData[sampleIndex];
        }
        try {
            return (int) services().rom().readByte(currentSlopeCurveRomAddress() + sampleIndex);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private int currentSlopeCurveRomAddress() {
        return switch (mappingFrame % 3) {
            case 1 -> SLOPE_CURVE_1_ROM_ADDR;
            case 2 -> SLOPE_CURVE_2_ROM_ADDR;
            default -> SLOPE_CURVE_0_ROM_ADDR;
        };
    }

    @Override
    public boolean isSlopeFlipped() {
        return isFlippedHorizontal();
    }

    @Override
    public boolean addsSlopeCatchRangeToVerticalOverlap() {
        return !isHorizontal();
    }

    @Override
    public boolean preservesPostSpecialTouchAirborneSideVelocity() {
        // ObjD7 can write x_vel from TouchResponse immediately before Obj86's
        // manual SolidObject checkpoint in the same frame (s2.asm:59403,
        // s2.asm:57869). This is only valid for an actual same-frame SPECIAL
        // touch; ordinary flipper side contacts still zero x_vel.
        return true;
    }

    @Override
    public boolean usesInclusiveRightEdge() {
        // Obj86 uses ROM helpers whose right-edge rejection is `bhi`, so
        // relX == width*2 remains a valid contact. Horizontal flippers route
        // through SolidObject_cont (docs/s2disasm/s2.asm:58467-58485,
        // 35355-35364); vertical flippers route through SlopedSolid_cont
        // (docs/s2disasm/s2.asm:58250-58279,35263-35272).
        return true;
    }

    @Override
    public boolean bypassesOffscreenSolidGate() {
        // ROM Obj86 (Flipper) reaches SolidObject_cont via
        // SolidObject_Always_SingleCharacter (s2.asm:58418/58427), which jumps
        // straight to SolidObject_cont (s2.asm:35059->35147) WITHOUT the
        // SolidObject_OnScreenTest render_flags(a0) gate at s2.asm:35330-35336.
        // Off-screen flippers therefore still resolve push/side contact in ROM,
        // mirroring the S3K SolidObjectFull2_1P behaviour. Required so enabling
        // CollisionRules.solidObjectOffscreenGate for S2 does not regress CNZ
        // pinball bounces (CNZ1 trace f355).
        return true;
    }

    private void ensureInitialized() {
        if (animInitialized) {
            return;
        }
        animInitialized = true;
        ObjectRenderManager renderManager = services().renderManager();
        this.animationState = new ObjectAnimationState(
                renderManager != null ? renderManager.getAnimations(Sonic2ObjectArtKeys.ANIM_FLIPPER) : null,
                idleAnimId,
                mappingFrame);
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        ensureInitialized();
        // Per-player cooldown decrement (mirrors ROM's independent objoff_36/37
        // tracking in s2.asm:57870-57879).
        if (!launchCooldown.isEmpty()) {
            launchCooldown.entrySet().removeIf(entry -> {
                int next = entry.getValue() - 1;
                if (next <= 0) {
                    return true;
                }
                entry.setValue(next);
                return false;
            });
        }

        SolidCheckpointBatch batch = services().solidExecution().resolveSolidNowAll();
        List<PlayableEntity> participants = playerParticipants(playerEntity);
        for (PlayableEntity participant : participants) {
            if (participant instanceof AbstractPlayableSprite player) {
                PlayerSolidContactResult result = batch.perPlayer().get(participant);
                applyCheckpointContact(player, result != null ? result : checkpoint(player));
            }
        }
        // ROM objoff_38 shared launch pass after both loc_2B20A calls
        // (s2.asm:58296-58303). Vertical flipper only.
        if (!isHorizontal()) {
            processVerticalLaunch(participants);
        }

        animationState.update();
        mappingFrame = animationState.getMappingFrame();
    }

    private List<PlayableEntity> playerParticipants(PlayableEntity updatePlayer) {
        List<PlayableEntity> participants = services().playerQuery().playersFor(PLAYER_PARTICIPATION);
        if (updatePlayer != null && !participants.contains(updatePlayer)) {
            ArrayList<PlayableEntity> withUpdatePlayer = new ArrayList<>(participants.size() + 1);
            withUpdatePlayer.add(updatePlayer);
            withUpdatePlayer.addAll(participants);
            return withUpdatePlayer;
        }
        return participants;
    }

    private PlayerSolidContactResult checkpoint(AbstractPlayableSprite player) {
        return services().solidExecution().resolveSolidNow(player);
    }

    /**
     * Release the movement suppression on the given player.
     * ROM: move.b #0,obj_control(a1) at loc_2B23C when player leaves flipper.
     * Per-player so releasing Sonic does not disturb a still-locked Tails
     * (objoff_36 / objoff_37 are independent in s2.asm:58286-58295).
     */
    private void releaseLockedPlayer(AbstractPlayableSprite player) {
        Boolean prevSuppressed = lockedPlayerPrevSuppressed.remove(player);
        Boolean prevPinballMode = lockedPlayerPrevPinballMode.remove(player);
        if (prevSuppressed != null) {
            ObjectControlState.setMovementSuppressionPreservingOwnership(
                    player, prevSuppressed);
            player.setPinballMode(Boolean.TRUE.equals(prevPinballMode));
        }
    }

    private void suppressMovementForLockedPlayer(AbstractPlayableSprite player) {
        if (!lockedPlayerPrevSuppressed.containsKey(player)) {
            lockedPlayerPrevSuppressed.put(player, player.isObjectControlSuppressesMovement());
            lockedPlayerPrevPinballMode.put(player, player.getPinballMode());
        }
        ObjectControlState.setMovementSuppressionPreservingOwnership(player, true);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager == null) {
            super.appendRenderCommands(commands);
            return;
        }
        PatternSpriteRenderer renderer = renderManager.getRenderer(Sonic2ObjectArtKeys.FLIPPER);
        if (renderer == null || !renderer.isReady()) {
            super.appendRenderCommands(commands);
            return;
        }
        boolean hFlip = isFlippedHorizontal();
        boolean vFlip = (spawn.renderFlags() & 0x2) != 0;
        renderer.drawFrameIndex(mappingFrame, spawn.x(), spawn.y(), hFlip, vFlip);
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(4);
    }
}
