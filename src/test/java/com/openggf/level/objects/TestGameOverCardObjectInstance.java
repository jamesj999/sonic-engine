package com.openggf.level.objects;

import com.openggf.control.InputActionMasks;
import com.openggf.game.JoypadPressSnapshot;
import com.openggf.game.GameOverExit;
import com.openggf.tests.SingletonResetExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shared Obj39 routine table: S1 {@code GameOverCard} (docs/s1disasm/_incObj/39
 * Game Over.asm), S2 {@code Obj39} (docs/s2disasm/s2.asm:27670-27774), S3K
 * {@code Obj_GameOver} (docs/skdisasm/sonic3k.asm:62020-62101).
 */
@ExtendWith(SingletonResetExtension.class)
class TestGameOverCardObjectInstance {

    /** {@code $80+320/2}. */
    private static final int TARGET_X = 0x120;

    /** A card whose per-game hooks are plain fields the test can set. */
    static final class Card extends AbstractGameOverCardObjectInstance {
        boolean artPending;
        int waitFrames = 12 * 60;
        boolean overPolls;
        JoypadPressSnapshot presses = JoypadPressSnapshot.NONE;
        boolean timeOver;
        int continues;
        int restarts;
        GameOverExit exit;
        int artReadyCalls;
        int waitFrameCalls;

        Card(int mappingFrame) {
            super(mappingFrame);
        }

        @Override protected boolean isArtPending() { return artPending; }
        @Override protected int waitFrames() { return waitFrames; }
        @Override protected boolean isDismissPressed(JoypadPressSnapshot p) {
            return (p.player1ActionPressed() & InputActionMasks.ACTION_ALL) != 0;
        }
        @Override protected boolean overElementPollsDismissButton() { return overPolls; }
        @Override protected void onArtReady() { artReadyCalls++; }
        @Override protected void onWaitFrame() { waitFrameCalls++; }
        @Override protected JoypadPressSnapshot currentPresses() { return presses; }
        @Override protected boolean isTimeOverFlagged() { return timeOver; }
        @Override protected int continuesRemaining() { return continues; }
        @Override protected void requestLevelRestart() { restarts++; }
        @Override protected void requestGameOverExit(GameOverExit e) { exit = e; }
    }

    private static Card card(int frame) {
        StubObjectServices services = new StubObjectServices().withIsolatedObjectManager();
        Card card = ObjectConstructionContext.construct(services, () -> new Card(frame));
        ObjectLifetimeOps.addDynamicAtReservedSlot(services.objectManager(), card, 2);
        return card;
    }

    private static void step(Card card, int frames) {
        for (int i = 0; i < frames; i++) {
            card.update(i, null);
        }
    }

    /** {@code Over_ChkPLC}: nothing happens, and nothing is drawn, until the PLC queue is empty. */
    @Test
    void routineZeroHoldsWhileArtIsPending() {
        Card card = card(AbstractGameOverCardObjectInstance.FRAME_GAME);
        card.artPending = true;
        step(card, 10);
        assertEquals(0, card.getRoutine());
        assertFalse(card.isDisplayedThisFrame());
        assertEquals(0, card.artReadyCalls);

        card.artPending = false;
        step(card, 1);
        assertEquals(1, card.artReadyCalls);
        assertEquals(2, card.getRoutine(), "Over_Main advances to Over_MoveIn on the release frame");
    }

    /** {@code move.w #$80-48,obX} then {@code +$10} per frame; the OVER word starts at {@code $80+320+48}. */
    @Test
    void wordSlidesInFromTheLeftAndOverFromTheRight() {
        Card word = card(AbstractGameOverCardObjectInstance.FRAME_GAME);
        Card over = card(AbstractGameOverCardObjectInstance.FRAME_OVER_GAME);
        step(word, 1);
        step(over, 1);
        // Over_Main falls into Over_MoveIn the same frame, so one step is already applied.
        assertEquals(0x50 + 0x10, word.getVdpX());
        assertEquals(0x1F0 - 0x10, over.getVdpX());
        assertTrue(word.isDisplayedThisFrame());
        assertTrue(over.isDisplayedThisFrame());
    }

    /**
     * ($120-$50)/$10 = 13 moves; the fourteenth frame finds obX at the target,
     * sets the timer, advances the routine and returns without DisplaySprite
     * ({@code FixBugs = 0}).
     */
    @Test
    void conjoiningFrameSetsTheTimerAndSkipsDisplay() {
        Card word = card(AbstractGameOverCardObjectInstance.FRAME_TIME);
        word.waitFrames = 8 * 60;
        step(word, 13);
        assertEquals(TARGET_X, word.getVdpX());
        assertEquals(2, word.getRoutine());
        assertTrue(word.isDisplayedThisFrame());

        step(word, 1);
        assertEquals(4, word.getRoutine());
        assertEquals(8 * 60, word.getWaitTimer());
        assertFalse(word.isDisplayedThisFrame(), "the un-fixed rts skips DisplaySprite once");

        step(word, 1);
        assertTrue(word.isDisplayedThisFrame());
        assertEquals(8 * 60 - 1, word.getWaitTimer());
    }

    /** {@code tst.w obTimeFrame; beq .changeMode}: dismisses on the frame the timer reads zero. */
    @Test
    void timerExpiryDismissesTheWordObject() {
        Card word = card(AbstractGameOverCardObjectInstance.FRAME_GAME);
        word.waitFrames = 3;
        step(word, 14); // conjoined, timer = 3
        step(word, 3);  // 2, 1, 0
        assertFalse(word.isDismissed());
        step(word, 1);
        assertTrue(word.isDismissed());
        assertEquals(GameOverExit.TITLE_SCREEN, word.exit, "no continues: id_Sega");
        assertEquals(0, word.restarts);
    }

    /** {@code tst.b (v_continues).w; bne} keeps the continue-screen mode. */
    @Test
    void continuesRouteToTheContinueScreen() {
        Card word = card(AbstractGameOverCardObjectInstance.FRAME_GAME);
        word.continues = 1;
        step(word, 14);
        word.presses = new JoypadPressSnapshot(InputActionMasks.ACTION_B, false, 0, false);
        step(word, 1);
        assertEquals(GameOverExit.CONTINUE_SCREEN, word.exit);
    }

    /** {@code tst.b (f_timeover).w; bne .restartLevel}: a time over restarts, never leaves. */
    @Test
    void timeOverRestartsTheLevel() {
        Card word = card(AbstractGameOverCardObjectInstance.FRAME_TIME);
        word.timeOver = true;
        word.continues = 3;
        step(word, 14);
        word.presses = new JoypadPressSnapshot(InputActionMasks.ACTION_A, false, 0, false);
        step(word, 1);
        assertEquals(1, word.restarts);
        assertNull(word.exit);
    }

    /** S2/S3K: {@code btst #0,mapping_frame; bne Obj39_Display} &mdash; the OVER word never decides. */
    @Test
    void overWordOnlyDisplaysUnlessTheGamePollsIt() {
        Card over = card(AbstractGameOverCardObjectInstance.FRAME_OVER_GAME);
        over.waitFrames = 0;
        step(over, 14);
        over.presses = new JoypadPressSnapshot(InputActionMasks.ACTION_C, false, 0, false);
        step(over, 5);
        assertFalse(over.isDismissed());
        assertTrue(over.isDisplayedThisFrame());

        Card s1Over = card(AbstractGameOverCardObjectInstance.FRAME_OVER_GAME);
        s1Over.overPolls = true;
        step(s1Over, 14);
        s1Over.presses = new JoypadPressSnapshot(InputActionMasks.ACTION_C, false, 0, false);
        step(s1Over, 1);
        assertTrue(s1Over.isDismissed(), "S1 Over_Wait tests the buttons before bit 0");
    }

    /** The OVER word's own timer is never counted (S1 :62-66, S2 :27725-27726). */
    @Test
    void overWordTimerNeverCountsDown() {
        Card over = card(AbstractGameOverCardObjectInstance.FRAME_OVER_GAME);
        over.waitFrames = 5;
        step(over, 14 + 50);
        assertEquals(5, over.getWaitTimer());
        assertFalse(over.isDismissed());
    }

    /** After the decision the card keeps running and keeps re-writing the same outcome. */
    @Test
    void dismissedCardKeepsDisplayingAndRepeatingItsWrite() {
        Card word = card(AbstractGameOverCardObjectInstance.FRAME_GAME);
        word.waitFrames = 0;
        step(word, 15);
        assertTrue(word.isDismissed());
        int waitCallsBefore = word.waitFrameCalls;
        word.exit = null;
        step(word, 3);
        assertEquals(GameOverExit.TITLE_SCREEN, word.exit);
        assertEquals(waitCallsBefore + 3, word.waitFrameCalls);
        assertTrue(word.isDisplayedThisFrame());
    }

    @Test
    void spawnPairLoadsWordThenOverAtTheGivenSlots() {
        StubObjectServices services = new StubObjectServices().withIsolatedObjectManager();
        AbstractGameOverCardObjectInstance.spawnPair(services, true, Card::new, 2, 3);
        Card word = null;
        Card over = null;
        for (ObjectInstance instance : services.objectManager().getActiveObjects()) {
            Card c = (Card) instance;
            if (c.getSlotIndex() == 2) word = c;
            if (c.getSlotIndex() == 3) over = c;
        }
        assertEquals(AbstractGameOverCardObjectInstance.FRAME_TIME, word.getMappingFrame());
        assertEquals(AbstractGameOverCardObjectInstance.FRAME_OVER_TIME, over.getMappingFrame());
        assertTrue(word.isTimeOverCard());
        assertTrue(over.isOverElement());
    }
}
