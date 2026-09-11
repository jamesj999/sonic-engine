package com.openggf.game.sonic3k.features;

import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.physics.Direction;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestHCZWaterSkimHandler {

    @AfterEach
    void resetSkimHandler() {
        HCZWaterSkimHandler.reset();
    }

    @Test
    void updateUsesNativeP1P2QueryAndIgnoresExtraSidekicks() {
        AbstractPlayableSprite main = skimmingCandidate();
        AbstractPlayableSprite nativeP2 = skimmingCandidate();
        AbstractPlayableSprite extraSidekick = skimmingCandidate();
        ObjectPlayerQuery query = new ObjectPlayerQuery(
                () -> main,
                () -> List.of(nativeP2, extraSidekick));

        HCZWaterSkimHandler.update(query, 0x200, 1);

        assertTrue(HCZWaterSkimHandler.isSkimActiveP1());
        assertTrue(HCZWaterSkimHandler.isSkimActiveP2());
        verify(main).setWaterSkimActive(true);
        verify(nativeP2).setWaterSkimActive(true);
        verify(extraSidekick, never()).setWaterSkimActive(anyBoolean());
    }

    @Test
    void updateClearsNativeP2SkimWhenFirstSidekickIsDeadWithoutPromotingExtraSidekick() {
        AbstractPlayableSprite main = skimmingCandidate();
        AbstractPlayableSprite nativeP2 = skimmingCandidate();
        AbstractPlayableSprite extraSidekick = skimmingCandidate();
        ObjectPlayerQuery activeQuery = new ObjectPlayerQuery(
                () -> main,
                () -> List.of(nativeP2, extraSidekick));
        HCZWaterSkimHandler.update(activeQuery, 0x200, 1);
        when(nativeP2.getDead()).thenReturn(true);
        ObjectPlayerQuery deadNativeP2Query = new ObjectPlayerQuery(
                () -> main,
                () -> List.of(nativeP2, extraSidekick));

        HCZWaterSkimHandler.update(deadNativeP2Query, 0x200, 2);

        assertFalse(HCZWaterSkimHandler.isSkimActiveP2());
        verify(extraSidekick, never()).setWaterSkimActive(anyBoolean());
    }

    @Test
    void updateLeavesNativeP2SkimStateUntouchedWhenNoSidekickCandidateExists() {
        AbstractPlayableSprite main = skimmingCandidate();
        AbstractPlayableSprite nativeP2 = skimmingCandidate();
        ObjectPlayerQuery activeQuery = new ObjectPlayerQuery(
                () -> main,
                () -> List.of(nativeP2));
        HCZWaterSkimHandler.update(activeQuery, 0x200, 1);
        ObjectPlayerQuery missingNativeP2Query = new ObjectPlayerQuery(
                () -> main,
                List::of);

        HCZWaterSkimHandler.update(missingNativeP2Query, 0x200, 2);

        assertTrue(HCZWaterSkimHandler.isSkimActiveP2());
    }

    @Test
    void airborneSustainPinsAndAppliesFrictionAtPostPlayerTiming() {
        AbstractPlayableSprite main = skimmingCandidate();
        when(main.getAir()).thenReturn(true);
        when(main.getXSpeed()).thenReturn((short) 0x700);
        ObjectPlayerQuery query = new ObjectPlayerQuery(
                () -> main,
                List::of);

        HCZWaterSkimHandler.update(query, 0x200, 1);
        HCZWaterSkimHandler.update(query, 0x200, 2);

        assertTrue(HCZWaterSkimHandler.isSkimActiveP1());
        verify(main).setCentreYPreserveSubpixel((short) 0x1EF);
        verify(main).setXSpeed((short) 0x6F4);
        verify(main, never()).suppressNextGravityStep();
    }

    @Test
    void airborneSpeedExitDoesNotSuppressTheFollowingFrameGravity() {
        AbstractPlayableSprite main = skimmingCandidate();
        when(main.getAir()).thenReturn(true);
        when(main.getXSpeed()).thenReturn((short) 0x700, (short) 0x6FF);
        ObjectPlayerQuery query = new ObjectPlayerQuery(
                () -> main,
                List::of);

        HCZWaterSkimHandler.update(query, 0x200, 1);
        HCZWaterSkimHandler.update(query, 0x200, 2);

        assertFalse(HCZWaterSkimHandler.isSkimActiveP1());
        verify(main).setWaterSkimActive(false);
        verify(main, never()).suppressNextGravityStep();
    }

    @Test
    void activationUsesHorizontalVelocityToPublishFacing() {
        AbstractPlayableSprite main = skimmingCandidate();
        when(main.getDirection()).thenReturn(Direction.LEFT);
        ObjectPlayerQuery query = new ObjectPlayerQuery(
                () -> main,
                List::of);

        HCZWaterSkimHandler.update(query, 0x200, 1);

        verify(main).setDirection(Direction.RIGHT);
        assertTrue(HCZWaterSkimHandler.isSkimActiveP1());
    }

    @Test
    void jumpExitPreservesNativeCentreYWhileEnteringRoll() {
        AbstractPlayableSprite main = skimmingCandidate();
        ObjectPlayerQuery query = new ObjectPlayerQuery(() -> main, List::of);

        HCZWaterSkimHandler.update(query, 0x200, 1);
        when(main.isJumpPressed()).thenReturn(true);
        HCZWaterSkimHandler.update(query, 0x200, 2);

        assertFalse(HCZWaterSkimHandler.isSkimActiveP1());
        verify(main).setRolling(true);
        verify(main).setCentreYPreserveSubpixel((short) 0x1EF);
    }

    private static AbstractPlayableSprite skimmingCandidate() {
        AbstractPlayableSprite player = mock(AbstractPlayableSprite.class);
        when(player.getYSpeed()).thenReturn((short) 0);
        when(player.getCentreY()).thenReturn((short) 0x1EF);
        when(player.getYRadius()).thenReturn((short) 0x10);
        when(player.getXSpeed()).thenReturn((short) 0x700);
        when(player.getDead()).thenReturn(false);
        when(player.isDebugMode()).thenReturn(false);
        when(player.getDirection()).thenReturn(Direction.RIGHT);
        return player;
    }
}
