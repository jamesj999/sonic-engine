package com.openggf.tests;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.objects.Sonic3kInvisibleHurtBlockVObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SolidContact;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestS3kInvisibleHurtBlockV {

    @Test
    public void solidObjectFull2KeepsCollisionLiveOutsideRenderBounds() {
        Sonic3kInvisibleHurtBlockVObjectInstance block =
                new Sonic3kInvisibleHurtBlockVObjectInstance(
                        new ObjectSpawn(0x0A98, 0x07C0, 0x6B, 0x16, 0x01, false, 0));

        assertTrue(block.bypassesOffscreenSolidGate());
        assertTrue(block.usesInclusiveRightEdge());
    }

    @Test
    public void standingContactKillsPlayerOnDefaultFace() {
        Sonic3kInvisibleHurtBlockVObjectInstance block =
                new Sonic3kInvisibleHurtBlockVObjectInstance(
                        new ObjectSpawn(0x1000, 0x0800, 0x6B, 0x00, 0x00, false, 0));
        PlayableEntity player = mock(PlayableEntity.class);

        block.onSolidContact(player, new SolidContact(true, false, false, false, false), 0);

        verify(player).applyCrushDeath();
    }

    @Test
    public void sideContactKillsPlayerWhenXFlipped() {
        Sonic3kInvisibleHurtBlockVObjectInstance block =
                new Sonic3kInvisibleHurtBlockVObjectInstance(
                        new ObjectSpawn(0x1000, 0x0800, 0x6B, 0x00, 0x01, false, 0));
        PlayableEntity player = mock(PlayableEntity.class);

        block.onSolidContact(player, new SolidContact(false, true, false, false, false), 0);

        verify(player).applyCrushDeath();
    }

    @Test
    public void inactiveFaceDoesNotKillPlayer() {
        Sonic3kInvisibleHurtBlockVObjectInstance block =
                new Sonic3kInvisibleHurtBlockVObjectInstance(
                        new ObjectSpawn(0x1000, 0x0800, 0x6B, 0x00, 0x02, false, 0));
        PlayableEntity player = mock(PlayableEntity.class);

        block.onSolidContact(player, new SolidContact(true, false, false, false, false), 0);

        verifyNoInteractions(player);
    }
}

