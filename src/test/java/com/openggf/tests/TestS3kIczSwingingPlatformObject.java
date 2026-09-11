package com.openggf.tests;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.objects.IczSwingingPlatformObjectInstance;
import com.openggf.game.sonic3k.objects.Sonic3kObjectRegistry;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.MultiPieceSolidProvider;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.tools.Sonic3kObjectProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestS3kIczSwingingPlatformObject {

    // Clear any gameplay session leaked by a prior test in this fork so the registry
    // resolves the S3KL zone set (not a leaked SKL zone). Parallel-suite flake fix.
    @BeforeEach
    void clearLeakedGameplaySession() {
        com.openggf.game.session.SessionManager.clear();
    }

    @Test
    void registryCreatesIczSwingingPlatformInstance() {
        ObjectInstance instance = new Sonic3kObjectRegistry().create(
                new ObjectSpawn(0x1200, 0x0700, Sonic3kObjectIds.ICZ_SWINGING_PLATFORM, 0, 0, false, 0));

        assertInstanceOf(IczSwingingPlatformObjectInstance.class, instance);
    }

    @Test
    void objectExposesRomSolidPieces() {
        IczSwingingPlatformObjectInstance platform = new IczSwingingPlatformObjectInstance(
                new ObjectSpawn(0x1200, 0x0700, Sonic3kObjectIds.ICZ_SWINGING_PLATFORM, 0, 0, false, 0));

        assertInstanceOf(MultiPieceSolidProvider.class, platform);
        assertEquals(2, platform.getPieceCount());

        SolidObjectParams lower = platform.getPieceParams(0);
        assertEquals(0x1200, platform.getPieceX(0));
        assertEquals(0x0708, platform.getPieceY(0));
        assertEquals(0x2B, lower.halfWidth());
        assertEquals(0x20, platform.getPieceLandingHalfWidth(0));
        assertEquals(8, lower.airHalfHeight());
        assertEquals(8, lower.groundHalfHeight());

        SolidObjectParams upper = platform.getPieceParams(1);
        assertEquals(0x121C, platform.getPieceX(1));
        assertEquals(0x06F8, platform.getPieceY(1));
        assertEquals(0x0F, upper.halfWidth());
        assertEquals(0x30, platform.getPieceLandingHalfWidth(1));
        assertEquals(8, upper.airHalfHeight());
        assertEquals(8, upper.groundHalfHeight());

        assertEquals(0x1200, platform.getX());
        assertEquals(0x0700, platform.getY());
        assertEquals(1, platform.getPriorityBucket());
        assertTrue(platform.usesPieceScopedStandingBits());
        assertTrue(platform.airborneStaleStandingBitReturnsNoContact(mock(PlayableEntity.class)));
        assertTrue(platform.sidekickCpuStalePushGraceKeepsFollowSteeringWhileRiding(
                mock(PlayableEntity.class)));
        assertFalse(platform.usesCollisionHalfWidthForTopLanding(),
                "SolidObjectFull re-reads each child slot's width_pixels after the broad overlap");
        assertTrue(platform.usesInclusiveRightEdge(),
                "SolidObjectFull's broad child overlap rejects only values above the right edge");
    }

    @Test
    void hFlipMirrorsAdjustedUpperSolidPiece() {
        IczSwingingPlatformObjectInstance platform = new IczSwingingPlatformObjectInstance(
                new ObjectSpawn(0x1200, 0x0700, Sonic3kObjectIds.ICZ_SWINGING_PLATFORM, 0, 1, false, 0));

        assertEquals(0x11E4, platform.getPieceX(1));
        assertEquals(0x06F8, platform.getPieceY(1));
    }

    @Test
    void standingHighVelocityTriggerStartsSwingAndClampsPlayerVelocity() {
        IczSwingingPlatformObjectInstance platform = new IczSwingingPlatformObjectInstance(
                new ObjectSpawn(0x1200, 0x0700, Sonic3kObjectIds.ICZ_SWINGING_PLATFORM, 0, 0, false, 0));
        PlayableEntity player = mock(PlayableEntity.class);
        when(player.getXSpeed()).thenReturn((short) 0x1000);

        platform.onPieceContact(0, player, standingContact(), 0);
        assertEquals(0x2B, platform.getPieceLandingHalfWidth(0),
                "the armed child keeps its broad continued-contact window while its standing bit owns the rider");
        platform.update(1, player);

        assertEquals(0x1200, platform.getX(),
                "ROM loc_8AD20 only arms the swing on the trigger frame; circular motion starts next update");
        assertEquals(0x0700, platform.getY());
        platform.update(2, player);

        assertTrue(platform.getX() > 0x1200);
        verify(player).setXSpeed((short) 0x0800);
        verify(player).setGSpeed((short) 0x0800);
    }

    @Test
    void continuedStandingTriggerHalvesSpeedBeforeSwingClamp() {
        IczSwingingPlatformObjectInstance platform = new IczSwingingPlatformObjectInstance(
                new ObjectSpawn(0x1200, 0x0700, Sonic3kObjectIds.ICZ_SWINGING_PLATFORM, 0, 0, false, 0));
        PlayableEntity player = mock(PlayableEntity.class);
        when(player.getXSpeed()).thenReturn((short) 0x08F8);

        platform.onPieceContact(0, player, standingContact(), 0, true);

        verify(player).setXSpeed((short) 0x047C);
        verify(player).setGSpeed((short) 0x047C);
    }

    @Test
    void wrongDirectionDoesNotStartSwing() {
        IczSwingingPlatformObjectInstance platform = new IczSwingingPlatformObjectInstance(
                new ObjectSpawn(0x1200, 0x0700, Sonic3kObjectIds.ICZ_SWINGING_PLATFORM, 0, 0, false, 0));
        PlayableEntity player = mock(PlayableEntity.class);
        when(player.getXSpeed()).thenReturn((short) -0x0400);

        platform.onPieceContact(0, player, standingContact(), 0);
        platform.update(1, player);

        assertEquals(0x1200, platform.getX());
        assertEquals(0x0700, platform.getY());
        verify(player, never()).setXSpeed(org.mockito.ArgumentMatchers.anyShort());
        verify(player, never()).setGSpeed(org.mockito.ArgumentMatchers.anyShort());
    }

    @Test
    void swingReturnsSmoothlyAfterVelocityChangesSign() {
        IczSwingingPlatformObjectInstance platform = new IczSwingingPlatformObjectInstance(
                new ObjectSpawn(0x1200, 0x0700, Sonic3kObjectIds.ICZ_SWINGING_PLATFORM, 0, 0, false, 0));
        PlayableEntity player = mock(PlayableEntity.class);
        when(player.getXSpeed()).thenReturn((short) 0x0800);

        platform.onPieceContact(0, player, standingContact(), 0);
        for (int frame = 1; frame <= 65; frame++) {
            platform.update(frame, player);
        }

        assertTrue(platform.getX() > 0x1200,
                "platform should still be returning from the far swing arc, not snapped to spawn");
    }

    @Test
    void releasedPlatformPreservesCircularFixedPointFraction() {
        IczSwingingPlatformObjectInstance platform = new IczSwingingPlatformObjectInstance(
                new ObjectSpawn(0x1200, 0x0700, Sonic3kObjectIds.ICZ_SWINGING_PLATFORM, 1, 0, false, 0));
        PlayableEntity player = mock(PlayableEntity.class);
        when(player.getXSpeed()).thenReturn((short) 0x0800);

        platform.onPieceContact(0, player, standingContact(), 0);
        for (int frame = 1; frame <= 100 && platform.getX() <= 0x1280; frame++) {
            platform.update(frame, player);
        }

        assertEquals(0x1283, platform.getX());
        assertEquals(0x066A, platform.getY());
        assertEquals(0, platform.getXSubpixelForTesting());
        assertEquals(0x8000, platform.getYSubpixelForTesting(),
                "MoveSprite must retain the half-pixel produced by MoveSprite_CircularSimple");
        assertEquals(0x1283, platform.getOutOfRangeReferenceX(),
                "Sprite_CheckDeleteTouch2 checks the moving x_pos, including after chain release");
    }

    @Test
    void renderUsesRomPaletteLinesForParentAndChainChildren() {
        PatternSpriteRenderer renderer = mock(PatternSpriteRenderer.class);
        TestableIczSwingingPlatform platform = new TestableIczSwingingPlatform(
                new ObjectSpawn(0x1200, 0x0700, Sonic3kObjectIds.ICZ_SWINGING_PLATFORM, 0, 0, false, 0),
                renderer);

        platform.appendRenderCommands(new ArrayList<>());

        verify(renderer).drawFrameIndex(8, 0x1200, 0x0680, false, false, 2);
        verify(renderer).drawFrameIndex(7, 0x1200, 0x0700, false, false, 1);
        verify(renderer).drawFrameIndex(0x27, 0x1200, 0x0708, false, false, 1);
        verify(renderer).drawFrameIndex(0x27, 0x121C, 0x06F8, false, false, 1);
    }

    @Test
    void profileMarksIczSwingingPlatformImplementedForS3klOnly() {
        Sonic3kObjectProfile profile = new Sonic3kObjectProfile();

        assertTrue(profile.getImplementedIds().contains(Sonic3kObjectIds.ICZ_SWINGING_PLATFORM));
    }

    private static SolidContact standingContact() {
        return new SolidContact(true, false, false, true, false);
    }

    private static final class TestableIczSwingingPlatform extends IczSwingingPlatformObjectInstance {
        private final PatternSpriteRenderer renderer;

        private TestableIczSwingingPlatform(ObjectSpawn spawn, PatternSpriteRenderer renderer) {
            super(spawn);
            this.renderer = renderer;
        }

        @Override
        protected PatternSpriteRenderer getRenderer(String artKey) {
            assertEquals(Sonic3kObjectArtKeys.ICZ_PLATFORMS, artKey);
            return renderer;
        }
    }
}
