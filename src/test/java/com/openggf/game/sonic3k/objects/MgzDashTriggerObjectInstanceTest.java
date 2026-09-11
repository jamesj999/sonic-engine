package com.openggf.game.sonic3k.objects;

import com.openggf.game.sonic3k.constants.Sonic3kAnimationIds;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SlopedSolidProvider;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class MgzDashTriggerObjectInstanceTest {

    @Test
    void appendRenderCommandsWhileArmedDrawsMainAndChildSpriteFrames() {
        PatternSpriteRenderer renderer = mock(PatternSpriteRenderer.class);
        MGZDashTriggerObjectInstance trigger = new TestDashTrigger(renderer,
                new ObjectSpawn(0x1200, 0x0340, 0x59, 0, 0, false, 0));
        trigger.setServices(new TestObjectServices());

        AbstractPlayableSprite player = mock(AbstractPlayableSprite.class);
        doReturn(Sonic3kAnimationIds.SPINDASH.id()).when(player).getAnimationId();
        doReturn(true).when(player).getSpindash();
        doReturn((short) 0x1200).when(player).getCentreX();
        doReturn((short) 0x0340).when(player).getCentreY();
        doReturn((short) 9).when(player).getXRadius();
        doReturn((short) 19).when(player).getYRadius();

        trigger.update(1, player);
        trigger.appendRenderCommands(new ArrayList<>());

        ArgumentCaptor<Integer> frameCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(renderer, times(2)).drawFrameIndex(frameCaptor.capture(),
                eq(0x1200), eq(0x0340), eq(false), eq(false));

        assertEquals(2, frameCaptor.getAllValues().size());
        assertTrue(frameCaptor.getAllValues().stream().anyMatch(frame -> frame >= 4),
                "Armed trigger should render its main sprite frame");
        assertTrue(frameCaptor.getAllValues().stream().anyMatch(frame -> frame >= 0 && frame < 4),
                "Armed trigger should also render the child shine frame");
    }

    @Test
    void dashTriggerExposesRomSlopeTableForRidingPlayers() {
        MGZDashTriggerObjectInstance trigger = new MGZDashTriggerObjectInstance(
                new ObjectSpawn(0x0950, 0x0E04, Sonic3kObjectIds.MGZ_DASH_TRIGGER,
                        0x04, 0, false, 0));

        SlopedSolidProvider sloped = assertInstanceOf(SlopedSolidProvider.class, trigger,
                "ROM Obj_MGZDashTrigger calls sub_1DD0E with byte_25F0E "
                        + "(sonic3k.asm:51489-51493,51611-51639)");
        int relX = 0x0948 - trigger.getX() + trigger.getSolidParams().halfWidth();
        int sampleX = relX >> 1;

        assertEquals(0x0F, sloped.getSlopeData()[sampleX],
                "MGZ F1451 rides sample 9 from byte_25F0E, not the flat 0x10 top");
    }

    @Test
    void dashTriggerSlopeFlipFollowsStatusBitZero() {
        MGZDashTriggerObjectInstance trigger = new MGZDashTriggerObjectInstance(
                new ObjectSpawn(0x0950, 0x0E04, Sonic3kObjectIds.MGZ_DASH_TRIGGER,
                        0x04, 1, false, 0));

        SlopedSolidProvider sloped = assertInstanceOf(SlopedSolidProvider.class, trigger);

        assertTrue(sloped.isSlopeFlipped(),
                "SolidObjSloped2 mirrors samples when object status bit 0 is set "
                        + "(sonic3k.asm:41730-41737)");
    }

    @Test
    void dashTriggerUsesRomSlopeEntryBoundsForNewContacts() {
        MGZDashTriggerObjectInstance trigger = new MGZDashTriggerObjectInstance(
                new ObjectSpawn(0x0950, 0x0E04, Sonic3kObjectIds.MGZ_DASH_TRIGGER,
                        0x04, 0, false, 0));

        SlopedSolidProvider sloped = assertInstanceOf(SlopedSolidProvider.class, trigger);

        assertTrue(sloped.usesSlopeForNewLanding(),
                "sub_1DD0E enters loc_1DECE and samples byte_25F0E for a new contact");
        assertTrue(sloped.addsSlopeCatchRangeToVerticalOverlap(),
                "loc_1DECE adds the caller's $10 catch range to the player radius");
        assertTrue(trigger.usesInclusiveRightEdge(),
                "loc_1DECE uses bhi, so relX == d1*2 remains a valid sample");
        assertTrue(trigger.bypassesOffscreenSolidGate(),
                "sub_1DD0E bypasses SolidObjectFull's render_flags bit-7 gate");
        assertEquals(0x0002, trigger.romObjectCodePointerHighWord());
    }

    private static final class TestDashTrigger extends MGZDashTriggerObjectInstance {
        private final PatternSpriteRenderer renderer;

        private TestDashTrigger(PatternSpriteRenderer renderer, ObjectSpawn spawn) {
            super(spawn);
            this.renderer = renderer;
        }

        @Override
        protected PatternSpriteRenderer getRenderer(String artKey) {
            return renderer;
        }
    }
}
