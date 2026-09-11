package com.openggf.level.objects;

/**
 * Per-game object load/unload windowing boundary supplied to the shared
 * {@link ObjectManager}.
 *
 * <p>This is the shared abstraction that keeps {@code com.openggf.level.objects}
 * free of any {@code com.openggf.game.sonicN} dependency: each game module
 * provides its own implementation (via its {@link ObjectRegistry}), and
 * {@code ObjectManager}/{@code Placement} consume only this interface. The S2
 * implementation lives at {@code com.openggf.game.sonic2.objects.S2ObjectWindowing}
 * and models the ROM {@code ObjectsManager_GoingForward}/{@code GoingBackward}
 * cursor edges plus the object-side {@code MarkObjGone} delete window.
 *
 * <p>The default behaviour ({@link #LEGACY}) is a no-op: it does not override the
 * placement load/trim window and does not impose a game-specific unload window,
 * so the manager keeps its existing symmetric widescreen-capped window source and
 * the S1 {@code out_of_range} macro for the off-screen unload decision. S1 and
 * S3K use {@link #LEGACY} this stage.
 */
public interface ObjectWindowingStrategy {

    /**
     * Whether {@link #loadWindowForwardEdge(int)} / {@link #loadWindowLeftTrimEdge(int)}
     * should replace the manager's default symmetric load/trim window. When
     * {@code false}, the manager keeps its existing widescreen-capped window source.
     */
    default boolean overridesLoadWindow() {
        return false;
    }

    /**
     * Forward load / right trim edge (exclusive): a spawn loads iff
     * {@code spawn.x < loadWindowForwardEdge(cameraX)}. Only consulted when
     * {@link #overridesLoadWindow()} is {@code true}.
     */
    default int loadWindowForwardEdge(int cameraX) {
        throw new UnsupportedOperationException("loadWindowForwardEdge requires overridesLoadWindow()");
    }

    /**
     * Backward load / left trim edge: spawns left of this edge are trimmed. Only
     * consulted when {@link #overridesLoadWindow()} is {@code true}.
     */
    default int loadWindowLeftTrimEdge(int cameraX) {
        throw new UnsupportedOperationException("loadWindowLeftTrimEdge requires overridesLoadWindow()");
    }

    /**
     * Whether {@link #isOutsideUnloadWindow(int, int)} should drive the standard
     * (non-custom) off-screen unload decision instead of the S1
     * {@code out_of_range} macro. When {@code false}, the manager keeps the S1
     * unload window.
     */
    default boolean overridesUnloadWindow() {
        return false;
    }

    /**
     * Game-specific off-screen unload decision: returns {@code true} when the
     * object at ROM reference X {@code objX} has left the live window for the
     * given camera X and should be unloaded. Only consulted when
     * {@link #overridesUnloadWindow()} is {@code true}.
     */
    default boolean isOutsideUnloadWindow(int objX, int cameraX) {
        throw new UnsupportedOperationException("isOutsideUnloadWindow requires overridesUnloadWindow()");
    }

    /**
     * Legacy / no-op strategy: keeps the manager's existing window source and the
     * S1 {@code out_of_range} unload macro. Used by S1 and S3K this stage.
     */
    ObjectWindowingStrategy LEGACY = new ObjectWindowingStrategy() {};
}
