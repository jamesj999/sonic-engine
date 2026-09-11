package com.openggf.game.sonic3k.objects;

import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.level.objects.AbstractObjectInstance;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestSonic3kCollapsingPlatformFragment {

    @Test
    void fallingDispatchConsumesPriorRenderFlagBeforeMovement() {
        var fragment = fragment(0);
        AbstractObjectInstance.updateCameraBounds(0, 0, 320, 224, 0);
        fragment.update(0, null);
        assertFalse(fragment.isDestroyed(), "visible fragment should enter its falling path");

        AbstractObjectInstance.updateCameraBounds(0x400, 0, 320, 224, 0);
        fragment.refreshPostCameraRenderState();
        fragment.update(1, null);

        assertTrue(fragment.isDestroyed(),
                "loc_20620 must delete from the preceding Render_Sprites bit before MoveSprite");
    }

    @Test
    void waitDispatchDefersRenderFlagDeletionUntilFallRoutine() {
        var fragment = fragment(1);
        assertEquals(0x3C, fragment.getOnScreenHalfWidth());
        assertEquals(0x20, fragment.getOnScreenHalfHeight());
        AbstractObjectInstance.updateCameraBounds(0x400, 0, 320, 224, 0);
        fragment.refreshPostCameraRenderState();

        fragment.update(0, null);
        assertFalse(fragment.isDestroyed(),
                "loc_205CE only decrements its delay and draws even when bit 7 is clear");

        fragment.update(1, null);
        assertTrue(fragment.isDestroyed(),
                "the following loc_20620 dispatch must consume the latched off-screen bit");
    }

    private static Sonic3kCollapsingPlatformObjectInstance.CollapsingPlatformFragment fragment(int delay) {
        return new Sonic3kCollapsingPlatformObjectInstance.CollapsingPlatformFragment(
                0x100, 0x100, 0, 0, delay,
                Sonic3kObjectArtKeys.COLLAPSING_PLATFORM_AIZ1, false);
    }
}
