package com.openggf.game;

import com.openggf.game.sonic1.titlecard.Sonic1TitleCardManager;
import com.openggf.game.sonic2.titlecard.TitleCardManager;
import com.openggf.game.sonic3k.titlecard.Sonic3kTitleCardManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Documents the per-game title-card physics policy from the disassemblies.
 */
public class TestTitleCardPhysicsPolicy {

    @Test
    public void sonic1BlocksPlayerPhysicsDuringLockedTitleCardPhase() {
        assertFalse(Sonic1TitleCardManager.getInstance().shouldRunPlayerPhysics());
    }

    @Test
    public void sonic2DefersPlayerPhysicsUntilTheRomCreatesPlayersForLeavePasses() {
        TitleCardManager manager = TitleCardManager.getInstance();
        manager.reset();

        assertFalse(manager.shouldRunPlayerPhysics());
    }

    @Test
    public void sonic3kBlocksPlayerPhysicsDuringLockedTitleCardPhase() {
        assertFalse(new Sonic3kTitleCardManager().shouldRunPlayerPhysics());
    }
}

