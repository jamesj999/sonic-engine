package com.openggf.trace;

public record EngineNearbyObject(
        int slot,
        int objectId,
        String name,
        int currentX,
        int currentY,
        int spawnX,
        int spawnY,
        boolean touchResponseProvider,
        int collisionFlags,
        int preUpdateCollisionFlags,
        int preUpdateX,
        int preUpdateY,
        boolean skipTouchThisFrame,
        boolean skipSolidThisFrame,
        boolean onScreenForTouch,
        String debugDetails
) {
    public EngineNearbyObject(
            int slot,
            int objectId,
            String name,
            int currentX,
            int currentY,
            int spawnX,
            int spawnY,
            boolean touchResponseProvider,
            int collisionFlags,
            int preUpdateCollisionFlags,
            int preUpdateX,
            int preUpdateY,
            boolean skipTouchThisFrame,
            boolean skipSolidThisFrame,
            boolean onScreenForTouch) {
        this(slot, objectId, name, currentX, currentY, spawnX, spawnY, touchResponseProvider,
                collisionFlags, preUpdateCollisionFlags, preUpdateX, preUpdateY,
                skipTouchThisFrame, skipSolidThisFrame, onScreenForTouch, "");
    }
}
