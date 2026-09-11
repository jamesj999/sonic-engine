package com.openggf.sprites.playable;

import java.util.Objects;

/**
 * Named object-control flag combinations used by ROM object scripts and
 * engine-only scripted handoffs.
 */
public enum ObjectControlState {
    NONE(false, false, false, true),
    NATIVE_BIT_7_FULL_CONTROL(true, false, true, true),
    NATIVE_BITS_0_TO_6_CPU_ALLOWED_MOVEMENT_SUPPRESSED(true, true, true, true),
    NATIVE_BITS_0_TO_6_CPU_ALLOWED_MOVEMENT_ACTIVE(true, true, false, true),
    MOVEMENT_SUPPRESSED_ONLY(false, false, true, true),
    ENGINE_SCRIPTED_TOUCH_SUPPRESSED_MOVEMENT_ACTIVE(true, false, false, true),
    ENGINE_SCRIPTED_PRESERVE_CPU_MOVEMENT_SUPPRESSED(true, false, true, false);

    private final boolean objectControlled;
    private final boolean objectControlAllowsCpu;
    private final boolean objectControlSuppressesMovement;
    private final boolean writesObjectControlAllowsCpu;

    ObjectControlState(boolean objectControlled,
                       boolean objectControlAllowsCpu,
                       boolean objectControlSuppressesMovement,
                       boolean writesObjectControlAllowsCpu) {
        this.objectControlled = objectControlled;
        this.objectControlAllowsCpu = objectControlAllowsCpu;
        this.objectControlSuppressesMovement = objectControlSuppressesMovement;
        this.writesObjectControlAllowsCpu = writesObjectControlAllowsCpu;
    }

    public static ObjectControlState none() {
        return NONE;
    }

    public static ObjectControlState nativeBit7FullControl() {
        return NATIVE_BIT_7_FULL_CONTROL;
    }

    public static ObjectControlState nativeBits0To6CpuAllowedMovementSuppressed() {
        return NATIVE_BITS_0_TO_6_CPU_ALLOWED_MOVEMENT_SUPPRESSED;
    }

    public static ObjectControlState nativeBits0To6CpuAllowedMovementActive() {
        return NATIVE_BITS_0_TO_6_CPU_ALLOWED_MOVEMENT_ACTIVE;
    }

    public static ObjectControlState movementSuppressedOnly() {
        return MOVEMENT_SUPPRESSED_ONLY;
    }

    public static ObjectControlState engineScriptedTouchSuppressedMovementActive() {
        return ENGINE_SCRIPTED_TOUCH_SUPPRESSED_MOVEMENT_ACTIVE;
    }

    public static ObjectControlState engineScriptedPreserveCpuMovementSuppressed() {
        return ENGINE_SCRIPTED_PRESERVE_CPU_MOVEMENT_SUPPRESSED;
    }

    public boolean objectControlled() {
        return objectControlled;
    }

    public boolean objectControlAllowsCpu() {
        return objectControlAllowsCpu;
    }

    public boolean objectControlSuppressesMovement() {
        return objectControlSuppressesMovement;
    }

    public boolean writesObjectControlAllowsCpu() {
        return writesObjectControlAllowsCpu;
    }

    public boolean touchResponseSuppressed() {
        return objectControlled && !objectControlAllowsCpu;
    }

    public void applyTo(AbstractPlayableSprite player) {
        Objects.requireNonNull(player, "player").applyObjectControlState(this);
    }

    public static void setMovementSuppressionPreservingOwnership(
            AbstractPlayableSprite player, boolean suppressesMovement) {
        Objects.requireNonNull(player, "player").setObjectControlSuppressesMovement(suppressesMovement);
    }
}
