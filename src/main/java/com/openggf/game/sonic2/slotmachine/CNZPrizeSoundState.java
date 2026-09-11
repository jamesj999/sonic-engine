package com.openggf.game.sonic2.slotmachine;

import com.openggf.game.rewind.RewindSnapshottable;

/** Session-owned CNZ use of the ROM's shared Bonus_Countdown_3 word. */
public final class CNZPrizeSoundState implements RewindSnapshottable<Integer> {
    public static final String REWIND_KEY = "sonic2.cnz-prize-sound";
    private int countdown;

    /** ObjD6 loc_2BD48 runs on every bomb-payout update that does not eject. */
    public void advanceBombPayout() {
        countdown = (countdown + 1) & 0xFFFF;
    }

    /** ObjD3 compares before clearing; impacts do not increment the word. */
    public boolean consumeSpikeSound() {
        if (countdown < 5) {
            return false;
        }
        countdown = 0;
        return true;
    }

    @Override public String key() { return REWIND_KEY; }
    @Override public Integer capture() { return countdown; }
    @Override public void restore(Integer snapshot) { countdown = snapshot == null ? 0 : snapshot & 0xFFFF; }
    @Override public void resetForMissingSnapshot() { countdown = 0; }
}
