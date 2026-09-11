package com.openggf.sprites;

import com.openggf.game.GameModuleRegistry;
import com.openggf.game.session.SessionManager;
import com.openggf.game.sonic2.Sonic2GameModule;
import com.openggf.tests.SingletonResetExtension;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(SingletonResetExtension.class)
class TestNativePositionOps {
    private TestablePlayableSprite player;

    @BeforeEach
    void setUp() {
        GameModuleRegistry.setCurrent(new Sonic2GameModule());
        TestEnvironment.activeGameplayMode();
        player = new TestablePlayableSprite("sonic", (short) 0, (short) 0);
    }

    @AfterEach
    void tearDown() {
        SessionManager.clear();
        GameModuleRegistry.reset();
    }

    @Test
    void writeXPosPreserveSubpixelWritesCentreCoordinateOnly() {
        player.setSubpixelRaw(0x1234, 0x5678);

        NativePositionOps.writeXPosPreserveSubpixel(player, 0x220);

        assertEquals(0x220, player.getCentreX());
        assertEquals(0x1234, player.getXSubpixelRaw());
        assertEquals(0x5678, player.getYSubpixelRaw());
    }

    @Test
    void writeYPosPreserveSubpixelWritesCentreCoordinateOnly() {
        player.setSubpixelRaw(0x1234, 0x5678);

        NativePositionOps.writeYPosPreserveSubpixel(player, 0x180);

        assertEquals(0x180, player.getCentreY());
        assertEquals(0x1234, player.getXSubpixelRaw());
        assertEquals(0x5678, player.getYSubpixelRaw());
    }

    @Test
    void addXPosPreserveSubpixelAddsToNativeCentreX() {
        player.setCentreXPreserveSubpixel((short) 0x100);
        player.setSubpixelRaw(0xCAFE, 0xBEEF);

        NativePositionOps.addXPosPreserveSubpixel(player, -7);

        assertEquals(0x0F9, player.getCentreX());
        assertEquals(0xCAFE, player.getXSubpixelRaw());
        assertEquals(0xBEEF, player.getYSubpixelRaw());
    }

    @Test
    void addYPosPreserveSubpixelAddsToNativeCentreY() {
        player.setCentreYPreserveSubpixel((short) 0x100);
        player.setSubpixelRaw(0xCAFE, 0xBEEF);

        NativePositionOps.addYPosPreserveSubpixel(player, 9);

        assertEquals(0x109, player.getCentreY());
        assertEquals(0xCAFE, player.getXSubpixelRaw());
        assertEquals(0xBEEF, player.getYSubpixelRaw());
    }

    @Test
    void writeXPosResetSubpixelClearsOnlyXFraction() {
        player.setSubpixelRaw(0x1111, 0x2222);

        NativePositionOps.writeXPosResetSubpixel(player, 0x240);

        assertEquals(0x240, player.getCentreX());
        assertEquals(0, player.getXSubpixelRaw());
        assertEquals(0x2222, player.getYSubpixelRaw());
    }

    @Test
    void writeYPosResetSubpixelClearsOnlyYFraction() {
        player.setSubpixelRaw(0x1111, 0x2222);

        NativePositionOps.writeYPosResetSubpixel(player, 0x1C0);

        assertEquals(0x1C0, player.getCentreY());
        assertEquals(0x1111, player.getXSubpixelRaw());
        assertEquals(0, player.getYSubpixelRaw());
    }
}
