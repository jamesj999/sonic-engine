package com.openggf.sprites.managers;

import com.openggf.data.RomByteReader;
import com.openggf.game.sonic3k.Sonic3kPlayerArt;
import com.openggf.sprites.animation.SpriteAnimationScript;
import com.openggf.sprites.animation.ScriptedVelocityAnimationProfile;
import com.openggf.sprites.art.SpriteArtSet;
import com.openggf.sprites.render.PlayerSpriteRenderer;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.TestablePlayableSprite;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@RequiresRom(SonicGame.SONIC_3K)
class TestTailsTailsFlightSelection {

    @Test
    void flyingParentAnimationsDrawSeparateTailArt() {
        TestablePlayableSprite tails = new TestablePlayableSprite("tails", (short) 0x100, (short) 0x200);
        PlayerSpriteRenderer renderer = mock(PlayerSpriteRenderer.class);
        TailsTailsController controller = new TailsTailsController(tails, renderer, true);

        for (int parentAnimation = 0x20; parentAnimation <= 0x24; parentAnimation++) {
            clearInvocations(renderer);
            tails.setAnimationId(parentAnimation);

            controller.update();
            controller.draw();

            verify(renderer).drawFrame(anyInt(), anyInt(), anyInt(), anyBoolean(), anyBoolean());
        }
    }

    @Test
    void swimmingParentAnimationsKeepSeparateTailBlank() {
        TestablePlayableSprite tails = new TestablePlayableSprite("tails", (short) 0x100, (short) 0x200);

        for (int parentAnimation = 0x25; parentAnimation <= 0x28; parentAnimation++) {
            PlayerSpriteRenderer renderer = mock(PlayerSpriteRenderer.class);
            TailsTailsController controller = new TailsTailsController(tails, renderer, true);
            tails.setAnimationId(parentAnimation);

            controller.update();
            controller.draw();

            verifyNoInteractions(renderer);
        }
    }

    @Test
    void rewindStateRestoresTheNextDynamicArtAnimationDecision() {
        TestablePlayableSprite tails =
                new TestablePlayableSprite(
                        "tails", (short) 0x100, (short) 0x200);
        TailsTailsController controller =
                new TailsTailsController(tails, null, false);
        tails.setAnimationId(5);
        controller.update();
        controller.update();
        TailsTailsController.RewindState saved =
                controller.captureRewindState();

        controller.update();
        TailsTailsController.RewindState expectedNext =
                controller.captureRewindState();
        controller.update();
        controller.update();

        controller.restoreRewindState(saved);
        controller.update();

        assertEquals(expectedNext, controller.captureRewindState(),
                "rewind must restore the Obj05 animation cursor that selects "
                        + "the next tails-tails DPLC mapping frame");
    }

    @Test
    void romFlightAndSwimScriptsResolveToMappingAndDplcFrames() throws Exception {
        SpriteArtSet tails = new Sonic3kPlayerArt(
                RomByteReader.fromRom(TestEnvironment.currentRom())).loadTails();

        for (int animationId = 0x20; animationId <= 0x28; animationId++) {
            SpriteAnimationScript script = tails.animationSet().getScript(animationId);
            assertNotNull(script, "missing animation script 0x" + Integer.toHexString(animationId));
            assertFalse(script.frames().isEmpty(), "empty animation script 0x" + Integer.toHexString(animationId));
            for (int frame : script.frames()) {
                assertTrue(frame >= 0 && frame < tails.mappingFrames().size(),
                        "mapping frame out of range for animation 0x" + Integer.toHexString(animationId));
                assertNotNull(tails.mappingFrames().get(frame));
                assertTrue(frame < tails.dplcFrames().size(),
                        "DPLC frame out of range for animation 0x" + Integer.toHexString(animationId));
                assertNotNull(tails.dplcFrames().get(frame));
            }
        }
    }

    @Test
    void s3kPlayableProfilesPublishWalkMappingBeforeTimerAdvance() throws Exception {
        Sonic3kPlayerArt loader = new Sonic3kPlayerArt(
                RomByteReader.fromRom(TestEnvironment.currentRom()));

        for (SpriteArtSet art : new SpriteArtSet[]{
                loader.loadSonic(), loader.loadTails(), loader.loadKnuckles()
        }) {
            assertTrue(((ScriptedVelocityAnimationProfile) art.animationProfile())
                    .isWalkRunPublishesFrameBeforeTimerAdvance());
        }
    }

    @Test
    void s3kTailsProfileUsesNativePrivateHighSpeedWalkTier() throws Exception {
        SpriteArtSet tails = new Sonic3kPlayerArt(
                RomByteReader.fromRom(TestEnvironment.currentRom())).loadTails();
        ScriptedVelocityAnimationProfile profile =
                (ScriptedVelocityAnimationProfile) tails.animationProfile();

        assertEquals(0x1F, profile.getHighSpeedWalkRunAnimId());
        assertEquals(0x700, profile.getHighSpeedWalkRunThreshold());
        assertEquals(1, profile.getHighSpeedSlopeFrameStride());
        assertTrue(profile.isPushUsesWalkSpecialHandler());
        assertEquals(0xC3, (int) tails.animationSet().getScript(0x1F).frames().get(0));
    }
}
