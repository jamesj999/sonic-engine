package com.openggf.game.sonic3k.objects;

/**
 * Shared state for the AIZ2 post-boss bridge / button / Knuckles cutscene.
 *
 * <p>The original game uses a handful of global work RAM flags and object
 * pointers (_unkFAA3/_unkFAA4/_unkFAA9). This class keeps the Java-side
 * coordination equally small without coupling these objects back to the boss.
 */
public final class Aiz2BossEndSequenceState {

    private static volatile boolean bridgeDropTriggered;
    private static volatile boolean buttonPressed;
    private static volatile boolean eggCapsuleReleased;
    private static volatile boolean cutsceneOverrideObjectsActive;
    /** Cross-act latches captured and restored by Aiz2BossEndSequenceStaticAdapter. */
    private static volatile int tailsControlReleaseDelay = -1;
    private static volatile CutsceneKnucklesAiz2Instance activeKnuckles;

    private Aiz2BossEndSequenceState() {
    }

    public static void reset() {
        bridgeDropTriggered = false;
        buttonPressed = false;
        eggCapsuleReleased = false;
        cutsceneOverrideObjectsActive = false;
        tailsControlReleaseDelay = -1;
        activeKnuckles = null;
    }

    public static boolean isBridgeDropTriggered() {
        return bridgeDropTriggered;
    }

    public static void triggerBridgeDrop() {
        bridgeDropTriggered = true;
    }

    public static boolean isButtonPressed() {
        return buttonPressed;
    }

    public static void pressButton() {
        buttonPressed = true;
    }

    public static boolean isEggCapsuleReleased() {
        return eggCapsuleReleased;
    }

    public static void releaseEggCapsule() {
        eggCapsuleReleased = true;
    }

    public static boolean isCutsceneOverrideObjectsActive() {
        return cutsceneOverrideObjectsActive;
    }

    public static void activateCutsceneOverrideObjects() {
        cutsceneOverrideObjectsActive = true;
    }

    public static void scheduleTailsControlRelease(int delay) {
        if (tailsControlReleaseDelay < 0) {
            tailsControlReleaseDelay = delay;
        }
    }

    public static boolean tickTailsControlRelease() {
        if (tailsControlReleaseDelay < 0) {
            return false;
        }
        if (tailsControlReleaseDelay == 0) {
            tailsControlReleaseDelay = -1;
            return true;
        }
        tailsControlReleaseDelay--;
        return false;
    }

    public static CutsceneKnucklesAiz2Instance getActiveKnuckles() {
        return activeKnuckles;
    }

    public static void setActiveKnuckles(CutsceneKnucklesAiz2Instance knuckles) {
        activeKnuckles = knuckles;
    }

    /**
     * Immutable rewind snapshot of the boss-endgame static latches. These
     * ratchet forward (bridge drop, button press, capsule release, cutscene
     * overrides, draw-bridge burn) and are consulted as {@code !instanceFlag
     * && staticLatch}; without rewind coverage they desync against the
     * rewound instance flags (bridge re-drops, cutscene-override objects get
     * deleted). The live {@code activeKnuckles} pointer is NOT captured here —
     * it is a reference to a dynamic object and is rebound from the restored
     * object set post-restore.
     */
    public record Snapshot(boolean bridgeDropTriggered,
                           boolean buttonPressed,
                           boolean eggCapsuleReleased,
                           boolean cutsceneOverrideObjectsActive,
                           int tailsControlReleaseDelay,
                           boolean drawBridgeBurnActive) {
    }

    public static Snapshot snapshot() {
        return new Snapshot(bridgeDropTriggered, buttonPressed, eggCapsuleReleased,
                cutsceneOverrideObjectsActive, tailsControlReleaseDelay,
                AizCollapsingLogBridgeObjectInstance.isDrawBridgeBurnActive());
    }

    public static void restore(Snapshot snapshot) {
        bridgeDropTriggered = snapshot.bridgeDropTriggered();
        buttonPressed = snapshot.buttonPressed();
        eggCapsuleReleased = snapshot.eggCapsuleReleased();
        cutsceneOverrideObjectsActive = snapshot.cutsceneOverrideObjectsActive();
        tailsControlReleaseDelay = snapshot.tailsControlReleaseDelay();
        AizCollapsingLogBridgeObjectInstance.setDrawBridgeBurnActive(
                snapshot.drawBridgeBurnActive());
        // Drop any stale live pointer; rebound from the restored object set by
        // the AIZ post-restore reconciliation.
        activeKnuckles = null;
    }
}
