package com.openggf.level.objects;

import com.openggf.game.session.EngineServices;
import com.openggf.game.session.GameplayModeContext;
import com.openggf.game.session.SessionManager;

/**
 * Legacy {@link ObjectServices} bridge for call sites that still build an
 * {@link ObjectManager} without passing explicit services.
 *
 * <p>Runtime-owned dependencies are resolved from the active
 * {@link GameplayModeContext}, so callers must create a gameplay mode first.</p>
 */
public final class BootstrapObjectServices extends DefaultObjectServices {

    public BootstrapObjectServices() {
        super(requireGameplayMode(), EngineServices.current());
    }

    private static GameplayModeContext requireGameplayMode() {
        GameplayModeContext gameplayMode = SessionManager.getCurrentGameplayMode();
        if (gameplayMode == null) {
            throw new IllegalStateException(
                    "BootstrapObjectServices requires an active gameplay runtime");
        }
        return gameplayMode;
    }
}
