package com.openggf.game.sonic3k;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class TestSonic3kLoadProfileSelection {

    @Test
    public void usesZoneActProfileForAiz1IntroBootstrap() {
        int index = Sonic3k.resolveLevelLoadBlockIndex(
                0,
                0,
                new Sonic3kLoadBootstrap(Sonic3kLoadBootstrap.Mode.INTRO, new int[]{0x40, 0x420}));
        assertEquals(0, index);
    }

    @Test
    public void usesIntroProfileForAiz1SkipIntroBootstrap() {
        int index = Sonic3k.resolveLevelLoadBlockIndex(
                0,
                0,
                new Sonic3kLoadBootstrap(Sonic3kLoadBootstrap.Mode.SKIP_INTRO, null));
        assertEquals(26, index);
    }

    @Test
    public void keepsZoneActProfileOutsideAiz1() {
        int index = Sonic3k.resolveLevelLoadBlockIndex(
                1,
                1,
                new Sonic3kLoadBootstrap(Sonic3kLoadBootstrap.Mode.INTRO, null));
        assertEquals(3, index);
    }
}


