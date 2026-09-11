package com.openggf.game.sonic1.events;

import org.junit.jupiter.api.Test;
import com.openggf.physics.SensorResult;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestSonic1LZWaterEventsClock {

    @Test
    void preObjectWaterFeaturesObserveTheVblankThatObjectExecutionWillPublish() {
        assertEquals(0xFF, Sonic1LZWaterEvents.romVisibleVblaByteBeforeObjectExecution(0x50FF));
        assertEquals(0x00, Sonic1LZWaterEvents.romVisibleVblaByteBeforeObjectExecution(0x5100));
    }

    @Test
    void airborneTunnelPlayerDoesNotLandAtARecordedRouteCoordinate() throws Exception {
        Sonic1LZWaterEvents events = new Sonic1LZWaterEvents();
        events.init(3, 2);
        setBoolean(events, "windTunnelActive", true);
        setBoolean(events, "windTunnelPreserveGroundContact", false);

        AbstractPlayableSprite player = mock(AbstractPlayableSprite.class);
        when(player.getCentreX()).thenReturn((short) 0x112C);
        SensorResult support = mock(SensorResult.class);
        when(support.distance()).thenReturn((byte) 0);
        when(support.angle()).thenReturn((byte) 0);

        assertFalse(events.allowsFlatZeroDistanceLanding(player, support));
    }

    @Test
    void waterFeaturesReadThePriorLogicalPadBeforeSonicCopiesCurrentHardwareInput() {
        AbstractPlayableSprite player = mock(AbstractPlayableSprite.class);
        when(player.getLogicalInputState()).thenReturn(AbstractPlayableSprite.INPUT_DOWN);
        when(player.isDownPressed()).thenReturn(false);

        assertTrue(Sonic1LZWaterEvents.isLogicalDirectionHeld(
                player, AbstractPlayableSprite.INPUT_DOWN));
    }

    private static void setBoolean(Object target, String fieldName, boolean value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.setBoolean(target, value);
    }
}
