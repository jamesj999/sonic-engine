package com.openggf.game;

import com.openggf.level.LevelManager;
import com.openggf.sprites.Sprite;
import com.openggf.sprites.managers.SpriteManager;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Coordinates in-level and results-return title-card preparation. */
public final class InLevelTitleCardCoordinator {
    private InLevelTitleCardCoordinator() {
    }

    /**
     * Locks control for a results-return title card. The fresh-player prelude
     * deliberately does NOT run here: the ROM's equivalent pass is
     * {@code Level_StartGame}'s single ObjPosLoad/ExecuteObjects iteration
     * after the locked title-card loop drains its PLCs, immediately before
     * {@code Level_MainLoop} — which the release path already models via
     * {@link com.openggf.game.TitleCardProvider#shouldRunPlayerPreludeAtRelease()}.
     */
    public static void prepareResultsTransition(Consumer<Boolean> controlLock) {
        controlLock.accept(true);
    }

    public static boolean startIfRequested(LevelManager levelManager,
                                           TitleCardProvider provider,
                                           boolean endOfLevelActive,
                                           Consumer<Boolean> controlLock) {
        Objects.requireNonNull(levelManager, "levelManager");
        Objects.requireNonNull(controlLock, "controlLock");
        // A pending request is an explicit title-card owner handoff.  Seamless
        // transitions may preserve End_of_level_active for the carried results
        // owner while assigning publication to this title-card coordinator;
        // the request itself is the semantic gate, not the preserved flag.
        if (!levelManager.consumeInLevelTitleCardRequest()) {
            return false;
        }
        if (provider == null) {
            return false;
        }
        provider.initializeInLevel(
                levelManager.getInLevelTitleCardZone(),
                levelManager.getInLevelTitleCardAct());
        if (levelManager.consumeInLevelTitleCardLevelGamestateResetRequest()) {
            provider.requestLevelGamestateResetAtInLevelDisplay(
                    levelManager.consumeInLevelTitleCardResetAdditionalDispatches(),
                    levelManager.consumeInLevelTitleCardResetPhaseOneDispatchOverlap());
        }
        if (levelManager.consumeInLevelTitleCardPlayerControlLockRequest()) {
            provider.requestInLevelPlayerControlLock();
            controlLock.accept(true);
        }
        provider.requestInLevelExitAdditionalDispatches(
                levelManager.consumeInLevelTitleCardExitAdditionalDispatches(),
                levelManager.consumeInLevelTitleCardExitPhaseOneDispatchOverlap());
        return true;
    }
}
