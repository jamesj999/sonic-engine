package com.openggf.level.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.rewind.GenericFieldCapturer;
import com.openggf.game.rewind.schema.RewindCaptureContext;
import com.openggf.sprites.Sprite;
import com.openggf.sprites.managers.SpriteManager;

/**
 * Shared base class for monitor (item box) objects across all games.
 * <p>
 * Encapsulates the icon-rise physics state machine that is identical across
 * S1, S2, and S3K: the icon rises with initial velocity {@code -0x300},
 * decelerates at {@code +0x18} per frame, waits {@code 0x1D} frames at
 * the apex (applying the power-up effect), then self-destructs.
 * <p>
 * Subclasses provide game-specific power-up logic via {@link #applyPowerup}.
 * <p>
 * ROM references: Pow_Move / Pow_ChkX (all three games share this code).
 */
public abstract class AbstractMonitorObjectInstance extends AbstractObjectInstance {

    // From disassembly: Pow_Main sets obVelY = -$300
    protected static final int ICON_INITIAL_VELOCITY = -0x300;

    // From disassembly: Pow_Move adds $18 to obVelY per frame
    protected static final int ICON_RISE_ACCEL = 0x18;

    // From disassembly: Pow_Move sets timer to $1D (29 frames)
    protected static final int ICON_WAIT_FRAMES = 0x1D;

    // Icon rising state (PowerUp object in disassembly)
    protected boolean iconActive;
    protected int iconSubY;
    protected int iconVelY;
    protected int iconWaitFrames;
    protected boolean effectApplied;
    // Captured/restored explicitly via captureRewindState/restoreRewindState
    // overrides below using the player's stable sprite code, because
    // PlayableEntity is a live reference type that the generic field capturer
    // cannot serialize.
    protected PlayableEntity effectTarget;
    protected boolean iconPendingInit;

    protected AbstractMonitorObjectInstance(ObjectSpawn spawn, String name) {
        super(spawn, name);
    }

    /**
     * Starts the icon-rise sequence. Call from {@code breakMonitor()} in subclasses
     * after setting the monitor to broken state.
     *
     * @param originY the Y position (pixels) from which the icon rises
     * @param player  the player who broke the monitor (receives the power-up)
     */
    protected void startIconRise(int originY, PlayableEntity player) {
        iconActive = true;
        iconSubY = originY << 8;
        iconVelY = ICON_INITIAL_VELOCITY;
        iconWaitFrames = 0;
        effectApplied = false;
        effectTarget = player;
        // ROM Pow_Main / Obj2E_Init falls through to the rise routine on the
        // monitor-content object's first execution. Embedded monitor shells can
        // opt into skipping the parent shell's same-frame post-break update.
        iconPendingInit = delayFirstIconUpdateAfterBreak();
    }

    protected boolean delayFirstIconUpdateAfterBreak() {
        return false;
    }

    /**
     * Update the rising icon and apply power-up effect when it reaches the apex.
     * <p>
     * State machine: rise (velocity &lt; 0) -> apply effect -> wait -> deactivate.
     * <p>
     * ROM: Pow_Move, Pow_ChkX — identical across S1, S2, and S3K.
     */
    protected void updateIcon() {
        if (!iconActive) {
            return;
        }
        if (iconPendingInit) {
            iconPendingInit = false;
            return;
        }
        if (iconVelY < 0) {
            // Rising phase: apply velocity and deceleration
            iconSubY += iconVelY;
            iconVelY += ICON_RISE_ACCEL;
            return;
        }
        if (!effectApplied && effectTarget != null) {
            // ROM tests y_vel before moving the monitor icon. When the
            // previous rise step adds $18 up to zero, the effect branch waits
            // until the next object update (S2 Obj2E_Raise, s2.asm:25618-25631;
            // S1 Pow_Move, 2E Monitor Content Power-Up.asm:35-43).
            iconVelY = 0;
            iconWaitFrames = ICON_WAIT_FRAMES;
            applyPowerup(effectTarget);
            effectApplied = true;
            effectTarget = null;
            return;
        }

        // Waiting phase: count down then deactivate icon
        if (iconWaitFrames > 0) {
            iconWaitFrames--;
            return;
        }
        iconActive = false;
        onIconDeactivated();
    }

    /**
     * Apply the game-specific power-up effect to the player.
     * Called exactly once when the icon reaches its apex.
     *
     * @param player the player who broke the monitor
     */
    protected abstract void applyPowerup(PlayableEntity player);

    /**
     * Hook called when the icon finishes its wait timer and deactivates.
     * Subclasses can override to clear rendering references or child slots.
     * Default implementation does nothing.
     */
    protected void onIconDeactivated() {
        // Default no-op; subclasses may override.
    }

    @Override
    public PerObjectRewindSnapshot captureRewindState(RewindCaptureContext context) {
        PerObjectRewindSnapshot snapshot = super.captureRewindState(context);
        if (snapshot.genericState() == null && snapshot.compactGenericState() == null) {
            var genericState = GenericFieldCapturer.captureObjectSubclassScalars(this);
            if (!genericState.keys().isEmpty()) {
                snapshot = snapshot.withGenericState(genericState);
            }
        }
        String code = (effectTarget instanceof Sprite sprite) ? sprite.getCode() : null;
        return snapshot.withObjectSubclassExtra(
                new PerObjectRewindSnapshot.MonitorRewindExtra(code));
    }

    @Override
    public void restoreRewindState(PerObjectRewindSnapshot snapshot, RewindCaptureContext context) {
        super.restoreRewindState(snapshot, context);
        // Default to null so a missing/unmatched extra leaves no stale recipient.
        effectTarget = null;
        if (snapshot.objectSubclassExtra()
                instanceof PerObjectRewindSnapshot.MonitorRewindExtra extra
                && extra.effectTargetSpriteCode() != null) {
            ObjectServices ctx = tryServices();
            SpriteManager spriteManager = ctx != null ? ctx.spriteManager() : null;
            if (spriteManager != null) {
                Sprite sprite = spriteManager.getSprite(extra.effectTargetSpriteCode());
                if (sprite instanceof PlayableEntity player) {
                    effectTarget = player;
                }
            }
        }
    }
}
