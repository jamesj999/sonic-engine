package com.openggf.game.sonic2;

import com.openggf.game.sonic2.constants.Sonic2AnimationIds;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.ObjectControlState;
import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestSonic2WfzWindTunnel {

    @Test
    void platingHoldingFlagSkipsWindTunnelLeaveAnimation() throws Exception {
        Sonic2ZoneFeatureProvider provider = new Sonic2ZoneFeatureProvider();
        TestablePlayableSprite player = new TestablePlayableSprite(
                "sonic", (short) 0x1710, (short) 0x04C0);

        invokeWindTunnel(provider, player);
        assertEquals(Sonic2AnimationIds.FLOAT2.id(), player.getAnimationId());

        player.setAnimationId(Sonic2AnimationIds.HANG);
        ObjectControlState.nativeBits0To6CpuAllowedMovementSuppressed().applyTo(player);
        provider.setWfzWindTunnelHolding(true);
        invokeWindTunnel(provider, player);

        assertTrue(provider.isWfzWindTunnelHolding());
        assertEquals(Sonic2AnimationIds.HANG.id(), player.getAnimationId(),
                "WindTunnel_holding_flag returns before the active tunnel's Walk leave write");
    }

    @Test
    void clearingHoldingFlagRestoresNormalWindTunnelLeavePath() throws Exception {
        Sonic2ZoneFeatureProvider provider = new Sonic2ZoneFeatureProvider();
        TestablePlayableSprite player = new TestablePlayableSprite(
                "sonic", (short) 0x1710, (short) 0x04C0);

        invokeWindTunnel(provider, player);
        player.setAnimationId(Sonic2AnimationIds.HANG);
        provider.setWfzWindTunnelHolding(false);
        player.setCentreX((short) 0x1AF0);
        invokeWindTunnel(provider, player);

        assertEquals(Sonic2AnimationIds.WALK.id(), player.getAnimationId());
    }

    private static void invokeWindTunnel(
            Sonic2ZoneFeatureProvider provider, AbstractPlayableSprite player) throws Exception {
        Method method = Sonic2ZoneFeatureProvider.class.getDeclaredMethod(
                "updateWfzWindTunnel", AbstractPlayableSprite.class);
        method.setAccessible(true);
        method.invoke(provider, player);
    }
}
