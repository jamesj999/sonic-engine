package com.openggf.game.sonic1.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.session.SessionManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.SharedLevel;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression test for the SBZ Rotating Junction (object 0x66) preserving the
 * player's subpixel fraction when calling Jun_ChgPos.
 *
 * <p>The ROM's Jun_ChgPos uses {@code move.w d0,obX(a1)} / {@code move.w d0,obY(a1)},
 * which writes only the upper word (pixel) of the position fields and preserves
 * the lower word (obSubpixelX / obSubpixelY). See
 * {@code docs/s1disasm/_incObj/66 Rotating Junction.asm} lines 167-173.
 *
 * <p>Previously the engine implementation called {@link com.openggf.sprites.AbstractSprite#setCentreX(short)}
 * / {@code setCentreY(short)} which zero the subpixel field, causing a sub-pixel
 * desync when the junction released the player and gravity-driven SpeedToPos resumed.
 * On the SBZ1 credits demo this surfaced as a 1-pixel y divergence at frame 285.
 *
 * <p>This test sets a non-zero player subpixel before invoking Jun_ChgPos and
 * asserts the subpixel fraction is preserved.
 */
@RequiresRom(SonicGame.SONIC_1)
public class TestSonic1JunctionSubpixelPreservation {

    private static final int ZONE_SBZ = 5;
    private static final int ACT_1 = 0;
    private static SharedLevel sharedLevel;

    @BeforeAll
    public static void loadLevel() throws Exception {
        sharedLevel = SharedLevel.load(SonicGame.SONIC_1, ZONE_SBZ, ACT_1);
    }

    @AfterAll
    public static void cleanup() {
        if (sharedLevel != null) sharedLevel.dispose();
    }

    @AfterEach
    public void tearDown() {
        SessionManager.clear();
    }

    @Test
    public void junChgPosPreservesPlayerSubpixel() throws Exception {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withSharedLevel(sharedLevel)
                .startPosition((short) 0x1500, (short) 0x0150)
                .startPositionIsCentre()
                .build();

        AbstractPlayableSprite player = fixture.sprite();
        // Seed a non-zero subpixel as if Sonic had just been running with sub_y=0x7800.
        player.setSubpixelRaw(0x1234, 0x7800);
        int xSubBefore = player.getXSubpixelRaw();
        int ySubBefore = player.getYSubpixelRaw();

        Sonic1JunctionObjectInstance junction = new Sonic1JunctionObjectInstance(
                new ObjectSpawn(0x1490, 0x0170, 0x66, 0x00, 0, false, 0));

        // Set Jun_ChgPos to use frame 4 (gap pointing down: x_off=0, y_off=+0x20)
        setMappingFrame(junction, 4);

        // Invoke private changePlayerPosition to mirror Jun_ChgPos.
        Method method = Sonic1JunctionObjectInstance.class.getDeclaredMethod(
                "changePlayerPosition", AbstractPlayableSprite.class);
        method.setAccessible(true);
        method.invoke(junction, player);

        // Player centre should be moved to (junction.x + 0, junction.y + 0x20) = (0x1490, 0x0190).
        assertEquals(0x1490, player.getCentreX() & 0xFFFF, "centreX after Jun_ChgPos frame 4");
        assertEquals(0x0190, player.getCentreY() & 0xFFFF, "centreY after Jun_ChgPos frame 4");

        // ROM-accurate subpixel preservation: Jun_ChgPos uses move.w to obX/obY,
        // which leaves obSubpixelX/Y untouched. Engine must mirror this.
        assertEquals(xSubBefore, player.getXSubpixelRaw(),
                "X subpixel must be preserved across Jun_ChgPos");
        assertEquals(ySubBefore, player.getYSubpixelRaw(),
                "Y subpixel must be preserved across Jun_ChgPos");
    }

    private static void setMappingFrame(Sonic1JunctionObjectInstance junction, int frame)
            throws Exception {
        java.lang.reflect.Field field =
                Sonic1JunctionObjectInstance.class.getDeclaredField("mappingFrame");
        field.setAccessible(true);
        field.setInt(junction, frame);
    }
}
