package com.openggf.game.sonic2.objects;

import com.openggf.game.GameServices;
import com.openggf.game.GameStateManager;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the length of {@code Obj6F}'s bonus tally.
 *
 * <p>{@code Obj6F_TallyScore} (docs/s2disasm/s2.asm:28376-28396) tests and
 * decrements {@code Bonus_Countdown_1}, {@code Bonus_Countdown_2} and
 * {@code Total_Bonus_Countdown} in one pass, each with its own
 * {@code subq.w #1} / {@code subi.w #10}, and only leaves for
 * {@code Obj6F_TimedDisplay} on the pass where all three are already zero. The
 * two ring countdowns are loaded from the two players' separate ring words
 * ({@code move.w (Ring_count).w,(Bonus_Countdown_1).w} /
 * {@code move.w (Ring_count_2P).w,(Bonus_Countdown_2).w},
 * docs/s2disasm/s2.asm:6784-6785), so they drain <em>side by side</em>: the
 * tally lasts as long as the longer of them, never as long as their sum.
 */
@RequiresRom(SonicGame.SONIC_2)
class TestSonic2SpecialStageResultsTallyCadence {

    private TestObjectServices services;

    @BeforeEach
    void setUp() {
        GameServices.module().createGame(TestEnvironment.currentRom());
        services = (TestObjectServices) new TestObjectServices() {
            @Override
            public <T> T gameService(Class<T> type) {
                return GameServices.module().getGameService(type);
            }
        }.withGameModule(GameServices.module())
                .withGameState(new GameStateManager());
    }

    private SpecialStageResultsScreenObjectInstance screen(int p1Rings, int p2Rings,
                                                           boolean gotEmerald) {
        return new SpecialStageResultsScreenObjectInstance(
                p1Rings, p2Rings, gotEmerald, 0, 0, services);
    }

    /**
     * Runs the card until both ring countdowns have emptied and returns how many
     * passes the tally itself took, i.e. the number of {@code Obj6F_TallyScore}
     * executions including the exhausted one that advances the routine.
     */
    private int tallyPasses(SpecialStageResultsScreenObjectInstance screen) {
        int frame = 0;
        int firstDrainFrame = -1;
        int p1 = screen.getDisplayedRingCount();
        int p2 = screen.getDisplayedRingCountP2();
        int bonus = screen.getEmeraldBonus();
        // Obj6F only reaches Obj6F_TallyScore after 18 
        // Obj34_MoveTowardsTargetPosition steps, the pass that latches $B4, and
        // that $B4 = 180 pass countdown (s2.asm:27494, 28243-28248,
        // 28367-28371), so wait for the first pass that actually decrements.
        while (frame < 4000) {
            frame++;
            screen.update(frame, null);
            int nextP1 = screen.getDisplayedRingCount();
            int nextP2 = screen.getDisplayedRingCountP2();
            int nextBonus = screen.getEmeraldBonus();
            boolean drained = nextP1 < p1 || nextP2 < p2 || nextBonus < bonus;
            p1 = nextP1;
            p2 = nextP2;
            bonus = nextBonus;
            if (drained && firstDrainFrame < 0) {
                firstDrainFrame = frame;
            }
            if (firstDrainFrame >= 0 && p1 == 0 && p2 == 0 && bonus == 0) {
                // Plus the exhausted pass on which Obj6F_TallyScore finds d0
                // zero and advances the routine (s2.asm:28395-28400).
                return frame - firstDrainFrame + 2;
            }
        }
        throw new AssertionError("tally never emptied");
    }

    @Test
    void twoPlayerRingCountdownsDrainInParallelSoTheTallyIsTheLongerOfThem() {
        // 91 + 76 would be 168 passes if the countdowns were summed into one.
        assertEquals(92, tallyPasses(screen(91, 76, false)),
                "Bonus_Countdown_1 and Bonus_Countdown_2 decrement in the same "
                        + "Obj6F_TallyScore pass (s2.asm:28381-28388)");
    }

    @Test
    void theThousandPointEmeraldBonusOutlastsAShortRingCountdown() {
        // Total_Bonus_Countdown is 1000 and drains at subi.w #10 per pass
        // (s2.asm:6779-6781, 28389-28392) => 100 draining passes plus the
        // exhausted one.
        assertEquals(101, tallyPasses(screen(40, 30, true)));
    }

    @Test
    void asinglePlayerCountdownIsUnaffectedByTheSecond() {
        assertEquals(162, tallyPasses(screen(161, 0, false)));
    }
}
