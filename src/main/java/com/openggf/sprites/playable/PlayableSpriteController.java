package com.openggf.sprites.playable;

import com.openggf.game.CanonicalAnimation;
import com.openggf.game.GameModule;
import com.openggf.game.rewind.RewindDeferred;
import com.openggf.level.objects.ObjectControlledSolidContactController;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.SolidContact;
import com.openggf.sprites.managers.PlayableSpriteAnimation;
import com.openggf.sprites.managers.PlayableSpriteMovement;
import com.openggf.sprites.managers.SpindashDustController;
import com.openggf.sprites.managers.TailsFlightController;
import com.openggf.sprites.managers.TailsTailsController;

public class PlayableSpriteController {
    private final AbstractPlayableSprite sprite;
    private final PlayableSpriteMovement movement;
    private final PlayableSpriteAnimation animation;
    private final DrowningController drowning;
    private final TailsFlightController tailsFlight;
    private final TailsCarryController tailsCarry;
    private SpindashDustController spindashDust;
    private TailsTailsController tailsTails;
    private SuperStateController superState;
    private boolean onObjectAtFrameStart;
    private boolean onObjectAtPreviousFrameStart;
    private boolean pushingAtFrameStart;
    private boolean airAtFrameStart;
    private boolean hurtAtFrameStart;
    private boolean hurtRecoveryCompletedThisFrame;
    /** Narrow ownership seam for the MGZ top-platform carry's solid feedback. */
    @RewindDeferred(reason = "active carried solid contact needs stable object identity snapshot")
    private ObjectInstance objectControlledSolidContactOwner;
    private boolean springHandoffPending;
    private int springHandoffXVelocity;
    private int springHandoffYVelocity;

    public PlayableSpriteController(AbstractPlayableSprite sprite) {
        this.sprite = sprite;
        this.movement = new PlayableSpriteMovement(sprite);
        this.animation = new PlayableSpriteAnimation(sprite);
        this.drowning = new DrowningController(sprite);
        this.tailsFlight = new TailsFlightController(sprite);
        this.tailsCarry = new TailsCarryController(sprite);
    }

    public PlayableSpriteMovement getMovement() {
        return movement;
    }

    public PlayableSpriteAnimation getAnimation() {
        return animation;
    }

    public DrowningController getDrowning() {
        return drowning;
    }

    public TailsFlightController getTailsFlight() {
        return tailsFlight;
    }

    public TailsCarryController getTailsCarry() {
        return tailsCarry;
    }

    public SpindashDustController getSpindashDust() {
        return spindashDust;
    }

    public void setSpindashDust(SpindashDustController spindashDust) {
        this.spindashDust = spindashDust;
    }

    public TailsTailsController getTailsTails() {
        return tailsTails;
    }

    public void setTailsTails(TailsTailsController tailsTails) {
        this.tailsTails = tailsTails;
    }

    public SuperStateController getSuperState() {
        return superState;
    }

    public void setSuperState(SuperStateController superState) {
        this.superState = superState;
    }

    public RewindState captureRewindState() {
        return new RewindState(
                movement != null ? movement.captureRewindState() : null,
                spindashDust != null ? spindashDust.captureRewindState() : null,
                animation != null ? animation.captureRewindState() : null,
                drowning != null ? drowning.captureRewindState() : null,
                tailsCarry != null ? tailsCarry.capture() : null,
                superState != null ? superState.captureRewindState() : null,
                tailsTails != null ? tailsTails.captureRewindState() : null);
    }

    public void restoreRewindState(RewindState state) {
        PlayableSpriteMovement.RewindState movementState = state != null ? state.movementState() : null;
        SpindashDustController.RewindState spindashState = state != null ? state.spindashDustState() : null;
        PlayableSpriteAnimation.RewindState animationState = state != null ? state.animationState() : null;
        DrowningController.RewindState drowningState = state != null ? state.drowningState() : null;
        TailsCarryController.Snapshot carryState = state != null ? state.tailsCarryState() : null;
        SuperStateController.RewindState superStateState = state != null ? state.superStateState() : null;
        TailsTailsController.RewindState tailsTailsState =
                state != null ? state.tailsTailsState() : null;
        if (movement != null) {
            movement.restoreRewindState(movementState);
        }
        if (spindashDust != null) {
            spindashDust.restoreRewindState(spindashState);
        }
        if (animation != null) {
            animation.restoreRewindState(animationState);
        }
        if (drowning != null) {
            drowning.restoreRewindState(drowningState);
        }
        if (tailsCarry != null) {
            if (carryState != null) {
                tailsCarry.restore(carryState);
            } else {
                tailsCarry.clearAndReleaseMain();
            }
        }
        if (superState != null && superStateState != null) {
            superState.restoreRewindState(superStateState);
        }
        if (tailsTails != null) {
            tailsTails.restoreRewindState(tailsTailsState);
        }
    }

    public void clearCarryAndReleaseMain() {
        if (tailsCarry != null) {
            tailsCarry.clearAndReleaseMain();
        }
    }

    public void clearTailsFlightIf(boolean enabled) {
        if (enabled && tailsFlight != null) {
            tailsFlight.clear();
        }
    }

    public void resetSuperState() {
        if (superState != null) {
            superState.reset();
        }
    }

    public void captureFrameStartState() {
        onObjectAtPreviousFrameStart = onObjectAtFrameStart;
        onObjectAtFrameStart = sprite.isOnObject();
        pushingAtFrameStart = sprite.getPushing();
        airAtFrameStart = sprite.getAir();
        hurtAtFrameStart = sprite.isHurt();
        hurtRecoveryCompletedThisFrame = false;
    }

    public void restoreFrameStartState(boolean onObject, boolean previousOnObject,
            boolean pushing, boolean hurt, boolean hurtRecoveryCompleted) {
        onObjectAtFrameStart = onObject;
        onObjectAtPreviousFrameStart = previousOnObject;
        pushingAtFrameStart = pushing;
        hurtAtFrameStart = hurt;
        hurtRecoveryCompletedThisFrame = hurtRecoveryCompleted;
    }

    public boolean isOnObjectAtFrameStart() { return onObjectAtFrameStart; }
    public boolean isOnObjectAtPreviousFrameStart() { return onObjectAtPreviousFrameStart; }
    public boolean isPushingAtFrameStart() { return pushingAtFrameStart; }
    public boolean isAirAtFrameStart() { return airAtFrameStart; }
    public boolean isHurtAtFrameStart() { return hurtAtFrameStart; }
    public boolean isHurtRecoveryCompletedThisFrame() { return hurtRecoveryCompletedThisFrame; }
    public void markHurtRecoveryCompleted() { hurtRecoveryCompletedThisFrame = true; }

    public void publishRunAsPreviousAnimation() {
        int animationId = sprite.resolveAnimationId(CanonicalAnimation.RUN);
        if (animationId >= 0) {
            animation.publishPreviousAnimationId(animationId);
        } else {
            animation.resetLastAnimationId();
        }
    }

    public void publishRawAnimation(CanonicalAnimation animation) {
        int animationId = sprite.resolveAnimationId(animation);
        if (animationId >= 0) {
            sprite.setAnimationId(animationId);
        }
    }

    public void publishLandingAnimationWrite() {
        GameModule module = sprite.currentGameModule();
        if (module != null && module.getLevelEventProvider() != null) {
            module.getLevelEventProvider().onPlayableLandingAnimationWrite(sprite);
        }
    }

    public boolean allowsObjectControlledSolidContact(ObjectInstance candidate) {
        if (candidate == null || objectControlledSolidContactOwner == null) {
            return false;
        }
        if (candidate == objectControlledSolidContactOwner) {
            return true;
        }
        return objectControlledSolidContactOwner instanceof ObjectControlledSolidContactController owner
                && owner.allowsObjectControlledSolidContact(sprite, candidate);
    }

    public void notifyObjectControlledSolidContact(ObjectInstance candidate, SolidContact contact) {
        if (candidate == null || contact == null) {
            return;
        }
        if (objectControlledSolidContactOwner instanceof ObjectControlledSolidContactController owner) {
            owner.onObjectControlledSolidContact(sprite, candidate, contact);
        }
    }

    public Short projectedObjectControlledSolidContactXSpeed(ObjectInstance candidate) {
        if (objectControlledSolidContactOwner instanceof ObjectControlledSolidContactController owner
                && candidate != null && owner.allowsObjectControlledSolidContact(sprite, candidate)) {
            return owner.projectedSolidContactXSpeed(sprite, candidate);
        }
        return null;
    }

    public void notifyObjectControlledSolidContactInvalidated(ObjectInstance candidate) {
        if (candidate != null
                && objectControlledSolidContactOwner instanceof ObjectControlledSolidContactController owner) {
            owner.onObjectControlledSolidContactInvalidated(sprite, candidate);
        }
    }

    public void setObjectControlledSolidContactOwner(ObjectInstance owner) {
        objectControlledSolidContactOwner = owner;
        if (owner == null) {
            clearSpringHandoff();
        }
    }

    public void clearObjectControlledSolidContactOwner() {
        objectControlledSolidContactOwner = null;
    }

    public boolean isObjectControlledSolidContactOwnedBy(ObjectInstance candidate) {
        return objectControlledSolidContactOwner == candidate;
    }

    public boolean hasObjectControlledSolidContactOwner() {
        return objectControlledSolidContactOwner != null;
    }

    public void recordSpringHandoff(int xVelocity, int yVelocity) {
        if (objectControlledSolidContactOwner == null) {
            return;
        }
        springHandoffPending = true;
        springHandoffXVelocity = xVelocity;
        springHandoffYVelocity = yVelocity;
    }

    public void restoreSpringHandoff(boolean pending, int xVelocity, int yVelocity) {
        springHandoffPending = pending;
        springHandoffXVelocity = xVelocity;
        springHandoffYVelocity = yVelocity;
    }

    public boolean isSpringHandoffPending() { return springHandoffPending; }
    public int getSpringHandoffXVelocity() { return springHandoffXVelocity; }
    public int getSpringHandoffYVelocity() { return springHandoffYVelocity; }

    public void clearSpringHandoff() {
        springHandoffPending = false;
        springHandoffXVelocity = 0;
        springHandoffYVelocity = 0;
    }

    public record RewindState(
            PlayableSpriteMovement.RewindState movementState,
            SpindashDustController.RewindState spindashDustState,
            PlayableSpriteAnimation.RewindState animationState,
            DrowningController.RewindState drowningState,
            TailsCarryController.Snapshot tailsCarryState,
            SuperStateController.RewindState superStateState,
            TailsTailsController.RewindState tailsTailsState
    ) {}
}
