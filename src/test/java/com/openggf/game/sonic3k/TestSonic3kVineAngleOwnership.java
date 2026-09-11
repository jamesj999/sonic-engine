package com.openggf.game.sonic3k;

import com.openggf.data.Rom;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Pins the owner of ROM {@code AIZ_vine_angle}.
 *
 * <p>{@code ChangeRingFrame} advances the word every Level main-loop iteration
 * (docs/skdisasm/sonic3k.asm:9693) and NOTHING clears it while the console runs:
 * both the level init and the special-stage init clear the oscillating table with
 * {@code clearRAM Oscillating_table,(AIZ_vine_angle-Oscillating_table)}
 * (sonic3k.asm:10609 and :7622), a length that stops one word short of it. The
 * swing phase of every AIZ giant ride vine therefore carries across a
 * giant-ring / special-stage round trip.
 *
 * <p>{@link com.openggf.level.LevelManager#initGameModule} builds a fresh
 * {@link com.openggf.data.Game} on every level load, so a {@code Sonic3k}-owned
 * carrier was reset by each load. The session-lived {@link Sonic3kGameModule}
 * owns it instead; this test fails if that ownership moves back.
 */
@RequiresRom(SonicGame.SONIC_3K)
class TestSonic3kVineAngleOwnership {

    @Test
    void everyGameFromOneModuleSharesTheVineAngleWord() throws Exception {
        Rom rom = TestEnvironment.currentRom();
        Sonic3kGameModule module = new Sonic3kGameModule();

        Sonic3k first = (Sonic3k) module.createGame(rom);
        Sonic3k second = (Sonic3k) module.createGame(rom);

        assertNotSame(first, second,
                "each level load builds a new Game; the test premise requires that");
        assertSame(first.globalAnimationState(), second.globalAnimationState(),
                "AIZ_vine_angle must outlive the Game a level load replaces");
    }

    @Test
    void separateModulesDoNotShareTheVineAngleWord() throws Exception {
        Rom rom = TestEnvironment.currentRom();
        assertNotSame(
                ((Sonic3k) new Sonic3kGameModule().createGame(rom)).globalAnimationState(),
                ((Sonic3k) new Sonic3kGameModule().createGame(rom)).globalAnimationState(),
                "a new session starts from cleared RAM");
    }
}
