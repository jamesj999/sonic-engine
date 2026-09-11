package com.openggf.sprites.playable;

final class PlayablePhysicsValueResolver {
    private PlayablePhysicsValueResolver() {
    }

    static short runAcceleration(short base, boolean waterActive, boolean speedShoes) {
        return waterActive ? (short) (base / 2) : speedShoes ? (short) (base * 2) : base;
    }

    static short runDeceleration(short base, boolean waterActive) {
        return waterActive ? (short) (base / 2) : base;
    }

    static short friction(short base, boolean waterActive, boolean speedShoes) {
        return waterActive ? (short) (base / 2) : speedShoes ? (short) (base * 2) : base;
    }

    static short maximumSpeed(short base, boolean waterActive, boolean speedShoes) {
        return waterActive ? (short) (base / 2) : speedShoes ? (short) (base * 2) : base;
    }

    static short jumpForce(short base, boolean inWater) {
        return inWater ? (short) 0x380 : base;
    }

    static short gravity(boolean inWater) {
        return inWater ? (short) 0x10 : (short) 0x38;
    }

    static short airDragThreshold(boolean inWater) {
        return inWater ? (short) -0x200 : (short) -0x400;
    }
}
