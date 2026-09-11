package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidRoutineKind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestHCZ2WallObjectInstance {

    @Test
    void movingWallDeclaresNativeSolidObjectFull2VisibilityContract() {
        HCZ2WallObjectInstance wall = new HCZ2WallObjectInstance();

        assertEquals(SolidRoutineKind.FULL_SOLID, wall.getSolidRoutineProfile().kind());
        assertTrue(wall.getSolidRoutineProfile().bypassesOffscreenSolidGate(),
                "Obj_HCZ2Wall calls SolidObjectFull2, so its collision box remains live outside render bounds");
    }

    @Test
    void sidePushRechecksEarlierEngineSlotVerticalHurtBlock() {
        HCZ2WallObjectInstance wall = new HCZ2WallObjectInstance();
        wall.setSlotIndex(17);
        Sonic3kInvisibleHurtBlockVObjectInstance block =
                new Sonic3kInvisibleHurtBlockVObjectInstance(
                        new ObjectSpawn(0x0A98, 0x07C0, 0x6B, 0x16, 0x01, false, 0));
        block.setSlotIndex(14);
        ObjectServices services = mock(ObjectServices.class);
        ObjectManager objectManager = mock(ObjectManager.class);
        PlayableEntity player = mock(PlayableEntity.class);
        when(services.objectManager()).thenReturn(objectManager);
        when(objectManager.activeObjectsOfType(Sonic3kInvisibleHurtBlockVObjectInstance.class))
                .thenReturn(List.of(block));
        wall.setServices(services);

        wall.onSolidContact(player,
                new SolidContact(false, true, false, false, true), 0);

        verify(objectManager).processImmediateInlineSolidCheckpoint(block, player, List.of());
    }
}
