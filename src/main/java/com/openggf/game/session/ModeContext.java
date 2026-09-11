package com.openggf.game.session;

import com.openggf.game.GameMode;

public interface ModeContext {
    GameMode getGameMode();

    void destroy();
}
