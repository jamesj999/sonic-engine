package com.openggf.game.sonic3k.objects;

import com.openggf.game.ShieldType;
import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestS3kShieldPriorityParity {

    @Test
    void fireShield_movesBehindPlayerForRearFrames() throws Exception {
        FireShieldObjectInstance shield = new FireShieldObjectInstance(testPlayer());

        setCurrentMappingFrame(shield, 0x0E);
        assertEquals(1, shield.getPriorityBucket());

        setCurrentMappingFrame(shield, 0x0F);
        assertEquals(4, shield.getPriorityBucket());
    }

    @Test
    void lightningShield_movesBehindPlayerForRearFrames() throws Exception {
        LightningShieldObjectInstance shield = new LightningShieldObjectInstance(testPlayer());

        setCurrentMappingFrame(shield, 0x0D);
        assertEquals(1, shield.getPriorityBucket());

        setCurrentMappingFrame(shield, 0x0E);
        assertEquals(4, shield.getPriorityBucket());
    }

    @Test
    void bubbleShield_staysInFrontBucket() throws Exception {
        BubbleShieldObjectInstance shield = new BubbleShieldObjectInstance(testPlayer());

        setCurrentMappingFrame(shield, 0x00);
        assertEquals(1, shield.getPriorityBucket());

        setCurrentMappingFrame(shield, 0x12);
        assertEquals(1, shield.getPriorityBucket());
    }

    private static TestablePlayableSprite testPlayer() {
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0, (short) 0);
        player.setShieldStateForTest(true, ShieldType.BASIC);
        return player;
    }

    private static void setCurrentMappingFrame(Object shield, int frame) throws Exception {
        Field field = shield.getClass().getDeclaredField("currentMappingFrame");
        field.setAccessible(true);
        field.setInt(shield, frame);
    }
}


