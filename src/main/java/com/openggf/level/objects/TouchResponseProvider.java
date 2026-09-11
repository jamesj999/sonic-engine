package com.openggf.level.objects;

import com.openggf.game.PlayableEntity;

public interface TouchResponseProvider {
    int getCollisionFlags();
    int getCollisionProperty();

    default TouchResponseProfile getTouchResponseProfile() {
        return getTouchResponseProfile(getMultiTouchRegions() != null);
    }

    default TouchResponseProfile getTouchResponseProfile(boolean multiRegionSource) {
        return TouchResponseProfile.fromProvider(this, multiRegionSource);
    }

    /**
     * Returns whether touch callbacks should fire every frame while overlapping.
     * <p>
     * Default behavior is edge-triggered (fires only when overlap begins),
     * which matches most touch objects. Some objects rely on per-frame
     * "collision_property"-style polling and need continuous callbacks.
     */
    default boolean requiresContinuousTouchCallbacks() {
        return false;
    }

    /**
     * Returns whether S3K {@code Touch_Special} property-style {@code 0xC0}
     * collision flags should dispatch as listener-only special callbacks instead
     * of boss touch handling.
     */
    default boolean usesS3kTouchSpecialPropertyResponse() {
        return false;
    }

    /**
     * Returns whether the object's collision byte should dispatch through enemy
     * attack/hurt handling regardless of the high category bits.
     */
    default boolean usesEnemyTouchCategoryOverride() {
        return false;
    }

    /**
     * Returns whether Sonic 1 {@code React_Special} property-style {@code 0xC0}
     * collision flags should dispatch as listener-only special callbacks.
     */
    default boolean usesSonic1TouchSpecialPropertyResponse() {
        return false;
    }

    /**
     * Returns whether Sonic 2 {@code Touch_Special} property-style {@code 0xC0}
     * collision flags should dispatch as listener-only special callbacks.
     */
    default boolean usesSonic2TouchSpecialPropertyResponse() {
        return false;
    }

    /**
     * Returns whether a SPECIAL touch from this object should enable same-frame
     * airborne side-velocity preservation on a following solid object.
     */
    default boolean enablesPostSpecialTouchAirborneSideVelocityPreservation() {
        return false;
    }

    /**
     * Returns whether touch response should be gated by the engine's render-flag
     * equivalent before testing this object.
     */
    default boolean requiresRenderFlagForTouch() {
        return true;
    }

    /**
     * Optional multi-region touch collision for objects with multiple independent
     * collision areas (e.g., spiked pole helix where each spike has its own hitbox).
     * <p>
     * When this returns non-null, the touch response system checks each region
     * independently instead of using the single spawn position. Each region
     * specifies its own center position and collision flags.
     * <p>
     * Returns null by default (single-region behavior using getCollisionFlags()).
     */
    default TouchRegion[] getMultiTouchRegions() {
        return null;
    }

    /**
     * Shield reaction flags, mirroring the S3K shield_reaction byte semantics.
     * Bit 3 indicates this object should be deflected by shield touch checks.
     */
    default int getShieldReactionFlags() {
        return 0;
    }

    /**
     * Called when the object is deflected by shield touch handling.
     *
     * @return true if the deflect was applied and regular hurt handling should be skipped
     */
    default boolean onShieldDeflect(PlayableEntity player) {
        return false;
    }

    /**
     * A single touch collision region with its own position and collision type.
     */
    record TouchRegion(int x, int y, int collisionFlags, int shieldReactionFlags) {
        public TouchRegion(int x, int y, int collisionFlags) {
            this(x, y, collisionFlags, 0);
        }
    }
}
