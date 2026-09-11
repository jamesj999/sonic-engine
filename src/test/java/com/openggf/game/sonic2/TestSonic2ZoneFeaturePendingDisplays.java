package com.openggf.game.sonic2;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class TestSonic2ZoneFeaturePendingDisplays {
    @Test
    void multipleDisplaysRetainOrderAndReuseGrowOnlyStorage() {
        Sonic2ZoneFeatureProvider provider = new Sonic2ZoneFeatureProvider();
        provider.requestSlotRender(10, 20, 1, 2);
        provider.requestSlotRender(30, 40, 3, 4);
        int[] firstStorage = provider.pendingSlotRenderStorage();

        assertEquals(2, provider.pendingSlotRenderCount());
        assertEquals(10, firstStorage[0]);
        assertEquals(30, firstStorage[4]);

        provider.clearPendingSlotRenders();
        provider.requestSlotRender(50, 60, 5, 6);

        assertSame(firstStorage, provider.pendingSlotRenderStorage());
        assertEquals(1, provider.pendingSlotRenderCount());
        assertEquals(50, firstStorage[0]);
    }
}
