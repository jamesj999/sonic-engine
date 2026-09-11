package com.openggf.tests;

import com.openggf.game.rules.GameRules;
import com.openggf.sprites.playable.Tails;

/**
 * Testable Tails subclass for unit tests that need a concrete Tails
 * without OpenGL or ROM dependencies. Passes {@code instanceof Tails}
 * checks so that physics providers return Tails-specific profiles.
 */
public class TestableTailsSprite extends Tails {

    public TestableTailsSprite(String code, short x, short y) {
        super(code, x, y);
    }

    @Override
    public void draw() {
    }

    @Override
    protected void createSensorLines() {
    }

    public void setTestY(short y) {
        this.yPixel = y;
    }

    public void setGameRulesForTest(GameRules rules) {
        super.setGameRulesForTest(rules);
    }
}

