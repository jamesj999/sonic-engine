package com.openggf.game.sonic3k.objects;

import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.ObjectControlState;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

public class TestPachinkoEnergyTrapObjectInstance {

    /**
     * ROM {@code sub_49FE4} loc_49FFC..loc_4A024
     * (docs/skdisasm/sonic3k.asm:96652-96665) is the whole of the capture and writes
     * exactly {@code move.w y_pos(a0),y_pos(a1)}, {@code move.b #$81,object_control(a1)}
     * and {@code bset #Status_InAir,status(a1)}. It never clears x_vel/y_vel/ground_vel,
     * never touches {@code Ctrl_1_locked}, and never clears the on-object bit — so this
     * test pins the three ROM writes and the absence of the rest.
     */
    @Test
    public void captureWritesOnlyTheThreeRomFields() {
        PachinkoEnergyTrapObjectInstance trap = new PachinkoEnergyTrapObjectInstance(
                new ObjectSpawn(0x78, 0xF30, 0xE8, 0, 0, false, 0));
        AbstractPlayableSprite player = mock(AbstractPlayableSprite.class);
        when(player.isDebugMode()).thenReturn(false);
        when(player.getCentreY()).thenReturn((short) 0xF30);
        when(player.isObjectControlled()).thenReturn(false);

        trap.setServices(new TestObjectServices());
        trap.update(0, player);

        verify(player).applyObjectControlState(ObjectControlState.nativeBit7FullControl());
        verify(player).setCentreYPreserveSubpixel((short) 0xF30);
        verify(player).setAir(true);
        verify(player, never()).setControlLocked(anyBoolean());
        verify(player, never()).setXSpeed(anyShort());
        verify(player, never()).setYSpeed(anyShort());
        verify(player, never()).setGSpeed(anyShort());
        verify(player, never()).setOnObject(anyBoolean());
        verify(player, never()).setCentreY(anyShort());
    }

    @Test
    public void trapIsPersistent() {
        PachinkoEnergyTrapObjectInstance trap = new PachinkoEnergyTrapObjectInstance(
                new ObjectSpawn(0x78, 0xF30, 0xE8, 0, 0, false, 0));

        assertTrue(trap.isPersistent());
    }

    @Test
    public void mainCharacterEscapingOutTopRequestsImmediateExit() {
        PachinkoEnergyTrapObjectInstance trap = new PachinkoEnergyTrapObjectInstance(
                new ObjectSpawn(0x78, 0xF30, 0xE8, 0, 0, false, 0));
        AbstractPlayableSprite player = mock(AbstractPlayableSprite.class);
        when(player.isDebugMode()).thenReturn(false);
        when(player.getCentreY()).thenReturn((short) -0x21);
        when(player.isObjectControlled()).thenReturn(false);

        boolean[] exitRequested = {false};
        TestObjectServices services = new TestObjectServices() {
            @Override
            public void requestBonusStageExit() {
                exitRequested[0] = true;
            }
        };

        trap.setServices(services);
        trap.update(0, player);

        assertTrue(exitRequested[0]);
        verify(player, never()).setCentreYPreserveSubpixel(anyShort());
    }

    @Test
    public void mainCharacterAtInclusiveTopBoundaryDoesNotRequestExit() {
        PachinkoEnergyTrapObjectInstance trap = new PachinkoEnergyTrapObjectInstance(
                new ObjectSpawn(0x78, 0xF30, 0xE8, 0, 0, false, 0));
        AbstractPlayableSprite player = mock(AbstractPlayableSprite.class);
        when(player.isDebugMode()).thenReturn(false);
        when(player.getCentreY()).thenReturn((short) -0x20);
        when(player.isObjectControlled()).thenReturn(false);

        boolean[] exitRequested = {false};
        TestObjectServices services = new TestObjectServices() {
            @Override
            public void requestBonusStageExit() {
                exitRequested[0] = true;
            }
        };

        trap.setServices(services);
        trap.update(0, player);

        assertFalse(exitRequested[0]);
    }

    @Test
    public void trapRisesUntilMainPlayerCaptureThenStopsRising() {
        PachinkoEnergyTrapObjectInstance trap = new PachinkoEnergyTrapObjectInstance(
                new ObjectSpawn(0x78, 0xF30, 0xE8, 0, 0, false, 0));
        AtomicInteger playerY = new AtomicInteger(0x2000);
        AbstractPlayableSprite player = mock(AbstractPlayableSprite.class);
        when(player.isDebugMode()).thenReturn(false);
        when(player.getCentreY()).thenAnswer(invocation -> (short) playerY.get());
        when(player.isObjectControlled()).thenReturn(false);

        trap.setServices(new TestObjectServices());

        for (int frame = 0; frame <= 240; frame++) {
            trap.update(frame, player);
        }
        assertEquals(0xF2F, trap.getY());

        playerY.set(trap.getY());
        trap.update(241, player);
        int yAfterCapture = trap.getY();

        for (int frame = 242; frame < 260; frame++) {
            trap.update(frame, player);
        }
        assertEquals(yAfterCapture, trap.getY());
    }
}
