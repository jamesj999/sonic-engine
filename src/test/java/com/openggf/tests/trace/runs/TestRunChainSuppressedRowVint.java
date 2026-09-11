package com.openggf.tests.trace.runs;

import com.openggf.LevelFrameContext;
import com.openggf.game.GameModule;
import com.openggf.game.LevelInitProfile;
import com.openggf.game.NoOpBonusStageProvider;
import com.openggf.game.resources.PlcFrameLifecycleCoordinator;
import com.openggf.game.resources.PlcLifecyclePhase;
import com.openggf.game.resources.PlcLifecycleService;
import com.openggf.game.timing.HardwareTimingService;
import com.openggf.level.objects.ObjectManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * The transition-row V-blank invariant, asserted without a ROM, a level or a
 * trace fixture.
 *
 * <p>{@code VintRet: addq.l #1,(Vint_runcount).w}
 * (docs/s2disasm/s2.asm:507-508) sits after the {@code jsr Vint_SwitchTbl}
 * dispatch at :504, so every V-int the ROM takes advances the object-visible
 * clock exactly once, whichever routine the dispatch selected. A transition is
 * made of such frames — {@code Vint_Fade} during {@code Pal_FadeToBlack}
 * (:3370-3380), {@code Vint_TitleCard}, {@code Vint_Lag} — so a run of N
 * transition rows advances the clock by exactly N.
 *
 * <p>The engine plays some of those rows with the level body suppressed, which
 * leaves the clock still. {@link AbstractRunChainTest#suppressedRowOwesVint} is
 * the rule that restores the ROM's count, and these are its assertions. They
 * are deliberately fixture-free: the property is arithmetic about one counter,
 * so nothing about it should depend on a recording.
 */
class TestRunChainSuppressedRowVint {

    @Test
    void lagHelperDispatchesGameVblankOnceWithoutObjectsOrOrdinaryPlc() {
        AtomicInteger gameVblanks = new AtomicInteger();
        AtomicInteger ordinaryPlc = new AtomicInteger();
        LevelInitProfile profile = mock(LevelInitProfile.class);
        org.mockito.Mockito.doAnswer(ignored -> {
            gameVblanks.incrementAndGet();
            return null;
        }).when(profile).serviceLevelLoadVBlank();
        GameModule module = mock(GameModule.class);
        when(module.getLevelInitProfile()).thenReturn(profile);
        PlcLifecycleService plc = new PlcLifecycleService() {
            @Override
            public void serviceVBlank(PlcLifecyclePhase phase) {
                if (phase == PlcLifecyclePhase.ORDINARY_LEVEL) {
                    ordinaryPlc.incrementAndGet();
                }
            }

            @Override
            public boolean hasPreparationBoundary(PlcLifecyclePhase phase) {
                return false;
            }

            @Override
            public void prepareAfterLoop(PlcLifecyclePhase phase) {
            }
        };
        ObjectManager objects = mock(ObjectManager.class);
        when(objects.getVblaCounter()).thenReturn(41);
        LevelFrameContext context = new LevelFrameContext(
                module, null, null, NoOpBonusStageProvider.INSTANCE,
                null, null, null, null, new HardwareTimingService(),
                ignored -> { }, null);

        AbstractRunChainTest.serviceLagRowVint(
                context, new PlcFrameLifecycleCoordinator(plc), objects);

        assertEquals(1, gameVblanks.get());
        assertEquals(0, ordinaryPlc.get());
        verify(objects).getVblaCounter();
        verify(objects).initVblaCounter(42);
        verifyNoMoreInteractions(objects);
    }

    @Test
    @DisplayName("A row the engine left the clock still on still owes its VintRet tick")
    void stalledRowOwesTheTick() {
        assertTrue(AbstractRunChainTest.suppressedRowOwesVint(true, 100, 100));
    }

    @Test
    @DisplayName("A row whose own body already ticked owes nothing, so the rule cannot double-count")
    void alreadyTickedRowOwesNothing() {
        assertFalse(AbstractRunChainTest.suppressedRowOwesVint(true, 100, 101));
    }

    @Test
    @DisplayName("A row that advanced the clock more than once is left alone")
    void multiplyAdvancedRowOwesNothing() {
        assertFalse(AbstractRunChainTest.suppressedRowOwesVint(true, 100, 103));
    }

    @Test
    @DisplayName("A row on which the level load replaced the object manager owes nothing")
    void reseededRowOwesNothing() {
        assertFalse(AbstractRunChainTest.suppressedRowOwesVint(false, 100, 100));
        assertFalse(AbstractRunChainTest.suppressedRowOwesVint(false, 100, 0));
    }

    /**
     * The property the rule exists for: a gap of N rows advances the clock by
     * exactly N, no matter which of those rows the engine's own body happened
     * to tick on. The mix below is the shape measured at the S2
     * {@code seg4_ehz1 -> seg5_ehz2} boundary — a run of suppressed fade rows
     * inside a majority of self-ticking rows.
     */
    @Test
    @DisplayName("Every transition row reaches the tick: N rows advance the clock by N")
    void everyTransitionRowReachesTheTick() {
        boolean[] engineTickedTheRowItself = new boolean[171];
        for (int row = 0; row < engineTickedTheRowItself.length; row++) {
            // Rows 0..21 are Pal_FadeToBlack's 22 WaitForVint passes, which the
            // engine spends with the level body suppressed; row 170 is the
            // gap's last row. Every other row's body ticks for itself.
            engineTickedTheRowItself[row] =
                    row >= 22 && row < engineTickedTheRowItself.length - 1;
        }

        int counter = 0x2000;
        for (boolean bodyTicked : engineTickedTheRowItself) {
            int before = counter;
            if (bodyTicked) {
                counter++;
            }
            if (AbstractRunChainTest.suppressedRowOwesVint(true, before, counter)) {
                counter++;
            }
            assertEquals(before + 1, counter,
                    "every transition row must end one V-blank ahead of where it started");
        }
        assertEquals(0x2000 + engineTickedTheRowItself.length, counter,
                "a gap of N rows must advance the object-visible clock by exactly N");
    }
}
