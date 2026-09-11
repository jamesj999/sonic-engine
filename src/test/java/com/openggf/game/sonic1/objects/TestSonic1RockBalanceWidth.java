package com.openggf.game.sonic1.objects;

import com.openggf.game.sonic1.constants.Sonic1ObjectIds;
import com.openggf.level.objects.ObjectSpawn;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestSonic1RockBalanceWidth {

    @Test
    void purpleRockBalanceUsesRomActiveWidthNotTheSharedDefault() {
        Sonic1RockObjectInstance rock = new Sonic1RockObjectInstance(
                new ObjectSpawn(0x0A00, 0x0300, Sonic1ObjectIds.ROCK, 0, 0, false, 0));

        // Rock_Main writes move.b #38/2,obActWid(a0) on the shipped FixBugs = 0
        // branch (docs/s1disasm/_incObj/3B GHZ Purple Rock.asm:20-27). Sonic_Balance
        // reads that byte off the stood-on object (01 Sonic.asm:423), and this class
        // is full-solid so the balance accessor inherits the on-screen accessor.
        assertEquals(0x13, rock.getOnScreenHalfWidth(),
                "BuildSprites culls Obj3B at its obActWid, not the shared 16");
        assertEquals(0x13, rock.getBalanceWidthPixels(),
                "Sonic_Balance reads Obj3B obActWid = #38/2");

        // Rock_Solid passes a separately authored d1; the collision width must not move.
        assertEquals(0x1B, rock.getSolidParams().halfWidth(),
                "Rock_Solid d1 is #32/2+sonic_solid_width at 3B GHZ Purple Rock.asm:31");
    }
}
