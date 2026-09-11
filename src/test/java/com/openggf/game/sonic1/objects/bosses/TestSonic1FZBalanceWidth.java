package com.openggf.game.sonic1.objects.bosses;

import com.openggf.game.sonic1.constants.Sonic1ObjectIds;
import com.openggf.level.objects.ObjectSpawn;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestSonic1FZBalanceWidth {

    private static Sonic1FZBossInstance boss() {
        return new Sonic1FZBossInstance(
                new ObjectSpawn(0, 0, Sonic1ObjectIds.FZ_BOSS, 0, 0, false, 0));
    }

    @Test
    void eggmanBalancesAtBossFinalObjData2RowZero() {
        // BossFinal_Main stores row 0's width byte, #64/2, into the parent's own
        // slot on REV01 (85,84,86 Boss - FZ Main, Cylinders, and Plasma
        // Balls.asm:56-58,96-101). REV00 writes obWidth there instead.
        Sonic1FZBossInstance eggman = boss();

        assertEquals(0x20, eggman.getOnScreenHalfWidth(),
                "the FZ boss's combat obActWid is BossFinal_ObjData2 row 0's #64/2");
        assertEquals(0x20, eggman.getBalanceWidthPixels(),
                "Sonic_Balance reads that byte, not the shared 16");
    }

    @Test
    void theLauncherCarriesTheRomsOwnZero() {
        // BossPlasma_Main writes obWidth where it meant obActWid and nothing
        // repairs it in either revision (:990-1001), so the slot keeps the zero
        // DeleteObject left in it (sub DeleteObject.asm:10-19).
        FZPlasmaLauncher launcher = new FZPlasmaLauncher(boss());

        assertEquals(0, launcher.getBalanceWidthPixels(),
                "the plasma launcher's obActWid is never written and stays 0");
        assertEquals(0x13, launcher.getSolidParams().halfWidth(),
                "BossPlasma_Collision d1 is #16/2+sonic_solid_width at :1022-1027");
    }

    @Test
    void aZeroWidthBalanceWindowCoversEveryStandingPosition() {
        // Sonic_Balance: d1 = obActWid + dx, d2 = 2*obActWid - 4; balance when
        // d1 < 4 or d1 >= d2 (01 Sonic.asm:422-431). At obActWid = 0 the two
        // halves are dx < 4 and dx >= -4, which between them leave no gap.
        int actWidth = new FZPlasmaLauncher(boss()).getBalanceWidthPixels();
        int d2 = 2 * actWidth - 4;
        for (int dx = -0x13; dx <= 0x13; dx++) {
            int d1 = actWidth + dx;
            assertEquals(true, d1 < 4 || d1 >= d2,
                    "ROM balances at every standable dx on a zero-width object; dx=" + dx);
        }
    }
}
