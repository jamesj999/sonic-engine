package com.openggf.game.sonic3k.objects;

import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.level.render.PatternSpriteRenderer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestStillSpriteInstance {

    @BeforeEach
    void resetCameraBounds() {
        AbstractObjectInstance.resetCameraBoundsForTests();
    }

    @AfterEach
    void restoreCameraBounds() {
        AbstractObjectInstance.resetCameraBoundsForTests();
    }

    @ParameterizedTest(name = "subtype ${0}")
    @CsvSource({
            "0x18, 4, 16, 1, true",
            "0x19, 4, 16, 1, true",
            "0x1A, 16, 4, 1, true",
            "0x1B, 16, 8, 1, true",
            "0x1C, 16, 8, 1, true",
            "0x1D, 16, 8, 4, false",
            "0x1E, 8, 8, 5, false"
    })
    void mhzStillSpritesUseRomTableRenderMetadata(
            int subtype, int halfWidth, int halfHeight, int priorityBucket, boolean highPriority) {
        StillSpriteInstance sprite = new StillSpriteInstance(new ObjectSpawn(
                0x0100, 0x0080, Sonic3kObjectIds.STILL_SPRITE, subtype, 0, true, 0));

        assertEquals(halfWidth, sprite.getOnScreenHalfWidth(),
                "word_2B968 must drive width_pixels for every MHZ StillSprite subtype");
        assertEquals(halfHeight, sprite.getOnScreenHalfHeight(),
                "word_2B968 must drive height_pixels for every MHZ StillSprite subtype");
        assertEquals(priorityBucket, sprite.getPriorityBucket(),
                "word_2B968 priority must be preserved as the engine priority bucket");
        assertEquals(highPriority, sprite.isHighPriority(),
                "make_art_tile priority bit must be preserved independently from the display priority word");
    }

    @ParameterizedTest(name = "subtype ${0}")
    @MethodSource("mhzRenderSheets")
    void mhzStillSpritesRenderThroughRomBackedSheets(
            int subtype, String artKey, int localFrame, boolean highPriority) {
        ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        PatternSpriteRenderer renderer = mock(PatternSpriteRenderer.class);
        when(renderManager.getRenderer(artKey)).thenReturn(renderer);
        when(renderer.isReady()).thenReturn(true);

        StillSpriteInstance sprite = new StillSpriteInstance(new ObjectSpawn(
                0x1200, 0x0600, Sonic3kObjectIds.STILL_SPRITE, subtype, 3, true, 0));
        sprite.setServices(new TestObjectServices() {
            @Override
            public ObjectRenderManager renderManager() {
                return renderManager;
            }
        });

        sprite.appendRenderCommands(new ArrayList<GLCommand>());

        verify(renderManager).getRenderer(artKey);
        verify(renderer).drawFrameIndexForcedPriority(
                localFrame, 0x1200, 0x0600, true, true, -1, highPriority);
    }

    @Test
    void mhzStillSpriteForcesRomArtTilePriorityBitWhenRendering() {
        ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        PatternSpriteRenderer renderer = mock(PatternSpriteRenderer.class);
        when(renderManager.getRenderer(Sonic3kObjectArtKeys.STILL_MHZ_CLIFF)).thenReturn(renderer);
        when(renderer.isReady()).thenReturn(true);

        StillSpriteInstance sprite = new StillSpriteInstance(new ObjectSpawn(
                0x1200, 0x0600, Sonic3kObjectIds.STILL_SPRITE, 0x18, 0, true, 0));
        sprite.setServices(new TestObjectServices() {
            @Override
            public ObjectRenderManager renderManager() {
                return renderManager;
            }
        });

        sprite.appendRenderCommands(new ArrayList<GLCommand>());

        verify(renderer).drawFrameIndexForcedPriority(0, 0x1200, 0x0600, false, false, -1, true);
    }

    @Test
    void mhzStillSpriteDeletesRespawnablyWhenSpriteOnScreenTestFails() {
        AbstractObjectInstance.updateCameraBounds(0, 0, 320, 224, 0);
        StillSpriteInstance sprite = new StillSpriteInstance(new ObjectSpawn(
                0x0400, 0x0080, Sonic3kObjectIds.STILL_SPRITE, 0x18, 0, true, 0));

        sprite.update(0, null);

        assertTrue(sprite.isDestroyed(),
                "Obj_StillSprite ends each frame with Sprite_OnScreen_Test and deletes once outside the ROM X window");
        assertTrue(sprite.isDestroyedRespawnable(),
                "Sprite_OnScreen_Test clears the respawn bit, so placement must be allowed to spawn this object again");
    }

    private static Stream<Arguments> mhzRenderSheets() {
        return Stream.of(
                Arguments.of(0x18, Sonic3kObjectArtKeys.STILL_MHZ_CLIFF, 0, true),
                Arguments.of(0x19, Sonic3kObjectArtKeys.STILL_MHZ_CLIFF, 1, true),
                Arguments.of(0x1A, Sonic3kObjectArtKeys.STILL_MHZ_CLIFF, 2, true),
                Arguments.of(0x1B, Sonic3kObjectArtKeys.STILL_MHZ_COLUMN, 0, true),
                Arguments.of(0x1C, Sonic3kObjectArtKeys.STILL_MHZ_COLUMN, 1, true),
                Arguments.of(0x1D, Sonic3kObjectArtKeys.STILL_MHZ_VINE, 0, false),
                Arguments.of(0x1E, Sonic3kObjectArtKeys.STILL_MHZ_PEDESTAL, 0, false)
        );
    }
}
