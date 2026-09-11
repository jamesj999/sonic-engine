package com.openggf.sprites.playable;

import com.openggf.game.CanonicalAnimation;
import com.openggf.game.GameStateManager;
import com.openggf.audio.GameSound;
import com.openggf.sprites.NativePositionOps;
import com.openggf.physics.CollisionSystem;
import com.openggf.physics.FrameCollisionPlan;
import com.openggf.game.rules.GameRules;

import java.util.Objects;

/** ROM-accurate shared owner for manual and scripted Tails carry state. */
public final class TailsCarryController {
    private static final int CARRIED_FRAME_DELAY = 0x0B;
    private static final int[] CARRIED_MAPPING_FRAMES = {
            0x91, 0x91, 0x90, 0x90, 0x90, 0x90, 0x90, 0x90,
            0x92, 0x92, 0x92, 0x92, 0x92, 0x92, 0x91, 0x91
    };

    public enum CarryContext { NONE, MANUAL, CNZ, MGZ_BOSS }

    public record Snapshot(short latchX, short latchY, boolean carrying,
                           boolean parentagePending, int cooldown, CarryContext context) { }

    private final AbstractPlayableSprite carrier;
    private short latchX;
    private short latchY;
    private boolean carrying;
    private boolean parentagePending;
    private int cooldown;
    private CarryContext context = CarryContext.NONE;

    public TailsCarryController(AbstractPlayableSprite carrier) {
        this.carrier = Objects.requireNonNull(carrier, "carrier");
    }

    public boolean isCarryingMainCharacter() { return carrying; }
    public CarryContext getContext() { return context; }

    public boolean tryGrabMainCharacter() {
        AbstractPlayableSprite main = resolveMain();
        GameRules rules = carrier.getGameRules();
        SidekickCpuController cpu = carrier.getCpuController();
        boolean playerTwoOwnsCarrier = carrier.isCpuControlled()
                && cpu != null && cpu.isUnderManualControl();
        if (carrier.getSecondaryAbility() != SecondaryAbility.FLY
                || rules == null || rules.playerCapability() == null
                || !rules.playerCapability().tailsFlightEnabled()
                || carrier.getTailsFlightController() == null
                || !carrier.getTailsFlightController().isActive()
                || !playerTwoOwnsCarrier
                || main == null || main == carrier || carrying || cooldown != 0 || !isInContactWindow(main)
                || main.isObjectControlled() || main.isHurt() || main.getDead()
                || main.isDebugMode() || main.getSpindash()) {
            return false;
        }
        attach(main, CarryContext.MANUAL, true);
        return true;
    }

    private boolean isInContactWindow(AbstractPlayableSprite main) {
        int xWindow = (main.getCentreX() - carrier.getCentreX() + 0x10) & 0xFFFF;
        int yWindow = main.getCentreY() - carrier.getCentreY() - 0x20;
        if (isReverseGravity()) yWindow += 0x50;
        return xWindow < 0x20 && (yWindow & 0xFFFF) < 0x10;
    }

    private boolean isReverseGravity() {
        GameStateManager gameState = carrier.currentGameStateOrNull();
        return gameState != null && gameState.isReverseGravityActive();
    }

    private void attach(AbstractPlayableSprite main, CarryContext newContext, boolean playSfx) {
        main.setXSpeed((short) 0);
        main.setYSpeed((short) 0);
        main.setGSpeed((short) 0);
        main.setAngle((byte) 0);
        NativePositionOps.writeXPosPreserveSubpixel(main, carrier.getCentreX());
        int yOffset = isReverseGravity() ? -0x1C : 0x1C;
        NativePositionOps.writeYPosPreserveSubpixel(main, carrier.getCentreY() + yOffset);
        main.setDirection(carrier.getDirection());
        int carriedAnimationId = main.resolveAnimationId(CanonicalAnimation.TAILS_CARRIED);
        // Tails_Carry_Sonic writes the carried id to both anim and prev_anim,
        // then clears anim_frame_timer and anim_frame in the carrier's slot.
        // That happens after Sonic's Animate call, so publish the native fields
        // immediately rather than waiting for the next player update.
        main.setAnimationId(carriedAnimationId);
        main.getAnimationManager().publishPreviousAnimationId(carriedAnimationId);
        main.setAnimationTick(0);
        main.setAnimationFrameIndex(0);
        main.setForcedAnimationId(carriedAnimationId);
        // Native object_control=$03 skips Sonic's ordinary Animate_Sonic call;
        // both shared animation-byte ticks instead come from the CPU and
        // post-flight Tails_Carry_Sonic passes.
        main.setObjectMappingFrameControl(true);
        // sub_1459E writes object_control=$03: bits 0-6 suppress normal
        // movement but do not take the bit-7 TouchResponse bypass.
        ObjectControlState.nativeBits0To6CpuAllowedMovementSuppressed().applyTo(main);
        main.setAir(true);
        main.setRollingJump(false);
        main.setSpindash(false);
        main.setSpindashCounter((short) 0);
        main.setJumping(false);
        main.setXSpeed(carrier.getXSpeed());
        main.setYSpeed(carrier.getYSpeed());
        latchX = main.getXSpeed();
        latchY = main.getYSpeed();
        carrying = true;
        parentagePending = true;
        cooldown = 0;
        context = newContext;
        if (playSfx && carrier.currentAudioManager() != null) {
            carrier.currentAudioManager().playSfx(GameSound.GRAB);
        }
    }

    public void updateAfterTailsCollision(int mainHeldInput) {
        AbstractPlayableSprite main = resolveMain();
        if (!carrying) {
            if (cooldown > 0) cooldown--;
            if (cooldown == 0) tryGrabMainCharacter();
            return;
        }
        if (main == null) {
            if (parentagePending) {
                return;
            }
            release(0x3C);
            return;
        }
        if (carrier.isHurt()) {
            releaseAfterCarrierHurt();
            return;
        }
        if (carrier.getDead() || carrier.isObjectControlled()
                || main.isHurt() || main.getDead()
                || !main.isObjectControlled()) {
            release(0x3C);
            return;
        }
        if (!main.getAir()) {
            carrier.getTailsFlightController().clear();
            main.setYSpeed((short) -0x0100);
            release(0);
            return;
        }
        if (main.getXSpeed() != latchX || main.getYSpeed() != latchY) {
            release(0x3C);
            return;
        }
        if ((mainHeldInput & AbstractPlayableSprite.INPUT_JUMP) != 0) {
            performJumpRelease(main, mainHeldInput);
            return;
        }
        followAndCollide(main);
    }

    private void followAndCollide(AbstractPlayableSprite main) {
        NativePositionOps.writeXPosPreserveSubpixel(main, carrier.getCentreX());
        int yOffset = isReverseGravity() ? -0x1C : 0x1C;
        NativePositionOps.writeYPosPreserveSubpixel(main, carrier.getCentreY() + yOffset);
        main.setDirection(carrier.getDirection());
        updateCarriedMapping(main);
        main.setXSpeed(carrier.getXSpeed());
        main.setYSpeed(carrier.getYSpeed());
        CollisionSystem collision = main.currentCollisionSystemOrNull();
        if (collision != null) {
            collision.resolveAirCollision(FrameCollisionPlan.terrainOnly(), main, sprite -> {
                if (sprite.getRolling()) {
                    int radiusDelta = sprite.getYRadius() - sprite.getStandYRadius();
                    sprite.setRollingFlagPreserveRadii(false);
                    NativePositionOps.writeYPosPreserveSubpixel(sprite, sprite.getCentreY() + radiusDelta);
                }
                sprite.applyStandingRadii(false);
                sprite.setYSpeed((short) 0);
                sprite.setGSpeed(sprite.getXSpeed());
                sprite.setAir(false);
                sprite.setPushing(false);
                sprite.setRollingJump(false);
                sprite.setJumping(false);
            });
        }
        latchX = main.getXSpeed();
        latchY = main.getYSpeed();
        parentagePending = false;
    }

    /**
     * Advances {@code AniRaw_Tails_Carry}, which runs from
     * {@code Tails_Carry_Sonic} after the main player's normal Animate pass.
     * Both routines share the native animation timer and frame bytes.
     */
    private void updateCarriedMapping(AbstractPlayableSprite main) {
        int remaining = main.getAnimationTick() - 1;
        if (remaining >= 0) {
            main.setAnimationTick(remaining);
            return;
        }

        main.setAnimationTick(CARRIED_FRAME_DELAY);
        int frameIndex = main.getAnimationFrameIndex();
        if (frameIndex < 0 || frameIndex >= CARRIED_MAPPING_FRAMES.length) {
            // Native reads the terminal $FF after incrementing anim_frame,
            // then resets anim_frame to zero and displays the first entry.
            main.setAnimationFrameIndex(0);
            main.setMappingFrame(CARRIED_MAPPING_FRAMES[0]);
            return;
        }
        main.setMappingFrame(CARRIED_MAPPING_FRAMES[frameIndex]);
        main.setAnimationFrameIndex(frameIndex + 1);
    }

    private void performJumpRelease(AbstractPlayableSprite main, int input) {
        boolean left = (input & AbstractPlayableSprite.INPUT_LEFT) != 0;
        boolean right = (input & AbstractPlayableSprite.INPUT_RIGHT) != 0;
        if (left) main.setXSpeed((short) -0x0200);
        if (right) main.setXSpeed((short) 0x0200);
        main.setYSpeed((short) -0x0380);
        main.setAir(true);
        CollisionSystem collision = main.currentCollisionSystemOrNull();
        if (collision != null) {
            collision.clearRidingObjectForJump(main);
        }
        main.setJumping(true);
        main.applyRollingRadii(false);
        main.setRollingFlagPreserveRadii(true);
        main.setRollingJump(false);
        main.setAnimationId(2);
        release(left || right || (input & (AbstractPlayableSprite.INPUT_UP | AbstractPlayableSprite.INPUT_DOWN)) != 0
                ? 0x3C : 0x12);
    }
    public void forceScriptedCarry(CarryContext context) {
        Objects.requireNonNull(context, "context");
        if (context == CarryContext.NONE || context == CarryContext.MANUAL) {
            throw new IllegalArgumentException("Scripted carry requires CNZ or MGZ_BOSS context");
        }
        AbstractPlayableSprite main = resolveMain();
        if (main == null) {
            return;
        }
        attach(main, context, false);
    }
    public void clearAndReleaseMain() {
        release(0x3C);
    }

    /**
     * Releases the main player when damage enters the carrier's hurt routine.
     * <p>
     * S3K clears Player_1 {@code object_control} and then clears the full
     * {@code Flying_carrying_Sonic_flag} word, including its cooldown byte
     * (sonic3k.asm:29187-29192). This must happen with the hurt transition,
     * before the main player's next touch-response pass.
     */
    public void releaseAfterCarrierHurt() {
        if (carrying) {
            release(0);
        }
    }

    void releaseWithCooldown(int releaseCooldown) {
        release(releaseCooldown);
    }

    private void release(int releaseCooldown) {
        AbstractPlayableSprite main = resolveMain();
        if (main != null && main != carrier) {
            ObjectControlState.none().applyTo(main);
            main.setControlLocked(false);
            main.setForcedAnimationId(-1);
            main.setObjectMappingFrameControl(false);
            // The engine updates Player 1's animation before the later CPU
            // Tails slot releases a grounded carry. That earlier pass can
            // temporarily re-apply the forced carried id over the Walk byte
            // written by Player_TouchFloor. Native object-slot order leaves
            // the landing write intact, so restore it as the carry owner exits.
            if (!main.getAir() && context == CarryContext.CNZ) {
                int walkAnimationId = main.resolveAnimationId(CanonicalAnimation.WALK);
                if (walkAnimationId >= 0) {
                    main.setAnimationId(walkAnimationId);
                }
            }
        }
        carrying = false;
        parentagePending = false;
        cooldown = releaseCooldown;
        context = CarryContext.NONE;
    }

    private AbstractPlayableSprite resolveMain() {
        var sprites = carrier.currentSpriteManagerOrNull();
        AbstractPlayableSprite main = sprites != null ? sprites.getMainPlayable() : null;
        return main == carrier ? null : main;
    }

    public Snapshot capture() {
        return new Snapshot(latchX, latchY, carrying, parentagePending, cooldown, context);
    }

    public void restore(Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        latchX = snapshot.latchX();
        latchY = snapshot.latchY();
        carrying = snapshot.carrying();
        parentagePending = snapshot.parentagePending();
        cooldown = snapshot.cooldown();
        context = snapshot.context();
    }

    void setParentagePending(boolean value) { parentagePending = value; }
    void setCarrying(boolean value) { carrying = value; }
    void setCooldown(int value) { cooldown = value; }
    int cooldown() { return cooldown; }
    int decrementCooldown() { if (cooldown > 0) cooldown--; return cooldown; }
    boolean parentagePending() { return parentagePending; }
    boolean velocityLatchMatches(AbstractPlayableSprite main) {
        return main != null && main.getXSpeed() == latchX && main.getYSpeed() == latchY;
    }
    void latchVelocity(AbstractPlayableSprite main) {
        latchX = main.getXSpeed();
        latchY = main.getYSpeed();
    }
    void clearState() {
        latchX = 0;
        latchY = 0;
        carrying = false;
        parentagePending = false;
        cooldown = 0;
        context = CarryContext.NONE;
    }
}
