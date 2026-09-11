package com.openggf.game.sonic3k.scroll;

import com.openggf.data.Rom;
import com.openggf.game.GameServices;
import com.openggf.game.session.SessionManager;
import com.openggf.game.sonic3k.Sonic3kLevelEventManager;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.level.scroll.M68KMath;
import com.openggf.level.scroll.ZoneScrollHandler;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.Test;

import static com.openggf.level.scroll.M68KMath.negWord;
import static com.openggf.level.scroll.M68KMath.packScrollWords;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SwScrlMhzTest {

    @Test
    void providerUsesMhzDeformForMushroomHill() throws Exception {
        Sonic3kScrollHandlerProvider provider = new Sonic3kScrollHandlerProvider();
        provider.load(new Rom());
        ZoneScrollHandler handler = provider.getHandler(Sonic3kZoneConstants.ZONE_MHZ);
        int[] hScroll = new int[M68KMath.VISIBLE_LINES];

        handler.update(hScroll, 0x0400, 0x0400, 0, 0);

        assertEquals((short) 0x0116, handler.getVscrollFactorBG(),
                "MHZ_Deform uses Camera_Y_pos_copy*5/32+$76, not the default 1/4 BG Y");
        assertEquals(packScrollWords(negWord(0x0400), negWord(0x0180)), hScroll[0],
                "MHZ_Deform publishes Camera_X_pos_BG_copy as 3/8 of Events_fg_1");
        assertEquals(hScroll[0], hScroll[M68KMath.VISIBLE_LINES - 1],
                "MHZ_Deform finishes with PlainDeformation, so all visible lines use the same scroll word");
    }

    @Test
    void endBossArenaRepeatUsesRomLoopAdjustedEventsFg1ForBgDeform() {
        SwScrlMhz handler = new SwScrlMhz();
        int[] hScroll = new int[M68KMath.VISIBLE_LINES];

        handler.update(hScroll, 0x427C, 0x0280, 1, 1);
        handler.update(hScroll, 0x4080, 0x0280, 2, 1);

        assertEquals(0x18F0, handler.getBgCameraX() & 0xFFFF,
                "MHZ_Deform should use Adjust_BGDuringLoop Events_fg_1=$4280 after the $200 end-boss arena wrap");
        assertEquals(packScrollWords(negWord(0x4080), negWord(0x18F0)), hScroll[0],
                "PlainDeformation should use the loop-adjusted BG camera X while foreground scroll uses wrapped camera X");
    }

    @Test
    void endBossVerticalDeformUsesSub554B8BaseWhenRoutineBgIsInBossAreaRange() throws Exception {
        setUpAct2BackgroundRoutine(8);
        try {
            SwScrlMhz handler = new SwScrlMhz();
            int[] hScroll = new int[M68KMath.VISIBLE_LINES];
            int cameraY = 0x0400;

            handler.update(hScroll, 0x0400, cameraY, 0, 1);

            // loc_552E0 (asm 112938-112966): Events_routine_bg only settles back at
            // $8 on the $420<P1.y<$500 && status-bit-1 branch, which calls sub_554B8.
            short expectedBgY = (short) ((((short) (cameraY - 0x280)) * 5 / 32) + 0x180);
            assertEquals(expectedBgY, handler.getVscrollFactorBG(),
                    "sub_554B8 computes vertical BG deform as (Camera_Y_pos_copy-$280)*5/32+$180 "
                            + "while Events_routine_bg is in the boss-area range, not the standard "
                            + "MHZ_Deform CamY*5/32+$76 base");
        } finally {
            SessionManager.clear();
        }
    }

    @Test
    void routineBg4UsesSub554B8Unconditionally() throws Exception {
        setUpAct2BackgroundRoutine(4);
        try {
            SwScrlMhz handler = new SwScrlMhz();
            int[] hScroll = new int[M68KMath.VISIBLE_LINES];
            int cameraY = 0x0400;

            handler.update(hScroll, 0x0400, cameraY, 0, 1);

            // loc_55236 (asm 112910-112911): Events_routine_bg==4 calls sub_554B8
            // with no gating condition at all, unlike routine 0 or routine 8.
            short expectedBgY = (short) ((((short) (cameraY - 0x280)) * 5 / 32) + 0x180);
            assertEquals(expectedBgY, handler.getVscrollFactorBG(),
                    "loc_55236 (Events_routine_bg=4) calls sub_554B8 unconditionally");
        } finally {
            SessionManager.clear();
        }
    }

    @Test
    void routineBgCUsesStandardMhzDeformUnconditionally() throws Exception {
        setUpAct2BackgroundRoutine(0x0C);
        try {
            SwScrlMhz handler = new SwScrlMhz();
            int[] hScroll = new int[M68KMath.VISIBLE_LINES];
            int cameraY = 0x0400;

            handler.update(hScroll, 0x0400, cameraY, 0, 1);

            // loc_552F8 (asm 112977-112978): Events_routine_bg==$C calls the shared
            // MHZ_Deform, not sub_554B8, even though $C is numerically >= $8.
            short expectedBgY = (short) ((((short) cameraY) * 5 / 32) + 0x76);
            assertEquals(expectedBgY, handler.getVscrollFactorBG(),
                    "loc_552F8 (Events_routine_bg=$C) calls the shared MHZ_Deform unconditionally, "
                            + "not sub_554B8 -- a flat \"routine >= $8\" gate is not ROM-faithful here");
        } finally {
            SessionManager.clear();
        }
    }

    @Test
    void sub554B8UsesAsrFlooringNotTruncatingDivisionForNegativeDelta() throws Exception {
        setUpAct2BackgroundRoutine(0x10);
        try {
            SwScrlMhz handler = new SwScrlMhz();
            int[] hScroll = new int[M68KMath.VISIBLE_LINES];
            int cameraY = 0x0001;

            handler.update(hScroll, 0x0400, cameraY, 0, 1);

            // cameraY-$280 = -639 here. sub_554B8's asm is asr.l (arithmetic shift,
            // floors toward negative infinity), giving (short) 0x011C. A oracle using
            // Java's truncating "*5/32" division would instead compute -99 (not -100)
            // for the intermediate term and land on the wrong (short) 0x011D.
            assertEquals((short) 0x011C, handler.getVscrollFactorBG(),
                    "sub_554B8's asr.l#3/asr.l#2 shift chain floors negative deltas; "
                            + "it must not be modeled as truncating integer division");
        } finally {
            SessionManager.clear();
        }
    }

    private static void setUpAct2BackgroundRoutine(int routineBg) throws Exception {
        TestEnvironment.configureGameModuleFixture(SonicGame.SONIC_3K);
        Sonic3kLevelEventManager manager =
                (Sonic3kLevelEventManager) GameServices.module().getLevelEventProvider();
        manager.initLevel(Sonic3kZoneIds.ZONE_MHZ, 1);
        manager.getMhzEvents().setAct2BackgroundRoutineForTest(routineBg);
    }
}
